package com.example.magicbeauty.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.nio.FloatBuffer

/**
 * 人体解析（LIP 20 类，SCHP / ResNet-101，ONNX）。
 *
 * 相比 MediaPipe 的 6 类分割，它能分开：
 *   5 Upper-clothes / 6 Dress / 8 Socks / 9 Pants / 10 Jumpsuits / 12 Skirt /
 *   16 Left-leg / 17 Right-leg / 18 Left-shoe / 19 Right-shoe
 * 这样拉腿时就能“按真实穿着”决定从哪一层开始拉：
 *   · 裙子/Dress/Jumpsuits → 以下摆为界，裙长不变、只整体上移
 *   · Pants（裤子）        → 从胯开始拉整条腿，裤子贴着腿，跟着变长是自然的
 *   · Socks（袜子）        → 不当作边界，跟着腿一起拉
 *   · Shoes（鞋）          → 脚踝以下完全不动
 *
 * 模型 68MB，首次使用会从 assets 拷到私有目录（之后直接复用）。
 * 推理在 CPU 上约 0.5–1.5 秒（473×473 输入），只在选图时跑一次，不影响滑动预览。
 */
class HumanParsingAnalyzer(context: Context) {

    private val appContext = context.applicationContext
    private var session: OrtSession? = null
    private var env: OrtEnvironment? = null
    private var failed = false

    data class Result(
        /** 裙摆/衣服下沿（GL 坐标，y 向上） */
        val hemY: Float,
        /** 下沿处半宽（按图片宽度归一化） */
        val hemHalfW: Float,
        /** 裙摆两侧比中间高多少 */
        val edgeLift: Float,
        /** 是否是裤子（裤子可以拉整条腿） */
        val isPants: Boolean,
        /** 解析到的类别名，便于排查 */
        val debug: String,
        /** 人体蒙版（非背景=255），边长 maskSize，作为变形的作用域 */
        val mask: ByteArray,
        val maskSize: Int,
        /** 以下四项是用解析结果自己推出来的位置，关键点检测失败时用来顶上 */
        val hipY: Float,
        val legTopY: Float,
        val ankleY: Float,
        val centerX: Float,
        val waistY: Float,
        val halfW: Float,
        /** 腰部实际半宽（在腰线那一行量的人体像素宽），比整个人体框准，瘦腰范围用它 */
        val waistHalfW: Float,
        /** 腿段里的人体覆盖率是否够（白丝袜+浅色墙时分割会把腿判成背景） */
        val maskReliable: Boolean,
        val hasBody: Boolean
    )

    private fun ensureSession(): OrtSession? {
        session?.let { return it }
        if (failed) return null
        return try {
            val modelFile = File(appContext.filesDir, "human_parsing.onnx")
            if (!modelFile.exists() || modelFile.length() < 10_000_000L) {
                appContext.assets.open("human_parsing.onnx").use { input ->
                    modelFile.outputStream().use { out -> input.copyTo(out, 1 shl 20) }
                }
            }
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val s = e.createSession(modelFile.absolutePath, opts)
            env = e
            session = s
            s
        } catch (_: Throwable) {
            failed = true
            null
        }
    }

    fun analyze(bitmap: Bitmap, guide: WarpGuide): Result? {
        // 关键点没检测到也要继续：后面会用解析结果自己推腿的位置
        val s = ensureSession() ?: return null
        val e = env ?: return null

        return try {
            val n = INPUT_SIZE
            val scaled = Bitmap.createScaledBitmap(bitmap, n, n, true)
            val pixels = IntArray(n * n)
            scaled.getPixels(pixels, 0, n, 0, 0, n, n)
            if (scaled !== bitmap) scaled.recycle()

            // CHW + ImageNet 归一化（SCHP 的标准预处理）
            val chw = FloatArray(3 * n * n)
            val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
            val std = floatArrayOf(0.229f, 0.224f, 0.225f)
            for (i in 0 until n * n) {
                val p = pixels[i]
                val r = ((p shr 16) and 0xff) / 255f
                val g = ((p shr 8) and 0xff) / 255f
                val b = (p and 0xff) / 255f
                chw[i] = (r - mean[0]) / std[0]
                chw[n * n + i] = (g - mean[1]) / std[1]
                chw[2 * n * n + i] = (b - mean[2]) / std[2]
            }

            val tensor = OnnxTensor.createTensor(
                e, FloatBuffer.wrap(chw), longArrayOf(1, 3, n.toLong(), n.toLong())
            )
            val inputName = s.inputNames.iterator().next()
            val result = s.run(mapOf(inputName to tensor))
            tensor.close()

            @Suppress("UNCHECKED_CAST")
            val logits = result[0].value as Array<Array<Array<FloatArray>>>
            result.close()

            val rows = logits[0]           // [20][H][W]
            val classes = ByteArray(n * n)
            for (y in 0 until n) {
                for (x in 0 until n) {
                    var best = 0
                    var bestV = rows[0][y][x]
                    for (c in 1 until rows.size) {
                        val v = rows[c][y][x]
                        if (v > bestV) {
                            bestV = v
                            best = c
                        }
                    }
                    classes[y * n + x] = best.toByte()
                }
            }

            buildResult(classes, n, guide)
        } catch (_: Throwable) {
            null
        }
    }

    /** 从 20 类结果里算出「从哪一层开始拉」，并顺手推出腿/腰的位置（不依赖关键点） */
    private fun buildResult(cls: ByteArray, n: Int, guide: WarpGuide): Result? {
        // ---- 1. 全图统计：人体外接框 + 各类的上下沿 ----
        var minX = n; var maxX = -1; var minY = n; var maxY = -1
        var clothTop = -1; var clothBottom = -1
        var skirtBottom = -1
        var skirtTop = -1
        var upperBottom = -1
        var pantsBottom = -1
        var shoeTop = -1
        var legTop = -1; var legBottom = -1

        for (y in 0 until n) {
            val base = y * n
            for (x in 0 until n) {
                val c = cls[base + x].toInt()
                if (c == C_BG) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y

                val isSkirt = c == C_SKIRT || c == C_DRESS || c == C_JUMPSUIT
                val isCloth = c == C_UPPER || c == C_COAT || c == C_PANTS || isSkirt
                if (isCloth) {
                    if (clothTop < 0 || y < clothTop) clothTop = y
                    if (y > clothBottom) clothBottom = y
                }
                if (isSkirt) {
                    if (y > skirtBottom) skirtBottom = y
                    if (skirtTop < 0 || y < skirtTop) skirtTop = y
                } else if (c == C_PANTS) {
                    if (y > pantsBottom) pantsBottom = y
                }
                if (c == C_SHOE_L || c == C_SHOE_R) {
                    if (shoeTop < 0 || y < shoeTop) shoeTop = y
                }
                if (c == C_UPPER) {
                    if (upperBottom < 0 || y > upperBottom) upperBottom = y
                }
                if (c == C_LEG_L || c == C_LEG_R) {
                    if (legTop < 0 || y < legTop) legTop = y
                    if (y > legBottom) legBottom = y
                }
            }
        }
        if (maxX < 0 || maxY < 0) return null
        // 用「人体像素实际占比」判断，不用外接框面积：
        // 手机截图里有大量 UI 文字/图标，会把框撑得很大，但人其实只占一点点。
        var personPx = 0
        for (i in 0 until n * n) if (cls[i].toInt() != C_BG) personPx++
        val personRatio = personPx.toFloat() / (n * n).toFloat()
        if (personRatio < 0.03f) return null          // 人像素太少，结果不可信

        // 下半身一个部位都没识别到（腿/裤/裙/袜/鞋全无）→ 位置是瞎猜的，别用。
        // 之前就是这种情况：模型只认出一条手臂，却据此推出了“脚踝在画面最底边”，
        // 结果 legOk 被判 false，拉腿彻底不生效。
        val hasLowerBody = skirtBottom >= 0 || pantsBottom >= 0 ||
                legTop >= 0 || shoeTop >= 0
        if (!hasLowerBody) return null

        // ---- 2. 位置：关键点优先，没有就用解析结果自己推 ----
        val poseOk = guide.hasPose && (guide.ankleY - guide.hipY) > 0.05f
        val ankleImg = if (poseOk) {
            1f - guide.ankleY
        } else {
            val row = when {
                shoeTop >= 0 -> shoeTop.toFloat() / n
                pantsBottom >= 0 -> pantsBottom.toFloat() / n
                // 没识别到鞋时，腿的最下沿其实是“脚底”。直接把脚底当脚踝，
                // 整只脚和袜子都会被拉长，还会把脚下的地板一起拖上来
                // （用户看到的“拉腿后面背景跟着动”就是这个）。
                legBottom >= 0 -> legBottom.toFloat() / n - 0.03f
                else -> maxY.toFloat() / n - 0.03f
            }
            row.coerceIn(0.02f, 0.995f)
        }
        val cx = if (poseOk) guide.bodyCenter.x else ((minX + maxX) / 2f) / n
        val halfW = if (poseOk) guide.bodyHalfW
        else (((maxX - minX) / 2f) / n).coerceIn(0.05f, 0.40f)

        // 腿段上界：长裤从腰拉、裙子从裙摆拉、只有露腿就从露腿最上沿拉
        val pantsLike = pantsBottom >= 0 && skirtBottom < 0 &&
                (ankleImg * n - pantsBottom) < n * 0.06f
        val hemImg = when {
            pantsLike -> (if (clothTop >= 0) clothTop.toFloat() else minY.toFloat()) / n
            skirtBottom >= 0 -> skirtBottom.toFloat() / n
            legTop >= 0 -> legTop.toFloat() / n
            clothBottom >= 0 -> clothBottom.toFloat() / n
            else -> (minY + (maxY - minY) * 0.5f) / n
        }
        if (ankleImg - hemImg < 0.04f) return null

        // ---- 3. 蒙版 ----
        val raw = ByteArray(n * n)
        for (i in raw.indices) if (cls[i].toInt() != C_BG) raw[i] = 255.toByte()
        val mask = featherMask(raw, n)

        val yHip = (hemImg * n).toInt().coerceIn(0, n - 1)
        val yAnkle = (ankleImg * n).toInt().coerceIn(0, n - 1)
        // 腰线：上衣下沿与裙子（连衣裙）上沿的交界，才是真正的腰。
        // 旧写法取「裙摆 - 4%」，JK 这种裙摆很低的，腰线会落到小腿上，
        // 一拉瘦腰就是在收小腿，看着像“没反应/识别到裙子上”。
        val waistImg = when {
            upperBottom >= 0 && skirtTop >= 0 ->
                (upperBottom + skirtTop) / 2f / n
            upperBottom >= 0 -> upperBottom.toFloat() / n
            skirtTop >= 0 -> skirtTop.toFloat() / n
            else -> (hemImg - 0.10f).coerceAtLeast(0.05f)
        }.coerceIn(0.08f, 0.94f)
        val waistGl = 1f - waistImg

        // 腰宽：在真正的腰线那一行量人体宽度（不用整个人体框，框里含手臂会撑太宽，
        // 瘦腰范围一宽就会把旁边的墙/门框一起拉进来）。
        val waistRow = (waistImg * n).toInt().coerceIn(0, n - 1)
        var wl = n
        var wr = -1
        for (y in (waistRow - 3).coerceAtLeast(0)..(waistRow + 3).coerceAtMost(n - 1)) {
            for (x in 0 until n) {
                if (cls[y * n + x].toInt() != C_BG) {
                    if (x < wl) wl = x
                    if (x > wr) wr = x
                }
            }
        }
        val waistHalf = if (wr > wl) ((wr - wl) / 2f / n).coerceIn(0.04f, 0.32f) else halfW

        // 腿段里的人体覆盖率：白丝袜+浅墙、白裙+白背景这类，分割会把腿判成背景，
        // 蒙版在腿上就是 0 —— 位移被整段抹掉，表现就是“拉腿完全没反应”。
        // 覆盖率太低时干脆不用蒙版（宁可背景动一点，也不能拉了没反应）。
        var hit = 0
        var tot = 0
        if (yAnkle > yHip) {
            val lx0 = ((cx - 0.22f) * n).toInt().coerceIn(0, n - 1)
            val lx1 = ((cx + 0.22f) * n).toInt().coerceIn(0, n - 1)
            for (y in yHip..yAnkle) {
                for (x in lx0..lx1) {
                    tot++
                    if (cls[y * n + x].toInt() != C_BG) hit++
                }
            }
        }
        val legCover = if (tot > 0) hit.toFloat() / tot else 0f
        val maskReliable = legCover >= 0.22f

        if (pantsLike) {
            return Result(
                hemY = 1f - ankleImg, hemHalfW = halfW, edgeLift = 0f, isPants = true,
                debug = "pants", mask = mask, maskSize = n,
                hipY = 1f - hemImg, legTopY = 1f - hemImg, ankleY = 1f - ankleImg,
                centerX = cx, waistY = waistGl, halfW = halfW,
                waistHalfW = waistHalf, maskReliable = maskReliable, hasBody = true
            )
        }

        // ---- 4. 裙摆：逐列找裙装类的最低一行 ----
        val x0 = ((cx - halfW * 0.55f) * n).toInt().coerceIn(0, n - 1)
        val x1 = ((cx + halfW * 0.55f) * n).toInt().coerceIn(0, n - 1)
        if (x1 <= x0) return null
        val cols = x1 - x0 + 1
        val hemCols = IntArray(cols) { -1 }
        for (i in 0 until cols) {
            val x = x0 + i
            var low = -1
            for (y in yHip..yAnkle) {
                val c = cls[y * n + x].toInt()
                if (c == C_SKIRT || c == C_DRESS || c == C_JUMPSUIT) low = y
            }
            hemCols[i] = low
        }
        val centerHem = median(hemCols, (cols * 0.35f).toInt().coerceIn(0, cols - 1),
            (cols * 0.65f).toInt().coerceIn(0, cols - 1))
        val edgeHem = medianEdges(hemCols, cols)
        val useHem = if (centerHem >= 0) centerHem else yHip
        var lift = ((useHem - if (edgeHem >= 0) edgeHem else useHem).toFloat()) / n
        lift = lift.coerceIn(0f, 0.10f)

        // 裙摆宽度
        var left = n
        var right = -1
        for (y in (useHem - 6).coerceAtLeast(0)..(useHem + 2).coerceAtMost(n - 1)) {
            for (x in 0 until n) {
                val c = cls[y * n + x].toInt()
                if (c == C_SKIRT || c == C_DRESS || c == C_JUMPSUIT) {
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }
        var hemHalf = if (right > left) (right - left).toFloat() / 2f / n else halfW
        if (hemHalf < halfW * 0.6f) hemHalf = halfW
        hemHalf = hemHalf.coerceIn(0.05f, 0.45f)

        val hemGl = 1f - (useHem + 1).toFloat() / n
        return Result(
            hemY = hemGl, hemHalfW = hemHalf, edgeLift = lift, isPants = false,
            debug = "hem %.2f".format(hemImg), mask = mask, maskSize = n,
            hipY = 1f - hemImg, legTopY = 1f - hemImg, ankleY = 1f - ankleImg,
            centerX = cx, waistY = waistGl, halfW = halfW,
            waistHalfW = waistHalf, maskReliable = maskReliable, hasBody = true
        )
    }

    /**
     * 蒙版膨胀 + 盒式模糊。
     * 目的：让人体内部（含轮廓）权重恒为 1，只在离开人体一小段距离后才衰减到 0。
     * 这样形变不会被自身的边缘削掉，背景依然不动。
     */
    private fun featherMask(src: ByteArray, n: Int): ByteArray {
        val grow = 4          // 膨胀半径（473 网格），约 0.85% 画面
        val blur = 6          // 模糊半径，形成柔和过渡带
        val tmp = ByteArray(n * n)
        val dst = ByteArray(n * n)

        // 横向 max
        for (y in 0 until n) {
            val base = y * n
            for (x in 0 until n) {
                var m = 0
                val x0 = (x - grow).coerceAtLeast(0)
                val x1 = (x + grow).coerceAtMost(n - 1)
                for (i in x0..x1) {
                    val v = src[base + i].toInt() and 0xff
                    if (v > m) m = v
                }
                tmp[base + x] = m.toByte()
            }
        }
        // 纵向 max
        for (x in 0 until n) {
            for (y in 0 until n) {
                var m = 0
                val y0 = (y - grow).coerceAtLeast(0)
                val y1 = (y + grow).coerceAtMost(n - 1)
                for (i in y0..y1) {
                    val v = tmp[i * n + x].toInt() and 0xff
                    if (v > m) m = v
                }
                dst[y * n + x] = m.toByte()
            }
        }
        // 可分离盒式模糊（横向 + 纵向）
        val tmp2 = ByteArray(n * n)
        for (y in 0 until n) {
            val base = y * n
            for (x in 0 until n) {
                var sum = 0
                var cnt = 0
                val x0 = (x - blur).coerceAtLeast(0)
                val x1 = (x + blur).coerceAtMost(n - 1)
                for (i in x0..x1) {
                    sum += dst[base + i].toInt() and 0xff
                    cnt++
                }
                tmp2[base + x] = (sum / cnt).toByte()
            }
        }
        for (x in 0 until n) {
            for (y in 0 until n) {
                var sum = 0
                var cnt = 0
                val y0 = (y - blur).coerceAtLeast(0)
                val y1 = (y + blur).coerceAtMost(n - 1)
                for (i in y0..y1) {
                    sum += tmp2[i * n + x].toInt() and 0xff
                    cnt++
                }
                dst[y * n + x] = (sum / cnt).toByte()
            }
        }
        return dst
    }

    private fun median(rows: IntArray, from: Int, to: Int): Int {
        val v = ArrayList<Int>()
        for (i in from..to) if (rows[i] >= 0) v.add(rows[i])
        if (v.isEmpty()) return -1
        v.sort()
        return v[v.size / 2]
    }

    private fun medianEdges(rows: IntArray, cols: Int): Int {
        val leftTo = (cols * 0.30f).toInt().coerceIn(0, cols - 1)
        val rightFrom = (cols * 0.70f).toInt().coerceIn(0, cols - 1)
        val v = ArrayList<Int>()
        for (i in 0..leftTo) if (rows[i] >= 0) v.add(rows[i])
        for (i in rightFrom until cols) if (rows[i] >= 0) v.add(rows[i])
        if (v.isEmpty()) return -1
        v.sort()
        return v[v.size / 2]
    }

    fun close() {
        try { session?.close() } catch (_: Exception) {}
        session = null
    }

    companion object {
        private const val INPUT_SIZE = 473

        // LIP 20 类索引
        private const val C_BG = 0
        private const val C_UPPER = 5
        private const val C_DRESS = 6
        private const val C_COAT = 7
        private const val C_PANTS = 9
        private const val C_JUMPSUIT = 10
        private const val C_SKIRT = 12
        private const val C_LEG_L = 16
        private const val C_LEG_R = 17
        private const val C_SHOE_L = 18
        private const val C_SHOE_R = 19
    }
}

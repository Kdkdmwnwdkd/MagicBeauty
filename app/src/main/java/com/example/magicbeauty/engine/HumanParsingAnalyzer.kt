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
        val maskSize: Int
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
        if (!guide.hasPose) return null
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

    /** 从 20 类结果里算出「从哪一层开始拉」 */
    private fun buildResult(cls: ByteArray, n: Int, guide: WarpGuide): Result? {
        val hipImg = 1f - guide.hipY
        val ankleImg = 1f - guide.ankleY
        if (ankleImg - hipImg < 0.05f) return null

        val cx = guide.bodyCenter.x
        val halfW = guide.bodyHalfW
        val x0 = ((cx - halfW * 0.55f) * n).toInt().coerceIn(0, n - 1)
        val x1 = ((cx + halfW * 0.55f) * n).toInt().coerceIn(0, n - 1)
        val yHip = (hipImg * n).toInt().coerceIn(0, n - 1)
        val yAnkle = (ankleImg * n).toInt().coerceIn(0, n - 1)
        if (x1 <= x0 || yAnkle <= yHip) return null

        // 人体蒙版：非背景（0）都算人。拉腿/瘦腰/瘦脸只在蒙版内生效，背景完全不动。
        val mask = ByteArray(n * n)
        for (i in mask.indices) if (cls[i].toInt() != C_BG) mask[i] = 255.toByte()

        // 统计下半身各类占比
        var pants = 0
        var skirt = 0
        var total = 0
        var pantsBottom = -1
        for (y in yHip..yAnkle) {
            for (x in x0..x1) {
                val c = cls[y * n + x].toInt()
                total++
                if (c == C_PANTS) {
                    pants++
                    if (y > pantsBottom) pantsBottom = y
                }
                if (c == C_SKIRT || c == C_DRESS || c == C_JUMPSUIT) skirt++
            }
        }
        if (total == 0) return null
        val pantsRatio = pants.toFloat() / total
        val skirtRatio = skirt.toFloat() / total

        // 裤子：占比够高，且裤脚基本到脚踝
        val pantsToAnkle = pantsBottom >= yAnkle - 3
        if (pantsRatio > 0.20f && pantsToAnkle && skirtRatio < 0.05f) {
            return Result(
                hemY = 1f - ankleImg, hemHalfW = halfW, edgeLift = 0f, isPants = true,
                debug = "pants %.2f".format(pantsRatio), mask = mask, maskSize = n
            )
        }

        // 裙子：逐列找“裙装类”的最低一行
        val cols = x1 - x0 + 1
        val hem = IntArray(cols) { -1 }
        for (i in 0 until cols) {
            val x = x0 + i
            var low = -1
            for (y in yHip..yAnkle) {
                val c = cls[y * n + x].toInt()
                if (c == C_SKIRT || c == C_DRESS || c == C_JUMPSUIT) low = y
            }
            hem[i] = low
        }
        val centerHem = median(hem, (cols * 0.35f).toInt().coerceIn(0, cols - 1),
            (cols * 0.65f).toInt().coerceIn(0, cols - 1))
        if (centerHem < 0) {
            // 没解析到裙装：交给调用方回退到 6 类方案
            return null
        }
        val edgeHem = medianEdges(hem, cols)
        var lift = ((centerHem - if (edgeHem >= 0) edgeHem else centerHem).toFloat()) / n
        lift = lift.coerceIn(0f, 0.10f)

        // 裙摆宽度
        var left = n
        var right = -1
        for (y in (centerHem - 6).coerceAtLeast(yHip)..centerHem) {
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

        val hemImg = (centerHem + 1).toFloat() / n
        return Result(
            hemY = 1f - hemImg,
            hemHalfW = hemHalf,
            edgeLift = lift,
            isPants = false,
            debug = "skirt %.2f".format(skirtRatio), mask = mask, maskSize = n
        )
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
        private const val C_DRESS = 6
        private const val C_PANTS = 9
        private const val C_JUMPSUIT = 10
        private const val C_SKIRT = 12
    }
}

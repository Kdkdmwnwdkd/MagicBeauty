package com.example.magicbeauty.engine

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter

/**
 * 衣服下沿分析（端侧、离线）。
 *
 * 目的：拉腿时要知道「衣服到哪儿为止」，才能决定从哪一层开始拉：
 *   · 长裤  —— 衣服一直包到脚踝，拉整条腿也不会破坏衣服（裤子本来就贴着腿）；
 *   · 裙子  —— 下摆在小腿/大腿，只能从下摆以下开始拉，否则整个裙摆会被拉长；
 *   · 短裤  —— 同裙子，下摆在大腿中部。
 *
 * 做法：用 selfie_multiclass 的「衣服」类别（第 4 类），在身体中线的竖条内
 * 自下而上找第一行衣服像素占比 ≥30% 的位置，就是衣服下沿；再取下沿附近的
 * 左右边界作为裙摆宽度（拉腿的横向权重用它，裙摆才会整体上移）。
 */
class ClothingAnalyzer(context: Context) {

    private val appContext = context.applicationContext
    private var created = false

    private val segmenter: ImageSegmenter by lazy {
        created = true
        val base = BaseOptions.builder()
            .setModelAssetPath("selfie_multiclass_256x256.tflite")
            .setDelegate(Delegate.CPU)
            .build()
        val opt = ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setOutputCategoryMask(true)
            .setOutputConfidenceMasks(false)
            .build()
        ImageSegmenter.createFromOptions(appContext, opt)
    }

    data class Info(
        /** 衣服下沿（GL 坐标，y 向上）；没检测到时为 0 */
        val hemY: Float,
        /** 下沿处的半宽（按图片宽度归一化） */
        val hemHalfW: Float,
        /** 是否长裤（下沿几乎贴到脚踝） */
        val isPants: Boolean,
        /** 裙摆两侧相对中间的抬高量（归一化，0 表示平下摆） */
        val edgeLift: Float = 0f
    )

    fun analyze(bitmap: Bitmap, guide: WarpGuide): Info? {
        if (!guide.hasPose) return null
        val mpImage = BitmapImageBuilder(bitmap).build()
        return try {
            val result = segmenter.segment(mpImage)
            val maskImage = result.categoryMask().orElse(null) ?: return null
            val mw = maskImage.width
            val mh = maskImage.height
            if (mw <= 0 || mh <= 0) return null

            val raw = ByteBufferExtractor.extract(maskImage)
            raw.rewind()
            val bytes = ByteArray(raw.remaining())
            raw.get(bytes)

            val total = mw * mh
            if (total <= 0 || bytes.size % total != 0) return null
            val ch = bytes.size / total
            if (ch !in 1..4) return null

            // WarpGuide 是 GL 坐标（y 向上），转成图像坐标（y 向下）
            val hipImg = 1f - guide.hipY
            val ankleImg = 1f - guide.ankleY
            if (ankleImg - hipImg < 0.05f) return null

            val cx = guide.bodyCenter.x
            val halfW = guide.bodyHalfW
            val x0 = ((cx - halfW * 0.55f) * mw).toInt().coerceIn(0, mw - 1)
            val x1 = ((cx + halfW * 0.55f) * mw).toInt().coerceIn(0, mw - 1)
            val yHip = (hipImg * mh).toInt().coerceIn(0, mh - 1)
            val yAnkle = (ankleImg * mh).toInt().coerceIn(0, mh - 1)
            if (x1 <= x0 || yAnkle <= yHip) return null

            // 逐列自下而上找衣服下沿。
            // 单取一个全宽的下沿会误伤 A 字裙/大摆裙：裙摆两侧比中间高，
            // 两侧就会被当成“腿”多拉一截。这里按列统计后再分区。
            // 逐列「自上而下」找第一段连续衣服的结束位置，而不是“最下面一行衣服”。
            // 原因：裙子 + 丝袜时，丝袜常被判成衣服类，若直接取最下面一行，
            // 下沿会落到脚踝 → 被误判成长裤 → 整条腿拉，裙摆又被拉长。
            // 改成“遇到超过 3% 画面高的空档就认为衣服到此为止”，裙子/短裤都能正确收边。
            val cols = x1 - x0 + 1
            val hemRows = IntArray(cols) { -1 }
            var any = false
            val gapMax = (mh * 0.03f).toInt().coerceAtLeast(2)
            for (i in 0 until cols) {
                val x = x0 + i
                var started = false
                var lastCloth = -1
                var gap = 0
                for (y in yHip..yAnkle) {
                    val isCloth = (bytes[(y * mw + x) * ch].toInt() and 0xff) == CLOTHES_CLASS
                    if (isCloth) {
                        started = true
                        lastCloth = y
                        gap = 0
                    } else if (started) {
                        gap++
                        if (gap > gapMax) break
                    }
                }
                if (lastCloth >= 0) {
                    hemRows[i] = lastCloth
                    any = true
                }
            }
            if (!any) return null

            // 中间区（靠近身体中线）与两侧区分别取中位数，得到“中间下沿”和“两侧抬高量”
            val midFrom = (cols * 0.35f).toInt().coerceIn(0, cols - 1)
            val midTo = (cols * 0.65f).toInt().coerceIn(0, cols - 1)
            val centerHem = medianOf(hemRows, midFrom, midTo)
            val edgeHem = medianOfEdges(hemRows, cols)
            if (centerHem < 0) return null
            // 图像坐标 y 向下：两侧更高 => edgeHem 更小 => lift 为正
            var lift = ((centerHem - if (edgeHem >= 0) edgeHem else centerHem).toFloat()) / mh
            lift = lift.coerceIn(0f, 0.10f)

            val hemRow = centerHem

            // 裙摆 / 裤脚宽度：在衣服下沿上方几行取左右最外沿
            var left = mw
            var right = -1
            val top = (hemRow - 8).coerceAtLeast(0)
            val bottom = (hemRow - 1).coerceAtLeast(0)
            if (bottom >= top) {
                for (y in top..bottom) {
                    val base = y * mw
                    for (x in 0 until mw) {
                        if ((bytes[(base + x) * ch].toInt() and 0xff) == CLOTHES_CLASS) {
                            if (x < left) left = x
                            if (x > right) right = x
                        }
                    }
                }
            }
            var hemHalf = if (right > left) (right - left).toFloat() / 2f / mw else halfW
            // 太窄说明只扫到一条裤腿，退回身体半宽
            if (hemHalf < halfW * 0.6f) hemHalf = halfW
            hemHalf = hemHalf.coerceIn(0.05f, 0.45f)

            val hemImg = (hemRow + 1).toFloat() / mh
            val isPants = (ankleImg - hemImg) < 0.06f

            Info(hemY = 1f - hemImg, hemHalfW = hemHalf, isPants = isPants, edgeLift = lift)
        } catch (_: Throwable) {
            null
        } finally {
            try { mpImage.close() } catch (_: Exception) {}
        }
    }

    /** 取指定列范围内有效行的中位数 */
    private fun medianOf(rows: IntArray, from: Int, to: Int): Int {
        val v = ArrayList<Int>()
        for (i in from..to) if (rows[i] >= 0) v.add(rows[i])
        if (v.isEmpty()) return -1
        v.sort()
        return v[v.size / 2]
    }

    /** 取左右两侧各 30% 列的中位数（裙摆外沿） */
    private fun medianOfEdges(rows: IntArray, cols: Int): Int {
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
        if (created) {
            try { segmenter.close() } catch (_: Exception) {}
        }
        created = false
    }

    companion object {
        private const val CLOTHES_CLASS = 4
    }
}

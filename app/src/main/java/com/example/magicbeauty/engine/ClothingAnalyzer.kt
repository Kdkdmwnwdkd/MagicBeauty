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
        val isPants: Boolean
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

            // 自下而上：第一行衣服像素占比 ≥ 30% 的，就是衣服下沿
            var hemRow = -1
            for (y in yAnkle downTo yHip) {
                var hit = 0
                var n = 0
                val base = y * mw
                for (x in x0..x1) {
                    val idx = bytes[(base + x) * ch].toInt() and 0xff
                    n++
                    if (idx == CLOTHES_CLASS) hit++
                }
                if (n > 0 && hit * 100 / n >= 30) {
                    hemRow = y
                    break
                }
            }
            if (hemRow < 0) return null

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

            Info(hemY = 1f - hemImg, hemHalfW = hemHalf, isPants = isPants)
        } catch (_: Throwable) {
            null
        } finally {
            try { mpImage.close() } catch (_: Exception) {}
        }
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

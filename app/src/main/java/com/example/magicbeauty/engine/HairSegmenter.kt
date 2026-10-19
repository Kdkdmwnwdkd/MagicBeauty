package com.example.magicbeauty.engine

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapExtractor
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter

/**
 * 头发分割（端侧、离线、免费）。
 * 使用 MediaPipe selfie_multiclass_256x256 的 6 类分割：
 *   0=背景 1=头发 2=身体皮肤 3=面部皮肤 4=衣服 5=其他
 * 只取第 1 类做掩码，这样“换发色”只作用在头发上，不会把脸和衣服一起染色。
 */
class HairSegmenter(context: Context) {

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

    /**
     * 返回与 [bitmap] 同尺寸的头发掩码（0 或 255）。
     * 没检测到头发、或模型加载失败时返回 null（调用方回退为整图处理）。
     */
    fun hairMask(bitmap: Bitmap): ByteArray? = try {
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = segmenter.segment(mpImage)
        val maskImage = result.categoryMask().orElse(null) ?: return null
        val maskBmp = BitmapExtractor.extract(maskImage) ?: return null

        val scaled = if (maskBmp.width != bitmap.width || maskBmp.height != bitmap.height) {
            // 用最近邻，保持类别序号不被插值破坏
            Bitmap.createScaledBitmap(maskBmp, bitmap.width, bitmap.height, false)
        } else maskBmp

        val px = IntArray(scaled.width * scaled.height)
        scaled.getPixels(px, 0, scaled.width, 0, 0, scaled.width, scaled.height)

        val out = ByteArray(px.size)
        var hits = 0
        for (i in px.indices) {
            // 类别掩码里 R/G/B 相同，都等于类别序号
            val category = (px[i] ushr 16) and 0xff
            if (category == HAIR_CLASS) {
                out[i] = 255.toByte()
                hits++
            }
        }
        if (hits == 0) null else out
    } catch (_: Throwable) {
        null
    }

    fun close() {
        if (created) {
            try { segmenter.close() } catch (_: Exception) {}
        }
        created = false
    }

    companion object {
        private const val HAIR_CLASS = 1
    }
}

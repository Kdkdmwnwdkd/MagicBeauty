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
 * 头发分割（端侧、离线、免费）。
 * 使用 MediaPipe selfie_multiclass_256x256 的 6 类分割：
 *   0=背景 1=头发 2=身体皮肤 3=面部皮肤 4=衣服 5=其他
 * 只取第 1 类做掩码，这样“换发色”只作用在头发上，不会把脸和衣服一起染色。
 *
 * 注意：分割结果 categoryMask 是 **ByteBuffer-backed** 的 MPImage，
 * 不能用 BitmapExtractor（它只支持 Bitmap 容器，会抛异常），要用 ByteBufferExtractor。
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
    fun hairMask(bitmap: Bitmap): ByteArray? {
        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            try {
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
                // 类别掩码通常是 1 字节/像素；少数实现会给 RGBA，这里按通道数自适应
                if (total <= 0 || bytes.size % total != 0) return null
                val channels = bytes.size / total
                if (channels !in 1..4) return null

                val bw = bitmap.width
                val bh = bitmap.height
                val out = ByteArray(bw * bh)
                var hits = 0

                // 最近邻缩放到原图尺寸（掩码后面还会做 3x3 羽化，硬边没关系）
                for (y in 0 until bh) {
                    val sy = y * mh / bh
                    val rowBase = sy * mw
                    val dstBase = y * bw
                    for (x in 0 until bw) {
                        val sx = x * mw / bw
                        val idx = bytes[(rowBase + sx) * channels].toInt() and 0xff
                        if (idx == HAIR_CLASS) {
                            out[dstBase + x] = 255.toByte()
                            hits++
                        }
                    }
                }
                if (hits == 0) null else out
            } finally {
                // 输入图像持有 native 资源，用完必须关掉
                try { mpImage.close() } catch (_: Exception) {}
            }
        } catch (_: Throwable) {
            null
        }
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

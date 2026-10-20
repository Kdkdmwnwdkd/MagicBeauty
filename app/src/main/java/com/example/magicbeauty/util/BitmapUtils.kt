package com.example.magicbeauty.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri

/**
 * 图片解码工具：按需采样 + 按 EXIF 摆正方向。
 * 直接 BitmapFactory.decodeStream 有两个通病：
 *  - 大图一次解码到内存容易 OOM；
 *  - 手机竖拍照片带 EXIF Orientation，解码出来是横的。
 * 这两个函数统一处理，并且都在调用方的 IO 线程执行（不要在 UI 线程调用）。
 */
object BitmapUtils {

    /** 从字节数组解码位图（AI 接口返回的 PNG/JPEG）。上限 4096，防恶意大图 OOM。 */
    fun fromBytes(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val maxDim = maxOf(opts.outWidth, opts.outHeight)
        var sample = 1
        while (maxDim / sample > 4096) sample *= 2
        val real = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, real)
    }

    fun decodeSampled(context: Context, uri: Uri, maxDim: Int = 2048): Bitmap? {
        // 1) 先只读尺寸
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        } catch (_: Exception) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // 2) 计算采样率（2 的幂）
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = try {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (_: Exception) {
            null
        } ?: return null

        // 3) 按 EXIF 摆正
        val oriented = applyExif(context, uri, decoded)

        // 4) 兜底再缩一次，保证不超过 maxDim
        val longer = maxOf(oriented.width, oriented.height)
        return if (longer > maxDim) {
            val s = maxDim.toFloat() / longer
            val scaled = Bitmap.createScaledBitmap(
                oriented,
                (oriented.width * s).toInt().coerceAtLeast(1),
                (oriented.height * s).toInt().coerceAtLeast(1),
                true
            )
            if (scaled !== oriented) oriented.recycle()
            scaled
        } else oriented
    }

    private fun applyExif(context: Context, uri: Uri, src: Bitmap): Bitmap {
        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return src
        }
        return try {
            val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            if (out !== src) src.recycle()
            out
        } catch (_: Exception) {
            src
        }
    }
}

package com.example.magicbeauty.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * AI 三件套的位图预处理：
 *  - 消除：把用户涂抹的区域涂成中性灰色，模型按指令“移除灰色区域并自然填充”
 *    （SiliconFlow 的编辑接口没有 mask 字段，用指令 + 中性色引导是实测可用的路子）
 *  - 扩图：模型不改变输出尺寸，所以先把画布四边 pad 大，让模型把内容补进去，
 *    拿到结果后再把 pad 出来的边按内容裁掉。
 */
object AiTools {

    /** 消除：在 [src] 副本上把 mask 白色区域涂成中性灰，返回 PNG 字节。 */
    fun compositeForErase(src: Bitmap, mask: Bitmap): ByteArray {
        val out = src.copy(Bitmap.Config.ARGB_8888, false) ?: src
        val canvas = Canvas(out)
        val gray = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9E9E9E.toInt() }
        // 用 mask 的 alpha 作 clipping：只把涂抹区涂灰
        val layer = canvas.saveLayer(0f, 0f, out.width.toFloat(), out.height.toFloat(), null)
        canvas.drawBitmap(mask, null, android.graphics.Rect(0, 0, out.width, out.height), null)
        gray.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawRect(0f, 0f, out.width.toFloat(), out.height.toFloat(), gray)
        gray.xfermode = null
        canvas.restoreToCount(layer)
        return toUploadPng(out)
    }

    /** 上传用的 PNG：超过 maxDim 先缩小（base64 体积/timeout 都友好很多） */
    fun toUploadPng(src: Bitmap, maxDim: Int = 1536): ByteArray {
        val m = max(src.width, src.height)
        val bmp = if (m > maxDim) {
            val scale = maxDim.toFloat() / m
            Bitmap.createScaledBitmap(
                src, (src.width * scale).toInt(), (src.height * scale).toInt(), true
            )
        } else src
        return ByteArrayOutputStream().use { bos ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
            bos.toByteArray()
        }
    }

    /**
     * 扩图：四边各扩 [ratio]（相对原图宽/高），新区域填中性灰。
     * @return (pad 后的图, 原图在 pad 图中的矩形 inset 比例 left/top/right/bottom，均为 0..1)
     */
    fun padForExpand(src: Bitmap, ratio: Float = 0.25f): Pair<Bitmap, FloatArray> {
        val padX = (src.width * ratio).toInt()
        val padY = (src.height * ratio).toInt()
        val w = src.width + padX * 2
        val h = src.height + padY * 2
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(0xFF9E9E9E.toInt())
        canvas.drawBitmap(src, padX.toFloat(), padY.toFloat(), null)
        val inset = floatArrayOf(
            padX.toFloat() / w, padY.toFloat() / h,
            (w - padX).toFloat() / w, (h - padY).toFloat() / h
        )
        return out to inset
    }

    /** 把 AI 输出里 pad 出来的边裁掉，只留原图对应的区域。 */
    fun cropInset(src: Bitmap, inset: FloatArray): Bitmap {
        val x = (inset[0] * src.width).toInt().coerceIn(0, src.width - 2)
        val y = (inset[1] * src.height).toInt().coerceIn(0, src.height - 2)
        val w = ((inset[2] - inset[0]) * src.width).toInt().coerceIn(1, src.width - x)
        val h = ((inset[3] - inset[1]) * src.height).toInt().coerceIn(1, src.height - y)
        return Bitmap.createBitmap(src, x, y, w, h)
    }
}

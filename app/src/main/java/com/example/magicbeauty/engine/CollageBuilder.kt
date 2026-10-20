package com.example.magicbeauty.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import kotlin.math.max

/**
 * 拼图合成：把 N 张图按网格模板 center-crop 进格子，输出一张大图。
 * 合成结果直接作为编辑器的“源图”，后面所有美化/塑形都能接着用。
 */
object CollageBuilder {

    /**
     * @param images 图片列表（按选择顺序）
     * @param cols 列数
     * @param rows 行数
     * @param gapRatio 格子间隙（相对格子边长）
     * @param bgColor 背景色（露出的缝隙）
     */
    fun build(
        images: List<Bitmap>,
        cols: Int,
        rows: Int,
        gapRatio: Float = 0.015f,
        bgColor: Int = 0xFF101218.toInt()
    ): Bitmap {
        require(images.isNotEmpty()) { "拼图至少需要一张图片" }
        val n = cols * rows
        val cell = 1000 // 每格基准边长，最后统一缩放
        val gap = (cell * gapRatio).toInt().coerceAtLeast(6)
        val w = cols * cell + (cols - 1) * gap
        val h = rows * cell + (rows - 1) * gap
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(bgColor)
        for (idx in 0 until n) {
            val bmp = images.getOrNull(idx) ?: images.last()
            val c = idx % cols
            val r = idx / cols
            val left = c * (cell + gap)
            val top = r * (cell + gap)
            canvas.drawBitmap(centerCrop(bmp, cell), left.toFloat(), top.toFloat(), null)
        }
        return out
    }

    /** 等比裁放大/缩小到刚好填满 size×size（center-crop） */
    fun centerCrop(src: Bitmap, size: Int): Bitmap {
        val scale = max(size.toFloat() / src.width, size.toFloat() / src.height)
        val m = Matrix().apply { postScale(scale, scale) }
        val scaled = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        val x = (scaled.width - size) / 2
        val y = (scaled.height - size) / 2
        return Bitmap.createBitmap(scaled, x, y, size, size)
    }

    /** 按图片数量自动选模板（自动模式） */
    fun autoTemplate(count: Int): Pair<Int, Int> = when (count) {
        1 -> 1 to 1
        2 -> 1 to 2
        3 -> 1 to 3
        4 -> 2 to 2
        5, 6 -> 2 to 3
        7, 8, 9 -> 3 to 3
        else -> 3 to 3
    }
}

package com.example.magicbeauty.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 内置贴纸：全部用 Canvas 矢量绘制，不打进 APK 体积。
 * 每款 256x256 透明位图，带 alpha；由 Overlay 段按变换混合。
 * 缓存一份，避免每次添加都重画。
 */
object Stickers {

    data class Entry(val name: String, val draw: (Canvas, Float) -> Unit)

    private const val SIZE = 256

    private val cache = HashMap<String, Bitmap>()

    val entries: List<Entry> = listOf(
        Entry("爱心") { c, s -> heart(c, s, 0xFFFF4D6D.toInt()) },
        Entry("星星") { c, s -> star(c, s, 0xFFFFD166.toInt()) },
        Entry("笑脸") { c, s -> smile(c, s) },
        Entry("墨镜") { c, s -> glasses(c, s) },
        Entry("皇冠") { c, s -> crown(c, s) },
        Entry("腮红") { c, s -> blush(c, s) },
        Entry("彩虹") { c, s -> rainbow(c, s) },
        Entry("气泡") { c, s -> bubble(c, s) },
        Entry("太阳") { c, s -> sun(c, s) },
        Entry("花朵") { c, s -> flower(c, s) },
        Entry("水滴") { c, s -> drop(c, s) },
        Entry("闪光") { c, s -> sparkle(c, s) }
    )

    fun bitmap(name: String): Bitmap = cache.getOrPut(name) {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val entry = entries.firstOrNull { it.name == name } ?: entries[0]
        entry.draw(canvas, SIZE / 2f)
        bmp
    }

    // ---------- 各款贴纸 ----------

    private fun heart(c: Canvas, s: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val path = Path()
        val r = s * 0.42f
        path.moveTo(s, s + r * 0.9f)
        path.cubicTo(s - r * 1.6f, s - r * 0.4f, s - r * 0.9f, s - r * 1.5f, s, s - r * 0.55f)
        path.cubicTo(s + r * 0.9f, s - r * 1.5f, s + r * 1.6f, s - r * 0.4f, s, s + r * 0.9f)
        path.close()
        c.drawPath(path, p)
        // 高光
        val hl = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0x55FFFFFF
        }
        c.drawCircle(s - r * 0.42f, s - r * 0.35f, r * 0.16f, hl)
    }

    private fun star(c: Canvas, s: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val path = Path()
        val R = s * 0.46f
        val r = R * 0.45f
        for (i in 0 until 10) {
            val rad = if (i % 2 == 0) R else r
            val a = -Math.PI / 2 + i * Math.PI / 5
            val x = s + rad * cos(a).toFloat()
            val y = s + rad * sin(a).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawPath(path, p)
    }

    private fun smile(c: Canvas, s: Float) {
        val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD54F.toInt() }
        c.drawCircle(s, s, s * 0.44f, face)
        val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3E2723.toInt() }
        c.drawCircle(s - s * 0.16f, s - s * 0.1f, s * 0.055f, eye)
        c.drawCircle(s + s * 0.16f, s - s * 0.1f, s * 0.055f, eye)
        val mouth = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF3E2723.toInt()
            style = Paint.Style.STROKE
            strokeWidth = s * 0.05f
            strokeCap = Paint.Cap.ROUND
        }
        val rect = RectF(s - s * 0.2f, s - s * 0.02f, s + s * 0.2f, s + s * 0.22f)
        c.drawArc(rect, 15f, 150f, false, mouth)
    }

    private fun glasses(c: Canvas, s: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1A1A2E.toInt()
            style = Paint.Style.FILL
        }
        val r = s * 0.2f
        c.drawRoundRect(RectF(s - r * 2.05f, s - r * 1.1f, s - r * 0.15f, s + r * 1.1f), r * 0.45f, r * 0.45f, p)
        c.drawRoundRect(RectF(s + r * 0.15f, s - r * 1.1f, s + r * 2.05f, s + r * 1.1f), r * 0.45f, r * 0.45f, p)
        c.drawRect(RectF(s - r * 0.2f, s - r * 0.3f, s + r * 0.2f, s - r * 0.1f), p)
        // 镜片反光
        val hl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x44FFFFFF }
        c.save()
        c.rotate(-20f, s - r * 1.1f, s)
        c.drawRoundRect(RectF(s - r * 1.9f, s - r * 0.95f, s - r * 0.9f, s - r * 0.55f), r * 0.2f, r * 0.2f, hl)
        c.restore()
    }

    private fun crown(c: Canvas, s: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFC53D.toInt()
        }
        val path = Path()
        val w = s * 0.46f
        val top = s - s * 0.4f
        val bot = s + s * 0.3f
        path.moveTo(s - w, bot)
        path.lineTo(s - w, top + s * 0.14f)
        path.lineTo(s - w * 0.5f, top + s * 0.02f)
        path.lineTo(s, top + s * 0.18f)
        path.lineTo(s + w * 0.5f, top + s * 0.02f)
        path.lineTo(s + w, top + s * 0.14f)
        path.lineTo(s + w, bot)
        path.close()
        c.drawPath(path, p)
        // 底部宝石
        val gem = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF4D6D.toInt() }
        c.drawCircle(s, bot - s * 0.1f, s * 0.06f, gem)
        val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8A317.toInt() }
        c.drawRect(RectF(s - w, bot - s * 0.1f, s + w, bot), band)
    }

    private fun blush(c: Canvas, s: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x66FF8FA3
        }
        c.drawCircle(s - s * 0.3f, s + s * 0.08f, s * 0.17f, p)
        c.drawCircle(s + s * 0.3f, s + s * 0.08f, s * 0.17f, p)
    }

    private fun rainbow(c: Canvas, s: Float) {
        val colors = intArrayOf(
            0xFFFF5A5A.toInt(), 0xFFFFA84D.toInt(), 0xFFFFE04D.toInt(),
            0xFF5AD66D.toInt(), 0xFF4DB8E0.toInt(), 0xFF8E5AFF.toInt()
        )
        val stroke = s * 0.085f
        colors.forEachIndexed { i, col ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = col
                style = Paint.Style.STROKE
                this.strokeWidth = stroke
            }
            val r = s * 0.42f - i * stroke
            c.drawArc(RectF(s - r, s - r, s + r, s + r), 180f, 180f, false, p)
        }
    }

    private fun bubble(c: Canvas, s: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        c.drawRoundRect(RectF(s * 0.1f, s * 0.12f, s * 0.9f, s * 0.62f), s * 0.18f, s * 0.18f, p)
        val path = Path()
        path.moveTo(s * 0.32f, s * 0.6f)
        path.lineTo(s * 0.26f, s * 0.86f)
        path.lineTo(s * 0.5f, s * 0.62f)
        path.close()
        c.drawPath(path, p)
    }

    private fun sun(c: Canvas, s: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC53D.toInt() }
        c.drawCircle(s, s, s * 0.28f, p)
        val ray = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFC53D.toInt()
            style = Paint.Style.STROKE
            strokeWidth = s * 0.045f
            strokeCap = Paint.Cap.ROUND
        }
        for (i in 0 until 8) {
            val a = i * Math.PI / 4
            val x1 = s + s * 0.34f * cos(a).toFloat()
            val y1 = s + s * 0.34f * sin(a).toFloat()
            val x2 = s + s * 0.46f * cos(a).toFloat()
            val y2 = s + s * 0.46f * sin(a).toFloat()
            c.drawLine(x1, y1, x2, y2, ray)
        }
    }

    private fun flower(c: Canvas, s: Float) {
        val petal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF8FB2.toInt() }
        for (i in 0 until 6) {
            val a = i * Math.PI / 3
            val cx = s + s * 0.26f * cos(a).toFloat()
            val cy = s + s * 0.26f * sin(a).toFloat()
            c.drawCircle(cx, cy, s * 0.15f, petal)
        }
        val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFE066.toInt() }
        c.drawCircle(s, s, s * 0.14f, core)
    }

    private fun drop(c: Canvas, s: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF5AC8FF.toInt()
        }
        val path = Path()
        path.moveTo(s, s - s * 0.42f)
        path.cubicTo(s + s * 0.38f, s - s * 0.05f, s + s * 0.36f, s + s * 0.34f, s, s + s * 0.34f)
        path.cubicTo(s - s * 0.36f, s + s * 0.34f, s - s * 0.38f, s - s * 0.05f, s, s - s * 0.42f)
        path.close()
        c.drawPath(path, p)
        val hl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
        c.drawCircle(s - s * 0.1f, s + s * 0.05f, s * 0.06f, hl)
    }

    private fun sparkle(c: Canvas, s: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFF3B0.toInt()
        }
        fun four(x: Float, y: Float, r: Float) {
            val path = Path()
            path.moveTo(x, y - r)
            path.quadTo(x + r * 0.18f, y - r * 0.18f, x + r, y)
            path.quadTo(x + r * 0.18f, y + r * 0.18f, x, y + r)
            path.quadTo(x - r * 0.18f, y + r * 0.18f, x - r, y)
            path.quadTo(x - r * 0.18f, y - r * 0.18f, x, y - r)
            path.close()
            c.drawPath(path, p)
        }
        four(s, s, s * 0.42f)
        four(s + s * 0.3f, s - s * 0.3f, s * 0.16f)
        four(s - s * 0.28f, s + s * 0.26f, s * 0.12f)
    }

    /** 文字贴纸：Canvas 排班后生成位图（带描边，保证浅色背景上也看得清） */
    fun textBitmap(text: String, color: Int): Bitmap {
        val pad = 24
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = 96f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
        val widths = text.split("\n").map { tp.measureText(it) }
        val w = (widths.maxOrNull() ?: 0f).toInt() + pad * 2
        val lineH = (tp.descent() - tp.ascent()).toInt()
        val h = lineH * text.split("\n").size + pad * 2
        val bmp = Bitmap.createBitmap(maxOf(w, 64), maxOf(h, 64), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val stroke = Paint(tp).apply {
            style = Paint.Style.STROKE
            strokeWidth = 8f
            this.color = 0xCC000000.toInt()
        }
        var y = pad - tp.ascent()
        text.split("\n").forEachIndexed { i, line ->
            val x = pad + ((w - pad * 2) - widths[i]) / 2f
            c.drawText(line, x, y, stroke)
            c.drawText(line, x, y, tp)
            y += lineH
        }
        return bmp
    }
}

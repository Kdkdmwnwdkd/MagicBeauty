package com.example.magicbeauty.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 曲线编辑器：两个可拖点（P1/P2），首尾固定在左下角/右上角。
 * 用 Fritsch-Carlson 单调三次插值生成 256 级 RGB LUT，避免过冲出界
 * （过冲会让暗部翻白、亮部发灰）。
 *
 * LUT 回调每次拖动都触发，预览实时更新；引擎按 curveVersion 判断重传。
 */
class CurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 可拖点（0..1，y 向上）。默认在对角线上=恒等变换 */
    val p1 = PointF(0.25f, 0.25f)
    val p2 = PointF(0.75f, 0.75f)

    var onLutChanged: ((IntArray) -> Unit)? = null

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val diagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7C5CFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private var dragging = 0 // 0=无 1=P1 2=P2
    private val touchSlop = 56f

    /** 生成 256 级 LUT（每项 0xRRGGBB） */
    fun buildLut(): IntArray {
        val xs = floatArrayOf(0f, p1.x, p2.x, 1f)
        val ys = floatArrayOf(0f, p1.y, p2.y, 1f)
        // Fritsch-Carlson：保证 x 单调，t 限制在 3*min(Δy) 内
        val n = 3
        val dx = FloatArray(n)
        val dy = FloatArray(n)
        val ms = FloatArray(n)
        for (i in 0 until n) {
            dx[i] = xs[i + 1] - xs[i]
            dy[i] = ys[i + 1] - ys[i]
            ms[i] = if (dx[i] > 1e-6f) dy[i] / dx[i] else 0f
        }
        val c1 = FloatArray(4)
        c1[0] = ms[0]
        c1[3] = ms[n - 1]
        for (i in 1 until n) {
            if (ms[i - 1] * ms[i] <= 0f) {
                c1[i] = 0f
            } else {
                val w1 = 2f * dx[i] + dx[i - 1]
                val w2 = dx[i] + 2f * dx[i - 1]
                c1[i] = (w1 + w2) / (w1 / ms[i - 1] + w2 / ms[i])
            }
        }
        val lut = IntArray(256)
        for (i in 0 until 256) {
            val x = i / 255f
            // 找区间
            var k = 0
            while (k < n - 1 && x > xs[k + 1]) k++
            val h = dx[k]
            val t = (x - xs[k]) / max(h, 1e-6f)
            val t2 = t * t
            val t3 = t2 * t
            // Hermite 基函数
            val y = (2 * t3 - 3 * t2 + 1) * ys[k] +
                    (t3 - 2 * t2 + t) * h * c1[k] +
                    (-2 * t3 + 3 * t2) * ys[k + 1] +
                    (t3 - t2) * h * c1[k + 1]
            val v = (min(1f, max(0f, y)) * 255f + 0.5f).toInt()
            lut[i] = (v shl 16) or (v shl 8) or v
        }
        return lut
    }

    fun reset() {
        p1.set(0.25f, 0.25f)
        p2.set(0.75f, 0.75f)
        onLutChanged?.invoke(buildLut())
        invalidate()
    }

    /** 是否还是默认曲线（恒等线，用于 UI 高亮） */
    fun isDefault(): Boolean =
        Math.abs(p1.x - 0.25f) < 0.005f && Math.abs(p1.y - 0.25f) < 0.005f &&
                Math.abs(p2.x - 0.75f) < 0.005f && Math.abs(p2.y - 0.75f) < 0.005f

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // 网格（四等分）
        for (i in 1..3) {
            val g = w * i / 4f
            canvas.drawLine(g, 0f, g, h, gridPaint)
            val gv = h * i / 4f
            canvas.drawLine(0f, gv, w, gv, gridPaint)
        }
        // 对角线（恒等线）
        canvas.drawLine(0f, h, w, 0f, diagPaint)
        // 曲线：直接用 LUT 采样画，保证"所见即所得"
        val lut = buildLut()
        val path = Path()
        for (i in 0 until 256) {
            val x = i / 255f * w
            val v = (lut[i] shr 16) and 0xff
            val y = (1f - v / 255f) * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, curvePaint)
        // 手柄
        fun handle(p: PointF) {
            val x = p.x * w
            val y = (1f - p.y) * h
            canvas.drawCircle(x, y, 11f, handlePaint)
            canvas.drawCircle(x, y, 11f, handleRing)
        }
        handle(p1)
        handle(p2)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                val x = e.x / w
                val y = 1f - e.y / h
                dragging = when {
                    dist(x, y, p1) * min(w, h) < touchSlop -> 1
                    dist(x, y, p2) * min(w, h) < touchSlop -> 2
                    else -> 0
                }
                return dragging != 0
            }
            MotionEvent.ACTION_MOVE -> {
                val x = (e.x / w).coerceIn(0.02f, 0.98f)
                val y = (1f - e.y / h).coerceIn(0f, 1f)
                if (dragging == 1) {
                    p1.set(min(x, p2.x - 0.08f), y)
                } else if (dragging == 2) {
                    p2.set(max(x, p1.x + 0.08f), y)
                }
                onLutChanged?.invoke(buildLut())
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = 0
                performClick()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun dist(x: Float, y: Float, p: PointF): Float {
        val dx = x - p.x
        val dy = y - p.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}

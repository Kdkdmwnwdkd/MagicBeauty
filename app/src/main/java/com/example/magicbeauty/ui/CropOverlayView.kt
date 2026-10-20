package com.example.magicbeauty.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 裁剪浮层：覆盖在照片上，拖动四角/四边/整体调整裁剪框，三分构图线，比例锁定。
 * 裁剪结果以归一化 RectF 给出（0..1，相对整图）。
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val RATIO_FREE = 0f
        const val RATIO_1_1 = 1f
        const val RATIO_4_3 = 4f / 3f
        const val RATIO_3_4 = 3f / 4f
        const val RATIO_16_9 = 16f / 9f
        const val RATIO_9_16 = 9f / 16f
        private const val MIN_SIZE = 0.08f // 归一化最小边
    }

    private val dimPaint = Paint().apply { color = 0x99000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    /** 当前裁剪框（归一化，0..1） */
    val crop = RectF(0.05f, 0.05f, 0.95f, 0.95f)

    /** 目标比例（宽/高，按图像像素计算）；RATIO_FREE=自由 */
    var ratio = RATIO_FREE

    private var imgW = 1
    private var imgH = 1
    private var viewW = 0f
    private var viewH = 0f

    /** 拖动中的把手；null=整体移动 */
    private var dragHandle: Int? = null
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragStartRect = RectF()

    /** 裁剪框变化（拖动过程中持续回调） */
    var onCropChanged: (() -> Unit)? = null

    fun setImageSize(w: Int, h: Int) {
        imgW = w; imgH = h
        if (ratio != RATIO_FREE) applyRatio()
        invalidate()
    }

    fun reset() {
        crop.set(0.05f, 0.05f, 0.95f, 0.95f)
        if (ratio != RATIO_FREE) applyRatio()
        onCropChanged?.invoke()
        invalidate()
    }

    /** 设置比例后把当前裁剪框收敛到该比例（围绕中心） */
    fun setRatioLock(r: Float) {
        ratio = r
        applyRatio()
        onCropChanged?.invoke()
        invalidate()
    }

    /** 归一化宽高比 → 屏幕上的归一化宽高比（考虑图像宽高比） */
    private fun screenRatio(r: Float): Float = r * imgH / imgW

    private fun applyRatio() {
        if (ratio == RATIO_FREE) return
        val target = screenRatio(ratio) // w/h in normalized space
        val cx = crop.centerX()
        val cy = crop.centerY()
        var w = crop.width()
        var h = crop.height()
        // 以较小的调整量适配比例
        val wFromH = h * target
        val hFromW = w / target
        if (abs(wFromH - w) < abs(hFromW - h)) w = wFromH else h = hFromW
        // 边界钳制
        w = min(w, 0.98f); h = min(h, 0.98f)
        val halfW = w / 2f; val halfH = h / 2f
        var left = cx - halfW; var top = cy - halfH
        if (left < 0.01f) left = 0.01f
        if (top < 0.01f) top = 0.01f
        if (left + w > 0.99f) left = 0.99f - w
        if (top + h > 0.99f) top = 0.99f - h
        crop.set(left, top, left + w, top + h)
    }

    /** contain 映射参数 */
    private fun mapping(): FloatArray {
        val scale = min(viewW / imgW, viewH / imgH)
        val dispW = imgW * scale
        val dispH = imgH * scale
        return floatArrayOf(scale, (viewW - dispW) / 2f, (viewH - dispH) / 2f, dispW, dispH)
    }

    /** 归一化 → 屏幕像素 */
    private fun toScreenX(nx: Float): Float {
        val m = mapping()
        return m[1] + nx * m[3]
    }

    private fun toScreenY(ny: Float): Float {
        val m = mapping()
        return m[2] + ny * m[4]
    }

    /** 屏幕像素 → 归一化 */
    private fun toNormX(x: Float): Float {
        val m = mapping()
        return ((x - m[1]) / m[3]).coerceIn(0f, 1f)
    }

    private fun toNormY(y: Float): Float {
        val m = mapping()
        return ((y - m[2]) / m[4]).coerceIn(0f, 1f)
    }

    private fun handleRadius(): Float = min(viewW, viewH) * 0.035f

    /** 把手编号：1=左上 2=右上 3=右下 4=左下 5=上 6=右 7=下 8=左 */
    private fun handleAt(x: Float, y: Float): Int? {
        val r = handleRadius()
        val pts = mapOf(
            1 to Pair(toScreenX(crop.left), toScreenY(crop.top)),
            2 to Pair(toScreenX(crop.right), toScreenY(crop.top)),
            3 to Pair(toScreenX(crop.right), toScreenY(crop.bottom)),
            4 to Pair(toScreenX(crop.left), toScreenY(crop.bottom)),
            5 to Pair(toScreenX(crop.centerX()), toScreenY(crop.top)),
            6 to Pair(toScreenX(crop.right), toScreenY(crop.centerY())),
            7 to Pair(toScreenX(crop.centerX()), toScreenY(crop.bottom)),
            8 to Pair(toScreenX(crop.left), toScreenY(crop.centerY()))
        )
        for ((id, p) in pts) {
            if (abs(x - p.first) <= r && abs(y - p.second) <= r) return id
        }
        return null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewW = w.toFloat(); viewH = h.toFloat()
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragHandle = handleAt(ev.x, ev.y)
                dragStartX = ev.x; dragStartY = ev.y
                dragStartRect.set(crop)
                if (dragHandle == null &&
                    ev.x > toScreenX(crop.left) && ev.x < toScreenX(crop.right) &&
                    ev.y > toScreenY(crop.top) && ev.y < toScreenY(crop.bottom)
                ) {
                    dragHandle = 0 // 整体移动
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val h = dragHandle ?: return true
                val dxNorm = (ev.x - dragStartX) / mapping()[3].coerceAtLeast(1f)
                val dyNorm = (ev.y - dragStartY) / mapping()[4].coerceAtLeast(1f)
                applyDrag(h, dxNorm, dyNorm)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragHandle = null
                invalidate()
                return true
            }
        }
        return false
    }

    private fun applyDrag(handle: Int, dx: Float, dy: Float) {
        val s = dragStartRect
        when (handle) {
            0 -> {
                val w = s.width(); val h = s.height()
                var left = s.left + dx; var top = s.top + dy
                left = left.coerceIn(0.01f, 0.99f - w)
                top = top.coerceIn(0.01f, 0.99f - h)
                crop.set(left, top, left + w, top + h)
            }
            1 -> { crop.left = (s.left + dx).coerceIn(0.01f, s.right - MIN_SIZE); crop.top = (s.top + dy).coerceIn(0.01f, s.bottom - MIN_SIZE) }
            2 -> { crop.right = (s.right + dx).coerceIn(s.left + MIN_SIZE, 0.99f); crop.top = (s.top + dy).coerceIn(0.01f, s.bottom - MIN_SIZE) }
            3 -> { crop.right = (s.right + dx).coerceIn(s.left + MIN_SIZE, 0.99f); crop.bottom = (s.bottom + dy).coerceIn(s.top + MIN_SIZE, 0.99f) }
            4 -> { crop.left = (s.left + dx).coerceIn(0.01f, s.right - MIN_SIZE); crop.bottom = (s.bottom + dy).coerceIn(s.top + MIN_SIZE, 0.99f) }
            5 -> { crop.top = (s.top + dy).coerceIn(0.01f, s.bottom - MIN_SIZE) }
            6 -> { crop.right = (s.right + dx).coerceIn(s.left + MIN_SIZE, 0.99f) }
            7 -> { crop.bottom = (s.bottom + dy).coerceIn(s.top + MIN_SIZE, 0.99f) }
            8 -> { crop.left = (s.left + dx).coerceIn(0.01f, s.right - MIN_SIZE) }
        }
        if (ratio != RATIO_FREE && handle != 0) fitRatioOnHandle(handle)
        onCropChanged?.invoke()
    }

    /** 拖动把手时保持比例：以对角为锚点调整 */
    private fun fitRatioOnHandle(handle: Int) {
        val target = screenRatio(ratio)
        // 锚点（对角）
        val anchorX: Float
        val anchorY: Float
        when (handle) {
            1 -> { anchorX = dragStartRect.right; anchorY = dragStartRect.bottom }
            3 -> { anchorX = dragStartRect.left; anchorY = dragStartRect.top }
            2 -> { anchorX = dragStartRect.left; anchorY = dragStartRect.bottom }
            4 -> { anchorX = dragStartRect.right; anchorY = dragStartRect.top }
            5, 7 -> { anchorX = dragStartRect.centerX(); anchorY = dragStartRect.centerY() }
            6, 8 -> { anchorX = dragStartRect.centerX(); anchorY = dragStartRect.centerY() }
            else -> return
        }
        // 从锚点出发，宽 = |free - anchor|，按比例求高
        val freeX = if (handle == 5 || handle == 7 || handle == 6 || handle == 8) dragStartRect.centerX() else
            (if (handle == 1 || handle == 4) dragStartRect.right else dragStartRect.left)
        val freeY = if (handle == 5 || handle == 7) dragStartRect.bottom else
            if (handle == 6 || handle == 8) dragStartRect.centerY() else
            (if (handle == 1 || handle == 2) dragStartRect.bottom else dragStartRect.top)

        var w = abs(freeX - anchorX).coerceAtLeast(MIN_SIZE)
        var h = w / target
        if (h < MIN_SIZE) { h = MIN_SIZE; w = h * target }
        // 方向
        val dirX = if (freeX >= anchorX) 1 else -1
        val dirY = if (freeY >= anchorY) 1 else -1
        var left = if (dirX > 0) anchorX else anchorX - w
        var right = if (dirX > 0) anchorX + w else anchorX
        var top = if (dirY > 0) anchorY else anchorY - h
        var bottom = if (dirY > 0) anchorY + h else anchorY
        // 边界钳制：超出则整体缩放
        if (left < 0.01f || right > 0.99f || top < 0.01f || bottom > 0.99f) {
            val maxW = min(if (dirX > 0) 0.99f - anchorX else anchorX - 0.01f, 0.98f)
            val maxH = min(if (dirY > 0) 0.99f - anchorY else anchorY - 0.01f, 0.98f)
            val scaleF = min(maxW / w, maxH / h)
            w *= scaleF; h *= scaleF
            left = if (dirX > 0) anchorX else anchorX - w
            right = if (dirX > 0) anchorX + w else anchorX
            top = if (dirY > 0) anchorY else anchorY - h
            bottom = if (dirY > 0) anchorY + h else anchorY
        }
        crop.set(left, top, right, bottom)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (viewW == 0f || viewH == 0f) return
        val left = toScreenX(crop.left)
        val top = toScreenY(crop.top)
        val right = toScreenX(crop.right)
        val bottom = toScreenY(crop.bottom)

        // 四周压暗
        canvas.drawRect(0f, 0f, viewW, top, dimPaint)
        canvas.drawRect(0f, bottom, viewW, viewH, dimPaint)
        canvas.drawRect(0f, top, left, bottom, dimPaint)
        canvas.drawRect(right, top, viewW, bottom, dimPaint)

        // 边框 + 三分线
        canvas.drawRect(left, top, right, bottom, borderPaint)
        for (i in 1..2) {
            val gx = left + (right - left) * i / 3f
            val gy = top + (bottom - top) * i / 3f
            canvas.drawLine(gx, top, gx, bottom, gridPaint)
            canvas.drawLine(left, gy, right, gy, gridPaint)
        }

        // 四角把手（L 形）
        val len = (right - left).coerceAtMost(bottom - top) * 0.12f
        val hl = len
        canvas.drawLine(left, top, left + hl, top, handlePaint)
        canvas.drawLine(left, top, left, top + hl, handlePaint)
        canvas.drawLine(right - hl, top, right, top, handlePaint)
        canvas.drawLine(right, top, right, top + hl, handlePaint)
        canvas.drawLine(right - hl, bottom, right, bottom, handlePaint)
        canvas.drawLine(right, bottom, right, bottom - hl, handlePaint)
        canvas.drawLine(left, bottom - hl, left, bottom, handlePaint)
        canvas.drawLine(left, bottom, left + hl, bottom, handlePaint)
    }

    /** 当前裁剪矩形（归一化） */
    fun cropRect(): RectF = RectF(crop)
}

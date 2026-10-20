package com.example.magicbeauty.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * 马赛克笔涂抹视图：覆盖在照片上，用户手指涂抹出要打码的区域。
 * - mask 位图与照片同尺寸（白=涂抹区，引擎按它混合马赛克）
 * - 自身把 mask 以半透明红绘制出来，方便看到涂了哪里
 * - 支持撤销（快照栈，上限 12 步）与橡皮
 */
class MaskPaintView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val MAX_UNDO = 12
        /** 撤销栈字节预算（全尺寸快照一张几 MB，12 张能吃掉几十 MB，超了先淘汰最旧） */
        private const val MAX_UNDO_BYTES = 48 * 1024 * 1024
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val erasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    /** 橡皮按下时画的空心圆点（CLEAR 模式 FILL） */
    private val eraseFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isAntiAlias = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FF3B30.toInt()
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    var mask: Bitmap? = null
        private set
    private var maskCanvas: Canvas? = null

    /** mask 内容版本号：每次涂抹/撤销后 +1，引擎据此重新上传纹理 */
    var maskVersion = 0
        private set

    /** 笔刷半径（相对画面短边，0.01..0.2） */
    var brush = 0.06f

    /** 橡皮模式 */
    var erasing = false

    private var imgW = 1
    private var imgH = 1
    private var viewW = 0f
    private var viewH = 0f

    private val undoStack = ArrayDeque<Bitmap>()
    /** 撤销栈总字节预算：全尺寸快照很占内存（1280px 一张 ~6.5MB），按总量淘汰 */
    private var undoBytes = 0
    private var lastX = 0f
    private var lastY = 0f
    private var drawing = false
    /** 当前正在绘制的指针 id（多点触控时只认最初那一根手指） */
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var cursorX = -1f
    private var cursorY = -1f

    /** mask 发生变化（涂抹/撤销/清空） */
    var onMaskChanged: (() -> Unit)? = null

    /** 新建/重置 mask（尺寸与照片一致）。old 会被回收。
     *  clear=true 时即使尺寸相同也清空内容——换新图/AI 消除重新涂抹时旧痕迹必须去掉；
     *  clear=false（重新打开马赛克工具）保留已有涂抹。 */
    fun setImageSize(w: Int, h: Int, clear: Boolean = true) {
        if (mask != null && mask!!.width == w && mask!!.height == h) {
            if (!clear) return   // 同尺寸且不清：保持现状
            maskCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            undoStack.forEach { it.recycle() }
            undoStack.clear()
            undoBytes = 0
            maskVersion++
            onMaskChanged?.invoke()
            invalidate()
            return
        }
        mask?.recycle()
        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bm.eraseColor(Color.TRANSPARENT)
        mask = bm
        maskCanvas = Canvas(bm)
        imgW = w; imgH = h
        undoStack.forEach { it.recycle() }
        undoStack.clear()
        undoBytes = 0
        maskVersion++
        onMaskChanged?.invoke()
        invalidate()
    }

    /** 换入外部创建好的 mask（裁剪/旋转后保持涂抹内容跟着变）。调用方持有新位图。 */
    fun replaceMask(bm: Bitmap) {
        if (mask === bm) return
        if (bm.isRecycled) return
        mask?.recycle()
        mask = bm
        maskCanvas = Canvas(bm)
        imgW = bm.width; imgH = bm.height
        undoStack.forEach { it.recycle() }
        undoStack.clear()
        undoBytes = 0
        maskVersion++
        onMaskChanged?.invoke()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewW = w.toFloat(); viewH = h.toFloat()
    }

    /** contain 映射参数（与 fitCenter ImageView 一致：遵守 padding） */
    private fun mapping(): FloatArray {
        val ew = (viewW - paddingLeft - paddingRight).coerceAtLeast(1f)
        val eh = (viewH - paddingTop - paddingBottom).coerceAtLeast(1f)
        val scale = min(ew / imgW, eh / imgH)
        val dispW = imgW * scale
        val dispH = imgH * scale
        return floatArrayOf(scale, paddingLeft + (ew - dispW) / 2f, paddingTop + (eh - dispH) / 2f, dispW, dispH)
    }

    /** 触摸坐标 → mask 位图像素坐标（按 contain 方式映射） */
    private fun toMaskXY(x: Float, y: Float): FloatArray {
        val m = mapping()
        return floatArrayOf((x - m[1]) / m[0], (y - m[2]) / m[0])
    }

    /** 当前笔刷在 mask 上的像素半径 */
    private fun maskRadius(): Float {
        val m = mapping()
        val shortSide = min(m[3], m[4])
        return (brush * shortSide) / m[0].coerceAtLeast(0.0001f)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // 只认第一根按下的手指，第二根手指按下不影响当前笔画
                if (!drawing) {
                    val idx = if (ev.actionMasked == MotionEvent.ACTION_DOWN) 0 else ev.action shr 8
                    activePointerId = ev.getPointerId(idx)
                    val p = toMaskXY(ev.getX(idx), ev.getY(idx))
                    pushUndo()
                    drawing = true
                    lastX = p[0]; lastY = p[1]
                    cursorX = ev.getX(idx); cursorY = ev.getY(idx)
                    maskCanvas?.drawCircle(p[0], p[1], maskRadius(),
                        if (erasing) eraseFill else fillPaint)
                    maskVersion++
                    onMaskChanged?.invoke()
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = if (activePointerId != MotionEvent.INVALID_POINTER_ID)
                    ev.findPointerIndex(activePointerId) else -1
                if (drawing && idx >= 0) {
                    cursorX = ev.getX(idx); cursorY = ev.getY(idx)
                    val p = toMaskXY(cursorX, cursorY)
                    val paint = if (erasing) erasePaint else strokePaint
                    paint.strokeWidth = maskRadius() * 2f
                    maskCanvas?.drawLine(lastX, lastY, p[0], p[1], paint)
                    lastX = p[0]; lastY = p[1]
                    maskVersion++
                    onMaskChanged?.invoke()
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                    drawing = false
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    cursorX = -1f; cursorY = -1f
                    invalidate()
                } else {
                    val idx = ev.action shr 8
                    val upId = ev.getPointerId(idx)
                    if (upId == activePointerId) {
                        // 绘制的主动手指抬起：结束本笔画（剩下的手指不续接，避免线段跳变）
                        drawing = false
                        activePointerId = MotionEvent.INVALID_POINTER_ID
                        cursorX = -1f; cursorY = -1f
                        invalidate()
                    }
                }
            }
        }
        return true
    }

    /** 压入撤销快照（全尺寸副本，按总量限额淘汰） */
    private fun pushUndo() {
        val m = mask ?: return
        val snap = m.copy(Bitmap.Config.ARGB_8888, false)
        undoStack.addLast(snap)
        undoBytes += snap.allocationByteCount
        while (undoStack.size > MAX_UNDO ||
            (undoBytes > MAX_UNDO_BYTES && undoStack.size > 1)) {
            val old = undoStack.removeFirst()
            undoBytes -= old.allocationByteCount
            old.recycle()
        }
    }

    fun undo() {
        val m = mask ?: return
        val snap = undoStack.removeLastOrNull() ?: return
        val c = maskCanvas ?: return
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        c.drawBitmap(snap, 0f, 0f, null)
        undoBytes -= snap.allocationByteCount
        if (undoBytes < 0) undoBytes = 0
        snap.recycle()
        maskVersion++
        onMaskChanged?.invoke()
        invalidate()
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()

    fun clearMask() {
        maskCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        maskVersion++
        onMaskChanged?.invoke()
        invalidate()
    }

    /** 是否有任何涂抹内容（隔行采样 alpha，够快够用） */
    fun hasPaint(): Boolean {
        val m = mask ?: return false
        val px = IntArray(m.width)
        var row = 0
        while (row < m.height) {
            m.getPixels(px, 0, m.width, 0, row, m.width, 1)
            for (v in px) if (v ushr 24 > 16) return true
            row += 37
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val m = mask ?: return
        val mp = mapping()
        val dst = Rect(mp[1].toInt(), mp[2].toInt(), (mp[1] + mp[3]).toInt(), (mp[2] + mp[4]).toInt())
        // 把 mask 的 alpha 抠出来染成半透明红：SRC_IN 只保留 mask 有 alpha 的位置
        val layer = canvas.saveLayer(0f, 0f, viewW, viewH, null)
        canvas.drawBitmap(m, null, dst, null)
        canvas.drawRect(dst, previewPaint)
        canvas.restoreToCount(layer)
        if (cursorX >= 0f) {
            canvas.drawCircle(cursorX, cursorY, brush * min(mp[3], mp[4]), cursorPaint)
        }
    }
}

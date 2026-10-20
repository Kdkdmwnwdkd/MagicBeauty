package com.example.magicbeauty.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.magicbeauty.engine.StickerItem
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 贴纸手势层：叠在预览图上，负责贴纸的 选中/拖动/双指缩放/双指旋转。
 * 贴纸图像本身由 GL 引擎渲染（Overlay 段），这里只画选中框，
 * 手势变化通过 [onStickerChanged] 通知外部重新渲染。
 *
 * 坐标系约定（与 OVERLAY_FRAG 一致）：
 *  - 贴纸在屏幕上的边长 = scale × 图像显示高度
 *  - 位置 cx/cy 为 y 向下的归一化图像坐标（contain 映射到屏幕）
 */
class StickerGestureView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 贴纸列表（外部持有，这里只操作引用） */
    var stickers: List<StickerItem> = emptyList()

    /** 当前选中的贴纸索引；-1 = 没有选中 */
    var selected = -1
        set(value) {
            field = value
            invalidate()
        }

    /** 图像显示区域（contain 映射后的屏幕矩形） */
    private val imgRect = RectF()

    /** 手势变化（拖动/缩放/旋转后持续回调） */
    var onStickerChanged: (() -> Unit)? = null

    /** 点击了空白处（用于取消选中） */
    var onBackgroundTap: (() -> Unit)? = null

    private var imgW = 1
    private var imgH = 1

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
    }
    private val deletePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE05252.toInt()
        style = Paint.Style.FILL
    }
    private val deleteTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    // 手势状态
    private var mode = 0 // 0=无 1=拖动 2=双指
    /** 发起拖动的那根手指（多点触控时避免抬手后指针错位） */
    private var dragPointerId = MotionEvent.INVALID_POINTER_ID
    private var lastX = 0f
    private var lastY = 0f
    private var startDist = 0f
    private var startAngle = 0f
    private var startScale = 1f
    private var startRot = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false

    fun setImageSize(w: Int, h: Int) {
        imgW = w; imgH = h
        updateImgRect()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateImgRect()
    }

    /** contain 映射（与 fitCenter ImageView 一致：遵守 padding） */
    private fun updateImgRect() {
        val ew = (width - paddingLeft - paddingRight).coerceAtLeast(1f)
        val eh = (height - paddingTop - paddingBottom).coerceAtLeast(1f)
        val scale = minOf(ew / imgW, eh / imgH)
        val dispW = imgW * scale
        val dispH = imgH * scale
        val offX = paddingLeft + (ew - dispW) / 2f
        val offY = paddingTop + (eh - dispH) / 2f
        imgRect.set(offX, offY, offX + dispW, offY + dispH)
    }

    // ---------- 坐标换算 ----------

    /** 贴纸中心在屏幕上的位置 */
    private fun centerScreen(st: StickerItem): FloatArray {
        val cx = imgRect.left + st.cx * imgRect.width()
        val cy = imgRect.top + st.cy * imgRect.height()
        return floatArrayOf(cx, cy)
    }

    /** 贴纸在屏幕上的边长（scale 相对图像高度，见 OVERLAY_FRAG） */
    private fun sizeScreen(st: StickerItem): Float = st.scale * imgRect.height()

    /** 屏幕点 → 贴纸局部坐标（未缩放单位：以贴纸边长为 1，中心为原点，y 向下） */
    private fun toLocal(st: StickerItem, x: Float, y: Float): FloatArray {
        val c = centerScreen(st)
        val size = sizeScreen(st).coerceAtLeast(0.001f)
        val dx = (x - c[0]) / size
        val dy = (y - c[1]) / size
        // 反向旋转（屏幕上是顺时针为正，反向即 -rot）
        val cs = cos(-st.rotation)
        val sn = sin(-st.rotation)
        return floatArrayOf(dx * cs - dy * sn, dx * sn + dy * cs)
    }

    /** 贴纸局部坐标 → 屏幕点 */
    private fun localToScreen(st: StickerItem, lx: Float, ly: Float): FloatArray {
        val cs = cos(st.rotation)
        val sn = sin(st.rotation)
        val rx = lx * cs - ly * sn
        val ry = lx * sn + ly * cs
        val c = centerScreen(st)
        val size = sizeScreen(st).coerceAtLeast(0.001f)
        return floatArrayOf(c[0] + rx * size, c[1] + ry * size)
    }

    /** 命中测试：从上层往下找（后加的贴上） */
    private fun hitTest(x: Float, y: Float): Int {
        for (i in stickers.indices.reversed()) {
            val st = stickers[i]
            val p = toLocal(st, x, y)
            if (abs(p[0]) <= 0.5f && abs(p[1]) <= 0.5f) return i
        }
        return -1
    }

    // ---------- 触摸 ----------

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y
                downTime = android.os.SystemClock.uptimeMillis()
                moved = false
                val hit = hitTest(ev.x, ev.y)
                if (hit >= 0) {
                    selected = hit
                    mode = 1
                    dragPointerId = ev.getPointerId(0)
                    lastX = ev.x; lastY = ev.y
                } else {
                    mode = 0
                    dragPointerId = MotionEvent.INVALID_POINTER_ID
                    selected = -1
                    onBackgroundTap?.invoke()
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount >= 2 && selected >= 0) {
                    mode = 2
                    startDist = dist(ev)
                    startAngle = angle(ev)
                    stickers.getOrNull(selected)?.let {
                        startScale = it.scale
                        startRot = it.rotation
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val st = stickers.getOrNull(selected)
                if (st == null) { mode = 0; return true }
                if (abs(ev.x - downX) > 6f || abs(ev.y - downY) > 6f) moved = true
                if (mode == 1) {
                    // 只跟随发起拖动的那根手指（pointerId 可能在抬手后错位）
                    val idx = if (dragPointerId != MotionEvent.INVALID_POINTER_ID)
                        ev.findPointerIndex(dragPointerId) else -1
                    if (idx < 0) return true
                    val dx = ev.getX(idx) - lastX
                    val dy = ev.getY(idx) - lastY
                    st.cx = (st.cx + dx / imgRect.width()).coerceIn(0f, 1f)
                    st.cy = (st.cy + dy / imgRect.height()).coerceIn(0f, 1f)
                    lastX = ev.getX(idx); lastY = ev.getY(idx)
                    onStickerChanged?.invoke()
                } else if (mode == 2 && ev.pointerCount >= 2) {
                    val d = dist(ev)
                    val a = angle(ev)
                    if (startDist > 1f) {
                        st.scale = (startScale * d / startDist).coerceIn(0.05f, 1.5f)
                    }
                    // 角度差归一化到 ±π：跨过 ±180° 时不能跳变（否则贴纸瞬间反转）
                    var da = a - startAngle
                    while (da > Math.PI) da -= (2 * Math.PI).toFloat()
                    while (da < -Math.PI) da += (2 * Math.PI).toFloat()
                    st.rotation = startRot + da
                    onStickerChanged?.invoke()
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 双指中抬起一根：若主动拖动的那根还在，降级为单指拖动续接；
                // 否则结束手势（旧代码没有这一分支，mode 卡在 2，之后完全失灵）
                if (mode == 2) {
                    val idx = ev.action shr 8
                    val upId = ev.getPointerId(idx)
                    if (upId == dragPointerId) {
                        // 拖动发起指抬了：换剩下那根续接
                        val remain = if (idx == 0) 1 else 0
                        dragPointerId = ev.getPointerId(remain)
                    }
                    if (ev.pointerCount > 2) {
                        // 还有第三根手指：保持双指，重新取基准
                        startDist = dist(ev)
                        startAngle = angle(ev)
                        stickers.getOrNull(selected)?.let {
                            startScale = it.scale
                            startRot = it.rotation
                        }
                    } else {
                        mode = 1
                        // 续接单指拖动：基准必须刷新成当前拖动手指的位置，
                        // 否则下一帧按 mode 2 之前的老基准算 dx，贴纸会跳变
                        val di = ev.findPointerIndex(dragPointerId)
                        if (di >= 0) { lastX = ev.getX(di); lastY = ev.getY(di) }
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val quickTap = !moved &&
                    android.os.SystemClock.uptimeMillis() - downTime < 250 &&
                    hypot(ev.x - downX, ev.y - downY) < 10f
                if (quickTap && selected >= 0) {
                    // 点中已选中的贴纸：交给外部判断是否点了删除按钮
                    onStickerTap?.invoke(selected, ev.x, ev.y)
                }
                mode = 0
                dragPointerId = MotionEvent.INVALID_POINTER_ID
                invalidate()
                return true
            }
        }
        return false
    }

    /** 单击贴纸（外部可用来删除/确认） */
    var onStickerTap: ((Int, Float, Float) -> Unit)? = null

    private fun dist(ev: MotionEvent): Float =
        hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1))

    private fun angle(ev: MotionEvent): Float =
        atan2(ev.getY(1) - ev.getY(0), ev.getX(1) - ev.getX(0))

    // ---------- 绘制选中框 ----------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val st = stickers.getOrNull(selected) ?: return
        val size = sizeScreen(st)
        val corners = arrayOf(
            localToScreen(st, -0.5f, -0.5f),
            localToScreen(st, 0.5f, -0.5f),
            localToScreen(st, 0.5f, 0.5f),
            localToScreen(st, -0.5f, 0.5f)
        )
        // 外框
        for (i in 0..3) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            canvas.drawLine(a[0], a[1], b[0], b[1], framePaint)
        }
        // 对角线（提示可旋转/缩放）
        canvas.drawLine(corners[0][0], corners[0][1], corners[2][0], corners[2][1], gridPaint)
        canvas.drawLine(corners[1][0], corners[1][1], corners[3][0], corners[3][1], gridPaint)
        // 右上角删除圆钮
        val del = corners[1]
        val r = size * 0.11f
        canvas.drawCircle(del[0], del[1], r, deletePaint)
        val ty = del[1] - (deleteTextPaint.descent() + deleteTextPaint.ascent()) / 2f
        canvas.drawText("×", del[0], ty, deleteTextPaint)
    }

    /** 供外部判断点击是否落在删除按钮上 */
    fun deleteButtonBounds(): FloatArray? {
        val st = stickers.getOrNull(selected) ?: return null
        val p = localToScreen(st, 0.5f, -0.5f)
        return floatArrayOf(p[0], p[1], sizeScreen(st) * 0.11f)
    }
}

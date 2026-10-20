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
import kotlin.math.min

/**
 * 塑形手动定位线：两条可拖动的水平线（裙摆线 / 脚踝线），
 * 用于 pose 检测不准或想手动控制拉腿区间时兜底。
 * 位置以 GL 坐标（y 向上，0..1）通过 [onLineChanged] 抛出。
 */
class GuideLineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 裙摆线（GL y，拉腿起点） */
    var hemY = 0.45f
    /** 脚踝线（GL y，拉腿终点） */
    var ankleY = 0.10f

    /** 拖动变化（持续回调） */
    var onLineChanged: ((Float, Float) -> Unit)? = null

    private var imgW = 1
    private var imgH = 1
    private val imgRect = RectF()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD166.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD166.toInt()
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD166.toInt()
        textSize = 26f
        isFakeBoldText = true
    }

    private var dragging = 0 // 0=无 1=裙摆 2=脚踝

    fun setImageSize(w: Int, h: Int) {
        imgW = w; imgH = h
        updateImgRect()
        invalidate()
    }

    /** 用自动检测值初始化两条线 */
    fun initFrom(autoHem: Float, autoAnkle: Float) {
        hemY = autoHem.coerceIn(0.02f, 0.98f)
        ankleY = autoAnkle.coerceIn(0.01f, 0.96f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateImgRect()
    }

    private fun updateImgRect() {
        val scale = min(width.toFloat() / imgW, height.toFloat() / imgH)
        val dispW = imgW * scale
        val dispH = imgH * scale
        imgRect.set(
            (width - dispW) / 2f, (height - dispH) / 2f,
            (width + dispW) / 2f, (height + dispH) / 2f
        )
    }

    /** GL y → 屏幕 y */
    private fun screenY(gy: Float): Float = imgRect.top + (1f - gy) * imgRect.height()

    /** 屏幕 y → GL y */
    private fun glY(sy: Float): Float =
        ((imgRect.bottom - sy) / imgRect.height()).coerceIn(0.005f, 0.995f)

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hem = screenY(hemY)
                val ank = screenY(ankleY)
                dragging = when {
                    abs(ev.y - hem) <= 40f -> 1
                    abs(ev.y - ank) <= 40f -> 2
                    else -> 0
                }
                return dragging != 0
            }
            MotionEvent.ACTION_MOVE -> {
                val gy = glY(ev.y)
                if (dragging == 1) {
                    // 裙摆线必须在脚踝线上方至少 0.03
                    hemY = gy.coerceIn(ankleY + 0.03f, 0.995f)
                    onLineChanged?.invoke(hemY, ankleY)
                } else if (dragging == 2) {
                    ankleY = gy.coerceIn(0.005f, hemY - 0.03f)
                    onLineChanged?.invoke(hemY, ankleY)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = 0
                return true
            }
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imgRect.isEmpty) return
        drawLine(canvas, hemY, "裙摆线（拉腿起点）")
        drawLine(canvas, ankleY, "脚踝线（拉腿终点）")
    }

    private fun drawLine(canvas: Canvas, gy: Float, label: String) {
        val y = screenY(gy)
        canvas.drawLine(imgRect.left, y, imgRect.right, y, linePaint)
        canvas.drawCircle(imgRect.left + 18f, y, 12f, handlePaint)
        canvas.drawText(label, imgRect.left + 38f, y - 10f, textPaint)
        // 两端小箭头，提示可拖
        canvas.drawCircle(imgRect.right - 18f, y, 12f, handlePaint)
    }
}

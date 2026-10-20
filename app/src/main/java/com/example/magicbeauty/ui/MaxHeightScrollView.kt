package com.example.magicbeauty.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * 限高 ScrollView：底部工具面板的内容越来越多（滑块/色带/曲线），
 * 直接铺开会把预览图顶没。这里把面板内容限制在屏幕高度的 45% 以内，
 * 超出则内部滚动；内容不多时仍保持 wrap_content，不会留一块空白。
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ScrollView(context, attrs) {

    /** 内容最大高度占屏幕比例 */
    var maxHeightFraction: Float = 0.45f

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        var h = heightSpec
        val mode = MeasureSpec.getMode(heightSpec)
        if (mode != MeasureSpec.UNSPECIFIED) {
            val maxH = (resources.displayMetrics.heightPixels * maxHeightFraction).toInt()
            val size = MeasureSpec.getSize(heightSpec)
            if (size > maxH) {
                h = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
            }
        }
        super.onMeasure(widthSpec, h)
    }
}

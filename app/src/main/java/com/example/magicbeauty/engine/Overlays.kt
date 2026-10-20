package com.example.magicbeauty.engine

import android.graphics.Bitmap

/**
 * 叠加层数据（贴纸 / 文字 / 马赛克笔涂抹）。
 * 这些不参与形变引导，全部在 Overlay 段（pass③）按屏幕空间混合。
 */

/** 一个贴纸/文字层：位图 + 位置（归一化，原点左上、y 向下，与触摸坐标一致）+ 缩放 + 旋转 */
data class StickerItem(
    val bitmap: Bitmap,
    var cx: Float = 0.5f,      // 中心 x（0..1，y 向下的图像坐标）
    var cy: Float = 0.5f,      // 中心 y
    var scale: Float = 0.35f,  // 层尺寸相对图像高度的比例（0.05..1.5）
    var rotation: Float = 0f   // 弧度（屏幕上看为顺时针）
) {
    /** 是否可回收（文字层是我们生成的，贴纸是缓存的，不能 recycle） */
    var owned: Boolean = false
}

/**
 * 马赛克笔涂抹：mask 位图（与源图同尺寸，白色=要打码的区域）。
 * UI 层用 [com.example.magicbeauty.ui.MaskPaintView] 画，引擎按 mask 混合。
 */
data class MosaicSpec(
    var enabled: Boolean = false,
    /** 全局块大小（相对短边，0.01..0.15） */
    var block: Float = 0.035f,
    /** 笔涂抹 mask（ARGB，白=涂抹区）；null=没有涂抹 */
    var mask: Bitmap? = null,
    /** 只有涂抹区生效（true）还是整图生效（false） */
    var brushOnly: Boolean = true,
    /** mask 内容版本号：每次涂抹后 +1，引擎据此判断是否重新上传纹理 */
    var maskVersion: Int = 0
)

/** 一次渲染的全部叠加内容 */
data class OverlaySpec(
    val stickers: List<StickerItem> = emptyList(),
    val mosaic: MosaicSpec = MosaicSpec()
) {
    /** 没有任何叠加内容时跳过第三段，省一次全屏 pass */
    val active: Boolean
        get() = stickers.isNotEmpty() || (mosaic.enabled && (mosaic.mask != null || !mosaic.brushOnly))
}

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

/** 马赛克样式（对标美图/醒图的笔刷类型） */
enum class MosaicStyle {
    /** 像素化：块内取平均色，平滑色块 */
    PIXELATE,
    /** 马赛克：块内单点采样，块间硬跳变（经典马赛克） */
    MOSAIC,
    /** 彩色噪点：块内加随机彩色，隐私打码用，块内看不出原图 */
    NOISE
}

/**
 * 马赛克笔涂抹：mask 位图（与源图同尺寸，白色=要打码的区域）。
 * UI 层用 [com.example.magicbeauty.ui.MaskPaintView] 画，引擎按 mask 混合。
 */
data class MosaicSpec(
    var enabled: Boolean = false,
    /**
     * 块大小，**绝对像素**（以最终导出的图像像素为准）。
     *
     * 早期版本这里存的是「相对图像高度的比例」，导致导出大图时块被按比例放大，
     * 预览与导出观感不一致；专业做法是锚定在输出像素上（Photoshop 的马赛克滤镜同理）。
     */
    var blockPx: Int = 16,
    /** 块内取色方式 */
    var style: MosaicStyle = MosaicStyle.PIXELATE,
    /** 笔涂抹 mask（ARGB，白=涂抹区）；null=没有涂抹 */
    var mask: Bitmap? = null,
    /** 只有涂抹区生效（true）还是整图生效（false） */
    var brushOnly: Boolean = true,
    /** mask 内容版本号：每次涂抹后 +1，引擎据此判断是否重新上传纹理 */
    var maskVersion: Int = 0,
    /** 自动识别人脸并打码（圆形羽化遮罩，无需手涂） */
    var autoFace: Boolean = false,
    /** 人脸圆形打码的半径（相对图像高度） */
    var faceRadius: Float = 0.13f,
    /** 遮罩边缘羽化宽度（相对图像高度），0 = 硬边 */
    var feather: Float = 0.012f
) {
    companion object {
        /** UI 给的块大小档位（像素）——专业 App 用离散档位，不给连续值 */
        val BLOCK_STEPS = intArrayOf(4, 8, 12, 16, 24, 32, 48, 64, 96)

        /** 把任意像素值吸附到最近的档位 */
        fun snapBlock(px: Int): Int {
            val v = px.coerceIn(BLOCK_STEPS.first(), BLOCK_STEPS.last())
            return BLOCK_STEPS.minByOrNull { kotlin.math.abs(it - v) } ?: v
        }
    }
}


/** 一次渲染的全部叠加内容 */
data class OverlaySpec(
    val stickers: List<StickerItem> = emptyList(),
    val mosaic: MosaicSpec = MosaicSpec()
) {
    /** 没有任何叠加内容时跳过第三段，省一次全屏 pass */
    val active: Boolean
        get() = stickers.isNotEmpty() || (mosaic.enabled && (mosaic.mask != null || !mosaic.brushOnly))
}

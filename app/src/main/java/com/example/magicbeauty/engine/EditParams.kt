package com.example.magicbeauty.engine

import android.graphics.PointF

/**
 * 编辑参数：UI 滑块直接绑定这些值。
 * 所有“强度类”参数默认 0（即不做处理），用户拉高才生效。
 */
data class EditParams(
    // 调色
    var brightness: Float = 0f,
    var contrast: Float = 1f,
    var saturation: Float = 1f,
    var warmth: Float = 0f,
    var blur: Float = 0f,        // 磨皮
    var vignette: Float = 0f,
    // 人脸形变
    var thinFace: Float = 0f,    // 瘦脸
    var eyeEnlarge: Float = 0f,  // 大眼
    // 身材形变
    var waistSlim: Float = 0f,   // 瘦腰
    var legStretch: Float = 0f   // 拉腿/调身高
)

/**
 * 由 MediaPipe 关键点推导出的形变引导标量（已转换为 GL 坐标：原点左下、y 向上）。
 * 未检测到人时给出中性值——配合强度=0，等价于不做形变。
 */
data class WarpGuide(
    val faceCenter: PointF = PointF(0.5f, 0.55f),
    val faceHalfW: Float = 0.11f,
    val faceHalfH: Float = 0.18f,
    val eyeL: PointF = PointF(0.4f, 0.6f),
    val eyeR: PointF = PointF(0.6f, 0.6f),
    val eyeRadius: Float = 0.07f,
    val bodyCenter: PointF = PointF(0.5f, 0.45f),
    val bodyHalfW: Float = 0.18f,
    val waistY: Float = 0.45f,
    val hipY: Float = 0.35f,
    val ankleY: Float = 0.05f,
    // 是否真的检测到人脸/人体。没检测到就不能拿默认坐标硬套，
    // 否则“瘦脸/大眼”会落在画面中央（用户看到的就是“没对准脸”）。
    val hasFace: Boolean = false,
    val hasPose: Boolean = false
) {
    companion object {
        // MediaPipe 归一化坐标(y 向下) -> GL 坐标(y 向上)
        fun toGL(x: Float, y: Float) = PointF(x, 1f - y)
    }
}

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
    var filterId: Int = 0,       // 滤镜预设（见 Filters.presets）
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
    /** 腿段上界：优先用膝盖（这样裙子/长上衣只会整体上移，不会被拉长） */
    val legTopY: Float = 0.35f,
    val ankleY: Float = 0.05f,
    /** 衣服下沿（GL 坐标）——拉腿的起点；裙子/短裤用它，长裤会用胯 */
    val clothesHemY: Float = 0f,
    /** 衣服下沿处的半宽（裙摆宽度），拉腿的横向作用范围 */
    val clothesHalfW: Float = 0f,
    val hasClothes: Boolean = false,
    /** 长裤（下沿贴脚踝）：可以拉整条腿，衣服不会变形 */
    val clothesIsPants: Boolean = false,
    /** 裙摆两侧相对中间的抬高量（A 字裙/大摆裙两侧比中间高） */
    val clothesEdgeLift: Float = 0f,
    /** 大眼强度的自适应系数：眼睛小就多给一点，眼睛大就少给一点 */
    val eyeGain: Float = 1f,
    /** 人体外接框顶/底（GL 坐标，y 向上）——无蒙版兜底时限制位移的垂直范围：
     *  头顶上方、脚底下方的背景位移恒 0（用户报的"人物上面背景跟着变高"的根治） */
    val bodyTopY: Float = 0f,
    val bodyBottomY: Float = 0f,
    /** 人体蒙版（非背景=255）：所有形变只在这个范围内生效，背景保持不动 */
    val personMask: ByteArray? = null,
    val maskSize: Int = 0,
    // 是否真的检测到人脸/人体。没检测到就不能拿默认坐标硬套，
    // 否则“瘦脸/大眼”会落在画面中央（用户看到的就是“没对准脸”）。
    val hasFace: Boolean = false,
    val hasPose: Boolean = false
) {
    /**
     * 腿段上界（已按穿着决定）：长裤=胯，裙子/短裤=裙摆下沿，兜底=膝盖。
     * 裙摆比膝盖低太多（袜子、长靴被误判成裙子）时不采信，退回膝盖。
     */
    val legTopResolved: Float
        get() = when {
            hasClothes && clothesIsPants -> hipY
            hasClothes && (clothesHemY - ankleY) > 0.04f &&
                    clothesHemY > legTopY - 0.06f -> clothesHemY
            else -> legTopY
        }

    /** 拉腿是否可用 */
    val legOk: Boolean
        get() = hasPose && (legTopResolved - ankleY) > 0.03f && ankleY > 0.005f

    /** 瘦腰是否可用：腰必须在腿段上沿附近，半身照（看不到腿）不生效 */
    val waistOk: Boolean
        get() = hasPose && bodyHalfW > 0.04f &&
                waistY > 0.18f && waistY < 0.95f &&
                (waistY - legTopResolved) > -0.06f &&
                (waistY - legTopResolved) < 0.32f

    companion object {
        // MediaPipe 归一化坐标(y 向下) -> GL 坐标(y 向上)
        fun toGL(x: Float, y: Float) = PointF(x, 1f - y)
    }
}

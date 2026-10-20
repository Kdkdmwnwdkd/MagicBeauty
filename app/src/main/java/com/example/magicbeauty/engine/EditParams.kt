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
    var whiten: Float = 0f,      // 美白（只提亮肤色区域）
    var filterId: Int = 0,       // 滤镜预设（见 Filters.presets）
    /** 滤镜强度 0..1。专业工作流建议 60%~85%，全强度会压掉中间调对比度 */
    var filterStrength: Float = 0.85f,
    // 调色进阶
    var sharpen: Float = 0f,     // 锐化 0..1（USM，边缘保持）
    var grain: Float = 0f,       // 颗粒 0..1（中间调最明显）
    var highlight: Float = 0f,   // 高光 -1..1（正=提亮）
    var shadow: Float = 0f,      // 阴影 -1..1（正=提亮）
    /** HSL 六色分频：索引 0=红 1=橙/黄 2=绿 3=青 4=蓝 5=品红。
     *  hue: -0.5..0.5 色相偏移；sat: 0..2 饱和度增益；lum: -0.3..0.3 明度偏移 */
    var hslHue: FloatArray = FloatArray(6),
    var hslSat: FloatArray = FloatArray(6) { 1f },
    var hslLum: FloatArray = FloatArray(6),
    /** 曲线 LUT：256 项，每项 0xRRGGBB；null = 不启用 */
    var curveLut: IntArray? = null,
    // 边框（画框）：宽 0..0.08（相对图像高度），颜色 RGB 0..1
    var borderWidth: Float = 0f,
    var borderColor: FloatArray = floatArrayOf(1f, 1f, 1f),
    /** LUT 版本号：每次编辑 +1，引擎据此判断是否需要重新上传纹理 */
    var curveVersion: Int = 0,
    // 人脸形变
    var thinFace: Float = 0f,    // 瘦脸
    var eyeEnlarge: Float = 0f,  // 大眼
    var noseSlim: Float = 0f,    // 瘦鼻
    var mouthSlim: Float = 0f,   // 缩唇
    var chinSlim: Float = 0f,    // 瘦下巴
    // 身材形变
    var waistSlim: Float = 0f,   // 瘦腰
    var legStretch: Float = 0f,  // 拉腿/调身高
    var shoulderSlim: Float = 0f,// 瘦肩
    var armSlim: Float = 0f,     // 瘦手臂
    var hipSlim: Float = 0f,     // 瘦臀
    var legSlim: Float = 0f      // 瘦腿
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
    // —— 面部重塑引导（face mesh 478 点推导）——
    /** 鼻中心（鼻尖附近）与半宽（两个鼻孔间距的一半） */
    val nose: PointF = PointF(0.5f, 0.70f),
    val noseHalfW: Float = 0.030f,
    /** 嘴中心与半宽（两口角间距的一半） */
    val mouth: PointF = PointF(0.5f, 0.62f),
    val mouthHalfW: Float = 0.040f,
    /** 下巴中心与半宽（下巴颏处脸宽的一半） */
    val chin: PointF = PointF(0.5f, 0.50f),
    val chinHalfW: Float = 0.055f,
    val bodyCenter: PointF = PointF(0.5f, 0.45f),
    val bodyHalfW: Float = 0.18f,
    val waistY: Float = 0.45f,
    val hipY: Float = 0.35f,
    /** 腿段上界：优先用膝盖（这样裙子/长上衣只会整体上移，不会被拉长） */
    val legTopY: Float = 0.35f,
    val ankleY: Float = 0.05f,
    // —— 瘦肩/瘦手臂/瘦臀/瘦腿 引导（pose 优先，人体解析兜底）——
    /** 肩线高度（GL）与肩线半宽 */
    val shoulderY: Float = 0f,
    val shoulderHalfW: Float = 0f,
    /** 手臂带（左右各一条竖直带）：上下界（GL）与半宽、左右中心 x */
    val armTopY: Float = 0f,
    val armBotY: Float = 0f,
    val armHalfW: Float = 0f,
    val armCxL: Float = 0f,
    val armCxR: Float = 0f,
    /** 手臂引导是否可用：需要 pose 的肩/肘/腕（解析分不出被头发遮住的手臂） */
    val hasArms: Boolean = false,
    /** 胯宽半宽（胯骨实际宽，瘦臀作用带） */
    val hipHalfW: Float = 0f,
    /** 腿宽半宽（大腿中段实测，瘦腿作用带） */
    val legHalfW: Float = 0f,
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
    val hasPose: Boolean = false,
    /** 手动定位兜底（用户拖动引导线设定）：裙摆线/脚踝线，GL y；
     *  非 null 时优先于检测值，且不再要求 hasPose */
    val manualHemY: Float? = null,
    val manualAnkleY: Float? = null
) {
    /**
     * 腿段上界（已按穿着决定）：长裤=胯，裙子/短裤=裙摆下沿，兜底=膝盖。
     * 裙摆比膝盖低太多（袜子、长靴被误判成裙子）时不采信，退回膝盖。
     * 用户手动拖过裙摆线时以手动值为准。
     */
    val legTopResolved: Float
        get() = manualHemY ?: when {
            hasClothes && clothesIsPants -> hipY
            hasClothes && (clothesHemY - ankleY) > 0.04f &&
                    clothesHemY > legTopY - 0.06f -> clothesHemY
            else -> legTopY
        }

    /** 拉腿是否可用：自动检测要 hasPose；手动定位两条线齐全即可 */
    val legOk: Boolean
        get() = if (manualHemY != null && manualAnkleY != null) {
            (manualHemY - manualAnkleY) > 0.03f && manualAnkleY > 0.005f
        } else {
            hasPose && (legTopResolved - ankleY) > 0.03f && ankleY > 0.005f
        }

    /** 瘦腰是否可用：腰必须在腿段上沿附近，半身照（看不到腿）不生效 */
    val waistOk: Boolean
        get() = hasPose && bodyHalfW > 0.04f &&
                waistY > 0.18f && waistY < 0.95f &&
                (waistY - legTopResolved) > -0.06f &&
                (waistY - legTopResolved) < 0.32f

    /** 瘦肩是否可用 */
    val shoulderOk: Boolean
        get() = hasPose && shoulderHalfW > 0.030f && shoulderY > 0.30f && shoulderY < 0.98f

    /** 瘦手臂是否可用（必须有 pose 的肩肘腕） */
    val armOk: Boolean
        get() = hasArms && armHalfW > 0.015f && (armTopY - armBotY) > 0.05f

    /** 瘦臀是否可用 */
    val hipOk: Boolean
        get() = hasPose && hipHalfW > 0.030f && hipY > 0.05f && hipY < 0.95f

    /** 瘦腿是否可用：腿段要够长，腿宽要量到 */
    val legSlimOk: Boolean
        get() = hasPose && legHalfW > 0.020f &&
                (legTopResolved - (manualAnkleY ?: ankleY)) > 0.04f

    /** 面部重塑（鼻/唇/下巴）是否可用 */
    val faceFeatureOk: Boolean
        get() = hasFace

    companion object {
        // MediaPipe 归一化坐标(y 向下) -> GL 坐标(y 向上)
        fun toGL(x: Float, y: Float) = PointF(x, 1f - y)
    }
}

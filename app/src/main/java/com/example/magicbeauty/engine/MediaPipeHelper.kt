package com.example.magicbeauty.engine

import android.content.Context
import android.graphics.PointF
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.core.RunningMode

/**
 * 用 MediaPipe（开源、本地、免费）提取人脸 468 点与人体 33 点，
 * 推导出 LocalEditEngine 需要的形变引导标量（WarpGuide）。
 * 注意：只在本机推理，不上传、不联网（除首次加载本地 .task 模型）。
 */
class MediaPipeHelper(context: Context) {

    private val appContext = context.applicationContext

    /** 衣服下沿分析（判断裙子/短裤/长裤），只在照片分析时启用，视频逐帧太慢 */
    private val clothingAnalyzer = ClothingAnalyzer(context)

    /** 更细的人体解析（LIP 20 类），优先使用；失败再回退到 6 类分割 */
    private val humanParsing = HumanParsingAnalyzer(context)

    private var faceCreated = false
    private var poseCreated = false

    private val faceLandmarker: FaceLandmarker by lazy {
        faceCreated = true
        val base = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .setDelegate(Delegate.CPU)
            .build()
        val opt = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(1)
            .build()
        FaceLandmarker.createFromOptions(appContext, opt)
    }

    private val poseLandmarker: PoseLandmarker by lazy {
        poseCreated = true
        val base = BaseOptions.builder()
            .setModelAssetPath("pose_landmarker_lite.task")
            .setDelegate(Delegate.CPU)
            .build()
        val opt = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setNumPoses(1)
            .build()
        PoseLandmarker.createFromOptions(appContext, opt)
    }

    /**
     * 分析一张图，返回形变引导。检测失败则返回默认（无形变）。
     * @param withClothing 是否做衣服分割（照片用 true；视频逐帧用 false 省时间）
     */
    fun analyze(bitmap: Bitmap, withClothing: Boolean = true): WarpGuide {
        val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
        var guide = WarpGuide()
        // uv 是按宽高各自归一化的，算距离/半径时要换算到同一尺度（以图片高度为单位）
        val aspect = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
        try {
            val faceRes = faceLandmarker.detect(mpImage)
            if (faceRes.faceLandmarks().isNotEmpty()) {
                val lm = faceRes.faceLandmarks()[0]
                val center = mid(lm[10], lm[152])
                // 234 / 454 = 左右脸颊最外点，用它们算半脸宽（比用整张脸的包围盒稳）
                val halfW = kotlin.math.abs(lm[234].x() - lm[454].x()) / 2f
                // 眼角间距换算成“高度单位”，供大眼半径使用
                val eyeDx = (lm[33].x() - lm[133].x()) * aspect
                val eyeDy = lm[33].y() - lm[133].y()
                val eyeW = kotlin.math.sqrt(eyeDx * eyeDx + eyeDy * eyeDy)
                // 眼睛相对于脸的大小：小眼睛多放大一点，大眼睛少放大一点，
                // 避免“本来眼睛就大”的人一拉就变成瞪眼。
                val faceW = (halfW * 2f * aspect).coerceAtLeast(0.02f)
                val eyeRatio = (eyeW / faceW).coerceIn(0.06f, 0.40f)
                val eyeGain = (0.17f / eyeRatio).coerceIn(0.55f, 1.30f)
                // —— 面部重塑引导（478 点 mesh）——
                // 鼻：鼻尖 1、鼻梁 168，半宽取两个鼻孔 98/327 间距的一半
                val noseC = mid(lm[1], lm[168])
                val noseHW = dist(lm[98], lm[327]) / 2f
                // 嘴：两口角 61/291，半宽取其间距一半
                val mouthC = mid(lm[61], lm[291])
                val mouthHW = dist(lm[61], lm[291]) / 2f
                // 下巴：颏部 152，半宽取脸颊 234/454 间距的 72%（下巴比脸颊窄）
                val chinHW = dist(lm[234], lm[454]) / 2f * 0.72f
                guide = guide.copy(
                    faceCenter = WarpGuide.toGL(center.x, center.y),
                    faceHalfW = halfW.coerceIn(0.04f, 0.30f),
                    faceHalfH = (kotlin.math.abs(lm[10].y() - lm[152].y()) / 2f).coerceIn(0.04f, 0.35f),
                    eyeL = WarpGuide.toGL(mid(lm[33], lm[133]).x, mid(lm[33], lm[133]).y),
                    eyeR = WarpGuide.toGL(mid(lm[263], lm[362]).x, mid(lm[263], lm[362]).y),
                    eyeRadius = (eyeW * 1.30f).coerceIn(0.02f, 0.12f),
                    eyeGain = eyeGain,
                    nose = WarpGuide.toGL(noseC.x, noseC.y),
                    noseHalfW = noseHW.coerceIn(0.012f, 0.06f),
                    mouth = WarpGuide.toGL(mouthC.x, mouthC.y),
                    mouthHalfW = mouthHW.coerceIn(0.015f, 0.08f),
                    chin = WarpGuide.toGL(lm[152].x(), lm[152].y()),
                    chinHalfW = chinHW.coerceIn(0.02f, 0.12f),
                    hasFace = true
                )
            }
        } catch (_: Exception) { /* 保留默认 */ }

        try {
            val poseRes = poseLandmarker.detect(mpImage)
            if (poseRes.landmarks().isNotEmpty()) {
                val p = poseRes.landmarks()[0]
                val hip = mid(p[23], p[24])
                val shoulder = mid(p[11], p[12])
                val ankle = mid(p[27], p[28])
                // 关键点数据合理性守卫：背面+复杂背景时 lite 模型会给出错乱的
                // 全身点（实测楼梯间背面照：脚踝 y=0.569 比胯 y=0.496 还"低"，
                // 即 GL 坐标里脚踝跑到胯上面，腿长变成负数）。
                // 这种数据宁可不用——用解析结果自己推，也不能拿它当基准。
                val poseSane = ankle.y > hip.y + 0.02f && hip.y > shoulder.y &&
                        shoulder.y > 0.02f && ankle.y < 1.05f
                if (poseSane) {
                    // 腰在胯之上：取肩胯连线上、靠胯 25% 的位置（原来直接用胯，位置偏低）
                    val waistYImg = hip.y - 0.25f * (hip.y - shoulder.y)
                    // 半身宽取「胯宽」和「肩宽一半」里较大的那个：
                    // 拉腿的横向权重靠它定范围，取窄了会把裙摆/阔腿裤的边缘留在原地，
                    // 看起来就是“身体上去了、裙子还拖在下面”。
                    val hipHalf = dist(p[23], p[24]) / 2f
                    val shoulderHalf = dist(p[11], p[12]) * 0.50f
                    val halfW = maxOf(hipHalf, shoulderHalf)

                    // 腿段上界优先取膝盖：裙子、长上衣基本都在膝盖以上，
                    // 以膝盖为界它们只会跟着上半身整体上移，不会被纵向拉长。
                    // 膝盖检测不可靠（被遮挡/位置不合理）时才退回胯部。
                    val knee = mid(p[25], p[26])
                    val kneeOk = knee.y > hip.y + 0.02f && knee.y < ankle.y - 0.04f
                    val legTopImg = if (kneeOk) knee.y else hip.y

                    // —— 瘦肩/瘦手臂/瘦臀/瘦腿 引导（骨骼直接给）——
                    val shL = p[11]; val shR = p[12]
                    val elL = p[13]; val elR = p[14]
                    val wrL = p[15]; val wrR = p[16]
                    // 手臂带：肩→腕的竖直范围（上下各留 1.5% 余量），
                    // 中心取肩与腕连线的中点 x（手臂弯曲时也大致压在手臂上）
                    val armTopImg = minOf(shL.y, wrL.y, shR.y, wrR.y)
                    val armBotImg = maxOf(shL.y, wrL.y, shR.y, wrR.y)
                    val armHalf = maxOf(dist(shL, elL), dist(shR, elR)) * 0.30f
                    // 胯宽 = 胯骨间距 ×1.05（胯骨外侧还有肉，瘦臀要覆盖到）

                    guide = guide.copy(
                        bodyCenter = WarpGuide.toGL(hip.x, waistYImg),
                        bodyHalfW = halfW.coerceIn(0.05f, 0.35f),
                        waistY = 1f - waistYImg,
                        hipY = 1f - hip.y,
                        legTopY = 1f - legTopImg,
                        ankleY = 1f - ankle.y,
                        shoulderY = 1f - shoulder.y,
                        shoulderHalfW = shoulderHalf.coerceIn(0.04f, 0.40f),
                        armTopY = (1f - armTopImg + 0.015f).coerceIn(0f, 1f),
                        armBotY = (1f - armBotImg - 0.015f).coerceIn(0f, 1f),
                        armHalfW = armHalf.coerceIn(0.018f, 0.10f),
                        armCxL = (shL.x + wrL.x) / 2f,
                        armCxR = (shR.x + wrR.x) / 2f,
                        hasArms = true,
                        hipHalfW = (hipHalf * 1.05f).coerceIn(0.03f, 0.40f),
                        hasPose = true
                    )
                }
            }
        } catch (_: Exception) { /* 保留默认 */ }

        // 衣服下沿：先用 LIP 20 类人体解析（裙/裤/袜/鞋分得清），失败再回退 6 类分割。
        // 注意不要加 guide.hasPose 条件：解析器自己能推腿/腰位置，
        // 加上就退回到“检不到关键点就什么都不做”的老问题。
        if (withClothing) {
            try {
                val hp = humanParsing.analyze(bitmap, guide)
                if (hp != null) {
                    // 关键点（pose）没检出时，用解析结果自己推出来的位置顶上，
                    // 否则瘦腰/拉腿会被整体清零 —— 这正是“拉滑块没反应”的主要原因。
                    val usePose = guide.hasPose && (guide.ankleY - guide.hipY) > 0.05f
                    val waistY = if (usePose) guide.waistY else hp.waistY
                    guide = guide.copy(
                        clothesHemY = hp.hemY,
                        clothesHalfW = hp.hemHalfW,
                        clothesIsPants = hp.isPants,
                        clothesEdgeLift = hp.edgeLift,
                        hasClothes = true,
                        // 分割不可靠时宁可不给蒙版：白丝袜+浅墙这类会被判成背景，
                        // 蒙版在腿上是 0，位移全被抹掉，看起来就是“拉腿没反应”。
                        personMask = if (hp.maskReliable) hp.mask else null,
                        maskSize = if (hp.maskReliable) hp.maskSize else 0,
                        hipY = if (usePose) guide.hipY else hp.hipY,
                        legTopY = if (usePose) guide.legTopY else hp.legTopY,
                        ankleY = if (usePose) guide.ankleY else hp.ankleY,
                        // 瘦肩/瘦臀/瘦腿 引导：pose 有时用骨骼值，没有就用解析实测值
                        shoulderY = if (usePose) guide.shoulderY else hp.shoulderY,
                        shoulderHalfW = if (usePose) guide.shoulderHalfW else hp.shoulderHalfW,
                        hipHalfW = if (usePose) guide.hipHalfW else hp.hipHalfW,
                        legHalfW = if (usePose) guide.legHalfW else hp.legHalfW,
                        // 中线与腰宽一律用解析值：它是按实际像素量的，
                        // 比骨骼肩宽的估计更贴腰，瘦腰范围才不会把墙/门框卷进来。
                        bodyCenter = PointF(hp.centerX, waistY),
                        bodyHalfW = hp.waistHalfW,
                        waistY = waistY,
                        // 人体外接框：无蒙版兜底时限制位移垂直范围，头顶上方背景不动
                        bodyTopY = hp.bodyTopY,
                        bodyBottomY = hp.bodyBottomY,
                        hasPose = usePose || hp.hasBody
                    )
                    return guide
                }
            } catch (_: Exception) { /* 继续回退 */ }

            try {
                clothingAnalyzer.analyze(bitmap, guide)?.let { c ->
                    guide = guide.copy(
                        clothesHemY = c.hemY,
                        clothesHalfW = c.hemHalfW,
                        clothesIsPants = c.isPants,
                        clothesEdgeLift = c.edgeLift,
                        hasClothes = true
                    )
                }
            } catch (_: Exception) { /* 分割失败就用膝盖兜底 */ }
        }

        return guide
    }

    fun close() {
        // 只关已经真正创建过的，避免 close() 反过来把 lazy 模型加载一遍
        if (faceCreated) try { faceLandmarker.close() } catch (_: Exception) {}
        if (poseCreated) try { poseLandmarker.close() } catch (_: Exception) {}
        try { clothingAnalyzer.close() } catch (_: Exception) {}
        try { humanParsing.close() } catch (_: Exception) {}
    }

    private fun mid(a: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
                    b: com.google.mediapipe.tasks.components.containers.NormalizedLandmark) =
        object {
            val x get() = (a.x() + b.x()) / 2f
            val y get() = (a.y() + b.y()) / 2f
        }

    private fun dist(a: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
                     b: com.google.mediapipe.tasks.components.containers.NormalizedLandmark): Float {
        val dx = a.x() - b.x(); val dy = a.y() - b.y()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}

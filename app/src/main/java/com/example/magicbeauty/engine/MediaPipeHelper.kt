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
                guide = guide.copy(
                    faceCenter = WarpGuide.toGL(center.x, center.y),
                    faceHalfW = halfW.coerceIn(0.04f, 0.30f),
                    faceHalfH = (kotlin.math.abs(lm[10].y() - lm[152].y()) / 2f).coerceIn(0.04f, 0.35f),
                    eyeL = WarpGuide.toGL(mid(lm[33], lm[133]).x, mid(lm[33], lm[133]).y),
                    eyeR = WarpGuide.toGL(mid(lm[263], lm[362]).x, mid(lm[263], lm[362]).y),
                    eyeRadius = (eyeW * 1.30f).coerceIn(0.02f, 0.12f),
                    eyeGain = eyeGain,
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

                guide = guide.copy(
                    bodyCenter = WarpGuide.toGL(hip.x, waistYImg),
                    bodyHalfW = halfW.coerceIn(0.05f, 0.35f),
                    waistY = 1f - waistYImg,
                    hipY = 1f - hip.y,
                    legTopY = 1f - legTopImg,
                    ankleY = 1f - ankle.y,
                    hasPose = true
                )
            }
        } catch (_: Exception) { /* 保留默认 */ }

        // 衣服下沿：先用 LIP 20 类人体解析（裙/裤/袜/鞋分得清），失败再回退 6 类分割
        if (withClothing && guide.hasPose) {
            try {
                val hp = humanParsing.analyze(bitmap, guide)
                if (hp != null) {
                    // 关键点（pose）没检出时，用解析结果自己推出来的位置顶上，
                    // 否则瘦腰/拉腿会被整体清零 —— 这正是“拉滑块没反应”的主要原因。
                    val usePose = guide.hasPose && (guide.ankleY - guide.hipY) > 0.05f
                    guide = guide.copy(
                        clothesHemY = hp.hemY,
                        clothesHalfW = hp.hemHalfW,
                        clothesIsPants = hp.isPants,
                        clothesEdgeLift = hp.edgeLift,
                        hasClothes = true,
                        personMask = hp.mask,
                        maskSize = hp.maskSize,
                        hipY = if (usePose) guide.hipY else hp.hipY,
                        legTopY = if (usePose) guide.legTopY else hp.legTopY,
                        ankleY = if (usePose) guide.ankleY else hp.ankleY,
                        bodyCenter = if (usePose) guide.bodyCenter else PointF(hp.centerX, hp.waistY),
                        bodyHalfW = if (usePose) guide.bodyHalfW else hp.halfW,
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

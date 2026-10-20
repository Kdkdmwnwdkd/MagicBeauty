package com.example.magicbeauty.engine

import android.content.Context
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

    /** 分析一张图，返回形变引导。检测失败则返回默认（无形变）。 */
    fun analyze(bitmap: Bitmap): WarpGuide {
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
                guide = guide.copy(
                    faceCenter = WarpGuide.toGL(center.x, center.y),
                    faceHalfW = halfW.coerceIn(0.04f, 0.30f),
                    faceHalfH = (kotlin.math.abs(lm[10].y() - lm[152].y()) / 2f).coerceIn(0.04f, 0.35f),
                    eyeL = WarpGuide.toGL(mid(lm[33], lm[133]).x, mid(lm[33], lm[133]).y),
                    eyeR = WarpGuide.toGL(mid(lm[263], lm[362]).x, mid(lm[263], lm[362]).y),
                    eyeRadius = (eyeW * 1.35f).coerceIn(0.02f, 0.12f),
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
                guide = guide.copy(
                    bodyCenter = WarpGuide.toGL(hip.x, waistYImg),
                    bodyHalfW = halfW.coerceIn(0.05f, 0.35f),
                    waistY = 1f - waistYImg,
                    hipY = 1f - hip.y,
                    ankleY = 1f - ankle.y,
                    hasPose = true
                )
            }
        } catch (_: Exception) { /* 保留默认 */ }

        return guide
    }

    fun close() {
        // 只关已经真正创建过的，避免 close() 反过来把 lazy 模型加载一遍
        if (faceCreated) try { faceLandmarker.close() } catch (_: Exception) {}
        if (poseCreated) try { poseLandmarker.close() } catch (_: Exception) {}
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

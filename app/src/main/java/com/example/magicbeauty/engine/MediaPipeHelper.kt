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
import java.util.concurrent.Executors

/**
 * 用 MediaPipe（开源、本地、免费）提取人脸 468 点与人体 33 点，
 * 推导出 LocalEditEngine 需要的形变引导标量（WarpGuide）。
 * 注意：只在本机推理，不上传、不联网（除首次加载本地 .task 模型）。
 */
class MediaPipeHelper(context: Context) {

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()

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
        try {
            val faceRes = faceLandmarker.detect(mpImage)
            if (faceRes.faceLandmarks().isNotEmpty()) {
                val lm = faceRes.faceLandmarks()[0]
                guide = guide.copy(
                    faceCenter = WarpGuide.toGL(mid(lm[10], lm[152]).x, mid(lm[10], lm[152]).y),
                    faceHalfH = kotlin.math.abs(lm[10].y() - lm[152].y()) / 2f,
                    eyeL = WarpGuide.toGL(mid(lm[33], lm[133]).x, mid(lm[33], lm[133]).y),
                    eyeR = WarpGuide.toGL(mid(lm[263], lm[362]).x, mid(lm[263], lm[362]).y),
                    eyeRadius = dist(lm[33], lm[133]) * 1.6f
                )
            }
        } catch (_: Exception) { /* 保留默认 */ }

        try {
            val poseRes = poseLandmarker.detect(mpImage)
            if (poseRes.landmarks().isNotEmpty()) {
                val p = poseRes.landmarks()[0]
                val shoulderX = mid(p[11], p[12]).x
                val hip = mid(p[23], p[24])
                val ankle = mid(p[27], p[28])
                val g2 = WarpGuide.toGL(shoulderX, hip.y)
                guide = guide.copy(
                    bodyCenter = g2,
                    bodyHalfW = dist(p[11], p[12]) / 2f,
                    waistY = 1f - hip.y,         // 腰部 ≈ 胯部上沿
                    hipY = 1f - hip.y,
                    ankleY = 1f - ankle.y
                )
            }
        } catch (_: Exception) { /* 保留默认 */ }

        return guide
    }

    fun close() {
        // 只关已经真正创建过的，避免 close() 反过来把 lazy 模型加载一遍
        if (faceCreated) try { faceLandmarker.close() } catch (_: Exception) {}
        if (poseCreated) try { poseLandmarker.close() } catch (_: Exception) {}
        executor.shutdown()
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

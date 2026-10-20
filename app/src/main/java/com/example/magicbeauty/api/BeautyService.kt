package com.example.magicbeauty.api

import java.io.File

/**
 * API 聚合层：UI 只跟它打交道，不用关心底层实现。
 * 这里集中管理“必须走云端”的两类能力：视频瘦身、AI 生成头发丝。
 * （图片级美颜/瘦身、端侧染发已由本地引擎完成，无需经过这里。）
 */
object BeautyService {
    val siliconFlow = SiliconFlowImageApi()
    val videoSlim = VideoSlimApi()

    /** AI 生成头发丝：原图 + 提示词 -> 结果图字节 */
    suspend fun generateHair(imageBytes: ByteArray, prompt: String): ByteArray =
        siliconFlow.generateHair(imageBytes, prompt)

    /** 视频瘦身：本地视频文件 -> 处理后的视频字节（流式上传，不整包读内存） */
    suspend fun slimVideo(videoFile: File, filename: String, extra: Map<String, String> = emptyMap()): ByteArray =
        videoSlim.slim(videoFile, filename, extra)
}

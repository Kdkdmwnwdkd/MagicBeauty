package com.example.magicbeauty.api

/**
 * API 聚合层：UI 只跟它打交道，不用关心底层是腾讯云还是 SiliconFlow。
 * 这里集中管理“必须走云端”的两类能力：视频瘦身、AI 生成头发丝。
 * （图片级美颜/瘦身已由本地引擎完成，无需经过这里。）
 */
object BeautyService {
    val siliconFlow = SiliconFlowImageApi()
    val videoSlim = VideoSlimApi()

    /** AI 生成头发丝：原图 + 提示词 -> 结果图字节 */
    suspend fun generateHair(imageBytes: ByteArray, prompt: String): ByteArray =
        siliconFlow.generateHair(imageBytes, prompt)

    /** 视频瘦身：视频字节 -> 处理后的视频字节 */
    suspend fun slimVideo(videoBytes: ByteArray, filename: String, extra: Map<String, String> = emptyMap()): ByteArray =
        videoSlim.slim(videoBytes, filename, extra)
}

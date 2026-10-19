package com.example.magicbeauty.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 视频瘦身适配器。
 *
 * 说明：目前**没有通用的免费“视频人像瘦身”API**。视频瘦身属于“视频处理 AI”
 * （逐帧对人体做塑形再重新编码），不是生成式大模型。落地方式有两种：
 *   1) 自己写云函数/服务端：抽帧 -> 逐帧人体塑形 -> 重新编码（可用开源 MovieReshape/
 *      MediaPipe Pose + 本工程的形变思路），把成品接口地址填到 ApiConfig.videoSlimEndpoint；
 *   2) 直接购买厂商的视频美体 SDK/服务，把它们的上传接口地址填到这里。
 *
 * 本适配器只负责“把视频发给一个可配置端点、拿回处理后的视频”，与具体厂商解耦。
 */
class VideoSlimApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * @param videoBytes 原始视频
     * @param filename   文件名（用于推断 MIME，如 xxx.mp4）
     * @param extra      可选的额外表单字段（如强度 strength=0.5），会作为表单字段一并提交
     */
    suspend fun slim(
        videoBytes: ByteArray,
        filename: String = "input.mp4",
        extra: Map<String, String> = emptyMap()
    ): ByteArray = withContext(Dispatchers.IO) {
        val endpoint = ApiConfig.videoSlimEndpoint
        require(endpoint.isNotBlank()) {
            "未配置视频瘦身端点。请在 ApiConfig.videoSlimEndpoint 填入你的云函数/服务端地址，\n" +
            "或购买厂商视频美体服务后填入其上传接口。"
        }
        val mime = if (filename.endsWith(".mov")) "video/quicktime" else "video/mp4"
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        builder.addFormDataPart(
            "video", filename,
            videoBytes.toRequestBody(mime.toMediaType())
        )
        extra.forEach { (k, v) -> builder.addFormDataPart(k, v) }
        val body = builder.build()

        val request = Request.Builder().url(endpoint).post(body).build()
        client.newCall(request).execute().use { resp ->
            val bytes = resp.body?.bytes()
            require(resp.isSuccessful && bytes != null) { "视频瘦身请求失败: ${resp.code}" }
            bytes
        }
    }
}

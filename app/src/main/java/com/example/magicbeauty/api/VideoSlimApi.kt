package com.example.magicbeauty.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 视频瘦身适配器（仅在你配置了云端终点时使用）。
 *
 * 说明：目前**没有通用的免费“视频人像瘦身”API**。视频瘦身属于“视频处理 AI”
 * （逐帧对人体做塑形再重新编码），不是生成式大模型。落地方式有两种：
 *   1) 自己写云函数/服务端：抽帧 -> 逐帧人体塑形 -> 重新编码，把成品接口地址填到
 *      ApiConfig.videoSlimEndpoint；
 *   2) 直接购买厂商的视频美体 SDK/服务，把它们的上传接口地址填到这里。
 *
 * 这里用文件流式上传（而不是 readBytes），避免长视频整包读进内存导致 OOM。
 */
class VideoSlimApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    /**
     * @param videoFile 本地视频文件（调用方负责在请求结束后删除）
     * @param filename  表单里的文件名（用于推断 MIME）
     * @param extra     额外表单字段（如 strength=0.5）
     */
    suspend fun slim(
        videoFile: File,
        filename: String = "input.mp4",
        extra: Map<String, String> = emptyMap()
    ): ByteArray = withContext(Dispatchers.IO) {
        val endpoint = ApiConfig.videoSlimEndpoint
        require(endpoint.isNotBlank()) {
            "未配置视频瘦身端点。请在「设置」页填入你的云函数/服务端地址，\n" +
            "或购买厂商视频美体服务后填入其上传接口。"
        }
        require(videoFile.exists()) { "视频文件不存在" }

        val mime = if (filename.endsWith(".mov")) "video/quicktime" else "video/mp4"
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        builder.addFormDataPart("video", filename, videoFile.asRequestBody(mime.toMediaType()))
        extra.forEach { (k, v) -> builder.addFormDataPart(k, v) }

        val request = Request.Builder().url(endpoint).post(builder.build()).build()
        client.newCall(request).execute().use { resp ->
            val bytes = resp.body?.bytes()
            require(resp.isSuccessful && bytes != null) { "视频瘦身请求失败: ${resp.code}" }
            bytes
        }
    }
}

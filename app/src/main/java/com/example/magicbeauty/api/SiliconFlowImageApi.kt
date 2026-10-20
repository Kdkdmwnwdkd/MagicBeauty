package com.example.magicbeauty.api

import android.util.Base64
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 硅基流动（SiliconFlow）图像 API —— 用于「AI 生成头发丝」。
 *
 * 关键：硅基流动**没有** /v1/images/edits 这个接口（旧代码调它就是 404）。
 * 官方只有一个 POST /v1/images/generations，图生图/编辑是把原图放进 JSON 的 image 字段：
 *   {
 *     "model": "Qwen/Qwen-Image-Edit-2509",
 *     "prompt": "...",
 *     "image": "data:image/png;base64,xxxx"
 *   }
 * Qwen/Qwen-Image-Edit-2509 支持图片输入（且不支持 image_size 字段）。
 */
class SiliconFlowImageApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    /** 支持图片输入的编辑模型；FLUX.1-dev 是纯文生图，不能用于改图。 */
    var model: String = "Qwen/Qwen-Image-Edit-2509"

    /**
     * 生成头发丝：原图 + 提示词 -> 结果图字节。
     * @param imageBytes 原图（PNG 字节）
     * @param prompt 描述想要的发型/发丝
     */
    suspend fun generateHair(imageBytes: ByteArray, prompt: String): ByteArray =
        withContext(Dispatchers.IO) {
            val key = ApiConfig.siliconFlowKey
            require(key.isNotBlank()) {
                "未配置硅基流动密钥，请到 App 底部「设置」页填写（注册 cloud.siliconflow.cn 获取 sk- 开头的 Key）"
            }

            val b64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            val payload = linkedMapOf<String, Any>(
                "model" to model,
                "prompt" to prompt,
                "image" to "data:image/png;base64,$b64"
            )
            val body = gson.toJson(payload).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${ApiConfig.SILICONFLOW_URL}/generations")
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                require(resp.isSuccessful) { "SiliconFlow 返回 ${resp.code}: ${raw.take(300)}" }
                val json = gson.fromJson(raw, Map::class.java)
                val image = (json["images"] as? List<*>)?.firstOrNull() as? Map<*, *>
                val url = image?.get("url") as? String
                val b64Out = image?.get("b64_json") as? String
                when {
                    url != null -> download(url)
                    b64Out != null -> Base64.decode(b64Out, Base64.NO_WRAP)
                    else -> error("SiliconFlow 响应中未找到图片: ${raw.take(300)}")
                }
            }
        }

    private fun download(url: String): ByteArray {
        val req = Request.Builder().url(url).get().build()
        return client.newCall(req).execute().use { it.body?.bytes() ?: byteArrayOf() }
    }
}

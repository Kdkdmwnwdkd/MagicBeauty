package com.example.magicbeauty.api

import android.util.Base64
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 硅基流动（SiliconFlow）图像 API 适配器 —— 用于「AI 生成头发丝」。
 * 本质：把原图 + 提示词发给图像大模型做“局部重绘 / 图生图”，由模型生成本来没有的发丝。
 * 这是生成式大模型（非视频生成），手机端实时跑不了，才走云端 API。
 *
 * 默认走 /images/edits（图生图+局部重绘）。若你的模型只支持文本生图，
 * 可改用 generateByPrompt() 纯提示词生成。
 */
class SiliconFlowImageApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    // 可选模型：black-forest-labs/FLUX.1-dev、FLUX.1.1-pro、Kwai-Kolors/Kolors 等
    var model: String = "black-forest-labs/FLUX.1-dev"

    /**
     * 生成头发丝：上传原图 + 提示词，返回生成后的图片字节。
     * @param imageBytes 原图
     * @param prompt 描述想要的发型/发丝，例如 “flowing long wavy hair, extra wispy strands, anime style”
     */
    suspend fun generateHair(imageBytes: ByteArray, prompt: String): ByteArray =
        withContext(Dispatchers.IO) {
            require(ApiConfig.siliconFlowKey.isNotBlank()) { "未配置硅基流动密钥，请到 App 底部「设置」页填写（注册 cloud.siliconflow.cn 获取 sk- 开头的 Key）" }

            val imagePart = MultipartBody.Part.createFormData(
                "image", "input.png",
                imageBytes.toRequestBody("image/png".toMediaType())
            )
            val promptPart = MultipartBody.Part.createFormData("prompt", prompt)
            val modelPart = MultipartBody.Part.createFormData("model", model)
            // 可选 mask：留空则对整图按 prompt 重绘；如需只改头发，可上传头发区域蒙版
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addPart(imagePart)
                .addPart(promptPart)
                .addPart(modelPart)
                .build()

            val request = Request.Builder()
                .url("${ApiConfig.SILICONFLOW_URL}/edits")
                .addHeader("Authorization", "Bearer ${ApiConfig.siliconFlowKey}")
                .post(body)
                .build()

            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                require(resp.isSuccessful) { "SiliconFlow 返回错误: $raw" }
                val json = gson.fromJson(raw, Map::class.java)
                val images = (json["images"] as? List<*>)?.firstOrNull() as? Map<*, *>
                when {
                    images?.get("url") != null -> download(images["url"] as String)
                    images?.get("b64_json") != null ->
                        Base64.decode(images["b64_json"] as String, Base64.NO_WRAP)
                    else -> error("SiliconFlow 响应中未找到图片: $raw")
                }
            }
        }

    /** 纯提示词生成（不传原图）。 */
    suspend fun generateByPrompt(prompt: String): ByteArray = withContext(Dispatchers.IO) {
        require(ApiConfig.siliconFlowKey.isNotBlank()) { "未配置硅基流动密钥，请到 App 底部「设置」页填写" }
        val payload = gson.toJson(
            mapOf(
                "model" to model,
                "prompt" to prompt,
                "image_size" to "1024x1024",
                "n" to 1,
                "response_format" to "b64_json"
            )
        )
        val request = Request.Builder()
            .url("${ApiConfig.SILICONFLOW_URL}/generations")
            .addHeader("Authorization", "Bearer ${ApiConfig.siliconFlowKey}")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            require(resp.isSuccessful) { "SiliconFlow 返回错误: $raw" }
            val json = gson.fromJson(raw, Map::class.java)
            val images = (json["images"] as? List<*>)?.firstOrNull() as? Map<*, *>
            val b64 = images?.get("b64_json") as? String
                ?: images?.get("url") as? String
                ?: error("未找到图片: $raw")
            if (b64.startsWith("http")) download(b64) else Base64.decode(b64, Base64.NO_WRAP)
        }
    }

    private fun download(url: String): ByteArray {
        val req = Request.Builder().url(url).get().build()
        return client.newCall(req).execute().use { it.body?.bytes() ?: byteArrayOf() }
    }
}

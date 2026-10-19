package com.example.magicbeauty.api

import android.util.Base64
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.Spec.EncodedKeySpec
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 腾讯云「人脸美颜 FaceBeautify」适配器（TC3-HMAC-SHA256 签名）。
 * 免费额度：每月 1000 次（个人足够）；之后约 0.0088 元/次。
 * 用途：图片级美颜/瘦脸，作为本地引擎之外“图片”场景的云端补充（可选）。
 */
class TencentCloudBeautyApi {

    private val client = OkHttpClient()
    private val gson = Gson()

    data class BeautyParam(
        val Whiten: Int = 60,       // 美白 0-100
        val Smoothing: Int = 60,    // 磨皮 0-100
        val FaceLifting: Int = 50,  // 瘦脸 0-100
        val EyeEnlarging: Int = 40  // 大眼 0-100
    )

    /** 输入图片字节，返回美颜后的图片字节。 */
    suspend fun beautifyImage(input: ByteArray, param: BeautyParam = BeautyParam()): ByteArray =
        withContext(Dispatchers.IO) {
            require(ApiConfig.tencentSecretId.isNotBlank()) { "未配置腾讯云密钥，请在 secrets.properties 填写 TENCENT_SECRET_ID/KEY" }
            val b64 = Base64.encodeToString(input, Base64.NO_WRAP)
            val body = gson.toJson(mapOf("Image" to b64, "BeautyParam" to param))
            val resp = postSigned("FaceBeautify", body)
            val imageB64 = gson.fromJson(resp, Map::class.java)["Response"]
                .let { (it as Map<*, *>)["Image"] as String }
            Base64.decode(imageB64, Base64.NO_WRAP)
        }

    /** 构造并发送经过 TC3 签名的请求。 */
    private fun postSigned(action: String, jsonBody: String): String {
        val timestamp = System.currentTimeMillis() / 1000
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestamp * 1000))

        val hashedPayload = sha256Hex(jsonBody.toByteArray(StandardCharsets.UTF_8))
        val canonicalHeaders = "content-type:application/json; charset=utf-8\nhost:${ApiConfig.TENCENT_HOST}\n"
        val signedHeaders = "content-type;host"
        val canonicalRequest = buildString {
            append("POST\n/\n\n")
            append(canonicalHeaders)
            append("\n")
            append(signedHeaders)
            append("\n")
            append(hashedPayload)
        }

        val credentialScope = "${date}/${ApiConfig.TENCENT_SERVICE}/request"
        val stringToSign = buildString {
            append("TC3-HMAC-SHA256\n")
            append(timestamp)
            append("\n")
            append(credentialScope)
            append("\n")
            append(sha256Hex(canonicalRequest.toByteArray(StandardCharsets.UTF_8)))
        }

        val secretDate = hmacSHA256("TC3${ApiConfig.tencentSecretKey}", date)
        val secretService = hmacSHA256(secretDate, ApiConfig.TENCENT_SERVICE)
        val secretSigning = hmacSHA256(secretService, "request")
        val signature = bytesToHex(hmacSHA256(secretSigning, stringToSign))

        val authorization = "TC3-HMAC-SHA256 Credential=${ApiConfig.tencentSecretId}/$credentialScope, " +
                "SignedHeaders=$signedHeaders, Signature=$signature"

        val request = Request.Builder()
            .url("https://${ApiConfig.TENCENT_HOST}")
            .addHeader("Authorization", authorization)
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .addHeader("Host", ApiConfig.TENCENT_HOST)
            .addHeader("X-TC-Action", action)
            .addHeader("X-TC-Version", ApiConfig.TENCENT_VERSION)
            .addHeader("X-TC-Timestamp", timestamp.toString())
            .addHeader("X-TC-Region", ApiConfig.TENCENT_REGION)
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            require(resp.isSuccessful) { "腾讯云返回错误: $body" }
            return body
        }
    }

    private fun sha256Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return bytesToHex(md.digest(data))
    }

    private fun hmacSHA256(key: String, data: String): ByteArray =
        hmacSHA256(key.toByteArray(StandardCharsets.UTF_8), data.toByteArray(StandardCharsets.UTF_8))

    private fun hmacSHA256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}

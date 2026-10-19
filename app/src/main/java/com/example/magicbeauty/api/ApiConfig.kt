package com.example.magicbeauty.api

import com.example.magicbeauty.BuildConfig

/**
 * API 配置。密钥来自 app/build.gradle.kts 注入的 BuildConfig 字段，
 * 真实值写在项目根目录 secrets.properties（已被 .gitignore，不会提交）。
 * 若为空字符串，调用对应功能时会直接抛错提示“未配置密钥”。
 */
object ApiConfig {
    val tencentSecretId: String = BuildConfig.TENCENT_SECRET_ID
    val tencentSecretKey: String = BuildConfig.TENCENT_SECRET_KEY
    val siliconFlowKey: String = BuildConfig.SILICONFLOW_API_KEY

    // 腾讯云人脸美颜 API 配置
    const val TENCENT_HOST = "iai.tencentcloudapi.com"
    const val TENCENT_SERVICE = "iai"
    const val TENCENT_VERSION = "2020-03-04"
    const val TENCENT_REGION = "ap-guangzhou"

    // 硅基流动（SiliconFlow）图像 API
    const val SILICONFLOW_URL = "https://api.siliconflow.cn/v1/images"

    // 视频瘦身：没有通用的免费“视频瘦身”API，这里用可配置端点。
    // 你可以填自己的云函数/服务端地址，或任意支持视频人像处理的厂商接口。
    var videoSlimEndpoint: String = ""
}

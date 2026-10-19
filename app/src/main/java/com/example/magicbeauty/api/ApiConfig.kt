package com.example.magicbeauty.api

import com.example.magicbeauty.Prefs

/**
 * API 配置。密钥在 App「设置」页填写，存于本机 SharedPreferences，不写在代码里，
 * 方便无电脑的用户直接在手机上粘贴 key 使用。
 */
object ApiConfig {
    val tencentSecretId: String get() = ""
    val tencentSecretKey: String get() = ""

    // 硅基流动：从本机设置读取
    val siliconFlowKey: String get() = Prefs.siliconFlowKey

    // 腾讯云人脸美颜 API 配置
    const val TENCENT_HOST = "iai.tencentcloudapi.com"
    const val TENCENT_SERVICE = "iai"
    const val TENCENT_VERSION = "2020-03-04"
    const val TENCENT_REGION = "ap-guangzhou"

    // 硅基流动（SiliconFlow）图像 API
    const val SILICONFLOW_URL = "https://api.siliconflow.cn/v1/images"

    // 视频瘦身：默认空 = 走端侧本地处理（免费离线）；
    // 若填入你自己的云函数/服务端地址，则走云端处理。
    var videoSlimEndpoint: String
        get() = Prefs.videoSlimEndpoint
        set(v) { Prefs.videoSlimEndpoint = v }
}

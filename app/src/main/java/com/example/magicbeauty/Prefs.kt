package com.example.magicbeauty

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地密钥/配置存储。密钥只保存在本机 SharedPreferences，绝对不上传、不进代码、不进 git。
 * 这样无电脑的用户也能在手机上直接填 Key 使用云端功能（画发丝等）。
 */
object Prefs {
    private const val NAME = "magicbeauty_prefs"
    private const val K_SF = "siliconflow_key"
    private const val K_VS = "video_slim_endpoint"

    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    /** 硅基流动 API Key（画发丝用）。 */
    var siliconFlowKey: String
        get() = sp.getString(K_SF, "") ?: ""
        set(v) { sp.edit().putString(K_SF, v.trim()).apply() }

    /** 视频瘦身云端终点（可选；留空则走端侧本地处理）。 */
    var videoSlimEndpoint: String
        get() = sp.getString(K_VS, "") ?: ""
        set(v) { sp.edit().putString(K_VS, v.trim()).apply() }
}

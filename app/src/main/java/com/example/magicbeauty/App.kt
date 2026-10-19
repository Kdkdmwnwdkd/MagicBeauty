package com.example.magicbeauty

import android.app.Application

/**
 * 全局 Application：初始化本地密钥存储（SharedPreferences）。
 * 这样用户无需电脑、无需改代码，在手机「设置」页直接粘贴 API Key 即可使用。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
    }
}

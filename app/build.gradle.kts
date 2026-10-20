import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ============================================================
// 签名密钥读取：优先环境变量 → keystore.properties → 仓库内置默认值
// 密钥文件默认 app/keystore/magicbeauty.jks（已随仓库提交，保证签名恒定）
// ============================================================

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

fun secret(key: String, fallback: String? = null): String? {
    val env = System.getenv(key)
    if (!env.isNullOrBlank()) return env
    val v = keystoreProps.getProperty(key)
    if (!v.isNullOrBlank()) return v
    return fallback
}

android {
    namespace = "com.example.magicbeauty"
    compileSdk = 34

    signingConfigs {
        create("shared") {
            val storePath = secret("MAGICBEESTORE_FILE", "app/keystore/magicbeauty.jks")
            storeFile = rootProject.file(storePath!!)
            storePassword = secret("MAGICBEESTORE_PASSWORD", "magicbeauty2027")
            keyAlias = secret("MAGICBEESTORE_KEY_ALIAS", "magicbeauty")
            keyPassword = secret("MAGICBEESTORE_KEY_PASSWORD", "magicbeauty2027")
        }
    }

    defaultConfig {
        applicationId = "com.example.magicbeauty"
        minSdk = 24
        targetSdk = 34
        versionCode = 28
        versionName = "2.7.2"

        // 仅保留常用 ABI，缩小包体
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }

        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            // debug 也用同一把固定密钥，保证本地包与线上包可互相覆盖安装
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }

    buildFeatures { viewBinding = true }

    // MediaPipe 需要直接 mmap 模型文件，.task / .tflite 都不能压缩
    androidResources { noCompress.addAll(listOf("task", "tflite")) }
}

dependencies {
    // 基础
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // MediaPipe（本地人脸/人体关键点，开源免费）
    implementation("com.google.mediapipe:tasks-vision:0.10.29")

    // ONNX Runtime：跑人体解析（LIP 20 类：裙/裤/袜/鞋分开），比 6 类分割细得多
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")

    // 协程（API 异步）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 外部 API（仅画发丝 / 可选云端视频瘦身使用）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // GPUPixel（可选，生产级美颜）：把官方 AAR 放到 app/libs/ 后取消下一行注释
    // implementation(files("libs/gpupixel-release.aar"))
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.magicbeauty"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.magicbeauty"
        minSdk = 24
        targetSdk = 34
        versionCode = 8
        versionName = "1.5.0"

        // 仅保留常用 ABI，缩小包体
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }

        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // MediaPipe（本地人脸/人体关键点，开源免费）
    implementation("com.google.mediapipe:tasks-vision:0.10.29")

    // 协程（API 异步）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 外部 API（仅画发丝 / 可选云端视频瘦身使用）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // GPUPixel（可选，生产级美颜）：把官方 AAR 放到 app/libs/ 后取消下一行注释
    // implementation(files("libs/gpupixel-release.aar"))
}

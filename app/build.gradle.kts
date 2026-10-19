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
        versionCode = 1
        versionName = "1.0.0"

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

    // 不压缩 .task 模型文件，MediaPipe 需直接 mmap
    aaptOptions { noCompress += listOf("task") }
}

dependencies {
    // 基础
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")

    // 相机（本地取流）
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // MediaPipe（本地人脸/人体关键点，开源免费）
    implementation("com.google.mediapipe:tasks-vision:0.10.29")

    // 协程（API 异步）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 外部 API（仅视频瘦身 / 画发丝 使用）
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // GPUPixel（可选，生产级美颜）：把官方 AAR 放到 app/libs/ 后取消下一行注释
    // implementation(files("libs/gpupixel-release.aar"))
}

# 保留 MediaPipe / OpenGL 相关
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Retrofit / OkHttp / Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-dontwarn retrofit2.**
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**

# 不混淆自己的模型与配置
-keep class com.example.magicbeauty.api.** { *; }

// MagicBeauty 根工程配置
// 说明：本工程为纯本地开源方案（MediaPipe + OpenGL ES）做基础 P 图，
// 仅「视频瘦身」与「AI 生成头发丝」调用外部 API（见 app/build.gradle.kts 的 api 依赖）。
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("de.undercouch.download") version "6.1.0" apply false
}

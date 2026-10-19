# MagicBeauty 美图工坊（安卓）

给表妹用的个人 P 图 App。核心原则：**开源能本地做的，全在手机上做；只有「视频瘦身」和「AI 生成头发丝」才走外部 API。**

- **本地（开源、不上传、零成本）**：滤镜 / 调色（亮度·对比度·饱和·冷暖·暗角）、磨皮、瘦脸、大眼、瘦腰、拉腿·调身高、染发（可扩展）。用 **MediaPipe** 提人脸/人体关键点 + 自写 **OpenGL ES** 着色器实时渲染。
- **端侧本地（免费、离线，手机直接计算）**：视频瘦身（`LocalVideoSlimEngine`，逐帧人体关键点 + 局部 warp）。
- **外部 API（仅一件，可选）**：AI 生成头发丝 → `SiliconFlowImageApi`（生成式大模型，局部重绘）。
- 视频瘦身也支持在「设置」填云端终点走云端（更快/更精修），不填则默认端侧。

> 关于你问的「这俩是 AI 还是生成视频的大模型」：
> - **视频瘦身 = AI 视频处理**（人体分割 + 网格形变逐帧塑形再合成），不是生成式大模型。
> - **画头发丝 = 生成式大模型**（图像大模型凭空生成发丝像素），属于生成式 AI。

---

## 一、功能与成本对照

| 功能 | 实现位置 | 是否联网 | 成本 |
|---|---|---|---|
| 滤镜 / 调色 / 磨皮 | 本地 OpenGL ES | 否 | 免费 |
| 瘦脸 / 大眼 | 本地（MediaPipe 关键点驱动） | 否 | 免费 |
| 瘦腰 / 拉腿 / 调身高 | 本地（姿态关键点驱动 warp） | 否 | 免费 |
| 染发（可扩展） | 本地（接 hair_segmentation 模型） | 否 | 免费 |
| 视频瘦身 | 端侧本地（可选云端） | 默认否 | 免费；云端另计 |
| AI 生成头发丝 | 外部 API（SiliconFlow） | 是 | 免费额度内≈0 |

---

## 二、构建步骤

1. 用 **Android Studio（Arctic Fox 或更新）** 打开本工程根目录 `MagicBeauty`。
2. 首次构建会自动联网下载 MediaPipe 模型（`face_landmarker.task`、`pose_landmarker_lite.task`）到 `app/src/main/assets/`（见 `app/build.gradle.kts` 里的 `download_models` 逻辑）。若自动下载失败，可手动下载后放入 `app/src/main/assets/`：
   - https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task
   - https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task
3. 连接安卓手机（或模拟器，建议真机 + 相机权限）点击 Run。

> 本工程需要 Android SDK / Gradle，无法在普通 PC 上直接跑，请在 Android Studio 中编译安装。

---

## 三、配置密钥（只有「画发丝」需要；视频瘦身无需密钥）

全程不用电脑、不用改代码，在手机 App 底部「设置」页直接填即可（密钥只存在本机，不上传）。

1. **画发丝（AI 生成发丝）**：注册 https://cloud.siliconflow.cn（手机号即可，有免费额度），拿到 `sk-` 开头的 Key，在 App「设置」页粘贴。
2. **视频瘦身**：默认走「端侧本地处理」，无需任何密钥、不上传、免费。若想要更快/更精修，再到「设置」页填一个云端瘦身终点（你的云函数/厂商地址）。
3. 旧的 `secrets.properties.template` 已不再使用（改为运行时在 App 内填写），可忽略。

---

## 四、目录结构

```
MagicBeauty/
├── app/build.gradle.kts          # 依赖：MediaPipe / CameraX / Retrofit / OkHttp
├── secrets.properties.template   # 密钥模板（复制为 secrets.properties）
└── app/src/main/java/com/example/magicbeauty/
    ├── MainActivity.kt           # 底部导航，三个页面
    ├── engine/
    │   ├── Shaders.kt            # GLSL 着色器（形变段 + 调色段）
    │   ├── LocalEditEngine.kt    # 离屏 EGL 渲染管线，输出 Bitmap
    │   ├── MediaPipeHelper.kt    # 人脸468点 + 人体33点 → 形变标量
    │   └── EditParams.kt         # 可调参数与 WarpGuide
    ├── api/
    │   ├── ApiConfig.kt          # 密钥与端点配置
    │   ├── TencentCloudBeautyApi.kt  # 腾讯云人脸美颜（TC3 签名）
    │   ├── SiliconFlowImageApi.kt    # 画发丝（图像大模型）
    │   ├── VideoSlimApi.kt       # 视频瘦身（可配置端点）
    │   └── BeautyService.kt      # UI 统一调用入口
    ├── ui/                       # PhotoEditor / VideoSlim / Hair 三个 Fragment
    └── util/SaveUtils.kt         # 存图/存视频到相册
```

---

## 五、进阶：接入 GPUPixel 做生产级美颜（可选）

本地磨皮/瘦脸目前是自写着色器（够用、可控、零依赖）。若要更自然的商业级美颜，可接入开源库 **GPUPixel**（含 mars-face-kit 人脸检测）：

1. 到 https://github.com/pixpark/gpupixel/releases 下载 `gpupixel-release.aar`。
2. 放入 `app/libs/`，取消 `app/build.gradle.kts` 中这行的注释：
   ```kotlin
   // implementation(files("libs/gpupixel-release.aar"))
   ```
3. 在 `Application.onCreate()` 调用 `GPUPixel.Init(context)`，即可使用 `BeautyFaceFilter`（setBeautyWhite / setFaceSlim / setEyeEnlarge 等）。

---

## 六、已知边界（诚实说明）

- 本地身材塑形（照片 + 视频）是「区域形变」级别，做不到 PrettyUp 那种背景/肌肉智能保护；要达到那种效果需接商业 SDK（相芯/火山）或服务端精修。
- **视频瘦身端侧版为预览级**：手机逐帧跑 MediaPipe + GPU 渲染，较慢（建议 10 秒内短视频），且只有瘦腰/拉腿等基础塑形；要快/精修可在「设置」填云端终点。
- 画发丝是生成式结果，可能与原图发色/脸型有差异，建议多试提示词；无网或无 Key 时可切「端侧染发」离线托底。

---

## 七、GitHub Actions 自动打包（无需本地安卓环境）

本仓库已内置工作流 `.github/workflows/build.yml`：**推送代码后 GitHub 自动编译并产出 Debug APK**，你本地不用装 Android Studio。

1. 把代码推到 GitHub（或网页端导入）。
2. 进入仓库 → 点 **Actions** → 选「构建 APK」工作流 → 等它跑完（首次约 10–20 分钟，要联网拉依赖与 Android SDK）。
3. 完成后在 **Artifacts** 里下载 `magicbeauty-apk`，里面是 `app-debug.apk`，传到手机安装即可。
4. 也可以进 Actions 页面点 **Run workflow** 手动触发。

> 出的是 **Debug 包**（免签名，个人用足够）。要正式上架需自己配签名并改用 `assembleRelease`。
> 若想改公开/私有，在仓库 Settings → Change visibility 调整；模型文件（`.task`）已随仓库提交，CI 不再依赖运行时下载。

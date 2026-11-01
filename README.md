# 录屏助手 · Screen Recorder

Android 屏幕录制应用：可以录制**整个屏幕**，也可以在 Android 14+ 上录制**单个应用**；录制参数（帧率、分辨率、码率、编码格式、声音、保存位置等）全部可配置。

技术栈：Kotlin · Jetpack Compose (Material 3) · MediaProjection · MediaCodec · MediaMuxer · Gradle（Kotlin DSL）。

---

## 功能

**录制**
- 整屏录制 / 单个应用录制（Android 14+ 系统级单应用捕获）
- H.264 / H.265 硬件编码，MediaCodec 表面输入
- 帧率 15 / 24 / 30 / 60 fps（**固定帧率**，成片帧率与设置一致，动画/游戏场景不会超过设定值）
- 分辨率 原始 / 1080p / 720p / 480p（自动对齐到 16 像素）
- 画质预设 + 自定义码率（1–50 Mbps，可自动计算）
- 声音：不录 / 麦克风 / 内部音频（AudioPlaybackCapture）
- 音频码率 64–320 Kbps

**录制控制**
- 前台服务 + 通知栏「暂停 / 继续 / 停止」，退出应用后继续录制
- 录制前倒计时 0–10 秒
- 最长录制时长限制（0–60 分钟，到点自动停止）
- 可选的「离开目标应用后自动停止」（需要「使用情况访问」权限）
- 录制中旋转屏幕时，画面按原始方向等比缩放并保留黑边，视频分辨率保持不变

**文件管理**
- 保存位置：相册 `Movies/ScreenRecorder`、SAF 自定义文件夹、应用专属目录
- 文件名前缀可配置，默认 `ScreenRecorder_20260101_120000.mp4`
- 应用内列表：播放 / 分享 / 删除

---

## 环境要求

| 项目 | 要求 |
| --- | --- |
| 最低系统 | Android 10（API 29） |
| 单应用录制 | Android 14（API 34）及以上 |
| JDK | 17 及以上（本项目在 JDK 21 上验证） |
| Android SDK | Platform 35、Build-Tools 35.0.1 |
| Gradle | 8.11.1（仓库自带 wrapper） |
| AGP / Kotlin | 8.7.3 / 2.0.21 |

## 构建

```bash
# Windows
gradlew.bat :app:assembleDebug

# macOS / Linux
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> **网络提示**：`gradle/wrapper/gradle-wrapper.properties` 中的 `distributionUrl` 当前指向腾讯云镜像
> （`https://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip`），因为构建环境无法直连
> `services.gradle.org`。如果你的网络可以访问官方地址，改回
> `https\://services.gradle.org/distributions/gradle-8.11.1-bin.zip` 即可。
>
> `local.properties` 里的 `sdk.dir` 需要指向本机 Android SDK 路径（该文件不提交到版本库）。

---

## 使用

1. 打开应用，首次录制时按提示授予「通知」「麦克风」权限。
2. 在「设置」中选择录制范围、分辨率、帧率、码率、声音来源与保存位置。
3. 回到首页点击中间的录制按钮；系统会弹出屏幕捕获授权（Android 14+ 可在弹窗中选择「单个应用」）。
4. 录制过程中可以在应用内或通知栏暂停 / 继续 / 停止；停止后文件会出现在「录制文件」页。

### 单应用录制说明

Android 的系统限制决定了「录制哪个应用」只能由**系统弹窗**选择，第三方应用无法强制指定。
因此本应用的做法是：

- 在设置里选择「单个应用」，此时申请屏幕捕获时会使用 `MediaProjectionConfig.createConfigForUserChoice()`，
  系统弹窗中会出现「单个应用」选项，由用户选择被录制的应用；
- 设置里的「目标应用」只用于界面提示和「离开目标应用后自动停止」判断，不会改变系统行为。

---

## 权限说明

| 权限 | 用途 |
| --- | --- |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PROJECTION` | 录制期间常驻前台服务 |
| `POST_NOTIFICATIONS` | 通知栏控制按钮（Android 13+） |
| `RECORD_AUDIO` | 麦克风或内部音频录制 |
| `WAKE_LOCK` | 长时间录制时保持 CPU 唤醒 |
| `PACKAGE_USAGE_STATS` | 可选的「离开目标应用后自动停止」，需用户在系统设置中手动授权 |
| `<queries>` (LAUNCHER) | 列出可录制的应用 |

屏幕捕获不需要在清单中声明权限，但每次录制都需要用户通过系统弹窗授权（授权仅在本次录制有效）。

---

## 架构

```
app/src/main/java/com/screenrecorder/app/
├── MainActivity.kt                 Compose 入口
├── ScreenRecorderApp.kt
├── core/
│   ├── RecorderSettings.kt         所有可配置项 + 分辨率/码率计算
│   ├── SettingsRepository.kt       SharedPreferences + StateFlow
│   └── RecorderState.kt            录制状态、控制器、服务命令
├── capture/
│   ├── ScreenRecorderService.kt    前台服务、通知栏、MediaProjection 生命周期
│   ├── RecordingEngine.kt          管线编排、暂停、旋转、时长/前台应用限制
│   ├── GlFramePump.kt              GL 重采样：固定帧率（CFR）、丢帧/补帧
│   ├── VideoEncoder.kt             MediaCodec 表面输入 + 输出线程
│   ├── AudioCapture.kt             AudioRecord → AAC
│   ├── Mp4Muxer.kt                 多轨就绪后启动、起步样本缓存、单调时间戳保护
│   └── TimelineClock.kt            统一音视频时间轴与暂停补偿
├── storage/
│   ├── RecordingStorage.kt         MediaStore / SAF / 私有目录输出
│   └── RecordingsRepository.kt     录制文件列表、播放、分享、删除
├── apps/
│   ├── InstalledApps.kt            已安装应用列表
│   └── ForegroundAppMonitor.kt     前台应用检测（UsageStats）
├── ui/                             Compose 界面（首页/设置/录制文件/应用选择）
└── util/                           格式化、显示尺寸
```

### 关键实现细节

- **前台服务优先**：Android 14 起必须先 `startForeground(type = mediaProjection)` 再调用
  `getMediaProjection()`，否则抛 `SecurityException`；`MediaProjection.Callback` 也必须在
  `createVirtualDisplay()` 之前注册。
- **时间轴**：`VirtualDisplay` 输出的帧时间戳是 `System.nanoTime()` 绝对值，音频时间戳由
  `AudioRecord` 采样数推算，二者共用一个 `TimelineClock`；暂停时把时间原点整体后移，避免音画不同步。
- **封装器**：`MediaMuxer` 只能在 `start()` 之前 `addTrack()`，而音视频轨的格式是异步到达的，
  因此 `Mp4Muxer` 会缓存起步样本，等所有轨道就绪（或 1.5s 超时降级为单轨）后统一写入。
- **帧率（CFR）**：屏幕镜像按刷新率（60/90/120 Hz）出帧，且画面静止时不出帧，直接送编码器会得到
  帧率忽高忽低的 VFR 文件。`GlFramePump` 用 GL 在虚拟显示与编码器之间按 1/fps 的固定节奏取最新
  一帧重绘，因此成片帧率恒等于设置值（静止画面重复上一帧）；手机解码/相册里显示的帧率不再跳动。
  如果设备编码性能不足以撑住设定帧率，实际帧率会随之下降，但不会超过设置值。
- **旋转**：Android 14 起一个 `MediaProjection` 只允许创建一个 `VirtualDisplay`，编码器也无法
  中途改分辨率，因此整个录制使用固定帧尺寸；旋转时系统镜像会等比缩放到该尺寸（上下/左右留黑边）。

---

## 已验证（1.0.0）

在 Android 15（API 35，x86_64）模拟器上做过端到端验证：

| 场景 | 结果 |
| --- | --- |
| 整屏录制 | 1072×2400 / H.264 → MP4 写入相册，MediaStore 元数据（时长、分辨率、大小）正确 |
| 帧率 30（480p） | 482 帧 / 16.29 s = **29.6 fps**，与设置一致 |
| 帧率 60（480p） | 573 帧 / 10.04 s = **57 fps**（模拟器 CPU 编码瓶颈略低，真机硬件编码可跑满） |
| 麦克风声音 | AAC 44.1kHz 单声道，与视频轨时长差 < 0.1s |
| 暂停 / 继续 | 实际 13.36s、暂停 3.5s → 成片 9.87s，音画仍同步 |
| 录制中旋转 | 录制不中断，画面等比缩放并保留黑边（分辨率不变） |
| 单个应用 | 系统应用选择器选中「时钟」后，画面只包含该应用 |
| 通知栏 | 录制 / 暂停状态与「暂停/继续/停止」按钮刷新正常 |

## 已知限制

- 单应用录制依赖系统 UI（Android 14+），第三方应用无法指定被录应用；「目标应用」仅用于提示与自动停止。
- 内部音频只对允许被捕获的应用生效（DRM 内容、部分应用会静音），系统还会屏蔽通知音等。
- 录制中旋转屏幕不会改变视频分辨率，只会等比缩放并留黑边；需要竖屏/横屏视频请在开始前确定方向。
- 固定帧率意味着静止画面也会按设定帧率重复编码（VBR 下每帧只有几百字节），文件会比纯 VFR 略大。
- 性能不足的老设备可能跑不满 60fps，此时实际帧率会低于设置值。
- 尚未实现：悬浮窗控制、触摸轨迹、摄像头画面合成、剪辑、录制后合并/裁剪。

## 版本

`1.0.0` — 首个可用版本，覆盖需求文档 `需求/version-1.0.0.MD`：
整屏/单应用录制、Gradle 构建、帧率/画质/保存位置等常用配置。

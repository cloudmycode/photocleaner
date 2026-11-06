# 月度照片与视频整理

> 交付入口更新（2026-11-01）：本文为历史验证记录，所列阶段性 APK 已移除。最新完整版本为 `output/android-latest/Photo-Cleaner-latest.apk`，1.0.2 / 3，已覆盖安装至真机。

实现日期：2026-11-01。按飞书确认方案实现，不发布应用商店版本。

## 行为

- 月度首页默认按拍摄时间混排照片和视频，支持 All / Photos / Videos 筛选。筛选后的月份统计与整理进度独立计算，决定仍按完整 URI 共用，切换筛选不会丢失已有决定。
- 视频卡片展示封面、时长与播放入口；保留原有上下滑动整理方向。播放在独立窗口中进行，拖动进度条不会触发整理手势。
- 播放窗口提供播放/暂停、拖动进度、静音、关闭、保留与标记待删除。标记后回到整理流程，可撤销。应用离开前台即暂停，关闭时释放播放器，同次应用会话保留播放位置。
- 待删除页支持视频预览，确认页汇总照片、视频和文件大小。复用原有系统删除授权、身份核验、进度、结果与音效流程；不引入直接删除视频的旁路。
- 分别读取照片和视频权限范围；只授权其中一种时继续使用已获授权部分。完整媒体 URI 区分图片与视频 ID。视频不进入截图、重复照片与连拍分析。
- 现有照片数据库和内容签名不变。视频封面通过 Coil 2.7.0 的视频扩展按请求尺寸解码，播放器使用 Android VideoView，仅在预览窗口中创建。

## 边界

视频能否解码取决于设备支持的格式；失败时提供重试，也可自行标记或保留。没有视频压缩、剪辑、重复视频检测或云端处理。大型相册、厂商私有编码、真实设备的部分授权仍需进一步覆盖。

## 验证命令

使用 JDK 17：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  -PisolatedDeviceTest=true \
  '-Pandroid.testInstrumentationRunnerArguments.class=com.maxdevlab.cleaner.photo.VideoReviewTest,com.maxdevlab.cleaner.photo.SmartCleanupTest,com.maxdevlab.cleaner.photo.MonthlyReviewTest#reviewUndoPersistCancelAndDelete'
```

设备测试只使用 `.devicetest` 隔离包和测试媒体 URI 白名单。视频为本地生成的测试图案，包含明确拍摄日期，不读取或删除个人媒体。

## 本轮结果

76 项单元测试全部通过；Lint 和普通 Debug 构建通过。Android 16 / API 36 隔离模拟器 4 项设备测试全部通过：视频播放控制、筛选、标记撤销、混合删除取消/确认；重复与连拍清理；截图整理与重建；原月度整理删除流程。首轮视频测试因测试素材缺少拍摄日期而分到另一月份，补齐素材 creation_time 后通过。

[播放器截图](previews/video-player.png)。测试素材为生成的彩色动态图案。

普通包：`output/android-video-review/photo-cleaner-debug.apk`，包名 `com.maxdevlab.cleaner.photo`，版本 1.0.1 / 2。SHA-256：`56d0316f12475df49c2f8a8a2a2b8c83b9a2132524b0a72be213be6e96c570d3`。

本轮未在实体手机运行新增视频功能；未发布商店版本。后台暂停、任意格式和部分授权需要继续在目标实体机上验证，当前自动化主要覆盖上述实际执行的路径。

## 播放进度条调整

参考本项目 iOS `PhotoPreview.swift` 的 `photosLayout`：白色 3dp 细轨道、12dp 圆形滑块（拖动时 16dp）、两端等宽时间数字；保留 48dp 触控高度与原生 Slider 的拖动及无障碍语义。新版截图：`previews/video-player-ios-style.png`。

调整后视频专项模拟器测试再次通过，普通 Debug 构建通过。新版包：`output/android-video-review/photo-cleaner-ios-style-debug.apk`。


## Player icon controls (2026-11-01)
Replaced play/pause, close and mute controls with white icons and retained accessible action descriptions and 48dp touch targets. VideoReviewTest updated to find accessible controls. Isolated emulator test passed; normal debug APK built and package verified. Screenshot: previews/video-player-icons.png. Artifact: output/android-video-review/photo-cleaner-icons-debug.apk.

Player layout update: play/pause replaces elapsed time at the left of the slider; mute is top-right; separate controls row removed. Normal debug build passed, isolated VideoReviewTest passed (37.174s). Screenshot: previews/video-player-layout.png. APK: output/android-video-review/photo-cleaner-player-layout-debug.apk.

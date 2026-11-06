# 重复照片、连拍与截图清理

> 交付入口更新（2026-11-01）：本文为历史验证记录，所列阶段性 APK 已移除。最新完整版本为 `output/android-latest/Photo-Cleaner-latest.apk`，1.0.2 / 3，已覆盖安装至真机。

设计参考：`design/android-smart-cleaner/`。实现日期：2026-11-01。

## 行为与复用

底部导航为 Months / Clean up / Screenshots / Remove。Clean up 包含 Duplicate photos 与 Burst photos。分组默认选中非推荐照片，支持全局选择多余照片、组内全选、逐张勾选、横向翻页大图预览及预览内修改选择。选择推荐照片仍可删除，但确认弹窗额外明确提示。删除后保留仅剩一张的已处理分组。用户调整的选择在 Activity 重建后保留于 ViewModel，不把大批 URI 写入系统大小受限的状态 Bundle；进程重启后重新载入推荐选择并重新确认。

截图通过 MediaStore 相册目录和文件名前缀识别。支持全部截图或指定月份整理，直接复用 ReviewScreen、ReviewSession、决定持久化、撤销、缩放、分享、待删除复核与进度恢复。月度页与截图页针对同一张照片共用整理决定，避免重复处理。

所有删除使用同一个 `PhotoDeleteCoordinator`，不添加独立删除通道。系统授权、2,000 张单次上限、逐照片身份核验、取消/部分成功/失败回执、数据库一致性、删除进度、完成动画、完成音效与清理后广告沿用现有实现。分组删除不要求先把照片标记到月度待删除列表。打开确认弹窗时固定候选集，扫描结果不会扩大这次选择；提交删除时再核对当前内容身份。分组扫描期间暂不允许提交删除。

## 本地识别边界

- 重复照片：参考 iOS 的 32×32 灰度量化指纹与宽高比匹配。极低纹理画面改用原文件 SHA-256，避免纯色图被粗略指纹误归为重复。推荐保留收藏照片，否则保留最早拍摄的一张。
- 连拍：使用真实 DATE_TAKEN，相邻间隔不超过 2.5 秒、整组不超过 20 秒，并核对尺寸、纹理和画面差异指纹；同时比较上一张与首张，避免逐渐漂移成无关照片。截图与仅有导入时间的图片不参与。推荐优先收藏，其次像素面积和文件大小。
- Android 没有跨品牌统一的 iOS burstIdentifier，因此这一版以时序和画面匹配推断连拍，不保证覆盖所有厂商封装的连拍格式。识别和推荐是辅助判断，用户可以预览和修改。
- 单张顺序解码，长边采样到约 256 像素；缓存仅保存内容身份与指纹，保存在应用私有 SQLite 数据库，不上传照片。读取失败的照片跳过并显示数量。部分授权只分析当前可访问照片。
- 首次进入智能清理时扫描，显示进度；重进复用结果，媒体或权限变化使旧结果失效并按内容身份重核。扫描结果不会覆盖更新的相册快照。

## 验证

74 项单元测试全部通过；Lint 无错误；Android 16 / API 36 模拟器 4 项设备集成测试全部通过：分组扫描/选择/预览/重建/内容身份核验/取消和确认删除、截图整理/撤销/重建、现有月度完整删除回归、现有完成动画时序与恢复。新增识别规则包含 8 项单元测试。原月度测试的 Done 返回目标断言已按既有实现从待删除页修正为首页，再通过 Remove 标签复核剩余内容。设备验证使用 `.devicetest` 隔离包、PhotoCleaner_API_36 模拟器和测试 URI 白名单，不读取或删除个人照片。

复测命令（JDK 17）：

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  -PisolatedDeviceTest=true \
  '-Pandroid.testInstrumentationRunnerArguments.class=com.maxdevlab.cleaner.photo.SmartCleanupTest,com.maxdevlab.cleaner.photo.MonthlyReviewTest#reviewUndoPersistCancelAndDelete,com.maxdevlab.cleaner.photo.CleanupResultAnimationTest'
```

[智能清理首页](previews/smart-home.png) · [重复照片](previews/duplicates.png) · [连拍](previews/bursts.png) · [截图页](previews/screenshots.png) · [复用删除结果](previews/deletion-result.png)

本轮没有在实体手机运行新增功能的删除测试。不同品牌的连拍封装与首次大型相册扫描耗时仍需实际设备覆盖；本轮不发布应用商店版本。

普通 Debug 构建通过，包名 `com.maxdevlab.cleaner.photo`（非隔离测试包），产物：`output/android-smart-cleaner/photo-cleaner-debug.apk`。SHA-256：`251602592282783ed1235b81def16a72f7f7c3fda0b283336cc23c1f87a043d4`。

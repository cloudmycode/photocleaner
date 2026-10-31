# minSdk 27 兼容版验证记录

> 后续滑动性能优化、最新构建和安装状态见 [性能验证](swipe-performance/VALIDATION.md)。

> 最新网页对齐实现及验证见 [本轮记录](web-parity/VALIDATION.md)。以下为历史记录。

最新界面改版验证见 [2026-10-31 手机界面验证](ui-refresh/VALIDATION.md)。下文为此前兼容改造的历史记录，产物哈希不是本次新版 APK。

日期：2026-10-30（设备报告使用 UTC 时间）。当前版本最低支持 Android 8.1，compileSdk / targetSdk 保持 36。以下是实际执行结果，不代表 Google Play 已审核通过。

## 本次实现范围

- `platform/PhotoPlatform.kt` 集中权限和系统能力判断。
- `platform/MediaStorePhotoSource.kt` 统一读取与映射，按系统能力选择媒体列和 URI。
- `platform/AndroidDeletionBackend.kt` 分别处理 Android 8.1–9 直接删除、Android 10 按需逐张授权、Android 11+ 批量系统确认。
- `data/PhotoDeletion.kt` 共用删除队列、授权结果处理、部分成功和中断恢复逻辑；`platform/DeletionJournal.kt` 持久化逐张结果。
- 月份分组、滑动、撤销、收藏、整理记录和界面继续复用。只将实际确认成功的删除计入结果；取消后不回滚此前已完成的删除。

## 真机：华为 BLA-AL00

Android 8.1.0 / API 27 / arm64-v8a。两条完整交互测试 **通过，0 失败、0 错误**，测试套件约 17 秒。

1. 收藏、大图预览、收藏持久化、待删改为保留、重置整理记录，验证三张测试图片始终存在。
2. 左右滑动、撤销、Activity 重建后的进度恢复、待删复核、取消 App 确认、批准永久删除、完成页、设置评价入口与隐私页。取消后为三张图片，批准删除一张后为两张。

运行的是独立的 `com.cloudmycode.photocleaner.devicetest` 应用。查询和删除均受测试 URI 白名单限制，只创建和清理随机命名的测试图片，不使用用户照片，不重置普通 App 的记录。测试结束已清理测试图片。权限由测试规则预先授予；运行时授权批准/拒绝的队列处理由单元测试覆盖，未宣称已验证华为所有权限弹窗路径。

- [原始 JUnit 报告](test-results/api27/TEST-BLA-AL00%20-%208.1.0-_app-.xml)
- [月份首页](screenshots-api27/01-months.png)、[滑动整理](screenshots-api27/02-review.png)、[待删复核](screenshots-api27/03-to-delete.png)
- [永久删除确认](screenshots-api27/04-app-confirmation.png)、[完成页](screenshots-api27/05-complete.png)、[设置](screenshots-api27/06-settings.png)

测试中发现 Android 8.1 的 SQLiteOpenHelper 尚不实现 AutoCloseable，已将测试资源释放改为显式 try/finally close，随后完整真机套件通过。

最终普通 Debug APK 已覆盖安装到同一台手机，未清除普通应用的数据；启动返回 `Status: ok`，本次 `TotalTime: 493 ms`（单次调试启动记录，不作为性能基准）。归档后已卸载独立测试应用及测试运行器，手机保留可正常体验的 Photo Cleaner。

## 当前自动化与构建结果

- 单元测试 **23 项通过**：9 项整理规则、14 项兼容和删除流程测试。包括旧版写权限拒绝/批准/重新核对、Android 10 授权后实际重试、部分成功后取消、重复授权保护、过期照片排除、批量确认结果、过期回调和中断收据恢复。
- [业务规则原始报告](test-results/unit/TEST-com.cloudmycode.photocleaner.ReviewRulesTest.xml)、[兼容流程原始报告](test-results/unit/TEST-com.cloudmycode.photocleaner.DeletionCompatibilityTest.xml)。

| 检查 | 结果 |
| --- | --- |
| 普通 Debug APK 构建 | 成功，包名 `com.cloudmycode.photocleaner`，minSdk 27 / targetSdk 36，测试模式关闭 |
| Release AAB 构建 | 成功，R8 缩减，未签名；测试模式关闭 |
| 单元测试 | 23 项，0 失败、0 错误 |
| Debug lint | 0 errors、25 warnings |
| APK ZIP / 原生 ELF 16 KB 对齐 | `zipalign -c -P 16 4` 通过，四种 ABI 的原生库 LOAD 段均为 16384 对齐 |
| Release 合并 Manifest | 旧版 WRITE_EXTERNAL_STORAGE 仅到 API 28；无 INTERNET、视频、位置、所有文件管理或前台服务权限 |

Lint 警告包括能力属性保护下的权限常量 InlinedApi 提示、依赖版本更新、KTX 写法建议和未使用字符串；没有 API 调用错误。版本能力属性由边界单元测试覆盖，未整体关闭兼容检查。

## Android 16 回归

PhotoCleaner_API_36 / Pixel 7，API 36、x86_64、4 KB 内存页。两条完整测试一次运行通过，0 失败、0 错误，套件约 62 秒。覆盖相同公共流程，并实际验证系统批量删除弹窗的取消和批准：取消仍为三张，批准后为两张。最终运行默认清理测试图片。

初次回归发现测试图片缺少 EXIF 时区，MediaStore 返回空拍摄时间，应用正确回退到加入相册的月份。已在测试图片中补齐 UTC offset，并新增 fixture 月份校验，随后两条测试全部通过。没有为通过测试更改生产月份分组逻辑。[Android DATE_TAKEN 官方定义](https://android.googlesource.com/platform/packages/providers/MediaProvider/+/ae8cbaf720b897031ae2c66adc002df27b59f48f/apex/framework/java/android/provider/MediaStore.java)

- [原始 JUnit 报告](test-results/api36/TEST-PhotoCleaner_API_36(AVD)%20-%2016-_app-.xml)
- [月份](screenshots-api36/01-months.png)、[滑动](screenshots-api36/02-review.png)、[系统删除确认](screenshots-api36/04-system-confirmation.png)、[完成](screenshots-api36/05-complete.png)、[设置](screenshots-api36/06-settings.png)

## 最终产物

- `app/build/outputs/apk/debug/app-debug.apk`：普通可安装调试版，约 18.1 MiB。
- `app/build/outputs/bundle/release/app-release.aab`：约 3.4 MiB，未签名，发布者信息尚需填写。

SHA-256：

```text
APK bdf7ca7b55fefd24b23e9382e7ee4f456f362bfc1a8a06fce98e2f1a63060e80
AAB 6f1bf4ce10ca1a12f76621322e8817093f71a96df9d4ddf56addf55b154805fd
```

Gradle 8.13 / AGP 8.12.0 / Kotlin 2.1.20 / JDK 17。详细构建报告位于 `app/build/reports/`；JUnit 报告和实际截图已复制到本目录，清理 build 不会删除归档。

## 复现

普通应用构建（JDK 17，SDK 36）：

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:bundleRelease
```

真机测试（先指定目标设备并解锁）：

```sh
ANDROID_SERIAL=YOUR_DEVICE_SERIAL ./gradlew :app:connectedDebugAndroidTest \
  -PisolatedDeviceTest=true \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.allowPhysicalDevice=true
```

模拟器同样使用 `isolatedDeviceTest=true`，不需要 `allowPhysicalDevice`。测试版默认清理专用图片；普通 Debug 构建不启用白名单；Release 始终不启用测试模式。

## 尚未覆盖

- Android 9、Android 10 真机；尤其是 Android 10 对其他应用创建照片的逐张系统授权 UI。该分支的批准、重试、取消和部分成功已用替身后端测试，不能替代设备验证。
- Android 14+ 部分照片权限的完整设备回归，TalkBack、大字体、横屏、超大相册、SD 卡和特殊图片格式。
- 删除期间进程被杀的完整设备回归；当前验证的是 Activity 重建及单元测试中的中断收据恢复。
- 每月提醒在重启、厂商省电策略下的长期表现；商店评价真实详情页（应用未上架）。
- 发布签名、发布者资料、公开隐私政策、Play Console 声明、广泛照片权限审批及 Play 预发布测试。

此前 minSdk 30 初版的 Android 17 / 16 KB 模拟器测试见 [历史记录](VALIDATION_INITIAL.md)，不作为本轮兼容改造在 Android 17 上的回归结论。正式发布前继续完成 [发布准备清单](PLAY_RELEASE_CHECKLIST.md)。

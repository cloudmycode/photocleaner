> 历史记录：以下内容对应 minSdk 30 的初版，不是当前兼容版的验证结论。当前结果见 [验证记录](VALIDATION.md)。

# 验证记录

日期：2026-10-30。以下是本次实际执行结果，不代表 Google Play 已审核通过。

## 环境

- macOS x86_64，OpenJDK 17.0.16。
- Gradle 8.13 / Android Gradle Plugin 8.12.0 / Kotlin 2.1.20。
- compileSdk / targetSdk 36，minSdk 30。
- Pixel_10_Pro 模拟器，系统 SDK 37，x86_64，`getconf PAGE_SIZE` 返回 **16384**。这是本机现有的 Android 17 模拟器镜像；不能代替 Android 11–16 的兼容覆盖。

## 已通过

| 检查 | 结果 |
| --- | --- |
| `:app:assembleDebug` | 成功，生成可安装的调试 APK |
| `:app:bundleRelease` | 成功，R8 混淆/缩减，生成未签名 AAB，约 3.3 MB |
| `:app:testDebugUnitTest` | 9 项，0 失败、0 错误 |
| `:app:connectedDebugAndroidTest` | 1 项端到端测试，0 失败、0 错误，约 23 秒 |
| `:app:lintDebug` | 0 errors，16 warnings；主要为依赖升级提示、KTX 风格建议和持久化 commit 建议 |
| Release lint vital | 构建通过 |
| APK 16 KB ZIP 对齐 | `zipalign -c -P 16 4` 通过 |
| 原生库 ELF 段对齐 | 四种 ABI 的 `libandroidx.graphics.path.so` LOAD 段均为 16384 对齐 |
| 合并 Manifest 检查 | 无 INTERNET、视频访问、所有文件管理、位置、写存储或前台服务权限 |
| 发布配置检查 | 未填写实际公开政策网址时，`verifyReleaseMetadata` 按预期拒绝通过 |

显式 `commit()` 用于在打开系统删除界面前持久化请求，已放在 IO 线程执行；它是有意保留的同步持久化边界，不阻塞界面线程。依赖版本提示不等于运行或政策错误，但正式维护时仍应评估更新并回归验证。

业务规则测试覆盖：修改后的照片不继承旧删除决定；删除候选必须可访问且被选中且已标记；URI 去重；2,000 张上限；单独收藏不计为已整理；跨年排序及进度；设备时区的月份边界；月度提醒截止时间、跨年与夏令时。

设备端端到端测试使用三张生成的测试图片，实际执行：

1. MediaStore 读取及月份显示。
2. 移除按钮、撤销、左滑标记、保留按钮。
3. Activity 重建后恢复整理进度。
4. 进入待删除列表，取消应用确认，验证三张图片仍存在。
5. 调用 Android 系统删除确认并取消，验证三张图片仍存在。
6. 再次确认并批准系统删除，验证只剩两张图片。
7. 完成页、设置中的评价入口、隐私说明页面。

测试只操作本 App 创建的指定测试文件；测试结束清理剩余测试图片。没有使用用户照片，也没有实际提交商店评价。

## 原生截图

截图来自上面的实际模拟器测试，图片是程序生成的测试图形，不是正式应用内置素材。

- [月份首页](screenshots/01-months.png)
- [滑动整理](screenshots/02-review.png)
- [待删除复核](screenshots/03-to-delete.png)
- [Android 系统删除确认](screenshots/04-system-confirmation.png)
- [删除完成](screenshots/05-complete.png)
- [设置与评价入口](screenshots/06-settings.png)

## 产物

- `app/build/outputs/apk/debug/app-debug.apk`（约 59 MB，包含调试和预览依赖，调试签名）。
- `app/build/outputs/bundle/release/app-release.aab`（约 3.3 MB，**未签名，发布者信息未填写**）。

SHA-256：

```text
APK  f0a5f8be79b3e9f77e12daa6464a8721f6d74b6029abac40815088bba1b09204
AAB  0257a2384dec04479a0d36980467f7183674df571bdd45c660ccd2d67636ddc0
```

报告路径：`app/build/reports/tests/testDebugUnitTest/`、`app/build/reports/androidTests/connected/debug/`、`app/build/reports/lint-results-debug.html`。清理构建目录会删除这些生成的报告和安装包，可重新执行对应命令生成。

## 尚未验证 / 尚未完成

- 真实手机和其他 Android 版本、部分照片授权的完整设备回归、TalkBack/大字体/横屏、超大照片库、SD 卡、特殊图片格式。
- 系统删除期间进程被杀后的完整设备回归；代码保留了待处理请求与保守恢复逻辑。
- 月度提醒在真实时间、关机重启、厂商省电策略下的长期行为。
- 商店评价真实详情页跳转（应用未上架）、Play 安装和预发布报告。
- ARM64 真机运行及最终由 Play 生成的 APK 验证；当前是 x86_64 16 KB 模拟器与 ELF 静态检查。
- 最终发布者名称、支持邮箱、公开隐私政策托管、发布签名、Play Console 各项声明、广泛照片权限审批、正式发布。

这些项目列入 [发布准备清单](PLAY_RELEASE_CHECKLIST.md)。当前交付定位是可编译、已验证主要流程的原生首版，不宣称已经完成正式商店发布。

## Android 16 补充模拟器测试

2026-10-30 按用户要求，在新建的 **PhotoCleaner_API_36 / Pixel 7** 模拟器复测。系统 Android 16 / API 36，x86_64，4 KB 内存页；与前面的 Android 17 / 16 KB 测试互为补充。

两条测试流程均已验证通过，分次完成：

- `favoritePreviewKeepSelectedAndResetDoNotDeletePhotos`：收藏、放大、关闭预览、重新进入后收藏保留、将待删照片改为保留、重置进度及收藏；验证三张测试图片始终存在。此场景在完整测试运行中通过。
- `reviewUndoPersistCancelAndDeleteWithSystemApproval`：实际左滑和右滑、撤销、Activity 重建后恢复、应用删除确认取消、Android 系统删除取消与批准、设置评价入口、隐私页。取消后图片数为 3，批准删除一张后为 2；单独复测通过。

测试过程中完善了图片加载等待、按钮语义定位，以及通过无障碍 dismiss 操作关闭取消提示，避免瞬时提示覆盖下一次点击。没有绕过 Android 的删除确认。最后一次删除流程执行结果为 `BUILD SUCCESSFUL`，崩溃日志为空。

最后一次单独复测使用：

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.keepTestPhotos=true \
  '-Pandroid.testInstrumentationRunnerArguments.class=com.cloudmycode.photocleaner.MonthlyReviewTest#reviewUndoPersistCancelAndDeleteWithSystemApproval'
```

当前 `app/build/reports/androidTests/connected/debug/` 报告对应这次单独删除流程复测。测试代码保留两个场景，不加 class 筛选即可运行整个套件。

模拟器和 App 保持打开，保留两张生成的测试图片用于手动体验。这些图片不属于用户相册。普通测试默认清理生成图片，只有上述 `keepTestPhotos` 参数开启时保留。

手动体验启动时，`am start -W` 返回 `Status: ok`、`LaunchState: COLD`、`TotalTime: 15247 ms`。这次模拟器 Debug 冷启动偏慢，尚未区分宿主机/模拟器负载与应用初始化的影响；不能据此推断真机 Release 性能，发布前需要测量启动耗时。

Android 16 截图：[月份页](screenshots-api36/01-months.png)、[滑动整理](screenshots-api36/02-review.png)、[删除复核](screenshots-api36/03-to-delete.png)、[系统确认](screenshots-api36/04-system-confirmation.png)、[删除完成](screenshots-api36/05-complete.png)、[设置](screenshots-api36/06-settings.png)。

本轮补充了 Android 16 的完整授权流程覆盖；部分授权、其他系统版本及真机检查仍按前面的未验证清单继续执行。

## 真机连接检查

2026-10-30 检测到已授权 USB 调试的华为 BLA-AL00 真机：Android 8.1.0 / API 27 / arm64-v8a。当前工程 `minSdk = 30`，最低支持 Android 11，因此这台手机无法运行当前安装包。

本次只完成设备与版本检查，没有安装应用、执行照片读取或删除测试。后续需选择使用 Android 11+ 真机，或先实现 Android 8.1 的照片访问和删除兼容方案；不能仅降低 minSdk 就认为已完成兼容。

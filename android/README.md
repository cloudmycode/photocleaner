# Tidy It — Android

正式发布版本为 **1.0.15（构建 16）**。[Google Play 上传包](../output/releases/1.0.15/Tidy-It-1.0.15.aab)

发布前需将线上隐私政策中有关视频访问的说明同步为当前版本的实际行为。

支持月度滑动整理、重复照片、连拍和截图清理的原生 Android App，与 `../design/android-monthly-cleaner/` 和 `../design/android-smart-cleaner/` 的手机交互设计对应。面向英语用户，应用名为 **Tidy It**。这个目录是独立 Android Studio 工程，不依赖 iOS 或服务器。

## 快速清理首页

冷启动默认进入 Clean up，并在媒体读取完成后自动扫描重复与连拍照片。首页优先推荐重复照片；没有重复候选时推荐连拍，展示该类别额外照片的数量及最多可释放容量。重复与连拍可能重叠，因此容量不跨类别相加；截图只标注总大小，需要用户自行复查。

首次授权页说明“查找额外照片 → 复查 → 确认清理”。扫描中、失败、部分授权、跳过不可读文件和无候选分别显示对应状态，不将未完成扫描描述为已清理。Months 保留按月整理，Android 返回键从 Months 回到 Clean up。恢复正在进行的复查或删除会话时仍保留原位置。

验证记录见 [快速清理首页](docs/quick-cleanup-home/VALIDATION.md)。

## 打开和运行

1. 在 Android Studio 中选择 **Open**，打开本目录 `android/`。
2. 安装 Android SDK Platform 36；Gradle JDK 选择 **JDK 17**。工程固定 Gradle 8.13、Android Gradle Plugin 8.12.0、Kotlin 2.1.20，避免使用不兼容的 JDK 25。
3. 等待 Gradle Sync，然后选择 `app`，在 Android 8.1 / API 27 及以上的手机或模拟器运行。
4. 点击 **Choose photo access** 后授权。模拟器没有照片时，先添加测试图片；正式应用不会自带演示照片。

命令行（将 JAVA_HOME 设置为自己的 JDK 17，SDK 路径由 Android Studio 写入 `local.properties`）：

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

安装包：`app/build/outputs/apk/debug/app-debug.apk`。这是调试包，不用于 Google Play 发布。首次同步需要网络下载依赖；App 运行不要求网络权限。

## 已实现

- 应用默认沉浸式全屏，隐藏系统状态栏和底部导航栏；从屏幕边缘可临时唤出，返回应用后自动恢复全屏。应用底部 Months / Clean up / Screenshots / Remove 导航保留，内容避让刘海/挖孔。

- 读取真实设备照片，按拍摄月份分组；无拍摄时间时回退到加入相册的时间。
- 图片为主的月份首页：最新有照片月份置顶横向封面，醒目的 Start sorting 按钮（整理中显示 Continue sorting，已完成隐藏按钮、保留进度条，点击封面仍可浏览），其他月份双列卡片，完成状态和整理进度持久化。
- 上滑保留、下滑标记移除；按钮替代手势，按钮翻页动画 560 ms；当前整理会话可连续撤销。
- 默认完整显示照片，下一张以 85% 大小堆在下面，随拖动放大，背景和控制按钮固定。
- 已完成月份可以重新浏览并更改决定；从待删页返回同一张照片，系统返回与顶部返回行为一致。
- 双指以触点为中心缩放、平移查看细节，松手弹簧复原；双指触摸锁定划选，避免误保留/删除。细节预览按需加载并限制解码内存。
- 待删网格点击多选，与网页一致。
- 完成页使用倾斜照片、计数与再次浏览；待删页底部确认、删除后留在列表，底部导航用文字计数。
- 待删除列表、多选、改为保留、明确的永久删除确认；按系统版本执行删除，取消会停止后续操作，已完成的删除不会回滚。
- Android 14+ 部分照片授权、权限改变后刷新、拒绝授权引导。
- 月份完成页提供可选评价卡片，复用设置页的 Google Play 入口；Not now 延后 30 天，打开评价页面后不再主动提示，不推断用户是否实际评分。
- 默认关闭的每月提醒、英文隐私说明、重置整理进度、**Rate Photo Cleaner** 商店入口。
- 本地 SQLite 数据、无账号或照片上传服务；接入 AdMob 清理后插屏，含联网权限。Debug 使用官方测试广告；未配置正式 ID 的 Release 不请求或展示广告。

没有 Vault 或独立保留相册，保留照片仍在原来的月份。标记移除不是回收站。所有版本均在 App 中明确确认永久删除。Android 8.1–9 使用旧版存储写权限后执行；Android 10 按需逐张请求系统授权再重试；Android 11+ 使用系统批量删除确认。结果按实际成功、失败、取消分别统计。

## 界面与状态职责

- `ui/Theme.kt` 统一系统蓝、系统红和中性灰配色。
- `ui/MonthScreens.kt` 展示月份与整理结果；`ui/TrashScreen.kt` 展示选择和删除入口。
- `ui/ReviewScreen.kt` 只负责手势、堆叠、动画和显示，按钮与手势复用同一个提交入口。
- `data/ReviewSession.kt` 管理本次浏览队列；`PhotoViewModel` 持久化决定、处理返回目标与撤销。完成标记不再阻止重新浏览。
- `data/LibrarySnapshot.kt` 统一删除后的照片和整理记录；`ReviewDatabase` 在同一事务保存成功删除回执并移除匹配的整理记录，刷新或重启后也不会被系统旧列表覆盖。见 [一致性验证](docs/deletion-consistency/VALIDATION.md)。
- `data/DeleteProgress.kt` 汇总已持久化的处理进度与释放字节；`ui/DeletionProgressPanel.kt` 独立订阅进度，避免每张删除都重绘照片网格。见 [华为删除耗时与进度验证](docs/deletion-progress/VALIDATION.md)。
- `data/CleanupSound.kt` 定义清理成功后的结果音效触发规则；`platform/CleanupSoundPlayer.kt` 管理完成音效预加载及前台播放；删除进度过程保持安静。设置可关闭，音源由 `tools/generate_cleanup_sounds.py` 原创生成。见 [音效验证](docs/cleanup-sound/VALIDATION.md)。
- `platform/PhotoShare.kt` 在后台核对当前图片并准备系统分享面板，只授予这一张图片的临时只读权限；分享不改变整理决定。见 [分享验证](docs/photo-share/VALIDATION.md)。
- `ui/PhotoComponents.kt` 复用图片加载和授权说明；筛选页两张卡片按 URI 保持组件身份，另外仅预加载一张。
- `images/PreviewPolicy.kt` 集中筛选预览尺寸限制；`PhotoCleanerApplication` 共享受限图片缓存。
- `ui/DesignComponents.kt` 集中网页对应的图标、尺寸、文字、按钮、导航与底部弹窗，避免 Material 默认样式带来的偏差。
- 设置继续使用 `PhotoCleanerApp` 里的原生 `ModalBottomSheet`，保留原有内容和操作，只统一配色。
- `platform/` 的权限和删除适配保持集中，界面不增加 Android 版本分支。

## 实现与交付材料

- [实现方案](docs/IMPLEMENTATION_PLAN.md)：架构、数据模型、流程和产品边界。
- [Google Play 发布准备](docs/PLAY_RELEASE_CHECKLIST.md)：权限申报英文稿、Data safety 填报依据和官方政策链接。
- [英文隐私政策模板](docs/privacy-policy.html)：需要替换主体、邮箱、日期并公开托管。
- [验证记录](docs/VALIDATION.md)：实际执行的构建、自动化测试和剩余设备覆盖。

## 图片滑动性能

滑动位移、缩放、旋转和提示透明度仅在图层阶段读取，不触发页面每帧重组。筛选预览最长边 2048 像素，保留原图；图片缓存使用可用应用内存的 20%，最多 48 MiB（不等于应用总内存上限）。下一张保留同一个图片组件，进入前台不重新挂载，后台仅额外预加载一张。

[性能验证与复测方法](docs/swipe-performance/VALIDATION.md) 包含非调试构建、1200 万像素横竖图测试和数据限制。

连续划选时，手势监听固定在照片区域，不随换图或保存状态重建。新的按下事件会提前结束上一张的飞出动画，并接手已加载的下一张；最多暂存一个绑定照片 URI 和内容签名的完整划动，前一张保存成功后才按顺序处理。取消、短划、双指缩放不产生决定；保存失败或应用退到后台时清除暂存。照片尚未加载或暂存已满时给出提示，不把多次方向指令堆积到未看见的照片上。底部不再显示 Kept 文案。

实现与回归记录见 [连续划选验证](docs/rapid-swipes/VALIDATION.md)。

## 授权引导与照片详情

首次授权页先说明清理收益、复查与确认步骤，媒体授权按钮位于下方。筛选页的 `…` 展开或收起文件信息，系统返回键优先收起详情。信息包含文件名、存放目录、大小、尺寸、格式、拍摄/修改/添加日期，以及照片记录的相机和 GPS 信息（如有）。当前照片静止后在后台准备详情，只缓存当前照片；重复开关不重新读取，切换照片或权限变化时清除旧内容。浮层固定高度、按可见区域排版并淡入淡出，背景为 70% 不透明度。Android 10+ 的照片 GPS 权限只在用户主动点击时请求，不获取设备当前位置。

实现与验证见 [照片详情验证记录](docs/photo-details/VALIDATION.md)。

双指缩放的高清图在当前照片静止时提前准备，仅保留当前照片的一份受限位图；缩放及回弹期间不切换清晰度，不重复加载。照片左下角的“…”展开或收起详情，默认不单独显示日期，右下角对称显示文件大小。圆角按照片实际 Fit 边界裁剪，随照片移动和缩放，黑色显示区域只负责矩形边界裁剪。

双指缩放实现与设备测试见 [缩放验证记录](docs/pinch-zoom/VALIDATION.md)。

## 发布配置

当前 applicationId 和源码 namespace 均为 `com.maxdevlab.cleaner.photo`。新包名不会覆盖原先 `com.cloudmycode.photocleaner` 的安装，也不会自动继承旧应用的私有整理记录。商店评价入口会自动使用实际 applicationId；应用未上架时无法展示真实商店评价页。

在本机 `~/.gradle/gradle.properties`（不要提交私密配置）或 Android Studio 构建参数中配置实际值：

```properties
publisherName=YOUR ACTUAL PUBLISHER NAME
supportEmail=YOUR MONITORED EMAIL
privacyUrl=YOUR PUBLIC HTTPS PRIVACY POLICY URL
```

```sh
./gradlew :app:verifyReleaseMetadata :app:bundleRelease
```

`verifyReleaseMetadata` 检查必填信息，**不会核实网站内容或代替审核**。Debug 可以独立编译；Release 构建必须有有效的本机签名配置，缺失时会停止，不会静默生成未签名产物。

正式版使用 `../../antivirus/Max2/max2/readme/key.jks`（相对于 `android/`），别名 `securityantivirus`。密码从本机 `android/signing.properties` 读取，该文件已被 Git 忽略并限制为仅当前用户可读。证书保留在原目录，没有复制到仓库。新开发环境可复制 `signing.properties.example` 为 `signing.properties`，再在本机填写密码。Debug 和隔离测试继续使用开发签名。

```sh
./gradlew :app:assembleRelease :app:bundleRelease
```

构建会自动检查签名配置。上面的命令生成签名产物，但不代替前面的发布信息检查或 Play Console 配置。

正式版产物：`app/build/outputs/apk/release/app-release.apk` 和 `app/build/outputs/bundle/release/app-release.aab`，均使用配置的发布证书签名。

## 自动化设备测试

设备测试必须使用独立的 `.devicetest` 调试应用（桌面名称 Photo Cleaner Test），不会重置普通应用的记录。照片查询和删除都受测试 URI 白名单限制，测试只创建和清理自己的图片：

```sh
./gradlew :app:connectedDebugAndroidTest -PisolatedDeviceTest=true
```

测试覆盖真实 MediaStore 读取、上下滑动、短滑回弹、横滑不触发决定、撤销、Activity 重建后的进度恢复、完成月份重进、待删页返回、取消应用弹窗、批准删除、双指缩放与复原、批量改为保留、重置进度、设置及隐私入口。Android 11+ 还覆盖系统批量删除的取消和批准；Android 10 外部照片的逐张授权流程另需设备验证。测试图片复制自网页示例素材，仅包含在 androidTest APK 中，不随普通 App 打包，也不包含用户照片。

如需测试后留在模拟器中手动体验，可以保留安装包和生成的测试图片（普通测试默认清理图片）：

```sh
./gradlew :app:connectedDebugAndroidTest -PisolatedDeviceTest=true -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.keepTestPhotos=true
```

真机测试还需显式传入 `-Pandroid.testInstrumentationRunnerArguments.allowPhysicalDevice=true`，用 `ANDROID_SERIAL` 指定目标手机并保持解锁。使用正常构建命令重新生成普通安装包；`isolatedDeviceTest` 只影响 Debug，Release 永远不启用测试白名单。

兼容层集中在 `platform/`；删除队列和部分结果处理位于 `data/PhotoDeletion.kt`，界面和月份整理逻辑共用。

这是可编译、可运行的首版实现。没有进行 Google Play 提交；照片广泛访问权限仍需 Google 审核，发布者资料和公开隐私政策仍需实际填写。

## 实际删除后的结果

系统确认成功删除后，独立结果页用 1.5 秒动画展示累计删除张数和文件大小总和，再展示评价入口。系统批量删除不提供逐文件实时进度，因此等待系统期间不虚报空间。取消和失败不计入成功数；有部分失败时保留说明与返回待删列表的入口。结果和计数跨页面重建保留。

评价邀请仅显示在实际删除成功的 Cleanup complete 页面，月份筛选完成页不显示。移除 Not now，用户可直接点 Done 离开。点击 Rate 后 30 天不提示（与设置入口共用状态），到期在前台刷新或下一次实际删除完成时重新计算。评价按钮为主要蓝色操作，待删入口为浅灰次要操作。

### 清理后插屏

流程与配置见 [AdMob 接入与验证](docs/cleanup-ads/VALIDATION.md)。`ads/` 集中处理计数、一次性展示、预加载和隐私同意，删除模块不依赖广告 SDK。

### 正式 AdMob ID

`android/gradle.properties` 已配置用户提供的 App ID `ca-app-pub-7579918370231503~8641056629` 和插屏单元 ID `ca-app-pub-7579918370231503/6769571752`。这些是公开标识符，不是账号密码。Release 使用正式广告并遵守 UMP 同意状态；Debug 强制使用 Google 官方测试 ID，performance 隔离性能测试关闭广告。计数仍仅保存在当前应用进程内，前两次成功清理不展示，从第三次起尝试展示已加载的广告。

## 智能清理与截图

新增重复照片和连拍分组清理，以及独立 Screenshots 标签页。截图共用月度滑动整理交互；分组清理默认保留推荐照片，支持预览与批量选择。所有类别复用原有系统删除、完成动画与音效。识别规则、兼容边界和验证见 [智能清理说明](docs/smart-cleanup/VALIDATION.md)。

# Google Play 发布准备

核对日期：2026-10-31。下面区分已实现的技术措施和发布者必须完成的事项，不能把技术检查通过理解为 Google 已批准上架。

## 1. 目标版本与构建

- `compileSdk = 36` / `targetSdk = 36`，符合当前普通手机新应用的目标版本要求；提交当天再次核对。[官方 Target API 要求](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en-GB)
- minSdk 27，未强制固定屏幕方向，适配系统边到边与安全区。
- 发布使用签名 AAB；Release 已配置 `max2/readme/key.jks`，密码保存在 Git 忽略的本机文件中。applicationId 为 `com.maxdevlab.cleaner.photo`；提交前确认版本号和 Play App Signing 设置。
- 不因项目主要使用 Kotlin 就忽略 16 KB 要求：Compose 的传递依赖包含 `libandroidx.graphics.path.so`。需检查最终所有 ABI 的 ELF LOAD 段与 APK ZIP 对齐，并在 16 KB 设备验证。[官方 16 KB 指南](https://developer.android.com/guide/practices/page-sizes)

## 2. 照片权限申报是审核重点

本 App 的核心功能是持续读取照片库、按月组织、保存整理进度和批量复核删除；不是偶尔上传一张头像。广泛照片访问具有产品理由，但是否满足政策由 Google 审核决定，不能保证通过。

Manifest 仅申请照片，不申请视频。Android 14+ 支持部分照片授权，并在首页显示选择范围限制。没有 `MANAGE_EXTERNAL_STORAGE`。可选 `ACCESS_MEDIA_LOCATION` 仅读取照片中已保存的 GPS；AdMob 使用 `INTERNET`，不接收照片或照片位置信息。为兼容 Android 8.1–9，声明 `WRITE_EXTERNAL_STORAGE` 并设置 `maxSdkVersion=28`；只有真正删除时才按需请求，Android 10+ 不使用此权限删除照片。

Google Play 要求使用 `READ_MEDIA_IMAGES` 的应用提交声明，说明核心用途及系统 Photo Picker 为什么不足以完成核心功能。[照片与视频权限政策](https://support.google.com/googleplay/android-developer/answer/16558241?hl=en-GB&rd=1)

可以据实调整下面的英文申报草稿，须与最终 App 和演示视频一致：

> Photo Cleaner's core function is ongoing, on-device photo library management. It groups accessible photos by month, lets users swipe to keep or mark photos for removal, saves review progress, and provides a review queue before deletion. On Android 11 and later, deletion uses Android's batch confirmation; Android 10 requests per-photo consent when required. Android 8.1–9 use storage write permission after an explicit in-app permanent-deletion confirmation. READ_MEDIA_IMAGES is used to enumerate and display the photo library for this core workflow. A one-time picker does not provide a complete monthly timeline or ongoing review progress across a user's photo library. Users can instead grant selected-photo access on supported Android versions; in that case, the app clearly limits its timeline to the allowed photos. Photos are processed locally and are not uploaded, sold, or used for advertising. Videos are not accessed.

审核演示录屏建议：首次用途说明 → 系统授权 → 月份列表 → 上/下滑动与撤销 → 待删除复核 → 应用确认 → Android 系统确认 → 部分授权提示 → 设置隐私说明。录制设备使用无个人信息的测试照片。权限声明可能要求视频演示，以 Play Console 实际字段为准。[官方权限声明说明](https://support.google.com/googleplay/android-developer/answer/9214102?hl=en)

如果广泛访问申请未获批准，应根据具体审核理由调整产品与权限；不能把它伪装成其他权限绕过。改为 Photo Picker 模式会改变“整库按月整理”的产品边界，需要另行设计导入及授权持久化流程。

## 3. 隐私政策与 Data safety

当前代码已加入 Google Mobile Ads SDK 和 UMP，不能再沿用“不收集、不共享数据”的声明。照片及整理记录仍在本机处理，但广告 SDK 会处理设备/IP、广告交互和诊断等数据。根据最终 SDK 版本、广告配置及 Google 官方披露逐项填写 Data safety，并声明应用包含广告。[Google Mobile Ads 数据披露](https://developers.google.com/admob/android/privacy/play-data-disclosure)。[Data safety 官方说明](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)

必须完成：

- 将 `privacy-policy.html` 模板替换为实际发布者名称、可联系邮箱和生效日期；公开托管为可访问的 HTML 网页。
- 页面无需登录、不受地域限制、不能只提供 PDF；明确 Photo Cleaner 名称、数据用途、保留/删除方式和联系渠道。
- 在 Play Console 和 App 中使用相同的公开隐私政策网址，App 内包含隐私政策入口和发布者联系方式。
- 用 `publisherName`、`supportEmail`、`privacyUrl` 构建，并运行 `:app:verifyReleaseMetadata`；任务只检查基本格式，还须人工打开网址核对内容。
- 当加入广告、分析、上传、支付或其他 SDK 时，重新核对 Data safety、权限与政策，不沿用“不收集”的结论。

即使不访问个人敏感数据的应用也需要隐私政策；本 App 实际访问照片，更应完整说明。[Google Play 用户数据政策](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en)

当前无账号创建，所以没有 App 账户删除流程；用户可以重置整理决定，或通过 Android 清除应用数据/卸载删除本地记录。照片删除是另一项用户主动操作，已删照片不会随重置记录恢复。

## 4. 删除、提醒、评价

- 下滑仅标记。Android 11+ 二次复核后调用 `MediaStore.createDeleteRequest`；Android 10 按需逐张授权再删除；Android 8.1–9 经 App 明确确认及旧版写权限后删除。只报告明确成功的照片，失败或取消保留对应待删标记。每批不超过 2,000 个 URI；明确“永久删除、可能无法恢复”。[共享媒体操作](https://developer.android.com/training/data-storage/shared/media)
- 部分授权刷新遵循实际可见范围，不将权限收回误认为成功删除。[Android 部分照片权限](https://developer.android.com/about/versions/14/changes/partial-photo-video-access)
- 提醒默认关闭，用户开启时才申请通知权限；WorkManager 普通任务，无精确闹钟、无前台服务。传递依赖包含 WAKE_LOCK、RECEIVE_BOOT_COMPLETED、ACCESS_NETWORK_STATE 等普通权限供调度使用，AdMob 另含 INTERNET、广告标识符等权限；最终以合并 Manifest 为准。
- **Rate Photo Cleaner** 直接打开真实商店详情页，设备无 Play 应用时用浏览器。没有星级筛选、好评奖励或功能解锁条件。显式按钮不依赖有配额的 In-App Review 弹窗。[官方评价交互建议](https://developer.android.com/guide/playcore/in-app-review)

## 5. 提交前人工操作

- 确认品牌与 Play Console 应用对应；当前 applicationId 为 `com.maxdevlab.cleaner.photo`。
- 在 Play Console 完成发布者验证、联系信息、目标受众、内容分级、广告声明、Data safety、照片权限声明和隐私政策。
- 准备真实手机截图、512×512 图标、商店图文；商店描述只承诺已经存在的功能，不宣传 AI 去重、病毒扫描、加速或“保证释放 X GB”。
- 应用未上架前，评价链接没有可用详情页，内部/封闭测试后检查实际链接。
- 完成自己账号适用的测试和正式发布准入要求，以 Play Console 当前提示为准；本工程不代替账号资格流程。
- 在目标设备上验证下述覆盖，再跑 Play 预发布报告，处理崩溃、ANR、无障碍与兼容问题。

建议英文短描述：

> Clean up photos month by month. Swipe to keep, review, and delete.

## 6. 设备覆盖清单

- Android 8.1/9、10、11/12、13、14+，全量授权、部分授权、拒绝、永久拒绝、重新授权。
- 空相册、HEIC、透明图、超大图、旋转信息、损坏或已失效 URI、SD 卡拔出。
- 外部添加/编辑/删除图片、权限缩减、Activity 重建、进程回收和删除中断。
- 删除取消、失败、成功、超过 2,000 张分批；验证只删除本次选中的照片。
- 常用英语地区时区、跨年月份、夏令时、月度提醒、通知关闭及电池限制。
- 大字体、TalkBack、横屏、小屏、安全区和手势导航。
- Release R8 构建、64 位设备、16 KB 页面设备、Play 测试安装及商店评价入口。

已执行的项目见 [验证记录](VALIDATION.md)；未执行项不得在商店资料中描述为“全面验证”。

## 隔离测试包

`-PisolatedDeviceTest=true` 生成仅供测试的 Debug 包 `.devicetest`，不得上传 Google Play。正式发布保持普通 applicationId、测试模式关闭，重新构建签名 AAB。真机测试不需要替换用户安装的正式应用，也不会把整个相册作为测试数据。

## 照片详情权限补充

新增 `ACCESS_MEDIA_LOCATION`：仅当用户展开详情并点击 Show photo location 时申请，用于读取照片 EXIF 内已有 GPS 坐标。无 `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`，不获取当前位置，不上传、不反向地理编码。首次图库授权不捆绑此权限。发布前同步审核权限说明和实际隐私政策；拒绝照片位置权限后其他详情和整理功能继续可用。

## 6. AdMob 发布配置

- Debug 固定官方测试 App ID / 插屏 ID，不使用正式广告点击测试。Release 通过 `-PadmobAppId=... -PadmobInterstitialId=...` 配置正式 ID；两个参数都为空时广告关闭。运行 `verifyReleaseMetadata` 校验参数。
- 在 AdMob 控制台按目标市场配置并发布 UMP 隐私消息；提供正式 ID 后验证欧洲及适用美国地区同意表单、撤回/变更入口。官方示例 App ID 无发布者消息，所以 Debug 测试广告不走真实同意表单；Release 无此跳过路径。
- 配置 app-ads.txt、完成 AdMob 应用验证，并核对内容分级与目标受众；当前方案面向一般成人用户，若面向儿童需另外配置与验证。
- 计数规则、断网/无填充/展示失败、旋转和恢复验证参见 `cleanup-ads/VALIDATION.md`。

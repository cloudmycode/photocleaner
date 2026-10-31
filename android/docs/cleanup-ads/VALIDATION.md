# 清理完成后的 AdMob 插屏

2026-10-31。已配置用户提供的正式 AdMob ID；Release 使用正式 ID，Debug 仍使用官方测试广告。正式广告填充及发布者后台隐私消息尚未实测。

## 行为

- 删除报告先写入本地数据库、排除已删照片并同步月份/整理记录，然后才产生广告展示机会。
- 每次应用进程启动时，从零开始在内存中记录清理次数，不读写磁盘。前两次符合条件的清理免广告；从第三次起，每次清理最多尝试展示一条已缓存广告。不设置每日/会话次数上限。
- 一次清理指一次删除操作，不按照片数量计数。有已确认删除且没有用户取消/中断才计数；部分失败但有成功仍计数。空操作、全部失败、取消（包括部分删除后的取消）、中断恢复、仅划选完成不计数/不展示。
- 次数饱和到 3，在内存中保留最近报告 ID 防止同一报告重复计数。计数由 Application 持有，页面重建、广告返回和短暂切后台不清零；应用进程重新启动后清零。旧版本磁盘上的计数不再读取。
- 只使用广告展示点已有、未过期的缓存。没有广告、无网络/无填充、没有同意、后台、展示异常均直接继续，不等待加载、不补弹、不积累展示次数。失败请求有 30 秒重试退避，这是加载保护而非展示频控。
- 结果页面在广告关闭/展示失败之后才进入 Compose；数字动画只在前台播放，成功完成音效也由可见结果页触发一次。重建 Activity 不重复广告；进程恢复直接显示结果，不恢复广告机会。
- 删除完成后的短暂过渡页不提供继续清理按钮，不把广告放在 Done 按钮后。SDK 关闭按钮保持原样。

## 代码边界

- `ads/CleanupAdPolicy.kt`：纯计数策略、一次性广告/声音领取，单元测试不依赖 Android 或广告网络。
- `ads/CleanupAdSession.kt`：Application 范围的内存计数与报告去重，无磁盘读写。
- `ads/CleanupAds.kt`：广告服务接口。自动删除测试替换为测试实现，不点击正式广告。
- `ads/AdMobCleanupAds.kt`：单条广告预加载、55 分钟缓存有效期、SDK 生命周期回调、UMP 同意和隐私选项。加载回调永不主动展示广告。
- `PhotoViewModel`：同步删除报告后发出一次展示事件；广告 SDK 不参与真实删除和成功字节计算。
- `MainActivity`：只在前台领取展示机会；任何展示失败仍进入结果页。

## 构建配置

- Google Mobile Ads SDK 25.1.0，UMP 4.0.0；minSdk 保持 27。25.5.0 的 Kotlin 2.3 元数据与当前 Kotlin 2.1 工具链不兼容，因此使用仍获支持的 25.x 版本，未为广告升级整套构建工具。
- performance 隔离性能测试明确关闭广告并使用示例 ID，避免继承 Release 的正式广告配置。
- Debug 固定 Google 官方测试 App ID `ca-app-pub-3940256099942544~3347511713` 和插屏 ID `ca-app-pub-3940256099942544/1033173712`，不使用任何正式广告单元。
- Release 默认读取 `android/gradle.properties` 中的正式 ID：App ID `ca-app-pub-7579918370231503~8641056629`，插屏单元 `ca-app-pub-7579918370231503/6769571752`。也可通过 `-PadmobAppId=... -PadmobInterstitialId=...` 覆盖；两项为空时 `ADS_ENABLED=false`，不初始化或请求广告。`verifyReleaseMetadata` 拒绝正式发布参数中的示例 ID 或半套配置。
- 官方示例 App ID 没有发布者 UMP 消息，仅测试广告模式跳过真实同意表单。Release 必须先通过 UMP `canRequestAds()` 才初始化/请求广告；设置按 UMP 状态显示 `Ad privacy choices`。修改隐私选择丢弃旧广告缓存与在途响应。
- 网络权限、应用内隐私说明、公开隐私政策模板与发布检查表已同步更新；不再声称无广告/无联网/不收集任何数据。照片、EXIF、文件夹和清理记录不传给广告服务。

## 验证记录

以下为此前持久计数版本的验证记录。改为会话内存计数后已同步更新测试代码，按用户要求未运行测试或重新构建；下列 APK 和测试结果不代表本次修改已验证。

- 66 项单元测试通过。
- API 36 模拟器 6 项流程测试通过：前两次免广告、第三次展示前数据已同步/结果未出现、无广告跳过后不补弹、展示异常仍到结果页、真实删除与系统取消/返回/重建、快速反向划图、持久计数去重。
- 华为 BLA-AL00 / Android 8.1 / API 27，最终依赖构建 4 项设备测试通过：第三次展示、无广告跳过、展示异常、持久计数。设备删除仅涉及独立测试版生成并加入白名单的图片。
- 最终依赖构建另在 API 36 模拟器跑真实 AdMob 集成测试：官方测试插屏成功加载、展示、关闭并调用完成回调（1 项通过，48.25 秒，包含网络加载及关闭等待）。`official-test-ad.png` 为真实 SDK 测试广告截图，不是自制广告。
- 以上删除流程测试通过接口使用测试替身控制就绪/失败状态；它与真实 SDK 网络测试分开，确保无填充不会掩盖业务回归。
- Debug 构建、Release Kotlin 编译、Android Lint 通过（0 error / 39 warning）；为解决广告 SDK 传递依赖中的旧 Fragment 与 Activity Result 冲突，增加 Fragment 1.8.9 约束。
- 初始测试广告普通版 APK SHA-256：`04581d476ac3fe1a78cd9e0aeb93e430de5db0efd39e6613d6a6632740ef8f6d`。
- 华为普通版已覆盖安装并启动，安装文件 SHA-256 与上面一致；原有 42 条整理记录内容摘要完全一致，未清空用户数据。独立测试应用与测试组件已移除。
- UMP 正式地区表单未实际网络验证，因为尚无发布者 App ID/已发布隐私消息；测试广告模式不代表正式同意流程已通过。

正式 ID 已配置。发布前仍需发布者核对 AdMob 应用关联、隐私消息、app-ads.txt 和公开隐私政策，并根据实际 SDK 配置完成 Play 广告声明与 Data safety。测试广告接入成功不代表正式广告获批或可填充。

## 官方参考

- [插屏集成](https://developers.google.com/admob/android/interstitial)
- [推荐展示位置](https://support.google.com/admob/answer/6201350?hl=en)
- [UMP 同意](https://developers.google.com/admob/android/privacy)
- [SDK 发布说明](https://developers.google.com/admob/android/rel-notes)
- [版本支持](https://developers.google.com/admob/android/deprecation)
- [广告数据披露](https://developers.google.com/admob/android/privacy/play-data-disclosure)

## 正式 ID 配置验证（2026-10-31）

用户提供的正式 App ID 和插屏单元已写入工程属性。`assembleRelease bundleRelease assembleDebug generatePerformanceBuildConfig processPerformanceMainManifest` 构建成功。核对生成配置和合并 Manifest：Release 正式 ID / 广告开启 / 非测试模式；Debug 官方测试 ID；performance 示例 ID / 广告关闭。

最终签名 APK 和 AAB 中均核对了新包名、正式 App ID、DEX 中的正式插屏单元；两者的证书均与 `max2/readme/key.jks` 匹配。当前产物哈希见 `production-config-verification.json`。正式广告填充和发布者后台隐私消息没有实测，本次也未安装到手机或触发正式广告请求。

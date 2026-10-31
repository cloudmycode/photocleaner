# 包名与发布签名

2026-10-31。

- applicationId、namespace、Kotlin 包声明及源文件目录已统一为 `com.maxdevlab.cleaner.photo`，包括 main、test、androidTest。
- 最终 APK 包名经 aapt2 检查；AAB manifest 包含同一标识。
- Release APK 和 AAB 均使用用户指定的 `Max2/max2/readme/key.jks`，别名 `securityantivirus`。两个产物的证书 SHA-256 均与该文件匹配。
- 证书 SHA-256：`A9:0A:89:31:75:22:8F:26:19:FE:C1:4F:AB:29:A8:D9:2C:46:7C:FD:04:B5:AB:C3:C0:7E:28:98:CC:5E:9D:12`。
- 密码只放在 Git 忽略的 `android/signing.properties`，权限为 0600；证书保留原位置，未复制入仓库。构建文件不包含密码，示例配置不包含密码。
- Release 构建强制检查本机签名配置；缺失配置或证书时失败，不生成未签名替代品。Debug/performance 仍使用开发签名。

## 验证

执行 `assembleRelease bundleRelease testDebugUnitTest compileDebugAndroidTestKotlin` 成功（2 分 41 秒）。66 项单元测试通过，无失败或跳过；设备测试源码编译通过，未执行设备测试。

APK 通过 apksigner 校验，v2 签名有效（minSdk 27）。AAB 通过 jarsigner 校验；自签名证书的信任链和时间戳提示不代表文件签名失败。发布证书与源证书已比对。产物哈希和大小见 `verification.json`。

未运行 `verifyReleaseMetadata`，本次只验证包名、签名和构建。公开隐私地址、发布者信息及正式广告 ID 等仍按发布检查表另行配置；未配置正式 AdMob ID 的 Release 不请求广告。

未在手机安装新包名版本。它会作为新应用安装，不覆盖 `com.cloudmycode.photocleaner`，旧包名私有数据不会自动迁移。

旧验证记录中的包名和安装哈希属于历史构建，不作追溯修改。

# 手机界面改版验证

> 最新网页对齐实现及验证见 [本轮记录](../web-parity/VALIDATION.md)。以下为历史记录。

日期：2026-10-31（Asia/Shanghai）。Android Studio 工程最低 API 27，target / compile API 36。

## 实现

- 按当前 HTML 原型实现中性灰白、系统蓝、系统红配色，筛选页使用纯黑背景。
- 月份首页移除大标题和筛选标签，本月为横向封面，其余月份双列图片卡片。
- 默认完整显示照片；上滑保留、下滑标记移除，背景与控件固定；下一张从 85% 随拖动放大。
- 按钮操作使用 560 ms 动画，手势释放后使用 230 ms 动画，复用同一提交入口。
- 浏览队列与持久化决定分开管理；已完成月份可以重新浏览、重新标记、撤销。
- 待删页三列网格、默认未选中、支持全选和批量保留；顶部箭头与 Android 返回均回到进入前的位置，没有额外 Continue sorting 按钮。长按图片可预览。
- 设置保留原有 ModalBottomSheet、内容和操作，仅统一配色；没有使用 Web 的独立设置页面。
- 权限适配、系统删除和中断恢复继续使用已有 platform 与删除协调器。没有新增权限或改变数据库表结构，旧整理记录可继续使用。

## 实际检查

- 单元测试：28 项通过（9 项整理规则、14 项删除兼容、5 项浏览会话），0 失败。
- 华为 BLA-AL00 / Android 8.1 / API 27：2 组交互测试通过，约 19 秒。
- Pixel 7 模拟器 / Android 16 / API 36：2 组交互测试通过；最终复测约 110 秒。
- Debug APK、Release AAB 构建成功；Release 仍未配置发布签名。
- Debug lint：0 errors，25 warnings，主要为既有权限常量保护、依赖更新和 KTX 建议。

两组设备测试覆盖：按钮操作、上下滑动、短距离拖动不提交、横滑不提交、撤销、完整显示切换、完成月份重进、待删页箭头返回、重建后 Android 返回、空待删页返回、批量保留、重置、应用删除确认取消和批准、设置底部弹窗与隐私入口。API 36 另验证了系统删除弹窗的取消和批准。

设备测试使用独立 `.devicetest` 应用与 URI 白名单，只创建、读取、删除自动生成的测试照片。测试正常结束后清理了测试照片，未操作用户真实照片或普通版整理记录。

最终复测后的小幅收尾：统一 photo/photos 单复数，保留删除结果页再次进入待删页时的原始返回目标；重新执行构建、单元测试和 lint。

## 证据

- [API 27 运行结果](test-results/api27.txt)
- [API 36 最终运行结果](test-results/api36.txt)
- [单元测试报告](test-results/unit/)
- [真机筛选页](screenshots-api27/screenshots/02-review.png)
- [真机设置底部弹窗](screenshots-api27/screenshots/06-settings.png)
- [API 36 筛选页](screenshots-api36/screenshots/02-review.png)
- [API 36 设置底部弹窗](screenshots-api36/screenshots/06-settings.png)

## 未覆盖

本轮没有重新验证 Android 10 外部照片逐张授权、Android 14+ 部分照片授权弹窗、进程被系统完全回收、大字体、横屏、TalkBack、超大相册或不同宽高比照片组合的完整设备矩阵。Activity 重建不等于进程回收；当前设备测试图片为竖图。未提交 Google Play 审核。

## 交付构建

普通 Debug APK：`app/build/outputs/apk/debug/app-debug.apk`，包名 `com.cloudmycode.photocleaner`，测试模式关闭，minSdk 27 / targetSdk 36。

- APK SHA-256：`fc3af44f5bc21ce2c204ad7d23beb687f364bbd215879448dd78b1fe43984fa3`
- 未签名 Release AAB SHA-256：`e0aa5f608971fb3d78414affc86002497ffdb15143ace9a43419dfe8196158d6`

## 普通版安装状态

2026-10-31 后续复核：最初更新被系统中止，手机仍运行旧版，这是用户看到界面不同的直接原因。重新进行覆盖安装后返回 `Success`，手机上实际 APK 的 SHA-256 已与本次交付一致：`fc3af44f5bc21ce2c204ad7d23beb687f364bbd215879448dd78b1fe43984fa3`。

启动 `com.cloudmycode.photocleaner/.MainActivity` 返回 `Status: ok`，单次调试启动 TotalTime 158 ms（不是性能基准）。普通版未卸载、未清除数据；独立测试版和生成的测试图片已清理。

# 月份完成后的评价入口

日期：2026-10-31。

## 参考与设计

参考只读项目 `/Users/wang/Project/antivirus/Max2/max2/`：

- `scan/activity/SafeActivity.java` 和 `fullscan/activity/FullScanSafeActivity.java`：扫描结果页内嵌评价区，处理过评价入口后隐藏。
- `aisecurity/activity/CleanResultActivity.java`：成果在前，评价在后。
- `aisecurity/util/MyUtil.java`：共享评价路由。

Photo Cleaner 在一个月全部划选完成后，依次呈现封面、Month, sorted.、Kept / To remove 统计、继续整理主操作、白色圆角评价卡片。没有弹窗和自动跳转。卡片文字：Share your experience / Your review helps improve Photo Cleaner.；按钮 Rate Photo Cleaner / Not now。未确认删除的标记不宣称为已释放空间。

## 实现

- `RatingInvitationCard` 只负责界面；`RatingInvitationStore` 只保存邀请状态；ViewModel 提供状态与操作。与设置页共用 MainActivity 的商店跳转逻辑。
- 2026-10-31 最终调整：点击 Rate Photo Cleaner 即保存 30 天提醒冷却期（设置页评价入口也共用）；到期后再次完成整理即可提示。Not now 仅隐藏本次完成页。移除 Already rated；不再使用 confirmed_rated 永久状态。这是应用内提醒策略，不代表能够核实商店提交结果。
- 状态在本机保存，无新增权限、网络调用、SDK。页面打不开时保留邀请并显示已有错误提示。
- 不复制 Max2 的五星文案、正负反馈分流以及“API 返回成功等于已评价”的推断。

## Google Play 依据

核对 [Google Play In-App Reviews 指南](https://developer.android.com/guide/playcore/in-app-review#quotas)：用户主动点击的评价按钮应直接前往 Play Store，避免应用内评价配额导致按钮无响应。因此复用已有明确跳转，不新增 Play Review SDK。

同时核对 [User Ratings, Reviews, and Installs](https://support.google.com/googleplay/android-developer/answer/9898684)：邀请真实反馈，无奖励、强制评价或预设评分。

## 初版验证（后续变更见 ui-refinement）

- debug 编译、45 项单元测试、lint 通过。
- API 36 模拟器两项新增端到端测试通过：完成后显示邀请；Not now 在重建后保持隐藏，继续浏览与设置入口可用；评价按钮正确指向本应用商店页面，重建后保持隐藏且照片未改动。
- 已完成月份重浏览与垃圾桶返回回归测试：组合执行首次遇到 Espresso 窗口焦点超时，单独重跑通过；保留两轮日志。
- 实际截图 completion.png 已检查：主操作、卡片与再次浏览/撤销均可见，无遮挡。
- 测试使用独立 .devicetest 包和生成照片；评价点击由测试拦截，只验证目标地址，不提交真实评价。Google Play 的实际评分提交需在商店可用的发行/测试版本验证。
- 已清理模拟器上的独立测试包及生成图片，停止模拟器。普通版照片和整理记录保留。

普通版已覆盖更新到华为 BLA-AL00；实际安装 APK 与本地 SHA-256 一致：`a574de1f7055a822c70879636e9d6d7ab020d793af2766db479317b1b35e18f9`，启动成功。本轮未在普通版替用户完成月份或发起评价。

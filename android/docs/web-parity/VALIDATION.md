# 网页界面对齐验证

> 后续滑动性能优化、最新构建和安装状态见 [性能验证](../swipe-performance/VALIDATION.md)。

2026-10-31。设计依据：`design/android-monthly-cleaner/index.html`。本记录替代上一轮 `ui-refresh/VALIDATION.md` 对当前界面和安装包的描述。

## 本轮修改

- 集中图标路径、文字、按钮、进度线、导航和底部弹窗，避免默认 Material 尺寸改变网页设计。
- 月份首页使用横向首张封面与双列卡片。本月没有照片时用最新月份封面，显示实际年份，不冒称本月。
- 筛选页完整显示照片，背景、按钮、日期固定；下一张从 85% 随位移放大。上滑保留、下滑标记移除；短滑和横滑不提交。按钮 560 ms，手势完成 230 ms，分别使用网页相同缓动和淡出时间。
- 待删页三列、多选、文字计数、灰蓝保留按钮与 #FF3B30 删除按钮；移除网页未启用的长按预览。顶部和系统返回保留浏览位置，没有 Continue sorting。
- 删除确认和手势说明改为底部弹窗；删除后留在待删列表，空列表显示网页对应空状态。
- 完成页改为倾斜照片、数量、Next month / Review removals、Browse again、Undo last swipe，以及底部导航。
- 设置仍为原有 Android ModalBottomSheet，内容和操作保留，仅使用统一主题。权限和系统删除授权保留 Android 流程。
- 没有改变权限范围、数据库结构或删除适配层；公共状态与业务逻辑继续复用。

## 已验证

- 普通 Debug APK 编译成功；minSdk 27、targetSdk 36，测试模式关闭。
- 28 项单元测试通过，0 失败。
- Debug lint：0 errors、25 warnings。
- API 36 模拟器首轮 2 项集成交互测试通过，87.499 秒。随后仅在截图前增加等待显示帧，不改变应用代码，最终复测 2 项通过，78.57 秒；取得稳定的视觉证据。另补充首页图片加载完成等待，修正了该等待错误应用于系统弹窗时的测试辅助函数问题；受影响的删除流程复测通过（1 项，50.762 秒），应用代码未改变。
- 覆盖：完整显示切换、点击与上下滑动、短滑/横滑回弹、撤销、Activity 重建、已完成月份再浏览、待删页和系统返回、批量保留、应用和系统删除确认取消/批准、删除后仍在列表、重置、设置及隐私入口。
- 测试使用独立 `.devicetest` 应用、精确 URI 白名单和网页示例照片副本。示例照片只打包到 androidTest，不进入普通应用。正常结束自动清理测试照片。

## 交付

- 普通 APK：`app/build/outputs/apk/debug/app-debug.apk`
- SHA-256：`218589b5c5f7fddb701a1ed4e8a91ce443647e21ee1abb67c3efb57857e32964`
- 本轮没有重新生成 Release AAB；此前的 AAB 不包含本轮修改。

## 真机状态与边界

华为 BLA-AL00 / Android 8.1 / API 27 仍装有上一轮普通版，核对 SHA-256 为 `fc3af44f5bc21ce2c204ad7d23beb687f364bbd215879448dd78b1fe43984fa3`。独立测试安装等待确认后已取消；最终普通版更新已发出，仍需手机端确认。尚不能宣称本轮真机测试或普通版更新完成。未清除普通版数据。

API 27 本轮待验证；没有新增 Android 10 外部照片授权、Android 14 部分授权、进程回收、大字体、横屏、TalkBack 或超大相册覆盖。系统状态栏、导航栏、字体渲染和权限/删除授权由 Android 提供；旧版本不提供网页同样的实时背景模糊。未提交 Google Play 审核。

## 证据

- [最终模拟器测试](test-results/api36.txt)
- [单元测试](test-results/unit/)
- [首页](screenshots-api36/01-months.png)
- [照片浏览](screenshots-api36/02-review.png)
- [待删列表](screenshots-api36/03-to-delete.png)
- [手势说明底部弹窗](screenshots-api36/08-gesture-guide.png)
- [完成页](screenshots-api36/09-month-sorted.png)
- [原有设置弹窗](screenshots-api36/06-settings.png)

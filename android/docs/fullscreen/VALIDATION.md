# 默认全屏（2026-10-31）

- 首页、筛选页及应用内底部弹窗默认隐藏状态栏和系统导航栏；保留应用自身的 Months / Remove 导航。
- 使用 AndroidX WindowInsetsControllerCompat 的临时边缘唤出行为，不更改手机全局导航设置。返回应用、关闭应用弹窗或从系统授权/设置页返回后恢复全屏。
- 公共适配集中在 platform/Fullscreen.kt；窗口配色与弹窗绑定由 ui/SystemBars.kt 处理。内容仍避让刘海/挖孔，暂时唤出系统栏不为页面增加永久空白。
- assembleDebug / testDebugUnitTest / lintDebug 通过；42 项单元测试通过。
- API 36 模拟器：2 项交互回归通过（双指缩放、保留/删除/撤销、系统删除确认返回、设置和隐私弹窗）；确认系统首次全屏提示后，补测缩放并检查首页、照片页、设置截图，状态栏/导航栏均隐藏。自动化测试会确认 Android 自己的首次全屏提示，不更改全局设置。华为 BLA-AL00（Android 8.1 / API 27）普通版覆盖安装成功，安装文件 SHA-256 与下列构建一致；启动 Status: ok（471 ms）。已验证首页、设置弹窗的系统状态栏与导航栏均为 HIDDEN；底部边缘上划可临时显示两栏，约 5 秒后自动隐藏；从系统应用设置返回、窗口重新获得焦点后恢复 HIDDEN。未清空普通版数据。
- 普通 APK SHA-256：`d370dc8acedf04939821a35e52bc367fb90852f692f9184651abbdf24f3a4c77`。

参考：[Android 沉浸式全屏](https://developer.android.com/develop/ui/views/layout/immersive)。系统自己的授权窗口由 Android 控制。

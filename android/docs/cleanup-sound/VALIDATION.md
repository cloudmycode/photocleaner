# 清理音效

2026-10-31

- 删除进度过程不播放任何音效。仅全部成功且结果保存、切换到结果页后播放一次完成声。失败、取消、启动恢复旧日志不播放成功完成声。
- 设置底部弹窗新增 `Cleanup sounds`，默认开启，关闭状态保存在本机。
- 使用无重放的 SharedFlow，只在 Activity 恢复到前台时接收新事件，重进页面或旋转不会重播旧音效；后台停止当前声音。
- 播放前检查静音/振动、系统音量、勿扰和正在播放的音乐；不调整系统音量、不申请音频焦点、不添加权限。
- 使用 SoundPool 预加载唯一的完成音效，最多一个声音；移除进度音效事件、释放声资源和不再需要的节流逻辑。
- 音源为项目原创合成 PCM：完成声 380 ms / 16,802 字节。生成脚本 `tools/generate_cleanup_sounds.py` 可重现，不使用第三方音乐素材。

初版（含进度音效）的历史验证：62 项单元测试通过；API 36 模拟器和华为 BLA-AL00 / API 27 各 3 项设备测试通过，覆盖资源解码、设置切换及 Activity/新 ViewModel 恢复、真实删除/取消/返回列表。构建和 Lint 完成（36 个 warning，0 个 error）。真机截图为独立测试版生成图片，不含用户图库。自动测试验证音频解码及触发逻辑，不包含扬声器录音或听感评价。

APK SHA-256：`1dd4dbacd6dc8e554ecb7a4b1430f30a673a849a260f36ef8ec345148dea392d`。

普通版已覆盖安装到华为手机；已安装包的 SHA-256 与本地一致，启动成功。更新前后 83 条原有整理记录内容摘要完全一致。独立测试应用及生成图片已清理。

API 参考：[SoundPool](https://developer.android.com/reference/android/media/SoundPool)、[音效用途](https://developer.android.com/reference/android/media/AudioAttributes#USAGE_ASSISTANCE_SONIFICATION)、[读取勿扰状态](https://developer.android.com/reference/android/app/NotificationManager#getCurrentInterruptionFilter())。

## 仅结果页音效调整

删除进度音效相关的 3 项旧测试随功能删除，保留完成声成功/取消/失败/中断的规则验证。当前 59 项单元测试通过，普通版构建、设备测试包编译及 Android Lint 通过。

API 36 模拟器完成音效解码测试通过（1 项）。本次未录制扬声器输出。华为普通版已覆盖更新并启动，安装文件校验一致，42 条整理记录更新前后完全一致。

当前 APK SHA-256：`d089727a3b75db10d350aae8dce94365c8654edb2f0be489c04dcddf07b1c93c`。

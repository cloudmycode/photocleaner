# 图片滑动性能验证

2026-10-31。保持当前 HTML 对应界面、上滑保留 / 下滑标记删除、560 ms 按钮动画与 230 ms 手势完成动画；设置继续使用原来的底部弹窗。

## 实现

- 滑动位移、缩放、旋转、淡出与提示透明度都在 `graphicsLayer` 中读取。两个静态方向提示只切换图层透明度，页面不再因为拖动距离逐帧重组。
- 两张图片使用同一调用位置和稳定 URI key。下一张进入前台时保留 painter / 图片组件，避免拆掉后重建。
- 两张可见照片之外，仅额外预加载一张；离开界面或改变候选照片时取消旧预加载。使用同一个请求构造函数和同一个 Coil 缓存。
- 以屏幕尺寸解码筛选预览，最长边上限 2048 像素；原始照片文件不变。屏幕未测量时不发起原尺寸解码。
- 共享图片缓存使用应用 memoryClass 的 20%，上限 48 MiB；BitmapFactory 并行解码限制为 2。缓存上限不是应用总内存上限，仍有活动图片、临时解码、GPU 和系统内存。
- 用 AsyncImage 替代 SubcomposeAsyncImage。URI、内容签名与预览尺寸共同区分缓存，内容变化不会沿用旧图。
- 照片签名、月份和日期格式化复用；同一状态快照复用派生集合。筛选时仅生成当前月份分组，不再排序其他月份。状态复制后重新计算，撤销和外部修改不会复用旧进度。
- 数据库写入仍在 IO 线程，仍等待持久化成功才推进队列，失败回弹逻辑保留。

实现依据：[Android Compose 延后状态读取](https://developer.android.com/develop/ui/compose/performance/bestpractices)、[Coil 的 Compose 组件说明](https://coil-kt.github.io/coil/compose/)。项目仍使用 Coil 2.7.0，没有迁移图片库。

## 同条件测量

Pixel 7 API 36 模拟器，60 Hz；`performance` 构建不可调试、未混淆，使用独立 `.devicetest` 包。测试直接通过 UiAutomator 发送真实输入，使用 Window FrameMetrics，不使用 Compose 测试时钟快进。

准备 24 张测试照片（4032×3024 与 3024×4032 混合），进入月份后等待 1.5 秒，连续 18 次翻页：12 次上下滑动与 6 次 Keep 按钮。只操作生成图片及独立测试记录，普通版的数据不受影响。输入、动画、布局、绘制耗时合计为表中的“应用侧工作”，不等于完整帧耗时。

| 指标 | 优化前 | 优化后 |
| --- | ---: | ---: |
| 记录帧数 | 685 | 563 |
| 应用侧工作 P95 | 18.58 ms | 12.70 ms |
| GPU 工作 P95 | 83.06 ms | 84.12 ms |
| 整体帧耗时 P95 | 298.04 ms | 298.44 ms |
| 超过刷新预算的帧 | 100% | 100% |
| 丢失的测量报告 | 0 | 0 |

这次单次对比的应用侧 P95 降低约 31.6%，但整体 P95 没有改善，模拟器 GPU / 调度限制占主导。因此这组数据只支持“应用侧工作量下降”，**不能作为整体流畅度通过或真机稳定 60 FPS 的证据**。总用时含 UiAutomator 等待，不能用它反推 FPS。采样帧数不同也不是掉帧率。

优化后检查：缓存上限约 38.4 MiB（该模拟器 memoryClass 为 192 MiB），测量结束缓存约 36.9 MiB；仍可缓存的筛选预览 9 张，实际最大边 1376 像素。预览尺寸和缓存容量断言通过。缓存中的旧预览可按 LRU 淘汰，后台预加载仍只有一张。

## 回归与构建

- 35 项单元测试通过：包含预览尺寸 / 缓存上限、派生状态失效、当前月份快速查询与原分组结果一致性。
- 非调试构建的 18 次连续滑动性能测试通过，65.147 秒（包含测试图片准备和清理）。
- 两组设备交互回归通过，67.286 秒：手势、点击、回弹、撤销、重建、完成月份再次浏览、待删页返回、批量保留、应用及系统删除确认、设置等。
- Debug APK、Performance APK、Release AAB 构建成功；Release AAB 未配置发布签名。
- Debug lint：0 errors、25 warnings。

初次性能测试的清理函数重复移除 FrameMetrics 监听器，已修正并重新完成前后测试；表中仅使用修正后成功运行的数据。性能构建保持未混淆，避免同包插桩工具链接被重命名的应用依赖；发布构建依然启用 R8。

## 复测

```sh
./gradlew :app:assemblePerformance :app:assemblePerformanceAndroidTest -PdeviceTestBuildType=performance
adb -s DEVICE install -r -t app/build/outputs/apk/performance/app-performance.apk
adb -s DEVICE install -r -t app/build/outputs/apk/androidTest/performance/app-performance-androidTest.apk
adb -s DEVICE shell am instrument -w -r \
  -e class com.cloudmycode.photocleaner.SwipePerformanceTest \
  -e verifyPreviewBudget true \
  com.cloudmycode.photocleaner.devicetest.test/androidx.test.runner.AndroidJUnitRunner
```

真机另加 `-e allowPhysicalDevice true`，保持手机解锁。测试先校验隔离包，再生成测试图片，结束清理白名单图片；不会执行照片删除操作。结果保存于测试包外部私有目录 `files/swipe-performance.json`。正式性能验收应使用目标真机，至少多轮重复并观察长时间运行 / 温升 / 不同照片格式与内存压力。

默认构建和普通版包名不变，minSdk 27 / targetSdk 36。普通 Debug APK SHA-256：`9b613aba4ec87d89ebada3e5ae7e19742c01a7b204731a15976651fda09aa5ea`。

## 真机状态

2026-10-31 再次覆盖安装返回 `Success`。华为 BLA-AL00 实际安装 APK 的 SHA-256 已核对为 `9b613aba4ec87d89ebada3e5ae7e19742c01a7b204731a15976651fda09aa5ea`，与最终性能优化版一致。启动返回 `Status: ok`（单次调试启动 701 ms，不作为性能基准）。未卸载普通版、未清除数据。真机滑动帧率仍未测量，安装成功不等于流畅度验收通过。

## 证据

- [优化前原始数据](test-results/baseline.json)
- [优化后原始数据](test-results/optimized.json)
- [优化后性能测试](test-results/optimized-run.txt)
- [交互回归](test-results/interaction-tests.txt)
- [单位测试结果](test-results/unit/)
- [筛选页截图](screenshots-api36/02-review.png)

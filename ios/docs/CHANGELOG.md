# iOS 端修改记录

本文档用于记录 `ios/PhotoCleaner` 目录下所有代码级修改。新条目请置于顶部，按时间倒序。

---

## 2026-07-10

### 修复：从"屏幕截图 / 视频 / 大视频 / 屏幕录制"列表删除照片后，返回首页再次进入仍能看到已删除项（占位缩略图 + 0KB）

**触发场景**（引自 `ios/docs/m.md`）：
> 屏幕截图列表删除对应的照片后，返回首页，再次进入屏幕截图，发现删除的照片还在，但是照片大小显示 0KB，你检查一下是否本地还有照片的缩略图或者缓存导致。

**根因分析**：

- `PhotoLibraryService.deleteAssets(with:)` 调用 `PHPhotoLibrary.performChanges` 完成删除后，直接返回，**没有同步维护内存中的分类数组**：
  - `screenshotAssets` / `livePhotoAssets` / `videoAssets` / `largeVideoAssets` / `screenRecordingAssets`
  - 对应计数：`screenshotCount` / `livePhotoCount` / `videoCount` / `largeVideoCount` / `screenRecordingCount`
  - 对应容量：`*StorageBytes`，以及聚合的 `mediaStorageBytes`
  - `monthGroups` 里包含这些资产的月份分组
- 组件只依赖 `PHPhotoLibraryChangeObserver.photoLibraryDidChange` 回调 + 1.5 秒防抖后触发的 `refreshLibrary()` 来刷新，系统回调本身还有延迟，用户手速快时列表尚未更新。
- `AssetGridCleanView` / `AssetSwipeCleanView` 使用局部 `@State removedIDs` 过滤 UI，但视图销毁（返回首页）后该本地状态丢失，再次进入时仍读取到未清理的 `library.screenshotAssets`，其中的 `PHAsset` 对应的 `localIdentifier` 已在系统相册中删除，`PHImageManager` 无法加载缩略图，`PHAssetResource.fileSize` 也读不到值 → 呈现"缩略图为空/占位、大小 0KB"的假象。

**修复方式**（`ios/PhotoCleaner/PhotoLibraryService.swift`）：

1. `deleteAssets(with:)`：`performChanges` 成功后，
   - 设置 `suppressLibraryRefreshUntil = now + 3s`（复用现有抑制窗口，避免系统回调再触发一次全量 `refreshLibrary`）；
   - 调用新增的 `purgeDeletedAssetsFromMemory(identifiers)` 立即从各类内存数组、计数、存储字节、月份分组、主页概览缓存里剔除已删资产；
   - 同时调用已有的 `removeDeletedAssetsFromDetailGroups(identifiers)` 清理重复/连拍详情组。
2. 新增 `purgeDeletedAssetsFromMemory(_:)`：
   - 增量更新 `screenshotAssets/livePhotoAssets/videoAssets/largeVideoAssets/screenRecordingAssets` 及各自 `Count/StorageBytes`；
   - 遍历 `monthGroups`，把包含已删资产的月份分组重建（重新计算 `storageBytes`，若空则丢弃该月份）；
   - 顺带清理这些资产在 `monthlyReviewedIDs` 中的已浏览标记；
   - 增量下调 `photoCount` 与 `mediaStorageBytes`；
   - 调用 `persistHomeSummary()`，让下一次冷启动读取到修正后的首页概览缓存，避免"退出重进 App 后又看到旧值"。
3. 新增私有工具方法：
   - `removedStorageDelta(from:identifiers:)`：一次遍历完成"移除 + 累加被删字节数"，避免二次遍历；
   - `assetStorageBytesSum(for:)`：`nonisolated` 静态函数，用于月份分组增量计算。
4. `deleteSimilarAssets(with:)`：在原有 detail group 清理基础上，一并调用 `purgeDeletedAssetsFromMemory`。相似照片本质上可能就是屏幕截图/视频，之前这里只清理了 detail group，同样存在残留问题。

**影响范围**：

- 涉及 UI 页面：`AssetGridCleanView`（屏幕截图/视频/大视频/屏幕录制的多选删除入口）、`AssetSwipeCleanView`（左右滑清理入口）、月度浏览页、相似组详情页、智能搜索结果批量删除等所有走 `library.deleteAssets(with:)` 的调用点，一次修复全部收益。
- 不改动删除本身的行为（仍走系统 `PHPhotoLibrary.performChanges`），只是让内存视图立即与系统状态一致。
- 抑制窗口沿用 3 秒，与既有 `deleteSimilarAssets` 一致。

**验证建议**：

1. 进入"屏幕截图"列表，删除若干项 → 立刻返回首页 → 立刻再次进入"屏幕截图"：
   - 已删项应立即消失（不再显示占位 + 0KB）；
   - 首页概览的截图数量、体积、总媒体体积应即时下降。
2. 冷启动 App（关闭进程后重开）→ 概览数据应仍然是修正后的值（验证 `persistHomeSummary`）。
3. 同样步骤验证"视频""大视频""屏幕录制"三类。
4. 在"相似照片""连拍"详情组内删除后返回首页 → 进入"屏幕截图"分类，确认相关 asset 也已同步移除。

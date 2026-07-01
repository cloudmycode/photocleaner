# 智能搜索实现说明

> 本文档描述 PhotoCleaner「智能搜索」的完整实现方案。核心思路：**服务端大模型只负责把自然语言解析成结构化检索计划（SearchPlan），真正的匹配全部在客户端本地倒排索引上完成**，隐私不出端。

## 一、整体架构

```
用户输入查询词
     ↓
[客户端] SmartSearchClient.fetchPlan —— 组包 + MD5 签名，POST /smart-search
     ↓
[服务端] handler.go —— 校验签名 → DeepSeek 解析 → normalize → 过滤幻觉 tag
     ↓  返回 SearchPlan（filters + must + count）
[客户端] SearchEngine.match —— 本地倒排索引匹配 + filters 过滤 + 时间排序截断
     ↓
返回匹配的 PHAsset 列表
```

另有一条离线链路 `/enrich-tags`：客户端把原始视觉标签 + OCR 片段发给服务端，由大模型补全同义标签、敏感类型、可搜索描述（searchDescription），写回本地索引，供后续 `searchKeywordGroups` 子串匹配使用。

## 二、数据结构：SearchPlan

服务端返回、客户端消费的核心结构（`models.go` / `SmartSearch.swift` 字段保持一致）：

```
SearchPlan {
  summary:    string          // 用户语言的简短意图描述
  filters:    Filters         // 元数据硬条件（查正排档案，不走倒排）
  must:       MustMatch       // 检索条件
  count:      int             // 结果上限（默认/上限见 Config，客户端再钳到 ≤1000）
  confidence: float           // 模型置信度 0~1
}

Filters {
  dateRange {start,end}       // yyyy-MM-dd
  locationBounds []           // 命名地点的经纬度包围盒
  mediaTypes []               // image / video
  assetTypes []               // screenshot / live / screen_recording
  minSizeMB / maxSizeMB       // 文件大小
  minPixelWidth / minPixelHeight
  hasLocation bool            // 是否带定位
}

MustMatch {
  searchKeywordGroups [][]    // 关键词组：对 searchDescription 做子串匹配
  visualTagGroups     [][]    // 视觉 tag 组：走本地倒排 postings
  sensitiveTypes      []      // id_card | bank_card | passport | document
  ocrContainsAll      []      // OCR 文本必须全部包含
}
```

**组内 OR、组间 AND**：`searchKeywordGroups` / `visualTagGroups` 每个内层数组是「一个概念的同义词集合（命中任一即可）」，多个数组之间取交集。同义词由**大模型在组内直接展开**，客户端不再维护任何本地同义词表（旧的 `VisualSynonyms.swift` 已删除）。空数组由 `MustMatch.MarshalJSON` 保证序列化为 `[]` 而非 `null`。

## 三、客户端实现

### 3.1 SmartSearch.swift

- `SmartSearchConfig`：从 Info.plist 读取 `SmartSearchEndpoint` / `SmartSearchSecret`；`enrichEndpoint` 由 endpoint 同级路径推导为 `enrich-tags`。
- `SmartSearchClient.fetchPlan(query:availableTags:)`：
  - 组包 `query / locale / appVersion / buildVersion / availableTags（上限 1000）/ sign`；
  - `sign = MD5(query + secret)`；
  - 解析响应为 `SearchPlan`。
- `TagEnrichmentClient.enrich(payloads:)`：批量补全标签，`sign = MD5(deviceId + secret)`。
- `SmartSearchService.search(query:)`：取本地 `availableTags` → 拉取 `SearchPlan` → 经 `SmartSearchQueryParser.enrichPlan` 兜底修正 → 调 `SearchEngine.match` → 映射回 `PHAsset`。

### 3.2 SearchEngine.swift

本地匹配顺序（`SearchEngine.match`）：

1. `searchKeywordGroups`：遍历索引条目，对 `searchableDescriptionText` 做小写子串匹配（组内 OR、组间 AND）。
2. `visualTagGroups` + `sensitiveTypes`：走倒排 `tagPostings` / `sensitivePostings`，组内并集、组间交集，再与上一步结果取交集。
3. `ocrContainsAll`：对 `searchableOCRText` 逐条 `contains` 过滤。
4. `filters`：`passesFilters` 校验日期、经纬度包围盒 / hasLocation、mediaTypes、assetTypes、大小、像素。
5. 按 `creationDate` 降序排序，`count`（≤1000）截断。

## 四、服务端实现

### 4.1 路由（main.go，默认端口 8081）

- `POST /smart-search` —— 查询意图解析
- `POST /enrich-tags` —— 标签补全 / OCR 转图片描述
- `GET  /health` —— 健康检查（version 2.0.0）

### 4.2 /smart-search 处理流程（handler.go）

1. 校验 method / query / locale / availableTags / sign（`MD5(query + ServerSecret)`）。
2. `renderPromptInternal` 渲染 prompt（注入 query、locale、当天日期、availableTags）。
3. 调用 DeepSeek（`DeepSeekClient.ParseQuery`）；失败时返回 `emptySearchPlan`（空 must，不报错）。
4. `normalizeSearchPlan` 归一化。
5. `filterVisualTagGroupsInAvailable`：逐组只保留客户端词表中真实存在的 tag，**丢弃模型幻觉标签**。
6. `ensureMustArrays` 保证四个数组非 null，返回 JSON。

### 4.3 归一化（normalize.go）

- 兼容多种历史字段（`visualTagsAll` / `keywords` / `visualConcepts` / `ocrKeywords` 等）统一并入标准结构。
- `repairSearchKeywordGroups`：确保原始查询词被覆盖，未覆盖则补回；`filterMisreadChineseKeywords` 过滤大模型对中文形近字的误读（如 卡车≠卡钳）。
- `stripEmptyFilters` 清除空 filters。

### 4.4 提示词（prompt.go）

单一 `PromptTemplate`（英文，已验证比中文版稳定），两步式指令：

- **STEP 1 — FILTERS**：先抽取所有时间 / 大小 / 媒体类型 / 截图 / 地点等元数据，这些词不得再出现在 must 中。相对时间（今年/去年/上个月/上周/最近三天）按 `Today` 换算成 yyyy-MM-dd。
- **STEP 2 — CONTENT**：对剩余内容词择一主路径：
  - A) `searchKeywordGroups`：物体、设备、品牌、账号、单据、标签文字等（默认路径，子串匹配 searchDescription，中英同义词并存如 `["海南","hainan"]`）；
  - B) `visualTagGroups`：纯场景/外观（海边、红衣服、猫狗），只能填 Device tags 中真实存在的字符串；
  - C) `sensitiveTypes`：证件/票据。
- 硬约束：保留用户原词，不擅自替换颜色/名词（红≠灰、猫≠狗）；能被 filters+visualTagGroups+sensitiveTypes 完全覆盖的查询，`searchKeywordGroups` 必须为空。

## 五、安全

- 客户端与服务端共享 `secret`，请求以 `MD5(query|deviceId + secret)` 签名，校验不通过返回 401。
- DeepSeek API Key 通过环境变量 `DEEPSEEK_API_KEY` 注入，`SERVER_SECRET` 同理，不落代码。

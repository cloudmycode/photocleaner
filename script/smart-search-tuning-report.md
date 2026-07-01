# 智能搜索 Prompt 调优技术报告

> 日期：2026-07-01
> 范围：`server/src/go/prompt.go`（LLM 提示词）、`server/src/test/test_api.py`（端到端测试集）
> 相关代码：`PhotoCleaner/SmartSearch.swift`、`PhotoCleaner/SearchEngine.swift`、`server/src/go/{handler,models,normalize}.go`

---

## 1. 背景与目标

PhotoCleaner 的智能搜索链路：用户中文 query → 后端 Go 服务调 DeepSeek → 返回结构化 `SearchPlan`（`filters` + `must`）→ iOS 客户端按 plan 在本地相册索引上做匹配。

本轮目标：在**不改动数据结构与匹配算法**的前提下，仅通过提示词优化，提升 SearchPlan 的抽取准确率与稳定性，并把测试数据集从 5 条扩充到覆盖全场景。

---

## 2. 系统结构（与调优相关的关键事实）

### 2.1 SearchPlan 结构

```jsonc
{
  "summary": "给用户看的简短描述",
  "filters": { "dateRange", "mediaTypes", "assetTypes",
               "minSizeMB", "maxSizeMB", "hasLocation", "locationBounds", ... },
  "must": {
    "searchKeywordGroups": [],   // 内容关键词（组内 OR、组间 AND）
    "visualTagGroups": [],       // 视觉标签（值必须来自 availableTags）
    "sensitiveTypes": [],        // id_card|bank_card|passport|document
    "ocrContainsAll": []         // OCR 文本包含
  },
  "count": 1000,
  "confidence": 0.8
}
```

### 2.2 客户端匹配数据（决定 prompt 语言策略的核心）

`searchKeywordGroups` 在客户端做 substring 匹配的 haystack 是（`SmartSearch.swift:89` `searchableDescriptionText`）：

```
caption + localizedLabels + ocrSummary + placemark + albumNames
```

其中 **placemark（地名）、albumNames（相册名）、ocrSummary（OCR 文字）在中文环境下基本是中文**。匹配逻辑在 `SearchEngine.swift matchKeywordGroups`：`lowercased` 后组内 OR、组间 AND。

> **结论性事实**：haystack 是中英混合、以中文为主。因此关键词组必须「中英并存」（如 `["海南","hainan"]`）才能两条路径都命中。

---

## 3. Prompt 调优过程（5 轮迭代）

### 3.1 起点问题（改「关键词优先」后暴露，2/5 通过）

1. 相对时间「去年/上周」没进 `filters.dateRange`，整句塞进 `searchKeywordGroups`；
2. 文件大小/媒体类型「大于5M的视频」没进 `filters`；
3. 「穿红衣服的人」`visualTagGroups` 过扩混入相反颜色（red + gray + white），summary 幻觉成「穿泳衣的人」。

### 3.2 关键发现

- **DeepSeek 对正例最敏感，对成堆 `NEVER` 反而困惑**。加入口语拆解正例后，「去海南」稳定拆成 `["海南","hainan"]`。

### 3.3 结构化重写 + 精简

原 prompt 96 行，同一条「别塞整句」红线重复 5 处。重写为约 72 行，结构：

- `STEP 1 — FILTERS`：先把时间/大小/媒体类型/截图/位置词抽进 `filters`，并给相对时间→`yyyy-MM-dd` 换算示例。
- `STEP 2 — CONTENT`：A) searchKeywordGroups（内容词，中英并存）/ B) visualTagGroups（严格同义、禁混相反概念）/ C) sensitiveTypes。
- `KEY` 段：被 filters+visual+sensitive 完全覆盖的 query，`searchKeywordGroups` 必须为 `[]`。
- `WORDING` 段：保留用户原词，`红≠灰 猫≠狗`，summary/tags 不加料。

精简约 40%。

### 3.4 踩坑与修复

精简时误删「保留原词、不改颜色」约束 → 「穿红衣服的人」退化成 summary「穿灰色衣服的人」+ `gray_clothing` 幻觉。补回一条精炼 `WORDING` 红线即根治。

### 3.5 结果

`test_api.py --local` **5/5 通过**：filters（去年/上周/大于5M）、同义词、sensitiveTypes、red_clothing 全对；summary/confidence 稳定。

---

## 4. 对照实验（均已回滚，结论沉淀）

| 版本 | 通过率 | 现象 | 结论 |
|---|---|---|---|
| **完整英文（72 行）** | **5/5** | summary/confidence 稳定 | ✅ 采用 |
| 中文 prompt | 2/5 | filters `{}`、数组全 `[]`、confidence 丢失 | ✗ 模型「偷懒」不抽取 |
| 极简英文（40 行） | 5/5 | summary 偶发 UTF-8 双重编码乱码、confidence 偶丢 | ✗ 稳定性差 |
| query 翻英文（论证） | — | 见下 | ✗ 会搜不到结果 |

### 4.1 为什么不能把 query 翻成英文

- 关键词最终匹配的是**中文为主的** placemark/albumNames/ocrSummary。翻成英文后 `["海南"]→["Hainan"]`，命中不了中文相册名/地名/OCR → **直接搜不到结果**。
- 多一次 LLM 调用：延迟×2、成本×2、叠加翻译误差（口语/相对时间易失真）。
- summary 要给中文用户看，翻英文还得再翻回来。
- DeepSeek 中文 query 已 5/5，不是出错源。

**规律总结**：DeepSeek 需要足够结构化的**英文技术指令**才输出稳定；换中文或砍太狠都会触发「偷懒 / 乱码」。最优是 prompt 英文 + query 保持中文原文 + 关键词组中英并存。

---

## 5. 测试数据集扩充（5 → 24 条）

文件：`server/src/test/test_api.py`

- **词表**：`SAMPLE_TAGS` 17 → 约 60 个 tag（人物/服饰颜色/动物/风景/交通/食物/物品）。因 `visualTagGroups` 的 tag 必须 ∈ `availableTags`，否则 `assert_search_plan` 报错。
- **新增 5 个 check helper**：`check_filter_contains(key,*vals)`、`check_no_visual_tags()`、`check_has_visual_tags()`、`check_visual_group_count(min)`、`check_has_ocr()`。旧 helper 保留。
- **用例 6 组共 24 条**：

| 组 | 数量 | 覆盖 |
|---|---|---|
| 日期 filter | 4 | 去年海南 / 今年 / 上个月 / 2023春节 |
| 媒体·资源类型 | 6 | 大于5M视频 / 截图 / 录屏 / live / 小于1M图片 / 所有视频 |
| 位置 | 1 | hasLocation |
| 敏感类型 | 3 | id_card / bank_card / passport（均带 no_visual_tags） |
| 视觉单对象 | 7 | 猫 / 狗 / 海边 / 美食 / 汽车 / 自拍 / 红衣人 |
| 组合 + OCR | 3 | 海边美食(2组) / 去年海边(日期+视觉) / 发票(OCR) |

> 部分新 check（护照 / 发票 OCR / 海边美食 2 组 / 自拍）可能因模型行为不稳而偶挂，属预期探测项。

**跑法**：
```bash
cd server/src/go && go build -o /tmp/ss-test . && \
  LOG_DIR=/tmp/ss-log PORT=8081 /tmp/ss-test &
cd server/src/test && python3 test_api.py --local --skip-validation -v
```

---

## 6. 残留顽疾与后续方案

### 6.1 整句复制（纯 prompt 根除不掉，3+ 轮验证）

模型即使已用 filters/visualTags 表达，仍习惯性把整句也塞进 `searchKeywordGroups`（4/5，如「找大于5M的视频」整句）。整句做 substring 匹配几乎命中不了，跨组 AND 会误清空结果。

### 6.2 服务端兜底清洗（待办）

在 `handler.go` 调用的 `normalizeSearchPlan(raw, query, config)` 里：当 plan 已有 `visual/sensitive/filters` 非空时，剔除 `searchKeywordGroups` 中 ≈ 整句 query 的项（子串 + rune 重合度 ≥ 0.7）。注意 `models.go` 中 `Filters` 为值类型。

---

## 7. 结论

1. **prompt 保持英文、72 行完整版**——已验证 5/5 且输出稳定；中文版、极简版均已实测劣化并回滚。
2. **query 保持用户中文原文，不翻译**；关键词组「中英并存」的设计正确，勿动。
3. **测试集扩充到 24 条 6 组**，覆盖 filters/敏感/视觉/组合/OCR 全场景。
4. **下一步靠服务端整句清洗**收口最后一环，而非继续在 prompt 上打磨或翻译 query。

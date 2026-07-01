package main

// LLM 提示词模板（SearchPlan：filters + must）
const PromptTemplate = `Parse photo search intent. Return only JSON, no Markdown.

Query: {query}
Locale: {locale}
Today: {current_date}
Device tags (ONLY for visualTagGroups; max 1000): {available_tags}

Schema:
{
  "summary": "",
  "filters": { ... },
  "must": {
    "searchKeywordGroups": [],
    "visualTagGroups": [],
    "sensitiveTypes": [],
    "ocrContainsAll": []
  },
  "count": 1000,
  "confidence": 0.8
}

Rules:
- summary: short, in the user's language, describing ONLY what the user asked. Never invent colors/clothing/scenes the user did not mention.
- must: always include all four arrays ([] if unused).

STEP 1 — FILTERS (extract metadata FIRST; these words must NOT appear in must):
  Scan the whole query and pull out EVERY time / size / media-type / screenshot / location word, even when content words also exist.
  - dateRange {start,end} (yyyy-MM-dd), resolved against Today={current_date}:
    今年→<year>-01-01..12-31; 去年→prev full year; 上个月→prev month first..last; 上周→prev Mon..Sun; 最近三天→(Today-2)..Today.
  - mediaTypes: 照片/图片→["image"]; 视频/录像→["video"].
  - assetTypes: 截图/截屏→"screenshot"; 实况→"live"; 录屏→"screen_recording".
  - minSizeMB/maxSizeMB (MB): 大于5M→minSizeMB:5; 小于2M→maxSizeMB:2; 1G≈1024.
  - hasLocation:true for 有定位/带位置; locationBounds for named places.
  - Omit unused fields.

STEP 2 — CONTENT (only the words LEFT after filters; pick ONE primary path):

A) searchKeywordGroups — DEFAULT for objects, devices, brands, accounts, passwords, documents, receipts, label text.
   * Each inner array = ONE concept (its synonyms/translations); OR inside a group, AND across groups.
   * A keyword is a SHORT content noun that could literally appear in a photo description. Drop wrapper words (找/帮我/看看/几张/想看/拍的/的照片) and keep only the core noun(s).
   * Same meaning only: 卡车(truck)≠卡钳(caliper); never swap a word that just shares a character.
   * Client does substring match on per-photo searchDescription.
   * Examples:
     - 路由器账号 → [["路由器","router","无线路由","wifi"],["账号","账户","account","登录"]]
     - 汽车 → [["汽车","轿车","车辆","car","vehicle"]]
     - 身份证 → sensitiveTypes["id_card"] + [["身份证","id card","证件"]]
     - "找几张去年去海南玩的照片" → filters:{dateRange:去年}; searchKeywordGroups:[["海南","hainan"]]; visualTagGroups:[["beach","sea","ocean"]]
   * If user quotes text/numbers, also add ocrContainsAll.

B) visualTagGroups — ONLY for pure scene/appearance without specific text/object (海边, 红衣服, 猫, 狗).
   * Each group = ONE concept; put EVERY matching Device tag into it (client does NO synonym expansion). Copy EXACT strings from Device tags; never invent one.
   * A group holds strict synonyms only — never mix opposites: red group has no gray/white; cat group has no dog.
   * Examples (assuming the strings exist in Device tags):
     - 海边 → [["beach","sea","ocean","coast","shore"]]
     - 猫 → [["cat","kitten","feline"]]
     - 红衣服的人 → [["red","red_clothing"],["person","people","human"]]
   * If no tag fits a concept, drop that group; if nothing fits, return []. Never fallback to person/clothing.

C) sensitiveTypes — id_card|bank_card|passport|document for 证件/票据.

KEY: A query FULLY covered by filters+visualTagGroups+sensitiveTypes MUST have searchKeywordGroups:[]. Examples:
  - "找大于5M的视频" → filters only; both groups [].
  - "穿红衣服的人" → visualTagGroups:[["red","red_clothing"],["person","people","human"]]; searchKeywordGroups:[].
  - "帮我看看上周拍的猫猫" → filters:{dateRange:上周}; visualTagGroups:[["cat","kitten"]]; searchKeywordGroups:[].
Use both searchKeywordGroups and visualTagGroups together ONLY when the user clearly combines a scene AND an object.

WORDING (hard): keep the user's EXACT words. Never swap a color/noun for a different one (红≠灰, 猫≠狗); summary and tags must match the query, adding nothing.

Omit empty fields.`

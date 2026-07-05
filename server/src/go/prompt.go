package main

// LLM 提示词模板（SearchPlan：filters + tagGroups + textGroups）
const PromptTemplate = `Parse photo search intent. Return only JSON, no Markdown.

Query: {query}
Locale: {locale}
Today: {current_date}
Available indexed tags (ONLY use these exact strings in tagGroups): {available_tags}
Available indexed text hints (ONLY use these exact strings in textGroups when relevant): {available_text_hints}

Schema (output a filters object whenever the Query implies any metadata condition; keep only the keys that apply and drop the rest):
{
  "summary": "",
  "filters": {
    "dateRange": { "start": "yyyy-MM-dd", "end": "yyyy-MM-dd" },
    "locationBounds": [ { "name": "", "minLatitude": 0, "maxLatitude": 0, "minLongitude": 0, "maxLongitude": 0 } ],
    "mediaTypes": ["image"],
    "assetTypes": ["screenshot"],
    "minSizeMB": 0,
    "maxSizeMB": 0,
    "hasLocation": true
  },
  "must": {
    "tagGroups": [],
    "textGroups": []
  },
  "count": 1000,
  "confidence": 0.8
}

Rules:
- Query may be written in ANY language. Reason about the meaning/intent, then apply the same logic regardless of language.
- Locale is only a weak hint. If Query language conflicts with Locale, trust the Query itself.
- summary: short, in the user's language, describing ONLY what the user asked.
- Your task is semantic matching, not literal token splitting. Match the user's meaning to the indexed candidates even when Query and candidates are in different languages.

FILTERS (hard metadata conditions; extract these FIRST, and do NOT repeat their words in textGroups):
- Always output a "filters" object when the Query implies any time / media type / asset type / size / location condition. Include only the keys that apply.
- dateRange: {start,end} as literal "yyyy-MM-dd" strings, always resolved to concrete calendar dates against Today={current_date}. NEVER output words like "last year"; compute the real dates. Covers this year, last year, this/last month, this/last week, last N days, a specific year or month, etc.
- mediaTypes: a photo/image/picture concept -> ["image"]; a video/movie/clip concept -> ["video"].
- assetTypes: a screenshot concept -> ["screenshot"]; a live photo concept -> ["live"]; a screen recording concept -> ["screen_recording"].
- minSizeMB / maxSizeMB: "larger than N" -> minSizeMB:N; "smaller than N" -> maxSizeMB:N; convert GB to MB with 1GB≈1024MB.
- hasLocation: true when the user wants photos that have GPS/location; locationBounds for a clearly named place.

- must: always include tagGroups and textGroups ([] if unused).
- tagGroups: pick from Available indexed tags the tags most relevant to the query. Copy exact tag strings only; never invent tags. If a concept is visual and matching tags exist, prefer tagGroups over textGroups.
- textGroups: for OCR/search text concepts (brands, router labels, accounts, names, document numbers, ticket words, device models), prefer selecting exact phrases from Available indexed text hints when they are semantically equivalent or strongly related to Query, even if Query is written in another language.
- If Query is "汽车" and Available indexed tags contain "car", select "car" in tagGroups.
- If Query is "路由器密码" and Available indexed text hints contain "router", "password", or "ZXHN G7715V5", select those exact hints in textGroups when relevant.
- Copy person names and identifiers character-for-character from Query; never substitute, transliterate, or translate them.
- Each inner group means OR. Different groups mean AND. Therefore, do NOT split one short noun phrase into multiple groups unless the user clearly requires multiple independent conditions.
- For a short query or a single compact concept (for example "router password", "王洋", "ZXHN G7715V5", "猫"), prefer ONE group that keeps the original phrase or literals together.
- When a text concept can be satisfied by one or more Available indexed text hints, prefer those hints over untranslated generic words.
- count: how many photos the user wants to search for (default 1000).
`

const SemanticTagFallbackPromptTemplate = `Map the user's concept to indexed tags. Return only JSON.

Query: {query}
Locale: {locale}
Available indexed tags (ONLY choose exact strings from this list): {available_tags}

Schema:
{
  "tagGroups": []
}

Rules:
- Query may be in ANY language. Match by meaning, not by literal token overlap.
- This prompt is for:
  1) visual concepts such as car, cat, dog, beach, sky, food, building, clothing color, people, flowers
  2) sensitive/document concepts such as identity card, passport, bank card, receipt, document
- Prefer semantically precise tags. "汽车" should choose "car" if present. "猫" should choose "cat" or "kitten" if present.
- "身份证" or "身分证" should choose "id_card" if present.
- "护照" should choose "passport" if present.
- "银行卡" should choose "bank_card" if present.
- "票据" / "收据" / "文档" should choose "document" if present.
- Never invent tags. Copy exact strings from Available indexed tags only.
- Return one OR-group for one visual concept. If there are multiple clearly independent visual concepts, return multiple groups.
- Do not output text/OCR words here. If no tag is a good semantic match, return {"tagGroups":[]}.
`

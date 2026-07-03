package main

// LLM 提示词模板（SearchPlan：filters + tagGroups + textGroups）
const PromptTemplate = `Parse photo search intent. Return only JSON, no Markdown.

Query: {query}
Locale: {locale}
Today: {current_date}
Available indexed tags (ONLY use these exact strings in tagGroups): {available_tags}

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

FILTERS (hard metadata conditions; extract these FIRST, and do NOT repeat their words in textGroups):
- Always output a "filters" object when the Query implies any time / media type / asset type / size / location condition. Include only the keys that apply.
- dateRange: {start,end} as literal "yyyy-MM-dd" strings, always resolved to concrete calendar dates against Today={current_date}. NEVER output words like "last year"; compute the real dates. Covers this year, last year, this/last month, this/last week, last N days, a specific year or month, etc.
- mediaTypes: a photo/image/picture concept -> ["image"]; a video/movie/clip concept -> ["video"].
- assetTypes: a screenshot concept -> ["screenshot"]; a live photo concept -> ["live"]; a screen recording concept -> ["screen_recording"].
- minSizeMB / maxSizeMB: "larger than N" -> minSizeMB:N; "smaller than N" -> maxSizeMB:N; convert GB to MB with 1GB≈1024MB.
- hasLocation: true when the user wants photos that have GPS/location; locationBounds for a clearly named place.

- must: always include tagGroups and textGroups ([] if unused).
- tagGroups: pick from Available indexed tags the tags most relevant to the query. For example, to find a "red car", select tags such as "red" and "car" that exist in the list. Copy exact tag strings only; never invent tags. If a concept is visual and matching tags exist, prefer tagGroups over textGroups.
- textGroups: literal text keywords that may appear in the photo's OCR/search text (names, brands, accounts, numbers, codes, etc.). Copy person names and identifiers character-for-character from Query; never substitute, transliterate, or translate them.
- Each inner group means OR. Different groups mean AND. Therefore, do NOT split one short noun phrase into multiple groups unless the user clearly requires multiple independent conditions.
- For a short query or a single compact concept (for example "router password", "王洋", "ZXHN G7715V5", "猫"), prefer ONE group that keeps the original phrase or literals together.
- count: how many photos the user wants to search for (default 1000).
`

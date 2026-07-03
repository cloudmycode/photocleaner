package main

import (
	"strings"
	"unicode"
)

func normalizeSearchPlan(raw map[string]interface{}, query string, availableTags []string, cfg *Config) SearchPlan {
	normalizedQuery := normalizeQuery(query)
	plan := SearchPlan{
		Summary: normalizedQuery,
		Must: MustMatch{
			TagGroups:  [][]string{},
			TextGroups: [][]string{},
		},
		Count: cfg.DefaultCount,
	}

	if s, ok := raw["summary"].(string); ok && strings.TrimSpace(s) != "" {
		plan.Summary = strings.TrimSpace(s)
	}

	plan.Filters = extractFilters(raw)
	plan.Must = extractMust(raw)
	plan.Count = normalizeCount(raw, cfg)
	plan.Confidence = normalizeConfidence(raw)

	stripEmptyFilters(&plan.Filters)
	repairLiteralTextOnlyQuery(&plan, normalizedQuery)
	mergeRelatedTextGroupsForSingleConcept(&plan, normalizedQuery)
	mergeCompactQueryTextGroups(&plan, normalizedQuery)
	ensureLiteralQueryCoverage(&plan, normalizedQuery)
	promoteExactAvailableTags(&plan, normalizedQuery, availableTags)
	dropSubsetTagGroups(&plan)
	plan.Must = ensureMustArrays(plan.Must)
	return plan
}

func normalizeQuery(query string) string {
	fields := strings.FieldsFunc(query, func(r rune) bool {
		return unicode.IsSpace(r) || r == '\u3000'
	})
	return strings.TrimSpace(strings.Join(fields, " "))
}

func ensureMustArrays(must MustMatch) MustMatch {
	if must.TagGroups == nil {
		must.TagGroups = [][]string{}
	}
	if must.TextGroups == nil {
		must.TextGroups = [][]string{}
	}
	return must
}

func emptySearchPlan(query string, cfg *Config) SearchPlan {
	return SearchPlan{
		Summary: query,
		Must: MustMatch{
			TagGroups:  [][]string{},
			TextGroups: [][]string{},
		},
		Count:      cfg.DefaultCount,
		Confidence: 0,
	}
}

func repairLiteralTextOnlyQuery(plan *SearchPlan, query string) {
	if !isLikelyLiteralTextOnlyQuery(query) {
		return
	}
	// 短人名/纯文本查询若带有元数据过滤条件，说明模型识别到了更复杂的意图，交给模型处理。
	if hasAnyFilter(plan.Filters) {
		return
	}
	trimmed := strings.TrimSpace(query)
	// 模型已正确返回 [[原始查询]] 时无需改动。
	if len(plan.Must.TagGroups) == 0 &&
		len(plan.Must.TextGroups) == 1 &&
		len(plan.Must.TextGroups[0]) == 1 &&
		strings.EqualFold(strings.TrimSpace(plan.Must.TextGroups[0][0]), trimmed) {
		return
	}
	// 兜底：短人名/纯文本查询必须精确匹配原始查询本身。
	// 模型有时会把人名塞进 tagGroups，甚至把名字替换成更常见的占位名
	// （例如把 王洋 换成 张伟），这里强制还原为字面文本。
	plan.Must.TagGroups = [][]string{}
	plan.Must.TextGroups = [][]string{{trimmed}}
}

func mergeRelatedTextGroupsForSingleConcept(plan *SearchPlan, query string) {
	if len(plan.Must.TextGroups) <= 1 || len(plan.Must.TagGroups) > 0 || hasAnyFilter(plan.Filters) {
		return
	}
	trimmed := strings.TrimSpace(query)
	if trimmed == "" || strings.ContainsAny(trimmed, " \t\r\n") {
		return
	}
	hasExactGroup := false
	merged := []string{}
	for _, group := range plan.Must.TextGroups {
		for _, word := range group {
			if strings.EqualFold(strings.TrimSpace(word), trimmed) {
				hasExactGroup = true
			}
		}
		merged = append(merged, group...)
	}
	if hasExactGroup {
		plan.Must.TextGroups = [][]string{uniqueStrings(merged)}
	}
}

func mergeCompactQueryTextGroups(plan *SearchPlan, query string) {
	if len(plan.Must.TextGroups) <= 1 || len(plan.Must.TagGroups) > 0 || hasAnyFilter(plan.Filters) {
		return
	}
	if !shouldPreferSingleTextGroup(query) {
		return
	}

	merged := []string{query}
	for _, group := range plan.Must.TextGroups {
		merged = append(merged, group...)
	}
	plan.Must.TextGroups = [][]string{uniqueStrings(merged)}
}

func shouldPreferSingleTextGroup(query string) bool {
	trimmed := strings.TrimSpace(query)
	if trimmed == "" {
		return false
	}
	if strings.ContainsAny(trimmed, ",;|/\\") {
		return false
	}
	lower := strings.ToLower(trimmed)
	for _, marker := range []string{
		" and ", " or ", " with ", " without ", "&",
		" 和 ", " 或 ", " 跟 ", "以及", "并且",
		" と ", " または ", " 및 ", " 혹은 ",
	} {
		if strings.Contains(lower, marker) {
			return false
		}
	}

	words := strings.Fields(trimmed)
	if len(words) > 0 {
		return len(words) <= 4
	}

	runes := []rune(trimmed)
	return len(runes) <= 24
}

func ensureLiteralQueryCoverage(plan *SearchPlan, query string) {
	if query == "" || hasAnyFilter(plan.Filters) {
		return
	}
	literals := extractImportantLiterals(query)
	if len(literals) == 0 {
		return
	}

	for _, literal := range literals {
		if groupsContainLiteral(plan.Must.TextGroups, literal) || groupsContainLiteral(plan.Must.TagGroups, literal) {
			continue
		}
		if shouldAppendLiteralAsSingleGroup(literal) {
			plan.Must.TextGroups = append(plan.Must.TextGroups, []string{literal})
		}
	}
}

func groupsContainLiteral(groups [][]string, literal string) bool {
	want := normalizeComparableToken(literal)
	if want == "" {
		return false
	}
	for _, group := range groups {
		for _, item := range group {
			if normalizeComparableToken(item) == want {
				return true
			}
		}
	}
	return false
}

func shouldAppendLiteralAsSingleGroup(literal string) bool {
	if literal == "" {
		return false
	}
	if hasLettersAndDigits(literal) {
		return true
	}
	if isLikelyLiteralTextOnlyQuery(literal) {
		return true
	}
	return strings.ContainsAny(literal, "@._-#/") && len([]rune(literal)) >= 3
}

func promoteExactAvailableTags(plan *SearchPlan, query string, availableTags []string) {
	if len(availableTags) == 0 {
		return
	}
	allowed := make(map[string]string, len(availableTags))
	for _, tag := range availableTags {
		trimmed := strings.TrimSpace(tag)
		key := normalizeComparableToken(trimmed)
		if trimmed != "" && key != "" {
			allowed[key] = strings.ToLower(trimmed)
		}
		for _, variant := range tagVariants(trimmed) {
			if variant != "" {
				allowed[variant] = strings.ToLower(trimmed)
			}
		}
	}

	var promoted [][]string
	addPromoted := func(token string) {
		group := []string{}
		seen := map[string]struct{}{}
		for _, variant := range tagVariants(token) {
			if tag, ok := allowed[variant]; ok {
				if _, exists := seen[tag]; !exists {
					group = append(group, tag)
					seen[tag] = struct{}{}
				}
			}
		}
		if len(group) == 0 {
			return
		}
		if !groupExists(plan.Must.TagGroups, group) && !groupExists(promoted, group) {
			promoted = append(promoted, group)
		}
	}

	for _, literal := range extractImportantLiterals(query) {
		addPromoted(literal)
	}
	for _, group := range plan.Must.TextGroups {
		for _, item := range group {
			addPromoted(item)
		}
	}

	if len(promoted) > 0 {
		plan.Must.TagGroups = append(plan.Must.TagGroups, promoted...)
	}
}

func groupExists(groups [][]string, candidate []string) bool {
	want := strings.Join(uniqueStrings(candidate), "\x00")
	for _, group := range groups {
		if strings.Join(uniqueStrings(group), "\x00") == want {
			return true
		}
	}
	return false
}

func dropSubsetTagGroups(plan *SearchPlan) {
	if len(plan.Must.TagGroups) <= 1 {
		return
	}
	keep := make([][]string, 0, len(plan.Must.TagGroups))
	for i, group := range plan.Must.TagGroups {
		if len(group) == 0 {
			continue
		}
		isSubset := false
		for j, other := range plan.Must.TagGroups {
			if i == j || len(other) < len(group) {
				continue
			}
			if isSubsetGroup(group, other) {
				isSubset = true
				break
			}
		}
		if !isSubset {
			keep = append(keep, group)
		}
	}
	plan.Must.TagGroups = keep
}

func isSubsetGroup(group, other []string) bool {
	set := make(map[string]struct{}, len(other))
	for _, item := range other {
		set[normalizeComparableToken(item)] = struct{}{}
	}
	for _, item := range group {
		if _, ok := set[normalizeComparableToken(item)]; !ok {
			return false
		}
	}
	return true
}

func hasAnyFilter(filters Filters) bool {
	return filters.DateRange != nil ||
		len(filters.LocationBounds) > 0 ||
		len(filters.MediaTypes) > 0 ||
		len(filters.AssetTypes) > 0 ||
		filters.MinSizeMB != nil ||
		filters.MaxSizeMB != nil ||
		filters.MinPixelWidth != nil ||
		filters.MinPixelHeight != nil ||
		filters.HasLocation != nil
}

func isLikelyLiteralTextOnlyQuery(query string) bool {
	trimmed := strings.TrimSpace(query)
	runes := []rune(trimmed)
	if len(runes) < 2 || len(runes) > 4 {
		return false
	}
	for _, r := range runes {
		if !unicode.Is(unicode.Han, r) {
			return false
		}
	}
	if strings.ContainsAny(trimmed, "车猫狗路山海湖河花树草天云衣鞋包饭菜水房楼店票卡证书屏图照人男孩女孩") {
		return false
	}
	return strings.ContainsRune(commonChineseSurnameChars, runes[0])
}

func extractImportantLiterals(query string) []string {
	trimmed := strings.TrimSpace(query)
	if trimmed == "" {
		return nil
	}
	var literals []string
	if shouldKeepWholeQueryLiteral(trimmed) {
		literals = append(literals, trimmed)
	}

	for _, token := range strings.FieldsFunc(trimmed, func(r rune) bool {
		return unicode.IsSpace(r) || unicode.IsPunct(r)
	}) {
		token = strings.TrimSpace(token)
		if token == "" {
			continue
		}
		if hasLettersAndDigits(token) || isLikelyLiteralTextOnlyQuery(token) {
			literals = append(literals, token)
		}
	}

	return uniqueStrings(literals)
}

func shouldKeepWholeQueryLiteral(query string) bool {
	if query == "" {
		return false
	}
	if hasLettersAndDigits(query) {
		return true
	}
	if isLikelyLiteralTextOnlyQuery(query) {
		return true
	}
	return len(strings.Fields(query)) > 1 && len([]rune(query)) <= 24
}

func hasLettersAndDigits(text string) bool {
	var hasLetter bool
	var hasDigit bool
	for _, r := range text {
		if unicode.IsLetter(r) {
			hasLetter = true
		}
		if unicode.IsDigit(r) {
			hasDigit = true
		}
	}
	return hasLetter && hasDigit
}

func normalizeComparableToken(text string) string {
	trimmed := strings.ToLower(strings.TrimSpace(text))
	replacer := strings.NewReplacer(
		"-", " ",
		"_", " ",
		"/", " ",
		"\\", " ",
	)
	trimmed = replacer.Replace(trimmed)
	return strings.Join(strings.Fields(trimmed), " ")
}

func tagVariants(text string) []string {
	trimmed := strings.TrimSpace(text)
	if trimmed == "" {
		return nil
	}
	base := normalizeComparableToken(trimmed)
	out := []string{base}
	if base != "" {
		out = append(out, strings.ReplaceAll(base, " ", "_"))
	}
	if !strings.Contains(base, " ") && strings.HasSuffix(base, "s") && len(base) > 3 {
		out = append(out, strings.TrimSuffix(base, "s"))
	}
	return uniqueStrings(out)
}

const commonChineseSurnameChars = "赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许何吕施张孔曹严华金魏陶姜戚谢邹喻柏水窦章云苏潘葛奚范彭郎鲁韦昌马苗凤花方俞任袁柳鲍史唐费廉岑薛雷贺倪汤滕殷罗毕郝邬安常乐于时傅皮卞齐康伍余元卜顾孟平黄和穆萧尹姚邵湛汪祁毛禹狄米贝明臧计伏成戴谈宋庞熊纪舒屈项祝董梁杜阮蓝闵席季麻强贾路娄危江童颜郭梅盛林刁钟徐邱骆高夏蔡田胡凌霍虞万支柯昝管卢莫经裘缪干解应宗丁宣邓郁单杭洪包诸左石崔吉龚程邢裴陆荣翁荀羊於惠甄曲家封芮羿储靳汲邴糜松井段富巫乌焦巴弓牧隗山谷车侯宓蓬全郗班仰秋仲伊宫宁仇栾暴甘钭厉戎祖武符刘景詹龙叶幸司韶郜黎蓟薄印宿白怀蒲台从鄂索咸籍赖卓蔺屠蒙池乔阴胥能苍双闻莘党翟谭贡劳逄姬申扶堵冉宰郦雍郤璩桑桂濮牛寿通边扈燕冀郏浦尚农温别庄晏柴瞿阎充慕连茹习宦艾鱼容向古易慎戈廖庾终暨居衡步都耿满弘匡国文寇广禄阙东欧殳沃利蔚越夔隆师巩厍聂晁勾敖融冷辛阚那简饶空曾毋沙乜养鞠须丰巢关蒯相查后荆红游竺权逯盖益桓公"

func extractFilters(raw map[string]interface{}) Filters {
	filters := Filters{}

	if nested, ok := raw["filters"].(map[string]interface{}); ok {
		mergeFiltersFromMap(&filters, nested)
	}
	mergeFiltersFromMap(&filters, raw)

	return filters
}

func mergeFiltersFromMap(filters *Filters, m map[string]interface{}) {
	if v := objectField(m, "dateRange"); v != nil {
		filters.DateRange = parseDateRange(v)
	}
	if bounds := sliceField(m, "locationBounds"); len(bounds) > 0 {
		filters.LocationBounds = parseLocationBounds(bounds)
	}
	if types := stringSliceField(m, "mediaTypes"); len(types) > 0 {
		filters.MediaTypes = types
	}
	if types := stringSliceField(m, "assetTypes"); len(types) > 0 {
		filters.AssetTypes = types
	}
	if v, ok := floatField(m, "minSizeMB"); ok {
		filters.MinSizeMB = &v
	}
	if v, ok := floatField(m, "maxSizeMB"); ok {
		filters.MaxSizeMB = &v
	}
	if v, ok := intField(m, "minPixelWidth"); ok {
		filters.MinPixelWidth = &v
	}
	if v, ok := intField(m, "minPixelHeight"); ok {
		filters.MinPixelHeight = &v
	}
	if v, ok := boolField(m, "hasLocation"); ok {
		filters.HasLocation = &v
	}
}

func extractMust(raw map[string]interface{}) MustMatch {
	must := MustMatch{
		TagGroups:  [][]string{},
		TextGroups: [][]string{},
	}

	if nested, ok := raw["must"].(map[string]interface{}); ok {
		must.TagGroups = append(must.TagGroups, stringMatrixField(nested, "tagGroups")...)
		must.TextGroups = append(must.TextGroups, stringMatrixField(nested, "textGroups")...)
	}

	must.TagGroups = append(must.TagGroups, stringMatrixField(raw, "tagGroups")...)
	must.TextGroups = append(must.TextGroups, stringMatrixField(raw, "textGroups")...)

	must.TagGroups = normalizeKeywordGroups(must.TagGroups)
	must.TextGroups = normalizeKeywordGroups(must.TextGroups)

	return must
}

func stringMatrixField(m map[string]interface{}, key string) [][]string {
	raw, ok := m[key].([]interface{})
	if !ok {
		return nil
	}
	var groups [][]string
	for _, item := range raw {
		group, ok := item.([]interface{})
		if !ok {
			continue
		}
		words := stringSliceFromInterface(group)
		if len(words) > 0 {
			groups = append(groups, words)
		}
	}
	return groups
}

func stringSliceFromInterface(items []interface{}) []string {
	var out []string
	for _, item := range items {
		if s, ok := item.(string); ok {
			s = strings.TrimSpace(s)
			if s != "" {
				out = append(out, s)
			}
		}
	}
	return out
}

func normalizeKeywordGroups(groups [][]string) [][]string {
	var out [][]string
	for _, group := range groups {
		words := uniqueStrings(group)
		if len(words) > 0 {
			out = append(out, words)
		}
	}
	return out
}

// filterTagGroupsInAvailable keeps model-selected tags constrained to the client index.
func filterTagGroupsInAvailable(groups [][]string, available []string) [][]string {
	if len(groups) == 0 {
		return [][]string{}
	}
	allowed := make(map[string]struct{}, len(available))
	for _, tag := range available {
		tag = strings.TrimSpace(strings.ToLower(tag))
		if tag != "" {
			allowed[tag] = struct{}{}
		}
	}
	out := make([][]string, 0, len(groups))
	for _, group := range groups {
		kept := make([]string, 0, len(group))
		for _, tag := range group {
			tag = strings.TrimSpace(strings.ToLower(tag))
			if _, ok := allowed[tag]; ok {
				kept = append(kept, tag)
			}
		}
		if len(kept) > 0 {
			out = append(out, uniqueStrings(kept))
		}
	}
	return out
}

func normalizeCount(raw map[string]interface{}, cfg *Config) int {
	count := cfg.DefaultCount
	if v, ok := floatField(raw, "count"); ok {
		count = int(v)
	}
	if count <= 0 {
		count = cfg.DefaultCount
	}
	if count > cfg.MaxCount {
		count = cfg.MaxCount
	}
	return count
}

func normalizeConfidence(raw map[string]interface{}) float64 {
	v, ok := floatField(raw, "confidence")
	if !ok {
		return 0
	}
	if v < 0 {
		return 0
	}
	if v > 1 {
		return 1
	}
	return v
}

func stripEmptyFilters(filters *Filters) {
	if filters.DateRange != nil && filters.DateRange.Start == "" && filters.DateRange.End == "" {
		filters.DateRange = nil
	}
	if len(filters.LocationBounds) == 0 {
		filters.LocationBounds = nil
	}
	if len(filters.MediaTypes) == 0 {
		filters.MediaTypes = nil
	}
	if len(filters.AssetTypes) == 0 {
		filters.AssetTypes = nil
	}
}

func parseDateRange(v interface{}) *DateRange {
	m, ok := v.(map[string]interface{})
	if !ok {
		return nil
	}
	dr := &DateRange{}
	if s, ok := m["start"].(string); ok {
		dr.Start = strings.TrimSpace(s)
	}
	if s, ok := m["end"].(string); ok {
		dr.End = strings.TrimSpace(s)
	}
	if dr.Start == "" && dr.End == "" {
		return nil
	}
	return dr
}

func parseLocationBounds(items []interface{}) []LocationBound {
	var bounds []LocationBound
	for _, item := range items {
		m, ok := item.(map[string]interface{})
		if !ok {
			continue
		}
		b := LocationBound{}
		if s, ok := m["name"].(string); ok {
			b.Name = s
		}
		if v, ok := floatField(m, "minLatitude"); ok {
			b.MinLatitude = v
		}
		if v, ok := floatField(m, "maxLatitude"); ok {
			b.MaxLatitude = v
		}
		if v, ok := floatField(m, "minLongitude"); ok {
			b.MinLongitude = v
		}
		if v, ok := floatField(m, "maxLongitude"); ok {
			b.MaxLongitude = v
		}
		bounds = append(bounds, b)
	}
	return bounds
}

func objectField(m map[string]interface{}, key string) map[string]interface{} {
	v, ok := m[key].(map[string]interface{})
	if !ok {
		return nil
	}
	return v
}

func sliceField(m map[string]interface{}, key string) []interface{} {
	v, ok := m[key].([]interface{})
	if !ok {
		return nil
	}
	return v
}

func stringSliceField(m map[string]interface{}, key string) []string {
	raw, ok := m[key].([]interface{})
	if !ok {
		return nil
	}
	var out []string
	for _, item := range raw {
		if s, ok := item.(string); ok {
			s = strings.TrimSpace(s)
			if s != "" {
				out = append(out, s)
			}
		}
	}
	return out
}

func floatField(m map[string]interface{}, key string) (float64, bool) {
	v, ok := m[key]
	if !ok {
		return 0, false
	}
	switch n := v.(type) {
	case float64:
		return n, true
	case int:
		return float64(n), true
	default:
		return 0, false
	}
}

func intField(m map[string]interface{}, key string) (int, bool) {
	v, ok := floatField(m, key)
	if !ok {
		return 0, false
	}
	return int(v), true
}

func boolField(m map[string]interface{}, key string) (bool, bool) {
	v, ok := m[key].(bool)
	return v, ok
}

func uniqueStrings(items []string) []string {
	if len(items) == 0 {
		return []string{}
	}
	seen := make(map[string]struct{}, len(items))
	out := make([]string, 0, len(items))
	for _, item := range items {
		if item == "" {
			continue
		}
		if _, ok := seen[item]; ok {
			continue
		}
		seen[item] = struct{}{}
		out = append(out, item)
	}
	return out
}

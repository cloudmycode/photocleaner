package main

import "testing"

func TestNormalizeSearchPlanPromotesExactAvailableTagFromTextGroup(t *testing.T) {
	raw := map[string]interface{}{
		"must": map[string]interface{}{
			"textGroups": []interface{}{
				[]interface{}{"cat"},
			},
		},
	}

	plan := normalizeSearchPlan(raw, "cat", []string{"cat", "dog"}, &Config{DefaultCount: 100, MaxCount: 1000})

	if len(plan.Must.TagGroups) != 1 || len(plan.Must.TagGroups[0]) != 1 || plan.Must.TagGroups[0][0] != "cat" {
		t.Fatalf("expected tagGroups [[cat]], got %#v", plan.Must.TagGroups)
	}
}

func TestNormalizeSearchPlanMergesCompactQueryTextGroups(t *testing.T) {
	raw := map[string]interface{}{
		"must": map[string]interface{}{
			"textGroups": []interface{}{
				[]interface{}{"router"},
				[]interface{}{"password"},
			},
		},
	}

	plan := normalizeSearchPlan(raw, "router password", nil, &Config{DefaultCount: 100, MaxCount: 1000})

	if len(plan.Must.TextGroups) != 1 {
		t.Fatalf("expected one merged text group, got %#v", plan.Must.TextGroups)
	}
	if !containsString(plan.Must.TextGroups[0], "router password") || !containsString(plan.Must.TextGroups[0], "router") || !containsString(plan.Must.TextGroups[0], "password") {
		t.Fatalf("expected merged group to preserve original phrase and tokens, got %#v", plan.Must.TextGroups)
	}
}

func TestNormalizeSearchPlanKeepsCompactLiteralCoverage(t *testing.T) {
	raw := map[string]interface{}{
		"must": map[string]interface{}{
			"textGroups": []interface{}{
				[]interface{}{"router"},
			},
		},
	}

	plan := normalizeSearchPlan(raw, "ZXHN G7715V5", nil, &Config{DefaultCount: 100, MaxCount: 1000})

	if len(plan.Must.TextGroups) == 0 || !groupsContainLiteral(plan.Must.TextGroups, "ZXHN G7715V5") {
		t.Fatalf("expected literal model string to be preserved, got %#v", plan.Must.TextGroups)
	}
}

func TestRepairLiteralTextOnlyQueryConvertsChineseNameToTextGroup(t *testing.T) {
	plan := SearchPlan{
		Must: MustMatch{
			TagGroups:  [][]string{{"car", "automobile"}},
			TextGroups: [][]string{},
		},
	}

	repairLiteralTextOnlyQuery(&plan, "王洋")

	if len(plan.Must.TagGroups) != 0 {
		t.Fatalf("expected tagGroups to be cleared, got %#v", plan.Must.TagGroups)
	}
	if len(plan.Must.TextGroups) != 1 || len(plan.Must.TextGroups[0]) != 1 || plan.Must.TextGroups[0][0] != "王洋" {
		t.Fatalf("expected textGroups [[王洋]], got %#v", plan.Must.TextGroups)
	}
}

func TestRepairLiteralTextOnlyQueryFixesSubstitutedName(t *testing.T) {
	// 模型把 王洋 幻觉替换成了 张伟，兜底逻辑必须还原为原始查询。
	plan := SearchPlan{
		Must: MustMatch{
			TagGroups:  [][]string{},
			TextGroups: [][]string{{"张伟"}},
		},
	}

	repairLiteralTextOnlyQuery(&plan, "王洋")

	if len(plan.Must.TextGroups) != 1 || len(plan.Must.TextGroups[0]) != 1 || plan.Must.TextGroups[0][0] != "王洋" {
		t.Fatalf("expected textGroups [[王洋]], got %#v", plan.Must.TextGroups)
	}
}

func TestRepairLiteralTextOnlyQueryKeepsCorrectName(t *testing.T) {
	plan := SearchPlan{
		Must: MustMatch{
			TagGroups:  [][]string{},
			TextGroups: [][]string{{"王洋"}},
		},
	}

	repairLiteralTextOnlyQuery(&plan, "王洋")

	if len(plan.Must.TextGroups) != 1 || len(plan.Must.TextGroups[0]) != 1 || plan.Must.TextGroups[0][0] != "王洋" {
		t.Fatalf("expected textGroups [[王洋]], got %#v", plan.Must.TextGroups)
	}
}

func TestRepairLiteralTextOnlyQueryDoesNotConvertVisualQuery(t *testing.T) {
	plan := SearchPlan{
		Must: MustMatch{
			TagGroups:  [][]string{{"car", "automobile"}},
			TextGroups: [][]string{},
		},
	}

	repairLiteralTextOnlyQuery(&plan, "汽车")

	if len(plan.Must.TextGroups) != 0 {
		t.Fatalf("expected visual query to keep textGroups empty, got %#v", plan.Must.TextGroups)
	}
	if len(plan.Must.TagGroups) != 1 {
		t.Fatalf("expected visual query to keep tagGroups, got %#v", plan.Must.TagGroups)
	}
}

func TestMergeRelatedTextGroupsForSingleConceptCombinesGroups(t *testing.T) {
	plan := SearchPlan{
		Must: MustMatch{
			TagGroups: [][]string{},
			TextGroups: [][]string{
				{"路由器", "router", "无线路由", "wifi"},
				{"密码", "password", "账号"},
			},
		},
	}

	mergeRelatedTextGroupsForSingleConcept(&plan, "路由器")

	if len(plan.Must.TextGroups) != 1 {
		t.Fatalf("expected one text group, got %#v", plan.Must.TextGroups)
	}
	if !containsString(plan.Must.TextGroups[0], "路由器") || !containsString(plan.Must.TextGroups[0], "密码") {
		t.Fatalf("expected related groups to be merged, got %#v", plan.Must.TextGroups)
	}
}

func TestMergeRelatedTextGroupsForSingleConceptKeepsMultiConceptQuery(t *testing.T) {
	plan := SearchPlan{
		Must: MustMatch{
			TagGroups: [][]string{},
			TextGroups: [][]string{
				{"路由器", "router", "无线路由", "wifi"},
				{"账号", "账户", "account", "登录"},
			},
		},
	}

	mergeRelatedTextGroupsForSingleConcept(&plan, "路由器账号")

	if len(plan.Must.TextGroups) != 2 {
		t.Fatalf("expected multi-concept query to keep both groups, got %#v", plan.Must.TextGroups)
	}
}

func containsString(items []string, expected string) bool {
	for _, item := range items {
		if item == expected {
			return true
		}
	}
	return false
}

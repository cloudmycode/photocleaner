package main

import "encoding/json"

// SearchPlan 客户端本地检索计划（倒排交集 + filters）
type SearchPlan struct {
	Summary    string    `json:"summary"`
	Filters    Filters   `json:"filters,omitempty"`
	Must       MustMatch `json:"must"`
	Count      int       `json:"count"`
	Confidence float64   `json:"confidence,omitempty"`
}

// Filters 元数据硬条件（查正排档案，不走倒排）
type Filters struct {
	DateRange      *DateRange      `json:"dateRange,omitempty"`
	LocationBounds []LocationBound `json:"locationBounds,omitempty"`
	MediaTypes     []string        `json:"mediaTypes,omitempty"`
	AssetTypes     []string        `json:"assetTypes,omitempty"`
	MinSizeMB      *float64        `json:"minSizeMB,omitempty"`
	MaxSizeMB      *float64        `json:"maxSizeMB,omitempty"`
	MinPixelWidth  *int            `json:"minPixelWidth,omitempty"`
	MinPixelHeight *int            `json:"minPixelHeight,omitempty"`
	HasLocation    *bool           `json:"hasLocation,omitempty"`
}

// MustMatch 检索条件：tag 组（模型从 availableTags 中选择）与文本组（模型抽取）
// 两类组均为「组内 OR、组间 AND」。
type MustMatch struct {
	TagGroups  [][]string `json:"tagGroups"`
	TextGroups [][]string `json:"textGroups"`
}

// MarshalJSON 保证空列表序列化为 [] 而非 null
func (m MustMatch) MarshalJSON() ([]byte, error) {
	type alias struct {
		TagGroups  [][]string `json:"tagGroups"`
		TextGroups [][]string `json:"textGroups"`
	}
	a := alias{
		TagGroups:  m.TagGroups,
		TextGroups: m.TextGroups,
	}
	if a.TagGroups == nil {
		a.TagGroups = [][]string{}
	}
	if a.TextGroups == nil {
		a.TextGroups = [][]string{}
	}
	return json.Marshal(a)
}

// DateRange 日期范围
type DateRange struct {
	Start string `json:"start,omitempty"`
	End   string `json:"end,omitempty"`
}

// LocationBound 位置边界
type LocationBound struct {
	Name         string  `json:"name,omitempty"`
	MinLatitude  float64 `json:"minLatitude,omitempty"`
	MaxLatitude  float64 `json:"maxLatitude,omitempty"`
	MinLongitude float64 `json:"minLongitude,omitempty"`
	MaxLongitude float64 `json:"maxLongitude,omitempty"`
}

var validSensitiveTypes = map[string]struct{}{
	"id_card":   {},
	"bank_card": {},
	"passport":  {},
	"document":  {},
}

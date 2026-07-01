#!/usr/bin/env python3
"""
本地检索匹配验证：模拟客户端 SearchEngine 的倒排交集 + filters + 关键词组匹配逻辑
不依赖服务端，直接用构造好的 SearchPlan 和照片索引跑匹配，验证检索算法正确性。

注意：同义词由服务端大模型在 plan 的组内直接展开，客户端不再做任何同义词扩展。
visualTagGroups / searchKeywordGroups 均为「组内 OR、组间 AND」。
"""
import sys


def make_entry(asset_id, visual_tags=None, ocr="", description="", creation=None,
               latitude=None, longitude=None, media_type="image", asset_types=None,
               storage_bytes=0, pixel_width=0, pixel_height=0):
    return {
        "id": asset_id,
        "visualTags": visual_tags or [],
        "searchableOCRText": ocr,
        "searchableDescriptionText": description,
        "creationDate": creation,
        "latitude": latitude,
        "longitude": longitude,
        "mediaType": media_type,
        "assetTypes": asset_types or [],
        "storageBytes": storage_bytes,
        "pixelWidth": pixel_width,
        "pixelHeight": pixel_height,
    }


def matches_filters(entry, filters):
    if not filters:
        return True
    date_range = filters.get("dateRange")
    if date_range:
        start = date_range.get("start")
        end = date_range.get("end")
        creation = entry.get("creationDate")
        if creation is None:
            return False
        if start and creation < start:
            return False
        if end and creation > end:
            return False
    bounds = filters.get("locationBounds") or []
    if bounds:
        lat = entry.get("latitude")
        lon = entry.get("longitude")
        if lat is None or lon is None:
            return False
        in_any = False
        for b in bounds:
            if (b["minLatitude"] <= lat <= b["maxLatitude"] and
                    b["minLongitude"] <= lon <= b["maxLongitude"]):
                in_any = True
                break
        if not in_any:
            return False
    media_types = filters.get("mediaTypes") or []
    if media_types and entry.get("mediaType") not in media_types:
        return False
    asset_types = filters.get("assetTypes") or []
    if asset_types:
        if not (set(asset_types) & set(entry.get("assetTypes", []))):
            return False
    min_mb = filters.get("minSizeMB")
    if min_mb is not None and entry.get("storageBytes", 0) < min_mb * 1024 * 1024:
        return False
    max_mb = filters.get("maxSizeMB")
    if max_mb is not None and entry.get("storageBytes", 0) > max_mb * 1024 * 1024:
        return False
    return True


def keyword_groups_match(entry, groups):
    """组内 OR 子串、组间 AND，匹配 searchableDescriptionText"""
    haystack = entry.get("searchableDescriptionText", "").lower()
    if not haystack:
        return False
    for group in groups:
        if not any(word.lower() in haystack for word in group):
            return False
    return True


def visual_tag_groups_match(entry, groups):
    """组内并集（OR）、组间交集（AND）；不做同义词扩展"""
    entry_tags = set(entry.get("visualTags", []))
    for group in groups:
        if not (set(group) & entry_tags):
            return False
    return True


def sensitive_match(sensitive_types, sensitive_postings):
    ids = None
    for stype in sensitive_types:
        group = sensitive_postings.get(stype, set())
        ids = group if ids is None else (ids & group)
    return ids or set()


def run_case(name, plan, entries, expected_ids, sensitive_postings=None):
    must = plan.get("must", {})
    groups = must.get("searchKeywordGroups") or []
    matched_ids = None

    # 关键词组匹配（描述子串）
    if groups:
        kw_ids = {e["id"] for e in entries if keyword_groups_match(e, groups)}
        matched_ids = kw_ids

    # visualTagGroups: 组内并集、组间交集
    visual_groups = must.get("visualTagGroups") or []
    if visual_groups:
        visual_ids = {e["id"] for e in entries if visual_tag_groups_match(e, visual_groups)}
        matched_ids = visual_ids if matched_ids is None else (matched_ids & visual_ids)

    # sensitiveTypes: 倒排交集
    sensitive = must.get("sensitiveTypes") or []
    if sensitive and sensitive_postings:
        s_ids = sensitive_match(sensitive, sensitive_postings)
        matched_ids = s_ids if matched_ids is None else (matched_ids & s_ids)

    if matched_ids is None:
        matched_ids = set()

    # ocrContainsAll
    ocr_terms = must.get("ocrContainsAll") or []
    if ocr_terms:
        ocr_by_id = {e["id"]: e.get("searchableOCRText", "").lower() for e in entries}
        for term in ocr_terms:
            needle = term.lower()
            matched_ids = {i for i in matched_ids if needle in ocr_by_id.get(i, "")}

    # filters
    filters = plan.get("filters")
    final_ids = [
        e["id"] for e in entries
        if e["id"] in matched_ids and matches_filters(e, filters)
    ]

    ok = set(final_ids) == set(expected_ids)
    status = "OK" if ok else "FAIL"
    print(f"[{status}] {name}: got {sorted(final_ids)}, expected {sorted(expected_ids)}")
    return ok


# ---- Test cases ----

def test_keyword_groups():
    entries = [
        make_entry("a", description="路由器 router 后台管理 账号 admin 密码"),
        make_entry("b", description="家里的 wifi 路由器 说明书"),
        make_entry("c", description="风景照 海边 日落"),
    ]
    plan = {
        "must": {
            "searchKeywordGroups": [
                ["路由器", "router", "wifi"],
                ["账号", "account", "admin"],
            ],
        },
    }
    return run_case("keyword_groups", plan, entries, ["a"])


def test_visual_tags():
    entries = [
        make_entry("a", visual_tags=["beach", "person"]),
        make_entry("b", visual_tags=["car"]),
        make_entry("c", visual_tags=["sea"]),
    ]
    # 同义词由服务端展开进组内
    plan = {
        "must": {
            "visualTagGroups": [["beach", "sea", "ocean", "coast", "shore"]],
        },
    }
    return run_case("visual_tags", plan, entries, ["a", "c"])


def test_visual_groups_and():
    # 两组视觉概念，组间 AND：海边 + 人
    entries = [
        make_entry("a", visual_tags=["beach", "person"]),
        make_entry("b", visual_tags=["beach"]),
        make_entry("c", visual_tags=["person"]),
    ]
    plan = {
        "must": {
            "visualTagGroups": [
                ["beach", "sea", "shore"],
                ["person", "people", "human"],
            ],
        },
    }
    return run_case("visual_groups_and", plan, entries, ["a"])


def test_visual_and_filter():
    entries = [
        make_entry("a", visual_tags=["beach"], creation="2024-06-01",
                   latitude=18.3, longitude=109.5),
        make_entry("b", visual_tags=["beach"], creation="2020-01-01"),
    ]
    plan = {
        "must": {"visualTagGroups": [["beach"]]},
        "filters": {"dateRange": {"start": "2024-01-01"}},
    }
    return run_case("visual_and_filter", plan, entries, ["a"])


def test_sensitive_types():
    entries = [
        make_entry("a", ocr="身份证 姓名 公民身份号码"),
        make_entry("b", ocr="普通照片"),
    ]
    plan = {
        "must": {"sensitiveTypes": ["id_card"], "ocrContainsAll": []},
        "filters": None,
    }
    sensitive_postings = {"id_card": {"a"}}
    return run_case("sensitive_types", plan, entries, ["a"], sensitive_postings)


def test_size_filter():
    entries = [
        make_entry("a", visual_tags=["car"], storage_bytes=5 * 1024 * 1024),
        make_entry("b", visual_tags=["car"], storage_bytes=1 * 1024 * 1024),
    ]
    plan = {
        "must": {"visualTagGroups": [["car"]]},
        "filters": {"minSizeMB": 3},
    }
    return run_case("size_filter", plan, entries, ["a"])


def test_no_match():
    entries = [make_entry("a", visual_tags=["dog"])]
    plan = {"must": {"visualTagGroups": [["cat"]]}}
    return run_case("no_match", plan, entries, [])


def main():
    passed = 0
    failed = 0
    tests = [
        test_keyword_groups,
        test_visual_tags,
        test_visual_groups_and,
        test_visual_and_filter,
        test_sensitive_types,
        test_size_filter,
        test_no_match,
    ]
    for test in tests:
        try:
            if test():
                passed += 1
            else:
                failed += 1
        except Exception as e:
            print(f"[ERROR] {test.__name__}: {e}")
            failed += 1
    print(f"\n{passed} passed, {failed} failed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()

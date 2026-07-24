from eval import common

BANNED = ("승인", "추천", "권장")
VALID_ACTIONS = {"유지", "수정", "드롭", "누락보완"}


def test_gold_file_structural_integrity():
    gold = common.load_confirmed_gold()
    assert len(gold) >= 29  # 초안 전건 + 누락보완
    for g in gold:
        assert set(g) >= {"doc", "name", "review_action", "confirmed", "review_note"}
        assert g["review_action"] in VALID_ACTIONS
        assert isinstance(g["confirmed"], dict)
        # confirmed 는 PRODUCT_KEYS 전 키 보유(doc 제외)
        assert set(g["confirmed"]) >= set(common.PRODUCT_KEYS)


def test_every_modification_cites_source():
    gold = common.load_confirmed_gold()
    for g in gold:
        if g["review_action"] in ("수정", "누락보완"):
            assert g.get("source_ref"), f"{g['name']}: 수정/누락보완은 source_ref 필수"
            assert g["review_note"].strip()


def test_no_banned_wording_in_confirmed():
    gold = common.load_confirmed_gold()
    for g in gold:
        blob = "".join(str(v) for v in g["confirmed"].values() if isinstance(v, str))
        for w in BANNED:
            assert w not in blob, f"{g['name']}: 용어 위반 '{w}'"


def test_draft_products_all_covered():
    draft = common.load_extraction_draft()
    gold = common.load_confirmed_gold()
    gold_keys = {(g["doc"], g["name"]) for g in gold}
    for d in draft:
        assert (d["doc"], d["name"]) in gold_keys, f"미검수: {d['name']}"

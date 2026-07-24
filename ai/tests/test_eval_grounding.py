from eval.suites import grounding


def test_verbatim_match_and_mismatch():
    docs = {"D": "가나다 라마바 사아자 차카타"}
    gold = [
        {"product_id": "F-1", "name": "A", "doc": "D", "quote": "라마바 사아자"},
        {"product_id": "F-2", "name": "B", "doc": "D", "quote": "존재하지 않는 인용"},
    ]
    m = grounding.evaluate(gold, docs)
    assert m["linked"] == 2
    assert m["verbatim_match_rate"] == 0.5
    assert m["mismatches"] == ["F-2"]


def test_all_verbatim_passes():
    docs = {"D": "제9조에 의한 2026년 소상공인 정책자금"}
    gold = [{"product_id": "F-1", "name": "A", "doc": "D", "quote": "소상공인 정책자금"}]
    m = grounding.evaluate(gold, docs)
    assert m["verbatim_match_rate"] == 1.0 and m["mismatches"] == []


def test_real_gold_all_verbatim():
    from eval import common
    gold = grounding.load_gold()
    docs = {g["doc"]: common.load_source_text(g["doc"]) for g in gold}
    m = grounding.evaluate(gold, docs)
    assert m["verbatim_match_rate"] == 1.0, m["mismatches"]
    assert m["linked"] == 6

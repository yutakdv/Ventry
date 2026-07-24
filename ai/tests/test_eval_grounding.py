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


def test_null_buckets_split_legit_vs_unexpected():
    from eval import common
    gold = grounding.load_gold()
    docs = {g["doc"]: common.load_source_text(g["doc"]) for g in gold}
    m = grounding.evaluate(gold, docs)
    # 깨진 서울신보 6문서 = 정당 null; 그 어느 것도 클린 버킷에 새지 않는다
    assert all("서울신보" in d for d in m["legitimate_null_docs"])
    assert len(m["legitimate_null_docs"]) == 6
    # 클린인데 청크 없는 문서 = 예상 밖 null (커버리지 개선 여지) — KB 상품목록·소진공 지원사업안내
    assert set(m["unexpected_null_docs"]) == {
        "KB_소상공인정책자금대출_상품목록",
        "소진공_소상공인정책자금_지원사업안내",
    }
    # 두 버킷은 서로소이고 gold 문서와도 겹치지 않는다
    assert not (set(m["legitimate_null_docs"]) & set(m["unexpected_null_docs"]))

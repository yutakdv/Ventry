import pytest

from eval.suites import grounding


def test_verbatim_match_and_mismatch():
    docs = {"D": "가나다 라마바 사아자 차카타"}
    gold = [
        {"product_id": "F-1", "name": "A", "doc": "D", "quote": "라마바 사아자"},
        {"product_id": "F-2", "name": "B", "doc": "D", "quote": "존재하지 않는 인용"},
    ]
    m = grounding.evaluate(gold, docs)
    assert m["gold_spotcheck_n"] == 2
    assert m["gold_verbatim_rate"] == 0.5
    assert m["gold_mismatches"] == ["F-2"]


def test_all_verbatim_passes():
    docs = {"D": "제9조에 의한 2026년 소상공인 정책자금"}
    gold = [{"product_id": "F-1", "name": "A", "doc": "D", "quote": "소상공인 정책자금"}]
    m = grounding.evaluate(gold, docs)
    assert m["gold_verbatim_rate"] == 1.0 and m["gold_mismatches"] == []


def test_real_gold_all_verbatim():
    from eval import common
    gold = grounding.load_gold()
    docs = {g["doc"]: common.load_source_text(g["doc"]) for g in gold}
    if not any(docs.values()):
        pytest.skip("원문 txt 부재(ai/data/interim 미커밋) — 데이터 의존 테스트")
    m = grounding.evaluate(gold, docs)
    assert m["gold_verbatim_rate"] == 1.0, m["gold_mismatches"]
    # KB 3건은 골드에서 뺐다 — 그 문서는 인용 불가 판정이라 적재본에 실리지 않는 인용을
    # 스팟체크해도 아무것도 지키지 못한다 (리뷰 #4-4).
    assert m["gold_spotcheck_n"] == 10


def test_null_buckets_split_legit_vs_unexpected():
    from eval import common
    gold = grounding.load_gold()
    docs = {g["doc"]: common.load_source_text(g["doc"]) for g in gold}
    if not any(docs.values()):
        pytest.skip("원문 txt 부재(ai/data/interim 미커밋) — is_clean_source 데이터 의존")
    m = grounding.evaluate(gold, docs)
    # 버킷 모수는 '인용이 비어 있는 적재 상품의 문서'이며 세 갈래다 (리뷰 #5·#4).
    # ① 인용 불가 문서 = KB 4종(브라우저 인쇄본이라 자격 문단이 원문에 없다) — 의도된 공백
    # ② 정당 null = 원문이 깨져 청크 불가  ③ 예상 밖 null = 클린인데 근거 없음 → 항상 0이어야 한다
    assert m["shipped_products"] == 26
    assert m["linked_products"] == 22
    assert len(m["non_quotable_docs"]) == 4
    assert all("KB" in d for d in m["non_quotable_docs"])
    assert m["legitimate_null_docs"] == []
    assert m["unexpected_null_docs"] == []
    # 서울신보 6문서는 웹 재수집(#72)으로 클린해졌다 — 되돌아가면 정당 null 로 떨어진다.
    assert not any("서울신보" in d for d in m["legitimate_null_docs"])
    # 두 버킷은 서로소다
    assert not (set(m["legitimate_null_docs"]) & set(m["unexpected_null_docs"]))


def test_coverage_is_over_shipped_products():
    """coverage 분모는 적재 상품 수다 — 초안 29건이 아니다 (리뷰 #5)."""
    from eval import common
    products = common.load_finance_products()
    assert len(products) == 26
    m = grounding.run(None)
    linked = sum(1 for _, ref in products if ref)
    assert m["shipped_products"] == 26
    assert m["linked_products"] == linked
    assert abs(m["coverage"] - linked / 26) < 1e-9


def test_shipped_chunks_are_verbatim_substrings_of_source():
    """DB 에 실릴 청크가 원문의 부분문자열이어야 한다 — 골드가 아니라 실물 대조 (리뷰 #5)."""
    m = grounding.run(None)
    assert m["shipped_chunks"] > 0
    assert m["chunk_verbatim_rate"] == 1.0, m["chunk_mismatches"]

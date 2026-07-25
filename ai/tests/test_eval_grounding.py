import pytest

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
    if not any(docs.values()):
        pytest.skip("원문 txt 부재(ai/data/interim 미커밋) — 데이터 의존 테스트")
    m = grounding.evaluate(gold, docs)
    assert m["verbatim_match_rate"] == 1.0, m["mismatches"]
    assert m["linked"] == 13


def test_null_buckets_split_legit_vs_unexpected():
    from eval import common
    gold = grounding.load_gold()
    docs = {g["doc"]: common.load_source_text(g["doc"]) for g in gold}
    if not any(docs.values()):
        pytest.skip("원문 txt 부재(ai/data/interim 미커밋) — is_clean_source 데이터 의존")
    m = grounding.evaluate(gold, docs)
    # 서울신보 6문서는 웹 재수집(#72)으로 클린해졌다 — 더는 '정당 null' 이 아니며,
    # 전건 인용이 붙었으므로 어느 버킷에도 남지 않는다. 이 단언이 깨지면 재수집이 유실된 것이다.
    assert m["legitimate_null_docs"] == []
    assert not any("서울신보" in d for d in m["unexpected_null_docs"])
    # 남은 예상 밖 null 1건: KB 상품목록은 볼드 중복 아티팩트로 대표 구절을 못 뽑는다.
    # 지표를 위해 억지 인용을 만들지 않는다(§5-4) — 개선 여지로 노출된 상태가 정상이다.
    assert set(m["unexpected_null_docs"]) == {"KB_소상공인정책자금대출_상품목록"}
    # 두 버킷은 서로소이고 gold 문서와도 겹치지 않는다
    assert not (set(m["legitimate_null_docs"]) & set(m["unexpected_null_docs"]))

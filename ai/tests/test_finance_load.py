"""정책자금 적재·청킹 단위 테스트 (AI-06-2)."""
from batch.load.finance import POLICY_BASE_RATE, build_finance, chunk_document, db_rate


def test_db_rate_fixed_passthrough():
    assert db_rate({"rate": 2.1, "rate_note": None}) == 2.1


def test_db_rate_variable_base_plus_addon():
    # '기준금리+0.6%p' → 기준금리 상수 + 가산 (effective)
    got = db_rate({"rate": None, "rate_note": "기준금리+0.6%p"})
    assert got == round(POLICY_BASE_RATE + 0.6, 3)


def test_db_rate_range_takes_lowest():
    assert db_rate({"rate": None, "rate_note": "연 2.5%~최고 연 3.5%"}) == 2.5


def test_db_rate_pure_variable_fallback_not_null():
    assert db_rate({"rate": None, "rate_note": "기준금리 변동"}) == POLICY_BASE_RATE


def test_chunk_document_keeps_original_text():
    chunks = chunk_document("소진공_x", "문단1 원문.\n\n문단2 원문.")
    assert chunks[0]["text"] == "문단1 원문."  # 재작성 없이 원문 그대로
    assert chunks[0]["chunk_id"]


def test_build_finance_links_doc_chunk_ref(tmp_path):
    (tmp_path / "소진공_x.txt").write_text("창업기업자금 안내.\n\n한도 7천만원.", encoding="utf-8")
    reviewed = [{
        "product_id": "p1", "name": "창업기업자금", "org": "소진공", "amount_max": 7000,
        "rate": 2.5, "status": "open", "doc": "소진공_x", "source_url": "http://x",
        "notice_date": "2026-06-01",
    }]
    tables = build_finance(reviewed, docs_dir=tmp_path)
    fp = tables["finance_product"].iloc[0]
    assert fp["doc_chunk_ref"] in set(tables["finance_doc_chunk"]["chunk_id"])
    assert fp["source_org"] == "소진공" and fp["data_as_of"]


def test_build_finance_missing_doc_null_ref(tmp_path):
    # 문서 부재/깨짐 → 청크 날조 금지, doc_chunk_ref=None (§5-4, 코드리뷰 S1)
    reviewed = [{"product_id": "p2", "name": "X자금", "org": "소진공",
                 "amount_max": 3000, "rate": 2.0, "status": "open", "doc": "없는문서"}]
    tables = build_finance(reviewed, docs_dir=tmp_path)
    assert tables["finance_product"].iloc[0]["doc_chunk_ref"] is None
    assert len(tables["finance_doc_chunk"]) == 0


def test_build_finance_skips_garbage_text(tmp_path):
    # 깨진 텍스트(키워드 밀도 낮음)는 청크 미생성 → null ref
    (tmp_path / "깨진문서.txt").write_text("폀햋밃쨊캧폀 핯퀓햋폀샴", encoding="utf-8")
    reviewed = [{"product_id": "p3", "name": "보증상품", "org": "서울신용보증재단",
                 "amount_max": 5000, "rate": 1.0, "status": "open", "doc": "깨진문서"}]
    tables = build_finance(reviewed, docs_dir=tmp_path)
    assert tables["finance_product"].iloc[0]["doc_chunk_ref"] is None

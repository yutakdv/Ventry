"""정책자금 적재·청킹 단위 테스트 (AI-06-2)."""
from batch.load.finance import build_finance, chunk_document, rate_fields


def test_rate_fields_fixed_passthrough():
    assert rate_fields({"rate": 2.1, "rate_note": None}) == (2.1, "fixed", None)


def test_rate_fields_addon_not_fabricated():
    # '기준금리+0.6%p' → 절대금리 지어내지 않음: rate=None, 원문 note 보존
    rate, rtype, note = rate_fields({"rate": None, "rate_note": "정책자금 기준금리 + 0.6%p"})
    assert rate is None and rtype == "variable"
    assert note == "정책자금 기준금리 + 0.6%p"


def test_rate_fields_disclosed_reference_variable():
    # 변동이지만 공시 절대값('최저 연 3.62%')이 있으면 그 값 + variable
    rate, rtype, _ = rate_fields({"rate": None, "rate_note": "최저 연 3.62% 3개월 변동금리"})
    assert rate == 3.62 and rtype == "variable"


def test_rate_fields_pure_variable_null():
    note = "정책자금 기준금리"
    assert rate_fields({"rate": None, "rate_note": note}) == (None, "variable", note)


def test_rate_fields_confirmed_quarter_rate_stays_variable():
    """검수로 해당 분기 실값을 확정해도 성격은 여전히 분기 변동이다 (#72, assumptions #34).

    값이 있다고 fixed 로 표기하면 사용자가 재확인 시점을 알 수 없다.
    """
    note = ("정책자금 기준금리 3.85% + 0.6%p = 연 4.45% "
            "(’26년 3/4분기, 2026-07-10 적용 · 분기별 변동금리)")
    assert rate_fields({"rate": 4.45, "rate_note": note}) == (4.45, "variable", note)


def test_rate_fields_guarantee_fee_never_becomes_rate():
    """보증상품의 추출 rate 는 보증료율일 수 있다 — note 에 고정 대출금리가 없으면 NULL."""
    note = "보증료 연 0.7%. 대출금리 서울시자금 이용 시 은행금리에서 1.8% 차감"
    rate, rtype, _ = rate_fields({"rate": 0.7, "product_type": "guarantee", "rate_note": note})
    assert rate is None and rtype == "variable"


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

"""정책자금 적재·청킹 단위 테스트 (AI-06-2)."""
from batch.load.finance import build_finance, chunk_document, rate_fields, select_chunk


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


def test_chunk_document_strips_nul_bytes():
    """PDF 추출 아티팩트인 NUL 은 제거한다 — psql 이 주변 문장까지 삼킨다 (리뷰 #4).

    NUL 은 공고문의 글자가 아니라 pypdf 산출물의 제어문자다. 지우면 DB 저장본이
    파이썬 청크와 정확히 같아져 verbatim 대조가 성립한다.
    """
    raw = "지원요건\x00(2026년정책자금)세부\x00지원요건.\n\n둘째 문단."
    chunks = chunk_document("소진공_x", raw)
    assert all("\x00" not in c["text"] for c in chunks)
    assert chunks[0]["text"] == "지원요건(2026년정책자금)세부지원요건."


def _chunks(*texts):
    return [{"chunk_id": f"D#{i}", "text": t} for i, t in enumerate(texts)]


def test_select_chunk_prefers_name_match_over_first_paragraph():
    chunks = _chunks("표지 브로슈어 머리말", "청년고용연계자금 한도 7천만원 상환 5년")
    assert select_chunk("청년고용연계자금", chunks)["chunk_id"] == "D#1"


def test_select_chunk_prefers_shorter_chunk_on_tie():
    """같은 점수면 더 짧은 청크가 구체적이다 — 통짜 첫 문단이 이기지 않게."""
    long_text = "대환대출 " + "기타 안내 " * 200
    chunks = _chunks(long_text, "대환대출 한도 5천만원")
    assert select_chunk("대환대출", chunks)["chunk_id"] == "D#1"


def test_select_chunk_returns_none_when_name_absent():
    """이름이 어디에도 없으면 인용을 비운다 — 첫 문단을 근거로 지목하지 않는다 (리뷰 #3)."""
    chunks = _chunks("전혀 다른 상품 안내", "또 다른 문단")
    assert select_chunk("혁신성장촉진자금", chunks) is None


def test_select_chunk_empty_chunks():
    assert select_chunk("아무자금", []) is None


def test_build_finance_no_blind_first_chunk_fallback(tmp_path):
    """이름이 원문에 없는 상품은 doc_chunk_ref 가 비어야 한다 (리뷰 #3)."""
    (tmp_path / "소진공_x.txt").write_text(
        "대출 융자 한도 금리 보증 지원 소상공인 상환 기업 안내문.\n\n"
        "창업기업자금 대출 한도 7천만원 금리 보증 지원 소상공인 상환 기업.",
        encoding="utf-8")
    reviewed = [
        {"product_id": "p1", "name": "창업기업자금", "org": "소진공", "amount_max": 7000,
         "rate": 2.5, "status": "open", "doc": "소진공_x"},
        {"product_id": "p2", "name": "존재하지않는자금", "org": "소진공", "amount_max": 3000,
         "rate": 2.0, "status": "open", "doc": "소진공_x"},
    ]
    import pandas as pd

    fp = build_finance(reviewed, docs_dir=tmp_path)["finance_product"].set_index("product_id")
    assert fp.loc["p1", "doc_chunk_ref"] == "소진공_x#1"
    # 혼합 컬럼이라 pandas 가 None 을 NaN 으로 바꾼다 — emit._lit 은 둘 다 NULL 로 내보낸다
    assert pd.isna(fp.loc["p2", "doc_chunk_ref"])


def test_build_finance_skips_garbage_text(tmp_path):
    # 깨진 텍스트(키워드 밀도 낮음)는 청크 미생성 → null ref
    (tmp_path / "깨진문서.txt").write_text("폀햋밃쨊캧폀 핯퀓햋폀샴", encoding="utf-8")
    reviewed = [{"product_id": "p3", "name": "보증상품", "org": "서울신용보증재단",
                 "amount_max": 5000, "rate": 1.0, "status": "open", "doc": "깨진문서"}]
    tables = build_finance(reviewed, docs_dir=tmp_path)
    assert tables["finance_product"].iloc[0]["doc_chunk_ref"] is None

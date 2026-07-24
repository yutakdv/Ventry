"""정책자금 gpt-4o 추출 파이프라인 단위 테스트 (실 API 미호출, AI-06)."""
from batch.extract.funding_llm import dedup_products, parse_extraction, validate_product


def test_parse_extraction_maps_nulls_as_unconstrained():
    raw = (
        '{"name":"창업기업자금","org":"소진공","max_age":39,'
        '"industries":["cafe","food"],"regions":null,"pre_startup_only":true,'
        '"amount_max":7000,"rate":2.5,"term_months":60,"exclusive_group":"policy",'
        '"status":"open","notice_date":"2026-06-01"}'
    )
    p = parse_extraction(raw)
    assert p["max_age"] == 39
    assert p["regions"] is None  # null = 전 지역 무제약
    assert p["amount_max"] == 7000


def test_validate_product_flags_missing_required():
    errs = validate_product({"name": "x"})  # amount_max·org·status 누락
    assert any("amount_max" in e for e in errs)


def test_validate_product_rejects_approval_wording():
    errs = validate_product({
        "name": "x", "org": "o", "amount_max": 100, "rate": 2.0,
        "status": "open", "note": "승인 보장",
    })
    assert any("용어" in e or "승인" in e for e in errs)


def test_validate_fixed_rate_ok():
    errs = validate_product({
        "name": "일반경영안정자금", "org": "소진공", "amount_max": 7000,
        "rate": 3.6, "status": "open",
    })
    assert errs == []


def test_validate_variable_rate_null_ok_with_note():
    # 변동금리: rate=null 이어도 rate_note 있으면 정당 (S6)
    errs = validate_product({
        "name": "청년고용연계자금", "org": "소진공", "amount_max": 7000,
        "rate": None, "rate_note": "기준금리", "status": "open",
    })
    assert errs == []


def test_validate_missing_rate_without_note_flags():
    errs = validate_product({
        "name": "x", "org": "o", "amount_max": 100, "rate": None, "status": "open",
    })
    assert any("rate" in e for e in errs)


def test_dedup_prefers_amount_present():
    # 소진공 두 문서 중복 → 금액 있는 쪽 유지 (S2)
    products = [
        {"org": "소진공", "name": "일반경영안정자금", "amount_max": None, "rate": None},
        {"org": "소진공", "name": "일반경영안정자금", "amount_max": 7000, "rate": 3.6},
    ]
    out = dedup_products(products)
    assert len(out) == 1
    assert out[0]["amount_max"] == 7000

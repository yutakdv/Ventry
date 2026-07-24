"""정책자금 gpt-4o 추출 파이프라인 단위 테스트 (실 API 미호출, AI-06)."""
from batch.extract.funding_llm import parse_extraction, validate_product


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
    errs = validate_product({"name": "x"})  # amount_max·rate·org·status 누락
    assert any("amount_max" in e for e in errs)


def test_validate_product_rejects_approval_wording():
    errs = validate_product({
        "name": "x", "org": "o", "amount_max": 100, "rate": 2.0,
        "status": "open", "note": "승인 보장",
    })
    assert any("용어" in e or "승인" in e for e in errs)


def test_validate_product_ok_has_no_errors():
    errs = validate_product({
        "name": "일반경영안정자금", "org": "소진공", "amount_max": 7000,
        "rate": 3.6, "status": "open",
    })
    assert errs == []

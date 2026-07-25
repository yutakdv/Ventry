"""서빙 조립 통합 테스트 — 실데이터 의존. data/ 부재(CI)면 자동 skip."""
import pytest

from batch.paths import INTERIM_DIR, RAW_DIR

_HAS_DATA = (RAW_DIR / "seoul_commercial").exists() and (INTERIM_DIR / "join").exists()
pytestmark = pytest.mark.skipif(not _HAS_DATA, reason="data/ 원천 부재 (CI 스킵)")


@pytest.fixture(scope="module")
def tables():
    from batch.load.serving import assemble
    return assemble()


def test_core_tables_present(tables):
    for name in ("commercial_area", "sales", "rent", "transit",
                 "location_score", "initial_cost", "data_source_meta"):
        assert name in tables and len(tables[name]) > 0


def test_location_score_grain_area_industry(tables):
    ls = tables["location_score"]
    assert not ls.duplicated(subset=["area_code", "industry"]).any()
    for col in ("w1", "w2", "w3", "w4", "w5"):
        assert ls[col].between(0, 1).all()


def test_no_burden_or_total_score_columns(tables):
    ls = tables["location_score"]
    assert "burden_ratio" not in ls.columns and "score" not in ls.columns


def test_initial_cost_matches_ddl_columns(tables):
    ic = tables["initial_cost"]
    assert "based_on_quarter" in ic.columns
    assert (ic["cost_incl_premium_high"] >= ic["cost_ex_premium_high"]).all()


def test_initial_cost_carries_industry_aware_rent(tables):
    """부담률 분자는 업종 대표면적 기준이어야 한다 (리뷰 #2).

    같은 상권에서 카페 환산임대료 < 음식점 환산임대료 여야 한다 — 29.2㎡ < 55.2㎡.
    """
    ic = tables["initial_cost"]
    assert "monthly_rent" in ic.columns
    pivot = ic.pivot(index="area_code", columns="industry", values="monthly_rent").dropna()
    assert len(pivot) > 100
    assert (pivot["cafe"] < pivot["food"]).all()


def test_fk_area_codes_subset_of_master(tables):
    valid = set(tables["commercial_area"]["area_code"])
    for name in ("sales", "rent", "location_score", "initial_cost", "transit"):
        assert set(tables[name]["area_code"]) <= valid

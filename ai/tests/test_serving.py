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

    같은 상권이면 두 업종의 환산임대료 비는 대표면적 비와 같아야 한다. 방향을
    상수에서 끌어오는 이유는 대표면적이 갱신되면(#152 카페 29.2→44.0) 부등호가
    뒤집힐 수도 있기 때문이다 — 검증해야 할 성질은 대소가 아니라 **면적 비례**다.
    """
    from batch.preprocess.cost import REPRESENTATIVE_AREA_M2

    ic = tables["initial_cost"]
    assert "monthly_rent" in ic.columns
    pivot = ic.pivot(index="area_code", columns="industry", values="monthly_rent").dropna()
    assert len(pivot) > 100
    expected = REPRESENTATIVE_AREA_M2["cafe"] / REPRESENTATIVE_AREA_M2["food"]
    ratio = pivot["cafe"] / pivot["food"]
    assert ((ratio - expected).abs() < 0.01).all()


def test_gu_avg_differs_from_region_avg(tables):
    """gu_avg 는 자치구 평균, region_avg 는 권역 평균 — 두 등급이 같은 값이면 5등급이 무의미하다.

    구 구현은 폴백 전건이 권역 평균으로 흘러 두 등급이 구분되지 않았다 (리뷰 #8).
    """
    import pandas as pd

    from batch.paths import INTERIM_DIR

    assign = pd.read_csv(INTERIM_DIR / "join" / "rent_assignment.csv", dtype=str)
    assign.columns = [c.lstrip("﻿") for c in assign.columns]
    gu_rows = assign[assign["assign_level"] == "gu_avg"]
    if gu_rows["sigungu_name"].nunique() < 2:
        pytest.skip("gu_avg 자치구가 1개뿐 — 비교 불가")
    rent = tables["rent"].merge(
        assign[["area_code", "assign_level", "sigungu_name"]], on="area_code", how="left")
    gu = rent[rent["assign_level"] == "gu_avg"]
    # 자치구 안에서는 단일값
    assert gu.groupby("sigungu_name")["unit_price"].nunique().max() == 1
    # 자치구마다 제 평균을 가져야 한다. 구 구현은 23개 자치구가 R-ONE 권역 4종 값으로
    # 붕괴했다 — distinct 단가 수가 자치구 수에 못 미치면 권역 평균을 쓰고 있다는 뜻이다.
    n_gu = gu["sigungu_name"].nunique()
    assert gu["unit_price"].nunique() == n_gu, (
        f"자치구 {n_gu}개인데 단가는 {gu['unit_price'].nunique()}종 — 권역 평균으로 붕괴"
    )


def test_fk_area_codes_subset_of_master(tables):
    valid = set(tables["commercial_area"]["area_code"])
    for name in ("sales", "rent", "location_score", "initial_cost", "transit"):
        assert set(tables[name]["area_code"]) <= valid

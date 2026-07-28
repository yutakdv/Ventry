"""초기비용·점수 산출 DataFrame 단위 테스트 (합성 입력, CI-safe)."""
import pandas as pd

from batch.preprocess.cost import build_initial_cost
from batch.preprocess.score import build_location_score


def test_build_initial_cost_columns():
    rent = pd.DataFrame([{"area_code": "A1", "industry": "cafe", "unit_price": 80.0}])
    df = build_initial_cost(rent, seoul_median_unit_price=80.0)
    assert {
        "deposit_low", "deposit_high", "premium_low", "premium_high",
        "interior_low", "interior_high", "monthly_fixed_cost",
        "cost_ex_premium_low", "cost_incl_premium_high",
    } <= set(df.columns)
    row = df.iloc[0]
    assert row["deposit_low"] == 352 * 8  # converted_rent(80,cafe)=352, ×8
    assert row["cost_incl_premium_high"] >= row["cost_ex_premium_high"]


def test_build_location_score_axis_range_and_competition_inverse():
    metrics = pd.DataFrame([
        {"area_code": "A1", "industry": "cafe", "pedestrian": 100, "backing": 50,
         "riders": 10000, "distance_m": 300, "est_sales": 2000, "monthly_rent": 200,
         "density": 10, "growth_rank": 0.7, "daily_floating": 100, "quarter": "20261"},
        {"area_code": "A2", "industry": "cafe", "pedestrian": 300, "backing": 80,
         "riders": 50000, "distance_m": 100, "est_sales": 3000, "monthly_rent": 400,
         "density": 40, "growth_rank": 0.4, "daily_floating": 300, "quarter": "20261"},
    ])
    df = build_location_score(metrics)
    for col in ("w1", "w2", "w3", "w4", "w5"):
        assert df[col].between(0, 1).all()
    # 경쟁여유 역방향: 밀도 낮은 A1 의 w3 이 A2 보다 높다
    by_area = df.set_index("area_code")
    assert by_area.loc["A1", "w3"] > by_area.loc["A2", "w3"]
    # 종합점수·부담률은 산출물에 없다 (BE 파생)
    assert "score" not in df.columns and "burden_ratio" not in df.columns

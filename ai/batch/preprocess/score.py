"""점수화 w1~w5 — 서울 전체 백분위 (스펙 §4-3). BE ScoreLookup 정의와 동일.

교통 감쇠 500m, w1 3성분 균등평균. 종합점수는 저장하지 않는다(가중치가 업종 프리셋 → BE 파생).
"""
from __future__ import annotations

import math

import pandas as pd

TRANSIT_DECAY_M = 500.0  # 교통 유입 감쇠 스케일 (BE ScoreLookup.TRANSIT_DECAY_M 와 동일)


def percentile(value: float, population: list[float]) -> float:
    """서울 전체 모집단 중 value 이하 비율 [0,1]."""
    if not population:
        return 0.0
    at_or_below = sum(1 for p in population if p <= value)
    return at_or_below / len(population)


def transit_influx(daily_riders: float, distance_m: float) -> float:
    """최근접 역 대중교통 유입 = 일평균 승하차 × exp(−거리/500m)."""
    return daily_riders * math.exp(-distance_m / TRANSIT_DECAY_M)


def demand_w1(pedestrian_pct: float, backing_pct: float, transit_pct: float) -> float:
    """w1 수요 = 3성분 백분위의 균등 결합(평균)."""
    return (pedestrian_pct + backing_pct + transit_pct) / 3.0


def build_location_score(metrics: pd.DataFrame) -> pd.DataFrame:
    """상권×업종 원지표 → w1~w5 서울 백분위 축값 DataFrame (업종별 모집단).

    입력 `metrics` 컬럼: area_code, industry, pedestrian, backing, riders, distance_m,
    est_sales(점포당 월매출), monthly_rent, density, growth_rank, daily_floating, quarter.
    """
    df = metrics.copy()
    df["influx"] = [transit_influx(r.riders, r.distance_m) for r in df.itertuples()]
    df["efficiency"] = df["est_sales"] / df["monthly_rent"]
    rows = []
    for industry, g in df.groupby("industry"):
        ped_pop = g["pedestrian"].tolist()
        bck_pop = g["backing"].tolist()
        inf_pop = g["influx"].tolist()
        sal_pop = g["est_sales"].tolist()
        neg_den_pop = (-g["density"]).tolist()
        growth_pop = g["growth_rank"].tolist()
        eff_pop = g["efficiency"].tolist()
        for r in g.itertuples():
            w1 = demand_w1(
                percentile(r.pedestrian, ped_pop),
                percentile(r.backing, bck_pop),
                percentile(r.influx, inf_pop),
            )
            rows.append({
                "area_code": r.area_code, "industry": industry,
                "w1": w1,
                "w2": percentile(r.est_sales, sal_pop),
                "w3": percentile(-r.density, neg_den_pop),
                "w4": percentile(r.growth_rank, growth_pop),
                "w5": percentile(r.efficiency, eff_pop),
                "est_sales": int(r.est_sales),
                "daily_floating": int(r.daily_floating),
                "based_on_quarter": r.quarter,
            })
    return pd.DataFrame(rows)

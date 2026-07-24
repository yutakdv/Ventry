"""점수화 w1~w5 — 서울 전체 백분위 (스펙 §4-3). BE ScoreLookup 정의와 동일.

교통 감쇠 500m, w1 3성분 균등평균. 종합점수는 저장하지 않는다(가중치가 업종 프리셋 → BE 파생).
"""
from __future__ import annotations

import math

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

"""초기비용 4블록 순수 함수 (스펙 §4-1, design 2026-07-24). 만원 단위 정수, 구간 표기.

상수 근거·잠정치는 docs/assumptions.md. BE CostCalculator 재계산과 정합 유지:
  cost_ex = deposit + interior + monthly_fixed_cost×6, cost_incl = cost_ex + premium.
"""
from __future__ import annotations

import pandas as pd

# 대표면적 = 인허가 소재지면적 영업중 중앙값 (채움률 99.8%, design 2-1)
REPRESENTATIVE_AREA_M2 = {"cafe": 29.2, "food": 55.2}
# 보증금 관행배수 (상가; 인허가 보증액 0% → 실측 불가라 관행, 전환율은 라벨 병기)
DEPOSIT_MULT_LOW, DEPOSIT_MULT_HIGH = 8, 12
# 권리금: 서울 숙박·음식점 ㎡당 평균 2025 (R-ONE A_2024_00445), 업종보정·구간비
PREMIUM_PER_M2_MANWON = 72.6
PREMIUM_INDUSTRY_ADJ = {"cafe": 0.85, "food": 1.0}
PREMIUM_LOW_RATIO, PREMIUM_HIGH_RATIO = 0.72, 1.15
RENT_RATIO_CLIP = (0.5, 2.0)
# 인테리어: FTC avrgJngEtcAmt 2025 만원 (assumptions #40), ±20%
INTERIOR_MANWON = {"cafe": 2485, "food": 4595}
INTERIOR_BAND = 0.20
# 월고정비 가산형 (design 2-3)
MIN_WAGE_MONTHLY_MANWON = 210  # 최저임금 시급×209h 월환산 (2025 기준; 2026 확정 시 갱신)
STAFF_STD = {"cafe": 1.5, "food": 2.0}
UTILITY_RATE = 0.20  # 공과금·기타 = 환산임대료 비율 (KOSIS 영업비용 확정치로 교체 예정, Task A0)

RESERVE_MONTHS = 6  # 예비 운영자금 = 월 고정비 × N개월 (BE CostCalculator 와 동일)


def converted_rent(unit_price_1000won_m2: float, industry: str) -> int:
    """환산임대료 만원/월 = 단가(천원/㎡) × 대표면적 ÷ 10."""
    return round(unit_price_1000won_m2 * REPRESENTATIVE_AREA_M2[industry] / 10)


def deposit_interval(monthly_rent: int) -> tuple[int, int]:
    """보증금 구간 = 환산임대료 × 관행배수 (상가 관행 8~12)."""
    return (monthly_rent * DEPOSIT_MULT_LOW, monthly_rent * DEPOSIT_MULT_HIGH)


def premium_interval(monthly_rent: int, seoul_median_rent: int, industry: str) -> tuple[int, int]:
    """권리금 구간 = ㎡당 권리금 × 대표면적 × 임대료비례 × 업종보정."""
    ratio = monthly_rent / seoul_median_rent if seoul_median_rent else 1.0
    ratio = min(max(ratio, RENT_RATIO_CLIP[0]), RENT_RATIO_CLIP[1])
    center = (
        PREMIUM_PER_M2_MANWON
        * REPRESENTATIVE_AREA_M2[industry]
        * ratio
        * PREMIUM_INDUSTRY_ADJ[industry]
    )
    return (round(center * PREMIUM_LOW_RATIO), round(center * PREMIUM_HIGH_RATIO))


def interior_interval(industry: str) -> tuple[int, int]:
    """인테리어·시설비 구간 = 업종 상수 ±20%."""
    c = INTERIOR_MANWON[industry]
    return (round(c * (1 - INTERIOR_BAND)), round(c * (1 + INTERIOR_BAND)))


def monthly_fixed_cost(monthly_rent: int, industry: str) -> int:
    """월 고정비(가산형) = 환산임대료 + 최저임금 인건비 + 공과금·기타. 예비운영자금 = ×6."""
    labor = STAFF_STD[industry] * MIN_WAGE_MONTHLY_MANWON
    utility = monthly_rent * UTILITY_RATE
    return round(monthly_rent + labor + utility)


def cost_ex_premium(
    deposit: tuple[int, int], interior: tuple[int, int], mfc: int
) -> tuple[int, int]:
    """권리금 제외 합계 구간 = (보증금 + 인테리어) + 예비운영자금(월고정비×6)."""
    reserve = mfc * RESERVE_MONTHS
    return (deposit[0] + interior[0] + reserve, deposit[1] + interior[1] + reserve)


def cost_incl_premium(ex: tuple[int, int], premium: tuple[int, int]) -> tuple[int, int]:
    """권리금 포함 합계 구간 = 권리금 제외 합계 + 권리금."""
    return (ex[0] + premium[0], ex[1] + premium[1])


def build_initial_cost(rent_df: pd.DataFrame, seoul_median_rent: int) -> pd.DataFrame:
    """상권×업종 임대료 단가 → 초기비용 4블록 + 합계 구간 DataFrame.

    입력 `rent_df` 컬럼: area_code, industry, unit_price(천원/㎡).
    """
    rows = []
    for r in rent_df.itertuples():
        industry = r.industry
        rent = converted_rent(r.unit_price, industry)
        dep = deposit_interval(rent)
        prem = premium_interval(rent, seoul_median_rent, industry)
        intr = interior_interval(industry)
        mfc = monthly_fixed_cost(rent, industry)
        ex = cost_ex_premium(dep, intr, mfc)
        incl = cost_incl_premium(ex, prem)
        rows.append({
            "area_code": r.area_code, "industry": industry,
            "monthly_rent": rent,
            "deposit_low": dep[0], "deposit_high": dep[1],
            "premium_low": prem[0], "premium_high": prem[1],
            "interior_low": intr[0], "interior_high": intr[1],
            "monthly_fixed_cost": mfc,
            "cost_ex_premium_low": ex[0], "cost_ex_premium_high": ex[1],
            "cost_incl_premium_low": incl[0], "cost_incl_premium_high": incl[1],
        })
    return pd.DataFrame(rows)

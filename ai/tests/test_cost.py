"""초기비용 4블록 순수 함수 단위 테스트 (스펙 §4-1, AI-05)."""
from batch.preprocess import cost


def test_converted_rent_units():
    # 단가 80천원/㎡ × 카페 29.2㎡ ÷ 10 = 233.6 → 234 만원/월
    assert cost.converted_rent(80.0, "cafe") == 234
    assert cost.converted_rent(20.0, "food") == round(20.0 * 55.2 / 10)


def test_deposit_interval_multiples():
    assert cost.deposit_interval(200) == (1600, 2400)  # ×8, ×12


def test_premium_interval_scales_with_rent_and_industry():
    lo, hi = cost.premium_interval(monthly_rent=300, seoul_median_rent=300, industry="cafe")
    center = 72.6 * 29.2 * 1.0 * 0.85  # ratio=1, 업종보정 0.85
    assert lo == round(center * 0.72)
    assert hi == round(center * 1.15)
    assert lo <= hi


def test_premium_ratio_clipped():
    # 임대료가 서울 중위의 10배여도 비례계수는 2.0 상한
    clipped = cost.premium_interval(3000, 300, "food")
    at_cap = cost.premium_interval(600, 300, "food")  # ratio=2.0 동일 상한
    assert clipped == at_cap


def test_interior_interval_band():
    assert cost.interior_interval("cafe") == (round(2485 * 0.8), round(2485 * 1.2))


def test_monthly_fixed_cost_additive():
    # 환산임대료 200 + 카페 1.5×210 + 200×0.20 = 200+315+40 = 555
    assert cost.monthly_fixed_cost(200, "cafe") == 555


def test_engine_cost_composition_matches():
    # BE CostCalculator 재계산과 정합: cost_ex = deposit+interior+mfc*6, cost_incl=+premium
    rent = 300
    dep = cost.deposit_interval(rent)
    intr = cost.interior_interval("food")
    prem = cost.premium_interval(rent, 300, "food")
    mfc = cost.monthly_fixed_cost(rent, "food")
    reserve = mfc * 6
    ex = (dep[0] + intr[0] + reserve, dep[1] + intr[1] + reserve)
    incl = (ex[0] + prem[0], ex[1] + prem[1])
    assert cost.cost_ex_premium(dep, intr, mfc) == ex
    assert cost.cost_incl_premium(ex, prem) == incl

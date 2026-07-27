"""초기비용 4블록 순수 함수 단위 테스트 (스펙 §4-1, AI-05)."""
from batch.preprocess import cost


def test_representative_area_pinned():
    """대표면적 상수를 못으로 박는다 (이슈 #152).

    이 값은 임대료·보증금·권리금·월고정비·초기비용 합계 **전부**에 곱해지고, 적재본
    3,300행이 여기서 나온다. 상수가 조용히 바뀌면 문서(가정 #41 ①)·제출 원고·부록 수치가
    한꺼번에 어긋나므로, 바꾸려면 이 테스트를 같이 고치게 만든다.

    출처는 `collect.permits.representative_area_m2()` — 인허가 `소재지면적` 영업중(>0)
    중앙값을 `classify_category()` 업종별로 낸 값이다. 인허가 **대장 구분**(휴게 29.3 ·
    일반 55.4)이 아니다.
    """
    assert cost.REPRESENTATIVE_AREA_M2 == {"cafe": 44.0, "food": 51.7}


def test_converted_rent_units():
    # 단가 80천원/㎡ × 카페 44.0㎡ ÷ 10 = 352.0 → 352 만원/월
    assert cost.converted_rent(80.0, "cafe") == 352
    assert cost.converted_rent(20.0, "food") == round(20.0 * 51.7 / 10)


def test_deposit_interval_multiples():
    assert cost.deposit_interval(200) == (1600, 2400)  # ×8, ×12


def test_premium_interval_scales_with_unit_price_and_industry():
    # 단가가 서울 중위와 같으면 ratio=1 — 대표면적·업종보정만 남는다
    lo, hi = cost.premium_interval(unit_price_1000won_m2=50.0,
                                   seoul_median_unit_price=50.0, industry="cafe")
    center = 72.6 * 44.0 * 1.0 * 0.85
    assert lo == round(center * 0.72)
    assert hi == round(center * 1.15)
    assert lo <= hi


def test_premium_ratio_clipped():
    # 단가가 중위의 10배여도 비례계수는 2.0 상한
    clipped = cost.premium_interval(500.0, 50.0, "food")
    at_cap = cost.premium_interval(100.0, 50.0, "food")  # ratio=2.0 동일 상한
    assert clipped == at_cap


def test_premium_ratio_is_industry_neutral():
    """같은 상권(같은 단가)이면 비례계수는 업종과 무관해야 한다 (리뷰 #1).

    구 구현은 ratio 분자에 대표면적이 들어가고 분모는 음식점 대표면적 기준 단일값이라,
    카페가 항상 작게 나왔다 — 그 결과 카페 상권 25.4%가 하한 클립(0.5)에 걸려
    권리금이 649만원 상수로 붕괴했다. 버그는 대표면적이 곱해지는 호출 경로에서 드러나므로
    산출 함수 전체로 검증한다.
    """
    import pandas as pd

    px = 50.0
    rent = pd.DataFrame([
        {"area_code": "A1", "industry": "cafe", "unit_price": px},
        {"area_code": "A1", "industry": "food", "unit_price": px},
    ])
    # 서울 중위 단가 = 자기 단가 → 두 업종 모두 ratio=1 이어야 한다
    df = cost.build_initial_cost(rent, seoul_median_unit_price=px).set_index("industry")
    cafe_lo = df.loc["cafe", "premium_low"]
    food_lo = df.loc["food", "premium_low"]
    # ratio 가 둘 다 1 이면 면적·업종보정 비만 남는다: (44.0×0.85) / (51.7×1.0)
    assert abs(cafe_lo / food_lo - (44.0 * 0.85) / 51.7) < 0.01


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

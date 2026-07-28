"""대표면적 재도출 회귀 테스트 (이슈 #152).

`cost.REPRESENTATIVE_AREA_M2` 는 인허가 원천에서 나온 값이지 사람이 고른 숫자가 아니다.
그런데 상수와 원천 사이에 코드 경로가 없어서, 한번 어긋난 뒤로 아무도 몰랐다 — 상수는
인허가 **대장 구분**(휴게 29.3·일반 55.4)으로 산출돼 있었고 주석·문서는 `category`
중앙값이라고 적고 있었다. 그 둘을 잇는 것이 이 파일이다.

원천 CSV(`data/interim/permits/`)는 저장소에 없다(.gitignore) — CI 에서는 건너뛰고,
원천을 가진 로컬·재현 환경에서만 실제로 대조한다.
"""
import pandas as pd
import pytest

from batch.collect.permits import parse_area_m2, representative_area_m2
from batch.paths import INTERIM_DIR
from batch.preprocess.cost import REPRESENTATIVE_AREA_M2

PERMITS_DIR = INTERIM_DIR / "permits"
needs_permits = pytest.mark.skipif(
    not (PERMITS_DIR / "general_live_classified.csv").exists(),
    reason="인허가 분류본 없음 (raw 는 로컬 보존) — `python -m batch.collect permits` 후 실행",
)


def test_parse_area_strips_thousands_separator():
    """`1,496.01` 을 결측으로 흘리면 대형 점포만 골라 떨어뜨려 중앙값이 내려간다.

    실측 283건이 이 형태이고, 쉼표가 붙는다는 건 네 자리 이상이라는 뜻이라 탈락분이
    전부 분포 위쪽이다. 음식점 중앙값이 51.52(쉼표 미처리) vs 51.69(처리)로 갈린다.
    """
    parsed = parse_area_m2(pd.Series(["1,496.01", "29.2", "", "0", None]))
    assert parsed.tolist()[:2] == [1496.01, 29.2]
    assert parsed.isna().tolist() == [False, False, True, False, True]


@needs_permits
def test_representative_area_matches_constant():
    """상수 = 원천 재도출값. 어긋나면 적재본 3,300행이 원천과 다른 면적으로 서 있다는 뜻이다."""
    assert representative_area_m2() == REPRESENTATIVE_AREA_M2


@needs_permits
def test_representative_area_is_category_not_permit_ledger():
    """대장 구분 중앙값과는 **달라야** 한다 — 옛 상수로 되돌아가는 것을 막는 못 (이슈 #152).

    휴게음식점 대장에는 카페가 아닌 업태가 13,624건 섞여 있고 일반음식점 대장에 카페로
    신고된 1,269건은 빠지므로, 두 축은 같은 값이 될 수 없다. 같아졌다면 분류가 아니라
    대장으로 나눈 것이다.
    """
    ledger = {}
    for suffix in ("general", "rest"):
        frame = pd.read_csv(
            PERMITS_DIR / f"{suffix}_live_classified.csv", dtype=str, low_memory=False
        )
        area = parse_area_m2(frame["소재지면적"])
        ledger[suffix] = float(area[area > 0].median())

    assert round(ledger["rest"], 1) == 29.3      # 휴게 대장 — 옛 카페 상수 29.2 의 출처
    assert round(ledger["general"], 1) == 55.4   # 일반 대장 — 옛 음식점 상수 55.2 의 출처
    assert REPRESENTATIVE_AREA_M2["cafe"] != round(ledger["rest"], 1)
    assert REPRESENTATIVE_AREA_M2["food"] != round(ledger["general"], 1)

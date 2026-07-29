"""경쟁밀도 점-폴리곤 조인 — 중첩 상권 귀속 규칙 (등재 #99). 합성 도형이라 실데이터 불필요."""
import geopandas as gpd
import pytest
from shapely.geometry import Point, box

from batch.preprocess import competition


def _areas():
    """겹치는 상권 2개. `area_code` 사전순으로 뒤에 오는 쪽(Z-BIG)이 더 크다.

    종전 구현은 `sort_values("area_code")` + `duplicated(keep="first")` 로 업소를
    사전순 앞선 상권에만 넣어, 뒤에 오는 상권의 인허가를 0건으로 만들었다.
    """
    return gpd.GeoDataFrame(
        {
            "area_code": ["A-SMALL", "Z-BIG"],
            "name": ["작은상권", "큰상권"],
            "sigungu_name": ["테스트구", "테스트구"],
            # 면적은 밀도 분모로만 쓰인다 — 1만㎡ 당 환산이 눈으로 확인되는 값을 쓴다.
            "area_m2": [10_000.0, 20_000.0],
            "geometry": [box(0, 0, 2, 2), box(1, 0, 5, 2)],
        },
        crs="EPSG:4326",
    )


def _permits(points):
    return gpd.GeoDataFrame(
        {"category": ["cafe"] * len(points), "geometry": [Point(*p) for p in points]},
        crs="EPSG:4326",
    )


def test_overlap_counts_store_for_both_areas():
    """교집합(1<x<2) 안의 업소는 두 상권 **모두**의 밀도에 들어간다."""
    out = competition.build(_areas(), _permits([(1.5, 1.0)]))
    counts = dict(zip(out["area_code"], out["permit_store_cnt"]))
    assert counts == {"A-SMALL": 1, "Z-BIG": 1}


def test_larger_area_code_is_not_starved():
    """사전순 뒤 상권이 자기 폴리곤 안의 업소를 잃지 않는다 (원 버그의 회귀 방지)."""
    # 교집합 2건 + Z-BIG 단독 구역 1건.
    out = competition.build(_areas(), _permits([(1.2, 1.0), (1.8, 1.0), (4.0, 1.0)]))
    counts = dict(zip(out["area_code"], out["permit_store_cnt"]))
    assert counts["Z-BIG"] == 3
    assert counts["A-SMALL"] == 2


def test_density_is_normalized_per_own_area():
    """밀도 = 제 폴리곤 업소 수 ÷ 제 면적 × 10,000 — 상권마다 독립이다."""
    out = competition.build(_areas(), _permits([(1.5, 1.0)]))
    dens = dict(zip(out["area_code"], out["store_per_10k_m2"]))
    assert dens["A-SMALL"] == pytest.approx(1.0)   # 1 / 10,000 × 10,000
    assert dens["Z-BIG"] == pytest.approx(0.5)     # 1 / 20,000 × 10,000


def test_permit_outside_every_polygon_is_dropped():
    """어느 폴리곤에도 안 들어가는 업소는 어느 상권 밀도에도 잡히지 않는다."""
    out = competition.build(_areas(), _permits([(9.0, 9.0)]))
    assert out.empty

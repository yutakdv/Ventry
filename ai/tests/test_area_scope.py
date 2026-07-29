"""상권·구획 경계 산출물 회귀 테스트 (가정 #95·#96).

이 산출물은 **커밋된 파일이 유일 원천**이다 — 원천 SHP 는 `ai/data/raw/`(gitignore)라
CI·심사 클론 환경에서 재생성할 수 없다. 그래서 재생성 가능 여부가 아니라 **커밋된 파일
자체가 계약을 지키는지**를 본다.

geopandas 는 ai-ci 에 설치되지 않으므로(pytest·pandas·requests 만) 원천 대조가 필요한
검사만 importorskip 으로 가른다 — 파일 자체 검사는 CI 에서도 그대로 돈다.
"""
import json

import pytest

from batch.load.area_geojson import COORD_PRECISION, OUT_PATH, SCHEMA, SIMPLIFY_M

# 서울 경계 여유 포함 bbox — preprocess/crs.py SEOUL_BBOX 와 같은 값
SEOUL_BBOX = (126.73, 37.41, 127.28, 37.72)

needs_artifact = pytest.mark.skipif(
    not OUT_PATH.exists(),
    reason=f"경계 산출물 없음 ({OUT_PATH.name}) — `make geo` 후 실행",
)


@pytest.fixture(scope="module")
def scope():
    return json.loads(OUT_PATH.read_text(encoding="utf-8"))


@needs_artifact
def test_header_is_the_contract_frontend_checks(scope):
    """FE 로더가 이 세 값으로 파일을 신뢰할지 판단한다 — 바뀌면 화면이 조용히 꺼진다."""
    assert scope["schema"] == SCHEMA
    assert scope["crs"] == "WGS84"
    assert scope["simplify_tolerance_m"] == int(SIMPLIFY_M)
    assert scope["coord_precision"] == COORD_PRECISION
    # 경계 판본과 통계 판본이 다르다는 사실을 화면 캡션이 읽어 간다 (가정 #97 주변 서술).
    assert set(scope["as_of"]) == {"areas", "districts"}


@needs_artifact
def test_counts_match_sources(scope):
    """1,650 상권 · 서울 72 구획. 수가 틀리면 조인이 조용히 비는 상권이 생긴다."""
    assert len(scope["areas"]) == 1650
    assert len(scope["districts"]) == 72


@needs_artifact
def test_rings_are_closed_and_drawable(scope):
    """카카오 `Polygon` 에 그대로 넘길 수 있는 형태인지 — 열린 링·3점 링은 그리다 깨진다."""
    for bucket in ("areas", "districts"):
        for key, entry in scope[bucket].items():
            assert entry["a"] > 0, f"{bucket}/{key} 면적이 0 이하"
            assert entry["r"], f"{bucket}/{key} 링 없음"
            for ring in entry["r"]:
                assert len(ring) >= 4, f"{bucket}/{key} 링 정점 {len(ring)}개"
                assert ring[0] == ring[-1], f"{bucket}/{key} 링 미폐합"


@needs_artifact
def test_coordinates_are_wgs84_inside_seoul(scope):
    """좌표계 사고(5181 값이 그대로 새는 것)를 잡는 가장 싼 검사다 (스펙 §0-4)."""
    lng_min, lat_min, lng_max, lat_max = SEOUL_BBOX
    for bucket in ("areas", "districts"):
        for key, entry in scope[bucket].items():
            for ring in entry["r"]:
                for lng, lat in ring:
                    assert lng_min <= lng <= lng_max, f"{bucket}/{key} lng {lng}"
                    assert lat_min <= lat <= lat_max, f"{bucket}/{key} lat {lat}"


@needs_artifact
def test_coordinates_are_rounded_as_declared(scope):
    """선언한 정밀도보다 긴 좌표가 섞이면 파일만 커지고 고지 문구가 거짓이 된다."""
    for bucket in ("areas", "districts"):
        for entry in scope[bucket].values():
            for ring in entry["r"]:
                for coord in ring:
                    for v in coord:
                        assert round(v, COORD_PRECISION) == v


@needs_artifact
def test_area_join_keys_cover_the_serving_dump(scope):
    """`area_code` 조인이 한 건이라도 비면 그 상권만 경계가 안 나온다 — 전건 대조한다."""
    import re

    from batch.paths import REPO_ROOT

    dump = REPO_ROOT / "db" / "init" / "10_data_core.sql"
    if not dump.exists():
        pytest.skip("적재 덤프 없음")
    sql = dump.read_text(encoding="utf-8")
    block = re.search(
        r"INSERT INTO commercial_area\s*\([^)]*\)\s*VALUES\s*\n(.*?);\s*\n", sql, re.S
    )
    codes = set(re.findall(r"^\s*\('(\d+)'", block.group(1), re.M))
    assert codes, "덤프에서 area_code 를 못 읽었다"
    assert codes <= set(scope["areas"]), f"경계 없는 상권 {sorted(codes - set(scope['areas']))[:5]}"


@needs_artifact
def test_simplification_stays_within_declared_error(scope):
    """가정 #95 가 고지한 오차(면적 p95 3% 이내)를 산출물이 실제로 지키는지 원천과 대조한다."""
    pytest.importorskip("geopandas", reason="원천 대조는 배치 환경에서만")
    import numpy as np

    from batch.preprocess.crs import CRS_METRIC, load_area_polygons

    src = load_area_polygons().to_crs(epsg=CRS_METRIC)
    baked = np.array([scope["areas"][str(c)]["a"] for c in src["area_code"]], dtype=float)
    err = np.abs(baked - src.geometry.area.to_numpy()) / src.geometry.area.to_numpy() * 100
    assert np.percentile(err, 95) < 3.0, f"면적 오차 p95 {np.percentile(err, 95):.3f}%"

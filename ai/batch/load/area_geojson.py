"""상권·부동산원 구획 경계 정적 산출 (가정 #95·#96).

**지도 표시 전용이다.** 판정·점수·비용은 기존 결정적 경로 그대로이며 이 산출물은 그 계산에
일절 참여하지 않는다. 화면이 쓰는 것은 두 가지뿐이다 — 윤곽선을 그릴 좌표, 그리고 근거
문장에 들어갈 면적(㎡).

산출 경로가 `db/init` 이 아니라 **`frontend/public/geo/`** 인 이유:

- 경계는 세션·업종·예산과 무관한 **불변** 자산이라 API 응답에 실을 이유가 없다. 계약
  `areas[]` 에 넣으면 슬라이더를 움직일 때마다 같은 폴리곤이 재전송된다.
- Postgres 에 PostGIS 가 없어 geometry 타입을 못 쓰고, `v_candidate_area` 의 그레인이
  area_code×industry 라 뷰에 붙이면 폴리곤이 업종 수만큼 중복된다.
- 원천 SHP 는 `ai/data/raw/` 라 gitignore 이고 compose 는 batch 를 실행 경로에서 제외한다
  → **커밋된 이 산출물이 CI·심사 클론 환경의 유일 원천**이다.

좌표는 WGS84(스펙 §0-4), 단순화 허용 오차 5m 는 가정 #95 에서 실측으로 확정했다.
"""
from __future__ import annotations

import json

import geopandas as gpd

from batch.paths import REPO_ROOT, logger
from batch.preprocess.crs import CRS_METRIC, CRS_WGS84, load_area_polygons, load_reb_districts

OUT_PATH = REPO_ROOT / "frontend" / "public" / "geo" / "area-scope.v1.json"

SCHEMA = "ventry.area-scope.v1"
SIMPLIFY_M = 5.0  # 가정 #95 — 하우스도르프 최대 8.08m
COORD_PRECISION = 5  # ≈1.1m. 단순화(5m)보다 촘촘해 추가 왜곡을 만들지 않는다.

# 원천 판본. 통계(2026-1Q)와 판본이 다르다는 사실을 화면 캡션에도 함께 적는다.
AS_OF = {"areas": "2023-10-20", "districts": "2024-10-31"}


def _rings(geom) -> list[list[list[float]]]:
    """Polygon/MultiPolygon 을 링 배열 하나로 평탄화한다.

    카카오맵 `Polygon` 은 단일 path 만 받으므로 MultiPolygon 89건은 프론트가 링별로 각각
    인스턴스를 만든다. 외곽·구멍을 구분하지 않고 같은 배열에 담는 이유는, 이번 표시가
    **채움 없는 윤곽선**이라 구멍의 의미(fill-rule)가 필요 없기 때문이다.
    """
    parts = geom.geoms if geom.geom_type == "MultiPolygon" else [geom]
    out: list[list[list[float]]] = []
    for part in parts:
        for ring in [part.exterior, *part.interiors]:
            pts: list[list[float]] = []
            for x, y in ring.coords:
                p = [round(x, COORD_PRECISION), round(y, COORD_PRECISION)]
                # 반올림이 만든 연속 중복점을 제거한다 (좌표 수를 줄이려는 게 아니라,
                # 같은 점이 이어지면 SVG path 에 길이 0 세그먼트가 생겨서다).
                if not pts or pts[-1] != p:
                    pts.append(p)
            if len(pts) >= 3 and pts[0] != pts[-1]:
                pts.append(pts[0])
            if len(pts) >= 4:  # 닫힌 링의 최소 점 수
                out.append(pts)
    return out


def _simplify(gdf: gpd.GeoDataFrame) -> gpd.GeoSeries:
    """미터 투영에서 단순화한 뒤 WGS84 로 되돌린다.

    위경도에서 바로 simplify 하면 허용 오차의 단위가 도(degree)라 위도에 따라 실제 거리가
    달라진다 — 거리 계산을 5179 에서 하는 `preprocess/crs.py` 와 같은 이유다.
    """
    return (
        gdf.to_crs(epsg=CRS_METRIC)
        .geometry.simplify(SIMPLIFY_M, preserve_topology=True)
        .to_crs(epsg=CRS_WGS84)
    )


def _pack(gdf: gpd.GeoDataFrame, key_col: str, area_col: str | None) -> dict[str, dict]:
    """키 → {면적 ㎡, 링 배열} 사전.

    면적은 **단순화 전** 투영 면적을 쓴다. 근거 문장의 배수가 표시용 왜곡을 타면 안 된다.
    """
    metric_area = gdf.to_crs(epsg=CRS_METRIC).geometry.area
    simplified = _simplify(gdf)
    out: dict[str, dict] = {}
    for idx, key in gdf[key_col].items():
        key = str(key)
        if not key or key in out:
            continue
        rings = _rings(simplified.loc[idx])
        if not rings:
            continue
        area = gdf.at[idx, area_col] if area_col else metric_area.loc[idx]
        out[key] = {"a": int(round(float(area))), "r": rings}
    return out


def run() -> None:
    areas = _pack(load_area_polygons(), "area_code", None)
    districts = _pack(load_reb_districts(), "reb_district_name", "reb_area_m2")

    payload = {
        "schema": SCHEMA,
        "crs": "WGS84",
        "as_of": AS_OF,
        "simplify_tolerance_m": int(SIMPLIFY_M),
        "coord_precision": COORD_PRECISION,
        "areas": areas,
        "districts": districts,
    }
    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUT_PATH.write_text(
        json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    size_kb = OUT_PATH.stat().st_size / 1024
    logger.info(
        "→ %s (상권 %d · 구획 %d · %.0f KB)", OUT_PATH, len(areas), len(districts), size_kb
    )

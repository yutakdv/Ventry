"""[3단계] 상권 대표점 → 최근접 지하철역 (AI-04d, 스펙 §3-1).

`sjoin_nearest`로 최근접 역과 거리 d를 구하고, 환승역 정규명 기준 일평균 승하차 V를 붙인다.
접근성 성분 `V × exp(−d/500m)` 산출은 AI-05 소관 — 여기서는 재료(d·V·역명·호선)까지다.

거리는 위경도로 재면 안 되므로 미터 투영(EPSG:5179)에서 계산하고 결과 m만 들고 나온다.
저장 좌표계는 변함없이 WGS84다 (스펙 §0-4).

후퇴: 최근접 역이 없거나 승하차가 0이면 **접근성 성분 0 + `fallback_flag`** —
w1의 나머지 두 성분으로 점수 산출이 계속돼 서비스가 멈추지 않는다.

검증: 대표역 3곳의 최근접 판정·환승역 합산치 대조.
"""
from __future__ import annotations

import geopandas as gpd
import pandas as pd

from batch.paths import INTERIM_DIR, logger
from batch.preprocess.crs import CRS_METRIC, load_area_polygons, load_stations

OUT_PATH = INTERIM_DIR / "join" / "transit_assignment.csv"
VERIFY_AREAS = ("망원역 1번", "강남역", "홍대입구역 3번")
MAX_DISTANCE_M = 3000  # 이보다 멀면 역세권으로 보지 않는다 (도보 30분 초과)


def build(areas: gpd.GeoDataFrame, stations: gpd.GeoDataFrame) -> pd.DataFrame:
    points = areas[["area_code", "name", "sigungu_name", "geometry"]].copy()
    points["geometry"] = areas.geometry.representative_point()

    metric_points = points.to_crs(epsg=CRS_METRIC)
    metric_stations = stations.to_crs(epsg=CRS_METRIC)
    joined = gpd.sjoin_nearest(
        metric_points, metric_stations, how="left", distance_col="distance_m"
    )
    joined = joined[~joined.index.duplicated(keep="first")]

    out = joined[
        ["area_code", "name", "sigungu_name", "station_name", "line",
         "daily_riders", "distance_m"]
    ].copy()
    out["distance_m"] = out["distance_m"].round().astype("Int64")
    out["daily_riders"] = out["daily_riders"].fillna(0).astype("Int64")

    too_far = out["distance_m"].isna() | (out["distance_m"] > MAX_DISTANCE_M)
    no_riders = out["daily_riders"].fillna(0) <= 0
    out["fallback_flag"] = too_far | no_riders
    out.loc[too_far, "fallback_reason"] = f"최근접 역 {MAX_DISTANCE_M}m 초과"
    out.loc[no_riders & ~too_far, "fallback_reason"] = "승하차 미매칭"
    # 폴백 상권은 접근성 성분을 0으로 두되 역명·거리는 근거 패널용으로 남긴다.
    logger.info(
        "최근접 역 조인: 상권 %d개 · 중앙값 %.0fm · 폴백 %d개 (거리초과 %d · 승하차미매칭 %d)",
        len(out), out["distance_m"].dropna().median(), int(out["fallback_flag"].sum()),
        int(too_far.sum()), int((no_riders & ~too_far).sum()),
    )
    return out.rename(columns={"station_name": "nearest_station"}).sort_values("area_code")


def verify(assignment: pd.DataFrame) -> None:
    """검증: 대표 상권 3곳의 최근접 역·거리·일평균 승하차 대조."""
    logger.info("── 검증(3) 대표역 3곳 대조 ──")
    for keyword in VERIFY_AREAS:
        rows = assignment[assignment["name"].str.contains(keyword, na=False)].head(3)
        if rows.empty:
            logger.warning("  '%s' 상권 없음", keyword)
            continue
        for _, r in rows.iterrows():
            logger.info(
                "  %s(%s) → %s(%s) %sm · 일평균 승하차 %s%s",
                r["name"], r["area_code"], r["nearest_station"], r["line"],
                r["distance_m"], f"{int(r['daily_riders']):,}",
                " ⚠️폴백" if r["fallback_flag"] else "",
            )


def run() -> pd.DataFrame:
    assignment = build(load_area_polygons(), load_stations())
    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    assignment.to_csv(OUT_PATH, index=False, encoding="utf-8-sig")
    logger.info("저장: %s (%d행)", OUT_PATH.relative_to(INTERIM_DIR.parent), len(assignment))
    verify(assignment)
    return assignment

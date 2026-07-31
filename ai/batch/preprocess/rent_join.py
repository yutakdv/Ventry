"""[2단계] 상권 대표점 → 부동산원 구획 = 임대료 할당 (AI-04c, 스펙 §3-1).

상권 폴리곤의 대표점을 부동산원 상권 구획(서울 72개)에 조인해 어느 부동산원 상권의
임대료를 쓸지 정한다. 실제 임대료 수치 결합은 AI-05 소관이고, 여기서는 **할당 등급과
폴백 판정**까지다.

해상도 혼합은 설계 의도다 (스펙 §2-1): 입지 변별은 상권 단위 매출·수요가, 임대 수준은
광역 상권 단위가 담당한다. 화면 라벨 "한국부동산원 ○○상권 분기 평균 (추정)"이 강제된다.

**할당 5등급** (팀 결정 2026-07-21, docs/assumptions.md #18):
  exact      대표점이 구획 내부                          → 해당 상권 임대료
  approx     구획 밖이지만 최근접 구획이 500m 이내        → 해당 상권 임대료 (근사 표기)
  dong       같은 **행정동**에 구획이 있음                → 그 구획 임대료 (생활권 동일)
  gu_avg     그 외 — 자치구 내 구획들의 평균              → fallback_flag
  region_avg 자치구에 구획이 하나도 없음(성북·양천)       → 서울 권역 평균, fallback_flag

구획 내부만 인정하면 폴백이 77.0%(1,650 − 379 = 1,271곳)가 되어 임대료 변별력이 25개 자치구
값으로 무너진다.
최근접 구획까지 거리 중앙값이 380m라 500m(도보 5~7분)는 같은 임대 시장으로 볼 수 있다.

`dong` 등급은 거리 임계를 늘리는 대신 **기준을 바꿔** 얻은 것이다 — 행정동은 생활권 단위라
"같은 임대 시장"이라는 근거가 단순 거리보다 강하다. 폴리곤 면적 중첩도 검토했으나, 조금이라도
겹치는 상권은 이미 전부 500m 안에 들어와 있어 추가로 건지는 상권이 0개였다.

대표점은 centroid가 아니라 `representative_point()`를 쓴다 — 오목하거나 MultiPolygon인
상권(89개)에서 centroid가 폴리곤 밖으로 나가 조인이 통째로 어긋난다.

검증: 부동산원 상권 3곳 수작업 대조.
"""
from __future__ import annotations

import geopandas as gpd
import pandas as pd

from batch.paths import INTERIM_DIR, logger
from batch.preprocess.crs import (
    CRS_METRIC,
    load_area_polygons,
    load_reb_districts,
    load_reb_regions,
    normalize_district,
)

OUT_PATH = INTERIM_DIR / "join" / "rent_assignment.csv"
APPROX_MAX_M = 500  # 근사 할당 허용 거리 (도보 5~7분)
DEFAULT_REGION = "기타"  # R-ONE 서울 권역: 도심 / 강남 / 영등포신촌 / 기타
VERIFY_AREAS = ("망원역 1번", "강남역", "홍대입구역 3번")


def _region_by_sigungu(
    areas: gpd.GeoDataFrame, districts: gpd.GeoDataFrame, regions: dict[str, str]
) -> dict[str, str]:
    """자치구 → R-ONE 권역. 자치구 안의 구획들이 속한 권역의 최빈값으로 정한다."""
    points = districts.copy()
    points["geometry"] = districts.geometry.representative_point()
    hit = gpd.sjoin(
        points[["reb_district_name", "geometry"]],
        areas[["sigungu_name", "geometry"]],
        how="left", predicate="within",
    )
    hit = hit[~hit.index.duplicated(keep="first")].dropna(subset=["sigungu_name"])
    hit["region"] = hit["reb_district_name"].map(
        lambda n: regions.get(normalize_district(n), DEFAULT_REGION)
    )
    mode = hit.groupby("sigungu_name")["region"].agg(
        lambda s: s.mode().iat[0] if not s.mode().empty else DEFAULT_REGION
    )
    return mode.to_dict()


def _district_by_dong(
    areas: gpd.GeoDataFrame, districts: gpd.GeoDataFrame
) -> pd.Series:
    """행정동 코드 → 그 행정동에 걸친 부동산원 구획(최빈값).

    구획 폴리곤과 상권 폴리곤을 겹쳐 각 행정동이 어느 구획에 닿는지 본다.
    """
    overlap = gpd.overlay(
        areas[["adstrd_code", "geometry"]],
        districts[["reb_district_name", "geometry"]],
        how="intersection", keep_geom_type=True,
    )
    if overlap.empty:
        return pd.Series(dtype=object)
    return overlap.groupby("adstrd_code")["reb_district_name"].agg(
        lambda s: s.mode().iat[0] if not s.mode().empty else None
    )


def build(
    areas: gpd.GeoDataFrame, districts: gpd.GeoDataFrame, regions: dict[str, str]
) -> pd.DataFrame:
    metric_areas = areas.to_crs(epsg=CRS_METRIC)
    points = metric_areas[
        ["area_code", "name", "sigungu_name", "adstrd_code", "geometry"]
    ].copy()
    points["geometry"] = metric_areas.geometry.representative_point()
    metric_districts = districts.to_crs(epsg=CRS_METRIC)
    dong_district = _district_by_dong(metric_areas, metric_districts)

    # 내부 포함과 최근접을 한 번에 얻는다 — 내부면 거리 0이 나온다.
    joined = gpd.sjoin_nearest(
        points, metric_districts[["reb_district_name", "geometry"]],
        how="left", distance_col="distance_m",
    )
    joined = joined[~joined.index.duplicated(keep="first")]

    out = joined[
        ["area_code", "name", "sigungu_name", "adstrd_code", "reb_district_name", "distance_m"]
    ].copy()
    out["distance_m"] = out["distance_m"].round().astype("Int64")
    gu_region = _region_by_sigungu(areas, districts, regions)
    gu_has_district = set(gu_region)

    inside = out["distance_m"] == 0
    approx = (~inside) & (out["distance_m"] <= APPROX_MAX_M)
    dong_hit = out["adstrd_code"].map(dong_district)
    same_dong = (~inside) & (~approx) & dong_hit.notna()
    gu_ok = out["sigungu_name"].isin(gu_has_district)

    out["assign_level"] = "region_avg"
    out.loc[gu_ok, "assign_level"] = "gu_avg"
    out.loc[same_dong, "assign_level"] = "dong"
    out.loc[approx, "assign_level"] = "approx"
    out.loc[inside, "assign_level"] = "exact"
    # 행정동 등급은 최근접 구획이 아니라 '그 행정동에 걸친 구획'을 쓴다.
    out.loc[same_dong, "reb_district_name"] = dong_hit[same_dong]
    out["fallback_flag"] = out["assign_level"].isin(("gu_avg", "region_avg"))

    # 폴백 행은 특정 구획을 쓰지 않으므로 상권명을 비운다 (오해 방지).
    out.loc[out["fallback_flag"], "reb_district_name"] = ""
    out.loc[out["fallback_flag"], "distance_m"] = pd.NA
    out["reb_region"] = out.apply(
        lambda r: regions.get(normalize_district(r["reb_district_name"]))
        or gu_region.get(r["sigungu_name"], DEFAULT_REGION),
        axis=1,
    )

    counts = out["assign_level"].value_counts()
    logger.info(
        "임대료 할당 %d개: exact %d · approx(≤%dm) %d · dong %d · gu_avg %d · region_avg %d "
        "→ 폴백 %d개(%.1f%%)",
        len(out), counts.get("exact", 0), APPROX_MAX_M, counts.get("approx", 0),
        counts.get("dong", 0), counts.get("gu_avg", 0), counts.get("region_avg", 0),
        int(out["fallback_flag"].sum()), 100 * out["fallback_flag"].mean(),
    )
    no_district_gu = sorted(set(out.loc[out["assign_level"] == "region_avg", "sigungu_name"]))
    if no_district_gu:
        logger.info("  구획 없는 자치구 → 서울 권역 평균: %s", ", ".join(no_district_gu))
    return out.sort_values("area_code")


def verify(assignment: pd.DataFrame) -> None:
    """검증: 대표 상권 3곳의 부동산원 상권 할당이 지리적으로 타당한지 대조."""
    logger.info("── 검증(2) 부동산원 상권 3곳 수작업 대조 ──")
    for keyword in VERIFY_AREAS:
        rows = assignment[assignment["name"] == keyword]
        if rows.empty:
            rows = assignment[assignment["name"].str.contains(keyword, na=False)].head(1)
        if rows.empty:
            logger.warning("  '%s' 상권 없음", keyword)
            continue
        r = rows.iloc[0]
        target = r["reb_district_name"] or f"{r['sigungu_name']}/{r['reb_region']} 평균"
        distance = "" if pd.isna(r["distance_m"]) else f" ({r['distance_m']}m)"
        logger.info(
            "  %s(%s, %s) → %s%s [%s]",
            r["name"], r["area_code"], r["sigungu_name"], target, distance, r["assign_level"],
        )


def run() -> pd.DataFrame:
    assignment = build(load_area_polygons(), load_reb_districts(), load_reb_regions())
    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    assignment.to_csv(OUT_PATH, index=False, encoding="utf-8-sig")
    logger.info("저장: %s (%d행)", OUT_PATH.relative_to(INTERIM_DIR.parent), len(assignment))
    verify(assignment)
    return assignment

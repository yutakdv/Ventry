"""좌표계 통일 + 조인 입력 로더 (AI-04a, 스펙 §3-1).

소스별 원본 좌표계가 셋으로 갈린다 (docs/assumptions.md #3 실측):

| 소스 | 원본 CRS | 비고 |
|---|---|---|
| 인허가 CSV `좌표정보(X/Y)` | EPSG:5174 | Bessel 중부원점TM |
| 부동산원 상권 구획도 SHP    | EPSG:5179 | Korea 2000 UTM-K, 전국 368 → 서울 72 |
| 서울 상권영역 SHP·API      | EPSG:5181 | 1,650 폴리곤 (assumptions #17) |
| 지하철 역사마스터           | WGS84     | LAT/LOT 직접 제공 (assumptions #14) |

**저장 좌표계는 전부 WGS84** (프론트 계약, 스펙 §0-4). 다만 거리 계산만은 위경도로 하면
안 되므로 미터 단위 투영(EPSG:5179)으로 잠깐 바꿔 계산하고 결과(m)만 들고 나온다.
"""
from __future__ import annotations

import json

import geopandas as gpd
import pandas as pd

from batch.paths import INTERIM_DIR, RAW_DIR, logger

CRS_PERMIT = 5174
CRS_REB_SHP = 5179
CRS_SEOUL_AREA = 5181
CRS_WGS84 = 4326
CRS_METRIC = 5179  # 거리 계산 전용 (미터)

# 서울 경계 여유 포함 bbox (lng_min, lat_min, lng_max, lat_max) — 변환 검증용
SEOUL_BBOX = (126.73, 37.41, 127.28, 37.72)

AREA_SHP = RAW_DIR / "서울상권영역_SHP" / "서울상권영역.shp"
REB_SHP = RAW_DIR / "부동산원_상권구획도_SHP" / "최종상권368.shp"
STATIONS_JSON = RAW_DIR / "transit" / "stations_subwayStationMaster.json"
RIDERS_JSON = INTERIM_DIR / "transit" / "riders_by_station.json"
PERMIT_CSVS = (
    INTERIM_DIR / "permits" / "general_live_classified.csv",
    INTERIM_DIR / "permits" / "rest_live_classified.csv",
)


def to_wgs84(gdf: gpd.GeoDataFrame, source_epsg: int) -> gpd.GeoDataFrame:
    """원본 CRS를 명시적으로 세팅한 뒤 WGS84로 변환한다.

    선언 CRS를 신뢰하지 않고 항상 덮어쓴다 — SHP의 .prj가 실제와 다른 경우가 있어서다.
    """
    return gdf.set_crs(epsg=source_epsg, allow_override=True).to_crs(epsg=CRS_WGS84)


def bbox_report(gdf: gpd.GeoDataFrame, label: str) -> int:
    """WGS84 변환 결과가 서울 bbox 안에 드는지 보고하고, 벗어난 건수를 돌려준다."""
    lng_min, lat_min, lng_max, lat_max = SEOUL_BBOX
    centroid = gdf.geometry.representative_point()
    outside = ~centroid.x.between(lng_min, lng_max) | ~centroid.y.between(lat_min, lat_max)
    bounds = [round(v, 5) for v in gdf.total_bounds]
    logger.info("%s: %d건 bbox=%s 서울 밖 %d건", label, len(gdf), bounds, int(outside.sum()))
    return int(outside.sum())


def load_area_polygons() -> gpd.GeoDataFrame:
    """서울 상권 폴리곤 1,650개 (WGS84). 조인 키 `area_code`(TRDAR_CD)."""
    gdf = gpd.read_file(AREA_SHP, encoding="utf-8")
    gdf = to_wgs84(gdf, CRS_SEOUL_AREA)
    out = gdf.rename(
        columns={
            "TRDAR_CD": "area_code",
            "TRDAR_CD_N": "name",
            "TRDAR_SE_C": "area_type_code",
            "TRDAR_SE_1": "area_type_name",
            "SIGNGU_CD": "sigungu_code",
            "SIGNGU_CD_": "sigungu_name",
            "ADSTRD_CD": "adstrd_code",
            "ADSTRD_CD_": "adstrd_name",
            "RELM_AR": "area_m2",
        }
    )[
        [
            "area_code", "name", "area_type_code", "area_type_name",
            "sigungu_code", "sigungu_name", "adstrd_code", "adstrd_name",
            "area_m2", "geometry",
        ]
    ]
    bbox_report(out, "상권 폴리곤(5181→WGS84)")
    return out


def load_permits() -> gpd.GeoDataFrame:
    """영업중 인허가 업소 포인트 (WGS84). 카페·음식점만, 좌표 결측·범위 이탈 제외."""
    frames = []
    for path in PERMIT_CSVS:
        if not path.exists():
            logger.warning("인허가 interim 없음: %s — `python -m batch.collect permits` 먼저", path)
            continue
        frames.append(
            pd.read_csv(
                path, dtype=str, low_memory=False,
                usecols=["좌표정보(X)", "좌표정보(Y)", "category", "업태구분명", "사업장명"],
            )
        )
    if not frames:
        raise SystemExit("인허가 interim 없음 — 수집 단계(batch.collect permits) 먼저 실행")

    df = pd.concat(frames, ignore_index=True)
    df = df[df["category"].isin(("cafe", "food"))].copy()
    df["x"] = pd.to_numeric(df["좌표정보(X)"], errors="coerce")
    df["y"] = pd.to_numeric(df["좌표정보(Y)"], errors="coerce")
    before = len(df)
    df = df.dropna(subset=["x", "y"])
    logger.info("인허가 카페·음식점 %d건 중 좌표 유효 %d건", before, len(df))

    gdf = gpd.GeoDataFrame(
        df[["category", "업태구분명", "사업장명"]],
        geometry=gpd.points_from_xy(df["x"], df["y"]),
    )
    out = to_wgs84(gdf, CRS_PERMIT)
    bbox_report(out, "인허가 포인트(5174→WGS84)")
    return out


def load_reb_districts() -> gpd.GeoDataFrame:
    """부동산원 상권 구획 폴리곤 — 서울 72개 (WGS84).

    ⚠️ `지역코드`는 서울 전건 NULL이라 조인 키로 못 쓴다 → 상권명 문자열이 유일 키
    (docs/assumptions.md #12).
    """
    gdf = gpd.read_file(REB_SHP, encoding="cp949")
    seoul = gdf[gdf["시도코드"].astype(str) == "11"].copy()
    seoul = seoul.rename(columns={"상권명": "reb_district_name", "구역면적": "reb_area_m2"})
    out = to_wgs84(seoul[["reb_district_name", "reb_area_m2", "geometry"]], CRS_REB_SHP)
    bbox_report(out, "부동산원 구획(5179→WGS84)")
    return out


def normalize_district(name: str) -> str:
    """부동산원 상권명 정규화 — SHP `상권명` ↔ R-ONE `ITM_NM` 대조 키 (assumptions #12).

    SHP의 `지역코드`가 서울 전건 NULL이라 문자열이 유일한 조인 키다.
    """
    return str(name or "").replace("/", "").replace("·", "").replace(" ", "")


def load_reb_regions() -> dict[str, str]:
    """부동산원 상권명(정규화) → 권역 매핑. `ITM_FULLNM` = '서울>도심>광화문' 3단계."""
    mapping: dict[str, str] = {}
    for path in sorted((RAW_DIR / "reb_rent").glob("items_rent_*.json")):
        for item in json.loads(path.read_text(encoding="utf-8")):
            full = item.get("ITM_FULLNM") or ""
            parts = full.split(">")
            if len(parts) == 3 and parts[0] == "서울":
                mapping.setdefault(normalize_district(parts[2]), parts[1])
    if not mapping:
        logger.warning("R-ONE 권역 항목 없음 — `python -m batch.collect reb_rent` 먼저")
    return mapping


def load_stations() -> gpd.GeoDataFrame:
    """지하철역 포인트 (이미 WGS84) + 환승역 정규명 일평균 승하차 결합."""
    from batch.collect.transit import normalize_station

    stations = pd.DataFrame(json.loads(STATIONS_JSON.read_text(encoding="utf-8")))
    stations["lat"] = pd.to_numeric(stations["LAT"], errors="coerce")
    stations["lng"] = pd.to_numeric(stations["LOT"], errors="coerce")
    stations = stations.dropna(subset=["lat", "lng"])
    stations["station_key"] = stations["BLDN_NM"].map(normalize_station)

    riders = pd.DataFrame(json.loads(RIDERS_JSON.read_text(encoding="utf-8")))
    riders["daily_riders"] = (riders["on"] + riders["off"]).round().astype(int)
    merged = stations.merge(
        riders[["station", "daily_riders"]],
        left_on="station_key", right_on="station", how="left",
    )
    missing = int(merged["daily_riders"].isna().sum())
    if missing:
        logger.warning("승하차 미매칭 역 %d/%d개 — 접근성 규모 0으로 처리", missing, len(merged))
    merged["daily_riders"] = merged["daily_riders"].fillna(0).astype(int)

    gdf = gpd.GeoDataFrame(
        merged.rename(columns={"BLDN_NM": "station_name", "ROUTE": "line"})[
            ["station_name", "station_key", "line", "daily_riders"]
        ],
        geometry=gpd.points_from_xy(merged["lng"], merged["lat"]),
        crs=f"EPSG:{CRS_WGS84}",
    )
    logger.info("역 %d개 (승하차 결합 %d개)", len(gdf), len(gdf) - missing)
    return gdf

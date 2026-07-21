"""[1단계] 인허가 좌표 → 상권 폴리곤 = 경쟁밀도 (AI-04b, 스펙 §3-1).

영업중 인허가 업소를 상권 폴리곤에 점-폴리곤 조인해 상권×업종 업소 수를 센다.
폐업은 수집 단계에서 이미 제외했다(`영업상태명 == '영업/정상'`, assumptions #3) —
누적 CSV의 75~78%가 폐업이라 걸러내지 않으면 밀도가 통째로 왜곡된다.

밀도 정의: 면적 정규화 `업소 수 / (상권 면적 ㎡) × 10,000` (= 1만㎡당 업소 수).
w3 경쟁여유는 이 값의 역방향이며 정규화는 AI-05 소관 — 여기서는 원값까지만 만든다.

검증: 강남역 표본 육안 (스펙 §3-1 후퇴·검증 규칙).
"""
from __future__ import annotations

import geopandas as gpd
import pandas as pd

from batch.paths import INTERIM_DIR, logger
from batch.preprocess.crs import load_area_polygons, load_permits

OUT_PATH = INTERIM_DIR / "join" / "store_density.csv"
VERIFY_KEYWORD = "강남역"


def build(areas: gpd.GeoDataFrame, permits: gpd.GeoDataFrame) -> pd.DataFrame:
    """상권×업종 업소 수 + 면적 정규화 밀도."""
    joined = gpd.sjoin(permits, areas[["area_code", "geometry"]], how="left", predicate="within")
    # 상권 폴리곤 53쌍이 서로 겹쳐서(실측) 한 업소가 두 상권에 잡힌다 → 그대로 두면
    # 겹친 구역의 업소가 두 번 세어져 밀도가 부풀려진다. area_code 기준으로 하나만 남긴다.
    overlapped = int(joined.index.duplicated(keep=False).sum())
    joined = joined.sort_values("area_code")
    joined = joined[~joined.index.duplicated(keep="first")]

    matched = joined["area_code"].notna()
    logger.info(
        "점-폴리곤 조인: 업소 %d건 중 상권 내부 %d건 (%.1f%%) · 폴리곤 밖 %d건 "
        "(중첩 상권 이중 매칭 %d건은 1건으로 정리)",
        len(joined), int(matched.sum()), 100 * matched.mean(), int((~matched).sum()),
        overlapped,
    )

    counts = (
        joined[matched]
        .groupby(["area_code", "category"])
        .size()
        .rename("permit_store_cnt")
        .reset_index()
    )
    out = counts.merge(
        areas[["area_code", "name", "sigungu_name", "area_m2"]], on="area_code", how="left"
    )
    out["store_per_10k_m2"] = (
        out["permit_store_cnt"] / out["area_m2"].replace(0, pd.NA) * 10000
    ).round(3)
    return out.rename(columns={"category": "industry"})[
        ["area_code", "name", "sigungu_name", "industry",
         "permit_store_cnt", "area_m2", "store_per_10k_m2"]
    ].sort_values(["area_code", "industry"])


def verify(density: pd.DataFrame) -> None:
    """검증: 강남역 일대 상권의 업소 수·밀도가 상위권인지 육안 확인."""
    sample = density[density["name"].str.contains(VERIFY_KEYWORD, na=False)]
    if sample.empty:
        logger.warning("검증 표본 '%s' 상권 없음 — 상권명 확인 필요", VERIFY_KEYWORD)
        return
    logger.info("── 검증(1) %s 표본 ──", VERIFY_KEYWORD)
    for _, r in sample.iterrows():
        rank = (density[density["industry"] == r["industry"]]["permit_store_cnt"]
                > r["permit_store_cnt"]).sum() + 1
        total = (density["industry"] == r["industry"]).sum()
        logger.info(
            "  %s(%s) %s: %d개 · 1만㎡당 %.1f · 업소수 순위 %d/%d",
            r["name"], r["area_code"], r["industry"],
            r["permit_store_cnt"], r["store_per_10k_m2"] or 0, rank, total,
        )


def run() -> pd.DataFrame:
    density = build(load_area_polygons(), load_permits())
    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    density.to_csv(OUT_PATH, index=False, encoding="utf-8-sig")
    logger.info("저장: %s (%d행)", OUT_PATH.relative_to(INTERIM_DIR.parent), len(density))
    verify(density)
    return density

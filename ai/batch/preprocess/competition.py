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
    # 상권 폴리곤 53쌍이 서로 겹쳐서(실측) 한 업소가 두 상권에 잡힌다. **그 업소는 양쪽 모두에
    # 계상한다** — 밀도는 상권마다 「제 폴리곤 안의 업소 ÷ 제 면적」으로 독립 계산되는 값이라
    # 보존되어야 할 전역 합계가 없고, 명동 발달상권 안의 식당은 그 상권에 들어갈 사람에게도
    # 이를 품은 관광특구에 들어갈 사람에게도 똑같이 경쟁이기 때문이다 (가정 #99).
    #
    # 이전에는 `sort_values("area_code")` + `duplicated(keep="first")` 로 업소당 한 상권만
    # 남겼는데, 그 기준이 **area_code 사전순**이라 중첩 상권 중 코드가 작은 쪽이 인허가를 통째로
    # 가져갔다. 관광특구 3001492 가 발달상권 3120022(북창동)·3120026(을지로입구역)·
    # 3120028(명동거리)를 앞서 세 곳의 permit 이 0건 → 밀도 결측 → 폴백 0 → w3(경쟁여유)=1.0,
    # 즉 서울에서 가장 빽빽한 상권이 「경쟁 여유 최상위」로 뒤집혔다 (가정 #69 전제의 반증).
    # 중첩을 양쪽에 계상하면서부터 `joined` 는 업소가 아니라 **(업소 × 상권) 쌍**이다.
    # 로그는 계속 「업소」 단위로 보고해야 등재 #19·#69 가 인용하는 「폴리곤 내부 81.2%」와
    # 같은 자를 쓴다 — 쌍 수를 그대로 찍으면 분자만 부풀어 산술이 닫히지 않는다.
    matched = joined["area_code"].notna().to_numpy()
    total = joined.index.nunique()
    inside = joined.index[matched].nunique()
    overlapped = joined.index[joined.index.duplicated(keep=False)].nunique()
    logger.info(
        "점-폴리곤 조인: 업소 %d건 중 상권 내부 %d건 (%.1f%%) · 폴리곤 밖 %d건 "
        "· 내부 중 중첩 상권에 걸친 %d건은 양쪽 상권에 각각 계상 (조인 %d행)",
        total, inside, 100 * inside / total if total else 0.0,
        total - inside, overlapped, len(joined),
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

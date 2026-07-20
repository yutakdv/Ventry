"""부동산원 임대료·전환율 + 상권 구획도 SHP + 권리금 (AI-02, 스펙 §2-0).

- 임대료·전환율·공실률: R-ONE OpenAPI(SttsApiTbl.do). data.go.kr가 아니라 R-ONE에서
  발급한 키를 쓴다(변수명 DATA_GO_KR_API_KEY_REB_RENT, 값은 R-ONE 키) — assumptions.md #2.
  호출: {BASE}?KEY=&STATBL_ID=&Type=json&pIndex=&pSize=
  ⚠️ STATBL_ID(통계표 코드)는 R-ONE 개발가이드 기준 — 라이브 실행 전 확정(현재 미설정).
- 상권 구획도 SHP: 로컬 raw(부동산원_상권구획도_SHP/최종상권368.shp, EPSG:5179).
  전국 368폴리곤 → 서울(시도코드=='11') 72. 좌표변환·서울필터는 preprocess(AI-04).
- 권리금: R-ONE 연간 조사(전년 기준) — 화면 라벨 '연간 조사(전년 기준)' (assumptions.md #1).
"""
from __future__ import annotations

import requests

from batch.collect._common import (
    RAW_DIR,
    get_json,
    http_session,
    load_env,
    logger,
    require_key,
    save_json,
    setup_logging,
)

REB_BASE = "https://www.reb.or.kr/r-one/openapi/SttsApiTbl.do"

# 통계표 코드(STATBL_ID) — R-ONE 개발가이드에서 확정 필요. 빈 값은 수집 스킵.
STATBL: dict[str, str] = {
    "rent": "",  # 상업용부동산 임대료(중대형·소규모·집합)
    "convert": "",  # 전월세 전환율
    "vacancy": "",  # 공실률
    "premium": "",  # 권리금(연간 조사)
}

SHP_PATH = RAW_DIR / "부동산원_상권구획도_SHP" / "최종상권368.shp"


def _extract_rows(payload: object) -> list[dict]:
    """R-ONE 응답에서 'row' 리스트를 재귀 수집(통계표마다 최상위 키 상이)."""
    found: list[dict] = []

    def walk(node: object) -> None:
        if isinstance(node, dict):
            for key, value in node.items():
                if key == "row" and isinstance(value, list):
                    found.extend(item for item in value if isinstance(item, dict))
                else:
                    walk(value)
        elif isinstance(node, list):
            for item in node:
                walk(item)

    walk(payload)
    return found


def fetch_reb(
    session: requests.Session, key: str, statbl_id: str, *, page_size: int = 1000
) -> list[dict]:
    rows: list[dict] = []
    index = 1
    while True:
        url = (
            f"{REB_BASE}?KEY={key}&STATBL_ID={statbl_id}"
            f"&Type=json&pIndex={index}&pSize={page_size}"
        )
        batch = _extract_rows(get_json(session, url))
        if not batch:
            break
        rows.extend(batch)
        logger.info("  R-ONE %s: %d건 (pIndex=%d)", statbl_id, len(rows), index)
        if len(batch) < page_size:
            break
        index += 1
    return rows


def run(env: dict[str, str], session: requests.Session) -> None:
    key = require_key(env, "DATA_GO_KR_API_KEY_REB_RENT")
    for name, statbl_id in STATBL.items():
        if not statbl_id:
            logger.warning("REB %s STATBL_ID 미설정 — R-ONE 개발가이드 확정 후 수집", name)
            continue
        rows = fetch_reb(session, key, statbl_id)
        save_json(rows, RAW_DIR / "reb_rent" / f"{name}_{statbl_id}.json")

    if SHP_PATH.exists():
        logger.info("SHP 확인: %s (EPSG:5179 — preprocess에서 서울 필터·변환)", SHP_PATH.name)
    else:
        logger.warning("SHP 없음: %s — data.go.kr 15086933 수동 다운로드", SHP_PATH)


def main() -> None:
    setup_logging()
    run(load_env(), http_session())


if __name__ == "__main__":
    main()

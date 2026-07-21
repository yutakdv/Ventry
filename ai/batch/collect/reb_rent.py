"""부동산원 임대료·전환율·공실률·권리금 + 상권 구획도 SHP (AI-02c, 스펙 §2-0).

R-ONE(부동산통계정보시스템) OpenAPI. data.go.kr가 아니라 R-ONE에서 발급한 키를 쓴다
(변수명 `DATA_GO_KR_API_KEY_REB_RENT`, 값은 R-ONE 키 — docs/assumptions.md #2).

엔드포인트 3종 (2026-07-21 실측, assumptions.md #13):
- `SttsApiTbl.do`      통계표 목록 (738건 카탈로그)
- `SttsApiTblItm.do`   분류 항목 — 지역 계층 `시도 > 권역 > 상권` (`ITM_FULLNM`)
- `SttsApiTblData.do`  **실데이터** — `STATBL_ID` + `DTACYCLE_CD` 필수

응답 행: `WRTTIME_IDTFR_ID`(202403 = 2024년 3분기) · `CLS_NM`/`CLS_FULLNM`(지역) ·
`ITM_NM`(지표) · `DTA_VAL`(값) · `UI_NM`(단위, 임대료는 천원/㎡).

상가 유형은 **소규모 → 중대형 → 집합** 순으로 할당한다. 세 유형 합집합이 구획도
서울 72개 상권을 전건 커버한다 (미커버 0 — 리스크 #18 해소, assumptions.md #12).
권리금만 주기가 **매년**이라 화면 라벨이 다르다 ("연간 조사(전년 기준)", assumptions.md #1).

상권 구획도 SHP는 로컬 raw(`부동산원_상권구획도_SHP/최종상권368.shp`, EPSG:5179) —
전국 368폴리곤 중 서울(`시도코드=='11'`) 72개. 좌표변환·서울필터는 preprocess(AI-04).
⚠️ SHP의 `지역코드`는 서울 전건 NULL이라 **조인 키는 상권명 문자열**이다.
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

REB_BASE = "https://www.reb.or.kr/r-one/openapi"
CYCLE_QUARTER = "QY"
CYCLE_YEAR = "YY"

# 파일 접미사 : (STATBL_ID, 주기, 설명)
STATBL: dict[str, tuple[str, str, str]] = {
    "rent_small": ("T248223134698125", CYCLE_QUARTER, "임대료 소규모 상가"),
    "rent_medium": ("T244363134858603", CYCLE_QUARTER, "임대료 중대형 상가"),
    "rent_complex": ("T244913134948657", CYCLE_QUARTER, "임대료 집합 상가"),
    "convert_small": ("T246253134905233", CYCLE_QUARTER, "전환율 소규모 상가"),
    "convert_medium": ("T241883134877452", CYCLE_QUARTER, "전환율 중대형 상가"),
    "convert_complex": ("T243133134985812", CYCLE_QUARTER, "전환율 집합 상가"),
    "vacancy_small": ("T241833134686576", CYCLE_QUARTER, "공실률 소규모 상가"),
    "vacancy_medium": ("T249633134845544", CYCLE_QUARTER, "공실률 중대형 상가"),
    "premium": ("A_2024_00445", CYCLE_YEAR, "권리금 시도별·업종별 (연간)"),
}

# 지역 계층 항목 — 상권명 ↔ 구획도 대조(AI-01b)의 원천이라 함께 보존한다.
ITEM_TABLES = ("rent_small", "rent_medium", "rent_complex")

SHP_PATH = RAW_DIR / "부동산원_상권구획도_SHP" / "최종상권368.shp"
PAGE_SIZE = 1000


def _rows(payload: object, root: str) -> list[dict]:
    """R-ONE 응답 `{root: [{head:[...]}, {row:[...]}]}` 에서 row 리스트 추출."""
    for node in (payload.get(root, []) if isinstance(payload, dict) else []):
        if isinstance(node, dict) and isinstance(node.get("row"), list):
            return [item for item in node["row"] if isinstance(item, dict)]
    return []


def fetch_paged(
    session: requests.Session, key: str, path: str, root: str, **params: str
) -> list[dict]:
    """R-ONE 페이지네이션 수집. 빈 페이지 또는 마지막 미만 배치에서 종료."""
    query = "".join(f"&{name}={value}" for name, value in params.items())
    rows: list[dict] = []
    index = 1
    while True:
        url = f"{REB_BASE}/{path}?KEY={key}&Type=json{query}&pIndex={index}&pSize={PAGE_SIZE}"
        batch = _rows(get_json(session, url), root)
        if not batch:
            break
        rows.extend(batch)
        logger.info("  R-ONE %s: %d건 (pIndex=%d)", params.get("STATBL_ID", path), len(rows), index)
        if len(batch) < PAGE_SIZE:
            break
        index += 1
    return rows


def run(env: dict[str, str], session: requests.Session) -> None:
    key = require_key(env, "DATA_GO_KR_API_KEY_REB_RENT")

    for suffix, (statbl_id, cycle, desc) in STATBL.items():
        logger.info("R-ONE %s (%s) 수집", desc, statbl_id)
        rows = fetch_paged(
            session, key, "SttsApiTblData.do", "SttsApiTblData",
            STATBL_ID=statbl_id, DTACYCLE_CD=cycle,
        )
        if not rows:
            logger.warning("R-ONE %s: 결과 없음 — STATBL_ID·주기 확인", desc)
            continue
        save_json(rows, RAW_DIR / "reb_rent" / f"{suffix}_{statbl_id}.json")

    for suffix in ITEM_TABLES:
        statbl_id, _, desc = STATBL[suffix]
        logger.info("R-ONE %s 분류 항목 수집", desc)
        items = fetch_paged(
            session, key, "SttsApiTblItm.do", "SttsApiTblItm", STATBL_ID=statbl_id
        )
        save_json(items, RAW_DIR / "reb_rent" / f"items_{suffix}_{statbl_id}.json")

    if SHP_PATH.exists():
        logger.info("SHP 확인: %s (EPSG:5179 — preprocess에서 서울 필터·변환)", SHP_PATH.name)
    else:
        logger.warning("SHP 없음: %s — data.go.kr 15086933 수동 다운로드", SHP_PATH)


def main() -> None:
    setup_logging()
    run(load_env(), http_session())


if __name__ == "__main__":
    main()

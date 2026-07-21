"""서울 상권분석서비스 8종 수집 (AI-02a, 스펙 §2-0).

서울 열린데이터광장 OpenAPI, 분기 단위. 계정당 인증키 1개(SEOUL_API_KEY_COMMERCIAL).
원본 JSON 그대로 raw/ 보존 — 정규화·공간 조인은 preprocess(AI-04).

서비스명·컬럼은 2026-07-21 전건 실호출로 확정 (docs/assumptions.md #10,
컬럼 정의 노트 = ai/data/raw/README.md §6). 조인 키는 전 데이터셋 공통 `TRDAR_CD`.

⚠️ 분기 경로 파라미터는 서비스마다 적용 여부가 다르다 (assumptions #11):
   Selng·Flpop·Stor·Ix 는 필터가 동작하고, Repop·WrcPopltn·Fclty 는 무시되어 전건이
   돌아온다 → 후자는 전건 수집 후 클라이언트 필터로 분기를 잘라낸다.
좌표계: 상권영역(TbgisTrdarRelm)의 중심점만 EPSG:5181 — WGS84 변환은 preprocess(AI-04).
폴리곤은 이 API에 없고 별도 SHP가 원천이다 (assumptions #17).
"""
from __future__ import annotations

from datetime import date

import requests

from batch.collect._common import (
    RAW_DIR,
    http_session,
    load_env,
    logger,
    require_key,
    save_json,
    seoul_count,
    seoul_fetch_all,
    setup_logging,
)

# 파일 접미사 : (서비스명, 설명, 분기 경로 필터 동작 여부)
SERVICES: dict[str, tuple[str, str, bool]] = {
    "selng": ("VwsmTrdarSelngQq", "추정매출", True),
    "flpop": ("VwsmTrdarFlpopQq", "길단위인구(유동)", True),
    "stor": ("VwsmTrdarStorQq", "점포", True),
    "ix": ("VwsmTrdarIxQq", "상권변화지표", True),
    "repop": ("VwsmTrdarRepopQq", "상주인구", False),
    "wrcpop": ("VwsmTrdarWrcPopltnQq", "직장인구", False),
    "fclty": ("VwsmTrdarFcltyQq", "집객시설", False),
}
AREA_SERVICE = ("area", "TbgisTrdarRelm", "상권영역(중심점 EPSG:5181·면적)")

QUARTER_FIELD = "STDR_YYQU_CD"
DEFAULT_QUARTERS = 4  # 팀 결정 2026-07-21: 최신 4개 분기 · 서울 전체
PROBE_SERVICE = "VwsmTrdarIxQq"  # 분기 탐색용 (상권당 1행이라 가볍고 필터가 동작)
PROBE_BACK = 12  # 오늘 기준 최대 12분기 소급 탐색


def quarter_candidates(today: date | None = None, back: int = PROBE_BACK) -> list[str]:
    """오늘 분기부터 과거로 내려가는 `YYYYQ` 후보 목록."""
    today = today or date.today()
    year, quarter = today.year, (today.month - 1) // 3 + 1
    out: list[str] = []
    for _ in range(back):
        out.append(f"{year}{quarter}")
        quarter -= 1
        if quarter == 0:
            year, quarter = year - 1, 4
    return out


def latest_quarters(
    session: requests.Session, key: str, count: int = DEFAULT_QUARTERS
) -> list[str]:
    """실제 데이터가 존재하는 최신 분기 `count`개 (오래된 것 → 최신 순).

    분기를 상수로 굳히면 다음 분기 공표 때 수집이 낡는다 — 매 실행마다 탐색한다.
    """
    found: list[str] = []
    for candidate in quarter_candidates():
        if seoul_count(session, key, PROBE_SERVICE, candidate) > 0:
            found.append(candidate)
            if len(found) == count:
                break
    if not found:
        raise SystemExit("분기 탐색 실패 — 서울 OpenAPI 응답·인증키 확인")
    return sorted(found)


def run(env: dict[str, str], session: requests.Session) -> None:
    key = require_key(env, "SEOUL_API_KEY_COMMERCIAL")
    quarters = latest_quarters(session, key)
    logger.info("수집 분기: %s", ", ".join(quarters))
    wanted = set(quarters)

    for suffix, (service, desc, filtered) in SERVICES.items():
        if filtered:
            rows: list[dict] = []
            for quarter in quarters:
                logger.info("상권분석 %s (%s) %s 수집", desc, service, quarter)
                rows.extend(seoul_fetch_all(session, key, service, quarter))
        else:
            logger.info("상권분석 %s (%s) 전건 수집 → 분기 필터", desc, service)
            rows = [
                row
                for row in seoul_fetch_all(session, key, service)
                if row.get(QUARTER_FIELD) in wanted
            ]
        save_json(rows, RAW_DIR / "seoul_commercial" / f"{suffix}_{service}.json")

    suffix, service, desc = AREA_SERVICE
    logger.info("상권분석 %s (%s) 수집", desc, service)
    save_json(
        seoul_fetch_all(session, key, service),
        RAW_DIR / "seoul_commercial" / f"{suffix}_{service}.json",
    )


def main() -> None:
    setup_logging()
    run(load_env(), http_session())


if __name__ == "__main__":
    main()

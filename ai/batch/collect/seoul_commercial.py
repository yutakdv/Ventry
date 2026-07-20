"""서울 상권분석서비스 7종 수집 (AI-02, 스펙 §2-0).

서울 열린데이터광장 OpenAPI, 분기 단위. 계정당 인증키 1개(SEOUL_API_KEY_COMMERCIAL).
원본 JSON 그대로 raw/ 보존 — 컬럼 확정·정규화는 preprocess(AI-04).

⚠️ 서비스명(SERVICES 값)은 공개 문서·샘플 URL 기준 최선치다. 라이브 실행 전
   각 데이터셋 페이지(data.seoul.go.kr)에서 실제 서비스명(SAMPLE URL)을 확정할 것.
   추정매출 VwsmTrdarSelngQq 는 검증된 값(§check_env 실측). 나머지는 동일 계열 추정.
좌표계: '상권영역'만 공간정보(EPSG:5181) 포함 → WGS84 변환은 preprocess(AI-04).
"""
from __future__ import annotations

import requests

from batch.collect._common import (
    RAW_DIR,
    http_session,
    load_env,
    logger,
    require_key,
    save_json,
    seoul_fetch_all,
    setup_logging,
)

# 파일 접미사 : (서비스명, 설명)  — 서비스명은 라이브 전 재확인(모듈 docstring 참조)
SERVICES: dict[str, tuple[str, str]] = {
    "selng": ("VwsmTrdarSelngQq", "추정매출"),
    "flpop": ("VwsmTrdarFlpopQq", "길단위인구(유동)"),
    "repop": ("VwsmTrdarRepopQq", "상주인구"),
    "wrcpop": ("VwsmTrdarWrcpopQq", "직장인구"),
    "fclty": ("VwsmTrdarFcltyQq", "집객시설"),
    "stor": ("VwsmTrdarStorQq", "점포"),
    "ix": ("VwsmTrdarIxQq", "상권변화지표"),
    "area": ("VwsmTrdarArea", "상권영역(구역·좌표 EPSG:5181)"),
}


def run(env: dict[str, str], session: requests.Session) -> None:
    key = require_key(env, "SEOUL_API_KEY_COMMERCIAL")
    for suffix, (service, desc) in SERVICES.items():
        logger.info("상권분석 %s (%s) 수집", desc, service)
        rows = seoul_fetch_all(session, key, service)
        save_json(rows, RAW_DIR / "seoul_commercial" / f"{suffix}_{service}.json")


def main() -> None:
    setup_logging()
    run(load_env(), http_session())


if __name__ == "__main__":
    main()

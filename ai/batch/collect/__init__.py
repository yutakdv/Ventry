"""원천 데이터 수집 (AI-01·02, 스펙 §2-0 소스 목록).

모듈:
- seoul_commercial : 서울 상권분석 7종(추정매출·길단위/상주/직장인구·집객시설·변화지표·영역)
- transit          : 역사마스터 좌표(+ t-data 폴백) + 승하차, 환승역 정규화·합산
- reb_rent         : 부동산원 임대료·전환율(R-ONE OpenAPI) + 상권구획도 SHP + 권리금 연간치
- permits          : 인허가 CSV(CP949) 영업중 필터 + 카페·음식점 재분류
- startup_cost      : 공정위 가맹정보(창업비용 분해) + KOSIS 소상공인실태(총액) — AI-05 상수
- funding_docs     : 정책자금·보증·대출 PDF 텍스트 추출(AI-06 RAG 입력)
- funding_web      : PDF로 확보 불가한 웹 1차 출처 — 서울신보 6종(프린트본 폰트 깨짐) +
                     소진공 「금리안내」 분기 기준금리표(융자공고가 홈페이지로 넘긴 값)

원칙: 원본 raw/ 보존, 스크립트 재실행 가능(스펙 §2-2). 좌표변환은 preprocess(AI-04).
실행: python -m batch.collect [모듈명 ...]   (인자 없으면 전체)

⚠️ 이 스크립트는 재실행 가능한 골격이다. 서울 서비스명·R-ONE STATBL_ID 등 일부 코드는
   각 모듈 docstring의 '재확인' 주석 대상 — 라이브 수집 전 데이터셋 페이지에서 확정할 것.
   현 환경엔 배치 의존(pandas/requests/pypdf)이 미설치라 실행은 배치 컨테이너에서.
"""
from __future__ import annotations

import sys

from batch.collect import (
    funding_docs,
    funding_web,
    permits,
    reb_rent,
    seoul_commercial,
    startup_cost,
    transit,
)
from batch.collect._common import http_session, load_env, logger, setup_logging

MODULES = {
    "seoul_commercial": seoul_commercial,
    "transit": transit,
    "reb_rent": reb_rent,
    "permits": permits,
    "startup_cost": startup_cost,
    "funding_docs": funding_docs,
    "funding_web": funding_web,
}


def main(argv: list[str] | None = None) -> None:
    setup_logging()
    env = load_env()
    session = http_session()
    requested = argv if argv is not None else sys.argv[1:]
    targets = requested or list(MODULES)
    unknown = [name for name in targets if name not in MODULES]
    if unknown:
        raise SystemExit(f"알 수 없는 모듈: {unknown} — 가능: {list(MODULES)}")
    for name in targets:
        logger.info("═══ collect:%s ═══", name)
        MODULES[name].run(env, session)


if __name__ == "__main__":
    main()

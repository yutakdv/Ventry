"""창업비용 통계 — 공정위 가맹정보(분해) + KOSIS 소상공인실태(총액) (AI-02e, 스펙 §4-1).

AI-05 초기비용 4블록 중 「인테리어·시설비 = 업종 상수」의 유일한 입력.
두 소스는 서로를 보완한다 — 어느 한쪽만으로는 블록을 채우지 못한다.

┌ 공정위 가맹정보 (data.go.kr 15110293) — 항목 **분해**를 준다
│  base : https://apis.data.go.kr/1130000/FftcSclasIndutyFntnStatsService/<op>
│  op   : 외식 15업종 / 서비스 22 / 도소매 7. yr=연도(2019~최신). resultType=json.
│  키   : DATA_GO_KR_API_KEY_FTC_COST (개발단계 자동승인, 10,000건/월)
│
│  ⚠️ 응답 필드명이 실제 의미와 어긋나 있다 (2026-07-24 108행 합 항등식으로 실증):
│     smtnAmt == frcsCnt + avrgFrcsAmt + avrgFntnAmt + avrgJngEtcAmt  (±1 반올림, 전 108행)
│     → frcsCnt 는 '가맹점 수'가 아니라 **평균 가맹보증금액**(금액). 실제 매핑:
│         jnghdqrtrsCnt  = 가맹본부 수 (유일한 진짜 count, 합계에 안 들어감)
│         frcsCnt        = 평균 가맹보증금액        (필드명 오배치)
│         avrgFrcsAmt    = 평균 가맹비(가입비)
│         avrgFntnAmt    = 평균 가맹교육금액
│         avrgJngEtcAmt  = 평균 가맹기타금액 = 필수설비+도면설계+인테리어+시공관리+초도물품
│                          → **인테리어·시설비 프록시** (보증금은 별도 필드라 이중계상 없음)
│         smtnAmt        = 창업비용 합계
│  ⚠️ crrncyUnitCdNm 라벨은 "(단위 :천원)"이지만 **실제 단위는 만원**이다 (data.go.kr
│     메타 버그). 근거: 일식 smtnAmt=11,016 → 만원이면 1.10억(풀서비스 프랜차이즈 실측
│     정합), 천원이면 11백만(불가능)·KOSIS 총액 대비 130배 낮음. → COST_UNIT_MANWON=1.
│  ⚠️ 가맹점(프랜차이즈) 기준이라 독립 창업 대비 상향 편의. "내 한도로 어디까지" 서비스에선
│     비용 과대추정 = 도달범위 과소추정 = **안전(하향) 방향**이므로 그대로 사용한다.
│
└ KOSIS 소상공인실태조사 (orgId=142 tblId=DT_3ME0126) — **총액 상한**을 준다
   base : https://kosis.kr/openapi/Param/statisticsParameterData.do?method=getList
   키   : KOSIS_API_KEY (kosis.kr 발급 — data.go.kr 키와 별개)
   축   : objL1=00(전국)/11(서울) · objL2=I(숙박및음식점업)/I56(음식점및주점업)
          objL3=10(기업체수)/123(기업체당 창업비용)/5325(기업체당 본인부담금액) · prdSe=Y
   ⚠️ 시도별은 대분류 I까지만 — **서울 × I56 조합은 없다**. 서울 보정은 전국 대비 비율로
      간접 산출 (2024: 서울 I 115 / 전국 I 108 = 1.065 → 인테리어엔 미적용, docstring 참조).
   ⚠️ 독립창업·국가승인통계·임대차보증금 및 권리금 포함 총액이라 FTC(분해·부동산 제외)와
      직접 비교 불가. 총액 **정합성 상한**으로만 쓴다 (한식 FTC 0.78억 < KOSIS 1.01억: OK).

원칙: 원본 raw/ 보존, 재실행 가능(스펙 §2-2). 파생 상수는 preprocess/AI-05, 등재는 assumptions.
"""
from __future__ import annotations

import datetime as _dt

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

# ── 공정위 가맹정보 창업비용 ────────────────────────────────────────────────
FTC_BASE = "https://apis.data.go.kr/1130000/FftcSclasIndutyFntnStatsService"
FTC_OPS: dict[str, str] = {
    "getSclaIndutyFntnOutStats": "외식",   # 커피·음료·제과·한식·분식·치킨·주점 등 15
    "getSclaIndutyFntnSrvcStats": "서비스",  # 22
    "getSclaIndutyFntnWhrtStats": "도소매",  # 7
}
FTC_FIRST_YEAR = 2019          # 실측 가용 최소 연도 (2018 이전은 미제공)
FTC_PROBE_HEADROOM = 1         # 올해 미발표분까지 프로브
FTC_PAGE = 100                 # 최대 업종 수(22)보다 크게

# ── KOSIS 소상공인실태조사 ──────────────────────────────────────────────────
KOSIS_URL = "https://kosis.kr/openapi/Param/statisticsParameterData.do"
KOSIS_PARAMS = {
    "method": "getList",
    "orgId": "142",
    "tblId": "DT_3ME0126",
    "itmId": "T01+",
    "prdSe": "Y",
    "objL1": "00+11+",          # 전국 · 서울특별시
    "objL2": "I+I56+",          # 숙박및음식점업 · 음식점및주점업
    "objL3": "10+123+5325+",    # 기업체수 · 기업체당 창업비용 · 기업체당 본인부담금액
    "startPrdDe": "2020",
    "format": "json",
    "jsonVD": "Y",
}


def _ftc_items(payload: object) -> list[dict]:
    """FTC 응답 `{resultCode, items:[...]}` 에서 items 추출 (단건은 dict일 수 있음)."""
    items = payload.get("items") if isinstance(payload, dict) else None
    if items is None:
        return []
    return items if isinstance(items, list) else [items]


def fetch_ftc(session: requests.Session, key: str) -> list[dict]:
    """공정위 3개 오퍼레이션 × 가용 연도 전건 수집. 연도·그룹 태그를 붙여 평탄화한다."""
    latest = _dt.date.today().year + FTC_PROBE_HEADROOM
    rows: list[dict] = []
    for op, group in FTC_OPS.items():
        for year in range(latest, FTC_FIRST_YEAR - 1, -1):
            payload = get_json(
                session,
                f"{FTC_BASE}/{op}"
                f"?serviceKey={key}&yr={year}&resultType=json&pageNo=1&numOfRows={FTC_PAGE}",
            )
            items = _ftc_items(payload)
            if not items:
                continue
            for item in items:
                rows.append({"group": group, **item})
            logger.info("  FTC %s %d: %d업종", group, year, len(items))
    return rows


def fetch_kosis(session: requests.Session, key: str) -> list[dict]:
    """KOSIS 소상공인실태조사 창업비용 총액 계열 수집 (전국·서울 × I·I56)."""
    params = {**KOSIS_PARAMS, "apiKey": key, "endPrdDe": str(_dt.date.today().year)}
    query = "&".join(f"{name}={value}" for name, value in params.items())
    payload = get_json(session, f"{KOSIS_URL}?{query}")
    if isinstance(payload, dict):  # 오류는 dict, 정상은 list
        raise SystemExit(f"KOSIS 응답 이상 (키·파라미터 확인): {payload}")
    rows = [row for row in payload if isinstance(row, dict)]
    logger.info("  KOSIS: %d건", len(rows))
    return rows


def run(env: dict[str, str], session: requests.Session) -> None:
    ftc_key = require_key(env, "DATA_GO_KR_API_KEY_FTC_COST")
    ftc_rows = fetch_ftc(session, ftc_key)
    if ftc_rows:
        save_json(ftc_rows, RAW_DIR / "startup_cost" / "ftc_franchise_cost.json")
    else:
        logger.warning("FTC 창업비용: 결과 없음 — 키·오퍼레이션 확인")

    kosis_key = require_key(env, "KOSIS_API_KEY")
    kosis_rows = fetch_kosis(session, kosis_key)
    if kosis_rows:
        save_json(kosis_rows, RAW_DIR / "startup_cost" / "kosis_startup_cost.json")
    else:
        logger.warning("KOSIS 창업비용: 결과 없음 — 키·통계표 코드 확인")


def main() -> None:
    setup_logging()
    run(load_env(), http_session())


if __name__ == "__main__":
    main()

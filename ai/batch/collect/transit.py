"""지하철 역사좌표 + 승하차 수집 · 환승역 정규화 (AI-02, 스펙 §2-2).

- 역사마스터(OA-21232, SEOUL_API_KEY_STATION): 역 좌표. 좌표계 불명확 판정 시
  t-data 지하철역_GEOM(위경도 명시·CC-BY)으로 교체 — AI-01 결정, docs/assumptions.md.
- 승하차(OA-12914, SEOUL_API_KEY_RIDERS): 역별 승차·하차 인원.
- 환승역 정규화: 역명에서 호선 접미·괄호 부기·'역' 제거 → 정규명 그룹 합산.
  (같은 물리 역이 노선별 복수 행 → 접근성 성분의 분모가 되는 승하차 V를 합산)

⚠️ 서비스명·필드명은 데이터셋 페이지(data.seoul.go.kr) 재확인 필요(현재값 최선치).
   일→연·월 일평균 산출 규칙과 정규화 규칙은 docs/assumptions.md(AI-02)에 등재.
"""
from __future__ import annotations

import re

import requests

from batch.collect._common import (
    INTERIM_DIR,
    RAW_DIR,
    http_session,
    load_env,
    logger,
    require_key,
    save_json,
    seoul_fetch_all,
    setup_logging,
)

# 서비스명 — 라이브 전 재확인
STATION_SERVICE = "subwayStationMaster"  # OA-21232 역사마스터
RIDERS_SERVICE = "CardSubwayStatsNew"  # OA-12914 승하차

# 승하차 필드명 — 라이브 전 재확인 (버전에 따라 상이)
RIDERS_NAME_KEY = "SBWY_STNS_NM"
RIDERS_ON_KEY = "GTON_TNOPE"
RIDERS_OFF_KEY = "GTOFF_TNOPE"

_PAREN = re.compile(r"\(.*?\)")
_LINE_PREFIX = re.compile(r"^\s*\d+호선\s*")
_LINE_SUFFIX = re.compile(r"\s*\d+호선\s*$")


def normalize_station(name: str) -> str:
    """역명 정규화(환승역 합산 키).

    괄호 부기·호선 접두/접미·말미 '역' 제거. 예:
    '왕십리(성동구청)'→'왕십리', '서울역'→'서울', '4호선 성신여대입구'→'성신여대입구'.
    (규칙 출처: docs/assumptions.md AI-02)
    """
    text = _PAREN.sub("", name or "")
    text = _LINE_PREFIX.sub("", text)
    text = _LINE_SUFFIX.sub("", text).strip()
    if len(text) > 1 and text.endswith("역"):
        text = text[:-1]
    return text.strip()


def _to_number(value: object) -> float:
    try:
        return float(str(value).replace(",", ""))
    except (TypeError, ValueError):
        return 0.0


def aggregate_riders(rows: list[dict]) -> dict[str, dict[str, float]]:
    """정규명 기준 승·하차 합산 → {정규명: {'on': .., 'off': ..}}."""
    agg: dict[str, dict[str, float]] = {}
    for row in rows:
        key = normalize_station(str(row.get(RIDERS_NAME_KEY, "")))
        if not key:
            continue
        cell = agg.setdefault(key, {"on": 0.0, "off": 0.0})
        cell["on"] += _to_number(row.get(RIDERS_ON_KEY))
        cell["off"] += _to_number(row.get(RIDERS_OFF_KEY))
    return agg


def run(env: dict[str, str], session: requests.Session) -> None:
    stations = seoul_fetch_all(session, require_key(env, "SEOUL_API_KEY_STATION"), STATION_SERVICE)
    save_json(stations, RAW_DIR / "transit" / f"stations_{STATION_SERVICE}.json")

    riders = seoul_fetch_all(session, require_key(env, "SEOUL_API_KEY_RIDERS"), RIDERS_SERVICE)
    save_json(riders, RAW_DIR / "transit" / f"riders_{RIDERS_SERVICE}.json")

    agg = aggregate_riders(riders)
    save_json(
        [{"station": name, **cells} for name, cells in agg.items()],
        INTERIM_DIR / "transit" / "riders_by_station.json",
    )
    logger.info("환승역 정규화: 승하차 %d행 → 정규역 %d개", len(riders), len(agg))


def main() -> None:
    setup_logging()
    run(load_env(), http_session())


if __name__ == "__main__":
    main()

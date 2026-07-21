"""지하철 역사좌표 + 승하차 수집 · 환승역 정규화 (AI-02b, 스펙 §2-2).

- 역사마스터(OA-21232 `subwayStationMaster`, SEOUL_API_KEY_STATION): 784행.
  `LAT`·`LOT`가 **WGS84 위경도**임을 2026-07-21 실측으로 확정했다(서울역 37.556228 /
  126.972135) → t-data 지하철역_GEOM 교체는 불필요 (docs/assumptions.md #14).
  수도권 전철이 포함돼 서울 밖 역도 들어오지만 최근접 조인(AI-04d)에서 자연 배제된다.
- 승하차(OA-12914 `CardSubwayStatsNew`, SEOUL_API_KEY_RIDERS): **경로 파라미터
  `USE_YMD` 필수**이며 3일 지연 갱신이다. 하루치가 618행 규모라 최근 N일을 훑어
  역별 **일평균**을 만든다.
- 환승역 정규화: 역명에서 호선 접미·괄호 부기·'역' 제거 → 정규명 그룹 합산
  (같은 물리 역이 노선별 복수 행 → 접근성 성분의 규모가 되는 승하차 V를 합산).
  규칙은 docs/assumptions.md #4.
"""
from __future__ import annotations

import re
from datetime import date, timedelta

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

STATION_SERVICE = "subwayStationMaster"  # OA-21232 역사마스터 (LAT/LOT = WGS84)
RIDERS_SERVICE = "CardSubwayStatsNew"  # OA-12914 승하차 (경로 파라미터 USE_YMD)

RIDERS_DATE_KEY = "USE_YMD"
RIDERS_NAME_KEY = "SBWY_STNS_NM"
RIDERS_LINE_KEY = "SBWY_ROUT_LN_NM"
RIDERS_ON_KEY = "GTON_TNOPE"
RIDERS_OFF_KEY = "GTOFF_TNOPE"

RIDERS_LAG_DAYS = 4  # 3일 지연 갱신 + 여유 1일
RIDERS_WINDOW_DAYS = 28  # 요일 편차를 없애려 4주 = 28일

_PAREN = re.compile(r"\(.*?\)")
_LINE_PREFIX = re.compile(r"^\s*\d+호선\s*")
_LINE_SUFFIX = re.compile(r"\s*\d+호선\s*$")

# 개명된 역 — 역사마스터는 옛 이름, 승하차는 새 이름을 쓰는 경우가 있다.
# 개명만 담는다. 표기가 비슷해도 다른 역(2호선 뚝섬 ≠ 7호선 뚝섬유원지)은 절대 넣지 않는다.
STATION_ALIASES = {"신천": "잠실새내"}


def normalize_station(name: str) -> str:
    """역명 정규화(환승역 합산 키).

    괄호 부기·호선 접두/접미·말미 '역' 제거. 예:
    '왕십리(성동구청)'→'왕십리', '서울역'→'서울', '4호선 성신여대입구'→'성신여대입구'.
    (규칙 출처: docs/assumptions.md #4)
    """
    text = _PAREN.sub("", name or "")
    text = _LINE_PREFIX.sub("", text)
    text = _LINE_SUFFIX.sub("", text).strip()
    if len(text) > 1 and text.endswith("역"):
        text = text[:-1]
    text = text.strip()
    return STATION_ALIASES.get(text, text)


def _to_number(value: object) -> float:
    try:
        return float(str(value).replace(",", ""))
    except (TypeError, ValueError):
        return 0.0


def riders_dates(today: date | None = None) -> list[str]:
    """수집 대상 일자 `YYYYMMDD` (최신 → 과거)."""
    end = (today or date.today()) - timedelta(days=RIDERS_LAG_DAYS)
    return [(end - timedelta(days=n)).strftime("%Y%m%d") for n in range(RIDERS_WINDOW_DAYS)]


def aggregate_riders(rows: list[dict]) -> dict[str, dict[str, float]]:
    """정규명 기준 **일평균** 승·하차 → {정규명: {'on':…, 'off':…, 'days':…}}.

    같은 역이 노선별 복수 행이므로 하루 안에서는 합산하고, 여러 날에 걸쳐서는
    그 역이 실제로 등장한 일수로 나눈다 (결측일이 평균을 끌어내리지 않게).
    """
    totals: dict[str, dict[str, float]] = {}
    days: dict[str, set[str]] = {}
    for row in rows:
        key = normalize_station(str(row.get(RIDERS_NAME_KEY, "")))
        if not key:
            continue
        cell = totals.setdefault(key, {"on": 0.0, "off": 0.0})
        cell["on"] += _to_number(row.get(RIDERS_ON_KEY))
        cell["off"] += _to_number(row.get(RIDERS_OFF_KEY))
        days.setdefault(key, set()).add(str(row.get(RIDERS_DATE_KEY, "")))
    return {
        name: {
            "on": round(cell["on"] / len(days[name]), 1),
            "off": round(cell["off"] / len(days[name]), 1),
            "days": len(days[name]),
        }
        for name, cell in totals.items()
    }


def run(env: dict[str, str], session: requests.Session) -> None:
    stations = seoul_fetch_all(session, require_key(env, "SEOUL_API_KEY_STATION"), STATION_SERVICE)
    save_json(stations, RAW_DIR / "transit" / f"stations_{STATION_SERVICE}.json")

    riders_key = require_key(env, "SEOUL_API_KEY_RIDERS")
    riders: list[dict] = []
    collected: list[str] = []
    for day in riders_dates():
        rows = seoul_fetch_all(session, riders_key, RIDERS_SERVICE, day)
        if not rows:
            logger.warning("승하차 %s: 데이터 없음 — 건너뜀", day)
            continue
        riders.extend(rows)
        collected.append(day)
    if not riders:
        raise SystemExit(f"{RIDERS_SERVICE} 수집 실패 — USE_YMD 범위·인증키 확인")
    save_json(riders, RAW_DIR / "transit" / f"riders_{RIDERS_SERVICE}.json")

    agg = aggregate_riders(riders)
    save_json(
        [{"station": name, **cells} for name, cells in sorted(agg.items())],
        INTERIM_DIR / "transit" / "riders_by_station.json",
    )
    logger.info(
        "승하차 %d일(%s~%s) %d행 → 정규역 %d개 일평균",
        len(collected),
        collected[-1],
        collected[0],
        len(riders),
        len(agg),
    )


def main() -> None:
    setup_logging()
    run(load_env(), http_session())


if __name__ == "__main__":
    main()

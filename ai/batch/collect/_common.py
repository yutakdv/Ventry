"""수집 공통 유틸 (AI-02).

- .env 로드 · raw/interim 경로 · 로깅
- HTTP GET(재시도) 헬퍼
- 서울 열린데이터광장 OpenAPI 페이지네이션 페처 (INFO-000 검증)
- 저장 헬퍼 (원본 JSON / 표 CSV)

원칙: 모든 원본은 raw/ 보존, 스크립트 재실행 가능 (스펙 §2-2).
좌표계는 소스별 상이(EPSG:5174/5179/5181) — 변환은 preprocess(AI-04) 담당
(docs/assumptions.md #3). 수집 단계는 원본 스키마 그대로 보존한다.
"""
from __future__ import annotations

import csv
import json
import logging
import os
import time
from pathlib import Path

import requests

# ── 경로 (ai/batch/collect/_common.py → ai/) ────────────────────────────────
AI_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = AI_ROOT.parent
RAW_DIR = AI_ROOT / "data" / "raw"
INTERIM_DIR = AI_ROOT / "data" / "interim"

logger = logging.getLogger("collect")

# ── 서울 열린데이터광장 OpenAPI ─────────────────────────────────────────────
SEOUL_BASE = "http://openapi.seoul.go.kr:8088"
SEOUL_PAGE = 1000  # 요청당 최대 행 수


def setup_logging(level: int = logging.INFO) -> None:
    logging.basicConfig(
        level=level,
        format="%(asctime)s [%(name)s] %(levelname)s %(message)s",
        datefmt="%H:%M:%S",
    )


def _parse_env(path: Path) -> dict[str, str]:
    out: dict[str, str] = {}
    if not path.exists():
        return out
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        out[key.strip()] = value.strip().strip('"').strip("'")
    return out


def load_env() -> dict[str, str]:
    """루트 .env 로드. python-dotenv 있으면 사용, 없으면 자체 파서. os.environ 우선."""
    env_path = REPO_ROOT / ".env"
    try:
        from dotenv import dotenv_values

        values = {k: (v or "") for k, v in dotenv_values(env_path).items()}
    except ImportError:
        values = _parse_env(env_path)
    return {name: os.environ.get(name, values[name]) for name in values}


def require_key(env: dict[str, str], name: str) -> str:
    key = env.get(name) or os.environ.get(name, "")
    if not key:
        raise SystemExit(f"환경변수 {name} 미설정 — .env 확인 (스펙 §2-0 소스 목록)")
    return key


def http_session() -> requests.Session:
    session = requests.Session()
    session.headers.update({"User-Agent": "ventry-collect/0.1"})
    return session


def get_json(
    session: requests.Session,
    url: str,
    *,
    timeout: int = 30,
    retries: int = 3,
    backoff: float = 1.5,
) -> dict:
    """GET → JSON. 실패 시 지수 백오프 재시도, 최종 실패는 중단."""
    last_exc: Exception | None = None
    for attempt in range(1, retries + 1):
        try:
            resp = session.get(url, timeout=timeout)
            resp.raise_for_status()
            return resp.json()
        except (requests.RequestException, ValueError) as exc:
            last_exc = exc
            logger.warning("요청 실패 (%d/%d): %s — %s", attempt, retries, url, exc)
            time.sleep(backoff * attempt)
    raise SystemExit(f"요청 {retries}회 실패: {url} — {last_exc}")


def seoul_fetch_all(session: requests.Session, key: str, service: str) -> list[dict]:
    """서울 OpenAPI 전건 수집 (페이지네이션).

    URL  : {BASE}/{KEY}/json/{SERVICE}/{START}/{END}/
    응답 : {SERVICE: {list_total_count, RESULT:{CODE,MESSAGE}, row:[...]}}
    CODE가 INFO-000이 아니면 중단 (INFO-100=인증오류 등).
    """
    rows: list[dict] = []
    start = 1
    while True:
        end = start + SEOUL_PAGE - 1
        url = f"{SEOUL_BASE}/{key}/json/{service}/{start}/{end}/"
        payload = get_json(session, url)
        body = payload.get(service)
        if body is None:
            result = payload.get("RESULT", payload)
            raise SystemExit(f"{service} 응답 이상 (인증/서비스명 확인): {result}")
        result = body.get("RESULT", {})
        code = result.get("CODE")
        if code not in ("INFO-000", None):
            raise SystemExit(f"{service} 수집 중단: {code} {result.get('MESSAGE')}")
        batch = body.get("row", []) or []
        rows.extend(batch)
        total = int(body.get("list_total_count", len(rows)))
        logger.info("  %s: %d/%d", service, len(rows), total)
        if len(batch) < SEOUL_PAGE or len(rows) >= total:
            break
        start += SEOUL_PAGE
    return rows


def save_json(rows: list[dict], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
    logger.info("저장: %s (%d건)", path.relative_to(AI_ROOT), len(rows))


def save_rows_csv(rows: list[dict], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if not rows:
        logger.warning("빈 결과 — %s 생략", path)
        return
    fields = list(rows[0].keys())
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
    logger.info("저장: %s (%d건)", path.relative_to(AI_ROOT), len(rows))

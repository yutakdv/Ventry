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
import os
import time
from pathlib import Path

import requests

from batch.paths import AI_ROOT, INTERIM_DIR, RAW_DIR, REPO_ROOT, logger, setup_logging

__all__ = [
    "AI_ROOT", "INTERIM_DIR", "RAW_DIR", "REPO_ROOT", "logger", "setup_logging",
    "load_env", "require_key", "http_session", "get_json",
    "seoul_count", "seoul_fetch_all", "save_json", "save_rows_csv",
    "SEOUL_BASE", "SEOUL_PAGE",
]

# ── 서울 열린데이터광장 OpenAPI ─────────────────────────────────────────────
SEOUL_BASE = "http://openapi.seoul.go.kr:8088"
SEOUL_PAGE = 1000  # 요청당 최대 행 수


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


def seoul_count(session: requests.Session, key: str, service: str, *path_params: str) -> int:
    """전건 수 조회 (1행만 요청). 데이터 없음(INFO-200)은 0."""
    tail = "".join(f"{p}/" for p in path_params)
    payload = get_json(session, f"{SEOUL_BASE}/{key}/json/{service}/1/1/{tail}")
    body = payload.get(service)
    if body is None:
        return 0
    return int(body.get("list_total_count", 0))


def seoul_fetch_all(
    session: requests.Session, key: str, service: str, *path_params: str
) -> list[dict]:
    """서울 OpenAPI 전건 수집 (페이지네이션).

    URL  : {BASE}/{KEY}/json/{SERVICE}/{START}/{END}/{PATH_PARAMS...}/
    응답 : {SERVICE: {list_total_count, RESULT:{CODE,MESSAGE}, row:[...]}}
    CODE가 INFO-000이 아니면 중단 (INFO-100=인증오류 등).

    ⚠️ 경로 파라미터(분기 등)는 서비스마다 적용 여부가 다르다 — 무시하는 서비스는
       전건을 반환하므로 호출부에서 클라이언트 필터가 필요하다 (docs/assumptions.md #11).
    """
    tail = "".join(f"{p}/" for p in path_params)
    rows: list[dict] = []
    start = 1
    while True:
        end = start + SEOUL_PAGE - 1
        url = f"{SEOUL_BASE}/{key}/json/{service}/{start}/{end}/{tail}"
        payload = get_json(session, url)
        body = payload.get(service)
        if body is None:
            result = payload.get("RESULT", payload)
            raise SystemExit(f"{service} 응답 이상 (인증/서비스명 확인): {result}")
        result = body.get("RESULT", {})
        code = result.get("CODE")
        if code == "INFO-200":  # 해당 조건에 데이터 없음 — 중단이 아니라 빈 결과
            break
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

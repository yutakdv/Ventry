"""문서별 **수집일** 매니페스트 (이슈 #93).

`finance_product.source_collected` 는 "이 공고문을 언제 받아왔는가"를 뜻하는데, 구 구현은
덤프를 <b>재생성한 날</b>을 찍었다. 배치를 다시 돌리기만 해도 26건 전건의 수집일이 오늘로
갱신돼, 같은 원문인데도 데이터가 새것처럼 보였다.

수집일은 수집 시점에만 알 수 있으므로 **수집기가 기록하고 저장소에 커밋**한다. 적재 배치는
이 파일을 읽을 뿐 날짜를 만들지 않는다 — 없는 문서는 오늘로 폴백하되 경고를 남긴다.

**`batch.collect` 안이 아니라 `batch` 바로 아래 두었다.** 그 패키지의 `__init__` 은 수집기
전 모듈을 즉시 임포트하고 그중 `_common` 이 `requests` 를 끌어온다. 적재 배치가 수집일을
읽으려고 수집 패키지를 건드리면 **수집 의존성 없이는 적재 테스트조차 돌지 않는다** — 실제로
CI(pytest·pandas 만 설치)가 이 연결을 잡아냈다.
"""
from __future__ import annotations

import json
from datetime import date
from pathlib import Path

from batch.paths import AI_ROOT, logger

MANIFEST = AI_ROOT / "data" / "finance" / "collected.json"


def load() -> dict[str, str]:
    """{문서명: ISO 수집일}. 파일이 없으면 빈 사전."""
    if not MANIFEST.exists():
        return {}
    return json.loads(MANIFEST.read_text(encoding="utf-8"))


def record(names: list[str], when: str | None = None) -> None:
    """실제로 수집한 문서만 갱신한다. 건드리지 않은 문서의 날짜는 보존된다."""
    if not names:
        return
    stamp = when or date.today().isoformat()
    manifest = load()
    manifest.update({name: stamp for name in sorted(names)})
    MANIFEST.parent.mkdir(parents=True, exist_ok=True)
    MANIFEST.write_text(
        json.dumps(dict(sorted(manifest.items())), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8")
    logger.info("수집일 기록: %d건 → %s", len(names), stamp)


def for_doc(doc: str, fallback: str) -> str:
    """문서의 수집일. 미기록이면 폴백을 쓰되 **경고를 남긴다** — 조용히 오늘로 찍지 않는다."""
    stamp = load().get(doc)
    if stamp is None:
        logger.warning("수집일 미기록 문서 '%s' — %s 로 폴백 (수집기 재실행 시 기록됨)",
                       doc, fallback)
        return fallback
    return stamp


def stamp_from_mtime(path: Path) -> str:
    """원본 파일의 수정 시각을 수집일로 본다 — 내려받은 순간이 곧 그 파일의 mtime 이다."""
    return date.fromtimestamp(path.stat().st_mtime).isoformat()

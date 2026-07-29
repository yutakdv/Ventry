"""금지어 목록 3곳의 사본이 갈라지지 않았는지 단언한다 (QA 리뷰 P2).

용어 컴플라이언스는 심사 감점에 직결되는데, 목록이 세 곳에 손으로 복사돼 있다:

1. `backend/.../llm/LlmResponses.java` `BANNED_TERMS` — LLM 출력 폐기 기준(런타임 차단)
2. `scripts/term_sweep.sh` `PATTERN`              — 소스 문자열 스윕(CI 게이트)
3. `scripts/qa_integration.py` `BANNED_WORDS`     — 기동 스택 응답 스윕(통합 게이트)

셋은 검사 대상이 달라 하나로 합칠 수 없다. 합칠 수 없다면 **갈라졌을 때 시끄러워야** 한다 —
목록이 있는데도 안 잡히는 것이 이 프로젝트가 실제로 겪은 실패 형태다("추천해 드립니다"가
두 검증기 사이로 빠져나갔다). 여기서 보는 것은 **핵심 어간의 교집합**이지 완전 동일성이 아니다:
`qa_integration.py` 는 응답 문장을 보므로 문장형 항목("대출을 받으세요")을 더 가질 수 있다.
"""
from __future__ import annotations

import re

import pytest

from eval import common

_LLM_PKG = common.REPO_ROOT / "backend" / "src" / "main" / "java" / "com" / "ventry" / "api" / "llm"
JAVA = _LLM_PKG / "LlmResponses.java"
SWEEP = common.REPO_ROOT / "scripts" / "term_sweep.sh"
QA = common.REPO_ROOT / "scripts" / "qa_integration.py"


def _read(path):
    if not path.exists():
        pytest.skip(f"대상 파일 없음 — {path}")
    return path.read_text(encoding="utf-8")


def java_terms() -> set[str]:
    body = re.search(r"BANNED_TERMS\s*=\s*List\.of\((.*?)\);", _read(JAVA), re.S)
    assert body, "LlmResponses.BANNED_TERMS 선언을 찾지 못했다"
    return set(re.findall(r'"([^"]+)"', body.group(1)))


def sweep_terms() -> set[str]:
    m = re.search(r"^PATTERN='([^']+)'", _read(SWEEP), re.M)
    assert m, "term_sweep.sh PATTERN 선언을 찾지 못했다"
    return set(m.group(1).split("|"))


def qa_terms() -> set[str]:
    body = re.search(r"BANNED_WORDS\s*=\s*\[(.*?)\]", _read(QA), re.S)
    assert body, "qa_integration.py BANNED_WORDS 선언을 찾지 못했다"
    return set(re.findall(r'"([^"]+)"', body.group(1)))


def test_java_and_sweep_agree():
    assert java_terms() == sweep_terms(), (
        "LlmResponses.BANNED_TERMS 와 term_sweep.sh PATTERN 이 갈라졌다 — "
        "한 곳을 고치면 세 곳을 함께 고칠 것"
    )


def test_qa_covers_every_term():
    """통합 스윕은 위 목록을 **전부 포함**해야 한다(더 가질 수는 있다)."""
    missing = java_terms() - qa_terms()
    assert not missing, f"qa_integration.py BANNED_WORDS 에 빠진 금지어: {sorted(missing)}"

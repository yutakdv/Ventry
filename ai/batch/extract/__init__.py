"""정책자금 공고문 LLM 배치 추출 (AI-06, 오프라인 전용).

실행: python -m batch.extract [funding_llm]   (인자 없으면 funding_llm)
"""
from __future__ import annotations

import sys

from batch.paths import setup_logging

STEPS = ("funding_llm",)


def main(argv: list[str] | None = None) -> None:
    setup_logging()
    requested = argv if argv is not None else sys.argv[1:]
    targets = requested or list(STEPS)
    unknown = [name for name in targets if name not in STEPS]
    if unknown:
        raise SystemExit(f"알 수 없는 단계: {unknown} — 가능: {list(STEPS)}")

    import importlib

    for name in targets:
        importlib.import_module(f"batch.extract.{name}").run()


if __name__ == "__main__":
    main()

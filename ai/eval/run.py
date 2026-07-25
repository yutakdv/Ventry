"""평가 하네스 엔트리포인트 — AI-07 (D9~10). 스펙 §12-1.

사용: python -m eval.run --suite extraction|grounding|sensitivity|model|report
matching(BE 소관)만 안내 후 통과한다 — model 은 AI-08(#21)에서 구현됐다.
"""
import argparse
import json
from pathlib import Path

from eval.suites import extraction, grounding, model, report, sensitivity

DISPATCH = {
    "extraction": extraction.run,
    "grounding": grounding.run,
    "sensitivity": sensitivity.run,
    "model": model.run,
    "report": report.run,
}
PASSTHROUGH = {
    "matching": "BE 소관 — EligibilityFilter 는 BE 단위 + 통합 테스트가 검증",
}
SUITES = (*DISPATCH, *PASSTHROUGH)


def main() -> None:
    parser = argparse.ArgumentParser(description="Ventry AI 품질 평가 하네스 (스펙 §12-1)")
    parser.add_argument("--suite", choices=SUITES, required=True)
    parser.add_argument("--out", default="eval/out", help="metrics.json·차트 출력 경로")
    args = parser.parse_args()

    if args.suite in PASSTHROUGH:
        print(f"eval suite '{args.suite}': {PASSTHROUGH[args.suite]} (본 하네스 미실행)")
        return
    result = DISPATCH[args.suite](Path(args.out))
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

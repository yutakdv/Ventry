"""평가 하네스 엔트리포인트 — AI-07·08 (D9~10)에서 스위트별 구현.

사용: python -m eval.run --suite matching|extraction|grounding|sensitivity|model|report
"""

import argparse

SUITES = ("matching", "extraction", "grounding", "sensitivity", "model", "report")


def main() -> None:
    parser = argparse.ArgumentParser(description="Ventry AI 품질 평가 하네스 (스펙 §12-1)")
    parser.add_argument("--suite", choices=SUITES, required=True)
    parser.add_argument("--out", default="eval/out", help="metrics.json·차트 출력 경로")
    args = parser.parse_args()

    # 구현 전 실행은 명시적으로 실패시킨다 — 지표가 없는데 성공으로 보이면 안 된다.
    raise SystemExit(
        f"eval suite '{args.suite}': 미구현 (AI-07·08, D9~10 예정). "
        "골드셋·프로토콜은 docs/TASKS.md·docs/assumptions.md 참고"
    )


if __name__ == "__main__":
    main()

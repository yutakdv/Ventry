"""전처리·산출 테이블 생성 (AI-04·05, 스펙 §3-1·§4).

공간 조인 3단계 (§3-1) — 처리 순서 고정: 좌표계 통일 → [1] → [2] → [3]:
  [0] `crs`           소스별 원본 CRS(5174/5179/5181) → WGS84 통일 + bbox 검증
  [1] `competition`   인허가 좌표 → 상권 폴리곤            = 경쟁밀도
  [2] `rent_join`     상권 대표점 → 부동산원 구획          = 임대료 할당
                      실패 시 자치구 평균 + fallback_flag
  [3] `transit_join`  대표점 → 최근접 지하철역(sjoin_nearest) = 거리 d + 승하차 V
                      실패 시 접근성 성분 0 + 플래그 (서비스 무중단)

각 단계는 스펙이 요구하는 검증 1종을 실행해 로그로 남긴다
(강남역 표본 / 부동산원 상권 3곳 / 대표역 3곳).

산출 (§4, AI-05): 초기비용 4블록(권리금 이중 표기) / 이중 필터 분자·분모 /
점수화 w1~w5(서울 전체 백분위 정규화) / 정렬 인덱스.
모든 파라미터·폴백 발동 내역은 docs/assumptions.md 등재.

실행: python -m batch.preprocess [단계명 ...]   (인자 없으면 전체)
"""
from __future__ import annotations

import sys

from batch.paths import logger, setup_logging

STEPS = ("competition", "rent_join", "transit_join")


def main(argv: list[str] | None = None) -> None:
    setup_logging()
    requested = argv if argv is not None else sys.argv[1:]
    targets = requested or list(STEPS)
    unknown = [name for name in targets if name not in STEPS]
    if unknown:
        raise SystemExit(f"알 수 없는 단계: {unknown} — 가능: {list(STEPS)}")

    import importlib

    for name in targets:
        logger.info("═══ preprocess:%s ═══", name)
        importlib.import_module(f"batch.preprocess.{name}").run()


if __name__ == "__main__":
    main()

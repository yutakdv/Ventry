"""평가 하네스가 미러링한 서빙 상수가 BE 원본과 같은지 단언한다 (AI 리뷰 P1).

민감도 스위트(`eval/suites/sensitivity.py`)의 가중치 w1~w5 와 부담률 임계 θ 는 백엔드
`LocationService.DEFAULT_WEIGHTS` · `ReverseCheck.DEFAULT_THETA` 의 **사본**이다. 언어 경계라
import 로 없앨 수 없다 — 그런데 사본은 한쪽이 바뀌어도 아무도 실패하지 않는다. 그 상태에서
`make eval` 은 계속 초록이면서 **실서빙과 다른 규칙을 재고**, 제출 근거(부록 1·2)의 무결성이
조용히 깨진다.

그래서 대조를 사람의 주의력이 아니라 테스트로 옮긴다. 이 테스트가 깨지면 둘 중 하나가
바뀐 것이고, **어느 쪽이 옳은지 판단해 양쪽을 함께 고치는 것**이 조치다(한쪽만 고쳐 통과시키면
이 테스트의 존재 이유가 사라진다).
"""
from __future__ import annotations

import re

import pytest

from eval import common
from eval.suites import sensitivity

BACKEND_SRC = common.REPO_ROOT / "backend" / "src" / "main" / "java" / "com" / "ventry" / "api"
LOCATION_SERVICE = BACKEND_SRC / "serving" / "LocationService.java"
REVERSE_CHECK = BACKEND_SRC / "engine" / "ReverseCheck.java"

_WEIGHTS_DECL = re.compile(
    r"DEFAULT_WEIGHTS\s*=\s*new\s+Weights\(\s*"
    r"([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*\)"
)
_THETA_DECL = re.compile(r"DEFAULT_THETA\s*=\s*([\d.]+)\s*;")


def _read(path):
    if not path.exists():
        pytest.skip(f"백엔드 소스 없음 — {path}")   # ai/ 만 체크아웃한 환경
    return path.read_text(encoding="utf-8")


def test_weights_match_backend():
    m = _WEIGHTS_DECL.search(_read(LOCATION_SERVICE))
    assert m, ("LocationService.DEFAULT_WEIGHTS 선언을 찾지 못했다 — "
               "선언 형태가 바뀌었으면 이 정규식도 함께 고칠 것")
    backend = [float(g) for g in m.groups()]
    mirrored = [sensitivity.WEIGHTS[f"w{i}"] for i in range(1, 6)]
    assert mirrored == backend, (
        f"민감도 스위트의 가중치 사본이 서빙과 어긋났다: eval={mirrored} vs BE={backend}"
    )


def test_theta_matches_backend():
    m = _THETA_DECL.search(_read(REVERSE_CHECK))
    assert m, ("ReverseCheck.DEFAULT_THETA 선언을 찾지 못했다 — "
               "선언 형태가 바뀌었으면 이 정규식도 함께 고칠 것")
    assert sensitivity.THETA == float(m.group(1)), (
        f"민감도 스위트의 θ 사본이 서빙과 어긋났다: eval={sensitivity.THETA} vs BE={m.group(1)}"
    )


def test_weights_sum_to_one():
    """사본이 서로 같아도 합이 1이 아니면 종합점수 정의 자체가 깨진다."""
    assert sum(sensitivity.WEIGHTS.values()) == pytest.approx(1.0)


# ── 대표면적: 배치 → 프론트 사본 ────────────────────────────────────────────────
#
# `monthly_rent` 는 「상권 단가 × 업종 대표면적」이라 곱셈의 절반이 화면 라벨에 있다. 계약에
# 면적 필드가 없어(D3 동결) 프론트가 값을 복제할 수밖에 없는데, 어긋나면 금액은 새 면적 기준인데
# **라벨만 옛 면적을 말한다** — 화면이 조용히 거짓말을 하는 형태다. 실제로 이슈 #152 에서 두 값이
# 함께 교체된 이력이 있다(cafe 29.2→44.0 · food 55.2→51.7).

RENT_AREA_TS = common.REPO_ROOT / "frontend" / "src" / "lib" / "rentArea.ts"
_TS_AREA = re.compile(
    r"REPRESENTATIVE_AREA_M2\s*:\s*Record<Industry,\s*number>\s*=\s*\{(.*?)\}", re.S
)


def _ts_representative_area() -> dict[str, float]:
    m = _TS_AREA.search(_read(RENT_AREA_TS))
    assert m, "rentArea.ts REPRESENTATIVE_AREA_M2 선언을 찾지 못했다"
    return {k: float(v) for k, v in re.findall(r"(\w+)\s*:\s*([\d.]+)", m.group(1))}


def test_representative_area_matches_batch():
    from batch.preprocess.cost import REPRESENTATIVE_AREA_M2

    assert _ts_representative_area() == REPRESENTATIVE_AREA_M2, (
        "프론트 대표면적 사본이 배치와 어긋났다 — 금액과 라벨이 다른 면적을 말하게 된다"
    )

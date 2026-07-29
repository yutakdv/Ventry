"""민감도 스위트 (스펙 §12-1·§4-3) — 가중치 ±20%·θ 변동 시 상위 3곳 유지율."""
from __future__ import annotations

from collections import defaultdict
from pathlib import Path

from eval import common

# ⚠️ 서빙 상수의 **사본**이다 (언어 경계라 import 불가).
#   w1~w5 ← backend `serving/LocationService.java` DEFAULT_WEIGHTS
#   θ     ← backend `engine/ReverseCheck.java`   DEFAULT_THETA
# 한쪽만 고치면 평가가 실서빙과 다른 규칙을 재면서도 초록으로 남는다 — 그 침묵을
# `tests/test_serving_constants_sync.py` 가 깨뜨린다. 값을 바꿀 때는 양쪽을 함께 고칠 것.
WEIGHTS = {"w1": 0.30, "w2": 0.20, "w3": 0.20, "w4": 0.15, "w5": 0.15}
THETA = 0.15


def composite(row: dict, weights: dict) -> float:
    return sum(weights[k] * row[k] for k in weights)


def top3(rows: list[dict], weights: dict, theta: float) -> list[str]:
    passing = [r for r in rows if r["burden_ratio"] is not None and r["burden_ratio"] <= theta]
    ranked = sorted(passing, key=lambda r: composite(r, weights), reverse=True)
    return [r["area_code"] for r in ranked[:3]]


def _overlap(base: list[str], pert: list[str]) -> float:
    return len(set(base) & set(pert)) / len(base) if base else 0.0


def evaluate(rows: list[dict]) -> dict:
    by_ind: dict[str, list] = defaultdict(list)
    for r in rows:
        by_ind[r["industry"]].append(r)
    out: dict[str, dict] = {}
    for ind, all_rows in by_ind.items():
        # 부담률을 못 구한 행(임대료 미매칭·추정매출 0)은 θ 필터에 태울 수 없다.
        # 조용히 빼면 n_areas 가 실제 평가 대상과 어긋나므로 따로 센다 (리뷰 #12).
        irows = [r for r in all_rows if r["burden_ratio"] is not None]
        unavailable = len(all_rows) - len(irows)
        base = top3(irows, WEIGHTS, THETA)
        retentions = []
        for k in WEIGHTS:                       # 가중치 단일축 ±20% (10회)
            for delta in (0.8, 1.2):
                w = {**WEIGHTS, k: WEIGHTS[k] * delta}
                retentions.append(_overlap(base, top3(irows, w, THETA)))
        for th in (0.10, 0.20):                 # θ 변동 (2회)
            retentions.append(_overlap(base, top3(irows, WEIGHTS, th)))
        out[ind] = {
            "base_top3": base,
            "mean_retention": sum(retentions) / len(retentions) if retentions else 0.0,
            "n_perturbations": len(retentions),
            "n_areas": len(irows),
            "n_burden_unavailable": unavailable,
        }
    return out


def run(out_dir: Path) -> dict:
    return evaluate(common.load_serving_scores())

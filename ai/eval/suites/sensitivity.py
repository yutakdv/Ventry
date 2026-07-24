"""민감도 스위트 (스펙 §12-1·§4-3) — 가중치 ±20%·θ 변동 시 상위 3곳 유지율."""
from __future__ import annotations

from collections import defaultdict
from pathlib import Path

from eval import common

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
    for ind, irows in by_ind.items():
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
        }
    return out


def run(out_dir: Path) -> dict:
    return evaluate(common.load_serving_scores())

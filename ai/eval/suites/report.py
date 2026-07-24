"""성적표 조립 (스펙 §12-1 부록 1) — metrics.json + 차트 PNG."""
from __future__ import annotations

import datetime as _dt
import json
from pathlib import Path

from eval.suites import extraction, grounding, sensitivity


def _pyplot():
    """matplotlib 지연 로드 — 차트 산출 시에만 필요(경량 CI·비차트 스위트는 미의존)."""
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    return plt


def run(out_dir: Path) -> dict:
    out_dir.mkdir(parents=True, exist_ok=True)
    ext = extraction.run(out_dir)
    grd = grounding.run(out_dir)
    sens = sensitivity.run(out_dir)
    metrics = {
        "generated_at": _dt.datetime.now().isoformat(timespec="seconds"),
        "extraction": ext,
        "grounding": grd,
        "sensitivity": sens,
        "matching": {"status": "BE 소관", "note": "EligibilityFilterTest + 통합 테스트"},
        "model": {"status": "AI-08 소관", "note": "§12-2 LightGBM+SHAP"},
    }
    (out_dir / "metrics.json").write_text(
        json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    _chart_extraction(ext, out_dir / "extraction.png")
    _chart_sensitivity(sens, out_dir / "sensitivity.png")
    return metrics


def _chart_extraction(ext: dict, path: Path) -> None:
    plt = _pyplot()
    keys = list(ext["per_field"])
    vals = [ext["per_field"][k] for k in keys]
    fig, ax = plt.subplots(figsize=(8, 4))
    ax.bar(range(len(keys)), vals, color="#4C78A8")
    ax.set_xticks(range(len(keys)))
    ax.set_xticklabels(keys, rotation=45, ha="right", fontsize=7)
    ax.set_ylim(0, 1)
    ax.set_ylabel("field match rate")
    ax.set_title(f"Extraction field accuracy = {ext['field_accuracy']:.1%}")
    fig.tight_layout()
    fig.savefig(path, dpi=120)
    plt.close(fig)


def _chart_sensitivity(sens: dict, path: Path) -> None:
    plt = _pyplot()
    inds = list(sens)
    vals = [sens[i]["mean_retention"] for i in inds]
    fig, ax = plt.subplots(figsize=(5, 4))
    ax.bar(inds, vals, color="#54A24B")
    ax.set_ylim(0, 1)
    ax.set_ylabel("top-3 retention")
    ax.set_title("Sensitivity: weight ±20% / theta")
    fig.tight_layout()
    fig.savefig(path, dpi=120)
    plt.close(fig)

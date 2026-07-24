"""추출 정확도 스위트 (스펙 §12-1) — 초안↔원문 확정골드 필드 일치율."""
from __future__ import annotations

from collections import Counter
from pathlib import Path

from eval import common


def _key(row: dict) -> tuple[str, str]:
    return (row.get("doc", ""), (row.get("name") or "").strip())


def evaluate(draft: list[dict], gold: list[dict]) -> dict:
    draft_by = {_key(d): d for d in draft}
    counts = Counter(g["review_action"] for g in gold)
    kept_mod = [g for g in gold if g["review_action"] in ("유지", "수정")]

    field_hits = 0
    field_total = 0
    per_field_hits: Counter = Counter()
    per_field_total: Counter = Counter()
    for g in kept_mod:
        d = draft_by.get(_key(g), {})
        conf = g["confirmed"]
        for k in common.PRODUCT_KEYS:
            match = common.norm_field(k, d.get(k)) == common.norm_field(k, conf.get(k))
            field_hits += int(match)
            field_total += 1
            per_field_hits[k] += int(match)
            per_field_total[k] += 1

    n_km = len(kept_mod)
    n_drop = counts.get("드롭", 0)
    n_miss = counts.get("누락보완", 0)
    n_keep = counts.get("유지", 0)
    return {
        "counts": {a: counts.get(a, 0) for a in ("유지", "수정", "드롭", "누락보완")},
        "product_precision": n_km / (n_km + n_drop) if (n_km + n_drop) else 0.0,
        "product_recall": n_km / (n_km + n_miss) if (n_km + n_miss) else 0.0,
        "product_exact_rate": n_keep / n_km if n_km else 0.0,
        "field_accuracy": field_hits / field_total if field_total else 0.0,
        "per_field": {k: per_field_hits[k] / per_field_total[k]
                      for k in common.PRODUCT_KEYS if per_field_total[k]},
        "total_products": len(gold),
        "total_fields_compared": field_total,
    }


def run(out_dir: Path) -> dict:
    return evaluate(common.load_extraction_draft(), common.load_confirmed_gold())

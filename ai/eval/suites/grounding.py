"""근거 충실도 스위트 (스펙 §12-1·§5-4) — 인용 청크의 원문 verbatim 충실도.

BE-06 라이브 서빙 인용 대조가 아니라, AI 배치가 만든 청크가 원문의 바이트 정확
부분문자열인지만 검증한다("인용은 검색이지 생성이 아니다").
"""
from __future__ import annotations

import json
from pathlib import Path

from eval import common


def load_gold() -> list[dict]:
    lines = (common.GOLD_DIR / "grounding_quotes.jsonl").read_text(encoding="utf-8").splitlines()
    return [json.loads(ln) for ln in lines if ln.strip()]


def evaluate(gold: list[dict], docs: dict[str, str]) -> dict:
    mismatches = [g["product_id"] for g in gold if g["quote"] not in docs.get(g["doc"], "")]
    draft = common.load_extraction_draft()
    total = len(draft)
    linked = len(gold)
    gold_docs = {g["doc"] for g in gold}
    draft_docs = {d["doc"] for d in draft}
    # §5-3 정당 null(깨진 원문 → 청크 불가) vs 예상 밖 null(클린인데 근거 없음) 구분
    legit_null = sorted(d for d in draft_docs
                        if not common.is_clean_source(d) and d not in gold_docs)
    unexpected_null = sorted(d for d in draft_docs
                             if common.is_clean_source(d) and d not in gold_docs)
    return {
        "verbatim_match_rate": (linked - len(mismatches)) / linked if linked else 0.0,
        "linked": linked,
        "total": total,
        "coverage": linked / total if total else 0.0,
        "mismatches": mismatches,
        "legitimate_null_docs": legit_null,
        "unexpected_null_docs": unexpected_null,
    }


def run(out_dir: Path) -> dict:
    gold = load_gold()
    docs = {g["doc"]: common.load_source_text(g["doc"]) for g in gold}
    return evaluate(gold, docs)

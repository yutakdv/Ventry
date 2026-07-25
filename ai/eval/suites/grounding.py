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
    """근거 충실도 — 두 층을 잰다.

    - **적재 청크 verbatim**: `db/init/20_finance.sql` 에 실제로 실린 청크가 원문의
      부분문자열인가. 화면에 뜨는 인용문이 곧 이 텍스트이므로 이것이 본 지표다.
      대조 기준은 인쇄 장식·NUL 을 뺀 정제 원문이다 (assumptions #50, 리뷰 #4).
    - **골드 스팟체크**: 사람이 고른 인용의 원문 일치 — 회귀 감시용 보조 지표.

    `coverage` 는 **적재 상품 중 인용이 붙은 비율**이다. 예전 정의(골드 13 ÷ 초안 29)는
    분자·분모의 단위가 달라 "인용을 비웠다"는 서술과도 어긋났다 (리뷰 #5).
    """
    products = common.load_finance_products()
    chunks = common.load_finance_chunks()
    linked_products = [pid for pid, ref in products if ref]

    chunk_mismatches = [
        chunk_id for chunk_id, text in chunks.items()
        if text not in common.load_source_text_clean(chunk_id.rsplit("#", 1)[0])
    ]

    gold_mismatches = [g["product_id"] for g in gold if g["quote"] not in docs.get(g["doc"], "")]

    # §5-3 정당 null(깨진 원문 → 청크 불가) vs 예상 밖 null(클린인데 근거 없음) 구분.
    # 모수는 **인용이 비어 있는 적재 상품의 문서**다 — 골드 수록 여부로 판정하던 옛 정의는
    # 골드에 없을 뿐 실제로는 인용이 붙은 문서까지 '예상 밖 null' 로 몰았다 (리뷰 #5).
    doc_of = {p.get("product_id"): p.get("doc", "") for p in common.load_reviewed_products()}
    unlinked_docs = {doc_of.get(pid, "") for pid, ref in products if not ref}
    unlinked_docs.discard("")
    legit_null = sorted(d for d in unlinked_docs if not common.is_clean_source(d))
    unexpected_null = sorted(d for d in unlinked_docs if common.is_clean_source(d))
    n_chunks = len(chunks)
    return {
        "shipped_products": len(products),
        "shipped_chunks": n_chunks,
        "linked_products": len(linked_products),
        "coverage": len(linked_products) / len(products) if products else 0.0,
        "chunk_verbatim_rate": (n_chunks - len(chunk_mismatches)) / n_chunks if n_chunks else 0.0,
        "chunk_mismatches": chunk_mismatches,
        "gold_spotcheck_n": len(gold),
        "gold_verbatim_rate": (len(gold) - len(gold_mismatches)) / len(gold) if gold else 0.0,
        "gold_mismatches": gold_mismatches,
        "legitimate_null_docs": legit_null,
        "unexpected_null_docs": unexpected_null,
    }


def run(out_dir: Path) -> dict:
    gold = load_gold()
    docs = {g["doc"]: common.load_source_text(g["doc"]) for g in gold}
    return evaluate(gold, docs)

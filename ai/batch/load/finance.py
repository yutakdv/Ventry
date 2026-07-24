"""정책자금 구조화 적재 (AI-06-2, 스펙 §5-4).

검수본(또는 자동추출 클린) → finance_product + finance_doc_chunk.
청크 text 는 공고문 원문 그대로(LLM 재작성 금지 — 인용은 검색이지 생성이 아니다).
실행: python -m batch.load finance
"""
from __future__ import annotations

import datetime as _dt
import json
import re
from pathlib import Path

import pandas as pd

from batch.extract.funding_llm import OUT_DIR, validate_product
from batch.paths import INTERIM_DIR, REPO_ROOT, logger

FUNDING_DIR = INTERIM_DIR / "funding_docs"
DB_INIT = REPO_ROOT / "db" / "init"
_SPLIT = re.compile(r"\n\s*\n|\n- \d+ -\n")
# finance_product.rate 는 nullable(스키마 A안, 3인 합의) — 변동금리는 rate=NULL + rate_note 원문.
# 기준금리 실값을 지어내지 않는다(조작 금지, 스펙 §0-1). 검수에서 소진공 공시 기준금리로 채운다.
_VAR_HINT = re.compile(r"기준금리|CD금리|변동")
_ADDON = re.compile(r"(기준금리|CD금리)\s*\+")  # 가산금리 → 절대금리 아님(base 미상)
_PCT = re.compile(r"([\d.]+)\s*%")


def rate_fields(product: dict) -> tuple[float | None, str, str | None]:
    """(rate, rate_type, rate_note) — 조작 없이 원문 기록.

    고정금리는 숫자·fixed. 변동금리는 공시 절대값이 있으면 그 값(variable), 없으면 NULL + 원문 note.
    """
    note = (product.get("rate_note") or "").strip() or None
    rate = product.get("rate")
    if isinstance(rate, (int, float)):
        return float(rate), "fixed", note
    is_var = bool(note and _VAR_HINT.search(note))
    rate_type = "variable" if is_var else "fixed"
    if note and _ADDON.search(note):
        return None, "variable", note  # "기준금리+X%p" → 절대금리 없음, 지어내지 않음
    if note and (m := _PCT.search(note)):
        return float(m.group(1)), rate_type, note  # 공시 절대 %("최저 연 X%")
    return None, rate_type, note  # 순수 변동·불명 → NULL
# source_quote 원문 청크는 클린 텍스트만 사용 — CID/JS 깨진 txt(서울신보 등)는 verbatim 불가라
# 날조 대신 doc_chunk_ref=null (스펙 §5-4 "인용은 검색이지 생성이 아니다", 코드리뷰 S1)
_KEYWORDS = ("대출", "융자", "한도", "금리", "보증", "지원", "소상공인", "상환", "기업")
_CLEAN_DENSITY = 3.0  # 1000자당 키워드 히트


def _is_clean(text: str) -> bool:
    if not text:
        return False
    return sum(text.count(k) for k in _KEYWORDS) / len(text) * 1000 >= _CLEAN_DENSITY


def chunk_document(doc_name: str, text: str) -> list[dict]:
    """원문을 문단/페이지 단위로 분할. text 는 원문 그대로 보존."""
    chunks = []
    for part in (p.strip() for p in _SPLIT.split(text)):
        if part:
            chunks.append({"chunk_id": f"{doc_name}#{len(chunks)}", "text": part})
    return chunks


def _load_doc_chunks(doc: str, docs_dir: Path, cache: dict) -> list[dict]:
    if doc not in cache:
        path = Path(docs_dir) / f"{doc}.txt"
        text = path.read_text(encoding="utf-8") if path.exists() else ""
        cache[doc] = chunk_document(doc, text) if _is_clean(text) else []
    return cache[doc]


def build_finance(reviewed: list[dict], docs_dir: Path) -> dict[str, pd.DataFrame]:
    """검수본 → {finance_product, finance_doc_chunk} (doc_chunk_ref 연결)."""
    today = _dt.date.today().isoformat()
    doc_cache: dict[str, list[dict]] = {}
    used_chunks: dict[str, dict] = {}
    products = []
    for idx, p in enumerate(reviewed):
        doc = p.get("doc", "")
        pid = p.get("product_id") or f"F-{idx:03d}"
        rate, rate_type, rate_note = rate_fields(p)
        chunks = _load_doc_chunks(doc, docs_dir, doc_cache)
        name = p.get("name") or ""
        ref = next((c for c in chunks if name and name in c["text"]),
                   chunks[0] if chunks else None)
        chunk_ref = None
        if ref is not None:  # 클린 원문 청크가 있을 때만 링크 (없으면 source_quote 없음)
            used_chunks.setdefault(ref["chunk_id"], {
                "chunk_id": ref["chunk_id"], "product_id": pid,
                "doc_meta": {"org": p.get("org"), "doc": doc, "date": p.get("notice_date")},
                "text": ref["text"]})
            chunk_ref = ref["chunk_id"]
        products.append({
            "product_id": pid, "name": name, "org": p.get("org"),
            "max_age": p.get("max_age"), "industries": p.get("industries"),
            "regions": p.get("regions"), "pre_startup_only": bool(p.get("pre_startup_only")),
            "amount_max": p.get("amount_max"),
            "rate": rate, "rate_type": rate_type, "rate_note": rate_note,
            "term_months": p.get("term_months"), "exclusive_group": p.get("exclusive_group"),
            "status": p.get("status") or "open", "notice_date": p.get("notice_date"),
            "data_as_of": p.get("notice_date") or "2026", "source_org": p.get("org"),
            "source_url": p.get("source_url") or "", "source_collected": today,
            "doc_chunk_ref": chunk_ref,
        })
    chunk_rows = [{"chunk_id": c["chunk_id"], "product_id": c["product_id"],
                   "doc_meta": json.dumps(c["doc_meta"], ensure_ascii=False), "text": c["text"]}
                  for c in used_chunks.values()]
    return {"finance_product": pd.DataFrame(products),
            "finance_doc_chunk": pd.DataFrame(chunk_rows)}


def run() -> None:
    from batch.load import emit

    extracted = json.loads((OUT_DIR / "extracted.json").read_text(encoding="utf-8"))
    clean = [p for p in extracted if not validate_product(p)]
    logger.info("finance: 추출 %d → 클린 %d 적재 (검수 전 잠정)", len(extracted), len(clean))
    tables = build_finance(clean, FUNDING_DIR)
    header = "Ventry 정책자금 구조화 (AI-06, 검수 전 자동추출 클린 — 전건 검수 후 재생성)"
    emit.emit_sql(tables, DB_INIT / "20_finance.sql",
                  ["finance_product", "finance_doc_chunk"], header=header)
    logger.info("→ %s (product %d · chunk %d)", DB_INIT / "20_finance.sql",
                len(tables["finance_product"]), len(tables["finance_doc_chunk"]))

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

from batch.collect import collected
from batch.extract.funding_llm import OUT_DIR, validate_product
from batch.paths import INTERIM_DIR, REPO_ROOT, logger

FUNDING_DIR = INTERIM_DIR / "funding_docs"
DB_INIT = REPO_ROOT / "db" / "init"
_SPLIT = re.compile(r"\n\s*\n|\n- \d+ -\n")
# finance_product.rate 는 nullable(스키마 A안, 3인 합의) —
# 절대금리 미상이면 rate=NULL + rate_note 원문.
# 기준금리 실값을 지어내지 않는다(조작 금지, 스펙 §0-1). 소진공 변동금리는 공시 「금리안내」
# 표(‘26년 3/4분기 기준금리 3.85%)로 검수에서 채웠다 — docs/assumptions.md #34, 이슈 #72.
# rate 는 월 상환액(§4-2·§353)에 쓰는 연 대출금리(%). 보증료율·차감폭·보증금비율은 rate 가 아니다.
_VAR_HINT = re.compile(r"기준금리|CD금리|변동")
# 절대금리 미상(base 미상) → 지어내지 않음: "기준금리/CD금리+가산" · "은행금리에서 X% 차감"
_BASE_VAR = re.compile(r"(기준금리|CD금리)\s*\+|은행금리에서.*차감")
_FIX_LOAN = re.compile(r"연\s*([\d.]+)\s*%\s*고정금리")  # note 에 명시된 고정 대출금리
_FLOOR = re.compile(r"최저\s*연\s*([\d.]+)\s*%")          # 공시 최저(floor) 절대금리


def _in_range(x: float | None) -> bool:
    return x is not None and 0 <= x < 20  # 연 % 유효 범위 (지어낸/오추출 값 차단)


def rate_fields(product: dict) -> tuple[float | None, str, str | None]:
    """(rate, rate_type, rate_note) — 조작 없이 원문 기록(§0-1).

    rate 는 월 상환액에 쓰는 연 대출금리(%). 단일 숫자로 모르면 NULL + rate_note 원문.
    보증상품의 추출 rate 는 보증료율일 수 있어 신뢰하지 않는다(note 의 고정 대출금리만 사용).
    """
    note = (product.get("rate_note") or "").strip() or None
    ptype = product.get("product_type", "loan")
    raw = product.get("rate")
    if note and _BASE_VAR.search(note):
        return None, "variable", note  # 기준금리+가산·은행금리 차감 → 절대금리 미상
    if ptype == "guarantee":
        if (m := _FIX_LOAN.search(note or "")) and _in_range(float(m.group(1))):
            return float(m.group(1)), "fixed", note  # note 에 명시된 고정 대출금리
        return None, "variable", note  # 보증료율은 대출금리 아님 → NULL
    if isinstance(raw, (int, float)) and _in_range(float(raw)):
        # 검수본이 해당 분기 실값을 확정한 경우 — 값은 절대금리지만 성격은 여전히 분기 변동이다.
        # note 에 기준금리/변동 단서가 있으면 variable 로 표기해야 사용자가 재확인 시점을 안다.
        return float(raw), ("variable" if note and _VAR_HINT.search(note) else "fixed"), note
    if (m := _FLOOR.search(note or "")) and _in_range(float(m.group(1))):  # 공시 최저 절대값
        return float(m.group(1)), ("variable" if note and _VAR_HINT.search(note) else "fixed"), note
    return None, ("variable" if note and _VAR_HINT.search(note) else "fixed"), note
# source_quote 원문 청크는 클린 텍스트만 사용 — CID/JS 깨진 txt(서울신보 등)는 verbatim 불가라
# 날조 대신 doc_chunk_ref=null (스펙 §5-4 "인용은 검색이지 생성이 아니다", 코드리뷰 S1)
_KEYWORDS = ("대출", "융자", "한도", "금리", "보증", "지원", "소상공인", "상환", "기업")
_CLEAN_DENSITY = 3.0  # 1000자당 키워드 히트


def _is_clean(text: str) -> bool:
    if not text:
        return False
    return sum(text.count(k) for k in _KEYWORDS) / len(text) * 1000 >= _CLEAN_DENSITY


# PDF 인쇄 머리말/꼬리말 — 브라우저 인쇄 시 주입되는 페이지 장식이지 공고문 문장이 아니다.
# 한글 폰트 CID 가 깨져 모지바케로 남으므로("2026. 7. 21. য়੹ 1:03…", "1ಕ੉૑/6ಕ੉૑https://…")
# 인용문에 섞이면 화면에 깨진 글자가 노출된다. 문장을 고쳐 쓰는 것이 아니라 장식 줄만 버린다
# — 남는 문장은 여전히 원문 그대로이고 verbatim 대조도 통과한다 (assumptions #50).
_PRINT_HEADER = re.compile(r"^\d{4}\.\s*\d{1,2}\.\s*\d{1,2}\.")   # 인쇄 날짜·시각 머리말
_PRINT_FOOTER = re.compile(r"^\d+\S{0,6}/\d+\S{0,6}https?://")     # N/M 페이지 + 원본 URL 꼬리말
# 인쇄본에 딸려온 사이트 내비게이션 — 링크 경로가 괄호로 노출된 줄(`서울지역 (/web/…`,
# `(/web/SUP01/…)+소공인특화지원`, 빵부스러기 메뉴). 산문에는 이 형태가 나타나지 않는다.
# 실측 836줄 중 23줄이며 전부 소진공 지원사업안내 1문서의 링크 목록이다 (가정 #61).
_PRINT_NAV = re.compile(r"\(/web/")


def strip_print_artifacts(text: str) -> str:
    """인쇄 머리말/꼬리말 줄 + NUL 제거. 본문 문장은 한 글자도 건드리지 않는다.

    NUL(`\\x00`)은 pypdf 텍스트 추출이 남기는 제어문자이지 공고문의 글자가 아니다.
    그대로 덤프에 실으면 psql 이 NUL 주변 구간을 조용히 삼켜 DB 저장본이 원문보다
    짧아진다(실측 411자 소실) — 「인용은 검색이지 생성이 아니다」가 깨지는 지점이라,
    지우는 쪽이 오히려 verbatim 을 복원한다 (스펙 §5-4, 리뷰 #4).

    **장식 줄 자리에는 빈 줄을 남긴다.** 그 줄은 실제로 페이지가 바뀐 지점이라 버릴 정보가
    아니라 **문단 경계**다. 그냥 지우면 앞 페이지 끝 문장과 뒤 페이지 첫 문장이 한 문단으로
    붙어, 빈 줄이 없는 문서(융자공고의 `- N -` 같은 표식이 없는 인쇄본)는 통짜 청크 하나가
    된다 — 인용문이 문서 전체가 되던 원인이다 (가정 #60).
    """
    kept = ["" if (_PRINT_HEADER.match(ln) or _PRINT_FOOTER.match(ln)
                   or _PRINT_NAV.search(ln)) else ln
            for ln in text.replace("\x00", "").splitlines()]
    return "\n".join(kept)


def chunk_document(doc_name: str, text: str) -> list[dict]:
    """원문을 문단/페이지 단위로 분할. text 는 인쇄 장식 줄을 뺀 원문 그대로 보존."""
    chunks = []
    for part in (p.strip() for p in _SPLIT.split(strip_print_artifacts(text))):
        if part:
            chunks.append({"chunk_id": f"{doc_name}#{len(chunks)}", "text": part})
    return chunks


_NAME_TOKEN = re.compile(r"[0-9A-Za-z가-힣]{2,}")


def select_chunk(name: str, chunks: list[dict]) -> dict | None:
    """상품명이 실제로 등장하는 청크를 고른다. 없으면 None (인용 비움).

    '문서 첫 문단' 폴백을 쓰지 않는다 — 그 문단은 대개 표지·브로슈어 머리말이고 그 상품의
    자격 근거가 아니다. 근거가 아닌 문단을 근거로 지목하는 것은 날조는 아니어도 **귀속
    오류**이며, 이 서비스가 임베딩 검색 대신 id 직접 조회를 택한 논거(심사_QA 20)와 정면으로
    어긋난다 (스펙 §5-4, 리뷰 #3).

    점수 = 상품명 토큰 중 청크에 등장하는 개수. 동점이면 **더 짧은** 청크가 이긴다 —
    통짜 첫 문단은 토큰을 많이 품지만 구체적인 근거는 짧은 문단에 있다.
    """
    tokens = _NAME_TOKEN.findall(name or "")
    if not tokens or not chunks:
        return None
    required = max(1, (len(tokens) + 1) // 2)  # 토큰 과반이 등장해야 인정
    best = min(
        chunks,
        key=lambda c: (-sum(1 for t in tokens if t in c["text"]), len(c["text"])),
    )
    hits = sum(1 for t in tokens if t in best["text"])
    return best if hits >= required else None


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
        ref = select_chunk(name, chunks)
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
            "existing_business_only": bool(p.get("existing_business_only")),
            "amount_max": p.get("amount_max"),
            "rate": rate, "rate_type": rate_type, "rate_note": rate_note,
            "term_months": p.get("term_months"), "exclusive_group": p.get("exclusive_group"),
            "status": p.get("status") or "open", "notice_date": p.get("notice_date"),
            "data_as_of": p.get("notice_date") or "2026", "source_org": p.get("org"),
            "source_url": p.get("source_url") or "",
            "source_collected": collected.for_doc(doc, today),
            "doc_chunk_ref": chunk_ref,
        })
    chunk_rows = [{"chunk_id": c["chunk_id"], "product_id": c["product_id"],
                   "doc_meta": json.dumps(c["doc_meta"], ensure_ascii=False), "text": c["text"]}
                  for c in used_chunks.values()]
    return {"finance_product": pd.DataFrame(products),
            "finance_doc_chunk": pd.DataFrame(chunk_rows)}


def run() -> None:
    from batch.load import emit

    # 검수본(reviewed.json) 있으면 그것으로 재생성(전건 검수 결과), 없으면 자동추출 클린(잠정).
    reviewed_path = OUT_DIR / "reviewed.json"
    if reviewed_path.exists():
        source = json.loads(reviewed_path.read_text(encoding="utf-8"))
        header = "Ventry 정책자금 구조화 (AI-06, 전건 검수본 reviewed.json 재생성)"
        logger.info("finance: 검수본 %d건 재생성", len(source))
    else:
        extracted = json.loads((OUT_DIR / "extracted.json").read_text(encoding="utf-8"))
        source = [p for p in extracted if not validate_product(p)]
        header = "Ventry 정책자금 구조화 (AI-06, 검수 전 자동추출 클린 — 전건 검수 후 재생성)"
        logger.info("finance: 추출 %d → 클린 %d 적재 (검수 전 잠정)", len(extracted), len(source))
    tables = build_finance(source, FUNDING_DIR)
    emit.emit_sql(tables, DB_INIT / "20_finance.sql",
                  ["finance_product", "finance_doc_chunk"], header=header)
    logger.info("→ %s (product %d · chunk %d)", DB_INIT / "20_finance.sql",
                len(tables["finance_product"]), len(tables["finance_doc_chunk"]))

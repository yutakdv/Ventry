"""정책자금·보증·대출 공개 문서 텍스트 추출 (AI-02 → AI-06 RAG, 스펙 §5-4).

로컬 raw PDF(소진공·서울신보·KB 등 — 인벤토리 ai/data/raw/README.md).
텍스트 추출 → interim/(AI-06에서 LLM 구조화 + 원문 문단 청크 벡터DB 적재).
추출은 인용이지 생성이 아니다: source_quote는 원문 그대로 (§5-4).

⚠️ 서울신보 6종은 브라우저 프린트 폰트(ToUnicode 누락)라 텍스트 추출 시 한글 깨짐 →
   OCR 또는 핵심 조건 수동 전사 필요(README §4). 아래에서 깨짐 의심 시 경고 표시.
"""
from __future__ import annotations

from pathlib import Path

from batch.collect._common import (
    INTERIM_DIR,
    RAW_DIR,
    load_env,
    logger,
    setup_logging,
)


def extract_text(path: Path) -> str:
    from pypdf import PdfReader  # 배치 전용 의존

    reader = PdfReader(str(path))
    return "\n".join((page.extract_text() or "") for page in reader.pages)


def looks_garbled(text: str, *, sample: int = 2000) -> bool:
    """한글 비율이 매우 낮으면 폰트 추출 실패로 간주(서울신보 프린트본)."""
    head = text[:sample]
    stripped = head.strip()
    if not stripped:
        return True
    hangul = sum(1 for ch in head if "가" <= ch <= "힣")
    return hangul / len(stripped) < 0.05


def run(env: dict[str, str], session: object | None = None) -> None:
    out_dir = INTERIM_DIR / "funding_docs"
    out_dir.mkdir(parents=True, exist_ok=True)
    pdfs = sorted(RAW_DIR.glob("*.pdf"))
    if not pdfs:
        logger.warning("PDF 없음: %s/*.pdf — 매니페스트(README.md) 참조", RAW_DIR)
        return
    garbled_count = 0
    for pdf in pdfs:
        text = extract_text(pdf)
        (out_dir / f"{pdf.stem}.txt").write_text(text, encoding="utf-8")
        garbled = looks_garbled(text)
        garbled_count += garbled
        logger.info("%s: %d자%s", pdf.name, len(text), " ⚠️ 깨짐(OCR 필요)" if garbled else "")
    if garbled_count:
        logger.warning("텍스트 깨짐 %d건 — AI-06에서 OCR/수동 전사 필요", garbled_count)


def main() -> None:
    setup_logging()
    run(load_env())


if __name__ == "__main__":
    main()

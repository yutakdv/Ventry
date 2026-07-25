"""정책자금·보증·대출 공개 문서 텍스트 추출 (AI-02 → AI-06 원문 인용, 스펙 §5-4).

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
    text = "\n".join((page.extract_text() or "") for page in reader.pages)
    # 서울신보 프린트본은 ToUnicode 누락으로 서로게이트 코드포인트를 뱉는다 → UTF-8 저장 불가.
    # 어차피 판독 불가 구간이라 U+FFFD로 치환해 통과시키고, 깨짐 판정에서 걸러낸다.
    return text.encode("utf-8", "replace").decode("utf-8")


# 한국어에서 빈출하는 음절. 정상 문서는 전체 한글의 20% 이상이 여기 속하지만,
# 폰트 ToUnicode 누락으로 깨진 문서는 '폀·햋·밃·쨊' 같은 희귀 음절만 쏟아내 0%에 가깝다.
_COMMON_SYLLABLES = frozenset(
    "이다는에하지의로기있인스니대시서아한자도리어고상정나가무부수전소원을를은과"
    "와안내년월일등및또그것위중제조회관업금액용신청보증출개발생활문화교육장국민단"
    "체성형방법목적요건간환사면때말등말차물면점표사용현재확인신규제출"
)


def looks_garbled(text: str, *, sample: int = 4000) -> bool:
    """폰트 추출 실패(서울신보 프린트본) 판별.

    한글 비율만 보면 깨진 글자도 완성형 한글이라 통과해버린다 → **빈출 음절 비중**으로 본다.
    """
    head = text[:sample]
    if not head.strip():
        return True
    hangul = [ch for ch in head if "가" <= ch <= "힣"]
    if len(hangul) < 30:  # 한글이 거의 없으면 추출 실패로 간주
        return True
    common = sum(1 for ch in hangul if ch in _COMMON_SYLLABLES)
    return common / len(hangul) < 0.20


# 웹 1차 출처(funding_web)가 정본인 문서 — PDF 프린트본은 모지바케라 덮어쓰면 안 된다.
# 이 가드가 없으면 `make collect` 도중 funding_web 이 네트워크 실패로 건너뛸 때, 앞서 돈
# funding_docs 가 이미 좋은 텍스트를 깨진 것으로 갈아엎은 뒤다 (assumptions #32, 리뷰 #9).
WEB_CANONICAL_PREFIX = "서울신보_"


def run(env: dict[str, str], session: object | None = None) -> None:
    out_dir = INTERIM_DIR / "funding_docs"
    out_dir.mkdir(parents=True, exist_ok=True)
    pdfs = sorted(RAW_DIR.glob("*.pdf"))
    if not pdfs:
        logger.warning("PDF 없음: %s/*.pdf — 매니페스트(README.md) 참조", RAW_DIR)
        return
    garbled_count = 0
    for pdf in pdfs:
        target = out_dir / f"{pdf.stem}.txt"
        if pdf.stem.startswith(WEB_CANONICAL_PREFIX) and target.exists():
            logger.info("%s: 웹 정본 유지 — PDF 추출본으로 덮지 않음", pdf.name)
            continue
        text = extract_text(pdf)
        target.write_text(text, encoding="utf-8")
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

"""정책자금·보증·대출 공개 문서 텍스트 추출 (AI-02 → AI-06 원문 인용, 스펙 §5-4).

로컬 raw PDF(소진공·서울신보·KB 등 — 인벤토리 ai/data/raw/README.md).
텍스트 추출 → interim/(AI-06에서 LLM 구조화 + 원문 문단 청크 벡터DB 적재).
추출은 인용이지 생성이 아니다: source_quote는 원문 그대로 (§5-4).

⚠️ 서울신보 6종은 브라우저 프린트 폰트(ToUnicode 누락)라 텍스트 추출 시 한글 깨짐 →
   OCR 또는 핵심 조건 수동 전사 필요(README §4). 아래에서 깨짐 의심 시 경고 표시.

⚠️ 브라우저 인쇄본은 볼드를 **덧그리기**로 낸다 — 같은 글자열을 0.2pt 씩 밀며 4번 그린다.
   그대로 두면 인용문이 `융자대상융자대상융자대상융자대상` 이 된다 (이슈 #150).
   `_overprint_runs`/`_collapse_overprint` 가 글자 좌표를 근거로 사본만 접는다.
"""
from __future__ import annotations

from pathlib import Path

from batch import collected
from batch.collect._common import (
    INTERIM_DIR,
    RAW_DIR,
    load_env,
    logger,
    setup_logging,
)

# 겹쳐 그린 것으로 볼 좌표 오차(pt). 브라우저 인쇄의 가짜 볼드는 같은 글자열을 0.2pt 남짓
# 어긋나게 4번 그린다 — 4pt 이상 글꼴이면 이웃한 별개 글자열이 이 안에 들어올 수 없다.
_OVERPRINT_EPS_PT = 2.0
# 덧그려지는 한 덩어리가 쪼개져 나오는 최대 조각 수. `매우불만족` 은 '매우'·'불'·'만족' 3조각,
# `자세한 사항은 여기로 문의하세요` 는 음절까지 쪼개져 9조각으로 나온다 — 주기를 못 보면 그
# 덩어리만 접히지 않고 남는다. 좌표 일치를 함께 요구하므로 주기를 늘려도 오검출이 늘지 않는다.
_MAX_OVERPRINT_PERIOD = 24


def _overprint_runs(page) -> tuple[str, list[tuple[str, int]]]:
    """페이지 추출문과, **같은 글자열이 겹쳐 그려진** 구간 목록 (글자열, 그린 횟수).

    브라우저 인쇄본의 볼드는 폰트가 아니라 **덧그리기**로 표현된다 — 소진공 지원사업안내
    `융자대상` 은 x 를 0.21pt 씩 밀며 4번 그려진다. pypdf 는 그릴 때마다 글자를 뱉으므로
    추출문에는 `융자대상융자대상융자대상융자대상` 이 남는다. 글자 위치로 보면 한 단어다.

    낱말이 여러 조각으로 나뉘어 나오면 반복도 조각 단위로 번갈아 일어나므로
    (`불`·`만족`·`불`·`만족`…), 인접 동일 레코드가 아니라 **레코드 열의 주기**를 본다.
    가장 짧은 주기를 먼저 채택한다 — `융자대상` 은 주기 1, `매우불만족` 은 주기 3이다.
    """
    recs: list[tuple[str, float, float]] = []

    def visit(text, cm, tm, font_dict, font_size):
        if text:  # 빈 문자열 레코드가 덧그리기 사이에 끼어 주기를 끊는다
            recs.append((text,
                         tm[4] * cm[0] + tm[5] * cm[2] + cm[4],
                         tm[4] * cm[1] + tm[5] * cm[3] + cm[5]))

    text = page.extract_text(visitor_text=visit) or ""
    return text, _runs_from_records(recs)


def _repeat_count(recs: list[tuple[str, float, float]], start: int, period: int) -> int:
    """recs[start:] 에서 길이 `period` 인 레코드 열이 **같은 자리에** 몇 번 반복되는가."""
    unit = [r[0] for r in recs[start:start + period]]
    count = 1
    while True:
        here = start + count * period
        if here + period > len(recs):
            return count
        if [r[0] for r in recs[here:here + period]] != unit:
            return count
        if (abs(recs[here][1] - recs[start][1]) >= _OVERPRINT_EPS_PT
                or abs(recs[here][2] - recs[start][2]) >= _OVERPRINT_EPS_PT):
            return count
        count += 1


def _shortest_repeat(recs, start: int) -> tuple[int, int] | None:
    """recs[start:] 를 여는 **가장 짧은** 덧그리기 주기 → (주기, 반복 횟수). 없으면 None."""
    for period in range(1, _MAX_OVERPRINT_PERIOD + 1):
        if start + 2 * period > len(recs):
            return None
        count = _repeat_count(recs, start, period)
        if count > 1:
            return period, count
    return None


def _runs_from_records(recs: list[tuple[str, float, float]]) -> list[tuple[str, int]]:
    runs: list[tuple[str, int]] = []
    i = 0
    while i < len(recs):
        found = _shortest_repeat(recs, i)
        if found is None:
            i += 1
            continue
        period, count = found
        unit = "".join(r[0] for r in recs[i:i + period])
        if unit.strip():  # 공백만 반복되는 자리는 자간이지 볼드가 아니다
            runs.append((unit, count))
        i += count * period
    return runs


def _collapse_overprint(text: str, runs: list[tuple[str, int]]) -> str:
    """덧그린 사본을 1회로 접는다. **그 외 한 글자도 건드리지 않는다.**

    원문 변형이 아니라 렌더링 중복 제거다 — 지우는 쪽이 오히려 지면에 인쇄된 문장을
    복원한다. NUL·인쇄 장식 줄과 같은 성격의 정제이며 (가정 #50·#60·#61), 근거는
    본문 어휘가 아니라 **글자 좌표**라 §5-4 「인용은 검색이지 생성이 아니다」와 충돌하지
    않는다. 추출문에서 사본이 실제로 연달아 붙어 있을 때만 접고, 아니면 그냥 둔다.
    """
    out: list[str] = []
    cursor = 0
    for unit, count in runs:
        repeated = unit * count
        found = text.find(repeated, cursor)
        if found < 0:
            continue
        out.append(text[cursor:found])
        out.append(unit)
        cursor = found + len(repeated)
    out.append(text[cursor:])
    return "".join(out)


def extract_text(path: Path) -> str:
    from pypdf import PdfReader  # 배치 전용 의존

    reader = PdfReader(str(path))
    pages, collapsed = [], 0
    for page in reader.pages:
        raw, runs = _overprint_runs(page)
        pages.append(_collapse_overprint(raw, runs))
        collapsed += len(runs)
    if collapsed:
        logger.info("%s: 덧그린 볼드 %d군데 접음", path.name, collapsed)
    text = "\n".join(pages)
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
        # 원본을 내려받은 시각이 그 파일의 mtime 이다 — 추출을 다시 돌려도 수집일은 안 바뀐다 (#93)
        collected.record([pdf.stem], collected.stamp_from_mtime(pdf))
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

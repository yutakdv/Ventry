"""덧그린 볼드(오버프린트) 접기 단위 테스트 (이슈 #150, 스펙 §5-4).

브라우저 인쇄본은 볼드를 폰트가 아니라 같은 글자열 4회 덧그리기로 낸다. 원문 변형이
아니라 렌더링 중복 제거이므로 「인용은 검색이지 생성이 아니다」와 충돌하지 않지만,
**사본만** 접고 나머지는 한 글자도 안 건드린다는 것이 그 논거의 전제다 — 그것을 잰다.
"""
from batch.collect.funding_docs import _collapse_overprint, _runs_from_records


def rec(text, x, y=100.0):
    return (text, x, y)


def test_detects_single_record_overprint():
    """`융자대상` — 한 조각짜리 덧그리기 4회. x 가 0.21pt 씩만 밀린다."""
    recs = [rec("융자대상", 170.77), rec("융자대상", 170.98),
            rec("융자대상", 170.98), rec("융자대상", 171.19)]
    assert _runs_from_records(recs) == [("융자대상", 4)]


def test_detects_split_word_overprint():
    """`불만족` 은 '불'·'만족' 두 조각으로 나오고 조각 단위로 번갈아 반복된다 (이슈 #150).

    인접 동일 레코드만 보면 '불' 다음이 '만족' 이라 하나도 안 걸린다 — 그래서 주기를 본다.
    """
    recs = []
    for dx in (0.0, 0.18, 0.18, 0.36):
        recs += [rec("불", 366.53 + dx), rec("만족", 372.50 + dx)]
    assert _runs_from_records(recs) == [("불만족", 4)]


def test_keeps_neighbouring_identical_words_apart():
    """같은 글자열이라도 **다른 자리**에 그려졌으면 표의 이웃 칸이지 덧그리기가 아니다."""
    recs = [rec("구분", 100.0), rec("구분", 140.0), rec("구분", 180.0), rec("구분", 220.0)]
    assert _runs_from_records(recs) == []


def test_keeps_same_column_on_different_lines_apart():
    """x 가 같아도 줄(y)이 다르면 세로로 반복된 셀이다."""
    recs = [rec("구분", 100.0, y) for y in (400.0, 380.0, 360.0, 340.0)]
    assert _runs_from_records(recs) == []


def test_whitespace_run_is_not_a_bold_run():
    """공백만 반복되는 자리는 자간 조정이라 접지 않는다."""
    recs = [rec(" ", 100.0), rec(" ", 100.1), rec(" ", 100.1), rec(" ", 100.2)]
    assert _runs_from_records(recs) == []


def test_collapses_only_the_marked_run():
    text = "머리말\n융자대상융자대상융자대상융자대상\n본문 그대로"
    assert (_collapse_overprint(text, [("융자대상", 4)])
            == "머리말\n융자대상\n본문 그대로")


def test_untouched_when_no_runs():
    text = "공고합니다.\n융자대상\n1533-0100"
    assert _collapse_overprint(text, []) == text


def test_keeps_repetition_that_is_not_overprint():
    """좌표가 겹치지 않아 목록에 없으면 글자가 반복돼도 접지 않는다.

    판단 근거는 「같은 글자가 붙어 있다」가 아니라 「같은 자리에 그려졌다」다.
    """
    text = "구분 구분 구분 구분"
    assert _collapse_overprint(text, []) == text


def test_skips_run_that_is_not_adjacent_in_extracted_text():
    """좌표는 겹쳐도 추출문에서 사본이 붙어 있지 않으면 손대지 않는다 (안전 폴백)."""
    text = "융자대상 안내 융자대상"
    assert _collapse_overprint(text, [("융자대상", 2)]) == text


def test_collapses_multiple_runs_in_order():
    text = "13571357135713571533-01001533-01001533-01001533-0100 끝"
    runs = [("1357", 4), ("1533-0100", 4)]
    assert _collapse_overprint(text, runs) == "13571533-0100 끝"


def test_second_run_does_not_rewind_past_the_first():
    """커서가 앞으로만 간다 — 같은 글자열이 두 번 덧그려져도 각각 한 번씩만 접힌다."""
    text = "가가가가 중간 가가가가"
    assert _collapse_overprint(text, [("가", 4), ("가", 4)]) == "가 중간 가"

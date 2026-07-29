package com.ventry.api.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * #96 — 리스크 검증 반박문 <b>검증기</b>. 프롬프트로 부탁한 제약은 제약이 아니고, 어긴 응답을
 * 버릴 수 있어야 제약이다. 그 "버리는 규칙"만 여기서 잠근다.
 */
class ReviewPromptTest {

    private static final String FACTS = """
            업종: cafe
            확정 예산(만원): 9000
            필터 통과 후보 수: 708
            판정별 후보 수: {CAUTION=120, FIT=588}
            상위 후보:
              - 망원역 상권: 판정 FIT, 종합점수 75, 부담률 0.110, 환산임대료 198만원, 추정매출 1800만원
            """;

    @Test
    void acceptsObjection_thatCitesOnlyGivenNumbers() {
        String text = "상위 후보의 부담률 0.110 은 추정매출이 유지된다는 전제에 기대고 있어, "
                + "매출 하위 시나리오에서는 CAUTION 후보 120곳과 같은 구간에 들어갈 수 있습니다.";

        assertThat(ReviewPrompt.sanitize(Optional.of(text), FACTS)).contains(text);
    }

    /** 이 테스트가 이 기능의 존재 이유다 — 지어낸 수치로 판정을 반박하면 §0-1 이 무너진다. */
    @Test
    void rejectsObjection_thatInventsANumber() {
        String invented = "상위 후보의 공실률이 12.7% 에 달해 추정매출이 과대평가되었을 수 있습니다.";

        assertThat(ReviewPrompt.sanitize(Optional.of(invented), FACTS)).isEmpty();
    }

    /**
     * 지어낸 수치가 <b>사실의 부분 문자열</b>이면 통과하던 구멍 — 대조가 문자열이었기 때문이다.
     *
     * <p>사실의 「추정매출 1800만원」·「환산임대료 198만원」에서 180·80·98 은 전부 다른 수인데,
     * {@code facts.contains("180")} 은 참이었다. 반박문에 없는 금액이 실린 채 검증 적용으로
     * 나가는 경로라 §0-1 위반이 그대로 화면에 오른다.
     */
    @Test
    void rejectsObjection_whoseNumberIsOnlyASubstringOfAFact() {
        String substring = "추정매출이 180만원만 흔들려도 부담률 임계를 넘길 수 있습니다.";
        String tail = "환산임대료가 98만원 오르면 같은 구간에 들어갑니다.";

        assertThat(ReviewPrompt.sanitize(Optional.of(substring), FACTS)).isEmpty();
        assertThat(ReviewPrompt.sanitize(Optional.of(tail), FACTS)).isEmpty();
    }

    /**
     * 표기 차이는 다른 수가 아니다 — 값으로 대조한다.
     *
     * <p>모델은 한국어 관행대로 「1,800만원」이라 쓰고 「0.110」을 「0.11」로 줄인다. 구 구현은
     * 문자열 대조라 이런 응답을 통째로 버렸고, 사용자에게는 <b>내용이 멀쩡한데도</b> 검증
     * 패널이 「검증 생략」으로 떴다.
     */
    @Test
    void acceptsObjection_thatWritesTheSameNumberDifferently() {
        String comma = "추정매출 1,800만원은 분기 평균이라 하위 시나리오에서는 더 낮아질 수 있습니다.";
        String trimmed = "부담률 0.11 은 추정매출이 유지된다는 전제에 기대고 있습니다.";

        assertThat(ReviewPrompt.sanitize(Optional.of(comma), FACTS)).contains(comma);
        assertThat(ReviewPrompt.sanitize(Optional.of(trimmed), FACTS)).contains(trimmed);
    }

    /** 날짜처럼 구분자가 여럿인 토큰은 수치가 아니다 — 예외로 요청을 깨지 말고 거부한다. */
    @Test
    void rejectsObjection_withNonNumericTokenInsteadOfThrowing() {
        String dated = "2026.07.27 기준 추정매출은 분기 평균이라 하위 시나리오를 덮지 못합니다.";

        assertThat(ReviewPrompt.sanitize(Optional.of(dated), FACTS)).isEmpty();
    }

    /**
     * <b>값 집합만 보면 수치의 귀속이 바뀐 문장을 막지 못한다.</b>
     *
     * <p>사실의 환산임대료는 198만원이고 1800만원은 추정매출인데, 값 집합 대조는 1800 이
     * 「어딘가에」 있었다는 것만 본다. 그래서 「환산임대료가 1,800만원」이 통과했다 —
     * 지어낸 숫자는 아니지만 <b>화면에 실리는 문장은 거짓</b>이고, 게다가 임대료를 9배로
     * 말하는 반박이라 사용자 판단을 정면으로 왜곡한다. §0-1 이 막으려는 것은 숫자의 출처가
     * 아니라 숫자가 뜻하는 바이므로, 라벨과 값이 함께 맞아야 한다.
     */
    @Test
    void rejectsObjection_thatReattachesAGivenNumberToTheWrongLabel() {
        String swapped = "환산임대료가 1,800만원에 이르러 상환 부담이 추정매출을 잠식할 수 있습니다.";
        String scoreAsRatio = "부담률 75 는 추정매출이 유지된다는 전제에 기대고 있어 하위 시나리오에 취약합니다.";

        assertThat(ReviewPrompt.sanitize(Optional.of(swapped), FACTS)).isEmpty();
        assertThat(ReviewPrompt.sanitize(Optional.of(scoreAsRatio), FACTS)).isEmpty();
    }

    /** 라벨이 맞으면 그대로 통과해야 한다 — 귀속 검사가 정상 반박까지 걷어차면 안 된다. */
    @Test
    void acceptsObjection_thatAttachesEachNumberToItsOwnLabel() {
        String correct = "환산임대료 198만원 대비 추정매출 1,800만원이라는 구성은 분기 평균에 기댄 것이라 "
                + "비수기에는 부담률 0.110 이 그대로 유지되지 않을 수 있습니다.";

        assertThat(ReviewPrompt.sanitize(Optional.of(correct), FACTS)).contains(correct);
    }

    /**
     * 「환산임대료 대비 추정매출 부담률 0.110」처럼 라벨이 줄지어 나오면 값의 임자는
     * <b>가장 가까운 라벨</b>이다 (checkArea 사실 문구가 실제로 이 형태다). 앞선 라벨까지
     * 값을 요구하면 정상 문장이 폐기된다.
     */
    @Test
    void acceptsObjection_whereNearestLabelOwnsTheNumber() {
        String chained = "환산임대료 대비 추정매출 부담률 0.110 은 매출이 유지된다는 전제에 기대고 있습니다.";

        assertThat(ReviewPrompt.sanitize(Optional.of(chained), FACTS)).contains(chained);
    }

    /**
     * <b>머리말 문단이 반박문 자리에 실리던 경로.</b>
     *
     * <p>{@code firstParagraph} 는 첫 문단만 취하는데, 모델이 「…아래에 작성하였습니다:」로
     * 한 문단을 먼저 쓰면 그 안내문이 첫 문단이 된다. 숫자가 없어 수치 검사는 <b>공허하게
     * 참</b>이고 금지어도 없어, 검증 적용(verified=true) 표시와 함께 안내문이 반박문으로
     * 화면에 올랐다. 반박이 사라지는 것보다 나쁘다 — 검증이 돌았다고 말하면서 내용이 없다.
     */
    @Test
    void skipsPreambleParagraph_andUsesTheObjectionThatFollows() {
        String objection = "부담률 0.110 은 추정매출이 유지된다는 전제에 기대고 있습니다.";
        String withPreamble = "요청하신 리스크 검증 반박문을 아래에 작성하였습니다:\n\n" + objection;

        assertThat(ReviewPrompt.sanitize(Optional.of(withPreamble), FACTS)).contains(objection);
    }

    /** 안내문 한 줄만 온 경우에는 건너뛸 다음 문단이 없다 — 그때는 폐기해야 한다. */
    @Test
    void rejectsHeaderOnlyResponse_insteadOfShowingItAsTheObjection() {
        String headerOnly = "요청하신 리스크 검증 반박문을 아래에 작성하였습니다:";

        assertThat(ReviewPrompt.sanitize(Optional.of(headerOnly), FACTS)).isEmpty();
    }

    /**
     * 수치 인용은 <b>강제하지 않는다</b>. 「추정매출은 분기 평균이라 계절 변동이 큰 업종에서는
     * 실제와 다를 수 있다」류는 수치가 없어도 유효한 반박이고, 검증기가 이를 막으면 정상 반박이
     * 폐기돼 「검증 생략」이 뜬다. 수치 인용 요구는 프롬프트에만 둔다.
     */
    @Test
    void acceptsQualitativeObjection_withoutAnyNumber() {
        String qualitative = "추정매출은 분기 평균이라 계절 변동이 큰 업종에서는 실제와 다를 수 있습니다.";

        assertThat(ReviewPrompt.sanitize(Optional.of(qualitative), FACTS)).contains(qualitative);
    }

    @Test
    void rejectsObjection_withBannedTerminology() {
        String banned = "이 상권은 자금 조건이 좋아 대출 승인 가능성이 높으니 권장할 만합니다.";

        assertThat(ReviewPrompt.sanitize(Optional.of(banned), FACTS)).isEmpty();
    }

    /**
     * 자문성 술어는 <b>활용형까지</b> 걸려야 한다. 구 목록은 「추천드립」·「추천합니」만 막아
     * 한국어에서 더 흔한 「추천해 드립니다」·「권유합니다」·「권해 드립니다」가 통과했다
     * (전 리뷰 지적). 반박문은 화면에 그대로 실리는 문장이라 여기서 막지 못하면 끝이다.
     */
    @Test
    void rejectsObjection_withInflectedAdvisoryForms() {
        for (String banned :
                new String[] {"추천해 드립니다", "추천드립니다", "추천합니다", "권유합니다", "권해 드립니다"}) {
            String text = "추정매출이 분기 평균이라 계절 변동이 큰 업종에서는 부담률이 달라질 수 있으니 "
                    + "여유 자금 확보를 " + banned;
            assertThat(ReviewPrompt.sanitize(Optional.of(text), FACTS))
                    .as("금지 활용형 '%s'", banned)
                    .isEmpty();
        }
    }

    @Test
    void rejectsTooShortAndTooLong() {
        assertThat(ReviewPrompt.sanitize(Optional.of("글쎄요."), FACTS)).isEmpty();
        assertThat(ReviewPrompt.sanitize(Optional.of("가".repeat(401)), FACTS)).isEmpty();
    }

    /** 무LLM·타임아웃은 빈 응답으로 들어온다 — 그대로 폴백으로 흘려보낸다. */
    @Test
    void emptyResponse_staysEmpty() {
        assertThat(ReviewPrompt.sanitize(Optional.empty(), FACTS)).isEmpty();
    }

    /**
     * 머리말·따옴표·불릿은 벗기되 <b>문장은 고치지 않는다</b>. 형태 때문에 멀쩡한 반박을 통째로
     * 버리는 것은 과하고, 문장을 다시 쓰는 것은 LLM 출력 재작성이라 또 다른 문제가 된다.
     */
    @Test
    void stripsDecoration_butKeepsSentenceIntact() {
        String sentence = "추정매출이 분기 평균이라 계절 변동이 큰 업종에서는 부담률이 달라질 수 있습니다.";

        assertThat(ReviewPrompt.sanitize(Optional.of("- \"" + sentence + "\""), FACTS))
                .contains(sentence);
        assertThat(ReviewPrompt.sanitize(Optional.of(sentence + "\n\n추가 설명: …"), FACTS))
                .contains(sentence);
    }

    /** 프롬프트에 입력 사실이 그대로 들어가야 모델이 인용할 수치를 갖는다. */
    @Test
    void promptCarriesFacts() {
        assertThat(ReviewPrompt.build(FACTS)).contains(FACTS).contains("입력에 있는 수치만");
    }
}

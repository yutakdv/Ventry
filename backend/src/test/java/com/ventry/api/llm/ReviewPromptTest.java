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

    @Test
    void rejectsObjection_withBannedTerminology() {
        String banned = "이 상권은 자금 조건이 좋아 대출 승인 가능성이 높으니 권장할 만합니다.";

        assertThat(ReviewPrompt.sanitize(Optional.of(banned), FACTS)).isEmpty();
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

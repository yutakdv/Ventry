package com.ventry.api.serving;

import static org.assertj.core.api.Assertions.assertThat;

import com.ventry.api.common.FinanceDtos.RiskReview;
import com.ventry.api.common.Verdict;
import com.ventry.api.llm.LlmClient;
import com.ventry.api.llm.NoLlmClient;
import com.ventry.api.recommend.RecommendDtos.Area;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * #96 — 리스크 검증 에이전트. <b>계약 플래그가 사실과 일치하는가</b>가 이 테스트의 전부다.
 *
 * <p>구 구현은 LLM 없이 하드코딩 문장을 {@code applied=true}·{@code skipped=false} 로 내보냈다.
 * API 키를 완전히 지운 스택에서도 그대로였고, 그래서 "검증을 수행했다"는 응답이 사실이 아니었다
 * (BE-07 통합 QA 지적).
 */
class RiskReviewAgentTest {

    private static final List<Area> AREAS = List.of(
            new Area("A-1", "망원역 상권", 37.5556, 126.9106, Verdict.FIT, 75,
                    null, null, 198, 1800, 24500, 0.11, "근거 문장", null, null));

    private static LlmClient responding(String text) {
        return new LlmClient() {
            @Override
            public Optional<String> complete(String prompt) {
                return Optional.of(text);
            }

            @Override
            public boolean enabled() {
                return true;
            }
        };
    }

    /** LLM 이 없으면 템플릿이 최종본이고, 그 사실을 {@code skipped=true} 로 밝힌다 (스펙 §5-3). */
    @Test
    void withoutLlm_fallsBackToTemplate_andFlagsSkipped() {
        RiskReviewAgent agent = new RiskReviewAgent(new ReviewGenerator(new NoLlmClient(null)));

        RiskReview review = agent.forRecommend("cafe", AREAS);

        assertThat(review.skipped()).isTrue();
        assertThat(review.applied()).isFalse();
        assertThat(review.objectionText())
                .isEqualTo(ReasonTemplate.recommendReview().objectionText());
    }

    /** 검증을 통과한 반박은 그대로 실리고 {@code applied=true} 다. */
    @Test
    void withLlm_carriesObjection_andFlagsApplied() {
        String objection = "상위 후보의 부담률 0.110 은 추정매출 1800만원이 유지된다는 전제에 "
                + "기대고 있어, 매출 하위 시나리오에서는 판정이 달라질 수 있습니다.";
        RiskReviewAgent agent = new RiskReviewAgent(new ReviewGenerator(responding(objection)));

        RiskReview review = agent.forRecommend("cafe", AREAS);

        assertThat(review.applied()).isTrue();
        assertThat(review.skipped()).isFalse();
        assertThat(review.objectionText()).isEqualTo(objection);
    }

    /**
     * LLM 이 <b>살아 있어도</b> 지어낸 수치는 폴백이다 — 가용성과 신뢰성은 다른 문제이고,
     * 응답 규격은 두 경우가 같아야 화면이 분기를 하나만 갖는다.
     */
    @Test
    void llmAlive_butInventedNumber_fallsBackToTemplate() {
        RiskReviewAgent agent = new RiskReviewAgent(new ReviewGenerator(
                responding("공실률이 12.7% 에 달해 추정매출이 과대평가되었을 수 있습니다.")));

        RiskReview review = agent.forRecommend("cafe", AREAS);

        assertThat(review.skipped()).isTrue();
        assertThat(review.objectionText())
                .isEqualTo(ReasonTemplate.recommendReview().objectionText());
    }

    /** 역방향 경로도 같은 규약을 따른다 — 사실 문자열만 다르다. */
    @Test
    void checkArea_usesItsOwnTemplate_whenLlmAbsent() {
        RiskReviewAgent agent = new RiskReviewAgent(new ReviewGenerator(new NoLlmClient(null)));

        RiskReview review = agent.forCheckArea("망원역 상권", Verdict.CONDITIONAL, 1320, 0.11);

        assertThat(review.skipped()).isTrue();
        assertThat(review.objectionText())
                .isEqualTo(ReasonTemplate.checkAreaReview().objectionText());
    }

    /** 부족분이 없는 판정(FIT)도 사실 문자열이 성립해야 한다 — null 이 그대로 흘러가면 안 된다. */
    @Test
    void checkArea_withoutGap_stillBuildsFacts() {
        RiskReviewAgent agent = new RiskReviewAgent(new ReviewGenerator(responding(
                "부담률이 임계에 근접해 있어 소폭의 매출 감소에도 판정이 달라질 수 있습니다.")));

        RiskReview review = agent.forCheckArea("망원역 상권", Verdict.FIT, null, 0.11);

        assertThat(review.applied()).isTrue();
    }
}

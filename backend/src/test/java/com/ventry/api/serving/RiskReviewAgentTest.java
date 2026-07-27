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
                .isEqualTo(ReasonTemplate.recommendReview(1, 0, 0).objectionText());
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
                .isEqualTo(ReasonTemplate.recommendReview(1, 0, 0).objectionText());
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

    /**
     * D-23 (#104 ①) — 화면이 걸러내는 범위 외 상권은 반박 대상 사실로 넘어가지 않는다.
     *
     * <p>범위 외 상권의 수치를 인용한 반박은 사실 목록에 그 숫자가 없으므로 {@code sanitize} 가
     * 버리고 템플릿으로 떨어진다. 즉 "볼 수 없는 상권에 대한 반박문"이 화면에 나갈 수 없다.
     */
    @Test
    void outOfScopeAreas_areNotOfferedAsFacts() {
        List<Area> outOfScope = List.of(
                new Area("A-9", "황학코아루아파트", 37.5, 127.0, Verdict.OUT_OF_SCOPE, 84,
                        null, null, 155, 2400, 30000, 0.065, "근거", null, null));
        RiskReviewAgent agent = new RiskReviewAgent(new ReviewGenerator(
                responding("황학코아루아파트의 부담률 0.065 는 매출 2400만원 유지를 전제로 합니다.")));

        RiskReview review = agent.forRecommend("cafe", outOfScope);

        assertThat(review.skipped()).isTrue();   // 사실에 없는 수치 → 폐기
        assertThat(review.objectionText()).contains("진입하는 후보가 없어");
    }

    /**
     * D-24 (#104 ②) — 템플릿 반박문이 판정 분포를 따라간다.
     *
     * <p>구 구현은 상수라 유의가 0곳인 구간에서도 「유의 판정 유지가 타당합니다」로 끝났다.
     */
    @Test
    void template_variesWithVerdictDistribution() {
        String noEntry = ReasonTemplate.recommendReview(0, 0, 70).objectionText();
        String withCaution = ReasonTemplate.recommendReview(340, 340, 503).objectionText();
        String allFit = ReasonTemplate.recommendReview(193, 0, 0).objectionText();

        assertThat(noEntry).contains("진입하는 후보가 없어", "70곳").doesNotContain("유의 판정 유지");
        assertThat(withCaution).contains("유의 판정 340곳");
        assertThat(allFit).contains("진입 후보 193곳").doesNotContain("유의 판정");
    }

    /**
     * 문장 안의 곳수는 금액과 같은 천단위 구분을 쓴다.
     *
     * <p>실데이터 후보는 네 자리다(카페 1,059곳). 「2,770만 원」과 「1018곳」이 한 문장에 섞이면
     * README 도슨트 대본(「1,018곳」)과 글자가 어긋나고, 심사위원은 대본을 들고 화면을 본다.
     */
    @Test
    void counts_useThousandSeparator_likeAmounts() {
        assertThat(ReasonTemplate.recommendReview(0, 0, 1018).objectionText()).contains("1,018곳");
        assertThat(ReasonTemplate.recommendReview(1234, 1234, 0).objectionText()).contains("1,234곳");

        // 세 자리 이하는 구분자가 붙지 않는다 — "0,070곳" 같은 표기가 나오면 안 된다.
        assertThat(ReasonTemplate.recommendReview(0, 0, 70).objectionText()).contains(" 70곳");
        assertThat(ReasonTemplate.recommendReview(999, 0, 0).objectionText()).contains(" 999곳");
    }

    /** D-11 — 사실 문자열의 판정은 한글 판정어다. 영문 enum 은 "분류" 메타 표현을 유도한다. */
    @Test
    void facts_useKoreanVerdictLabel() {
        RiskReviewAgent agent = new RiskReviewAgent(new ReviewGenerator(
                responding("조건부 적합 판정은 무권리 매물 확보를 전제로 하며 부족분 1320만원이 남습니다.")));

        RiskReview review = agent.forCheckArea("망원역 상권", Verdict.CONDITIONAL, 1320, 0.11);

        assertThat(review.applied()).isTrue();   // '조건부 적합' 이 사실에 있어야 통과한다
    }

    /** D-04 — 매출 결측 상권의 부담률이 사실 문자열에 `Infinity` 로 실리지 않는다. */
    @Test
    void checkArea_withNonFiniteRatio_reportsUnavailableInsteadOfInfinity() {
        RiskReviewAgent agent = new RiskReviewAgent(new ReviewGenerator(
                responding("추정매출이 결측이라 산출 불가 상태이며 부족분 500만원만 확인됩니다.")));

        RiskReview review = agent.forCheckArea(
                "동대문역 1번", Verdict.CONDITIONAL, 500, Double.POSITIVE_INFINITY);

        assertThat(review.applied()).isTrue();
        assertThat(review.objectionText()).doesNotContain("Infinity");
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

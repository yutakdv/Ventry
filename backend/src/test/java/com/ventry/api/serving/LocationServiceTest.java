package com.ventry.api.serving;

import static org.assertj.core.api.Assertions.assertThat;

import com.ventry.api.checkarea.CheckAreaDtos.CheckAreaResponse;
import com.ventry.api.common.Verdict;
import com.ventry.api.engine.Profile;
import com.ventry.api.recommend.RecommendDtos.RecommendResponse;
import org.junit.jupiter.api.Test;

/** BE-03f — 도구 계층 결선(엔진 오케스트레이션) 검증. 데모 프로필·예산 8,000 기준. */
class LocationServiceTest {

    private final LocationService svc = new LocationService(new DemoCandidates(), new DemoProducts());
    private final Profile demo = new Profile(32, 5000, "cafe", "망원");

    @Test
    void recommend_ordersByScore_withFitFitCautionAtDemoBudget() {
        RecommendResponse res = svc.recommend(demo, 8000);
        assertThat(res.areas()).hasSize(3);
        assertThat(res.areas().get(0).name()).isEqualTo("망원역 상권");
        assertThat(res.areas().get(0).verdict()).isEqualTo(Verdict.FIT);
        assertThat(res.areas().get(1).verdict()).isEqualTo(Verdict.FIT);
        assertThat(res.areas().get(2).name()).isEqualTo("홍대입구역 상권");
        assertThat(res.areas().get(2).verdict()).isEqualTo(Verdict.CAUTION);
    }

    @Test
    void recommend_costIsDualIntervalComputedByEngine() {
        RecommendResponse res = svc.recommend(demo, 8000);
        var cost = res.areas().get(0).cost();   // 망원: ex[5600,6800] incl[7000,8500]
        assertThat(cost.exPremium()).containsExactly(5600, 6800);
        assertThat(cost.inclPremium()).containsExactly(7000, 8500);
    }

    @Test
    void recommend_riskReviewApplied_andReasonTextComplianceSafe() {
        RecommendResponse res = svc.recommend(demo, 8000);
        assertThat(res.riskReview().applied()).isTrue();
        assertThat(res.riskReview().skipped()).isFalse();
        for (var area : res.areas()) {
            assertThat(area.reasonText()).isNotEmpty();
            assertThat(area.reasonText()).doesNotContain("추천", "권장");
        }
    }

    @Test
    void checkArea_demoTarget_isConditionalWithGap1320() {
        CheckAreaResponse res = svc.checkArea(demo, 8000, "A-9999");
        assertThat(res.verdict()).isEqualTo(Verdict.CONDITIONAL);
        assertThat(res.gapAmount()).isEqualTo(1320);
    }

    @Test
    void checkArea_matchingProductsQualified_withNullSourceQuoteBeforeRag() {
        CheckAreaResponse res = svc.checkArea(demo, 8000, "A-9999");
        assertThat(res.matchingProducts()).isNotEmpty();
        assertThat(res.matchingProducts().get(0).sourceQuote()).isNull();  // RAG=P1, 구현 전 null
    }

    @Test
    void checkArea_lowerBudget_dropsToOutOfScope() {
        CheckAreaResponse res = svc.checkArea(demo, 6000, "A-9999");
        assertThat(res.verdict()).isEqualTo(Verdict.OUT_OF_SCOPE);
    }

    @Test
    void checkArea_unknownArea_throwsAreaNotFound() {
        assertThat(catchThrowable(() -> svc.checkArea(demo, 8000, "A-0000")))
                .hasMessageContaining("A-0000");
    }

    private static Throwable catchThrowable(ThrowingCallable c) {
        try {
            c.call();
        } catch (Throwable t) {
            return t;
        }
        return null;
    }

    @FunctionalInterface
    private interface ThrowingCallable {
        void call() throws Exception;
    }
}

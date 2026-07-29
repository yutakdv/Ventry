package com.ventry.api.serving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.ventry.api.checkarea.CheckAreaDtos.CheckAreaResponse;
import com.ventry.api.common.Verdict;
import com.ventry.api.engine.Profile;
import com.ventry.api.llm.NoLlmClient;
import com.ventry.api.recommend.RecommendDtos.Area;
import com.ventry.api.recommend.RecommendDtos.RecommendResponse;
import com.ventry.api.scenario.ScenarioDtos.BudgetPreview;
import java.util.Comparator;
import org.junit.jupiter.api.Test;

/** BE-03f — 도구 계층 결선(엔진 오케스트레이션) 검증. 데모 프로필·예산 8,000 기준. */
class LocationServiceTest {

    /** 무LLM 검증 에이전트 — 이 테스트가 보는 것은 판정·정렬이지 반박문이 아니다 (#96). */
    private final LocationService svc = new LocationService(new DemoCandidates(),
            new DemoProducts(), new DemoDataMeta(), new RiskReviewAgent(new ReviewGenerator(new NoLlmClient(null))));
    private final Profile demo = new Profile(32, 5000, false, "cafe", "망원");

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

    /**
     * 실사용 점검(2026-07-29) — 요약 평균의 모집단은 <b>화면에 남는 후보</b>다.
     *
     * <p>종전에는 후보 풀 전체를 평균해서, 예산을 내려 추천이 0곳이 된 화면에서도 평균
     * 임대료·매출이 그대로 떴다(실측: 5,000~15,000만원 전 구간에서 221/1,042 고정).
     * 기대값을 상수로 박지 않고 응답 자체에서 유도하는 이유는, 픽스처가 바뀌어도 이
     * 단언이 지키려는 성질(모집단 일치)이 그대로 남게 하기 위해서다.
     */
    @Test
    void recommend_summaryAveragesVisibleAreasOnly() {
        RecommendResponse res = svc.recommend(demo, 8000);
        var visible = res.areas().stream()
                .filter(a -> a.verdict() != Verdict.OUT_OF_SCOPE).toList();
        assertThat(visible).isNotEmpty();
        assertThat(res.summary().avgRent()).isEqualTo(
                (int) Math.round(visible.stream().mapToInt(Area::monthlyRent).average().orElseThrow()));
        assertThat(res.summary().avgSales()).isEqualTo(
                (int) Math.round(visible.stream().mapToInt(Area::estSales).average().orElseThrow()));
    }

    /** 남는 후보가 없으면 평균은 0이 아니라 <b>정의되지 않는다</b> — 필드를 생략한다. */
    @Test
    void recommend_omitsSummaryWhenNothingVisible() {
        RecommendResponse res = svc.recommend(demo, 0);
        assertThat(res.areas()).isNotEmpty();
        assertThat(res.areas()).allMatch(a -> a.verdict() == Verdict.OUT_OF_SCOPE);
        assertThat(res.summary()).isNull();
    }

    @Test
    void recommend_costIsDualIntervalComputedByEngine() {
        RecommendResponse res = svc.recommend(demo, 8000);
        var cost = res.areas().get(0).cost();   // 망원: ex[5600,6800] incl[7000,8500]
        assertThat(cost.exPremium()).containsExactly(5600, 6800);
        assertThat(cost.inclPremium()).containsExactly(7000, 8500);
    }

    @Test
    void recommend_riskReviewSkippedWithoutLlm_andReasonTextComplianceSafe() {
        RecommendResponse res = svc.recommend(demo, 8000);
        // 무LLM 에이전트를 주입했으므로 반박은 생략되고 템플릿이 최종본이다 (#96, 스펙 §5-3).
        // 판정·근거문은 그대로 나온다 — 검증 실패가 추천을 막지 않는다.
        assertThat(res.riskReview().skipped()).isTrue();
        assertThat(res.riskReview().objectionText()).isNotEmpty();
        for (var area : res.areas()) {
            assertThat(area.reasonText()).isNotEmpty();
            assertThat(area.reasonText()).doesNotContain("추천", "권장");
        }
    }

    /** BE-01a: 점수는 가중 합[0,1]의 0~100 투영이며 정렬 순서와 일치해야 한다. */
    @Test
    void recommend_exposesScoreConsistentWithSortOrder() {
        RecommendResponse res = svc.recommend(demo, 8000);
        assertThat(res.areas().get(0).score()).isEqualTo(75);   // 망원 .752 → 75
        assertThat(res.areas()).isSortedAccordingTo(
                Comparator.comparingInt(Area::score).reversed());
        assertThat(res.areas()).allSatisfy(a -> assertThat(a.score()).isBetween(0, 100));
    }

    /** BE-01a: 부담률은 픽스처 상수가 아니라 임대료÷매출 파생값이다 (스펙 §4-2). */
    @Test
    void recommend_burdenRatioIsDerivedFromRentAndSales() {
        RecommendResponse res = svc.recommend(demo, 8000);
        for (Area area : res.areas()) {
            assertThat(area.burdenRatio())
                    .isEqualTo((double) area.monthlyRent() / area.estSales());
        }
        assertThat(res.areas().get(0).burdenRatio()).isCloseTo(0.11, within(0.001));
    }

    @Test
    void recommend_summaryAveragesWholePool() {
        RecommendResponse res = svc.recommend(demo, 8000);
        assertThat(res.totalCount()).isEqualTo(3);
        assertThat(res.summary().avgRent()).isEqualTo(309);     // (198+273+456)/3
        assertThat(res.summary().avgSales()).isEqualTo(2100);   // (1800+2100+2400)/3
    }

    /** 프리뷰 개수는 진입 프론티어 N_entry(B)와 같아야 한다 (expl §2-1). */
    @Test
    void preview_countMatchesEntryFrontier_andRangesCoverEnteredAreas() {
        BudgetPreview preview = svc.preview("cafe", 8000);
        assertThat(preview.areaCount()).isEqualTo(3);
        assertThat(preview.rentRange()).containsExactly(198, 456);
        assertThat(preview.floatingRange()).containsExactly(22800, 38200);
    }

    @Test
    void preview_narrowsAsBudgetDrops() {
        assertThat(svc.preview("cafe", 7600).areaCount()).isEqualTo(1);   // 홍대(7,500)만 진입
        assertThat(svc.preview("cafe", 7600).rentRange()).containsExactly(456, 456);
    }

    @Test
    void preview_belowEveryCandidate_hasNoRanges() {
        BudgetPreview preview = svc.preview("cafe", 5000);
        assertThat(preview.areaCount()).isZero();
        assertThat(preview.rentRange()).isNull();
        assertThat(preview.floatingRange()).isNull();
    }

    @Test
    void checkArea_demoTarget_isConditionalWithGap1320() {
        CheckAreaResponse res = svc.checkArea(demo, 8000, "A-9999");
        assertThat(res.verdict()).isEqualTo(Verdict.CONDITIONAL);
        assertThat(res.gapAmount()).isEqualTo(1320);
    }

    @Test
    void checkArea_matchingProducts_omitQuote_whenProductHasNoChunk() {
        CheckAreaResponse res = svc.checkArea(demo, 8000, "A-9999");
        assertThat(res.matchingProducts()).isNotEmpty();
        // 이 경로는 픽스처 상품(DemoProducts)이라 연결된 청크가 없다 — 인용을 지어내지 않는다.
        // 실적재 경로(profile db)에서는 26/26 이 청크를 보유해 인용이 실린다 (BE-06 ①).
        assertThat(res.matchingProducts().get(0).sourceQuote()).isNull();
    }

    /** 계약 D8: matching_products는 amount_max 내림차순 고정 정렬 + rate_type passthrough. */
    @Test
    void checkArea_matchingProductsSortedByLimitDesc_withRateType() {
        CheckAreaResponse res = svc.checkArea(demo, 8000, "A-9999");
        assertThat(res.matchingProducts())
                .isSortedAccordingTo(Comparator.comparingInt(
                        (com.ventry.api.common.FinanceDtos.Product p) -> p.amountMax()).reversed());
        assertThat(res.matchingProducts().get(0).amountMax()).isEqualTo(3000);   // 소진공 먼저
        assertThat(res.matchingProducts().get(1).amountMax()).isEqualTo(1500);   // 서울보증 다음
        assertThat(res.matchingProducts()).allSatisfy(p -> {
            assertThat(p.rateType()).isEqualTo("fixed");   // 데모 상품 전부 확정 이율
            assertThat(p.rateNote()).isNull();
        });
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

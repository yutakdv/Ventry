package com.ventry.api.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.ventry.api.common.FinanceDtos.Source;
import com.ventry.api.engine.CoverOutcome.Reason;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * BE-05 — 변동금리(확정 이율 미상) 처리와 커버 실패 사유 구분 (assumptions #28).
 * 핵심 원칙: <b>금리를 모르면 가정 금리를 만들지 않고 그 경계를 보고에서 제외</b>한다.
 */
class CoverOutcomeTest {

    private static final Source SRC = new Source("테스트기관", "https://example.test", "2026-07-19");
    private static final FundingInput ROOMY = new FundingInput(250, true);

    /** 확정 이율 상품 (연 2.5% · 60개월). */
    private static FundingProduct fixed(String name, int amountMax) {
        return new FundingProduct(name, new Eligibility(null, null, null, false),
                amountMax, 2.5, 60, null, "open", "2026-Q1", SRC);
    }

    /** 변동금리 상품 — 확정 이율이 없어 rate=null. */
    private static FundingProduct variable(String name, int amountMax) {
        return new FundingProduct(name, new Eligibility(null, null, null, false),
                amountMax, null, FundingProduct.RATE_VARIABLE, "기준금리 + 0.6%p",
                60, null, "open", "2026-Q1", SRC);
    }

    /** 테스트 10 — 변동금리 상품뿐이면 m을 산출할 수 없으므로 경계를 제외하고 사유를 남긴다. */
    @Test
    void onlyVariableRateProduct_isExcludedWithRateUnknownReason() {
        CoverOutcome outcome = FundingCheck.coverWithReason(
                200, List.of(variable("변동금리 운전자금", 3000)), Map.of(), ROOMY);

        assertThat(outcome.isCovered()).isFalse();
        assertThat(outcome.plan()).isEmpty();
        assertThat(outcome.reason()).isEqualTo(Reason.RATE_UNKNOWN);   // 한도는 충분했다
    }

    /** 테스트 11 — 변동금리 상품은 건너뛰고 확정 이율 상품으로 커버한다 (한도가 더 커도 무시). */
    @Test
    void variableRateProduct_isSkipped_whileFixedRateProductCovers() {
        CoverOutcome outcome = FundingCheck.coverWithReason(
                200, List.of(variable("변동금리 운전자금", 3000), fixed("보증", 1500)),
                Map.of(), ROOMY);

        assertThat(outcome.reason()).isEqualTo(Reason.NONE);
        FundingPlan plan = outcome.plan().orElseThrow();
        assertThat(plan.allocations()).singleElement()
                .satisfies(a -> assertThat(a.product().name()).isEqualTo("보증"));
        assertThat(plan.totalAmount()).isEqualTo(200);
        // 한도 큰 변동금리 상품이 먼저 정렬되더라도 편성되지 않는다
        assertThat(plan.allocations()).noneMatch(a -> a.product().rate() == null);
    }

    /** 테스트 12 — 실패 사유 3종이 서로 구분된다: 경계 없음 / 한도 부족 / 상환 여력 초과. */
    @Test
    void failureReasons_areDistinguished() {
        assertThat(FundingCheck.coverWithReason(0, List.of(fixed("보증", 1500)), Map.of(), ROOMY)
                .reason()).isEqualTo(Reason.NO_BOUNDARY);

        // 확정 이율 상품만 있는데 한도 합(1,500)이 갭(5,000)에 미달 → 금리 문제가 아니다
        assertThat(FundingCheck.coverWithReason(5000, List.of(fixed("보증", 1500)), Map.of(), ROOMY)
                .reason()).isEqualTo(Reason.LIMIT_SHORT);

        // 조달은 되지만 m≈23.43 > 월 투자 가능액 20 (assumptions #23)
        assertThat(FundingCheck.coverWithReason(1320, List.of(fixed("정책자금", 3000)), Map.of(),
                new FundingInput(20, true)).reason()).isEqualTo(Reason.REPAYMENT_OVER);
    }

    /** 테스트 13 — 기존 {@code cover()}는 사유만 버릴 뿐 판정이 동일하다 (위임 검증). */
    @Test
    void legacyCover_delegatesWithIdenticalPlan() {
        List<FundingProduct> products = List.of(variable("변동금리 운전자금", 3000), fixed("보증", 1500));

        assertThat(FundingCheck.cover(200, products, Map.of(), ROOMY))
                .isEqualTo(FundingCheck.coverWithReason(200, products, Map.of(), ROOMY).plan());
        assertThat(FundingCheck.cover(5000, products, Map.of(), ROOMY))
                .isEqualTo(FundingCheck.coverWithReason(5000, products, Map.of(), ROOMY).plan())
                .isEmpty();
    }
}

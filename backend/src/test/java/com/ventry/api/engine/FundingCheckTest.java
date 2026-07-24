package com.ventry.api.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.ventry.api.common.FinanceDtos.Source;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * BE-04 FundingCheck — 조달 검증 (expl §2-2). DoD: 경계·갭·마진 수작업 계산 대조 3케이스 +
 * 커버 실패 2종. 프론티어 값은 {@link Frontier}(기존 구현)로 계산해 결합 검증한다.
 */
class FundingCheckTest {

    /** 수작업 대조용 후보 비용 배열 — FrontierTest 와 동일 (만원). */
    private static final int[] COSTS = {6480, 7100, 7800, 8200, 9320};
    private static final int BUDGET = 8000;

    private static final Source SRC = new Source("테스트기관", "https://example.test", "2026-07-19");

    /** 보증 1,500 · 연 2.5% · 60개월 · 중복수혜 제약 없음. */
    private static final FundingProduct GUARANTEE = product("보증", 1500, null);
    /** 정책자금 3,000 · 그룹 SEMAS_YOUTH. */
    private static final FundingProduct POLICY = product("정책자금", 3000, "SEMAS_YOUTH");

    private static final FundingInput ROOMY = new FundingInput(250, true);

    // ── DoD 1. 경계·갭 → 커버 성공 ────────────────────────────────────────
    /** B₀=8,000의 다음 경계 8,200 → 갭 200. 보증 잔여로 전액 커버되고 m≈3.55만. */
    @Test
    void dod1_boundaryGap_isCoveredByGuarantee_withHandCalculatedPayment() {
        int gap = Frontier.gap(COSTS, BUDGET).orElseThrow();
        assertThat(gap).isEqualTo(200);                     // 8,200 − 8,000 (수작업)

        Optional<FundingPlan> plan = FundingCheck.cover(gap, List.of(GUARANTEE), Map.of(), ROOMY);

        assertThat(plan).isPresent();
        assertThat(plan.get().totalAmount()).isEqualTo(200);
        assertThat(plan.get().monthlyPayment()).isCloseTo(3.55, within(0.01));
        assertThat(plan.get().allocations()).singleElement()
                .satisfies(a -> {
                    assertThat(a.product().name()).isEqualTo("보증");
                    assertThat(a.amount()).isEqualTo(200);
                    assertThat(a.termMonths()).isEqualTo(60);
                    assertThat(a.termAssumed()).isFalse();   // 상품 조건이 있으므로 가정 아님
                });
    }

    // ── DoD 2. 하향 안전 마진 ─────────────────────────────────────────────
    /** B_safe = max{c_a ≤ B₀} = 7,800 → 현재 예산 대비 200만 여유 (수작업). */
    @Test
    void dod2_safetyMargin_isLargestCostAtOrBelowBudget() {
        int bSafe = Frontier.bSafe(COSTS, BUDGET).orElseThrow();
        assertThat(bSafe).isEqualTo(7800);
        assertThat(BUDGET - bSafe).isEqualTo(200);
        // 마진 구간에서는 진입 후보 수가 유지된다
        assertThat(Frontier.nEntry(COSTS, bSafe)).isEqualTo(Frontier.nEntry(COSTS, BUDGET));
    }

    // ── DoD 3. 커버 실패 2종 → 경계 보고 제외 ─────────────────────────────
    /** ⓐ 갭이 총 잔여 한도(3,000+1,500=4,500)를 넘으면 커버 불가. */
    @Test
    void dod3a_coverFails_whenGapExceedsTotalRemainingLimit() {
        assertThat(FundingCheck.cover(5000, List.of(POLICY, GUARANTEE), Map.of(), ROOMY)).isEmpty();
    }

    /** ⓑ 조달은 되지만 월 상환액이 상환 여력을 넘으면 제외 (m≈23.43 > 20, assumptions #23). */
    @Test
    void dod3b_coverFails_whenPaymentExceedsMonthlyInvestable() {
        FundingInput tight = new FundingInput(20, true);
        assertThat(FundingCheck.cover(1320, List.of(POLICY), Map.of(), tight)).isEmpty();
        // 여력이 충분하면 동일 갭이 커버된다
        assertThat(FundingCheck.cover(1320, List.of(POLICY), Map.of(), ROOMY)).isPresent();
    }

    // ── 제약별 경계 케이스 ────────────────────────────────────────────────
    /** 동일 exclusive_group 은 1개만 — 두 상품 합(6,000)이 아니라 3,000까지만 조달된다. */
    @Test
    void exclusiveGroup_allowsOnlyOneProductPerGroup() {
        FundingProduct sameGroup = product("정책자금B", 3000, "SEMAS_YOUTH");
        assertThat(FundingCheck.cover(4000, List.of(POLICY, sameGroup), Map.of(), ROOMY)).isEmpty();
        assertThat(FundingCheck.cover(3000, List.of(POLICY, sameGroup), Map.of(), ROOMY)).isPresent();
    }

    /** 잔여 한도 원칙: B₀에 이미 쓰인 상품의 미사용 한도를 먼저 소진한다 (expl §2-2 ①). */
    @Test
    void remainingLimitPrinciple_prefersProductAlreadyUsedInBudget() {
        Map<String, Integer> used = Map.of("보증", 1000);   // 보증 1,500 중 1,000 사용 → 잔여 500
        Optional<FundingPlan> plan =
                FundingCheck.cover(400, List.of(POLICY, GUARANTEE), used, ROOMY);

        assertThat(plan).isPresent();
        assertThat(plan.get().allocations()).singleElement()
                .satisfies(a -> assertThat(a.product().name()).isEqualTo("보증"));   // 잔여 500 우선
    }

    /** 이미 한도를 다 쓴 상품은 후보에서 빠진다. */
    @Test
    void exhaustedProduct_isExcluded() {
        Map<String, Integer> used = Map.of("보증", 1500);
        assertThat(FundingCheck.cover(200, List.of(GUARANTEE), used, ROOMY)).isEmpty();
    }

    /** status=closed 상품은 조달 재원이 아니다. */
    @Test
    void closedProduct_isExcluded() {
        FundingProduct closed = new FundingProduct("마감상품",
                new Eligibility(null, null, null, false), 3000, 2.5, 60, null, "closed",
                "2026-Q1", SRC);
        assertThat(FundingCheck.cover(200, List.of(closed), Map.of(), ROOMY)).isEmpty();
    }

    /** 상향 경계가 없으면(gap ≤ 0) 조달 검증 대상이 아니다. */
    @Test
    void nonPositiveGap_returnsEmpty() {
        assertThat(FundingCheck.cover(0, List.of(GUARANTEE), Map.of(), ROOMY)).isEmpty();
        assertThat(FundingCheck.cover(-100, List.of(GUARANTEE), Map.of(), ROOMY)).isEmpty();
    }

    /** 상품에 상환기간이 없으면 60개월 가정 + termAssumed=true (assumptions #22). */
    @Test
    void missingTerm_fallsBackToDefaultWithAssumedFlag() {
        FundingProduct noTerm = new FundingProduct("기간미정",
                new Eligibility(null, null, null, false), 3000, 2.5, null, null, "open",
                "2026-Q1", SRC);
        Optional<FundingPlan> plan = FundingCheck.cover(1320, List.of(noTerm), Map.of(), ROOMY);

        assertThat(plan).isPresent();
        assertThat(plan.get().allocations()).singleElement().satisfies(a -> {
            assertThat(a.termMonths()).isEqualTo(Loan.DEFAULT_TERM_MONTHS);
            assertThat(a.termAssumed()).isTrue();
        });
    }

    /** 월 투자 가능액 미기재(null)는 상한 없음으로 본다. */
    @Test
    void nullMonthlyInvestable_meansNoRepaymentCap() {
        FundingInput noCap = new FundingInput(null, false);
        assertThat(FundingCheck.cover(3000, List.of(POLICY), Map.of(), noCap)).isPresent();
    }

    /** 여러 상품에 걸쳐 갭을 채우면 월 상환액은 각 조달의 합이다. */
    @Test
    void multipleProducts_sumAmountsAndPayments() {
        Optional<FundingPlan> plan =
                FundingCheck.cover(4000, List.of(POLICY, GUARANTEE), Map.of(), ROOMY);

        assertThat(plan).isPresent();
        assertThat(plan.get().totalAmount()).isEqualTo(4000);          // 3,000 + 1,000
        assertThat(plan.get().allocations()).hasSize(2);
        assertThat(plan.get().monthlyPayment()).isCloseTo(
                Loan.monthlyPayment(3000, 2.5, 60) + Loan.monthlyPayment(1000, 2.5, 60),
                within(0.001));
    }

    private static FundingProduct product(String name, int amountMax, String exclusiveGroup) {
        return new FundingProduct(name, new Eligibility(null, null, null, false),
                amountMax, 2.5, 60, exclusiveGroup, "open", "2026-Q1", SRC);
    }
}

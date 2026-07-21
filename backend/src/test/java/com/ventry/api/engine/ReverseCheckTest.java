package com.ventry.api.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.ventry.api.common.Verdict;
import org.junit.jupiter.api.Test;

/** BE-03e ReverseCheck — 역방향 판정 4단계·경계 규칙 (스펙 §4-1·§4-2, 계약 6). */
class ReverseCheckTest {

    // ex[5800,7200] 중앙값 6500 / incl[7400,9100] 중앙값 8250
    private final CostEstimate cost = new CostEstimate(new Interval(5800, 7200), new Interval(7400, 9100));
    private static final double THETA = 0.15;

    @Test
    void fit_whenBudgetCoversInclMedianAndBurdenWithinTheta() {
        ReverseResult r = ReverseCheck.evaluate(9000, cost, 0.11, THETA);
        assertThat(r.verdict()).isEqualTo(Verdict.FIT);
        assertThat(r.gapAmount()).isZero();
    }

    @Test
    void caution_whenBudgetCoversButBurdenExceedsTheta() {
        ReverseResult r = ReverseCheck.evaluate(9000, cost, 0.19, THETA);
        assertThat(r.verdict()).isEqualTo(Verdict.CAUTION);
        assertThat(r.gapAmount()).isZero();
    }

    @Test
    void conditional_whenBudgetBetweenExAndInclMedian() {
        // 6500 ≤ 8000 < 8250 → 무권리 시 진입 (조건부)
        ReverseResult r = ReverseCheck.evaluate(8000, cost, 0.11, THETA);
        assertThat(r.verdict()).isEqualTo(Verdict.CONDITIONAL);
        assertThat(r.gapAmount()).isEqualTo(250);   // ceil(8250 − 8000)
    }

    @Test
    void outOfScope_whenBudgetBelowExMedian() {
        ReverseResult r = ReverseCheck.evaluate(6000, cost, 0.11, THETA);
        assertThat(r.verdict()).isEqualTo(Verdict.OUT_OF_SCOPE);
        assertThat(r.gapAmount()).isEqualTo(2250);  // ceil(8250 − 6000)
    }

    @Test
    void boundary_budgetEqualsInclMedian_isEntryNotConditional() {
        ReverseResult r = ReverseCheck.evaluate(8250, cost, 0.11, THETA);
        assertThat(r.verdict()).isEqualTo(Verdict.FIT);
        assertThat(r.gapAmount()).isZero();
    }

    @Test
    void boundary_budgetEqualsExMedian_isConditional() {
        ReverseResult r = ReverseCheck.evaluate(6500, cost, 0.11, THETA);
        assertThat(r.verdict()).isEqualTo(Verdict.CONDITIONAL);
        assertThat(r.gapAmount()).isEqualTo(1750);  // ceil(8250 − 6500)
    }

    @Test
    void burdenAtThetaBoundary_isInclusive_stillFit() {
        ReverseResult r = ReverseCheck.evaluate(9000, cost, 0.15, THETA);
        assertThat(r.verdict()).isEqualTo(Verdict.FIT);   // 부담률 = θ 는 임계 이내
    }
}

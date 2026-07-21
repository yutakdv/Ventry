package com.ventry.api.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** BE-03b CostCalculator — 초기비용 4블록 합성·구간 유지·권리금 이중 표기 (스펙 §4-1). */
class CostCalculatorTest {

    // 데모(망원) 정합: deposit+interior+reserve = ex[5800,7200], premium[1600,1900] → incl[7400,9100]
    private final CostBlocks mangwon = new CostBlocks(
            new Interval(4000, 5000),   // 보증금
            new Interval(1600, 1900),   // 권리금
            new Interval(1200, 1600),   // 인테리어·시설비
            100);                       // 월 고정비 → 예비 운영자금 = ×6 = 600

    @Test
    void exPremium_sumsDepositInteriorAndReserve() {
        CostEstimate cost = CostCalculator.estimate(mangwon);
        assertThat(cost.exPremium()).isEqualTo(new Interval(5800, 7200));
    }

    @Test
    void reserve_isSixMonthsOfFixedCost() {
        // 월 고정비만 200으로 → 예비 운영자금 1200 증가분이 ex 구간에 반영
        CostBlocks blocks = new CostBlocks(new Interval(0, 0), new Interval(0, 0),
                new Interval(0, 0), 200);
        CostEstimate cost = CostCalculator.estimate(blocks);
        assertThat(cost.exPremium()).isEqualTo(new Interval(1200, 1200));
    }

    @Test
    void inclPremium_addsPremiumIntervalOnTopOfExPremium() {
        CostEstimate cost = CostCalculator.estimate(mangwon);
        assertThat(cost.inclPremium()).isEqualTo(new Interval(7400, 9100));
    }

    @Test
    void preservesIntervals_lowStrictlyBelowHigh() {
        CostEstimate cost = CostCalculator.estimate(mangwon);
        assertThat(cost.exPremium().low()).isLessThan(cost.exPremium().high());
        assertThat(cost.inclPremium().low()).isLessThan(cost.inclPremium().high());
    }

    @Test
    void intervalMedian_isMidpointAsDouble() {
        assertThat(new Interval(7400, 9100).median()).isEqualTo(8250.0);
        assertThat(new Interval(5, 6).median()).isEqualTo(5.5);
    }
}

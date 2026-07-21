package com.ventry.api.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * BE-03d Frontier — 진입 프론티어 닫힌 형태 (exploration spec §2-1). 오차 0 검증.
 * costs = 후보 상권별 비용 중앙값(만원). c_a(포함) 또는 c'_a(무권리) 배열 동일 계산.
 */
class FrontierTest {

    // 수작업 대조용 후보 비용 배열 (정렬 상태)
    private final int[] costs = {6480, 7100, 7800, 8200, 9320};

    @Test
    void nEntry_countsCandidatesAtOrBelowBudget() {
        assertThat(Frontier.nEntry(costs, 8000)).isEqualTo(3);   // 6480,7100,7800
    }

    @Test
    void nEntry_atExactCost_isInclusive() {
        assertThat(Frontier.nEntry(costs, 7800)).isEqualTo(3);   // 7800 포함
        assertThat(Frontier.nEntry(costs, 7799)).isEqualTo(2);   // 7800 제외
    }

    @Test
    void nEntry_isMonotoneNonDecreasingInBudget() {
        assertThat(Frontier.nEntry(costs, 6000)).isEqualTo(0);
        assertThat(Frontier.nEntry(costs, 6480)).isEqualTo(1);
        assertThat(Frontier.nEntry(costs, 9320)).isEqualTo(5);
        assertThat(Frontier.nEntry(costs, 99999)).isEqualTo(5);
    }

    @Test
    void nextBoundary_isSmallestCostStrictlyAboveB0() {
        assertThat(Frontier.nextBoundary(costs, 8000)).hasValue(8200);
        assertThat(Frontier.nextBoundary(costs, 7800)).hasValue(8200);   // 동점은 초과가 아님
    }

    @Test
    void nextBoundary_emptyWhenB0AtOrAboveMax() {
        assertThat(Frontier.nextBoundary(costs, 9320)).isEmpty();
        assertThat(Frontier.nextBoundary(costs, 10000)).isEmpty();
    }

    @Test
    void gap_isNextBoundaryMinusB0() {
        assertThat(Frontier.gap(costs, 8000)).hasValue(200);     // 8200 − 8000
    }

    @Test
    void gap_emptyWhenNoHigherCandidate() {
        assertThat(Frontier.gap(costs, 9320)).isEmpty();
    }

    @Test
    void bSafe_isLargestCostAtOrBelowB0() {
        assertThat(Frontier.bSafe(costs, 8000)).hasValue(7800);
        assertThat(Frontier.bSafe(costs, 7800)).hasValue(7800);
    }

    @Test
    void bSafe_emptyWhenB0BelowMinimumCost() {
        assertThat(Frontier.bSafe(costs, 6000)).isEmpty();
    }

    @Test
    void closedForm_isOrderIndependent() {
        int[] shuffled = {8200, 6480, 9320, 7800, 7100};
        assertThat(Frontier.nextBoundary(shuffled, 8000)).hasValue(8200);
        assertThat(Frontier.bSafe(shuffled, 8000)).hasValue(7800);
        assertThat(Frontier.nEntry(shuffled, 8000)).isEqualTo(3);
    }

    @Test
    void frontierPoints_areCumulativeStepCoordinates() {
        assertThat(Frontier.frontierPoints(costs)).containsExactly(
                new FrontierPoint(6480, 1), new FrontierPoint(7100, 2), new FrontierPoint(7800, 3),
                new FrontierPoint(8200, 4), new FrontierPoint(9320, 5));
    }

    @Test
    void frontierPoints_collapseDuplicateCostsIntoOneThreshold() {
        int[] dup = {6480, 6480, 7800};
        assertThat(Frontier.frontierPoints(dup)).containsExactly(
                new FrontierPoint(6480, 2), new FrontierPoint(7800, 3));
    }

    @Test
    void frontierPoints_emptyForNoCandidates() {
        assertThat(Frontier.frontierPoints(new int[] {})).isEqualTo(List.of());
    }
}

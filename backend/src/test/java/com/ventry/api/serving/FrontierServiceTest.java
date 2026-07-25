package com.ventry.api.serving;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * BE-04 FrontierService — 후보 → 비용 배열 → {@link com.ventry.api.engine.Frontier} 결선 검증.
 * 데모 픽스처 비용 중앙값(수작업): 홍대 incl 7,500 / 망원 7,750 / 합정 7,950,
 * 무권리 ex 홍대 6,050 / 망원 6,200 / 합정 6,400.
 */
class FrontierServiceTest {

    private final FrontierService svc = new FrontierService(new DemoCandidates());

    @Test
    void frontierPoints_areCumulativeStepsInContractShape() {
        assertThat(svc.frontierPoints("cafe")).containsExactly(
                List.of(7500, 1), List.of(7750, 2), List.of(7950, 3));
    }

    /** 무권리 프론티어는 같은 계산에 c'_a 배열만 바꿔 넣은 결과다 (expl §2-1). */
    @Test
    void frontierPointsExPremium_useExPremiumCostArray() {
        assertThat(svc.frontierPointsExPremium("cafe")).containsExactly(
                List.of(6050, 1), List.of(6200, 2), List.of(6400, 3));
    }

    @Test
    void entryCount_matchesStepFunctionAtBudget() {
        assertThat(svc.entryCount("cafe", 8000)).isEqualTo(3);
        assertThat(svc.entryCount("cafe", 7600)).isEqualTo(1);   // 홍대(7,500)만 진입
        assertThat(svc.entryCount("cafe", 5000)).isZero();
    }

    /** 데모 예산 8,000에서는 모든 후보가 이미 진입 가능 → 상향 경계가 없다. */
    @Test
    void noBoundaryAboveDemoBudget_sinceAllCandidatesAlreadyEntered() {
        assertThat(svc.nextBoundary("cafe", 8000)).isEmpty();
        assertThat(svc.gap("cafe", 8000)).isEmpty();
    }

    /** 7,600에서는 다음 경계가 망원(7,750) → 갭 150 (수작업 대조). */
    @Test
    void boundaryAndGap_areClosedFormValues() {
        assertThat(svc.nextBoundary("cafe", 7600)).hasValue(7750);
        assertThat(svc.gap("cafe", 7600)).hasValue(150);
    }

    @Test
    void safeBudget_isLargestCostAtOrBelowBudget() {
        assertThat(svc.safeBudget("cafe", 8000)).hasValue(7950);
        assertThat(svc.safeBudget("cafe", 7600)).hasValue(7500);
        assertThat(svc.safeBudget("cafe", 5000)).isEmpty();
    }

    /** 무권리 비용은 항상 권리금 포함 비용보다 작다 (권리금 블록만큼). */
    @Test
    void exPremiumCosts_areBelowInclusiveCosts() {
        int[] incl = svc.inclusiveCosts("cafe");
        int[] ex = svc.exPremiumCosts("cafe");
        assertThat(ex).hasSameSizeAs(incl);
        for (int i = 0; i < incl.length; i++) {
            assertThat(ex[i]).isLessThan(incl[i]);
        }
    }
}

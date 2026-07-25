package com.ventry.api.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * BE-04 Loan — 원리금균등 월 상환액 (assumptions #22). 수작업 계산 대조 + 경계값.
 * m = P·i/(1−(1+i)⁻ⁿ), i = 연이율/12.
 */
class LoanTest {

    /** 데모 갭 1,320만 · 연 2.5% · 60개월 → 2.75 / 0.117385 ≈ 23.43만원. */
    @Test
    void monthlyPayment_matchesHandCalculation_forDemoGap() {
        assertThat(Loan.monthlyPayment(1320, 2.5, 60)).isCloseTo(23.43, within(0.01));
    }

    /** DoD 케이스 1의 갭 200만 · 연 2.5% · 60개월 → 0.416667 / 0.117385 ≈ 3.55만원. */
    @Test
    void monthlyPayment_matchesHandCalculation_forSmallGap() {
        assertThat(Loan.monthlyPayment(200, 2.5, 60)).isCloseTo(3.55, within(0.01));
    }

    /** 경계: 이율 0 → 원금 균등분할(이자항 0으로 나눗셈 붕괴 방지). */
    @Test
    void monthlyPayment_zeroRate_splitsPrincipalEvenly() {
        assertThat(Loan.monthlyPayment(1200, 0.0, 12)).isEqualTo(100.0);
    }

    /** 경계: 기간 1개월 → 원금 + 1개월 이자. 100만·연 12%(월 1%) → 101만. */
    @Test
    void monthlyPayment_singleMonth_isPrincipalPlusOneMonthInterest() {
        assertThat(Loan.monthlyPayment(100, 12.0, 1)).isCloseTo(101.0, within(0.001));
    }

    /** 경계: 원금이 없으면 상환액도 0 (커버 금액 0인 경계 처리). */
    @Test
    void monthlyPayment_nonPositivePrincipal_isZero() {
        assertThat(Loan.monthlyPayment(0, 2.5, 60)).isZero();
        assertThat(Loan.monthlyPayment(-100, 2.5, 60)).isZero();
    }

    /** 기간이 0 이하이면 정의되지 않는다 — 조용히 0을 돌려주지 않고 즉시 실패시킨다. */
    @Test
    void monthlyPayment_nonPositiveTerm_throws() {
        assertThatThrownBy(() -> Loan.monthlyPayment(1000, 2.5, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 기간이 길수록 월 상환액은 작아진다 (단조성 — 가정 병기 문구의 근거). */
    @Test
    void monthlyPayment_decreasesAsTermLengthens() {
        assertThat(Loan.monthlyPayment(1320, 2.5, 36))
                .isGreaterThan(Loan.monthlyPayment(1320, 2.5, 60));
    }
}

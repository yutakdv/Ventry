package com.ventry.api.engine;

/**
 * BE-04 — 원리금균등 상환 월 납입액 (순수 함수, exploration spec §2-2·§2-3).
 * {@code m = P·i / (1 − (1+i)⁻ⁿ)}, {@code i = 연이율/12}. 이율 0이면 원금 균등분할.
 * 금액은 만원 단위, 금리는 연 % (assumptions #22).
 */
public final class Loan {

    /** 상품 조건에 상환기간이 없을 때의 보수 가정 — 화면 병기 대상 (assumptions #22). */
    public static final int DEFAULT_TERM_MONTHS = 60;

    private Loan() {}

    /**
     * 월 상환액(만원). 원금이 0 이하면 0, 기간은 양수여야 한다.
     * 반올림은 표현 계층 몫 — 여기서는 정밀도를 유지한다.
     */
    public static double monthlyPayment(int principal, double annualRatePct, int termMonths) {
        if (termMonths <= 0) {
            throw new IllegalArgumentException("termMonths must be positive: " + termMonths);
        }
        if (principal <= 0) {
            return 0.0;
        }
        double i = annualRatePct / 100.0 / 12.0;
        if (i == 0.0) {
            return (double) principal / termMonths;   // 무이자 → 원금 균등분할
        }
        return principal * i / (1 - Math.pow(1 + i, -termMonths));
    }
}

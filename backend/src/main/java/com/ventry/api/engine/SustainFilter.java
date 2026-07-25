package com.ventry.api.engine;

import java.util.List;

/**
 * BE-05 — 확장 부담률 필터 2′ 와 지속 프론티어 (순수 함수, exploration spec §2-3).
 *
 * <pre>
 * 필터 2   :  환산임대료 / 월 추정매출        ≤ θ
 * 필터 2′  : (환산임대료 + m) / 월 추정매출   ≤ θ′
 * N_sustain(B) = |{a : c_a ≤ B  AND  필터 2′ 통과}|
 * </pre>
 *
 * <p><b>m=0이면 필터 2와 완전히 동일하다.</b> 좌변이 같아지는 것만으로는 부족해서,
 * 임계도 θ′(0.20)가 아니라 θ(0.15)를 적용한다 — 차입이 없으면 확장 임계를 쓸 근거가
 * 없기 때문이다 (assumptions #26). 이 동일성이 필터 2′ 구현의 검증 기준점이다.
 *
 * <p>N_sustain은 <b>B에 대해 단조가 아니다</b>. 차입으로 B를 늘리면 그 월 상환액 m이
 * 새로 진입한 후보뿐 아니라 <b>기존 후보 전원의 고정비</b>에도 동일하게 얹히므로,
 * 진입 후보가 늘어도 지속 후보는 줄 수 있다. 이 비단조성이 "버틸 수 있는 곳"이라는
 * 서비스 철학의 수학적 구현이며, T1 인사이트는 두 수를 반드시 병기한다.
 *
 * <p>m 은 {@link Loan#monthlyPayment}가 만드는 결정적 계산값이며 LLM이 산출하지 않는다.
 */
public final class SustainFilter {

    /** 확장 부담률 임계 θ′ 기본값 = θ + 5%p (assumptions #15·#26, 통용 기준이며 조정 가능). */
    public static final double DEFAULT_THETA_PRIME = 0.20;

    private SustainFilter() {}

    /**
     * 확장 부담률 = (환산임대료 + 월 상환액) ÷ 월 추정매출.
     * 매출이 0 이하(결측)면 판정 불가를 뜻하는 {@link Double#POSITIVE_INFINITY}를 돌려준다.
     */
    public static double extendedBurdenRatio(SustainInput candidate, double monthlyPayment) {
        if (candidate.estSales() <= 0) {
            return Double.POSITIVE_INFINITY;   // 분모 결측 — 어떤 임계도 통과하지 못한다
        }
        return (candidate.monthlyRent() + monthlyPayment) / candidate.estSales();
    }

    /** 필터 2′ 통과 여부 (기본 임계 θ=0.15 · θ′=0.20). */
    public static boolean passes(SustainInput candidate, double monthlyPayment) {
        return passes(candidate, monthlyPayment, ReverseCheck.DEFAULT_THETA, DEFAULT_THETA_PRIME);
    }

    /**
     * 필터 2′ 통과 여부. <b>차입이 없으면(m ≤ 0) θ를 적용해 필터 2와 동일해진다</b> —
     * 좌변도 {@code 임대료/매출}로 축약되고 임계도 θ가 되어 두 필터가 완전히 일치한다.
     */
    public static boolean passes(SustainInput candidate, double monthlyPayment,
                                 double theta, double thetaPrime) {
        double threshold = monthlyPayment <= 0 ? theta : thetaPrime;
        return extendedBurdenRatio(candidate, monthlyPayment) <= threshold;
    }

    /** N_sustain(B) — 진입 통과(c_a ≤ B) 이면서 필터 2′도 통과하는 후보 수. */
    public static int nSustain(List<SustainInput> candidates, int budget, double monthlyPayment) {
        return nSustain(candidates, budget, monthlyPayment,
                ReverseCheck.DEFAULT_THETA, DEFAULT_THETA_PRIME);
    }

    /** N_sustain(B) — 임계를 직접 지정하는 형태. */
    public static int nSustain(List<SustainInput> candidates, int budget, double monthlyPayment,
                               double theta, double thetaPrime) {
        int count = 0;
        for (SustainInput candidate : candidates) {
            if (candidate.costMedian() <= budget
                    && passes(candidate, monthlyPayment, theta, thetaPrime)) {
                count++;
            }
        }
        return count;
    }
}

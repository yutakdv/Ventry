package com.ventry.api.engine;

import com.ventry.api.common.Verdict;

/**
 * BE-03e — 역방향 판정 (순수 함수, 스펙 §4-1·§4-2). 임의 상권 클릭 → 판정 4단계.
 * 경계 규칙: 진입 = 권리금 포함 구간 중앙값 ≤ 예산. 제외 ≤ 예산 &lt; 포함 중앙값 → ⚪ 조건부.
 * 진입 후 부담률 θ 초과 시 🟠 유의.
 */
public final class ReverseCheck {

    /** 부담률 임계 θ 기본값 (assumptions.md 등재, D1 문헌 확정 시 갱신). */
    public static final double DEFAULT_THETA = 0.15;

    private ReverseCheck() {}

    /**
     * 부담률 = 환산임대료 ÷ 월 추정매출 (스펙 §4-2 필터 2의 좌변).
     * 분모·분자는 배치 산출물(AI-05)이며 서빙은 비율을 저장하지 않고 매번 파생한다.
     *
     * <p><b>매출이 0이면 판정 불가를 명시적 센티널로 돌려준다</b> — {@link SustainFilter#extendedBurdenRatio}
     * 와 같은 의미론이다. 가드가 없을 때 `rent / 0` 이 그대로 흘러 계약이 number 로 규정한
     * `burden_ratio` 가 문자열 {@code "Infinity"} 로 직렬화됐고, 리스크 검증 반박문에도 그 글자가
     * 실려 나갔다 (BE 리뷰 D-04 · 이슈 #104 ⑤). 표현 계층은 비유한값을 필드 생략으로 내보낸다.
     */
    public static double burdenRatio(int monthlyRent, int estSales) {
        if (estSales <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return (double) monthlyRent / estSales;
    }

    public static ReverseResult evaluate(int budget, CostEstimate cost, double burdenRatio, double theta) {
        double exMedian = cost.exPremium().median();
        double inclMedian = cost.inclPremium().median();
        int gap = budget < inclMedian ? (int) Math.ceil(inclMedian - budget) : 0;

        Verdict verdict;
        if (budget < exMedian) {
            verdict = Verdict.OUT_OF_SCOPE;                 // 무권리 진입선에도 미달 → 범위 외
        } else if (budget < inclMedian) {
            verdict = Verdict.CONDITIONAL;                  // 제외 ≤ 예산 < 포함 중앙값 → 조건부
        } else if (burdenRatio <= theta) {
            verdict = Verdict.FIT;                          // 진입 + 부담률 임계 이내 → 적합
        } else {
            verdict = Verdict.CAUTION;                      // 진입되나 부담률 θ 초과 → 유의
        }
        return new ReverseResult(verdict, gap);
    }
}

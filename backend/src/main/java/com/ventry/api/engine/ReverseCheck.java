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

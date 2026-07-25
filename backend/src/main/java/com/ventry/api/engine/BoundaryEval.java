package com.ventry.api.engine;

/**
 * BE-05 — 의사결정 경계 1건의 평가 결과 (exploration spec §2-4의 스코어 입력).
 * 순수 데이터이며, 이 값들을 만드는 계산은 전부 {@link Frontier}·{@link SustainFilter}·
 * {@link FundingCheck}·{@link ScoreLookup} 이 담당한다 — 여기서 새로 계산하지 않는다.
 *
 * <p><b>커버 실패 경계도 이 객체로 남긴다.</b> 보고에서는 빠지지만
 * {@code scenarios_explored}(평가한 경계 개수, assumptions #29)에는 포함되기 때문이다 —
 * "검토했으나 조달 수단이 없어 제외했다"와 "검토조차 안 했다"는 다른 사실이다.
 *
 * @param boundary            경계 예산값(만원) = min{c_a &gt; B₀}
 * @param gap                 경계 − B₀ (만원)
 * @param monthlyPayment      한계 조달의 월 상환액 m(만원). 커버 실패면 0
 * @param fundingProductCount 편성된 상품 수 — 조건 단순성(F)의 재료. 적을수록 단순하다
 * @param topScoreBefore      B₀ 진입 후보 중 최고 종합점수(0~100, assumptions #8)
 * @param topScoreAfter       경계 진입 후보 중 최고 종합점수(0~100)
 * @param reason              커버 판정 사유. {@link CoverOutcome.Reason#NONE} 만 보고 대상
 */
public record BoundaryEval(int boundary, int gap, double monthlyPayment, int fundingProductCount,
                           int nEntryBefore, int nEntryAfter,
                           int nGreenBefore, int nGreenAfter,
                           int nSustainBefore, int nSustainAfter,
                           int topScoreBefore, int topScoreAfter,
                           CoverOutcome.Reason reason) {

    /** 점수 등급 구간 폭 — 스펙 §2-4 "상위 백분위 1구간"의 구현 단위 (90+/80/70/60). */
    public static final int SCORE_GRADE_BAND = 10;

    /** 조달 커버에 성공해 보고 후보가 될 수 있는 경계인가. */
    public boolean covered() {
        return reason == CoverOutcome.Reason.NONE;
    }

    public int deltaNEntry() {
        return nEntryAfter - nEntryBefore;
    }

    /** 🟢(적합) 후보 증가분 — 품질 조건의 1차 기준. */
    public int deltaNGreen() {
        return nGreenAfter - nGreenBefore;
    }

    /** 지속 안정 후보 증가분. 상환 부담 때문에 <b>음수일 수 있다</b> (비단조, §2-3). */
    public int deltaNSustain() {
        return nSustainAfter - nSustainBefore;
    }

    /**
     * 최고 점수의 <b>등급 구간</b> 개선 폭 — 스펙 §2-4 "ΔS_top이 상위 백분위 1구간 이상 개선".
     * 원점수 차가 아니라 구간 차를 쓰는 이유는 Q의 다른 항(후보 수 증가분)과 단위를 맞추기 위함이다.
     */
    public int deltaScoreGrades() {
        return topScoreAfter / SCORE_GRADE_BAND - topScoreBefore / SCORE_GRADE_BAND;
    }
}

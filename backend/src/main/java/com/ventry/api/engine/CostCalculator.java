package com.ventry.api.engine;

/**
 * BE-03b — 초기비용 4블록 합성 (순수 함수, 스펙 §4-1).
 * 구간 유지(점추정 금지), 권리금 이중 표기.
 */
public final class CostCalculator {

    /** 예비 운영자금 = 월 고정비 × N개월 (여신 관행 버퍼). */
    static final int RESERVE_MONTHS = 6;

    private CostCalculator() {}

    public static CostEstimate estimate(CostBlocks blocks) {
        int reserve = blocks.monthlyFixedCost() * RESERVE_MONTHS;
        Interval exPremium = blocks.deposit().plus(blocks.interior()).shift(reserve);
        Interval inclPremium = exPremium.plus(blocks.premium());
        return new CostEstimate(exPremium, inclPremium);
    }
}

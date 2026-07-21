package com.ventry.api.engine;

/**
 * 초기비용 4블록 원자재 (스펙 §4-1). 각 블록은 구간, 예비 운영자금은 월 고정비×6으로 산출.
 *
 * @param deposit          보증금 구간(환산임대료 역산)
 * @param premium          권리금 구간(연간 조사, 전년 기준)
 * @param interior         인테리어·시설비 구간(업종 상수)
 * @param monthlyFixedCost 월 고정비(만원) — 예비 운영자금 = ×6 (여신 관행 버퍼)
 */
public record CostBlocks(Interval deposit, Interval premium, Interval interior, int monthlyFixedCost) {}

package com.ventry.api.engine;

/**
 * 5축 정규화 점수 [0,1] (서울 전체 백분위) — 계약 recommend.breakdown 과 1:1 (스펙 §4-3).
 *
 * @param w1 수요(길단위 유동+배후+교통 유입)
 * @param w2 구매력(추정매출)
 * @param w3 경쟁여유(밀도 역방향)
 * @param w4 성장성(상권변화지표)
 * @param w5 비용효율(매출÷임대료)
 */
public record AxisScores(double w1, double w2, double w3, double w4, double w5) {}

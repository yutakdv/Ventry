package com.ventry.api.engine;

/**
 * 초기비용 추정 결과 — 권리금 이중 표기(계약 cost.ex_premium / cost.incl_premium).
 * 둘 다 구간, "추정치" 라벨은 표현 계층에서 부여.
 */
public record CostEstimate(Interval exPremium, Interval inclPremium) {}

package com.ventry.api.engine;

/** 업종 프리셋 가중치 (화면 공개, 서빙 ML 미사용). 종합점수 = Σ wᵢ·축ᵢ. */
public record Weights(double w1, double w2, double w3, double w4, double w5) {}

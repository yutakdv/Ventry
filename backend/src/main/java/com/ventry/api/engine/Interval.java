package com.ventry.api.engine;

/**
 * 닫힌 정수 구간 [low, high] (만원 단위). 점추정 금지 원칙(스펙 §4-1)의 표현 단위.
 */
public record Interval(int low, int high) {

    /** 구간 중앙값(비교·판정용, 만원 소수 허용). */
    public double median() {
        return (low + high) / 2.0;
    }

    /** 두 구간의 원소별 합. */
    public Interval plus(Interval other) {
        return new Interval(low + other.low, high + other.high);
    }

    /** 구간 전체에 상수 이동. */
    public Interval shift(int amount) {
        return new Interval(low + amount, high + amount);
    }
}

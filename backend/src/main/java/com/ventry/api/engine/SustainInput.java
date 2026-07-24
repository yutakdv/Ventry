package com.ventry.api.engine;

/**
 * BE-05 — 지속 프론티어 계산용 후보 원자재 (exploration spec §2-3).
 * engine 계층이 serving 타입({@code CandidateArea})을 알지 못하도록 두는 경계이며,
 * 부담률은 여기서도 저장하지 않고 분모·분자에서 매번 파생한다 (스펙 §4-2).
 *
 * @param monthlyRent 환산임대료(만원/월) — 부담률 분자
 * @param estSales    월 추정매출(만원) — 부담률 분모. 0 이하는 결측으로 보고 탈락 처리
 * @param costMedian  권리금 포함 비용 중앙값 c_a(만원) — 진입 비교 기준
 */
public record SustainInput(int monthlyRent, int estSales, int costMedian) {}

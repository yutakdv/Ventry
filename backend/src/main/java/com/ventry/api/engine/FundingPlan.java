package com.ventry.api.engine;

import java.util.List;

/**
 * BE-04 — 한계 조달 명세 (exploration spec §2-2). 커버 성공 시에만 생성된다.
 * 금액은 만원 단위, 월 상환액은 원리금균등 결정적 계산값이다.
 *
 * @param allocations    선택된 상품별 사용액 (선택 순서 = 잔여 한도 원칙 순)
 * @param totalAmount    조달 합계(만원) — 항상 gap 이상
 * @param monthlyPayment 월 상환액 m(만원) — 화면 표기 시 금리·기간 가정 병기 필수 (expl §7)
 */
public record FundingPlan(List<Allocation> allocations, int totalAmount, double monthlyPayment) {

    /**
     * @param termMonths  적용된 상환기간(개월)
     * @param termAssumed 상품 조건이 없어 기본 가정(60개월)을 쓴 경우 true — 화면 병기 대상 (assumptions #22)
     */
    public record Allocation(FundingProduct product, int amount, int termMonths, boolean termAssumed) {}
}

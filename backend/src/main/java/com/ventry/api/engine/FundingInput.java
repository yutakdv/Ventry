package com.ventry.api.engine;

/**
 * BE-04 — 조달 검증 전용 입력. 자격 판정 입력인 {@link Profile}과 분리되어 있으며
 * {@link EligibilityFilter}는 이 필드들을 알지 못한다 (자격 정규칙 ≠ 조달 조건).
 *
 * @param monthlyInvestable   월 투자 가능액(만원) — 한계 조달 월 상환액 m 의 상한. null=상한 없음 (assumptions #23)
 * @param collateralAvailable 담보 제공 가능 여부 — **현재 판정 미반영**. 상품 측에 담보 요구 필드가
 *                            없어 결선을 보류한 상태다 (assumptions #24, AI-06 컬럼 확정 시 재개)
 */
public record FundingInput(Integer monthlyInvestable, boolean collateralAvailable) {}

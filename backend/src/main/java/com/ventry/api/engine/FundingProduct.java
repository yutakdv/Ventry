package com.ventry.api.engine;

import com.ventry.api.common.FinanceDtos.Source;

/**
 * 금융상품 도메인 엔티티 — 자격 필터(BE-03a)와 조달 검증(BE-04)이 공유.
 * 금액은 만원 단위 정수, 금리는 연 %.
 *
 * @param amountMax       최대 한도(만원)
 * @param rate            연 금리(%)
 * @param termMonths      상환기간(개월). null=상품 조건 미정(보증 가정 T는 BE-04에서 부여)
 * @param exclusiveGroup  중복수혜 제약 그룹(동일 그룹 1개만). null=제약 없음
 * @param status          "open" | "closed"
 */
public record FundingProduct(String name, Eligibility eligibility, int amountMax, double rate,
                             Integer termMonths, String exclusiveGroup, String status, Source source) {}

package com.ventry.api.engine;

import com.ventry.api.common.FinanceDtos.Product;
import com.ventry.api.common.FinanceDtos.Source;
import com.ventry.api.common.FinanceDtos.SourceQuote;

/**
 * 금융상품 도메인 엔티티 — 자격 필터(BE-03a)와 조달 검증(BE-04)이 공유.
 * 금액은 만원 단위 정수, 금리는 연 %.
 *
 * @param amountMax       최대 한도(만원)
 * @param rate            연 금리(%)
 * @param termMonths      상환기간(개월). null=상품 조건 미정(보증 가정 T는 BE-04에서 부여)
 * @param exclusiveGroup  중복수혜 제약 그룹(동일 그룹 1개만). null=제약 없음
 * @param status          "open" | "closed"
 * @param dataAsOf        상품 조건 기준일 — 화면 표기 필수 (계약 공통 규약)
 */
public record FundingProduct(String name, Eligibility eligibility, int amountMax, double rate,
                             Integer termMonths, String exclusiveGroup, String status,
                             String dataAsOf, Source source) {

    /** 화면 노출용 DTO 투영. source_quote(RAG)는 BE-06까지 null. */
    public Product toProduct(SourceQuote sourceQuote) {
        return new Product(name, amountMax, rate, dataAsOf, source, sourceQuote);
    }
}

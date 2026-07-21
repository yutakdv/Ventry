package com.ventry.api.common;

/** 금융상품 공통 DTO — 시나리오·탐색·역방향이 공유 (계약 공통 규약: source 필수). */
public final class FinanceDtos {

    private FinanceDtos() {}

    public record Source(String org, String url, String collected) {}

    /** RAG 원문 인용 — 구현 전 null 허용 (P1-①, 스펙 §5-4). */
    public record SourceQuote(String text, String org, String doc, String date) {}

    /**
     * 금융상품 화면 DTO. `data_as_of`(기준일)는 계약 공통 규약상 필수 —
     * 화면이 "○○ 기준"을 표기할 근거이며 상품 조건의 신선도를 사용자에게 드러낸다.
     *
     * @param amountMax 최대 한도(만원). 승인 금액이 아니라 상품 공고상 한도
     * @param rate      연 금리(%)
     */
    public record Product(String name, int amountMax, double rate, String dataAsOf,
                          Source source, SourceQuote sourceQuote) {}

    public record RiskReview(String objectionText, boolean applied, boolean skipped) {}
}

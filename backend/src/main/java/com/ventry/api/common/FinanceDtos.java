package com.ventry.api.common;

/** 금융상품 공통 DTO — 시나리오·탐색·역방향이 공유 (계약 공통 규약: source 필수). */
public final class FinanceDtos {

    private FinanceDtos() {}

    public record Source(String org, String url, String collected) {}

    /** RAG 원문 인용 — 구현 전 null 허용 (P1-①, 스펙 §5-4). */
    public record SourceQuote(String text, String org, String doc, String date) {}

    public record Product(String name, Source source, SourceQuote sourceQuote) {}

    public record RiskReview(String objectionText, boolean applied, boolean skipped) {}
}

package com.ventry.api.common;

/** 금융상품 공통 DTO — 시나리오·탐색·역방향이 공유 (계약 공통 규약: source 필수). */
public final class FinanceDtos {

    private FinanceDtos() {}

    public record Source(String org, String url, String collected) {}

    /**
     * 원문 인용 (BE-06 ①, 스펙 §5-4). {@code finance_product.doc_chunk_ref} 로
     * {@code finance_doc_chunk} 를 <b>id 직접 조회</b>한 결과이며 유사도 검색·벡터DB는 쓰지 않는다
     * (DECISIONS §7).
     *
     * <p>{@code text} 는 <b>공고문 원문 그대로</b>다 — 서버는 요약·재작성은 물론 길이 자르기도 하지
     * 않는다. "인용은 검색이지 생성이 아니다"(§5-4)는 바이트 동일성으로만 증명되기 때문이다.
     * 적재본 기준 19자~7,688자로 편차가 크므로(문단 단위 10건 ≤583자, 문서 통짜 8건 1.5~7.7KB)
     * <b>화면에서의 줄 수 제한은 표현 계층이 담당</b>하고 전문은 {@code source.url} 로 연결한다.
     *
     * <p>연결된 청크가 없으면(=doc_chunk_ref NULL) 이 객체 자체가 null 이다. 인용을 지어내지 않는다.
     *
     * @param org  출처 기관 · @param doc 문서명 · @param date 문서 기준일 (청크 {@code doc_meta})
     */
    public record SourceQuote(String text, String org, String doc, String date) {}

    /**
     * 금융상품 화면 DTO. `data_as_of`(기준일)는 계약 공통 규약상 필수 —
     * 화면이 "○○ 기준"을 표기할 근거이며 상품 조건의 신선도를 사용자에게 드러낸다.
     *
     * @param amountMax 최대 한도(만원). 승인 금액이 아니라 상품 공고상 한도
     * @param rate      연 금리(%). <b>확정 이율이 없으면 null</b> — 계약 공통 규약
     *                  ("값이 없는 필드는 응답에서 생략")에 따라 필드 자체가 사라진다.
     *                  0을 넣어 무이자로 오인시키지 않기 위한 선택이다 (assumptions #28)
     * @param rateType  {@code "fixed"} | {@code "variable"} — <b>항상 존재</b>(계약 D8 FE 분기 키).
     *                  {@code rate} 유무가 아니라 이 값으로 판단한다(non_null 직렬화라 rate는 생략됨)
     * @param rateNote  변동금리 원문 표현(예: "정책자금 기준금리+0.6%p"). fixed면 null → 응답에서 생략
     */
    public record Product(String name, int amountMax, Double rate, String rateType, String rateNote,
                          String dataAsOf, Source source, SourceQuote sourceQuote) {}

    public record RiskReview(String objectionText, boolean applied, boolean skipped) {}
}

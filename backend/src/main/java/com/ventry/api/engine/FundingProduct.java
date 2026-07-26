package com.ventry.api.engine;

import com.ventry.api.common.FinanceDtos.Product;
import com.ventry.api.common.FinanceDtos.Source;
import com.ventry.api.common.FinanceDtos.SourceQuote;

/**
 * 금융상품 도메인 엔티티 — 자격 필터(BE-03a)와 조달 검증(BE-04·05)이 공유.
 * 금액은 만원 단위 정수, 금리는 연 %.
 *
 * <p><b>금리는 null일 수 있다</b> (BE-05, assumptions #28). 변동금리·기준금리 연동 상품은
 * 확정 이율이 공고에 없어서, 월 상환액 m을 계산할 수 없다. 이때 <b>가정 금리를 만들지 않고</b>
 * 해당 경계를 보고에서 제외한다 — 근거 없는 수치를 만드는 것이 침묵보다 나쁘기 때문이다.
 *
 * @param amountMax       최대 한도(만원)
 * @param rate            연 금리(%). <b>null = 확정 이율 미상</b> → m 산출 불가 → 커버 후보 제외
 * @param rateType        {@link #RATE_FIXED} | {@link #RATE_VARIABLE} — 화면·로그 설명용
 * @param rateNote        금리 조건 원문 메모(예: "기준금리 + 0.6%p"). 없으면 null
 * @param termMonths      상환기간(개월). null=상품 조건 미정 (보증 가정 T = {@link Loan#DEFAULT_TERM_MONTHS})
 * @param exclusiveGroup  중복수혜 제약 그룹(동일 그룹 1개만). null=제약 없음
 * @param status          "open" | "closed"
 * @param dataAsOf        상품 조건 기준일 — 화면 표기 필수 (계약 공통 규약)
 * @param sourceQuote     공고 원문 인용. <b>null = 연결된 청크 없음</b> (BE-06 ①, 스펙 §5-4).
 *                        유사도 검색이 아니라 {@code doc_chunk_ref} id 직접 조회 결과이므로
 *                        상품에 1:1로 붙는 속성이다 — 조회 시점이 아니라 적재 시점에 정해진다
 */
public record FundingProduct(String productId, String name, Eligibility eligibility, int amountMax,
                             Double rate, String rateType, String rateNote,
                             Integer termMonths, String exclusiveGroup, String status,
                             String dataAsOf, Source source, SourceQuote sourceQuote) {

    /** 확정 이율 — m을 결정적으로 계산할 수 있다. */
    public static final String RATE_FIXED = "fixed";

    /** 변동·연동 금리 — 확정 이율이 없으면 {@code rate=null}과 함께 쓴다. */
    public static final String RATE_VARIABLE = "variable";

    /**
     * 확정 이율 상품용 간편 생성자 (기존 호출부·픽스처 호환).
     * {@code rateType}은 {@link #RATE_FIXED}, {@code rateNote}·{@code sourceQuote}는 null로 채운다.
     */
    public FundingProduct(String name, Eligibility eligibility, int amountMax, double rate,
                          Integer termMonths, String exclusiveGroup, String status,
                          String dataAsOf, Source source) {
        this(null, name, eligibility, amountMax, rate, RATE_FIXED, null,
                termMonths, exclusiveGroup, status, dataAsOf, source, null);
    }

    /** 인용 없는 상품용 생성자 (픽스처·합성 상품). 적재 경로는 정식 생성자로 청크를 함께 싣는다. */
    public FundingProduct(String name, Eligibility eligibility, int amountMax, Double rate,
                          String rateType, String rateNote, Integer termMonths,
                          String exclusiveGroup, String status, String dataAsOf, Source source) {
        this(null, name, eligibility, amountMax, rate, rateType, rateNote,
                termMonths, exclusiveGroup, status, dataAsOf, source, null);
    }

    /**
     * 계약 §6 의 동점 정렬 키 — {@code product_id} 오름차순. 픽스처 상품은 ID 가 없으므로
     * 이름으로 대체한다(픽스처는 동점이 없어 순서가 갈리지 않는다).
     *
     * <p>ID 의 안정성은 적재 측이 보증한다 — 위치 기반 채번을 금지하고 검수본에 명시한다
     * (AI 리뷰 #5 · 가정 #74). 그 전제가 깨지면 이 정렬도 의미를 잃는다.
     */
    public String sortKey() {
        return productId != null ? productId : name;
    }

    /** 월 상환액을 결정적으로 계산할 수 있는가 — 조달 검증의 1차 게이트 (assumptions #28). */
    public boolean hasKnownRate() {
        return rate != null;
    }

    /**
     * <b>확정 이율</b>인가 — 즉 산출한 월 상환액을 그대로 표기해도 되는가 (계약 D8).
     *
     * <p>{@link #hasKnownRate()} 와 다르다. 변동금리 상품도 현 분기 금리는 알려져 있어 계산은
     * 되지만, 그 값을 {@code marginal_payment} 로 실으면 <b>분기마다 바뀌는 값을 고정 금액처럼</b>
     * 보여주게 된다. 그래서 계약은 변동금리에서 금액 대신 {@code marginal_payment_note} 를
     * 싣기로 3인 합의했다(D8 · assumptions #30). 계산 가능성과 표기 가능성은 다른 문제다.
     */
    public boolean hasFixedRate() {
        return rate != null && RATE_FIXED.equals(rateType);
    }

    /**
     * 화면 노출용 DTO 투영. rate_type은 항상, rate_note는 변동 시에만, source_quote는 연결된 청크가
     * 있을 때만 실린다 (non_null 직렬화라 없으면 필드 자체가 생략된다).
     */
    public Product toProduct() {
        return new Product(name, amountMax, rate, rateType, rateNote, dataAsOf, source, sourceQuote);
    }
}

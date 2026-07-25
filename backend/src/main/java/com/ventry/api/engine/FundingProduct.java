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
 */
public record FundingProduct(String name, Eligibility eligibility, int amountMax, Double rate,
                             String rateType, String rateNote,
                             Integer termMonths, String exclusiveGroup, String status,
                             String dataAsOf, Source source) {

    /** 확정 이율 — m을 결정적으로 계산할 수 있다. */
    public static final String RATE_FIXED = "fixed";

    /** 변동·연동 금리 — 확정 이율이 없으면 {@code rate=null}과 함께 쓴다. */
    public static final String RATE_VARIABLE = "variable";

    /**
     * 확정 이율 상품용 간편 생성자 (기존 호출부·픽스처 호환).
     * {@code rateType}은 {@link #RATE_FIXED}, {@code rateNote}는 null로 채운다.
     */
    public FundingProduct(String name, Eligibility eligibility, int amountMax, double rate,
                          Integer termMonths, String exclusiveGroup, String status,
                          String dataAsOf, Source source) {
        this(name, eligibility, amountMax, rate, RATE_FIXED, null,
                termMonths, exclusiveGroup, status, dataAsOf, source);
    }

    /** 월 상환액을 결정적으로 계산할 수 있는가 — 조달 검증의 1차 게이트 (assumptions #28). */
    public boolean hasKnownRate() {
        return rate != null;
    }

    /** 화면 노출용 DTO 투영. rate_type은 항상, rate_note는 변동 시에만 실린다. source_quote(RAG)는 BE-06까지 null. */
    public Product toProduct(SourceQuote sourceQuote) {
        return new Product(name, amountMax, rate, rateType, rateNote, dataAsOf, source, sourceQuote);
    }
}

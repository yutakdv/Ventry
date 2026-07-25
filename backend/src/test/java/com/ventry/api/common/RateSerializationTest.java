package com.ventry.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.ventry.api.common.FinanceDtos.Product;
import com.ventry.api.common.FinanceDtos.Source;
import com.ventry.api.explore.ExploreDtos.Funding;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

/**
 * BE-05 (C안) — 금리 표기 3필드의 직렬화 규약 검증 (계약 D8 · non_null 직렬화).
 *
 * <ul>
 *   <li><b>rate_type은 항상 실린다</b> — FE 분기 키(계약 "항상 존재"). fixed도 예외 없다.
 *   <li><b>rate는 null이면 필드 누락</b> — 0.0·""로 나가면 화면이 무이자로 오인한다 (#28).
 *   <li><b>rate_note는 null이면 생략</b> — fixed 상품엔 안 실리고, variable 상품엔 원문이 실린다.
 * </ul>
 *
 * <p>애플리케이션이 실제로 쓰는 ObjectMapper를 주입받아, 키 문자열 `"rate":`와 `"rate_type":`을
 * 구분해 검증한다(부분 문자열 "rate"로 판단하면 rate_type과 섞인다).
 */
@SpringBootTest
class RateSerializationTest {

    @Autowired
    private ObjectMapper objectMapper;

    private static final Source SRC = new Source("기관", "https://example.test", "2026-07-19");

    /** fixed 상품: rate_type="fixed"는 실리고, rate_note는 null이라 생략된다. */
    @Test
    void fixedProduct_alwaysCarriesRateType_butOmitsNullRateNote() throws Exception {
        String json = objectMapper.writeValueAsString(
                new Product("확정금리 상품", 3000, 2.5, "fixed", null, "2026-Q1", SRC, null));
        assertThat(json).contains("\"rate\":2.5");
        assertThat(json).contains("\"rate_type\":\"fixed\"");   // 항상 존재 (FE 분기 키)
        assertThat(json).doesNotContain("rate_note");           // null → 생략
    }

    /** variable 상품: rate 키는 누락되지만 rate_type·rate_note는 실린다. */
    @Test
    void variableProduct_omitsRateKey_butCarriesRateTypeAndNote() throws Exception {
        String json = objectMapper.writeValueAsString(
                new Product("변동금리 상품", 3000, null, "variable", "정책자금 기준금리+0.6%p",
                        "2026-Q1", SRC, null));
        assertThat(json).doesNotContain("\"rate\":");           // null → 키 누락 (0.0·"" 아님)
        assertThat(json).contains("\"rate_type\":\"variable\"");
        assertThat(json).contains("\"rate_note\":\"정책자금 기준금리+0.6%p\"");
        assertThat(json).contains("\"amount_max\":3000");
    }

    /** 탐색 인사이트 funding 블록도 동일 규약: rate_type 항상, rate_note는 변동 시에만. */
    @Test
    void fundingBlock_followsSameRateContract() throws Exception {
        String fixed = objectMapper.writeValueAsString(
                new Funding("확정금리", 1500, 2.5, "fixed", null, 60, "open", null, null, SRC, null));
        assertThat(fixed).contains("\"rate\":2.5");
        assertThat(fixed).contains("\"rate_type\":\"fixed\"");
        assertThat(fixed).doesNotContain("rate_note");
        assertThat(fixed).contains("\"term_assumed\":60");

        String variable = objectMapper.writeValueAsString(
                new Funding("변동금리", 3000, null, "variable", "기준금리+0.6%p", 60, "open",
                        null, null, SRC, null));
        assertThat(variable).doesNotContain("\"rate\":");
        assertThat(variable).contains("\"rate_type\":\"variable\"");
        assertThat(variable).contains("\"rate_note\":\"기준금리+0.6%p\"");
    }
}

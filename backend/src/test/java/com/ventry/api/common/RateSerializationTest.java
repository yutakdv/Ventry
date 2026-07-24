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
 * BE-05 — {@code rate} 를 Double로 바꾼 뒤, null이 <b>필드 누락</b>으로 나가는지 실제 확인한다
 * (계약 공통 규약 "값이 없는 필드는 응답에서 생략" · non_null 직렬화).
 *
 * <p>이 검증이 필요한 이유: null이 {@code 0.0}이나 {@code ""} 로 나가면 화면이 <b>무이자 상품</b>으로
 * 오인 표기하게 된다. 애플리케이션이 실제로 쓰는 ObjectMapper를 주입받아 확인한다.
 */
@SpringBootTest
class RateSerializationTest {

    @Autowired
    private ObjectMapper objectMapper;

    private static final Source SRC = new Source("기관", "https://example.test", "2026-07-19");

    @Test
    void nullRate_isOmittedFromJson_whileKnownRateIsSerialized() throws Exception {
        String unknown = objectMapper.writeValueAsString(
                new Product("변동금리 상품", 3000, null, "2026-Q1", SRC, null));
        assertThat(unknown).doesNotContain("rate");     // 0.0 도 "" 도 아닌 필드 누락
        assertThat(unknown).contains("\"amount_max\":3000");

        String known = objectMapper.writeValueAsString(
                new Product("확정금리 상품", 3000, 2.5, "2026-Q1", SRC, null));
        assertThat(known).contains("\"rate\":2.5");

        // 탐색 인사이트의 funding 블록도 동일 규약을 따른다
        String funding = objectMapper.writeValueAsString(
                new Funding("변동금리 상품", 3000, null, 60, "open", "2026-05-15", null, SRC, null));
        assertThat(funding).doesNotContain("rate");
        assertThat(funding).contains("\"term_assumed\":60");
    }
}

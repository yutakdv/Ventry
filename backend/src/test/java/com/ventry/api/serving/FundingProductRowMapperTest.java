package com.ventry.api.serving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ventry.api.engine.FundingProduct;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/**
 * BE-06 ① — {@link FundingProductRowMapper} 의 원문 인용 매핑. 실 DB 없이 mock ResultSet 으로
 * 검증한다({@code CandidateRowMapperTest} 와 같은 방식).
 *
 * <p>여기서 지키려는 것은 한 가지다 — <b>인용은 있는 그대로 실리거나, 아예 실리지 않는다</b>.
 * 청크가 없을 때 빈 문자열·상품 설명 따위로 자리를 메우면 그 순간 근거를 지어낸 것이 된다
 * (스펙 §5-4 "인용은 검색이지 생성이 아니다").
 */
class FundingProductRowMapperTest {

    private final FundingProductRowMapper mapper = new FundingProductRowMapper();

    /** 원문은 줄바꿈·공백까지 바이트 동일해야 한다 — 서버는 요약도 길이 자르기도 하지 않는다. */
    @Test
    void mapsSourceQuote_verbatim_fromJoinedChunk() throws SQLException {
        String verbatim = "○ 지원대상\n  - 만 39세 이하 예비창업자로서 사업자등록 전인 자\n";
        ResultSet rs = productRow();
        when(rs.getString("quote_text")).thenReturn(verbatim);
        when(rs.getString("quote_org")).thenReturn("소진공");
        when(rs.getString("quote_doc")).thenReturn("2026_소상공인정책자금_융자공고");
        when(rs.getString("quote_date")).thenReturn("2026-07-21");

        FundingProduct product = mapper.mapRow(rs, 0);

        assertThat(product.sourceQuote()).isNotNull();
        assertThat(product.sourceQuote().text()).isEqualTo(verbatim);
        assertThat(product.sourceQuote().org()).isEqualTo("소진공");
        assertThat(product.sourceQuote().doc()).isEqualTo("2026_소상공인정책자금_융자공고");
        assertThat(product.sourceQuote().date()).isEqualTo("2026-07-21");
        assertThat(product.toProduct().sourceQuote()).isSameAs(product.sourceQuote());
    }

    /** doc_chunk_ref 가 비면 LEFT JOIN 이 NULL 을 준다 — 인용 객체 자체를 만들지 않는다. */
    @Test
    void chunkMissing_leavesQuoteNull_ratherThanEmptyText() throws SQLException {
        ResultSet rs = productRow();
        when(rs.getString("quote_text")).thenReturn(null);

        FundingProduct product = mapper.mapRow(rs, 0);

        assertThat(product.sourceQuote()).isNull();
        assertThat(product.name()).isEqualTo("청년고용연계자금");   // 상품은 목록에서 사라지지 않는다
    }

    /** 인용 유무와 무관하게 기존 NULL 의미론(무제약·확정이율 미상)은 그대로여야 한다. */
    @Test
    void quoteMapping_doesNotDisturbNullSemantics() throws SQLException {
        ResultSet rs = productRow();
        when(rs.getString("quote_text")).thenReturn("원문");

        FundingProduct product = mapper.mapRow(rs, 0);

        assertThat(product.eligibility().maxAge()).isEqualTo(39);
        assertThat(product.eligibility().industries()).isNull();   // NULL = 업종 무제약
        assertThat(product.rate()).isEqualTo(3.0);
        assertThat(product.termMonths()).isEqualTo(60);
    }

    /** 인용 컬럼을 제외한 나머지는 모든 케이스가 공유하는 기본 상품 1행. */
    private static ResultSet productRow() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("name")).thenReturn("청년고용연계자금");
        when(rs.getInt("max_age")).thenReturn(39);
        when(rs.getArray("industries")).thenReturn(null);
        when(rs.getArray("regions")).thenReturn(null);
        when(rs.getBoolean("pre_startup_only")).thenReturn(false);
        when(rs.getInt("amount_max")).thenReturn(7000);
        when(rs.getDouble("rate")).thenReturn(3.0);
        when(rs.getString("rate_type")).thenReturn(FundingProduct.RATE_FIXED);
        when(rs.getString("rate_note")).thenReturn(null);
        when(rs.getInt("term_months")).thenReturn(60);
        when(rs.getString("exclusive_group")).thenReturn(null);
        when(rs.getString("status")).thenReturn("open");
        when(rs.getString("data_as_of")).thenReturn("2026-07-21");
        when(rs.getString("source_org")).thenReturn("소진공");
        when(rs.getString("source_url")).thenReturn("https://www.semas.or.kr");
        when(rs.getString("source_collected")).thenReturn("2026-07-21");
        when(rs.wasNull()).thenReturn(false);
        return rs;
    }
}

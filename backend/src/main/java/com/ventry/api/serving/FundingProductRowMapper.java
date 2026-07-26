package com.ventry.api.serving;

import com.ventry.api.common.FinanceDtos.Source;
import com.ventry.api.common.FinanceDtos.SourceQuote;
import com.ventry.api.engine.Eligibility;
import com.ventry.api.engine.FundingProduct;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.jdbc.core.RowMapper;

/**
 * BE-03g — finance_product 1행 → {@link FundingProduct} 매핑.
 *
 * <p>NULL 의미론을 그대로 옮기는 것이 이 매퍼의 전부다 (스키마 주석 · assumptions #30):
 * <ul>
 *   <li>{@code max_age}·{@code industries}·{@code regions} NULL = <b>해당 축 무제약</b>.
 *       원시 {@code int} 로 받으면 0 으로 강등돼 "0세 이하"가 되므로 반드시 박싱 타입으로 읽는다.</li>
 *   <li>{@code rate} NULL = <b>확정 이율 미상</b>. 0.0 으로 강등되면 금리 0% 월 상환액이 되어
 *       부담률이 과소 산정되고 도달범위가 과대해진다 (이슈 #82, DECISIONS §13-1).</li>
 *   <li>{@code term_months} NULL = 상품 조건 미정 → 조달 검증이 보증 가정 T 를 부여한다
 *       (assumptions #22).</li>
 * </ul>
 */
public class FundingProductRowMapper implements RowMapper<FundingProduct> {

    @Override
    public FundingProduct mapRow(ResultSet rs, int rowNum) throws SQLException {
        Source source = new Source(
                rs.getString("source_org"), rs.getString("source_url"),
                rs.getString("source_collected"));

        Eligibility eligibility = new Eligibility(
                nullableInt(rs, "max_age"),
                textArray(rs, "industries"),
                textArray(rs, "regions"),
                rs.getBoolean("pre_startup_only"),
                rs.getBoolean("existing_business_only"));

        return new FundingProduct(
                rs.getString("name"), eligibility, rs.getInt("amount_max"),
                nullableDouble(rs, "rate"), rs.getString("rate_type"), rs.getString("rate_note"),
                nullableInt(rs, "term_months"), rs.getString("exclusive_group"),
                rs.getString("status"), rs.getString("data_as_of"), source, sourceQuote(rs));
    }

    /**
     * 원문 인용 (BE-06 ①). {@code doc_chunk_ref} 가 비었거나 가리키는 청크가 없으면 LEFT JOIN 이
     * NULL 을 주고, 그때는 <b>인용 객체 자체를 만들지 않는다</b> — 빈 문자열이나 상품 설명으로
     * 대체하면 그 순간 근거를 지어낸 것이 된다 (스펙 §5-4).
     *
     * <p>{@code text} 는 자르지 않는다. 화면 줄 수 제한은 표현 계층의 몫이다.
     */
    private static SourceQuote sourceQuote(ResultSet rs) throws SQLException {
        String text = rs.getString("quote_text");
        if (text == null) {
            return null;
        }
        return new SourceQuote(text, rs.getString("quote_org"),
                rs.getString("quote_doc"), rs.getString("quote_date"));
    }

    /** NULL 을 0 으로 강등하지 않는다 — "무제약"과 "0"은 다른 뜻이다. */
    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    /** {@code TEXT[]} → 집합. NULL·빈 배열 모두 "무제약"이라 null 로 통일한다. */
    private static Set<String> textArray(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return null;
        }
        String[] values = (String[]) array.getArray();
        if (values == null || values.length == 0) {
            return null;
        }
        return new LinkedHashSet<>(Arrays.asList(values));
    }
}

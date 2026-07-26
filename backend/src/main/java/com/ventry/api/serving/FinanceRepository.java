package com.ventry.api.serving;

import com.ventry.api.engine.FundingProduct;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * BE-03g — finance_product 조회 리포지토리 (read-only).
 *
 * <p>"탐색당 쿼리 1회" 원칙(expl §5): 상품 전량을 한 번의 쿼리로 조회해 인메모리 판정에 넘긴다.
 * 자격 필터를 SQL 로 내리지 않는 이유는 두 가지다 — 상품이 수십 건 규모라 이득이 없고,
 * 자격 판정은 {@code EligibilityFilter} 순수 함수로 두어야 단위 테스트로 증명할 수 있다.
 *
 * <p>{@code status='open'} 필터도 걸지 않는다. 마감 상품은 조달 검증({@code FundingCheck})이
 * 거르며, 자격 부합 목록에는 상태와 함께 노출될 수 있어야 하기 때문이다.
 *
 * <p><b>원문 인용도 같은 쿼리에서 가져온다</b> (BE-06 ①): {@code doc_chunk_ref} → {@code chunk_id}
 * <b>LEFT JOIN</b> 한 번이다. 유사도 검색·벡터DB는 도입하지 않았고(DECISIONS §7) 상품당 청크가
 * 1건이라 조인으로 행이 늘지 않는다. LEFT 인 이유는 청크가 없는 상품도 목록에서 사라지면 안 되기
 * 때문이며, 그 경우 인용은 null 이 된다 — 없는 근거를 지어내지 않는다.
 */
@Repository
public class FinanceRepository {

    private static final FundingProductRowMapper ROW_MAPPER = new FundingProductRowMapper();

    private static final String SELECT_ALL = """
            SELECT p.product_id, p.name, p.max_age, p.industries, p.regions,
                   p.pre_startup_only, p.existing_business_only, p.target_group,
                   p.amount_max, p.rate, p.rate_type, p.rate_note, p.term_months,
                   p.exclusive_group, p.status, p.data_as_of,
                   p.source_org, p.source_url, p.source_collected,
                   c.text              AS quote_text,
                   c.doc_meta ->> 'org'  AS quote_org,
                   c.doc_meta ->> 'doc'  AS quote_doc,
                   c.doc_meta ->> 'date' AS quote_date
            FROM finance_product p
            LEFT JOIN finance_doc_chunk c ON c.chunk_id = p.doc_chunk_ref
            ORDER BY p.amount_max DESC, p.product_id
            """;

    private final JdbcClient jdbcClient;

    public FinanceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** 상품 전량. 정렬은 계약 D8 의 목록 정렬(한도 내림차순·동점 시 이름)과 같은 순서로 둔다. */
    public List<FundingProduct> findAll() {
        return jdbcClient.sql(SELECT_ALL).query(ROW_MAPPER).list();
    }
}

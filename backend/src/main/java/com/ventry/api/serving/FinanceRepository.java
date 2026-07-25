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
 */
@Repository
public class FinanceRepository {

    private static final FundingProductRowMapper ROW_MAPPER = new FundingProductRowMapper();

    private static final String SELECT_ALL = """
            SELECT name, max_age, industries, regions, pre_startup_only,
                   amount_max, rate, rate_type, rate_note, term_months,
                   exclusive_group, status, data_as_of,
                   source_org, source_url, source_collected
            FROM finance_product
            ORDER BY amount_max DESC, name
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

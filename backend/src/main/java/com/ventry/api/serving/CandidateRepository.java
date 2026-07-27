package com.ventry.api.serving;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * BE-02 — v_candidate_area 조회 리포지토리 (read-only).
 * "탐색당 쿼리 1회" 원칙(expl §5): 업종별 후보를 한 번의 쿼리로 전량 조회해 인메모리 계산에 넘긴다.
 * 비용 합계·점수·부담률은 여기서 굽지 않는다 — engine(CostCalculator·ScoreLookup·ReverseCheck) 몫.
 */
@Repository
public class CandidateRepository {

    private static final CandidateRowMapper ROW_MAPPER = new CandidateRowMapper();

    // 뷰의 cost_ex/incl_premium_* 과 sigungu_name 은 조회하지 않는다 — 합계는 engine 이 만들고
    // 자치구는 조회 축이 아니다 (이슈 A·D, 근거는 CandidateRowMapper 주석).
    private static final String SELECT_BY_INDUSTRY = """
            SELECT area_code, name, lat, lng,
                   deposit_low, deposit_high, premium_low, premium_high,
                   interior_low, interior_high, monthly_fixed_cost,
                   w1, w2, w3, w4, w5,
                   monthly_rent, est_sales, daily_floating,
                   rent_org, rent_district, rent_fallback,
                   transit_station, transit_line, transit_distance_m,
                   transit_daily_riders, transit_fallback
            FROM v_candidate_area
            WHERE industry = ?
            """;

    private final JdbcClient jdbcClient;

    public CandidateRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 업종별 후보 상권 전량을 한 번의 쿼리로 조회한다.
     * 뷰 그레인이 area_code×industry 라 industry 필터가 필수다 (이슈 C).
     */
    public List<CandidateArea> findCandidates(String industry) {
        return jdbcClient.sql(SELECT_BY_INDUSTRY).param(industry).query(ROW_MAPPER).list();
    }
}

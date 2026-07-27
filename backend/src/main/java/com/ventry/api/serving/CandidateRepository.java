package com.ventry.api.serving;

import java.util.List;
import org.springframework.cache.annotation.Cacheable;
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
     *
     * <p><b>캐시를 여기에 두는 이유</b> — 이전에는 {@link DbCandidateSource#findCandidates} 에
     * 걸려 있었는데, 같은 클래스의 {@code find}(역방향 판정 단건 조회)는 이 리포지토리를 직접
     * 부르므로 <b>프록시를 타지 않아 캐시를 통째로 우회</b>했다. 지도 마커를 누를 때마다 업종
     * 전건(카페 1,059행)을 다시 읽던 경로다. 공급원이 둘(리스트·단건)이어도 조회는 하나이므로
     * 캐시도 하나여야 한다.
     *
     * <p>키는 업종 단독으로 완전하다 (가정 #82). {@code condition} 은 키가 null 일 때 Spring 이
     * {@code IllegalArgumentException} 을 던져 <b>입력 오류가 500으로 증폭</b>되는 것을 막는다.
     * 캐시 자체는 {@code CacheConfig} 가 db 프로파일 전용이라 픽스처 경로에서는 동작하지 않는다.
     */
    @Cacheable(cacheNames = "candidates", key = "#industry", condition = "#industry != null")
    public List<CandidateArea> findCandidates(String industry) {
        return jdbcClient.sql(SELECT_BY_INDUSTRY).param(industry).query(ROW_MAPPER).list();
    }
}

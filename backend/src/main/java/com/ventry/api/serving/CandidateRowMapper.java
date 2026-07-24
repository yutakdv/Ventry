package com.ventry.api.serving;

import com.ventry.api.engine.AxisScores;
import com.ventry.api.engine.CostBlocks;
import com.ventry.api.engine.Interval;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;

/**
 * BE-02 — v_candidate_area 1행 → {@link CandidateArea} 매핑.
 * 비용은 원재료 4블록(deposit/premium/interior/monthlyFixedCost)만 매핑하고,
 * 합계(cost_ex/incl_premium)·부담률·종합점수는 굽지 않는다 — engine(CostCalculator 등) 몫.
 */
public class CandidateRowMapper implements RowMapper<CandidateArea> {

    // TODO(BE-02): 뷰의 cost_ex/incl_premium_* 은 CostCalculator 재계산과 이중 경로 — 정합성 대조 후속 (이슈 A)
    // TODO(BE-02): sigungu_name 은 Caffeine 캐시 키(업종·자치구)용 — 캐시 단계에서 추가 (이슈 D)

    @Override
    public CandidateArea mapRow(ResultSet rs, int rowNum) throws SQLException {
        CostBlocks costBlocks = new CostBlocks(
                new Interval(rs.getInt("deposit_low"), rs.getInt("deposit_high")),
                new Interval(rs.getInt("premium_low"), rs.getInt("premium_high")),
                new Interval(rs.getInt("interior_low"), rs.getInt("interior_high")),
                rs.getInt("monthly_fixed_cost"));

        AxisScores axisScores = new AxisScores(
                rs.getDouble("w1"), rs.getDouble("w2"), rs.getDouble("w3"),
                rs.getDouble("w4"), rs.getDouble("w5"));

        // 이슈 B: transit 은 뷰에서 LEFT JOIN 이라 역 미매칭 상권은 전 컬럼이 NULL 이 된다.
        // fallback_flag 는 transit 테이블 유일의 NOT NULL 컬럼이라, 그 wasNull() 이 조인 실패의
        // 유일하게 견고한 시그널이다 (nearest_station·distance_m 은 원래 nullable → 판별 부적합).
        // 조인 실패면 접근성 성분 0 + fallback=true (스펙 §3-1 "유입 성분 0 처리").
        boolean fallbackFlag = rs.getBoolean("transit_fallback");
        boolean transitMissing = rs.wasNull();

        String transitStation;
        String transitLine;
        int transitDistanceM;
        int transitDailyRiders;
        boolean transitFallback;
        if (transitMissing) {
            transitStation = "";
            transitLine = "";
            transitDistanceM = 0;
            transitDailyRiders = 0;
            transitFallback = true;
        } else {
            transitStation = orEmpty(rs.getString("transit_station"));
            transitLine = orEmpty(rs.getString("transit_line"));
            transitDistanceM = rs.getInt("transit_distance_m");
            transitDailyRiders = rs.getInt("transit_daily_riders");
            transitFallback = fallbackFlag;
        }

        return new CandidateArea(
                rs.getString("area_code"), rs.getString("name"),
                rs.getDouble("lat"), rs.getDouble("lng"),
                costBlocks, axisScores,
                rs.getInt("monthly_rent"), rs.getInt("est_sales"), rs.getInt("daily_floating"),
                rs.getString("rent_org"), rs.getString("rent_district"), rs.getBoolean("rent_fallback"),
                transitStation, transitLine, transitDistanceM, transitDailyRiders, transitFallback);
    }

    private static String orEmpty(String value) {
        return value != null ? value : "";
    }
}

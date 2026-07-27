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

    // 이슈 A (2026-07-27 대조 완료): 뷰의 cost_ex/incl_premium_* 은 AI 배치가 구운 값이고 BE 는
    // 같은 값을 CostCalculator 로 다시 만든다 — 두 경로가 존재하는 것 자체는 설계다(뷰 컬럼은
    // 적재 검증·SQL 탐색용, 서빙 숫자는 engine 이 만든다). 갈릴 수 있다는 것이 위험이라 배포
    // 덤프 전건을 대조했고 **3,300행 불일치 0건**이다. 산식 수준은
    // ai/tests/test_cost.py::test_engine_cost_composition_matches 가 RESERVE_MONTHS=6 까지
    // 이 클래스가 넘기는 엔진 상수와 맞춰 고정한다 (근거·재현 SQL: docs/assumptions.md #81).
    // 그래서 이 매퍼는 합계 컬럼을 **읽지 않는다** — 읽으면 어느 쪽이 진실인지가 다시 흐려진다.
    //
    // 이슈 D (해소): sigungu_name 은 캐시 키(업종·자치구)용으로 예약했던 것인데, region_hint 의
    // 사정거리가 「지역 한정 상품의 자격 판정 전용」으로 확정돼(#136) 후보 조회는 업종으로만 한다.
    // 자치구가 조회 축이 아니므로 키에 넣을 이유가 없다 — DbCandidateSource 주석 참조.

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

package com.ventry.api.serving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ventry.api.engine.Interval;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/**
 * BE-02 — CandidateRowMapper 단위 테스트. 실 DB 없이 mock ResultSet 으로 검증한다
 * (Testcontainers 등 실 DB 통합 테스트는 후속). 경계 케이스 = transit LEFT JOIN 실패.
 */
class CandidateRowMapperTest {

    private final CandidateRowMapper mapper = new CandidateRowMapper();

    @Test
    void mapsRow_withTransit() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("area_code")).thenReturn("A-1101");
        when(rs.getString("name")).thenReturn("망원역 상권");
        when(rs.getDouble("lat")).thenReturn(37.5556);
        when(rs.getDouble("lng")).thenReturn(126.9106);
        when(rs.getInt("deposit_low")).thenReturn(2000);
        when(rs.getInt("deposit_high")).thenReturn(2500);
        when(rs.getInt("premium_low")).thenReturn(1400);
        when(rs.getInt("premium_high")).thenReturn(1700);
        when(rs.getInt("interior_low")).thenReturn(2500);
        when(rs.getInt("interior_high")).thenReturn(3000);
        when(rs.getInt("monthly_fixed_cost")).thenReturn(200);
        when(rs.getDouble("w1")).thenReturn(0.82);
        when(rs.getDouble("w2")).thenReturn(0.74);
        when(rs.getDouble("w3")).thenReturn(0.68);
        when(rs.getDouble("w4")).thenReturn(0.71);
        when(rs.getDouble("w5")).thenReturn(0.77);
        when(rs.getInt("monthly_rent")).thenReturn(198);
        when(rs.getInt("est_sales")).thenReturn(1800);
        when(rs.getInt("daily_floating")).thenReturn(24500);
        when(rs.getString("rent_org")).thenReturn("REB");
        when(rs.getString("rent_district")).thenReturn("홍대합정상권");
        when(rs.getBoolean("rent_fallback")).thenReturn(false);
        when(rs.getBoolean("transit_fallback")).thenReturn(false);
        when(rs.wasNull()).thenReturn(false);   // transit 조인 성공
        when(rs.getString("transit_station")).thenReturn("망원");
        when(rs.getString("transit_line")).thenReturn("6");
        when(rs.getInt("transit_distance_m")).thenReturn(320);
        when(rs.getInt("transit_daily_riders")).thenReturn(21000);

        CandidateArea area = mapper.mapRow(rs, 0);

        assertThat(area.areaCode()).isEqualTo("A-1101");
        assertThat(area.name()).isEqualTo("망원역 상권");
        assertThat(area.lat()).isEqualTo(37.5556);
        assertThat(area.lng()).isEqualTo(126.9106);
        // 비용은 원재료 4블록만 매핑 (합계는 engine 몫)
        assertThat(area.costBlocks().deposit()).isEqualTo(new Interval(2000, 2500));
        assertThat(area.costBlocks().premium()).isEqualTo(new Interval(1400, 1700));
        assertThat(area.costBlocks().interior()).isEqualTo(new Interval(2500, 3000));
        assertThat(area.costBlocks().monthlyFixedCost()).isEqualTo(200);
        assertThat(area.axisScores().w1()).isEqualTo(0.82);
        assertThat(area.axisScores().w5()).isEqualTo(0.77);
        assertThat(area.monthlyRent()).isEqualTo(198);
        assertThat(area.estSales()).isEqualTo(1800);
        assertThat(area.dailyFloating()).isEqualTo(24500);
        assertThat(area.rentOrg()).isEqualTo("REB");
        assertThat(area.rentDistrict()).isEqualTo("홍대합정상권");
        assertThat(area.rentFallback()).isFalse();
        assertThat(area.transitStation()).isEqualTo("망원");
        assertThat(area.transitLine()).isEqualTo("6");
        assertThat(area.transitDistanceM()).isEqualTo(320);
        assertThat(area.transitDailyRiders()).isEqualTo(21000);
        assertThat(area.transitFallback()).isFalse();
        // 원재료 매핑이 정확하면 파생 부담률도 정확 (198/1800)
        assertThat(area.burdenRatio()).isEqualTo(198.0 / 1800);
    }

    @Test
    void mapsRow_transitMissing_setsFallbackTrue() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("area_code")).thenReturn("A-2202");
        when(rs.getString("name")).thenReturn("역 없는 상권");
        when(rs.getDouble("lat")).thenReturn(37.5);
        when(rs.getDouble("lng")).thenReturn(127.0);
        when(rs.getInt("deposit_low")).thenReturn(1000);
        when(rs.getInt("deposit_high")).thenReturn(1200);
        when(rs.getInt("interior_low")).thenReturn(1500);
        when(rs.getInt("interior_high")).thenReturn(1800);
        when(rs.getInt("monthly_fixed_cost")).thenReturn(150);
        when(rs.getInt("monthly_rent")).thenReturn(120);
        when(rs.getInt("est_sales")).thenReturn(1000);
        when(rs.getInt("daily_floating")).thenReturn(8000);
        when(rs.getString("rent_org")).thenReturn("REB");
        when(rs.getString("rent_district")).thenReturn("자치구평균");
        when(rs.getBoolean("rent_fallback")).thenReturn(true);
        // 이슈 B: transit LEFT JOIN 실패 → fallback_flag 컬럼이 NULL → wasNull()=true
        when(rs.getBoolean("transit_fallback")).thenReturn(false);
        when(rs.wasNull()).thenReturn(true);

        CandidateArea area = mapper.mapRow(rs, 0);

        // 접근성 성분 0 + fallback=true 로 처리되어야 한다
        assertThat(area.transitFallback()).isTrue();
        assertThat(area.transitDistanceM()).isZero();
        assertThat(area.transitDailyRiders()).isZero();
        assertThat(area.transitStation()).isEmpty();
        assertThat(area.transitLine()).isEmpty();
        // 나머지 필드는 정상 매핑 유지
        assertThat(area.areaCode()).isEqualTo("A-2202");
        assertThat(area.rentFallback()).isTrue();
        assertThat(area.monthlyRent()).isEqualTo(120);
    }
}

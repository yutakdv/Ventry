package com.ventry.api.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** BE-03c ScoreLookup — 교통 감쇠·백분위 정규화·w1 결합·가중 종합점수 (스펙 §4-3). */
class ScoreLookupTest {

    @Test
    void transitInflux_atStation_equalsDailyRiders() {
        // 거리 0 → exp(0)=1 → 승하차 그대로
        assertThat(ScoreLookup.transitInflux(1000, 0)).isEqualTo(1000.0);
    }

    @Test
    void transitInflux_decaysExponentiallyWith500mScale() {
        // 500m → exp(-1) = 0.367879...
        assertThat(ScoreLookup.transitInflux(1000, 500)).isCloseTo(367.879441, within(1e-3));
    }

    @Test
    void percentile_isFractionAtOrBelowValue() {
        double[] seoul = {1, 2, 3, 4};
        assertThat(ScoreLookup.percentile(3, seoul)).isEqualTo(0.75);
    }

    @Test
    void percentile_topValueIsOne_belowAllIsZero() {
        double[] seoul = {10, 20, 30, 40};
        assertThat(ScoreLookup.percentile(40, seoul)).isEqualTo(1.0);
        assertThat(ScoreLookup.percentile(5, seoul)).isEqualTo(0.0);
    }

    @Test
    void demandScore_isEqualMeanOfThreeComponentPercentiles() {
        // w1 수요 = 길단위 유동 + 배후 + 교통유입 (서울 백분위 후 균등 결합)
        assertThat(ScoreLookup.demandScore(0.9, 0.6, 0.3)).isCloseTo(0.6, within(1e-9));
    }

    @Test
    void score_isWeightedSumOfFiveAxes() {
        AxisScores axes = new AxisScores(0.8, 0.6, 0.5, 0.4, 0.7);
        Weights weights = new Weights(0.3, 0.2, 0.2, 0.15, 0.15);
        // 0.24 + 0.12 + 0.10 + 0.06 + 0.105 = 0.625
        assertThat(ScoreLookup.score(axes, weights)).isCloseTo(0.625, within(1e-9));
    }
}

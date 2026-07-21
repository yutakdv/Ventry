package com.ventry.api.engine;

/**
 * BE-03c — 점수 조회·결합 (순수 함수, 스펙 §4-3). 서빙 경로 ML 없음.
 * 정규화(서울 전체 백분위)·교통 감쇠·가중 합만 담당하며 원자재 수치는 배치가 사전 계산.
 */
public final class ScoreLookup {

    /** 교통 유입 감쇠 스케일(m) — 승하차 × exp(−거리/DECAY). */
    static final double TRANSIT_DECAY_M = 500.0;

    private ScoreLookup() {}

    /** 최근접 역 대중교통 유입 = 일평균 승하차 × exp(−거리/500m). */
    public static double transitInflux(int dailyRiders, int distanceM) {
        return dailyRiders * Math.exp(-distanceM / TRANSIT_DECAY_M);
    }

    /** 서울 전체 백분위 = population 중 value 이하 비율 [0,1]. */
    public static double percentile(double value, double[] population) {
        long atOrBelow = 0;
        for (double p : population) {
            if (p <= value) {
                atOrBelow++;
            }
        }
        return (double) atOrBelow / population.length;
    }

    /** w1 수요 = 3성분 백분위의 균등 결합(평균). */
    public static double demandScore(double pedestrianPct, double backingPct, double transitPct) {
        return (pedestrianPct + backingPct + transitPct) / 3.0;
    }

    /** 종합 점수 = Σ wᵢ·축ᵢ. */
    public static double score(AxisScores axes, Weights weights) {
        return axes.w1() * weights.w1() + axes.w2() * weights.w2() + axes.w3() * weights.w3()
                + axes.w4() * weights.w4() + axes.w5() * weights.w5();
    }
}

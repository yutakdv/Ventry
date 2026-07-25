package com.ventry.api.serving;

import com.ventry.api.engine.AxisScores;
import com.ventry.api.engine.CostBlocks;
import com.ventry.api.engine.CostCalculator;
import com.ventry.api.engine.ReverseCheck;

/**
 * 서빙용 후보 상권 원자재 — 결정적 도구 계층(engine)의 입력.
 * BE-02에서 사전 적재 테이블 조회로 대체되며, 현재는 DemoCandidates 픽스처가 공급한다.
 * costBlocks·axisScores·부담률 분모·분자는 배치 산출물(AI-05), 나머지는 표시용 통과 데이터.
 *
 * @param monthlyRent   환산임대료(만원/월) — 부담률 분자이자 화면 표기값
 * @param estSales      월 추정매출(만원) — 부담률 분모이자 화면 표기값
 * @param dailyFloating 일평균 유동인구(명) — 정규화 전 원값, 화면 표기용
 */
public record CandidateArea(
        String areaCode, String name, double lat, double lng,
        CostBlocks costBlocks, AxisScores axisScores,
        int monthlyRent, int estSales, int dailyFloating,
        String rentOrg, String rentDistrict, boolean rentFallback,
        String transitStation, String transitLine, int transitDistanceM,
        int transitDailyRiders, boolean transitFallback) {

    /** 부담률 = 환산임대료 ÷ 추정매출 (스펙 §4-2) — 픽스처 상수가 아닌 파생값. */
    public double burdenRatio() {
        return ReverseCheck.burdenRatio(monthlyRent, estSales);
    }

    /** 진입 비교 기준 c_a = 권리금 포함 비용 중앙값 (스펙 §4-1 · expl §2-1). */
    public int inclusiveCostMedian() {
        return (int) Math.ceil(CostCalculator.estimate(costBlocks).inclPremium().median());
    }

    /** 무권리 기준 c'_a = 권리금 제외 비용 중앙값 — A4 축·T5 인사이트의 기준선. */
    public int exPremiumCostMedian() {
        return (int) Math.ceil(CostCalculator.estimate(costBlocks).exPremium().median());
    }
}

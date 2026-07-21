package com.ventry.api.serving;

import com.ventry.api.engine.AxisScores;
import com.ventry.api.engine.CostBlocks;

/**
 * 서빙용 후보 상권 원자재 — 결정적 도구 계층(engine)의 입력.
 * BE-02에서 사전 적재 테이블 조회로 대체되며, 현재는 DemoCandidates 픽스처가 공급한다.
 * costBlocks·axisScores는 배치 산출물, 나머지는 표시용 통과 데이터.
 */
public record CandidateArea(
        String areaCode, String name, double lat, double lng,
        CostBlocks costBlocks, AxisScores axisScores, double burdenRatio,
        String rentOrg, String rentDistrict, boolean rentFallback,
        String transitStation, String transitLine, int transitDistanceM,
        int transitDailyRiders, boolean transitFallback) {}

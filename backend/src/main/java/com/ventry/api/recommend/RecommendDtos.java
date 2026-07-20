package com.ventry.api.recommend;

import com.ventry.api.common.FinanceDtos.RiskReview;
import com.ventry.api.common.Verdict;
import java.util.List;

/** GET /api/recommend/{sid} DTO (계약 4번). 좌표 WGS84, 금액 만원 단위, 비용은 구간. */
public final class RecommendDtos {

    private RecommendDtos() {}

    public record RecommendResponse(String dataAsOf, List<Area> areas, RiskReview riskReview) {}

    public record Area(String areaCode, String name, double lat, double lng,
                       Verdict verdict, Breakdown breakdown, Cost cost, double burdenRatio,
                       String reasonText, RentSource rentSource, Transit transit) {}

    public record Breakdown(double w1, double w2, double w3, double w4, double w5) {}

    /** 구간 [하한, 상한] — 점추정 금지 (스펙 §4-1). */
    public record Cost(List<Integer> exPremium, List<Integer> inclPremium) {}

    public record RentSource(String org, String district, boolean fallback) {}

    public record Transit(String station, String line, int distanceM,
                          int dailyRiders, boolean fallback) {}
}

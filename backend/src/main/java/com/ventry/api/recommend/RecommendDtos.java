package com.ventry.api.recommend;

import com.ventry.api.common.FinanceDtos.RiskReview;
import com.ventry.api.common.Verdict;
import java.util.List;

/** GET /api/recommend/{sid} DTO (계약 4번). 좌표 WGS84, 금액 만원 단위, 비용은 구간. */
public final class RecommendDtos {

    private RecommendDtos() {}

    /**
     * @param totalCount 필터 통과 후보 총수. 현재는 페이징이 없어 areas 길이와 같다
     * @param summary    후보군 집계 — 목록 헤더·요약 밴드용
     */
    public record RecommendResponse(String dataAsOf, int totalCount, Summary summary,
                                    List<Area> areas, RiskReview riskReview) {}

    /** 후보군 평균 (만원). 정렬·필터를 프론트가 하므로 서버는 전체 집계만 준다. */
    public record Summary(int avgRent, int avgSales) {}

    /**
     * @param score         종합점수 0~100 = round(Σ wᵢ·축ᵢ × 100). 등급 구간 매핑은 프론트 소관
     * @param monthlyRent   환산임대료(만원/월) — 부담률 분자
     * @param estSales      월 추정매출(만원) — 부담률 분모
     * @param dailyFloating 일평균 유동인구(명)
     * @param burdenRatio   monthlyRent ÷ estSales (스펙 §4-2)
     */
    public record Area(String areaCode, String name, double lat, double lng,
                       Verdict verdict, int score, Breakdown breakdown, Cost cost,
                       int monthlyRent, int estSales, int dailyFloating, double burdenRatio,
                       String reasonText, RentSource rentSource, Transit transit) {}

    public record Breakdown(double w1, double w2, double w3, double w4, double w5) {}

    /** 구간 [하한, 상한] — 점추정 금지 (스펙 §4-1). */
    public record Cost(List<Integer> exPremium, List<Integer> inclPremium) {}

    public record RentSource(String org, String district, boolean fallback) {}

    public record Transit(String station, String line, int distanceM,
                          int dailyRiders, boolean fallback) {}
}

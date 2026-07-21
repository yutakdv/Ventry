package com.ventry.api.serving;

import com.ventry.api.checkarea.CheckAreaDtos.CheckAreaResponse;
import com.ventry.api.common.ApiException;
import com.ventry.api.common.FinanceDtos.Product;
import com.ventry.api.common.MockData;
import com.ventry.api.engine.CostCalculator;
import com.ventry.api.engine.CostEstimate;
import com.ventry.api.engine.EligibilityFilter;
import com.ventry.api.engine.Profile;
import com.ventry.api.engine.ReverseCheck;
import com.ventry.api.engine.ReverseResult;
import com.ventry.api.engine.ScoreLookup;
import com.ventry.api.engine.Weights;
import com.ventry.api.recommend.RecommendDtos.Area;
import com.ventry.api.recommend.RecommendDtos.Breakdown;
import com.ventry.api.recommend.RecommendDtos.Cost;
import com.ventry.api.recommend.RecommendDtos.RecommendResponse;
import com.ventry.api.recommend.RecommendDtos.RentSource;
import com.ventry.api.recommend.RecommendDtos.Transit;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * BE-03f — 입지 추천·역방향 판정 오케스트레이션. 결정적 도구 계층(engine)의 첫 소비자.
 * 이중 필터 → 점수 → 판정 4단계 → reason_text 템플릿. LLM 의존성 0.
 */
@Service
public class LocationService {

    /** 업종 프리셋 가중치 (화면 공개, assumptions.md). 현재 카페·음식점 공용. */
    private static final Weights DEFAULT_WEIGHTS = new Weights(0.30, 0.20, 0.20, 0.15, 0.15);

    private final DemoCandidates candidates;
    private final DemoProducts products;

    public LocationService(DemoCandidates candidates, DemoProducts products) {
        this.candidates = candidates;
        this.products = products;
    }

    /** 화면 3 입지 추천: 후보 풀 → 점수 정렬 → 판정 + 근거문. */
    public RecommendResponse recommend(Profile profile, int budget) {
        Weights weights = DEFAULT_WEIGHTS;   // 업종 프리셋 (현재 카페·음식점 공용, assumptions.md)
        List<Area> areas = candidates.recommendPool().stream()
                .sorted(Comparator.comparingDouble(
                        (CandidateArea c) -> ScoreLookup.score(c.axisScores(), weights)).reversed())
                .map(c -> toArea(c, budget, weights))
                .toList();
        return new RecommendResponse(MockData.DATA_AS_OF, areas, ReasonTemplate.recommendReview());
    }

    /** 역방향 판정: 임의 클릭 상권 → 판정 4단계 + 부족분 + 자격 부합 상품. */
    public CheckAreaResponse checkArea(Profile profile, int budget, String areaCode) {
        CandidateArea area = candidates.find(areaCode)
                .orElseThrow(() -> ApiException.areaNotFound(areaCode));
        CostEstimate cost = CostCalculator.estimate(area.costBlocks());
        ReverseResult result = ReverseCheck.evaluate(budget, cost, area.burdenRatio(),
                ReverseCheck.DEFAULT_THETA);
        List<Product> matching = EligibilityFilter.qualify(profile, products.all()).stream()
                .map(fp -> new Product(fp.name(), fp.source(), null))   // source_quote=RAG(P1), 구현 전 null
                .toList();
        return new CheckAreaResponse(result.verdict(), result.gapAmount(), matching,
                ReasonTemplate.checkAreaReview());
    }

    private Area toArea(CandidateArea c, int budget, Weights weights) {
        CostEstimate cost = CostCalculator.estimate(c.costBlocks());
        ReverseResult result = ReverseCheck.evaluate(budget, cost, c.burdenRatio(),
                ReverseCheck.DEFAULT_THETA);
        return new Area(c.areaCode(), c.name(), c.lat(), c.lng(), result.verdict(),
                breakdown(c), toCost(cost), c.burdenRatio(),
                ReasonTemplate.reason(c.name(), result.verdict(), c.burdenRatio()),
                new RentSource(c.rentOrg(), c.rentDistrict(), c.rentFallback()),
                new Transit(c.transitStation(), c.transitLine(), c.transitDistanceM(),
                        c.transitDailyRiders(), c.transitFallback()));
    }

    private static Breakdown breakdown(CandidateArea c) {
        var a = c.axisScores();
        return new Breakdown(a.w1(), a.w2(), a.w3(), a.w4(), a.w5());
    }

    private static Cost toCost(CostEstimate cost) {
        return new Cost(List.of(cost.exPremium().low(), cost.exPremium().high()),
                List.of(cost.inclPremium().low(), cost.inclPremium().high()));
    }
}

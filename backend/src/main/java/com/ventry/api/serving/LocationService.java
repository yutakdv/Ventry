package com.ventry.api.serving;

import com.ventry.api.checkarea.CheckAreaDtos.CheckAreaResponse;
import com.ventry.api.common.ApiException;
import com.ventry.api.common.FinanceDtos.Product;
import com.ventry.api.engine.CostCalculator;
import com.ventry.api.engine.CostEstimate;
import com.ventry.api.engine.EligibilityFilter;
import com.ventry.api.engine.Frontier;
import com.ventry.api.engine.FundingProduct;
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
import com.ventry.api.recommend.RecommendDtos.Summary;
import com.ventry.api.recommend.RecommendDtos.Transit;
import com.ventry.api.scenario.ScenarioDtos.BudgetPreview;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * BE-03f — 입지 추천·역방향 판정 오케스트레이션. 결정적 도구 계층(engine)의 첫 소비자.
 * 이중 필터 → 점수 → 판정 4단계 → reason_text 템플릿.
 *
 * <p><b>판정과 수치에는 LLM 의존성이 0이다.</b> {@link RiskReviewAgent} 가 붙은 뒤에도 그렇다 —
 * 검증 에이전트는 이미 확정된 결과를 <b>반박하는 문장</b>만 만들고 판정을 바꾸지 않는다(§5-3).
 * 그 호출이 실패하면 템플릿 문장 + {@code skipped=true} 로 떨어질 뿐 응답의 나머지는 동일하다.
 */
@Service
public class LocationService {

    /**
     * 업종 프리셋 가중치 (화면 공개, assumptions.md). 현재 카페·음식점 공용.
     *
     * <p>⚠️ 이 값은 평가 하네스가 <b>사본으로 미러링</b>한다 —
     * {@code ai/eval/suites/sensitivity.py} 의 {@code WEIGHTS}. 언어 경계라 공유할 수 없으므로,
     * 바꿀 때는 그쪽도 함께 고쳐야 한다. 한쪽만 바뀌면 민감도 지표(부록 1)가 실서빙과 다른
     * 가중치를 재게 되며, 그 어긋남은 {@code ai/tests/test_serving_constants_sync.py} 가 잡는다.
     */
    static final Weights DEFAULT_WEIGHTS = new Weights(0.30, 0.20, 0.20, 0.15, 0.15);

    private final CandidateSource candidates;
    private final ProductSource products;
    private final DataMetaSource meta;
    private final RiskReviewAgent riskReview;

    public LocationService(CandidateSource candidates, ProductSource products, DataMetaSource meta,
                           RiskReviewAgent riskReview) {
        this.candidates = candidates;
        this.products = products;
        this.meta = meta;
        this.riskReview = riskReview;
    }

    /** 화면 3 입지 추천: 후보 풀 → 점수 정렬 → 판정 + 근거문. */
    public RecommendResponse recommend(Profile profile, int budget) {
        Weights weights = DEFAULT_WEIGHTS;   // 업종 프리셋 (현재 카페·음식점 공용, assumptions.md)
        List<CandidateArea> pool = candidates.findCandidates(profile.industry());
        List<Area> areas = pool.stream()
                .sorted(Comparator.comparingDouble(
                        (CandidateArea c) -> ScoreLookup.score(c.axisScores(), weights)).reversed())
                .map(c -> toArea(c, budget, weights))
                .toList();
        return new RecommendResponse(meta.asOf("sales"), areas.size(), summary(pool), areas,
                riskReview.forRecommend(profile.industry(), areas));
    }

    /**
     * 화면 2 예산 확정 프리뷰: 확정 예산으로 진입하는 후보 수와 그 후보군의 임대료·유동인구 범위.
     * 진입 판정은 recommend와 동일한 기준(권리금 포함 비용 중앙값 ≤ 예산, expl §2-1)을 쓴다.
     */
    public BudgetPreview preview(String industry, int budget) {
        List<CandidateArea> pool = candidates.findCandidates(industry);
        int[] costs = pool.stream().mapToInt(CandidateArea::inclusiveCostMedian).toArray();
        int areaCount = Frontier.nEntry(costs, budget);   // 개수는 도구 계층이 계산 (스펙 §5-1)
        if (areaCount == 0) {
            return new BudgetPreview(0, null, null);
        }
        List<CandidateArea> entered = pool.stream()
                .filter(c -> c.inclusiveCostMedian() <= budget).toList();
        return new BudgetPreview(areaCount,
                range(entered, CandidateArea::monthlyRent),
                range(entered, CandidateArea::dailyFloating));
    }

    /**
     * 프리뷰 수치의 데이터 기준일 — {@code /recommend} 와 <b>같은 원천</b>을 쓴다.
     * 화면 3만 기준일 표기 원천이 없어 불변 원칙 4를 지키지 못했다 (이슈 #104 ④ · BE 리뷰 D-25).
     */
    public String previewAsOf() {
        return meta.asOf("sales");
    }

    /** 역방향 판정: 임의 클릭 상권 → 판정 4단계 + 부족분 + 자격 부합 상품. */
    public CheckAreaResponse checkArea(Profile profile, int budget, String areaCode) {
        CandidateArea area = candidates.find(profile.industry(), areaCode)
                .orElseThrow(() -> ApiException.areaNotFound(areaCode));
        CostEstimate cost = CostCalculator.estimate(area.costBlocks());
        ReverseResult result = ReverseCheck.evaluate(budget, cost, area.burdenRatio(),
                ReverseCheck.DEFAULT_THETA);
        // 계약 §6·D8: matching_products는 amount_max 내림차순, 동점 시 **product_id 오름차순**.
        // 정렬을 FundingProduct 단계에서 끝내는 이유 — Product(응답 DTO)에는 product_id 가 없다.
        // 계약이 요구한 것은 순서이지 노출이 아니므로 ID 를 응답에 싣지 않고 순서만 지킨다
        // (BE 리뷰 D-13). 금리 정렬은 하지 않는다 — 변동금리 상품의 순위를 임의로 정하지 않는다.
        List<Product> matching = EligibilityFilter.qualify(profile, products.all()).stream()
                .sorted(Comparator.comparingInt(FundingProduct::amountMax).reversed()
                        .thenComparing(FundingProduct::sortKey))
                .map(FundingProduct::toProduct)   // rate_type은 항상, source_quote는 청크 보유 시 실림
                .toList();
        return new CheckAreaResponse(result.verdict(), result.gapAmount(), matching,
                riskReview.forCheckArea(area.name(), result.verdict(), result.gapAmount(),
                        area.burdenRatio()));
    }

    private Area toArea(CandidateArea c, int budget, Weights weights) {
        CostEstimate cost = CostCalculator.estimate(c.costBlocks());
        double burdenRatio = c.burdenRatio();
        ReverseResult result = ReverseCheck.evaluate(budget, cost, burdenRatio,
                ReverseCheck.DEFAULT_THETA);
        return new Area(c.areaCode(), c.name(), c.lat(), c.lng(), result.verdict(),
                score(c, weights), breakdown(c), toCost(cost),
                c.monthlyRent(), c.estSales(), c.dailyFloating(), serializableRatio(burdenRatio),
                ReasonTemplate.reason(c.name(), result.verdict(), burdenRatio),
                new RentSource(c.rentOrg(), c.rentDistrict(), c.rentFallback()),
                new Transit(c.transitStation(), c.transitLine(), c.transitDistanceM(),
                        c.transitDailyRiders(), c.transitFallback()));
    }

    /**
     * 계약 타입을 지키는 마지막 관문 — 비유한값은 <b>필드 생략</b>으로 내보낸다 (BE 리뷰 D-04).
     * 추정매출이 결측(0)인 상권에서 {@code Infinity} 가 문자열로 직렬화되던 경로를 여기서 끊는다.
     * 데이터가 고쳐져도(가정 #70) 이 가드는 계약 타입 보증으로 남는다.
     */
    private static Double serializableRatio(double value) {
        return Double.isFinite(value) ? value : null;
    }

    /** 화면 노출 점수 = 가중 합[0,1]을 0~100 정수로 (assumptions.md #8). */
    private static int score(CandidateArea c, Weights weights) {
        return (int) Math.round(ScoreLookup.score(c.axisScores(), weights) * 100);
    }

    private static Summary summary(List<CandidateArea> pool) {
        return new Summary(average(pool, CandidateArea::monthlyRent),
                average(pool, CandidateArea::estSales));
    }

    private static int average(List<CandidateArea> areas, java.util.function.ToIntFunction<CandidateArea> field) {
        return (int) Math.round(areas.stream().mapToInt(field).average().orElse(0));
    }

    private static List<Integer> range(List<CandidateArea> areas,
                                       java.util.function.ToIntFunction<CandidateArea> field) {
        var stats = areas.stream().mapToInt(field).summaryStatistics();
        return List.of(stats.getMin(), stats.getMax());
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

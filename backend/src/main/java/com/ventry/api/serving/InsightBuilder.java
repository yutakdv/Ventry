package com.ventry.api.serving;

import com.ventry.api.common.Verdict;
import com.ventry.api.engine.BoundaryEval;
import com.ventry.api.engine.CostCalculator;
import com.ventry.api.engine.CoverOutcome;
import com.ventry.api.engine.EligibilityFilter;
import com.ventry.api.engine.Frontier;
import com.ventry.api.engine.FundingCheck;
import com.ventry.api.engine.FundingInput;
import com.ventry.api.engine.FundingPlan;
import com.ventry.api.engine.FundingProduct;
import com.ventry.api.engine.InsightScore;
import com.ventry.api.engine.Profile;
import com.ventry.api.engine.ReverseCheck;
import com.ventry.api.engine.SustainFilter;
import com.ventry.api.engine.SustainInput;
import com.ventry.api.explore.ExploreDtos.Delta;
import com.ventry.api.explore.ExploreDtos.Funding;
import com.ventry.api.explore.ExploreDtos.InsightEvent;
import com.ventry.api.scenario.ScenarioDtos.CompositionItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.springframework.stereotype.Service;

/**
 * BE-05 — 결정공간 탐색 인사이트 <b>조립</b> (exploration spec §2·§3).
 *
 * <p><b>여기에 새 계산식은 없다.</b> 경계는 {@link Frontier}, 지속 후보는 {@link SustainFilter},
 * 조달 가능성은 {@link FundingCheck}, 점수는 {@link InsightScore}·{@link com.ventry.api.engine.ScoreLookup},
 * 판정은 {@link ReverseCheck}, 비용은 {@link com.ventry.api.engine.CostCalculator}가 만든다.
 * 이 클래스가 하는 일은 그 결과를 계약 DTO로 엮고 문장을 조립하는 것뿐이다.
 *
 * <p><b>상향 단독 노출 금지를 코드로 강제한다</b> (스펙 §7 · CLAUDE.md §0-4):
 * 하향 안전 마진(T2)이 성립하지 않는 상황에서는 T1을 <b>생성하지 않는다</b>. 지속 후보 수와
 * 고지 플래그는 T1 생성 시 항상 함께 실린다 — 셋 중 하나라도 빠진 T1은 만들어질 수 없다.
 */
@Service
public class InsightBuilder {

    /** 헤드라인 말미의 자격 한정 문구. 자문성·보장성 술어를 쓰지 않는다 (용어 컴플라이언스 §7). */
    private static final String QUALIFICATION_TAIL =
            "자격 요건 부합 여부만 확인된 것이며, 실제 한도와 심사 결과는 해당 기관이 정합니다.";

    /**
     * 변동금리 근거일 때 {@code marginal_payment} 자리를 대신하는 문구 (계약 D8 ③).
     * <b>문구는 서버가 단일 통제한다</b> — FE 가 하드코딩 사전을 두면 같은 상황에 두 문장이 생긴다.
     */
    static final String VARIABLE_PAYMENT_NOTE =
            "근거 상품이 분기별 변동금리라 월 상환액을 확정 금액으로 표기하지 않습니다. "
                    + "지속 안정 후보 수는 현 분기 고시 금리를 적용해 산출했으며, "
                    + "금리 조건은 상품 원문을 확인하세요.";

    /** 0건 보고 문장 — 스펙 §2-4가 규정한 정상 출력이다. */
    static final String NO_INSIGHT_REASON =
            "인접 시나리오를 전 구간 검토했으나 현 조건 대비 유의미한 대안이 없습니다. "
                    + "현재 예산은 안정 구간입니다.";

    /**
     * 0건 사유의 <b>다른 갈래</b> — 진입 후보가 아예 없는 상태 (BE 리뷰 D-07).
     *
     * <p>스펙 §2-4가 규정한 0건 문장은 "전 경계가 임계 미달"용인데, 한 갈래로 합쳐 두면
     * 진입 가능 후보가 0곳인 구간에서도 "현재 예산은 안정 구간입니다"로 안심시키게 된다 —
     * 사용자에게 정반대의 판단을 유도한다.
     */
    static final String NO_ENTRY_REASON =
            "현재 예산으로 진입 가능한 후보가 없습니다. 예산 범위를 넓히거나 무권리 매물 조건을 "
                    + "함께 검토하면 진입 가능 구간이 나타납니다.";

    private final CandidateSource candidates;
    private final ProductSource products;
    private final FrontierService frontier;

    public InsightBuilder(CandidateSource candidates, ProductSource products,
                          FrontierService frontier) {
        this.candidates = candidates;
        this.products = products;
        this.frontier = frontier;
    }

    /**
     * @param insights          보고 인사이트 (최대 3건 = score 상위 2 + T2 안전 마진 1)
     * @param scenariosExplored 평가한 경계 개수 — 보고 건수가 아니다 (assumptions #29)
     * @param emptyReason       0건일 때의 사유 문장, 있으면 null
     */
    public record Result(List<InsightEvent> insights, int scenariosExplored, String emptyReason) {}

    /** B₀ 시점의 기준값 묶음 — 모든 경계가 같은 기준선과 비교되도록 한 번만 계산한다. */
    private record Baseline(int nEntry, int nGreen, int nSustain, int topScore) {}

    public Result build(Profile profile, int budget, FundingInput funding,
                        List<CompositionItem> composition) {
        String industry = profile.industry();
        List<CandidateArea> pool = candidates.findCandidates(industry);
        List<SustainInput> sustainPool = pool.stream().map(InsightBuilder::toSustainInput).toList();
        int[] costs = pool.stream().mapToInt(CandidateArea::inclusiveCostMedian).toArray();
        double medianSales = InsightScore.medianSales(sustainPool);

        Baseline baseline = new Baseline(Frontier.nEntry(costs, budget), greenCount(pool, budget),
                SustainFilter.nSustain(sustainPool, budget, 0.0),   // m=0 → 필터2와 동일 (#26)
                topScore(pool, budget));

        List<FundingProduct> qualified = EligibilityFilter.qualify(profile, products.all());
        Map<String, Integer> used = UsedLimits.byProduct(composition, qualified);

        List<BoundaryEval> evals = new ArrayList<>();
        Map<Integer, FundingPlan> plans = new HashMap<>();
        for (int boundary : frontier.boundaries(industry, budget)) {
            CoverOutcome outcome = FundingCheck.coverWithReason(
                    boundary - budget, qualified, used, funding);
            outcome.plan().ifPresent(plan -> plans.put(boundary, plan));
            evals.add(evaluate(boundary, budget, pool, sustainPool, costs, baseline, outcome));
        }

        List<InsightEvent> insights = assemble(pool, sustainPool, budget, industry,
                evals, plans, medianSales);
        // A1 경계 + A4(무권리) 경계 = 평가한 경계 총수 (assumptions #29)
        int explored = InsightScore.evaluatedCount(evals)
                + frontier.boundariesExPremium(industry, budget).size();
        String emptyReason = insights.isEmpty()
                ? (baseline.nEntry() == 0 ? NO_ENTRY_REASON : NO_INSIGHT_REASON)
                : null;
        return new Result(insights, explored, emptyReason);
    }

    // ── 경계 평가 (전부 engine 호출) ────────────────────────────────────────

    private BoundaryEval evaluate(int boundary, int budget, List<CandidateArea> pool,
                                  List<SustainInput> sustainPool, int[] costs,
                                  Baseline baseline, CoverOutcome outcome) {
        double payment = outcome.plan().map(FundingPlan::monthlyPayment).orElse(0.0);
        int productCount = outcome.plan().map(p -> p.allocations().size()).orElse(0);
        return new BoundaryEval(boundary, boundary - budget, payment, productCount,
                baseline.nEntry(), Frontier.nEntry(costs, boundary),
                baseline.nGreen(), greenCount(pool, boundary),
                baseline.nSustain(), SustainFilter.nSustain(sustainPool, boundary, payment),
                baseline.topScore(), topScore(pool, boundary),
                outcome.reason());
    }

    // ── 인사이트 조립 ──────────────────────────────────────────────────────

    private List<InsightEvent> assemble(List<CandidateArea> pool, List<SustainInput> sustainPool,
                                        int budget, String industry, List<BoundaryEval> evals,
                                        Map<Integer, FundingPlan> plans, double medianSales) {
        OptionalInt safeBudget = frontier.safeBudget(industry, budget);
        // B_safe = max{c_a ≤ B₀} 이므로 **부재 ⟺ 진입 후보 0곳**이다 (Frontier.bSafe).
        // 마진이 0(B_safe == B₀)인 것은 "마진이 없는 것"이 아니라 **현재 예산이 어떤 후보의 진입
        // 하한과 정확히 일치하는 정상 상태**다. 그런데 시나리오 카드 기본값(equity + 필요분)은
        // 정의상 항상 그 경계값이라, 두 조건을 합쳐 두면 **슬라이더를 건드리지 않은 첫 화면에서
        // T1·T2가 전멸**했다 (BE 리뷰 D-05). 게이트를 둘로 나눈다.
        boolean marginExists = safeBudget.isPresent();                          // 상향 방출 조건
        boolean marginNonZero = marginExists && safeBudget.getAsInt() < budget;  // T2 문장 분기

        List<InsightEvent> insights = new ArrayList<>();
        if (marginExists) {
            for (BoundaryEval eval : InsightScore.topBoundaries(evals, medianSales)) {
                insights.add(t1(nextId(insights), eval, plans.get(eval.boundary()), medianSales));
            }
            if (insights.size() < InsightScore.MAX_SCORED_REPORTS) {
                conditionalCount(pool, budget).ifPresent(count -> insights.add(
                        t5(nextId(insights), pool, sustainPool, budget, count)));
            }
            // T2는 **항상 마지막에** 실린다 — 상향(T1·T5)이 단독으로 나갈 경로를 코드에서 없앤다
            insights.add(t2(nextId(insights), safeBudget.getAsInt(), budget, pool, sustainPool,
                    marginNonZero));
        }
        return List.copyOf(insights);
    }

    /**
     * T1 기회 경계 — 진입 수·지속 수·갭·조달 명세·고지가 <b>한 묶음</b>으로만 생성된다.
     *
     * <p><b>변동금리 근거는 금액 대신 문구를 싣는다</b> (계약 D8 · BE 리뷰 D-03). 현 분기 금리로
     * 월 상환액을 계산해 지속 후보 수를 내는 것까지는 하되, 그 금액을 화면에 고정 금액처럼
     * 표기하지는 않는다 — 분기마다 바뀌는 값이기 때문이다.
     */
    private InsightEvent t1(String id, BoundaryEval eval, FundingPlan plan, double medianSales) {
        FundingPlan.Allocation lead = plan.allocations().get(0);
        FundingProduct product = lead.product();
        int payment = (int) Math.round(eval.monthlyPayment());
        boolean fixedRate = plan.allocations().stream()
                .allMatch(a -> a.product().hasFixedRate());
        String rateClause = fixedRate
                ? "연 %s%%, %d개월 상환%s".formatted(rate(product.rate()), lead.termMonths(),
                        lead.termAssumed() ? " 가정" : "")
                : "분기별 변동금리, %d개월 상환%s".formatted(lead.termMonths(),
                        lead.termAssumed() ? " 가정" : "");
        String paymentClause = fixedRate
                ? "월 상환 부담 %s만 원을 반영하면 지속 안정 후보는 %d곳입니다. "
                        .formatted(won(payment), eval.nSustainAfter())
                : "상환 부담을 반영하면 지속 안정 후보는 %d곳입니다. ".formatted(eval.nSustainAfter());
        String headline = "%s만 원을 추가 확보하면 진입 가능 후보는 %d곳에서 %d곳으로 늘어납니다. "
                .formatted(won(eval.gap()), eval.nEntryBefore(), eval.nEntryAfter())
                + "다만 해당 금액을 %s(%s)으로 조달할 경우 ".formatted(product.name(), rateClause)
                + paymentClause
                + QUALIFICATION_TAIL;
        // score_delta 는 계약·스펙상 **ΔS_top(종합점수 등급 개선분)** 이다. 목적함수 값
        // (Q×F/C)을 실으면 0~100 점수 체계에 +455.41 같은 값이 나가 화면에 그릴 수 없다
        // (BE 리뷰 D-02). 목적함수는 정렬에만 쓴다.
        Delta delta = new Delta(eval.nEntryBefore(), eval.nEntryAfter(), eval.nSustainAfter(),
                (double) eval.deltaScoreGrades());
        // rate_type은 항상, rate_note는 변동 시에만 실린다. notice_date는 여전히 미보유 → 생략.
        // source_quote는 상품에 붙어 오므로(BE-06 ①) 인사이트 근거에도 원문이 함께 실린다.
        Funding funding = new Funding(product.name(), product.amountMax(), product.rate(),
                product.rateType(), product.rateNote(), lead.termMonths(), product.status(),
                null, product.exclusiveGroup(), product.source(), product.sourceQuote());
        return new InsightEvent(id, "T1", headline, delta, eval.gap(),
                fixedRate ? payment : null, fixedRate ? null : VARIABLE_PAYMENT_NOTE,
                funding, true);
    }

    /**
     * T2 안전 마진 — 하향 정보이므로 조달 명세가 없다.
     *
     * <p>{@code n_sustain_after} 에는 <b>지속 필터를 통과한 수</b>가 실린다. 진입 수를 세 자리에
     * 그대로 넣던 구 구현은 같은 예산에서 T1(362곳)과 T2(548곳)가 다른 "지속 안정 후보"를
     * 보고하게 만들었다 (BE 리뷰 D-02).
     *
     * @param marginNonZero {@code false} 면 B_safe == B₀ — "~까지 낮춰도 유지"라는 문장이 성립하지
     *                      않으므로 하한 일치 사실을 그대로 서술한다 (D-05)
     */
    private InsightEvent t2(String id, int safeBudget, int budget, List<CandidateArea> pool,
                            List<SustainInput> sustainPool, boolean marginNonZero) {
        int entered = greenOrEnteredCount(pool, budget);
        int sustain = SustainFilter.nSustain(sustainPool, budget, 0.0);  // m=0 → 필터2와 동일 (#26)
        String headline = marginNonZero
                ? "%s만 원까지 낮춰도 현재 후보 %d곳이 전부 유지됩니다. "
                        .formatted(won(safeBudget), entered)
                        + "차액을 예비 운영자금으로 두면 지속 여력 지표가 개선됩니다."
                : "현재 예산은 후보 %d곳의 진입 하한과 정확히 일치합니다. "
                        .formatted(entered)
                        + "여기서 예산을 낮추면 진입 가능 후보가 줄어듭니다.";
        return new InsightEvent(id, "T2", headline,
                new Delta(entered, entered, sustain, 0.0), null, null, null, null, true);
    }

    /**
     * T5 무권리 조건부 — 권리금 없는 매물을 전제로 한 추가 진입 가능 수.
     *
     * <p>delta 는 <b>도구 계층 계산값</b>이다. 구 구현은 풀 전체 크기를 넣어 진입 후보 수와
     * 어긋난 숫자를 내보냈고(실측 533 대신 558, 1,036 대신 1,061), 그 두 값은 어떤 계산의
     * 결과도 아니었다 — 원칙 1 위반이다 (BE 리뷰 D-01).
     */
    private InsightEvent t5(String id, List<CandidateArea> pool, List<SustainInput> sustainPool,
                            int budget, int conditionalCount) {
        int before = Frontier.nEntry(
                pool.stream().mapToInt(CandidateArea::inclusiveCostMedian).toArray(), budget);
        int after = before + conditionalCount;   // 무권리 전제 상한 — 조건부 후보만 더해진다
        int sustainAfter = SustainFilter.nSustain(sustainPool, budget, 0.0);
        String headline = "무권리 매물을 확보하면 %d곳이 추가로 진입 가능합니다. "
                .formatted(conditionalCount)
                + "권리금 포함 비용 기준으로는 현재 예산을 넘어서는 상권입니다.";
        return new InsightEvent(id, "T5", headline,
                new Delta(before, after, sustainAfter, null),
                null, null, null, null, true);
    }

    // ── 집계 보조 (전부 기존 engine 판정을 그대로 사용) ──────────────────────

    /** 🟢 적합 후보 수 — 판정은 {@link ReverseCheck}가 한다. */
    private static int greenCount(List<CandidateArea> pool, int budget) {
        return (int) pool.stream().filter(c -> verdict(c, budget) == Verdict.FIT).count();
    }

    /** 진입한 후보 수(적합·유의 포함) — T2 문장의 "현재 후보 n곳". */
    private static int greenOrEnteredCount(List<CandidateArea> pool, int budget) {
        return (int) pool.stream().filter(c -> c.inclusiveCostMedian() <= budget).count();
    }

    /** ⚪ 조건부 적합(무권리면 진입 가능) 후보 수. 0곳이면 T5를 만들지 않는다. */
    private static OptionalInt conditionalCount(List<CandidateArea> pool, int budget) {
        int count = (int) pool.stream()
                .filter(c -> verdict(c, budget) == Verdict.CONDITIONAL).count();
        return count > 0 ? OptionalInt.of(count) : OptionalInt.empty();
    }

    private static Verdict verdict(CandidateArea c, int budget) {
        return ReverseCheck.evaluate(budget, CostCalculator.estimate(c.costBlocks()),
                c.burdenRatio(), ReverseCheck.DEFAULT_THETA).verdict();
    }

    /** 진입 후보 중 최고 종합점수 — 산식은 ScoreLookup, 투영은 InsightScore가 담당한다. */
    private static int topScore(List<CandidateArea> pool, int budget) {
        return InsightScore.topScore(pool.stream()
                .filter(c -> c.inclusiveCostMedian() <= budget)
                .map(CandidateArea::axisScores)
                .toList(), LocationService.DEFAULT_WEIGHTS);
    }

    private static SustainInput toSustainInput(CandidateArea c) {
        return new SustainInput(c.monthlyRent(), c.estSales(), c.inclusiveCostMedian());
    }

    // ── 표기 보조 ─────────────────────────────────────────────────────────

    private static String nextId(List<InsightEvent> issued) {
        return "i-" + (issued.size() + 1);
    }

    /** 만원 단위 정수를 천단위 구분해 표기 (계약 공통 규약: 금액은 만원 단위 정수). */
    private static String won(int amount) {
        return String.format("%,d", amount);
    }

    /** 연 이율 표기 — 2.50 같은 잉여 0을 붙이지 않는다. */
    private static String rate(Double annualRate) {
        double value = annualRate;
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}

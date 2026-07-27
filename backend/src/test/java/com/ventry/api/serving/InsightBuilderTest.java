package com.ventry.api.serving;

import static org.assertj.core.api.Assertions.assertThat;

import com.ventry.api.common.FinanceDtos.Source;
import com.ventry.api.engine.Eligibility;
import com.ventry.api.engine.Frontier;
import com.ventry.api.engine.FundingInput;
import com.ventry.api.engine.FundingProduct;
import com.ventry.api.engine.Profile;
import com.ventry.api.engine.SustainFilter;
import com.ventry.api.engine.SustainInput;
import com.ventry.api.explore.ExploreDtos.InsightEvent;
import com.ventry.api.scenario.ScenarioDtos.CompositionItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * BE-05 InsightBuilder — 결정공간 탐색 인사이트 조립 (expl §2·§3·§7).
 *
 * <p>데모 픽스처 후보 3곳의 권리금 포함 비용 중앙값은 홍대 7,500 · 망원 7,750 · 합정 7,950이다.
 * B₀=7,800이면 상향 경계 7,950(갭 150)이 생기고 하향 마진 7,750이 함께 성립한다.
 */
class InsightBuilderTest {

    private final InsightBuilder builder = new InsightBuilder(
            new DemoCandidates(), new DemoProducts(), new FrontierService(new DemoCandidates()));

    private final Profile demo = new Profile(32, 5000, false, "cafe", "서울 마포구");
    private static final FundingInput ROOMY = new FundingInput(250, true);

    /** 월 투자 가능액 1만 원 — 어떤 상품으로도 큰 갭을 덮을 수 없다(커버 실패 경계 재현용). */
    private static final FundingInput TIGHT = new FundingInput(1, false);

    private static Optional<InsightEvent> of(InsightBuilder.Result result, String type) {
        return result.insights().stream().filter(i -> i.type().equals(type)).findFirst();
    }

    // ── #19 T1은 진입·지속·갭·조달·고지가 한 묶음일 때만 생성된다 ──────────

    @Test
    void t1_carriesEntryAndSustainAndGapAndFundingAndDisclaimer() {
        InsightBuilder.Result result = builder.build(demo, 7800, ROOMY, null);

        InsightEvent t1 = of(result, "T1").orElseThrow();
        assertThat(t1.delta().nEntryBefore()).isEqualTo(2);      // 홍대 7,500 · 망원 7,750
        assertThat(t1.delta().nEntryAfter()).isEqualTo(3);       // + 합정 7,950
        assertThat(t1.delta().nSustainAfter()).isEqualTo(3);     // 상환 부담 반영 후에도 3곳
        assertThat(t1.gapAmount()).isEqualTo(150);               // 7,950 − 7,800
        assertThat(t1.marginalPayment()).isEqualTo(3);           // m ≈ 2.66만 원 (연 2.5%·60개월)
        assertThat(t1.funding().name()).isEqualTo("소진공 청년 전용 창업자금");
        assertThat(t1.funding().rate()).isEqualTo(2.5);
        assertThat(t1.disclaimer()).isTrue();

        assertThat(t1.headline())
                .contains("150")            // 갭
                .contains("2곳")            // 진입 전
                .contains("3곳")            // 진입 후 · 지속
                .contains("지속 안정")
                .contains("60개월 상환");   // 금리·기간 가정 병기 (§7)

        // 상향은 하향 마진과 반드시 동반된다
        assertThat(of(result, "T2")).isPresent();
        assertThat(result.insights()).hasSizeLessThanOrEqualTo(3);
        assertThat(result.emptyReason()).isNull();
    }

    // ── #20 상향은 T2 동반이 성립할 때만 만든다 (단독 노출 금지) ──────────

    /**
     * 진입 후보가 0곳이면 상향도 하향도 만들지 않는다 — {@code B_safe} 부재 ⟺ 진입 0곳이다.
     * 이때 0건 사유는 "안정 구간"이 아니라 <b>진입 후보 없음</b>이어야 한다 (D-07).
     */
    @Test
    void withoutEntryCandidates_noInsightAndReasonSaysNoEntry() {
        InsightBuilder.Result belowAll = builder.build(demo, 7000, ROOMY, null);

        assertThat(belowAll.insights()).isEmpty();
        assertThat(belowAll.scenariosExplored()).isPositive();   // 검토는 했다
        assertThat(belowAll.emptyReason()).isEqualTo(InsightBuilder.NO_ENTRY_REASON);
        assertThat(belowAll.emptyReason()).doesNotContain("안정 구간");
    }

    /**
     * D-05 — {@code B_safe == B₀}(마진 0)는 <b>정상 상태</b>이지 상향을 막을 이유가 아니다.
     *
     * <p>시나리오 카드 기본값은 정의상 항상 어떤 후보의 진입 하한과 같으므로, 이 조건에서
     * 상향을 막으면 <b>슬라이더를 건드리지 않은 첫 화면</b>에서 인사이트가 비었다.
     * 대신 T2 문장이 "낮춰도 유지" 대신 하한 일치 사실을 서술한다.
     */
    @Test
    void marginZero_stillReportsUpsideWithBoundaryAwareT2() {
        InsightBuilder.Result exactly = builder.build(demo, 7500, ROOMY, null);

        assertThat(of(exactly, "T2")).isPresent();
        assertThat(of(exactly, "T2").orElseThrow().headline())
                .contains("진입 하한과 정확히 일치")
                .doesNotContain("낮춰도");
        // 상향이 나왔다면 T2 동반이 이미 확인됐다 (원칙 3)
        assertThat(exactly.insights()).isNotEmpty();
    }

    /**
     * D-01·D-02 — delta 3필드가 전부 <b>도구 계층 계산값</b>이어야 한다.
     *
     * <p>구 구현은 T5 에 풀 전체 크기를, T2 의 지속 자리에 진입 수를 넣었다. 둘 다 어떤 계산의
     * 결과도 아니어서 같은 예산에서 T1·T2 가 서로 다른 "지속 안정 후보"를 보고했다.
     */
    @Test
    void deltaFieldsComeFromToolLayer() {
        List<CandidateArea> pool = new DemoCandidates().findCandidates("cafe");
        int[] costs = pool.stream().mapToInt(CandidateArea::inclusiveCostMedian).toArray();
        List<SustainInput> sustainPool = pool.stream()
                .map(c -> new SustainInput(c.monthlyRent(), c.estSales(), c.inclusiveCostMedian()))
                .toList();

        for (int budget : new int[] {7500, 7800, 7950}) {
            InsightBuilder.Result result = builder.build(demo, budget, ROOMY, null);
            int nEntry = Frontier.nEntry(costs, budget);
            int nSustain = SustainFilter.nSustain(sustainPool, budget, 0.0);

            of(result, "T5").ifPresent(t5 -> {
                assertThat(t5.delta().nEntryBefore())
                        .as("T5 before = 실제 진입 후보 수 (budget %d)", budget).isEqualTo(nEntry);
                assertThat(t5.delta().nEntryAfter())
                        .as("T5 after ≤ 풀 크기").isLessThanOrEqualTo(pool.size());
                assertThat(t5.delta().nSustainAfter())
                        .as("T5 도 지속 후보 수를 동반한다 (원칙 3)").isEqualTo(nSustain);
            });
            of(result, "T2").ifPresent(t2 -> assertThat(t2.delta().nSustainAfter())
                    .as("T2 지속 자리 = SustainFilter 계산값 (budget %d)", budget)
                    .isEqualTo(nSustain));
        }
    }

    /** score_delta 는 ΔS_top(등급 구간)이다 — 목적함수 값(Q×F/C)이 아니다 (D-02). */
    @Test
    void scoreDeltaIsGradeImprovement_notObjectiveValue() {
        InsightBuilder.Result result = builder.build(demo, 7800, ROOMY, null);

        of(result, "T1").ifPresent(t1 -> assertThat(t1.delta().scoreDelta())
                .isNotNull()
                .isBetween(-10.0, 10.0));   // 0~100 점수 체계의 등급 구간 차
    }

    /**
     * D-03 (계약 D8) — <b>변동금리 근거</b>는 월 상환액을 금액으로 싣지 않고 문구로 대체한다.
     *
     * <p>실데이터 26건 중 19건이 변동금리이고 그중 13건이 현 분기 금리를 갖는다. 그 금리로
     * 계산은 하되(지속 후보 수 산출), 분기마다 바뀌는 값을 고정 금액처럼 표기하지 않는다.
     */
    @Test
    void variableRateLead_omitsMarginalPayment_andShipsServerNote() {
        FundingProduct variable = new FundingProduct(
                "소진공 혁신성장촉진자금",
                new Eligibility(null, null, null, false),
                3000, 4.25, "variable",
                "정책자금 기준금리 3.85% + 0.4%p = 연 4.25% (분기별 변동금리)",
                60, null, "open", "2026-Q1",
                new Source("소상공인시장진흥공단", "https://www.semas.or.kr", "2026-07-19"));
        InsightBuilder variableBuilder = new InsightBuilder(
                new DemoCandidates(), () -> List.of(variable),
                new FrontierService(new DemoCandidates()));

        InsightBuilder.Result result = variableBuilder.build(demo, 7800, ROOMY, null);
        InsightEvent t1 = of(result, "T1").orElseThrow();

        assertThat(t1.marginalPayment()).isNull();                     // 금액 생략 (계약 D8)
        assertThat(t1.marginalPaymentNote()).isEqualTo(InsightBuilder.VARIABLE_PAYMENT_NOTE);
        assertThat(t1.funding().rateType()).isEqualTo("variable");
        assertThat(t1.funding().rateNote()).isNotBlank();
        assertThat(t1.headline())
                .contains("분기별 변동금리")
                .doesNotContain("연 4.25%")                            // 고정 어감 금지
                .contains("지속 안정 후보");                            // 지속 수는 여전히 보고한다
    }

    /** 고정금리 근거는 금액을 싣고 문구를 싣지 않는다 — 두 필드가 동시에 나가면 안 된다. */
    @Test
    void fixedRateLead_shipsAmount_withoutNote() {
        InsightEvent t1 = of(builder.build(demo, 7800, ROOMY, null), "T1").orElseThrow();

        assertThat(t1.marginalPayment()).isNotNull();
        assertThat(t1.marginalPaymentNote()).isNull();
    }

    /** 원칙 3의 코드적 강제 — 상향(T1·T5)이 있으면 같은 스트림에 T2가 반드시 있다. */
    @Test
    void upsideNeverShipsWithoutSafetyMargin() {
        for (int budget : new int[] {7000, 7500, 7600, 7800, 7950, 8200}) {
            InsightBuilder.Result result = builder.build(demo, budget, ROOMY, null);
            boolean hasUpside = of(result, "T1").isPresent() || of(result, "T5").isPresent();
            if (hasUpside) {
                assertThat(of(result, "T2"))
                        .as("budget %d — 상향이 있으면 T2가 동반돼야 한다", budget)
                        .isPresent();
                assertThat(of(result, "T2").orElseThrow().delta().nSustainAfter())
                        .as("budget %d — 지속 후보 수가 실려야 한다", budget)
                        .isNotNull();
            }
        }
    }

    // ── #21 용어 컴플라이언스 ───────────────────────────────────────────────

    @Test
    void headlines_containNoAdvisoryPredicates() {
        for (int budget : new int[] {7000, 7500, 7800, 8000}) {
            for (InsightEvent insight : builder.build(demo, budget, ROOMY, null).insights()) {
                assertThat(insight.headline())
                        .as("budget %d / %s", budget, insight.type())
                        .isNotEmpty()
                        .doesNotContain("추천", "권장", "승인", "좋습니다", "유리합니다", "보장");
            }
        }
    }

    // ── #22 커버 실패 경계는 보고에서 빠진다 ────────────────────────────────

    @Test
    void boundaryWithoutFundingCover_isNotReported() {
        // 월 투자 가능액 1만 원 < m 2.66만 원 → 상환 여력 초과로 경계 제외 (assumptions #23)
        InsightBuilder.Result tight = builder.build(demo, 7800, new FundingInput(1, true), null);

        assertThat(of(tight, "T1")).isEmpty();
        assertThat(of(tight, "T2")).isPresent();                 // 하향 정보는 조달과 무관
        assertThat(tight.scenariosExplored()).isPositive();      // 평가 집합에는 남는다
    }

    // ── #23 잔여 한도 역매핑 (assumptions #31) ──────────────────────────────

    @Test
    void usedLimits_mapsCompositionToProducts_byLimitDescending() {
        Source semas = new Source("소상공인시장진흥공단", "https://www.semas.or.kr", "2026-07-19");
        FundingProduct big = product("정책자금 대", 3000, semas);
        FundingProduct small = product("정책자금 소", 1000, semas);

        // 동일 type 상품이 복수면 한도 큰 순으로 소진한다
        assertThat(UsedLimits.byProduct(
                List.of(new CompositionItem("policy_loan", 3500)), List.of(small, big)))
                .containsExactlyInAnyOrderEntriesOf(Map.of("정책자금 대", 3000, "정책자금 소", 500));

        // 자기자본·미지의 type은 어떤 상품에도 귀속되지 않는다
        assertThat(UsedLimits.byProduct(List.of(new CompositionItem("equity", 5000),
                new CompositionItem("crowdfunding", 2000)), List.of(big, small))).isEmpty();
        assertThat(UsedLimits.byProduct(null, List.of(big))).isEmpty();

        // 통합: B₀를 정책자금 3,000으로 채웠으면 그 상품 잔여는 0 → 보증 상품이 갭을 덮는다
        InsightBuilder.Result result = builder.build(demo, 7800, ROOMY,
                List.of(new CompositionItem("equity", 5000),
                        new CompositionItem("policy_loan", 3000)));
        assertThat(of(result, "T1").orElseThrow().funding().name())
                .isEqualTo("서울신용보증재단 창업보증");
    }

    // ── #24 T5는 무권리 조건부 상권이 있을 때만 ─────────────────────────────

    @Test
    void t5_appearsOnlyWhenConditionalAreaExists() {
        // B₀=7,800: 합정이 ⚪ 조건부 (무권리 6,400 ≤ 7,800 < 포함 7,950)
        assertThat(of(builder.build(demo, 7800, ROOMY, null), "T5")).isPresent();

        // B₀=8,000: 후보 3곳 전부 진입 → 조건부 상권 없음 → T5 없음, 상향 경계도 없음
        InsightBuilder.Result full = builder.build(demo, 8000, ROOMY, null);
        assertThat(of(full, "T5")).isEmpty();
        assertThat(of(full, "T1")).isEmpty();
        assertThat(of(full, "T2")).isPresent();                  // B_safe 7,950 < 8,000
        assertThat(full.scenariosExplored()).isZero();           // 위로 남은 경계가 없다
    }

    // ── 문장 안의 곳수 표기 — 네 자리는 실데이터에서만 나온다 ────────────────

    /**
     * 데모 픽스처는 후보가 3곳이라 곳수가 <b>세 자리를 넘지 않는다</b>. 실데이터(카페 1,059곳)
     * 에서만 네 자리가 되므로, 곳수 표기를 `%d` 로 되돌려도 기존 테스트는 전부 초록이었다 —
     * 실기동 화면에서만 「1018곳」이 드러났고 README 도슨트 대본(「1,018곳」)과 글자가 어긋났다.
     *
     * <p>그래서 픽스처를 <b>곳수만 늘려</b> 같은 판정 성질로 복제하고, T1 의 진입 전·후·지속
     * 세 자리를 한 번에 잠근다. 계약의 숫자 필드는 그대로 정수임을 함께 확인한다.
     */
    @Test
    void t1AndT2Headlines_useThousandSeparator_onFourDigitCounts() {
        List<CandidateArea> pool = new ArrayList<>();
        pool.addAll(copies(fixture("A-1103"), 1000, "H-"));   // 포함 7,500 → B₀에서 진입 (유의)
        pool.addAll(copies(fixture("A-1101"), 1000, "M-"));   // 포함 7,750 → B₀에서 진입 (적합)
        pool.addAll(copies(fixture("A-1102"), 1010, "J-"));   // 포함 7,950 → 상향 경계 (갭 150)

        InsightBuilder.Result result = builderOf(pool).build(demo, 7800, ROOMY, null);

        InsightEvent t1 = of(result, "T1").orElseThrow();
        assertThat(t1.headline())
                .contains("2,000곳에서 3,010곳으로")
                .contains("지속 안정 후보는 3,010곳");
        // 계약 필드는 문자열이 아니라 정수 그대로다 — 포맷은 화면 몫이다
        assertThat(t1.delta().nEntryBefore()).isEqualTo(2000);
        assertThat(t1.delta().nEntryAfter()).isEqualTo(3010);
        assertThat(t1.delta().nSustainAfter()).isEqualTo(3010);

        assertThat(of(result, "T2").orElseThrow().headline()).contains("현재 후보 2,000곳");
    }

    /** T5 의 조건부 곳수도 같은 규칙을 따른다 — 상향 경계가 조달 미커버라 T1 없이 T5·T2만 남는다. */
    @Test
    void t5Headline_usesThousandSeparator_onFourDigitCount() {
        List<CandidateArea> pool = new ArrayList<>();
        pool.addAll(copies(fixture("A-1103"), 1000, "H-"));   // 포함 7,500 → 진입
        pool.addAll(copies(fixture("A-9999"), 1010, "Y-"));   // 무권리 7,700 ≤ B₀ < 포함 9,320 → 조건부

        InsightBuilder.Result result = builderOf(pool).build(demo, 7800, TIGHT, null);

        assertThat(of(result, "T1")).isEmpty();               // 갭 1,520 은 월 1만원으로 못 덮는다
        InsightEvent t5 = of(result, "T5").orElseThrow();
        assertThat(t5.headline()).contains("무권리 매물을 확보하면 1,010곳이");
        assertThat(t5.delta().nEntryAfter()).isEqualTo(2010);
        assertThat(of(result, "T2").orElseThrow().headline()).contains("현재 후보 1,000곳");
    }

    /** 데모 픽스처 원본 1건 — 비용 구간·부담률·판정 성질을 그대로 쓰기 위해 조회해서 복제한다. */
    private static CandidateArea fixture(String areaCode) {
        return new DemoCandidates().find("cafe", areaCode).orElseThrow();
    }

    /** 같은 원자재를 상권 코드만 바꿔 복제한다 — 곳수만 늘리고 판정은 원본과 동일하다. */
    private static List<CandidateArea> copies(CandidateArea template, int count, String prefix) {
        return IntStream.range(0, count)
                .mapToObj(i -> new CandidateArea(prefix + i, template.name() + " " + i,
                        template.lat(), template.lng(), template.costBlocks(), template.axisScores(),
                        template.monthlyRent(), template.estSales(), template.dailyFloating(),
                        template.rentOrg(), template.rentDistrict(), template.rentFallback(),
                        template.transitStation(), template.transitLine(),
                        template.transitDistanceM(), template.transitDailyRiders(),
                        template.transitFallback()))
                .toList();
    }

    private static InsightBuilder builderOf(List<CandidateArea> pool) {
        CandidateSource source = new ScaledCandidates(List.copyOf(pool));
        return new InsightBuilder(source, new DemoProducts(), new FrontierService(source));
    }

    /** 곳수만 늘린 후보 공급원. 업종은 데모와 같이 단일이라 무시한다. */
    private record ScaledCandidates(List<CandidateArea> areas) implements CandidateSource {

        @Override
        public List<CandidateArea> findCandidates(String industry) {
            return areas;
        }

        @Override
        public Optional<CandidateArea> find(String industry, String areaCode) {
            return areas.stream().filter(a -> a.areaCode().equals(areaCode)).findFirst();
        }
    }

    private static FundingProduct product(String name, int amountMax, Source source) {
        return new FundingProduct(name, new Eligibility(null, null, null, false),
                amountMax, 2.5, 60, null, "open", "2026-Q1", source);
    }
}

package com.ventry.api.common;

import com.ventry.api.common.FinanceDtos.Product;
import com.ventry.api.common.FinanceDtos.RiskReview;
import com.ventry.api.common.FinanceDtos.Source;
import com.ventry.api.common.FinanceDtos.SourceQuote;
import com.ventry.api.explore.ExploreDtos.Delta;
import com.ventry.api.explore.ExploreDtos.DoneEvent;
import com.ventry.api.explore.ExploreDtos.Funding;
import com.ventry.api.explore.ExploreDtos.InsightEvent;
import com.ventry.api.explore.ExploreDtos.PlanEvent;
import com.ventry.api.explore.ExploreDtos.RefineEvent;
import com.ventry.api.recommend.RecommendDtos.Area;
import com.ventry.api.recommend.RecommendDtos.Breakdown;
import com.ventry.api.recommend.RecommendDtos.Cost;
import com.ventry.api.recommend.RecommendDtos.RecommendResponse;
import com.ventry.api.recommend.RecommendDtos.RentSource;
import com.ventry.api.recommend.RecommendDtos.Transit;
import com.ventry.api.scenario.ScenarioDtos.CompositionItem;
import com.ventry.api.scenario.ScenarioDtos.ScenarioCard;
import java.util.List;

/**
 * BE-01 목 데이터 — 데모 프로필(만 32세 / 자기자본 5,000만 / 마포 망원 카페) 기준.
 * 수치는 데모 시나리오(expl §8: 예산 8,000만 → 후보 3곳 → T1 1,320만 갭 → 진입 11·지속 7)와
 * 일치시켰다. CP2(D6)에서 실데이터로 교체 — 문장은 용어 컴플라이언스(§0-4) 준수.
 */
public final class MockData {

    public static final String DATA_AS_OF = "2026-Q1";

    public static final String DISCLAIMER =
            "본 정보는 공개 자료 기반 정보 제공이며 대출 권유·중개·자문이 아닙니다. "
                    + "실제 한도·금리·승인 여부는 해당 기관의 심사에 따릅니다.";

    private MockData() {}

    // ── 조달 시나리오 (화면 2) ───────────────────────────────────────────

    private static final Product GUARANTEE_PRODUCT = new Product(
            "서울신용보증재단 창업보증",
            new Source("서울신용보증재단", "https://www.seoulshinbo.co.kr", "2026-07-19"),
            null);

    private static final Product POLICY_LOAN_PRODUCT = new Product(
            "소진공 청년 전용 창업자금",
            new Source("소상공인시장진흥공단", "https://www.semas.or.kr", "2026-07-19"),
            null);

    public static List<ScenarioCard> scenarios() {
        return List.of(
                new ScenarioCard("보수", 6500,
                        List.of(new CompositionItem("equity", 5000),
                                new CompositionItem("guarantee", 1500)),
                        List.of(GUARANTEE_PRODUCT)),
                new ScenarioCard("적극", 8000,
                        List.of(new CompositionItem("equity", 5000),
                                new CompositionItem("policy_loan", 3000)),
                        List.of(POLICY_LOAN_PRODUCT, GUARANTEE_PRODUCT)));
    }

    // ── 입지 추천 (화면 3) — 마커 3종: 🟢2 🟠1 ──────────────────────────

    public static RecommendResponse recommend() {
        List<Area> areas = List.of(
                new Area("A-1101", "망원역 상권", 37.5556, 126.9106, Verdict.FIT,
                        new Breakdown(0.82, 0.74, 0.68, 0.71, 0.77),
                        new Cost(List.of(5800, 7200), List.of(7400, 9100)), 0.11,
                        "길단위 유동·배후 인구가 서울 상위 구간이며 부담률 11%로 임계 이내입니다.",
                        new RentSource("REB", "홍대합정상권", false),
                        new Transit("망원", "6", 320, 21000, false)),
                new Area("A-1102", "합정역 상권", 37.5495, 126.9139, Verdict.FIT,
                        new Breakdown(0.79, 0.81, 0.55, 0.66, 0.70),
                        new Cost(List.of(6200, 7800), List.of(7900, 9600)), 0.13,
                        "환승역 교통 접근성과 직장인구 배후가 강하며 부담률 13%로 임계 이내입니다.",
                        new RentSource("REB", "홍대합정상권", false),
                        new Transit("합정", "2·6", 210, 68000, false)),
                new Area("A-1103", "홍대입구역 상권", 37.5572, 126.9236, Verdict.CAUTION,
                        new Breakdown(0.91, 0.88, 0.31, 0.74, 0.52),
                        new Cost(List.of(7600, 9400), List.of(9800, 12500)), 0.19,
                        "수요 지표는 최상위지만 환산임대료 부담률 19%로 임계를 초과합니다.",
                        new RentSource("REB", "홍대합정상권", false),
                        new Transit("홍대입구", "2", 180, 92000, false)));
        RiskReview review = new RiskReview(
                "홍대입구역 상권은 경쟁밀도 상위 5% 구간으로, 추정매출 하위 25% 시나리오에서는 "
                        + "부담률이 23%까지 상승합니다. 유의 판정 유지가 타당합니다.",
                true, false);
        return new RecommendResponse(DATA_AS_OF, areas, review);
    }

    // ── 결정공간 탐색 (expl §8 데모 시나리오) ────────────────────────────

    public static PlanEvent explorePlan() {
        return new PlanEvent(List.of("A1", "A4"), "대화 맥락 기반: 권리금 축 우선 검토");
    }

    public static InsightEvent insightT1() {
        return new InsightEvent("i-1", "T1",
                "1,320만 원 추가 확보 시 진입 후보 3→11곳, 상환 부담 반영 시 지속 안정 7곳. "
                        + "서울신용보증재단 창업보증(연 2.5%, 5년 상환 가정)이 자격 요건에 부합합니다 "
                        + "— 한도·승인은 기관 심사 사항입니다.",
                new Delta(3, 11, 7, 0.42), 1320, 28,
                new Funding("서울신용보증재단 창업보증", 3000, 2.5, 60, "open", "2026-05-15",
                        "SGF_STARTUP",
                        new Source("서울신용보증재단", "https://www.seoulshinbo.co.kr", "2026-07-19"),
                        null),
                true);
    }

    public static InsightEvent insightT2() {
        return new InsightEvent("i-2", "T2",
                "6,480만 원까지 낮춰도 현재 후보 3곳이 전부 유지됩니다. "
                        + "차액을 예비 운영자금으로 두면 지속 여력 지표가 개선됩니다.",
                new Delta(3, 3, 3, 0.0), null, null, null, true);
    }

    public static RefineEvent refineT1() {
        return new RefineEvent("i-1",
                "권리금 부담을 걱정하셨는데, 1,320만 원을 추가 확보하면 진입 후보가 3곳에서 11곳으로 "
                        + "늘어납니다. 다만 이 금액을 서울신용보증재단 창업보증(연 2.5%, 5년 상환 가정)으로 "
                        + "조달하면 월 상환 부담을 반영한 지속 안정 후보는 7곳입니다 — 자격 요건 부합 여부만 "
                        + "확인된 것이며, 한도·승인은 기관 심사 사항입니다.");
    }

    public static DoneEvent exploreDone() {
        return new DoneEvent(6,
                List.of(List.of(6480, 3), List.of(7800, 5), List.of(9320, 11)));
    }

    // ── 역방향 판정 ─────────────────────────────────────────────────────

    public static Product matchingProduct() {
        return new Product(
                "소진공 청년 전용 창업자금",
                new Source("소상공인시장진흥공단", "https://www.semas.or.kr", "2026-07-19"),
                new SourceQuote("만 39세 이하 예비창업자로서 사업자등록 전 또는 등록 후 1년 이내인 자",
                        "소상공인시장진흥공단", "청년 전용 창업자금 공고", "2026-06"));
    }

    public static RiskReview checkAreaReview() {
        return new RiskReview(
                "해당 상권의 권리금 포함 비용 구간 상단을 기준으로 하면 부족분이 2,100만 원까지 "
                        + "늘어날 수 있습니다. 구간 하단 기준 판정임을 함께 표기해야 합니다.",
                true, false);
    }
}

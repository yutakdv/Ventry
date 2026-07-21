package com.ventry.api.common;

import com.ventry.api.common.FinanceDtos.Product;
import com.ventry.api.common.FinanceDtos.Source;
import com.ventry.api.explore.ExploreDtos.Delta;
import com.ventry.api.explore.ExploreDtos.DoneEvent;
import com.ventry.api.explore.ExploreDtos.Funding;
import com.ventry.api.explore.ExploreDtos.InsightEvent;
import com.ventry.api.explore.ExploreDtos.PlanEvent;
import com.ventry.api.explore.ExploreDtos.RefineEvent;
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
}

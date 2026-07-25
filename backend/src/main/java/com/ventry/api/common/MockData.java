package com.ventry.api.common;

import com.ventry.api.common.FinanceDtos.Product;
import com.ventry.api.common.FinanceDtos.Source;
import com.ventry.api.scenario.ScenarioDtos.CompositionRange;
import com.ventry.api.scenario.ScenarioDtos.ScenarioCard;
import java.util.List;

/**
 * BE-01 목 데이터 — 데모 프로필(만 32세 / 자기자본 5,000만 / 마포 망원 카페) 기준.
 * 결정공간 탐색(/explore) 목은 BE-05에서 실계산({@code ExploreService}·{@code InsightBuilder})으로
 * 교체되어 삭제됐다. 남은 목은 recommend·diagnose·check-area·scenarios 골격뿐이다.
 */
public final class MockData {

    public static final String DATA_AS_OF = "2026-Q1";

    public static final String DISCLAIMER =
            "본 정보는 공개 자료 기반 정보 제공이며 대출 권유·중개·자문이 아닙니다. "
                    + "실제 한도·금리·승인 여부는 해당 기관의 심사에 따릅니다.";

    private MockData() {}

    // ── 조달 시나리오 (화면 2) ───────────────────────────────────────────

    /** 자기자본 — 심사와 무관한 확정 재원이므로 두 카드의 예산 하한을 이룬다. */
    private static final int EQUITY = 5000;

    private static final Product GUARANTEE_PRODUCT = new Product(
            "서울신용보증재단 창업보증", 1500, 2.5, DATA_AS_OF,
            new Source("서울신용보증재단", "https://www.seoulshinbo.co.kr", "2026-07-19"),
            null);

    private static final Product POLICY_LOAN_PRODUCT = new Product(
            "소진공 청년 전용 창업자금", 3000, 2.5, DATA_AS_OF,
            new Source("소상공인시장진흥공단", "https://www.semas.or.kr", "2026-07-19"),
            null);

    /**
     * 조달 시나리오 2장. 예산은 [자기자본, 자기자본 + Σ 상품 한도] 범위로 제시한다 —
     * 한도는 공고상 상한일 뿐 승인 금액이 아니므로 단일값 노출은 보장 어감을 만든다(§0-4).
     * 실 시나리오 생성(자격 필터 통과 상품 조합)은 BE-04에서 교체.
     */
    public static List<ScenarioCard> scenarios() {
        return List.of(
                new ScenarioCard("보수", 6500, EQUITY, 6500,
                        List.of(new CompositionRange("equity", EQUITY, EQUITY),
                                new CompositionRange("guarantee", 0, 1500)),
                        List.of(GUARANTEE_PRODUCT)),
                new ScenarioCard("적극", 8000, EQUITY, 8000,
                        List.of(new CompositionRange("equity", EQUITY, EQUITY),
                                new CompositionRange("policy_loan", 0, 3000)),
                        List.of(POLICY_LOAN_PRODUCT, GUARANTEE_PRODUCT)));
    }
}

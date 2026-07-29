package com.ventry.api.serving;

import static org.assertj.core.api.Assertions.assertThat;

import com.ventry.api.common.FinanceDtos.Source;
import com.ventry.api.engine.Eligibility;
import com.ventry.api.engine.FundingProduct;
import com.ventry.api.engine.Profile;
import com.ventry.api.scenario.ScenarioDtos.ScenarioCard;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * BE-04 ScenarioBuilder — 조달 시나리오 실계산 (assumptions #25).
 * 데모 프로필(자기자본 5,000) 수작업 산수: 보수 5,000+보증 1,500=6,500 / 적극 5,000+정책자금 3,000=8,000.
 */
class ScenarioBuilderTest {

    private final ScenarioBuilder builder =
            new ScenarioBuilder(new DemoProducts(), new DemoCandidates(), new DemoDataMeta());
    private final Profile demo = new Profile(32, 5000, false, "cafe", "서울 마포구");

    /**
     * 실사용 점검(2026-07-29) — 초기 예산은 <b>슬라이더 격자 위</b>에 있어야 한다.
     *
     * <p>격자는 {@code budget_min + k*100} 이다(프론트 {@code Budget.tsx} 의 step).
     * 초기값이 그 밖에 있으면 사용자가 슬라이더를 <b>잡기만 해도</b> 값이 소리 없이 스냅한다 —
     * 실측에서 확정 예산 7,901만원이 화면 4에서 손대는 순간 7,900만원이 됐다.
     */
    @Test
    void initialBudget_sitsOnSliderStepGrid() {
        for (ScenarioCard card : builder.build(demo)) {
            assertThat((card.budget() - card.budgetMin()) % 100)
                    .as("%s 카드의 초기값 %d 이 격자(min %d + k*100)를 벗어났다",
                            card.label(), card.budget(), card.budgetMin())
                    .isZero();
            assertThat(card.budget()).isBetween(card.budgetMin(), card.budgetMax());
        }
    }

    /**
     * #87 — 실적재 상품에는 한도 8억(80,000만원) 보증이 3건 있다. 구 규칙("한도 최대")은
     * 소상공인 카페 창업에 8억을 제시했다. 필요분 최소 커버 규칙은 그 상품을 고르지 않는다
     * (DECISIONS §13-2). 데모 후보의 진입 비용 중앙값이 1억을 넘지 않으므로 8억은 과잉이다.
     */
    @Test
    void hugeLimitProduct_isNotSelected_whenNeedIsSmall() {
        ProductSource withHugeLimit = () -> {
            List<FundingProduct> all = new java.util.ArrayList<>(new DemoProducts().all());
            all.add(new FundingProduct("ESG 실천기업 보증",
                    new Eligibility(null, null, null, false),
                    80000, 3.0, 60, null, "open", "2026-07-21",
                    new Source("서울신용보증재단", "https://www.seoulshinbo.co.kr", "2026-07-25")));
            return all;
        };
        ScenarioBuilder svc =
                new ScenarioBuilder(withHugeLimit, new DemoCandidates(), new DemoDataMeta());

        for (ScenarioCard card : svc.build(demo)) {
            assertThat(card.products()).allSatisfy(p ->
                    assertThat(p.amountMax()).isLessThan(80000));
            assertThat(card.budgetMax()).isLessThan(85000);
        }
    }

    /** 덮는 상품이 하나도 없으면 그중 한도 최대로 폴백한다 — 부족해도 가장 가까이 간다. */
    @Test
    void whenNoProductCoversNeed_fallsBackToLargestAvailable() {
        ProductSource tiny = () -> List.of(new FundingProduct("소액 보증",
                new Eligibility(null, null, null, false),
                100, 2.0, 60, null, "open", "2026-07-21",
                new Source("테스트", "https://example.test", "2026-07-25")));
        ScenarioBuilder svc = new ScenarioBuilder(tiny, new DemoCandidates(), new DemoDataMeta());

        assertThat(svc.build(demo).get(1).budgetMax()).isEqualTo(5100);   // 5,000 + 100
    }

    @Test
    void buildsTwoCards_conservativeAndAggressive() {
        List<ScenarioCard> cards = builder.build(demo);
        assertThat(cards).hasSize(2);
        assertThat(cards).extracting(ScenarioCard::label).containsExactly("보수", "적극");
    }

    /** 보수 = 보증형 중 '권리금 제외 필요분'을 덮는 최소 한도 → 5,000 + 1,500 (DECISIONS §13-2). */
    @Test
    void conservativeCard_usesGuaranteeProduct() {
        ScenarioCard card = builder.build(demo).get(0);
        assertThat(card.budgetMin()).isEqualTo(5000);
        assertThat(card.budgetMax()).isEqualTo(6500);
        // 슬라이더 초기값 = 자기자본 + 필요분 (DECISIONS §13-3). 추천 풀 3곳의 권리금 제외
        // 진입 비용 중앙값 6,200(망원 6,200·합정 6,400·홍대 6,050) → 필요분 1,200 → 초기값 6,200.
        // 상한 6,500 은 그대로 노출되므로 사용자가 한도 전액까지 올릴 수 있다.
        assertThat(card.budget()).isEqualTo(6200);
        assertThat(card.budget()).isBetween(card.budgetMin(), card.budgetMax());
        assertThat(card.composition()).extracting("type").containsExactly("equity", "guarantee");
        assertThat(card.products()).singleElement()
                .satisfies(p -> assertThat(p.amountMax()).isEqualTo(1500));
    }

    /** 적극 = 전체 중 '권리금 포함 필요분'을 덮는 최소 한도(정책자금 3,000) → 8,000. 두 상품 편성이면 9,500이라 계약과 어긋난다. */
    @Test
    void aggressiveCard_usesHighestLimitProduct_andMatchesContractBudgetMax() {
        ScenarioCard card = builder.build(demo).get(1);
        assertThat(card.budgetMin()).isEqualTo(5000);
        assertThat(card.budgetMax()).isEqualTo(8000);
        assertThat(card.composition()).extracting("type").containsExactly("equity", "policy_loan");
        assertThat(card.products()).singleElement()
                .satisfies(p -> assertThat(p.amountMax()).isEqualTo(3000));
    }

    /** 규칙 A의 정의: budget_max = budget_min + Σ products[].amount_max (계약 §2 주석과 일치). */
    @Test
    void budgetMax_alwaysEqualsBudgetMinPlusProductLimits() {
        for (ScenarioCard card : builder.build(demo)) {
            int productSum = card.products().stream().mapToInt(p -> p.amountMax()).sum();
            assertThat(card.budgetMax()).isEqualTo(card.budgetMin() + productSum);
        }
    }

    /** 자기자본 구간은 심사와 무관한 확정 재원이라 범위가 아닌 고정값이다. */
    @Test
    void equityComposition_isFixedRange() {
        ScenarioCard card = builder.build(demo).get(0);
        assertThat(card.composition().get(0).amountMin()).isEqualTo(5000);
        assertThat(card.composition().get(0).amountMax()).isEqualTo(5000);
        assertThat(card.composition().get(1).amountMin()).isZero();   // 상품 구간은 0부터
    }

    /** 기준일은 하드코딩이 아니라 DataMetaSource 주입값이다 — 상품 카드이므로 상품 기준일을 쓴다. */
    @Test
    void productDataAsOf_comesFromDataMetaSource() {
        assertThat(builder.build(demo).get(0).products())
                .allSatisfy(p -> assertThat(p.dataAsOf())
                        .isEqualTo(new DemoDataMeta().asOf("finance_product")));
    }

    /** 자격 미달 프로필(기존 사업자·고연령)은 예비창업자 한정 상품이 빠진다 — 보증만 남는다. */
    @Test
    void existingBusinessProfile_dropsPreStartupOnlyProduct() {
        Profile existing = new Profile(45, 5000, true, "cafe", "서울 마포구");
        List<ScenarioCard> cards = builder.build(existing);
        assertThat(cards.get(1).budgetMax()).isEqualTo(6500);   // 정책자금 제외 → 보증만
    }

    /** 계약 D8: 카드 상품에 rate_type("fixed")이 실리고 rate_note는 fixed라 null이다. */
    @Test
    void cardProducts_carryRateTypeFromFundingProduct() {
        for (ScenarioCard card : builder.build(demo)) {
            assertThat(card.products()).allSatisfy(p -> {
                assertThat(p.rateType()).isEqualTo("fixed");   // 데모 상품은 전부 확정 이율
                assertThat(p.rateNote()).isNull();             // fixed → 원문 메모 없음
            });
        }
    }
}

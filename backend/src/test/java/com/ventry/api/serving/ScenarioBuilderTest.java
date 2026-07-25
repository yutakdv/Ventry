package com.ventry.api.serving;

import static org.assertj.core.api.Assertions.assertThat;

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
            new ScenarioBuilder(new DemoProducts(), new DemoDataMeta());
    private final Profile demo = new Profile(32, 5000, false, "cafe", "서울 마포구");

    @Test
    void buildsTwoCards_conservativeAndAggressive() {
        List<ScenarioCard> cards = builder.build(demo);
        assertThat(cards).hasSize(2);
        assertThat(cards).extracting(ScenarioCard::label).containsExactly("보수", "적극");
    }

    /** 보수 = 보증형 중 한도 최대 → 5,000 + 1,500. */
    @Test
    void conservativeCard_usesGuaranteeProduct() {
        ScenarioCard card = builder.build(demo).get(0);
        assertThat(card.budgetMin()).isEqualTo(5000);
        assertThat(card.budgetMax()).isEqualTo(6500);
        assertThat(card.budget()).isEqualTo(6500);      // 슬라이더 초기값 = 상한
        assertThat(card.composition()).extracting("type").containsExactly("equity", "guarantee");
        assertThat(card.products()).singleElement()
                .satisfies(p -> assertThat(p.amountMax()).isEqualTo(1500));
    }

    /** 적극 = 전체 중 한도 최대(정책자금 3,000) → 5,000 + 3,000 = 8,000. 두 상품 편성이면 9,500이 되어 계약과 어긋난다. */
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
}

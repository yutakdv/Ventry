package com.ventry.api.serving;

import com.ventry.api.common.FinanceDtos.Product;
import com.ventry.api.engine.EligibilityFilter;
import com.ventry.api.engine.FundingProduct;
import com.ventry.api.engine.Profile;
import com.ventry.api.scenario.ScenarioDtos.CompositionRange;
import com.ventry.api.scenario.ScenarioDtos.ScenarioCard;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * BE-04 — 조달 시나리오 실계산 (스펙 §5-2 ②, 계약 2번). 자격 통과 상품으로 보수·적극 2장을 만든다.
 *
 * <p>조합 규칙 (assumptions #25): 카드당 상품 <b>1종만</b> 예산에 편성한다 —
 * 보수 = 보증형 중 한도 최대, 적극 = 전체 중 한도 최대. 따라서
 * {@code budget_max = budget_min + Σ products[].amount_max} 가 항상 일치한다.
 *
 * <p>한도는 공고상 상한일 뿐 승인 금액이 아니다 — 화면은 "한도·승인은 기관 심사 사항" 고지를
 * 동반해야 하며, 이 클래스는 어떤 자문성 판단도 하지 않는다 (용어 컴플라이언스 §7).
 */
@Service
public class ScenarioBuilder {

    /** 상품 카드의 기준일은 상품 데이터 기준일을 쓴다 (data_source_meta.finance_product). */
    private static final String META_SOURCE_PRODUCT = "finance_product";

    private final DemoProducts products;
    private final DataMetaSource meta;

    public ScenarioBuilder(DemoProducts products, DataMetaSource meta) {
        this.products = products;
        this.meta = meta;
    }

    /** 보수·적극 2장. 자격 통과 상품이 없으면 자기자본만으로 구성된 카드가 된다. */
    public List<ScenarioCard> build(Profile profile) {
        List<FundingProduct> qualified = EligibilityFilter.qualify(profile, products.all());
        int equity = profile.capital();

        Optional<FundingProduct> conservative = qualified.stream()
                .filter(p -> ProductType.GUARANTEE.equals(ProductType.of(p)))
                .max(Comparator.comparingInt(FundingProduct::amountMax));
        Optional<FundingProduct> aggressive = qualified.stream()
                .max(Comparator.comparingInt(FundingProduct::amountMax));

        return List.of(card("보수", equity, conservative), card("적극", equity, aggressive));
    }

    private ScenarioCard card(String label, int equity, Optional<FundingProduct> selected) {
        List<CompositionRange> composition = new ArrayList<>();
        composition.add(new CompositionRange(ProductType.EQUITY, equity, equity));  // 심사와 무관한 확정 재원
        List<Product> cardProducts = new ArrayList<>();
        int budgetMax = equity;

        if (selected.isPresent()) {
            FundingProduct product = selected.get();
            composition.add(new CompositionRange(ProductType.of(product), 0, product.amountMax()));
            cardProducts.add(new Product(product.name(), product.amountMax(), product.rate(),
                    meta.asOf(META_SOURCE_PRODUCT), product.source(), null));   // source_quote=RAG(P1)
            budgetMax += product.amountMax();
        }
        // 슬라이더 초기 선택값 = 상한(한도 전액 활용 가정) — 승인 금액이라는 뜻이 아니다
        return new ScenarioCard(label, budgetMax, equity, budgetMax, composition, cardProducts);
    }
}

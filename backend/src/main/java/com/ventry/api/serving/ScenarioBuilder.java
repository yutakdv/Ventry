package com.ventry.api.serving;

import com.ventry.api.common.FinanceDtos.Product;
import com.ventry.api.engine.CostCalculator;
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
 * <p>조합 규칙 (assumptions #25, DECISIONS §13-2): 카드당 상품 <b>1종만</b> 예산에 편성하며,
 * 그 1종은 <b>필요분을 커버하는 최소 한도</b> 상품이다. 따라서
 * {@code budget_max = budget_min + Σ products[].amount_max} 가 항상 일치한다.
 *
 * <p><b>왜 '한도 최대'가 아닌가</b>: 실적재 상품에는 한도 8억 보증이 3건 있어, 한도 최대 규칙은
 * 소상공인 카페 창업에 8억을 제시한다. 이 서비스가 답하는 질문은 "내 한도로 어디까지 가능한가"
 * 이지 "얼마나 크게 빌릴 수 있는가"가 아니다 — 필요분을 넘는 조달을 기본값으로 놓는 것은
 * 상향 인사이트를 단독 노출하지 않는다는 원칙과도 어긋난다.
 *
 * <p><b>필요분의 출처</b>: 시나리오 단계에는 확정 예산이 없으므로 <b>해당 업종 후보 상권의
 * 진입 비용 중앙값</b>에서 자기자본을 뺀 값을 쓴다. 두 카드는 그 기준이 다르다 —
 * 보수는 <b>권리금 제외</b>(권리금 없는 자리도 있다), 적극은 <b>권리금 포함</b> 중앙값이다.
 * 이 차이가 카드의 성격 차이 그 자체이며, 임의 배수 파라미터를 도입하지 않고도 두 카드가 갈린다.
 *
 * <p><b>보수·적극은 금액의 대소가 아니라 '수단'이다</b> (이슈 #90, DECISIONS §13-3).
 * 보수는 <b>보증형만</b>, 적극은 <b>전체 상품</b>을 후보로 본다. 두 카드의 후보 풀이 다르므로
 * <b>적극의 예산이 보수보다 크다는 보장이 없다</b> — 실데이터에서 확정 이율이 있는 보증형의
 * 최소 한도(1억)가 전체 최소(5천만)보다 커서 보수가 더 큰 예산을 갖는 경우가 나온다.
 * 이것은 결함이 아니라 <b>가용 상품 구조가 그렇다는 사실</b>이며, 화면은 두 카드를 크기순으로
 * 전제하지 말고 수단의 차이로 서술해야 한다.
 *
 * <p>한도는 공고상 상한일 뿐 승인 금액이 아니다 — 화면은 "한도·승인은 기관 심사 사항" 고지를
 * 동반해야 하며, 이 클래스는 어떤 자문성 판단도 하지 않는다 (용어 컴플라이언스 §7).
 */
@Service
public class ScenarioBuilder {

    /** 상품 카드의 기준일은 상품 데이터 기준일을 쓴다 (data_source_meta.finance_product). */
    private static final String META_SOURCE_PRODUCT = "finance_product";

    /** 계약 D8 고정 정렬: 한도(amount_max) 내림차순, 동점 시 이름 오름차순. 금리 정렬 금지. */
    private static final Comparator<Product> PRODUCT_ORDER =
            Comparator.comparingInt(Product::amountMax).reversed().thenComparing(Product::name);

    private final ProductSource products;
    private final CandidateSource candidates;
    private final DataMetaSource meta;

    public ScenarioBuilder(ProductSource products, CandidateSource candidates,
                           DataMetaSource meta) {
        this.products = products;
        this.candidates = candidates;
        this.meta = meta;
    }

    /** 보수·적극 2장. 자격 통과 상품이 없으면 자기자본만으로 구성된 카드가 된다. */
    public List<ScenarioCard> build(Profile profile) {
        // 확정 이율이 없는 상품은 카드에 편성하지 않는다 — 월 상환액을 못 구하면 상환 여력을
        // 검증할 수 없다 (DECISIONS §13-1). 자격 부합 '목록'에는 남으며, 그쪽은 LocationService 몫.
        List<FundingProduct> qualified = EligibilityFilter.qualify(profile, products.all()).stream()
                .filter(FundingProduct::hasKnownRate)
                .toList();
        int equity = profile.capital();
        List<CandidateArea> pool = candidates.findCandidates(profile.industry());

        int conservativeNeed = shortfall(pool, equity, false);   // 권리금 제외 기준
        int aggressiveNeed = shortfall(pool, equity, true);      // 권리금 포함 기준

        Optional<FundingProduct> conservative = smallestCovering(
                qualified.stream()
                        .filter(p -> ProductType.GUARANTEE.equals(ProductType.of(p)))
                        .toList(),
                conservativeNeed);
        Optional<FundingProduct> aggressive = smallestCovering(qualified, aggressiveNeed);

        return List.of(card("보수", equity, conservativeNeed, conservative),
                card("적극", equity, aggressiveNeed, aggressive));
    }

    /**
     * 필요분 = 진입 비용 중앙값 − 자기자본 (음수면 0).
     *
     * <p>후보 풀이 비면 0을 돌려주며, 그 경우 아래 {@code smallestCovering} 이 한도 최소 상품을
     * 고른다 — 데이터가 없을 때 큰 조달을 기본값으로 삼지 않는다.
     */
    private static int shortfall(List<CandidateArea> pool, int equity, boolean includePremium) {
        if (pool.isEmpty()) {
            return 0;
        }
        double[] medians = pool.stream()
                .map(c -> CostCalculator.estimate(c.costBlocks()))
                .mapToDouble(e -> includePremium ? e.inclPremium().median() : e.exPremium().median())
                .sorted()
                .toArray();
        int mid = medians.length / 2;
        double median = medians.length % 2 == 1
                ? medians[mid]
                : (medians[mid - 1] + medians[mid]) / 2.0;
        return (int) Math.max(0, Math.ceil(median - equity));
    }

    /**
     * 필요분을 덮는 <b>최소 한도</b> 상품. 덮는 상품이 없으면 그중 한도 최대로 폴백한다 —
     * 부족해도 가장 가까이 가는 선택이 사용자에게 유용하고, 그 경우에도 과잉 조달은 생기지 않는다.
     */
    private static Optional<FundingProduct> smallestCovering(List<FundingProduct> pool, int need) {
        return pool.stream()
                .filter(p -> p.amountMax() >= need)
                .min(Comparator.comparingInt(FundingProduct::amountMax))
                .or(() -> pool.stream().max(Comparator.comparingInt(FundingProduct::amountMax)));
    }

    private ScenarioCard card(String label, int equity, int need,
                              Optional<FundingProduct> selected) {
        List<CompositionRange> composition = new ArrayList<>();
        composition.add(new CompositionRange(ProductType.EQUITY, equity, equity));  // 심사와 무관한 확정 재원
        List<Product> cardProducts = new ArrayList<>();
        int budgetMax = equity;

        if (selected.isPresent()) {
            FundingProduct product = selected.get();
            composition.add(new CompositionRange(ProductType.of(product), 0, product.amountMax()));
            // 기준일은 상품 데이터 기준일로 덮어쓰되, rate_type·rate_note는 상품 값을 그대로 싣는다
            cardProducts.add(new Product(product.name(), product.amountMax(), product.rate(),
                    product.rateType(), product.rateNote(), meta.asOf(META_SOURCE_PRODUCT),
                    product.source(), null));   // source_quote=RAG(P1)
            budgetMax += product.amountMax();
        }
        cardProducts.sort(PRODUCT_ORDER);   // 계약 D8 고정 정렬 (카드당 1종이라 실질 no-op, 규약 준수)
        // 슬라이더 초기 선택값 = 자기자본 + 필요분 (범위 안으로 클램프).
        // 상한을 초기값으로 두면 "한도 전액을 쓰는 것"이 기본 선택이 된다 — 가용 상품의 최소
        // 한도가 필요분보다 큰 경우(실데이터에서 흔하다) 과잉 조달이 기본값이 되어버린다.
        // 상한은 여전히 budget_max 로 노출되므로 사용자가 올릴 수 있다 (DECISIONS §13-3).
        int initial = Math.clamp((long) equity + need, equity, budgetMax);
        return new ScenarioCard(label, initial, equity, budgetMax, composition, cardProducts);
    }
}

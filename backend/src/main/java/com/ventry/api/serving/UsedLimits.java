package com.ventry.api.serving;

import com.ventry.api.engine.FundingProduct;
import com.ventry.api.scenario.ScenarioDtos.CompositionItem;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * B₀ 구성 → <b>상품별 이미 사용한 금액</b> 역매핑 (assumptions #31).
 * exploration spec §2-2 ①의 "잔여 한도 원칙"이 성립하려면 조달 검증이
 * "이 상품에서 이미 얼마를 썼는가"를 알아야 하는데, 세션의 {@code composition[]} 에는
 * <b>상품명이 없고 type만</b> 있어서 그대로는 연결되지 않는다.
 *
 * <p>그래서 {@link ProductType}(시나리오 생성이 쓰는 바로 그 규칙)으로 type을 역추적한다.
 * 동일 type 상품이 여럿이면 <b>한도가 큰 순서로 소진</b>한다 — 시나리오 카드가 "그 type에서
 * 한도 최대 상품"을 골랐으므로(assumptions #25) 같은 순서를 따라야 어긋나지 않는다.
 *
 * <p>{@code equity}처럼 대응 상품이 없는 type과 미지의 type은 <b>조용히 무시</b>한다 —
 * 자기자본은 상품 한도를 소비하지 않고, 알 수 없는 재원을 특정 상품에 귀속시키면
 * 잔여 한도를 근거 없이 깎게 되기 때문이다.
 */
public final class UsedLimits {

    private UsedLimits() {}

    /**
     * @param composition 세션에 기록된 B₀ 구성 (null 허용 — 예산 미확정 세션)
     * @param products    자격 통과 상품 (역매핑 대상)
     * @return 상품명 → 사용액(만원). 사용액 0인 상품은 항목을 만들지 않는다
     */
    public static Map<String, Integer> byProduct(List<CompositionItem> composition,
                                                 List<FundingProduct> products) {
        Map<String, Integer> used = new HashMap<>();
        if (composition == null || composition.isEmpty()) {
            return used;
        }
        for (CompositionItem item : composition) {
            int remaining = item.amount();
            if (remaining <= 0) {
                continue;
            }
            for (FundingProduct product : sameTypeByLimitDesc(products, item.type())) {
                if (remaining <= 0) {
                    break;
                }
                int take = Math.min(product.amountMax(), remaining);
                used.merge(product.name(), take, Integer::sum);
                remaining -= take;
            }
            // 남은 금액은 귀속시키지 않는다 (equity·미지의 type·한도 초과분)
        }
        return used;
    }

    /** 같은 type 상품을 한도 큰 순으로 — 시나리오 카드의 선택 순서(assumptions #25)와 동일. */
    private static List<FundingProduct> sameTypeByLimitDesc(List<FundingProduct> products,
                                                            String type) {
        return products.stream()
                .filter(p -> ProductType.of(p).equals(type))
                .sorted(Comparator.comparingInt(FundingProduct::amountMax).reversed())
                .toList();
    }
}

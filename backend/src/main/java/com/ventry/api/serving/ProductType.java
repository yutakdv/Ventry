package com.ventry.api.serving;

import com.ventry.api.engine.FundingProduct;
import java.util.Map;

/**
 * 계약의 {@code composition[].type} 판별 (assumptions #25).
 * 상품 스키마에 종류 컬럼이 없어({@code FundingProduct}·{@code finance_product} 모두 부재)
 * <b>기관명으로 판별</b>한다 — AI-06에서 종류 컬럼이 확정되면 이 상수를 제거하고 컬럼으로 교체한다.
 *
 * <p>BE-05에서 {@code ScenarioBuilder}로부터 이관했다(로직 무변경). 시나리오 생성과
 * 잔여 한도 역매핑({@link UsedLimits})이 <b>같은 규칙</b>을 써야 B₀ 구성과 조달 검증이 어긋나지 않는다.
 */
public final class ProductType {

    public static final String EQUITY = "equity";
    public static final String GUARANTEE = "guarantee";
    public static final String POLICY_LOAN = "policy_loan";

    private static final Map<String, String> ORG_TO_TYPE = Map.of(
            "서울신용보증재단", GUARANTEE,
            "소상공인시장진흥공단", POLICY_LOAN);
    private static final String DEFAULT_TYPE = POLICY_LOAN;

    private ProductType() {}

    /** 기관명 → composition type. 미등록 기관은 정책자금으로 본다 (assumptions #25). */
    public static String of(FundingProduct product) {
        return ORG_TO_TYPE.getOrDefault(product.source().org(), DEFAULT_TYPE);
    }
}

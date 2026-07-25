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
            "소상공인시장진흥공단", POLICY_LOAN,
            "소진공", POLICY_LOAN);          // 실적재 org 표기 (검수본 기준)
    private static final String DEFAULT_TYPE = POLICY_LOAN;

    /**
     * 상품명에 '보증'이 들어가면 보증형이다 — 기관명만으로는 갈리지 않는다.
     * 예: "KB소상공인 보증서대출"은 은행 취급이지만 보증기관 보증서를 담보로 하는 보증형이다.
     * 기관명 규칙보다 <b>먼저</b> 본다.
     */
    private static final String GUARANTEE_KEYWORD = "보증";

    private ProductType() {}

    /**
     * composition type 판별 — 상품명의 '보증' 우선, 그다음 기관명 (assumptions #25).
     *
     * <p>기관명 단독 판별은 실데이터에서 두 군데가 어긋났다(이슈 #87): 적재 org 는
     * "소상공인시장진흥공단"이 아니라 "소진공"이고, KB 보증서대출은 은행 상품이라 정책자금으로
     * 분류돼 <b>보증형 후보가 서울신보 2건(한도 4억·8억)만 남았다</b> — 보수 카드가 4억을
     * 편성하는 원인이었다.
     */
    public static String of(FundingProduct product) {
        if (product.name() != null && product.name().contains(GUARANTEE_KEYWORD)) {
            return GUARANTEE;
        }
        return ORG_TO_TYPE.getOrDefault(product.source().org(), DEFAULT_TYPE);
    }
}

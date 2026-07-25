package com.ventry.api.serving;

import com.ventry.api.common.FinanceDtos.Source;
import com.ventry.api.common.MockData;
import com.ventry.api.engine.Eligibility;
import com.ventry.api.engine.FundingProduct;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 데모 금융상품 픽스처 — EligibilityFilter(BE-03a)·조달 검증(BE-04)의 입력.
 * AI-06 정책자금 구조화 DB로 대체 예정. exclusive_group·term은 구조화 필드 예시.
 * 한도는 데모 시나리오와 정합: 보수 = 자기자본 5,000 + 보증 1,500 = 6,500 /
 * 적극 = 자기자본 5,000 + 정책자금 3,000 = 8,000 (expl §8).
 */
@Component
@Profile("!db")
public class DemoProducts implements ProductSource {

    private static final FundingProduct YOUTH_STARTUP = new FundingProduct(
            "소진공 청년 전용 창업자금",
            new Eligibility(39, null, null, true),           // 만 39세 이하 예비창업자, 전 업종·전 지역
            3000, 2.5, 60, "SEMAS_YOUTH", "open", MockData.DATA_AS_OF,
            new Source("소상공인시장진흥공단", "https://www.semas.or.kr", "2026-07-19"));

    private static final FundingProduct SEOUL_GUARANTEE = new FundingProduct(
            "서울신용보증재단 창업보증",
            new Eligibility(null, null, null, false),        // 자격 무제약(보증)
            1500, 2.5, 60, null, "open", MockData.DATA_AS_OF,
            new Source("서울신용보증재단", "https://www.seoulshinbo.co.kr", "2026-07-19"));

    private static final List<FundingProduct> ALL = List.of(YOUTH_STARTUP, SEOUL_GUARANTEE);

    @Override
    public List<FundingProduct> all() {
        return ALL;
    }
}

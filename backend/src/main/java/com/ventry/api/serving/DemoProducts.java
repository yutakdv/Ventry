package com.ventry.api.serving;

import com.ventry.api.common.FinanceDtos.Source;
import com.ventry.api.engine.Eligibility;
import com.ventry.api.engine.FundingProduct;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 데모 금융상품 픽스처 — EligibilityFilter(BE-03a)·조달 검증(BE-04)의 입력.
 * AI-06 정책자금 구조화 DB로 대체 예정. exclusive_group·term은 구조화 필드 예시.
 */
@Component
public class DemoProducts {

    private static final FundingProduct YOUTH_STARTUP = new FundingProduct(
            "소진공 청년 전용 창업자금",
            new Eligibility(39, null, null),                 // 만 39세 이하, 전 업종·전 지역
            3000, 2.5, 60, "SEMAS_YOUTH", "open",
            new Source("소상공인시장진흥공단", "https://www.semas.or.kr", "2026-07-19"));

    private static final FundingProduct SEOUL_GUARANTEE = new FundingProduct(
            "서울신용보증재단 창업보증",
            new Eligibility(null, null, null),               // 자격 무제약(보증)
            3000, 2.5, 60, null, "open",
            new Source("서울신용보증재단", "https://www.seoulshinbo.co.kr", "2026-07-19"));

    private static final List<FundingProduct> ALL = List.of(YOUTH_STARTUP, SEOUL_GUARANTEE);

    public List<FundingProduct> all() {
        return ALL;
    }
}

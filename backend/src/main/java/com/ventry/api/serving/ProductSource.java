package com.ventry.api.serving;

import com.ventry.api.engine.FundingProduct;
import java.util.List;

/**
 * BE-03g — 금융상품 공급원. 픽스처({@link DemoProducts})와 DB 조회({@link DbProductSource})를
 * 프로파일로 교체한다. {@link LocationService}·{@link ScenarioBuilder} 는 이 인터페이스에만
 * 의존하며 구현체 선택은 프로파일이 결정한다 ({@link CandidateSource} 와 동형).
 */
public interface ProductSource {

    /**
     * 자격 필터·조달 검증의 입력이 되는 상품 전량.
     *
     * <p>"탐색당 쿼리 1회" 원칙(expl §5)에 따라 전량을 한 번에 조회한다 — 상품 수가
     * 수십 건 규모라 필터를 DB로 내리는 것보다 인메모리 판정이 단순하고 빠르다.
     */
    List<FundingProduct> all();
}

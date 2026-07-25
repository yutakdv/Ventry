package com.ventry.api.serving;

import com.ventry.api.engine.FundingProduct;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * BE-03g — DB(finance_product) 기반 상품 공급원. {@code spring.profiles.active=db} 에서만 활성.
 *
 * <p>상품 목록은 분기 단위로만 바뀌는 준정적 데이터라 Caffeine 에 통째로 얹는다
 * ({@link DbCandidateSource} 와 동일 전략).
 */
@Component
@Profile("db")
public class DbProductSource implements ProductSource {

    private final FinanceRepository repository;

    public DbProductSource(FinanceRepository repository) {
        this.repository = repository;
    }

    @Override
    @Cacheable(cacheNames = "products")
    public List<FundingProduct> all() {
        return repository.findAll();
    }
}

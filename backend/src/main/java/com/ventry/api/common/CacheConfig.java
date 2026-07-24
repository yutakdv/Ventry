package com.ventry.api.common;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * BE-02 — Caffeine 캐시 설정. db 프로파일 전용이라 default(픽스처) 경로엔 캐시 프록시가 걸리지 않는다.
 * 상권 데이터는 배치 산출이라 런타임 중 불변 → expireAfterWrite 1시간 + 앱 재시작 시 갱신(무효화 API 불필요).
 */
@Configuration
@EnableCaching
@Profile("db")
public class CacheConfig {

    /** 후보 상권 조회 캐시. 이름은 {@code @Cacheable("candidates")} 와 일치해야 한다. */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("candidates");
        manager.setCaffeine(Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1)));
        return manager;
    }
}

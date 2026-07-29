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

    /**
     * 조회 캐시 — 이름은 {@code @Cacheable} 어노테이션과 일치해야 한다.
     * {@code candidates} = 후보 상권(업종별) · {@code products} = 금융상품 전량(BE-03g).
     * 둘 다 배치 산출이라 런타임 중 불변이다.
     *
     * <p>{@code reviews}·{@code refines}·{@code plans} 는 성격이 다르다 — 리스크 검증
     * 반박문(BE-05·#96)·인사이트 언어화(BE-06 ③)·탐색 계획(expl §1)이며 <b>LLM 왕복을
     * 줄이려는</b> 캐시다. 키가 각각 입력 사실 문자열·템플릿 본문·(업종+관심사)라 내용이 같으면
     * 같은 결과가 나오고, 슬라이더가 예산을 되돌릴 때 왕복이 사라진다. 최대 크기를 두는 이유도
     * 여기 있다: 예산·후보 조합만큼 키가 늘 수 있어 무한정 쌓게 두지 않는다.
     * ({@code plans} 만은 프롬프트 변수가 업종 2종 × 관심사 8종이라 키가 최대 16개다.)
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(
                "candidates", "products", "reviews", "refines", "plans");
        manager.setCaffeine(
                Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1)).maximumSize(500));
        return manager;
    }
}

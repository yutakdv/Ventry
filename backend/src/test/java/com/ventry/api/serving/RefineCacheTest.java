package com.ventry.api.serving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.ventry.api.llm.LlmClient;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * {@link RefineGenerator} 의 <b>캐시 프록시 위</b> 동작.
 *
 * <p>보통 캐시 설정은 테스트할 것이 없지만 여기는 다르다 — {@code @Cacheable} 의 SpEL 은
 * <b>프록시가 붙어야만</b> 평가되는데, 운영 캐시 설정({@code CacheConfig})이
 * {@code @Profile("db")} 라 일반 단위 테스트에는 프록시 자체가 없다. 그래서 조건식이 틀려도
 * 테스트가 전부 통과한다.
 *
 * <p>실제로 그랬다. {@code unless = "#result.isEmpty()"} 는 스프링이 Optional 반환값을
 * <b>벗겨서</b> SpEL 에 넘긴다는 사실 때문에 empty 일 때 {@code #result} 가 null 이 되고,
 * <b>폴백 경로에서만</b> 예외로 터졌다 — 성공 경로는 멀쩡했다. 하필 그 폴백이
 * 「LLM 이 죽어도 화면은 산다」를 지키는 경로라 무LLM 스택의 SSE 가 done 을 못 보내고 끊겼다.
 * 통합 QA(F2)가 잡았고, 이 테스트는 그것을 단위 층으로 내린다.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = RefineCacheTest.CachingContext.class)
class RefineCacheTest {

    private static final String BODY = "진입 가능 후보는 382곳에서 1,014곳으로 늘어납니다.";
    private static final String REFINED = "진입 가능한 후보가 382곳에서 1,014곳으로 늘어납니다.";

    /** 호출 횟수를 세는 스텁 — 캐시 적중 여부를 이걸로 본다. */
    static final AtomicInteger CALLS = new AtomicInteger();
    static volatile String response;

    @Configuration
    @EnableCaching
    static class CachingContext {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("refines");
        }

        @Bean
        LlmClient llm() {
            return new LlmClient() {
                @Override
                public Optional<String> complete(String prompt) {
                    CALLS.incrementAndGet();
                    return Optional.ofNullable(response);
                }

                @Override
                public boolean enabled() {
                    return response != null;
                }
            };
        }

        @Bean
        RefineGenerator refineGenerator(LlmClient llm) {
            return new RefineGenerator(llm);
        }
    }

    @Autowired
    private RefineGenerator generator;

    /** 이 테스트가 존재하는 이유 — 폴백에서 SpEL 이 터지면 SSE 전체가 끊긴다. */
    @Test
    @DisplayName("언어화 실패는 예외 없이 empty 로 수렴한다 (캐시 조건식 포함)")
    void fallbackDoesNotThrowThroughCacheProxy() {
        CALLS.set(0);
        response = null;   // 무LLM

        assertThatCode(() -> assertThat(generator.refine(BODY + " 무LLM")).isEmpty())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("성공은 캐시되고 두 번째 호출은 왕복하지 않는다")
    void successIsCached() {
        CALLS.set(0);
        response = REFINED;
        String body = BODY + " 성공";

        assertThat(generator.refine(body)).contains(REFINED);
        assertThat(generator.refine(body)).contains(REFINED);
        assertThat(CALLS.get()).as("두 번째는 캐시 적중이라 왕복이 없다").isEqualTo(1);
    }

    /**
     * 폴백은 캐시하지 않는다 — 일시적 타임아웃 한 번이 그 문장의 언어화를 1시간 봉인하면
     * 데모 중 한 번 삐끗한 것이 계속 삐끗한다 (리스크 검증 D-12 와 같은 이유).
     */
    @Test
    @DisplayName("폴백은 캐시하지 않아 다음 호출이 다시 시도한다")
    void fallbackIsNotCached() {
        CALLS.set(0);
        response = null;
        String body = BODY + " 재시도";

        assertThat(generator.refine(body)).isEmpty();
        response = REFINED;                                   // LLM 이 돌아왔다
        assertThat(generator.refine(body)).contains(REFINED);  // 봉인돼 있지 않다
        assertThat(CALLS.get()).isEqualTo(2);
    }
}

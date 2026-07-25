package com.ventry.api.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * BE-04 — LLM 골격 검증. 핵심 계약: <b>모든 실패가 Optional.empty() 하나로 수렴</b>해
 * 호출부가 분기 없이 템플릿 폴백을 타는 것 (expl §2-5, 리스크 #9).
 */
class LlmClientTest {

    // ── 무LLM 모드 ────────────────────────────────────────────────────────
    @Test
    void noLlmClient_alwaysReturnsEmpty_soCallerFallsBackToTemplate() {
        NoLlmClient client = new NoLlmClient("");
        assertThat(client.complete("아무 프롬프트")).isEmpty();
        assertThat(client.enabled()).isFalse();
        assertThat(client.apiKeyPresent()).isFalse();
    }

    /** 키가 주입돼도 이 구현은 호출하지 않는다 — 실제 호출은 BE-05 클라이언트 몫. */
    @Test
    void noLlmClient_withApiKey_stillReturnsEmptyButReportsKeyPresent() {
        NoLlmClient client = new NoLlmClient("sk-test-key");
        assertThat(client.complete("프롬프트")).isEmpty();
        assertThat(client.enabled()).isFalse();
        assertThat(client.apiKeyPresent()).isTrue();
    }

    // ── 설정 기본값 ───────────────────────────────────────────────────────
    @Test
    void settings_applyDefaultTimeoutAndConcurrency() {
        LlmSettings settings = new LlmSettings("key", null, 0);
        assertThat(settings.timeout()).isEqualTo(LlmSettings.DEFAULT_TIMEOUT);   // 5s
        assertThat(settings.maxConcurrent()).isEqualTo(LlmSettings.DEFAULT_MAX_CONCURRENT);
        assertThat(settings.hasApiKey()).isTrue();
    }

    @Test
    void settings_blankKeyMeansNoLlmMode() {
        assertThat(LlmSettings.fromEnv("").hasApiKey()).isFalse();
        assertThat(LlmSettings.fromEnv(null).hasApiKey()).isFalse();
        assertThat(LlmSettings.fromEnv("  ").hasApiKey()).isFalse();
    }

    // ── 가드 동작 (BE-05 실클라이언트가 상속할 구조) ────────────────────────
    @Test
    void guarded_returnsValue_whenCallSucceedsWithinTimeout() {
        GuardedLlmClient client = stub(settings(Duration.ofSeconds(5), 4), p -> "다듬은 문장");
        assertThat(client.complete("프롬프트")).contains("다듬은 문장");
        assertThat(client.enabled()).isTrue();
    }

    @Test
    void guarded_withoutApiKey_shortCircuitsToEmpty() {
        GuardedLlmClient client = stub(new LlmSettings("", Duration.ofSeconds(5), 4), p -> "안 불림");
        assertThat(client.complete("프롬프트")).isEmpty();
    }

    /** 타임아웃 초과 → 폴백 (결과를 기다리지 않는다). */
    @Test
    void guarded_timesOut_returnsEmpty() {
        GuardedLlmClient client = stub(settings(Duration.ofMillis(50), 4), p -> {
            Thread.sleep(2000);
            return "늦은 응답";
        });
        assertThat(client.complete("프롬프트")).isEmpty();
    }

    /** 호출 실패(예외)도 폴백으로 흡수 — complete는 예외를 던지지 않는다. */
    @Test
    void guarded_callThrows_returnsEmpty() {
        GuardedLlmClient client = stub(settings(Duration.ofSeconds(5), 4), p -> {
            throw new IllegalStateException("API 장애");
        });
        assertThat(client.complete("프롬프트")).isEmpty();
    }

    @Test
    void guarded_nullResult_returnsEmpty() {
        GuardedLlmClient client = stub(settings(Duration.ofSeconds(5), 4), p -> null);
        assertThat(client.complete("프롬프트")).isEmpty();
    }

    /** 동시 호출 상한(K=1) 초과분은 대기하지 않고 즉시 폴백한다. */
    @Test
    void guarded_exceedingConcurrencyLimit_fallsBackImmediately() throws Exception {
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        GuardedLlmClient client = stub(settings(Duration.ofSeconds(5), 1), p -> {
            inFlight.countDown();
            release.await(2, TimeUnit.SECONDS);
            return "첫 번째 응답";
        });

        Thread first = new Thread(() -> client.complete("프롬프트1"));
        first.start();
        assertThat(inFlight.await(2, TimeUnit.SECONDS)).isTrue();   // 첫 호출이 점유 중

        Optional<String> second = client.complete("프롬프트2");     // 상한 초과 → 즉시 empty
        assertThat(second).isEmpty();

        release.countDown();
        first.join(3000);
    }

    // ── 테스트 헬퍼 ───────────────────────────────────────────────────────
    private static LlmSettings settings(Duration timeout, int maxConcurrent) {
        return new LlmSettings("sk-test-key", timeout, maxConcurrent);
    }

    /** BE-05 실클라이언트 자리에 끼우는 스텁 — call() 하나만 구현하면 되는 구조를 검증한다. */
    private static GuardedLlmClient stub(LlmSettings settings, StubCall call) {
        return new GuardedLlmClient(settings) {
            @Override
            protected String call(String prompt) throws Exception {
                return call.apply(prompt);
            }
        };
    }

    @FunctionalInterface
    private interface StubCall {
        String apply(String prompt) throws Exception;
    }
}

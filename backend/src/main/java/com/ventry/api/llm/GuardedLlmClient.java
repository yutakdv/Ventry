package com.ventry.api.llm;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BE-04 — 타임아웃·동시성 가드를 공통 처리하는 LLM 클라이언트 기반 클래스.
 * <b>BE-05의 실제 클라이언트는 {@link #call(String)} 하나만 구현하면 된다</b> —
 * 폴백 규약(모든 실패 → {@code Optional.empty()})은 여기서 이미 보장된다.
 *
 * <p>가드: ① 무LLM 모드면 즉시 empty ② 동시 호출 K건 초과면 empty(대기하지 않음 — 응답 지연이
 * 템플릿 폴백보다 나쁘다) ③ 타임아웃 초과·예외도 empty. 호출은 전용 스레드에서 수행해
 * 톰캣 워커를 점유하지 않는다 (expl §5).
 */
public abstract class GuardedLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(GuardedLlmClient.class);

    private final LlmSettings settings;
    private final Semaphore semaphore;
    private final ExecutorService executor;

    protected GuardedLlmClient(LlmSettings settings) {
        this.settings = settings;
        this.semaphore = new Semaphore(settings.maxConcurrent());
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public boolean enabled() {
        return settings.hasApiKey();
    }

    @Override
    public final Optional<String> complete(String prompt) {
        return complete(prompt, settings.timeout());
    }

    /**
     * 호출 지점별 상한을 받는 본체.
     *
     * <p>모든 종료 경로가 {@code outcome} 한 줄을 남긴다. 이 서비스는 LLM이 죽어도 템플릿으로
     * 멀쩡히 동작하므로, 로그가 없으면 <b>LLM이 실질적으로 죽어 있어도 아무도 모른다</b>
     * (AI 리뷰 M-03). {@code outcome} 은 폴백의 원인을 구분하고 — 이전에는 타임아웃과 검증
     * 거부가 같은 문장을 남겼다 — {@code elapsed_ms} 는 상한값의 타당성을 사후 검증한다.
     */
    @Override
    public final Optional<String> complete(String prompt, Duration timeout) {
        if (!enabled()) {
            return Optional.empty();                 // 무LLM 모드 — 템플릿이 최종본
        }
        if (!semaphore.tryAcquire()) {
            // 대기 대신 폴백. 이 줄이 잦으면 K가 병목이라는 뜻이다.
            log.info("llm call outcome=saturated limit={}", settings.maxConcurrent());
            return Optional.empty();
        }
        Duration limit = (timeout == null || timeout.isNegative() || timeout.isZero())
                ? settings.timeout() : timeout;
        long startedAt = System.nanoTime();
        Future<String> task = executor.submit(() -> call(prompt));
        String outcome = "ok";
        try {
            String body = task.get(limit.toMillis(), TimeUnit.MILLISECONDS);
            if (body == null) {
                outcome = "empty";
            }
            return Optional.ofNullable(body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.cancel(true);
            outcome = "interrupted";
            return Optional.empty();
        } catch (TimeoutException e) {
            task.cancel(true);                       // 상한 초과 — 결과를 버리고 폴백
            outcome = "timeout";
            return Optional.empty();
        } catch (Exception e) {
            task.cancel(true);                       // 호출 실패 — 결과를 버리고 폴백
            outcome = "error";
            return Optional.empty();
        } finally {
            semaphore.release();
            log.info("llm call outcome={} elapsed_ms={} limit_ms={}",
                    outcome, (System.nanoTime() - startedAt) / 1_000_000L, limit.toMillis());
        }
    }

    /**
     * 실제 LLM 호출 (BE-05에서 구현). 예외를 던져도 되며, 상위에서 폴백으로 흡수된다.
     * 반환이 null이면 폴백으로 처리된다.
     */
    protected abstract String call(String prompt) throws Exception;
}

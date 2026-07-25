package com.ventry.api.llm;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

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
        if (!enabled()) {
            return Optional.empty();                 // 무LLM 모드 — 템플릿이 최종본
        }
        if (!semaphore.tryAcquire()) {
            return Optional.empty();                 // 동시 호출 상한 초과 — 대기 대신 폴백
        }
        Future<String> task = executor.submit(() -> call(prompt));
        try {
            return Optional.ofNullable(task.get(settings.timeout().toMillis(), TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.cancel(true);
            return Optional.empty();
        } catch (Exception e) {
            task.cancel(true);                       // 타임아웃·호출 실패 — 결과를 버리고 폴백
            return Optional.empty();
        } finally {
            semaphore.release();
        }
    }

    /**
     * 실제 LLM 호출 (BE-05에서 구현). 예외를 던져도 되며, 상위에서 폴백으로 흡수된다.
     * 반환이 null이면 폴백으로 처리된다.
     */
    protected abstract String call(String prompt) throws Exception;
}

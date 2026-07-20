package com.ventry.api.common;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 공통 실행기 (expl §5): 송출은 전용 스레드에서 수행 — 톰캣 워커 스레드에서
 * LLM 대기 금지. 15s 하트비트 comment 프레임으로 프록시 타임아웃 방지 (계약 §시스템 계약).
 */
@Component
public class SseSupport {

    private static final long TIMEOUT_MS = 60_000L;
    private static final long HEARTBEAT_SEC = 15L;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();

    public SseEmitter run(SseBody body) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        ScheduledFuture<?> hb = heartbeat.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception ignored) {
                // 이미 완료/끊긴 emitter — onCompletion에서 취소된다
            }
        }, HEARTBEAT_SEC, HEARTBEAT_SEC, TimeUnit.SECONDS);
        emitter.onCompletion(() -> hb.cancel(true));
        emitter.onTimeout(() -> hb.cancel(true));
        emitter.onError(e -> hb.cancel(true));

        executor.submit(() -> {
            try {
                body.emit(emitter);
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        heartbeat.shutdownNow();
    }

    @FunctionalInterface
    public interface SseBody {
        void emit(SseEmitter emitter) throws Exception;
    }
}

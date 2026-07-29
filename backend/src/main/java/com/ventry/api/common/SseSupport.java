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
    /**
     * 하트비트 스케줄러는 <b>1스레드로 충분하다</b> — 실제 쓰기는 가상 스레드로 넘기기 때문이다.
     *
     * <p>이전에는 4스레드를 두고 스케줄러 스레드가 직접 {@code send} 했다. {@code SseEmitter.send}
     * 는 블로킹 쓰기라, 소비가 느린 클라이언트(모바일 회선·백그라운드 탭)에서 TCP 송신 버퍼가
     * 차면 그 호출이 스케줄러 스레드를 붙잡는다. 즉 「한 emitter 의 send 가 막혀도 다른 스트림이
     * 멈추지 않는다」는 보장이 <b>느린 클라이언트 4명까지만</b> 성립했다 (BE 리뷰 M-02).
     * 지금은 스케줄러가 제출만 하고 즉시 반환하므로 그 상한이 사라진다.
     */
    private final ScheduledExecutorService heartbeat = Executors.newScheduledThreadPool(1);

    /**
     * SSE 스트림 1개를 띄운다.
     *
     * <p><b>emitter 는 스레드 안전하지 않다</b>. 하트비트 스레드와 본문 스레드가 같은 emitter 에
     * 동시에 {@code send} 하면 프레임이 섞여 클라이언트가 파싱하지 못하는 조각이 나갈 수 있다.
     * emitter 별 락으로 직렬화한다 (BE 리뷰 D-14). 락은 스트림당 하나라 경합 범위가 그 스트림에
     * 국한된다.
     */
    public SseEmitter run(SseBody body) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Object lock = new Object();
        // 블로킹 쓰기를 가상 스레드로 이관한다 — 스케줄러 스레드는 제출만 하고 즉시 반환하므로
        // 느린 클라이언트가 몇이든 서로의 하트비트를 굶기지 않는다 (M-02).
        ScheduledFuture<?> hb = heartbeat.scheduleAtFixedRate(
                () -> executor.submit(() -> {
                    try {
                        synchronized (lock) {
                            emitter.send(SseEmitter.event().comment("heartbeat"));
                        }
                    } catch (Exception ignored) {
                        // 이미 완료/끊긴 emitter — onCompletion에서 취소된다
                    }
                }),
                HEARTBEAT_SEC, HEARTBEAT_SEC, TimeUnit.SECONDS);
        emitter.onCompletion(() -> hb.cancel(true));
        emitter.onTimeout(() -> hb.cancel(true));
        emitter.onError(e -> hb.cancel(true));

        executor.submit(() -> {
            try {
                body.emit(new LockedEmitter(emitter, lock));
                synchronized (lock) {
                    emitter.complete();
                }
            } catch (Exception e) {
                synchronized (lock) {
                    emitter.completeWithError(e);
                }
            }
        });
        return emitter;
    }

    /**
     * 본문 송출을 하트비트와 직렬화하는 래퍼. 호출부는 {@link SseEmitter} 를 그대로 쓰고,
     * 락은 여기서만 잡는다 — 각 컨트롤러가 동기화를 기억할 필요가 없다.
     */
    private static final class LockedEmitter extends SseEmitter {
        private final SseEmitter delegate;
        private final Object lock;

        private LockedEmitter(SseEmitter delegate, Object lock) {
            super(TIMEOUT_MS);
            this.delegate = delegate;
            this.lock = lock;
        }

        @Override
        public void send(SseEventBuilder builder) throws java.io.IOException {
            synchronized (lock) {
                delegate.send(builder);
            }
        }

        @Override
        public void send(Object object) throws java.io.IOException {
            synchronized (lock) {
                delegate.send(object);
            }
        }

        @Override
        public void send(Object object, org.springframework.http.MediaType mediaType)
                throws java.io.IOException {
            synchronized (lock) {
                delegate.send(object, mediaType);
            }
        }
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

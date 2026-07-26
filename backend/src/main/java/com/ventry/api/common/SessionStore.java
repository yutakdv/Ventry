package com.ventry.api.common;

import com.ventry.api.diagnose.DiagnoseDtos.ParsedProfile;
import com.ventry.api.scenario.ScenarioDtos.CompositionItem;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 인메모리 세션 저장소 (BE-01). B₀ 구성(자기자본+상품별 사용액) 기록은
 * expl §2-2 잔여 한도 원칙의 재료 — BE-04 조달 검증이 소비한다.
 */
@Component
public class SessionStore {

    private static final Duration TTL = Duration.ofMinutes(60);

    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    public SessionState create(ParsedProfile profile) {
        String id = UUID.randomUUID().toString();
        SessionState state = new SessionState(id, profile);
        sessions.put(id, state);
        return state;
    }

    /**
     * 만료 세션 일괄 정리 — <b>다시 찾아오지 않는 세션</b>을 지운다 (BE 리뷰 D-16).
     *
     * <p>제거가 접근 시점(lazy)뿐이면 한 번 만들고 이탈한 세션은 영원히 남는다. 데모·심사 동안
     * 인메모리 맵이 단조 증가하는 것을 막는 최소 조치다. 세션 저장소가 인메모리·단일 인스턴스
     * 전제라는 사실 자체는 그대로다(예선 스코프).
     */
    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
    void evictExpired() {
        sessions.values().removeIf(SessionState::expired);
    }

    /** 만료 세션은 접근 시점에 제거(lazy eviction) 후 404. */
    public SessionState get(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null || state.expired()) {
            sessions.remove(sessionId);
            throw ApiException.sessionNotFound(sessionId);
        }
        state.touch();
        return state;
    }

    public static class SessionState {
        private final String id;
        private final ParsedProfile profile;
        private final AtomicLong version = new AtomicLong(0); // /explore·/recommend 취소 규약 공유
        private volatile Integer confirmedBudget;
        private volatile List<CompositionItem> composition;   // B₀ 구성
        private volatile Instant lastAccessedAt = Instant.now();

        SessionState(String id, ParsedProfile profile) {
            this.id = id;
            this.profile = profile;
        }

        public String id() {
            return id;
        }

        public ParsedProfile profile() {
            return profile;
        }

        /** 프론트가 전달한 version을 최신으로 반영하고, 구 버전 여부를 돌려준다. */
        public boolean acceptVersion(long v) {
            long latest = version.updateAndGet(cur -> Math.max(cur, v));
            return v >= latest;
        }

        public long latestVersion() {
            return version.get();
        }

        public void confirmBudget(int budget, List<CompositionItem> composition) {
            this.confirmedBudget = budget;
            this.composition = composition;
        }

        public Integer confirmedBudget() {
            return confirmedBudget;
        }

        public List<CompositionItem> composition() {
            return composition;
        }

        boolean expired() {
            return lastAccessedAt.plus(TTL).isBefore(Instant.now());
        }

        void touch() {
            lastAccessedAt = Instant.now();
        }
    }
}

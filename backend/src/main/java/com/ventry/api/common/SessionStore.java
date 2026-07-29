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

    /**
     * 동시 보관 세션 상한 (BE 리뷰 2026-07-29 C-02).
     *
     * <p>{@code POST /api/diagnose} 는 인증이 없고 호출 한 번에 세션 하나를 만든다. TTL 60분 ·
     * 스윕 10분 주기만으로는 <b>만료 전 구간의 생성 속도</b>에 상한이 없어, 반복 호출이 그대로
     * 힙 증가로 이어진다 — 단일 인스턴스 인메모리 저장소라 그 끝은 OOM 이고 그 시점에 진행 중인
     * 모든 세션이 함께 사라진다.
     *
     * <p>상한은 넉넉하게 잡는다. 데모·심사 동선의 동시 세션은 두 자릿수이고, 1만 건이면 정상
     * 사용이 닿지 않으면서 폭주는 확실히 끊긴다.
     */
    static final int MAX_SESSIONS = 10_000;

    /**
     * 슬라이더 version 의 상한 (QA 리뷰 2026-07-29 Q-02). 화면 2·3의 슬라이더는 변경 1회에
     * 1씩 올리며, TTL 60분 세션에서 사람 손이 닿을 수 있는 자리가 아니다.
     */
    static final long MAX_VERSION = 1_000_000L;

    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    /**
     * 세션 발급. 상한에 닿으면 <b>만료분을 먼저 걷어내고</b>, 그래도 자리가 없으면 429로 거절한다 —
     * 조용히 받아 두었다가 힙이 무너지는 것보다 그 요청 하나가 실패하는 편이 낫다.
     */
    public SessionState create(ParsedProfile profile) {
        if (sessions.size() >= MAX_SESSIONS) {
            evictExpired();
            if (sessions.size() >= MAX_SESSIONS) {
                throw ApiException.tooManySessions();
            }
        }
        String id = UUID.randomUUID().toString();
        SessionState state = new SessionState(id, profile);
        sessions.put(id, state);
        return state;
    }

    /** 현재 보관 중인 세션 수 — 상한 동작 검증용. */
    int size() {
        return sessions.size();
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

        /**
         * 프론트가 전달한 version을 최신으로 반영하고, 구 버전 여부를 돌려준다.
         *
         * <p><b>규격 밖의 값은 반영하기 전에 막는다</b> (QA 리뷰 2026-07-29 Q-02). version 은
         * 단조 증가만 하는 값이라 한 번 반영되면 내려가지 않는데, 검증이 없어 두 방향으로 샜다:
         * <ul>
         *   <li>{@code ?v=-1} → 세션 최신(0)보다 작아 <b>이벤트 0건인 정상 종료 스트림</b>이 나갔다.
         *       프론트는 그것을 오류로 보고 목 폴백을 켜므로, 잘못된 입력이 <b>지어낸 인사이트</b>로
         *       화면에 도달한다 (`api/client.ts` {@code es.onerror} → {@code mockExplore}).</li>
         *   <li>{@code ?v=<거대값>} → 이후 정상 요청이 전부 구 버전이 되어 그 세션의 탐색이
         *       TTL(60분) 동안 되살아나지 않는다.</li>
         * </ul>
         *
         * <p>상한은 <b>피해 범위를 줄일 뿐 봉인하지는 않는다</b> — 상한 안의 값으로도 같은 독점이
         * 가능하다. 완전한 해소는 version 을 서버가 발급하도록 바꾸는 것이고 그것은 계약 변경이라
         * 여기서 단독으로 하지 않는다(CONTRIBUTING §6). 상한 값은 슬라이더 조작이 60분 세션 안에
         * 닿을 수 없는 자리다 — {@code AGE_MAX} 와 같은 성격의 오타·조작 차단선이다.
         */
        public boolean acceptVersion(long v) {
            if (v < 0 || v > MAX_VERSION) {
                throw ApiException.invalidRequest(
                        "v 는 0~" + MAX_VERSION + " 범위의 정수여야 합니다: " + v);
            }
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

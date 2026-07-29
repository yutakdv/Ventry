package com.ventry.api.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ventry.api.diagnose.DiagnoseDtos.ParsedProfile;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 세션 저장소 상한 (BE 리뷰 2026-07-29 C-02).
 *
 * <p>{@code POST /api/diagnose} 는 인증이 없어 호출 수에 상한이 없다. TTL·스윕만으로는 만료 전
 * 구간의 생성 속도를 막지 못해, 인메모리 맵이 힙이 버티는 데까지 자란다.
 */
class SessionStoreTest {

    private static final ParsedProfile PROFILE = new ParsedProfile(
            32, 5000, false, true, 250, "cafe", "서울 마포구", List.of(), "form_only");

    @Test
    void createsSessionsUntilLimit_thenRejectsWith429() {
        SessionStore store = new SessionStore();
        for (int i = 0; i < SessionStore.MAX_SESSIONS; i++) {
            store.create(PROFILE);
        }
        assertThat(store.size()).isEqualTo(SessionStore.MAX_SESSIONS);

        assertThatThrownBy(() -> store.create(PROFILE))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.code()).isEqualTo("TOO_MANY_SESSIONS");
                });
    }

    @Test
    void unknownSession_throwsNotFound() {
        SessionStore store = new SessionStore();
        assertThatThrownBy(() -> store.get("없는-세션"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    /** 발급된 세션은 정상 조회되고, 예산 확정 결과가 그대로 보관된다. */
    @Test
    void storesConfirmedBudget() {
        SessionStore store = new SessionStore();
        SessionStore.SessionState state = store.create(PROFILE);
        state.confirmBudget(8000, List.of());
        assertThat(store.get(state.id()).confirmedBudget()).isEqualTo(8000);
    }
}

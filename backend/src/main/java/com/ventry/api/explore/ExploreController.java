package com.ventry.api.explore;

import com.ventry.api.common.MockData;
import com.ventry.api.common.SessionStore;
import com.ventry.api.common.SseSupport;
import com.ventry.api.serving.SessionMapper;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * GET /api/explore/{sid}?v= — 이벤트 순서: plan → insight(1건씩) → refine(선택) → done.
 * BE-01 목: 데모 시나리오(expl §8) 고정 송출. version 취소 규약(expl §5): 이벤트 송출 직전
 * 세션 최신 version과 비교해 구 버전이면 폐기 — LLM plan/refine 실구현은 BE-05.
 */
@RestController
public class ExploreController {

    private final SessionStore sessions;
    private final SseSupport sse;

    public ExploreController(SessionStore sessions, SseSupport sse) {
        this.sessions = sessions;
        this.sse = sse;
    }

    @GetMapping(value = "/api/explore/{sid}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter explore(@PathVariable String sid,
                              @RequestParam(name = "v", defaultValue = "0") long version) {
        SessionStore.SessionState state = sessions.get(sid);
        state.acceptVersion(version);
        return sse.run(emitter -> {
            if (stale(state, version)) {
                return; // 구 버전 요청 — 아무 이벤트도 보내지 않고 종료
            }
            emitter.send(SseEmitter.event().name("plan")
                    .data(MockData.explorePlan(), MediaType.APPLICATION_JSON));
            emitter.send(SseEmitter.event().name("insight")
                    .data(MockData.insightT1(), MediaType.APPLICATION_JSON));
            emitter.send(SseEmitter.event().name("insight")
                    .data(MockData.insightT2(), MediaType.APPLICATION_JSON));
            if (!stale(state, version)) {
                emitter.send(SseEmitter.event().name("refine")
                        .data(MockData.refineT1(), MediaType.APPLICATION_JSON));
            }
            emitter.send(SseEmitter.event().name("done")
                    .data(MockData.exploreDone(SessionMapper.budget(state)),
                            MediaType.APPLICATION_JSON));
        });
    }

    private boolean stale(SessionStore.SessionState state, long version) {
        return version < state.latestVersion();
    }
}

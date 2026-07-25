package com.ventry.api.explore;

import com.ventry.api.common.SessionStore;
import com.ventry.api.common.SseSupport;
import com.ventry.api.explore.ExploreDtos.InsightEvent;
import com.ventry.api.serving.ExploreService;
import com.ventry.api.serving.ExploreService.ExplorePayload;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * GET /api/explore/{sid}?v= — 이벤트 순서: plan → insight(1건씩) → refine(선택) → done.
 * 수치는 전부 결정적 계산({@link ExploreService})이며 LLM은 plan·refine 언어화에만 개입한다.
 *
 * <p>version 취소 규약(expl §5): 이벤트 송출 직전마다 세션 최신 version과 비교해 구 버전이면
 * 폐기한다 — 슬라이더 연타 시 구 응답이 새 응답을 덮어쓰지 못하게 한다. refine은 무LLM 모드에서
 * 미송출이 정상이며 계약상 "(선택적)"이라 규격을 준수한다 ([6]단계에서 LLM 교체로 붙는다).
 */
@RestController
public class ExploreController {

    private final SessionStore sessions;
    private final SseSupport sse;
    private final ExploreService explore;

    public ExploreController(SessionStore sessions, SseSupport sse, ExploreService explore) {
        this.sessions = sessions;
        this.sse = sse;
        this.explore = explore;
    }

    @GetMapping(value = "/api/explore/{sid}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter explore(@PathVariable String sid,
                              @RequestParam(name = "v", defaultValue = "0") long version) {
        SessionStore.SessionState state = sessions.get(sid);
        state.acceptVersion(version);
        ExplorePayload payload = explore.explore(state);   // 결정적 계산 (송출 밖에서 1회)
        return sse.run(emitter -> {
            if (stale(state, version)) {
                return;   // 구 버전 요청 — 아무 이벤트도 보내지 않고 종료
            }
            emitter.send(SseEmitter.event().name("plan")
                    .data(payload.plan(), MediaType.APPLICATION_JSON));
            for (InsightEvent insight : payload.insights()) {
                if (stale(state, version)) {
                    return;
                }
                emitter.send(SseEmitter.event().name("insight")
                        .data(insight, MediaType.APPLICATION_JSON));
            }
            // refine(LLM 언어화 교체)은 [6]단계에서 붙는다 — 무LLM 모드에서는 미송출이 규격 준수.
            if (!stale(state, version)) {
                emitter.send(SseEmitter.event().name("done")
                        .data(payload.done(), MediaType.APPLICATION_JSON));
            }
        });
    }

    private boolean stale(SessionStore.SessionState state, long version) {
        return version < state.latestVersion();
    }
}

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
        return sse.run(emitter -> {
            if (stale(state, version)) {
                return;   // 구 버전 요청 — 아무 이벤트도 보내지 않고 종료
            }
            // 계산(그 안의 LLM 계획 호출 포함)을 **송출 스레드 안에서** 한다. 밖에 두면 톰캣
            // 워커가 LLM 왕복 동안 점유돼, SseSupport 자신의 주석(「워커 스레드에서 LLM 대기
            // 금지」)을 어긴다 — 동시 요청이 몇 개만 겹쳐도 워커가 고갈된다 (BE 리뷰 D-10).
            // TTFB 자체는 그대로다. **축 목록**을 폴백으로 먼저 보내고 나중에 교체하는 것은
            // 축이 바뀌어도 되는지 FE 와 확인이 필요해 여전히 범위 밖이다 — 아래 refine 은
            // 인사이트 **문장** 교체이며 계약이 이미 규정한 별개 이벤트다.
            ExplorePayload payload = explore.explore(state);
            emitter.send(SseEmitter.event().name("plan")
                    .data(payload.plan(), MediaType.APPLICATION_JSON));
            for (InsightEvent insight : payload.insights()) {
                if (stale(state, version)) {
                    return;
                }
                emitter.send(SseEmitter.event().name("insight")
                        .data(insight, MediaType.APPLICATION_JSON));
            }
            // LLM 언어화(역할 ②)는 **템플릿 문장이 나간 뒤에** 부른다 — 여기서 왕복이 나야
            // 「템플릿 즉시 → 선택적 교체」(expl §2-5)가 성립한다. 언어화가 실패하면 이벤트가
            // 없을 뿐이고 템플릿이 최종본으로 남는다(계약이 refine 을 선택적 이벤트로 규정).
            for (ExploreDtos.RefineEvent refine : explore.refine(payload.insights())) {
                if (stale(state, version)) {
                    return;
                }
                emitter.send(SseEmitter.event().name("refine")
                        .data(refine, MediaType.APPLICATION_JSON));
            }
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

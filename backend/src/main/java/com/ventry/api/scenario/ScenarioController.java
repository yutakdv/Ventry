package com.ventry.api.scenario;

import com.ventry.api.common.MockData;
import com.ventry.api.common.SessionStore;
import com.ventry.api.common.SseSupport;
import com.ventry.api.scenario.ScenarioDtos.BudgetRequest;
import com.ventry.api.scenario.ScenarioDtos.BudgetResponse;
import com.ventry.api.scenario.ScenarioDtos.ScenarioCard;
import com.ventry.api.scenario.ScenarioDtos.ScenarioDone;
import com.ventry.api.serving.LocationService;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 계약 2번(시나리오 SSE)·3번(예산 확정). 이벤트 순서: scenario ×2 → done. */
@RestController
public class ScenarioController {

    private final SessionStore sessions;
    private final SseSupport sse;
    private final LocationService locationService;

    public ScenarioController(SessionStore sessions, SseSupport sse,
                              LocationService locationService) {
        this.sessions = sessions;
        this.sse = sse;
        this.locationService = locationService;
    }

    @GetMapping(value = "/api/scenarios/{sid}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter scenarios(@PathVariable String sid) {
        sessions.get(sid); // 세션 검증 — 없으면 404
        List<ScenarioCard> cards = MockData.scenarios();
        return sse.run(emitter -> {
            for (ScenarioCard card : cards) {
                emitter.send(SseEmitter.event().name("scenario")
                        .data(card, MediaType.APPLICATION_JSON));
            }
            emitter.send(SseEmitter.event().name("done")
                    .data(new ScenarioDone(cards.size()), MediaType.APPLICATION_JSON));
        });
    }

    /**
     * B₀ 구성을 세션에 기록 (expl §2-2 잔여 한도 원칙 — BE-04 조달 검증의 재료) +
     * 확정 예산 기준 프리뷰 반환 (DECISIONS.md §9).
     * B₀는 덮어쓰기다 — 화면 2 슬라이더는 debounce 후 이 엔드포인트를 반복 호출하고
     * 마지막 값이 확정값이 된다.
     */
    @PostMapping("/api/budget/{sid}")
    public BudgetResponse confirmBudget(@PathVariable String sid,
                                        @RequestBody BudgetRequest request) {
        SessionStore.SessionState state = sessions.get(sid);
        state.confirmBudget(request.confirmedBudget(), request.composition());
        return new BudgetResponse(request.confirmedBudget(), request.composition(),
                locationService.preview(request.confirmedBudget()));
    }
}

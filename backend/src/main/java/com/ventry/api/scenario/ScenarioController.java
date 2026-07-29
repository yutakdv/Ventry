package com.ventry.api.scenario;

import com.ventry.api.common.Amounts;
import com.ventry.api.common.ApiException;
import com.ventry.api.common.SessionStore;
import com.ventry.api.common.SseSupport;
import com.ventry.api.scenario.ScenarioDtos.BudgetRequest;
import com.ventry.api.scenario.ScenarioDtos.BudgetResponse;
import com.ventry.api.scenario.ScenarioDtos.ScenarioCard;
import com.ventry.api.scenario.ScenarioDtos.ScenarioDone;
import com.ventry.api.serving.LocationService;
import com.ventry.api.serving.ScenarioBuilder;
import com.ventry.api.serving.SessionMapper;
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
    private final ScenarioBuilder scenarioBuilder;

    public ScenarioController(SessionStore sessions, SseSupport sse,
                              LocationService locationService, ScenarioBuilder scenarioBuilder) {
        this.sessions = sessions;
        this.sse = sse;
        this.locationService = locationService;
        this.scenarioBuilder = scenarioBuilder;
    }

    @GetMapping(value = "/api/scenarios/{sid}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter scenarios(@PathVariable String sid) {
        SessionStore.SessionState state = sessions.get(sid);   // 없으면 404
        List<ScenarioCard> cards = scenarioBuilder.build(SessionMapper.profile(state));
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
        // 자원 존재 여부를 먼저 본다. 없는 세션 + 잘못된 예산이면 400 이 아니라 404 가 맞다
        // — 고칠 것이 본문인지 주소인지를 응답이 잘못 가리키면 클라이언트가 헛수고를 한다 (N-03).
        SessionStore.SessionState state = sessions.get(sid);
        if (request.confirmedBudget() == null) {
            // 필드 자체가 빠진 경우. 원시형이던 시절에는 Jackson 이 0으로 채워 B₀=0 세션이
            // 만들어지고, 이후 전 화면이 "진입 후보 0곳"으로 정상처럼 흘렀다 (N-03).
            throw ApiException.invalidRequest("confirmed_budget 은 필수입니다.");
        }
        if (request.confirmedBudget() < 0) {
            // 음수 예산은 200으로 통과했다 — 이후 진입 후보 0곳·판정 전건 범위 외로 흘러
            // "예산이 잘못됐다"는 사실이 화면 어디에도 남지 않는다 (BE 리뷰 D-17).
            throw ApiException.invalidRequest("confirmed_budget 은 음수일 수 없습니다.");
        }
        // 상한도 같은 이유로 막는다 — capital 의 오버플로 경로(QA 리뷰 Q-01)와 짝이며,
        // int 최댓값 예산은 「전 후보 진입」이라는 무의미한 프리뷰를 200 으로 돌려준다.
        if (request.confirmedBudget() > Amounts.MAX) {
            throw ApiException.invalidRequest("confirmed_budget 은 " + Amounts.MAX_LABEL
                    + "(만원) 이하여야 합니다: " + request.confirmedBudget());
        }
        validateComposition(request.composition());
        state.confirmBudget(request.confirmedBudget(), request.composition());
        return new BudgetResponse(locationService.previewAsOf(), request.confirmedBudget(),
                request.composition(),
                locationService.preview(SessionMapper.profile(state).industry(),
                        request.confirmedBudget()));
    }

    /**
     * 조달 구성 항목의 금액 검증 (QA 리뷰 Q-04).
     *
     * <p>음수·거대값이 200 으로 통과하고 <b>응답에 그대로 반향</b>됐다. 계산에는 영향이 없다 —
     * {@code UsedLimits.byProduct} 가 {@code amount <= 0} 을 건너뛰고 상품 한도로 클램프한다.
     * 그래서 결함은 「의미 없는 값이 응답에 남는다」이고, 그 응답을 화면이 다시 보여 준다는 점에서
     * 금액 표기의 진실성 문제다. {@code composition} 자체는 계약상 생략 가능하므로 없으면 통과한다.
     */
    private static void validateComposition(List<ScenarioDtos.CompositionItem> composition) {
        if (composition == null) {
            return;
        }
        for (ScenarioDtos.CompositionItem item : composition) {
            if (item == null) {
                continue;
            }
            if (item.amount() < 0 || item.amount() > Amounts.MAX) {
                throw ApiException.invalidRequest("composition[].amount 는 0 이상 "
                        + Amounts.MAX_LABEL + "(만원) 이하여야 합니다: " + item.amount());
            }
        }
    }
}

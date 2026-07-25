package com.ventry.api.serving;

import com.ventry.api.common.SessionStore.SessionState;
import com.ventry.api.engine.FundingInput;
import com.ventry.api.engine.Profile;
import com.ventry.api.explore.ExploreAxis;
import com.ventry.api.explore.ExploreDtos.DoneEvent;
import com.ventry.api.explore.ExploreDtos.InsightEvent;
import com.ventry.api.explore.ExploreDtos.PlanEvent;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * BE-05 — 결정공간 탐색 <b>페이로드 생성</b> (계약 5번 · exploration spec §2~§4).
 * plan → insight → (refine) → done 이벤트의 데이터를 만든다. 실제 SSE 송출과 version 취소는
 * {@link com.ventry.api.explore.ExploreController}가 {@link com.ventry.api.common.SseSupport}
 * 위에서 하고, 이 클래스는 <b>조립·계산 위임만</b> 한다.
 *
 * <p>모든 수치는 {@link InsightBuilder}·{@link FrontierService}가 만든 결정적 계산값이다 —
 * 여기서 새로 계산하지 않는다 (불변 원칙 §0-2-1). LLM plan·refine 실호출은 [6]단계에서 붙는다.
 */
@Service
public class ExploreService {

    /** A1(예산)은 계획과 무관하게 항상 실행한다. A4는 무권리 경계가 있을 때만 (expl §1). */
    private static final String AXIS_BUDGET = "A1";
    private static final String AXIS_PREMIUM = "A4";

    private final InsightBuilder insights;
    private final FrontierService frontier;

    public ExploreService(InsightBuilder insights, FrontierService frontier) {
        this.insights = insights;
        this.frontier = frontier;
    }

    /** 한 번의 탐색 결과 — 컨트롤러가 순서대로 송출한다. refine은 무LLM에서 비어 있다. */
    public record ExplorePayload(PlanEvent plan, List<InsightEvent> insights, DoneEvent done) {}

    public ExplorePayload explore(SessionState state) {
        Profile profile = SessionMapper.profile(state);
        int budget = SessionMapper.budget(state);
        FundingInput funding = SessionMapper.fundingInput(state);
        String industry = profile.industry();

        InsightBuilder.Result result = insights.build(profile, budget, funding, state.composition());

        PlanEvent plan = plan(industry, budget, result);
        DoneEvent done = new DoneEvent(result.scenariosExplored(),
                frontier.frontierPoints(industry), budget);
        return new ExplorePayload(plan, result.insights(), done);
    }

    /**
     * 탐색 계획: 실행한 축과 라벨. 무권리 경계가 있으면 A4를 함께 노출한다 (A1의 부산물, expl §1).
     * 0건 보고일 때는 사유 문장을 rationale로 실어, 화면이 "왜 인사이트가 없는지"를 말하게 한다
     * (계약에 별도 필드가 없으므로 rationale이 그 자리를 맡는다 — assumptions #30).
     */
    private PlanEvent plan(String industry, int budget, InsightBuilder.Result result) {
        boolean hasPremiumBoundary = !frontier.boundariesExPremium(industry, budget).isEmpty();
        List<String> axes = hasPremiumBoundary
                ? List.of(AXIS_BUDGET, AXIS_PREMIUM)
                : List.of(AXIS_BUDGET);
        String rationale = result.emptyReason() != null
                ? result.emptyReason()
                : "예산 축을 기준으로 인접 시나리오의 진입·지속 경계를 검토했습니다.";
        return new PlanEvent(axes, ExploreAxis.labels(axes), rationale);
    }
}

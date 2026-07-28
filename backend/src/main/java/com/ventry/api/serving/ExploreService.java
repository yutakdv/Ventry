package com.ventry.api.serving;

import com.ventry.api.common.SessionStore.SessionState;
import com.ventry.api.engine.FundingInput;
import com.ventry.api.engine.Profile;
import com.ventry.api.explore.ExploreAxis;
import com.ventry.api.explore.ExploreDtos.DoneEvent;
import com.ventry.api.explore.ExploreDtos.InsightEvent;
import com.ventry.api.explore.ExploreDtos.PlanEvent;
import com.ventry.api.llm.LlmClient;
import com.ventry.api.llm.PlanPrompt;
import java.util.ArrayList;
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
    private final LlmClient llm;

    public ExploreService(InsightBuilder insights, FrontierService frontier, LlmClient llm) {
        this.insights = insights;
        this.frontier = frontier;
        this.llm = llm;
    }

    /** 한 번의 탐색 결과 — 컨트롤러가 순서대로 송출한다. refine은 무LLM에서 비어 있다. */
    public record ExplorePayload(PlanEvent plan, List<InsightEvent> insights, DoneEvent done) {}

    public ExplorePayload explore(SessionState state) {
        Profile profile = SessionMapper.profile(state);
        int budget = SessionMapper.budget(state);
        FundingInput funding = SessionMapper.fundingInput(state);
        String industry = profile.industry();

        InsightBuilder.Result result = insights.build(profile, budget, funding, state.composition());

        PlanEvent plan = plan(profile, budget, state.profile().concerns(), result);
        DoneEvent done = new DoneEvent(result.scenariosExplored(),
                frontier.frontierPoints(industry), budget);
        return new ExplorePayload(plan, result.insights(), done);
    }

    /**
     * 탐색 계획: LLM이 대화 맥락으로 축 우선순위를 정하고(결정 ① C안), 서버는 <b>실제 계산 가능한
     * 축만</b> 남긴다. LLM이 A4를 요청해도 무권리 경계가 없으면 빼고, A2·A3는 미구현이라 요청돼도
     * 뺀다(assumptions #47). LLM 부재·실패 시 폴백 축으로 떨어지므로 plan 이벤트는 항상 송출된다.
     *
     * <p>0건 보고일 때는 사유 문장을 rationale로 싣는다 — 계약에 별도 필드가 없어 rationale이
     * "왜 인사이트가 없는지"를 화면에 전한다 (assumptions #47).
     */
    private PlanEvent plan(Profile profile, int budget, List<String> concerns,
                           InsightBuilder.Result result) {
        String industry = profile.industry();
        List<String> requested = PlanPrompt.parseAxes(
                llm.complete(PlanPrompt.build(industry, concerns)));   // 실패는 폴백 축으로 수렴

        boolean hasPremiumBoundary = !frontier.boundariesExPremium(industry, budget).isEmpty();
        List<String> axes = new ArrayList<>();
        axes.add(AXIS_BUDGET);                                          // A1은 항상 실행 (expl §1)
        if (requested.contains(AXIS_PREMIUM) && hasPremiumBoundary) {
            axes.add(AXIS_PREMIUM);                                     // 계산 가능한 축만 노출
        }
        String rationale = result.emptyReason() != null
                ? result.emptyReason()
                : "예산 축을 기준으로 인접 시나리오의 진입·지속 경계를 검토했습니다.";
        return new PlanEvent(axes, ExploreAxis.labels(axes), rationale);
    }
}

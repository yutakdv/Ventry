package com.ventry.api.serving;

import com.ventry.api.common.SessionStore.SessionState;
import com.ventry.api.engine.FundingInput;
import com.ventry.api.engine.Profile;
import com.ventry.api.explore.ExploreAxis;
import com.ventry.api.explore.ExploreDtos.DoneEvent;
import com.ventry.api.explore.ExploreDtos.InsightEvent;
import com.ventry.api.explore.ExploreDtos.PlanEvent;
import com.ventry.api.explore.ExploreDtos.RefineEvent;
import com.ventry.api.llm.LlmClient;
import com.ventry.api.llm.PlanPrompt;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    /**
     * 언어화(refine)할 인사이트 수 — <b>상위 1건</b>.
     *
     * <p>전건을 언어화하면 왕복이 건수만큼 늘어난다. 병렬로 돌릴 수는 있지만 동시 호출 상한이
     * 4라(expl §5 「LLM 이 유일한 병목」) 한 요청이 3개를 잡으면 접속 두 명에 이미 폴백이
     * 시작된다. 얻는 것은 같은 종류의 문장 두 개 더인데 대가가 그 방향이면 맞지 않는다.
     *
     * <p>상위 1건인 것에는 다른 이유도 있다 — 인사이트는 목적함수 순으로 정렬돼 있고
     * (`InsightScore.topBoundaries`), 도슨트가 ★ 로 지목하는 것도 첫 카드다.
     */
    private static final int REFINE_LIMIT = 1;

    private final InsightBuilder insights;
    private final FrontierService frontier;
    private final LlmClient llm;
    private final RefineGenerator refiner;

    public ExploreService(InsightBuilder insights, FrontierService frontier, LlmClient llm,
                          RefineGenerator refiner) {
        this.insights = insights;
        this.frontier = frontier;
        this.llm = llm;
        this.refiner = refiner;
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
     * 인사이트 언어화 (LLM 역할 ② — 스펙 §0-1, expl §2-5).
     *
     * <p><b>템플릿 문장이 이미 송출된 뒤에 부른다.</b> 이 호출을 {@link #explore} 안에 두면
     * 첫 바이트가 LLM 왕복만큼 늦어져 「템플릿 즉시 → 선택적 교체」라는 설계가 뒤집힌다
     * (BE 리뷰 D-10 이 같은 이유로 plan 호출 위치를 옮겼다).
     *
     * <p><b>자격 한정 꼬리는 LLM 에 주지 않는다.</b> 떼어 낸 본문만 넘기고 통과한 문장 뒤에
     * 서버가 다시 붙인다 — 필수 고지(CLAUDE.md 절대 불변 원칙 3)를 모델이 지울 수 있는 자리에
     * 두지 않기 위해서다. 꼬리가 없는 인사이트(T2)는 본문 전체가 대상이 된다.
     *
     * <p>언어화에 실패하면 그 인사이트의 {@code refine} 이벤트가 <b>없을 뿐</b>이다 — 계약이
     * refine 을 선택적 이벤트로 규정하므로 오지 않는 것이 규격 준수다.
     */
    public List<RefineEvent> refine(List<InsightEvent> insights) {
        return insights.stream().limit(REFINE_LIMIT)
                .map(this::refineOne)
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<RefineEvent> refineOne(InsightEvent insight) {
        String headline = insight.headline();
        boolean hasTail = headline.endsWith(InsightBuilder.QUALIFICATION_TAIL);
        String body = hasTail
                ? headline.substring(0, headline.length() - InsightBuilder.QUALIFICATION_TAIL.length()).strip()
                : headline;
        return refiner.refine(body)
                .map(text -> new RefineEvent(insight.insightId(),
                        hasTail ? text + " " + InsightBuilder.QUALIFICATION_TAIL : text));
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

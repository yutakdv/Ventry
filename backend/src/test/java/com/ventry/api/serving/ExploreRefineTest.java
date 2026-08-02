package com.ventry.api.serving;

import static org.assertj.core.api.Assertions.assertThat;

import com.ventry.api.explore.ExploreDtos.Delta;
import com.ventry.api.explore.ExploreDtos.InsightEvent;
import com.ventry.api.explore.ExploreDtos.RefineEvent;
import com.ventry.api.llm.LlmClient;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BE-06 ③ — 인사이트 언어화의 <b>조립 규약</b> (스펙 §0-1 역할 ②).
 *
 * <p>응답 검증 자체는 {@code RefinePromptTest} 가 본다. 여기서 보는 것은 그 위의 두 가지다 —
 * <b>필수 고지가 LLM 손을 타지 않는가</b>, 그리고 <b>실패가 조용한 미송출로 수렴하는가</b>.
 *
 * <p>{@code refine()} 은 언어화만 하므로 나머지 협력자는 쓰지 않는다 — 넘기지 않는다.
 */
class ExploreRefineTest {

    private static final String BODY =
            "3,869만 원을 추가 확보하면 진입 가능 후보는 382곳에서 1,014곳으로 늘어납니다. "
                    + "다만 상환 부담을 반영하면 지속 안정 후보는 244곳입니다.";

    /** LLM 이 돌려주는 언어화본 — 수치는 그대로, 문장만 다르다. */
    private static final String REFINED =
            "3,869만 원을 더 마련하면 들어갈 수 있는 상권이 382곳에서 1,014곳으로 늘어납니다. "
                    + "다만 상환 부담까지 보면 오래 버틸 수 있는 곳은 244곳입니다.";

    /** 응답을 고정하는 스텁. {@code enabled} 는 로그 분기에만 쓰인다. */
    private record StubLlm(String response) implements LlmClient {
        @Override
        public Optional<String> complete(String prompt) {
            return Optional.ofNullable(response);
        }

        @Override
        public boolean enabled() {
            return response != null;
        }
    }

    private static ExploreService serviceWith(String llmResponse) {
        return new ExploreService(null, null, null, new RefineGenerator(new StubLlm(llmResponse)));
    }

    private static InsightEvent insight(String id, String headline) {
        return new InsightEvent(id, "T1", headline, new Delta(382, 1014, 244, 0.0),
                3869, null, null, null, true);
    }

    /**
     * 필수 고지는 LLM 이 지울 수 없어야 한다 — 프롬프트에 아예 넣지 않고 서버가 다시 붙인다
     * (PROJECT_RULES §2 · {@code axis_labels} 서버 단일 통제와 같은 이유).
     */
    @Test
    @DisplayName("자격 한정 꼬리는 LLM 응답에 없어도 서버가 다시 붙인다")
    void reattachesQualificationTail() {
        InsightEvent t1 = insight("i-1", BODY + " " + InsightBuilder.QUALIFICATION_TAIL);

        List<RefineEvent> events = serviceWith(REFINED).refine(List.of(t1));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).insightId()).isEqualTo("i-1");
        assertThat(events.get(0).headline())
                .startsWith("3,869만 원을 더 마련하면")                       // LLM 문장이 실렸고
                .endsWith(InsightBuilder.QUALIFICATION_TAIL);              // 고지가 살아 있다
    }

    /** 꼬리가 없는 인사이트(T2)는 본문 전체가 대상이며 없는 꼬리를 지어 붙이지 않는다. */
    @Test
    @DisplayName("꼬리 없는 인사이트는 꼬리를 새로 만들지 않는다")
    void doesNotInventTailWhenAbsent() {
        List<RefineEvent> events = serviceWith(REFINED).refine(List.of(insight("i-2", BODY)));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).headline())
                .isEqualTo(REFINED)
                .doesNotContain(InsightBuilder.QUALIFICATION_TAIL);
    }

    /**
     * 실패는 「생략했다」 플래그가 아니라 <b>이벤트 부재</b>로 표현된다 — 계약이 refine 을
     * 선택적 이벤트로 규정하므로 오지 않는 것이 규격 준수다.
     */
    @Test
    @DisplayName("무LLM이면 refine 이벤트가 아예 없다")
    void emitsNothingWithoutLlm() {
        assertThat(serviceWith(null).refine(List.of(insight("i-1", BODY)))).isEmpty();
    }

    @Test
    @DisplayName("수치를 바꾼 응답은 폐기돼 이벤트가 없다")
    void emitsNothingWhenNumbersDrift() {
        String drifted = "3,869만 원을 더 마련하면 상권이 382곳에서 약 1,000곳으로 늘어납니다. "
                + "지속 안정 후보는 244곳입니다.";
        assertThat(serviceWith(drifted).refine(List.of(insight("i-1", BODY)))).isEmpty();
    }

    /**
     * 상위 1건만 언어화한다 — 동시 호출 상한이 4라(expl §5) 한 요청이 여러 개를 잡으면
     * 접속 두 명에 폴백이 시작된다.
     */
    @Test
    @DisplayName("인사이트가 여러 건이어도 상위 1건만 언어화한다")
    void refinesOnlyTheTopInsight() {
        List<RefineEvent> events = serviceWith(REFINED).refine(
                List.of(insight("i-1", BODY), insight("i-2", BODY), insight("i-3", BODY)));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).insightId()).isEqualTo("i-1");
    }
}

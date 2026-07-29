package com.ventry.api.serving;

import com.ventry.api.llm.LlmClient;
import com.ventry.api.llm.RefinePrompt;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * BE-06 ③ — 인사이트 언어화 1왕복의 <b>캐시 경계</b>. {@link ExploreService} 가 무엇을 넘길지
 * 정하고(자격 한정 꼬리를 뗀 본문), 실제 호출과 캐시는 여기서 일어난다.
 *
 * <p>{@link ReviewGenerator} 와 같은 이유로 빈을 나눴다 — {@code @Cacheable} 은 스프링 프록시가
 * 가로채야 동작하는데 같은 빈 안에서 부르면 프록시를 우회해 캐시가 조용히 무력화된다.
 *
 * <p>캐시 키는 <b>템플릿 본문 자체</b>다. 슬라이더가 예산을 되돌리면 같은 인사이트 문장이
 * 다시 만들어지는데, 그때 왕복이 사라진다. 수치가 하나라도 달라지면 본문이 달라지므로 키가
 * 자동으로 빗나간다 — 무효화 API 가 필요 없다.
 */
@Service
public class RefineGenerator {

    private static final Logger log = LoggerFactory.getLogger(RefineGenerator.class);

    private final LlmClient llm;

    public RefineGenerator(LlmClient llm) {
        this.llm = llm;
    }

    /**
     * 언어화 1왕복. 실패는 전부 한 경로로 수렴한다 — 키 부재·타임아웃·동시 호출 초과는
     * {@link com.ventry.api.llm.GuardedLlmClient} 가, 수치 변조·누락·금지 표현·형태 위반은
     * {@link RefinePrompt#sanitize} 가 걸러낸다. 그 경우 <b>{@code refine} 이벤트를 아예 보내지
     * 않고</b> 템플릿 문장이 최종본으로 남는다 (expl §2-5).
     *
     * <p>반박문과 달리 「생략했다」는 플래그가 없다 — 계약이 refine 을 <b>선택적 이벤트</b>로
     * 규정하므로, 오지 않는 것이 곧 규격 준수다. 화면도 이미 그렇게 동작한다.
     */
    // unless: 폴백(empty)은 캐시하지 않는다. 일시적 타임아웃 한 번이 그 문장에 대해 언어화를
    // 1시간 봉인하면, 데모 중 한 번 삐끗한 것이 계속 삐끗한다 (리스크 검증 D-12 와 같은 이유).
    //
    // ⚠️ 조건이 `#result == null` 인 것은 실수가 아니다. 스프링 캐시는 반환 타입이 Optional 이면
    // **값을 벗겨서** SpEL 에 넘긴다 — empty 일 때 `#result` 는 빈 Optional 이 아니라 null 이다.
    // `#result.isEmpty()` 로 쓰면 성공 경로(문자열)에서는 멀쩡히 돌다가 **폴백 경로에서만**
    // `EL1011E: Attempted to call method isEmpty() on null context object` 로 터진다.
    // 하필 그 폴백이 「LLM 이 죽어도 화면은 산다」를 지키는 경로라, 무LLM 스택에서 SSE 가
    // done 을 못 보내고 끊겼다 (통합 QA F2 가 잡았다 — 단위 테스트는 캐시 프록시가 없어 못 잡는다).
    @Cacheable(cacheNames = "refines", key = "#templateBody", unless = "#result == null")
    public Optional<String> refine(String templateBody) {
        Optional<String> raw = llm.complete(RefinePrompt.build(templateBody));
        Optional<String> refined = RefinePrompt.sanitize(raw, templateBody);
        if (refined.isEmpty()) {
            /*
             * 폴백 사유를 세 갈래로 나눈다 — 「LLM이 없다」와 「응답이 없다(타임아웃·포화)」와
             * 「검증기가 버렸다」는 대응이 완전히 다른데 이전에는 같은 문장이었다.
             * 마지막 항목의 비율이 곧 프롬프트 품질 지표다 (AI 리뷰 M-03).
             */
            String reason = !llm.enabled() ? "no_llm" : raw.isEmpty() ? "no_response" : "rejected_by_validator";
            log.info("인사이트 언어화 폴백 reason={} — 템플릿을 최종본으로 사용", reason);
        }
        return refined;
    }
}

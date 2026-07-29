package com.ventry.api.serving;

import com.ventry.api.llm.LlmClient;
import com.ventry.api.llm.PlanPrompt;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * 탐색 계획 1왕복의 <b>캐시 경계</b> (expl §1). {@link ExploreService} 가 축 목록을 어떻게 쓸지
 * 정하고, 실제 호출과 캐시는 여기서 일어난다.
 *
 * <p><b>왜 캐시가 필요한가</b>: 이 호출은 {@code /api/explore} 의 <b>첫 이벤트(plan)를 막고
 * 있다</b> — SSE 는 plan 을 먼저 보내야 하고 그 데이터가 이 왕복 뒤에 나온다. 즉 탐색 화면의
 * 첫 바이트가 LLM 응답 시간(실측 약 1.9초, 장애 시 타임아웃 5초)만큼 늦는다. 반박문·언어화는
 * 진작 캐시 경계를 뒀는데 정작 <b>가장 앞에 있는 왕복만 매번 새로</b> 돌고 있었다.
 *
 * <p><b>캐시가 잘 듣는 자리이기도 하다.</b> 프롬프트의 변수는 업종(cafe·food 2종)과
 * 관심사(premium·rent·traffic 부분집합 8종)뿐이라 <b>서로 다른 프롬프트가 최대 16개</b>다
 * (관심사는 {@code DiagnoseController} 의 키워드 매칭이 만드는 고정 어휘다). 사실상 첫 요청만
 * 왕복하고 나머지는 전부 적중한다.
 *
 * <p>빈을 나눈 이유는 {@link ReviewGenerator}·{@link RefineGenerator} 와 같다 —
 * {@code @Cacheable} 은 스프링 프록시가 가로채야 동작하는데 같은 빈 안에서 부르면 프록시를
 * 우회해 캐시가 조용히 무력화된다.
 */
@Service
public class PlanGenerator {

    private static final Logger log = LoggerFactory.getLogger(PlanGenerator.class);

    private final LlmClient llm;

    public PlanGenerator(LlmClient llm) {
        this.llm = llm;
    }

    /**
     * LLM 이 <b>요청한</b> 축 목록. 실패(무LLM·타임아웃·깨진 JSON·화이트리스트 전멸)는 빈 목록이며,
     * <b>폴백 축을 씌우는 일은 호출부</b>가 한다 — 그래야 폴백을 캐시에서 뺄 수 있다.
     *
     * <p>서버가 계산 가능한 축만 남기는 필터도 호출부 몫이다. 그 판단은 예산·프론티어에 달려 있어
     * 여기 넣으면 캐시 키가 예산까지 물게 되고, 슬라이더 한 칸마다 키가 빗나가 캐시가 무의미해진다.
     */
    // unless: 폴백(빈 목록)은 캐시하지 않는다 — 일시적 타임아웃 한 번이 그 프로필의 축 계획을
    // 1시간 고정하면, 데모 중 한 번 삐끗한 것이 계속 삐끗한다 (BE 리뷰 D-12 와 같은 이유).
    @Cacheable(cacheNames = "plans", key = "#industry + '|' + #concerns",
            unless = "#result.isEmpty()")
    public List<String> requestedAxes(String industry, List<String> concerns) {
        List<String> axes = PlanPrompt.parseAxesOrEmpty(
                llm.complete(PlanPrompt.build(industry, concerns)));
        if (axes.isEmpty()) {
            log.info("탐색 계획 폴백 (llm_enabled={}) — 폴백 축을 사용", llm.enabled());
        }
        return axes;
    }
}

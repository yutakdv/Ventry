package com.ventry.api.serving;

import com.ventry.api.common.FinanceDtos.RiskReview;
import com.ventry.api.llm.LlmClient;
import com.ventry.api.llm.ReviewPrompt;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * BE-05 — 리스크 검증 1왕복의 <b>캐시 경계</b> (#96). {@link RiskReviewAgent} 가 사실을 조립하고,
 * 실제 호출과 캐시는 여기서 일어난다.
 *
 * <p><b>왜 클래스를 나눴나</b>: {@code @Cacheable} 은 스프링 프록시가 가로채야 동작하는데, 같은
 * 빈 안에서 메서드를 부르면 프록시를 우회해 <b>캐시가 조용히 무력화</b>된다. 실제로 한 클래스에
 * 두었을 때 2회차 호출이 1.3초 걸려(캐시 히트라면 수 밀리초) 그 사실이 드러났다. 빈을 나누는 것이
 * 이 함정을 구조적으로 없애는 방법이다.
 *
 * <p>캐시 키는 <b>입력 사실 문자열 자체</b>다. 사실이 같으면 반박도 같아야 하고, 사실이 달라지면
 * 키가 자동으로 빗나간다 — 무효화 API 가 필요 없다.
 */
@Service
public class ReviewGenerator {

    private static final Logger log = LoggerFactory.getLogger(ReviewGenerator.class);

    private final LlmClient llm;

    public ReviewGenerator(LlmClient llm) {
        this.llm = llm;
    }

    /**
     * 반박문 1왕복. 실패는 전부 한 경로로 수렴한다 — 키 부재·타임아웃·동시 호출 초과는
     * {@link com.ventry.api.llm.GuardedLlmClient} 가, 지어낸 수치·금지 표현·형태 위반은
     * {@link ReviewPrompt#sanitize} 가 걸러낸다. 그 경우 템플릿이 최종본이 되고
     * {@code skipped=true} 가 선다 (스펙 §5-3 "반박 생략 + 추천 판정 유지 + 검증 생략 플래그").
     *
     * <p>{@code template} 은 호출 지점마다 고정 상수라 키에 넣지 않는다 — 사실 문자열이 이미
     * 두 지점을 구분한다.
     */
    // unless: 폴백(skipped=true)은 캐시하지 않는다. 일시적 타임아웃 한 번이 그 사실 조합에 대해
    // 「검증 생략」을 1시간 고정하면, 데모 중 한 번 삐끗한 것이 계속 삐끗한다. 성공만 캐시한다
    // (BE 리뷰 D-12). 이 수정이 있어야 D-24 의 판정 분포 분기도 구간마다 다시 계산된다.
    @Cacheable(cacheNames = "reviews", key = "#facts", unless = "#result.skipped()")
    public RiskReview generate(String facts, RiskReview template) {
        Optional<String> objection = ReviewPrompt.sanitize(
                llm.complete(ReviewPrompt.build(facts)), facts);
        if (objection.isEmpty()) {
            // 원인(무LLM인가 응답 거부인가)은 로그로만 구분한다. 응답 규격은 어느 쪽이든 같아야
            // 화면이 분기를 하나만 갖는다.
            log.info("리스크 검증 폴백 (llm_enabled={}) — 템플릿을 최종본으로 사용", llm.enabled());
            return new RiskReview(template.objectionText(), false, true);
        }
        return new RiskReview(objection.get(), true, false);
    }
}

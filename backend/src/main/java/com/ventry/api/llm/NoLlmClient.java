package com.ventry.api.llm;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * BE-04 — 무LLM 모드 클라이언트. 키가 없어도 서비스가 정상 동작해야 한다는 원칙의 구현체로,
 * 항상 {@code Optional.empty()} 를 돌려주어 <b>호출부가 템플릿 폴백 경로를 그대로 타게</b> 한다
 * (expl §2-5 · 리스크 #9 "LLM 전면 차단 QA"의 기준 동작).
 *
 * <p>BE-05에서 실제 클라이언트({@link GuardedLlmClient} 상속)가 추가되면, 키가 있을 때만
 * 그쪽이 빈으로 선택되고 이 구현은 폴백 빈으로 남는다.
 */
@Component
public class NoLlmClient implements LlmClient {

    private final LlmSettings settings;

    public NoLlmClient(@Value("${OPENAI_API_KEY:}") String apiKey) {
        this.settings = LlmSettings.fromEnv(apiKey);
    }

    /** 무LLM 모드에서는 키가 있어도 호출하지 않는다 — 실제 호출은 BE-05 클라이언트 몫. */
    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public Optional<String> complete(String prompt) {
        return Optional.empty();
    }

    /** 키가 주입돼 있는지(BE-05에서 실클라이언트 선택 조건으로 쓰인다). */
    public boolean apiKeyPresent() {
        return settings.hasApiKey();
    }
}

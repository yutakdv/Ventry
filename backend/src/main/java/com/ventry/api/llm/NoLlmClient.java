package com.ventry.api.llm;

import java.util.Optional;

/**
 * BE-04 — 무LLM 모드 클라이언트. 키가 없어도 서비스가 정상 동작해야 한다는 원칙의 구현체로,
 * 항상 {@code Optional.empty()} 를 돌려주어 <b>호출부가 템플릿 폴백 경로를 그대로 타게</b> 한다
 * (expl §2-5 · 리스크 #9 "LLM 전면 차단 QA"의 기준 동작).
 *
 * <p>빈 선택은 {@link LlmClientConfig}가 한다 — 키가 있으면 {@link OpenAiLlmClient}, 없으면 이
 * 구현이 주입된다. 그래서 이 클래스는 스테레오타입 없이 설정이 직접 생성한다.
 */
public class NoLlmClient implements LlmClient {

    private final LlmSettings settings;

    public NoLlmClient(String apiKey) {
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

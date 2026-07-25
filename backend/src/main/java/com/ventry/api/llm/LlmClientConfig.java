package com.ventry.api.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * BE-05 — LLM 클라이언트 빈 선택 (expl §2-5, 리스크 #9).
 * <b>키가 있으면 {@link OpenAiLlmClient}, 없으면 {@link NoLlmClient}</b>가 주입된다.
 * CI·로컬 테스트에는 키가 없어 자동으로 무LLM 경로를 타므로 네트워크 의존 없이 전부 그린이다.
 *
 * <p>{@code @ConditionalOnProperty}는 키가 <b>존재</b>하면 실호출 빈을 만들지만, compose가
 * 빈 문자열({@code OPENAI_API_KEY:-})을 넘기는 경우까지 방어하려고 {@link #build}에서
 * 공백 키를 다시 걸러 무LLM으로 떨어뜨린다 — 어느 경로로 와도 키가 실제로 없으면 무LLM이다.
 */
@Configuration
public class LlmClientConfig {

    @Bean
    @ConditionalOnProperty(name = "OPENAI_API_KEY")
    LlmClient openAiLlmClient(@Value("${OPENAI_API_KEY:}") String apiKey) {
        return build(apiKey);
    }

    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    LlmClient fallbackLlmClient(@Value("${OPENAI_API_KEY:}") String apiKey) {
        return new NoLlmClient(apiKey);
    }

    /** 키 유무로 실클라이언트/무LLM을 고른다. 공백 키는 무LLM으로 간주한다. */
    static LlmClient build(String apiKey) {
        LlmSettings settings = LlmSettings.fromEnv(apiKey);
        return settings.hasApiKey() ? new OpenAiLlmClient(settings) : new NoLlmClient(apiKey);
    }
}

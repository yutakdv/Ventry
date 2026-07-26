package com.ventry.api.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * BE-05 — LLM 클라이언트 빈 선택 (expl §2-5, 리스크 #9).
 * <b>키가 있으면 {@link OpenAiLlmClient}, 없으면 {@link NoLlmClient}</b>가 주입된다.
 * CI·로컬 테스트에는 키가 없어 자동으로 무LLM 경로를 타므로 네트워크 의존 없이 전부 그린이다.
 *
 * <p><b>빈은 하나이고 선택은 {@link #build} 안에서 한다</b> (BE 리뷰 D-18). 조건 애너테이션으로
 * 두 빈을 양분하던 구성은 두 가지로 깨졌다 — {@code @ConditionalOnMissingBean} 은 일반
 * {@code @Configuration} 에서 {@code @Bean} 평가 순서에 의존하고, {@code @ConditionalOnProperty}
 * 로 나누면 compose 가 넘기는 <b>빈 문자열</b>({@code OPENAI_API_KEY:-})에서 두 조건이 동시에
 * 참이 되어 빈 2개로 기동이 실패한다(실측). 조건을 없애고 값 하나로 분기하면 그 두 함정이
 * 같이 사라진다 — "키가 실제로 없으면 무LLM"이라는 규칙이 코드 한 곳에만 남는다.
 */
@Configuration
public class LlmClientConfig {

    @Bean
    LlmClient llmClient(@Value("${OPENAI_API_KEY:}") String apiKey) {
        return build(apiKey);
    }

    /** 키 유무로 실클라이언트/무LLM을 고른다. 공백 키는 무LLM으로 간주한다. */
    static LlmClient build(String apiKey) {
        LlmSettings settings = LlmSettings.fromEnv(apiKey);
        return settings.hasApiKey() ? new OpenAiLlmClient(settings) : new NoLlmClient(apiKey);
    }
}

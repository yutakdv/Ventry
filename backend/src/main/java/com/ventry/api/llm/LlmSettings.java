package com.ventry.api.llm;

import java.time.Duration;

/**
 * BE-04 — LLM 호출 가드레일 설정 (expl §5·§6). 서빙 모델은 gpt-4o-mini (DECISIONS.md §6).
 *
 * @param apiKey        API 키. 비어 있으면 <b>무LLM 모드</b> — 템플릿이 최종본이 된다
 * @param timeout       단일 호출 타임아웃 (기본 5s, expl §6)
 * @param maxConcurrent 동시 호출 상한 K — LLM이 유일한 병목이므로 세마포어로 제한한다 (expl §5)
 */
public record LlmSettings(String apiKey, Duration timeout, int maxConcurrent) {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    public static final int DEFAULT_MAX_CONCURRENT = 4;

    public LlmSettings {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            timeout = DEFAULT_TIMEOUT;
        }
        if (maxConcurrent <= 0) {
            maxConcurrent = DEFAULT_MAX_CONCURRENT;
        }
    }

    /** 환경변수 OPENAI_API_KEY 기반 기본 설정 (키 부재 시 무LLM 모드). */
    public static LlmSettings fromEnv(String apiKey) {
        return new LlmSettings(apiKey, DEFAULT_TIMEOUT, DEFAULT_MAX_CONCURRENT);
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}

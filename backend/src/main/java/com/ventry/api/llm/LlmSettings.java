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

    /**
     * <b>동기 API 전용 상한</b> — {@code /api/recommend}·{@code /api/check-area} 처럼 톰캣 워커
     * 스레드가 응답을 기다리는 경로에서 쓴다 (BE 리뷰 M-01 (a)안).
     *
     * <p>기본 5초는 SSE 송출 전용 스레드를 전제로 잡은 값이다. 슬라이더가 움직일 때마다 재호출되는
     * {@code /api/recommend} 가 그 값을 그대로 쓰면 캐시 미스 한 번이 화면을 5초 멈춰
     * 스펙 §7 「&lt;100ms 재계산 체감」과 정면으로 어긋난다. 반면 상한을 넘겼을 때의 동작은
     * <b>기존 폴백과 완전히 같다</b> — {@code skipped=true} + 템플릿이 최종본. 새 실패 모드가
     * 생기지 않으므로 상한만 짧게 주는 것이 가장 싼 해소다.
     *
     * <p>1.2초는 저장소의 기존 실측(캐시 히트 수 밀리초 / 미스 약 1.9초)에서, <b>빠른 왕복은
     * 살리고 느린 왕복만 버리는</b> 자리로 잡았다. 반박문은 있으면 좋은 것이지 없으면 화면이
     * 깨지는 것이 아니므로, 지연과 맞바꾸지 않는다.
     */
    public static final Duration SYNC_TIMEOUT = Duration.ofMillis(1200);

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

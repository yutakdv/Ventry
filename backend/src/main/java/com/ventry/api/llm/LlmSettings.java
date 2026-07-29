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
     * <p><b>1.2초 → 3초 (2026-07-29 실사용 점검).</b> 1.2초는 "빠른 왕복은 살리고 느린 왕복만
     * 버리는" 자리로 잡은 값이었지만, 실측에서 <b>살아남는 왕복이 하나도 없었다</b> —
     * 기동 중인 스택 로그에서 {@code limit_ms=1200} 호출 33건이 <b>전건 timeout</b>이었고,
     * {@code limit_ms=5000} 호출 6건은 전건 성공했다(elapsed 812·1557·1734·1844·2212·2347ms,
     * 중앙값 약 1.79초). 즉 이 상한은 느린 왕복만이 아니라 <b>모든 왕복</b>을 버리고 있었다.
     *
     * <p>더 나쁜 것은 캐시와의 상호작용이다. {@code ReviewGenerator} 는
     * {@code unless="#result.skipped()"} 로 <b>성공만 캐시</b>하므로, 전건 타임아웃이면 캐시가
     * 영원히 비어 있고 매 요청이 상한을 꽉 채워 실패한다 — 지연은 지연대로 내면서 결과는
     * 항상 템플릿이었다. 화면에서는 「리스크 검증 생략」이 조건과 무관하게 상시 노출된다.
     *
     * <p>3초는 실측 최대치(2.35초)에 여유를 둔 값이다. 슬라이더 재호출 우려는 성립하지 않는다 —
     * {@code RiskReviewAgent} 가 예산을 캐시 키(사실 문자열)에서 <b>의도적으로 제외</b>해,
     * 한 번 성공하면 상위 3곳 판정이 바뀌기 전까지 왕복 자체가 사라진다.
     */
    public static final Duration SYNC_TIMEOUT = Duration.ofMillis(3000);

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

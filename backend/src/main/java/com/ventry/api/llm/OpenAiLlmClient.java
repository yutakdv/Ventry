package com.ventry.api.llm;

import java.net.http.HttpClient;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * BE-05 — OpenAI Chat Completions 실호출 클라이언트 (DECISIONS.md §6, 서빙 모델 gpt-4o-mini).
 * <b>{@link #call} 하나만 구현</b>한다 — 타임아웃·세마포어·폴백(모든 실패 → empty)은
 * {@link GuardedLlmClient}가 이미 보장한다. SDK를 쓰지 않고 Spring {@link RestClient}로 POST한다.
 *
 * <p>탐색 계획(plan) 1곳에서만 쓰인다 (결정 ① C안). refine·리스크 검증·진단 파싱은 템플릿·규칙
 * 그대로다. 호출이 실패해도 상위에서 {@code Optional.empty()}로 흡수되어 폴백 축이 대신 실린다.
 */
public class OpenAiLlmClient extends GuardedLlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLlmClient.class);

    private static final String ENDPOINT = "https://api.openai.com/v1/chat/completions";
    private static final String MODEL = "gpt-4o-mini";

    private final RestClient http;

    public OpenAiLlmClient(LlmSettings settings) {
        this(settings, RestClient.builder()
                .baseUrl(ENDPOINT)
                .requestFactory(requestFactory(settings))
                .defaultHeader("Authorization", "Bearer " + settings.apiKey())
                .build());
    }

    /**
     * HTTP 계층에도 같은 타임아웃을 건다 (BE 리뷰 2026-07-29 M-05).
     *
     * <p>{@code RestClient.builder()} 의 기본 요청 팩토리는 <b>연결·읽기 타임아웃이 무한</b>이다.
     * 지금은 {@link GuardedLlmClient} 의 {@code Future.get(timeout)} + {@code cancel(true)} 가
     * 실제로 소켓까지 회수하는 것을 확인했지만(무응답 업스트림 프로브: 5,001ms 폴백 · 소켓 해제),
     * 그 회수는 <b>인터럽트가 하부 클라이언트를 실제로 끊는다는 전제</b> 위에 서 있다. 요청
     * 팩토리 구현이 바뀌면 조용히 무너지는 종류의 전제라, 같은 상한을 HTTP 계층에도 명시해 둔다.
     */
    private static ClientHttpRequestFactory requestFactory(LlmSettings settings) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(settings.timeout()).build());
        factory.setReadTimeout(settings.timeout());
        return factory;
    }

    /** 테스트에서 스텁 RestClient를 주입하기 위한 생성자 (네트워크 없는 파싱 검증용). */
    OpenAiLlmClient(LlmSettings settings, RestClient http) {
        super(settings);
        this.http = http;
    }

    /** 예외를 던져도 되며 상위 {@link GuardedLlmClient#complete}가 폴백으로 흡수한다. */
    @Override
    protected String call(String prompt) {
        ChatRequest request = new ChatRequest(MODEL, List.of(new Message("user", prompt)), 0.0);
        ChatResponse response = http.post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ChatResponse.class);
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            return null;   // 빈 응답 — 폴백으로 처리된다
        }
        logUsage(response.usage());
        Message message = response.choices().get(0).message();
        return message == null ? null : message.content();
    }

    /**
     * 토큰 사용량을 버리지 않는다 (AI 리뷰 M-03).
     *
     * <p>이 서비스는 LLM이 죽어도 템플릿으로 동작하므로, 사용량 기록이 없으면 <b>"이번 달 얼마
     * 썼나"에 답할 수단 자체가 없다.</b> 응답 본문에 이미 실려 오는 값이라 추가 왕복이 없다.
     */
    private void logUsage(Usage usage) {
        if (usage == null) {
            return;   // 스텁 응답·구 스키마 — 없는 것을 지어내지 않는다
        }
        log.info("llm usage model={} prompt_tokens={} completion_tokens={}",
                MODEL, usage.prompt_tokens(), usage.completion_tokens());
    }

    // 요청·응답 최소 스키마 (필드명 전부 단어 1개라 snake_case 전역 설정과 무관).
    record ChatRequest(String model, List<Message> messages, double temperature) {}

    record Message(String role, String content) {}

    record ChatResponse(List<Choice> choices, Usage usage) {}

    record Choice(Message message) {}

    /**
     * 사용량. 컴포넌트 이름을 <b>일부러 snake_case 그대로</b> 두었다 — 이 자리는 두 단어 필드라
     * camelCase 로 적으면 응답의 {@code prompt_tokens} 와 이름이 어긋나 항상 0이 된다.
     */
    record Usage(int prompt_tokens, int completion_tokens) {}
}

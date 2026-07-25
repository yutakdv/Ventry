package com.ventry.api.llm;

import java.util.List;
import org.springframework.http.MediaType;
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

    private static final String ENDPOINT = "https://api.openai.com/v1/chat/completions";
    private static final String MODEL = "gpt-4o-mini";

    private final RestClient http;

    public OpenAiLlmClient(LlmSettings settings) {
        this(settings, RestClient.builder()
                .baseUrl(ENDPOINT)
                .defaultHeader("Authorization", "Bearer " + settings.apiKey())
                .build());
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
        Message message = response.choices().get(0).message();
        return message == null ? null : message.content();
    }

    // 요청·응답 최소 스키마 (필드명 전부 단어 1개라 snake_case 전역 설정과 무관).
    record ChatRequest(String model, List<Message> messages, double temperature) {}

    record Message(String role, String content) {}

    record ChatResponse(List<Choice> choices) {}

    record Choice(Message message) {}
}

package com.ventry.api.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * BE-05 — LLM 탐색 계획: 빈 선택(키 유무)과 응답 파싱. <b>네트워크 호출 0</b> — 실제
 * api.openai.com을 때리지 않고 파서와 설정만 검증한다 (CI에 키 없음, 결정 ① C안).
 */
class PlanLlmTest {

    // ── #25 키 유무에 따른 빈 선택 (CI 기본 경로는 무LLM) ──────────────────

    @Test
    void withoutApiKey_selectsNoLlmClient() {
        LlmClient client = LlmClientConfig.build("");
        assertThat(client).isInstanceOf(NoLlmClient.class);
        assertThat(client.enabled()).isFalse();
        assertThat(client.complete("아무 프롬프트")).isEmpty();   // 무LLM → 폴백

        // 공백·null 키도 무LLM (compose가 빈 문자열을 넘기는 경우 방어)
        assertThat(LlmClientConfig.build("   ")).isInstanceOf(NoLlmClient.class);
        assertThat(LlmClientConfig.build(null)).isInstanceOf(NoLlmClient.class);
    }

    @Test
    void withApiKey_selectsOpenAiClient_butMakesNoCallUntilComplete() {
        LlmClient client = LlmClientConfig.build("sk-test-key");
        assertThat(client).isInstanceOf(OpenAiLlmClient.class);
        assertThat(client.enabled()).isTrue();   // 생성만으로는 네트워크 호출이 없다
    }

    // ── #26 화이트리스트 밖 축 폐기 ─────────────────────────────────────────

    @Test
    void response_withUnknownAxis_keepsOnlyWhitelisted() {
        assertThat(PlanPrompt.parseAxes(Optional.of("[\"A1\",\"A9\"]")))
                .containsExactly("A1");   // A9 폐기, 폴백으로 대체하지 않는다
        assertThat(PlanPrompt.parseAxes(Optional.of("[\"A4\",\"A1\",\"A1\"]")))
                .containsExactly("A4", "A1");   // 순서 보존 · 중복 제거
        // 텍스트에 배열이 섞여 있어도 첫 배열만 취한다
        assertThat(PlanPrompt.parseAxes(Optional.of("다음과 같습니다: [\"A1\",\"A4\"] 참고하세요")))
                .containsExactly("A1", "A4");
    }

    // ── #27 깨진 JSON → 폴백 ────────────────────────────────────────────────

    @Test
    void response_brokenOrAxisless_fallsBackToDefaultAxes() {
        assertThat(PlanPrompt.parseAxes(Optional.of("[\"A1\", \"A4\"")))     // 닫히지 않음
                .isEqualTo(PlanPrompt.FALLBACK_AXES);
        assertThat(PlanPrompt.parseAxes(Optional.of("그냥 문장, 배열 없음")))  // 배열 없음
                .isEqualTo(PlanPrompt.FALLBACK_AXES);
        assertThat(PlanPrompt.parseAxes(Optional.of("[\"A9\",\"A8\"]")))      // 화이트리스트 전멸
                .isEqualTo(PlanPrompt.FALLBACK_AXES);
        assertThat(PlanPrompt.FALLBACK_AXES).containsExactly("A1", "A4");
    }

    // ── #28 empty → 폴백 (plan 이벤트는 그대로 송출된다) ────────────────────

    @Test
    void emptyResponse_fallsBackToDefaultAxes() {
        assertThat(PlanPrompt.parseAxes(Optional.empty()))
                .isEqualTo(PlanPrompt.FALLBACK_AXES)
                .isNotEmpty();   // 폴백 축이 있으므로 plan 이벤트는 항상 송출 가능
    }

    // ── call() 파싱 (MockRestServiceServer, 네트워크 없음) ──────────────────

    /** OpenAiLlmClient가 응답 본문에서 첫 choice의 content를 뽑아내는지만 검증한다. */
    @Test
    void openAiClient_extractsContentFromResponse_withoutNetwork() {
        String endpoint = "https://api.openai.com/v1/chat/completions";
        String body = """
                {"choices":[{"message":{"role":"assistant","content":"[\\"A1\\",\\"A4\\"]"}}]}
                """;
        RestClient.Builder builder = RestClient.builder().baseUrl(endpoint);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(endpoint))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        OpenAiLlmClient client = new OpenAiLlmClient(
                new LlmSettings("sk-test-key", Duration.ofSeconds(5), 4), builder.build());

        assertThat(PlanPrompt.parseAxes(client.complete("프롬프트")))
                .containsExactly("A1", "A4");
        server.verify();
    }

    /** 데모 프로필 관심사가 프롬프트에 반영되되 수치는 담기지 않는다. */
    @Test
    void prompt_includesConcerns_butNoNumbers() {
        String prompt = PlanPrompt.build("cafe", List.of("premium"));
        assertThat(prompt).contains("premium").contains("A1").contains("JSON");
        assertThat(prompt).doesNotContainPattern("[0-9]{3,}");   // 예산·금액 미포함
    }
}

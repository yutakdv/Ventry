package com.ventry.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 금액·version 상한 회귀 (QA 리뷰 2026-07-29 Q-01·Q-02).
 *
 * <p>고치기 전 두 경로 모두 <b>정상 응답으로 위장</b>했다.
 * <ul>
 *   <li><b>Q-01</b> {@code capital} 에 상한이 없어 {@code int} 최댓값 근처가 통과했고,
 *       시나리오 조립의 {@code budget_max = 자기자본 + 상품 한도} 가 오버플로해
 *       {@code Math.clamp} 가 {@code min > max} 로 터졌다 —
 *       {@code GET /api/scenarios/{sid}} 가 <b>500</b>. 실측 경계는 2,147,473,648 이며
 *       <b>선택된 상품의 한도만큼 움직인다</b>. 그래서 입력 상한과 {@code long} 산술을 함께 둔다.</li>
 *   <li><b>Q-02</b> {@code ?v=-1} 은 세션 최신(0)보다 작아 <b>이벤트 0건인 정상 종료 스트림</b>이
 *       나갔다. 프론트는 그것을 오류로 보고 목 폴백을 켜므로 잘못된 입력이 지어낸 인사이트로
 *       화면에 도달한다.</li>
 * </ul>
 *
 * <p>픽스처 프로파일로 돈다 — 실데이터에서의 같은 경계는 기동 중인 스택에 직접 던져
 * 확인했다 ({@code scripts/qa_integration.py} 의 경계 시나리오 B1~B3).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AmountBoundaryTest {

    /** {@code Amounts.MAX} 와 같은 값. 테스트가 상수를 참조하면 상수가 틀려도 같이 틀린다. */
    private static final long AMOUNT_MAX = 100_000_000L;

    @Autowired
    private MockMvc mockMvc;

    private static String diagnoseBody(String capital) {
        return """
                { "form": { "age": 32, "capital": %s, "industry": "cafe",
                            "region_hint": "서울 마포구" }, "free_text": "" }
                """.formatted(capital);
    }

    private String sessionWithCapital(long capital) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/diagnose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(diagnoseBody(String.valueOf(capital))))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.session_id");
    }

    @Nested
    @DisplayName("Q-01 금액 상한 — capital / confirmed_budget")
    class AmountUpperBound {

        @Test
        @DisplayName("capital 이 상한을 넘으면 400 — 세션을 만들기 전에 막는다")
        void capitalAboveMaxIsRejected() throws Exception {
            mockMvc.perform(post("/api/diagnose")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(diagnoseBody(String.valueOf(AMOUNT_MAX + 1))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }

        @Test
        @DisplayName("capital 이 정확히 상한이면 통과하고 시나리오도 200 이다")
        void capitalAtMaxStillBuildsScenarios() throws Exception {
            String sid = sessionWithCapital(AMOUNT_MAX);
            mockMvc.perform(get("/api/scenarios/" + sid)).andExpect(status().isOk());
        }

        /**
         * 상한을 통과한 값이 <b>계산에서도</b> 안전한지가 이 이슈의 본체다. 입력 검증만 고치고
         * 산술을 그대로 두면 상품 한도가 커지는 순간 같은 500이 다시 열린다.
         */
        @Test
        @DisplayName("상한값 자기자본에서 budget_max 가 뒤집히지 않는다 (long 산술)")
        void budgetMaxDoesNotOverflow() throws Exception {
            String sid = sessionWithCapital(AMOUNT_MAX);
            mockMvc.perform(get("/api/scenarios/" + sid))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .content().string(org.hamcrest.Matchers.not(
                                    org.hamcrest.Matchers.containsString("\"budget_max\":-"))));
        }

        @Test
        @DisplayName("confirmed_budget 이 상한을 넘으면 400")
        void confirmedBudgetAboveMaxIsRejected() throws Exception {
            String sid = sessionWithCapital(5000);
            mockMvc.perform(post("/api/budget/" + sid)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ \"confirmed_budget\": %d, \"composition\": [] }"
                                    .formatted(AMOUNT_MAX + 1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }

        @Test
        @DisplayName("정상 금액(5,000)의 기존 산출은 그대로다")
        void normalAmountIsUnchanged() throws Exception {
            String sid = sessionWithCapital(5000);
            mockMvc.perform(post("/api/budget/" + sid)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "confirmed_budget": 8000,
                                      "composition": [ { "type": "equity", "amount": 5000 } ] }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.confirmed_budget").value(8000));
        }
    }

    @Nested
    @DisplayName("Q-02 version 규격 — 음수·상한 초과")
    class VersionRange {

        @Test
        @DisplayName("v 가 음수면 400 — 빈 스트림(→ 목 폴백)으로 새지 않는다")
        void negativeVersionIsRejected() throws Exception {
            String sid = sessionWithCapital(5000);
            mockMvc.perform(get("/api/recommend/" + sid).param("v", "-1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }

        @Test
        @DisplayName("v 가 상한을 넘으면 400 — 세션 탐색을 영구 정지시키는 값의 범위를 좁힌다")
        void hugeVersionIsRejected() throws Exception {
            String sid = sessionWithCapital(5000);
            mockMvc.perform(get("/api/explore/" + sid).param("v", "9223372036854775807"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }

        @Test
        @DisplayName("정상 version 은 그대로 동작한다 (0·1)")
        void normalVersionsPass() throws Exception {
            String sid = sessionWithCapital(5000);
            mockMvc.perform(get("/api/recommend/" + sid).param("v", "0"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/recommend/" + sid).param("v", "1"))
                    .andExpect(status().isOk());
        }
    }
}

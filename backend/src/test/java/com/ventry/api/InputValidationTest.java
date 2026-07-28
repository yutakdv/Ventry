package com.ventry.api;

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
 * 진단 입력 검증 회귀 — 경계값 고정 (이슈 #155·#158).
 *
 * <p>고치기 전의 상태는 <b>전부 200이었다</b>. 나이 -5·0·미기재가 「만 39세 이하」 청년 전용
 * 상품을 편성했고, 음수 월 투자 가능액이 모든 경계를 상환 초과로 떨어뜨린 뒤 화면에
 * "유의미한 대안이 없습니다"로 표시됐다 — 오류가 아니라 정상으로 보였다. 그래서 이 테스트는
 * <b>차단(400)</b>과 <b>자격 판정 제외</b>를 둘 다 못 박는다.
 *
 * <p>픽스처 프로파일로 돈다. 실데이터(후보 1,059곳 · 자격 부합 11건)에서의 같은 경계는
 * {@code scripts/qa_integration.py} 의 게이트 G6 이 기동 중인 스택에 직접 던져 확인한다 —
 * 어느 쪽도 혼자서는 전체를 덮지 못한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InputValidationTest {

    /** 픽스처 청년 전용 상품 — 만 39세 이하 상한이 걸린 유일한 상품 ({@code DemoProducts}). */
    private static final String YOUTH_PRODUCT = "소진공 청년 전용 창업자금";
    /** 자격 무제약 보증 상품 — 나이와 무관하게 항상 편성돼야 한다. */
    private static final String GUARANTEE_PRODUCT = "서울신용보증재단 창업보증";

    @Autowired
    private MockMvc mockMvc;

    /** 폼 한 벌 — {@code ageJson}·{@code monthlyJson} 자리에 원시 JSON 조각을 그대로 넣는다. */
    private static String form(String ageJson, String monthlyJson) {
        return """
                { "form": { %s "capital": 5000, "is_existing_business": false,
                            "collateral_available": true, %s
                            "industry": "cafe", "region_hint": "서울 마포구" },
                  "free_text": "" }
                """.formatted(ageJson, monthlyJson);
    }

    private static String age(Integer value) {
        return value == null ? "" : "\"age\": " + value + ",";
    }

    private static String monthly(Integer value) {
        return value == null ? "" : "\"monthly_investable\": " + value + ",";
    }

    private String sessionFor(String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/diagnose")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.session_id");
    }

    private void expectRejected(String body) throws Exception {
        mockMvc.perform(post("/api/diagnose")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    /** 확정 예산 8,000 — {@code A-9999} 가 조건부 적합이 되는 데모 값 (expl §8). */
    private void confirmBudget(String sid) throws Exception {
        mockMvc.perform(post("/api/budget/" + sid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "confirmed_budget": 8000,
                                  "composition": [ { "type": "equity", "amount": 5000 } ] }
                                """))
                .andExpect(status().isOk());
    }

    /** 자격 부합 상품 목록을 이름으로 확인한다 — 청년 상품이 편성됐는지가 이 이슈의 핵심이다. */
    private org.springframework.test.web.servlet.ResultActions matchingProducts(String sid)
            throws Exception {
        confirmBudget(sid);
        return mockMvc.perform(post("/api/check-area/" + sid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"area_code\": \"A-9999\" }"))
                .andExpect(status().isOk());
    }

    @Nested
    @DisplayName("age 경계 5종 — -5 / 0 / null / 39 / 40")
    class AgeBoundary {

        @Test
        @DisplayName("-5 는 400 — 음수가 상한 조건을 통과하던 경로를 막는다")
        void negative() throws Exception {
            expectRejected(form(age(-5), monthly(250)));
        }

        @Test
        @DisplayName("0 은 400 — 0 은 미기재의 강등값이지 나이가 아니다")
        void zero() throws Exception {
            expectRejected(form(age(0), monthly(250)));
        }

        @Test
        @DisplayName("200 은 400 — 상한 밖")
        void tooOld() throws Exception {
            expectRejected(form(age(200), monthly(250)));
        }

        /**
         * 미기재는 <b>통과시키되 자격 판정에서 뺀다</b>. 폼에서 나이는 필수가 아니라 400으로
         * 막으면 정상 사용자를 막게 되고, 0으로 강등하면 청년 상품이 편성된다 — 둘 다 아니다.
         */
        @Test
        @DisplayName("미기재는 200 이지만 청년 전용 상품이 편성되지 않는다")
        void absent() throws Exception {
            String sid = sessionFor(form(age(null), monthly(250)));
            matchingProducts(sid)
                    .andExpect(jsonPath("$.matching_products.length()").value(1))
                    .andExpect(jsonPath("$.matching_products[0].name").value(GUARANTEE_PRODUCT));
        }

        @Test
        @DisplayName("39 는 청년 상품 포함 (기존 정상 동작 유지)")
        void atUpperBound() throws Exception {
            String sid = sessionFor(form(age(39), monthly(250)));
            matchingProducts(sid)
                    .andExpect(jsonPath("$.matching_products.length()").value(2))
                    .andExpect(jsonPath("$.matching_products[0].name").value(YOUTH_PRODUCT));
        }

        @Test
        @DisplayName("40 은 청년 상품 제외 (기존 정상 동작 유지)")
        void aboveUpperBound() throws Exception {
            String sid = sessionFor(form(age(40), monthly(250)));
            matchingProducts(sid)
                    .andExpect(jsonPath("$.matching_products.length()").value(1))
                    .andExpect(jsonPath("$.matching_products[0].name").value(GUARANTEE_PRODUCT));
        }

        @Test
        @DisplayName("15 는 하한 경계라 통과한다")
        void atLowerBound() throws Exception {
            sessionFor(form(age(15), monthly(250)));
        }
    }

    @Nested
    @DisplayName("monthly_investable 3종 — -100 / 0 / 250")
    class MonthlyInvestableBoundary {

        @Test
        @DisplayName("-100 은 400 — 200 + 「대안 없음」으로 나가던 경로를 막는다")
        void negative() throws Exception {
            expectRejected(form(age(32), monthly(-100)));
        }

        /**
         * 0 은 <b>허용한다</b>. 월 상환 여력이 0이면 어떤 차입도 상한을 넘으므로 T1 이 0건이
         * 되는 것은 정의상 맞는 결과다 — 음수와 달리 입력 자체가 모순이 아니다 (이슈 #157).
         */
        @Test
        @DisplayName("0 은 200 — 상환 여력 0 은 유효한 입력이다")
        void zero() throws Exception {
            sessionFor(form(age(32), monthly(0)));
        }

        @Test
        @DisplayName("미기재는 200 — 상환 여력 상한 없음 (가정 #23)")
        void absent() throws Exception {
            sessionFor(form(age(32), monthly(null)));
        }
    }

    /**
     * 정상 입력의 산출이 검증 추가로 흔들리지 않았는지 — 이 테스트가 없으면 "막았다"만 알고
     * "막느라 뭘 깨뜨렸는지"는 모른다.
     */
    @Test
    @DisplayName("정상 입력(32 · 250)의 기존 산출이 그대로다")
    void normalInputIsUnchanged() throws Exception {
        String sid = sessionFor(form(age(32), monthly(250)));
        matchingProducts(sid)
                .andExpect(jsonPath("$.verdict").value("CONDITIONAL"))
                .andExpect(jsonPath("$.gap_amount").value(1320))
                .andExpect(jsonPath("$.matching_products.length()").value(2))
                .andExpect(jsonPath("$.matching_products[0].name").value(YOUTH_PRODUCT))
                .andExpect(jsonPath("$.matching_products[1].name").value(GUARANTEE_PRODUCT));
    }

    /**
     * 본문 크기 상한 (이슈 #172) — Tomcat 의 {@code maxPostSize} 는 JSON 에 걸리지 않아
     * 2천만 자 본문이 200 으로 통과했다. 상한 초과는 413 이고 정상 입력은 영향이 없다.
     */
    @Test
    @DisplayName("상한 초과 본문은 413 으로 끊는다")
    void oversizedBodyIsRejected() throws Exception {
        String padding = "가".repeat(300_000);   // UTF-8 3바이트 × 30만 = 약 900 KB
        String body = """
                { "form": { "age": 32, "capital": 5000, "industry": "cafe" },
                  "free_text": "%s" }
                """.formatted(padding);
        mockMvc.perform(post("/api/diagnose")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.error.code").value("PAYLOAD_TOO_LARGE"));
    }
}

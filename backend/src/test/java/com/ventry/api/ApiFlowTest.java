package com.ventry.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** BE-01 목 API 6종 — 계약(docs/API_CONTRACT.md) 필드·이벤트 순서 검증. */
@SpringBootTest
@AutoConfigureMockMvc
class ApiFlowTest {

    @Autowired
    private MockMvc mockMvc;

    private String createSession() throws Exception {
        String body = """
                { "form": { "age": 32, "capital": 5000, "is_existing_business": false,
                            "collateral_available": true, "monthly_investable": 250,
                            "industry": "cafe", "region_hint": "서울 마포구" },
                  "free_text": "권리금이 제일 걱정입니다" }
                """;
        MvcResult result = mockMvc.perform(post("/api/diagnose")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session_id").isNotEmpty())
                .andExpect(jsonPath("$.parsed_profile.capital").value(5000))
                // BE-01a: 폼 신규 3필드는 parsed_profile로 그대로 반향된다
                .andExpect(jsonPath("$.parsed_profile.is_existing_business").value(false))
                .andExpect(jsonPath("$.parsed_profile.collateral_available").value(true))
                .andExpect(jsonPath("$.parsed_profile.monthly_investable").value(250))
                .andExpect(jsonPath("$.parsed_profile.concerns[0]").value("premium"))
                // 자유 텍스트가 있어도 form_only 다 — concerns 추출은 키워드 매칭이고 LLM 호출이
                // 아니다. 구 단언은 "llm" 을 기대해 실제로는 하지 않은 일을 계약으로 굳히고 있었다
                // (BE-07 통합 QA 지적).
                .andExpect(jsonPath("$.parsed_profile.parse_source").value("form_only"))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.session_id");
    }

    /** 데모 예산 확정: 자기자본 5,000 + 정책자금 3,000 = 8,000 (expl §8). */
    private void confirmBudget(String sid) throws Exception {
        String body = """
                { "confirmed_budget": 8000,
                  "composition": [ { "type": "equity", "amount": 5000 },
                                   { "type": "policy_loan", "amount": 3000 } ] }
                """;
        mockMvc.perform(post("/api/budget/" + sid)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    /** 임의 예산 확정 — 구성은 자기자본만(상품 잔여 한도를 소비하지 않는다). */
    private void confirmBudget(String sid, int budget) throws Exception {
        String body = """
                { "confirmed_budget": %d,
                  "composition": [ { "type": "equity", "amount": %d } ] }
                """.formatted(budget, budget);
        mockMvc.perform(post("/api/budget/" + sid)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void diagnose_withoutFreeText_marksFormOnly() throws Exception {
        String body = """
                { "form": { "age": 32, "capital": 5000, "industry": "cafe", "region_hint": "망원" } }
                """;
        mockMvc.perform(post("/api/diagnose")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parsed_profile.parse_source").value("form_only"));
    }

    @Test
    void budget_recordsCompositionIntoSession() throws Exception {
        String sid = createSession();
        String body = """
                { "confirmed_budget": 8000,
                  "composition": [ { "type": "equity", "amount": 5000 },
                                   { "type": "policy_loan", "amount": 3000 } ] }
                """;
        mockMvc.perform(post("/api/budget/" + sid)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmed_budget").value(8000))
                .andExpect(jsonPath("$.composition[1].type").value("policy_loan"))
                // BE-01a: 화면 2 슬라이더가 즉시 그리는 프리뷰 (DECISIONS.md §9)
                .andExpect(jsonPath("$.preview.area_count").value(3))
                .andExpect(jsonPath("$.preview.rent_range.length()").value(2))
                .andExpect(jsonPath("$.preview.floating_range[1]").value(38200));
    }

    /** 진입 후보가 없으면 개수만 0이고 범위 필드는 생략된다 (non_null 정책). */
    @Test
    void budget_belowEveryCandidate_previewHasCountOnly() throws Exception {
        String sid = createSession();
        mockMvc.perform(post("/api/budget/" + sid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"confirmed_budget\": 5000, \"composition\": [] }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview.area_count").value(0))
                .andExpect(jsonPath("$.preview.rent_range").doesNotExist());
    }

    @Test
    void recommend_returnsThreeAreasWithVerdictAndRiskReview() throws Exception {
        String sid = createSession();
        confirmBudget(sid);   // 실 흐름: 예산 확정(B₀=8000) 후 추천 — 판정이 예산에 의존
        mockMvc.perform(get("/api/recommend/" + sid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data_as_of").value("2026-Q1"))
                .andExpect(jsonPath("$.areas.length()").value(3))
                .andExpect(jsonPath("$.areas[0].verdict").value("FIT"))
                .andExpect(jsonPath("$.areas[2].verdict").value("CAUTION"))
                .andExpect(jsonPath("$.areas[0].cost.ex_premium.length()").value(2))
                .andExpect(jsonPath("$.areas[0].rent_source.org").value("REB"))
                .andExpect(jsonPath("$.areas[0].transit.station").value("망원"))
                // BE-01a: 목록 헤더·카드 표기용 집계와 원자재
                .andExpect(jsonPath("$.total_count").value(3))
                .andExpect(jsonPath("$.summary.avg_rent").value(309))
                .andExpect(jsonPath("$.summary.avg_sales").value(2100))
                .andExpect(jsonPath("$.areas[0].score").value(75))
                .andExpect(jsonPath("$.areas[0].monthly_rent").value(198))
                .andExpect(jsonPath("$.areas[0].est_sales").value(1800))
                .andExpect(jsonPath("$.areas[0].daily_floating").value(24500))
                // 테스트 프로파일에는 LLM 키가 없다 → 검증 에이전트가 돌지 않고 템플릿이
                // 최종본이 되며, 그 사실이 skipped=true 로 드러난다 (#96, 스펙 §5-3).
                // 구 단언(applied=true)은 LLM 없이도 "검증했다"고 말하던 상태를 굳히고 있었다.
                .andExpect(jsonPath("$.risk_review.applied").value(false))
                .andExpect(jsonPath("$.risk_review.skipped").value(true))
                .andExpect(jsonPath("$.risk_review.objection_text").isNotEmpty());
    }

    @Test
    void checkArea_returnsConditionalWithMatchingProducts() throws Exception {
        String sid = createSession();
        confirmBudget(sid);   // B₀=8000 확정 시 A-9999 갭 = 1320
        mockMvc.perform(post("/api/check-area/" + sid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"area_code\": \"A-9999\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("CONDITIONAL"))
                .andExpect(jsonPath("$.gap_amount").value(1320))
                .andExpect(jsonPath("$.matching_products[0].name").isNotEmpty())
                // 계약 D8: rate_type은 항상 존재(FE 분기 키), 데모 상품은 전부 fixed
                .andExpect(jsonPath("$.matching_products[0].rate_type").value("fixed"))
                // 계약 D8 고정 정렬: amount_max 내림차순 (소진공 3000 → 서울보증 1500)
                .andExpect(jsonPath("$.matching_products[0].amount_max").value(3000))
                .andExpect(jsonPath("$.matching_products[1].amount_max").value(1500))
                // 픽스처 상품에는 연결된 청크가 없다 → non_null 직렬화라 필드 자체가 생략된다
                .andExpect(jsonPath("$.matching_products[0].source_quote").doesNotExist())
                .andExpect(jsonPath("$.matching_products[0].rate_note").doesNotExist())  // fixed → 생략
                .andExpect(jsonPath("$.risk_review.skipped").value(true));   // 키 없음 → 템플릿 (#96)
    }

    @Test
    void scenarios_streamsTwoCardsThenDone() throws Exception {
        String sid = createSession();
        MvcResult result = mockMvc.perform(get("/api/scenarios/" + sid))
                .andExpect(request().asyncStarted())
                .andReturn();
        String content = awaitSse(result, "event:done");
        assertThat(content).contains("event:scenario");
        assertThat(content).contains("\"label\":\"보수\"");
        assertThat(content).contains("\"label\":\"적극\"");
        assertThat(content).contains("\"scenario_count\":2");
        // BE-01a: 예산은 범위로, 상품은 한도·금리·기준일과 함께
        assertThat(content).contains("\"budget_min\":5000");
        assertThat(content).contains("\"budget_max\":8000");
        assertThat(content).contains("\"amount_min\":0");
        assertThat(content).contains("\"data_as_of\":\"2026-Q1\"");
        // 계약 D8: 상품 카드에도 rate_type 항상 존재(데모 fixed)
        assertThat(content).contains("\"rate_type\":\"fixed\"");
    }

    /**
     * BE-05: /explore 실계산 — plan → insight(1건씩) → done. refine은 무LLM 모드에서 미송출이
     * 정상이라(계약상 "(선택적)") 순서 단언에서 제외한다. LLM 교체는 [6]단계 몫이다.
     *
     * <p>이 테스트는 <b>픽스처 경로(CI는 DB 없음)</b>라 데모 화면과 수치가 다르다 — 여기선
     * B₀=7,800에서 진입 2→3·지속 3·갭 150이다. 실제 데모는 db 프로파일의 후보 10곳 경로이며
     * B₀=8,000에서 진입 3→9·지속 8이 나온다 (5-A 실측). 두 경로의 수치가 다른 것이 정상이다.
     */
    @Test
    void explore_streamsPlanInsightDoneInOrder_refineOptional() throws Exception {
        String sid = createSession();
        confirmBudget(sid, 7800);   // 픽스처 상향 경계 7,950이 생기는 예산 (done.current_budget)
        MvcResult result = mockMvc.perform(get("/api/explore/" + sid).param("v", "1"))
                .andExpect(request().asyncStarted())
                .andReturn();
        String content = awaitSse(result, "event:done");
        int plan = content.indexOf("event:plan");
        int insight = content.indexOf("event:insight");
        int done = content.indexOf("event:done");
        assertThat(plan).isNotNegative();
        assertThat(insight).isGreaterThan(plan);
        assertThat(done).isGreaterThan(insight);
        assertThat(content).contains("\"n_entry_before\":2");   // 홍대 7,500 · 망원 7,750
        assertThat(content).contains("\"n_entry_after\":3");    // + 합정 7,950
        assertThat(content).contains("\"n_sustain_after\":3");  // 상환 부담 반영 후 지속 3곳 병기
        assertThat(content).contains("\"gap_amount\":150");
        // 계약 D8: T1 funding 블록에 rate_type 항상 존재(lead 상품은 fixed만 될 수 있다)
        assertThat(content).contains("\"rate_type\":\"fixed\"");
        assertThat(content).contains("frontier_points");
        // 축 라벨은 서버가 송출(프론트 하드코딩 사전 제거). 무권리 경계가 없어 A1만 실린다
        assertThat(content).contains("\"axis_labels\":{\"A1\":\"예산\"}");
        assertThat(content).contains("\"current_budget\":7800");
    }

    @Test
    void unknownSession_returnsContractErrorFormat() throws Exception {
        mockMvc.perform(get("/api/recommend/no-such-session"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").isNotEmpty());
    }

    /**
     * BE-07 통합 QA 에서 잡힌 것 — 타입이 틀린 필드 하나가 <b>500</b>을 냈다.
     *
     * <p>서버 결함이 아니라 클라이언트 입력 문제이므로 400 이어야 한다. 500 이면 프론트는
     * 재시도할지 입력을 고칠지 판단할 수 없고, 심사위원이 API 를 찔러 보는 경로에서도
     * 없는 장애로 보인다. 응답 메시지에 Jackson 예외 원문(내부 타입명)이 새지 않는 것도 함께 잠근다.
     */
    @Test
    void malformedBody_returnsBadRequest_notServerError() throws Exception {
        mockMvc.perform(post("/api/diagnose").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"form\":{\"age\":\"서른둘\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").isNotEmpty())
                .andExpect(jsonPath("$.error.message").value(not(containsString("java.lang"))));
    }

    /** 본문이 JSON 조차 아닌 경우도 같은 규격으로 떨어져야 한다. */
    @Test
    void nonJsonBody_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/diagnose").contentType(MediaType.APPLICATION_JSON)
                        .content("not json at all"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    /**
     * SSE는 전용 executor에서 송출되므로 완료 마커가 나타날 때까지 짧게 폴링한다.
     * 마커 줄만 플러시된 순간 반환하면 뒤따르는 {@code data:} 줄을 놓치므로,
     * <b>마커가 속한 이벤트 프레임이 빈 줄(\n\n)로 끝날 때까지</b> 기다린다. 시간 내
     * 완결되지 않으면 조용히 반환하지 않고 명시적으로 실패시켜 원인을 드러낸다.
     */
    private String awaitSse(MvcResult result, String marker) throws Exception {
        for (int i = 0; i < 300; i++) {
            String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            int at = content.indexOf(marker);
            if (at >= 0 && content.indexOf("\n\n", at) >= 0) {
                return content;   // 프레임 완결(data 줄까지 flush) 확인
            }
            Thread.sleep(20);
        }
        throw new AssertionError("SSE 프레임이 6초 내 완결되지 않음 (marker=" + marker + "):\n"
                + result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}

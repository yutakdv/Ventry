package com.ventry.api;

import static org.assertj.core.api.Assertions.assertThat;
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
                { "form": { "age": 32, "capital": 5000, "industry": "cafe", "region_hint": "망원" },
                  "free_text": "권리금이 제일 걱정입니다" }
                """;
        MvcResult result = mockMvc.perform(post("/api/diagnose")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session_id").isNotEmpty())
                .andExpect(jsonPath("$.parsed_profile.capital").value(5000))
                .andExpect(jsonPath("$.parsed_profile.concerns[0]").value("premium"))
                .andExpect(jsonPath("$.parsed_profile.parse_source").value("llm"))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.session_id");
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
                .andExpect(jsonPath("$.composition[1].type").value("policy_loan"));
    }

    @Test
    void recommend_returnsThreeAreasWithVerdictAndRiskReview() throws Exception {
        String sid = createSession();
        mockMvc.perform(get("/api/recommend/" + sid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data_as_of").value("2026-Q1"))
                .andExpect(jsonPath("$.areas.length()").value(3))
                .andExpect(jsonPath("$.areas[0].verdict").value("FIT"))
                .andExpect(jsonPath("$.areas[2].verdict").value("CAUTION"))
                .andExpect(jsonPath("$.areas[0].cost.ex_premium.length()").value(2))
                .andExpect(jsonPath("$.areas[0].rent_source.org").value("REB"))
                .andExpect(jsonPath("$.areas[0].transit.station").value("망원"))
                .andExpect(jsonPath("$.risk_review.applied").value(true))
                .andExpect(jsonPath("$.risk_review.skipped").value(false));
    }

    @Test
    void checkArea_returnsConditionalWithSourceQuote() throws Exception {
        String sid = createSession();
        mockMvc.perform(post("/api/check-area/" + sid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"area_code\": \"A-9999\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("CONDITIONAL"))
                .andExpect(jsonPath("$.gap_amount").value(1320))
                .andExpect(jsonPath("$.matching_products[0].source_quote.text").isNotEmpty())
                .andExpect(jsonPath("$.risk_review.applied").value(true));
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
    }

    @Test
    void explore_streamsPlanInsightRefineDoneInOrder() throws Exception {
        String sid = createSession();
        MvcResult result = mockMvc.perform(get("/api/explore/" + sid).param("v", "1"))
                .andExpect(request().asyncStarted())
                .andReturn();
        String content = awaitSse(result, "event:done");
        int plan = content.indexOf("event:plan");
        int insight = content.indexOf("event:insight");
        int refine = content.indexOf("event:refine");
        int done = content.indexOf("event:done");
        assertThat(plan).isNotNegative();
        assertThat(insight).isGreaterThan(plan);
        assertThat(refine).isGreaterThan(insight);
        assertThat(done).isGreaterThan(refine);
        assertThat(content).contains("\"n_entry_after\":11");   // T1: 진입 3→11
        assertThat(content).contains("\"n_sustain_after\":7");  // 지속 안정 7 병기
        assertThat(content).contains("frontier_points");
    }

    @Test
    void unknownSession_returnsContractErrorFormat() throws Exception {
        mockMvc.perform(get("/api/recommend/no-such-session"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").isNotEmpty());
    }

    /** SSE는 전용 executor에서 송출되므로 완료 마커가 나타날 때까지 짧게 폴링한다. */
    private String awaitSse(MvcResult result, String marker) throws Exception {
        for (int i = 0; i < 100; i++) {
            String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (content.contains(marker)) {
                return content;
            }
            Thread.sleep(20);
        }
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}

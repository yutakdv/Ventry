package com.ventry.api.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 오류 응답 계약 (계약 §시스템 계약 · BE 리뷰 2026-07-29 C-01).
 *
 * <p><b>클라이언트 입력 문제는 4xx 여야 한다.</b> {@code @ExceptionHandler(Exception.class)} 하나만
 * 있던 동안, 스프링이 기본적으로 4xx 로 답하던 다섯 경우가 전부 <b>500 + INTERNAL_ERROR</b> 로
 * 나갔다 — catch-all 이 {@code DefaultHandlerExceptionResolver} 보다 먼저 평가되기 때문이다.
 * 아래 단언들이 그 회귀를 막는다. 형식({@code error.code}) 도 함께 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ErrorContractTest {

    @Autowired
    private MockMvc mockMvc;

    /** 쿼리 파라미터 타입 오류 — 본문 타입 오류와 같은 성격이므로 같은 400이어야 한다. */
    @Test
    void badQueryParameterType_isBadRequest() throws Exception {
        mockMvc.perform(get("/api/recommend/any-session?v=abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    /** 오타 난 경로는 404다 — 「서버 내부 오류」가 아니다. */
    @Test
    void unknownPath_isNotFound() throws Exception {
        mockMvc.perform(get("/api/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void wrongMethod_isMethodNotAllowed() throws Exception {
        mockMvc.perform(get("/api/diagnose"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void missingContentType_isUnsupportedMediaType() throws Exception {
        mockMvc.perform(post("/api/diagnose").content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void wrongContentType_isUnsupportedMediaType() throws Exception {
        mockMvc.perform(post("/api/diagnose").contentType(MediaType.TEXT_PLAIN).content("hi"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    /** 기존 동작 회귀 방지 — 본문 없음·세션 없음은 그대로 400·404다. */
    @Test
    void missingBody_isBadRequest() throws Exception {
        mockMvc.perform(post("/api/diagnose").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void unknownSession_isNotFound() throws Exception {
        mockMvc.perform(post("/api/check-area/no-such-session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"area_code\":\"A-1101\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"));
    }

    /** 오류 본문에 내부 타입·클래스 경로가 새어 나가면 안 된다. */
    @Test
    void errorMessage_doesNotLeakInternals() throws Exception {
        mockMvc.perform(get("/api/recommend/any-session?v=abc"))
                .andExpect(jsonPath("$.error.message")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("java."))))
                .andExpect(jsonPath("$.error.message")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("com.ventry"))));
    }
}

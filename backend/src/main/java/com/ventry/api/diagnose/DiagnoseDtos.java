package com.ventry.api.diagnose;

import java.util.List;

/** POST /api/diagnose DTO (계약 1번). 금액 만원 단위 정수. */
public final class DiagnoseDtos {

    private DiagnoseDtos() {}

    public record DiagnoseRequest(Form form, String freeText) {}

    public record Form(Integer age, Integer capital, String industry, String regionHint) {}

    public record DiagnoseResponse(String sessionId, ParsedProfile parsedProfile) {}

    /** parse_source: "llm" | "form_only"(LLM 장애 폴백) — DECISIONS.md #5. */
    public record ParsedProfile(Integer age, Integer capital, String industry,
                                String regionHint, List<String> concerns, String parseSource) {}
}

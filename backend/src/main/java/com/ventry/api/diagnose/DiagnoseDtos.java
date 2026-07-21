package com.ventry.api.diagnose;

import java.util.List;

/** POST /api/diagnose DTO (계약 1번). 금액 만원 단위 정수. */
public final class DiagnoseDtos {

    private DiagnoseDtos() {}

    public record DiagnoseRequest(Form form, String freeText) {}

    /**
     * 진단 폼. 금액은 만원 단위 정수이며 **자유 텍스트가 아니라 반드시 이 폼으로** 들어온다
     * (자유 텍스트 경유는 LLM이 수치를 만드는 통로가 되어 불변 원칙 §0-1과 충돌 — DECISIONS.md §8).
     *
     * @param isExistingBusiness  재창업·기존 사업자 여부 — 예비창업자 한정 상품의 자격 정규칙
     * @param collateralAvailable 담보 제공 가능 여부 — 현재 판정 미사용, BE-04 조달 검증 예약
     * @param monthlyInvestable   월 투자 가능액(만원) — BE-04 상환 여력 상한(m ≤ 이 값) 예약
     */
    public record Form(Integer age, Integer capital, Boolean isExistingBusiness,
                       Boolean collateralAvailable, Integer monthlyInvestable,
                       String industry, String regionHint) {}

    public record DiagnoseResponse(String sessionId, ParsedProfile parsedProfile) {}

    /** parse_source: "llm" | "form_only"(LLM 장애 폴백) — DECISIONS.md #5. */
    public record ParsedProfile(Integer age, Integer capital, Boolean isExistingBusiness,
                                Boolean collateralAvailable, Integer monthlyInvestable,
                                String industry, String regionHint,
                                List<String> concerns, String parseSource) {}
}

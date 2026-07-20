package com.ventry.api.scenario;

import com.ventry.api.common.FinanceDtos.Product;
import java.util.List;

/** GET /api/scenarios/{sid} (SSE) · POST /api/budget/{sid} DTO (계약 2·3번). */
public final class ScenarioDtos {

    private ScenarioDtos() {}

    /** SSE `scenario` 이벤트 — 카드 1장씩 송출 (DECISIONS.md #3). */
    public record ScenarioCard(String label, int budget,
                               List<CompositionItem> composition, List<Product> products) {}

    public record ScenarioDone(int scenarioCount) {}

    /** type: equity(자기자본) | guarantee(보증) | policy_loan(정책자금) 등. amount 만원. */
    public record CompositionItem(String type, int amount) {}

    public record BudgetRequest(int confirmedBudget, List<CompositionItem> composition) {}

    public record BudgetResponse(int confirmedBudget, List<CompositionItem> composition) {}
}

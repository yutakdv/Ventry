package com.ventry.api.scenario;

import com.ventry.api.common.FinanceDtos.Product;
import java.util.List;

/** GET /api/scenarios/{sid} (SSE) · POST /api/budget/{sid} DTO (계약 2·3번). */
public final class ScenarioDtos {

    private ScenarioDtos() {}

    /**
     * SSE `scenario` 이벤트 — 카드 1장씩 송출 (DECISIONS.md #3).
     * 예산은 범위로 제시한다: 상품 한도는 공고상 상한일 뿐 승인 금액이 아니므로,
     * 하한(심사와 무관한 확정 재원)과 상한(한도 전액 활용 가정)을 함께 노출한다.
     *
     * @param budget    화면 2 슬라이더 초기 선택값 = <b>budgetMin + 필요분</b>
     *                  (★2026-07-26 계약 변경 — 구: budgetMax). 상한을 초기값으로 두면
     *                  「한도 전액 사용」이 기본 선택이 된다 (DECISIONS §13-3)
     * @param budgetMin 심사와 무관한 확정 재원 합 (자기자본 등)
     * @param budgetMax budgetMin + Σ 상품 한도(amountMax)
     */
    public record ScenarioCard(String label, int budget, int budgetMin, int budgetMax,
                               List<CompositionRange> composition, List<Product> products) {}

    public record ScenarioDone(int scenarioCount) {}

    /** type: equity(자기자본) | guarantee(보증) | policy_loan(정책자금) 등. amount 만원. */
    public record CompositionItem(String type, int amount) {}

    /**
     * 시나리오 카드의 구성 항목 — 슬라이더 위치에 따라 변하므로 범위로 준다 (게이지 렌더용).
     * 확정 시점의 구성({@link CompositionItem})은 단일 금액이다.
     */
    public record CompositionRange(String type, int amountMin, int amountMax) {}

    public record BudgetRequest(int confirmedBudget, List<CompositionItem> composition) {}

    /**
     * @param dataAsOf 프리뷰 수치(환산임대료·유동인구)의 데이터 기준일. 화면 3은 이 값이 없어
     *                 <b>기준일을 표기할 원천이 없는 유일한 화면</b>이었다 (불변 원칙 4 ·
     *                 이슈 #104 ④). 값은 {@code /recommend} 와 같은 {@code data_source_meta.sales}
     */
    public record BudgetResponse(String dataAsOf, int confirmedBudget,
                                 List<CompositionItem> composition, BudgetPreview preview) {}

    /**
     * 확정 예산 기준 프리뷰 (화면 2 슬라이더 즉시 갱신용, DECISIONS.md §9).
     * 진입 후보가 0곳이면 두 range는 null이며 직렬화에서 생략된다.
     *
     * @param areaCount     진입 후보 수 N_entry(B) — expl §2-1 계단 함수
     * @param rentRange     진입 후보의 환산임대료 [최소, 최대] (만원/월)
     * @param floatingRange 진입 후보의 일평균 유동인구 [최소, 최대] (명)
     */
    public record BudgetPreview(int areaCount, List<Integer> rentRange,
                                List<Integer> floatingRange) {}
}

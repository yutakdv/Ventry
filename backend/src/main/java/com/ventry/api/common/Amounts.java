package com.ventry.api.common;

/**
 * 금액 입력의 상식 상한 (QA 리뷰 2026-07-29 Q-01).
 *
 * <p>계약상 모든 금액은 <b>만원 단위 정수</b>다(PROJECT_RULES §1-3). 하한(음수)은 이미 각
 * 컨트롤러가 막고 있었으나 <b>상한이 없었고</b>, 그 결과 {@code capital} 에 {@code int} 최댓값
 * 근처를 넣으면 시나리오 조립에서 {@code budget_max = 자기자본 + 상품 한도} 가 오버플로해
 * {@code GET /api/scenarios/{sid}} 가 500 으로 떨어졌다 (실측 경계 2,147,473,648 —
 * 선택된 상품의 {@code amount_max} 만큼 달라지므로 <b>데이터에 따라 움직이는 경계</b>다).
 *
 * <p>그래서 방어를 두 겹으로 둔다. 여기 상한은 <b>입력단</b>이고, {@code ScenarioBuilder} 는
 * 같은 계산을 {@code long} 으로 해 산술 자체를 안전하게 만든다. 경계가 데이터에 따라 움직이는
 * 이상 입력 검증만으로는 상품 한도가 바뀔 때 다시 열린다.
 *
 * <p>값 1조원(1억 만원)은 {@code AGE_MAX = 100} 과 같은 성격의 <b>오타를 걸러 내는 선</b>이다.
 * 소상공인 창업 자기자본이 닿을 수 없는 자리이면서, 오버플로 경계(약 21억 만원)에는 한참
 * 못 미쳐 상품 한도가 어떻게 바뀌어도 산술이 안전하다.
 */
public final class Amounts {

    private Amounts() {}

    /** 금액 입력 상한 (만원). 1억 만원 = 1조원. */
    public static final int MAX = 100_000_000;

    /** 표시용 — 오류 메시지가 자리수를 직접 세지 않도록. */
    public static final String MAX_LABEL = "100,000,000";
}

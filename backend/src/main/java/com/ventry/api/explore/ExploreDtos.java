package com.ventry.api.explore;

import com.ventry.api.common.FinanceDtos.Source;
import com.ventry.api.common.FinanceDtos.SourceQuote;
import java.util.List;
import java.util.Map;

/** GET /api/explore/{sid}?v= (SSE) DTO — 이벤트 plan/insight/refine/done (계약 5번). */
public final class ExploreDtos {

    private ExploreDtos() {}

    /**
     * @param axes       실행 축 코드 (A1~A4, expl §1)
     * @param axisLabels 코드→화면 라벨. 서버가 송출해 용어 컴플라이언스를 단일 통제한다
     */
    public record PlanEvent(List<String> axes, Map<String, String> axisLabels, String rationale) {}

    /**
     * insight_id는 refine 이벤트가 교체 대상을 지목하는 키.
     *
     * @param marginalPayment     월 상환액 증분(만원). <b>고정금리 근거일 때만</b> 실린다 (계약 D8)
     * @param marginalPaymentNote 변동금리 근거일 때 금액 자리를 대신하는 <b>서버 송출 문구</b>.
     *                            FE 가 하드코딩 사전을 두지 않도록 문구를 서버가 단일 통제한다
     *                            (계약 D8 ③ · assumptions #30). 고정금리면 null → 필드 생략
     */
    public record InsightEvent(String insightId, String type, String headline, Delta delta,
                               Integer gapAmount, Integer marginalPayment,
                               String marginalPaymentNote, Funding funding,
                               boolean disclaimer) {}

    public record Delta(int nEntryBefore, int nEntryAfter, Integer nSustainAfter,
                        Double scoreDelta) {}

    /**
     * rate는 확정 이율이 없으면 null → 응답에서 필드 생략(계약 공통 규약). rate_type은 항상 존재
     * (계약 D8 FE 분기 키), rate_note는 변동 시에만 실린다.
     *
     * <p><b>T1 lead 는 fixed·variable 둘 다 될 수 있다</b> — 실데이터 26건 중 변동금리가 19건이고
     * 그중 13건이 현 분기 금리를 갖는다. 변동금리 근거는 경계를 보고하되 상위
     * {@code marginal_payment} 를 생략하고 {@code marginal_payment_note} 로 대체한다 (계약 D8).
     */
    public record Funding(String name, int amountMax, Double rate, String rateType, String rateNote,
                          int termAssumed, String status, String noticeDate, String exclusiveGroup,
                          Source source, SourceQuote sourceQuote) {}

    public record RefineEvent(String insightId, String headline) {}

    /**
     * @param frontierPoints 계단 함수 좌표 [예산, 진입 후보 수] 배열 (P1 미니 차트)
     * @param currentBudget  현재 확정 예산 B₀ — 차트의 "현재 예산" 마커 좌표
     */
    public record DoneEvent(int scenariosExplored, List<List<Integer>> frontierPoints,
                            int currentBudget) {}
}

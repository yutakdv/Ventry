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

    /** insight_id는 refine 이벤트가 교체 대상을 지목하는 키. */
    public record InsightEvent(String insightId, String type, String headline, Delta delta,
                               Integer gapAmount, Integer marginalPayment, Funding funding,
                               boolean disclaimer) {}

    public record Delta(int nEntryBefore, int nEntryAfter, Integer nSustainAfter,
                        Double scoreDelta) {}

    public record Funding(String name, int amountMax, double rate, int termAssumed,
                          String status, String noticeDate, String exclusiveGroup,
                          Source source, SourceQuote sourceQuote) {}

    public record RefineEvent(String insightId, String headline) {}

    /**
     * @param frontierPoints 계단 함수 좌표 [예산, 진입 후보 수] 배열 (P1 미니 차트)
     * @param currentBudget  현재 확정 예산 B₀ — 차트의 "현재 예산" 마커 좌표
     */
    public record DoneEvent(int scenariosExplored, List<List<Integer>> frontierPoints,
                            int currentBudget) {}
}

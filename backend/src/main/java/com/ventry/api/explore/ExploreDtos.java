package com.ventry.api.explore;

import com.ventry.api.common.FinanceDtos.Source;
import com.ventry.api.common.FinanceDtos.SourceQuote;
import java.util.List;

/** GET /api/explore/{sid}?v= (SSE) DTO — 이벤트 plan/insight/refine/done (계약 5번). */
public final class ExploreDtos {

    private ExploreDtos() {}

    public record PlanEvent(List<String> axes, String rationale) {}

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

    public record DoneEvent(int scenariosExplored, List<List<Integer>> frontierPoints) {}
}

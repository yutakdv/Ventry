package com.ventry.api.checkarea;

import com.ventry.api.common.FinanceDtos.Product;
import com.ventry.api.common.FinanceDtos.RiskReview;
import com.ventry.api.common.MockData;
import com.ventry.api.common.SessionStore;
import com.ventry.api.common.Verdict;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** POST /api/check-area/{sid} — 역방향 판정 목 (BE-01). 실구현은 BE-03 reverse_check. */
@RestController
public class CheckAreaController {

    private final SessionStore sessions;

    public CheckAreaController(SessionStore sessions) {
        this.sessions = sessions;
    }

    public record CheckAreaRequest(String areaCode) {}

    public record CheckAreaResponse(Verdict verdict, int gapAmount,
                                    List<Product> matchingProducts, RiskReview riskReview) {}

    @PostMapping("/api/check-area/{sid}")
    public CheckAreaResponse checkArea(@PathVariable String sid,
                                       @RequestBody CheckAreaRequest request) {
        sessions.get(sid);
        return new CheckAreaResponse(Verdict.CONDITIONAL, 1320,
                List.of(MockData.matchingProduct()), MockData.checkAreaReview());
    }
}

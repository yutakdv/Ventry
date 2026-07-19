package com.ventry.api.recommend;

import com.ventry.api.common.MockData;
import com.ventry.api.common.SessionStore;
import com.ventry.api.recommend.RecommendDtos.RecommendResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/recommend/{sid} — 목 판정 3곳(🟢2 🟠1) + risk_review (BE-01).
 * 실구현(이중 필터→점수→판정 + reason_text 템플릿)은 BE-03에서 교체.
 */
@RestController
public class RecommendController {

    private final SessionStore sessions;

    public RecommendController(SessionStore sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/api/recommend/{sid}")
    public RecommendResponse recommend(@PathVariable String sid,
                                       @RequestParam(name = "v", defaultValue = "0") long version) {
        SessionStore.SessionState state = sessions.get(sid);
        state.acceptVersion(version); // /explore와 동일 version 공유 (계약 공통 규약)
        return MockData.recommend();
    }
}

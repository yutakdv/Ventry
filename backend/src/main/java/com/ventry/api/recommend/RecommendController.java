package com.ventry.api.recommend;

import com.ventry.api.common.SessionStore;
import com.ventry.api.recommend.RecommendDtos.RecommendResponse;
import com.ventry.api.serving.LocationService;
import com.ventry.api.serving.SessionMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/recommend/{sid} — 이중 필터 → 점수 → 판정 4단계 + reason_text (BE-03f).
 * 결정적 도구 계층(engine)을 LocationService가 오케스트레이션. LLM 의존성 0.
 */
@RestController
public class RecommendController {

    private final SessionStore sessions;
    private final LocationService locationService;

    public RecommendController(SessionStore sessions, LocationService locationService) {
        this.sessions = sessions;
        this.locationService = locationService;
    }

    @GetMapping("/api/recommend/{sid}")
    public RecommendResponse recommend(@PathVariable String sid,
                                       @RequestParam(name = "v", defaultValue = "0") long version) {
        SessionStore.SessionState state = sessions.get(sid);
        state.acceptVersion(version); // /explore와 동일 version 공유 (계약 공통 규약)
        return locationService.recommend(SessionMapper.profile(state), SessionMapper.budget(state));
    }
}

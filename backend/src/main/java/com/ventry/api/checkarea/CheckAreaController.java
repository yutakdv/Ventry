package com.ventry.api.checkarea;

import com.ventry.api.checkarea.CheckAreaDtos.CheckAreaRequest;
import com.ventry.api.checkarea.CheckAreaDtos.CheckAreaResponse;
import com.ventry.api.common.SessionStore;
import com.ventry.api.serving.LocationService;
import com.ventry.api.serving.SessionMapper;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/check-area/{sid} — 역방향 판정 4단계 + 부족분 + 자격 부합 상품 (BE-03f).
 * ReverseCheck·EligibilityFilter 결선. source_quote(RAG)는 P1(BE-06)까지 null.
 */
@RestController
public class CheckAreaController {

    private final SessionStore sessions;
    private final LocationService locationService;

    public CheckAreaController(SessionStore sessions, LocationService locationService) {
        this.sessions = sessions;
        this.locationService = locationService;
    }

    @PostMapping("/api/check-area/{sid}")
    public CheckAreaResponse checkArea(@PathVariable String sid,
                                       @RequestBody CheckAreaRequest request) {
        SessionStore.SessionState state = sessions.get(sid);
        return locationService.checkArea(SessionMapper.profile(state), SessionMapper.budget(state),
                request.areaCode());
    }
}

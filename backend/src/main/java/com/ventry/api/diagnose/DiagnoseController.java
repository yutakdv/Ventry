package com.ventry.api.diagnose;

import com.ventry.api.common.SessionStore;
import com.ventry.api.diagnose.DiagnoseDtos.DiagnoseRequest;
import com.ventry.api.diagnose.DiagnoseDtos.DiagnoseResponse;
import com.ventry.api.diagnose.DiagnoseDtos.Form;
import com.ventry.api.diagnose.DiagnoseDtos.ParsedProfile;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/diagnose — 진단 폼 + 자유 텍스트 처리 (BE-01).
 *
 * <p>자유 텍스트는 <b>키워드 매칭</b>으로 concerns 를 추출한다. LLM 실파싱은 아직 붙지 않았고,
 * 그래서 {@code parse_source} 는 <b>항상 {@code "form_only"}</b> 다 — 하지 않은 일을 했다고
 * 말하지 않기 위해서다 (BE-07 통합 QA 지적).
 */
@RestController
public class DiagnoseController {

    private final SessionStore sessions;

    public DiagnoseController(SessionStore sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/api/diagnose")
    public DiagnoseResponse diagnose(@RequestBody DiagnoseRequest request) {
        Form form = request.form() != null ? request.form()
                : new Form(null, null, null, null, null, null, null);
        String freeText = request.freeText() != null ? request.freeText() : "";

        List<String> concerns = new ArrayList<>();
        if (freeText.contains("권리금")) {
            concerns.add("premium");
        }
        if (freeText.contains("임대") || freeText.contains("월세")) {
            concerns.add("rent");
        }
        if (freeText.contains("유동") || freeText.contains("손님")) {
            concerns.add("traffic");
        }

        // 항상 form_only 다 — 위 concerns 추출은 키워드 매칭이고 LLM 호출이 아니다 (BE-07).
        // 구 구현은 free_text 가 비어 있지 않으면 "llm" 을 실었는데, API 키를 지운 스택에서도
        // 그대로 "llm" 이 나갔다. 하지 않은 일을 했다고 말하는 필드가 되어 있었다.
        // LLM 실파싱을 붙이는 시점에 그 호출의 성공 여부로 이 값을 정한다.
        String parseSource = "form_only";
        ParsedProfile profile = new ParsedProfile(form.age(), form.capital(),
                form.isExistingBusiness(), form.collateralAvailable(), form.monthlyInvestable(),
                form.industry(), form.regionHint(), concerns, parseSource);
        SessionStore.SessionState state = sessions.create(profile);
        return new DiagnoseResponse(state.id(), profile);
    }
}

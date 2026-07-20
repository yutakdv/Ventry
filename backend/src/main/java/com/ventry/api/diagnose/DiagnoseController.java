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
 * POST /api/diagnose — 목 파싱 (BE-01). 자유 텍스트는 키워드 매칭으로 concerns를 추출한다.
 * LLM 실파싱(claude-haiku-4-5)은 BE-05에서 교체 — 장애 시 폼 값만 사용(parse_source=form_only).
 */
@RestController
public class DiagnoseController {

    private final SessionStore sessions;

    public DiagnoseController(SessionStore sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/api/diagnose")
    public DiagnoseResponse diagnose(@RequestBody DiagnoseRequest request) {
        Form form = request.form() != null ? request.form() : new Form(null, null, null, null);
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

        String parseSource = freeText.isBlank() ? "form_only" : "llm";
        ParsedProfile profile = new ParsedProfile(form.age(), form.capital(), form.industry(),
                form.regionHint(), concerns, parseSource);
        SessionStore.SessionState state = sessions.create(profile);
        return new DiagnoseResponse(state.id(), profile);
    }
}

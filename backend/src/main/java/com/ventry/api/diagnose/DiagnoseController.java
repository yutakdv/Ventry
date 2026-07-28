package com.ventry.api.diagnose;

import com.ventry.api.common.ApiException;
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

    /** 후보 조회 그레인이 area_code × industry 라 업종 없이는 어떤 계산도 성립하지 않는다. */
    private static final List<String> INDUSTRIES = List.of("cafe", "food");

    /**
     * 나이 허용 범위 — 만 나이 기준.
     *
     * <p>하한 15는 「만 15세 미만은 근로·사업 개시가 제한된다」가 아니라 <b>오타를 걸러 내는
     * 선</b>이다. 자격 판정에 쓰는 상품 조건의 하한은 없고 상한만 있어서(청년 만 39세 이하),
     * 이 검증이 막는 것은 음수·0 같은 값이 상한 조건을 통과해 버리는 경로다 (이슈 #155 ①).
     * 상한 100은 같은 성격의 상식선이다 — 200이 200으로 계산되는 것을 막는다.
     */
    private static final int AGE_MIN = 15;
    private static final int AGE_MAX = 100;

    @PostMapping("/api/diagnose")
    public DiagnoseResponse diagnose(@RequestBody DiagnoseRequest request) {
        Form form = request.form() != null ? request.form()
                : new Form(null, null, null, null, null, null, null);
        validate(form);
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

    /**
     * 입력 규격 검증 — <b>세션을 만들기 전에</b> 막는다 (BE 리뷰 D-09).
     *
     * <p>본문 {@code {}} 로도 200 + 세션이 발급됐고, 그 세션은 이후 {@code /recommend}·
     * {@code /scenarios}·{@code /explore} 를 전부 500으로 만들었다
     * ({@code Null key returned for cache operation}). 오류를 늦게 드러내면 원인이 먼 곳에서 난다.
     *
     * <p>업종은 <b>화이트리스트</b>로 검증한다 — 오타(`cafee`)가 조용히 빈 후보 목록이 되면
     * 사용자는 "우리 동네엔 후보가 없구나"로 읽는다.
     *
     * <p><b>필수와 규격을 구분한다.</b> {@code industry}·{@code capital} 은 없으면 계산이
     * 성립하지 않아 필수지만, {@code age}·{@code monthly_investable} 은 폼에서 선택 입력이라
     * 없어도 된다 — 대신 <b>들어온 값이 규격을 벗어나면</b> 막는다. 미기재와 잘못된 값은
     * 다른 사건이고, 미기재를 0으로 바꿔 통과시킨 것이 이슈 #155 의 원인이었다.
     */
    private static void validate(Form form) {
        if (form.industry() == null || form.industry().isBlank()) {
            throw ApiException.invalidRequest("industry 는 필수입니다 (cafe | food).");
        }
        if (!INDUSTRIES.contains(form.industry())) {
            throw ApiException.invalidRequest(
                    "지원하지 않는 industry 입니다: " + form.industry() + " (cafe | food)");
        }
        if (form.capital() == null) {
            throw ApiException.invalidRequest("capital 은 필수입니다 (만원 단위 정수).");
        }
        if (form.capital() < 0) {
            throw ApiException.invalidRequest("capital 은 음수일 수 없습니다.");
        }
        // age 미기재는 통과시킨다 — 자격 판정에서 나이 조건 상품이 빠지는 것으로 처리된다
        // (EligibilityFilter, 가정 #86). 들어온 값이 범위 밖일 때만 막는다.
        if (form.age() != null && (form.age() < AGE_MIN || form.age() > AGE_MAX)) {
            throw ApiException.invalidRequest(
                    "age 는 " + AGE_MIN + "~" + AGE_MAX + " 범위여야 합니다: " + form.age());
        }
        // 음수 월 투자 가능액은 상환 여력 상한을 음수로 만들어 모든 경계를 REPAYMENT_OVER 로
        // 떨어뜨린다 — 200 + "유의미한 대안이 없습니다"로 나가 정상처럼 보인다 (이슈 #155 ②).
        if (form.monthlyInvestable() != null && form.monthlyInvestable() < 0) {
            throw ApiException.invalidRequest("monthly_investable 은 음수일 수 없습니다.");
        }
    }
}

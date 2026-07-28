package com.ventry.api.serving;

import com.ventry.api.common.SessionStore.SessionState;
import com.ventry.api.diagnose.DiagnoseDtos.ParsedProfile;
import com.ventry.api.engine.FundingInput;
import com.ventry.api.engine.Profile;

/** 세션 상태 → engine 입력 매핑. 예산 미확정 시 자기자본만 구간으로 폴백. */
public final class SessionMapper {

    private SessionMapper() {}

    /**
     * 나이는 <b>강등하지 않고 그대로</b> 넘긴다 — {@code nz()} 로 0을 만들면 「만 39세 이하」
     * 상품이 미기재 사용자에게 편성된다 (이슈 #155 ①). 미기재의 처리는 자격 필터 소관이다.
     */
    public static Profile profile(SessionState state) {
        ParsedProfile p = state.profile();
        return new Profile(p.age(), nz(p.capital()),
                Boolean.TRUE.equals(p.isExistingBusiness()),   // 미기재는 예비창업자로 취급
                p.industry(), p.regionHint());
    }

    /**
     * 조달 검증 입력 — 진단 폼의 월 투자 가능액·담보 여부를 그대로 옮긴다.
     * monthly_investable 미기재(null)면 상환 여력 상한이 없는 것으로 본다 (assumptions #23).
     */
    public static FundingInput fundingInput(SessionState state) {
        ParsedProfile p = state.profile();
        return new FundingInput(p.monthlyInvestable(),
                Boolean.TRUE.equals(p.collateralAvailable()));
    }

    /**
     * 확정 예산(B₀)이 있으면 그 값, 없으면 자기자본만(무권리 진입 구간) — 가정 #88.
     *
     * <p>이 폴백은 계산을 맞게 하지만 <b>"예산을 확정하지 않았다"는 사실을 응답에 남기지 않는다</b>.
     * 계약에 해당 필드가 없어 표기 계층에서 막는다 — FE 가 화면 3 진입 자체를 예산 확정 뒤로
     * 보내므로 데모·심사 동선에서는 재현되지 않고, API 를 직접 부를 때만 노출된다.
     */
    public static int budget(SessionState state) {
        Integer confirmed = state.confirmedBudget();
        return confirmed != null ? confirmed : nz(state.profile().capital());
    }

    private static int nz(Integer value) {
        return value != null ? value : 0;
    }
}

package com.ventry.api.serving;

import com.ventry.api.common.SessionStore.SessionState;
import com.ventry.api.diagnose.DiagnoseDtos.ParsedProfile;
import com.ventry.api.engine.FundingInput;
import com.ventry.api.engine.Profile;

/** 세션 상태 → engine 입력 매핑. 예산 미확정 시 자기자본만 구간으로 폴백. */
public final class SessionMapper {

    private SessionMapper() {}

    public static Profile profile(SessionState state) {
        ParsedProfile p = state.profile();
        return new Profile(nz(p.age()), nz(p.capital()),
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

    /** 확정 예산(B₀)이 있으면 그 값, 없으면 자기자본만(무권리 진입 구간). */
    public static int budget(SessionState state) {
        Integer confirmed = state.confirmedBudget();
        return confirmed != null ? confirmed : nz(state.profile().capital());
    }

    private static int nz(Integer value) {
        return value != null ? value : 0;
    }
}

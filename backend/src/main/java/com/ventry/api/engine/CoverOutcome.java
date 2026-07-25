package com.ventry.api.engine;

import java.util.Optional;

/**
 * BE-05 — 조달 검증 결과 + <b>제외 사유</b> (exploration spec §2-2 ③).
 * 커버 실패는 그 자체로 정보다 — "왜 이 경계를 보고하지 않았는가"를 남기지 않으면
 * 인사이트 0건이 장애인지 정상인지 구분할 수 없다.
 *
 * @param plan   커버 성공 시 조달 명세, 실패 시 empty
 * @param reason 성공이면 {@link Reason#NONE}, 실패면 그 사유
 */
public record CoverOutcome(Optional<FundingPlan> plan, Reason reason) {

    /** 커버 실패 사유 — 화면 노출이 아니라 로그·0건 사유 문장의 재료다. */
    public enum Reason {
        /** 커버 성공. */
        NONE,
        /** gap ≤ 0 — 상향 경계 자체가 없다. */
        NO_BOUNDARY,
        /** 확정 이율 미상 상품을 빼고 나니 한도가 모자랐다 — 가정 금리를 만들지 않은 결과 (#28). */
        RATE_UNKNOWN,
        /** 자격·중복수혜 제약 하의 잔여 한도 합이 gap에 미달. */
        LIMIT_SHORT,
        /** 조달은 되지만 월 상환액 m이 월 투자 가능액을 초과 (#23). */
        REPAYMENT_OVER
    }

    public static CoverOutcome covered(FundingPlan plan) {
        return new CoverOutcome(Optional.of(plan), Reason.NONE);
    }

    public static CoverOutcome excluded(Reason reason) {
        return new CoverOutcome(Optional.empty(), reason);
    }

    public boolean isCovered() {
        return plan.isPresent();
    }
}

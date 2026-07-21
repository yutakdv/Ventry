package com.ventry.api.serving;

import com.ventry.api.common.FinanceDtos.RiskReview;
import com.ventry.api.common.Verdict;

/**
 * reason_text·risk_review 템플릿 (f-string 상당) — LLM 의존성 0 (BE-03f).
 * 용어 컴플라이언스(§0-4): "추천/권장" 등 자문성 술어 금지, 정보 서술형만.
 * BE-05에서 LLM refine이 도착 시 교체되며, 장애 시 이 템플릿이 최종본.
 */
public final class ReasonTemplate {

    private ReasonTemplate() {}

    public static String reason(String name, Verdict verdict, double burdenRatio) {
        long pct = Math.round(burdenRatio * 100);
        return switch (verdict) {
            case FIT -> name + " — 길단위 유동·배후 인구가 서울 상위 구간이며 환산임대료 부담률 "
                    + pct + "%로 임계 이내입니다.";
            case CAUTION -> name + " — 수요 지표는 상위 구간이나 환산임대료 부담률 "
                    + pct + "%로 임계를 초과합니다.";
            case CONDITIONAL -> name + " — 권리금 포함 시 예산을 초과하나, 무권리 매물 확보 시 "
                    + "진입 가능한 구간입니다.";
            case OUT_OF_SCOPE -> name + " — 현재 예산 기준으로는 진입 범위 밖입니다.";
        };
    }

    public static RiskReview recommendReview() {
        return new RiskReview(
                "수요 상위 상권일수록 경쟁밀도가 높아, 추정매출 하위 시나리오에서는 부담률이 임계를 "
                        + "넘을 수 있습니다. 유의 판정 유지가 타당합니다.",
                true, false);
    }

    public static RiskReview checkAreaReview() {
        return new RiskReview(
                "권리금 포함 비용 구간 상단을 기준으로 하면 부족분이 더 커질 수 있어, 구간 하단 기준 "
                        + "판정임을 함께 표기해야 합니다.",
                true, false);
    }
}

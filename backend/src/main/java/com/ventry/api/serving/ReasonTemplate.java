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

    /**
     * 추천 결과 반박 템플릿 — <b>판정 분포로 갈린다</b> (BE 리뷰 D-24 · 이슈 #104 ②).
     *
     * <p>구 구현은 인자 없는 상수라 「유의 판정 유지가 타당합니다」로 끝났는데, 유의가 0곳인
     * 구간(예: 진입 후보가 없어 전부 범위 외·조건부)에서는 <b>없는 판정을 유지하라</b>는 반박이
     * 된다. 이 문장은 LLM 장애 시 <b>최종본</b>이고(스펙 §5-3), 키가 없는 스택에서는 모든
     * 구간에서 이것이 나간다.
     *
     * <p>분포 3개 값은 이미 확정된 판정을 <b>세기만</b> 한 것이라 새 계산식이 아니다(§0-1).
     * LLM 사실 목록에는 넣지 않는다 — 예산이 한 칸 움직일 때마다 바뀌어 캐시가 빗나가면
     * assumptions #64 가 지킨 슬라이더 성능이 무너진다.
     *
     * @param entered     진입 후보 수 (적합 + 유의)
     * @param caution     유의 판정 수
     * @param conditional 조건부 적합 수
     */
    public static RiskReview recommendReview(int entered, int caution, int conditional) {
        String text;
        if (entered == 0) {
            text = "현재 예산으로 진입하는 후보가 없어 제시된 " + conditional + "곳은 전부 조건부 적합입니다. "
                    + "무권리 매물 확보를 전제로 한 판정이므로, 그 전제가 성립하지 않으면 진입 가능 구간이 아닙니다.";
        } else if (caution > 0) {
            text = "수요 상위 상권일수록 경쟁밀도가 높아, 추정매출 하위 시나리오에서는 부담률이 임계를 "
                    + "넘을 수 있습니다. 유의 판정 " + caution + "곳이 그 구간에 있습니다.";
        } else {
            text = "진입 후보 " + entered + "곳이 전부 부담률 임계 이내이나, 추정매출은 분기 평균 기준이라 "
                    + "하위 시나리오에서는 임계를 넘을 수 있습니다.";
        }
        return new RiskReview(text, true, false);
    }

    public static RiskReview checkAreaReview() {
        return new RiskReview(
                "권리금 포함 비용 구간 상단을 기준으로 하면 부족분이 더 커질 수 있어, 구간 하단 기준 "
                        + "판정임을 함께 표기해야 합니다.",
                true, false);
    }
}

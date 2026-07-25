package com.ventry.api.engine;

import java.util.Comparator;
import java.util.List;

/**
 * BE-05 — 인사이트 목적함수와 컷 (순수 함수, exploration spec §2-4).
 *
 * <pre>
 * Q = 1·ΔN_green + 1·ΔS_top(등급 구간) + 2·ΔN_sustain     품질가중 임팩트
 * F = 조달 커버 완전성 × 조건 단순성                        (커버 실패 경계는 애초에 제외)
 * C = 1 + m / 월 추정매출 중앙값                            조달 비용 페널티
 * score = Q × F / C
 * </pre>
 *
 * <p><b>보고는 선택이다.</b> 프론티어 전체를 계산하되 품질 조건(ΔN_green ≥ 1 또는 ΔS_top ≥ 1구간)을
 * 넘는 경계만 보고하며, 전부 미달이면 0건이 정상 출력이다 — 억지로 채우지 않는다 (§0-2 ③).
 *
 * <p><b>지속 계수를 2배로 둔 것</b>은 "버틸 수 있는 곳"이라는 서비스 철학의 반영이며 문헌 근거가
 * 아니다 (assumptions #27). 그 결과 ΔN_sustain이 큰 <b>대형 차입 경계가 상위로 뽑히는 경향</b>이
 * 있는데, 이는 상향 단독 노출 금지·T2 안전마진 동반·고지 문구의 3중 방어로 상쇄한다.
 */
public final class InsightScore {

    /** Q의 가중치 (assumptions #27 — 초기값, 조정 가능). */
    public static final double W_GREEN = 1.0;
    public static final double W_SCORE_GRADE = 1.0;
    public static final double W_SUSTAIN = 2.0;

    /** 품질 조건 임계 — 스펙의 ΔN_green ≥ 2에서 완화 (assumptions #27). */
    public static final int MIN_DELTA_GREEN = 1;

    /** score 상위 보고 슬롯 수. 나머지 1슬롯은 T2 안전 마진 몫이다 (총 3건, §2-4). */
    public static final int MAX_SCORED_REPORTS = 2;

    /** 정렬 결정성 확보용 스코어 양자화 배율 — 부동소수 잔차로 순위가 흔들리지 않게 한다. */
    private static final double RANK_SCALE = 1e6;

    private InsightScore() {}

    // ── 목적함수 ──────────────────────────────────────────────────────────

    /** Q = 1·ΔN_green + 1·ΔS_top(등급) + 2·ΔN_sustain. ΔN_sustain 음수면 Q가 깎인다. */
    public static double quality(BoundaryEval eval) {
        return W_GREEN * eval.deltaNGreen()
                + W_SCORE_GRADE * eval.deltaScoreGrades()
                + W_SUSTAIN * eval.deltaNSustain();
    }

    /**
     * F = 커버 완전성 × 조건 단순성. 커버 실패 경계는 보고 대상이 아니므로 완전성은 1이고,
     * 단순성은 편성 상품 수의 역수다 — 여러 상품을 엮어야 닿는 경계일수록 실행 난도가 높다.
     */
    public static double feasibility(BoundaryEval eval) {
        return 1.0 / Math.max(1, eval.fundingProductCount());
    }

    /**
     * C = 1 + m / 월 추정매출 중앙값. m=0이면 1(페널티 없음)이며,
     * 중앙값이 0 이하(후보 없음·매출 결측)면 페널티를 계산할 근거가 없어 중립값 1을 쓴다.
     */
    public static double cost(BoundaryEval eval, double medianSales) {
        if (medianSales <= 0) {
            return 1.0;
        }
        return 1.0 + eval.monthlyPayment() / medianSales;
    }

    public static double score(BoundaryEval eval, double medianSales) {
        return quality(eval) * feasibility(eval) / cost(eval, medianSales);
    }

    // ── 컷 ───────────────────────────────────────────────────────────────

    /**
     * 품질 조건: ΔN_green ≥ 1 <b>또는</b> ΔS_top이 1구간 이상 개선.
     * N₀=1→2 같은 무의미 발동과 저품질 대량 편입을 걸러내기 위한 관문이다 (§2-4).
     */
    public static boolean meetsQualityGate(BoundaryEval eval) {
        return eval.deltaNGreen() >= MIN_DELTA_GREEN || eval.deltaScoreGrades() >= 1;
    }

    /**
     * 보고할 경계를 최대 {@value #MAX_SCORED_REPORTS}건 고른다.
     * 커버 실패 경계는 <b>여기서 제외</b>되지만 입력 리스트에는 남아 있어
     * {@link #evaluatedCount}가 세는 "평가한 경계"에는 계속 포함된다 (assumptions #29).
     */
    public static List<BoundaryEval> topBoundaries(List<BoundaryEval> evals, double medianSales) {
        return evals.stream()
                .filter(BoundaryEval::covered)
                .filter(InsightScore::meetsQualityGate)
                .sorted(ranking(medianSales))
                .limit(MAX_SCORED_REPORTS)
                .toList();
    }

    /**
     * 순위 규칙: ① score 내림차순 ② <b>동점이면 갭이 작은 쪽(덜 빌리는 쪽)</b> ③ 그래도 같으면 경계값 오름차순.
     *
     * <p>동점 우선순위를 "덜 빌리는 쪽"으로 둔 것은 불변 원칙 §0-2-4("더 빌려라가 아니다")와 정합하며,
     * score를 정수로 양자화해 비교하므로 부동소수 잔차로 순위가 뒤집히지 않는다(전순서 보장).
     */
    public static Comparator<BoundaryEval> ranking(double medianSales) {
        return Comparator
                .comparingLong((BoundaryEval e) -> -Math.round(score(e, medianSales) * RANK_SCALE))
                .thenComparingInt(BoundaryEval::gap)
                .thenComparingInt(BoundaryEval::boundary);
    }

    /**
     * {@code scenarios_explored} = 평가한 경계 개수 (assumptions #29).
     * 보고 건수가 아니라 <b>검토 건수</b>이며, 커버 실패로 제외된 경계도 포함한다.
     */
    public static int evaluatedCount(List<BoundaryEval> evals) {
        return evals.size();
    }

    // ── 보조 계산 ─────────────────────────────────────────────────────────

    /**
     * 진입 후보 중 최고 종합점수(0~100). 점수 산식은 {@link ScoreLookup#score}를 그대로 쓰고
     * 노출 단위 투영만 여기서 한다 (assumptions #8 — LocationService와 동일 규칙).
     */
    public static int topScore(List<AxisScores> entered, Weights weights) {
        return entered.stream()
                .mapToInt(axes -> (int) Math.round(ScoreLookup.score(axes, weights) * 100))
                .max()
                .orElse(0);
    }

    /** 월 추정매출 중앙값(C의 분모). 후보가 없으면 0 — 호출부는 그때 페널티를 걸지 않는다. */
    public static double medianSales(List<SustainInput> pool) {
        if (pool.isEmpty()) {
            return 0.0;
        }
        int[] sorted = pool.stream().mapToInt(SustainInput::estSales).sorted().toArray();
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1
                ? sorted[mid]
                : (sorted[mid - 1] + sorted[mid]) / 2.0;
    }
}

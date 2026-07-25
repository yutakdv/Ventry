package com.ventry.api.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.ventry.api.engine.CoverOutcome.Reason;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * BE-05 InsightScore — 목적함수·품질 조건·컷 (exploration spec §2-4, assumptions #27).
 * 보고는 선택이며 0건도 유효한 결과다 — 억지로 채우지 않는지까지 검증한다.
 */
class InsightScoreTest {

    private static final Weights WEIGHTS = new Weights(0.30, 0.20, 0.20, 0.15, 0.15);

    /** 커버 성공 경계. 스코어에 쓰이지 않는 진입 수는 3→4로 고정한다. */
    private static BoundaryEval covered(int boundary, int gap, double payment, int products,
                                        int greenBefore, int greenAfter,
                                        int sustainBefore, int sustainAfter,
                                        int topBefore, int topAfter) {
        return new BoundaryEval(boundary, gap, payment, products, 3, 4,
                greenBefore, greenAfter, sustainBefore, sustainAfter,
                topBefore, topAfter, Reason.NONE);
    }

    /** 품질은 높지만 조달 수단이 없어 보고에서 빠지는 경계. */
    private static BoundaryEval uncovered(int boundary, int gap, Reason reason) {
        return new BoundaryEval(boundary, gap, 0.0, 0, 3, 9,
                1, 6, 2, 8, 70, 95, reason);
    }

    // ── #14 목적함수 ──────────────────────────────────────────────────────

    /** ΔN_green 1 + ΔS_top 1구간 + ΔN_sustain 2 → Q = 1 + 1 + 2×2 = 6. */
    @Test
    void quality_appliesDecidedWeights() {
        BoundaryEval eval = covered(9420, 1420, 25.21, 1, 1, 2, 2, 4, 75, 82);

        assertThat(eval.deltaNGreen()).isEqualTo(1);
        assertThat(eval.deltaScoreGrades()).isEqualTo(1);   // 70대 → 80대
        assertThat(eval.deltaNSustain()).isEqualTo(2);
        assertThat(InsightScore.quality(eval)).isEqualTo(6.0);

        // ΔS_top의 원천은 ScoreLookup.score 그대로다 — 점수 산식을 새로 만들지 않았음을 못 박는다
        AxisScores mangwon = new AxisScores(0.82, 0.74, 0.68, 0.71, 0.77);
        AxisScores hongdae = new AxisScores(0.91, 0.88, 0.31, 0.74, 0.52);
        assertThat(InsightScore.topScore(List.of(mangwon), WEIGHTS))
                .isEqualTo((int) Math.round(ScoreLookup.score(mangwon, WEIGHTS) * 100))
                .isEqualTo(75);
        assertThat(InsightScore.topScore(List.of(hongdae, mangwon), WEIGHTS)).isEqualTo(75);
        assertThat(InsightScore.topScore(List.of(), WEIGHTS)).isZero();
    }

    /** 지속 후보가 줄어드는(비단조) 경계는 Q가 깎인다 — 차입 확대가 자동으로 이득이 되지 않는다. */
    @Test
    void quality_isPenalisedWhenSustainDrops() {
        BoundaryEval shrinking = covered(9980, 1980, 35.15, 1, 1, 2, 5, 3, 75, 75);
        assertThat(shrinking.deltaNSustain()).isEqualTo(-2);
        assertThat(InsightScore.quality(shrinking)).isEqualTo(1 + 0 + 2 * -2);   // = −3
    }

    // ── #15 품질 조건 ─────────────────────────────────────────────────────

    @Test
    void qualityGate_needsGreenGainOrGradeGain() {
        // ΔN_green 0 · 등급 개선 0 → 탈락 (무의미 발동 차단)
        assertThat(InsightScore.meetsQualityGate(covered(8280, 280, 4.97, 1, 2, 2, 2, 3, 75, 75)))
                .isFalse();
        // ΔN_green 1 → 통과 (스펙 ≥2에서 완화, assumptions #27)
        assertThat(InsightScore.meetsQualityGate(covered(8280, 280, 4.97, 1, 2, 3, 2, 3, 75, 75)))
                .isTrue();
        // ΔN_green 0 이지만 등급 1구간 개선 → 통과
        assertThat(InsightScore.meetsQualityGate(covered(8280, 280, 4.97, 1, 2, 2, 2, 3, 79, 80)))
                .isTrue();
        // 원점수는 8점 올랐지만 같은 70대 구간 → 탈락 (구간 기준이 원점수 기준이 아님을 확인)
        assertThat(InsightScore.meetsQualityGate(covered(8280, 280, 4.97, 1, 2, 2, 2, 3, 71, 79)))
                .isFalse();
    }

    // ── #16 조달 비용 페널티 C ────────────────────────────────────────────

    @Test
    void cost_isNeutralWithoutBorrowingOrSalesDenominator() {
        BoundaryEval borrowed = covered(9420, 1420, 25.205, 1, 1, 2, 2, 4, 75, 82);
        assertThat(InsightScore.cost(borrowed, 1950)).isCloseTo(1 + 25.205 / 1950, within(1e-9));

        // m=0 → 페널티 없음
        assertThat(InsightScore.cost(covered(7950, 150, 0.0, 1, 1, 2, 2, 3, 75, 75), 1950))
                .isEqualTo(1.0);
        // 매출 중앙값 0(후보 없음·결측) → 0으로 나누지 않고 중립값 1
        assertThat(InsightScore.cost(borrowed, 0)).isEqualTo(1.0);
        assertThat(InsightScore.cost(borrowed, -5)).isEqualTo(1.0);

        assertThat(InsightScore.medianSales(List.of())).isZero();
        assertThat(InsightScore.medianSales(List.of(
                new SustainInput(198, 1800, 7750), new SustainInput(273, 2100, 7950))))
                .isEqualTo(1950.0);                                  // 짝수 개 → 두 중앙값 평균
        assertThat(InsightScore.medianSales(List.of(
                new SustainInput(256, 1600, 9320), new SustainInput(198, 1800, 7750),
                new SustainInput(273, 2100, 7950))))
                .isEqualTo(1800.0);                                  // 홀수 개 → 가운데 값
    }

    // ── #17 컷: 보고 대상 ≠ 평가 대상 ─────────────────────────────────────

    /**
     * 보고는 최대 2건(나머지 1슬롯은 T2 몫)이며 커버 실패 경계는 <b>스코어 대상에서 제외</b>된다.
     * 그러나 평가 건수({@code scenarios_explored})에는 그대로 남는다 — 서로 다른 집합이다 (#29).
     */
    @Test
    void topBoundaries_excludeUncovered_butEvaluatedCountKeepsThem() {
        BoundaryEval strong = covered(9420, 1420, 25.21, 1, 1, 3, 2, 5, 75, 82);
        BoundaryEval weaker = covered(8660, 660, 11.72, 1, 1, 2, 2, 3, 75, 75);
        BoundaryEval gateFail = covered(8280, 280, 4.97, 1, 2, 2, 2, 2, 75, 75);
        BoundaryEval rateUnknown = uncovered(9320, 1320, Reason.RATE_UNKNOWN);
        BoundaryEval limitShort = uncovered(14390, 6390, Reason.LIMIT_SHORT);
        List<BoundaryEval> all = List.of(strong, weaker, gateFail, rateUnknown, limitShort);

        List<BoundaryEval> reported = InsightScore.topBoundaries(all, 1950);

        assertThat(reported).hasSize(InsightScore.MAX_SCORED_REPORTS);
        assertThat(reported).containsExactly(strong, weaker);
        assertThat(reported).noneMatch(e -> e.reason() == Reason.RATE_UNKNOWN);
        assertThat(reported).noneMatch(e -> e.reason() == Reason.LIMIT_SHORT);
        assertThat(reported).doesNotContain(gateFail);

        // 평가한 경계는 커버 실패·품질 미달까지 전부 포함한 5건
        assertThat(InsightScore.evaluatedCount(all)).isEqualTo(5);
        assertThat(InsightScore.evaluatedCount(all)).isGreaterThan(reported.size());
    }

    // ── #18 0건 보고 ──────────────────────────────────────────────────────

    /** 전 경계가 품질 조건 미달이면 빈 리스트다 — 슬롯을 채우려고 저품질 경계를 올리지 않는다. */
    @Test
    void allBelowQualityGate_reportsNothing() {
        List<BoundaryEval> flat = List.of(
                covered(8280, 280, 4.97, 1, 2, 2, 2, 2, 75, 75),
                covered(8660, 660, 11.72, 1, 2, 2, 3, 3, 75, 79));

        assertThat(InsightScore.topBoundaries(flat, 1950)).isEmpty();
        assertThat(InsightScore.evaluatedCount(flat)).isEqualTo(2);   // 검토는 했다
    }

    // ── #19 동점 처리 ─────────────────────────────────────────────────────

    /**
     * 동점이면 <b>갭이 작은 쪽(덜 빌리는 쪽)</b>이 앞선다 — 불변 원칙 §0-2-4와 정합.
     * 입력 순서를 뒤집어도 결과가 같아야 한다(결정적 정렬).
     */
    @Test
    void ties_preferSmallerGap_andOrderingIsDeterministic() {
        BoundaryEval cheaper = covered(8100, 100, 5.0, 1, 1, 2, 2, 4, 75, 82);
        BoundaryEval pricier = covered(9500, 1500, 5.0, 1, 1, 2, 2, 4, 75, 82);
        assertThat(InsightScore.score(cheaper, 1950))
                .isEqualTo(InsightScore.score(pricier, 1950));       // 완전 동점

        assertThat(InsightScore.topBoundaries(List.of(pricier, cheaper), 1950))
                .containsExactly(cheaper, pricier);
        assertThat(InsightScore.topBoundaries(List.of(cheaper, pricier), 1950))
                .containsExactly(cheaper, pricier);

        // 정렬은 입력 순서에 의존하지 않는다 — 뒤집어 넣어도 같은 순서가 나온다
        List<BoundaryEval> pool = new ArrayList<>(List.of(
                covered(8660, 660, 11.72, 1, 1, 2, 2, 3, 75, 82),
                cheaper, pricier,
                covered(9420, 1420, 25.21, 1, 1, 3, 2, 5, 75, 82)));
        List<BoundaryEval> ascending = pool.stream().sorted(InsightScore.ranking(1950)).toList();
        List<BoundaryEval> reversedInput = pool.reversed().stream()
                .sorted(InsightScore.ranking(1950)).toList();
        assertThat(reversedInput).isEqualTo(ascending);
    }
}

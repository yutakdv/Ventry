package com.ventry.api.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * BE-05 SustainFilter — 확장 부담률 필터 2′ · 지속 프론티어 (exploration spec §2-3).
 *
 * <p>핵심 계약 2가지를 가장 먼저 못 박는다:
 * ① <b>m=0이면 필터 2′는 필터 2와 완전히 동일</b> (좌변·임계 둘 다) — 테스트 1·2
 * ② 차입이 있을 때만 확장 임계 θ′를 적용한다 (assumptions #26)
 */
class SustainFilterTest {

    private static final double THETA = ReverseCheck.DEFAULT_THETA;              // 0.15
    private static final double THETA_PRIME = SustainFilter.DEFAULT_THETA_PRIME; // 0.20

    /** 부담률이 서로 다른 후보 5종 — 비용은 전부 진입 가능하게 두어 필터만 검증한다. */
    private static final List<SustainInput> BURDEN_SAMPLES = List.of(
            new SustainInput(198, 1800, 7000),   // 0.110 — θ 이내
            new SustainInput(300, 2000, 7000),   // 0.150 — θ 경계 정확 일치
            new SustainInput(320, 2000, 7000),   // 0.160 — θ 초과, θ′ 이내
            new SustainInput(456, 2400, 7000),   // 0.190 — θ 초과, θ′ 이내
            new SustainInput(400, 2000, 7000));  // 0.200 — θ′ 경계 정확 일치

    // ── ① m=0 일관성 (스펙 §2-3 "일관성 확인 테스트 케이스") ──────────────

    /**
     * 테스트 1 — 자기자본만으로 예산을 구성한 구간(m=0)에서는 필터 2′가 필터 2와
     * <b>모든 부담률 구간에서 동일한 판정</b>을 내려야 한다. θ 근방·θ′ 근방을 모두 포함한다.
     */
    @Test
    void withoutBorrowing_extendedFilterIsIdenticalToBaseFilter() {
        for (SustainInput candidate : BURDEN_SAMPLES) {
            boolean baseFilter = ReverseCheck.burdenRatio(
                    candidate.monthlyRent(), candidate.estSales()) <= THETA;
            assertThat(SustainFilter.passes(candidate, 0.0))
                    .as("부담률 %.3f — m=0에서 필터2와 필터2′는 같아야 한다",
                            ReverseCheck.burdenRatio(candidate.monthlyRent(), candidate.estSales()))
                    .isEqualTo(baseFilter);
        }
    }

    /**
     * 테스트 2 — m=0이면 임계도 θ(0.15)를 써야 한다. 부담률 0.19는 θ′(0.20)를 썼다면
     * 통과했을 값이므로, 여기서 탈락해야 "θ′를 무조건 쓰지는 않는다"가 증명된다.
     */
    @Test
    void withoutBorrowing_appliesThetaNotThetaPrime() {
        SustainInput burden19 = new SustainInput(456, 2400, 7000);   // 0.19
        assertThat(SustainFilter.passes(burden19, 0.0)).isFalse();   // θ=0.15 적용 → 탈락
        assertThat(SustainFilter.passes(burden19, 1.0)).isTrue();    // 차입 있으면 θ′=0.20 → 통과
    }

    // ── ② 임계 경계값 ────────────────────────────────────────────────────

    /** 테스트 3 — 필터는 `≤` 비교다. 부담률이 θ에 정확히 일치하면 통과한다. */
    @Test
    void burdenExactlyAtTheta_passesWithoutBorrowing() {
        assertThat(SustainFilter.passes(new SustainInput(300, 2000, 7000), 0.0)).isTrue();
        assertThat(SustainFilter.passes(new SustainInput(301, 2000, 7000), 0.0)).isFalse();
    }

    /** 테스트 4 — 차입이 있으면 확장 임계 θ′=0.20을 적용한다. */
    @Test
    void withBorrowing_appliesExtendedThreshold() {
        SustainInput burden19 = new SustainInput(456, 2400, 7000);
        assertThat(SustainFilter.extendedBurdenRatio(burden19, 10.0))
                .isEqualTo((456 + 10.0) / 2400);
        assertThat(SustainFilter.passes(burden19, 10.0)).isTrue();   // 0.1942 ≤ 0.20
    }

    /**
     * 테스트 5 — θ′ 경계 정확 일치. 여유 = θ′×매출 − 임대료 이며,
     * m 이 여유와 같으면 통과하고 조금이라도 넘으면 탈락한다.
     */
    @Test
    void extendedBurdenExactlyAtThetaPrime_passesButNotAbove() {
        SustainInput candidate = new SustainInput(400, 2500, 7000);
        double headroom = THETA_PRIME * 2500 - 400;                  // = 100
        assertThat(SustainFilter.passes(candidate, headroom)).isTrue();
        assertThat(SustainFilter.passes(candidate, headroom + 0.01)).isFalse();
    }

    // ── ③ 지속 프론티어 N_sustain ────────────────────────────────────────

    /** 테스트 6 — N_sustain은 "진입 통과 AND 필터 2′ 통과"다. 부담률이 좋아도 비싸면 제외. */
    @Test
    void nSustain_requiresBothEntryAndExtendedBurden() {
        List<SustainInput> pool = List.of(
                new SustainInput(198, 1800, 7500),    // 진입 O · 부담률 0.110 O
                new SustainInput(456, 2400, 7750),    // 진입 O · 부담률 0.190 → θ′ 이내 O
                new SustainInput(100, 3000, 9999));   // 부담률 0.033이지만 비용 초과 → X
        assertThat(SustainFilter.nSustain(pool, 8000, 5.0)).isEqualTo(2);
        assertThat(Frontier.nEntry(new int[] {7500, 7750, 9999}, 8000)).isEqualTo(2);
    }

    /**
     * 테스트 7 — <b>비단조성</b>: 차입이 커지면 m이 모든 후보의 고정비를 올려
     * 진입 후보가 그대로여도 지속 후보는 줄어든다 (스펙 §2-3 · 심사 예상질문 1번).
     * 홍대 픽스처(456/2,400)의 θ′ 여유는 0.20×2,400−456 = 24 만원이다.
     */
    @Test
    void nSustain_isNotMonotone_asBorrowingGrows() {
        List<SustainInput> pool = List.of(
                new SustainInput(198, 1800, 7750),    // 여유 162
                new SustainInput(273, 2100, 7950),    // 여유 147
                new SustainInput(456, 2400, 7500));   // 여유  24
        assertThat(SustainFilter.nSustain(pool, 9500, 23.0)).isEqualTo(3);   // m<24 → 전원 유지
        assertThat(SustainFilter.nSustain(pool, 9500, 26.0)).isEqualTo(2);   // m>24 → 1곳 탈락
    }

    /** 테스트 8 — 매출 0(분모 결측)은 예외가 아니라 탈락으로 처리한다. */
    @Test
    void zeroSales_failsInsteadOfThrowing() {
        assertThat(SustainFilter.passes(new SustainInput(198, 0, 7000), 0.0)).isFalse();
        assertThat(SustainFilter.nSustain(List.of(new SustainInput(198, 0, 7000)), 8000, 0.0))
                .isZero();
    }

    /** 테스트 9 — 후보가 없으면 0. (0건 보고도 유효한 결과, 스펙 §0-2) */
    @Test
    void emptyPool_isZero() {
        assertThat(SustainFilter.nSustain(List.of(), 8000, 10.0)).isZero();
    }
}

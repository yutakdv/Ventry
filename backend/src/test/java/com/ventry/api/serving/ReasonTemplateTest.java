package com.ventry.api.serving;

import static org.assertj.core.api.Assertions.assertThat;

import com.ventry.api.common.Verdict;
import com.ventry.api.engine.ReverseCheck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 근거문 템플릿 — <b>서버가 만드는 최종 문자열</b>이라 프론트가 되돌릴 수 없다.
 *
 * <p>따라서 여기서 보는 것은 문장의 미문이 아니라 「어떤 입력이 와도 화면에 낼 수 있는
 * 문장인가」다. 특히 추정매출 결측이 만드는 비유한 부담률이 그대로 흘러 「부담률 922경%」가
 * 찍히던 통로를 막았는지 확인한다 (직렬화 쪽은 계약 D9·이슈 #104 ⑤ 로 이미 막혀 있었다).
 */
class ReasonTemplateTest {

    @Test
    @DisplayName("유한 부담률은 백분율로 실린다")
    void finiteRatioIsRendered() {
        assertThat(ReasonTemplate.reason("망원역 상권", Verdict.FIT, 0.15))
                .isEqualTo("망원역 상권 — 길단위 유동·배후 인구가 서울 상위 구간이며 "
                        + "환산임대료 부담률 15%로 임계 이내입니다.");
        assertThat(ReasonTemplate.reason("홍대입구역 상권", Verdict.CAUTION, 0.234))
                .contains("부담률 23%로")
                .contains("임계를 초과합니다");
    }

    /**
     * 추정매출이 0·결측이면 {@link ReverseCheck#burdenRatio} 가 무한대를 센티널로 돌려주고,
     * 그 값은 θ 를 넘으므로 <b>유의 판정과 함께</b> 이 템플릿까지 온다. 가드가 없던 구현은
     * {@code Math.round(Infinity)} = {@code Long.MAX_VALUE} 를 그대로 찍었다.
     */
    @Test
    @DisplayName("추정매출 0이 만드는 무한대 부담률은 수치 없이 서술만 남는다")
    void infiniteRatioDropsTheNumber() {
        double ratio = ReverseCheck.burdenRatio(300, 0);
        assertThat(ratio).isInfinite();

        String text = ReasonTemplate.reason("가상 상권", Verdict.CAUTION, ratio);

        assertThat(text)
                .isEqualTo("가상 상권 — 수요 지표는 상위 구간이나 환산임대료 부담률이 임계를 초과합니다.")
                .doesNotContain(String.valueOf(Long.MAX_VALUE))
                .doesNotContain("Infinity")
                .doesNotContain("%");
    }

    @Test
    @DisplayName("NaN 부담률도 같은 경로로 흘린다")
    void nanRatioDropsTheNumber() {
        assertThat(ReasonTemplate.reason("가상 상권", Verdict.FIT, Double.NaN))
                .contains("부담률이 임계 이내입니다")
                .doesNotContain("NaN");
    }

    /** 부담률을 쓰지 않는 두 판정은 입력값과 무관하게 같은 문장이어야 한다. */
    @Test
    @DisplayName("조건부·범위 외는 부담률을 인용하지 않는다")
    void ratioFreeVerdictsAreStable() {
        for (double ratio : new double[] {0.1, Double.POSITIVE_INFINITY, Double.NaN}) {
            assertThat(ReasonTemplate.reason("가상 상권", Verdict.CONDITIONAL, ratio))
                    .isEqualTo("가상 상권 — 권리금 포함 시 예산을 초과하나, 무권리 매물 확보 시 진입 가능한 구간입니다.");
            assertThat(ReasonTemplate.reason("가상 상권", Verdict.OUT_OF_SCOPE, ratio))
                    .isEqualTo("가상 상권 — 현재 예산 기준으로는 진입 범위 밖입니다.");
        }
    }
}

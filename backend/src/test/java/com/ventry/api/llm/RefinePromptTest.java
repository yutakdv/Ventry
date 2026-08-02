package com.ventry.api.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * BE-06 ③ — 인사이트 언어화 응답 검증기 (스펙 §0-1 역할 ②).
 *
 * <p>이 테스트가 지키는 것은 「LLM 이 문장을 잘 쓰는가」가 아니라 <b>「수치를 건드리면 반드시
 * 폐기되는가」</b>다. 언어화는 서빙 경로에서 LLM 출력이 화면 문장이 되는 유일한 자리라,
 * 가드가 뚫리면 §0-1(모든 숫자는 결정적 계산이 만든다)이 그 자리에서 무너진다.
 */
class RefinePromptTest {

    /** T1 헤드라인 본문 — 자격 한정 꼬리는 호출부가 떼어 낸 상태로 들어온다. */
    private static final String T1_BODY =
            "3,869만 원을 추가 확보하면 진입 가능 후보는 382곳에서 1,014곳으로 늘어납니다. "
                    + "다만 해당 금액을 민간투자연계형 매칭융자(분기별 변동금리, 96개월 상환)으로 "
                    + "조달할 경우 상환 부담을 반영하면 지속 안정 후보는 244곳입니다.";

    private static Optional<String> refine(String response) {
        return RefinePrompt.sanitize(Optional.of(response), T1_BODY);
    }

    @Nested
    @DisplayName("수치 보존 — 양방향")
    class NumberFidelity {

        @Test
        @DisplayName("수치를 그대로 둔 문장은 통과한다")
        void keepsAllNumbers() {
            String refined = "3,869만 원을 더 마련하면 들어갈 수 있는 상권이 382곳에서 1,014곳으로 "
                    + "늘어납니다. 다만 그 돈을 민간투자연계형 매칭융자(분기별 변동금리, 96개월 상환)로 "
                    + "빌리면 상환 부담까지 감안했을 때 오래 버틸 수 있는 곳은 244곳입니다.";
            assertThat(refine(refined)).contains(refined);
        }

        /** 없던 숫자를 만드는 것 — 반박문 검증기와 같은 방향의 사고다. */
        @Test
        @DisplayName("입력에 없는 숫자를 덧붙이면 폐기한다")
        void rejectsInventedNumber() {
            assertThat(refine("3,869만 원을 더 마련하면 진입 가능 후보가 382곳에서 1,014곳으로 "
                    + "늘고, 지속 안정 후보는 244곳이며 성공률은 약 70%입니다.")).isEmpty();
        }

        /**
         * <b>언어화 고유의 사고</b> — 지속 안정 후보 수가 빠지면 「차입하면 후보가 늘어난다」만
         * 남아 상향 인사이트가 단독으로 나간다 (PROJECT_RULES §2 위반).
         * 반박문 검증기의 부분집합 검사로는 이걸 잡지 못한다.
         */
        @Test
        @DisplayName("지속 안정 후보 수를 빼먹으면 폐기한다 — 상향 단독 노출 차단")
        void rejectsDroppedSustainCount() {
            assertThat(refine("3,869만 원을 더 마련하면 진입 가능 후보가 382곳에서 1,014곳으로 "
                    + "늘어납니다. 민간투자연계형 매칭융자(분기별 변동금리, 96개월 상환)를 쓰면 됩니다."))
                    .isEmpty();
        }

        @Test
        @DisplayName("단위를 바꾸면 폐기한다 (96개월 → 8년)")
        void rejectsUnitConversion() {
            assertThat(refine("3,869만 원을 더 마련하면 진입 가능 후보가 382곳에서 1,014곳으로 "
                    + "늘고, 8년 상환 조건의 민간투자연계형 매칭융자를 쓰면 지속 안정 후보는 244곳입니다."))
                    .isEmpty();
        }

        @Test
        @DisplayName("근사하면 폐기한다 (1,014곳 → 약 1,000곳)")
        void rejectsRounding() {
            assertThat(refine("3,869만 원을 더 마련하면 진입 가능 후보가 382곳에서 약 1,000곳으로 "
                    + "늘고, 96개월 상환 조건에서 지속 안정 후보는 244곳입니다.")).isEmpty();
        }

        /** 천단위 쉼표 유무는 같은 값이다 — 값 비교라 표기 차이로 정상 문장을 버리지 않는다. */
        @Test
        @DisplayName("쉼표 표기가 달라도 같은 값이면 통과한다")
        void acceptsThousandSeparatorVariation() {
            String refined = "3869만 원을 더 확보하면 진입 가능 후보가 382곳에서 1014곳으로 늘고, "
                    + "민간투자연계형 매칭융자(분기별 변동금리, 96개월 상환) 기준 지속 안정 후보는 244곳입니다.";
            assertThat(refine(refined)).contains(refined);
        }

        /** 수치가 없는 문장(T2 일부)도 집합이 양쪽 다 비면 통과해야 한다. */
        @Test
        @DisplayName("수치가 없는 본문은 수치 없는 문장으로 통과한다")
        void acceptsNumberlessBody() {
            String body = "현재 예산은 안정 구간이며 추가 조달 없이도 후보 구성이 유지됩니다.";
            assertThat(RefinePrompt.sanitize(
                    Optional.of("지금 예산이면 더 빌리지 않아도 후보 구성이 그대로 유지됩니다."), body))
                    .isPresent();
        }
    }

    @Nested
    @DisplayName("용어 컴플라이언스 · 형태")
    class Compliance {

        @Test
        @DisplayName("금지 표현이 섞이면 폐기한다")
        void rejectsBannedWords() {
            for (String banned : new String[] {"승인", "권장", "보장", "심사역"}) {
                String text = "3,869만 원을 더 마련하면 진입 가능 후보가 382곳에서 1,014곳으로 늘고, "
                        + "96개월 상환 조건에서 지속 안정 후보는 244곳입니다. " + banned + "됩니다.";
                assertThat(refine(text)).as("금지어 '%s'", banned).isEmpty();
            }
        }

        /**
         * 활용형까지 걸려야 한다.
         *
         * <p>구 목록은 「추천드립」·「추천합니」만 막아서, 한국어에서 훨씬 자연스러운
         * <b>「추천해 드립니다」·「권유합니다」·「권해 드립니다」가 그대로 통과</b>했다.
         * 이 문장들이 화면에 실리면 자금 관련 자문성 술어 금지(PROJECT_RULES §2)를 정면으로
         * 어기는 것이고, 심사 감점에 직결된다.
         */
        @Test
        @DisplayName("자문성 술어의 활용형도 폐기한다 — 추천해/권유/권해")
        void rejectsInflectedAdvisoryForms() {
            for (String banned :
                    new String[] {"추천해 드립니다", "추천드립니다", "추천합니다", "권유합니다", "권해 드립니다"}) {
                String text = "3,869만 원을 더 마련하면 진입 가능 후보가 382곳에서 1,014곳으로 늘고, "
                        + "96개월 상환 조건에서 지속 안정 후보는 244곳입니다. 이 구성을 " + banned;
                assertThat(refine(text)).as("금지 활용형 '%s'", banned).isEmpty();
            }
        }

        @Test
        @DisplayName("너무 짧거나 긴 응답은 폐기한다")
        void rejectsOutOfRangeLength() {
            assertThat(refine("네.")).isEmpty();
            assertThat(refine("가".repeat(500))).isEmpty();
        }

        /** 머리말·불릿·따옴표는 벗기고 첫 문단만 취한다 — 내용이 멀쩡한 응답을 버리지 않는다. */
        @Test
        @DisplayName("장식을 벗겨 첫 문단만 취한다")
        void stripsDecoration() {
            Optional<String> result = refine("""
                    - "3,869만 원을 더 마련하면 진입 가능 후보가 382곳에서 1,014곳으로 늘고, \
                    96개월 상환 조건에서 지속 안정 후보는 244곳입니다."

                    추가 설명: 이 문장은 두 번째 문단입니다.""");
            assertThat(result).isPresent();
            assertThat(result.get()).doesNotStartWith("-").doesNotStartWith("\"")
                    .doesNotContain("두 번째 문단");
        }

        @Test
        @DisplayName("응답이 없으면(무LLM·타임아웃) 그대로 empty")
        void passesThroughEmpty() {
            assertThat(RefinePrompt.sanitize(Optional.empty(), T1_BODY)).isEmpty();
        }
    }

    /**
     * 수치 <b>집합</b>이 같아도 증감 방향이 뒤집히면 문장의 뜻이 반대가 된다 (AI 리뷰 M-04).
     * 두 수가 같은 라벨(진입 가능 후보)을 공유해 라벨 결속 검사로도 구별되지 않는 자리다.
     */
    @Nested
    @DisplayName("증감 방향 — 대소 관계 교차 검증")
    class Direction {

        @Test
        @DisplayName("「A에서 B로 늘어납니다」의 A와 B가 뒤바뀌면 폐기한다")
        void rejectsSwappedTransition() {
            assertThat(refine("3,869만 원을 더 마련하면 진입 가능 후보가 1,014곳에서 382곳으로 "
                    + "늘어납니다. 96개월 상환 조건에서 지속 안정 후보는 244곳입니다.")).isEmpty();
        }

        @Test
        @DisplayName("방향이 맞으면 통과한다 — 어순 자유도는 그대로 둔다")
        void acceptsCorrectTransition() {
            String refined = "3,869만 원을 더 마련하면 진입 가능 후보가 382곳에서 1,014곳으로 "
                    + "늘어납니다. 96개월 상환 조건에서 지속 안정 후보는 244곳입니다.";
            assertThat(refine(refined)).contains(refined);
        }

        /** 「A에서 B로」 꼴이 없으면 판단 근거가 없다 — 정상 문장을 걸러내지 않는다. */
        @Test
        @DisplayName("전이 표현이 없는 문장은 이 검사가 관여하지 않는다")
        void ignoresSentenceWithoutTransition() {
            String refined = "3,869만 원을 더 마련하면 진입 가능 후보는 1,014곳이 됩니다"
                    + "(현재 382곳). 96개월 상환 조건에서 지속 안정 후보는 244곳입니다.";
            assertThat(refine(refined)).contains(refined);
        }
    }

    @Test
    @DisplayName("프롬프트에 템플릿 본문과 수치 보존 지시가 실린다")
    void promptCarriesBodyAndConstraint() {
        String prompt = RefinePrompt.build(T1_BODY);
        assertThat(prompt).contains(T1_BODY)
                .contains("수치를 하나도 바꾸지 마세요")
                .contains("빼먹지 마세요");
    }
}

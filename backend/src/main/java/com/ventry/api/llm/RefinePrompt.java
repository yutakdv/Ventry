package com.ventry.api.llm;

import java.math.BigDecimal;
import java.util.List;
import java.util.NavigableSet;
import java.util.Optional;

/**
 * BE-06 ③ — 인사이트 <b>언어화</b> 프롬프트 조립 + 응답 검증기 (스펙 §0-1 역할 ②, expl §2-5).
 *
 * <p>LLM 3역할 중 ②(구조화된 결과의 언어화)가 실제로 도는 자리다. 템플릿이 기계적으로 조립한
 * 헤드라인을 읽기 좋은 문장으로 <b>바꿔 쓰기만</b> 하며, 계산은 물론 <b>수치 자체를 건드리지
 * 않는다</b>. SSE 는 템플릿 문장을 먼저 보내고 이 결과가 오면 {@code refine} 이벤트로 교체하므로,
 * LLM 이 느리거나 죽어도 첫 바이트와 최종본이 모두 보장된다 (데모 무중단, expl §2-5).
 *
 * <p><b>이 클래스의 본체는 {@link #sanitize} 다.</b> {@link ReviewPrompt} 와 같은 이유로
 * 프롬프트의 부탁은 제약이 아니다. 다만 검사 하나가 더 있다.
 *
 * <ul>
 *   <li><b>수치 집합이 양방향으로 같아야 한다.</b> 반박문은 「입력 밖 수치 금지」 한 방향만 보면
 *       됐지만(부분집합), 언어화는 <b>누락도 막아야 한다</b>. 헤드라인에서 지속 안정 후보 수가
 *       빠지면 그 문장은 「차입하면 후보가 늘어난다」만 남아 <b>상향 인사이트 단독 노출</b>이
 *       되는데, 그건 CLAUDE.md 절대 불변 원칙 3이 금지한 형태다. 새 수치 금지(⊆)와 필수 수치
 *       보존(⊇)을 함께 걸면 「수치는 그대로 두고 문장만 다시 쓴다」가 기계적으로 강제된다.</li>
 *   <li><b>용어 컴플라이언스</b>: "승인" 계열·자금 권유형 술어가 섞이면 버린다.</li>
 *   <li><b>형태</b>: 한 문단·길이 범위.</li>
 * </ul>
 *
 * <p><b>고지 문구는 LLM 에 주지 않는다.</b> 호출부가 자격 한정 꼬리를 떼어 본문만 넘기고,
 * 통과한 문장 뒤에 <b>서버가 다시 붙인다</b> — 필수 고지를 모델이 지울 수 있는 자리에 두지
 * 않는다 ({@code axis_labels}·{@code rate_note} 를 서버가 단일 통제하는 것과 같은 이유).
 */
public final class RefinePrompt {

    /** 카드 한 장에 들어가는 분량. 템플릿 본문이 대개 90~160자라 그 언저리를 허용한다. */
    private static final int MIN_LENGTH = 20;
    private static final int MAX_LENGTH = 400;

    /**
     * 용어 컴플라이언스 (CLAUDE.md 절대 불변 원칙 3).
     *
     * <p>{@link ReviewPrompt} 의 목록과 달리 판정 번복 어휘("재검토" 등)는 넣지 않는다 —
     * 언어화는 판정을 다투는 자리가 아니라 이미 확정된 결과를 옮겨 적는 자리라 그 어휘가
     * 나올 맥락 자체가 없고, 넣으면 정상 문장을 걸러낼 위험만 늘어난다.
     */
    private static final List<String> BANNED =
            List.of("승인", "권장", "추천드립", "추천합니", "조달 가능", "심사역", "보장");

    private RefinePrompt() {}

    /**
     * 언어화 요청 프롬프트. <b>수치를 바꾸지 말라</b>가 유일한 실질 제약이다.
     *
     * <p>「자연스럽게 써라」로 끝내지 않고 무엇을 하지 말아야 하는지를 적는다 — 모델이 흔히
     * 하는 일이 단위를 바꾸거나(96개월 → 8년) 근사하는 것(1,014곳 → 약 1,000곳)인데,
     * 둘 다 {@link #sanitize} 가 폐기하므로 프롬프트에서 미리 막는 편이 폐기율이 낮다.
     */
    public static String build(String templateBody) {
        return """
                당신은 창업 자금 컨설팅 화면의 문장을 다듬는 편집자입니다.
                아래 문장은 결정적 계산의 결과를 기계적으로 조립한 것입니다.

                %s

                같은 내용을 더 읽기 쉬운 한국어로 다시 쓰세요.

                규칙:
                - **수치를 하나도 바꾸지 마세요.** 숫자·단위·자릿수를 그대로 두세요.
                  (예: "96개월"을 "8년"으로, "1,014곳"을 "약 1,000곳"으로 바꾸면 안 됩니다)
                - 위 문장에 없는 숫자를 새로 만들지 마세요.
                - 위 문장에 있는 숫자를 빼먹지 마세요. 특히 후보 수는 전부 남겨야 합니다.
                - 한 문단, 200자 이내의 한국어 평서문으로 쓰세요.
                - "승인", "권장", "추천", "보장" 이라는 표현을 쓰지 마세요.
                - 자금 조달을 권유하지 말고, 사실만 서술하세요.
                - 머리말·목록·따옴표 없이 문장만 출력하세요.
                """.formatted(templateBody);
    }

    /**
     * 응답 → 화면에 실을 헤드라인 본문. <b>규칙을 어긴 응답은 통과시키지 않는다</b>.
     *
     * @param response     LLM 응답. empty 면 그대로 empty (무LLM·타임아웃)
     * @param templateBody {@link #build} 에 넣은 템플릿 본문 — 수치 집합의 기준
     * @return 검증을 통과한 한 문단, 또는 empty(→ 호출부가 refine 을 송출하지 않는다)
     */
    public static Optional<String> sanitize(Optional<String> response, String templateBody) {
        if (response.isEmpty()) {
            return Optional.empty();
        }
        String text = LlmResponses.firstParagraph(response.get());
        if (text.length() < MIN_LENGTH || text.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        for (String word : BANNED) {
            if (text.contains(word)) {
                return Optional.empty();
            }
        }
        return keepsEveryNumber(text, templateBody) ? Optional.of(text) : Optional.empty();
    }

    /**
     * 템플릿과 언어화본의 <b>수치 값 집합이 같아야</b> true.
     *
     * <p>한쪽 방향만 보면 각각 다른 사고가 통과한다:
     *
     * <ul>
     *   <li>⊆ 만 보면 — 지어낸 수치는 막지만 <b>지속 안정 후보 수를 빼먹은</b> 문장이 통과해
     *       상향 인사이트가 단독으로 나간다.</li>
     *   <li>⊇ 만 보면 — 필수 수치는 남지만 <b>없던 수치를 덧붙인</b> 문장이 통과해 §0-1 이
     *       무너진다.</li>
     * </ul>
     *
     * <p>같은 수가 여러 번 나오는 것은 개수를 세지 않는다 — 「382곳에서 1,014곳」을
     * 「1,014곳으로, 382곳에서」처럼 순서만 바꾸는 것은 허용해야 언어화가 의미를 갖는다.
     */
    private static boolean keepsEveryNumber(String text, String templateBody) {
        NavigableSet<BigDecimal> template = LlmResponses.numberValues(templateBody);
        NavigableSet<BigDecimal> refined = LlmResponses.numberValues(text);
        return template.equals(refined);
    }
}

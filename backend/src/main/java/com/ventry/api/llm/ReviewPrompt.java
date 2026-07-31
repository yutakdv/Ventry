package com.ventry.api.llm;

import java.math.BigDecimal;
import java.util.List;
import java.util.NavigableSet;
import java.util.Optional;

/**
 * BE-05 — 리스크 검증 반박문 프롬프트 조립 + <b>응답 검증기</b> (스펙 §5-3, 이슈 #96).
 *
 * <p>구도는 「추천 에이전트(<b>긍정 논리</b>) ↔ 리스크 검증 에이전트(반대 논리)」이고 <b>1왕복 고정</b>이다.
 * 입력은 이미 결정적으로 계산된 추천 결과이며, LLM 은 그 결과를 <b>반박하는 문장 하나</b>만 만든다.
 *
 * <p><b>이 클래스의 본체는 프롬프트가 아니라 {@link #sanitize} 다.</b> 프롬프트로 부탁하는 것은
 * 제약이 아니다 — 지키지 않은 응답을 <b>버릴 수 있어야</b> 제약이다. 세 가지를 검증한다:
 *
 * <ul>
 *   <li><b>입력 밖 수치 금지</b> (스펙 §5-3 명문): 출력의 숫자 토큰이 전부 입력 사실에 등장해야
 *       한다. 하나라도 없으면 응답 전체를 버린다 — 검증 에이전트가 지어낸 숫자로 판정을
 *       반박하면 그 순간 §0-1 이 무너진다.</li>
 *   <li><b>용어 컴플라이언스</b>: "승인" 계열·자금 권유형 술어가 섞이면 버린다. 심사 감점 직결이라
 *       LLM 출력이라고 예외를 두지 않는다.</li>
 *   <li><b>형태</b>: 한 문단·길이 범위. 목록·코드블록·장황한 서론을 걸러 패널에 그대로 실을 수
 *       있는 문장만 통과시킨다.</li>
 * </ul>
 *
 * <p>모든 거부는 {@code Optional.empty()} 로 수렴하고, 호출부는 템플릿 문장을 최종본으로 쓰면서
 * {@code skipped=true} 를 세운다 (스펙 §5-3 "장애 시: 반박 생략 + 추천 판정 유지 + 검증 생략 플래그").
 */
public final class ReviewPrompt {

    /** 패널 한 칸에 들어가는 분량. 너무 짧으면 반박이 아니고, 길면 화면을 넘긴다. */
    private static final int MIN_LENGTH = 20;
    private static final int MAX_LENGTH = 400;

    /**
     * 용어 컴플라이언스 금지어(→ {@link LlmResponses#BANNED_TERMS}) <b>위에 얹는</b> 판정 번복 어휘.
     *
     * <p>이 3개는 반박이 <b>판정 자체를 다투는</b> 문장을 걸러낸다 — 스펙 §5-3 은 반박이 판정을
     * 바꾸지 않는다고 규정하는데, 실측에서 「판정이 …로 분류된 이유가 충분히 검토되지 않았을
     * 가능성이 있다… 재검토할 필요가 있다」가 나왔다. 화면에서는 시스템이 자기 판정을 부정하는
     * 것으로 읽힌다 (BE 리뷰 D-11).
     */
    private static final List<String> EXTRA_BANNED = List.of("재검토", "분류된 이유", "타당하지");

    private ReviewPrompt() {}

    /**
     * 반박 요청 프롬프트. 입력 사실을 그대로 주고 <b>그 안의 수치만</b> 쓰도록 제약한다.
     *
     * <p>수치를 아예 금지하지 않는 이유: 근거 없는 일반론("경쟁이 심할 수 있습니다")은 반박으로서
     * 가치가 없다. 계산된 값을 인용해 반박해야 「데이터 기반 반박문」(§5-3)이 된다. 대신 그 값이
     * 입력에 있는 것인지를 {@link #sanitize} 가 기계적으로 검증한다.
     */
    public static String build(String facts) {
        return """
                당신은 입지 추천 결과를 검토하는 리스크 검증자입니다.
                아래는 결정적으로 계산된 추천 결과입니다.

                %s

                이 결과가 낙관적으로 해석될 여지를 한 가지 지적하는 반박문을 쓰세요.

                규칙:
                - 위 입력에 있는 수치만 쓰세요. 입력에 없는 숫자를 새로 만들면 안 됩니다.
                - **수치를 다른 지표에 옮겨 붙이지 마세요.** 입력에서 추정매출이던 값을
                  환산임대료로, 종합점수이던 값을 부담률로 쓰면 안 됩니다. 숫자를 인용할 때는
                  입력에 적힌 지표 이름을 그대로 함께 쓰세요.
                - 입력의 수치를 최소 하나는 인용하세요. 숫자 없는 일반론은 반박이 아닙니다.
                - 한 문단, 두 문장 이내, 200자 이내의 한국어 평서문으로 쓰세요.
                - "승인", "권장", "보장", "추천"(추천드립니다·추천합니다·추천해 드립니다),
                  "권유합니다", "권해 드립니다" 같은 표현을 쓰지 마세요.
                - 대출·자금을 부추기지 말고, 판정이 놓칠 수 있는 지점만 서술하세요.
                - **판정 자체의 타당성을 다투지 마세요.** 판정은 결정적 계산의 결과이며 재검토
                  대상이 아닙니다. 그 판정이 낙관적으로 해석될 여지만 지적하세요.
                - 머리말·목록·따옴표 없이 문장만 출력하세요.
                """.formatted(facts);
    }

    /**
     * 응답 → 패널에 실을 반박문. <b>규칙을 어긴 응답은 통과시키지 않는다</b>.
     *
     * @param response LLM 응답. empty 면 그대로 empty (무LLM·타임아웃)
     * @param facts    {@link #build} 에 넣은 입력 사실 — 수치 허용 목록의 원천
     * @return 검증을 통과한 한 문단, 또는 empty(→ 호출부가 템플릿 폴백 + skipped=true)
     */
    public static Optional<String> sanitize(Optional<String> response, String facts) {
        if (response.isEmpty()) {
            return Optional.empty();
        }
        String text = LlmResponses.firstParagraph(response.get());
        if (text.length() < MIN_LENGTH || text.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        if (LlmResponses.looksLikeHeader(text)) {
            return Optional.empty();   // 「…아래에 작성하였습니다:」 — 반박이 아니라 안내문이다
        }
        if (LlmResponses.violatesTerminology(text, EXTRA_BANNED)) {
            return Optional.empty();
        }
        return isGrounded(text, facts) ? Optional.of(text) : Optional.empty();
    }

    /**
     * 반박문이 입력 사실에 <b>묶여 있는가</b> — 두 겹으로 본다.
     *
     * <ol>
     *   <li><b>값</b>: 출력의 숫자가 전부 사실에 있던 값인가 ({@link #usesOnlyGivenNumbers}).</li>
     *   <li><b>귀속</b>: 그 값이 사실에서와 <b>같은 지표</b>에 붙어 있는가
     *       ({@link LlmResponses#labelsAgree}). 값 대조만으로는 사실의 추정매출 1800만원을
     *       「환산임대료 1800만원」이라 옮겨 적은 문장이 통과한다.</li>
     * </ol>
     *
     * <p><b>수치 인용을 의무화하지는 않는다.</b> 프롬프트는 수치 인용을 요구하지만, 검증기가
     * 그것을 강제하면 「추정매출은 분기 평균이라 계절 변동이 큰 업종에서는 실제와 다를 수
     * 있습니다」처럼 <b>수치 없이도 유효한 질적 반박</b>이 통째로 폐기된다. 그 형태를 정상으로
     * 보는 것은 이 저장소의 기존 판단이기도 하다 (ReviewPromptTest·RiskReviewAgentTest 가
     * 수치 없는 반박을 정상 케이스로 잠가 두었다). 강제는 프롬프트에 두고 검증기는 거짓만
     * 막는다.
     */
    private static boolean isGrounded(String text, String facts) {
        return usesOnlyGivenNumbers(text, LlmResponses.numberValues(facts))
                && LlmResponses.labelsAgree(text, LlmResponses.labeledValues(facts));
    }

    /**
     * 입력 사실에 없는 숫자가 하나라도 있으면 false — 지어낸 수치로 반박하는 것을 막는다.
     *
     * <p><b>문자열이 아니라 값으로 대조한다.</b> 구 구현은 {@code facts.contains(토큰)} 이라
     * 부분 문자열이 통과했다: 사실이 「추정매출 2400만원」이면 출력의 <b>240</b>·<b>40</b> 이,
     * 「환산임대료 155만원」이면 <b>55</b> 가 전부 통과했다. 지어낸 수치를 막는 것이 이 함수의
     * 유일한 존재 이유인데 그 구멍이 바로 그 자리에 있었다.
     *
     * <p>반대 방향도 틀렸다. 모델이 한국어 관행대로 「2,400만원」이라 쓰거나 「0.110」을
     * 「0.11」로 줄이면 <b>내용이 정확한 반박문이 통째로 폐기</b>돼 화면에 「검증 생략」이 떴다.
     * 값 비교는 그 둘을 같은 수로 본다.
     */
    private static boolean usesOnlyGivenNumbers(String text, NavigableSet<BigDecimal> factNumbers) {
        return LlmResponses.allNumbersIn(text, factNumbers);
    }
}

package com.ventry.api.llm;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BE-05 — 리스크 검증 반박문 프롬프트 조립 + <b>응답 검증기</b> (스펙 §5-3, 이슈 #96).
 *
 * <p>구도는 「추천 에이전트(승인 논리) ↔ 리스크 검증 에이전트(반대 논리)」이고 <b>1왕복 고정</b>이다.
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

    /** 용어 컴플라이언스 (CLAUDE.md 절대 불변 원칙 3). LLM 출력에도 그대로 적용한다. */
    private static final List<String> BANNED = List.of(
            "승인", "권장", "추천드립", "추천합니", "조달 가능", "심사역", "보장");

    /** 숫자 토큰 — 소수점·천단위 쉼표를 포함해 최대 길이로 끊는다. */
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");

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
                - 한 문단, 두 문장 이내, 200자 이내의 한국어 평서문으로 쓰세요.
                - "승인", "권장", "추천", "보장" 이라는 표현을 쓰지 마세요.
                - 대출·자금을 권유하지 말고, 판정이 놓칠 수 있는 지점만 서술하세요.
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
        String text = firstParagraph(response.get());
        if (text.length() < MIN_LENGTH || text.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        for (String word : BANNED) {
            if (text.contains(word)) {
                return Optional.empty();
            }
        }
        return usesOnlyGivenNumbers(text, facts) ? Optional.of(text) : Optional.empty();
    }

    /** 입력 사실에 없는 숫자가 하나라도 있으면 false — 지어낸 수치로 반박하는 것을 막는다. */
    private static boolean usesOnlyGivenNumbers(String text, String facts) {
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            if (!facts.contains(matcher.group())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 첫 문단만 취하고 장식을 벗긴다. 모델이 머리말·따옴표·불릿을 붙이는 경우가 흔한데,
     * 그 때문에 내용이 멀쩡한 응답을 통째로 버리는 것은 과하다 — 형태만 정리하고 <b>문장은
     * 손대지 않는다</b>.
     */
    private static String firstParagraph(String raw) {
        String text = raw.strip();
        int blank = text.indexOf("\n\n");
        if (blank > 0) {
            text = text.substring(0, blank);
        }
        text = text.replace("\n", " ").strip();
        text = text.replaceAll("^[-*•\\s]+", "").strip();
        if (text.length() > 1 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1).strip();
        }
        return text;
    }
}

package com.ventry.api.llm;

import java.math.BigDecimal;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 응답 검증기의 공용 부품 — 숫자 토큰 추출과 형태 정리.
 *
 * <p>{@link ReviewPrompt}(반박문)와 {@link RefinePrompt}(언어화)가 같은 검사를 한다:
 * <b>출력의 수치가 입력에 있던 것인가</b>. 두 곳에 같은 코드를 두면 한쪽만 고쳐지고, 그 한쪽이
 * 수치 생성 금지(§0-1)를 지키는 유일한 관문이라 조용히 갈라지면 안 된다.
 */
final class LlmResponses {

    /** 숫자 토큰 — 소수점·천단위 쉼표를 포함해 최대 길이로 끊는다. */
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");

    private LlmResponses() {}

    /**
     * 문자열에 등장하는 수치의 <b>값</b> 집합.
     *
     * <p>{@code TreeSet} 은 {@code compareTo} 로 원소를 보므로 {@code 0.110} 과 {@code 0.11} 이
     * 같은 값으로 잡힌다 ({@code BigDecimal.equals} 는 소수 자릿수까지 보기 때문에
     * {@code HashSet} 을 쓰면 안 된다). 문자열이 아니라 값으로 비교해야
     * 「2,400만원」과 「2400만원」이 같은 수로 취급된다.
     */
    static NavigableSet<BigDecimal> numberValues(String text) {
        NavigableSet<BigDecimal> values = new TreeSet<>();
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            parse(matcher.group()).ifPresent(values::add);
        }
        return values;
    }

    /**
     * {@code text} 의 숫자 토큰이 <b>전부</b> {@code allowed} 안의 값이면 true.
     *
     * <p>값 집합 비교로 대신할 수 없다 — 파싱되지 않는 토큰({@code 2026.07.27} 같은 날짜)은
     * 집합에 들어가지 않으므로, 집합만 보면 그런 토큰이 <b>없는 것처럼</b> 통과한다.
     * 토큰 단위로 훑어야 「사실에 없는 숫자」로 거부된다.
     */
    static boolean allNumbersIn(String text, NavigableSet<BigDecimal> allowed) {
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            Optional<BigDecimal> value = parse(matcher.group());
            if (value.isEmpty() || !allowed.contains(value.get())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 숫자 토큰 → 값. 천단위 쉼표는 지운다.
     *
     * <p>정규식은 {@code 2026.07.27} 처럼 구분자가 여럿인 토큰도 잡는데 이는 수치가 아니다.
     * 파싱 실패를 empty 로 돌려 <b>출력 쪽에서는 거부, 사실 쪽에서는 제외</b>로 흘린다 —
     * 어느 쪽이든 「사실에 없는 숫자」로 취급되므로 가드가 느슨해지지 않는다.
     */
    static Optional<BigDecimal> parse(String token) {
        try {
            return Optional.of(new BigDecimal(token.replace(",", "")));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * 첫 문단만 취하고 장식을 벗긴다. 모델이 머리말·따옴표·불릿을 붙이는 경우가 흔한데,
     * 그 때문에 내용이 멀쩡한 응답을 통째로 버리는 것은 과하다 — 형태만 정리하고 <b>문장은
     * 손대지 않는다</b>.
     */
    static String firstParagraph(String raw) {
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

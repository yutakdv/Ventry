package com.ventry.api.llm;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * 용어 컴플라이언스 금지어 (PROJECT_RULES §2) — <b>두 검증기의 공용 목록</b>.
     *
     * <p>{@link ReviewPrompt}·{@link RefinePrompt} 가 각자 목록을 들고 있었는데, 그 결과
     * 「추천드립」·「추천합니」만 막고 <b>「추천해 드립니다」·「권유합니다」는 통과</b>했다.
     * 심사 감점에 직결되는 목록이 조용히 갈라지지 않도록 여기 하나만 둔다 — 이 클래스가
     * 존재하는 이유(수치 검사 이중화 방지)와 같은 이유다.
     *
     * <p>어간까지만 적는다. 「추천드립니다/추천드려요」는 <b>추천드</b>, 「추천합니다/추천합시다」는
     * <b>추천합</b> 로 활용형을 함께 걸린다. 명사 「추천 점수」·「추천 상권」은 어간 뒤에 조사가
     * 붙지 않으므로 오탐이 아니다.
     *
     * <p>고지 문구의 「대출 권유·중개·자문이 아닙니다」와는 충돌하지 않는다 — 고지는 검증을
     * 통과한 문장 <b>뒤에 서버가 붙이므로</b> LLM 출력에 들어 있을 이유가 없고, 들어 있다면
     * 모델이 고지를 흉내 낸 것이라 폐기하는 편이 옳다.
     */
    static final List<String> BANNED_TERMS = List.of(
            "승인", "권장", "보장", "조달 가능", "심사역",
            "추천드", "추천합", "추천해", "권유", "권해");

    private LlmResponses() {}

    /**
     * 금지어가 하나라도 섞였으면 true — 호출부는 응답 전체를 버린다.
     *
     * @param extra 검증기별 추가 금지어(예: 반박문의 판정 번복 어휘). 없으면 빈 목록.
     */
    static boolean violatesTerminology(String text, List<String> extra) {
        for (String word : BANNED_TERMS) {
            if (text.contains(word)) {
                return true;
            }
        }
        for (String word : extra) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

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
     * 값이 붙어 다니는 지표 라벨 — <b>수치의 귀속</b>을 검사하기 위한 어휘.
     *
     * <p>{@link RiskReviewAgent} 가 사실로 넘기는 지표 이름과 같아야 한다. 여기 없는 낱말은
     * 검사 대상이 아니므로(=제약이 걸리지 않으므로) 어휘를 늘리는 것은 안전하고, 줄이면
     * 그 지표의 귀속만 검사되지 않는다.
     */
    private static final List<String> METRIC_LABELS =
            List.of("종합점수", "부담률", "환산임대료", "추정매출", "부족분");

    /** 라벨과 숫자를 <b>한 번에</b> 훑는다 — 둘의 등장 순서가 곧 귀속 관계이기 때문이다. */
    private static final Pattern LABEL_OR_NUMBER =
            Pattern.compile("(" + String.join("|", METRIC_LABELS) + ")|(\\d+(?:[.,]\\d+)*)");

    /**
     * 라벨이 값을 소유한다고 볼 최대 간격(문자). 「환산임대료 198만원」은 1, 「부족분(만원): 300」은
     * 6이다. 넓히면 「추정매출이 유지된다는 전제에 … CAUTION 후보 120곳」의 120 이 추정매출에
     * 잘못 묶여 <b>정상 반박이 폐기</b>되고, 좁히면 사실 문구의 「(만원): 」이 끊긴다.
     */
    private static final int LABEL_REACH = 8;

    /**
     * 라벨 → 그 라벨에 붙어 등장한 값들.
     *
     * <p><b>값의 임자는 가장 가까운 앞 라벨</b>이다. 「환산임대료 대비 추정매출 부담률 0.110」은
     * 부담률의 값이지 환산임대료의 값이 아니다 — checkArea 사실 문구가 실제로 이 형태라
     * 앞선 라벨까지 값을 요구하면 정상 문장이 걸린다. 값 하나가 라벨을 소진하므로 뒤따르는
     * 수는 임자가 없고(=제약 없음), 간격이 {@link #LABEL_REACH} 를 넘어도 임자가 없다.
     */
    static Map<String, NavigableSet<BigDecimal>> labeledValues(String text) {
        Map<String, NavigableSet<BigDecimal>> bound = new LinkedHashMap<>();
        Matcher matcher = LABEL_OR_NUMBER.matcher(text);
        String label = null;
        int labelEnd = 0;
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                label = matcher.group(1);
                labelEnd = matcher.end();
                continue;
            }
            String owner = label;
            if (owner != null && matcher.start() - labelEnd <= LABEL_REACH) {
                parse(matcher.group(2)).ifPresent(
                        value -> bound.computeIfAbsent(owner, k -> new TreeSet<>()).add(value));
            }
            label = null;   // 숫자를 만나면 라벨은 소진된다 — 그 뒤의 수는 더 멀 뿐이다
        }
        return bound;
    }

    /**
     * 출력이 <b>같은 라벨에 같은 값</b>을 붙였으면 true.
     *
     * <p>{@link #allNumbersIn} 만으로는 부족하다 — 값 집합 대조는 「1800 이 어딘가에 있었다」만
     * 보므로, 사실의 추정매출 1800만원을 <b>환산임대료 1800만원</b>이라 옮겨 적은 문장이
     * 통과한다. 지어낸 숫자는 아니지만 화면에 실리는 문장은 거짓이고, §0-1 이 지키려는 것은
     * 숫자의 출처가 아니라 숫자가 뜻하는 바다.
     *
     * <p>사실이 값을 붙여 준 라벨만 검사한다. 사실에 없던 라벨은 값 집합 대조가 이미 막는다.
     */
    static boolean labelsAgree(String text, Map<String, NavigableSet<BigDecimal>> factLabels) {
        for (Map.Entry<String, NavigableSet<BigDecimal>> entry : labeledValues(text).entrySet()) {
            NavigableSet<BigDecimal> allowed = factLabels.get(entry.getKey());
            if (allowed != null && !allowed.containsAll(entry.getValue())) {
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

    /** 「…아래에 작성하였습니다:」류 안내 문단의 표식. 산문은 콜론으로 끝나지 않는다. */
    private static final Pattern HEADER_TAIL = Pattern.compile("[:：]$");

    /**
     * 첫 문단만 취하고 장식을 벗긴다. 모델이 머리말·따옴표·불릿을 붙이는 경우가 흔한데,
     * 그 때문에 내용이 멀쩡한 응답을 통째로 버리는 것은 과하다 — 형태만 정리하고 <b>문장은
     * 손대지 않는다</b>.
     *
     * <p><b>안내 문단은 건너뛴다.</b> 「요청하신 반박문을 아래에 작성하였습니다:」가 한 문단으로
     * 먼저 오면 그것이 첫 문단이 되는데, 숫자가 없어 수치 검사가 공허하게 참이 되고 금지어도
     * 없어 <b>안내문이 반박문 자리에 실렸다</b>. 본문은 그 다음 문단에 멀쩡히 있으므로,
     * 버리는 대신 건너뛰는 것이 맞다.
     */
    static String firstParagraph(String raw) {
        String text = dropHeaderParagraphs(raw.strip());
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

    /**
     * 문장이 아니라 <b>안내문</b>인가. 산문은 콜론으로 끝나지 않는다.
     *
     * <p>{@link #firstParagraph} 의 건너뛰기는 안내문 뒤에 본문이 <b>문단으로</b> 따라올 때만
     * 듣는다. 모델이 안내문 한 줄만 내놓으면 건너뛸 다음 문단이 없어 그것이 그대로 남으므로,
     * 호출부가 마지막에 한 번 더 본다.
     */
    static boolean looksLikeHeader(String text) {
        return HEADER_TAIL.matcher(text).find();
    }

    /** 콜론으로 끝나는 앞 문단을 걷어낸다. 전부 안내문이면 빈 문자열이 남아 길이 검사가 버린다. */
    private static String dropHeaderParagraphs(String text) {
        String rest = text;
        int blank;
        while ((blank = rest.indexOf("\n\n")) > 0
                && HEADER_TAIL.matcher(rest.substring(0, blank).strip()).find()) {
            rest = rest.substring(blank + 2).strip();
        }
        return rest;
    }
}

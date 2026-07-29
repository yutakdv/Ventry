package com.ventry.api.llm;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * BE-05 — LLM 탐색 계획 프롬프트 조립 + <b>응답 파서</b> (exploration spec §1).
 * LLM은 대화 맥락으로 A2~A4의 실행 여부·우선순위만 정한다(A1은 무조건 실행). 액션 공간이
 * 유한(축 4개)해 환각 통로가 없고, <b>화이트리스트 밖 축은 폐기</b>한다.
 *
 * <p><b>모든 실패가 폴백 축으로 수렴한다</b> — 빈 응답·깨진 JSON·화이트리스트 전멸은 전부
 * {@link #FALLBACK_AXES}(A1·A4)로 떨어진다. LLM이 죽어도 plan 이벤트는 폴백 축으로 송출되므로
 * 데모가 멈추지 않는다 (§2-5). 수치는 여기서 만들지 않는다 — 축 코드만 다룬다.
 */
public final class PlanPrompt {

    /** 탐색 축 화이트리스트 (expl §1, 4종 고정). */
    public static final Set<String> AXIS_WHITELIST = Set.of("A1", "A2", "A3", "A4");

    /** LLM 부재·실패 시 폴백 — A1(항상 실행) + A4(권리금 조건, A1의 부산물이라 비용 0). */
    public static final List<String> FALLBACK_AXES = List.of("A1", "A4");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private PlanPrompt() {}

    /**
     * 계획 요청 프롬프트. 프로필의 관심사(concerns)를 맥락으로 주되 <b>수치는 넣지 않는다</b> —
     * LLM이 숫자를 만드는 통로를 열지 않기 위함이다 (§0-2-1). 출력은 축 코드 JSON 배열로 강제한다.
     */
    /**
     * 업종 코드 → 한국어 표기. 한국어 프롬프트에 영문 코드가 섞이는 형태를 없앤다 (AI 리뷰 m-03).
     *
     * <p>액션 공간이 유한(축 4종 화이트리스트)이라 실질 위험은 없던 항목이지만, 코드가 그대로
     * 들어가면 모델이 「cafe」를 지시어가 아닌 데이터로 읽을 여지를 남긴다. 값은
     * {@code DiagnoseController} 가 세션 생성 <b>전에</b> 화이트리스트로 검증하므로
     * 매핑에 없는 값은 도달하지 않으며, 도달하더라도 원문을 그대로 쓴다(지어내지 않는다).
     */
    private static String industryLabel(String industry) {
        return switch (industry == null ? "" : industry) {
            case "cafe" -> "카페";
            case "food" -> "음식점";
            default -> industry;
        };
    }

    public static String build(String industry, List<String> concerns) {
        String context = (concerns == null || concerns.isEmpty())
                ? "특별한 관심사가 명시되지 않았습니다."
                : "사용자가 언급한 관심사: " + String.join(", ", concerns) + ".";
        return """
                당신은 상권 탐색 계획을 세우는 보조자입니다. 업종은 %s입니다.
                %s
                탐색 축은 다음 4가지뿐입니다:
                  A1=예산, A2=지역 확장, A3=업종 스왑, A4=권리금 조건.
                A1은 항상 포함하고, 관심사에 맞는 축을 우선순위 순으로 고르세요.
                오직 JSON 배열만 출력하세요. 예: ["A1","A4"]
                """.formatted(industryLabel(industry), context);
    }

    /**
     * 응답 → 축 목록. 화이트리스트로 거르고 순서·중복을 정리하며, 어떤 실패든 폴백 축으로 수렴한다.
     *
     * @param response LLM 응답(JSON 배열이 텍스트에 섞여 있어도 첫 배열만 취한다). empty면 폴백
     */
    public static List<String> parseAxes(Optional<String> response) {
        List<String> axes = parseAxesOrEmpty(response);
        return axes.isEmpty() ? FALLBACK_AXES : axes;
    }

    /**
     * {@link #parseAxes} 와 같되 <b>실패를 폴백으로 덮지 않고 빈 목록으로</b> 돌려준다.
     *
     * <p>캐시 경계({@link com.ventry.api.serving.PlanGenerator})가 성공과 실패를 구분해야 하기
     * 때문이다 — 폴백까지 캐시하면 일시적 타임아웃 한 번이 그 프로필의 축 계획을 1시간 고정한다
     * (리스크 검증 D-12·언어화와 같은 이유). 폴백을 <b>씌우는 일은 호출부</b>가 한다.
     */
    public static List<String> parseAxesOrEmpty(Optional<String> response) {
        if (response.isEmpty()) {
            return List.of();
        }
        String text = response.get();
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();   // 배열이 없다 — 깨진/비정형 응답
        }
        try {
            List<String> raw = JSON.readValue(text.substring(start, end + 1), STRING_LIST);
            return raw.stream()
                    .map(s -> s == null ? "" : s.trim().toUpperCase())
                    .filter(AXIS_WHITELIST::contains)   // A9 등 화이트리스트 밖 폐기
                    .distinct()
                    .toList();
        } catch (Exception e) {
            return List.of();   // 깨진 JSON
        }
    }
}

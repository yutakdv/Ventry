package com.ventry.api.common;

/** 판정 4단계 (docs/API_CONTRACT.md · DECISIONS.md #1 — "승인" 계열 단어 금지). */
public enum Verdict {
    FIT("적합"),
    CONDITIONAL("조건부 적합"),        // 무권리 시
    CAUTION("유의"),                  // 부담률 초과
    OUT_OF_SCOPE("범위 외");

    private final String label;

    Verdict(String label) {
        this.label = label;
    }

    /**
     * 화면·프롬프트에 쓰는 한글 판정어.
     *
     * <p>LLM 에 넘기는 사실 문자열에 영문 enum 이 그대로 들어가면 반박문이 <b>"OUT_OF_SCOPE 로
     * 분류된 이유가…"</b> 같은 메타 표현을 쓰게 된다 — 판정을 다투는 문장이 되어 스펙 §5-3
     * (「반박은 판정을 바꾸지 않는다」)과 어긋난다 (BE 리뷰 D-11).
     */
    public String label() {
        return label;
    }
}

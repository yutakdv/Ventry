package com.ventry.api.common;

/** 판정 4단계 (docs/API_CONTRACT.md · DECISIONS.md #1 — "승인" 계열 단어 금지). */
public enum Verdict {
    FIT,            // 적합
    CONDITIONAL,    // 조건부 적합 (무권리 시)
    CAUTION,        // 유의 (부담률 초과)
    OUT_OF_SCOPE    // 범위 외
}

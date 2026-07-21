package com.ventry.api.engine;

import com.ventry.api.common.Verdict;

/** 역방향 판정 결과 — 판정 4단계 + 부족분(만원). 계약 check-area {verdict, gap_amount}과 1:1. */
public record ReverseResult(Verdict verdict, int gapAmount) {}

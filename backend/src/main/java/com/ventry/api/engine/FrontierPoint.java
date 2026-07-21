package com.ventry.api.engine;

/**
 * 진입 프론티어 계단 함수 좌표 — (예산 임계, 그 이하 진입 후보 수).
 * 계약 explore done.frontier_points([budget, count])와 1:1 (P1 미니 차트용).
 */
public record FrontierPoint(int budget, int count) {}

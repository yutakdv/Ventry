package com.ventry.api.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;

/**
 * BE-03d — 해석적 진입 프론티어 (순수 함수, exploration spec §2-1).
 * 그리드 없이 정렬 비교만으로 계단 함수를 닫힌 형태로 계산 — 오차 0.
 * costs = 후보별 비용 중앙값(만원); 권리금 포함 c_a 또는 무권리 c'_a 배열 모두 동일 적용.
 * 지속 프론티어(비단조, 확장 부담률)는 BE-04·05에서 확장한다.
 */
public final class Frontier {

    private Frontier() {}

    /** N_entry(B) = |{a : c_a ≤ B}| — B에 대해 단조 비감소. */
    public static int nEntry(int[] costs, int budget) {
        int count = 0;
        for (int c : costs) {
            if (c <= budget) {
                count++;
            }
        }
        return count;
    }

    /** 다음 경계 = min{c_a : c_a > B₀}. 후보 없으면 empty. */
    public static OptionalInt nextBoundary(int[] costs, int b0) {
        int best = Integer.MAX_VALUE;
        boolean found = false;
        for (int c : costs) {
            if (c > b0 && c < best) {
                best = c;
                found = true;
            }
        }
        return found ? OptionalInt.of(best) : OptionalInt.empty();
    }

    /** 갭 = 다음 경계 − B₀ (원 단위 정확). 상향 후보 없으면 empty. */
    public static OptionalInt gap(int[] costs, int b0) {
        OptionalInt boundary = nextBoundary(costs, b0);
        return boundary.isPresent() ? OptionalInt.of(boundary.getAsInt() - b0) : OptionalInt.empty();
    }

    /** 하향 안전 마진 B_safe = max{c_a : c_a ≤ B₀}. 진입 후보 없으면 empty. */
    public static OptionalInt bSafe(int[] costs, int b0) {
        int best = Integer.MIN_VALUE;
        boolean found = false;
        for (int c : costs) {
            if (c <= b0 && c > best) {
                best = c;
                found = true;
            }
        }
        return found ? OptionalInt.of(best) : OptionalInt.empty();
    }

    /** 계단 함수 좌표: 각 서로 다른 비용값 v에 대해 (v, N_entry(v)). */
    public static List<FrontierPoint> frontierPoints(int[] costs) {
        int[] sorted = costs.clone();
        Arrays.sort(sorted);
        List<FrontierPoint> points = new ArrayList<>();
        int i = 0;
        while (i < sorted.length) {
            int value = sorted[i];
            while (i < sorted.length && sorted[i] == value) {
                i++;
            }
            points.add(new FrontierPoint(value, i));   // 정렬 상태에서 i = value 이하 개수
        }
        return points;
    }
}

package com.ventry.api.serving;

import com.ventry.api.engine.Frontier;
import com.ventry.api.engine.FrontierPoint;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import org.springframework.stereotype.Service;

/**
 * BE-04 — 해석적 프론티어 결선. 후보 상권을 비용 배열로 만들어 {@link Frontier}(BE-03d 구현·테스트 완료)를
 * <b>호출만</b> 한다. 계단 함수·경계·갭·마진은 전부 닫힌 형태 계산이며 그리드·샘플링을 쓰지 않는다
 * (exploration spec §2-1).
 *
 * <p>비용 배열은 두 종류다 — 권리금 포함 {@code c_a}(기본 진입 기준)와 무권리 {@code c'_a}.
 * 무권리 프론티어는 <b>같은 함수에 배열만 바꿔 넣어</b> 얻는다 (추가 비용 0, expl §2-1).
 */
@Service
public class FrontierService {

    private final CandidateSource candidates;

    public FrontierService(CandidateSource candidates) {
        this.candidates = candidates;
    }

    // ── 계단 함수 좌표 (계약 explore done.frontier_points) ──────────────────

    /** 권리금 포함 기준 계단 좌표 [예산, 진입 후보 수]. */
    public List<List<Integer>> frontierPoints(String industry) {
        return toContract(Frontier.frontierPoints(inclusiveCosts(industry)));
    }

    /** 무권리(c'_a) 기준 계단 좌표 — T5 무권리 조건부 인사이트용 (BE-05 송출). */
    public List<List<Integer>> frontierPointsExPremium(String industry) {
        return toContract(Frontier.frontierPoints(exPremiumCosts(industry)));
    }

    // ── 경계·갭·마진 (BE-05 T1·T2 인사이트의 재료) ─────────────────────────

    /** N_entry(B) = 예산 이하로 진입 가능한 후보 수. */
    public int entryCount(String industry, int budget) {
        return Frontier.nEntry(inclusiveCosts(industry), budget);
    }

    /** 다음 경계 = min{c_a > B₀}. 상향 후보가 없으면 empty. */
    public OptionalInt nextBoundary(String industry, int budget) {
        return Frontier.nextBoundary(inclusiveCosts(industry), budget);
    }

    /** 갭 = 다음 경계 − B₀ (원 단위 정확). */
    public OptionalInt gap(String industry, int budget) {
        return Frontier.gap(inclusiveCosts(industry), budget);
    }

    /** 하향 안전 마진 B_safe = max{c_a ≤ B₀}. */
    public OptionalInt safeBudget(String industry, int budget) {
        return Frontier.bSafe(inclusiveCosts(industry), budget);
    }

    /**
     * B₀ 위쪽 <b>모든</b> 경계값을 오름차순으로. {@code nextBoundary}가 첫 경계 하나만 주는 데 비해
     * 이쪽은 계단 함수의 남은 점프 지점 전부를 준다 — 인사이트 스코어링이 경계들을 비교해야 하기 때문이다.
     * 같은 비용의 후보가 여럿이면 경계는 하나다(중복 제거).
     */
    public List<Integer> boundaries(String industry, int budget) {
        return above(inclusiveCosts(industry), budget);
    }

    /** 무권리(c'_a) 기준 경계 — A4 축(권리금 조건)의 부산물 (expl §1). */
    public List<Integer> boundariesExPremium(String industry, int budget) {
        return above(exPremiumCosts(industry), budget);
    }

    private static List<Integer> above(int[] costs, int budget) {
        return Arrays.stream(costs).filter(c -> c > budget).distinct().sorted().boxed().toList();
    }

    // ── 비용 배열 (탐색당 쿼리 1회로 받은 후보를 인메모리 변환) ──────────────

    /** 권리금 포함 비용 중앙값 배열 c_a. */
    int[] inclusiveCosts(String industry) {
        return candidates.findCandidates(industry).stream()
                .mapToInt(CandidateArea::inclusiveCostMedian)
                .toArray();
    }

    /** 무권리 비용 중앙값 배열 c'_a. */
    int[] exPremiumCosts(String industry) {
        return candidates.findCandidates(industry).stream()
                .mapToInt(CandidateArea::exPremiumCostMedian)
                .toArray();
    }

    /** FrontierPoint → 계약형 [예산, 후보 수] 쌍. */
    private static List<List<Integer>> toContract(List<FrontierPoint> points) {
        return points.stream().map(p -> List.of(p.budget(), p.count())).toList();
    }
}

package com.ventry.api.serving;

import com.ventry.api.engine.AxisScores;
import com.ventry.api.engine.CostBlocks;
import com.ventry.api.engine.Interval;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 데모 후보 상권 픽스처 — 목→실 전환의 "입력"만 픽스처로 남긴다(출력은 engine이 계산).
 * 수치는 데모 프로필(예산 8,000만·θ 0.15)에서 expl §8 판정(🟢2·🟠1, A-9999 조건부 갭 1,320)이
 * 재현되도록 설계. BE-02(AI-03 DDL)에서 사전 적재 테이블 조회로 대체 예정.
 * 좌표 WGS84, 금액 만원 단위. 가정: docs/assumptions.md (#7·#8·#9).
 *
 * <p>임대료·매출은 실측 수준으로, 월 고정비는 데모 예산 8,000만 재현을 위해 축소 보정된 값으로
 * 각각 두었다(assumptions #9) — 월 고정비는 API에 노출되지 않고 예비 운영자금(×6) 산출에만 쓰인다.
 * 픽스처 내부 순서 정합은 유지한다: 매출 순위 = w2 순위, (매출÷임대료) 순위 = w5 순위.
 */
@Component
@Profile("!db")
public class DemoCandidates implements CandidateSource {

    // 추천 풀 (예산 8,000·θ 0.15에서 FIT·FIT·CAUTION, 점수순 망원>합정>홍대)
    private static final CandidateArea MANGWON = new CandidateArea(
            "A-1101", "망원역 상권", 37.5556, 126.9106,
            new CostBlocks(new Interval(4000, 4800), new Interval(1400, 1700),
                    new Interval(1000, 1400), 100),          // incl 중앙값 7,750 ≤ 8,000 → 진입
            new AxisScores(0.82, 0.74, 0.68, 0.71, 0.77),
            198, 1800, 24500,                                // 부담률 0.11 ≤ θ → 적합
            "REB", "홍대합정상권", false, "망원", "6", 320, 21000, false);

    private static final CandidateArea HAPJEONG = new CandidateArea(
            "A-1102", "합정역 상권", 37.5495, 126.9139,
            new CostBlocks(new Interval(4200, 5000), new Interval(1400, 1700),
                    new Interval(1000, 1400), 100),          // incl 중앙값 7,950 ≤ 8,000 → 진입
            new AxisScores(0.79, 0.81, 0.55, 0.66, 0.70),
            273, 2100, 22800,                                // 부담률 0.13 ≤ θ → 적합
            "REB", "홍대합정상권", false, "합정", "2·6", 210, 68000, false);

    private static final CandidateArea HONGDAE = new CandidateArea(
            "A-1103", "홍대입구역 상권", 37.5572, 126.9236,
            new CostBlocks(new Interval(4000, 4600), new Interval(1300, 1600),
                    new Interval(1000, 1300), 100),          // incl 중앙값 7,500 ≤ 8,000 → 진입
            new AxisScores(0.91, 0.88, 0.31, 0.74, 0.52),
            456, 2400, 38200,                                // 부담률 0.19 > θ → 유의
            "REB", "홍대합정상권", false, "홍대입구", "2", 180, 92000, false);

    // 역방향 판정용 임의 클릭 상권 (예산 8,000에서 조건부, 갭 1,320)
    private static final CandidateArea YEONNAM = new CandidateArea(
            "A-9999", "연남동 상권", 37.5623, 126.9250,
            new CostBlocks(new Interval(5400, 6000), new Interval(1420, 1820),
                    new Interval(1200, 1600), 100),          // ex 중앙값 7,700 ≤ 8,000 < incl 중앙값 9,320 → 조건부
            new AxisScores(0.75, 0.70, 0.60, 0.62, 0.66),
            256, 1600, 18600,                                // 부담률 0.16 (판정은 진입 단계에서 결정)
            "REB", "홍대합정상권", false, "가좌", "경의중앙", 640, 12000, false);

    private static final List<CandidateArea> RECOMMEND_POOL = List.of(MANGWON, HAPJEONG, HONGDAE);
    private static final List<CandidateArea> ALL = List.of(MANGWON, HAPJEONG, HONGDAE, YEONNAM);

    /** 추천(화면 3)·프리뷰 후보 풀. 데모는 cafe 단일 업종이라 industry 는 무시한다. */
    @Override
    public List<CandidateArea> findCandidates(String industry) {
        return RECOMMEND_POOL;
    }

    /** 역방향 판정용 상권 조회 (임의 클릭). 데모는 단일 업종이라 industry 는 무시한다. */
    @Override
    public Optional<CandidateArea> find(String industry, String areaCode) {
        return ALL.stream().filter(c -> c.areaCode().equals(areaCode)).findFirst();
    }
}

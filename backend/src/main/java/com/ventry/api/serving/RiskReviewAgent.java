package com.ventry.api.serving;

import com.ventry.api.common.FinanceDtos.RiskReview;
import com.ventry.api.common.Verdict;
import com.ventry.api.recommend.RecommendDtos.Area;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * BE-05 — 리스크 검증 에이전트 (스펙 §5-3, 이슈 #96). 「추천 에이전트(승인 논리) ↔ 리스크 검증
 * 에이전트(반대 논리)」 구도의 반대편이며 <b>1왕복 고정</b>이다. 이 클래스는 <b>무엇을 사실로
 * 넘길지</b>를 정하고, 호출·캐시는 {@link ReviewGenerator} 가 맡는다.
 *
 * <p><b>판정을 바꾸지 않는다.</b> 판정 4단계는 결정적 계산이 만들고 이 에이전트는 그 결과를
 * 반박하는 문장을 붙일 뿐이다 — 반박이 판정을 뒤집으면 "모든 숫자는 결정적 계산이 만든다"가
 * 무너진다 (§0-1). 스펙 §5-3 도 장애 시 "추천 판정 유지"를 명시한다.
 *
 * <p><b>사실에 넣은 것만 반박문에 나올 수 있다</b> — {@link com.ventry.api.llm.ReviewPrompt}
 * 가 출력의 숫자 토큰을 입력 사실과 대조해 하나라도 없으면 응답 전체를 버린다(§5-3 "반박도 입력
 * JSON 밖 수치 금지"). 그래서 사실 목록을 정하는 일이 곧 반박의 사정거리를 정하는 일이다.
 */
@Service
public class RiskReviewAgent {

    /** 반박 대상으로 넘길 상위 후보 수. 전건을 넘기면 프롬프트가 커지고 요지가 흐려진다. */
    private static final int FACT_AREAS = 3;

    private final ReviewGenerator generator;

    public RiskReviewAgent(ReviewGenerator generator) {
        this.generator = generator;
    }

    /**
     * 화면 3 추천 결과에 대한 반박.
     *
     * <p><b>확정 예산과 후보 총수를 사실에서 뺐다.</b> 슬라이더는 예산이 바뀔 때마다
     * {@code /api/recommend} 를 다시 부르는데(스펙 §7 「&lt;100ms 재계산 체감」) 그 값들을 사실에
     * 넣으면 <b>드래그 한 칸마다 캐시가 빗나가</b> 매번 LLM 왕복(실측 약 1.9초)을 타게 된다.
     *
     * <p>대신 <b>상위 후보의 판정</b>을 넣는다. 종합점수 정렬은 예산과 무관하므로 상위 3곳의
     * 이름은 고정이고, 예산이 움직이면 그 3곳의 <b>판정만</b> 바뀐다. 즉 사실이 달라지는 시점이
     * 곧 <b>추천이 실질적으로 달라지는 시점</b>이고, 그 사이 구간에서는 같은 반박이 유효하다.
     * 예산 값 자체가 없으면 반박문이 예산을 인용할 수 없지만, 반박의 대상은 예산이 아니라
     * 그 예산이 만들어 낸 후보 구성이다.
     */
    public RiskReview forRecommend(String industry, List<Area> areas) {
        return generator.generate(recommendFacts(industry, areas),
                ReasonTemplate.recommendReview());
    }

    /** 역방향 판정에 대한 반박. 클릭 한 곳의 판정·부족분·부담률이 사실이다. */
    public RiskReview forCheckArea(String areaName, Verdict verdict, Integer gapAmount,
                                   double burdenRatio) {
        String facts = """
                판정 대상: %s
                판정: %s
                부족분(만원): %s
                환산임대료 대비 추정매출 부담률: %.3f
                """.formatted(areaName, verdict,
                gapAmount == null ? "없음" : String.valueOf(gapAmount), burdenRatio);
        return generator.generate(facts, ReasonTemplate.checkAreaReview());
    }

    private static String recommendFacts(String industry, List<Area> areas) {
        String top = areas.stream().limit(FACT_AREAS)
                .map(a -> "  - %s: 판정 %s, 종합점수 %d, 부담률 %.3f, 환산임대료 %d만원, 추정매출 %d만원"
                        .formatted(a.name(), a.verdict(), a.score(), a.burdenRatio(),
                                a.monthlyRent(), a.estSales()))
                .collect(Collectors.joining("\n"));
        return """
                업종: %s
                상위 후보:
                %s
                """.formatted(industry, top);
    }
}

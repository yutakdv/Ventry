package com.ventry.api.engine;

import java.util.Set;

/**
 * 금융상품 자격 정규칙. 각 필드가 {@code null}(또는 빈 집합)이면 "해당 축 무제약".
 *
 * @param maxAge         상한 연령(포함). null=연령 무제약
 * @param industries     허용 업종 코드. null/빈 집합=전 업종
 * @param regions        허용 지역. null/빈 집합=전 지역
 * @param preStartupOnly 예비창업자 한정 여부 (스펙 §5-4 "만 39세 이하 예비창업자로서…").
 *                       true면 기존 사업자·재창업자는 제외
 * @param existingBusinessOnly 기존 사업자 한정 여부 — {@code preStartupOnly} 의 <b>반대 축</b>이다.
 *                       공고문이 "보유한 대출"(대환) 또는 "재창업"을 명시한 상품은 예비창업자가
 *                       신청 자체를 할 수 없다. 이 축이 없던 동안 예비창업 프로필에 대환대출이
 *                       편성됐다 (이슈 #90 문제 2)
 * @param targetGroups   <b>프로필로 확인할 수 없는 대상 한정 요건</b> (장애인기업·사회적경제기업·
 *                       인증기업·고용우수기업 등). 5종 축으로는 공고문의 대상 요건을 표현할 수
 *                       없어 신청 자격이 없는 상품이 "자격 요건 부합"으로 노출됐다 (BE 리뷰 D-06).
 *                       null/빈 집합 = 대상 제한 없음
 */
public record Eligibility(Integer maxAge, Set<String> industries, Set<String> regions,
                          boolean preStartupOnly, boolean existingBusinessOnly,
                          Set<String> targetGroups) {

    /** 기존 호출부·픽스처 호환 — 기존 사업자 한정이 아닌 상품. */
    public Eligibility(Integer maxAge, Set<String> industries, Set<String> regions,
                       boolean preStartupOnly) {
        this(maxAge, industries, regions, preStartupOnly, false, null);
    }

    /** 대상 한정이 없는 상품 (적재 상품 26건 중 21건). */
    public Eligibility(Integer maxAge, Set<String> industries, Set<String> regions,
                       boolean preStartupOnly, boolean existingBusinessOnly) {
        this(maxAge, industries, regions, preStartupOnly, existingBusinessOnly, null);
    }
}

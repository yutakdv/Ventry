package com.ventry.api.engine;

import java.util.List;
import java.util.Set;

/**
 * BE-03a — 자격 정규칙 필터 (순수 함수, 스펙 §5-1).
 * 나이·창업 단계(예비창업 한정 / 기존 사업자 한정)·업종·지역 정규칙만 판정한다.
 * status=open·중복수혜·담보·상환 여력 등 조달 게이트는 BE-04 소관.
 */
public final class EligibilityFilter {

    private EligibilityFilter() {}

    /** 프로필이 자격 요건에 부합하는 상품만, 입력 순서를 보존해 반환한다. */
    public static List<FundingProduct> qualify(Profile profile, List<FundingProduct> products) {
        return products.stream().filter(p -> qualifies(profile, p.eligibility())).toList();
    }

    private static boolean qualifies(Profile profile, Eligibility e) {
        // 나이 상한이 걸린 상품은 **나이를 확인했을 때만** 통과시킨다. 진단 폼에서 나이는 필수가
        // 아니라 미기재가 실제로 들어오는데, 예전 구현은 그것을 0으로 강등해 「만 39세 이하」를
        // 통과시켰다 — regions·targetGroups 와 같은 하향 안전 규칙으로 통일한다 (이슈 #155 ①).
        if (e.maxAge() != null && (profile.age() == null || profile.age() > e.maxAge())) {
            return false;
        }
        if (e.preStartupOnly() && profile.existingBusiness()) {
            return false;
        }
        // 반대 축 — 대환·재창업 상품은 예비창업자가 신청할 수 없다 (#90 문제 2).
        if (e.existingBusinessOnly() && !profile.existingBusiness()) {
            return false;
        }
        if (constrains(e.industries()) && !e.industries().contains(profile.industry())) {
            return false;
        }
        // 대상 한정 요건(장애인기업·사회적경제기업·인증기업 등)은 진단 폼에 대응 필드가 없다.
        // 확인하지 못한 자격을 주장하지 않는다 — regions 와 동형의 하향 안전 규칙이다.
        // 「미기재 = 해당 없음」이므로 대상 한정 상품은 전부 탈락한다 (BE 리뷰 D-06 ②).
        if (constrains(e.targetGroups())) {
            return false;
        }
        return !constrains(e.regions()) || regionMatches(profile.region(), e.regions());
    }

    /** null·빈 집합은 "무제약" — 제약이 걸린 경우만 true. */
    private static boolean constrains(Set<String> allowed) {
        return allowed != null && !allowed.isEmpty();
    }

    /**
     * 지역 판정 — 해상도가 서로 다르다.
     *
     * <p>계약상 {@code region_hint} 는 "시/도 + 구/군" 결합 문자열("서울 마포구")인데
     * {@code finance_product.regions} 는 시/도 수준(['서울'])이다. 문자열 동등 비교로는
     * 서울 신청자에게 지역제한 상품이 전건 배제된다 (이슈 #76 — 적재 26건 중 25건이 ['서울']).
     *
     * <p>그래서 프로필 지역의 <b>시/도</b>가 허용 목록에 있거나, 결합 문자열의 어느 토큰이
     * 그대로 있으면 통과시킨다(자치구 해상도 상품 대비). 지역을 모르면 통과시키지 않는다 —
     * 확인하지 못한 자격을 주장하지 않는 것이 하향 안전 마진에 정합한다.
     */
    private static boolean regionMatches(String region, Set<String> allowed) {
        if (region == null || region.isBlank()) {
            return false;
        }
        String[] tokens = region.trim().split("\\s+");
        if (allowed.contains(sido(tokens[0]))) {
            return true;
        }
        for (String token : tokens) {
            if (allowed.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /** 광역단체 접미사 — 긴 것부터 봐야 '제주특별자치도'가 '도'로 먼저 잘리지 않는다. */
    private static final List<String> SIDO_SUFFIXES =
            List.of("특별자치도", "특별자치시", "광역시", "특별시", "도");

    /** "서울특별시" · "경기도" → "서울" · "경기". 접미사가 없으면 입력 그대로. */
    static String sido(String head) {
        for (String suffix : SIDO_SUFFIXES) {
            if (head.length() > suffix.length() && head.endsWith(suffix)) {
                return head.substring(0, head.length() - suffix.length());
            }
        }
        return head;
    }
}

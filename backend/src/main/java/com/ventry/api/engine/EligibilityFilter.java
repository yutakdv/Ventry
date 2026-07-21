package com.ventry.api.engine;

import java.util.List;
import java.util.Set;

/**
 * BE-03a — 자격 정규칙 필터 (순수 함수, 스펙 §5-1).
 * 나이·업종·지역 정규칙만 판정한다. status=open·중복수혜 등 조달 게이트는 BE-04 소관.
 */
public final class EligibilityFilter {

    private EligibilityFilter() {}

    /** 프로필이 자격 요건에 부합하는 상품만, 입력 순서를 보존해 반환한다. */
    public static List<FundingProduct> qualify(Profile profile, List<FundingProduct> products) {
        return products.stream().filter(p -> qualifies(profile, p.eligibility())).toList();
    }

    private static boolean qualifies(Profile profile, Eligibility e) {
        if (e.maxAge() != null && profile.age() > e.maxAge()) {
            return false;
        }
        if (constrains(e.industries()) && !e.industries().contains(profile.industry())) {
            return false;
        }
        return !constrains(e.regions()) || e.regions().contains(profile.region());
    }

    /** null·빈 집합은 "무제약" — 제약이 걸린 경우만 true. */
    private static boolean constrains(Set<String> allowed) {
        return allowed != null && !allowed.isEmpty();
    }
}

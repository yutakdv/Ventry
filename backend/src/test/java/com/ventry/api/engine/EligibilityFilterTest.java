package com.ventry.api.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.ventry.api.common.FinanceDtos.Source;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** BE-03a EligibilityFilter — 자격 정규칙(나이·업종·지역) 순수 함수 (스펙 §5-1). */
class EligibilityFilterTest {

    private static final Source SRC = new Source("소진공", "https://semas.or.kr", "2026-07-19");

    /** 데모 프로필: 만 32세 / 자기자본 5,000만 / 카페 / 마포. */
    private final Profile cafe32Mapo = new Profile(32, 5000, false, "cafe", "마포");

    private FundingProduct product(String name, Eligibility e) {
        return new FundingProduct(name, e, 3000, 2.5, 60, null, "open", "2026-Q1", SRC);
    }

    @Test
    void unconstrainedProduct_qualifiesEveryProfile() {
        FundingProduct p = product("무제약", new Eligibility(null, null, null, false));
        assertThat(EligibilityFilter.qualify(cafe32Mapo, List.of(p))).containsExactly(p);
    }

    @Test
    void ageOverMax_excluded() {
        FundingProduct p = product("청년(39이하)", new Eligibility(39, null, null, false));
        Profile over = new Profile(45, 5000, false, "cafe", "마포");
        assertThat(EligibilityFilter.qualify(over, List.of(p))).isEmpty();
    }

    @Test
    void ageAtMaxBoundary_included() {
        FundingProduct p = product("청년(39이하)", new Eligibility(39, null, null, false));
        Profile at = new Profile(39, 5000, false, "cafe", "마포");
        assertThat(EligibilityFilter.qualify(at, List.of(p))).containsExactly(p);
    }

    @Test
    void industryMismatch_excluded() {
        FundingProduct p = product("제조업 전용", new Eligibility(null, Set.of("manufacturing"), null, false));
        assertThat(EligibilityFilter.qualify(cafe32Mapo, List.of(p))).isEmpty();
    }

    @Test
    void regionMismatch_excluded() {
        FundingProduct p = product("강남 전용", new Eligibility(null, null, Set.of("강남"), false));
        assertThat(EligibilityFilter.qualify(cafe32Mapo, List.of(p))).isEmpty();
    }

    /** 스펙 §5-4 인용 예시 "만 39세 이하 예비창업자로서…" — 기존 사업자는 제외된다. */
    @Test
    void preStartupOnlyProduct_excludesExistingBusinessOwner() {
        FundingProduct p = product("예비창업자 전용", new Eligibility(39, null, null, true));
        Profile existing = new Profile(32, 5000, true, "cafe", "마포");
        assertThat(EligibilityFilter.qualify(existing, List.of(p))).isEmpty();
        assertThat(EligibilityFilter.qualify(cafe32Mapo, List.of(p))).containsExactly(p);
    }

    @Test
    void preStartupFlagIgnored_whenProductDoesNotRequireIt() {
        FundingProduct p = product("무제약", new Eligibility(null, null, null, false));
        Profile existing = new Profile(32, 5000, true, "cafe", "마포");
        assertThat(EligibilityFilter.qualify(existing, List.of(p))).containsExactly(p);
    }

    /**
     * #76 — 계약상 region_hint 는 "시/도 + 구/군" 결합 문자열인데
     * finance_product.regions 는 시/도 수준(['서울'])이라 문자열 동등 비교가 전부 어긋났다.
     * 실적재 26건 중 20건이 ['서울'] 이므로 서울 신청자에게 지역제한 상품이 전건 배제됐다.
     */
    @Test
    void sidoLevelProductRegion_matchesSiGunGuHint() {
        FundingProduct p = product("서울 전용", new Eligibility(null, null, Set.of("서울"), false));
        Profile seoulMapo = new Profile(32, 5000, false, "cafe", "서울 마포구");
        assertThat(EligibilityFilter.qualify(seoulMapo, List.of(p))).containsExactly(p);
    }

    @Test
    void fullSidoName_normalizedBeforeMatching() {
        FundingProduct p = product("서울 전용", new Eligibility(null, null, Set.of("서울"), false));
        Profile formal = new Profile(32, 5000, false, "cafe", "서울특별시 마포구");
        assertThat(EligibilityFilter.qualify(formal, List.of(p))).containsExactly(p);
    }

    @Test
    void otherSido_stillExcluded() {
        FundingProduct p = product("서울 전용", new Eligibility(null, null, Set.of("서울"), false));
        Profile gyeonggi = new Profile(32, 5000, false, "cafe", "경기도 성남시");
        assertThat(EligibilityFilter.qualify(gyeonggi, List.of(p))).isEmpty();
    }

    /** 지역을 모르면 지역제한 상품을 통과시키지 않는다 — 확인 못 한 자격을 주장하지 않는다. */
    @Test
    void blankRegion_excludedFromRegionConstrainedProduct() {
        FundingProduct constrained =
                product("서울 전용", new Eligibility(null, null, Set.of("서울"), false));
        FundingProduct free = product("무제약", new Eligibility(null, null, null, false));
        Profile unknown = new Profile(32, 5000, false, "cafe", "  ");
        assertThat(EligibilityFilter.qualify(unknown, List.of(constrained, free)))
                .containsExactly(free);
    }

    /**
     * #82 — 확정 이율이 없는 상품(적재 26건 중 7건, 전부 "은행금리 대비 차감폭")의 정책.
     *
     * <p>결정(2026-07-26): <b>조달 조합에서는 제외</b>(월 상환액을 못 구해 상환 여력을 검증할 수
     * 없다 — {@code FundingCheck} 의 {@code hasKnownRate} 게이트)하되, <b>자격 부합 목록에는
     * 남긴다</b>. 자격 축은 연령·업종·지역·예비창업이지 금리가 아니므로, 금리를 모른다는 이유로
     * 자격 자체를 부정하면 실재하는 제도를 화면에서 지우게 된다.
     */
    @Test
    void unknownRateProduct_stillQualifies_soItCanBeListed() {
        FundingProduct unknownRate = new FundingProduct(
                "ESG 실천기업 보증", new Eligibility(null, null, Set.of("서울"), false),
                80000, null, FundingProduct.RATE_VARIABLE,
                "서울시자금 이용 시 은행금리에서 2.5% 차감(서울시 부담)",
                null, null, "open", "2026-07-21", SRC);
        Profile seoul = new Profile(32, 5000, false, "cafe", "서울 마포구");

        assertThat(EligibilityFilter.qualify(seoul, List.of(unknownRate)))
                .containsExactly(unknownRate);
        assertThat(unknownRate.hasKnownRate()).isFalse();   // 조달 조합에서는 제외된다
    }

    /** 목록 투영에 금리 미공시 사유가 그대로 실려야 화면이 "금리 미공시"를 표기할 수 있다. */
    @Test
    void unknownRateProduct_projectionCarriesRateTypeAndNote() {
        String note = "은행금리에서 1.8% 차감(서울시 부담)";
        FundingProduct p = new FundingProduct(
                "장애인 기업 특별보증", new Eligibility(null, null, null, false),
                10000, null, FundingProduct.RATE_VARIABLE, note,
                84, null, "open", "2026-07-21", SRC);

        var dto = p.toProduct(null);
        assertThat(dto.rate()).isNull();
        assertThat(dto.rateType()).isEqualTo(FundingProduct.RATE_VARIABLE);
        assertThat(dto.rateNote()).isEqualTo(note);
    }

    @Test
    void mixedList_returnsOnlyQualifyingPreservingOrder() {
        FundingProduct ok = product("적합", new Eligibility(39, Set.of("cafe"), Set.of("마포"), false));
        FundingProduct no = product("부적합(연령)", new Eligibility(30, null, null, false));
        assertThat(EligibilityFilter.qualify(cafe32Mapo, List.of(ok, no))).containsExactly(ok);
    }
}

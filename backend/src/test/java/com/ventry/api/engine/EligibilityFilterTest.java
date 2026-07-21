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
    private final Profile cafe32Mapo = new Profile(32, 5000, "cafe", "마포");

    private FundingProduct product(String name, Eligibility e) {
        return new FundingProduct(name, e, 3000, 2.5, 60, null, "open", SRC);
    }

    @Test
    void unconstrainedProduct_qualifiesEveryProfile() {
        FundingProduct p = product("무제약", new Eligibility(null, null, null));
        assertThat(EligibilityFilter.qualify(cafe32Mapo, List.of(p))).containsExactly(p);
    }

    @Test
    void ageOverMax_excluded() {
        FundingProduct p = product("청년(39이하)", new Eligibility(39, null, null));
        Profile over = new Profile(45, 5000, "cafe", "마포");
        assertThat(EligibilityFilter.qualify(over, List.of(p))).isEmpty();
    }

    @Test
    void ageAtMaxBoundary_included() {
        FundingProduct p = product("청년(39이하)", new Eligibility(39, null, null));
        Profile at = new Profile(39, 5000, "cafe", "마포");
        assertThat(EligibilityFilter.qualify(at, List.of(p))).containsExactly(p);
    }

    @Test
    void industryMismatch_excluded() {
        FundingProduct p = product("제조업 전용", new Eligibility(null, Set.of("manufacturing"), null));
        assertThat(EligibilityFilter.qualify(cafe32Mapo, List.of(p))).isEmpty();
    }

    @Test
    void regionMismatch_excluded() {
        FundingProduct p = product("강남 전용", new Eligibility(null, null, Set.of("강남")));
        assertThat(EligibilityFilter.qualify(cafe32Mapo, List.of(p))).isEmpty();
    }

    @Test
    void mixedList_returnsOnlyQualifyingPreservingOrder() {
        FundingProduct ok = product("적합", new Eligibility(39, Set.of("cafe"), Set.of("마포")));
        FundingProduct no = product("부적합(연령)", new Eligibility(30, null, null));
        assertThat(EligibilityFilter.qualify(cafe32Mapo, List.of(ok, no))).containsExactly(ok);
    }
}

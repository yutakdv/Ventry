package com.ventry.api.engine;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.ventry.api.common.FinanceDtos.Source;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * #77 — 자격 매칭 Precision/Recall 골드. <b>부록 1 성적표 「자격 매칭 P/R」 행의 원천</b>.
 *
 * <p>AI 평가 하네스(`make eval`)는 이 축을 채점하지 않는다 — {@link EligibilityFilter} 는 LLM이
 * 개입하지 않는 순수 함수라 백엔드 테스트가 검증하는 것이 맞다(assumptions #29). 그 위임을
 * 실제로 이행하는 것이 이 테스트다.
 *
 * <p><b>골드의 독립성</b>: 기대 라벨은 필터를 돌려 만든 것이 아니라 사람이
 * {@code db/init/20_finance.sql} 의 자격 축을 읽고 손으로 적었다({@code matching_gold.json}).
 * 필터 출력으로 골드를 만들면 순환 논증이 되어 아무것도 증명하지 못한다.
 *
 * <p><b>대상은 실적재본이다</b>: 픽스처가 아니라 배포되는 덤프를 직접 파싱한다. 덤프가 바뀌면
 * 이 테스트가 먼저 깨져야 골드와 데이터의 괴리를 놓치지 않는다. 덤프를 {@code src/test/resources}
 * 로 복사해 두면 그 순간 픽스처가 되어 실적재본과 조용히 어긋날 수 있으므로 복사하지 않는다.
 *
 * <p><b>Docker 이미지 빌드에서는 건너뛴다</b>: 이미지의 빌드 컨텍스트는 {@code ./backend} 하나뿐이라
 * 저장소 루트의 {@code db/init} 이 아예 존재하지 않는다. 채점은 전체 체크아웃으로 도는
 * backend-ci 「Test &amp; Build」 단계가 담당한다. 다만 {@code db/init} 이 보이는데 덤프만 없는
 * 경우는 실제 결함이므로 건너뛰지 않고 실패한다.
 */
class EligibilityMatchingGoldTest {

    /** 덤프 1행 = 상품 1건 (emit 이 보장하는 포맷). id·자격 축만 뽑는다. */
    private static final Pattern PRODUCT_ROW = Pattern.compile(
            "^\\('(?<id>F-\\d+)', '(?<name>(?:[^']|'')*)', '(?:[^']|'')*', "
                    + "(?<maxAge>NULL|\\d+), (?<industries>NULL|ARRAY\\[[^\\]]*\\]), "
                    + "(?<regions>NULL|ARRAY\\[[^\\]]*\\]), (?<preStartup>TRUE|FALSE), "
                    + "(?<existingOnly>TRUE|FALSE), ",
            Pattern.MULTILINE);

    private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path DUMP_DIR = REPO_ROOT.resolve("db/init");
    private static final Source SRC = new Source("적재본", "https://example.test", "2026-07-26");

    private static List<FundingProduct> products;
    private static JsonNode gold;

    @BeforeAll
    static void loadFixtures() throws IOException {
        // db/init 이 있는데 덤프만 없으면 parseShippedProducts 가 그대로 실패한다 — 그건 실제 결함이다.
        if (Files.isDirectory(DUMP_DIR)) {
            products = parseShippedProducts();
        }
        try (var in = EligibilityMatchingGoldTest.class.getResourceAsStream("/matching_gold.json")) {
            gold = JsonMapper.builder().build().readTree(in);
        }
    }

    /**
     * 적재 덤프를 읽는 테스트만 건너뛴다 — 합성 상품 축 테스트는 파일에 의존하지 않으므로
     * Docker 이미지 빌드에서도 그대로 돈다.
     */
    private static void requireShippedDump() {
        Assumptions.assumeTrue(products != null,
                "db/init 이 빌드 컨텍스트에 없다 (Docker 이미지 빌드) — 채점은 저장소 체크아웃 잡이 한다");
    }

    /** 골드가 전제한 적재 규모와 실덤프가 어긋나면 라벨 전체가 무효다 — 먼저 잠근다. */
    @Test
    void shippedDumpMatchesGoldAssumptions() {
        requireShippedDump();
        assertThat(products).hasSize(gold.get("products_expected_total").asInt());
        assertThat(idsOf(products)).contains(gold.get("unconstrained_product").asText());
    }

    /**
     * 프로필 × 상품 전건 교차에서 Precision/Recall/F1 을 산출하고 <b>완전 일치</b>를 요구한다.
     *
     * <p>결정적 순수 함수이므로 1.0 미만이면 그 자체가 버그다 — 확률적 산출물처럼 임계치를
     * 두지 않는다. 수치는 부록 1에 그대로 싣는다.
     */
    @Test
    void qualifyMatchesHandLabeledGold_withPerfectPrecisionAndRecall() {
        requireShippedDump();
        int truePositive = 0;
        int falsePositive = 0;
        int falseNegative = 0;
        int trueNegative = 0;
        List<String> mismatches = new ArrayList<>();

        for (JsonNode p : gold.get("profiles")) {
            Profile profile = new Profile(p.get("age").asInt(), 5000,
                    p.get("existingBusiness").asBoolean(),
                    p.get("industry").asText(), p.get("region").asText());
            Set<String> expectedExcluded = new LinkedHashSet<>();
            p.get("expectedExcluded").forEach(n -> expectedExcluded.add(n.asText()));

            Set<String> actualQualified = idsOf(EligibilityFilter.qualify(profile, products));
            for (FundingProduct product : products) {
                String id = idOf(product);
                boolean goldQualifies = !expectedExcluded.contains(id);
                boolean filterQualifies = actualQualified.contains(id);

                if (goldQualifies && filterQualifies) {
                    truePositive++;
                } else if (!goldQualifies && !filterQualifies) {
                    trueNegative++;
                } else if (filterQualifies) {
                    falsePositive++;
                    mismatches.add(p.get("id").asText() + "/" + id + " 골드=탈락 필터=통과");
                } else {
                    falseNegative++;
                    mismatches.add(p.get("id").asText() + "/" + id + " 골드=통과 필터=탈락");
                }
            }
        }

        double precision = (double) truePositive / (truePositive + falsePositive);
        double recall = (double) truePositive / (truePositive + falseNegative);
        double f1 = 2 * precision * recall / (precision + recall);

        System.out.printf(
                "[#77 자격 매칭 골드] 프로필 %d종 × 상품 %d건 = %d쌍 · "
                        + "TP %d · FP %d · FN %d · TN %d → P %.3f / R %.3f / F1 %.3f%n",
                gold.get("profiles").size(), products.size(),
                truePositive + falsePositive + falseNegative + trueNegative,
                truePositive, falsePositive, falseNegative, trueNegative, precision, recall, f1);

        assertThat(mismatches).isEmpty();
        assertThat(precision).isEqualTo(1.0);
        assertThat(recall).isEqualTo(1.0);
        assertThat(f1).isEqualTo(1.0);
    }

    /**
     * 실적재본에 없는 축은 합성 상품으로 증명한다 — 검수본에 예비창업자 한정 상품이 0건이라
     * {@code pre_startup_only} 는 위 교차만으로는 한 번도 실행되지 않는다.
     */
    @Test
    void preStartupOnlyAxis_provenWithSyntheticProduct() {
        FundingProduct preStartupOnly = new FundingProduct("합성 예비창업자 전용",
                new Eligibility(null, null, null, true),
                3000, 2.5, 60, null, "open", "2026-07-26", SRC);
        Profile preStartup = new Profile(32, 5000, false, "cafe", "서울 마포구");
        Profile existing = new Profile(32, 5000, true, "cafe", "서울 마포구");

        assertThat(EligibilityFilter.qualify(preStartup, List.of(preStartupOnly)))
                .containsExactly(preStartupOnly);
        assertThat(EligibilityFilter.qualify(existing, List.of(preStartupOnly))).isEmpty();
    }

    /**
     * 기존 사업자 한정 축 — 실적재본에 4건 있으므로 위 교차에서도 채점되지만, <b>방향</b>이
     * {@code pre_startup_only} 와 반대라는 것을 여기서 못 박는다 (#90 문제 2).
     *
     * <p>이 축이 없던 동안 예비창업 프로필의 적극 카드에 <b>대환대출</b>이 편성됐다. 상품명
     * 블랙리스트로 코드에 숨기는 대신 공고문 근거("보유한 대출"·"재창업")를 자격 축으로 옮겼다.
     */
    @Test
    void existingBusinessOnlyAxis_isOppositeOfPreStartupOnly() {
        FundingProduct refinancing = new FundingProduct("합성 대환 상품",
                new Eligibility(null, null, null, false, true),
                5000, 4.5, 120, null, "open", "2026-07-26", SRC);
        Profile preStartup = new Profile(32, 5000, false, "cafe", "서울 마포구");
        Profile existing = new Profile(32, 5000, true, "cafe", "서울 마포구");

        assertThat(EligibilityFilter.qualify(preStartup, List.of(refinancing))).isEmpty();
        assertThat(EligibilityFilter.qualify(existing, List.of(refinancing)))
                .containsExactly(refinancing);
    }

    /** 두 축이 동시에 true 이면 아무도 통과하지 못한다 — 데이터 모순을 조용히 넘기지 않는다. */
    @Test
    void bothStageAxes_excludeEveryone() {
        FundingProduct contradictory = new FundingProduct("합성 모순 상품",
                new Eligibility(null, null, null, true, true),
                3000, 2.5, 60, null, "open", "2026-07-26", SRC);

        assertThat(EligibilityFilter.qualify(
                new Profile(32, 5000, false, "cafe", "서울 마포구"), List.of(contradictory)))
                .isEmpty();
        assertThat(EligibilityFilter.qualify(
                new Profile(32, 5000, true, "cafe", "서울 마포구"), List.of(contradictory)))
                .isEmpty();
    }

    /** 업종 축도 실적재에는 제조업 1건뿐이라, 카페/음식점 경계는 합성 상품으로 증명한다. */
    @Test
    void industryAxis_provenWithSyntheticProducts() {
        FundingProduct cafeOnly = new FundingProduct("합성 카페 전용",
                new Eligibility(null, Set.of("cafe"), null, false),
                3000, 2.5, 60, null, "open", "2026-07-26", SRC);
        Profile cafe = new Profile(32, 5000, false, "cafe", "서울 마포구");
        Profile food = new Profile(32, 5000, false, "food", "서울 마포구");

        assertThat(EligibilityFilter.qualify(cafe, List.of(cafeOnly))).containsExactly(cafeOnly);
        assertThat(EligibilityFilter.qualify(food, List.of(cafeOnly))).isEmpty();
    }

    // ── 덤프 파싱 ────────────────────────────────────────────────────────────
    private static List<FundingProduct> parseShippedProducts() throws IOException {
        String sql = Files.readString(REPO_ROOT.resolve("db/init/20_finance.sql"));
        List<FundingProduct> parsed = new ArrayList<>();
        Matcher m = PRODUCT_ROW.matcher(sql);
        while (m.find()) {
            parsed.add(new FundingProduct(
                    m.group("id") + " " + m.group("name"),
                    new Eligibility(
                            "NULL".equals(m.group("maxAge")) ? null : Integer.valueOf(m.group("maxAge")),
                            textArray(m.group("industries")),
                            textArray(m.group("regions")),
                            "TRUE".equals(m.group("preStartup")),
                            "TRUE".equals(m.group("existingOnly"))),
                    0, 0.0, 60, null, "open", "2026-07-26", SRC));
        }
        return parsed;
    }

    /** {@code ARRAY['서울']} → {"서울"}. NULL·빈 배열은 무제약이라 null. */
    private static Set<String> textArray(String literal) {
        if ("NULL".equals(literal)) {
            return null;
        }
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("'((?:[^']|'')*)'").matcher(literal);
        while (m.find()) {
            out.add(m.group(1).replace("''", "'"));
        }
        return out.isEmpty() ? null : out;
    }

    /** 파싱 시 name 앞에 product_id 를 붙여 두었다 — 그 접두를 다시 꺼낸다. */
    private static String idOf(FundingProduct product) {
        return product.name().substring(0, product.name().indexOf(' '));
    }

    private static Set<String> idsOf(List<FundingProduct> list) {
        Set<String> ids = new LinkedHashSet<>();
        list.forEach(p -> ids.add(idOf(p)));
        return ids;
    }
}

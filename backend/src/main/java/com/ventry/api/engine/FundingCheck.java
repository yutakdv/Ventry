package com.ventry.api.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * BE-04 — 조달 검증 (순수 함수, exploration spec §2-2).
 * 경계까지의 부족분 gap 을 덮을 재원이 제약 하에 존재하는지 판정한다.
 * <b>커버 불가 경계는 보고에서 제외된다</b> — "돈이 더 있으면 좋다"는 인사이트가 아니기 때문이다.
 *
 * <p>제약 순서:
 * ① <b>잔여 한도 원칙</b> — 확정(B₀에 이미 쓰인) 상품의 미사용 한도 우선 → 자격 통과·open 미포함 상품
 * ② <b>중복수혜 제약</b> — 동일 {@code exclusiveGroup} 은 1개만 (null=제약 없음)
 * ③ <b>커버 완전성</b> — 조달 합계 ≥ gap
 * ④ <b>상환 여력 상한</b> — m ≤ {@code monthlyInvestable} (assumptions #23)
 *
 * <p>자격 정규칙(나이·업종·지역·예비창업자)은 {@link EligibilityFilter}가 앞단에서 거른다.
 * 담보 요구 여부는 상품 스키마에 필드가 없어 판정에 반영하지 않는다 (assumptions #24).
 */
public final class FundingCheck {

    private static final String STATUS_OPEN = "open";

    private FundingCheck() {}

    /**
     * gap 커버 가능 여부를 판정한다.
     *
     * @param gap            부족분(만원). 0 이하면 상향 경계가 없다는 뜻이라 empty
     * @param qualified      자격 통과 상품 (EligibilityFilter 결과)
     * @param usedByProduct  상품명 → B₀에서 이미 사용한 금액(만원). 미사용 상품은 항목 없음
     * @param input          조달 조건(월 상환 여력 등)
     * @return 커버 성공 시 조달 명세, 실패 시 empty (= 그 경계는 보고 제외)
     */
    public static Optional<FundingPlan> cover(int gap, List<FundingProduct> qualified,
                                              Map<String, Integer> usedByProduct, FundingInput input) {
        if (gap <= 0) {
            return Optional.empty();
        }
        List<FundingProduct> ordered = qualified.stream()
                .filter(p -> STATUS_OPEN.equals(p.status()))
                .filter(p -> remaining(p, usedByProduct) > 0)
                .sorted(allocationOrder(usedByProduct))
                .toList();

        List<FundingPlan.Allocation> picked = new ArrayList<>();
        Set<String> claimedGroups = new HashSet<>();
        int total = 0;
        for (FundingProduct product : ordered) {
            if (total >= gap) {
                break;
            }
            String group = product.exclusiveGroup();
            if (group != null && !claimedGroups.add(group)) {
                continue;   // 동일 그룹 중복수혜 제약
            }
            int take = Math.min(remaining(product, usedByProduct), gap - total);
            boolean termAssumed = product.termMonths() == null;
            int term = termAssumed ? Loan.DEFAULT_TERM_MONTHS : product.termMonths();
            picked.add(new FundingPlan.Allocation(product, take, term, termAssumed));
            total += take;
        }
        if (total < gap) {
            return Optional.empty();   // 커버 완전성 미달 → 경계 제외
        }

        double monthlyPayment = picked.stream()
                .mapToDouble(a -> Loan.monthlyPayment(a.amount(), a.product().rate(), a.termMonths()))
                .sum();
        if (input.monthlyInvestable() != null && monthlyPayment > input.monthlyInvestable()) {
            return Optional.empty();   // 상환 여력 초과 → 경계 제외
        }
        return Optional.of(new FundingPlan(picked, total, monthlyPayment));
    }

    /** 확정 상품(이미 사용액이 있는 것)을 먼저, 그다음 잔여 한도가 큰 순 (expl §2-2 ①). */
    private static Comparator<FundingProduct> allocationOrder(Map<String, Integer> usedByProduct) {
        return Comparator
                .comparingInt((FundingProduct p) -> usedByProduct.getOrDefault(p.name(), 0) > 0 ? 0 : 1)
                .thenComparing(Comparator.comparingInt(
                        (FundingProduct p) -> remaining(p, usedByProduct)).reversed());
    }

    /** 미사용 한도 = 상품 한도 − B₀에서 이미 쓴 금액. */
    private static int remaining(FundingProduct product, Map<String, Integer> usedByProduct) {
        return product.amountMax() - usedByProduct.getOrDefault(product.name(), 0);
    }
}

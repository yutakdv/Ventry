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
 * ① <b>확정 이율</b> — {@code rate} 가 null인 상품은 m을 계산할 수 없어 제외 (assumptions #28)
 * ② <b>잔여 한도 원칙</b> — 확정(B₀에 이미 쓰인) 상품의 미사용 한도 우선 → 자격 통과·open 미포함 상품
 * ③ <b>중복수혜 제약</b> — 동일 {@code exclusiveGroup} 은 1개만 (null=제약 없음)
 * ④ <b>커버 완전성</b> — 조달 합계 ≥ gap
 * ⑤ <b>상환 여력 상한</b> — m ≤ {@code monthlyInvestable} (assumptions #23)
 *
 * <p>자격 정규칙(나이·업종·지역·예비창업자)은 {@link EligibilityFilter}가 앞단에서 거른다.
 * 담보 요구 여부는 상품 스키마에 필드가 없어 판정에 반영하지 않는다 (assumptions #24).
 */
public final class FundingCheck {

    private static final String STATUS_OPEN = "open";

    private FundingCheck() {}

    /**
     * gap 커버 가능 여부를 판정한다. 시그니처·동작은 BE-04와 동일하며
     * {@link #coverWithReason}에 위임한다 — 사유가 필요 없는 호출부를 위한 형태다.
     *
     * @param gap            부족분(만원). 0 이하면 상향 경계가 없다는 뜻이라 empty
     * @param qualified      자격 통과 상품 (EligibilityFilter 결과)
     * @param usedByProduct  상품명 → B₀에서 이미 사용한 금액(만원). 미사용 상품은 항목 없음
     * @param input          조달 조건(월 상환 여력 등)
     * @return 커버 성공 시 조달 명세, 실패 시 empty (= 그 경계는 보고 제외)
     */
    public static Optional<FundingPlan> cover(int gap, List<FundingProduct> qualified,
                                              Map<String, Integer> usedByProduct, FundingInput input) {
        return coverWithReason(gap, qualified, usedByProduct, input).plan();
    }

    /**
     * gap 커버 판정 + <b>실패 사유</b>. 사유는 "왜 이 경계를 보고하지 않았는가"를 남기기 위한 것으로,
     * 0건 보고 문장과 서버 로그의 재료다 (expl §2-4).
     *
     * <p>확정 이율이 없는 상품({@code rate == null})은 <b>가정 금리를 만들지 않고</b> 후보에서 빼며,
     * 그 제외가 커버 실패의 원인이면 {@link CoverOutcome.Reason#RATE_UNKNOWN} 으로 구분해 알린다
     * (단순 한도 부족인 {@link CoverOutcome.Reason#LIMIT_SHORT} 와 원인이 다르다).
     */
    public static CoverOutcome coverWithReason(int gap, List<FundingProduct> qualified,
                                               Map<String, Integer> usedByProduct,
                                               FundingInput input) {
        if (gap <= 0) {
            return CoverOutcome.excluded(CoverOutcome.Reason.NO_BOUNDARY);
        }
        List<FundingProduct> available = qualified.stream()
                .filter(p -> STATUS_OPEN.equals(p.status()))
                .filter(p -> remaining(p, usedByProduct) > 0)
                .toList();
        List<FundingProduct> ordered = available.stream()
                .filter(FundingProduct::hasKnownRate)   // 확정 이율만 — m을 지어내지 않는다 (#28)
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
            return CoverOutcome.excluded(shortfallReason(gap, available, usedByProduct));
        }

        double monthlyPayment = 0.0;
        for (FundingPlan.Allocation allocation : picked) {
            Double rate = allocation.product().rate();
            if (rate == null) {
                // 위 필터상 도달 불가 — 필터가 무력화되면 가정 금리로 새는 대신 여기서 막는다
                return CoverOutcome.excluded(CoverOutcome.Reason.RATE_UNKNOWN);
            }
            monthlyPayment += Loan.monthlyPayment(allocation.amount(), rate, allocation.termMonths());
        }
        if (input.monthlyInvestable() != null && monthlyPayment > input.monthlyInvestable()) {
            return CoverOutcome.excluded(CoverOutcome.Reason.REPAYMENT_OVER);
        }
        return CoverOutcome.covered(new FundingPlan(picked, total, monthlyPayment));
    }

    /**
     * 한도 미달의 원인 구분: 확정 이율 미상 상품의 한도까지 더하면 gap을 넘었을 경우
     * 실패 원인은 "한도 부족"이 아니라 "금리 미상으로 제외"다.
     */
    private static CoverOutcome.Reason shortfallReason(int gap, List<FundingProduct> available,
                                                       Map<String, Integer> usedByProduct) {
        int withUnknownRate = available.stream()
                .mapToInt(p -> remaining(p, usedByProduct))
                .sum();
        return withUnknownRate >= gap
                ? CoverOutcome.Reason.RATE_UNKNOWN
                : CoverOutcome.Reason.LIMIT_SHORT;
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

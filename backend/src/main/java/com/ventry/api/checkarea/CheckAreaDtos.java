package com.ventry.api.checkarea;

import com.ventry.api.common.FinanceDtos.Product;
import com.ventry.api.common.FinanceDtos.RiskReview;
import com.ventry.api.common.Verdict;
import java.util.List;

/** POST /api/check-area/{sid} DTO (계약 6번). 판정 4단계 + 부족분 + 자격 부합 상품. */
public final class CheckAreaDtos {

    private CheckAreaDtos() {}

    public record CheckAreaRequest(String areaCode) {}

    public record CheckAreaResponse(Verdict verdict, int gapAmount,
                                    List<Product> matchingProducts, RiskReview riskReview) {}
}

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
 */
public record Eligibility(Integer maxAge, Set<String> industries, Set<String> regions,
                          boolean preStartupOnly) {}

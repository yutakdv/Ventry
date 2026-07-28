package com.ventry.api.engine;

/**
 * 신청자 프로필 (진단 parsed_profile 유래). 자격 판정에 쓰는 정규칙 필드만 담는다.
 * capital·금액은 만원 단위 정수 (계약 공통 규약).
 *
 * @param age 만 나이. <b>미기재는 {@code null}</b> 이며 0으로 강등하지 않는다 — 0은 「만 39세
 *            이하」 같은 상한 조건을 통과해 버려서, 나이를 적지 않은 사용자가 조용히 청년 전용
 *            상품을 받았다 (이슈 #155 ①). null 의 처리는 {@link EligibilityFilter} 가 정한다.
 * @param existingBusiness 기존 사업자·재창업자 여부. true면 예비창업자 한정 상품에서 제외된다.
 *                         담보 여부·월 투자 가능액은 자격이 아닌 조달 검증 입력이라 BE-04 소관.
 */
public record Profile(Integer age, int capital, boolean existingBusiness,
                      String industry, String region) {}

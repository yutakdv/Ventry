package com.ventry.api.engine;

/**
 * 신청자 프로필 (진단 parsed_profile 유래). 자격 판정에 쓰는 정규칙 필드만 담는다.
 * capital·금액은 만원 단위 정수 (계약 공통 규약).
 */
public record Profile(int age, int capital, String industry, String region) {}

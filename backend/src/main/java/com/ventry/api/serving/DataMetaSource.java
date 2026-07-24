package com.ventry.api.serving;

/**
 * BE-02 — 데이터 기준일(data_as_of) 공급원. 픽스처({@link DemoDataMeta})와
 * 메타 테이블 조회({@link DbDataMeta})를 프로파일로 교체한다 (CandidateSource 와 동일 패턴).
 * recommend 최상위 data_as_of 는 상권 지표 대표로 {@code asOf("sales")} 를 쓴다.
 */
public interface DataMetaSource {

    /** source(예: "sales", "rent", "premium")별 화면 표기 기준일 문자열. */
    String asOf(String source);
}

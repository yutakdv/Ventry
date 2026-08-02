package com.ventry.api.common;

/**
 * BE-01 목 데이터의 잔여 — <b>상수 2개</b>만 남았다.
 *
 * <p>목 시나리오 카드({@code scenarios()})와 그 상품 상수는 삭제했다. {@code ScenarioBuilder} 가
 * 실상품으로 카드를 만든 뒤로 호출부가 없었고, 死코드는 "아직 목을 쓰는 경로가 있나"라는
 * 오해를 남긴다 (BE 리뷰 D-21). 결정공간 탐색 목은 BE-05 실계산으로 이미 교체됐다.
 */
public final class MockData {

    /** 픽스처 프로파일의 데이터 기준일. DB 프로파일은 {@code data_source_meta} 를 읽는다. */
    public static final String DATA_AS_OF = "2026-Q1";

    /**
     * 고지 문구의 <b>정본</b> (PROJECT_RULES §2).
     *
     * <p>현재 응답은 {@code disclaimer: true} 플래그만 싣고 문구는 FE 가 표기한다. 그래서 이
     * 상수는 서빙 경로에서 참조되지 않지만, <b>문구가 바뀔 때 어디를 고쳐야 하는지</b>를 남기기
     * 위해 유지한다 — 서버가 문구까지 통제할지는 팀 결정 사항이다.
     */
    public static final String DISCLAIMER =
            "본 정보는 공개 자료 기반 정보 제공이며 대출 권유·중개·자문이 아닙니다. "
                    + "실제 한도·금리·승인 여부는 해당 기관의 심사에 따릅니다.";

    private MockData() {}
}

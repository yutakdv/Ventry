package com.ventry.api.serving;

import java.util.List;
import java.util.Optional;

/**
 * BE-02 — 후보 상권 공급원. 픽스처({@link DemoCandidates})와 DB 조회({@link DbCandidateSource})를
 * 프로파일로 교체한다. LocationService 는 이 인터페이스에만 의존하며 구현체 선택은 프로파일이 결정한다.
 */
public interface CandidateSource {

    /** 추천(화면 3)·프리뷰(화면 2) 후보 풀 — 업종별 전량을 한 번에 조회한다 (탐색당 쿼리 1회). */
    List<CandidateArea> findCandidates(String industry);

    /** 역방향 판정(임의 클릭)용 단건 조회 — 뷰 그레인이 area_code×industry 라 업종으로 좁힌다. */
    Optional<CandidateArea> find(String industry, String areaCode);
}

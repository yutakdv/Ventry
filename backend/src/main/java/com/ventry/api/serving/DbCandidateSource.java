package com.ventry.api.serving;

import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * BE-02 — DB(v_candidate_area) 기반 후보 공급원. spring.profiles.active=db 에서만 활성화된다.
 * 단건 조회는 업종 후보를 한 번 조회해 인메모리에서 매칭한다 — checkArea 요청당 쿼리 1회.
 */
@Component
@Profile("db")
public class DbCandidateSource implements CandidateSource {

    private final CandidateRepository repository;

    public DbCandidateSource(CandidateRepository repository) {
        this.repository = repository;
    }

    // 이슈 D (2026-07-27 해소): 「캐시 키에 자치구 추가」는 후보 조회가 지역으로 좁혀질 것을
    // 전제한 메모였다. region_hint 의 사정거리는 **지역 한정 상품의 자격 판정 전용**으로 확정됐고
    // (#136 · docs/심사_QA.md), CandidateRepository 의 조건은 업종뿐이다. 조회 축이 하나이므로
    // 키(업종)는 이미 완전하다 — 자치구를 넣으면 같은 결과를 자치구 수만큼 중복 적재하게 된다.
    // TODO(BE-04): 정렬 비용 배열 사전 정렬 보관 (프론티어 결선 최적화 — 후보 리스트 캐싱까지가 오늘 범위)
    //
    // 캐시(@Cacheable)는 CandidateRepository.findCandidates 에 있다. 여기에 걸면 아래 find 가
    // **프록시를 우회**해 같은 리스트를 매번 다시 읽는다 (리포지토리 주석 참조).
    @Override
    public List<CandidateArea> findCandidates(String industry) {
        return repository.findCandidates(industry);
    }

    /** 두 메서드가 같은 캐시 항목을 공유한다 — 단건 조회도 조회는 업종 1회다. */
    @Override
    public Optional<CandidateArea> find(String industry, String areaCode) {
        return repository.findCandidates(industry).stream()
                .filter(c -> c.areaCode().equals(areaCode))
                .findFirst();
    }
}

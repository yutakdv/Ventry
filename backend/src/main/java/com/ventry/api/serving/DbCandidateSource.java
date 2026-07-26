package com.ventry.api.serving;

import java.util.List;
import java.util.Optional;
import org.springframework.cache.annotation.Cacheable;
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

    // TODO(BE-02→이슈D): 캐시 키에 자치구(sigungu) 추가 — findCandidates 에 자치구 파라미터 도입 후
    // TODO(BE-04): 정렬 비용 배열 사전 정렬 보관 (프론티어 결선 최적화 — 후보 리스트 캐싱까지가 오늘 범위)
    // condition: 캐시 키가 null 이면 Spring 이 IllegalArgumentException 을 던져 요청 전체가 500이
    // 된다. 입력 검증(D-09)이 앞단에서 막지만, 캐시 계층이 **입력 오류를 500으로 증폭**하지
    // 않도록 여기서도 잠근다 — 방어 지점이 둘이어야 한 곳이 뚫려도 규격이 유지된다.
    @Override
    @Cacheable(cacheNames = "candidates", key = "#industry", condition = "#industry != null")
    public List<CandidateArea> findCandidates(String industry) {
        return repository.findCandidates(industry);
    }

    @Override
    public Optional<CandidateArea> find(String industry, String areaCode) {
        return repository.findCandidates(industry).stream()
                .filter(c -> c.areaCode().equals(areaCode))
                .findFirst();
    }
}

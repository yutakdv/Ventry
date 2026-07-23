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

    @Override
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

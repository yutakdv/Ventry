package com.ventry.api.serving;

import java.util.List;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * BE-02 — 기동 시 후보 캐시 예열 (db 프로파일 전용). 지원 업종을 미리 조회해
 * {@code @Cacheable("candidates")} 를 채워, 첫 사용자 요청의 조회 지연을 없앤다.
 */
@Component
@Profile("db")
public class CandidateWarmup {

    /** 예열 대상 업종 (v_candidate_area.industry 도메인). */
    private static final List<String> INDUSTRIES = List.of("cafe", "food");

    private final CandidateSource candidates;

    public CandidateWarmup(CandidateSource candidates) {
        this.candidates = candidates;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        // 다른 빈 경유 호출이라 CandidateRepository 의 @Cacheable 프록시가 동작한다
        INDUSTRIES.forEach(candidates::findCandidates);
    }
}

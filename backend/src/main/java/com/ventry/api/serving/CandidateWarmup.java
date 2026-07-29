package com.ventry.api.serving;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * BE-02 — 기동 시 후보 캐시 예열 (db 프로파일 전용). 지원 업종을 미리 조회해
 * {@code @Cacheable("candidates")} 를 채워, 첫 사용자 요청의 조회 지연을 없앤다.
 *
 * <p><b>예열은 최적화이지 기동 조건이 아니다</b> (BE 리뷰 2026-07-29 C-03). 이 리스너가 예외를
 * 던지면 {@code ApplicationReadyEvent} 발행이 실패로 처리돼 <b>JVM 이 종료 코드 1로 죽는다</b> —
 * 실측: DB 미가용 상태에서 "Started VentryApiApplication" 로그가 찍힌 <b>직후</b> 컨테이너가
 * 4초 만에 종료했다. compose 의 {@code depends_on: service_healthy} 는 최초 기동만 보장하므로,
 * DB 가 잠깐 흔들리는 순간에 재기동이 겹치면 API 컨테이너가 그대로 사라진다(재시작 정책 없음).
 *
 * <p>그래서 실패를 삼키고 경고만 남긴다. 캐시는 첫 요청 때 지연 적재되고, DB 가 돌아오면
 * 서비스도 스스로 돌아온다 — 예열이 안 된 대가는 첫 요청 한 번의 조회 지연뿐이다.
 */
@Component
@Profile("db")
public class CandidateWarmup {

    private static final Logger log = LoggerFactory.getLogger(CandidateWarmup.class);

    /** 예열 대상 업종 (v_candidate_area.industry 도메인). */
    private static final List<String> INDUSTRIES = List.of("cafe", "food");

    private final CandidateSource candidates;

    public CandidateWarmup(CandidateSource candidates) {
        this.candidates = candidates;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        try {
            // 다른 빈 경유 호출이라 CandidateRepository 의 @Cacheable 프록시가 동작한다
            INDUSTRIES.forEach(candidates::findCandidates);
        } catch (RuntimeException e) {
            log.warn("후보 캐시 예열 실패 — 첫 요청에서 지연 적재된다 (사유: {})", e.toString());
        }
    }
}

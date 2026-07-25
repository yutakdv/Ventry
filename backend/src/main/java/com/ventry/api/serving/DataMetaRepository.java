package com.ventry.api.serving;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * BE-02 — data_source_meta 조회 리포지토리 (read-only). 화면 표기 기준일의 단일 원천 (스펙 §0-4).
 */
@Repository
public class DataMetaRepository {

    private static final String SELECT_AS_OF =
            "SELECT as_of FROM data_source_meta WHERE source = ?";

    private final JdbcClient jdbcClient;

    public DataMetaRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * source 의 기준일 문자열. <b>없으면 예외 대신 {@code "미상"}</b> 을 돌려준다.
     *
     * <p>기준일 한 줄이 비었다고 화면 전체를 500으로 떨어뜨릴 이유가 없다 — 실제로
     * {@code data_source_meta} 에 {@code finance_product} 행이 없어 시나리오 화면이 통째로
     * 죽는 것을 실데이터 결선에서 확인했다(이슈 #87). 데이터는 배치에서 채우되, 서빙은
     * 한 행의 부재로 무너지지 않아야 한다.
     */
    public String asOf(String source) {
        return jdbcClient.sql(SELECT_AS_OF).param(source).query(String.class)
                .optional().orElse(UNKNOWN_AS_OF);
    }

    /** 기준일 미상 표기 — 화면에 빈 값 대신 명시적으로 노출한다 (기준일 상시 표기 원칙). */
    static final String UNKNOWN_AS_OF = "미상";
}

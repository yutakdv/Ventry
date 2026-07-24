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

    /** source 의 기준일 문자열을 조회한다. */
    public String asOf(String source) {
        return jdbcClient.sql(SELECT_AS_OF).param(source).query(String.class).single();
    }
}

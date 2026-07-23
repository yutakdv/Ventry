package com.ventry.api.serving;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * BE-02 — DB 기준일 공급원 (db 프로파일). data_source_meta 테이블에서 source별 기준일을 읽는다.
 */
@Component
@Profile("db")
public class DbDataMeta implements DataMetaSource {

    private final DataMetaRepository repository;

    public DbDataMeta(DataMetaRepository repository) {
        this.repository = repository;
    }

    @Override
    public String asOf(String source) {
        return repository.asOf(source);
    }
}

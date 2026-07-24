package com.ventry.api.serving;

import com.ventry.api.common.MockData;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * BE-02 — 픽스처 기준일 공급원 (default 프로파일). 기존 값("2026-Q1")을 그대로 유지한다.
 * source 구분 없이 데모 상수 하나를 돌려준다 — db 프로파일에서 {@link DbDataMeta} 로 교체된다.
 */
@Component
@Profile("!db")
public class DemoDataMeta implements DataMetaSource {

    @Override
    public String asOf(String source) {
        return MockData.DATA_AS_OF;
    }
}

package com.ventry.api.common;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 오류 응답 공통 포맷: HTTP 4xx/5xx + {error:{code,message}} (계약 §시스템 계약). */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e) {
        return ResponseEntity.status(e.status())
                .body(Map.of("error", Map.of("code", e.code(), "message", e.getMessage())));
    }

    /**
     * 사용자에게는 내부 사정을 노출하지 않되, <b>서버 로그에는 스택을 남긴다</b>.
     * 남기지 않으면 통합 QA(BE-07)나 실데이터 전환에서 500의 원인을 추적할 방법이 사라진다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", Map.of("code", "INTERNAL_ERROR",
                        "message", "서버 내부 오류가 발생했습니다")));
    }
}

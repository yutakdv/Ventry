package com.ventry.api.common;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 오류 응답 공통 포맷: HTTP 4xx/5xx + {error:{code,message}} (계약 §시스템 계약). */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e) {
        return ResponseEntity.status(e.status())
                .body(Map.of("error", Map.of("code", e.code(), "message", e.getMessage())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", Map.of("code", "INTERNAL_ERROR",
                        "message", "서버 내부 오류가 발생했습니다")));
    }
}

package com.ventry.api.common;

import org.springframework.http.HttpStatus;

/** 공통 오류 — GlobalExceptionHandler가 {error:{code,message}} 포맷으로 변환한다. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException sessionNotFound(String sessionId) {
        return new ApiException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND",
                "세션을 찾을 수 없습니다: " + sessionId);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}

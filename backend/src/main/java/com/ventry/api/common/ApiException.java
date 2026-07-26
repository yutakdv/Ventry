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

    public static ApiException areaNotFound(String areaCode) {
        return new ApiException(HttpStatus.NOT_FOUND, "AREA_NOT_FOUND",
                "상권을 찾을 수 없습니다: " + areaCode);
    }

    /**
     * 입력 규격 위반 (400). <b>세션을 만들기 전에</b> 던져야 한다 — 진단이 200으로 통과하면
     * 그 세션은 이후 모든 엔드포인트에서 500을 낸다 (BE 리뷰 D-09).
     */
    public static ApiException invalidRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}

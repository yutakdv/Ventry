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

    /**
     * 세션 저장소 상한 도달 (429). 인증 없는 진단 호출이 인메모리 맵을 무한히 키우는 것을
     * 막는 마지막 관문이다 — 상한을 넘긴 요청 하나가 실패하는 편이 힙이 무너지는 것보다 낫다
     * (BE 리뷰 2026-07-29 C-02).
     */
    public static ApiException tooManySessions() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_SESSIONS",
                "동시 세션 상한에 도달했습니다. 잠시 후 다시 시도해 주세요.");
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}

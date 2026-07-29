package com.ventry.api.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
     * 읽을 수 없는 요청 본문은 <b>400</b>이다 — 서버 결함이 아니라 클라이언트 입력 문제다 (BE-07).
     *
     * <p>기본 동작은 이것을 {@link Exception} 핸들러로 흘려보내 500 + {@code INTERNAL_ERROR} 로
     * 만들었다. 타입이 틀린 필드 하나(예: {@code "age": "서른둘"})에 서버 내부 오류라고 답하면
     * 프론트는 재시도할지 입력을 고칠지 판단할 수 없고, 심사위원이 API를 찔러 보는 경로에서도
     * 없는 장애로 보인다.
     *
     * <p>메시지에 Jackson 예외 원문을 싣지 않는다 — 내부 타입명·클래스 경로가 그대로 새어 나간다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(
            HttpMessageNotReadableException e) {
        log.warn("요청 본문 해석 실패: {}", e.getMostSpecificCause().getMessage());
        return badRequest("요청 본문을 해석할 수 없습니다");
    }

    /**
     * <b>쿼리 파라미터가 규격을 벗어난 경우도 400이다</b> (BE 리뷰 2026-07-29 C-01).
     *
     * <p>{@code /api/recommend/{sid}?v=abc} 는 {@code long} 바인딩에서 터지는데, 아래
     * {@link #handleUnexpected} 가 그것을 <b>500 + INTERNAL_ERROR</b> 로 만들고 있었다(실측).
     * 본문 타입 오류는 이미 400으로 고쳤으면서 쿼리 타입 오류만 500으로 남아 있으면, 같은
     * 성격의 입력 오류에 프론트가 서로 다른 분기를 갖게 된다 — 재시도 여부 판단도 갈린다.
     *
     * <p>메시지에는 <b>파라미터 이름만</b> 싣는다. {@code getMessage()} 원문에는 대상 타입의
     * 클래스 경로가 그대로 들어 있어 내부 구조가 새어 나간다.
     */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<Map<String, Object>> handleBadParameter(Exception e) {
        String name = e instanceof MethodArgumentTypeMismatchException mismatch
                ? mismatch.getName()
                : ((MissingServletRequestParameterException) e).getParameterName();
        log.warn("요청 파라미터 규격 위반: {}", name);
        return badRequest("요청 파라미터가 올바르지 않습니다: " + name);
    }

    /**
     * 없는 경로는 <b>404</b>다 — 서버가 죽은 것이 아니다.
     *
     * <p>{@code @ExceptionHandler(Exception.class)} 는 스프링이 기본 제공하는 상태코드 변환
     * ({@code DefaultHandlerExceptionResolver})보다 <b>먼저</b> 평가되므로, catch-all 을 두는
     * 순간 프레임워크가 4xx 로 답하던 것까지 전부 500이 된다. 오타 한 번에 「서버 내부 오류」가
     * 나가고, 그때마다 로그에 ERROR 스택이 쌓여 진짜 장애를 덮는다.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException e) {
        log.warn("존재하지 않는 경로: {}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error("NOT_FOUND", "요청한 경로를 찾을 수 없습니다"));
    }

    /** 허용되지 않은 메서드는 405 — {@code Allow} 헤더로 무엇이 되는지 알린다. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException e) {
        log.warn("허용되지 않은 메서드: {}", e.getMethod());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (e.getSupportedHttpMethods() != null) {
            response.allow(e.getSupportedHttpMethods().toArray(new HttpMethod[0]));
        }
        return response.body(error("METHOD_NOT_ALLOWED", "허용되지 않은 메서드입니다"));
    }

    /** 본문 타입 불일치는 415 — 400과 구분해야 클라이언트가 헤더를 고칠 수 있다. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException e) {
        log.warn("지원하지 않는 Content-Type: {}", e.getContentType());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(error("UNSUPPORTED_MEDIA_TYPE",
                        "요청 본문은 application/json 이어야 합니다"));
    }

    /**
     * 사용자에게는 내부 사정을 노출하지 않되, <b>서버 로그에는 스택을 남긴다</b>.
     * 남기지 않으면 통합 QA(BE-07)나 실데이터 전환에서 500의 원인을 추적할 방법이 사라진다.
     *
     * <p>어느 요청에서 났는지를 함께 남긴다 — 메서드·경로 없이 스택만 있으면 재현 경로를
     * 되짚을 수 없다. 쿼리 문자열은 싣지 않는다(세션 id 가 경로에 있고 로그 노출면을 넓힌다).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e,
                                                                HttpServletRequest request) {
        log.error("처리되지 않은 예외 [{} {}]", request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error("INTERNAL_ERROR", "서버 내부 오류가 발생했습니다"));
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(error("INVALID_REQUEST", message));
    }

    private static Map<String, Object> error(String code, String message) {
        return Map.of("error", Map.of("code", code, "message", message));
    }
}

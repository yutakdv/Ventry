package com.ventry.api.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청 본문 크기 상한 (이슈 #171 ①).
 *
 * <p>설정 한 줄로 끝나지 않는다 — Tomcat 의 {@code maxPostSize}·
 * {@code max-http-form-post-size} 는 {@code application/x-www-form-urlencoded} 에만 걸리고
 * {@code application/json} 에는 적용되지 않는다. 실제로 2만 자 / 200만 자 / 2천만 자 본문이
 * 전부 {@code 200 OK} 로 통과했다.
 *
 * <p><b>파급은 크지 않았다.</b> {@code free_text} 는 키워드 {@code contains()} 매칭에만 쓰이고
 * 세션에도 LLM 프롬프트에도 들어가지 않아 토큰·로그 폭증 통로가 없다. 남는 것은 요청 메모리뿐이라
 * 이 필터의 목적은 방어 자체이지 특정 공격의 차단이 아니다 — 그래서 상한도 넉넉하게 잡는다.
 *
 * <p>{@code Content-Length} 만 본다. 청크 전송(길이 미상)까지 막으려면 입력 스트림을 감싸
 * 세어야 하는데, 본 서비스의 클라이언트는 전부 길이를 싣고 그 방어는 여기서 얻을 것에 비해
 * 크다 — 넣지 않는 쪽을 택했다.
 *
 * <p>필터는 {@code DispatcherServlet} 바깥이라 {@link GlobalExceptionHandler} 가 닿지 않는다.
 * 그래서 오류 본문을 직접 쓰되 <b>같은 {@code {error:{code,message}}} 포맷</b>을 지킨다.
 */
@Component
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    /**
     * 상한 256 KB. 정상 요청은 이 근처에도 오지 않는다 — 가장 큰 요청 본문이
     * {@code POST /budget} 의 {@code composition} 이고 실측 200 바이트 미만이다.
     * {@code free_text} 가 아무리 길어도(진단 폼 자유 입력) 한 화면 분량은 KB 단위다.
     */
    static final long MAX_BODY_BYTES = 256L * 1024;

    private static final String TOO_LARGE_BODY = """
            {"error":{"code":"PAYLOAD_TOO_LARGE",\
            "message":"요청 본문이 너무 큽니다 (상한 %d바이트)."}}""".formatted(MAX_BODY_BYTES);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());   // 413
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            // 연결을 끊는다 — 상한을 넘긴 본문을 끝까지 읽어 주는 것 자체가 이 필터가 막으려는 일이다.
            response.setHeader(HttpHeaders.CONNECTION, "close");
            response.getWriter().write(TOO_LARGE_BODY);
            return;
        }
        chain.doFilter(request, response);
    }
}

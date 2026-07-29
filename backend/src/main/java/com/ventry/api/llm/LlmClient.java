package com.ventry.api.llm;

import java.time.Duration;
import java.util.Optional;

/**
 * BE-04 — LLM 클라이언트 골격 (실제 호출 구현은 BE-05).
 *
 * <p><b>LLM은 필수 경로에 없다.</b> 호출부는 결과가 비었는지만 보고 템플릿 문장을 최종본으로 쓴다 —
 * 키 부재·타임아웃·동시 호출 초과·장애가 전부 {@code Optional.empty()} 라는 <b>하나의 폴백 경로</b>로
 * 수렴하므로, 호출부에 "LLM 사용 가능한가?" 분기를 두지 않는다 (expl §2-5 템플릿 우선, 데모 무중단).
 *
 * <p>LLM은 언어화·탐색 계획·검증 반박문만 담당하며 <b>수치를 생성·재계산하지 않는다</b>
 * (불변 원칙 §0-1). 프롬프트에는 "입력 JSON에 없는 수치·상품 언급 금지" 제약이 걸린다.
 */
public interface LlmClient {

    /**
     * 프롬프트에 대한 응답. <b>비어 있으면 호출부는 템플릿 폴백</b>을 최종본으로 사용한다.
     * 이 메서드는 예외를 던지지 않는다 — 모든 실패는 empty로 표현된다.
     */
    Optional<String> complete(String prompt);

    /**
     * 호출 지점별 상한을 지정하는 변형. <b>기본 상한(5초)은 SSE 경로 기준</b>이라, 톰캣 워커
     * 스레드에서 동기로 기다리는 경로가 그대로 쓰면 슬라이더 한 번이 최대 5초 멈춘다
     * (스펙 §7 「&lt;100ms 재계산 체감」과 정면 충돌 — BE 리뷰 M-01).
     *
     * <p>상한을 넘기면 지금과 <b>동일한 폴백</b>이 일어난다({@code Optional.empty()} → 템플릿이
     * 최종본). 새 실패 모드가 생기지 않으므로 상한만 짧게 주는 것으로 닫힌다.
     * 기본 구현은 상한을 무시한다 — 무LLM 구현·테스트 스텁은 애초에 기다리지 않는다.
     */
    default Optional<String> complete(String prompt, Duration timeout) {
        return complete(prompt);
    }

    /** 실제 LLM 호출이 가능한 상태인지(키 존재 등). 무LLM 모드에서는 false. */
    boolean enabled();
}

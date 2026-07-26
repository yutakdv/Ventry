/**
 * 모션 축소 요청 여부 (FE-06).
 *
 * CSS 쪽은 `global.css`의 미디어 쿼리가 전역으로 끄지만, **JS가 직접 부르는 애니메이션**은
 * 그 규칙이 닿지 않는다 — `scrollIntoView({ behavior: 'smooth' })`가 대표적이다.
 * 그래서 호출 지점에서 이 함수로 한 번 확인한다.
 */
export function prefersReducedMotion(): boolean {
  return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
}

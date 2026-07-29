/**
 * 세션 소실로 첫 화면에 되돌려 보낼 때 실어 보내는 라우터 state (FE 리뷰 M-13).
 *
 * 세션은 메모리 전용이라(`store/session.tsx`) **새로고침·뒤로가기 한 번이면 통째로 사라진다.**
 * 각 화면의 가드가 `/diagnose` 로 되돌리는 동작 자체는 정상 종료지만, 사용자에게는 아무 설명이
 * 없어 방금 본 결과가 왜 사라졌는지 알 수 없었다 — 심사 중 실수로 F5 를 누른 상황이 그 자리다.
 *
 * **데이터를 남기지 않는 쪽을 택했다.** `sessionStorage` 영속화는 개인 자금 정보가 브라우저에
 * 남는 문제라 저장 항목·만료·개인정보 문구를 함께 손봐야 하고, 화면 1의 「입력한 정보는 서버에
 * 저장하지 않습니다」 문구와의 관계도 다시 정리해야 한다. 마감을 고려해 **안내만** 붙인다.
 */
export const SESSION_LOST_STATE = { reason: 'session-lost' } as const

export function isSessionLost(state: unknown): boolean {
  return (
    typeof state === 'object' &&
    state !== null &&
    (state as { reason?: unknown }).reason === 'session-lost'
  )
}

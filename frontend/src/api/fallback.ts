/**
 * 목 폴백 발생 여부 (FE-06).
 *
 * `client.ts`는 실제 호출이 실패하면 조용히 목으로 넘어간다 — 데모가 끊기지 않게 하려는 설계다.
 * 문제는 **화면이 그 사실을 말하지 않는다**는 점이었다. 목 응답에도 "한국부동산원 ○○상권 분기
 * 평균", "데이터 기준일 2026-Q1" 같은 출처·기준일 표기가 그대로 붙으므로, 서버가 죽은 채로
 * 심사가 진행되면 **지어낸 수치를 실데이터로 믿고 보게 된다.** 출처를 사칭하는 셈이라
 * 「데이터 기준일·추정치 라벨 상시 표기」(CLAUDE.md 불변 원칙 4)와 정면으로 어긋난다.
 *
 * 그래서 폴백이 한 번이라도 일어나면 표시를 세우고, 화면 상단에 고지 한 줄을 띄운다.
 * 무중단은 그대로 유지하면서 사칭만 막는 방법이다.
 *
 * **되돌리지 않는다.** 뒤 호출이 성공해도 이미 화면에 목 수치가 섞여 있을 수 있어,
 * 표시를 지우면 그 화면이 다시 실데이터인 것처럼 보인다.
 *
 * `VITE_USE_MOCK=1`(개발자가 의도적으로 켠 목 모드)에서는 세우지 않는다 — 그건 장애가 아니다.
 */
let active = false
/**
 * 폴백이 **몇 번** 일어났는가. `active` 는 한 번 켜지면 되돌지 않으므로(위 설계) 그것만으로는
 * "다시 시도했더니 또 실패했다"를 구분할 수 없다 — 재시도 경로가 사용자에게 결과를 말해 주려면
 * 새 실패가 있었는지가 필요하다. 값은 단조 증가하며 되돌지 않는다.
 */
let count = 0
const listeners = new Set<() => void>()

export function markApiFallback(): void {
  count += 1
  if (active) {
    listeners.forEach((l) => l())
    return
  }
  active = true
  listeners.forEach((l) => l())
}

export function isApiFallback(): boolean {
  return active
}

export function apiFallbackCount(): number {
  return count
}

export function subscribeApiFallback(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

import { useSyncExternalStore } from 'react'
import { apiFallbackCount, isApiFallback, subscribeApiFallback } from '../api/fallback'

/** 목 폴백이 한 번이라도 일어났는지. 서버 상태를 구독하는 것이 아니라 이 세션의 사실을 읽는다. */
export function useApiFallback(): boolean {
  return useSyncExternalStore(subscribeApiFallback, isApiFallback, () => false)
}

/** 폴백 누적 횟수 — 재시도 후 **새 실패가 있었는지**를 판별하는 데 쓴다. */
export function useApiFallbackCount(): number {
  return useSyncExternalStore(subscribeApiFallback, apiFallbackCount, () => 0)
}

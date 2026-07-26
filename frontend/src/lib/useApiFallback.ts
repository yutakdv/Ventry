import { useSyncExternalStore } from 'react'
import { isApiFallback, subscribeApiFallback } from '../api/fallback'

/** 목 폴백이 한 번이라도 일어났는지. 서버 상태를 구독하는 것이 아니라 이 세션의 사실을 읽는다. */
export function useApiFallback(): boolean {
  return useSyncExternalStore(subscribeApiFallback, isApiFallback, () => false)
}

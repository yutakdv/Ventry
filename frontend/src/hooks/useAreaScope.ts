import { useEffect, useState } from 'react'
import { loadAreaScope, type AreaScope } from '../lib/areaScope'

/**
 * 경계 데이터를 첫 페인트 **이후에** 받아 온다 (가정 #96).
 *
 * 872KB(gzip 약 202KB)를 마운트 즉시 받으면 마커·목록 렌더와 대역을 다툰다. 경계는
 * 상권을 선택해야 쓰이므로 급하지 않다 — `requestIdleCallback` 으로 미뤄, 아직 안 왔으면
 * 경계와 근거 문장만 잠깐 없다가 도착하면 나타난다.
 */
export function useAreaScope(): AreaScope | null {
  const [scope, setScope] = useState<AreaScope | null>(null)

  useEffect(() => {
    let alive = true
    const start = () => {
      loadAreaScope().then((s) => {
        if (alive) setScope(s)
      })
    }
    // Safari 는 requestIdleCallback 이 없다 — 폴백은 다음 틱으로 미루는 정도면 충분하다.
    // (타입 선언상으로는 항상 존재하므로 truthiness 가 아니라 typeof 로 가른다.)
    const hasIdle = typeof window.requestIdleCallback === 'function'
    const handle = hasIdle
      ? window.requestIdleCallback(start, { timeout: 2000 })
      : window.setTimeout(start, 0)
    return () => {
      alive = false
      if (hasIdle) window.cancelIdleCallback(handle)
      else window.clearTimeout(handle)
    }
  }, [])

  return scope
}

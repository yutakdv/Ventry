import { useEffect, useState } from 'react'

export type KakaoStatus = 'loading' | 'ready' | 'error'

/**
 * 카카오맵 SDK 준비 상태.
 * index.html이 `autoload=false`로 스크립트를 넣으므로 `kakao.maps.load()`를 한 번 호출해야 한다.
 * 앱키가 없거나(치환 실패) 도메인이 등록되지 않으면 스크립트가 로드되지 않아 `window.kakao`가 없다 —
 * 이 경우 지도를 포기하고 목록만 보여준다(지도 없이도 화면이 죽지 않아야 한다).
 */
export function useKakaoLoader(): KakaoStatus {
  const [status, setStatus] = useState<KakaoStatus>(() =>
    typeof window !== 'undefined' && window.kakao?.maps ? 'loading' : 'error',
  )

  useEffect(() => {
    const sdk = window.kakao
    if (!sdk?.maps) {
      console.warn('[kakao] SDK를 찾을 수 없습니다 — 앱키(VITE_KAKAO_APP_KEY)·도메인 등록을 확인하세요.')
      setStatus('error')
      return
    }
    let cancelled = false
    sdk.maps.load(() => {
      if (!cancelled) setStatus('ready')
    })
    return () => {
      cancelled = true
    }
  }, [])

  return status
}

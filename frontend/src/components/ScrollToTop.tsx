import { useEffect } from 'react'
import { useLocation } from 'react-router-dom'
import { prefersReducedMotion } from '../lib/motion'

/**
 * 경로가 바뀌면 스크롤을 맨 위로 되돌린다.
 *
 * React Router는 화면을 갈아 끼울 뿐 스크롤 위치를 건드리지 않는다. 그래서 랜딩 맨 아래
 * CTA를 누르면 **진단 화면이 하단부터 보이는** 상태가 됐다. 화면마다 각자 처리하면 반드시
 * 빠지는 곳이 생기므로 라우터 안에 하나만 둔다.
 *
 * `search`는 의존성에서 뺀다 — `?demo=1` 같은 쿼리 변화로 스크롤이 튀면 안 된다.
 */
export default function ScrollToTop() {
  const { pathname } = useLocation()

  useEffect(() => {
    window.scrollTo({ top: 0, behavior: prefersReducedMotion() ? 'auto' : 'instant' })
  }, [pathname])

  return null
}

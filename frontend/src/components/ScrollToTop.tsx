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
 *
 * `hash`는 반대로 **반드시 본다**. GNB의 "서비스 소개"·"데이터 출처"가 라우터 링크가 된 뒤로는
 * 브라우저의 프래그먼트 이동이 일어나지 않으므로, 여기서 대상 섹션까지 데려다주지 않으면 랜딩
 * 최상단만 보인다. 대상이 없으면(다른 화면의 해시) 기존대로 맨 위로 되돌린다.
 */
export default function ScrollToTop() {
  const { pathname, hash } = useLocation()

  useEffect(() => {
    const reduced = prefersReducedMotion()
    if (hash) {
      const target = document.getElementById(hash.slice(1))
      if (target) {
        target.scrollIntoView({ behavior: reduced ? 'auto' : 'smooth', block: 'start' })
        return
      }
    }
    window.scrollTo({ top: 0, behavior: reduced ? 'auto' : 'instant' })
  }, [pathname, hash])

  return null
}

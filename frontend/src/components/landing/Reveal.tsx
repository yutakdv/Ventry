import { useEffect, useRef, useState, type ReactNode } from 'react'
import { prefersReducedMotion } from '../../lib/motion'
import styles from './Reveal.module.css'

/**
 * 스크롤 리빌 — 뷰포트에 들어오면 한 번만 올라오며 나타난다.
 *
 * 라이브러리를 쓰지 않는 이유는 이 동작이 `IntersectionObserver` + CSS transition 두 줄이기
 * 때문이다. 랜딩 하나 때문에 애니메이션 런타임을 번들에 얹으면 서비스 화면의 로딩까지 느려진다.
 *
 * **모션 축소 요청이면 즉시 보이게 한다.** 애니메이션을 끄는 것으로 끝내면 요소가 투명한 채로
 * 남아 내용이 아예 보이지 않는 사고가 난다 — 끄는 쪽이 기본값이어야 한다.
 */
export default function Reveal({
  children,
  delay = 0,
  as: Tag = 'div',
  className = '',
}: {
  children: ReactNode
  /** 같은 줄의 카드들을 순차로 띄울 때의 지연(ms). 200ms를 넘기면 느리다는 인상이 된다. */
  delay?: number
  as?: 'div' | 'section' | 'li'
  className?: string
}) {
  const ref = useRef<HTMLElement>(null)
  const [shown, setShown] = useState(() => prefersReducedMotion())

  useEffect(() => {
    if (shown || !ref.current) return
    const el = ref.current
    const io = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) {
          setShown(true)
          io.disconnect()
        }
      },
      // 요소가 조금 보이기 시작할 때 = 아래에서 12% 올라온 시점
      { rootMargin: '0px 0px -12% 0px', threshold: 0.05 },
    )
    io.observe(el)
    return () => io.disconnect()
  }, [shown])

  const Component = Tag as 'div'
  return (
    <Component
      ref={ref as React.Ref<HTMLDivElement>}
      className={`${styles.reveal} ${shown ? styles.shown : ''} ${className}`}
      style={delay ? { transitionDelay: `${delay}ms` } : undefined}
    >
      {children}
    </Component>
  )
}

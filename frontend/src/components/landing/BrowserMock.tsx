import type { ReactNode } from 'react'
import styles from './BrowserMock.module.css'

/**
 * 브라우저 크롬을 두른 화면 캡처.
 *
 * 캡처를 맨몸으로 올리면 "이미지를 붙여 둔" 인상이 남는다. 크롬 바가 있으면 같은 그림이
 * "제품 안에서 도는 화면"으로 읽힌다 — 랜딩에서 캡처를 쓰는 이유가 그것이다.
 *
 * `children`은 캡처 위에 얹는 오버레이(수치 카드 등) 자리다.
 */
export default function BrowserMock({
  src,
  alt,
  width,
  height,
  url = 'ventry.app',
  children,
  className = '',
}: {
  src: string
  alt: string
  width: number
  height: number
  url?: string
  children?: ReactNode
  className?: string
}) {
  return (
    <div className={`${styles.frame} ${className}`}>
      <div className={styles.bar} aria-hidden>
        <span className={styles.dots}>
          <span className={styles.dot} />
          <span className={styles.dot} />
          <span className={styles.dot} />
        </span>
        <span className={`t-caption ${styles.url}`}>{url}</span>
      </div>
      <img className={styles.shot} src={src} alt={alt} width={width} height={height} />
      {children}
    </div>
  )
}

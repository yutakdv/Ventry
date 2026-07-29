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
  priority = false,
}: {
  src: string
  alt: string
  width: number
  height: number
  url?: string
  children?: ReactNode
  className?: string
  /**
   * 첫 화면(히어로)인가.
   *
   * 캡처 두 장이 합쳐 812KB 인데 전부 즉시 받고 있었다. 아래 쇼케이스는 스크롤해야 보이는
   * 자리라 히어로와 대역을 다툴 이유가 없다 — 기본을 지연 로드로 두고, 첫 화면만 명시적으로
   * 우선한다. 폭·높이는 이미 고정돼 있어 지연 로드로 레이아웃이 흔들리지 않는다.
   */
  priority?: boolean
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
      <img
        className={styles.shot}
        src={src}
        alt={alt}
        width={width}
        height={height}
        loading={priority ? 'eager' : 'lazy'}
        decoding={priority ? 'sync' : 'async'}
        fetchPriority={priority ? 'high' : 'low'}
      />
      {children}
    </div>
  )
}

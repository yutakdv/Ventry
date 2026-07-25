import type { ReactNode } from 'react'
import styles from './InfoBanner.module.css'

type Tone = 'info' | 'tip'

/** 안내 배너 — Figma Info Banner (info=blue/bg-info, tip=yellow/bg-tip). 앞에 색 점. */
export default function InfoBanner({ tone = 'info', children }: { tone?: Tone; children: ReactNode }) {
  return (
    <div className={`${styles.banner} ${styles[tone]}`}>
      <span className={styles.dot} aria-hidden />
      <span className={`t-body ${styles.text}`}>{children}</span>
    </div>
  )
}

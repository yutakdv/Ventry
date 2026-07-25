import type { ReactNode } from 'react'
import styles from './Field.module.css'

/** 라벨 + 컨트롤 래퍼 — Figma Form Field. error 있으면 하단에 빨간 메시지. */
export default function Field({
  label,
  htmlFor,
  error,
  children,
}: {
  label: string
  htmlFor?: string
  error?: string
  children: ReactNode
}) {
  return (
    <div className={styles.field}>
      <label className={`t-label ${styles.label}`} htmlFor={htmlFor}>
        {label}
      </label>
      {children}
      {error && <span className={`t-caption ${styles.error}`}>{error}</span>}
    </div>
  )
}

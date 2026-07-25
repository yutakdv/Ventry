import type { ReactNode } from 'react'
import { AlertCircle } from 'lucide-react'
import styles from './Alert.module.css'

/** 에러 요약 Alert — 폼 상단 미입력 안내. (현재 error 톤만 사용) */
export default function Alert({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className={styles.alert} role="alert">
      <AlertCircle size={18} className={styles.icon} aria-hidden />
      <div className={styles.body}>
        <p className={`t-body-strong ${styles.title}`}>{title}</p>
        {children && <p className={`t-caption ${styles.desc}`}>{children}</p>}
      </div>
    </div>
  )
}

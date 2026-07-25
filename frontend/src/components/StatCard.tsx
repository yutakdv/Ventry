import type { LucideIcon } from 'lucide-react'
import styles from './StatCard.module.css'

export type StatTone = 'blue' | 'purple' | 'green' | 'orange'

/** 요약 통계 카드 — Figma "Stat Card"(높이 88·라운딩 12·아이콘 40). 전 화면 요약 바 공용. */
export default function StatCard({
  icon: Icon,
  label,
  value,
  caption,
  tone = 'blue',
}: {
  icon: LucideIcon
  label: string
  value: string
  caption?: string
  tone?: StatTone
}) {
  return (
    <div className={styles.card}>
      <span className={`${styles.iconWrap} ${styles[tone]}`}>
        <Icon size={18} aria-hidden />
      </span>
      <span className={styles.texts}>
        <span className={`t-caption ${styles.label}`}>{label}</span>
        <span className={`t-stat ${styles.value}`}>{value}</span>
        {caption && <span className={`t-caption ${styles.caption}`}>{caption}</span>}
      </span>
    </div>
  )
}

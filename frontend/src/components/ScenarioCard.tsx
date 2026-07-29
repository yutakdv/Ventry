import type { LucideIcon } from 'lucide-react'
import styles from './ScenarioCard.module.css'

/** 시나리오 선택 카드 — 보수/적극 탭. Figma "tab-보수적/적극적" + 아이콘 개선. */
export default function ScenarioCard({
  icon: Icon,
  title,
  description,
  active,
  disabled = false,
  onClick,
}: {
  icon: LucideIcon
  title: string
  description: string
  active: boolean
  /** 아직 스트림에 도착하지 않은 시나리오 — 눌러도 반응이 없는 카드를 그렇게 보이게 한다. */
  disabled?: boolean
  onClick: () => void
}) {
  return (
    <button
      type="button"
      className={`${styles.card} ${active ? styles.active : ''}`}
      aria-pressed={active}
      disabled={disabled}
      onClick={onClick}
    >
      <span className={styles.radio} aria-hidden />
      <Icon size={20} className={styles.icon} aria-hidden />
      <span className={styles.texts}>
        <span className={`t-body-strong ${styles.title}`}>{title}</span>
        <span className={`t-caption ${styles.desc}`}>{description}</span>
      </span>
    </button>
  )
}

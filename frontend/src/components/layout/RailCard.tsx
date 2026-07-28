import Button from '../Button'
import styles from './RailCard.module.css'

interface Props {
  title: string
  items: string[]
  marker?: 'check' | 'dot'
  tone?: 'default' | 'brand'
  actionLabel?: string
  /**
   * 버튼을 눌렀을 때 할 일. **없으면 버튼을 그리지 않는다** — 눌러도 아무 일이 없는 버튼은
   * 심사위원이 반드시 누르는 자리라, 라벨만 있고 동작이 없는 상태를 타입으로 막는다.
   */
  onAction?: () => void
}

/** 우측 레일 안내 카드 — 제목 + 체크/점 리스트 + (선택) 하단 버튼. */
export default function RailCard({
  title,
  items,
  marker = 'check',
  tone = 'default',
  actionLabel,
  onAction,
}: Props) {
  return (
    <section className={`${styles.card} ${tone === 'brand' ? styles.brand : ''}`}>
      <h3 className={`t-body-strong ${styles.title}`}>{title}</h3>
      <ul className={styles.list}>
        {items.map((it, i) => (
          <li key={i} className={styles.item}>
            <span
              className={`${styles.marker} ${marker === 'check' ? styles.check : styles.dot}`}
              aria-hidden
            >
              {marker === 'check' ? '✓' : '·'}
            </span>
            <span className={`t-caption ${styles.itemText}`}>{it}</span>
          </li>
        ))}
      </ul>
      {actionLabel && onAction && (
        <Button variant="secondary" size="sm" fullWidth className={styles.action} onClick={onAction}>
          {actionLabel}
        </Button>
      )}
    </section>
  )
}

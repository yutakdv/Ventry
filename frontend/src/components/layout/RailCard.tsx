import Button from '../Button'
import styles from './RailCard.module.css'

interface Props {
  title: string
  items: string[]
  marker?: 'check' | 'dot'
  tone?: 'default' | 'brand'
  actionLabel?: string
}

/** 우측 레일 안내 카드 — 제목 + 체크/점 리스트 + (선택) 하단 버튼. */
export default function RailCard({ title, items, marker = 'check', tone = 'default', actionLabel }: Props) {
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
      {actionLabel && (
        <Button variant="secondary" size="sm" fullWidth className={styles.action}>
          {actionLabel}
        </Button>
      )}
    </section>
  )
}

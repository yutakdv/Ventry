import type { Verdict } from '../api/types'
import { VERDICT_LABEL } from '../lib/verdict'
import styles from './VerdictBadge.module.css'

/** 판정 뱃지 — Figma Verdict Badge. 문구는 VERDICT_LABEL에서만 가져온다. */
export default function VerdictBadge({ verdict }: { verdict: Verdict }) {
  return (
    <span className={`t-caption ${styles.badge} ${styles[verdict]}`}>
      <span className={styles.dot} aria-hidden />
      {VERDICT_LABEL[verdict]}
    </span>
  )
}

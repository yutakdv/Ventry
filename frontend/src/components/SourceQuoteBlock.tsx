import type { SourceQuote } from '../api/types'
import styles from './SourceQuoteBlock.module.css'

/**
 * 공고 원문 인용 블록 (스펙 §5-4).
 * `text`는 벡터DB 원문 청크를 **그대로** 출력한다 — 요약·재작성 금지.
 * RAG 구현 전에는 서버가 `source_quote: null`을 주므로 호출부에서 렌더하지 않는다.
 */
export default function SourceQuoteBlock({ quote }: { quote: SourceQuote }) {
  const attribution = [quote.org, quote.doc, quote.date].filter(Boolean).join(' · ')

  return (
    <blockquote className={styles.block}>
      <span className={styles.bar} aria-hidden />
      <span className={styles.texts}>
        <span className={`t-caption ${styles.text}`}>&ldquo;{quote.text}&rdquo;</span>
        {attribution && <span className={`t-caption ${styles.attribution}`}>— {attribution}</span>}
      </span>
    </blockquote>
  )
}

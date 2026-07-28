import type { SourceQuote } from '../api/types'
import styles from './SourceQuoteBlock.module.css'

/**
 * 공고 원문 인용 블록 (스펙 §5-4).
 *
 * `text`는 `finance_doc_chunk` 원문 청크를 **그대로** 출력한다 — 유사도 검색·벡터DB는 쓰지 않고
 * `doc_chunk_ref` id 직접 조회다(DECISIONS §7). 요약·재작성은 물론 길이 자르기도 서버가
 * 하지 않는다. 그래서 실측 길이가 19~1,863자로 편차가 크고, 화면이 4줄 클램프로 통제하며
 * 전문은 `sourceUrl`(공고 원문)로 넘긴다 (docs/HANDOFF_FRONTEND.md #8).
 *
 * 원문에 같은 어절이 반복되는 구간이 보일 수 있다(PDF가 굵은 글씨를 겹쳐 인쇄한 흔적).
 * 화면에서 고치지 않는다 — 고치면 원문 대조가 깨진다.
 */
export default function SourceQuoteBlock({
  quote,
  sourceUrl,
}: {
  quote: SourceQuote
  sourceUrl?: string
}) {
  const attribution = [quote.org, quote.doc, quote.date].filter(Boolean).join(' · ')

  return (
    <blockquote className={styles.block}>
      <span className={styles.bar} aria-hidden />
      <span className={styles.texts}>
        <span className={`t-caption ${styles.text}`}>&ldquo;{quote.text}&rdquo;</span>
        <span className={styles.meta}>
          {attribution && <span className={`t-caption ${styles.attribution}`}>— {attribution}</span>}
          {sourceUrl && (
            <a
              className={`t-caption ${styles.sourceLink}`}
              href={sourceUrl}
              target="_blank"
              rel="noreferrer noopener"
            >
              원문 보기 ↗
            </a>
          )}
        </span>
      </span>
    </blockquote>
  )
}

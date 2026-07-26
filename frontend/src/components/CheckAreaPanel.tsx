import { useState } from 'react'
import VerdictBadge from './VerdictBadge'
import SourceQuoteBlock from './SourceQuoteBlock'
import RiskReviewPanel from './RiskReviewPanel'
import { formatAmount, formatRate, formatRateNote } from '../lib/format'
import { VERDICT_LABEL } from '../lib/verdict'
import type { CheckAreaResponse } from '../api/types'
import styles from './CheckAreaPanel.module.css'

/** 처음에 펼쳐 보이는 상품 수 — 실데이터는 25건까지 온다. */
const VISIBLE = 3

/**
 * 역방향 판정 결과 — `POST /api/check-area` (계약 6번).
 *
 * 표기 규칙:
 *  - `matching_products`는 서버가 `amount_max` 내림차순으로 고정 정렬한다. **재정렬 금지** —
 *    `rate`가 생략된 변동금리 상품의 순위를 프론트가 정하면 사실상 순위 조작이 된다.
 *  - `gap_amount: 0`(예산 내)일 때도 상품이 온다. "부족분 상품"이 아니라 "자격 요건 부합 상품"이므로
 *    부족분 0일 때의 문구를 따로 낸다.
 *  - "조달 가능"이라고 쓰지 않는다 — 한도·승인은 기관 심사 사항 (CLAUDE.md 용어 컴플라이언스).
 */
export default function CheckAreaPanel({
  areaName,
  result,
  loading,
}: {
  areaName: string
  result: CheckAreaResponse | null
  loading: boolean
}) {
  const [expanded, setExpanded] = useState(false)

  if (loading) {
    return <p className={`t-body ${styles.loading}`}>판정을 확인하는 중…</p>
  }
  if (!result) return null

  const products = expanded ? result.matching_products : result.matching_products.slice(0, VISIBLE)
  const rest = result.matching_products.length - products.length

  return (
    <section className={styles.panel} aria-label={`${areaName} 역방향 판정`}>
      {/* 상권명은 감싸는 모달 제목이 갖는다 — 여기서는 판정과 부족분만 낸다. */}
      <div className={styles.head}>
        <VerdictBadge verdict={result.verdict} />
        <span className={`t-caption ${styles.gap}`}>
          {result.gap_amount > 0 ? (
            <>
              부족분 <span className={styles.gapStrong}>{formatAmount(result.gap_amount)}</span>
            </>
          ) : (
            '확정 예산 내'
          )}
        </span>
      </div>

      <p className={`t-caption ${styles.note}`}>
        자격 요건 부합 상품 {result.matching_products.length}건이 확인됩니다. 한도·승인은 기관 심사
        사항입니다.
      </p>

      <ul className={styles.products}>
        {products.map((p) => {
          const rate = formatRate(p)
          const rateNote = formatRateNote(p)
          return (
            <li key={p.name} className={styles.product}>
              <span className={`t-body-strong ${styles.productName}`}>{p.name}</span>
              <span className={`t-caption ${styles.productMeta}`}>
                최대 {p.amount_max.toLocaleString('ko-KR')}만원
                {rate && ` · ${rate}`}
                {` · ${p.source.org} · ${p.data_as_of} 기준`}
              </span>
              {rateNote && <span className={`t-caption ${styles.rateNote}`}>{rateNote}</span>}
              {p.source_quote && <SourceQuoteBlock quote={p.source_quote} sourceUrl={p.source.url} />}
            </li>
          )
        })}
      </ul>

      {rest > 0 && (
        <button type="button" className={`t-caption ${styles.more}`} onClick={() => setExpanded(true)}>
          자격 부합 상품 {rest}건 더 보기
        </button>
      )}

      {/*
        상시 노출에서 클릭 전개로 바꿨다 — 스펙 §5-3은 반박 왕복을 "왜?" 클릭 시 전개로 규정하고,
        같은 성격의 내용을 추천 화면과 이 모달이 다른 방식으로 보여줄 이유가 없다.
      */}
      <RiskReviewPanel
        review={result.risk_review}
        claim={
          result.gap_amount > 0
            ? `${areaName}을 ${VERDICT_LABEL[result.verdict]}으로 판정했고, 확정 예산 대비 부족분은 ${formatAmount(result.gap_amount)}입니다.`
            : `${areaName}을 ${VERDICT_LABEL[result.verdict]}으로 판정했고, 확정 예산 내입니다.`
        }
        label={areaName}
      />
    </section>
  )
}

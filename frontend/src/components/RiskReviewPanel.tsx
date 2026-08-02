import { useEffect, useId, useRef, useState } from 'react'
import { ChevronDown } from 'lucide-react'
import type { RiskReview } from '../api/types'
import styles from './RiskReviewPanel.module.css'

/**
 * 리스크 검증 "왜?" 패널 (스펙 §5-3 · §0-8, FE-05).
 *
 * **기본은 카운트 배지 하나뿐이고 왕복은 클릭해야 열린다.** 스펙이 금지하는 것은 "사고 과정이
 * 상시 흘러가는 쇼"이므로, 존재는 배지로 고지하되 내용은 접어 둔다.
 *
 * 왕복은 계약의 세 필드만으로 구성한다 — 서버에 왕복 로그 엔드포인트가 따로 없고, D3 동결 후
 * 계약 변경 절차를 밟아야 해 새 필드를 요구하지 않았다. [1]단은 이미 화면에 있는 결정적
 * 계산 결과를 호출부가 문장으로 넘기고, [2]단이 `objection_text`, [3]단이 `applied`다.
 *
 * ⚠️ `skipped=true`여도 `objection_text`는 비어 있지 않다 — 서버가 템플릿 문장을 최종본으로
 * 실어 보낸다(#96, 계약 §4 주석). 그래서 **문장을 숨기지 않고 출처 라벨만 분기**한다.
 * LLM이 만든 반박과 템플릿 문장을 화면이 같은 것처럼 말하면, #96이 고친 「수행하지 않은 검증을
 * 수행했다고 보고하는」 문제를 프론트에서 되살리는 셈이 된다.
 */
export default function RiskReviewPanel({
  review,
  claim,
  label = '추천 결과',
}: {
  review: RiskReview
  /**
   * [1]단 = 반박의 대상이 된 주장 1줄. **호출부가 실제 응답 값으로 구성한다** —
   * 이 컴포넌트가 문장을 지어내면 화면이 수치를 만드는 통로가 된다 (§0-1).
   */
  claim: string
  /** 접근성 라벨 접두 — 추천 결과 전체인지, 특정 상권 판정인지 구분한다. */
  label?: string
}) {
  const [open, setOpen] = useState(false)
  const panelId = useId()
  const panelRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (panelRef.current) panelRef.current.inert = !open
  }, [open])

  // 문장이 없으면 왕복 자체가 없다 — 빈 패널을 여는 배지는 두지 않는다.
  if (!review.objection_text) return null

  const verified = !review.skipped && review.applied

  return (
    <section className={styles.panel} aria-label={`${label} 리스크 검증`}>
      <button
        type="button"
        className={`t-label ${styles.trigger}`}
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((o) => !o)}
      >
        왜?
        <span className={`t-caption ${styles.count}`}>
          {verified ? '· 검증 의견 1건 반영' : '· 리스크 검증 생략'}
        </span>
        <ChevronDown
          size={14}
          className={`${styles.chevron} ${open ? styles.chevronOpen : ''}`}
          aria-hidden
        />
      </button>

      <div id={panelId} ref={panelRef} className={`${styles.body} ${open ? styles.bodyOpen : ''}`}>
        <div className={styles.bodyInner}>
          <ol className={styles.rounds}>
            <li className={styles.round}>
              <span className={`t-label ${styles.step}`}>1. 추천 판정</span>
              <p className={`t-caption ${styles.text}`}>{claim}</p>
              <span className={`t-caption ${styles.origin}`}>결정적 계산</span>
            </li>

            <li className={styles.round}>
              <span className={`t-label ${styles.step}`}>2. 반박</span>
              <p className={`t-caption ${styles.text}`}>{review.objection_text}</p>
              <span className={`t-caption ${styles.origin}`}>
                {verified ? '리스크 검증 에이전트 (1왕복)' : '기본 문안 (템플릿)'}
              </span>
            </li>

            <li className={styles.round}>
              <span className={`t-label ${styles.step}`}>3. 반영</span>
              <p className={`t-caption ${styles.text}`}>
                {verified
                  ? '반박을 근거에 병기했습니다. 판정 4단계는 결정적 계산 결과이므로 반박으로 바뀌지 않습니다.'
                  : '리스크 검증 에이전트의 응답이 반영되지 않아 기본 문안을 표시합니다. 판정 4단계는 결정적 계산 결과 그대로입니다.'}
              </p>
            </li>
          </ol>

          <p className={`t-caption ${styles.note}`}>
            리스크 검증은 추천의 반대 논리를 세우는 1왕복 고정 절차입니다. 반박문에는 입력된 계산
            결과 밖의 수치가 들어갈 수 없습니다 (스펙 §5-3).
          </p>
        </div>
      </div>
    </section>
  )
}

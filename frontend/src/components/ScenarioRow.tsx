import { useEffect, useId, useRef, useState } from 'react'
import { Check, ChevronDown, ShieldCheck, Star, TrendingUp } from 'lucide-react'
import Button from './Button'
import SourceQuoteBlock from './SourceQuoteBlock'
import { formatAmount, formatRate, formatRateNote } from '../lib/format'
import type { ExploreInsightEvent } from '../api/types'
import styles from './ScenarioRow.module.css'

/** 고정 고지 문구 (expl §7) — 모든 T1 하단에 반드시 동반된다. */
const DISCLAIMER =
  '본 정보는 공개 자료 기반 정보 제공이며 대출 권유·중개·자문이 아닙니다. 실제 한도·금리·승인 여부는 해당 기관의 심사에 따릅니다.'

/**
 * 인사이트 타입 → 화면 라벨.
 * expl §3의 타입명을 그대로 쓴다. 자금 관련 "추천/권장" 술어는 쓰지 않는다
 * (CLAUDE.md 용어 컴플라이언스 — 정보 서술형만).
 */
const TYPE_LABEL: Record<string, string> = {
  T1: '기회 경계 (T1)',
  T2: '안전 마진 (T2)',
  T3: '지역 대안 (T3)',
  T4: '업종 대안 (T4)',
  T5: '무권리 조건부 (T5)',
}

function typeIcon(type: string) {
  if (type === 'T2') return ShieldCheck
  return TrendingUp
}

/** 만원 정수 → "+2,770만원" (증분 표기용, 0이면 부호 없음) */
function signedAmount(manwon: number): string {
  return manwon > 0 ? `+${formatAmount(manwon)}` : formatAmount(manwon)
}

export interface ScenarioRowProps {
  insight: ExploreInsightEvent
  /** 임팩트 최대 1건만 강조 — 시각 위계는 주되 "추천" 문구는 쓰지 않는다. */
  highlight?: boolean
  /** 지금 이 시나리오가 적용 중인가 */
  applied?: boolean
  /** 이 시나리오 예산을 적용(=POST /budget 재호출) */
  onApply: () => void
  /** **이 행**이 적용 중인가 — 문구를 바꾼다 */
  applying?: boolean
  /** 어느 행이든 적용 중인가 — 중복 적용만 막고 문구는 바꾸지 않는다 */
  busy?: boolean
}

/**
 * 탐색 시나리오 1행 — Figma "Scenario Row".
 *
 * 표기 규칙(하드 룰):
 *  - T1은 **진입/지속을 반드시 병기**한다 (서비스 철학의 핵심 — 차입은 진입을 늘리지만
 *    상환 부담이 지속을 줄인다는 비단조성을 화면에서 지운 순간 서사가 사라진다).
 *  - `marginal_payment`가 없으면 서버가 준 `marginal_payment_note`를 그 자리에 넣는다.
 *    프론트가 월 상환액을 만들어내지 않는다 (§0-1).
 *  - 고지 문구는 상시 노출한다.
 */
export default function ScenarioRow({
  insight,
  highlight = false,
  applied = false,
  onApply,
  applying = false,
  busy = false,
}: ScenarioRowProps) {
  const [open, setOpen] = useState(false)
  const panelId = useId()
  const panelRef = useRef<HTMLDivElement>(null)
  const Icon = highlight ? Star : typeIcon(insight.type)

  useEffect(() => {
    if (panelRef.current) panelRef.current.inert = !open
  }, [open])

  const { delta } = insight
  const isSafety = insight.type === 'T2'
  const entryDiff = delta.n_entry_after - delta.n_entry_before

  /*
   * T2(안전 마진)는 before == after로 오고 `gap_amount`도 실리지 않는다.
   * 하한 예산이 실려 온 경우에만 금액을 쓰고, 없으면 "추가 조달 없음"으로 낸다 —
   * 없는 수치를 프론트가 만들지 않는다 (§0-1).
   */
  const budgetLabel = isSafety ? '안전 마진' : '현재 예산 대비'
  const budgetValue = isSafety
    ? insight.gap_amount != null
      ? `${formatAmount(insight.gap_amount)}까지 하향 가능`
      : '추가 조달 없음'
    : insight.gap_amount != null
      ? `${signedAmount(insight.gap_amount)} 확보`
      : null // 갭이 없는 타입(T5 등)은 줄 자체를 비운다 — "—"를 금액인 양 두지 않는다

  const paymentValue =
    insight.marginal_payment != null
      ? `약 ${insight.marginal_payment.toLocaleString('ko-KR')}만원`
      : (insight.marginal_payment_note ?? '변동 없음')
  // 값과 같은 숫자를 괄호로 되풀이하지 않는다 — 가정(금리·기간)을 밝히는 자리다 (expl §7).
  const paymentSub =
    insight.marginal_payment != null
      ? insight.funding?.term_assumed
        ? `상환 ${insight.funding.term_assumed}개월 가정`
        : '한계 차입 기준'
      : isSafety
        ? '추가 차입 없음'
        : '월 상환액 미산출'

  const rateText = insight.funding ? formatRate(insight.funding) : null
  const rateNote = insight.funding ? formatRateNote(insight.funding) : null

  return (
    <article className={`${styles.row} ${highlight ? styles.highlight : ''} ${applied ? styles.applied : ''}`}>
      <div className={styles.top}>
        <span className={`${styles.badge} ${highlight ? styles.badgeHighlight : ''}`}>
          <Icon size={18} aria-hidden />
        </span>

        <div className={styles.titleCol}>
          <div className={styles.titleRow}>
            <span className={`t-title2 ${styles.title}`}>
              {isSafety
                ? '현재 예산 유지'
                : entryDiff > 0
                  ? `진입 후보 ${entryDiff.toLocaleString('ko-KR')}곳 확대`
                  : '조건 변경'}
            </span>
            {applied && <span className={`t-caption ${styles.appliedChip}`}>적용 중</span>}
            {highlight && !applied && <span className={`t-caption ${styles.chip}`}>임팩트 최대</span>}
          </div>
          <span className={`t-caption ${styles.sub}`}>
            {TYPE_LABEL[insight.type] ?? insight.type}
            {budgetValue && ` · ${budgetLabel}`}
          </span>
          {budgetValue && <span className={`t-label ${styles.accent}`}>{budgetValue}</span>}
        </div>

        <div className={styles.stat}>
          <span className={`t-caption ${styles.statLabel}`}>진입 가능 상권</span>
          <span className={`t-stat ${styles.statValue}`}>
            {delta.n_entry_after.toLocaleString('ko-KR')}곳
          </span>
          <span className={`t-caption ${styles.statSub}`}>
            {entryDiff > 0 ? `(+${entryDiff.toLocaleString('ko-KR')}곳)` : '(변동 없음)'}
          </span>
        </div>

        <div className={styles.stat}>
          <span className={`t-caption ${styles.statLabel}`}>지속 가능 후보</span>
          <span className={`t-stat ${styles.statValue}`}>
            {delta.n_sustain_after != null ? `${delta.n_sustain_after.toLocaleString('ko-KR')}곳` : '—'}
          </span>
          <span className={`t-caption ${styles.statSub}`}>
            {delta.n_sustain_after != null && delta.n_sustain_after < delta.n_entry_after
              ? '(상환 부담 반영)'
              : '(변동 없음)'}
          </span>
        </div>

        <div className={styles.stat}>
          <span className={`t-caption ${styles.statLabel}`}>월 상환 부담</span>
          <span className={`t-stat ${styles.statValue}`}>{paymentValue}</span>
          <span className={`t-caption ${styles.statSub}`}>{paymentSub}</span>
        </div>

        <span className={styles.action}>
          <Button
            variant={applied ? 'secondary' : highlight ? 'primary' : 'secondary'}
            size="md"
            onClick={onApply}
            disabled={busy || applied}
          >
            {/*
              적용 중 표시는 **누른 행에만** 붙인다. 이전에는 플래그가 전역이라 한 행을 적용하는
              동안 모든 행이 똑같이 비활성돼, 어느 것을 눌렀는지 화면이 말해 주지 않았다 (m-3).
              나머지 행은 중복 적용 방지를 위해 비활성만 유지한다.
            */}
            {applied ? '적용 중' : applying ? '적용하는 중…' : '적용하기'}
          </Button>
        </span>
      </div>

      <div className={styles.reasons}>
        <span className={`t-caption ${styles.reasonsLabel}`}>판단 근거</span>
        {isSafety ? (
          <>
            <span className={`t-caption ${styles.reasonChip}`}>
              <Check size={13} className={styles.check} aria-hidden /> 재무 안정성 최우선
            </span>
            <span className={`t-caption ${styles.reasonChip}`}>
              <Check size={13} className={styles.check} aria-hidden /> 상환 부담 최소화
            </span>
          </>
        ) : (
          <>
            <span className={`t-caption ${styles.reasonChip}`}>
              <Check size={13} className={styles.check} aria-hidden /> 진입 후보 {entryDiff.toLocaleString('ko-KR')}곳 증가
            </span>
            {delta.n_sustain_after != null && (
              <span className={`t-caption ${styles.reasonChip}`}>
                <Check size={13} className={styles.check} aria-hidden /> 상환 부담 반영 후 지속 {delta.n_sustain_after.toLocaleString('ko-KR')}곳
              </span>
            )}
          </>
        )}

        <button
          type="button"
          className={`t-caption ${styles.expand}`}
          aria-expanded={open}
          aria-controls={panelId}
          onClick={() => setOpen((o) => !o)}
        >
          근거 보기
          <ChevronDown size={14} className={`${styles.chevron} ${open ? styles.chevronOpen : ''}`} aria-hidden />
        </button>
      </div>

      <div id={panelId} ref={panelRef} className={`${styles.panel} ${open ? styles.panelOpen : ''}`}>
        <div className={styles.panelInner}>
          <div className={styles.panelBody}>
            <p className={`t-body ${styles.fundingMeta}`}>{insight.headline}</p>

            {insight.funding && (
              <>
                <p className={`t-body-strong ${styles.fundingName}`}>
                  자격 요건 부합 상품: {insight.funding.name}
                </p>
                <p className={`t-caption ${styles.fundingMeta}`}>
                  최대 {insight.funding.amount_max.toLocaleString('ko-KR')}만원
                  {rateText && ` · ${rateText}`}
                  {insight.funding.term_assumed > 0 && ` · 상환 ${insight.funding.term_assumed}개월 가정`}
                  {' · 출처 '}
                  {insight.funding.source.org}
                  {insight.funding.source.collected && ` (${insight.funding.source.collected} 수집)`}
                </p>
                {rateNote && <p className={`t-caption ${styles.fundingMeta}`}>{rateNote}</p>}
                {insight.funding.source_quote && (
                  <SourceQuoteBlock
                    quote={insight.funding.source_quote}
                    sourceUrl={insight.funding.source.url}
                  />
                )}
              </>
            )}

          </div>
        </div>
      </div>

      {/*
        고지는 **접힘 밖**에 둔다. 계약이 인사이트마다 `disclaimer: true` 를 보내는 취지는
        카드 단위 동반인데, 「근거 보기」를 펼쳐야 보이는 자리에 있으면 그 취지가 성립하지
        않는다 (계약 리뷰 P2-5). 화면 하단의 상시 고지는 그대로 두고 — 화면 단위 요건과
        카드 단위 요건은 별개다 — 여기서는 이 행이 말하는 선택지에 붙인다.
      */}
      {insight.disclaimer && (
        <p className={`t-caption ${styles.disclaimer}`}>ⓘ {DISCLAIMER}</p>
      )}
    </article>
  )
}

import { useEffect, useId, useRef, useState } from 'react'
import { ChevronDown, Landmark, MapPin, Map as MapIcon, RotateCcw, TrendingUp } from 'lucide-react'
import Button from '../components/Button'
import { formatAmount, splitAmount } from '../lib/format'
import styles from './ExploreSummary.module.css'

export interface ExploreSummaryProps {
  /** 현재 화면이 기준으로 삼는 예산(적용 후면 갱신된 값) */
  budget: number
  /** 인사이트 적용 전 예산. 같으면 미적용 상태로 렌더한다. */
  baseBudget: number
  entryCount: number | null
  baseEntryCount: number | null
  sustainCount: number | null
  /** 월 상환 부담(만원). 미적용이면 null */
  monthlyPayment: number | null
  dataAsOf?: string
  onOpenMap: () => void
  onRevert: () => void
}

/**
 * 탐색 화면 상단 요약 스트립 — Figma "applied-summary".
 *
 * 적용 여부 하나로 두 상태가 갈린다:
 *  - 미적용: "확정 예산" + 확정 예산 기준 후보 수, 되돌리기 없음
 *  - 적용됨: "적용된 예산" + 증분·기준 예산 병기, 되돌리기 노출
 *
 * 되돌리기가 반드시 있어야 하는 이유는 컴플라이언스다 — 상향 인사이트를 적용한 상태로
 * 화면이 고정되면 "더 빌리는 쪽"만 남는다. 하향 복귀 경로를 항상 열어 둔다(expl §7).
 */
export default function ExploreSummary({
  budget,
  baseBudget,
  entryCount,
  baseEntryCount,
  sustainCount,
  monthlyPayment,
  dataAsOf,
  onOpenMap,
  onRevert,
}: ExploreSummaryProps) {
  const [open, setOpen] = useState(false)
  const panelId = useId()
  const panelRef = useRef<HTMLDivElement>(null)
  const applied = budget !== baseBudget
  const gap = budget - baseBudget
  const { value, unit } = splitAmount(budget)

  useEffect(() => {
    if (panelRef.current) panelRef.current.inert = !open
  }, [open])

  const entryDiff = entryCount != null && baseEntryCount != null ? entryCount - baseEntryCount : null

  return (
    <section className={styles.card} aria-label="탐색 기준 요약">
      <div className={styles.row}>
        <div className={styles.budget}>
          <span className={`t-caption ${styles.chip}`}>{applied ? '적용된 예산' : '확정 예산'}</span>
          <span className={styles.valueRow}>
            <span className="t-display">{value}</span>
            <span className="t-title2">{unit}</span>
          </span>
          {applied ? (
            <>
              <span className={`t-label ${styles.delta}`}>
                {gap > 0 ? `+${formatAmount(gap)} 확보` : `${formatAmount(Math.abs(gap))} 하향`}
              </span>
              <span className={`t-caption ${styles.base}`}>기준 예산 {formatAmount(baseBudget)}</span>
            </>
          ) : (
            <span className={`t-caption ${styles.base}`}>예산 선택 단계에서 확정한 금액</span>
          )}
        </div>

        <span className={styles.divider} aria-hidden />

        <div className={styles.stat}>
          <span className={styles.iconWrap}>
            <MapPin size={16} aria-hidden />
          </span>
          <span className={styles.statTexts}>
            <span className={`t-caption ${styles.statLabel}`}>진입 가능 상권</span>
            <span className={`t-stat ${styles.statValue}`}>
              {entryCount != null ? `${entryCount.toLocaleString('ko-KR')}곳` : '—'}
            </span>
            <span className={`t-caption ${styles.statSub}`}>
              {entryDiff != null && entryDiff !== 0
                ? `기준 ${baseEntryCount?.toLocaleString('ko-KR')}곳 대비 ${entryDiff > 0 ? '+' : ''}${entryDiff.toLocaleString('ko-KR')}곳`
                : '확정 예산 기준'}
            </span>
          </span>
        </div>

        <div className={styles.stat}>
          <span className={styles.iconWrap}>
            <TrendingUp size={16} aria-hidden />
          </span>
          <span className={styles.statTexts}>
            <span className={`t-caption ${styles.statLabel}`}>지속 가능 후보</span>
            <span className={`t-stat ${styles.statValue}`}>
              {sustainCount != null ? `${sustainCount.toLocaleString('ko-KR')}곳` : '—'}
            </span>
            <span className={`t-caption ${styles.statSub}`}>상환 부담 반영</span>
          </span>
        </div>

        <div className={styles.stat}>
          <span className={styles.iconWrap}>
            <Landmark size={16} aria-hidden />
          </span>
          <span className={styles.statTexts}>
            <span className={`t-caption ${styles.statLabel}`}>월 상환 부담</span>
            <span className={`t-stat ${styles.statValue}`}>
              {monthlyPayment != null ? `약 ${monthlyPayment.toLocaleString('ko-KR')}만원` : '해당 없음'}
            </span>
            <span className={`t-caption ${styles.statSub}`}>
              {monthlyPayment != null ? '한계 차입 기준' : '추가 차입 없음'}
            </span>
          </span>
        </div>

        <span className={styles.spacer} />

        <div className={styles.cta}>
          <Button variant="primary" size="md" onClick={onOpenMap}>
            <MapIcon size={16} aria-hidden /> 지도에서 보기 →
          </Button>
          {applied && (
            <button type="button" className={`t-caption ${styles.revert}`} onClick={onRevert}>
              <RotateCcw size={12} aria-hidden /> 이전 예산으로 돌아가기
            </button>
          )}
        </div>
      </div>

      <button
        type="button"
        className={`t-caption ${styles.disclosure}`}
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((o) => !o)}
      >
        분석 기준 및 데이터 출처 보기
        <ChevronDown size={14} className={`${styles.chevron} ${open ? styles.chevronOpen : ''}`} aria-hidden />
      </button>

      <div id={panelId} ref={panelRef} className={`${styles.sourcePanel} ${open ? styles.sourcePanelOpen : ''}`}>
        <div className={styles.sourceInner}>
          <div className={`t-caption ${styles.sourceBody}`}>
            <span>
              진입 후보 = 초기비용이 예산 이내인 상권. 지속 가능 후보 = 여기에 한계 차입의 월 상환
              부담까지 반영해 부담률 임계를 넘지 않는 상권 (스펙 §4-2 · expl §2-3).
            </span>
            <span>
              임대료는 한국부동산원 상권 분기 평균(추정), 권리금은 연간 조사(전년 기준)이며 실제
              금액은 개별 물건에 따라 다릅니다.
              {dataAsOf && ` 데이터 기준일 ${dataAsOf}.`}
            </span>
            <span>
              본 정보는 공개 자료 기반 정보 제공이며 대출 권유·중개·자문이 아닙니다. 한도·승인은
              기관 심사 사항입니다.
            </span>
          </div>
        </div>
      </div>
    </section>
  )
}

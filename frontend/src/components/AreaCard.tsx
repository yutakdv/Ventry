import { ClipboardCheck } from 'lucide-react'
import VerdictBadge from './VerdictBadge'
import { formatBurdenRatio, formatRentSource, formatTransit } from '../lib/format'
import { rentAreaShort, rentPerPyeong } from '../lib/rentArea'
import type { Area, Industry } from '../api/types'
import styles from './AreaCard.module.css'

/** 점수 등급 구간(90+/80/70/60) 매핑은 프론트 소관 (API_CONTRACT §4). */
function grade(score: number): 'g90' | 'g80' | 'g70' | 'g60' {
  if (score >= 90) return 'g90'
  if (score >= 80) return 'g80'
  if (score >= 70) return 'g70'
  return 'g60'
}

function won(v: number) {
  return `${v.toLocaleString('ko-KR')}만원`
}

/**
 * 추천 상권 카드 — Figma District Card.
 * **임대료 출처 줄과 교통 줄은 접지 않고 항상 보인다** (스펙 §7 하드 룰).
 */
export default function AreaCard({
  area,
  selected,
  onSelect,
  onCheck,
  industry,
  scopeNote,
}: {
  area: Area
  selected: boolean
  /** 카드 본문 클릭 — 선택(지도 마커 연동)만 한다. */
  onSelect: () => void
  /** 하단 버튼 — 역방향 판정을 연다. 선택과 분리해 둔 이유는 목록을 훑는 동안 모달이 뜨지 않게 하기 위함. */
  onCheck: () => void
  /** 임대료 금액의 면적 조건 표기용 (이슈 #151). 모르면 면적을 적지 않는다. */
  industry?: Industry | null
  /**
   * 임대료 근거 범위 문장 (가정 #96). **선택된 카드에만** 내려온다 — 지도에 경계가 떠
   * 있을 때 그 두 선이 무엇인지 글로 받는 자리라, 목록 전체에 깔면 짝이 맞지 않는다.
   */
  scopeNote?: string
}) {
  const areaUnit = rentAreaShort(industry)
  const perPyeong = rentPerPyeong(area.monthly_rent, industry)
  return (
    <article
      className={`${styles.card} ${selected ? styles.selected : ''}`}
      data-area-code={area.area_code}
    >
      <button type="button" className={styles.body} aria-pressed={selected} onClick={onSelect}>
      <span className={styles.header}>
        <span className={`t-title2 ${styles.score} ${styles[grade(area.score)]}`}>{area.score}</span>
        <span className={styles.titleCol}>
          <span className={styles.titleRow}>
            <span className={`t-body-strong ${styles.name}`}>{area.name}</span>
            <VerdictBadge verdict={area.verdict} />
          </span>
        </span>
      </span>

      <span className={styles.stats}>
        <span className={styles.stat}>
          <span className={`t-caption ${styles.statLabel}`}>
            환산 임대료 (월{areaUnit ? `, ${areaUnit}` : ', 추정'})
          </span>
          <span className={`t-label ${styles.statValue}`}>{won(area.monthly_rent)}</span>
          {/* 대표면적과 다른 평수를 생각 중인 사용자가 곧바로 환산할 수 있게 (이슈 #151). */}
          {perPyeong && <span className={`t-caption ${styles.statNote}`}>{perPyeong}</span>}
        </span>
        <span className={styles.stat}>
          <span className={`t-caption ${styles.statLabel}`}>추정 매출 (월)</span>
          <span className={`t-label ${styles.statValue}`}>{won(area.est_sales)}</span>
        </span>
        <span className={styles.stat}>
          <span className={`t-caption ${styles.statLabel}`}>유동인구 (일 평균)</span>
          <span className={`t-label ${styles.statValue}`}>{area.daily_floating.toLocaleString('ko-KR')}명</span>
        </span>
        <span className={styles.stat}>
          <span className={`t-caption ${styles.statLabel}`}>부담률 (임대료/매출)</span>
          <span className={`t-label ${styles.statValue}`}>{formatBurdenRatio(area.burden_ratio)}</span>
        </span>
        <span className={styles.stat}>
          <span className={`t-caption ${styles.statLabel}`}>초기비용 (권리금 제외)</span>
          <span className={`t-label ${styles.statValue}`}>
            {area.cost.ex_premium[0].toLocaleString('ko-KR')}~{won(area.cost.ex_premium[1])}
          </span>
        </span>
        <span className={styles.stat}>
          <span className={`t-caption ${styles.statLabel}`}>초기비용 (권리금 포함)</span>
          <span className={`t-label ${styles.statValue}`}>
            {area.cost.incl_premium[0].toLocaleString('ko-KR')}~{won(area.cost.incl_premium[1])}
          </span>
        </span>
      </span>

      {/* 근거 — 임대료 출처·교통은 상시 표기 (스펙 §7) */}
      <span className={styles.evidence}>
        <span className={`t-caption ${styles.evidenceLine}`}>
          {formatRentSource(area.rent_source, industry)}
        </span>
        <span className={`t-caption ${styles.evidenceLine}`}>{formatTransit(area.transit)}</span>
        <span className={`t-caption ${styles.evidenceLine}`}>
          권리금: 연간 조사(전년 기준) · 실제 금액은 개별 물건에 따라 다릅니다
        </span>
        {scopeNote && <span className={`t-caption ${styles.evidenceLine}`}>{scopeNote}</span>}
      </span>

      <span className={styles.reason}>
        <span className={`t-caption ${styles.reasonTitle}`}>분석 근거</span>
        <span className={`t-caption ${styles.reasonBody}`}>{area.reason_text}</span>
      </span>
      </button>

      <button
        type="button"
        className={`t-label ${styles.verdictBtn}`}
        onClick={onCheck}
        aria-label={`${area.name} 판정과 자격 요건 부합 상품 보기`}
      >
        <ClipboardCheck size={14} aria-hidden /> 판정·자격 부합 상품 보기
      </button>
    </article>
  )
}

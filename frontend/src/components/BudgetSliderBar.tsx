import Slider from './Slider'
import { formatAmount } from '../lib/format'
import { rentAreaShort } from '../lib/rentArea'
import type { BudgetPreview, Industry } from '../api/types'
import styles from './BudgetSliderBar.module.css'

/**
 * 화면 3 하단 고정 예산 슬라이더 (스펙 §7 · FE-05).
 *
 * 화면 2의 슬라이더가 예산을 **확정**하는 도구라면 이쪽은 확정 후 **미세 조정**이다. 둘 다
 * `POST /budget`(덮어쓰기)을 호출하므로 예산의 진실 원천은 여전히 세션 하나다.
 *
 * 표시하는 수치는 전부 `/budget` 응답의 `preview`다 — 화면이 후보 수를 세거나 범위를 다시
 * 구하지 않는다 (§0-1). 재조회 중에는 직전 값을 지우지 않고 흐리게 두는데, 값을 비우면
 * 슬라이더를 움직일 때마다 숫자가 사라졌다 나타나 오히려 느리게 느껴지기 때문이다.
 */
export default function BudgetSliderBar({
  min,
  max,
  value,
  onChange,
  preview,
  conditionalCount,
  pending,
  industry,
}: {
  min: number
  max: number
  value: number
  onChange: (v: number) => void
  preview: BudgetPreview | null
  /**
   * 목록에 남아 있는 조건부 적합 수.
   *
   * 진입 후보가 0곳이어도 목록·지도에는 조건부 적합이 남는다 — 무권리 매물을 잡으면 열리는
   * 구간이라 마커 3종에 포함되기 때문이다(스펙 §0-4). "진입 0곳"만 말하고 끝내면 화면이
   * 스스로 모순돼 보이므로, 남아 있는 것이 무엇인지 같은 자리에서 밝힌다.
   */
  conditionalCount: number
  /** 재계산 진행 중 — 값을 지우지 않고 흐리게만 만든다. */
  pending: boolean
  /** 임대료 금액의 면적 조건 표기용 (이슈 #151). */
  industry?: Industry | null
}) {
  const areaUnit = rentAreaShort(industry)
  const noCandidate = preview?.area_count === 0

  return (
    <div className={styles.bar}>
      <div className={styles.sliderCol}>
        <div className={styles.head}>
          <span className={`t-label ${styles.title}`}>예산 조정</span>
          <span className={`t-caption ${styles.hint}`}>
            움직이면 추천 결과가 다시 계산됩니다
          </span>
        </div>
        <Slider
          min={min}
          max={max}
          step={100}
          value={value}
          onChange={onChange}
          format={formatAmount}
          label="입지 추천에 사용할 예산"
        />
      </div>

      <div className={`${styles.previewCol} ${pending ? styles.previewPending : ''}`}>
        {noCandidate ? (
          <p className={`t-caption ${styles.none}`}>
            권리금 포함 기준으로 진입 가능한 상권이 없습니다.
            {conditionalCount > 0 && (
              <>
                {' '}
                목록의 <strong>조건부 적합 {conditionalCount.toLocaleString('ko-KR')}곳</strong>은
                무권리 매물을 확보하면 진입 가능한 구간입니다.
              </>
            )}{' '}
            예산을 올리거나 결정공간 탐색에서 다른 구성을 확인할 수 있습니다.
          </p>
        ) : (
          <>
            <div className={styles.stat}>
              <span className={`t-caption ${styles.statLabel}`}>진입 가능 상권</span>
              <span className={`t-body-strong ${styles.statValue}`}>
                {preview ? `${preview.area_count.toLocaleString('ko-KR')}곳` : '—'}
              </span>
              <span className={`t-caption ${styles.statNote}`}>권리금 포함 기준</span>
            </div>
            <div className={styles.stat}>
              <span className={`t-caption ${styles.statLabel}`}>
                환산 임대료 (월{areaUnit ? `, ${areaUnit}` : ', 추정'})
              </span>
              <span className={`t-body-strong ${styles.statValue}`}>
                {preview?.rent_range
                  ? `${formatAmount(preview.rent_range[0])} ~ ${formatAmount(preview.rent_range[1])}`
                  : '—'}
              </span>
            </div>
            <div className={styles.stat}>
              <span className={`t-caption ${styles.statLabel}`}>일평균 유동인구</span>
              <span className={`t-body-strong ${styles.statValue}`}>
                {preview?.floating_range
                  ? `${preview.floating_range[0].toLocaleString('ko-KR')} ~ ${preview.floating_range[1].toLocaleString('ko-KR')}명`
                  : '—'}
              </span>
            </div>
          </>
        )}
      </div>
    </div>
  )
}

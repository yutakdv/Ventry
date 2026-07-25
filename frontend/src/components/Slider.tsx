import styles from './Slider.module.css'

/**
 * 단일 핸들 예산 슬라이더 — Figma "Range Slider"(트랙 6px·핸들 20px·파란 말풍선).
 * 계약상 `confirmed_budget`이 단일 정수라 핸들도 하나다 (범위 전송은 서버가 거절).
 * 접근성은 네이티브 `input[type=range]`에 맡긴다 — 키보드 조작·스크린리더가 그대로 동작한다.
 */
export default function Slider({
  min,
  max,
  step = 1,
  value,
  onChange,
  format,
  label,
}: {
  min: number
  max: number
  step?: number
  value: number
  onChange: (v: number) => void
  /** 말풍선·눈금에 쓰는 표시 형식 */
  format: (v: number) => string
  label: string
}) {
  const pct = max > min ? ((value - min) / (max - min)) * 100 : 0

  return (
    <div className={styles.wrap} style={{ ['--pct' as string]: `${pct}%` }}>
      <div className={styles.bubbleRow}>
        <span className={`t-caption ${styles.bubble}`}>{format(value)}</span>
      </div>

      <input
        type="range"
        className={styles.input}
        min={min}
        max={max}
        step={step}
        value={value}
        aria-label={label}
        aria-valuetext={format(value)}
        onChange={(e) => onChange(Number(e.target.value))}
      />

      <div className={styles.scale}>
        <span className="t-caption">{format(min)}</span>
        <span className="t-caption">{format(max)}</span>
      </div>
    </div>
  )
}

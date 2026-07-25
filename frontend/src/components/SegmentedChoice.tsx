import styles from './SegmentedChoice.module.css'

interface Option<T extends string> {
  value: T
  label: string
}

interface Props<T extends string> {
  options: Option<T>[]
  value: T | null
  onChange: (v: T) => void
  invalid?: boolean
}

/** 세그먼트 선택 — Figma Segmented Choice (예/아니오, 가능/어려움 등). 선택 시 흰 pill. invalid면 빨간 테두리. */
export default function SegmentedChoice<T extends string>({
  options,
  value,
  onChange,
  invalid,
}: Props<T>) {
  return (
    <div className={`${styles.group} ${invalid ? styles.invalid : ''}`} role="group">
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          className={`t-body ${styles.seg} ${value === o.value ? styles.active : ''}`}
          aria-pressed={value === o.value}
          onClick={() => onChange(o.value)}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}

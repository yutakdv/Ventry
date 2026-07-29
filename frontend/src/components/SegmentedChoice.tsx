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
  /**
   * 아래 셋은 `Field` 가 붙여 준다 — 버튼 그룹은 `<label for>` 로 이름을 받지 못하므로
   * `aria-labelledby` 가 없으면 "기존 사업자 / 예비 창업자" 두 버튼만 읽히고
   * **무엇을 묻는 질문인지가 사라진다.**
   */
  id?: string
  'aria-labelledby'?: string
  'aria-describedby'?: string
}

/** 세그먼트 선택 — Figma Segmented Choice (예/아니오, 가능/어려움 등). 선택 시 흰 pill. invalid면 빨간 테두리. */
export default function SegmentedChoice<T extends string>({
  options,
  value,
  onChange,
  invalid,
  id,
  'aria-labelledby': ariaLabelledBy,
  'aria-describedby': ariaDescribedBy,
}: Props<T>) {
  return (
    <div
      className={`${styles.group} ${invalid ? styles.invalid : ''}`}
      role="group"
      id={id}
      aria-labelledby={ariaLabelledBy}
      aria-describedby={ariaDescribedBy}
    >
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

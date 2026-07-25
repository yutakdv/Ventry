import type { InputHTMLAttributes } from 'react'
import styles from './TextInput.module.css'

interface Props extends InputHTMLAttributes<HTMLInputElement> {
  /** 우측 접미 단위 (예: "원", "세") */
  suffix?: string
  /** Validation 실패 시 빨간 테두리 */
  invalid?: boolean
}

/** 텍스트/숫자 입력 — Figma Form Field 입력부. invalid면 빨간 테두리. */
export default function TextInput({ suffix, invalid, ...rest }: Props) {
  return (
    <div className={`${styles.wrap} ${invalid ? styles.invalid : ''}`}>
      <input className={`t-body ${styles.input}`} {...rest} />
      {suffix && <span className={`t-body ${styles.suffix}`}>{suffix}</span>}
    </div>
  )
}

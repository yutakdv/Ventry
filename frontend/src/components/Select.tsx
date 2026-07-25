import type { SelectHTMLAttributes, ReactNode } from 'react'
import styles from './Select.module.css'

interface Props extends SelectHTMLAttributes<HTMLSelectElement> {
  placeholder?: string
  invalid?: boolean
  children: ReactNode // <option> 목록
}

/** 드롭다운 — Figma Form Field 셀렉트부. value 없으면 placeholder를 tertiary 색으로. invalid면 빨간 테두리. */
export default function Select({ placeholder, invalid, children, value, ...rest }: Props) {
  const isEmpty = value === '' || value === undefined
  return (
    <div className={styles.wrap}>
      <select
        className={`t-body ${styles.select} ${isEmpty ? styles.empty : ''} ${invalid ? styles.invalid : ''}`}
        value={value}
        {...rest}
      >
        {placeholder && (
          <option value="" disabled>
            {placeholder}
          </option>
        )}
        {children}
      </select>
      <span className={styles.chevron} aria-hidden>
        ▾
      </span>
    </div>
  )
}

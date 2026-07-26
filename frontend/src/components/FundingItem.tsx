import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import type { LucideIcon } from 'lucide-react'
import { ChevronDown } from 'lucide-react'
import styles from './FundingItem.module.css'

export interface FundingDetail {
  label: string
  value: ReactNode
}

/**
 * 조달 구성 내역 1행 — Figma "Accordion Row".
 * `details`가 있으면 펼칠 수 있는 행이 되고, 없으면(자기자본 등) 평범한 행으로 남는다
 * — 펼칠 내용이 없는 행에 화살표를 두면 눌러도 아무 일이 없다.
 */
export default function FundingItem({
  icon: Icon,
  title,
  subtitle,
  value,
  source,
  dataAsOf,
  note,
  details,
}: {
  icon: LucideIcon
  title: string
  subtitle: string
  value: string
  source?: string
  dataAsOf?: string
  note?: string
  details?: FundingDetail[]
}) {
  const [open, setOpen] = useState(false)
  const panelId = useId()
  const panelRef = useRef<HTMLDivElement>(null)
  const expandable = !!details?.length

  // 접힌 패널 안의 링크가 탭 순서에 잡히지 않도록 한다(속성이 아닌 DOM 프로퍼티로 설정).
  useEffect(() => {
    if (panelRef.current) panelRef.current.inert = !open
  }, [open])

  const head = (
    <>
      <span className={styles.iconWrap}>
        <Icon size={18} className={styles.icon} aria-hidden />
      </span>
      <span className={styles.left}>
        <span className={`t-body-strong ${styles.title}`}>{title}</span>
        <span className={`t-caption ${styles.subtitle}`}>{subtitle}</span>
        {source && (
          <span className={`t-caption ${styles.source}`}>
            🔗 출처: {source}
            {dataAsOf && ` · ${dataAsOf} 기준`}
          </span>
        )}
      </span>
      <span className={styles.right}>
        <span className={`t-body-strong ${styles.value}`}>{value}</span>
        {note && <span className={`t-caption ${styles.note}`}>{note}</span>}
      </span>
      {expandable && (
        <ChevronDown size={16} className={`${styles.chevron} ${open ? styles.chevronOpen : ''}`} aria-hidden />
      )}
    </>
  )

  return (
    <div className={styles.item}>
      {expandable ? (
        <button
          type="button"
          className={`${styles.row} ${styles.rowButton}`}
          aria-expanded={open}
          aria-controls={panelId}
          onClick={() => setOpen((o) => !o)}
        >
          {head}
        </button>
      ) : (
        <div className={styles.row}>{head}</div>
      )}

      {expandable && (
        <div id={panelId} ref={panelRef} className={`${styles.panel} ${open ? styles.panelOpen : ''}`}>
          <div className={styles.panelInner}>
            <dl className={styles.detailList}>
              {details.map((d) => (
                <div key={d.label} className={styles.detailRow}>
                  <dt className={`t-caption ${styles.detailLabel}`}>{d.label}</dt>
                  <dd className={`t-label ${styles.detailValue}`}>{d.value}</dd>
                </div>
              ))}
            </dl>
          </div>
        </div>
      )}
    </div>
  )
}

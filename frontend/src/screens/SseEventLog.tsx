import { useEffect, useId, useRef, useState } from 'react'
import { ChevronDown } from 'lucide-react'
import styles from './SseEventLog.module.css'

export interface SseLogEntry {
  /** SSE 이벤트명 그대로 — plan / insight / refine / done */
  name: string
  /** 그 이벤트로 실제 도착한 필드 요약 */
  detail: string
}

/**
 * 계산 로그 (스펙 §5-2 "SSE로 도구 호출 로그 노출").
 *
 * 계약에는 도구 호출 이벤트가 없다. 그래서 `eligibility_filter` 같은 내부 함수명을 화면에
 * 적지 않는다 — 서버가 보내지 않은 것을 프론트가 지어내는 셈이라 §0-1과 충돌한다.
 * 대신 **실제 수신한 SSE 이벤트를 그대로** 나열한다. 그 자체가 "무엇을 계산했는지"의 증거다.
 *
 * 기본 접힘이다 (§5-3 — 사고 과정이 상시 흘러가는 연출 금지). 존재의 고지는 카운트 배지로 한다.
 */
export default function SseEventLog({ entries, done }: { entries: SseLogEntry[]; done: boolean }) {
  const [open, setOpen] = useState(false)
  const panelId = useId()
  const panelRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (panelRef.current) panelRef.current.inert = !open
  }, [open])

  if (entries.length === 0) return null

  return (
    <section className={styles.panel}>
      <button
        type="button"
        className={`t-label ${styles.trigger}`}
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((o) => !o)}
      >
        계산 로그
        <span className={`t-caption ${styles.count}`}>
          · 수신 이벤트 {entries.length}건{done ? ' (탐색 완료)' : ''}
        </span>
        <ChevronDown size={14} className={`${styles.chevron} ${open ? styles.chevronOpen : ''}`} aria-hidden />
      </button>

      <div id={panelId} ref={panelRef} className={`${styles.body} ${open ? styles.bodyOpen : ''}`}>
        <div className={styles.bodyInner}>
          <ul className={styles.list}>
            {entries.map((e, i) => (
              <li key={`${e.name}-${i}`} className={`t-caption ${styles.entry}`}>
                <span className={styles.name}>{e.name}</span>
                <span className={styles.detail}>{e.detail}</span>
              </li>
            ))}
          </ul>
          <p className={`t-caption ${styles.note}`}>
            수신한 SSE 이벤트를 그대로 표시합니다. 수치는 전부 서버의 결정적 계산 결과이며 화면에서
            재계산하지 않습니다 (스펙 §0-1).
          </p>
        </div>
      </div>
    </section>
  )
}

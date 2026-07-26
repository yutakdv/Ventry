import { useEffect, useRef, type ReactNode } from 'react'
import { X } from 'lucide-react'
import styles from './Modal.module.css'

/**
 * 모달 — 네이티브 `<dialog open>`의 modal 모드를 쓴다.
 * 포커스 트랩·Esc 닫기·배경 inert 처리를 브라우저가 해 주므로 직접 구현하지 않는다.
 * 배경 클릭으로도 닫힌다(대화상자 자체가 클릭 대상이면 여백을 누른 것).
 */
export default function Modal({
  open,
  title,
  onClose,
  children,
}: {
  open: boolean
  title: ReactNode
  onClose: () => void
  children: ReactNode
}) {
  const ref = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const el = ref.current
    if (!el) return
    if (open && !el.open) el.showModal()
    if (!open && el.open) el.close()
  }, [open])

  return (
    <dialog
      ref={ref}
      className={styles.dialog}
      onClose={onClose}
      onClick={(e) => {
        if (e.target === ref.current) onClose()
      }}
    >
      <div className={styles.head}>
        <span className={`t-title2 ${styles.title}`}>{title}</span>
        <button type="button" className={styles.close} onClick={onClose} aria-label="닫기">
          <X size={18} aria-hidden />
        </button>
      </div>
      <div className={styles.body}>{children}</div>
    </dialog>
  )
}

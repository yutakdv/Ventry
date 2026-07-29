import { cloneElement, isValidElement, useId, type ReactElement, type ReactNode } from 'react'
import styles from './Field.module.css'

/**
 * 라벨 + 컨트롤 래퍼 — Figma Form Field. error 있으면 하단에 빨간 메시지.
 *
 * **연결은 여기서 한다.** 예전에는 `htmlFor` 가 선택 인자였고 진단 화면 8개 필수 필드 중
 * 어느 곳도 넘기지 않아, `<label>` 이 어떤 컨트롤도 가리키지 않았다 — 스크린리더에서
 * "업종", "나이", "자기자본" 같은 이름이 전부 사라지고 빈 입력 상자만 읽혔다. 호출부가
 * id 를 손으로 붙이게 하는 방식은 필드가 늘 때마다 빠지므로, 하나뿐인 자식 컨트롤에
 * 이 컴포넌트가 직접 붙인다. 이미 id 가 있으면 그쪽을 존중한다.
 *
 * 에러 메시지에는 `role="alert"` 를 두지 않는다 — 제출 시 8건이 한꺼번에 나타나므로
 * 필드마다 알림을 쏘면 낭독이 뒤엉킨다. 요약 알림은 폼 상단 `Alert` 한 곳이 맡고,
 * 여기서는 `aria-describedby` 로 해당 컨트롤에 붙여 포커스했을 때 읽히게 한다.
 */
export default function Field({
  label,
  htmlFor,
  error,
  children,
}: {
  label: string
  htmlFor?: string
  error?: string
  children: ReactNode
}) {
  const uid = useId()
  const labelId = `${uid}-label`
  const errorId = `${uid}-error`

  type ControlProps = {
    id?: string
    'aria-labelledby'?: string
    'aria-invalid'?: boolean
    'aria-describedby'?: string
  }
  const child = isValidElement<ControlProps>(children) ? (children as ReactElement<ControlProps>) : null
  const controlId = htmlFor ?? child?.props.id ?? `${uid}-control`

  const control = child
    ? cloneElement(child, {
        id: controlId,
        // 네이티브 컨트롤은 htmlFor 로 충분하지만, 버튼 그룹(SegmentedChoice)은
        // label/for 로 이름을 받지 못한다 — 둘 다 통하는 경로를 함께 준다.
        'aria-labelledby': child.props['aria-labelledby'] ?? labelId,
        'aria-invalid': error ? true : undefined,
        'aria-describedby': error ? errorId : child.props['aria-describedby'],
      })
    : children

  return (
    <div className={styles.field}>
      <label className={`t-label ${styles.label}`} id={labelId} htmlFor={controlId}>
        {label}
      </label>
      {control}
      {error && (
        <span id={errorId} className={`t-caption ${styles.error}`}>
          {error}
        </span>
      )}
    </div>
  )
}

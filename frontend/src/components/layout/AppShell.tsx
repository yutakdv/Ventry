import type { ReactNode } from 'react'
import GNBHeader from './GNBHeader'
import Sidebar from './Sidebar'
import { useApiFallback } from '../../lib/useApiFallback'
import styles from './AppShell.module.css'

/** 전 화면 공용 셸 — GNB + (사이드바 | 메인 | 우측레일) 3열 그리드. */
export default function AppShell({
  activeStep,
  children,
  aside,
}: {
  activeStep?: number
  children: ReactNode
  aside?: ReactNode
}) {
  const fallback = useApiFallback()

  return (
    <div className={styles.shell}>
      <GNBHeader />
      {/*
        목 폴백 고지 — 화면 안이 아니라 셸에 둔다. 폴백은 특정 화면의 사건이 아니라
        세션 전체의 상태이고, 어느 화면으로 이동해도 따라와야 하기 때문이다.
      */}
      {fallback && (
        <div className={styles.fallbackNotice} role="status">
          <span className={`t-caption ${styles.fallbackText}`}>
            ⚠ 서버에 연결하지 못해 <strong>예시 데이터</strong>로 표시하고 있습니다. 화면의 수치와
            출처·기준일 표기는 실제 조사 결과가 아닙니다.
          </span>
        </div>
      )}
      <div className={`${styles.container} ${aside ? '' : styles.noRail}`}>
        <Sidebar activeStep={activeStep} />
        <main className={styles.main}>{children}</main>
        {aside && <div className={styles.rail}>{aside}</div>}
      </div>
    </div>
  )
}

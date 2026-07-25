import type { ReactNode } from 'react'
import GNBHeader from './GNBHeader'
import Sidebar from './Sidebar'
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
  return (
    <div className={styles.shell}>
      <GNBHeader />
      <div className={`${styles.container} ${aside ? '' : styles.noRail}`}>
        <Sidebar activeStep={activeStep} />
        <main className={styles.main}>{children}</main>
        {aside && <div className={styles.rail}>{aside}</div>}
      </div>
    </div>
  )
}

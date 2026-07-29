import { useState, type ReactNode } from 'react'
import GNBHeader from './GNBHeader'
import Sidebar from './Sidebar'
import { useApiFallback, useApiFallbackCount } from '../../lib/useApiFallback'
import { useSession } from '../../store/session'
import styles from './AppShell.module.css'

/** 전 화면 공용 셸 — GNB + (사이드바 | 메인 | 우측레일) 3열 그리드. */
export default function AppShell({
  activeStep,
  children,
  aside,
  wide,
}: {
  activeStep?: number
  children: ReactNode
  aside?: ReactNode
  /**
   * 사이드바를 접고 본문이 전폭을 쓴다 (실사용 점검 2026-07-29).
   *
   * `/map`·`/explore` 는 지도+목록 / 차트+시나리오의 **2단 구성**이라 가로가 가장 아쉬운
   * 화면인데, 사이드바가 `position: sticky` 로 스크롤 내내 따라오면서 232px + 거터 24px 을
   * 영구 점유했다. 1280(13인치)에서 본문이 976px 로 줄어 `/explore` 가 실제로 넘쳤다.
   *
   * 마지막 두 단계에서 단계 표시의 값이 가장 낮다는 점도 함께 봤다 — 어디까지 왔는지는
   * 이미 알고 있고, 그 자리에 필요한 것은 결과를 볼 면적이다. 진단·시나리오·예산 화면에는
   * 사이드바가 그대로 남는다.
   */
  wide?: boolean
}) {
  const fallback = useApiFallback()
  const fallbackCount = useApiFallbackCount()
  const { retryFetch } = useSession()
  /** 「다시 불러오기」를 누른 시점의 폴백 누적 횟수. 이후 늘어났다면 재시도도 실패한 것이다. */
  const [retriedAt, setRetriedAt] = useState<number | null>(null)
  const retryFailedAgain = retriedAt != null && fallbackCount > retriedAt

  return (
    <div className={styles.shell}>
      <GNBHeader />
      {/*
        목 폴백 고지 — 화면 안이 아니라 셸에 둔다. 폴백은 특정 화면의 사건이 아니라
        세션 전체의 상태이고, 어느 화면으로 이동해도 따라와야 하기 때문이다.

        배너 자체는 **되돌리지 않는다**(api/fallback.ts) — 이미 화면에 섞인 목 수치를 되돌릴 수
        없기 때문이다. 다만 그동안 일시 장애에서 회복할 방법이 새로고침뿐이었고, 새로고침은
        세션을 통째로 날린다. 그래서 고지는 유지한 채 **조회만 다시 도는** 경로를 붙인다.
      */}
      {fallback && (
        <div className={styles.fallbackNotice} role="status">
          <span className={`t-caption ${styles.fallbackText}`}>
            ⚠ 서버에 연결하지 못해 <strong>예시 데이터</strong>로 표시하고 있습니다. 화면의 수치와
            출처·기준일 표기는 실제 조사 결과가 아닙니다.
            {retryFailedAgain && ' 다시 시도했으나 여전히 연결하지 못했습니다.'}
          </span>
          <button
            type="button"
            className={styles.fallbackRetry}
            onClick={() => {
              setRetriedAt(fallbackCount)
              retryFetch()
            }}
          >
            다시 불러오기
          </button>
        </div>
      )}
      <div
        className={`${styles.container} ${aside ? '' : styles.noRail} ${wide ? styles.wide : ''}`}
      >
        {!wide && <Sidebar activeStep={activeStep} />}
        <main className={styles.main}>{children}</main>
        {aside && <div className={styles.rail}>{aside}</div>}
      </div>
    </div>
  )
}

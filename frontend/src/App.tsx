import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { Suspense, lazy } from 'react'
import { SessionProvider } from './store/session'
import ScrollToTop from './components/ScrollToTop'
import Landing from './screens/landing'
import styles from './App.module.css'

/*
 * 랜딩만 즉시 로드하고 흐름 화면 5개는 라우트 단위로 쪼갠다.
 *
 * 전부 한 덩어리이던 동안 첫 방문자는 카카오맵 연동·프론티어 차트·SSE 탐색 화면까지 전부
 * 내려받은 뒤에야 랜딩을 봤다. 랜딩에서 이탈하는 사용자에게는 그 코드가 끝까지 쓰이지 않는다.
 * 세션은 라우터 바깥 `SessionProvider` 에 있으므로 화면을 나눠도 상태는 그대로다.
 */
const Diagnose = lazy(() => import('./screens/Diagnose'))
const Scenario = lazy(() => import('./screens/Scenario'))
const Budget = lazy(() => import('./screens/Budget'))
const Recommend = lazy(() => import('./screens/Recommend'))
const Explore = lazy(() => import('./screens/Explore'))

/**
 * 화면 라우팅 (스펙 §7) — 진단 → 시나리오 → 예산 → 입지 흐름.
 *  - /           랜딩 (이슈 #101). CTA가 진단으로 보낸다.
 *                `?demo=1`은 데모 프로필을 채운 채 진단을 여는 경로다 — 심사 동선의
 *                "원클릭 시작"(§0-8)을 랜딩에서 바로 잇기 위한 것이다.
 *  - /diagnose   1단계: 자금 진단 (FE-02)
 *  - /scenarios  2단계: 조달 시나리오 (FE-03)
 *  - /budget     3단계: 예산 선택 (FE-03)
 *  - /map        4단계: 입지 추천 (FE-03)
 *  - /explore    4단계 하위: 결정공간 탐색 (FE-04) — 진입점은 /map 하단 CTA다.
 *                스펙 §7·expl §8의 데모 순서가 "추천 → 탐색"이므로 /map 뒤에 온다.
 */
export default function App() {
  return (
    <SessionProvider>
      <BrowserRouter>
        <ScrollToTop />
        {/*
          청크는 같은 오리진에서 오고 크기가 작아 대개 한 프레임 안에 붙는다. 그래도 느린
          회선에서 흰 화면이 남지 않도록 최소한의 문구를 둔다 — 스크린리더에도 전환이 알려진다.
        */}
        <Suspense
          fallback={
            <p className={`t-body ${styles.routeFallback}`} role="status">
              화면을 불러오는 중…
            </p>
          }
        >
          <Routes>
            <Route path="/" element={<Landing />} />
            <Route path="/diagnose" element={<Diagnose />} />
            <Route path="/scenarios" element={<Scenario />} />
            <Route path="/budget" element={<Budget />} />
            <Route path="/map" element={<Recommend />} />
            <Route path="/explore" element={<Explore />} />
            {/*
              없는 경로로 들어오면 이전에는 빈 화면이 떴다 (라우트 미매칭 = null).
              주소와 내용이 어긋나지 않게 랜딩으로 되돌린다.
            */}
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </Suspense>
      </BrowserRouter>
    </SessionProvider>
  )
}

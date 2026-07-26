import { BrowserRouter, Routes, Route } from 'react-router-dom'
import { SessionProvider } from './store/session'
import ScrollToTop from './components/ScrollToTop'
import Landing from './screens/landing'
import Diagnose from './screens/Diagnose'
import Scenario from './screens/Scenario'
import Budget from './screens/Budget'
import Recommend from './screens/Recommend'
import Explore from './screens/Explore'

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
        <Routes>
          <Route path="/" element={<Landing />} />
          <Route path="/diagnose" element={<Diagnose />} />
          <Route path="/scenarios" element={<Scenario />} />
          <Route path="/budget" element={<Budget />} />
          <Route path="/map" element={<Recommend />} />
          <Route path="/explore" element={<Explore />} />
        </Routes>
      </BrowserRouter>
    </SessionProvider>
  )
}

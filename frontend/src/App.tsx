import { BrowserRouter, Routes, Route } from 'react-router-dom'
import { SessionProvider } from './store/session'
import Diagnose from './screens/Diagnose'
import Placeholder from './screens/Placeholder'

/**
 * 화면 라우팅 (스펙 §7) — 화면 1→2→3 흐름.
 *  - /           화면 1: 자금 진단 (FE-02)
 *  - /scenarios  화면 2: 조달 시나리오 (FE-03, 현재 골격)
 *  - /map        화면 3: 입지 추천 (FE-03~05, 현재 골격)
 */
export default function App() {
  return (
    <SessionProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/" element={<Diagnose />} />
          <Route path="/scenarios" element={<Placeholder step={2} title="2단계. 조달 시나리오" />} />
          <Route path="/map" element={<Placeholder step={4} title="4단계. 입지 추천" />} />
        </Routes>
      </BrowserRouter>
    </SessionProvider>
  )
}

import { useEffect, useState } from 'react'

/**
 * 화면 골격 (스펙 §7)
 *  - 화면 1: 자금 진단 (폼 + 자연어 하이브리드) + "데모 프로필 불러오기" 버튼  → FE-02
 *  - 화면 2: 조달 시나리오 카드 2장 (보수/적극) + 출처 배지 + SSE            → FE-03
 *  - 화면 3: 카카오맵 마커 3종 + 근거 패널 + 예산 슬라이더 + 역방향 판정      → FE-03~05
 */
export default function App() {
  const [apiStatus, setApiStatus] = useState<'checking' | 'ok' | 'down'>('checking')

  useEffect(() => {
    fetch('/api/health')
      .then((r) => (r.ok ? setApiStatus('ok') : setApiStatus('down')))
      .catch(() => setApiStatus('down'))
  }, [])

  return (
    <main style={{ fontFamily: 'sans-serif', maxWidth: 720, margin: '4rem auto', padding: '0 1rem' }}>
      <h1>Ventry</h1>
      <p>“어디가 좋은가”가 아니라 <strong>“내 한도로 어디까지 가능한가”</strong>.</p>
      <p>
        API 상태:{' '}
        {apiStatus === 'checking' ? '확인 중…' : apiStatus === 'ok' ? '✅ 연결됨' : '❌ 미연결'}
      </p>
      <hr />
      <p style={{ color: '#888' }}>
        화면 1(진단) · 2(시나리오) · 3(지도 판정)은 docs/TASKS.md의 FE-02~05에서 구현됩니다.
      </p>
    </main>
  )
}

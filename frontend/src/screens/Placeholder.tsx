import { useNavigate } from 'react-router-dom'
import AppShell from '../components/layout/AppShell'
import Button from '../components/Button'

/** FE-03~05에서 구현될 화면 자리. 현재는 진단→시나리오 전환만 확인용. */
export default function Placeholder({ step, title }: { step: number; title: string }) {
  const navigate = useNavigate()
  return (
    <AppShell activeStep={step}>
      <div
        style={{
          padding: 'var(--space-24)',
          background: 'var(--color-bg-surface)',
          border: '1px solid var(--color-border-default)',
          borderRadius: 'var(--radius-lg)',
          boxShadow: 'var(--shadow-card)',
        }}
      >
        <h1 className="t-title1">{title}</h1>
        <p className="t-body" style={{ color: 'var(--color-text-secondary)', margin: '8px 0 24px' }}>
          이 화면은 FE-03~05에서 구현됩니다.
        </p>
        <Button variant="secondary" size="md" onClick={() => navigate('/')}>
          ← 진단으로 돌아가기
        </Button>
      </div>
    </AppShell>
  )
}

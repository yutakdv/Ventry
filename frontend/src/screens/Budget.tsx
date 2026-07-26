import { useCallback, useEffect, useMemo, useState } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { Wallet, PiggyBank, CalendarClock, MapPin, Receipt, Users } from 'lucide-react'
import AppShell from '../components/layout/AppShell'
import RailCard from '../components/layout/RailCard'
import Button from '../components/Button'
import InfoBanner from '../components/InfoBanner'
import Slider from '../components/Slider'
import StatCard from '../components/StatCard'
import { postBudget } from '../api/client'
import { useSession } from '../store/session'
import { formatAmount, formatBudgetRange, formatPeople } from '../lib/format'
import { buildComposition } from '../lib/composition'
import type { BudgetCompositionItem, BudgetPreview, Scenario } from '../api/types'
import styles from './Budget.module.css'

const SCENARIO_LABEL: Record<Scenario['label'], { title: string; desc: string }> = {
  보수: { title: '보수적 시나리오', desc: '안정적으로 가능한 자금만 반영' },
  적극: { title: '적극적 시나리오', desc: '추가 확보 가능 자금까지 반영' },
}

/** 빠른 선택 지점 — 가동 범위를 4등분한 위치. "추천"은 자금 관련 금지어라 중립 표현을 쓴다. */
const PRESETS: { label: string; ratio: number }[] = [
  { label: '최소', ratio: 0 },
  { label: '보수', ratio: 0.25 },
  { label: '균형', ratio: 0.5 },
  { label: '적극', ratio: 0.75 },
  { label: '최대', ratio: 1 },
]

export default function Budget() {
  const navigate = useNavigate()
  const { sessionId, parsedProfile, selectedScenario, setBudget, bumpVersion } = useSession()

  const scenario = selectedScenario
  const [value, setValue] = useState(() => scenario?.budget ?? 0)
  const [preview, setPreview] = useState<BudgetPreview | null>(null)
  const [pending, setPending] = useState(false)

  const composition = useMemo(
    () => (scenario ? buildComposition(scenario, value) : []),
    [scenario, value],
  )

  const confirm = useCallback(
    async (budget: number, comp: BudgetCompositionItem[]) => {
      if (!scenario) return
      setPending(true)
      try {
        const res = await postBudget(sessionId ?? 'mock', {
          confirmed_budget: budget,
          composition: comp,
        })
        setPreview(res.preview)
        setBudget(res.confirmed_budget, res.preview) // 세션 B₀ — 화면 4의 진실 원천
        bumpVersion() // /recommend·/explore가 공유하는 version 갱신
      } finally {
        setPending(false)
      }
    },
    [scenario, sessionId, setBudget, bumpVersion],
  )

  // 슬라이더 변경 → debounce 300ms 후 예산 확정(B₀ 덮어쓰기, 마지막 값이 확정값).
  useEffect(() => {
    if (!scenario) return
    const t = setTimeout(() => void confirm(value, composition), 300)
    return () => clearTimeout(t)
  }, [scenario, value, composition, confirm])

  // 시나리오를 고르지 않고 직접 들어온 경우 — 화면 2로 되돌린다.
  if (!scenario) return <Navigate to="/scenarios" replace />

  const meta = SCENARIO_LABEL[scenario.label]
  const noCandidate = preview?.area_count === 0

  const aside = null

  return (
    <AppShell activeStep={3} aside={aside}>
      <div className={styles.surface}>
        <div className={styles.header}>
          <div className={styles.titleRow}>
            <h1 className="t-title1">3단계. 예산 선택</h1>
            <Button variant="secondary" size="sm" onClick={() => navigate('/scenarios')}>
              ‹ 이전 단계로 돌아가기
            </Button>
          </div>
          <p className={`t-body ${styles.subtitle}`}>
            조달 시나리오를 바탕으로 설정한 예산 범위 내에서 도달 가능한 입지를 추천해 드립니다.
          </p>
        </div>

        {/* 선택한 시나리오 요약 */}
        <div className={styles.summaryBar}>
          <div className={styles.scenarioCard}>
            <p className={`t-body-strong ${styles.scenarioTitle}`}>{meta.title}</p>
            <p className={`t-caption ${styles.scenarioDesc}`}>{meta.desc}</p>
          </div>
          <StatCard
            icon={Wallet}
            tone="blue"
            label="예상 총 예산 범위"
            value={formatBudgetRange(scenario.budget_min, scenario.budget_max, '')}
          />
          <StatCard
            icon={PiggyBank}
            tone="purple"
            label="자기자본 (확정 재원)"
            value={formatAmount(scenario.budget_min)}
          />
          <StatCard
            icon={CalendarClock}
            tone="green"
            label="월 투자 가능 금액 (운영비 포함)"
            value={
              parsedProfile?.monthly_investable != null
                ? formatAmount(parsedProfile.monthly_investable)
                : '—'
            }
          />
          <Button variant="secondary" size="sm" onClick={() => navigate('/scenarios')}>
            시나리오 다시 보기 ›
          </Button>
        </div>

        {/* 슬라이더 + 안내 패널 */}
        <div className={styles.columns}>
          <div className={styles.selectCard}>
            <p className="t-title2">예산 범위 선택</p>
            <p className={`t-body ${styles.hint}`}>
              슬라이더를 움직여 입지 추천에 사용할 예산을 설정하세요.
            </p>

            <Slider
              min={scenario.budget_min}
              max={scenario.budget_max}
              step={100}
              value={value}
              onChange={setValue}
              format={formatAmount}
              label="입지 추천에 사용할 예산"
            />

            <div className={styles.presets}>
              <span className={`t-label ${styles.presetLabel}`}>빠른 선택</span>
              {PRESETS.map((p) => {
                const target =
                  scenario.budget_min + Math.round((scenario.budget_max - scenario.budget_min) * p.ratio)
                return (
                  <button
                    key={p.label}
                    type="button"
                    className={`t-label ${styles.chip} ${value === target ? styles.chipActive : ''}`}
                    aria-pressed={value === target}
                    onClick={() => setValue(target)}
                  >
                    {p.label}
                  </button>
                )
              })}
            </div>

            <InfoBanner tone="info">
              선택하신 예산은 {meta.title}의 예상 총 예산 범위(
              {formatBudgetRange(scenario.budget_min, scenario.budget_max, '')}) 내 금액입니다. 한도·승인은
              기관 심사 사항입니다.
            </InfoBanner>
          </div>

          <div className={styles.infoColumn}>
            <RailCard
              title="예산에 포함되는 항목"
              items={[
                '시설 투자비 — 임대보증금, 인테리어, 집기/설비 등',
                '초기 운영비 — 초기 재고, 마케팅, 인허가 등',
                '운영비 (3개월) — 임대료, 인건비, 관리비, 공과금 등',
              ]}
            />
            <RailCard
              title="안내 사항"
              items={[
                '선택한 예산 범위 내에서 상권 데이터를 필터링하여 추천 결과가 제공됩니다.',
                '예산을 변경하면 도달 가능한 입지의 수와 범위가 달라질 수 있습니다.',
                '실제 대출 심사는 별도로 진행되며, 본 서비스는 의사결정을 지원하는 참고 자료입니다.',
              ]}
            />
          </div>
        </div>

        {/* 확정 예산 기준 프리뷰 */}
        <p className={`t-title2 ${styles.summaryTitle}`}>선택한 예산 요약</p>
        <div className={styles.summaryStats}>
          <StatCard icon={Wallet} tone="blue" label="확정 예산" value={formatAmount(value)} />
          <StatCard
            icon={MapPin}
            tone="purple"
            label="진입 가능 상권"
            value={preview ? `${preview.area_count}곳` : pending ? '계산 중…' : '—'}
          />
          <StatCard
            icon={Receipt}
            tone="green"
            label="환산 임대료 (월, 추정)"
            value={
              preview?.rent_range
                ? formatBudgetRange(preview.rent_range[0], preview.rent_range[1], '')
                : '—'
            }
            caption={preview?.rent_range ? '진입 후보의 최소~최대' : undefined}
          />
          <StatCard
            icon={Users}
            tone="orange"
            label="유동인구 (일 평균)"
            value={
              preview?.floating_range
                ? `${formatPeople(preview.floating_range[0])} ~ ${formatPeople(preview.floating_range[1])}`
                : '—'
            }
            caption={preview?.floating_range ? '진입 후보의 최소~최대' : undefined}
          />
        </div>

        {/* 상향 유도로 읽히지 않도록 사실만 서술한다 (자금 관련 권유 표현 금지). */}
        {noCandidate && (
          <InfoBanner tone="tip">
            {value >= scenario.budget_max
              ? `현재 시나리오의 최대 예산(${formatAmount(scenario.budget_max)}) 기준으로 진입 가능한 상권이 없습니다. 조달 시나리오를 다시 확인하거나 입력 조건을 조정하면 결과가 달라질 수 있습니다.`
              : '현재 예산 기준으로 진입 가능한 상권이 없습니다. 예산을 조정하면 진입 가능 상권 수가 달라집니다.'}
          </InfoBanner>
        )}

        <p className={`t-caption ${styles.disclaimer}`}>
          ⓘ 본 정보는 공개 자료 기반 정보 제공이며 대출 권유·중개·자문이 아닙니다. 자격 요건 부합 상품을 확인한
          결과이며, 한도·승인은 기관 심사 사항입니다.
        </p>

        <div className={styles.ctaBar}>
          <p className="t-body">
            이제 선택하신 예산 범위 내에서 도달 가능한 입지를 지도에서 확인할 수 있습니다.
          </p>
          <Button variant="primary" size="md" disabled={noCandidate} onClick={() => navigate('/map')}>
            입지 추천 결과 보기 →
          </Button>
        </div>
      </div>
    </AppShell>
  )
}

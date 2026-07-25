import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ShieldCheck, TrendingUp, Landmark, Wallet } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import AppShell from '../components/layout/AppShell'
import Button from '../components/Button'
import InfoBanner from '../components/InfoBanner'
import ScenarioCard from '../components/ScenarioCard'
import FundingItem from '../components/FundingItem'
import { getScenarios } from '../api/client'
import { useSession } from '../store/session'
import { formatBudgetRange } from '../lib/format'
import type { CompositionType, Scenario as ScenarioData } from '../api/types'
import styles from './Scenario.module.css'

const SCENARIO_META: Record<ScenarioData['label'], { icon: LucideIcon; title: string; description: string }> = {
  // "승인" 표현은 용어 컴플라이언스 금지어(CLAUDE.md) — "안정적으로 가능한 자금" 등 정보 서술형으로 대체.
  보수: { icon: ShieldCheck, title: '보수적 시나리오', description: '안정적으로 가능한 자금만 반영' },
  적극: { icon: TrendingUp, title: '적극적 시나리오', description: '추가 확보 가능 자금까지 반영' },
}

const COMPOSITION_ORDER: CompositionType[] = ['policy_loan', 'guarantee', 'equity']
const COMPOSITION_LABEL: Record<CompositionType, string> = {
  policy_loan: '정책자금',
  guarantee: '보증',
  equity: '자기자본',
}
const COMPOSITION_COLOR_VAR: Record<CompositionType, string> = {
  policy_loan: 'var(--color-data-blue)',
  guarantee: 'var(--color-data-green)',
  equity: 'var(--color-data-purple)',
}
const COMPOSITION_ICON: Record<CompositionType, LucideIcon> = {
  policy_loan: Landmark,
  guarantee: ShieldCheck,
  equity: Wallet,
}

/** 상품명 키워드로 아이콘을 고른다 — products[]에 type 필드가 없어 결정적 매칭으로 대체(BE 확인 전 임시). */
function productIcon(name: string): LucideIcon {
  return name.includes('보증') ? ShieldCheck : Landmark
}

export default function Scenario() {
  const navigate = useNavigate()
  const { sessionId, setSelectedScenario } = useSession()
  const [scenarios, setScenarios] = useState<ScenarioData[]>([])
  const [selectedLabel, setSelectedLabel] = useState<ScenarioData['label'] | null>(null)

  useEffect(() => {
    const ac = new AbortController()
    setScenarios([])
    setSelectedLabel(null)
    getScenarios(
      sessionId ?? 'mock',
      (s) => {
        setScenarios((prev) => [...prev, s])
        setSelectedLabel((prev) => prev ?? s.label)
      },
      ac.signal,
    )
    return () => ac.abort()
  }, [sessionId])

  const selected = useMemo(
    () => scenarios.find((s) => s.label === selectedLabel) ?? null,
    [scenarios, selectedLabel],
  )

  const equity = selected?.composition.find((c) => c.type === 'equity')

  return (
    <AppShell activeStep={2}>
      <div className={styles.surface}>
        <div className={styles.header}>
          <div className={styles.titleRow}>
            <h1 className="t-title1">2단계. 조달 시나리오</h1>
            <Button variant="secondary" size="sm" onClick={() => navigate('/')}>
              입력 정보 수정
            </Button>
          </div>
          <p className={`t-body ${styles.subtitle}`}>
            입력하신 정보에 기반하여 활용 가능한 정책자금, 보증, 대출을 매칭하고 보수적·적극적 시나리오별 예산
            범위를 산출해 드립니다.
          </p>
        </div>

        <InfoBanner tone="info">
          모든 금융상품 정보는 공고문 및 공식 자료를 기반으로 제공되며, 자격조건 충족 및 심사 결과에 따라 실제
          지원 가능 금액은 달라질 수 있습니다.
        </InfoBanner>

        <div className={styles.tabs}>
          {(['보수', '적극'] as const).map((label) => {
            const meta = SCENARIO_META[label]
            const available = scenarios.some((s) => s.label === label)
            return (
              <ScenarioCard
                key={label}
                icon={meta.icon}
                title={meta.title}
                description={meta.description}
                active={selectedLabel === label}
                onClick={() => available && setSelectedLabel(label)}
              />
            )
          })}
        </div>

        {!selected ? (
          <p className={`t-body ${styles.loading}`}>시나리오를 불러오는 중…</p>
        ) : (
          <div className={styles.columns} key={selected.label}>
            <div className={styles.budgetCard}>
              <div className={styles.budgetHead}>
                <p className={`t-label ${styles.budgetTitle}`}>
                  예상 총 예산 범위 ({SCENARIO_META[selected.label].title.replace(' 시나리오', '')})
                </p>
                <p className={styles.budgetAmount}>{formatBudgetRange(selected.budget_min, selected.budget_max)}</p>
              </div>
              <div className={styles.gauge}>
                {COMPOSITION_ORDER.map((type) => {
                  const c = selected.composition.find((item) => item.type === type)
                  if (!c) return null
                  return (
                    <span
                      key={type}
                      className={styles.gaugeSeg}
                      style={{ flexGrow: c.amount_max, background: COMPOSITION_COLOR_VAR[type] }}
                      title={`${COMPOSITION_LABEL[type]} ${c.amount_max.toLocaleString('ko-KR')}만원`}
                    />
                  )
                })}
              </div>
              <ul className={styles.legend}>
                {COMPOSITION_ORDER.map((type) => {
                  const c = selected.composition.find((item) => item.type === type)
                  if (!c) return null
                  return (
                    <li key={type} className={styles.legendRow}>
                      <span className={styles.legendLhs}>
                        <span className={styles.legendDot} style={{ background: COMPOSITION_COLOR_VAR[type] }} />
                        <span className="t-body">{COMPOSITION_LABEL[type]}</span>
                      </span>
                      <span className="t-body-strong">
                        {c.amount_min === c.amount_max
                          ? `${c.amount_max.toLocaleString('ko-KR')}만원`
                          : `${c.amount_min.toLocaleString('ko-KR')}~${c.amount_max.toLocaleString('ko-KR')}만원`}
                      </span>
                    </li>
                  )
                })}
              </ul>
              <p className={`t-caption ${styles.footnote}`}>
                ※ 위 금액은 입력하신 정보와 일반적인 상품 조건 기준의 추정치입니다.
              </p>
            </div>

            <div className={styles.breakdown}>
              <p className="t-title2">조달 구성 내역</p>
              {/* 계약(scenarios)에 자격조건 필드가 없어 "자격조건 확인"을 약속하지 않는다. */}
              <p className={`t-caption ${styles.breakdownHint}`}>
                금융상품을 선택하면 한도·금리·출처를 확인할 수 있습니다.
              </p>
              <div className={styles.itemList}>
                {selected.products.map((p) => {
                  // 금리 표시 규칙(API_CONTRACT §금리 표기): rate가 오면 "연 N%", 생략되면 rate_note를 그대로.
                  const rateText = p.rate != null ? `연 ${p.rate}%` : p.rate_note
                  return (
                    <FundingItem
                      key={p.name}
                      icon={productIcon(p.name)}
                      title={p.name.includes('보증') ? '보증' : '정책자금'}
                      subtitle={p.name}
                      value={`최대 ${p.amount_max.toLocaleString('ko-KR')}만원`}
                      source={p.source.org}
                      dataAsOf={p.data_as_of}
                      note={rateText}
                      quote={p.source_quote}
                      details={[
                        { label: '상품명', value: p.name },
                        { label: '한도', value: `최대 ${p.amount_max.toLocaleString('ko-KR')}만원` },
                        ...(rateText ? [{ label: '금리', value: rateText }] : []),
                        { label: '데이터 기준일', value: p.data_as_of },
                        {
                          label: '출처',
                          value: p.source.url ? (
                            <a href={p.source.url} target="_blank" rel="noreferrer noopener">
                              {p.source.org} ↗
                            </a>
                          ) : (
                            p.source.org
                          ),
                        },
                      ]}
                    />
                  )
                })}
                {equity && (
                  <FundingItem
                    icon={COMPOSITION_ICON.equity}
                    title="자기자본"
                    subtitle="입력하신 현금 보유액"
                    value={`${equity.amount_max.toLocaleString('ko-KR')}만원`}
                    note="직접 입력"
                  />
                )}
              </div>
            </div>
          </div>
        )}

        <p className={`t-caption ${styles.disclaimer}`}>
          ⓘ 본 정보는 공개 자료 기반 정보 제공이며 대출 권유·중개·자문이 아닙니다. 자격 요건 부합 상품을 확인한
          결과이며, 한도·승인은 기관 심사 사항입니다.
        </p>

        <div className={styles.ctaBar}>
          <p className="t-body">다음 단계에서 예산 범위를 선택하면, 그 예산 내에서 도달 가능한 입지를 확인할 수 있습니다.</p>
          <Button
            variant="primary"
            size="md"
            disabled={!selected}
            onClick={() => {
              if (!selected) return
              setSelectedScenario(selected) // 화면 3 슬라이더의 가동 범위 근거
              navigate('/budget')
            }}
          >
            예산 선택으로 이동 →
          </Button>
        </div>
      </div>
    </AppShell>
  )
}

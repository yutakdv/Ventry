import { useEffect, useMemo, useState } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { ShieldCheck, Layers, Landmark, Wallet } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import AppShell from '../components/layout/AppShell'
import Button from '../components/Button'
import InfoBanner from '../components/InfoBanner'
import ScenarioCard from '../components/ScenarioCard'
import FundingItem from '../components/FundingItem'
import { getScenarios } from '../api/client'
import { useSession } from '../store/session'
import { formatAmount, formatBudgetRange, formatRate, formatRateNote } from '../lib/format'
import { SESSION_LOST_STATE } from '../lib/sessionLost'
import type { CompositionType, Scenario as ScenarioData } from '../api/types'
import styles from './Scenario.module.css'

/*
 * 두 카드는 **조달 수단의 차이**로 서술한다 — 보수는 보증형만, 적극은 전체 상품을 후보로 본다.
 * 금액의 대소가 아니다: 후보 풀이 달라 실데이터에서 보수 상한(15,000) > 적극 상한(10,000)이
 * 나오기도 한다. "더 큰 예산"을 암시하는 문구·아이콘을 두지 않는다.
 * "승인" 표현도 용어 컴플라이언스 금지어(PROJECT_RULES §2)라 정보 서술형만 쓴다.
 */
const SCENARIO_META: Record<
  ScenarioData['label'],
  { icon: LucideIcon; title: string; description: string; basis: string }
> = {
  보수: {
    icon: ShieldCheck,
    title: '보수적 시나리오',
    description: '보증형 상품만으로 구성',
    basis: '권리금 제외 진입 비용',
  },
  적극: {
    icon: Layers,
    title: '적극적 시나리오',
    description: '정책자금·대출까지 포함해 구성',
    basis: '권리금 포함 진입 비용',
  },
}

/**
 * 카드에 편성된 필요분 = 기본 예산 − 자기자본.
 *
 * 서버가 `budget = 자기자본 + 필요분`, `budget_min = 자기자본`으로 내려주므로 뺄셈으로 나온다
 * (계약 2번 · `ScenarioBuilder`). 새 필드를 요구하지 않고 서버 값에서 파생시킨 것이지
 * 화면이 금액을 만들어 내는 것이 아니다 (§0-1).
 */
function neededAmount(s: ScenarioData): number {
  return Math.max(0, s.budget - s.budget_min)
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
  const { sessionId, setSelectedScenario, retryToken } = useSession()
  const [scenarios, setScenarios] = useState<ScenarioData[]>([])
  const [selectedLabel, setSelectedLabel] = useState<ScenarioData['label'] | null>(null)
  /**
   * 스트림이 끝났는가. 「아직 오는 중」과 「다 왔는데 0건」은 화면이 달라야 한다 —
   * 이것 없이는 서버가 `scenario` 없이 `done`만 보낸 경우 "불러오는 중…"이 영원히 남는다.
   */
  const [streamDone, setStreamDone] = useState(false)

  useEffect(() => {
    /*
     * 세션이 없으면 **조회하지 않는다.** 아래 렌더 분기가 /diagnose 로 돌려보내지만 effect 는
     * 그 전에 한 번 돈다 — 이 화면을 새로고침하거나 주소로 바로 열면 `getScenarios('mock')` 이
     * 404 를 받고 목 폴백 플래그(api/fallback.ts)가 켜졌다. 그 플래그는 **되돌리지 않는 설계**라
     * 이후 처음부터 다시 진행해 실데이터를 받아도 "예시 데이터" 배너가 진짜 수치 위에 남는다.
     * /map·/explore 에 있는 가드가 여기만 빠져 있었다.
     */
    if (!sessionId) return

    const ac = new AbortController()
    setScenarios([])
    setSelectedLabel(null)
    setStreamDone(false)
    getScenarios(
      sessionId,
      (s) => {
        setScenarios((prev) => [...prev, s])
        setSelectedLabel((prev) => prev ?? s.label)
      },
      ac.signal,
      /*
       * 스트림이 끊겨 재연결하거나 목으로 폴백할 때 수신분을 버린다. 재연결은 카드를 처음부터
       * 다시 받으므로 비우지 않으면 중복되고, 폴백은 비우지 않으면 실카드 뒤에 목 카드가
       * 이어 붙어 **보수/적극 두 장이 실·목 혼성**이 된다(배너는 전체를 예시로 고지하므로
       * 진짜 수치까지 예시로 라벨링된다).
       */
      () => {
        if (ac.signal.aborted) return
        setScenarios([])
        setSelectedLabel(null)
      },
    ).finally(() => {
      if (!ac.signal.aborted) setStreamDone(true)
    })
    return () => ac.abort()
    // retryToken: 폴백 배너의 「다시 불러오기」가 세션을 유지한 채 이 조회만 다시 돌린다 (M-14)
  }, [sessionId, retryToken])

  const selected = useMemo(
    () => scenarios.find((s) => s.label === selectedLabel) ?? null,
    [scenarios, selectedLabel],
  )

  const equity = selected?.composition.find((c) => c.type === 'equity')

  // 세션이 사라진 이유를 첫 화면이 설명할 수 있도록 state 를 실어 보낸다 (M-13)
  if (!sessionId) return <Navigate to="/diagnose" replace state={SESSION_LOST_STATE} />

  return (
    <AppShell activeStep={2}>
      <div className={styles.surface}>
        <div className={styles.header}>
          <div className={styles.titleRow}>
            <h1 className="t-title1">2단계. 조달 시나리오</h1>
            <Button variant="secondary" size="sm" onClick={() => navigate('/diagnose')}>
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
                /* 아직 도착하지 않은 카드는 눌러도 아무 일이 없었다 — 그 사실을 화면에 밝힌다. */
                disabled={!available}
                onClick={() => available && setSelectedLabel(label)}
              />
            )
          })}
        </div>

        {!selected ? (
          streamDone ? (
            <div className={styles.loading}>
              <p className="t-body">
                입력하신 조건으로 편성 가능한 조달 시나리오를 만들지 못했습니다. 입력 정보를 수정하면
                다시 계산됩니다.
              </p>
              <div className={styles.emptyAction}>
                <Button variant="secondary" size="md" onClick={() => navigate('/diagnose')}>
                  입력 정보 수정
                </Button>
              </div>
            </div>
          ) : (
            <p className={`t-body ${styles.loading}`}>시나리오를 불러오는 중…</p>
          )
        ) : (
          <div className={styles.columns} key={selected.label}>
            <div className={styles.budgetCard}>
              <div className={styles.budgetHead}>
                <p className={`t-label ${styles.budgetTitle}`}>
                  예상 총 예산 범위 ({SCENARIO_META[selected.label].title.replace(' 시나리오', '')})
                </p>
                <p className={styles.budgetAmount}>{formatBudgetRange(selected.budget_min, selected.budget_max)}</p>
                {/*
                  상한이 카드 간에 역전될 수 있다 — 편성 규칙이 「한도 최대」가 아니라
                  「필요분을 덮는 최소 한도」라, 후보 풀이 넓은 적극 쪽이 더 잘 맞는(=더 작은)
                  상품을 고르는 경우가 있기 때문이다 (DECISIONS §13-3). 이유를 화면에 밝히지 않으면
                  "적극인데 왜 상한이 작은가"가 결함으로 읽힌다.
                */}
                <p className={`t-caption ${styles.budgetBasis}`}>
                  {SCENARIO_META[selected.label].basis} 기준 필요분{' '}
                  <strong>{formatAmount(neededAmount(selected))}</strong>을 덮는{' '}
                  <strong>최소 한도</strong> 상품이 편성됩니다. 상한은 그 상품의 공고상 한도이므로
                  두 시나리오의 크기를 비교하는 값이 아닙니다.
                </p>
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
                  const rateText = formatRate(p)
                  const rateNote = formatRateNote(p)
                  return (
                    <FundingItem
                      key={p.name}
                      icon={productIcon(p.name)}
                      title={p.name.includes('보증') ? '보증' : '정책자금'}
                      subtitle={p.name}
                      value={`최대 ${p.amount_max.toLocaleString('ko-KR')}만원`}
                      source={p.source.org}
                      dataAsOf={p.data_as_of}
                      note={rateText ?? undefined}
                      details={[
                        { label: '상품명', value: p.name },
                        { label: '한도', value: `최대 ${p.amount_max.toLocaleString('ko-KR')}만원` },
                        /*
                          `rate` 가 없으면 이 자리에 오는 것은 이율이 아니라 **공고 원문의 금리
                          조건 문장**이다. 실데이터에는 보증료율로 시작하는 원문도 있어(F-010·F-011
                          「보증료 연 0.8%~1.0%. 대출금리 …」) 「금리」라는 라벨 아래 놓이면
                          보증료를 이율로 오독할 수 있다 (QA 리뷰 Q-08). 원문은 그대로 두고
                          — 재작성은 §5-4 위반이다 — **라벨만** 사실에 맞춘다.
                        */
                        ...(rateText
                          ? [{ label: p.rate != null ? '금리' : '금리 조건', value: rateText }]
                          : []),
                        ...(rateNote ? [{ label: '적용 조건', value: rateNote }] : []),
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

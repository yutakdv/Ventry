import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { Wallet, PiggyBank, CalendarClock, MapPin, Receipt, Users } from 'lucide-react'
import AppShell from '../components/layout/AppShell'
import RailCard from '../components/layout/RailCard'
import Button from '../components/Button'
import InfoBanner from '../components/InfoBanner'
import Slider from '../components/Slider'
import StatCard from '../components/StatCard'
import { isSessionGone, postBudget } from '../api/client'
import { useSession } from '../store/session'
import { formatAmount, formatBudgetRange, formatPeople } from '../lib/format'
import { SESSION_LOST_STATE } from '../lib/sessionLost'
import { rentAreaShort, rentAreaBasis } from '../lib/rentArea'
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
  const {
    sessionId,
    parsedProfile,
    selectedScenario,
    budget: sessionBudget,
    setBudget,
    bumpVersion,
  } = useSession()

  const scenario = selectedScenario
  /** 임대료 금액의 면적 조건 (이슈 #151) — 라벨엔 ㎡만, 근거 줄엔 평까지. */
  const rentUnit = rentAreaShort(parsedProfile?.industry)
  const rentBasis = rentAreaBasis(parsedProfile?.industry)
  const [value, setValue] = useState(() => scenario?.budget ?? 0)
  const [preview, setPreview] = useState<BudgetPreview | null>(null)
  const [dataAsOf, setDataAsOf] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  /** 서버에 세션이 없다(404) — 첫 화면으로 돌려보낸다. */
  const [sessionGone, setSessionGone] = useState(false)
  /**
   * 요청 일련번호.
   *
   * debounce(300ms)는 요청 **수**를 줄일 뿐 도착 **순서**를 보장하지 않는다. `/budget` 응답이
   * 300ms보다 오래 걸리면 두 요청이 동시에 떠 있게 되고, 먼저 보낸 쪽이 나중에 도착하면
   * 사용자가 방금 고른 예산의 프리뷰와 세션 확정값이 **이전 예산의 것으로 덮인다**.
   * 마지막으로 보낸 요청의 응답만 반영한다.
   */
  const seqRef = useRef(0)
  /**
   * 진행 중인 `/budget` 요청. 새 요청을 보내기 전에 앞선 것을 **실제로 끊는다** (QA 리뷰 Q-06).
   *
   * 일련번호만으로도 화면이 구 응답에 덮이는 일은 막히지만, 느린 회선에서는 debounce 창마다
   * 요청이 하나씩 쌓여 **동시에 떠 있는 수가 늘어난다** — 각각이 톰캣 워커와 프리뷰 조회를
   * 붙잡는다. 취소는 그 누적을 1건으로 묶는다.
   *
   * ⚠️ 서버 쪽 도착 순서까지 보장하는 것은 아니다. B₀ 는 **마지막으로 도착한** 요청이 이기므로,
   * 끊긴 요청이 서버에 먼저 닿아 있었다면 그 값이 남을 수 있다. 다만 슬라이더가 멈추면 마지막
   * 값으로 한 번 더 확정되고, 화면이 읽는 값은 항상 서버 응답(`res.confirmed_budget`)이라
   * 화면과 세션이 어긋난 채로 남지는 않는다.
   */
  const inFlightRef = useRef<AbortController | null>(null)

  const composition = useMemo(
    () => (scenario ? buildComposition(scenario, value) : []),
    [scenario, value],
  )

  const confirm = useCallback(
    async (budget: number, comp: BudgetCompositionItem[]) => {
      if (!scenario || !sessionId) return
      const seq = (seqRef.current += 1)
      inFlightRef.current?.abort()
      const ac = new AbortController()
      inFlightRef.current = ac
      setPending(true)
      try {
        const res = await postBudget(
          sessionId,
          { confirmed_budget: budget, composition: comp },
          ac.signal,
        )
        if (seq !== seqRef.current) return // 더 나중에 보낸 요청이 있다 — 이 응답은 버린다
        setPreview(res.preview)
        setDataAsOf(res.data_as_of)
        setBudget(res.confirmed_budget, res.preview, res.data_as_of) // 세션 B₀ — 화면 4의 진실 원천
        bumpVersion() // /recommend·/explore가 공유하는 version 갱신
      } catch (e) {
        // 취소(새 요청이 앞선 것을 끊었다)와 **4xx** 가 온다. 장애는 client.ts 가 목으로 흡수한다.
        // 세션이 서버에 없으면 첫 화면으로 — 없는 세션의 예산을 확정한 척하지 않는다.
        if (isSessionGone(e)) setSessionGone(true)
      } finally {
        // 뒤늦게 끝난 구 요청이 "계산 중"을 먼저 꺼 버리지 않게 한다.
        if (seq === seqRef.current) setPending(false)
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

  // 세션·시나리오 없이 직접 들어온 경우 — 앞 단계로 되돌린다.
  // 세션이 사라진 이유를 첫 화면이 설명할 수 있도록 state 를 실어 보낸다 (M-13)
  if (!sessionId || sessionGone) return <Navigate to="/diagnose" replace state={SESSION_LOST_STATE} />
  if (!scenario) return <Navigate to="/scenarios" replace />

  const meta = SCENARIO_LABEL[scenario.label]
  const noCandidate = preview?.area_count === 0
  /*
   * 확정이 아직 한 번도 끝나지 않았으면 세션 예산이 없다. 그 상태로 /map 에 보내면 화면 4가
   * 곧바로 여기로 되돌려 보내, 사용자에게는 CTA 를 눌러도 아무 일이 없는 것처럼 보인다
   * (진입 직후 debounce 300ms + 응답 시간 동안 실제로 그랬다).
   */
  const budgetNotReady = sessionBudget == null

  return (
    <AppShell activeStep={3}>
      <div className={styles.surface}>
        <div className={styles.header}>
          <div className={styles.titleRow}>
            <h1 className="t-title1">3단계. 예산 선택</h1>
            <Button variant="secondary" size="sm" onClick={() => navigate('/scenarios')}>
              ‹ 이전 단계로 돌아가기
            </Button>
          </div>
          <p className={`t-body ${styles.subtitle}`}>
            조달 시나리오를 바탕으로 설정한 예산 범위 내에서 도달 가능한 입지를 확인할 수 있습니다.
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
                /*
                 * 슬라이더 눈금(100만원) 위로 올린다 (실사용 점검 2026-07-29).
                 * 격자를 벗어난 값이면 칩을 누른 뒤 슬라이더를 잡는 순간 값이 스냅해
                 * 선택 표시(`value === target`)가 풀린다 — 방금 고른 칩이 꺼져 보인다.
                 */
                const raw =
                  scenario.budget_min + Math.round((scenario.budget_max - scenario.budget_min) * p.ratio)
                const target = Math.min(
                  scenario.budget_max,
                  scenario.budget_min + Math.round((raw - scenario.budget_min) / 100) * 100,
                )
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
            label={`환산 임대료 (월${rentUnit ? `, ${rentUnit}` : ', 추정'})`}
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

        {/*
          출처는 카드 한 장이 아니라 위 수치 전체에 걸린다. 카드 캡션에 넣으면 그 카드만
          두 줄이 되어 4장의 리듬이 깨지므로 묶어서 아래 한 줄로 낸다.

          기준일은 후보가 0곳이어도 낸다 — 「0곳」 역시 그 기준일의 데이터가 만든 판정이다.
          반면 임대료·유동인구 출처 문장은 보여줄 수치가 있을 때만 붙인다.
        */}
        {dataAsOf && (
          <p className={`t-caption ${styles.previewSource}`}>
            {preview?.rent_range &&
              `임대료는 한국부동산원 상권 분기 평균(추정)${rentBasis ? ` · ${rentBasis}` : ''}, 유동인구는 서울 열린데이터광장 분기 집계입니다. `}
            데이터 기준일 {dataAsOf}.
          </p>
        )}

        {/* 상향 유도로 읽히지 않도록 사실만 서술한다 (자금 관련 권유 표현 금지). */}
        {noCandidate && (
          <InfoBanner tone="tip">
            {value >= scenario.budget_max
              ? `현재 시나리오의 최대 예산(${formatAmount(scenario.budget_max)}) 기준으로 진입 가능한 상권이 없습니다. 조달 시나리오를 다시 확인하거나 입력 조건을 조정하면 결과가 달라질 수 있습니다.`
              : '현재 예산 기준으로 진입 가능한 상권이 없습니다. 예산을 조정하면 진입 가능 상권 수가 달라집니다.'}
          </InfoBanner>
        )}

        {/*
          기준일은 서버가 준 `data_as_of` 를 그대로 쓴다. 화면이 날짜를 지어내지 않으므로
          응답에 없으면 줄 자체를 내지 않는다 (계약 D9 이전 서버와 붙어도 거짓 표기가 없다).
        */}
        <p className={`t-caption ${styles.disclaimer}`}>
          ⓘ 본 정보는 공개 자료 기반 정보 제공이며 대출 권유·중개·자문이 아닙니다. 자격 요건 부합 상품을 확인한
          결과이며, 한도·승인은 기관 심사 사항입니다.
        </p>

        <div className={styles.ctaBar}>
          {/*
            버튼이 꺼져 있는데 안내문은 「확인할 수 있습니다」라고 말하던 자리
            (실사용 점검 2026-07-29). 빠른 선택 「최소」·「보수」가 실제로 0곳이라
            심사자가 칩을 고른 직후 이 모순을 본다. 상태에 맞는 문장으로 가른다.
          */}
          <p className="t-body">
            {noCandidate
              ? '진입 가능한 상권이 0곳이라 다음 단계로 넘어갈 수 없습니다. 슬라이더나 빠른 선택으로 예산을 올리면 진입 가능 상권 수가 달라집니다.'
              : '이제 선택하신 예산 범위 내에서 도달 가능한 입지를 지도에서 확인할 수 있습니다.'}
          </p>
          <Button
            variant="primary"
            size="md"
            disabled={noCandidate || budgetNotReady}
            onClick={() => navigate('/map')}
          >
            {budgetNotReady ? '예산 계산 중…' : '입지 추천 결과 보기 →'}
          </Button>
        </div>
      </div>
    </AppShell>
  )
}

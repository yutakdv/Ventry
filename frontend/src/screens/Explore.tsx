import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import AppShell from '../components/layout/AppShell'
import Button from '../components/Button'
import FrontierChart from '../components/FrontierChart'
import ScenarioRow from '../components/ScenarioRow'
import ExploreSummary from './ExploreSummary'
import SseEventLog from './SseEventLog'
import { getExplore, postBudget } from '../api/client'
import { useSession, type ExploreCache } from '../store/session'
import { buildComposition } from '../lib/composition'
import { SESSION_LOST_STATE } from '../lib/sessionLost'
import { formatAmount } from '../lib/format'
import type { ExploreInsightEvent } from '../api/types'
import styles from './Explore.module.css'

/** 인사이트 정렬 — T1(기회 경계)을 위로, 같은 타입이면 도착 순서를 지킨다. */
function orderInsights(list: ExploreInsightEvent[]): ExploreInsightEvent[] {
  return [...list].sort((a, b) => (a.type === 'T2' ? 1 : 0) - (b.type === 'T2' ? 1 : 0))
}

/** 되돌리기(기준 예산 복귀)에는 인사이트 id 가 없다 — 적용 중 표시를 위한 자리표시자. */
const REVERT_KEY = '__revert__'

/** 인사이트를 적용했을 때의 예산 — 항상 **기준 예산 기준**이라 갈아타기가 누적되지 않는다. */
function budgetFor(insight: ExploreInsightEvent, base: number): number {
  if (insight.type === 'T2') return insight.gap_amount ?? base
  return base + (insight.gap_amount ?? 0)
}

export default function Explore() {
  const navigate = useNavigate()
  const {
    sessionId,
    version,
    budget,
    baseBudget,
    selectedScenario,
    dataAsOf,
    explore,
    setExplore,
    applyExploreBudget,
    bumpVersion,
    retryToken,
  } = useSession()

  const [loading, setLoading] = useState(false)
  /**
   * 적용 중인 인사이트 id. 되돌리기는 `REVERT_KEY` 를 쓴다 — 불리언 한 개였을 때는
   * 어느 행을 눌렀는지 화면이 말해 주지 못했다 (FE 리뷰 m-3).
   */
  const [applyingId, setApplyingId] = useState<string | null>(null)
  const busy = applyingId != null
  const abortRef = useRef<AbortController | null>(null)
  /*
   * 스트리밍 도중 캐시가 갱신되면 effect 의존성이 바뀌어 cleanup(abort)이 돌고 SSE가 끊긴다.
   * 실행 여부는 ref로만 판단해 의존성에서 상태를 뺀다. version도 요청 시점 값만 필요하다.
   */
  const runKeyRef = useRef<string | null>(null)
  const exploreRef = useRef(explore)
  exploreRef.current = explore
  const versionRef = useRef(version)
  versionRef.current = version
  /*
   * `budget` 도 같은 이유로 ref 다. 인사이트를 적용하면 세션 예산이 바뀌는데, 그것이 의존성에
   * 남아 있으면 effect 가 다시 돌면서 cleanup 이 **진행 중인 SSE 를 끊는다**. 재실행은 캐시
   * 가드에 걸려 조기 return 하므로 스트림이 다시 시작되지도 않아, `done`·`refine` 이 유실되고
   * 프론티어 차트가 빈 채로 고정됐다(첫 인사이트가 오자마자 적용을 누르면 재현). effect 가
   * `budget` 에서 필요한 것은 진입 시점의 null 여부뿐이라 값은 ref 로 읽는다.
   */
  const budgetRef = useRef(budget)
  budgetRef.current = budget

  const effectiveBudget = budget ?? 0
  const base = baseBudget ?? effectiveBudget
  const cached = explore && explore.baseBudget === base ? explore : null

  /*
   * 탐색은 기준 예산당 한 번만 돌린다.
   * 인사이트를 적용하면 세션 예산이 바뀌지만 여기서 재실행하지 않는다 — 재실행하면 방금 고른
   * 선택지들이 사라져 다른 시나리오로 갈아탈 수 없게 된다. 기준 예산이 바뀔 때만(화면 3에서
   * 재확정) 캐시가 비워지고 다시 돈다.
   */
  useEffect(() => {
    if (!sessionId || budgetRef.current == null) return
    // 이미 이 기준 예산으로 돌았거나 도는 중이면 재실행하지 않는다.
    // retryToken 을 키에 넣어야 「다시 불러오기」가 이 가드를 넘는다 — 토큰이 바뀔 때
    // `retryFetch` 가 탐색 캐시도 함께 비우므로 아래 캐시 가드도 통과한다 (M-14).
    const key = `${sessionId}:${base}:${retryToken}`
    if (runKeyRef.current === key || exploreRef.current?.baseBudget === base) {
      // 스트림을 새로 시작하지 않는 경로다 — 로딩 표시를 켠 채로 두면 "↻ 시나리오를 받는 중…"이
      // 영영 남는다. 이미 false 면 React 가 리렌더를 생략하므로 무해하다.
      setLoading(false)
      return
    }
    runKeyRef.current = key

    abortRef.current?.abort()
    const ac = new AbortController()
    abortRef.current = ac
    setLoading(true)

    const draft: ExploreCache = {
      baseBudget: base,
      plan: null,
      insights: [],
      done: null,
      log: [],
      appliedId: null,
    }
    const flush = () => setExplore({ ...draft, insights: [...draft.insights], log: [...draft.log] })

    getExplore(
      sessionId,
      versionRef.current,
      {
        onPlan: (e) => {
          if (ac.signal.aborted) return
          draft.plan = e
          draft.log.push({
            name: 'plan',
            detail: `axes=[${e.axes.join(', ')}] · axis_labels{${Object.values(e.axis_labels).join(', ')}}`,
          })
          flush()
        },
        onInsight: (e) => {
          if (ac.signal.aborted) return
          draft.insights.push(e)
          draft.log.push({
            name: 'insight',
            detail:
              `${e.insight_id} ${e.type}` +
              (e.gap_amount != null ? ` · gap_amount ${e.gap_amount.toLocaleString('ko-KR')}만` : '') +
              ` · delta{${e.delta.n_entry_before} → ${e.delta.n_entry_after}` +
              (e.delta.n_sustain_after != null ? ` · 지속 ${e.delta.n_sustain_after}` : '') +
              '}',
          })
          flush()
        },
        onRefine: (e) => {
          if (ac.signal.aborted) return
          draft.insights = draft.insights.map((i) =>
            i.insight_id === e.insight_id ? { ...i, headline: e.headline } : i,
          )
          draft.log.push({ name: 'refine', detail: `${e.insight_id} · 문장 교체` })
          flush()
        },
        onDone: (e) => {
          if (ac.signal.aborted) return
          draft.done = e
          draft.log.push({
            name: 'done',
            detail: `scenarios_explored ${e.scenarios_explored} · frontier_points ${e.frontier_points.length} · current_budget ${e.current_budget.toLocaleString('ko-KR')}만`,
          })
          flush()
        },
        /*
         * 스트림 단절 시 수신분을 버린다 — 재연결은 plan 부터 다시 오므로 비우지 않으면
         * 인사이트가 중복되고, 목 폴백은 비우지 않으면 실인사이트와 목 인사이트가 한 목록에
         * 섞여 프론티어 차트와 목록이 서로 다른 계산을 말하게 된다.
         */
        onReset: () => {
          if (ac.signal.aborted) return
          draft.plan = null
          draft.insights = []
          draft.done = null
          draft.log = [{ name: 'reset', detail: '스트림 단절 — 수신분을 비우고 다시 받는다' }]
          draft.appliedId = null
          flush()
        },
      },
      base,
      ac.signal,
    ).finally(() => {
      if (!ac.signal.aborted) setLoading(false)
    })

    return () => {
      ac.abort()
      /*
       * 가드를 반드시 풀어 준다. StrictMode는 effect를 두 번 돌리는데, 첫 실행의 cleanup이
       * SSE를 끊은 뒤 두 번째 실행이 가드에 막히면 스트림이 영영 시작되지 않는다(목록 0건).
       */
      if (runKeyRef.current === key) runKeyRef.current = null
    }
  }, [sessionId, base, setExplore, retryToken])

  /** 프론티어 계단에서 특정 예산의 진입 후보 수 — 요약 스트립 기준값. */
  const frontierAt = useCallback(
    (b: number): number | null => {
      const pts = cached?.done?.frontier_points
      if (!pts || pts.length === 0) return null
      /*
       * 첫 계단점보다 낮은 예산이면 **답이 없다**. reduce 의 초기값이 `pts[0]` 이라 그대로 두면
       * 해당 예산보다 높은 계단의 후보 수가 그 예산의 값인 것처럼 표시된다 — T2(하향)를 적용해
       * 예산이 프론티어 최저점 아래로 내려가는 경우가 그 자리다 (계약 리뷰 P2-3).
       * 화면이 없는 수치를 만들지 않는다는 원칙(§0-1)에 따라 null(= "—")을 준다.
       */
      if (b < pts[0][0]) return null
      return pts.reduce((acc, p) => (p[0] <= b ? p : acc), pts[0])[1]
    },
    [cached],
  )

  const ordered = useMemo(() => orderInsights(cached?.insights ?? []), [cached])

  /**
   * 강조는 1건만 — 진입 증가폭이 가장 큰 T1. "추천" 문구 없이 시각 위계로만 표시한다.
   *
   * **지속 가능 후보가 늘지 않는 행은 강조하지 않는다** (실사용 점검 2026-07-29).
   * 실측에서 i-1(+5,968만원 → 진입 1,014곳·지속 196곳)과 i-2(+6,120만원 → 진입 1,018곳·
   * 지속 **196곳**)가 나왔고, 배지는 i-2에 붙었다. 152만원을 더 내서 진입이 4곳 열리지만
   * **버틸 수 있는 후보는 한 곳도 늘지 않는** 선택지가 「임팩트 최대」로 보인 것이다.
   * 이 서비스의 명제가 「진입」이 아니라 「지속」이므로 그 방향으로 강조를 건다. 상향
   * 인사이트를 단독으로 부각하지 않는다는 용어 컴플라이언스와도 방향이 같다.
   *
   * 계약에 `n_sustain_before` 가 없어 Δ지속을 직접 못 구하므로, **지속이 진입 증가에 전혀
   * 기여하지 않는 행**(지속 후보가 그 축의 최대치보다 작지 않은데 진입만 큰 경우)을 가리는
   * 대신 보수적으로 간다 — 후보군 중 지속 후보 수가 최대인 행들로 먼저 좁히고, 그 안에서
   * 진입 증가폭이 가장 큰 것을 고른다. 지속이 같으면 종전과 같은 결과가 나오고, 지속이
   * 적은 행이 진입만으로 배지를 가져가는 경우가 사라진다.
   */
  const highlightId = useMemo(() => {
    const t1 = ordered.filter((i) => i.type !== 'T2')
    if (t1.length === 0) return null
    // `n_sustain_after` 는 계약상 선택 필드다. 없는 축이 섞이면 비교가 성립하지 않으므로,
    // 전건이 값을 가진 경우에만 지속으로 좁히고 아니면 종전대로 진입 증가폭만 본다.
    const sustains = t1.map((i) => i.delta.n_sustain_after)
    const hasSustain = sustains.every((s): s is number => s != null)
    const maxSustain = hasSustain ? Math.max(...sustains) : null
    const best =
      maxSustain == null ? t1 : t1.filter((i) => i.delta.n_sustain_after === maxSustain)
    return best.reduce((acc, cur) =>
      cur.delta.n_entry_after - cur.delta.n_entry_before >
      acc.delta.n_entry_after - acc.delta.n_entry_before
        ? cur
        : acc,
    ).insight_id
  }, [ordered])

  const appliedInsight = ordered.find((i) => i.insight_id === cached?.appliedId) ?? null

  /**
   * 지금 반영돼 있는 행.
   *
   * 명시적으로 적용한 게 있으면 그것이고, 없으면 **적용해도 예산이 그대로인 행**이 현재 상태다.
   * 실데이터의 T2(안전 마진)가 여기 해당한다 — 하한 금액(`gap_amount`)이 응답에 없어 기준 예산과
   * 같은 값으로 계산되므로, 사용자 눈에는 이미 그 상태다. 아무것도 적용 중이 아니라고 표시하면
   * 화면이 거짓말을 한다. BE가 하한 금액을 실어 보내기 시작하면 이 판정은 저절로 풀린다.
   */
  const activeId =
    cached?.appliedId ??
    ordered.find((i) => budgetFor(i, base) === effectiveBudget)?.insight_id ??
    null

  /** 적용 중인 행은 맨 위로 분리하고, 나머지는 대안 목록으로 남긴다. */
  const activeRow = ordered.find((i) => i.insight_id === activeId) ?? null
  const alternatives = ordered.filter((i) => i.insight_id !== activeId)

  /**
   * 예산 적용/되돌리기 — 진실 원천은 세션 하나이므로 POST /budget으로 덮어쓴다.
   * 이미 적용 중이어도 기준 예산에서 다시 계산하므로 갈아타기가 누적되지 않는다.
   */
  const changeBudget = useCallback(
    async (next: number, appliedId: string | null) => {
      if (!sessionId || !selectedScenario || next === effectiveBudget) return
      setApplyingId(appliedId ?? REVERT_KEY)
      try {
        const res = await postBudget(sessionId, {
          confirmed_budget: next,
          /*
           * 배분 규칙은 계약이 못 박은 하나뿐이다(API_CONTRACT §2 D12 — 자기자본 우선, 이후
           * `amount_max` 한도까지 순서대로). 여기서만 직접 계산하던 구 구현은 비자기자본 항목
           * **전부에** 잔액을 한도 없이 똑같이 넣어, 같은 금액인데 화면 2·3과 다른 구성을 보냈다.
           * 이 구성은 표기용이 아니라 잔여 한도 계산의 입력이라 T1 의 근거 상품이 조용히 갈린다.
           */
          composition: buildComposition(selectedScenario, next),
        })
        applyExploreBudget(next, appliedId, res.preview, res.data_as_of)
        bumpVersion() // recommend가 공유하는 version
      } finally {
        setApplyingId(null)
      }
    },
    [sessionId, selectedScenario, effectiveBudget, applyExploreBudget, bumpVersion],
  )

  // 세션이 사라진 이유를 첫 화면이 설명할 수 있도록 state 를 실어 보낸다 (M-13)
  if (!sessionId) return <Navigate to="/diagnose" replace state={SESSION_LOST_STATE} />
  if (budget == null) return <Navigate to="/budget" replace />

  const plan = cached?.plan
  const planLine = plan
    ? `탐색 계획: ${plan.rationale} (탐색 축: ${plan.axes.map((a) => plan.axis_labels[a] ?? a).join(', ')})`
    : '탐색 계획을 세우는 중입니다…'

  // wide: 사이드바를 접고 차트+시나리오가 전폭을 쓴다 (실사용 점검 2026-07-29)
  return (
    <AppShell activeStep={4} wide>
      <div className={styles.surface}>
        <div className={styles.header}>
          <div className={styles.titleRow}>
            <h1 className="t-title1">결정공간 탐색</h1>
            <Button variant="secondary" size="sm" onClick={() => navigate('/map')}>
              ‹ 입지 추천으로
            </Button>
          </div>
          <p className={`t-body ${styles.subtitle}`}>
            예산과 도달 가능한 입지의 트레이드오프를 탐색합니다. 시나리오는 언제든 다른 것으로 바꾸거나
            기준 예산으로 되돌릴 수 있습니다.
          </p>
        </div>

        {/* plan 근거 1줄 — LLM이 판단했음이 문면에 남게 상시 표기 (스펙 §0-8) */}
        <p className={`t-body ${styles.planBanner}`}>
          <span className={styles.planDot} aria-hidden />
          {planLine}
        </p>

        {/*
          `dataAsOf` — 기준일은 화면이 지어내지 않는다. `/explore` 응답에는 기준일이 없어서
          이 화면이 「데이터 기준일 상시 표기」(스펙 §0-4)를 못 지키는 유일한 자리였다.
          `POST /budget` 응답이 D9 에서 실어 보내기 시작한 값을 세션이 그대로 옮겨 온다.
        */}
        {/*
          지속·상환 지표는 `appliedInsight` 가 아니라 `activeRow` 를 본다
          (실사용 점검 2026-07-29). `appliedInsight` 는 **명시적으로 적용한 것**만 잡아서,
          적용 전에는 항상 null → 요약 배지가 「지속 가능 후보 —」 인데 바로 아래 카드들은
          227·308·307곳을 적고 있었다. 같은 지표가 한 화면에서 두 값으로 보인 것이다.
          더 나쁘게는 아래 목록이 `activeId` 로 「적용 중」 칩을 달 수 있어 **「적용 중」과
          「—」가 동시에** 떴다. 화면의 다른 부분이 이미 `activeRow` 를 쓰므로 여기만 맞춘다.
        */}
        <ExploreSummary
          budget={effectiveBudget}
          baseBudget={base}
          entryCount={frontierAt(effectiveBudget)}
          baseEntryCount={frontierAt(base)}
          sustainCount={activeRow?.delta.n_sustain_after ?? null}
          monthlyPayment={activeRow?.marginal_payment ?? null}
          dataAsOf={dataAsOf ?? undefined}
          onOpenMap={() => navigate('/map')}
          onRevert={() => void changeBudget(base, null)}
        />

        <div className={styles.columns}>
          <section className={styles.frontierCard} aria-label="예산-입지 프론티어">
            <h2 className="t-title2">예산-입지 프론티어</h2>
            <p className={`t-caption ${styles.frontierCaption}`}>
              가로: 총 예산(만원) · 세로: 진입 가능 상권 수. 예산이 임계를 넘는 순간 후보가 계단식으로
              열립니다.
            </p>
            <FrontierChart
              points={cached?.done?.frontier_points ?? []}
              currentBudget={effectiveBudget}
            />
            {cached?.done && (
              <p className={`t-caption ${styles.doneSummary}`}>
                ✓ 탐색 완료 · {cached.done.scenarios_explored}개 시나리오 분석됨
              </p>
            )}
          </section>

          {/*
            계산 로그는 차트와 같은 열에 둔다 — 둘 다 "어떻게 계산했는가"를 말하는 근거이고,
            시나리오 목록보다 짧은 좌측 열의 빈 공간을 메운다.
          */}
          <div className={styles.evidenceCol}>
            <SseEventLog entries={cached?.log ?? []} done={!!cached?.done} />
          </div>

          <section className={styles.stream} aria-label="탐색 시나리오">
            {/*
              적용 중인 시나리오는 목록 위 별도 블록으로 뺀다 — "지금 쓰고 있는 것"과
              "바꿔 볼 수 있는 것"은 성격이 달라 같은 목록에 섞으면 매번 찾아야 한다.
            */}
            {activeRow && (
              <div className={styles.activeBlock}>
                <h2 className={`t-title2 ${styles.activeTitle}`}>선택된 시나리오</h2>
                <ScenarioRow
                  key={activeRow.insight_id}
                  insight={activeRow}
                  highlight={activeRow.insight_id === highlightId}
                  applied
                  applying={applyingId === activeRow.insight_id}
                  busy={busy}
                  onApply={() => void changeBudget(budgetFor(activeRow, base), activeRow.insight_id)}
                />
              </div>
            )}

            <div className={styles.streamHeader}>
              <h2 className="t-title2">AI 탐색 시나리오</h2>
              {loading && (
                <span className={`t-caption ${styles.liveTag}`}>
                  <span className={styles.liveDot} aria-hidden /> 실시간 분석
                </span>
              )}
              {ordered.length > 0 && (
                <span className={`t-caption ${styles.countBadge}`}>탐색 인사이트 {ordered.length}건</span>
              )}
            </div>

            {ordered.length > 0 && (
              <p className={`t-caption ${styles.baselineNote}`}>
                {/*
                  「현재」를 쓰지 않는다 (실사용 점검 2026-07-29). 이 문장과 화면 최하단 고지가
                  둘 다 「현재」로 시작하면서 **서로 다른 금액**(적용 예산 / 기준 예산)을 가리켜,
                  한 화면에서 같은 낱말이 두 뜻이 됐다. 두 금액을 각각 「적용 예산」·「기준 예산」
                  으로 불러 이름과 값이 1:1이 되게 한다.
                */}
                {appliedInsight
                  ? `적용 예산 ${formatAmount(effectiveBudget)} 기준으로 결과가 갱신돼 있습니다. 다른 시나리오를 누르면 기준 예산에서 다시 계산됩니다.`
                  : `기준 예산 ${formatAmount(base)} 기준입니다. 다른 시나리오를 적용하면 예산과 결과가 갱신됩니다.`}
              </p>
            )}

            {ordered.length === 0 ? (
              <p className={`t-body ${loading ? styles.loading : styles.empty}`}>
                {loading
                  ? '결정 지형을 계산하는 중입니다…'
                  : '인접 시나리오를 전 구간 검토했으나 현 조건 대비 유의미한 대안이 없습니다. 현재 예산은 안정 구간입니다.'}
              </p>
            ) : (
              alternatives.map((insight) => (
                <ScenarioRow
                  key={insight.insight_id}
                  insight={insight}
                  highlight={insight.insight_id === highlightId}
                  applying={applyingId === insight.insight_id}
                  busy={busy}
                  onApply={() => void changeBudget(budgetFor(insight, base), insight.insight_id)}
                />
              ))
            )}

            {/*
              「문장을 다듬는 중」이라고 쓰지 않는다. 서버가 `refine` 을 송출하게 된 뒤에도
              그렇다 — 계약이 refine 을 **선택적 이벤트**로 규정하므로 무LLM 스택에서는 오지
              않고, 온다 해도 이 문구가 떠 있는 시간의 대부분은 인사이트 수신 구간이다.
              일어날지 모르는 일을 예고하면 안 온 쪽이 고장으로 읽힌다
              (docs/QA_REPORT_INTEGRATION_CM.md F-7).

              언어화가 실제로 일어났다는 사실은 예고가 아니라 **기록**으로 남긴다 —
              SSE 이벤트 로그에 `refine · 문장 교체` 가 찍힌다 (위 onRefine).
            */}
            {loading && ordered.length > 0 && (
              <p className={`t-caption ${styles.refineNote}`}>↻ 시나리오를 받는 중…</p>
            )}
          </section>
        </div>

        {/*
          기준일은 접힘 패널이 아니라 **여기**에도 적는다. 스펙 §0-4 가 요구하는 것은
          「상시 표기」이고, 요약 스트립의 출처 패널은 사용자가 펼쳐야 보인다 —
          다른 화면(3·4)이 이 자리에 기준일을 적는 것과 형태를 맞춘다.
        */}
        <p className={`t-caption ${styles.disclaimer}`}>
          ⓘ 본 정보는 공개 자료 기반 정보 제공이며 대출 권유·중개·자문이 아닙니다. 실제 한도·금리·승인
          여부는 해당 기관의 심사에 따릅니다. 기준 예산은 {formatAmount(base)}입니다.
          {dataAsOf && ` 데이터 기준일 ${dataAsOf}.`}
        </p>
      </div>
    </AppShell>
  )
}

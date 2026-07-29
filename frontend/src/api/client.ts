import type {
  BudgetRequest,
  BudgetResponse,
  CheckAreaResponse,
  DiagnoseRequest,
  DiagnoseResponse,
  ExploreDoneEvent,
  ExploreInsightEvent,
  ExplorePlanEvent,
  ExploreRefineEvent,
  RecommendResponse,
  Scenario,
} from './types'
import {
  mockBudget,
  mockCheckArea,
  mockDiagnose,
  mockExplore,
  mockRecommend,
  mockScenarios,
} from './mock'
import { markApiFallback } from './fallback'

/**
 * API 클라이언트 — "실제 우선 + 목 폴백" 하이브리드.
 * 실제 엔드포인트가 있으면(/api/diagnose는 BE에 구현됨) 그걸 쓰고,
 * BE가 죽었거나 404면 목으로 폴백해 데모가 안 끊기게 한다 (데모 무중단 원칙).
 * 순수 목만 강제하려면 VITE_USE_MOCK=1.
 */
const FORCE_MOCK = import.meta.env.VITE_USE_MOCK === '1'

/**
 * SSE 재연결 대기 — `done` 이전 오류 1회는 재연결로 흡수한다.
 *
 * EventSource는 일시 단절에도 `onerror`를 발화한다(프록시 재시작·백엔드 GC·모바일 회선 blip).
 * 그것을 곧바로 종국 실패로 처리하면 **순간 단절 한 번으로 세션 전체가 "예시 데이터" 모드에
 * 고정된다** — 목 폴백 플래그는 되돌리지 않는 설계(`api/fallback.ts`)이기 때문이다.
 * 800ms는 서버 재기동을 기다리는 값이 아니라 순간 blip만 넘기는 값이다. 그보다 길게 잡으면
 * 진짜 장애일 때 목 폴백이 늦어져 화면이 그만큼 더 비어 있는다.
 */
const SSE_RETRY_DELAY_MS = 800

export async function postDiagnose(req: DiagnoseRequest): Promise<DiagnoseResponse> {
  if (FORCE_MOCK) return mockDiagnose(req)
  try {
    const res = await fetch('/api/diagnose', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req),
    })
    if (!res.ok) throw new Error(`diagnose ${res.status}`)
    return (await res.json()) as DiagnoseResponse
  } catch (e) {
    console.warn('[api] 실제 diagnose 실패 → 목 폴백', e)
    markApiFallback()
    return mockDiagnose(req)
  }
}

/**
 * 예산 확정(B₀ 기록) + 확정 예산 기준 프리뷰.
 * 슬라이더 변경마다 debounce 후 재호출한다 — B₀는 덮어쓰기이며 마지막 값이 확정값이다.
 */
export async function postBudget(
  sessionId: string,
  req: BudgetRequest,
  signal?: AbortSignal,
): Promise<BudgetResponse> {
  if (FORCE_MOCK) return mockBudget(req)
  try {
    const res = await fetch(`/api/budget/${sessionId}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req),
      signal,
    })
    if (!res.ok) throw new Error(`budget ${res.status}`)
    return (await res.json()) as BudgetResponse
  } catch (e) {
    if (signal?.aborted) throw e   // 취소는 장애가 아니다 — 목 폴백 배너를 세우지 않는다
    console.warn('[api] 실제 budget 실패 → 목 폴백', e)
    markApiFallback()
    return mockBudget(req)
  }
}

/**
 * 입지 추천 — 세션 확정 예산(B₀) 기준 후보 상권.
 * `?budget=` 쿼리는 두지 않는다(예산의 진실 원천은 세션 하나). `v`는 슬라이더 최신성 판별용.
 */
export async function getRecommend(
  sessionId: string,
  version?: number,
  signal?: AbortSignal,
): Promise<RecommendResponse> {
  if (FORCE_MOCK) return mockRecommend()
  try {
    const q = version != null ? `?v=${version}` : ''
    // `signal` 을 실제 요청까지 내린다 — 호출부가 결과만 무시하면 슬라이더를 움직이는 동안
    // 버려질 응답 본문(실측 gzip 122KB)을 끝까지 받는다 (FE 리뷰 m-1). SSE 두 함수는 이미 같다.
    const res = await fetch(`/api/recommend/${sessionId}${q}`, { signal })
    if (!res.ok) throw new Error(`recommend ${res.status}`)
    return (await res.json()) as RecommendResponse
  } catch (e) {
    // 취소는 장애가 아니다 — 목 폴백 배너를 세우면 사용자 조작이 "서버 장애"로 고지된다.
    if (signal?.aborted) throw e
    console.warn('[api] 실제 recommend 실패 → 목 폴백', e)
    markApiFallback()
    return mockRecommend()
  }
}

/**
 * 조달 시나리오 SSE — `scenario` 이벤트(카드 1장씩) → `done`.
 * 연결 실패 시 목 시나리오로 폴백해 데모가 끊기지 않게 한다.
 * `signal`로 중단하면 열린 EventSource를 닫는다 — 넘기지 않으면 연결이 남는다
 * (StrictMode 이중 실행·세션 변경·언마운트).
 */
export function getScenarios(
  sessionId: string,
  onScenario: (s: Scenario) => void,
  signal?: AbortSignal,
  /**
   * 지금까지 수신한 카드를 버리라는 신호. 재연결·목 폴백 직전에 호출된다 —
   * 서버는 재연결 시 카드를 처음부터 다시 보내므로 비우지 않으면 중복되고,
   * 폴백 시 비우지 않으면 **실데이터 카드와 목 카드가 한 화면에 섞인다.**
   */
  onReset?: () => void,
): Promise<void> {
  if (FORCE_MOCK) return mockScenarios(onScenario, signal)
  if (signal?.aborted) return Promise.resolve()

  return new Promise((resolve) => {
    let settled = false
    let retried = false
    let es: EventSource | null = null
    let retryTimer: ReturnType<typeof setTimeout> | undefined

    const finish = () => {
      if (settled) return true
      settled = true
      es?.close()
      es = null
      if (retryTimer !== undefined) clearTimeout(retryTimer)
      signal?.removeEventListener('abort', onAbort)
      return false
    }
    function onAbort() {
      if (!finish()) resolve()
    }
    signal?.addEventListener('abort', onAbort)

    const connect = () => {
      const source = new EventSource(`/api/scenarios/${sessionId}`)
      es = source

      source.addEventListener('scenario', (e) => {
        try {
          onScenario(JSON.parse((e as MessageEvent).data) as Scenario)
        } catch (err) {
          console.warn('[api] scenario 이벤트 파싱 실패', err)
        }
      })

      source.addEventListener('done', () => {
        if (!finish()) resolve()
      })

      source.onerror = (err) => {
        if (settled || es !== source) return // 이미 끝났거나 교체된 구 연결의 뒤늦은 오류
        source.close()
        if (!retried) {
          retried = true
          console.warn('[api] scenarios SSE 단절 → 1회 재연결', err)
          onReset?.()
          retryTimer = setTimeout(() => {
            if (!settled) connect()
          }, SSE_RETRY_DELAY_MS)
          return
        }
        if (finish()) return
        console.warn('[api] 실제 scenarios SSE 실패 → 목 폴백', err)
        onReset?.()
        markApiFallback()
        mockScenarios(onScenario, signal).then(resolve)
      }
    }
    connect()
  })
}

export interface ExploreHandlers {
  onPlan: (e: ExplorePlanEvent) => void
  onInsight: (e: ExploreInsightEvent) => void
  /** LLM 문장 교체. 무LLM 모드에서는 도착하지 않는 것이 정상이다(템플릿이 최종본). */
  onRefine?: (e: ExploreRefineEvent) => void
  onDone: (e: ExploreDoneEvent) => void
  /**
   * 수신분 폐기 신호 — 재연결·목 폴백 직전에 호출된다.
   * 재연결 시 서버가 `plan`부터 다시 보내므로 비우지 않으면 인사이트가 중복되고,
   * 폴백 시 비우지 않으면 실데이터 인사이트와 목 인사이트가 한 목록에 섞인다.
   */
  onReset?: () => void
}

/**
 * 결정공간 탐색 SSE — `plan` → `insight`(1건씩) → `refine`(선택) → `done`.
 *
 * `version`은 슬라이더 최신성 판별용이다. 서버는 세션 최신 version이 아니면 이벤트를 송출하기
 * 전에 폐기하므로(expl §5), 구 요청이 새 결과를 덮어쓰지 않는다. 프론트도 같은 이유로
 * 이전 요청의 `signal`을 반드시 abort해야 한다 — 안 하면 EventSource가 남는다.
 */
export function getExplore(
  sessionId: string,
  version: number,
  handlers: ExploreHandlers,
  currentBudget: number,
  signal?: AbortSignal,
): Promise<void> {
  if (FORCE_MOCK) return mockExplore(handlers, currentBudget, signal)
  if (signal?.aborted) return Promise.resolve()

  return new Promise((resolve) => {
    let settled = false
    let retried = false
    let es: EventSource | null = null
    let retryTimer: ReturnType<typeof setTimeout> | undefined

    const finish = () => {
      if (settled) return true
      settled = true
      es?.close()
      es = null
      if (retryTimer !== undefined) clearTimeout(retryTimer)
      signal?.removeEventListener('abort', onAbort)
      return false
    }
    function onAbort() {
      if (!finish()) resolve()
    }
    signal?.addEventListener('abort', onAbort)

    const connect = () => {
      const source = new EventSource(`/api/explore/${sessionId}?v=${version}`)
      es = source

      const on = <T,>(name: string, handle: ((e: T) => void) | undefined) => {
        source.addEventListener(name, (e) => {
          if (!handle) return
          try {
            handle(JSON.parse((e as MessageEvent).data) as T)
          } catch (err) {
            console.warn(`[api] explore ${name} 이벤트 파싱 실패`, err)
          }
        })
      }
      on<ExplorePlanEvent>('plan', handlers.onPlan)
      on<ExploreInsightEvent>('insight', handlers.onInsight)
      on<ExploreRefineEvent>('refine', handlers.onRefine)

      source.addEventListener('done', (e) => {
        try {
          handlers.onDone(JSON.parse((e as MessageEvent).data) as ExploreDoneEvent)
        } catch (err) {
          console.warn('[api] explore done 이벤트 파싱 실패', err)
        }
        if (!finish()) resolve()
      })

      source.onerror = (err) => {
        if (settled || es !== source) return // 이미 끝났거나 교체된 구 연결의 뒤늦은 오류
        source.close()
        if (!retried) {
          retried = true
          console.warn('[api] explore SSE 단절 → 1회 재연결', err)
          handlers.onReset?.()
          retryTimer = setTimeout(() => {
            if (!settled) connect()
          }, SSE_RETRY_DELAY_MS)
          return
        }
        if (finish()) return
        console.warn('[api] 실제 explore SSE 실패 → 목 폴백', err)
        handlers.onReset?.()
        markApiFallback()
        mockExplore(handlers, currentBudget, signal).then(resolve)
      }
    }
    connect()
  })
}

/**
 * 역방향 판정 — 임의 상권 클릭 시 판정 4단계 + 부족분 + 자격 부합 상품.
 * `matching_products`는 서버가 `amount_max` 내림차순으로 고정 정렬해 주며 프론트는 재정렬하지
 * 않는다 (금리 정렬은 사실상 순위 조작 — 계약 6번).
 */
export async function postCheckArea(
  sessionId: string,
  areaCode: string,
  signal?: AbortSignal,
): Promise<CheckAreaResponse> {
  if (FORCE_MOCK) return mockCheckArea(areaCode)
  try {
    const res = await fetch(`/api/check-area/${sessionId}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ area_code: areaCode }),
      signal,
    })
    if (!res.ok) throw new Error(`check-area ${res.status}`)
    return (await res.json()) as CheckAreaResponse
  } catch (e) {
    if (signal?.aborted) throw e   // 취소는 장애가 아니다 (위와 같은 이유)
    console.warn('[api] 실제 check-area 실패 → 목 폴백', e)
    markApiFallback()
    return mockCheckArea(areaCode)
  }
}

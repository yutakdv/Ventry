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

/**
 * API 클라이언트 — "실제 우선 + 목 폴백" 하이브리드.
 * 실제 엔드포인트가 있으면(/api/diagnose는 BE에 구현됨) 그걸 쓰고,
 * BE가 죽었거나 404면 목으로 폴백해 데모가 안 끊기게 한다 (데모 무중단 원칙).
 * 순수 목만 강제하려면 VITE_USE_MOCK=1.
 */
const FORCE_MOCK = import.meta.env.VITE_USE_MOCK === '1'

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
    return mockDiagnose(req)
  }
}

/**
 * 예산 확정(B₀ 기록) + 확정 예산 기준 프리뷰.
 * 슬라이더 변경마다 debounce 후 재호출한다 — B₀는 덮어쓰기이며 마지막 값이 확정값이다.
 */
export async function postBudget(sessionId: string, req: BudgetRequest): Promise<BudgetResponse> {
  if (FORCE_MOCK) return mockBudget(req)
  try {
    const res = await fetch(`/api/budget/${sessionId}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req),
    })
    if (!res.ok) throw new Error(`budget ${res.status}`)
    return (await res.json()) as BudgetResponse
  } catch (e) {
    console.warn('[api] 실제 budget 실패 → 목 폴백', e)
    return mockBudget(req)
  }
}

/**
 * 입지 추천 — 세션 확정 예산(B₀) 기준 후보 상권.
 * `?budget=` 쿼리는 두지 않는다(예산의 진실 원천은 세션 하나). `v`는 슬라이더 최신성 판별용.
 */
export async function getRecommend(sessionId: string, version?: number): Promise<RecommendResponse> {
  if (FORCE_MOCK) return mockRecommend()
  try {
    const q = version != null ? `?v=${version}` : ''
    const res = await fetch(`/api/recommend/${sessionId}${q}`)
    if (!res.ok) throw new Error(`recommend ${res.status}`)
    return (await res.json()) as RecommendResponse
  } catch (e) {
    console.warn('[api] 실제 recommend 실패 → 목 폴백', e)
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
): Promise<void> {
  if (FORCE_MOCK) return mockScenarios(onScenario, signal)
  if (signal?.aborted) return Promise.resolve()

  return new Promise((resolve) => {
    let settled = false
    const es = new EventSource(`/api/scenarios/${sessionId}`)

    const finish = () => {
      if (settled) return true
      settled = true
      es.close()
      signal?.removeEventListener('abort', onAbort)
      return false
    }
    function onAbort() {
      if (!finish()) resolve()
    }
    signal?.addEventListener('abort', onAbort)

    es.addEventListener('scenario', (e) => {
      try {
        onScenario(JSON.parse((e as MessageEvent).data) as Scenario)
      } catch (err) {
        console.warn('[api] scenario 이벤트 파싱 실패', err)
      }
    })

    es.addEventListener('done', () => {
      if (!finish()) resolve()
    })

    es.onerror = (err) => {
      if (finish()) return
      console.warn('[api] 실제 scenarios SSE 실패 → 목 폴백', err)
      mockScenarios(onScenario, signal).then(resolve)
    }
  })
}

export interface ExploreHandlers {
  onPlan: (e: ExplorePlanEvent) => void
  onInsight: (e: ExploreInsightEvent) => void
  /** LLM 문장 교체. 무LLM 모드에서는 도착하지 않는 것이 정상이다(템플릿이 최종본). */
  onRefine?: (e: ExploreRefineEvent) => void
  onDone: (e: ExploreDoneEvent) => void
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
    const es = new EventSource(`/api/explore/${sessionId}?v=${version}`)

    const finish = () => {
      if (settled) return true
      settled = true
      es.close()
      signal?.removeEventListener('abort', onAbort)
      return false
    }
    function onAbort() {
      if (!finish()) resolve()
    }
    signal?.addEventListener('abort', onAbort)

    const on = <T,>(name: string, handle: ((e: T) => void) | undefined) => {
      es.addEventListener(name, (e) => {
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

    es.addEventListener('done', (e) => {
      try {
        handlers.onDone(JSON.parse((e as MessageEvent).data) as ExploreDoneEvent)
      } catch (err) {
        console.warn('[api] explore done 이벤트 파싱 실패', err)
      }
      if (!finish()) resolve()
    })

    es.onerror = (err) => {
      if (finish()) return
      console.warn('[api] 실제 explore SSE 실패 → 목 폴백', err)
      mockExplore(handlers, currentBudget, signal).then(resolve)
    }
  })
}

/**
 * 역방향 판정 — 임의 상권 클릭 시 판정 4단계 + 부족분 + 자격 부합 상품.
 * `matching_products`는 서버가 `amount_max` 내림차순으로 고정 정렬해 주며 프론트는 재정렬하지
 * 않는다 (금리 정렬은 사실상 순위 조작 — 계약 6번).
 */
export async function postCheckArea(sessionId: string, areaCode: string): Promise<CheckAreaResponse> {
  if (FORCE_MOCK) return mockCheckArea(areaCode)
  try {
    const res = await fetch(`/api/check-area/${sessionId}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ area_code: areaCode }),
    })
    if (!res.ok) throw new Error(`check-area ${res.status}`)
    return (await res.json()) as CheckAreaResponse
  } catch (e) {
    console.warn('[api] 실제 check-area 실패 → 목 폴백', e)
    return mockCheckArea(areaCode)
  }
}

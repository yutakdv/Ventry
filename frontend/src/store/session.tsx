import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'
import type {
  ExploreDoneEvent,
  ExploreInsightEvent,
  ExplorePlanEvent,
  ParsedProfile,
  Scenario,
} from '../api/types'
import type { FormState } from '../screens/diagnoseData'

/**
 * 탐색 결과 캐시.
 *
 * 탐색은 **기준 예산(B₀) 한 번**만 돌린다. 인사이트를 적용하면 세션 예산이 바뀌는데, 그때마다
 * 다시 돌리면 방금 보고 있던 선택지 3개가 사라져 "다른 시나리오로 갈아타기"가 불가능해진다.
 * 그래서 결과를 기준 예산에 묶어 보관하고, 적용은 그중 하나를 고르는 행위로 다룬다.
 */
export interface ExploreCache {
  baseBudget: number
  plan: ExplorePlanEvent | null
  insights: ExploreInsightEvent[]
  done: ExploreDoneEvent | null
  log: { name: string; detail: string }[]
  /** 지금 적용 중인 인사이트. null이면 기준 예산 그대로. */
  appliedId: string | null
}

/**
 * 세션 전역 상태 — session_id · version · parsed_profile.
 * version은 화면 2/3의 슬라이더 변경마다 증가시켜 SSE 최신성 판별에 쓴다(FE-05 기반).
 */
interface SessionState {
  sessionId: string | null
  version: number
  parsedProfile: ParsedProfile | null
  /** 현재 유효 예산(만원). 탐색 인사이트를 적용하면 이 값만 바뀐다. */
  budget: number | null
  /**
   * 화면 3에서 확정한 원래 예산(B₀). 탐색에서 인사이트를 적용해도 보존되며 "되돌리기"의 기준이다.
   * 상향 인사이트를 적용한 상태로 화면이 고정되면 "더 빌리는 쪽"만 남으므로, 하향 복귀 경로를
   * 항상 열어 두기 위해 별도로 들고 있는다 (expl §7).
   */
  baseBudget: number | null
  /**
   * 진단 폼에 입력한 원본 값. `parsedProfile`은 계약 형태(만원 단위)라 폼 복원에 쓸 수 없고
   * 선택 항목(창업 시기·운영 형태·상권 유형·자유 입력)도 담기지 않는다 —
   * 화면 2의 "입력 정보 수정"으로 돌아왔을 때 입력값을 그대로 되살리기 위해 보관한다.
   */
  diagnoseForm: FormState | null
  /** 화면 2에서 고른 시나리오 — 화면 3 슬라이더의 가동 범위(budget_min~max)와 조달 구성의 출처. */
  selectedScenario: Scenario | null
  setSession: (id: string, profile: ParsedProfile) => void
  setDiagnoseForm: (form: FormState) => void
  setSelectedScenario: (s: Scenario) => void
  /** 탐색 결과 — 화면을 떠났다 돌아와도 선택지가 유지되도록 세션에 둔다. */
  explore: ExploreCache | null
  setExplore: (cache: ExploreCache | null) => void
  /** 화면 3 확정 — 현재 예산과 기준 예산을 함께 세운다. 탐색 캐시는 무효화된다. */
  setBudget: (budget: number) => void
  /** 탐색에서 인사이트 예산을 적용 — 기준 예산(baseBudget)은 건드리지 않는다. */
  applyExploreBudget: (budget: number, appliedId: string | null) => void
  bumpVersion: () => void
}

const Ctx = createContext<SessionState | null>(null)

export function SessionProvider({ children }: { children: ReactNode }) {
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [version, setVersion] = useState(0)
  const [parsedProfile, setParsedProfile] = useState<ParsedProfile | null>(null)
  const [budget, setBudgetState] = useState<number | null>(null)
  const [baseBudget, setBaseBudgetState] = useState<number | null>(null)
  const [explore, setExploreState] = useState<ExploreCache | null>(null)
  const [diagnoseForm, setDiagnoseFormState] = useState<FormState | null>(null)
  const [selectedScenario, setSelectedScenarioState] = useState<Scenario | null>(null)

  /**
   * setter는 **신원이 고정**되어야 한다.
   * 매 렌더 새 함수를 만들면, setter를 의존성에 넣은 effect가 자기 호출로 다시 트리거되어
   * 무한 요청 루프가 된다 (화면 3 슬라이더 debounce에서 실제로 발생).
   */
  const setSession = useCallback((id: string, profile: ParsedProfile) => {
    setSessionId(id)
    setParsedProfile(profile)
  }, [])
  const setDiagnoseForm = useCallback((form: FormState) => setDiagnoseFormState(form), [])
  const setSelectedScenario = useCallback((s: Scenario) => setSelectedScenarioState(s), [])
  const setBudget = useCallback((b: number) => {
    setBudgetState(b)
    setBaseBudgetState(b)
    setExploreState(null) // 기준 예산이 바뀌면 이전 탐색 결과는 더 이상 유효하지 않다
  }, [])
  const setExplore = useCallback((c: ExploreCache | null) => setExploreState(c), [])
  const applyExploreBudget = useCallback((b: number, appliedId: string | null) => {
    setBudgetState(b)
    setExploreState((prev) => (prev ? { ...prev, appliedId } : prev))
  }, [])
  const bumpVersion = useCallback(() => setVersion((v) => v + 1), [])

  const value = useMemo<SessionState>(
    () => ({
      sessionId,
      version,
      parsedProfile,
      budget,
      baseBudget,
      explore,
      diagnoseForm,
      selectedScenario,
      setSession,
      setDiagnoseForm,
      setSelectedScenario,
      setBudget,
      setExplore,
      applyExploreBudget,
      bumpVersion,
    }),
    [
      sessionId,
      version,
      parsedProfile,
      budget,
      baseBudget,
      explore,
      diagnoseForm,
      selectedScenario,
      setSession,
      setDiagnoseForm,
      setSelectedScenario,
      setBudget,
      setExplore,
      applyExploreBudget,
      bumpVersion,
    ],
  )

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

export function useSession() {
  const v = useContext(Ctx)
  if (!v) throw new Error('useSession must be used within SessionProvider')
  return v
}

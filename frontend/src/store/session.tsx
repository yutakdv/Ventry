import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'
import type {
  BudgetPreview,
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
  /**
   * 확정 예산 기준 프리뷰 — `POST /budget` 응답의 `preview`.
   *
   * 예산을 확정한 화면과 그 결과를 보여주는 화면이 다르기 때문에 세션에 둔다. 화면 4가 자체 상태로
   * 들고 있으면 **진입 직후에는 값이 없어 "—"** 가 뜨는데, 그렇다고 진입할 때마다 `/budget`을
   * 다시 부르면 예산을 다시 쓰는 셈이라 옳지 않다. 확정한 쪽이 결과를 넘기는 것이 맞다.
   */
  budgetPreview: BudgetPreview | null
  /**
   * `POST /budget` 응답의 기준일(계약 D9). 프리뷰와 **같은 호출에서** 받아 같이 보관한다.
   *
   * 탐색 화면(/explore)이 이것을 쓴다 — 자체 응답에는 기준일이 없어, 기준일을 표기하지 못하는
   * 유일한 화면이었다(「데이터 기준일 상시 표기」 스펙 §0-4). 화면이 지어내지 않고 서버가 준
   * 값을 옮기기 위해 예산을 확정한 쪽에서 실어 보낸다.
   */
  dataAsOf: string | null
  setSession: (id: string, profile: ParsedProfile) => void
  setDiagnoseForm: (form: FormState) => void
  setSelectedScenario: (s: Scenario) => void
  /** 탐색 결과 — 화면을 떠났다 돌아와도 선택지가 유지되도록 세션에 둔다. */
  explore: ExploreCache | null
  setExplore: (cache: ExploreCache | null) => void
  /**
   * 화면 3 확정 — 현재 예산과 기준 예산을 함께 세운다. 탐색 캐시는 무효화된다.
   * 프리뷰를 같은 호출로 받는 이유는 예산과 프리뷰가 **따로 움직이면 안 되기** 때문이다 —
   * 예산만 바뀌고 프리뷰가 남으면 화면이 옛 후보 수를 새 예산의 것처럼 말하게 된다.
   */
  setBudget: (budget: number, preview: BudgetPreview | null, dataAsOf: string | null) => void
  /** 탐색에서 인사이트 예산을 적용 — 기준 예산(baseBudget)은 건드리지 않는다. */
  applyExploreBudget: (
    budget: number,
    appliedId: string | null,
    preview: BudgetPreview | null,
    dataAsOf: string | null,
  ) => void
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
  const [budgetPreview, setBudgetPreviewState] = useState<BudgetPreview | null>(null)
  const [dataAsOf, setDataAsOfState] = useState<string | null>(null)

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
  const setBudget = useCallback((b: number, preview: BudgetPreview | null, asOf: string | null) => {
    setBudgetState(b)
    setBaseBudgetState(b)
    setBudgetPreviewState(preview)
    setDataAsOfState(asOf)
    setExploreState(null) // 기준 예산이 바뀌면 이전 탐색 결과는 더 이상 유효하지 않다
  }, [])
  const setExplore = useCallback((c: ExploreCache | null) => setExploreState(c), [])
  const applyExploreBudget = useCallback(
    (b: number, appliedId: string | null, preview: BudgetPreview | null, asOf: string | null) => {
      setBudgetState(b)
      setBudgetPreviewState(preview)
      setDataAsOfState(asOf)
      setExploreState((prev) => (prev ? { ...prev, appliedId } : prev))
    },
    [],
  )
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
      budgetPreview,
      dataAsOf,
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
      budgetPreview,
      dataAsOf,
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

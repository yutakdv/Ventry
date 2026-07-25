import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'
import type { ParsedProfile, Scenario } from '../api/types'
import type { FormState } from '../screens/diagnoseData'

/**
 * 세션 전역 상태 — session_id · version · parsed_profile.
 * version은 화면 2/3의 슬라이더 변경마다 증가시켜 SSE 최신성 판별에 쓴다(FE-05 기반).
 */
interface SessionState {
  sessionId: string | null
  version: number
  parsedProfile: ParsedProfile | null
  /** 확정 예산(만원) — 화면 2/3에서 슬라이더로 확정(FE-03). 진단 단계에선 null. */
  budget: number | null
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
  setBudget: (budget: number) => void
  bumpVersion: () => void
}

const Ctx = createContext<SessionState | null>(null)

export function SessionProvider({ children }: { children: ReactNode }) {
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [version, setVersion] = useState(0)
  const [parsedProfile, setParsedProfile] = useState<ParsedProfile | null>(null)
  const [budget, setBudgetState] = useState<number | null>(null)
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
  const setBudget = useCallback((b: number) => setBudgetState(b), [])
  const bumpVersion = useCallback(() => setVersion((v) => v + 1), [])

  const value = useMemo<SessionState>(
    () => ({
      sessionId,
      version,
      parsedProfile,
      budget,
      diagnoseForm,
      selectedScenario,
      setSession,
      setDiagnoseForm,
      setSelectedScenario,
      setBudget,
      bumpVersion,
    }),
    [
      sessionId,
      version,
      parsedProfile,
      budget,
      diagnoseForm,
      selectedScenario,
      setSession,
      setDiagnoseForm,
      setSelectedScenario,
      setBudget,
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

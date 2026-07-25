import { createContext, useContext, useMemo, useState, type ReactNode } from 'react'
import type { ParsedProfile } from '../api/types'

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
  setSession: (id: string, profile: ParsedProfile) => void
  setBudget: (budget: number) => void
  bumpVersion: () => void
}

const Ctx = createContext<SessionState | null>(null)

export function SessionProvider({ children }: { children: ReactNode }) {
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [version, setVersion] = useState(0)
  const [parsedProfile, setParsedProfile] = useState<ParsedProfile | null>(null)
  const [budget, setBudgetState] = useState<number | null>(null)

  const value = useMemo<SessionState>(
    () => ({
      sessionId,
      version,
      parsedProfile,
      budget,
      setSession: (id, profile) => {
        setSessionId(id)
        setParsedProfile(profile)
      },
      setBudget: (b) => setBudgetState(b),
      bumpVersion: () => setVersion((v) => v + 1),
    }),
    [sessionId, version, parsedProfile, budget],
  )

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

export function useSession() {
  const v = useContext(Ctx)
  if (!v) throw new Error('useSession must be used within SessionProvider')
  return v
}

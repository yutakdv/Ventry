import type { DiagnoseRequest, DiagnoseResponse } from './types'
import { mockDiagnose } from './mock'

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

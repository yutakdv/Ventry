import type { DiagnoseRequest, DiagnoseResponse } from './types'

/**
 * 목 diagnose — 실제 BE가 없거나 죽었을 때의 폴백.
 * 폼 값을 그대로 반향 + free_text에서 관심사 키워드를 **결정적으로** 추출한다
 * (BE DiagnoseController와 동일 규칙: 권리금→premium, 임대/월세→rent, 유동/손님→traffic).
 */
export async function mockDiagnose(req: DiagnoseRequest): Promise<DiagnoseResponse> {
  const t = req.free_text
  const concerns: string[] = []
  if (t.includes('권리금')) concerns.push('premium')
  if (t.includes('임대') || t.includes('월세')) concerns.push('rent')
  if (t.includes('유동') || t.includes('손님')) concerns.push('traffic')

  await new Promise((r) => setTimeout(r, 300)) // 네트워크 지연 흉내

  return {
    session_id: `mock-${Date.now()}`,
    parsed_profile: {
      ...req.form,
      concerns,
      parse_source: t.trim() ? 'llm' : 'form_only',
    },
  }
}

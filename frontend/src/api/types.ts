/**
 * API 계약(docs/API_CONTRACT.md) DTO의 TS 표현.
 * 서버는 SNAKE_CASE 직렬화 → 필드명 그대로 snake_case를 쓴다.
 * 금액은 모두 **만원 단위 정수** (계약 공통 규약 · DECISIONS §2).
 */

export type Industry = 'cafe' | 'food'

/** 진단 폼 — 숫자는 반드시 이 폼으로 전송(§0-1, free_text 경유 금지). */
export interface DiagnoseForm {
  age: number | null
  capital: number | null // 만원
  is_existing_business: boolean | null
  collateral_available: boolean | null
  monthly_investable: number | null // 만원
  industry: Industry | null
  region_hint: string | null // "서울특별시 마포구" (시/도+구 프론트 조립)
}

export interface DiagnoseRequest {
  form: DiagnoseForm
  free_text: string
}

export type ParseSource = 'llm' | 'form_only'

/** 응답 프로필 — form 전 필드 반향 + concerns·parse_source. */
export interface ParsedProfile extends DiagnoseForm {
  concerns: string[] // "premium" | "rent" | "traffic" ...
  parse_source: ParseSource
}

export interface DiagnoseResponse {
  session_id: string
  parsed_profile: ParsedProfile
}

/** 공통 오류 포맷 { error: { code, message } }. */
export interface ApiError {
  error: { code: string; message: string }
}

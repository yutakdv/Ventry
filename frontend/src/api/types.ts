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

/* ─────────────────────────── 화면 2: 조달 시나리오 ─────────────────────────── */

export type CompositionType = 'equity' | 'guarantee' | 'policy_loan'

export interface ScenarioComposition {
  type: CompositionType
  amount_min: number // 만원
  amount_max: number // 만원
}

export type RateType = 'fixed' | 'variable'

export interface ProductSource {
  org: string
  url?: string
  collected?: string
}

/**
 * 금융상품 — `rate`는 nullable(변동금리는 생략), 표시 규칙은 API_CONTRACT §금리 표기.
 * 계약상 `rate_type`은 항상 존재한다고 되어 있으나 현재 BE(목 단계)는 보내지 않는다.
 * 계약의 "프론트는 undefined 허용으로 파싱" 규약에 따라 optional로 둔다 — BE-04 실계산 후 재확인.
 */
export interface ScenarioProduct {
  name: string
  amount_max: number // 만원
  rate?: number // 연 % — 생략되면 rate_note를 그대로 표기
  rate_type?: RateType
  rate_note?: string
  data_as_of: string
  source: ProductSource
  source_quote?: string | null
}

export type ScenarioLabel = '보수' | '적극'

/** `GET /api/scenarios/{sid}` SSE `scenario` 이벤트 페이로드 (카드 1장). */
export interface Scenario {
  label: ScenarioLabel
  budget: number // 만원 — 슬라이더 초기값(= budget_max)
  budget_min: number // 만원 — 확정 재원 합(자기자본 등)
  budget_max: number // 만원 — budget_min + Σ 상품 한도
  composition: ScenarioComposition[]
  products: ScenarioProduct[]
}

/* ─────────────────────────── 화면 3: 예산 선택 ─────────────────────────── */

export interface BudgetCompositionItem {
  type: CompositionType
  amount: number // 만원
}

/** `confirmed_budget`은 **단일 확정값**이다 — 배열을 보내면 서버가 500으로 거절한다. */
export interface BudgetRequest {
  confirmed_budget: number // 만원
  composition: BudgetCompositionItem[]
}

/**
 * 확정 예산 기준 프리뷰.
 * 진입 후보가 0곳이면 `area_count: 0`이고 두 range 필드는 **응답에서 생략된다**(non_null 직렬화).
 */
export interface BudgetPreview {
  area_count: number
  rent_range?: [number, number] // 환산임대료 [최소, 최대] (만원/월)
  floating_range?: [number, number] // 일평균 유동인구 [최소, 최대] (명)
}

export interface BudgetResponse {
  confirmed_budget: number
  composition: BudgetCompositionItem[]
  preview: BudgetPreview
}

/* ─────────────────────────── 화면 4: 입지 추천 ─────────────────────────── */

/** 판정 4단계 (CLAUDE.md §0-4). "승인" 계열 표현은 쓰지 않는다. */
export type Verdict = 'FIT' | 'CONDITIONAL' | 'CAUTION' | 'OUT_OF_SCOPE'

/** 임대료 출처 — 화면에 **상시** 표기한다 (스펙 §7). fallback이면 자치구 평균으로 대체된 값. */
export interface RentSource {
  org: string // "REB" 등 기관 코드
  district: string // 부동산원 상권명
  fallback: boolean
}

/** 최근접 역 — 화면에 **상시** 표기한다 (스펙 §7). */
export interface Transit {
  station: string
  line: string // "2·6"처럼 환승역은 결합 표기
  distance_m: number
  daily_riders: number
  fallback: boolean
}

export interface AreaScoreBreakdown {
  w1: number
  w2: number
  w3: number
  w4: number
  w5: number
}

export interface AreaCost {
  ex_premium: [number, number] // 권리금 제외 [최소, 최대] (만원)
  incl_premium: [number, number] // 권리금 포함 [최소, 최대] (만원)
}

export interface Area {
  area_code: string
  name: string
  lat: number // WGS84
  lng: number // WGS84
  verdict: Verdict
  score: number // 0~100
  breakdown: AreaScoreBreakdown
  cost: AreaCost
  monthly_rent: number // 만원/월 (환산임대료)
  est_sales: number // 만원/월 (추정매출)
  daily_floating: number // 명/일
  burden_ratio: number // monthly_rent ÷ est_sales
  reason_text: string
  rent_source: RentSource
  transit: Transit
}

/** 결과 **전체**에 대한 검증 의견 1건 — 상권별로 오지 않는다. 장애 시 skipped=true. */
export interface RiskReview {
  objection_text: string
  applied: boolean
  skipped: boolean
}

export interface RecommendResponse {
  data_as_of: string
  total_count: number
  summary: { avg_rent: number; avg_sales: number }
  /** `score` 내림차순으로 정렬되어 온다. 정렬·필터 쿼리는 없으며 프론트가 처리한다. */
  areas: Area[]
  risk_review: RiskReview
}

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
 * 금융상품 — `rate`는 변동금리면 **키 자체가 생략**된다(null이 아니다). 표시 규칙은
 * API_CONTRACT §금리 표기.
 *
 * `rate_type`은 계약상 항상 존재하고 **실데이터도 그렇다** — `finance_product` 26건 전건이
 * NOT NULL이며 시나리오·역방향 응답에서 누락 0건이다(2026-07-26 실측). 그럼에도 `optional`로
 * 두는 것은 계약의 "프론트는 undefined 허용으로 파싱" 규약을 지키기 위해서다. `formatRate`가
 * 없을 때도 동작하므로 필수로 바꿔 얻는 안전성이 없다.
 */
export interface ScenarioProduct {
  name: string
  amount_max: number // 만원
  rate?: number // 연 % — 생략되면 rate_note를 그대로 표기
  rate_type?: RateType
  rate_note?: string
  data_as_of: string
  source: ProductSource
  /** 원문 인용 — 구조는 check-area와 동일. RAG 구현 전 null (P1-①). */
  source_quote?: SourceQuote | null
}

/** 벡터DB 원문 청크 그대로 (스펙 §5-4). LLM 재작성 금지 — 구현 전에는 null. */
export interface SourceQuote {
  text: string
  org: string
  doc: string
  date: string
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
  /**
   * monthly_rent ÷ est_sales.
   * ⚠️ `est_sales = 0` 인 상권에서 서버가 문자열 `"Infinity"` 를 보낸다(실측 1,061건 중 1건).
   * 표시할 때는 `formatBurdenRatio` 로 감싼다 — 타입만 믿고 계산하면 화면에 `Infinity%` 가 찍힌다.
   */
  burden_ratio: number
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
  /**
   * `score` 내림차순으로 정렬되어 온다. 정렬·필터 쿼리는 없으며 프론트가 처리한다.
   * ⚠️ 실데이터에서는 1,000건대가 한 번에 온다(실측 1,061건·770KB). 전량을 마커·목록으로
   * 그리면 지도가 버티지 못하므로 표시 상한은 프론트가 건다 (`MAP_MARKER_LIMIT`).
   * `OUT_OF_SCOPE`도 섞여 오는데 마커는 3종 고정이라(스펙 §0-4) 화면에서 제외한다.
   */
  areas: Area[]
  risk_review: RiskReview
}

/* ─────────────────────── 화면 4: 결정공간 탐색 (SSE) ─────────────────────── */

/** 탐색 축 4종 고정 (expl §1). 라벨은 `plan.axis_labels`로 오며 하드코딩 사전을 두지 않는다. */
export type ExploreAxis = 'A1' | 'A2' | 'A3' | 'A4'

export interface ExplorePlanEvent {
  axes: ExploreAxis[]
  /** 코드→화면 라벨. 화면 문구는 용어 컴플라이언스 대상이라 서버가 단일 통제한다. */
  axis_labels: Record<string, string>
  rationale: string
}

/** 인사이트 타입 5종 고정 (expl §3). 타입 추가 금지. */
export type InsightType = 'T1' | 'T2' | 'T3' | 'T4' | 'T5'

/**
 * T1은 진입/지속을 **반드시 병기**한다 (서비스 철학의 핵심 표기).
 * T2(안전 마진)는 before == after == sustain으로 오므로 증감 표기가 무의미하다 — 카드에서 분기한다.
 * `score_delta`는 백분위가 아니라 원점수 합(실측 644 규모)이라 화면에 노출하지 않는다.
 */
export interface InsightDelta {
  n_entry_before: number
  n_entry_after: number
  n_sustain_after?: number
  score_delta?: number
}

/**
 * 인사이트의 근거 상품.
 * 금리 표기는 계약(§금리 표기)을 그대로 따른다 — `rate`가 실려 오면 "연 {rate}%"만 쓰고,
 * 생략됐을 때만 `rate_note` 원문을 표기한다. 고정/변동 라벨은 항상 `rate_type`으로 판단한다.
 * (실데이터에는 `rate`와 `rate_note`가 함께 오는 상품이 있으나, 표시 규칙은 위와 같다.)
 */
export interface InsightFunding {
  name: string
  amount_max: number // 만원
  rate?: number // 연 %
  rate_type?: RateType
  rate_note?: string
  term_assumed: number // 개월 — 월 상환액 산출 가정
  status?: string
  notice_date?: string
  exclusive_group?: string
  source: ProductSource
  source_quote?: SourceQuote | null
}

/** `insight_id`는 `refine` 이벤트가 교체 대상을 지목하는 키. */
export interface ExploreInsightEvent {
  insight_id: string
  type: InsightType
  headline: string
  delta: InsightDelta
  gap_amount?: number // 만원 — T2에는 없다
  /** 고정금리 근거일 때만. 생략 시 `marginal_payment_note`를 그 자리에 표기한다. */
  marginal_payment?: number // 만원/월
  marginal_payment_note?: string
  funding?: InsightFunding // T2에는 없다
  disclaimer: boolean
}

/** LLM 문장 교체. 미도착이 정상이며(무LLM 모드) 그 경우 템플릿이 최종본이다. */
export interface ExploreRefineEvent {
  insight_id: string
  headline: string
}

export interface ExploreDoneEvent {
  scenarios_explored: number
  /** 계단 함수 좌표 [예산(만원), 진입 후보 수]. */
  frontier_points: [number, number][]
  current_budget: number // 만원
}

/* ─────────────────────── 화면 4: 역방향 판정 ─────────────────────── */

/** `matching_products`의 상품. 정렬은 서버가 `amount_max` 내림차순으로 고정 — 재정렬 금지. */
export interface MatchingProduct {
  name: string
  amount_max: number // 만원
  rate?: number
  rate_type?: RateType
  rate_note?: string
  data_as_of: string
  source: ProductSource
  source_quote?: SourceQuote | null
}

/**
 * 역방향 판정 응답.
 * `verdict: "FIT"`·`gap_amount: 0`일 때도 `matching_products`가 온다 — "부족분 상품"이 아니라
 * "자격 요건 부합 상품"이므로 부족분 0일 때의 문구를 따로 둔다.
 */
export interface CheckAreaResponse {
  verdict: Verdict
  gap_amount: number // 만원 — 0이면 예산 내
  matching_products: MatchingProduct[]
  risk_review: RiskReview
}

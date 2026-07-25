import type {
  BudgetRequest,
  BudgetResponse,
  DiagnoseRequest,
  DiagnoseResponse,
  RecommendResponse,
  Scenario,
} from './types'

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

const MOCK_SCENARIOS: Scenario[] = [
  {
    label: '보수',
    budget: 6500,
    budget_min: 5000,
    budget_max: 6500,
    composition: [
      { type: 'equity', amount_min: 5000, amount_max: 5000 },
      { type: 'policy_loan', amount_min: 0, amount_max: 1500 },
    ],
    products: [
      {
        name: '소상공인 정책자금 (청년고용연계자금)',
        amount_max: 1500,
        rate_type: 'variable',
        rate_note: '정책자금 기준금리+0.6%p',
        data_as_of: '2025-12-29',
        source: { org: '소진공 공고문' },
        source_quote: null,
      },
    ],
  },
  {
    label: '적극',
    budget: 8000,
    budget_min: 5000,
    budget_max: 8000,
    composition: [
      { type: 'equity', amount_min: 5000, amount_max: 5000 },
      { type: 'policy_loan', amount_min: 0, amount_max: 1500 },
      { type: 'guarantee', amount_min: 0, amount_max: 1500 },
    ],
    products: [
      {
        name: '소상공인 정책자금 (청년고용연계자금)',
        amount_max: 1500,
        rate_type: 'variable',
        rate_note: '정책자금 기준금리+0.6%p',
        data_as_of: '2025-12-29',
        source: { org: '소진공 공고문' },
        source_quote: null,
      },
      {
        name: '지역신용보증재단 보증',
        amount_max: 1500,
        rate_type: 'variable',
        rate_note: '최저 연 3.62% (변동)',
        data_as_of: '2026-07-21',
        source: { org: '소진공 공고문' },
        source_quote: null,
      },
    ],
  },
]

/**
 * 목 budget — 예산이 오를수록 진입 후보가 늘어나는 계단 함수(실제 BE 픽스처와 같은 성격).
 * 후보 0곳이면 range 필드를 **생략**해 실제 응답의 non_null 직렬화 동작을 그대로 흉내 낸다.
 */
export async function mockBudget(req: BudgetRequest): Promise<BudgetResponse> {
  await new Promise((r) => setTimeout(r, 200))
  const b = req.confirmed_budget
  const areaCount = b >= 8000 ? 3 : b >= 7000 ? 2 : b >= 6500 ? 1 : 0

  return {
    confirmed_budget: b,
    composition: req.composition,
    preview:
      areaCount === 0
        ? { area_count: 0 }
        : {
            area_count: areaCount,
            rent_range: [198, 198 + areaCount * 86],
            floating_range: [22800, 22800 + areaCount * 5100],
          },
  }
}

/**
 * 목 recommend — BE 데모 픽스처와 같은 3곳(망원 FIT·합정 FIT·홍대입구 CAUTION).
 * 판정 3종 마커를 모두 확인할 수 있도록 CONDITIONAL 1곳을 더 둔다(실 API엔 없는 조합).
 */
export async function mockRecommend(): Promise<RecommendResponse> {
  await new Promise((r) => setTimeout(r, 250))
  return {
    data_as_of: '2026-Q1',
    total_count: 4,
    summary: { avg_rent: 309, avg_sales: 2100 },
    areas: [
      {
        area_code: 'A-1101',
        name: '망원역 상권',
        lat: 37.5556,
        lng: 126.9106,
        verdict: 'FIT',
        score: 75,
        breakdown: { w1: 0.82, w2: 0.74, w3: 0.68, w4: 0.71, w5: 0.77 },
        cost: { ex_premium: [5600, 6800], incl_premium: [7000, 8500] },
        monthly_rent: 198,
        est_sales: 1800,
        daily_floating: 24500,
        burden_ratio: 0.11,
        reason_text:
          '망원역 상권 — 길단위 유동·배후 인구가 서울 상위 구간이며 환산임대료 부담률 11%로 임계 이내입니다.',
        rent_source: { org: 'REB', district: '홍대합정상권', fallback: false },
        transit: { station: '망원', line: '6', distance_m: 320, daily_riders: 21000, fallback: false },
      },
      {
        area_code: 'A-1102',
        name: '합정역 상권',
        lat: 37.5495,
        lng: 126.9139,
        verdict: 'FIT',
        score: 71,
        breakdown: { w1: 0.79, w2: 0.81, w3: 0.55, w4: 0.66, w5: 0.7 },
        cost: { ex_premium: [5800, 7000], incl_premium: [7200, 8700] },
        monthly_rent: 273,
        est_sales: 2100,
        daily_floating: 22800,
        burden_ratio: 0.13,
        reason_text:
          '합정역 상권 — 길단위 유동·배후 인구가 서울 상위 구간이며 환산임대료 부담률 13%로 임계 이내입니다.',
        rent_source: { org: 'REB', district: '홍대합정상권', fallback: false },
        transit: { station: '합정', line: '2·6', distance_m: 210, daily_riders: 68000, fallback: false },
      },
      {
        area_code: 'A-1104',
        name: '상수역 상권',
        lat: 37.5478,
        lng: 126.9227,
        verdict: 'CONDITIONAL',
        score: 68,
        breakdown: { w1: 0.7, w2: 0.66, w3: 0.6, w4: 0.62, w5: 0.64 },
        cost: { ex_premium: [5400, 6300], incl_premium: [7600, 9100] },
        monthly_rent: 244,
        est_sales: 1900,
        daily_floating: 19800,
        burden_ratio: 0.13,
        reason_text: '상수역 상권 — 무권리 물건 기준으로는 예산 내이나 권리금 포함 시 예산을 초과합니다.',
        rent_source: { org: 'REB', district: '홍대합정상권', fallback: true },
        transit: { station: '상수', line: '6', distance_m: 260, daily_riders: 17400, fallback: false },
      },
      {
        area_code: 'A-1103',
        name: '홍대입구역 상권',
        lat: 37.5572,
        lng: 126.9236,
        verdict: 'CAUTION',
        score: 70,
        breakdown: { w1: 0.91, w2: 0.88, w3: 0.31, w4: 0.74, w5: 0.52 },
        cost: { ex_premium: [5600, 6500], incl_premium: [6900, 8100] },
        monthly_rent: 456,
        est_sales: 2400,
        daily_floating: 38200,
        burden_ratio: 0.19,
        reason_text:
          '홍대입구역 상권 — 수요 지표는 상위 구간이나 환산임대료 부담률 19%로 임계를 초과합니다.',
        rent_source: { org: 'REB', district: '홍대합정상권', fallback: false },
        transit: { station: '홍대입구', line: '2', distance_m: 180, daily_riders: 92000, fallback: false },
      },
    ],
    risk_review: {
      objection_text:
        '수요 상위 상권일수록 경쟁밀도가 높아, 추정매출 하위 시나리오에서는 부담률이 임계를 넘을 수 있습니다. 유의 판정 유지가 타당합니다.',
      applied: true,
      skipped: false,
    },
  }
}

/** 목 scenarios — SSE 이벤트 순서(카드 1장씩 → done)를 흉내 낸다. signal로 중단 가능. */
export async function mockScenarios(onScenario: (s: Scenario) => void, signal?: AbortSignal): Promise<void> {
  for (const s of MOCK_SCENARIOS) {
    await new Promise((r) => setTimeout(r, 350))
    if (signal?.aborted) return
    onScenario(s)
  }
}

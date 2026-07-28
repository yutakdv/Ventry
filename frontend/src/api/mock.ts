import type {
  Area,
  BudgetRequest,
  BudgetResponse,
  CheckAreaResponse,
  DiagnoseRequest,
  DiagnoseResponse,
  ExploreDoneEvent,
  ExploreInsightEvent,
  ExplorePlanEvent,
  RecommendResponse,
  RiskReview,
  Scenario,
  Verdict,
} from './types'

/**
 * 목 리스크 검증 결과.
 *
 * 서버는 LLM 왕복이 실패하거나 응답이 검증기에 걸리면 **템플릿 문장을 그대로 실은 채**
 * `applied=false`·`skipped=true`로 내려보낸다 (계약 §4 주석, #96). 목이 성공 경로만 만들면
 * 그 분기의 화면을 목만으로는 한 번도 볼 수 없어, QA가 BE를 띄우고 키를 지워야만 확인 가능해진다.
 * `?mock_risk=skipped`로 재현한다 — 빌드를 다시 하지 않고 주소창만으로 바꾸기 위해 쿼리로 뒀다.
 */
function mockRiskReview(objectionText: string): RiskReview {
  const skipped = new URLSearchParams(window.location.search).get('mock_risk') === 'skipped'
  return { objection_text: objectionText, applied: !skipped, skipped }
}

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
      /*
       * 항상 `form_only` 다 — 위 관심사 추출은 키워드 매칭이고 LLM 호출이 아니다. 실서버도
       * 같은 이유로 항상 이 값을 보낸다(API_CONTRACT ★2026-07-26 · 가정 #63). 구 구현은
       * 자유 텍스트가 있으면 `'llm'` 을 세워 화면에 「AI 파싱 반영」 배지를 띄웠는데,
       * 하지 않은 일을 했다고 말하는 배지였다.
       */
      parse_source: 'form_only',
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
  mockConfirmedBudget = req.confirmed_budget

  const entering = MOCK_AREAS.filter((a) => median(a.cost.incl_premium) <= mockConfirmedBudget)

  return {
    data_as_of: '2026-Q1', // mockRecommend와 같은 값 — 계약상 둘은 같은 원천이다
    confirmed_budget: mockConfirmedBudget,
    composition: req.composition,
    preview:
      entering.length === 0
        ? { area_count: 0 }
        : {
            area_count: entering.length,
            rent_range: range(entering.map((a) => a.monthly_rent)),
            floating_range: range(entering.map((a) => a.daily_floating)),
          },
  }
}

/**
 * 목 세션의 확정 예산(B₀).
 *
 * 실제 BE는 세션에 B₀를 들고 `/recommend`가 그 값을 읽는다(계약 §4 — `?budget=` 쿼리를 두지
 * 않는 이유). 목에 이 변수가 없으면 `/budget`만 예산에 반응하고 `/recommend`는 고정 응답을 내어,
 * **하단 프리뷰는 "2곳"인데 목록에는 4곳이 그대로 남는** 모순이 생긴다. 목이 데모 폴백인 이상
 * (데모 무중단 원칙) 그 모순은 심사 중에 그대로 보인다.
 */
let mockConfirmedBudget = 8000

/** 부담률 임계 θ — BE `ReverseCheck.DEFAULT_THETA`와 같은 값이어야 판정이 갈리지 않는다. */
const THETA = 0.15

const median = ([low, high]: [number, number]) => (low + high) / 2
const range = (xs: number[]): [number, number] => [Math.min(...xs), Math.max(...xs)]

/**
 * 판정·근거문은 예산에 따라 달라지므로 고정 데이터에서 뺀다.
 * `burden_ratio` 는 실 계약에서 생략될 수 있지만(추정매출 결측) 목 픽스처 4곳은 전건 매출이
 * 있으므로 필수로 좁힌다 — 목 판정 규칙이 결측 분기를 떠안지 않아도 된다.
 */
type MockAreaBase = Omit<Area, 'verdict' | 'reason_text'> & { burden_ratio: number }

/**
 * 목 후보 4곳 — BE 데모 픽스처와 같은 3곳(망원·합정·홍대입구)에 조건부 적합 1곳(상수)을 더했다.
 * 판정 3종 마커를 한 화면에서 모두 확인하기 위한 조합이라 실 API에는 없다.
 */
const MOCK_AREAS: MockAreaBase[] = [
  {
    area_code: 'A-1101',
    name: '망원역 상권',
    lat: 37.5556,
    lng: 126.9106,
    score: 75,
    breakdown: { w1: 0.82, w2: 0.74, w3: 0.68, w4: 0.71, w5: 0.77 },
    cost: { ex_premium: [5600, 6800], incl_premium: [7000, 8500] },
    monthly_rent: 198,
    est_sales: 1800,
    daily_floating: 24500,
    burden_ratio: 0.11,
    rent_source: { org: 'REB', district: '홍대합정상권', fallback: false },
    transit: { station: '망원', line: '6', distance_m: 320, daily_riders: 21000, fallback: false },
  },
  {
    area_code: 'A-1102',
    name: '합정역 상권',
    lat: 37.5495,
    lng: 126.9139,
    score: 71,
    breakdown: { w1: 0.79, w2: 0.81, w3: 0.55, w4: 0.66, w5: 0.7 },
    cost: { ex_premium: [5800, 7000], incl_premium: [7200, 8700] },
    monthly_rent: 273,
    est_sales: 2100,
    daily_floating: 22800,
    burden_ratio: 0.13,
    rent_source: { org: 'REB', district: '홍대합정상권', fallback: false },
    transit: { station: '합정', line: '2·6', distance_m: 210, daily_riders: 68000, fallback: false },
  },
  {
    area_code: 'A-1104',
    name: '상수역 상권',
    lat: 37.5478,
    lng: 126.9227,
    score: 68,
    breakdown: { w1: 0.7, w2: 0.66, w3: 0.6, w4: 0.62, w5: 0.64 },
    cost: { ex_premium: [5400, 6300], incl_premium: [7600, 9100] },
    monthly_rent: 244,
    est_sales: 1900,
    daily_floating: 19800,
    burden_ratio: 0.13,
    rent_source: { org: 'REB', district: '홍대합정상권', fallback: true },
    transit: { station: '상수', line: '6', distance_m: 260, daily_riders: 17400, fallback: false },
  },
  {
    area_code: 'A-1103',
    name: '홍대입구역 상권',
    lat: 37.5572,
    lng: 126.9236,
    score: 70,
    breakdown: { w1: 0.91, w2: 0.88, w3: 0.31, w4: 0.74, w5: 0.52 },
    cost: { ex_premium: [5600, 6500], incl_premium: [6900, 8100] },
    monthly_rent: 456,
    est_sales: 2400,
    daily_floating: 38200,
    burden_ratio: 0.19,
    rent_source: { org: 'REB', district: '홍대합정상권', fallback: false },
    transit: { station: '홍대입구', line: '2', distance_m: 180, daily_riders: 92000, fallback: false },
  },
]

/**
 * 판정 4단계 — BE `ReverseCheck.evaluate`와 같은 규칙이다 (스펙 §4-1·§4-2).
 * 진입 = 권리금 포함 구간 중앙값 ≤ 예산. 제외 중앙값 ≤ 예산 < 포함 중앙값이면 조건부 적합,
 * 진입했어도 부담률이 θ를 넘으면 유의.
 */
function mockVerdict(area: MockAreaBase, budget: number): Verdict {
  if (budget < median(area.cost.ex_premium)) return 'OUT_OF_SCOPE'
  if (budget < median(area.cost.incl_premium)) return 'CONDITIONAL'
  return area.burden_ratio <= THETA ? 'FIT' : 'CAUTION'
}

/** 근거문 — BE `ReasonTemplate.reason`과 같은 문안. */
function mockReason(name: string, verdict: Verdict, burdenRatio: number): string {
  const pct = Math.round(burdenRatio * 100)
  switch (verdict) {
    case 'FIT':
      return `${name} — 길단위 유동·배후 인구가 서울 상위 구간이며 환산임대료 부담률 ${pct}%로 임계 이내입니다.`
    case 'CAUTION':
      return `${name} — 수요 지표는 상위 구간이나 환산임대료 부담률 ${pct}%로 임계를 초과합니다.`
    case 'CONDITIONAL':
      return `${name} — 권리금 포함 시 예산을 초과하나, 무권리 매물 확보 시 진입 가능한 구간입니다.`
    default:
      return `${name} — 현재 예산 기준으로는 진입 범위 밖입니다.`
  }
}

/**
 * 목 recommend — 세션 확정 예산(`mockConfirmedBudget`) 기준으로 판정을 다시 매긴다.
 *
 * 판정·근거문이 예산의 함수라는 점이 이 화면의 요지이므로(슬라이더를 움직이면 목록·지도가 함께
 * 바뀐다) 목도 같은 규칙으로 계산한다. `areas`는 실제 응답과 같이 **종합점수 내림차순**이며
 * 범위 외도 섞어 보낸다 — 화면이 그것을 걸러내는 동작까지 목으로 확인할 수 있어야 한다.
 */
export async function mockRecommend(): Promise<RecommendResponse> {
  await new Promise((r) => setTimeout(r, 250))

  const areas: Area[] = MOCK_AREAS.map((a) => {
    const verdict = mockVerdict(a, mockConfirmedBudget)
    return { ...a, verdict, reason_text: mockReason(a.name, verdict, a.burden_ratio) }
  }).sort((a, b) => b.score - a.score)

  const avg = (xs: number[]) => Math.round(xs.reduce((t, x) => t + x, 0) / xs.length)

  return {
    data_as_of: '2026-Q1',
    total_count: areas.length,
    // 요약은 예산과 무관한 후보 풀 전체의 평균이다 (BE `LocationService.summary`와 동일).
    summary: {
      avg_rent: avg(MOCK_AREAS.map((a) => a.monthly_rent)),
      avg_sales: avg(MOCK_AREAS.map((a) => a.est_sales)),
    },
    areas,
    risk_review: mockRiskReview(
      '수요 상위 상권일수록 경쟁밀도가 높아, 추정매출 하위 시나리오에서는 부담률이 임계를 넘을 수 있습니다. 유의 판정 유지가 타당합니다.',
    ),
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

/* ─────────────────────────── 탐색·역방향 (FE-04) ─────────────────────────── */

/**
 * 목 explore — 데모 프로필(만 32세·자기자본 5,000만·서울 카페, 확정 예산 8,000만) 기준
 * 실제 백엔드 응답을 그대로 옮긴 값이다. 심사 동선과 수치가 어긋나지 않게 하려면
 * 목을 임의 값으로 두면 안 된다 (LLM 장애·BE 미기동 시 이게 최종 화면이 된다).
 */
const MOCK_PLAN: ExplorePlanEvent = {
  axes: ['A1', 'A4'],
  axis_labels: { A1: '예산', A4: '권리금 조건' },
  rationale: '예산 축을 기준으로 인접 시나리오의 진입·지속 경계를 검토했습니다.',
}

const MOCK_FUNDING = {
  name: '민간투자연계형 매칭융자',
  amount_max: 50000,
  rate: 4.25,
  rate_type: 'variable' as const,
  rate_note:
    '정책자금 기준금리 3.85% + 0.4%p = 연 4.25% (’26년 3/4분기, 2026-07-10 적용 · 분기별 변동금리)',
  term_assumed: 96,
  status: 'open',
  source: { org: '소진공', url: 'https://ols.semas.or.kr', collected: '2026-07-25' },
  source_quote: null,
}

const MOCK_INSIGHTS: ExploreInsightEvent[] = [
  {
    insight_id: 'i-1',
    type: 'T1',
    headline:
      '2,770만 원을 추가 확보하면 진입 가능 후보는 342곳에서 1,020곳으로 늘어납니다. 다만 해당 금액을 민간투자연계형 매칭융자(연 4.25%, 96개월 상환)으로 조달할 경우 월 상환 부담 34만 원을 반영하면 지속 안정 후보는 354곳입니다. 자격 요건 부합 여부만 확인된 것이며, 실제 한도와 심사 결과는 해당 기관이 정합니다.',
    delta: { n_entry_before: 342, n_entry_after: 1020, n_sustain_after: 354, score_delta: 644.35 },
    gap_amount: 2770,
    marginal_payment: 34,
    funding: MOCK_FUNDING,
    disclaimer: true,
  },
  {
    insight_id: 'i-2',
    type: 'T1',
    headline:
      '3,030만 원을 추가 확보하면 진입 가능 후보는 342곳에서 1,036곳으로 늘어납니다. 다만 해당 금액을 민간투자연계형 매칭융자(연 4.25%, 96개월 상환)으로 조달할 경우 월 상환 부담 37만 원을 반영하면 지속 안정 후보는 354곳입니다. 자격 요건 부합 여부만 확인된 것이며, 실제 한도와 심사 결과는 해당 기관이 정합니다.',
    delta: { n_entry_before: 342, n_entry_after: 1036, n_sustain_after: 354, score_delta: 643.68 },
    gap_amount: 3030,
    marginal_payment: 37,
    funding: MOCK_FUNDING,
    disclaimer: true,
  },
  {
    insight_id: 'i-3',
    type: 'T2',
    headline:
      '7,931만 원까지 낮춰도 현재 후보 342곳이 전부 유지됩니다. 차액을 예비 운영자금으로 두면 지속 여력 지표가 개선됩니다.',
    delta: { n_entry_before: 342, n_entry_after: 342, n_sustain_after: 342, score_delta: 0 },
    disclaimer: true,
  },
]

/** 계단 함수 — 실측 91점을 그대로 쓴다 (프론티어 차트 형태가 목에서도 같아야 한다). */
const MOCK_FRONTIER: [number, number][] = [
  [6531, 6], [6981, 25], [7004, 30], [7089, 42], [7099, 61], [7128, 70], [7329, 82], [7333, 92],
  [7358, 107], [7430, 114], [7477, 131], [7504, 150], [7533, 163], [7683, 178], [7774, 188],
  [7776, 199], [7778, 212], [7803, 225], [7806, 237], [7830, 247], [7831, 259], [7863, 271],
  [7870, 290], [7892, 305], [7896, 320], [7922, 329], [7931, 342], [8011, 360], [8043, 370],
  [8068, 371], [8077, 382], [8160, 389], [8165, 399], [8166, 403], [8225, 405], [8226, 425],
  [8249, 434], [8274, 449], [8314, 487], [8347, 492], [8370, 497], [8372, 507], [8398, 533],
  [8428, 536], [8483, 548], [8514, 562], [8549, 569], [8575, 595], [8635, 614], [8723, 624],
  [8815, 638], [8819, 665], [8842, 673], [8898, 681], [8962, 708], [9056, 716], [9167, 749],
  [9170, 754], [9197, 766], [9198, 775], [9256, 783], [9314, 810], [9370, 815], [9407, 836],
  [9408, 839], [9547, 852], [9580, 860], [9673, 861], [9815, 872], [9854, 884], [9879, 899],
  [9967, 923], [10235, 936], [10298, 948], [10316, 951], [10343, 962], [10436, 968], [10437, 976],
  [10500, 983], [10533, 989], [10679, 1016], [10770, 1020], [10851, 1024], [11030, 1036],
  [11418, 1037], [11683, 1040], [11824, 1041], [12338, 1045], [12615, 1053], [12733, 1057],
  [15157, 1061],
]

export interface ExploreHandlers {
  onPlan: (e: ExplorePlanEvent) => void
  onInsight: (e: ExploreInsightEvent) => void
  onDone: (e: ExploreDoneEvent) => void
}

/** 목 explore — plan → insight(1건씩) → done. refine은 무LLM 모드와 동일하게 보내지 않는다. */
export async function mockExplore(
  handlers: ExploreHandlers,
  currentBudget: number,
  signal?: AbortSignal,
): Promise<void> {
  await new Promise((r) => setTimeout(r, 200))
  if (signal?.aborted) return
  handlers.onPlan(MOCK_PLAN)

  for (const insight of MOCK_INSIGHTS) {
    await new Promise((r) => setTimeout(r, 320))
    if (signal?.aborted) return
    handlers.onInsight(insight)
  }

  await new Promise((r) => setTimeout(r, 200))
  if (signal?.aborted) return
  handlers.onDone({
    scenarios_explored: 75,
    frontier_points: MOCK_FRONTIER,
    current_budget: currentBudget,
  })
}

/**
 * 목 check-area — 판정·부족분은 area_code로 결정적으로 갈라 준다.
 * 상품 목록은 실측처럼 `amount_max` 내림차순 고정이며 프론트는 재정렬하지 않는다.
 */
export async function mockCheckArea(areaCode: string): Promise<CheckAreaResponse> {
  await new Promise((r) => setTimeout(r, 220))

  const sum = [...areaCode].reduce((acc, ch) => acc + ch.charCodeAt(0), 0)
  const inBudget = sum % 3 === 0

  return {
    verdict: inBudget ? 'FIT' : 'CONDITIONAL',
    gap_amount: inBudget ? 0 : 962 + (sum % 13) * 100,
    matching_products: [
      {
        name: 'ESG 실천기업 보증',
        amount_max: 80000,
        rate_type: 'variable',
        rate_note:
          '서울시자금(ESG 자금) 이용 시 은행금리에서 2.5% 차감(서울시 부담), 그 외의 경우 자금에 따라 상이',
        data_as_of: '2026-07-21',
        source: { org: '서울신용보증재단', url: 'https://www.seoulshinbo.co.kr', collected: '2026-07-25' },
        source_quote: null,
      },
      {
        name: '미래 유망기업 성장지원 보증',
        amount_max: 80000,
        rate: 3.0,
        rate_type: 'fixed',
        rate_note: '서울시자금(혁신형기업도약자금) 이용 시 연 3.0% 고정금리, 그 외의 경우 자금에 따라 상이',
        data_as_of: '2026-07-21',
        source: { org: '서울신용보증재단', url: 'https://www.seoulshinbo.co.kr', collected: '2026-07-25' },
        source_quote: null,
      },
      {
        name: '민간투자연계형 매칭융자',
        amount_max: 50000,
        rate: 4.25,
        rate_type: 'variable',
        rate_note: '정책자금 기준금리 3.85% + 0.4%p = 연 4.25% (’26년 3/4분기, 2026-07-10 적용)',
        data_as_of: '2026-07-21',
        source: { org: '소진공', url: 'https://ols.semas.or.kr', collected: '2026-07-25' },
        source_quote: null,
      },
    ],
    risk_review: mockRiskReview(
      '권리금 포함 비용 구간 상단을 기준으로 하면 부족분이 더 커질 수 있어, 구간 하단 기준 판정임을 함께 표기해야 합니다.',
    ),
  }
}

import {
  BarChart3,
  Brain,
  Building2,
  Compass,
  Landmark,
  ShieldCheck,
  Wallet,
  Zap,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'

/**
 * 랜딩 문구·수치 (이슈 #101).
 *
 * **수치는 전부 실제 적재 데이터에서 온 것이다.** 랜딩은 심사위원이 가장 먼저 보는 화면이라
 * 여기서 한 번 부풀리면 뒤 화면의 숫자까지 의심받는다. 시안에 있던 「정책자금 120개+」·
 * 「사용자 만족도 98%」·「24년 11월 기준」은 근거가 없거나 실제와 달라 아래 값으로 바꿨다.
 *
 * 갱신할 때는 근거(주석의 출처)도 함께 고친다.
 */

export interface Kpi {
  icon: LucideIcon
  label: string
  value: string
  note: string
}

/** 근거: `commercial_area` 1,650행 · `/api/recommend` total_count 1,059 · `finance_product` 26행 */
export const KPIS: Kpi[] = [
  {
    icon: Building2,
    label: '분석 대상 서울 상권',
    value: '1,650',
    note: '업종 필터 후 후보 1,059곳',
  },
  {
    icon: Landmark,
    label: '정책자금·보증 상품',
    value: '26',
    note: '소진공 · 서울신용보증재단 · KB',
  },
  {
    icon: Zap,
    label: '결정적 계산',
    value: '0.1초',
    note: '예산 변경 시 전 결과 재계산',
  },
  {
    icon: ShieldCheck,
    label: '공개 데이터 출처',
    value: '7곳',
    note: '전 수치에 출처·기준일 표기',
  },
]

export interface ProcessStep {
  icon: LucideIcon
  no: string
  title: string
  desc: string
}

/** 스펙 §1의 3단계 AI 개입 구도를 사용자 동선 순서로 풀어 쓴 것이다. */
export const PROCESS: ProcessStep[] = [
  {
    icon: Brain,
    no: '01',
    title: 'AI 자금 진단',
    desc: '자유 문장에서 관심사를 읽고, 금액은 폼 값만 사용합니다.',
  },
  {
    icon: Wallet,
    no: '02',
    title: '예산 계산',
    desc: '정책자금·보증·대출을 조합해 보수·적극 두 시나리오를 만듭니다.',
  },
  {
    icon: Compass,
    no: '03',
    title: '입지 탐색',
    desc: '예산 임계마다 열리는 상권을 계단 함수로 계산합니다.',
  },
  {
    icon: BarChart3,
    no: '04',
    title: 'AI 추천 · 검증',
    desc: '판정 4단계를 제시하고, 검증 절차가 데이터로 반박합니다.',
  },
]

export interface Showcase {
  eyebrow: string
  title: string
  desc: string
  points: string[]
  shotUrl: string
  alt: string
}

export const SHOWCASES: Omit<Showcase, 'shot'>[] = [
  {
    eyebrow: '입지 추천',
    title: '내 예산으로 도달 가능한\n상권만 지도에 남습니다',
    desc: '권리금 포함·제외 비용을 각각 계산해 판정을 4단계로 나눕니다. 예산을 움직이면 판정이 그 자리에서 다시 계산됩니다.',
    points: [
      '적합 · 조건부 적합 · 유의 · 범위 외 4단계 판정',
      '임대료·교통 출처와 데이터 기준일 상시 표기',
      '상권 클릭 → 부족분과 자격 요건 부합 상품 확인',
    ],
    shotUrl: 'ventry.app/map',
    alt: '입지 추천 화면 — 지도의 판정 마커와 추천 상권 목록, 상권별 근거 패널',
  },
  {
    eyebrow: '결정공간 탐색',
    title: '예산을 조금 더 확보하면\n어디까지 열리는가',
    desc: '예산이 임계를 넘는 순간 후보가 계단식으로 열립니다. 상환 부담을 반영한 지속 가능 후보도 함께 계산합니다.',
    points: [
      '예산-입지 프론티어를 계단 함수로 시각화',
      '진입 가능 수와 지속 가능 수를 나란히 비교',
      '어떤 도구를 어떤 순서로 계산했는지 로그 공개',
    ],
    shotUrl: 'ventry.app/explore',
    alt: '결정공간 탐색 화면 — 예산-입지 프론티어 계단 차트와 탐색 시나리오 목록',
  },
]

/**
 * 실제로 데이터를 가져오는 곳만 적는다 (README 「데이터 출처·라이선스」 표 기준).
 * 지하철 역사·승하차도 **서울 열린데이터광장 OpenAPI**(OA-21232·OA-12914)로 받으므로
 * 서울교통공사를 따로 적지 않는다 — 쓰지 않는 기관을 나열하면 출처 표기가 장식이 된다.
 */
export const DATA_SOURCES = [
  { name: '서울 열린데이터광장', via: 'OpenAPI' },
  { name: '한국부동산원', via: 'OpenAPI' },
  { name: '공공데이터포털', via: 'OpenAPI' },
  { name: 'KOSIS 국가통계포털', via: 'OpenAPI' },
  { name: '소상공인시장진흥공단', via: '공개 공고문' },
  { name: '서울신용보증재단', via: '공개 공고문' },
  { name: 'KB국민은행', via: '공개 문서' },
]

/**
 * 히어로 캡처 위에 얹는 수치. **캡처에 실제로 찍혀 있는 값과 같아야 한다** —
 * 오버레이가 캡처와 다른 숫자를 말하면 둘 중 하나가 거짓이 된다.
 *
 * ⚠️ **재적재 파급 대상이다.** 이 값들은 DB 에서 오지 않고 캡처 시점의 실측을 손으로 옮긴
 * 상수라, 덤프를 다시 구우면 캡처·이 상수·도슨트 대본이 **함께** 움직여야 한다. 어느 하나만
 * 남으면 랜딩이 현재 데이터와 다른 숫자를 말한다. 갱신 절차와 함께 움직이는 항목 목록은
 * `docs/tasks/CM-05_제출_검수_체크리스트.md` 「재적재 파급」 절에 있다 (계약 리뷰 P2-6).
 */
export const HERO_OVERLAY: { label: string; value: string; note?: string }[] = [
  { label: '확정 예산', value: '6,600만원' },
  /*
   * **한정 문구를 뗄 수 없다** (실사용 점검 2026-07-29).
   *
   * 「403곳」만 적으면 6,600만원으로 403곳에 갈 수 있다고 읽힌다. 실제로 그 예산에서 바로
   * 진입 가능한 곳은 6곳이고, 나머지 397곳은 **무권리 매물을 잡았을 때** 열리는 조건부다.
   * 앱 안에서는 이 구분을 모든 화면이 지키는데(`BudgetSliderBar`·`Recommend`) 랜딩 오버레이
   * 한 곳만 떼고 있었다. 캡처 이미지에는 이 문장이 이미 찍혀 있어, 오버레이만 말을 줄인 것이다.
   */
  {
    label: '추천 상권',
    value: '403곳',
    note: '진입 가능 6곳 · 조건부 적합 397곳 (무권리 매물 기준)',
  },
  { label: '평균 추정 매출', value: '1,042만원' },
]

/**
 * 적재한 데이터와 그 출처 (README 「데이터 출처·라이선스」 표 + `data_source_meta` 기준일).
 * 기준일은 DB의 실제 값이다 — 화면이 날짜를 지어내지 않는다.
 */
export const DATA_TABLE = [
  {
    what: '추정매출 · 유동/길단위인구 · 상주/직장인구 · 점포 · 상권변화지표',
    org: '서울 열린데이터광장 상권분석서비스',
    cycle: '분기',
    asOf: '2026-Q1',
  },
  {
    what: '지하철 역사 좌표 · 역별 승하차 인원',
    org: '서울 열린데이터광장 (OA-21232 · OA-12914)',
    cycle: '분기 · 일',
    asOf: '2026-07',
  },
  {
    what: '상권 임대료 · 전환율 · 상권 구획도',
    org: '한국부동산원 임대동향조사',
    cycle: '분기',
    asOf: '2026-Q1',
  },
  {
    what: '권리금',
    org: '한국부동산원 임대동향조사',
    cycle: '연 1회',
    asOf: '2025년(전년 기준)',
  },
  {
    /*
     * 출처를 실적재 값에 맞춘다 (실사용 점검 2026-07-29). DB `data_source_meta.interior` 와
     * 기술설명서 2장은 「공정위 가맹정보 2025 (가맹점 기준·상향)」인데 랜딩만 KOSIS 라고
     * 적고 있었다 — KOSIS 는 총액 정합성 **상한 검증**에만 쓰고 적재값의 출처가 아니다
     * (`ai/batch/collect/startup_cost.py`). 출처 규율이 이 서비스의 근거인 만큼, 랜딩의
     * 출처 오기는 다른 수치의 신뢰도까지 함께 깎는다. 「가맹점 기준·상향」도 함께 밝힌다 —
     * 판정을 움직이는 비용이 의도적으로 보수적 고평가라는 사실이 화면에 없었다.
     */
    what: '창업비용 통계 (가맹점 기준·상향)',
    org: '공정위 가맹정보 · 공공데이터포털',
    cycle: '연',
    asOf: '2025',
  },
  {
    what: '정책자금 · 보증 · 대출 상품 26건',
    org: '소상공인시장진흥공단 · 서울신용보증재단 · KB국민은행 공개 문서',
    cycle: '수시',
    asOf: '2026-07-21',
  },
]

/** 데이터가 화면의 숫자가 되기까지 (스펙 §0-1 · §1). */
export const PIPELINE = [
  {
    title: '수집 · 적재',
    desc: '공개 API와 공고 원문을 배치로 받아 좌표계(WGS84)와 단위(만원)를 맞춰 적재합니다. 가정은 전부 문서에 기록합니다.',
  },
  {
    title: '결정적 계산',
    desc: '자격 필터 · 비용 계산 · 점수화 · 프론티어 · 역방향 판정이 순수 함수로 돌아갑니다. 서빙 경로에 ML 모델은 없습니다.',
  },
  {
    // 세 역할이 전부 실제로 돈다 — 탐색 축 선정(plan)·인사이트 언어화(refine)·리스크 검증
    // 반박. 언어화는 수치 집합이 템플릿과 다르면 서버가 폐기하므로 「다시 쓰기」에 그친다
    // (RefinePrompt.sanitize). 자유 텍스트 파싱은 여전히 키워드 매칭이라 여기 넣지 않는다.
    title: 'AI 계획 · 언어화 · 검증',
    desc: 'AI는 어떤 축을 탐색할지 계획하고, 계산 결과를 문장으로 옮기고, 그 결과에 반대 논리를 세웁니다. 수치를 만들거나 다시 계산하지 않습니다.',
  },
]

export const GITHUB_URL = 'https://github.com/yutakdv/Ventry'

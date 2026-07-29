import type { Industry } from '../api/types'

export type YesNo = 'yes' | 'no'

/** 스코프: 서울 / 카페·음식점 (스펙). */
export const SIDO = [{ value: 'seoul', label: '서울특별시' }] as const

export const SEOUL_GU = [
  '종로구', '중구', '용산구', '성동구', '광진구', '동대문구', '중랑구', '성북구',
  '강북구', '도봉구', '노원구', '은평구', '서대문구', '마포구', '양천구', '강서구',
  '구로구', '금천구', '영등포구', '동작구', '관악구', '서초구', '강남구', '송파구', '강동구',
] as const

export const INDUSTRY: { value: Industry; label: string }[] = [
  { value: 'cafe', label: '카페' },
  { value: 'food', label: '음식점' },
]

/*
 * 「추가 정보(선택)」 3종(창업 희망 시기·희망 운영 형태·상권 유형)을 제거했다
 * (실사용 점검 2026-07-29). free_text 꼬리표로만 붙었는데 서버 키워드 3군
 * (권리금 / 임대·월세 / 유동·손님)에 **한 선택지도 걸리지 않아** 결과가 전혀 달라지지 않았다.
 * 상권 유형은 서울시 공식 구분(골목·발달·전통시장·관광특구)을 쓰는 필터로 화면 4에 옮겼다.
 */

/** 화면 폼 상태 — 금액은 원 단위 문자열(제출 시 만원 환산). */
export interface FormState {
  industry: Industry | ''
  isExisting: YesNo | null
  age: string
  sido: string
  gu: string
  capitalWon: string
  monthlyWon: string
  collateral: YesNo | null
  freeText: string
}

export const EMPTY_FORM: FormState = {
  industry: '',
  isExisting: null,
  age: '',
  sido: '',
  gu: '',
  capitalWon: '',
  monthlyWon: '',
  collateral: null,
  freeText: '',
}

/** 데모 프로필 (§0-8) — 금액은 원 단위. "마이데이터 불러오기"가 이 값으로 폼을 채운다. */
export const DEMO_PROFILE: FormState = {
  industry: 'cafe',
  isExisting: 'no', // 예비 창업자
  age: '32',
  sido: 'seoul',
  gu: '마포구',
  capitalWon: '50000000', // 5,000만원
  monthlyWon: '2500000', // 250만원
  collateral: 'yes',
  freeText: '권리금이 제일 걱정이에요. 역세권이 아니어도 괜찮습니다.',
}

/* ─────────── Validation (필수 8항목) ─────────── */

export type RequiredField =
  | 'industry'
  | 'isExisting'
  | 'age'
  | 'sido'
  | 'gu'
  | 'capitalWon'
  | 'monthlyWon'
  | 'collateral'

export const REQUIRED_MESSAGES: Record<RequiredField, string> = {
  industry: '업종을 선택해주세요.',
  isExisting: '사업자 여부를 선택해주세요.',
  age: '나이를 입력해주세요.',
  sido: '시/도를 선택해주세요.',
  gu: '구/군을 선택해주세요.',
  capitalWon: '자기자본을 입력해주세요.',
  monthlyWon: '월 투자 가능 금액을 입력해주세요.',
  collateral: '담보 제공 여부를 선택해주세요.',
}

export type FormErrors = Partial<Record<RequiredField, string>>

/**
 * 나이 허용 범위 — **서버 `DiagnoseController.AGE_MIN/AGE_MAX` 의 사본이다.**
 * 한쪽을 고치면 다른 쪽도 함께 고친다. 갈라지면 화면이 통과시킨 값을 서버가 거절한다.
 */
export const AGE_MIN = 15
export const AGE_MAX = 100

export const AGE_RANGE_MESSAGE = `나이는 만 ${AGE_MIN}~${AGE_MAX}세 범위로 입력해주세요.`

/**
 * 미입력 필수 항목 → 에러 메시지 맵을 반환.
 * 선택 항목(자유입력)은 검증하지 않는다.
 *
 * **나이는 범위까지 본다** (실사용 점검 2026-07-29). 종전에는 `!f.age` 로 빈 값만 걸러서
 * `999`·`322`·출생연도(`1994`)가 그대로 서버로 갔고, 서버가 400 으로 정확히 거절했는데도
 * 화면은 그 메시지를 버리고 **목 데이터로 전환**했다 — 사용자 입력 오류가 「서버에 연결하지
 * 못했다」로 둔갑하고, 이후 진짜 수치를 보면서도 「실제 조사 결과가 아닙니다」 배너가 남았다.
 * 근본 원인은 `api/client.ts` 의 4xx 미분기이며 그쪽도 함께 고쳤다. 여기서는 **애초에 서버까지
 * 보내지 않는다** — 폼에서 즉시 알려주는 편이 왕복 한 번보다 빠르고 친절하다.
 */
export function validateDiagnose(f: FormState): FormErrors {
  const e: FormErrors = {}
  if (!f.industry) e.industry = REQUIRED_MESSAGES.industry
  if (f.isExisting == null) e.isExisting = REQUIRED_MESSAGES.isExisting
  if (!f.age) {
    e.age = REQUIRED_MESSAGES.age
  } else {
    const age = Number(f.age)
    if (!Number.isInteger(age) || age < AGE_MIN || age > AGE_MAX) e.age = AGE_RANGE_MESSAGE
  }
  if (!f.sido) e.sido = REQUIRED_MESSAGES.sido
  if (!f.gu) e.gu = REQUIRED_MESSAGES.gu
  if (!f.capitalWon) e.capitalWon = REQUIRED_MESSAGES.capitalWon
  if (!f.monthlyWon) e.monthlyWon = REQUIRED_MESSAGES.monthlyWon
  if (f.collateral == null) e.collateral = REQUIRED_MESSAGES.collateral
  return e
}

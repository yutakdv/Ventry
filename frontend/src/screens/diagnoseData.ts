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

/** 아래 3종은 계약 form에 없어 free_text 맥락으로 전송(범주형 → §0-1 무관). 선택 입력. */
export const START_TIMING = ['3개월 내', '6개월 내', '1년 내', '아직 미정'] as const
export const OP_TYPE = ['1인 운영', '2~3인', '4인 이상'] as const
export const AREA_TYPE = ['오피스 상권', '주거 상권', '대학가', '번화가', '상관없음'] as const

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
  startTiming: string
  opType: string
  areaType: string
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
  startTiming: '',
  opType: '',
  areaType: '',
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
  startTiming: '6개월 내',
  opType: '1인 운영',
  areaType: '오피스 상권',
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
 * 미입력 필수 항목 → 에러 메시지 맵을 반환.
 * 선택 항목(창업시기·운영형태·상권유형·자유입력)은 검증하지 않는다.
 */
export function validateDiagnose(f: FormState): FormErrors {
  const e: FormErrors = {}
  if (!f.industry) e.industry = REQUIRED_MESSAGES.industry
  if (f.isExisting == null) e.isExisting = REQUIRED_MESSAGES.isExisting
  if (!f.age) e.age = REQUIRED_MESSAGES.age
  if (!f.sido) e.sido = REQUIRED_MESSAGES.sido
  if (!f.gu) e.gu = REQUIRED_MESSAGES.gu
  if (!f.capitalWon) e.capitalWon = REQUIRED_MESSAGES.capitalWon
  if (!f.monthlyWon) e.monthlyWon = REQUIRED_MESSAGES.monthlyWon
  if (f.collateral == null) e.collateral = REQUIRED_MESSAGES.collateral
  return e
}

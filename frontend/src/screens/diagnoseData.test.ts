import { describe, expect, it } from 'vitest'
import {
  AGE_MAX,
  AGE_MIN,
  AGE_RANGE_MESSAGE,
  DEMO_PROFILE,
  EMPTY_FORM,
  REQUIRED_MESSAGES,
  validateDiagnose,
  type FormState,
} from './diagnoseData'

/**
 * 진단 폼 검증 (실사용 점검 2026-07-29).
 *
 * 이 파일이 지키는 것은 **나이가 서버까지 가지 않는 것**이다. 종전에는 `!f.age` 로 빈 값만
 * 걸러서 `999`·출생연도가 그대로 전송됐고, 서버가 400 으로 정확히 거절했는데도 화면은
 * 그 메시지를 버리고 목 데이터로 세션 전체를 전환했다 — 사용자는 거부당한 줄 몰랐다.
 */

/** 필수 8항목이 모두 채워진 유효 폼. 검사 대상 필드만 바꿔 가며 쓴다. */
const valid: FormState = { ...EMPTY_FORM, ...DEMO_PROFILE }

describe('validateDiagnose — 나이 범위', () => {
  it('유효한 데모 프로필은 오류가 없다', () => {
    expect(validateDiagnose(valid)).toEqual({})
  })

  it('경계값은 통과한다', () => {
    for (const age of [AGE_MIN, AGE_MAX]) {
      expect(validateDiagnose({ ...valid, age: String(age) }).age).toBeUndefined()
    }
  })

  it('경계 밖은 범위 메시지로 막는다 — 서버까지 보내지 않는다', () => {
    for (const age of [AGE_MIN - 1, AGE_MAX + 1, 0, 3, 322, 999, 1994]) {
      expect(validateDiagnose({ ...valid, age: String(age) }).age).toBe(AGE_RANGE_MESSAGE)
    }
  })

  it('빈 값은 범위가 아니라 미입력 메시지다 — 두 사유를 섞지 않는다', () => {
    expect(validateDiagnose({ ...valid, age: '' }).age).toBe(REQUIRED_MESSAGES.age)
  })

  it('정수가 아닌 값도 막는다', () => {
    expect(validateDiagnose({ ...valid, age: '32.5' }).age).toBe(AGE_RANGE_MESSAGE)
    expect(validateDiagnose({ ...valid, age: 'abc' }).age).toBe(AGE_RANGE_MESSAGE)
  })
})

describe('validateDiagnose — 필수 항목', () => {
  it('빈 폼은 필수 8항목을 전부 보고한다', () => {
    const errors = validateDiagnose(EMPTY_FORM)
    expect(Object.keys(errors).sort()).toEqual(
      ['age', 'capitalWon', 'collateral', 'gu', 'industry', 'isExisting', 'monthlyWon', 'sido'].sort(),
    )
  })

  it('선택 입력(자유 텍스트)은 검증하지 않는다', () => {
    expect(validateDiagnose({ ...valid, freeText: '' })).toEqual({})
  })
})

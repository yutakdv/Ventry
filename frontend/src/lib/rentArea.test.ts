import { describe, expect, it } from 'vitest'
import { rentAreaBasis, rentAreaShort, rentPerPyeong } from './rentArea'

/**
 * 이 파일의 대표면적 상수는 **배치 상수의 복제본**이다(`ai/batch/preprocess/cost.py`).
 * 어긋나면 `monthly_rent` 는 새 면적 기준인데 라벨만 옛 면적을 말하게 된다 — 화면이 조용히
 * 거짓말을 하는 형태다. 사본 일치 자체는 언어 경계라 `ai/tests/test_rent_area_sync.py` 가
 * 대조하고, 여기서는 **그 상수를 쓰는 표현 규칙**을 잠근다.
 */
describe('rentAreaShort — 금액 라벨의 면적 조건', () => {
  it('전 화면이 같은 짧은 형태를 쓴다', () => {
    expect(rentAreaShort('cafe')).toBe('44㎡')
    expect(rentAreaShort('food')).toBe('51.7㎡')
  })

  it('업종을 모르면 null — 근거 없는 면적을 지어내지 않는다', () => {
    expect(rentAreaShort(null)).toBeNull()
    expect(rentAreaShort(undefined)).toBeNull()
  })
})

describe('rentPerPyeong — 역산 단가', () => {
  it('대표면적을 평으로 환산해 나눈다', () => {
    // 카페 44.0㎡ = 13.3평. 1,330만원 ÷ 13.3 = 100.0만원
    expect(rentPerPyeong(1330, 'cafe')).toBe('평당 약 100만원')
  })

  it('업종·금액이 유효하지 않으면 null (0·음수·비유한값 포함)', () => {
    expect(rentPerPyeong(198, null)).toBeNull()
    expect(rentPerPyeong(0, 'cafe')).toBeNull()
    expect(rentPerPyeong(-1, 'cafe')).toBeNull()
    expect(rentPerPyeong(Number.POSITIVE_INFINITY, 'cafe')).toBeNull()
    expect(rentPerPyeong(Number.NaN, 'cafe')).toBeNull()
  })
})

describe('rentAreaBasis — 출처 줄의 면적 근거', () => {
  it('오해의 출처가 평 단위 호가이므로 ㎡와 평을 함께 쓴다', () => {
    expect(rentAreaBasis('cafe')).toBe('카페 대표면적 44㎡(13.3평) 기준')
    expect(rentAreaBasis('food')).toBe('음식점 대표면적 51.7㎡(15.6평) 기준')
  })

  it('업종을 모르면 null', () => {
    expect(rentAreaBasis(null)).toBeNull()
  })
})

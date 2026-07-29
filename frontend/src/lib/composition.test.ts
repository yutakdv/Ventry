import { describe, expect, it } from 'vitest'
import { buildComposition } from './composition'
import type { Scenario } from '../api/types'

/**
 * `buildComposition` 은 API 계약이 **참조 구현으로 지목한 배분 규칙**이다
 * (docs/API_CONTRACT.md §2 D12 — "참조 구현: FE lib/composition.ts"). 계약 스스로
 * "규칙이 계약 밖에 있으면 클라이언트를 다시 구현할 때 결과가 조용히 갈린다"고 적은 자리라,
 * 여기서 깨지는 것은 표시가 아니라 **잔여 한도 계산의 입력**이다 — T1 인사이트의 근거 상품이
 * 갈린다. 그래서 규칙의 세 조항(자기자본 우선 · amount_max 클램프 · 잔액 소진)을 각각 잠근다.
 */
function scenario(composition: Scenario['composition']): Scenario {
  return {
    label: '적극',
    budget: 0,
    budget_min: 0,
    budget_max: 0,
    composition,
    products: [],
  }
}

const EQUITY_LAST = scenario([
  { type: 'policy_loan', amount_min: 0, amount_max: 3000 },
  { type: 'guarantee', amount_min: 0, amount_max: 2000 },
  { type: 'equity', amount_min: 1000, amount_max: 1000 },
])

describe('buildComposition — 계약 §2 D12 배분 규칙', () => {
  it('자기자본을 먼저 채운다 — 입력 순서가 뒤여도 마찬가지다', () => {
    // 자기자본은 심사와 무관한 확정 재원이라 순서가 규칙이지 입력 배열의 순서가 아니다.
    const result = buildComposition(EQUITY_LAST, 1500)
    expect(result[0]).toEqual({ type: 'equity', amount: 1000 })
    expect(result.map((c) => c.type)).toEqual(['equity', 'policy_loan', 'guarantee'])
  })

  it('각 항목을 amount_max 로 클램프하고 잔액을 다음 항목으로 넘긴다', () => {
    const result = buildComposition(EQUITY_LAST, 5000)
    expect(result).toEqual([
      { type: 'equity', amount: 1000 },
      { type: 'policy_loan', amount: 3000 },
      { type: 'guarantee', amount: 1000 }, // 5000 − 1000 − 3000
    ])
  })

  it('예산이 총 한도를 넘어도 한도 밖으로 배분하지 않는다', () => {
    const result = buildComposition(EQUITY_LAST, 99_999)
    expect(result.map((c) => c.amount)).toEqual([1000, 3000, 2000])
  })

  it('잔액이 0이 되면 이후 항목은 0으로 남는다 — 음수가 나오지 않는다', () => {
    const result = buildComposition(EQUITY_LAST, 1000)
    expect(result).toEqual([
      { type: 'equity', amount: 1000 },
      { type: 'policy_loan', amount: 0 },
      { type: 'guarantee', amount: 0 },
    ])
  })

  it('예산 0이면 전 항목이 0이다', () => {
    expect(buildComposition(EQUITY_LAST, 0).every((c) => c.amount === 0)).toBe(true)
  })

  it('자기자본만 있는 시나리오도 규칙이 같다 (m=0 일관성 케이스)', () => {
    const onlyEquity = scenario([{ type: 'equity', amount_min: 2000, amount_max: 2000 }])
    expect(buildComposition(onlyEquity, 5000)).toEqual([{ type: 'equity', amount: 2000 }])
  })

  it('입력 배열을 변형하지 않는다 — 세션에 보관된 시나리오가 오염되면 재계산이 갈린다', () => {
    const input = scenario([
      { type: 'policy_loan', amount_min: 0, amount_max: 3000 },
      { type: 'equity', amount_min: 1000, amount_max: 1000 },
    ])
    const before = input.composition.map((c) => c.type)
    buildComposition(input, 4000)
    expect(input.composition.map((c) => c.type)).toEqual(before)
  })
})

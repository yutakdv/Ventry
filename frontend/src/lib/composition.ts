import type { BudgetCompositionItem, Scenario } from '../api/types'

/**
 * 확정 예산을 조달 구성에 배분한다.
 * 자기자본은 심사와 무관한 확정 재원이므로 먼저 채우고, 나머지를 상품 한도 순서대로 배분한다.
 *
 * 화면 2(예산 확정)와 화면 3(하단 슬라이더)이 같은 `POST /budget`을 부르므로 배분 규칙도 하나여야
 * 한다 — 두 화면이 같은 금액에서 다른 구성을 보내면 마지막에 누른 쪽이 이기는 형태가 된다.
 */
export function buildComposition(scenario: Scenario, budget: number): BudgetCompositionItem[] {
  const ordered = [...scenario.composition].sort((a, b) =>
    a.type === 'equity' ? -1 : b.type === 'equity' ? 1 : 0,
  )
  let remaining = budget
  return ordered.map((c) => {
    const amount = Math.max(0, Math.min(c.amount_max, remaining))
    remaining -= amount
    return { type: c.type, amount }
  })
}

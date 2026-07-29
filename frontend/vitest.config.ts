import { defineConfig } from 'vitest/config'

/**
 * 순수 함수 단위 테스트만 돌린다 (jsdom·컴포넌트 렌더 없음).
 *
 * 프론트 테스트가 0건이던 자리를 메우되, 범위를 **계약 규칙을 코드로 옮긴 순수 함수**로 좁혔다.
 * 그 함수들은 부작용이 없어 테스트 비용이 가장 낮고, 동시에 회귀 시 계약 위반이 조용히 발생하는
 * 유일한 지점이기 때문이다 — 특히 `lib/composition.ts` 는 API 계약이 **"참조 구현"으로 직접
 * 지목한 파일**이다 (docs/API_CONTRACT.md §2 D12).
 */
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
})

import type { Verdict } from '../api/types'

/**
 * 판정 4단계 표기 (PROJECT_RULES §2 · Figma Verdict Badge).
 * 문구는 이 표에서만 나온다 — 화면에서 "승인" 계열 단어를 만들지 않기 위한 단일 통로다.
 */
export const VERDICT_LABEL: Record<Verdict, string> = {
  FIT: '적합',
  CONDITIONAL: '조건부 적합',
  CAUTION: '유의',
  OUT_OF_SCOPE: '범위 외',
}

/** 지도 마커 색 (Figma Verdict Marker). 범위 외는 지도에 노출하지 않는다. */
export const VERDICT_MARKER_COLOR: Record<Verdict, string> = {
  FIT: '#22a06b', // green/500
  CONDITIONAL: '#f5a800', // yellow/600
  CAUTION: '#ff9500', // orange/500
  OUT_OF_SCOPE: '#9ca1ab', // gray/400
}

/** 지도 범례 — 판정 기준 3종 (점수 구간이 아니다). */
export const MAP_LEGEND: Verdict[] = ['FIT', 'CONDITIONAL', 'CAUTION']

/**
 * **진입 가능** = 적합 + 유의. 권리금을 포함한 비용 중앙값이 확정 예산 이하인 곳이다.
 *
 * 조건부 적합이 여기 없는 것이 핵심이다 — 그쪽은 **무권리 매물을 잡아야** 열리는 구간이라
 * 확정 예산만으로는 갈 수 없다. `/budget` 프리뷰의 `area_count` 도 같은 정의를 쓰며,
 * 통합 게이트 D1 이 `FIT + CAUTION == area_count` 를 단언해 서버·화면의 정의를 묶어 둔다.
 *
 * 이 상수가 생긴 계기(2026-07-30): 지도가 세 판정을 한꺼번에 그리고 있어, 확정 예산 1억에서
 * 마커 100개 중 **실제로 갈 수 있는 곳은 36개**뿐이었다(8,000만에서는 100개 중 1개). 「내
 * 한도로 어디까지 가능한가」를 답하는 화면의 지도가 한도와 무관한 후보로 채워져 있었다.
 */
export const ENTRY_VERDICTS: Verdict[] = ['FIT', 'CAUTION']

/**
 * 목록·지도 판정 필터의 값. `ENTRY` 는 판정이 아니라 **두 판정의 합**이라 Verdict 에 넣지
 * 않는다 — 판정 4단계는 스펙 §0-4 가 고정한 어휘이고, 화면 필터가 거기에 항목을 더하는
 * 순간 「5번째 판정」처럼 읽힌다.
 */
export type VerdictFilter = Verdict | 'ALL' | 'ENTRY'

/** 판정 하나가 현재 필터에 걸리는가. 지도·목록·구분별 집계가 모두 이 하나를 쓴다. */
export function matchVerdict(verdict: Verdict, filter: VerdictFilter): boolean {
  if (filter === 'ALL') return true
  if (filter === 'ENTRY') return ENTRY_VERDICTS.includes(verdict)
  return verdict === filter
}

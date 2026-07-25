import type { Verdict } from '../api/types'

/**
 * 판정 4단계 표기 (CLAUDE.md §0-4 · Figma Verdict Badge).
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

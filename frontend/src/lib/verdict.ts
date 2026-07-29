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

/**
 * 지도 마커 색. 범위 외는 지도에 노출하지 않는다.
 *
 * **2026-07-30 재설계 — 종전 팔레트는 세 색 중 둘이 사실상 같은 색이었다.**
 * 조건부 적합 `#f5a800`(색상 41.1°)과 유의 `#ff9500`(35.1°)이 **색상환에서 6.1° 차이**,
 * 채도는 둘 다 100%, 명도는 48% vs 50% 였다. 36px 마커로 지도 배경 위에 찍히면 사람 눈이
 * 구분하지 못한다 — 데모 기본 예산에서 후보 대부분이 조건부 적합이라 지도가 통째로 한 색으로
 * 보였고, 판정 4단계라는 이 서비스의 핵심 장치가 화면에서 사라졌다.
 *
 * 새 팔레트는 **색상·명도를 함께 벌린다** (최소 색상차 68.7°, 흰 글자 대비 전부 4.5:1 이상):
 * - 적합 `#1b8759` 초록 154° — 도달했다
 * - 조건부 적합 `#2f63e8` 파랑 223° — **경고가 아니라 정보**다. 무권리 매물이라는 다른 조건이
 *   붙을 뿐이므로 노랑(주의 계열)보다 파랑이 뜻에 맞고, 앞뒤 색과도 가장 멀다
 * - 유의 `#d93a25` 빨강 7° — 부담률이 임계를 넘을 수 있는 구간
 *
 * 초록↔빨강은 적록색약이 어려워하는 조합이라 **색에만 의미를 싣지 않는다** — 마커 모양을
 * 함께 나눈다(`VERDICT_MARKER_SHAPE`). 색·모양·숫자 셋이 같은 것을 말한다.
 */
export const VERDICT_MARKER_COLOR: Record<Verdict, string> = {
  FIT: '#1b8759', // green/600
  CONDITIONAL: '#2f63e8', // blue/600
  CAUTION: '#d93a25', // red/600
  OUT_OF_SCOPE: '#9ca1ab', // gray/400
}

export type VerdictShape = 'circle' | 'square' | 'diamond'

/**
 * 지도 마커 모양 — **색을 못 봐도 판정을 읽을 수 있어야 한다** (WCAG 1.4.1 「색만으로 전달 금지」).
 * 범례가 같은 모양을 함께 보여 주므로 대조표가 화면 안에 있다.
 */
export const VERDICT_MARKER_SHAPE: Record<Verdict, VerdictShape> = {
  FIT: 'circle',
  CONDITIONAL: 'square',
  CAUTION: 'diamond',
  OUT_OF_SCOPE: 'circle',
}

/** 지도 범례 — 판정 기준 3종 (점수 구간이 아니다). */
export const MAP_LEGEND: Verdict[] = ['FIT', 'CONDITIONAL', 'CAUTION']

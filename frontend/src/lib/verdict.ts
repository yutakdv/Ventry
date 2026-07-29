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
 * **조건부 적합의 노랑은 KB 브랜드 색이라 유지한다.** `#f5a800`(yellow/600)은 KB 옐로
 * 계열이며, 후보 대부분이 조건부 적합이라 지도의 지배색이 곧 브랜드색이 된다 — 이 서비스가
 * KB 공모전 출품작인 만큼 바꾸지 않는다. 대신 **유의를 주황에서 빨강으로 옮겨** 충돌을 푼다.
 * 유의는 세 판정 중 가장 드물어(데모 기본 예산에서 533곳 중 6곳) 옮기는 비용이 가장 작다.
 *
 * 결과 (최소 색상차 6.1° → **41.1°**):
 * - 적합 `#1b8759` 초록 154° — 도달했다
 * - 조건부 적합 `#f5a800` 노랑 41° — **KB 브랜드색 유지**. 무권리 매물을 잡으면 열리는 구간
 * - 유의 `#c92a2a` 빨강 0° — 부담률이 임계를 넘을 수 있는 구간
 *
 * 초록↔빨강은 적록색약이 어려워하는 조합이라 **색에만 의미를 싣지 않는다** — 마커 모양을
 * 함께 나눈다(`VERDICT_MARKER_SHAPE`). 색·모양·숫자 셋이 같은 것을 말한다.
 */
export const VERDICT_MARKER_COLOR: Record<Verdict, string> = {
  FIT: '#1b8759', // green/600
  CONDITIONAL: '#f5a800', // yellow/600 — KB 브랜드색
  CAUTION: '#c92a2a', // red/700
  OUT_OF_SCOPE: '#9ca1ab', // gray/400
}

/**
 * 마커 안 숫자 색 — 배경 대비로 고른다.
 *
 * 종전에는 무조건 흰색이었는데, KB 옐로 위 흰 글자는 대비가 **2.00:1** 이라 점수가 사실상
 * 보이지 않았다(WCAG AA 기준 4.5:1). 노랑을 지키려면 글자가 바뀌어야 한다 — 검정으로
 * 두면 8.69:1 이 된다. 색이 바뀌어도 이 함수가 따라가므로 다시 어긋나지 않는다.
 */
export function markerTextColor(hex: string): string {
  const lum = [1, 3, 5]
    .map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4))
  const l = 0.2126 * lum[0] + 0.7152 * lum[1] + 0.0722 * lum[2]
  // 흰 글자 대비가 AA(4.5:1)에 못 미치면 검정으로 뒤집는다.
  return 1.05 / (l + 0.05) >= 4.5 ? '#ffffff' : '#1a1a1a'
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

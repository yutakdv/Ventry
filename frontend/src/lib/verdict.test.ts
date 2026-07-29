import { describe, expect, it } from 'vitest'
import {
  MAP_LEGEND,
  VERDICT_LABEL,
  VERDICT_MARKER_COLOR,
  VERDICT_MARKER_SHAPE,
  markerTextColor,
} from './verdict'

/** #rrggbb → 색상(0~360°) · 명도(0~100%) */
function hueLightness(hex: string): { hue: number; lightness: number } {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
  const max = Math.max(r, g, b)
  const min = Math.min(r, g, b)
  const d = max - min
  let hue = 0
  if (d !== 0) {
    if (max === r) hue = ((g - b) / d) % 6
    else if (max === g) hue = (b - r) / d + 2
    else hue = (r - g) / d + 4
    hue = (hue * 60 + 360) % 360
  }
  return { hue, lightness: ((max + min) / 2) * 100 }
}

/** 두 색의 대비비 (WCAG 2.x 상대 휘도) */
function relLum(hex: string): number {
  const c = [1, 3, 5]
    .map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4))
  return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
}
function contrast(a: string, b: string): number {
  const [hi, lo] = [relLum(a), relLum(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

/**
 * 판정 문구는 이 표 하나에서만 나온다 — 그것이 "승인" 계열 단어를 화면에서 원천 차단하는
 * 구조다 (CLAUDE.md 절대 불변 원칙 3). 표가 바뀌는 것은 문구가 바뀌는 것이므로,
 * **용어 컴플라이언스 자체를 테스트로 잠근다.** grep 스윕은 소스 전체를 훑지만 이 표가
 * 조용히 한 글자 바뀌는 것은 잡지 못한다(그 값도 금지어가 아닌 한 통과한다).
 */
describe('VERDICT_LABEL — 판정 4단계 (스펙 §0-1 ③)', () => {
  it('4단계 문구가 정확히 스펙 그대로다', () => {
    expect(VERDICT_LABEL).toEqual({
      FIT: '적합',
      CONDITIONAL: '조건부 적합',
      CAUTION: '유의',
      OUT_OF_SCOPE: '범위 외',
    })
  })

  it('어떤 판정 문구에도 "승인" 계열 단어가 없다', () => {
    const banned = ['승인', '거절', '부적격', '추천', '권장', '보장']
    for (const label of Object.values(VERDICT_LABEL)) {
      for (const word of banned) {
        expect(label).not.toContain(word)
      }
    }
  })
})

describe('지도 표기', () => {
  it('범례는 3종이며 범위 외를 노출하지 않는다', () => {
    expect(MAP_LEGEND).toEqual(['FIT', 'CONDITIONAL', 'CAUTION'])
    expect(MAP_LEGEND).not.toContain('OUT_OF_SCOPE')
  })

  it('판정 전 종류에 마커 색이 정의돼 있다 — 색 없는 판정이 생기면 마커가 사라진다', () => {
    for (const key of Object.keys(VERDICT_LABEL)) {
      expect(VERDICT_MARKER_COLOR[key as keyof typeof VERDICT_LABEL]).toMatch(/^#[0-9a-f]{6}$/i)
    }
  })

  /*
   * 2026-07-30 — 종전 팔레트는 조건부 적합(41.1°)과 유의(35.1°)가 **색상환에서 6.1° 차이**로
   * 사실상 같은 색이었다. 36px 마커로는 구분이 불가능했고, 데모 기본 예산에서 후보 대부분이
   * 조건부 적합이라 지도가 통째로 한 색으로 보였다 — 판정 4단계라는 핵심 장치가 화면에서
   * 사라진 것이다. 눈으로만 고르면 같은 실수가 다시 나므로 수치로 잠근다.
   */
  it('범례 3색은 색상환에서 충분히 떨어져 있다 (최소 40°)', () => {
    const hues = MAP_LEGEND.map((v) => hueLightness(VERDICT_MARKER_COLOR[v]).hue)
    for (let i = 0; i < hues.length; i += 1) {
      for (let j = i + 1; j < hues.length; j += 1) {
        const raw = Math.abs(hues[i] - hues[j])
        const gap = Math.min(raw, 360 - raw)
        expect(gap, `${MAP_LEGEND[i]} vs ${MAP_LEGEND[j]} 색상차 ${gap.toFixed(1)}°`)
          .toBeGreaterThan(40)
      }
    }
  })

  /*
   * KB 브랜드색인 노랑은 흰 글자 대비가 2.00:1 이라 그 위에 흰 숫자를 쓰면 보이지 않는다.
   * 색을 바꾸는 대신 **글자를 뒤집는다** — `markerTextColor` 가 배경을 보고 고르므로,
   * 팔레트가 바뀌어도 마커 안 숫자는 항상 읽힌다. 그 계약을 여기서 잠근다.
   */
  it('마커 안 숫자가 읽힌다 — 글자색은 배경 대비로 고른다 (WCAG AA)', () => {
    for (const v of MAP_LEGEND) {
      const bg = VERDICT_MARKER_COLOR[v]
      const fg = markerTextColor(bg)
      expect(contrast(bg, fg), `${v} ${bg} 위 ${fg} 대비`).toBeGreaterThanOrEqual(4.5)
    }
  })

  it('KB 브랜드색(노랑)이 조건부 적합에 유지된다', () => {
    expect(VERDICT_MARKER_COLOR.CONDITIONAL).toBe('#f5a800')
    // 그 위 숫자는 흰색이 아니라 검정이어야 한다 — 흰색이면 2.00:1 로 사라진다.
    expect(markerTextColor('#f5a800')).toBe('#1a1a1a')
  })

  it('색을 못 봐도 구분되도록 모양이 서로 다르다 (WCAG 1.4.1)', () => {
    const shapes = MAP_LEGEND.map((v) => VERDICT_MARKER_SHAPE[v])
    expect(new Set(shapes).size).toBe(MAP_LEGEND.length)
  })
})

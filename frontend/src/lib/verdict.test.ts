import { describe, expect, it } from 'vitest'
import { MAP_LEGEND, VERDICT_LABEL, VERDICT_MARKER_COLOR } from './verdict'

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
})

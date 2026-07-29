import { describe, expect, it } from 'vitest'
import { guFromRegionHint } from './areaScope'

const SEOUL = ['강남구', '노원구', '마포구', '송파구', '중구']

/*
 * 진단의 「희망 지역」이 화면 4의 자치구 필터에 닿는 유일한 통로다 (가정 #112). 이 함수가
 * 조용히 null 을 돌려주면 「희망 지역 ○○구만 보기」 칩이 사라질 뿐 오류는 나지 않는다 —
 * 즉 **깨져도 화면이 말해 주지 않는** 자리라 계약을 테스트로 잠근다.
 */
describe('guFromRegionHint — 희망 지역 → 자치구', () => {
  it('시/도 표기가 흔들려도 마지막 토큰을 읽는다', () => {
    // 계약 예시는 "서울 마포구", 프론트 조립값은 "서울특별시 마포구" 다 (API_CONTRACT §4).
    expect(guFromRegionHint('서울특별시 마포구', SEOUL)).toBe('마포구')
    expect(guFromRegionHint('서울 마포구', SEOUL)).toBe('마포구')
    expect(guFromRegionHint('  서울특별시   강남구  ', SEOUL)).toBe('강남구')
  })

  it('서울 밖·미입력이면 없는 필터를 권하지 않는다', () => {
    expect(guFromRegionHint('경기도 성남시', SEOUL)).toBeNull()
    expect(guFromRegionHint('부산광역시 해운대구', SEOUL)).toBeNull()
    expect(guFromRegionHint(null, SEOUL)).toBeNull()
    expect(guFromRegionHint(undefined, SEOUL)).toBeNull()
    expect(guFromRegionHint('', SEOUL)).toBeNull()
  })

  it('자산을 못 받아 자치구 목록이 비면 아무것도 인정하지 않는다', () => {
    expect(guFromRegionHint('서울특별시 마포구', [])).toBeNull()
  })

  it('구 이름만 와도 인정한다 — 자유 텍스트 파싱이 시/도를 못 붙이는 경우', () => {
    expect(guFromRegionHint('송파구', SEOUL)).toBe('송파구')
  })
})

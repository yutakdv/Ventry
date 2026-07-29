import { describe, expect, it } from 'vitest'
import {
  formatAmount,
  formatBurdenRatio,
  formatRate,
  formatRateNote,
  formatRentSource,
  formatTransit,
  walkMinutes,
} from './format'

/**
 * 여기서 잠그는 것은 "보기 좋은 문자열"이 아니라 **계약·컴플라이언스 규칙**이다.
 * 금리 표기(API_CONTRACT §금리 표기)·임대료 라벨(CLAUDE.md §5)·결측 대체 표기(§0-1)는
 * 문구 하나가 규칙 위반이 되는 자리라, 회귀가 조용히 일어나면 안 된다.
 */

describe('formatRate — 계약 §금리 표기', () => {
  it('rate 가 오면 "연 N%" 를 쓰고 rate_type 으로 고정/변동을 붙인다', () => {
    expect(formatRate({ rate: 3.62, rate_type: 'variable' })).toBe('연 3.62% (변동)')
    expect(formatRate({ rate: 2.1, rate_type: 'fixed' })).toBe('연 2.1% (고정)')
  })

  it('rate 가 생략되면 rate_note 원문을 그대로 쓴다 — 프론트가 폴백 문구를 만들지 않는다', () => {
    const note = '은행 대출 금리 - 이자 지원 금리'
    expect(formatRate({ rate_type: 'variable', rate_note: note })).toBe(note)
  })

  it('rate 도 rate_note 도 없으면 null — 없는 금리를 지어내지 않는다', () => {
    expect(formatRate({ rate_type: 'fixed' })).toBeNull()
  })

  it('rate_type 이 없어도(계약의 undefined 허용 파싱) 숫자는 낸다', () => {
    expect(formatRate({ rate: 4.45 })).toBe('연 4.45%')
  })
})

describe('formatRateNote — rate 와 함께 낼 원문 표현', () => {
  it('rate 가 있으면 적용 조건을 병기한다 (기준일 없는 확정 이율로 읽히지 않게)', () => {
    expect(formatRateNote({ rate: 4.45, rate_note: '분기별 변동금리' })).toBe('분기별 변동금리')
  })

  it('rate 가 없으면 null — formatRate 가 이미 본문으로 쓰므로 중복을 피한다', () => {
    expect(formatRateNote({ rate_note: '분기별 변동금리' })).toBeNull()
  })
})

describe('formatBurdenRatio — 결측·비유한값 대체 표기', () => {
  it('정상 비율은 백분율 정수', () => {
    expect(formatBurdenRatio(0.11)).toBe('11%')
  })

  it('필드 생략(undefined)은 계약 D9 이후 정상 입력이다', () => {
    expect(formatBurdenRatio(undefined)).toBe('—')
  })

  it('비유한값도 계산하지 않는다 — 구버전 서버의 "Infinity" 방어', () => {
    expect(formatBurdenRatio(Number.POSITIVE_INFINITY)).toBe('—')
    expect(formatBurdenRatio(Number.NaN)).toBe('—')
  })
})

describe('formatRentSource — 임대료 라벨 (CLAUDE.md §5 하드 룰)', () => {
  it('상권 매칭 시 "분기 평균 (추정)" 라벨을 쓴다 — 권리금의 "연간 조사"와 혼동 금지', () => {
    const line = formatRentSource({ org: 'REB', district: '홍대합정', fallback: false })
    expect(line).toContain('한국부동산원')
    expect(line).toContain('분기 평균 (추정)')
    expect(line).not.toContain('연간 조사')
  })

  it('폴백 행은 자치구 평균임을 사실대로 밝힌다', () => {
    expect(formatRentSource({ org: 'REB', district: '마포구', fallback: true })).toContain(
      '자치구 평균 (추정 · 상권 단위 미매칭)',
    )
  })

  it('district 가 null 이면(계약 §4 폴백 행) 이름을 지어내지 않는다', () => {
    const line = formatRentSource({ org: 'REB', district: null, fallback: true })
    expect(line).toContain('자치구 평균')
    expect(line).not.toContain('null')
  })
})

describe('formatTransit — 실데이터의 지저분함을 한곳에서 흡수한다', () => {
  it('역명에 접미사가 없으면 붙이고 노선도 정규화한다', () => {
    expect(formatTransit({ station: '망원', line: '6', distance_m: 335, daily_riders: 21000 })).toBe(
      '도보 5분 내 망원역(6호선) — 일평균 승하차 21,000명',
    )
  })

  it('부역명 괄호가 있으면 "8호선 잠실(송파구청)" 형태로 낸다 — 괄호 중첩 방지', () => {
    expect(
      formatTransit({ station: '잠실(송파구청)', line: '8호선', distance_m: 67, daily_riders: 1 }),
    ).toBe('도보 1분 내 8호선 잠실(송파구청) — 일평균 승하차 1명')
  })
})

describe('walkMinutes / formatAmount — 결정적 환산', () => {
  it('보행 분속 67m 환산이며 최소 1분이다 (assumptions #85)', () => {
    expect(walkMinutes(670)).toBe(10)
    expect(walkMinutes(5)).toBe(1)
  })

  it('1억 이상은 억 단위로 축약한다', () => {
    expect(formatAmount(8000)).toBe('8,000만원')
    expect(formatAmount(10000)).toBe('1억 원')
    expect(formatAmount(12000)).toBe('1.2억 원')
  })
})

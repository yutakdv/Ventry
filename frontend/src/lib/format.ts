/**
 * 표시 형식 유틸 — 계약 금액 단위는 전부 **만원 정수**다 (API_CONTRACT 공통 규약).
 * 화면 2·3이 같은 규칙을 쓰도록 한곳에 모아 둔다.
 */

/** 만원 정수 → 표시용 값·단위. 1억(10,000만원) 이상은 억 단위로 축약. */
export function splitAmount(manwon: number): { value: string; unit: string } {
  if (manwon >= 10000) {
    const eok = manwon / 10000
    return { value: Number.isInteger(eok) ? String(eok) : eok.toFixed(1), unit: '억 원' }
  }
  return { value: manwon.toLocaleString('ko-KR'), unit: '만원' }
}

/** 단일 금액 — "8,000만원" / "1.2억 원" */
export function formatAmount(manwon: number): string {
  const { value, unit } = splitAmount(manwon)
  return `${value}${unit}`
}

/** 금액 범위 — 단위가 같으면 앞쪽 단위를 생략한다 ("약 5,000 ~ 6,500만원"). */
export function formatBudgetRange(min: number, max: number, prefix = '약 '): string {
  const a = splitAmount(min)
  const b = splitAmount(max)
  return a.unit === b.unit
    ? `${prefix}${a.value} ~ ${b.value}${b.unit}`
    : `${prefix}${a.value}${a.unit} ~ ${b.value}${b.unit}`
}

/** 인원 — 10,000명 이상은 만 단위로 축약 ("2.3만 명"). */
export function formatPeople(n: number): string {
  return n >= 10000 ? `${(n / 10000).toFixed(1)}만 명` : `${n.toLocaleString('ko-KR')}명`
}

/* ─────────────────── 근거 표기 (스펙 §7 — 임대료·교통 줄 상시 표기) ─────────────────── */

/** 기관 코드 → 표기명. 계약은 코드(`REB`)로 오고 화면에는 정식 명칭을 쓴다. */
const ORG_LABEL: Record<string, string> = { REB: '한국부동산원' }

/**
 * 임대료 출처 줄 — "임대료: 한국부동산원 ○○상권 분기 평균 (추정)".
 * 라벨을 "분기 평균"으로 고정하는 건 하드 룰이다 (CLAUDE.md §4 — 권리금의 "연간 조사"와 혼동 금지).
 * `fallback`이면 상권 단위 매칭에 실패해 자치구 평균으로 대체된 값이므로 그 사실을 함께 밝힌다.
 */
export function formatRentSource(src: { org: string; district: string; fallback: boolean }): string {
  const org = ORG_LABEL[src.org] ?? src.org
  return src.fallback
    ? `임대료: ${org} 자치구 평균 (추정 · 상권 단위 미매칭)`
    : `임대료: ${org} ${src.district} 분기 평균 (추정)`
}

/**
 * 도보 소요 시간(분). 계약에는 `distance_m`만 있어 결정적 계수로 환산한다 —
 * 보행 속도 4km/h ≈ 분속 67m (docs/assumptions.md 등재 대상). 최소 1분.
 */
export function walkMinutes(distanceM: number): number {
  return Math.max(1, Math.round(distanceM / 67))
}

/**
 * 역명 표기 — 실데이터의 `station`은 "망원"처럼 접미사가 없기도 하고
 * "잠실(송파구청)"처럼 부역명 괄호를 달고 오기도 한다. 뒤에 무조건 "역"을 붙이면
 * "잠실(송파구청)역"이 되므로, 괄호가 있거나 이미 "역"으로 끝나면 그대로 쓴다.
 */
function stationLabel(station: string): string {
  return station.includes('(') || station.endsWith('역') ? station : `${station}역`
}

/**
 * 노선 표기 — `line`이 "6"으로 올 수도 "8호선"으로 올 수도 있다.
 * 이미 "호선"이 붙어 있으면 덧붙이지 않는다("8호선호선" 방지).
 */
function lineLabel(line: string): string {
  return line.includes('호선') ? line : `${line}호선`
}

/**
 * 교통 줄 — "도보 5분 내 망원역(6호선) — 일평균 승하차 21,000명".
 * 역명에 부역명 괄호가 있으면 괄호 중첩을 피해 "8호선 잠실(송파구청)" 형태로 낸다.
 */
export function formatTransit(t: {
  station: string
  line: string
  distance_m: number
  daily_riders: number
}): string {
  const walk = `도보 ${walkMinutes(t.distance_m)}분 내`
  const riders = `일평균 승하차 ${t.daily_riders.toLocaleString('ko-KR')}명`
  const place = t.station.includes('(')
    ? `${lineLabel(t.line)} ${t.station}`
    : `${stationLabel(t.station)}(${lineLabel(t.line)})`
  return `${walk} ${place} — ${riders}`
}

/* ─────────────────── 금리 표기 (API_CONTRACT §금리 표기) ─────────────────── */

/**
 * 금융상품의 금리 한 줄.
 *
 * 계약이 정한 표시 규칙을 그대로 따른다 — `rate`가 실려 오면 "연 {rate}%"를 쓰고,
 * **생략됐을 때만** `rate_note` 원문을 그대로 표기한다. 고정/변동 라벨은 `rate` 유무가 아니라
 * 항상 `rate_type`으로 판단한다(계약 D8: `rate_type`은 항상 존재, non_null 직렬화라 `rate`는
 * null이 아니라 키 자체가 사라진다).
 *
 * 폴백 문구를 프론트가 만들지 않는 것이 핵심이다 — 금리 표기는 서버가 단일 통제한다.
 */
export function formatRate(p: { rate?: number; rate_type?: string; rate_note?: string }): string | null {
  const kind = p.rate_type === 'variable' ? '변동' : p.rate_type === 'fixed' ? '고정' : null
  if (p.rate != null) return kind ? `연 ${p.rate}% (${kind})` : `연 ${p.rate}%`
  return p.rate_note ?? null
}

/**
 * 표시 형식 유틸 — 계약 금액 단위는 전부 **만원 정수**다 (API_CONTRACT 공통 규약).
 * 화면 2·3이 같은 규칙을 쓰도록 한곳에 모아 둔다.
 */
import type { Industry } from '../api/types'
import { rentAreaBasis } from './rentArea'

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
 * 임대료 출처 줄 — "임대료: 한국부동산원 ○○상권 분기 평균 (추정) · 카페 대표면적 44.0㎡(13.3평) 기준".
 * 라벨을 "분기 평균"으로 고정하는 건 하드 룰이다 (CLAUDE.md §4 — 권리금의 "연간 조사"와 혼동 금지).
 * `fallback`이면 상권 단위 매칭에 실패해 자치구 평균으로 대체된 값이므로 그 사실을 함께 밝힌다.
 *
 * 면적 근거는 하드 룰 문구를 건드리지 않고 **뒤에만 덧붙인다** (이슈 #151). 출처 줄은 이미
 * 어느 상권·어느 주기·추정 여부까지 밝히는데 정작 어느 면적의 금액인지가 빠져 있었다.
 * 업종을 모르면 붙이지 않는다 — 화면이 없는 근거를 지어내지 않는다.
 */
export function formatRentSource(
  src: { org: string; district: string | null; fallback: boolean },
  industry?: Industry | null,
): string {
  const org = ORG_LABEL[src.org] ?? src.org
  // `district` 는 폴백 행에서 null 로 온다 (계약 §4). `fallback` 만 보고 분기하면 두 필드가
  // 어긋난 응답에서 "null 분기 평균"이 화면에 찍히므로, 이름이 없으면 폴백 문구를 쓴다.
  const base =
    src.fallback || !src.district
      ? `임대료: ${org} 자치구 평균 (추정 · 상권 단위 미매칭)`
      : `임대료: ${org} ${src.district} 분기 평균 (추정)`
  const basis = rentAreaBasis(industry)
  return basis ? `${base} · ${basis}` : base
}

/** ㎢ 표기 — 상권은 0.07 수준, 구획은 2 수준이라 소수 둘째 자리면 둘 다 읽힌다. */
function km2(areaM2: number): string {
  return `${(areaM2 / 1_000_000).toFixed(2)}㎢`
}

/**
 * 임대료 **근거 범위** 줄 (가정 #96) — 출처 줄 다음에 오는 4번째 근거 줄.
 *
 * 출처 줄은 「어느 기관의 어느 상권 분기 평균인지」까지 밝히지만, 그 값이 **얼마나 넓은
 * 범위의 평균인지**는 말하지 않는다. 실제로는 판정이 계산되는 단위(상권, 중앙 0.07㎢)와
 * 임대료가 조사된 단위(부동산원 구획, 중앙 0.58㎢)가 약 8배 차이 난다. 그 사실을 숨기는
 * 대신 지도의 두 경계선과 같은 내용을 문장으로 적는다.
 *
 * 배수는 배치가 구운 면적의 나눗셈 1회다 — 화면이 수치를 새로 만들지 않는다 (§0-1).
 * 권유·추천 술어를 쓰지 않고 사실만 서술한다 (CLAUDE.md §3).
 */
export function formatRentScope(
  src: { district: string | null; fallback: boolean },
  areaM2: number,
  districtM2: number | null,
): string {
  if (src.fallback || !districtM2 || !src.district) {
    return '임대료 근거 범위: 이 상권은 한국부동산원 조사 구획에 속하지 않아 자치구 평균을 적용했습니다 — 표시할 구획 경계가 없습니다.'
  }
  /*
   * 세 갈래로 나눈다. 대부분(78%)은 구획이 3배 이상 넓지만, 두 구획이 서로 다른 분할이라
   * **구획이 더 좁은 경우도 2.2% 있다** — 명동 관광특구가 그 구획의 3배다(실측, 가정 #96).
   * 그걸 "비슷한 넓이"로 뭉개면 화면이 사실보다 안전하게 들린다.
   */
  const ratio = districtM2 / areaM2
  const comp =
    ratio < 0.8
      ? `이 상권 ${km2(areaM2)}보다 좁은`
      : ratio < 1.3
        ? `이 상권 ${km2(areaM2)}와 비슷한`
        : `이 상권 ${km2(areaM2)}보다 약 ${ratio < 10 ? ratio.toFixed(1) : Math.round(ratio)}배 넓은`
  return `임대료 근거 범위: 한국부동산원 '${src.district}' 구획 ${km2(districtM2)} — ${comp} 범위의 분기 평균 (추정)입니다.`
}

/**
 * 상권 **범위 중첩** 줄 (가정 #98) — 겹치는 상권이 있을 때만 나온다.
 *
 * 서울 상권영역은 골목·발달·전통시장·**관광특구** 4개 층이 한 파일에 들어 있고, 관광특구
 * 6곳은 하위 상권을 통째로 품는다. 그래서 잠실 관광특구·방이동먹자골목·잠실역이 후보
 * 목록에 **각각** 올라오고, 같은 땅이 여러 번 세어진 것처럼 보인다. 그 사실을 숨기지 않는다.
 *
 * 실질 중첩은 1,650곳 중 52곳뿐이다 — 교차 5,128쌍의 98%는 경계선이 스치는 수준이라
 * 배치에서 10% 임계로 걸러 두었다.
 */
export function formatScopeOverlap(
  selfType: string | undefined,
  containedBy: { name: string; type: string; pct: number }[],
  contains: { name: string; type: string; pct: number }[],
): string | undefined {
  const parts: string[] = []
  const top = containedBy[0]
  if (top) {
    parts.push(
      `이 상권${selfType ? `(${selfType})` : ''} 면적의 ${Math.round(top.pct)}%가 '${top.name}'(${top.type}) 범위와 겹칩니다`,
    )
  }
  if (contains.length) {
    const names = contains.slice(0, 2).map((o) => o.name).join('·')
    const rest = contains.length > 2 ? ` 외 ${contains.length - 2}곳` : ''
    parts.push(`이 범위 안에 다른 후보 ${contains.length}곳이 함께 있습니다 (${names}${rest})`)
  }
  if (!parts.length) return undefined
  return `범위 중첩: ${parts.join(' · ')}. 상권 구분이 4개 층(골목·발달·전통시장·관광특구)이라 같은 지역이 둘 이상의 후보에 속할 수 있습니다.`
}

/**
 * 도보 소요 시간(분). 계약에는 `distance_m`만 있어 결정적 계수로 환산한다 —
 * 보행 속도 4km/h ≈ 분속 67m (docs/assumptions.md #85). 최소 1분.
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

/**
 * 금리 보조 줄 — `rate` 숫자와 **함께** 낼 원문 표현.
 *
 * `rate`가 실려 와도 그 숫자는 특정 분기 실값이다("’26년 3/4분기 적용 · 분기별 변동금리").
 * 숫자만 남기면 사용자는 지금도 유효한 확정 이율로 읽고, 데이터 기준일 상시 표기 원칙과도
 * 어긋난다 (docs/HANDOFF_FRONTEND.md #3). 그래서 적용 조건이 담긴 `rate_note`를 병기한다.
 * 고정금리 상품의 `rate_note`도 "○○자금 이용 시에만 해당" 같은 단서를 담고 있어 함께 낸다.
 *
 * `rate`가 없을 때는 `formatRate`가 이미 `rate_note`를 본문으로 쓰므로 중복을 피해 null을 준다.
 */
export function formatRateNote(p: { rate?: number; rate_note?: string }): string | null {
  return p.rate != null ? (p.rate_note ?? null) : null
}

/**
 * 부담률 표기 — 계산할 수 없는 경우를 한자리에서 대체 표시한다.
 *
 * 추정매출이 결측(0)인 상권은 부담률이 정의되지 않는다. 계약 D9 이후 서버는 그런 상권에서
 * **필드를 생략**하므로 `undefined` 가 정상 입력이다 (이슈 #104 ⑤).
 *
 * `Number.isFinite` 검사는 D9 이전 서버가 보내던 문자열 `"Infinity"` 에 대한 방어로 남겨 둔다.
 * 컨테이너가 구버전이면 그대로 계산돼 화면에 `Infinity%` 가 찍혔던 자리다.
 */
export function formatBurdenRatio(ratio?: number): string {
  return ratio != null && Number.isFinite(ratio) ? `${Math.round(ratio * 100)}%` : '—'
}

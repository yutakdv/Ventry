/**
 * 상권·부동산원 구획 경계 로더 (가정 #95·#96).
 *
 * 이 파일은 **계약 밖 정적 자산**을 다룬다. 경계는 세션·업종·예산과 무관한 불변 자산이라
 * `/api/recommend` 응답에 실을 이유가 없다 — 슬라이더를 움직일 때마다 같은 폴리곤이
 * 재전송된다. 배치가 구운 `/geo/area-scope.v1.json` 을 세션당 한 번만 받아 캐시한다.
 *
 * **실패는 예외가 아니라 정상 경로다.** 파일이 없거나 형식이 어긋나면 `null` 을 돌려주고,
 * 화면은 경계와 근거 문장만 빠진 채 오늘과 똑같이 동작한다 (데모 무중단 원칙).
 */

/** 링 배열. 각 링은 [lng, lat] 좌표의 닫힌 고리 (첫 점 = 끝 점). */
export type Rings = number[][][]

export interface ScopeEntry {
  /** ㎡. 단순화 **전** 투영 면적이라 근거 문장의 배수가 표시용 왜곡을 타지 않는다. */
  areaM2: number
  rings: Rings
  /** 상권명 — 중첩 상대를 이름으로 부르기 위해 함께 굽는다. */
  name?: string
  /** 골목상권 / 발달상권 / 전통시장 / 관광특구 (가정 #98). */
  type?: string
}

/** 중첩 상대 하나 — `pct` 는 **기준 상권 자기 면적** 중 겹친 비율(%). */
export interface Overlap {
  code: string
  name: string
  type: string
  pct: number
}

export interface AreaScope {
  /** 상권코드(TRDAR_CD = `areas[].area_code`) → 경계 */
  areas: Map<string, ScopeEntry>
  /** 부동산원 상권명(`rent_source.district`) → 경계 */
  districts: Map<string, ScopeEntry>
  /** 이 상권이 **잠긴** 상대들 (내림차순) — "내 면적의 76%가 잠실 관광특구 안" */
  containedBy: Map<string, Overlap[]>
  /** 이 상권이 **품는** 상대들 (내림차순) — "이 범위 안에 다른 후보 5곳" */
  contains: Map<string, Overlap[]>
  /** 원천 판본. 통계 기준일과 다르다는 사실을 화면 캡션이 함께 적는다. */
  asOf: { areas: string; districts: string }
  /** 표시용 단순화 허용 오차(m) — 고지 문구가 이 값에서 나온다. */
  toleranceM: number
}

const URL = '/geo/area-scope.v1.json'
const SCHEMA = 'ventry.area-scope.v1'

let cached: Promise<AreaScope | null> | null = null

function toEntries(raw: unknown): Map<string, ScopeEntry> {
  const out = new Map<string, ScopeEntry>()
  if (!raw || typeof raw !== 'object') return out
  for (const [key, value] of Object.entries(raw as Record<string, unknown>)) {
    const v = value as { a?: unknown; r?: unknown; n?: unknown; t?: unknown }
    if (typeof v?.a !== 'number' || !Array.isArray(v?.r)) continue
    out.set(key, {
      areaM2: v.a,
      rings: v.r as Rings,
      name: typeof v.n === 'string' ? v.n : undefined,
      type: typeof v.t === 'string' ? v.t : undefined,
    })
  }
  return out
}

/**
 * `{코드: [[상대코드, 비율], …]}` 을 양방향 색인으로 편다 (가정 #98).
 *
 * 배치는 「내가 잠긴 비율」만 굽는다. 화면은 반대 방향(「내가 품는 상권」)도 필요한데,
 * 실질 중첩이 52개 상권·58쌍뿐이라 역색인을 여기서 만드는 편이 파일에 양쪽을 중복해
 * 굽는 것보다 싸다.
 */
function toOverlaps(raw: unknown, areas: Map<string, ScopeEntry>) {
  const containedBy = new Map<string, Overlap[]>()
  const contains = new Map<string, Overlap[]>()
  if (!raw || typeof raw !== 'object') return { containedBy, contains }

  const describe = (code: string, pct: number): Overlap => ({
    code,
    name: areas.get(code)?.name ?? code,
    type: areas.get(code)?.type ?? '',
    pct,
  })
  const push = (m: Map<string, Overlap[]>, key: string, v: Overlap) => {
    const list = m.get(key)
    if (list) list.push(v)
    else m.set(key, [v])
  }

  for (const [code, entries] of Object.entries(raw as Record<string, unknown>)) {
    if (!Array.isArray(entries)) continue
    for (const pair of entries) {
      if (!Array.isArray(pair) || typeof pair[0] !== 'string' || typeof pair[1] !== 'number') continue
      const [other, pct] = pair as [string, number]
      push(containedBy, code, describe(other, pct))
      push(contains, other, describe(code, pct))
    }
  }
  for (const m of [containedBy, contains]) for (const list of m.values()) list.sort((a, b) => b.pct - a.pct)
  return { containedBy, contains }
}

async function fetchScope(): Promise<AreaScope | null> {
  try {
    const res = await fetch(URL, { cache: 'force-cache' })
    // SPA 폴백이 살아 있는 환경(개발 서버 등)에서는 없는 파일이 index.html 200 으로 온다.
    // 상태 코드만 믿지 않고 content-type 과 schema 필드까지 본다.
    if (!res.ok) return null
    if (!(res.headers.get('content-type') ?? '').includes('json')) return null
    const raw = (await res.json()) as Record<string, unknown>
    if (raw?.schema !== SCHEMA) return null

    const asOf = raw.as_of as { areas?: unknown; districts?: unknown } | undefined
    const areas = toEntries(raw.areas)
    return {
      areas,
      districts: toEntries(raw.districts),
      ...toOverlaps(raw.overlaps, areas),
      asOf: {
        areas: typeof asOf?.areas === 'string' ? asOf.areas : '',
        districts: typeof asOf?.districts === 'string' ? asOf.districts : '',
      },
      toleranceM: typeof raw.simplify_tolerance_m === 'number' ? raw.simplify_tolerance_m : 0,
    }
  } catch {
    // 네트워크 실패·JSON 파싱 실패 모두 같은 결말이다. 던지지 않는다.
    return null
  }
}

/** 세션당 1회. 동시에 여러 번 불러도 요청은 하나다. */
export function loadAreaScope(): Promise<AreaScope | null> {
  if (!cached) {
    cached = fetchScope().then((scope) => {
      if (!scope) console.warn(`[areaScope] ${URL} 를 쓸 수 없어 경계 표시를 건너뜁니다`)
      return scope
    })
  }
  return cached
}

/**
 * 링 배열 → 카카오 `LatLng[][]`.
 *
 * 카카오 `Polygon` 은 path 하나만 받으므로 MultiPolygon(원천 89건)은 링별로 인스턴스를
 * 나눠 그린다. 변환 결과는 같은 상권을 다시 선택할 때 재사용하려고 캐시한다 — 링 하나가
 * 평균 24 정점이라 비용 자체는 작지만, 캐시하면 선택을 오갈 때 객체가 재생성되지 않는다.
 */
const latLngCache = new WeakMap<Rings, kakao.maps.LatLng[][]>()

export function ringsToLatLng(rings: Rings): kakao.maps.LatLng[][] {
  const hit = latLngCache.get(rings)
  if (hit) return hit
  const out = rings.map((ring) => ring.map(([lng, lat]) => new kakao.maps.LatLng(lat, lng)))
  latLngCache.set(rings, out)
  return out
}

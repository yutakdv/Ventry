import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { MapPin, Receipt, TrendingUp, Wallet } from 'lucide-react'
import AppShell from '../components/layout/AppShell'
import Button from '../components/Button'
import StatCard from '../components/StatCard'
import KakaoMap from '../components/KakaoMap'
import AreaCard from '../components/AreaCard'
import CheckAreaPanel from '../components/CheckAreaPanel'
import RiskReviewPanel from '../components/RiskReviewPanel'
import BudgetSliderBar from '../components/BudgetSliderBar'
import Modal from '../components/Modal'
import { getRecommend, isSessionGone, postBudget, postCheckArea } from '../api/client'
import { useSession } from '../store/session'
import { formatAmount, formatRentScope, formatScopeOverlap } from '../lib/format'
import { SESSION_LOST_STATE } from '../lib/sessionLost'
import { rentAreaShort } from '../lib/rentArea'
import { useAreaScope } from '../hooks/useAreaScope'
import { buildComposition } from '../lib/composition'
import { prefersReducedMotion } from '../lib/motion'
import { VERDICT_LABEL } from '../lib/verdict'
import type { CheckAreaResponse, RecommendResponse, Verdict } from '../api/types'
import styles from './Recommend.module.css'

type SortKey = 'score' | 'rent' | 'sales' | 'floating'

/**
 * 지도 마커 상한. 실데이터는 1,000건대가 한 번에 오는데(실측 1,059건) 전량을 마커로 그리면
 * 카카오맵이 버티지 못한다. 목록에서 나머지를 볼 수 있으므로 상위 점수만 지도에 올린다.
 */
const MAP_MARKER_LIMIT = 100

/** 목록 1페이지 — "더 보기"로 늘린다. */
const LIST_PAGE = 50

const SORT_LABEL: Record<SortKey, string> = {
  score: '추천 점수 높은 순',
  rent: '환산 임대료 낮은 순',
  sales: '추정 매출 높은 순',
  floating: '유동인구 많은 순',
}

/** 판정 필터 — 계약에 필터 쿼리가 없어 전부 클라이언트에서 처리한다 (API_CONTRACT §4). */
const VERDICT_FILTERS: (Verdict | 'ALL')[] = ['ALL', 'FIT', 'CONDITIONAL', 'CAUTION']

/**
 * 상권 구분 필터 (실사용 점검 2026-07-29).
 *
 * 값은 **서울시 상권분석서비스의 공식 구분**(`TRDAR_SE_CD` — A 골목 / D 발달 / R 전통시장 /
 * U 관광특구)을 그대로 쓴다. 화면이 이 이름을 적는 것은 분류가 아니라 **인용**이다.
 *
 * 진단 폼에 있던 「오피스/주거/대학가/번화가」를 여기로 옮기지 **않은** 이유가 이것이다 —
 * 그 어휘에 대응하는 데이터가 없어 우리가 직접 임계값을 정해야 하는데(직장인구 비중 몇 %부터
 * 오피스인가), 그러면 출처 규율을 지켜 온 화면에 근거 없는 분류가 하나 생긴다. 대학가는
 * 데이터 자체가 없다. 설명 문구(`hint`)는 분류를 바꾸지 않고 **읽기만 돕는다.**
 *
 * 자리도 옮겼다: 진단(1단계)에서 미리 선언하는 축이 아니라 **결과를 좁히는 축**이다.
 * 1,000곳 넘는 목록에서 실제로 필요한 동작이고, 지도 마커 겹침도 함께 줄어든다.
 */
const AREA_TYPE_FILTERS: { value: string; hint: string }[] = [
  { value: '골목상권', hint: '주택가·이면도로 중심' },
  { value: '발달상권', hint: '대로변 대형 상권' },
  { value: '전통시장', hint: '재래시장 배후' },
  { value: '관광특구', hint: '관광 수요 중심' },
]

export default function Recommend() {
  const navigate = useNavigate()
  const {
    sessionId,
    version,
    budget,
    budgetPreview,
    selectedScenario,
    parsedProfile,
    setBudget,
    bumpVersion,
    retryToken,
  } = useSession()
  /**
   * 임대료 금액이 어느 면적 기준인지 밝히기 위해 지도·카드·슬라이더 바로 내린다 (이슈 #151).
   * 상단 KPI(평균 환산 임대료)도 같은 곱셈의 결과라 `rentUnit` 을 직접 쓴다 — 이슈가 지목한
   * 5개 지점에는 없었지만, 면적 조건 없이 임대료를 노출하는 자리라는 점에서 같은 결함이다.
   */
  const industry = parsedProfile?.industry ?? null
  const rentUnit = rentAreaShort(industry)

  const [data, setData] = useState<RecommendResponse | null>(null)
  const [loading, setLoading] = useState(true)
  /**
   * 슬라이더로 인한 재조회. `loading`과 나누는 이유가 이 화면의 체감을 좌우한다 —
   * `loading`은 지도·목록을 통째로 문구로 대체하는데, 슬라이더를 움직일 때마다 그러면
   * 화면이 매번 사라졌다 돌아온다. 재조회 중에는 직전 결과를 그대로 두고 흐리게만 만든다.
   *
   * 특히 `/api/recommend`는 리스크 검증 LLM 왕복을 물고 있어 상위 후보의 판정 구성이 바뀌는
   * 순간 1초 이상 걸린다(BE 실측 1.4~1.8초, 같은 구성 안에서는 캐시로 ~10ms).
   */
  const [refreshing, setRefreshing] = useState(false)
  /** 서버에 세션이 없다(404) — 첫 화면으로 돌려보내고 사유를 알린다. */
  const [sessionGone, setSessionGone] = useState(false)
  const [selected, setSelected] = useState<string | null>(null)
  const [sort, setSort] = useState<SortKey>('score')
  const [verdictFilter, setVerdictFilter] = useState<Verdict | 'ALL'>('ALL')
  /** 상권 구분 필터 — 서울시 공식 구분값 그대로. 'ALL'이면 좁히지 않는다. */
  const [areaTypeFilter, setAreaTypeFilter] = useState<string>('ALL')
  const [listLimit, setListLimit] = useState(LIST_PAGE)
  const [check, setCheck] = useState<CheckAreaResponse | null>(null)
  const [checking, setChecking] = useState(false)
  /**
   * 역방향 판정 모달 대상. 선택(`selected`)과 분리해 둔다 —
   * 목록을 훑으며 선택만 바꾸는 동안 모달이 따라 뜨면 방해가 되고,
   * 스펙도 판정을 "상권 클릭 → 판정"이라는 **별도 행동**으로 규정한다 (§5-2).
   */
  const [verdictOf, setVerdictOf] = useState<string | null>(null)
  const listRef = useRef<HTMLDivElement>(null)

  /** 판정 열기 — 해당 상권을 선택 상태로도 맞춘다(지도 마커 연동). */
  const openVerdict = useCallback((code: string) => {
    setSelected(code)
    setVerdictOf(code)
  }, [])

  /** 첫 조회인지 재조회인지의 판별. 상태로 두면 이 effect가 자기 자신을 다시 트리거한다. */
  const hasDataRef = useRef(false)

  useEffect(() => {
    /*
     * 세션이 없으면 **조회 자체를 하지 않는다.** 아래 렌더 분기가 /diagnose 로 돌려보내지만
     * effect 는 그 전에 한 번 돈다 — 이 화면에서 새로고침만 해도 `getRecommend('mock', 0)` 이
     * 404 를 받고 목 폴백 플래그(api/fallback.ts)가 켜졌다. 그 플래그는 **되돌리지 않는 설계**라
     * 이후 처음부터 다시 진행해 실데이터를 받아도 "예시 데이터" 배너가 진짜 수치 위에 남는다.
     * /budget·/explore 에는 이미 있는 가드가 여기만 빠져 있었다.
     */
    if (!sessionId || budget == null) return

    // AbortController 로 실제 요청까지 취소한다 — 불리언으로 결과만 무시하면 버려질 응답
    // 본문(gzip 122KB)을 끝까지 받는다. SSE 두 경로는 이미 같은 방식이다 (m-1).
    const ac = new AbortController()
    if (hasDataRef.current) setRefreshing(true)
    else setLoading(true)

    getRecommend(sessionId, version, ac.signal)
      .then((res) => {
        if (ac.signal.aborted) return
        setData(res)
        hasDataRef.current = true
        /*
         * 예산이 바뀌어도 보고 있던 상권은 그대로 둔다 — 슬라이더를 한 칸 움직였다고 선택이
         * 1위로 튀면, 정작 비교하려던 상권의 판정 변화를 볼 수 없다. 후보에서 빠진 경우에만
         * 1위로 되돌린다.
         */
        setSelected((prev) =>
          prev && res.areas.some((a) => a.area_code === prev)
            ? prev
            : (res.areas[0]?.area_code ?? null),
        )
        setVerdictOf(null) // 예산이 바뀌면 이전 판정은 더 이상 유효하지 않다
      })
      .catch((e: unknown) => {
        // 취소된 요청은 reject 한다(client.ts) — 그 경우 화면 상태를 건드리지 않는다.
        // 세션이 서버에 없으면(404) 목 데이터로 이어 붙이지 않고 첫 화면으로 돌린다 —
        // 없는 세션의 결과를 지어내 보여 주는 것보다 사실을 말하는 편이 낫다.
        if (!ac.signal.aborted && isSessionGone(e)) setSessionGone(true)
      })
      .finally(() => {
        if (ac.signal.aborted) return
        setLoading(false)
        setRefreshing(false)
      })
    return () => ac.abort()
    // retryToken: 폴백 배너의 「다시 불러오기」가 세션을 유지한 채 이 조회만 다시 돌린다 (M-14)
  }, [sessionId, version, budget, retryToken])

  /*
   * 상권 경계·구분 정적 자산. `AreaCard` 의 구분 라벨과 근거 문장이 이미 이 값을 쓰고 있어,
   * 상권 구분 필터도 API 왕복 없이 여기서 그대로 나온다. 로드 실패 시 null 이며 그때는
   * 필터 자체를 노출하지 않는다 (데이터가 없으면 고르게 하지 않는다).
   */
  const scope = useAreaScope()

  /** 판정 필터까지 적용한 뒤의 상권 구분별 개수 — 칩에 실어 「고르면 몇 곳」인지 미리 보인다. */
  const areaTypeCounts = useMemo(() => {
    const out = new Map<string, number>()
    if (!data || !scope) return out
    const base = data.areas.filter((a) => a.verdict !== 'OUT_OF_SCOPE')
    const afterVerdict =
      verdictFilter === 'ALL' ? base : base.filter((a) => a.verdict === verdictFilter)
    for (const a of afterVerdict) {
      const t = scope.areas.get(a.area_code)?.type
      if (t) out.set(t, (out.get(t) ?? 0) + 1)
    }
    return out
  }, [data, scope, verdictFilter])

  const areas = useMemo(() => {
    if (!data) return []
    /*
     * 마커는 3종(적합·조건부 적합·유의) 고정이고 임의 추가가 금지되어 있다(스펙 §0-4).
     * 계약상 `areas`에는 `OUT_OF_SCOPE`도 섞여 오므로 화면 단계에서 걸러낸다 —
     * 총계(total_count)에는 남아 있으니 수치가 사라지는 것은 아니다.
     */
    const inScope = data.areas.filter((a) => a.verdict !== 'OUT_OF_SCOPE')
    const byVerdict =
      verdictFilter === 'ALL' ? inScope : inScope.filter((a) => a.verdict === verdictFilter)
    // 상권 구분(골목·발달·전통시장·관광특구)은 서울시 공식 값이며 정적 자산에서 온다.
    const filtered =
      areaTypeFilter === 'ALL' || !scope
        ? byVerdict
        : byVerdict.filter((a) => scope.areas.get(a.area_code)?.type === areaTypeFilter)
    const sorted = [...filtered]
    sorted.sort((a, b) => {
      switch (sort) {
        case 'rent':
          return a.monthly_rent - b.monthly_rent
        case 'sales':
          return b.est_sales - a.est_sales
        case 'floating':
          return b.daily_floating - a.daily_floating
        default:
          return b.score - a.score
      }
    })
    return sorted
  }, [data, scope, sort, verdictFilter, areaTypeFilter])

  /*
   * 목록 구성이 바뀌면 "더 보기"로 늘려 둔 개수를 되돌린다 (FE 리뷰 m-2).
   * 200건까지 펼친 뒤 예산·정렬·필터를 바꾸면 **새 결과 200건이 한 번에 렌더**됐다 —
   * 카드 한 장의 DOM 이 커서(스탯 6개 + 근거 4줄) 체감에 그대로 닿는다.
   */
  useEffect(() => {
    setListLimit(LIST_PAGE)
  }, [data, sort, verdictFilter, areaTypeFilter])

  /**
   * 판정별 개수.
   *
   * **`/budget`의 "진입 후보"와 목록에 보이는 수가 다른 이유가 여기에 있다.** 진입 후보는
   * 권리금 포함 비용 중앙값이 예산 이하인 곳(적합·유의)이고, 조건부 적합은 무권리 매물을 잡아야
   * 열리는 구간이라 진입 후보에 들어가지 않는다. 그런데 마커 3종은 조건부 적합을 포함하므로
   * (스펙 §0-4) 목록에는 남는다. 두 숫자를 나란히 두면서 관계를 말하지 않으면 모순으로 읽힌다.
   *
   * 서버가 준 판정을 세는 것이지 판정을 다시 계산하는 것이 아니다 (§0-1).
   */
  const counts = useMemo(() => {
    const c = { FIT: 0, CAUTION: 0, CONDITIONAL: 0, OUT_OF_SCOPE: 0 }
    data?.areas.forEach((a) => {
      c[a.verdict] += 1
    })
    return { ...c, entry: c.FIT + c.CAUTION, inScope: c.FIT + c.CAUTION + c.CONDITIONAL }
  }, [data])

  /**
   * 리스크 검증이 반박한 대상 = **화면이 실제로 제시한 결과**여야 한다.
   *
   * 처음엔 서버 응답 순서 그대로의 상위 3곳을 썼는데, 종합점수는 예산과 무관하므로 예산이 낮으면
   * 그 3곳이 전부 **범위 외**로 나온다. 화면은 범위 외를 걸러내므로, 그대로 쓰면 "보여주지도 않은
   * 상권을 후보로 제시했다"는 문장이 된다(예산 6,000만에서 실제로 그랬다).
   *
   * 그래서 판정 분포를 먼저 말하고, 이름은 **표시되는 후보**의 상위에서 가져온다.
   * 여기 수치는 전부 서버가 준 `verdict`를 센 것이지 판정을 다시 만든 것이 아니다 (§0-1).
   */
  const riskClaim = useMemo(() => {
    if (!data) return ''
    const visible = data.areas.filter((a) => a.verdict !== 'OUT_OF_SCOPE')
    const head = `확정 예산 기준으로 진입 가능 ${counts.entry.toLocaleString('ko-KR')}곳 · 조건부 적합 ${counts.CONDITIONAL.toLocaleString('ko-KR')}곳으로 판정했습니다.`
    if (visible.length === 0) return `${head} 현재 예산에서 표시 가능한 후보는 없습니다.`
    const top = visible
      .slice(0, 3)
      .map((a) => `${a.name}(${VERDICT_LABEL[a.verdict]}·${a.score}점)`)
      .join(', ')
    return `${head} 종합점수 상위는 ${top}입니다.`
  }, [data, counts])
  const mapAreas = useMemo(() => areas.slice(0, MAP_MARKER_LIMIT), [areas])
  const listAreas = useMemo(() => areas.slice(0, listLimit), [areas, listLimit])
  const verdictArea = useMemo(
    () => areas.find((a) => a.area_code === verdictOf) ?? null,
    [areas, verdictOf],
  )

  /**
   * 상권·구획 경계 (가정 #96). 첫 페인트 이후에 도착하며, 못 받으면 `null` 로 남아
   * 경계·근거 범위 문장만 빠진 채 나머지 화면은 그대로 동작한다.
   */
  const scopeNote = useMemo(() => {
    if (!scope || !selected) return undefined
    const area = areas.find((a) => a.area_code === selected)
    const entry = scope.areas.get(selected)
    if (!area || !entry) return undefined
    // 폴백 판정은 문자열이 아니라 불리언으로 한다 — 폴백 행 district 는 null 로 온다(등재 #96).
    const district = area.rent_source?.fallback ? null : area.rent_source?.district
    const districtM2 = (district && scope.districts.get(district)?.areaM2) || null
    return formatRentScope(area.rent_source, entry.areaM2, districtM2)
  }, [scope, selected, areas])

  /** 범위 중첩 줄 — 실질 중첩이 있는 52곳에서만 나온다 (가정 #98). */
  const overlapNote = useMemo(() => {
    if (!scope || !selected) return undefined
    return formatScopeOverlap(
      scope.areas.get(selected)?.type,
      scope.containedBy.get(selected) ?? [],
      scope.contains.get(selected) ?? [],
    )
  }, [scope, selected])

  /*
   * 지도에서 마커를 고르면 해당 카드가 목록 밖에 있을 수 있다 — 보이는 위치로 끌어온다.
   *
   * **목록을 먼저 늘린다** (실사용 점검 2026-07-29). 지도는 상위 100곳(`MAP_MARKER_LIMIT`)에
   * 마커를 그리는데 목록은 50건(`LIST_PAGE`)만 렌더해서, 51~100위 마커를 누르면 카드가 DOM 에
   * 아예 없어 `querySelector` 가 null → **아무 일도 일어나지 않았다.** 주석은 「목록 밖에 있을
   * 수 있다 — 끌어온다」라고 적혀 있었지만 그 처리가 없었고, 지도 말풍선은 「자세한 근거는
   * 오른쪽 목록에서 확인할 수 있습니다」라고 안내하고 있었다. 두 안내가 다 어긋난 셈이다.
   */
  useEffect(() => {
    if (!selected) return
    const index = areas.findIndex((a) => a.area_code === selected)
    if (index >= 0 && index >= listLimit) {
      // 고른 카드가 나올 때까지 페이지 단위로 늘린다 — 다음 렌더에서 스크롤이 걸린다.
      setListLimit(Math.ceil((index + 1) / LIST_PAGE) * LIST_PAGE)
    }
  }, [selected, areas, listLimit])

  useEffect(() => {
    if (!selected || !listRef.current) return
    const card = listRef.current.querySelector<HTMLElement>(`[data-area-code="${CSS.escape(selected)}"]`)
    // CSS 미디어 쿼리는 JS가 부르는 스크롤에 닿지 않는다 — 여기서 직접 확인한다.
    card?.scrollIntoView({
      block: 'nearest',
      behavior: prefersReducedMotion() ? 'auto' : 'smooth',
    })
  }, [selected, listAreas])

  // 상권을 고르면 역방향 판정을 재조회한다 (계약 6번 — 판정 4단계 + 부족분 + 자격 부합 상품).
  useEffect(() => {
    if (!sessionId || !verdictOf) {
      setCheck(null)
      return
    }
    const ac = new AbortController()
    setChecking(true)
    postCheckArea(sessionId, verdictOf, ac.signal)
      .then((res) => {
        if (!ac.signal.aborted) setCheck(res)
      })
      .catch(() => {})   // 취소는 장애가 아니다 — 상태를 건드리지 않는다
      .finally(() => {
        if (!ac.signal.aborted) setChecking(false)
      })
    return () => ac.abort()
  }, [sessionId, verdictOf, version])

  /* ── 하단 고정 슬라이더 (FE-05) ─────────────────────────────────────────── */

  const [sliderValue, setSliderValue] = useState(budget ?? 0)
  const [budgetPending, setBudgetPending] = useState(false)
  /**
   * 요청 일련번호.
   *
   * debounce(300ms)는 요청 **수**만 줄인다 — `/budget` 응답이 그보다 오래 걸리면 두 요청이 동시에
   * 떠 있고, 먼저 보낸 쪽이 나중에 도착하면 아래 `setSliderValue(res.confirmed_budget)` 가
   * **사용자가 방금 놓은 위치를 이전 값으로 되돌린다.** 되돌린 뒤에는 `sliderValue === budget` 이
   * 성립해 effect 가 멈추므로 그 잘못된 값이 그대로 확정된다. 마지막 요청의 응답만 반영한다.
   */
  const budgetSeqRef = useRef(0)
  /** 진행 중인 `/budget` 요청 — 새 요청 전에 실제로 끊는다. 근거는 화면 3과 같다 (Q-06). */
  const budgetInFlightRef = useRef<AbortController | null>(null)

  /**
   * debounce 300ms 후 예산 재확정 (스펙 §7). `POST /budget`은 덮어쓰기이므로 마지막 값이 확정값이다.
   *
   * `sliderValue === budget`이면 아무것도 하지 않는 것이 이 effect의 종료 조건이다 — 화면 진입
   * 직후(초기값 = 세션 예산)와 적용 완료 직후가 모두 이 상태라, 진입만으로 예산을 다시 쓰는 일도
   * 없고 적용 후 스스로 다시 도는 일도 없다. 서버가 값을 조정해 돌려주면 슬라이더를 그 값에
   * 맞춰야 조건이 성립하므로 `confirmed_budget`으로 되맞춘다.
   */
  useEffect(() => {
    if (!sessionId || !selectedScenario || budget == null) return
    if (sliderValue === budget) return

    const t = setTimeout(() => {
      const seq = (budgetSeqRef.current += 1)
      budgetInFlightRef.current?.abort()
      const ac = new AbortController()
      budgetInFlightRef.current = ac
      setBudgetPending(true)
      postBudget(
        sessionId,
        {
          confirmed_budget: sliderValue,
          composition: buildComposition(selectedScenario, sliderValue),
        },
        ac.signal,
      )
        .then((res) => {
          if (seq !== budgetSeqRef.current) return // 더 나중에 보낸 요청이 있다 — 이 응답은 버린다
          setSliderValue(res.confirmed_budget)
          setBudget(res.confirmed_budget, res.preview, res.data_as_of)
          bumpVersion() // /recommend·/explore가 공유하는 version
        })
        .catch(() => {
          // 취소(새 요청이 앞선 것을 끊었다)뿐이다 — 그 외 실패는 client.ts 가 목으로 흡수한다.
        })
        .finally(() => {
          if (seq === budgetSeqRef.current) setBudgetPending(false)
        })
    }, 300)

    return () => clearTimeout(t)
  }, [sliderValue, budget, sessionId, selectedScenario, setBudget, bumpVersion])

  /*
   * 세션이 없으면 목 폴백으로 그럴듯한 화면이 떠서 "확정 예산 —"처럼 반쪽 상태가 된다
   * (새로고침·주소 직접 입력에서 실제로 발생). 화면을 보여주는 대신 앞 단계로 돌려보낸다.
   */
  // 세션이 사라진 이유를 첫 화면이 설명할 수 있도록 state 를 실어 보낸다 (M-13)
  if (!sessionId || sessionGone) return <Navigate to="/diagnose" replace state={SESSION_LOST_STATE} />
  if (budget == null) return <Navigate to="/budget" replace />

  return (
    <AppShell activeStep={4} wide>
      <div className={styles.surface}>
        <div className={styles.header}>
          <div className={styles.titleRow}>
            {/*
              사이드바를 접으면서 그 자리의 태그라인(「내 형편에 어디까지 가능한가 / 자금이
              입지를 결정합니다」)이 사라진다. 이 서비스의 명제라 화면에서 빠지면 안 되므로
              결과 화면의 제목 옆으로 옮긴다 (실사용 점검 2026-07-29).
            */}
            <h1 className="t-title1">
              4단계. 입지 추천
              <span className={`t-body ${styles.thesis}`}>자금이 입지를 결정합니다</span>
            </h1>
            <Button variant="secondary" size="sm" onClick={() => navigate('/budget')}>
              ‹ 예산 다시 선택
            </Button>
          </div>
          <p className={`t-body ${styles.subtitle}`}>
            {budget != null
              ? `확정하신 예산 ${formatAmount(budget)} 내에서 도달 가능한 상권입니다. 지도의 마커나 목록을 선택하면 근거를 확인할 수 있습니다.`
              : '확정 예산 내에서 도달 가능한 상권입니다. 지도의 마커나 목록을 선택하면 근거를 확인할 수 있습니다.'}
          </p>
        </div>

        {data && (
          <div className={styles.summaryBar}>
            <StatCard icon={Wallet} tone="blue" label="확정 예산" value={budget != null ? formatAmount(budget) : '—'} />
            {/*
              `total_count`에는 범위 외까지 포함돼 온다. 화면에서 범위 외를 제외하므로
              카드의 큰 숫자는 실제로 보이는 후보 수를 쓰고, 내역을 캡션에서 갈라 준다 —
              큰 숫자 하나만 두면 하단 슬라이더의 "진입 가능 N곳"과 어긋나 보인다.
            */}
            <StatCard
              icon={MapPin}
              tone="purple"
              label="추천 상권 수"
              value={`${counts.inScope.toLocaleString('ko-KR')}곳`}
              caption={`진입 가능 ${counts.entry.toLocaleString('ko-KR')}곳 · 조건부 적합 ${counts.CONDITIONAL.toLocaleString('ko-KR')}곳 (무권리 매물 기준)`}
            />
            {/*
              후보가 0곳이면 서버가 `summary` 를 생략한다 — 그때는 「—」로 둔다
              (실사용 점검 2026-07-29). 종전에는 서버가 후보 풀 전체를 평균해서, 추천이
              0곳인 화면에서도 「평균 임대료 221만원」이 그대로 떴다.
            */}
            <StatCard
              icon={Receipt}
              tone="green"
              label={`평균 환산 임대료 (월${rentUnit ? `, ${rentUnit}` : ''})`}
              value={data.summary ? formatAmount(data.summary.avg_rent) : '—'}
            />
            <StatCard
              icon={TrendingUp}
              tone="orange"
              label="평균 추정 매출 (월)"
              value={data.summary ? formatAmount(data.summary.avg_sales) : '—'}
            />
          </div>
        )}

        {loading ? (
          <p className={`t-body ${styles.loading}`}>추천 상권을 불러오는 중…</p>
        ) : !data ? (
          <p className={`t-body ${styles.loading}`}>추천 결과를 불러오지 못했습니다.</p>
        ) : (
          <div className={`${styles.columns} ${refreshing ? styles.columnsStale : ''}`}>
            <div className={styles.mapCol}>
              <KakaoMap
                areas={mapAreas}
                selectedCode={selected}
                onSelect={setSelected}
                dataAsOf={data.data_as_of}
                industry={industry}
                scope={scope}
              />
              {areas.length > mapAreas.length && (
                <p className={`t-caption ${styles.mapNote}`}>
                  지도에는 추천 점수 상위 {MAP_MARKER_LIMIT}곳의 마커만 표시됩니다 (조건 충족{' '}
                  {areas.length.toLocaleString('ko-KR')}곳). 나머지는 목록에서 확인할 수 있습니다.
                </p>
              )}
            </div>

            <div className={styles.list}>
              <div className={styles.listHeader}>
                <p className="t-title2">추천 상권 리스트 ({areas.length})</p>
                <label className={styles.sortLabel}>
                  <span className="t-caption">정렬</span>
                  <select
                    className={`t-caption ${styles.sortSelect}`}
                    value={sort}
                    onChange={(e) => setSort(e.target.value as SortKey)}
                  >
                    {(Object.keys(SORT_LABEL) as SortKey[]).map((k) => (
                      <option key={k} value={k}>
                        {SORT_LABEL[k]}
                      </option>
                    ))}
                  </select>
                </label>
              </div>

              <div className={styles.filters}>
                {VERDICT_FILTERS.map((v) => (
                  <button
                    key={v}
                    type="button"
                    className={`t-label ${styles.chip} ${verdictFilter === v ? styles.chipActive : ''}`}
                    aria-pressed={verdictFilter === v}
                    onClick={() => setVerdictFilter(v)}
                  >
                    {v === 'ALL' ? '전체' : VERDICT_LABEL[v]}
                  </button>
                ))}
              </div>

              {/*
                상권 구분 필터 — 경계 자산이 실린 경우에만 노출한다. 자산 로드는 선택 경로라
                (`useAreaScope`) 실패하면 필터가 아예 나타나지 않고 나머지는 그대로 동작한다.
              */}
              {scope && (
                <div className={styles.filters}>
                  <button
                    type="button"
                    className={`t-label ${styles.chip} ${areaTypeFilter === 'ALL' ? styles.chipActive : ''}`}
                    aria-pressed={areaTypeFilter === 'ALL'}
                    onClick={() => setAreaTypeFilter('ALL')}
                  >
                    상권 구분 전체
                  </button>
                  {AREA_TYPE_FILTERS.filter((t) => (areaTypeCounts.get(t.value) ?? 0) > 0).map(
                    (t) => (
                      <button
                        key={t.value}
                        type="button"
                        className={`t-label ${styles.chip} ${areaTypeFilter === t.value ? styles.chipActive : ''}`}
                        aria-pressed={areaTypeFilter === t.value}
                        // 구분 이름만으로는 무엇인지 모를 수 있어 설명을 툴팁·보조라벨로 함께 준다.
                        title={t.hint}
                        aria-label={`${t.value} — ${t.hint}, ${areaTypeCounts.get(t.value)}곳`}
                        onClick={() => setAreaTypeFilter(t.value)}
                      >
                        {t.value} {areaTypeCounts.get(t.value)}
                      </button>
                    ),
                  )}
                </div>
              )}
              {scope && areaTypeFilter !== 'ALL' && (
                <p className={`t-caption ${styles.filterHint}`}>
                  {AREA_TYPE_FILTERS.find((t) => t.value === areaTypeFilter)?.hint} · 서울시 상권분석서비스
                  상권 구분 기준입니다.
                </p>
              )}

              {/* 검증 의견은 결과 전체에 1건이다 (상권별 아님 — API_CONTRACT §4) */}
              <RiskReviewPanel review={data.risk_review} claim={riskClaim} label="추천 결과 전체" />

              <div className={styles.cards} ref={listRef}>
                {areas.length === 0 ? (
                  <p className={`t-body ${styles.empty}`}>선택한 조건에 해당하는 상권이 없습니다.</p>
                ) : (
                  <>
                    {listAreas.map((a) => (
                      <AreaCard
                        key={a.area_code}
                        area={a}
                        selected={a.area_code === selected}
                        onSelect={() => setSelected(a.area_code)}
                        onCheck={() => openVerdict(a.area_code)}
                        industry={industry}
                        scopeNote={a.area_code === selected ? scopeNote : undefined}
                        overlapNote={a.area_code === selected ? overlapNote : undefined}
                        areaType={scope?.areas.get(a.area_code)?.type}
                      />
                    ))}
                    {areas.length > listAreas.length && (
                      <Button
                        variant="secondary"
                        size="md"
                        fullWidth
                        className={styles.moreBtn}
                        onClick={() => setListLimit((n) => n + LIST_PAGE)}
                      >
                        {(areas.length - listAreas.length).toLocaleString('ko-KR')}곳 더 보기
                      </Button>
                    )}
                  </>
                )}
              </div>
            </div>
          </div>
        )}

        {/*
          하단 고정 슬라이더 (스펙 §7). 시나리오 없이 이 화면에 올 수 없지만, 없으면 가동 범위를
          정할 근거가 사라지므로 바를 내지 않는다 — 범위를 화면이 지어내지 않는다.
        */}
        {/*
          가동 상한은 시나리오 상한과 **지금 적용된 예산 중 큰 값**이다. 탐색 화면의 T1(기회 경계)은
          카드 구성 밖 상품을 근거로 삼으므로 적용 예산이 `budget_max` 를 넘는 경우가 실데이터에서
          흔한데, 상한을 카드 값으로 고정하면 슬라이더가 그 예산을 표현하지 못한다. 그 상태에서
          바를 한 번 건드리면 클램프된 값이 확정 예산으로 전송돼 **방금 적용한 인사이트가 소리 없이
          해제**된다. 범위를 지어내는 것이 아니라 이미 확정된 값을 표현 가능하게 만드는 것이다.
        */}
        {selectedScenario && (
          <BudgetSliderBar
            min={selectedScenario.budget_min}
            max={Math.max(selectedScenario.budget_max, budget)}
            value={sliderValue}
            onChange={setSliderValue}
            preview={budgetPreview}
            conditionalCount={counts.CONDITIONAL}
            pending={budgetPending || refreshing}
            industry={industry}
            onOpenExplore={() => navigate('/explore')}
          />
        )}

        {/* 탐색 진입점 — 데모 순서가 "추천 → 탐색"이므로 결과를 본 뒤에 열린다 (expl §8) */}
        <div className={styles.exploreCta}>
          <p className={`t-body ${styles.exploreText}`}>
            이 예산으로 어디까지 열리는지 궁금하다면 — 예산을 조금 더 확보했을 때 진입·지속 가능한
            후보가 어떻게 달라지는지 탐색할 수 있습니다.
          </p>
          <Button variant="primary" size="md" onClick={() => navigate('/explore')}>
            결정공간 탐색 열기 →
          </Button>
        </div>

        {/* 역방향 판정 — 상권을 고른 뒤 모달로 띄운다 (지도·목록 맥락을 가리지 않게) */}
        <Modal
          open={!!verdictArea}
          title={verdictArea ? `${verdictArea.name} 판정` : ''}
          onClose={() => setVerdictOf(null)}
        >
          {verdictArea && (
            <CheckAreaPanel areaName={verdictArea.name} result={check} loading={checking} />
          )}
        </Modal>

        <p className={`t-caption ${styles.disclaimer}`}>
          ⓘ 본 정보는 공개 자료 기반 정보 제공이며 대출 권유·중개·자문이 아닙니다. 임대료는 한국부동산원 상권 분기
          평균(추정), 권리금은 연간 조사(전년 기준)이며 실제 금액은 개별 물건에 따라 다릅니다.
          {data && ` 데이터 기준일 ${data.data_as_of}.`}
        </p>
      </div>
    </AppShell>
  )
}

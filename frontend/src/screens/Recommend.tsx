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
import { guFromRegionHint } from '../lib/areaScope'
import { buildComposition } from '../lib/composition'
import { prefersReducedMotion } from '../lib/motion'
import { ENTRY_VERDICTS, VERDICT_LABEL, matchVerdict, type VerdictFilter } from '../lib/verdict'
import type { CheckAreaResponse, RecommendResponse } from '../api/types'
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

/**
 * 판정 필터 — 계약에 필터 쿼리가 없어 전부 클라이언트에서 처리한다 (API_CONTRACT §4).
 *
 * 기본값이 「진입 가능」인 것이 이 화면의 전제다 (2026-07-30). 종전 기본값은 「전체」였고,
 * 그래서 확정 예산 1억에서 지도 마커 100개 중 36개만 실제로 갈 수 있는 곳이었다 —
 * 조건부 적합 636곳이 점수순으로 섞여 들어와 나머지를 채웠다. 「내 한도로 어디까지
 * 가능한가」를 답하는 화면이 한도와 무관한 후보를 같은 무게로 보여 주고 있었던 셈이다.
 *
 * 조건부 적합을 숨기는 것이 아니라 **자리를 나눈다** — 지도 아래 전용 패널이 개수·상위
 * 후보·전환 버튼을 항상 노출하고, 칩 한 번으로 지도와 목록에 되돌아온다.
 */
const VERDICT_FILTERS: { value: VerdictFilter; label: string }[] = [
  { value: 'ENTRY', label: '진입 가능' },
  { value: 'FIT', label: VERDICT_LABEL.FIT },
  { value: 'CAUTION', label: VERDICT_LABEL.CAUTION },
  { value: 'CONDITIONAL', label: VERDICT_LABEL.CONDITIONAL },
  { value: 'ALL', label: '전체' },
]

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
  const [verdictFilter, setVerdictFilter] = useState<VerdictFilter>('ENTRY')
  /** 상권 구분 필터 — 서울시 공식 구분값 그대로. 'ALL'이면 좁히지 않는다. */
  const [areaTypeFilter, setAreaTypeFilter] = useState<string>('ALL')
  /**
   * 자치구 필터 (가정 #112·#113). 초기값은 **진단에서 고른 희망 지역**이다.
   *
   * 처음엔 「좁히지 않음」으로 뒀다 — 서비스가 먼저 지우면 사용자가 모르고 지나쳤을 선택지가
   * 화면에 오르지 못한다는 이유였다(심사_QA.md). 그런데 그러면 희망 지역을 고른 사람이 다시
   * 한 번 그것을 눌러야 자기 지역을 본다. 좁히는 것을 **되돌리는 비용**(칩 한 번)이 좁히지
   * 않아 생기는 비용(내 지역을 못 찾음)보다 작아, 기본을 희망 지역으로 옮긴다.
   *
   * 「모르고 지나칠 선택지」는 자치구 드롭다운이 25개 구의 후보 수를 전부 보여 주는 것과
   * 「서울 전체로」 한 번으로 지킨다 — 지워지는 것이 아니라 한 칸 뒤에 있다.
   */
  const [guFilter, setGuFilter] = useState<string>('ALL')
  /**
   * 사용자가 자치구를 직접 건드렸는가.
   *
   * 이 플래그가 서는 순간 아래 자동 적용이 멈춘다. 「서울 전체로」를 눌러 둔 사람에게 슬라이더를
   * 움직일 때마다 자기 구로 되돌아오게 하면, 화면이 사용자의 결정을 계속 무르는 셈이 된다.
   */
  const guTouchedRef = useRef(false)
  const chooseGu = useCallback((gu: string) => {
    guTouchedRef.current = true
    setGuFilter(gu)
  }, [])
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
        // 선택 유지·복구는 아래 「선택은 항상 보이는 후보 안에」 효과가 전담한다 —
        // 여기서 `res.areas[0]` 로 되돌리면 판정 필터에 걸려 보이지도 않는 상권이 선택된다.
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

  /**
   * 판정 필터까지 적용한 뒤의 상권 구분·자치구별 개수 — 「고르면 몇 곳」인지 미리 보인다.
   *
   * 두 축을 한 번에 세는 것은 순회를 아끼려는 것이 아니라 **같은 모수에서 세기 위해서다.**
   * 서로가 서로를 좁히지 않으므로(구분 칩을 눌러도 자치구 개수는 그대로), 어느 쪽을 먼저
   * 골라도 화면의 숫자가 흔들리지 않는다.
   */
  const { areaTypeCounts, guCounts } = useMemo(() => {
    const areaTypeCounts = new Map<string, number>()
    const guCounts = new Map<string, number>()
    if (!data || !scope) return { areaTypeCounts, guCounts }
    for (const a of data.areas) {
      if (a.verdict === 'OUT_OF_SCOPE' || !matchVerdict(a.verdict, verdictFilter)) continue
      const entry = scope.areas.get(a.area_code)
      if (entry?.type) areaTypeCounts.set(entry.type, (areaTypeCounts.get(entry.type) ?? 0) + 1)
      if (entry?.sigungu) guCounts.set(entry.sigungu, (guCounts.get(entry.sigungu) ?? 0) + 1)
    }
    return { areaTypeCounts, guCounts }
  }, [data, scope, verdictFilter])

  /** 자치구를 풀었을 때의 후보 수 — 드롭다운의 「전체」가 쓴다. */
  const guTotal = useMemo(() => {
    let sum = 0
    for (const n of guCounts.values()) sum += n
    return sum
  }, [guCounts])

  /** 자산에 실린 자치구 전체 (가나다순). 개수 0인 구도 드롭다운에는 남는다 — 아래 주석 참조. */
  const allGus = useMemo(() => {
    if (!scope) return []
    const set = new Set<string>()
    for (const entry of scope.areas.values()) if (entry.sigungu) set.add(entry.sigungu)
    return [...set].sort((a, b) => a.localeCompare(b, 'ko'))
  }, [scope])

  /**
   * 진단에서 고른 「희망 지역」의 자치구.
   *
   * `region_hint` 는 "서울특별시 마포구" 처럼 시/도와 구를 합친 단일 문자열이다
   * (API_CONTRACT §4). 마지막 토큰이 자치구이며, **자산의 자치구 목록**에 있을 때만 인정한다 —
   * 「경기도 성남시」처럼 서울 밖 값이 들어와도 없는 필터를 권하지 않기 위해서다. 후보 수가
   * 아니라 목록으로 판정하는 것은, 예산·판정을 바꿀 때마다 이 칩이 사라졌다 나타나지 않게
   * 하기 위해서다 — 「그 구에는 지금 후보가 없다」는 개수 0으로 말하는 편이 정확하다.
   */
  const wishGu = useMemo(
    () => guFromRegionHint(parsedProfile?.region_hint, allGus),
    [parsedProfile, allGus],
  )

  /** 희망 지역에 지금 예산으로 갈 수 있는 곳이 있는가 — 없으면 좁히지 않는다(아래 참조). */
  const wishGuCount = wishGu ? (guCounts.get(wishGu) ?? 0) : 0

  /**
   * 희망 지역을 **먼저 보여준다** — 첫 진입에서도, 예산을 조정한 뒤에도 (가정 #113).
   *
   * 예산에 매번 다시 판단하는 이유가 실데이터에 있다. 확정 예산 8,000만에서 진입 가능은
   * 노원구 6곳뿐이라 마포구가 **0곳**이고, 1억이 되어야 20곳이 열린다. 한 번만 적용하는
   * 방식이면 8,000만으로 들어온 사람은 예산을 올려도 자기 지역으로 돌아오지 못한다.
   *
   * **후보가 0곳이면 좁히지 않는다.** 진입하자마자 빈 목록을 보여 주는 대신 서울 전체를 두고,
   * 아래 안내가 「희망 지역에는 지금 갈 수 있는 곳이 없다」는 사실을 문장으로 말한다 — 빈
   * 화면보다 그 편이 정확하고, 예산을 올리면 이 효과가 알아서 희망 지역으로 되돌린다.
   */
  useEffect(() => {
    if (guTouchedRef.current || !wishGu) return
    setGuFilter(wishGuCount > 0 ? wishGu : 'ALL')
  }, [wishGu, wishGuCount])

  const areas = useMemo(() => {
    if (!data) return []
    /*
     * 마커는 3종(적합·조건부 적합·유의) 고정이고 임의 추가가 금지되어 있다(스펙 §0-4).
     * 계약상 `areas`에는 `OUT_OF_SCOPE`도 섞여 오므로 화면 단계에서 걸러낸다 —
     * 총계(total_count)에는 남아 있으니 수치가 사라지는 것은 아니다.
     */
    const inScope = data.areas.filter((a) => a.verdict !== 'OUT_OF_SCOPE')
    const byVerdict = inScope.filter((a) => matchVerdict(a.verdict, verdictFilter))
    // 상권 구분(골목·발달·전통시장·관광특구)·자치구 모두 서울시 공식 값이며 정적 자산에서 온다.
    const filtered = byVerdict.filter((a) => {
      if (!scope) return true
      const entry = scope.areas.get(a.area_code)
      if (areaTypeFilter !== 'ALL' && entry?.type !== areaTypeFilter) return false
      if (guFilter !== 'ALL' && entry?.sigungu !== guFilter) return false
      return true
    })
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
  }, [data, scope, sort, verdictFilter, areaTypeFilter, guFilter])

  /*
   * 목록 구성이 바뀌면 "더 보기"로 늘려 둔 개수를 되돌린다 (FE 리뷰 m-2).
   * 200건까지 펼친 뒤 예산·정렬·필터를 바꾸면 **새 결과 200건이 한 번에 렌더**됐다 —
   * 카드 한 장의 DOM 이 커서(스탯 6개 + 근거 4줄) 체감에 그대로 닿는다.
   */
  useEffect(() => {
    setListLimit(LIST_PAGE)
  }, [data, sort, verdictFilter, areaTypeFilter, guFilter])

  /**
   * 선택은 항상 **보이는 후보 안에** 있어야 한다.
   *
   * 판정 필터 기본값이 「진입 가능」이 되면서 생긴 요구다 — 선택된 상권이 필터 밖으로 나가면
   * 지도에 마커도, 목록에 카드도 없는데 근거 문장(`scopeNote`·`overlapNote`)만 그 상권을
   * 가리키는 상태가 된다. 예산을 바꿔 판정이 옮겨 갈 때도 같은 일이 일어난다.
   *
   * 보이는 동안에는 건드리지 않는다 — 슬라이더를 한 칸 움직였다고 선택이 1위로 튀면
   * 정작 비교하려던 상권의 판정 변화를 볼 수 없다. 사라졌을 때만 1위로 되돌린다.
   */
  useEffect(() => {
    setSelected((prev) =>
      prev && areas.some((a) => a.area_code === prev) ? prev : (areas[0]?.area_code ?? null),
    )
  }, [areas])

  /**
   * 판정별 개수.
   *
   * `entry`(적합+유의)가 `/budget` 프리뷰의 "진입 후보"와 같은 정의다 — 권리금 포함 비용
   * 중앙값이 예산 이하인 곳. 조건부 적합은 무권리 매물을 잡아야 열리는 구간이라 여기 들어가지
   * 않으며, 그래서 화면에서도 지도·목록의 기본 표시에서 빠지고 전용 패널로 분리된다
   * (2026-07-30). 판정 어휘 3종은 필터 칩과 그 패널이 계속 노출한다 (스펙 §0-4).
   *
   * 서버가 준 판정을 세는 것이지 판정을 다시 계산하는 것이 아니다 (§0-1).
   */
  const counts = useMemo(() => {
    const c = { FIT: 0, CAUTION: 0, CONDITIONAL: 0, OUT_OF_SCOPE: 0 }
    data?.areas.forEach((a) => {
      c[a.verdict] += 1
    })
    return { ...c, entry: c.FIT + c.CAUTION }
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
   *
   * 2026-07-30 — 그 「표시되는 후보」를 **진입 가능(적합+유의)** 으로 좁혔다. 종전에는 조건부
   * 적합까지 포함한 상위 3곳을 썼는데, 확정 예산 1억에서 1순위로 호명되던 신림역 8번(조건부
   * 84점)은 **무권리 매물을 잡지 않으면 갈 수 없는 곳**이었다. 지도에서 고치려는 것과 같은
   * 문제가 문장으로 남아 있었던 셈이라, 목록 기본값을 옮기면서 이쪽도 함께 옮긴다.
   */
  const riskClaim = useMemo(() => {
    if (!data) return ''
    const entryVisible = data.areas.filter((a) => ENTRY_VERDICTS.includes(a.verdict))
    const head = `확정 예산 기준으로 진입 가능 ${counts.entry.toLocaleString('ko-KR')}곳 · 조건부 적합 ${counts.CONDITIONAL.toLocaleString('ko-KR')}곳으로 판정했습니다.`
    if (entryVisible.length === 0) return `${head} 현재 예산으로 진입 가능한 후보는 없습니다.`
    const top = entryVisible
      .slice(0, 3)
      .map((a) => `${a.name}(${VERDICT_LABEL[a.verdict]}·${a.score}점)`)
      .join(', ')
    return `${head} 종합점수 상위는 ${top}입니다.`
  }, [data, counts])

  /**
   * 조건부 적합 상위 3곳 — 지도 아래 전용 패널에 싣는다.
   *
   * 서버 응답 순서(종합점수 내림차순)를 그대로 쓴다. 목록의 정렬(`sort`)을 따르지 않는 것은
   * 이 패널이 목록의 일부가 아니라 **분리된 자리**이기 때문이다 — 임대료순으로 정렬한 상태에서
   * 이 패널만 다른 기준으로 바뀌면 두 목록의 관계가 읽히지 않는다.
   */
  const conditionalTop = useMemo(
    () => (data?.areas ?? []).filter((a) => a.verdict === 'CONDITIONAL').slice(0, 3),
    [data],
  )

  const mapAreas = useMemo(() => areas.slice(0, MAP_MARKER_LIMIT), [areas])
  const listAreas = useMemo(() => areas.slice(0, listLimit), [areas, listLimit])

  /**
   * 지도가 지금 무엇을 그리고 있는지 한 문장으로.
   *
   * 상한(100곳)만 알리던 종전 문구는 「상위 100곳」이 무엇 중의 상위인지를 말하지 않아,
   * 갈 수 없는 후보가 섞여 있다는 사실이 화면 어디에도 없었다. 자치구까지 붙이는 것은 같은
   * 이유다 — 좁혀 놓은 상태에서 수만 보면 예산이 줄어든 것처럼 읽힌다.
   */
  const mapNote = useMemo(() => {
    if (areas.length === 0) return '지금 지도에 표시할 후보가 없습니다.'
    const where = guFilter === 'ALL' ? '' : `${guFilter}에서 `
    const what =
      verdictFilter === 'ENTRY'
        ? '확정 예산으로 지금 갈 수 있는'
        : (VERDICT_FILTERS.find((f) => f.value === verdictFilter)?.label ?? '')
    const capped =
      areas.length > mapAreas.length ? ` 중 추천 점수 상위 ${MAP_MARKER_LIMIT}곳` : ''
    const rest = areas.length > mapAreas.length ? ' 나머지는 목록에서 확인할 수 있습니다.' : ''
    return `${where}${what} ${areas.length.toLocaleString('ko-KR')}곳${capped}입니다.${rest}`
  }, [areas, mapAreas, guFilter, verdictFilter])
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
              큰 숫자는 **진입 가능**(적합+유의)이다 (2026-07-30). 종전에는 범위 외만 제외한
              수(실측 1,018곳)를 실었는데, 그중 636곳은 무권리 매물을 잡아야 열리는 구간이라
              확정 예산으로 갈 수 있는 곳의 수가 아니었다. 하단 슬라이더의 「진입 가능 N곳」·
              서버 프리뷰의 `area_count` 와 이제 같은 정의를 쓴다.
            */}
            <StatCard
              icon={MapPin}
              tone="purple"
              label="진입 가능 상권"
              value={`${counts.entry.toLocaleString('ko-KR')}곳`}
              caption={`조건부 적합 ${counts.CONDITIONAL.toLocaleString('ko-KR')}곳은 무권리 매물 기준으로 아래에 따로 표시됩니다`}
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
                /* 판정·자치구를 갈아타면 후보가 있는 자리가 달라진다 — 그때만 다시 맞춘다. */
                fitToken={`${verdictFilter}|${guFilter}`}
                /*
                 * 뷰포트 기준은 **표시 중인 마커가 아니라 후보 전체**다. 진입 가능이 6곳뿐인
                 * 예산(8,000만)에서 표시분에 맞추면 지도가 상계동 골목 하나로 확대돼, 「서울
                 * 어디까지 가능한가」를 보여줄 자리에서 서울이 사라진다. 범위 외까지 넣는 것은
                 * 그 집합만이 예산·필터와 무관하게 서울 전역으로 고정돼 있기 때문이다.
                 *
                 * 자치구를 고른 동안만 예외다 — 그때는 **사용자가 직접 범위를 좁힌 것**이므로
                 * 표시분에 맞춰 그 구를 채운다. 「서울 전체 보기」도 같은 기준을 따른다.
                 */
                fitAreas={guFilter === 'ALL' ? data.areas : undefined}
                /*
                 * 범례에서 바로 조건부 적합으로 건너뛴다 — 지도를 보다가 「노란 마커는 어디
                 * 갔나」가 될 자리라, 답을 그 자리에 둔다. 필터를 옮기는 것이므로 지도·목록·
                 * 구분별 개수가 한꺼번에 따라온다(표시 상태가 두 벌로 갈라지지 않는다).
                 */
                legendAction={
                  counts.CONDITIONAL > 0
                    ? verdictFilter === 'CONDITIONAL'
                      ? { label: '← 진입 가능 보기', onClick: () => setVerdictFilter('ENTRY') }
                      : {
                          label: `조건부 적합 ${counts.CONDITIONAL.toLocaleString('ko-KR')}곳 보기`,
                          onClick: () => setVerdictFilter('CONDITIONAL'),
                        }
                    : undefined
                }
              />
              {/*
                지도가 무엇을 그리고 있는지 한 줄로 말한다 — 상한(100곳)만 알리던 종전 문구는
                「상위 100곳」이 무엇 중의 상위인지를 말하지 않아, 갈 수 없는 후보가 섞여 있다는
                사실이 화면 어디에도 없었다.
              */}
              <p className={`t-caption ${styles.mapNote}`}>
                {mapNote}
                {/* 좁혀 놓은 동안에는 서울 전체 수를 함께 적는다 — 상단 카드의 큰 숫자와
                    지도의 수가 달라 보이는 이유가 「자치구를 좁혀 뒀다」임을 그 자리에서 말한다. */}
                {guFilter !== 'ALL' && verdictFilter === 'ENTRY' && (
                  <> 서울 전체로는 {counts.entry.toLocaleString('ko-KR')}곳입니다.</>
                )}
              </p>

              {/*
                조건부 적합 분리 패널 (2026-07-30).

                「숨긴다」가 아니라 **자리를 나눈다** 는 것이 이 패널의 뜻이다 — 개수·상위 후보·
                전환 버튼이 지도 바로 아래에 항상 있으므로, 조건부 적합이 화면에서 사라지지
                않으면서도 진입 가능과 같은 무게로 섞이지도 않는다.

                문구는 **무권리 매물이라는 조건**만 서술한다. 「예산을 올리면 열립니다」로 쓰는
                순간 상향 인사이트가 되어 지속 후보 수·하향 안전 마진·고지 문구를 함께 달아야
                하며(CLAUDE.md 원칙 3), 그 서사는 세 요소를 이미 갖춘 탐색 화면의 몫이다.
              */}
              {counts.CONDITIONAL > 0 && (
                <div className={styles.conditionalPanel}>
                  <p className={`t-body-strong ${styles.conditionalHead}`}>
                    조건 붙는 후보 {counts.CONDITIONAL.toLocaleString('ko-KR')}곳
                  </p>
                  <p className={`t-caption ${styles.conditionalDesc}`}>
                    권리금이 없는 매물을 잡아야 열리는 구간입니다. 확정 예산만으로 지금 갈 수 있는
                    곳은 아니어서 지도와 목록에서 분리해 두었습니다.
                  </p>
                  {conditionalTop.length > 0 && (
                    <ul className={styles.conditionalList}>
                      {conditionalTop.map((a) => (
                        <li key={a.area_code}>
                          <button
                            type="button"
                            className={`t-caption ${styles.conditionalItem}`}
                            /*
                             * 고르면 필터까지 옮긴다 — 그러지 않으면 지도에 마커가 없다.
                             * 좁혀 둔 축(자치구·구분)도 함께 푼다: 이 패널의 수는 예산 전체
                             * 기준이라, 좁힌 채로 열면 패널이 말한 것과 화면이 어긋난다.
                             */
                            onClick={() => {
                              setVerdictFilter('CONDITIONAL')
                              chooseGu('ALL')
                              setAreaTypeFilter('ALL')
                              setSelected(a.area_code)
                            }}
                          >
                            <span className={styles.conditionalName}>{a.name}</span>
                            <span className={styles.conditionalScore}>{a.score}점</span>
                          </button>
                        </li>
                      ))}
                    </ul>
                  )}
                  <div className={styles.conditionalActions}>
                    <Button
                      variant="secondary"
                      size="sm"
                      onClick={() => {
                        setVerdictFilter(verdictFilter === 'CONDITIONAL' ? 'ENTRY' : 'CONDITIONAL')
                        chooseGu('ALL')
                        setAreaTypeFilter('ALL')
                      }}
                    >
                      {verdictFilter === 'CONDITIONAL'
                        ? '진입 가능으로 돌아가기'
                        : '지도·목록에서 보기'}
                    </Button>
                    <button
                      type="button"
                      className={`t-caption ${styles.conditionalLink}`}
                      onClick={() => navigate('/explore')}
                    >
                      결정공간 탐색에서 보기 →
                    </button>
                  </div>
                </div>
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
                {VERDICT_FILTERS.map((f) => (
                  <button
                    key={f.value}
                    type="button"
                    className={`t-label ${styles.chip} ${verdictFilter === f.value ? styles.chipActive : ''}`}
                    aria-pressed={verdictFilter === f.value}
                    // 「진입 가능」은 판정이 아니라 두 판정의 합이라, 무엇의 합인지 밝혀 둔다.
                    title={f.value === 'ENTRY' ? '적합 + 유의 — 확정 예산으로 갈 수 있는 곳' : undefined}
                    onClick={() => setVerdictFilter(f.value)}
                  >
                    {f.label}
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

              {/*
                자치구 필터 (가정 #112).

                진단의 「희망 지역」이 이 화면에 닿는 유일한 자리다. 서버는 후보를 지역으로
                자르지 않으므로(서울 전역 1,059곳) 그 입력만으로는 결과가 한 글자도 달라지지
                않았고, 강남구를 고른 사람이 노원구 후보를 보며 「반영이 안 된다」고 판단했다.
                자르는 주체를 서버에서 **사용자**로 옮긴다 — 기본은 전체이고, 좁히는 것은 선택이다.

                25개를 칩으로 늘어놓으면 판정·구분 칩과 뒤엉키므로 드롭다운을 쓴다. 후보가 0곳인
                구도 목록에 남긴다 — 사라지면 「내 구가 왜 없지」가 되고, 0이라는 사실 자체가
                예산 대비 그 지역의 상태를 말해 준다.
              */}
              {scope && allGus.length > 0 && (
                <div className={styles.guRow}>
                  <label className={styles.sortLabel}>
                    <span className="t-caption">자치구</span>
                    <select
                      className={`t-caption ${styles.sortSelect}`}
                      value={guFilter}
                      onChange={(e) => chooseGu(e.target.value)}
                    >
                      {/* 「전체」의 수는 자치구를 풀었을 때의 수여야 한다 — 지금 보이는 수를
                          쓰면 구를 고른 순간 「전체」가 그 구의 수로 줄어 읽힌다. */}
                      <option value="ALL">
                        전체 ({guTotal.toLocaleString('ko-KR')}곳)
                      </option>
                      {allGus.map((gu) => (
                        <option key={gu} value={gu}>
                          {gu} ({guCounts.get(gu) ?? 0}곳)
                        </option>
                      ))}
                    </select>
                  </label>
                  {wishGu && guFilter !== wishGu && (
                    <button
                      type="button"
                      className={`t-label ${styles.chip}`}
                      onClick={() => chooseGu(wishGu)}
                    >
                      희망 지역 {wishGu}만 보기 ({(guCounts.get(wishGu) ?? 0).toLocaleString('ko-KR')}곳)
                    </button>
                  )}
                  {guFilter !== 'ALL' && (
                    <button
                      type="button"
                      className={`t-caption ${styles.conditionalLink}`}
                      onClick={() => chooseGu('ALL')}
                    >
                      서울 전체로
                    </button>
                  )}
                </div>
              )}
              {/*
                희망 지역에 후보가 0곳이면 좁히지 않고(위 자동 적용 효과) **그 사실을 말한다.**
                빈 목록을 보여 주는 것보다 정확하고, 이 서비스가 답하기로 한 질문
                (「내 한도로 어디까지 가능한가」)에 대한 답이 바로 이 문장이다.
              */}
              {scope && allGus.length > 0 && (
                <p className={`t-caption ${styles.filterHint}`}>
                  {wishGu && wishGuCount === 0 ? (
                    <>
                      확정 예산으로 <strong>{wishGu}</strong>에서{' '}
                      {VERDICT_FILTERS.find((f) => f.value === verdictFilter)?.label} 판정을 받은 곳은
                      아직 없어 서울 전체를 보여드립니다. 예산을 올리면 이 목록이 다시 {wishGu}로
                      좁혀집니다.
                    </>
                  ) : (
                    <>
                      진단에서 입력한 희망 지역은 <strong>지역 한정 상품의 자격 판정</strong>에
                      쓰입니다. 상권 후보는 서울 전역이며, 좁혀 보는 것은 여기서 선택합니다.
                    </>
                  )}
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

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
import { getRecommend, postBudget, postCheckArea } from '../api/client'
import { useSession } from '../store/session'
import { formatAmount } from '../lib/format'
import { buildComposition } from '../lib/composition'
import { prefersReducedMotion } from '../lib/motion'
import { VERDICT_LABEL } from '../lib/verdict'
import type { CheckAreaResponse, RecommendResponse, Verdict } from '../api/types'
import styles from './Recommend.module.css'

type SortKey = 'score' | 'rent' | 'sales' | 'floating'

/**
 * 지도 마커 상한. 실데이터는 1,000건대가 한 번에 오는데(실측 1,061건) 전량을 마커로 그리면
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

export default function Recommend() {
  const navigate = useNavigate()
  const { sessionId, version, budget, budgetPreview, selectedScenario, setBudget, bumpVersion } =
    useSession()

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
  const [selected, setSelected] = useState<string | null>(null)
  const [sort, setSort] = useState<SortKey>('score')
  const [verdictFilter, setVerdictFilter] = useState<Verdict | 'ALL'>('ALL')
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
    let cancelled = false
    if (hasDataRef.current) setRefreshing(true)
    else setLoading(true)

    getRecommend(sessionId ?? 'mock', version)
      .then((res) => {
        if (cancelled) return
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
      .finally(() => {
        if (cancelled) return
        setLoading(false)
        setRefreshing(false)
      })
    return () => {
      cancelled = true
    }
  }, [sessionId, version])

  const areas = useMemo(() => {
    if (!data) return []
    /*
     * 마커는 3종(적합·조건부 적합·유의) 고정이고 임의 추가가 금지되어 있다(스펙 §0-4).
     * 계약상 `areas`에는 `OUT_OF_SCOPE`도 섞여 오므로 화면 단계에서 걸러낸다 —
     * 총계(total_count)에는 남아 있으니 수치가 사라지는 것은 아니다.
     */
    const inScope = data.areas.filter((a) => a.verdict !== 'OUT_OF_SCOPE')
    const filtered =
      verdictFilter === 'ALL' ? inScope : inScope.filter((a) => a.verdict === verdictFilter)
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
  }, [data, sort, verdictFilter])


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

  // 지도에서 마커를 고르면 해당 카드가 목록 밖에 있을 수 있다 — 보이는 위치로 끌어온다.
  useEffect(() => {
    if (!selected || !listRef.current) return
    const card = listRef.current.querySelector<HTMLElement>(`[data-area-code="${CSS.escape(selected)}"]`)
    // CSS 미디어 쿼리는 JS가 부르는 스크롤에 닿지 않는다 — 여기서 직접 확인한다.
    card?.scrollIntoView({
      block: 'nearest',
      behavior: prefersReducedMotion() ? 'auto' : 'smooth',
    })
  }, [selected])

  // 상권을 고르면 역방향 판정을 재조회한다 (계약 6번 — 판정 4단계 + 부족분 + 자격 부합 상품).
  useEffect(() => {
    if (!sessionId || !verdictOf) {
      setCheck(null)
      return
    }
    let cancelled = false
    setChecking(true)
    postCheckArea(sessionId, verdictOf)
      .then((res) => {
        if (!cancelled) setCheck(res)
      })
      .finally(() => {
        if (!cancelled) setChecking(false)
      })
    return () => {
      cancelled = true
    }
  }, [sessionId, verdictOf, version])

  /* ── 하단 고정 슬라이더 (FE-05) ─────────────────────────────────────────── */

  const [sliderValue, setSliderValue] = useState(budget ?? 0)
  const [budgetPending, setBudgetPending] = useState(false)

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
      setBudgetPending(true)
      postBudget(sessionId, {
        confirmed_budget: sliderValue,
        composition: buildComposition(selectedScenario, sliderValue),
      })
        .then((res) => {
          setSliderValue(res.confirmed_budget)
          setBudget(res.confirmed_budget, res.preview)
          bumpVersion() // /recommend·/explore가 공유하는 version
        })
        .finally(() => setBudgetPending(false))
    }, 300)

    return () => clearTimeout(t)
  }, [sliderValue, budget, sessionId, selectedScenario, setBudget, bumpVersion])

  /*
   * 세션이 없으면 목 폴백으로 그럴듯한 화면이 떠서 "확정 예산 —"처럼 반쪽 상태가 된다
   * (새로고침·주소 직접 입력에서 실제로 발생). 화면을 보여주는 대신 앞 단계로 돌려보낸다.
   */
  if (!sessionId) return <Navigate to="/diagnose" replace />
  if (budget == null) return <Navigate to="/budget" replace />

  return (
    <AppShell activeStep={4}>
      <div className={styles.surface}>
        <div className={styles.header}>
          <div className={styles.titleRow}>
            <h1 className="t-title1">4단계. 입지 추천</h1>
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
            <StatCard
              icon={Receipt}
              tone="green"
              label="평균 환산 임대료 (월)"
              value={formatAmount(data.summary.avg_rent)}
            />
            <StatCard
              icon={TrendingUp}
              tone="orange"
              label="평균 추정 매출 (월)"
              value={formatAmount(data.summary.avg_sales)}
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
        {selectedScenario && (
          <BudgetSliderBar
            min={selectedScenario.budget_min}
            max={selectedScenario.budget_max}
            value={sliderValue}
            onChange={setSliderValue}
            preview={budgetPreview}
            conditionalCount={counts.CONDITIONAL}
            pending={budgetPending || refreshing}
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

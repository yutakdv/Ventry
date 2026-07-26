import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { MapPin, Receipt, TrendingUp, Wallet } from 'lucide-react'
import AppShell from '../components/layout/AppShell'
import Button from '../components/Button'
import StatCard from '../components/StatCard'
import KakaoMap from '../components/KakaoMap'
import AreaCard from '../components/AreaCard'
import CheckAreaPanel from '../components/CheckAreaPanel'
import Modal from '../components/Modal'
import { getRecommend, postCheckArea } from '../api/client'
import { useSession } from '../store/session'
import { formatAmount } from '../lib/format'
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
  const { sessionId, version, budget } = useSession()

  const [data, setData] = useState<RecommendResponse | null>(null)
  const [loading, setLoading] = useState(true)
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

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    getRecommend(sessionId ?? 'mock', version)
      .then((res) => {
        if (cancelled) return
        setData(res)
        setSelected(res.areas[0]?.area_code ?? null)
        setVerdictOf(null) // 예산이 바뀌면 이전 판정은 더 이상 유효하지 않다
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
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

  /** 판정 필터와 무관한 "범위 외 제외" 후보 총수 — 요약 카드용. */
  const inScopeCount = useMemo(
    () => (data ? data.areas.filter((a) => a.verdict !== 'OUT_OF_SCOPE').length : 0),
    [data],
  )
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
    card?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
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

  /*
   * 세션이 없으면 목 폴백으로 그럴듯한 화면이 떠서 "확정 예산 —"처럼 반쪽 상태가 된다
   * (새로고침·주소 직접 입력에서 실제로 발생). 화면을 보여주는 대신 앞 단계로 돌려보낸다.
   */
  if (!sessionId) return <Navigate to="/" replace />
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
              카드의 큰 숫자는 실제로 보이는 후보 수를 쓰고, 총 계산 대상은 캡션으로 밝힌다.
            */}
            <StatCard
              icon={MapPin}
              tone="purple"
              label="추천 상권 수"
              value={`${inScopeCount.toLocaleString('ko-KR')}곳`}
              caption={`범위 외 제외 · 총 ${data.total_count.toLocaleString('ko-KR')}곳 계산`}
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
          <div className={styles.columns}>
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
              {!data.risk_review.skipped && (
                <button type="button" className={`t-label ${styles.riskTrigger}`} disabled>
                  왜? (검증 의견 1건, 결과 전체) ˅
                </button>
              )}

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

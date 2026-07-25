import { useEffect, useMemo, useRef, useState } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { MapPin, Receipt, TrendingUp, Wallet } from 'lucide-react'
import AppShell from '../components/layout/AppShell'
import Button from '../components/Button'
import StatCard from '../components/StatCard'
import KakaoMap from '../components/KakaoMap'
import AreaCard from '../components/AreaCard'
import { getRecommend } from '../api/client'
import { useSession } from '../store/session'
import { formatAmount } from '../lib/format'
import { VERDICT_LABEL } from '../lib/verdict'
import type { RecommendResponse, Verdict } from '../api/types'
import styles from './Recommend.module.css'

type SortKey = 'score' | 'rent' | 'sales' | 'floating'

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
  const listRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    getRecommend(sessionId ?? 'mock', version)
      .then((res) => {
        if (cancelled) return
        setData(res)
        setSelected(res.areas[0]?.area_code ?? null)
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
    const filtered =
      verdictFilter === 'ALL' ? data.areas : data.areas.filter((a) => a.verdict === verdictFilter)
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

  // 지도에서 마커를 고르면 해당 카드가 목록 밖에 있을 수 있다 — 보이는 위치로 끌어온다.
  useEffect(() => {
    if (!selected || !listRef.current) return
    const card = listRef.current.querySelector<HTMLElement>(`[data-area-code="${CSS.escape(selected)}"]`)
    card?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  }, [selected])

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
            <StatCard icon={MapPin} tone="purple" label="추천 상권 수" value={`${data.total_count}곳`} />
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
            <KakaoMap
              areas={areas}
              selectedCode={selected}
              onSelect={setSelected}
              dataAsOf={data.data_as_of}
            />

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
                  areas.map((a) => (
                    <AreaCard
                      key={a.area_code}
                      area={a}
                      selected={a.area_code === selected}
                      onSelect={() => setSelected(a.area_code)}
                    />
                  ))
                )}
              </div>
            </div>
          </div>
        )}

        <p className={`t-caption ${styles.disclaimer}`}>
          ⓘ 본 정보는 공개 자료 기반 정보 제공이며 대출 권유·중개·자문이 아닙니다. 임대료는 한국부동산원 상권 분기
          평균(추정), 권리금은 연간 조사(전년 기준)이며 실제 금액은 개별 물건에 따라 다릅니다.
          {data && ` 데이터 기준일 ${data.data_as_of}.`}
        </p>
      </div>
    </AppShell>
  )
}

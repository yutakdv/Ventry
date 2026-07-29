import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { useKakaoLoader } from '../lib/useKakaoLoader'
import { MAP_LEGEND, VERDICT_LABEL, VERDICT_MARKER_COLOR } from '../lib/verdict'
import { formatBurdenRatio, formatTransit } from '../lib/format'
import { rentAreaShort, rentPerPyeong } from '../lib/rentArea'
import { ringsToLatLng, type AreaScope } from '../lib/areaScope'
import type { Area, Industry } from '../api/types'
import styles from './KakaoMap.module.css'

/**
 * 경계 표시 킬 스위치. `false` 면 화면이 정확히 도입 전 상태로 돌아간다
 * (D10 동결 예외의 되돌리기 단위 — docs/심사_QA.md).
 */
const SCOPE_BOUNDARY = true

/**
 * 경계 윤곽선 스타일 (가정 #96).
 *
 * **둘 다 채움이 없다(fillOpacity 0).** 두 가지를 동시에 얻으려는 선택이다 —
 * ① 면을 칠하지 않으므로 「이 영역 전체가 ○○ 판정」으로 읽힐 여지가 없다(스펙 §0-4:
 * 마커 3종 고정, 경계선은 판정 채널이 아니다) ② 채움이 없으면 넓은 구획 면이 그 아래
 * 마커의 클릭을 가로채지 않는다.
 *
 * 색도 판정 팔레트(VERDICT_MARKER_COLOR)를 쓰지 않는다. 이 선이 뜻하는 것은 판정이
 * 아니라 「선택됨」과 「임대료가 조사된 범위」이기 때문이다.
 */
const SCOPE_STYLE = {
  area: {
    strokeColor: '#111418',
    strokeWeight: 2.5,
    strokeOpacity: 0.95,
    strokeStyle: 'solid',
    fillOpacity: 0,
    zIndex: -1,
  },
  district: {
    strokeColor: '#3d4552',
    strokeWeight: 1.5,
    strokeOpacity: 0.75,
    strokeStyle: 'longdash',
    fillOpacity: 0,
    zIndex: -2,
  },
} as const

/**
 * 후보 전체를 담을 때 네 방향에 남길 여백(px).
 *
 * 위쪽만 190 을 주던 시절이 있었다 — 말풍선(≈150px)이 잘리지 않게 자리를 미리 비우는
 * 방어였는데, 지도 높이의 30% 를 빈 공간으로 예약하는 값이라 대가가 컸다. 후보가 아래로
 * 눌리고 중심이 북쪽으로 밀려, 1440×900 에서 지도 위쪽 1/3 이 후보가 하나도 없는
 * **경기 북부(파주·포천·동두천)** 로 채워졌다. 「서울 상권 컨설팅」인데 서울이 화면의
 * 작은 일부로 보였다 (이슈 #154).
 *
 * 그 방어는 이미 중복이었다 — 아래 선택 효과에서 말풍선이 상단에 걸리면 `panTo` 가
 * 지도를 옮긴다. 초기 fit 에서까지 자리를 비워 둘 이유가 없어 네 방향을 같은 값으로 맞춘다.
 */
const FIT_PADDING = 70

/**
 * 말풍선이 마커 위로 차지하는 높이(px). 상단 잘림 판정의 기준.
 *
 * 1440×900 실측 170px(상권명 한 줄 · 임대료 단가 행 포함)에 여유를 둔 값이다 — 내용에 따라
 * 몇 px 오르내리므로 판정은 넉넉한 쪽으로 틀려야 안전하다(불필요한 `panTo` < 잘린 말풍선).
 */
const OVERLAY_HEIGHT_PX = 180

/**
 * 선택된 상권의 말풍선.
 * 문자열 HTML 대신 DOM으로 만들어 textContent만 쓴다 — 상권명이 그대로 마크업이 되지 않도록.
 */
function buildOverlay(area: Area, industry: Industry | null | undefined, onClose: () => void): HTMLElement {
  const box = document.createElement('div')
  box.className = styles.overlay

  const head = document.createElement('div')
  head.className = styles.overlayHead
  const title = document.createElement('span')
  title.className = styles.overlayTitle
  title.textContent = area.name
  const badge = document.createElement('span')
  badge.className = `${styles.overlayBadge} ${styles[area.verdict]}`
  badge.textContent = VERDICT_LABEL[area.verdict]
  // 말풍선이 경계를 가릴 때 사용자가 직접 치울 수 있어야 한다 — 선택은 유지된다.
  const close = document.createElement('button')
  close.type = 'button'
  close.className = styles.overlayClose
  close.setAttribute('aria-label', '말풍선 닫기')
  close.textContent = '×'
  close.addEventListener('click', (e) => {
    e.stopPropagation()
    onClose()
  })
  head.append(title, badge, close)

  const rows = document.createElement('dl')
  rows.className = styles.overlayRows
  const add = (label: string, value: string) => {
    const dt = document.createElement('dt')
    dt.textContent = label
    const dd = document.createElement('dd')
    dd.textContent = value
    rows.append(dt, dd)
  }
  add('추천 점수', `${area.score}점`)
  // 말풍선은 dt/dd 한 줄이라 라벨이 길어지면 값이 밀린다 — 면적은 ㎡만 붙인다 (이슈 #151).
  const rentUnit = rentAreaShort(industry)
  add(
    `환산 임대료 (월${rentUnit ? `, ${rentUnit}` : ''})`,
    `${area.monthly_rent.toLocaleString('ko-KR')}만원`,
  )
  const perPyeong = rentPerPyeong(area.monthly_rent, industry)
  if (perPyeong) add('임대료 단가', perPyeong)
  add('부담률', formatBurdenRatio(area.burden_ratio))

  const transit = document.createElement('p')
  transit.className = styles.overlayTransit
  transit.textContent = formatTransit(area.transit)

  const hint = document.createElement('p')
  hint.className = styles.overlayHint
  hint.textContent = '자세한 근거는 오른쪽 목록에서 확인할 수 있습니다.'

  box.append(head, rows, transit, hint)
  return box
}

/**
 * 판정 색 원 + 점수 마커 (SVG data URI — 외부 이미지 의존 없음).
 * 선택 시에는 캔버스째 키운다. 반지름만 몇 px 늘리면 클릭됐다는 느낌이 나지 않는다.
 */
function buildMarkerImage(color: string, score: number, selected: boolean): kakao.maps.MarkerImage {
  const canvas = selected ? 52 : 36
  const c = canvas / 2
  const r = selected ? 19 : 13
  const font = selected ? 16 : 13
  // 선택 마커는 같은 색 반투명 링을 둘러 주변에서 확실히 도드라지게 한다.
  const halo = selected ? `<circle cx="${c}" cy="${c}" r="${r + 6}" fill="${color}" opacity="0.2"/>` : ''
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${canvas}" height="${canvas}" viewBox="0 0 ${canvas} ${canvas}">
${halo}<circle cx="${c}" cy="${c}" r="${r}" fill="${color}" stroke="#ffffff" stroke-width="${selected ? 3 : 2}"/>
<text x="${c}" y="${c + font * 0.35}" text-anchor="middle" font-family="'Noto Sans KR',sans-serif" font-size="${font}" font-weight="700" fill="#ffffff">${score}</text>
</svg>`
  return new kakao.maps.MarkerImage(
    `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`,
    new kakao.maps.Size(canvas, canvas),
    { offset: new kakao.maps.Point(c, c) },
  )
}

/**
 * 카카오맵 + 판정 마커 3종 (스펙 §7 · §1-1).
 * SDK를 못 불러오면(앱키 미설정·도메인 미등록) 지도 대신 안내를 보여준다 —
 * 지도가 없어도 우측 상권 목록으로 동선이 이어져야 한다.
 */
export default function KakaoMap({
  areas,
  selectedCode,
  onSelect,
  dataAsOf,
  industry,
  scope,
  legendAction,
  fitToken,
  fitAreas,
}: {
  areas: Area[]
  selectedCode: string | null
  onSelect: (areaCode: string) => void
  dataAsOf: string
  /** 말풍선 임대료의 면적 조건 표기용 (이슈 #151). */
  industry?: Industry | null
  /** 상권·구획 경계 (가정 #96). 없으면 경계 없이 오늘과 같은 화면이 된다. */
  scope?: AreaScope | null
  /**
   * 범례 옆에 붙는 전환 버튼 (조건부 적합 보기 등). 없으면 범례만 나온다.
   * ReactNode 가 아니라 라벨+동작으로 받는다 — 버튼이 범례 상자 안에 사는 이상 그 스타일은
   * 이 컴포넌트가 가져야 하고, 호출부가 자기 CSS 모듈의 클래스를 넘기게 두면 갈라진다.
   */
  legendAction?: { label: string; onClick: () => void }
  /**
   * 값이 바뀌면 후보 전체가 보이도록 뷰포트를 다시 맞춘다.
   *
   * 초기 fit 은 의도적으로 1회뿐이다 — 예산 슬라이더가 `areas` 를 매번 새로 만들기 때문에,
   * `areas` 변화마다 맞추면 자치구를 확대해 둔 시야가 한 칸 움직일 때마다 파괴된다(이슈 #154).
   * 하지만 **판정 필터를 옮기는 것은 다른 종류의 사건**이다: 진입 가능 6곳에서 조건부 적합
   * 542곳으로 갈아타면 후보가 있는 자리가 달라져, 확대해 둔 화면에 마커가 하나도 없을 수 있다.
   * 그래서 「같은 후보군을 다시 계산했다」와 「후보군을 갈아탔다」를 이 토큰으로 가른다.
   * 돌아가는 곳은 `fitAreas` 기준의 전체 보기이므로, 갈아탄 뒤의 화면은 언제나 서울 전체다.
   */
  fitToken?: string
  /**
   * 「전체 보기」의 기준이 되는 후보 집합. 없으면 `areas`(표시 중인 마커)를 쓴다.
   *
   * 둘을 나눈 이유가 이 화면의 첫인상이다 (2026-07-30). 뷰포트를 **표시 중인 마커**에 맞추면
   * 후보가 적은 예산에서 지도가 통째로 확대된다 — 확정 예산 8,000만의 진입 가능 6곳은
   * 노원구 상계동 일대 **약 1.2km × 0.9km** 안에 몰려 있어(위도폭 0.011°·경도폭 0.010°),
   * 진입하자마자 지도가 그 골목만 비췄다. 「서울 어디까지 가능한가」를 보여줄 자리에서
   * 서울이 사라지고, 「서울 전체 보기」 버튼조차 그 골목으로 돌아갔다.
   *
   * 그래서 뷰포트는 **예산·필터와 무관한 고정 기준**인 후보 전체(범위 외 포함 서울 상권
   * 1,059곳, 위도 37.435~37.690 · 경도 126.809~127.174)에 맞춘다. 상수를 박지 않고 데이터에서
   * 얻으므로 적재본이 바뀌어도 따라온다.
   */
  fitAreas?: Area[]
}) {
  const status = useKakaoLoader()
  /** 지도 컨테이너가 가리키는 대체 경로 안내의 id (m-4). */
  const mapAltId = useId()
  /**
   * 말풍선을 접었는가. 경계를 보려고 확대하면 폭 230px·높이 약 180px 짜리 말풍선이 정확히
   * 그 위를 덮는다. 확대 버튼을 누르면 자동으로 접고, 다른 상권을 고르면 다시 편다 —
   * 선택 자체는 유지되므로 카드·목록 연동은 그대로다.
   */
  const [overlayHidden, setOverlayHidden] = useState(false)
  const boxRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<kakao.maps.Map | null>(null)
  const markersRef = useRef<Map<string, kakao.maps.Marker>>(new Map())
  const boundsRef = useRef<kakao.maps.LatLngBounds | null>(null)
  const overlayRef = useRef<kakao.maps.CustomOverlay | null>(null)
  // 최신 onSelect를 유지해, 마커를 다시 만들지 않고도 콜백이 갱신되게 한다.
  const onSelectRef = useRef(onSelect)
  onSelectRef.current = onSelect
  /**
   * 같은 이유로 선택 코드도 ref로 읽는다 — 의존성에 넣으면 선택할 때마다 마커 전체가 다시 만들어진다.
   * 직전 선택은 강조를 되돌릴 대상 하나를 찾는 데 쓴다.
   */
  const selectedRef = useRef(selectedCode)
  selectedRef.current = selectedCode
  const prevSelectedRef = useRef<string | null>(null)
  const areaPolyRef = useRef<kakao.maps.Polygon | null>(null)
  const districtPolyRef = useRef<kakao.maps.Polygon | null>(null)
  /** 초기 1회만 후보 전체에 맞춘다 — 아래 fit 효과 주석 참조. */
  const fitDoneRef = useRef(false)
  /** 마지막으로 뷰포트를 맞춘 후보군. 초기값은 첫 렌더의 토큰이라 진입 직후에는 돌지 않는다. */
  const fitTokenRef = useRef(fitToken)
  /** 화면이 스스로 고른 첫 선택인가 — 그 한 번은 지도를 움직이지 않는다 (아래 말풍선 효과). */
  const initialSelectRef = useRef(true)

  /**
   * 선택된 상권의 경계와, 그 임대료가 **실제로 조사된** 부동산원 구획의 경계 (가정 #96).
   *
   * 폴백 여부는 `district` 문자열이 아니라 `fallback` 불리언으로 가른다 — 폴백 행의
   * district 는 API 에서 `null` 로 오고 계약 타입(`RentSource.district: string`)이 그
   * nullable 을 반영하지 못한다(등재 #96). 불리언만 항상 믿을 수 있다.
   *
   * 상권 경계를 못 찾으면 구획도 그리지 않는다. 둘 중 하나만 뜨면 「이 선이 무엇의
   * 경계인지」가 화면에서 사라진다.
   */
  /**
   * 범례는 **지금 지도에 실제로 찍힌 판정만** 싣는다 (2026-07-30).
   *
   * 종전에는 `MAP_LEGEND` 3종을 항상 그렸는데, 판정 필터가 붙은 뒤로는 그것이 거짓말이 된다 —
   * 「진입 가능」으로 좁혀 적합·유의만 찍힌 지도에 조건부 적합 범례가 남으면, 노란 마커를
   * 찾다가 없는 것을 화면 탓으로 돌리게 된다. 어휘 3종(스펙 §0-4)은 필터 칩과 아래
   * 조건부 패널이 항상 노출하므로 화면에서 사라지지 않는다.
   */
  const drawnLegend = useMemo(() => {
    const present = new Set(areas.map((a) => a.verdict))
    return MAP_LEGEND.filter((v) => present.has(v))
  }, [areas])

  const boundary = useMemo(() => {
    if (!SCOPE_BOUNDARY || !scope || !selectedCode) return null
    const area = areas.find((a) => a.area_code === selectedCode)
    const areaEntry = scope.areas.get(selectedCode)
    if (!area || !areaEntry) return null
    const district = area.rent_source?.fallback ? null : area.rent_source?.district
    return { areaEntry, districtEntry: (district && scope.districts.get(district)) || null }
  }, [scope, selectedCode, areas])

  // 지도 1회 생성
  useEffect(() => {
    if (status !== 'ready' || !boxRef.current || mapRef.current) return
    mapRef.current = new kakao.maps.Map(boxRef.current, {
      center: new kakao.maps.LatLng(37.5556, 126.9106),
      level: 6,
    })
  }, [status])

  // 후보가 바뀌면 마커를 다시 그리고 전체가 보이도록 범위를 맞춘다
  useEffect(() => {
    const map = mapRef.current
    if (status !== 'ready' || !map) return

    markersRef.current.forEach((m) => m.setMap(null))
    markersRef.current.clear()

    areas.forEach((a) => {
      const pos = new kakao.maps.LatLng(a.lat, a.lng)
      // 선택 상태를 여기서 반영한다(ref로 읽으므로 의존성은 늘지 않는다) — 아래 강조 효과가
      // 마커 전체를 다시 칠하지 않아도 되게 하려면 처음부터 맞는 이미지로 만들어야 한다.
      const selected = a.area_code === selectedRef.current
      const marker = new kakao.maps.Marker({
        position: pos,
        map,
        title: `${a.name} · ${VERDICT_LABEL[a.verdict]} · ${a.score}점`,
        image: buildMarkerImage(VERDICT_MARKER_COLOR[a.verdict], a.score, selected),
        zIndex: selected ? 10 : 1,
      })
      kakao.maps.event.addListener(marker, 'click', () => {
        // 같은 마커를 다시 눌러도 말풍선이 돌아와야 한다 — selectedCode 가 안 바뀌므로
        // 아래 리셋 효과에 기대지 못한다.
        setOverlayHidden(false)
        onSelectRef.current(a.area_code)
      })
      markersRef.current.set(a.area_code, marker)
    })
    prevSelectedRef.current = selectedRef.current

    /*
     * 뷰포트는 **표시 중인 마커가 아니라 후보 전체(서울)** 에 맞춘다 (`fitAreas` 주석 참조).
     * 네 방향 같은 여백 — 서울이 지도를 꽉 채운다. 말풍선 잘림은 아래 panTo 담당.
     *
     * **최초 1회만** 맞춘다. 이 효과는 `areas` 가 바뀔 때마다 도는데, 예산 슬라이더는 그
     * 배열을 매번 새로 만든다 — 조건이 `areas.length > 0` 뿐이던 동안에는 슬라이더를 한 칸
     * 움직일 때마다 뷰포트가 서울 전체로 튕겨 나가, 특정 자치구를 확대해 둔 상태가 파괴됐다.
     * 전체를 다시 보고 싶을 때는 아래 「서울 전체 보기」로 명시적으로 요청한다.
     */
    const bounds = new kakao.maps.LatLngBounds()
    ;(fitAreas ?? areas).forEach((a) => bounds.extend(new kakao.maps.LatLng(a.lat, a.lng)))
    boundsRef.current = bounds
    if (!fitDoneRef.current && !bounds.isEmpty()) {
      map.setBounds(bounds, FIT_PADDING, FIT_PADDING, FIT_PADDING, FIT_PADDING)
      fitDoneRef.current = true
    }
  }, [status, areas, fitAreas])

  /*
   * 후보군을 갈아탔을 때만 다시 맞춘다 (`fitToken` 주석 참조). 위 마커 효과가 `boundsRef` 를
   * 먼저 갱신하므로 — 선언 순서가 곧 실행 순서다 — 여기서는 그 결과를 그대로 쓴다.
   */
  useEffect(() => {
    const map = mapRef.current
    const b = boundsRef.current
    if (status !== 'ready' || !map || fitTokenRef.current === fitToken) return
    fitTokenRef.current = fitToken
    if (b && !b.isEmpty()) {
      map.setBounds(b, FIT_PADDING, FIT_PADDING, FIT_PADDING, FIT_PADDING)
    }
  }, [status, fitToken, areas])

  /**
   * 경계 윤곽선 2개 (가정 #96). 인스턴스는 만들어 두고 `setPath` 로만 갈아 끼운다 —
   * 선택을 오갈 때 SVG path 를 파괴·재생성하지 않기 위해서다.
   *
   * 예산 슬라이더는 이 효과를 건드리지 않는다. 경계가 판정색에 묶여 있지 않아, verdict 가
   * 바뀌어도 선택이 그대로면 그릴 것도 그대로다.
   */
  useEffect(() => {
    const map = mapRef.current
    if (status !== 'ready' || !map) return

    const draw = (
      ref: React.MutableRefObject<kakao.maps.Polygon | null>,
      style: (typeof SCOPE_STYLE)[keyof typeof SCOPE_STYLE],
      rings: number[][][] | null,
    ) => {
      if (!rings) {
        ref.current?.setMap(null)
        return
      }
      const path = ringsToLatLng(rings)
      if (!ref.current) {
        ref.current = new kakao.maps.Polygon({ path, ...style })
      } else {
        ref.current.setPath(path)
      }
      ref.current.setMap(map)
    }

    draw(areaPolyRef, SCOPE_STYLE.area, boundary?.areaEntry.rings ?? null)
    draw(districtPolyRef, SCOPE_STYLE.district, boundary?.districtEntry?.rings ?? null)
  }, [status, boundary])

  // 언마운트 시 폴리곤을 지도에서 떼어 낸다 (지도는 남고 컴포넌트만 사라지는 경우 대비).
  useEffect(
    () => () => {
      areaPolyRef.current?.setMap(null)
      districtPolyRef.current?.setMap(null)
    },
    [],
  )

  /**
   * 컨테이너 크기가 확정되기 전에 지도가 생성되면 뷰포트를 좁게 잡아 타일이 일부만 그려진다.
   * 크기가 바뀔 때마다 relayout 후 범위를 다시 맞춘다 (ResizeObserver는 관찰 즉시 1회 발화).
   */
  useEffect(() => {
    const map = mapRef.current
    const box = boxRef.current
    if (status !== 'ready' || !map || !box) return
    const ro = new ResizeObserver(() => {
      map.relayout()
      const b = boundsRef.current
      if (b && !b.isEmpty()) {
        map.setBounds(b, FIT_PADDING, FIT_PADDING, FIT_PADDING, FIT_PADDING)
      }
    })
    ro.observe(box)
    return () => ro.disconnect()
  }, [status])

  // 선택된 마커 강조 + 말풍선 표시 + 해당 위치로 지도 이동
  useEffect(() => {
    if (status !== 'ready') return
    const map = mapRef.current

    /*
     * 강조는 **바뀐 마커 둘만** 다시 칠한다 (직전 선택 해제 + 새 선택 강조).
     * 전건을 돌면 선택 한 번에 마커 이미지를 100개 다시 만드는데(SVG data URI 생성 포함),
     * 목록을 훑는 동안 매 클릭마다 그 비용을 치르게 되어 <100ms 체감(스펙 §7)이 무너진다.
     */
    const repaint = (code: string | null, selected: boolean) => {
      if (!code) return
      const marker = markersRef.current.get(code)
      const a = areas.find((x) => x.area_code === code)
      if (!marker || !a) return
      marker.setImage(buildMarkerImage(VERDICT_MARKER_COLOR[a.verdict], a.score, selected))
      marker.setZIndex(selected ? 10 : 1)
    }
    if (prevSelectedRef.current !== selectedCode) {
      repaint(prevSelectedRef.current, false)
      repaint(selectedCode, true)
      prevSelectedRef.current = selectedCode
    }

    const area = areas.find((a) => a.area_code === selectedCode)
    if (!map || !area || overlayHidden) {
      overlayRef.current?.setMap(null)
      return
    }

    const pos = new kakao.maps.LatLng(area.lat, area.lng)
    const content = buildOverlay(area, industry, () => setOverlayHidden(true))
    if (!overlayRef.current) {
      overlayRef.current = new kakao.maps.CustomOverlay({
        position: pos,
        content,
        yAnchor: 1.35, // 마커 위로 띄운다
        zIndex: 20,
      })
    } else {
      overlayRef.current.setPosition(pos)
      overlayRef.current.setContent(content)
    }
    overlayRef.current.setMap(map)

    /*
     * **화면이 스스로 고른 첫 선택으로는 지도를 움직이지 않는다** (2026-07-30).
     *
     * 진입 시 1위 상권이 자동 선택되는데, 그 말풍선이 상단에 걸리면 아래 판정이 곧바로
     * `panTo` 를 불러 첫 화면이 서울 전체가 아니게 된다. 확정 예산 8,000만의 진입 가능 6곳은
     * 전부 노원구 상계동(위도 37.656~37.667)이라 잘림 임계(약 37.60)를 넘어, 진입하자마자
     * 지도가 서울 북쪽으로 끌려가고 강남·강서가 화면 밖으로 나갔다.
     *
     * 아래 이동 규칙 자체는 그대로 둔다 — 그것은 **사용자가 마커를 고른 뒤**의 규칙이다.
     * 이 효과는 선택이 없으면 위에서 이미 빠져나가므로, 여기서 소비되는 것은 언제나
     * 자동 선택 한 번뿐이고 사용자의 첫 클릭은 정상적으로 이동한다.
     */
    if (initialSelectRef.current) {
      initialSelectRef.current = false
      return
    }

    /*
     * 이미 보이는 마커를 눌렀는데 지도가 움직이면 나머지 후보가 시야에서 밀려나 비교가 끊긴다.
     * 그래서 이동은 두 경우로 한정한다 — 화면 밖이거나, 상단에 너무 붙어 말풍선이 잘릴 때.
     *
     * 잘림 여유는 **말풍선 픽셀을 위도 폭으로 환산해** 정한다. 예전엔 위도 폭의 0.28 로
     * 박아 뒀는데 그건 지도 높이 560px 에서만 맞는 값이었다 — 높이를 460px 로 줄이면
     * 같은 150px 이 위도 폭의 0.33 을 차지해 상단 마커의 말풍선이 잘린다 (이슈 #154).
     * 컨테이너 높이에서 매번 환산하면 높이를 다시 조정해도 판정이 따라온다.
     */
    const b = map.getBounds()
    const latSpan = b.getNorthEast().getLat() - b.getSouthWest().getLat()
    const boxHeight = boxRef.current?.clientHeight ?? 0
    const clipSpan = boxHeight > 0 ? latSpan * (OVERLAY_HEIGHT_PX / boxHeight) : latSpan * 0.33
    const overlayClipped = area.lat > b.getNorthEast().getLat() - clipSpan
    if (!b.contain(pos) || overlayClipped) map.panTo(pos)
  }, [status, areas, selectedCode, industry, overlayHidden])

  // 다른 상권을 고르면 접힘을 푼다 — 접기는 「지금 이 경계를 보는 중」이라는 일시 상태다.
  useEffect(() => setOverlayHidden(false), [selectedCode])

  return (
    <div className={styles.panel}>
      {status === 'error' ? (
        <div className={styles.fallback}>
          <p className="t-body-strong">지도를 불러오지 못했습니다</p>
          <p className={`t-caption ${styles.fallbackHint}`}>
            카카오맵 앱키·도메인 등록을 확인해 주세요. 오른쪽 목록에서 추천 상권을 그대로 확인할 수 있습니다.
          </p>
        </div>
      ) : (
        <div className={styles.mapBox}>
          {/*
              지도는 키보드로 마커를 옮겨 다닐 수 없다. 우측 목록이 완전한 대체 경로로
              설계돼 있으므로(AreaCard 전체가 버튼) **그 사실을 스크린리더에도 알린다** —
              실질 차단은 아니지만 대체 경로가 있다는 것을 모르면 없는 것과 같다 (FE 리뷰 m-4).
            */}
          <p id={mapAltId} className={styles.srOnly}>
            지도의 마커는 키보드로 선택할 수 없습니다. 오른쪽 상권 목록에서 동일한 내용을 선택할 수 있습니다.
          </p>
          <div
            ref={boxRef}
            className={styles.map}
            role="application"
            aria-label="추천 상권 지도"
            aria-describedby={mapAltId}
          />
          {/* 초기 fit 을 1회로 줄인 대신, 전체 조망은 명시적으로 요청할 수 있게 남긴다. */}
          <div className={styles.mapBtns}>
            {/*
             * 상권 중앙 면적이 0.07㎢(한 변 약 268m)라, 후보 전체가 들어오는 기본 뷰에서는
             * 경계가 8px 남짓이라 사실상 보이지 않는다. 그렇다고 선택할 때마다 지도를 자동으로
             * 확대하지는 않는다 — 비교 중이던 시야가 매번 무너지기 때문이다(아래 panTo 주석과
             * 같은 이유). 대신 **명시적으로 요청**할 수 있게 둔다.
             */}
            {boundary && (
              <button
                type="button"
                className={`t-caption ${styles.fitBtn}`}
                onClick={() => {
                  const map = mapRef.current
                  if (!map) return
                  // 경계를 보려고 확대하는 것이므로 그 위를 덮는 말풍선은 접는다.
                  setOverlayHidden(true)
                  const b = new kakao.maps.LatLngBounds()
                  const rings = [
                    ...boundary.areaEntry.rings,
                    ...(boundary.districtEntry?.rings ?? []),
                  ]
                  rings.forEach((ring) =>
                    ring.forEach(([lng, lat]) => b.extend(new kakao.maps.LatLng(lat, lng))),
                  )
                  if (!b.isEmpty()) map.setBounds(b, 40, 40, 40, 40)
                }}
              >
                근거 범위로 확대
              </button>
            )}
            <button
              type="button"
              className={`t-caption ${styles.fitBtn}`}
              onClick={() => {
                const map = mapRef.current
                const b = boundsRef.current
                if (map && b && !b.isEmpty()) {
                  map.setBounds(b, FIT_PADDING, FIT_PADDING, FIT_PADDING, FIT_PADDING)
                }
              }}
            >
              서울 전체 보기
            </button>
          </div>
        </div>
      )}

      {/* 찍힌 마커도 없고 붙일 버튼도 없으면 빈 상자만 뜬다 — 그때는 범례를 내지 않는다. */}
      <div className={styles.legend} hidden={drawnLegend.length === 0 && !legendAction}>
        {drawnLegend.map((v) => (
          <span key={v} className={`t-caption ${styles.legendItem}`}>
            <span className={styles.legendDot} style={{ background: VERDICT_MARKER_COLOR[v] }} />
            {VERDICT_LABEL[v]}
          </span>
        ))}
        {legendAction && (
          <button
            type="button"
            className={`t-caption ${styles.legendBtn}`}
            onClick={legendAction.onClick}
          >
            {legendAction.label}
          </button>
        )}
      </div>

      {/* 판정 범례(3종)는 그대로 두고, 경계가 실제로 떠 있을 때만 선 뜻풀이를 덧붙인다. */}
      {boundary && (
        <p className={`t-caption ${styles.scopeLegend}`}>
          <span className={styles.scopeSolid} aria-hidden="true" /> 선택한 상권 경계
          <span className={styles.scopeDashed} aria-hidden="true" /> 임대료 조사 구획 경계
          {!boundary.districtEntry && ' (이 상권은 해당 구획이 없습니다)'}
        </p>
      )}

      <p className={`t-caption ${styles.note}`}>
        마커나 오른쪽 목록을 선택하면 서로 연동됩니다. · 데이터 기준일 {dataAsOf}
        {scope && (
          <>
            {' '}
            · 상권 경계 {scope.asOf.areas}판 · 임대료 조사 구획 {scope.asOf.districts}판 (통계와
            판본이 다릅니다) · 표시용 단순화 적용 (경계 오차 최대 약 8m)
          </>
        )}
      </p>
    </div>
  )
}

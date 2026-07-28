import { useEffect, useRef } from 'react'
import { useKakaoLoader } from '../lib/useKakaoLoader'
import { MAP_LEGEND, VERDICT_LABEL, VERDICT_MARKER_COLOR } from '../lib/verdict'
import { formatBurdenRatio, formatTransit } from '../lib/format'
import { rentAreaShort, rentPerPyeong } from '../lib/rentArea'
import type { Area, Industry } from '../api/types'
import styles from './KakaoMap.module.css'

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
function buildOverlay(area: Area, industry?: Industry | null): HTMLElement {
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
  head.append(title, badge)

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
}: {
  areas: Area[]
  selectedCode: string | null
  onSelect: (areaCode: string) => void
  dataAsOf: string
  /** 말풍선 임대료의 면적 조건 표기용 (이슈 #151). */
  industry?: Industry | null
}) {
  const status = useKakaoLoader()
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

    const bounds = new kakao.maps.LatLngBounds()
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
      kakao.maps.event.addListener(marker, 'click', () => onSelectRef.current(a.area_code))
      markersRef.current.set(a.area_code, marker)
      bounds.extend(pos)
    })
    prevSelectedRef.current = selectedRef.current

    boundsRef.current = bounds
    // 네 방향 같은 여백 — 후보 분포(서울)가 지도를 꽉 채운다. 말풍선 잘림은 아래 panTo 담당.
    if (areas.length > 0 && !bounds.isEmpty()) {
      map.setBounds(bounds, FIT_PADDING, FIT_PADDING, FIT_PADDING, FIT_PADDING)
    }
  }, [status, areas])

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
    if (!map || !area) {
      overlayRef.current?.setMap(null)
      return
    }

    const pos = new kakao.maps.LatLng(area.lat, area.lng)
    if (!overlayRef.current) {
      overlayRef.current = new kakao.maps.CustomOverlay({
        position: pos,
        content: buildOverlay(area, industry),
        yAnchor: 1.35, // 마커 위로 띄운다
        zIndex: 20,
      })
    } else {
      overlayRef.current.setPosition(pos)
      overlayRef.current.setContent(buildOverlay(area, industry))
    }
    overlayRef.current.setMap(map)

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
  }, [status, areas, selectedCode, industry])

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
        <div ref={boxRef} className={styles.map} role="application" aria-label="추천 상권 지도" />
      )}

      <div className={styles.legend}>
        {MAP_LEGEND.map((v) => (
          <span key={v} className={`t-caption ${styles.legendItem}`}>
            <span className={styles.legendDot} style={{ background: VERDICT_MARKER_COLOR[v] }} />
            {VERDICT_LABEL[v]}
          </span>
        ))}
      </div>

      <p className={`t-caption ${styles.note}`}>
        마커나 오른쪽 목록을 선택하면 서로 연동됩니다. · 데이터 기준일 {dataAsOf}
      </p>
    </div>
  )
}

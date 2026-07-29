import { useCallback, useMemo, useRef, useState } from 'react'
import { Download } from 'lucide-react'
import { downloadSvgAsPng } from '../lib/svgExport'
import styles from './FrontierChart.module.css'

const W = 400
const H = 260
const PAD = { left: 46, right: 10, top: 14, bottom: 44 }

/** 눈금 후보 — 값 범위에 맞춰 "좋은" 간격을 고른다(1·2·5 × 10ⁿ). */
function niceStep(span: number, target: number): number {
  const raw = span / target
  const mag = 10 ** Math.floor(Math.log10(raw))
  const norm = raw / mag
  const mult = norm <= 1 ? 1 : norm <= 2 ? 2 : norm <= 5 ? 5 : 10
  return mult * mag
}

function ticks(min: number, max: number, count: number): number[] {
  const step = niceStep(max - min, count)
  const out: number[] = []
  for (let v = Math.ceil(min / step) * step; v <= max; v += step) out.push(v)
  return out
}

export interface FrontierChartProps {
  /** `done.frontier_points` — [예산(만원), 진입 후보 수] 계단 좌표 */
  points: [number, number][]
  /** `done.current_budget` — "현재 예산" 마커 좌표 */
  currentBudget: number
}

/**
 * 예산-입지 프론티어 (스펙 §0-8 P1-② · expl §2-1).
 *
 * 계단 함수인 것이 핵심이다 — 예산이 특정 임계를 넘는 순간 후보가 계단식으로 열린다는 것이
 * "닫힌 형태 계산"의 시각 증거다. 곡선으로 부드럽게 이으면 그 성질이 사라지므로
 * 수평→수직 세그먼트로만 잇는다.
 *
 * 수치는 전부 서버가 준 좌표이며 화면은 좌표계 변환만 한다 (§0-1).
 */
export default function FrontierChart({ points, currentBudget }: FrontierChartProps) {
  const svgRef = useRef<SVGSVGElement>(null)
  const [exportError, setExportError] = useState(false)

  /** 기술설명서 삽입용 정적 이미지 (§0-8 P1-② ★v6.1). */
  const exportPng = useCallback(async () => {
    if (!svgRef.current) return
    setExportError(false)
    try {
      await downloadSvgAsPng(svgRef.current, `ventry-frontier-${currentBudget}.png`)
    } catch {
      // 내보내기가 실패해도 차트 자체는 멀쩡하다 — 화면을 망가뜨리지 않고 문구로만 알린다.
      setExportError(true)
    }
  }, [currentBudget])

  const model = useMemo(() => {
    if (points.length === 0) return null

    const xs = points.map((p) => p[0])
    const ys = points.map((p) => p[1])
    const xMin = Math.min(...xs)
    const xMax = Math.max(...xs)
    const yMax = Math.max(...ys)
    if (xMax === xMin || yMax === 0) return null

    const sx = (v: number) => PAD.left + ((v - xMin) / (xMax - xMin)) * (W - PAD.left - PAD.right)
    const sy = (v: number) => H - PAD.bottom - (v / yMax) * (H - PAD.bottom - PAD.top)

    // 계단 경로: 수평으로 이동 후 수직으로 상승
    let d = `M ${sx(points[0][0])} ${sy(points[0][1])}`
    for (let i = 1; i < points.length; i += 1) {
      d += ` L ${sx(points[i][0])} ${sy(points[i - 1][1])} L ${sx(points[i][0])} ${sy(points[i][1])}`
    }

    // 현재 예산 이하의 마지막 계단 = 그 예산에서 실제로 열리는 후보 수
    const cur = points.reduce((acc, p) => (p[0] <= currentBudget ? p : acc), points[0])

    return {
      d,
      sx,
      sy,
      xTicks: ticks(xMin, xMax, 4),
      yTicks: ticks(0, yMax, 4),
      dots: points.filter((_, i) => i % 6 === 0),
      cur,
      // 상·하한 모두 클램프한다 — 종전에는 하한만 잡아 예산이 xMax 를 넘으면 마커가
      // viewBox 밖에 그려졌다 (실사용 점검 2026-07-29).
      curX: sx(Math.min(Math.max(currentBudget, xMin), xMax)),
      curY: sy(cur[1]),
    }
  }, [points, currentBudget])

  if (!model) {
    return <p className={`t-caption ${styles.empty}`}>탐색이 끝나면 예산-후보 곡선이 표시됩니다.</p>
  }

  const label = `현재 예산 ${currentBudget.toLocaleString('ko-KR')}만원 · ${model.cur[1].toLocaleString('ko-KR')}곳`
  /*
   * 글자 폭 추정을 **한글 기준**으로 고친다 (실사용 점검 2026-07-29).
   *
   * 종전 `label.length * 6.4` 는 라틴 문자 폭이다. 이 라벨은 한글이 섞여 있고 한글은 11px
   * 폰트에서 사실상 1em(≈11px)을 차지하므로, 박스가 실제 텍스트보다 좁게 잡혀 **글자가
   * 자기 말풍선 밖으로 삐져나왔다.** 폭이 과소평가되면 `flip` 임계도 늦게 걸려 오른쪽
   * 경계에서 더 늦게 접힌다.
   */
  const calloutW =
    [...label].reduce((w, ch) => w + (/[가-힣ㄱ-ㅎㅏ-ㅣ]/.test(ch) ? 11 : 6.4), 0) + 16
  const flip = model.curX + calloutW + 14 > W
  /*
   * 좌우 모두 플롯 안으로 가둔다. `flip` 만으로는 예산이 x축 최대를 넘는 경우
   * (탐색 인사이트가 `budget_max` 를 넘기는 실데이터가 있다) 마커·말풍선이 viewBox 밖으로
   * 나가 PNG 내보내기에서 잘렸다.
   */
  const calloutX = Math.min(
    Math.max(flip ? model.curX - calloutW - 12 : model.curX + 12, PAD.left),
    W - calloutW,
  )
  /*
   * 마커가 바닥 근처면(후보 수가 적을 때) 말풍선이 x축 눈금 위에 겹친다.
   * 플롯 영역 안으로 끌어올려 축 라벨을 가리지 않게 한다.
   */
  const calloutY = Math.min(model.curY - 12, H - PAD.bottom - 26)

  return (
    <div className={styles.wrap}>
      <svg
        ref={svgRef}
        className={styles.svg}
        viewBox={`0 0 ${W} ${H}`}
        role="img"
        aria-label={`예산-입지 프론티어. ${label}.`}
      >
        <text className={styles.axisTitle} x={0} y={10}>
          진입 가능 상권 수 (곳)
        </text>

        {model.yTicks.map((t) => (
          <g key={`y${t}`}>
            <line className={styles.grid} x1={PAD.left} x2={W - PAD.right} y1={model.sy(t)} y2={model.sy(t)} />
            <text className={styles.axisText} x={PAD.left - 8} y={model.sy(t) + 4} textAnchor="end">
              {t.toLocaleString('ko-KR')}
            </text>
          </g>
        ))}

        {model.xTicks.map((t) => (
          <text
            key={`x${t}`}
            className={styles.axisText}
            x={model.sx(t)}
            y={H - PAD.bottom + 18}
            textAnchor="middle"
          >
            {t.toLocaleString('ko-KR')}
          </text>
        ))}
        <text className={styles.axisTitle} x={W / 2} y={H - 8} textAnchor="middle">
          총 예산 (만원)
        </text>

        <path className={styles.curve} d={model.d} />

        {model.dots.map(([x, y]) => (
          <circle key={`${x}-${y}`} className={styles.point} cx={model.sx(x)} cy={model.sy(y)} r={2.5} />
        ))}

        <circle className={styles.marker} cx={model.curX} cy={model.curY} r={6} />
        <rect
          className={styles.calloutBox}
          x={calloutX}
          y={calloutY}
          width={calloutW}
          height={22}
          rx={6}
        />
        <text className={styles.calloutText} x={calloutX + 8} y={calloutY + 15}>
          {label}
        </text>
      </svg>

      <div className={styles.actions}>
        <button type="button" className={`t-caption ${styles.export}`} onClick={() => void exportPng()}>
          <Download size={13} aria-hidden />
          이미지로 저장 (PNG)
        </button>
        {exportError && (
          <span className={`t-caption ${styles.exportError}`}>
            이미지를 만들지 못했습니다. 차트는 그대로 사용할 수 있습니다.
          </span>
        )}
      </div>
    </div>
  )
}

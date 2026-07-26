/**
 * SVG → PNG 내보내기 (FE-05 · 스펙 §0-8 P1-②).
 *
 * 기술설명서에는 영상 채널이 없어 프론티어 계단 차트가 **유일한 시각 서사 수단**이다(★v6.1).
 * 화면에 이미 그린 차트를 그대로 문서에 넣을 수 있어야 배치 산출물과 화면이 어긋나지 않는다.
 *
 * 외부 라이브러리를 쓰지 않는다 — 차트 하나 내보내려고 의존성을 늘릴 이유가 없고, 필요한 것은
 * 브라우저에 다 있다(`XMLSerializer` · `canvas` · `toBlob`).
 */

/**
 * 직렬화된 SVG에 남겨야 하는 표현 속성.
 *
 * **이 인라인 작업이 이 모듈의 존재 이유다.** 차트는 CSS 모듈 클래스로 칠해져 있는데, SVG를
 * 문서 밖으로 떼어내는 순간 그 스타일시트가 따라오지 않아 브라우저 기본값(검은 채움·선 없음)으로
 * 래스터화된다. 그래서 원본에서 계산된 값을 읽어 복제본에 직접 박는다. CSS 변수도 이 시점에
 * 구체적인 색으로 풀린다.
 */
const INLINE_PROPS = [
  'fill',
  'stroke',
  'stroke-width',
  'stroke-linejoin',
  'stroke-linecap',
  'stroke-dasharray',
  'opacity',
  'font-size',
  'font-family',
  'font-weight',
  'text-anchor',
] as const

/** 문서 삽입용이므로 배경은 흰색으로 고정한다 — 투명 PNG는 어두운 슬라이드에서 글자가 사라진다. */
const BACKGROUND = '#ffffff'

function inlineStyles(source: SVGSVGElement, clone: SVGSVGElement): void {
  const src = [source, ...Array.from(source.querySelectorAll<SVGElement>('*'))]
  const dst = [clone, ...Array.from(clone.querySelectorAll<SVGElement>('*'))]

  src.forEach((node, i) => {
    const target = dst[i]
    if (!target) return
    const computed = window.getComputedStyle(node)
    const declarations = INLINE_PROPS.map((p) => `${p}:${computed.getPropertyValue(p)}`).join(';')
    target.setAttribute('style', declarations)
    target.removeAttribute('class') // 클래스는 더 이상 가리키는 것이 없다
  })
}

/**
 * 화면의 SVG를 PNG로 내려받는다.
 *
 * `scale`은 문서 삽입 시 글자가 뭉개지지 않게 하는 배율이다 — 화면 렌더는 벡터라 상관없지만
 * PNG는 고정 해상도라 1배로 뽑으면 400px짜리 이미지가 되어 인쇄·확대에서 깨진다.
 */
export async function downloadSvgAsPng(
  svg: SVGSVGElement,
  filename: string,
  scale = 3,
): Promise<void> {
  const box = svg.viewBox.baseVal
  const width = box.width || svg.clientWidth
  const height = box.height || svg.clientHeight

  const clone = svg.cloneNode(true) as SVGSVGElement
  inlineStyles(svg, clone)
  clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg')
  clone.setAttribute('width', String(width))
  clone.setAttribute('height', String(height))

  const markup = new XMLSerializer().serializeToString(clone)
  // btoa는 라틴1만 받는다 — 축 라벨이 한글이라 UTF-8을 그대로 실을 수 있는 인코딩을 쓴다.
  const svgUrl = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(markup)}`

  const image = new Image()
  await new Promise<void>((resolve, reject) => {
    image.onload = () => resolve()
    image.onerror = () => reject(new Error('차트 이미지를 만들지 못했습니다.'))
    image.src = svgUrl
  })

  const canvas = document.createElement('canvas')
  canvas.width = Math.round(width * scale)
  canvas.height = Math.round(height * scale)
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('차트 이미지를 만들지 못했습니다.')

  ctx.fillStyle = BACKGROUND
  ctx.fillRect(0, 0, canvas.width, canvas.height)
  ctx.drawImage(image, 0, 0, canvas.width, canvas.height)

  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png'))
  if (!blob) throw new Error('차트 이미지를 만들지 못했습니다.')

  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

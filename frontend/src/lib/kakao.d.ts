/**
 * 카카오맵 JS SDK 최소 타입 선언 — 실제로 쓰는 API만 담는다.
 * SDK는 index.html에서 `autoload=false`로 로드되므로 `kakao.maps.load(cb)` 이후에만 접근 가능하다.
 */
declare namespace kakao.maps {
  function load(callback: () => void): void

  class LatLng {
    constructor(lat: number, lng: number)
    getLat(): number
    getLng(): number
  }

  class LatLngBounds {
    constructor()
    extend(latlng: LatLng): void
    isEmpty(): boolean
    contain(latlng: LatLng): boolean
    getSouthWest(): LatLng
    getNorthEast(): LatLng
  }

  class Size {
    constructor(width: number, height: number)
  }

  class Point {
    constructor(x: number, y: number)
  }

  interface MapOptions {
    center: LatLng
    level?: number
  }

  class Map {
    constructor(container: HTMLElement, options: MapOptions)
    setCenter(latlng: LatLng): void
    panTo(latlng: LatLng): void
    getBounds(): LatLngBounds
    getLevel(): number
    setLevel(level: number): void
    setBounds(bounds: LatLngBounds, ...padding: number[]): void
    relayout(): void
  }

  interface CustomOverlayOptions {
    position: LatLng
    content: string | HTMLElement
    map?: Map
    xAnchor?: number
    yAnchor?: number
    zIndex?: number
    clickable?: boolean
  }

  class CustomOverlay {
    constructor(options: CustomOverlayOptions)
    setMap(map: Map | null): void
    setPosition(latlng: LatLng): void
    setContent(content: string | HTMLElement): void
  }

  interface MarkerImageOptions {
    offset?: Point
  }

  class MarkerImage {
    constructor(src: string, size: Size, options?: MarkerImageOptions)
  }

  interface MarkerOptions {
    position: LatLng
    map?: Map
    image?: MarkerImage
    title?: string
    zIndex?: number
  }

  class Marker {
    constructor(options: MarkerOptions)
    setMap(map: Map | null): void
    setImage(image: MarkerImage): void
    setZIndex(z: number): void
  }

  interface PolygonOptions {
    /** 링 하나면 LatLng[], 구멍을 표현하면 LatLng[][]. */
    path: LatLng[] | LatLng[][]
    map?: Map
    strokeWeight?: number
    strokeColor?: string
    strokeOpacity?: number
    strokeStyle?: 'solid' | 'shortdash' | 'dot' | 'longdash' | 'dash'
    fillColor?: string
    fillOpacity?: number
    zIndex?: number
  }

  /**
   * 경계 윤곽선 (스펙 §0-4 — 판정 채널이 아니다. 채움 없이 선만 쓴다).
   * SDK 코어 번들에 있어 `libraries` 파라미터가 필요 없다.
   */
  class Polygon {
    constructor(options: PolygonOptions)
    setMap(map: Map | null): void
    setPath(path: LatLng[] | LatLng[][]): void
    setOptions(options: Partial<PolygonOptions>): void
  }

  namespace event {
    function addListener(target: object, type: string, handler: () => void): void
    function removeListener(target: object, type: string, handler: () => void): void
  }
}

interface Window {
  kakao?: typeof kakao
}

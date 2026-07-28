import type { Industry } from '../api/types'

/**
 * 업종 대표면적 — 임대료·보증금·권리금 금액이 "어느 크기 점포 기준인가"를 밝히기 위한 상수.
 *
 * `monthly_rent`는 상권의 임대료가 아니라 **그 상권 단가 × 업종 대표면적**이다. 곱셈의 절반인
 * 면적이 화면에 없으면, 사용자는 자기가 본 매물 호가와 다른 크기의 금액을 비교하게 된다
 * (실제로 방이동먹자골목 임대료 ↔ 매물 호가 250만원 오해가 팀 내에서 발생했다 — 이슈 #151).
 *
 * ⚠️ **배치 상수의 복제본이다.** 원천은 `ai/batch/preprocess/cost.py` 의
 * `REPRESENTATIVE_AREA_M2` 이며 API 로 내려오지 않는다(계약에 필드가 없다). 그래서 프론트가
 * 값을 들고 있을 수밖에 없는데, 두 곳이 어긋나면 화면이 조용히 거짓말을 한다. 값을 고칠 일이
 * 생기면 **이 객체 하나만** 고치도록 화면 어디에서도 숫자를 직접 쓰지 않는다.
 *
 * 2026-07-28 — 이슈 #152 로 두 값이 함께 교체됐다(cafe 29.2 → 44.0 · food 55.2 → 51.7).
 * 옛 값은 인허가 **대장 구분**(휴게/일반음식점) 중앙값이라 파이프라인 자신의 KSIC 분류와
 * 가로질렀고, 카페가 자기 면적 분포의 p28.5(8.8평)에 앉아 있었다. 배치 재적재로
 * `monthly_rent` 가 이미 새 면적 기준이므로 이 파일이 옛 값을 들고 있으면 라벨만 거짓이 된다.
 */
const REPRESENTATIVE_AREA_M2: Record<Industry, number> = {
  cafe: 44.0,
  food: 51.7,
}

const INDUSTRY_LABEL: Record<Industry, string> = {
  cafe: '카페',
  food: '음식점',
}

/** 1평 = 3.3058㎡ (부동산 관행 표기). 소수 첫째 자리까지. */
function toPyeong(m2: number): number {
  return Math.round((m2 / 3.3058) * 10) / 10
}

/**
 * 금액 라벨에 붙일 짧은 면적 조건 — "44.0㎡".
 *
 * 임대료 라벨은 **전 화면이 `환산 임대료 (월, 44.0㎡)` 한 형태로 통일**돼 있다. 업종명·평수·
 * "기준"·"추정" 같은 부연은 전부 출처 줄(`formatRentSource`)이 맡는다.
 *
 * 자리마다 길이를 달리하는 안을 먼저 넣어 봤다가 되돌렸다. 상권 카드의 stat 칸이 155px 뿐이라
 * "추정"까지 넣으면 "㎡)"만 다음 줄로 넘어가 2열 그리드에서 값 높이가 어긋났고, 무엇보다
 * 같은 지표가 자리에 따라 세 가지 이름으로 불리게 됐다. 짧은 쪽으로 맞추면 추정 여부는
 * §7 하드 룰로 상시 표기되는 출처 줄이 어차피 밝힌다.
 *
 * 업종을 모르면 `null` — 화면이 근거 없는 면적을 지어내지 않는다.
 */
export function rentAreaShort(industry: Industry | null | undefined): string | null {
  if (!industry) return null
  return `${REPRESENTATIVE_AREA_M2[industry]}㎡`
}

/**
 * 평당 단가 — "평당 약 11.9만원".
 *
 * 면적을 밝혀도 사용자의 평수가 대표면적과 다르면 여전히 직접 나눠야 한다. 단가가 있으면
 * "난 20평 생각했으니 월 238만원"으로 바로 환산된다 — 오해의 뿌리를 없애는 쪽이다 (이슈 #151).
 *
 * ⚠️ **`monthly_rent` 에서 역산한 값이다.** 정확한 `unit_price` 는 `rent` 테이블에만 있고
 * 계약·BE·FE 어디에도 없는데, 필드 추가는 D3 계약 동결 대상이라 3인 합의 + 계약 PR 이
 * 선행돼야 한다. 그래서 이미 가진 두 값으로 나눈다 (`walkMinutes` 와 같은 표현 계층 환산).
 *
 * 역산 오차는 `monthly_rent` 가 만원 단위 정수로 반올림된 데서 온다 — 적재본 실측으로
 * **카페 최대 0.35% · 평균 0.13%(n=1,059) · 음식점 최대 0.30% · 평균 0.10%(n=1,438)**
 * (2026-07-28 재측정, 대표면적 교정 반영). 평당 만원 단위(소수 첫째 자리)로 반올림하면 표기 자리 아래로
 * 사라지는 크기다. 그래서 ㎡당 원 단위 같은 정밀한 표기는 쓰지 않는다 — 거짓 정밀이 된다.
 */
export function rentPerPyeong(
  monthlyRentManwon: number,
  industry: Industry | null | undefined,
): string | null {
  if (!industry || !Number.isFinite(monthlyRentManwon) || monthlyRentManwon <= 0) return null
  const perPyeong = monthlyRentManwon / toPyeong(REPRESENTATIVE_AREA_M2[industry])
  return `평당 약 ${Math.round(perPyeong * 10) / 10}만원`
}

/**
 * 출처 줄에 덧붙일 면적 근거 — "카페 대표면적 44.0㎡(13.3평) 기준".
 *
 * 평을 함께 쓰는 이유는 오해의 출처가 '평' 단위 매물 호가이기 때문이다 — 같은 단위가 있어야
 * 사용자가 자기 기준으로 대조할 수 있다.
 */
export function rentAreaBasis(industry: Industry | null | undefined): string | null {
  if (!industry) return null
  const m2 = REPRESENTATIVE_AREA_M2[industry]
  return `${INDUSTRY_LABEL[industry]} 대표면적 ${m2}㎡(${toPyeong(m2)}평) 기준`
}

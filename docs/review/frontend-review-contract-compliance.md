# Frontend Review

리뷰어: 시니어 프론트엔드 엔지니어 (읽기 전용 리뷰) · 리뷰일 2026-07-29 · 대상 브랜치 `develop`
대상: `frontend/` 전체 (React 18 + Vite 5 + TS 5.5, 카카오맵 JS SDK, SSE)
기준 문서: `docs/API_CONTRACT.md`, `docs/specs/최종_스펙문서.md` v6.3, `docs/specs/exploration_agent_spec_v2_1.md`, `CLAUDE.md` 절대 원칙

> ⚠️ 리뷰 도중 작업 트리에 미커밋 변경이 계속 들어왔다(`git diff --stat` 기준 frontend 14개 파일 + backend 8개 파일).
> 본 리뷰와 아래 검증 로그는 **리뷰 종료 시점의 작업 트리**를 기준으로 하며, 인용한 줄번호는 그 시점에 재확인한 값이다.

## Executive Summary

프론트엔드는 스펙·계약 준수 관점에서 이례적으로 높은 완성도다. **출시를 차단하는 P0는 발견하지 못했다.**

- **용어 컴플라이언스**: 판정 문구가 `lib/verdict.ts` 단일 표에서만 나오고, 금지어("승인" 판정 표현, 자금 "권장/추천드립니다", "조달 가능") grep 결과 위반 0건. "승인" 히트는 전부 허용 문맥("한도·승인은 기관 심사 사항")이다. 고지 문구·데이터 기준일·임대료/권리금 라벨은 5개 화면 전부에 상시 표기된다.
- **API 계약 일치**: `src/api/types.ts`가 계약의 6개 엔드포인트를 필드 단위로 충실히 반영하며, non_null 직렬화(키 생략)·금리 표시 규칙(`rate` 있으면 "연 N%", 없으면 `rate_note` 원문)·`matching_products` 재정렬 금지·composition 그리디 배분 규칙까지 코드로 옮겨져 있다. 불일치는 경미한 타입 정밀도 2건(P2)뿐이다.
- **주요 리스크는 2건(P1)**: ① 프론트에 단위 테스트가 0건인데, 그중 `lib/composition.ts`는 API 계약이 "참조 구현"으로 지목한 계약 규칙 그 자체다. ② SSE가 첫 `onerror`에서 즉시 목 폴백으로 전환되어(재시도 없음) 일시적 끊김 한 번으로 세션 전체가 "예시 데이터" 모드로 고정되고, 스트림 중간 단절 시 실데이터와 목 데이터가 한 화면에 섞일 수 있다.
- lint(eslint)·`tsc --noEmit`·프로덕션 빌드 모두 통과. 테스트 스크립트는 package.json에 없어 실행 대상이 없다.

## Score — 88/100 (근거)

| 영역 | 평가 |
|---|---|
| 스펙·컴플라이언스 준수 (35) | 34 — 금지어 0건, 판정 4단계 단일 통로, 고지·기준일·라벨 상시 표기. 상향 인사이트 동반 요건 중 "하향 안전 마진"이 서버 T2 송출에 의존(P2-4)해 1점 감점 |
| API 계약 일치 (25) | 23 — 필드·직렬화 규칙·표시 규칙 일치. `district` nullable 미반영, check-area `skipped` 타입 과잉(각 P2) |
| 안정성·UX (20) | 16 — 요청 일련번호/AbortController/StrictMode 대응은 모범적. SSE 무재시도 폴백과 실·목 혼합 가능성(P1-2), frontierAt 경계값(P2-3) 감점 |
| 검증 가능성 (10) | 5 — lint/tsc/build 통과하나 테스트 0건(P1-1) |
| 빌드·배포 구성 (10) | 10 — nginx SSE 버퍼링 off·gzip·`/geo/` 404 처리, 코드 스플리팅, 카카오 키 빌드타임 주입과 실패 시 우아한 강등 |

## 요구사항 충족 평가 (스펙·API 계약 대비)

### API 계약 대조 (docs/API_CONTRACT.md ↔ src/api/types.ts·client.ts)

| 계약 | 프론트 구현 | 판정 |
|---|---|---|
| §1 `POST /diagnose` — 숫자는 form으로만, `region_hint` 프론트 조립 | `DiagnoseForm`(types.ts:10-18), Diagnose.tsx:47 `${sidoLabel} ${f.gu}` 조립, 금액은 form 필드로만 전송(선택 3필드만 free_text 맥락) | 일치 |
| §2 `GET /scenarios/{sid}` SSE — `budget`=초기 선택값(≠budget_max), 보수/적극은 수단 차이(크기순 전제 금지) | `Scenario`(types.ts:93-106) 주석까지 계약 D13 반영. Scenario.tsx:16-21·184-195가 "상한 역전 가능"을 화면 문구로 설명 | 일치 |
| §2 composition(구간)→§3 composition(단일값) 변환 — 자기자본 우선 그리디 | `lib/composition.ts:10-20` 계약 의사코드와 동일. 계약이 이 파일을 참조 구현으로 지목(API_CONTRACT.md:108) | 일치 (단, 무테스트 — P1-1) |
| §3 `POST /budget` — `data_as_of`(D9), 후보 0곳 시 range 생략 | `BudgetResponse`(types.ts:131-141), `BudgetPreview` optional range(types.ts:125-129) | 일치 |
| §4 `GET /recommend` — score 내림차순, `burden_ratio` 결측 시 생략, `rent_source.district` 폴백 시 null | `Area`(types.ts:177-199), `formatBurdenRatio`(format.ts:217-219)가 생략·비유한값 방어. **단 `district: string`(types.ts:151)이 null 미반영 — P2-1** | 대체로 일치 |
| §4 geometry 계약 밖 정적 자산 — `/geo/area-scope.v1.json` 세션 1회 캐시 | `lib/areaScope.ts`(모듈 레벨 Promise 캐시) + `hooks/useAreaScope.ts`(requestIdleCallback 지연 로드), 실패 시 null로 강등 | 일치 |
| §5 `GET /explore?v=` SSE — plan→insight→refine(선택)→done, `axis_labels` 서버 통제, `marginal_payment` 변동금리 시 생략+note | `ExplorePlanEvent`·`ExploreInsightEvent`(types.ts:227-281), Explore.tsx:238 라벨을 `plan.axis_labels`에서만 취득(하드코딩 사전 없음), ScenarioRow.tsx:91-94 note 대체 표기 | 일치 |
| §6 `POST /check-area` — `amount_max` 내림차순 고정 정렬, 재정렬 금지, `source_quote` 부재는 정상 | client.ts:217-232 재정렬 없음, CheckAreaPanel.tsx:17-20 주석으로 금지 명시, `source_quote?` optional. **`risk_review.skipped` 타입 과잉 — P2-2** | 대체로 일치 |
| 공통 — 금리 표기 규칙(rate 있으면 "연 N%", 생략 시 rate_note 원문, 분기 키는 rate_type) | `formatRate`·`formatRateNote`(format.ts:188-206) — 폴백 문구를 프론트가 만들지 않음 | 일치 |
| 공통 — non_null 직렬화(값 없으면 키 생략, undefined 허용 파싱) | optional 필드로 일관 반영(types.ts 전반) | 일치 |
| 공통 — WGS84 | lat/lng 직접 사용, GeoJSON `[lng,lat]` → `LatLng(lat,lng)` 변환 정확(areaScope.ts:154-160, KakaoMap.tsx:435) | 일치 |

### 절대 원칙(프론트 관련) 체크

| 원칙 | 확인 결과 |
|---|---|
| 판정 4단계 표기, "승인" 계열 금지 | `VERDICT_LABEL`(lib/verdict.ts:7-12) 단일 통로. grep 결과 "승인"은 전부 "한도·승인은 기관 심사 사항" 허용 문맥. 판정 표현 위반 0건 |
| 자금 "권장/추천드립니다" 금지 | "추천드립/권장드립/권장합니다" 히트 0건. "추천"은 입지 추천(서비스 기능명)·추천 점수에만 사용 — 자금 권유 아님. Budget.tsx:23 프리셋도 "추천" 회피 주석 |
| 상향 인사이트 단독 노출 금지 | ScenarioRow가 지속 가능 후보를 필수 병기(ScenarioRow.tsx:144-154), Explore 화면 하단 고지 상시(Explore.tsx:385-388), 되돌리기 경로 상시(ExploreSummary.tsx:130-134, session.tsx baseBudget 보존). 단 T2(하향 안전 마진) 자체는 서버 송출 의존 — P2-4 |
| 고지 문구 정확 문구 | 5개 화면 + 랜딩 푸터·CTA밴드에 원문 그대로 존재(Scenario.tsx:291, Budget.tsx:284, Recommend.tsx:513, Explore.tsx:385, ExploreSummary.tsx:162, SiteFooter.tsx:37, CtaBand.tsx:28) |
| 데이터 기준일 상시 표기 | Recommend.tsx:502, Budget.tsx(dataAsOf 조건 표기 — 서버 미제공 시 지어내지 않음), Explore.tsx:387, Scenario 상품별 `data_as_of`, KakaoMap.tsx:479. 기준일을 화면이 생성하는 곳 없음 |
| 임대료 라벨 "한국부동산원 ○○상권 분기 평균 (추정)" | `formatRentSource`(format.ts:51-61) — org 코드 `REB`→"한국부동산원" 매핑, 폴백 시 "자치구 평균 (추정 · 상권 단위 미매칭)"으로 사실 표기 |
| 권리금 라벨 "연간 조사(전년 기준)" | AreaCard.tsx:117, Recommend.tsx:514, ExploreSummary.tsx:157, SiteFooter.tsx:39 |
| 금액 만원 단위 정수 | 전 타입 주석·`formatAmount`/`splitAmount`(format.ts:9-21) 일관 |
| 마커 3종 고정(범위 외 미노출) | `MAP_LEGEND` 3종(verdict.ts:23), Recommend.tsx:147 OUT_OF_SCOPE 화면 제외, KakaoMap 범례 3종 |
| LLM 수치 생성 금지(§0-1) 프론트 측면 | 화면이 수치를 만들지 않음 — walkMinutes(결정적 계수, assumptions #85), neededAmount(서버 값 뺄셈), riskClaim(서버 verdict 집계). 위반 없음 |

## 상세 발견 사항

### P0 — 반드시 수정해야 출시 가능

- 발견된 P0 없음. 컴플라이언스 금지어·고지 문구·기준일 표기·계약 필드 대조에서 출시를 차단할 위반을 찾지 못했다.

### P1 — 출시 전 수정 권장

- **계약 참조 구현을 포함해 프론트 단위 테스트 0건** — 근거: `frontend/package.json` scripts에 test 없음(dev/build/lint/preview뿐), 테스트 파일 0개. 그런데 `frontend/src/lib/composition.ts:10-20`은 API 계약이 명시적으로 "참조 구현: FE `lib/composition.ts`"(docs/API_CONTRACT.md:108)로 지목한 배분 규칙이고, 계약 스스로 "규칙이 계약 밖에 있으면 클라이언트를 다시 구현할 때 결과가 조용히 갈리므로"라고 리스크를 적시했다. 이 값은 표기용이 아니라 잔여 한도 계산의 실제 입력(T1 근거 상품이 갈림)이다. `formatRate`/`formatBurdenRatio`/`buildComposition`처럼 계약 규칙을 코드로 옮긴 순수 함수가 무테스트면 회귀 시 계약 위반이 조용히 발생한다. 영향: 리팩터링·병합 시 배분 순서(equity 우선)나 한도 클램프가 깨져도 CI가 잡지 못함. 권장 조치: vitest 도입 후 `composition.ts`(equity 우선·amount_max 클램프·잔여 0 케이스)·`format.ts`(rate 유무 4분기·burden_ratio 생략/Infinity)·`verdict.ts` 라벨 스냅샷 최소 3파일만이라도 단위 테스트 추가, CI(fe lint 잡)에 연결.
- **SSE 첫 오류에서 무재시도 목 폴백 — 일시 단절로 세션이 "예시 데이터"로 고정되고 실·목 데이터 혼합 가능** — 근거: `frontend/src/api/client.ts:130-135`(scenarios)·`203-208`(explore)의 `es.onerror`가 오류 1회에 스트림을 닫고 `mockScenarios`/`mockExplore`로 폴백한다. EventSource는 일시 단절 시 자동 재연결을 시도하며 그때도 `onerror`가 발화하므로, 순간 blip(프록시 재시작·백엔드 GC 등)도 종국 실패로 처리된다. 폴백 플래그는 설계상 되돌리지 않으므로(`api/fallback.ts:13-15`) 이후 실호출이 전부 성공해도 "예시 데이터" 배너가 심사 종료까지 남는다. 또 스트림 중간 단절이면 이미 수신한 실데이터 카드 뒤에 목 카드가 이어 붙는다(Scenario.tsx:101 append) — 배너가 전체를 예시로 고지하므로 출처 사칭은 없지만, 실데이터까지 예시로 라벨링되고 보수/적극 카드가 실·목 혼성이 된다. 영향: 데모 중 네트워크 순간 이상 1회로 남은 심사 전체가 예시 모드로 강등. 권장 조치: `done` 수신 전 오류는 1회(짧은 지연 후) 재연결을 시도하고, 그때도 실패하면 **수신분을 비우고** 목으로 전환(혼합 방지). 이미 `done`까지 받은 뒤의 오류는 무시.

### P2 — 출시 후 개선 가능

- **`RentSource.district` 타입이 계약의 폴백 시 null을 반영하지 못함** — 근거: docs/API_CONTRACT.md:175-177 "⚠️ `rent_source.district`는 폴백 행에서 **`null`** 로 나간다", 그러나 `frontend/src/api/types.ts:151`은 `district: string`. 사용처는 전부 `fallback` 불리언으로 선분기해 런타임 안전하며(KakaoMap.tsx:199-216 주석이 이 불일치를 인지·등재 #96, format.ts:51-61도 폴백 분기 선행), 남은 것은 타입 정밀도뿐. 영향: 새 사용처가 폴백 분기 없이 `district`를 쓰면 컴파일러가 잡지 못함. 권장 조치: `district: string | null`로 정정.
- **check-area `risk_review`의 `skipped`가 계약 예시에 없는데 타입은 필수** — 근거: `CheckAreaResponse.risk_review: RiskReview`(types.ts:319)에서 `RiskReview.skipped: boolean` 필수(types.ts:202-206)이나, 계약 §6 응답 예시(API_CONTRACT.md:244)에는 `applied`만 있다. `RiskReviewPanel.tsx:46`의 `!review.skipped`는 undefined에도 의도대로 동작하므로 런타임 문제는 없다. **추가 검토 필요**: BE가 check-area에도 `skipped`를 싣는지 확인 후, 아니라면 `skipped?: boolean`으로 완화하거나 계약 예시에 명기.
- **`frontierAt`이 첫 계단점 아래 예산에서 첫 계단 값을 반환** — 근거: `frontend/src/screens/Explore.tsx:163-170`의 `pts.reduce((acc, p) => (p[0] <= b ? p : acc), pts[0])` — `b < pts[0][0]`이면 초기값 `pts[0]`이 그대로 남아, 해당 예산보다 높은 계단의 진입 후보 수를 표시한다. T2 하향 적용으로 예산이 프론티어 최저점 아래로 내려가는 경우 요약 스트립의 "진입 가능 상권"이 과대 표시될 수 있다. **추가 검토 필요**(서버가 frontier_points에 하한 이하 구간을 항상 포함하는지). 권장 조치: `b < pts[0][0]`이면 null(—) 반환.
- **상향 인사이트 동반 요건 중 "하향 안전 마진"이 서버 T2 송출에 의존** — 근거: 지속 후보 수(ScenarioRow.tsx:144-154)와 고지 문구(Explore.tsx:385, 인사이트별로는 접힘 패널 안 ScenarioRow.tsx:238)는 화면이 보장하지만, 하향 안전 마진은 T2 인사이트가 도착해야만 목록에 존재한다. 서버가 T1만 보내는 응답에서는 상향 선택지 옆에 하향 정보가 없다(되돌리기 경로는 적용 후에만 노출 — ExploreSummary.tsx:130-134). **추가 검토 필요**: BE가 T2를 항상 포함하는지 exploration spec §7 기준으로 확인. 아니라면 T2 부재 시 기준 예산 유지 행을 프론트가 상시 표기하는 보완 고려.
- **인사이트별 고지 문구가 접힘 패널 안에만 있음** — 근거: ScenarioRow.tsx:238의 `{insight.disclaimer && ...}`가 "근거 보기"를 펼쳐야 보이는 패널(ScenarioRow.tsx:210) 내부다. 화면 하단 상시 고지(Explore.tsx:385)가 있어 화면 단위 요건은 충족하나, 계약이 인사이트마다 `disclaimer: true`를 보내는 취지(카드 단위 동반)와는 거리가 있다. 권장 조치: 접힘 밖(행 하단)으로 이동 검토.
- **외부 리소스 의존(Google Fonts·카카오 SDK)과 랜딩 하드코딩 지표** — 근거: `frontend/index.html:8-15` — 폰트 CDN과 카카오 SDK를 외부에서 로드. 카카오 실패는 우아하게 강등되고(KakaoMap.tsx:402-408 목록 폴백, useKakaoLoader.ts) 폰트는 시스템 폰트로 대체되므로 차단은 아니나, 오프라인 심사장 대비면 폰트 self-host가 안전하다. 또 `screens/landing/landingData.ts:151` 등 랜딩 지표('1,042만원' 등)는 DB 재적재 시 어긋날 수 있는 상수다(주석이 "DB의 실제 값"이라 단언하므로 재적재 시 docs의 파급 목록과 함께 갱신 필요).
- **세션 인메모리 — 새로고침 시 전 단계 소실** — 근거: `store/session.tsx` 전체가 React state뿐. 각 화면의 가드(`Navigate to="/diagnose"`)가 반쪽 화면을 막아 안전하며 데모 특성상 수용 가능하나, 심사 중 실수로 새로고침하면 처음부터다. sessionStorage 백업은 출시 후 개선으로 충분.

## Good Points

- **컴플라이언스를 아키텍처로 강제** — 판정 문구는 `lib/verdict.ts` 단일 표에서만 생성되고, 금리 폴백 문구·탐색 축 라벨은 서버 송출 값만 사용(하드코딩 사전 금지 준수). 문구 위반이 구조적으로 어렵다.
- **목 폴백 고지 배너**(`api/fallback.ts` + AppShell.tsx) — "데모 무중단"과 "출처 사칭 금지"를 동시에 만족시키는 설계. 폴백 표시를 되돌리지 않는 이유까지 문서화되어 있다.
- **경쟁 상태를 실제로 잡았다** — `/budget` debounce에 요청 일련번호(Budget.tsx:59-84, Recommend.tsx 하단 슬라이더 동일), SSE에 AbortController + StrictMode 이중 실행 가드(Explore.tsx:74-160), 지연 응답이 최신 선택을 덮는 경로가 막혀 있다.
- **지도 성능·정합** — 마커 100개 상한을 UI에 고지, OUT_OF_SCOPE 제외(마커 3종 고정), 선택 시 바뀐 마커 2개만 리페인트, 경계 JSON은 idle 시점 지연 로드, GeoJSON 좌표 순서 변환 정확.
- **XSS 안전한 오버레이** — 카카오 CustomOverlay를 HTML 문자열이 아닌 DOM + textContent로 구성(KakaoMap.tsx:70-126).
- **riskClaim이 "실제로 화면에 보인 결과"를 반박 대상으로 구성**(Recommend.tsx:231-249 인근) — 보여주지 않은 후보를 제시했다고 말하는 왜곡을 스스로 막았다.
- **nginx 구성** — SSE 버퍼링 off·긴 read timeout, gzip, `/geo/` 프리픽스의 SPA 폴백 차단(=404)까지 실패 모드를 고려한 설정.
- 코드 전반의 주석이 "왜"를 이슈 번호·스펙 조항과 함께 남겨, 계약 변경 이력(D9·D12·D13)이 코드에서 추적된다.

## Remaining Tasks

1. (P1) vitest 도입 + `composition.ts`·`format.ts`·`verdict.ts` 단위 테스트, CI 연결.
2. (P1) SSE `done` 이전 오류의 1회 재연결 + 폴백 시 수신분 초기화(실·목 혼합 방지).
3. (P2) `RentSource.district`를 `string | null`로 정정.
4. (P2) check-area `skipped` 계약 확인 후 타입 정합(BE 확인 필요).
5. (P2) `frontierAt` 하한 이하 예산에서 null 반환.
6. (P2) T2 미송출 시 하향 정보 동반 방안 확인(exploration spec §7 대조) · 인사이트별 고지 문구 접힘 밖 노출 검토.
7. (P2) 폰트 self-host 검토, 랜딩 하드코딩 지표를 재적재 파급 목록과 함께 관리.

## 검증 로그

실행 환경: darwin, node_modules 기존 설치본 사용(`npm ci` 불필요). 모든 명령은 `/Users/yutak/Desktop/Ventry/frontend`에서 실행.

| 항목 | 명령 | 결과 |
|---|---|---|
| Lint | `npm run lint` (eslint .) | **통과** (1차 실행 통과 → 리뷰 중 동시 편집으로 일시 1 error 관측 → 최종 재실행 `npx eslint .` exit 0) |
| 타입 검사 | `npx tsc --noEmit` | **통과** (exit 0) |
| 빌드 | `npm run build` (tsc --noEmit && vite build) | **통과** — 코드 스플리팅 적용 상태, 주요 청크: index 207.71 kB(gzip 69.59), Recommend 30.81 kB, Explore 25.47 kB, CSS 72.71 kB. 633ms |
| 테스트 | — | **실행 대상 없음** — package.json scripts에 test 스크립트 없음, 테스트 파일 0개 (P1-1로 등재) |
| 금지어 grep | `grep -rn "승인\|추천드립\|권장드립\|권장합니다\|추천합니다\|조달 가능" src index.html` | UI 문자열 위반 **0건**. "승인" 히트 14곳은 전부 허용 문맥("한도·승인은 기관 심사 사항") 또는 주석. "조달 가능"은 금지 사유를 설명하는 주석에만 존재 |
| 고지 문구 grep | `grep -rn "대출 권유·중개·자문이 아닙" src` | 8곳 — Scenario/Budget/Recommend/Explore/ExploreSummary/ScenarioRow/SiteFooter/CtaBand, 원문 일치 |
| 라벨 grep | `grep -rn "한국부동산원\|연간 조사" src` | 임대료 "분기 평균(추정)" 라벨 format.ts:51-61 단일 함수 + 각 화면 요약문, 권리금 "연간 조사(전년 기준)" 4곳 확인 |
| 기준일 grep | `grep -rn "데이터 기준일" src` | Recommend:502 · Budget:268 인근 · Explore:387 · ExploreSummary:159 · KakaoMap:479 · Scenario 상품 상세 — 전 화면 표기, 서버 값(`data_as_of`)만 사용 |
| 작업 트리 상태 | `git diff --stat` | 리뷰 시점 미커밋 변경 존재(frontend 14파일 + backend 8파일, +627/-54) — 본 리뷰는 이 작업 트리 기준 |

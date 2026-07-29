# Frontend Review — Ventry

- 리뷰 일자: 2026-07-29 · 대상 브랜치 `develop`
- 대상: `frontend/` 전체 (React 18 + Vite 5 + TS 5.5 + CSS Modules, TS·TSX 49개 파일 / 7,259 LOC)
- 관점: **UX · UI 일관성 · 성능 · 유지보수성 · 접근성 · 코드 품질** (수정 적용형 리뷰)
- 검증 수단: `npm run lint`(eslint) · `npm run build`(`tsc --noEmit` + vite build) · vite dev 모듈 트랜스폼 확인
- 범위 제외: 백엔드 · AI 프롬프트 · QA · 비즈니스 로직 자체 · 디자인 취향

> 📎 **관련 문서** — 같은 날 작성된 `frontend-review-contract-compliance.md`는 **스펙·API 계약 준수**
> 관점의 읽기 전용 리뷰다(용어 컴플라이언스 grep, 계약 필드 대조, 절대 원칙 체크). 본 문서와
> 관점이 겹치지 않으므로 함께 읽는 것을 권한다. 두 문서가 독립적으로 지적한 공통 항목은
> **프론트 테스트 0건**과 **`RentSource.district` nullable 미반영** 둘이다.

---

# Executive Summary

**결론: Conditional Approve.**

이 프론트엔드는 프로토타입 평균을 크게 상회한다. 특히 세 가지가 두드러진다.

1. **상태 경계가 명확하다.** 예산의 진실 원천이 세션 하나로 고정돼 있고(`POST /budget` 덮어쓰기),
   화면 3·4·탐색이 모두 같은 값을 본다. 파생 데이터를 화면이 다시 계산하지 않는다.
2. **주석이 "왜"를 남긴다.** `KakaoMap`의 `FIT_PADDING`, `Explore`의 ref 회피, `AreaCard`의
   근거 줄 상시 표기 — 되돌리면 안 되는 결정에 근거가 붙어 있어 유지보수 비용이 낮다.
3. **성능 병목을 이미 인지하고 처리했다.** 마커 재도색을 2건으로 한정, 재조회 중 결과 유지(stale),
   경계 JSON `requestIdleCallback` 지연, 지도 마커 100건 상한.

반면 **일관성이 결정적인 지점에서 깨져 있었다.** 세션 가드가 `/map`·`/explore`·`/budget`에는 있고
`/scenarios`에만 없었고, `POST /budget` 요청 순서 보장이 어느 화면에도 없었다. 두 결함 모두
"한 번 발생하면 세션 전체가 잘못된 상태로 고정"되는 성질이라 P0/P1로 분류했다.

접근성은 가장 약한 축이다. 진단 폼의 **필수 입력 8개 전부가 스크린리더에서 이름이 없었고**,
법적 고지 문구가 화면에서 가장 대비가 낮은 텍스트(2.48:1)였다.

이번 리뷰에서 **P0 1건 + P1 11건을 직접 수정**했고, 빌드·린트가 모두 통과한다.
남은 항목은 아래 Action Items에 정리했다.

| 지표 | 수정 전 | 수정 후 |
|---|---|---|
| 초기 JS (gzip) | 106.25 kB | **69.61 kB** (−34%) |
| 초기 CSS (gzip) | 12.42 kB | **4.52 kB** (−64%) |
| 라우트 청크 | 0개 (단일 번들) | 5개 (화면별) |
| 진단 폼 접근 가능 이름 | 0 / 8 | **8 / 8** |
| 고지 문구 명도 대비 | 2.48:1 (AA 미달) | **5.35:1** (AA 통과) |

---

# Critical

## C-1. `/scenarios` 에 세션 가드가 없어 목 폴백 플래그가 영구 점등된다 — ✅ 수정함

### Problem
`Scenario.tsx`가 `getScenarios(sessionId ?? 'mock', …)`로 호출했고, 세션이 없을 때 화면을
앞 단계로 되돌리는 가드가 없었다.

### Evidence
- `frontend/src/screens/Scenario.tsx` (수정 전 84행): `sessionId ?? 'mock'`
- 세션은 메모리 전용(`store/session.tsx`)이라 **새로고침·주소 직접 입력이면 항상 null**이다.
- `/api/scenarios/mock` → 404 → `es.onerror` → `markApiFallback()` (`api/client.ts:130-135`)
- `api/fallback.ts:13-14`가 명시한다: "**되돌리지 않는다.** 뒤 호출이 성공해도 …"
- 같은 가드가 `/map`(`Recommend.tsx:107`), `/explore`(`Explore.tsx:75`), `/budget`에는 있었다.
  `Recommend.tsx:100-106` 주석이 이 결함을 정확히 기술하면서도 `/scenarios`만 빠져 있었다.

### Impact
`/scenarios`에서 새로고침 한 번 → ① "⚠ 서버에 연결하지 못해 **예시 데이터**로 표시하고 있습니다"
배너가 **세션 종료까지 지워지지 않는다.** 처음부터 다시 진단해 실데이터를 받아도 진짜 수치 위에
거짓 고지가 남는다. ② 목 시나리오로 화면 2·3을 진행한 뒤 화면 4에서 `/diagnose`로 튕겨 나간다 —
3화면 분량의 작업이 통째로 무효가 된다.
심사 중 발생하면 CLAUDE.md 불변 원칙 4(데이터 기준일·추정치 라벨 상시 표기)와 정면으로 어긋난다.

### Recommendation / 적용한 수정
```tsx
// Scenario.tsx
useEffect(() => {
  if (!sessionId) return          // 조회 자체를 하지 않는다
  …
}, [sessionId])

if (!sessionId) return <Navigate to="/diagnose" replace />
```
`?? 'mock'` 폴백을 제거하고 `/map`·`/explore`와 동일한 가드 형태로 맞췄다.

---

# Major

## M-1. `POST /budget` 요청 순서가 보장되지 않아 슬라이더가 이전 예산으로 되돌아간다 — ✅ 수정함

### Problem
`Recommend.tsx` 하단 슬라이더가 debounce 300ms만 두고 **응답 순서를 확인하지 않았다.**

### Evidence
- `Recommend.tsx:283-302`(수정 전): `setTimeout(300)` → `postBudget(...).then(res => setSliderValue(res.confirmed_budget))`
- 같은 파일 71-73행 주석이 명시한다: "BE 실측 **1.4~1.8초**".
  응답 시간(1.4s) ≫ debounce(300ms)이므로 **두 요청이 동시에 떠 있는 것이 정상 경로**다.
- 재현 경로: 예산을 5,000 → 6,000(요청 A 발사) → 400ms 후 7,000(요청 B 발사).
  B가 먼저 도착하면 슬라이더 7,000 확정 → 이어서 A가 도착해 `setSliderValue(6000)` 실행.
- 되돌아간 직후 `sliderValue === budget`이 성립해 effect의 종료 조건에 걸리므로,
  **effect가 스스로 멈춘다 — 잘못된 값이 그대로 최종 확정값이 된다.**

### Impact
사용자가 고른 예산이 아닌 값으로 입지 추천·프론티어·판정이 계산된다. 화면은 그 사실을 알리지
않는다. "내 한도로 어디까지 가능한가"를 답하는 서비스에서 예산이 조용히 바뀌는 것은 결과 전체를
무효화한다.

### Recommendation / 적용한 수정
요청 일련번호로 마지막 요청의 응답만 반영한다.
```tsx
const budgetSeqRef = useRef(0)
…
const seq = (budgetSeqRef.current += 1)
postBudget(...)
  .then((res) => {
    if (seq !== budgetSeqRef.current) return   // 뒤에 보낸 요청이 있다 — 버린다
    setSliderValue(res.confirmed_budget); setBudget(...); bumpVersion()
  })
  .finally(() => { if (seq === budgetSeqRef.current) setBudgetPending(false) })
```
`finally`도 함께 가드했다 — 뒤늦게 끝난 구 요청이 "계산 중" 표시를 먼저 꺼 버리는 문제가 있었다.

---

## M-2. 같은 경합이 화면 3(`/budget`)에도 있어 프리뷰와 확정 예산이 어긋난다 — ✅ 수정함

### Problem / Evidence
`Budget.tsx`의 `confirm()`도 동일하게 시퀀싱이 없었다(수정 전 50-68행). 여기서는 슬라이더 위치를
되돌리지는 않지만, `setPreview` / `setBudget(res.confirmed_budget, …)`이 구 응답으로 덮인다.

### Impact
사용자가 보는 슬라이더 값(`value`)과 "진입 가능 상권 N곳"·세션 확정 예산이 **서로 다른 예산의
결과**가 된다. 화면 4로 넘어가면 화면 3에서 본 숫자와 다른 결과가 나온다.

### Recommendation / 적용한 수정
`seqRef`로 동일하게 처리. 아울러 `postBudget(sessionId ?? 'mock', …)`의 `?? 'mock'`을 제거하고
`if (!sessionId) return <Navigate to="/diagnose" replace />` 가드를 추가했다.

---

## M-3. 화면 3의 주 CTA가 진입 직후 눌러도 아무 일이 없다 — ✅ 수정함

### Problem
"입지 추천 결과 보기 →"가 세션 예산이 확정되기 전에도 활성 상태였다.

### Evidence
- `Budget.tsx`: 진입 시 `useEffect` → debounce 300ms → `postBudget` → 응답 후에야 `setBudget` 호출
- `Recommend.tsx:309`: `if (budget == null) return <Navigate to="/budget" replace />`
- 즉 확정 전에 CTA를 누르면 `/map` → 즉시 `/budget` 복귀. 사용자에게는 **클릭이 삼켜진 것**으로 보인다.
- 무반응 구간은 최소 300ms + 응답 시간(실측 기준 1초 안팎).

### Impact
데모·심사 첫 클릭에서 발생하기 쉬운 자리다. 버튼이 죽은 것으로 읽힌다.

### Recommendation / 적용한 수정
```tsx
const budgetNotReady = sessionBudget == null
<Button disabled={noCandidate || budgetNotReady} …>
  {budgetNotReady ? '예산 계산 중…' : '입지 추천 결과 보기 →'}
</Button>
```
상태를 숨기지 않고 문구로 밝힌다.

---

## M-4. 시나리오 SSE에 종료 상태가 없어 무한 로딩에 빠질 수 있다 — ✅ 수정함

### Problem
`Scenario.tsx`가 `getScenarios()`의 Promise를 무시하고, `!selected`이면 항상
"시나리오를 불러오는 중…"을 렌더했다.

### Evidence
- 수정 전 `getScenarios(…)` 반환값 미사용, 완료/실패 상태 없음
- `api/client.ts:126-128`: 서버가 `scenario` 이벤트 없이 `done`만 보내도 Promise는 정상 resolve
- 편성 가능한 시나리오가 0건인 입력(자기자본·연령 조건 미충족 등)에서 실제로 도달 가능한 경로

### Impact
사용자는 영원히 도착하지 않는 결과를 기다린다. 되돌아갈 경로도 화면에 없다(상단 "입력 정보 수정"
버튼은 있으나 로딩 문구가 상황을 잘못 설명한다).

### Recommendation / 적용한 수정
`streamDone` 상태를 추가하고, 종료 후에도 결과가 없으면 원인 문구 + `/diagnose` 복귀 버튼을 낸다.
```tsx
getScenarios(…).finally(() => { if (!ac.signal.aborted) setStreamDone(true) })
```

---

## M-5. 진단 폼의 필수 입력 8개 전부가 스크린리더에서 이름이 없다 — ✅ 수정함

### Problem
`Field` 컴포넌트의 `htmlFor`가 선택 인자였고, `Diagnose.tsx`의 **8개 호출부 어디도 넘기지 않았다.**
`<label>`이 형제 요소일 뿐 어떤 컨트롤과도 연결돼 있지 않았다.

### Evidence
- `Field.tsx`(수정 전 18행): `<label htmlFor={htmlFor}>` — 값이 항상 `undefined`
- `TextInput`/`Select`에 `id`를 넘기는 호출부 없음 (전 파일 검색 결과 0건)
- `SegmentedChoice`는 `<div role="group">`인데 `aria-label`/`aria-labelledby`가 없어
  "기존 사업자 / 예비 창업자" 버튼만 읽히고 **질문이 무엇인지 사라졌다**
- `aria-invalid`, `aria-describedby` 도 전무 — 에러 메시지가 컨트롤과 연결되지 않았다

### Impact
스크린리더 사용자에게 진단 폼은 "이름 없는 입력 상자 8개"다. WCAG 2.1 **1.3.1 Info and
Relationships / 3.3.2 Labels or Instructions / 4.1.2 Name, Role, Value** 위반.
서비스의 첫 단계이자 필수 관문이므로 여기서 막히면 이후 화면에 도달할 수 없다.

### Recommendation / 적용한 수정
호출부 8곳이 각자 `id`를 붙이게 하면 필드가 늘 때마다 빠지므로, `Field`가 `useId()`로 id를
만들어 **자식 컨트롤에 직접 주입**하도록 바꿨다.
```tsx
const control = cloneElement(child, {
  id: controlId,
  'aria-labelledby': child.props['aria-labelledby'] ?? labelId,
  'aria-invalid': error ? true : undefined,
  'aria-describedby': error ? errorId : child.props['aria-describedby'],
})
```
- `SegmentedChoice`가 `id` / `aria-labelledby` / `aria-describedby`를 받도록 확장 (버튼 그룹은
  `label/for`로 이름을 받지 못한다).
- 라벨 하나에 컨트롤이 둘인 "희망 지역"은 자동 연결이 닿지 않으므로 두 `<Select>`에
  `aria-label="희망 지역 시/도"` / `"희망 지역 구/군"`을 직접 부여했다.
- 필드별 에러에는 `role="alert"`를 **두지 않았다** — 제출 시 8건이 동시에 나타나 낭독이 뒤엉킨다.
  요약 알림은 폼 상단 `Alert`(`role="alert"`) 한 곳이 맡는다.

---

## M-6. 검증 실패 시 포커스가 이동하지 않아 두 번째 제출부터 피드백이 없다 — ✅ 수정함

### Problem / Evidence
`Diagnose.tsx`의 `submit()`이 `window.scrollTo`만 했다(수정 전 106행). 키보드 포커스는 화면 맨
아래 제출 버튼에 남고, `Alert`는 이미 마운트돼 있으므로 **두 번째 제출부터는 `role="alert"`가
다시 낭독되지 않는다.**

### Impact
키보드·스크린리더 사용자는 제출이 실패했다는 사실 자체를 알 수 없다. 시각 사용자도 스크롤만
움직였을 뿐 다음 Tab이 폼 바깥으로 빠진다.

### Recommendation / 적용한 수정
표준 에러 요약 패턴대로 요약 블록에 포커스를 준다.
```tsx
const errorSummaryRef = useRef<HTMLDivElement>(null)
…
requestAnimationFrame(() => errorSummaryRef.current?.focus())
…
<div ref={errorSummaryRef} tabIndex={-1} className={styles.errorSummary}> <Alert …/> </div>
```
`:focus-visible` 아웃라인을 `Alert` 테두리와 겹치지 않게 바깥으로 뺐다.

---

## M-7. 법적 고지 문구가 화면에서 가장 대비가 낮은 텍스트다 (2.48:1) — ✅ 부분 수정

### Problem
CLAUDE.md 불변 원칙 3이 요구하는 고지 문구 5곳 전부가 `--color-text-tertiary` 12px로 렌더된다.

### Evidence
`--color-text-tertiary` = `--gray-400` = `#9ca1ab`. 상대 휘도 L = 0.3550.
- `#ffffff`(카드 배경) 대비: **2.59 : 1**
- `#fafaf8`(페이지 배경) 대비: **2.48 : 1**

WCAG 2.1 AA는 일반 텍스트 4.5:1, 큰 텍스트(≥24px 또는 ≥18.66px bold) 3:1을 요구한다.
12px 캡션이므로 **큰 텍스트 기준으로도 미달**이다.

적용 위치 (`grep`으로 확인, 5곳 모두 `.disclaimer`):
`screens/Budget.module.css:159` · `screens/Explore.module.css:145` ·
`screens/Scenario.module.css:171` · `screens/Recommend.module.css:150` ·
`components/ScenarioRow.module.css:191`

### Impact
저시력 사용자에게 읽히지 않는다. 동시에 **심사 리스크**이기도 하다 — "대출 권유·중개·자문이
아닙니다"는 프로젝트가 상시 노출을 하드 룰로 못 박은 문구인데, 실제로는 화면에서 가장 안 보이는
텍스트로 렌더되고 있었다. 노출과 가독은 다르다.

### Recommendation / 적용한 수정
`tokens.css`가 "값 임의 변경 금지"를 명시하므로 **토큰 값은 건드리지 않았다.** 대신 5곳의
`.disclaimer`를 기존 시맨틱 토큰 `--color-text-secondary`(`#666b75`, **5.35 : 1**, AA 통과)로
바꿨다. 새 값을 만들지 않고 토큰 체계 안에서 해결한 것이다.

**남은 작업(미적용):** `--color-text-tertiary`는 여전히 CSS 66곳에서 쓰인다(힌트·캡션·출처 줄).
`--gray-400`을 `#6e737d`(흰 배경 4.77:1 / 페이지 배경 4.56:1) 수준으로 올리면 전 지점이 한 번에
AA를 통과한다. 이것은 Figma Foundations 갱신이 선행돼야 하는 **디자인 시스템 결정**이므로
단독으로 적용하지 않았다. → Action Items P1

---

## M-8. 코드 스플리팅이 없어 랜딩 방문자가 앱 전체를 내려받는다 — ✅ 수정함

### Problem / Evidence
`App.tsx`가 5개 화면을 전부 정적 import했다. 빌드 산출물이 단일 청크였다.
```
dist/assets/index-*.js   314.13 kB │ gzip: 106.25 kB
dist/assets/index-*.css   72.71 kB │ gzip:  12.42 kB
```
랜딩만 보고 이탈하는 사용자도 카카오맵 연동·프론티어 차트 SVG·SSE 탐색 화면 코드를 전부 받는다.

### Impact
첫 화면(랜딩)의 TTI가 불필요하게 늘어난다. 심사·데모의 첫인상이 걸리는 자리다.

### Recommendation / 적용한 수정
랜딩만 즉시 로드하고 흐름 5화면을 `React.lazy`로 분리, `Suspense` fallback을 추가했다.
세션은 라우터 바깥 `SessionProvider`에 있으므로 분할이 상태에 영향을 주지 않는다.
```
초기 JS   106.25 kB → 69.61 kB gzip  (−34%)
초기 CSS   12.42 kB →  4.52 kB gzip  (−64%)
라우트 청크  Diagnose 7.06 / Scenario 4.28 / Budget 3.69 / Explore 9.77 / Recommend 11.97 kB gzip
```

---

## M-9. 랜딩 캡처 812 kB가 전부 즉시 로드된다 — ✅ 부분 수정

### Problem / Evidence
- `hero-preview.png` **595 kB** (히어로, 첫 화면)
- `report-preview.png` **217 kB** (쇼케이스, 스크롤해야 보임)
- `BrowserMock`에 `loading` / `decoding` / `fetchPriority` 지정 없음 → 둘 다 즉시 로드

### Impact
느린 회선에서 히어로 렌더가 지연되고, 아직 보이지도 않는 217 kB가 대역을 나눠 쓴다.

### Recommendation / 적용한 수정
`BrowserMock`에 `priority` prop을 추가했다. 히어로만 `eager` + `fetchPriority="high"`,
쇼케이스는 `lazy` + `decoding="async"` + `fetchPriority="low"`.
`width`/`height`가 이미 고정돼 있어 지연 로드로 레이아웃이 흔들리지 않는다.

**남은 작업(미적용):** 595 kB PNG 자체가 과하다. WebP/AVIF 변환 시 동일 화질에서 **70~85% 절감**이
기대된다(실측 필요). 이미지 재생성은 원본 캡처 자산에 대한 작업이라 리뷰 범위에서 임의로 하지
않았다. → Action Items P1

---

## M-10. 모바일에서 폼과 요약 카드가 고정 열 수로 눌린다 — ✅ 수정함

### Problem / Evidence
- `Diagnose.module.css`에 **`@media` 쿼리가 하나도 없었다.** `.row3`이 `1fr 1fr 1fr` 고정.
  375px 뷰포트에서 한 칸 ≈ 90px → `예) 5,000,000` 자리표시자가 잘리고 "원" 접미와 겹친다.
- `Recommend.module.css:37`(수정 전): `.summaryBar { grid-template-columns: repeat(4, minmax(0,1fr)) }` 고정.
  "진입 가능 1,059곳 · 조건부 적합 …" 캡션이 글자 단위로 줄바꿈된다.
- 참고: `AppShell`은 1200px·900px 브레이크포인트를 갖고 있어 셸은 접히지만 **내용은 접히지 않았다.**
- 화면 3(`Budget.module.css:154`)은 이미 `auto-fit minmax(250px, 1fr)`로 올바르게 처리돼 있었다 —
  같은 성격의 요소가 화면마다 달랐다.

### Impact
태블릿 세로(768px)·모바일에서 진단 폼의 금액 입력값을 확인할 수 없다. 진단은 필수 관문이다.

### Recommendation / 적용한 수정
화면 3과 동일한 `auto-fit` 방식으로 통일했다 — 브레이크포인트를 필드마다 다시 정할 필요가 없다.
```css
.row3      { grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); }  /* 금액+접미 최소폭 */
.row2      { grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); }
.summaryBar{ grid-template-columns: repeat(auto-fit, minmax(230px, 1fr)); }
```

---

## M-11. 도달하지 않은 시나리오 카드가 눌러도 반응이 없다 — ✅ 수정함

### Problem / Evidence
`Scenario.tsx`: `onClick={() => available && setSelectedLabel(label)}`.
SSE로 아직 도착하지 않은 카드는 시각적으로 활성 상태 그대로인데 클릭이 무시됐다.
`ScenarioCard`에 `disabled` 개념이 없었다.

### Impact
"적극적 시나리오" 카드를 눌렀는데 아무 일도 일어나지 않는다. 고장으로 읽힌다.
`aria-pressed`만 있고 `disabled`가 없어 스크린리더에도 선택 가능한 것으로 노출된다.

### Recommendation / 적용한 수정
`ScenarioCard`에 `disabled` prop을 추가하고 `.card:disabled { opacity: .5; cursor: not-allowed }`를 적용.

---

## M-12. 존재하지 않는 경로가 빈 화면을 낸다 — ✅ 수정함

### Problem / Evidence
`App.tsx`의 `<Routes>`에 `path="*"`가 없어, 라우트 미매칭 시 `null`이 렌더됐다.

### Impact
오타·구 링크로 진입하면 완전한 백지 화면. 되돌아갈 단서가 없다.

### Recommendation / 적용한 수정
`<Route path="*" element={<Navigate to="/" replace />} />`. 주소와 내용이 어긋나지 않도록
랜딩 렌더가 아니라 리다이렉트를 택했다.

---

## M-13. 새로고침하면 세션이 통째로 사라지고 설명이 없다 — ⚠️ 미수정 (설계 결정 필요)

### Problem / Evidence
`store/session.tsx`가 전부 `useState`다. 영속화가 없다.
- 어느 화면에서든 새로고침 → `sessionId=null` → 가드 연쇄로 `/diagnose`까지 되돌아간다
  (`/map` → `/budget` → `/scenarios` → `/diagnose`).
- 이동은 정상 종료하지만 **사용자에게는 아무 설명이 없다.** 방금 본 결과가 사라진 이유를 알 수 없다.
- `GNBHeader.tsx:28-32` 주석이 이 위험을 이미 인지하고 있다("앵커가 일으키는 전체 리로드 한 번이면
  통째로 사라진다") — 그래서 GNB 링크를 라우터 링크로 바꿨지만, 브라우저 새로고침은 남아 있다.

### Impact
심사·데모 중 실수로 F5를 누르면 처음부터 다시 해야 한다. 뒤로가기로 `/map`에 돌아와도 마찬가지다.

### Recommendation (미적용)
두 가지 중 택일이 필요하며, 어느 쪽도 리뷰어가 단독으로 정할 사안이 아니다.
1. **`sessionStorage` 영속화** — `{sessionId, parsedProfile, budget, baseBudget, selectedScenario,
   budgetPreview, dataAsOf}`만 저장하고 `explore` 캐시는 제외. 서버 저장이 아니므로
   "입력한 정보는 … 서버에 저장하지 않습니다"(`Diagnose.tsx:130`) 문구와 충돌하지 않는다.
   단, 개인 자금 정보가 브라우저에 남는 것에 대한 정책 판단이 필요하다.
2. **최소 대응** — `/diagnose`로 되돌릴 때 `Navigate`에 `state`를 실어 "세션이 만료되어 처음부터
   시작합니다" 안내를 노출한다. 데이터는 남기지 않는다.

리뷰어 의견으로는 **2안이 현 스코프에 맞는다** — 마감이 가깝고, 1안은 저장 항목·만료·개인정보
문구를 함께 손봐야 한다.

---

## M-14. API 실패가 전부 조용한 목 폴백이라 사용자에게 재시도 경로가 없다 — ⚠️ 미수정

### Problem / Evidence
`api/client.ts`의 6개 함수 전부가 `catch → markApiFallback() → mock*()` 패턴이다.
**어떤 호출도 reject하지 않는다.** 따라서 화면에는 에러 상태 자체가 존재하지 않는다.
- `Recommend.tsx:360`의 `!data` 분기("추천 결과를 불러오지 못했습니다")는 사실상 도달 불가능한
  죽은 코드다.
- 사용자가 할 수 있는 일은 "새로고침"뿐인데, 그러면 M-13으로 세션이 날아간다.
- (`frontend-review-contract-compliance.md`의 P1-2가 같은 지점을 SSE 재시도 부재 관점에서 지적한다.)

### Impact
"데모 무중단"이라는 설계 의도는 달성했지만, **일시적 장애에서 회복할 방법이 없다.** BE가 5초 뒤
살아나도 화면은 계속 목 데이터를 보여주고 배너는 지워지지 않는다(의도된 비가역 설계).

### Recommendation (미적용)
`AppShell`의 폴백 배너에 "다시 시도" 버튼을 추가하고, 누르면 현재 화면의 조회를 재실행한다
(`bumpVersion()`으로 effect 재트리거가 가능하다). 배너 자체의 비가역성은 유지한다 — 이미 화면에
섞인 목 수치를 되돌릴 수 없다는 판단은 옳다. 다만 **재시도 후 성공하면** "실데이터로 갱신됨"을
같은 자리에 덧붙여, 무엇이 실데이터인지 구분 가능하게 한다.
→ 계약·BE 협의 없이 프론트 단독 구현 가능. Action Items P1.

---

# Minor

## m-1. `getRecommend` / `postCheckArea`에 `AbortSignal`이 없다
`Recommend.tsx:109·256`은 `cancelled` 불리언으로 **결과만 무시**한다. 네트워크 요청은 계속 진행되고
응답 본문도 끝까지 받는다. SSE 두 함수(`getScenarios`·`getExplore`)는 이미 `signal`을 받으므로
패턴이 갈려 있다. → `client.ts`의 `fetch`에 `signal`을 전달하도록 통일 권장. (P2)

## m-2. 정렬·필터 변경 시 `listLimit`이 초기화되지 않는다
`Recommend.tsx:79`. "더 보기"로 200건까지 늘린 뒤 예산을 바꾸면 새 결과도 200건이 한 번에
렌더된다. 카드 1장의 DOM이 크므로(스탯 6개 + 근거 4줄) 체감에 닿는다. → `data`·`sort`·`verdictFilter`
변경 시 `setListLimit(LIST_PAGE)`. (P2)

## m-3. 탐색 화면의 `applying`이 전역 플래그다
`Explore.tsx:43`. 한 시나리오를 적용하는 동안 **모든 행**의 버튼이 비활성화된다. 의도적일 수
있으나(중복 적용 방지), 어느 것을 누른 건지 알 수 없다. → 적용 중인 `insight_id`를 담아 해당
행만 "적용 중…"으로 표시하고 나머지는 단순 비활성. (P2)

## m-4. 지도를 키보드로 조작할 수 없다
`KakaoMap.tsx:411`의 `role="application"` 컨테이너에 마커 포커스 경로가 없다. 다만 **우측 목록이
완전한 대체 경로**로 설계돼 있고(`AreaCard` 전체가 버튼), 지도 실패 시 폴백 문구도
"오른쪽 목록에서 그대로 확인할 수 있습니다"로 안내한다. 실질 차단은 아니므로 Minor로 둔다.
→ 개선한다면 `role="application"`에 `aria-describedby`로 "목록에서 동일한 내용을 선택할 수
있습니다"를 연결. (P2)

## m-5. `AreaCard` 본문 버튼의 접근 이름이 카드 전체 텍스트다
`AreaCard.tsx:62`. `<button>` 안에 점수·6개 스탯·근거 4줄·분석 근거가 모두 들어 있어 스크린리더가
한 번에 전부 낭독한다. 하단 "판정·자격 부합 상품 보기" 버튼은 별도 `aria-label`이 잘 붙어 있다.
→ 본문 버튼에 `aria-label={`${area.name} 선택`}`을 주면 이름은 짧아지고 내용은 읽기 모드에서
그대로 접근 가능하다. (P2)

## m-6. `RentSource.district`의 타입이 실제 nullable을 반영하지 못한다
`api/types.ts`가 `district: string`인데 폴백 행은 `null`로 온다. 코드 두 곳
(`Recommend.tsx:223`, `KakaoMap.tsx:214`)이 주석과 함께 `fallback` 불리언으로 우회하고 있다.
D3 계약 동결 이후라 타입 수정은 합의 사항이지만, 최소한 프론트 타입만이라도
`district: string | null`로 좁히면 우회 코드가 타입으로 설명된다.
(계약 리뷰 문서의 P2-1과 동일 지적.) (P2)

## m-7. 프론트엔드 테스트가 0건이다
`*.test.*` / `*.spec.*` / `vitest.config.*` 모두 존재하지 않고 `package.json`에 `test` 스크립트가
없다. `lib/` 하위 순수 함수들(`format.ts` 219줄, `areaScope.ts`, `composition.ts`, `rentArea.ts`,
`verdict.ts`)은 부작용이 없어 **테스트하기 가장 쉬운 코드**인데 커버리지가 없다.
백엔드는 `./gradlew test`, AI는 `make eval`이 있어 프론트만 비어 있다.
(계약 리뷰 문서는 `lib/composition.ts`가 **API 계약이 참조 구현으로 지목한 파일**이라는 점을 들어
이를 P1로 올렸다 — 그 근거가 타당하므로 Action Items에서 P1로 승격한다.)

## m-8. `Suspense` fallback에만 인라인 스타일이 있다
`App.tsx`. 프로젝트 전체가 CSS Modules로 일관돼 있는데 이 한 곳만 `style={{…}}`이다.
요소 하나를 위해 모듈 파일을 만드는 것도 과하므로 판단이 필요하다. (P2)

---

# Good Points

프론트엔드에서 **잘 설계된 부분**을 명시한다. 아래는 리뷰 중 되돌리면 안 된다고 판단한 결정들이다.

1. **`api/fallback.ts`의 비가역 플래그.** 목 폴백을 조용히 숨기지 않고 화면에 고지하되,
   **한 번 켜지면 끄지 않는다.** "뒤 호출이 성공해도 이미 화면에 목 수치가 섞여 있을 수 있다"는
   판단이 정확하다. `useSyncExternalStore`로 구현해 React 외부 상태를 올바르게 구독한다.

2. **`Recommend.tsx`의 `loading` / `refreshing` 분리.** 슬라이더 재조회 중 지도·목록을 문구로
   갈아 끼우지 않고 `opacity: .55 + pointer-events: none`으로만 처리한다. 마커 재생성 비용을
   피하면서 "갱신 중"임도 전달한다. 응답이 1.4~1.8초인 API에서 이 선택은 체감을 좌우한다.

3. **`KakaoMap`의 선택 강조 최적화.** 선택이 바뀔 때 마커 100개를 전부 다시 칠하지 않고
   `prevSelectedRef`로 **바뀐 둘만** 재도색한다. SVG data URI 생성 비용을 감안한 정확한 판단이다.
   `onSelect`·`selectedCode`를 ref로 읽어 마커 재생성을 막은 것도 같은 맥락이다.

4. **`buildOverlay`의 DOM 조립.** 말풍선을 HTML 문자열이 아니라 `createElement` + `textContent`로
   만든다. 상권명이 마크업으로 해석될 여지가 원천 차단된다. 전 코드베이스에
   `dangerouslySetInnerHTML` / `innerHTML` / `eval` / `localStorage` 토큰 저장이 **모두 0건**이다.

5. **`global.css`의 전역 `prefers-reduced-motion`.** "transition을 쓰는 모듈 8개가 각자 챙기는
   방식은 반드시 빠진다"는 진단이 맞다. `0`이 아니라 `0.01ms`를 쓴 이유(`transitionend` 미발화
   회피)까지 주석에 남아 있다.

6. **`Modal`이 네이티브 `<dialog>.showModal()`을 쓴다.** 포커스 트랩·Esc·배경 inert를 직접
   구현하지 않고 브라우저에 맡겼다. 접근성 버그가 가장 많이 나오는 컴포넌트에서 옳은 선택이다.

7. **`useAreaScope`의 `requestIdleCallback` 지연 + Safari 폴백.** 971 kB JSON을 첫 페인트와
   경쟁시키지 않고, `typeof` 검사로 폴백을 가른 것까지 정확하다.

8. **`lib/` 순수 함수 분리.** `format.ts`의 `stationLabel`/`lineLabel`처럼 실데이터의 지저분함
   ("잠실(송파구청)역", "8호선호선")을 한 곳에서 흡수한다. 화면 코드에 조건문이 번지지 않는다.

9. **디자인 토큰 체계.** Primitives ↔ Semantic 2단 구조, `--pill-pad-*` 3종으로 칩 여백 10종
   난립을 정리한 이력이 주석에 남아 있다. 하드코딩 색상은 `SCOPE_STYLE`(카카오맵 SDK가 CSS 변수를
   못 읽는 자리)뿐이며 그 이유가 명시돼 있다.

10. **주석이 결정의 근거를 남긴다.** `FIT_PADDING`(왜 190→70인지), `OVERLAY_HEIGHT_PX`(왜 고정
    비율이 아닌 환산인지), `Explore`의 ref 회피(왜 의존성에서 뺐는지) — 전부 되돌리면 재발할
    버그에 근거가 붙어 있다. 이 수준의 주석 규율은 드물다.

11. **컴플라이언스가 코드에 내장돼 있다.** "추천"·"승인"·"조달 가능" 금지어 회피가 상수
    (`PRESETS`의 "빠른 선택", `VERDICT_LABEL`)와 주석으로 강제된다. `RiskReviewPanel`이
    `skipped=true`일 때 문장을 숨기지 않고 **출처 라벨만 분기**하는 처리는 특히 정확하다.

12. **`ScrollToTop`의 `hash` 처리.** GNB를 라우터 링크로 바꾸면서 사라진 프래그먼트 이동을
    한 곳에서 되살렸고, `search`는 일부러 의존성에서 뺐다(`?demo=1`로 스크롤이 튀지 않게).

13. **`nginx.conf`가 SPA 함정을 피한다.** `/geo/`를 `location /` 위에 두고 `try_files $uri =404`로
    처리해, SPA 폴백이 파일 누락을 `index.html` 200으로 감추는 문제를 막았다. SSE를 위한
    `proxy_buffering off`도 정확하다.

---

# UX Improvements

수정으로 반영된 것 외에, 추가로 검토할 만한 항목이다.

1. **`/map` 첫 진입에 스켈레톤이 없다.** 현재는 "추천 상권을 불러오는 중…" 텍스트 한 줄로 지도와
   목록 전체가 대체된다. 응답이 1.4~1.8초이므로 지도 영역·카드 3장 형태의 스켈레톤이 체감을
   개선한다. (재조회 중 stale 처리는 이미 훌륭하므로 **첫 진입만** 해당)

2. **목 폴백 배너에 회복 경로가 없다.** M-14 참조.

3. **`/explore` 첫 진입에 뼈대가 없다.** `ordered.length === 0 && loading`이면 문구 한 줄이다.
   프론티어 차트 영역은 "탐색이 끝나면 …"으로 잘 처리돼 있으나 시나리오 목록은 비어 있다.
   `ScenarioRow` 형태의 스켈레톤 2행 권장.

4. **성공 피드백이 약하다.** 탐색에서 "적용하기"를 누르면 버튼이 "적용 중"으로 바뀌고 상단
   요약이 갱신되지만, 변화가 화면 두 곳에 흩어져 있어 놓치기 쉽다. 요약 스트립의 예산 숫자에
   짧은 강조 전환(`prefers-reduced-motion` 존중)을 주면 인과가 붙는다.

5. **"더 보기"가 스크롤 컨테이너 안에 있다.** `Recommend`의 `.cards`는 `max-height: 620px`
   내부 스크롤이다. 1,059곳 중 50곳씩 늘리려면 매번 컨테이너 끝까지 스크롤해야 한다.
   → 무한 스크롤(`IntersectionObserver`) 또는 헤더에 총계 대비 진행 표시.

6. **GNB 로고에 홈 링크가 없다.** `GNBHeader`의 로고는 `<img>`일 뿐이라 랜딩으로 돌아갈
   보편적 경로가 없다. `<Link to="/">`로 감싸는 것이 일반적 기대에 맞는다.

7. **`ParsedResult`가 세션 ID를 그대로 노출한다.** `세션 {sessionId}`. 디버깅에는 유용하나
   심사 화면에서는 의미 없는 문자열이다. 접어 두거나 개발 모드에서만 노출.

---

# Performance Improvements

## 적용됨
- **라우트 코드 스플리팅** — 초기 JS 106.25 → 69.61 kB gzip (−34%), CSS 12.42 → 4.52 kB (−64%)
- **랜딩 캡처 지연 로드** — 쇼케이스 217 kB를 첫 화면 대역에서 분리, 히어로만 `fetchPriority="high"`

## 미적용 (권장 순)
1. **`hero-preview.png` 595 kB → WebP/AVIF 변환.** 동일 화질에서 70~85% 절감 기대(실측 필요).
   단일 항목으로는 가장 큰 이득이다. 자산 재생성이 필요해 리뷰 범위 밖으로 뒀다.
2. **`area-scope.v1.json` 971 kB.** nginx gzip(약 202 kB)과 `Cache-Control: max-age=86400`이
   이미 적용돼 있고 idle 로드라 급하지 않다. 더 줄이려면 좌표 정밀도를 소수 5자리로 낮추거나
   (약 1m 오차 — 현재 표시 오차가 이미 8m), TopoJSON으로 전환한다.
3. **`lucide-react` 트리셰이킹 확인.** 현재 22종 아이콘을 named import 중이라 ESM 트리셰이킹이
   동작할 것으로 보이나, 207 kB 벤더 청크 내역을 `rollup-plugin-visualizer`로 한 번 확인할 가치가 있다.
4. **`Recommend`의 카드 목록 가상화.** 1,059곳 중 200건 이상 펼치면 DOM 노드가 급증한다.
   `LIST_PAGE=50` 페이지네이션이 완충하고 있으나 m-2(필터 변경 시 리셋)를 먼저 고치는 편이 싸다.
5. **`AreaCard` 메모이제이션.** 슬라이더 조작마다 `areas` 배열이 새로 만들어져 카드 50장이
   전부 리렌더된다. `React.memo` + `area` 참조 안정성으로 억제 가능. 단 `scopeNote`/`overlapNote`가
   선택 상태에 의존하므로 prop 설계를 함께 봐야 한다.

---

# Action Items

### P0
- [x] `/scenarios` 세션 가드 추가 — 목 폴백 플래그 영구 점등 차단 (C-1)

### P1
- [x] `POST /budget` 요청 시퀀싱 — `/map` 슬라이더 (M-1)
- [x] `POST /budget` 요청 시퀀싱 — `/budget` 화면 (M-2)
- [x] 화면 3 CTA를 세션 예산 확정 전까지 비활성 + 상태 문구 (M-3)
- [x] 시나리오 SSE 종료 상태 + 빈 결과 복귀 경로 (M-4)
- [x] 진단 폼 라벨/컨트롤 연결 · `aria-invalid` · `aria-describedby` (M-5)
- [x] 검증 실패 시 에러 요약으로 포커스 이동 (M-6)
- [x] 고지 문구 5곳 명도 대비 AA 통과 (M-7 부분)
- [x] 라우트 코드 스플리팅 (M-8)
- [x] 랜딩 캡처 지연 로드 + 우선순위 (M-9 부분)
- [x] 진단 폼 · 추천 요약 반응형 (M-10)
- [x] 도달하지 않은 시나리오 카드 `disabled` (M-11)
- [x] `path="*"` 폴백 라우트 (M-12)
- [ ] `lib/composition.ts` 단위 테스트 — **API 계약이 참조 구현으로 지목한 파일** (m-7 승격)
- [ ] `--color-text-tertiary` 대비 개선 — **Figma Foundations 갱신 선행 필요** (M-7 잔여)
- [ ] `hero-preview.png` WebP/AVIF 변환 (M-9 잔여)
- [ ] 새로고침 시 세션 소실 안내 또는 `sessionStorage` 영속화 — **설계 결정 필요** (M-13)
- [ ] 목 폴백 배너에 "다시 시도" 경로 추가 (M-14)

### P2
- [ ] `getRecommend` / `postCheckArea`에 `AbortSignal` 전달 (m-1)
- [ ] 정렬·필터·데이터 변경 시 `listLimit` 초기화 (m-2)
- [ ] 탐색 `applying`을 `insight_id` 단위로 좁히기 (m-3)
- [ ] 지도 컨테이너에 목록 대체 경로 `aria-describedby` 연결 (m-4)
- [ ] `AreaCard` 본문 버튼에 짧은 `aria-label` 부여 (m-5)
- [ ] `RentSource.district`를 `string | null`로 정정 — 계약 문서 확인 (m-6)
- [ ] `lib/` 나머지 순수 함수 단위 테스트 (vitest) (m-7)
- [ ] `Suspense` fallback 인라인 스타일 정리 (m-8)
- [ ] `/map`·`/explore` 첫 진입 스켈레톤 (UX-1, UX-3)
- [ ] GNB 로고에 `/` 링크 (UX-6)
- [ ] `ParsedResult`의 세션 ID 노출 정리 (UX-7)

---

# UX Score

**78 / 100** (수정 전 추정 63)

| 항목 | 점수 | 비고 |
|---|---|---|
| 흐름 설계 | 17/20 | 4단계 진행·되돌리기 경로가 명확. 새로고침 회복만 결여 (M-13) |
| 로딩·상태 표현 | 15/20 | stale 처리가 탁월. 첫 진입 스켈레톤 부재 |
| 에러·빈 상태 | 13/20 | 빈 상태 문구는 우수. 재시도 경로 없음 (M-14) |
| 접근성 | 15/20 | 수정으로 크게 개선. 대비 잔여·지도 키보드 미지원 |
| 반응형 | 12/20 | 수정으로 붕괴는 해소. 데스크톱 1440 최적화 기조는 유지 |

감점의 성격이 다르다 — 결함이 흩어져 있는 것이 아니라 **회복 경로(새로고침·재시도) 한 축**에
몰려 있다. 그 축을 채우면 85 이상이 가능하다.

# Performance Score

**79 / 100** (수정 전 추정 68)

| 항목 | 점수 | 비고 |
|---|---|---|
| 번들 | 17/20 | 코드 스플리팅 적용. 벤더 207 kB 내역 미확인 |
| 이미지 | 12/20 | 지연 로드 적용. 595 kB PNG 잔존 |
| 렌더링 | 19/20 | 마커 재도색 최소화·ref 회피가 정교하다 |
| 네트워크 | 16/20 | gzip·캐시 헤더·idle 로드 양호. 요청 취소 미비 (m-1) |
| 런타임 상태 | 15/20 | 슬라이더 조작 시 카드 50장 전체 리렌더 |

# Maintainability Score

**82 / 100**

| 항목 | 점수 | 비고 |
|---|---|---|
| 구조·분리 | 18/20 | screens / components / lib / api / store 경계가 선명 |
| 타입 안전성 | 15/20 | `any` 0건. `district` nullable 미반영 (m-6) |
| 스타일 체계 | 18/20 | CSS Modules + 2단 토큰. 하드코딩은 근거 있는 1곳뿐 |
| 문서화 | 19/20 | **최상위.** 주석이 "무엇"이 아니라 "왜"를 남긴다 |
| 테스트 | 6/20 | **0건.** 순수 함수조차 커버리지 없음 (m-7) |
| 중복 | 6/10 | `formatAmount` 대신 `toLocaleString('ko-KR') + '만원'` 인라인이 여러 곳 |

테스트가 유일한 큰 구멍이다. 나머지는 프로덕션 수준에 근접한다.

---

# Final Decision

## Conditional Approve

**승인 근거**
- P0 1건과 P1 12건 중 11건을 이번 리뷰에서 수정했고, `eslint` · `tsc --noEmit` · `vite build`가
  모두 통과한다. 수정한 모든 모듈이 dev 서버에서 정상 트랜스폼됨을 확인했다.
- 아키텍처·상태 관리·렌더링 최적화·문서화는 이미 프로덕션 수준이다. 구조적 재작업이 필요한
  결함은 발견되지 않았다.
- 발견된 결함은 전부 **국소적**이었다. 세션 가드 누락, 요청 시퀀싱 부재, 라벨 연결 누락 —
  설계가 틀린 것이 아니라 이미 옳게 정한 규칙이 한두 지점에서 빠진 형태였다.

**조건 (병합 전 처리 권장)**
1. **`lib/composition.ts` 테스트** — API 계약이 참조 구현으로 지목한 파일이 무검증 상태다.
   순수 함수라 테스트 비용이 가장 낮은 곳이기도 하다.
2. **M-13 (새로고침 세션 소실)** — 설계 결정이 필요하다. 최소한 안내 문구라도 붙여야 심사 중
   사고를 막는다. 리뷰어 권장안은 2안(안내만).
3. **M-7 잔여 (`--color-text-tertiary` 대비)** — Figma Foundations 갱신 여부를 정해야 한다.
   `tokens.css`가 임의 변경을 금지하므로 리뷰어가 단독 처리하지 않았다. 고지 문구 5곳은 이미
   해소했으므로 심사 리스크는 낮아졌다.
4. **M-9 잔여 (595 kB PNG)** — 자산 변환. 첫인상에 직결되므로 마감 전 처리 가치가 높다.

**Reject하지 않은 이유**
발견된 P0가 실데이터를 조작하거나 잘못된 금액을 계산하는 종류가 아니었다. 프로젝트의 절대 불변
원칙(모든 숫자는 결정적 계산이 만든다 / 서빙 경로에 ML 없음 / 용어 컴플라이언스 / 좌표·단위)은
프론트엔드 전 범위에서 **위반이 발견되지 않았다.** 오히려 화면이 없는 수치를 지어내지 않도록
`null` 처리를 일관되게 하고 있으며(`'—'` 표기, `budgetValue = null`이면 줄 자체를 비움),
이는 이 프로젝트에서 가장 중요한 요구사항이다.

---

## 변경 파일 (이번 리뷰에서 수정)

```
frontend/src/App.tsx                              라우트 스플리팅 · Suspense · * 폴백
frontend/src/components/Field.tsx                 라벨/컨트롤 자동 연결 · aria-*
frontend/src/components/SegmentedChoice.tsx       id · aria-labelledby · aria-describedby 수용
frontend/src/components/ScenarioCard.tsx          disabled prop
frontend/src/components/ScenarioCard.module.css   :disabled 스타일
frontend/src/components/ScenarioRow.module.css    고지 문구 대비
frontend/src/components/landing/BrowserMock.tsx   priority prop · lazy/decoding/fetchPriority
frontend/src/screens/Diagnose.tsx                 에러 요약 포커스 · 지역 셀렉트 aria-label
frontend/src/screens/Diagnose.module.css          반응형 폼 행 · 포커스 링
frontend/src/screens/Scenario.tsx                 세션 가드 · streamDone · 빈 결과 복귀
frontend/src/screens/Scenario.module.css          빈 결과 액션 · 고지 문구 대비
frontend/src/screens/Budget.tsx                   요청 시퀀싱 · 세션 가드 · CTA 가드 · dead code
frontend/src/screens/Budget.module.css            고지 문구 대비
frontend/src/screens/Recommend.tsx                요청 시퀀싱
frontend/src/screens/Recommend.module.css         반응형 요약 · 고지 문구 대비
frontend/src/screens/Explore.module.css           고지 문구 대비
frontend/src/screens/landing/Hero.tsx             히어로 이미지 priority
```

검증: `npm run lint` ✅ · `npm run build`(`tsc --noEmit` 포함) ✅ · vite dev 트랜스폼 8/8 ✅

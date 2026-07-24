# API 계약 (스펙 §6 · exploration spec §4)

**상태: 확정 (2026-07-20 리더 확정 · 2026-07-21 FE 검토 의견 반영 —**
**D3 CP1에서 최종 동결. 이후 변경은 CONTRIBUTING §6 절차(3인 합의 + 본 문서 수정 PR)로만.)**

초안 공백 5건은 2026-07-20 확정되어 본문에 반영됨 — 상세 근거는 [DECISIONS.md](DECISIONS.md):
①판정 용어 "유의"(enum `CAUTION`) ②금액 만원 단위 ③scenarios SSE 이벤트 스키마
④recommend `risk_review` ⑤diagnose `parse_source`.

FE 검토 의견 6건은 2026-07-21 반영됨 (5건 수용 · 1건 스코프 외) — 근거는 DECISIONS.md §8~§11.

공통 규약
- 좌표: **WGS84** (lat, lng)
- 금액: **만원 단위 정수**
- 금융상품 응답에는 `source`(org·url·collected) 필수, 화면 표기용 `data_as_of`(기준일) 필수
- **금리 표기 (★2026-07-24 변경, 3인 합의 대상)**: 정책자금 상당수가 "정책자금 기준금리+가산"
  변동금리라 고정 숫자로 담을 수 없다. 따라서 금융상품의 `rate`(연 %)는 **nullable**이며,
  `rate_type`(`fixed`|`variable`)와 `rate_note`(변동금리 원문 표현, 예 "정책자금 기준금리+0.6%p")를
  동반한다. 화면은 `rate`가 있으면 숫자, 없으면(`variable`) `rate_note`를 그대로 표기한다
  (기준금리 실값은 서비스가 지어내지 않는다 — 스펙 §0-1). BE는 `rate` NULL을 허용해 파싱한다.
- 판정 enum: `FIT`(적합) / `CONDITIONAL`(조건부 적합) / `CAUTION`(유의) / `OUT_OF_SCOPE`(범위 외)
  — 화면 문구는 용어 컴플라이언스 표(CLAUDE.md) 준수
- 세션·버전: 슬라이더 변경마다 프론트가 `v`(version) 증가시켜 전달. 서버는 세션 최신 version이
  아니면 SSE 이벤트 송출 전 폐기 (exploration spec §5)
- 값이 없는 필드는 응답에서 **생략**된다 (`non_null` 직렬화). 프론트는 `undefined` 허용으로 파싱.

## 엔드포인트

### 1) `POST /api/diagnose`
진단 폼+자연어 파싱 (AI 역할 [1]).

```jsonc
// req
{ "form": { "age": 32, "capital": 5000,
            "is_existing_business": false,   // 재창업/기존 사업자 여부 — 자격 정규칙(예비창업자 한정 상품)
            "collateral_available": true,    // 담보 제공 가능 — 현재 판정 미사용, BE-04 조달 검증 예약 필드
            "monthly_investable": 250,       // 월 투자 가능액(만원) — BE-04 상환 여력 상한
            "industry": "cafe", "region_hint": "서울 마포구" },
  "free_text": "권리금이 제일 걱정..." }
// res
{ "session_id": "…", "parsed_profile": { …, "concerns": ["premium"],
                                          "parse_source": "llm" } }   // "llm" | "form_only"(LLM 장애 폴백)
```

- **숫자는 반드시 `form`으로 보낸다.** 금액을 `free_text`에만 담으면 LLM 파싱이 수치를
  생성하는 통로가 되어 불변 원칙 §0-1("모든 숫자는 결정적 계산이 만든다")과 충돌한다.
- `parsed_profile`은 `form`의 전 필드를 그대로 반향하며 `concerns`·`parse_source`를 덧붙인다.
- `region_hint`는 "시/도 + 구/군"을 결합한 단일 문자열 (프론트 조립).

### 2) `GET /api/scenarios/{sid}` (SSE)
조달 시나리오 카드 2장 (보수/적극).
이벤트 순서: `scenario`(카드 1장씩, 2회) → `done` — 하트비트 공통 규약 적용.

```jsonc
// scenario (1장씩)
{ "label": "보수",
  "budget": 6500,          // 슬라이더 초기 선택값 (= budget_max, 한도 전액 활용 가정)
  "budget_min": 5000,      // 심사와 무관한 확정 재원 합 (자기자본 등)
  "budget_max": 6500,      // budget_min + Σ 상품 한도(amount_max)
  "composition": [ { "type": "equity", "amount_min": 5000, "amount_max": 5000 },
                   { "type": "guarantee", "amount_min": 0, "amount_max": 1500 } ],
  "products": [ { "name": "…", "amount_max": 1500, "rate": 2.5,
                  "data_as_of": "2026-Q1", "source": {…}, "source_quote": null } ] }
// done
{ "scenario_count": 2 }
```

- `budget_min`~`budget_max`가 화면 2 예산 슬라이더의 가동 범위다. 상한이 곧 승인 금액이라는
  뜻이 아니며, 화면에는 "한도·승인은 기관 심사 사항" 고지가 동반되어야 한다.
- `composition[].type`: `equity`(자기자본) / `guarantee`(보증) / `policy_loan`(정책자금).

### 3) `POST /api/budget/{sid}`
예산 확정(B₀ 기록) + **확정 예산 기준 프리뷰**.

```jsonc
// req  — 확정값이므로 단일 금액
{ "confirmed_budget": 8000, "composition": [ { "type": "equity", "amount": 5000 }, … ] }
// res
{ "confirmed_budget": 8000,
  "composition": [ … ],
  "preview": { "area_count": 3,             // 진입 후보 수 N_entry(B) — expl §2-1 계단 함수
               "rent_range": [198, 456],    // 진입 후보의 환산임대료 [최소, 최대] (만원/월)
               "floating_range": [22800, 38200] } }   // 진입 후보의 일평균 유동인구 [최소, 최대]
```

- **프리뷰 주체는 `/budget`이다** (FE 제안 채택). 화면 2 시점에는 세션 B₀가 아직 미확정이라
  `/recommend`가 임의 예산의 프리뷰를 만들 수 없다.
- 화면 2 슬라이더는 debounce 후 `POST /budget`을 재호출한다 — B₀는 덮어쓰기이며 마지막 값이
  확정값이다. 화면 3 슬라이더는 `POST /budget` → `GET /recommend?v=`·`GET /explore?v=`
  (동일 version 공유, 스펙 §7 · expl §5).
- `/recommend`에 `?budget=` 쿼리는 두지 않는다 — 예산의 진실 원천을 세션 하나로 유지한다.
- 진입 후보가 0곳이면 `area_count: 0`이고 두 range 필드는 생략된다.

### 4) `GET /api/recommend/{sid}`
```jsonc
{ "data_as_of": "2026-Q1",
  "total_count": 3,                                  // 필터 통과 후보 총수 (현재 areas 길이와 동일)
  "summary": { "avg_rent": 309, "avg_sales": 2100 }, // 후보군 평균 환산임대료·월 추정매출(만원)
  "areas": [ { "area_code": "…", "name": "망원역 상권", "lat": 0, "lng": 0,
    "verdict": "FIT",
    "score": 75,                                     // 종합점수 0~100 = round(Σ wᵢ·축ᵢ × 100)
    "breakdown": { "w1": …, "w2": …, "w3": …, "w4": …, "w5": … },
    "cost": { "ex_premium": [5800, 7200], "incl_premium": [7400, 9100] },   // 구간·추정치
    "monthly_rent": 198, "est_sales": 1800, "daily_floating": 24500,
    "burden_ratio": 0.11,                            // = monthly_rent ÷ est_sales (스펙 §4-2)
    "reason_text": "…",
    "rent_source": { "org": "REB", "district": "…", "fallback": false },
    "transit": { "station": "망원", "line": "6", "distance_m": 320,
                 "daily_riders": 21000, "fallback": false } } ],
  "risk_review": { "objection_text": "…", "applied": true,
                   "skipped": false } }   // FE "왜?(검증 의견 n건)" 패널 원천 — 장애 시 skipped=true("검증 생략" 플래그)
```

- `areas`는 `score` 내림차순 정렬로 반환된다.
- **정렬·필터 쿼리 파라미터는 두지 않는다.** `score`·`monthly_rent`·`est_sales`·
  `daily_floating`이 전부 실려 있어 프론트가 왕복 0회로 처리할 수 있고, 그편이 스펙 §7의
  "<100ms 재계산 체감" 목표에 유리하다. 후보 풀이 커져 페이징이 필요해지는 시점(BE-02
  실데이터 전환)에 재검토한다.
- 점수 등급 구간(90+/80/70/60) 매핑은 프론트 소관.

### 5) `GET /api/explore/{sid}?v={version}` (SSE)
이벤트 순서: `plan` → `insight`(템플릿 즉시, 1건씩) → `refine`(LLM 교체, 선택적) → `done`

```jsonc
// plan
{ "axes": ["A1", "A4"],
  "axis_labels": { "A1": "예산", "A4": "권리금 조건" },   // 화면 표기용 — 서버가 송출(용어 컴플라이언스)
  "rationale": "대화 맥락 기반: 권리금 축 우선 검토" }
// insight (T1 예) — insight_id는 refine 이벤트의 교체 대상 키 (BE-01 구현 중 추가)
{ "insight_id": "i-1", "type": "T1", "headline": "…",
  "delta": { "n_entry_before": 3, "n_entry_after": 11, "n_sustain_after": 7, "score_delta": … },
  "gap_amount": 1320, "marginal_payment": 28,
  "funding": { "name": "…", "amount_max": …, "rate": 2.5, "term_assumed": 60,
               "status": "open", "notice_date": "…", "exclusive_group": "…",
               "source": { "org": "…", "url": "…", "collected": "…" },
               "source_quote": null },        // RAG 구현 전 null 허용 (P1-①)
  "disclaimer": true }
// refine
{ "insight_id": "…", "headline": "…" }
// done
{ "scenarios_explored": 6, "frontier_points": [ [6480, 3], [7800, 5], … ],
  "current_budget": 8000 }                    // 프론티어 차트의 "현재 예산" 마커 좌표
```

탐색 축 코드 (expl §1 — 4종 고정, 추가 금지):

| 코드 | 라벨 | 내용 |
|---|---|---|
| `A1` | 예산 | 상·하향 통합 해석적 프런티어. 계획과 무관하게 항상 실행 |
| `A2` | 지역 확장 | 인접 자치구별 프런티어 재계산 |
| `A3` | 업종 스왑 | 타 업종 프런티어 재계산 |
| `A4` | 권리금 조건 | `cost_ex_premium` 기준 프런티어 (A1의 부산물) |

- 라벨은 `plan.axis_labels`로 매 이벤트에 실려 오므로 프론트는 하드코딩 사전을 두지 않는다
  (화면 문구는 용어 컴플라이언스 대상이라 서버가 단일 통제한다).

### 6) `POST /api/check-area/{sid}`
역방향 판정.

```jsonc
// req
{ "area_code": "…" }
// res
{ "verdict": "CONDITIONAL", "gap_amount": 1320,
  "matching_products": [ { "name": "…", "amount_max": 3000, "rate": 2.5,
      "data_as_of": "2026-Q1", "source": {…},
      "source_quote": { "text": "만 39세 이하 예비창업자로서…", "org": "소진공",
                        "doc": "○○공고", "date": "2026-06" } } ],
  "risk_review": { "objection_text": "…", "applied": true } }
```

## 시스템 계약

- `GET /api/health` → `{ "status": "ok" }` (compose·CI 스모크용, 이미 구현)
- 오류: HTTP 4xx/5xx + `{ "error": { "code", "message" } }`
- SSE 하트비트: 15s 간격 comment 프레임 (프록시 타임아웃 방지)

## 스코프 외 (엔드포인트 없음)

결과 저장·PDF 상담 리포트·공유 링크·저장 목록은 **스펙 §0-2의 P2(로드맵)** 항목이다.
예선 스코프에 포함하지 않으며 엔드포인트를 신설하지 않는다 — 프론트도 해당 화면을 만들지 않는다.

## 변경 이력

| 일자 | 변경 | 합의 |
|---|---|---|
| D0 | 초안 작성 (스펙 §6 전사) | — |
| D0 (7/20) | 초안 공백 5건 식별 (용어·단위·scenarios SSE·risk_review·parse_source) | 초안 단계 |
| D0 (7/20) | 5건 전부 확정 반영: 판정 enum `CAUTION`(유의), 만원 단위(스펙 §6 정정), scenarios SSE 스키마, recommend `risk_review`, `parse_source` — 근거 DECISIONS.md | 리더 확정 (D3 CP1 최종 동결) |
| D2 (7/21) | **FE 검토 의견 6건 반영** — ①diagnose 폼 3필드 ②budget 프리뷰 응답 ③recommend `score`·`total_count`·`summary`·원자재 3종 ④scenarios 예산 범위·상품 `amount_max`/`rate`/`data_as_of` ⑤explore `axis_labels`·`current_budget` ⑥결과 저장 API = 스코프 외 회신. 근거 DECISIONS.md §8~§11 | FE 제안 → 리더 반영 (D3 CP1 확인 대상) |
| D6 (7/24) | **금융상품 `rate` nullable + `rate_type`·`rate_note` 추가** (AI 제안) — 정책자금 변동금리("기준금리+가산")를 고정 숫자로 조작하지 않고 원문 그대로 기록. `finance_product` DDL·`20_finance.sql` 반영, BE는 `rate` NULL 허용 파싱 필요. 근거 assumptions #28 | ⚠️ **AI 발의 — BE·리더 3인 합의·ratify 대기** (변동금리를 표현 못 하던 계약 공백 보완) |

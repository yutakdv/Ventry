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
- **금리 표기 (★2026-07-24 변경 · 2026-07-25 ratify — 3인 합의 완료, BE·FE 조건 반영)**: 정책자금
  상당수가 "정책자금 기준금리+가산" 변동금리라 고정 숫자로 담을 수 없다. 따라서 금융상품의
  `rate`(연 %)는 **nullable**이며, `rate_type`(`fixed`|`variable`)·`rate_note`(원문 표현, 예
  "정책자금 기준금리+0.6%p")를 동반한다. 계약 규칙(FE 분기 안정성):
  - `rate_type`은 **항상 존재**한다(DDL `NOT NULL DEFAULT 'fixed'`). FE 분기 키는 `rate_type`이며
    `fixed`/`variable` 라벨 판단에 쓴다. non_null 직렬화라 `rate` NULL은 `null`이 아니라 **키 생략**으로
    도착하므로, prose의 "rate 있으면/없으면"이 아니라 위 필드로 판단한다.
  - **표시 규칙**: `rate`가 실려 오면 "연 {rate}%"를 표기, 생략되면 `rate_note`를 **그대로** 표기한다
    (기준금리 실값은 서비스가 지어내지 않는다 — 스펙 §0-1).
  - **`rate_note`·폴백 문구는 서버가 단일 통제한다.** `rate`가 생략된 모든 상품에 대해 서버가
    `rate_note`를 항상 채우며(원문 표현, 원문에 금리 표현이 없으면 표준 폴백 문구), FE는 금리 표기용
    하드코딩 사전을 두지 않는다(용어 컴플라이언스 — `axis_labels` 선례와 동일). BE는 `rate` NULL 허용 파싱.
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
                                          "parse_source": "form_only" } }
// ★2026-07-26 — 현재 서버는 **항상 `"form_only"`** 를 보낸다. 자유 텍스트 처리는 키워드 매칭이고
// LLM 실파싱이 없기 때문이다(BE-07 지적, 가정 #63). `"llm"` 은 실파싱을 붙인 뒤에야 나간다.
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
  "budget": 6200,          // 슬라이더 초기 선택값 = budget_min + 필요분 (★2026-07-26 변경, 구: budget_max)
  "budget_min": 5000,      // 심사와 무관한 확정 재원 합 (자기자본 등)
  "budget_max": 6500,      // budget_min + Σ 상품 한도(amount_max)
  "composition": [ { "type": "equity", "amount_min": 5000, "amount_max": 5000 },
                   { "type": "guarantee", "amount_min": 0, "amount_max": 1500 } ],
  "products": [ { "name": "…", "amount_max": 1500,
                  "rate": 2.5, "rate_type": "fixed",   // 변동금리면 rate 생략 + "rate_type":"variable","rate_note":"정책자금 기준금리+0.6%p"
                  "data_as_of": "2026-Q1", "source": {…},
                  "source_quote": { "text": "…", "org": "…", "doc": "…", "date": "…" } } ] }
// done
{ "scenario_count": 2 }
```

- `budget_min`~`budget_max`가 화면 2 예산 슬라이더의 가동 범위다. 상한이 곧 승인 금액이라는
  뜻이 아니며, 화면에는 "한도·승인은 기관 심사 사항" 고지가 동반되어야 한다.
- **★2026-07-26 변경 — `budget` 초기값 의미**: `budget_max`(한도 전액)가 아니라
  **`budget_min` + 필요분**이다. 필요분 = 해당 업종 후보 상권의 진입 비용 중앙값 − 자기자본이며,
  보수 카드는 권리금 제외·적극 카드는 권리금 포함 중앙값을 쓴다. 상한을 기본 선택으로 두면
  가용 상품의 최소 한도가 필요분보다 큰 경우(실데이터에서 흔하다) **과잉 조달이 기본값**이
  되기 때문이다. 범위(`budget_min`~`budget_max`)는 그대로이므로 사용자가 상한까지 올릴 수 있다.
  근거: [DECISIONS §13-3](DECISIONS.md) · 이슈 #90
- **★2026-07-26 — `label`("보수"/"적극")은 금액의 대소가 아니라 '수단'이다**: 보수는 보증형만,
  적극은 전체 상품을 후보로 본다. 두 카드의 후보 풀이 달라 **적극의 예산이 보수보다 크다는
  보장이 없다**(실데이터 실측: 보수 상한 15,000 · 적극 상한 10,000). 화면은 두 카드를 크기순으로
  전제하지 말 것 — "적극이 더 큰 예산"이라는 문구·정렬·강조는 사실과 어긋날 수 있다.
- `composition[].type`: `equity`(자기자본) / `guarantee`(보증) / `policy_loan`(정책자금).
  판별은 상품명의 '보증' 우선, 그다음 기관명이다 (BE `ProductType`).

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
  "gap_amount": 1320, "marginal_payment": 28,   // 변동금리 근거면 생략 → "marginal_payment_note": "…"(서버 송출)로 대체
  "funding": { "name": "…", "amount_max": …, "rate": 2.5, "rate_type": "fixed", "term_assumed": 60,
               // 변동금리(rate 생략·"rate_type":"variable")면 "rate_note" 동반, 상위 marginal_payment 생략
               "status": "open", "notice_date": "…", "exclusive_group": "…",
               "source": { "org": "…", "url": "…", "collected": "…" },
               "source_quote": { "text": "…", "org": "…", "doc": "…", "date": "…" } },
                                             // ★2026-07-26 결선 (#18). 청크 없으면 필드 생략
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
- **`marginal_payment`(월 상환액 증분, 만원)은 고정금리 근거일 때만 실린다.** 근거 상품이
  변동금리(`rate` 생략·`rate_type`=`variable`)면 BE-05는 월 상환액 m을 지어내지 않고(§0-1)
  `marginal_payment`를 **생략**하며, 대신 서버가 `marginal_payment_note`(대체 표기 문자열,
  용어 컴플라이언스)를 보낸다. FE는 해당 슬롯을 비우고 이 문구를 표시한다(하드코딩 금지).

### 6) `POST /api/check-area/{sid}`
역방향 판정.

```jsonc
// req
{ "area_code": "…" }
// res
{ "verdict": "CONDITIONAL", "gap_amount": 1320,
  "matching_products": [ { "name": "…", "amount_max": 3000,
      "rate": 2.5, "rate_type": "fixed",   // 변동금리면 rate 생략 + "rate_type":"variable","rate_note":"…"
      "data_as_of": "2026-Q1", "source": {…},
      "source_quote": { "text": "만 39세 이하 예비창업자로서…", "org": "소진공",
                        "doc": "○○공고", "date": "2026-06" } } ],
  "risk_review": { "objection_text": "…", "applied": true } }
```

- **★2026-07-26 — `source_quote`가 실제로 채워진다** (BE-06 ① 결선, 이슈 #18). `finance_product.doc_chunk_ref`
  → `finance_doc_chunk` **id 직접 조회**(LEFT JOIN 1회)이며 유사도 검색·벡터DB는 쓰지 않는다.
  적재 26건 전건이 청크를 보유해 세 경로(`check-area`·`scenarios`·`explore`) 모두 인용이 실린다.
  - `text`는 **공고문 원문 그대로**다 — 서버는 요약·재작성은 물론 **길이 자르기도 하지 않는다**.
    바이트 동일성이 §5-4("인용은 검색이지 생성이 아니다")의 유일한 증명 수단이기 때문이다.
  - **길이 편차가 크다: 19자 ~ 1,863자**(중앙 463). 화면 줄 수 제한은 표현 계층이 담당하고 전문은
    `source.url`로 연결한다. FE 조치 사항은 [HANDOFF_FRONTEND.md](HANDOFF_FRONTEND.md) 참고.
  - 청크가 없는 상품은 **필드 자체가 생략**된다(non_null 직렬화). 빈 문자열을 넣지 않는다.
- `matching_products`는 서버가 `amount_max` **내림차순**(동점 시 `product_id` 오름차순)으로 **고정 정렬**해
  반환한다. **금리 정렬은 하지 않는다** — `rate`가 생략된 상품(변동금리)의 순위를 프론트가 정하면 사실상
  순위 조작이 되므로 정렬 기준은 계약이 고정하고 FE는 재정렬하지 않는다. scenarios의 `products`도 동일.

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
| D8 (7/25) | **위 D6 변경 ratify 완료** (BE @Jongkwang131 · FE @youngjun1227, 이슈 #73) + **FE 조건 4건 반영**: ①JSON 예시 3곳 `rate_type`·`rate_note` ②분기 키 `rate_type`(항상 존재) 명문화 ③`rate_note`·폴백 문구 서버 단일 통제 ④변동금리 `marginal_payment` 생략+`marginal_payment_note`·`matching_products` `amount_max` desc 고정(금리 정렬 금지). BE-05는 변동금리 m 미산출. 데이터 검수 게이트 2건(F-002·F-010 `fixed`+`rate` NULL, F-010 `rate_note` 비금리)은 assumptions #30 등재 | ✅ **3인 합의 완료** (BE·FE ratify · AI 반영) |

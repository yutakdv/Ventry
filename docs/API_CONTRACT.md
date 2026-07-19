# API 계약 (스펙 §6 · exploration spec §4)

**상태: 확정 (2026-07-20 리더 확정 — D3 CP1에서 FE·AI 검토 후 최종 동결.**
**이후 변경은 CONTRIBUTING §6 절차(3인 합의 + 본 문서 수정 PR)로만.)**

초안 공백 5건은 2026-07-20 확정되어 본문에 반영됨 — 상세 근거는 [DECISIONS.md](DECISIONS.md):
①판정 용어 "유의"(enum `CAUTION`) ②금액 만원 단위 ③scenarios SSE 이벤트 스키마
④recommend `risk_review` ⑤diagnose `parse_source`.

공통 규약
- 좌표: **WGS84** (lat, lng)
- 금액: **만원 단위 정수**
- 금융상품 응답에는 `source`(org·url·collected) 필수, 화면 표기용 `data_as_of`(기준일) 필수
- 판정 enum: `FIT`(적합) / `CONDITIONAL`(조건부 적합) / `CAUTION`(유의) / `OUT_OF_SCOPE`(범위 외)
  — 화면 문구는 용어 컴플라이언스 표(CLAUDE.md) 준수
- 세션·버전: 슬라이더 변경마다 프론트가 `v`(version) 증가시켜 전달. 서버는 세션 최신 version이
  아니면 SSE 이벤트 송출 전 폐기 (exploration spec §5)

## 엔드포인트

### 1) `POST /api/diagnose`
진단 폼+자연어 파싱 (AI 역할 [1]).

```jsonc
// req
{ "form": { "age": 32, "capital": 5000, "industry": "cafe", "region_hint": "망원" },
  "free_text": "권리금이 제일 걱정..." }
// res
{ "session_id": "…", "parsed_profile": { …, "concerns": ["premium"],
                                          "parse_source": "llm" } }   // "llm" | "form_only"(LLM 장애 폴백)
```

### 2) `GET /api/scenarios/{sid}` (SSE)
조달 시나리오 카드 2장 (보수/적극).
이벤트 순서: `scenario`(카드 1장씩, 2회) → `done` — 하트비트 공통 규약 적용.

```jsonc
// scenario (1장씩)
{ "label": "보수", "budget": 6500, "composition": [ … ],
  "products": [ { "name": "…", "source": {…}, "source_quote": null } ] }
// done
{ "scenario_count": 2 }
```

### 3) `POST /api/budget/{sid}`
```jsonc
// req
{ "confirmed_budget": 8000, "composition": [ { "type": "equity", "amount": 5000 }, … ] }
```

### 4) `GET /api/recommend/{sid}`
```jsonc
{ "data_as_of": "2026-Q1",
  "areas": [ { "area_code": "…", "name": "망원역 상권", "lat": 0, "lng": 0,
    "verdict": "FIT",
    "breakdown": { "w1": …, "w2": …, "w3": …, "w4": …, "w5": … },
    "cost": { "ex_premium": [5800, 7200], "incl_premium": [7400, 9100] },   // 구간·추정치
    "burden_ratio": 0.11,
    "reason_text": "…",
    "rent_source": { "org": "REB", "district": "…", "fallback": false },
    "transit": { "station": "망원", "line": "6", "distance_m": 320,
                 "daily_riders": 21000, "fallback": false } } ],
  "risk_review": { "objection_text": "…", "applied": true,
                   "skipped": false } }   // FE "왜?(검증 의견 n건)" 패널 원천 — 장애 시 skipped=true("검증 생략" 플래그)
```

### 5) `GET /api/explore/{sid}?v={version}` (SSE)
이벤트 순서: `plan` → `insight`(템플릿 즉시, 1건씩) → `refine`(LLM 교체, 선택적) → `done`

```jsonc
// plan
{ "axes": ["A1", "A4"], "rationale": "대화 맥락 기반: 권리금 축 우선 검토" }
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
{ "scenarios_explored": 6, "frontier_points": [ [6480, 3], [7800, 5], … ] }
```

### 6) `POST /api/check-area/{sid}`
역방향 판정.

```jsonc
// req
{ "area_code": "…" }
// res
{ "verdict": "CONDITIONAL", "gap_amount": 1320,
  "matching_products": [ { "name": "…", "source": {…},
      "source_quote": { "text": "만 39세 이하 예비창업자로서…", "org": "소진공",
                        "doc": "○○공고", "date": "2026-06" } } ],
  "risk_review": { "objection_text": "…", "applied": true } }
```

## 시스템 계약

- `GET /api/health` → `{ "status": "ok" }` (compose·CI 스모크용, 이미 구현)
- 오류: HTTP 4xx/5xx + `{ "error": { "code", "message" } }`
- SSE 하트비트: 15s 간격 comment 프레임 (프록시 타임아웃 방지)

## 변경 이력

| 일자 | 변경 | 합의 |
|---|---|---|
| D0 | 초안 작성 (스펙 §6 전사) | — |
| D0 (7/20) | 초안 공백 5건 식별 (용어·단위·scenarios SSE·risk_review·parse_source) | 초안 단계 |
| D0 (7/20) | 5건 전부 확정 반영: 판정 enum `CAUTION`(유의), 만원 단위(스펙 §6 정정), scenarios SSE 스키마, recommend `risk_review`, `parse_source` — 근거 DECISIONS.md | 리더 확정 (D3 CP1 최종 동결) |

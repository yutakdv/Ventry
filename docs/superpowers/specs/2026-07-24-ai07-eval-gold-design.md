# AI-07 평가 하네스 골드셋 설계 (2026-07-24)

> 스펙 §12-1 「평가 하네스 — `make eval`」의 AI-07 소관분 설계.
> 상위 진실 원천은 `docs/specs/최종_스펙문서.md` §12. 본 문서는 그 하위 구현 설계다.

## 1. 목적

기획의 AI 품질 주장을 **심사위원이 재현 가능한 숫자**로 바꾼다. 단, 숫자는 **AI가 실제로
만든 산출물**에 대해서만 낸다 — LLM 추출·근거 청크·점수 설계. 결정적 백엔드 로직은
평가하지 않는다.

## 2. 스코프 결정 (AI는 AI만 테스트한다)

핵심 원칙: **AI 하네스는 AI 기능만 검증한다. 결정적 백엔드 로직은 BE 단위 + 통합 테스트가
검증한다.**

| §12-1 항목 | 성격 | AI-07 처리 |
|---|---|---|
| 추출 정확도 | gpt-4o LLM → 구조화 (순수 AI) | ✅ **본 하네스** (핵심) |
| 근거 충실도 | AI 배치가 만든 청크의 verbatim 충실도 (§5-4) | ✅ **본 하네스** (AI 청크 출력만) |
| 민감도 분석 | AI-05 점수 설계(w1~w5·θ) 강건성 | ✅ **본 하네스** (AI 데이터 재계산) |
| 자격 매칭 P/R | 결정적 `EligibilityFilter` (자바 순수 함수) | ❌ **BE 전적 소관** — `EligibilityFilterTest` 확장 + 통합 테스트. 부록 1 매칭 행은 BE 산출 |
| 검증 모델 지표 | LightGBM+SHAP | ⏸ **AI-08 소관** — 리포트에 자리표시자만 |

### 2-1. 매칭을 AI 하네스에서 뺀 근거

`EligibilityFilter.qualify`(백엔드, `backend/.../engine/EligibilityFilter.java`)는 결정적
순수 함수다(연령·예비창업·업종·지역 4축). 이를 파이썬으로 재구현해 "테스트"하면 (a) 백엔드
로직을 복제해 발산 위험을 만들고, (b) AI가 아닌 것을 AI 하네스가 검증하는 역할 혼선을 낳는다.
백엔드에는 이미 `EligibilityFilterTest.java`가 경계 케이스(연령 상한 포함/초과·예비창업·업종·
지역)를 잡고 있고, 프로필→필터 종단 동작은 통합 테스트가 잡는다. 따라서 매칭 골드·P/R은
BE 소관으로 둔다.

### 2-2. 부수 발견 (BE 전달)

`backend/.../serving/SessionMapper.java`가 `Profile.region`에 `regionHint` 전체 문자열
(`"서울 마포구"`)을 넣는데, `finance_product.regions`는 시/도 수준(`["서울"]`)이다. 필터의
`e.regions().contains(profile.region())`는 `["서울"].contains("서울 마포구")` → **false**가 되어
지역 제한 상품이 서울 신청자에게도 배제되는 잠재 버그다. 지역 제한 상품이 현재 적재분(21개)엔
없어 표면화되지 않았을 뿐이다. **BE가 필터 진입 전 region을 시/도로 정규화**할 것을 권고
(매칭 골드를 BE가 작성할 때 이 케이스를 포함). 본 문서 범위 밖이나 매칭 골드 정확도에 직결되어
기록한다.

## 3. 산출물 구조

기존 스캐폴드(`ai/eval/run.py`·`ai/Makefile`)를 채운다. **읽기 전용** — 서비스 파이프라인·DDL
무변경 (스펙 §12-1).

```
ai/eval/
  run.py                  # 기존 디스패처 — 스위트 배선 (미구현 SystemExit 제거)
  common.py               # 로더(finance_product·doc_chunk·serving CSV) + 지표·차트 헬퍼
  suites/
    extraction.py         # 필드 일치율
    grounding.py           # 청크 verbatim 충실도 + 커버리지
    sensitivity.py        # 가중치 ±20% · θ 변동 → 상위 3곳 유지율
    report.py             # metrics.json 조립 + 성적표 PNG
  gold/
    extraction_confirmed.json   # 원문 대조 확정골드 (검수액션·검수의견 포함)
    grounding_quotes.jsonl       # product_id → 기대 verbatim 청크 + 원문 오프셋
  out/                    # metrics.json + PNG (git-ignore; 부록 사본은 필요 시 커밋)
```

`make eval` = `eval-extraction` + `eval-grounding` + `eval-sensitivity` + `report`.
`eval-matching`·`eval-model` 타깃은 **BE·AI-08 소관 안내 문구를 출력하고 통과**시킨다(하네스
전체가 초록이되, 미소관 항목은 재현 주체를 명시).

## 4. 추출 정확도 스위트 (핵심)

### 4-1. 입력
- **초안(draft)** = `ai/data/finance/extracted.json` — gpt-4o 추출 원본(중복제거 후 **29상품**:
  KB 4·서울신보 8·소진공 17). 이 중 `amount_max` 결측 8건(1 KB + 7 소진공)은 검수 대상.
- **확정본(gold)** = 신규 `ai/eval/gold/extraction_confirmed.json` — 각 초안 상품을 **원문
  `.txt`/PDF와 대조**해 만든 검수 확정본. 확정 필드값은 **원문에서 전사**하며 생성·추정하지
  않는다(스펙 §0-1). 필드가 원문에 없으면 null.

### 4-2. 확정골드 스키마 (`extraction_confirmed.json`)
```jsonc
[
  {
    "doc": "소진공_소상공인정책자금_지원사업안내",
    "name": "성장기반자금",
    "review_action": "수정",          // 유지 | 수정 | 드롭
    "confirmed": { ...PRODUCT_KEYS... },  // 원문 전사 확정값 (수정/유지)
    "review_note": "원문 p.17 '한도 없음, 시설 최대 …' — 개별 한도 미명시 → amount_max=null 유지, 서빙 드롭",
    "source_ref": "소진공_2026_..._융자공고.txt#L..."  // 수정 근거 원문 위치
  }
]
```
- `review_action=드롭`: 초안이 만든 허위/부적격 상품(원문에 개별 한도 없음 등). 확정 카탈로그
  미포함.
- 확정본에만 있고 초안에 없는 상품(누락) 행은 `review_action="누락보완"`으로 별도 표기.

### 4-3. 지표 (`extraction` in metrics.json)
초안↔확정본을 `(doc, name)`로 매칭 후:
- **필드 일치율** = Σ(일치 필드) / Σ(대상 상품 × PRODUCT_KEYS) — 유지+수정 상품 대상.
- **상품 정밀도** = (유지+수정) / (유지+수정+드롭) — 허위 추출 없는 비율.
- **상품 재현율** = (유지+수정) / (유지+수정+누락) — 완전성.
- **상품 완전일치율** = 유지 / 전체.
- **필드별 일치율** — `amount_max`·`rate`·`term_months` 등 어느 필드에서 LLM이 틀리는지.
- 문서별 커버리지(0건 포함)는 기존 `검수대조표.csv` 값을 그대로 인용.

### 4-4. 값 정규화 규칙 (비교 전)
- 배열 필드(`industries`·`regions`): CSV/JSON 표기 차 흡수 후 집합 비교. null == 빈 배열.
- `rate`: null vs 숫자 구분(변동금리 정당 null은 일치로). 부동소수 오차 ±0.001.
- 문자열: 앞뒤 공백·전각 정규화 후 비교.
- 이 규칙은 `docs/assumptions.md`에 등재한다.

## 5. 근거 충실도 스위트

### 5-1. 원칙
"인용은 검색이지 생성이 아니다"(§5-4)의 **AI 청크 출력**만 검증한다. BE-06 RAG 서빙 인용 대조는
통합/§5-4 이원화("설계 확정·본선 반영") 소관이며 본 스위트 밖이다.

### 5-2. 입력·골드
- 적재된 `finance_doc_chunk`(**6청크**, 전부 소진공 클린 문서) + `finance_product.doc_chunk_ref`.
- 신규 `ai/eval/gold/grounding_quotes.jsonl` — 링크된 상품마다 기대 verbatim 부분문자열 + 원문
  `.txt` 내 바이트 오프셋.

### 5-3. 지표 (`grounding` in metrics.json)
- **verbatim 일치율** = (청크 text가 원문 `.txt`의 바이트 정확 부분문자열인 링크 수) / (전체 링크
  수). 구조상 100% 기대 — 드리프트·재작성을 탐지하는 회귀 게이트.
- **링크 커버리지** = 링크된 상품 / 전체 상품. §5-4 규칙상 정당한 null(깨진 서울신보·KB 문서)과
  예상치 못한 null을 구분해 보고.

## 6. 민감도 스위트

### 6-1. 입력
`v_candidate_area`(서빙 뷰, cafe 1,061·food 1,439 = 2,500행)를 CSV로 익스포트해 읽는다
(Postgres 없이 CI에서 실행). 필요한 성분은 전부 `location_score`에 적재됨:
`w1~w5`([0,1] 서울 백분위 축값), `est_sales`; 부담률용 `rent.monthly_rent`.

### 6-2. 계산
- 기준: 종합 = `round(Σ weightₖ·wₖ × 100)`, 가중치 = (w1 .30·w2 .20·w3 .20·w4 .15·w5 .15,
  카페·음식점 공용, assumptions #6). 부담률 게이트 `burden_ratio ≤ θ`, θ=0.15.
- 섭동: 각 가중치 **±20%**(단일 축 및 합동), θ ∈ **{0.10, 0.15, 0.20}**.
- 업종별로 θ 통과 상권을 종합점수 정렬 → **상위 3곳**. 섭동 후 상위 3곳 집합 유지율(overlap)과
  순위 안정성 측정.

### 6-3. 지표 (`sensitivity` in metrics.json)
- **상위 3곳 유지율**(업종별, 섭동 평균). 수기 골드 없음 — 계산형 강건성 수치.
- 기존 D10 민감도 의도를 그대로 편입(§4-3). 이 스위트는 하네스 최후까지 유지(스펙 §12-4).

## 7. 리포트 조립

`report.py`:
- 4·5·6장 지표를 `out/metrics.json` 단일 파일로 병합. 스키마:
  ```jsonc
  { "generated_at": "...", "data_as_of": "...",
    "extraction": {...}, "grounding": {...}, "sensitivity": {...},
    "matching": { "status": "BE 소관", "note": "EligibilityFilterTest + 통합" },
    "model":    { "status": "AI-08 소관" } }
  ```
- 성적표 PNG: 스위트별 1장(matplotlib, requirements에 존재). 차트 작성 시 `dataviz` 스킬 참조.
- 용어 컴플라이언스: 리포트·라벨 텍스트에 "승인/추천" 계열 금지(CLAUDE.md §3).

## 8. 가드레일 / 컴플라이언스

- 읽기 전용: 서비스 파이프라인·DB 스키마·API 계약 무변경(스펙 §12-1).
- 확정/근거 값은 전부 원문 전사 — 수치 생성 금지(§0-1).
- 결정적 도구 계층 재구현 금지(매칭은 BE 소관).
- 모든 파라미터·정규화·가정은 `docs/assumptions.md`에 즉시 등재(CLAUDE.md).
- 데이터 기준일 상시 표기(§0-1 4항).

## 9. 검증 (DoD)

- `make eval` 1회 실행 성공 → `out/metrics.json` + PNG 산출(스펙 AI-07 DoD).
- 추출: 필드 일치율·정밀도·재현율 수치 산출, 확정골드 원문 근거 추적 가능.
- 근거: verbatim 일치율·커버리지 산출, 링크 무결성 회귀 통과.
- 민감도: 상위 3곳 유지율 산출.
- 각 스위트 순수 함수부 단위 테스트(`ai/tests/`)로 지표 계산 정확성 회귀.

## 10. 범위 밖 / 후속

- 매칭 골드·P/R → **BE**(§2-1). 부수 발견(§2-2) 지역 정규화 → **BE**.
- 검증 모델(LightGBM+SHAP)·게이트 판정 → **AI-08**(§12-2·§12-3).
- 최종 게이트 판정·부록 수치 확정 → **AI-09**(§12-3).
- 라이브 BE-06 RAG 서빙 인용 대조 → 통합/§5-4 이원화.

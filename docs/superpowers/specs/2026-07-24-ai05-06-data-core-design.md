# AI-05 · AI-05a · AI-06 구현 설계 — 산출 테이블 + 정책자금 구조화

- 작성: 2026-07-24 · AI 파트
- 대상 이슈: #11(AI-05) · #65(AI-05a) · #13(AI-06)
- 상위 기준: `docs/specs/최종_스펙문서.md` §3-1·§4(단일 진실 원천) · `docs/API_CONTRACT.md`(동결) · `docs/assumptions.md`
- 성격: **경쟁 스펙이 아니라, 스펙 §4 체크리스트를 모듈·산식·덤프 계약으로 사상한 구현 설계.**
  스펙과 어긋나면 스펙이 우선하며, 파생 상수는 전부 `assumptions.md`에 등재한다.

---

## 0. 백엔드 소비 계약 (병합된 BE-02가 고정한 것)

AI-05/06 산출물의 소비자는 병합 완료된 BE-02다. 배치는 아래 계약의 **원재료만** 공급하고,
합계·부담률·종합점수는 굽지 않는다(BE engine이 재계산).

- `CandidateRepository` 는 `v_candidate_area` 에서 다음 컬럼만 조회한다:
  `area_code,name,lat,lng, deposit_low/high, premium_low/high, interior_low/high,
  monthly_fixed_cost, w1..w5, monthly_rent, est_sales, daily_floating,
  rent_org, rent_district, rent_fallback, transit_*`.
- `CostCalculator`: `reserve = monthly_fixed_cost × 6` →
  `cost_ex = (deposit + interior).shift(reserve)`, `cost_incl = cost_ex + premium`.
  → **뷰의 `cost_ex/incl_premium_*` 는 BE가 읽지 않는다(이중경로, RowMapper 이슈 A).**
  배치가 채우는 뷰 컬럼은 데모·검증용이며, `deposit/interior/premium/monthly_fixed_cost`
  4블록이 engine 재계산과 정합하도록 산출하는 것이 실계약이다.
- `ScoreLookup`: `score = Σ wᵢ·weightᵢ`, `w1 = mean(pedestrian_pct, backing_pct, transit_pct)`,
  `transit_influx = riders × exp(−dist/500)`, 백분위 = 서울 모집단 중 이하 비율. θ=0.15.
- `ReverseCheck.burdenRatio = monthly_rent ÷ est_sales`.
- transit 은 뷰에서 LEFT JOIN → 미매칭 상권은 `transit_fallback` 로만 판별. 배치는 폴백 행을
  억지로 만들지 않는다(누락 허용).

**함의**: `location_score.w1..w5` 는 배치가 **최종 [0,1] 축값**으로 사전 계산한다(engine은 곱·합만).
`est_sales` 는 부담률 분모라 **점포당 월매출**이어야 rent(점포당)와 차원이 맞는다.

---

## 1. 아키텍처 — 모듈 배치

```
ai/batch/collect/
  └─ startup_cost.py         (+KOSIS 영업비용 구조 계열 추가 — 월고정비 공과금 앵커 검증)
ai/batch/preprocess/         STEPS 에 cost·score 추가
  ├─ cost.py                 [AI-05] 초기비용 4블록 → interim/derived/initial_cost.csv
  └─ score.py                [AI-05] w1~w5 서울 백분위 → interim/derived/location_score.csv
ai/batch/load/               (스텁 → 구현)
  ├─ serving.py              raw+interim 조인 → 전 서빙테이블 행 (commercial_area … initial_cost)
  ├─ finance.py              [AI-06] finance_product + finance_doc_chunk 행
  └─ emit.py                 → db/init/10_data_core.sql · 20_finance.sql
ai/batch/extract/            [AI-06] 신규
  └─ funding_llm.py          gpt-4o 배치 추출 → interim/finance/extracted.json + 검수대조표.csv
db/init/01_schema.sql        finance_doc_chunk 테이블만 신설(나머지 동결)
```

원칙: 순수 함수 + 단위 테스트(스펙 §5-1), 파라미터·폴백 전부 `assumptions.md`.

---

## 2. AI-05 산식 (★심사 감점 직결 — 확정)

전 블록: **구간 + "추정치" 라벨 + 출처**. 금액 만원 단위 정수. 권리금 이중표기.

### 2-1. 입력 대표값 (데이터 확정)
- **대표면적** = 인허가 `소재지면적` 영업중 중앙값: **카페 29.2㎡ · 음식점 55.2㎡** (채움률 99.8%).
  전역 업종 상수로 사용 — 환산임대료 차이는 임대료 단가(위치 신호)에서만 나오게 한다.
  (per-상권 면적은 v2 여지로 보류.)
- **환산임대료** `monthly_rent`(만원/월) = `임대료단가(천원/㎡) × 대표면적(㎡) ÷ 10`.
  임대료 단가 = R-ONE 할당 구획(rent_assignment)의 최신 분기값, 폴백 등급 승계.
- **점포당 월매출** `est_sales`(만원) = `분기추정매출(원) ÷ 점포수 ÷ 3 ÷ 10000`.
  ⚠️ 구현 시 `THSMON_SELNG_AMT` 가 분기합인지 재확인(분기합 전제, /3). 점포수=`store_density.store_cnt`.

### 2-2. 초기비용 4블록

| 블록 | 산식 (만원) | 구간 | 출처 라벨 |
|---|---|---|---|
| **보증금** deposit | `monthly_rent × 관행배수` | low ×8 · high ×12 (중심 10) | "환산임대료 역산(상가 관행배수 10, 전월세전환율 {c}% 병기)" |
| **권리금** premium | `㎡당권리금 × 대표면적 × rent_ratio × 업종보정` | low ×0.72 · high ×1.15 | "한국부동산원 권리금 연간 조사(전년 기준), 서울 숙박·음식점업" |
| **인테리어** interior | 업종 상수(FTC 2025) | ±20% | "공정위 가맹정보 2025 (가맹점 기준·상향, 만원)" |
| **예비운영자금** | `monthly_fixed_cost × 6` (engine 산출) | 스칼라 입력 | "여신 관행 버퍼(6개월)" |

- **권리금 상수**: 서울 숙박·음식점업 ㎡당 평균 = **72.6만원/㎡** (2025, R-ONE A_2024_00445).
  `rent_ratio = clip(상권 monthly_rent ÷ 서울 중위 monthly_rent, 0.5, 2.0)` (임대료 수준 비례).
  `업종보정` = 카페 0.85 · 음식점 1.0 (카페 권리금 통상 낮음 — 잠정, 정보공개서 교차 시 갱신).
  구간 폭 ×0.72(중위수/평균=3990/5580)·×1.15(평균 상단). **권리금 유 비율 80.4%** 는 이중표기의
  근거(무권리 진입 = `cost_ex_premium`)로만 쓰고 값에는 곱하지 않는다.
- **인테리어 상수**(FTC `avrgJngEtcAmt` 2025, 만원): 카페 **2,485** · 음식점 **4,595**(비카페 12업종
  기타 중앙값). 음식점 대표값은 AI-05에서 permit food 하위분포(한식·분식·치킨 등) 가중으로 정련.

### 2-3. 월 고정비 (가산형 — 확정)

```
monthly_fixed_cost(만원) = round( 환산임대료
                                 + 표준인원 × 인당월인건비
                                 + 공과금·기타 )
표준인원        = 카페 1.5 · 음식점 2.0 (인허가 종사자수 중앙값 2인 참고 + 업종 특성)
인당월인건비    = 최저임금 시급 × 209h ÷ 10000  (2025: 10,030원 → 약 210만원/월·인; 2026 확정치 구현 시 갱신)
공과금·기타     = 환산임대료 × 0.20  (잠정 — KOSIS 영업비용 구조 수집 후 임차료·인건비 외 비중으로 확정)
```

- 각 항이 공시값(최저임금)·상권데이터로 검증 가능해 심사 QA 방어에 유리.
- ⚠️ 데모(`DemoCandidates`)는 월고정비 100으로 **축소 보정**(assumptions #9)이라, 실값 전환 시
  도달범위가 현실적으로 좁아진다 — 이는 "내 한도로 어디까지"의 정직한 답이며 조달/프론티어가
  예산 상향을 탐색한다. DemoCandidates 는 `!db` 프로필 픽스처라 실덤프와 분리돼 영향 없음.

### 2-4. 점수화 w1~w5 (서울 전체 백분위, 업종별)

배치가 최종 축값 [0,1] 을 사전 계산한다(engine 은 가중 합만).

```
w1 수요   = mean( pct(길단위 유동인구), pct(상주+직장인구), pct(교통유입) )
            교통유입 = daily_riders × exp(−distance_m/500)   (미매칭 상권 = 0)
w2 구매력 = pct(est_sales 점포당 월매출)
w3 경쟁여유 = pct( −경쟁밀도 )    (밀도 역방향; 밀도 = permit_store_cnt 또는 면적정규화)
w4 성장성 = pct( 상권변화지표 성장방향 순위 )
w5 비용효율 = pct( est_sales ÷ monthly_rent )
pct(x) = 서울 전체(해당 업종) 모집단 중 x 이하 비율   ← ScoreLookup.percentile 과 동일 정의
```

- 종합점수는 저장하지 않는다(가중치가 업종 프리셋 → BE 파생, DDL 주석·assumptions #8).
- 가중치 프리셋(카페·음식점 공용): w1 .30 · w2 .20 · w3 .20 · w4 .15 · w5 .15 (assumptions #6, BE 상수와 일치).

---

## 3. AI-05a — 3인 합의 반영 (표결은 팀, 코드는 확정)

assumptions #22 방침을 AI-05 산식·라벨에 반영한다(사용자 지시: 합의요청 코멘트는 생략).
- 단위 = **만원** (FTC `crrncyUnitCdNm` "천원" 라벨 무시 — 메타 버그, 자릿수·KOSIS 교차 검증).
- 가맹점 편의 = **보정계수 미적용**(과대추정 = 하향 안전 마진, 스펙 §0-1).
- 라벨 = **"공정위 가맹정보 2025 (가맹점 기준·상향, 만원)"** + 기준일. 인테리어 블록 출처에 고정.

---

## 4. AI-06 — 정책자금 구조화 (gpt-4o 실배치 — 확정)

### 4-1. 스키마 신설 (`01_schema.sql` 유일 변경)
```sql
CREATE TABLE finance_doc_chunk (
    chunk_id   TEXT PRIMARY KEY,       -- finance_product.doc_chunk_ref 가 가리키는 키
    product_id TEXT REFERENCES finance_product(product_id),
    doc_meta   JSONB NOT NULL,         -- {org, doc, date, page/para}
    text       TEXT NOT NULL           -- 원문 문단 그대로 (LLM 재작성 금지, source_quote 원천)
);
```
`doc_chunk_ref` 미도입 결정(assumptions): pgvector 없이 청크 테이블 직접 조회(§0-2 결정 반영).

### 4-2. 추출 파이프라인
1. `extract/funding_llm.py`: `interim/funding_docs/*.txt` 12건 → gpt-4o 배치. 키 `OPENAI_API_KEY`
   (BE 서빙과 공용). 추출 스키마: `{name, org, max_age, industries[], regions[], pre_startup_only,
   amount_max, rate, term_months, exclusive_group, status, notice_date, source_url}`.
   **LLM은 오프라인 배치 전용·구조화만**. 수치는 문서 인용값 그대로(생성 금지, 불변 §0-1).
2. **전건 사람 검수**: `extracted.json` → `검수대조표.csv`(문서·필드·원문근거·검수결과 열). 팀 검수용
   산출. 이 검수본이 AI-07 '추출 정확도' 골드(추가 라벨링 0).
3. `load/finance.py`: 검수 반영본 → `finance_product` 행 + 원문 문단 청킹 → `finance_doc_chunk`,
   `doc_chunk_ref` 연결.
4. `emit.py` → `db/init/20_finance.sql`.

---

## 5. 덤프 계약 (load/emit)

- `db/init/10_data_core.sql`: `data_source_meta, commercial_area, sales, floating_pop,
  resident_pop, worker_pop, store_density, change_index, rent, transit, location_score,
  initial_cost` (동결 스키마 그대로).
- `db/init/20_finance.sql`: `finance_product, finance_doc_chunk`.
- 형식: 결정적 `INSERT`(또는 `COPY`) — 재실행 가능, `TRUNCATE ... RESTART` 선행. `02_mock_data.sql`
  는 유지하되 사전순 뒤라 실덤프가 덮어씀(또는 BE `db` 프로필에서 목 제외 — BE와 확인).
- 검증: `docker compose up` 재기동 → `v_candidate_area` 행수·NULL·부담률 분포 로그.

---

## 6. 공공데이터 발전 (확정 범위)

- ✅ 즉시 채택(신규 수집 0): 인허가 `소재지면적` → 업종 대표면적.
- 🆕 소규모 추가: **KOSIS 소상공인실태 영업비용 구조**(기보유 커넥터 재사용) → 월고정비 공과금·인건비
  비중 검증·확정.
- ⏸ 보류: 국토부 상업용 부동산 임대차 실거래(상가 공개 제한적) — 관행배수 10 유지.
- ❌ 불가: 인허가 종사자수(1.9~7%)·보증액/월세액(0%) — 채움률 미달.

---

## 7. 서브이슈 · 브랜치 · 이슈관리

- 브랜치(2단계 흐름): `ai` → 토픽 `ai05-cost-score` · `ai05-load` · `ai06-finance` →(로컬 병합)→
  `ai` →(PR)→ `develop`. attribution 금지 하드룰 준수.
- 서브이슈: #11 하위 `AI-05-1 cost/score`·`AI-05-2 load/dump`, #13 하위
  `AI-06-1 extract/review`·`AI-06-2 chunk/schema/dump`. 생성 후 끝까지 종료.
- 단계 전환(계획/개발/검토)마다 해당 이슈에 진행 코멘트. PR 본문 `Closes #`.

## 8. 단위 테스트 (스펙 §5-1)
- cost: 보증금 배수·권리금 비례·인테리어 상수·월고정비 가산 각 경계값. engine `CostCalculator`
  재계산과 `cost_ex/incl` 정합(동일 입력 → 동일 합) 골든 테스트.
- score: percentile 정의·w1 균등평균·교통 감쇠·경쟁 역방향·[0,1] 범위 불변식.
- finance: 추출 스키마 유효성·NULL(무제약) 의미론·exclusive_group·검수본 왕복.

## 9. 열린 확인 항목 (구현 초입에 처리)
- `THSMON_SELNG_AMT` 분기/월 단위 재확인 → est_sales /3 여부.
- 2026 최저임금 확정치.
- KOSIS 영업비용 통계표(tblId) 실호출 확인.
- `02_mock_data.sql` 와 실덤프 적재 순서 — BE와 1줄 확인.

# AI 태스크 상세 — `ai` 브랜치 · `ai/` + `db/init/` 디렉토리

담당: AI 1인 · 스택: Python 3.11 + pandas/geopandas + PostgreSQL, 평가는 LightGBM+SHAP(오프라인 전용)
개요·의존 관계는 [../TASKS.md](../TASKS.md). **모든 가정은 발생 즉시 [../assumptions.md](../assumptions.md) 등재.**

## 0. 환경 준비 (Day 0)

- [ ] `git config core.hooksPath .githooks`
- [ ] `cd ai && python -m venv .venv && source .venv/bin/activate && pip install -r requirements.txt`
- [ ] `.env`에 🔑 `DATA_GO_KR_API_KEY`·`SEOUL_OPEN_DATA_API_KEY` 입력 (발급처는 .env.example 주석)
- [ ] `ruff check .` 그린 확인 (CI와 동일)

## AI-01 (D1) — 데이터 실사 ★최우선, 지연 시 전체 일정 영향

- [ ] **공공데이터포털 신규 API(15099345) 활용신청** — 승인 지연 가능하므로 D1 오전 즉시
- [ ] 서울 상권분석 7종(추정매출·길단위/상주/직장인구·집객시설·점포·상권변화·영역) 실수집
      → 컬럼 정의 노트 작성 (2026-07-03 제공 기준 변경: 행정동단위구역, 2021년~ 자료 확인)
- [ ] 상권 영역 좌표계 EPSG:5181 → WGS84 변환 검증 (강남 표본 육안)
- [ ] **상권 구획도 SHP(2024-10-31판, 368개) ↔ 최신 분기 임대료 통계 상권코드 전건 대조**
      (R-ONE "구획도 변경 예정" 공지 — 리스크 #18). 불일치 상권 목록 → 자치구 평균 폴백 대상 확정
- [ ] 역사마스터(OA-21232) 좌표계 확인 — 불명확하면 **즉시 t-data 지하철역_GEOM으로 교체**
      (CC-BY 표기 의무 → README 라이선스 표에 이미 반영됨)
- [ ] θ(부담률 임계)·θ'(=θ+5%p 초기값) 문헌 탐색 — 실패 시 "통용 기준+조정 가능" 표현 확정
- [ ] Track A(열린데이터광장 임대시세) 존재 확인 → 존재+호환 시 교체, 아니면 Track B 확정
- **DoD**: 정합성 대조 결과·좌표계 확인·θ 근거 전부 assumptions.md 등재

## AI-02 (D2) — 수집 완료 (`ai/batch/collect/`)

- [ ] `seoul_commercial.py` — 상권분석 7종 (재실행 가능, raw/ 보존)
- [ ] `transit.py` — 역사 좌표 + 승하차(OA-12914, 파일/OpenAPI 경로 — Sheet 미사용).
      환승역 = 노선별 복수 행 → 역명 정규화 후 합산 (규칙 assumptions.md)
- [ ] `reb_rent.py` — 임대료·전환율(API, 장애 시 파일 폴백) + 구획도 SHP + **권리금 연간치**
      (연 1회 발행 — 화면 라벨 "연간 조사(전년 기준)" 확정)
- [ ] `permits.py` — 인허가 CSV, **업종 재분류(837→247) 신분류 코드 기준** 카페·음식점 매핑표
- [ ] `funding_docs.py` — 정책자금·보증·대출 공개 문서 20~40건 (소진공·서울신보·시중은행, KB 포함)
- [ ] 창업비용 통계 (소상공인실태조사, KOSIS) 업종 2~3종
- **DoD**: 전 소스 raw/ 적재 + 스크립트 재실행 검증

## AI-03 (D3) — 스키마 DDL + 목 덤프 ★CP1

- [ ] DDL: `commercial_area / sales / floating_pop / resident_pop / worker_pop /
      rent(+reb_district_cd, fallback_flag) / store_density / change_index /
      transit(+nearest_station, line, distance_m, daily_riders, fallback_flag) /
      location_score / initial_cost(+정렬 인덱스) / finance_product(+exclusive_group,
      term_months, doc_chunk_ref)` (스펙 §3-1)
- [ ] 목 데이터 (상권 10곳·상품 5종 수준) → `db/init/01_schema.sql`·`02_mock_data.sql`
- [ ] `docker compose up` 재기동으로 적재 확인 → BE 조회 테스트 합류
- **DoD**: CP1 E2E — 스키마 변경 시 BE 사전 공지 (CONTRIBUTING §6)

## AI-04 (D4~5) — 공간 조인 3단계 (`ai/batch/preprocess/`)

- [ ] [1] 인허가 좌표(WGS84) → 상권 폴리곤 = 경쟁밀도 (검증: 강남역 표본 육안)
- [ ] [2] 구역 중심점 → 부동산원 구획 = 임대료 할당 (검증: 상권 3곳 수작업 대조)
      실패·불일치 → 자치구 평균 + `fallback_flag` (AI-01 대조 목록 활용)
- [ ] [3] 중심점 → 최근접 역 `sjoin_nearest` = 거리 d + 일평균 승하차 V (검증: 대표역 3곳)
      실패 → 접근성 성분 0 + 플래그 (서비스 무중단)
- [ ] 처리 순서: 좌표계 통일(pyproj) → 1 → 2 → 3 (D4 1.5일+2h 버퍼, 리스크 #3)
- **DoD**: 검증 3종 통과 로그 + 폴백 발동 내역 assumptions.md

## AI-05 (D5~6) — 산출 테이블 ★CP2

- [ ] 초기비용 4블록: 보증금(환산임대료 역산: 관행 비율+전환율) / 권리금(서울 평균+업종 보정,
      임대료 수준 비례) / 인테리어·설비(업종 상수) / 예비 운영자금(월고정비×6개월)
      — 전 블록 구간 + "추정치" 라벨 + 출처, 권리금 이중 컬럼(`cost_ex_premium`/`cost_incl_premium`)
- [ ] 이중 필터 재료: 부담률 = 환산임대료(부동산원 상권) ÷ 추정매출(행정동단위구역) ≤ θ
- [ ] 점수화: `score = w1·입지 + w2·구매력 + w3·경쟁여신 + w4·성장성 + w5·비용효율`
      w1 = 길단위 유동 + 배후(상주+직장) + **교통 접근성(승하차 × exp(−d/500m))**
      — 서울 전체 백분위 정규화, 성분 결합 규칙·가중치 assumptions.md
- [ ] 정렬 인덱스 (BE 프론티어용 사전 정렬)
- [ ] `db/init/10_data_core.sql` 덤프 내보내기 (`ai/batch/load/`)
- **DoD**: CP2 — BE 실데이터 전환 성공

## AI-06 (D6) — 정책자금 구조화

- [ ] LLM 배치 추출: 공고문 → {대상 요건(나이·업종·지역), 한도, 금리, 기간, status,
      exclusive_group, 공고일} 구조화
- [ ] **전건 사람 검수** → 검수 대조표 보존 (AI-07 '추출 정확도'의 골드 — 추가 라벨링 0)
- [ ] 원문 문단 청크 → 벡터DB(또는 청크 테이블) 적재, `doc_chunk_ref` 연결 (BE-06 RAG 재료)
- [ ] `db/init/20_finance.sql` 덤프
- **DoD**: 구조화 DB + 청크 + 검수 대조표 3종 세트

## AI-07 (D9~10) — 평가 하네스 (`ai/eval/`, 스펙 §12-1)

- [ ] 골드셋: 가상 프로필 12~20종 × 상품 전건 자격 교차 판정표 (수작업)
- [ ] `eval/run.py` 스위트 구현: matching(P/R) / extraction(검수본 일치율) /
      grounding(source_quote↔원문 문자열 일치) / sensitivity(가중치 ±20%·θ 변동 상위 3곳 유지율) /
      model(AI-08) / report(metrics.json+차트 PNG 취합)
- [ ] 기존 정산 테이블·검수 산출물 **읽기 전용** — 서비스 파이프라인·스키마 불변 확인
- **DoD**: `make eval` 1회 통주(通走) — 산출물이 그대로 부록 1

## AI-08 (D9~10) — LightGBM+SHAP 설계 교차 검증 (스펙 §12-2·12-3)

⚠️ **프로토콜을 결과 확인 전에 고정하고 assumptions.md에 등재** (사후 조정 금지 — 리스크 #19):

- [ ] 타깃 = log(점포당 추정매출) / 피처 = 비매출 성분만 (w1 3성분 + w3 경쟁 + w4 변화지표,
      w2·w5는 매출 파생이라 배제) / CV = 자치구 블록 5-fold GroupKFold
- [ ] 지표 = fold 중앙값 R²·Spearman ρ·WAPE·MAE + 베이스라인(자치구 평균) 대비 — MAPE 미사용
- [ ] SHAP summary plot + 축별 기여 방향 vs 설계 부호 일치 수 (n/5)
- [ ] **게이트 사전 등재 확인**: A(R²≥0.30 ∧ ρ≥0.60 ∧ 방향≥4/5) / B(R²≥0.15 ∧ (ρ≥0.45 ∨ 방향≥4/5)) / C(미수록)
- [ ] 한계 명기 문구(부록 2 하단 고정) 포함
- **DoD**: 재현 스크립트 + 프로토콜 등재 (실행·판정은 AI-09)

## AI-09 (D11~12) — 최종 평가·게이트 판정 ★CP5

- [ ] `make eval` 최종 실행 → 게이트 A/B/C 판정
- [ ] A: 부록 2 전체 수록 / B: 방향 일치 중심 축약 / C: 부록 2 삭제 + 성적표에서 해당 행 제외
      + 민감도 단독 유지 (v6.1 상태 복귀 — "매출 예측" 표현 전면 금지)
- [ ] 부록 1·2 수치 확정 → CM-05 기술설명서에 전달
- **DoD**: 판정 근거 문서화, README "AI 품질 평가 재현" 3줄 유효성 확인

## PR 슬라이스 권장

| PR | 내용 | 시점 |
|---|---|---|
| 1 | AI-01 실사 노트+AI-02 수집 스크립트 | D2 |
| 2 | AI-03 DDL+목 덤프 | D3 |
| 3 | AI-04 공간 조인 | D5 |
| 4 | AI-05 산출 테이블+실데이터 덤프 | D6 |
| 5 | AI-06 정책자금 구조화 | D7 |
| 6 | AI-07·08 평가 하네스+검증 모델 | D10 |
| 7 | AI-09 최종 지표·부록 산출물 | D12 |

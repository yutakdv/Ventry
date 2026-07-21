# BE 태스크 상세 — `backend` 브랜치 · `backend/` 디렉토리

담당: BE 1인 · 스택: Spring Boot 4.1 (Java 25) + PostgreSQL + Caffeine + SseEmitter
개요·의존 관계는 [../TASKS.md](../TASKS.md), 알고리즘 상세는 `../specs/exploration_agent_spec_v2_1.md`.

> **처음 이어받는다면 [../HANDOFF_BACKEND.md](../HANDOFF_BACKEND.md)를 먼저 읽을 것.**
> 이 문서는 "무엇을 할 것인가"의 체크리스트이고, 인수인계 문서는 "지금 무엇이 진짜로 도는가"
> (엔드포인트별 목/실 상태·도구 계층 결선 현황·함정 목록)를 다룬다.

## 0. 환경 준비 (Day 0)

- [ ] `git config core.hooksPath .githooks`
- [ ] IntelliJ 설정 (CONTRIBUTING §1-1): 2025.2+, `backend/` 열기, Gradle *Wrapper* +
      Gradle JVM **JDK 25** (Download JDK 가능), 재동기화 — 테스트 import 오류는 재동기화로 해소
- [ ] `cd backend && ./gradlew test` 그린 확인 (wrapper가 Gradle 9.5.1 자동 다운로드)
- [ ] `docker compose up db` 로 로컬 DB 기동 확인
- [ ] LLM 키 발급: **OpenAI API 확정(7/21 변경, DECISIONS.md §6)** — platform.openai.com/api-keys에서
      키 발급 → .env `OPENAI_API_KEY` (AI-06 배치와 공용, 서빙 모델 gpt-4o-mini)

## BE-01 (D3) — API 계약 동결 + 목 구현 ★CP1

- [ ] `docs/API_CONTRACT.md` 검토·보완 → 3인 합의로 **동결** (필드·enum·SSE 이벤트 순서)
- [x] ~~동결 전 결정 5건 해소~~ → **7/20 확정 완료** (판정 enum `CAUTION`(유의)·만원 단위·
      scenarios SSE·recommend `risk_review`·`parse_source` — DECISIONS.md, 계약 반영됨).
      D3에는 FE·AI 검토 확인만
- [x] 세션 저장소: 인메모리(ConcurrentHashMap + TTL 60분), **B₀ 구성(자기자본+상품별 사용액)
      기록** (expl §2-2 잔여 한도 원칙의 재료 — BE-04에서 사용) (7/20, SessionStore)
- [x] 패키지 구조: `com.ventry.api.{diagnose,scenario,recommend,explore,checkarea,common}` (7/20)
- [x] DTO 전체 정의 (record 사용, 만원 단위 int, WGS84 double) (7/20)
- [x] 6개 엔드포인트 목 응답 구현 (데모 프로필 기준 — expl §8 수치 정합) (7/20)
      ※ /api/diagnose는 이 시점엔 목 파싱(키워드 concerns) — LLM 실파싱은 BE-05에서
- [x] SSE 목: `/api/scenarios`·`/api/explore` — SseEmitter + 전용 비동기 executor
      (가상 스레드, 톰캣 워커 점유 금지, expl §5) + 15s 하트비트 (7/20, SseSupport)
- [x] 오류 포맷 `{error:{code,message}}` 공통 핸들러 (7/20)
- **DoD**: FE가 목만으로 전 화면 개발 가능 / compose 스모크 그린

### BE-01a (D2) — 계약 CP1 검토 반영 (FE 제안 6건, 이슈 #41)

- [ ] `POST /diagnose` 폼 3필드 (`monthly_investable`·`is_existing_business`·
      `collateral_available`) + `Eligibility.pre_startup_only` 자격 규칙 결선
- [ ] `POST /budget` 응답 프리뷰 (`area_count`·`rent_range`·`floating_range`)
- [ ] `GET /recommend` `score`·`total_count`·`summary` + area별 `monthly_rent`·`est_sales`·
      `daily_floating`, `burden_ratio`를 임대료÷매출 파생으로 교체
- [ ] `GET /scenarios` 예산 범위(`budget_min`/`budget_max`)·`composition` 범위화 +
      `Product`에 `amount_max`·`rate`·`data_as_of` (공통 규약이 요구하던 누락 필드)
- [ ] `GET /explore` `plan.axis_labels`·`done.current_budget`
- **DoD**: 계약 문서 반영 + `./gradlew test` 그린 + FE 회신 문서 제출 (DECISIONS.md §8~§11)

## BE-02 (D4~5) — DB 연동 + 캐시

- [ ] `spring-boot-starter-jdbc` 활성화 (build.gradle 주석 해제) + `application.yml` datasource 주석 해제
- [ ] 사전 적재 테이블 read-only 조회 리포지토리 (AI-03 DDL 기준)
- [ ] **탐색 세션당 쿼리 1회** 원칙: 후보 행(비용·점수·임대료·매출) 일괄 조회 → 인메모리 계산
      (시나리오별 N+1 쿼리 금지, expl §5)
- [ ] Caffeine 캐시: 키 (업종, 자치구), 기동 시 예열, 정렬 비용 배열은 사전 정렬 보관
- [ ] 목→실데이터 전환 스위치 (프로파일 or 설정값) — AI 덤프 지연 시 무중단
- [ ] 데이터 기준일 메타 테이블(AI-03) 조회 → 전 응답 공통 `data_as_of` 주입
- **DoD**: CP2에서 실데이터 응답 확인, 조회 지연 10~30ms 로그

## BE-03 (D5~6) — 결정적 도구 계층 5종 (P0의 심장)

`com.ventry.api.engine` — **순수 함수 + 단위 테스트 필수** (스펙 §5-1):

- [ ] `EligibilityFilter` — 자격 정규칙 (나이·업종·지역·상품 요건)
- [ ] `CostCalculator` — 초기비용 4블록 합성, 권리금 이중 표기(`costExPremium`/`costInclPremium`),
      구간 유지 (점추정 금지)
- [ ] `ScoreLookup` — 사전 계산 점수 조회 + breakdown
- [ ] `Frontier` — §2 해석적 이중 프론티어 (BE-04·05에서 확장)
- [ ] `ReverseCheck` — 임의 상권 역방향 판정 (판정 4단계 enum)
- [ ] 필터 경계 규칙 테스트: `제외 ≤ 예산 < 포함 중위값` → ⚪ 조건부 적합 (스펙 §4-1)
- [ ] **`/api/recommend`·`/api/check-area` 목→실 전환**: 이중 필터→점수→판정 4단계 +
      `reason_text` 템플릿(f-string 상당) 조립 — 도구 계층 결선의 첫 소비자
- **DoD**: 도구별 단위 테스트 + 경계 케이스 통과, LLM 의존성 0

## BE-04 (D7) — 해석적 프론티어(진입) + 조달 검증

- [ ] 진입 프론티어: 비용 정렬 배열 → `N_entry(B) = |{a : c_a ≤ B}|` 닫힌 형태
      — 그리드 금지, 다음 경계 = `min{c_a > B₀}`, 갭 = 경계 − B₀ (절단점 오차 0, expl §2-1)
- [ ] 하향 안전 마진 `B_safe = max{c_a ≤ B₀}` / 무권리 프론티어는 `c'_a` 배열로 동일 계산
- [ ] 조달 검증 (expl §2-2): 커버 사양(한정 상품 미사용 한도 → 자격 통과·open 상품),
      `exclusive_group` 동일 그룹 1개 제약, 커버 불가 경계는 보고 제외
- [ ] 커버 성공 시 조달 명세 {상품, 금액, 금리 r, 기간 T(상품 조건 우선, 부재 시 보증 가정 5년
      → `docs/assumptions.md` 등재 + 화면 병기)}
- [ ] **진단 폼 신규 2필드 결선** (DECISIONS.md §8 — BE-01a에서 수집만 하고 미사용 상태):
      `monthly_investable` = 월 상환액 m의 상한(m ≤ 월 투자 가능액) / `collateral_available` =
      담보·보증 요구 상품의 커버 가능 여부. 규칙 확정 시 `docs/assumptions.md` 등재
- [ ] **조달 시나리오 생성 (화면 2 실데이터)**: 자격 필터 통과 상품 조합으로 보수/적극
      시나리오 2종 구성 + 추천 태그 (스펙 §5-2 ② 시뮬레이션 — 목→실 전환)
- [ ] **LLM 클라이언트 골격 선행** (D8 부하 분산): SDK 셋업·프롬프트 골격·타임아웃 5s·
      세마포어(동시 K건, expl §5)·**키 부재 시 무LLM 모드**(템플릿 폴백 경로와 동일 코드패스)
- **DoD**: 경계·갭·마진 수치 검증 테스트 (수작업 계산 대조 3케이스)

## BE-05 (D8) — 탐색·검증 에이전트 ★CP3 (최대 부하 일차)

- [ ] 확장 부담률 필터 2′: `(환산임대료 + m(gap,r,T)) / 월추정매출 ≤ θ'` (원리금균등 m 공식)
- [ ] 지속 프론티어 `N_sustain(B)` — **비단조**. 자기자본만 구간에서 m=0 ⇒ 필터2=필터2′
      일관성 테스트 필수 (expl §2-3)
- [ ] 스코어링·컷: `score = Q×F/C`, 품질 조건(ΔN_green≥2 or ΔS_top 1구간), 보고 ≤3건
      (T1~T5 중 상위 2 + T2 안전 마진 1), **0건 보고도 유효** — 전용 문장 출력
- [ ] LLM 탐색 계획(plan): 대화 맥락·프로필 입력 → A2~A4 실행 여부·우선순위 JSON
      (A1은 무조건 실행)
- [ ] 언어화: **템플릿(f-string) 즉시 송출 → LLM refine 도착 시 교체** 이벤트.
      LLM 프롬프트 제약 "입력 JSON에 없는 수치·상품 언급 금지" / 타임아웃 5s / 장애 시 템플릿 최종
- [ ] `/api/explore` SSE 실구현: version(AtomicLong) 취소 — 이벤트 송출 직전 최신 version 비교
- [ ] tool-calling 오케스트레이터 결선: 진단→시나리오→예산→추천→탐색 도구 호출 로그 SSE 노출
- [ ] 리스크 검증 에이전트 (스펙 §5-3): 추천 JSON 입력 → 데이터 기반 반박문 1회 → 판정·근거
      패널 반영, **1왕복 고정**, 장애 시 "검증 생략" 플래그 + 추천 유지
- [ ] **진단 파싱 실구현** (AI 역할 [1], 스펙 §5-2 ①): 폼+자연어 → parsed_profile
      (LLM 파싱, 장애 시 폼 값만 사용하는 폴백) — BE-01 목을 교체
- **초과 시 폴백 (리스크 #12)**: A2·A3 스킵, A1 이중 프론티어 + 검증 1왕복만 사수
- **DoD**: 데모 시나리오(expl §8) 전 구간 재현 = 크루 모먼트 성립.
  **+ 데모 프로필로 T1 경계가 실제 존재하는지 사전 검증** (expl §10 "경계 전무" 리스크 —
  0건 보고 문장만 나오면 크루 모먼트가 사라지므로 데모 프로필·예산 값을 조정)

## BE-06 (D9~10) — P1 (여유 시, 순서 고정: ①→②→③)

- [ ] ① RAG 근거 인용: `doc_chunk_ref` → 벡터DB(또는 청크 테이블) 조회 → `source_quote`
      **원문 그대로** (LLM 재작성 금지 — "인용은 검색이지 생성이 아니다", 스펙 §5-4)
      미구현 확정 시: D10에 계약의 `source_quote: null` 유지 + 스펙 §5-4 이월 문서 처리 (CM-03)
- [ ] ② 근거문 캐시: 상권×업종 근거문 사전 생성, 탐색 인사이트는 템플릿 즉시+LLM 교체
- [ ] ③ 개인화 언어화: 근거문 프롬프트에 사용자 발화 요약 주입
- **DoD**: 구현분 계약 반영 or 이월 문서화 완료

## BE-07 (D11~12) — 통합 QA·하드닝

- [ ] QA 시나리오 10종 실행 (정상 5 + 경계 3 + 장애 2)
- [ ] **LLM 전면 차단 QA**: 키 제거 상태로 전 동선 — 템플릿 폴백이 최종본으로 자연스러운지
      (심사위원이 직접 실행하므로 폴백 화면 품질이 채점 대상, 리스크 #9)
- [ ] SSE 부하: 슬라이더 연타 시 version 취소 동작 확인
- [ ] Docker 정리: 이미지 크기·기동 시간, compose 스모크 재확인
- **DoD**: QA 리포트 작성, 미해결 이슈 명시

## PR 슬라이스 권장

| PR | 내용 | 시점 |
|---|---|---|
| 1 | BE-01 계약 동결+목 6종+SSE 골격 | D3 |
| 2 | BE-02 DB·캐시 | D5 |
| 3 | BE-03 도구 계층+테스트 | D6 |
| 4 | BE-04 프론티어+조달 검증 | D7 |
| 5 | BE-05 탐색·검증 에이전트 | D8~9 |
| 6 | BE-06 P1 구현분 | D10 |
| 7 | BE-07 QA 수정 | D12 |

# 확정 사항 — 전 파트 적용

리더(BE, yutak) 확정. 문서 반영 완료 상태로 공유하며, 이의는 D3 CP1 합동 점검에서 제기
(이후에는 CONTRIBUTING §6 계약 변경 절차). 각 항목의 "반영 위치"는 이미 수정된 파일이다.

- **§1~§7**: D0(2026-07-20) 확정분.
- **§8~§11**: D2(2026-07-21) FE 계약 검토 의견 반영분 — 계약이 아직 동결 전(D3 CP1)이므로
  CONTRIBUTING §6 사후 절차가 아니라 **예정된 CP1 검토 창구**로 처리했다.

## 1. 판정 용어 = "유의" (enum `CAUTION`)

- **문제**: 스펙 v6.2·탐색 스펙은 "유의", CLAUDE.md·README·API 계약·태스크 문서는 "정속"으로
  갈라져 있었음. 용어 컴플라이언스는 심사 감점 직결 항목.
- **확정**: 판정 4단계 = `적합 / 조건부 적합 / 유의 / 범위 외`. API enum은
  `FIT / CONDITIONAL / CAUTION / OUT_OF_SCOPE` ("SLOW" 폐기).
- **근거**: "문서와 코드가 어긋나면 스펙 문서가 우선"(CLAUDE.md) + "유의"가 심사위원에게
  자명한 표준 용어.
- **반영**: CLAUDE.md · README.md · API_CONTRACT.md · TASKS.md · tasks/FRONTEND.md.
- **할 일**: FE — 화면 문구·마커 라벨에 "정속" 사용 금지. BE — Verdict enum `CAUTION`.

## 2. 금액 단위 = 만원 단위 정수

- **문제**: 스펙 §6에 "원 단위 정수"로 오기 (계약·CLAUDE.md·모든 예시는 만원).
- **확정**: API 전 구간 **만원 단위 정수** (예: 8000 = 8,000만 원). 스펙 §6 정정 완료.
- **반영**: specs/최종_스펙문서.md §6.

## 3. `/api/scenarios` SSE 이벤트 스키마

- **확정**: `scenario` 이벤트(카드 1장씩, 2회) → `done` `{scenario_count}`. 하트비트 15s 공통.
- **반영**: API_CONTRACT.md 엔드포인트 2.

## 4. `/api/recommend` 응답에 `risk_review` 추가

- **문제**: FE-05 "왜? (검증 의견 n건)" 패널(추천 판정 옆)의 데이터 원천이 계약에 없었음
  (`risk_review`는 check-area에만 존재).
- **확정**: recommend 응답에 `risk_review: {objection_text, applied, skipped}` 포함.
  리스크 검증 장애 시 `skipped: true` = "검증 생략" 플래그(용어 수위 §0-4 준수).
- **반영**: API_CONTRACT.md 엔드포인트 4.

## 5. `/api/diagnose` 응답에 `parse_source` 추가

- **확정**: `parsed_profile.parse_source: "llm" | "form_only"` — LLM 파싱 폴백(폼 값만 사용)
  여부를 FE가 표시·QA가 확인할 수 있게.
- **반영**: API_CONTRACT.md 엔드포인트 1.

## 6. LLM 제공자 = OpenAI API  (2026-07-21 변경 — 기존 Anthropic Claude 확정 대체)

- **확정**: 서빙(진단 파싱·탐색 plan·언어화 refine·리스크 반박) = **`gpt-4o-mini`**
  (저지연 — 타임아웃 5s·데모 반응성 제약에 정합). AI-06 배치 추출(정책자금 구조화) =
  **`gpt-4o`** (정확도 우선 — 전건 사람 검수 전 초안 품질이 검수 공수를 좌우).
- **키**: 루트 .env `OPENAI_API_KEY` 하나로 BE·AI 공용 (SDK가 자동 인식하는 표준 변수명).
  발급: https://platform.openai.com/api-keys — **BE·AI는 D1까지 발급 완료할 것.**
- **불변 원칙 유지**: 키 부재·장애 시 템플릿 폴백이 최종본(데모 무중단). LLM 수치 생성 금지.
- **변경 이력**: 2026-07-20 Anthropic Claude(haiku/opus)로 확정 → 2026-07-21 OpenAI로 변경.
  저지연 서빙 / 정확도 배치의 역할 매핑은 그대로 승계(haiku→gpt-4o-mini, opus→gpt-4o).
  모델 ID는 D3 CP1에서 최종 확인(비용·성능 확인 후 4.1 계열 등으로 조정 가능).
- **반영**: .env.example §5 · docker-compose.yaml (환경변수명 `ANTHROPIC_API_KEY` →
  `OPENAI_API_KEY`) · TASKS.md · tasks/BACKEND.md · tasks/AI.md.

## 7. 정책자금 원문 청크 저장 = Postgres 테이블 (pgvector 미도입)

- **근거**: source_quote는 매칭된 상품의 `doc_chunk_ref`로 원문 청크를 **id 직접 조회**하는
  구조(스펙 §5-4 "인용은 검색이지 생성이 아니다") — 유사도 검색이 필요 없으므로 벡터DB는
  과설계. compose에 컨테이너 추가 없이 기존 PostgreSQL로 충분.
- **확정**: `finance_doc_chunk` 테이블(chunk_id PK, 문서 메타(org·doc·date), 원문 text)로 적재.
  스펙의 "벡터DB(또는 청크 테이블)" 표기 중 후자 채택.
- **할 일**: AI — AI-03 DDL·AI-06 적재에 반영, assumptions.md 등재. BE — BE-06 RAG 조회는
  단순 SELECT.

## 8. 진단 폼 신규 3필드 — 수치는 구조화 필수 (2026-07-21, FE 제안 ①)

- **문제**: 화면 1이 받는 입력 중 재창업 여부·담보 제공 가능 여부·월 투자 가능액 세 가지가
  `form`에 자리가 없었다. FE는 이 값들을 `free_text`에 실어 보내는 우회안을 제시했다.
- **확정**: 세 필드 모두 `form`에 구조화한다.
  - `monthly_investable`(int, 만원) — **자유 텍스트 경유 불가**. "250만원"을 LLM이 250 /
    2,500,000 / 누락 중 무엇으로 뽑을지가 비결정적이며, 이는 LLM이 수치를 만드는 통로가 되어
    불변 원칙 §0-1과 정면 충돌한다. FE의 지적이 정확하다.
  - `is_existing_business`(bool) — 자격 정규칙 축이다. 스펙 §5-4의 인용 예시가 이미
    "만 39세 이하 **예비창업자**로서…"이므로 예비창업자 한정 상품을 거를 근거가 된다.
    `Eligibility.pre_startup_only`로 EligibilityFilter에 즉시 결선.
  - `collateral_available`(bool) — 스펙에 담보 축이 없어 **현재 판정에 쓰지 않는다.** 프로필로
    기록만 하고 BE-04 조달 검증의 보조 입력으로 예약한다. 계약 문서에 "미사용"을 명시해
    "받아놓고 안 쓰는 필드"임을 숨기지 않는다.
- **미사용 필드 처리 원칙**: `monthly_investable`도 현 시점 계산에는 쓰이지 않는다. BE-04에서
  한계 조달의 월 상환액 m에 대한 상환 여력 상한(m ≤ monthly_investable)으로 결선하며,
  그때 가정으로 assumptions.md에 등재한다.
- **반영**: API_CONTRACT.md 엔드포인트 1 · tasks/BACKEND.md BE-04.

## 9. 예산 프리뷰의 주체 = `POST /budget` 응답 (2026-07-21, FE 제안 ②)

- **문제**: 화면 2의 예산 슬라이더가 즉시 프리뷰(예상 후보 수·임대료·유동인구)를 그리는데
  `POST /budget`에 응답 스키마가 없었다. FE가 "/budget이 주는가, 프론트가 /recommend를
  재호출하는가"를 아키텍처 결정 사항으로 물었다.
- **확정**: **`POST /budget` 응답이 프리뷰를 준다** (FE 선호안과 동일).
- **근거**: `/recommend`는 세션의 확정 예산 B₀로 계산한다. 화면 2 시점에는 B₀가 아직 없어
  자기자본으로 폴백하므로, 슬라이더가 가리키는 임의 예산의 프리뷰를 **원리적으로** 만들 수
  없다. `/recommend?budget=` 쿼리로 우회하면 예산의 진실 원천이 세션과 쿼리로 이중화되어
  `/explore`(세션 B₀ 기준)와 어긋난다.
- **부수 확정**: B₀는 덮어쓰기다. 화면 2 슬라이더는 debounce 후 `POST /budget`을 반복 호출하고
  마지막 값이 확정값이 된다. 화면 3 슬라이더는 스펙 §7·expl §5대로
  `POST /budget` → `/recommend?v=`·`/explore?v=`(동일 version) 순서를 유지한다.
- **반영**: API_CONTRACT.md 엔드포인트 3.

## 10. `/recommend` 정렬·필터 = 클라이언트 처리 (2026-07-21, FE 제안 ③)

- **확정**: 응답 필드는 FE 요청분(`score`·`total_count`·`summary` + area별 `monthly_rent`·
  `est_sales`·`daily_floating`)을 **전부 추가**하되, 정렬·필터 **쿼리 파라미터는 두지 않는다.**
- **근거**: ① 정렬·필터에 필요한 값이 모두 응답에 실리므로 프론트가 왕복 0회로 처리할 수 있고,
  그편이 스펙 §7의 "<100ms 재계산 체감" 목표에 유리하다. ② 서버 정렬·필터를 두면
  `total_count`·`summary`의 기준 집합이 파라미터마다 달라져 계약이 복잡해진다.
- **재검토 시점**: BE-02 실데이터 전환으로 후보 풀이 커져 페이징이 필요해지면 그때 도입한다.
- **부수 정정**: `burden_ratio`를 픽스처 상수에서 **환산임대료 ÷ 추정매출 파생**으로 바꿨다.
  스펙 §4-2의 정의 그대로이며 AI-05 산출물(부담률 분모·분자)과도 정합한다.
- **반영**: API_CONTRACT.md 엔드포인트 4 · assumptions.md #8.

## 11. 결과 저장·PDF 보고서 = 스코프 외 유지 (2026-07-21, FE 제안 ⑥)

- **확정**: 결과 저장·PDF 상담 리포트·공유 링크·저장 목록 API를 **신설하지 않는다.**
  프론트도 해당 화면(Step 5)을 만들지 않는다.
- **근거**: 스펙 §0-2가 "PDF 상담 리포트"를 P2로 명시 이월했고, P2는 "생각하지 못한 것이 아니라
  의도적으로 미룬 것"으로 로드맵에 서술하는 항목이다. D10 기능 동결까지 남은 공수는 P0
  미완료분(BE-04·05, FE-04·05)에 배정한다.
- **반영**: API_CONTRACT.md "스코프 외" 절 · tasks/FRONTEND.md.

---

## 파트별 액션 요약

| 파트 | 해야 할 일 |
|---|---|
| FE | "정속" → "유의" (화면 문구·타입). `src/api/types.ts`에 `CAUTION` enum·`risk_review`·`parse_source` 반영 |
| BE | Verdict enum `CAUTION`. 목 응답에 risk_review·parse_source·scenarios SSE 스키마 포함 (BE-01에 반영됨) |
| AI | `OPENAI_API_KEY` 발급(D1). 배치 추출 모델 gpt-4o. `finance_doc_chunk` 테이블 DDL |
| FE (§8~§11) | 진단 폼에 3필드 추가 송신 / 화면 2 슬라이더는 `POST /budget` 재호출로 프리뷰 수신 / 추천 목록 정렬·필터는 응답 필드로 클라이언트 처리 / 축 라벨 하드코딩 사전 제거(`plan.axis_labels` 사용) / Step 5(결과 저장) 화면 미구현 |
| BE (§8~§11) | 계약 반영 결선 (BE-01a) — DTO·엔진·픽스처, `collateral_available`·`monthly_investable`은 BE-04에서 계산 결선 |
| AI (§8~§11) | AI-05 산출 테이블에 **환산임대료·월 추정매출·일평균 유동인구** 컬럼 필요 (부담률 분모·분자 + 화면 표기용) |
| 전원 | D3 CP1에서 본 확정 11건 검토 → 계약 최종 동결 |

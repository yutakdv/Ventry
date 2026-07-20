# D0 확정 사항 (2026-07-20) — 전 파트 적용

리더(BE, yutak) 확정. 문서 반영 완료 상태로 공유하며, 이의는 D3 CP1 합동 점검에서 제기
(이후에는 CONTRIBUTING §6 계약 변경 절차). 각 항목의 "반영 위치"는 이미 수정된 파일이다.

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

---

## 파트별 액션 요약

| 파트 | 해야 할 일 |
|---|---|
| FE | "정속" → "유의" (화면 문구·타입). `src/api/types.ts`에 `CAUTION` enum·`risk_review`·`parse_source` 반영 |
| BE | Verdict enum `CAUTION`. 목 응답에 risk_review·parse_source·scenarios SSE 스키마 포함 (BE-01에 반영됨) |
| AI | `OPENAI_API_KEY` 발급(D1). 배치 추출 모델 gpt-4o. `finance_doc_chunk` 테이블 DDL |
| 전원 | D3 CP1에서 본 확정 7건 검토 → 계약 최종 동결 |

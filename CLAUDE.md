# Ventry — 여금(여유 자금) 우선 입지 컨설팅 에이전트

KB 제8회 Future Finance AI Challenge · 주제 2 「AI 데이터 기반 최적 입지 컨설팅 서비스」.
"어디가 좋은가"가 아니라 **"내 한도로 어디까지 가능한가"** 를 답하는 서비스.
스코프: 서울 / 카페·음식점 / 개발 14일 / 심사 마감 2026-08-03(월) 16:00.

## 스펙 문서 (단일 진실 원천)

- `docs/specs/최종_스펙문서.md` (v6.2) — 서비스 전체 설계. 모든 판단의 기준.
- `docs/specs/exploration_agent_spec_v2_1.md` — 결정공간 탐색 에이전트 상세.
- `docs/API_CONTRACT.md` — API 계약. **D3 동결 후에는 3인 합의 + 이 문서 수정 PR 없이 변경 금지.**
- `docs/TASKS.md` — 역할별 태스크 분해. 작업 시작 전 자신의 태스크 ID·의존성 확인.

문서와 코드가 어긋나면 **스펙 문서가 우선**하며, 스펙 변경은 팀 합의 후 문서부터 수정한다.

## 절대 불변 원칙 (스펙 §0-1, §1)

1. **모든 숫자는 결정적 계산이 만든다.** LLM은 (a) 탐색 축 우선순위 계획, (b) 구조화 결과의
   언어화, (c) 리스크 검증 반박문 생성만 담당. LLM의 수치 생성·재계산 절대 금지.
2. **서빙 경로에 ML 모델 없음.** LightGBM+SHAP은 오프라인 배치 검증(§12) 전용.
   `lightgbm`·`shap` 의존성은 `ai/` 배치 requirements에만 존재해야 한다.
3. **용어 컴플라이언스 (심사 감점 직결):**
   - 판정 4단계: `적합 / 조건부 적합 / 유의 / 범위 외` — "승인" 계열 단어 전면 금지
   - "심사역" → "리스크 검증", "조달 가능" → "자격 요건 부합 상품 확인(한도·승인은 기관 심사 사항)"
   - 자금 관련 "권장/추천드립니다" 금지 → 정보 서술형 ("~하면 …지표가 개선됩니다")
   - 상향 인사이트는 단독 노출 금지: 지속 후보 수 + 하향 안전 마진 + 고지 문구 동반 필수
   - 고지 문구: "본 정보는 공개 자료 기반 정보 제공이며 대출 권유·중개·자문이 아닙니다."
4. **좌표는 전부 WGS84** (프론트 계약), 금액은 **만원 단위 정수**, 데이터 기준일 상시 표기.
5. 임대료 라벨: "한국부동산원 ○○상권 분기 평균 (추정)" / 권리금 라벨: "연간 조사(전년 기준)".

## 저장소 구조·역할

| 경로 | 담당 | 스택 |
|---|---|---|
| `frontend/` | FE | React 18 + Vite + TS, 카카오맵 JS SDK, SSE |
| `backend/` | BE | Spring Boot 4.1 (Java 25) + PostgreSQL + Caffeine, SseEmitter |
| `ai/` | AI | Python 3.11 — 수집/전처리(pandas·geopandas)/적재 배치 + 평가 하네스(`make eval`) |
| `db/init/` | AI | 사전 적재 덤프 (compose 최초 기동 시 자동 실행) |
| `docs/` | 공통 | 스펙·태스크·API 계약·assumptions.md·심사_QA.md |

## Git 규칙

### ⛔ Claude attribution 금지 (하드 룰)

- 커밋 메시지·PR 본문에 **`Co-Authored-By: Claude ...` 라인을 절대 추가하지 않는다.**
- **`🤖 Generated with [Claude Code]` 등 attribution 문구도 금지.**
- `.claude/settings.json`의 `"includeCoAuthoredBy": false`가 1차 차단하며,
  `.githooks/commit-msg` 훅이 2차 차단한다 (clone 후 `git config core.hooksPath .githooks` 필수).
- 이 규칙은 다른 어떤 기본 동작보다 우선한다.

### 브랜치 전략

```
main ← develop ← frontend / backend / ai
```

- 작업은 자기 영역 브랜치(`frontend`/`backend`/`ai`)에서. 필요 시 `frontend/feat-지도` 등 하위 토픽 브랜치 허용.
- `develop`으로는 **PR로만** 병합 (CI: lint + 테스트 + docker build 통과 필수).
- `develop` push 시 통합 compose 테스트 후 `main`으로 자동 병합 (`.github/workflows/develop-ci.yml`).
- `main` 직접 push 금지.

### 커밋 컨벤션

```
[FE|BE|AI|CM] type: 요약 (한국어 허용)
예: [BE] feat: 해석적 프론티어 직입 계산 구현 (spec §2-1)
```
type: feat / fix / refactor / test / docs / chore / data

## 자주 쓰는 명령

```bash
docker compose up --build        # 통합 실행 → web http://localhost:3000, api :8080
cd frontend && npm install && npm run dev     # FE 개발 서버 (:5173, /api → :8080 프록시)
cd frontend && npm run lint && npm run build  # FE CI와 동일 검증
cd backend && ./gradlew test     # BE 테스트 (또는 docker build ./backend)
cd ai && ruff check .            # AI lint (CI와 동일)
cd ai && make eval               # AI 품질 평가 하네스 (D9~10 구현, §12-1)
```

## 개발 시 주의

- 결정적 도구 계층(`eligibility_filter/cost_calculator/score_lookup/frontier/reverse_check`)은
  순수 함수 + 단위 테스트 필수 (스펙 §5-1).
- /explore SSE: 템플릿 문장 즉시 송출 → LLM refine은 선택적 교체. LLM 장애 시 템플릿이 최종본
  (데모 무중단 원칙, exploration spec §2-5·§5).
- 인용은 검색이지 생성이 아니다: source_quote는 벡터DB 원문 청크 그대로, LLM 재작성 금지 (§5-4).
- 배치 산출물의 모든 가정은 `docs/assumptions.md`에 즉시 등재 (좌표계·정규화·폴백 규칙 등).

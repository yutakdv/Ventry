# CONTRIBUTING — 협업 규칙

3인(FE·BE·AI) 병렬 개발을 위한 규칙. 스펙 문서(`docs/specs/`)가 모든 판단의 기준이다.

## 1. 최초 세팅 (clone 직후 1회)

```bash
git clone <repo-url> && cd Ventry
git config core.hooksPath .githooks   # Claude co-author 차단 훅 활성화 (필수)
cp .env.example .env                  # 키 값 채우기
```

## 2. 브랜치 전략

```
main        제출·데모 기준 브랜치. 직접 push 금지. develop에서 자동 병합만.
develop     통합 브랜치. PR로만 병합 (CI 통과 필수).
frontend    FE 작업 브랜치 (React)
backend     BE 작업 브랜치 (Spring Boot)
ai          AI 작업 브랜치 (Python 배치·평가)
```

- 흐름: `frontend|backend|ai` → (PR + CI) → `develop` → (통합 compose 테스트) → `main` 자동 병합.
- 영역 브랜치 하위에 토픽 브랜치 허용: `frontend/feat-map`, `backend/fix-sse` 등.
  토픽 → 영역 브랜치는 자유 병합, 영역 → develop은 반드시 PR.
- 충돌 예방: 영역 간 공유 지점은 `docs/API_CONTRACT.md`와 `docker-compose.yaml`뿐이다.
  이 두 파일을 수정하는 PR은 3인 리뷰 필수.

### 브랜치 보호 설정 (리포 관리자 1회, GitHub)

```bash
gh api repos/{owner}/{repo}/branches/develop/protection -X PUT \
  -f "required_status_checks[strict]=true" \
  -f "required_status_checks[contexts][]=frontend-ci" \
  -f "required_status_checks[contexts][]=backend-ci" \
  -f "required_status_checks[contexts][]=ai-ci" \
  -F "enforce_admins=false" \
  -F "required_pull_request_reviews[required_approving_review_count]=1" \
  -F "restrictions=null"
# main: push 제한 (자동 병합 워크플로만 허용)
```

## 3. PR 규칙

- 대상: `develop`. 템플릿(`.github/PULL_REQUEST_TEMPLATE.md`) 체크리스트 필수.
- CI가 검증하는 것: **lint → 테스트 → 해당 영역 docker build**. 실패 시 병합 불가.
- 리뷰 1인 이상 (자기 영역 외 1인). 24시간 내 리뷰 원칙 — 14일 일정에서 PR 적체가 최대 리스크.
- PR 단위 = `docs/TASKS.md`의 태스크 ID 1~2개. 태스크 ID를 PR 제목에 표기.
  예: `[BE-04] 해석적 프론티어 + 조합 제약 조달 검증`

## 4. 커밋 컨벤션

```
[FE|BE|AI|CM] type: 요약
type: feat / fix / refactor / test / docs / chore / data
```

### ⛔ Claude attribution 금지

- `Co-Authored-By: Claude ...`, `🤖 Generated with Claude Code` 등 AI attribution 라인 금지.
- 차단 장치: `.claude/settings.json`(`includeCoAuthoredBy: false`) + `.githooks/commit-msg` 훅.
- PR 본문도 동일 적용. 리뷰어는 발견 시 수정 요청.

## 5. CI 구성 (.github/workflows/)

| 워크플로 | 트리거 | 내용 |
|---|---|---|
| `frontend-ci.yml` | `frontend` push · develop 대상 PR(frontend/** 변경) | npm lint + tsc/vite build + docker build |
| `backend-ci.yml` | `backend` push · develop 대상 PR(backend/** 변경) | gradle test/build + docker build |
| `ai-ci.yml` | `ai` push · develop 대상 PR(ai/** 변경) | ruff lint + docker build |
| `develop-ci.yml` | develop push·PR | `docker compose build` + 기동 스모크(api health·web 200) → push 시 main 자동 병합 |

각 영역 브랜치는 **자기 Dockerfile로 단독 docker 테스트가 가능**해야 한다
(`docker build ./frontend` 등). 루트 통합 테스트는 `docker compose up --build`.

## 6. 공유 계약 변경 절차

1. `docs/API_CONTRACT.md`는 **D3에 동결**. 이후 변경은 ① 이슈로 제안 → ② 3인 합의 →
   ③ 계약 문서 수정 PR(3인 리뷰) → ④ 각 영역 반영 순서만 허용.
2. DB 스키마(`db/init/` 덤프 구조)는 AI 담당이 오너. 변경 시 BE에 사전 공지.
3. 용어 컴플라이언스(CLAUDE.md §절대 불변 원칙 3)는 화면 문구·API reason_text·문서 전부에 적용.
   FE는 D11~12에 전체 화면 용어 스윕을 수행한다.

## 7. 일정 앵커 (스펙 §9)

- **D3**: API 계약 동결 + 목 데이터 E2E (체크포인트 1)
- **D10**: 기능 동결. 이후 신규 기능 금지, 버그픽스·QA·문서만
- **D11**: 외부 1인(비개발자) README 도슨트 3분 테스트
- **D13~14**: 기술설명서(PPT→PDF)·README 최종화, 제출 리허설 2회 (2026-08-03 16:00 마감)

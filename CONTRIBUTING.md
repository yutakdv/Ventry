# CONTRIBUTING — 협업 규칙

3인(FE·BE·AI) 병렬 개발을 위한 규칙. 스펙 문서(`docs/specs/`)가 모든 판단의 기준이다.

## 1. 최초 세팅 (clone 직후 1회)

```bash
git clone <repo-url> && cd Ventry
git config core.hooksPath .githooks   # Claude co-author 차단 훅 활성화 (필수)
cp .env.example .env                  # 키 값 채우기
```

## 1-1. IDE 설정

어떤 IDE든 자유. 커밋 훅·CI가 품질을 보증하므로 IDE는 개인 선택이다.

**IntelliJ (BE 권장)**
1. IntelliJ **2025.2 이상** 필요 (Java 25 언어 레벨 지원).
2. `File → Open` 으로 **`backend/` 디렉토리를 프로젝트로 열기** (루트를 열었다면
   `backend/build.gradle` 우클릭 → *Link Gradle Project*).
3. `Settings → Build Tools → Gradle`:
   - *Distribution*: **Wrapper** (저장소에 gradlew 9.5.1 고정 — IDE·CI·Docker 동일 버전)
   - *Gradle JVM*: **JDK 25** (없으면 같은 창에서 *Download JDK* → Temurin 25)
4. `Project Structure → Project SDK`: JDK 25 / Language level 25.
5. 코끼리 아이콘(⟳) **Gradle 재동기화** — 테스트 코드의 `@WebMvcTest` 등 import 오류는
   대부분 재동기화 전 stale 상태다. 재동기화 후에도 남으면 `File → Invalidate Caches`.
6. 판정 기준은 IDE가 아니라 `./gradlew build` / `docker build ./backend` — IDE 빨간줄이
   남아도 이 두 개가 그린이면 코드는 정상이다.

**VS Code (FE·AI)**: 확장 ESLint(FE)·Ruff(AI) 설치 권장. 저장 시 포맷은 강제하지 않는다.

## 2. 브랜치 전략

```
main        제출·데모 기준 브랜치. 직접 push 금지. develop에서 자동 병합만.
develop     통합 브랜치. PR로만 병합 (CI 통과 필수).
frontend    FE 작업 브랜치 (React)
backend     BE 작업 브랜치 (Spring Boot)
ai          AI 작업 브랜치 (Python 배치·평가)
```

### 작업 흐름 — **반드시 2단계를 거친다**

```
토픽 브랜치  ──(로컬 병합, PR 불필요)──▶  영역 브랜치      ──(PR + CI + 리뷰 1인)──▶  develop
be04-frontier                            frontend|backend|ai                          │
                                                                                       ▼
                                                                       (compose 스모크) main 자동 병합
```

- **토픽 브랜치에서 develop으로 직접 PR을 올리지 않는다.** develop으로 가는 PR의 head는
  항상 `frontend`·`backend`·`ai` 셋 중 하나다.
- 토픽 → 영역: PR 없이 병합해도 된다. 영역 → develop: **반드시 PR**.
- 영역 브랜치는 각자가 오너다. 자기 영역 브랜치에는 자유롭게 push한다.

**왜 2단계인가**: 영역 브랜치가 "develop에 내보낼 준비가 된 것"의 단일 창구가 된다.
토픽에서 직접 PR을 올리면 한 사람이 동시에 여러 PR을 열게 되고, 리뷰어는 그 영역의 작업이
어디까지 진행됐는지를 PR 목록으로 재구성해야 한다.

### 토픽 브랜치 이름

```
<태스크ID 소문자>-<슬러그>      예: be04-frontier · fe03-mapview · ai03-schema · cm07-readme
```

> ⚠️ **`backend/…`·`frontend/…`·`ai/…` 형태는 만들 수 없다.** 같은 이름의 브랜치가 이미
> 있어서 git이 거부한다 (`cannot lock ref: 'refs/heads/backend' exists`). 위 규칙을 쓸 것.

### 시작할 때 — 영역 브랜치를 먼저 최신화

```bash
git checkout backend && git pull                 # 자기 영역 브랜치
git merge origin/develop                         # develop의 남의 작업 흡수
git checkout -b be04-frontier                    # 토픽 브랜치 분기
```

작업이 끝나면:

```bash
git checkout backend && git merge be04-frontier  # 토픽 → 영역 (PR 불필요)
git push origin backend                          # 여기서 자기 영역 CI가 돈다
gh pr create --base develop --head backend       # 영역 → develop (PR·리뷰 필수)
git branch -d be04-frontier                      # 병합된 토픽 정리
```

- **CM(공통) 작업도 예외가 아니다.** 문서·CI·compose 등 영역이 없는 작업은 그 태스크의
  **담당자 영역 브랜치**를 경유한다 (TASKS.md의 CM 표는 태스크마다 담당을 지정한다).
  영역 브랜치를 우회하는 예외를 하나 만들면 규칙이 사실상 사라진다.
- **`Closes #<이슈번호>`는 영역 → develop PR 본문에 적는다.** 이슈 자동 종료 잡은 develop
  push 시점에 PR 본문을 파싱하므로, 토픽 → 영역 병합 커밋에 적으면 발동하지 않는다.
- 한 PR에 여러 태스크가 묶이면 `Closes #12` `Closes #13`처럼 줄을 나눠 모두 적는다.
- 충돌 예방: 영역 간 공유 지점은 `docs/API_CONTRACT.md`와 `docker-compose.yaml`뿐이다.
  이 두 파일을 수정하는 PR은 3인 리뷰 필수.

### 브랜치 보호 — 적용 완료 (2026-07-19)

| 브랜치 | 적용 규칙 |
|---|---|
| `develop` | 필수 상태 체크 4종(frontend-ci·backend-ci·ai-ci·compose-smoke, strict) + 승인 리뷰 1인 + 리뷰 코멘트 해결 필수 + force push·삭제 차단 |
| `main` | force push·삭제 차단 |

- `main`에 push 제한을 걸지 않은 이유: ① 개인 소유 리포는 push 허용자 지정(restrictions)을
  지원하지 않고, ② 자동 병합 워크플로(develop-ci의 promote 잡)가 GITHUB_TOKEN으로 main에
  push해야 하기 때문. **사람의 main 직접 push는 컨벤션으로 금지**하며 리뷰에서 걸러낸다.
- 규칙 변경이 필요하면: `gh api repos/yutakdv/Ventry/branches/<branch>/protection` (관리자만).

## 3. PR 규칙

- **base = `develop`, head = 영역 브랜치(`frontend`·`backend`·`ai`).**
  토픽 브랜치를 head로 하는 PR은 리뷰에서 반려한다 (§2 작업 흐름).
- 템플릿(`.github/PULL_REQUEST_TEMPLATE.md`) 체크리스트 필수.
- CI가 검증하는 것: **lint → 테스트 → 해당 영역 docker build**. 실패 시 병합 불가.
- 리뷰 1인 이상 (자기 영역 외 1인). 24시간 내 리뷰 원칙 — 14일 일정에서 PR 적체가 최대 리스크.
- PR 단위 = `docs/TASKS.md`의 태스크 ID 1~2개. 태스크 ID를 PR 제목에 표기.
  예: `[BE-04] 해석적 프론티어 + 조합 제약 조달 검증`
- PR 본문에 `Closes #<이슈번호>` 표기 — 병합 시 태스크 이슈 자동 종료 + 보드 Done 이동
  (대시보드 운영: `docs/BOARD.md`).

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
| `frontend-ci.yml` | `frontend` push(frontend/** 변경) · develop 대상 모든 PR | npm ci + lint + tsc/vite build + docker build |
| `backend-ci.yml` | `backend` push(backend/** 변경) · develop 대상 모든 PR | gradle test/build (Java 25) + docker build |
| `ai-ci.yml` | `ai` push(ai/** 변경) · develop 대상 모든 PR | ruff lint + docker build |
| `develop-ci.yml` | develop push·PR | `docker compose build` + 기동 스모크(api health·web 200) → push 시 main 자동 병합 |

> PR에서는 세 CI가 **경로와 무관하게 항상 실행**된다. 브랜치 보호의 필수 상태 체크로 등록된
> 워크플로가 path 필터에 걸려 실행되지 않으면 체크가 pending으로 남아 병합이 영구히 막히기
> 때문이다. push 트리거에만 path 필터를 둔다.

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

# 팀원 온보딩 — 첫날 30분 가이드

Ventry: "내 한도로 어디까지 가능한가"를 답하는 입지 컨설팅 에이전트.
KB AI Challenge 출품 · 마감 **2026-08-03(월) 16:00** · 개발 14일 (D1 = 2026-07-20).

## 0. 세팅 (10분, 전원 공통)

```bash
git clone https://github.com/yutakdv/Ventry.git && cd Ventry
git config core.hooksPath .githooks     # ⛔ Claude co-author 차단 훅 — 필수
cp .env.example .env                    # 🔑 표시 키만 발급받아 붙여넣기 (파일 주석에 발급 링크)
docker compose up --build               # → http://localhost:3000 뜨면 세팅 끝
```

- 자기 역할 브랜치에서 작업: `git switch frontend` (또는 `backend` / `ai`)
- IDE: 자유. BE는 IntelliJ 2025.2+ 권장 — 설정은 [CONTRIBUTING §1-1](../CONTRIBUTING.md)

## 1. 읽기 순서 (15분)

| 순서 | 문서 | 왜 |
|---|---|---|
| 1 | [CLAUDE.md](../CLAUDE.md) (5분) | 절대 불변 원칙 — 숫자는 결정적 계산·용어 금지어·Git 규칙. **전원 암기 수준** |
| 2 | 자기 문서: [tasks/FRONTEND.md](tasks/FRONTEND.md) / [tasks/BACKEND.md](tasks/BACKEND.md) / [tasks/AI.md](tasks/AI.md) (7분) | 내 태스크 체크리스트·DoD·PR 슬라이스 |
| 3 | [API_CONTRACT.md](API_CONTRACT.md) 훑기 (3분) | 팀 간 유일한 계약 — D3 동결 |
| 시간 나면 | `docs/specs/` 스펙 원본 · [TASKS.md](TASKS.md) 의존 관계 | 깊이 |

## 2. 오늘 할 일 (D1)

- **FE**: [FE-01] 카카오맵 앱키 발급 + 지도·SSE 스파이크
- **BE**: [BE-01] API 계약 초안 검토 시작 (D3 동결 목표)
- **AI**: [AI-01] 데이터 실사 — **공공데이터포털 활용신청은 오전에 즉시** (승인 1~2일)
- 전원: 대시보드에서 자기 이슈에 assignee 지정 → In Progress로 이동
  (보드: https://github.com/users/yutakdv/projects/1 — Roadmap 뷰가 간트 차트)

## 3. 협업 루틴

- **작업 흐름**: 자기 브랜치 커밋 → `develop` 대상 PR (제목 `[FE-02] ...`, 본문 `Closes #이슈번호`)
  → CI 4종(frontend-ci·backend-ci·ai-ci·compose-smoke) 그린 + 리뷰 1인 → 병합
  → main 자동 병합·보드 자동 Done. **브랜치 보호 적용됨** — CI 실패 시 병합 물리적으로 불가.
- **데일리 10분**: 보드 Roadmap에서 오늘 D-day 확인, 밀린 항목은 TASKS.md 폴백 규칙 결정.
- **리뷰는 24시간 내**: 14일 일정에서 PR 적체가 최대 리스크.
- 공유 지점은 단 두 개: `docs/API_CONTRACT.md`(D3 후 변경은 3인 합의)와 `docker-compose.yaml`.

## 4. 하지 말 것 (즉시 리뷰 반려 대상)

1. 커밋·PR에 `Co-Authored-By: Claude` / `Generated with Claude Code` — 훅이 막지만 우회 금지
2. `main` 직접 push (자동 병합 전용)
3. LLM이 수치를 만들게 하는 코드 — 숫자는 결정적 계산만 (CLAUDE.md 원칙 1)
4. 화면·문서에 "승인"·"조달 가능합니다"·자금 관련 "추천드립니다" — 용어 컴플라이언스
5. 새 가정을 `docs/assumptions.md` 등재 없이 코드에 하드코딩

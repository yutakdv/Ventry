# 태스크 대시보드 운영 — GitHub Projects v2

## 왜 GitHub Projects인가 (도구 비교)

| 후보 | 간트/로드맵 | PR·CI 연동 | 자동화 | 3인·14일 적합성 |
|---|---|---|---|---|
| **GitHub Projects v2 (채택)** | Roadmap 뷰 = 간트 차트 (Start/End 날짜 필드) | **이슈↔PR↔CI가 한 몸** — `Closes #n` 병합 시 자동 Done | 기본 Workflows + Actions | ◎ 도구 이동 0, 무료 |
| Jira (간트=Timeline) | ◎ | 앱 연동 설정 필요 | ◎ | △ 세팅·관리 오버헤드가 14일 일정에 과잉 |
| Notion (보드/타임라인) | ○ | 없음 (수동 링크) | 제한적 | △ PR 상태를 사람이 옮겨야 함 — 마감 주간에 반드시 어긋남 |

핵심 근거: 우리 진행률의 원천 데이터는 **PR 병합**이다(CONTRIBUTING §3 — PR = 태스크 ID 1~2개).
PR이 곧 진행률이 되게 하려면 보드가 GitHub 안에 있어야 하며, 그러면 "보드 업데이트"라는
별도 노동이 사라진다. Jira/Notion은 이 동기화를 사람이 하게 된다.

> Notion을 굳이 쓰고 싶다면: Claude의 Notion 커넥터(claude.ai 연결 설정에서 승인 필요)로
> 주간 스냅숏을 내보내는 보조 용도로만. 원천 보드는 GitHub 유지 권장.

## 구축 (1회, 5분)

```bash
brew install gh jq
gh auth login && gh auth refresh -s project
git push -u origin main develop frontend backend ai   # 아직 안 했다면
./scripts/setup_board.sh                              # D1=2026-07-20 기본
# START_DATE=2026-07-21 ./scripts/setup_board.sh      # 시작일 변경 시
```

스크립트가 자동으로: 라벨(FE/BE/AI/CM·P0/P1) → 마일스톤 CP1~CP6(마감일 계산) →
**태스크 이슈 27건**(문서 링크·일정 포함) → Project 생성 + Start/End 날짜 입력까지 수행.
⚠️ 재실행 시 이슈 중복 — 1회만.

마무리(웹 UI 1분): ① Project에서 **New view → Roadmap → Date fields = Start/End** (간트 완성)
② Workflows에서 "Item closed → Done", "Pull request merged → Done" ON
③ Settings에 `PROJECT_URL`(Variable)·`PROJECT_PAT`(Secret) 등록 → 신규 이슈/PR 자동 보드 추가
④ 이슈별 담당자 지정.

## 일상 운영 (자동화 후 사람이 하는 일)

1. **작업 시작**: 자기 이슈를 In Progress로 드래그 (하루 1번, 유일한 수동 조작)
2. **PR 생성**: 본문에 `Closes #이슈번호` — 이후는 전부 자동:
   PR 열림 → 보드 자동 추가 / CI 상태 PR에 표시 / 병합 → 이슈 자동 종료 → Done 이동
3. **데일리 10분**: Roadmap 뷰에서 오늘 D-day 열 확인 — 밀린 막대는 그 자리에서
   일정 드래그로 조정 (TASKS.md의 폴백 규칙 적용 여부 결정)
4. **체크포인트(CP1~CP6)**: 마일스톤 페이지의 진행률 바가 곧 게이트 현황
   — CP 마감일에 미완 이슈가 있으면 TASKS.md "일정 리스크 대응" 발동

## 뷰 구성 권장

| 뷰 | 타입 | 용도 |
|---|---|---|
| Roadmap | Roadmap (Start/End) | 간트 — 전체 일정·의존 확인 (기본 화면) |
| Board | Board (Status) | 데일리 — Todo / In Progress / Done |
| By Role | Table, 라벨 그룹 | 팀원별 잔여량 확인 (FE/BE/AI 밸런스) |
| By CP | Table, Milestone 그룹 | 체크포인트 게이트 점검 |

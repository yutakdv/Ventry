#!/usr/bin/env bash
# =============================================================================
# Ventry 태스크 보드 원커맨드 구축 — GitHub Projects v2 (로드맵/간트 뷰용)
#
# 하는 일:
#   1) 라벨(FE/BE/AI/CM, P0/P1) 생성
#   2) 마일스톤 CP1~CP6 생성 (START_DATE 기준 마감일 자동 계산)
#   3) docs/TASKS.md의 27개 태스크를 이슈로 생성 (라벨·마일스톤·문서 링크 포함)
#   4) Project v2 생성 + Start/End 날짜 필드 생성 + 전 이슈 추가·일정 입력
#      → 웹 UI에서 Roadmap 뷰를 열면 즉시 간트 차트로 보인다
#
# 사전 조건:
#   brew install gh
#   gh auth login                 # 계정 인증
#   gh auth refresh -s project    # Projects v2 권한 (1회)
#   저장소가 push 되어 있어야 함 (origin 기준)
#
# 사용:
#   ./scripts/setup_board.sh                     # D1=2026-07-20 기본
#   START_DATE=2026-07-21 ./scripts/setup_board.sh
#
# ⚠️ 재실행하면 이슈가 중복 생성된다. 1회 실행 전제 (실패 지점부터 수동 보완).
# =============================================================================
set -euo pipefail

command -v gh >/dev/null || { echo "gh CLI가 필요합니다: brew install gh && gh auth login"; exit 1; }
command -v jq >/dev/null || { echo "jq가 필요합니다: brew install jq"; exit 1; }

START_DATE="${START_DATE:-2026-07-20}"   # D1 날짜
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
OWNER=${REPO%%/*}
PROJECT_TITLE="${PROJECT_TITLE:-Ventry D1–D14}"

# D{n} → 날짜 (D1 = START_DATE). GNU date / BSD(macOS) date 모두 지원
d() {
  local n=$(( $1 - 1 ))
  if date -d "$START_DATE" +%F >/dev/null 2>&1; then
    date -d "$START_DATE ${n} days" +%F
  else
    local off; off=$([ "$n" -ge 0 ] && echo "+${n}d" || echo "${n}d")
    date -j -v"$off" -f %Y-%m-%d "$START_DATE" +%F
  fi
}

echo "== [1/4] 라벨 생성 (repo: $REPO)"
gh label create FE --color 1f6feb --description "Frontend" 2>/dev/null || true
gh label create BE --color 2da44e --description "Backend" 2>/dev/null || true
gh label create AI --color 8250df --description "AI/Data" 2>/dev/null || true
gh label create CM --color 6e7781 --description "공통" 2>/dev/null || true
gh label create P0 --color d1242f --description "필수" 2>/dev/null || true
gh label create P1 --color fb8f44 --description "여유 시" 2>/dev/null || true

echo "== [2/4] 마일스톤 CP1~CP6 생성"
create_ms() { # title, D-day
  gh api "repos/$REPO/milestones" -f title="$1" -f due_on="$(d "$2")T09:00:00Z" >/dev/null 2>&1 \
    || echo "   (이미 존재: $1)"
}
create_ms "CP1 · 계약 동결·목 E2E (D3)" 3
create_ms "CP2 · 실데이터 전환 (D6)" 6
create_ms "CP3 · 에이전트 동작 (D8)" 8
create_ms "CP4 · 기능 동결 (D10)" 10
create_ms "CP5 · QA·평가 확정 (D12)" 12
create_ms "CP6 · 제출 (D14)" 14

ms_title() { # CP코드 → 실제 마일스톤 제목
  gh api "repos/$REPO/milestones" --jq ".[] | select(.title | startswith(\"$1\")) | .title"
}

echo "== [3/4] 태스크 이슈 27건 생성"
# ID|라벨|CP|D시작|D끝|제목   (근거: docs/TASKS.md · docs/tasks/*.md)
TASKS='CM-01|CM|CP1|0|0|리포·브랜치·CI·compose 세팅 + 브랜치 보호
AI-01|AI|CP1|1|1|데이터 실사 — 스키마 확정·구획도 정합성 대조·좌표계·θ 문헌
FE-01|FE|CP1|1|1|카카오맵 앱키·도메인 등록 + 지도·SSE 스파이크
AI-02|AI|CP1|2|2|원천 수집 완료 (상권 7종·교통·임대료·인허가·정책자금)
BE-01|BE|CP1|1|3|API 계약 동결 + 목 6종 + SSE 골격
AI-03|AI|CP1|3|3|스키마 DDL + 목 데이터 덤프 (db/init)
CM-02|CM|CP1|3|3|CP1 — 목 데이터 E2E 합동 점검
FE-02|FE|CP2|3|4|화면 1 진단 (폼+자연어 하이브리드, 데모 프로필 버튼)
AI-04|AI|CP2|4|5|공간 조인 3단계 (검증 3종·폴백 플래그)
BE-02|BE|CP2|4|5|DB 연동 + Caffeine 캐시 (탐색당 쿼리 1회 원칙)
AI-05|AI|CP2|5|6|산출 테이블 — 비용 4블록·이중 필터·점수화 w1~w5
BE-03|BE|CP2|5|6|결정적 도구 계층 5종 + 단위 테스트
AI-06|AI|CP2|6|6|정책자금 구조화 + 전건 검수 + 벡터DB 청크
FE-03|FE|CP2|4|6|화면 2·3 골격 (시나리오 SSE·지도 마커·근거 패널·슬라이더)
BE-04|BE|CP3|7|7|해석적 프론티어(진입) + 조합 제약 조달 검증
FE-04|FE|CP3|7|8|탐색 인사이트 카드·역방향 판정 UI
BE-05|BE|CP3|8|8|탐색·검증 에이전트 (지속 프론티어·plan·SSE·리스크 1왕복)
BE-06|BE|CP4|9|10|P1 — RAG 원문 인용·근거문 캐시·개인화
FE-05|FE|CP4|9|10|검증 패널·반응성(<100ms)·프론티어 미니 차트
AI-07|AI|CP4|9|10|평가 하네스 — 골드셋 12~20종·make eval 5개 스위트
AI-08|AI|CP4|9|10|LightGBM+SHAP 교차 검증 (프로토콜·게이트 사전 고정)
CM-03|CM|CP4|10|10|CP4 — 기능 동결 + RAG 구현/이월 판정
BE-07|BE|CP5|11|12|통합 QA·하드닝 (LLM 전면 차단 QA 포함)
FE-06|FE|CP5|11|12|마감 스윕 — 용어 컴플라이언스·데모 이미지 3종 캡처
AI-09|AI|CP5|11|12|make eval 최종 → 게이트 판정·부록 1·2 확정
CM-04|CM|CP5|11|11|외부 1인 README 도슨트 3분 테스트
CM-05|CM|CP6|13|14|기술설명서 PPT→PDF·최종 검수·제출 리허설 2회'

role_doc() {
  case "$1" in
    FE) echo "docs/tasks/FRONTEND.md" ;;
    BE) echo "docs/tasks/BACKEND.md" ;;
    AI) echo "docs/tasks/AI.md" ;;
    *)  echo "docs/TASKS.md" ;;
  esac
}

ISSUE_ROWS=()  # "url|Dstart|Dend"
while IFS='|' read -r id role cp ds de title; do
  [ -z "$id" ] && continue
  labels="$role,P0"; [ "$id" = "BE-06" ] && labels="$role,P1"
  body="- 일정: D${ds}~D${de} ($(d "$ds") ~ $(d "$de"))
- 상세 체크리스트: \`$(role_doc "$role")\` 의 ${id} 섹션
- 총괄·의존 관계: \`docs/TASKS.md\`
- 완료: PR 본문에 \`Closes #<이 이슈 번호>\` → 병합 시 자동 종료 + 보드 Done 이동"
  url=$(gh issue create --repo "$REPO" --title "[$id] $title" --body "$body" \
        --label "$labels" --milestone "$(ms_title "$cp")")
  ISSUE_ROWS+=("$url|$(d "$ds")|$(d "$de")")
  echo "   $url  [$id]"
done <<< "$TASKS"

echo "== [4/4] Project v2 생성 + Roadmap용 날짜 필드"
number=$(gh project create --owner "$OWNER" --title "$PROJECT_TITLE" --format json | jq -r .number)
project_id=$(gh project view "$number" --owner "$OWNER" --format json | jq -r .id)
gh project field-create "$number" --owner "$OWNER" --name "Start" --data-type DATE >/dev/null
gh project field-create "$number" --owner "$OWNER" --name "End"   --data-type DATE >/dev/null
fields_json=$(gh project field-list "$number" --owner "$OWNER" --format json)
start_fid=$(echo "$fields_json" | jq -r '.fields[] | select(.name=="Start") | .id')
end_fid=$(echo "$fields_json"   | jq -r '.fields[] | select(.name=="End")   | .id')

for row in "${ISSUE_ROWS[@]}"; do
  IFS='|' read -r url sdate edate <<< "$row"
  item_id=$(gh project item-add "$number" --owner "$OWNER" --url "$url" --format json | jq -r .id)
  gh project item-edit --project-id "$project_id" --id "$item_id" --field-id "$start_fid" --date "$sdate" >/dev/null
  gh project item-edit --project-id "$project_id" --id "$item_id" --field-id "$end_fid"   --date "$edate" >/dev/null
done

cat <<EOF

✅ 완료. 마무리는 웹 UI에서 1분:
  1) https://github.com/users/$OWNER/projects/$number 접속
  2) [+ New view] → Roadmap → Date fields를 Start/End로 지정  ← 간트 차트 완성
  3) Project 설정 → Workflows: "Item closed → Done", "Pull request merged → Done" ON
  4) 신규 이슈/PR 자동 추가: 리포 Settings → Variables에 PROJECT_URL, Secrets에
     PROJECT_PAT(project 스코프 PAT) 등록 → .github/workflows/project-automation.yml 활성화
  5) 각 이슈에 담당자(assignee) 지정
EOF

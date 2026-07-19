# PR: [태스크ID] 제목

## 무엇을 (docs/TASKS.md 태스크 ID 명시)

-

## 어떻게 확인했나

- [ ] 로컬 lint/테스트 통과 (명령·결과 요약: )
- [ ] `docker build ./<영역>` 성공
- [ ] (스키마·계약 변경 시) `docker compose up --build` 스모크 확인

## 체크리스트

- [ ] `docs/API_CONTRACT.md` 변경 없음 — 변경했다면 3인 리뷰 요청함
- [ ] 용어 컴플라이언스 준수 (승인 계열 금지 / 판정 4단계 / 고지 문구 — CLAUDE.md 참고)
- [ ] 새 가정·폴백은 `docs/assumptions.md`에 등재함
- [ ] 커밋·PR 본문에 Claude co-author/attribution 라인 없음

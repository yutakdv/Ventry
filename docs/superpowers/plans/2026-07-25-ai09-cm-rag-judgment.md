# AI-09 최종 판정 + CM-03 RAG 판정 재정의 + CM-05 착수 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** BE 산출물을 기다리지 않고 닫을 수 있는 전부를 닫는다 — AI-09(#25)를 매칭 행 의존 없이 완결하고, CM-03(#22) RAG 판정의 전제가 실제 코드와 어긋난 부분을 판정 **전에** 바로잡고, CM-05(#27) 기술설명서를 RAG 판정과 무관한 장부터 착수한다.

**Architecture:** 세 갈래다. ①**AI-09 탈의존화** — 부록 1의 자격 매칭 행은 값이 아니라 "BE 소관" 표기로 확정해 #77 지연이 AI-09를 막지 못하게 한다. 부록 2는 게이트 B가 **ρ 경로**로 성립했다는 사실(방향 일치 0/3)을 전면 공개하는 문안으로 쓴다. ②**CM-03 판정 재정의** — "RAG 구현/이월" 단일 판정을 「①`finance_product` 26건 서빙 결선」과 「②`source_quote` 인용 층」 두 판정으로 분리하고, 이월 문안 B의 사실 오류를 지금 고친다. ③**CM-05 선착수** — 판정 결과에 문안이 좌우되지 않는 장(매핑 표·데이터·평가·사업성)부터 쓴다.

**Tech Stack:** Python 3.11 (`ai/.venv`) · lightgbm · shap · scikit-learn · pandas · matplotlib(Agg) · make · ruff · pytest

## Global Constraints

- **스펙 §0-1**: 모든 숫자는 결정적 계산이 만든다. 부록 수치는 `make eval` 산출물을 그대로 옮긴다 — 문서 작성 중 재계산·반올림 조정 금지.
- **스펙 §12-3 게이트는 사전 등록분이다.** 결과에 맞춰 임계치·문안 수위를 조정하지 않는다. 게이트 B의 **경로**(ρ / 방향)를 병기한다 — [`model.py:46`](../../../ai/eval/suites/model.py#L46) `gate_path`.
- **리스크 #19**: 결과가 좋아도 서술은 "설계 교차 검증" 한정. **"매출 예측" 표현 전면 금지.**
- **리스크 #17**: 문서·코드 괴리 금지. 구현되지 않은 것을 구현된 것처럼 쓰지 않는다 — 이 계획의 CM-03 갈래가 정확히 이 리스크의 재발 차단이다.
- **용어 컴플라이언스**: 판정 4단계 `적합/조건부 적합/유의/범위 외`. "승인" 계열 금지. 자금 관련 "권장/추천" 금지. 고지 문구 동반.
- **커밋**: `[AI|CM] type: 요약`. **`Co-Authored-By: Claude`·`Generated with Claude Code` 절대 금지.**
- **브랜치 2단계**: 토픽 → (로컬 병합) → `ai` → (PR) → `develop`.

---

## 사전 조사에서 확정된 사실 (계획의 근거)

실행자는 재확인 없이 사실로 취급해도 된다. 2026-07-25 실측.

| # | 사실 | 확인 방법 |
|---|---|---|
| G1 | **서빙은 `finance_product`를 읽지 않는다.** 백엔드의 DB 쿼리는 `v_candidate_area`([CandidateRepository.java:19](../../../backend/src/main/java/com/ventry/api/serving/CandidateRepository.java#L19))와 `data_source_meta`([DataMetaRepository.java:13](../../../backend/src/main/java/com/ventry/api/serving/DataMetaRepository.java#L13)) 둘뿐 | `grep -rn "SELECT" backend/src/main --include="*.java"` |
| G2 | 자금 상품은 [DemoProducts.java:31](../../../backend/src/main/java/com/ventry/api/serving/DemoProducts.java#L31)의 **하드코딩 2건**이 유일한 출처. `EligibilityFilter.qualify`·`FundingCheck.cover` 모두 이 2건만 본다 | `grep -rn "new FundingProduct\|products.all()"` |
| G3 | 데모 2건의 한도(3,000/1,500)는 데모 서사 수치(보수 6,500 / 적극 8,000)에 맞춰 **역산된 값**이다 → 실 26건 전환 시 데모 숫자가 움직인다 | [DemoProducts.java:13](../../../backend/src/main/java/com/ventry/api/serving/DemoProducts.java#L13) 주석 |
| G4 | 적재는 완비: `finance_product` **26건 전건** `doc_chunk_ref` 채워짐(NULL 0건·dangling 0건), `finance_doc_chunk` **14청크**. 26건이 14청크를 공유한다 | `db/init/20_finance.sql` 파싱 |
| G5 | 청크 14개 중 **10개는 완전 클린**, KB 4개만 깨진 글자 0.88~2.03%. 위치는 전부 **PDF 인쇄 헤더/푸터 줄**(`2026. 7. 21. 오전 1:03…`, `N페이지/M페이지https://…`)이고 본문은 정상 | 청크별 모지바케 비율 측정 |
| G6 | 게이트 판정 = **B**, 경로 = **"순위상관(ρ) 충족 · 방향 일치 미달"**. R² 0.178 / ρ 0.514 / 방향 **0/3축** | `ai/eval/out/metrics.json` |
| G7 | 방향 미달의 주범은 `store_per_10k_m2` — mean&#124;SHAP&#124; 0.505로 **중요도 1위**인데 설계 부호 −1 대비 관측 +1(부호 반전). raw Spearman도 +0.481 | 동 `model.direction.by_feature` |
| G8 | [docs/tasks/AI.md:120](../../tasks/AI.md#L120)의 B 문안("방향 일치 중심 축약")은 **방향 경로를 전제한 서술**이라 ρ 경로 판정에 그대로 쓰면 사실과 어긋난다. AI-08이 [`model.py:46-59`](../../../ai/eval/suites/model.py#L46-L59)에 `gate_path` 병기 규칙으로 이미 예견해 둠 | 코드 주석 |
| G9 | [심사_QA.md:61](../../심사_QA.md#L61) 문안 B의 *"남은 것은 서빙 경로에서 이 청크를 읽어 오는 연결뿐"* 은 **G1·G2와 모순**. 청크 연결 앞에 상품 테이블 결선이 남아 있다 | G1·G2 대조 |
| G10 | 하네스는 `matching`을 이미 **BE 소관 패스스루**로 명문화 — 부록 1의 매칭 행을 AI가 산출하지 않는 것이 설계다 | [`run.py:20`](../../../ai/eval/run.py#L20) |
| G11 | 기술설명서는 리포에 **초안이 존재하지 않는다**(전 리포 검색 0건). CM-05가 최대 미착수 덩어리 | `find . -iname "*기술설명서*"` |
| G12 | grounding 지표는 `ai/data/interim/funding_docs/*.txt` **원문 대조**라 `20_finance.sql` 청크를 손봐도 수치가 흔들리지 않는다 | [`grounding.py`](../../../ai/eval/suites/grounding.py) `load_source_text` |

---

## 자체 채택 결정 (근거를 문서에 남긴다)

| # | 결정 | 근거 |
|---|---|---|
| D1 | 부록 1의 「자격 매칭 P/R」 **행은 유지하되 값 자리에 "결정적 계층 — BE 단위·통합 테스트 소관(#77)" 표기 + 각주**. #77 도착 시 수치로 교체 | G10. 스펙 §12-1이 요구하는 것은 그 축의 존재이며, AI 하네스가 산출하지 않는 것은 설계 결정이지 결손이 아니다. 이 처리로 AI-09가 BE 일정에서 분리된다 |
| D2 | 부록 2는 **"순위상관 중심 축약 + 방향 3축 미달 전면 공개"** 로 쓴다. AI.md:120의 "방향 일치 중심" 문구는 ρ 경로에 해당하지 않음을 판정 문서에 1줄 명시 | G6·G8. 사전 등록 게이트의 취지는 결과를 보고 서술 수위를 고르지 않는 것 — 경로가 다르면 문안도 경로를 따라야 한다 |
| D3 | `store_per_10k_m2` 부호 반전은 **내생성**으로 서술하고, 이를 설계 원칙 박스 ①("서빙에 설명 불가능한 모델을 두지 않았다")의 **실증 근거로 승격**한다 | G7. 좋은 상권이라 점포가 몰린 것이지 점포가 몰려 매출이 오른 게 아니다. 설계의 w3 음수 부호는 "같은 상권에서 점포가 더 늘면 나빠진다"는 조건부 진술이고 횡단면 상관은 반대로 나온다 |
| D4 | CM-03 판정을 **①상품 테이블 결선 / ②인용 층** 둘로 분리. ②는 ①의 함수이므로 단독 판정하지 않는다 | G1·G2. ①이 없으면 ②는 논리적으로 불가능 |
| D5 | 문안 B의 사실 오류(G9)를 **판정 전에** 수정한다. "판정 후에 문안을 새로 쓰지 않는다"는 원칙은 **수위 조정 금지**이지 사실 오류 방치가 아니다 | 리스크 #17. 사전 작성의 목적은 결과를 보고 서술을 고르는 것을 막는 것이며, 사실 정정은 그 반대 방향 |
| D6 | 청크 인쇄 헤더/푸터 정제는 **하되**, "원문 그대로" 원칙과의 관계를 assumptions.md에 등재한다. LLM 재작성이 아니라 PDF 인쇄 아티팩트 제거임을 명시 | G5·G12. 이월하더라도 심사위원이 `20_finance.sql`을 열 수 있고, 지표에는 영향이 없어 안전하다 |
| D7 | CM-05는 **자금 매칭 장·탐색 장을 D10 이후로 미룬다** | D4의 판정 결과에 따라 두 장의 문안이 갈린다. 지금 쓰면 확실히 두 번 쓴다 |

---

## Task 1 — AI-09 지표 재산출 (`make eval` 최종 실행)

- [x] `cd ai && PATH="$PWD/.venv/bin:$PATH" make eval` 완주
- [x] 직전 `metrics.json`과 diff — a31337c(정책자금 원문 텍스트 리포 편입) 이후 첫 완주이므로 **grounding verbatim 1.0 유지**와 **model seed 고정 재현**을 확인
- [x] 산출물 4종 갱신 확인: `metrics.json` / `extraction.png` / `sensitivity.png` / `shap_summary.png`
- **DoD**: 게이트 판정과 `gate_path`가 재현되고, 변동이 있으면 변동 사유가 설명된다

## Task 2 — CM-03 사전 작업: 문안 B 사실 수정 + 판정 체크리스트 교체

- [x] [심사_QA.md](../../심사_QA.md) 문안 B에서 "남은 것은 청크 연결뿐" / "현재 버전은 출처 기관·문서명·기준일을 표기" 두 문장을 G1·G2 사실에 맞게 교체
- [x] 판정 체크리스트 4줄을 **판정 ①/② 분리 구조**로 교체 — ①`finance_product` 26건이 서빙에 반영되는가 → 아니면 ②는 자동 이월
- [x] **시각 컷 명시**: D9(2026-07-28) 자정까지 `develop` 미병합이면 자동 이월 (D10 기능 동결 직전 실데이터 전환의 파급 G3을 감안)
- [x] 수정 사유를 문서 안에 1줄 남긴다 (D5의 "사실 정정 ≠ 수위 조정")
- **DoD**: CP4에서 5분 안에 판정 가능한 상태 + 어느 분기든 즉시 인용 가능한 문안 2종

## Task 3 — 부록 1·2 원고 작성

- [x] `docs/부록1_2_평가성적표.md` 신설 — 기술설명서(CM-05)에 그대로 이식할 원고
- [x] **부록 1**: extraction / grounding / sensitivity / model 4행 + matching 행(D1 표기) + 각 행에 재현 명령·데이터 기준일
- [x] **부록 2**: 프로토콜(사전 등록) → 결과(R²·ρ·WAPE·MAE·베이스라인) → **게이트 B / ρ 경로 명시** → 방향 0/3 전면 공개 → D3 내생성 해석 → 한계 문구
- [x] "매출 예측" 표현 부재 확인 (리스크 #19), 판정 4단계 용어 준수
- [x] 게이트 B 문안 불일치(G8)를 판정 근거로 1줄 기록
- **DoD**: 수치가 전부 `metrics.json`에서 그대로 온 것이고, CM-05가 편집 없이 흡수 가능

## Task 4 — README 재현 3줄 유효성 검증

- [x] 리포를 임시 디렉터리에 클린 클론 → `cd ai && make eval` 이 문서대로 도는지 실측
- [x] 실패 시 [README.md:36](../../../README.md#L36) 3줄 섹션을 실제 절차에 맞게 정정 (venv·requirements·데이터 경로)
- **DoD**: 심사위원이 README만 보고 재현 시도 시 막히지 않는다

## Task 5 — 청크 인쇄 아티팩트 정제 (D6)

- [x] [`ai/batch/load/finance.py`](../../../ai/batch/load/finance.py)에 헤더/푸터 줄 필터 추가 (`^\d{4}\. \d+\. \d+\..*\d+:\d+`, `^\d+페이지/\d+페이지https?://`)
- [x] `python -m batch.load finance` 재실행 → **diff가 `finance_doc_chunk` 텍스트에만 국한되는지 확인** (rate·rate_type·rate_note 등 상품 필드 무변동)
- [x] `make eval-grounding` 재실행으로 지표 무변동 확인 (G12)
- [x] `docs/assumptions.md`에 정제 규칙 등재 — LLM 재작성이 아님을 명시
- **DoD**: 청크 모지바케 0%, 상품 필드 diff 0, grounding 지표 불변

## Task 6 — CM-05 기술설명서 착수 (판정 무관 장)

- [x] 문서 골격 생성 + 장 번호 확정 (본문 15~20 / 부록 10~15, 스펙 §0-6-0)
- [x] 1장 매핑 표 (§1-1) — 주제 요구사항 대조
- [x] 데이터 장 — 출처·해상도·기준일, 설계 원칙 박스 ③
- [x] 평가 장 — Task 3 원고 이식, 설계 원칙 박스 ①·⑤
- [x] 사업성·기대효과 1페이지 (§0-7) — KB 결합 프레이밍, 주최사 제품 공격 프레임 금지
- [x] **자금 매칭 장·탐색 장은 착수하지 않는다** (D7)
- **DoD**: 판정과 무관한 장이 초안 완료, D10 이후 남은 장만 쓰면 되는 상태

---

## 실행 순서

```
Task 1 (지표 재산출)
   ├→ Task 2 (문안 B 수정)      ← Task 1과 독립, 병행 가능
   ├→ Task 3 (부록 원고)         ← Task 1 결과 필요
   ├→ Task 4 (재현 검증)         ← Task 1 이후
   └→ Task 5 (청크 정제)         ← Task 1 이후(지표 대조용)
Task 6 (CM-05)                   ← Task 3 완료분을 흡수
```

## 이 계획이 다루지 않는 것

- `finance_product` 26건 서빙 결선, #76 지역 정규화, #82 rate NULL 정책 — **BE 소관**. 이 계획은 그 결과를 판정 입력으로만 받는다.
- #77 자격 매칭 P/R 골드 — BE 산출. 도착 시 부록 1의 D1 표기를 수치로 교체하는 것이 유일한 후속 작업.
- CM-04 외부 1인 도슨트 실측 — 사람이 필요하며 문서 준비는 이미 완료.

---

## 실행 결과 (2026-07-25)

| Task | 결과 |
|---|---|
| 1 지표 재산출 | `make eval` 완주. 동일 리비전 재실행에서 **139개 키 중 `generated_at` 하나만 차이** — 게이트 B / 경로 「순위상관 충족·방향 일치 미달」 재현 |
| 2 CM-03 사전 작업 | [심사_QA.md](../../심사_QA.md) 문안 B 사실 정정 + 판정 체크리스트를 **판정 ①/② 분리 구조**로 교체(D9 자정 시각 컷 포함) |
| 3 부록 원고 | [부록1_2_평가성적표.md](../../부록1_2_평가성적표.md) 신설 — 부록 1 5행(매칭 행은 D1 표기) + 부록 2 전문 |
| 4 재현 검증 | **클린 클론에서 README 3줄이 실패했다** — `make`가 bare `python` 호출(macOS는 `python3`만 존재) + 의존성 설치 단계 부재. `ai/Makefile`에 `PYTHON ?= python3` 도입, README를 venv 포함 3줄로 교체. 재검증 시 **파이썬 3.14 + 새 의존성**으로도 게이트·전 대표 지표 동일(부동소수 말단 13개만 상대오차 ≤1e-12) |
| 4-b README 사실 정정 | "`make eval`이 자격 매칭 P/R까지 재산출" 서술 삭제 — 하네스는 그 축을 채점하지 않는다(BE 소관). `make help` 문구도 동일 정정 |
| 5 청크 정제 | `strip_print_artifacts()` 추가 → 1,336줄 중 **50줄 제거**, 청크 모지바케 **0.88~2.03% → 0%**, `finance_product` 26행 **diff 0**, grounding 지표 불변. `docs/assumptions.md` **#43** 등재 |
| 6 CM-05 착수 | [기술설명서_원고.md](../../기술설명서_원고.md) 신설 — 1·2·3·4·8·9장 + 설계 원칙 박스 ①③⑤ 작성, 5·6·7·10장은 CP4 판정 대기로 명시 |

**검증**: `ruff check .` 통과 · `pytest -q` **75 passed** · 용어 컴플라이언스 스윕(금지 표현 0건, 고지 문구 포함).

**미착수(의도)**: 자금 매칭 장·탐색 장(D7), 부록 1 매칭 행 수치(#77 도착 시 교체), CM-04 외부 1인 실측.

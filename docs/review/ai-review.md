# AI Review

리뷰 일시: 2026-07-29 · 대상: `ai/` 전체, `db/init/` 덤프 생성 경로, `docs/assumptions.md` 표본 대조
리뷰 기준: `docs/specs/최종_스펙문서.md` v6.3 §0-1·§3-1·§4·§5-1·§5-4·§12, `docs/TASKS.md` AI-01~09

## Executive Summary

AI 영역은 출시 가능 상태다. **P0(출시 차단) 이슈는 발견하지 못했다.** ruff 클린, pytest 126건 전부 통과,
`make eval`이 커밋된 덤프·검수본만 읽어 끝까지 재현되며, 재실행한 전 지표(추출 0.966/1.000/0.867 ·
근거 verbatim 1.000 · 민감도 0.917/0.944 · 모델 게이트 B, R² 0.202 · ρ 0.556)가
`docs/부록1_2_평가성적표.md`의 수치와 항목 단위로 일치함을 확인했다.

절대 원칙 준수도 확인했다: `lightgbm`·`shap`은 `ai/requirements.txt`(오프라인 절 명시)에만 존재하고
backend `build.gradle`·frontend에는 없다. `docker-compose.yaml`에 ai 서비스가 없으며(배치는 compose
실행 경로 제외, 파일 3행 주석) `ai/Dockerfile`은 수동 실행 컨테이너(`CMD make help`)다. LLM은 오프라인
추출(구조화)에만 쓰이고, 수치 생성 금지·용어 컴플라이언스가 프롬프트가 아니라 기계 검증
(`validate_product`)과 전건 사람 검수로 잠겨 있다.

남는 지적은 P1 두 건 — ① 적재본 F-002의 `rate_type='fixed'` 표기가 은행금리 연동 산식 비고와 어긋날
가능성, ② 평가 하네스가 미러링하는 상수(BE 가중치·θ, 청크 클린 판정)가 사본이라 드리프트 시 평가
증거가 조용히 무효화되는 구조 — 와 P2 다섯 건이다.

## Score — 90/100 (근거)

- **+ 스펙 준수(§0-1 5원칙)**: 표본 검증한 전 항목에서 위반 없음. 특히 서빙 무모델·수치 결정성·용어
  컴플라이언스가 코드 수준에서 강제된다 (아래 Good Points).
- **+ 검증 가능성**: `make eval`이 신선한 환경 기준으로도 커밋 산출물(`db/init/*.sql`,
  `ai/data/finance/*.json`, `ai/eval/gold/`)만 읽어 재현되고, 결과가 제출 문서와 일치.
- **+ 가정 등재 규율**: 모델 프로토콜·게이트가 결과 확인 전에 등재(#35→#36, 등재 커밋이 구현 커밋에
  선행한다고 문서에 커밋 해시로 명기)됐고, 표본 대조한 상수·수치가 코드와 전부 일치.
- **− P1 2건**(금리 표기 1행의 분류 정확성, 평가-서빙 상수 사본 드리프트 리스크) 및 P2 다수 —
  모두 국소적이며 수정 비용이 작다.
- **− 모델 검증의 방향 축 0/3**은 결함이 아니라 정직하게 보고된 결과지만, 심사 방어 논리가 부록
  문구 유지에 의존한다는 구조적 취약점으로 소폭 감점.

## 요구사항 충족 평가 (스펙 대비)

| 요구사항 | 판정 | 근거 |
|---|---|---|
| §0-1 ① 모든 숫자 결정적 계산 | 충족 | LLM은 `ai/batch/extract/funding_llm.py`(오프라인 추출)뿐. 프롬프트에 생성·추정·계산 금지 명시(43-59행) + `validate_product`(75-95행) 기계 검증 + 전건 사람 검수(`reviewed.json`)가 적재 원천. org/URL은 문서 메타로 결정적 주입(142행) |
| §0-1 ② 서빙 경로 무모델 | 충족 | `lightgbm`·`shap`은 `ai/requirements.txt:16-17`(「평가 하네스 — 서빙 경로 아님」절)에만. backend `build.gradle` 매치 0건, 소스 매치는 `FrontierServiceTest.java:18`의 `ContractShape` 등 문자열 우연뿐. compose에 ai 서비스 없음 |
| §0-1 ③ 용어 컴플라이언스 | 충족 | `funding_llm.py:34` `BANNED_WORDS=("승인","추천","권장")`을 전 문자열 필드에 기계 검사(85-88행). ai/ 산출물 표본에서 위반 미발견 |
| §0-1 ④ WGS84·만원 정수 | 충족 | `batch/preprocess/crs.py`가 소스별 CRS(5174/5179/5181)를 명시 등록 후 WGS84 통일, 거리만 EPSG:5179 일시 투영. 금액은 만원 정수(`cost.py` round, `serving.py:211,216` 만원 환산) |
| §0-1 ⑤ 가정 등재 | 충족(표본) | 아래 표본 대조 6건 전부 일치 |
| §5-1 결정적 도구 순수 함수+단위 테스트 | 충족(AI 몫) | `cost_calculator`/`score_lookup`의 배치 미러 `preprocess/cost.py`·`score.py`는 순수 함수이고 `tests/test_cost.py`(9건)·`test_score.py`(5건)가 커버. `eligibility_filter`/`frontier`/`reverse_check`는 BE 소관(Java, `eval/run.py:19-21`이 matching을 BE 소관으로 명시) — AI 영역 결손 아님 |
| §12-1 평가 하네스 `make eval` | 충족 | 5개 스위트(extraction/grounding/sensitivity/model/report + matching 안내) 정상 동작, 검증 로그 참조 |
| §12-2·§12-3 LightGBM+SHAP 오프라인 검증 | 충족 | `eval/suites/model.py` — 자치구 블록 GroupKFold, 사전 등록 하이퍼파라미터·게이트(20-27행, assumptions #35 ⑥⑦), MAPE 배제 근거, SHAP 방향은 원자료 Spearman 대조 병행(133-147행) |
| db/init 덤프 생성 경로 일관성 | 충족 | `batch/load/__init__.py` CORE_ORDER 12테이블 = `10_data_core.sql` INSERT 12테이블 정확 일치(실측). `20_finance.sql` 2테이블 일치. `02_mock_data.sql`은 `10_/20_` 선두 TRUNCATE로 서빙 미도달임을 파일 헤더·README가 명시 |

**assumptions.md 표본 대조 (6건 전부 일치)**

| 가정 | 코드 | 판정 |
|---|---|---|
| #40 인테리어 상수 카페 2,485·음식점 4,595 | `cost.py:25` `INTERIOR_MANWON={"cafe":2485,"food":4595}` | 일치 |
| #35 ⑥⑦ 모델 파라미터·게이트 사전 고정 | `model.py:20-27` PARAMS·A/B 게이트 값 동일 | 일치 |
| #3 폐업 제외(영업/정상만) | `collect/permits.py:33` `OPEN_STATUS="영업/정상"` | 일치 |
| #99 중첩 상권 양쪽 계상(사전순 몰아주기 철회) | `competition.py:26-49` 구현·로그 단위까지 등재 내용과 일치, #19에 철회 취소선 반영 | 일치 |
| #29 민감도 3차 재산출 카페 0.917·음식점 0.944 | `make eval` 재실행 실측 0.9167/0.9444 | 일치 |
| 이슈 #152 대표면적 교정(44.0/51.7) | `cost.py:16` + `10_data_core.sql` meta 행 「음식점 51.7㎡ 기준」 | 일치 |

## 상세 발견 사항

### P0 — 반드시 수정해야 출시 가능

발견된 P0 없음.

### P1 — 출시 전 수정 권장

- **F-002 `rate_type='fixed'` 표기가 은행금리 연동 비고와 상충할 가능성** —
  근거: `db/init/20_finance.sql`의 F-002(이자지원 보증서 대출) 행이 `rate=NULL, rate_type='fixed',
  rate_note='은행 대출 금리 - 이자 지원 금리'`로 적재돼 있다(덤프 내 `NULL, 'fixed'` 조합은 이 1행뿐).
  원인은 `ai/batch/load/finance.py:30`의 변동 판정 정규식 `_BASE_VAR = r"(기준금리|CD금리)\s*\+|은행금리에서.*차감"`이
  「은행 대출 금리 - 이자 지원 금리」라는 표현(마이너스 산식·「차감」 미출현)을 못 잡기 때문이다.
  스키마 주석(`01_schema.sql:232`)이 「fixed도 숫자 미표기 시 NULL 가능」을 허용하므로 계약 위반은
  아니나, 은행금리에 연동해 움직이는 금리를 FE 분기 키(`rate_type`, `01_schema.sql:233` 「FE 분기 키」)가
  '고정'으로 나를 소지가 있다 — 금융 정보 서비스에서 금리 성격 표기는 심사 감점 리스크.
  영향: 상품 카드 1건의 금리 성격 오표기 가능(추가 검토 필요 — FE 실제 표기 확인은 FE 영역).
  권장 조치: `_BASE_VAR`에 해당 패턴 보강 또는 검수본(`reviewed.json`)에 rate_type 명시 필드를 두고
  이 행만 수동 확정 후 `make load` 재생성.

- **평가 하네스의 서빙 상수 미러가 사본이라 드리프트를 감지할 장치가 없음** —
  근거: ① `ai/eval/suites/sensitivity.py:10-11` `WEIGHTS={w1:0.30,…}`·`THETA=0.15`는 BE
  `LocationService.java:40` `DEFAULT_WEIGHTS(0.30,0.20,0.20,0.15,0.15)`의 사본(현재 값 일치 확인).
  ② `ai/eval/common.py:47-53` `is_clean_source`는 `batch/load/finance.py:63-70`(`_KEYWORDS`·`_CLEAN_DENSITY`)의
  재구현이다 — 같은 파일에서 `strip_print_artifacts`는 import(common.py:63)하면서 이것만 복제했다.
  영향: 어느 한쪽이 바뀌면 민감도·근거 지표가 실서빙과 다른 규칙을 평가하면서도 테스트는 계속
  통과한다 — 제출 근거(부록 1·2)의 무결성이 조용히 깨지는 경로.
  권장 조치: ②는 `finance._is_clean` import로 사본 제거(즉시 가능). ①은 언어 경계라 import 불가 —
  최소한 pytest에 「BE 상수 원문 grep 대조」 스냅샷 단언 또는 양쪽 주석에 상호 참조를 걸고
  `docs/assumptions.md`의 재적재 파급 목록에 등재.

### P2 — 출시 후 개선 가능

- **`competition.py`의 가정 번호 표기 어긋남(#98 vs #99)** — 근거: `ai/batch/preprocess/competition.py:30`이
  양쪽 계상 규칙의 출처를 「(가정 #98)」로 적었으나, 그 규칙의 등재는 `docs/assumptions.md` **#99**
  (172-173행 부근; #98은 레이어 중첩 실측)이고 해당 커밋 메시지(`1fdd3b7`)도 #99를 가리킨다.
  영향: 추적성 혼선. 조치: 주석 번호만 #99로 교정.
- **덤프 헤더의 `date.today()` — 내용 불변 재생성에도 diff 발생** — 근거: `ai/batch/load/emit.py:60`
  `f"-- 생성: {_dt.date.today().isoformat()}"`. 영향: 같은 입력으로 다른 날 재생성하면 1.6MB 파일에
  헤더 diff가 나 「내용 동일」 확인 비용 증가(부록이 재현성 비교를 명시적으로 수행하는 프로젝트라 체감됨).
  조치: 입력 데이터 기준일(분기)로 대체하거나 헤더 제외 diff 스크립트 메모.
- **성장 결측 기본값 `growth_rank=1`(정체) 하드코딩** — 근거: `ai/batch/load/serving.py:349`
  `"growth_rank": growth.get(area, 1)`. 매출·유동인구 결측은 점수 제외인데(리뷰 #20·#21 주석) 성장
  결측만 중간값 대입으로 규칙이 다르다. assumptions에서 이 폴백의 등재를 확인하지 못했다(추가 검토
  필요 — #41 산식 등재 세부는 표본 범위 밖). 조치: 등재 또는 결측 규칙 통일 검토.
- **SHAP 방향 일치 0/3축 — 심사 방어가 부록 문구에 의존** — 근거: `make eval` 실측
  `direction.hits=0/3`, `store_per_10k_m2`는 SHAP·원자료 Spearman(+0.49) 모두 설계 부호(w3, −)와
  반대. 게이트 B는 ρ 경로로 성립하고 `model.py:46-59` `gate_path`가 「방향 일치 미달」을 정직하게
  병기하며 부록도 반영돼 있다 — 결함이 아니라 보고된 사실. 영향: 부록 편집 과정에서 한계 문구가
  탈락하면 §12-3 B 게이트 조건(「한계 문구 필수」) 위반이 된다. 조치: 제출 전 최종 PDF에서 해당
  문구 잔존 확인을 체크리스트化.
- **`finance.py`의 `data_as_of` 폴백 `"2026"`** — 근거: `ai/batch/load/finance.py:208`
  `p.get("notice_date") or "2026"`. 현 적재본은 전 상품 notice_date 보유로 미발동이나, 발동 시 화면
  기준일이 연도 단독 표기가 된다(스펙 §0-4 기준일 상시 표기의 하한선). 조치: 발동 시 경고 로그 추가.

## Good Points

- **평가의 재현 가능성 설계**: `eval/common.py`가 원천 DB 대신 커밋된 덤프(`db/init/*.sql`)를 직접
  파싱해(16-17행) 심사위원이 raw 데이터 없이 `make eval`만으로 부록 수치를 재산출할 수 있다.
  실제로 이번 리뷰 재실행 값이 부록과 전 항목 일치했다. raw 미포함은 `.gitignore:34-37` +
  `ai/data/raw/README.md`로 문서화돼 있다.
- **사후 조정 방지**: 모델 하이퍼파라미터·게이트를 결과 확인 전 등재(#35, 등재 커밋 `fa4be7a`가 구현
  `54539db`에 선행함을 문서에 명기)하고 코드에 「여기서 임계치를 바꾸지 말 것」(`model.py:9-10`)을
  박아 두었다. 유지율·R²가 내려간 재산출도 「내려간 것을 그대로 보고한다」로 일관(부록 3차 재산출 절).
- **LLM 경계의 기계적 강제**: 수치 생성 금지가 프롬프트 요청이 아니라 `validate_product`의 범위
  검사·용어 검사, 결정적 메타 주입, 위치 기반 ID 금지(`finance.py:180-186` — 골드셋 오귀속 방지),
  전건 검수 게이트로 겹겹이 잠겨 있다.
- **인용 무결성(§5-4)**: NUL·인쇄 장식 제거가 「원문 훼손」이 아니라 「verbatim 복원」임을 실측
  근거(411자 소실)로 논증하고(`finance.py:85-101`), 귀속 오류를 막기 위해 첫 문단 폴백을 제거하고
  인용 불가 문서는 문서 단위로 비웠다(`NON_QUOTABLE_DOCS`). 재실행 verbatim 1.000.
- **오류 교정의 깊이**: 중첩 상권 사전순 몰아주기(#99)를 「가장 빽빽한 상권이 경쟁여유 최상위로
  반전」이라는 실질 영향까지 추적해 고치고, 덤프·부록·assumptions(#19 취소선 포함)를 같은 날 함께
  갱신 — 재적재 파급 관리가 실제로 작동한다.
- **결측의 정직한 처리**: 매출 0 반올림·유동인구 결측을 0 강등 대신 점수 제외(`serving.py:218-226,
  339-343`), NaN이 `or 0`을 통과해 백분위를 반전시키던 버그를 `.dropna()`로 봉인한 경위 주석(329행).

## Remaining Tasks

코드 주석·문서에 명시된 미완 항목 (리뷰에서 새로 만든 과제 아님):

- `cost.py:30` `UTILITY_RATE` — KOSIS 영업비용 확정치로 교체 예정(Task A0 표기).
- `cost.py:28` 최저임금 월환산 — 2026 확정 시 갱신.
- assumptions #69 후속 — 인허가 미조인 27건의 w3=1.0 폴백을 `store_cnt÷면적`으로 바꾸는 개선(#99 말미).
- 부록 2026-07-27 재산출 절 — `transit.daily_riders` 재수집이 남은 원천 이슈로 명기.
- 본 리뷰 P1 2건·P2 5건.

## 검증 로그

실행 환경: 로컬 `ai/.venv`(Python 3.11, 의존성 기설치). 프로젝트 파일은 일절 수정하지 않음
(`make eval` 산출은 `.gitignore:47`의 `ai/eval/out/`에만 기록됨을 확인).

| 검증 | 명령 | 결과 |
|---|---|---|
| Lint | `cd ai && ./.venv/bin/ruff check .` | **All checks passed!** (시스템 PATH에 ruff 없음 → venv 실행) |
| 단위 테스트 | `cd ai && ./.venv/bin/python -m pytest -q` | **126 passed**, 3 warnings(shap 내부 PendingDeprecationWarning), 20.86s |
| 평가 하네스 | `cd ai && make eval PYTHON=.venv/bin/python` | 정상 종료(report 스위트 = 전 스위트 1회 실행). 추출 P 0.966 / R 1.000 / 필드 0.867(약필드 amount_max 0.536·rate_note 0.286) · 근거 verbatim 1.000(16청크)·coverage 22/26=0.846 · 민감도 카페 0.9167·음식점 0.9444(각 12섭동) · 모델 n=2,455, R² 중앙값 0.202·ρ 0.556·WAPE 0.548·MAE 748만원·베이스라인 R² −0.002, SHAP 방향 0/3축, **게이트 B(ρ 경로)** — `docs/부록1_2_평가성적표.md` 수치와 전 항목 일치. 최초 시도는 macOS에 `timeout` 명령 부재로 실패해 제거 후 재실행 |
| 서빙 무모델 grep | `grep -rniE 'lightgbm\|shap' backend/ frontend/` 및 `backend/build.gradle` | build.gradle 매치 0건. 소스 매치는 `FrontierServiceTest.java:18` `...ContractShape()` 등 'shape' 부분 문자열 우연뿐. Python requirements는 리포 전체에서 `ai/requirements.txt` 하나이며 lightgbm·shap이 「평가 하네스(서빙 경로 아님)」 절에 위치 |
| compose 경로 | `docker-compose.yaml` 확인 | ai 서비스 없음(3행 주석: 배치는 compose 실행 경로 제외). `ai/Dockerfile`은 `CMD ["make","help"]` 수동 컨테이너 |
| 덤프 일관성 | `db/init/*.sql` 헤더·INSERT 테이블 집합 vs `batch/load/__init__.py`·`finance.py` | `10_data_core.sql` 12테이블 = CORE_ORDER 12개 정확 일치, `20_finance.sql` 2테이블 일치, 헤더가 검수본(reviewed.json) 재생성임을 명시. 최근 재생성 커밋 `1fdd3b7`(#99 교정 반영) |

부기: 리뷰 중 세션 한도로 병렬 조사 에이전트 2기가 중단되어 db/init 일관성·assumptions 표본 대조는
본 세션에서 직접 재수행했다. `docker compose` 통합 기동은 AI 영역 범위 밖이라 실행하지 않았다.

---

# AI 시스템 리뷰 — LLM·프롬프트·에이전트 계층 (2차 패스)

리뷰 일시: 2026-07-29 · 리뷰어 역할: AI Reviewer (Prompt / Context / RAG / Model / Agent / Pipeline / Security)
대상: `backend/src/main/java/com/ventry/api/llm/`, `backend/.../serving/{ExploreService,RiskReviewAgent,ReviewGenerator,RefineGenerator,PlanGenerator}`,
`ai/batch/extract/funding_llm.py`, `ai/batch/load/finance.py`(인용 경로), `ai/eval/suites/`
기준: CLAUDE.md 절대 불변 원칙 §0-1·3, 스펙 v6.3 §5-3·§5-4·§12, exploration spec §1·§2-5·§5·§6

> 위의 1차 패스는 `ai/` 배치·평가 파이프라인을 대상으로 한다. 이 절은 **서빙 경로의 LLM 계층**
> (프롬프트 조립·응답 검증·에이전트 워크플로·캐시·보안)을 대상으로 하며 범위가 겹치지 않는다.

## Executive Summary

이 프로젝트의 LLM 계층은 **내가 본 LLM 서비스 중 상위권의 설계**다. 핵심은 "프롬프트로 부탁한
것은 제약이 아니다 — 어긴 응답을 버릴 수 있어야 제약이다"라는 원칙이 코드에 실제로 구현되어
있다는 점이고, 이건 대부분의 프로덕션 LLM 서비스가 못 하고 있는 것이다.

다만 그 검증기에 **한 겹이 비어 있었다**. 출력 수치를 `사실의 값 집합`과 대조하는데, 집합 대조는
"1800이 어딘가에 있었다"만 볼 뿐 **그 값이 무엇을 뜻하는 값이었는지는 보지 않는다.** 그래서
사실의 「추정매출 1800만원」을 「환산임대료 1,800만원」으로 옮겨 적은 반박문이 검증을 통과해
`verified=true`와 함께 화면에 오른다. 지어낸 숫자가 아니라서 §0-1의 기존 방어를 전부 통과하지만,
사용자가 보는 문장은 임대료를 9배로 말한다. **금융 판단에 쓰이는 화면에서 이건 P0다.**
이번 리뷰에서 실패 테스트로 재현하고 수정했다.

두 번째로, LLM 왕복 3곳 중 **가장 앞에 있는 하나(plan)만 캐시 경계가 없었다.** 반박문·언어화는
캐시 경계를 위해 빈까지 나눠 두었는데, 정작 `/api/explore`의 **첫 SSE 이벤트를 막고 있는** plan
호출은 매 요청 새로 돌고 있었다. 프롬프트 변수가 업종 2종 × 관심사 8종이라 서로 다른 프롬프트가
최대 16개뿐인, 캐시가 가장 잘 듣는 자리다. 이것도 수정했다.

세 번째는 수정하지 않았다. **운영 관측이 없다.** 토큰 사용량·지연·거부율 중 어느 것도 기록되지
않아, 프로덕션에서 "이번 달 얼마 썼나 / 검증기가 몇 %를 버리나 / 어느 프롬프트가 문제인가"에
답할 수단이 없다. 정확성이 아니라 **운영 가능성**의 문제이며, 지표 싱크 선택이 팀 결정 사항이라
패치 대신 제안으로 남긴다.

Prompt Injection은 **구조적으로 차단되어 있다** — 서빙 프롬프트에 들어가는 값이 전부
화이트리스트(`cafe|food`)이거나 고정 어휘(`premium|rent|traffic`)이거나 DB·결정적 계산 산출물이고,
사용자 자유 텍스트(`free_text`)는 프롬프트에 **닿지 않는다**. 이건 사후 방어가 아니라 설계 결정이며
(`DiagnoseDtos:13-14`), 이 리뷰에서 가장 높이 평가하는 부분이다.

## AI Quality Score — 88/100

| 축 | 점수 | 근거 |
|---|---|---|
| Prompt Engineering | 19/20 | 제약을 프롬프트가 아니라 검증기에 두는 설계. 프롬프트가 "무엇을 하지 말라"를 구체 예시로 적어 폐기율을 낮춤(`RefinePrompt:55-56`). −1: plan에 `response_format` 미사용 |
| Context Engineering | 18/20 | 컨텍스트가 작고 결정적. 반박 대상을 상위 3건으로 제한해 요지 유지(`RiskReviewAgent:26`), 고지 문구를 LLM에 주지 않고 서버가 재부착 | 
| Model / Output | 14/20 | temperature 0, 모델 선택(서빙 mini / 배치 4o) 합리적. −6: `max_completion_tokens` 미설정, structured output 미사용, 출력 스키마가 자유 텍스트 |
| Agent / Workflow | 17/20 | 1왕복 고정, 액션 공간 유한(축 4종 화이트리스트), 모든 실패가 단일 폴백 경로로 수렴. −3: plan 왕복이 첫 이벤트를 막던 구조(수정함) |
| Pipeline / Ops | 10/20 | 타임아웃·세마포어·캐시·폴백은 훌륭. −10: **토큰·지연·거부율 관측 전무**, 배치 추출에 retry/timeout 없음 |
| Security | 10/10 | 프롬프트 인젝션 표면 없음(자유 텍스트 미유입), 키 미커밋·미로깅, 요청 크기 상한, PII 프롬프트 유입 없음 |

## Critical (P0) — 1건 (수정 완료)

### C-01. 리스크 검증 반박문이 **수치의 귀속을 바꿔** 화면에 오를 수 있었다

**문제.** `ReviewPrompt.sanitize`는 출력의 숫자가 전부 입력 사실에 있던 **값**인지만 검사했다
(`LlmResponses.allNumbersIn`). 값 집합 대조는 그 값이 **어느 지표의 값이었는지**를 보지 않는다.

**근거.** 사실이 `환산임대료 198만원, 추정매출 1800만원`일 때 반박문
「환산임대료가 1,800만원에 이르러 상환 부담이 추정매출을 잠식할 수 있습니다」는
1800 ∈ {75, 0.110, 198, 1800, …} 이므로 **통과한다**. 리뷰에서 실패 테스트로 재현했다
(`ReviewPromptTest.rejectsObjection_thatReattachesAGivenNumberToTheWrongLabel` — 패치 전 FAILED).
같은 방식으로 「부담률 75」(종합점수 값을 부담률로) 도 통과했다.

**영향.** 반박문은 `verified=true`와 함께 리스크 검증 패널에 **그대로 실린다**. 사용자는
임대료를 9배로 읽고, 그 화면은 시스템이 "검증했다"고 말하고 있다. 지어낸 숫자가 아니라서
§0-1의 기존 방어(생성 금지)를 전부 통과하지만, §0-1이 지키려는 것은 숫자의 출처가 아니라
**숫자가 뜻하는 바**다. 자금 조달 판단에 쓰이는 화면이라 심사 리스크이자 사용자 피해다.

**개선안 (적용함).** `LlmResponses`에 **라벨-값 결속** 검사를 추가했다.
- `labeledValues(text)` — 지표 라벨(`종합점수·부담률·환산임대료·추정매출·부족분`)과 숫자를 한 번에
  훑어, **값의 임자를 가장 가까운 앞 라벨**로 정한다. 값 하나가 라벨을 소진하고, 라벨-값 간격이
  8자를 넘으면 임자가 없다(=제약 없음).
- `labelsAgree(text, factLabels)` — 사실이 값을 붙여 준 라벨에 한해 출력의 값이 일치해야 한다.

간격 상한 8자는 양방향 오류를 다 피하도록 잡았다: 넓히면 「추정매출이 유지된다는 전제에 …
CAUTION 후보 120곳」의 120이 추정매출에 잘못 묶여 **정상 반박이 폐기**되고, 좁히면 사실 문구의
「부족분(만원): 」(6자)이 끊긴다. 라벨이 줄지어 나오는 「환산임대료 대비 추정매출 부담률 0.110」
(checkArea 사실 문구의 실제 형태)은 가장 가까운 `부담률`이 값을 갖는다 —
`acceptsObjection_whereNearestLabelOwnsTheNumber`가 이를 잠근다.

프롬프트에도 같은 규칙을 명시했다(검증기가 버릴 것을 모델이 덜 만들도록):
「수치를 다른 지표에 옮겨 붙이지 마세요 … 입력에 적힌 지표 이름을 그대로 함께 쓰세요」.

## Major (P1) — 4건 (2건 수정, 2건 제안)

### M-01. plan LLM 왕복이 `/explore` 첫 이벤트를 막고 있었고, **캐시 경계가 없었다** (수정 완료)

**문제.** `ExploreService.plan()`이 `llm.complete()`를 직접 호출했다. plan은 SSE의 **첫 이벤트**라
탐색 화면의 첫 바이트가 이 왕복 뒤에 나온다. 그런데 반박문(`ReviewGenerator`)·언어화
(`RefineGenerator`)는 캐시를 위해 빈까지 분리해 둔 반면 이 호출만 **매번 새로** 돌았다.

**근거.** 코드가 스스로 그 대가를 적어 두었다 — `RiskReviewAgent:40`은 LLM 왕복을 "실측 약 1.9초",
`LlmSettings:14`는 타임아웃 5초로 기록한다(둘 다 저장소의 기존 실측치이며 이번 리뷰에서 재측정하지
않았다). `ExploreController:45-50`은 이 호출이 송출 스레드 안에 있음을 명시하고
"TTFB 자체는 그대로다"라고 인정한다. 반면 `ExploreService.refine()`의 주석은 같은 이유로 언어화
호출을 **밖으로 뺐다**고 적고 있다 — 원칙은 있는데 plan에만 적용되지 않았다.

**영향.** 탐색 화면 진입 시 첫 이벤트가 LLM 응답 시간만큼(장애 시 타임아웃 5초까지) 지연된다.
"템플릿 즉시 → 선택적 교체"(expl §2-5)라는 설계가 가장 앞단에서 성립하지 않는다.

**개선안 (적용함).** `PlanGenerator` 빈을 신설해 캐시 경계를 만들었다 —
`@Cacheable(cacheNames="plans", key="#industry + '|' + #concerns", unless="#result.isEmpty()")`.
빈을 나눈 것은 이 저장소가 이미 두 번 같은 이유로 한 일이다(`@Cacheable`은 프록시가 가로채야
동작하므로 자기 호출은 캐시를 조용히 무력화한다). 폴백은 캐시하지 않는다 — 일시적 타임아웃 한 번이
그 프로필의 축 계획을 1시간 고정하는 것을 막기 위해(D-12와 같은 논거), `PlanPrompt.parseAxesOrEmpty`를
추가해 **실패를 빈 목록으로** 돌리고 폴백 축은 호출부가 씌운다.

**이 캐시가 특히 잘 듣는 이유**: 프롬프트 변수가 업종 2종(`cafe|food`, 화이트리스트) ×
관심사 부분집합 8종(`premium|rent|traffic`, `DiagnoseController`의 키워드 매칭이 만드는 고정 어휘)
= **서로 다른 프롬프트 최대 16개**다. 사실상 첫 요청만 왕복하고 나머지는 전부 적중한다.
`CacheConfig`에 `plans`를 등록했다(compose는 `SPRING_PROFILES_ACTIVE=db`라 운영 경로에서 활성).

### M-02. 모델의 **안내문**이 반박문 자리에 실렸다 (수정 완료)

**문제.** `LlmResponses.firstParagraph`가 첫 문단만 취하는데, 모델이
「요청하신 리스크 검증 반박문을 아래에 작성하였습니다:」를 한 문단으로 먼저 쓰면 **그 안내문이
첫 문단**이 된다. 숫자가 없어 수치 검사가 공허하게 참이고, 금지어도 없고, 길이(23자)도 통과한다.

**근거.** 실패 테스트로 재현했다(`rejectsHeaderOnlyResponse_…`·`skipsPreambleParagraph_…` — 패치 전
FAILED). 흥미롭게도 기존 테스트 `stripsDecoration_butKeepsSentenceIntact`가 **정확히 같은 메커니즘의
반대 방향**(첫 문단을 취하고 뒤를 버림)을 정상 동작으로 잠가 두고 있었다.

**영향.** 반박이 사라지는 것보다 나쁘다 — `verified=true`로 "검증이 돌았다"고 표시하면서 내용이
안내문이다. `RefinePrompt`는 수치 집합 동일성 검사 덕에 같은 사고에서 안전하다(안내문에는 수치가
없어 집합이 어긋난다). **노출된 것은 `ReviewPrompt` 한 곳**이다.

**개선안 (적용함).** 두 겹으로 막았다.
- `firstParagraph` — 콜론으로 끝나는 앞 문단은 건너뛰고 **다음 문단을 본문으로** 쓴다. 본문이
  실제로 뒤에 있으므로 통째로 버리는 것보다 낫다(반박이 살아남는다).
- `looksLikeHeader` — 안내문 한 줄만 온 경우엔 건너뛸 다음 문단이 없으므로 `sanitize`가 폐기한다.

### M-03. LLM 운영 관측이 없다 (미수정 — 지표 싱크 결정 필요)

**문제.** LLM 호출에 대해 **토큰 사용량·지연·성공/거부율 중 어느 것도 기록되지 않는다.**
전체 LLM 관련 로그는 폴백 시 `log.info` 두 줄뿐이며(`RefineGenerator:57`, `ReviewGenerator:54`),
그나마 "폴백했다"만 남기고 **원인(타임아웃인가 검증 거부인가)을 구분하지 않는다** — 주석이
"로그로만 구분한다"고 적었지만 실제로는 두 경우가 같은 문장을 남긴다.

**근거.** `OpenAiLlmClient.ChatResponse`가 `choices`만 매핑하고 OpenAI가 돌려주는 `usage`
(`prompt_tokens`·`completion_tokens`)를 **버린다**(`OpenAiLlmClient:76`). `GuardedLlmClient.complete`에
타이밍 계측이 없다. 액추에이터 노출은 `health`뿐(`application.yml`)이다.

**영향.** 프로덕션에서 다음에 답할 수단이 없다 — ① 이번 달 LLM 비용, ② 검증기가 응답의 몇 %를
버리는가(= 프롬프트 품질 지표), ③ 타임아웃 5초가 적절한가, ④ 세마포어 K=4가 병목인가.
특히 ②는 이 서비스의 품질 지표 그 자체다. 검증기가 90%를 버리고 있어도 화면은 템플릿으로 멀쩡히
동작하므로 **아무도 모른 채 LLM 값이 0이 된다.**

**개선안 (패치 제안).** 최소 침습으로 세 가지를 남기면 위 네 질문에 전부 답할 수 있다.

```java
// 1) OpenAiLlmClient — usage 를 버리지 않는다 (record 한 줄 + 필드 하나)
record ChatResponse(List<Choice> choices, Usage usage) {}
record Usage(int prompt_tokens, int completion_tokens) {}   // 필드명 그대로 = snake_case 무관

// 2) GuardedLlmClient.complete — 왕복 시간과 결과 종류를 남긴다
long startedAt = System.nanoTime();
...
log.info("llm call outcome={} elapsed_ms={}", outcome, (System.nanoTime()-startedAt)/1_000_000);
//   outcome ∈ {ok, no_key, saturated, timeout, error}  ← 폴백 원인이 실제로 구분된다

// 3) ReviewGenerator/RefineGenerator — 폴백 사유를 두 갈래로 나눈다
log.info("리스크 검증 폴백 reason={}", llm.enabled() ? "rejected_by_validator" : "no_llm");
```

`usage`를 살리면 ①이, `elapsed_ms`가 ③④가, `outcome`+`reason`이 ②가 된다. 지표를 어디로
보낼지(액추에이터 metrics · 로그 집계 · 아무것도 안 함)는 팀 결정 사항이라 패치하지 않았다.

### M-04. 언어화(refine)는 **수치의 순서 뒤바뀜**을 막지 못한다 (미수정 — 의도된 트레이드오프)

**문제.** `RefinePrompt.keepsEveryNumber`는 템플릿과 언어화본의 수치 **집합**이 같은지 본다.
집합은 순서를 보지 않으므로 「진입 가능 후보는 382곳에서 1,014곳으로 늘어납니다」를
「진입 가능 후보는 **1,014곳에서 382곳으로** 늘어납니다」로 뒤집어도 통과한다.

**근거.** `RefinePrompt:113-114`가 순서 무시를 **명시적 설계 결정**으로 적고 있다 —
"「382곳에서 1,014곳」을 「1,014곳으로, 382곳에서」처럼 순서만 바꾸는 것은 허용해야 언어화가
의미를 갖는다". C-01의 라벨 결속 검사도 여기엔 듣지 않는다: 두 수가 **같은 라벨**(진입 가능 후보)을
공유해 라벨로는 구별되지 않는다.

**영향.** 발생 확률은 낮다(temperature 0 + 「수치를 하나도 바꾸지 마세요」 명시). 다만 발생하면
증감 방향이 뒤집힌 문장이 인사이트 카드에 실린다.

**개선안 (선택 필요 — 팀 결정을 뒤집지 않기 위해 패치하지 않음).**
- (A) **순서까지 동일 요구**: `numberValues`(집합) 대신 등장 순서 리스트를 비교. 가장 확실하지만
  위 설계 결정을 되돌린다 — 언어화의 자유도가 줄어 폐기율이 오른다.
- (B) **증감 어휘와 대소 관계만 교차 검증**: 「늘어납니다/줄어듭니다」가 있을 때 첫 수 < 둘째 수인지만
  본다. 설계 결정을 보존하면서 뒤집힘만 잡는다. **이쪽을 권한다.**
- (C) 현행 유지 + 리스크 등재.

## Minor (P2) — 5건

### m-01. `max_completion_tokens` 미설정
`OpenAiLlmClient.ChatRequest`가 출력 상한을 걸지 않는다. 실질 노출은 작다 — 5초 타임아웃이
벽시계를 막고 `MAX_LENGTH=400`이 긴 출력을 폐기한다. 다만 상한이 없으면 모델이 루프에 빠졌을 때
5초치 토큰을 그대로 청구받는다. **주의**: 이 record는 필드명이 전부 한 단어라 snake_case 전역
설정과 무관한데(코드 주석 71행), 두 단어 필드를 넣으면 camelCase로 직렬화되어 400이 난다 —
`@JsonProperty("max_completion_tokens")`가 필요하다. 그 검증(직렬화 형태 실측)을 이번 세션에서
하지 못해 패치하지 않았다. **추가 검증 필요.**

### m-02. plan 응답에 structured output 미사용
`PlanPrompt`가 "오직 JSON 배열만 출력하세요"로 부탁하고 파서가 텍스트에서 첫 배열을 긁어낸다.
파서는 견고하지만(깨진 JSON·화이트리스트 밖 축 전부 폴백), `response_format={"type":"json_object"}`
(배치 추출은 이미 쓰고 있다 — `funding_llm.py:124`)을 쓰면 폐기율과 토큰이 함께 준다.
배열이 최상위라 객체로 감싸는 스키마 변경이 따라온다.

### m-03. `industry`가 영문 코드 그대로 프롬프트에 들어간다
`PlanPrompt.build`가 「업종은 cafe입니다」를 만든다. 한국어 프롬프트에 영문 코드가 섞이는 형태라
`cafe→카페`, `food→음식점` 매핑이 낫다. 액션 공간이 유한해 실질 위험은 없다.

### m-04. 세마포어가 취소 완료 전에 반환된다
`GuardedLlmClient.complete`의 `finally { semaphore.release(); }`는 `task.cancel(true)` 직후 실행되는데,
취소는 비동기라 HTTP 요청이 아직 살아 있을 수 있다. 타임아웃이 연달아 나는 구간에서 **실효 동시
호출이 K=4를 넘을 수 있다.** 소켓 레벨 타임아웃이 붙은 뒤로는 상한이 잡히므로 영향은 작다.

### m-05. 배치 추출에 retry·timeout이 없다
`funding_llm.extract_doc`이 `client.chat.completions.create`를 단발로 부른다 — 일시적 429/5xx면
그 문서가 0건이 되고, 루프는 다음 PDF로 넘어간다. 0건은 `write_review_sheet`가 「⚠️ 0건 — 원본 확인
필요」로 표시하고 전건 사람 검수가 최종 게이트라 **오염이 적재까지 가지는 않는다** — 그래서 P2다.
`OpenAI(api_key=key, max_retries=3, timeout=60)` 한 줄로 닫힌다.

## Security — 지적 없음

**Prompt Injection: 표면이 없다.** 서빙 프롬프트에 들어가는 값을 전부 추적했다.
- `industry` — `DiagnoseController:32,94` 화이트리스트(`cafe|food`), 세션 생성 **전에** 검증.
- `concerns` — `DiagnoseController:52-61` 키워드 매칭이 만드는 **고정 3어휘**. 사용자 문자열이 아니다.
- 상권명·판정·수치 — DB 및 결정적 계산 산출물.
- `free_text` — **프롬프트에 닿지 않는다.** 세션에도 저장되지 않고 `contains()` 매칭에만 쓰인다
  (`RequestSizeLimitFilter:22-24`가 이 사실을 파급 분석의 근거로 삼는다).

이건 사후 방어가 아니라 설계 결정이다 — `DiagnoseDtos:13-14`가 "금액은 자유 텍스트가 아니라 반드시
폼으로 들어온다(자유 텍스트 경유는 LLM이 수치를 만드는 통로가 되어 §0-1과 충돌)"고 적고 있다.
**인젝션 방어를 목표로 한 결정이 아닌데 인젝션 표면까지 같이 없앴다.**

**간접 인젝션(배치)**: `funding_llm.py`는 신뢰할 수 없는 PDF를 gpt-4o에 넣는다. 공고문 PDF에
지시문이 심겨 있으면 추출 결과가 오염될 수 있다. 다만 ① 산출물이 서빙에 직행하지 않고
`reviewed.json` **전건 사람 검수**를 거치며, ② `validate_product`가 금액·금리 범위와 용어를 기계
검증하고, ③ `org`·`source_url`은 LLM이 아니라 문서 메타로 **결정적 주입**된다. 실질 방어는 충분하다.

**기타**: `.env`는 `.gitignore:6`으로 제외되고 추적본은 `.env.example`(키 값 비어 있음)뿐.
API 키가 로그에 실리는 경로 없음. 프롬프트에 PII 없음(나이·자본금은 프롬프트에 들어가지 않는다).
요청 본문 상한 256KB(`RequestSizeLimitFilter`).

## Good Points

- **"프롬프트는 제약이 아니다"가 코드로 구현되어 있다.** `ReviewPrompt`·`RefinePrompt`의 클래스
  javadoc이 "이 클래스의 본체는 프롬프트가 아니라 `sanitize`다"라고 선언하고 실제로 그렇게 되어
  있다. 대부분의 프로덕션 LLM 서비스가 프롬프트에 규칙을 적고 끝낸다.
- **모든 실패가 단일 폴백 경로로 수렴한다.** 키 부재·타임아웃·동시성 초과·예외·검증 거부가 전부
  `Optional.empty()`가 되어, 호출부에 "LLM 쓸 수 있나?" 분기가 **하나도 없다**. 무LLM 스택이
  CI 기본 경로라 이 폴백이 상시 테스트된다.
- **고지 문구를 LLM이 지울 수 없는 자리에 둔다.** `ExploreService.refineOne`이 자격 한정 꼬리를
  떼어 본문만 넘기고, 통과한 문장 뒤에 **서버가 다시 붙인다**. 필수 고지를 모델 출력에 의존시키지
  않는 이 패턴은 컴플라이언스 설계로서 정확하다.
- **캐시 키 선택이 영리하다.** 반박문 캐시에서 확정 예산·후보 총수를 **일부러 사실에서 뺐다** —
  슬라이더 드래그 한 칸마다 캐시가 빗나가는 것을 막으면서, "사실이 달라지는 시점 = 추천이 실질적으로
  달라지는 시점"을 유지한다(`RiskReviewAgent:38-46`). 캐시를 비용이 아니라 **의미 단위**로 설계했다.
- **`unless = "#result == null"` 주석**. 스프링 캐시가 Optional을 벗겨 SpEL에 넘기는 탓에
  `#result.isEmpty()`가 **폴백 경로에서만** 터지던 사건을 주석으로 박제해 두었다. 이런 함정은
  재발이 잦은데, 재발 시 원인을 찾는 시간이 0이 된다.
- **금지어 목록의 단일화**. 두 검증기가 각자 목록을 들고 있다가 「추천해 드립니다」를 함께 놓친
  경위를 적고 `LlmResponses.BANNED_TERMS` 하나로 모았다. 활용형까지 어간으로 잡는다.
- **RAG를 안 쓰기로 한 결정이 옳다.** 상품↔근거 관계가 1:1로 **이미 알려져 있으므로**
  `doc_chunk_ref` id 직접 조회가 정답이고 임베딩 검색은 정확도를 떨어뜨릴 뿐이다. 게다가 근거 문단이
  없는 문서는 **인용을 비운다**(`NON_QUOTABLE_DOCS`) — 첫 문단 폴백을 "날조는 아니어도 귀속 오류"로
  규정해 제거한 판단이 특히 좋다. `chunk_verbatim_rate 1.000`이 이를 뒷받침한다.
- **평가 하네스가 LLM 출력 품질을 실제로 잰다.** 추출 정확도(필드 단위)·근거 verbatim·민감도를
  `make eval`로 재현 가능하게 산출한다. LLM 서비스에서 이 정도 평가 자산을 갖춘 경우는 드물다.

## Action Items

- [x] **P0** C-01 반박문 수치 귀속 검사 — `LlmResponses.labeledValues/labelsAgree` 추가, `ReviewPrompt`
      결선, 프롬프트에 규칙 명시. 테스트 3건 추가(재현 1 + 회귀 2)
- [x] **P1** M-01 plan 캐시 경계 — `PlanGenerator` 신설, `PlanPrompt.parseAxesOrEmpty` 추가,
      `CacheConfig`에 `plans` 등록, `ExploreService` 결선
- [x] **P1** M-02 안내문 폐기 — `firstParagraph` 머리말 건너뛰기 + `looksLikeHeader`. 테스트 2건
- [ ] **P1** M-03 LLM 관측 — `usage` 매핑 + `elapsed_ms`/`outcome` 로깅 + 폴백 사유 2분기 (지표 싱크 결정 필요)
- [ ] **P1** M-04 refine 순서 뒤바뀜 — (B) 증감 어휘 × 대소 관계 교차 검증 권장
- [ ] **P2** m-01 `max_completion_tokens` (직렬화 형태 실측 선행 필요)
- [ ] **P2** m-02 plan `response_format` json_object
- [ ] **P2** m-03 `industry` 한국어 라벨 매핑
- [ ] **P2** m-04 세마포어 반환 시점
- [ ] **P2** m-05 배치 추출 `max_retries`/`timeout`

## Final Opinion — **Conditional Approve**

**이유.** LLM 계층의 아키텍처는 승인 수준을 넘는다 — 폴백 단일화, 검증기 우선 설계, 고지 문구
서버 통제, 인젝션 표면 제거, 캐시 키의 의미 단위 설계는 모두 프로덕션 기준에서 모범적이다.
P0였던 수치 귀속 오류는 이번 리뷰에서 재현·수정했고 회귀 테스트로 잠갔다.

조건부인 이유는 **M-03(운영 관측)** 하나다. 지금 상태로 배포하면 검증기가 응답의 대부분을 버리고
있어도 화면은 템플릿으로 멀쩡히 동작하므로 **LLM이 실질적으로 죽어 있는 것을 아무도 알 수 없다.**
"LLM 없이도 산다"는 이 서비스의 최대 강점이 동시에 관측의 사각지대를 만든다. 로그 세 줄이면 닫히는
문제이며, 그것만 붙으면 Approve다.

M-04는 발생 확률이 낮고 팀의 명시적 설계 결정과 얽혀 있어 조건에 넣지 않는다 — 리스크 등재로 충분하다.

## 검증 로그 (2차 패스)

| 검증 | 명령 | 결과 |
|---|---|---|
| 패치 전 결함 재현 | `./gradlew test --tests "…ReviewPromptTest"` | **17 tests, 3 failed** — 수치 귀속·안내문·수치 미인용 3건이 실제로 통과하고 있었음을 확인 |
| 패치 후 전체 | `./gradlew clean test` | **BUILD SUCCESSFUL** · tests=252 skipped=0 **failures=0 errors=0** (클린 재컴파일 포함) |
| 프롬프트 유입 경로 추적 | `DiagnoseController`·`SessionMapper`·`RiskReviewAgent`·`PlanPrompt` 정독 | 서빙 프롬프트에 사용자 자유 텍스트 유입 경로 없음 |
| 키 노출 | `git check-ignore .env` · `git ls-files \| grep env` | `.env` 제외됨, 추적본은 `.env.example`(키 값 공란)뿐 |

**한계 (추측하지 않기 위해 명시).**
- OpenAI 실호출은 하지 않았다(키·네트워크 미사용). 요청 직렬화 형태·응답 스키마는 코드 정독과
  기존 스텁 테스트 범위에서만 확인했다 — m-01이 미패치인 이유가 이것이다.
- "실측 약 1.9초"·"5,001ms 폴백" 등은 **저장소의 기존 기록**이며 이번 리뷰의 측정치가 아니다.
- C-01은 **검증기가 잘못된 문장을 통과시킨다**는 것을 증명했다. gpt-4o-mini가 실제로 그 문장을
  얼마나 자주 생성하는지는 측정하지 않았다 — 방어의 부재를 지적한 것이지 발생률을 주장한 것이 아니다.
- `OpenAiLlmClient`의 소켓 타임아웃 패치는 **이 리뷰의 작업이 아니다.** 같은 시각 병행된 backend
  리뷰 패스가 적용했으며(`docs/review/backend-review.md` P1), 그 안의 프로브 실측치도 그쪽 기록이다.

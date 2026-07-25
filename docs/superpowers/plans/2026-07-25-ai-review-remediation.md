# AI 파트 코드리뷰 지적 13건 수정 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 2026-07-25 AI 파트 리뷰에서 확인된 계산 정합 4건·평가/문서 사실오류 4건·정리 5건을 높음→낮음 순으로 수정하고, 바뀐 수치를 부록 1·2와 기술설명서에 일관되게 반영한다.

**Architecture:** 배치 순수 함수(`batch/preprocess/cost.py`·`batch/load/finance.py`)를 먼저 고치고, `db/init/*.sql` 덤프를 재생성한 뒤, 평가 하네스(`eval/`)가 **실제 적재 산출물**을 읽도록 바꾸고, 마지막에 `make eval` 산출값을 문서로 옮긴다. 문서 수치는 손으로 쓰지 않고 `ai/eval/out/metrics.json`에서 그대로 옮긴다(스펙 §0-1).

**Tech Stack:** Python 3.11 (pandas·geopandas·lightgbm·shap), PostgreSQL 16, pytest, ruff

## Global Constraints

- **모든 숫자는 결정적 계산이 만든다.** 문서 작성 과정의 재계산·조정 금지 (스펙 §0-1).
- **인용은 검색이지 생성이 아니다.** `finance_doc_chunk.text`는 공고문 원문 그대로 (스펙 §5-4).
- 금액은 **만원 단위 정수**, 좌표는 **WGS84**, 데이터 기준일 상시 표기 (CLAUDE.md §4).
- 판정 4단계 `적합 / 조건부 적합 / 유의 / 범위 외` — "승인" 계열 금지 (CLAUDE.md §3).
- 커밋 메시지에 `Co-Authored-By: Claude` / `Generated with Claude Code` **절대 금지** (CLAUDE.md 하드 룰).
- 커밋 컨벤션 `[AI] type: 요약`. 작업 브랜치는 현재 브랜치 `ai09-cm-judgment`를 그대로 쓴다.
- 검증 명령 3종은 매 태스크 종료 시 통과해야 한다:
  `cd ai && .venv/bin/python -m ruff check .` · `cd ai && .venv/bin/python -m pytest -q` · 변경이 덤프에 닿으면 Task 5의 Postgres 왕복 검증.
- `ai/.venv`는 이미 존재한다(Python 3.11 + 전 의존성). 새로 만들지 말고 `.venv/bin/python`을 쓴다.

## File Structure

| 파일 | 책임 | 태스크 |
|---|---|---|
| `ai/batch/preprocess/cost.py` | 초기비용 4블록 순수 함수 — 권리금 비례계수 정의 | 1 |
| `ai/batch/load/serving.py` | 서빙 12테이블 조립 — 중위 단가·업종별 환산임대료 주입 | 1, 2, 8 |
| `db/init/01_schema.sql` | DDL — `initial_cost.monthly_rent` 추가, `v_candidate_area` 전환 | 2 |
| `ai/eval/common.py` | 평가 로더 — 부담률 입력을 업종별 임대료로 교체 | 2 |
| `ai/batch/load/finance.py` | 원문 청크 생성·상품 연결 — 폴백 제거, NUL 제거 | 3, 4 |
| `ai/eval/suites/grounding.py` | 근거 충실도 — 실제 덤프 대조로 재정의 | 6 |
| `ai/batch/preprocess/rent_join.py` | 임대료 할당 등급 — `gu_avg` 실계산 | 8 |
| `docs/부록1_2_평가성적표.md` · `docs/기술설명서_원고.md` · `docs/심사_QA.md` | 심사 제출 문안 — 사실 정정·수치 갱신 | 7, 10 |
| `docs/assumptions.md` | 가정 대장 — 변경 4건 등재 | 10 |

---

## Task 1: 권리금 비례계수에서 대표면적 이중 반영 제거 (지적 #1)

**문제:** `center = ㎡당권리금 × 대표면적 × ratio × 업종보정`인데 `ratio = monthly_rent / seoul_median_rent`의 분자에도 대표면적이 들어 있고, 분모는 음식점 55.2㎡ 기준 단일값이다. 카페는 구조적으로 `ratio ≈ 0.53 × (자기단가/중위단가)`가 되어 하한 클립(0.5)에 걸린다 — 실측 419/1,650행(25.4%)이 권리금 649만원 상수.

**Files:**
- Modify: `ai/batch/preprocess/cost.py:40-50` (`premium_interval`), `:79-104` (`build_initial_cost`)
- Modify: `ai/batch/load/serving.py:243-247` (`_derive`)
- Test: `ai/tests/test_cost.py`, `ai/tests/test_cost_score_run.py`

**Interfaces:**
- Produces: `cost.premium_interval(unit_price: float, seoul_median_unit_price: float, industry: str) -> tuple[int,int]` — 2번째 인자가 **환산임대료(만원)에서 단가(천원/㎡)로 바뀐다.**
- Produces: `cost.build_initial_cost(rent_df: pd.DataFrame, seoul_median_unit_price: float) -> pd.DataFrame` — 컬럼 계약은 동일.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ai/tests/test_cost.py`의 `test_premium_interval_scales_with_rent_and_industry`·`test_premium_ratio_clipped`를 아래로 교체하고, 업종 불변 테스트를 추가한다.

```python
def test_premium_interval_scales_with_unit_price_and_industry():
    # 단가가 서울 중위와 같으면 ratio=1 — 대표면적·업종보정만 남는다
    lo, hi = cost.premium_interval(unit_price=50.0, seoul_median_unit_price=50.0, industry="cafe")
    center = 72.6 * 29.2 * 1.0 * 0.85
    assert lo == round(center * 0.72)
    assert hi == round(center * 1.15)
    assert lo <= hi


def test_premium_ratio_clipped():
    # 단가가 중위의 10배여도 비례계수는 2.0 상한
    clipped = cost.premium_interval(500.0, 50.0, "food")
    at_cap = cost.premium_interval(100.0, 50.0, "food")  # ratio=2.0 동일 상한
    assert clipped == at_cap


def test_premium_ratio_is_industry_neutral():
    """같은 상권(같은 단가)이면 비례계수는 업종과 무관해야 한다.

    구 구현은 ratio 분자에 대표면적이 들어가 카페가 항상 0.53배 작게 나왔고,
    그 결과 카페 상권 25.4%가 하한 클립에 걸려 권리금이 상수로 붕괴했다.
    """
    px, median = 50.0, 50.0
    cafe_lo, _ = cost.premium_interval(px, median, "cafe")
    food_lo, _ = cost.premium_interval(px, median, "food")
    # 면적·업종보정 비만 남는다: (29.2×0.85) / (55.2×1.0)
    assert abs(cafe_lo / food_lo - (29.2 * 0.85) / 55.2) < 0.01
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `cd ai && .venv/bin/python -m pytest tests/test_cost.py -q`
Expected: FAIL — `test_premium_ratio_is_industry_neutral`에서 비율이 약 0.53배 더 작게 나온다.

- [ ] **Step 3: `premium_interval`을 단가 비로 바꾼다**

`ai/batch/preprocess/cost.py:40-50`을 교체:

```python
def premium_interval(
    unit_price_1000won_m2: float, seoul_median_unit_price: float, industry: str
) -> tuple[int, int]:
    """권리금 구간 = ㎡당 권리금 × 대표면적 × 임대료비례 × 업종보정.

    임대료비례는 **단가(천원/㎡) 비**로 잡는다 — 환산임대료 비로 잡으면 대표면적이
    center 항과 ratio 항에 두 번 들어가 업종 간 비교가 깨진다(리뷰 지적 #1).
    """
    ratio = (
        unit_price_1000won_m2 / seoul_median_unit_price if seoul_median_unit_price else 1.0
    )
    ratio = min(max(ratio, RENT_RATIO_CLIP[0]), RENT_RATIO_CLIP[1])
    center = (
        PREMIUM_PER_M2_MANWON
        * REPRESENTATIVE_AREA_M2[industry]
        * ratio
        * PREMIUM_INDUSTRY_ADJ[industry]
    )
    return (round(center * PREMIUM_LOW_RATIO), round(center * PREMIUM_HIGH_RATIO))
```

- [ ] **Step 4: `build_initial_cost` 호출부를 맞춘다**

`ai/batch/preprocess/cost.py:79-104`에서 파라미터명과 호출을 바꾼다.

```python
def build_initial_cost(rent_df: pd.DataFrame, seoul_median_unit_price: float) -> pd.DataFrame:
    """상권×업종 임대료 단가 → 초기비용 4블록 + 합계 구간 DataFrame.

    입력 `rent_df` 컬럼: area_code, industry, unit_price(천원/㎡).
    `seoul_median_unit_price` 도 같은 단위(천원/㎡)다 — 권리금 비례계수의 분모.
    """
```

같은 함수 본문의 권리금 호출을 바꾼다(라인 89):

```python
        prem = premium_interval(r.unit_price, seoul_median_unit_price, industry)
```

- [ ] **Step 5: `serving._derive`가 단가 중위값을 넘기게 한다**

`ai/batch/load/serving.py:243-247`을 교체:

```python
    seoul_median_unit_price = float(rent["unit_price"].median())
    rent_df = pd.concat(
        [rent[["area_code", "unit_price"]].assign(industry=ind) for ind in INDUSTRIES],
        ignore_index=True)
    initial_cost = cost.build_initial_cost(rent_df, seoul_median_unit_price)
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `cd ai && .venv/bin/python -m pytest tests/test_cost.py tests/test_cost_score_run.py -q`
Expected: PASS (전건)

`test_cost_score_run.py::test_build_initial_cost_columns`의 `seoul_median_rent=234` 인자는 이제 단가 의미다 — `build_initial_cost(rent, seoul_median_unit_price=80.0)`로 바꾸고 `row["deposit_low"] == 234 * 8` 단언은 그대로 둔다(보증금은 환산임대료 기반이라 영향 없음).

- [ ] **Step 7: lint + 전체 테스트**

Run: `cd ai && .venv/bin/python -m ruff check . && .venv/bin/python -m pytest -q`
Expected: `All checks passed!` + 전건 PASS

- [ ] **Step 8: 커밋**

```bash
git add ai/batch/preprocess/cost.py ai/batch/load/serving.py ai/tests/test_cost.py ai/tests/test_cost_score_run.py
git commit -m "[AI] fix: 권리금 비례계수에서 대표면적 이중 반영 제거 — 단가 비로 교체 (리뷰 #1)"
```

---

## Task 2: 업종별 환산임대료로 부담률 정합 (지적 #2)

**문제:** `rent` 테이블은 `area_code` 단일 PK라 업종 축이 없고, `monthly_rent`가 음식점 55.2㎡ 기준 하나뿐이다. 부담률 분모인 `est_sales`는 업종별 점포당 매출이라, 카페는 55.2㎡ 임대료를 29.2㎡ 매출로 나눈다. 실측 부담률 중앙값 카페 0.472 / 음식점 0.209, θ=0.15 통과 카페 176/1,060.

**해결 방식:** `rent.monthly_rent`(상권 단위 표기값)는 라벨대로 유지하고, **업종 축이 이미 있는 `initial_cost`에 `monthly_rent`를 되살려** `v_candidate_area`가 그것을 노출한다. BE 코드·API 계약 필드명은 그대로다(값만 업종 정합으로 바뀐다).

> ⚠️ **DDL 변경이다.** `db/init/01_schema.sql`을 고치므로 CONTRIBUTING §6에 따라 BE에 사전 공지가 필요하다. 컬럼 추가 + 뷰 SELECT 소스 교체이며 `v_candidate_area`의 **컬럼 이름·개수·타입은 불변**이라 `CandidateRowMapper`는 손대지 않는다.

**Files:**
- Modify: `db/init/01_schema.sql:173-196` (`initial_cost`), `:256-286` (`v_candidate_area`)
- Modify: `ai/batch/load/serving.py:292-293` (`assemble`의 initial_cost 정리)
- Modify: `ai/eval/common.py:189-205` (`load_serving_scores`)
- Test: `ai/tests/test_serving.py`, `ai/tests/test_eval_common.py`

**Interfaces:**
- Consumes: Task 1의 `cost.build_initial_cost` (이미 `monthly_rent` 컬럼을 만들어 낸다 — 지금까지는 버려 왔다).
- Produces: `initial_cost.monthly_rent` (INTEGER NOT NULL, 업종 대표면적 기준 환산임대료 만원/월). `v_candidate_area.monthly_rent`의 출처가 `rent` → `initial_cost`로 바뀐다.
- Produces: `eval.common.load_serving_scores`가 `initial_cost`에서 `monthly_rent`를 읽는다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ai/tests/test_serving.py` 끝에 추가:

```python
def test_initial_cost_carries_industry_aware_rent(tables):
    """부담률 분자는 업종 대표면적 기준이어야 한다 (리뷰 #2).

    같은 상권에서 카페 환산임대료 < 음식점 환산임대료 여야 한다 — 29.2㎡ < 55.2㎡.
    """
    ic = tables["initial_cost"]
    assert "monthly_rent" in ic.columns
    pivot = ic.pivot(index="area_code", columns="industry", values="monthly_rent").dropna()
    assert len(pivot) > 100
    assert (pivot["cafe"] < pivot["food"]).all()
```

`ai/tests/test_eval_common.py`의 `test_load_serving_scores_parses_sql`을 교체 — 부담률 분자를 `initial_cost`에서 읽는다.

```python
def test_load_serving_scores_uses_industry_rent(tmp_path: Path):
    sql = tmp_path / "core.sql"
    sql.write_text(
        "INSERT INTO initial_cost (area_code, industry, monthly_rent, deposit_low) VALUES\n"
        "('3110002', 'cafe', 200, 1600),\n"
        "('3110002', 'food', 400, 3200);\n"
        "INSERT INTO location_score (area_code, industry, w1, w2, w3, w4, w5, "
        "est_sales, daily_floating, based_on_quarter) VALUES\n"
        "('3110002', 'cafe', 0.3, 0.6, 0.7, 0.4, 0.5, 800, 620376, '20261'),\n"
        "('3110002', 'food', 0.3, 0.6, 0.7, 0.4, 0.5, 1600, 620376, '20261');\n",
        encoding="utf-8",
    )
    rows = {r["industry"]: r for r in common.load_serving_scores(sql)}
    assert rows["cafe"]["monthly_rent"] == 200      # 음식점 400 이 아니다
    assert abs(rows["cafe"]["burden_ratio"] - 0.25) < 1e-9
    assert abs(rows["food"]["burden_ratio"] - 0.25) < 1e-9
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `cd ai && .venv/bin/python -m pytest tests/test_eval_common.py -q`
Expected: FAIL — `load_serving_scores`가 아직 `rent` 테이블을 읽는다.

- [ ] **Step 3: DDL에 컬럼을 추가하고 뷰를 전환한다**

`db/init/01_schema.sql`의 `initial_cost` 정의에서 `industry` 줄 바로 아래에 추가:

```sql
    monthly_rent          INTEGER NOT NULL,   -- 업종 대표면적 기준 환산임대료 만원/월 (부담률 분자)
```

같은 파일 `v_candidate_area`에서 `r.monthly_rent,` 를 아래로 교체:

```sql
    c.monthly_rent,
```

그리고 `initial_cost` 테이블 아래에 주석을 추가한다:

```sql
COMMENT ON COLUMN initial_cost.monthly_rent IS
    '업종 대표면적(카페 29.2㎡ · 음식점 55.2㎡) 기준 환산임대료. rent.monthly_rent 는 상권 단위
     표기값(음식점 55.2㎡ 기준)이라 업종별 부담률 분자로 쓰면 카페가 1.89배 과대해진다 (리뷰 #2).';
```

- [ ] **Step 4: `serving.assemble`이 컬럼을 버리지 않게 한다**

`ai/batch/load/serving.py:292-293`을 교체:

```python
    # initial_cost: DDL 정합 — monthly_rent 는 업종별 부담률 분자로 유지 (리뷰 #2)
    initial_cost = initial_cost.assign(based_on_quarter="20261")
```

- [ ] **Step 5: 평가 로더를 `initial_cost` 기준으로 바꾼다**

`ai/eval/common.py:189-205`를 교체:

```python
def load_serving_scores(sql_path: Path = DATA_CORE_SQL) -> list[dict]:
    """서빙 점수 성분 로드 — 민감도 재계산 입력.

    부담률 분자는 `initial_cost.monthly_rent`(업종 대표면적 기준)다. `rent.monthly_rent`
    는 상권 단위 표기값이라 업종별 부담률에 쓰면 카페가 과대해진다 (리뷰 #2).
    """
    text = sql_path.read_text(encoding="utf-8")
    # initial_cost: area_code, industry, monthly_rent, deposit_low, …
    rent = {(r[0], r[1]): int(r[2]) for r in _iter_sql_rows(text, "initial_cost")}
    out: list[dict] = []
    for r in _iter_sql_rows(text, "location_score"):
        area, industry = r[0], r[1]
        w1, w2, w3, w4, w5 = (float(x) for x in r[2:7])
        est_sales = int(r[7])
        mr = rent.get((area, industry))
        out.append({
            "area_code": area, "industry": industry,
            "w1": w1, "w2": w2, "w3": w3, "w4": w4, "w5": w5,
            "est_sales": est_sales, "monthly_rent": mr,
            "burden_ratio": (mr / est_sales) if (mr and est_sales) else None,
        })
    return out
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `cd ai && .venv/bin/python -m pytest tests/test_eval_common.py -q`
Expected: PASS. `test_serving.py`의 새 테스트는 Task 5에서 덤프를 재생성해야 실제 값이 맞으므로, 여기서는 `assemble()`이 조립한 DataFrame으로 통과한다.

Run: `cd ai && .venv/bin/python -m ruff check . && .venv/bin/python -m pytest -q`
Expected: 전건 PASS

- [ ] **Step 7: 커밋**

```bash
git add db/init/01_schema.sql ai/batch/load/serving.py ai/eval/common.py ai/tests/test_serving.py ai/tests/test_eval_common.py
git commit -m "[AI] fix: 업종별 환산임대료를 initial_cost 로 노출 — 부담률 업종 정합 (리뷰 #2)"
```

- [ ] **Step 8: BE 사전 공지 메모를 남긴다**

`docs/HANDOFF_BACKEND.md` 끝에 추가:

```markdown
## 2026-07-25 DDL 변경 공지 — `initial_cost.monthly_rent` 추가 (리뷰 #2)

- `initial_cost`에 `monthly_rent INTEGER NOT NULL` 추가. `v_candidate_area.monthly_rent` 의
  출처가 `rent` → `initial_cost` 로 바뀐다.
- **뷰의 컬럼 이름·개수·타입은 불변**이라 `CandidateRowMapper`·`CandidateArea`·API 계약은 손대지
  않는다. 바뀌는 것은 **값**이다 — 카페 상권의 환산임대료가 29.2㎡ 기준으로 내려가고, 부담률이
  기존의 약 0.53배가 된다(카페 부담률 중앙값 0.472 → 0.25 수준).
- 이유: `rent` 는 `area_code` 단일 PK라 업종 축이 없어, 카페가 음식점 55.2㎡ 임대료를 29.2㎡
  매출로 나누고 있었다. θ=0.15 통과 카페가 176/1,060 으로 과소했다.
- 조치 필요 없음. 단 BE 통합 테스트에 부담률 기대값을 하드코딩한 곳이 있으면 갱신할 것.
```

```bash
git add docs/HANDOFF_BACKEND.md
git commit -m "[AI] docs: initial_cost.monthly_rent DDL 변경 BE 사전 공지 (리뷰 #2)"
```

---

## Task 3: 근거 인용의 "첫 문단" 폴백 제거 (지적 #3)

**문제:** `ref = next((c for c in chunks if name in c["text"]), chunks[0] if chunks else None)` 의 폴백이 사실상 기본 경로다 — 26건 중 24건이 `#0`(문서 첫 문단)을 가리킨다. F-000의 인용문은 "최대 1억원최대 1억원최대 1억원한도한도한도한도" 같은 브로슈어 머리말이다. 심사_QA 20번이 "임베딩은 다른 상품 문단을 붙일 수 있어 위험하다"고 주장하는 바로 그 귀속 오류를 폴백이 재생산한다.

**해결:** 상품명 토큰 중첩 점수로 청크를 고르고, 동점이면 **더 짧은(구체적인) 청크**를 쓴다. 과반 토큰이 등장하지 않으면 `None`(인용 비움). 프로토타입 실측: 26/26 매칭, 소진공 융자공고 상품들이 `#0` 대신 자기 자금 문단(`#5`·`#6`·`#7`·`#9`·`#11`·`#14`·`#15`)을 가리킨다.

**Files:**
- Modify: `ai/batch/load/finance.py:104-141` (`build_finance`), 상단에 헬퍼 추가
- Test: `ai/tests/test_finance_load.py`

**Interfaces:**
- Produces: `finance.select_chunk(name: str, chunks: list[dict]) -> dict | None` — 상품명과 가장 잘 맞는 청크 또는 `None`.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ai/tests/test_finance_load.py` 상단 import에 `select_chunk`를 추가하고 테스트를 붙인다.

```python
from batch.load.finance import build_finance, chunk_document, rate_fields, select_chunk


def _chunks(*texts):
    return [{"chunk_id": f"D#{i}", "text": t} for i, t in enumerate(texts)]


def test_select_chunk_prefers_name_match_over_first_paragraph():
    chunks = _chunks("표지 브로슈어 머리말", "청년고용연계자금 한도 7천만원 상환 5년")
    assert select_chunk("청년고용연계자금", chunks)["chunk_id"] == "D#1"


def test_select_chunk_prefers_shorter_chunk_on_tie():
    """같은 점수면 더 짧은 청크가 구체적이다 — 통짜 첫 문단이 이기지 않게."""
    long_text = "대환대출 " + "기타 안내 " * 200
    chunks = _chunks(long_text, "대환대출 한도 5천만원")
    assert select_chunk("대환대출", chunks)["chunk_id"] == "D#1"


def test_select_chunk_returns_none_when_name_absent():
    """이름이 어디에도 없으면 인용을 비운다 — 첫 문단을 근거로 지목하지 않는다 (리뷰 #3)."""
    chunks = _chunks("전혀 다른 상품 안내", "또 다른 문단")
    assert select_chunk("혁신성장촉진자금", chunks) is None


def test_select_chunk_empty_chunks():
    assert select_chunk("아무자금", []) is None


def test_build_finance_no_blind_first_chunk_fallback(tmp_path):
    """이름이 원문에 없는 상품은 doc_chunk_ref 가 비어야 한다 (리뷰 #3)."""
    (tmp_path / "소진공_x.txt").write_text(
        "대출 융자 한도 금리 보증 지원 소상공인 상환 기업 안내문.\n\n"
        "창업기업자금 대출 한도 7천만원 금리 보증 지원 소상공인 상환 기업.",
        encoding="utf-8")
    reviewed = [
        {"product_id": "p1", "name": "창업기업자금", "org": "소진공", "amount_max": 7000,
         "rate": 2.5, "status": "open", "doc": "소진공_x"},
        {"product_id": "p2", "name": "존재하지않는자금", "org": "소진공", "amount_max": 3000,
         "rate": 2.0, "status": "open", "doc": "소진공_x"},
    ]
    fp = build_finance(reviewed, docs_dir=tmp_path)["finance_product"].set_index("product_id")
    assert fp.loc["p1", "doc_chunk_ref"] == "소진공_x#1"
    assert fp.loc["p2", "doc_chunk_ref"] is None
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `cd ai && .venv/bin/python -m pytest tests/test_finance_load.py -q`
Expected: FAIL — `ImportError: cannot import name 'select_chunk'`

- [ ] **Step 3: `select_chunk`를 구현한다**

`ai/batch/load/finance.py`의 `chunk_document` 정의 바로 아래에 추가:

```python
_NAME_TOKEN = re.compile(r"[0-9A-Za-z가-힣]{2,}")


def select_chunk(name: str, chunks: list[dict]) -> dict | None:
    """상품명이 실제로 등장하는 청크를 고른다. 없으면 None (인용 비움).

    '문서 첫 문단' 폴백을 쓰지 않는다 — 그 문단은 대개 표지·브로슈어 머리말이고,
    그 상품의 자격 근거가 아니다. 근거가 아닌 문단을 근거로 지목하는 것은 날조는
    아니어도 귀속 오류이며, 이 서비스가 RAG 대신 id 직접 조회를 택한 이유와 정면으로
    어긋난다 (스펙 §5-4, 리뷰 #3).

    점수 = 상품명 토큰 중 청크에 등장하는 개수. 동점이면 **더 짧은** 청크가 이긴다 —
    통짜 첫 문단은 토큰을 많이 품지만 구체적인 근거는 짧은 문단에 있다.
    """
    tokens = _NAME_TOKEN.findall(name or "")
    if not tokens or not chunks:
        return None
    required = max(1, (len(tokens) + 1) // 2)  # 토큰 과반이 등장해야 인정
    best = min(
        chunks,
        key=lambda c: (-sum(1 for t in tokens if t in c["text"]), len(c["text"])),
    )
    hits = sum(1 for t in tokens if t in best["text"])
    return best if hits >= required else None
```

- [ ] **Step 4: `build_finance`가 그것을 쓰게 한다**

`ai/batch/load/finance.py:115-117`의 세 줄을 교체:

```python
        name = p.get("name") or ""
        ref = select_chunk(name, chunks)
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd ai && .venv/bin/python -m pytest tests/test_finance_load.py -q`
Expected: PASS (전건)

- [ ] **Step 6: lint + 전체 테스트**

Run: `cd ai && .venv/bin/python -m ruff check . && .venv/bin/python -m pytest -q`
Expected: 전건 PASS

- [ ] **Step 7: 커밋**

```bash
git add ai/batch/load/finance.py ai/tests/test_finance_load.py
git commit -m "[AI] fix: 근거 인용 '첫 문단' 폴백 제거 — 상품명 토큰 매칭 (리뷰 #3)"
```

---

## Task 4: 원문 청크의 NUL 바이트 제거 (지적 #4)

**문제:** `소진공_소상공인정책자금_지원사업안내.txt`에 pypdf가 남긴 NUL 59개가 `20_finance.sql`에 그대로 실린다. 적재는 성공하지만 psql이 NUL 주변을 삼켜 **DB 저장본이 파이썬 청크보다 411자 짧다**(7,688 → 7,277). 삭제된 구간에 `지원요건(2026년정책자금)세부지원요건…` 같은 실제 문장이 포함된다. 스키마 주석 "text 는 공고문 원문 그대로"와 문안 A "문자 단위로 일치"가 이 청크에서 성립하지 않는다.

**Files:**
- Modify: `ai/batch/load/finance.py:80-93` (`strip_print_artifacts`·`chunk_document`)
- Modify: `ai/batch/load/emit.py:13-29` (`_lit` 경계 방어)
- Test: `ai/tests/test_finance_load.py`, `ai/tests/test_emit.py`

**Interfaces:**
- Produces: `chunk_document`가 만든 어떤 `text`에도 제어문자 `\x00`이 없다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ai/tests/test_finance_load.py`에 추가:

```python
def test_chunk_document_strips_nul_bytes():
    """PDF 추출 아티팩트인 NUL 은 제거한다 — psql 이 주변 문장까지 삼킨다 (리뷰 #4).

    NUL 은 공고문의 글자가 아니라 pypdf 산출물의 제어문자다. 지우면 DB 저장본이
    파이썬 청크와 정확히 같아져 verbatim 대조가 성립한다.
    """
    chunks = chunk_document("소진공_x", "지원요건\x00(2026년정책자금)세부\x00지원요건.\n\n둘째 문단.")
    assert all("\x00" not in c["text"] for c in chunks)
    assert chunks[0]["text"] == "지원요건(2026년정책자금)세부지원요건."
```

`ai/tests/test_emit.py`에 추가:

```python
def test_to_insert_sql_rejects_nul_in_values():
    """NUL 이 덤프에 새면 psql 이 값 일부를 조용히 삼킨다 — 경계에서 막는다 (리뷰 #4)."""
    import pytest
    df = pd.DataFrame([{"text": "정상\x00문자열"}])
    with pytest.raises(ValueError, match="NUL"):
        to_insert_sql("finance_doc_chunk", df)
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `cd ai && .venv/bin/python -m pytest tests/test_finance_load.py::test_chunk_document_strips_nul_bytes tests/test_emit.py -q`
Expected: FAIL — NUL이 그대로 남고, `to_insert_sql`이 예외를 던지지 않는다.

- [ ] **Step 3: `strip_print_artifacts`에서 NUL을 제거한다**

`ai/batch/load/finance.py:80-84`를 교체:

```python
def strip_print_artifacts(text: str) -> str:
    """인쇄 머리말/꼬리말 줄 + NUL 제거. 본문 문장은 한 글자도 건드리지 않는다.

    NUL(`\\x00`)은 pypdf 텍스트 추출이 남기는 제어문자이지 공고문의 글자가 아니다.
    그대로 덤프에 실으면 psql 이 NUL 주변 구간을 조용히 삼켜 DB 저장본이 원문보다
    짧아진다(실측 411자 소실) — 「인용은 검색이지 생성이 아니다」가 깨지는 지점이다
    (스펙 §5-4, 리뷰 #4).
    """
    kept = [ln for ln in text.replace("\x00", "").splitlines()
            if not (_PRINT_HEADER.match(ln) or _PRINT_FOOTER.match(ln))]
    return "\n".join(kept)
```

- [ ] **Step 4: `emit._lit`에 경계 방어를 넣는다**

`ai/batch/load/emit.py`의 `_lit` 마지막 줄을 교체:

```python
    literal = str(v)
    if "\x00" in literal:
        raise ValueError(
            f"NUL 바이트가 값에 있다 — psql 이 주변 문자를 삼킨다 (리뷰 #4): {literal[:40]!r}"
        )
    return "'" + literal.replace("'", "''") + "'"
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd ai && .venv/bin/python -m ruff check . && .venv/bin/python -m pytest -q`
Expected: 전건 PASS

- [ ] **Step 6: 커밋**

```bash
git add ai/batch/load/finance.py ai/batch/load/emit.py ai/tests/test_finance_load.py ai/tests/test_emit.py
git commit -m "[AI] fix: 원문 청크 NUL 제거 + emit 경계 방어 — DB 저장본 411자 소실 해소 (리뷰 #4)"
```

---

## Task 5: 덤프 재생성 + Postgres 왕복 검증

Task 1~4의 결과를 실제 산출물에 반영하고, **DB에 적재한 뒤 다시 읽어** 원문과 일치하는지 확인한다. 이 왕복이 지적 #4를 실증적으로 닫는 유일한 방법이다.

**Files:**
- Regenerate: `db/init/10_data_core.sql`, `db/init/20_finance.sql`

- [ ] **Step 1: 코어 덤프 재생성**

```bash
cd ai && .venv/bin/python -m batch.load core
```

Expected: `serving: base 테이블 조립 시작` 로그 후 12테이블 행수 출력, `→ .../10_data_core.sql (12테이블)`

- [ ] **Step 2: 금융 덤프 재생성**

```bash
cd ai && .venv/bin/python -m batch.load finance
```

Expected: `finance: 검수본 26건 재생성` → `product 26 · chunk N`

- [ ] **Step 3: 권리금 클립 붕괴가 해소됐는지 확인**

```bash
cd /Users/yutak/Desktop/Ventry && ai/.venv/bin/python - <<'PY'
import sys, statistics; sys.path.insert(0,'ai')
from eval import common
from collections import Counter
t = open('db/init/10_data_core.sql', encoding='utf-8').read()
by = {}
for r in common._iter_sql_rows(t, 'initial_cost'):
    by.setdefault(r[1], []).append(int(r[4]))   # premium_low
for ind, v in by.items():
    top = Counter(v).most_common(1)[0]
    print(f"{ind}: distinct={len(set(v))} 최빈값={top[0]} ({top[1]}/{len(v)} = {100*top[1]/len(v):.1f}%)")
PY
```

Expected: 카페의 최빈값 비중이 25.4%에서 한 자릿수로 떨어지고 `distinct`가 크게 는다.

- [ ] **Step 4: 부담률 업종 정합 확인**

```bash
cd /Users/yutak/Desktop/Ventry && ai/.venv/bin/python - <<'PY'
import sys, statistics; sys.path.insert(0,'ai')
from eval import common
rows = common.load_serving_scores()
for ind in ('cafe', 'food'):
    br = [r['burden_ratio'] for r in rows if r['industry']==ind and r['burden_ratio'] is not None]
    print(f"{ind}: n={len(br)} 중앙값={statistics.median(br):.3f} θ0.15통과={sum(1 for b in br if b<=0.15)}")
PY
```

Expected: 카페 중앙값이 0.472에서 0.25 수준으로 내려가고 θ 통과 수가 176에서 유의미하게 는다.

- [ ] **Step 5: 인용 폴백이 사라졌는지 확인**

```bash
cd /Users/yutak/Desktop/Ventry && grep -oE "'[^']*#[0-9]+'\)," db/init/20_finance.sql | sort | uniq -c | sort -rn | head
```

Expected: `#0` 참조가 24건에서 크게 줄고 `#5`·`#6`·`#7` 등 자금별 문단이 등장한다.

- [ ] **Step 6: NUL이 사라졌는지 확인**

```bash
cd /Users/yutak/Desktop/Ventry && LC_ALL=C tr -dc '\000' < db/init/20_finance.sql | wc -c
```

Expected: `0`

- [ ] **Step 7: Postgres 왕복 검증 — DB 저장본 == 파이썬 청크**

```bash
cd /Users/yutak/Desktop/Ventry
docker rm -f ventry-verify-pg >/dev/null 2>&1
docker run --rm -d --name ventry-verify-pg -e POSTGRES_PASSWORD=x -e POSTGRES_DB=ventry \
  -v "$PWD/db/init:/docker-entrypoint-initdb.d:ro" postgres:16-alpine
sleep 25
docker logs ventry-verify-pg 2>&1 | grep -E "ERROR|FATAL" || echo "적재 오류 없음"
docker exec ventry-verify-pg psql -U postgres -d ventry -t -A -c \
  "select count(*) from finance_product;" -c "select count(*) from finance_doc_chunk;" \
  -c "select count(*) from v_candidate_area;"
```

Expected: 오류 없음, product 26 · chunk N · 뷰 2,500행

```bash
docker exec ventry-verify-pg psql -U postgres -d ventry -t -A -c \
  "copy (select chunk_id, text from finance_doc_chunk order by chunk_id) to stdout with (format csv)" \
  > /tmp/pg_chunks.csv
ai/.venv/bin/python - <<'PY'
import sys, csv, json; sys.path.insert(0,'ai')
from pathlib import Path
from batch.load.finance import chunk_document, _is_clean
D = Path('ai/data/interim/funding_docs')
db = {cid: txt for cid, txt in csv.reader(open('/tmp/pg_chunks.csv', encoding='utf-8'))}
bad = []
for cid, txt in db.items():
    doc = cid.rsplit('#', 1)[0]
    src = (D / f'{doc}.txt').read_text(encoding='utf-8')
    if txt not in src:
        bad.append(cid)
print(f"DB 청크 {len(db)}개 · 원문 부분문자열 아님: {len(bad)} {bad}")
PY
docker rm -f ventry-verify-pg >/dev/null 2>&1
```

Expected: `원문 부분문자열 아님: 0 []` — **이것이 지적 #4의 종결 조건이다.**

- [ ] **Step 8: 전체 테스트 (실데이터 통합 테스트 포함)**

Run: `cd ai && .venv/bin/python -m pytest -q`
Expected: 전건 PASS — 특히 `test_serving.py::test_initial_cost_carries_industry_aware_rent`

- [ ] **Step 9: 커밋**

```bash
git add db/init/10_data_core.sql db/init/20_finance.sql
git commit -m "[AI] data: 덤프 재생성 — 권리금 비례계수·업종별 임대료·인용 매칭·NUL 반영 (리뷰 #1~#4)"
```

---

## Task 6: 근거 충실도 지표를 실제 적재 산출물 기준으로 재정의 (지적 #5)

**문제:** `coverage = len(gold) / len(draft)` 는 분자가 골드 인용 13건, 분모가 **초안 추출 29건**이라 단위가 다르다. 적재본은 26건이고 전건 `doc_chunk_ref`가 채워져 있는데도 부록은 "붙지 않은 건은 비운 것"이라 설명한다 — 하네스 자신의 `legitimate_null_docs: []` 와도 어긋난다. 더 근본적으로 이 스위트는 **DB에 실린 청크를 한 번도 보지 않는다**.

**해결:** verbatim 검사는 `20_finance.sql`의 실제 청크 ↔ 원문 txt로 하고, coverage는 **적재 상품 중 인용이 붙은 비율**로 정의한다. 손으로 만든 골드 JSONL은 별도 스팟체크로 남긴다.

**Files:**
- Modify: `ai/eval/suites/grounding.py` (전면)
- Modify: `ai/eval/common.py` — `load_finance_chunks`·`load_finance_products` 추가
- Test: `ai/tests/test_eval_grounding.py`

**Interfaces:**
- Consumes: `common._iter_sql_rows_lines`(기존), `common.FINANCE_SQL`(기존 상수 — 지금까지 미사용)
- Produces: `common.load_finance_chunks(sql_path) -> dict[str, str]` (chunk_id → text), `common.load_finance_products(sql_path) -> list[tuple[str, str | None]]` (product_id, doc_chunk_ref)
- Produces: `grounding.evaluate(...)` 반환 키에 `shipped_products`·`shipped_chunks`·`chunk_verbatim_rate` 추가, `coverage` 의미 변경

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ai/tests/test_eval_grounding.py`에 추가:

```python
def test_coverage_is_over_shipped_products():
    """coverage 분모는 적재 상품 수다 — 초안 29건이 아니다 (리뷰 #5)."""
    from eval import common
    products = common.load_finance_products()
    chunks = common.load_finance_chunks()
    assert len(products) == 26
    assert chunks, "적재 청크가 없다"
    m = grounding.run(None)
    linked = sum(1 for _, ref in products if ref)
    assert m["shipped_products"] == 26
    assert abs(m["coverage"] - linked / 26) < 1e-9


def test_shipped_chunks_are_verbatim_substrings_of_source():
    """DB 에 실릴 청크가 원문의 부분문자열이어야 한다 — 골드가 아니라 실물 대조 (리뷰 #5)."""
    m = grounding.run(None)
    assert m["chunk_verbatim_rate"] == 1.0, m["chunk_mismatches"]
    assert m["shipped_chunks"] > 0
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `cd ai && .venv/bin/python -m pytest tests/test_eval_grounding.py -q`
Expected: FAIL — `AttributeError: module 'eval.common' has no attribute 'load_finance_products'`

- [ ] **Step 3: `common`에 적재본 로더를 추가한다**

`ai/eval/common.py`의 `load_serving_scores` 아래에 추가:

```python
def load_finance_chunks(sql_path: Path = FINANCE_SQL) -> dict[str, str]:
    """적재 덤프의 원문 청크 {chunk_id: text}. 청크 text 는 개행을 품으므로 블록째 파싱한다."""
    text = sql_path.read_text(encoding="utf-8")
    block = re.search(
        r"INSERT INTO finance_doc_chunk\b[^;]*?VALUES\s*(.*?);\s*\n", text, re.DOTALL
    )
    if not block:
        return {}
    reader = csv.reader(
        io.StringIO(block.group(1).strip().rstrip(",")), quotechar="'", skipinitialspace=True
    )
    out: dict[str, str] = {}
    pending: list[str] = []
    for fields in reader:
        pending.extend(fields)
        # (chunk_id, product_id, doc_meta, text) — text 안의 개행 때문에 행이 쪼개져 들어온다
        if len(pending) >= 4:
            chunk_id = pending[0].lstrip("(")
            out[chunk_id] = "\n".join(pending[3:]).rstrip(")")
            pending = []
    return out


def load_finance_products(sql_path: Path = FINANCE_SQL) -> list[tuple[str, str | None]]:
    """적재 덤프의 (product_id, doc_chunk_ref). ref 없으면 None."""
    text = sql_path.read_text(encoding="utf-8")
    rows = []
    for r in _iter_sql_rows_lines(text, "finance_product"):
        ref = r[-1]
        rows.append((r[0], None if ref in ("NULL", "") else ref))
    return rows
```

같은 파일 상단 import에 `io`를 추가한다 (`import csv` 다음 줄).

- [ ] **Step 4: `grounding` 스위트를 재정의한다**

`ai/eval/suites/grounding.py:19-45`를 교체:

```python
def evaluate(gold: list[dict], docs: dict[str, str]) -> dict:
    """근거 충실도.

    두 층을 잰다:
    - **적재 청크 verbatim** — `20_finance.sql` 에 실제로 실린 청크가 원문의 부분문자열인가.
      화면에 뜨는 문장이 곧 이 텍스트이므로 이것이 본 지표다 (리뷰 #5).
    - **골드 스팟체크** — 사람이 고른 인용 13건의 원문 일치(회귀 감시용).

    coverage 는 **적재 상품 중 인용이 붙은 비율**이다. 예전 정의(골드 13 ÷ 초안 29)는
    분자·분모 단위가 달라 "인용을 비웠다"는 서술과도 어긋났다.
    """
    products = common.load_finance_products()
    chunks = common.load_finance_chunks()
    linked_products = [pid for pid, ref in products if ref]

    chunk_mismatches = []
    for chunk_id, text in chunks.items():
        doc = chunk_id.rsplit("#", 1)[0]
        if text not in common.load_source_text(doc):
            chunk_mismatches.append(chunk_id)

    gold_mismatches = [g["product_id"] for g in gold if g["quote"] not in docs.get(g["doc"], "")]
    gold_docs = {g["doc"] for g in gold}
    draft_docs = {d["doc"] for d in common.load_extraction_draft()}
    legit_null = sorted(d for d in draft_docs
                        if not common.is_clean_source(d) and d not in gold_docs)
    unexpected_null = sorted(d for d in draft_docs
                             if common.is_clean_source(d) and d not in gold_docs)
    n_chunks = len(chunks)
    return {
        "shipped_products": len(products),
        "shipped_chunks": n_chunks,
        "linked_products": len(linked_products),
        "coverage": len(linked_products) / len(products) if products else 0.0,
        "chunk_verbatim_rate": (n_chunks - len(chunk_mismatches)) / n_chunks if n_chunks else 0.0,
        "chunk_mismatches": chunk_mismatches,
        "gold_spotcheck_n": len(gold),
        "gold_verbatim_rate": (len(gold) - len(gold_mismatches)) / len(gold) if gold else 0.0,
        "gold_mismatches": gold_mismatches,
        "legitimate_null_docs": legit_null,
        "unexpected_null_docs": unexpected_null,
    }
```

- [ ] **Step 5: 기존 골드 테스트의 키 이름을 맞춘다**

`ai/tests/test_eval_grounding.py`의 기존 3개 테스트에서 `m["verbatim_match_rate"]` → `m["gold_verbatim_rate"]`, `m["mismatches"]` → `m["gold_mismatches"]`, `m["linked"]` → `m["gold_spotcheck_n"]` 로 바꾼다.

- [ ] **Step 6: 테스트 통과 확인**

Run: `cd ai && .venv/bin/python -m ruff check . && .venv/bin/python -m pytest -q`
Expected: 전건 PASS

- [ ] **Step 7: 커밋**

```bash
git add ai/eval/common.py ai/eval/suites/grounding.py ai/tests/test_eval_grounding.py
git commit -m "[AI] fix: 근거 충실도를 적재 청크 실물 대조로 재정의 — coverage 분모 정정 (리뷰 #5)"
```

---

## Task 7: 문서 사실 정정 — 게이트 축 수 · 청크 카디널리티 (지적 #6, #7)

**문제 #6:** 부록 1·2의 게이트 표가 스펙 §12-3의 "방향 ≥ 4/5"를 그대로 옮겼는데, 구현은 판정 축 3개 + 비율 임계 0.8이라 **사실상 3/3 만점**을 요구한다. 같은 문서 안에서 "0/3축"과 충돌한다. assumptions #35 ⑤가 이미 "3개면 n/3로 보고, 게이트는 비율 환산"으로 재등록했다.

**문제 #7:** 기술설명서·심사_QA가 "상품↔청크 외래키로 1:1"이라 주장하지만 실제는 N:1이다(26건 → 청크 N개, `소진공_지원사업안내#0` 하나에 4개 상품). RAG 미도입 논거 자체는 유효하니 표현만 정확히 한다.

**Files:**
- Modify: `docs/부록1_2_평가성적표.md:101`
- Modify: `docs/기술설명서_원고.md:76`
- Modify: `docs/심사_QA.md:36`

- [ ] **Step 1: 부록 게이트 표를 실제 구현에 맞춘다**

`docs/부록1_2_평가성적표.md:101`을 교체:

```markdown
| 수록 게이트 | A: R²≥0.30 ∧ ρ≥0.60 ∧ 방향일치비율≥0.8 · B: R²≥0.15 ∧ (ρ≥0.45 ∨ 방향일치비율≥0.8) · C: 그 외 미수록 |
```

바로 아래 문단(게이트 사전 등재 설명) 끝에 한 문장 추가:

```markdown
스펙 §12-3의 원문 표기는 "방향 일치 ≥ 4/5축"이지만, 우리 설계에서 **판정 가능한 축은 3개**다
(w1은 4피처 다수결, w3·w4는 단일 피처, w2·w5는 매출 파생이라 애초에 피처가 아니다). 그래서
게이트는 축 수에 의존하지 않는 **비율 0.8**로 환산해 등재했다(assumptions #35 ⑤). 3축에서
0.8은 **3/3 만점**을 뜻하므로, 우리가 쓴 임계는 스펙 문안보다 오히려 엄격하다.
```

- [ ] **Step 2: 기술설명서의 1:1 주장을 정정한다**

`docs/기술설명서_원고.md:76` 문장을 교체:

```markdown
상품이 인용할 청크는 자격 필터가 이미 확정해 둔 것이라 검색할 질의가 없다. 상품→청크는
외래키(`doc_chunk_ref`) 직접 조회이며, 한 공고 문단이 여러 자금을 함께 규정하는 경우가 있어
관계는 N:1이다. 임베딩으로 뽑으면 다른 상품의 문단이
```

- [ ] **Step 3: 심사_QA 20번의 1:1 주장을 정정한다**

`docs/심사_QA.md:36`의 문장을 교체:

```markdown
    상품↔공고 원문 청크는 `doc_chunk_ref` 외래키로 직접 연결된다(한 문단이 여러 자금을 함께
    규정하는 공고가 있어 관계는 N:1이다). 입력이 자연어 질의가 아니라 상품
```

- [ ] **Step 4: 검증**

```bash
cd /Users/yutak/Desktop/Ventry && grep -n "4/5\|1:1" docs/부록1_2_평가성적표.md docs/기술설명서_원고.md docs/심사_QA.md
```

Expected: 게이트 표·본문에 남은 `4/5`는 "스펙 §12-3의 원문 표기는" 설명 문맥 하나뿐이고, `1:1` 주장은 사라졌다.

- [ ] **Step 5: 커밋**

```bash
git add docs/부록1_2_평가성적표.md docs/기술설명서_원고.md docs/심사_QA.md
git commit -m "[AI] docs: 게이트 축 수(3축·비율 0.8)·청크 카디널리티(N:1) 사실 정정 (리뷰 #6 #7)"
```

---

## Task 8: `gu_avg` 등급을 실제 자치구 평균으로 구현 (지적 #8)

**문제:** `rent_join.py`는 5등급을 문서화하며 `gu_avg`를 "자치구 내 구획들의 평균"으로 정의하지만, 수치를 붙이는 `serving._build_rent`에는 **권역 평균 경로밖에 없다**. 자치구 평균은 어디서도 계산되지 않아 `gu_avg`와 `region_avg`가 같은 값으로 수렴한다 — 5등급 중 두 등급이 사실상 하나다.

**Files:**
- Modify: `ai/batch/preprocess/rent_join.py:120-136` (`build` — 폴백 행에 자치구를 남긴다)
- Modify: `ai/batch/load/serving.py:74-115` (`_build_rent`)
- Test: `ai/tests/test_serving.py`

**Interfaces:**
- Consumes: `rent_assignment.csv`의 `assign_level`·`sigungu_name`·`reb_region` (기존 컬럼, 변경 없음)
- Produces: `_build_rent`가 `gu_avg` 행에 **자치구 내 구획 단가 평균**을, `region_avg` 행에만 권역 평균을 쓴다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ai/tests/test_serving.py`에 추가:

```python
def test_gu_avg_differs_from_region_avg(tables):
    """gu_avg 는 자치구 평균, region_avg 는 권역 평균 — 두 등급이 같은 값이면 5등급이 무의미하다.

    같은 권역 안에 gu_avg 자치구가 둘 이상이면 단가가 서로 달라야 한다 (리뷰 #8).
    """
    import pandas as pd
    from batch.paths import INTERIM_DIR
    assign = pd.read_csv(INTERIM_DIR / "join" / "rent_assignment.csv", dtype=str)
    assign.columns = [c.lstrip("﻿") for c in assign.columns]
    gu_rows = assign[assign["assign_level"] == "gu_avg"]
    if gu_rows["sigungu_name"].nunique() < 2:
        import pytest
        pytest.skip("gu_avg 자치구가 1개뿐 — 비교 불가")
    rent = tables["rent"].merge(
        assign[["area_code", "assign_level", "sigungu_name"]], on="area_code", how="left")
    gu = rent[rent["assign_level"] == "gu_avg"]
    assert gu.groupby("sigungu_name")["unit_price"].nunique().max() == 1  # 자치구 내 단일값
    assert gu["unit_price"].nunique() >= 2, "자치구가 달라도 단가가 같다 — 권역 평균을 쓰고 있다"
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `cd ai && .venv/bin/python -m pytest tests/test_serving.py::test_gu_avg_differs_from_region_avg -q`
Expected: FAIL — `자치구가 달라도 단가가 같다`

- [ ] **Step 3: `_build_rent`에 자치구 평균을 넣는다**

`ai/batch/load/serving.py:85-104`를 교체:

```python
    region_px: dict[str, float] = {}
    gu_px: dict[str, float] = {}
    tmp = assign.assign(_px=assign["reb_district_name"].map(_px_of))
    for region, g in tmp.groupby("reb_region"):
        vals = [v for v in g["_px"] if pd.notna(v)]
        if vals:
            region_px[region] = sum(vals) / len(vals)
    # 자치구 평균 — assign_level 'gu_avg' 의 정의(assumptions #18)를 실제로 구현한다.
    # 이게 없으면 gu_avg 가 권역 평균으로 흘러 region_avg 와 구분되지 않는다 (리뷰 #8).
    for sigungu, g in tmp.groupby("sigungu_name"):
        vals = [v for v in g["_px"] if pd.notna(v)]
        if vals:
            gu_px[sigungu] = sum(vals) / len(vals)

    def _opt(lookup: dict, name: str):  # 전환율·공실 값(round) 또는 None
        hit = lookup.get(name)
        return round(hit[0], 3) if hit else None

    rows = []
    for r in assign.itertuples():
        name = (r.reb_district_name or "").strip()
        px, store_type = unit_px.get(name, (None, None))
        fallback = str(r.fallback_flag).lower() == "true"
        if px is None:  # 폴백 — 자치구 평균 우선, 구획 없는 자치구만 권역 평균
            px = gu_px.get(r.sigungu_name) or region_px.get(r.reb_region)
            store_type, fallback = None, True
        if px is None:
            continue
```

- [ ] **Step 4: 덤프 재생성**

```bash
cd ai && .venv/bin/python -m batch.load core
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd ai && .venv/bin/python -m ruff check . && .venv/bin/python -m pytest -q`
Expected: 전건 PASS

- [ ] **Step 6: 등급 분포 로그 확인**

```bash
cd /Users/yutak/Desktop/Ventry && ai/.venv/bin/python - <<'PY'
import sys; sys.path.insert(0,'ai')
import pandas as pd
from batch.paths import INTERIM_DIR
from eval import common
a = pd.read_csv(INTERIM_DIR/'join'/'rent_assignment.csv', dtype=str)
a.columns = [c.lstrip('﻿') for c in a.columns]
t = open('db/init/10_data_core.sql', encoding='utf-8').read()
px = {r[0]: float(r[3]) for r in common._iter_sql_rows(t, 'rent')}
a['px'] = a['area_code'].map(px)
print(a.groupby('assign_level')['px'].agg(['count','nunique','mean']).round(2))
PY
```

Expected: `gu_avg`의 `nunique`가 1보다 크고 `region_avg`와 평균이 다르다.

- [ ] **Step 7: 커밋**

```bash
git add ai/batch/load/serving.py ai/tests/test_serving.py db/init/10_data_core.sql
git commit -m "[AI] fix: gu_avg 등급을 실제 자치구 평균으로 구현 — region_avg 와 분리 (리뷰 #8)"
```

---

## Task 9: 낮음 5건 일괄 정리 (지적 #9~#13)

- **#9** 수집 순서 의존: `funding_web` 실패 시 모지바케 회귀
- **#10** `scipy` 미선언 (sklearn 전이 의존에 기대고 있음)
- **#11** `load_model_features`의 `float(r[3])`이 transit `distance_m` NULL에서 크래시
- **#12** `est_sales=0` 카페 1행이 θ 필터에서 조용히 누락
- **#13** `common.FINANCE_SQL` 미사용 상수 → Task 6에서 이미 소비하므로 확인만

**Files:**
- Modify: `ai/batch/collect/funding_docs.py`
- Modify: `ai/requirements.txt`
- Modify: `ai/eval/common.py:146-149`
- Modify: `ai/eval/suites/sensitivity.py:27-46`
- Test: `ai/tests/test_eval_model.py`, `ai/tests/test_eval_sensitivity.py`

- [ ] **Step 1: 실패하는 테스트를 쓴다 (#11, #12)**

`ai/tests/test_eval_model.py`에 추가:

```python
def test_transit_null_distance_does_not_crash(tmp_path):
    """transit.distance_m 이 NULL 이어도 로더가 죽지 않고 그 상권을 건너뛴다 (리뷰 #11)."""
    sql = tmp_path / "core.sql"
    sql.write_text(
        "INSERT INTO commercial_area (area_code, name, a, b, sigungu_code) VALUES\n"
        "('A1', 'x', 'A', 'B', '11110');\n"
        "INSERT INTO transit (area_code, nearest_station, line, distance_m, daily_riders, fb) VALUES\n"
        "('A1', '역', '1', NULL, NULL, FALSE);\n",
        encoding="utf-8",
    )
    rows, _ = common.load_model_features(sql)
    assert rows == []
```

`ai/tests/test_eval_sensitivity.py`에 추가:

```python
def test_zero_est_sales_row_is_reported_not_silently_dropped():
    """부담률을 못 구한 행은 n_areas 에서 빼고 별도로 센다 (리뷰 #12)."""
    rows = _rows()
    rows.append({"area_code": "E", "industry": "cafe", "w1": 0.5, "w2": 0.5, "w3": 0.5,
                 "w4": 0.5, "w5": 0.5, "est_sales": 0, "monthly_rent": 100,
                 "burden_ratio": None})
    m = sensitivity.evaluate(rows)
    assert m["cafe"]["n_areas"] == 4          # 부담률 산출 가능 행만
    assert m["cafe"]["n_burden_unavailable"] == 1
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `cd ai && .venv/bin/python -m pytest tests/test_eval_model.py::test_transit_null_distance_does_not_crash tests/test_eval_sensitivity.py -q`
Expected: FAIL — 각각 `ValueError: could not convert string to float: 'NULL'` 과 `KeyError: 'n_burden_unavailable'`

- [ ] **Step 3: `#11` transit NULL 방어**

`ai/eval/common.py:146-149`를 교체:

```python
    # distance_m·daily_riders 는 역 미매칭 상권에서 NULL 이 될 수 있다 (리뷰 #11).
    transit = {}
    for r in _iter_sql_rows_lines(text, "transit"):
        if "NULL" in (r[3], r[4]):
            continue
        transit[r[0]] = float(r[4]) * math.exp(-float(r[3]) / _TRANSIT_DECAY_M)
```

`load_model_features` 상단의 `import math`를 함수 밖 모듈 상단으로 올린다(`import csv` 다음 줄에 `import math`), 함수 안의 `import math` 줄은 지운다.

- [ ] **Step 4: `#12` 부담률 미산출 행을 드러낸다**

`ai/eval/suites/sensitivity.py:27-46`의 `evaluate`에서 업종별 집계를 교체:

```python
def evaluate(rows: list[dict]) -> dict:
    by_ind: dict[str, list] = defaultdict(list)
    for r in rows:
        by_ind[r["industry"]].append(r)
    out: dict[str, dict] = {}
    for ind, all_rows in by_ind.items():
        # 부담률을 못 구한 행(임대료 미매칭·추정매출 0)은 θ 필터에 태울 수 없다.
        # 조용히 빼면 n_areas 가 실제 평가 대상과 어긋나므로 따로 센다 (리뷰 #12).
        irows = [r for r in all_rows if r["burden_ratio"] is not None]
        unavailable = len(all_rows) - len(irows)
        base = top3(irows, WEIGHTS, THETA)
        retentions = []
        for k in WEIGHTS:                       # 가중치 단일축 ±20% (10회)
            for delta in (0.8, 1.2):
                w = {**WEIGHTS, k: WEIGHTS[k] * delta}
                retentions.append(_overlap(base, top3(irows, w, THETA)))
        for th in (0.10, 0.20):                 # θ 변동 (2회)
            retentions.append(_overlap(base, top3(irows, WEIGHTS, th)))
        out[ind] = {
            "base_top3": base,
            "mean_retention": sum(retentions) / len(retentions) if retentions else 0.0,
            "n_perturbations": len(retentions),
            "n_areas": len(irows),
            "n_burden_unavailable": unavailable,
        }
    return out
```

- [ ] **Step 5: `#10` scipy 명시**

`ai/requirements.txt`의 평가 하네스 블록에 추가 (scikit-learn 줄 아래):

```
scipy>=1.13          # spearmanr — sklearn 전이 의존에 기대지 않고 명시한다
```

- [ ] **Step 6: `#9` 수집 순서 의존 제거**

`ai/batch/collect/funding_docs.py:57-72`의 `run`을 교체:

```python
# 웹 1차 출처(funding_web)가 정본인 문서 — PDF 프린트본은 모지바케라 덮어쓰면 안 된다.
# 이게 없으면 `make collect` 도중 funding_web 이 네트워크 실패로 건너뛸 때, 앞서 돈
# funding_docs 가 이미 좋은 텍스트를 깨진 것으로 갈아엎은 뒤다 (assumptions #32, 리뷰 #9).
WEB_CANONICAL_PREFIX = "서울신보_"


def run(env: dict[str, str], session: object | None = None) -> None:
    out_dir = INTERIM_DIR / "funding_docs"
    out_dir.mkdir(parents=True, exist_ok=True)
    pdfs = sorted(RAW_DIR.glob("*.pdf"))
    if not pdfs:
        logger.warning("PDF 없음: %s/*.pdf — 매니페스트(README.md) 참조", RAW_DIR)
        return
    garbled_count = 0
    for pdf in pdfs:
        target = out_dir / f"{pdf.stem}.txt"
        if pdf.stem.startswith(WEB_CANONICAL_PREFIX) and target.exists():
            logger.info("%s: 웹 정본 유지 — PDF 추출본으로 덮지 않음", pdf.name)
            continue
        text = extract_text(pdf)
        target.write_text(text, encoding="utf-8")
        garbled = looks_garbled(text)
        garbled_count += garbled
        logger.info("%s: %d자%s", pdf.name, len(text), " ⚠️ 깨짐(OCR 필요)" if garbled else "")
    if garbled_count:
        logger.warning("텍스트 깨짐 %d건 — AI-06에서 OCR/수동 전사 필요", garbled_count)
```

- [ ] **Step 7: `#13` 확인**

Run: `cd /Users/yutak/Desktop/Ventry && grep -rn "FINANCE_SQL" ai`
Expected: `common.py`의 정의 + `load_finance_chunks`·`load_finance_products`의 기본 인자 — Task 6에서 소비 중이므로 별도 조치 없음.

- [ ] **Step 8: 테스트 통과 확인**

Run: `cd ai && .venv/bin/python -m ruff check . && .venv/bin/python -m pytest -q`
Expected: 전건 PASS

- [ ] **Step 9: 커밋**

```bash
git add ai/eval/common.py ai/eval/suites/sensitivity.py ai/requirements.txt ai/batch/collect/funding_docs.py ai/tests/test_eval_model.py ai/tests/test_eval_sensitivity.py
git commit -m "[AI] fix: transit NULL 방어·부담률 미산출 노출·scipy 명시·수집 순서 의존 제거 (리뷰 #9~#13)"
```

---

## Task 10: `make eval` 재실행 → 부록·기술설명서·assumptions 수치 갱신

**전제:** Task 1~9가 끝나 있어야 한다. 문서 수치는 `ai/eval/out/metrics.json`에서 **그대로** 옮긴다 — 문서 작성 중 재계산·조정 금지(스펙 §0-1).

**Files:**
- Regenerate: `ai/eval/out/metrics.json`, `*.png`
- Modify: `docs/부록1_2_평가성적표.md`, `docs/기술설명서_원고.md`, `docs/심사_QA.md`, `docs/assumptions.md`

- [ ] **Step 1: 평가 하네스 전량 재실행**

```bash
cd ai && make eval PYTHON=.venv/bin/python
```

Expected: 5개 스위트 실행 후 `metrics.json` 생성. `model.gate`가 A/B/C 중 무엇으로 나오든 **그대로 받는다** — 임계치를 결과에 맞춰 움직이지 않는다(assumptions #35 ⑦).

- [ ] **Step 2: 갱신 대상 수치를 뽑는다**

```bash
cd /Users/yutak/Desktop/Ventry && ai/.venv/bin/python - <<'PY'
import json
m = json.load(open('ai/eval/out/metrics.json', encoding='utf-8'))
e, g, s, d = m['extraction'], m['grounding'], m['sensitivity'], m['model']
print(f"추출  P {e['product_precision']:.3f} · R {e['product_recall']:.3f} · 필드 {e['field_accuracy']:.3f}")
print(f"근거  coverage {g['linked_products']}/{g['shipped_products']} ({g['coverage']:.1%}) · 청크 verbatim {g['chunk_verbatim_rate']:.3f} · 골드 {g['gold_verbatim_rate']:.3f}")
for k, v in s.items():
    print(f"민감도 {k}: {v['mean_retention']:.3f} (n={v['n_areas']}, 부담률불가 {v['n_burden_unavailable']})")
print(f"모델  게이트 {d['gate']} ({d['gate_path']}) · R² {d['r2_median']:.3f} · ρ {d['rho_median']:.3f} · 방향 {d['direction']['hits']}/{d['direction']['total']}")
print(f"      WAPE {d['wape_median']:.3f} · MAE {d['mae_median']:.1f} · baseline R² {d['baseline_r2_median']:.4f}")
print("      부호:", {k: (v['observed_sign'], v['design_sign'], round(v['mean_abs_shap'],3), round(v['raw_spearman'],3)) for k, v in d['direction']['by_feature'].items()})
print("생성:", m['generated_at'])
PY
```

- [ ] **Step 3: 부록 1·2를 갱신한다**

`docs/부록1_2_평가성적표.md`에서 아래를 Step 2 출력값으로 **그대로** 교체한다:
- 헤더의 **산출 시각**
- 부록 1 성적표 표의 1~4행 결과 칸
- 「1. 정책자금 추출 정확도」의 필드별 표와 해석 수치
- 「2. 근거 충실도」 전체 — coverage 정의가 바뀌었으므로 문단을 다시 쓴다:

```markdown
### 2. 근거 충실도 (`make eval-grounding`)

- **적재 청크 verbatim 일치율 {chunk_verbatim_rate}** — `db/init/20_finance.sql`에 실린 청크
  전건이 공고문 원문의 바이트 정확 부분문자열이다. 화면에 뜨는 문장이 곧 이 텍스트다.
- **coverage {linked_products}/{shipped_products}** — 적재 상품 중 인용이 붙은 비율이다.
  인용은 상품명이 실제로 등장하는 문단에만 붙이며, 어느 문단에서도 확인되지 않으면 비운다 —
  문서 첫 문단을 근거로 지목하지 않는다.
- 골드 스팟체크 {gold_spotcheck_n}건(사람이 고른 인용)도 일치율 {gold_verbatim_rate}로 회귀 감시한다.

> 인용은 검색이지 생성이 아니다 — 화면의 인용문은 `finance_doc_chunk.text`에서 그대로 오며,
> 인용 가능한 원문이 없으면 출처 기관·문서명·기준일만 노출하고 인용은 비운다.
```

- 「3. 민감도」의 유지율·상권 수
- 부록 2의 결과 표(R²·ρ·WAPE·MAE)·판정 문단·부호 반전 표

> ⚠️ **게이트가 바뀌면** `GATE_NOTE`가 지시하는 수위를 따른다. C면 부록 2를 삭제하고 성적표에서
> 행을 뺀다(스펙 §12-3, 리스크 #19). 결과에 맞춰 임계치를 조정하지 않는다.

- [ ] **Step 4: 기술설명서·심사_QA의 수치를 맞춘다**

`docs/기술설명서_원고.md`:
- 44행 「정책자금 26건 · 원문 청크 14개」 → 실제 청크 수로 교체
- 147~150행 성적표 3줄을 Step 2 값으로 교체

`docs/심사_QA.md`:
- 「수치 근거」 항목의 `coverage 13/29 (44.8%) · verbatim 일치율 1.0` 을 새 정의·새 값으로 교체하고, "붙지 않은 건은 원문 텍스트 품질이 인용 기준에 못 미쳐 비운 것" 문장을 아래로 교체:

```markdown
  붙지 않은 건은 상품명이 원문 어디에서도 확인되지 않아 **비운 것**이다(지어내지 않았고,
  문서 첫 문단을 근거로 갖다 붙이지도 않았다).
```

- 문안 B의 「정책자금 26건 전건이 원문 청크 14개에 연결」을 실제 수치로 교체

- [ ] **Step 5: assumptions.md에 변경 4건을 등재한다**

표 마지막 번호 다음부터 이어서 추가한다(현재 마지막은 #43이므로 #44~#47).

```markdown
| 44 | 2026-07-25 | AI | **권리금 비례계수를 단가 비로 교체 (리뷰 #1).** 구 산식은 `ratio = 환산임대료/서울중위환산임대료` 였는데 분자에 대표면적이 들어가고 분모는 음식점 55.2㎡ 기준 단일값이라, 카페가 구조적으로 0.53배 작아져 하한 클립(0.5)에 걸렸다 — 실측 카페 419/1,650행(25.4%)의 권리금이 649만원 상수로 붕괴. `ratio = 단가/서울중위단가`(천원/㎡)로 바꿔 대표면적 이중 반영을 제거했다 | `ai/batch/preprocess/cost.py:premium_interval`, `serving._derive` | `initial_cost.premium_*`·`cost_incl_premium_*` 전건, 프론티어 경계 |
| 45 | 2026-07-25 | AI | **업종별 환산임대료를 `initial_cost.monthly_rent` 로 노출 (리뷰 #2).** `rent` 는 `area_code` 단일 PK라 업종 축이 없어 카페가 음식점 55.2㎡ 임대료를 29.2㎡ 매출로 나눴다(부담률 중앙값 카페 0.472 vs 음식점 0.209, θ=0.15 통과 카페 176/1,060). `initial_cost` 에 `monthly_rent` 를 되살리고 `v_candidate_area` 가 그것을 노출한다 — 뷰 컬럼 이름·타입 불변이라 BE 코드 무변경, **값만** 바뀐다 | `db/init/01_schema.sql`, `ai/batch/load/serving.py`, `ai/eval/common.py` | 부담률·θ 필터·역방향 판정, BE 공지 `docs/HANDOFF_BACKEND.md` |
| 46 | 2026-07-25 | AI | **근거 인용의 '문서 첫 문단' 폴백 제거 (리뷰 #3).** 구 구현은 상품명이 청크에 없으면 `chunks[0]` 을 지목했고, 실측 26건 중 24건이 그 경로였다(F-000 인용문 = 브로슈어 머리말). 상품명 토큰 과반이 등장하는 청크만 고르고 동점이면 더 짧은 청크를 쓰며, 미달이면 `doc_chunk_ref=NULL`. 날조는 아니지만 **귀속 오류**이고, RAG 대신 id 직접 조회를 택한 논거(심사_QA 20)와 정면으로 어긋났다 | `ai/batch/load/finance.py:select_chunk` | `finance_doc_chunk` 구성, 근거 충실도 지표 |
| 47 | 2026-07-25 | AI | **원문 청크에서 NUL 바이트를 제거한다 (리뷰 #4).** `소진공_소상공인정책자금_지원사업안내.txt` 에 pypdf 가 남긴 NUL 59개가 덤프에 실렸고, psql 이 NUL 주변을 삼켜 **DB 저장본이 파이썬 청크보다 411자 짧았다**(7,688 → 7,277). NUL 은 공고문의 글자가 아니라 추출 산출물의 제어문자이므로, 지우면 오히려 verbatim 이 복원된다. `emit._lit` 에도 경계 방어를 뒀다. 검증은 Postgres 적재 후 원문 부분문자열 대조 왕복 | `ai/batch/load/finance.py:strip_print_artifacts`, `ai/batch/load/emit.py` | `finance_doc_chunk.text`, 근거 충실도 |
```

「등재 대기 항목」 체크리스트의 AI-05 미완 2줄은 이번 변경으로 산식이 확정됐으므로 상태를 갱신한다:

```markdown
- [x] AI-05: 거리 감쇠 파라미터(기준 500m) 근거 / w1 성분 결합·정규화 규칙 / 가중치 값 → 등재 #6·#41
- [x] AI-05: 권리금 "연간 조사(전년 기준)" 라벨·수치 / 예비 운영자금 = 월고정비×6개월 관행 출처 → 등재 #41·#44
```

- [ ] **Step 6: 최종 검증 3종**

```bash
cd ai && .venv/bin/python -m ruff check . && .venv/bin/python -m pytest -q
```
Expected: `All checks passed!` + 전건 PASS

```bash
cd /Users/yutak/Desktop/Ventry
docker rm -f ventry-final-pg >/dev/null 2>&1
docker run --rm -d --name ventry-final-pg -e POSTGRES_PASSWORD=x -e POSTGRES_DB=ventry \
  -v "$PWD/db/init:/docker-entrypoint-initdb.d:ro" postgres:16-alpine
sleep 25
docker logs ventry-final-pg 2>&1 | grep -E "ERROR|FATAL" || echo "적재 오류 없음"
docker exec ventry-final-pg psql -U postgres -d ventry -t -A \
  -c "select count(*) from v_candidate_area;" -c "select count(*) from finance_product;"
docker rm -f ventry-final-pg >/dev/null 2>&1
```
Expected: 오류 없음 · 뷰 2,500행 · 상품 26건

문서에 남은 옛 수치가 없는지 훑는다:
```bash
grep -rn "13/29\|44.8\|0.867\|0.472\|방향≥4/5" docs/*.md
```
Expected: 매치 없음(또는 의도적으로 남긴 「구 수치」 설명 문맥뿐)

- [ ] **Step 7: 커밋**

```bash
git add ai/eval/out docs/부록1_2_평가성적표.md docs/기술설명서_원고.md docs/심사_QA.md docs/assumptions.md
git commit -m "[AI] docs: make eval 재실행 결과 반영 — 부록 1·2 수치 갱신 + 가정 #44~#47 등재 (리뷰 전건)"
```

---

## 완료 조건

- [ ] `cd ai && .venv/bin/python -m ruff check .` → `All checks passed!`
- [ ] `cd ai && .venv/bin/python -m pytest -q` → 전건 PASS (신규 테스트 포함)
- [ ] Postgres 왕복: `db/init/*.sql` 4개 적재 오류 0 · 뷰 2,500행 · 상품 26건
- [ ] DB 청크 전건이 원문 txt의 부분문자열 (지적 #4 종결 조건)
- [ ] `20_finance.sql`의 NUL 바이트 0개
- [ ] 부록 1·2 / 기술설명서 / 심사_QA의 모든 수치가 `ai/eval/out/metrics.json`과 일치
- [ ] `docs/assumptions.md`에 #44~#47 등재
- [ ] `docs/HANDOFF_BACKEND.md`에 DDL 변경 공지 기재

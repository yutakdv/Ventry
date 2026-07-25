# AI-08 게이트 사전 등록 + 정책자금 출처 검증 + CP4 문서 선작성 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 열린 이슈 5건(#72·#21·#65·#22·#26)을 팀 표결 없이 자체 판단으로 확정 처리하고, 각 판단 근거를 문서에 남긴다.

**Architecture:** 세 갈래다. ①`ai/eval/suites/model.py` 신설로 `make eval-model`을 실제 구현(현재 passthrough 스텁) — 단, 게이트·프로토콜을 **코드보다 먼저** `docs/assumptions.md`에 등재해 사후 조정 여지를 없앤다. ②정책자금 데이터의 **출처 검증**: 서울신보 6종 원문을 HTML로 재수집해 청킹하고, 출처가 확인되지 않는 소진공 금리 수치를 골드셋에서 제거한다. ③CM 문서 2건은 판정 전 선작성이 요구되는 부분만 채우고 사람 게이트는 남긴다.

**Tech Stack:** Python 3.11 (`ai/.venv`) · lightgbm · shap · scikit-learn · pandas · matplotlib(Agg) · curl(poppler `pdftotext`는 보조) · pytest · ruff

## Global Constraints

- **스펙 §0-1**: 모든 숫자는 결정적 계산이 만든다. LLM의 수치 생성·재계산 금지. **출처가 확인되지 않는 수치는 적재하지 않는다** — 채우는 것보다 NULL이 안전하다.
- **스펙 §0-1 원칙 2**: 서빙 경로에 ML 모델 없음. `lightgbm`·`shap`은 `ai/` 배치 requirements에만 존재해야 한다. 서빙 코드·API·DB 스키마 무변경.
- **스펙 §12-2**: 타깃 = log(월 **점포당** 추정매출). 피처 = 비매출 성분만(w1 3성분 + w3 경쟁밀도 + w4 상권변화 + 선택 임대료). **w2·w5는 매출 파생이므로 배제**(누수). 검증 = **자치구 블록 5-fold 그룹아웃 CV**. 지표 = fold **중앙값** R² / Spearman ρ / WAPE·MAE(만원) / 베이스라인(자치구 평균) 대비 개선. **MAPE 미사용.**
- **스펙 §12-3 게이트(사전 등록, 결과 확인 전 고정)**: A = R² ≥ 0.30 **그리고** ρ ≥ 0.60 **그리고** SHAP 방향 일치 ≥ 4/5축 / B = R² ≥ 0.15 **그리고** (ρ ≥ 0.45 **또는** 방향 일치 ≥ 4/5축) / C = 그 외.
- **리스크 #19**: 프로토콜 변경이 불가피하면 **실행 전** assumptions.md 재등록만 허용. 사후 조정 금지. 결과가 좋아도 서술은 "설계 교차 검증" 한정 — "매출 예측" 표현 금지.
- **용어 컴플라이언스**: 판정 4단계 `적합/조건부 적합/유의/범위 외`. "승인" 계열 금지. 자금 관련 "권장/추천" 금지.
- **커밋**: `[AI|CM] type: 요약`. **`Co-Authored-By: Claude`·`Generated with Claude Code` 절대 금지.**
- **브랜치 2단계**: 토픽 → (로컬 병합) → `ai` → (PR) → `develop`.

---

## 사전 조사에서 확정된 사실 (계획의 근거)

이 계획은 다음 실측 위에 서 있다. 실행자는 재확인 없이 사실로 취급해도 된다.

| # | 사실 | 확인 방법 |
|---|---|---|
| F1 | `ai/eval/suites/model.py`·`matching.py` **부재** → `make eval-model`은 `run.py`의 `PASSTHROUGH`가 안내문만 출력하고 통과 | `ls ai/eval/suites/` |
| F2 | `location_score` 2,500행(1,467상권 × cafe 1,061·food 1,439), `commercial_area` 1,650행에 `sigungu_name` 보유 → 자치구 블록 CV 가능 | `eval.common.load_serving_scores()` |
| F3 | 서울신보 raw PDF 6종은 리포에 **있으나** 텍스트 레이어의 폰트 CID가 깨져 모지바케(`폀옠6먗퓏핯…`) → `doc_chunk_ref` 전건 NULL | `ai/data/interim/funding_docs/서울신보_*.txt` |
| F4 | 서울신보 원본 URL은 모지바케 텍스트 안에 ASCII로 살아 있음. `curl`은 정상 200 (WebFetch는 인증서 체인 검증 실패) | 아래 URL 표 |
| F5 | 소진공 금리 수치는 리포 내 **PDF**에는 없다. 융자공고·지원사업안내 PDF를 `pdftotext -layout`으로 재추출해도 0건이며, 융자공고 원문은 "정책자금 기준금리 및 분기별 대출금리는 **소진공 홈페이지에 공지**"라고만 명시 | `pdftotext -layout` + 토큰 카운트 |
| F5' | **그 홈페이지 공지가 정적 HTML로 수집 가능하다.** `https://www.semas.or.kr/web/SUP01/SUP0103/SUP010301.kmdc` 의 「금리안내」 표에 **‘26년 3/4분기 정책자금 금리(’26.7.10부터 적용)** 전문이 있다: **기준금리 3.85%** + 자금별 가산금리 → 연 3.85~5.45%. (PDF 인쇄본이 이 표를 누락했던 것) | `curl` + HTML 태그→개행 변환 후 파싱 |
| F6 | `ai/eval/gold/extraction_confirmed.json`의 "지원사업안내 금리표 '연 4.25%'(3.85%+0.4%p) 원문값 유지" 노트 5건은 **F5'의 공식 표와 정확히 일치한다 — 수치는 옳았다.** 결함은 값이 아니라 **그 출처 텍스트가 코퍼스에 저장돼 있지 않다는 것**이다 | 공식 표 ↔ `20_finance.sql` 소진공 14건 전건 대조 |
| F6' | 공식 표는 SQL에 **이미 채워진** 값과도 전건 정합: F-013 재해 2.0=고정 연2.00% · F-016 대환 4.5=연4.50% · F-020 장애인 2.0=연2.00% · F-022~F-025 4.25/4.25/4.25/4.45 전부 일치 | 동일 대조 |
| F7 | `db/init/20_finance.sql` 26건 중 **14건이 `rate = NULL`**(54%). BE `FundingProduct.rate`는 원시 `double`이라 실테이블 결선 시 NULL→0.0 강등 위험 | SQL 파싱 + `FundingProduct.java:18` |
| F8 | 현재 grounding coverage = **6/29 (20.7%)**, 서울신보 6개 문서가 `legitimate_null_docs`로 **오분류**되어 있음 | `ai/eval/out/metrics.json` |
| F9 | `docs/심사_QA.md`에 §5-4 RAG 구현/이월 이원화 문안이 **없음** | `grep '5-4\|RAG\|이월'` → 0건 |
| F10 | README 「심사 도슨트 가이드 (3분)」는 **이미 존재**(L14~37). 미충족분은 기술설명서 장 번호 앵커와 외부 1인 실측뿐 | `README.md:14` |

**서울신보 재수집 URL (F4):**

| 상품 | mng_cd |
|---|---|
| ESG 실천기업 | `BUSI5389` |
| 지능형 모바일 자동심사 | `BUSI2346` |
| 미래 유망기업 성장지원 | `BUSI4617` |
| 일자리창출·사회적경제 | `BUSI4763` |
| 장애인 기업 | `BUSI5388` |
| 창업자금·사업장 임차자금 | `BUSI5337` |

베이스: `https://www.seoulshinbo.co.kr/wbase/contents.do?mng_cd=<mng_cd>`
F-005(서울형 자영업자 위기극복 안심통장)는 대응 PDF·URL 모두 없음 → 8건 중 7건 커버가 상한.

---

## 자체 채택 결정 (팀 표결 대체 — 근거를 문서에 남긴다)

이 계획은 다음 5개 결정을 **채택된 것으로** 실행한다. 각 결정은 `docs/DECISIONS.md`에 등재하며, 이견 발생 시 문서를 고치고 코드를 되돌린다.

**D-A. 소진공 「금리안내」 공식 표를 1차 출처로 재수집하고, 변동 7건의 `rate`를 표값으로 채운다.**
초안에서는 "출처가 없으니 NULL 유지"로 갔으나 F5'가 이를 뒤집었다. PDF 인쇄본이 표를 누락했을 뿐, 공시 자체는 정적 HTML로 수집·저장·재현이 가능하다. **비우는 것이 정직이 아니라, 출처를 리포에 저장하고 채우는 것이 정직이다.** 웹 검색으로 나온 2차 출처(블로그의 1분기 2.96%·2분기 3.44%)는 여전히 미채택한다 — 채택 근거는 오직 semas.or.kr 공식 표다.

기준금리 **3.85%** (‘26년 3/4분기, 2026-07-10 적용). 채울 7건:

| product_id | 자금명 | 가산 | rate |
|---|---|---|---|
| F-012 | 일반경영안정자금 | +0.6%p | **4.45** |
| F-014 | 긴급경영안정자금 (일시적 경영애로) | +0.0%p | **3.85** |
| F-015 | 신용취약소상공인자금 | +1.6%p | **5.45** |
| F-017 | 재도전특별자금 (일반형) | +1.6%p | **5.45** |
| F-018 | 재도전특별자금 (희망형) | +0.6%p | **4.45** |
| F-019 | 재도전특별자금 (도약형) | +0.4%p | **4.25** |
| F-021 | 청년고용연계자금 | +0.0%p | **3.85** |

**D-B. 골드셋 수치는 옳았다 — 제거하지 않고 출처를 코퍼스에 편입한다 (F6).**
초안의 "미검증 수치 제거"는 F6' 대조로 철회한다. `extraction_confirmed.json`의 3.85/4.25/4.45는 공식 표와 정확히 일치한다. 진짜 결함은 **검증에 쓸 원문이 저장돼 있지 않았다는 것**이므로, 금리표 페이지를 `funding_docs/`에 수집해 출처 검증 테스트가 통과하게 만든다. 값이 아니라 근거를 고친다.

**D-C. 서울신보는 HTML 재수집으로 해결한다 (OCR 아님).**
tesseract 미설치이며 설치는 심사 환경 재현성을 해친다. `curl` HTML은 200으로 확인됐고, 웹 원문이 PDF 인쇄본보다 **오히려 1차 출처에 가깝다**(§5-4 "인용은 검색이지 생성이 아니다"에 정합). 재수집 스크립트를 `ai/batch/collect/`에 남겨 재현 가능하게 한다.

**D-D. AI-08은 프로토콜·게이트 등재 커밋을 모델 코드보다 먼저 올린다.**
§12-3의 방어선은 임계치가 아니라 **"결과를 보기 전에 등록했다"는 커밋 순서**다. 순서가 뒤집히면 게이트의 심사 가치가 0이 된다. Task 3(등재)과 Task 4(구현)를 반드시 별도 커밋으로 분리한다.

**D-E. #65는 3인 표결 없이 확정 종료한다.**
`assumptions.md #21~#24`에 근거가 등재됐고 코드·라벨 반영은 PR #70에서 병합 완료다. 남은 건 형식적 👍뿐이므로 DECISIONS.md에 확정 기록 후 종료한다.

---

## File Structure

| 파일 | 책임 | 상태 |
|---|---|---|
| `docs/assumptions.md` | AI-08 프로토콜·게이트 사전 등재 (#32), 서울신보 재수집 (#33), 소진공 금리 NULL 정책 (#34) | 수정 |
| `docs/DECISIONS.md` | D-A~D-E 자체 채택 결정 기록 | 수정 |
| `ai/batch/collect/seoulshinbo.py` | 서울신보 6종 HTML 재수집 → `ai/data/interim/funding_docs/*.txt` 교체 | 신설 |
| `ai/batch/load/finance.py` | 서울신보 청크 생성·`doc_chunk_ref` 연결 (기존 `_is_clean` 게이트 통과분 확대) | 수정 |
| `ai/eval/gold/extraction_confirmed.json` | 미검증 rate 5건 제거 + 노트 교정 | 수정 |
| `ai/eval/suites/model.py` | LightGBM 학습 · 자치구 블록 5-fold CV · 지표 · SHAP 방향 일치 · 게이트 판정 | 신설 |
| `ai/eval/common.py` | `load_model_features()` — 비매출 피처 + 점포당 매출 타깃 + 자치구 그룹 로더 | 수정 |
| `ai/eval/run.py` | `model` 을 PASSTHROUGH → DISPATCH 로 이동 | 수정 |
| `ai/eval/suites/report.py` | metrics.json 에 model 실측 병합 + `shap_summary.png` | 수정 |
| `ai/requirements-batch.txt` | lightgbm·shap 명시 (서빙 requirements 와 분리 확인) | 확인/수정 |
| `ai/tests/test_eval_model.py` | 게이트 판정·CV 분할·누수 방지 단위 테스트 | 신설 |
| `db/init/20_finance.sql` | 재생성 (서울신보 chunk + doc_chunk_ref) | 재생성 |
| `docs/심사_QA.md` | §5-4 RAG 구현/이월 이원화 문안 2종 | 수정 |
| `README.md` | 도슨트 가이드 정련 + 외부 테스트 프로토콜 링크 | 수정 |
| `docs/tasks/CM-04_도슨트_테스트_프로토콜.md` | 외부 1인 3분 테스트 실행 대본·기록지 | 신설 |

---

### Task 1: 소진공 금리표 수집 → 변동 7건 rate 확정 + 출처 검증 테스트 (#72 항목 1)

**이 태스크가 첫 번째인 이유:** 골드셋 수치의 출처가 코퍼스에 없는 상태에서 `make eval`을 돌리면 부록 1의 추출 정확도가 방어 불가다. 다른 모든 평가 작업의 선행 조건이다.

**Files:**
- Create: `ai/batch/collect/funding_web.py` (Task 2의 서울신보 수집과 **같은 모듈**로 통합 — 둘 다 "웹 1차 출처 → clean 텍스트 → `funding_docs/`" 동일 파이프라인)
- Modify: `ai/data/finance/reviewed.json` (변동 7건 rate)
- Modify: `docs/assumptions.md` (#34)
- Test: `ai/tests/test_finance_provenance.py` (신설)

**Interfaces:**
- Consumes: 없음 (최초 태스크)
- Produces: `ai/data/interim/funding_docs/소진공_정책자금_금리안내.txt` — Task 2의 서울신보 텍스트와 함께 `eval.common.is_clean_source()` 대상 코퍼스를 이룬다. `reviewed.json`의 소진공 7건이 non-null `rate` 보유.

- [ ] **Step 1: 금리표 수집 (실측 완료 — 아래 값이 정본)**

`https://www.semas.or.kr/web/SUP01/SUP0103/SUP010301.kmdc` 의 「금리안내」 섹션. 헤더는 `‘26년 3/4분기 정책자금 금리(’26.7.10부터 적용)`, 기준금리 열은 전 자금 공통 **3.85%**(단 소공인특화자금(유망)만 3.44%). D-A 표의 7건을 이 표에서 읽는다. **표의 「금리」 열 값을 그대로 쓴다** — 기준금리+가산을 직접 더해 만들지 않는다(반올림 불일치 방지).

- [ ] **Step 2: 출처 검증 테스트를 먼저 작성**

```python
# ai/tests/test_finance_provenance.py
"""골드셋 수치의 원문 출처 검증 (스펙 §0-1 — 출처 없는 수치 금지)."""
import json
import re
from pathlib import Path

AI_ROOT = Path(__file__).resolve().parents[1]
GOLD = AI_ROOT / "eval" / "gold" / "extraction_confirmed.json"
DOCS = AI_ROOT / "data" / "interim" / "funding_docs"


def _corpus() -> str:
    return "\n".join(p.read_text(encoding="utf-8") for p in DOCS.glob("*.txt"))


def test_gold_rates_are_traceable_to_source_text():
    """골드의 모든 rate 값은 수집 원문 코퍼스 안에서 발견돼야 한다."""
    corpus = _corpus()
    orphans = []
    for item in json.loads(GOLD.read_text(encoding="utf-8")):
        rate = item.get("rate")
        if rate is None:
            continue
        token = f"{float(rate):g}"
        if token not in corpus:
            orphans.append((item.get("name"), rate))
    assert orphans == [], f"원문 미확인 rate: {orphans}"
```

- [ ] **Step 3: 테스트를 돌려 실패를 확인**

Run: `cd ai && .venv/bin/python -m pytest tests/test_finance_provenance.py -v`
Expected: FAIL — `원문 미확인 rate: [('혁신성장촉진자금', 4.25), ('소공인 특화자금(일반)', 4.45), ...]` 형태로 5건 내외 검출.

- [ ] **Step 4: 금리표 수집 + `reviewed.json` 7건 rate 채움**

`funding_web.py`(Task 2에서 함께 작성)로 금리안내 페이지를 `소진공_정책자금_금리안내.txt` 로 저장한 뒤, `reviewed.json` 의 D-A 표 7건에 `rate` 를 넣고 `rate_note` 를 분기 기준일이 드러나게 바꾼다. 기준일 표기는 하드 룰이다(CLAUDE.md §4).

```
"rate": 4.45,
"rate_note": "정책자금 기준금리 3.85% + 0.6%p (’26년 3/4분기, 2026-07-10 적용)"
```

기존 verbatim 산식("정책자금 기준금리 + 0.6%p")은 이 문장에 가산 폭으로 보존되므로 정보 손실이 없다. **표의 「금리」 열 값을 그대로 옮긴다** — 3.85+0.6 을 계산해 넣지 않는다.

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd ai && .venv/bin/python -m pytest tests/test_finance_provenance.py -v`
Expected: PASS — 금리표 텍스트가 코퍼스에 들어오면서 3.85·4.25·4.45·5.45 가 모두 원문에서 발견된다.

- [ ] **Step 6: assumptions.md #34 등재**

기존 표 형식(`| 번호 | 날짜 | 파트 | 내용 | 근거 | 영향 |`)에 맞춰 1행 추가:

```
| 34 | 2026-07-25 | AI | **소진공 정책자금 기준금리 = 3.85%** (‘26년 3/4분기, 2026-07-10 적용). 융자공고 PDF는 "기준금리(분기별 변동) + 사업별 가산금리"로만 규정하고 수치를 담지 않으나, 공고가 지목한 소진공 홈페이지 「금리안내」 표(`SUP010301.kmdc`)에 분기 전문이 공시돼 있어 이를 1차 출처로 수집·저장한다(`batch/collect/funding_web.py`). 변동 7건(F-012·014·015·017·018·019·021)의 `rate` 를 표의 「금리」 열 값 그대로 채움 — 기준금리+가산 직접 계산 금지. 2차 출처(블로그 등)는 §0-1 미충족으로 미채택. **분기 변동값이므로 `rate_note` 에 적용 분기·시행일 병기 필수** | 스펙 §0-1·§4 기준일 표기, semas.or.kr 「금리안내」 | 부록 1 추출 정확도 `rate` 행 · `20_finance.sql` 소진공 전건 · BE 월 상환액 산정 |
```

- [ ] **Step 7: 커밋**

```bash
git add ai/batch/collect/funding_web.py ai/data/finance/reviewed.json \
        ai/data/interim/funding_docs/소진공_정책자금_금리안내.txt \
        ai/tests/test_finance_provenance.py docs/assumptions.md
git commit -m "[AI] data: 소진공 금리안내 1차 출처 수집 + 변동 7건 rate 확정 (#72, spec §0-1)"
```

---

### Task 2: 서울신보 원문 HTML 재수집 → 청킹 → source_quote 연결 (#72 항목 2)

**Files:**
- Create: `ai/batch/collect/seoulshinbo.py`
- Modify: `ai/data/interim/funding_docs/서울신보_보증상품_*.txt` (6종 교체)
- Modify: `ai/batch/load/finance.py`
- Regenerate: `db/init/20_finance.sql`
- Test: `ai/tests/test_collect_seoulshinbo.py` (신설)

**Interfaces:**
- Consumes: Task 1 의 골드셋 상태 (독립적이지만 같은 `make eval` 을 공유)
- Produces: `ai/data/interim/funding_docs/서울신보_보증상품_<슬러그>.txt` 6종이 `eval.common.is_clean_source()` 를 통과하는 정상 한국어 텍스트. `20_finance.sql` 의 F-004·F-006~F-011 이 non-NULL `doc_chunk_ref` 보유.

- [ ] **Step 1: 재수집 실패를 검출하는 테스트를 먼저 작성**

```python
# ai/tests/test_collect_seoulshinbo.py
"""서울신보 원문 텍스트 건전성 — 폰트 CID 깨짐 재발 방지 (#72)."""
from pathlib import Path

DOCS = Path(__file__).resolve().parents[1] / "data" / "interim" / "funding_docs"
KEYWORDS = ("보증", "한도", "기업", "지원")


def test_seoulshinbo_texts_are_readable_korean():
    """모지바케(폰트 CID 깨짐) 텍스트는 도메인 키워드가 거의 등장하지 않는다."""
    files = sorted(DOCS.glob("서울신보_보증상품_*.txt"))
    assert len(files) >= 6, f"서울신보 원문 부족: {len(files)}"
    for path in files:
        text = path.read_text(encoding="utf-8")
        hits = sum(text.count(k) for k in KEYWORDS)
        assert hits >= 5, f"{path.name}: 도메인 키워드 {hits}건 — 추출 깨짐 의심"
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인**

Run: `cd ai && .venv/bin/python -m pytest tests/test_collect_seoulshinbo.py -v`
Expected: FAIL — 현재 모지바케 텍스트라 키워드 히트가 0~2건.

- [ ] **Step 3: 재수집 스크립트 작성**

`ai/batch/collect/` 의 기존 모듈 스타일(모듈 docstring + `run()` 엔트리)을 따른다. 본문 영역만 남기고 내비게이션을 걷어내는 것이 핵심이다 — 페이지 전체 텍스트를 그대로 저장하면 청크에 메뉴 트리가 섞인다.

```python
"""서울신용보증재단 보증상품 원문 재수집 (#72).

raw PDF 는 웹 인쇄본이라 폰트 CID 매핑이 깨져 텍스트 레이어가 사용 불가다.
1차 출처인 웹 페이지에서 본문을 직접 받아 저장한다 — 인용은 검색이지 생성이 아니다(§5-4).
"""
from __future__ import annotations

import html
import re
import subprocess
from pathlib import Path

BASE = "https://www.seoulshinbo.co.kr/wbase/contents.do?mng_cd={code}"
OUT_DIR = Path(__file__).resolve().parents[2] / "data" / "interim" / "funding_docs"

PRODUCTS = {
    "ESG실천기업": "BUSI5389",
    "모바일앱자동심사": "BUSI2346",
    "미래성장산업": "BUSI4617",
    "일자리창출사회적기업": "BUSI4763",
    "장애인기업": "BUSI5388",
    "창업기업": "BUSI5337",
}

# 본문 시작 앵커 — 이 문자열 이후만 채택해 좌측 메뉴 트리를 배제한다.
BODY_ANCHOR = "보증상품"
NAV_NOISE = re.compile(r"^(홈페이지|재단스토리|주요업무|알림광장|정보공개|경영정보|소통참여)$")


def fetch(code: str) -> str:
    """정적 HTML 취득. WebFetch 는 이 도메인 인증서 체인 검증에 실패하므로 curl 을 쓴다."""
    result = subprocess.run(
        ["curl", "-sS", "--fail", BASE.format(code=code)],
        capture_output=True, text=True, check=True, timeout=30,
    )
    return result.stdout


def to_text(raw: str) -> str:
    body = re.sub(r"(?is)<(script|style)\b.*?</\1>", " ", raw)
    body = html.unescape(re.sub(r"<[^>]+>", "\n", body))
    lines = [ln.strip() for ln in body.splitlines()]
    lines = [ln for ln in lines if ln and not NAV_NOISE.match(ln)]
    text = "\n".join(lines)
    idx = text.rfind(BODY_ANCHOR)
    return text[idx:] if idx > 0 else text


def run() -> dict[str, int]:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    written: dict[str, int] = {}
    for slug, code in PRODUCTS.items():
        text = to_text(fetch(code))
        path = OUT_DIR / f"서울신보_보증상품_{slug}.txt"
        path.write_text(text, encoding="utf-8")
        written[slug] = len(text)
    return written


if __name__ == "__main__":
    print(run())
```

- [ ] **Step 4: 재수집 실행 후 테스트 통과 확인**

```bash
cd ai && .venv/bin/python -m batch.collect.seoulshinbo
.venv/bin/python -m pytest tests/test_collect_seoulshinbo.py -v
```
Expected: PASS. 실패하면 `BODY_ANCHOR`/`NAV_NOISE` 를 조정한다 — **텍스트를 손으로 고치지 말 것**(원문 verbatim 원칙).

- [ ] **Step 5: 재생성 후 청크 연결 확인**

```bash
cd ai && .venv/bin/python -m batch.load
cd .. && .venv/bin/python - <<'PY'
import re, pathlib
s = pathlib.Path('db/init/20_finance.sql').read_text(encoding='utf-8')
print('서울신보 chunk 건수:', s.count('서울신보_보증상품_'))
PY
```
Expected: 0 이 아닐 것. F-004·F-006~F-011 의 `doc_chunk_ref` 가 `서울신보_보증상품_<슬러그>#0` 형태로 채워진다. F-005(안심통장)는 원본 부재로 NULL 유지가 정상이다.

- [ ] **Step 6: assumptions.md #33 등재**

```
| 33 | 2026-07-25 | AI | **서울신보 6종 원문은 웹 HTML 재수집본을 정본으로 쓴다.** raw PDF(웹 인쇄본)는 폰트 CID 매핑이 깨져 텍스트 레이어가 모지바케(`폀옠6먗퓏핯…`) — OCR 대신 1차 출처인 웹 본문을 `curl` 로 받아 내비게이션 제거 후 저장(`batch/collect/seoulshinbo.py`, 재현 가능). F-005(서울형 안심통장)는 대응 페이지 부재로 `doc_chunk_ref` NULL 유지 | §5-4 "인용은 검색이지 생성이 아니다", `ai/data/raw/서울신보_*.pdf` 추출 실패 로그 | grounding coverage 6/29 → 상승 · 부록 1 근거 충실도 행 |
```

- [ ] **Step 7: 커밋**

```bash
git add ai/batch/collect/seoulshinbo.py ai/tests/test_collect_seoulshinbo.py \
        ai/data/interim/funding_docs/ ai/batch/load/finance.py db/init/20_finance.sql docs/assumptions.md
git commit -m "[AI] data: 서울신보 6종 원문 HTML 재수집 + source_quote 청킹 연결 (#72)"
```

---

### Task 3: AI-08 프로토콜·게이트 사전 등재 (**모델 코드보다 먼저 커밋**)

**이 태스크를 Task 4와 합치지 말 것.** §12-3의 심사 가치는 임계치가 아니라 커밋 순서에 있다(D-D).

**Files:**
- Modify: `docs/assumptions.md` (#32 추가)
- Modify: `docs/DECISIONS.md`

**Interfaces:**
- Consumes: 없음
- Produces: Task 4의 `model.py` 가 구현해야 할 **정확한 피처 목록·타깃 정의·CV 분할·지표·게이트 임계치**. Task 4 구현자는 이 등재 내용을 스펙으로 삼는다.

- [ ] **Step 1: assumptions.md #32 등재**

```
| 32 | 2026-07-25 | AI | **AI-08 검증 모델 프로토콜 사전 고정 (결과 확인 전 등재 — 리스크 #19).** ①타깃 = `log(월 점포당 추정매출)` = `log(sales.monthly_sales / store_density.store_cnt)`, 0·결측 행 제외 ②피처 6종 = 길단위 유동(`floating_pop.daily_floating`)·상주인구(`resident_pop.resident_pop`)·직장인구(`worker_pop.worker_pop`)·교통유입(`transit.daily_riders × exp(−distance_m/500)`)·경쟁밀도(`store_density.store_per_10k_m2`)·상권변화(`change_index.oper_avg_months`). **w2·w5 및 `location_score.est_sales` 는 매출 파생이라 배제(누수)**, 임대료는 `rent.fallback_flag=true` 행 제외 후 선택 피처 ③분할 = `commercial_area.sigungu_code` 기준 `GroupKFold(n_splits=5)` — 랜덤 CV 금지(공간 자기상관) ④지표 = fold **중앙값** R²·Spearman ρ·WAPE·MAE(만원)·베이스라인(자치구 평균 예측) 대비 개선, **MAPE 미사용** ⑤SHAP = 축별 평균 SHAP 부호 vs 설계 부호(유동+·상주+·직장+·교통+·경쟁밀도−·상권변화+)의 일치 수 ⑥LightGBM 고정 하이퍼파라미터 `n_estimators=400, learning_rate=0.05, num_leaves=31, min_child_samples=20, random_state=42` — 튜닝 금지(사후 조정 방지) ⑦**게이트(§12-3) = A: R²≥0.30 ∧ ρ≥0.60 ∧ 방향일치≥4/5 / B: R²≥0.15 ∧ (ρ≥0.45 ∨ 방향일치≥4/5) / C: 그 외** | 스펙 §12-2·§12-3, 리스크 #19 | 부록 2 수록 여부 · `make eval-model` 산출 · AI-09(#25) 판정 입력 |
```

축이 5개가 아니라 6개인 점을 주의한다 — §12-2가 말하는 "5축"은 w1~w5 설계 축이고, 여기 피처는 w1의 3성분을 펼친 것이다. **방향 일치 판정은 설계 축 5개 기준**으로 집계한다: w1(유동·상주·직장·교통 4피처의 다수결) / w3(경쟁밀도) / w4(상권변화) / 임대료(선택, 음의 방향) — 실제로 판정 가능한 축이 4개면 `4/5` 대신 분모를 명시해 `n/4` 로 보고하고 게이트는 비율로 환산한다.

- [ ] **Step 2: DECISIONS.md 에 D-A~D-E 등재**

기존 문서의 항목 형식을 그대로 따른다. 각 결정마다 **결정·근거·되돌리는 조건** 3줄을 쓴다. 되돌리는 조건 예: "D-A 는 소진공 공시 페이지의 정적 수집 경로가 확보되면 즉시 재검토한다."

- [ ] **Step 3: 커밋 (반드시 Task 4 이전)**

```bash
git add docs/assumptions.md docs/DECISIONS.md
git commit -m "[AI] docs: AI-08 검증 모델 프로토콜·게이트 사전 등재 (#21, spec §12-2·§12-3)"
```

- [ ] **Step 4: 커밋 순서 검증**

Run: `git log --oneline -3`
Expected: 이 커밋이 model.py 커밋보다 **앞선다**. 순서가 뒤집혔으면 rebase 하지 말고 사실대로 둔 뒤 PR 본문에 기록한다 — 은폐가 더 큰 감점이다.

---

### Task 4: `eval/suites/model.py` — LightGBM + 자치구 블록 CV + SHAP

**Files:**
- Create: `ai/eval/suites/model.py`
- Modify: `ai/eval/common.py`
- Modify: `ai/eval/run.py`
- Test: `ai/tests/test_eval_model.py`

**Interfaces:**
- Consumes: Task 3 이 등재한 프로토콜 (#32). `eval.common._iter_sql_rows(sql_text, table)` — 기존 헬퍼, 시그니처 불변.
- Produces:
  - `eval.common.load_model_features() -> tuple[list[dict], list[str]]` — 행 목록과 피처명 목록. 각 행은 `{"area_code", "sigungu_code", "target", <피처명>...}`.
  - `eval.suites.model.gate(r2: float, rho: float, direction_hits: int, direction_total: int) -> str` — `"A"|"B"|"C"` 반환.
  - `eval.suites.model.run(out_dir: Path) -> dict` — `report.py` 가 `metrics["model"]` 로 병합.

- [ ] **Step 1: 게이트 판정 테스트를 먼저 작성**

게이트는 순수 함수라 데이터 없이 전건 검증이 가능하다. 여기가 이 태스크에서 가장 중요한 테스트다.

```python
# ai/tests/test_eval_model.py
"""AI-08 검증 모델 — 게이트 판정·CV 분할·누수 방지 (스펙 §12-2·§12-3)."""
import pytest

from eval.suites import model


@pytest.mark.parametrize(
    ("r2", "rho", "hits", "total", "expected"),
    [
        (0.35, 0.65, 4, 5, "A"),      # 전 조건 충족
        (0.30, 0.60, 4, 5, "A"),      # 경계값 포함 (≥)
        (0.29, 0.65, 5, 5, "B"),      # R² 미달 → A 탈락, B 충족
        (0.35, 0.55, 3, 5, "B"),      # ρ 미달이나 R²≥0.15
        (0.20, 0.30, 4, 5, "B"),      # ρ 미달이지만 방향 일치 충족
        (0.20, 0.30, 2, 5, "C"),      # 둘 다 미달
        (0.10, 0.90, 5, 5, "C"),      # R² 0.15 미만이면 무조건 C
    ],
)
def test_gate_thresholds(r2, rho, hits, total, expected):
    assert model.gate(r2, rho, hits, total) == expected


def test_gate_normalizes_direction_ratio():
    """판정 가능 축이 4개면 3/4 는 4/5(0.8) 기준을 충족한다."""
    assert model.gate(0.35, 0.65, 4, 4) == "A"
    assert model.gate(0.35, 0.65, 2, 4) == "B"


def test_features_exclude_sales_derived_columns():
    """w2·w5·est_sales 는 타깃 파생이라 피처에 있어선 안 된다 (누수 차단)."""
    _, feature_names = __import__("eval.common", fromlist=["common"]).load_model_features()
    banned = {"w2", "w5", "est_sales", "monthly_sales"}
    assert banned.isdisjoint(feature_names), f"누수 피처 포함: {banned & set(feature_names)}"


def test_group_split_keeps_sigungu_whole():
    """같은 자치구가 train/test 에 동시에 등장하면 공간 CV 가 무의미하다."""
    rows, _ = __import__("eval.common", fromlist=["common"]).load_model_features()
    folds = model.group_folds(rows, n_splits=5)
    assert len(folds) == 5
    for train_idx, test_idx in folds:
        train_gu = {rows[i]["sigungu_code"] for i in train_idx}
        test_gu = {rows[i]["sigungu_code"] for i in test_idx}
        assert train_gu.isdisjoint(test_gu)
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인**

Run: `cd ai && .venv/bin/python -m pytest tests/test_eval_model.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'eval.suites.model'`

- [ ] **Step 3: `common.load_model_features()` 구현**

`load_serving_scores()` 바로 아래에 추가한다. 기존 `_iter_sql_rows` 를 재사용하고, 테이블별 최신 분기 1건만 취한다(분기 중복 시 `quarter` 최대값).

```python
DESIGN_SIGNS = {                      # 설계 부호 — SHAP 방향 일치 판정 기준 (assumptions #32)
    "daily_floating": +1, "resident_pop": +1, "worker_pop": +1, "transit_access": +1,
    "store_per_10k_m2": -1, "oper_avg_months": +1,
}
DESIGN_AXIS = {                       # 설계 축 5개로 집계 (w1 은 4피처 다수결)
    "daily_floating": "w1", "resident_pop": "w1", "worker_pop": "w1", "transit_access": "w1",
    "store_per_10k_m2": "w3", "oper_avg_months": "w4",
}


def load_model_features(sql_path: Path = DATA_CORE_SQL):
    """§12-2 프로토콜 피처·타깃 로더. 매출 파생 컬럼은 절대 포함하지 않는다."""
    import math

    text = sql_path.read_text(encoding="utf-8")

    def latest(table, key_idx, quarter_idx, value_map):
        out = {}
        for r in _iter_sql_rows(text, table):
            key, q = r[key_idx], r[quarter_idx]
            if key not in out or q >= out[key][0]:
                out[key] = (q, value_map(r))
        return {k: v for k, (_, v) in out.items()}

    sigungu = {r[0]: r[5] for r in _iter_sql_rows(text, "commercial_area")}
    floating = latest("floating_pop", 0, 1, lambda r: float(r[2]))
    resident = latest("resident_pop", 0, 1, lambda r: float(r[2]))
    worker = latest("worker_pop", 0, 1, lambda r: float(r[2]))
    change = latest("change_index", 0, 1, lambda r: float(r[4]))
    transit = {r[0]: float(r[4]) * math.exp(-float(r[3]) / 500.0)
               for r in _iter_sql_rows(text, "transit")}

    density, sales = {}, {}
    for r in _iter_sql_rows(text, "store_density"):
        density[(r[0], r[2])] = (r[1], float(r[3]), float(r[7]))     # quarter, store_cnt, per_10k
    for r in _iter_sql_rows(text, "sales"):
        sales[(r[0], r[3])] = (r[1], float(r[4]))                    # quarter, monthly_sales

    feature_names = ["daily_floating", "resident_pop", "worker_pop",
                     "transit_access", "store_per_10k_m2", "oper_avg_months"]
    rows = []
    for (area, industry), (_, store_cnt, per_10k) in density.items():
        sale = sales.get((area, industry))
        if not sale or store_cnt <= 0 or sale[1] <= 0 or area not in sigungu:
            continue
        values = {
            "daily_floating": floating.get(area), "resident_pop": resident.get(area),
            "worker_pop": worker.get(area), "transit_access": transit.get(area),
            "store_per_10k_m2": per_10k, "oper_avg_months": change.get(area),
        }
        if any(v is None for v in values.values()):
            continue
        rows.append({"area_code": area, "industry": industry,
                     "sigungu_code": sigungu[area],
                     "target": math.log(sale[1] / store_cnt), **values})
    return rows, feature_names
```

- [ ] **Step 4: `model.py` 구현**

```python
"""검증 모델 스위트 (스펙 §12-2·§12-3) — LightGBM 설계 교차 검증 + SHAP 방향 일치.

서빙 경로가 아니다. 오프라인 배치 전용이며 추천 엔진을 교체하지 않는다(§0-1 원칙 2).
프로토콜·게이트는 결과 확인 전에 assumptions.md #32 에 등재됐다 — 여기서 임계치를 바꾸지 말 것.
"""
from __future__ import annotations

import statistics
from pathlib import Path

from eval import common

PARAMS = {"n_estimators": 400, "learning_rate": 0.05, "num_leaves": 31,
          "min_child_samples": 20, "random_state": 42, "verbose": -1}
A_THRESHOLD = {"r2": 0.30, "rho": 0.60, "direction": 0.8}
B_THRESHOLD = {"r2": 0.15, "rho": 0.45, "direction": 0.8}


def gate(r2: float, rho: float, direction_hits: int, direction_total: int) -> str:
    """§12-3 수록 게이트. 사전 등록된 임계치 — 결과에 맞춰 조정 금지."""
    ratio = direction_hits / direction_total if direction_total else 0.0
    if r2 >= A_THRESHOLD["r2"] and rho >= A_THRESHOLD["rho"] and ratio >= A_THRESHOLD["direction"]:
        return "A"
    if r2 >= B_THRESHOLD["r2"] and (rho >= B_THRESHOLD["rho"] or ratio >= B_THRESHOLD["direction"]):
        return "B"
    return "C"


def group_folds(rows: list[dict], n_splits: int = 5):
    """자치구 블록 그룹아웃 — 공간 자기상관 탓에 랜덤 CV 는 성능을 과대평가한다."""
    from sklearn.model_selection import GroupKFold

    groups = [r["sigungu_code"] for r in rows]
    splitter = GroupKFold(n_splits=n_splits)
    dummy = [[0.0]] * len(rows)
    return [(list(tr), list(te)) for tr, te in splitter.split(dummy, groups=groups)]
```

`run()` 은 fold 별로 LightGBM 을 학습해 R²·Spearman ρ·WAPE·MAE 를 모으고 **중앙값**을 취한다. WAPE·MAE 는 로그 타깃을 `exp` 로 되돌린 만원 단위로 계산한다. 베이스라인은 train 자치구 평균 타깃을 test 전체에 상수 예측한 값이다. SHAP 은 전체 데이터 재학습 모델 1개에 `shap.TreeExplainer` 를 적용해 피처별 `mean(shap * feature_centered)` 의 부호를 `common.DESIGN_SIGNS` 와 비교하고, `common.DESIGN_AXIS` 로 설계 축 단위 다수결 집계한다. summary plot 은 `out_dir / "shap_summary.png"` 로 저장하되 `matplotlib.use("Agg")` 지연 로드는 `report.py` 패턴을 따른다.

반환 dict 는 다음 키를 반드시 포함한다 — AI-09(#25)와 부록 2가 이 키를 읽는다:
`{"n_rows", "n_groups", "features", "target", "cv", "r2_median", "rho_median", "wape_median", "mae_median", "baseline_r2_median", "direction": {"hits", "total", "by_axis"}, "gate", "gate_note"}`

`gate_note` 는 판정별 고정 문구를 담는다: A = `"부록 2 전체 수록 — 설계-데이터 정합을 설명력·방향 양면에서 확인"`, B = `"부록 2 축소(방향 일치 중심) — 방향 정합 확인, 설명력은 참고 수준"`, C = `"부록 2 미수록 — 민감도 분석 단독 유지(v6.1 상태 복귀)"`.

- [ ] **Step 5: `run.py` 에서 model 을 DISPATCH 로 이동**

```python
from eval.suites import extraction, grounding, model, report, sensitivity

DISPATCH = {
    "extraction": extraction.run,
    "grounding": grounding.run,
    "sensitivity": sensitivity.run,
    "model": model.run,
    "report": report.run,
}
PASSTHROUGH = {
    "matching": "BE 소관 — EligibilityFilter 는 BE 단위 + 통합 테스트가 검증",
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `cd ai && .venv/bin/python -m pytest tests/test_eval_model.py -v`
Expected: PASS (7개 게이트 케이스 + 정규화 + 누수 + 그룹 분할)

- [ ] **Step 7: 실제 스위트 실행**

Run: `cd ai && make eval-model`
Expected: JSON 출력에 `gate` 가 `A|B|C` 중 하나. **판정이 C여도 그대로 둔다** — C는 정상 동작이지 실패가 아니다(§12-4 후퇴 규칙).

- [ ] **Step 8: `report.py` 병합 + requirements 확인**

`report.py` 의 `metrics["model"]` 을 스텁에서 `model.run(out_dir)` 실측으로 교체하고, `lightgbm`·`shap` 이 배치 requirements 에만 있는지 확인한다.

Run: `grep -rn "lightgbm\|shap" ai/requirements*.txt backend/ frontend/ 2>/dev/null`
Expected: `ai/` 배치 requirements 에만 등장. 서빙 쪽에 나오면 §0-1 원칙 2 위반이므로 즉시 제거.

- [ ] **Step 9: 커밋**

```bash
git add ai/eval/suites/model.py ai/eval/common.py ai/eval/run.py \
        ai/eval/suites/report.py ai/tests/test_eval_model.py ai/requirements-batch.txt
git commit -m "[AI] feat: 검증 모델 스위트 — 자치구 블록 CV + SHAP 방향 일치 + 게이트 판정 (#21, spec §12-2)"
```

---

### Task 5: #65 자체 확정 종료

**Files:**
- Modify: `docs/DECISIONS.md`

**Interfaces:**
- Consumes: Task 3 이 만든 DECISIONS.md 의 D-E 항목
- Produces: 없음 (이슈 종료용)

- [ ] **Step 1: 확정 사실 확인**

Run: `grep -n "만원\|가맹" docs/assumptions.md | head`
Expected: #21~#24 에 단위=만원·보정계수 미적용·라벨 문구가 이미 등재돼 있다. 없으면 등재부터 한다.

- [ ] **Step 2: 라벨이 코드에 실제로 반영됐는지 확인**

Run: `grep -rn "가맹점 기준" ai/batch/load/serving.py`
Expected: `"공정위 가맹정보 2025 (가맹점 기준·상향, 만원)"` 가 `data_source_meta` 에 존재.

- [ ] **Step 3: 커밋 (D-E 는 Task 3 에서 이미 등재됨 — 확인만 하고 넘어간다)**

추가 코드 변경이 없다면 커밋 없이 Task 8 의 이슈 코멘트로 넘어간다.

---

### Task 6: 평가 하네스 전건 재실행 + 회귀 확인

**Files:**
- Modify: `ai/eval/out/metrics.json`, `ai/eval/out/*.png` (산출물)

**Interfaces:**
- Consumes: Task 1(골드 정정)·Task 2(청크 연결)·Task 4(model 스위트)의 결과 전부
- Produces: 부록 1·2에 그대로 실릴 `metrics.json`

- [ ] **Step 1: 전건 실행**

Run: `cd ai && make eval`
Expected: 5개 스위트 중 `matching` 만 passthrough 안내, 나머지 4개 실행. 오류 0.

- [ ] **Step 2: 개선 확인**

```bash
cd ai && .venv/bin/python -c "
import json; m=json.load(open('eval/out/metrics.json'))
g=m['grounding']; print('grounding coverage:', g['coverage'], f\"({g['linked']}/{g['total']})\")
print('legitimate_null_docs:', g['legitimate_null_docs'])
print('model gate:', m['model'].get('gate'))
"
```
Expected: coverage 가 0.207 에서 **상승**하고, `legitimate_null_docs` 에서 서울신보 6종이 사라진다. 그대로면 Task 2 의 청킹이 연결되지 않은 것이므로 되돌아간다.

- [ ] **Step 3: 전체 테스트·린트**

```bash
cd ai && ruff check . && .venv/bin/python -m pytest -q
```
Expected: ruff 통과, 기존 55테스트 + 신규 테스트 전건 통과.

- [ ] **Step 4: 커밋**

```bash
git add ai/eval/out/
git commit -m "[AI] chore: make eval 전건 재실행 — 근거 충실도·검증 모델 지표 갱신 (#21 #72)"
```

---

### Task 7: CM-03 이원화 문안 + CM-04 도슨트 프로토콜 (별도 토픽 브랜치)

**Files:**
- Modify: `docs/심사_QA.md`
- Modify: `README.md`
- Create: `docs/tasks/CM-04_도슨트_테스트_프로토콜.md`

**Interfaces:**
- Consumes: 없음 (문서 전용, AI 태스크와 독립)
- Produces: CP4(D10) 판정 시 그대로 채택할 문안 2종

- [ ] **Step 1: 심사_QA.md 에 §5-4 이원화 문안 2종 추가**

「설계 원칙 박스 5」 섹션 **뒤에** 새 섹션으로 넣는다. 판정 전에 두 문안을 모두 확정해 두는 것이 요구사항이므로, 둘 다 완성된 문장으로 쓴다 — 골격만 잡고 나중에 채우는 건 요구사항 미충족이다.

```markdown
## RAG 근거 인용 — 구현/이월 이원화 문안 (스펙 §5-4 · CP4 D10 판정 전 사전 작성)

CP4에서 BE-06 ①RAG 근거 인용의 구현 여부가 갈린다. 어느 쪽으로 판정되든 **아래 문안을
그대로** 기술설명서·README에 반영한다. 판정 후 문안을 새로 쓰지 않는다.

### 문안 A — 구현 판정 시
> 근거 패널의 인용문은 정책자금 공고 원문 청크를 **벡터DB에서 검색해 그대로** 노출한다.
> LLM은 인용문을 재작성하지 않으며, 표시되는 문장은 `finance_doc_chunk.text`와 바이트 단위로
> 일치한다. 인용 가능한 원문이 없는 상품은 인용 없이 출처 링크만 노출한다 — 없는 근거를
> 만들어내지 않는 것이 이 설계의 요점이다.

### 문안 B — 이월 판정 시
> 근거 인용은 **설계 확정·본선 반영** 항목이다. 원문 청크 스키마(`finance_doc_chunk`)와
> 상품↔청크 연결(`doc_chunk_ref`)은 이미 적재돼 있고, 인용 경로만 본선에서 연결한다.
> 현재 버전은 출처 기관·문서명·기준일을 표기하며, 이 범위에서 표시되는 모든 값은
> 공개 자료에서 결정적으로 계산된 수치다.

**공통 고지**: 본 정보는 공개 자료 기반 정보 제공이며 대출 권유·중개·자문이 아닙니다.
```

- [ ] **Step 2: 도슨트 테스트 프로토콜 문서 작성**

`docs/tasks/CM-04_도슨트_테스트_프로토콜.md` 신설. 외부 1인(비개발자)이 그대로 들고 실행할 수 있어야 한다. 담을 것: ①사전 준비(`docker compose up --build` 완료 상태·브라우저) ②안내자가 **말하지 말 것** 목록(힌트 금지 — 3분 안에 스스로 도달하는지가 측정 대상) ③3분 타이머 기준 통과 조건 = "T1 인사이트의 진입/지속 병기를 스스로 읽어냈는가" ④기록지(각 단계 도달 시각·막힌 지점·발화) ⑤실패 시 조정 대상 우선순위(README 문구 → 화면 카피 → 동선).

- [ ] **Step 3: README 도슨트 가이드 정련**

현재 가이드(L14~37)는 이미 완성도가 높다. 손댈 곳은 두 군데다: ①기술설명서 장 번호 앵커는 CM-05(D13~14)에 문서가 생긴 뒤에야 넣을 수 있으므로 `<!-- CM-05: 기술설명서 장 번호 앵커 삽입 지점 -->` 주석만 심어 둔다 ②테스트 프로토콜 문서로 가는 링크 1줄 추가.

- [ ] **Step 4: 용어 컴플라이언스 확인**

Run: `grep -n "승인\|심사역\|조달 가능\|권장드립\|추천드립" docs/심사_QA.md README.md docs/tasks/CM-04_도슨트_테스트_프로토콜.md`
Expected: 0건. 걸리면 CLAUDE.md §3 대체어로 교체.

- [ ] **Step 5: 커밋**

```bash
git add docs/심사_QA.md README.md docs/tasks/CM-04_도슨트_테스트_프로토콜.md
git commit -m "[CM] docs: RAG 이원화 문안 2종 사전 작성 + 도슨트 테스트 프로토콜 (#22 #26)"
```

---

### Task 8: 병합·PR·이슈 정리

**Interfaces:**
- Consumes: Task 1~7 의 모든 커밋
- Produces: `develop` 대상 PR 2건

- [ ] **Step 1: 토픽 브랜치를 `ai` 로 로컬 병합**

브랜치 규칙상 develop 대상 PR의 head 는 `frontend`·`backend`·`ai` 중 하나여야 한다. 토픽에서 develop 으로 직접 PR 을 올리지 않는다.

```bash
git checkout ai && git merge --no-ff <토픽브랜치>
```

- [ ] **Step 2: PR A (AI) 생성**

본문에 반드시 담을 것: ①D-A~D-E 결정 요약과 근거 ②`make eval` 전후 grounding coverage 수치 ③게이트 판정 결과와 "프로토콜 등재 커밋이 모델 커밋보다 앞선다"는 사실 + 커밋 해시 ④`Closes #72` `Closes #21` `Closes #65`.

```bash
gh pr create --base develop --head ai --title "[AI-08/AI-06 후속] 검증 모델 게이트 사전 등록 + 정책자금 출처 검증"
```

- [ ] **Step 3: PR B (CM) 생성** — `Closes #22` 는 쓰지 않는다

CM-03 은 **CP4(D10) 판정 자체**가 DoD 이고 이번 작업은 "판정 전 사전 작성"분이다. CM-04 도 외부 1인 실측이 남는다. 따라서 두 이슈 모두 `Closes` 없이 진행 코멘트만 남긴다.

- [ ] **Step 4: 이슈 정리**

- #72 · #21 · #65 → PR A 병합 시 자동 종료. 보드 Done 이동 확인.
- #22 · #26 → In Progress 유지 + 진행 코멘트(무엇이 끝났고 무엇이 사람 게이트로 남았는지).
- **신규 이슈 1건 등록**: `[BE] finance_product.rate nullable 대응 — FundingProduct.rate 를 Double 로, NULL 상품 월 상환액 정책 확정`. 본문에 F7(14/26 NULL, `FundingProduct.java:18` 원시 double, `FundingCheck.java:73` 사용처)과 D-A 를 인용한다. 라벨 `BE`, P0.

---

## Self-Review

**1. 스펙 커버리지**
- §12-2 프로토콜 5항 → Task 3 등재 + Task 4 구현 ✓ (타깃·피처·CV·지표·SHAP 전부)
- §12-3 게이트 3판정 → Task 4 Step 1 의 7개 파라미터 케이스로 경계값 포함 검증 ✓
- §0-1 원칙 2 (서빙 무모델) → Task 4 Step 8 의 requirements 확인 ✓
- §5-4 (인용은 검색) → Task 2 재수집 + Task 7 문안 A ✓
- 리스크 #19 (사후 조정 금지) → Task 3/4 커밋 분리 + Step 4 순서 검증 ✓
- #72 DoD 5건 → 검수본 반영·SQL 재생성은 완료분, 기준금리는 Task 1(D-A로 확정), source_quote 는 Task 2 ✓

**2. 플레이스홀더 스캔**
Task 4 Step 4 의 `run()` 본문을 산문으로 기술한 것이 이 계획에서 가장 느슨한 지점이다. 다만 반환 키 목록·베이스라인 정의·SHAP 집계 방식·고정 문구를 모두 명시했으므로 구현자가 추측할 여지는 없다. Task 7 의 문안 2종은 완성 문장으로 작성했다 ✓

**3. 타입 일관성**
`gate(r2, rho, direction_hits, direction_total)` — Task 4 Step 1 테스트와 Step 4 구현 시그니처 일치 ✓
`group_folds(rows, n_splits)` → `list[tuple[list[int], list[int]]]` — 테스트의 `for train_idx, test_idx in folds` 와 정합 ✓
`load_model_features()` → `(rows, feature_names)` 튜플 — 테스트가 `_, feature_names` 로 언패킹, 구현도 2튜플 반환 ✓
`common.DESIGN_SIGNS`·`common.DESIGN_AXIS` — Task 4 Step 3 에서 정의, Step 4 산문에서 동일 이름으로 참조 ✓

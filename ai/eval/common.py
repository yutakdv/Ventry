"""AI-07 평가 하네스 공용 로더·정규화·SQL 파서 (스펙 §12-1). 읽기 전용."""
from __future__ import annotations

import csv
import io
import json
import math
import re
from pathlib import Path

AI_ROOT = Path(__file__).resolve().parents[1]           # ai/
REPO_ROOT = AI_ROOT.parent
FINANCE_DIR = AI_ROOT / "data" / "finance"
FUNDING_DOCS = AI_ROOT / "data" / "interim" / "funding_docs"
GOLD_DIR = Path(__file__).resolve().parent / "gold"
DATA_CORE_SQL = REPO_ROOT / "db" / "init" / "10_data_core.sql"
FINANCE_SQL = REPO_ROOT / "db" / "init" / "20_finance.sql"

# finance_product 검수 필드 (extract/funding_llm.py 와 동일 순서)
PRODUCT_KEYS = (
    "name", "org", "product_type", "max_age", "industries", "regions",
    "pre_startup_only", "amount_max", "rate", "rate_note", "term_months",
    "exclusive_group", "status", "notice_date", "source_url",
)

_TOKEN = re.compile(r"[\w가-힣]+")


def load_extraction_draft() -> list[dict]:
    return json.loads((FINANCE_DIR / "extracted.json").read_text(encoding="utf-8"))


def load_reviewed_products() -> list[dict]:
    """전건 검수본(적재 원천). `doc` 필드로 상품↔문서를 잇는다."""
    return json.loads((FINANCE_DIR / "reviewed.json").read_text(encoding="utf-8"))


def load_confirmed_gold() -> list[dict]:
    return json.loads((GOLD_DIR / "extraction_confirmed.json").read_text(encoding="utf-8"))


def load_source_text(doc: str) -> str:
    path = FUNDING_DOCS / f"{doc}.txt"
    return path.read_text(encoding="utf-8") if path.exists() else ""


def is_clean_source(doc: str) -> bool:
    """근거 청크 가능 문서 판정.

    **적재 코드를 직접 부른다** — 사본이 아니다. 이전에는 같은 규칙(키워드 9종·밀도 3.0)을
    여기에 다시 적어 두었는데, 한쪽만 바뀌어도 테스트는 계속 통과하면서 평가가 **실서빙과
    다른 규칙을 재고 있는** 상태가 된다. 제출 근거(부록 1·2)의 무결성이 조용히 깨지는 경로라
    사본을 없앤다 (AI 리뷰 P1). 같은 파일의 `strip_print_artifacts` 는 이미 import 하고 있었다.
    """
    from batch.load.finance import _is_clean

    return _is_clean(load_source_text(doc))


def load_source_text_clean(doc: str) -> str:
    """청크 대조용 정제 원문.

    청크는 `load/finance.py:strip_print_artifacts` 를 거친 텍스트를 자른 것이므로,
    raw txt 와 직접 비교하면 인쇄 장식 줄·NUL 이 빠진 만큼 부분문자열이 되지 않는다.
    같은 정제를 적용한 텍스트가 올바른 대조 기준이다 (assumptions #50, 리뷰 #4·#5).
    """
    from batch.load.finance import strip_print_artifacts

    return strip_print_artifacts(load_source_text(doc))


def load_finance_chunks(sql_path: Path = FINANCE_SQL) -> dict[str, str]:
    """적재 덤프의 원문 청크 {chunk_id: text}. text 가 개행을 품어 블록째 파싱한다."""
    text = sql_path.read_text(encoding="utf-8")
    block = re.search(
        r"INSERT INTO finance_doc_chunk\b[^;]*?VALUES\s*(.*?);\s*\n", text, re.DOTALL
    )
    if not block:
        return {}
    body = block.group(1).strip()
    # 레코드는 줄머리 `('<chunk_id>', '<product_id>', ` 로 시작한다. 이 경계를 먼저 잡아야
    # 바깥 괄호를 벗길 수 있고, 그래야 csv 가 첫 필드를 인용문으로 인식해 text 안의 개행을
    # 제대로 흡수한다.
    starts = [m.start() for m in re.finditer(r"(?m)^\('[^']*#\d+', 'F-\d+', ", body)]
    out: dict[str, str] = {}
    for i, start in enumerate(starts):
        end = starts[i + 1] if i + 1 < len(starts) else len(body)
        record = body[start:end].strip().rstrip(";").rstrip(",").strip()
        record = record[1:-1] if record.startswith("(") and record.endswith(")") else record
        fields: list[str] = []
        for part in csv.reader(io.StringIO(record), quotechar="'", skipinitialspace=True):
            fields.extend(part)
        if len(fields) >= 4:  # (chunk_id, product_id, doc_meta, text)
            out[fields[0]] = "\n".join(fields[3:])
    return out


def load_finance_products(sql_path: Path = FINANCE_SQL) -> list[tuple[str, str | None]]:
    """적재 덤프의 (product_id, doc_chunk_ref). 인용 없으면 None."""
    text = sql_path.read_text(encoding="utf-8")
    rows = []
    for r in _iter_sql_rows_lines(text, "finance_product"):
        ref = r[-1].rstrip(")").strip()
        rows.append((r[0].lstrip("("), None if ref in ("NULL", "") else ref))
    return rows


def norm_field(key: str, value):
    """초안↔확정 비교용 정규화 — 배열은 집합·null==빈배열·문자열 공백정리·수치 캐스팅."""
    if key in ("industries", "regions"):
        if value in (None, "", "[]", "NULL"):
            return frozenset()
        if isinstance(value, str):
            return frozenset(_TOKEN.findall(value))
        return frozenset(value)
    if key == "rate":
        return None if value in (None, "", "NULL") else round(float(value), 3)
    if key in ("max_age", "amount_max", "term_months"):
        return None if value in (None, "", "NULL") else int(value)
    if key == "pre_startup_only":
        if isinstance(value, str):
            return value.strip().lower() == "true"
        return bool(value)
    if isinstance(value, str):
        return value.strip() or None
    return value


def _iter_sql_rows(sql_text: str, table: str):
    """INSERT INTO <table> ... VALUES (...),(...); 의 각 행을 필드 리스트로 산출.

    location_score·rent 는 문자열 필드에 괄호·개행이 없는 검증된 포맷이라 안전하다.
    동일 테이블 INSERT 가 여러 개면 전부 순회한다.
    """
    pattern = re.compile(
        rf"INSERT INTO {re.escape(table)}\b[^;]*?VALUES\s*(.*?);", re.DOTALL
    )
    for block in pattern.finditer(sql_text):
        for raw in re.findall(r"\(([^()]*)\)", block.group(1)):
            yield next(csv.reader([raw], quotechar="'", skipinitialspace=True))


# ── AI-08 검증 모델 입력 (스펙 §12-2, assumptions #35) ────────────────────────
# 설계 부호: 점포당 매출에 대해 각 피처가 어느 방향으로 기여해야 하는가.
# SHAP 실측 부호와 이 표를 대조한 일치 수가 §12-3 게이트의 세 번째 조건이다.
DESIGN_SIGNS = {
    "daily_floating": +1, "resident_pop": +1, "worker_pop": +1, "transit_access": +1,
    "store_per_10k_m2": -1, "oper_avg_months": +1,
}
# 설계 축 단위 집계 — w1 은 4개 피처의 다수결, w3·w4 는 단일 피처.
# (w2·w5 는 매출 파생이라 애초에 피처가 아니므로 판정 축이 3개다.)
DESIGN_AXIS = {
    "daily_floating": "w1", "resident_pop": "w1", "worker_pop": "w1", "transit_access": "w1",
    "store_per_10k_m2": "w3", "oper_avg_months": "w4",
}
MODEL_FEATURES = (
    "daily_floating", "resident_pop", "worker_pop",
    "transit_access", "store_per_10k_m2", "oper_avg_months",
)
_TRANSIT_DECAY_M = 500.0  # 교통 접근성 거리 감쇠 상수 (스펙 §4-3 w1 3성분과 동일)


_ROW_LINE = re.compile(r"^\((.*)\)[,;]?\s*$")


def _iter_sql_rows_lines(sql_text: str, table: str):
    """행 단위 파서 — 문자열 필드에 괄호가 있는 테이블용(`commercial_area` 상권명 등).

    `_iter_sql_rows` 의 `\\(([^()]*)\\)` 는 '배화여자대학교(박노수미술관)' 에서 깨진다.
    적재 SQL 은 한 줄에 한 행이므로 줄 단위로 자른 뒤 csv 로 따옴표를 해석한다.
    """
    pattern = re.compile(rf"INSERT INTO {re.escape(table)}\b[^;]*?VALUES\s*(.*?);", re.DOTALL)
    for block in pattern.finditer(sql_text):
        for line in block.group(1).splitlines():
            matched = _ROW_LINE.match(line.strip())
            if matched:
                yield next(csv.reader([matched.group(1)], quotechar="'", skipinitialspace=True))


def _latest_by_area(rows, quarter_idx: int, value):
    """상권별 최신 분기 1건만 남긴다 (분기 누적 테이블 → 횡단면)."""
    out: dict[str, tuple[str, float]] = {}
    for r in rows:
        area, quarter = r[0], r[quarter_idx]
        if area not in out or quarter >= out[area][0]:
            out[area] = (quarter, value(r))
    return {area: v for area, (_, v) in out.items()}


def load_model_features(sql_path: Path = DATA_CORE_SQL) -> tuple[list[dict], list[str]]:
    """§12-2 프로토콜의 피처·타깃 로더. 매출 파생 컬럼은 절대 포함하지 않는다.

    타깃 = log(월 점포당 추정매출). 총매출은 점포수와 준항등이라 검증력이 없고,
    "한 점포가 버틸 수 있는가"라는 서비스 의미론과도 점포당 매출이 정합한다.
    """
    text = sql_path.read_text(encoding="utf-8")
    # commercial_area: area_code, name, area_type_code, area_type_name, sigungu_code, …
    sigungu = {r[0]: r[4] for r in _iter_sql_rows_lines(text, "commercial_area")}
    floating = _latest_by_area(_iter_sql_rows_lines(text, "floating_pop"), 1, lambda r: float(r[2]))
    resident = _latest_by_area(_iter_sql_rows_lines(text, "resident_pop"), 1, lambda r: float(r[2]))
    worker = _latest_by_area(_iter_sql_rows_lines(text, "worker_pop"), 1, lambda r: float(r[2]))
    change = _latest_by_area(_iter_sql_rows_lines(text, "change_index"), 1, lambda r: float(r[4]))
    # distance_m·daily_riders 는 역 미매칭 상권에서 NULL 이 될 수 있다 — float('NULL') 로
    # 터지지 않게 걸러낸다. 그 상권은 피처 결측이라 아래에서 자연 제외된다 (리뷰 #11).
    transit = {
        r[0]: float(r[4]) * math.exp(-float(r[3]) / _TRANSIT_DECAY_M)
        for r in _iter_sql_rows_lines(text, "transit")
        if "NULL" not in (r[3], r[4])
    }

    density, sales = {}, {}
    # store_density: area, quarter, industry, store_cnt, similar, permit, store_per_10k_m2
    for r in _iter_sql_rows_lines(text, "store_density"):
        if "NULL" in (r[3], r[6]):
            continue
        key, quarter = (r[0], r[2]), r[1]
        if key not in density or quarter >= density[key][0]:
            density[key] = (quarter, float(r[3]), float(r[6]))
    # sales: area, quarter, industry, industry_code, monthly_sales, monthly_sales_cnt
    for r in _iter_sql_rows_lines(text, "sales"):
        if r[4] == "NULL":
            continue
        key, quarter = (r[0], r[2]), r[1]
        if key not in sales or quarter >= sales[key][0]:
            sales[key] = (quarter, float(r[4]))

    rows: list[dict] = []
    for (area, industry), (_, store_cnt, per_10k) in density.items():
        sale = sales.get((area, industry))
        if not sale or store_cnt <= 0 or sale[1] <= 0 or area not in sigungu:
            continue
        values = {
            "daily_floating": floating.get(area),
            "resident_pop": resident.get(area),
            "worker_pop": worker.get(area),
            "transit_access": transit.get(area),
            "store_per_10k_m2": per_10k,
            "oper_avg_months": change.get(area),
        }
        if any(v is None for v in values.values()):
            continue
        rows.append({
            "area_code": area, "industry": industry, "sigungu_code": sigungu[area],
            "target": math.log(sale[1] / store_cnt), **values,
        })
    return rows, list(MODEL_FEATURES)


def load_serving_scores(sql_path: Path = DATA_CORE_SQL) -> list[dict]:
    """서빙 점수 성분 로드 — 민감도 재계산 입력. burden_ratio = monthly_rent / est_sales.

    분자는 `initial_cost.monthly_rent`(업종 대표면적 기준)다. `rent.monthly_rent` 는 상권
    단위 표기값이라 업종별 부담률에 쓰면 카페가 1.89배 과대해진다 (리뷰 #2).
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

"""AI-07 평가 하네스 공용 로더·정규화·SQL 파서 (스펙 §12-1). 읽기 전용."""
from __future__ import annotations

import csv
import json
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


def load_confirmed_gold() -> list[dict]:
    return json.loads((GOLD_DIR / "extraction_confirmed.json").read_text(encoding="utf-8"))


def load_source_text(doc: str) -> str:
    path = FUNDING_DOCS / f"{doc}.txt"
    return path.read_text(encoding="utf-8") if path.exists() else ""


def is_clean_source(doc: str) -> bool:
    """근거 청크 가능 문서 판정 (load/finance.py _is_clean 미러)."""
    text = load_source_text(doc)
    if not text:
        return False
    keywords = ("대출", "융자", "한도", "금리", "보증", "지원", "소상공인", "상환", "기업")
    return sum(text.count(k) for k in keywords) / len(text) * 1000 >= 3.0


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


def load_serving_scores(sql_path: Path = DATA_CORE_SQL) -> list[dict]:
    """서빙 점수 성분 로드 — 민감도 재계산 입력. burden_ratio = monthly_rent / est_sales."""
    text = sql_path.read_text(encoding="utf-8")
    rent = {r[0]: int(r[2]) for r in _iter_sql_rows(text, "rent")}
    out: list[dict] = []
    for r in _iter_sql_rows(text, "location_score"):
        area, industry = r[0], r[1]
        w1, w2, w3, w4, w5 = (float(x) for x in r[2:7])
        est_sales = int(r[7])
        mr = rent.get(area)
        out.append({
            "area_code": area, "industry": industry,
            "w1": w1, "w2": w2, "w3": w3, "w4": w4, "w5": w5,
            "est_sales": est_sales, "monthly_rent": mr,
            "burden_ratio": (mr / est_sales) if (mr and est_sales) else None,
        })
    return out

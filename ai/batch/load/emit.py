"""DataFrame → PostgreSQL 결정적 덤프 (AI-05·06). 재실행 가능(TRUNCATE 선행).

값 규칙: None/NaN→NULL, bool→TRUE/FALSE, 숫자→리터럴, 리스트→ARRAY[...], 그 외→'이스케이프'.
"""
from __future__ import annotations

import datetime as _dt
import math

import pandas as pd


def _lit(v) -> str:
    if v is None or (isinstance(v, float) and math.isnan(v)):
        return "NULL"
    if isinstance(v, bool):
        return "TRUE" if v else "FALSE"
    if isinstance(v, float) and v.is_integer():
        return str(int(v))
    if isinstance(v, (int, float)):
        return repr(v)
    if isinstance(v, (list, tuple)):
        if len(v) == 0:
            return "'{}'"
        inner = ", ".join(_lit(x) for x in v)
        return f"ARRAY[{inner}]"
    if isinstance(v, _dt.date):
        return f"'{v.isoformat()}'"
    return "'" + str(v).replace("'", "''") + "'"


def to_insert_sql(table: str, df: pd.DataFrame) -> str:
    """단일 테이블 INSERT 문 (빈 DataFrame이면 빈 문자열)."""
    if df is None or len(df) == 0:
        return ""
    cols = list(df.columns)
    col_sql = ", ".join(cols)
    lines = []
    for row in df.itertuples(index=False):
        values = ", ".join(_lit(v) for v in row)
        lines.append(f"({values})")
    body = ",\n".join(lines)
    return f"INSERT INTO {table} ({col_sql}) VALUES\n{body};\n"


def emit_sql(tables: dict[str, pd.DataFrame], path, insert_order: list[str], header: str) -> None:
    """insert_order(부모→자식)대로 TRUNCATE + INSERT 덤프를 파일에 쓴다."""
    present = [t for t in insert_order if t in tables and len(tables[t]) > 0]
    truncate = ", ".join(reversed(present))  # 자식부터 TRUNCATE
    parts = [
        f"-- {header}",
        f"-- 생성: {_dt.date.today().isoformat()} (batch.load) — 재실행 시 전체 교체",
        "BEGIN;",
        f"TRUNCATE {truncate} RESTART IDENTITY CASCADE;",
        "",
    ]
    for name in present:
        parts.append(to_insert_sql(name, tables[name]))
    parts.append("COMMIT;")
    path.write_text("\n".join(parts) + "\n", encoding="utf-8")

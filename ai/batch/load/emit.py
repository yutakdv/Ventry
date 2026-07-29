"""DataFrame → PostgreSQL 결정적 덤프 (AI-05·06). 재실행 가능(TRUNCATE 선행).

값 규칙: None/NaN→NULL, bool→TRUE/FALSE, 숫자→리터럴, 리스트→ARRAY[...], 그 외→'이스케이프'.
"""
from __future__ import annotations

import datetime as _dt

import pandas as pd


def _lit(v) -> str:
    # pd.NA·NaT 도 결측이다 — float('nan') 만 걸러내면 nullable 정수(Int64) 컬럼이 한 단계만
    # 우회해도 문자열 '<NA>' 가 그대로 INSERT 된다 (rent_join·transit_join 이 Int64 사용, 리뷰 #12).
    if v is None:
        return "NULL"
    if not isinstance(v, (list, tuple, str)) and pd.isna(v):
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
    literal = str(v)
    if "\x00" in literal:
        raise ValueError(
            f"NUL 바이트가 값에 있다 — psql 이 주변 문자를 삼킨다 (리뷰 #4): {literal[:40]!r}"
        )
    return "'" + literal.replace("'", "''") + "'"


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
    """insert_order(부모→자식)대로 TRUNCATE + INSERT 덤프를 파일에 쓴다.

    헤더에 **생성 날짜를 넣지 않는다.** 이 저장소는 부록에서 재현성을 명시적으로 대조하는데,
    같은 입력으로 다른 날 재생성하면 1.6MB 파일에 헤더 한 줄 diff 가 나 「내용이 같은가」를
    확인하는 비용이 매번 발생했다 (AI 리뷰 P2). 덤프의 기준일은 각 행의 `data_as_of` 가 이미
    싣고 있으므로, 파일 헤더의 실행 날짜는 정보가 아니라 잡음이다.
    """
    present = [t for t in insert_order if t in tables and len(tables[t]) > 0]
    truncate = ", ".join(reversed(present))  # 자식부터 TRUNCATE
    parts = [
        f"-- {header}",
        "-- 생성: batch.load — 재실행 시 전체 교체 (기준일은 각 행 data_as_of 참조)",
        "BEGIN;",
        f"TRUNCATE {truncate} RESTART IDENTITY CASCADE;",
        "",
    ]
    for name in present:
        parts.append(to_insert_sql(name, tables[name]))
    parts.append("COMMIT;")
    path.write_text("\n".join(parts) + "\n", encoding="utf-8")

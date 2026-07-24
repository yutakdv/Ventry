"""SQL 덤프 emit 단위 테스트 (결정적·이스케이프, AI-05-2)."""
import pandas as pd

from batch.load.emit import to_insert_sql


def test_to_insert_sql_escapes_and_types():
    df = pd.DataFrame([{"area_code": "A'1", "name": "강남", "lat": 37.5, "n": 100}])
    sql = to_insert_sql("commercial_area", df)
    assert "INSERT INTO commercial_area" in sql
    assert "A''1" in sql  # 따옴표 이스케이프
    assert "37.5" in sql and "100" in sql


def test_to_insert_sql_null_and_bool():
    df = pd.DataFrame([{"a": None, "b": True, "c": float("nan")}])
    sql = to_insert_sql("t", df)
    assert "NULL" in sql
    assert "TRUE" in sql
    assert sql.count("NULL") == 2  # None + NaN


def test_emit_deterministic():
    df = pd.DataFrame([{"area_code": "A1", "name": "x"}])
    assert to_insert_sql("t", df) == to_insert_sql("t", df)


def test_empty_dataframe_no_insert():
    assert to_insert_sql("t", pd.DataFrame()) == ""

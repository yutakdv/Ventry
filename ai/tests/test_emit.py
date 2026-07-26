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


def test_to_insert_sql_nullable_int_na():
    """Int64(nullable) 컬럼의 pd.NA 가 문자열 '<NA>' 로 새지 않는다 (리뷰 #12).

    rent_join·transit_join 이 Int64 를 쓰므로 한 단계만 우회하면 실제로 발생할 수 있다.
    """
    df = pd.DataFrame({"riders": pd.array([100, None], dtype="Int64")})
    sql = to_insert_sql("t", df)
    assert "<NA>" not in sql
    assert sql.count("NULL") == 1
    assert "100" in sql


def test_emit_deterministic():
    df = pd.DataFrame([{"area_code": "A1", "name": "x"}])
    assert to_insert_sql("t", df) == to_insert_sql("t", df)


def test_to_insert_sql_rejects_nul_in_values():
    """NUL 이 덤프에 새면 psql 이 값 일부를 조용히 삼킨다 — 경계에서 막는다 (리뷰 #4)."""
    import pytest

    df = pd.DataFrame([{"text": "정상\x00문자열"}])
    with pytest.raises(ValueError, match="NUL"):
        to_insert_sql("finance_doc_chunk", df)


def test_empty_dataframe_no_insert():
    assert to_insert_sql("t", pd.DataFrame()) == ""

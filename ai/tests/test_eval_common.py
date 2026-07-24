from pathlib import Path

from eval import common


def test_norm_field_arrays_and_nulls():
    assert common.norm_field("regions", None) == frozenset()
    assert common.norm_field("regions", []) == frozenset()
    assert common.norm_field("regions", ["서울"]) == frozenset({"서울"})
    assert common.norm_field("regions", "['서울']") == frozenset({"서울"})
    assert common.norm_field("rate", None) is None
    assert common.norm_field("rate", "2.1") == 2.1
    assert common.norm_field("amount_max", "") is None
    assert common.norm_field("amount_max", "7000") == 7000
    assert common.norm_field("pre_startup_only", "True") is True
    assert common.norm_field("name", "  KB소상공인  ") == "KB소상공인"


def test_load_serving_scores_parses_sql(tmp_path: Path):
    sql = tmp_path / "core.sql"
    sql.write_text(
        "INSERT INTO rent (area_code, quarter, monthly_rent, x) VALUES\n"
        "('3110002', '20261', 400, NULL);\n"
        "INSERT INTO location_score (area_code, industry, w1, w2, w3, w4, w5, "
        "est_sales, daily_floating, based_on_quarter) VALUES\n"
        "('3110002', 'cafe', 0.3, 0.6, 0.7, 0.4, 0.5, 800, 620376, '20261');\n",
        encoding="utf-8",
    )
    rows = common.load_serving_scores(sql)
    assert len(rows) == 1
    r = rows[0]
    assert r["area_code"] == "3110002" and r["industry"] == "cafe"
    assert r["w1"] == 0.3 and r["w5"] == 0.5 and r["est_sales"] == 800
    assert r["monthly_rent"] == 400
    assert abs(r["burden_ratio"] - 0.5) < 1e-9


def test_load_extraction_draft_real():
    draft = common.load_extraction_draft()
    assert len(draft) == 29
    assert all("doc" in p and "name" in p for p in draft)

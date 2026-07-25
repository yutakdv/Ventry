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


def test_load_serving_scores_uses_industry_rent(tmp_path: Path):
    """부담률 분자는 `initial_cost.monthly_rent`(업종별)다.

    `rent.monthly_rent`(상권 단위)가 아니다 — 리뷰 #2.
    """
    sql = tmp_path / "core.sql"
    sql.write_text(
        "INSERT INTO rent (area_code, quarter, monthly_rent, x) VALUES\n"
        "('3110002', '20261', 400, NULL);\n"
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
    assert len(rows) == 2
    assert rows["cafe"]["area_code"] == "3110002"
    assert rows["cafe"]["w1"] == 0.3 and rows["cafe"]["est_sales"] == 800
    assert rows["cafe"]["monthly_rent"] == 200      # 상권 단위 400 이 아니다
    assert abs(rows["cafe"]["burden_ratio"] - 0.25) < 1e-9
    assert abs(rows["food"]["burden_ratio"] - 0.25) < 1e-9


def test_load_extraction_draft_real():
    draft = common.load_extraction_draft()
    assert len(draft) == 29
    assert all("doc" in p and "name" in p for p in draft)

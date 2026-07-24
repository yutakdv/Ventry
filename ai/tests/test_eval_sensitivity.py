from eval.suites import sensitivity


def _rows():
    # cafe 4곳, 전부 θ 통과(burden 0.1). w1 지배 → area 순위 명확.
    def r(code, w1):
        return {"area_code": code, "industry": "cafe",
                "w1": w1, "w2": 0.5, "w3": 0.5, "w4": 0.5, "w5": 0.5,
                "est_sales": 1000, "monthly_rent": 100, "burden_ratio": 0.1}
    return [r("A", 0.9), r("B", 0.8), r("C", 0.7), r("D", 0.1)]


def test_composite_and_top3():
    rows = _rows()
    base = sensitivity.top3(rows, sensitivity.WEIGHTS, sensitivity.THETA)
    assert base == ["A", "B", "C"]


def test_theta_gate_excludes_high_burden():
    rows = _rows()
    rows[0]["burden_ratio"] = 0.9  # A 탈락
    t = sensitivity.top3(rows, sensitivity.WEIGHTS, 0.15)
    assert "A" not in t and t == ["B", "C", "D"]


def test_evaluate_reports_retention():
    m = sensitivity.evaluate(_rows())
    assert m["cafe"]["base_top3"] == ["A", "B", "C"]
    assert 0.0 <= m["cafe"]["mean_retention"] <= 1.0
    assert m["cafe"]["n_perturbations"] == 12
    assert m["cafe"]["n_areas"] == 4

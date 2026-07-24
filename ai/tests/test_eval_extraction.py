from eval.suites import extraction


def _draft():
    return [
        {"doc": "D", "name": "A", "amount_max": 7000, "rate": 2.1, "regions": ["서울"]},
        {"doc": "D", "name": "B", "amount_max": None, "rate": None, "regions": None},
        {"doc": "D", "name": "C", "amount_max": 5000, "rate": 1.0, "regions": ["서울"]},
    ]


def _gold():
    # A: 완전일치(유지). B: amount_max 수정(1필드 불일치). C: 드롭. + 누락보완 E.
    base = {k: None for k in extraction.common.PRODUCT_KEYS}
    a = {**base, "name": "A", "amount_max": 7000, "rate": 2.1, "regions": ["서울"]}
    b = {**base, "name": "B", "amount_max": 3000, "rate": None, "regions": None}
    c = {**base, "name": "C", "amount_max": 5000, "rate": 1.0, "regions": ["서울"]}
    e = {**base, "name": "E", "amount_max": 1000}
    return [
        {"doc": "D", "name": "A", "review_action": "유지", "confirmed": a},
        {"doc": "D", "name": "B", "review_action": "수정", "confirmed": b},
        {"doc": "D", "name": "C", "review_action": "드롭", "confirmed": c},
        {"doc": "D", "name": "E", "review_action": "누락보완", "confirmed": e},
    ]


def test_counts_and_product_metrics():
    m = extraction.evaluate(_draft(), _gold())
    assert m["counts"] == {"유지": 1, "수정": 1, "드롭": 1, "누락보완": 1}
    assert m["product_precision"] == 2 / 3           # 유지+수정 / +드롭
    assert m["product_recall"] == 2 / 3              # 유지+수정 / +누락보완
    assert m["product_exact_rate"] == 1 / 2          # 유지 / 유지+수정


def test_field_accuracy_counts_single_mismatch():
    m = extraction.evaluate(_draft(), _gold())
    n_keys = len(extraction.common.PRODUCT_KEYS)
    # 대상 2상품(A,B) × n_keys, B 의 amount_max 1건만 불일치
    assert m["total_fields_compared"] == 2 * n_keys
    assert abs(m["field_accuracy"] - (2 * n_keys - 1) / (2 * n_keys)) < 1e-9
    assert m["per_field"]["amount_max"] == 1 / 2
    assert m["per_field"]["name"] == 1.0

"""AI-08 검증 모델 — 게이트 판정·CV 분할·누수 방지 (스펙 §12-2·§12-3, assumptions #35).

게이트는 결과 확인 전에 등재됐다. 임계치를 결과에 맞춰 바꾸면 §12-3의 심사 가치가 사라지므로,
경계값을 전건 고정해 둔다.
"""
from __future__ import annotations

import pytest

from eval import common
from eval.suites import model


@pytest.mark.parametrize(
    ("r2", "rho", "hits", "total", "expected"),
    [
        (0.35, 0.65, 4, 5, "A"),   # 전 조건 충족
        (0.30, 0.60, 4, 5, "A"),   # 경계값 포함 (≥)
        (0.29, 0.65, 5, 5, "B"),   # R² 미달 → A 탈락, B 충족
        (0.35, 0.55, 3, 5, "B"),   # ρ 미달이나 R²≥0.15 · 방향 0.6 → B
        (0.20, 0.30, 4, 5, "B"),   # ρ 미달이지만 방향 일치 0.8 충족
        (0.20, 0.30, 2, 5, "C"),   # 둘 다 미달
        (0.10, 0.90, 5, 5, "C"),   # R² 0.15 미만이면 무조건 C
        (0.15, 0.45, 0, 5, "B"),   # B 경계값 (≥)
    ],
)
def test_gate_thresholds(r2, rho, hits, total, expected):
    assert model.gate(r2, rho, hits, total) == expected


def test_gate_normalizes_direction_ratio():
    """판정 가능 축이 3개면 방향 일치는 비율로 환산한다 (assumptions #35 ⑤)."""
    assert model.gate(0.35, 0.65, 3, 3) == "A"       # 3/3 = 1.0 ≥ 0.8
    assert model.gate(0.35, 0.65, 2, 3) == "B"       # 2/3 ≈ 0.67 < 0.8 → A 탈락
    assert model.gate(0.35, 0.65, 0, 0) == "B"       # 판정 축 없음 → 방향 조건 미충족


def test_features_exclude_sales_derived_columns():
    """w2·w5·est_sales·monthly_sales 는 타깃 파생이라 피처에 있어선 안 된다 (누수 차단)."""
    _, feature_names = common.load_model_features()
    banned = {"w2", "w5", "est_sales", "monthly_sales", "target"}
    assert banned.isdisjoint(feature_names), f"누수 피처 포함: {banned & set(feature_names)}"


def test_feature_rows_have_target_and_group():
    rows, feature_names = common.load_model_features()
    assert len(rows) > 100, f"학습 표본 부족: {len(rows)}"
    assert len({r["sigungu_code"] for r in rows}) >= 5, "자치구 블록이 5-fold 에 못 미침"
    for row in rows[:20]:
        assert row["target"] > 0 or row["target"] < 0  # log 값은 음수도 유효, NaN 만 배제
        assert all(name in row for name in feature_names)


def test_group_split_keeps_sigungu_whole():
    """같은 자치구가 train/test 에 동시에 등장하면 공간 CV 가 무의미하다."""
    pytest.importorskip("sklearn", reason="배치 전용 의존 — 경량 CI(pytest+pandas)에선 skip")
    rows, _ = common.load_model_features()
    folds = model.group_folds(rows, n_splits=5)
    assert len(folds) == 5
    for train_idx, test_idx in folds:
        train_gu = {rows[i]["sigungu_code"] for i in train_idx}
        test_gu = {rows[i]["sigungu_code"] for i in test_idx}
        assert train_gu.isdisjoint(test_gu)


def test_design_signs_cover_all_features():
    """설계 부호·축 매핑이 피처 전건을 덮어야 SHAP 방향 판정이 성립한다."""
    _, feature_names = common.load_model_features()
    assert set(feature_names) <= set(common.DESIGN_SIGNS)
    assert set(feature_names) <= set(common.DESIGN_AXIS)


def test_hyperparameters_are_frozen():
    """튜닝 금지(assumptions #35 ⑥) — 값이 바뀌면 프로토콜 재등록이 선행돼야 한다."""
    assert model.PARAMS["n_estimators"] == 400
    assert model.PARAMS["learning_rate"] == 0.05
    assert model.PARAMS["num_leaves"] == 31
    assert model.PARAMS["min_child_samples"] == 20
    assert model.PARAMS["random_state"] == 42

"""검증 모델 스위트 (스펙 §12-2·§12-3) — LightGBM 설계 교차 검증 + SHAP 방향 일치.

**서빙 경로가 아니다.** 추천 엔진을 교체하지 않으며, 오프라인 배치·부록 전용이다
(§0-1 원칙 2 — 서빙 무모델). `lightgbm`·`shap` 은 ai/ 배치 의존에만 존재한다.

검증 질문: "매출을 입력하지 않은 우리 점수 축(수요·경쟁·성장)이 실제 추정매출을 설명하는가,
그리고 기여 방향이 설계 부호와 일치하는가."

프로토콜·게이트는 **결과 확인 전에** docs/assumptions.md #35 에 등재됐다(리스크 #19).
여기서 임계치·하이퍼파라미터를 바꾸지 말 것 — 바꿔야 한다면 실행 전 문서 재등록이 선행한다.
"""
from __future__ import annotations

import statistics
from pathlib import Path

from eval import common

# assumptions #35 ⑥ — 튜닝 금지 (사후 조정 방지)
PARAMS = {
    "n_estimators": 400, "learning_rate": 0.05, "num_leaves": 31,
    "min_child_samples": 20, "random_state": 42, "verbose": -1,
}
N_SPLITS = 5
# assumptions #35 ⑦ — §12-3 수록 게이트 (결과 확인 전 고정)
A_GATE = {"r2": 0.30, "rho": 0.60, "direction": 0.8}
B_GATE = {"r2": 0.15, "rho": 0.45, "direction": 0.8}

GATE_NOTE = {
    "A": "부록 2 전체 수록 — 설계-데이터 정합을 설명력·방향 양면에서 확인",
    "B": "부록 2 축소(방향 일치 중심 반 장) — 방향 정합 확인, 설명력은 참고 수준 + 한계 문구 필수",
    "C": "부록 2 미수록 — 성적표 행 제외, 민감도 분석 단독 유지(v6.1 상태 복귀)",
}


def gate(r2: float, rho: float, direction_hits: int, direction_total: int) -> str:
    """§12-3 수록 게이트. 사전 등록된 임계치 — 결과에 맞춰 조정 금지."""
    ratio = direction_hits / direction_total if direction_total else 0.0
    if r2 >= A_GATE["r2"] and rho >= A_GATE["rho"] and ratio >= A_GATE["direction"]:
        return "A"
    if r2 >= B_GATE["r2"] and (rho >= B_GATE["rho"] or ratio >= B_GATE["direction"]):
        return "B"
    return "C"


def gate_path(r2: float, rho: float, direction_hits: int, direction_total: int) -> str:
    """B 는 'ρ' 와 '방향 일치' 중 어느 쪽으로도 성립한다 — 어느 경로였는지 밝힌다.

    §12-3 표의 B 서술("방향 정합 확인, 설명력은 참고 수준")은 방향 경로를 전제한 문안이라,
    ρ 경로로 B 가 나오면 그대로 쓰면 사실과 어긋난다. 판정은 그대로 두고 경로를 병기한다.
    """
    if gate(r2, rho, direction_hits, direction_total) != "B":
        return "n/a"
    ratio = direction_hits / direction_total if direction_total else 0.0
    by_rho = rho >= B_GATE["rho"]
    by_direction = ratio >= B_GATE["direction"]
    if by_rho and by_direction:
        return "순위상관·방향 일치 양쪽 충족"
    return "순위상관(ρ) 충족 · 방향 일치 미달" if by_rho else "방향 일치 충족 · 순위상관(ρ) 미달"


def group_folds(rows: list[dict], n_splits: int = N_SPLITS):
    """자치구 블록 그룹아웃 — 공간 자기상관 탓에 랜덤 CV 는 성능을 과대평가한다."""
    from sklearn.model_selection import GroupKFold

    groups = [r["sigungu_code"] for r in rows]
    splitter = GroupKFold(n_splits=n_splits)
    return [(list(tr), list(te)) for tr, te in splitter.split([[0.0]] * len(rows), groups=groups)]


def _fit(train_x, train_y):
    from lightgbm import LGBMRegressor

    estimator = LGBMRegressor(**PARAMS)
    estimator.fit(train_x, train_y)
    return estimator


def _fold_metrics(true_y, pred_y, baseline_y) -> dict:
    """R²·ρ·WAPE·MAE. MAPE 미사용 — 저매출 구역 분모로 수치가 폭발·왜곡된다(§12-2 ④)."""
    import math

    from scipy.stats import spearmanr
    from sklearn.metrics import r2_score

    # WAPE·MAE 는 로그를 되돌린 만원 단위에서 계산해야 보고값의 의미가 산다.
    true_won = [math.exp(v) for v in true_y]
    pred_won = [math.exp(v) for v in pred_y]
    abs_err = [abs(t - p) for t, p in zip(true_won, pred_won, strict=True)]
    rho = spearmanr(true_y, pred_y).statistic
    return {
        "r2": float(r2_score(true_y, pred_y)),
        "rho": float(rho) if rho == rho else 0.0,          # NaN 방어 (분산 0 fold)
        "wape": sum(abs_err) / sum(true_won) if sum(true_won) else 0.0,
        "mae": sum(abs_err) / len(abs_err),
        "baseline_r2": float(r2_score(true_y, baseline_y)),
    }


def cross_validate(rows: list[dict], features: list[str]) -> dict:
    """자치구 블록 5-fold. fold 중앙값을 보고값으로 쓴다 — 낮더라도 정직한 숫자."""
    folds = group_folds(rows)
    per_fold = []
    for train_idx, test_idx in folds:
        train_x = [[rows[i][f] for f in features] for i in train_idx]
        train_y = [rows[i]["target"] for i in train_idx]
        test_x = [[rows[i][f] for f in features] for i in test_idx]
        test_y = [rows[i]["target"] for i in test_idx]
        pred_y = list(_fit(train_x, train_y).predict(test_x))
        baseline = statistics.fmean(train_y)               # 자치구 평균 예측 = 베이스라인
        per_fold.append(_fold_metrics(test_y, pred_y, [baseline] * len(test_y)))
    median = {f"{k}_median": statistics.median(m[k] for m in per_fold) for k in per_fold[0]}
    return {"per_fold": per_fold, **median, "n_splits": len(folds)}


def shap_directions(rows: list[dict], features: list[str], out_dir: Path) -> dict:
    """피처별 SHAP 기여 방향 → 설계 축 단위 다수결 (§12-2 ⑤).

    가중치 '크기'의 일치까지는 주장하지 않는다 — 방향·상대 중요도 수준의 정합만 본다.
    """
    from collections import defaultdict

    import numpy as np
    import shap

    matrix = np.array([[r[f] for f in features] for r in rows], dtype=float)
    target = np.array([r["target"] for r in rows], dtype=float)
    estimator = _fit(matrix, target)
    values = shap.TreeExplainer(estimator).shap_values(matrix)

    # 피처값이 클수록 SHAP 이 커지면 양의 기여 — 중심화한 피처와의 공분산 부호로 판정한다.
    # 모델 없는 원자료 Spearman 도 함께 낸다: 부호 불일치가 SHAP 아티팩트인지 데이터 신호인지
    # 구분하려면 이 대조가 필요하다(다중공선성 하에서 트리 SHAP 은 기여를 임의 배분할 수 있다).
    from scipy.stats import spearmanr

    by_feature, importance = {}, {}
    for idx, name in enumerate(features):
        centered = matrix[:, idx] - matrix[:, idx].mean()
        covariance = float(np.mean(centered * values[:, idx]))
        raw_rho = float(spearmanr(matrix[:, idx], target).statistic)
        by_feature[name] = {
            "observed_sign": 1 if covariance > 0 else (-1 if covariance < 0 else 0),
            "design_sign": common.DESIGN_SIGNS[name],
            "mean_abs_shap": float(np.abs(values[:, idx]).mean()),
            "raw_spearman": raw_rho,
            "raw_sign_agrees": (raw_rho > 0) == (common.DESIGN_SIGNS[name] > 0),
        }
        importance[name] = by_feature[name]["mean_abs_shap"]

    axis_votes: dict[str, list[bool]] = defaultdict(list)
    for name, info in by_feature.items():
        axis_votes[common.DESIGN_AXIS[name]].append(info["observed_sign"] == info["design_sign"])
    # 다수결 — 동수(2:2)는 '다수'가 아니므로 불일치로 본다. 판정 근거를 남긴다.
    by_axis = {axis: sum(v) > len(v) / 2 for axis, v in axis_votes.items()}
    axis_votes_detail = {a: f"{sum(v)}/{len(v)}" for a, v in axis_votes.items()}

    _summary_plot(values, matrix, features, out_dir / "shap_summary.png")
    return {
        "hits": sum(by_axis.values()),
        "total": len(by_axis),
        "by_axis": {a: bool(v) for a, v in by_axis.items()},
        "axis_votes": axis_votes_detail,
        "by_feature": by_feature,
        "importance_rank": sorted(importance, key=importance.get, reverse=True),
    }


def _summary_plot(values, matrix, features: list[str], path: Path) -> None:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import shap

    shap.summary_plot(values, matrix, feature_names=features, show=False, plot_size=(7, 4))
    plt.tight_layout()
    plt.savefig(path, dpi=120)
    plt.close("all")


def run(out_dir: Path) -> dict:
    out_dir.mkdir(parents=True, exist_ok=True)
    rows, features = common.load_model_features()
    cv = cross_validate(rows, features)
    direction = shap_directions(rows, features, out_dir)
    args = (cv["r2_median"], cv["rho_median"], direction["hits"], direction["total"])
    verdict = gate(*args)
    return {
        "n_rows": len(rows),
        "n_groups": len({r["sigungu_code"] for r in rows}),
        "features": features,
        "target": "log(월 점포당 추정매출)",
        "cv": f"자치구 블록 GroupKFold(n_splits={cv['n_splits']})",
        "params": PARAMS,
        "r2_median": cv["r2_median"],
        "rho_median": cv["rho_median"],
        "wape_median": cv["wape_median"],
        "mae_median": cv["mae_median"],
        "baseline_r2_median": cv["baseline_r2_median"],
        "per_fold": cv["per_fold"],
        "direction": direction,
        "gate": verdict,
        "gate_note": GATE_NOTE[verdict],
        "gate_path": gate_path(*args),
        "limitation": (
            "타깃인 추정매출 자체가 카드 데이터 기반 공표 추정치이므로, 본 검증은 '설계와 공개 "
            "데이터 세계의 정합' 확인이며 실매출 예측력 주장이 아니다. 서비스의 모든 숫자가 동일 "
            "데이터 위에서 작동하므로 이것이 정확한 검증 범위다."
        ),
    }

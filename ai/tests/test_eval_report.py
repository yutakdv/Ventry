import json
from pathlib import Path

import pytest

pytest.importorskip("matplotlib")  # 차트 산출 의존 — 경량 CI(미설치)에선 skip

from eval.suites import report  # noqa: E402


def test_report_writes_metrics_json(tmp_path: Path):
    result = report.run(tmp_path)
    out = tmp_path / "metrics.json"
    assert out.exists()
    data = json.loads(out.read_text(encoding="utf-8"))
    assert set(data) >= {"generated_at", "extraction", "grounding",
                         "sensitivity", "matching", "model"}
    assert data["matching"]["status"] == "BE 소관"
    assert result["extraction"]["total_products"] >= 29
    # model 은 AI-08(#21)에서 스텁 → 실측으로 교체됐다. 게이트 판정이 성적표에 들어와야 한다.
    assert data["model"]["gate"] in {"A", "B", "C"}
    assert data["model"]["target"] == "log(월 점포당 추정매출)"
    assert (tmp_path / "shap_summary.png").exists()

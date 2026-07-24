import json
from pathlib import Path

from eval.suites import report


def test_report_writes_metrics_json(tmp_path: Path):
    result = report.run(tmp_path)
    out = tmp_path / "metrics.json"
    assert out.exists()
    data = json.loads(out.read_text(encoding="utf-8"))
    assert set(data) >= {"generated_at", "extraction", "grounding",
                         "sensitivity", "matching", "model"}
    assert data["matching"]["status"] == "BE 소관"
    assert data["model"]["status"] == "AI-08 소관"
    assert result["extraction"]["total_products"] >= 29

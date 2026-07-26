"""#93 — 문서별 수집일 매니페스트. 배치를 다시 돌려도 수집일이 갱신되면 안 된다."""
from __future__ import annotations

import json
from datetime import date

import pytest

from batch.collect import collected


@pytest.fixture
def manifest(tmp_path, monkeypatch):
    path = tmp_path / "collected.json"
    monkeypatch.setattr(collected, "MANIFEST", path)
    return path


def test_record_then_load_roundtrip(manifest):
    collected.record(["소진공_융자공고"], "2026-07-21")

    assert collected.load() == {"소진공_융자공고": "2026-07-21"}


def test_record_updates_only_named_documents(manifest):
    """실제로 수집한 문서만 갱신한다 — 건드리지 않은 문서의 날짜는 보존된다."""
    collected.record(["A", "B"], "2026-07-21")

    collected.record(["B"], "2026-07-26")

    assert collected.load() == {"A": "2026-07-21", "B": "2026-07-26"}


def test_for_doc_returnsRecordedDate_notToday(manifest):
    """이 테스트가 #93 의 본체다 — 적재를 다시 돌린 날이 아니라 수집한 날이 나와야 한다."""
    collected.record(["소진공_융자공고"], "2026-07-21")

    assert collected.for_doc("소진공_융자공고", date.today().isoformat()) == "2026-07-21"


def test_for_doc_fallsBackWithWarning_whenUnrecorded(manifest, caplog):
    """미기록 문서는 폴백하되 조용히 넘어가지 않는다 — 경고가 없으면 결함이 다시 숨는다."""
    with caplog.at_level("WARNING"):
        stamp = collected.for_doc("미기록문서", "2026-07-26")

    assert stamp == "2026-07-26"
    assert "미기록문서" in caplog.text


def test_stamp_from_mtime_usesFileTime(tmp_path):
    """원본을 내려받은 시각이 그 파일의 mtime 이다 — 추출을 다시 돌려도 변하지 않는다."""
    pdf = tmp_path / "doc.pdf"
    pdf.write_bytes(b"%PDF-1.4")
    import os
    os.utime(pdf, (1_784_000_000, 1_784_000_000))   # 2026-07-14 부근 고정 시각

    assert collected.stamp_from_mtime(pdf) == date.fromtimestamp(1_784_000_000).isoformat()


def test_missing_manifest_isEmpty_notCrash(manifest):
    assert collected.load() == {}


def test_shipped_manifest_covers_every_loaded_document():
    """배포되는 매니페스트가 적재 문서를 전부 덮는지 — 하나라도 빠지면 그 문서만 오늘로 찍힌다."""
    from batch.paths import AI_ROOT

    shipped = json.loads((AI_ROOT / "data" / "finance" / "collected.json").read_text("utf-8"))
    reviewed = json.loads(
        (AI_ROOT / "data" / "finance" / "reviewed.json").read_text("utf-8"))

    missing = sorted({p["doc"] for p in reviewed} - set(shipped))
    assert missing == []

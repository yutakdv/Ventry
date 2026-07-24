"""정책자금 적재·청킹 단위 테스트 (AI-06-2)."""
from batch.load.finance import build_finance, chunk_document


def test_chunk_document_keeps_original_text():
    chunks = chunk_document("소진공_x", "문단1 원문.\n\n문단2 원문.")
    assert chunks[0]["text"] == "문단1 원문."  # 재작성 없이 원문 그대로
    assert chunks[0]["chunk_id"]


def test_build_finance_links_doc_chunk_ref(tmp_path):
    (tmp_path / "소진공_x.txt").write_text("창업기업자금 안내.\n\n한도 7천만원.", encoding="utf-8")
    reviewed = [{
        "product_id": "p1", "name": "창업기업자금", "org": "소진공", "amount_max": 7000,
        "rate": 2.5, "status": "open", "doc": "소진공_x", "source_url": "http://x",
        "notice_date": "2026-06-01",
    }]
    tables = build_finance(reviewed, docs_dir=tmp_path)
    fp = tables["finance_product"].iloc[0]
    assert fp["doc_chunk_ref"] in set(tables["finance_doc_chunk"]["chunk_id"])
    assert fp["source_org"] == "소진공" and fp["data_as_of"]


def test_build_finance_missing_doc_still_links(tmp_path):
    reviewed = [{"product_id": "p2", "name": "X자금", "org": "소진공",
                 "amount_max": 3000, "rate": 2.0, "status": "open", "doc": "없는문서"}]
    tables = build_finance(reviewed, docs_dir=tmp_path)
    fp = tables["finance_product"].iloc[0]
    assert fp["doc_chunk_ref"] in set(tables["finance_doc_chunk"]["chunk_id"])  # 폴백 청크 링크

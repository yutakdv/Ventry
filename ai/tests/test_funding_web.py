"""웹 1차 출처 수집본의 건전성 (#72, 스펙 §0-1·§5-4).

두 가지를 잠근다:
1. 서울신보 원문이 다시 모지바케로 되돌아가지 않을 것 (프린트본 PDF 회귀 방지)
2. 골드셋에 있는 모든 금리 수치가 수집 원문 안에서 발견될 것 (출처 없는 수치 금지)
"""
from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

# `batch.collect` 패키지 __init__ 이 전 수집기를 eager import 하며 requests 를 끌어온다.
# ai-ci 는 pytest+pandas 만 설치하는 경량 환경이라 모듈 최상위에서 import 하면 수집이 깨진다.
# 파일만 읽는 테스트는 의존이 없으므로, 수집기 헬퍼가 필요한 테스트에서만 지연 import 한다.

AI_ROOT = Path(__file__).resolve().parents[1]
DOCS = AI_ROOT / "data" / "interim" / "funding_docs"
GOLD = AI_ROOT / "eval" / "gold" / "extraction_confirmed.json"

_DOMAIN_KEYWORDS = ("보증", "한도", "기업", "지원")


def _corpus() -> str:
    return "\n".join(p.read_text(encoding="utf-8") for p in DOCS.glob("*.txt"))


def test_seoulshinbo_texts_are_readable_korean():
    """모지바케 텍스트는 도메인 키워드가 거의 등장하지 않는다."""
    files = sorted(DOCS.glob("서울신보_보증상품_*.txt"))
    assert len(files) >= 6, f"서울신보 원문 부족: {len(files)}"
    for path in files:
        text = path.read_text(encoding="utf-8")
        hits = sum(text.count(k) for k in _DOMAIN_KEYWORDS)
        assert hits >= 5, f"{path.name}: 도메인 키워드 {hits}건 — 추출 깨짐 의심"


def test_semas_rate_table_present_in_corpus():
    """소진공 분기 기준금리표가 원문 코퍼스 안에 있어야 한다 (변동금리 rate 의 근거).

    별도 수집이 아니라 지원사업안내 PDF 추출본에 이미 들어 있다 — 이 사실이 깨지면
    변동금리 상품의 rate 가 출처를 잃으므로 회귀로 잡는다.
    """
    text = (DOCS / "소진공_소상공인정책자금_지원사업안내.txt").read_text(encoding="utf-8")
    assert "기준금리" in text
    assert "3.85%" in text, "분기 기준금리 실값이 코퍼스에 없다"
    assert "3/4분기" in text, "적용 분기 표기가 없다 — 기준일 표기 필수(CLAUDE.md §4)"


def test_gold_rates_are_traceable_to_source_text():
    """골드셋의 모든 rate 값은 수집 원문에서 **금리 문맥으로** 발견돼야 한다 (§0-1).

    맨 숫자 포함 여부로는 부족하다 — `2.0` 은 `2` 로 포맷되어 아무 문서에나 걸린다.
    `연2.00%` · `2.0%` · `+0.4%p` 처럼 % 를 동반한 형태만 출처로 인정한다.
    """
    corpus = _corpus()
    orphans = []
    for item in json.loads(GOLD.read_text(encoding="utf-8")):
        if item.get("review_action") == "드롭":
            continue
        rate = (item.get("confirmed") or {}).get("rate")
        if rate is None:
            continue
        value = float(rate)
        # 4.25 → '4.25' / 2.0 → '2.0' 과 '2.00' 둘 다 허용 (표기 관행이 문서마다 다름)
        forms = {f"{value:g}", f"{value:.1f}", f"{value:.2f}"}
        alternatives = "|".join(re.escape(f) for f in sorted(forms))
        if not re.search(rf"(?:연\s?)?(?:{alternatives})\s?%", corpus):
            orphans.append((item.get("name"), rate))
    assert orphans == [], f"원문에서 금리 문맥으로 확인되지 않는 rate: {orphans}"


def test_nav_noise_is_stripped():
    """본문 앵커 뒤에 남는 전역 내비게이션 잔재를 제거한다."""
    pytest.importorskip("requests", reason="batch.collect 패키지가 requests 를 eager import 한다")
    from batch.collect import funding_web

    raw = "<div>메뉴</div><div>금리안내</div><div>Quick Link</div><div>기준금리 3.85%</div>"
    text = funding_web.to_text(raw, "금리안내")
    assert "Quick Link" not in text
    assert "기준금리 3.85%" in text

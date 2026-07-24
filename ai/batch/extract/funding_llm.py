"""정책자금 공고문 → 구조화 (gpt-4o 배치, AI-06, DECISIONS §6).

불변 원칙(스펙 §0-1): LLM은 **오프라인 배치에서 구조화만** 한다. 수치는 공고문에 명시된 값을
그대로 추출하며 생성·추정·재계산하지 않는다(없으면 null). 전건 사람 검수가 최종 게이트다.

산출: interim/finance/extracted.json (상품 리스트) + 검수대조표.csv (전건 사람 검수용).
실행: python -m batch.extract funding_llm
"""
from __future__ import annotations

import csv
import json

from batch.collect._common import load_env, require_key
from batch.paths import INTERIM_DIR, logger

OPENAI_MODEL = "gpt-4o"
FUNDING_DIR = INTERIM_DIR / "funding_docs"
OUT_DIR = INTERIM_DIR / "finance"

# 상품 스키마 키 (finance_product DDL + 검수 메타 rate_note)
PRODUCT_KEYS = (
    "name", "org", "max_age", "industries", "regions", "pre_startup_only",
    "amount_max", "rate", "rate_note", "term_months", "exclusive_group",
    "status", "notice_date", "source_url",
)
REQUIRED_FIELDS = ("name", "org", "amount_max", "rate", "status")
# 용어 컴플라이언스 — 추출 값에 '승인/추천/권장'(자금 서술 금칙어) 유입 차단 (CLAUDE.md)
BANNED_WORDS = ("승인", "추천", "권장")

SYSTEM_PROMPT = """당신은 소상공인 정책자금 공고문에서 상품 정보를 '추출'하는 도구다. 규칙:
- 문서에 명시된 값만 추출한다. 없으면 null 로 둔다. 값을 생성·추정·계산하지 마라.
- 한 공고문에 세부 자금이 여러 개면 각각을 별도 상품으로 추출한다.
- 금액(amount_max)은 만원 단위 정수(1억원=10000, 7천만원=7000).
- 금리(rate)는 연 % 숫자. '기준금리+X%p' 변동금리면 rate 에 가산 X 를 넣고 rate_note 에
  "기준금리+X%p" 를 기재한다. 고정금리면 rate 에 그 값, rate_note 는 null.
- term_months 는 대출기간(개월). max_age 는 상한 연령(청년 등), 없으면 null.
- industries/regions 는 제한이 있으면 배열, 전 업종/지역이면 null.
- pre_startup_only 는 예비창업자 한정이면 true, 아니면 false.
- status 는 접수중이면 "open", 마감이면 "closed".
반드시 JSON 객체만 출력: {"products": [ { …위 키… }, ... ]}"""


def parse_extraction(raw_json: str) -> dict:
    """단일 상품 JSON 문자열 → 정규화 dict (전 키 존재, null 보존)."""
    obj = json.loads(raw_json)
    return {k: obj.get(k) for k in PRODUCT_KEYS}


def validate_product(product: dict) -> list[str]:
    """검수 자동 1차 — 필수 결측·용어 위반·범위 오류 목록(비면 통과)."""
    errors: list[str] = []
    for field in REQUIRED_FIELDS:
        if product.get(field) in (None, ""):
            errors.append(f"필수 결측: {field}")
    for key, value in product.items():
        if isinstance(value, str):
            for word in BANNED_WORDS:
                if word in value:
                    errors.append(f"용어 컴플라이언스 위반('{word}'): {key}")
    amount = product.get("amount_max")
    if isinstance(amount, (int, float)) and amount <= 0:
        errors.append("amount_max ≤ 0")
    rate = product.get("rate")
    if isinstance(rate, (int, float)) and not 0 <= rate < 20:
        errors.append("rate 범위 밖(0~20%)")
    return errors


def extract_doc(client, doc_name: str, text: str) -> list[dict]:
    """공고문 1건 → 상품 리스트 (gpt-4o JSON 모드). 문서 태그를 붙인다."""
    resp = client.chat.completions.create(
        model=OPENAI_MODEL,
        response_format={"type": "json_object"},
        temperature=0,
        messages=[
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": f"[공고문: {doc_name}]\n\n{text}"},
        ],
    )
    payload = json.loads(resp.choices[0].message.content)
    products = payload.get("products", []) if isinstance(payload, dict) else []
    out = []
    for raw in products:
        product = {k: raw.get(k) for k in PRODUCT_KEYS}
        product["doc"] = doc_name
        out.append(product)
    return out


def extract_all(client) -> list[dict]:
    docs = sorted(FUNDING_DIR.glob("*.txt"))
    all_products: list[dict] = []
    for path in docs:
        text = path.read_text(encoding="utf-8")
        products = extract_doc(client, path.stem, text)
        logger.info("  %-40s → %d상품", path.stem[:40], len(products))
        all_products.extend(products)
    return all_products


def write_review_sheet(products: list[dict], path) -> None:
    """전건 사람 검수 대조표 — 상품×필드 + 자동검증 결과 + 수정칸(빈)."""
    path.parent.mkdir(parents=True, exist_ok=True)
    fields = ["doc", *PRODUCT_KEYS]
    with path.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.writer(f)
        writer.writerow([*fields, "자동검증", "검수결과", "수정값"])
        for p in products:
            errors = validate_product(p)
            verdict = "OK" if not errors else "; ".join(errors)
            writer.writerow([*(p.get(k) for k in fields), verdict, "", ""])


def run() -> None:
    from openai import OpenAI

    key = require_key(load_env(), "OPENAI_API_KEY")
    client = OpenAI(api_key=key)
    products = extract_all(client)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    (OUT_DIR / "extracted.json").write_text(
        json.dumps(products, ensure_ascii=False, indent=2), encoding="utf-8")
    write_review_sheet(products, OUT_DIR / "검수대조표.csv")
    ok = sum(1 for p in products if not validate_product(p))
    logger.info("추출 %d상품 (자동검증 통과 %d) → %s", len(products), ok, OUT_DIR)

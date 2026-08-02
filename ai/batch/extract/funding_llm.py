"""정책자금 공고문(PDF) → 구조화 (gpt-4o 배치, AI-06, DECISIONS §6).

불변 원칙(스펙 §0-1): LLM은 **오프라인 배치에서 구조화만** 한다. 수치는 공고문에 명시된 값을
그대로 추출하며 생성·추정·재계산하지 않는다(없으면 null). 전건 사람 검수가 최종 게이트다.

⚠️ 입력은 **raw PDF**다 — interim txt 는 CID 폰트/JS 렌더로 깨진 문서(서울신보·KB일부)가 있어
gpt-4o 네이티브 PDF 입력으로 우회한다(코드리뷰 S1). 서비스 스코프는 **서울** 이므로 지역 분할
상품은 서울 기준만 추출한다(S3). 대출/보증 상품을 구분하고(보증료율≠대출금리), 변동금리는
rate=null + rate_note 로 표기한다(S3·S4).

산출: ai/data/finance/extracted.json + 검수대조표.csv (문서별 커버리지 포함, 전건 사람 검수용).
실행: python -m batch.extract funding_llm
"""
from __future__ import annotations

import base64
import csv
import json

from batch.paths import AI_ROOT, RAW_DIR, logger

OPENAI_MODEL = "gpt-4o"
PDF_DIR = RAW_DIR
OUT_DIR = AI_ROOT / "data" / "finance"  # 추적 경로 (검수대조표 = DoD 보존·AI-07 골드)

# finance_product DDL + 검수 메타(product_type·rate_note)
PRODUCT_KEYS = (
    "name", "org", "product_type", "max_age", "industries", "regions",
    "pre_startup_only", "amount_max", "rate", "rate_note", "term_months",
    "exclusive_group", "status", "notice_date", "source_url",
)
# rate 는 필수에서 제외 — 변동금리(rate_note 있음)는 정당한 null (코드리뷰 S6)
REQUIRED_FIELDS = ("name", "org", "amount_max", "status")
BANNED_WORDS = ("승인", "추천", "권장")  # 용어 컴플라이언스 (docs/PROJECT_RULES.md §2)

# 문서 출처 → (org, source_url) — 기관·URL 은 문서 메타로 결정적 주입(LLM 아님)
DOC_SOURCES: dict[str, tuple[str, str]] = {
    "소진공": ("소진공", "https://ols.semas.or.kr"),
    "서울신보": ("서울신용보증재단", "https://www.seoulshinbo.co.kr"),
    "KB": ("KB국민은행", "https://obank.kbstar.com"),
}

SYSTEM_PROMPT = """당신은 소상공인 정책자금·보증 공고문(PDF)에서 상품 정보를 '추출'하는 도구다.
규칙:
- 문서에 명시된 값만 추출한다. 없으면 null. 값을 생성·추정·계산하지 마라.
- 서비스 대상은 **서울 소상공인**이다. 지역별로 조건이 나뉘면 **서울 기준만** 추출한다.
- 한 문서에 세부 자금/보증이 여러 개면 각각을 별도 상품으로 추출한다. name(자금·보증명)과
  amount_max(한도)는 반드시 채운다. 명확한 자금명·한도가 없는 잡음 항목은 만들지 마라.
- product_type: 대출상품이면 "loan", 보증상품이면 "guarantee".
  loan → amount_max=대출한도, rate=대출금리(연 %). guarantee → amount_max=보증한도,
  rate=보증료율(연 %). 둘을 섞지 마라.
- amount_max 는 만원 단위 정수(1억=10000, 7천만원=7000, 5천만원=5000).
- 금리(rate): **고정금리면 그 숫자**(rate_note=null). **변동금리('기준금리+X%p', '기준금리',
  '분기별 변동' 등)면 rate=null 로 두고 rate_note 에 원문 표현을 그대로 적는다.**
- term_months: 대출기간(개월, 5년=60). max_age: 상한 연령(청년 등) 없으면 null.
- industries/regions: 제한 있으면 배열(서울이면 ["서울"]), 전 업종/지역이면 null.
- pre_startup_only: 예비창업자 한정이면 true. status: 접수중 "open", 마감 "closed"(불명확 "open").
- notice_date: 공고일 YYYY-MM-DD, 없으면 null. org·source_url 은 비워둬도 된다(후처리).
반드시 JSON 객체만 출력: {"products": [ { …위 키… }, ... ]}"""


def _doc_source(doc_name: str) -> tuple[str | None, str | None]:
    for prefix, (org, url) in DOC_SOURCES.items():
        if doc_name.startswith(prefix):
            return org, url
    return None, None


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
    # 고정금리인데 rate 없음 → 결측. 변동금리(rate_note 있음)면 rate null 정당 (S6)
    if product.get("rate") is None and not product.get("rate_note"):
        errors.append("필수 결측: rate (고정금리)")
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


def _norm_name(name: str) -> str:
    return "".join((name or "").split()).replace("(", "").replace(")", "")


def dedup_products(products: list[dict]) -> list[dict]:
    """(org, 자금명) 중복 제거 — 금액 있는 행 우선(소진공 두 문서 중복, S2)."""
    best: dict[tuple, dict] = {}
    for p in products:
        key = (p.get("org"), _norm_name(p.get("name", "")))
        cur = best.get(key)
        if cur is None:
            best[key] = p
            continue
        # 금액 있는 쪽, 그다음 채운 필드 많은 쪽 우선
        score = (p.get("amount_max") is not None, sum(v is not None for v in p.values()))
        cur_score = (cur.get("amount_max") is not None, sum(v is not None for v in cur.values()))
        if score > cur_score:
            best[key] = p
    return list(best.values())


def extract_doc(client, doc_name: str, pdf_bytes: bytes) -> list[dict]:
    """공고문 PDF 1건 → 상품 리스트 (gpt-4o 네이티브 PDF 입력). 문서·출처 태그."""
    b64 = base64.b64encode(pdf_bytes).decode()
    resp = client.chat.completions.create(
        model=OPENAI_MODEL,
        temperature=0,
        response_format={"type": "json_object"},
        messages=[
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": [
                {"type": "file", "file": {
                    "filename": f"{doc_name}.pdf",
                    "file_data": f"data:application/pdf;base64,{b64}"}},
                {"type": "text", "text": f"공고문 파일명: {doc_name}. 상품을 추출하라."},
            ]},
        ],
    )
    payload = json.loads(resp.choices[0].message.content)
    raw_products = payload.get("products", []) if isinstance(payload, dict) else []
    org, url = _doc_source(doc_name)
    out = []
    for raw in raw_products:
        product = {k: raw.get(k) for k in PRODUCT_KEYS}
        product["org"] = org  # 문서 메타 결정적 주입
        product["source_url"] = product.get("source_url") or url
        product["doc"] = doc_name
        out.append(product)
    return out


def extract_all(client) -> tuple[list[dict], dict[str, int]]:
    """전 PDF 추출 + 문서별 상품 수(0 포함) 커버리지."""
    coverage: dict[str, int] = {}
    all_products: list[dict] = []
    for path in sorted(PDF_DIR.glob("*.pdf")):
        products = extract_doc(client, path.stem, path.read_bytes())
        coverage[path.stem] = len(products)
        flag = " ⚠️0건" if not products else ""
        logger.info("  %-42s → %d상품%s", path.stem[:42], len(products), flag)
        all_products.extend(products)
    return all_products, coverage


def write_review_sheet(products: list[dict], coverage: dict[str, int], path) -> None:
    """전건 사람 검수 대조표 — 문서별 커버리지(0건 포함) + 상품행(필드별 편집) + 검수액션."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(["# 문서별 커버리지 (0건 = 추출 실패/누락 — 반드시 확인)"])
        w.writerow(["문서", "추출상품수", "비고"])
        for doc, cnt in sorted(coverage.items()):
            w.writerow([doc, cnt, "⚠️ 0건 — 원본 확인 필요" if cnt == 0 else ""])
        w.writerow([])
        w.writerow(["# 상품 상세 (필드 칸을 직접 수정. 검수액션: 유지/수정/드롭)"])
        w.writerow(["doc", *PRODUCT_KEYS, "자동검증", "검수액션", "검수의견"])
        for p in products:
            errors = validate_product(p)
            verdict = "OK" if not errors else "; ".join(errors)
            w.writerow([p.get("doc"), *(p.get(k) for k in PRODUCT_KEYS), verdict, "", ""])


def run() -> None:
    from openai import OpenAI

    from batch.collect._common import load_env, require_key

    key = require_key(load_env(), "OPENAI_API_KEY")
    # 단발 호출이면 일시적 429/5xx 한 번에 그 문서가 0건이 되고 루프는 다음 PDF 로 넘어간다.
    # 0건은 검수대조표가 「⚠️ 0건 — 원본 확인 필요」로 세우고 전건 사람 검수가 최종 게이트라
    # 오염이 적재까지 가지는 않지만, 재실행 비용이 크므로 SDK 재시도로 흡수한다 (AI 리뷰 P2 m-05).
    client = OpenAI(api_key=key, max_retries=3, timeout=60)
    raw_products, coverage = extract_all(client)
    products = dedup_products(raw_products)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    (OUT_DIR / "extracted.json").write_text(
        json.dumps(products, ensure_ascii=False, indent=2), encoding="utf-8")
    write_review_sheet(products, coverage, OUT_DIR / "검수대조표.csv")
    ok = sum(1 for p in products if not validate_product(p))
    zero = [d for d, c in coverage.items() if c == 0]
    logger.info("추출 %d(중복제거 후) · 자동검증 통과 %d · 0건 문서 %d개 %s",
                len(products), ok, len(zero), zero)

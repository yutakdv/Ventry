#!/usr/bin/env python3
"""BE-07 통합 QA 하네스 (이슈 #23) — 기동 중인 스택에 실제 HTTP 요청을 던져 계약을 검증한다.

    docker compose up -d --build
    python3 scripts/qa_integration.py                    # 전 시나리오
    python3 scripts/qa_integration.py --only N1 B2       # 일부만

단위 테스트가 이미 순수 함수를 증명하고 있으므로, 여기서 재는 것은 **계약과 실데이터가
붙은 상태의 동작**이다 — 직렬화 규칙(값 없는 필드 생략), SSE 이벤트 순서, 세션 상태,
오류 응답 규격, 무LLM 폴백. 표준 라이브러리만 쓴다(심사위원이 추가 설치 없이 돌릴 수 있어야 한다).

무LLM 시나리오(F2)는 키를 지운 별도 스택이 필요하므로 이 스크립트 밖에서 준비한다:
`OPENAI_API_KEY= docker compose up -d --force-recreate api` 후 `--only F2`.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
TIMEOUT = 15

PROFILE_CAFE = {
    "form": {"age": 32, "capital": 5000, "is_existing_business": False,
             "collateral_available": True, "monthly_investable": 250,
             "industry": "cafe", "region_hint": "서울 마포구"},
    "free_text": "권리금이 제일 걱정이에요",
}
VERDICTS = {"FIT", "CONDITIONAL", "CAUTION", "OUT_OF_SCOPE"}

# README 도슨트 가이드의 기준값 사본 (시나리오 D1). 정본은 README 이며 여기는 대조용이다 —
# 어긋나면 이 상수가 아니라 README 를 고친다. 값이 바뀌는 계기는 코드가 아니라 **재적재**다.
DOCENT = {
    "confirmed_budget": 10000,
    # 2026-07-30 갱신 — 초기 예산을 슬라이더 격자(100만원)로 올리면서 7,901→8,000 ·
    # 10,440→10,500 이 됐다. 격자를 벗어난 초기값은 슬라이더를 잡는 순간 스냅해, 확정했다고
    # 적힌 금액이 조작만으로 달라졌다(가정 #105 계열 · `ScenarioBuilder.BUDGET_STEP`).
    # 확정 예산(1억) 이후의 수치는 이 변경과 무관하다 — 아래 값들은 그대로다.
    "cards": {"보수": 8000, "적극": 10500},
    "n_entry": 382,
    "n_conditional": 636,
    # 2026-07-30 — 지도·목록 기본 표시를 진입 가능(적합+유의)으로 좁히면서 「상위 후보」의
    # 모집단이 바뀌었다. 조건부 적합은 분리 패널의 상위로 따로 잰다.
    "top3": [("방이동먹자골목", "FIT", 83), ("잠실 관광특구", "FIT", 83),
             ("화곡역 4번", "FIT", 82)],
    "top3_conditional": [("신림역 8번", 84), ("사당역 4번", 81),
                         ("구로디지털단지역", 80)],
    "t1": {"gap": 3869, "n_entry_before": 382, "n_entry_after": 1014, "n_sustain_after": 244},
    "area_code": "3120229",   # 방이동먹자골목 — 대본 6번의 역방향 판정 대상
    "n_products": 11,
}

# 인용 불가 문서(브라우저 인쇄된 KB 웹 페이지 4종)에서 온 상품 — 원문에 자격 요건 문단이
# 없어 인용을 비웠다. 이 4건 외에 인용이 비면 적재 연결이 끊긴 것이다 (가정 #73).
NON_QUOTABLE_PRODUCTS = {
    "KB소상공인 보증서대출", "KB소상공인 신용대출",
    "이자지원 보증서 대출", "소상공인 정책자금대출",
}

# 용어 컴플라이언스 (CLAUDE.md 절대 불변 원칙 3) — 화면에 나가는 문자열에 있으면 안 되는 말.
# "승인" 계열과 자금 권유형 술어. 심사 감점 직결이라 QA 가 매번 훑는다.
#
# **BE 검증기(`LlmResponses.BANNED_TERMS`)의 사본이며 어간까지만 적는다.** 구 목록이
# "추천드립" 만 담아 「추천해 드립니다」·「권유합니다」를 통과시켰다 — 활용형이 빠지면
# 목록이 있는데도 안 잡힌다. 어느 한쪽을 고치면 반드시 다른 쪽도 같이 고친다.
BANNED_WORDS = [
    "승인", "권장", "보장", "조달 가능", "심사역",
    "추천드", "추천합", "추천해", "권유", "권해",
    "대출을 받으세요",
]

# 상향 인사이트에 반드시 동반되는 고지 (CLAUDE.md 절대 불변 원칙 3). 언어화(refine)가 이 문구를
# 지우면 상향이 단독 노출되므로, 서버는 LLM 에 넘기기 전에 떼어 두고 통과한 문장 뒤에 다시 붙인다.
DISCLOSURE = "자격 요건 부합 여부만 확인된 것이며, 실제 한도와 심사 결과는 해당 기관이 정합니다."

# 수치 토큰 — BE 검증기(LlmResponses.NUMBER)와 같은 규칙으로 끊는다.
_NUMBER = re.compile(r"\d+(?:[.,]\d+)*")


def _numbers(text: str) -> set[str]:
    """문자열의 수치 **값** 집합. 천단위 쉼표는 지워 「1,014」와 「1014」를 같은 값으로 본다."""
    values = set()
    for token in _NUMBER.findall(text):
        try:
            values.add(str(float(token.replace(",", ""))))
        except ValueError:
            values.add(token)          # 날짜처럼 파싱 안 되는 토큰은 원문 그대로 비교
    return values

failures: list[str] = []
gaps: list[str] = []
notes: list[str] = []

# 알려진 미결선 — 실패로 세지 않되 매 실행에 드러낸다. 조용히 단언을 지우면 QA 는 통과하고
# 문제는 남으므로, 「통과」와 「미결선」을 구분해 보여주고 이슈 번호를 함께 인쇄한다.
KNOWN_GAPS: dict[str, str] = {}


# ── HTTP ────────────────────────────────────────────────────────────────────
def request(method: str, path: str, body: dict | None = None) -> tuple[int, dict]:
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as res:
            return res.status, json.loads(res.read() or b"{}")
    except urllib.error.HTTPError as exc:
        raw = exc.read()
        try:
            return exc.code, json.loads(raw or b"{}")
        except json.JSONDecodeError:
            return exc.code, {"_raw": raw.decode(errors="replace")}


def sse(path: str, limit_seconds: int = 20) -> list[tuple[str, dict]]:
    """SSE 스트림을 (event, data) 목록으로 모은다. 스트림 종료까지 읽는다."""
    events: list[tuple[str, dict]] = []
    name = None
    deadline = time.time() + limit_seconds
    with urllib.request.urlopen(BASE + path, timeout=TIMEOUT) as res:
        for raw in res:
            if time.time() > deadline:
                break
            line = raw.decode().rstrip("\n")
            if line.startswith("event:"):
                name = line[6:].strip()
            elif line.startswith("data:"):
                payload = line[5:].strip()
                try:
                    events.append((name or "message", json.loads(payload)))
                except json.JSONDecodeError:
                    events.append((name or "message", {"_raw": payload}))
    return events


def new_session(profile: dict = None) -> str:
    status, body = request("POST", "/api/diagnose", profile or PROFILE_CAFE)
    assert status == 200, f"diagnose {status} {body}"
    return body["session_id"]


# ── 단언 ────────────────────────────────────────────────────────────────────
def check(scenario: str, condition: bool, detail: str, gap: str | None = None) -> bool:
    if not condition:
        if gap:
            gaps.append(f"{scenario}: {detail} — {KNOWN_GAPS[gap]}")
        else:
            failures.append(f"{scenario}: {detail}")
    return condition


def _without_quotes(payload):
    """`source_quote` 를 들어낸 사본.

    인용문은 **공고 원문 그대로**이며 서버가 한 글자도 고치지 않는 것이 §5-4 의 전제다.
    거기 들어 있는 「승인」·「보장」은 우리가 쓴 말이 아니라 기관이 쓴 말이라 용어 규칙의
    대상이 아니고, 스윕에 걸린다고 고치면 그 순간 「인용은 검색이지 생성이 아니다」가
    무너진다 (가정 #31 도 같은 판단). 실제로 F-009 의 인용에는
    「국민기초생활보장법」이라는 법령명이 들어 있다.
    """
    if isinstance(payload, dict):
        return {k: _without_quotes(v) for k, v in payload.items() if k != "source_quote"}
    if isinstance(payload, list):
        return [_without_quotes(v) for v in payload]
    return payload


def scan_terms(scenario: str, payload) -> None:
    """응답 전체를 훑어 금지 표현을 찾는다 — 인용문을 뺀 어느 필드에 섞이든 잡는다."""
    text = json.dumps(_without_quotes(payload), ensure_ascii=False)
    for word in BANNED_WORDS:
        check(scenario, word not in text, f"금지 표현 '{word}' 노출")


# ── 정상 5 ──────────────────────────────────────────────────────────────────
def n1_diagnose() -> None:
    status, body = request("POST", "/api/diagnose", PROFILE_CAFE)
    check("N1", status == 200, f"HTTP {status}")
    check("N1", bool(body.get("session_id")), "session_id 없음")
    profile = body.get("parsed_profile", {})
    check("N1", profile.get("parse_source") in {"llm", "form_only"},
          f"parse_source={profile.get('parse_source')}")
    notes.append(f"N1 parse_source={profile.get('parse_source')}")
    scan_terms("N1", body)


def n2_scenarios() -> None:
    sid = new_session()
    events = sse(f"/api/scenarios/{sid}")
    names = [n for n, _ in events]
    check("N2", names.count("scenario") == 2, f"scenario 이벤트 {names.count('scenario')}건 (2 기대)")
    check("N2", names[-1] == "done", f"마지막 이벤트 {names[-1]!r} (done 기대)")
    cards = [d for n, d in events if n == "scenario"]
    check("N2", [c.get("label") for c in cards] == ["보수", "적극"],
          f"라벨 순서 {[c.get('label') for c in cards]}")
    for card in cards:
        lo, hi, cur = card["budget_min"], card["budget_max"], card["budget"]
        check("N2", lo <= cur <= hi, f"{card['label']} budget {cur} ∉ [{lo}, {hi}]")
        total = sum(p["amount_max"] for p in card.get("products", []))
        check("N2", hi == lo + total, f"{card['label']} budget_max {hi} ≠ {lo}+{total}")
        for product in card.get("products", []):
            check("N2", "rate_type" in product, f"{product['name']} rate_type 누락(항상 필수)")
            if product.get("rate") is None:
                check("N2", "rate" not in product, "rate=null 이 생략되지 않고 실렸다")
    quoted = [p for c in cards for p in c.get("products", []) if p.get("source_quote")]
    notes.append(f"N2 카드 상품 인용 {len(quoted)}건")
    scan_terms("N2", [dict(e[1]) for e in events])


def n3_budget_preview() -> None:
    sid = new_session()
    sse(f"/api/scenarios/{sid}")
    counts = []
    for budget in (6000, 9000, 15000):
        status, body = request("POST", f"/api/budget/{sid}",
                               {"confirmed_budget": budget,
                                "composition": [{"type": "equity", "amount": 5000}]})
        check("N3", status == 200, f"budget {budget} → HTTP {status}")
        preview = body.get("preview", {})
        counts.append(preview.get("area_count"))
        if preview.get("area_count") == 0:
            check("N3", "rent_range" not in preview,
                  "area_count=0 인데 rent_range 가 실렸다 (계약: 생략)")
    check("N3", counts == sorted(counts),
          f"예산을 올렸는데 진입 후보 수가 줄었다 {counts} — N(B) 단조 위반")
    notes.append(f"N3 진입 후보 수 6000/9000/15000 → {counts}")


def n4_recommend() -> None:
    sid = new_session()
    sse(f"/api/scenarios/{sid}")
    request("POST", f"/api/budget/{sid}",
            {"confirmed_budget": 9000, "composition": [{"type": "equity", "amount": 5000}]})
    status, body = request("GET", f"/api/recommend/{sid}")
    check("N4", status == 200, f"HTTP {status}")
    areas = body.get("areas", [])
    check("N4", len(areas) > 0, "추천 후보 0곳")
    check("N4", bool(body.get("data_as_of")), "data_as_of 누락 (기준일 상시 표기)")
    scores = [a["score"] for a in areas]
    check("N4", scores == sorted(scores, reverse=True), "score 내림차순 정렬 아님")
    for area in areas:
        check("N4", area["verdict"] in VERDICTS, f"판정 외 값 {area['verdict']}")
        check("N4", 33.0 < area["lat"] < 39.0 and 124.0 < area["lng"] < 132.0,
              f"{area['name']} 좌표가 WGS84 한국 범위 밖 ({area['lat']}, {area['lng']})")
        check("N4", area["cost"]["ex_premium"][0] <= area["cost"]["incl_premium"][0],
              f"{area['name']} 권리금 제외 비용이 포함 비용보다 크다")
    review = body.get("risk_review", {})
    check("N4", review.get("applied") is not review.get("skipped"),
          f"applied={review.get('applied')} skipped={review.get('skipped')} — 두 플래그가 모순이다")
    check("N4", bool(review.get("objection_text")), "objection_text 가 비었다")
    notes.append(f"N4 후보 {len(areas)}곳 · 검증 applied={review.get('applied')} "
                 f"skipped={review.get('skipped')}")
    scan_terms("N4", body)


def n5_check_area_quote() -> None:
    sid = new_session()
    sse(f"/api/scenarios/{sid}")
    request("POST", f"/api/budget/{sid}",
            {"confirmed_budget": 9000, "composition": [{"type": "equity", "amount": 5000}]})
    _, rec = request("GET", f"/api/recommend/{sid}")
    area_code = rec["areas"][0]["area_code"]
    status, body = request("POST", f"/api/check-area/{sid}", {"area_code": area_code})
    check("N5", status == 200, f"HTTP {status}")
    check("N5", body.get("verdict") in VERDICTS, f"판정 외 값 {body.get('verdict')}")
    products = body.get("matching_products", [])
    check("N5", len(products) > 2, f"자격 부합 상품 {len(products)}건 — 실테이블 결선 의심")
    limits = [p["amount_max"] for p in products]
    check("N5", limits == sorted(limits, reverse=True), "amount_max 내림차순 정렬 아님")
    # 인용이 빈 상품은 **인용 불가 문서**(브라우저 인쇄된 KB 웹 페이지 4종)뿐이어야 한다.
    # 그 문서에는 자격 요건 문단이 원문에 없어 인용을 비웠다 — 전건 보유를 기대하던 구 단언은
    # 「내비게이션을 자격 근거로 실어야 통과」하는 잘못된 게이트였다 (AI 리뷰 #4 · 가정 #73).
    quoted = [p for p in products if p.get("source_quote")]
    unquoted = [p["name"] for p in products if not p.get("source_quote")]
    check("N5", set(unquoted) <= NON_QUOTABLE_PRODUCTS,
          f"인용 없는 상품에 인쇄본 4종 외가 섞였다: {sorted(set(unquoted) - NON_QUOTABLE_PRODUCTS)}")
    check("N5", len(quoted) > 0, "인용이 하나도 없다 — 적재 연결 의심")
    for product in quoted:
        quote = product["source_quote"]
        for field in ("text", "org", "doc", "date"):
            check("N5", bool(quote.get(field)), f"{product['name']} source_quote.{field} 비었음")
    if quoted:
        longest = max(len(p["source_quote"]["text"]) for p in quoted)
        notes.append(f"N5 상품 {len(products)}건 · 인용 {len(quoted)}건 · 최장 {longest}자")
    scan_terms("N5", body)


# ── 경계 3 ──────────────────────────────────────────────────────────────────
def b1_budget_too_small() -> None:
    sid = new_session()
    sse(f"/api/scenarios/{sid}")
    request("POST", f"/api/budget/{sid}",
            {"confirmed_budget": 100, "composition": [{"type": "equity", "amount": 100}]})
    _, body = request("POST", f"/api/check-area/{sid}", {"area_code": "3001491"})
    check("B1", body.get("verdict") in {"OUT_OF_SCOPE", "CAUTION"},
          f"예산 100만원인데 판정이 {body.get('verdict')}")
    check("B1", (body.get("gap_amount") or 0) > 0,
          f"부족분이 {body.get('gap_amount')} — 양수여야 한다")
    notes.append(f"B1 예산 100 → {body.get('verdict')} · 부족분 {body.get('gap_amount')}")


def b2_age_boundary() -> None:
    def products_for(age: int) -> set[str]:
        profile = json.loads(json.dumps(PROFILE_CAFE))
        profile["form"]["age"] = age
        sid = new_session(profile)
        _, body = request("POST", f"/api/check-area/{sid}", {"area_code": "3001491"})
        return {p["name"] for p in body.get("matching_products", [])}

    at_39, at_40 = products_for(39), products_for(40)
    dropped = at_39 - at_40
    check("B2", len(dropped) >= 1,
          "39세 → 40세에서 빠지는 상품이 없다 (max_age=39 상품이 걸러지지 않음)")
    check("B2", at_40 < at_39, "40세 상품 집합이 39세의 부분집합이 아니다")
    notes.append(f"B2 39세 {len(at_39)}건 → 40세 {len(at_40)}건 · 탈락 {sorted(dropped)}")


def b3_region_unknown() -> None:
    profile = json.loads(json.dumps(PROFILE_CAFE))
    profile["form"]["region_hint"] = ""
    sid = new_session(profile)
    _, body = request("POST", f"/api/check-area/{sid}", {"area_code": "3001491"})
    named = {p["name"] for p in body.get("matching_products", [])}
    _, seoul = request("POST", f"/api/check-area/{new_session()}", {"area_code": "3001491"})
    seoul_named = {p["name"] for p in seoul.get("matching_products", [])}
    check("B3", len(named) < len(seoul_named),
          f"지역 미상 {len(named)}건 = 서울 {len(seoul_named)}건 — 지역제한 상품이 배제되지 않았다")
    notes.append(f"B3 지역 미상 {len(named)}건 vs 서울 {len(seoul_named)}건")


# ── 장애 2 ──────────────────────────────────────────────────────────────────
def f1_error_contract() -> None:
    cases = [
        ("없는 세션", "POST", "/api/check-area/00000000-0000-0000-0000-000000000000",
         {"area_code": "3001491"}),
        ("없는 상권", "POST", f"/api/check-area/{new_session()}", {"area_code": "NOPE-9999"}),
        ("깨진 본문", "POST", "/api/diagnose", {"form": {"age": "서른둘"}}),
    ]
    for label, method, path, body in cases:
        status, payload = request(method, path, body)
        check("F1", 400 <= status < 500,
              f"{label} → HTTP {status} (4xx 기대, 5xx 는 내부 오류 노출)")
        check("F1", isinstance(payload.get("error"), dict) and payload["error"].get("code"),
              f"{label} → 오류 규격 아님: {payload}")
        notes.append(f"F1 {label} → {status} {payload.get('error', {}).get('code')}")


def f2_no_llm() -> None:
    """무LLM 모드 — 키를 지운 스택에서 돌린다. 템플릿이 최종본으로 성립하는지가 요점."""
    sid = new_session()
    _, diag = request("POST", "/api/diagnose", PROFILE_CAFE)
    check("F2", diag["parsed_profile"]["parse_source"] == "form_only",
          f"parse_source={diag['parsed_profile']['parse_source']} — 키를 지운 스택이 맞는가?")
    sse(f"/api/scenarios/{sid}")
    request("POST", f"/api/budget/{sid}",
            {"confirmed_budget": 9000, "composition": [{"type": "equity", "amount": 5000}]})
    status, rec = request("GET", f"/api/recommend/{sid}")
    check("F2", status == 200, f"무LLM 추천 HTTP {status}")
    check("F2", all(a.get("reason_text") for a in rec.get("areas", [])),
          "근거 문장이 빈 후보가 있다 — 템플릿 폴백이 최종본이 되지 못했다")
    review = rec.get("risk_review", {})
    check("F2", review.get("skipped") is True,
          f"LLM 없는데 risk_review.skipped={review.get('skipped')} (true 기대)")
    check("F2", review.get("applied") is False,
          f"LLM 없는데 risk_review.applied={review.get('applied')} (false 기대)")
    check("F2", bool(review.get("objection_text")),
          "검증 생략인데 템플릿 문장도 비었다 — 화면이 빈 패널을 받는다")
    events = sse(f"/api/explore/{sid}?v=1")
    names = [n for n, _ in events]
    check("F2", "refine" not in names, "무LLM 인데 refine 이벤트가 왔다")
    check("F2", names and names[0] == "plan" and names[-1] == "done",
          f"탐색 이벤트 순서 이상: {names}")
    check("F2", all(d.get("headline") for n, d in events if n == "insight"),
          "인사이트 headline 이 비었다")
    scan_terms("F2", rec)
    notes.append(f"F2 무LLM 탐색 이벤트 {names}")


# ── SSE 버전 취소 ───────────────────────────────────────────────────────────
def s1_stale_version() -> None:
    """슬라이더 연타 = version 증가. 구 버전 요청은 이벤트를 하나도 보내지 않아야 한다."""
    sid = new_session()
    sse(f"/api/scenarios/{sid}")
    request("POST", f"/api/budget/{sid}",
            {"confirmed_budget": 9000, "composition": [{"type": "equity", "amount": 5000}]})
    fresh = sse(f"/api/explore/{sid}?v=5")
    check("S1", len(fresh) > 0, "최신 version 요청인데 이벤트가 없다")
    stale = sse(f"/api/explore/{sid}?v=3")
    check("S1", len(stale) == 0, f"구 version(3 < 5) 요청에 이벤트 {len(stale)}건이 왔다")
    notes.append(f"S1 v=5 {len(fresh)}건 · v=3(구 버전) {len(stale)}건")



# ── 실데이터 계약 게이트 (G1~G9) ─────────────────────────────────────────────
# 왜 별도인가: 위 시나리오는 **정상 동작 확인**에 최적화돼 있고(11건 전부 통과 상태에서도
# P0 결함 7건이 살아 있었다), 아래는 **계약 위반 탐지**가 목적이다. 성격이 다르므로 둘 다 둔다.
# 근인은 하나였다 — 자동 게이트가 전부 픽스처 프로파일(!db)에서 돌아 실데이터의 어려운 경우
# (변동금리 13건·매출 결측·상권 1,000곳 이상·경계 91개)를 한 번도 지나지 않았다.
def _insights_at(sid: str, budget: int) -> tuple[list[dict], dict]:
    request("POST", f"/api/budget/{sid}", {"confirmed_budget": budget,
                                           "composition": [{"type": "equity", "amount": budget}]})
    events = sse(f"/api/explore/{sid}?v=1")
    insights = [payload for name, payload in events if name == "insight"]
    plan = next((payload for name, payload in events if name == "plan"), {})
    return insights, plan


def g1_no_non_finite_tokens() -> None:
    """비유한 토큰은 계약 타입을 깬다 — number 자리에 문자열 "Infinity" 가 실렸다 (D-04)."""
    sid = new_session()
    request("POST", f"/api/budget/{sid}", {"confirmed_budget": 8000,
                                           "composition": [{"type": "equity", "amount": 5000}]})
    for path in (f"/api/recommend/{sid}", f"/api/scenarios/{sid}"):
        if path.endswith("scenarios/" + sid):
            raw = json.dumps([p for _, p in sse(path)], ensure_ascii=False)
        else:
            raw = json.dumps(request("GET", path)[1], ensure_ascii=False)
        for token in ("Infinity", "-Infinity", "NaN"):
            check("G1", f'"{token}"' not in raw and f": {token}" not in raw,
                  f"{path} 응답에 비유한 토큰 {token}")
    _, body = request("GET", f"/api/recommend/{sid}")
    bad = [a["area_code"] for a in body["areas"]
           if "burden_ratio" in a and not isinstance(a["burden_ratio"], (int, float))]
    check("G1", not bad, f"burden_ratio 가 number 가 아닌 상권 {bad[:3]}")
    omitted = [a for a in body["areas"] if "burden_ratio" not in a]
    notes.append(f"G1 burden_ratio 생략(매출 결측) {len(omitted)}건 / 후보 {len(body['areas'])}건")


def g2_delta_matches_entry_count() -> None:
    """delta 는 도구 계층 계산값이어야 한다 — 풀 크기를 넣던 자리 (D-01)."""
    sid = new_session()
    _, budget_body = request("POST", f"/api/budget/{sid}",
                             {"confirmed_budget": 8000,
                              "composition": [{"type": "equity", "amount": 5000}]})
    entry_count = budget_body["preview"]["area_count"]
    insights, _ = _insights_at(sid, 8000)
    for insight in insights:
        delta = insight.get("delta") or {}
        if insight["type"] in ("T1", "T5"):
            check("G2", delta.get("n_entry_before") == entry_count,
                  f"{insight['type']} n_entry_before {delta.get('n_entry_before')} != 진입 후보 {entry_count}")
            check("G2", delta.get("n_entry_after", 0) >= delta.get("n_entry_before", 0),
                  f"{insight['type']} after < before")


def g3_sustain_is_a_sustain_count() -> None:
    """지속 자리에 진입 수를 넣던 것을 막는다 (D-02).

    T1 과 T2 의 지속 수는 **다른 양이라 같을 필요가 없다** — T1 은 경계 예산에서 월 상환액 m 을
    반영한 값이고, T2 는 B₀ 에서 m=0 인 값이다. 대신 정의상 반드시 성립하는 두 가지를 잰다:
    ① 지속 ⊆ 진입 ② 같은 기준(B₀·m=0)을 쓰는 T2 와 T5 는 서로 일치.
    """
    sid = new_session()
    insights, _ = _insights_at(sid, 8000)
    by_type = {}
    for insight in insights:
        delta = insight.get("delta") or {}
        sustain, entry = delta.get("n_sustain_after"), delta.get("n_entry_after")
        by_type[insight["type"]] = sustain
        if sustain is not None and entry is not None:
            check("G3", sustain <= entry,
                  f"{insight['type']} 지속 {sustain} > 진입 {entry} (지속은 진입의 부분집합이다)")
        if delta.get("score_delta") is not None:
            check("G3", -10 <= delta["score_delta"] <= 10,
                  f"score_delta 가 등급 구간 범위를 벗어난다: {delta['score_delta']}")
    if by_type.get("T2") is not None and by_type.get("T5") is not None:
        check("G3", by_type["T2"] == by_type["T5"],
              f"같은 기준(B₀·m=0)인데 T2 {by_type['T2']} != T5 {by_type['T5']}")
    notes.append(f"G3 지속 후보 수: {by_type}")


def g4_variable_rate_branch() -> None:
    """변동금리 근거는 금액 대신 문구다 (계약 D8 · D-03)."""
    sid = new_session()
    insights, _ = _insights_at(sid, 8000)
    seen_variable = False
    for insight in insights:
        funding = insight.get("funding")
        if not funding:
            continue
        if funding.get("rate_type") == "variable":
            seen_variable = True
            check("G4", "marginal_payment" not in insight,
                  f"변동금리 근거에 marginal_payment 가 실렸다: {funding.get('name')}")
            check("G4", insight.get("marginal_payment_note"),
                  f"변동금리 근거에 marginal_payment_note 가 없다: {funding.get('name')}")
        else:
            check("G4", "marginal_payment_note" not in insight,
                  f"고정금리 근거에 note 가 실렸다: {funding.get('name')}")
        check("G4", funding.get("rate_type") in ("fixed", "variable"),
              "rate_type 은 항상 존재해야 한다 (FE 분기 키)")
    notes.append(f"G4 변동금리 근거 관측: {seen_variable}")


def g5_upside_never_alone() -> None:
    """상향 단독 노출 금지의 기계적 확인 (원칙 3 · D-01·D-05)."""
    sid = new_session()
    for budget in (6000, 8000, 8398, 9000):
        insights, plan = _insights_at(sid, budget)
        types = {i["type"] for i in insights}
        if types & {"T1", "T5"}:
            check("G5", "T2" in types,
                  f"예산 {budget}: 상향 {sorted(types)} 이 T2 없이 나갔다")
            for insight in insights:
                if insight["type"] in ("T1", "T5"):
                    check("G5", (insight.get("delta") or {}).get("n_sustain_after") is not None,
                          f"예산 {budget}: {insight['type']} 에 지속 후보 수가 없다")
        if not insights:
            check("G5", bool(plan.get("rationale")), f"예산 {budget}: 0건인데 사유 문장이 없다")
        notes.append(f"G5 예산 {budget}: 인사이트 {sorted(types) or '0건'}")


def g6_input_validation() -> None:
    """입력 오류는 발생 지점에서 400 이어야 한다 — 200 + 세션은 이후 전 경로를 500으로 만든다."""
    status, body = request("POST", "/api/diagnose", {})
    check("G6", status == 400, f"/diagnose {{}} → {status} (400 이어야 한다)")
    check("G6", (body.get("error") or {}).get("code") == "INVALID_REQUEST",
          f"오류 코드 규격 위반: {body}")
    bad_industry = {"form": {"age": 32, "capital": 5000, "industry": "cafee"}}
    status, _ = request("POST", "/api/diagnose", bad_industry)
    check("G6", status == 400, f"업종 오타 → {status} (400 이어야 한다)")
    sid = new_session()
    status, _ = request("POST", f"/api/budget/{sid}",
                        {"confirmed_budget": -9999, "composition": []})
    check("G6", status == 400, f"음수 예산 → {status} (400 이어야 한다)")

    # 이슈 #155 — 나이·월 투자 가능액이 어디서도 검증되지 않아, 음수·미기재가 「만 39세 이하」
    # 청년 전용 상품을 통과시켰다. 픽스처 회귀(InputValidationTest)와 짝이며, 이쪽은 실적재
    # 상품 26건에서 같은 경계를 확인한다.
    def diagnose_with(field: str, value) -> int:
        profile = json.loads(json.dumps(PROFILE_CAFE))
        if value is None:
            profile["form"].pop(field, None)
        else:
            profile["form"][field] = value
        return request("POST", "/api/diagnose", profile)[0]

    for bad_age in (-5, 0, 200):
        check("G6", diagnose_with("age", bad_age) == 400,
              f"age {bad_age} → 400 이어야 한다")
    check("G6", diagnose_with("monthly_investable", -100) == 400,
          "monthly_investable -100 → 400 이어야 한다")
    # 미기재는 막지 않는다 — 폼에서 선택 입력이다. 대신 나이 조건 상품이 빠져야 한다.
    check("G6", diagnose_with("age", None) == 200, "age 미기재는 200 이어야 한다")
    check("G6", diagnose_with("monthly_investable", 0) == 200,
          "monthly_investable 0 은 200 이어야 한다")

    no_age = json.loads(json.dumps(PROFILE_CAFE))
    no_age["form"].pop("age", None)
    _, absent = request("POST", f"/api/check-area/{new_session(no_age)}",
                        {"area_code": "3001491"})
    absent_named = {p["name"] for p in absent.get("matching_products", [])}
    _, known = request("POST", f"/api/check-area/{new_session()}", {"area_code": "3001491"})
    known_named = {p["name"] for p in known.get("matching_products", [])}
    check("G6", absent_named < known_named,
          f"나이 미기재 {len(absent_named)}건 = 만 32세 {len(known_named)}건 — "
          "나이 조건 상품이 미기재 사용자에게 편성됐다")
    notes.append(f"G6 나이 미기재 {len(absent_named)}건 / 만 32세 {len(known_named)}건 · "
                 f"탈락 {sorted(known_named - absent_named)}")

    # 이슈 #172 — Tomcat maxPostSize 는 JSON 에 걸리지 않아 2천만 자 본문이 200 으로 통과했다.
    oversized = json.loads(json.dumps(PROFILE_CAFE))
    oversized["free_text"] = "가" * 300_000          # UTF-8 3바이트 × 30만 ≈ 900 KB
    status, _ = request("POST", "/api/diagnose", oversized)
    check("G6", status == 413, f"상한 초과 본문 → {status} (413 이어야 한다)")


def g7_terminology() -> None:
    """용어 컴플라이언스 — 응답 전문 스캔 (심사 감점 직결)."""
    sid = new_session()
    request("POST", f"/api/budget/{sid}", {"confirmed_budget": 8000,
                                           "composition": [{"type": "equity", "amount": 5000}]})
    payloads = [request("GET", f"/api/recommend/{sid}")[1],
                [p for _, p in sse(f"/api/scenarios/{sid}")],
                [p for _, p in sse(f"/api/explore/{sid}?v=1")]]
    for payload in payloads:
        scan_terms("G7", payload)


def g8_objection_scope() -> None:
    """반박은 화면이 제시한 결과만 다룬다 — 범위 외 상권·없는 판정을 말하면 안 된다 (D-23·D-24)."""
    sid = new_session()
    request("POST", f"/api/budget/{sid}", {"confirmed_budget": 6000,
                                           "composition": [{"type": "equity", "amount": 5000}]})
    _, body = request("GET", f"/api/recommend/{sid}")
    review = body.get("risk_review") or {}
    text = review.get("objection_text", "")
    counts = {}
    for area in body["areas"]:
        counts[area["verdict"]] = counts.get(area["verdict"], 0) + 1
    check("G8", "OUT_OF_SCOPE" not in text, "반박문에 영문 판정 enum 이 노출됐다")
    for word in ("재검토", "분류된 이유"):
        check("G8", word not in text, f"반박이 판정을 다툰다: '{word}'")
    if counts.get("CAUTION", 0) == 0:
        check("G8", "유의 판정 유지" not in text,
              f"유의 0곳인데 유의 판정 유지를 말한다 (분포 {counts})")
    notes.append(f"G8 예산 6,000 판정 분포 {counts}")


def g10_refine_preserves_numbers() -> None:
    """언어화(refine)가 오면 **수치를 그대로 보존**해야 한다 (스펙 §0-1 역할 ②, BE-06 ③).

    서빙 경로에서 LLM 출력이 그대로 화면 문장이 되는 유일한 자리라, 여기서 수치가 바뀌면
    「모든 숫자는 결정적 계산이 만든다」가 그 자리에서 무너진다. 단위 테스트가 검증기를 보고
    이 게이트는 **실제로 나간 이벤트**를 본다.

    refine 은 계약상 **선택적 이벤트**다 — 무LLM 스택(CI 기본)에서는 오지 않는 것이 규격
    준수이고, 그 경우 이 게이트는 확인할 것이 없다는 사실만 남긴다.
    """
    sid = new_session()
    request("POST", f"/api/budget/{sid}",
            {"confirmed_budget": 10000,
             "composition": [{"type": "equity", "amount": 5000},
                             {"type": "policy_loan", "amount": 5000}]})
    events = sse(f"/api/explore/{sid}?v=1")
    names = [n for n, _ in events]
    insights = {d["insight_id"]: d for n, d in events if n == "insight"}
    refines = [d for n, d in events if n == "refine"]

    if not refines:
        notes.append(f"G10 refine 미송출 (LLM 부재이거나 전건 폐기) · 이벤트 {names}")
        return

    # 계약 §5 순서: plan → insight → refine → done
    check("G10", names.index("refine") > max(i for i, n in enumerate(names) if n == "insight"),
          f"refine 이 insight 보다 먼저 왔다: {names}")
    check("G10", names[-1] == "done", f"refine 뒤에 done 이 없다: {names}")

    for refine in refines:
        target = insights.get(refine["insight_id"])
        if not check("G10", target is not None,
                     f"refine 이 없는 인사이트를 지목한다: {refine['insight_id']}"):
            continue
        before, after = _numbers(target["headline"]), _numbers(refine["headline"])
        check("G10", after == before,
              f"{refine['insight_id']} 수치가 달라졌다 — 새로 생긴 {sorted(after - before)} · "
              f"빠진 {sorted(before - after)}")
        if DISCLOSURE in target["headline"]:
            check("G10", DISCLOSURE in refine["headline"],
                  f"{refine['insight_id']} 언어화본에서 고지 문구가 사라졌다")
        for word in BANNED_WORDS:
            check("G10", word not in refine["headline"],
                  f"{refine['insight_id']} 언어화본에 금지 표현 '{word}'")
    notes.append(f"G10 refine {len(refines)}건 · 수치 보존 확인 · 이벤트 {names}")


def g9_data_as_of() -> None:
    """모든 화면이 데이터 기준일을 표기할 수 있어야 한다 (불변 원칙 4 · D-25)."""
    sid = new_session()
    _, budget = request("POST", f"/api/budget/{sid}",
                        {"confirmed_budget": 8000,
                         "composition": [{"type": "equity", "amount": 5000}]})
    _, recommend = request("GET", f"/api/recommend/{sid}")
    check("G9", budget.get("data_as_of"), "/budget 응답에 data_as_of 가 없다")
    check("G9", budget.get("data_as_of") == recommend.get("data_as_of"),
          f"기준일 불일치: budget={budget.get('data_as_of')} recommend={recommend.get('data_as_of')}")


def d1_docent_script() -> None:
    """README 도슨트 대본의 수치가 기동 중인 스택에서 그대로 재현되는지 (이슈 #26·#152).

    **대본이 틀리면 심사위원은 자기가 뭘 잘못했다고 생각하고 멈춘다.** 실제로 배치 재적재
    (업종 대표면적 교정, 이슈 #152)로 도슨트 경로의 숫자가 통째로 이동해 「예산 8,000만 →
    342곳」이 같은 예산에서 6곳이 된 적이 있다. 코드가 아니라 **데이터가 바뀔 때** 조용히
    낡는 종류라 사람의 눈으로는 놓친다.

    기준값의 정본은 README 도슨트 가이드이며, 여기 상수는 그 사본이다 — 둘이 어긋나면
    이 게이트가 아니라 **README 를 먼저 고친다**. 대본 전체 기준값 표는
    `docs/tasks/CM-04_도슨트_테스트_프로토콜.md` §1-1.
    """
    budget = DOCENT["confirmed_budget"]
    sid = new_session()

    cards = {c["label"]: c for _, c in sse(f"/api/scenarios/{sid}") if "label" in c}
    for label, expected in DOCENT["cards"].items():
        actual = cards.get(label, {}).get("budget")
        check("D1", actual == expected,
              f"화면 2 {label} 카드 기본 예산 {actual} ≠ 대본 {expected}")

    _, preview = request("POST", f"/api/budget/{sid}",
                         {"confirmed_budget": budget,
                          "composition": [{"type": "equity", "amount": 5000},
                                          {"type": "policy_loan", "amount": budget - 5000}]})
    entry = preview.get("preview", {}).get("area_count")
    check("D1", entry == DOCENT["n_entry"],
          f"화면 3 진입 가능 후보 {entry}곳 ≠ 대본 {DOCENT['n_entry']}곳")

    _, body = request("GET", f"/api/recommend/{sid}")
    counts = {}
    for area in body["areas"]:
        counts[area["verdict"]] = counts.get(area["verdict"], 0) + 1
    check("D1", counts.get("CONDITIONAL") == DOCENT["n_conditional"],
          f"조건부 적합 {counts.get('CONDITIONAL')}곳 ≠ 대본 {DOCENT['n_conditional']}곳")
    check("D1", counts.get("FIT", 0) + counts.get("CAUTION", 0) == entry,
          "화면이 세는 진입 가능(적합+유의)과 /budget 프리뷰 수가 어긋난다")

    # 화면의 기본 표시는 **진입 가능(적합+유의)** 이므로 대본의 「상위 후보」도 그 안의
    # 상위여야 한다 (2026-07-30). 종전엔 범위 외만 걸러 조건부 적합까지 포함했는데, 그러면
    # 1순위가 신림역 8번(조건부 84점) — **무권리 매물 없이는 갈 수 없는 곳**이었다.
    entry = [a for a in body["areas"] if a["verdict"] in ("FIT", "CAUTION")]
    top = [(a["name"], a["verdict"], a["score"]) for a in entry[:3]]
    check("D1", top == DOCENT["top3"], f"상위 후보 {top} ≠ 대본 {DOCENT['top3']}")

    # 분리 패널의 조건부 적합 상위 3곳도 대본에 있다 — 화면이 두 목록을 나눠 보여 주므로
    # 게이트도 둘 다 확인해야 한 쪽만 조용히 낡는 일이 없다.
    cond = [a for a in body["areas"] if a["verdict"] == "CONDITIONAL"]
    top_cond = [(a["name"], a["score"]) for a in cond[:3]]
    check("D1", top_cond == DOCENT["top3_conditional"],
          f"조건부 적합 상위 {top_cond} ≠ 대본 {DOCENT['top3_conditional']}")

    insights = [d for n, d in sse(f"/api/explore/{sid}?v=1") if n == "insight"]
    t1 = next((i for i in insights if i["type"] == "T1"), None)
    if check("D1", t1 is not None, "T1 인사이트가 없다 — 대본의 ★ 지점이 재현되지 않는다"):
        delta, expected = t1["delta"], DOCENT["t1"]
        check("D1", t1["gap_amount"] == expected["gap"],
              f"T1 추가 확보액 {t1['gap_amount']} ≠ 대본 {expected['gap']}")
        for key in ("n_entry_before", "n_entry_after", "n_sustain_after"):
            check("D1", delta[key] == expected[key],
                  f"T1 {key} {delta[key]} ≠ 대본 {expected[key]}")

    _, area = request("POST", f"/api/check-area/{sid}", {"area_code": DOCENT["area_code"]})
    matching = area.get("matching_products", [])
    check("D1", len(matching) == DOCENT["n_products"],
          f"자격 부합 상품 {len(matching)}건 ≠ 대본 {DOCENT['n_products']}건")
    notes.append(f"D1 진입 {entry}곳 · 조건부 {counts.get('CONDITIONAL')}곳 · "
                 f"자격 부합 {len(matching)}건 · 상위 {top[0] if top else '—'}")


SCENARIOS = {
    "D1": ("도슨트 · README 대본 수치 정합", d1_docent_script),
    "N1": ("정상 · 진단 폼 + 자연어 파싱", n1_diagnose),
    "N2": ("정상 · 조달 시나리오 SSE 2장", n2_scenarios),
    "N3": ("정상 · 예산 확정 + 프리뷰 단조성", n3_budget_preview),
    "N4": ("정상 · 추천 목록 정렬·좌표·판정", n4_recommend),
    "N5": ("정상 · 역방향 판정 + 원문 인용", n5_check_area_quote),
    "B1": ("경계 · 예산 과소 → 범위 외 + 부족분", b1_budget_too_small),
    "B2": ("경계 · 연령 39/40 상품 집합 차이", b2_age_boundary),
    "B3": ("경계 · 지역 미상 → 지역제한 배제", b3_region_unknown),
    "F1": ("장애 · 오류 응답 규격 (4xx·code)", f1_error_contract),
    "F2": ("장애 · LLM 전면 차단 폴백", f2_no_llm),
    "S1": ("SSE · 구 version 취소", s1_stale_version),
    "G1": ("게이트 · 비유한값 0건 (타입)", g1_no_non_finite_tokens),
    "G2": ("게이트 · delta 가 도구 계층 계산값", g2_delta_matches_entry_count),
    "G3": ("게이트 · 지속 후보 수 정합·score_delta 범위", g3_sustain_is_a_sustain_count),
    "G4": ("게이트 · 변동금리 금액 대신 문구", g4_variable_rate_branch),
    "G5": ("게이트 · 상향 단독 노출 금지", g5_upside_never_alone),
    "G6": ("게이트 · 입력 검증 400", g6_input_validation),
    "G7": ("게이트 · 용어 컴플라이언스", g7_terminology),
    "G8": ("게이트 · 반박 사정거리", g8_objection_scope),
    "G9": ("게이트 · 데이터 기준일 표기", g9_data_as_of),
    "G10": ("게이트 · 언어화가 수치를 보존", g10_refine_preserves_numbers),
}

"""도슨트 대본(D1)을 계약 게이트에 넣는 이유 — 이 게이트가 막는 것은 코드 회귀가 아니라
**데이터가 바뀌었는데 대본이 안 바뀐 상태**다. 재적재는 배치 쪽 커밋 하나로 일어나는데
README 도슨트는 CM 문서라 같은 PR 에 들어오지 않는다. 실제로 그렇게 낡아 「예산 8,000만 →
342곳」이 6곳이 됐고, 아무 테스트도 울지 않았다 (이슈 #152·#26)."""
CONTRACT_GATE = ["D1", "G1", "G2", "G3", "G4", "G5", "G6", "G7", "G8", "G9", "G10"]


def main() -> int:
    global BASE
    parser = argparse.ArgumentParser()
    parser.add_argument("--only", nargs="*", default=None, help="실행할 시나리오 코드")
    parser.add_argument("--base", default=BASE)
    parser.add_argument("--contract-gate", action="store_true",
                        help="실데이터 계약 게이트(G1~G9)만 실행 — CI 용")
    args = parser.parse_args()
    BASE = args.base
    if args.contract_gate:
        selected = CONTRACT_GATE
    else:
        selected = args.only or [c for c in SCENARIOS if c != "F2"]

    print(f"BE-07 통합 QA — {BASE}\n")
    for code in selected:
        label, fn = SCENARIOS[code]
        before, gaps_before = len(failures), len(gaps)
        try:
            fn()
        except Exception as exc:                      # noqa: BLE001 — QA 는 예외도 결과다
            failures.append(f"{code}: 실행 중 예외 {type(exc).__name__}: {exc}")
        mark = "PASS" if len(failures) == before else "FAIL"
        if mark == "PASS" and len(gaps) > gaps_before:
            mark = "GAP "
        print(f"  [{mark}] {code}  {label}")

    if notes:
        print("\n실측:")
        for note in notes:
            print(f"  · {note}")
    if gaps:
        print(f"\n알려진 미결선 {len(gaps)}건 (실패로 세지 않음):")
        for gap in gaps:
            print(f"  ~ {gap}")
    if failures:
        print(f"\n실패 {len(failures)}건:")
        for failure in failures:
            print(f"  ✗ {failure}")
        return 1
    print(f"\n전 시나리오 통과 ({len(selected)}건)")
    return 0


if __name__ == "__main__":
    sys.exit(main())

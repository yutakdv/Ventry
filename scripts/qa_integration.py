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

# 용어 컴플라이언스 (CLAUDE.md 절대 불변 원칙 3) — 화면에 나가는 문자열에 있으면 안 되는 말.
# "승인" 계열과 자금 권유형 술어. 심사 감점 직결이라 QA 가 매번 훑는다.
BANNED_WORDS = ["승인", "권장", "추천드립", "조달 가능", "심사역", "대출을 받으세요"]

failures: list[str] = []
gaps: list[str] = []
notes: list[str] = []

# 알려진 미결선 — 실패로 세지 않되 매 실행에 드러낸다. 조용히 단언을 지우면 QA 는 통과하고
# 문제는 남으므로, 「통과」와 「미결선」을 구분해 보여주고 이슈 번호를 함께 인쇄한다.
KNOWN_GAPS = {
    "risk_review": "#96 리스크 검증 에이전트 LLM 미결선 — 템플릿이 applied=true 로 나간다",
}


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


def scan_terms(scenario: str, payload) -> None:
    """응답 전체를 훑어 금지 표현을 찾는다 — 어느 필드에 섞이든 잡는다."""
    text = json.dumps(payload, ensure_ascii=False)
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
    notes.append(f"N4 후보 {len(areas)}곳 · risk_review.applied="
                 f"{body.get('risk_review', {}).get('applied')}")
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
    quoted = [p for p in products if p.get("source_quote")]
    check("N5", len(quoted) == len(products),
          f"인용 {len(quoted)}/{len(products)} — 적재본은 전건 보유가 기대값")
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
          f"LLM 없는데 risk_review.skipped={review.get('skipped')} (true 기대)",
          gap="risk_review")
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


SCENARIOS = {
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
}


def main() -> int:
    global BASE
    parser = argparse.ArgumentParser()
    parser.add_argument("--only", nargs="*", default=None, help="실행할 시나리오 코드")
    parser.add_argument("--base", default=BASE)
    args = parser.parse_args()
    BASE = args.base
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

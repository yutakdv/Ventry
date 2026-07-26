"""서울신보 보증상품 **웹 1차 출처** 수집 (AI-06 후속 #72, 스펙 §5-4).

`funding_docs`(PDF 텍스트 추출)의 보완 모듈이다. 서울신보 6종은 raw PDF가 브라우저
프린트본이라 폰트 ToUnicode 누락으로 텍스트 레이어가 모지바케(`폀옠6먗퓏핯…`)다 —
`funding_docs.looks_garbled()` 가 이미 경고를 띄우던 그 문서들이다. OCR 대신 **원본 웹
페이지 본문**을 받는다: 인쇄본보다 오히려 1차 출처에 가깝고, 인용은 검색이지 생성이
아니라는 §5-4 원칙에도 정합한다.

**소진공은 여기서 다루지 않는다.** 2026 융자공고 PDF는 금리 수치를 홈페이지로 넘기지만,
그 「금리안내」 표는 `소진공_소상공인정책자금_지원사업안내.txt`(PDF 추출본)에 이미 전문이
들어와 있다 — ‘26년 3/4분기 기준금리 3.85% + 자금별 가산금리. 같은 내용을 별도 문서로
중복 수집하면 grounding 지표의 문서 모수만 흐려지므로 추가하지 않는다.

**왜 requests가 아니라 curl인가**: 이 호스트는 OpenSSL 3.x 기본 제안 스위트로는
`SSLV3_ALERT_HANDSHAKE_FAILURE` 를 반환한다(레거시 cipher만 수용). macOS `curl` 은 handshake
에 성공하므로, 검증을 끄지 않고(`--insecure` 미사용) 받을 수 있는 유일한 경로다.
"""
from __future__ import annotations

import html
import re
import subprocess

from batch.collect import collected
from batch.collect._common import INTERIM_DIR, logger, setup_logging

OUT_DIR = INTERIM_DIR / "funding_docs"

SEOULSHINBO = "https://www.seoulshinbo.co.kr/wbase/contents.do?mng_cd={code}"

# 문서명 → (URL, 본문 시작 앵커). 앵커 이후만 채택해 좌측 메뉴 트리를 배제한다.
# 문서명은 기존 raw PDF stem 과 동일하게 유지한다 —
# 상품↔문서 매핑(`reviewed.json` 의 `doc`)이 이 이름을 키로 쓴다.
PAGES: dict[str, tuple[str, str]] = {
    "서울신보_보증상품_ESG실천기업": (SEOULSHINBO.format(code="BUSI5389"), "보증상품"),
    "서울신보_보증상품_모바일앱자동심사": (SEOULSHINBO.format(code="BUSI2346"), "보증상품"),
    "서울신보_보증상품_미래성장산업": (SEOULSHINBO.format(code="BUSI4617"), "보증상품"),
    "서울신보_보증상품_일자리창출사회적기업": (SEOULSHINBO.format(code="BUSI4763"), "보증상품"),
    "서울신보_보증상품_장애인기업": (SEOULSHINBO.format(code="BUSI5388"), "보증상품"),
    "서울신보_보증상품_창업기업": (SEOULSHINBO.format(code="BUSI5337"), "보증상품"),
}

# 전역 내비게이션·푸터 상용구. 본문 앵커 뒤에도 남는 잔재라 줄 단위로 걷어낸다.
_NAV_NOISE = re.compile(
    r"^(홈페이지|재단스토리|주요업무|알림광장|정보공개|경영정보|소통참여|Quick Link|TOP"
    r"|바로가기|본문 바로가기|내비게이션 바로가기|푸터 바로가기|-->|열람하신 정보에.*"
    r"|매우만족|만족|보통|불만족|매우불만족|평가하기)$"
)


def fetch(url: str, *, timeout: int = 30) -> str:
    """정적 HTML 취득. 인증서 검증은 켠 채로 둔다 (`--insecure` 금지)."""
    result = subprocess.run(
        ["curl", "-sS", "--fail", "--max-time", str(timeout), url],
        capture_output=True, text=True, check=True, timeout=timeout + 10,
    )
    return result.stdout


def to_text(raw: str, anchor: str) -> str:
    """HTML → 본문 텍스트. **상품 표를 문단 단위로 뽑는다.**

    이 사이트의 본문은 전부 `<table>` 안에 있고 **표 하나가 상품 하나**다(첫 셀이 "○○ 목록").
    표만 뽑으면 좌측 메뉴·글자크기·프린트 같은 전역 크롬이 애초에 들어오지 않는다.

    표 사이를 **빈 줄**로 띄우는 것이 핵심이다. `load.finance._SPLIT` 은 빈 줄과 `- N -` 페이지
    표식만 문단 경계로 보는데, 구 구현이 빈 줄을 전부 버리고 한 줄씩 이어 붙인 탓에 문서 하나가
    통짜 청크 `#0` 하나가 되었고 — 후보가 하나뿐이니 `select_chunk` 도 그것을 고를 수밖에 없어 —
    **인용문이 사이트 내비게이션으로 시작**했다 (가정 #60, 스펙 §5-4 귀속 오류).

    표가 없으면 앵커 이후 전문으로 폴백한다. 사이트 개편으로 인용을 통째로 잃느니 넓게 받는다.
    """
    body = re.sub(r"(?is)<(script|style)\b.*?</\1>", " ", raw)
    tables = re.findall(r"(?is)<table\b.*?</table>", body)
    if tables:
        return "\n\n".join(_lines(table) for table in tables)
    text = _lines(body)
    idx = text.rfind(anchor)
    return text[idx:] if idx > 0 else text


def _lines(fragment: str) -> str:
    """태그를 개행으로 바꿔 표의 셀 경계를 보존하고, 빈 줄·전역 크롬 줄을 걷어낸다."""
    text = html.unescape(re.sub(r"<[^>]+>", "\n", fragment))
    kept = [ln.strip() for ln in text.splitlines()]
    return "\n".join(ln for ln in kept if ln and not _NAV_NOISE.match(ln))


def run(env: dict[str, str] | None = None, session: object | None = None) -> dict[str, int]:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    written: dict[str, int] = {}
    for name, (url, anchor) in PAGES.items():
        try:
            text = to_text(fetch(url), anchor)
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
            logger.warning("%s 수집 실패 (%s) — 기존 텍스트 유지", name, type(exc).__name__)
            continue
        (OUT_DIR / f"{name}.txt").write_text(text, encoding="utf-8")
        written[name] = len(text)
        logger.info("%s: %d자", name, len(text))
    # 수집일은 수집한 지금만 알 수 있다 — 실패해 건너뛴 문서는 옛 날짜가 보존된다 (#93)
    collected.record(list(written))
    return written


def main() -> None:
    setup_logging()
    run()


if __name__ == "__main__":
    main()

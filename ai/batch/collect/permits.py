"""인허가 CSV 적재·필터·업종 재분류 (AI-02, 스펙 §2 · 리스크 #18).

로컬 raw (localdata.go.kr 폐쇄로 data.go.kr 이관 — assumptions.md #3):
  인허가_일반음식점_서울.csv (15045016) · 인허가_휴게음식점_서울.csv (15006730)
  인코딩 CP949, 39컬럼. 좌표정보(X/Y)=EPSG:5174 → WGS84 변환은 preprocess(AI-04).

경쟁밀도는 폐업 제외, 영업중만 사용: 영업상태명 == '영업/정상'.
산출은 interim/ 로 저장(원본 raw 불변).

**업종 매핑은 한국표준산업분류(KSIC) I56 음식점 및 주점업 기준이다** (assumptions.md #20):
  cafe  = I5622 비알코올 음료점업  — 커피숍·다방·까페·전통찻집·떡카페·아이스크림
  food  = I561 음식점업 + I5621 주점업 — 한식·중식·일식·분식·호프/통닭·주점·제과점 등
  other = 비요식 또는 시설 내 매점  — 편의점·백화점·철도역구내·극장·관광호텔·공항 등
소진공 업종분류 개편(837→247)은 **소진공 상가업소 데이터의 코드 체계**이며, 이 파이프라인은
행안부 인허가 CSV의 `업태구분명`(한글 명칭)을 쓰므로 해당 코드 개편의 영향을 받지 않는다.
"""
from __future__ import annotations

from batch.collect._common import (
    INTERIM_DIR,
    RAW_DIR,
    load_env,
    logger,
    setup_logging,
)

SOURCES: dict[str, tuple[str, str]] = {
    "general": ("인허가_일반음식점_서울.csv", "일반음식점"),
    "rest": ("인허가_휴게음식점_서울.csv", "휴게음식점"),
}
OPEN_STATUS = "영업/정상"

# KSIC I5622 비알코올 음료점업. '까페'는 일반음식점 쪽 표기(1,230건)이므로 빠뜨리면 안 된다.
CAFE_TYPES = {"커피숍", "까페", "다방", "전통찻집", "떡카페", "아이스크림"}

# 비요식이거나 시설 내 매점이라 독립 점포 경쟁으로 볼 수 없는 업태.
# 키즈카페는 이름과 달리 실내놀이터업이라 카페 경쟁에서 뺀다.
NON_FOOD_TYPES = {
    "편의점", "백화점", "슈퍼마켓", "철도역구내", "극장", "관광호텔",
    "유원지", "공항", "고속도로", "키즈카페",
}


def classify_category(business_type: str) -> str:
    """업태구분명 → cafe / food / other (KSIC I56 기준, assumptions.md #20)."""
    name = "" if business_type is None else str(business_type).strip()  # 결측은 float nan
    if name in CAFE_TYPES:
        return "cafe"
    if name in NON_FOOD_TYPES:
        return "other"
    return "food"


def run(env: dict[str, str], session: object | None = None) -> None:
    import pandas as pd  # 배치 전용 의존(서빙 경로 아님)

    for suffix, (fname, desc) in SOURCES.items():
        path = RAW_DIR / fname
        if not path.exists():
            logger.warning("인허가 없음: %s — data.go.kr 수동 다운로드", path)
            continue
        frame = pd.read_csv(path, encoding="cp949", dtype=str, low_memory=False)
        live = frame[frame["영업상태명"] == OPEN_STATUS].copy()
        live["category"] = live["업태구분명"].map(classify_category)

        out = INTERIM_DIR / "permits" / f"{suffix}_live_classified.csv"
        out.parent.mkdir(parents=True, exist_ok=True)
        live.to_csv(out, index=False, encoding="utf-8-sig")
        logger.info(
            "%s: 총 %d → 영업중 %d (cafe=%d food=%d other=%d)",
            desc,
            len(frame),
            len(live),
            int((live["category"] == "cafe").sum()),
            int((live["category"] == "food").sum()),
            int((live["category"] == "other").sum()),
        )


def main() -> None:
    setup_logging()
    run(load_env())


if __name__ == "__main__":
    main()

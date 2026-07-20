"""인허가 CSV 적재·필터·업종 재분류 (AI-02, 스펙 §2 · 리스크 #18).

로컬 raw (localdata.go.kr 폐쇄로 data.go.kr 이관 — assumptions.md #3):
  인허가_일반음식점_서울.csv (15045016) · 인허가_휴게음식점_서울.csv (15006730)
  인코딩 CP949, 39컬럼. 좌표정보(X/Y)=EPSG:5174 → WGS84 변환은 preprocess(AI-04).

경쟁밀도는 폐업 제외, 영업중만 사용: 영업상태명 == '영업/정상'.
카페 = 휴게 中 커피숍·다방 / 음식점 = 일반음식점 전체 + 휴게 일부 (매핑 assumptions.md AI-02).
산출은 interim/ 로 저장(원본 raw 불변).
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
CAFE_TYPES = {"커피숍", "다방"}  # 휴게 中 카페(과자점 포함 여부는 AI-02 확정 후)
NON_FOOD_TYPES = {"편의점", "백화점", "슈퍼마켓", "기타휴게음식점"}  # 비요식 제외 후보


def classify_category(business_type: str) -> str:
    if business_type in CAFE_TYPES:
        return "cafe"
    if business_type in NON_FOOD_TYPES:
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

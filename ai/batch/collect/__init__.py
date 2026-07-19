"""원천 데이터 수집 (AI-01·02, 스펙 §2-0 소스 목록).

모듈 계획:
- seoul_commercial.py : 서울 상권분석 7종 (추정매출·길단위/상주/직장인구·집객시설·변화지표·영역)
- transit.py          : 역사마스터 좌표(불명확 시 t-data 지하철역_GEOM 폴백) + 승하차 인원
                        (환승역 정규화·합산 규칙은 docs/assumptions.md 등재)
- reb_rent.py         : 부동산원 임대료·전환율 (신규 API 15099345, 장애 시 파일 폴백)
                        + 상권 구획도 SHP + 권리금 연간치
- permits.py          : 인허가 시가정보 CSV (업종 재분류 247 기준 카페·음식점 매핑)
- funding_docs.py     : 정책자금·보증·대출 공개 문서 20~40건 (LLM 추출 → 전건 사람 검수)

원칙: 원본 raw/ 보존, 스크립트 재실행 가능 (스펙 §2-2).
"""


def main() -> None:
    raise SystemExit("collect: AI-01·02 (D1~2)에서 구현 예정 — docs/TASKS.md 참고")


if __name__ == "__main__":
    main()

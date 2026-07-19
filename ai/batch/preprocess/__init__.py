"""전처리·산출 테이블 생성 (AI-04·05, 스펙 §3-1·§4).

공간 조인 3단계 (§3-1):
  [1] 인허가 좌표(WGS84) → 상권 폴리곤(EPSG:5181→WGS84)  = 경쟁밀도
  [2] 구역 중심점 → 부동산원 구획(SHP)                    = 임대료 할당
      실패 시 자치구 평균 + fallback_flag
  [3] 중심점 → 최근접 지하철역(sjoin_nearest)             = 거리 d + 승하차 V
      접근성 성분 = V × exp(−d/500m), 실패 시 0 + 플래그

산출 (§4): 초기비용 4블록(권리금 이중 표기) / 이중 필터 분자·분모 /
점수화 w1~w5(서울 전체 백분위 정규화) / 정렬 인덱스.
검증 케이스 3종 통과 로그 필수. 모든 파라미터는 docs/assumptions.md 등재.
"""


def main() -> None:
    raise SystemExit("preprocess: AI-04·05 (D4~6)에서 구현 예정 — docs/TASKS.md 참고")


if __name__ == "__main__":
    main()

"""PostgreSQL 적재 + 덤프 내보내기 (AI-03·05·06).

- DDL·정산 테이블·금융상품 구조화(+원문 청크) 적재
- pg_dump 산출물을 db/init/ 에 배치 → compose 최초 기동 시 자동 적재 (스펙 §8)
- 스키마 변경 시 BE 사전 공지 (CONTRIBUTING §6)
"""


def main() -> None:
    raise SystemExit("load: AI-03 (D3)에서 구현 예정 — docs/TASKS.md 참고")


if __name__ == "__main__":
    main()

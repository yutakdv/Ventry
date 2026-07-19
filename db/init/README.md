# db/init — 사전 적재 덤프

`docker compose up` 최초 기동 시 이 디렉토리의 `*.sql`이 **사전순으로 자동 실행**된다
(PostgreSQL docker-entrypoint-initdb.d). 배치(Python)는 compose 실행 경로에 포함되지
않으므로(스펙 §8), AI 담당이 배치 산출물을 덤프로 내보내 여기에 커밋한다.

권장 파일 순서:

```
01_schema.sql        # DDL (AI-03, D3)
02_mock_data.sql     # 목 데이터 (D3 체크포인트 1용 — 실데이터 덤프 후 삭제)
10_data_core.sql     # 상권·매출·인구·임대료·점수·비용 (AI-05, D6)
20_finance.sql       # 금융상품 구조화 + 원문 청크 (AI-06, D6)
```

주의:
- 볼륨이 이미 생성된 뒤에는 재실행되지 않는다. 스키마 변경 시 `docker compose down -v` 후 재기동.
- 덤프 교체 시 BE에 사전 공지 (CONTRIBUTING §6).

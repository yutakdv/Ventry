"""Ventry 배치 파이프라인 (스펙 §2~§4).

collect  → 원천 수집 (raw/ 보존, 재실행 가능)
preprocess → 좌표계 통일·공간 조인 3단계·산출 테이블 생성
load     → PostgreSQL 적재 + db/init/ 덤프 내보내기
"""

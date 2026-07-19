"""AI 품질 평가 하네스 (스펙 §12 — 서비스 기능 아님, 오프라인 전용).

`make eval` 1회로 기술설명서 부록 1·2의 전 지표를 재산출한다.
기존 정산 테이블·검수 산출물을 **읽기만** 하며 서비스 파이프라인·DB 스키마 불변.

스위트 (§12-1):
- matching    : eligibility_filter 판정 vs 골드 라벨 P/R (가상 프로필 12~20종)
- extraction  : LLM 구조화 필드 vs 사람 검수본 일치율 (추가 라벨링 0)
- grounding   : source_quote ↔ 벡터DB 원문 청크 일치율
- sensitivity : 가중치 ±20%·θ 변동 시 상위 3곳 순위 유지율
- model       : LightGBM+SHAP 설계 교차 검증 (§12-2 프로토콜 고정 · §12-3 게이트)

산출: eval/out/metrics.json + 차트 PNG → 부록 1·2.
게이트(§12-3)는 결과 확인 전 사전 등재 — 미달 시 부록 2 삭제·민감도 단독 유지(리스크 #19).
"""

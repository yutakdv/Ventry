# Ventry AI 배치·평가 (스펙 §12-1)
# 평가 하네스는 기존 정산 테이블·검수 산출물을 "읽기만" 한다 — 서비스 파이프라인 불변.

.PHONY: help eval eval-matching eval-extraction eval-grounding eval-sensitivity eval-model \
        collect preprocess load

help:
	@echo "Ventry ai/ 타겟:"
	@echo "  make eval               통합 평가 (부록 1·2 전 지표 재산출)"
	@echo "  make eval-matching      자격 매칭 Precision/Recall (골드셋)"
	@echo "  make eval-extraction    정책자금 LLM 추출 정확도 (검수본 대조)"
	@echo "  make eval-grounding     source_quote 근거 충실도 (원문 일치)"
	@echo "  make eval-sensitivity   민감도 분석 (가중치 ±20% · θ 변동)"
	@echo "  make eval-model         LightGBM+SHAP 설계 교차 검증 (§12-2, 게이트 §12-3)"
	@echo "  make collect|preprocess|load   배치 파이프라인 (AI-02~05)"

eval: eval-matching eval-extraction eval-grounding eval-sensitivity eval-model
	python -m eval.run --suite report

eval-matching:
	python -m eval.run --suite matching

eval-extraction:
	python -m eval.run --suite extraction

eval-grounding:
	python -m eval.run --suite grounding

eval-sensitivity:
	python -m eval.run --suite sensitivity

eval-model:
	python -m eval.run --suite model

collect:
	python -m batch.collect

preprocess:
	python -m batch.preprocess

load:
	python -m batch.load

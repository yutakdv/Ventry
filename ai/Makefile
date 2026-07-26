# Ventry AI 배치·평가 (스펙 §12-1)
# 평가 하네스는 기존 정산 테이블·검수 산출물을 "읽기만" 한다 — 서비스 파이프라인 불변.
#
# PYTHON: 기본 python3 (macOS·최신 배포판에는 `python` 심볼릭이 없다).
# venv 사용 시 `make eval PYTHON=.venv/bin/python` 또는 venv 활성화 후 그대로 실행.
PYTHON ?= python3

.PHONY: help eval eval-matching eval-extraction eval-grounding eval-sensitivity eval-model \
        collect preprocess load

help:
	@echo "Ventry ai/ 타겟:"
	@echo "  make eval               통합 평가 (부록 1·2 전 지표 재산출)"
	@echo "  make eval-matching      자격 매칭 P/R — BE 단위·통합 테스트 소관(안내만 출력)"
	@echo "  make eval-extraction    정책자금 LLM 추출 정확도 (검수본 대조)"
	@echo "  make eval-grounding     source_quote 근거 충실도 (원문 일치)"
	@echo "  make eval-sensitivity   민감도 분석 (가중치 ±20% · θ 변동)"
	@echo "  make eval-model         LightGBM+SHAP 설계 교차 검증 (§12-2, 게이트 §12-3)"
	@echo "  make collect|preprocess|load   배치 파이프라인 (AI-02~05)"

# report 스위트가 내부에서 전 스위트를 실행한다 — 앞에 개별 타깃을 나열하면 LightGBM 학습·SHAP 이
# 두 번 돌아 심사위원 재현 시간이 2배가 된다 (리뷰 #9). 개별 타깃은 아래에 그대로 남아 있다.
eval:
	$(PYTHON) -m eval.run --suite report

eval-matching:
	$(PYTHON) -m eval.run --suite matching

eval-extraction:
	$(PYTHON) -m eval.run --suite extraction

eval-grounding:
	$(PYTHON) -m eval.run --suite grounding

eval-sensitivity:
	$(PYTHON) -m eval.run --suite sensitivity

eval-model:
	$(PYTHON) -m eval.run --suite model

collect:
	$(PYTHON) -m batch.collect

preprocess:
	$(PYTHON) -m batch.preprocess

# core 만 돌리면 20_finance.sql 이 옛 상태로 남는다 — 적재는 두 덤프가 한 벌이다 (리뷰 #10).
load:
	$(PYTHON) -m batch.load core
	$(PYTHON) -m batch.load finance

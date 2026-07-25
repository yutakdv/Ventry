# BE 인수인계 — 현재 상태와 다음 순서

> 작성: 2026-07-21 (D2 종료 시점, CP1 직전) · 대상: BE를 이어받는 팀원
> 총괄 일정·의존은 [TASKS.md](TASKS.md), 체크리스트는 [tasks/BACKEND.md](tasks/BACKEND.md),
> 스키마는 [API_CONTRACT.md](API_CONTRACT.md). **이 문서는 "지금 무엇이 진짜로 도는가"만 다룬다.**

---

## 0. 한 줄 요약

**엔드포인트 6개가 전부 응답하지만, 그중 계산이 실제로 도는 건 3개다.**
나머지 3개는 고정 목 데이터를 그대로 흘려보낸다. 앞으로의 작업은 대부분 **"목을 실계산으로
갈아끼우는 일"**이지, 새 엔드포인트를 만드는 일이 아니다.

```
D0 ──── D2(지금) ──── D3 ──── D5 ──── D7 ──── D8 ──── D10 ──── D14
        ▲             CP1     CP2     BE-04   CP3     기능동결  제출
        여기          계약동결 실데이터                 (8/2)
```

---

## 1. 지금 무엇이 도는가 — 엔드포인트별 진실

| 엔드포인트 | 상태 | 응답을 만드는 것 | 교체 태스크 |
|---|---|---|---|
| `POST /api/diagnose` | 🟡 **목 파싱** | 폼 값 반향 + 자유텍스트 **키워드 매칭 3종**(권리금/임대·월세/유동·손님) | BE-05 (LLM 실파싱) |
| `GET /api/scenarios` | 🔴 **전부 목** | `MockData.scenarios()` 고정 2장 | BE-04 (자격 통과 상품 조합) |
| `POST /api/budget` | 🟢 **실계산** | `Frontier.nEntry` + `CostCalculator` + 픽스처 | BE-02 (픽스처→DB) |
| `GET /api/recommend` | 🟢 **실계산** | engine 5종 전부 + 픽스처 | BE-02 (픽스처→DB) |
| `GET /api/explore` | 🔴 **전부 목** | `MockData.explorePlan/insightT1/insightT2/refineT1/exploreDone` 고정 송출 | BE-05 |
| `POST /api/check-area` | 🟢 **실계산** | `ReverseCheck` + `EligibilityFilter` + 픽스처 | BE-02 (픽스처→DB) |
| `GET /api/health` | 🟢 | — | — |

> 🟢도 **데이터는 픽스처**다. 계산 로직이 실제로 돈다는 뜻이지 실데이터라는 뜻이 아니다.

### 결정적 도구 계층(`engine/`) — 구현 vs 결선

| 도구 | 구현 | 단위 테스트 | 서빙 결선 |
|---|---|---|---|
| `EligibilityFilter` | ✅ 나이·예비창업자·업종·지역 | ✅ 8건 | ✅ check-area |
| `CostCalculator` | ✅ 4블록 합성·구간 유지 | ✅ | ✅ 3개 엔드포인트 |
| `ReverseCheck` | ✅ 판정 4단계 + 부담률 파생 | ✅ | ✅ recommend·check-area |
| `ScoreLookup` | ✅ | ✅ | 🟡 **`score()`만**. `transitInflux`·`percentile`·`demandScore`는 미결선 (배치 AI-05가 사전 계산하는 값이라 서빙에서 안 쓸 수도 있음 — BE-02에서 판단) |
| `Frontier` | ✅ **진입만** | ✅ 12건 | 🟡 **`nEntry`만**(budget 프리뷰). `nextBoundary`·`gap`·`bSafe`·`frontierPoints`는 **구현+테스트 완료인데 아무도 안 쓴다** |
| 지속 프론티어(필터 2′) | ❌ **미구현** | — | — |

> **BE-04에서 프론티어를 새로 짜지 마라.** 이미 있고 테스트도 통과한다. 결선만 하면 된다.
> `Frontier.frontierPoints()`는 계약 `explore.done.frontier_points`와 1:1로 맞춰뒀다.

### 아직 없는 인프라

| 항목 | 상태 | 언제 |
|---|---|---|
| JDBC·DataSource | `build.gradle`·`application.yml`에 **주석으로 대기 중** | BE-02 |
| Caffeine 캐시 | 의존성만 있고 **캐시 코드 0줄** | BE-02 |
| LLM 클라이언트 | **의존성조차 없음.** OpenAI 호출 코드 0줄 | BE-04(골격)·BE-05(실사용) |
| 리스크 검증 에이전트 | `ReasonTemplate`의 **고정 문자열**이 `risk_review`로 나감 | BE-05 |

### 받아만 두고 안 쓰는 필드 (BE-04 숙제)

| 필드 | 계약 | 계산 사용 |
|---|---|---|
| `monthly_investable` | ✅ 수신·세션 기록 | ❌ → 월 상환액 상한(`m ≤ 이 값`) |
| `collateral_available` | ✅ 수신·세션 기록 | ❌ → 담보·보증 요구 상품 커버 판정 |

계약 문서에 "현재 판정 미사용"이라고 명시돼 있다. **화면에서 "이 값이 결과를 바꿉니다"라고
말하면 안 되는 상태**이므로, BE-04에서 결선하면 FE에 반드시 알릴 것.

---

## 2. 코드 지도 — 어디를 건드리면 되나

```
com.ventry.api
├── engine/         ★ 결정적 도구 계층 (순수 함수 + 단위 테스트 필수, 스펙 §5-1)
│                     LLM·Spring 의존성 0. 여기 들어가는 건 전부 테스트가 있어야 한다.
├── serving/        ★ 오케스트레이션 — engine을 조합해 DTO를 만든다
│   ├── LocationService    recommend·budget 프리뷰·check-area
│   ├── DemoCandidates     후보 상권 픽스처 ← BE-02에서 리포지토리로 교체
│   ├── DemoProducts       금융상품 픽스처  ← BE-02/AI-06에서 교체
│   ├── ReasonTemplate     reason_text·risk_review 템플릿 ← BE-05에서 LLM refine 추가
│   └── SessionMapper      세션 → engine 입력 매핑
├── common/         SessionStore(인메모리·TTL 60분) · SseSupport · MockData · 오류 핸들러
├── diagnose/ scenario/ recommend/ explore/ checkarea/   각 엔드포인트 컨트롤러 + DTO
└── health/
```

**교체 지점은 `serving/`에 몰려 있다.** `DemoCandidates`·`DemoProducts`를 리포지토리로 바꾸면
`LocationService`는 그대로 두고 BE-02가 끝난다 — 그렇게 설계돼 있다.

---

## 3. 다음 순서

### 즉시 (D3, 7/22) — CP1

- [ ] **계약 동결 선언** (CM-02). FE·AI 검토 반영은 끝난 상태([DECISIONS.md](DECISIONS.md) §8~§11).
      동결 후 계약 변경은 **CONTRIBUTING §6**: 이슈 제안 → 3인 합의 → 계약 문서 PR → 각 영역 반영.
- [ ] 목 데이터 E2E 합동 점검 (화면1→진단→시나리오)

### BE-02 (D4~5) — DB 연동 + 캐시 · [#10](https://github.com/yutakdv/Ventry/issues/10)

**차단 요인: AI-03([#6](https://github.com/yutakdv/Ventry/issues/6)) DDL + `db/init/` 덤프.**
이게 없으면 착수 불가 — AI 담당과 D3에 스키마 확인부터 할 것. 지연되면 픽스처 유지로 무중단.

1. `build.gradle`·`application.yml`의 주석 2줄 해제
2. `DemoCandidates`·`DemoProducts` → 리포지토리 교체 (`LocationService`는 무변경 목표)
3. **탐색당 쿼리 1회 원칙** — 후보 행 일괄 조회 → 인메모리 계산. 시나리오별 N+1 금지 (expl §5)
4. Caffeine 캐시 (키: 업종·자치구, 기동 시 예열)
5. `data_as_of`를 메타 테이블에서 주입 (지금은 `MockData.DATA_AS_OF` 상수)

> **AI-05 산출 테이블에 요구한 것**: `환산임대료`·`월 추정매출`·`일평균 유동인구`를 **정규화 전
> 원값으로** 적재. 부담률은 컬럼으로 굽지 말 것 — BE가 분모÷분자로 파생한다(스펙 §4-2).

### BE-04 (D7) — 프론티어 결선 + 조달 검증 · [#15](https://github.com/yutakdv/Ventry/issues/15)

**차단 요인: AI-06([#13](https://github.com/yutakdv/Ventry/issues/13)) `exclusive_group` 구조화.** 없으면 `DemoProducts`로 선행 가능.

1. `Frontier`의 미결선 메서드 4종을 `/explore`에 연결 (**새로 짜지 말 것**)
2. 조달 검증 — 잔여 한도 원칙 → `exclusive_group` 제약 → 커버 불가 경계는 보고 제외 (expl §2-2)
3. `monthly_investable`·`collateral_available` 결선 + `assumptions.md` 등재
4. `/scenarios` 실계산 — 자격 통과 상품 조합으로 보수/적극 2장
5. **LLM 클라이언트 골격 선행** (D8 부하 분산): SDK·타임아웃 5s·세마포어·**키 부재 시 무LLM 모드**

### BE-05 (D8) — 탐색·검증 에이전트 · [#17](https://github.com/yutakdv/Ventry/issues/17) ★최대 부하

1. 확장 부담률 필터 2′ + **지속 프론티어(비단조)** — 이 프로젝트의 핵심. 자기자본만 구간에서
   `m=0 ⇒ 필터2=필터2′` 일관성 테스트 필수
2. 스코어링·컷 (`score = Q×F/C`, 보고 ≤3건, **0건도 유효**)
3. LLM 탐색 계획(plan) / 언어화(refine) / **템플릿 우선 — 장애 시 템플릿이 최종본**
4. `/explore` SSE 실구현 (version 취소) · 진단 LLM 파싱 · 리스크 검증 1왕복
5. **DoD = expl §8 데모 시나리오 재현** (진입 3→11, 지속 7, 갭 1,320)

### BE-06 (D9~10) P1 · [#18](https://github.com/yutakdv/Ventry/issues/18) → BE-07 (D11~12) QA · [#23](https://github.com/yutakdv/Ventry/issues/23)

BE-06은 여유 시에만 (원문 인용 → 근거문 캐시 → 개인화 순). 미구현 시 D10에 **문서 이월 처리**.
BE-07은 시나리오 10종 + **LLM 전면 차단 QA**(템플릿 폴백 경로 확인).

---

## 4. 함정 — 모르면 시간 날리는 것들

**① 데모 픽스처는 역산 설계다.**
`DemoCandidates`의 숫자는 "예산 8,000만·θ 0.15에서 expl §8 판정(🟢2·🟠1, 갭 1,320)이 재현되도록"
거꾸로 맞춘 값이다. 하나만 바꿔도 `LocationServiceTest`·`ApiFlowTest`의 판정과 갭이 깨진다.
BE-02에서 실데이터로 갈아탈 때 **이 테스트들도 함께 재설계**해야 한다.

**② 월 고정비와 임대료는 지금 정합하지 않다** ([assumptions.md](assumptions.md) #9).
데모 예산 8,000만을 재현하려면 월 고정비를 축소해야 하는데 임대료는 실측 수준이어야 해서
분리해뒀다. `monthlyFixedCost`는 API에 노출되지 않아 무해하지만 **BE-02에서 동시 해소할 것**.

**③ `burden_ratio`는 파생값이다.** `monthly_rent ÷ est_sales`. 상수로 저장하지 마라 (스펙 §4-2).

**④ snake_case는 Jackson 전역 설정이다.** DTO record는 camelCase로 두면 자동 변환된다.
`@JsonProperty`로 필드명을 직접 쓰지 말 것.

**⑤ `non_null` 직렬화 — null 필드는 응답에서 사라진다.** FE는 `undefined` 허용으로 파싱한다.
"null이면 필드 자체가 없다"가 계약 동작이다 (예: `preview.rent_range`, `source_quote`).

**⑥ version 규약을 SSE 안에서 매 이벤트 재확인해야 한다.**
`/recommend`와 `/explore`가 세션 version을 공유한다. `ExploreController.stale()`이 이벤트 송출
직전마다 비교하는 패턴을 유지할 것 — 슬라이더 연타 시 구 버전 응답이 새 응답을 덮어쓴다.

**⑦ SSE는 가상 스레드 전용 executor에서만.** 톰캣 워커에서 LLM을 기다리면 안 된다 (expl §5).
`SseSupport.run()` 경로를 벗어나지 말 것.

**⑧ 용어 컴플라이언스는 테스트가 지킨다.**
`LocationServiceTest`가 `reason_text`에 "추천"·"권장"이 없는지 검사한다. LLM 문장을 붙일 때
프롬프트 제약("입력 JSON에 없는 수치·상품 언급 금지" + 자문성 술어 금지)을 반드시 걸 것.

**⑨ LLM 장애 = 템플릿이 최종본.** 데모 무중단 원칙(expl §2-5). LLM은 **필수 경로에 없어야** 한다.

**⑩ 계약 파일은 3인 리뷰 대상이다.** `docs/API_CONTRACT.md`·`docker-compose.yaml`을 건드리는
PR은 CONTRIBUTING §2에 따라 3인 리뷰가 필수다.

---

## 5. 첫 30분 — 환경 세팅과 확인

```bash
git config core.hooksPath .githooks     # Claude attribution 차단 훅 (필수)
cp .env.example .env                    # OPENAI_API_KEY 채우기 (BE-04부터 필요)

cd backend && ./gradlew build           # 61건 그린이어야 정상
docker build ./backend                  # CI와 동일 검증
docker compose up --build               # 통합 — web :3000, api :8080
```

IntelliJ는 **2025.2 이상 + JDK 25**가 필요하다 (CONTRIBUTING §1-1).
IDE 빨간줄이 남아도 `./gradlew build`와 `docker build ./backend`가 그린이면 코드는 정상이다.

동작 확인 (데모 프로필 전 구간):

```bash
SID=$(curl -s -XPOST localhost:8080/api/diagnose -H 'Content-Type: application/json' -d '{
  "form":{"age":32,"capital":5000,"is_existing_business":false,
          "collateral_available":true,"monthly_investable":250,
          "industry":"cafe","region_hint":"서울 마포구"},
  "free_text":"권리금이 제일 걱정입니다"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["session_id"])')

curl -s -XPOST localhost:8080/api/budget/$SID -H 'Content-Type: application/json' \
  -d '{"confirmed_budget":8000,"composition":[{"type":"equity","amount":5000},
                                              {"type":"policy_loan","amount":3000}]}'
# → preview.area_count = 3

curl -s localhost:8080/api/recommend/$SID          # → 망원 FIT 75점 / 합정 FIT 71 / 홍대 CAUTION 70
curl -s -XPOST localhost:8080/api/check-area/$SID -H 'Content-Type: application/json' \
  -d '{"area_code":"A-9999"}'                      # → CONDITIONAL, gap_amount 1320

# ⚠️ SSE는 URL을 따옴표로 감쌀 것 — zsh가 `?`를 글롭으로 확장해 "no matches found"가 난다
curl -sN "localhost:8080/api/scenarios/$SID"       # → scenario×2 (budget_min/max 포함) → done
curl -sN "localhost:8080/api/explore/$SID?v=1"     # → plan(axis_labels) / insight×2 / refine / done
```

기대값 (2026-07-21 실행 확인):

| 호출 | 기대 결과 |
|---|---|
| `/budget` | `preview.area_count=3`, `rent_range=[198,456]`, `floating_range=[22800,38200]` |
| `/recommend` | `total_count=3`, `summary={avg_rent:309, avg_sales:2100}`, 망원 FIT·75 / 합정 FIT·71 / 홍대 CAUTION·70 |
| `/check-area` (A-9999) | `CONDITIONAL`, `gap_amount=1320`, 자격 부합 상품 2건 |
| `/scenarios` | 보수 `[5000,6500]` / 적극 `[5000,8000]` |
| `/explore` | `axis_labels={"A1":"예산","A4":"권리금 조건"}`, `current_budget=8000` |

이 값들이 그대로 나오면 인수 상태가 정상이다. 하나라도 다르면 픽스처가 손상된 것이다 (함정 ①).

---

## 6. 작업 규칙 (요약 — 전문은 CONTRIBUTING)

- **브랜치는 2단계다**: 토픽 →(로컬 병합)→ `backend` →(PR)→ `develop`.
  토픽에서 `develop`으로 직접 PR을 올리지 않는다.

  ```bash
  git checkout backend && git pull && git merge origin/develop   # 남의 작업 흡수
  git checkout -b be04-frontier                                  # 토픽 분기
  # …작업…
  git checkout backend && git merge be04-frontier && git push origin backend
  gh pr create --base develop --head backend                     # 여기만 PR
  ```

  - ⚠️ 토픽 이름을 `backend/xxx`로 지을 수 **없다**. `backend`가 이미 브랜치라 git이 거부한다
    (`cannot lock ref`). `<태스크ID>-<슬러그>` 형식을 쓸 것
- 커밋: `[BE] type: 요약 (태스크ID)` · **Claude attribution 라인 절대 금지**
- PR 제목에 태스크 ID, 본문에 `Closes #<이슈번호>` — **영역 → develop PR 본문**에 적어야
  자동 종료 잡이 발동한다 (토픽 → 영역 병합 커밋에 적으면 무시된다)
- 태스크가 여러 슬라이스로 갈리면 **서브이슈**: `[BE-04a] 요약`, 본문 첫 줄에 `부모: #15`
- 새 가정·폴백은 발생 즉시 [assumptions.md](assumptions.md)에 등재

---

## 7. 절대 지켜야 할 것 (심사 감점 직결)

1. **모든 숫자는 결정적 계산이 만든다.** LLM은 ⓐ탐색 축 계획 ⓑ언어화 ⓒ리스크 반박문만.
   LLM이 수치를 생성·재계산하는 경로를 만들면 안 된다.
2. **서빙 경로에 ML 모델 없음.** `lightgbm`·`shap`은 `ai/` 배치에만.
3. **판정 4단계**: 적합 / 조건부 적합 / 유의 / 범위 외 — "승인" 계열 단어 전면 금지.
   자금 관련 "권장/추천드립니다" 금지 → 정보 서술형.
4. **상향 인사이트 단독 노출 금지** — 지속 후보 수 + 하향 안전 마진 + 고지 문구 동반 필수.
5. **좌표 WGS84 / 금액 만원 단위 정수 / 데이터 기준일 상시 표기.**

---

## 부록 — 최근 커밋 이력

| 커밋 | 내용 |
|---|---|
| `496bea9` | BE-01a — API 계약 CP1 검토 반영 (FE 제안 6건), 부담률 파생 전환 |
| `763598e` | BE-03f — recommend·check-area 도구 계층 결선 + 데모 픽스처 |
| `827808f` | BE-03a~e — 결정적 도구 계층 5종 + 단위 테스트 |
| `64f17ed` | BE-01 — 목 API 6종 + SSE 골격 + 공통 계층 |

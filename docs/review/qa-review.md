# QA Review

작성 2026-07-29 (D-5) · 리뷰어: QA Lead
기준 커밋 `396b41f` + **작업 트리 미커밋 변경 포함** · 검증 방식: **기동 중인 스택에 실제 HTTP 요청**
(docker compose db/api/web, 실데이터 후보 1,059곳 · 금융상품 26건)

> 이 문서는 2회차다. 1회차(같은 날 18:10)는 테스트·CI·컴플라이언스의 **정적 검토**였고,
> 이번 회차는 **스택을 띄워 실제로 깨뜨려 본 동적 검토**다. 1회차 결론은 §부록에 보존한다.

---

# Executive Summary

**P0 없음. 서비스가 죽는 경로는 발견되지 않았다.** 정상 동선(진단 → 시나리오 → 예산 → 추천 →
탐색 → 역방향 판정)은 실데이터에서 전 구간 동작하고, 프로젝트 자체 계약 게이트 21건이 **LLM
활성/비활성 양쪽 모두** 통과한다.

동적 검증에서 **정상 응답으로 위장하던 결함 3건(P1)** 을 새로 찾아 **직접 수정**했다. 셋 다
단위 테스트·계약 게이트가 닿지 않던 자리다.

| ID | 결함 | 관측된 증상 | 상태 |
|---|---|---|---|
| **Q-01** | `capital` 상한 부재 → 정수 오버플로 | `GET /api/scenarios/{sid}` **500** | ✅ 수정 + 회귀 테스트 |
| **Q-02** | `?v=` 규격 검증 부재 | 빈 SSE 스트림 → **FE가 목(가짜) 인사이트로 대체** / 세션 탐색 영구 정지 | ✅ 수정 + 회귀 테스트 |
| **Q-03** | DB 장애 시 30초 행 | 모든 엔드포인트 **30.02초 후 500** | ✅ 3.02초로 단축 (근본 원인 중 `/health` 오보는 미해소) |

가장 심각한 잔여 항목은 **`/api/health` 가 거짓말을 한다**는 점이다. DB가 완전히 내려간
상태에서도 `{"status":"ok"}` 200을 돌려준다 — compose·CI 스모크가 이 값을 신뢰하므로 **깨진
스택이 정상으로 보고된다.** 계약이 응답 형태를 고정하고 있어 단독 수정하지 않고 조치안을 남긴다.

리뷰 중 작업 트리가 병행 수정되어(백엔드 리뷰 세션), 초기에 관측한 **4xx 오분류 4건**
(`?v=abc`·잘못된 메서드·잘못된 Content-Type → 전부 500)은 재빌드 후 400/405/415로 해소된 것을
확인했다. §Fixed-in-flight 참조.

---

# Critical Bugs

**없음.** 아래를 전부 시도했으나 서비스 중단·데이터 손실·권한 오류는 재현되지 않았다.

| 시도 | 결과 |
|---|---|
| SQL Injection (`area_code`, `region_hint`) | 파라미터 바인딩 — `'; DROP TABLE candidate_area; --` 가 값으로만 취급, 404/200 정상 |
| XSS 문자열 (`<script>alert(1)</script>`) | JSON 값으로 그대로 반향, 실행 경로 없음 |
| Path traversal (`..%2F..%2Fetc%2Fpasswd`) | 400 |
| 대용량 본문 (300KB) | 413 `PAYLOAD_TOO_LARGE` |
| SSE 동시 20 스트림 | 20/20 완료, 5.2초 |
| LLM 전면 장애 (잘못된 키) | 템플릿 폴백 · `applied=false, skipped=true` · 0.49~0.69초 |
| LLM 부재 (키 없음) | 계약 게이트 21건 전건 통과 |
| DB 복구 | 재기동 없이 자동 회복 200 |

---

# Major Bugs

## Q-01 (P1) `capital` 정수 오버플로 → `GET /api/scenarios/{sid}` 500 — ✅ 수정 완료

### Test Scenario
진단 폼 `capital`(자기자본, 만원)에 큰 값을 넣고 시나리오 SSE를 요청.

### Expected
규격을 벗어난 금액은 **세션을 만들기 전에** 400으로 거절 (`age`·음수 `capital` 과 동일한 취급).

### Actual
`POST /api/diagnose` 는 **200 + 세션 발급**, 이어지는 `GET /api/scenarios/{sid}` 가 **500**.

```
java.lang.IllegalArgumentException: 2147483647 > -2147473649
    at java.base/java.lang.Math.clamp(Unknown Source)
    at com.ventry.api.serving.ScenarioBuilder.card(ScenarioBuilder.java:142)
    at com.ventry.api.serving.ScenarioBuilder.build(ScenarioBuilder.java:84)
```

`budget_max = 자기자본 + 상품 한도` 가 `int` 에서 뒤집혀 음수가 되고,
`Math.clamp(value, min, max)` 가 `min > max` 로 터진다.

### 재현 방법
```bash
SID=$(curl -s -X POST localhost:8080/api/diagnose -H 'Content-Type: application/json' \
  -d '{"form":{"age":32,"capital":2147483647,"industry":"cafe","region_hint":"서울 마포구"}}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['session_id'])")
curl -s -w '\n[%{http_code}]\n' localhost:8080/api/scenarios/$SID
```

**재현 확률 100%.** 실측 경계는 `capital ≥ 2,147,473,648`.

### Risk
경계가 **선택된 상품의 `amount_max` 만큼 움직인다**(관측 시점 10,000). 즉 **금융상품 덤프를
다시 적재하면 경계도 조용히 이동한다** — 입력 검증만 고쳐서는 재발한다. 화면 2 진입이 통째로
막히고, FE는 SSE 오류를 목 폴백으로 처리하므로 사용자에게는 **가짜 시나리오 카드**가 보인다.

### Recommendation / 수정 내용
방어를 두 겹으로 뒀다.

1. **입력단** — `common/Amounts.java` 신설(상한 1억 만원 = 1조원), `DiagnoseController`(`capital`,
   `monthly_investable`) · `ScenarioController`(`confirmed_budget`) 에서 400으로 거절.
   기존 `AGE_MAX = 100` 과 같은 성격의 오타 차단선이다.
2. **산술** — `ScenarioBuilder.card` 의 `budgetMax` 누적을 `long` 으로 바꿔 상품 한도가 커져도
   뒤집히지 않게 했다.

**검증**
```
capital=100000000   diagnose[200] scenarios[200] "budget_max":100010000 "budget_max":100003000
capital=100000001   diagnose[400] {"error":{"code":"INVALID_REQUEST", ...}}
capital=2147483647  diagnose[400] {"error":{"code":"INVALID_REQUEST", ...}}
```

---

## Q-02 (P1) `?v=` 규격 검증 부재 → 빈 스트림이 **가짜 인사이트**가 되고, 세션 탐색이 영구 정지 — ✅ 수정 완료

### Test Scenario
`GET /api/explore/{sid}?v=` 에 음수·거대값을 넣고 이후 정상 요청의 동작을 확인.

### Expected
규격 밖 `v` 는 400. 정상 `v` 는 이후에도 계속 동작.

### Actual
두 방향 모두 **오류 없이 조용히 샜다.**

| 입력 | 관측 |
|---|---|
| `?v=-1` | 이벤트 **0건인 정상 종료 스트림** (세션 최신 0보다 작아 구 버전 취급) |
| `?v=9223372036854775807` | 정상. 그러나 **이후 `?v=1`·`?v=2` 가 전부 0건** — 그 세션의 탐색이 TTL 60분 동안 되살아나지 않음 |

`SessionStore.acceptVersion` 이 `Math.max` 로 단조 증가만 하고 내려오지 않기 때문이다.
`/api/recommend?v=` 도 같은 version 을 공유하므로 **추천 쪽으로 넣어도 탐색이 죽는다.**

### 재현 방법
```bash
curl -s "localhost:8080/api/explore/$SID?v=9223372036854775807" >/dev/null
curl -s "localhost:8080/api/explore/$SID?v=2" | grep -c '^event:'   # → 0
```
**재현 확률 100%.**

### Risk
빈 스트림이 사용자에게 **"아무 일도 없음"으로 도달하지 않는다.** FE `api/client.ts` 의
`getExplore` 는 이벤트 없이 닫힌 스트림을 `es.onerror` 로 받아 `markApiFallback()` 후
`mockExplore(...)` 를 돌린다 — 즉 **잘못된 입력이 지어낸 인사이트로 화면에 도달한다.**
「모든 숫자는 결정적 계산이 만든다」(불변 원칙 1)와 정면으로 어긋나는 도달 경로다.
(서버 측 빈 스트림은 실측, FE 측 폴백 경로는 코드 추적으로 확인 — 브라우저 실행 검증은 미수행.)

### Recommendation / 수정 내용
`SessionStore.acceptVersion` 에 범위 검증(`0 ≤ v ≤ 1,000,000`)을 넣어 400으로 거절.
두 컨트롤러가 공유하는 유일한 지점이라 한 곳으로 양쪽이 덮인다.

**⚠️ 한계를 분명히 한다.** 상한은 **피해 범위를 줄일 뿐 봉인하지 않는다** — 상한 안의 값
(예: `v=999999`)으로도 같은 독점이 가능하다. 완전한 해소는 **version 을 서버가 발급**하도록
바꾸는 것이고, 그것은 계약(§공통 규약) 변경이라 단독 진행하지 않았다.
다만 정상 FE는 version 을 되돌리지 않으므로 **정상 동선에서는 재현되지 않는다** — 세션 id를
아는 제3자만이 도달할 수 있는 경로다.

**검증**
```
v=-1        [400] {"error":{"code":"INVALID_REQUEST","message":"v 는 0~1000000 범위..."}}
v=거대값    [400] 동일
v=1 (정상)  6 이벤트
v=2 (이후)  6 이벤트   ← 세션 생존 확인
```

---

## Q-03 (P1) DB 장애 시 **30초 행** + `/api/health` 오보 — ⚠️ 부분 수정

### Test Scenario
데모 도중 `db` 컨테이너가 내려간 상황 (`docker compose stop db`).

### Expected
빠르게 실패하고, 헬스체크가 비정상을 알린다.

### Actual (수정 전)
```
/api/health    : {"service":"ventry","status":"ok"} [200]
/api/budget    : [500] 30.021159s
/api/recommend : [500] 30.018243s
```

### Risk
1. **30초 동안 톰캣 워커가 요청 하나씩 붙잡는다.** 동시 접속 몇 건이면 워커가 고갈된다.
   FE `fetch` 에는 타임아웃이 없어 **목 폴백조차 30초 뒤에야 시작된다** — 「데모 무중단」이
   실질적으로 **30초 정지**가 된다.
2. **`/api/health` 가 정상을 보고한다.** compose `depends_on`·CI 스모크·시연 전 점검이 전부
   이 값을 근거로 삼는데, **모든 업무 엔드포인트가 500인 스택을 "ok" 로 통과시킨다.**

### Recommendation / 수정 내용
- ✅ **행 시간 단축** — `application.yml` 에 Hikari `connection-timeout: 3000`(+`validation-timeout`,
  `initialization-fail-timeout: -1`) 추가. 조회는 캐시 예열 후 6~13ms 라 3초는 정상 부하에 닿지
  않는다. **실측 30.02초 → 3.02초.** 빨리 실패해야 폴백이 빨리 뜬다.
- ❌ **`/api/health` 는 손대지 않았다.** 응답 형태가 계약(§시스템 계약 `{"status":"ok"}`)에
  고정돼 있어 단독 변경 대상이 아니다. 조치안 두 가지:
  - (권장·계약 무영향) compose·CI 스모크의 대상을 **`/actuator/health`** 로 옮긴다.
    `management.endpoints.web.exposure.include: health` 가 이미 켜져 있어 DB 인디케이터가 포함된다.
    *실제 DOWN 응답 확인은 추가 검증 필요.*
  - (계약 변경) `/api/health` 가 DB 도달성을 반영하도록 하고 계약 문서를 함께 고친다 — 3인 합의 절차 대상.

---

# Minor Bugs

| ID | 항목 | 근거 | 판단 |
|---|---|---|---|
| **Q-04** | `composition[].amount` 에 음수·거대값이 200으로 통과 | `{"type":"equity","amount":-99999}`, `{"type":"guarantee","amount":2147483647}` 둘 다 200이며 응답에 **그대로 반향**된다 | 계산 영향은 없다 — `UsedLimits.byProduct` 가 `amount <= 0` 을 건너뛰고 상품 한도로 클램프한다. 다만 **의미 없는 값이 응답에 남는다.** 미수정(파급 없음) |
| **Q-05** | `/recommend` 콜드 응답 1.3~2.6초 (LLM 왕복이 요청 경로에 있음) | 예산 100만원 단위 20스텝 드래그 실측: 19회 6~13ms, **1회 1,312ms**. 콜드 최초 2.585s | 캐시(`reviews`) 설계가 잘 듣는다(미스 1/20). 다만 상위 3곳 판정이 바뀌는 순간 지도 전체가 LLM을 기다린다. 스펙 §7 「<100ms 재계산 체감」과 어긋나는 유일한 구간. 개선안: 반박문을 별도 이벤트/후속 조회로 분리(계약 변경) |
| **Q-06** | `POST /budget` 에 요청 순서 보증이 없다 | `Budget.tsx:78-82` debounce 300ms, in-flight 요청 **abort 없음**, `setPending` 공유 | 응답 역전 시 화면 프리뷰와 서버 B₀ 가 갈릴 수 있다. **로컬 실측 3~5ms 라 재현하지 못했다 — 느린 회선에서의 재현은 추가 검증 필요.** 코드 추적 기반 지적 |
| **Q-07** | `rate_type=fixed` 인데 `rate` 가 없는 상품 1건(F-002) | DB 실측 `F-002 이자지원 보증서 대출: rate=NULL, rate_type=fixed, rate_note='은행 대출 금리 - 이자 지원 금리'` | **계약 위반 아님** — 「`rate` 생략 시 `rate_note` 표기」 규칙이 성립한다(rate NULL 26건 중 `rate_note` 누락 **0건** 확인). 이미 assumptions #30 등재. `fixed` 라벨과 비수치 문구가 함께 보이는 표시상의 어색함만 남음 |
| **Q-08** | F-011 `rate_note` 가 **보증료**를 서술한다 | `창업자금 및 사업장 임차자금 특별보증: '보증료 일반교육 이수자 연 1.0%…'` | 금리 슬롯에 보증료가 들어가면 오독 소지. Q-07과 같은 데이터 검수 게이트 계열 |
| **Q-09** | `term_sweep.sh backend/src/main` 오탐 6건 | ALLOW 패턴이 `//`·`/*` 만 면제해 **Java 텍스트 블록** 안의 금지어 지시문을 못 거른다 | 도구 문제. 오탐이 진짜를 덮는 것을 스크립트 스스로 경계하므로 ALLOW 보강 권장 |
| **Q-10** | nginx 보안 헤더 없음 | `curl -D -` 결과 `X-Frame-Options`·`X-Content-Type-Options`·CSP 부재, `Server: nginx/1.27.5` 노출 | 예선 스코프에서 실피해는 낮으나 1줄 추가로 해소 가능 |
| **Q-11** | 세션 소유권 검증 없음 | 세션 id만 알면 타 세션의 `POST /budget` 이 200 | UUIDv4 bearer 구조이며 로그인 없는 예선 스코프의 **의도된 설계**로 판단. 다만 Q-02 독점의 전제 조건이기도 함 |

---

# Fixed in flight (리뷰 중 병행 수정 확인)

리뷰 초반, **기동 중이던 이미지**에서 아래 4건이 500이었다. 작업 트리에 병행 수정(백엔드 리뷰
세션의 `GlobalExceptionHandler` 보강)이 들어와 있어 재빌드 후 재검증한 결과 전부 해소됐다.
**초기 관측은 낡은 이미지 기준이었으므로 결함으로 계상하지 않는다.**

| 요청 | 재빌드 전 | 재빌드 후 |
|---|---|---|
| `GET /api/explore/{sid}?v=abc` | 500 `INTERNAL_ERROR` | **400** `INVALID_REQUEST` |
| `GET /api/recommend/{sid}?v=abc` | 500 | **400** |
| `GET /api/diagnose` (메서드 오류) | 500 | **405** `METHOD_NOT_ALLOWED` |
| `POST /api/diagnose` `Content-Type: text/plain` | 500 | **415** `UNSUPPORTED_MEDIA_TYPE` |
| `GET /api/nope` | — | **404** `NOT_FOUND` |

---

# Test Coverage

## 수행한 테스트 목록 (이번 회차 · 전부 기동 중인 스택 대상)

**정상 입력 / 기능**
- 전 구간 동선 1회 완주 — diagnose → scenarios(SSE) → budget → recommend → explore(SSE) → check-area
- 계약 적합성 (`/recommend` 1,059건): `total_count == len(areas)` ✅ / `score` 내림차순 ✅ /
  좌표 서울 범위 이탈 **0** / `area_code` 중복 **0** / 비용 구간 역전 **0** / `incl < ex` **0** /
  `est_sales ≤ 0` **0** / `burden_ratio` 비유한값 **0** / `score` 17~84
- 계약 적합성 (`/check-area`): `amount_max` 내림차순 ✅ / `rate_type` 전건 존재 ✅ /
  변동금리인데 `rate_note` 없는 상품 **0** / `data_as_of` 누락 **0** / 인용 7/11
- 프리뷰 단조성 스윕 (B=7,000 → 20,000): `area_count` 0 → 6 → 25 → 114 → 837 → 1,055 (단조 증가 ✅)

**비정상 입력 / Boundary / Empty**
- 빈 본문 `{}` → 400 · 깨진 JSON → 400 · 타입 오류(`"age":"서른둘"`) → 400
- `capital` 경계 4점 (100,000,000 / 100,000,001 / 2,147,473,648 / 2,147,483,647) → **Q-01**
- `capital` 범위 초과(9999999999, long) → 400
- `confirmed_budget` 누락 → 400 · `composition` 누락 → 200(허용) · 음수 → 400 · 상한 초과 → 400
- `area_code` null / 미존재 / SQLi 문자열 → 404
- `?v=` 음수 / 거대값 / 비숫자 → **Q-02**
- 이모지 · 5,000자 한글 `region_hint` → 200 정상 반향
- 300KB 본문 → 413

**API / 오류 처리**
- HTTP 메서드 오류 → 405, Content-Type 오류 → 415, 없는 경로 → 404, 없는 세션 → 404
- 오류 응답 규격 `{error:{code,message}}` 전건 준수

**동시성**
- `POST /budget` 10건 동시 (더블클릭 모사) → 10/10 200, 마지막 쓰기 승리
- SSE `/explore` 20 스트림 동시 → 20/20 완료 5.2초, `done` 누락 0

**장애 / 네트워크**
- LLM 잘못된 키 → 템플릿 폴백 (`applied=false, skipped=true`), 0.49~0.69초
- LLM 키 부재(무LLM) → 계약 게이트 **21/21 통과**
- DB 정지 → **Q-03** / DB 복구 → 자동 회복 200

**성능**
- `/recommend` gzip 722,857B → **122,477B** (5.9배)
- `/recommend` 웜 6~13ms, 콜드 2.585s (Q-05)
- `/budget` 3~5ms · `/geo/area-scope.v1.json` 949KB → gzip 288KB
- SSE 이벤트 nginx 경유 스트리밍 정상 (`proxy_buffering off` 확인)

**보안**
- SQLi / XSS / path traversal / 세션 소유권 / 보안 헤더 / `Server` 헤더 노출

**회귀**
- `./gradlew test` **260건 · 실패 0** (신규 `AmountBoundaryTest` 8건 포함)
- `ai` pytest **126건 · 실패 0**
- `npm run lint` ✅ · `tsc --noEmit` ✅ · `term_sweep.sh frontend/src` **위반 0**
- `scripts/qa_integration.py` **21/21** (LLM 켬/끔 양쪽)

## 커버리지 매트릭스

| 항목 | 수행 | 비고 |
|---|---|---|
| 정상 입력 | ✅ | |
| 비정상 입력 | ✅ | |
| Boundary Value | ✅ | Q-01 발견 |
| Empty Value | ✅ | |
| Duplicate Request | ✅ | 10건 동시 |
| Invalid / Expired Token | ⚠️ | 세션 토큰 개념 없음. **TTL 60분 만료는 미검증** |
| Slow Network | ❌ | 미수행 — Q-06이 여기 걸린다 |
| Offline | ⚠️ | 서버측 장애만 확인, 브라우저 offline 미수행 |
| API Error | ✅ | 4xx/5xx 전 계열 |
| Large Data | ✅ | 1,059건 · 300KB 본문 |
| Desktop / Mobile / Browser | ❌ | **미수행** — 브라우저 실행 검증 없음 |

---

# Missing Test

1. **브라우저 실행 검증 0건** — 이번 회차는 전부 HTTP 레벨이다. 화면 렌더·마커 갱신·폴백 배너·
   새로고침/뒤로가기 동작은 확인하지 못했다. FE 자동 테스트도 0건이라 **화면 회귀 방어가
   2026-07-26 수동 CDP 주행 기록에만 의존**한다 (1회차 P1-1과 동일 결론).
2. **세션 TTL 60분 만료 후 동작** — 만료 시 `/recommend` 404 → FE가 조용히 목으로 전환한다
   (코드 추적). 실데이터 1,059곳이 **가짜 3곳으로 바뀌는** 순간이며 신호는 상단 배너 한 줄뿐이다.
   TTL 을 짧게 주입한 프로파일로 자동 검증할 것.
3. **느린 회선에서의 `POST /budget` 응답 역전** (Q-06) — 프록시 지연 주입으로 재현 시도 필요.
4. **`/actuator/health` 가 DB DOWN 을 실제로 보고하는지** (Q-03 조치안의 전제).
5. **모바일·타 브라우저** — Safari 의 `EventSource` 재연결 동작이 목 폴백 로직과 맞물리는 지점.
6. **금지어 목록 3곳 사본 일치 단언** (1회차 P2-2, 미해소).

---

# Good Points

- **장애 폴백이 실제로 동작한다.** LLM을 잘못된 키로 죽여도, 아예 없애도 계약 게이트 21건이
  전건 통과한다. `applied=false, skipped=true` 로 **하지 않은 일을 했다고 말하지 않는** 설계가
  런타임에서 그대로 확인됐다.
- **데이터 위생이 좋다.** 1,059건 전수 검사에서 구간 역전·중복 코드·좌표 이탈·비유한 부담률이
  **전부 0건**이다. 이 규모에서 흔한 결측 오염이 없다.
- **목 폴백 표시 설계가 정직하다.** 폴백이 한 번이라도 일어나면 배너를 세우고 되돌리지 않는다
  (`api/fallback.ts`). 「출처 사칭 금지」를 코드로 강제한 드문 사례다.
- **캐시 설계가 실측으로 옳다.** 리스크 검증 캐시 키를 「상위 3곳 판정」으로 잡은 판단이
  슬라이더 20스텝에서 미스 1회로 실증됐다 — 예산을 키에 넣었다면 20회 전부 LLM 왕복이었다.
- **자체 QA 하네스가 실질적이다.** `qa_integration.py` 의 D1(도슨트 대본 수치 정확 일치)·
  G1~G10 은 형식적 게이트가 아니라 재적재 사고를 실제로 잡는 장치다.
- **오류 처리가 리뷰 중에도 개선됐다.** 4xx 오분류 4건이 병행 수정으로 해소된 것을 재빌드로 확인했다.

---

# Action Items

## P0
- [x] **없음** — 서비스 중단·데이터 손실 경로 미발견

## P1
- [x] **Q-01** `capital` 정수 오버플로 → `/api/scenarios` 500 — 입력 상한 + `long` 산술로 수정, 회귀 테스트 8건 추가
- [x] **Q-02** `?v=` 규격 검증 부재 → 빈 스트림/세션 정지 — 범위 검증 400 추가 *(상한 안의 독점은 잔존 — 아래 참조)*
- [x] **Q-03-a** DB 장애 30초 행 → Hikari `connection-timeout: 3000` 으로 **3.02초** 단축
- [ ] **Q-03-b** `/api/health` 가 DB 다운 상태에서 `ok` 를 보고한다 — compose·CI 스모크를
      `/actuator/health` 로 옮기거나 계약 변경 합의. **제출 전 필수**
- [ ] **Q-02-b** version 독점의 근본 해소(서버 발급 version) 여부 결정 — 계약 변경이라 3인 합의 대상.
      "정상 동선 미도달"로 수용한다면 그 판단을 DECISIONS 에 남길 것
- [ ] 제출 전 **브라우저 전 구간 주행 1회** + `docs/images` 캡처가 현 덤프 수치와 일치하는지 확인
- [ ] 시연 머신 리허설에서 앱키 · `--build` · `down -v` 3종 실기 통과

## P2
- [ ] **Q-05** `/recommend` 콜드 LLM 왕복 1.3~2.6초 — 반박문 분리 검토 (계약 변경 수반)
- [ ] **Q-06** `POST /budget` in-flight abort + 요청 순번 가드 (느린 회선 재현 선행)
- [ ] **Q-04** `composition[].amount` 음수/거대값 검증
- [ ] **Q-09** `term_sweep.sh` ALLOW 에 Java 텍스트 블록 패턴 추가
- [ ] **Q-10** nginx 보안 헤더 3종 추가
- [ ] **Q-07/Q-08** F-002·F-011 `rate_note` 데이터 검수 (assumptions #30 후속)
- [ ] 세션 TTL 만료 시 화면 동작 자동 검증

---

# Test Coverage Score

## 78 / 100

| 축 | 배점 | 득점 | 근거 |
|---|---|---|---|
| 단위 테스트 | 25 | 22 | BE 260건 · AI 126건 실패 0. 결정적 도구 계층 5종 전부 보유 |
| 계약·통합 테스트 | 25 | 24 | 자체 하네스 21건이 LLM 켬/끔 양쪽 통과. 실데이터 기준 |
| 경계·비정상 입력 | 20 | 16 | 이번 회차로 크게 보강(Q-01·Q-02 발견). 상한 검증이 **없던 상태로 제출될 뻔했다** (-4) |
| 장애·복원력 | 20 | 16 | LLM 2종·DB 1종 실검증. Slow Network·Offline 미수행 (-4) |
| 화면(FE) | 10 | 0 | **자동 테스트 0건 + 이번 회차 브라우저 실행 0건** |

# Stability Score

## 84 / 100

| 축 | 배점 | 득점 | 근거 |
|---|---|---|---|
| 정상 동선 안정성 | 30 | 30 | 전 구간 동작, 데이터 위생 결함 0 |
| 오류 처리 정확성 | 25 | 22 | 4xx/5xx 분류 정상(재빌드 후). `/api/health` 오보 (-3) |
| 장애 복원력 | 25 | 20 | LLM 폴백 무결. DB 장애 시 전 엔드포인트 500 — 폴백 없음, 헬스 오보 (-5) |
| 동시성 | 10 | 10 | 20 SSE · 10 동시 POST 이상 없음 |
| 성능 | 10 | 2 | 웜 6~13ms·gzip 5.9배는 우수하나, 콜드 2.6초 구간이 스펙 §7 목표를 벗어난다 (-8) |

---

# Final Decision

## **Conditional Pass**

**이유**

**Pass 근거** — P0가 없다. 실데이터 정상 동선이 전 구간 동작하고, 자체 계약 게이트 21건이 LLM
활성/비활성 양쪽에서 통과하며, 1,059건 데이터 위생 검사에서 결함이 0건이다. 이번 회차에서
발견한 P1 3건은 **전부 이 리뷰 안에서 수정하고 회귀 테스트로 고정했으며**, 수정 후
단위 260건·통합 21건·FE lint/tsc/용어 스윕이 모두 그린이다.

**무조건 Pass가 아닌 이유** — 두 가지다.

1. **`/api/health` 가 깨진 스택을 정상으로 보고한다(Q-03-b).** 이것은 단순 버그가 아니라
   **검증 체계 자체의 구멍**이다. 시연 직전 점검이 이 값을 근거로 삼는 이상, DB가 내려간 채
   "ok"를 받고 데모에 들어가는 시나리오가 열려 있다. 계약 고정 필드라 단독 수정하지 않았으므로
   **제출 전 결정이 필요하다.**
2. **화면 계층이 이번 회차에서 한 번도 실행되지 않았다.** FE 자동 테스트가 0건이고 이번
   검증도 전부 HTTP 레벨이다. 특히 **세션 만료·SSE 빈 스트림·API 실패가 전부 "목 데이터
   무음 전환"으로 수렴**하는 구조라(Q-02의 파급 경로), 그 순간 화면이 실제로 어떻게 보이는지는
   **추가 검증 필요** 상태다. 배너 설계는 훌륭하지만 동작을 눈으로 확인하지 못했다.

위 P1 잔여 2건(Q-03-b, 브라우저 주행)을 처리하면 **Pass**로 판단한다.

---

# 변경 파일 (이번 회차 · 미커밋)

| 파일 | 변경 |
|---|---|
| `backend/.../common/Amounts.java` | **신설** — 금액 입력 상한 상수 + 근거 |
| `backend/.../diagnose/DiagnoseController.java` | `capital`·`monthly_investable` 상한 검증 |
| `backend/.../scenario/ScenarioController.java` | `confirmed_budget` 상한 검증 |
| `backend/.../serving/ScenarioBuilder.java` | `budgetMax` 를 `long` 누적으로 (오버플로 차단) |
| `backend/.../common/SessionStore.java` | `acceptVersion` 범위 검증 + `MAX_VERSION` |
| `backend/src/main/resources/application.yml` | Hikari 빠른 실패 3종 |
| `backend/src/test/.../AmountBoundaryTest.java` | **신설** — Q-01·Q-02 회귀 8건 |

---

# 부록 — 1회차(정적 검토) 결론 보존

2026-07-29 16:00~16:30 KST, 기준 커밋 `396b41f`, 읽기 전용.

**요약**: 품질 게이트 구조가 이 규모로는 이례적으로 좋다. 단위 테스트 위에 실데이터 계약 게이트
G1~G10 + 도슨트 대본 수치 정확 일치 게이트 D1이 compose 스모크마다 돌고, 용어 컴플라이언스는
`term_sweep.sh` 로 frontend-ci에 결선돼 있다. 과거 QA 리포트의 P0~P2(유동인구 90배 과대, 폴백
미표시, 앱키 절차, BE07 수치 불일치)는 표본 추적 결과 전부 해소. 정적 점수 82/100.

**1회차 P1 (이번 회차에도 유효)**
- FE 자동 테스트 0건 — 화면 회귀 방어가 수동 주행 기록에 의존 → **이번 회차 Missing Test 1로 승계**
- `develop` 직접 push 시 영역 CI 없이 `main` 자동 승격 (`develop-ci.yml` promote-to-main).
  브랜치 보호 실설정은 **추가 검증 필요**
- 앱키·볼륨(`down -v`) 절차가 사람 손에 있음 → Action Items P1로 승계

**1회차 P2 (미해소)**
- AI 데이터 의존 테스트의 자동 skip — 있어야 할 테스트가 skip돼도 CI가 침묵. `pytest -rs` 권장
- 금지어 목록 3곳 수동 사본 (`term_sweep.sh` / `LlmResponses.BANNED_TERMS` / `qa_integration.py`)
- 목 폴백 플래그가 비가역 — 일시 장애 후 복구돼도 "예시 데이터" 배너가 진짜 수치 위에 남음
  (안전 방향 오류라 심각도 낮음)
- 리포트 간 gzip 수치 불일치 — **이번 회차 실측 722,857B → 122,477B (5.9배)** 로 확정. 제출물 대조 시 이 값을 쓸 것

**1회차 검증 로그 요약**: `gh run list` 15건 중 최신 develop-ci 성공 / `docker compose config` 정상 /
`git grep` 시크릿 0건 / 덤프 과대값 8,060,412 → 0건 / D1 상수 = README 대본 일치.

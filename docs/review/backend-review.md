# Backend Review

- 리뷰 일자: 2026-07-29 (2차 — **운영·경계면 중심**. 1차 「계약·스펙 준수」 패스 결과를 이 문서에 통합·갱신)
- 대상: `backend/` 전체 (develop, 396b41f), `backend/Dockerfile`, `docker-compose.yaml` 의 api 경로,
  `db/init/01_schema.sql` 중 서빙 조회 경로
- 기준 문서: `docs/API_CONTRACT.md`, `docs/specs/최종_스펙문서.md` v6.3,
  `docs/specs/exploration_agent_spec_v2_1.md`, CLAUDE.md 절대 불변 원칙
- 검증 방법: 정적 리뷰 + **실행 프로브** — MockMvc 상태코드 측정 / 무응답 업스트림 소켓 회수 측정 /
  후보 1,061곳 규모 부하 측정 / DB 미가용 컨테이너 기동 측정 / `docker build` 및 컨테이너 실행 확인
- 스코프 제외: Frontend, AI 프롬프트 문안, QA 시나리오 — 백엔드에 영향을 주는 부분만 다룬다

> **1차 패스와의 관계**: 1차는 계약 6종 필드·타입·정렬·용어 컴플라이언스를 대조해 **P0 없음 / P1 1건**으로
> 마무리했다. 그 판정은 대체로 유지된다 — 다만 1차가 검사하지 않은 **오류 경로·자원 상한·기동 복원력**에서
> P0 3건이 나왔고, 1차의 P1 1건은 **실측으로 근거가 일부 반증**되어 등급을 조정했다(M-05).

---

# Executive Summary

**결론: Conditional Approve.**

결정적 계층(`engine/`)의 품질은 실서비스 기준으로도 높다. 순수 함수 + 단위 테스트, 파라미터 바인딩 SQL,
쓰기 경로 부재, LLM 실패의 단일 폴백 수렴, 계약 타입 가드(`Infinity` 차단)까지
「모든 숫자는 결정적 계산이 만든다」가 규율이 아니라 **구조로** 강제되어 있다. 이 부분은 손댈 것이 없다.

결함은 전부 **경계면**에 있었다 — 오류 응답, 자원 상한, 기동 복원력. 세 건은 실측으로 재현되는
서비스 장애급이라 이번 리뷰에서 직접 수정했다.

| 등급 | 건수 | 조치 |
|---|---|---|
| Critical (P0) | 3 | **3건 수정 완료** (회귀 테스트 11건 추가) |
| Major (P1) | 6 | 2건 수정 완료 · 4건 패치 제안 (설계·계약 합의 필요) |
| Minor (P2) | 12 | 제안만 (1차 패스 P2 4건 포함) |

수정 후 전체 테스트 **260건 그린 (실패 0 · 오류 0)** — 그중 11건이 이번에 추가한 회귀 테스트다.
`docker build ./backend` 성공, 비루트(uid 10001) 실행 확인, DB 미가용 상태에서 컨테이너 생존 확인.

> **동시 작업 주의**: 이 리뷰가 진행되는 동안 같은 워킹트리에서 QA·프론트 리뷰 패스가 함께 수정을
> 넣었다(`Amounts`·`acceptVersion` 범위 검증·Hikari 타임아웃·`plans` 캐시 등). 위 260건과 아래
> 「해소됨」 표기는 **그 변경들이 합쳐진 현재 트리** 기준이며, 어느 패스가 무엇을 고쳤는지는
> 해당 항목에 명시했다.

가장 무거운 미해결 항목은 **M-01 (동기 API가 톰캣 워커 스레드에서 LLM을 최대 5초 대기)** 이다.
`SseSupport` 가 스스로 적어 둔 원칙(「톰캣 워커 스레드에서 LLM 대기 금지」)을 `/api/recommend` 가
어기고 있고, 하필 그 경로가 스펙 §7의 「<100ms 재계산 체감」 대상인 슬라이더 경로다.
계약이 `risk_review` 를 응답 필수 필드로 규정하므로 코드만으로는 닫히지 않는다 — 3인 합의가 필요하다.

---

# Critical

## C-01. 클라이언트 입력 오류 5종이 전부 `500 INTERNAL_ERROR` 로 나간다 — **수정 완료**

### Problem

`GlobalExceptionHandler` 에 `@ExceptionHandler(Exception.class)` catch-all 만 있고 스프링 MVC 표준 4xx
예외 핸들러가 없었다. `ExceptionHandlerExceptionResolver` 는 `DefaultHandlerExceptionResolver` 보다
**먼저** 평가되므로, catch-all 을 두는 순간 프레임워크가 4xx 로 답하던 것까지 전부 500이 된다.

### Evidence

MockMvc 프로브 실측 (수정 전):

```
PROBE[bad query param    GET /api/recommend/{sid}?v=abc  ] status=500  ex=MethodArgumentTypeMismatchException
PROBE[unknown path       GET /api/nope                   ] status=500  ex=NoResourceFoundException
PROBE[wrong method       GET /api/diagnose               ] status=500  ex=HttpRequestMethodNotSupportedException
PROBE[no content type    POST /api/diagnose              ] status=500  ex=HttpMediaTypeNotSupportedException
PROBE[wrong content type POST /api/diagnose text/plain   ] status=500  ex=HttpMediaTypeNotSupportedException
```

- 계약 위반: `docs/API_CONTRACT.md` §시스템 계약은 「HTTP 4xx/5xx + `{error:{code,message}}`」이고,
  프로젝트 자체 QA(`scripts/qa_integration.py::f1_error_contract`)도 **"5xx 는 내부 오류 노출"** 을
  실패 조건으로 명시한다. 그 QA가 검사하는 3케이스는 통과하고 있었고 위 5케이스는 검사 밖이었다.
- 1차 패스가 「오류 규격 충족」으로 판정한 근거(`GlobalExceptionHandler.java:18-53`)는
  **ApiException·본문 파싱 실패 2경로만** 본 것이었다. 같은 성격인 **쿼리** 타입 오류가 500으로 남아
  본문 타입 오류(400)와 비대칭이었다.

### Impact

1. 프론트가 재시도 여부를 판단할 수 없다 — 5xx는 "서버가 아픈 것"이라 재시도 대상이지만, 실제로는
   고쳐야 할 것이 입력이다.
2. **인증 없이 외부에서 ERROR 로그를 무제한 유발할 수 있다.** 없는 경로 호출 한 번에
   `log.error(스택트레이스)` 한 건이 쌓인다. 스캐너 한 대가 붙으면 그 안에서 진짜 500을 찾을 수 없다.
3. 심사위원이 API를 직접 찔러 보는 동선에서 없는 장애로 보인다.

### Recommendation / 적용한 수정

`common/GlobalExceptionHandler.java` 에 표준 예외 핸들러를 추가했다. 응답 봉투는 그대로이므로
**계약 변경이 아니라 계약 준수**다.

| 예외 | 이전 | 이후 | code |
|---|---|---|---|
| `MethodArgumentTypeMismatchException` · `MissingServletRequestParameterException` | 500 | **400** | `INVALID_REQUEST` |
| `NoResourceFoundException` | 500 | **404** | `NOT_FOUND` |
| `HttpRequestMethodNotSupportedException` | 500 | **405** (+`Allow`) | `METHOD_NOT_ALLOWED` |
| `HttpMediaTypeNotSupportedException` | 500 | **415** | `UNSUPPORTED_MEDIA_TYPE` |

부수 조치 2건:
- 로그를 `error(스택)` → `warn(요약)` 으로 낮춰 로그 폭증 통로를 끊었다.
- 메시지에 예외 원문을 싣지 않는다. `MethodArgumentTypeMismatchException.getMessage()` 에는 대상 타입의
  **클래스 경로가 그대로** 들어 있다 — 파라미터 이름만 노출한다. 회귀 테스트가 응답 본문에
  `java.`·`com.ventry` 문자열이 없음을 단언한다.
- 500 로그에 요청 메서드·경로를 남긴다(쿼리 문자열은 제외 — 세션 id 노출면을 넓히지 않는다).

회귀 테스트: `common/ErrorContractTest.java` (8건).

> **팀 확인 요청**: 새 `error.code` 4종(`NOT_FOUND`/`METHOD_NOT_ALLOWED`/`UNSUPPORTED_MEDIA_TYPE`
> + C-02의 `TOO_MANY_SESSIONS`)은 계약에 코드 목록이 없어 충돌하지 않지만,
> `docs/API_CONTRACT.md` §시스템 계약에 표로 추가해 두는 편이 낫다.

---

## C-02. 인증 없는 세션 생성에 상한이 없어 인메모리 저장소가 무한히 자란다 — **수정 완료**

### Problem

`POST /api/diagnose` 는 인증·레이트리밋이 없고 호출 한 번에 `SessionStore` 항목 하나를 만든다.
방어는 TTL 60분 + 10분 주기 스윕뿐인데, 이는 **만료된 것을 지우는 장치**이지
**만료 전 구간의 생성 속도를 막는 장치**가 아니다.

### Evidence

```java
// 수정 전 SessionStore.create — 상한 없음
public SessionState create(ParsedProfile profile) {
    String id = UUID.randomUUID().toString();
    sessions.put(id, new SessionState(id, profile));   // 무조건 적재
    return state;
}
```
`@Scheduled(fixedDelay = 10, MINUTES)` 스윕은 `expired()`(마지막 접근 + 60분)만 지운다.
즉 60분 창 안에서는 호출 수만큼 항목이 단조 증가한다.

### Impact

단일 인스턴스 인메모리 저장소라 상한 없는 증가의 끝은 OOM 이고, **그 시점에 진행 중인 모든 세션이
함께 사라진다.** 데모·심사 중 발생하면 복구 수단이 프로세스 재시작뿐이다.
`POST /api/budget/{sid}` 로 큰 `composition` 배열을 붙이면 항목당 크기도 키울 수 있다
(본문 상한 256KB는 `RequestSizeLimitFilter` 가 잡지만 세션 **수** 는 잡지 않는다).

### Recommendation / 적용한 수정

동시 보관 상한 `MAX_SESSIONS = 10_000` 을 두고, 도달 시 **만료분을 먼저 걷어낸 뒤** 그래도 자리가
없으면 `429 TOO_MANY_SESSIONS` 로 거절한다. 정상 사용(동시 세션 두 자릿수)은 이 선에 닿지 않는다.

```java
public SessionState create(ParsedProfile profile) {
    if (sessions.size() >= MAX_SESSIONS) {
        evictExpired();
        if (sessions.size() >= MAX_SESSIONS) {
            throw ApiException.tooManySessions();   // 429
        }
    }
    ...
}
```

회귀 테스트: `common/SessionStoreTest.java` (3건).
남은 근본 대책은 **엔드포인트 레이트리밋**이다(현재 없음) — N-02 참조.

---

## C-03. DB가 잠깐이라도 불가하면 API 컨테이너가 죽고 다시 오지 않는다 — **수정 완료**

### Problem

`CandidateWarmup.warmUp()` 이 `@EventListener(ApplicationReadyEvent.class)` 에서 DB를 조회하는데,
이 리스너가 던진 예외는 `SpringApplication.run` 의 실패로 처리되어 **JVM이 종료 코드 1로 죽는다.**
예열은 최적화인데 기동 조건이 되어 있었다.

### Evidence

수정 전 이미지를 DB 없이 띄운 실측:

```
state=exited exit=1                                        ← 컨테이너가 약 4초 만에 종료
INFO  ... : Started VentryApiApplication in 0.96 seconds       ← 기동 성공 로그 직후에
  at com.ventry.api.serving.CandidateWarmup.warmUp(CandidateWarmup.java:29)
Caused by: org.postgresql.util.PSQLException: Connection to 127.0.0.1:5999 refused.
```

`docker-compose.yaml` 의 `depends_on: {db: {condition: service_healthy}}` 는 **최초 기동만** 보장한다.
그리고 api 서비스에는 **재시작 정책이 없다**(`restart:` 미지정).

### Impact

DB 컨테이너 재시작·순단이 API 컨테이너의 영구 소멸로 이어진다. 웹(nginx)은 살아 있으므로 화면은 뜨고
API 호출만 전부 실패하는, 원인 파악이 가장 어려운 형태의 장애가 된다. 심사·데모 중 발생하면 수동
`docker compose up` 전까지 복구되지 않는다.

### Recommendation / 적용한 수정

예열 실패를 삼키고 경고만 남긴다. 캐시는 첫 요청 때 지연 적재되고, DB가 돌아오면 서비스도 스스로
돌아온다 — 예열이 안 된 대가는 첫 요청 한 번의 조회 지연뿐이다.

```java
try {
    INDUSTRIES.forEach(candidates::findCandidates);
} catch (RuntimeException e) {
    log.warn("후보 캐시 예열 실패 — 첫 요청에서 지연 적재된다 (사유: {})", e.toString());
}
```

수정 후 같은 조건 실측:

```
state=running
{"service":"ventry","status":"ok"}   http=200
WARN ... CandidateWarmup : 후보 캐시 예열 실패 — 첫 요청에서 지연 적재된다
        (사유: org.springframework.jdbc.CannotGetJdbcConnectionException: Failed to obtain JDBC Connection)
```

> **이 수정은 새로운 사실을 하나 만든다** — 이제 DB가 죽어도 컨테이너는 살아 있고 `/api/health` 는
> 여전히 200 `ok` 를 반환한다. 즉 **degraded 가 오케스트레이션에 보이지 않는다.**
> 반드시 M-06(헬스체크를 `/actuator/health` 로)과 **세트로** 처리해야 한다. 단독 적용은 장애를 숨긴다.

---

# Major

## M-01. `/api/recommend`·`/api/check-area` 가 톰캣 워커 스레드에서 LLM을 최대 5초 대기한다 — **미수정 (합의 필요)**

### Problem

호출 사슬이 동기다.

```
RecommendController.recommend()                        ← 톰캣 워커 스레드
  └ LocationService.recommend()
      └ RiskReviewAgent.forRecommend()
          └ ReviewGenerator.generate()   @Cacheable("reviews")
              └ GuardedLlmClient.complete()            ← 여기서 블로킹
```

`SseSupport` 자신의 주석이 「송출은 전용 스레드에서 수행 — 톰캣 워커 스레드에서 LLM 대기 금지」라 적고,
`ExploreController` 는 BE 리뷰 D-10에서 정확히 그 이유로 계산 위치를 옮겼다. 동기 API 2종만 원칙 밖에 있다.

### Evidence

- 코드 사슬은 위와 같고 분기가 없다 — `RiskReviewAgent` 는 항상 호출된다.
- 무응답 업스트림 프로브 실측: `complete()` 는 정확히 타임아웃만큼 블로킹한다 →
  `PROBE[hung upstream] elapsedMs=5001 empty=true`
- `LlmSettings.DEFAULT_TIMEOUT = 5s`, `DEFAULT_MAX_CONCURRENT = 4`.
- `/api/recommend` 는 **슬라이더가 움직일 때마다 재호출되는 경로**다
  (`application.yml` 주석 · 스펙 §7 「<100ms 재계산 체감」).
- 1차 패스는 같은 계열 문제를 `/explore` 의 plan 왕복(TTFB 최대 5s)으로 P2 기록했는데,
  `/recommend` 쪽이 **호출 빈도·성능 요구가 모두 높아** 등급이 다르다.

### Impact

- 캐시 미스 + LLM 지연 시 슬라이더 응답이 최대 5초 멈춘다. 프로젝트 자체 실측이 캐시 히트 수 밀리초 대
  미스 약 1.9초다(`ReviewGenerator` 주석).
- 세마포어(K=4) 덕에 동시 LLM 대기는 4건으로 묶이지만, 그 4건이 톰캣 워커 4개를 5초씩 점유한다.
  나머지 요청은 즉시 폴백되므로 워커 고갈까지는 가지 않는다 — **가용성보다 지연**의 문제다.
- `RiskReviewAgent` 의 캐시 키 설계(예산·후보 총수를 사실에서 제외)가 비용을 크게 줄여 두었다.
  그래서 상시 문제가 아니라 **캐시 미스 구간에서만 터지고**, 그만큼 발견도 늦다.

### Recommendation

계약이 `risk_review` 를 `/recommend` 응답 필수 필드로 규정하므로 코드만으로는 닫히지 않는다.
비용 순서로 셋 중 하나를 3인 합의로 고른다.

**(a) 동기 경로 전용 타임아웃 단축 — 가장 싸다. 계약 무변경. 권고안.**

```java
// LlmSettings
public static final Duration SYNC_TIMEOUT   = Duration.ofMillis(1200);  // 슬라이더 경로
public static final Duration STREAM_TIMEOUT = Duration.ofSeconds(5);    // SSE plan·refine
```
`ReviewGenerator` 만 짧은 상한을 쓰게 하고, 초과 시 지금과 동일하게 `skipped=true` 템플릿 폴백.
「LLM이 늦으면 템플릿이 최종본」은 이미 설계된 동작이라 새 실패 모드가 생기지 않는다.

**(b) 사전 계산** — `POST /budget` 시점에 반박문을 미리 데운다. 캐시 키가 「업종 + 상위 후보 판정」이라
예산 확정 직후 값이 정해지므로 `/recommend` 는 캐시 히트만 하게 된다. 구현량 중간.

**(c) 계약 변경** — `risk_review` 를 SSE·별도 엔드포인트로 분리. 가장 깨끗하지만 D3 동결 이후 변경이라
3인 합의 + 문서 수정 PR이 필요하다. 마감을 고려하면 비권장.

---

## M-02. SSE 자원 상한이 없다 — 하트비트 스레드 4개 고정 + 스트림 수 무제한 — **미수정 (패치 제안)**

### Problem

```java
private static final int HEARTBEAT_THREADS = 4;
private final ScheduledExecutorService heartbeat = Executors.newScheduledThreadPool(HEARTBEAT_THREADS);
...
hb = heartbeat.scheduleAtFixedRate(() -> {
    synchronized (lock) { emitter.send(...); }     // 블로킹 write
}, 15, 15, SECONDS);
```

`SseEmitter.send` 는 블로킹 쓰기다. 소비가 느린 클라이언트(모바일 네트워크·백그라운드 탭)에서 TCP 송신
버퍼가 차면 이 호출이 스케줄러 스레드를 붙잡는다. 스레드가 4개뿐이라 **느린 클라이언트 4명이면 전체
스트림의 하트비트가 멈춘다.** 주석은 「한 emitter 의 send 가 막혀도 다른 스트림이 멈추지 않게(D-15)」라
적지만, 4라는 값은 **4명까지만** 그 보장을 준다.

### Evidence

- 코드 근거는 위와 같다. **실측은 하지 않았다** — 재현에 느린 소비자 클라이언트가 필요하다.
  논리적 결함은 명확하나 발현 조건이 실환경 네트워크에 의존하므로 **추가 검증 필요**로 표기한다.
- 스트림 수 자체에도 상한이 없다: `Executors.newVirtualThreadPerTaskExecutor()` + 세션당 제한 없음.
  C-02로 세션 수는 묶었지만 **한 세션이 여는 동시 스트림 수**는 여전히 무제한이다.

### Impact

하트비트가 15초 안에 못 나가면 nginx `proxy_read_timeout`(3600s)에는 안 걸려도 그 앞단의 사내망·캐리어
프록시 타임아웃에 걸려 스트림이 끊긴다. 화면 4(탐색)가 이유 없이 멈춘 것처럼 보인다.

### Recommendation

```java
// SseSupport — 하트비트도 가상 스레드로 보내 스케줄러 스레드를 잡아 두지 않는다
private final ScheduledExecutorService heartbeat = Executors.newScheduledThreadPool(1);
private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

hb = heartbeat.scheduleAtFixedRate(
        () -> executor.submit(() -> {          // 블로킹 write 를 가상 스레드로 이관
            try {
                synchronized (lock) { emitter.send(SseEmitter.event().comment("heartbeat")); }
            } catch (Exception ignored) { }
        }),
        HEARTBEAT_SEC, HEARTBEAT_SEC, TimeUnit.SECONDS);
```
스케줄러 스레드는 제출만 하고 즉시 반환하므로 느린 클라이언트가 몇이든 서로를 굶기지 않는다.
동시 스트림 상한은 `Semaphore` 하나로 충분하다(초과 시 `503` + `Retry-After`).

---

## M-03. `db` 프로파일 코드가 JUnit 계층에서 한 줄도 실행되지 않는다 — **미수정 (테스트 보강 필요)**

### Problem

`CandidateRepository`·`FinanceRepository`·`DataMetaRepository` 의 **SQL 문자열 자체**와
`DbCandidateSource`·`DbProductSource`·`DbDataMeta`·`CandidateWarmup`·`CacheConfig` 는 전부
`@Profile("db")` 이고, 테스트는 모두 기본(픽스처) 프로파일에서 돈다.

### Evidence

- `backend-ci.yml` 는 `./gradlew build` + `docker build` 만 돌린다. 둘 다 `db` 프로파일이 아니다.
- 실데이터 게이트(G1~G9)는 `develop-ci.yml` 의 compose 스모크에서 **파이썬 스크립트로만** 돈다.
- 결과: `SELECT` 컬럼명 오타·뷰 스키마 변경·`@Cacheable` 키 오류는 **backend-ci 를 통과하고
  develop-ci 에서야 터진다** — 영역 브랜치 PR 게이트가 잡지 못한다.
- 로우 매퍼는 `CandidateRowMapperTest`·`FundingProductRowMapperTest` 가 가짜 `ResultSet` 으로 덮는다.
  즉 **매핑은 검증되고 쿼리는 검증되지 않는 비대칭**이다.

### Impact

`db/init/01_schema.sql` 의 뷰(`v_candidate_area`)와 `CandidateRepository.SELECT_BY_INDUSTRY` 가 갈라지는
순간을 잡을 자동 게이트가 PR 단계에 없다. AI 배치가 뷰를 다시 구울 때 정확히 이 경로가 위험하다.

### Recommendation

Testcontainers 슬라이스 테스트 1개면 충분하다. **행이 0건이어도 목적은 달성된다** — 컬럼·뷰·타입 검증이다.

```gradle
testImplementation 'org.springframework.boot:spring-boot-testcontainers'
testImplementation 'org.testcontainers:postgresql'
```

```java
@SpringBootTest
@ActiveProfiles("db")
@Testcontainers
class DbProfileQueryTest {
    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init/01_schema.sql");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl);
        r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
    }

    @Autowired CandidateSource candidates;
    @Autowired ProductSource products;

    @Test void queriesCompileAgainstRealSchema() {
        assertThatCode(() -> candidates.findCandidates("cafe")).doesNotThrowAnyException();
        assertThatCode(products::all).doesNotThrowAnyException();
    }
}
```
CI 시간이 부담이면 `@Tag("db")` 로 묶어 backend-ci 에서만 돌린다.

---

## M-04. 컨테이너가 root 로 실행된다 — **수정 완료**

### Problem / Evidence

`backend/Dockerfile` 의 run 단계에 `USER` 지시자가 없어 기본값 root 로 떴다.
애플리케이션은 8080 바인딩 외에 특권이 필요 없다.

### Impact

컨테이너 안에서의 임의 코드 실행이 곧 루트 권한이 된다. 컨테이너 이스케이프 취약점과 결합할 때 피해
범위가 달라지는 지점이며, 공모전 산출물의 기본 하드닝 항목이기도 하다.

### Recommendation / 적용한 수정

```dockerfile
RUN useradd --system --uid 10001 --create-home --shell /usr/sbin/nologin ventry \
    && chown -R ventry:ventry /app
USER ventry
```

빌드·실행 실측:
```
$ docker run --rm --entrypoint sh ventry-api-review -c "id && ls -l /app"
uid=10001(ventry) gid=999(ventry) groups=999(ventry)
-rw-r--r-- 1 ventry ventry 25805605 ... app.jar
```

---

## M-05. OpenAI 클라이언트에 HTTP 계층 타임아웃이 없었다 — **수정 완료 (1차 P1 판정을 실측으로 조정)**

### Problem

`RestClient.builder()` 의 기본 요청 팩토리는 **연결·읽기 타임아웃이 무한**이다.
가드는 `GuardedLlmClient` 의 `Future.get(5s)` + `cancel(true)` 하나뿐이었다.

### Evidence — 1차 패스 판정의 정정

1차 패스는 이를 P1로 올리며 근거를 이렇게 적었다:
> *"블로킹 소켓 읽기는 인터럽트에 반응하지 않으므로 실제 HTTP 호출은 계속 살아 있다 …
> 진행 중 커넥션·가상 스레드가 무한 누적될 수 있다."*

무응답 업스트림(수락 후 무회신) 프로브로 이 부분을 직접 측정한 결과는 다르다.

```
PROBE[request factory] org.springframework.http.client.JdkClientHttpRequestFactory
PROBE[hung upstream]   elapsedMs=5001  empty=true
PROBE[hung upstream]   socketReleased=true        ← cancel(true) 인터럽트가 소켓까지 회수한다
```

현재 클래스패스에서 선택되는 요청 팩토리는 `JdkClientHttpRequestFactory` 이고, JDK `HttpClient.send` 는
인터럽트에 반응한다. 서버 쪽 소켓이 실제로 EOF를 관측했다 — **커넥션 누적은 재현되지 않았다.**

즉 1차의 결론(타임아웃을 지정하라)은 옳고, 근거로 든 실패 메커니즘은 현 구성에서 성립하지 않는다.
**실제 위험은 "지금 새고 있다"가 아니라 "안전성이 암묵적 전제 위에 서 있다"** 이다 — 클래스패스에
Apache HttpClient5 등이 들어와 요청 팩토리가 바뀌면 조용히 무너진다.

### Impact

현재 누수 없음. 잠재적으로는 LLM 지연이 5초 가드를 넘겨 스레드·소켓을 붙잡는 형태.

### Recommendation / 적용한 수정

같은 상한을 HTTP 계층에도 명시해 전제를 코드로 고정했다.

```java
private static ClientHttpRequestFactory requestFactory(LlmSettings settings) {
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(settings.timeout()).build());
    factory.setReadTimeout(settings.timeout());
    return factory;
}
```

---

## M-06. `/api/health` 가 DB 상태를 반영하지 않아 degraded 를 healthy 로 보고한다 — **미수정 (패치 제안)**

### Problem

`HealthController` 는 상수를 반환한다: `{"status":"ok","service":"ventry"}`. DB가 죽어도 200이다.
**C-03 수정 이후 이 사실의 무게가 달라졌다** — 이전에는 컨테이너가 죽어 장애가 드러났지만, 이제는
살아 있으면서 데이터 API만 전부 실패한다.

### Evidence

DB 미가용 컨테이너 실측:
```
GET /api/health       → 200  {"service":"ventry","status":"ok"}
GET /actuator/health  → 503  {"groups":["liveness","readiness"],"status":"DOWN"}
```
`application.yml` 이 이미 actuator health 를 노출하고 있고(`management.endpoints.web.exposure.include: health`)
DB 인디케이터가 정상 동작한다. **필요한 것은 이미 있고, 쓰이지 않을 뿐이다.**
한편 `develop-ci.yml` 스모크는 `/api/health` 를 본다 — **DB 없는 스택도 CI를 통과할 수 있다.**

### Impact

오케스트레이터·CI·심사 스모크가 모두 "정상"이라고 답하는 동안 서비스는 전 기능 실패 상태일 수 있다.

### Recommendation

`/api/health` 는 계약에 명시된 엔드포인트이므로 그대로 두고, **오케스트레이션 probe 만** 옮긴다.

```yaml
# docker-compose.yaml — api 서비스
  api:
    build: ./backend
    restart: unless-stopped                       # C-03 심층 방어
    healthcheck:
      # temurin 이미지에는 curl·wget 이 없다 — bash /dev/tcp 로 상태줄만 확인한다
      test: ["CMD-SHELL", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q '200 OK' <&3"]
      interval: 10s
      timeout: 5s
      retries: 6
      start_period: 30s
```
`develop-ci.yml` 스모크에도 `/actuator/health` 확인을 한 줄 추가한다.

> compose 는 CM 소관이라 **직접 수정하지 않았다.** 위 패치는 그대로 적용 가능하다.

---

# Minor

## N-01. `?v=` 파라미터가 무검증이라 큰 값 하나로 세션의 `/explore` 가 영구 무응답이 된다 — **해소됨 (QA 패스 Q-02)**

`RecommendController`·`ExploreController` 모두 클라이언트가 준 `v` 를 `state.acceptVersion(version)`
(= `max(cur, v)`)에 그대로 넣고 있었다. 되돌릴 방법이 없다.

```
PROBE[explore before]        len=172   ← 정상 송출
GET /api/recommend/{sid}?v=9223372036854775807
PROBE[explore after poison]  len=0     ← plan·insight·done 전부 미송출, 오류도 없음
```

version 취소 규약은 계약 공통 규약이라 이 리뷰에서는 단독 변경하지 않고 권고만 남겼는데,
**같은 날 QA 패스(Q-02)가 `SessionStore.SessionState.acceptVersion` 에 `0 ≤ v ≤ 1_000_000` 검증을
넣어 해소했다.** 현재 트리에서 범위 밖 `v` 는 `400 INVALID_REQUEST` 다.

QA 패스가 함께 밝힌 사실 하나를 남겨 둔다 — 음수 `v` 는 **이벤트 0건인 정상 종료 스트림**을 만들고,
프론트는 그것을 오류로 보아 목 폴백(`mockExplore`)을 켠다. 즉 잘못된 입력이 **지어낸 인사이트**로
화면에 도달하는 경로였다. 상한 검증은 피해를 줄일 뿐 봉인하지는 않으므로(상한 안의 값으로도 같은
독점이 가능하다), 근본 해소인 **version 서버 발급**은 계약 변경 항목으로 남는다.

## N-02. 엔드포인트 레이트리밋이 전혀 없다

C-02 로 세션 수는 묶였지만 요청 수는 묶이지 않았다. 가장 싼 자리는 nginx다.

```nginx
limit_req_zone $binary_remote_addr zone=api:10m rate=20r/s;
location /api/ { limit_req zone=api burst=40 nodelay; ... }
```
SSE 엔드포인트는 장시간 연결이라 성격이 다르므로 `limit_conn` 으로 따로 묶는 편이 낫다.

## N-03. `POST /budget` 에서 `confirmed_budget` 미전송이 0으로 묵과된다 *(1차 패스 P2 — 일부 유지)*

`BudgetRequest.confirmedBudget` 이 원시형 `int`(`ScenarioDtos.java:36`)라 필드가 빠지면 Jackson이 0으로
채우고 음수 검증만 통과한다. B₀=0 세션이 만들어져 이후 화면 전부가 "진입 후보 0곳"으로 정상처럼 흐른다
— D-17(음수 통과)과 같은 계열의 잔여 경로. `Integer` + null 검증이 「미기재와 0은 다른 사건」이라는
`DiagnoseController` 의 기존 판단과도 일관된다. **이 부분은 여전히 남아 있다.**

상한 쪽은 같은 날 QA 패스(Q-01)가 `Amounts.MAX` 검증을 추가해 닫았다(`int` 최댓값 예산이 200으로
통과하던 경로).

덧붙여 `ScenarioController.confirmBudget` 은 본문 검증을 `sessions.get(sid)` **앞에서** 한다.
없는 세션 + 잘못된 예산이면 404가 아니라 400이 나간다 — 자원 존재 여부가 먼저다. 미해소.

## N-04. `/explore` 첫 이벤트(plan)가 LLM 왕복(최대 5s)에 종속된다 *(1차 패스 P2 — 유지)*

`ExploreService.explore` → `plan()` 이 `llm.complete(PlanPrompt.build(...))` 를 동기 호출하고, 그 계산이
끝나야 plan 이벤트가 나간다. 「템플릿 즉시 송출」 원칙은 인사이트 문장에 대한 것이라 계약 위반은 아니며,
폴백 축 선송출안이 FE 합의 대기로 보류된 사실이 코드 주석에 남아 있다. M-01 (a) 를 채택하면 이 경로의
상한도 함께 짧아진다.

## N-05. check-area 응답 `risk_review` 에 계약 §6 예시에 없는 `skipped` 가 실린다 *(1차 패스 P2 — 유지)*

세 경로가 `FinanceDtos.RiskReview`(boolean 2개 — 원시형이라 non_null 생략 불가)를 공유하는데 계약 §6
예시는 `{objection_text, applied}` 만 보여준다. §4에는 정의돼 있어 의미는 문서화돼 있다.
실질 영향 없음(FE는 미지 필드 무시) — 계약 §6 예시에 한 줄 추가하는 문서 정정이면 충분하다.

## N-06. 탐색 축 A2·A3 미구현이 계약 표에 드러나지 않는다 *(1차 패스 P2 — 유지)*

`ExploreService.plan` 이 A2·A3를 제거(assumptions #47)하는 반면 계약 §5 축 표는 4종을 미구현 표시 없이
나열한다. `plan.axes` 에 실리지 않으므로 런타임 불일치는 아니다. 축 표에 각주 한 줄이면 닫힌다.

## N-07. 정적 분석 도구가 없다

`ai/` 는 ruff, `frontend/` 는 eslint 로 CI 게이트가 있는데 `backend/` 만 없다. Checkstyle 이나 SpotBugs
중 하나만 붙여도 이번에 발견한 것과 다른 계열(null 전파·리소스 누수)을 잡는다. `build.gradle` 3줄이다.

## N-08. graceful shutdown 이 없다 *(커넥션 풀 부분은 QA 패스 Q-03 에서 해소)*

`server.shutdown` 미지정 → 즉시 종료(진행 중 요청 절단)다. 다만
`server.shutdown: graceful` 은 열린 SSE 스트림 때문에 종료를 최대 30초 늘리므로,
`spring.lifecycle.timeout-per-shutdown-phase: 5s` 를 함께 지정해야 개발 체감이 나빠지지 않는다.

Hikari 는 같은 날 QA 패스(Q-03)가 `connection-timeout: 3000` · `validation-timeout: 2000` ·
`initialization-fail-timeout: -1` 을 넣어 해소했다 — DB 부재 시 모든 엔드포인트가 기본값 30초를
매달리던 경로다. 특히 `initialization-fail-timeout: -1` 은 C-03 과 같은 방향의 조치다
(기동 시 DB가 없어도 뜨고, 살아난 뒤 스스로 복구한다).

## N-09. `@Cacheable` 이 돌려주는 리스트가 가변이다

`CandidateRepository.findCandidates` 는 `JdbcClient...list()`(가변 `ArrayList`)를 그대로 캐시에 넣고
**같은 인스턴스**가 모든 요청에 공유된다. 현재 호출부는 전부 스트림만 쓰므로 실제 결함은 없다.
다만 누군가 `pool.sort(...)` 를 한 줄 넣는 순간 요청 간 상태 오염이 되고 재현이 매우 어렵다.
반환을 `List.copyOf(...)` 로 감싸면 그 사고 유형이 구조적으로 사라진다(`DbProductSource.all()` 동일).

## N-10. `v_candidate_area` 의 `industry` 필터가 인덱스 접두를 타지 못한다

`location_score` PK 가 `(area_code, industry)` 라 `WHERE industry = ?` 는 접두가 아니다 → seq scan.
**현 규모(업종당 약 1,061행)에서는 무영향**이고 결과도 캐시된다. 후보 그레인이 커지면
`CREATE INDEX ON location_score (industry)` 한 줄로 해소된다.

## N-11. `v_candidate_area` 가 `rent` 를 INNER JOIN 한다 — **추가 검증 필요**

`transit` 은 LEFT JOIN + `fallback_flag` 로 결측을 명시적으로 다루는데 `rent` 는 INNER 다.
임대료 행이 없는 상권은 **후보 목록에서 조용히 사라진다**(오류도, 폴백 플래그도 없다).
현재 덤프에서 실제 누락이 발생하는지는 확인하지 않았다.
`SELECT count(*) FROM location_score s LEFT JOIN rent r USING (area_code) WHERE r.area_code IS NULL;`
로 1분이면 확인된다. 0이 아니라면 `total_count`(랜딩 지표 1,061)의 근거가 흔들린다.

## N-12. `/explore` 인사이트 조립이 O(경계 수 × 후보 수) 다 — 현 규모에서는 문제 없음

`InsightBuilder.build` 가 경계마다 `greenCount`·`nSustain`·`topScore`·`nEntry` 로 후보 전량을 4회 훑는다.
후보 1,061곳 · 경계 836개 합성 데이터 실측 **평균 23ms**(상품 목록 비움 조건). 지금은 전혀 문제가 아니다.
후보가 한 자릿수 배로 늘면 제곱으로 커지므로, 그때 비용순 정렬 + 접두합·이분 탐색으로 O(n log n)이 된다.
**지금 손댈 이유는 없다.**

---

# Good Points

리뷰 중 **바꾸지 말아야 할 것**으로 판단한 것들이다. (1차 패스 관찰을 포함해 통합)

1. **결정적 계층의 순수 함수화가 원칙을 코드로 강제한다.**
   `engine/` 전체가 상태 없는 static 함수 + 단위 테스트이고 Spring 비의존이다.
   `Frontier` 는 정렬 비교만으로 계단 함수를 닫힌 형태로 계산한다(그리드·샘플링 없음).
   `Frontier`·`SustainFilter`·`FundingCheck`·`InsightScore` 어디에도 LLM 진입점이 없다.

2. **LLM 수치 생성 통로가 기계적으로 봉쇄돼 있다.**
   LLM 응답을 파싱하는 곳은 2곳뿐이고, `PlanPrompt.parseAxes` 는 축 코드 화이트리스트만 취한다.
   `RefinePrompt.sanitize` 는 수치 집합 **양방향 일치**(⊆∧⊇), `ReviewPrompt.sanitize` 는 입력 밖 수치를
   거부하며, 비교가 `BigDecimal` **값** 단위라 부분 문자열 통과 구멍이 막혀 있다.
   LLM 산출이 DTO 수치 필드로 들어가는 경로는 전수 검색에서 발견되지 않았다.

3. **LLM 실패가 단일 경로로 수렴한다.**
   키 부재·타임아웃·동시 호출 초과·예외·검증 거부가 전부 `Optional.empty()` 하나로 모여, 호출부에
   "LLM 사용 가능한가?" 분기가 없다. 구현체는 `call()` 하나만 짜면 폴백 규약을 어길 방법이 없다.
   무LLM 스택에서도 전 기능이 동작한다 — 외부 의존성 처리의 모범이다.

4. **필수 고지를 LLM이 지울 수 없는 설계.**
   자격 한정 꼬리를 떼어 본문만 언어화하고 통과문 뒤에 서버가 재부착한다.
   상향 인사이트 단독 노출 금지도 생성 로직 자체에 묶여 있다 — T2가 성립하지 않으면 T1을 만들지 않는다.

5. **SQL 인젝션 노출면이 없고, 쓰기 경로 자체가 없다.**
   전 쿼리가 `JdbcClient.param()` 바인딩이며 문자열 결합이 없다. `INSERT/UPDATE/DELETE`·`@Transactional`
   이 하나도 없어 트랜잭션 경계·락·데드락이 원천적으로 성립하지 않는다. 조회는 소스별 1회 + 캐시라 N+1도 없다.

6. **캐시 경계를 위한 빈 분리 판단이 정확하다.**
   `RiskReviewAgent`/`ReviewGenerator`, `ExploreService`/`RefineGenerator` 를 나눈 이유가 「자기 호출은
   프록시를 우회한다」이고 실측치(2회차 1.3초)와 함께 주석에 남아 있다. 캐시를 `CandidateRepository` 로
   내린 판단, 폴백을 캐시하지 않는 `unless` 설계도 같은 계열의 정확한 판단이다.

7. **계약 타입을 코드가 마지막에 지킨다.**
   `serializableRatio`(비유한값 → 필드 생략), `burdenPhrase`(Infinity → 서술 대체),
   `FundingProductRowMapper` 의 NULL 의미론 보존(0으로 강등 금지), 로케일 고정은 전부
   "데이터가 나빠져도 계약은 안 깨진다" 방향의 방어다.

8. **주석이 결정의 근거와 실측치를 남긴다.**
   대부분이 "무엇을"이 아니라 **"왜 이 선택이 아닌 저 선택인가"** 를 적고, 다수에 실측치와 이슈 번호가
   붙어 있다. 리뷰어가 의도를 재구성하는 비용이 크게 낮았다 — 유지할 가치가 있는 문화다.

9. **actuator 노출이 최소(`health`)이고 `.env` 는 추적 제외이며 키가 로그에 남지 않는다.**
   `OpenAiLlmClient` 는 키를 헤더로만 쓰고 예외 메시지에 싣지 않는다.

10. **테스트 252건 그린 + 자격 판정 골든 테스트(`matching_gold.json`) + 실데이터 계약 게이트(G1~G9).**

---

# Action Items

## P0 — 완료

- [x] **C-01** `GlobalExceptionHandler` 400/404/405/415 핸들러 추가 · 로그 하향 · 내부 타입 노출 차단
      → `common/GlobalExceptionHandler.java` · 회귀 테스트 `common/ErrorContractTest.java` (8건)
- [x] **C-02** `SessionStore` 동시 세션 상한 10,000 + `429 TOO_MANY_SESSIONS`
      → `common/SessionStore.java`, `common/ApiException.java` · 테스트 `common/SessionStoreTest.java` (3건)
- [x] **C-03** `CandidateWarmup` 예열 실패를 기동 실패로 만들지 않음 (DB 순단 시 컨테이너 생존)
      → `serving/CandidateWarmup.java`

## P1 — 잔여

- [ ] **M-01** 동기 API의 LLM 대기 해소 — **(a) 동기 경로 타임아웃 1.2s 분리** 권고, 3인 합의 필요
- [ ] **M-02** SSE 하트비트를 가상 스레드로 이관 + 동시 스트림 상한
- [ ] **M-03** Testcontainers 로 `db` 프로파일 쿼리 검증 1건 추가 (backend-ci 게이트 편입)
- [x] **M-04** 컨테이너 비루트 실행 → `backend/Dockerfile` (uid 10001 실행 확인)
- [x] **M-05** OpenAI 클라이언트 connect/read 타임아웃 명시 → `llm/OpenAiLlmClient.java`
- [ ] **M-06** compose healthcheck·`restart: unless-stopped` + CI 스모크를 `/actuator/health` 로
      — **C-03 과 반드시 함께** (단독 적용 시 degraded 가 숨는다). CM 소관이라 미적용

## P2

- [x] **N-01** `?v=` 범위 검증 — **QA 패스 Q-02 에서 해소.** 근본 해소(version 서버 발급)는 계약 항목
- [ ] **N-02** nginx 레이트리밋 (`limit_req` + SSE `limit_conn`)
- [ ] **N-03** `BudgetRequest.confirmedBudget` 을 `Integer` + null 검증 / 검증 순서를 404 우선으로
      (상한 검증은 QA 패스 Q-01 에서 해소)
- [ ] **N-04** `/explore` plan 폴백 축 선송출 (FE 합의 필요)
- [ ] **N-05** 계약 §6 예시에 `risk_review.skipped` 반영 (문서 정정)
- [ ] **N-06** 계약 §5 축 표에 A2·A3 미구현 각주
- [ ] **N-07** Checkstyle 또는 SpotBugs 를 backend-ci 에 편입
- [ ] **N-08** `server.shutdown: graceful` + `timeout-per-shutdown-phase: 5s`
      (Hikari 타임아웃은 QA 패스 Q-03 에서 해소)
- [ ] **N-09** 캐시 반환 리스트 불변화 (`List.copyOf`)
- [ ] **N-10** 후보 그레인 증가 시 `location_score(industry)` 인덱스
- [ ] **N-11** `rent` INNER JOIN 결측 상권 실측 확인 (**추가 검증 필요**)
- [ ] **N-12** 후보 규모가 한 자릿수 배 늘면 `InsightBuilder` 접두합·이분 탐색 전환
- [ ] `docs/API_CONTRACT.md` §시스템 계약에 `error.code` 목록 표 추가

## 계획된 미완 (예선 스코프에서 현행이 정합)

diagnose LLM 실파싱(`parse_source: "llm"`), refine 전건 언어화 확대, `/recommend` 페이징
(계약 §4 재검토 조건) — 모두 계약·코드 주석에 보류 사유가 명시된 항목이다.

---

# Architecture Score

**82 / 100**

| 항목 | 배점 | 점수 | 근거 |
|---|---:|---:|---|
| Architecture (레이어·DIP·경계) | 20 | 18 | `CandidateSource`/`ProductSource`/`DataMetaSource` + 프로파일 교체가 깔끔하다. engine 이 serving 타입을 모르도록 `SustainInput` 으로 끊은 것도 정확하다. `serving` 이 controller DTO 를 직접 조립하는 결합이 남아 있으나 이 규모에서는 과잉 분리가 더 나쁘다 |
| API 설계·계약 준수 | 15 | 10 | 6종 엔드포인트의 필드·타입·정렬·생략 규칙은 계약과 일치. **오류 상태코드 5종이 계약 위반이었다(C-01)** — 수정했으나 그것이 배포 상태로 남아 있었다는 사실을 반영 |
| Business Logic / Domain | 15 | 15 | 이 프로젝트의 강점. 순수 함수 · 골든 테스트 · 경계 조건 문서화까지 감점 요소를 찾지 못했다 |
| Database | 15 | 13 | 파라미터 바인딩, 조회 1회 원칙, N+1 부재, 인덱스 설계 의도가 주석에 있다. `rent` INNER JOIN 결측 처리(N-11)와 `industry` 인덱스 접두(N-10)는 미확인·미해소 |
| Security | 15 | 10 | SQLi·시크릿 노출 없음, 용어 컴플라이언스 이중 차단. 반면 **root 실행(M-04)·세션 무제한(C-02)·레이트리밋 부재(N-02)**. 인증 부재는 예선 스코프 판단으로 감점하지 않음 |
| Performance / 자원 관리 | 10 | 6 | 캐시 전략·gzip·해석적 계단 함수는 좋다. **워커 스레드 LLM 대기(M-01)** 와 **SSE 자원 상한 부재(M-02)** 가 실서비스 기준 감점 |
| Logging / 운영 | 5 | 3 | 폴백 사유 로깅은 있으나 요청 상관관계(trace id·MDC)·액세스 로그가 없다. 500 로그의 요청 경로 부재는 이번에 수정 |
| Test | 5 | 4 | 252건 그린 · 골든 테스트 · 계약 게이트까지 갖췄다. `db` 프로파일 공백(M-03)만 감점 |
| Infrastructure | 10 | 3 | **기동 복원력 결함(C-03)** 이 최대 감점. 재시작 정책·헬스체크 부재(M-06)도 같은 계열 |

> 1차 패스 점수는 88점이었다. 차이는 **평가 축**에서 온다 — 1차는 계약·스펙 준수를,
> 2차는 운영·경계면을 봤다. 감점이 「경계면·운영」에 몰리고 도메인 로직에는 거의 없다는 분포는
> **설계자가 문제를 깊게 판 반면 배포 후를 덜 봤다**는 뜻이며, 남은 작업량으로는 가장 다루기 쉬운 형태다.

---

# Final Decision

## **Conditional Approve**

### 승인하는 이유

핵심 도메인 로직이 견고하다. 「모든 숫자는 결정적 계산이 만든다」가 문서상의 다짐이 아니라 패키지
구조·순수 함수·검증기로 강제되어 있고, LLM 장애가 서비스 장애가 되지 않도록 폴백이 단일 경로로
수렴한다. 이것은 뒤늦게 바꾸기 가장 어려운 부분이며 이미 옳게 되어 있다.
발견된 P0 3건은 전부 **도메인 밖 경계면**의 문제였고, 세 건 모두 이번 리뷰에서 수정 후
252건 테스트 그린 · 컨테이너 실행 검증까지 마쳤다.

### 조건 (병합·데모 전 처리)

1. **필수 — M-06 을 C-03 과 함께 적용한다.**
   C-03 수정으로 DB 장애 시 컨테이너가 살아남게 되었는데 `/api/health` 는 여전히 200이다.
   헬스체크를 `/actuator/health` 로 옮기지 않으면 **장애가 숨는다.** 이 한 건만은 세트로 처리해야 한다.
2. **필수 — M-01 의 방향을 3인 합의로 정한다.**
   최소 조치인 (a) 동기 경로 타임아웃 분리는 상수 하나 분리이고 실패 시 동작이 기존 폴백과 같다.
   결정을 미루더라도 **결정했다는 사실은 문서에 남겨야** 한다 — 지금은 `SseSupport` 주석과 실제 코드가
   서로 다른 말을 하고 있다.
3. **권고 — M-03(Testcontainers 1건)을 마감 전에 넣는다.**
   AI 배치가 뷰를 다시 구울 예정이라면, 그 변경이 PR 게이트에서 걸리는지가 곧 사고 확률이다.

### Reject 하지 않는 이유

발견된 결함 중 **잘못된 숫자를 사용자에게 보여주는 것은 하나도 없었다.**
판정·비용·경계·조달 계산의 정확성에 영향을 주는 결함은 확인되지 않았고, 용어 컴플라이언스 게이트
(`LlmResponses.BANNED_TERMS` · 양방향 수치 집합 검증)도 정상 동작한다. 문제는 전부
「나쁜 입력·나쁜 환경에 어떻게 반응하는가」였고, 그 성격의 결함은 국소 수정으로 닫힌다.

---

# 부록 A — 이번 리뷰에서 변경한 파일

| 파일 | 변경 | 항목 |
|---|---|---|
| `backend/src/main/java/com/ventry/api/common/GlobalExceptionHandler.java` | 표준 4xx 핸들러 4종 · 로그 하향 · 내부 노출 차단 · 500 로그에 요청 경로 | C-01 |
| `backend/src/main/java/com/ventry/api/common/SessionStore.java` | 동시 세션 상한 + 상한 도달 시 만료 선 정리 (같은 파일의 `MAX_VERSION`·`acceptVersion` 검증은 QA 패스 Q-02) | C-02 |
| `backend/src/main/java/com/ventry/api/common/ApiException.java` | `tooManySessions()` (429) 추가 | C-02 |
| `backend/src/main/java/com/ventry/api/serving/CandidateWarmup.java` | 예열 실패를 기동 실패로 만들지 않음 | C-03 |
| `backend/src/main/java/com/ventry/api/llm/OpenAiLlmClient.java` | HTTP connect/read 타임아웃 명시 | M-05 |
| `backend/Dockerfile` | 비루트 사용자(`ventry`, uid 10001) 실행 | M-04 |
| `backend/src/test/java/com/ventry/api/common/ErrorContractTest.java` | **신규** — 오류 계약 회귀 8건 | C-01 |
| `backend/src/test/java/com/ventry/api/common/SessionStoreTest.java` | **신규** — 세션 상한 3건 | C-02 |

# 부록 B — 검증 로그

모든 검증은 2026-07-29 `/Users/yutak/Desktop/Ventry` 워킹트리에서 수행했다.

1. **테스트 전량 재실행** — `cd backend && ./gradlew clean test` (캐시 통과는 증거가 아니므로 clean 강제).
   내 수정만 반영된 시점 집계 **tests=252, failures=0, errors=0**(35개 결과 파일, 중첩 테스트 클래스 XML 포함),
   그중 11건이 이번에 추가한 회귀 테스트다. 이후 QA·FE 패스의 동시 수정이 합쳐진 최종 트리에서
   다시 전량 재실행 → **tests=260, failures=0, errors=0**.
2. **오류 상태코드 프로브** — MockMvc 로 7개 요청의 실제 상태코드·해결된 예외 타입을 출력.
   수정 전 5건이 500이었고, 수정 후 `ErrorContractTest` 8건이 400/404/405/415/404 를 단언한다.
3. **무응답 업스트림 프로브** — 로컬 `ServerSocket` 이 요청을 수락하고 응답하지 않는 상태에서
   `OpenAiLlmClient.complete()` 호출. `elapsedMs=5001` · `empty=true` · **`socketReleased=true`**.
   요청 팩토리는 `JdkClientHttpRequestFactory` 로 확인 — 1차 패스의 「인터럽트가 소켓을 끊지 못한다」는
   근거를 반증한다(M-05).
4. **탐색 규모 프로브** — 합성 후보 1,061곳(경계 836개)에서 `InsightBuilder.build` 평균 **23ms**
   (3회 측정, 워밍업 1회 후, 상품 목록 비움 조건). O(경계×후보) 이지만 현 규모에서는 문제 아님(N-12).
5. **버전 오염 프로브** — `/api/recommend?v=Long.MAX_VALUE` 이후 `/api/explore?v=2` 응답 길이
   **172 → 0 바이트**(오류 없음). N-01의 근거.
6. **컨테이너 검증** — `docker build ./backend` 성공.
   `docker run --entrypoint sh` 로 `uid=10001(ventry)` 확인(M-04).
   DB 미가용 기동: 수정 전 `exited exit=1`(약 4초), 수정 후 `running` + `/api/health` 200 +
   예열 실패 WARN 1건(C-03). 같은 조건에서 `/actuator/health` 는 **503 DOWN** 확인(M-06 근거).
7. **쓰기 경로 확인** — `grep -rn "Transactional|INSERT|UPDATE |DELETE " backend/src/main/java`
   **매치 0건**. 트랜잭션·락·데드락 검토 대상 없음.

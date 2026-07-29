# build 단계 — Gradle wrapper 사용 (로컬 IDE·CI·Docker가 동일한 Gradle 9.5.1로 빌드)
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
# 배포판+의존성 레이어 캐시 (빌드 파일 변경 시에만 무효화)
RUN ./gradlew dependencies --no-daemon -q || true
COPY src ./src
RUN ./gradlew build --no-daemon

# run 단계
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
# 비루트 실행 (BE 리뷰 2026-07-29 M-04). 기본값은 root 이고, 그 상태로는 컨테이너 안에서의
# 임의 코드 실행이 곧 루트 권한이 된다 — 애플리케이션은 8080 바인딩 외에 특권이 필요 없다.
# 소유권을 옮겨 두어야 읽기 전용 파일시스템·볼륨 마운트 조합에서도 그대로 뜬다.
RUN useradd --system --uid 10001 --create-home --shell /usr/sbin/nologin ventry \
    && chown -R ventry:ventry /app
USER ventry
# 컨테이너 실행 시에만 DB 프로파일 활성화 — 사전 적재 테이블(v_candidate_area) 조회 경로.
# 빌드 단계의 테스트는 이 ENV 없이 돌아 기본 프로파일(픽스처)로 실행된다 (BE-05 5-B).
# 로컬 개발/CI의 gradlew test 도 이 값을 주입받지 않으므로 영향이 없다.
ENV SPRING_PROFILES_ACTIVE=db
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

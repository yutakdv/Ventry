# build 단계 (gradle wrapper 없이 공식 이미지 사용 — 테스트 포함 빌드)
# Spring Boot 4.x는 Gradle 8.14+ / 9.x 필요
FROM gradle:9-jdk25 AS build
WORKDIR /app
COPY build.gradle settings.gradle ./
# 의존성 레이어 캐시 (실패해도 무시 — 소스 복사 후 본빌드에서 해결)
RUN gradle dependencies --no-daemon -q || true
COPY src ./src
RUN gradle build --no-daemon

# run 단계
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

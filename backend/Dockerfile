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
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

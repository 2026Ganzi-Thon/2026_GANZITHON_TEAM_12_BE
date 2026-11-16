# syntax=docker/dockerfile:1
# Come & Down 백엔드 실행 이미지. 테스트는 CI(.github/workflows/ci.yml)에서 돌리므로 여기서는 jar만 만든다.

# ---- 빌드 ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# 의존성 계층을 소스와 분리해 소스만 바뀌면 다시 내려받지 않게 한다.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
# Windows에서 체크아웃하면 gradlew 줄바꿈이 CRLF일 수 있어 LF로 맞춘다.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null

COPY src src
RUN ./gradlew --no-daemon bootJar -x test \
    && cp "$(ls build/libs/*.jar | grep -v -- '-plain\.jar$' | head -n 1)" /workspace/app.jar

# ---- 실행 ----
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home comedown
WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar

# 컨테이너 메모리 한도에 맞춰 힙을 잡는다.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
# 프로필을 지정하지 않는다: local 전용 기능(/api/dev/**, 날짜 이동 시계, 데모 시드)은 켜지지 않는다.
# DB·구글·암호화 키는 환경 변수로 넣는다(docs/배포_가이드.md).
EXPOSE 8080
USER comedown
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

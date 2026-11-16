# 치지직 서버 이미지 (5단계). 로컬 JDK 없이 Docker 만으로 빌드합니다.
#   docker build -t chzzk:local .
# 실행 단계는 Java 17 JRE. 기본 프로필(H2·로컬 폴더)로도 뜨지만, 클러스터에서는 SPRING_PROFILES_ACTIVE=cluster 로 실행합니다.

# ---- 1단계: bootJar 빌드 ----
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
# 의존성 캐시: 빌드 스크립트가 바뀌지 않으면 소스만 바꿔도 의존성을 다시 받지 않음
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
# 실행 jar 이름은 build.gradle 에서 chzzk.jar 로 고정 (빌드가 실패하면 여기서 멈춤)
RUN ./gradlew --no-daemon -q bootJar -x test
RUN cp build/libs/chzzk.jar /src/app.jar

# ---- 2단계: 실행 ----
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10001 --home-dir /app chzzk \
    && mkdir -p /app/data /data/uploads \
    && chown -R chzzk /app /data
COPY --from=build /src/app.jar /app/app.jar
USER chzzk
ENV DATA_DIR=/app/data \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
# exec 형식: java 가 PID 1 이 되어 SIGTERM 을 직접 받고 graceful shutdown 을 진행
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

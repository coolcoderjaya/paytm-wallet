# syntax=docker/dockerfile:1.7
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --create-home app

WORKDIR /app
COPY --from=build --chown=app:app /workspace/target/wallet-r2-0.0.1-SNAPSHOT.jar /app/wallet.jar
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=5 \
  CMD curl --fail --silent http://localhost:8080/health || exit 1
ENTRYPOINT ["java", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/wallet.jar"]

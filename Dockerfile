# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# Resolve dependencies in their own layer so code changes do not re-download them.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon installDist -x test

FROM eclipse-temurin:21-jre
RUN groupadd --system ledger && useradd --system --gid ledger ledger
WORKDIR /app
COPY --from=build /src/build/install/payments-ledger /app
USER ledger
# Cloud Run injects PORT; default for local runs.
ENV PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -XX:+ZGenerational"
EXPOSE 8080
ENTRYPOINT ["/app/bin/payments-ledger"]

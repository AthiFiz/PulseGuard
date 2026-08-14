# PulseGuard Notification Service — Kafka consumer and email delivery.
#
# Built from this directory as context, so the service stays an independent
# Maven project with its own wrapper. There is no shared backend build parent,
# and this file deliberately duplicates a small pattern rather than inventing
# one: each service must be deployable on its own.
#
# This image runs no Flyway. The schema belongs to the Control API, and this
# service only validates its JPA mappings against it.

# ---------------------------------------------------------------------------
# Build stage — full JDK, Maven wrapper, no tests
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /build

# The wrapper and the POM arrive first so that a source-only edit reuses the
# resolved dependency layer instead of re-reading the POM from scratch.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw

COPY src/ src/

# The test suites are the quality gate on a developer machine and later in CI,
# where a failure is visible and actionable. Re-running them inside every image
# build would add minutes to `docker compose build` and report a broken test as
# a Docker build error, which is a much worse place to read it.
#
# The BuildKit cache mount keeps ~/.m2 across builds without baking it into a
# layer, so nothing from Maven's cache reaches the runtime image.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B package -DskipTests

# ---------------------------------------------------------------------------
# Runtime stage — JRE only
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime

# curl is the health probe. Compose calls /actuator/health through it, and a
# probe binary that is missing from the image is a probe that always fails.
#
# Note that this service's health endpoint deliberately says nothing about
# SMTP: an unreachable mail server is a survivable condition here, and the
# probe must not restart a process that is doing its job correctly.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Nothing this application does needs root.
RUN groupadd --system --gid 1001 pulseguard \
    && useradd --system --uid 1001 --gid pulseguard --no-create-home pulseguard

WORKDIR /app

# Only the Spring Boot jar crosses the stage boundary — no source, no Maven
# cache, no test classes, no JDK compiler.
COPY --from=build --chown=pulseguard:pulseguard /build/target/notification-service-*.jar app.jar

USER pulseguard

EXPOSE 8082

# The JVM reads the container's memory limit on its own; this just stops it
# claiming the whole allowance and leaving nothing for the rest of the process.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]

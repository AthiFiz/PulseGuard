# PulseGuard Monitor Worker — health checking, incident lifecycle, outbox
# producer.
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
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# This is the one service that makes outbound HTTP requests to addresses its
# users supply, which makes running it unprivileged worth more here than
# anywhere else in the stack.
RUN groupadd --system --gid 1001 pulseguard \
    && useradd --system --uid 1001 --gid pulseguard --no-create-home pulseguard

WORKDIR /app

# Only the Spring Boot jar crosses the stage boundary — no source, no Maven
# cache, no test classes, no JDK compiler.
COPY --from=build --chown=pulseguard:pulseguard /build/target/monitor-worker-*.jar app.jar

USER pulseguard

EXPOSE 8081

# The JVM reads the container's memory limit on its own; this just stops it
# claiming the whole allowance and leaving nothing for the rest of the process.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]

# syntax=docker/dockerfile:1

# --- build stage: compile and package the jar with JDK 17 -----------------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# Cache dependencies first: resolve against pom before copying source.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

# Build the jar. Tests run separately in CI, so skip them here for a faster image build.
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

# --- runtime stage: small JRE image runs the jar -------------------------------
FROM eclipse-temurin:17-jre
WORKDIR /app

# Run as a non-root user.
RUN useradd --system --uid 1001 spring
USER spring

COPY --from=build /workspace/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B verify

FROM node:22-jammy AS meshopt-node

FROM eclipse-temurin:21-jre-jammy
RUN groupadd --system twin && useradd --system --gid twin twin
WORKDIR /app
COPY --from=build /build/target/backend-0.1.0.jar /app/backend.jar
COPY --from=meshopt-node /usr/local/bin/node /usr/local/bin/node
COPY meshopt/worker.mjs /app/meshopt/worker.mjs
USER twin
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"
ENV TWIN_MESHOPT_WORKER=/app/meshopt/worker.mjs
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/backend.jar"]

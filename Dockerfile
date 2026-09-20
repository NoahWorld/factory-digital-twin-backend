FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B verify

FROM eclipse-temurin:21-jre-jammy
RUN groupadd --system twin && useradd --system --gid twin twin
WORKDIR /app
COPY --from=build /build/target/backend-0.1.0.jar /app/backend.jar
USER twin
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/backend.jar"]

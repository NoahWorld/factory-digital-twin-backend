FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B verify

FROM node:22-bookworm-slim AS meshopt-node
FROM eclipse-temurin:21-jre-jammy AS java-runtime

FROM python:3.11-slim-bookworm AS engineering-runtime
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 libatomic1 fontconfig fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*
COPY engineering/requirements.txt /opt/engineering-requirements.txt
ARG PIP_INDEX_URL=https://pypi.org/simple
RUN pip install --no-cache-dir --require-hashes --only-binary=:all: -r /opt/engineering-requirements.txt \
    && python -c "import ifcopenshell, ezdxf, trimesh, shapely, numpy, mapbox_earcut"
COPY --from=java-runtime /opt/java/openjdk /opt/java/openjdk
COPY --from=meshopt-node /usr/local/bin/node /usr/local/bin/node
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="/opt/java/openjdk/bin:${PATH}"

FROM engineering-runtime
RUN groupadd --system twin && useradd --system --gid twin twin
WORKDIR /app
COPY --from=build /build/target/backend-0.1.0.jar /app/backend.jar
COPY meshopt/worker.mjs /app/meshopt/worker.mjs
COPY engineering /app/engineering
USER twin
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"
ENV TWIN_MESHOPT_WORKER=/app/meshopt/worker.mjs
ENV TWIN_ENGINEERING_PYTHON=/usr/local/bin/python3
ENV TWIN_ENGINEERING_WORKER=/app/engineering/convert.py
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 XDG_CACHE_HOME=/tmp
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/backend.jar"]

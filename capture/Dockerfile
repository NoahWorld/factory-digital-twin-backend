# Build on an Internet-connected linux/amd64 machine; the result runs offline.
# GPU is NOT needed to compile; CUDA_ARCH_LIST must cover the eventual GPU.
FROM maven:3.9.11-eclipse-temurin-21 AS java-build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B clean verify

FROM nvidia/cuda:12.4.1-devel-ubuntu22.04 AS native-build
ARG DEBIAN_FRONTEND=noninteractive
ARG CUDA_ARCH_LIST="7.5;8.0;8.6;8.9;9.0"
ENV TORCH_CUDA_ARCH_LIST=${CUDA_ARCH_LIST} MAX_JOBS=4 BUILD_NO_CUDA=0
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates git build-essential cmake ninja-build python3 python3-dev python3-venv \
    libboost-program-options-dev libboost-graph-dev libboost-system-dev libeigen3-dev libflann-dev liblz4-dev \
    libfreeimage-dev libmetis-dev libgoogle-glog-dev libsqlite3-dev libglew-dev \
    libceres-dev libsuitesparse-dev \
    && rm -rf /var/lib/apt/lists/*
RUN git clone --depth 1 --branch 3.11.1 https://github.com/colmap/colmap.git /src/colmap \
    && cmake -S /src/colmap -B /src/colmap/build -GNinja \
       -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/opt/colmap \
       -DGUI_ENABLED=OFF -DCUDA_ENABLED=OFF -DOPENGL_ENABLED=OFF -DTESTS_ENABLED=OFF \
       -DCGAL_ENABLED=OFF -DLSD_ENABLED=OFF \
    && cmake --build /src/colmap/build -j 4 \
    && cmake --install /src/colmap/build
RUN python3 -m venv /opt/capture-venv
ENV PATH=/opt/capture-venv/bin:${PATH}
COPY capture/requirements.txt /build/requirements.txt
RUN pip install --no-cache-dir pip==25.1.1 setuptools==80.9.0 wheel==0.45.1 \
    && pip install --no-cache-dir torch==2.5.1 --index-url https://download.pytorch.org/whl/cu124 \
    && pip install --no-cache-dir -r /build/requirements.txt
RUN git clone --depth 1 --branch v1.5.3 --recurse-submodules --shallow-submodules \
      https://github.com/nerfstudio-project/gsplat.git /src/gsplat \
    && pip wheel --no-deps --no-build-isolation /src/gsplat --wheel-dir /wheels \
    && pip install --no-deps /wheels/gsplat-1.5.3-*.whl \
    && python -c "import torch; from gsplat import csrc; print('Precompiled gsplat extension:', csrc.__file__)" \
    && mkdir -p /opt/capture-build \
    && pip freeze --all > /opt/capture-build/python-freeze.txt \
    && dpkg-query -W > /opt/capture-build/build-debian-packages.txt \
    && git -C /src/colmap rev-parse HEAD > /opt/capture-build/colmap-revision.txt \
    && git -C /src/gsplat rev-parse HEAD > /opt/capture-build/gsplat-revision.txt \
    && cp /src/colmap/COPYING.txt /opt/capture-build/COLMAP-LICENSE.txt \
    && cp /src/gsplat/LICENSE /opt/capture-build/GSPLAT-LICENSE.txt \
    && printf '%s\n' "$TORCH_CUDA_ARCH_LIST" > /opt/capture-build/cuda-architectures.txt

FROM eclipse-temurin:21-jre-jammy AS java-runtime
FROM nvidia/cuda:12.4.1-runtime-ubuntu22.04
ARG DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 ffmpeg libboost-program-options1.74.0 libboost-graph1.74.0 libboost-system1.74.0 libfreeimage3 \
    libgoogle-glog0v5 libceres2 libmetis5 libsqlite3-0 libglew2.2 libflann1.9 libgomp1 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system twin && useradd --system --gid twin twin \
    && mkdir /work && chown twin:twin /work
COPY --from=native-build /opt/colmap /opt/colmap
COPY --from=native-build /opt/capture-venv /opt/capture-venv
COPY --from=native-build /opt/capture-build /opt/capture-build
COPY --from=java-runtime /opt/java/openjdk /opt/java/openjdk
COPY --from=java-build /build/target/backend-0.1.0.jar /app/backend.jar
COPY capture /app/capture
ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:/opt/capture-venv/bin:/opt/colmap/bin:${PATH} \
    LD_LIBRARY_PATH=/opt/colmap/lib:/usr/local/cuda/lib64:${LD_LIBRARY_PATH} \
    TWIN_CAPTURE_PYTHON=/opt/capture-venv/bin/python \
    TWIN_CAPTURE_WORKER=/app/capture/worker.py \
    PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 QT_QPA_PLATFORM=offscreen \
    OMP_NUM_THREADS=4 OPENBLAS_NUM_THREADS=4 XDG_CACHE_HOME=/tmp CUDA_CACHE_PATH=/tmp/cuda-cache \
    PIP_NO_INDEX=1 NVIDIA_VISIBLE_DEVICES=all NVIDIA_DRIVER_CAPABILITIES=compute,utility \
    JAVA_TOOL_OPTIONS="-Xmx512m -XX:+ExitOnOutOfMemoryError"
# No compiler, source downloads, pip install, or startup compilation in runtime.
RUN /opt/capture-venv/bin/python -m unittest discover -s /app/capture -p 'test_*.py' \
    && /opt/colmap/bin/colmap -h >/dev/null \
    && test ! -x /usr/local/cuda/bin/nvcc
USER twin
WORKDIR /app
ENTRYPOINT ["java", "-jar", "/app/backend.jar"]

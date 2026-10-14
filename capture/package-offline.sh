#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 2 ]]; then
  echo 'Usage: capture/package-offline.sh IMAGE_TAG OUTPUT_DIRECTORY' >&2
  exit 2
fi
capture_image=$1
capture_output=$2
capture_root=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$capture_output"
capture_output=$(cd "$capture_output" && pwd)
if [[ -e "$capture_output/capture-image.tar" ]]; then
  echo 'Output already contains capture-image.tar; choose a new directory.' >&2
  exit 2
fi
docker build --platform linux/amd64 -f "$capture_root/capture/Dockerfile" \
  --build-arg "CUDA_ARCH_LIST=${CAPTURE_CUDA_ARCH_LIST:-7.5;8.0;8.6;8.9;9.0}" \
  -t "$capture_image" "$capture_root"
docker image inspect "$capture_image" > "$capture_output/image-inspect.json"
docker save -o "$capture_output/capture-image.tar" "$capture_image"
if command -v sha256sum >/dev/null 2>&1; then
  (cd "$capture_output" && sha256sum capture-image.tar > capture-image.tar.sha256)
else
  (cd "$capture_output" && shasum -a 256 capture-image.tar > capture-image.tar.sha256)
fi
cp "$capture_root/capture/load-offline.sh" "$capture_output/load-offline.sh"
cp "$capture_root/capture/accept-gpu.sh" "$capture_output/accept-gpu.sh"
cp "$capture_root/capture/README.md" "$capture_output/README.md"
printf '%s\n' "$capture_image" > "$capture_output/image-tag.txt"
echo 'Image packaged. GPU execution and real-capture quality are NOT verified by packaging.'

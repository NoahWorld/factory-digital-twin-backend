#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 3 ]]; then
  echo 'Usage: capture/accept-gpu.sh IMAGE_TAG INPUT_DIRECTORY NEW_OUTPUT_DIRECTORY' >&2
  echo 'INPUT_DIRECTORY must contain manifest.json with relative source paths and a real consented capture.' >&2
  exit 2
fi
capture_image=$1
capture_input=$(cd "$2" && pwd)
capture_output=$3
test -f "$capture_input/manifest.json"
if [[ -e "$capture_output" ]]; then
  echo 'Output directory already exists; choose a new path.' >&2
  exit 2
fi
mkdir -p "$capture_output"
capture_output=$(cd "$capture_output" && pwd)
docker run --rm --network none --gpus all --read-only \
  --user "$(id -u):$(id -g)" --cap-drop ALL --security-opt no-new-privileges \
  --memory "${CAPTURE_MEMORY_LIMIT:-16g}" --cpus 4 --pids-limit 256 --shm-size 1g \
  --tmpfs /tmp:rw,nosuid,nodev,size=512m \
  --mount "type=bind,src=$capture_input,dst=/input,readonly" \
  --mount "type=bind,src=$capture_output,dst=/output" \
  --entrypoint /opt/capture-venv/bin/python "$capture_image" /app/capture/worker.py \
  --input /input/manifest.json --output /output
test -s "$capture_output/result.json"
test -s "$capture_output/scene.ply"
echo 'Real GPU reconstruction finished. Import scene.ply with result.json and inspect landmarks, camera orientation, holes and motion in the browser before accepting visual quality.'

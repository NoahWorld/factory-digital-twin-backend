#!/usr/bin/env bash
set -euo pipefail
capture_bundle=$(cd "$(dirname "$0")" && pwd)
cd "$capture_bundle"
sha256sum -c capture-image.tar.sha256
docker load -i capture-image.tar
capture_image=$(cat image-tag.txt)
# Host must already have the compatible NVIDIA driver and Container Toolkit.
capture_probe=$(docker run --rm --network none --gpus all --read-only \
  --tmpfs /tmp:rw,nosuid,nodev,size=256m --memory 2g --cpus 2 --pids-limit 128 \
  --entrypoint /opt/capture-venv/bin/python "$capture_image" /app/capture/worker.py --probe)
printf '%s\n' "$capture_probe"
if ! printf '%s\n' "$capture_probe" | python3 -c 'import json,sys; sys.exit(0 if json.load(sys.stdin).get("available") is True else 1)'; then
  echo 'GPU availability check failed; do not enable capture service.' >&2
  exit 1
fi
echo 'Offline GPU probe passed. Complete a real-capture reconstruction before production acceptance.'

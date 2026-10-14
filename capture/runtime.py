"""Local-only subprocesses, image validation, and COLMAP model interpretation."""
from __future__ import annotations

import hashlib
import json
import math
import os
from pathlib import Path
import selectors
import signal
import subprocess
import time
import warnings

from core import (CaptureError, IMAGE_FORMATS, MAX_EDGE, MAX_IMAGE_PIXELS, MAX_IMAGES,
                  MAX_VIDEO_PIXELS, MAX_VIDEO_SECONDS, MAX_WORK_BYTES, MIN_IMAGES,
                  fail, finite, validate_quality)


class Execution:
    def __init__(self, output: Path, timeout_seconds=10800):
        self.output = output
        self.deadline = time.monotonic() + timeout_seconds
        # The clock epoch is unspecified (macOS Python 3.9 starts near zero).
        # Always check the initial workspace before applying the 5-second throttle.
        self.last_disk_check = -math.inf
        self.stage = "validating"
        self.log_bytes = 0
        self.log = (output / "worker.log").open("wb")
        self.child = None

    def close(self):
        self.log.close()

    def report_failure(self):
        """Java retains stderr after deleting the ephemeral task workspace."""
        import sys
        try:
            self.log.flush()
            with (self.output / "worker.log").open("rb") as stream:
                stream.seek(0, os.SEEK_END)
                stream.seek(max(0, stream.tell() - 8192))
                lines = stream.read(8192).decode("utf-8", errors="replace").splitlines()[-40:]
            for line in lines:
                # At most 1,000 codepoints: JSON escaping still fits Java's 16 KiB line cap.
                print(json.dumps({"diagnosticTail": True, "stage": self.stage, "message": line[-1000:]}, ensure_ascii=False), file=sys.stderr, flush=True)
        except OSError as exc:
            print(f"Failed to preserve native diagnostic tail: {type(exc).__name__}: {exc}", file=sys.stderr, flush=True)

    def progress(self, stage, completed=None, total=None):
        self.stage = stage
        # 0/0 explicitly means indeterminate, never an invented completion ratio.
        message = {"stage": stage, "completed": 0 if completed is None else completed, "total": 0 if total is None else total}
        print(json.dumps(message), flush=True)
        self.check()

    def check(self):
        now = time.monotonic()
        if now > self.deadline:
            fail("timeout", "重建超过 3 小时处理上限，请缩小采集范围。")
        if now - self.last_disk_check > 5:
            total = 0
            files = 0
            for root, dirs, names in os.walk(self.output, followlinks=False):
                for name in names:
                    item = Path(root) / name
                    if item.is_symlink():
                        fail("resource_limit", "任务工作目录包含非法符号链接。")
                    total += item.stat().st_size
                    files += 1
                    if total > MAX_WORK_BYTES or files > 5000:
                        fail("resource_limit", "重建工作目录超过资源上限，请缩小采集范围。")
            self.last_disk_check = now

    def run(self, command, *, timeout=1800, error="reconstruction_failed", capture=False):
        """No shell; output/timeout/disk bounded. Children die when Java cancels worker."""
        self.log.write((json.dumps({"stage": self.stage, "command": command}, ensure_ascii=False) + "\n").encode())
        self.log.flush()
        env = {**os.environ, "QT_QPA_PLATFORM": "offscreen", "OMP_NUM_THREADS": "4", "OPENBLAS_NUM_THREADS": "4"}
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env, start_new_session=True)
        self.child = process
        result = bytearray()
        stage_deadline = min(self.deadline, time.monotonic() + timeout)
        selector = selectors.DefaultSelector()
        selector.register(process.stdout, selectors.EVENT_READ)
        last_heartbeat = time.monotonic()
        try:
            while selector.get_map():
                for key, _ in selector.select(timeout=1):
                    chunk = os.read(key.fileobj.fileno(), 8192)
                    if not chunk:
                        selector.unregister(key.fileobj)
                        continue
                    self.log_bytes += len(chunk)
                    if self.log_bytes > 16 * 1024 * 1024:
                        fail("resource_limit", "重建诊断输出超过上限，已停止任务。")
                    self.log.write(chunk)
                    self.log.flush()
                    if capture:
                        result.extend(chunk)
                        if len(result) > 1024 * 1024:
                            fail("resource_limit", "媒体元数据超过处理上限。")
                self.check()
                if time.monotonic() > stage_deadline:
                    fail("timeout", "当前重建阶段处理超时，请缩小采集范围。")
                if time.monotonic() - last_heartbeat >= 10:
                    self.progress(self.stage)
                    last_heartbeat = time.monotonic()
            code = process.wait(timeout=5)
            if code != 0:
                fail(error, f"{self.stage} 阶段执行失败，诊断记录已保存。")
            return result.decode("utf-8", errors="strict") if capture else None
        finally:
            selector.close()
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            process.stdout.close()
            self.child = None

    def cancel(self, signum, frame):
        if self.child is not None and self.child.poll() is None:
            os.killpg(self.child.pid, signal.SIGKILL)
        fail("timeout", "任务已被取消或工作进程停止。")


def normalize_images(sources, images_dir: Path, execution: Execution, *, extracted=False):
    from PIL import Image, ImageOps, UnidentifiedImageError
    import numpy as np

    Image.MAX_IMAGE_PIXELS = MAX_IMAGE_PIXELS
    images_dir.mkdir()
    hashes = set()
    metrics = []
    for index, source in enumerate(sources):
        execution.progress("validating", index, len(sources))
        try:
            with warnings.catch_warnings():
                warnings.simplefilter("error", Image.DecompressionBombWarning)
                with Image.open(source["path"]) as original:
                    expected = "PNG" if extracted else IMAGE_FORMATS[Path(source["filename"]).suffix.lower()]
                    if original.format != expected or getattr(original, "n_frames", 1) != 1:
                        fail("image_decode_failed", f"第 {index + 1} 张图片格式不符或为动画。")
                    if original.width * original.height > MAX_IMAGE_PIXELS or min(original.size) < 360 or max(original.size) < 480:
                        fail("image_quality_insufficient", f"第 {index + 1} 张图片需至少 480×360 像素，且不超过 4000 万像素。")
                    original.load()
                    image = ImageOps.exif_transpose(original)
                    if "A" not in image.getbands() and "transparency" in image.info:
                        image = image.convert("RGBA")
                    if "A" in image.getbands() and image.getchannel("A").getextrema()[0] != 255:
                        fail("image_quality_insufficient", f"第 {index + 1} 张图片包含透明区域，请使用原始现场照片。")
                    image = image.convert("RGB")
                    image.thumbnail((MAX_EDGE, MAX_EDGE), Image.Resampling.LANCZOS)
                    digest = hashlib.sha256(image.tobytes()).digest()
                    if digest in hashes:
                        fail("image_quality_insufficient", f"第 {index + 1} 张图片与已有视角完全重复。")
                    hashes.add(digest)
                    gray_image = image.convert("L")
                    gray_image.thumbnail((768, 768), Image.Resampling.LANCZOS)
                    gray = np.asarray(gray_image, dtype=np.float32)
                    laplacian = (gray[:-2, 1:-1] + gray[2:, 1:-1] + gray[1:-1, :-2] + gray[1:-1, 2:] - 4 * gray[1:-1, 1:-1])
                    sharpness = float(laplacian.var())
                    if sharpness < 12:
                        fail("image_quality_insufficient", f"第 {index + 1} 张图片清晰度或纹理不足，请重新拍摄。")
                    filename = f"image_{index:04d}.jpg"
                    image.save(images_dir / filename, format="JPEG", quality=95)
                    metrics.append({"filename": filename, "source": source["filename"], "sharpness": sharpness, "width": image.width, "height": image.height})
        except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError, Image.DecompressionBombWarning) as exc:
            raise CaptureError("image_decode_failed", f"第 {index + 1} 张图片无法安全解码。") from exc
    execution.progress("validating", len(sources), len(sources))
    return metrics


def extract_video(source, directory: Path, execution: Execution):
    execution.progress("extracting")
    metadata = execution.run(["ffprobe", "-v", "error", "-protocol_whitelist", "file,pipe", "-format_whitelist", "mov,matroska,webm", "-show_entries", "format=duration:stream=codec_type,width,height,avg_frame_rate", "-of", "json", str(source["path"])], timeout=60, error="video_invalid", capture=True)
    try:
        value = json.loads(metadata)
        videos = [s for s in value["streams"] if s["codec_type"] == "video"]
        if len(videos) != 1:
            fail("video_invalid", "视频必须只有一个画面轨道。")
        stream = videos[0]
        width, height = int(stream["width"]), int(stream["height"])
        duration = float(value["format"]["duration"])
        numerator, denominator = stream["avg_frame_rate"].split("/")
        fps = float(numerator) / float(denominator)
    except (KeyError, TypeError, ValueError, ZeroDivisionError, json.JSONDecodeError) as exc:
        raise CaptureError("video_invalid", "视频时长、尺寸或帧率信息无效。") from exc
    if not finite([duration, fps]) or not 4 <= duration <= MAX_VIDEO_SECONDS or not 1 <= fps <= 60:
        fail("video_invalid", "视频需为 4 秒至 10 分钟，帧率 1–60 fps。")
    if width * height > MAX_VIDEO_PIXELS or min(width, height) < 360 or max(width, height) > 4096:
        fail("video_invalid", "视频短边至少 360 像素，画面像素总量不超过 3840×2160。")
    count = min(MAX_IMAGES, math.floor(duration * min(2, fps)))
    if count < MIN_IMAGES:
        fail("video_invalid", "视频有效帧不足 8 帧。")
    directory.mkdir()
    filter_spec = f"fps={count / duration:.10f},scale={MAX_EDGE}:{MAX_EDGE}:force_original_aspect_ratio=decrease"
    execution.run(["ffmpeg", "-nostdin", "-v", "error", "-threads", "4", "-protocol_whitelist", "file,pipe", "-format_whitelist", "mov,matroska,webm", "-i", str(source["path"]), "-map", "0:v:0", "-an", "-sn", "-dn", "-t", str(duration), "-vf", filter_spec, "-frames:v", str(count), "-threads", "4", str(directory / "frame_%04d.png")], timeout=600, error="video_invalid")
    paths = sorted(directory.glob("frame_*.png"))
    if not MIN_IMAGES <= len(paths) <= MAX_IMAGES:
        fail("video_invalid", "视频未能提取到 8–120 张有效画面。")
    execution.progress("extracting", len(paths), count)
    return [{"path": p, "filename": p.name} for p in paths]


def text_lines(path: Path, max_bytes=256 * 1024 * 1024):
    if path.stat().st_size > max_bytes:
        fail("resource_limit", "相机重建数据超过读取上限。")
    with path.open(encoding="utf-8") as stream:
        for line in stream:
            if len(line) > 2 * 1024 * 1024:
                fail("resource_limit", "相机特征记录超过读取上限。")
            if not line.startswith("#"):
                yield line.strip()


def read_colmap(directory: Path):
    import numpy as np

    cameras = {}
    for line in text_lines(directory / "cameras.txt", 1024 * 1024):
        if not line:
            continue
        parts = line.split()
        if len(parts) != 8 or parts[1] != "PINHOLE":
            fail("reconstruction_failed", "去畸变后相机模型不是 PINHOLE。")
        camera_id, width, height = int(parts[0]), int(parts[2]), int(parts[3])
        fx, fy, cx, cy = map(float, parts[4:])
        if not finite([fx, fy, cx, cy]) or min(fx, fy) <= 0 or min(width, height) < 1 or max(width, height) > MAX_EDGE:
            fail("reconstruction_failed", "相机内参数值或图像尺寸无效。")
        cameras[camera_id] = {"K": np.array([[fx, 0, cx], [0, fy, cy], [0, 0, 1]], dtype=np.float32), "width": width, "height": height}
    views = []
    lines = iter(text_lines(directory / "images.txt"))
    for line in lines:
        if not line:
            continue
        parts = line.split()
        if len(parts) != 10:
            fail("reconstruction_failed", "相机位姿记录无效。")
        q = np.array(list(map(float, parts[1:5])), dtype=np.float64)
        translation = np.array(list(map(float, parts[5:8])), dtype=np.float64)
        name, camera_id = parts[9], int(parts[8])
        if not name.startswith("image_") or Path(name).name != name or camera_id not in cameras or not np.isfinite(q).all() or not np.isfinite(translation).all() or abs(float(np.linalg.norm(q)) - 1) > 0.001:
            fail("reconstruction_failed", "重建相机位姿或图像引用无效。")
        w, x, y, z = q
        rotation = np.array([[1 - 2 * y*y - 2 * z*z, 2*x*y - 2*w*z, 2*x*z + 2*w*y], [2*x*y + 2*w*z, 1 - 2*x*x - 2*z*z, 2*y*z - 2*w*x], [2*x*z - 2*w*y, 2*y*z + 2*w*x, 1 - 2*x*x - 2*y*y]])
        w2c = np.eye(4, dtype=np.float32)
        w2c[:3, :3], w2c[:3, 3] = rotation, translation
        c2w = np.eye(4, dtype=np.float32)
        c2w[:3, :3], c2w[:3, 3] = rotation.T, -rotation.T @ translation
        views.append({**cameras[camera_id], "name": name, "w2c": w2c, "c2w": c2w})
        if next(lines, None) is None:
            fail("reconstruction_failed", "相机观测记录不完整。")
        if len(views) > MAX_IMAGES:
            fail("resource_limit", "注册相机数量超过上限。")
    points, colors, errors = [], [], []
    for line in text_lines(directory / "points3D.txt"):
        if not line:
            continue
        parts = line.split()
        if len(parts) < 12 or (len(parts) - 8) % 2:
            fail("reconstruction_failed", "稀疏点观测记录无效。")
        xyz = list(map(float, parts[1:4]))
        rgb = list(map(int, parts[4:7]))
        error = float(parts[7])
        if not finite(xyz + [error]) or any(v < 0 or v > 255 for v in rgb):
            fail("reconstruction_failed", "稀疏点数值无效。")
        points.append(xyz)
        colors.append(rgb)
        errors.append(error)
        if len(points) > 500_000:
            fail("resource_limit", "稀疏特征点超过 50 万，请缩小采集范围。")
    if not points or not views:
        fail("coverage_insufficient", "未能建立连通的现场相机与空间点。")
    return views, np.asarray(points, dtype=np.float32), np.asarray(colors, dtype=np.float32) / 255, float(np.mean(errors))


def reconstruct(images: Path, image_count, is_video: bool, execution: Execution):
    output = execution.output
    database = output / "colmap.db"
    sparse = output / "sparse"
    sparse.mkdir()
    execution.progress("matching")
    execution.run(["colmap", "feature_extractor", "--database_path", str(database), "--image_path", str(images), "--ImageReader.camera_model", "SIMPLE_RADIAL", "--ImageReader.single_camera", "1" if is_video else "0", "--SiftExtraction.use_gpu", "0", "--SiftExtraction.num_threads", "4", "--SiftExtraction.max_num_features", "8192", "--SiftExtraction.max_image_size", str(MAX_EDGE)], timeout=1800)
    execution.run(["colmap", "exhaustive_matcher", "--database_path", str(database), "--SiftMatching.use_gpu", "0", "--SiftMatching.num_threads", "4", "--SiftMatching.max_num_matches", "16384", "--ExhaustiveMatching.block_size", "20"], timeout=2400)
    execution.progress("reconstructing")
    execution.run(["colmap", "mapper", "--database_path", str(database), "--image_path", str(images), "--output_path", str(sparse), "--Mapper.num_threads", "4", "--Mapper.multiple_models", "0", "--Mapper.min_model_size", str(MIN_IMAGES), "--Mapper.ba_use_gpu", "0"], timeout=2400)
    models = [p for p in sparse.iterdir() if p.is_dir()]
    if len(models) != 1:
        fail("coverage_insufficient", "素材未形成一个连续空间，请增加有位移且重叠的视角。")
    undistorted = output / "undistorted"
    execution.run(["colmap", "image_undistorter", "--image_path", str(images), "--input_path", str(models[0]), "--output_path", str(undistorted), "--output_type", "COLMAP", "--max_image_size", str(MAX_EDGE)], timeout=600)
    text_model = output / "model-text"
    text_model.mkdir()
    execution.run(["colmap", "model_converter", "--input_path", str(undistorted / "sparse"), "--output_path", str(text_model), "--output_type", "TXT"], timeout=120)
    views, points, colors, mean_error = read_colmap(text_model)
    validate_quality(image_count, len(views), len(points), mean_error)
    import numpy as np
    centers = np.stack([v["c2w"][:3, 3] for v in views])
    extent = float(np.linalg.norm(points.max(axis=0) - points.min(axis=0)))
    baseline = float(np.linalg.norm(centers.max(axis=0) - centers.min(axis=0)))
    if not math.isfinite(extent) or extent < 1e-7 or baseline / extent < 0.005:
        fail("coverage_insufficient", "相机移动距离不足，无法确定空间深度。请移动拍摄位置，避免站在原地旋转。")
    quality = {"inputImages": image_count, "registeredImages": len(views), "points3D": len(points), "meanReprojectionError": mean_error}
    return undistorted / "images", views, points, colors, quality

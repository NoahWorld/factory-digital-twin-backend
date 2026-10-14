"""Bounded capture input/output contract; deliberately has no torch dependency."""
from __future__ import annotations

import json
import math
import os
from pathlib import Path
import stat
import struct

MIB = 1024 * 1024
MIN_IMAGES = 8
MAX_IMAGES = 120
MAX_IMAGE_BYTES = 8 * MIB
MAX_TOTAL_BYTES = 500 * MIB
MAX_VIDEO_BYTES = 100 * MIB
MAX_IMAGE_PIXELS = 40_000_000
MAX_EDGE = 1500
MAX_GAUSSIANS = 1_000_000
MAX_OUTPUT_BYTES = 200 * MIB
MAX_WORK_BYTES = 8 * 1024 * MIB
MAX_VIDEO_SECONDS = 600
MAX_VIDEO_PIXELS = 3840 * 2160
MAX_MANIFEST_BYTES = 128 * 1024
SH_DEGREE = 0
IMAGE_FORMATS = {".png": "PNG", ".jpg": "JPEG", ".jpeg": "JPEG", ".webp": "WEBP"}


class CaptureError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def fail(code: str, message: str):
    raise CaptureError(code, message)


def finite(values):
    return all(isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v) for v in values)


def safe_file(path: Path, root: Path) -> Path:
    """Inputs are local files staged under the manifest directory by Java, not URLs."""
    if path.is_symlink():
        fail("input_invalid", "源文件不能是符号链接。")
    resolved = path.resolve(strict=True)
    if root not in resolved.parents or not stat.S_ISREG(resolved.stat().st_mode):
        fail("input_invalid", "源文件必须位于当前任务的输入目录中。")
    return resolved


def read_manifest(path: Path):
    if path.is_symlink() or path.stat().st_size > MAX_MANIFEST_BYTES:
        fail("invalid_manifest", "任务清单无效或超过大小限制。")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise CaptureError("invalid_manifest", "任务清单不是有效 JSON。") from exc
    if not isinstance(value, dict) or set(value) - {"version", "inputKind", "sources", "options"}:
        fail("invalid_manifest", "任务清单包含不支持的字段。")
    if type(value.get("version")) is not int or value["version"] != 1:
        fail("invalid_manifest", "任务清单版本不受支持。")
    kind = value.get("inputKind")
    sources = value.get("sources")
    if kind not in ("images", "video") or not isinstance(sources, list):
        fail("invalid_manifest", "任务素材类型或列表无效。")
    if value.get("options", {}) != {}:
        fail("invalid_manifest", "当前版本不接受自定义重建参数。")
    if (kind == "images" and not MIN_IMAGES <= len(sources) <= MAX_IMAGES) or (kind == "video" and len(sources) != 1):
        fail("input_invalid", "需要 8–120 张照片，或一个视频。")
    root = path.resolve().parent
    normalized = []
    names, paths = set(), set()
    total = 0
    for source in sources:
        if not isinstance(source, dict) or set(source) != {"path", "filename"}:
            fail("invalid_manifest", "源文件字段无效。")
        name, source_path = source["filename"], source["path"]
        if not isinstance(name, str) or not 1 <= len(name) <= 240 or any(c in name for c in ("/", "\\", "\x00")) or any(ord(c) < 32 for c in name):
            fail("input_invalid", "源文件名称无效。")
        if not isinstance(source_path, str) or not source_path or len(source_path) > 4096:
            fail("input_invalid", "源文件路径无效。")
        local = Path(source_path)
        if not local.is_absolute():
            local = root / local
        try:
            local = safe_file(local, root)
        except OSError as exc:
            raise CaptureError("input_invalid", "无法读取当前任务的源文件。") from exc
        if name.casefold() in names or str(local) in paths:
            fail("input_invalid", "源文件列表包含重复文件。")
        names.add(name.casefold())
        paths.add(str(local))
        suffix = Path(name).suffix.lower()
        allowed = IMAGE_FORMATS if kind == "images" else {".mp4", ".webm"}
        if suffix not in allowed:
            fail("input_invalid", "素材格式不受支持。")
        size = local.stat().st_size
        limit = MAX_IMAGE_BYTES if kind == "images" else MAX_VIDEO_BYTES
        if not 0 < size <= limit:
            fail("input_invalid", "源文件为空或超过单文件大小限制。")
        total += size
        normalized.append({"path": local, "filename": name})
    if total > MAX_TOTAL_BYTES:
        fail("input_invalid", "素材总大小超过 500 MiB。")
    return {"version": 1, "inputKind": kind, "sources": normalized, "options": {}}


def validate_quality(input_images, registered_images, points, reprojection_error):
    if not MIN_IMAGES <= input_images <= MAX_IMAGES or registered_images < MIN_IMAGES or registered_images / input_images < 0.8:
        fail("coverage_insufficient", "成功匹配的照片不足：至少需要 8 张且达到输入照片的 80%。请补充连续重叠的移动视角。")
    if points < 500:
        fail("coverage_insufficient", "可重建空间特征不足 500 个，请补充清晰、有纹理且有位移的视角。")
    if not finite([reprojection_error]) or reprojection_error > 2.5:
        fail("coverage_insufficient", "相机匹配误差过大，当前素材无法可靠重建。请避免模糊、变焦和动态场景。")


def gaussian_properties(rest_count=0):
    if rest_count not in (0, 9, 24, 45):
        fail("output_invalid", "球谐系数数量无效。")
    return (["x", "y", "z", "nx", "ny", "nz"] + [f"f_dc_{i}" for i in range(3)]
            + [f"f_rest_{i}" for i in range(rest_count)] + ["opacity"]
            + [f"scale_{i}" for i in range(3)] + [f"rot_{i}" for i in range(4)])


def write_ply(path: Path, rows, count: int, rest_count=0):
    """Write standard 3DGS: raw SH, opacity logits, log scales, wxyz quaternions."""
    if type(count) is not int or not 1 <= count <= MAX_GAUSSIANS:
        fail("output_invalid", "高斯点数量无效或超过上限。")
    properties = gaussian_properties(rest_count)
    header = ("ply\nformat binary_little_endian 1.0\nelement vertex " + str(count) + "\n"
              + "".join(f"property float {p}\n" for p in properties) + "end_header\n").encode("ascii")
    expected_size = len(header) + count * len(properties) * 4
    if expected_size > MAX_OUTPUT_BYTES:
        fail("resource_limit", "生成的漫游模型超过 200 MiB。请缩小采集范围。")
    packer = struct.Struct("<" + "f" * len(properties))
    temporary = path.with_suffix(".ply.tmp")
    lower, upper = [math.inf] * 3, [-math.inf] * 3
    written = 0
    try:
        with temporary.open("xb") as stream:
            stream.write(header)
            for row in rows:
                # numpy scalars are intentionally converted to Python floats here.
                values = [float(v) for v in row]
                if len(values) != len(properties) or not finite(values) or any(abs(v) > 3.4e38 for v in values):
                    fail("output_invalid", "训练结果包含无效数值。")
                if written >= count:
                    fail("output_invalid", "导出点数量与声明不一致。")
                if abs(sum(v * v for v in values[-4:]) - 1) > 0.01:
                    fail("output_invalid", "训练结果包含无效旋转。")
                if any(v < -12 or v > 9 for v in values[-7:-4]):
                    fail("output_invalid", "训练结果包含异常高斯尺度。")
                if any(abs(v) > 1e9 for v in values[:3]):
                    fail("output_invalid", "训练结果空间坐标超出漫游范围。")
                packed = packer.pack(*values)
                stream.write(packed)
                coordinates = struct.unpack_from("<3f", packed)
                for axis in range(3):
                    lower[axis] = min(lower[axis], coordinates[axis])
                    upper[axis] = max(upper[axis], coordinates[axis])
                written += 1
            if written != count:
                fail("output_invalid", "导出点数量与声明不一致。")
            if not any(upper[axis] > lower[axis] for axis in range(3)):
                fail("output_invalid", "训练结果没有有效的空间范围。")
        os.replace(temporary, path)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise
    return {"byteSize": expected_size, "gaussianCount": count, "bounds": {"min": lower, "max": upper}}


def write_json(path: Path, value):
    temporary = path.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":")), encoding="utf-8")
    os.replace(temporary, path)

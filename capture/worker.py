#!/usr/bin/env python3
"""Offline capture worker. stdout is JSONL; diagnostics are stderr / worker.log."""
from __future__ import annotations

import argparse
import contextlib
import importlib.metadata
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import traceback

from core import CaptureError, fail, read_manifest, write_json, write_ply


def deny_jit(*args, **kwargs):
    fail("worker_unavailable", "CUDA 扩展未预编译，当前禁止运行时编译或下载。")


def probe():
    status = {"available": False, "cuda": False, "colmap": False, "ffmpeg": False, "gsplatVersion": None, "precompiledExtension": False, "reason": None}
    try:
        if not shutil.which("colmap"):
            fail("worker_unavailable", "重建服务缺少 COLMAP。")
        version = subprocess.run(["colmap", "-h"], capture_output=True, timeout=15, check=True, env={**os.environ, "QT_QPA_PLATFORM": "offscreen"})
        if b"3.11.1" not in version.stdout + version.stderr:
            fail("worker_unavailable", "重建服务需要固定版本 COLMAP 3.11.1。")
        status["colmap"] = True
        status["ffmpeg"] = bool(shutil.which("ffmpeg") and shutil.which("ffprobe"))
        if not status["ffmpeg"]:
            fail("worker_unavailable", "重建服务缺少 FFmpeg 媒体组件。")
        status["gsplatVersion"] = importlib.metadata.version("gsplat")
        if status["gsplatVersion"] != "1.5.3":
            fail("worker_unavailable", "重建服务需要固定版本 gsplat 1.5.3。")
        distribution = importlib.metadata.distribution("gsplat")
        package = Path(distribution.locate_file("gsplat"))
        if not list(package.glob("csrc*.so")):
            fail("worker_unavailable", "CUDA 扩展未预编译，请使用完整离线镜像。")
        # Never let upstream's fallback JIT run, including a broken installed .so.
        import torch
        import torch.utils.cpp_extension
        torch.utils.cpp_extension._jit_compile = deny_jit
        torch.utils.cpp_extension.load = deny_jit
        torch.utils.cpp_extension.load_inline = deny_jit
        status["cuda"] = bool(torch.cuda.is_available())
        if not status["cuda"]:
            fail("worker_unavailable", "当前没有可用的 NVIDIA CUDA GPU，无法执行写实重建。")
        with contextlib.redirect_stdout(sys.stderr):
            from gsplat.cuda._backend import _C
        if _C is None:
            fail("worker_unavailable", "CUDA 扩展无法加载，请检查驱动和离线镜像。")
        status["precompiledExtension"] = True
        # Exercise both precompiled forward/backward kernels on the actual GPU;
        # importing a .so alone does not prove its architecture is supported.
        from gsplat import rasterization
        means = torch.tensor([[0., 0., 2.]], device="cuda", requires_grad=True)
        image, _, _ = rasterization(means=means, quats=torch.tensor([[1., 0., 0., 0.]], device="cuda"), scales=torch.full((1, 3), .1, device="cuda"), opacities=torch.ones(1, device="cuda") * .5, colors=torch.ones((1, 3), device="cuda") * .5, viewmats=torch.eye(4, device="cuda")[None], Ks=torch.tensor([[[16., 0., 8.], [0., 16., 8.], [0., 0., 1.]]], device="cuda"), width=16, height=16, packed=True)
        image.sum().backward()
        if not torch.isfinite(image).all().item() or means.grad is None or not torch.isfinite(means.grad).all().item():
            fail("worker_unavailable", "当前 GPU 的重建内核自检失败。")
        torch.cuda.synchronize()
        status["available"] = True
    except CaptureError as exc:
        status["reason"] = exc.message
    except (ImportError, OSError, RuntimeError, subprocess.SubprocessError, importlib.metadata.PackageNotFoundError) as exc:
        print(f"capture probe {type(exc).__name__}: {exc}", file=sys.stderr, flush=True)
        status["reason"] = "重建服务依赖或 GPU 运行环境不可用，请查看工作进程诊断。"
    return status


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--probe", action="store_true")
    parser.add_argument("--input", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.probe:
        print(json.dumps(probe(), ensure_ascii=False), flush=True)
        return 0
    if args.input is None or args.output is None:
        parser.error("--input and --output are required")
    output = args.output.absolute()
    if output.is_symlink() or (output.exists() and (not output.is_dir() or any(output.iterdir()))):
        print(json.dumps({"code": "invalid_manifest", "message": "输出目录必须为空且不能是符号链接。"}, ensure_ascii=False), file=sys.stderr)
        return 1
    output.mkdir(parents=True, exist_ok=True)
    from runtime import Execution, extract_video, normalize_images, reconstruct
    execution = Execution(output)
    signal.signal(signal.SIGTERM, execution.cancel)
    signal.signal(signal.SIGINT, execution.cancel)
    try:
        execution.progress("validating")
        manifest = read_manifest(args.input)
        status = probe()
        if not status["available"]:
            fail("worker_unavailable", status["reason"])
        video = manifest["inputKind"] == "video"
        sources = extract_video(manifest["sources"][0], output / "frames", execution) if video else manifest["sources"]
        image_dir = output / "images"
        metrics = normalize_images(sources, image_dir, execution, extracted=video)
        write_json(output / "input-quality.json", {"images": metrics})
        image_dir, views, points, colors, quality = reconstruct(image_dir, len(sources), video, execution)
        from training import camera_manifest, ply_rows, train
        values, training_metrics, scene_scale = train(image_dir, views, points, colors, execution)
        execution.progress("exporting")
        exported = write_ply(output / "scene.ply", ply_rows(values), len(values["means"]))
        cameras = camera_manifest(views, scene_scale)
        notices = ["空间保持 COLMAP 相对坐标，未经真实尺度标定，不可直接用于测量。", "当前外观采用 SH0，不保证复现玻璃、镜面和随视角变化的反光；未拍到的区域不保证完整。"]
        if quality["registeredImages"] != quality["inputImages"]:
            notices.append(f"{quality['inputImages'] - quality['registeredImages']} 张图片未注册，结果仅覆盖已匹配视角。")
        result = {"format": "ply", "file": "scene.ply", **exported, "initialCamera": {key: cameras[0][key] for key in ("position", "target", "up")}, "quality": quality, "warnings": notices, "registeredCameras": cameras, "coordinateSystem": "colmap", "units": "arbitrary", "training": training_metrics}
        write_json(output / "result.json", result)
        execution.progress("exporting", 1, 1)
        return 0
    except CaptureError as exc:
        (output / "result.json").unlink(missing_ok=True)
        error = {"code": exc.code, "message": exc.message}
        write_json(output / "error.json", error)
        print(json.dumps(error, ensure_ascii=False), file=sys.stderr, flush=True)
        execution.report_failure()
        traceback.print_exc(file=sys.stderr)
        return 1
    except Exception as exc:
        # Process boundary only: preserve full diagnostics and fail, never fake an output.
        code = "resource_limit" if "out of memory" in str(exc).lower() else "internal_error"
        (output / "result.json").unlink(missing_ok=True)
        error = {"code": code, "message": "重建资源不足，请缩小范围。" if code == "resource_limit" else "重建工作进程异常，诊断记录已保存。"}
        write_json(output / "error.json", error)
        print(json.dumps(error, ensure_ascii=False), file=sys.stderr, flush=True)
        execution.report_failure()
        traceback.print_exc(file=sys.stderr)
        return 1
    finally:
        execution.close()


if __name__ == "__main__":
    sys.exit(main())

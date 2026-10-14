"""CPU-only checks. Synthetic fixtures test contracts, never reconstruction quality."""
from __future__ import annotations

import contextlib
import io
import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from core import CaptureError, MAX_GAUSSIANS, MAX_IMAGE_BYTES, gaussian_properties, read_manifest, validate_quality, write_ply
from runtime import Execution, extract_video, normalize_images, read_colmap
from training import camera_manifest


class WorkerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def manifest(self):
        sources = []
        for i in range(8):
            path = self.root / f"photo-{i}.jpg"
            path.write_bytes(b"test-boundary-not-image")
            sources.append({"path": path.name, "filename": path.name})
        value = {"version": 1, "inputKind": "images", "sources": sources, "options": {}}
        path = self.root / "manifest.json"
        path.write_text(json.dumps(value))
        return path, value

    def assert_code(self, expected, operation):
        with self.assertRaises(CaptureError) as caught:
            operation()
        self.assertEqual(caught.exception.code, expected)

    def test_manifest_accepts_scoped_files(self):
        path, _ = self.manifest()
        parsed = read_manifest(path)
        self.assertEqual(len(parsed["sources"]), 8)
        self.assertTrue(parsed["sources"][0]["path"].is_absolute())

    def test_manifest_rejects_outside_path_and_symlinks(self):
        path, value = self.manifest()
        value["sources"][0]["path"] = str(Path(__file__).absolute())
        path.write_text(json.dumps(value))
        self.assert_code("input_invalid", lambda: read_manifest(path))
        (self.root / "link.jpg").symlink_to(self.root / "photo-0.jpg")
        value["sources"][0]["path"] = "link.jpg"
        path.write_text(json.dumps(value))
        self.assert_code("input_invalid", lambda: read_manifest(path))

    def test_manifest_limits_and_untrusted_options(self):
        for case in ("few", "duplicate", "large", "options", "filename"):
            with self.subTest(case=case):
                path, value = self.manifest()
                if case == "few":
                    value["sources"].pop()
                elif case == "duplicate":
                    value["sources"][1] = value["sources"][0]
                elif case == "large":
                    with (self.root / "photo-0.jpg").open("wb") as stream:
                        stream.truncate(MAX_IMAGE_BYTES + 1)
                elif case == "options":
                    value["options"] = {"command": "malicious"}
                else:
                    value["sources"][0]["filename"] = "../photo.jpg"
                path.write_text(json.dumps(value))
                self.assert_code("invalid_manifest" if case == "options" else "input_invalid", lambda: read_manifest(path))

    def test_quality_thresholds_fail_without_inventing_coverage(self):
        validate_quality(10, 8, 500, 2.5)
        for values in ((20, 8, 500, 1), (8, 7, 1000, 1), (8, 8, 499, 1), (8, 8, 500, 2.51), (8, 8, 500, float("nan"))):
            self.assert_code("coverage_insufficient", lambda: validate_quality(*values))

    def row(self):
        return [1.25, -2, 3, 0, 0, 0, .1, .2, .3, -1, -2, -2, -2, 1, 0, 0, 0]

    def test_ply_standard_byte_layout_and_bounds(self):
        path = self.root / "scene.ply"
        second = self.row()
        second[:3] = [-1, 4, 0]
        result = write_ply(path, iter([self.row(), second]), 2)
        data = path.read_bytes()
        header, payload = data.split(b"end_header\n", 1)
        self.assertIn(b"format binary_little_endian 1.0", header)
        self.assertNotIn(b"f_rest", header)
        self.assertEqual(len(gaussian_properties()), 17)
        self.assertEqual(len(payload), 2 * 17 * 4)
        self.assertEqual(struct.unpack("<17f", payload[:68])[:3], (1.25, -2, 3))
        self.assertEqual(result["bounds"], {"min": [-1, -2, 0], "max": [1.25, 4, 3]})
        self.assertEqual(result["byteSize"], len(data))

    def test_ply_rejects_nonfinite_bad_quaternion_and_budget(self):
        for field, value in ((0, float("nan")), (0, 1e10), (13, 0), (10, -13), (11, 10)):
            row = self.row()
            row[field] = value
            self.assert_code("output_invalid", lambda: write_ply(self.root / "scene.ply", [row], 1))
            self.assertFalse((self.root / "scene.ply").exists())
        self.assert_code("output_invalid", lambda: write_ply(self.root / "scene.ply", [], MAX_GAUSSIANS + 1))
        self.assert_code("output_invalid", lambda: write_ply(self.root / "scene.ply", [], 1))
        self.assert_code("output_invalid", lambda: write_ply(self.root / "scene.ply", [self.row(), self.row()], 2))

    def execution(self):
        output = self.root / "output"
        output.mkdir()
        return Execution(output)

    def test_progress_unknown_is_explicit_indeterminate(self):
        execution = self.execution()
        with contextlib.redirect_stdout(io.StringIO()) as output:
            execution.progress("matching")
        execution.close()
        self.assertEqual(json.loads(output.getvalue()), {"stage": "matching", "completed": 0, "total": 0})

    def test_subprocess_failure_keeps_diagnostics(self):
        execution = self.execution()
        self.assert_code("reconstruction_failed", lambda: execution.run([sys.executable, "-c", "print('intentional fixture failure');raise SystemExit(3)"]))
        with contextlib.redirect_stderr(io.StringIO()) as errors:
            execution.report_failure()
        self.assertIn("intentional fixture failure", errors.getvalue())
        execution.close()
        self.assertIn("intentional fixture failure", (execution.output / "worker.log").read_text())

    def test_diagnostic_tail_is_bounded_for_server_log(self):
        execution = self.execution()
        execution.log.write(("\x01" * 2000 + "\n").encode() * 100)
        with contextlib.redirect_stderr(io.StringIO()) as errors:
            execution.report_failure()
        execution.close()
        lines = errors.getvalue().splitlines()
        self.assertLessEqual(len(lines), 40)
        self.assertTrue(all(len(line.encode()) < 16 * 1024 for line in lines))
        self.assertTrue(all(json.loads(line)["diagnosticTail"] for line in lines))

    def test_decode_rejects_corrupt_and_solid_color(self):
        from PIL import Image
        execution = self.execution()
        image = self.root / "image.jpg"
        image.write_bytes(b"invalid jpeg")
        sources = [{"path": image, "filename": "image.jpg"}]
        with contextlib.redirect_stdout(io.StringIO()):
            self.assert_code("image_decode_failed", lambda: normalize_images(sources, self.root / "decode1", execution))
            Image.new("RGB", (640, 480), "white").save(image)
            self.assert_code("image_quality_insufficient", lambda: normalize_images(sources, self.root / "decode2", execution))
        execution.close()

    def test_palette_transparency_is_rejected(self):
        from PIL import Image
        execution = self.execution()
        image = self.root / "transparent.png"
        Image.new("P", (640, 480)).save(image, transparency=0)
        with contextlib.redirect_stdout(io.StringIO()):
            self.assert_code("image_quality_insufficient", lambda: normalize_images([{"path": image, "filename": image.name}], self.root / "palette", execution))
        execution.close()

    def test_video_metadata_limits_reject_before_decode(self):
        execution = self.execution()
        for duration, fps, width, height in ((601, "30/1", 640, 480), (4, "61/1", 640, 480), (4, "0/0", 640, 480), (4, "30/1", 7680, 4320), (3, "30/1", 640, 480)):
            with self.subTest(duration=duration, fps=fps, width=width):
                metadata = json.dumps({"format": {"duration": str(duration)}, "streams": [{"codec_type": "video", "width": width, "height": height, "avg_frame_rate": fps}]})
                with patch.object(execution, "run", return_value=metadata) as run, contextlib.redirect_stdout(io.StringIO()):
                    self.assert_code("video_invalid", lambda: extract_video({"path": self.root / "video.mp4"}, self.root / "frames", execution))
                self.assertEqual(run.call_count, 1)
        execution.close()

    @unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "FFmpeg not installed")
    def test_real_ffmpeg_extraction_is_bounded(self):
        # Codec/extraction fixture only. This is NOT a reconstruction-quality test.
        video = self.root / "clip.mp4"
        subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=640x480:rate=8", "-t", "4", "-c:v", "mpeg4", "-q:v", "5", str(video)], check=True, timeout=30)
        execution = self.execution()
        with contextlib.redirect_stdout(io.StringIO()):
            frames = extract_video({"path": video}, self.root / "frames", execution)
            metrics = normalize_images(frames, self.root / "video-normalized", execution, extracted=True)
        execution.close()
        self.assertEqual(len(frames), 8)
        self.assertTrue(all(max(item["width"], item["height"]) <= 1500 for item in metrics))

    def test_normalize_bounded_images_and_reject_duplicate_content(self):
        import numpy as np
        from PIL import Image
        execution = self.execution()
        path = self.root / "large.png"
        random = np.random.default_rng(10).integers(0, 256, size=(1200, 1800, 3), dtype=np.uint8)
        Image.fromarray(random).save(path)
        source = {"path": path, "filename": "large.png"}
        with contextlib.redirect_stdout(io.StringIO()):
            metrics = normalize_images([source], self.root / "normalized", execution)
            self.assert_code("image_quality_insufficient", lambda: normalize_images([source, source], self.root / "duplicates", execution))
        self.assertEqual((metrics[0]["width"], metrics[0]["height"]), (1500, 1000))
        with Image.open(self.root / "normalized" / "image_0000.jpg") as image:
            self.assertEqual(image.format, "JPEG")
        execution.close()

    def test_colmap_pose_convention_and_empty_observations(self):
        import numpy as np
        (self.root / "cameras.txt").write_text("# comment\n1 PINHOLE 640 480 500 500 320 240\n")
        (self.root / "images.txt").write_text("# comment\n1 1 0 0 0 1 2 3 1 image_0000.jpg\n\n2 1 0 0 0 2 2 3 1 image_0001.jpg\n1 2 1\n")
        (self.root / "points3D.txt").write_text("1 1 2 3 255 100 0 0.5 1 0 2 0\n")
        views, points, colors, error = read_colmap(self.root)
        self.assertEqual(len(views), 2)
        self.assertEqual(views[0]["c2w"][:3, 3].tolist(), [-1, -2, -3])
        cameras = camera_manifest(views, 2)
        self.assertEqual(cameras[0]["up"], [0, -1, 0])
        self.assertEqual(cameras[0]["target"], [-1, -2, -1])
        self.assertAlmostEqual(np.dot(np.array(cameras[0]["target"]) - cameras[0]["position"], cameras[0]["up"]), 0)
        self.assertEqual(error, .5)

    def test_rotated_colmap_camera_projects_its_forward_target_to_image_center(self):
        import math
        import numpy as np
        component = math.sqrt(.5)
        (self.root / "cameras.txt").write_text("1 PINHOLE 640 480 500 510 320 240\n")
        (self.root / "images.txt").write_text(f"1 {component} 0 {component} 0 1 2 3 1 image_0000.jpg\n\n")
        (self.root / "points3D.txt").write_text("1 1 2 3 255 100 0 0.5 1 0 2 0\n")
        views, _, _, _ = read_colmap(self.root)
        view = views[0]
        camera = camera_manifest(views, 2)[0]
        np.testing.assert_allclose(view["w2c"] @ view["c2w"], np.eye(4), atol=1e-6)
        np.testing.assert_allclose(camera["position"], [3, -2, -1], atol=1e-6)
        target_in_camera = (view["w2c"] @ np.array([*camera["target"], 1]))[:3]
        np.testing.assert_allclose(target_in_camera, [0, 0, 2], atol=1e-6)
        projected = view["K"] @ target_in_camera
        np.testing.assert_allclose(projected[:2] / projected[2], [320, 240], atol=1e-5)
        np.testing.assert_allclose(view["w2c"][:3, :3] @ camera["up"], [0, -1, 0], atol=1e-6)

    def test_no_dependencies_probe_does_not_fake_available(self):
        from worker import probe
        with patch("worker.shutil.which", return_value=None):
            status = probe()
        self.assertFalse(status["available"])
        self.assertFalse(status["cuda"])
        self.assertIn("COLMAP", status["reason"])

    def test_invalid_job_returns_error_not_scene(self):
        manifest, value = self.manifest()
        value["sources"] = []
        manifest.write_text(json.dumps(value))
        output = self.root / "failed"
        result = subprocess.run([sys.executable, str(Path(__file__).with_name("worker.py")), "--input", str(manifest), "--output", str(output)], capture_output=True)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(json.loads((output / "error.json").read_text())["code"], "input_invalid")
        self.assertFalse((output / "result.json").exists())
        self.assertFalse((output / "scene.ply").exists())


if __name__ == "__main__":
    unittest.main()

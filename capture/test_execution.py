"""Real process/signal and packaging-failure tests; no reconstruction fixtures."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest

from core import CaptureError, MAX_WORK_BYTES
from runtime import Execution


class ExecutionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.output = self.root / "output"
        self.output.mkdir()

    def tearDown(self):
        self.temporary.cleanup()

    def tree_command(self):
        # Both native process and its descendant keep the inherited stdout open.
        code = (
            "import subprocess,sys,time,os,json; from pathlib import Path; "
            "child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)']); "
            f"Path({str(self.root / 'pids.json')!r}).write_text(json.dumps([os.getpid(),child.pid])); "
            "print('native tree ready',flush=True); time.sleep(60)"
        )
        return [sys.executable, "-c", code]

    def assert_tree_stopped(self):
        pids = json.loads((self.root / "pids.json").read_text())
        for pid in pids:
            # An adopted zombie has stopped running; reaping belongs to PID 1.
            if sys.platform.startswith("linux"):
                # The minimal runtime image does not need the procps package.
                try:
                    state = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()[0]
                except FileNotFoundError:
                    state = ""
            else:
                state = subprocess.run(["ps", "-p", str(pid), "-o", "stat="], capture_output=True, text=True, check=False).stdout.strip()
            if state and not state.startswith("Z"):
                os.kill(pid, signal.SIGKILL)
                self.fail(f"Native descendant {pid} survived cleanup: {state}")

    def test_timeout_stops_native_process_and_descendant(self):
        execution = Execution(self.output)
        started = time.monotonic()
        try:
            with self.assertRaises(CaptureError) as caught:
                execution.run(self.tree_command(), timeout=0.25)
            self.assertEqual(caught.exception.code, "timeout")
            self.assertLess(time.monotonic() - started, 5)
            self.assert_tree_stopped()
            self.assertIn("native tree ready", (self.output / "worker.log").read_text())
        finally:
            execution.close()

    def test_sigterm_stops_native_process_and_descendant(self):
        harness = self.root / "harness.py"
        harness.write_text(
            "import signal,json; from pathlib import Path\n"
            "from core import CaptureError\nfrom runtime import Execution\n"
            f"execution=Execution(Path({str(self.output)!r}))\n"
            "signal.signal(signal.SIGTERM,execution.cancel)\n"
            "try:\n"
            f"    execution.run({self.tree_command()!r})\n"
            "except CaptureError as exc:\n"
            "    print(json.dumps({'code':exc.code}),flush=True)\n"
            "    raise SystemExit(1)\n"
            "finally:\n    execution.close()\n"
        )
        process = subprocess.Popen([sys.executable, str(harness)], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   env={**os.environ, "PYTHONPATH": str(Path(__file__).resolve().parent)})
        try:
            deadline = time.monotonic() + 5
            while not (self.root / "pids.json").exists() and time.monotonic() < deadline:
                if process.poll() is not None:
                    self.fail(f"Cancellation harness exited early: {process.communicate()}")
                time.sleep(0.02)
            self.assertTrue((self.root / "pids.json").exists(), "Native tree did not start")
            process.send_signal(signal.SIGTERM)
            stdout, stderr = process.communicate(timeout=5)
            self.assertEqual(process.returncode, 1, stderr.decode())
            self.assertEqual(json.loads(stdout), {"code": "timeout"})
            self.assert_tree_stopped()
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate()

    def test_work_budget_rejects_oversized_sparse_file(self):
        execution = Execution(self.output)
        try:
            # Sparse file exercises the actual stat budget without allocating 8 GiB.
            with (self.output / "oversized.bin").open("wb") as stream:
                stream.truncate(MAX_WORK_BYTES + 1)
            with self.assertRaises(CaptureError) as caught:
                execution.check()
            self.assertEqual(caught.exception.code, "resource_limit")
        finally:
            execution.close()

    def test_native_log_budget_stops_noisy_command(self):
        execution = Execution(self.output)
        try:
            with self.assertRaises(CaptureError) as caught:
                execution.run([sys.executable, "-c", "import sys;sys.stdout.buffer.write(b'x'*(17*1024*1024));sys.stdout.flush()"])
            self.assertEqual(caught.exception.code, "resource_limit")
            self.assertLess((self.output / "worker.log").stat().st_size, 17 * 1024 * 1024)
        finally:
            execution.close()


@unittest.skipUnless(shutil.which("bash") and shutil.which("sha256sum"), "Offline loader requires bash and sha256sum")
class OfflineLoaderTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        shutil.copy(Path(__file__).with_name("load-offline.sh"), self.root / "load-offline.sh")
        archive = b"loader-contract-fixture-not-a-docker-image"
        (self.root / "capture-image.tar").write_bytes(archive)
        (self.root / "capture-image.tar.sha256").write_text(hashlib.sha256(archive).hexdigest() + "  capture-image.tar\n")
        (self.root / "image-tag.txt").write_text("capture-test:fixture\n")
        self.log = self.root / "docker-calls.log"
        executable = self.root / "docker"
        executable.write_text(f'#!/bin/sh\nprintf "%s\\n" "$*" >> "{self.log}"\nif [ "$1" = run ]; then printf \'{{"available":false,"reason":"fixture unavailable"}}\\n\'; fi\n')
        executable.chmod(0o755)

    def tearDown(self):
        self.temporary.cleanup()

    def invoke(self):
        return subprocess.run(["bash", str(self.root / "load-offline.sh")], capture_output=True, text=True, timeout=10,
                              env={**os.environ, "PATH": str(self.root) + os.pathsep + os.environ["PATH"]})

    def test_corrupt_archive_rejected_before_docker_load(self):
        (self.root / "capture-image.tar").write_bytes(b"corrupt archive")
        result = self.invoke()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.log.exists())

    def test_unavailable_probe_never_reports_acceptance(self):
        result = self.invoke()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("GPU availability check failed", result.stderr)
        self.assertNotIn("Offline GPU probe passed", result.stdout)
        calls = self.log.read_text().splitlines()
        self.assertEqual(len(calls), 2)
        self.assertTrue(calls[0].startswith("load -i "))
        self.assertIn("--network none", calls[1])


if __name__ == "__main__":
    unittest.main()

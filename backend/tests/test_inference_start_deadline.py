"""Regression for an unready worker holding the inference ownership slot."""

import multiprocessing
import os
import signal
import sys
import time
from unittest.mock import patch

import pytest

from backend.app.schemas import DetectContext
from backend.app.services import inference_process as ipc


@pytest.mark.skipif(os.name != "posix", reason="direct child ownership is POSIX-only")
def test_direct_spawn_never_uses_blockable_multiprocessing_start():
    runner = ipc.InferenceProcessRunner(timeout_seconds=1, startup_timeout_seconds=10)
    try:
        with patch.object(multiprocessing.process.BaseProcess, "start", side_effect=AssertionError("Process.start called")):
            runner._ensure_worker()
        assert runner._process is not None
        assert runner._process.is_alive()
    finally:
        runner.close()


@pytest.mark.skipif(os.name != "posix", reason="direct child ownership is POSIX-only")
def test_unready_child_expires_reaps_and_releases_request_lock():
    runner = ipc.InferenceProcessRunner(timeout_seconds=1, startup_timeout_seconds=0.1)
    real_spawn = os.posix_spawn
    child_pids = []

    def spawn_unready(path, argv, environment, **kwargs):
        pid = real_spawn(
            sys.executable,
            (sys.executable, "-c", "import time; time.sleep(30)"),
            environment,
            **kwargs,
        )
        child_pids.append(pid)
        return pid

    try:
        with patch.object(ipc.os, "posix_spawn", side_effect=spawn_unready):
            started = time.monotonic()
            with pytest.raises(ipc.InferenceDeadlineExceeded):
                runner.run_legacy(
                    image_bytes=b"unused", content_type="image/png",
                    context=DetectContext(), settings=object(),
                )
            assert time.monotonic() - started < 2
        assert child_pids
        assert runner._process is None
        assert runner._connection is None
        with pytest.raises(ChildProcessError):
            os.waitpid(child_pids[0], os.WNOHANG)
        with pytest.raises(ipc.InferenceRestartAdmissionLimited):
            runner.run_legacy(
                image_bytes=b"unused", content_type="image/png",
                context=DetectContext(), settings=object(),
            )
    finally:
        runner.close()


@pytest.mark.skipif(os.name != "posix", reason="direct child ownership is POSIX-only")
def test_close_bounds_unready_active_submission_and_reaps_child():
    runner = ipc.InferenceProcessRunner(timeout_seconds=30, startup_timeout_seconds=30)
    real_spawn = os.posix_spawn
    child_pids = []

    def spawn_unready(path, argv, environment, **kwargs):
        pid = real_spawn(
            sys.executable,
            (sys.executable, "-c", "import time; time.sleep(30)"),
            environment,
            **kwargs,
        )
        child_pids.append(pid)
        return pid

    try:
        with patch.object(ipc.os, "posix_spawn", side_effect=spawn_unready):
            future = runner.submit_inference(
                "legacy",
                image_bytes=b"unused", content_type="image/png",
                context=DetectContext(), settings=object(),
                deadline=time.monotonic() + 30,
            )
            wait_until = time.monotonic() + 2
            while not child_pids and time.monotonic() < wait_until:
                time.sleep(0.01)
            assert child_pids
            started = time.monotonic()
            runner.close()
            assert time.monotonic() - started < 5
        assert future.done()
        with pytest.raises(RuntimeError):
            future.result()
        with pytest.raises(ChildProcessError):
            os.waitpid(child_pids[0], os.WNOHANG)
    finally:
        for pid in child_pids:
            try:
                waited, _ = os.waitpid(pid, os.WNOHANG)
            except ChildProcessError:
                continue
            if not waited:
                os.killpg(pid, signal.SIGKILL)
                os.waitpid(pid, 0)
        runner.close()

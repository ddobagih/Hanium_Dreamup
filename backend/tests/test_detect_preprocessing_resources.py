"""Bounded resource checks for the real detector preprocessing process path."""

from __future__ import annotations

import asyncio
from concurrent.futures import ThreadPoolExecutor
import io
import json
import os
from pathlib import Path
import socket
import signal
import sys
import threading
import time
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest.mock import patch

from fastapi import HTTPException
from PIL import Image
import pytest
from starlette.datastructures import Headers, UploadFile

from backend.app import uploads
from backend.app.api.detect import DETECT_PREPROCESSING_MAX_CONCURRENT
from backend.app.config import DEFAULT_INFERENCE_TIMEOUT_SECONDS
from backend.app.services import _detection_preprocessing as preprocessing


_WIDTH = 4_000
_HEIGHT = 5_000
_BARRIER_ENV = "WALKSAFE_TEST_PREPROCESSING_RESOURCE_BARRIER"


class _Upload(UploadFile):
    def __init__(self, content: bytes) -> None:
        super().__init__(
            file=io.BytesIO(content),
            filename="frame.png",
            headers=Headers({"content-type": "image/png"}),
        )
        self.read_calls = 0

    async def read(self, size: int = -1) -> bytes:
        self.read_calls += 1
        return await super().read(size)


class _FailingUpload(_Upload):
    async def read(self, size: int = -1) -> bytes:
        self.read_calls += 1
        raise OSError("upload read failed")


def _png(width: int, height: int) -> bytes:
    output = io.BytesIO()
    # One-bit pixels make the exact 20 MP boundary affordable in CI.
    with Image.new("1", (width, height), 0) as image:
        image.save(output, format="PNG")
    return output.getvalue()


def _rgb_png(color: tuple[int, int, int], width: int, height: int) -> bytes:
    output = io.BytesIO()
    with Image.new("RGB", (width, height), color) as image:
        image.save(output, format="PNG")
    return output.getvalue()


def _settings() -> SimpleNamespace:
    return SimpleNamespace(
        allowed_image_content_types={"image/png"},
        max_upload_bytes=1024 * 1024,
        inference_timeout_seconds=30.0,
    )


def _worker_with_resource_barrier(connection: socket.socket) -> None:
    """Observe real Pillow decode and save calls inside the production worker."""
    from PIL import ImageFile, PngImagePlugin
    from backend.app.services import _detection_preprocessing as worker_preprocessing

    barrier = Path(os.environ[_BARRIER_ENV])
    width, height = json.loads((barrier / "dimensions.json").read_text())
    original_load = ImageFile.ImageFile.load
    original_save = Image.Image.save
    decoded_once = False
    saved_once = False

    def hold(phase: str, pixels: int) -> None:
        pid = os.getpid()
        temporary = barrier / f"{pid}.{phase}.partial"
        temporary.write_text(f"{pid} {pixels}\n")
        temporary.replace(barrier / f"{pid}.{phase}.ready")
        deadline = time.monotonic() + 2.0
        while not (barrier / f"{phase}.release").exists():
            if time.monotonic() >= deadline:
                (barrier / f"{pid}.{phase}.rescued").touch()
                return
            time.sleep(0.005)

    def load_with_barrier(image, *args, **kwargs):
        nonlocal decoded_once
        if (
            not decoded_once
            and isinstance(image, PngImagePlugin.PngImageFile)
            and image.size == (width, height)
            and image.mode == "RGB"
        ):
            decoded_once = True
            hold("decode_enter", image.width * image.height)
            load_started_ns = time.monotonic_ns()
            result = original_load(image, *args, **kwargs)
            load_finished_ns = time.monotonic_ns()
            pid = os.getpid()
            temporary = barrier / f"{pid}.load_interval.partial"
            temporary.write_text(f"{pid} {load_started_ns} {load_finished_ns}\n")
            temporary.replace(barrier / f"{pid}.load_interval.ready")
            hold("decode_done", image.width * image.height)
            return result
        return original_load(image, *args, **kwargs)

    def save_with_barrier(image, *args, **kwargs):
        nonlocal saved_once
        if not saved_once and image.size == (width, height) and image.mode == "RGB":
            saved_once = True
            hold("save_ready", image.width * image.height)
            started_ns = time.monotonic_ns()
            result = original_save(image, *args, **kwargs)
            finished_ns = time.monotonic_ns()
            pid = os.getpid()
            temporary = barrier / f"{pid}.save_interval.partial"
            temporary.write_text(f"{pid} {started_ns} {finished_ns}\n")
            temporary.replace(barrier / f"{pid}.save_interval.ready")
            hold("save_done", image.width * image.height)
            return result
        return original_save(image, *args, **kwargs)

    with patch.object(ImageFile.ImageFile, "load", load_with_barrier), patch.object(
        Image.Image, "save", save_with_barrier,
    ):
        worker_preprocessing._preprocessing_worker(connection)


def _memory_kib(pid: int) -> tuple[int, int]:
    fields = {
        line.split(":", 1)[0]: int(line.split()[1])
        for line in Path(f"/proc/{pid}/smaps_rollup").read_text().splitlines()
        if line.startswith(("Pss:", "Rss:"))
    }
    return fields["Pss"], fields["Rss"]


def test_exact_max_pixels_succeed_and_pixel_only_overflow_is_rejected() -> None:
    assert uploads.MAX_IMAGE_PIXELS == _WIDTH * _HEIGHT == 20_000_000
    assert _HEIGHT + 1 <= uploads.MAX_IMAGE_DIMENSION
    accepted = _Upload(_png(_WIDTH, _HEIGHT))
    rejected = _Upload(_png(_WIDTH, _HEIGHT + 1))
    capacity = threading.BoundedSemaphore(2)

    async def run() -> None:
        try:
            sanitized, content_type = await preprocessing.read_detection_image_upload(
                accepted, _settings(), capacity=capacity,
            )
            assert content_type == "image/png"
            with Image.open(io.BytesIO(sanitized)) as image:
                assert image.size == (_WIDTH, _HEIGHT)
            with pytest.raises(HTTPException) as caught:
                await preprocessing.read_detection_image_upload(
                    rejected, _settings(), capacity=capacity,
                )
            assert caught.value.status_code == 413
            assert caught.value.detail == {
                "code": "image_dimensions_too_large",
                "max_dimension": uploads.MAX_IMAGE_DIMENSION,
                "max_pixels": uploads.MAX_IMAGE_PIXELS,
            }
            assert capacity.acquire(blocking=False)
            assert capacity.acquire(blocking=False)
            capacity.release()
            capacity.release()
        finally:
            await accepted.close()
            await rejected.close()

    asyncio.run(run())


def test_upload_error_releases_slots_when_default_executor_is_shut_down() -> None:
    frames = [_FailingUpload(b"") for _ in range(3)]
    capacity = threading.BoundedSemaphore(2)

    async def run() -> None:
        try:
            loop = asyncio.get_running_loop()
            executor = ThreadPoolExecutor(max_workers=1)
            loop.set_default_executor(executor)
            executor.shutdown(wait=True)
            with pytest.raises(RuntimeError, match="cannot schedule new futures"):
                loop.run_in_executor(None, lambda: None)

            for frame in frames[:2]:
                with pytest.raises(OSError, match="upload read failed"):
                    await preprocessing.read_detection_image_upload(
                        frame, _settings(), capacity=capacity,
                    )

            assert capacity.acquire(blocking=False)
            assert capacity.acquire(blocking=False)
            capacity.release()
            capacity.release()

            with pytest.raises(OSError, match="upload read failed"):
                await preprocessing.read_detection_image_upload(
                    frames[2], _settings(), capacity=capacity,
                )
            assert [frame.read_calls for frame in frames] == [1, 1, 1]
        finally:
            for frame in frames:
                await frame.close()

    asyncio.run(run())


def _worker_waiting_for_forced_cleanup(channel: socket.socket, ready_path: str) -> None:
    # A live worker that does not complete promptly on the graceful terminate attempt.
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    try:
        preprocessing._receive_frame(channel, deadline=None)
        Path(ready_path).touch()
        while True:
            time.sleep(0.01)
    finally:
        channel.close()


@pytest.mark.parametrize("ending", ["cancel", "deadline"])
@pytest.mark.parametrize("executor_state", ["shutdown", "busy"])
def test_active_cleanup_after_executor_shutdown_keeps_loop_and_slot_boundaries(
    tmp_path: Path, ending: str, executor_state: str,
) -> None:
    if sys.platform == "win32":
        pytest.skip("the controlled child requires POSIX SIGTERM handling")
    context = preprocessing.multiprocessing.get_context("spawn")
    ready = tmp_path / "worker.ready"
    allow_kill = threading.Event()
    termination_times = []
    processes = []
    channels = []
    real_socketpair = socket.socketpair

    class ControlledExitProcess:
        def __init__(self, channel):
            self.process = context.Process(
                target=_worker_waiting_for_forced_cleanup,
                args=(channel, str(ready)),
                daemon=True,
            )
            self.closed = False
            self.exitcode = None

        @property
        def pid(self):
            return self.process.pid

        def start(self):
            self.process.start()

        def is_alive(self):
            return self.process.is_alive()

        def terminate(self):
            termination_times.append(time.monotonic())
            self.process.terminate()

        def kill(self):
            # Keep actual cleanup pending until the event loop checks the retained slot.
            if allow_kill.is_set():
                self.process.kill()

        def join(self, timeout=None):
            self.process.join(timeout=timeout)

        def close(self):
            self.exitcode = self.process.exitcode
            self.process.close()
            self.closed = True

    def create_process(**kwargs):
        process = ControlledExitProcess(kwargs["args"][0])
        processes.append(process)
        return process

    def capture_socketpair():
        pair = real_socketpair()
        channels.append(pair)
        return pair

    async def wait_until(predicate, timeout=5.0):
        end = time.monotonic() + timeout
        while not predicate():
            assert time.monotonic() < end, "preprocessing did not reach the expected state"
            await asyncio.sleep(0.005)

    async def run():
        capacity = threading.BoundedSemaphore(1)
        settings = _settings()
        settings.inference_timeout_seconds = 1.5 if ending == "deadline" else 5.0
        frame = _Upload(_png(3, 2))
        task = None
        loop = asyncio.get_running_loop()
        executor = ThreadPoolExecutor(max_workers=1)
        loop.set_default_executor(executor)
        executor_release = threading.Event()
        if executor_state == "shutdown":
            executor.shutdown(wait=True)
        else:
            # This test owns the event loop and pool; no application pool is touched.
            executor.submit(executor_release.wait, 10.0)
        try:
            with patch.object(
                preprocessing.multiprocessing,
                "get_context",
                return_value=SimpleNamespace(Process=create_process),
            ), patch.object(preprocessing.socket, "socketpair", side_effect=capture_socketpair):
                task = asyncio.create_task(
                    preprocessing.read_detection_image_upload(frame, settings, capacity=capacity)
                )
                await wait_until(ready.exists)
                assert len(processes) == 1 and processes[0].is_alive()
                if ending == "cancel":
                    task.cancel()
                done, _ = await asyncio.wait(
                    {task}, timeout=2.0 if ending == "deadline" else 0.5,
                )
                assert task in done, "request completion waited for unrelated executor work"
                await wait_until(lambda: bool(termination_times))
                assert time.monotonic() - termination_times[0] < 0.2, (
                    "synchronous cleanup blocked the event loop"
                )
                assert not processes[0].closed
                assert not capacity.acquire(blocking=False)
                rejected = _Upload(_png(3, 2))
                try:
                    with pytest.raises(HTTPException) as busy:
                        await preprocessing.read_detection_image_upload(
                            rejected, settings, capacity=capacity,
                        )
                    assert busy.value.detail["code"] == "detect_preprocessing_capacity_exceeded"
                    assert rejected.read_calls == 0
                finally:
                    await rejected.close()
                if ending == "cancel":
                    with pytest.raises(asyncio.CancelledError):
                        await task
                else:
                    with pytest.raises(HTTPException) as expired:
                        await task
                    assert expired.value.detail["code"] == "detect_preprocessing_deadline_exceeded"
                assert not capacity.acquire(blocking=False)
                allow_kill.set()
                await wait_until(lambda: processes[0].closed)
                await wait_until(lambda: capacity.acquire(blocking=False))
                capacity.release()
                assert processes[0].exitcode is not None
                assert all(channel.fileno() == -1 for pair in channels for channel in pair)

            # The same unavailable default executor must not prevent the next real image.
            recovered = _Upload(_png(3, 2))
            try:
                content, content_type = await preprocessing.read_detection_image_upload(
                    recovered, _settings(), capacity=capacity,
                )
                assert content_type == "image/png"
                with Image.open(io.BytesIO(content)) as result:
                    result.load()
                    assert result.size == (3, 2)
            finally:
                await recovered.close()
            assert capacity.acquire(blocking=False)
            assert not capacity.acquire(blocking=False), "preprocessing released its slot twice"
            capacity.release()
        finally:
            executor_release.set()
            allow_kill.set()
            if task is not None:
                if not task.done():
                    task.cancel()
                await asyncio.gather(task, return_exceptions=True)
            await wait_until(lambda: all(process.closed for process in processes))
            await frame.close()
            executor.shutdown(wait=True)

    asyncio.run(run())


@pytest.mark.parametrize(("width", "height"), [(1_000, 1_000), (_WIDTH, _HEIGHT)],
                         ids=["one-megapixel", "max-pixels"])
def test_two_real_rgb_workers_report_co_resident_resource_usage(width: int, height: int) -> None:
    if sys.platform != "linux" or not Path("/proc/self/smaps_rollup").is_file():
        pytest.skip("Linux smaps_rollup is required for concurrent PSS/RSS evidence")

    colors = ((17, 73, 149), (221, 109, 31))
    assert DETECT_PREPROCESSING_MAX_CONCURRENT == 2
    assert width * height <= uploads.MAX_IMAGE_PIXELS
    contents = [_rgb_png(color, width, height) for color in colors]
    assert contents[0] != contents[1]
    assert all(len(content) < 1024 * 1024 for content in contents)
    frames = [_Upload(contents[0]), _Upload(contents[1]), _Upload(contents[0])]
    capacity = threading.BoundedSemaphore(DETECT_PREPROCESSING_MAX_CONCURRENT)
    settings = _settings()
    settings.inference_timeout_seconds = DEFAULT_INFERENCE_TIMEOUT_SECONDS

    async def run(barrier: Path) -> None:
        (barrier / "dimensions.json").write_text(json.dumps([width, height]))
        samples = []
        sampling = True

        async def sample_resources() -> None:
            while sampling:
                worker_pids = [int(marker.name.split(".", 1)[0])
                               for marker in barrier.glob("*.decode_enter.ready")]
                measurements = {}
                for pid in [os.getpid(), *worker_pids]:
                    try:
                        measurements[pid] = _memory_kib(pid)
                    except (FileNotFoundError, ProcessLookupError):
                        continue  # A completed child can exit between discovery and sampling.
                samples.append({
                    "time_ns": time.monotonic_ns(),
                    "parent_rss_kib": measurements[os.getpid()][1],
                    "workers": len(measurements) - 1,
                    "sum_rss_kib": sum(rss for _, rss in measurements.values()),
                    "sum_pss_kib": sum(pss for pss, _ in measurements.values()),
                })
                await asyncio.sleep(0.01)

        sampler = asyncio.create_task(sample_resources())
        request_started_ns = time.monotonic_ns()
        tasks = [
            asyncio.create_task(
                preprocessing.read_detection_image_upload(
                    frame, settings, capacity=capacity,
                )
            )
            for frame in frames[:2]
        ]
        try:
            async def wait_for_phase(phase: str) -> list[int]:
                deadline = asyncio.get_running_loop().time() + 10.0
                while True:
                    assert not list(barrier.glob("*.rescued")), "a worker exceeded a 2s phase hold"
                    ready = list(barrier.glob(f"*.{phase}.ready"))
                    if len(ready) == 2:
                        samples = [tuple(map(int, marker.read_text().split())) for marker in ready]
                        pids = [pid for pid, pixels in samples]
                        assert len(set(pids)) == 2
                        assert all(pixels == width * height for _, pixels in samples)
                        return pids
                    if any(task.done() for task in tasks):
                        raise AssertionError(f"a worker exited before {phase}")
                    if asyncio.get_running_loop().time() >= deadline:
                        raise AssertionError(f"two workers did not reach {phase}")
                    await asyncio.sleep(0.01)

            pids = await wait_for_phase("decode_enter")
            (barrier / "decode_enter.release").touch()
            assert set(await wait_for_phase("decode_done")) == set(pids)

            load_intervals = []
            for pid in pids:
                recorded_pid, started_ns, finished_ns = map(
                    int, (barrier / f"{pid}.load_interval.ready").read_text().split(),
                )
                assert recorded_pid == pid and started_ns < finished_ns
                load_intervals.append((started_ns, finished_ns))
            overlap_ns = max(
                0,
                min(finished for _, finished in load_intervals)
                - max(started for started, _ in load_intervals),
            )
            # Entry synchronization does not guarantee that the load calls overlap.
            print(
                "preprocessing decode observation: "
                f"load_durations_ms={[(finished - started) / 1_000_000 for started, finished in load_intervals]} "
                f"strict_load_overlap_ms={overlap_ns / 1_000_000} "
                f"strict_load_overlap_observed={overlap_ns > 0}"
            )

            def assert_held(phase: str) -> None:
                assert not (barrier / f"{phase}.release").exists(), f"{phase} was released"
                assert not list(barrier.glob("*.rescued")), f"a worker escaped a phase hold during {phase}"
                held_pids = {
                    int(marker.name.split(".", 1)[0])
                    for marker in barrier.glob(f"*.{phase}.ready")
                }
                assert held_pids == set(pids), f"both workers must be held at {phase}"
                assert not any(task.done() for task in tasks), f"a worker exited during {phase}"

            def measure(phase: str) -> None:
                assert_held(phase)
                measurements = {os.getpid(): _memory_kib(os.getpid())}
                measurements.update({pid: _memory_kib(pid) for pid in pids})
                assert_held(phase)
                assert all(pss > 0 and rss >= pss for pss, rss in measurements.values())
                print(
                    "preprocessing resource observation: "
                    f"phase={phase} co_resident_pixels={len(pids) * width * height} "
                    f"parent_pid={os.getpid()} parent_pss_kib={measurements[os.getpid()][0]} "
                    f"parent_rss_kib={measurements[os.getpid()][1]} "
                    f"child_measurements_kib={[(pid, *measurements[pid]) for pid in pids]} "
                    f"total_pss_kib={sum(pss for pss, _ in measurements.values())} "
                    f"total_rss_kib={sum(rss for _, rss in measurements.values())} "
                    "instantaneous_not_peak=true os_variability=true"
                )

            measure("decode_done")
            (barrier / "decode_done.release").touch()
            assert set(await wait_for_phase("save_ready")) == set(pids)
            measure("save_ready")

            with pytest.raises(HTTPException) as caught:
                await preprocessing.read_detection_image_upload(
                    frames[2], _settings(), capacity=capacity,
                )
            assert caught.value.status_code == 503
            assert caught.value.detail["code"] == "detect_preprocessing_capacity_exceeded"
            assert frames[2].read_calls == 0
            assert_held("save_ready")

            (barrier / "save_ready.release").touch()
            assert set(await wait_for_phase("save_done")) == set(pids)
            measure("save_done")
            save_intervals = [
                tuple(map(int, (barrier / f"{pid}.save_interval.ready").read_text().split()))
                for pid in pids
            ]
            save_overlap_ns = max(0, min(end for _, _, end in save_intervals)
                                  - max(start for _, start, _ in save_intervals))
            (barrier / "save_done.release").touch()
            results = await asyncio.wait_for(asyncio.gather(*tasks), timeout=30.0)
            request_finished_ns = time.monotonic_ns()
            sampling = False
            await sampler
            assert any(sample["workers"] == 2 for sample in samples)
            print("preprocessing measured resources: " + json.dumps({
                "per_image_pixels": width * height, "concurrency": len(pids),
                "co_resident_pixels": len(pids) * width * height,
                "time_budget_seconds": settings.inference_timeout_seconds,
                "requests_elapsed_ms": (request_finished_ns - request_started_ns) / 1_000_000,
                "decode_overlap_ms": overlap_ns / 1_000_000,
                "encode_overlap_ms": save_overlap_ns / 1_000_000,
                "sampled_max_sum_rss_kib": max(sample["sum_rss_kib"] for sample in samples),
                "sampled_max_sum_pss_kib": max(sample["sum_pss_kib"] for sample in samples),
                "sampled_max_parent_rss_kib": max(sample["parent_rss_kib"] for sample in samples),
                "samples": len(samples), "sampling_interval_seconds": 0.01,
                "sampled_not_exact_peak": True, "third_admission_rejected_before_read": True,
            }, sort_keys=True))
            for (sanitized, content_type), color in zip(results, colors):
                assert content_type == "image/png"
                with Image.open(io.BytesIO(sanitized)) as image:
                    assert image.size == (width, height)
                    assert image.mode == "RGB"
                    assert image.getpixel((0, 0)) == color
            acquired = 0
            try:
                for _ in range(2):
                    assert capacity.acquire(blocking=False)
                    acquired += 1
            finally:
                for _ in range(acquired):
                    capacity.release()
        finally:
            for phase in ("decode_enter", "decode_done", "save_ready", "save_done"):
                (barrier / f"{phase}.release").touch()
            for task in tasks:
                if not task.done():
                    task.cancel()
            await asyncio.wait_for(asyncio.gather(*tasks, return_exceptions=True), timeout=10.0)
            sampling = False
            await sampler
            for frame in frames:
                await frame.close()

    with TemporaryDirectory(prefix="walksafe-preprocessing-resource-") as directory:
        with patch.dict(os.environ, {_BARRIER_ENV: directory}):
            with patch.object(
                preprocessing, "_preprocessing_worker", _worker_with_resource_barrier,
            ):
                asyncio.run(run(Path(directory)))


@pytest.mark.skipif(sys.platform != "linux", reason="Linux proc is used to confirm worker exit")
def test_two_decoded_workers_timeout_release_capacity_and_recover(tmp_path: Path) -> None:
    """Hold inside real Pillow save, without large input or a simulated worker."""
    width, height = 1_000, 1_000
    (tmp_path / "dimensions.json").write_text(json.dumps([width, height]))
    settings = _settings()
    settings.inference_timeout_seconds = DEFAULT_INFERENCE_TIMEOUT_SECONDS
    capacity = threading.BoundedSemaphore(DETECT_PREPROCESSING_MAX_CONCURRENT)
    frames = [_Upload(_rgb_png(color, width, height))
              for color in ((17, 73, 149), (221, 109, 31))]

    async def run():
        started = time.monotonic()
        tasks = [asyncio.create_task(preprocessing.read_detection_image_upload(
            frame, settings, capacity=capacity,
        )) for frame in frames]
        try:
            async def phase_pids(phase):
                until = time.monotonic() + 5.0
                while len(markers := list(tmp_path.glob(f"*.{phase}.ready"))) != 2:
                    assert time.monotonic() < until, f"workers did not reach {phase}"
                    assert not any(task.done() for task in tasks)
                    await asyncio.sleep(0.005)
                return {int(marker.name.split(".", 1)[0]) for marker in markers}

            pids = await phase_pids("decode_enter")
            (tmp_path / "decode_enter.release").touch()
            assert await phase_pids("decode_done") == pids
            (tmp_path / "decode_done.release").touch()
            assert await phase_pids("save_ready") == pids
            assert len(pids) == DETECT_PREPROCESSING_MAX_CONCURRENT == 2
            assert all(Path(f"/proc/{pid}").exists() for pid in pids)
            # Deliberately leave save_ready held: the real child cannot finish
            # before its parent's existing request budget expires.
            outcomes = await asyncio.wait_for(asyncio.gather(*tasks, return_exceptions=True),
                                              timeout=settings.inference_timeout_seconds + 3.0)
            response_elapsed = time.monotonic() - started
            assert all(isinstance(result, HTTPException) for result in outcomes)
            assert all(result.status_code == 503 for result in outcomes)
            assert all(result.detail["code"] == "detect_preprocessing_deadline_exceeded"
                       for result in outcomes)
            assert not list(tmp_path.glob("*.rescued"))
            until = time.monotonic() + 3.0
            while any(Path(f"/proc/{pid}").exists() for pid in pids):
                assert time.monotonic() < until, "timed-out child was not reaped"
                await asyncio.sleep(0.005)
            acquired = 0
            try:
                while acquired < 2:
                    if capacity.acquire(blocking=False):
                        acquired += 1
                    else:
                        assert time.monotonic() < until, "confirmed cleanup retained its slot"
                        await asyncio.sleep(0.005)
                assert not capacity.acquire(blocking=False), "capacity released more than twice"
            finally:
                for _ in range(acquired):
                    capacity.release()
            cleanup_elapsed = time.monotonic() - started
            recovered = _Upload(_png(3, 2))
            try:
                content, content_type = await preprocessing.read_detection_image_upload(
                    recovered, settings, capacity=capacity,
                )
                assert content_type == "image/png"
                with Image.open(io.BytesIO(content)) as decoded:
                    decoded.load()
                    assert decoded.size == (3, 2)
            finally:
                await recovered.close()
            print("preprocessing timeout recovery: " + json.dumps({
                "workers": 2, "co_resident_pixels": 2 * width * height,
                "time_budget_seconds": settings.inference_timeout_seconds,
                "deadline_responses": 2, "response_elapsed_seconds": response_elapsed,
                "confirmed_cleanup_elapsed_seconds": cleanup_elapsed,
                "workers_reaped": True, "capacity_returned_exactly": 2,
                "next_png_succeeded": True,
            }, sort_keys=True))
        finally:
            for phase in ("decode_enter", "decode_done", "save_ready", "save_done"):
                (tmp_path / f"{phase}.release").touch()
            for task in tasks:
                if not task.done():
                    task.cancel()
            await asyncio.wait_for(asyncio.gather(*tasks, return_exceptions=True), timeout=5.0)
            for frame in frames:
                await frame.close()

    with patch.dict(os.environ, {_BARRIER_ENV: str(tmp_path)}), patch.object(
        preprocessing, "_preprocessing_worker", _worker_with_resource_barrier,
    ):
        asyncio.run(run())

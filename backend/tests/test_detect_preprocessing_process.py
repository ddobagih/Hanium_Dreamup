"""Exercise process-isolated detector image preprocessing without model execution."""

from __future__ import annotations

import asyncio
from concurrent.futures import Future
import io
import socket
import threading
import time
from types import SimpleNamespace
from unittest.mock import patch

import pytest
from fastapi import HTTPException
from PIL import Image, PngImagePlugin
from starlette.datastructures import Headers, UploadFile

from backend.app.api import detect
from backend.app.services import _detection_preprocessing as preprocessing


class _Upload(UploadFile):
    def __init__(self, content: bytes, *, filename: str = "frame.png") -> None:
        super().__init__(
            file=io.BytesIO(content),
            filename=filename,
            headers=Headers({"content-type": "image/png"}),
        )


class _SlowUpload:
    content_type = "image/png"
    filename = "frame.png"

    async def read(self, size: int = -1) -> bytes:
        await asyncio.sleep(60)
        return b""


class _BlockingUpload:
    content_type = "image/png"
    filename = "frame.png"

    def __init__(self, content: bytes, started: asyncio.Event, release: asyncio.Event) -> None:
        self._content = content
        self._started = started
        self._release = release

    async def read(self, size: int = -1) -> bytes:
        self._started.set()
        await self._release.wait()
        return self._content[:size] if size >= 0 else self._content


def _png_bytes() -> bytes:
    output = io.BytesIO()
    with Image.new("RGB", (3, 2), (5, 17, 29)) as image:
        image.save(output, format="PNG")
    return output.getvalue()


def _emit_raw_frame_then_wait(channel: socket.socket, frame: bytes | None) -> None:
    try:
        if frame is not None:
            channel.sendall(frame)
        time.sleep(30)
    finally:
        channel.close()


def _settings(*, timeout: float = 5.0):
    return SimpleNamespace(
        allowed_image_content_types={"image/png"},
        max_upload_bytes=1024 * 1024,
        inference_timeout_seconds=timeout,
    )


class TestDetectPreprocessingProcess:
    def _capacity(self):
        return threading.BoundedSemaphore(2)

    async def _read(self, upload, *, settings=None):
        return await detect._read_detection_image_upload(
            upload,
            _settings() if settings is None else settings,
        )

    def _production_path(self, capacity):
        return patch.multiple(
            detect,
            _DETECT_PREPROCESSING_EXECUTOR=None,
            _DETECT_PREPROCESSING_CAPACITY=capacity,
        )

    @pytest.mark.parametrize("failure_stage", ["process", "thread"])
    def test_pre_registration_construction_failure_closes_real_sockets(self, failure_stage):
        real_socketpair = socket.socketpair
        channels = []
        processes = []

        def capture_socketpair():
            pair = real_socketpair()
            channels.append(pair)
            return pair

        class Process:
            def __init__(self):
                self.closed = False
                processes.append(self)

            def close(self):
                self.closed = True

        def create_process(**kwargs):
            if failure_stage == "process":
                raise RuntimeError("injected Process construction failure")
            return Process()

        context = SimpleNamespace(Process=create_process)
        with patch.object(preprocessing.multiprocessing, "get_context", return_value=context):
            job = preprocessing._ProcessSanitizationExecutor(
                deadline=time.monotonic() + 1,
                on_closed=lambda _job: None,
            )
        with patch.object(preprocessing.socket, "socketpair", side_effect=capture_socketpair):
            with patch.object(
                preprocessing.threading,
                "Thread",
                side_effect=RuntimeError("injected IPC Thread construction failure"),
            ):
                with pytest.raises(RuntimeError, match="construction failure"):
                    job.submit(None, b"pixels", "image/png")

        assert len(channels) == 1
        assert all(channel.fileno() == -1 for channel in channels[0])
        assert job._future is None
        assert job._parent is None
        assert job._child is None
        assert len(processes) == (failure_stage == "thread")
        assert all(process.closed for process in processes)

    @pytest.mark.parametrize("failure_stage", ["process", "thread"])
    def test_pre_registration_cleanup_attempts_every_resource_after_first_close_fails(
        self, failure_stage,
    ):
        original = RuntimeError("injected construction failure")

        class Parent:
            close_attempts = 0

            def close(self):
                self.close_attempts += 1
                raise OSError("injected parent close failure")

        class Child:
            closed = False

            def close(self):
                self.closed = True

        class Process:
            closed = False

            def close(self):
                self.closed = True

        parent, child = Parent(), Child()
        processes = []

        def create_process(**kwargs):
            if failure_stage == "process":
                raise original
            process = Process()
            processes.append(process)
            return process

        context = SimpleNamespace(Process=create_process)
        with patch.object(preprocessing.multiprocessing, "get_context", return_value=context):
            job = preprocessing._ProcessSanitizationExecutor(
                deadline=time.monotonic() + 1,
                on_closed=lambda _job: None,
            )
        with patch.object(preprocessing.socket, "socketpair", return_value=(parent, child)):
            with patch.object(preprocessing.threading, "Thread", side_effect=original):
                with pytest.raises(RuntimeError) as caught:
                    job.submit(None, b"pixels", "image/png")

        assert caught.value is original
        assert parent.close_attempts == 1
        assert child.closed
        assert len(processes) == (failure_stage == "thread")
        assert all(process.closed for process in processes)
        assert job._future is None
        assert job._parent is None
        assert job._child is None

    def test_request_wire_uses_fixed_tag_and_primitive_bytes(self):
        sender, receiver = socket.socketpair()
        try:
            preprocessing._send_frame(
                sender, b"Q", b"\x09", b"image/png", b"pixels", deadline=None
            )
            payload = b"\x09image/pngpixels"
            assert receiver.recv(1024) == b"Q" + len(payload).to_bytes(4, "big") + payload
        finally:
            sender.close()
            receiver.close()

    def test_child_memory_error_closes_channel_instead_of_reporting_invalid_image(self):
        parent, child = socket.socketpair()
        try:
            preprocessing._send_frame(
                parent, b"Q", b"\x09", b"image/png", b"pixels", deadline=None
            )
            with patch(
                "backend.app.uploads.strip_image_metadata",
                side_effect=MemoryError("injected child memory exhaustion"),
            ):
                with pytest.raises(MemoryError):
                    preprocessing._preprocessing_worker(child)
            with pytest.raises(EOFError):
                preprocessing._receive_frame(
                    parent, deadline=time.monotonic() + 0.25
                )
        finally:
            parent.close()
            child.close()

    def test_decoder_memory_error_in_real_sanitizer_closes_channel(self):
        content = _png_bytes()
        parent, child = socket.socketpair()
        try:
            preprocessing._send_frame(
                parent, b"Q", b"\x09", b"image/png", content, deadline=None
            )
            with patch.object(
                PngImagePlugin.PngImageFile,
                "load",
                side_effect=MemoryError("injected PNG decode exhaustion"),
            ) as decode:
                with pytest.raises(MemoryError, match="injected PNG decode exhaustion"):
                    preprocessing._preprocessing_worker(child)
            assert decode.called
            with pytest.raises(EOFError):
                preprocessing._receive_frame(
                    parent, deadline=time.monotonic() + 0.25
                )
        finally:
            parent.close()
            child.close()

    def test_outgoing_oversize_frame_is_rejected_before_any_write(self):
        sender, receiver = socket.socketpair()
        try:
            with patch.object(preprocessing, "_MAX_FRAME_BYTES", 4):
                with pytest.raises(preprocessing._FrameProtocolError):
                    preprocessing._send_frame(sender, b"O", b"12345", deadline=None)
            receiver.settimeout(0.01)
            with pytest.raises(socket.timeout):
                receiver.recv(1)
        finally:
            sender.close()
            receiver.close()

    @pytest.mark.parametrize(
        "wire",
        [
            b"O" + (5).to_bytes(4, "big"),
            b"X" + (0).to_bytes(4, "big"),
            b"I" + (1).to_bytes(4, "big") + b"x",
        ],
        ids=("oversize", "unknown-tag", "error-with-payload"),
    )
    def test_incoming_oversize_or_invalid_header_is_rejected_without_body(self, wire):
        sender, receiver = socket.socketpair()
        try:
            sender.sendall(wire)
            with patch.object(preprocessing, "_MAX_FRAME_BYTES", 4):
                with pytest.raises(preprocessing._FrameProtocolError):
                    preprocessing._receive_frame(
                        receiver, deadline=time.monotonic() + 0.25
                    )
        finally:
            sender.close()
            receiver.close()

    @pytest.mark.parametrize(
        "frame, timeout, expected",
        [
            (b"X" + (0).to_bytes(4, "big"), 3.0, preprocessing.DetectionPreprocessingUnavailable),
            (b"O" + (preprocessing._MAX_FRAME_BYTES + 1).to_bytes(4, "big"), 3.0, preprocessing.DetectionPreprocessingUnavailable),
            (None, 0.5, preprocessing.DetectionPreprocessingDeadlineExceeded),
        ],
        ids=("invalid-tag", "oversize", "timeout"),
    )
    def test_bad_child_frame_or_timeout_reaps_child_and_releases_slot(self, frame, timeout, expected):
        context = preprocessing.multiprocessing.get_context("spawn")
        processes = []

        class RawFrameProcess:
            def __init__(self, channel):
                self._process = context.Process(
                    target=_emit_raw_frame_then_wait,
                    args=(channel, frame),
                    daemon=True,
                )
                self.terminated = False
                self.joined = False
                self.closed = False

            @property
            def pid(self):
                return self._process.pid

            def start(self):
                self._process.start()

            def is_alive(self):
                return self._process.is_alive()

            def terminate(self):
                self.terminated = True
                self._process.terminate()

            def kill(self):
                self._process.kill()

            def join(self, timeout=None):
                self.joined = True
                self._process.join(timeout=timeout)

            def close(self):
                self._process.close()
                self.closed = True

        def create_process(**kwargs):
            process = RawFrameProcess(kwargs["args"][0])
            processes.append(process)
            return process

        capacity = threading.BoundedSemaphore(1)
        assert capacity.acquire(blocking=False)
        released = threading.Event()

        def on_closed(_job):
            capacity.release()
            released.set()

        job = None
        try:
            with patch.object(
                preprocessing.multiprocessing,
                "get_context",
                return_value=SimpleNamespace(Process=create_process),
            ):
                job = preprocessing._ProcessSanitizationExecutor(
                    deadline=time.monotonic() + timeout,
                    on_closed=on_closed,
                )
                future = job.submit(None, _png_bytes(), "image/png")
                with pytest.raises(expected):
                    future.result(timeout=5)
            assert released.wait(timeout=2)
            assert len(processes) == 1
            assert processes[0].terminated
            assert processes[0].joined
            assert processes[0].closed
            assert capacity.acquire(blocking=False)
        finally:
            if job is not None:
                job.close()

    def test_real_image_is_sanitized_in_a_child_process(self):
        async def run():
            with self._production_path(self._capacity()):
                content, content_type = await self._read(_Upload(_png_bytes()))
            assert content_type == "image/png"
            with Image.open(io.BytesIO(content)) as image:
                image.load()
                assert image.size == (3, 2)
                assert image.getpixel((0, 0)) == (5, 17, 29)

        asyncio.run(run())

    def test_invalid_image_keeps_existing_400_contract_and_returns_capacity(self):
        async def run():
            capacity = self._capacity()
            with self._production_path(capacity):
                try:
                    await self._read(_Upload(b"\x89PNG\r\n\x1a\nbroken"))
                except HTTPException as error:
                    assert error.status_code == 400
                    assert error.detail["code"] == "invalid_image_content"
                else:
                    raise AssertionError("invalid image unexpectedly succeeded")
            assert capacity.acquire(blocking=False)

        asyncio.run(run())

    def test_upload_read_is_inside_the_preprocessing_budget(self):
        async def run():
            capacity = self._capacity()
            with self._production_path(capacity):
                try:
                    await self._read(
                        _SlowUpload(),
                        settings=_settings(timeout=0.1),
                    )
                except HTTPException as error:
                    assert error.status_code == 503
                    assert error.detail == {
                        "code": "detect_preprocessing_deadline_exceeded",
                        "message": "Image preprocessing exceeded its time budget. Retry with a fresh frame.",
                        "retry_after_seconds": 1,
                    }
                    assert error.headers["Retry-After"] == "1"
                else:
                    raise AssertionError("blocked upload unexpectedly succeeded")
            assert capacity.acquire(blocking=False)

        asyncio.run(run())

    def test_two_process_slots_have_no_waiting_third_request(self):
        async def run():
            capacity = self._capacity()
            first_started = asyncio.Event()
            second_started = asyncio.Event()
            release = asyncio.Event()
            first = _BlockingUpload(_png_bytes(), first_started, release)
            second = _BlockingUpload(_png_bytes(), second_started, release)
            with self._production_path(capacity):
                first_task = asyncio.create_task(self._read(first))
                second_task = asyncio.create_task(self._read(second))
                await asyncio.wait_for(first_started.wait(), timeout=1.0)
                await asyncio.wait_for(second_started.wait(), timeout=1.0)
                try:
                    await self._read(_Upload(_png_bytes()))
                except HTTPException as error:
                    assert error.status_code == 503
                    assert error.detail["code"] == "detect_preprocessing_capacity_exceeded"
                else:
                    raise AssertionError("third preprocessing request was queued")
                release.set()
                first_result, second_result = await asyncio.gather(first_task, second_task)
            assert first_result[1] == "image/png"
            assert second_result[1] == "image/png"

        asyncio.run(run())

    def test_runtime_close_allows_a_later_lifespan_to_preprocess(self):
        async def run():
            capacity = self._capacity()
            with self._production_path(capacity):
                detect.close_detection_preprocessing()
                content, content_type = await self._read(_Upload(_png_bytes()))
                assert content_type == "image/png"
                with Image.open(io.BytesIO(content)) as image:
                    image.load()
                    assert image.size == (3, 2)
            assert capacity.acquire(blocking=False)

        asyncio.run(run())

    def test_child_channel_failure_is_a_retryable_service_error(self):
        async def run():
            capacity = self._capacity()
            with self._production_path(capacity):
                with patch.object(preprocessing, "_receive_frame", side_effect=EOFError("closed")):
                    with pytest.raises(HTTPException) as caught:
                        await self._read(_Upload(_png_bytes()))
            assert caught.value.status_code == 503
            assert caught.value.detail["code"] == "detect_preprocessing_unavailable"
            assert caught.value.headers["Retry-After"] == "1"
            assert capacity.acquire(blocking=False)

        asyncio.run(run())

    def test_unconfirmed_child_keeps_its_slot_until_reaped(self):
        class StubbornProcess:
            def __init__(self):
                self.alive = True
                self.joins = []
                self.kills = 0
                self.closed = False

            def is_alive(self):
                return self.alive

            def terminate(self):
                pass

            def kill(self):
                self.kills += 1

            def join(self, timeout=None):
                assert timeout is not None and 0 < timeout < 1
                self.joins.append(timeout)

            def close(self):
                self.closed = True

        capacity = threading.BoundedSemaphore(1)
        assert capacity.acquire(blocking=False)
        released = threading.Event()

        def on_closed(_job):
            capacity.release()
            released.set()

        job = preprocessing._ProcessSanitizationExecutor(
            deadline=time.monotonic() + 1,
            on_closed=on_closed,
        )
        process = StubbornProcess()
        job._process = process
        job._process_started = True
        with pytest.raises(preprocessing.DetectionPreprocessingCleanupError):
            job.close()
        assert process.kills >= 1
        assert len(process.joins) >= 2
        assert not capacity.acquire(blocking=False)
        process.alive = False
        assert released.wait(timeout=2), "late child exit was not reaped"
        assert process.closed
        assert capacity.acquire(blocking=False)

    def test_competing_completions_deliver_future_and_release_capacity_once(self):
        capacity = threading.BoundedSemaphore(1)
        assert capacity.acquire(blocking=False)
        releases = []

        def on_closed(_job):
            releases.append(1)
            capacity.release()

        job = preprocessing._ProcessSanitizationExecutor(
            deadline=time.monotonic() + 1,
            on_closed=on_closed,
        )
        future = Future()
        job._future = future
        job._process = SimpleNamespace(close=lambda: None)
        job._process_started = False
        rendezvous = threading.Barrier(2)
        errors = []

        def complete():
            try:
                job._complete(b"unused", None)
            except BaseException as exc:
                errors.append(exc)

        with patch.object(job, "_start_reaper", side_effect=lambda: rendezvous.wait(timeout=2)):
            # Force both callers past the old Future.done() check before either delivery.
            with patch.object(future, "done", return_value=False):
                with patch.object(future, "set_exception", wraps=future.set_exception) as deliver:
                    workers = [threading.Thread(target=complete) for _ in range(2)]
                    for worker in workers:
                        worker.start()
                    for worker in workers:
                        worker.join(timeout=3)
                    assert all(not worker.is_alive() for worker in workers)
                    assert not rendezvous.broken
                    assert not errors
                    assert deliver.call_count == 1

        assert isinstance(future.exception(), preprocessing.DetectionPreprocessingUnavailable)
        assert releases == []
        job.close()
        job.close()
        assert releases == [1]
        assert capacity.acquire(blocking=False)
        assert not capacity.acquire(blocking=False)

    def test_reaper_start_failure_is_retryable_and_keeps_503_sanitized(self):
        class StubbornProcess:
            alive = True
            closed = False

            def is_alive(self):
                return self.alive

            def terminate(self):
                pass

            def kill(self):
                pass

            def join(self, timeout=None):
                pass

            def close(self):
                self.closed = True

        capacity = threading.BoundedSemaphore(1)
        assert capacity.acquire(blocking=False)
        released = threading.Event()
        releases = []

        def on_closed(_job):
            releases.append(1)
            capacity.release()
            released.set()

        job = preprocessing._ProcessSanitizationExecutor(
            deadline=time.monotonic() + 1,
            on_closed=on_closed,
        )
        future = Future()
        job._future = future
        process = StubbornProcess()
        job._process = process
        job._process_started = True
        original_start = threading.Thread.start
        reaper_starts = []

        def start_with_one_failure(thread):
            if thread.name == "detect-preprocessing-reaper":
                reaper_starts.append(thread)
                if len(reaper_starts) == 1:
                    raise RuntimeError("thread unavailable")
            return original_start(thread)

        with patch.object(threading.Thread, "start", new=start_with_one_failure):
            with pytest.raises(preprocessing.DetectionPreprocessingCleanupError):
                job.close()
            assert job._reaper is None
            error = future.exception(timeout=0)
            assert isinstance(error, preprocessing.DetectionPreprocessingUnavailable)
            assert error.status_code == 503
            assert error.detail["code"] == "detect_preprocessing_unavailable"
            assert error.headers["Retry-After"] == "1"
            assert not capacity.acquire(blocking=False)

            with pytest.raises(preprocessing.DetectionPreprocessingCleanupError):
                job.close()
            assert len(reaper_starts) == 2
            assert job._reaper is reaper_starts[1]
            process.alive = False
            assert released.wait(timeout=2), "retry reaper did not release the slot"

        job._reaper.join(timeout=2)
        assert not job._reaper.is_alive()
        assert process.closed
        job.close()
        assert releases == [1]
        assert capacity.acquire(blocking=False)
        assert not capacity.acquire(blocking=False)

    def test_cancelled_upload_returns_capacity_after_confirmed_cleanup(self):
        async def run():
            capacity = threading.BoundedSemaphore(1)
            started = asyncio.Event()
            release = asyncio.Event()
            upload = _BlockingUpload(_png_bytes(), started, release)
            with self._production_path(capacity):
                task = asyncio.create_task(self._read(upload))
                await asyncio.wait_for(started.wait(), timeout=1)
                assert not capacity.acquire(blocking=False)
                task.cancel()
                with pytest.raises(asyncio.CancelledError):
                    await task
                for _ in range(100):
                    if capacity.acquire(blocking=False):
                        break
                    await asyncio.sleep(0.01)
                else:
                    raise AssertionError("cancelled upload retained a cleaned slot")

        asyncio.run(run())

    def test_blocked_process_start_keeps_loop_deadline_and_slots(self):
        release = threading.Event()
        both_started = threading.Event()
        start_lock = threading.Lock()
        start_entries = 0
        processes = []

        class BlockedProcess:
            def __init__(self):
                self.pid = None
                self.alive = False
                self.closed = False
                processes.append(self)

            def start(self):
                nonlocal start_entries
                with start_lock:
                    start_entries += 1
                    if start_entries == 2:
                        both_started.set()
                if not release.wait(timeout=2):
                    raise RuntimeError("test Process.start remained blocked")
                self.pid = 123
                self.alive = True

            def is_alive(self):
                return self.alive

            def terminate(self):
                self.alive = False

            def kill(self):
                self.alive = False

            def join(self, timeout=None):
                pass

            def close(self):
                self.closed = True

        async def run():
            capacity = self._capacity()
            first = second = None
            rescue = threading.Timer(1.5, release.set)
            rescue.start()
            try:
                context = SimpleNamespace(Process=lambda **kwargs: BlockedProcess())
                with patch.object(preprocessing.multiprocessing, "get_context", return_value=context):
                    with self._production_path(capacity):
                        started_at = time.monotonic()
                        first = asyncio.create_task(self._read(_Upload(_png_bytes()), settings=_settings(timeout=0.1)))
                        second = asyncio.create_task(self._read(_Upload(_png_bytes()), settings=_settings(timeout=0.1)))
                        heartbeat = asyncio.create_task(asyncio.sleep(0.02))
                        await asyncio.wait_for(asyncio.to_thread(both_started.wait, 0.5), timeout=0.7)
                        assert both_started.is_set()
                        await heartbeat
                        assert time.monotonic() - started_at < 0.5, "Process.start blocked the event loop"
                        first.cancel()
                        with pytest.raises(asyncio.CancelledError):
                            await asyncio.wait_for(first, timeout=0.5)
                        with pytest.raises(HTTPException) as caught:
                            await asyncio.wait_for(second, timeout=0.5)
                        assert caught.value.status_code == 503
                        assert caught.value.detail["code"] == "detect_preprocessing_deadline_exceeded"
                        assert not release.is_set()
                        assert not capacity.acquire(blocking=False)
                        with pytest.raises(HTTPException) as busy:
                            await self._read(_Upload(_png_bytes()))
                        assert busy.value.detail["code"] == "detect_preprocessing_capacity_exceeded"
            finally:
                release.set()
                rescue.cancel()
                await asyncio.gather(*(task for task in (first, second) if task is not None), return_exceptions=True)

            for _ in range(100):
                if capacity.acquire(blocking=False):
                    break
                await asyncio.sleep(0.01)
            else:
                raise AssertionError("first blocked start kept its slot after cleanup")
            for _ in range(100):
                if capacity.acquire(blocking=False):
                    break
                await asyncio.sleep(0.01)
            else:
                raise AssertionError("second blocked start kept its slot after cleanup")
            assert len(processes) == 2
            assert all(process.closed for process in processes)
            assert not capacity.acquire(blocking=False)

        asyncio.run(run())

    @pytest.mark.parametrize("start_fails", [False, True])
    def test_child_close_failure_completes_future_and_retains_slot(self, start_fails):
        allow_child_close = threading.Event()
        channels = []
        processes = []

        class Parent:
            closed = False

            def close(self):
                self.closed = True

        class Child:
            closed = False

            def close(self):
                if not allow_child_close.is_set():
                    raise OSError("injected child close failure")
                self.closed = True

        class Process:
            def __init__(self):
                self.pid = None
                self.alive = False
                self.closed = False
                processes.append(self)

            def start(self):
                if start_fails:
                    raise OSError("injected process start failure")
                self.pid = 123
                self.alive = True

            def is_alive(self):
                return self.alive

            def terminate(self):
                self.alive = False

            def kill(self):
                self.alive = False

            def join(self, timeout=None):
                pass

            def close(self):
                self.closed = True

        def socketpair():
            pair = (Parent(), Child())
            channels.append(pair)
            return pair

        async def run():
            capacity = threading.BoundedSemaphore(1)
            context = SimpleNamespace(Process=lambda **kwargs: Process())
            with patch.object(preprocessing.multiprocessing, "get_context", return_value=context):
                with patch.object(preprocessing.socket, "socketpair", side_effect=socketpair):
                    with self._production_path(capacity):
                        try:
                            with pytest.raises(HTTPException) as caught:
                                await asyncio.wait_for(
                                    self._read(_Upload(_png_bytes()), settings=_settings(timeout=1)),
                                    timeout=0.5,
                                )
                            assert caught.value.status_code == 503
                            assert caught.value.detail["code"] == "detect_preprocessing_unavailable"
                            assert not capacity.acquire(blocking=False)
                            assert len(channels) == 1
                            assert not channels[0][1].closed
                        finally:
                            allow_child_close.set()

            for _ in range(100):
                if capacity.acquire(blocking=False):
                    break
                await asyncio.sleep(0.01)
            else:
                raise AssertionError("child close retry did not return the slot")
            assert channels[0][0].closed
            assert channels[0][1].closed
            assert processes[0].closed
            assert not capacity.acquire(blocking=False)

        asyncio.run(run())

    def test_worker_start_and_child_close_failures_keep_503_and_slot(self):
        allow_child_close = threading.Event()
        channels = []
        processes = []

        class Parent:
            closed = False

            def close(self):
                self.closed = True

        class Child:
            closed = False
            close_attempts = 0

            def close(self):
                self.close_attempts += 1
                if not allow_child_close.is_set():
                    raise OSError("injected child close failure")
                self.closed = True

        class Process:
            def __init__(self):
                self.closed = False
                processes.append(self)

            def close(self):
                self.closed = True

        def socketpair():
            pair = (Parent(), Child())
            channels.append(pair)
            return pair

        original_start = threading.Thread.start

        def start_with_ipc_failure(thread):
            if thread.name == "detect-preprocessing-ipc":
                raise RuntimeError("injected IPC thread start failure")
            return original_start(thread)

        async def run():
            capacity = threading.BoundedSemaphore(1)
            context = SimpleNamespace(Process=lambda **kwargs: Process())
            with patch.object(preprocessing.multiprocessing, "get_context", return_value=context):
                with patch.object(preprocessing.socket, "socketpair", side_effect=socketpair):
                    with patch.object(threading.Thread, "start", new=start_with_ipc_failure):
                        with self._production_path(capacity):
                            try:
                                with pytest.raises(HTTPException) as caught:
                                    await asyncio.wait_for(
                                        self._read(_Upload(_png_bytes()), settings=_settings(timeout=1)),
                                        timeout=0.5,
                                    )
                                assert caught.value.status_code == 503
                                assert caught.value.detail["code"] == "detect_preprocessing_unavailable"
                                assert len(channels) == 1
                                assert channels[0][1].close_attempts >= 2
                                assert not channels[0][1].closed
                                assert not capacity.acquire(blocking=False)
                            finally:
                                allow_child_close.set()

            for _ in range(100):
                if capacity.acquire(blocking=False):
                    break
                await asyncio.sleep(0.01)
            else:
                raise AssertionError("worker-start failure retained a cleaned slot")
            assert channels[0][0].closed
            assert channels[0][1].closed
            assert processes[0].closed
            assert not capacity.acquire(blocking=False)

        asyncio.run(run())

    def test_runtime_open_failure_returns_the_acquired_slot(self):
        async def run():
            capacity = threading.BoundedSemaphore(1)
            with self._production_path(capacity):
                with patch.object(preprocessing._RUNTIME, "open", side_effect=RuntimeError("closed")):
                    with pytest.raises(HTTPException) as caught:
                        await self._read(_Upload(_png_bytes()))
            assert caught.value.status_code == 503
            assert caught.value.detail["code"] == "detect_preprocessing_unavailable"
            assert capacity.acquire(blocking=False)

        asyncio.run(run())

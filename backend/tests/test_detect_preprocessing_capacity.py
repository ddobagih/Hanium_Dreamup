"""Exercise detection admission around real image sanitation without model execution."""

from __future__ import annotations

import asyncio
import io
import threading
import unittest
from concurrent.futures import Future, ThreadPoolExecutor
from types import SimpleNamespace
from unittest.mock import patch

from fastapi import HTTPException
from PIL import Image
from starlette.datastructures import Headers, UploadFile

from backend.app import uploads
from backend.app.api import detect


class _RecordingExecutor(ThreadPoolExecutor):
    def __init__(self) -> None:
        super().__init__(max_workers=2, thread_name_prefix="test-detect-preprocessing")
        self.futures = []

    def submit(self, fn, /, *args, **kwargs):
        future = super().submit(fn, *args, **kwargs)
        self.futures.append(future)
        return future


class _CountingUpload(UploadFile):
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


class _InferenceSink:
    """Only downstream model execution is replaced; upload and sanitation are real."""

    queue_timeout_seconds = 2.0
    startup_timeout_seconds = 30.0

    def __init__(self) -> None:
        self.calls = []

    def submit_inference(self, kind, *, image_bytes, content_type, context, settings, deadline):
        contract = "v1" if kind == "legacy" else "v2"
        self.calls.append((contract, image_bytes, content_type))
        future = Future()
        future.set_result(contract)
        return future

    def run_legacy(self, **kwargs):
        self.calls.append(("v1", kwargs["image_bytes"], kwargs["content_type"]))
        return "v1"

    def run_v2(self, **kwargs):
        self.calls.append(("v2", kwargs["image_bytes"], kwargs["content_type"]))
        return "v2"


class TestDetectPreprocessingCapacity(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.executor = _RecordingExecutor()
        self.executor_patch = patch.object(
            detect, "_DETECT_PREPROCESSING_EXECUTOR", self.executor,
        )
        self.capacity_patch = patch.object(
            detect, "_DETECT_PREPROCESSING_CAPACITY", threading.BoundedSemaphore(2),
        )
        self.executor_patch.start()
        self.capacity_patch.start()
        self.addCleanup(self.executor_patch.stop)
        self.addCleanup(self.capacity_patch.stop)
        self.settings = SimpleNamespace(
            allowed_image_content_types={"image/png"},
            max_upload_bytes=1024 * 1024,
        )
        self.inference = _InferenceSink()
        self.router = detect.create_router(
            self.settings, inference_runner=self.inference,
        )
        self.tasks = []
        self.images = []
        self.gates = []

    async def asyncTearDown(self) -> None:
        for gate in self.gates:
            gate.set()
        for task in self.tasks:
            if not task.done():
                task.cancel()
        await asyncio.gather(*self.tasks, return_exceptions=True)
        await asyncio.gather(
            *(asyncio.wrap_future(future) for future in self.executor.futures),
            return_exceptions=True,
        )
        self.executor.shutdown(wait=True, cancel_futures=True)
        for image in self.images:
            await image.close()

    def _upload(self, content: bytes | None = None) -> _CountingUpload:
        if content is None:
            output = io.BytesIO()
            with Image.new("RGB", (3, 2), (5, 17, 29)) as image:
                image.save(output, format="PNG")
            content = output.getvalue()
        upload = _CountingUpload(content)
        self.images.append(upload)
        return upload

    async def _call(self, path: str, image=None, *, router=None):
        selected_router = self.router if router is None else router
        endpoint = next(
            route.endpoint
            for route in selected_router.routes
            if route.path == path and "POST" in route.methods
        )
        return await endpoint(
            context="{}",
            image=self._upload() if image is None else image,
        )

    def _start(self, path: str, image=None):
        task = asyncio.create_task(self._call(path, image))
        self.tasks.append(task)
        return task

    def _gate_calls(self, count: int):
        loop = asyncio.get_running_loop()
        started = [asyncio.Event() for _ in range(count)]
        gates = [threading.Event() for _ in range(count)]
        self.gates.extend(gates)
        original = uploads.strip_image_metadata
        lock = threading.Lock()
        call_count = 0

        def gated_sanitizer(content: bytes, content_type: str) -> bytes:
            nonlocal call_count
            with lock:
                index = call_count
                call_count += 1
            if index < count:
                loop.call_soon_threadsafe(started[index].set)
                gates[index].wait()
            return original(content, content_type)

        sanitizer_patch = patch.object(uploads, "strip_image_metadata", gated_sanitizer)
        sanitizer_patch.start()
        self.addCleanup(sanitizer_patch.stop)
        return started, gates

    async def _wait_started(self, event: asyncio.Event) -> None:
        await asyncio.wait_for(event.wait(), timeout=5.0)

    async def _assert_capacity_rejected(self, path: str, *, router=None) -> None:
        image = self._upload()
        with self.assertRaises(HTTPException) as caught:
            await self._call(path, image, router=router)
        self.assertEqual(503, caught.exception.status_code)
        self.assertEqual(
            {
                "code": "detect_preprocessing_capacity_exceeded",
                "message": "Image preprocessing is busy. Retry with a fresh image.",
            },
            caught.exception.detail,
        )
        self.assertEqual(0, image.read_calls)
        self.assertEqual(2, len(self.executor.futures))

    def _assert_real_sanitized_images_reached_inference(self) -> None:
        for _, content, content_type in self.inference.calls:
            self.assertEqual("image/png", content_type)
            with Image.open(io.BytesIO(content)) as image:
                image.load()
                self.assertEqual((3, 2), image.size)
                self.assertEqual((5, 17, 29), image.getpixel((0, 0)))

    async def test_v1_v2_share_two_slots_and_return_capacity_after_actual_completion(self):
        started, gates = self._gate_calls(2)
        first = self._start("/detect")
        await self._wait_started(started[0])
        second = self._start("/detect/v2")
        await self._wait_started(started[1])
        other_router = detect.create_router(
            self.settings, inference_runner=self.inference,
        )

        self.assertTrue(all(future.running() for future in self.executor.futures))
        await self._assert_capacity_rejected("/detect")
        await self._assert_capacity_rejected("/detect/v2", router=other_router)
        self.assertEqual([], self.inference.calls)

        gates[0].set()
        gates[1].set()
        self.assertEqual(["v1", "v2"], await asyncio.gather(first, second))
        self.assertTrue(all(future.done() for future in self.executor.futures))
        self.assertEqual("v1", await self._call("/detect", router=other_router))
        self.assertEqual(3, len(self.inference.calls))
        self._assert_real_sanitized_images_reached_inference()

    async def test_cancelled_request_keeps_its_slot_until_the_sync_job_finishes(self):
        started, gates = self._gate_calls(2)
        first = self._start("/detect")
        await self._wait_started(started[0])
        second = self._start("/detect/v2")
        await self._wait_started(started[1])
        first_future = self.executor.futures[0]

        first.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await first
        self.assertTrue(first_future.running())
        self.assertFalse(first_future.done())
        await self._assert_capacity_rejected("/detect/v2")
        self.assertEqual([], self.inference.calls)

        gates[0].set()
        await asyncio.wrap_future(first_future)
        self.assertTrue(first_future.done())
        self.assertEqual("v1", await self._call("/detect"))
        self.assertEqual(["v1"], [call[0] for call in self.inference.calls])

        gates[1].set()
        self.assertEqual("v2", await second)
        self.assertEqual(["v1", "v2"], [call[0] for call in self.inference.calls])
        self._assert_real_sanitized_images_reached_inference()

    async def test_real_decode_failure_returns_capacity_without_reaching_inference(self):
        started, gates = self._gate_calls(2)
        invalid = self._start("/detect", self._upload(b"\x89PNG\r\n\x1a\nbroken"))
        await self._wait_started(started[0])
        second = self._start("/detect/v2")
        await self._wait_started(started[1])
        await self._assert_capacity_rejected("/detect")

        gates[0].set()
        with self.assertRaises(HTTPException) as caught:
            await invalid
        self.assertEqual(400, caught.exception.status_code)
        self.assertEqual("invalid_image_content", caught.exception.detail["code"])
        self.assertTrue(self.executor.futures[0].done())
        self.assertEqual([], self.inference.calls)
        self.assertEqual("v1", await self._call("/detect"))

        gates[1].set()
        self.assertEqual("v2", await second)
        self.assertEqual(["v1", "v2"], [call[0] for call in self.inference.calls])
        self._assert_real_sanitized_images_reached_inference()

    async def test_validation_failure_before_submission_does_not_consume_capacity(self):
        with self.assertRaises(HTTPException) as caught:
            await self._call("/detect", self._upload(b""))
        self.assertEqual(400, caught.exception.status_code)
        self.assertEqual("empty_image", caught.exception.detail["code"])
        self.assertEqual([], self.executor.futures)

        started, gates = self._gate_calls(2)
        first = self._start("/detect")
        await self._wait_started(started[0])
        second = self._start("/detect/v2")
        await self._wait_started(started[1])
        await self._assert_capacity_rejected("/detect")
        gates[0].set()
        gates[1].set()
        self.assertEqual(["v1", "v2"], await asyncio.gather(first, second))
        self._assert_real_sanitized_images_reached_inference()

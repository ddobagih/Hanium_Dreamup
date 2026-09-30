"""Preprocessing completion must not wait for unrelated default executor work."""
from __future__ import annotations

import asyncio
from concurrent.futures import ThreadPoolExecutor
import io
import threading
from types import SimpleNamespace

from fastapi import HTTPException
from PIL import Image
import pytest
from starlette.datastructures import Headers, UploadFile

from backend.app.services import _detection_preprocessing as preprocessing


class _WaitingUpload:
    content_type = "image/png"
    filename = "frame.png"

    def __init__(self, started: asyncio.Event) -> None:
        self.started = started

    async def read(self, size: int = -1) -> bytes:
        self.started.set()
        await asyncio.Event().wait()
        raise AssertionError("waiting upload should be cancelled")


def _image() -> UploadFile:
    output = io.BytesIO()
    with Image.new("RGB", (3, 2), (5, 17, 29)) as image:
        image.save(output, format="PNG")
    return UploadFile(file=io.BytesIO(output.getvalue()), filename="frame.png",
                      headers=Headers({"content-type": "image/png"}))


@pytest.mark.parametrize("ending", ["deadline", "cancel"])
def test_upload_ending_does_not_wait_for_default_executor_backlog(ending):
    async def run():
        settings = SimpleNamespace(allowed_image_content_types={"image/png"},
                                   max_upload_bytes=1024 * 1024,
                                   inference_timeout_seconds=0.05 if ending == "deadline" else 5.0)
        capacity = threading.BoundedSemaphore(1)
        occupied = threading.Event()
        release = threading.Event()
        executor = ThreadPoolExecutor(max_workers=1)
        loop = asyncio.get_running_loop()
        loop.set_default_executor(executor)

        def unrelated_work():
            occupied.set()
            assert release.wait(3.0), "test rescue released a stuck executor"

        blocker = loop.run_in_executor(None, unrelated_work)
        task = None
        try:
            while not occupied.is_set():
                await asyncio.sleep(0.001)
            started = asyncio.Event()
            task = asyncio.create_task(preprocessing.read_detection_image_upload(
                _WaitingUpload(started), settings, capacity=capacity,
            ))
            await asyncio.wait_for(started.wait(), timeout=1.0)
            if ending == "cancel":
                task.cancel()
            done, _ = await asyncio.wait({task}, timeout=0.5)
            # The observation bound is a watchdog, not a new service SLA.
            assert done == {task}, "cleanup queued behind unrelated default executor work"
            assert not release.is_set() and not blocker.done()
            if ending == "cancel":
                with pytest.raises(asyncio.CancelledError):
                    await task
            else:
                with pytest.raises(HTTPException) as caught:
                    await task
                assert caught.value.status_code == 503
                assert caught.value.detail["code"] == "detect_preprocessing_deadline_exceeded"
            assert capacity.acquire(blocking=False)
            assert not capacity.acquire(blocking=False), "preprocessing released capacity twice"
            capacity.release()

            # The next real worker must also finish while the unrelated pool is busy.
            settings.inference_timeout_seconds = 5.0
            image = _image()
            try:
                recovered = asyncio.create_task(preprocessing.read_detection_image_upload(
                    image, settings, capacity=capacity,
                ))
                try:
                    done, _ = await asyncio.wait({recovered}, timeout=2.0)
                    assert done == {recovered}, "successful cleanup waited for the default executor"
                    content, content_type = await recovered
                    assert content_type == "image/png"
                    with Image.open(io.BytesIO(content)) as decoded:
                        decoded.load()
                        assert decoded.size == (3, 2)
                    assert not release.is_set() and not blocker.done()
                finally:
                    if not recovered.done():
                        recovered.cancel()
                    release.set()
                    await asyncio.gather(recovered, return_exceptions=True)
            finally:
                await image.close()
        finally:
            release.set()
            if task is not None:
                if not task.done():
                    task.cancel()
                await asyncio.gather(task, return_exceptions=True)
            await blocker
            executor.shutdown(wait=True)

    asyncio.run(run())

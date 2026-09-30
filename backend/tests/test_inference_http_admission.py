"""Real runner/admission/router regressions with local worker effects only.

No model child, provider, database or network request is started. Native process
and IPC operations are replaced; owned executors, Futures and HTTP consumers run.
"""

from __future__ import annotations

import asyncio
from concurrent.futures import ThreadPoolExecutor
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from anyio import to_thread
from fastapi import HTTPException

from backend.app.api import detect as detect_api
from backend.app.schemas import DetectContext, DetectResponse, DetectV2Response
from backend.app.services import inference_process as ipc


class _Gate:
    def __init__(self) -> None:
        self.entered = threading.Event()
        self.release = threading.Event()
        self.release.set()

    def wait(self) -> None:
        self.entered.set()
        self.release.wait()


class _Endpoint:
    def __init__(self) -> None:
        self.closed = False

    def setblocking(self, _value) -> None:
        pass

    def close(self) -> None:
        self.closed = True


class _Process:
    def __init__(self, effects) -> None:
        self.effects = effects
        self.started = False
        self.alive = False
        self.closed = False

    @property
    def pid(self):
        return 123 if self.started else None

    @property
    def exitcode(self):
        return -15 if self.started and not self.alive else None

    def start(self) -> None:
        self.started = True
        self.alive = True
        self.effects.now += self.effects.start_advance

    def is_alive(self):
        if self.closed:
            raise ValueError("synthetic closed process queried")
        return self.alive

    def terminate(self) -> None:
        self.alive = False

    def kill(self) -> None:
        self.alive = False

    def join(self, timeout) -> None:
        self.effects.joins.append(timeout)

    def close(self) -> None:
        if self.alive:
            raise ValueError("synthetic live process cannot close")
        self.closed = True


class _Channel:
    def __init__(self, effects, endpoint) -> None:
        self.effects = effects
        self.endpoint = endpoint

    def exchange(self, request, deadline):
        self.effects.exchanges.append((request, deadline))
        self.effects.exchange_gate.wait()
        if self.effects.worker_error:
            return request[0], "error", "RuntimeError", "synthetic worker failure"
        if request[1] == "legacy":
            response = DetectResponse(
                model_status="ready", model_version="synthetic-http-admission",
                detections=[],
            )
        else:
            response = DetectV2Response(schema_version="detect.v2", detections=[])
        return request[0], "ok", response

    def close(self) -> None:
        self.effects.cleanup_gate.wait()
        self.endpoint.close()


class _Effects:
    def __init__(self) -> None:
        self.now = 100.0
        self.start_advance = 0.0
        self.worker_error = False
        self.exchange_gate = _Gate()
        self.cleanup_gate = _Gate()
        self.creation_times = []
        self.processes = []
        self.pairs = []
        self.exchanges = []
        self.joins = []

    def monotonic(self):
        return self.now

    def socketpair(self):
        self.creation_times.append(self.now)
        pair = _Endpoint(), _Endpoint()
        self.pairs.append(pair)
        return pair

    def Process(self, *, target, args, daemon):
        process = _Process(self)
        self.processes.append(process)
        return process

    def channel(self, endpoint):
        return _Channel(self, endpoint)


class _ExecutorEffect:
    """Real single-worker executor with a gated dispatch or post-queue fault."""

    def __init__(self, *, occupied=False, fail_after_queue=False) -> None:
        self.inner = ThreadPoolExecutor(max_workers=1, thread_name_prefix="test-owned-dispatch")
        self.gate = _Gate()
        self.gate.release.clear()
        self.fail_after_queue = fail_after_queue
        self.calls = []
        self.futures = []
        self.occupier = self.inner.submit(self.gate.wait) if occupied else None

    def submit(self, fn, /, *args, **kwargs):
        self.calls.append((fn, args, kwargs))
        future = self.inner.submit(fn, *args, **kwargs)
        self.futures.append(future)
        if self.fail_after_queue:
            self.fail_after_queue = False
            raise OSError("synthetic submit failure after queueing")
        return future

    def shutdown(self, *, wait=True, cancel_futures=False):
        return self.inner.shutdown(wait=wait, cancel_futures=cancel_futures)


class TestInferenceHttpAdmission(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.effects = _Effects()
        self.runner = ipc.InferenceProcessRunner(
            timeout_seconds=2.0, startup_timeout_seconds=3.0,
            queue_timeout_seconds=0.1,
        )
        self.runner._context = self.effects
        self.tasks = []
        self.futures = []
        self.terminal = []
        self.executor_effects = []
        self.extra_gates = []
        self.closers = []
        self.close_errors = []
        self.loop_errors = []
        self.loop = asyncio.get_running_loop()
        self.old_exception_handler = self.loop.get_exception_handler()
        self.loop.set_exception_handler(lambda _loop, context: self.loop_errors.append(context))

        async def sanitized(_image, _settings):
            return b"synthetic sanitized image", "image/png"

        bound_submit = self.runner.submit_inference

        def record_submit(*args, **kwargs):
            future = bound_submit(*args, **kwargs)
            self.futures.append(future)

            def observe_terminal(completed):
                self.terminal.append((
                    completed,
                    None if completed.cancelled() else completed.exception(),
                ))

            future.add_done_callback(observe_terminal)
            return future

        installed = [
            patch.object(ipc, "time", SimpleNamespace(monotonic=self.effects.monotonic)),
            patch.object(ipc, "socket", SimpleNamespace(socketpair=self.effects.socketpair)),
            patch.object(ipc, "_FramedSocket", self.effects.channel),
            patch.object(detect_api, "time", SimpleNamespace(monotonic=self.effects.monotonic)),
            patch.object(detect_api, "_read_detection_image_upload", sanitized),
            patch.object(self.runner, "submit_inference", record_submit),
        ]
        for item in installed:
            item.start()
            self.addCleanup(item.stop)
        self.router = detect_api.create_router(
            SimpleNamespace(), inference_runner=self.runner,
        )

    async def asyncTearDown(self):
        # Release every test-owned gate before draining tasks, Futures or closers.
        self.effects.exchange_gate.release.set()
        self.effects.cleanup_gate.release.set()
        for gate in self.extra_gates:
            gate.release.set()
        for executor in self.executor_effects:
            executor.gate.release.set()
        try:
            for task in self.tasks:
                if not task.done():
                    task.cancel()
            await asyncio.gather(*self.tasks, return_exceptions=True)
            owned = list(self.futures)
            for executor in self.executor_effects:
                owned.extend(executor.futures)
                if executor.occupier is not None:
                    owned.append(executor.occupier)
            if owned:
                await asyncio.wait_for(
                    asyncio.gather(
                        *(asyncio.wrap_future(future) for future in owned),
                        return_exceptions=True,
                    ),
                    timeout=3.0,
                )
            for thread, _done in self.closers:
                await asyncio.to_thread(thread.join, 3.0)
            await asyncio.to_thread(self.runner.close)
            for executor in self.executor_effects:
                await asyncio.to_thread(executor.shutdown, wait=True, cancel_futures=True)
            await asyncio.sleep(0)
        finally:
            self.loop.set_exception_handler(self.old_exception_handler)

    async def _until(self, predicate):
        async def poll():
            while not predicate():
                await asyncio.sleep(0.001)
        await asyncio.wait_for(poll(), timeout=2.0)

    async def _wait(self, event):
        await self._until(event.is_set)

    async def _http(self, path="/detect"):
        endpoint = next(
            route.endpoint for route in self.router.routes
            if route.path == path and "POST" in route.methods
        )
        return await endpoint(context="{}", image=object())

    def _start_http(self, path="/detect"):
        task = asyncio.create_task(self._http(path))
        self.tasks.append(task)
        return task

    def _submit(self, *, kind="legacy", deadline=None):
        return self.runner.submit_inference(
            kind, image_bytes=b"synthetic image", content_type="image/png",
            context=DetectContext(), settings=object(),
            deadline=self.effects.now + 3.1 if deadline is None else deadline,
        )

    async def _busy_http(self, path="/detect/v2"):
        with self.assertRaises(HTTPException) as caught:
            await self._http(path)
        self.assertEqual(503, caught.exception.status_code)
        self.assertEqual(
            "detect_queue_timeout" if path == "/detect" else "detect_v2_queue_timeout",
            caught.exception.detail["code"],
        )
        self.assertEqual({"Retry-After": "1"}, caught.exception.headers)

    def _executor_effect(self, **kwargs):
        executor = _ExecutorEffect(**kwargs)
        self.executor_effects.append(executor)
        self.runner._executor = executor
        return executor

    def _start_close(self):
        done = threading.Event()

        def close():
            try:
                self.runner.close()
            except BaseException as exc:
                self.close_errors.append(exc)
            finally:
                done.set()

        thread = threading.Thread(target=close, name="test-runner-close", daemon=True)
        self.closers.append((thread, done))
        thread.start()
        return done

    async def test_isolated_http_progresses_while_shared_anyio_pool_is_full(self):
        limiter = to_thread.current_default_thread_limiter()
        old_tokens = limiter.total_tokens
        pool_ran = threading.Event()
        pool_task = None
        acquired = False
        limiter.total_tokens = 1
        try:
            await limiter.acquire()
            acquired = True
            pool_task = asyncio.create_task(to_thread.run_sync(pool_ran.set))
            await asyncio.sleep(0)
            response = await self._http()
            self.assertIsInstance(response, DetectResponse)
            self.assertFalse(pool_ran.is_set())
            self.assertEqual(1, len(self.effects.exchanges))
        finally:
            if acquired:
                limiter.release()
            limiter.total_tokens = old_tokens
            if pool_task is not None:
                await asyncio.wait_for(pool_task, 2.0)
        self.assertTrue(pool_ran.is_set())

    async def test_v1_and_v2_share_one_slot_without_an_extra_waiting_queue(self):
        self.effects.exchange_gate.release.clear()
        first = self._start_http()
        try:
            await self._wait(self.effects.exchange_gate.entered)
            await self._busy_http("/detect/v2")
            await self._busy_http("/detect")
            self.assertEqual(1, len(self.futures))
            self.assertEqual(1, len(self.effects.exchanges))
            self.assertTrue(self.futures[0].running())
        finally:
            self.effects.exchange_gate.release.set()
        self.assertIsInstance(await first, DetectResponse)
        self.assertIsInstance(await self._http("/detect/v2"), DetectV2Response)
        self.assertEqual(["legacy", "v2"], [item[0][1] for item in self.effects.exchanges])

    async def test_http_timeout_retains_lease_until_actual_deadline_cleanup_finishes(self):
        self.runner.startup_timeout_seconds = 0.02
        self.runner.queue_timeout_seconds = 0.01
        self.effects.exchange_gate.release.clear()
        self.effects.cleanup_gate.release.clear()
        first = self._start_http()
        try:
            await self._wait(self.effects.exchange_gate.entered)
            with self.assertRaises(HTTPException) as caught:
                await first
            self.assertEqual(503, caught.exception.status_code)
            self.assertEqual("detect_queue_timeout", caught.exception.detail["code"])
            self.assertFalse(self.futures[0].done())
            await self._busy_http()

            self.effects.now += 1.0
            self.effects.exchange_gate.release.set()
            await self._wait(self.effects.cleanup_gate.entered)
            self.assertFalse(self.futures[0].done())
            await self._busy_http()
        finally:
            self.effects.exchange_gate.release.set()
            self.effects.cleanup_gate.release.set()
        await self._until(lambda: bool(self.terminal))
        self.assertIsInstance(self.terminal[0][1], ipc.InferenceDeadlineExceeded)
        self.assertTrue(self.effects.processes[0].closed)
        self.effects.now += 5.0
        self.assertIsInstance(await self._http(), DetectResponse)
        self.assertEqual([], self.loop_errors)

    async def test_running_http_cancellation_retains_lease_through_error_cleanup(self):
        self.effects.worker_error = True
        self.effects.exchange_gate.release.clear()
        self.effects.cleanup_gate.release.clear()
        first = self._start_http("/detect/v2")
        try:
            await self._wait(self.effects.exchange_gate.entered)
            first.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await first
            self.assertFalse(self.futures[0].cancelled())
            self.assertFalse(self.futures[0].done())
            await self._busy_http()
            self.effects.exchange_gate.release.set()
            await self._wait(self.effects.cleanup_gate.entered)
            self.assertFalse(self.futures[0].done())
            await self._busy_http("/detect")
        finally:
            self.effects.exchange_gate.release.set()
            self.effects.cleanup_gate.release.set()
        await self._until(lambda: bool(self.terminal))
        self.assertIsInstance(self.terminal[0][1], RuntimeError)
        self.effects.worker_error = False
        self.effects.now += 5.0
        self.assertIsInstance(await self._http("/detect/v2"), DetectV2Response)
        self.assertEqual([], self.loop_errors)

    async def test_http_cancellation_does_not_cancel_a_known_not_started_future(self):
        executor = self._executor_effect(occupied=True)
        first = self._start_http()
        try:
            await self._wait(executor.gate.entered)
            await self._until(lambda: bool(self.futures))
            self.assertFalse(self.futures[0].running())
            first.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await first
            self.assertFalse(self.futures[0].done())
            self.assertFalse(self.futures[0].cancelled())
            await self._busy_http()
            self.assertEqual([], self.effects.creation_times)
        finally:
            executor.gate.release.set()
        await self._until(lambda: bool(self.terminal))
        self.assertIsNone(self.terminal[0][1])
        self.assertEqual(1, len(self.effects.creation_times))
        self.assertIsInstance(await self._http("/detect/v2"), DetectV2Response)

    async def test_late_success_is_retrieved_after_http_waiter_cancels(self):
        self.effects.exchange_gate.release.clear()
        first = self._start_http()
        try:
            await self._wait(self.effects.exchange_gate.entered)
            first.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await first
            self.assertFalse(self.futures[0].done())
        finally:
            self.effects.exchange_gate.release.set()
        await self._until(lambda: bool(self.terminal))
        await asyncio.sleep(0)
        self.assertIsNone(self.terminal[0][1])
        self.assertIsInstance(self.futures[0].result(), DetectResponse)
        self.assertTrue(self.runner.is_warmed("legacy"))
        self.assertEqual([], self.loop_errors)
        self.assertIsInstance(await self._http("/detect/v2"), DetectV2Response)

    async def test_dispatch_expiry_rejects_before_any_worker_effect(self):
        executor = self._executor_effect(occupied=True)
        first = self._start_http()
        try:
            await self._wait(executor.gate.entered)
            await self._until(lambda: bool(self.futures))
            self.effects.now += 4.0
            executor.gate.release.set()
            with self.assertRaises(HTTPException) as caught:
                await first
            self.assertEqual("detect_queue_timeout", caught.exception.detail["code"])
            self.assertEqual(503, caught.exception.status_code)
            self.assertEqual([], self.effects.creation_times)
            self.assertEqual([], self.effects.exchanges)
        finally:
            executor.gate.release.set()
        await self._until(lambda: bool(self.terminal))
        self.assertIsInstance(self.terminal[0][1], ipc.InferenceQueueDeadlineExceeded)
        self.assertIsInstance(await self._http(), DetectResponse)

    async def test_runner_queue_wait_uses_remaining_absolute_budget(self):
        self.runner._lock.acquire()
        try:
            future = self._submit(deadline=self.effects.now + 0.01)
            with self.assertRaises(ipc.InferenceQueueDeadlineExceeded):
                await asyncio.wait_for(asyncio.wrap_future(future), timeout=1.0)
            self.assertEqual([], self.effects.creation_times)
            self.assertEqual([], self.effects.exchanges)
        finally:
            self.runner._lock.release()
        self.assertIsInstance(await self._http(), DetectResponse)

    async def test_preparation_consumes_absolute_budget_and_warm_work_keeps_its_cap(self):
        self.effects.start_advance = 1.0
        self.assertIsInstance(await self._http(), DetectResponse)
        self.assertAlmostEqual(103.1, self.effects.exchanges[0][1])
        self.assertEqual(101.0, self.effects.now)
        self.assertIsInstance(await self._http(), DetectResponse)
        self.assertAlmostEqual(103.0, self.effects.exchanges[1][1])
        self.assertEqual([100.0], self.effects.creation_times)

    async def test_multiple_closers_and_stale_completion_cannot_reopen_or_release_new_work(self):
        self.effects.exchange_gate.release.clear()
        future = self._submit()
        await self._wait(self.effects.exchange_gate.entered)
        old_submission = self.runner._submission
        old_executor = self.runner._executor
        second_retirement = _Gate()
        second_retirement.release.clear()
        self.extra_gates.append(second_retirement)
        original_retire = self.runner._terminate_worker
        calls = 0
        calls_lock = threading.Lock()

        def gated_retirement():
            nonlocal calls
            with calls_lock:
                calls += 1
                number = calls
            if number == 2:
                second_retirement.wait()
            original_retire()

        with patch.object(self.runner, "_terminate_worker", gated_retirement):
            try:
                first_close = self._start_close()
                await self._until(lambda: self.runner._closing_count == 1)
                second_close = self._start_close()
                await self._until(lambda: self.runner._closing_count == 2)
                with self.assertRaises(ipc.InferenceQueueDeadlineExceeded):
                    self._submit()
                self.assertFalse(future.done())
                self.effects.exchange_gate.release.set()
                await self._wait(second_retirement.entered)
                await self._until(lambda: first_close.is_set() or second_close.is_set())
                self.assertEqual(1, self.runner._closing_count)
                with self.assertRaises(ipc.InferenceQueueDeadlineExceeded):
                    self._submit()
            finally:
                self.effects.exchange_gate.release.set()
                second_retirement.release.set()
                for _thread, done in self.closers:
                    await self._wait(done)
        self.assertEqual([], self.close_errors)
        self.assertTrue(future.done())
        self.effects.now += 5.0
        self.effects.exchange_gate.entered.clear()
        self.effects.exchange_gate.release.clear()
        new_future = self._submit()
        try:
            await self._wait(self.effects.exchange_gate.entered)
            self.assertIsNot(old_executor, self.runner._executor)
            self.runner._submission_finished(old_submission, future)
            with self.assertRaises(ipc.InferenceQueueDeadlineExceeded):
                self._submit()
            self.assertFalse(new_future.done())
        finally:
            self.effects.exchange_gate.release.set()
        self.assertIsInstance(await asyncio.wrap_future(new_future), DetectResponse)
        self.assertEqual([100.0, 105.0], self.effects.creation_times)

    async def test_post_queue_submit_failure_keeps_capacity_and_revokes_late_worker_entry(self):
        executor = self._executor_effect(occupied=True, fail_after_queue=True)
        try:
            await self._wait(executor.gate.entered)
            with self.assertRaisesRegex(RuntimeError, "detector submission failed"):
                self._submit()
            self.assertEqual([], self.effects.creation_times)
            self.assertFalse(executor.futures[0].done())
            with self.assertRaises(ipc.InferenceQueueDeadlineExceeded):
                self._submit()
        finally:
            executor.gate.release.set()
        self.assertIsNone(await asyncio.wrap_future(executor.futures[0]))
        self.assertEqual([], self.effects.creation_times)
        self.assertEqual([], self.effects.exchanges)
        self.assertIsInstance(await self._http(), DetectResponse)

    async def test_uncertain_close_drains_executor_and_old_wrapper_cannot_touch_new_lease(self):
        executor = self._executor_effect(occupied=True, fail_after_queue=True)
        done = None
        try:
            await self._wait(executor.gate.entered)
            with self.assertRaisesRegex(RuntimeError, "detector submission failed"):
                self._submit()
            old_fn, old_args, old_kwargs = executor.calls[0]
            done = self._start_close()
            await self._until(lambda: self.runner._closing_count == 1)
            await self._until(lambda: executor.futures[0].cancelled())
            self.assertFalse(done.is_set())
            with self.assertRaises(ipc.InferenceQueueDeadlineExceeded):
                self._submit()
        finally:
            executor.gate.release.set()
            if done is not None:
                await self._wait(done)
        self.assertEqual([], self.close_errors)
        self.assertEqual([], self.effects.creation_times)
        self.effects.exchange_gate.release.clear()
        future = self._submit()
        try:
            await self._wait(self.effects.exchange_gate.entered)
            before = len(self.effects.exchanges)
            self.assertIsNone(old_fn(*old_args, **old_kwargs))
            self.assertEqual(before, len(self.effects.exchanges))
            with self.assertRaises(ipc.InferenceQueueDeadlineExceeded):
                self._submit()
            self.assertFalse(future.done())
        finally:
            self.effects.exchange_gate.release.set()
        self.assertIsInstance(await asyncio.wrap_future(future), DetectResponse)

    async def test_existing_worker_error_and_deadline_http_projections_are_preserved(self):
        for path, worker_error, expected_code in (
            ("/detect", True, "model_unavailable"),
            ("/detect/v2", True, "detect_v2_unavailable"),
            ("/detect", False, "detect_inference_deadline_exceeded"),
            ("/detect/v2", False, "detect_v2_inference_deadline_exceeded"),
        ):
            with self.subTest(path=path, worker_error=worker_error):
                self.effects.now += 60.0
                self.effects.worker_error = worker_error
                self.effects.exchange_gate.entered.clear()
                self.effects.exchange_gate.release.clear()
                task = self._start_http(path)
                try:
                    await self._wait(self.effects.exchange_gate.entered)
                    if not worker_error:
                        self.effects.now += 4.0
                    self.effects.exchange_gate.release.set()
                    with self.assertRaises(HTTPException) as caught:
                        await task
                    self.assertEqual(503, caught.exception.status_code)
                    self.assertEqual(expected_code, caught.exception.detail["code"])
                    if worker_error:
                        self.assertEqual("detector_runtime_error", caught.exception.detail["reason"])
                    else:
                        self.assertEqual({"Retry-After": "1"}, caught.exception.headers)
                finally:
                    self.effects.exchange_gate.release.set()

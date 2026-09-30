"""Exercise production restart admission with synthetic process/transport effects.

The runner, response DTO validation, warmup and HTTP exception consumers are real.
These tests do not spawn a model process or prove native IPC/field restart rates.
"""

from __future__ import annotations

import asyncio
import multiprocessing
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from fastapi import HTTPException

from backend.app.api import detect as detect_api
from backend.app import detector
from backend.app.schemas import DetectContext, DetectResponse, DetectV2Response
from backend.app.services import inference_process as ipc


class _Endpoint:
    def __init__(self) -> None:
        self.closed = False
        self.close_failure = False
        self.close_calls = 0
        self.blocking = None

    def setblocking(self, value) -> None:
        self.blocking = value

    def close(self) -> None:
        self.close_calls += 1
        if self.close_failure:
            raise OSError("synthetic endpoint cleanup failure")
        self.closed = True


class _Process:
    def __init__(self, effects) -> None:
        self.effects = effects
        self.started = False
        self.alive = False
        self.closed = False
        self.close_failure = effects.fail_process_close
        self.start_calls = 0
        self.terminate_calls = 0
        self.kill_calls = 0
        self.close_calls = 0
        self.join_timeouts = []

    @property
    def pid(self):
        return 123 if self.started else None

    @property
    def exitcode(self):
        return -15 if self.started and not self.alive else None

    def is_alive(self) -> bool:
        if self.closed:
            raise ValueError("synthetic closed process queried")
        return self.alive

    def start(self) -> None:
        self.start_calls += 1
        if self.effects.fail_start_after_ownership:
            self.started = True
            self.alive = True
            raise OSError("synthetic partial start failure")
        if self.effects.fail_start:
            raise OSError("synthetic start failure")
        self.started = True
        self.alive = True

    def terminate(self) -> None:
        self.terminate_calls += 1
        self.alive = False

    def kill(self) -> None:
        self.kill_calls += 1
        self.alive = False

    def join(self, timeout) -> None:
        self.join_timeouts.append(timeout)

    def close(self) -> None:
        self.close_calls += 1
        if self.close_failure:
            raise OSError("synthetic process cleanup failure")
        if self.alive:
            raise ValueError("synthetic live process cannot be closed")
        self.closed = True


class _Connection:
    def __init__(self, effects, endpoint) -> None:
        self.effects = effects
        self.endpoint = endpoint
        self.outcome = effects.outcome
        self.close_calls = 0

    def exchange(self, request, deadline):
        self.effects.exchanges.append((self, request, deadline))
        if self.outcome == "error":
            return request[0], "error", "RuntimeError", "synthetic worker failure"
        if self.outcome == "untyped_image_error":
            return request[0], "error", "RuntimeError", "image_decode_failed"
        if self.outcome == "malformed_image":
            return request[0], "error", "RuntimeError", "image_decode_failed", "malformed_image_input"
        if self.outcome == "malformed_context":
            return request[0], "error", "RuntimeError", "invalid_detection_context", "malformed_context_input"
        if self.outcome == "invalid_output":
            return request[0], "ok", "not a detection response"
        if self.outcome == "timeout":
            raise ipc.InferenceDeadlineExceeded("detector execution deadline exceeded")
        if request[1] == "legacy":
            response = DetectResponse(
                model_status="ready",
                model_version="synthetic-admission-test",
                detections=[],
            )
        else:
            response = DetectV2Response(schema_version="detect.v2", detections=[])
        return request[0], "ok", response

    def close(self) -> None:
        self.close_calls += 1
        self.endpoint.close()


class _Effects:
    """Record creation effects only; no rate/admission decision is reproduced."""

    def __init__(self) -> None:
        self.now = 0.0
        self.outcome = "ok"
        self.fail_pair = False
        self.fail_start = False
        self.fail_start_after_ownership = False
        self.fail_parent_close = False
        self.fail_process_close = False
        self.creation_times = []
        self.pairs = []
        self.processes = []
        self.connections = []
        self.exchanges = []
        self.process_arguments = []

    def monotonic(self) -> float:
        return self.now

    def socketpair(self):
        self.creation_times.append(self.now)
        if self.fail_pair:
            raise OSError("synthetic socketpair failure")
        parent, child = _Endpoint(), _Endpoint()
        parent.close_failure = self.fail_parent_close
        self.pairs.append((parent, child))
        return parent, child

    def connection(self, endpoint):
        connection = _Connection(self, endpoint)
        self.connections.append(connection)
        return connection

    def Process(self, *, target, args, daemon):
        self.process_arguments.append((target, args, daemon))
        process = _Process(self)
        self.processes.append(process)
        return process


class TestInferenceRestartAdmission(unittest.TestCase):
    def setUp(self) -> None:
        self.effects = _Effects()
        self.runner = ipc.InferenceProcessRunner(
            timeout_seconds=2.0,
            startup_timeout_seconds=30.0,
            queue_timeout_seconds=0.0,
        )
        self.runner._context = self.effects
        # Replace module references, not global time/socket functions used by asyncio.
        self.patches = [
            patch.object(ipc, "time", SimpleNamespace(monotonic=self.effects.monotonic)),
            patch.object(ipc, "socket", SimpleNamespace(socketpair=self.effects.socketpair)),
            patch.object(ipc, "_FramedSocket", self.effects.connection),
            patch.object(ipc.logger, "warning"),
        ]
        for installed in self.patches:
            installed.start()
            self.addCleanup(installed.stop)
        self.warning = ipc.logger.warning

    def tearDown(self) -> None:
        # Fault release applies only to this test's synthetic owned resources.
        for pair in self.effects.pairs:
            for endpoint in pair:
                endpoint.close_failure = False
        for process in self.effects.processes:
            process.close_failure = False
        self.runner.close()

    def _call(self, kind="legacy"):
        method = self.runner.run_legacy if kind == "legacy" else self.runner.run_v2
        return method(
            image_bytes=b"synthetic transport input",
            content_type="image/png",
            context=DetectContext(),
            settings=object(),
        )

    def _assert_limited(self, kind="legacy") -> None:
        with self.assertRaises(ipc.InferenceRestartAdmissionLimited) as caught:
            self._call(kind)
        self.assertIsInstance(caught.exception, RuntimeError)
        self.assertEqual(
            "inference_worker_restart_admission_limited",
            str(caught.exception),
        )

    def _assert_worker_error(self, kind="legacy") -> None:
        with self.assertRaisesRegex(RuntimeError, "synthetic worker failure") as caught:
            self._call(kind)
        self.assertNotIsInstance(caught.exception, ipc.InferenceRestartAdmissionLimited)

    def _assert_fixed_events(self, count) -> None:
        self.assertEqual(count, self.warning.call_count)
        for call in self.warning.call_args_list:
            self.assertEqual(("inference_worker_restart_admission_limited",), call.args)
            self.assertEqual({}, call.kwargs)

    def test_initial_creation_and_close_obey_exact_five_second_boundary(self):
        self.assertIsInstance(self._call(), DetectResponse)
        self.runner.close()
        self.effects.now = 4.999
        with patch.object(
            self.runner, "_terminate_worker", wraps=self.runner._terminate_worker,
        ) as retire:
            self._assert_limited()
            self._assert_limited("v2")
            self.assertEqual(0, retire.call_count)
        self.runner.close()
        self._assert_limited()
        self.assertEqual([0.0], self.effects.creation_times)
        self._assert_fixed_events(1)

        self.effects.now = 5.0
        self.assertIsInstance(self._call(), DetectResponse)
        self.assertTrue(self.runner.is_warmed("legacy"))
        self.runner.close()
        self.effects.now = 9.999
        self._assert_limited()
        self._assert_fixed_events(2)
        self.effects.now = 10.0
        self.assertIsInstance(self._call(), DetectResponse)
        self.assertEqual([0.0, 5.0, 10.0], self.effects.creation_times)
        self.runner.close()
        self.effects.now = 15.0
        self._assert_limited()
        self._assert_fixed_events(3)

    def test_rolling_window_prunes_at_sixty_seconds_and_recovers(self):
        self.effects.outcome = "error"
        for now in (0.0, 5.0, 10.0):
            self.effects.now = now
            self._assert_worker_error()
        self.effects.now = 59.999
        self._assert_limited()
        self.assertEqual(3, len(self.effects.processes))
        self._assert_fixed_events(1)

        self.effects.now = 60.0
        self._assert_worker_error()
        self.assertEqual([0.0, 5.0, 10.0, 60.0], self.effects.creation_times)
        self.effects.now = 64.999
        self._assert_limited()
        self._assert_fixed_events(2)
        self.effects.now = 65.0
        self.effects.outcome = "ok"
        self.assertIsInstance(self._call(), DetectResponse)
        self.assertEqual([0.0, 5.0, 10.0, 60.0, 65.0], self.effects.creation_times)
        self.assertTrue(all(process.closed for process in self.effects.processes[:-1]))

    def test_warning_failure_keeps_admission_rejection_and_recovery(self):
        self.effects.outcome = "error"
        for now in (0.0, 5.0, 10.0):
            self.effects.now = now
            self._assert_worker_error()

        self.effects.now = 15.0
        self.warning.side_effect = RuntimeError("synthetic logging failure")
        with patch.object(
            self.runner, "_terminate_worker", wraps=self.runner._terminate_worker,
        ) as retire:
            self._assert_limited()
            self._assert_limited()
            self.assertEqual(0, retire.call_count)
        self.assertEqual([0.0, 5.0, 10.0], self.effects.creation_times)
        self.assertEqual([0.0, 5.0, 10.0], list(self.runner._worker_creation_times))
        self.assertEqual(3, len(self.effects.processes))
        self.assertIsNone(self.runner._process)
        self.assertIsNone(self.runner._connection)
        self.assertIsNone(self.runner._child_connection)
        self.assertTrue(self.runner._restart_admission_limited)
        self._assert_fixed_events(1)

        self.effects.now = 60.0
        self.effects.outcome = "ok"
        self.assertIsInstance(self._call(), DetectResponse)
        self.assertEqual([0.0, 5.0, 10.0, 60.0], self.effects.creation_times)
        self.assertEqual(4, len(self.effects.processes))
        self.assertFalse(self.runner._restart_admission_limited)
        self.assertTrue(self.runner.is_warmed("legacy"))

    def test_generic_v2_child_error_still_retires_and_restarts(self):
        self.effects.outcome = "error"
        with patch.object(
            self.runner, "_terminate_worker", wraps=self.runner._terminate_worker,
        ) as retire:
            self._assert_worker_error("v2")
            self.assertEqual(1, retire.call_count)
        first = self.effects.processes[0]
        self.assertEqual(1, first.terminate_calls)
        self.assertTrue(first.closed)

        self.effects.now = 5.0
        self.effects.outcome = "ok"
        self.assertIsInstance(self._call("legacy"), DetectResponse)
        self.assertIsNot(first, self.effects.processes[-1])
        self.assertEqual([0.0, 5.0], self.effects.creation_times)

    def test_child_context_validation_reports_recoverable_input_before_model(self):
        endpoint = _Endpoint()
        request = ("job", "legacy", b"image", "image/png", [], object())
        with patch.object(ipc, "_worker_receive", side_effect=[request, None]), \
             patch.object(ipc, "_worker_send") as send, \
             patch.object(detector, "_run_detection_sync") as detect:
            ipc._inference_worker(endpoint)
        send.assert_called_once_with(
            endpoint,
            ("job", "error", "RuntimeError", "invalid_detection_context", "malformed_context_input"),
        )
        detect.assert_not_called()
        self.assertTrue(endpoint.closed)

    def test_typed_malformed_requests_keep_one_worker_and_creation_budget(self):
        with patch.object(
            self.runner, "_terminate_worker", wraps=self.runner._terminate_worker,
        ) as retire:
            for outcome, kind, message in (
                ("malformed_image", "legacy", "image_decode_failed"),
                ("malformed_context", "v2", "invalid_detection_context"),
            ):
                self.effects.outcome = outcome
                if self.effects.connections:
                    self.effects.connections[-1].outcome = outcome
                for _ in range(3):
                    with self.assertRaisesRegex(RuntimeError, f"^RuntimeError: {message}$"):
                        self._call(kind)
            self.effects.outcome = "ok"
            self.effects.connections[-1].outcome = "ok"
            self.assertIsInstance(self._call("legacy"), DetectResponse)
            self.assertIsInstance(self._call("v2"), DetectV2Response)
            self.assertEqual(0, retire.call_count)
        self.assertEqual([0.0], self.effects.creation_times)
        self.assertEqual(1, len(self.effects.processes))
        self.assertTrue(self.effects.processes[0].alive)
        self._assert_fixed_events(0)

    def test_untyped_image_error_retires_as_runtime_damage(self):
        self.effects.outcome = "untyped_image_error"
        with patch.object(
            self.runner, "_terminate_worker", wraps=self.runner._terminate_worker,
        ) as retire:
            with self.assertRaisesRegex(RuntimeError, "^RuntimeError: image_decode_failed$"):
                self._call()
            self.assertEqual(1, retire.call_count)
        self.assertTrue(self.effects.processes[0].closed)
        self.assertEqual([0.0], self.effects.creation_times)

    def test_invalid_output_contract_retires_then_obeys_cooldown(self):
        self.effects.outcome = "invalid_output"
        with self.assertRaisesRegex(RuntimeError, "detector worker returned an invalid response"):
            self._call()
        self.assertTrue(self.effects.processes[0].closed)
        self.effects.now = 4.999
        self._assert_limited()
        self.effects.now = 5.0
        self.effects.outcome = "ok"
        self.assertIsInstance(self._call(), DetectResponse)
        self.assertEqual([0.0, 5.0], self.effects.creation_times)

    def test_timeout_retires_then_obeys_cooldown(self):
        self.effects.outcome = "timeout"
        with self.assertRaises(ipc.InferenceDeadlineExceeded):
            self._call()
        self.assertTrue(self.effects.processes[0].closed)
        self.effects.now = 4.999
        self._assert_limited()
        self.effects.now = 5.0
        self.effects.outcome = "ok"
        self.assertIsInstance(self._call(), DetectResponse)
        self.assertEqual([0.0, 5.0], self.effects.creation_times)

    def test_healthy_worker_reuse_preserves_warm_deadlines_at_capacity(self):
        for now in (0.0, 5.0):
            self.effects.now = now
            self._call()
            self.runner.close()
        self.effects.now = 10.0
        self._call()
        connection = self.effects.connections[-1]
        process = self.effects.processes[-1]
        self.effects.now = 10.1
        with patch.object(
            self.runner, "_terminate_worker", wraps=self.runner._terminate_worker,
        ) as retire:
            self.assertIsInstance(self._call(), DetectResponse)
            self.assertIsInstance(self._call("v2"), DetectV2Response)
            self.assertIsInstance(self._call("v2"), DetectV2Response)
            exchange_count = len(self.effects.exchanges)
            self.runner.warmup_v2(object())
            self.assertEqual(exchange_count, len(self.effects.exchanges))
            self.assertEqual(0, retire.call_count)
        self.assertEqual(3, len(self.effects.creation_times))
        self.assertTrue(self.runner.is_warmed("legacy"))
        self.assertTrue(self.runner.is_warmed("v2"))
        self.assertFalse(process.closed)
        self.assertEqual(0, process.terminate_calls)
        self.assertTrue(all(
            entry[0] is connection for entry in self.effects.exchanges[-4:]
        ))
        deadlines = [entry[2] for entry in self.effects.exchanges[-4:]]
        for expected, actual in zip((40.0, 12.1, 40.1, 12.1), deadlines):
            self.assertAlmostEqual(expected, actual)
        self._assert_fixed_events(0)

    def test_legacy_v2_and_warmup_share_creation_budget(self):
        self.effects.outcome = "error"
        self._assert_worker_error("legacy")
        self.effects.now = 5.0
        self._assert_worker_error("v2")
        self.effects.now = 10.0
        with self.assertRaisesRegex(RuntimeError, "synthetic worker failure"):
            self.runner.warmup_v2(object())
        self.assertEqual(
            ["legacy", "v2", "v2"],
            [entry[1][1] for entry in self.effects.exchanges],
        )
        self.assertEqual(ipc.WARMUP_PNG_BYTES, self.effects.exchanges[-1][1][2])

        self.effects.now = 15.0
        with self.assertRaises(ipc.InferenceRestartAdmissionLimited):
            self.runner.warmup_v2(object())
        self._assert_limited("legacy")
        self._assert_limited("v2")
        self.assertEqual(3, len(self.effects.creation_times))
        self._assert_fixed_events(1)


    def test_success_then_failure_does_not_reset_creation_history(self):
        for now in (0.0, 5.0, 10.0):
            self.effects.now = now
            self.assertIsInstance(self._call(), DetectResponse)
            self.effects.connections[-1].outcome = "error"
            self.effects.now = now + 0.1
            self._assert_worker_error()
            self.assertFalse(self.runner.is_warmed("legacy"))
        self.effects.now = 15.0
        self._assert_limited()
        self.assertEqual([0.0, 5.0, 10.0], self.effects.creation_times)
        self.assertTrue(all(process.closed for process in self.effects.processes))
        self.effects.now = 60.0
        self.assertIsInstance(self._call(), DetectResponse)
        self.assertEqual(4, len(self.effects.creation_times))

    def test_queue_rejection_does_not_consume_creation_budget(self):
        self.runner._lock.acquire()
        try:
            with self.assertRaises(ipc.InferenceQueueDeadlineExceeded):
                self._call()
        finally:
            self.runner._lock.release()
        self.assertEqual([], self.effects.creation_times)
        self._assert_fixed_events(0)
        for now in (0.0, 5.0, 10.0):
            self.effects.now = now
            self.assertIsInstance(self._call(), DetectResponse)
            self.runner.close()
        self.effects.now = 15.0
        self._assert_limited()
        self.assertEqual([0.0, 5.0, 10.0], self.effects.creation_times)

    def test_failed_start_consumes_reservation_and_retires_owned_resources(self):
        self.effects.fail_start = True
        with self.assertRaisesRegex(RuntimeError, "detector worker exited unexpectedly"):
            self._call()
        failed = self.effects.processes[0]
        self.assertEqual(1, failed.start_calls)
        self.assertFalse(failed.started)
        self.assertTrue(failed.closed)
        self.assertEqual(0, failed.terminate_calls)
        self.assertTrue(all(endpoint.closed for endpoint in self.effects.pairs[0]))
        self.assertIsNone(self.runner._process)
        self.assertIsNone(self.runner._connection)
        self.assertIsNone(self.runner._child_connection)
        self.effects.now = 4.999
        self._assert_limited()
        self.assertEqual(1, len(self.effects.creation_times))

        self.effects.fail_start = False
        for now in (5.0, 10.0):
            self.effects.now = now
            self._call()
            self.runner.close()
        self.effects.now = 15.0
        self._assert_limited()
        self.assertEqual([0.0, 5.0, 10.0], self.effects.creation_times)

    def test_unresolved_cleanup_prevents_reservation_and_preserves_references(self):
        self.effects.fail_start_after_ownership = True
        self.effects.fail_parent_close = True
        self.effects.fail_process_close = True
        with self.assertRaisesRegex(RuntimeError, "detector worker cleanup failed"):
            self._call()
        owned_process = self.effects.processes[0]
        owned_connection = self.effects.connections[0]
        self.assertIs(owned_process, self.runner._process)
        self.assertIs(owned_connection, self.runner._connection)
        self.assertFalse(owned_process.alive)
        self.assertFalse(owned_process.closed)
        self.assertFalse(owned_connection.endpoint.closed)
        self.assertFalse(self.runner.is_warmed("legacy"))

        self.effects.now = 5.0
        with self.assertRaisesRegex(RuntimeError, "detector worker cleanup failed") as caught:
            self._call()
        self.assertNotIsInstance(caught.exception, ipc.InferenceRestartAdmissionLimited)
        self.assertEqual([0.0], self.effects.creation_times)
        self.assertIs(owned_process, self.runner._process)
        self.assertIs(owned_connection, self.runner._connection)
        self._assert_fixed_events(0)

        owned_process.close_failure = False
        owned_connection.endpoint.close_failure = False
        self.effects.fail_start_after_ownership = False
        self.effects.fail_parent_close = False
        self.effects.fail_process_close = False
        self.assertIsInstance(self._call(), DetectResponse)
        self.assertTrue(owned_process.closed)
        self.assertTrue(owned_connection.endpoint.closed)
        self.assertEqual([0.0, 5.0], self.effects.creation_times)
        self.runner.close()
        self.effects.now = 10.0
        self._call()
        self.runner.close()
        self.effects.now = 15.0
        self._assert_limited()
        self.assertEqual([0.0, 5.0, 10.0], self.effects.creation_times)

    def test_socketpair_failure_also_consumes_the_reserved_attempt(self):
        self.effects.fail_pair = True
        with self.assertRaisesRegex(RuntimeError, "detector worker exited unexpectedly"):
            self._call()
        self.assertEqual([0.0], self.effects.creation_times)
        self.assertEqual([], self.effects.processes)
        self.effects.now = 4.999
        self._assert_limited()
        self.assertEqual([0.0], self.effects.creation_times)
        self.effects.fail_pair = False
        for now in (5.0, 10.0):
            self.effects.now = now
            self._call()
            self.runner.close()
        self.effects.now = 15.0
        self._assert_limited()
        self.assertEqual([0.0, 5.0, 10.0], self.effects.creation_times)
        self.assertEqual(2, len(self.effects.processes))

    def test_existing_detection_consumers_return_503_for_typed_rejection(self):
        self.effects.outcome = "error"
        for now in (0.0, 5.0, 10.0):
            self.effects.now = now
            self._assert_worker_error()
        self.effects.now = 15.0
        router = detect_api.create_router(
            SimpleNamespace(), inference_runner=self.runner,
        )

        async def already_sanitized(_image, _settings):
            return b"synthetic sanitized boundary", "image/png"

        async def exercise():
            for path, code in (
                ("/detect", "model_unavailable"),
                ("/detect/v2", "detect_v2_unavailable"),
            ):
                endpoint = next(
                    route.endpoint for route in router.routes
                    if route.path == path and "POST" in route.methods
                )
                with self.assertRaises(HTTPException) as caught:
                    await endpoint(context="{}", image=object())
                self.assertEqual(503, caught.exception.status_code)
                self.assertEqual(code, caught.exception.detail["code"])
                self.assertEqual(
                    "detector_runtime_error", caught.exception.detail["reason"],
                )
                self.assertIsInstance(
                    caught.exception.__cause__, ipc.InferenceRestartAdmissionLimited,
                )

        with patch.object(
            detect_api, "_read_detection_image_upload", already_sanitized,
        ):
            asyncio.run(exercise())
        self.assertEqual(3, len(self.effects.creation_times))
        self._assert_fixed_events(1)


class TestInferenceProviderUnavailableChild(unittest.TestCase):
    @unittest.skipUnless("fork" in multiprocessing.get_all_start_methods(), "requires fork")
    def test_repeated_v2_precondition_keeps_real_child_for_legacy(self):
        def legacy_success(_image_bytes, _content_type, _context):
            return DetectResponse(
                model_status="ready",
                model_version="synthetic-admission-test",
                detections=[],
            )

        runner = ipc.InferenceProcessRunner(timeout_seconds=2.0, startup_timeout_seconds=10.0)
        runner._context = multiprocessing.get_context("fork")
        settings = SimpleNamespace(detect_v2_mode="real")
        with patch.object(detector, "_run_detection_sync", legacy_success):
            try:
                with patch.object(runner, "_terminate_worker", wraps=runner._terminate_worker) as retire:
                    for _ in range(5):
                        with self.assertRaises(RuntimeError) as caught:
                            runner.run_v2(
                                image_bytes=b"synthetic transport input",
                                content_type="image/png",
                                context=DetectContext(),
                                settings=settings,
                            )
                        self.assertEqual(
                            "RuntimeError: detect_v2_model_not_configured:custom_tactile,coco_general",
                            str(caught.exception),
                        )
                    child = runner._process
                    self.assertIsNotNone(child)
                    pid = child.pid
                    self.assertIsInstance(
                        runner.run_legacy(
                            image_bytes=b"synthetic transport input",
                            content_type="image/png",
                            context=DetectContext(),
                            settings=settings,
                        ),
                        DetectResponse,
                    )
                    self.assertIs(child, runner._process)
                    self.assertEqual(pid, runner._process.pid)
                    self.assertTrue(child.is_alive())
                    self.assertEqual(1, len(runner._worker_creation_times))
                    self.assertEqual(0, retire.call_count)
                    self.assertTrue(runner.is_warmed("legacy"))
            finally:
                runner.close()

from __future__ import annotations

import copy
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import run_walksafe_fp008_session_resume_gate_20260809 as resume


class WalkSafeFp008SessionResumeGateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        live = json.loads(
            (ROOT / resume.start_gate.CHECKPOINT_RELATIVE).read_bytes()
        )
        history = live.get("goal_execution", {}).get("transition_history", [])
        if history and history[-1].get("event_type") == "WORK_SESSION_RESUMED":
            from scripts import apply_walksafe_fp008_work_session_resumed_seq48_20260809 as publisher

            cls.source, _ = publisher._source_from_resumed_checkpoint(live)
        else:
            cls.source = live
        cls.contract_binding = resume.start_gate.expected_contract_binding()

    def _retained_publish_fixture(
        self,
        root: Path,
    ) -> tuple[
        resume.start_gate._RetainedFreshEventDirectory,
        resume.start_gate._RetainedGateLog,
    ]:
        gate_root = root / resume.start_gate.GATE_ROOT_RELATIVE
        gate_root.mkdir(parents=True)
        event = resume.start_gate._RetainedFreshEventDirectory.create(
            root,
            "TEST-EVENT",
        )
        stream = resume.start_gate._open_private_exclusive_at(
            event.event_fd,
            "01-CHECK.log",
        )
        try:
            stream.write(b"PASS\n")
            stream.flush()
            retained_log = resume.start_gate._RetainedGateLog.capture(
                event,
                "01-CHECK.log",
                stream,
                allow_empty=False,
            )
        except BaseException as exc:
            stream.close()
            event.close(exc)
            raise
        return event, retained_log

    @staticmethod
    def _raw_output_failure_fixture(
        failure: str,
    ) -> tuple[mock.Mock, mock.Mock, list[tuple[int, bytes]], object]:
        stdout_fd = 101
        stderr_fd = 102
        stdout = mock.Mock()
        stdout.fileno.return_value = stdout_fd
        stderr = mock.Mock()
        stderr.fileno.return_value = stderr_fd
        calls: list[tuple[int, bytes]] = []
        stdout_calls = 0

        def raw_write(descriptor: int, content: bytes) -> int:
            nonlocal stdout_calls
            calls.append((descriptor, content))
            if descriptor == stderr_fd:
                if failure == "diagnostic-error":
                    raise OSError("injected stderr diagnostic failure")
                return len(content)
            if descriptor != stdout_fd:
                raise AssertionError("unexpected raw output descriptor")
            stdout_calls += 1
            if failure in {"error", "diagnostic-error"}:
                raise OSError("injected stdout write failure")
            if failure == "zero":
                return 0
            if failure == "partial-error":
                if stdout_calls == 1:
                    return max(1, len(content) // 2)
                raise OSError("injected stdout continuation failure")
            raise AssertionError("unexpected raw output failure fixture")

        return stdout, stderr, calls, raw_write

    @staticmethod
    def _initialize_git_repository(root: Path) -> None:
        environment = {
            "PATH": "/usr/bin:/bin",
            "HOME": os.fspath(root / "home"),
            "LANG": "C.UTF-8",
            "LC_ALL": "C",
        }
        (root / "home").mkdir()
        subprocess.run(
            ["git", "init", "--quiet", "--initial-branch=test-authority"],
            cwd=root,
            env=environment,
            check=True,
        )
        (root / "tracked.txt").write_text("bound\n", encoding="utf-8")
        subprocess.run(
            ["git", "add", "tracked.txt"],
            cwd=root,
            env=environment,
            check=True,
        )
        subprocess.run(
            [
                "git",
                "-c",
                "user.name=WalkSafe Gate Test",
                "-c",
                "user.email=walksafe-gate@example.invalid",
                "commit",
                "--quiet",
                "-m",
                "authority fixture",
            ],
            cwd=root,
            env=environment,
            check=True,
        )

    def test_exact_resume_source_is_seq47_in_progress(self) -> None:
        ready_sha, occurred_at = resume._validate_resume_source(
            self.source,
            contract_binding=self.contract_binding,
        )
        self.assertEqual(ready_sha, resume.SOURCE_READY_EVENT_SHA256)
        self.assertEqual(
            self.source["goal_execution"]["transition_history"][-1]["event_sha256"],
            resume.SOURCE_EVENT_SHA256,
        )
        self.assertEqual(occurred_at.isoformat(), "2026-08-03T15:10:06+09:00")

    def test_resume_source_rejects_status_or_tail_drift(self) -> None:
        changed = copy.deepcopy(self.source)
        changed["goal_execution"]["status_by_goal"][resume.TARGET_GOAL_ID] = "READY"
        with self.assertRaisesRegex(resume.GateError, "runtime"):
            resume._validate_resume_source(
                changed,
                contract_binding=self.contract_binding,
            )

        changed = copy.deepcopy(self.source)
        changed["goal_execution"]["transition_history"][-1]["event_id"] = "WRONG"
        with self.assertRaisesRegex(resume.GateError, "seq47"):
            resume._validate_resume_source(
                changed,
                contract_binding=self.contract_binding,
            )

    def test_resume_adapter_reuses_start_gate_security_with_resume_identity(self) -> None:
        original = {
            name: getattr(resume.start_gate, name)
            for name in (
                "GATE_PURPOSE",
                "RECEIPT_NAME",
                "RECEIPT_STAGE_NAME",
                "load_gate_context",
                "_load_gate_contract",
                "_document_id",
            )
        }
        with resume._resume_adapter():
            self.assertEqual(resume.start_gate.GATE_PURPOSE, "SESSION_RESUME")
            self.assertEqual(resume.start_gate.RECEIPT_NAME, resume.RECEIPT_NAME)
            self.assertIs(resume.start_gate.load_gate_context, resume.load_gate_context)
            self.assertEqual(resume.start_gate._document_id(resume.EVENT_ID), resume.DOCUMENT_ID)
        for name, value in original.items():
            self.assertIs(getattr(resume.start_gate, name), value)

    def test_run_gate_requires_unused_dated_event_id(self) -> None:
        with mock.patch.object(
            resume,
            "_recover_existing_attempt",
            return_value=None,
        ):
            with self.assertRaisesRegex(resume.GateError, "precedes"):
                resume.run_gate(
                    ROOT,
                    resume.EVENT_ID.replace("20260809", "20260808"),
                )
        next_event_id = resume.EVENT_ID[:-3] + "002"
        with mock.patch.object(
            resume,
            "_recover_existing_attempt",
            return_value=None,
        ), mock.patch.object(
            resume.start_gate,
            "run_gate",
            return_value=Path("receipt"),
        ) as run:
            self.assertEqual(
                resume.run_gate(
                    ROOT,
                    next_event_id,
                    clock=lambda: datetime(
                        2026,
                        8,
                        9,
                        0,
                        0,
                        tzinfo=resume.ZoneInfo("Asia/Seoul"),
                    ),
                ),
                Path("receipt"),
            )
        run.assert_called_once()

        next_day_id = resume.EVENT_ID.replace("20260809-001", "20260810-001")
        with mock.patch.object(
            resume,
            "_recover_existing_attempt",
            return_value=None,
        ), mock.patch.object(
            resume.start_gate,
            "run_gate",
            return_value=Path("next-day-receipt"),
        ):
            self.assertEqual(
                resume.run_gate(
                    ROOT,
                    next_day_id,
                    clock=lambda: datetime(
                        2026,
                        8,
                        10,
                        0,
                        0,
                        tzinfo=resume.ZoneInfo("Asia/Seoul"),
                    ),
                ),
                Path("next-day-receipt"),
            )

    def test_run_gate_checks_exact_kst_date_before_consuming_event_id(self) -> None:
        with mock.patch.object(
            resume,
            "_recover_existing_attempt",
            return_value=None,
        ), mock.patch.object(resume.start_gate, "run_gate") as run:
            with self.assertRaisesRegex(resume.GateError, "KST execution date"):
                resume.run_gate(
                    ROOT,
                    resume.EVENT_ID,
                    clock=lambda: datetime(
                        2026,
                        8,
                        9,
                        15,
                        0,
                        1,
                        tzinfo=timezone.utc,
                    ),
                )

    def test_completed_recovery_precedes_clock_validation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            recovered = root / "published-receipt"
            with (
                mock.patch.object(
                    resume,
                    "_recover_existing_attempt",
                    return_value=recovered,
                ) as recover,
                mock.patch.object(resume.start_gate, "run_gate") as run,
            ):
                self.assertEqual(
                    resume.run_gate(
                        root,
                        resume.EVENT_ID,
                        clock=lambda: (_ for _ in ()).throw(
                            AssertionError("clock must not be consumed")
                        ),
                    ),
                    recovered,
                )
            recover.assert_called_once_with(root, resume.EVENT_ID)
            run.assert_not_called()

        with mock.patch.object(
            resume,
            "_recover_existing_attempt",
            return_value=None,
        ), mock.patch.object(
            resume.start_gate,
            "run_gate",
            return_value=Path("receipt"),
        ) as run:
            resume.run_gate(
                ROOT,
                resume.EVENT_ID,
                clock=lambda: datetime(
                    2026,
                    8,
                    8,
                    15,
                    0,
                    0,
                    tzinfo=timezone.utc,
                ),
            )

        run.assert_called_once()

        values = iter(
            (
                datetime(
                    2026,
                    8,
                    9,
                    23,
                    59,
                    59,
                    tzinfo=resume.ZoneInfo("Asia/Seoul"),
                ),
                datetime(
                    2026,
                    8,
                    10,
                    0,
                    0,
                    0,
                    tzinfo=resume.ZoneInfo("Asia/Seoul"),
                ),
            )
        )

        def consume_across_midnight(*args: object, **kwargs: object) -> Path:
            clock = kwargs["clock"]
            assert callable(clock)
            clock()
            clock()
            return Path("unreachable")

        with mock.patch.object(
            resume,
            "_recover_existing_attempt",
            return_value=None,
        ), mock.patch.object(
            resume.start_gate,
            "run_gate",
            side_effect=consume_across_midnight,
        ):
            with self.assertRaisesRegex(resume.GateError, "crossed"):
                resume.run_gate(
                    ROOT,
                    resume.EVENT_ID,
                    clock=lambda: next(values),
                )

        def consume_nonadvancing_clock(*args: object, **kwargs: object) -> Path:
            clock = kwargs["clock"]
            assert callable(clock)
            for _ in range(len(resume.start_gate.EXPECTED_CHECK_IDS) + 3):
                clock()
            return Path("unreachable")

        with mock.patch.object(
            resume,
            "_recover_existing_attempt",
            return_value=None,
        ), mock.patch.object(
            resume.start_gate,
            "run_gate",
            side_effect=consume_nonadvancing_clock,
        ):
            with self.assertRaisesRegex(
                resume.GateError,
                "timestamp adjustment crossed",
            ):
                resume.run_gate(
                    ROOT,
                    resume.EVENT_ID,
                    clock=lambda: datetime(
                        2026,
                        8,
                        9,
                        23,
                        59,
                        59,
                        tzinfo=resume.ZoneInfo("Asia/Seoul"),
                    ),
                )

    def test_start_cli_raw_pass_output_failures_are_rc2(self) -> None:
        for failure in ("error", "zero", "partial-error", "diagnostic-error"):
            with self.subTest(failure=failure):
                stdout, stderr, calls, raw_write = (
                    self._raw_output_failure_fixture(failure)
                )
                with mock.patch.object(
                    resume.start_gate,
                    "run_gate",
                    return_value=Path("receipt"),
                ), mock.patch.object(
                    resume.start_gate.os,
                    "write",
                    side_effect=raw_write,
                ), mock.patch.object(
                    resume.start_gate.sys,
                    "stdout",
                    stdout,
                ), mock.patch.object(
                    resume.start_gate.sys,
                    "stderr",
                    stderr,
                ):
                    result = resume.start_gate.main(
                        ["--event-id", "TEST-EVENT"]
                    )
                self.assertEqual(result, 2)
                self.assertTrue(any(fd == 102 for fd, _ in calls))
                if failure == "partial-error":
                    self.assertEqual(sum(fd == 101 for fd, _ in calls), 2)

    def test_resume_cli_raw_pass_failures_are_rc2_for_fresh_and_recovery(
        self,
    ) -> None:
        event_id = (
            "WS-GOAL-GRAPH-V2-4-WORK-SESSION-RESUMED-FP008-"
            f"{datetime.now(resume.ZoneInfo('Asia/Seoul')):%Y%m%d}-001"
        )
        for route in ("fresh", "recovery"):
            for failure in (
                "error",
                "zero",
                "partial-error",
                "diagnostic-error",
            ):
                with (
                    self.subTest(route=route, failure=failure),
                    tempfile.TemporaryDirectory() as temporary,
                ):
                    root = Path(temporary).resolve()
                    receipt = root / f"{route}-receipt"
                    recovered = receipt if route == "recovery" else None
                    stdout, stderr, calls, raw_write = (
                        self._raw_output_failure_fixture(failure)
                    )
                    with mock.patch.object(
                        resume,
                        "_recover_existing_attempt",
                        return_value=recovered,
                    ) as recover, mock.patch.object(
                        resume.start_gate,
                        "run_gate",
                        return_value=receipt,
                    ) as fresh, mock.patch.object(
                        resume.os,
                        "write",
                        side_effect=raw_write,
                    ), mock.patch.object(
                        resume.sys,
                        "stdout",
                        stdout,
                    ), mock.patch.object(
                        resume.sys,
                        "stderr",
                        stderr,
                    ):
                        result = resume.main(
                            ["--event-id", event_id, "--root", os.fspath(root)]
                        )
                    self.assertEqual(result, 2)
                    self.assertTrue(any(fd == 102 for fd, _ in calls))
                    if failure == "partial-error":
                        self.assertEqual(sum(fd == 101 for fd, _ in calls), 2)
                    recover.assert_called_once_with(root, event_id)
                    if route == "fresh":
                        fresh.assert_called_once()
                    else:
                        fresh.assert_not_called()

    def test_receipt_publish_retains_log_inode_across_rename(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            event, retained_log = self._retained_publish_fixture(root)
            event_dir = event.event_dir
            log = event_dir / retained_log.name
            real_rename = resume.start_gate._rename_noreplace_at

            def swap_log_then_publish(
                directory_fd: int,
                source_name: str,
                destination_name: str,
            ) -> None:
                replacement = event_dir / "replacement"
                replacement.write_bytes(b"PASS\n")
                os.chmod(replacement, 0o600)
                os.replace(replacement, log)
                real_rename(directory_fd, source_name, destination_name)

            try:
                with mock.patch.object(
                    resume.start_gate,
                    "_rename_noreplace_at",
                    side_effect=swap_log_then_publish,
                ):
                    with self.assertRaises(resume.GatePostCommitUncertain):
                        resume.start_gate._publish_private_receipt(
                            event,
                            b"{\"status\":\"PASS\"}\n",
                            retained_logs={retained_log.name: retained_log},
                            writer=os.write,
                            file_fsync=os.fsync,
                            directory_fsync=os.fsync,
                            before_publish=lambda: None,
                            after_publish=lambda: None,
                        )
            finally:
                retained_log.close()
                event.close()
            self.assertTrue(
                (event_dir / resume.start_gate.RECEIPT_NAME).is_file()
            )

    def test_receipt_publish_pins_logs_before_prepublish_callback(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            event, retained_log = self._retained_publish_fixture(root)
            event_dir = event.event_dir
            log = event_dir / retained_log.name

            def swap_log() -> None:
                replacement = event_dir / "replacement"
                replacement.write_bytes(b"PASS\n")
                os.chmod(replacement, 0o600)
                os.replace(replacement, log)

            try:
                with self.assertRaisesRegex(
                    resume.GateError,
                    "retained gate log changed",
                ):
                    resume.start_gate._publish_private_receipt(
                        event,
                        b'{"status":"PASS"}\n',
                        retained_logs={retained_log.name: retained_log},
                        writer=os.write,
                        file_fsync=os.fsync,
                        directory_fsync=os.fsync,
                        before_publish=swap_log,
                    )
            finally:
                retained_log.close()
                event.close()
            self.assertFalse(
                (event_dir / resume.start_gate.RECEIPT_NAME).exists()
            )

    def test_receipt_publish_retains_staging_inode_after_rename(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            event, retained_log = self._retained_publish_fixture(root)
            event_dir = event.event_dir
            receipt_bytes = b'{"status":"PASS"}\n'

            def swap_published_receipt() -> None:
                receipt = event_dir / resume.start_gate.RECEIPT_NAME
                replacement = event_dir / "replacement"
                replacement.write_bytes(receipt_bytes)
                os.chmod(replacement, 0o600)
                os.replace(replacement, receipt)

            try:
                with self.assertRaises(resume.GatePostCommitUncertain):
                    resume.start_gate._publish_private_receipt(
                        event,
                        receipt_bytes,
                        retained_logs={retained_log.name: retained_log},
                        writer=os.write,
                        file_fsync=os.fsync,
                        directory_fsync=os.fsync,
                        before_publish=lambda: None,
                        after_publish=swap_published_receipt,
                    )
            finally:
                retained_log.close()
                event.close()
            self.assertFalse(
                (event_dir / resume.start_gate.RECEIPT_STAGE_NAME).exists()
            )

    def test_source_guard_rejects_same_byte_inode_and_ancestor_swap(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            source = root / "a/b.txt"
            source.parent.mkdir()
            source.write_bytes(b"bound")
            guard = resume.start_gate._RetainedSourceGuard.capture(
                root,
                (Path("a/b.txt"),),
            )
            try:
                replacement = root / "replacement"
                replacement.write_bytes(b"bound")
                os.replace(replacement, source)
                with self.assertRaisesRegex(resume.GateError, "retained source"):
                    guard.verify()
            finally:
                guard.close()

    def test_completed_evidence_guard_rejects_same_byte_receipt_swap(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            event_relative = (
                resume.start_gate.GATE_ROOT_RELATIVE / resume.EVENT_ID
            )
            event_dir = root / event_relative
            event_dir.mkdir(mode=0o700, parents=True)
            os.chmod(event_dir, 0o700)
            receipt = event_dir / resume.RECEIPT_NAME
            receipt.write_bytes(b"receipt\n")
            os.chmod(receipt, 0o600)
            guard = resume.start_gate._RetainedEventEvidenceGuard.capture(
                root,
                event_relative,
                {resume.RECEIPT_NAME},
            )
            try:
                replacement = event_dir / "replacement"
                replacement.write_bytes(b"receipt\n")
                os.chmod(replacement, 0o600)
                os.replace(replacement, receipt)
                with self.assertRaises(resume.GateError) as raised:
                    guard.verify()
                self.assertIn(
                    str(raised.exception),
                    {
                        "retained gate evidence changed: "
                        f"{resume.RECEIPT_NAME}",
                        "retained source ancestor identity changed: "
                        f"{event_relative.as_posix()}",
                    },
                )
            finally:
                guard.close()

    def test_retained_fresh_event_create_updates_gate_root_boundary(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            (root / resume.start_gate.GATE_ROOT_RELATIVE).mkdir(parents=True)
            event = resume.start_gate._RetainedFreshEventDirectory.create(
                root,
                "TEST-FRESH-EVENT",
            )
            try:
                event.verify()
                self.assertTrue(event.event_dir.is_dir())
            finally:
                event.close()

    def test_completed_recovery_close_failure_is_postcommit_uncertain(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            event_dir = (
                root / resume.start_gate.GATE_ROOT_RELATIVE / resume.EVENT_ID
            )
            event_dir.mkdir(mode=0o700, parents=True)
            os.chmod(event_dir, 0o700)
            source_guard = mock.Mock()
            source_guard.close.side_effect = OSError("injected source close failure")
            evidence_guard = mock.Mock()
            with (
                mock.patch.object(
                    resume.start_gate._RetainedSourceGuard,
                    "capture",
                    return_value=source_guard,
                ),
                mock.patch.object(
                    resume.start_gate._RetainedEventEvidenceGuard,
                    "capture",
                    return_value=evidence_guard,
                ),
                mock.patch.object(
                    resume,
                    "_validate_completed_attempt_retained",
                    return_value=event_dir / resume.RECEIPT_NAME,
                ),
                self.assertRaisesRegex(
                    resume.GatePostCommitUncertain,
                    "guard cleanup failed",
                ),
            ):
                resume._validate_completed_attempt(
                    root,
                    event_dir,
                    resume.EVENT_ID,
                )
            source_guard.verify.assert_called()
            evidence_guard.verify.assert_called()

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            source = root / "a/b.txt"
            source.parent.mkdir()
            source.write_bytes(b"bound")
            guard = resume.start_gate._RetainedSourceGuard.capture(
                root,
                (Path("a/b.txt"),),
            )
            try:
                original_parent = root / "a-original"
                os.rename(root / "a", original_parent)
                source.parent.mkdir()
                source.write_bytes(b"bound")
                with self.assertRaisesRegex(
                    resume.GateError,
                    "retained source ancestor",
                ):
                    guard.verify()
            finally:
                guard.close()

    def test_completed_recovery_shared_lock_cleanup_failure_is_postcommit_uncertain(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            event_dir = (
                root / resume.start_gate.GATE_ROOT_RELATIVE / resume.EVENT_ID
            )
            event_dir.mkdir(mode=0o700, parents=True)
            os.chmod(event_dir, 0o700)
            checkpoint_lock = mock.Mock()
            lock_manager = mock.MagicMock()
            lock_manager.__enter__.return_value = checkpoint_lock
            lock_manager.__exit__.side_effect = OSError(
                "injected shared-lock cleanup failure"
            )
            source_guard = mock.Mock()
            evidence_guard = mock.Mock()
            with (
                mock.patch.object(
                    resume.start_gate,
                    "_checkpoint_parent_shared_lock",
                    return_value=lock_manager,
                ),
                mock.patch.object(
                    resume.start_gate._RetainedSourceGuard,
                    "capture",
                    return_value=source_guard,
                ),
                mock.patch.object(
                    resume.start_gate._RetainedEventEvidenceGuard,
                    "capture",
                    return_value=evidence_guard,
                ),
                mock.patch.object(
                    resume,
                    "_validate_completed_attempt_retained",
                    return_value=event_dir / resume.RECEIPT_NAME,
                ),
                self.assertRaisesRegex(
                    resume.GatePostCommitUncertain,
                    "terminal lock cleanup failed",
                ),
            ):
                resume._validate_completed_attempt(
                    root,
                    event_dir,
                    resume.EVENT_ID,
                )
            source_guard.close.assert_called_once_with()
            evidence_guard.close.assert_called_once_with(None)
            lock_manager.__exit__.assert_called_once()

    def test_incomplete_attempt_is_preserved_and_retry_uses_next_id(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            gate_root = root / resume.start_gate.GATE_ROOT_RELATIVE
            gate_root.mkdir(parents=True)
            event_dir = gate_root / resume.EVENT_ID
            event_dir.mkdir(mode=0o700)
            os.chmod(event_dir, 0o700)
            partial = event_dir / "01-CONTINUATION.log"
            partial.write_bytes(b"partial\n")
            os.chmod(partial, 0o600)
            identity = resume.start_gate._directory_identity(event_dir)
            partial_identity = partial.stat().st_dev, partial.stat().st_ino

            with self.assertRaisesRegex(
                resume.GateError,
                "add-only preserved",
            ):
                resume._recover_existing_attempt(
                    root,
                    resume.EVENT_ID,
                )
            self.assertEqual(
                resume.start_gate._directory_identity(event_dir),
                identity,
            )
            self.assertEqual(
                (partial.stat().st_dev, partial.stat().st_ino),
                partial_identity,
            )
            self.assertEqual(partial.read_bytes(), b"partial\n")
            next_event_id = resume.EVENT_ID[:-3] + "002"
            with mock.patch.object(
                resume,
                "_recover_existing_attempt",
                return_value=None,
            ) as recover, mock.patch.object(
                resume.start_gate,
                "run_gate",
                return_value=Path("next-receipt"),
            ) as run:
                self.assertEqual(
                    resume.run_gate(
                        root,
                        next_event_id,
                        clock=lambda: datetime(
                            2026,
                            8,
                            9,
                            1,
                            0,
                            tzinfo=resume.ZoneInfo("Asia/Seoul"),
                        ),
                    ),
                    Path("next-receipt"),
                )
            recover.assert_called_once_with(root, next_event_id)
            self.assertEqual(run.call_args.args[1], next_event_id)
            self.assertEqual(partial.read_bytes(), b"partial\n")

    def test_completed_attempt_recovery_rejects_receipt_snapshot_drift(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            event_dir = (
                root / resume.start_gate.GATE_ROOT_RELATIVE / resume.EVENT_ID
            )
            event_dir.mkdir(mode=0o700, parents=True)
            os.chmod(event_dir, 0o700)
            context = resume.start_gate.GateContext(
                checks=(("CHECK", "true"),),
                checkpoint_sha256="1" * 64,
                target_goal_sha256=resume.TARGET_GOAL_SHA256,
                source_activation_event_sha256="2" * 64,
                source_ready_event_sha256=resume.SOURCE_READY_EVENT_SHA256,
                source_ready_occurred_at=datetime(
                    2026,
                    8,
                    3,
                    tzinfo=resume.ZoneInfo("Asia/Seoul"),
                ),
                contract_binding=resume.start_gate.expected_contract_binding(),
                runtime_bindings=(("runtime.py", "3" * 64),),
            )
            receipt = {
                field: None for field in resume.start_gate.RECEIPT_FIELDS
            }
            receipt.update(
                {
                    "schema_version": "1.1",
                    "document_id": resume.DOCUMENT_ID,
                    "evidence_type": "IMPLEMENTATION_START_OR_RESUME_GATE",
                    "gate_purpose": resume.GATE_PURPOSE,
                    "status": "PASS",
                    "package_id": resume.start_gate.PACKAGE_ID,
                    "target_transition_event_id": resume.EVENT_ID,
                    "target_goal_id": resume.TARGET_GOAL_ID,
                    "target_goal_content_sha256": context.target_goal_sha256,
                    "static_plan_manifest_sha256": resume.start_gate.MANIFEST_SHA256,
                    "source_activation_event_sha256": (
                        context.source_activation_event_sha256
                    ),
                    "source_checkpoint_sha256": context.checkpoint_sha256,
                    "source_ready_event_sha256": context.source_ready_event_sha256,
                    "check_command_contract_version": (
                        context.contract_binding["contract_version"]
                    ),
                    "check_command_contract_sha256": (
                        context.contract_binding["canonical_contract_sha256"]
                    ),
                    "implementation_start_gate_contract_binding": (
                        context.contract_binding
                    ),
                    "runtime_bindings": [
                        {"path": "runtime.py", "file_sha256": "3" * 64}
                    ],
                    "check_runs": [{"output_sha256": "4" * 64}],
                    "repository_snapshot": {"bound": False},
                }
            )
            receipt_path = event_dir / resume.RECEIPT_NAME
            receipt_path.write_text(json.dumps(receipt) + "\n", encoding="utf-8")
            os.chmod(receipt_path, 0o600)
            for name in resume._expected_log_names():
                path = event_dir / name
                path.write_text("PASS\n", encoding="utf-8")
                os.chmod(path, 0o600)
            with (
                mock.patch.object(
                    resume,
                    "load_gate_context",
                    return_value=context,
                ),
                mock.patch.object(
                    resume.continuation,
                    "_validate_check_runs",
                    return_value=([], {"repository": "payload"}),
                ),
                mock.patch.object(
                    resume.start_gate,
                    "repository_snapshot_from_payload",
                    return_value={"bound": True},
                ),
                mock.patch.object(
                    resume.start_gate._RetainedSourceGuard,
                    "capture",
                    return_value=mock.Mock(),
                ),
                self.assertRaisesRegex(
                    resume.GateError,
                    "receipt repository snapshot differs",
                ),
            ):
                resume._validate_completed_attempt(
                    root,
                    event_dir,
                    resume.EVENT_ID,
                )

    def test_checkpoint_retained_guard_requires_private_single_link(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            checkpoint = root / resume.start_gate.CHECKPOINT_RELATIVE
            checkpoint.parent.mkdir(parents=True)
            checkpoint.write_bytes(b"{}\n")
            checkpoint.chmod(0o644)
            with self.assertRaisesRegex(
                resume.GateError,
                "retained source authority differs",
            ):
                resume.start_gate._RetainedSourceGuard.capture(
                    root,
                    (resume.start_gate.CHECKPOINT_RELATIVE,),
                )

            checkpoint.chmod(0o600)
            os.link(checkpoint, checkpoint.with_name("checkpoint-hardlink"))
            with self.assertRaisesRegex(
                resume.GateError,
                "retained source authority differs",
            ):
                resume.start_gate._RetainedSourceGuard.capture(
                    root,
                    (resume.start_gate.CHECKPOINT_RELATIVE,),
                )

    def test_repository_authority_seals_info_exclude_and_git_command(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            self._initialize_git_repository(root)
            guard = resume.start_gate.RetainedRepositoryAuthorityGuard.capture(
                root
            )
            mutation = RuntimeError("expected authority mutation")
            try:
                exclude = root / ".git/info/exclude"
                replacement = root / ".git/info/exclude.replacement"
                replacement.write_bytes(exclude.read_bytes())
                os.replace(replacement, exclude)
                with self.assertRaisesRegex(
                    resume.GateError,
                    "repository namespace",
                ):
                    guard.verify()
            finally:
                guard.close(mutation)

    def test_repository_authority_allows_unrelated_parent_sibling_churn(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            outer = Path(temporary).resolve()
            root = outer / "repository-parent/repository"
            root.mkdir(parents=True)
            guard = resume.start_gate.RetainedRepositoryAuthorityGuard.capture(
                root
            )
            try:
                (root.parent / "unrelated-root-sibling").mkdir()
                guard.verify()
                (root.parent.parent / "unrelated-parent-sibling").mkdir()
                guard.verify()
            finally:
                guard.close()

    def test_repository_authority_rejects_root_and_parent_inode_swaps(
        self,
    ) -> None:
        for target in ("root", "root-parent"):
            with self.subTest(target=target), tempfile.TemporaryDirectory() as temporary:
                outer = Path(temporary).resolve()
                root = outer / "repository-parent/repository"
                root.mkdir(parents=True)
                guard = (
                    resume.start_gate.RetainedRepositoryAuthorityGuard.capture(
                        root
                    )
                )
                mutation = RuntimeError("expected namespace inode swap")
                try:
                    if target == "root":
                        detached = root.with_name("repository-original")
                        os.rename(root, detached)
                        root.mkdir()
                    else:
                        detached = root.parent.with_name(
                            "repository-parent-original"
                        )
                        os.rename(root.parent, detached)
                        root.mkdir(parents=True)
                    with self.assertRaisesRegex(
                        resume.GateError,
                        "repository namespace",
                    ):
                        guard.verify()
                finally:
                    guard.close(mutation)

    def test_repository_authority_seals_root_inventory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve() / "repository-parent/repository"
            root.mkdir(parents=True)
            guard = resume.start_gate.RetainedRepositoryAuthorityGuard.capture(
                root
            )
            mutation = RuntimeError("expected root inventory mutation")
            try:
                (root / "unexpected-root-entry").write_bytes(b"drift\n")
                with self.assertRaisesRegex(
                    resume.GateError,
                    "repository namespace path identity changed",
                ):
                    guard.verify()
            finally:
                guard.close(mutation)

    def test_direct_tmp_repository_isolated_snapshot_capture_succeeds(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory(
            prefix="walksafe-direct-repository-",
            dir="/tmp",
        ) as temporary:
            root = Path(temporary).resolve()
            self._initialize_git_repository(root)
            repository_guard = (
                resume.start_gate.RetainedRepositoryAuthorityGuard.capture(root)
            )
            payload = {"repository": "direct-tmp-authority"}
            authority = resume.start_gate._RepositoryStateAuthority.from_payload(
                payload,
                strict_cli_output=True,
            )
            snapshot = None
            primary: BaseException | None = None
            try:
                snapshot = (
                    resume.start_gate._RetainedIsolatedRepositorySnapshot.capture(
                        root,
                        resume.EVENT_ID,
                        authority,
                        repository_guard,
                        lambda _root, _checkpoint, _event_id: copy.deepcopy(
                            payload
                        ),
                    )
                )
                snapshot.verify()
            except BaseException as exc:
                primary = exc
                raise
            finally:
                if snapshot is not None:
                    snapshot.close(primary)
                repository_guard.close(primary)

    def test_isolated_snapshot_is_external_private_and_independent(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve() / "live-parent/live-repository"
            root.mkdir(mode=0o700, parents=True)
            self._initialize_git_repository(root)
            repository_guard = (
                resume.start_gate.RetainedRepositoryAuthorityGuard.capture(root)
            )
            payload = {"repository": "exact-authority"}
            authority = resume.start_gate._RepositoryStateAuthority.from_payload(
                payload,
                strict_cli_output=True,
            )
            snapshot = None
            primary: BaseException | None = None
            try:
                snapshot = (
                    resume.start_gate._RetainedIsolatedRepositorySnapshot.capture(
                        root,
                        resume.EVENT_ID,
                        authority,
                        repository_guard,
                        lambda _root, _checkpoint, _event_id: copy.deepcopy(
                            payload
                        ),
                    )
                )
                self.assertEqual(
                    stat.S_IMODE(snapshot.root.stat().st_mode),
                    0o700,
                )
                self.assertTrue((snapshot.root / ".git").is_dir())
                self.assertFalse((snapshot.root / ".git").is_symlink())
                with self.assertRaises(ValueError):
                    snapshot.root.relative_to(root)
                self.assertNotEqual(
                    (snapshot.root / ".git").stat().st_ino,
                    (root / ".git").stat().st_ino,
                )
                (root / "tracked.txt").write_text(
                    "live drift\n",
                    encoding="utf-8",
                )
                self.assertEqual(
                    (snapshot.root / "tracked.txt").read_text(encoding="utf-8"),
                    "bound\n",
                )
                snapshot.verify()
            except BaseException as exc:
                primary = exc
                raise
            finally:
                if snapshot is not None:
                    snapshot.close(primary)
                repository_guard.close(primary)

    def test_receipt_final_cas_is_immediately_before_atomic_rename(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            event, retained_log = self._retained_publish_fixture(root)
            ordering: list[str] = []
            real_rename = resume.start_gate._rename_noreplace_at

            def observed_rename(
                directory_fd: int,
                source_name: str,
                destination_name: str,
            ) -> None:
                self.assertEqual(ordering, ["final-cas"])
                real_rename(directory_fd, source_name, destination_name)

            try:
                with mock.patch.object(
                    resume.start_gate,
                    "_rename_noreplace_at",
                    side_effect=observed_rename,
                ):
                    resume.start_gate._publish_private_receipt(
                        event,
                        b'{"status":"PASS"}\n',
                        retained_logs={retained_log.name: retained_log},
                        writer=os.write,
                        file_fsync=os.fsync,
                        directory_fsync=os.fsync,
                        before_publish=lambda: None,
                        final_cas=lambda: ordering.append("final-cas"),
                    )
            finally:
                retained_log.close()
                event.close()

    def test_receipt_revalidates_exact_evidence_after_final_cas(self) -> None:
        receipt_bytes = b'{"status":"PASS"}\n'
        for mutation in ("log-replace", "stage-replace", "extra-inventory"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve()
                event, retained_log = self._retained_publish_fixture(root)
                event_dir = event.event_dir

                def mutate_after_repository_cas() -> None:
                    replacement = event_dir / "replacement"
                    if mutation == "log-replace":
                        replacement.write_bytes(retained_log.content)
                        replacement.chmod(0o600)
                        os.replace(replacement, event_dir / retained_log.name)
                    elif mutation == "stage-replace":
                        replacement.write_bytes(receipt_bytes)
                        replacement.chmod(0o600)
                        os.replace(
                            replacement,
                            event_dir / resume.start_gate.RECEIPT_STAGE_NAME,
                        )
                    else:
                        replacement.write_bytes(b"unexpected inventory\n")
                        replacement.chmod(0o600)

                real_rename = resume.start_gate._rename_noreplace_at
                try:
                    with mock.patch.object(
                        resume.start_gate,
                        "_rename_noreplace_at",
                        wraps=real_rename,
                    ) as rename, self.assertRaises(resume.GateError):
                        resume.start_gate._publish_private_receipt(
                            event,
                            receipt_bytes,
                            retained_logs={retained_log.name: retained_log},
                            writer=os.write,
                            file_fsync=os.fsync,
                            directory_fsync=os.fsync,
                            before_publish=lambda: None,
                            final_cas=mutate_after_repository_cas,
                        )
                    rename.assert_not_called()
                    self.assertFalse(
                        (event_dir / resume.start_gate.RECEIPT_NAME).exists()
                    )
                finally:
                    retained_log.close()
                    event.close()

    def test_final_repository_capture_source_swap_blocks_receipt_rename(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            checkpoint = root / resume.start_gate.CHECKPOINT_RELATIVE
            checkpoint.parent.mkdir(parents=True)
            source_bytes = b'{"source":"retained"}\n'
            checkpoint.write_bytes(source_bytes)
            checkpoint.chmod(0o600)
            event, retained_log = self._retained_publish_fixture(root)
            source_guard = resume.start_gate._RetainedSourceGuard.capture(
                root,
                (resume.start_gate.CHECKPOINT_RELATIVE,),
            )
            repository_guard = mock.Mock()
            resources = mock.Mock()
            resources.repository_guard = repository_guard

            def verify_source() -> None:
                repository_guard.verify()
                source_guard.verify()
                repository_guard.verify()

            resources.verify_source.side_effect = verify_source
            payload = {"repository": "exact"}
            authority = resume.start_gate._RepositoryStateAuthority.from_payload(
                payload,
                strict_cli_output=False,
            )

            def capture_repository_state(
                _root: Path,
                _checkpoint: Path,
                _event_id: str,
            ) -> dict[str, str]:
                replacement = checkpoint.with_name("source-replacement")
                replacement.write_bytes(source_bytes)
                replacement.chmod(0o600)
                os.replace(replacement, checkpoint)
                return copy.deepcopy(payload)

            def retained_capture_state(
                checkpoint_path: Path,
                event_id: str,
                *,
                capture: object,
            ) -> dict[str, str]:
                self.assertEqual(checkpoint_path, checkpoint)
                self.assertEqual(event_id, resume.EVENT_ID)
                self.assertIs(capture, capture_repository_state)
                return capture_repository_state(root, checkpoint_path, event_id)

            repository_guard.capture_state.side_effect = retained_capture_state

            def final_cas() -> None:
                resources.verify_source()
                resume.start_gate._require_prepublish_repository_exact(
                    resources,
                    root,
                    resume.EVENT_ID,
                    capture_repository_state,
                    authority,
                )

            event_dir = event.event_dir
            real_rename = resume.start_gate._rename_noreplace_at
            primary: BaseException | None = None
            try:
                with mock.patch.object(
                    resume.start_gate,
                    "_rename_noreplace_at",
                    wraps=real_rename,
                ) as rename, self.assertRaisesRegex(
                    resume.GateError,
                    "source changed before receipt publication",
                ) as raised:
                    resume.start_gate._publish_private_receipt(
                        event,
                        b'{"status":"PASS"}\n',
                        retained_logs={retained_log.name: retained_log},
                        writer=os.write,
                        file_fsync=os.fsync,
                        directory_fsync=os.fsync,
                        before_publish=lambda: None,
                        final_cas=final_cas,
                    )
                primary = raised.exception
                rename.assert_not_called()
                self.assertFalse(
                    (event_dir / resume.start_gate.RECEIPT_NAME).exists()
                )
                repository_guard.capture_state.assert_called_once()
                self.assertEqual(repository_guard.verify.call_count, 3)
            except BaseException as exc:
                primary = exc
                raise
            finally:
                source_guard.close(primary)
                retained_log.close(primary)
                event.close(primary)

    def test_postpublish_baseexception_and_recovery_fsync_are_uncertain(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            event, retained_log = self._retained_publish_fixture(root)
            try:
                with self.assertRaises(resume.GatePostCommitUncertain):
                    resume.start_gate._publish_private_receipt(
                        event,
                        b'{"status":"PASS"}\n',
                        retained_logs={retained_log.name: retained_log},
                        writer=os.write,
                        file_fsync=os.fsync,
                        directory_fsync=os.fsync,
                        before_publish=lambda: None,
                        after_publish=lambda: (_ for _ in ()).throw(
                            KeyboardInterrupt()
                        ),
                    )
            finally:
                retained_log.close()
                event.close()

        evidence_guard = mock.Mock()
        evidence_guard.fsync_event_and_parent.side_effect = OSError(
            "injected completed-evidence fsync failure"
        )
        with self.assertRaisesRegex(
            resume.GatePostCommitUncertain,
            "durability is uncertain",
        ):
            resume._fsync_completed_evidence(evidence_guard)

    def test_completed_recovery_requires_current_full_repository_exact_cas(
        self,
    ) -> None:
        repository_guard = mock.Mock()
        repository_guard.capture_state.return_value = {"state": "drifted"}
        with self.assertRaisesRegex(
            resume.GateError,
            "current full repository",
        ):
            resume._require_current_repository_exact(
                Path("/repository"),
                resume.EVENT_ID,
                {"state": "recorded"},
                repository_guard,
            )

        repository_guard.capture_state.side_effect = OSError(
            "injected current repository capture failure"
        )
        with self.assertRaisesRegex(
            resume.GatePostCommitUncertain,
            "current repository capture is uncertain",
        ):
            resume._require_current_repository_exact(
                Path("/repository"),
                resume.EVENT_ID,
                {"state": "recorded"},
                repository_guard,
            )


if __name__ == "__main__":
    unittest.main()

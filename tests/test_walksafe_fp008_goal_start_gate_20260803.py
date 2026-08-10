from __future__ import annotations

import copy
from datetime import datetime, timedelta, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest import mock

from scripts import check_walksafe_project_continuation_v2_4 as continuation
from scripts import run_walksafe_fp008_goal_start_gate_20260803 as gate


ROOT = Path(__file__).resolve().parents[1]
EVENT_ID = "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP008-20260803-001"


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def repository_payload(event_id: str) -> dict[str, object]:
    return {
        "schema_version": "1.0.0",
        "evidence_type": "GATE_REPOSITORY_STATE",
        "gate_event_id": event_id,
        "repository": {
            "head_commit": "a" * 40,
            "branch": "test/fp008-gate",
            "object_format": "sha1",
        },
        "git_status_raw": {
            "scope": gate.SNAPSHOT_SCOPE,
            "sha256": "1" * 64,
            "byte_count": 11,
            "record_count": 1,
        },
        "dirty_snapshot": {
            "dirty_path_count": 2,
            "path_set_sha256": "2" * 64,
            "content_set_sha256": "3" * 64,
            "index_state_sha256": "4" * 64,
        },
        "checkpoint_controlled_working_snapshot": {
            "base_head": "a" * 40,
            "managed_changed_path_count": 3,
            "path_set_sha256": "5" * 64,
            "content_set_sha256": "6" * 64,
        },
        "transaction_exclusions": {
            "allowed_rule_count": 2,
            "checkpoint_exact_path": gate.CHECKPOINT_RELATIVE.as_posix(),
            "gate_event_exact_prefix": (
                gate.GATE_ROOT_RELATIVE / event_id
            ).as_posix()
            + "/",
        },
    }


class FakeRunner:
    def __init__(
        self,
        *,
        fail_index: int | None = None,
        empty_indices: set[int] | None = None,
        invalid_repository: bool = False,
        raise_index: int | None = None,
        after_call: object | None = None,
    ) -> None:
        self.fail_index = fail_index
        self.empty_indices = empty_indices or set()
        self.invalid_repository = invalid_repository
        self.raise_index = raise_index
        self.after_call = after_call
        self.calls: list[dict[str, object]] = []

    def __call__(self, command: str, **kwargs: object) -> subprocess.CompletedProcess[str]:
        index = len(self.calls) + 1
        self.calls.append({"command": command, **kwargs})
        output = kwargs["stdout"]
        assert hasattr(output, "write")
        if "--print-gate-repository-state" in command:
            environment = kwargs["env"]
            assert isinstance(environment, dict)
            payload = repository_payload(environment["WALKSAFE_GATE_EVENT_ID"])
            if self.invalid_repository:
                payload["git_status_raw"]["scope"] = "UNBOUND"  # type: ignore[index]
            output.write((json.dumps(payload) + "\n").encode("utf-8"))
        elif index not in self.empty_indices:
            output.write(f"check {index}: PASS\n".encode("utf-8"))
        if callable(self.after_call):
            self.after_call(index)
        if index == self.raise_index:
            raise RuntimeError("injected runner error")
        return subprocess.CompletedProcess(
            args=command,
            returncode=7 if index == self.fail_index else 0,
        )


class WalkSafeFp008GoalStartGateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        for relative in (
            gate.MANIFEST_RELATIVE,
            gate.CONTRACT_RELATIVE,
            gate.GOAL_RELATIVE,
            gate.ROOT_CONTROL_TEST_RELATIVE,
            *gate.RUNTIME_BINDING_RELATIVES,
        ):
            self._copy(relative)
        (self.root / gate.GATE_ROOT_RELATIVE).mkdir(parents=True)
        self.checkpoint = self._checkpoint()
        self._write_checkpoint(self.checkpoint)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _copy(self, relative: Path) -> None:
        destination = self.root / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes((ROOT / relative).read_bytes())

    def _write_checkpoint(self, checkpoint: dict[str, object]) -> None:
        path = self.root / gate.CHECKPOINT_RELATIVE
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(checkpoint, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        path.chmod(0o600)

    def _checkpoint(self) -> dict[str, object]:
        activation = {
            "sequence": 1,
            "event_type": "PACKAGE_ACTIVATED",
            "static_plan_manifest_sha256": gate.MANIFEST_SHA256,
            "event_sha256": "7" * 64,
        }
        history: list[dict[str, object]] = [activation]
        history.extend(
            {
                "sequence": sequence,
                "event_type": "TEST_HISTORY_EVENT",
                "event_sha256": f"{sequence:064x}",
            }
            for sequence in range(2, 45)
        )
        materialized = {
            "sequence": 45,
            "event_id": gate.MATERIALIZED_EVENT_ID,
            "event_type": "GOAL_MATERIALIZED",
            "materialized_goal_id": gate.TARGET_GOAL_ID,
            "to_status": "PLANNED",
            "previous_event_sha256": history[-1]["event_sha256"],
        }
        materialized["event_sha256"] = gate.event_sha256(materialized)
        ready = {
            "sequence": gate.SOURCE_SEQUENCE,
            "event_id": gate.READY_EVENT_ID,
            "event_type": "GOAL_READY",
            "subject_goal_id": gate.TARGET_GOAL_ID,
            "focus_goal_id": gate.TARGET_GOAL_ID,
            "focus_goal_content_sha256": gate.TARGET_GOAL_SHA256,
            "from_status": "PLANNED",
            "to_status": "READY",
            "previous_event_sha256": materialized["event_sha256"],
            "occurred_at": "2026-08-03T13:20:01+09:00",
            "implementation_start_gate_contract_binding": (
                gate.expected_contract_binding()
            ),
        }
        ready["event_sha256"] = gate.event_sha256(ready)
        history.extend([materialized, ready])
        return {
            "goal_execution": {
                "package_id": gate.PACKAGE_ID,
                "package_status": "ACTIVE",
                "activation_status": "ACTIVE",
                "static_plan_manifest_path": gate.MANIFEST_RELATIVE.as_posix(),
                "static_plan_manifest_sha256": gate.MANIFEST_SHA256,
                "focus_goal_id": gate.TARGET_GOAL_ID,
                "focus_goal_path": gate.GOAL_RELATIVE.as_posix(),
                "focus_work_item_id": gate.WORK_ITEM_ID,
                "focus_source": "IMPLEMENTATION_BACKLOG",
                "ready_frontier_goal_ids": list(gate.READY_FRONTIER),
                "status_by_goal": {
                    gate.TARGET_GOAL_ID: "READY",
                    gate.PREDECESSOR_GOAL_ID: "COMPLETE_AT_TARGET",
                },
                "blockers_by_goal": {},
                "blocked_goal_ids": [],
                "pending_questions": [],
                "open_question_count": 0,
                "dynamic_goal_inventory": {
                    gate.TARGET_GOAL_ID: {
                        "goal_id": gate.TARGET_GOAL_ID,
                        "path": gate.GOAL_RELATIVE.as_posix(),
                        "sha256": gate.TARGET_GOAL_SHA256,
                        "materialized_event_sha256": materialized["event_sha256"],
                        "predecessor_goal_id": gate.PREDECESSOR_GOAL_ID,
                    }
                },
                "materialized_child_goal_ids_by_parent": {
                    gate.PARENT_GOAL_ID: [gate.TARGET_GOAL_ID]
                },
                "transition_history": history,
                "transition_history_anchor_sha256": ready["event_sha256"],
            },
            "current_work": {
                "work_item_id": gate.WORK_ITEM_ID,
                "current_focus": (
                    "FP008/GAP-017 Goal READY; active internal start gate not run"
                ),
                "release_completion_claimed": False,
            },
        }

    @staticmethod
    def _clock() -> datetime:
        return datetime(2026, 8, 3, 14, 0, tzinfo=timezone.utc).astimezone(
            timezone.utc
        )

    def _run(
        self,
        runner: FakeRunner,
        *,
        event_id: str = EVENT_ID,
        repository_state_guard: object | None = None,
        source_ready_event_sha256: str | None = None,
        receipt_writer: object | None = None,
        receipt_file_fsync: object | None = None,
        receipt_directory_fsync: object | None = None,
    ) -> Path:
        environment = {
            "HOME": str(self.root / "home"),
            "JAVA_HOME": str(self.root / "java"),
            "WALKSAFE_TEST_DATABASE_URL": "postgresql://secret:test@db/walksafe_test",
            "DATABASE_URL": "postgresql://operator:secret@prod/walksafe",
            "BASH_ENV": str(self.root / "malicious-bash-env"),
            "PYTEST_ADDOPTS": "--pwned",
            "GRADLE_OPTS": "-Dunsafe=true",
            "JAVA_TOOL_OPTIONS": "-javaagent:unsafe.jar",
        }
        ready = self.checkpoint["goal_execution"]["transition_history"][-1]  # type: ignore[index]
        synthetic_ready_sha256 = ready["event_sha256"]  # type: ignore[index]
        with (
            mock.patch.dict(os.environ, environment, clear=True),
            mock.patch.object(
                gate,
                "SOURCE_READY_EVENT_SHA256",
                (
                    synthetic_ready_sha256
                    if source_ready_event_sha256 is None
                    else source_ready_event_sha256
                ),
            ),
        ):
            return gate.run_gate(
                self.root,
                event_id,
                process_runner=runner,
                repository_state_guard=(
                    repository_state_guard
                    if callable(repository_state_guard)
                    else lambda _root, _checkpoint, current_event_id: (
                        repository_payload(current_event_id)
                    )
                ),
                receipt_writer=(
                    receipt_writer if callable(receipt_writer) else os.write
                ),
                receipt_file_fsync=(
                    receipt_file_fsync
                    if callable(receipt_file_fsync)
                    else os.fsync
                ),
                receipt_directory_fsync=(
                    receipt_directory_fsync
                    if callable(receipt_directory_fsync)
                    else os.fsync
                ),
                clock=self._clock,
            )

    def test_external_contract_exact_order_and_internal_only_scope(self) -> None:
        checks, contract = gate._load_gate_contract(self.root)
        self.assertEqual(
            sha256_bytes((self.root / gate.CONTRACT_RELATIVE).read_bytes()),
            gate.CONTRACT_FILE_SHA256,
        )
        self.assertEqual(
            gate.canonical_sha256(contract),
            gate.CONTRACT_CANONICAL_SHA256,
        )
        self.assertEqual(
            tuple(check_id for check_id, _ in checks),
            gate.EXPECTED_CHECK_IDS,
        )
        commands = dict(checks)
        self.assertIn(
            "validate_v24_artifact_work_queue",
            commands["V24_ARTIFACT_WORK_QUEUE"],
        )
        self.assertIn(
            "WALKSAFE_TEST_DATABASE_URL:?required dedicated test database",
            commands["BACKEND_TEST_DATABASE_PREFLIGHT"],
        )
        self.assertIn("alembic -c backend/alembic.ini upgrade head", commands["BACKEND_TEST_DATABASE_PREFLIGHT"])
        for path in (
            "backend/tests/test_admin_security.py",
            "backend/tests/test_openapi_contract.py",
            "backend/tests/test_reports.py",
            "backend/tests/test_reports_v2.py",
            "backend/tests/test_report_original_access.py",
        ):
            self.assertIn(path, commands["BACKEND_ADMIN_REPORTS_OPENAPI_POSTGRES"])
        self.assertIn("--offline --no-daemon --max-workers=1", commands["ANDROID_USER_INTERNAL"])
        self.assertIn(":app:testDebugUnitTest", commands["ANDROID_USER_INTERNAL"])
        self.assertIn(":adminapp:testDebugUnitTest", commands["ANDROID_ADMIN_INTERNAL"])
        self.assertNotIn(":app:testDebugUnitTest", commands["ANDROID_ADMIN_INTERNAL"])
        for path in (
            "tests/test_walksafe_fp008_goal_seq45_46_20260803.py",
            "tests/test_walksafe_fp008_goal_start_gate_20260803.py",
            "tests/test_walksafe_project_continuation_v2_4.py",
        ):
            self.assertIn(path, commands["ROOT_FP008_CONTROL_REGRESSION"])
        for _, command in checks:
            for fragment in gate.FORBIDDEN_COMMAND_FRAGMENTS:
                self.assertNotIn(fragment, command.lower())

    def test_success_is_private_add_only_bound_and_secret_free(self) -> None:
        runner = FakeRunner(empty_indices={3})
        old_umask = os.umask(0o777)
        try:
            receipt_path = self._run(runner)
        finally:
            os.umask(old_umask)

        event_dir = receipt_path.parent
        self.assertEqual(stat.S_IMODE(event_dir.stat().st_mode), 0o700)
        expected_files = {
            f"{index:02d}-{check_id}.log"
            for index, check_id in enumerate(gate.EXPECTED_CHECK_IDS, start=1)
        } | {"implementation-start-gate-receipt.json"}
        self.assertEqual({path.name for path in event_dir.iterdir()}, expected_files)
        for path in event_dir.iterdir():
            metadata = path.stat()
            self.assertEqual(stat.S_IMODE(metadata.st_mode), 0o600)
            self.assertEqual(metadata.st_nlink, 1)

        receipt_bytes = receipt_path.read_bytes()
        self.assertNotIn(b"postgresql://secret", receipt_bytes)
        receipt = json.loads(receipt_bytes)
        self.assertEqual(receipt["schema_version"], "1.1")
        self.assertEqual(receipt["status"], "PASS")
        self.assertEqual(receipt["target_goal_id"], gate.TARGET_GOAL_ID)
        self.assertEqual(
            receipt["implementation_start_gate_contract_binding"],
            gate.expected_contract_binding(),
        )
        self.assertEqual(
            receipt["check_command_contract_sha256"],
            gate.CONTRACT_CANONICAL_SHA256,
        )
        self.assertEqual(
            receipt["source_ready_event_sha256"],
            self.checkpoint["goal_execution"]["transition_history"][-1]["event_sha256"],  # type: ignore[index]
        )
        self.assertEqual(
            receipt["source_checkpoint_sha256"],
            sha256_bytes((self.root / gate.CHECKPOINT_RELATIVE).read_bytes()),
        )
        self.assertEqual(
            [binding["path"] for binding in receipt["runtime_bindings"]],
            [path.as_posix() for path in gate.RUNTIME_BINDING_RELATIVES],
        )
        wrapper_jar_binding = next(
            binding
            for binding in receipt["runtime_bindings"]
            if binding["path"]
            == "apps/android/gradle/wrapper/gradle-wrapper.jar"
        )
        self.assertEqual(
            wrapper_jar_binding["file_sha256"],
            sha256_bytes(
                (
                    self.root
                    / "apps/android/gradle/wrapper/gradle-wrapper.jar"
                ).read_bytes()
            ),
        )
        self.assertEqual(len(receipt["check_runs"]), 9)
        self.assertEqual(
            [run["command"] for run in receipt["check_runs"]],
            [call["command"] for call in runner.calls],
        )
        for run in receipt["check_runs"]:
            content = (self.root / run["output_path"]).read_bytes()
            self.assertEqual(sha256_bytes(content), run["output_sha256"])
        self.assertIn(
            b"TEST_LAYER_REGISTRY_VALIDATE: PASS",
            (event_dir / "03-TEST_LAYER_REGISTRY_VALIDATE.log").read_bytes(),
        )
        self.assertEqual(
            receipt["repository_snapshot"]["snapshot_scope"],
            gate.SNAPSHOT_SCOPE,
        )
        self.assertEqual(
            receipt["repository_snapshot"]["gate_repository_state_output_sha256"],
            receipt["check_runs"][-1]["output_sha256"],
        )
        timestamps = [
            receipt["execution_window"]["started_at"],
            *[run["executed_at"] for run in receipt["check_runs"]],
            receipt["execution_window"]["ended_at"],
            receipt["generated_at"],
        ]
        self.assertEqual(timestamps, sorted(timestamps))
        self.assertEqual(len(timestamps), len(set(timestamps)))
        for call in runner.calls:
            environment = call["env"]
            self.assertEqual(environment["WALKSAFE_GATE_EVENT_ID"], EVENT_ID)  # type: ignore[index]
            self.assertIn("WALKSAFE_TEST_DATABASE_URL", environment)  # type: ignore[operator]
            for name in (
                "DATABASE_URL",
                "BASH_ENV",
                "PYTEST_ADDOPTS",
                "GRADLE_OPTS",
                "JAVA_TOOL_OPTIONS",
            ):
                self.assertNotIn(name, environment)  # type: ignore[operator]

    def test_seq47_checker_accepts_goal_scoped_receipt_and_keeps_legacy_contract(self) -> None:
        receipt_path = self._run(FakeRunner(empty_indices={3}))
        receipt = json.loads(receipt_path.read_bytes())
        occurred_at = (
            datetime.fromisoformat(receipt["generated_at"]) + timedelta(seconds=1)
        ).replace(microsecond=0).isoformat()
        ready = self.checkpoint["goal_execution"]["transition_history"][-1]  # type: ignore[index]
        event = {
            "sequence": 47,
            "event_id": EVENT_ID,
            "event_type": "GOAL_STARTED",
            "subject_goal_id": gate.TARGET_GOAL_ID,
            "previous_event_sha256": ready["event_sha256"],  # type: ignore[index]
            "occurred_at": occurred_at,
            "repository_snapshot_before": receipt["repository_snapshot"],
            "implementation_start_gate_binding": {
                "document_id": receipt["document_id"],
                "path": receipt_path.relative_to(self.root).as_posix(),
                "file_sha256": sha256_bytes(receipt_path.read_bytes()),
            },
        }
        self.checkpoint["goal_execution"]["transition_history"].append(event)  # type: ignore[index]
        manifest = json.loads((self.root / gate.MANIFEST_RELATIVE).read_bytes())
        errors = continuation._validate_start_gate(
            self.root,
            event=event,
            checkpoint=self.checkpoint,
            goal_paths={gate.TARGET_GOAL_ID: gate.GOAL_RELATIVE.as_posix()},
            manifest=manifest,
            manifest_sha256=gate.MANIFEST_SHA256,
            activation_sha256="7" * 64,
            activation_occurred_at=datetime(2026, 8, 3, tzinfo=timezone.utc),
        )
        self.assertEqual(errors, [])
        legacy_checks = continuation._control_checks(
            manifest,
            "implementation_start_gate_checks",
        )
        self.assertEqual(len(legacy_checks), 19)
        self.assertEqual(
            [item["check_id"] for item in legacy_checks],
            manifest["transition_contract"]["implementation_start_gate_check_ids"],
        )

    def test_tampered_contract_or_seq46_binding_fails_before_mutation(self) -> None:
        contract_path = self.root / gate.CONTRACT_RELATIVE
        contract = json.loads(contract_path.read_bytes())
        contract["ordered_checks"][0]["command"] += " --tampered"
        contract_path.write_text(json.dumps(contract), encoding="utf-8")
        runner = FakeRunner()
        with self.assertRaisesRegex(gate.GateError, "contract file SHA-256"):
            self._run(runner)
        self.assertEqual(runner.calls, [])
        self.assertFalse(os.path.lexists(self.root / gate.GATE_ROOT_RELATIVE / EVENT_ID))

        self._copy(gate.CONTRACT_RELATIVE)
        ready = self.checkpoint["goal_execution"]["transition_history"][-1]  # type: ignore[index]
        ready["implementation_start_gate_contract_binding"]["file_sha256"] = "0" * 64  # type: ignore[index]
        ready["event_sha256"] = gate.event_sha256(ready)  # type: ignore[arg-type,index]
        self.checkpoint["goal_execution"]["transition_history_anchor_sha256"] = ready["event_sha256"]  # type: ignore[index]
        self._write_checkpoint(self.checkpoint)
        with self.assertRaisesRegex(gate.GateError, "contract binding differs"):
            self._run(runner)
        self.assertEqual(runner.calls, [])
        self.assertFalse(os.path.lexists(self.root / gate.GATE_ROOT_RELATIVE / EVENT_ID))

    def test_wrong_seq46_ready_event_pin_fails_before_mutation(self) -> None:
        runner = FakeRunner()
        with self.assertRaisesRegex(gate.GateError, "trust anchor differs"):
            self._run(runner, source_ready_event_sha256="0" * 64)
        self.assertEqual(runner.calls, [])
        self.assertFalse(
            os.path.lexists(self.root / gate.GATE_ROOT_RELATIVE / EVENT_ID)
        )

    def test_failed_attempt_is_preserved_and_cannot_be_reused(self) -> None:
        runner = FakeRunner(fail_index=5)
        with self.assertRaises(gate.GateCheckFailed) as raised:
            self._run(runner)
        self.assertEqual(raised.exception.exit_code, 7)
        event_dir = self.root / gate.GATE_ROOT_RELATIVE / EVENT_ID
        self.assertTrue(event_dir.is_dir())
        self.assertEqual(
            {path.name for path in event_dir.iterdir()},
            {
                "01-CONTINUATION.log",
                "02-V24_ARTIFACT_WORK_QUEUE.log",
                "03-TEST_LAYER_REGISTRY_VALIDATE.log",
                "04-BACKEND_TEST_DATABASE_PREFLIGHT.log",
                "05-BACKEND_ADMIN_REPORTS_OPENAPI_POSTGRES.log",
            },
        )
        self.assertFalse((event_dir / "implementation-start-gate-receipt.json").exists())
        call_count = len(runner.calls)
        with self.assertRaisesRegex(gate.GateError, "already exists"):
            self._run(runner)
        self.assertEqual(len(runner.calls), call_count)
        for path in event_dir.iterdir():
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            self.assertEqual(path.stat().st_nlink, 1)

    def test_broken_symlink_attempt_is_rejected_without_checks(self) -> None:
        event_path = self.root / gate.GATE_ROOT_RELATIVE / EVENT_ID
        event_path.symlink_to("missing-attempt")
        runner = FakeRunner()
        with self.assertRaisesRegex(gate.GateError, "already exists"):
            self._run(runner)
        self.assertEqual(runner.calls, [])
        self.assertTrue(event_path.is_symlink())

    def test_empty_success_output_and_invalid_repository_preserve_no_receipt(self) -> None:
        empty_runner = FakeRunner(empty_indices={1})
        with self.assertRaisesRegex(gate.GateError, "empty"):
            self._run(empty_runner)
        empty_dir = self.root / gate.GATE_ROOT_RELATIVE / EVENT_ID
        self.assertTrue((empty_dir / "01-CONTINUATION.log").exists())
        self.assertFalse((empty_dir / "implementation-start-gate-receipt.json").exists())

        second_event = "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP008-20260803-002"
        invalid_runner = FakeRunner(invalid_repository=True)
        with self.assertRaisesRegex(gate.GateError, "transaction exclusions"):
            self._run(invalid_runner, event_id=second_event)
        invalid_dir = self.root / gate.GATE_ROOT_RELATIVE / second_event
        self.assertEqual(len(list(invalid_dir.glob("*.log"))), 9)
        self.assertFalse((invalid_dir / "implementation-start-gate-receipt.json").exists())

    def test_checkpoint_source_cas_drift_preserves_logs_without_receipt(self) -> None:
        def drift_after_last_check(index: int) -> None:
            if index == 9:
                drifted = copy.deepcopy(self.checkpoint)
                drifted["test_only_concurrent_drift"] = True
                self._write_checkpoint(drifted)

        runner = FakeRunner(after_call=drift_after_last_check)
        with self.assertRaisesRegex(gate.GateError, "source changed"):
            self._run(runner)
        event_dir = self.root / gate.GATE_ROOT_RELATIVE / EVENT_ID
        self.assertEqual(len(list(event_dir.glob("*.log"))), 9)
        self.assertFalse((event_dir / "implementation-start-gate-receipt.json").exists())

    def test_repository_commit_guard_drift_preserves_logs_without_receipt(self) -> None:
        def drifted_repository(
            _root: Path,
            _checkpoint: Path,
            event_id: str,
        ) -> dict[str, object]:
            payload = repository_payload(event_id)
            payload["dirty_snapshot"]["dirty_path_count"] = 3  # type: ignore[index]
            return payload

        runner = FakeRunner()
        with self.assertRaisesRegex(gate.GateError, "changed after REPOSITORY_STATE"):
            self._run(runner, repository_state_guard=drifted_repository)
        event_dir = self.root / gate.GATE_ROOT_RELATIVE / EVENT_ID
        self.assertEqual(len(list(event_dir.glob("*.log"))), 9)
        self.assertFalse((event_dir / "implementation-start-gate-receipt.json").exists())

    def test_production_snapshot_copies_only_authorized_ignored_paths(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as repository_directory:
            repository = Path(repository_directory)
            subprocess.run(
                ["git", "init", "--quiet", "--initial-branch=main"],
                cwd=repository,
                check=True,
            )
            (repository / ".gitignore").write_text(
                "*.log\ndocs/control/execution/goal-gates/\n",
                encoding="utf-8",
            )
            (repository / "tracked.txt").write_text("tracked\n", encoding="utf-8")
            subprocess.run(
                ["git", "add", ".gitignore", "tracked.txt"],
                cwd=repository,
                check=True,
            )
            subprocess.run(
                [
                    "git",
                    "-c",
                    "user.name=WalkSafe Test",
                    "-c",
                    "user.email=walksafe@example.invalid",
                    "commit",
                    "--quiet",
                    "-m",
                    "fixture",
                ],
                cwd=repository,
                check=True,
            )
            head = subprocess.run(
                ["git", "rev-parse", "HEAD"],
                cwd=repository,
                check=True,
                capture_output=True,
                text=True,
            ).stdout.strip()
            controlled_relative = Path(
                "evidence/focused-android-tests-resumed.log"
            )
            unrelated_relative = Path("evidence/unrelated-ignored.log")
            controlled = repository / controlled_relative
            controlled.parent.mkdir(parents=True)
            controlled.write_bytes(b"authorized ignored evidence\n")
            (repository / unrelated_relative).write_bytes(
                b"unrelated ignored evidence\n"
            )
            bound_event_relative = gate.GATE_ROOT_RELATIVE / (
                "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP099-20260809-001"
            )
            bound_receipt_relative = bound_event_relative / (
                "implementation-start-gate-receipt.json"
            )
            bound_log_relative = bound_event_relative / "01-CONTINUATION.log"
            unbound_event_relative = gate.GATE_ROOT_RELATIVE / (
                "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP099-20260809-002"
            )
            (repository / bound_event_relative).mkdir(parents=True)
            (repository / bound_receipt_relative).write_bytes(b"bound receipt\n")
            (repository / bound_log_relative).write_bytes(b"bound log\n")
            (repository / unbound_event_relative).mkdir(parents=True)
            (repository / unbound_event_relative / "01-CONTINUATION.log").write_bytes(
                b"unbound log\n"
            )
            managed_paths = [controlled_relative.as_posix()]
            path_hash, content_hash = continuation.working_snapshot_hashes(
                repository,
                managed_paths,
            )
            checkpoint = {
                "working_tree_snapshot": {
                    "base_head": head,
                    "managed_changed_path_count": len(managed_paths),
                    "managed_changed_paths": managed_paths,
                    "path_set_sha256": path_hash,
                    "content_set_sha256": content_hash,
                },
                "session_handoff": {
                    "source_commit_or_snapshot": {"current_head": head}
                },
                "goal_execution": {
                    "transition_history": [
                        {
                            "implementation_start_gate_binding": {
                                "path": bound_receipt_relative.as_posix()
                            }
                        }
                    ]
                },
            }
            checkpoint_path = repository / gate.CHECKPOINT_RELATIVE
            checkpoint_path.parent.mkdir(parents=True, exist_ok=True)
            checkpoint_path.write_text(
                json.dumps(checkpoint, sort_keys=True) + "\n",
                encoding="utf-8",
            )
            checkpoint_path.chmod(0o600)

            repository_guard = gate.RetainedRepositoryAuthorityGuard.capture(
                repository
            )
            authority = None
            try:
                authority = gate._RepositoryStateAuthority.capture(
                    repository_guard,
                    checkpoint_path,
                    EVENT_ID,
                    gate.capture_repository_state,
                )
                self.assertEqual(
                    authority.authorized_controlled_paths,
                    (controlled_relative,),
                )
                self.assertIsNotNone(authority.authorized_gate_evidence)
                self.assertEqual(
                    authority.authorized_gate_evidence.paths,
                    tuple(sorted((bound_log_relative, bound_receipt_relative))),
                )
                snapshot = gate._RetainedIsolatedRepositorySnapshot.capture(
                    repository,
                    EVENT_ID,
                    authority,
                    repository_guard,
                    gate.capture_repository_state,
                )
                try:
                    self.assertEqual(
                        (snapshot.root / controlled_relative).read_bytes(),
                        controlled.read_bytes(),
                    )
                    self.assertFalse(
                        (snapshot.root / unrelated_relative).exists()
                    )
                    self.assertEqual(
                        (snapshot.root / bound_receipt_relative).read_bytes(),
                        b"bound receipt\n",
                    )
                    self.assertEqual(
                        (snapshot.root / bound_log_relative).read_bytes(),
                        b"bound log\n",
                    )
                    self.assertFalse(
                        (snapshot.root / unbound_event_relative).exists()
                    )
                    snapshot.verify_repository()
                finally:
                    snapshot.close()

                capture_source_guard = gate._RetainedSourceGuard.capture

                def capture_after_pre_pin_drift(
                    root: Path,
                    relatives: object,
                    **kwargs: object,
                ) -> object:
                    if root != repository:
                        (root / bound_receipt_relative).write_bytes(
                            b"evil! receipt\n"
                        )
                        (root / bound_event_relative / "injected.log").write_bytes(
                            b"injected\n"
                        )
                    return capture_source_guard(root, relatives, **kwargs)

                with mock.patch.object(
                    gate._RetainedSourceGuard,
                    "capture",
                    side_effect=capture_after_pre_pin_drift,
                ):
                    with self.assertRaisesRegex(
                        gate.GateError,
                        "isolated gate evidence",
                    ):
                        gate._RetainedIsolatedRepositorySnapshot.capture(
                            repository,
                            EVENT_ID,
                            authority,
                            repository_guard,
                            gate.capture_repository_state,
                        )

                drifted_snapshot = (
                    gate._RetainedIsolatedRepositorySnapshot.capture(
                        repository,
                        EVENT_ID,
                        authority,
                        repository_guard,
                        gate.capture_repository_state,
                    )
                )
                try:
                    (drifted_snapshot.root / bound_receipt_relative).write_bytes(
                        b"evil! receipt\n"
                    )
                    with self.assertRaises(gate.GateError):
                        drifted_snapshot.verify_repository()
                finally:
                    drifted_snapshot.close(
                        RuntimeError("expected isolated evidence drift")
                    )

                copy_regular_file = gate._copy_snapshot_regular_file

                def omit_controlled_file(source: Path, destination: Path) -> None:
                    if source == controlled:
                        return
                    copy_regular_file(source, destination)

                with mock.patch.object(
                    gate,
                    "_copy_snapshot_regular_file",
                    side_effect=omit_controlled_file,
                ):
                    with self.assertRaisesRegex(
                        ValueError,
                        "controlled working snapshot cannot be reproduced",
                    ):
                        gate._RetainedIsolatedRepositorySnapshot.capture(
                            repository,
                            EVENT_ID,
                            authority,
                            repository_guard,
                            gate.capture_repository_state,
                        )
            finally:
                if authority is not None:
                    authority.close()
                repository_guard.close()

    def test_checkpoint_bound_gate_evidence_rejects_missing_and_unsafe_entries(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as repository_directory:
            repository = Path(repository_directory)
            event_relative = gate.GATE_ROOT_RELATIVE / (
                "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP099-20260809-003"
            )
            event_directory = repository / event_relative
            event_directory.mkdir(parents=True)
            (event_directory / "other.log").write_bytes(b"other\n")
            missing = event_relative / "implementation-start-gate-receipt.json"
            checkpoint_bytes = json.dumps(
                {"binding": {"path": missing.as_posix()}}
            ).encode("utf-8")
            with self.assertRaisesRegex(gate.GateError, "file is missing"):
                gate._RetainedCheckpointGateEvidence.capture(
                    repository,
                    checkpoint_bytes,
                )

            nested = event_relative / "nested" / "receipt.json"
            nested_checkpoint = json.dumps(
                {"binding": {"path": nested.as_posix()}}
            ).encode("utf-8")
            with self.assertRaisesRegex(gate.GateError, "outside an event"):
                gate._RetainedCheckpointGateEvidence.capture(
                    repository,
                    nested_checkpoint,
                )

            (event_directory / missing.name).write_bytes(b"receipt\n")
            (event_directory / "unsafe-directory").mkdir()
            with self.assertRaisesRegex(
                gate.GateError,
                "gate evidence is unsafe",
            ):
                gate._RetainedCheckpointGateEvidence.capture(
                    repository,
                    checkpoint_bytes,
                )

    def test_checkpoint_bound_gate_evidence_detects_post_capture_drift(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as repository_directory:
            repository = Path(repository_directory)
            event_relative = gate.GATE_ROOT_RELATIVE / (
                "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP099-20260809-004"
            )
            receipt_relative = event_relative / (
                "implementation-start-gate-receipt.json"
            )
            event_directory = repository / event_relative
            event_directory.mkdir(parents=True)
            receipt = repository / receipt_relative
            receipt.write_bytes(b"receipt\n")
            checkpoint_bytes = json.dumps(
                {"binding": {"path": receipt_relative.as_posix()}}
            ).encode("utf-8")
            evidence = gate._RetainedCheckpointGateEvidence.capture(
                repository,
                checkpoint_bytes,
            )
            replacement = event_directory / ".replacement"
            replacement.write_bytes(receipt.read_bytes())
            os.replace(replacement, receipt)
            try:
                with self.assertRaises(gate.GateError):
                    evidence.verify()
            finally:
                evidence.close(RuntimeError("expected test drift"))

    def test_retained_source_limits_before_second_read_and_closes_failed_fd(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as repository_directory:
            repository = Path(repository_directory)
            first = Path("first.bin")
            second = Path("second.bin")
            (repository / first).write_bytes(b"a" * 6)
            (repository / second).write_bytes(b"b" * 6)
            with mock.patch.object(
                gate,
                "_pread_all",
                wraps=gate._pread_all,
            ) as pread_all:
                with self.assertRaisesRegex(
                    gate.GateError,
                    "retained source authority differs",
                ):
                    gate._RetainedSourceGuard.capture(
                        repository,
                        (first, second),
                        maximum_bytes=10,
                        total_maximum_bytes=10,
                    )
                self.assertEqual(pread_all.call_count, 1)

            descriptor_count = len(os.listdir("/proc/self/fd"))
            with mock.patch.object(
                gate,
                "_pread_all",
                side_effect=gate.GateError("injected read failure"),
            ):
                with self.assertRaisesRegex(
                    gate.GateError,
                    "injected read failure",
                ):
                    gate._RetainedSourceGuard.capture(repository, (first,))
            self.assertEqual(
                len(os.listdir("/proc/self/fd")),
                descriptor_count,
            )

    def test_snapshot_bind_closes_root_fd_when_namespace_capture_fails(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            workspace = Path(directory).resolve()
            snapshots = workspace / "snapshots"
            container = snapshots / "repository-001"
            snapshot_root = container / "repository"
            live_root = workspace / "live"
            snapshot_root.mkdir(parents=True)
            live_root.mkdir()
            (snapshot_root / ".git").mkdir()
            os.chmod(container, 0o700)
            os.chmod(snapshot_root, 0o700)
            repository_guard = mock.Mock()
            repository_guard.workspace = workspace
            authority = gate._RepositoryStateAuthority.from_payload(
                {},
                strict_cli_output=True,
            )
            descriptor_count = len(os.listdir("/proc/self/fd"))
            with mock.patch.object(
                gate.RetainedRepositoryAuthorityGuard,
                "capture",
                side_effect=gate.GateError("injected namespace failure"),
            ):
                with self.assertRaisesRegex(
                    gate.GateError,
                    "injected namespace failure",
                ):
                    gate._RetainedIsolatedRepositorySnapshot.bind(
                        live_root,
                        container,
                        snapshot_root,
                        authority,
                        repository_guard,
                        EVENT_ID,
                        gate.capture_repository_state,
                    )
            self.assertEqual(
                len(os.listdir("/proc/self/fd")),
                descriptor_count,
            )

    def test_commit_guard_checkpoint_drift_is_caught_after_equal_repository_state(self) -> None:
        def mutate_checkpoint_but_return_equal_repository(
            _root: Path,
            _checkpoint: Path,
            event_id: str,
        ) -> dict[str, object]:
            drifted = copy.deepcopy(self.checkpoint)
            drifted["summary_preserving_guard_drift"] = True
            self._write_checkpoint(drifted)
            return repository_payload(event_id)

        runner = FakeRunner()
        with self.assertRaisesRegex(
            gate.GateError,
            "source changed during repository commit guard",
        ):
            self._run(
                runner,
                repository_state_guard=(
                    mutate_checkpoint_but_return_equal_repository
                ),
            )
        event_dir = self.root / gate.GATE_ROOT_RELATIVE / EVENT_ID
        self.assertEqual(len(list(event_dir.glob("*.log"))), 9)
        self.assertFalse((event_dir / gate.RECEIPT_NAME).exists())

    def test_receipt_short_writes_publish_only_complete_final_json(self) -> None:
        def short_writer(descriptor: int, content: bytes) -> int:
            return os.write(descriptor, content[: min(17, len(content))])

        receipt_path = self._run(
            FakeRunner(empty_indices={3}),
            receipt_writer=short_writer,
        )
        receipt = json.loads(receipt_path.read_bytes())
        self.assertEqual(receipt["status"], "PASS")
        self.assertFalse((receipt_path.parent / gate.RECEIPT_STAGE_NAME).exists())
        self.assertEqual(receipt_path.stat().st_nlink, 1)
        self.assertEqual(stat.S_IMODE(receipt_path.stat().st_mode), 0o600)

    def test_receipt_write_failure_never_exposes_partial_final_name(self) -> None:
        calls = 0

        def failing_writer(descriptor: int, content: bytes) -> int:
            nonlocal calls
            calls += 1
            if calls == 1:
                return os.write(descriptor, content[: min(23, len(content))])
            raise OSError("injected receipt write failure")

        with self.assertRaisesRegex(gate.GateError, "staging write failed"):
            self._run(FakeRunner(), receipt_writer=failing_writer)
        event_dir = self.root / gate.GATE_ROOT_RELATIVE / EVENT_ID
        self.assertFalse((event_dir / gate.RECEIPT_NAME).exists())
        stage = event_dir / gate.RECEIPT_STAGE_NAME
        self.assertTrue(stage.is_file())
        self.assertGreater(stage.stat().st_size, 0)
        self.assertEqual(stat.S_IMODE(stage.stat().st_mode), 0o600)
        self.assertEqual(stage.stat().st_nlink, 1)

    def test_receipt_fsync_failure_never_exposes_final_name(self) -> None:
        def failing_fsync(_descriptor: int) -> None:
            raise OSError("injected receipt fsync failure")

        with self.assertRaisesRegex(gate.GateError, "staging fsync failed"):
            self._run(FakeRunner(), receipt_file_fsync=failing_fsync)
        event_dir = self.root / gate.GATE_ROOT_RELATIVE / EVENT_ID
        self.assertFalse((event_dir / gate.RECEIPT_NAME).exists())
        stage = event_dir / gate.RECEIPT_STAGE_NAME
        staged_receipt = json.loads(stage.read_bytes())
        self.assertEqual(staged_receipt["status"], "PASS")
        self.assertEqual(stat.S_IMODE(stage.stat().st_mode), 0o600)
        self.assertEqual(stage.stat().st_nlink, 1)

    def test_receipt_publish_never_replaces_a_racing_final_name(self) -> None:
        created = False
        sentinel = b"FOREIGN-FINAL-SENTINEL\n"

        def colliding_writer(descriptor: int, content: bytes) -> int:
            nonlocal created
            if not created:
                created = True
                final = (
                    self.root
                    / gate.GATE_ROOT_RELATIVE
                    / EVENT_ID
                    / gate.RECEIPT_NAME
                )
                final.write_bytes(sentinel)
            return os.write(descriptor, content)

        with self.assertRaises(FileExistsError):
            self._run(FakeRunner(), receipt_writer=colliding_writer)
        event_dir = self.root / gate.GATE_ROOT_RELATIVE / EVENT_ID
        self.assertEqual((event_dir / gate.RECEIPT_NAME).read_bytes(), sentinel)
        self.assertTrue((event_dir / gate.RECEIPT_STAGE_NAME).is_file())

    def test_receipt_writer_repository_drift_is_caught_before_atomic_publish(
        self,
    ) -> None:
        marker = self.root / "ordinary-repository-drift.txt"
        guard_calls = 0

        def stateful_repository_guard(
            _root: Path,
            _checkpoint: Path,
            event_id: str,
        ) -> dict[str, object]:
            nonlocal guard_calls
            guard_calls += 1
            payload = repository_payload(event_id)
            if marker.exists():
                payload["dirty_snapshot"]["dirty_path_count"] = 3  # type: ignore[index]
            return payload

        def drifting_writer(descriptor: int, content: bytes) -> int:
            if not marker.exists():
                marker.write_text("drift\n", encoding="utf-8")
            return os.write(descriptor, content)

        with self.assertRaisesRegex(
            gate.GateError,
            "repository changed before receipt publication",
        ):
            self._run(
                FakeRunner(),
                repository_state_guard=stateful_repository_guard,
                receipt_writer=drifting_writer,
            )
        event_dir = self.root / gate.GATE_ROOT_RELATIVE / EVENT_ID
        self.assertEqual(guard_calls, 2)
        self.assertFalse((event_dir / gate.RECEIPT_NAME).exists())
        staged_receipt = json.loads(
            (event_dir / gate.RECEIPT_STAGE_NAME).read_bytes()
        )
        self.assertEqual(staged_receipt["status"], "PASS")

    def test_receipt_writer_runtime_binding_drift_is_caught_before_publish(
        self,
    ) -> None:
        wrapper_properties = (
            self.root
            / "apps/android/gradle/wrapper/gradle-wrapper.properties"
        )
        mutated = False

        def runtime_mutating_writer(descriptor: int, content: bytes) -> int:
            nonlocal mutated
            if not mutated:
                mutated = True
                wrapper_properties.write_bytes(
                    wrapper_properties.read_bytes() + b"# injected drift\n"
                )
            return os.write(descriptor, content)

        with self.assertRaisesRegex(
            gate.GateError,
            "source changed before receipt publication",
        ):
            self._run(
                FakeRunner(),
                receipt_writer=runtime_mutating_writer,
            )
        event_dir = self.root / gate.GATE_ROOT_RELATIVE / EVENT_ID
        self.assertFalse((event_dir / gate.RECEIPT_NAME).exists())
        self.assertEqual(
            json.loads((event_dir / gate.RECEIPT_STAGE_NAME).read_bytes())[
                "status"
            ],
            "PASS",
        )

    def test_postrename_directory_fsync_failure_is_explicit_and_keeps_final(
        self,
    ) -> None:
        def failing_directory_fsync(_descriptor: int) -> None:
            raise OSError("injected post-rename directory fsync failure")

        with self.assertRaisesRegex(
            gate.GatePostCommitUncertain,
            "directory durability or verification is uncertain",
        ):
            self._run(
                FakeRunner(empty_indices={3}),
                receipt_directory_fsync=failing_directory_fsync,
            )
        event_dir = self.root / gate.GATE_ROOT_RELATIVE / EVENT_ID
        receipt_path = event_dir / gate.RECEIPT_NAME
        self.assertEqual(json.loads(receipt_path.read_bytes())["status"], "PASS")
        self.assertFalse((event_dir / gate.RECEIPT_STAGE_NAME).exists())
        self.assertEqual(stat.S_IMODE(receipt_path.stat().st_mode), 0o600)
        self.assertEqual(receipt_path.stat().st_nlink, 1)

    def test_checkpoint_parent_exclusive_lock_blocks_before_attempt_mutation(
        self,
    ) -> None:
        checkpoint_parent = self.root / gate.CHECKPOINT_RELATIVE.parent
        descriptor = os.open(
            checkpoint_parent,
            os.O_RDONLY | getattr(os, "O_DIRECTORY", 0),
        )
        runner = FakeRunner()
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with self.assertRaisesRegex(
                gate.GateError,
                "checkpoint parent lock unavailable",
            ):
                self._run(runner)
        finally:
            fcntl.flock(descriptor, fcntl.LOCK_UN)
            os.close(descriptor)
        self.assertEqual(runner.calls, [])
        self.assertFalse(
            os.path.lexists(self.root / gate.GATE_ROOT_RELATIVE / EVENT_ID)
        )

    def test_directory_swap_and_runner_exception_fail_closed(self) -> None:
        event_dir = self.root / gate.GATE_ROOT_RELATIVE / EVENT_ID
        held_dir = event_dir.with_name(EVENT_ID + "-held")

        def swap_after_first_check(index: int) -> None:
            if index == 1:
                event_dir.rename(held_dir)
                event_dir.mkdir(mode=0o700)

        swap_runner = FakeRunner(after_call=swap_after_first_check)
        with self.assertRaisesRegex(gate.GateError, "identity changed"):
            self._run(swap_runner)
        self.assertTrue((held_dir / "01-CONTINUATION.log").exists())
        self.assertFalse((event_dir / "implementation-start-gate-receipt.json").exists())

        exception_event = "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP008-20260803-002"
        exception_runner = FakeRunner(raise_index=2)
        with self.assertRaisesRegex(gate.GateError, "runner raised"):
            self._run(exception_runner, event_id=exception_event)
        exception_dir = self.root / gate.GATE_ROOT_RELATIVE / exception_event
        self.assertTrue((exception_dir / "02-V24_ARTIFACT_WORK_QUEUE.log").exists())
        self.assertFalse(
            (exception_dir / "implementation-start-gate-receipt.json").exists()
        )

    def test_naive_clock_fails_closed_and_preserves_unique_attempt(self) -> None:
        runner = FakeRunner()
        synthetic_ready_sha256 = self.checkpoint["goal_execution"][
            "transition_history"
        ][-1]["event_sha256"]  # type: ignore[index]
        with (
            mock.patch.object(
                gate,
                "SOURCE_READY_EVENT_SHA256",
                synthetic_ready_sha256,
            ),
            self.assertRaisesRegex(gate.GateError, "timezone-aware"),
        ):
            gate.run_gate(
                self.root,
                EVENT_ID,
                process_runner=runner,
                clock=lambda: datetime(2026, 8, 3, 14, 0),
            )
        event_dir = self.root / gate.GATE_ROOT_RELATIVE / EVENT_ID
        self.assertTrue(event_dir.is_dir())
        self.assertEqual(list(event_dir.iterdir()), [])
        self.assertEqual(runner.calls, [])


if __name__ == "__main__":
    unittest.main()

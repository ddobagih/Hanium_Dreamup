from __future__ import annotations

from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

from scripts import run_walksafe_fp048_goal_start_gate_20260802 as gate


ROOT = Path(__file__).resolve().parents[1]
EVENT_ID = "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-20260802-001"


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def repository_payload(event_id: str) -> dict[str, object]:
    digest = "1" * 64
    return {
        "schema_version": "1.0.0",
        "evidence_type": "GATE_REPOSITORY_STATE",
        "gate_event_id": event_id,
        "snapshot_scope": (
            "COMPLETE_GIT_VISIBLE_DIRTY_STATE_EXCLUDING_CHECKPOINT_AND_"
            "CURRENT_GATE_EVENT"
        ),
        "repository": {
            "root": ".",
            "head_commit": "a" * 40,
            "branch": "test/fp048-gate",
            "object_format": "sha1",
        },
        "git_status_raw": {
            "sha256": digest,
            "byte_count": 10,
            "record_count": 1,
        },
        "dirty_snapshot": {
            "dirty_path_count": 1,
            "path_set_sha256": "2" * 64,
            "content_set_sha256": "3" * 64,
            "index_state_sha256": "4" * 64,
        },
        "checkpoint_controlled_working_snapshot": {
            "base_head": "a" * 40,
            "managed_changed_path_count": 1,
            "path_set_sha256": "5" * 64,
            "content_set_sha256": "6" * 64,
        },
    }


class FakeRunner:
    def __init__(self, *, fail_index: int | None = None) -> None:
        self.fail_index = fail_index
        self.calls: list[dict[str, object]] = []

    def __call__(self, command: str, **kwargs: object) -> subprocess.CompletedProcess[str]:
        index = len(self.calls) + 1
        self.calls.append({"command": command, **kwargs})
        output = kwargs["stdout"]
        if "--print-gate-repository-state" in command:
            assert hasattr(output, "write")
            output.write(
                (json.dumps(repository_payload(EVENT_ID)) + "\n").encode(
                    "utf-8"
                )
            )
        else:
            assert hasattr(output, "write")
            output.write(f"check {index}\n".encode("utf-8"))
        return subprocess.CompletedProcess(
            args=command,
            returncode=9 if index == self.fail_index else 0,
        )


class WalkSafeFp048GoalStartGateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self._copy(
            gate.MANIFEST_RELATIVE,
            ROOT / gate.MANIFEST_RELATIVE,
        )
        self._copy(
            gate.TOOLCHAIN_LOCK_RELATIVE,
            ROOT / gate.TOOLCHAIN_LOCK_RELATIVE,
        )
        goal_relative = Path(
            "docs/control/goals/walksafe-completion-graph-v2-4/"
            "work-items/epic-03/epic-03-fp048-at-rest-encryption-r001.md"
        )
        goal_bytes = b"# FP-048 test Goal\n"
        self._write(goal_relative, goal_bytes)
        checkpoint = {
            "goal_execution": {
                "package_id": gate.PACKAGE_ID,
                "package_status": "ACTIVE",
                "activation_status": "ACTIVE",
                "focus_goal_id": gate.TARGET_GOAL_ID,
                "status_by_goal": {gate.TARGET_GOAL_ID: "READY"},
                "static_plan_manifest_path": gate.MANIFEST_RELATIVE.as_posix(),
                "static_plan_manifest_sha256": gate.MANIFEST_SHA256,
                "dynamic_goal_inventory": {
                    gate.TARGET_GOAL_ID: {
                        "path": goal_relative.as_posix(),
                        "sha256": sha256_bytes(goal_bytes),
                    }
                },
                "transition_history": [
                    {
                        "event_type": "PACKAGE_ACTIVATED",
                        "static_plan_manifest_sha256": gate.MANIFEST_SHA256,
                        "event_sha256": "7" * 64,
                    }
                ],
            }
        }
        self._write(
            gate.CHECKPOINT_RELATIVE,
            (json.dumps(checkpoint) + "\n").encode("utf-8"),
        )
        (self.root / gate.GATE_ROOT_RELATIVE).mkdir(parents=True)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _write(self, relative: Path, value: bytes) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(value)

    def _copy(self, relative: Path, source: Path) -> None:
        self._write(relative, source.read_bytes())

    @staticmethod
    def _clock() -> datetime:
        return datetime(
            2026,
            8,
            2,
            1,
            0,
            tzinfo=timezone(timedelta(hours=9)),
        )

    def test_success_uses_exact_contract_and_writes_pass_receipt(self) -> None:
        context = gate.load_gate_context(self.root)
        runner = FakeRunner()
        with mock.patch.dict(
            os.environ,
            {
                "WALKSAFE_NODE_BIN_DIR": "/untrusted/node/bin",
                "WALKSAFE_LOCKED_TEST_PYTHON": "/untrusted/python",
            },
            clear=True,
        ):
            receipt_path = gate.run_gate(
                self.root,
                EVENT_ID,
                process_runner=runner,
                clock=self._clock,
            )

        self.assertEqual(
            tuple(check_id for check_id, _ in context.checks),
            gate.EXPECTED_CHECK_IDS,
        )
        self.assertEqual(len(runner.calls), 19)
        self.assertEqual(
            [call["command"] for call in runner.calls],
            [command for _, command in context.checks],
        )
        for call in runner.calls:
            environment = call["env"]
            self.assertEqual(
                environment["WALKSAFE_NODE_BIN_DIR"],
                gate.PINNED_NODE_BIN_DIR,
            )
            self.assertEqual(
                environment["WALKSAFE_LOCKED_TEST_PYTHON"],
                gate.LOCKED_TEST_PYTHON,
            )
            self.assertEqual(environment["WALKSAFE_GATE_EVENT_ID"], EVENT_ID)
            self.assertEqual(call["executable"], "/bin/bash")
            self.assertTrue(call["shell"])
        receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
        self.assertEqual(set(receipt), gate.RECEIPT_FIELDS)
        self.assertEqual(receipt["status"], "PASS")
        self.assertEqual(receipt["gate_purpose"], "INITIAL_START")
        self.assertEqual(receipt["target_goal_id"], gate.TARGET_GOAL_ID)
        self.assertEqual(receipt["target_transition_event_id"], EVENT_ID)
        self.assertEqual(receipt["check_command_contract_sha256"], gate.CONTRACT_SHA256)
        self.assertEqual(len(receipt["check_runs"]), 19)
        self.assertEqual(
            datetime.fromisoformat(
                receipt["execution_window"]["started_at"]
            ).microsecond,
            0,
        )
        self.assertEqual(
            datetime.fromisoformat(
                receipt["execution_window"]["ended_at"]
            ).microsecond,
            0,
        )
        self.assertEqual(
            datetime.fromisoformat(receipt["generated_at"]).microsecond,
            0,
        )
        self.assertEqual(
            [run["output_path"].rsplit("/", 1)[1] for run in receipt["check_runs"]],
            [
                f"{index:02d}-{check_id}.log"
                for index, check_id in enumerate(gate.EXPECTED_CHECK_IDS, start=1)
            ],
        )
        executed = [
            datetime.fromisoformat(run["executed_at"])
            for run in receipt["check_runs"]
        ]
        self.assertEqual(executed, sorted(executed))
        self.assertEqual(len(executed), len(set(executed)))
        repository_log = receipt_path.parent / "19-REPOSITORY_STATE.log"
        self.assertEqual(
            receipt["repository_snapshot"][
                "gate_repository_state_output_sha256"
            ],
            gate.sha256_file(repository_log),
        )

    def test_failed_check_stops_without_pass_receipt(self) -> None:
        runner = FakeRunner(fail_index=4)
        with self.assertRaises(gate.GateCheckFailed) as raised:
            gate.run_gate(
                self.root,
                EVENT_ID,
                process_runner=runner,
                clock=self._clock,
            )
        event_dir = self.root / gate.GATE_ROOT_RELATIVE / EVENT_ID
        self.assertEqual(raised.exception.exit_code, 9)
        self.assertEqual(len(runner.calls), 4)
        self.assertTrue((event_dir / "04-ANDROID_GATEWAY_BOUNDARY.log").is_file())
        self.assertFalse((event_dir / "05-NODE_TOOLCHAIN_PRE.log").exists())
        self.assertFalse(
            (event_dir / "implementation-start-gate-receipt.json").exists()
        )

    def test_existing_event_directory_is_never_reused(self) -> None:
        event_dir = self.root / gate.GATE_ROOT_RELATIVE / EVENT_ID
        event_dir.mkdir()
        sentinel = event_dir / "sentinel.txt"
        sentinel.write_text("keep\n", encoding="utf-8")
        runner = FakeRunner()
        with self.assertRaisesRegex(gate.GateError, "already exists"):
            gate.run_gate(
                self.root,
                EVENT_ID,
                process_runner=runner,
                clock=self._clock,
            )
        self.assertEqual(runner.calls, [])
        self.assertEqual(sentinel.read_text(encoding="utf-8"), "keep\n")


if __name__ == "__main__":
    unittest.main()

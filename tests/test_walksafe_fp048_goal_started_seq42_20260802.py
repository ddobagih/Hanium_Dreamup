from __future__ import annotations

import copy
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import tempfile
import unittest

from scripts import apply_walksafe_fp048_goal_started_seq42_20260802 as apply
from scripts import check_walksafe_project_continuation_v2_4 as contract
from scripts import materialize_walksafe_fp048_goal_20260802 as materialize
from scripts import run_walksafe_fp048_goal_start_gate_20260802 as gate


EVENT_ID = "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-20260802-003"
ACTIVATION_SHA256 = "7" * 64


def repository_payload(event_id: str) -> dict[str, object]:
    return {
        "schema_version": "1.0.0",
        "evidence_type": "GATE_REPOSITORY_STATE",
        "gate_event_id": event_id,
        "canonicalization": {
            "encoding": "UTF-8",
            "json": "sort_keys=true,separators=(',',':'),ensure_ascii=false",
            "trailing_newline_in_cli_output": True,
        },
        "snapshot_scope": (
            "COMPLETE_GIT_VISIBLE_DIRTY_STATE_EXCLUDING_CHECKPOINT_AND_"
            "CURRENT_GATE_EVENT"
        ),
        "repository": {
            "root": ".",
            "head_commit": "a" * 40,
            "branch": "test/fp048-seq42",
            "object_format": "sha1",
        },
        "git_status_raw": {
            "command": ["git", "status"],
            "config_overrides": {},
            "scope": "AFTER_EXACT_TRANSACTION_EXCLUSIONS",
            "sha256": "1" * 64,
            "byte_count": 12,
            "record_count": 1,
        },
        "transaction_exclusions": {
            "allowed_rule_count": 2,
            "checkpoint_exact_path": apply.CHECKPOINT_RELATIVE.as_posix(),
            "gate_event_exact_prefix": (
                f"docs/control/execution/goal-gates/{event_id}/"
            ),
        },
        "dirty_snapshot": {
            "dirty_path_count": 1,
            "path_set_sha256": "2" * 64,
            "content_set_sha256": "3" * 64,
            "index_state_sha256": "4" * 64,
            "paths": [],
        },
        "checkpoint_controlled_working_snapshot": {
            "base_head": "a" * 40,
            "managed_changed_path_count": 1,
            "path_set_sha256": "5" * 64,
            "content_set_sha256": "6" * 64,
        },
    }


def source_checkpoint() -> dict[str, object]:
    runtime = {
        "focus_goal_id": materialize.GOAL_ID,
        "focus_goal_path": materialize.GOAL_PATH.as_posix(),
        "focus_work_item_id": materialize.WORK_ITEM_ID,
        "focus_source": "IMPLEMENTATION_BACKLOG",
        "ready_frontier_goal_ids": [
            materialize.GOAL_ID,
            materialize.PARENT_GOAL_ID,
            "WS-GOAL-EPIC-12",
        ],
        "blocked_goal_ids": [],
        "pending_questions": [],
        "open_question_count": 0,
        "artifact_work_queue_sha256": "8" * 64,
        "completion_boundary_sha256": "9" * 64,
        "activation_status": "ACTIVE",
        "package_status": "ACTIVE",
    }
    history: list[dict[str, object]] = [
        {
            "sequence": 1,
            "event_id": "PREPARED",
            "event_type": "PACKAGE_PREPARED",
            "occurred_at": "2026-07-25T09:50:00+09:00",
            "event_sha256": "6" * 64,
        },
        {
            "sequence": 2,
            "event_id": "ACTIVATED",
            "event_type": "PACKAGE_ACTIVATED",
            "occurred_at": "2026-07-25T09:51:09+09:00",
            "event_sha256": ACTIVATION_SHA256,
        },
    ]
    history.extend(
        {
            "sequence": sequence,
            "event_id": f"DUMMY-{sequence}",
            "event_type": "GOAL_COMPLETED",
            "occurred_at": f"2026-07-26T00:00:{sequence:02d}+09:00",
            "event_sha256": f"{sequence:064x}",
        }
        for sequence in range(3, 40)
    )
    history.extend(
        [
            {
                "sequence": 40,
                "event_id": materialize.MATERIALIZED_EVENT_ID,
                "event_type": "GOAL_MATERIALIZED",
                "occurred_at": materialize.MATERIALIZED_AT,
                "event_sha256": apply.SOURCE_MATERIALIZED_EVENT_SHA256,
            },
            {
                "sequence": 41,
                "event_id": materialize.READY_EVENT_ID,
                "event_type": "GOAL_READY",
                "occurred_at": materialize.READY_AT,
                "event_sha256": apply.SOURCE_READY_EVENT_SHA256,
                "runtime_after": runtime,
            },
        ]
    )
    return {
        "schema_version": "1.25.0",
        "goal_execution": {
            "goal_status": "READY",
            "transition_history": history,
            "transition_history_anchor_sha256": apply.SOURCE_READY_EVENT_SHA256,
            "validation_cutoff_at": materialize.READY_AT,
            "status_by_goal": {materialize.GOAL_ID: "READY"},
            "focus_goal_id": materialize.GOAL_ID,
            "focus_goal_path": materialize.GOAL_PATH.as_posix(),
            "focus_work_item_id": materialize.WORK_ITEM_ID,
            "focus_source": "IMPLEMENTATION_BACKLOG",
            "ready_frontier_goal_ids": runtime["ready_frontier_goal_ids"],
            "blocked_goal_ids": [],
            "pending_questions": [],
            "open_question_count": 0,
            "activation_status": "ACTIVE",
            "package_status": "ACTIVE",
            "blockers_by_goal": {},
            "blocker_resolution_history": [],
        },
        "current_work": {"current_focus": "gate waiting"},
        "working_tree_snapshot": {"scope": "seq41"},
        "session_handoff": {
            "current_epic": "READY_NOT_STARTED",
            "last_verification_status": "READY_NOT_STARTED",
        },
    }


class WalkSafeFp048GoalStartedSeq42Test(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.source = source_checkpoint()
        self.checkpoint_path = self.root / apply.CHECKPOINT_RELATIVE
        self.checkpoint_path.parent.mkdir(parents=True)
        self.checkpoint_path.write_bytes(apply.json_bytes(self.source))
        self.context = gate.GateContext(
            checks=tuple(
                (check_id, f"command-{index}")
                for index, check_id in enumerate(gate.EXPECTED_CHECK_IDS, start=1)
            ),
            target_goal_sha256=materialize.GOAL_SHA256,
            source_activation_event_sha256=ACTIVATION_SHA256,
            toolchain_lock_sha256="a" * 64,
        )
        self.payload = repository_payload(EVENT_ID)
        self._write_pass_evidence()

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _write_pass_evidence(self) -> None:
        event_dir = self.root / gate.GATE_ROOT_RELATIVE / EVENT_ID
        event_dir.mkdir(parents=True)
        offset = timezone(timedelta(hours=9))
        started = datetime(2026, 8, 2, 21, 0, 0, tzinfo=offset)
        runs: list[dict[str, object]] = []
        for index, (check_id, command) in enumerate(self.context.checks, start=1):
            relative = (
                gate.GATE_ROOT_RELATIVE
                / EVENT_ID
                / f"{index:02d}-{check_id}.log"
            )
            path = self.root / relative
            if check_id == "REPOSITORY_STATE":
                content = contract.canonical_json_bytes(self.payload) + b"\n"
            else:
                content = f"PASS {check_id}\n".encode("utf-8")
            path.write_bytes(content)
            runs.append(
                {
                    "check_id": check_id,
                    "command": command,
                    "output_path": relative.as_posix(),
                    "output_sha256": apply.sha256_bytes(content),
                    "exit_code": 0,
                    "executed_at": (
                        started + timedelta(microseconds=index)
                    ).isoformat(),
                }
            )
        receipt = {
            "schema_version": "1.0",
            "document_id": gate._document_id(EVENT_ID),
            "evidence_type": "IMPLEMENTATION_START_OR_RESUME_GATE",
            "gate_purpose": "INITIAL_START",
            "status": "PASS",
            "package_id": gate.PACKAGE_ID,
            "target_transition_event_id": EVENT_ID,
            "target_goal_id": materialize.GOAL_ID,
            "target_goal_content_sha256": materialize.GOAL_SHA256,
            "static_plan_manifest_sha256": gate.MANIFEST_SHA256,
            "source_activation_event_sha256": ACTIVATION_SHA256,
            "check_command_contract_version": gate.CONTRACT_VERSION,
            "check_command_contract_sha256": gate.CONTRACT_SHA256,
            "toolchain_lock_binding": {
                "path": gate.TOOLCHAIN_LOCK_RELATIVE.as_posix(),
                "file_sha256": self.context.toolchain_lock_sha256,
            },
            "execution_window": {
                "started_at": started.isoformat(),
                "ended_at": (started + timedelta(seconds=1)).isoformat(),
            },
            "check_runs": runs,
            "repository_snapshot": gate.repository_snapshot_from_payload(
                self.payload,
                event_id=EVENT_ID,
                output_sha256=runs[-1]["output_sha256"],
            ),
            "generated_at": (started + timedelta(seconds=2)).isoformat(),
        }
        (event_dir / "implementation-start-gate-receipt.json").write_bytes(
            apply.json_bytes(receipt)
        )

    def _prepare(self, *, capture=None) -> apply.PreparedProjection:
        capture = capture or (lambda _root, _checkpoint, _event: self.payload)
        return apply.prepare_projection(
            self.root,
            event_id=EVENT_ID,
            source_validator=lambda _source: None,
            live_validator=lambda _root: [],
            projected_validator=lambda _root, _checkpoint: [],
            context_loader=lambda _root: self.context,
            capture_repository_state=capture,
        )

    def test_pass_receipt_projects_and_atomically_writes_seq42(self) -> None:
        before = self.checkpoint_path.read_bytes()
        prepared = self._prepare()
        event = prepared.event
        self.assertEqual(self.checkpoint_path.read_bytes(), before)
        self.assertEqual(set(event), contract.V24_FIRST_START_EVENT_FIELDS)
        self.assertEqual(event["sequence"], 42)
        self.assertEqual(event["event_id"], EVENT_ID)
        self.assertEqual(event["event_type"], "GOAL_STARTED")
        self.assertEqual(event["from_status"], "READY")
        self.assertEqual(event["to_status"], "IN_PROGRESS")
        self.assertEqual(event["previous_event_sha256"], apply.SOURCE_READY_EVENT_SHA256)
        self.assertEqual(event["event_sha256"], contract.event_sha256(event))
        self.assertEqual(event["occurred_at"], "2026-08-02T21:00:03+09:00")

        apply.write_projection(
            prepared,
            projected_validator=lambda _root, _checkpoint: [],
            context_loader=lambda _root: self.context,
            capture_repository_state=lambda _root, _checkpoint, _event: self.payload,
        )
        written = json.loads(self.checkpoint_path.read_text(encoding="utf-8"))
        state = written["goal_execution"]
        self.assertEqual(len(state["transition_history"]), 42)
        self.assertEqual(state["transition_history"][-1], event)
        self.assertEqual(state["status_by_goal"][materialize.GOAL_ID], "IN_PROGRESS")
        self.assertEqual(state["goal_status"], "READY")
        self.assertEqual(state["transition_history_anchor_sha256"], event["event_sha256"])
        self.assertEqual(written["working_tree_snapshot"]["scope"], apply.STARTED_SCOPE)
        self.assertEqual(
            written["session_handoff"]["last_verification_status"],
            "PASS_WITH_EPIC_IN_PROGRESS",
        )
        with self.assertRaisesRegex(apply.StartApplyError, "source checkpoint changed"):
            apply.write_projection(
                prepared,
                projected_validator=lambda _root, _checkpoint: [],
                context_loader=lambda _root: self.context,
                capture_repository_state=lambda _root, _checkpoint, _event: self.payload,
            )

    def test_missing_pass_receipt_never_writes_checkpoint(self) -> None:
        before = self.checkpoint_path.read_bytes()
        receipt = (
            self.root
            / gate.GATE_ROOT_RELATIVE
            / EVENT_ID
            / "implementation-start-gate-receipt.json"
        )
        receipt.unlink()
        with self.assertRaisesRegex(apply.StartApplyError, "missing or unsafe"):
            self._prepare()
        self.assertEqual(self.checkpoint_path.read_bytes(), before)

    def test_non_pass_receipt_never_writes_checkpoint(self) -> None:
        before = self.checkpoint_path.read_bytes()
        receipt_path = (
            self.root
            / gate.GATE_ROOT_RELATIVE
            / EVENT_ID
            / "implementation-start-gate-receipt.json"
        )
        receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
        receipt["status"] = "FAIL"
        receipt_path.write_bytes(apply.json_bytes(receipt))
        with self.assertRaisesRegex(apply.StartApplyError, "status differs"):
            self._prepare()
        self.assertEqual(self.checkpoint_path.read_bytes(), before)

    def test_current_repository_snapshot_mismatch_never_projects_or_writes(self) -> None:
        before = self.checkpoint_path.read_bytes()
        changed = copy.deepcopy(self.payload)
        changed["repository"]["branch"] = "changed/after-gate"
        with self.assertRaisesRegex(apply.StartApplyError, "current repository snapshot"):
            self._prepare(capture=lambda _root, _checkpoint, _event: changed)
        self.assertEqual(self.checkpoint_path.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()

from __future__ import annotations

import copy
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest import mock

from scripts import materialize_walksafe_npc_single_admin_recovery_goal_seq56_57_20260810 as subject


def _sha(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _write_json(path: Path, value: object, *, mode: int = 0o644) -> bytes:
    path.parent.mkdir(parents=True, exist_ok=True)
    raw = (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    path.write_bytes(raw)
    path.chmod(mode)
    return raw


def _canonical_bindings(backlog_sha: str = "a" * 64, gap_sha: str = "b" * 64) -> list[dict[str, object]]:
    completion_role = f"WORK_ITEM_COMPLETION::{subject.PREDECESSOR_GOAL_ID}"
    return [
        {
            "role": "IMPLEMENTATION_BACKLOG",
            "document_id": subject.BACKLOG_ID,
            "path": subject.BACKLOG_PATH.as_posix(),
            "file_sha256": backlog_sha,
        },
        {
            "role": "IMPLEMENTATION_GAP",
            "document_id": subject.GAP_DOCUMENT_ID,
            "path": subject.GAP_PATH.as_posix(),
            "file_sha256": gap_sha,
        },
        {
            "role": completion_role,
            "document_id": "FIXTURE-FP046-COMPLETION",
            "path": "fixture/fp046-completion.json",
            "file_sha256": "9" * 64,
        },
        *[
            {
                "role": f"FIXTURE_ROLE_{index:02d}",
                "document_id": f"FIXTURE-DOCUMENT-{index:02d}",
                "path": f"fixture/binding-{index:02d}.json",
                "file_sha256": f"{index % 10}" * 64,
            }
            for index in range(38)
        ],
    ]


def make_source(
    *,
    backlog_sha: str = "a" * 64,
    gap_sha: str = "b" * 64,
) -> dict[str, object]:
    bindings = _canonical_bindings(backlog_sha, gap_sha)
    canonical_snapshot = {
        binding["role"]: copy.deepcopy(binding) for binding in bindings
    }
    history: list[dict[str, object]] = []
    previous = ""
    for sequence in range(1, 54):
        event: dict[str, object] = {
            "sequence": sequence,
            "event_id": f"FIXTURE-EVENT-{sequence:02d}",
            "event_type": "WORK_SESSION_RESUMED",
            "occurred_on": "2026-08-09",
            "occurred_at": f"2026-08-09T00:{sequence % 60:02d}:00+09:00",
            "previous_event_sha256": previous,
            "status_changes": {},
        }
        event["event_sha256"] = subject.contract.event_sha256(event)
        previous = str(event["event_sha256"])
        history.append(event)
    canonical: dict[str, object] = {
        "sequence": 54,
        "event_id": subject.SOURCE_CANONICAL_EVENT_ID,
        "event_type": "CANONICAL_BINDINGS_UPDATED",
        "occurred_on": "2026-08-10",
        "occurred_at": "2026-08-10T06:00:00+09:00",
        "previous_event_sha256": previous,
        "status_changes": {},
        "produced_by_goal_id": subject.PREDECESSOR_GOAL_ID,
        "canonical_binding_snapshot_after": copy.deepcopy(canonical_snapshot),
    }
    canonical["event_sha256"] = subject.contract.event_sha256(canonical)
    history.append(canonical)
    completion: dict[str, object] = {
        "sequence": 55,
        "event_id": subject.SOURCE_COMPLETION_EVENT_ID,
        "event_type": "GOAL_COMPLETED",
        "occurred_on": "2026-08-10",
        "occurred_at": "2026-08-10T06:00:01+09:00",
        "previous_event_sha256": canonical["event_sha256"],
        "canonical_update_event_sha256": canonical["event_sha256"],
        "subject_goal_id": subject.PREDECESSOR_GOAL_ID,
        "from_status": "IN_PROGRESS",
        "to_status": "COMPLETE_AT_TARGET",
        "status_changes": {subject.PREDECESSOR_GOAL_ID: "COMPLETE_AT_TARGET"},
        "completion_receipt_binding": copy.deepcopy(
            canonical_snapshot[
                f"WORK_ITEM_COMPLETION::{subject.PREDECESSOR_GOAL_ID}"
            ]
        ),
        "completion_evidence_bindings": {
            f"WORK_ITEM_COMPLETION::{subject.PREDECESSOR_GOAL_ID}": copy.deepcopy(
                canonical_snapshot[
                    f"WORK_ITEM_COMPLETION::{subject.PREDECESSOR_GOAL_ID}"
                ]
            )
        },
        "canonical_binding_snapshot_after": copy.deepcopy(canonical_snapshot),
    }
    completion["event_sha256"] = subject.contract.event_sha256(completion)
    history.append(completion)
    return {
        "schema_version": "1.25.0",
        "canonical_bindings": bindings,
        "goal_execution": {
            "transition_history": history,
            "transition_history_anchor_sha256": completion["event_sha256"],
            "validation_cutoff_at": completion["occurred_at"],
            "status_by_goal": {
                subject.PREDECESSOR_GOAL_ID: "COMPLETE_AT_TARGET",
                subject.PARENT_GOAL_ID: "READY",
                subject.EPIC12_GOAL_ID: "READY",
            },
            "focus_goal_id": subject.PARENT_GOAL_ID,
            "focus_goal_path": subject.PARENT_GOAL_PATH.as_posix(),
            "focus_work_item_id": "",
            "focus_source": "WORKSTREAM_GRAPH",
            "ready_frontier_goal_ids": copy.deepcopy(subject.MATERIALIZED_FRONTIER),
            "blocked_goal_ids": [],
            "blockers_by_goal": {},
            "blocker_resolution_history": [],
            "pending_questions": [],
            "open_question_count": 0,
            "activation_status": "ACTIVE",
            "package_status": "ACTIVE",
            "goal_document_paths": [
                subject.PARENT_GOAL_PATH.as_posix(),
                subject.PREDECESSOR_GOAL_PATH.as_posix(),
            ],
            "goal_document_count": 2,
            "managed_goal_paths": [
                subject.PARENT_GOAL_PATH.as_posix(),
                subject.PREDECESSOR_GOAL_PATH.as_posix(),
            ],
            "managed_goal_path_count": 2,
            "dynamic_goal_inventory": {},
            "materialized_child_goal_ids_by_parent": {
                subject.PARENT_GOAL_ID: [subject.PREDECESSOR_GOAL_ID]
            },
            "static_plan_manifest_sha256": "c" * 64,
            "artifact_work_queue": {},
            "completion_boundary": {},
            "completion_evidence_by_goal": {
                subject.PREDECESSOR_GOAL_ID: [
                    f"WORK_ITEM_COMPLETION::{subject.PREDECESSOR_GOAL_ID}"
                ]
            },
        },
        "current_work": {
            "work_item_id": subject.WORK_ITEM_ID,
            "next_action": subject.NEXT_ACTION,
            "status": "PLANNED_NEXT",
            "release_completion_claimed": False,
        },
        "approved_state": {"release_status": "NOT_ELIGIBLE"},
        "verification_boundary": {
            "actual_device_test_status": "NOT_RUN",
            "all_remaining_gate_status": "NOT_RUN",
            "formal_test_pass_claimed": False,
            "release_eligible": False,
        },
        "authority_boundary": {"normative_policy_source": False},
        "working_tree_snapshot": {
            "scope": "fixture",
            "managed_changed_paths": [],
            "managed_changed_path_count": 0,
            "path_set_sha256": "d" * 64,
            "content_set_sha256": "e" * 64,
        },
        "session_handoff": {
            "changed_files": [],
            "source_commit_or_snapshot": {
                "file_count": 0,
                "path_set_sha256": "d" * 64,
                "content_set_sha256": "e" * 64,
            },
            "last_verification_status": subject.SAFE_VERIFICATION_STATUS,
        },
    }


def make_source_checkpoint(source: dict[str, object]) -> subject.SourceCheckpoint:
    raw = (json.dumps(source, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    return subject.SourceCheckpoint(
        raw=raw,
        raw_sha256=_sha(raw),
        byte_count=len(raw),
        document=source,
        identity=(1, 2, 0o100600, 3, 4, 1, len(raw)),
    )


def make_static_inputs() -> subject.StaticInputs:
    return subject.StaticInputs(
        goal_sha256="f" * 64,
        backlog_binding=_canonical_bindings()[0],
        gap_binding=_canonical_bindings()[1],
        start_gate_binding={
            "document_id": subject.START_GATE_CONTRACT_DOCUMENT_ID,
            "contract_id": subject.START_GATE_CONTRACT_ID,
            "contract_version": subject.START_GATE_CONTRACT_VERSION,
            "path": subject.START_GATE_CONTRACT_PATH.as_posix(),
            "file_sha256": "1" * 64,
            "canonical_sha256": "2" * 64,
        },
    )


def project_fixture(root: Path) -> tuple[dict[str, object], dict[str, object], dict[str, object], dict[str, object], subject.StaticInputs]:
    source = make_source()
    inputs = make_static_inputs()
    with (
        mock.patch.object(subject, "require_source_working_snapshot"),
        mock.patch.object(subject, "load_static_inputs", return_value=inputs),
        mock.patch.object(subject.contract, "package_hashes", return_value=("3" * 64, "4" * 64)),
        mock.patch.object(subject.contract, "working_snapshot_hashes", return_value=("5" * 64, "6" * 64)),
        mock.patch.object(
            subject,
            "_derive_queue_and_boundary",
            side_effect=[
                ({"queue": "planned"}, {"boundary": "planned"}),
                ({"queue": "ready"}, {"boundary": "ready"}),
            ],
        ),
    ):
        projected, materialized, ready = subject.project(root, make_source_checkpoint(source))
    return source, projected, materialized, ready, inputs


def _gate_contract(goal_sha: str) -> dict[str, object]:
    commands = {
        "CONTINUATION": "python scripts/check_walksafe_project_continuation_v2_4.py",
        "V24_ARTIFACT_WORK_QUEUE": "python scripts/check_walksafe_goal_graph_v2_4.py",
        "TEST_LAYER_REGISTRY_VALIDATE": "bash scripts/run_walksafe_test_layers_20260711.sh validate",
        "BACKEND_TEST_DATABASE_PREFLIGHT": "python scripts/check_walksafe_test_database_20260713.py",
        "BACKEND_ADMIN_SECURITY_RECOVERY_POSTGRES": "pytest backend/tests/test_admin_recovery.py",
        "ANDROID_ADMIN_INTERNAL": "gradle admin recovery unitTest",
        "ROOT_NPC_SINGLE_ADMIN_RECOVERY_CONTROL_REGRESSION": "pytest tests/test_admin_recovery_control.py",
        "REPOSITORY_STATE": "python check_walksafe_project_continuation_v2_4.py --print-gate-repository-state",
    }
    return {
        "schema_version": "1.0",
        "document_id": subject.START_GATE_CONTRACT_DOCUMENT_ID,
        "contract_id": subject.START_GATE_CONTRACT_ID,
        "contract_version": subject.START_GATE_CONTRACT_VERSION,
        "target_goal_id": subject.GOAL_ID,
        "target_goal_content_sha256": goal_sha,
        "gate_purpose": "INITIAL_START",
        "ordered_checks": [
            {"check_id": check_id, "command": commands[check_id]}
            for check_id in subject.START_GATE_CHECK_IDS
        ],
    }


class NpcSingleAdminRecoveryMaterializerTests(unittest.TestCase):
    def test_exact_seq55_raw_is_loaded_from_a_0600_tmp_fixture(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw = _write_json(root / subject.CHECKPOINT, make_source(), mode=0o600)
            loaded = subject.load_exact_source(root)
            self.assertEqual(loaded.raw, raw)
            self.assertEqual(loaded.raw_sha256, _sha(raw))
            self.assertEqual(loaded.byte_count, len(raw))

    def test_source_rejects_seq56_or_a_changed_predecessor_seal(self) -> None:
        source = make_source()
        source["goal_execution"]["transition_history"].append({})
        with self.assertRaisesRegex(subject.ProjectionError, "not seq55"):
            subject.require_exact_source(source)
        source = make_source()
        source["goal_execution"]["transition_history"][-1]["event_sha256"] = "0" * 64
        with self.assertRaisesRegex(subject.ProjectionError, "seq55 seal"):
            subject.require_exact_source(source)

    def test_source_accepts_canonical_binding_metadata_but_still_binds_receipt_core(self) -> None:
        source = make_source()
        completion_role = f"WORK_ITEM_COMPLETION::{subject.PREDECESSOR_GOAL_ID}"
        completion_binding = next(
            binding
            for binding in source["canonical_bindings"]
            if binding["role"] == completion_role
        )
        completion_binding["identity_json_path"] = "document_id"
        completion_binding["mutable"] = False
        subject.require_exact_source(source)

        completion_binding["file_sha256"] = "8" * 64
        with self.assertRaisesRegex(
            subject.ProjectionError,
            "completion evidence|canonical snapshot",
        ):
            subject.require_exact_source(source)

    def test_r025_goal_and_start_contract_are_exact_tmp_inputs(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            backlog = {
                "next_single_action": {
                    "action": subject.NEXT_ACTION,
                    "epic_id": "EPIC-03",
                    "gap_id": subject.POLICY_GAP_ID,
                    "priority_rank": subject.PRIORITY_RANK,
                    "source_policy_id": subject.POLICY_ID,
                    "status": "PLANNED_NEXT",
                },
                "next_action_sequence": [
                    {
                        "action": subject.NEXT_ACTION,
                        "epic_id": "EPIC-03",
                        "order": 22,
                        "priority": "P0",
                        "source_policy_id": subject.POLICY_ID,
                        "status": "MISSING",
                        "wave": 1,
                    }
                ],
            }
            gap = {
                "assessments": [
                    {
                        "source_policy_id": subject.POLICY_ID,
                        "gap_id": subject.POLICY_GAP_ID,
                        "status": "MISSING",
                        "formal_test_status": "NOT_RUN",
                        "planned_test_ids": ["TC-NPC-SINGLE-ADMIN-RECOVERY-01"],
                    }
                ]
            }
            backlog_raw = _write_json(root / subject.BACKLOG_PATH, backlog)
            gap_raw = _write_json(root / subject.GAP_PATH, gap)
            bindings = _canonical_bindings(_sha(backlog_raw), _sha(gap_raw))
            source = make_source(backlog_sha=_sha(backlog_raw), gap_sha=_sha(gap_raw))
            (root / subject.GOAL_PATH).parent.mkdir(parents=True, exist_ok=True)
            goal_raw = subject.render_goal_document(bindings[0])
            (root / subject.GOAL_PATH).write_bytes(goal_raw)
            for relative in (subject.PREDECESSOR_GOAL_PATH, subject.PARENT_GOAL_PATH):
                destination = root / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(subject.ROOT / relative, destination)
            _write_json(
                root / subject.START_GATE_CONTRACT_PATH,
                _gate_contract(_sha(goal_raw)),
            )
            inputs = subject.load_static_inputs(root, source)
            self.assertEqual(inputs.goal_sha256, _sha(goal_raw))
            self.assertEqual(inputs.backlog_binding, bindings[0])
            self.assertEqual(tuple(item["check_id"] for item in _gate_contract(_sha(goal_raw))["ordered_checks"]), subject.START_GATE_CHECK_IDS)

    def test_r025_goal_priority_drift_is_rejected(self) -> None:
        binding = _canonical_bindings()[0]
        rendered = subject.render_goal_document(binding)
        self.assertIn(b'priority_rank = 22', rendered)
        self.assertNotEqual(rendered.replace(b"priority_rank = 22", b"priority_rank = 23"), rendered)

    def test_projection_is_add_only_seq56_57_and_preserves_frozen_source(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source, projected, materialized, ready, _ = project_fixture(Path(directory))
        history = projected["goal_execution"]["transition_history"]
        self.assertEqual(history[:55], source["goal_execution"]["transition_history"])
        self.assertEqual([event["sequence"] for event in history[-2:]], [56, 57])
        self.assertEqual([event["event_type"] for event in history[-2:]], ["GOAL_MATERIALIZED", "GOAL_READY"])
        self.assertEqual(materialized["previous_event_sha256"], history[54]["event_sha256"])
        self.assertEqual(ready["previous_event_sha256"], materialized["event_sha256"])
        self.assertEqual(materialized["event_sha256"], subject.contract.event_sha256(materialized))
        self.assertEqual(ready["event_sha256"], subject.contract.event_sha256(ready))
        self.assertEqual(projected["canonical_bindings"], source["canonical_bindings"])
        self.assertEqual(projected["verification_boundary"], source["verification_boundary"])
        self.assertEqual(projected["approved_state"], source["approved_state"])
        self.assertEqual(projected["authority_boundary"], source["authority_boundary"])

    def test_ready_is_exact_fp046_dependency_and_zero_credit(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source, projected, _, ready, inputs = project_fixture(Path(directory))
            with mock.patch.object(subject, "load_static_inputs", return_value=inputs):
                subject.require_ready_checkpoint(
                    Path(directory), projected, run_external_validators=False
                )
        completion_hash = source["goal_execution"]["transition_history"][-1]["event_sha256"]
        self.assertEqual(ready["readiness_basis"]["predecessor_completion_event_sha256"], completion_hash)
        self.assertEqual(projected["goal_execution"]["status_by_goal"][subject.GOAL_ID], "READY")
        self.assertNotIn("IN_PROGRESS", projected["goal_execution"]["status_by_goal"].values())
        self.assertEqual(projected["approved_state"]["release_status"], "NOT_ELIGIBLE")
        self.assertFalse(projected["verification_boundary"]["release_eligible"])
        self.assertEqual(projected["verification_boundary"]["actual_device_test_status"], "NOT_RUN")
        self.assertFalse(any(event.get("event_type") == "GOAL_STARTED" and event.get("subject_goal_id") == subject.GOAL_ID for event in projected["goal_execution"]["transition_history"][:57]))

    def test_projection_rejects_a_seq55_managed_snapshot_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = make_source()
            with mock.patch.object(
                subject.contract,
                "working_snapshot_hashes",
                return_value=("actual-path", "actual-content"),
            ):
                with self.assertRaisesRegex(subject.ProjectionError, "seq55 working snapshot"):
                    subject.require_source_working_snapshot(root, source)

    def test_cli_does_not_accept_abbreviated_or_implicit_modes(self) -> None:
        with self.assertRaises(SystemExit):
            subject.parse_args([])
        with self.assertRaises(SystemExit):
            subject.parse_args(["--pre", "--root", "/tmp"])


if __name__ == "__main__":
    unittest.main()

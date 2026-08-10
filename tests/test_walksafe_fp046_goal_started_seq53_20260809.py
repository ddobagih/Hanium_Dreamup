from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import unittest

from scripts import apply_walksafe_fp046_goal_started_seq53_20260809 as started
from scripts import check_walksafe_project_continuation_v2_4 as continuation
from scripts import materialize_walksafe_fp046_goal_seq51_52_20260809 as materialize
from scripts import run_walksafe_fp046_goal_start_gate_20260809 as gate


ROOT = Path(__file__).resolve().parents[1]
EVENT_ID = "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-20260809-001"


def _ready_source() -> dict[str, object]:
    raw = (ROOT / materialize.CHECKPOINT).read_bytes()
    live = json.loads(raw)
    if hashlib.sha256(raw).hexdigest() == materialize.SOURCE_CHECKPOINT_SHA256:
        _, source = materialize.load_exact_source(ROOT)
        projected, _, _ = materialize.project(ROOT, source)
        return projected

    candidate = copy.deepcopy(live)
    state = candidate["goal_execution"]
    history = state["transition_history"]
    if len(history) < 52:
        raise AssertionError("live checkpoint is neither seq50 nor an FP046 successor")
    del history[52:]
    ready = history[-1]
    state["transition_history_anchor_sha256"] = ready["event_sha256"]
    state["validation_cutoff_at"] = ready["occurred_at"]
    state["status_by_goal"][materialize.GOAL_ID] = "READY"
    state["focus_goal_id"] = materialize.GOAL_ID
    state["focus_goal_path"] = materialize.GOAL_PATH.as_posix()
    state["focus_work_item_id"] = materialize.WORK_ITEM_ID
    state["focus_source"] = "IMPLEMENTATION_BACKLOG"
    state["ready_frontier_goal_ids"] = copy.deepcopy(materialize.READY_FRONTIER)
    candidate["current_work"]["work_item_id"] = materialize.WORK_ITEM_ID
    candidate["current_work"]["current_focus"] = (
        "FP046/GAP-055 Goal READY; active internal start gate not run"
    )
    candidate["current_work"]["release_completion_claimed"] = False
    return candidate


def _evidence() -> started.GateEvidence:
    receipt_binding = {
        "document_id": gate._document_id(EVENT_ID),
        "path": (
            f"docs/control/execution/goal-gates/{EVENT_ID}/"
            "implementation-start-gate-receipt.json"
        ),
        "file_sha256": "d" * 64,
    }
    repository_snapshot = {
        "head_commit": "a" * 40,
        "branch": "test/fp046",
        "git_status_raw_sha256": "1" * 64,
        "git_status_raw_byte_count": 1,
        "git_status_record_count": 1,
        "dirty_path_count": 1,
        "dirty_path_set_sha256": "2" * 64,
        "dirty_content_set_sha256": "3" * 64,
        "index_state_sha256": "4" * 64,
        "checkpoint_managed_changed_path_count": 1,
        "checkpoint_managed_path_set_sha256": "5" * 64,
        "checkpoint_managed_content_set_sha256": "6" * 64,
        "gate_repository_state_output_sha256": "7" * 64,
    }
    receipt = {
        "repository_snapshot": repository_snapshot,
    }
    return started.GateEvidence(
        receipt=receipt,
        receipt_bytes=b"{}\n",
        receipt_binding=receipt_binding,
        repository_payload={},
        event_occurred_at="2026-08-09T20:00:01+09:00",
    )


class WalkSafeFp046GoalStartedSeq53Test(unittest.TestCase):
    def test_seq53_projection_is_exactly_goal_started_in_progress(self) -> None:
        source = _ready_source()
        started.require_exact_source(source)
        projected, event = started.project_seq53(
            source,
            _evidence(),
            event_id=EVENT_ID,
        )
        self.assertEqual(set(event), continuation.V24_FIRST_START_EVENT_FIELDS)
        self.assertEqual(event["sequence"], 53)
        self.assertEqual(event["event_type"], "GOAL_STARTED")
        self.assertEqual(event["subject_goal_id"], materialize.GOAL_ID)
        self.assertEqual((event["from_status"], event["to_status"]), ("READY", "IN_PROGRESS"))
        self.assertEqual(event["previous_event_sha256"], materialize.EXPECTED_READY_EVENT_SHA256)
        self.assertEqual(event["event_sha256"], continuation.event_sha256(event))
        state = projected["goal_execution"]
        self.assertEqual(len(state["transition_history"]), 53)
        self.assertEqual(state["transition_history"][:-1], source["goal_execution"]["transition_history"])
        self.assertEqual(state["transition_history_anchor_sha256"], event["event_sha256"])
        self.assertEqual(state["status_by_goal"][materialize.GOAL_ID], "IN_PROGRESS")
        self.assertEqual(projected["current_work"]["status"], "IN_PROGRESS")
        self.assertEqual(projected["current_work"]["current_focus"], started.STARTED_CURRENT_FOCUS)

    def test_seq53_carries_only_internal_start_credit(self) -> None:
        projected, event = started.project_seq53(
            _ready_source(),
            _evidence(),
            event_id=EVENT_ID,
        )
        self.assertEqual(event["evidence_refs"], [])
        self.assertFalse(projected["current_work"]["release_completion_claimed"])
        scope = projected["working_tree_snapshot"]["scope"].lower()
        for excluded in ("admin", "web", "device", "external", "formal", "release"):
            self.assertIn(excluded, scope)
        self.assertEqual(
            projected["session_handoff"]["current_epic"],
            "EPIC-03 / FP046/GAP-055 IN_PROGRESS",
        )

    def test_source_validator_rejects_a_started_or_tampered_source(self) -> None:
        source = _ready_source()
        tampered = copy.deepcopy(source)
        tampered["goal_execution"]["status_by_goal"][materialize.GOAL_ID] = (
            "IN_PROGRESS"
        )
        with self.assertRaisesRegex(started.StartApplyError, "READY start target"):
            started.require_exact_source(tampered)

        successor, _ = started.project_seq53(
            source,
            _evidence(),
            event_id=EVENT_ID,
        )
        with self.assertRaisesRegex(started.StartApplyError, "source is not seq52"):
            started.require_exact_source(successor)


if __name__ == "__main__":
    unittest.main()

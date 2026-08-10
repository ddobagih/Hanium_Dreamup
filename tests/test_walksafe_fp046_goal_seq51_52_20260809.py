from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import stat
import unittest

from scripts import apply_walksafe_fp046_goal_seq51_52_20260809 as publisher
from scripts import check_walksafe_project_continuation_v2_4 as continuation
from scripts import materialize_walksafe_fp046_goal_seq51_52_20260809 as materialize


ROOT = Path(__file__).resolve().parents[1]


class WalkSafeFp046GoalSeq5152Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.checkpoint_path = ROOT / materialize.CHECKPOINT
        cls.before_bytes = cls.checkpoint_path.read_bytes()
        cls.before_stat = cls.checkpoint_path.stat()
        live = json.loads(cls.before_bytes)
        cls.source_mode = (
            hashlib.sha256(cls.before_bytes).hexdigest()
            == materialize.SOURCE_CHECKPOINT_SHA256
        )
        if cls.source_mode:
            _, cls.source = materialize.load_exact_source(ROOT)
            cls.projected, cls.seq51, cls.seq52 = materialize.preflight(ROOT)
        else:
            cls.source = None
            history = live.get("goal_execution", {}).get("transition_history", [])
            if len(history) < 52:
                raise AssertionError("live checkpoint is neither seq50 nor an FP046 successor")
            cls.projected = live
            cls.seq51, cls.seq52 = history[50:52]

    @classmethod
    def tearDownClass(cls) -> None:
        after = cls.checkpoint_path.stat()
        if cls.checkpoint_path.read_bytes() != cls.before_bytes:
            raise AssertionError("test changed the live checkpoint bytes")
        if (after.st_dev, after.st_ino) != (
            cls.before_stat.st_dev,
            cls.before_stat.st_ino,
        ):
            raise AssertionError("test changed the live checkpoint inode")

    def test_goal_and_start_contract_are_exactly_bound(self) -> None:
        self.assertEqual(stat.S_IMODE(self.before_stat.st_mode), 0o600)
        self.assertEqual(
            continuation.sha256_file(ROOT / materialize.GOAL_PATH),
            materialize.GOAL_SHA256,
        )
        contract = materialize.load_start_gate_contract(ROOT)
        self.assertEqual(
            tuple(item["check_id"] for item in contract["ordered_checks"]),
            materialize.START_GATE_CHECK_IDS,
        )
        self.assertEqual(
            self.seq52["implementation_start_gate_contract_binding"],
            materialize.start_gate_contract_binding(),
        )

    def test_seq51_and_seq52_form_an_add_only_materialized_ready_pair(self) -> None:
        self.assertEqual(self.seq51["sequence"], 51)
        self.assertEqual(self.seq51["event_id"], materialize.MATERIALIZED_EVENT_ID)
        self.assertEqual(self.seq51["event_type"], "GOAL_MATERIALIZED")
        self.assertEqual(self.seq51["materialized_goal_id"], materialize.GOAL_ID)
        self.assertEqual(self.seq51["previous_event_sha256"], materialize.SOURCE_TAIL_SHA256)
        self.assertEqual(self.seq51["status_changes"], {materialize.GOAL_ID: "PLANNED"})
        self.assertEqual(self.seq51["event_sha256"], continuation.event_sha256(self.seq51))
        self.assertEqual(
            self.seq51["event_sha256"],
            materialize.EXPECTED_MATERIALIZED_EVENT_SHA256,
        )

        self.assertEqual(self.seq52["sequence"], 52)
        self.assertEqual(self.seq52["event_id"], materialize.READY_EVENT_ID)
        self.assertEqual(self.seq52["event_type"], "GOAL_READY")
        self.assertEqual(self.seq52["subject_goal_id"], materialize.GOAL_ID)
        self.assertEqual(self.seq52["previous_event_sha256"], self.seq51["event_sha256"])
        self.assertEqual(self.seq52["status_changes"], {materialize.GOAL_ID: "READY"})
        self.assertEqual(self.seq52["event_sha256"], continuation.event_sha256(self.seq52))
        self.assertEqual(
            self.seq52["event_sha256"],
            materialize.EXPECTED_READY_EVENT_SHA256,
        )

    def test_readiness_depends_on_the_exact_fp008_completion(self) -> None:
        self.assertEqual(
            self.seq52["readiness_basis"],
            {
                "dependency_completion_events": [
                    {
                        "goal_id": materialize.PREDECESSOR_GOAL_ID,
                        "event_sha256": materialize.PREDECESSOR_COMPLETION_EVENT_SHA256,
                    }
                ],
                "predecessor_goal_id": materialize.PREDECESSOR_GOAL_ID,
                "predecessor_completion_event_sha256": (
                    materialize.PREDECESSOR_COMPLETION_EVENT_SHA256
                ),
            },
        )
        inventory = self.projected["goal_execution"]["dynamic_goal_inventory"]
        self.assertEqual(
            inventory[materialize.GOAL_ID]["materialized_event_sha256"],
            self.seq51["event_sha256"],
        )

    def test_exact_seq50_projection_preserves_prior_authority_and_validates(self) -> None:
        if not self.source_mode:
            self.skipTest("live checkpoint has advanced beyond the exact seq50 source")
        source_state = self.source["goal_execution"]
        projected_state = self.projected["goal_execution"]
        self.assertEqual(
            projected_state["transition_history"][: materialize.SOURCE_SEQUENCE],
            source_state["transition_history"],
        )
        self.assertEqual(
            projected_state["completion_evidence_by_goal"],
            source_state["completion_evidence_by_goal"],
        )
        self.assertEqual(
            self.projected["verification_boundary"],
            self.source["verification_boundary"],
        )
        self.assertEqual(materialize.validate_projection(ROOT, self.projected), [])
        materialize.require_ready_checkpoint(ROOT, self.projected)

    def test_projection_is_ready_not_started_and_zero_credit(self) -> None:
        if self.source_mode or len(
            self.projected["goal_execution"]["transition_history"]
        ) == 52:
            state = self.projected["goal_execution"]
            self.assertEqual(state["status_by_goal"][materialize.GOAL_ID], "READY")
            self.assertNotIn("IN_PROGRESS", state["status_by_goal"].values())
            self.assertEqual(self.projected["current_work"]["status"], "READY")
            self.assertEqual(state["focus_goal_id"], materialize.GOAL_ID)
            self.assertEqual(state["ready_frontier_goal_ids"], materialize.READY_FRONTIER)
            self.assertIn("no GOAL_STARTED", self.projected["working_tree_snapshot"]["scope"])
            self.assertFalse(self.projected["current_work"]["release_completion_claimed"])
        self.assertFalse(
            any(
                event.get("event_type") == "GOAL_COMPLETED"
                and event.get("subject_goal_id") == materialize.GOAL_ID
                for event in self.projected["goal_execution"]["transition_history"]
            )
        )

    def test_exact_source_reconciliation_rejects_unlisted_drift(self) -> None:
        if not self.source_mode:
            self.skipTest("live checkpoint has advanced beyond the exact seq50 source")
        candidate = copy.deepcopy(self.source)
        candidate["working_tree_snapshot"]["content_set_sha256"] = "0" * 64
        with self.assertRaisesRegex(
            materialize.ProjectionError,
            "source working snapshot authority differs",
        ):
            materialize.require_authorized_source_delta(ROOT, candidate)

    def test_publication_prepare_is_read_only(self) -> None:
        if not self.source_mode:
            self.skipTest("publication preparation only accepts exact seq50")
        prepared = publisher.prepare(ROOT)
        try:
            self.assertEqual(self.checkpoint_path.read_bytes(), self.before_bytes)
            self.assertEqual(prepared.projected, self.projected)
        finally:
            prepared.cohort.close()


if __name__ == "__main__":
    unittest.main()

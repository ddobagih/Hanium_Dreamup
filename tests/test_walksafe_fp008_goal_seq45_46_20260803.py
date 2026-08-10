from __future__ import annotations

import copy
import hashlib
import io
import json
import stat
import tempfile
from contextlib import redirect_stderr
from pathlib import Path
import unittest
from unittest import mock

from scripts import check_walksafe_project_continuation_v2_4 as contract
from scripts import apply_walksafe_fp008_goal_seq45_46_20260803 as publisher
from scripts import materialize_walksafe_fp008_goal_seq45_46_20260803 as materialize


ROOT = Path(__file__).resolve().parents[1]


class WalkSafeFp008GoalSeq4546Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.checkpoint_path = ROOT / materialize.CHECKPOINT
        cls.before_bytes = cls.checkpoint_path.read_bytes()
        cls.before_stat = cls.checkpoint_path.stat()
        live_sha256 = hashlib.sha256(cls.before_bytes).hexdigest()
        if live_sha256 == materialize.SOURCE_CHECKPOINT_SHA256:
            cls.source_mode = True
            _, cls.source = materialize.load_exact_source(ROOT)
            cls.projected, cls.seq45, cls.seq46 = materialize.preflight(ROOT)
        else:
            cls.source_mode = False
            cls.source = None
            successor = json.loads(cls.before_bytes)
            history = successor.get("goal_execution", {}).get(
                "transition_history",
                [],
            )
            if len(history) == materialize.READY_SEQUENCE:
                cls.projected = successor
                materialize.require_ready_checkpoint(ROOT, cls.projected)
            else:
                cls.projected = materialize.ready_checkpoint_from_exact_seq47_successor(
                    ROOT,
                    successor,
                )
            cls.seq45, cls.seq46 = cls.projected["goal_execution"][
                "transition_history"
            ][-2:]

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

    def test_exact_source_and_static_pins(self) -> None:
        if self.source_mode:
            self.assertEqual(
                len(self.before_bytes), materialize.SOURCE_CHECKPOINT_BYTE_COUNT
            )
            self.assertEqual(
                hashlib.sha256(self.before_bytes).hexdigest(),
                materialize.SOURCE_CHECKPOINT_SHA256,
            )
        else:
            materialize.require_ready_checkpoint(ROOT, self.projected)
        self.assertEqual(stat.S_IMODE(self.before_stat.st_mode), 0o600)
        self.assertEqual(
            contract.sha256_file(ROOT / materialize.GOAL_PATH),
            materialize.GOAL_SHA256,
        )
        self.assertEqual(
            contract.sha256_file(ROOT / materialize.BACKLOG_PATH),
            materialize.BACKLOG_SHA256,
        )
        self.assertEqual(
            contract.sha256_file(ROOT / materialize.GAP_PATH),
            materialize.GAP_SHA256,
        )

    def test_seq45_and_seq46_are_exact_add_only_transition_pair(self) -> None:
        state = self.projected["goal_execution"]
        history = state["transition_history"]
        self.assertEqual(len(history), 46)
        self.assertEqual(history[-2:], [self.seq45, self.seq46])
        self.assertEqual(self.seq45["sequence"], 45)
        self.assertEqual(self.seq45["event_type"], "GOAL_MATERIALIZED")
        self.assertEqual(self.seq45["previous_event_sha256"], materialize.SOURCE_TAIL_SHA256)
        self.assertEqual(self.seq45["status_changes"], {materialize.GOAL_ID: "PLANNED"})
        self.assertEqual(self.seq45["event_sha256"], contract.event_sha256(self.seq45))
        self.assertEqual(set(self.seq45), materialize.EXPECTED_MATERIALIZED_EVENT_FIELDS)
        self.assertEqual(
            self.seq45["event_sha256"],
            materialize.EXPECTED_MATERIALIZED_EVENT_SHA256,
        )
        self.assertEqual(self.seq46["sequence"], 46)
        self.assertEqual(self.seq46["event_type"], "GOAL_READY")
        self.assertEqual(self.seq46["previous_event_sha256"], self.seq45["event_sha256"])
        self.assertEqual(self.seq46["status_changes"], {materialize.GOAL_ID: "READY"})
        self.assertEqual(self.seq46["event_sha256"], contract.event_sha256(self.seq46))
        self.assertEqual(set(self.seq46), materialize.EXPECTED_READY_EVENT_FIELDS)
        self.assertEqual(
            self.seq46["event_sha256"],
            materialize.EXPECTED_READY_EVENT_SHA256,
        )
        self.assertEqual(state["transition_history_anchor_sha256"], self.seq46["event_sha256"])
        self.assertEqual(state["status_by_goal"][materialize.GOAL_ID], "READY")
        self.assertFalse(any(event.get("sequence") == 47 for event in history))

    def test_source_history_completion_and_verification_evidence_are_unchanged(self) -> None:
        after = self.projected["goal_execution"]
        if self.source_mode:
            before = self.source["goal_execution"]
            self.assertEqual(
                after["transition_history"][: materialize.SOURCE_SEQUENCE],
                before["transition_history"],
            )
            self.assertEqual(
                after["completion_evidence_by_goal"],
                before["completion_evidence_by_goal"],
            )
            self.assertEqual(
                after["archived_completion_evidence_by_goal"],
                before["archived_completion_evidence_by_goal"],
            )
            self.assertEqual(
                after["verification_evidence_refs"],
                before["verification_evidence_refs"],
            )
            self.assertEqual(
                self.projected["verification_boundary"],
                self.source["verification_boundary"],
            )
        self.assertEqual(
            contract.canonical_json_sha256(
                after["transition_history"][: materialize.SOURCE_SEQUENCE]
            ),
            materialize.SOURCE_HISTORY_PREFIX_SHA256,
        )
        self.assertEqual(
            contract.canonical_json_sha256(self.projected["verification_boundary"]),
            materialize.SOURCE_VERIFICATION_BOUNDARY_SHA256,
        )

    def test_ready_semantic_pin_rejects_unmodeled_checkpoint_mutations(self) -> None:
        mutations = (
            (("session_handoff", "current_epic"), "TAMPERED"),
            (("current_work", "status"), "COMPLETE"),
            (("current_work", "next_action"), "TAMPERED"),
            (("working_tree_snapshot", "scope"), "TAMPERED"),
            (("working_tree_snapshot", "base_head"), "0" * 40),
            (("repository", "branch"), "tampered"),
            (("metadata", "purpose"), "tampered"),
            (("approved_state", "release_status"), "ELIGIBLE"),
            (("authority_boundary", "normative_policy_source"), True),
            (("implementation_gap_snapshot", "report_id"), "tampered"),
            (("execution_order",), []),
            (("goal_execution", "goal_status"), "COMPLETE"),
            (("goal_execution", "schema_version"), "tampered"),
            (("goal_execution", "static_plan_locked"), False),
            (("goal_execution", "graph_model"), "tampered"),
            (("goal_execution", "standing_execution_authority"), {}),
        )
        for key_path, wrong in mutations:
            with self.subTest(path=".".join(key_path)):
                candidate = copy.deepcopy(self.projected)
                target = candidate
                for key in key_path[:-1]:
                    target = target[key]
                target[key_path[-1]] = wrong
                with self.assertRaisesRegex(
                    materialize.ProjectionError,
                    "semantic SHA-256 differs",
                ):
                    materialize.require_ready_checkpoint(ROOT, candidate)

    def test_ready_content_self_reference_is_recomputed_and_cross_checked(self) -> None:
        candidate = copy.deepcopy(self.projected)
        candidate["working_tree_snapshot"]["content_set_sha256"] = "0" * 64
        candidate["session_handoff"]["source_commit_or_snapshot"][
            "content_set_sha256"
        ] = "0" * 64
        self.assertEqual(
            materialize.ready_checkpoint_semantic_sha256(candidate),
            materialize.READY_CHECKPOINT_SEMANTIC_SHA256,
        )
        with self.assertRaisesRegex(
            materialize.ProjectionError,
            "working snapshot content-set SHA-256 differs",
        ):
            materialize.require_ready_checkpoint(ROOT, candidate)

    def test_coordinated_source_rebind_is_rejected_by_frozen_source_pin(self) -> None:
        runner = "scripts/run_walksafe_fp008_goal_start_gate_20260803.py"
        paths = self.projected["working_tree_snapshot"]["managed_changed_paths"]
        self.assertIn(runner, paths)
        real_sha256 = materialize.contract.sha256_file
        forged_runner_sha256 = "f" * 64
        digests = {
            relative: (
                forged_runner_sha256
                if relative == runner
                else real_sha256(ROOT / relative)
            )
            for relative in paths
        }
        rebound = materialize._content_set_sha256_from_digests(paths, digests)
        candidate = copy.deepcopy(self.projected)
        candidate["working_tree_snapshot"]["content_set_sha256"] = rebound
        candidate["session_handoff"]["source_commit_or_snapshot"][
            "content_set_sha256"
        ] = rebound
        self.assertEqual(
            materialize.ready_checkpoint_semantic_sha256(candidate),
            materialize.READY_CHECKPOINT_SEMANTIC_SHA256,
        )

        def forged_sha256(path: Path) -> str:
            if path == ROOT / runner:
                return forged_runner_sha256
            return real_sha256(path)

        with mock.patch.object(
            materialize.contract,
            "sha256_file",
            side_effect=forged_sha256,
        ), mock.patch.object(
            materialize.contract._v23_utility,
            "sha256_file",
            side_effect=forged_sha256,
        ):
            with mock.patch.object(
                materialize,
                "require_frozen_ready_sources",
                return_value=None,
            ):
                materialize.require_ready_checkpoint(ROOT, candidate)
            with self.assertRaisesRegex(
                materialize.ProjectionError,
                "frozen ready source SHA-256 differs",
            ):
                materialize.require_ready_checkpoint(ROOT, candidate)

    def test_exact_goal_inventory_dependency_and_canonical39(self) -> None:
        state = self.projected["goal_execution"]
        inventory = state["dynamic_goal_inventory"][materialize.GOAL_ID]
        self.assertEqual(inventory, materialize._inventory_record(self.seq45["event_sha256"]))
        self.assertEqual(
            state["materialized_child_goal_ids_by_parent"][materialize.PARENT_GOAL_ID][-1],
            materialize.GOAL_ID,
        )
        self.assertEqual(state["goal_document_count"], 28)
        self.assertEqual(state["managed_goal_path_count"], 34)
        self.assertEqual(
            self.seq46["readiness_basis"],
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
        source_snapshot = contract.canonical_binding_snapshot(
            self.source if self.source_mode else self.projected
        )
        self.assertEqual(len(source_snapshot), 39)
        self.assertEqual(
            self.seq45["canonical_binding_snapshot_after"],
            source_snapshot,
        )
        self.assertEqual(
            self.seq46["canonical_binding_snapshot_after"],
            source_snapshot,
        )

    def test_queue_boundary_frontier_and_zero_credit_are_explicit(self) -> None:
        state = self.projected["goal_execution"]
        self.assertEqual(
            contract.canonical_json_sha256(state["artifact_work_queue"]),
            materialize.EXPECTED_QUEUE_SHA256,
        )
        self.assertEqual(
            self.seq45["runtime_after"]["completion_boundary_sha256"],
            materialize.EXPECTED_MATERIALIZED_BOUNDARY_SHA256,
        )
        self.assertEqual(
            self.seq46["runtime_after"]["completion_boundary_sha256"],
            materialize.EXPECTED_READY_BOUNDARY_SHA256,
        )
        self.assertEqual(
            contract.canonical_json_sha256(state["completion_boundary"]),
            materialize.EXPECTED_READY_BOUNDARY_SHA256,
        )
        self.assertEqual(
            self.seq45["runtime_after"]["ready_frontier_goal_ids"],
            materialize.MATERIALIZED_FRONTIER,
        )
        self.assertEqual(
            state["ready_frontier_goal_ids"],
            materialize.READY_FRONTIER,
        )
        scope = self.projected["working_tree_snapshot"]["scope"]
        self.assertIn("no GOAL_STARTED", scope)
        self.assertIn("release credit", scope)
        self.assertFalse(self.projected["current_work"]["release_completion_claimed"])

    def test_goal_scoped_start_gate_contract_is_byte_and_semantic_bound(self) -> None:
        loaded = materialize.load_start_gate_contract(ROOT)
        self.assertEqual(
            self.seq46["implementation_start_gate_contract_binding"],
            materialize.start_gate_contract_binding(),
        )
        self.assertEqual(
            tuple(item["check_id"] for item in loaded["ordered_checks"]),
            materialize.START_GATE_CHECK_IDS,
        )
        commands = "\n".join(item["command"] for item in loaded["ordered_checks"])
        for forbidden in (
            "apps/web",
            "npm ",
            "connectedDebugAndroidTest",
            "validate_walksafe_full_rc_20260713.py",
        ):
            self.assertNotIn(forbidden, commands)

    def test_only_exact_authorized_post_seq44_delta_is_accepted(self) -> None:
        if not self.source_mode:
            self.skipTest("seq44 source bytes have already been add-only superseded")
        reconciliation = materialize.require_authorized_source_delta(
            ROOT,
            self.source,
        )
        self.assertEqual(
            reconciliation,
            self.seq45["source_working_snapshot_reconciliation"],
        )
        real_sha256 = materialize.contract.sha256_file
        unlisted = next(
            path
            for path in self.source["working_tree_snapshot"]["managed_changed_paths"]
            if path not in {item["path"] for item in materialize.AUTHORIZED_SOURCE_DELTAS}
        )

        def drift_one(path: Path) -> str:
            if path == ROOT / unlisted:
                return "f" * 64
            return real_sha256(path)

        with mock.patch.object(
            materialize.contract,
            "sha256_file",
            side_effect=drift_one,
        ):
            with self.assertRaisesRegex(
                materialize.ProjectionError,
                "unlisted controlled-tree drift",
            ):
                materialize.require_authorized_source_delta(ROOT, self.source)

    def test_projection_is_deterministic_and_live_checkpoint_is_unchanged(self) -> None:
        if self.source_mode:
            _, source = materialize.load_exact_source(ROOT)
            projected, seq45, seq46 = materialize.project(ROOT, source)
            self.assertEqual(projected, self.projected)
            self.assertEqual(seq45, self.seq45)
            self.assertEqual(seq46, self.seq46)
        else:
            materialize.require_ready_checkpoint(ROOT, self.projected)
        self.assertEqual(self.checkpoint_path.read_bytes(), self.before_bytes)
        after = self.checkpoint_path.stat()
        self.assertEqual(
            (after.st_dev, after.st_ino),
            (self.before_stat.st_dev, self.before_stat.st_ino),
        )

    def test_hardened_publisher_preflight_retains_the_exact_projection(self) -> None:
        if not self.source_mode:
            self.skipTest("the add-only seq45/46 publication is already complete")
        prepared = publisher.prepare(ROOT)
        try:
            self.assertEqual(prepared.projected, self.projected)
            self.assertEqual(
                prepared.projected_bytes,
                (
                    json.dumps(self.projected, ensure_ascii=False, indent=2)
                    + "\n"
                ).encode("utf-8"),
            )
            prepared.cohort.verify()
        finally:
            prepared.cohort.close()
        self.assertEqual(self.checkpoint_path.read_bytes(), self.before_bytes)

    def test_publisher_retained_cohort_rejects_named_byte_drift(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "input.txt"
            path.write_bytes(b"before")
            cohort = publisher.PinnedCohort.capture(root, ["input.txt"])
            try:
                path.write_bytes(b"after")
                with self.assertRaisesRegex(
                    publisher.PublicationError,
                    "retained pin",
                ):
                    cohort.verify()
            finally:
                cohort.close()

    def test_tampered_source_tail_fails_closed(self) -> None:
        if not self.source_mode:
            self.skipTest("seq44 source bytes have already been add-only superseded")
        _, source = materialize.load_exact_source(ROOT)
        tampered = copy.deepcopy(source)
        tampered["goal_execution"]["transition_history"][-1]["event_sha256"] = "0" * 64
        with self.assertRaisesRegex(materialize.ProjectionError, "exact FP048 completion"):
            materialize.require_exact_source(tampered)

    def test_tampered_r023_binding_fails_closed(self) -> None:
        if not self.source_mode:
            self.skipTest("seq44 source bytes have already been add-only superseded")
        _, source = materialize.load_exact_source(ROOT)
        tampered = copy.deepcopy(source)
        for binding in tampered["canonical_bindings"]:
            if binding.get("role") == "IMPLEMENTATION_BACKLOG":
                binding["file_sha256"] = "0" * 64
                break
        with self.assertRaisesRegex(materialize.ProjectionError, "canonical backlog"):
            materialize.require_static_inputs(ROOT, tampered)

    def test_tampered_goal_contract_fails_closed(self) -> None:
        source = self.source if self.source_mode else self.projected
        real_parser = materialize.goal_graph.frozen_goal.parse_goal

        def parse_wrong(path: Path):
            node, body = real_parser(path)
            wrong = copy.deepcopy(node)
            wrong["start_requires"] = []
            return wrong, body

        with mock.patch.object(
            materialize.goal_graph.frozen_goal,
            "parse_goal",
            side_effect=parse_wrong,
        ):
            with self.assertRaisesRegex(materialize.ProjectionError, "Goal metadata"):
                materialize.require_static_inputs(ROOT, source)

    def test_cli_exposes_preflight_only(self) -> None:
        with redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                materialize.parse_args(["--write"])
        args = materialize.parse_args(["--preflight", "--root", str(ROOT)])
        self.assertTrue(args.preflight)


if __name__ == "__main__":
    unittest.main()

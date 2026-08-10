import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from collections import Counter
from contextlib import contextmanager
from pathlib import Path
from unittest.mock import patch

from scripts import check_walksafe_goal_graph as graph


ROOT = Path(__file__).resolve().parents[1]


class WalkSafeGoalGraphTest(unittest.TestCase):
    def setUp(self) -> None:
        self._anchor_names = tuple(
            name
            for name in (
                "EXPECTED_MANIFEST_SHA256",
                "EXPECTED_INITIAL_EVENT_SHA256",
                "EXPECTED_PACKAGE_ACTIVATION_AUTHORIZATION_SHA256",
                "EXPECTED_PACKAGE_ACTIVATION_EVENT_SHA256",
            )
            if hasattr(graph, name)
        )
        self._anchors = {
            name: getattr(graph, name) for name in self._anchor_names
        }

    def tearDown(self) -> None:
        for name, value in self._anchors.items():
            setattr(graph, name, value)

    def test_current_goal_graph_is_valid(self) -> None:
        self.assertEqual(graph.validate(ROOT, check_continuation=False), [])

    def test_fixture_projects_immutable_prepared_phase(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            state = checkpoint["goal_execution"]
            prepared = state["transition_history"][0]

            self.assertEqual(len(state["transition_history"]), 1)
            self.assertEqual(prepared["event_type"], "PACKAGE_PREPARED")
            self.assertEqual(
                prepared["event_sha256"],
                graph.EXPECTED_INITIAL_EVENT_SHA256,
            )
            self.assertEqual(
                (
                    state["activation_status"],
                    state["package_status"],
                ),
                (
                    "READY_NOT_ACTIVATED",
                    "PREPARED_NOT_ACTIVATED",
                ),
            )
            self.assertEqual(
                state["status_by_goal"],
                prepared["status_changes"],
            )
            self.assertEqual(state["managed_goal_path_count"], 20)
            self.assertEqual(state["goal_document_count"], 14)
            self.assertEqual(
                graph.canonical_binding_snapshot(
                    graph.canonical_binding_map(checkpoint)
                ),
                prepared["canonical_binding_snapshot_after"],
            )
            self.assertEqual(
                graph.validate(root, check_continuation=False),
                [],
            )

    def test_package_has_no_fixed_stage_runtime_fields(self) -> None:
        checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        state = checkpoint["goal_execution"]
        self.assertFalse(graph.PROHIBITED_LINEAR_FIELDS & set(state))
        for relative in state["goal_document_paths"]:
            metadata, _ = graph.parse_goal(ROOT / relative)
            self.assertFalse(graph.PROHIBITED_LINEAR_FIELDS & set(metadata))

    def test_manifest_declares_dynamic_graph_not_a_stage_count(self) -> None:
        manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
        orchestration = manifest["orchestration"]
        self.assertEqual(orchestration["model"], "DEPENDENCY_DAG_READY_FRONTIER")
        self.assertFalse(orchestration["fixed_stage_count"])
        self.assertTrue(orchestration["dynamic_goal_materialization"])
        self.assertFalse(orchestration["standing_control_is_stage"])
        transitions = manifest["transition_contract"]
        self.assertEqual(
            transitions["completed_workstream_change_strategy"],
            "EVENT_SOURCED_AGGREGATE_REOPEN_WITH_WORK_ITEM_SUCCESSORS",
        )
        self.assertEqual(
            transitions["completed_master_change_strategy"],
            "TERMINAL_NEW_PROJECT_PACKAGE_REQUIRED",
        )
        self.assertEqual(
            transitions["execution_resume_event"],
            "WORK_SESSION_RESUMED",
        )
        self.assertEqual(
            transitions["quick_activation_check_ids"],
            list(graph.QUICK_ACTIVATION_CHECK_IDS),
        )
        self.assertEqual(
            transitions["implementation_start_gate_check_ids"],
            list(graph.IMPLEMENTATION_START_GATE_CHECK_IDS),
        )

    def test_manifest_requires_exact_bootstrap_consumed_pair(self) -> None:
        checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
        manifest["policy_gap_contract"].pop(
            "bootstrap_consumed_policy_gap_pairs"
        )

        errors = graph.validate_manifest(
            ROOT,
            checkpoint["goal_execution"],
            manifest,
            graph.discover_package_paths(ROOT),
        )

        self.assertTrue(
            any(
                "bootstrap consumed pair contract differs" in error
                for error in errors
            )
        )

    def test_manifest_requires_exact_v21_supersession_binding(self) -> None:
        checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        state = checkpoint["goal_execution"]
        state["static_plan_manifest_path"] = (
            graph.MANIFEST_RELATIVE.as_posix()
        )
        state["static_plan_manifest_sha256"] = graph.sha256_file(
            ROOT / graph.MANIFEST_RELATIVE
        )
        state["static_plan_version"] = graph.EXPECTED_PLAN_VERSION
        manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
        manifest["supersedes"]["manifest_sha256"] = "f" * 64

        errors = graph.validate_manifest(
            ROOT,
            state,
            manifest,
            graph.discover_package_paths(ROOT),
        )

        self.assertTrue(
            any(
                "superseded v2.1 package binding differs" in error
                for error in errors
            )
        )

    def test_runtime_requires_exact_bootstrap_consumed_pair(self) -> None:
        with self.fixture_root() as root:
            self.refresh_integrity(root)
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            checkpoint["goal_execution"].pop(
                "bootstrap_consumed_policy_gap_pairs",
                None,
            )
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "runtime differs: bootstrap_consumed_policy_gap_pairs"
                in error
                or "bootstrap consumed policy/Gap pair state differs"
                in error
                for error in errors
            )
        )

    def test_prepared_event_requires_exact_bootstrap_consumed_pair(
        self,
    ) -> None:
        with self.fixture_root() as root:
            self.refresh_integrity(root)
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            state = checkpoint["goal_execution"]
            state["bootstrap_consumed_policy_gap_pairs"] = json.loads(
                json.dumps(
                    graph.EXPECTED_BOOTSTRAP_CONSUMED_POLICY_GAP_PAIRS
                )
            )
            event = state["transition_history"][0]
            event["bootstrap_consumed_policy_gap_pairs"] = json.loads(
                json.dumps(
                    graph.EXPECTED_BOOTSTRAP_CONSUMED_POLICY_GAP_PAIRS
                )
            )
            event["bootstrap_consumed_policy_gap_pairs"][0][
                "completion_level"
            ] = "IMPLEMENTED"
            self.save_rehashed_checkpoint(root, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "prepared bootstrap consumed policy/Gap pair contract differs"
                in error
                for error in errors
            )
        )

    def test_prepared_bootstrap_requires_source_evidence_bindings(
        self,
    ) -> None:
        with self.fixture_root() as root:
            self.refresh_integrity(root)
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            state = checkpoint["goal_execution"]
            exact_records = json.loads(
                json.dumps(
                    graph.EXPECTED_BOOTSTRAP_CONSUMED_POLICY_GAP_PAIRS
                )
            )
            state["bootstrap_consumed_policy_gap_pairs"] = exact_records
            event = state["transition_history"][0]
            event["bootstrap_consumed_policy_gap_pairs"] = json.loads(
                json.dumps(exact_records)
            )
            event["canonical_binding_snapshot_after"].pop(
                "EPIC02_PHASE_A_RECORD"
            )
            self.save_rehashed_checkpoint(root, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "bootstrap consumed policy/Gap source bindings are missing"
                in error
                for error in errors
            )
        )

    def test_artifact_graph_has_257_nodes_and_698_dependency_edges(self) -> None:
        checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        bindings = graph.canonical_binding_map(checkpoint)
        self.assertEqual(graph.validate_artifact_graph(ROOT, bindings), [])
        register = graph.load_json(ROOT / bindings["ARTIFACT_REGISTER"]["path"])
        self.assertEqual(len(register["artifacts"]), 257)
        self.assertEqual(
            sum(len(row["trace"]["upstream_types"]) for row in register["artifacts"]),
            698,
        )

    def test_current_artifact_queue_has_exact_partition_and_first_candidate(
        self,
    ) -> None:
        checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        state = checkpoint["goal_execution"]
        manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
        nodes = self.load_nodes(ROOT, manifest, include_dynamic=True)

        errors, queue = graph.derive_artifact_work_queue(
            ROOT,
            graph.canonical_binding_map(checkpoint),
            nodes,
            state["status_by_goal"],
        )

        self.assertEqual(errors, [])
        self.assertEqual(queue, state["artifact_work_queue"])
        self.assertEqual(
            queue["counts_by_status"],
            {
                "INACTIVE": 1,
                "LIVE_GOAL": 0,
                "STRUCTURALLY_DUE": 0,
                "TERMINAL": 104,
                "WAITING_APPLICABILITY": 87,
                "WAITING_TRIGGER": 65,
                "WAITING_UPSTREAM": 0,
            },
        )
        self.assertEqual(
            queue["next_assessment_target_id"],
            "DLV-DES-21",
        )
        self.assertEqual(
            queue["structural_candidate_ids"][0],
            "DLV-DES-21",
        )

    def test_structurally_due_artifact_becomes_materialization_target(
        self,
    ) -> None:
        checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        binding = graph.canonical_binding_map(checkpoint)[
            "ARTIFACT_REGISTER"
        ]
        register = self.load_binding_json(checkpoint, "ARTIFACT_REGISTER")
        register = json.loads(json.dumps(register))
        target = next(
            row
            for row in register["artifacts"]
            if row["artifact_type_code"] == "DLV-DES-21"
        )
        target["state"]["blockers"] = []
        manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
        nodes = self.load_nodes(ROOT, manifest, include_dynamic=True)

        errors, queue = graph.derive_artifact_work_queue_from_register(
            binding,
            register,
            nodes,
            checkpoint["goal_execution"]["status_by_goal"],
        )

        self.assertEqual(
            queue["partition_by_status"]["STRUCTURALLY_DUE"],
            ["DLV-DES-21"],
        )
        self.assertEqual(queue["next_due_target_id"], "DLV-DES-21")
        self.assertEqual(errors, [])

    def test_duplicate_live_artifact_subject_ownership_is_rejected(
        self,
    ) -> None:
        checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        binding = graph.canonical_binding_map(checkpoint)[
            "ARTIFACT_REGISTER"
        ]
        register = self.load_binding_json(checkpoint, "ARTIFACT_REGISTER")
        node = {
            "goal_kind": "WORK_ITEM",
            "work_item_type": "ARTIFACT_WORK",
            "output_subject_ids_by_role": {
                "ARTIFACT_REGISTER": ["DLV-DES-21"],
                "ARTIFACT_CHANGE_LOG": ["DLV-DES-21"],
            },
        }
        nodes = {
            "ARTIFACT-A-R001": dict(node),
            "ARTIFACT-B-R001": dict(node),
        }

        errors, queue = graph.derive_artifact_work_queue_from_register(
            binding,
            register,
            nodes,
            {
                "ARTIFACT-A-R001": "PLANNED",
                "ARTIFACT-B-R001": "READY",
            },
        )

        self.assertEqual(
            queue["partition_by_status"]["LIVE_GOAL"],
            ["DLV-DES-21"],
        )
        self.assertTrue(
            any(
                "duplicate live subject ownership" in error
                for error in errors
            )
        )

    def test_next_due_uses_declared_selection_policy_not_code_order(
        self,
    ) -> None:
        checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        binding = graph.canonical_binding_map(checkpoint)[
            "ARTIFACT_REGISTER"
        ]
        register = json.loads(
            json.dumps(
                self.load_binding_json(checkpoint, "ARTIFACT_REGISTER")
            )
        )
        for row in register["artifacts"]:
            if row["artifact_type_code"] in {
                "DLV-AIML-01",
                "DLV-DES-21",
            }:
                row["state"]["blockers"] = []
        manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
        nodes = self.load_nodes(ROOT, manifest, include_dynamic=True)

        errors, queue = graph.derive_artifact_work_queue_from_register(
            binding,
            register,
            nodes,
            checkpoint["goal_execution"]["status_by_goal"],
        )

        self.assertEqual(
            queue["partition_by_status"]["STRUCTURALLY_DUE"],
            ["DLV-AIML-01", "DLV-DES-21"],
        )
        self.assertEqual(queue["next_due_target_id"], "DLV-DES-21")
        self.assertEqual(errors, [])

    def test_live_artifact_owner_cannot_bypass_reason_eligibility(
        self,
    ) -> None:
        checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        binding = graph.canonical_binding_map(checkpoint)[
            "ARTIFACT_REGISTER"
        ]
        register = self.load_binding_json(checkpoint, "ARTIFACT_REGISTER")
        node = {
            "goal_kind": "WORK_ITEM",
            "work_item_type": "ARTIFACT_WORK",
            "artifact_work_reason": "APPLICABILITY_DECISION",
            "artifact_trigger_evidence_refs": [],
            "output_subject_ids_by_role": {
                "ARTIFACT_REGISTER": ["DLV-DES-21"],
                "ARTIFACT_CHANGE_LOG": ["DLV-DES-21"],
            },
        }

        errors, queue = graph.derive_artifact_work_queue_from_register(
            binding,
            register,
            {"ARTIFACT-INVALID-R001": node},
            {"ARTIFACT-INVALID-R001": "READY"},
        )

        self.assertEqual(
            queue["partition_by_status"]["LIVE_GOAL"],
            ["DLV-DES-21"],
        )
        self.assertTrue(
            any(
                "reason/register lifecycle is incompatible" in error
                for error in errors
            )
        )

    def test_active_event_owner_cannot_bypass_upstream_eligibility(
        self,
    ) -> None:
        binding = {
            "role": "ARTIFACT_REGISTER",
            "document_id": "REGISTER",
            "path": "register.json",
            "file_sha256": "0" * 64,
        }
        register = {
            "artifacts": [
                {
                    "artifact_type_code": "DLV-UPSTREAM",
                    "activation_result": "ACTIVE",
                    "priority": "P1",
                    "state": {
                        "lifecycle_status": "DRAFT",
                        "blockers": ["NOT_COMPLETE"],
                    },
                    "trace": {
                        "upstream_types": [],
                        "downstream_types": ["DLV-TARGET"],
                    },
                },
                {
                    "artifact_type_code": "DLV-TARGET",
                    "activation_result": "ACTIVE",
                    "priority": "P1",
                    "state": {
                        "lifecycle_status": "DRAFT",
                        "blockers": ["EVENT_TRIGGER"],
                    },
                    "trace": {
                        "upstream_types": ["DLV-UPSTREAM"],
                        "downstream_types": [],
                    },
                },
            ]
        }
        node = {
            "goal_kind": "WORK_ITEM",
            "work_item_type": "ARTIFACT_WORK",
            "artifact_work_reason": "ACTIVE_EVENT_UPDATE",
            "artifact_trigger_evidence_refs": ["EVENT-1"],
            "output_subject_ids_by_role": {
                "ARTIFACT_REGISTER": ["DLV-TARGET"],
                "ARTIFACT_CHANGE_LOG": ["DLV-TARGET"],
            },
        }

        errors, queue = graph.derive_artifact_work_queue_from_register(
            binding,
            register,
            {"ARTIFACT-EVENT-R001": node},
            {"ARTIFACT-EVENT-R001": "READY"},
        )

        self.assertEqual(
            queue["partition_by_status"]["LIVE_GOAL"],
            ["DLV-TARGET"],
        )
        self.assertTrue(
            any(
                "bypasses artifact upstream eligibility" in error
                for error in errors
            )
        )

    def test_waiting_applicability_becomes_a_deterministic_assessment(
        self,
    ) -> None:
        binding = {
            "role": "ARTIFACT_REGISTER",
            "document_id": "REGISTER",
            "path": "register.json",
            "file_sha256": "0" * 64,
        }
        register = {
            "artifacts": [
                {
                    "artifact_type_code": "DLV-PENDING-01",
                    "activation_result": "PENDING_EVALUATION",
                    "priority": "P1",
                    "state": {
                        "lifecycle_status": "PLANNED",
                        "blockers": ["PENDING_ACTIVATION_EVALUATION"],
                    },
                    "trace": {
                        "upstream_types": [],
                        "downstream_types": [],
                    },
                }
            ]
        }

        errors, queue = graph.derive_artifact_work_queue_from_register(
            binding,
            register,
            {},
            {},
        )

        self.assertEqual(errors, [])
        self.assertEqual(
            queue["partition_by_status"]["WAITING_APPLICABILITY"],
            ["DLV-PENDING-01"],
        )
        self.assertEqual(
            queue["next_assessment_target_id"],
            "DLV-PENDING-01",
        )
        self.assertEqual(
            queue["next_assessment_required_work_reason"],
            "APPLICABILITY_DECISION",
        )
        self.assertEqual(
            queue["next_assessment_required_outcomes"],
            [
                "MATERIALIZE_ARTIFACT_WORK",
                "CANONICAL_REGISTER_TERMINAL_DISPOSITION",
            ],
        )

    def test_assessment_obligation_defers_rejects_and_accepts_exactly(
        self,
    ) -> None:
        previous_runtime = {
            "artifact_work_queue": {
                "next_assessment_target_id": "DLV-DUE",
                "partition_by_status": {
                    "LIVE_GOAL": [],
                    "STRUCTURALLY_DUE": ["DLV-DUE"],
                },
            },
            "completion_boundary": {
                "internal_runnable_goal_ids": [],
            },
        }
        nodes = {
            "INTERNAL": {
                "goal_kind": "WORK_ITEM",
                "work_item_type": "POLICY_GAP_WORK",
            },
            "ARTIFACT": {
                "goal_kind": "WORK_ITEM",
                "work_item_type": "ARTIFACT_WORK",
                "output_subject_ids_by_role": {
                    "ARTIFACT_REGISTER": ["DLV-DUE"],
                },
            },
        }

        target = graph.artifact_assessment_obligation_target(
            previous_runtime,
            nodes,
            {"INTERNAL": "PLANNED", "ARTIFACT": "PLANNED"},
        )
        self.assertEqual(target, "DLV-DUE")
        self.assertEqual(
            graph.unconsumed_structurally_due_ids(previous_runtime),
            ["DLV-DUE"],
        )
        self.assertFalse(
            graph.artifact_assessment_transition_satisfied(
                target_id=target,
                event_type="GOAL_FOCUS_CHANGED",
                event={},
                nodes=nodes,
                queue_after={
                    "partition_by_status": {
                        "LIVE_GOAL": [],
                        "STRUCTURALLY_DUE": ["DLV-DUE"],
                    }
                },
            )
        )
        self.assertTrue(
            graph.artifact_assessment_transition_satisfied(
                target_id=target,
                event_type="GOAL_MATERIALIZED",
                event={"materialized_goal_id": "ARTIFACT"},
                nodes=nodes,
                queue_after={
                    "partition_by_status": {
                        "LIVE_GOAL": ["DLV-DUE"],
                    }
                },
            )
        )
        consumed_runtime = {
            "artifact_work_queue": {
                "partition_by_status": {
                    "LIVE_GOAL": ["DLV-DUE"],
                    "STRUCTURALLY_DUE": [],
                }
            }
        }
        self.assertEqual(
            graph.unconsumed_structurally_due_ids(consumed_runtime),
            [],
        )
        self.assertTrue(
            graph.artifact_assessment_transition_satisfied(
                target_id=target,
                event_type="CANONICAL_BINDINGS_UPDATED",
                event={},
                nodes=nodes,
                queue_after={
                    "partition_by_status": {
                        "TERMINAL": ["DLV-DUE"],
                        "INACTIVE": [],
                    }
                },
            )
        )

        runnable_runtime = json.loads(json.dumps(previous_runtime))
        runnable_runtime["completion_boundary"][
            "internal_runnable_goal_ids"
        ] = ["INTERNAL"]
        self.assertIsNone(
            graph.artifact_assessment_obligation_target(
                runnable_runtime,
                nodes,
                {"INTERNAL": "READY", "ARTIFACT": "PLANNED"},
            )
        )
        self.assertIsNone(
            graph.artifact_assessment_obligation_target(
                previous_runtime,
                nodes,
                {"INTERNAL": "IN_PROGRESS", "ARTIFACT": "PLANNED"},
            )
        )

    def test_active_event_update_requires_exact_live_direct_bindings(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            evidence_path = root / "evidence.json"
            self.write_json(
                evidence_path,
                {
                    "document_id": "EVENT-EVIDENCE-001",
                    "status": "RECORDED",
                },
            )
            node = {
                "work_item_type": "ARTIFACT_WORK",
                "artifact_work_reason": "ACTIVE_EVENT_UPDATE",
                "artifact_trigger_evidence_refs": [
                    "EVENT-EVIDENCE-001"
                ],
            }
            binding = {
                "document_id": "EVENT-EVIDENCE-001",
                "path": "evidence.json",
                "file_sha256": graph.sha256_file(evidence_path),
            }
            valid_event = {
                "artifact_trigger_evidence_refs": [
                    "EVENT-EVIDENCE-001"
                ],
                "artifact_trigger_evidence_bindings": {
                    "EVENT-EVIDENCE-001": binding
                },
            }

            self.assertEqual(
                graph.validate_artifact_trigger_event_bindings(
                    root,
                    label="test",
                    event=valid_event,
                    node=node,
                ),
                [],
            )
            missing = json.loads(json.dumps(valid_event))
            missing["artifact_trigger_evidence_bindings"] = {}
            self.assertTrue(
                graph.validate_artifact_trigger_event_bindings(
                    root,
                    label="test",
                    event=missing,
                    node=node,
                )
            )
            arbitrary = json.loads(json.dumps(valid_event))
            arbitrary["artifact_trigger_evidence_refs"] = ["ARBITRARY"]
            arbitrary["artifact_trigger_evidence_bindings"] = {
                "ARBITRARY": binding
            }
            self.assertTrue(
                graph.validate_artifact_trigger_event_bindings(
                    root,
                    label="test",
                    event=arbitrary,
                    node={
                        **node,
                        "artifact_trigger_evidence_refs": ["ARBITRARY"],
                    },
                )
            )

    def test_all_68_policy_gap_pairs_are_covered_once(self) -> None:
        checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        bindings = graph.canonical_binding_map(checkpoint)
        mapping_errors, mapping = graph.load_policy_gap_mapping(ROOT, bindings)
        self.assertEqual(mapping_errors, [])
        manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
        nodes = self.load_nodes(ROOT, manifest)
        policies = [
            policy
            for node in nodes.values()
            if node.get("goal_kind") == "WORKSTREAM"
            for policy in node["source_policy_ids"]
        ]
        gaps = [
            gap_id
            for node in nodes.values()
            if node.get("goal_kind") == "WORKSTREAM"
            for gap_id in node["gap_ids"]
        ]
        self.assertEqual(Counter(policies), Counter(mapping.keys()))
        self.assertEqual(Counter(gaps), Counter(mapping.values()))

    def test_effective_policy_scope_matches_the_static_graph_exactly(self) -> None:
        checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        bindings = graph.canonical_binding_map(checkpoint)
        mapping_errors, mapping = graph.load_policy_gap_mapping(ROOT, bindings)
        self.assertEqual(mapping_errors, [])
        self.assertEqual(
            graph.validate_policy_scope_against_mapping(
                ROOT,
                bindings,
                mapping,
            ),
            [],
        )

    def test_new_policy_id_requires_a_successor_package(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            bindings = graph.canonical_binding_map(checkpoint)
            policy_binding = dict(bindings["POLICY_BASELINE"])
            payload = graph.load_json(root / policy_binding["path"])
            payload = json.loads(json.dumps(payload))
            payload["composition"]["approved_feature_id"] = "FP-999"
            relative = (
                "docs/control/baselines/"
                "walksafe-feature-policy-baseline-test-fp999.json"
            )
            path = root / relative
            self.write_json(path, payload)
            policy_binding.update(
                {
                    "path": relative,
                    "file_sha256": graph.sha256_file(path),
                }
            )
            bindings["POLICY_BASELINE"] = policy_binding
            mapping_errors, mapping = graph.load_policy_gap_mapping(
                root,
                bindings,
            )
            self.assertEqual(mapping_errors, [])

            errors = graph.validate_policy_scope_against_mapping(
                root,
                bindings,
                mapping,
            )

            self.assertTrue(
                any(
                    "STATIC_GRAPH_CHANGE_REQUIRES_SUCCESSOR_PACKAGE" in error
                    for error in errors
                )
            )

    def test_policy_gap_pair_identity_is_static(self) -> None:
        checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        bindings = graph.canonical_binding_map(checkpoint)
        mapping_errors, mapping = graph.load_policy_gap_mapping(ROOT, bindings)
        self.assertEqual(mapping_errors, [])
        swapped = dict(mapping)
        swapped["FP-019"], swapped["FP-020"] = (
            swapped["FP-020"],
            swapped["FP-019"],
        )

        errors = graph.validate_policy_scope_against_mapping(
            ROOT,
            bindings,
            swapped,
        )

        self.assertTrue(
            any(
                "pair identity differs" in error
                and "STATIC_GRAPH_CHANGE_REQUIRES_SUCCESSOR_PACKAGE" in error
                for error in errors
            )
        )

    def test_ready_frontier_contains_independent_epic03_branch(self) -> None:
        checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        state = checkpoint["goal_execution"]
        self.assertIn("WS-GOAL-EPIC-03", state["ready_frontier_goal_ids"])
        self.assertNotEqual(
            state["status_by_goal"]["WS-GOAL-EPIC-02"],
            "COMPLETE_AT_TARGET",
        )

    def test_ready_frontier_rejects_a_leaf_under_an_unavailable_ancestor(self) -> None:
        checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
        nodes = self.load_nodes(ROOT, manifest, include_dynamic=True)
        backlog = self.load_binding_json(checkpoint, "IMPLEMENTATION_BACKLOG")
        state = checkpoint["goal_execution"]

        for parent_status, add_blocker in (
            ("PLANNED", False),
            ("BLOCKED", True),
            ("COMPLETE_AT_TARGET", False),
        ):
            with self.subTest(parent_status=parent_status):
                statuses = dict(state["status_by_goal"])
                blockers = dict(state["blockers_by_goal"])
                statuses["WS-GOAL-EPIC-02"] = parent_status
                if add_blocker:
                    blockers["WS-GOAL-EPIC-02"] = [{"blocker_id": "TEST"}]
                calculated = graph.ready_frontier(
                    nodes,
                    statuses,
                    state["materialized_child_goal_ids_by_parent"],
                    blockers,
                    backlog,
                )
                self.assertNotIn("WS-GOAL-EPIC-02-FP-018-R001", calculated)

    def test_canonical_next_action_wins_without_serializing_other_branches(self) -> None:
        checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
        nodes = self.load_nodes(ROOT, manifest, include_dynamic=True)
        backlog = self.load_binding_json(checkpoint, "IMPLEMENTATION_BACKLOG")
        state = checkpoint["goal_execution"]
        calculated = graph.ready_frontier(
            nodes,
            state["status_by_goal"],
            state["materialized_child_goal_ids_by_parent"],
            state["blockers_by_goal"],
            backlog,
        )
        self.assertEqual(
            calculated,
            [
                "WS-GOAL-EPIC-02-FP-004-R001",
                "WS-GOAL-EPIC-03",
                "WS-GOAL-EPIC-12",
            ],
        )

    def test_graph_validator_accepts_more_than_four_top_level_nodes(self) -> None:
        manifest = {
            "goal_graph": {
                "static_nodes": [
                    self.declaration("ROOT", "", "MASTER", 0),
                    *[
                        self.declaration(f"N{index}", "ROOT", "WORKSTREAM", index)
                        for index in range(1, 8)
                    ],
                ]
            }
        }
        nodes = {
            "ROOT": self.node("ROOT", "", "MASTER", 0, [f"N{i}" for i in range(1, 8)]),
            **{
                f"N{index}": self.node(
                    f"N{index}",
                    "ROOT",
                    "WORKSTREAM",
                    index,
                    [],
                )
                for index in range(1, 8)
            },
        }
        old_root = graph.EXPECTED_ROOT_GOAL_ID
        graph.EXPECTED_ROOT_GOAL_ID = "ROOT"
        try:
            self.assertEqual(graph.validate_graph(nodes, manifest), [])
        finally:
            graph.EXPECTED_ROOT_GOAL_ID = old_root

    def test_dependency_cycle_is_rejected(self) -> None:
        nodes = {
            "A": {"start_requires": ["B"], "completion_requires": []},
            "B": {"start_requires": ["A"], "completion_requires": []},
        }
        self.assertTrue(graph.has_cycle(nodes, ("start_requires", "completion_requires")))

    def test_artifact_dependency_cycle_is_rejected(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            bindings = graph.canonical_binding_map(checkpoint)
            binding = bindings["ARTIFACT_REGISTER"]
            register = graph.load_json(root / binding["path"])
            first = register["artifacts"][0]
            second = register["artifacts"][1]
            first_code = first["artifact_type_code"]
            second_code = second["artifact_type_code"]
            first["trace"]["upstream_types"].append(second_code)
            second["trace"]["upstream_types"].append(first_code)
            self.write_json(root / binding["path"], register)
            binding["file_sha256"] = graph.sha256_file(root / binding["path"])

            errors = graph.validate_artifact_graph(root, bindings)

        self.assertTrue(any("cycle" in error for error in errors))

    def test_artifact_upstream_and_downstream_must_be_reverse_references(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            bindings = graph.canonical_binding_map(checkpoint)
            binding = bindings["ARTIFACT_REGISTER"]
            register = graph.load_json(root / binding["path"])
            codes = [row["artifact_type_code"] for row in register["artifacts"]]
            row = next(
                item
                for item in register["artifacts"]
                if item["trace"]["downstream_types"]
            )
            current = row["trace"]["downstream_types"]
            replacement = next(
                code
                for code in codes
                if code != row["artifact_type_code"] and code not in current
            )
            current[0] = replacement
            self.write_json(root / binding["path"], register)
            binding["file_sha256"] = graph.sha256_file(root / binding["path"])

            errors = graph.validate_artifact_graph(root, bindings)

        self.assertTrue(
            any("upstream/downstream reverse references differ" in error for error in errors)
        )

    def test_work_item_cannot_own_another_work_item(self) -> None:
        checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
        nodes = self.load_nodes(ROOT, manifest, include_dynamic=True)
        parent_id = "WS-GOAL-EPIC-02-FP-018-R001"
        nodes["WS-GOAL-TEST-CHILD-R001"] = {
            "goal_id": "WS-GOAL-TEST-CHILD-R001",
            "goal_kind": "WORK_ITEM",
            "parent_goal_id": parent_id,
            "child_goal_ids": [],
            "start_requires": [],
            "completion_requires": [],
        }

        errors = graph.validate_graph(nodes, manifest)

        self.assertTrue(
            any("Work Item parent must be a Workstream or Master" in error for error in errors)
        )

    def test_manifest_and_goal_metadata_drift_is_rejected_after_rehash(self) -> None:
        with self.fixture_root() as root:
            relative = (
                graph.PACKAGE_RELATIVE
                / "workstreams/epic-03-account-admin-security.md"
            ).as_posix()
            path = root / relative
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "priority_rank = 3",
                    "priority_rank = 99",
                    1,
                ),
                encoding="utf-8",
            )
            self.refresh_integrity(root)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(any("metadata differs from manifest" in error for error in errors))

    def test_fixed_stage_field_is_rejected_after_rehash(self) -> None:
        with self.fixture_root() as root:
            relative = (
                graph.PACKAGE_RELATIVE
                / "workstreams/epic-03-account-admin-security.md"
            ).as_posix()
            path = root / relative
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    'parent_goal_id = "WS-GOAL-WALKSAFE-COMPLETION-GRAPH-V2"',
                    'phase_id = "A"\n'
                    'parent_goal_id = "WS-GOAL-WALKSAFE-COMPLETION-GRAPH-V2"',
                    1,
                ),
                encoding="utf-8",
            )
            self.refresh_integrity(root)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(any("fixed-stage fields are prohibited" in error for error in errors))

    def test_ready_frontier_tampering_is_rejected(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            checkpoint["goal_execution"]["ready_frontier_goal_ids"] = [
                "WS-GOAL-EPIC-12"
            ]
            checkpoint["goal_execution"]["transition_history"][0]["runtime_after"][
                "ready_frontier_goal_ids"
            ] = ["WS-GOAL-EPIC-12"]
            self.rehash_event(checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(any("ready frontier differs" in error for error in errors))

    def test_completion_boundary_tampering_is_rejected(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            state = checkpoint["goal_execution"]
            forged = json.loads(json.dumps(state["completion_boundary"]))
            forged["repository_scope_status"] = (
                "COMPLETE_AWAITING_EXTERNAL"
            )
            forged["external_action_packet_id"] = "FORGED"
            state["completion_boundary"] = forged
            state["transition_history"][0]["runtime_after"][
                "completion_boundary"
            ] = forged
            self.rehash_event(checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "completion boundary differs from runnable and external work"
                in error
                for error in errors
            )
        )

    def test_completion_boundary_rejects_external_milestone_with_internal_leaf(
        self,
    ) -> None:
        nodes = {
            "INTERNAL": {
                "goal_kind": "WORK_ITEM",
                "work_item_type": "POLICY_GAP_WORK",
                "parent_goal_id": "",
            },
            "EXTERNAL": {
                "goal_kind": "WORK_ITEM",
                "work_item_type": "FORMAL_TEST_RUN",
                "parent_goal_id": "",
                "source_blocker_ids": ["EXT-1"],
            },
        }
        terminal_queue = {
            "partition_by_status": {
                "LIVE_GOAL": [],
                "STRUCTURALLY_DUE": [],
                "WAITING_APPLICABILITY": [],
                "WAITING_TRIGGER": [],
                "WAITING_UPSTREAM": [],
            }
        }

        errors, boundary = graph.derive_completion_boundary(
            nodes,
            {
                "INTERNAL": "PLANNED",
                "EXTERNAL": "AWAITING_EXTERNAL",
            },
            [],
            {
                "EXTERNAL": [self.typed_external_blocker()]
            },
            terminal_queue,
            package_status="ACTIVE",
        )

        self.assertEqual(errors, [])
        self.assertEqual(boundary["repository_scope_status"], "IN_PROGRESS")
        self.assertEqual(
            boundary["internal_pending_goal_ids"],
            ["INTERNAL"],
        )

    def test_completed_package_boundary_fails_closed_with_pending_work(
        self,
    ) -> None:
        terminal_queue = {
            "partition_by_status": {
                "LIVE_GOAL": [],
                "STRUCTURALLY_DUE": [],
                "WAITING_APPLICABILITY": [],
                "WAITING_TRIGGER": [],
                "WAITING_UPSTREAM": [],
            }
        }

        errors, boundary = graph.derive_completion_boundary(
            {
                "INTERNAL": {
                    "goal_kind": "WORK_ITEM",
                    "work_item_type": "POLICY_GAP_WORK",
                    "parent_goal_id": "",
                }
            },
            {"INTERNAL": "PLANNED"},
            [],
            {},
            terminal_queue,
            package_status="COMPLETED",
        )

        self.assertEqual(
            boundary["repository_scope_status"],
            "IN_PROGRESS",
        )
        self.assertEqual(boundary["project_status"], "NOT_COMPLETE")
        self.assertTrue(
            any(
                "completed package still has" in error
                for error in errors
            )
        )

    def test_completion_boundary_rejects_external_milestone_with_artifact_work(
        self,
    ) -> None:
        nodes = {
            "EXTERNAL": {
                "goal_kind": "WORK_ITEM",
                "work_item_type": "FORMAL_TEST_RUN",
                "parent_goal_id": "",
                "source_blocker_ids": ["EXT-1"],
            }
        }
        artifact_queue = {
            "partition_by_status": {
                "LIVE_GOAL": [],
                "STRUCTURALLY_DUE": [],
                "WAITING_APPLICABILITY": [],
                "WAITING_TRIGGER": ["DLV-DES-21"],
                "WAITING_UPSTREAM": [],
            }
        }

        errors, boundary = graph.derive_completion_boundary(
            nodes,
            {"EXTERNAL": "AWAITING_EXTERNAL"},
            [],
            {
                "EXTERNAL": [self.typed_external_blocker()]
            },
            artifact_queue,
            package_status="ACTIVE",
        )

        self.assertEqual(errors, [])
        self.assertEqual(boundary["repository_scope_status"], "IN_PROGRESS")
        self.assertEqual(
            boundary["artifact_pending_target_ids"],
            ["DLV-DES-21"],
        )

    def test_completion_boundary_allows_milestone_only_for_external_remainder(
        self,
    ) -> None:
        nodes = {
            "EXTERNAL": {
                "goal_kind": "WORK_ITEM",
                "work_item_type": "FORMAL_TEST_RUN",
                "parent_goal_id": "",
                "source_blocker_ids": ["EXT-1"],
            }
        }
        empty_queue = {
            "partition_by_status": {
                "LIVE_GOAL": [],
                "STRUCTURALLY_DUE": [],
                "WAITING_APPLICABILITY": [],
                "WAITING_TRIGGER": [],
                "WAITING_UPSTREAM": [],
            }
        }

        errors, boundary = graph.derive_completion_boundary(
            nodes,
            {"EXTERNAL": "AWAITING_EXTERNAL"},
            [],
            {
                "EXTERNAL": [self.typed_external_blocker()]
            },
            empty_queue,
            package_status="ACTIVE",
        )

        self.assertEqual(errors, [])
        self.assertEqual(
            boundary["repository_scope_status"],
            "COMPLETE_AWAITING_EXTERNAL",
        )
        self.assertEqual(boundary["project_status"], "NOT_COMPLETE")
        self.assertTrue(boundary["external_action_packet_id"])

    def test_external_goal_without_typed_request_blocker_fails_closed(
        self,
    ) -> None:
        nodes = {
            "EXTERNAL": {
                "goal_kind": "WORK_ITEM",
                "work_item_type": "FORMAL_TEST_RUN",
                "parent_goal_id": "",
                "source_blocker_ids": [],
            }
        }
        empty_queue = {
            "partition_by_status": {
                "LIVE_GOAL": [],
                "STRUCTURALLY_DUE": [],
                "WAITING_APPLICABILITY": [],
                "WAITING_TRIGGER": [],
                "WAITING_UPSTREAM": [],
            }
        }

        errors, boundary = graph.derive_completion_boundary(
            nodes,
            {"EXTERNAL": "READY"},
            ["EXTERNAL"],
            {},
            empty_queue,
            package_status="ACTIVE",
        )

        self.assertEqual(boundary["repository_scope_status"], "IN_PROGRESS")
        self.assertTrue(
            any(
                "lacks an active typed EXTERNAL request blocker" in error
                for error in errors
            )
        )

    def test_typed_request_requires_identity_action_roles_and_authority(
        self,
    ) -> None:
        record = {
            "blocker_id": "EXT-1",
            "blocks_goal_id": "GOAL-1",
            "owner": "EXTERNAL",
            "request_kind": "EXTERNAL_ACTION_EVIDENCE",
            "request_key": "REQUEST-1",
            "requested_action": "실기기 시험을 수행한다.",
            "required_resolution_evidence_roles": [
                "BLOCKER_RESOLUTION::EXT-1"
            ],
            "authority_requirement": "EXTERNAL_ATTESTATION",
            "request_event_id": "EVENT-1",
            "target_goal_path": "goal.md",
            "target_goal_content_sha256": "1" * 64,
            "target_work_item_type": "FORMAL_TEST_RUN",
            "blocker_snapshot_sha256": "2" * 64,
        }
        record["request_key"] = graph.deterministic_request_key(record)

        self.assertEqual(
            graph.validate_typed_request_record(record),
            [],
        )
        missing_roles = json.loads(json.dumps(record))
        missing_roles["required_resolution_evidence_roles"] = []
        self.assertTrue(
            graph.validate_typed_request_record(missing_roles)
        )
        wrong_owner_contract = json.loads(json.dumps(record))
        wrong_owner_contract["owner"] = "USER"
        self.assertTrue(
            graph.validate_typed_request_record(wrong_owner_contract)
        )
        for field in ("blocker_id", "request_key", "request_event_id"):
            blank_identity = json.loads(json.dumps(record))
            blank_identity[field] = "   "
            self.assertTrue(
                graph.validate_typed_request_record(blank_identity),
                field,
            )
        for field in (
            "blocker_id",
            "requested_action",
            "request_event_id",
        ):
            unnormalized = json.loads(json.dumps(record))
            unnormalized[field] = f" {unnormalized[field]} "
            if field == "blocker_id":
                unnormalized["required_resolution_evidence_roles"] = [
                    f"BLOCKER_RESOLUTION::{unnormalized[field]}"
                ]
            unnormalized["request_key"] = (
                graph.deterministic_request_key(unnormalized)
            )
            self.assertTrue(
                graph.validate_typed_request_record(unnormalized),
                field,
            )
        blank_question = {
            **record,
            "owner": "USER",
            "request_kind": "USER_DECISION",
            "authority_requirement": "USER_AUTHORIZATION",
            "prompt": "   ",
        }
        self.assertTrue(
            any(
                "question is blank" in error
                for error in graph.validate_typed_request_record(
                    blank_question
                )
            )
        )
        unnormalized_question = {
            **blank_question,
            "prompt": " 사용자 결정을 승인할까요? ",
        }
        unnormalized_question["request_key"] = (
            graph.deterministic_request_key(unnormalized_question)
        )
        self.assertTrue(
            graph.validate_typed_request_record(unnormalized_question)
        )
        same_logical_request = json.loads(json.dumps(record))
        same_logical_request["blocker_id"] = "EXT-2"
        same_logical_request["request_event_id"] = "EVENT-2"
        same_logical_request["required_resolution_evidence_roles"] = [
            "BLOCKER_RESOLUTION::EXT-2"
        ]
        self.assertEqual(
            graph.deterministic_request_key(same_logical_request),
            record["request_key"],
        )
        changed_action = json.loads(json.dumps(record))
        changed_action["requested_action"] = "다른 외부 시험을 수행한다."
        self.assertNotEqual(
            graph.deterministic_request_key(changed_action),
            record["request_key"],
        )
        whitespace_alias = json.loads(json.dumps(record))
        whitespace_alias["requested_action"] = (
            f" {record['requested_action']} "
        )
        whitespace_alias["request_key"] = (
            graph.deterministic_request_key(whitespace_alias)
        )
        self.assertNotEqual(
            whitespace_alias["request_key"],
            record["request_key"],
        )
        self.assertTrue(
            graph.validate_typed_request_record(whitespace_alias)
        )

    def test_request_key_cannot_overlap_between_user_and_external_owners(
        self,
    ) -> None:
        nodes = {
            "USER-GOAL": {
                "goal_kind": "WORK_ITEM",
                "work_item_type": "POLICY_GAP_WORK",
                "parent_goal_id": "",
            },
            "EXTERNAL-GOAL": {
                "goal_kind": "WORK_ITEM",
                "work_item_type": "FORMAL_TEST_RUN",
                "parent_goal_id": "",
                "source_blocker_ids": ["EXT-1"],
            },
        }
        shared = {
            "request_key": "SHARED-REQUEST",
            "requested_action": "요청을 처리한다.",
            "request_event_id": "EVENT-1",
            "target_goal_path": "goal.md",
            "target_goal_content_sha256": "1" * 64,
            "target_work_item_type": "POLICY_GAP_WORK",
            "blocker_snapshot_sha256": "2" * 64,
        }
        user = {
            **shared,
            "blocker_id": "USER-1",
            "blocks_goal_id": "USER-GOAL",
            "owner": "USER",
            "request_kind": "USER_DECISION",
            "required_resolution_evidence_roles": [
                "BLOCKER_RESOLUTION::USER-1"
            ],
            "authority_requirement": "USER_AUTHORIZATION",
        }
        external = {
            **shared,
            "blocker_id": "EXT-1",
            "blocks_goal_id": "EXTERNAL-GOAL",
            "owner": "EXTERNAL",
            "request_kind": "EXTERNAL_ACTION_EVIDENCE",
            "required_resolution_evidence_roles": [
                "BLOCKER_RESOLUTION::EXT-1"
            ],
            "authority_requirement": "EXTERNAL_ATTESTATION",
            "target_work_item_type": "FORMAL_TEST_RUN",
        }
        empty_queue = {
            "partition_by_status": {
                "LIVE_GOAL": [],
                "STRUCTURALLY_DUE": [],
                "WAITING_APPLICABILITY": [],
                "WAITING_TRIGGER": [],
                "WAITING_UPSTREAM": [],
            }
        }

        errors, _ = graph.derive_completion_boundary(
            nodes,
            {
                "USER-GOAL": "AWAITING_USER",
                "EXTERNAL-GOAL": "AWAITING_EXTERNAL",
            },
            [],
            {
                "USER-GOAL": [user],
                "EXTERNAL-GOAL": [external],
            },
            empty_queue,
            package_status="ACTIVE",
        )

        self.assertTrue(
            any(
                "duplicate request identity across owners" in error
                for error in errors
            )
        )

    def test_pending_questions_are_exactly_derived_from_user_requests(
        self,
    ) -> None:
        blockers = {
            "GOAL-1": [
                {
                    "blocker_id": "USER-1",
                    "owner": "USER",
                    "request_key": "REQUEST-USER-1",
                    "prompt": "배포를 승인할까요?",
                }
            ],
            "GOAL-2": [
                {
                    "blocker_id": "EXT-1",
                    "owner": "EXTERNAL",
                    "request_key": "REQUEST-EXT-1",
                    "prompt": "외부 시험을 수행한다.",
                }
            ],
        }

        self.assertEqual(
            graph.pending_user_questions(blockers),
            [
                {
                    "request_key": "REQUEST-USER-1",
                    "goal_id": "GOAL-1",
                    "blocker_id": "USER-1",
                    "question": "배포를 승인할까요?",
                    "delivery_status": "READY_FOR_USER",
                }
            ],
        )
        self.assertEqual(
            graph.pending_user_questions(
                blockers,
                delivery_deferred=True,
            )[0]["delivery_status"],
            "DEFERRED_INTERNAL_FRONTIER",
        )

    def test_blocker_request_targets_focus_without_preempting_other_ready(
        self,
    ) -> None:
        previous_runtime = {"focus_goal_id": "FOCUS"}
        ready_goal_ids = ["FOCUS", "OTHER"]

        self.assertTrue(
            graph.blocker_request_targets_deterministic_focus(
                "FOCUS",
                previous_runtime,
                ready_goal_ids,
            )
        )
        self.assertFalse(
            graph.blocker_request_targets_deterministic_focus(
                "OTHER",
                previous_runtime,
                ready_goal_ids,
            )
        )
        nodes = {
            goal_id: {
                "goal_kind": "WORK_ITEM",
                "work_item_type": "POLICY_GAP_WORK",
                "parent_goal_id": "",
                "start_requires": [],
            }
            for goal_id in ("FOCUS", "OTHER")
        }
        self.assertEqual(
            graph.ready_frontier(
                nodes,
                {
                    "FOCUS": "AWAITING_USER",
                    "OTHER": "READY",
                },
                {},
                {"FOCUS": [{"blocker_id": "USER-1"}]},
                {},
            ),
            ["OTHER"],
        )

    def test_external_action_packet_is_required_only_on_entry_or_basis_change(
        self,
    ) -> None:
        first = {
            "repository_scope_status": "COMPLETE_AWAITING_EXTERNAL",
            "external_action_request_basis_sha256": "1" * 64,
        }
        same = json.loads(json.dumps(first))
        changed = {
            **first,
            "external_action_request_basis_sha256": "2" * 64,
        }

        self.assertTrue(
            graph.external_action_packet_is_required({}, first)
        )
        self.assertFalse(
            graph.external_action_packet_is_required(first, same)
        )
        self.assertTrue(
            graph.external_action_packet_is_required(first, changed)
        )
        self.assertFalse(
            graph.external_action_packet_is_required(
                first,
                {
                    "repository_scope_status": "IN_PROGRESS",
                    "external_action_request_basis_sha256": "2" * 64,
                },
            )
        )

    def test_external_action_packet_binds_exact_basis_and_is_add_only(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            event_id = "EVENT-EXTERNAL-PACKET-001"
            blocker = {
                "blocker_id": "EXT-1",
                "blocks_goal_id": "GOAL-1",
                "owner": "EXTERNAL",
                "request_kind": "EXTERNAL_ACTION_EVIDENCE",
                "request_key": "REQUEST-EXT-1",
                "requested_action": "실기기 시험을 수행한다.",
                "required_resolution_evidence_roles": [
                    "BLOCKER_RESOLUTION::EXT-1"
                ],
                "authority_requirement": "EXTERNAL_ATTESTATION",
                "request_event_id": "EVENT-BLOCKER-1",
                "target_goal_path": "goal.md",
                "target_goal_content_sha256": "1" * 64,
                "target_work_item_type": "FORMAL_TEST_RUN",
                "blocker_snapshot_sha256": "2" * 64,
                "created_at": "2026-07-24T20:00:00+09:00",
            }
            blocker["request_key"] = graph.deterministic_request_key(
                blocker
            )
            blockers = {"GOAL-1": [blocker]}
            basis = graph.external_action_request_basis(blockers)
            basis_hash = graph.canonical_json_sha256(basis)
            packet_id = (
                "WS-EXTERNAL-ACTION-PACKET-"
                + basis_hash[:16].upper()
            )
            packet_path = (
                root
                / "docs/control/execution/goal-gates"
                / event_id
                / "external-action-packet.json"
            )
            self.write_json(
                packet_path,
                {
                    "schema_version": "1.0",
                    "document_id": packet_id,
                    "evidence_type": "EXTERNAL_ACTION_PACKET",
                    "status": "ISSUED_NOT_EVIDENCE",
                    "package_id": graph.EXPECTED_PACKAGE_ID,
                    "static_plan_manifest_sha256": "3" * 64,
                    "target_transition_event_id": event_id,
                    "previous_transition_event_sha256": "4" * 64,
                    "request_basis": basis,
                    "request_basis_sha256": basis_hash,
                    "issued_at": "2026-07-24T20:01:00+09:00",
                },
            )
            binding = {
                "document_id": packet_id,
                "path": str(packet_path.relative_to(root)),
                "file_sha256": graph.sha256_file(packet_path),
            }
            event = {
                "event_id": event_id,
                "static_plan_manifest_sha256": "3" * 64,
                "previous_event_sha256": "4" * 64,
                "external_action_packet_binding": binding,
            }
            boundary = {
                "external_action_packet_id": packet_id,
                "external_action_request_basis_sha256": basis_hash,
            }
            used_paths: set[str] = set()
            used_document_ids: set[str] = set()
            previous_occurred_at = graph.parse_iso_datetime(
                "2026-07-24T19:59:00+09:00"
            )

            self.assertEqual(
                graph.validate_external_action_packet(
                    root,
                    label="test",
                    event=event,
                    blockers_after=blockers,
                    expected_boundary=boundary,
                    occurred_at=graph.parse_iso_datetime(
                        "2026-07-24T20:02:00+09:00"
                    ),
                    previous_occurred_at=previous_occurred_at,
                    used_packet_paths=used_paths,
                    used_packet_document_ids=used_document_ids,
                ),
                [],
            )
            reused_errors = graph.validate_external_action_packet(
                root,
                label="test",
                event=event,
                blockers_after=blockers,
                expected_boundary=boundary,
                occurred_at=graph.parse_iso_datetime(
                    "2026-07-24T20:02:00+09:00"
                ),
                previous_occurred_at=previous_occurred_at,
                used_packet_paths=used_paths,
                used_packet_document_ids=used_document_ids,
            )
            self.assertTrue(
                any("path is reused" in error for error in reused_errors)
            )
            self.assertTrue(
                any(
                    "document ID is reused" in error
                    for error in reused_errors
                )
            )
            missing_errors = graph.validate_external_action_packet(
                root,
                label="test",
                event={
                    **event,
                    "external_action_packet_binding": None,
                },
                blockers_after=blockers,
                expected_boundary=boundary,
                occurred_at=graph.parse_iso_datetime(
                    "2026-07-24T20:02:00+09:00"
                ),
                previous_occurred_at=previous_occurred_at,
                used_packet_paths=set(),
                used_packet_document_ids=set(),
            )
            self.assertTrue(missing_errors)
            stale_event = {
                **event,
                "event_id": "EVENT-EXTERNAL-PACKET-002",
            }
            stale_errors = graph.validate_external_action_packet(
                root,
                label="test",
                event=stale_event,
                blockers_after=blockers,
                expected_boundary=boundary,
                occurred_at=graph.parse_iso_datetime(
                    "2026-07-24T20:02:00+09:00"
                ),
                previous_occurred_at=previous_occurred_at,
                used_packet_paths=set(),
                used_packet_document_ids=set(),
            )
            self.assertTrue(
                any(
                    "path is not event-scoped" in error
                    for error in stale_errors
                )
            )
            wrong_roles = json.loads(json.dumps(blockers))
            wrong_roles["GOAL-1"][0][
                "required_resolution_evidence_roles"
            ] = []
            role_errors = graph.validate_external_action_packet(
                root,
                label="test",
                event=event,
                blockers_after=wrong_roles,
                expected_boundary=boundary,
                occurred_at=graph.parse_iso_datetime(
                    "2026-07-24T20:02:00+09:00"
                ),
                previous_occurred_at=previous_occurred_at,
                used_packet_paths=set(),
                used_packet_document_ids=set(),
            )
            self.assertTrue(
                any("semantics differ" in error for error in role_errors)
            )
            early_packet = graph.load_json(packet_path)
            early_packet["issued_at"] = "2026-07-24T19:58:00+09:00"
            self.write_json(packet_path, early_packet)
            early_event = {
                **event,
                "external_action_packet_binding": {
                    **binding,
                    "file_sha256": graph.sha256_file(packet_path),
                },
            }
            early_errors = graph.validate_external_action_packet(
                root,
                label="test",
                event=early_event,
                blockers_after=blockers,
                expected_boundary=boundary,
                occurred_at=graph.parse_iso_datetime(
                    "2026-07-24T20:02:00+09:00"
                ),
                previous_occurred_at=previous_occurred_at,
                used_packet_paths=set(),
                used_packet_document_ids=set(),
            )
            self.assertTrue(
                any("semantics differ" in error for error in early_errors)
            )
            early_packet["issued_at"] = "2026-07-24T20:01:00+09:00"
            self.write_json(packet_path, early_packet)
            forged_packet = graph.load_json(packet_path)
            forged_packet["request_basis_sha256"] = "0" * 64
            self.write_json(packet_path, forged_packet)
            forged_event = {
                **event,
                "external_action_packet_binding": {
                    **binding,
                    "file_sha256": graph.sha256_file(packet_path),
                },
            }
            hash_errors = graph.validate_external_action_packet(
                root,
                label="test",
                event=forged_event,
                blockers_after=blockers,
                expected_boundary=boundary,
                occurred_at=graph.parse_iso_datetime(
                    "2026-07-24T20:02:00+09:00"
                ),
                previous_occurred_at=previous_occurred_at,
                used_packet_paths=set(),
                used_packet_document_ids=set(),
            )
            self.assertTrue(
                any("semantics differ" in error for error in hash_errors)
            )

    def test_external_action_packet_cannot_be_completion_evidence(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            packet_path = root / "packet.json"
            self.write_json(
                packet_path,
                {
                    "document_id": "PACKET-1",
                    "evidence_type": "EXTERNAL_ACTION_PACKET",
                    "status": "ISSUED_NOT_EVIDENCE",
                },
            )
            binding = {
                "document_id": "PACKET-1",
                "path": "packet.json",
                "file_sha256": graph.sha256_file(packet_path),
            }

            self.assertTrue(
                graph.completion_references_include_external_action_packet(
                    root,
                    ["EXTERNAL_PACKET"],
                    {"EXTERNAL_PACKET": binding},
                )
            )
            direct_errors = graph.completion_reference_errors(
                root,
                label="test",
                references=["PACKET-1"],
                bindings={},
                direct_packet_document_ids={"PACKET-1"},
            )
            self.assertTrue(
                any(
                    "must all resolve to canonical bindings" in error
                    for error in direct_errors
                )
            )
            self.assertTrue(
                any(
                    "cannot be used as completion evidence" in error
                    for error in direct_errors
                )
            )

    def test_focus_with_unmet_dependency_is_rejected(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            state = checkpoint["goal_execution"]
            state["focus_goal_id"] = "WS-GOAL-EPIC-04"
            state["focus_goal_path"] = (
                graph.PACKAGE_RELATIVE
                / "workstreams/epic-04-navigation-arrival-deviation.md"
            ).as_posix()
            state["focus_work_item_id"] = ""
            event = state["transition_history"][0]
            event["runtime_after"]["focus_goal_id"] = state["focus_goal_id"]
            event["runtime_after"]["focus_goal_path"] = state["focus_goal_path"]
            event["runtime_after"]["focus_work_item_id"] = ""
            self.rehash_event(checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(any("focus is not the deterministic ready selection" in error for error in errors))

    def test_policy_duplication_is_rejected_after_integrity_refresh(self) -> None:
        with self.fixture_root() as root:
            relative = (
                graph.PACKAGE_RELATIVE
                / "workstreams/epic-03-account-admin-security.md"
            ).as_posix()
            path = root / relative
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    '"FP-047"',
                    '"FP-018"',
                    1,
                ),
                encoding="utf-8",
            )
            self.refresh_integrity(root)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(any("ordered policies differ" in error for error in errors))
        self.assertTrue(any("not covered exactly once" in error for error in errors))

    def test_workstream_target_change_requires_successor_package(self) -> None:
        checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
        nodes = self.load_nodes(ROOT, manifest)
        bindings = graph.canonical_binding_map(checkpoint)
        backlog = graph.load_json(ROOT / bindings["IMPLEMENTATION_BACKLOG"]["path"])
        _, mapping = graph.load_policy_gap_mapping(ROOT, bindings)
        backlog["epics"][0]["target_completion_level"] = (
            "VERIFICATION_COMPLETE"
        )

        errors = graph.validate_workstreams_against_backlog(
            nodes,
            backlog,
            mapping,
        )

        self.assertTrue(
            any(
                "STATIC_GRAPH_CHANGE_REQUIRES_SUCCESSOR_PACKAGE" in error
                for error in errors
            )
        )

    def test_epic12_dependency_change_requires_successor_package(self) -> None:
        checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
        nodes = self.load_nodes(ROOT, manifest)
        bindings = graph.canonical_binding_map(checkpoint)
        backlog = graph.load_json(ROOT / bindings["IMPLEMENTATION_BACKLOG"]["path"])
        _, mapping = graph.load_policy_gap_mapping(ROOT, bindings)
        epic12 = next(
            epic for epic in backlog["epics"] if epic["epic_id"] == "EPIC-12"
        )
        epic12["dependencies"] = epic12["dependencies"][:-1]

        errors = graph.validate_workstreams_against_backlog(
            nodes,
            backlog,
            mapping,
        )

        self.assertTrue(
            any(
                "EPIC-12: completion requirements changed" in error
                for error in errors
            )
        )

    def test_formal_and_release_boundary_cannot_be_promoted(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            checkpoint["approved_state"]["formal_test_not_run_count"] = 0
            checkpoint["approved_state"]["remaining_gate_count"] = 0
            checkpoint["approved_state"]["remaining_gates_waived"] = True
            checkpoint["approved_state"]["release_status"] = "ELIGIBLE"
            checkpoint["verification_boundary"]["actual_device_test_status"] = "PASS"
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(any("formal tests were promoted" in error for error in errors))
        self.assertTrue(any("must not be waived" in error for error in errors))
        self.assertTrue(any("release eligibility was promoted" in error for error in errors))

    def test_unactivated_graph_cannot_contain_in_progress_work(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            state = checkpoint["goal_execution"]
            state["status_by_goal"][state["focus_goal_id"]] = "IN_PROGRESS"
            state["transition_history"][0]["status_changes"][
                state["focus_goal_id"]
            ] = "IN_PROGRESS"
            self.rehash_event(checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(any("unactivated Goal graph" in error for error in errors))

    def test_v21_supersession_binding_cannot_be_removed(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            event = checkpoint["goal_execution"]["transition_history"][0]
            event["supersedes_event_sha256"] = ""
            self.rehash_event(checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any("v2.1 supersession binding" in error for error in errors)
        )

    def test_event_hash_tampering_is_rejected(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            checkpoint["goal_execution"]["transition_history"][0]["to_status"] = "PLANNED"
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(any("event SHA-256 differs" in error for error in errors))

    def test_protected_goal_change_is_rejected(self) -> None:
        with self.fixture_root() as root:
            path = root / (
                graph.PACKAGE_RELATIVE
                / "workstreams/epic-05-object-detection-safety.md"
            )
            path.write_text(path.read_text(encoding="utf-8") + "\n변조\n", encoding="utf-8")

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(any("protected file content changed" in error for error in errors))

    def test_active_package_undeclared_binary_file_is_rejected(self) -> None:
        with self.fixture_root() as root:
            path = root / graph.PACKAGE_RELATIVE / "undeclared.bin"
            path.write_bytes(b"\x00undeclared")

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "Goal graph managed path set differs" in error
                for error in errors
            )
        )

    def test_missing_goal_is_rejected_even_if_runtime_path_list_is_edited(self) -> None:
        with self.fixture_root() as root:
            relative = (
                graph.PACKAGE_RELATIVE
                / "workstreams/epic-05-object-detection-safety.md"
            ).as_posix()
            (root / relative).unlink()
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            state = checkpoint["goal_execution"]
            state["managed_goal_paths"].remove(relative)
            state["goal_document_paths"].remove(relative)
            state["managed_goal_path_count"] -= 1
            state["goal_document_count"] -= 1
            state["status_by_goal"].pop("WS-GOAL-EPIC-05")
            state["transition_history"][0]["status_changes"].pop("WS-GOAL-EPIC-05")
            self.rehash_event(checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(any("protected file is missing" in error for error in errors))
        self.assertTrue(any("static Goal document set is incomplete" in error for error in errors))

    def test_policy_gap_work_item_binds_exactly_one_pair(self) -> None:
        checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
        nodes = self.load_nodes(ROOT, manifest, include_dynamic=True)
        node = nodes[checkpoint["goal_execution"]["focus_goal_id"]]
        self.assertEqual(node["source_policy_ids"], ["FP-004"])
        self.assertEqual(node["gap_ids"], ["GAP-013"])

    def test_dynamic_template_covers_all_required_node_types(self) -> None:
        manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
        types = {
            item["work_item_type"] for item in manifest["dynamic_node_contracts"]
        }
        self.assertEqual(
            types,
            {
                "POLICY_GAP_WORK",
                "ARTIFACT_WORK",
                "INTEGRATION_CANDIDATE",
                "FORMAL_TEST_RUN",
                "RELEASE_GATE",
                "RELEASE_DECISION",
                "DEPLOYMENT_DELIVERY_EVENT",
                "OPERATION_EVENT",
                "HANDOVER_CLOSURE_EVENT",
                "BLOCKER_OR_EXTERNAL_RECEIPT",
            },
        )

    def test_external_work_types_declare_fail_closed_start_contracts(self) -> None:
        manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
        contracts = graph.dynamic_contract_map(manifest)

        self.assertEqual(
            contracts["FORMAL_TEST_RUN"]["required_start_evidence_roles"],
            [
                "TEST_PLAN_APPROVAL_RECEIPT",
                "INTEGRATION_CANDIDATE_MANIFEST",
            ],
        )
        self.assertEqual(
            contracts["RELEASE_DECISION"]["required_start_goal_type_counts"],
            {
                "INTEGRATION_CANDIDATE": 1,
                "FORMAL_TEST_RUN": 1,
                "RELEASE_GATE": 5,
            },
        )
        self.assertEqual(
            contracts["DEPLOYMENT_DELIVERY_EVENT"]["allowed_parent_scope"],
            "MASTER",
        )

    def test_external_attestation_history_accepts_prior_and_current_hashes(
        self,
    ) -> None:
        role = "TEST_PLAN_APPROVAL_RECEIPT"
        first = "1" * 64
        second = "2" * 64
        with patch.dict(
            graph.legacy.EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE,
            {},
            clear=True,
        ), patch.dict(
            graph.legacy.EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_HISTORY_BY_ROLE,
            {role: (first, second)},
            clear=True,
        ):
            self.assertEqual(
                graph.legacy.validate_external_attestation_anchor(role, first),
                [],
            )
            self.assertEqual(
                graph.legacy.validate_external_attestation_anchor(role, second),
                [],
            )
            self.assertTrue(
                graph.legacy.validate_external_attestation_anchor(role, "3" * 64)
            )

    def test_scoped_gap_diff_does_not_fan_out_and_mixed_global_diff_does(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            before_path = root / "before.json"
            after_path = root / "after.json"
            before = {
                "assessment_method": {"rule": "approved policy"},
                "assessments": [
                    {
                        "gap_id": "GAP-027",
                        "source_policy_id": "FP-018",
                        "status": "MISSING",
                    },
                    {
                        "gap_id": "GAP-028",
                        "source_policy_id": "FP-019",
                        "status": "MISSING",
                    },
                ],
            }
            after = json.loads(json.dumps(before))
            after["assessments"][0]["status"] = "PARTIAL"
            after["reassessment_scope"] = {
                "directly_reassessed_gap_ids": ["GAP-027"],
                "impact_reviewed_gap_ids": [],
                "reviewed_gap_ids": ["GAP-027"],
                "carried_forward_gap_ids": ["GAP-028"],
                "carried_forward_gap_count": 1,
            }
            self.write_json(before_path, before)
            self.write_json(after_path, after)
            before_binding = {
                "role": "IMPLEMENTATION_GAP",
                "document_id": "BEFORE",
                "path": "before.json",
                "file_sha256": graph.sha256_file(before_path),
            }
            after_binding = {
                "role": "IMPLEMENTATION_GAP",
                "document_id": "AFTER",
                "path": "after.json",
                "file_sha256": graph.sha256_file(after_path),
            }
            errors, subjects = graph.canonical_changed_subject_ids_by_role(
                root,
                changed_roles=["IMPLEMENTATION_GAP"],
                bindings_before={"IMPLEMENTATION_GAP": before_binding},
                bindings_after={"IMPLEMENTATION_GAP": after_binding},
            )
            self.assertEqual(errors, [])
            self.assertEqual(
                subjects["IMPLEMENTATION_GAP"],
                ["FP-018", "GAP-027"],
            )

            after["assessment_method"] = {"rule": "different global rule"}
            self.write_json(after_path, after)
            after_binding["file_sha256"] = graph.sha256_file(after_path)
            errors, subjects = graph.canonical_changed_subject_ids_by_role(
                root,
                changed_roles=["IMPLEMENTATION_GAP"],
                bindings_before={"IMPLEMENTATION_GAP": before_binding},
                bindings_after={"IMPLEMENTATION_GAP": after_binding},
            )
            self.assertEqual(errors, [])
            self.assertIn("*", subjects["IMPLEMENTATION_GAP"])

    def test_real_gap_successors_do_not_turn_report_provenance_into_global_change(
        self,
    ) -> None:
        for before_revision, after_revision in ((6, 7), (7, 8)):
            before_relative = (
                "docs/control/audits/"
                "walksafe-implementation-gap-analysis-20260723-"
                f"r{before_revision:03d}.json"
            )
            after_relative = (
                "docs/control/audits/"
                "walksafe-implementation-gap-analysis-20260723-"
                f"r{after_revision:03d}.json"
            )
            before_path = ROOT / before_relative
            after_path = ROOT / after_relative
            errors, subjects = graph.canonical_changed_subject_ids_by_role(
                ROOT,
                changed_roles=["IMPLEMENTATION_GAP"],
                bindings_before={
                    "IMPLEMENTATION_GAP": {
                        "role": "IMPLEMENTATION_GAP",
                        "document_id": f"R{before_revision:03d}",
                        "path": before_relative,
                        "file_sha256": graph.sha256_file(before_path),
                    }
                },
                bindings_after={
                    "IMPLEMENTATION_GAP": {
                        "role": "IMPLEMENTATION_GAP",
                        "document_id": f"R{after_revision:03d}",
                        "path": after_relative,
                        "file_sha256": graph.sha256_file(after_path),
                    }
                },
            )
            self.assertEqual(errors, [])
            self.assertNotIn("*", subjects["IMPLEMENTATION_GAP"])

    def test_unknown_normative_top_level_key_is_global_for_every_scoped_role(
        self,
    ) -> None:
        for role in sorted(graph.SCOPED_IMPACT_ROLES):
            with self.subTest(role=role), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                before_path = root / "before.json"
                after_path = root / "after.json"
                before = {}
                after = {
                    "new_normative_safety_override": {
                        "must_stop": False,
                    }
                }
                self.write_json(before_path, before)
                self.write_json(after_path, after)
                bindings_before = {
                    role: {
                        "role": role,
                        "document_id": "BEFORE",
                        "path": "before.json",
                        "file_sha256": graph.sha256_file(before_path),
                    }
                }
                bindings_after = {
                    role: {
                        "role": role,
                        "document_id": "AFTER",
                        "path": "after.json",
                        "file_sha256": graph.sha256_file(after_path),
                    }
                }

                _, subjects = graph.canonical_changed_subject_ids_by_role(
                    root,
                    changed_roles=[role],
                    bindings_before=bindings_before,
                    bindings_after=bindings_after,
                )

                self.assertEqual(subjects[role], ["*"])
                if role in {
                    "REQUIREMENTS_TRACEABILITY",
                    "DESIGN_TRACEABILITY",
                }:
                    self.assertTrue(
                        graph.canonical_role_requires_external_authorization(
                            root,
                            role,
                            bindings_before[role],
                            bindings_after[role],
                        )
                    )

    def test_unknown_sha_named_field_cannot_hide_global_change(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            before_path = root / "before.json"
            after_path = root / "after.json"
            self.write_json(before_path, {})
            self.write_json(after_path, {"disable_safety_sha256": True})

            _, subjects = graph.canonical_changed_subject_ids_by_role(
                root,
                changed_roles=["DESIGN_TRACEABILITY"],
                bindings_before={
                    "DESIGN_TRACEABILITY": {
                        "path": "before.json",
                        "file_sha256": graph.sha256_file(before_path),
                    }
                },
                bindings_after={
                    "DESIGN_TRACEABILITY": {
                        "path": "after.json",
                        "file_sha256": graph.sha256_file(after_path),
                    }
                },
            )

        self.assertEqual(subjects["DESIGN_TRACEABILITY"], ["*"])

    def test_unknown_source_binding_payload_cannot_hide_global_change(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            before_path = root / "before.json"
            after_path = root / "after.json"
            self.write_json(before_path, {"source_bindings": {}})
            self.write_json(
                after_path,
                {"source_bindings": {"disable_safety": True}},
            )

            _, subjects = graph.canonical_changed_subject_ids_by_role(
                root,
                changed_roles=["REQUIREMENTS_TRACEABILITY"],
                bindings_before={
                    "REQUIREMENTS_TRACEABILITY": {
                        "path": "before.json",
                        "file_sha256": graph.sha256_file(before_path),
                    }
                },
                bindings_after={
                    "REQUIREMENTS_TRACEABILITY": {
                        "path": "after.json",
                        "file_sha256": graph.sha256_file(after_path),
                    }
                },
            )

        self.assertEqual(subjects["REQUIREMENTS_TRACEABILITY"], ["*"])

    def test_trace_row_without_policy_or_gap_reference_fails_global(
        self,
    ) -> None:
        for role, collection, row_id_key, row_id in (
            (
                "REQUIREMENTS_TRACEABILITY",
                "requirements",
                "requirement_id",
                "RQ-NEW-001",
            ),
            ("DESIGN_TRACEABILITY", "records", "design_id", "DES-99"),
        ):
            with self.subTest(role=role), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                before_path = root / "before.json"
                after_path = root / "after.json"
                before = {
                    collection: [
                        {row_id_key: row_id, "title": "before"}
                    ]
                }
                after = {
                    collection: [
                        {row_id_key: row_id, "title": "after"}
                    ]
                }
                self.write_json(before_path, before)
                self.write_json(after_path, after)
                errors, subjects = graph.canonical_changed_subject_ids_by_role(
                    root,
                    changed_roles=[role],
                    bindings_before={
                        role: {
                            "path": "before.json",
                            "file_sha256": graph.sha256_file(before_path),
                        }
                    },
                    bindings_after={
                        role: {
                            "path": "after.json",
                            "file_sha256": graph.sha256_file(after_path),
                        }
                    },
                )
                self.assertEqual(errors, [])
                self.assertEqual(subjects[role], ["*"])

    def test_unknown_metadata_identifier_change_fails_global(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            before_path = root / "before.json"
            after_path = root / "after.json"
            before = {"metadata": {"safety_policy_id": "FP-001"}}
            after = {"metadata": {"safety_policy_id": "FP-999"}}
            self.write_json(before_path, before)
            self.write_json(after_path, after)

            errors, subjects = graph.canonical_changed_subject_ids_by_role(
                root,
                changed_roles=["REQUIREMENTS_TRACEABILITY"],
                bindings_before={
                    "REQUIREMENTS_TRACEABILITY": {
                        "path": "before.json",
                        "file_sha256": graph.sha256_file(before_path),
                    }
                },
                bindings_after={
                    "REQUIREMENTS_TRACEABILITY": {
                        "path": "after.json",
                        "file_sha256": graph.sha256_file(after_path),
                    }
                },
            )

            self.assertEqual(errors, [])
            self.assertEqual(subjects["REQUIREMENTS_TRACEABILITY"], ["*"])

    def test_gap_and_backlog_are_an_atomic_canonical_pair(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            snapshot = graph.canonical_binding_snapshot(
                graph.canonical_binding_map(checkpoint)
            )
            gap_binding = dict(snapshot["IMPLEMENTATION_GAP"])
            gap = graph.load_json(root / gap_binding["path"])
            gap = json.loads(json.dumps(gap))
            gap["purpose"] += " test revision"
            gap["report_content_sha256"] = graph.canonical_json_sha256(
                gap,
                omit={"report_content_sha256"},
            )
            relative = (
                "docs/control/audits/"
                "walksafe-implementation-gap-analysis-test-unpaired.json"
            )
            path = root / relative
            self.write_json(path, gap)
            gap_binding["path"] = relative
            gap_binding["file_sha256"] = graph.sha256_file(path)
            snapshot["IMPLEMENTATION_GAP"] = gap_binding

            errors = graph.validate_gap_backlog_pair(root, snapshot)

            self.assertTrue(
                any("atomically bound pair" in error for error in errors)
            )

    def test_only_a_completed_work_item_may_be_declared_unaffected(
        self,
    ) -> None:
        self.assertTrue(
            graph.goal_allows_unaffected_disposition(
                {"goal_kind": "WORK_ITEM"},
                "COMPLETE_AT_TARGET",
                {"IMPLEMENTATION_GAP"},
            )
        )
        self.assertFalse(
            graph.goal_allows_unaffected_disposition(
                {"goal_kind": "WORKSTREAM"},
                "COMPLETE_AT_TARGET",
                {"IMPLEMENTATION_GAP"},
            )
        )
        self.assertFalse(
            graph.goal_allows_unaffected_disposition(
                {"goal_kind": "WORK_ITEM"},
                "COMPLETE_AT_TARGET",
                {"DEPENDENCY_CLOSURE"},
            )
        )

    def test_internal_producer_completes_before_pending_successors(self) -> None:
        self.assertEqual(
            graph.pending_canonical_transaction_order_errors(
                pending_producer_completion_goal_id="PRODUCER",
                pending_reopen_goal_ids={"DOWNSTREAM"},
                event_type="GOAL_COMPLETED",
                subject_goal_id="PRODUCER",
            ),
            [],
        )
        self.assertEqual(
            graph.pending_canonical_transaction_order_errors(
                pending_producer_completion_goal_id=None,
                pending_reopen_goal_ids={"DOWNSTREAM"},
                event_type="GOAL_SUPERSEDED",
                subject_goal_id="DOWNSTREAM",
            ),
            [],
        )
        self.assertTrue(
            graph.pending_canonical_transaction_order_errors(
                pending_producer_completion_goal_id="PRODUCER",
                pending_reopen_goal_ids={"DOWNSTREAM"},
                event_type="GOAL_SUPERSEDED",
                subject_goal_id="DOWNSTREAM",
            )
        )

    def test_typed_completion_rejects_a_suffix_alias_for_required_role(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            errors = graph.validate_typed_work_item_completion_semantics(
                Path(tmp),
                goal_id="TEST-FORMAL",
                node={
                    "goal_kind": "WORK_ITEM",
                    "work_item_type": "FORMAL_TEST_RUN",
                },
                goal_path="missing.md",
                references=[
                    "TEST_PLAN_APPROVAL_RECEIPT",
                    "FAKE::FORMAL_TEST_REPORT",
                    "ACTUAL_DEVICE_TEST_REPORT",
                ],
                binding_snapshot={},
                paths_by_id={},
            )

        self.assertTrue(
            any(
                "typed completion evidence roles differ" in error
                and "FORMAL_TEST_REPORT" in error
                for error in errors
            )
        )

    def test_completed_workstream_reopens_without_rewriting_its_completion(
        self,
    ) -> None:
        with self.fixture_root() as root:
            self.refresh_integrity(root)
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_package(root, checkpoint)
            state = checkpoint["goal_execution"]
            bindings_before = next(
                event["canonical_binding_snapshot_after"]
                for event in reversed(state["transition_history"])
                if "canonical_binding_snapshot_after" in event
            )
            gap_binding = graph.canonical_binding_map(checkpoint)[
                "IMPLEMENTATION_GAP"
            ]
            gap_payload = graph.load_json(root / gap_binding["path"])
            gap_payload = json.loads(json.dumps(gap_payload))
            changed_row = next(
                row
                for row in gap_payload["assessments"]
                if row["gap_id"] == "GAP-010"
            )
            changed_row["rationale"] += " 회귀 재평가가 필요하다."
            carried = sorted(
                row["gap_id"]
                for row in gap_payload["assessments"]
                if row["gap_id"] != "GAP-010"
            )
            gap_payload["reassessment_scope"] = {
                "mode": "TEST_COMPLETED_WORKSTREAM_REOPEN",
                "directly_reassessed_gap_ids": ["GAP-010"],
                "impact_reviewed_gap_ids": [],
                "reviewed_gap_ids": ["GAP-010"],
                "carried_forward_gap_count": len(carried),
                "carried_forward_gap_ids": carried,
            }
            gap_payload["metadata"]["report_id"] = "TEST-GAP-R009"
            gap_payload["metadata"]["version"] = "0.9.0"
            gap_payload["report_content_sha256"] = graph.canonical_json_sha256(
                gap_payload,
                omit={"report_content_sha256"},
            )
            gap_relative = (
                "docs/control/audits/"
                "walksafe-implementation-gap-analysis-test-r009.json"
            )
            gap_path = root / gap_relative
            self.write_json(gap_path, gap_payload)
            backlog_binding = graph.canonical_binding_map(checkpoint)[
                "IMPLEMENTATION_BACKLOG"
            ]
            backlog_payload = graph.load_json(root / backlog_binding["path"])
            backlog_payload = json.loads(json.dumps(backlog_payload))
            backlog_payload["metadata"]["backlog_id"] = "TEST-BACKLOG-R009"
            backlog_payload["metadata"]["version"] = "0.9.0"
            backlog_payload["metadata"]["predecessor_backlog_id"] = (
                backlog_binding["document_id"]
            )
            backlog_payload["source_predecessor"] = {
                "path": backlog_binding["path"],
                "file_sha256": backlog_binding["file_sha256"],
                "preserved_unchanged": True,
            }
            backlog_payload["gap_report_content_sha256"] = gap_payload[
                "report_content_sha256"
            ]
            backlog_payload["backlog_content_sha256"] = (
                graph.canonical_json_sha256(
                    backlog_payload,
                    omit={"backlog_content_sha256"},
                )
            )
            backlog_relative = (
                "docs/control/audits/"
                "walksafe-implementation-remediation-backlog-test-r009.json"
            )
            backlog_path = root / backlog_relative
            self.write_json(backlog_path, backlog_payload)
            for binding in checkpoint["canonical_bindings"]:
                if binding["role"] == "IMPLEMENTATION_GAP":
                    binding["path"] = gap_relative
                    binding["document_id"] = "TEST-GAP-R009"
                    binding["file_sha256"] = graph.sha256_file(gap_path)
                elif binding["role"] == "IMPLEMENTATION_BACKLOG":
                    binding["path"] = backlog_relative
                    binding["document_id"] = "TEST-BACKLOG-R009"
                    binding["file_sha256"] = graph.sha256_file(backlog_path)
            bindings_after = graph.canonical_binding_snapshot(
                graph.canonical_binding_map(checkpoint)
            )
            diff_errors, changed_subjects = (
                graph.canonical_changed_subject_ids_by_role(
                    root,
                    changed_roles=[
                        "IMPLEMENTATION_BACKLOG",
                        "IMPLEMENTATION_GAP",
                    ],
                    bindings_before=bindings_before,
                    bindings_after=bindings_after,
                )
            )
            self.assertEqual(diff_errors, [])
            self.assertEqual(
                changed_subjects,
                {
                    "IMPLEMENTATION_BACKLOG": [],
                    "IMPLEMENTATION_GAP": ["FP-001", "GAP-010"],
                },
            )

            epic_id = "WS-GOAL-EPIC-01"
            previous_focus_id = state["focus_goal_id"]
            previous_focus_path = root / state["focus_goal_path"]
            state["status_by_goal"][epic_id] = "READY"
            state["status_by_goal"]["WS-GOAL-EPIC-02"] = "PLANNED"
            state["status_by_goal"][
                "WS-GOAL-EPIC-02-FP-018-R001"
            ] = "PLANNED"
            state["status_by_goal"]["WS-GOAL-EPIC-03"] = "PLANNED"
            state["completion_evidence_by_goal"].pop(epic_id)
            manifest = graph.load_json(root / graph.MANIFEST_RELATIVE)
            nodes = self.load_nodes(root, manifest, include_dynamic=True)
            backlog = self.load_binding_json_from_root(
                root,
                checkpoint,
                "IMPLEMENTATION_BACKLOG",
            )
            state["ready_frontier_goal_ids"] = graph.ready_frontier(
                nodes,
                state["status_by_goal"],
                state["materialized_child_goal_ids_by_parent"],
                state["blockers_by_goal"],
                backlog,
            )
            state["focus_goal_id"] = state["ready_frontier_goal_ids"][0]
            focus_node = nodes[state["focus_goal_id"]]
            focus_relative = next(
                relative
                for relative in state["goal_document_paths"]
                if graph.parse_goal(root / relative)[0]["goal_id"]
                == state["focus_goal_id"]
            )
            state["focus_goal_path"] = focus_relative
            state["focus_work_item_id"] = (
                focus_node.get("work_item_id")
                if focus_node.get("goal_kind") == "WORK_ITEM"
                else ""
            )
            state["focus_source"] = (
                focus_node.get("materialized_from_role")
                if focus_node.get("goal_kind") == "WORK_ITEM"
                else "WORKSTREAM_GRAPH"
            )
            focus_path = root / state["focus_goal_path"]
            closure_goal_ids = [
                "WS-GOAL-EPIC-01",
                "WS-GOAL-EPIC-02",
                "WS-GOAL-EPIC-02-FP-018-R001",
                "WS-GOAL-EPIC-03",
            ]
            event = {
                "sequence": len(state["transition_history"]) + 1,
                "event_id": "WS-GOAL-GRAPH-CANONICAL-REOPEN-TEST-001",
                "event_type": "CANONICAL_BINDINGS_UPDATED",
                "changed_binding_roles": [
                    "IMPLEMENTATION_BACKLOG",
                    "IMPLEMENTATION_GAP",
                ],
                "changed_subject_ids_by_role": changed_subjects,
                "impact_closure_goal_ids": closure_goal_ids,
                "impact_disposition_by_goal": {
                    epic_id: {
                        "result": "REOPEN_CONTAINER",
                        "target_status": "READY",
                    },
                    "WS-GOAL-EPIC-02": {
                        "result": "REVALIDATION_REFRESH_REQUIRED",
                        "target_status": "PLANNED",
                    },
                    "WS-GOAL-EPIC-02-FP-018-R001": {
                        "result": "DEPENDENCY_INVALIDATED",
                        "target_status": "PLANNED",
                    },
                    "WS-GOAL-EPIC-03": {
                        "result": "REVALIDATION_REFRESH_REQUIRED",
                        "target_status": "PLANNED",
                    },
                },
                "reopened_completion_event_sha256_by_goal": {
                    epic_id: state["transition_history"][0]["event_sha256"]
                },
                "canonical_binding_snapshot_after": bindings_after,
                "occurred_on": "2026-07-24",
                "occurred_at": "2026-07-24T19:42:00+09:00",
                "previous_focus_goal_id": previous_focus_id,
                "previous_focus_content_sha256": graph.sha256_file(
                    previous_focus_path
                ),
                "focus_goal_id": state["focus_goal_id"],
                "focus_goal_content_sha256": graph.sha256_file(focus_path),
                "from_status": state["status_by_goal"][state["focus_goal_id"]],
                "to_status": state["status_by_goal"][state["focus_goal_id"]],
                "static_plan_manifest_sha256": state[
                    "static_plan_manifest_sha256"
                ],
                "status_changes": {
                    epic_id: "READY",
                    "WS-GOAL-EPIC-02": "PLANNED",
                    "WS-GOAL-EPIC-02-FP-018-R001": "PLANNED",
                    "WS-GOAL-EPIC-03": "PLANNED",
                },
                "runtime_after": self.runtime_snapshot(root, checkpoint),
                "blockers_after": json.loads(json.dumps(state["blockers_by_goal"])),
                "blocker_resolution_ids_after": [],
                "source_checkpoint_version": checkpoint["schema_version"],
                "evidence_refs": [
                    "IMPLEMENTATION_BACKLOG",
                    "IMPLEMENTATION_GAP",
                ],
                "previous_event_sha256": state["transition_history"][-1][
                    "event_sha256"
                ],
            }
            event["event_sha256"] = graph.event_sha256(event)
            state["transition_history"].append(event)
            state["transition_history_anchor_sha256"] = event["event_sha256"]
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            with patch.object(
                graph,
                "validate_canonical_update_authorization",
                return_value=[],
            ):
                errors = graph.validate(root, check_continuation=False)
            self.assertEqual(errors, [])

            event["impact_disposition_by_goal"][epic_id][
                "result"
            ] = "REOPEN_REQUIRED"
            self.rehash_event(checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)
            with patch.object(
                graph,
                "validate_canonical_update_authorization",
                return_value=[],
            ):
                errors = graph.validate(root, check_continuation=False)
            self.assertTrue(
                any(
                    "container impact must use REOPEN_CONTAINER" in error
                    for error in errors
                )
            )

    def test_changed_subject_scope_affects_only_related_goal(self) -> None:
        nodes = {
            "FP018": {
                "goal_kind": "WORK_ITEM",
                "canonical_input_roles": ["IMPLEMENTATION_GAP"],
                "source_policy_ids": ["FP-018"],
                "gap_ids": ["GAP-027"],
            },
            "FP019": {
                "goal_kind": "WORK_ITEM",
                "canonical_input_roles": ["IMPLEMENTATION_GAP"],
                "source_policy_ids": ["FP-019"],
                "gap_ids": ["GAP-028"],
            },
        }
        affected = graph.changed_binding_affected_goals(
            changed_roles={"IMPLEMENTATION_GAP"},
            changed_subject_ids_by_role={
                "IMPLEMENTATION_GAP": ["FP-018", "GAP-027"]
            },
            nodes=nodes,
            statuses={
                "FP018": "COMPLETE_AT_TARGET",
                "FP019": "COMPLETE_AT_TARGET",
            },
            completion_bindings_by_goal={},
            latest_start_event_by_goal={},
        )
        self.assertEqual(set(affected), {"FP018"})

    def test_dependency_impact_closure_reaches_downstream_and_parent(self) -> None:
        nodes = {
            "A": {
                "goal_kind": "WORK_ITEM",
                "parent_goal_id": "UPSTREAM",
                "start_requires": [],
                "completion_requires": [],
            },
            "B": {
                "goal_kind": "WORK_ITEM",
                "parent_goal_id": "DOWNSTREAM",
                "start_requires": ["A"],
                "completion_requires": ["A"],
            },
            "UPSTREAM": {
                "goal_kind": "WORKSTREAM",
                "parent_goal_id": "MASTER",
                "start_requires": [],
                "completion_requires": [],
            },
            "DOWNSTREAM": {
                "goal_kind": "WORKSTREAM",
                "parent_goal_id": "MASTER",
                "start_requires": ["UPSTREAM"],
                "completion_requires": ["UPSTREAM"],
            },
            "MASTER": {
                "goal_kind": "MASTER",
                "parent_goal_id": "",
                "start_requires": [],
                "completion_requires": [],
            },
        }
        closure = graph.expand_affected_goal_dependency_closure(
            nodes=nodes,
            statuses={
                "A": "COMPLETE_AT_TARGET",
                "B": "COMPLETE_AT_TARGET",
                "UPSTREAM": "COMPLETE_AT_TARGET",
                "DOWNSTREAM": "COMPLETE_AT_TARGET",
                "MASTER": "READY",
            },
            directly_affected_goal_roles={"A": {"IMPLEMENTATION_GAP"}},
        )
        self.assertEqual(set(closure), {"A", "B", "UPSTREAM", "DOWNSTREAM"})
        self.assertIn("DEPENDENCY_CLOSURE", closure["B"])
        self.assertIn("DEPENDENCY_CLOSURE", closure["UPSTREAM"])
        self.assertIn("DEPENDENCY_CLOSURE", closure["DOWNSTREAM"])

    def test_completed_workstream_reopen_target_uses_start_dependency_reason(
        self,
    ) -> None:
        self.assertEqual(
            graph.workstream_reopen_target_status(
                {"IMPLEMENTATION_GAP"}
            ),
            "READY",
        )
        self.assertEqual(
            graph.workstream_reopen_target_status(
                {
                    "IMPLEMENTATION_GAP",
                    "DEPENDENCY_CLOSURE",
                    "CHILD_AGGREGATE_INVALIDATED",
                }
            ),
            "READY",
        )
        self.assertEqual(
            graph.workstream_reopen_target_status(
                {
                    "IMPLEMENTATION_GAP",
                    "DEPENDENCY_CLOSURE",
                    "START_DEPENDENCY_INVALIDATED",
                }
            ),
            "PLANNED",
        )

    def test_dependency_impact_closure_revises_planned_work_item_edge(
        self,
    ) -> None:
        nodes = {
            "A-R001": {
                "goal_kind": "WORK_ITEM",
                "parent_goal_id": "UPSTREAM",
                "start_requires": [],
                "completion_requires": [],
            },
            "B-R001": {
                "goal_kind": "WORK_ITEM",
                "parent_goal_id": "DOWNSTREAM",
                "start_requires": ["A-R001"],
                "completion_requires": ["A-R001"],
            },
            "UPSTREAM": {
                "goal_kind": "WORKSTREAM",
                "parent_goal_id": "MASTER",
                "start_requires": [],
                "completion_requires": [],
            },
            "DOWNSTREAM": {
                "goal_kind": "WORKSTREAM",
                "parent_goal_id": "MASTER",
                "start_requires": ["UPSTREAM"],
                "completion_requires": ["UPSTREAM"],
            },
            "MASTER": {
                "goal_kind": "MASTER",
                "parent_goal_id": "",
                "start_requires": [],
                "completion_requires": [],
            },
        }

        closure = graph.expand_affected_goal_dependency_closure(
            nodes=nodes,
            statuses={
                "A-R001": "COMPLETE_AT_TARGET",
                "B-R001": "PLANNED",
                "UPSTREAM": "READY",
                "DOWNSTREAM": "PLANNED",
                "MASTER": "READY",
            },
            directly_affected_goal_roles={
                "A-R001": {"IMPLEMENTATION_GAP"}
            },
        )

        self.assertIn("B-R001", closure)
        self.assertIn("DEPENDENCY_CLOSURE", closure["B-R001"])

    def test_canonical_change_successor_can_be_created_under_planned_parent(
        self,
    ) -> None:
        self.assertTrue(
            graph.successor_parent_accepts_revision(
                "PLANNED",
                canonical_change_successor=True,
            )
        )
        self.assertTrue(
            graph.successor_parent_accepts_revision(
                "BLOCKED",
                canonical_change_successor=True,
            )
        )
        self.assertFalse(
            graph.successor_parent_accepts_revision(
                "PLANNED",
                canonical_change_successor=False,
            )
        )
        self.assertFalse(
            graph.successor_parent_accepts_revision(
                "COMPLETE_AT_TARGET",
                canonical_change_successor=True,
            )
        )

    def test_work_item_dependency_cannot_reference_ancestor_or_downstream(
        self,
    ) -> None:
        nodes = {
            "MASTER": {
                "goal_kind": "MASTER",
                "parent_goal_id": "",
            },
            "UPSTREAM": {
                "goal_kind": "WORKSTREAM",
                "parent_goal_id": "MASTER",
                "start_requires": [],
                "completion_requires": [],
            },
            "DOWNSTREAM": {
                "goal_kind": "WORKSTREAM",
                "parent_goal_id": "MASTER",
                "start_requires": ["UPSTREAM"],
                "completion_requires": ["UPSTREAM"],
            },
            "UPSTREAM-CHILD": {
                "goal_kind": "WORK_ITEM",
                "parent_goal_id": "UPSTREAM",
                "start_requires": ["UPSTREAM"],
                "completion_requires": ["DOWNSTREAM-CHILD"],
            },
            "DOWNSTREAM-CHILD": {
                "goal_kind": "WORK_ITEM",
                "parent_goal_id": "DOWNSTREAM",
                "start_requires": [],
                "completion_requires": [],
            },
        }

        errors = graph.validate_work_item_dependency_topology(
            "UPSTREAM-CHILD",
            nodes["UPSTREAM-CHILD"],
            nodes,
            statuses=None,
        )

        self.assertTrue(any("cannot reference its ancestor" in e for e in errors))
        self.assertTrue(
            any("downstream or unrelated Workstream" in e for e in errors)
        )

    def test_work_item_cannot_have_completion_only_dependency(self) -> None:
        nodes = {
            "MASTER": {
                "goal_kind": "MASTER",
                "parent_goal_id": "",
            },
            "WS": {
                "goal_kind": "WORKSTREAM",
                "parent_goal_id": "MASTER",
                "start_requires": [],
                "completion_requires": [],
            },
            "A": {
                "goal_kind": "WORK_ITEM",
                "parent_goal_id": "WS",
                "start_requires": [],
                "completion_requires": ["B"],
            },
            "B": {
                "goal_kind": "WORK_ITEM",
                "parent_goal_id": "WS",
                "start_requires": [],
                "completion_requires": [],
            },
        }

        errors = graph.validate_work_item_dependency_topology(
            "A",
            nodes["A"],
            nodes,
            statuses=None,
        )

        self.assertTrue(
            any(
                "completion dependencies must also be start dependencies"
                in error
                for error in errors
            )
        )

    def test_active_work_item_cannot_depend_on_superseded_revision(
        self,
    ) -> None:
        nodes = {
            "MASTER": {
                "goal_kind": "MASTER",
                "parent_goal_id": "",
            },
            "WS": {
                "goal_kind": "WORKSTREAM",
                "parent_goal_id": "MASTER",
                "start_requires": [],
                "completion_requires": [],
            },
            "A-R001": {
                "goal_kind": "WORK_ITEM",
                "parent_goal_id": "WS",
            },
            "A-R002": {
                "goal_kind": "WORK_ITEM",
                "parent_goal_id": "WS",
            },
            "B-R001": {
                "goal_kind": "WORK_ITEM",
                "parent_goal_id": "WS",
                "start_requires": ["A-R001"],
                "completion_requires": ["A-R001"],
            },
        }
        statuses = {
            "A-R001": "SUPERSEDED",
            "A-R002": "COMPLETE_AT_TARGET",
            "B-R001": "PLANNED",
        }

        errors = graph.validate_work_item_dependency_topology(
            "B-R001",
            nodes["B-R001"],
            nodes,
            statuses,
        )
        self.assertTrue(
            any("references a superseded revision" in e for e in errors)
        )

        nodes["B-R001"]["start_requires"] = ["A-R002"]
        nodes["B-R001"]["completion_requires"] = ["A-R002"]
        self.assertEqual(
            graph.validate_work_item_dependency_topology(
                "B-R001",
                nodes["B-R001"],
                nodes,
                statuses,
            ),
            [],
        )

    def test_successor_cannot_change_work_type_or_policy_scope(self) -> None:
        predecessor = {
            field: f"same-{field}"
            for field in graph.SUCCESSOR_INVARIANT_FIELDS
        }
        successor = dict(predecessor)
        self.assertTrue(
            graph.successor_semantic_scope_matches(
                predecessor,
                successor,
            )
        )

        successor["work_item_type"] = "FORMAL_TEST_RUN"
        self.assertFalse(
            graph.successor_semantic_scope_matches(
                predecessor,
                successor,
            )
        )
        successor = dict(predecessor)
        successor["source_policy_ids"] = ["FP-999"]
        self.assertFalse(
            graph.successor_semantic_scope_matches(
                predecessor,
                successor,
            )
        )
        successor = dict(predecessor)
        successor["source_blocker_ids"] = ["BLOCKER-B"]
        self.assertFalse(
            graph.successor_semantic_scope_matches(
                predecessor,
                successor,
            )
        )
        successor = dict(predecessor)
        successor["output_subject_ids_by_role"] = {
            "MODULE_REGISTER": ["FP-999"]
        }
        self.assertFalse(
            graph.successor_semantic_scope_matches(
                predecessor,
                successor,
            )
        )
        successor = dict(predecessor)
        successor["priority_rank"] = 999
        self.assertFalse(
            graph.successor_semantic_scope_matches(
                predecessor,
                successor,
            )
        )
        successor = dict(predecessor)
        successor["question_policy"] = "IGNORE_MASTER"
        self.assertFalse(
            graph.successor_semantic_scope_matches(
                predecessor,
                successor,
            )
        )

    def test_dynamic_goal_must_inherit_master_question_and_stop_policy(
        self,
    ) -> None:
        self.assertEqual(
            graph.validate_dynamic_control_policy(
                "GOAL",
                {
                    "question_policy": "INHERIT_MASTER",
                    "stop_policy": "INHERIT_MASTER",
                },
            ),
            [],
        )
        errors = graph.validate_dynamic_control_policy(
            "GOAL",
            {"question_policy": "IGNORE_MASTER"},
        )
        self.assertEqual(len(errors), 2)

    def test_imported_completion_evidence_tamper_is_rejected(self) -> None:
        with self.fixture_root() as root:
            self.refresh_integrity(root)
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_package(root, checkpoint)
            event = checkpoint["goal_execution"]["transition_history"][1]
            event["imported_completion_evidence_bindings_by_goal"][
                "WS-GOAL-EPIC-01"
            ]["EPIC01_PHASE_G_RECORD"]["file_sha256"] = "0" * 64
            self.rehash_event(checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any("imported completion evidence differs" in error for error in errors)
        )

    def test_reopened_workstream_requires_fresh_aggregate_revalidation(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            role = "WORKSTREAM_REVALIDATION::WS"
            receipt_path = root / "revalidation.json"
            aggregate = {
                "reopen_event_sha256": "1" * 64,
                "latest_impact_event_sha256": "1" * 64,
                "dependency_completion_event_sha256_by_goal": {
                    "A": "2" * 64
                },
                "child_completion_event_sha256_by_goal": {
                    "C": "3" * 64
                },
            }
            receipt = {
                "schema_version": "1.0",
                "evidence_type": "WORKSTREAM_REVALIDATION",
                "document_id": "REVALIDATION-001",
                "status": "ACCEPTED",
                "result": "PASS",
                "target_goal_id": "WS",
                "aggregate_revalidation": aggregate,
                "generated_at": "2026-07-24T10:30:00+09:00",
            }
            self.write_json(receipt_path, receipt)
            binding = {
                "role": role,
                "document_id": "REVALIDATION-001",
                "path": "revalidation.json",
                "file_sha256": graph.sha256_file(receipt_path),
            }
            arguments = {
                "root": root,
                "label": "test",
                "goal_id": "WS",
                "node": {
                    "start_requires": ["A"],
                    "completion_requires": ["A"],
                },
                "nodes": {
                    "WS": {"goal_kind": "WORKSTREAM"},
                    "A": {"goal_kind": "WORK_ITEM"},
                    "C": {
                        "goal_kind": "WORK_ITEM",
                        "parent_goal_id": "WS",
                    },
                },
                "statuses_before_completion": {
                    "WS": "READY",
                    "A": "COMPLETE_AT_TARGET",
                    "C": "COMPLETE_AT_TARGET",
                },
                "references": [role],
                "binding_snapshot": {role: binding},
                "completion_event_by_goal": {
                    "A": {
                        "event_sha256": "2" * 64,
                        "occurred_at": "2026-07-24T10:10:00+09:00",
                    },
                    "C": {
                        "event_sha256": "3" * 64,
                        "occurred_at": "2026-07-24T10:20:00+09:00",
                    },
                },
                "reopen_event": {
                    "event_sha256": "1" * 64,
                    "occurred_at": "2026-07-24T10:00:00+09:00",
                },
                "latest_impact_event": {
                    "event_sha256": "1" * 64,
                    "occurred_at": "2026-07-24T10:00:00+09:00",
                },
                "completion_event": {
                    "occurred_at": "2026-07-24T11:00:00+09:00",
                    "aggregate_revalidation": aggregate,
                },
            }
            self.assertEqual(
                graph.validate_reopened_workstream_completion(**arguments),
                [],
            )

            receipt["generated_at"] = "2026-07-24T09:59:00+09:00"
            self.write_json(receipt_path, receipt)
            binding["file_sha256"] = graph.sha256_file(receipt_path)
            errors = graph.validate_reopened_workstream_completion(**arguments)
            self.assertTrue(
                any("revalidation semantics differ" in error for error in errors)
            )

            receipt["generated_at"] = "2026-07-24T10:15:00+09:00"
            self.write_json(receipt_path, receipt)
            binding["file_sha256"] = graph.sha256_file(receipt_path)
            errors = graph.validate_reopened_workstream_completion(**arguments)
            self.assertTrue(
                any("revalidation semantics differ" in error for error in errors)
            )

            arguments["latest_impact_event"] = {
                "event_sha256": "4" * 64,
                "occurred_at": "2026-07-24T10:40:00+09:00",
            }
            arguments["completion_event"]["aggregate_revalidation"][
                "latest_impact_event_sha256"
            ] = "4" * 64
            receipt["aggregate_revalidation"][
                "latest_impact_event_sha256"
            ] = "4" * 64
            receipt["generated_at"] = "2026-07-24T10:30:00+09:00"
            self.write_json(receipt_path, receipt)
            binding["file_sha256"] = graph.sha256_file(receipt_path)
            errors = graph.validate_reopened_workstream_completion(**arguments)
            self.assertTrue(
                any("revalidation semantics differ" in error for error in errors)
            )

            receipt["generated_at"] = "2026-07-24T10:50:00+09:00"
            self.write_json(receipt_path, receipt)
            binding["file_sha256"] = graph.sha256_file(receipt_path)
            self.assertEqual(
                graph.validate_reopened_workstream_completion(**arguments),
                [],
            )

    def test_backlog_epic_progress_fields_are_not_normative_input_delta(
        self,
    ) -> None:
        before = {
            "next_action_sequence": [
                {
                    "source_policy_id": "FP-018",
                    "status": "PARTIAL",
                    "action": "복구 흐름을 완성한다.",
                }
            ],
            "epics": [
                {
                    "epic_id": "EPIC-02",
                    "source_policy_ids": ["FP-018", "FP-019"],
                    "gap_ids": ["GAP-027", "GAP-028"],
                    "current_status": "IN_PROGRESS",
                    "current_status_reason": "진행 중",
                    "completed_internal_phases": ["A"],
                }
            ],
        }
        after = json.loads(json.dumps(before))
        after["epics"][0]["current_status"] = "IMPLEMENTATION_READY"
        after["epics"][0]["current_status_reason"] = "내부 구현 완료"
        after["epics"][0]["completed_internal_phases"] = ["A", "B"]
        self.assertEqual(
            graph.backlog_scoped_projection(before),
            graph.backlog_scoped_projection(after),
        )

    def test_policy_gap_completion_rejects_unresolved_conflict(self) -> None:
        before_row = {
            "gap_id": "GAP-027",
            "source_policy_id": "FP-018",
            "status": "MISSING",
            "evidence_ids": ["EVD-OLD"],
            "rationale": "구현 없음",
            "current_implementation_in_plain_language": "구현 없음",
            "remediation": "구현한다",
            "acceptance_criteria": ["내부 검증"],
        }
        before_row["assessment_sha256"] = graph.canonical_json_sha256(
            before_row
        )
        after_row = json.loads(json.dumps(before_row))
        after_row["status"] = "CONFLICTING"
        after_row["rationale"] = "정책과 반대로 동작"
        after_row.pop("assessment_sha256")
        after_row["assessment_sha256"] = graph.canonical_json_sha256(
            after_row
        )
        errors = graph.validate_policy_gap_reassessment_result(
            producer_goal_id="GOAL-FP018",
            producer_node={
                "source_policy_ids": ["FP-018"],
                "gap_ids": ["GAP-027"],
            },
            gap_before={"assessments": [before_row]},
            gap_after={
                "assessments": [after_row],
                "reassessment_scope": {
                    "directly_reassessed_gap_ids": ["GAP-027"],
                    "reviewed_gap_ids": ["GAP-027"],
                },
            },
        )
        self.assertTrue(
            any("outcome/evidence differs" in error for error in errors)
        )

    def test_policy_gap_rejects_fake_evidence_id(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            errors = graph.validate_policy_gap_evidence_catalog(
                Path(tmp),
                producer_goal_id="GOAL-FP018",
                producer_node={"gap_ids": ["GAP-027"]},
                gap_after={
                    "assessments": [
                        {
                            "gap_id": "GAP-027",
                            "evidence_ids": ["EVD-FAKE"],
                        }
                    ],
                    "evidence_catalog": [],
                },
                receipt={"result_evidence": []},
            )
        self.assertTrue(
            any("unique valid catalog" in error for error in errors)
        )
        self.assertTrue(
            any("producer receipt" in error for error in errors)
        )

    def test_policy_gap_catalog_binds_exact_implementation_and_verification(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            gap_after, receipt, _ = self.policy_gap_catalog_fixture(root)

            errors = graph.validate_policy_gap_evidence_catalog(
                root,
                producer_goal_id="GOAL-FP018",
                producer_node={"gap_ids": ["GAP-027"]},
                gap_after=gap_after,
                receipt=receipt,
            )

        self.assertEqual(errors, [])

    def test_policy_gap_catalog_rejects_missing_required_result_role(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            gap_after, receipt, _ = self.policy_gap_catalog_fixture(root)
            receipt["result_evidence"] = [
                item
                for item in receipt["result_evidence"]
                if item["kind"] != "VERIFICATION_RESULT"
            ]

            errors = graph.validate_policy_gap_evidence_catalog(
                root,
                producer_goal_id="GOAL-FP018",
                producer_node={"gap_ids": ["GAP-027"]},
                gap_after=gap_after,
                receipt=receipt,
            )

        self.assertTrue(
            any(
                "must contain exactly implementation and verification"
                in error
                for error in errors
            )
        )

    def test_policy_gap_catalog_rejects_duplicate_allowed_result_role(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            gap_after, receipt, _ = self.policy_gap_catalog_fixture(root)
            implementation = next(
                item
                for item in receipt["result_evidence"]
                if item["kind"] == "IMPLEMENTATION_RECORD"
            )
            receipt["result_evidence"].insert(
                0,
                json.loads(json.dumps(implementation)),
            )

            errors = graph.validate_policy_gap_evidence_catalog(
                root,
                producer_goal_id="GOAL-FP018",
                producer_node={"gap_ids": ["GAP-027"]},
                gap_after=gap_after,
                receipt=receipt,
            )

        self.assertTrue(
            any("one valid item per role" in error for error in errors)
        )

    def test_policy_gap_catalog_rejects_successor_trace_hash_cycle(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            gap_after, receipt, hashes = self.policy_gap_catalog_fixture(
                root
            )
            gap_after["evidence_catalog"][0][
                "result_evidence_sha256_by_kind"
            ]["SUCCESSOR_TRACE"] = hashes["SUCCESSOR_TRACE"]

            errors = graph.validate_policy_gap_evidence_catalog(
                root,
                producer_goal_id="GOAL-FP018",
                producer_node={"gap_ids": ["GAP-027"]},
                gap_after=gap_after,
                receipt=receipt,
            )

        self.assertTrue(
            any("producer receipt" in error for error in errors)
        )

    def test_successor_trace_remains_required_for_work_item_completion(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _, receipt, _ = self.policy_gap_catalog_fixture(root)
            receipt["result_evidence"] = [
                item
                for item in receipt["result_evidence"]
                if item["kind"] != "SUCCESSOR_TRACE"
            ]
            receipt_path = root / "receipt.json"
            self.write_json(receipt_path, receipt)

            errors = graph.validate_internal_producer_result_evidence(
                root,
                goal_id="GOAL-FP018",
                node={
                    "work_item_type": "POLICY_GAP_WORK",
                    "source_policy_ids": ["FP-018"],
                    "gap_ids": ["GAP-027"],
                },
                binding_snapshot={
                    "WORK_ITEM_COMPLETION::GOAL-FP018": {
                        "path": "receipt.json",
                        "file_sha256": graph.sha256_file(receipt_path),
                    }
                },
            )

        self.assertTrue(
            any(
                "internal result evidence set is incomplete" in error
                for error in errors
            )
        )

    def test_completed_successor_artifact_chain_allows_new_live_generation(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "app.kt"
            artifact.write_bytes(b"successor generation")
            historical_sha256 = hashlib.sha256(
                b"predecessor generation"
            ).hexdigest()
            implementation_path = root / "successor-implementation.json"
            self.write_json(
                implementation_path,
                {
                    "goal_id": "GOAL-B",
                    "kind": "IMPLEMENTATION_RECORD",
                    "changed_artifacts": [
                        {
                            "path": "app.kt",
                            "before_sha256": historical_sha256,
                            "after_sha256": graph.sha256_file(artifact),
                        }
                    ],
                },
            )
            receipt_path = root / "successor-receipt.json"
            self.write_json(
                receipt_path,
                {
                    "result_evidence": [
                        {
                            "kind": "IMPLEMENTATION_RECORD",
                            "path": implementation_path.name,
                            "sha256": graph.sha256_file(implementation_path),
                        }
                    ]
                },
            )
            reached = graph.completed_successor_artifact_chain_reaches_live(
                root,
                goal_id="GOAL-A",
                relative_path="app.kt",
                historical_sha256=historical_sha256,
                nodes={
                    "GOAL-A": {"work_item_type": "POLICY_GAP_WORK"},
                    "GOAL-B": {
                        "work_item_type": "POLICY_GAP_WORK",
                        "start_requires": ["GOAL-A"],
                        "completion_requires": ["GOAL-A"],
                    },
                },
                completion_bindings_by_goal={
                    "GOAL-B": {
                        "WORK_ITEM_COMPLETION::GOAL-B": {
                            "path": receipt_path.name,
                            "file_sha256": graph.sha256_file(receipt_path),
                        }
                    }
                },
                completion_event_by_goal={
                    "GOAL-A": {"sequence": 1},
                    "GOAL-B": {"sequence": 2},
                },
            )

        self.assertTrue(reached)

    def test_successor_artifact_chain_rejects_broken_or_unrelated_hop(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "app.kt"
            artifact.write_bytes(b"successor generation")
            historical_sha256 = hashlib.sha256(
                b"predecessor generation"
            ).hexdigest()
            implementation_path = root / "successor-implementation.json"
            self.write_json(
                implementation_path,
                {
                    "goal_id": "GOAL-B",
                    "kind": "IMPLEMENTATION_RECORD",
                    "changed_artifacts": [
                        {
                            "path": "app.kt",
                            "before_sha256": "f" * 64,
                            "after_sha256": graph.sha256_file(artifact),
                        }
                    ],
                },
            )
            receipt_path = root / "successor-receipt.json"
            self.write_json(
                receipt_path,
                {
                    "result_evidence": [
                        {
                            "kind": "IMPLEMENTATION_RECORD",
                            "path": implementation_path.name,
                            "sha256": graph.sha256_file(implementation_path),
                        }
                    ]
                },
            )
            common = {
                "root": root,
                "goal_id": "GOAL-A",
                "relative_path": "app.kt",
                "historical_sha256": historical_sha256,
                "completion_bindings_by_goal": {
                    "GOAL-B": {
                        "WORK_ITEM_COMPLETION::GOAL-B": {
                            "path": receipt_path.name,
                            "file_sha256": graph.sha256_file(receipt_path),
                        }
                    }
                },
                "completion_event_by_goal": {
                    "GOAL-A": {"sequence": 1},
                    "GOAL-B": {"sequence": 2},
                },
            }
            broken = graph.completed_successor_artifact_chain_reaches_live(
                nodes={
                    "GOAL-A": {"work_item_type": "POLICY_GAP_WORK"},
                    "GOAL-B": {
                        "work_item_type": "POLICY_GAP_WORK",
                        "start_requires": ["GOAL-A"],
                    },
                },
                **common,
            )
            unrelated = graph.completed_successor_artifact_chain_reaches_live(
                nodes={
                    "GOAL-A": {"work_item_type": "POLICY_GAP_WORK"},
                    "GOAL-B": {
                        "work_item_type": "POLICY_GAP_WORK",
                        "start_requires": ["GOAL-C"],
                    },
                },
                **common,
            )

        self.assertFalse(broken)
        self.assertFalse(unrelated)

    def test_policy_backlog_update_cannot_change_unrelated_epic(self) -> None:
        before = {
            "execution_order": ["EPIC-02", "EPIC-03"],
            "epics": [
                {
                    "epic_id": "EPIC-02",
                    "ordered_source_policy_ids": ["FP-018"],
                    "current_status": "IN_PROGRESS",
                    "current_status_reason": "진행 중",
                },
                {
                    "epic_id": "EPIC-03",
                    "ordered_source_policy_ids": ["FP-019"],
                    "current_status": "PLANNED",
                    "current_status_reason": "계획",
                },
            ],
            "next_single_action": {
                "epic_id": "EPIC-03",
                "work_item_id": "NEXT",
                "source_policy_id": "FP-019",
                "gap_id": "GAP-028",
                "status": "PLANNED_NEXT",
                "action": "다음 작업",
            },
        }
        after = json.loads(json.dumps(before))
        after["epics"][0]["current_status"] = "IMPLEMENTATION_READY"
        after["epics"][0]["current_status_reason"] = "내부 구현 준비"
        after["epics"][1]["current_status"] = "IMPLEMENTATION_READY"
        after["epics"][1]["current_status_reason"] = "조작"
        errors = graph.validate_policy_backlog_progress_update(
            producer_goal_id="GOAL-FP018",
            producer_node={
                "parent_goal_id": "WS-EPIC-02",
                "source_policy_ids": ["FP-018"],
                "gap_ids": ["GAP-027"],
            },
            backlog_before=before,
            backlog_after=after,
            gap_after={
                "assessments": [
                    {
                        "source_policy_id": "FP-018",
                        "gap_id": "GAP-027",
                    },
                    {
                        "source_policy_id": "FP-019",
                        "gap_id": "GAP-028",
                    },
                ]
            },
            nodes={
                "WS-EPIC-02": {
                    "goal_kind": "WORKSTREAM",
                    "external_key": "EPIC-02",
                    "source_policy_ids": ["FP-018"],
                    "target_completion_level": "IMPLEMENTATION_READY",
                },
                "WS-EPIC-03": {
                    "goal_kind": "WORKSTREAM",
                    "external_key": "EPIC-03",
                    "source_policy_ids": ["FP-019"],
                    "target_completion_level": "IMPLEMENTATION_READY",
                },
                "GOAL-FP018": {
                    "goal_kind": "WORK_ITEM",
                    "work_item_type": "POLICY_GAP_WORK",
                    "parent_goal_id": "WS-EPIC-02",
                    "source_policy_ids": ["FP-018"],
                    "gap_ids": ["GAP-027"],
                },
            },
            statuses_before_update={
                "WS-EPIC-02": "READY",
                "WS-EPIC-03": "PLANNED",
                "GOAL-FP018": "IN_PROGRESS",
            },
        )
        self.assertTrue(
            any("unrelated Backlog EPIC" in error for error in errors)
        )

    def test_bootstrap_pair_is_used_only_for_next_policy_gap_pointer(
        self,
    ) -> None:
        fixture = self.policy_backlog_bootstrap_fixture()

        errors = graph.validate_policy_backlog_progress_update(**fixture)

        self.assertEqual(errors, [])
        parent_after = fixture["backlog_after"]["epics"][0]
        self.assertEqual(parent_after["current_status"], "IN_PROGRESS")
        self.assertEqual(
            fixture["backlog_after"]["next_single_action"][
                "source_policy_id"
            ],
            "NPC-PERMISSION-SESSION-LIFECYCLE",
        )
        self.assertEqual(
            fixture["backlog_after"]["next_single_action"]["gap_id"],
            "GAP-006",
        )

    def test_bootstrap_pair_cannot_promote_parent_epic_completion(
        self,
    ) -> None:
        fixture = self.policy_backlog_bootstrap_fixture()
        fixture["backlog_after"]["epics"][0][
            "current_status"
        ] = "IMPLEMENTATION_READY"

        errors = graph.validate_policy_backlog_progress_update(**fixture)

        self.assertTrue(
            any(
                "parent Backlog progress is not derived" in error
                for error in errors
            )
        )

    def test_bootstrap_pair_is_workstream_coverage_credit_only(
        self,
    ) -> None:
        workstream_id = "WS-EPIC-02"
        child_id = "GOAL-FP018"
        errors = graph.validate_completion_contracts(
            {
                workstream_id: {
                    "goal_kind": "WORKSTREAM",
                    "workstream_type": "IMPLEMENTATION",
                    "external_key": "EPIC-02",
                    "target_completion_level": "IMPLEMENTATION_READY",
                    "source_policy_ids": ["FP-017", "FP-018"],
                    "completion_requires": [],
                    "child_goal_ids": [],
                },
                child_id: {
                    "goal_kind": "WORK_ITEM",
                    "work_item_type": "POLICY_GAP_WORK",
                    "parent_goal_id": workstream_id,
                    "source_policy_ids": ["FP-018"],
                    "gap_ids": ["GAP-027"],
                    "completion_requires": [],
                    "child_goal_ids": [],
                },
            },
            {
                workstream_id: "COMPLETE_AT_TARGET",
                child_id: "COMPLETE_AT_TARGET",
            },
            {workstream_id: [child_id]},
            {
                "epics": [
                    {
                        "epic_id": "EPIC-02",
                        "current_status": "IN_PROGRESS",
                    }
                ]
            },
            {
                "FP-017": "GAP-026",
                "FP-018": "GAP-027",
            },
        )

        self.assertEqual(errors, [])

    def test_arbitrary_partial_pair_is_not_bootstrap_consumed(self) -> None:
        records = json.loads(
            json.dumps(graph.EXPECTED_BOOTSTRAP_CONSUMED_POLICY_GAP_PAIRS)
        )
        records[0][
            "completion_level"
        ] = "PARTIAL"

        self.assertEqual(
            graph.bootstrap_consumed_policy_gap_pair_set(records),
            set(),
        )

    def test_completed_policy_backlog_rejects_stale_next_action(
        self,
    ) -> None:
        fixture = self.policy_backlog_bootstrap_fixture()
        nodes = fixture["nodes"]
        statuses = fixture["statuses_before_update"]
        for suffix, policy_id, gap_id in (
            ("FP017", "FP-017", "GAP-026"),
            (
                "NPC",
                "NPC-PERMISSION-SESSION-LIFECYCLE",
                "GAP-006",
            ),
        ):
            goal_id = f"GOAL-{suffix}"
            nodes[goal_id] = {
                "goal_kind": "WORK_ITEM",
                "work_item_type": "POLICY_GAP_WORK",
                "parent_goal_id": "WS-EPIC-02",
                "source_policy_ids": [policy_id],
                "gap_ids": [gap_id],
            }
            statuses[goal_id] = "COMPLETE_AT_TARGET"
        fixture["backlog_after"]["epics"][0][
            "current_status"
        ] = "IMPLEMENTATION_READY"

        errors = graph.validate_policy_backlog_progress_update(**fixture)

        self.assertTrue(
            any(
                "next action must be empty" in error
                for error in errors
            )
        )

    def test_internal_producer_cannot_replace_policy_baseline(self) -> None:
        errors = graph.validate_internal_canonical_producer(
            ROOT,
            label="test",
            event={"producer_completion_receipt_binding": None},
            producer_goal_id="GOAL",
            producer_node={
                "work_item_type": "POLICY_GAP_WORK",
                "parent_goal_id": "PARENT",
            },
            produced_roles=["POLICY_BASELINE"],
            changed_subjects_by_role={"POLICY_BASELINE": ["FP-018"]},
            bindings_before={},
            bindings_after={},
            paths_by_id={},
            nodes={
                "PARENT": {
                    "source_policy_ids": ["FP-018"],
                    "gap_ids": ["GAP-027"],
                }
            },
            statuses_before_update={},
            latest_start_event=None,
            occurred_at=graph.parse_iso_datetime(
                "2026-07-24T10:00:00+09:00"
            ),
        )
        self.assertTrue(
            any("producer output role is not allowed" in error for error in errors)
        )

    def test_policy_gap_producer_cannot_directly_reassess_sibling_gap(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            gap_path = root / "gap.json"
            self.write_json(
                gap_path,
                {
                    "assessments": [
                        {
                            "gap_id": "GAP-026",
                            "source_policy_id": "FP-017",
                        },
                        {
                            "gap_id": "GAP-027",
                            "source_policy_id": "FP-018",
                        },
                    ],
                    "reassessment_scope": {
                        "directly_reassessed_gap_ids": ["GAP-026"],
                        "reviewed_gap_ids": ["GAP-026"],
                    },
                },
            )
            errors = graph.validate_internal_canonical_producer(
                root,
                label="test",
                event={"producer_completion_receipt_binding": None},
                producer_goal_id="GOAL-FP018",
                producer_node={
                    "work_item_type": "POLICY_GAP_WORK",
                    "parent_goal_id": "EPIC-02",
                    "source_policy_ids": ["FP-018"],
                    "gap_ids": ["GAP-027"],
                },
                produced_roles=["IMPLEMENTATION_GAP"],
                changed_subjects_by_role={
                    "IMPLEMENTATION_GAP": ["FP-017", "GAP-026"]
                },
                bindings_before={},
                bindings_after={
                    "IMPLEMENTATION_GAP": {
                        "path": "gap.json",
                    }
                },
                paths_by_id={},
                nodes={
                    "EPIC-02": {
                        "source_policy_ids": ["FP-017", "FP-018"],
                        "gap_ids": ["GAP-026", "GAP-027"],
                    }
                },
                statuses_before_update={},
                latest_start_event=None,
                occurred_at=graph.parse_iso_datetime(
                    "2026-07-24T10:00:00+09:00"
                ),
            )

        self.assertTrue(
            any(
                "producer output exceeds its Workstream subject scope"
                in error
                for error in errors
            )
        )

    def test_artifact_producer_accepts_declared_cross_domain_row_scope(
        self,
    ) -> None:
        changed_subjects = {
            "ARTIFACT_CHANGE_LOG": ["DLV-DES-03"],
            "ARTIFACT_REGISTER": ["DLV-DES-03"],
            "MODULE_REGISTER": ["DES-01", "FP-007", "RQ-MODULE-001"],
            "PLANNED_TEST_CASES": [
                "DES-22",
                "FP-019",
                "RQ-TEST-001",
            ],
            "REQUIREMENTS_TRACEABILITY": [
                "FP-018",
                "RQ-RECOVERY-001",
            ],
        }
        producer_node = {
            "work_item_type": "ARTIFACT_WORK",
            "parent_goal_id": graph.EXPECTED_ROOT_GOAL_ID,
            "output_subject_ids_by_role": changed_subjects,
        }
        errors = graph.validate_internal_canonical_producer(
            ROOT,
            label="test",
            event={
                "producer_completion_receipt_binding": None,
                "producer_output_subject_ids_by_role": changed_subjects,
            },
            producer_goal_id="GOAL",
            producer_node=producer_node,
            produced_roles=sorted(changed_subjects),
            changed_subjects_by_role=changed_subjects,
            bindings_before={},
            bindings_after={},
            paths_by_id={},
            nodes={
                graph.EXPECTED_ROOT_GOAL_ID: {
                    "goal_kind": "MASTER",
                }
            },
            statuses_before_update={},
            latest_start_event=None,
            occurred_at=graph.parse_iso_datetime(
                "2026-07-24T10:00:00+09:00"
            ),
        )

        self.assertFalse(
            any("subject scope" in error for error in errors)
        )
        self.assertFalse(
            any("producer output role is not allowed" in error for error in errors)
        )

    def test_artifact_producer_rejects_undeclared_row_scope(self) -> None:
        declared = {
            "ARTIFACT_CHANGE_LOG": ["DLV-DES-03"],
            "ARTIFACT_REGISTER": ["DLV-DES-03"],
            "MODULE_REGISTER": ["DES-01"],
        }
        errors = graph.validate_internal_canonical_producer(
            ROOT,
            label="test",
            event={
                "producer_completion_receipt_binding": None,
                "producer_output_subject_ids_by_role": declared,
            },
            producer_goal_id="GOAL",
            producer_node={
                "work_item_type": "ARTIFACT_WORK",
                "parent_goal_id": graph.EXPECTED_ROOT_GOAL_ID,
                "output_subject_ids_by_role": declared,
            },
            produced_roles=sorted(declared),
            changed_subjects_by_role={
                "ARTIFACT_CHANGE_LOG": ["DLV-DES-03"],
                "ARTIFACT_REGISTER": ["DLV-DES-03"],
                "MODULE_REGISTER": ["DES-01", "RQ-NEW-001"],
            },
            bindings_before={},
            bindings_after={},
            paths_by_id={},
            nodes={
                graph.EXPECTED_ROOT_GOAL_ID: {
                    "goal_kind": "MASTER",
                }
            },
            statuses_before_update={},
            latest_start_event=None,
            occurred_at=graph.parse_iso_datetime(
                "2026-07-24T10:00:00+09:00"
            ),
        )

        self.assertTrue(
            any(
                "materialization-time subject scope" in error
                for error in errors
            )
        )

    def test_artifact_register_pass_claim_requires_external_authority(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            before_path = root / "before.json"
            after_path = root / "after.json"
            before = {
                "artifacts": [
                    {
                        "artifact_type_code": "DLV-WS-01",
                        "artifact_instance_id": "ART-WS-01-001",
                        "state": {
                            "lifecycle_status": "PLANNED",
                            "verification_status": "NOT_RUN",
                            "blockers": ["ACTUAL_TEST_REQUIRED"],
                            "approval_status": "NOT_APPROVED",
                            "baseline_status": "NOT_BASELINED",
                        },
                    }
                ]
            }
            after = json.loads(json.dumps(before))
            after["artifacts"][0]["state"]["verification_status"] = "PASS"
            after["artifacts"][0]["state"]["blockers"] = []
            self.write_json(before_path, before)
            self.write_json(after_path, after)
            before_binding = {
                "path": "before.json",
                "file_sha256": graph.sha256_file(before_path),
            }
            after_binding = {
                "path": "after.json",
                "file_sha256": graph.sha256_file(after_path),
            }

            errors, changed, authorization_required, global_changed = (
                graph.artifact_register_delta(
                    root,
                    before_binding,
                    after_binding,
                )
            )

        self.assertEqual(errors, [])
        self.assertEqual(changed, ["DLV-WS-01"])
        self.assertTrue(authorization_required)
        self.assertFalse(global_changed)
        self.assertTrue(
            graph.canonical_role_requires_external_authorization(
                root,
                "ARTIFACT_REGISTER",
                before_binding,
                after_binding,
            )
        )

    def test_internal_technical_artifact_approval_uses_delegated_path(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            before_path = root / "before.json"
            after_path = root / "after.json"
            before = {
                "artifacts": [
                    {
                        "artifact_type_code": "DLV-DES-03",
                        "display_code": "DES-03",
                        "artifact_instance_id": "ART-DES-03-001",
                        "management_contract": {
                            "change_profile_id": "VERSIONED_DOCUMENT"
                        },
                        "state": {
                            "lifecycle_status": "DRAFT",
                            "verification_status": "STRUCTURE_CHECKED",
                            "blockers": [],
                            "approval_status": "NOT_APPROVED",
                            "baseline_status": "NOT_BASELINED",
                        },
                    }
                ]
            }
            after = json.loads(json.dumps(before))
            after["artifacts"][0]["state"] = {
                "lifecycle_status": "APPROVED_BASELINED",
                "verification_status": "CONTENT_APPROVED_AND_HASH_BOUND",
                "blockers": [],
                "approval_status": "APPROVED",
                "baseline_status": "BASELINED",
            }
            self.write_json(before_path, before)
            self.write_json(after_path, after)
            before_binding = {
                "path": "before.json",
                "file_sha256": graph.sha256_file(before_path),
            }
            after_binding = {
                "path": "after.json",
                "file_sha256": graph.sha256_file(after_path),
            }

            _, _, sensitive, _ = graph.artifact_register_delta(
                root,
                before_binding,
                after_binding,
            )
            external = (
                graph.canonical_role_requires_external_authorization(
                    root,
                    "ARTIFACT_REGISTER",
                    before_binding,
                    after_binding,
                )
            )

        self.assertTrue(sensitive)
        self.assertFalse(external)

    def test_artifact_register_external_claim_requires_external_authority(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            before_path = root / "before.json"
            after_path = root / "after.json"
            before = {
                "artifacts": [
                    {
                        "artifact_type_code": "DLV-TST-20",
                        "artifact_instance_id": "ART-TST-20-001",
                        "state": {"lifecycle_status": "PLANNED"},
                        "location": {"external_source": None},
                        "record_controls": {
                            "external_record_hash": None,
                            "external_signature_status": "NOT_APPLICABLE",
                        },
                        "dates": {"external_signed_or_issued_at": None},
                    }
                ]
            }
            after = json.loads(json.dumps(before))
            after["artifacts"][0]["location"]["external_source"] = "lab"
            after["artifacts"][0]["record_controls"][
                "external_record_hash"
            ] = "a" * 64
            after["artifacts"][0]["record_controls"][
                "external_signature_status"
            ] = "SIGNED"
            after["artifacts"][0]["dates"][
                "external_signed_or_issued_at"
            ] = "2026-07-24T10:00:00+09:00"
            self.write_json(before_path, before)
            self.write_json(after_path, after)

            _, _, authorization_required, _ = graph.artifact_register_delta(
                root,
                {
                    "path": "before.json",
                    "file_sha256": graph.sha256_file(before_path),
                },
                {
                    "path": "after.json",
                    "file_sha256": graph.sha256_file(after_path),
                },
            )

        self.assertTrue(authorization_required)

    def test_artifact_register_integrity_timestamp_is_not_external_state(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            before_path = root / "before.json"
            after_path = root / "after.json"
            before = {
                "artifacts": [
                    {
                        "artifact_type_code": "DLV-DOC-01",
                        "artifact_instance_id": "ART-DOC-01-001",
                        "state": {"lifecycle_status": "ACTIVE"},
                        "integrity": {
                            "last_verified_at": "2026-07-23T10:00:00+09:00"
                        },
                    }
                ]
            }
            after = json.loads(json.dumps(before))
            after["artifacts"][0]["integrity"][
                "last_verified_at"
            ] = "2026-07-23T11:00:00+09:00"
            self.write_json(before_path, before)
            self.write_json(after_path, after)

            errors, changed, authorization_required, global_changed = (
                graph.artifact_register_delta(
                    root,
                    {
                        "path": "before.json",
                        "file_sha256": graph.sha256_file(before_path),
                    },
                    {
                        "path": "after.json",
                        "file_sha256": graph.sha256_file(after_path),
                    },
                )
            )

        self.assertEqual(errors, [])
        self.assertEqual(changed, ["DLV-DOC-01"])
        self.assertFalse(authorization_required)
        self.assertFalse(global_changed)

    def test_artifact_terminal_state_rejects_prefix_lookalikes(self) -> None:
        self.assertFalse(
            graph.artifact_terminal_state_is_valid(
                {
                    "lifecycle_status": "APPROVED_BASELINED",
                    "verification_status": "PASS_FAKE",
                    "approval_status": "APPROVED_NO",
                    "baseline_status": "BASELINED",
                }
            )
        )
        self.assertTrue(
            graph.artifact_terminal_state_is_valid(
                {
                    "lifecycle_status": "APPROVED_BASELINED",
                    "verification_status": "CONTENT_APPROVED_AND_HASH_BOUND",
                    "approval_status": "APPROVED",
                    "baseline_status": "BASELINED",
                }
            )
        )

    def test_artifact_change_log_scope_uses_artifact_type_codes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            before_path = root / "before-log.json"
            after_path = root / "after-log.json"
            register_path = root / "register.json"
            goal_path = root / "goal.md"
            artifact_path = root / "artifact.md"
            goal_path.write_text("# Goal\n", encoding="utf-8")
            artifact_path.write_text("# Artifact\n", encoding="utf-8")
            self.write_json(before_path, {"changes": []})
            self.write_json(
                register_path,
                {
                    "artifacts": [
                        {
                            "artifact_type_code": "DLV-WS-01",
                            "display_code": "WS-01",
                        }
                    ]
                },
            )
            register_binding = {
                "path": "register.json",
                "file_sha256": graph.sha256_file(register_path),
            }
            self.write_json(
                after_path,
                {
                    "changes": [
                        {
                            "change_id": "CHG-001",
                            "date": "2026-07-24",
                            "change_type": "INTERNAL_ARTIFACT_UPDATE",
                            "title": "검증된 산출물 갱신",
                            "reason": "Goal 완료 결과 반영",
                            "before_summary": "이전 내용",
                            "after_summary": "검증 후 내용",
                            "affected_artifact_codes": ["WS-01"],
                            "affected_paths": ["artifact.md"],
                            "affected_path_bindings": [
                                {
                                    "path": "artifact.md",
                                    "before_sha256": None,
                                    "after_sha256": graph.sha256_file(
                                        artifact_path
                                    ),
                                }
                            ],
                            "source_baseline_id": "PB-1",
                            "lifecycle_status": "ACTIVE",
                            "producer_receipt_role": (
                                "WORK_ITEM_COMPLETION::GOAL"
                            ),
                            "source_goal_binding": {
                                "document_id": "GOAL",
                                "path": "goal.md",
                                "file_sha256": graph.sha256_file(goal_path),
                            },
                            "artifact_register_binding_after": (
                                register_binding
                            ),
                        }
                    ]
                },
            )

            errors, scope = graph.artifact_change_log_delta_scope(
                root,
                {
                    "path": "before-log.json",
                    "file_sha256": graph.sha256_file(before_path),
                },
                {
                    "path": "after-log.json",
                    "file_sha256": graph.sha256_file(after_path),
                },
                register_binding,
            )

        self.assertEqual(errors, [])
        self.assertEqual(scope, ["DLV-WS-01"])

    def test_artifact_change_log_rejects_unbound_placeholder_row(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            before_path = root / "before-log.json"
            after_path = root / "after-log.json"
            register_path = root / "register.json"
            self.write_json(before_path, {"changes": []})
            self.write_json(
                after_path,
                {
                    "changes": [
                        {
                            "change_id": "CHG-PLACEHOLDER",
                            "affected_artifact_codes": ["DES-03"],
                        }
                    ]
                },
            )
            self.write_json(
                register_path,
                {
                    "artifacts": [
                        {
                            "artifact_type_code": "DLV-DES-03",
                            "display_code": "DES-03",
                        }
                    ]
                },
            )
            register_binding = {
                "path": "register.json",
                "file_sha256": graph.sha256_file(register_path),
            }
            errors, _ = graph.artifact_change_log_delta_scope(
                root,
                {
                    "path": "before-log.json",
                    "file_sha256": graph.sha256_file(before_path),
                },
                {
                    "path": "after-log.json",
                    "file_sha256": graph.sha256_file(after_path),
                },
                register_binding,
            )

        self.assertTrue(
            any("row semantics differ" in error for error in errors)
        )

    def test_change_log_paths_must_match_implementation_record(self) -> None:
        errors = (
            graph.validate_artifact_change_log_implementation_binding(
                appended_rows=[
                    {
                        "affected_path_bindings": [
                            {
                                "path": "docs/wrong.md",
                                "before_sha256": "1" * 64,
                                "after_sha256": "2" * 64,
                            }
                        ]
                    }
                ],
                implementation={
                    "changed_artifacts": [
                        {
                            "path": "docs/actual.md",
                            "before_sha256": "1" * 64,
                            "after_sha256": "3" * 64,
                        }
                    ]
                },
            )
        )
        self.assertTrue(
            any("implementation record" in error for error in errors)
        )

    def test_artifact_register_rejects_stale_content_seal(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            before_path = root / "before.json"
            after_path = root / "after.json"
            before = {
                "artifacts": [],
                "content_sha256": "0" * 64,
            }
            after = {
                "artifacts": [
                    {
                        "artifact_type_code": "DLV-DOC-01",
                        "artifact_instance_id": "ART-DOC-01-001",
                    }
                ],
                "content_sha256": "0" * 64,
            }
            self.write_json(before_path, before)
            self.write_json(after_path, after)

            errors, _, _, _ = graph.artifact_register_delta(
                root,
                {
                    "path": "before.json",
                    "file_sha256": graph.sha256_file(before_path),
                },
                {
                    "path": "after.json",
                    "file_sha256": graph.sha256_file(after_path),
                },
            )

        self.assertTrue(any("content seal differs" in error for error in errors))

    def test_artifact_producer_requires_register_change_log_pair(self) -> None:
        errors = graph.validate_internal_canonical_producer(
            ROOT,
            label="test",
            event={
                "producer_completion_receipt_binding": None,
                "producer_output_subject_ids_by_role": {
                    "MODULE_REGISTER": ["FP-018"]
                },
            },
            producer_goal_id="GOAL",
            producer_node={
                "work_item_type": "ARTIFACT_WORK",
                "parent_goal_id": graph.EXPECTED_ROOT_GOAL_ID,
                "output_subject_ids_by_role": {
                    "MODULE_REGISTER": ["FP-018"]
                },
            },
            produced_roles=["MODULE_REGISTER"],
            changed_subjects_by_role={
                "MODULE_REGISTER": ["FP-018"]
            },
            bindings_before={},
            bindings_after={},
            paths_by_id={},
            nodes={graph.EXPECTED_ROOT_GOAL_ID: {"goal_kind": "MASTER"}},
            statuses_before_update={},
            latest_start_event=None,
            occurred_at=graph.parse_iso_datetime(
                "2026-07-24T10:00:00+09:00"
            ),
        )

        self.assertTrue(
            any(
                "must atomically update the artifact register" in error
                for error in errors
            )
        )

    def test_four_field_internal_results_cannot_complete_work(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            result_items = []
            for kind in graph.legacy.WORK_ITEM_RESULT_EVIDENCE_KINDS:
                path = root / f"{kind.lower()}.json"
                self.write_json(
                    path,
                    {
                        "goal_id": "GOAL",
                        "kind": kind,
                        "status": "PASS",
                        "observed_at": "2026-07-24T10:00:00+09:00",
                    },
                )
                result_items.append(
                    {
                        "kind": kind,
                        "path": path.name,
                        "sha256": graph.sha256_file(path),
                    }
                )
            receipt_path = root / "receipt.json"
            self.write_json(
                receipt_path,
                {"result_evidence": result_items},
            )

            errors = graph.validate_internal_producer_result_evidence(
                root,
                goal_id="GOAL",
                node={
                    "work_item_type": "POLICY_GAP_WORK",
                    "source_policy_ids": ["FP-018"],
                    "gap_ids": ["GAP-027"],
                },
                binding_snapshot={
                    "WORK_ITEM_COMPLETION::GOAL": {
                        "path": "receipt.json",
                        "file_sha256": graph.sha256_file(receipt_path),
                    }
                },
            )

        self.assertTrue(
            any("changed_artifacts is missing" in error for error in errors)
        )
        self.assertTrue(
            any("verification checks are missing" in error for error in errors)
        )
        self.assertTrue(
            any("canonical bindings differ" in error for error in errors)
        )
        self.assertTrue(
            any("distinct identified actors" in error for error in errors)
        )
        self.assertTrue(
            any("reviewer provenance differs" in error for error in errors)
        )

    def test_artifact_work_materialization_requires_nonempty_scope(
        self,
    ) -> None:
        errors = graph.validate_artifact_work_materialization_scope(
            ROOT,
            "GOAL",
            {
                "work_item_type": "ARTIFACT_WORK",
                "materialized_from_role": "ARTIFACT_REGISTER",
            },
        )

        self.assertTrue(
            any("materialization output scope is invalid" in error for error in errors)
        )

    def test_inactive_pwa_artifact_cannot_be_materialized(self) -> None:
        checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        binding = graph.canonical_binding_map(checkpoint)[
            "ARTIFACT_REGISTER"
        ]
        errors = graph.validate_artifact_work_materialization_scope(
            ROOT,
            "GOAL",
            {
                "work_item_type": "ARTIFACT_WORK",
                "materialized_from_role": "ARTIFACT_REGISTER",
                "materialized_from_path": binding["path"],
                "materialized_from_document_id": binding["document_id"],
                "materialized_from_sha256": binding["file_sha256"],
                "output_subject_ids_by_role": {
                    "ARTIFACT_REGISTER": ["DLV-WS-16"],
                    "ARTIFACT_CHANGE_LOG": ["DLV-WS-16"],
                },
            },
        )

        self.assertTrue(
            any("target is not active" in error for error in errors)
        )

    def test_approved_design_downgrade_still_requires_external_authority(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            before_path = root / "before.json"
            after_path = root / "after.json"
            self.write_json(
                before_path,
                {
                    "metadata": {
                        "approval_status": "APPROVED",
                        "baseline_status": "BASELINED",
                    }
                },
            )
            self.write_json(
                after_path,
                {
                    "metadata": {
                        "approval_status": "NOT_APPROVED",
                        "baseline_status": "NOT_BASELINED",
                    }
                },
            )
            self.assertTrue(
                graph.canonical_role_requires_external_authorization(
                    root,
                    "DESIGN_TRACEABILITY",
                    {"path": "before.json"},
                    {"path": "after.json"},
                )
            )

    def test_design_product_boundary_change_is_global_and_external(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            before_path = root / "before.json"
            after_path = root / "after.json"
            self.write_json(
                before_path,
                {
                    "product_boundary": {
                        "primary_product": "ANDROID_USER_AND_ADMIN_APPS"
                    },
                    "records": [],
                },
            )
            self.write_json(
                after_path,
                {
                    "product_boundary": {
                        "primary_product": "WEB_PWA"
                    },
                    "records": [],
                },
            )
            before_binding = {
                "role": "DESIGN_TRACEABILITY",
                "document_id": "BEFORE",
                "path": "before.json",
                "file_sha256": graph.sha256_file(before_path),
            }
            after_binding = {
                "role": "DESIGN_TRACEABILITY",
                "document_id": "AFTER",
                "path": "after.json",
                "file_sha256": graph.sha256_file(after_path),
            }
            errors, subjects = graph.canonical_changed_subject_ids_by_role(
                root,
                changed_roles=["DESIGN_TRACEABILITY"],
                bindings_before={"DESIGN_TRACEABILITY": before_binding},
                bindings_after={"DESIGN_TRACEABILITY": after_binding},
            )
            self.assertEqual(errors, [])
            self.assertEqual(subjects["DESIGN_TRACEABILITY"], ["*"])
            self.assertTrue(
                graph.canonical_role_requires_external_authorization(
                    root,
                    "DESIGN_TRACEABILITY",
                    before_binding,
                    after_binding,
                )
            )

    def test_artifact_change_log_accepts_append_and_rejects_rewrite(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            before_path = root / "before.json"
            after_path = root / "after.json"
            before = {"changes": [{"change_id": "CHG-001", "value": "old"}]}
            after = {
                "changes": [
                    {"change_id": "CHG-001", "value": "old"},
                    {"change_id": "CHG-002", "value": "new"},
                ]
            }
            self.write_json(before_path, before)
            self.write_json(after_path, after)
            before_binding = {
                "path": "before.json",
                "file_sha256": graph.sha256_file(before_path),
            }
            after_binding = {
                "path": "after.json",
                "file_sha256": graph.sha256_file(after_path),
            }
            self.assertEqual(
                graph.validate_nonretroactive_ledger_update(
                    root,
                    label="test",
                    role="ARTIFACT_CHANGE_LOG",
                    binding_before=before_binding,
                    binding_after=after_binding,
                ),
                [],
            )
            after["changes"][0]["value"] = "rewritten"
            self.write_json(after_path, after)
            after_binding["file_sha256"] = graph.sha256_file(after_path)
            self.assertTrue(
                graph.validate_nonretroactive_ledger_update(
                    root,
                    label="test",
                    role="ARTIFACT_CHANGE_LOG",
                    binding_before=before_binding,
                    binding_after=after_binding,
                )
            )

    def test_authority_roster_rotation_replays_history_and_rejects_rollback(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)

            def write_roster(
                name: str,
                sequence: int,
                effective_at: str,
                predecessor: dict | None,
            ) -> dict:
                path = root / f"{name}.json"
                self.write_json(
                    path,
                    {
                        "sequence": sequence,
                        "effective_at": effective_at,
                        "supersedes_authority_roster": predecessor,
                    },
                )
                return {
                    "document_id": name,
                    "path": f"{name}.json",
                    "file_sha256": graph.sha256_file(path),
                }

            roster_b = write_roster(
                "B",
                1,
                "2026-07-24T09:00:00+09:00",
                None,
            )
            roster_c = write_roster(
                "C",
                2,
                "2026-07-24T10:00:00+09:00",
                {
                    "document_id": "B",
                    "file_sha256": roster_b["file_sha256"],
                },
            )
            roster_d = write_roster(
                "D",
                3,
                "2026-07-24T11:00:00+09:00",
                {
                    "document_id": "C",
                    "file_sha256": roster_c["file_sha256"],
                },
            )
            with patch.object(
                graph.legacy,
                "EXPECTED_AUTHORITY_ROSTER_SHA256",
                roster_b["file_sha256"],
            ), patch.object(
                graph.legacy,
                "EXPECTED_AUTHORITY_ROSTER_SHA256_HISTORY",
                (),
            ):
                self.assertEqual(
                    graph.validate_authority_roster_rotation(
                        root,
                        label="genesis",
                        binding_before=None,
                        binding_after=roster_b,
                        occurred_at=graph.parse_iso_datetime(
                            "2026-07-24T09:01:00+09:00"
                        ),
                    ),
                    [],
                )
            with patch.object(
                graph.legacy,
                "EXPECTED_AUTHORITY_ROSTER_SHA256",
                roster_d["file_sha256"],
            ), patch.object(
                graph.legacy,
                "EXPECTED_AUTHORITY_ROSTER_SHA256_HISTORY",
                (roster_b["file_sha256"], roster_c["file_sha256"]),
            ):
                self.assertEqual(
                    graph.validate_authority_roster_rotation(
                        root,
                        label="B-to-C",
                        binding_before=roster_b,
                        binding_after=roster_c,
                        occurred_at=graph.parse_iso_datetime(
                            "2026-07-24T10:01:00+09:00"
                        ),
                    ),
                    [],
                )
                self.assertEqual(
                    graph.validate_authority_roster_rotation(
                        root,
                        label="C-to-D",
                        binding_before=roster_c,
                        binding_after=roster_d,
                        occurred_at=graph.parse_iso_datetime(
                            "2026-07-24T11:01:00+09:00"
                        ),
                    ),
                    [],
                )
                self.assertTrue(
                    graph.validate_authority_roster_rotation(
                        root,
                        label="rollback",
                        binding_before=roster_d,
                        binding_after=roster_c,
                        occurred_at=graph.parse_iso_datetime(
                            "2026-07-24T12:00:00+09:00"
                        ),
                    )
                )

    def test_blocker_and_operation_chronology_reject_time_reversal(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            raw_path = root / "raw.json"
            raw_path.write_text("{}", encoding="utf-8")
            blocker_path = root / "blocker.json"
            blocker_receipt = {
                "execution_window": {
                    "started_at": "2026-07-24T10:02:00+09:00",
                    "ended_at": "2026-07-24T10:05:00+09:00",
                },
                "decided_at": "2026-07-24T10:06:00+09:00",
                "generated_at": "2026-07-24T10:07:00+09:00",
                "raw_evidence": [
                    {
                        "path": "raw.json",
                        "sha256": graph.sha256_file(raw_path),
                        "collected_at": "2026-07-24T10:04:00+09:00",
                    }
                ],
            }
            self.write_json(blocker_path, blocker_receipt)
            blocker_binding = {
                "path": "blocker.json",
                "file_sha256": graph.sha256_file(blocker_path),
            }
            chronology_args = {
                "root": root,
                "label": "test",
                "reference": "BLOCKER",
                "bindings": {"BLOCKER": blocker_binding},
                "blocker_created_at": graph.parse_iso_datetime(
                    "2026-07-24T10:00:00+09:00"
                ),
                "blocker_recorded_at": graph.parse_iso_datetime(
                    "2026-07-24T10:01:00+09:00"
                ),
                "blocker_resolved_at": graph.parse_iso_datetime(
                    "2026-07-24T10:08:00+09:00"
                ),
            }
            self.assertEqual(
                graph.validate_blocker_resolution_event_chronology(
                    **chronology_args
                ),
                [],
            )
            chronology_args["blocker_resolved_at"] = graph.parse_iso_datetime(
                "2026-07-24T10:05:30+09:00"
            )
            self.assertTrue(
                graph.validate_blocker_resolution_event_chronology(
                    **chronology_args
                )
            )

            operation_path = root / "operation.json"
            operation = {
                "candidate_sha256": "1" * 64,
                "execution_window": {
                    "started_at": "2026-07-24T10:02:00+09:00",
                    "ended_at": "2026-07-24T10:30:00+09:00",
                },
                "reviewer": {
                    "decided_at": "2026-07-24T10:40:00+09:00"
                },
                "generated_at": "2026-07-24T10:45:00+09:00",
                "raw_evidence": [
                    {"collected_at": "2026-07-24T10:15:00+09:00"}
                ],
            }
            self.write_json(operation_path, operation)
            operation_binding = {
                "path": "operation.json",
                "file_sha256": graph.sha256_file(operation_path),
            }
            operation_args = {
                "root": root,
                "goal_id": "OPERATION",
                "node": {},
                "work_type": "OPERATION_EVENT",
                "binding_snapshot": {
                    "OPERATION_EVENT_RECORD": operation_binding
                },
                "paths_by_id": {},
                "nodes": {},
                "latest_start_event": {
                    "occurred_at": "2026-07-24T10:01:00+09:00",
                    "start_evidence_bindings": {},
                },
                "completion_event": {
                    "occurred_at": "2026-07-24T11:00:00+09:00"
                },
            }
            self.assertEqual(
                graph.validate_specialized_evidence_chronology(
                    **operation_args
                ),
                [],
            )
            operation["execution_window"][
                "ended_at"
            ] = "2026-07-24T11:01:00+09:00"
            self.write_json(operation_path, operation)
            operation_binding["file_sha256"] = graph.sha256_file(
                operation_path
            )
            self.assertTrue(
                any(
                    "operation evidence chronology differs" in error
                    for error in graph.validate_specialized_evidence_chronology(
                        **operation_args
                    )
                )
            )

    def test_formal_work_item_cannot_omit_typed_start_dependencies(self) -> None:
        checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
        nodes = self.load_nodes(ROOT, manifest, include_dynamic=True)
        paths = {}
        for relative in checkpoint["goal_execution"]["goal_document_paths"]:
            metadata, _ = graph.parse_goal(ROOT / relative)
            paths[metadata["goal_id"]] = relative
        goal_id = "WS-GOAL-EPIC-02-FP-018-R001"
        nodes[goal_id]["work_item_type"] = "FORMAL_TEST_RUN"
        nodes[goal_id]["parent_goal_id"] = "WS-GOAL-EPIC-12"
        nodes[goal_id]["target_completion_level"] = (
            "FORMAL_VERIFICATION_ACTION_COMPLETE"
        )
        nodes[goal_id]["start_requires"] = []
        bindings = graph.canonical_binding_map(checkpoint)
        _, mapping = graph.load_policy_gap_mapping(ROOT, bindings)

        errors = graph.validate_dynamic_work_items(
            ROOT,
            manifest,
            nodes,
            paths,
            mapping,
            bindings,
        )

        self.assertTrue(
            any(
                "start requirements lack 1 completed INTEGRATION_CANDIDATE" in error
                for error in errors
            )
        )

    def test_integration_candidate_requires_all_component_workstreams(
        self,
    ) -> None:
        checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
        nodes = self.load_nodes(ROOT, manifest, include_dynamic=True)
        paths = {}
        for relative in checkpoint["goal_execution"]["goal_document_paths"]:
            metadata, _ = graph.parse_goal(ROOT / relative)
            paths[metadata["goal_id"]] = relative
        goal_id = "WS-GOAL-EPIC-02-FP-018-R001"
        nodes[goal_id]["work_item_type"] = "INTEGRATION_CANDIDATE"
        nodes[goal_id]["parent_goal_id"] = "WS-GOAL-EPIC-11"
        nodes[goal_id]["target_completion_level"] = "IMMUTABLE_CANDIDATE_BOUND"
        nodes[goal_id]["start_requires"] = ["WS-GOAL-EPIC-01"]
        bindings = graph.canonical_binding_map(checkpoint)
        _, mapping = graph.load_policy_gap_mapping(ROOT, bindings)

        errors = graph.validate_dynamic_work_items(
            ROOT,
            manifest,
            nodes,
            paths,
            mapping,
            bindings,
        )

        self.assertTrue(
            any(
                "start contract omits required Goal IDs"
                in error
                for error in errors
            )
        )

    def test_formal_start_evidence_fails_closed_when_receipts_are_missing(
        self,
    ) -> None:
        errors = graph.validate_typed_start_evidence(
            ROOT,
            label="test formal start",
            event={"evidence_refs": []},
            goal_id="WS-GOAL-FORMAL-TEST",
            node={"work_item_type": "FORMAL_TEST_RUN"},
            binding_snapshot={},
            paths_by_id={},
            completion_event_by_goal={},
            completion_bindings_by_goal={},
            nodes={},
            occurred_at=graph.parse_iso_datetime("2026-07-24T19:43:00+09:00"),
        )

        self.assertTrue(
            any("typed start evidence references differ" in error for error in errors)
        )
        self.assertTrue(
            any("start evidence binding snapshot differs" in error for error in errors)
        )
        self.assertTrue(
            any("lacks a matching completed producer" in error for error in errors)
        )

    def test_identity_only_integration_candidate_manifest_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "candidate.json"
            self.write_json(
                path,
                {
                    "schema_version": "1.0",
                    "evidence_type": "INTEGRATION_CANDIDATE_MANIFEST",
                    "document_id": "CANDIDATE-TEST",
                    "status": "BOUND",
                    "generated_at": "2026-07-24T19:40:00+09:00",
                },
            )
            binding = {
                "path": "candidate.json",
                "document_id": "CANDIDATE-TEST",
                "file_sha256": graph.sha256_file(path),
            }

            errors, _, _ = graph.validate_integration_candidate_manifest(
                root,
                label="candidate test",
                binding=binding,
            )

        self.assertTrue(any("components are missing" in error for error in errors))
        self.assertTrue(any("source commit hash is invalid" in error for error in errors))
        self.assertTrue(any("sbom differs" in error for error in errors))

    def test_completion_candidate_must_match_goal_started_candidate(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            candidate_path = root / "candidate.json"
            report_path = root / "formal-report.json"
            self.write_json(candidate_path, {"candidate": "A"})
            candidate_hash = graph.sha256_file(candidate_path)
            self.write_json(
                report_path,
                {
                    "candidate_sha256": "b" * 64,
                    "execution_window": {
                        "started_at": "2026-07-24T19:44:00+09:00",
                    },
                    "generated_at": "2026-07-24T19:47:00+09:00",
                },
            )
            errors = graph.validate_specialized_evidence_chronology(
                root,
                goal_id="WS-GOAL-FORMAL-TEST",
                node={},
                work_type="FORMAL_TEST_RUN",
                binding_snapshot={
                    "FORMAL_TEST_REPORT": {
                        "path": "formal-report.json",
                        "file_sha256": graph.sha256_file(report_path),
                    }
                },
                paths_by_id={},
                nodes={},
                latest_start_event={
                    "occurred_at": "2026-07-24T19:43:00+09:00",
                    "start_evidence_bindings": {
                        "INTEGRATION_CANDIDATE_MANIFEST": {
                            "path": "candidate.json",
                            "file_sha256": candidate_hash,
                        }
                    },
                },
                completion_event={
                    "occurred_at": "2026-07-24T19:48:00+09:00",
                    "started_candidate_sha256": candidate_hash,
                },
            )

        self.assertTrue(
            any(
                "completion evidence candidate differs from GOAL_STARTED" in error
                for error in errors
            )
        )

    def test_goal_started_event_invokes_typed_start_gate(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_and_start_focus(root, checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            with patch.object(
                graph,
                "validate_typed_start_evidence",
                return_value=["START_GATE_MARKER"],
            ) as validator:
                errors = graph.validate(root, check_continuation=False)

        self.assertTrue(validator.called)
        self.assertIn("START_GATE_MARKER", errors)

    def test_activation_then_in_progress_focus_is_valid(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_and_start_focus(root, checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertEqual(errors, [])

    def test_historical_start_snapshot_may_differ_from_reconciled_checkpoint(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_and_start_focus(root, checkpoint)
            start_event = checkpoint["goal_execution"][
                "transition_history"
            ][-1]
            historical_snapshot = json.loads(
                json.dumps(start_event["repository_snapshot_before"])
            )
            checkpoint["working_tree_snapshot"][
                "content_set_sha256"
            ] = "a" * 64
            self.assertNotEqual(
                historical_snapshot[
                    "checkpoint_content_set_sha256"
                ],
                checkpoint["working_tree_snapshot"][
                    "content_set_sha256"
                ],
            )
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertEqual(errors, [])

    def test_start_gate_rejects_a_substitute_command(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_and_start_focus(root, checkpoint)
            event = checkpoint["goal_execution"]["transition_history"][-1]
            binding = event["implementation_start_gate_binding"]
            receipt_path = root / binding["path"]
            receipt = graph.load_json(receipt_path)
            receipt["check_runs"][-1]["command"] = "true"
            self.write_json(receipt_path, receipt)
            binding["file_sha256"] = graph.sha256_file(receipt_path)
            self.rehash_event(checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "check result differs: REPOSITORY_STATE" in error
                for error in errors
            )
        )

    def test_start_gate_receipt_snapshot_must_equal_event_snapshot(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_and_start_focus(root, checkpoint)
            event = checkpoint["goal_execution"]["transition_history"][-1]
            binding = event["implementation_start_gate_binding"]
            receipt_path = root / binding["path"]
            receipt = graph.load_json(receipt_path)
            receipt["repository_snapshot"]["content_set_sha256"] = "7" * 64
            self.write_json(receipt_path, receipt)
            binding["file_sha256"] = graph.sha256_file(receipt_path)
            self.rehash_event(checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "repository snapshot log/receipt/event binding differs"
                in error
                for error in errors
            )
        )

    def test_full_start_repository_state_is_the_exact_last_check(self) -> None:
        self.assertEqual(graph.CHECK_COMMAND_CONTRACT_VERSION, "2026-07-24.2")
        self.assertEqual(len(graph.IMPLEMENTATION_START_GATE_CHECKS), 19)
        self.assertEqual(
            graph.IMPLEMENTATION_START_GATE_CHECKS[-1],
            (
                "REPOSITORY_STATE",
                ': "${WALKSAFE_GATE_EVENT_ID:?required}" && python3 -B scripts/check_walksafe_project_continuation.py --root . --checkpoint docs/control/walksafe-project-continuation-checkpoint.json --print-gate-repository-state --gate-event-id "${WALKSAFE_GATE_EVENT_ID}"',
            ),
        )

    def test_gate_repository_payload_accepts_complete_git_visible_state(
        self,
    ) -> None:
        event_id = "WS-GATE-REPOSITORY-CAPTURE-TEST-001"
        with self.gate_capture_root() as (root, checkpoint_path):
            transaction_log = (
                root
                / "docs/control/execution/goal-gates"
                / event_id
                / "19-REPOSITORY_STATE.log"
            )
            transaction_log.parent.mkdir(parents=True, exist_ok=True)
            transaction_log.write_bytes(b"current transaction output")
            payload = graph.continuation.capture_gate_repository_state(
                root,
                checkpoint_path,
                event_id,
            )

            errors = graph.validate_gate_repository_state_payload(
                payload,
                expected_event_id=event_id,
            )

        self.assertEqual(errors, [])
        entries = {
            item["path"]: item
            for item in payload["dirty_snapshot"]["paths"]
        }
        self.assertEqual(
            set(entries),
            {
                "deleted.txt",
                "modified.txt",
                "renamed-new.txt",
                "renamed-old.txt",
                "staged.txt",
                "untracked.bin",
            },
        )
        self.assertEqual(
            entries["deleted.txt"]["worktree"]["state"],
            "ABSENT",
        )
        self.assertEqual(
            entries["renamed-new.txt"]["path_role"],
            "DESTINATION",
        )
        self.assertEqual(
            entries["renamed-old.txt"]["path_role"],
            "RENAME_SOURCE",
        )
        self.assertEqual(
            entries["staged.txt"]["status"]["xy"],
            "MM",
        )
        self.assertEqual(
            entries["untracked.bin"]["status"]["kind"],
            "UNTRACKED",
        )
        self.assertEqual(
            payload["dirty_snapshot"]["dirty_path_count"],
            len(entries),
        )
        self.assertNotIn(
            str(transaction_log.relative_to(root)),
            entries,
        )

    def test_gate_repository_payload_hashes_untracked_worktree_and_index_drift(
        self,
    ) -> None:
        event_id = "WS-GATE-REPOSITORY-DRIFT-TEST-001"
        with self.gate_capture_root() as (root, checkpoint_path):
            (root / "intent-to-add.txt").write_text(
                "intent-to-add bytes\n",
                encoding="utf-8",
            )
            subprocess.run(
                ["git", "add", "-N", "intent-to-add.txt"],
                cwd=root,
                check=True,
                capture_output=True,
            )
            before = graph.continuation.capture_gate_repository_state(
                root,
                checkpoint_path,
                event_id,
            )
            (root / "untracked.bin").write_bytes(b"\x00CHANGED!!\xff")
            (root / "modified.txt").write_text(
                "changed bytes!\n",
                encoding="utf-8",
            )
            worktree_after = (
                graph.continuation.capture_gate_repository_state(
                    root,
                    checkpoint_path,
                    event_id,
                )
            )
            subprocess.run(
                ["git", "add", "modified.txt"],
                cwd=root,
                check=True,
                capture_output=True,
            )
            index_after = graph.continuation.capture_gate_repository_state(
                root,
                checkpoint_path,
                event_id,
            )

        self.assertEqual(
            graph.validate_gate_repository_state_payload(
                worktree_after,
                expected_event_id=event_id,
            ),
            [],
        )
        self.assertEqual(
            before["dirty_snapshot"]["path_set_sha256"],
            worktree_after["dirty_snapshot"]["path_set_sha256"],
        )
        self.assertNotEqual(
            before["dirty_snapshot"]["content_set_sha256"],
            worktree_after["dirty_snapshot"]["content_set_sha256"],
        )
        self.assertNotEqual(
            worktree_after["dirty_snapshot"]["index_state_sha256"],
            index_after["dirty_snapshot"]["index_state_sha256"],
        )

    def test_gate_repository_payload_rejects_forged_schema_and_semantics(
        self,
    ) -> None:
        event_id = "WS-GATE-REPOSITORY-FORGERY-TEST-001"
        with self.gate_capture_root() as (root, checkpoint_path):
            payload = graph.continuation.capture_gate_repository_state(
                root,
                checkpoint_path,
                event_id,
            )

        mutations = []
        extra = json.loads(json.dumps(payload))
        extra["unexpected"] = True
        mutations.append(("top-level fields", extra))
        missing = json.loads(json.dumps(payload))
        missing.pop("canonicalization")
        mutations.append(("top-level fields", missing))
        wrong_canonical_type = json.loads(json.dumps(payload))
        wrong_canonical_type["canonicalization"][
            "trailing_newline_in_cli_output"
        ] = 1
        mutations.append(("canonicalization", wrong_canonical_type))
        wrong_event = json.loads(json.dumps(payload))
        wrong_event["gate_event_id"] = "OTHER-EVENT"
        mutations.append(("identity", wrong_event))
        wrong_exclusion = json.loads(json.dumps(payload))
        wrong_exclusion["transaction_exclusions"][
            "gate_event_exact_prefix"
        ] = "docs/control/execution/goal-gates/OTHER-EVENT/"
        mutations.append(("transaction exclusions", wrong_exclusion))
        wrong_exclusion_type = json.loads(json.dumps(payload))
        wrong_exclusion_type["transaction_exclusions"][
            "allowed_rule_count"
        ] = 2.0
        mutations.append(("transaction exclusions", wrong_exclusion_type))
        wrong_head = json.loads(json.dumps(payload))
        wrong_head["repository"]["head_commit"] = "0" * 64
        mutations.append(("repository identity", wrong_head))
        wrong_branch = json.loads(json.dumps(payload))
        wrong_branch["repository"]["branch"] = "../unsafe"
        mutations.append(("repository identity", wrong_branch))
        wrong_checkpoint = json.loads(json.dumps(payload))
        wrong_checkpoint["checkpoint_controlled_working_snapshot"][
            "managed_changed_path_count"
        ] = -1
        mutations.append(("checkpoint controlled summary", wrong_checkpoint))
        wrong_content = json.loads(json.dumps(payload))
        wrong_content["dirty_snapshot"]["content_set_sha256"] = "0" * 64
        mutations.append(("worktree content hash", wrong_content))
        wrong_index = json.loads(json.dumps(payload))
        wrong_index["dirty_snapshot"]["index_state_sha256"] = "0" * 64
        mutations.append(("index hash", wrong_index))
        excluded_row = json.loads(json.dumps(payload))
        excluded_row["dirty_snapshot"]["paths"][0]["path"] = (
            "docs/control/execution/goal-gates/"
            f"{event_id}/19-REPOSITORY_STATE.log"
        )
        mutations.append(("path/exclusion", excluded_row))
        wrong_absent_type = json.loads(json.dumps(payload))
        deleted_row = next(
            row
            for row in wrong_absent_type["dirty_snapshot"]["paths"]
            if row["path"] == "deleted.txt"
        )
        deleted_row["worktree"]["byte_count"] = 0.0
        mutations.append(("worktree identity", wrong_absent_type))

        for expected_error, mutated in mutations:
            with self.subTest(expected_error=expected_error):
                errors = graph.validate_gate_repository_state_payload(
                    mutated,
                    expected_event_id=event_id,
                )
                self.assertTrue(
                    any(expected_error in error for error in errors),
                    errors,
                )

    def test_gate_repository_payload_arbitrary_json_types_fail_closed(
        self,
    ) -> None:
        event_id = "WS-GATE-REPOSITORY-TYPE-FORGERY-TEST-001"
        with self.gate_capture_root() as (root, checkpoint_path):
            payload = graph.continuation.capture_gate_repository_state(
                root,
                checkpoint_path,
                event_id,
            )

        malformed_role = json.loads(json.dumps(payload))
        malformed_role["dirty_snapshot"]["paths"][0]["path_role"] = []
        malformed_kind = json.loads(json.dumps(payload))
        malformed_kind["dirty_snapshot"]["paths"][0]["status"]["kind"] = []
        malformed_counterpart = json.loads(json.dumps(payload))
        destination = next(
            row
            for row in malformed_counterpart["dirty_snapshot"]["paths"]
            if row["path_role"] == "DESTINATION"
        )
        destination["counterpart_path"] = []

        for mutated in (
            malformed_role,
            malformed_kind,
            malformed_counterpart,
        ):
            with self.subTest(mutated=mutated):
                errors = graph.validate_gate_repository_state_payload(
                    mutated,
                    expected_event_id=event_id,
                )
                self.assertTrue(errors)

    def test_gate_repository_payload_rejects_cross_field_forgery(
        self,
    ) -> None:
        event_id = "WS-GATE-REPOSITORY-CROSS-FORGERY-TEST-001"
        with self.gate_capture_root() as (root, checkpoint_path):
            payload = graph.continuation.capture_gate_repository_state(
                root,
                checkpoint_path,
                event_id,
            )

        entries = {
            row["path"]: row
            for row in payload["dirty_snapshot"]["paths"]
        }
        absent_identity = json.loads(
            json.dumps(entries["deleted.txt"]["worktree"])
        )
        present_identity = json.loads(
            json.dumps(entries["modified.txt"]["worktree"])
        )

        untracked_absent = json.loads(json.dumps(payload))
        next(
            row
            for row in untracked_absent["dirty_snapshot"]["paths"]
            if row["path"] == "untracked.bin"
        )["worktree"] = absent_identity
        self.rehash_gate_dirty_payload(untracked_absent)

        deletion_present = json.loads(json.dumps(payload))
        next(
            row
            for row in deletion_present["dirty_snapshot"]["paths"]
            if row["path"] == "deleted.txt"
        )["worktree"] = present_identity
        self.rehash_gate_dirty_payload(deletion_present)

        index_mismatch = json.loads(json.dumps(payload))
        next(
            row
            for row in index_mismatch["dirty_snapshot"]["paths"]
            if row["path"] == "modified.txt"
        )["index_entries"][0]["object_id"] = "0" * 40
        self.rehash_gate_dirty_payload(index_mismatch)

        cases = (
            (
                untracked_absent,
                "untracked status/index/worktree semantics differ",
            ),
            (
                deletion_present,
                "status/worktree presence semantics differ",
            ),
            (
                index_mismatch,
                "status/index stage-zero identity differs",
            ),
        )
        for mutated, expected_error in cases:
            with self.subTest(expected_error=expected_error):
                errors = graph.validate_gate_repository_state_payload(
                    mutated,
                    expected_event_id=event_id,
                )
                self.assertTrue(
                    any(expected_error in error for error in errors),
                    errors,
                )

    def test_start_gate_log_receipt_and_event_use_one_strict_summary(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_and_start_focus(root, checkpoint)
            event = checkpoint["goal_execution"]["transition_history"][-1]
            receipt = graph.load_json(
                root / event["implementation_start_gate_binding"]["path"]
            )

            errors, payload, summary = (
                graph.load_gate_repository_state_log(
                    root,
                    label="test start gate",
                    event_id=event["event_id"],
                    check_runs=receipt["check_runs"],
                )
            )

        self.assertEqual(errors, [])
        self.assertIsInstance(payload, dict)
        self.assertEqual(summary, receipt["repository_snapshot"])
        self.assertEqual(summary, event["repository_snapshot_before"])
        self.assertEqual(
            set(summary),
            {
                "snapshot_scope",
                "gate_event_id",
                "head_commit",
                "branch",
                "object_format",
                "git_status_raw_sha256",
                "git_status_raw_byte_count",
                "git_status_raw_record_count",
                "dirty_path_count",
                "path_set_sha256",
                "content_set_sha256",
                "index_state_sha256",
                "checkpoint_base_head",
                "checkpoint_managed_path_count",
                "checkpoint_path_set_sha256",
                "checkpoint_content_set_sha256",
                "gate_repository_state_output_sha256",
            },
        )

    def test_start_gate_rejects_noncanonical_or_mismatched_repository_log(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_and_start_focus(root, checkpoint)
            event = checkpoint["goal_execution"]["transition_history"][-1]
            binding = event["implementation_start_gate_binding"]
            receipt_path = root / binding["path"]
            receipt = graph.load_json(receipt_path)
            run = receipt["check_runs"][-1]
            log_path = root / run["output_path"]
            payload = graph.load_json(log_path)
            payload["repository"]["branch"] = "later-valid-branch"
            log_path.write_text(
                json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            run["output_sha256"] = graph.sha256_file(log_path)
            self.write_json(receipt_path, receipt)
            binding["file_sha256"] = graph.sha256_file(receipt_path)
            self.rehash_event(checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "log bytes are not canonical JSON" in error
                for error in errors
            )
        )
        self.assertTrue(
            any(
                "log/receipt/event binding differs" in error
                for error in errors
            )
        )

    def test_later_repository_drift_only_fails_current_session_assertion(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_and_start_focus(root, checkpoint)
            event = checkpoint["goal_execution"]["transition_history"][-1]
            event_id = event["event_id"]
            recorded = self.recorded_gate_repository_payload(root, event)
            later = json.loads(json.dumps(recorded))
            later["repository"]["branch"] = "later-drift"
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            with patch.object(
                graph.continuation,
                "capture_gate_repository_state",
                side_effect=AssertionError(
                    "historical validation must not capture live state"
                ),
            ) as historical_capture:
                historical_errors = graph.validate(
                    root,
                    check_continuation=False,
                )
            historical_capture.assert_not_called()

            with patch.object(
                graph.continuation,
                "capture_gate_repository_state",
                return_value=later,
            ) as current_capture:
                current_errors = graph.validate(
                    root,
                    check_continuation=False,
                    current_work_session_id=event_id,
                )

        self.assertFalse(
            any(
                "live gate repository state differs" in error
                for error in historical_errors
            )
        )
        self.assertTrue(
            any(
                "live gate repository state differs" in error
                for error in current_errors
            )
        )
        current_capture.assert_called_once()

    def test_current_session_live_capture_detects_real_byte_and_index_drift(
        self,
    ) -> None:
        event_id = "WS-GATE-REPOSITORY-LIVE-SESSION-TEST-001"
        with self.gate_capture_root() as (root, checkpoint_path):
            payload = graph.continuation.capture_gate_repository_state(
                root,
                checkpoint_path,
                event_id,
            )
            event_dir = (
                root
                / "docs/control/execution/goal-gates"
                / event_id
            )
            event_dir.mkdir(parents=True, exist_ok=True)
            log_path = event_dir / "19-REPOSITORY_STATE.log"
            log_path.write_bytes(
                graph.canonical_json_bytes(payload) + b"\n"
            )
            check_runs = [
                {}
                for _ in range(
                    graph.GATE_REPOSITORY_STATE_CHECK_NUMBER - 1
                )
            ]
            check_runs.append(
                {
                    "check_id": "REPOSITORY_STATE",
                    "output_path": str(log_path.relative_to(root)),
                    "output_sha256": graph.sha256_file(log_path),
                }
            )
            receipt_path = (
                event_dir / "implementation-start-gate-receipt.json"
            )
            receipt = {
                "document_id": "TEST-LIVE-REPOSITORY-GATE",
                "check_runs": check_runs,
            }
            self.write_json(receipt_path, receipt)
            event = {
                "event_id": event_id,
                "implementation_start_gate_binding": {
                    "document_id": receipt["document_id"],
                    "path": str(receipt_path.relative_to(root)),
                    "file_sha256": graph.sha256_file(receipt_path),
                },
            }

            immediate_errors = (
                graph.validate_current_gate_repository_state(
                    root,
                    checkpoint_path=checkpoint_path,
                    event=event,
                )
            )
            (root / "untracked.bin").write_bytes(b"later bytes")
            subprocess.run(
                ["git", "add", "modified.txt"],
                cwd=root,
                check=True,
                capture_output=True,
            )
            historical_errors, _, _ = (
                graph.load_gate_repository_state_log(
                    root,
                    label="historical live-session test",
                    event_id=event_id,
                    check_runs=check_runs,
                )
            )
            drift_errors = graph.validate_current_gate_repository_state(
                root,
                checkpoint_path=checkpoint_path,
                event=event,
            )

        self.assertEqual(immediate_errors, [])
        self.assertEqual(historical_errors, [])
        self.assertTrue(
            any(
                "live gate repository state differs" in error
                for error in drift_errors
            )
        )

    def test_older_start_event_never_triggers_live_repository_capture(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_and_start_focus(root, checkpoint)
            start_event_id = checkpoint["goal_execution"][
                "transition_history"
            ][-1]["event_id"]
            goal_id = checkpoint["goal_execution"]["focus_goal_id"]
            self.resume_goal(root, checkpoint, goal_id)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            with patch.object(
                graph.continuation,
                "capture_gate_repository_state",
                side_effect=AssertionError(
                    "older historical start must not capture live state"
                ),
            ) as capture:
                normal_errors = graph.validate(
                    root,
                    check_continuation=False,
                )
                stale_assertion_errors = graph.validate(
                    root,
                    check_continuation=False,
                    current_work_session_id=start_event_id,
                )

        capture.assert_not_called()
        self.assertFalse(
            any(
                "live gate repository state" in error
                for error in normal_errors
            )
        )
        self.assertTrue(
            any(
                "current work session is not bound" in error
                for error in stale_assertion_errors
            )
        )

    def test_current_work_session_argument_binds_latest_gate_event(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_and_start_focus(root, checkpoint)
            event_id = checkpoint["goal_execution"]["transition_history"][-1][
                "event_id"
            ]
            latest_event = checkpoint["goal_execution"][
                "transition_history"
            ][-1]
            recorded_payload = self.recorded_gate_repository_payload(
                root,
                latest_event,
            )
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            with patch.object(
                graph.continuation,
                "capture_gate_repository_state",
                return_value=recorded_payload,
            ) as capture:
                valid_errors = graph.validate(
                    root,
                    check_continuation=False,
                    current_work_session_id=event_id,
                )
                stale_errors = graph.validate(
                    root,
                    check_continuation=False,
                    current_work_session_id="STALE-WORK-SESSION",
                )

        self.assertEqual(valid_errors, [])
        capture.assert_called_once_with(
            root,
            root / graph.CHECKPOINT_RELATIVE,
            event_id,
        )
        self.assertTrue(
            any(
                "current work session is not bound" in error
                for error in stale_errors
            )
        )

    def test_in_progress_goal_can_open_a_fresh_resume_session(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_and_start_focus(root, checkpoint)
            goal_id = checkpoint["goal_execution"]["focus_goal_id"]
            self.resume_goal(root, checkpoint, goal_id)
            event_id = checkpoint["goal_execution"]["transition_history"][-1][
                "event_id"
            ]
            latest_event = checkpoint["goal_execution"][
                "transition_history"
            ][-1]
            recorded_payload = self.recorded_gate_repository_payload(
                root,
                latest_event,
            )
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            with patch.object(
                graph.continuation,
                "capture_gate_repository_state",
                return_value=recorded_payload,
            ) as capture:
                errors = graph.validate(
                    root,
                    check_continuation=False,
                    current_work_session_id=event_id,
                )

        self.assertEqual(errors, [])
        capture.assert_called_once()

    def test_resume_gate_cannot_reuse_a_previous_output_path(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_and_start_focus(root, checkpoint)
            state = checkpoint["goal_execution"]
            start_event = state["transition_history"][-1]
            goal_id = state["focus_goal_id"]
            self.resume_goal(root, checkpoint, goal_id)
            resume_event = state["transition_history"][-1]
            start_receipt = graph.load_json(
                root / start_event["implementation_start_gate_binding"]["path"]
            )
            binding = resume_event["implementation_start_gate_binding"]
            receipt_path = root / binding["path"]
            receipt = graph.load_json(receipt_path)
            receipt["check_runs"][0]["output_path"] = (
                start_receipt["check_runs"][0]["output_path"]
            )
            receipt["check_runs"][0]["output_sha256"] = (
                start_receipt["check_runs"][0]["output_sha256"]
            )
            self.write_json(receipt_path, receipt)
            binding["file_sha256"] = graph.sha256_file(receipt_path)
            self.rehash_event(checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any("check output path is reused" in error for error in errors)
        )

    def test_package_activation_cannot_start_a_goal_in_same_event(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self._activate_package_only(root, checkpoint)
            state = checkpoint["goal_execution"]
            focus_id = state["focus_goal_id"]
            event = state["transition_history"][-1]
            state["status_by_goal"][focus_id] = "IN_PROGRESS"
            event["status_changes"] = {focus_id: "IN_PROGRESS"}
            event["from_status"] = "READY"
            event["to_status"] = "IN_PROGRESS"
            event["runtime_after"] = self.runtime_snapshot(root, checkpoint)
            self.rehash_event(checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "PACKAGE_ACTIVATED must not rewrite Goal status" in error
                for error in errors
            )
        )

    def test_intermediate_event_cannot_revert_package_runtime_lifecycle(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_package(root, checkpoint)
            self.materialize_artifact_work(root, checkpoint)
            self.ready_materialized_artifact(root, checkpoint)
            event = checkpoint["goal_execution"]["transition_history"][-2]
            event["runtime_after"]["activation_status"] = "READY_NOT_ACTIVATED"
            event["runtime_after"]["package_status"] = "PREPARED_NOT_ACTIVATED"
            self.rehash_event(checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "runtime activation/package status differs from lifecycle" in error
                for error in errors
            )
        )

    def test_intermediate_event_cannot_forge_focus_work_item_identity(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_package(root, checkpoint)
            self.materialize_artifact_work(root, checkpoint)
            self.ready_materialized_artifact(root, checkpoint)
            event = checkpoint["goal_execution"]["transition_history"][-2]
            event["runtime_after"]["focus_work_item_id"] = "FORGED"
            event["runtime_after"]["focus_source"] = "FORGED"
            self.rehash_event(checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any("runtime focus Work Item ID differs" in error for error in errors)
        )
        self.assertTrue(any("runtime focus source differs" in error for error in errors))

    def test_intermediate_event_cannot_forge_artifact_queue(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_and_start_focus(root, checkpoint)
            activation_event = checkpoint["goal_execution"][
                "transition_history"
            ][1]
            activation_event["runtime_after"]["artifact_work_queue"][
                "next_assessment_target_id"
            ] = "FORGED"
            self.rehash_event(checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "runtime artifact work queue differs from replayed bindings "
                "and statuses" in error
                for error in errors
            )
        )

    def test_intermediate_event_cannot_forge_completion_boundary(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_and_start_focus(root, checkpoint)
            activation_event = checkpoint["goal_execution"][
                "transition_history"
            ][1]
            activation_event["runtime_after"]["completion_boundary"][
                "repository_scope_status"
            ] = "COMPLETE_AWAITING_EXTERNAL"
            self.rehash_event(checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "runtime completion boundary differs from replayed status, "
                "blockers, and artifact queue" in error
                for error in errors
            )
        )

    def test_dynamic_goal_materialization_does_not_rewrite_static_anchors(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            manifest_before = graph.sha256_file(root / graph.MANIFEST_RELATIVE)
            initial_event_before = checkpoint["goal_execution"]["transition_history"][0][
                "event_sha256"
            ]
            self.activate_package(root, checkpoint)
            self.materialize_artifact_work(root, checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

            self.assertEqual(graph.sha256_file(root / graph.MANIFEST_RELATIVE), manifest_before)
            self.assertEqual(
                checkpoint["goal_execution"]["transition_history"][0]["event_sha256"],
                initial_event_before,
            )
        self.assertEqual(errors, [])

    def test_artifact_materialization_must_use_next_assessment_target(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_package(root, checkpoint)
            self.materialize_artifact_work(
                root,
                checkpoint,
                artifact_code="DLV-AIML-01",
            )
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "does not satisfy the deterministic assessment target"
                in error
                for error in errors
            )
        )

    def test_goal_materialized_cannot_bypass_goal_ready(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_package(root, checkpoint)
            self.materialize_artifact_work(
                root,
                checkpoint,
                initial_status="READY",
                start_dependency="WS-GOAL-EPIC-03",
            )
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "GOAL_MATERIALIZED must create a PLANNED Goal" in error
                for error in errors
            )
        )
        self.assertTrue(
            any(
                "dynamic Goal initial status must be PLANNED" in error
                for error in errors
            )
        )

    def test_planned_goal_can_become_ready_after_dependencies_complete(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_package(root, checkpoint)
            self.materialize_artifact_work(
                root,
                checkpoint,
                initial_status="PLANNED",
            )
            self.ready_materialized_artifact(root, checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertEqual(errors, [])

    def test_goal_ready_rejects_incorrect_dependency_event_hash(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_package(root, checkpoint)
            self.materialize_artifact_work(
                root,
                checkpoint,
                initial_status="PLANNED",
            )
            self.ready_materialized_artifact(root, checkpoint)
            event = checkpoint["goal_execution"]["transition_history"][-1]
            event["readiness_basis"]["dependency_completion_events"][0][
                "event_sha256"
            ] = "0" * 64
            self.rehash_event(checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(any("readiness basis differs" in error for error in errors))

    def test_goal_ready_rejects_incomplete_dependency(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_package(root, checkpoint)
            self.materialize_artifact_work(
                root,
                checkpoint,
                initial_status="PLANNED",
                start_dependency="WS-GOAL-EPIC-03",
            )
            self.ready_materialized_artifact(
                root,
                checkpoint,
                dependency_event_sha256="0" * 64,
            )
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any("GOAL_READY dependencies are not complete" in error for error in errors)
        )

    def test_goal_ready_rejects_extra_status_change(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_package(root, checkpoint)
            self.materialize_artifact_work(
                root,
                checkpoint,
                initial_status="PLANNED",
            )
            self.ready_materialized_artifact(root, checkpoint)
            state = checkpoint["goal_execution"]
            event = state["transition_history"][-1]
            event["status_changes"]["WS-GOAL-EPIC-03"] = "BLOCKED"
            state["status_by_goal"]["WS-GOAL-EPIC-03"] = "BLOCKED"
            state["blockers_by_goal"]["WS-GOAL-EPIC-03"] = [
                {"blocker_id": "TEST"}
            ]
            state["blocked_goal_ids"] = ["WS-GOAL-EPIC-03"]
            event["blockers_after"] = state["blockers_by_goal"]
            event["runtime_after"] = self.runtime_snapshot(root, checkpoint)
            self.rehash_event(checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any("subject event must change exactly its Goal" in error for error in errors)
        )

    def test_blocker_resolved_cannot_replace_goal_ready(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_package(root, checkpoint)
            self.materialize_artifact_work(
                root,
                checkpoint,
                initial_status="PLANNED",
            )
            self.ready_materialized_artifact(root, checkpoint)
            event = checkpoint["goal_execution"]["transition_history"][-1]
            event["event_type"] = "BLOCKER_RESOLVED"
            event.pop("readiness_basis")
            self.rehash_event(checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any("blocker resolution has no active blocker" in error for error in errors)
        )

    def test_planned_goal_returns_to_planned_after_blocker_resolution(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_package(root, checkpoint)
            self.materialize_artifact_work(
                root,
                checkpoint,
                start_dependency="WS-GOAL-EPIC-03",
            )
            blocker = self.record_planned_artifact_blocker(root, checkpoint)
            self.resolve_artifact_blocker(root, checkpoint, blocker)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            with patch.object(
                graph.legacy,
                "validate_blocker_resolution_receipt",
                return_value=[],
            ):
                errors = graph.validate(root, check_continuation=False)

        goal_id = "WS-GOAL-EPIC-03-ARTIFACT-TEST-R001"
        self.assertEqual(
            checkpoint["goal_execution"]["status_by_goal"][goal_id],
            "PLANNED",
        )
        self.assertNotIn(
            goal_id,
            checkpoint["goal_execution"]["ready_frontier_goal_ids"],
        )
        self.assertFalse(any("invalid BLOCKER_RESOLVED" in error for error in errors))

    def test_new_external_request_is_rejected_for_nonfocus_goal(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_package(root, checkpoint)
            self.materialize_artifact_work(
                root,
                checkpoint,
                start_dependency="WS-GOAL-EPIC-03",
            )
            self.record_planned_artifact_blocker(root, checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "blocker request must target the current deterministic focus"
                in error
                for error in errors
            )
        )

    def test_typed_request_whitespace_alias_is_rejected_by_full_replay(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_package(root, checkpoint)
            state = checkpoint["goal_execution"]
            subject_id = state["focus_goal_id"]
            previous_focus_path = root / state["focus_goal_path"]
            event_id = (
                "WS-GOAL-GRAPH-BLOCKER-RECORDED-SPACE-TEST-001"
            )
            occurred_at = "2026-07-24T19:43:00+09:00"
            blocker_id = " EXT-SPACE-001 "
            blocker = {
                "blocker_id": blocker_id,
                "blocks_goal_id": subject_id,
                "condition_code": "EXTERNAL_EVIDENCE_REQUIRED",
                "owner": "EXTERNAL",
                "request_kind": "EXTERNAL_ACTION_EVIDENCE",
                "requested_action": " 외부 증거를 준비한다. ",
                "required_resolution_evidence_roles": [
                    f"BLOCKER_RESOLUTION::{blocker_id}"
                ],
                "authority_requirement": "EXTERNAL_ATTESTATION",
                "request_event_id": event_id,
                "target_goal_path": state["focus_goal_path"],
                "target_goal_content_sha256": graph.sha256_file(
                    previous_focus_path
                ),
                "target_work_item_type": "POLICY_GAP_WORK",
                "created_at": occurred_at,
                "return_status": "READY",
                "prompt": " 외부 증거를 준비한다. ",
            }
            blocker["request_key"] = graph.deterministic_request_key(
                blocker
            )
            blocker["blocker_snapshot_sha256"] = (
                graph.legacy.blocker_snapshot_sha256(blocker)
            )
            state["status_by_goal"][subject_id] = "AWAITING_EXTERNAL"
            state["blockers_by_goal"] = {subject_id: [blocker]}
            state["blocked_goal_ids"] = [subject_id]

            manifest = graph.load_json(root / graph.MANIFEST_RELATIVE)
            nodes = self.load_nodes(root, manifest, include_dynamic=True)
            backlog = self.load_binding_json_from_root(
                root,
                checkpoint,
                "IMPLEMENTATION_BACKLOG",
            )
            ready = graph.ready_frontier(
                nodes,
                state["status_by_goal"],
                state["materialized_child_goal_ids_by_parent"],
                state["blockers_by_goal"],
                backlog,
            )
            state["ready_frontier_goal_ids"] = ready
            next_focus_id = ready[0]
            paths_by_id = {}
            for relative in state["goal_document_paths"]:
                metadata, _ = graph.parse_goal(root / relative)
                paths_by_id[metadata["goal_id"]] = relative
            state["focus_goal_id"] = next_focus_id
            state["focus_goal_path"] = paths_by_id[next_focus_id]
            state["focus_work_item_id"] = ""
            state["focus_source"] = "WORKSTREAM_GRAPH"
            next_focus_path = root / state["focus_goal_path"]

            event = {
                "sequence": len(state["transition_history"]) + 1,
                "event_id": event_id,
                "event_type": "BLOCKER_RECORDED",
                "subject_goal_id": subject_id,
                "blocker_ids": [blocker_id],
                "occurred_on": "2026-07-24",
                "occurred_at": occurred_at,
                "previous_focus_goal_id": subject_id,
                "previous_focus_content_sha256": graph.sha256_file(
                    previous_focus_path
                ),
                "focus_goal_id": next_focus_id,
                "focus_goal_content_sha256": graph.sha256_file(
                    next_focus_path
                ),
                "from_status": "READY",
                "to_status": "AWAITING_EXTERNAL",
                "static_plan_manifest_sha256": state[
                    "static_plan_manifest_sha256"
                ],
                "status_changes": {
                    subject_id: "AWAITING_EXTERNAL"
                },
                "runtime_after": self.runtime_snapshot(root, checkpoint),
                "blockers_after": json.loads(
                    json.dumps(state["blockers_by_goal"])
                ),
                "blocker_resolution_ids_after": [],
                "source_checkpoint_version": checkpoint["schema_version"],
                "evidence_refs": [],
                "previous_event_sha256": state["transition_history"][-1][
                    "event_sha256"
                ],
            }
            event["event_sha256"] = graph.event_sha256(event)
            state["transition_history"].append(event)
            state["transition_history_anchor_sha256"] = event[
                "event_sha256"
            ]
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "typed blocker request contract differs" in error
                or "blocker record contract differs" in error
                for error in errors
            )
        )

    def test_resolved_request_key_cannot_be_reissued_later_in_history(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_package(root, checkpoint)
            self.materialize_artifact_work(
                root,
                checkpoint,
                start_dependency="WS-GOAL-EPIC-03",
            )
            first = self.record_planned_artifact_blocker(
                root,
                checkpoint,
            )
            self.resolve_artifact_blocker(root, checkpoint, first)
            self.record_planned_artifact_blocker(
                root,
                checkpoint,
                blocker_id="TEST-BLOCKER-002",
                request_key=first["request_key"],
                event_id=(
                    "WS-GOAL-GRAPH-BLOCKER-RECORDED-TEST-002"
                ),
                occurred_at="2026-07-24T19:45:00+09:00",
            )
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            with patch.object(
                graph.legacy,
                "validate_blocker_resolution_receipt",
                return_value=[],
            ):
                errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "blocker request key was already issued in history"
                in error
                for error in errors
            )
        )

    def test_resolved_blocker_id_cannot_be_reissued_later_in_history(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_package(root, checkpoint)
            self.materialize_artifact_work(
                root,
                checkpoint,
                start_dependency="WS-GOAL-EPIC-03",
            )
            first = self.record_planned_artifact_blocker(
                root,
                checkpoint,
            )
            self.resolve_artifact_blocker(root, checkpoint, first)
            self.record_planned_artifact_blocker(
                root,
                checkpoint,
                blocker_id=first["blocker_id"],
                request_key="NEW-REQUEST-KEY",
                event_id=(
                    "WS-GOAL-GRAPH-BLOCKER-RECORDED-TEST-002"
                ),
                occurred_at="2026-07-24T19:45:00+09:00",
            )
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            with patch.object(
                graph.legacy,
                "validate_blocker_resolution_receipt",
                return_value=[],
            ):
                errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "blocker ID was already issued in history" in error
                for error in errors
            )
        )
        self.assertTrue(
            any(
                "active and resolved blocker ID histories overlap" in error
                for error in errors
            )
        )

    def test_blocker_record_cannot_change_without_an_event(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_package(root, checkpoint)
            self.materialize_artifact_work(
                root,
                checkpoint,
                start_dependency="WS-GOAL-EPIC-03",
            )
            self.record_planned_artifact_blocker(root, checkpoint)
            state = checkpoint["goal_execution"]
            state["blockers_by_goal"] = json.loads(
                json.dumps(state["blockers_by_goal"])
            )
            goal_id = "WS-GOAL-EPIC-03-ARTIFACT-TEST-R001"
            state["blockers_by_goal"][goal_id][0][
                "condition_code"
            ] = "REAL_DEVICE_OR_PARTICIPANT_REQUIRED"
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any("replayed blocker records differ from runtime" in error for error in errors)
        )

    def test_blocker_resolution_cannot_promote_planned_goal_to_ready(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_package(root, checkpoint)
            self.materialize_artifact_work(
                root,
                checkpoint,
                start_dependency="WS-GOAL-EPIC-03",
            )
            blocker = self.record_planned_artifact_blocker(root, checkpoint)
            self.resolve_artifact_blocker(root, checkpoint, blocker)
            state = checkpoint["goal_execution"]
            goal_id = "WS-GOAL-EPIC-03-ARTIFACT-TEST-R001"
            state["status_by_goal"][goal_id] = "READY"
            event = state["transition_history"][-1]
            event["status_changes"][goal_id] = "READY"
            event["to_status"] = "READY"
            event["runtime_after"] = self.runtime_snapshot(root, checkpoint)
            self.rehash_event(checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            with patch.object(
                graph.legacy,
                "validate_blocker_resolution_receipt",
                return_value=[],
            ):
                errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any("invalid BLOCKER_RESOLVED transition" in error for error in errors)
        )

    def test_goal_started_cannot_self_select_a_lower_priority_branch(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_package(root, checkpoint)
            self.start_goal(root, checkpoint, "WS-GOAL-EPIC-03")
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "GOAL_STARTED subject is not deterministic focus" in error
                for error in errors
            )
        )
        self.assertTrue(
            any("only a Work Item may enter IN_PROGRESS" in error for error in errors)
        )

    def test_historical_materialization_source_survives_current_binding_change(
        self,
    ) -> None:
        checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
        nodes = self.load_nodes(ROOT, manifest, include_dynamic=True)
        paths = {}
        for relative in checkpoint["goal_execution"]["goal_document_paths"]:
            metadata, _ = graph.parse_goal(ROOT / relative)
            paths[metadata["goal_id"]] = relative
        bindings = graph.canonical_binding_map(checkpoint)
        bindings["IMPLEMENTATION_BACKLOG"] = {
            **bindings["IMPLEMENTATION_BACKLOG"],
            "document_id": "FUTURE-R009",
            "path": bindings["IMPLEMENTATION_GAP"]["path"],
            "file_sha256": bindings["IMPLEMENTATION_GAP"]["file_sha256"],
        }
        _, mapping = graph.load_policy_gap_mapping(
            ROOT,
            graph.canonical_binding_map(checkpoint),
        )

        errors = graph.validate_dynamic_work_items(
            ROOT,
            manifest,
            nodes,
            paths,
            mapping,
            bindings,
        )

        self.assertFalse(
            any("materialization source differs" in error for error in errors)
        )

    def test_materialization_event_must_use_event_time_canonical_binding(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_package(root, checkpoint)
            self.materialize_artifact_work(root, checkpoint)
            state = checkpoint["goal_execution"]
            event = state["transition_history"][-1]
            event["materialized_from_sha256"] = "0" * 64
            goal_id = event["materialized_goal_id"]
            state["dynamic_goal_inventory"][goal_id][
                "materialized_from_sha256"
            ] = "0" * 64
            self.rehash_event(checkpoint)
            state["dynamic_goal_inventory"][goal_id][
                "materialized_event_sha256"
            ] = event["event_sha256"]
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "materialization source is not canonical at this event" in error
                for error in errors
            )
        )

    def test_work_item_completion_binds_start_receipt_and_event(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_and_start_focus(root, checkpoint)
            self.add_completion_receipt_binding(root, checkpoint)
            self.complete_focus_work_item(root, checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            with patch.object(
                graph.legacy,
                "validate_work_item_completion_receipt",
                return_value=[],
            ), patch.object(
                graph,
                "CANONICAL_PRODUCER_WORK_TYPES",
                set(),
            ), patch.object(
                graph,
                "validate_internal_producer_result_evidence",
                return_value=[],
            ):
                errors = graph.validate(root, check_continuation=False)

        self.assertEqual(errors, [])

    def test_superseded_work_item_keeps_historical_receipt_semantics(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_and_start_focus(root, checkpoint)
            receipt_path = self.add_completion_receipt_binding(root, checkpoint)
            receipt = graph.load_json(receipt_path)
            receipt["result"] = "FAIL"
            self.write_json(receipt_path, receipt)
            self.refresh_latest_binding_hash(checkpoint, receipt_path)
            self.complete_focus_work_item(root, checkpoint)
            self.supersede_completed_focus_work_item(root, checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            with patch.object(
                graph.legacy,
                "validate_reopen_trigger_receipts",
                return_value=[],
            ):
                errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any("execution receipt result is not PASS" in error for error in errors)
        )

    def test_standalone_defect_successor_is_rejected(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_and_start_focus(root, checkpoint)
            self.add_completion_receipt_binding(root, checkpoint)
            self.complete_focus_work_item(root, checkpoint)
            self.supersede_completed_focus_work_item(root, checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "standalone defect/evidence successor is prohibited" in error
                for error in errors
            )
        )

    def test_archived_completion_evidence_cannot_be_rewritten(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_and_start_focus(root, checkpoint)
            self.add_completion_receipt_binding(root, checkpoint)
            self.complete_focus_work_item(root, checkpoint)
            self.supersede_completed_focus_work_item(root, checkpoint)
            checkpoint["goal_execution"][
                "archived_completion_evidence_by_goal"
            ]["WS-GOAL-EPIC-02-FP-018-R001"] = []
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            with patch.object(
                graph.legacy,
                "validate_reopen_trigger_receipts",
                return_value=[],
            ):
                errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "replayed archived completion evidence differs" in error
                for error in errors
            )
        )

    def test_workstream_completion_uses_event_time_binding_snapshot(self) -> None:
        event_bindings = {
            "FORMAL_TEST_REPORT": {
                "role": "FORMAL_TEST_REPORT",
                "path": "evidence/formal-at-completion.json",
                "document_id": "FORMAL-AT-COMPLETION",
                "file_sha256": "1" * 64,
            }
        }
        current_bindings = {
            "FORMAL_TEST_REPORT": {
                "role": "FORMAL_TEST_REPORT",
                "path": "evidence/formal-current.json",
                "document_id": "FORMAL-CURRENT",
                "file_sha256": "2" * 64,
            }
        }
        checkpoint = {
            "goal_execution": {
                "status_by_goal": {
                    "WS-GOAL-EPIC-12": "COMPLETE_AT_TARGET",
                },
                "completion_evidence_by_goal": {
                    "WS-GOAL-EPIC-12": ["FORMAL_TEST_REPORT"],
                },
                "archived_completion_evidence_by_goal": {},
            }
        }
        with patch.object(
            graph.legacy,
            "validate_phase_b_evidence_receipts",
            return_value=[],
        ) as validator:
            graph.validate_workstream_completion_semantics(
                ROOT,
                goal_id="WS-GOAL-EPIC-12",
                references=["FORMAL_TEST_REPORT"],
                binding_snapshot=event_bindings,
                paths_by_id={},
                nodes={},
                statuses_before_completion={},
                completion_event_by_goal={},
            )
            graph.validate_semantic_completion_evidence(
                ROOT,
                checkpoint,
                {},
                {},
                current_bindings,
            )

        self.assertEqual(validator.call_count, 1)
        self.assertIs(validator.call_args.args[1], event_bindings)

    def test_completed_historical_goal_survives_new_current_backlog_binding(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_and_start_focus(root, checkpoint)
            self.add_completion_receipt_binding(root, checkpoint)
            self.complete_focus_work_item(root, checkpoint)
            bindings = graph.canonical_binding_map(checkpoint)
            old_binding = bindings["IMPLEMENTATION_BACKLOG"]
            new_relative = (
                "docs/control/audits/"
                "walksafe-implementation-remediation-backlog-future-test.json"
            )
            new_path = root / new_relative
            new_path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(root / old_binding["path"], new_path)
            for binding in checkpoint["canonical_bindings"]:
                if binding["role"] == "IMPLEMENTATION_BACKLOG":
                    binding["path"] = new_relative
                    binding["file_sha256"] = graph.sha256_file(new_path)
                    break
            self.append_binding_update_event(
                root,
                checkpoint,
                ["IMPLEMENTATION_BACKLOG"],
                occurred_at="2026-07-24T19:49:00+09:00",
                event_id="WS-GOAL-GRAPH-BACKLOG-UPDATED-TEST-001",
            )
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            with patch.object(
                graph.legacy,
                "validate_work_item_completion_receipt",
                return_value=[],
            ), patch.object(
                graph,
                "CANONICAL_PRODUCER_WORK_TYPES",
                set(),
            ), patch.object(
                graph,
                "validate_internal_producer_result_evidence",
                return_value=[],
            ):
                errors = graph.validate(root, check_continuation=False)

        self.assertEqual(errors, [])

    def test_work_item_completion_rejects_random_start_hash(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_and_start_focus(root, checkpoint)
            receipt_path = self.add_completion_receipt_binding(root, checkpoint)
            receipt = graph.load_json(receipt_path)
            receipt["execution_start_event_sha256"] = "0" * 64
            self.write_json(receipt_path, receipt)
            self.refresh_latest_binding_hash(checkpoint, receipt_path)
            self.complete_focus_work_item(root, checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            with patch.object(
                graph.legacy,
                "validate_work_item_completion_receipt",
                return_value=[],
            ):
                errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any("latest execution-session event" in error for error in errors)
        )

    def test_completion_event_must_snapshot_receipt_binding(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_and_start_focus(root, checkpoint)
            self.add_completion_receipt_binding(root, checkpoint)
            self.complete_focus_work_item(root, checkpoint)
            event = checkpoint["goal_execution"]["transition_history"][-1]
            event.pop("completion_receipt_binding")
            self.rehash_event(checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            with patch.object(
                graph.legacy,
                "validate_work_item_completion_receipt",
                return_value=[],
            ):
                errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any("completion receipt binding snapshot differs" in error for error in errors)
        )

    def test_completion_event_must_snapshot_all_evidence_bindings(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_and_start_focus(root, checkpoint)
            self.add_completion_receipt_binding(root, checkpoint)
            self.complete_focus_work_item(root, checkpoint)
            event = checkpoint["goal_execution"]["transition_history"][-1]
            event.pop("completion_evidence_bindings")
            self.rehash_event(checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            with patch.object(
                graph.legacy,
                "validate_work_item_completion_receipt",
                return_value=[],
            ):
                errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "completion evidence binding snapshot differs" in error
                for error in errors
            )
        )

    def test_completion_event_evidence_must_match_runtime_map(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_and_start_focus(root, checkpoint)
            self.add_completion_receipt_binding(root, checkpoint)
            self.complete_focus_work_item(root, checkpoint)
            event = checkpoint["goal_execution"]["transition_history"][-1]
            event["evidence_refs"] = []
            self.rehash_event(checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            with patch.object(
                graph.legacy,
                "validate_work_item_completion_receipt",
                return_value=[],
            ):
                errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "replayed active completion evidence differs" in error
                for error in errors
            )
        )

    def test_preactivation_completion_evidence_cannot_be_replaced(
        self,
    ) -> None:
        with self.fixture_root() as root:
            self.refresh_integrity(root)
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            checkpoint["goal_execution"]["completion_evidence_by_goal"][
                "WS-GOAL-EPIC-01"
            ] = ["POLICY_BASELINE"]
            self.activate_package(root, checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "preactivation completion evidence differs" in error
                or "activation imported completion set differs" in error
                for error in errors
            )
        )

    def test_completion_receipt_must_precede_completion_event(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_and_start_focus(root, checkpoint)
            receipt_path = self.add_completion_receipt_binding(root, checkpoint)
            receipt = graph.load_json(receipt_path)
            receipt["generated_at"] = "2026-07-24T19:49:00+09:00"
            self.write_json(receipt_path, receipt)
            self.refresh_latest_binding_hash(checkpoint, receipt_path)
            self.complete_focus_work_item(root, checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            with patch.object(
                graph.legacy,
                "validate_work_item_completion_receipt",
                return_value=[],
            ):
                errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any("not finalized within" in error for error in errors)
        )

    def test_open_gate_id_count_mismatch_is_rejected(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            checkpoint["verification_boundary"]["remaining_gate_ids"].pop()
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any("remaining Gate count differs" in error for error in errors)
        )

    def test_materialized_goal_content_is_immutable(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_package(root, checkpoint)
            path = self.materialize_artifact_work(root, checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)
            path.write_text(
                path.read_text(encoding="utf-8") + "\n변조\n",
                encoding="utf-8",
            )

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(any("dynamic inventory differs: sha256" in error for error in errors))
        self.assertTrue(any("materialization event differs" in error for error in errors))

    def test_illegal_event_from_status_is_rejected(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_and_start_focus(root, checkpoint)
            start_event = checkpoint["goal_execution"]["transition_history"][-1]
            start_event["from_status"] = "PLANNED"
            self.rehash_event(checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(any("from_status differs from replay" in error for error in errors))

    def test_snapshot_cutoff_can_advance_without_rewriting_initial_event(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            initial_hash = checkpoint["goal_execution"]["transition_history"][0][
                "event_sha256"
            ]
            self._activate_package_only(root, checkpoint)
            state = checkpoint["goal_execution"]
            state["validation_cutoff_at"] = (
                "2026-07-24T23:59:58+09:00"
            )
            self.rehash_event(checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

            self.assertEqual(
                checkpoint["goal_execution"]["transition_history"][0]["event_sha256"],
                initial_hash,
            )
        self.assertEqual(errors, [])

    def test_package_cannot_complete_before_all_workstreams(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_package(root, checkpoint)
            self.complete_package_early(root, checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "project cannot complete before all Workstreams" in error
                for error in errors
            )
        )

    def test_no_event_is_allowed_after_package_completion(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_package(root, checkpoint)
            self.complete_package_early(root, checkpoint)
            state = checkpoint["goal_execution"]
            event = {
                "sequence": len(state["transition_history"]) + 1,
                "event_id": "WS-GOAL-GRAPH-AFTER-COMPLETION-TEST-001",
                "event_type": "GOAL_FOCUS_CHANGED",
                "occurred_on": "2026-07-24",
                "occurred_at": "2026-07-24T19:43:00+09:00",
                "previous_focus_goal_id": "",
                "previous_focus_content_sha256": "",
                "focus_goal_id": "",
                "focus_goal_content_sha256": "",
                "from_status": "",
                "to_status": "",
                "static_plan_manifest_sha256": state[
                    "static_plan_manifest_sha256"
                ],
                "status_changes": {},
                "runtime_after": self.runtime_snapshot(root, checkpoint),
                "blockers_after": json.loads(json.dumps(state["blockers_by_goal"])),
                "blocker_resolution_ids_after": [],
                "source_checkpoint_version": checkpoint["schema_version"],
                "evidence_refs": [],
                "previous_event_sha256": state["transition_history"][-1][
                    "event_sha256"
                ],
            }
            event["event_sha256"] = graph.event_sha256(event)
            state["transition_history"].append(event)
            state["transition_history_anchor_sha256"] = event["event_sha256"]
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any("event appears after PACKAGE_COMPLETED" in error for error in errors)
        )

    def test_no_op_status_event_is_rejected(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.activate_and_start_focus(root, checkpoint)
            state = checkpoint["goal_execution"]
            focus_id = state["focus_goal_id"]
            start_event = state["transition_history"][-1]
            start_event["status_changes"][focus_id] = "READY"
            start_event["to_status"] = "READY"
            state["status_by_goal"][focus_id] = "READY"
            start_event["runtime_after"] = self.runtime_snapshot(
                root,
                checkpoint,
            )
            self.rehash_event(checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(any("no-op status change is prohibited" in error for error in errors))
        self.assertTrue(any("event type and target status differ" in error for error in errors))

    def test_blocker_map_and_status_must_match(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            state = checkpoint["goal_execution"]
            state["blocked_goal_ids"] = [state["focus_goal_id"]]
            state["blockers_by_goal"][state["focus_goal_id"]] = []
            state["transition_history"][0]["runtime_after"]["blocked_goal_ids"] = list(
                state["blocked_goal_ids"]
            )
            self.rehash_event(checkpoint)
            self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(any("blocker state, records, and status differ" in error for error in errors))

    def test_generic_receipt_cannot_replace_formal_test_evidence(self) -> None:
        checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
        nodes = self.load_nodes(ROOT, manifest, include_dynamic=True)
        paths = {}
        for relative in checkpoint["goal_execution"]["goal_document_paths"]:
            metadata, _ = graph.parse_goal(ROOT / relative)
            paths[metadata["goal_id"]] = relative
        goal_id = "WS-GOAL-EPIC-02-FP-018-R001"
        nodes[goal_id]["work_item_type"] = "FORMAL_TEST_RUN"
        bindings = graph.canonical_binding_map(checkpoint)

        errors = graph.validate_typed_work_item_completion_semantics(
            ROOT,
            goal_id=goal_id,
            node=nodes[goal_id],
            goal_path=paths[goal_id],
            references=["EPIC01_PHASE_G_RECORD"],
            binding_snapshot=bindings,
            paths_by_id=paths,
        )

        self.assertTrue(
            any(
                "typed completion evidence roles differ for FORMAL_TEST_RUN"
                in error
                for error in errors
            )
        )

    def test_workstream_cannot_complete_with_only_one_policy_pair(self) -> None:
        checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
        nodes = self.load_nodes(ROOT, manifest, include_dynamic=True)
        state = checkpoint["goal_execution"]
        statuses = dict(state["status_by_goal"])
        statuses["WS-GOAL-EPIC-02-FP-018-R001"] = "COMPLETE_AT_TARGET"
        statuses["WS-GOAL-EPIC-02"] = "COMPLETE_AT_TARGET"
        backlog = self.load_binding_json(checkpoint, "IMPLEMENTATION_BACKLOG")
        bindings = graph.canonical_binding_map(checkpoint)
        mapping_errors, mapping = graph.load_policy_gap_mapping(ROOT, bindings)
        self.assertEqual(mapping_errors, [])

        errors = graph.validate_completion_contracts(
            nodes,
            statuses,
            state["materialized_child_goal_ids_by_parent"],
            backlog,
            mapping,
        )

        self.assertTrue(any("coverage is incomplete" in error for error in errors))
        self.assertTrue(any("Backlog has not reached target" in error for error in errors))

    def test_unmet_completion_requirement_is_rejected(self) -> None:
        checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
        nodes = self.load_nodes(ROOT, manifest, include_dynamic=True)
        statuses = dict(checkpoint["goal_execution"]["status_by_goal"])
        statuses["WS-GOAL-EPIC-04"] = "COMPLETE_AT_TARGET"
        backlog = self.load_binding_json(checkpoint, "IMPLEMENTATION_BACKLOG")
        bindings = graph.canonical_binding_map(checkpoint)
        _, mapping = graph.load_policy_gap_mapping(ROOT, bindings)

        errors = graph.validate_completion_contracts(
            nodes,
            statuses,
            checkpoint["goal_execution"]["materialized_child_goal_ids_by_parent"],
            backlog,
            mapping,
        )

        self.assertTrue(any("completion requirements are not complete" in error for error in errors))

    def test_disallowed_dynamic_target_is_rejected(self) -> None:
        checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
        nodes = self.load_nodes(ROOT, manifest, include_dynamic=True)
        paths = {}
        for relative in checkpoint["goal_execution"]["goal_document_paths"]:
            metadata, _ = graph.parse_goal(ROOT / relative)
            paths[metadata["goal_id"]] = relative
        node = nodes["WS-GOAL-EPIC-02-FP-018-R001"]
        node["target_completion_level"] = "RELEASE_GATE_CLOSED"
        bindings = graph.canonical_binding_map(checkpoint)
        _, mapping = graph.load_policy_gap_mapping(ROOT, bindings)

        errors = graph.validate_dynamic_work_items(
            ROOT,
            manifest,
            nodes,
            paths,
            mapping,
            bindings,
        )

        self.assertTrue(any("target completion level is not allowed" in error for error in errors))

    def test_dynamic_work_item_cannot_omit_canonical_stale_input_role(
        self,
    ) -> None:
        checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
        nodes = self.load_nodes(ROOT, manifest, include_dynamic=True)
        paths = {}
        for relative in checkpoint["goal_execution"]["goal_document_paths"]:
            metadata, _ = graph.parse_goal(ROOT / relative)
            paths[metadata["goal_id"]] = relative
        goal_id = graph.EXPECTED_INITIAL_FOCUS_GOAL_ID
        nodes[goal_id]["canonical_input_roles"] = [
            "IMPLEMENTATION_BACKLOG"
        ]
        bindings = graph.canonical_binding_map(checkpoint)
        _, mapping = graph.load_policy_gap_mapping(ROOT, bindings)

        errors = graph.validate_dynamic_work_items(
            ROOT,
            manifest,
            nodes,
            paths,
            mapping,
            bindings,
        )

        self.assertTrue(
            any(
                "omits mandatory stale-input controls" in error
                for error in errors
            )
        )

    def test_superseded_v1_file_deletion_is_rejected(self) -> None:
        with self.fixture_root() as root:
            path = root / (
                "docs/control/goals/walksafe-completion-v1/"
                "10-phase-a-implementation-readiness.md"
            )
            path.unlink()

            errors = graph.validate_superseded_v1(root)

        self.assertTrue(any("protected file is missing" in error for error in errors))

    def test_superseded_v2_package_is_valid(self) -> None:
        self.assertEqual(graph.validate_superseded_v2_0(ROOT), [])

    def test_superseded_v2_protected_file_change_is_rejected(self) -> None:
        with self.fixture_root() as root:
            path = root / (
                "docs/control/goals/walksafe-completion-graph-v2/"
                "workstreams/epic-05-object-detection-safety.md"
            )
            path.write_text(
                path.read_text(encoding="utf-8") + "\n변조\n",
                encoding="utf-8",
            )

            errors = graph.validate_superseded_v2_0(root)

        self.assertTrue(
            any(
                "superseded v2 protected file changed" in error
                for error in errors
            )
        )

    def test_superseded_v2_archived_event_cannot_be_reanchored(
        self,
    ) -> None:
        with self.fixture_root() as root:
            path = root / (
                graph.V21_PACKAGE_RELATIVE
                / "superseded-v2.0.0-package-prepared-event.json"
            )
            event = graph.load_json(path)
            event["event_id"] = "FORGED-V2-PACKAGE-PREPARED"
            event["event_sha256"] = graph.event_sha256(event)
            self.write_json(path, event)

            errors = graph.validate_superseded_v2_0(root)

        self.assertTrue(
            any(
                "preparation event stored hash differs" in error
                for error in errors
            )
        )
        self.assertTrue(
            any(
                "preparation event canonical hash differs" in error
                for error in errors
            )
        )

    def test_superseded_v2_unexpected_managed_path_is_rejected(
        self,
    ) -> None:
        with self.fixture_root() as root:
            path = root / (
                "docs/control/goals/walksafe-completion-graph-v2/"
                "unexpected-audit-file.bin"
            )
            path.write_bytes(b"\x00unexpected")

            errors = graph.validate_superseded_v2_0(root)

        self.assertTrue(
            any(
                "superseded v2 managed path set differs" in error
                for error in errors
            )
        )

    def test_superseded_v21_package_is_valid(self) -> None:
        self.assertEqual(graph.validate_superseded_v2_1(ROOT), [])

    def test_superseded_v21_protected_file_change_is_rejected(self) -> None:
        with self.fixture_root() as root:
            path = root / (
                graph.V21_PACKAGE_RELATIVE
                / "workstreams/epic-05-object-detection-safety.md"
            )
            path.write_text(
                path.read_text(encoding="utf-8") + "\n변조\n",
                encoding="utf-8",
            )

            errors = graph.validate_superseded_v2_1(root)

        self.assertTrue(
            any(
                "superseded v2.1 protected file changed" in error
                for error in errors
            )
        )

    def test_superseded_v21_manifest_cannot_be_reanchored(self) -> None:
        with self.fixture_root() as root:
            path = root / graph.V21_MANIFEST_RELATIVE
            manifest = graph.load_json(path)
            manifest["plan_version"] = "2.1.1-forged"
            self.write_json(path, manifest)

            errors = graph.validate_superseded_v2_1(root)

        self.assertTrue(
            any(
                "superseded v2.1 manifest content changed" in error
                for error in errors
            )
        )
        self.assertTrue(
            any(
                "superseded v2.1 manifest package binding differs" in error
                for error in errors
            )
        )

    def test_superseded_v21_archived_event_cannot_be_reanchored(
        self,
    ) -> None:
        with self.fixture_root() as root:
            path = root / (
                graph.PACKAGE_RELATIVE
                / "superseded-v2.1.0-package-prepared-event.json"
            )
            event = graph.load_json(path)
            event["event_id"] = "FORGED-V2-1-PACKAGE-PREPARED"
            event["event_sha256"] = graph.event_sha256(event)
            self.write_json(path, event)

            errors = graph.validate_superseded_v2_1(root)

        self.assertTrue(
            any(
                "v2.1 preparation event stored hash differs" in error
                for error in errors
            )
        )
        self.assertTrue(
            any(
                "v2.1 preparation event canonical hash differs" in error
                for error in errors
            )
        )

    def test_superseded_v21_unexpected_managed_path_is_rejected(
        self,
    ) -> None:
        with self.fixture_root() as root:
            path = root / (
                graph.V21_PACKAGE_RELATIVE / "unexpected-audit-file.bin"
            )
            path.write_bytes(b"\x00unexpected")

            errors = graph.validate_superseded_v2_1(root)

        self.assertTrue(
            any(
                "superseded v2.1 managed path set differs" in error
                for error in errors
            )
        )

    def test_v21_supersession_record_tampering_is_rejected(self) -> None:
        with self.fixture_root() as root:
            path = root / (
                graph.PACKAGE_RELATIVE
                / "preactivation-supersession-record-v2.1.0.json"
            )
            record = graph.load_json(path)
            record["successor_plan_version"] = "2.2.1-forged"
            self.write_json(path, record)

            errors = graph.validate_superseded_v2_1(root)

        self.assertTrue(
            any(
                "v2.1 supersession record successor binding differs"
                in error
                for error in errors
            )
        )

    def test_prestart_control_repair_commits_valid_noop(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(
                root / graph.CHECKPOINT_RELATIVE
            )
            self.commit_prestart_control_repair(root, checkpoint)
            self.write_json(
                root / graph.CHECKPOINT_RELATIVE,
                checkpoint,
            )

            errors = graph.validate(root, check_continuation=False)

        self.assertEqual(errors, [])

    def test_prestart_control_repair_uses_split_semantic_regression_contract(
        self,
    ) -> None:
        full_command = dict(
            graph.IMPLEMENTATION_START_GATE_CHECKS
        )["GOAL_CONTROL_PYTEST"]
        semantic_command = (
            graph.PRESTART_CONTROL_REPAIR_SEMANTIC_REGRESSION_COMMAND
        )
        expected_deselections = {
            (
                "tests/test_walksafe_goal_graph.py::"
                "WalkSafeGoalGraphTest::test_current_goal_graph_is_valid"
            ),
            (
                "tests/test_walksafe_project_continuation.py::"
                "WalkSafeProjectContinuationTest::"
                "test_current_checkpoint_is_valid"
            ),
        }
        semantic_tokens = semantic_command.split()
        deselections = {
            token.removeprefix("--deselect=")
            for token in semantic_tokens
            if token.startswith("--deselect=")
        }

        self.assertNotEqual(semantic_command, full_command)
        self.assertEqual(
            graph.PRESTART_CONTROL_REPAIR_CHECKS,
            (
                (
                    "FAIL_BEFORE",
                    full_command,
                    1,
                    "01-FAIL_BEFORE.log",
                ),
                (
                    "PASS_AFTER",
                    semantic_command,
                    0,
                    "02-PASS_AFTER.log",
                ),
            ),
        )
        self.assertIn(
            "tests/test_walksafe_goal_graph.py",
            semantic_tokens,
        )
        self.assertIn(
            "tests/test_walksafe_project_continuation.py",
            semantic_tokens,
        )
        self.assertEqual(deselections, expected_deselections)
        self.assertEqual(
            {
                deselection
                for deselection in deselections
                if deselection.startswith(
                    "tests/test_walksafe_goal_graph.py::"
                )
            },
            {
                (
                    "tests/test_walksafe_goal_graph.py::"
                    "WalkSafeGoalGraphTest::"
                    "test_current_goal_graph_is_valid"
                )
            },
        )

        with self.fixture_root() as root:
            checkpoint = graph.load_json(
                root / graph.CHECKPOINT_RELATIVE
            )
            repair = self.commit_prestart_control_repair(
                root,
                checkpoint,
            )
            self.assertEqual(
                [
                    (
                        run["check_id"],
                        run["command"],
                        run["exit_code"],
                    )
                    for run in repair["receipt"]["check_runs"]
                ],
                [
                    ("FAIL_BEFORE", full_command, 1),
                    ("PASS_AFTER", semantic_command, 0),
                ],
            )
            self.write_json(
                root / graph.CHECKPOINT_RELATIVE,
                checkpoint,
            )

            errors = graph.validate(root, check_continuation=False)

        self.assertEqual(errors, [])

    def test_prestart_control_repair_archive_tampering_is_rejected(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(
                root / graph.CHECKPOINT_RELATIVE
            )
            repair = self.commit_prestart_control_repair(
                root,
                checkpoint,
            )
            archive_path = (
                root
                / repair["changed_paths"][0][
                    "before_archive_path"
                ]
            )
            archive_path.write_bytes(
                archive_path.read_bytes() + b"\ntampered\n"
            )
            self.write_json(
                root / graph.CHECKPOINT_RELATIVE,
                checkpoint,
            )

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "repair changed path evidence differs: "
                "scripts/check_walksafe_goal_graph.py"
                in error
                for error in errors
            )
        )

    def test_prestart_control_repair_rejects_product_path(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(
                root / graph.CHECKPOINT_RELATIVE
            )
            repair = self.commit_prestart_control_repair(
                root,
                checkpoint,
            )
            product_path = (
                "apps/android/app/src/main/java/kr/co/hanium/"
                "dreamup/walksafe/MainActivity.kt"
            )
            repair["receipt"]["changed_paths"][0][
                "path"
            ] = product_path
            repair["review"]["changed_paths"][0][
                "path"
            ] = product_path
            self.rebind_prestart_control_repair(
                root,
                checkpoint,
                repair,
            )

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "repair changed path set/order differs" in error
                for error in errors
            )
        )

    def test_prestart_control_repair_cannot_change_goal_status(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(
                root / graph.CHECKPOINT_RELATIVE
            )
            repair = self.commit_prestart_control_repair(
                root,
                checkpoint,
            )
            focus_id = checkpoint["goal_execution"]["focus_goal_id"]
            repair["event"]["status_changes"] = {
                focus_id: "IN_PROGRESS"
            }
            self.save_rehashed_checkpoint(root, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "prestart control repair must be a "
                "Goal/runtime/blocker/completion semantic no-op"
                in error
                for error in errors
            )
        )

    def test_prestart_control_repair_cannot_repeat(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(
                root / graph.CHECKPOINT_RELATIVE
            )
            repair = self.commit_prestart_control_repair(
                root,
                checkpoint,
            )
            state = checkpoint["goal_execution"]
            duplicate = json.loads(
                json.dumps(repair["event"])
            )
            duplicate["sequence"] = len(
                state["transition_history"]
            ) + 1
            duplicate["event_id"] = (
                "WS-GOAL-GRAPH-PRESTART-CONTROL-REPAIR-TEST-002"
            )
            duplicate["occurred_at"] = (
                "2026-07-24T19:41:10+09:00"
            )
            state["transition_history"].append(duplicate)
            self.save_rehashed_checkpoint(root, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertIn(
            "Goal graph prestart control repair event is duplicated",
            errors,
        )

    def test_prestart_control_repair_is_rejected_after_execution_start(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(
                root / graph.CHECKPOINT_RELATIVE
            )
            self.commit_prestart_control_repair(
                root,
                checkpoint,
                start_before_repair=True,
            )
            self.write_json(
                root / graph.CHECKPOINT_RELATIVE,
                checkpoint,
            )

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "prestart control repair must occur exactly once "
                "immediately after activation and before execution"
                in error
                for error in errors
            )
        )
        self.assertIn(
            "Goal graph first post-activation event must be the "
            "one-time prestart control repair",
            errors,
        )

    def test_package_activation_rehash_cannot_bypass_checker_anchor(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(
                root / graph.CHECKPOINT_RELATIVE
            )
            self._activate_package_only(root, checkpoint)
            activation = checkpoint["goal_execution"][
                "transition_history"
            ][1]
            activation["forged_note"] = "locally rehashed"
            self.save_rehashed_checkpoint(root, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "PACKAGE_ACTIVATED lacks the exact checker event "
                "trust anchor"
                in error
                for error in errors
            )
        )

    def test_non_tail_prestart_repair_after_hash_tampering_is_rejected(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(
                root / graph.CHECKPOINT_RELATIVE
            )
            repair = self.commit_prestart_control_repair(
                root,
                checkpoint,
            )
            self.start_goal(
                root,
                checkpoint,
                checkpoint["goal_execution"]["focus_goal_id"],
            )
            forged_sha256 = "0" * 64
            repair["receipt"]["changed_paths"][0][
                "after_sha256"
            ] = forged_sha256
            repair["review"]["changed_paths"][0][
                "after_sha256"
            ] = forged_sha256
            self.rebind_prestart_control_repair(
                root,
                checkpoint,
                repair,
            )

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "repair changed path evidence differs: "
                "scripts/check_walksafe_goal_graph.py"
                in error
                for error in errors
            )
        )

    def test_non_tail_prestart_repair_after_aggregate_must_match_witness(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(
                root / graph.CHECKPOINT_RELATIVE
            )
            repair = self.commit_prestart_control_repair(
                root,
                checkpoint,
            )
            witness_map = repair["witness"][
                "managed_content_sha256_by_path_after"
            ]
            witnessed_after = graph.controlled_content_root_from_witness(
                witness_map
            )
            forged_after = (
                "0" * 64
                if witnessed_after != "0" * 64
                else "1" * 64
            )
            self.set_prestart_repair_after_content_sha256(
                checkpoint,
                repair,
                forged_after,
            )
            self.start_goal(
                root,
                checkpoint,
                checkpoint["goal_execution"]["focus_goal_id"],
            )
            self.rebind_prestart_control_repair(
                root,
                checkpoint,
                repair,
            )

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "controlled content witness differs" in error
                for error in errors
            )
        )
        self.assertFalse(
            any(
                "first execution gate checkpoint snapshot differs"
                in error
                for error in errors
            ),
            errors,
        )

    def test_non_tail_prestart_repair_witness_cannot_reanchor_activation_before_root(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(
                root / graph.CHECKPOINT_RELATIVE
            )
            repair = self.commit_prestart_control_repair(
                root,
                checkpoint,
            )
            witness_map = repair["witness"][
                "managed_content_sha256_by_path_after"
            ]
            tampered_path = next(
                relative
                for relative in witness_map
                if relative
                not in graph.PRESTART_CONTROL_REPAIR_CHANGED_PATHS
            )
            original_sha256 = witness_map[tampered_path]
            witness_map[tampered_path] = (
                "0" * 64
                if original_sha256 != "0" * 64
                else "1" * 64
            )
            forged_after = graph.controlled_content_root_from_witness(
                witness_map
            )
            self.set_prestart_repair_after_content_sha256(
                checkpoint,
                repair,
                forged_after,
            )
            witnessed_before = dict(witness_map)
            for row in repair["changed_paths"]:
                witnessed_before[row["path"]] = row["before_sha256"]
            self.assertNotEqual(
                graph.controlled_content_root_from_witness(
                    witnessed_before
                ),
                repair["event"]["repository_snapshot_before"][
                    "content_set_sha256"
                ],
            )
            self.start_goal(
                root,
                checkpoint,
                checkpoint["goal_execution"]["focus_goal_id"],
            )
            self.rebind_prestart_control_repair(
                root,
                checkpoint,
                repair,
            )

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "controlled content witness differs" in error
                for error in errors
            )
        )
        self.assertFalse(
            any(
                "first execution gate checkpoint snapshot differs"
                in error
                for error in errors
            ),
            errors,
        )

    def test_first_start_gate_cannot_predate_prestart_repair(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(
                root / graph.CHECKPOINT_RELATIVE
            )
            self.commit_prestart_control_repair(root, checkpoint)
            self.start_goal(
                root,
                checkpoint,
                checkpoint["goal_execution"]["focus_goal_id"],
            )
            start_event = checkpoint["goal_execution"][
                "transition_history"
            ][-1]
            binding = start_event[
                "implementation_start_gate_binding"
            ]
            receipt_path = root / binding["path"]
            receipt = graph.load_json(receipt_path)
            receipt["execution_window"]["started_at"] = (
                "2026-07-24T19:41:08+09:00"
            )
            self.write_json(receipt_path, receipt)
            binding["file_sha256"] = graph.sha256_file(receipt_path)
            self.save_rehashed_checkpoint(root, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "first execution gate predates prestart repair"
                in error
                for error in errors
            )
        )

    def test_prestart_control_repair_rejects_unexpected_evidence_entries(
        self,
    ) -> None:
        for entry_kind in ("empty_directory", "fifo"):
            with self.subTest(entry_kind=entry_kind):
                with self.fixture_root() as root:
                    checkpoint = graph.load_json(
                        root / graph.CHECKPOINT_RELATIVE
                    )
                    repair = self.commit_prestart_control_repair(
                        root,
                        checkpoint,
                    )
                    unexpected = (
                        repair["receipt_path"].parent
                        / f"unexpected-{entry_kind}"
                    )
                    if entry_kind == "empty_directory":
                        unexpected.mkdir()
                    else:
                        os.mkfifo(unexpected)
                    self.write_json(
                        root / graph.CHECKPOINT_RELATIVE,
                        checkpoint,
                    )

                    errors = graph.validate(
                        root,
                        check_continuation=False,
                    )

                self.assertTrue(
                    any(
                        "repair event evidence inventory differs"
                        in error
                        for error in errors
                    ),
                    errors,
                )

    def test_prestart_control_repair_rejects_unreadable_receipt(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(
                root / graph.CHECKPOINT_RELATIVE
            )
            repair = self.commit_prestart_control_repair(
                root,
                checkpoint,
            )
            self.write_json(
                root / graph.CHECKPOINT_RELATIVE,
                checkpoint,
            )
            receipt_path = repair["receipt_path"]
            path_type = type(receipt_path)
            original_read_bytes = path_type.read_bytes

            def unreadable(candidate: Path) -> bytes:
                if candidate == receipt_path:
                    raise PermissionError("synthetic unreadable receipt")
                return original_read_bytes(candidate)

            with patch.object(
                path_type,
                "read_bytes",
                new=unreadable,
            ):
                errors = graph.validate(
                    root,
                    check_continuation=False,
                )

        self.assertTrue(
            any(
                "prestart control repair receipt cannot be loaded"
                in error
                for error in errors
            ),
            errors,
        )

    def test_prestart_control_repair_rejects_deep_receipt_json(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(
                root / graph.CHECKPOINT_RELATIVE
            )
            repair = self.commit_prestart_control_repair(
                root,
                checkpoint,
            )
            receipt_path = repair["receipt_path"]
            deep_json = (
                b'{"document_id":'
                b'"TEST-PRESTART-CONTROL-REPAIR-RECEIPT",'
                b'"deep":'
                + (b"[" * 16384)
                + b"0"
                + (b"]" * 16384)
                + b"}"
            )
            receipt_path.write_bytes(deep_json)
            repair["event"][
                "prestart_control_repair_receipt_binding"
            ]["file_sha256"] = graph.sha256_file(receipt_path)
            self.save_rehashed_checkpoint(root, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "prestart control repair receipt cannot be loaded"
                in error
                for error in errors
            ),
            errors,
        )

    def test_prestart_control_repair_rejects_unsafe_event_id(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(
                root / graph.CHECKPOINT_RELATIVE
            )
            repair = self.commit_prestart_control_repair(
                root,
                checkpoint,
            )
            repair["event"]["event_id"] = ".."
            self.save_rehashed_checkpoint(root, checkpoint)

            errors = graph.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "prestart control repair event ID is unsafe"
                in error
                for error in errors
            ),
            errors,
        )

    def copy_controlled_fixture_files(
        self,
        root: Path,
        checkpoint: dict,
    ) -> None:
        prepared_package_paths = set(
            checkpoint["goal_execution"]["managed_goal_paths"]
        )
        package_prefix = graph.PACKAGE_RELATIVE.as_posix() + "/"
        managed_paths = checkpoint["working_tree_snapshot"][
            "managed_changed_paths"
        ]
        managed_paths[:] = [
            relative
            for relative in managed_paths
            if not relative.startswith(package_prefix)
            or relative in prepared_package_paths
        ]
        for relative in managed_paths:
            source = ROOT / relative
            self.assertTrue(source.is_file(), relative)
            target = root / relative
            if target.is_file():
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)

    def checkpoint_repository_snapshot(
        self,
        checkpoint: dict,
    ) -> dict:
        working = checkpoint["working_tree_snapshot"]
        return {
            "base_head": working["base_head"],
            "branch": checkpoint["repository"]["branch"],
            "managed_path_count": working[
                "managed_changed_path_count"
            ],
            "path_set_sha256": working["path_set_sha256"],
            "content_set_sha256": working["content_set_sha256"],
        }

    def commit_prestart_control_repair(
        self,
        root: Path,
        checkpoint: dict,
        *,
        start_before_repair: bool = False,
    ) -> dict:
        clone = lambda value: json.loads(json.dumps(value))
        state = checkpoint["goal_execution"]
        self.copy_controlled_fixture_files(root, checkpoint)
        repair_paths = [
            "scripts/check_walksafe_goal_graph.py",
            "tests/test_walksafe_goal_graph.py",
        ]
        after_bytes = {
            relative: (root / relative).read_bytes()
            for relative in repair_paths
        }
        before_bytes = {
            relative: (
                b"# synthetic prestart control repair predecessor\n"
                + content
            )
            for relative, content in after_bytes.items()
        }
        for relative, content in before_bytes.items():
            (root / relative).write_bytes(content)

        managed_paths = checkpoint["working_tree_snapshot"][
            "managed_changed_paths"
        ]
        before_path_hash, before_content_hash = (
            graph.continuation.working_snapshot_hashes(
                root,
                managed_paths,
            )
        )
        working = checkpoint["working_tree_snapshot"]
        working["managed_changed_path_count"] = len(managed_paths)
        working["path_set_sha256"] = before_path_hash
        working["content_set_sha256"] = before_content_hash
        handoff_snapshot = checkpoint["session_handoff"][
            "source_commit_or_snapshot"
        ]
        handoff_snapshot["file_count"] = len(managed_paths)
        handoff_snapshot["path_set_sha256"] = before_path_hash
        handoff_snapshot["content_set_sha256"] = before_content_hash

        self._activate_package_only(root, checkpoint)
        activation_event = state["transition_history"][-1]
        if start_before_repair:
            self.start_goal(
                root,
                checkpoint,
                state["focus_goal_id"],
            )

        for relative, content in after_bytes.items():
            (root / relative).write_bytes(content)
        after_path_hash, after_content_hash = (
            graph.continuation.working_snapshot_hashes(
                root,
                managed_paths,
            )
        )
        working["path_set_sha256"] = after_path_hash
        working["content_set_sha256"] = after_content_hash
        handoff_snapshot["path_set_sha256"] = after_path_hash
        handoff_snapshot["content_set_sha256"] = after_content_hash

        event_id = "WS-GOAL-GRAPH-PRESTART-CONTROL-REPAIR-TEST-001"
        event_relative = (
            f"docs/control/execution/goal-gates/{event_id}"
        )
        evidence_dir = root / event_relative
        changed_paths = []
        for relative in repair_paths:
            archive_relative = (
                f"{event_relative}/before/{relative}"
            )
            archive_path = root / archive_relative
            archive_path.parent.mkdir(parents=True, exist_ok=True)
            archive_path.write_bytes(before_bytes[relative])
            changed_paths.append(
                {
                    "path": relative,
                    "before_archive_path": archive_relative,
                    "before_sha256": graph.sha256_file(archive_path),
                    "after_sha256": graph.sha256_file(
                        root / relative
                    ),
                }
            )

        fail_before_command = dict(
            graph.IMPLEMENTATION_START_GATE_CHECKS
        )["GOAL_CONTROL_PYTEST"]
        pass_after_command = (
            graph.PRESTART_CONTROL_REPAIR_SEMANTIC_REGRESSION_COMMAND
        )
        check_runs = []
        for index, (
            check_id,
            executed_at,
            exit_code,
            command,
            content,
        ) in enumerate(
            (
                (
                    "FAIL_BEFORE",
                    "2026-07-24T19:41:03+09:00",
                    1,
                    fail_before_command,
                    "state-dependent fixture inherited ACTIVE: FAIL\n",
                ),
                (
                    "PASS_AFTER",
                    "2026-07-24T19:41:04+09:00",
                    0,
                    pass_after_command,
                    "prepared fixture projection: PASS\n",
                ),
            ),
            start=1,
        ):
            output_path = evidence_dir / f"{index:02d}-{check_id}.log"
            output_path.parent.mkdir(parents=True, exist_ok=True)
            output_path.write_text(content, encoding="utf-8")
            check_runs.append(
                {
                    "check_id": check_id,
                    "command": command,
                    "executed_at": executed_at,
                    "exit_code": exit_code,
                    "output_path": str(output_path.relative_to(root)),
                    "output_sha256": graph.sha256_file(output_path),
                }
            )

        before_snapshot = clone(
            activation_event["repository_snapshot_before"]
        )
        after_snapshot = self.checkpoint_repository_snapshot(
            checkpoint
        )
        witness_path = (
            evidence_dir / "controlled-content-witness.json"
        )
        witness = {
            "schema_version": "1.0",
            "document_id": (
                "TEST-PRESTART-CONTROL-REPAIR-CONTENT-WITNESS"
            ),
            "evidence_type": (
                "PRESTART_CONTROL_REPAIR_CONTROLLED_CONTENT_WITNESS"
            ),
            "status": "CAPTURED",
            "target_transition_event_id": event_id,
            "repository_snapshot_before": before_snapshot,
            "repository_snapshot_after": after_snapshot,
            "managed_content_sha256_by_path_after": {
                relative: graph.sha256_file(root / relative)
                for relative in sorted(managed_paths)
            },
            "generated_at": "2026-07-24T19:41:05+09:00",
        }
        self.write_json(witness_path, witness)
        witness_binding = {
            "document_id": witness["document_id"],
            "path": str(witness_path.relative_to(root)),
            "file_sha256": graph.sha256_file(witness_path),
        }
        review_path = evidence_dir / "independent-review.json"
        review = {
            "schema_version": "1.0",
            "document_id": "TEST-PRESTART-CONTROL-REPAIR-REVIEW",
            "evidence_type": (
                "PRESTART_CONTROL_REPAIR_INDEPENDENT_REVIEW"
            ),
            "status": "ACCEPTED",
            "target_transition_event_id": event_id,
            "repository_snapshot_before": before_snapshot,
            "repository_snapshot_after": after_snapshot,
            "changed_paths": clone(changed_paths),
            "controlled_content_witness_binding": clone(
                witness_binding
            ),
            "reviewer": {
                "id": "TEST-INDEPENDENT-CONTROL-REVIEWER",
                "role": "INDEPENDENT_CONTROL_REVIEWER",
                "authority": "INTERNAL_REPOSITORY_CONTROL",
            },
            "reviewed_at": "2026-07-24T19:41:06+09:00",
            "generated_at": "2026-07-24T19:41:07+09:00",
        }
        self.write_json(review_path, review)
        review_binding = {
            "document_id": review["document_id"],
            "path": str(review_path.relative_to(root)),
            "file_sha256": graph.sha256_file(review_path),
        }

        receipt_path = (
            evidence_dir / "prestart-control-repair-receipt.json"
        )
        receipt = {
            "schema_version": "1.0",
            "document_id": "TEST-PRESTART-CONTROL-REPAIR-RECEIPT",
            "evidence_type": "PRESTART_CONTROL_REPAIR_RECEIPT",
            "status": "PASS",
            "package_id": graph.EXPECTED_PACKAGE_ID,
            "target_transition_event_id": event_id,
            "source_activation_event_sha256": activation_event[
                "event_sha256"
            ],
            "reason_code": (
                "STATE_DEPENDENT_TEST_FIXTURE_REPAIR"
            ),
            "repository_snapshot_before": before_snapshot,
            "repository_snapshot_after": after_snapshot,
            "changed_paths": clone(changed_paths),
            "execution_window": {
                "started_at": "2026-07-24T19:41:02+09:00",
                "ended_at": "2026-07-24T19:41:05+09:00",
            },
            "check_runs": check_runs,
            "executor": {
                "id": "TEST-CONTROL-PLANE-REPAIR-EXECUTOR",
                "role": "CONTROL_PLANE_REPAIR_EXECUTOR",
                "authority": "INTERNAL_REPOSITORY_CONTROL",
            },
            "controlled_content_witness_binding": clone(
                witness_binding
            ),
            "independent_review_binding": review_binding,
            "generated_at": "2026-07-24T19:41:08+09:00",
        }
        self.write_json(receipt_path, receipt)
        receipt_binding = {
            "document_id": receipt["document_id"],
            "path": str(receipt_path.relative_to(root)),
            "file_sha256": graph.sha256_file(receipt_path),
        }

        focus_path = root / state["focus_goal_path"]
        focus_status = state["status_by_goal"][
            state["focus_goal_id"]
        ]
        event = {
            "sequence": len(state["transition_history"]) + 1,
            "event_id": event_id,
            "event_type": "PRESTART_CONTROL_REPAIR_COMMITTED",
            "occurred_on": "2026-07-24",
            "occurred_at": "2026-07-24T19:41:09+09:00",
            "previous_focus_goal_id": state["focus_goal_id"],
            "previous_focus_content_sha256": graph.sha256_file(
                focus_path
            ),
            "focus_goal_id": state["focus_goal_id"],
            "focus_goal_content_sha256": graph.sha256_file(
                focus_path
            ),
            "from_status": focus_status,
            "to_status": focus_status,
            "static_plan_manifest_sha256": state[
                "static_plan_manifest_sha256"
            ],
            "status_changes": {},
            "runtime_after": self.runtime_snapshot(root, checkpoint),
            "blockers_after": clone(state["blockers_by_goal"]),
            "blocker_resolution_ids_after": [
                resolution["blocker_id"]
                for resolution in state["blocker_resolution_history"]
            ],
            "source_checkpoint_version": checkpoint["schema_version"],
            "evidence_refs": [],
            "prestart_control_repair_receipt_binding": (
                receipt_binding
            ),
            "repository_snapshot_before": before_snapshot,
            "repository_snapshot_after": after_snapshot,
            "previous_event_sha256": state["transition_history"][-1][
                "event_sha256"
            ],
        }
        event["event_sha256"] = graph.event_sha256(event)
        state["transition_history"].append(event)
        state["transition_history_anchor_sha256"] = event[
            "event_sha256"
        ]
        return {
            "event": event,
            "receipt": receipt,
            "receipt_path": receipt_path,
            "review": review,
            "review_path": review_path,
            "witness": witness,
            "witness_path": witness_path,
            "changed_paths": changed_paths,
        }

    def set_prestart_repair_after_content_sha256(
        self,
        checkpoint: dict,
        repair: dict,
        content_sha256: str,
    ) -> None:
        for payload in (
            repair["event"],
            repair["receipt"],
            repair["review"],
            repair["witness"],
        ):
            payload["repository_snapshot_after"][
                "content_set_sha256"
            ] = content_sha256
        checkpoint["working_tree_snapshot"][
            "content_set_sha256"
        ] = content_sha256
        checkpoint["session_handoff"]["source_commit_or_snapshot"][
            "content_set_sha256"
        ] = content_sha256

    def rebind_prestart_control_repair(
        self,
        root: Path,
        checkpoint: dict,
        repair: dict,
    ) -> None:
        self.write_json(
            repair["witness_path"],
            repair["witness"],
        )
        witness_sha256 = graph.sha256_file(
            repair["witness_path"]
        )
        repair["receipt"][
            "controlled_content_witness_binding"
        ]["file_sha256"] = witness_sha256
        repair["review"][
            "controlled_content_witness_binding"
        ]["file_sha256"] = witness_sha256
        self.write_json(
            repair["review_path"],
            repair["review"],
        )
        review_binding = repair["receipt"][
            "independent_review_binding"
        ]
        review_binding["file_sha256"] = graph.sha256_file(
            repair["review_path"]
        )
        self.write_json(
            repair["receipt_path"],
            repair["receipt"],
        )
        receipt_binding = repair["event"][
            "prestart_control_repair_receipt_binding"
        ]
        receipt_binding["file_sha256"] = graph.sha256_file(
            repair["receipt_path"]
        )
        self.save_rehashed_checkpoint(root, checkpoint)

    def _activate_package_only(
        self,
        root: Path,
        checkpoint: dict,
    ) -> None:
        state = checkpoint["goal_execution"]
        event_id = "WS-GOAL-GRAPH-PACKAGE-ACTIVATED-TEST-001"
        imported_goal_ids = sorted(
            goal_id
            for goal_id, status in state["status_by_goal"].items()
            if status == "COMPLETE_AT_TARGET"
        )
        binding_snapshot = graph.canonical_binding_snapshot(
            graph.canonical_binding_map(checkpoint)
        )
        imported_refs = {
            goal_id: list(state["completion_evidence_by_goal"][goal_id])
            for goal_id in imported_goal_ids
        }
        state["activation_status"] = "ACTIVE"
        state["package_status"] = "ACTIVE"
        state["validation_cutoff_at"] = "2026-07-24T23:59:59+09:00"
        focus_path = root / state["focus_goal_path"]
        evidence_dir = (
            root / "docs/control/execution/goal-gates" / event_id
        )
        evidence_dir.mkdir(parents=True, exist_ok=True)
        quick_runs = []
        for index, (check_id, command) in enumerate(
            graph.QUICK_ACTIVATION_CHECKS,
            start=1,
        ):
            output_path = evidence_dir / f"{index:02d}-{check_id}.log"
            output_path.write_text(
                f"{check_id}: PASS\n",
                encoding="utf-8",
            )
            quick_runs.append(
                {
                    "check_id": check_id,
                    "command": command,
                    "executed_at": (
                        f"2026-07-24T19:39:{30 + index:02d}+09:00"
                    ),
                    "exit_code": 0,
                    "output_path": str(output_path.relative_to(root)),
                    "output_sha256": graph.sha256_file(output_path),
                }
            )
        working_snapshot = checkpoint["working_tree_snapshot"]
        repository_snapshot = {
            "base_head": working_snapshot["base_head"],
            "branch": checkpoint["repository"]["branch"],
            "managed_path_count": working_snapshot[
                "managed_changed_path_count"
            ],
            "path_set_sha256": working_snapshot["path_set_sha256"],
            "content_set_sha256": working_snapshot[
                "content_set_sha256"
            ],
        }
        authorization_path = evidence_dir / "authorization.json"
        authorization = {
            "schema_version": "1.0",
            "document_id": "TEST-PACKAGE-ACTIVATION-AUTHORIZATION",
            "evidence_type": "PACKAGE_ACTIVATION_AUTHORIZATION",
            "status": "AUTHORIZED",
            "package_id": graph.EXPECTED_PACKAGE_ID,
            "activation_request": {
                "request_id": "TEST-USER-ACTIVATION-REQUEST",
                "source_kind": "USER_EXPLICIT_REQUEST",
                "request_sha256": "9" * 64,
                "requested_at": "2026-07-24T19:39:00+09:00",
            },
            "authorized_at": "2026-07-24T19:39:10+09:00",
            "generated_at": "2026-07-24T19:39:20+09:00",
        }
        self.write_json(authorization_path, authorization)
        graph.EXPECTED_PACKAGE_ACTIVATION_AUTHORIZATION_SHA256 = (
            graph.sha256_file(authorization_path)
        )
        authorization_binding = {
            "document_id": authorization["document_id"],
            "path": str(authorization_path.relative_to(root)),
            "file_sha256": graph.sha256_file(authorization_path),
        }
        quick_gate_path = evidence_dir / "quick-gate-receipt.json"
        quick_gate = {
            "schema_version": "1.0",
            "document_id": "TEST-PACKAGE-ACTIVATION-QUICK-GATE",
            "evidence_type": "PACKAGE_ACTIVATION_QUICK_GATE",
            "status": "PASS",
            "package_id": graph.EXPECTED_PACKAGE_ID,
            "target_transition_event_id": event_id,
            "static_plan_manifest_sha256": state[
                "static_plan_manifest_sha256"
            ],
            "authorization_receipt_binding": authorization_binding,
            "check_command_contract_version": (
                graph.CHECK_COMMAND_CONTRACT_VERSION
            ),
            "check_command_contract_sha256": (
                graph.QUICK_ACTIVATION_CHECK_CONTRACT_SHA256
            ),
            "execution_window": {
                "started_at": "2026-07-24T19:39:30+09:00",
                "ended_at": "2026-07-24T19:40:30+09:00",
            },
            "check_runs": quick_runs,
            "repository_snapshot": repository_snapshot,
            "generated_at": "2026-07-24T19:40:40+09:00",
        }
        self.write_json(quick_gate_path, quick_gate)
        quick_gate_binding = {
            "document_id": quick_gate["document_id"],
            "path": str(quick_gate_path.relative_to(root)),
            "file_sha256": graph.sha256_file(quick_gate_path),
        }
        event = {
            "sequence": len(state["transition_history"]) + 1,
            "event_id": event_id,
            "event_type": "PACKAGE_ACTIVATED",
            "imported_completion_evidence_refs_by_goal": imported_refs,
            "imported_completion_evidence_bindings_by_goal": {
                goal_id: {
                    reference: binding_snapshot[reference]
                    for reference in references
                }
                for goal_id, references in imported_refs.items()
            },
            "imported_completion_event_sha256_by_goal": {
                goal_id: next(
                    prior["event_sha256"]
                    for prior in reversed(state["transition_history"])
                    if prior.get("status_changes", {}).get(goal_id)
                    == "COMPLETE_AT_TARGET"
                )
                for goal_id in imported_goal_ids
            },
            "occurred_on": "2026-07-24",
            "occurred_at": "2026-07-24T19:41:00+09:00",
            "previous_focus_goal_id": state["focus_goal_id"],
            "previous_focus_content_sha256": graph.sha256_file(focus_path),
            "focus_goal_id": state["focus_goal_id"],
            "focus_goal_content_sha256": graph.sha256_file(focus_path),
            "from_status": state["status_by_goal"][state["focus_goal_id"]],
            "to_status": state["status_by_goal"][state["focus_goal_id"]],
            "static_plan_manifest_sha256": state["static_plan_manifest_sha256"],
            "status_changes": {},
            "runtime_after": self.runtime_snapshot(root, checkpoint),
            "blockers_after": json.loads(json.dumps(state["blockers_by_goal"])),
            "blocker_resolution_ids_after": [],
            "source_checkpoint_version": checkpoint["schema_version"],
            "evidence_refs": [],
            "package_activation_authorization_binding": (
                authorization_binding
            ),
            "activation_quick_gate_binding": quick_gate_binding,
            "repository_snapshot_before": repository_snapshot,
            "previous_event_sha256": state["transition_history"][-1]["event_sha256"],
        }
        event["event_sha256"] = graph.event_sha256(event)
        if hasattr(
            graph,
            "EXPECTED_PACKAGE_ACTIVATION_EVENT_SHA256",
        ):
            graph.EXPECTED_PACKAGE_ACTIVATION_EVENT_SHA256 = event[
                "event_sha256"
            ]
        state["transition_history"].append(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]

    def activate_package(self, root: Path, checkpoint: dict) -> None:
        self.commit_prestart_control_repair(root, checkpoint)

    def activate_and_start_focus(self, root: Path, checkpoint: dict) -> None:
        self.activate_package(root, checkpoint)
        self.start_goal(
            root,
            checkpoint,
            checkpoint["goal_execution"]["focus_goal_id"],
        )

    def start_goal(
        self,
        root: Path,
        checkpoint: dict,
        goal_id: str,
    ) -> None:
        state = checkpoint["goal_execution"]
        event_id = (
            "WS-GOAL-GRAPH-GOAL-STARTED-TEST-"
            f"{len(state['transition_history']) + 1:03d}"
        )
        manifest = graph.load_json(root / graph.MANIFEST_RELATIVE)
        nodes = self.load_nodes(root, manifest, include_dynamic=True)
        paths = {}
        for relative in state["goal_document_paths"]:
            metadata, _ = graph.parse_goal(root / relative)
            paths[metadata["goal_id"]] = relative
        previous_focus_id = state["focus_goal_id"]
        previous_focus_path = root / state["focus_goal_path"]
        focus_node = nodes[goal_id]
        state["focus_goal_id"] = goal_id
        state["focus_goal_path"] = paths[goal_id]
        state["focus_work_item_id"] = (
            focus_node.get("work_item_id")
            if focus_node.get("goal_kind") == "WORK_ITEM"
            else ""
        )
        state["focus_source"] = (
            focus_node.get("materialized_from_role")
            if focus_node.get("goal_kind") == "WORK_ITEM"
            else "WORKSTREAM_GRAPH"
        )
        focus_path = root / state["focus_goal_path"]
        previous_status = state["status_by_goal"][goal_id]
        state["status_by_goal"][goal_id] = "IN_PROGRESS"
        backlog = self.load_binding_json_from_root(
            root,
            checkpoint,
            "IMPLEMENTATION_BACKLOG",
        )
        state["ready_frontier_goal_ids"] = graph.ready_frontier(
            nodes,
            state["status_by_goal"],
            state["materialized_child_goal_ids_by_parent"],
            state["blockers_by_goal"],
            backlog,
        )
        evidence_dir = (
            root / "docs/control/execution/goal-gates" / event_id
        )
        evidence_dir.mkdir(parents=True, exist_ok=True)
        gate_repository_payload = self.gate_repository_payload(
            checkpoint,
            event_id,
        )
        gate_runs = []
        safe_goal_id = goal_id.replace("/", "-")
        for index, (check_id, command) in enumerate(
            graph.IMPLEMENTATION_START_GATE_CHECKS,
            start=1,
        ):
            output_path = evidence_dir / f"{index:02d}-{check_id}.log"
            if check_id == "REPOSITORY_STATE":
                output_path.write_bytes(
                    graph.canonical_json_bytes(
                        gate_repository_payload
                    )
                    + b"\n"
                )
            else:
                output_path.write_text(
                    f"{check_id}: PASS\n",
                    encoding="utf-8",
                )
            gate_runs.append(
                {
                    "check_id": check_id,
                    "command": command,
                    "executed_at": (
                        f"2026-07-24T19:41:{10 + index:02d}+09:00"
                    ),
                    "exit_code": 0,
                    "output_path": str(output_path.relative_to(root)),
                    "output_sha256": graph.sha256_file(output_path),
                }
            )
        repository_snapshot = graph.gate_repository_state_snapshot_summary(
            gate_repository_payload,
            output_sha256=gate_runs[-1]["output_sha256"],
        )
        lock_path = (
            root / "configs/walksafe_node_toolchain_lock_20260715.json"
        )
        start_receipt_path = (
            evidence_dir
            / "implementation-start-gate-receipt.json"
        )
        start_receipt = {
            "schema_version": "1.0",
            "document_id": f"TEST-START-GATE-{safe_goal_id}",
            "evidence_type": "IMPLEMENTATION_START_OR_RESUME_GATE",
            "gate_purpose": "INITIAL_START",
            "status": "PASS",
            "package_id": graph.EXPECTED_PACKAGE_ID,
            "target_transition_event_id": event_id,
            "target_goal_id": goal_id,
            "target_goal_content_sha256": graph.sha256_file(focus_path),
            "static_plan_manifest_sha256": state[
                "static_plan_manifest_sha256"
            ],
            "source_activation_event_sha256": next(
                prior["event_sha256"]
                for prior in state["transition_history"]
                if prior.get("event_type") == "PACKAGE_ACTIVATED"
            ),
            "check_command_contract_version": (
                graph.CHECK_COMMAND_CONTRACT_VERSION
            ),
            "check_command_contract_sha256": (
                graph.IMPLEMENTATION_START_GATE_CHECK_CONTRACT_SHA256
            ),
            "toolchain_lock_binding": {
                "path": (
                    "configs/walksafe_node_toolchain_lock_20260715.json"
                ),
                "file_sha256": graph.sha256_file(lock_path),
            },
            "repository_snapshot": repository_snapshot,
            "execution_window": {
                "started_at": "2026-07-24T19:41:10+09:00",
                "ended_at": "2026-07-24T19:41:40+09:00",
            },
            "check_runs": gate_runs,
            "generated_at": "2026-07-24T19:41:50+09:00",
        }
        self.write_json(start_receipt_path, start_receipt)
        start_gate_binding = {
            "document_id": start_receipt["document_id"],
            "path": str(start_receipt_path.relative_to(root)),
            "file_sha256": graph.sha256_file(start_receipt_path),
        }
        event = {
            "sequence": len(state["transition_history"]) + 1,
            "event_id": event_id,
            "event_type": "GOAL_STARTED",
            "subject_goal_id": goal_id,
            "occurred_on": "2026-07-24",
            "occurred_at": "2026-07-24T19:42:00+09:00",
            "previous_focus_goal_id": previous_focus_id,
            "previous_focus_content_sha256": graph.sha256_file(previous_focus_path),
            "focus_goal_id": goal_id,
            "focus_goal_content_sha256": graph.sha256_file(focus_path),
            "from_status": previous_status,
            "to_status": "IN_PROGRESS",
            "static_plan_manifest_sha256": state["static_plan_manifest_sha256"],
            "status_changes": {goal_id: "IN_PROGRESS"},
            "runtime_after": self.runtime_snapshot(root, checkpoint),
            "blockers_after": json.loads(json.dumps(state["blockers_by_goal"])),
            "blocker_resolution_ids_after": [],
            "source_checkpoint_version": checkpoint["schema_version"],
            "evidence_refs": [],
            "implementation_start_gate_binding": start_gate_binding,
            "repository_snapshot_before": repository_snapshot,
            "previous_event_sha256": state["transition_history"][-1]["event_sha256"],
        }
        event["event_sha256"] = graph.event_sha256(event)
        state["transition_history"].append(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]

    def resume_goal(
        self,
        root: Path,
        checkpoint: dict,
        goal_id: str,
    ) -> None:
        state = checkpoint["goal_execution"]
        previous_event = state["transition_history"][-1]
        event_id = (
            "WS-GOAL-GRAPH-WORK-SESSION-RESUMED-TEST-"
            f"{len(state['transition_history']) + 1:03d}"
        )
        evidence_dir = (
            root / "docs/control/execution/goal-gates" / event_id
        )
        evidence_dir.mkdir(parents=True, exist_ok=True)
        gate_repository_payload = self.gate_repository_payload(
            checkpoint,
            event_id,
        )
        gate_runs = []
        for index, (check_id, command) in enumerate(
            graph.IMPLEMENTATION_START_GATE_CHECKS,
            start=1,
        ):
            output_path = evidence_dir / f"{index:02d}-{check_id}.log"
            if check_id == "REPOSITORY_STATE":
                output_path.write_bytes(
                    graph.canonical_json_bytes(
                        gate_repository_payload
                    )
                    + b"\n"
                )
            else:
                output_path.write_text(
                    f"{check_id}: RESUME PASS\n",
                    encoding="utf-8",
                )
            gate_runs.append(
                {
                    "check_id": check_id,
                    "command": command,
                    "executed_at": (
                        f"2026-07-24T20:00:{index:02d}+09:00"
                    ),
                    "exit_code": 0,
                    "output_path": str(output_path.relative_to(root)),
                    "output_sha256": graph.sha256_file(output_path),
                }
            )
        repository_snapshot = graph.gate_repository_state_snapshot_summary(
            gate_repository_payload,
            output_sha256=gate_runs[-1]["output_sha256"],
        )
        goal_path = root / state["focus_goal_path"]
        lock_path = (
            root / "configs/walksafe_node_toolchain_lock_20260715.json"
        )
        receipt_path = (
            evidence_dir / "implementation-resume-gate-receipt.json"
        )
        receipt = {
            "schema_version": "1.0",
            "document_id": f"TEST-RESUME-GATE-{goal_id}",
            "evidence_type": "IMPLEMENTATION_START_OR_RESUME_GATE",
            "gate_purpose": "SESSION_RESUME",
            "status": "PASS",
            "package_id": graph.EXPECTED_PACKAGE_ID,
            "target_transition_event_id": event_id,
            "target_goal_id": goal_id,
            "target_goal_content_sha256": graph.sha256_file(goal_path),
            "static_plan_manifest_sha256": state[
                "static_plan_manifest_sha256"
            ],
            "source_activation_event_sha256": next(
                prior["event_sha256"]
                for prior in state["transition_history"]
                if prior.get("event_type") == "PACKAGE_ACTIVATED"
            ),
            "check_command_contract_version": (
                graph.CHECK_COMMAND_CONTRACT_VERSION
            ),
            "check_command_contract_sha256": (
                graph.IMPLEMENTATION_START_GATE_CHECK_CONTRACT_SHA256
            ),
            "toolchain_lock_binding": {
                "path": (
                    "configs/walksafe_node_toolchain_lock_20260715.json"
                ),
                "file_sha256": graph.sha256_file(lock_path),
            },
            "repository_snapshot": repository_snapshot,
            "execution_window": {
                "started_at": "2026-07-24T20:00:00+09:00",
                "ended_at": "2026-07-24T20:00:30+09:00",
            },
            "check_runs": gate_runs,
            "generated_at": "2026-07-24T20:00:40+09:00",
        }
        self.write_json(receipt_path, receipt)
        binding = {
            "document_id": receipt["document_id"],
            "path": str(receipt_path.relative_to(root)),
            "file_sha256": graph.sha256_file(receipt_path),
        }
        event = {
            "sequence": len(state["transition_history"]) + 1,
            "event_id": event_id,
            "event_type": "WORK_SESSION_RESUMED",
            "subject_goal_id": goal_id,
            "occurred_on": "2026-07-24",
            "occurred_at": "2026-07-24T20:01:00+09:00",
            "previous_focus_goal_id": goal_id,
            "previous_focus_content_sha256": graph.sha256_file(goal_path),
            "focus_goal_id": goal_id,
            "focus_goal_content_sha256": graph.sha256_file(goal_path),
            "from_status": "IN_PROGRESS",
            "to_status": "IN_PROGRESS",
            "static_plan_manifest_sha256": state[
                "static_plan_manifest_sha256"
            ],
            "status_changes": {},
            "runtime_after": self.runtime_snapshot(root, checkpoint),
            "blockers_after": json.loads(json.dumps(state["blockers_by_goal"])),
            "blocker_resolution_ids_after": [],
            "source_checkpoint_version": checkpoint["schema_version"],
            "evidence_refs": [],
            "previous_execution_session_event_sha256": previous_event[
                "event_sha256"
            ],
            "implementation_start_gate_binding": binding,
            "repository_snapshot_before": repository_snapshot,
            "previous_event_sha256": previous_event["event_sha256"],
        }
        event["event_sha256"] = graph.event_sha256(event)
        state["transition_history"].append(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]

    def materialize_artifact_work(
        self,
        root: Path,
        checkpoint: dict,
        *,
        initial_status: str = "PLANNED",
        start_dependency: str = "WS-GOAL-EPIC-01",
        artifact_code: str = "DLV-DES-21",
    ) -> Path:
        state = checkpoint["goal_execution"]
        goal_id = "WS-GOAL-EPIC-03-ARTIFACT-TEST-R001"
        relative = (
            graph.PACKAGE_RELATIVE
            / "work-items/epic-03/artifact-test-r001.md"
        ).as_posix()
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        binding = graph.canonical_binding_map(checkpoint)["ARTIFACT_REGISTER"]
        predecessor_id = state["focus_goal_id"]
        predecessor_path = root / state["focus_goal_path"]
        predecessor_hash = graph.sha256_file(predecessor_path)
        path.write_text(
            f"""+++
schema_version = "2.0"
goal_id = "{goal_id}"
goal_kind = "WORK_ITEM"
document_version = "2.2.0"
parent_goal_id = "WS-GOAL-EPIC-03"
work_item_type = "ARTIFACT_WORK"
priority_rank = 1
initial_status = "{initial_status}"
target_completion_level = "ARTIFACT_TARGET_STATE_REACHED"
work_item_id = "EPIC-03-ARTIFACT-TEST"
start_requires = ["{start_dependency}"]
completion_requires = ["{start_dependency}"]
child_goal_ids = []
source_policy_ids = []
gap_ids = []
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
output_subject_ids_by_role = {{ ARTIFACT_REGISTER = ["{artifact_code}"], ARTIFACT_CHANGE_LOG = ["{artifact_code}"] }}
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
materialized_from_role = "ARTIFACT_REGISTER"
materialized_from_path = "{binding['path']}"
materialized_from_document_id = "{binding['document_id']}"
materialized_from_sha256 = "{binding['file_sha256']}"
predecessor_goal_id = "{predecessor_id}"
predecessor_goal_content_sha256 = "{predecessor_hash}"
supersedes_goal_id = ""
supersedes_goal_content_sha256 = ""
reopen_reason = ""
reopen_evidence_refs = []
artifact_work_reason = "DRAFT_COMPLETION"
artifact_trigger_evidence_refs = []
source_blocker_ids = []
+++

# Dynamic artifact test Goal

## 목표

동적 Goal 봉인을 검증한다.

## 정본 입력

ARTIFACT_REGISTER를 사용한다.

## 범위와 제외

테스트 fixture만 포함한다.

## 실행 절차

동적 노드를 생성한다.

## 검증

그래프 검사기를 실행한다.

## 완료 기준

현재는 {initial_status}다.

## 질문·중단 조건

없음.

## 완료 후 인계

ready frontier를 갱신한다.
""",
            encoding="utf-8",
        )
        digest = graph.sha256_file(path)
        state["status_by_goal"][goal_id] = initial_status
        state["materialized_child_goal_ids_by_parent"]["WS-GOAL-EPIC-03"] = [
            goal_id
        ]
        manifest = graph.load_json(root / graph.MANIFEST_RELATIVE)
        nodes = self.load_nodes(root, manifest, include_dynamic=True)
        backlog = self.load_binding_json_from_root(
            root,
            checkpoint,
            "IMPLEMENTATION_BACKLOG",
        )
        state["ready_frontier_goal_ids"] = graph.ready_frontier(
            nodes,
            state["status_by_goal"],
            state["materialized_child_goal_ids_by_parent"],
            state["blockers_by_goal"],
            backlog,
        )
        focus_path = root / state["focus_goal_path"]
        event = {
            "sequence": len(state["transition_history"]) + 1,
            "event_id": "WS-GOAL-GRAPH-GOAL-MATERIALIZED-TEST-001",
            "event_type": "GOAL_MATERIALIZED",
            "materialized_goal_id": goal_id,
            "materialized_goal_path": relative,
            "materialized_goal_content_sha256": digest,
            "materialized_from_role": "ARTIFACT_REGISTER",
            "materialized_from_path": binding["path"],
            "materialized_from_document_id": binding["document_id"],
            "materialized_from_sha256": binding["file_sha256"],
            "predecessor_goal_id": predecessor_id,
            "predecessor_goal_content_sha256": predecessor_hash,
            "supersedes_goal_id": "",
            "supersedes_goal_content_sha256": "",
            "artifact_work_reason": "DRAFT_COMPLETION",
            "artifact_trigger_evidence_refs": [],
            "artifact_trigger_evidence_bindings": {},
            "occurred_on": "2026-07-24",
            "occurred_at": "2026-07-24T19:42:00+09:00",
            "previous_focus_goal_id": state["focus_goal_id"],
            "previous_focus_content_sha256": graph.sha256_file(focus_path),
            "focus_goal_id": state["focus_goal_id"],
            "focus_goal_content_sha256": graph.sha256_file(focus_path),
            "from_status": "",
            "to_status": initial_status,
            "static_plan_manifest_sha256": state["static_plan_manifest_sha256"],
            "status_changes": {goal_id: initial_status},
            "runtime_after": self.runtime_snapshot(root, checkpoint),
            "blockers_after": json.loads(json.dumps(state["blockers_by_goal"])),
            "blocker_resolution_ids_after": [],
            "source_checkpoint_version": checkpoint["schema_version"],
            "evidence_refs": ["ARTIFACT_REGISTER"],
            "previous_event_sha256": state["transition_history"][-1]["event_sha256"],
        }
        event["event_sha256"] = graph.event_sha256(event)
        state["transition_history"].append(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]
        state["dynamic_goal_inventory"][goal_id] = {
            "artifact_trigger_evidence_refs": [],
            "artifact_work_reason": "DRAFT_COMPLETION",
            "goal_id": goal_id,
            "path": relative,
            "sha256": digest,
            "goal_kind": "WORK_ITEM",
            "work_item_type": "ARTIFACT_WORK",
            "parent_goal_id": "WS-GOAL-EPIC-03",
            "materialized_event_sha256": event["event_sha256"],
            "materialized_from_role": "ARTIFACT_REGISTER",
            "materialized_from_path": binding["path"],
            "materialized_from_document_id": binding["document_id"],
            "materialized_from_sha256": binding["file_sha256"],
            "predecessor_goal_id": predecessor_id,
            "predecessor_goal_content_sha256": predecessor_hash,
            "supersedes_goal_id": "",
            "supersedes_goal_content_sha256": "",
        }
        paths = graph.discover_package_paths(root)
        state["managed_goal_paths"] = paths
        state["managed_goal_path_count"] = len(paths)
        state["goal_document_paths"] = sorted(
            set(state["goal_document_paths"]) | {relative}
        )
        state["goal_document_count"] = len(state["goal_document_paths"])
        state["path_set_sha256"], state["content_set_sha256"] = graph.package_hashes(
            root,
            paths,
        )
        return path

    def ready_materialized_artifact(
        self,
        root: Path,
        checkpoint: dict,
        *,
        dependency_event_sha256: str | None = None,
    ) -> None:
        state = checkpoint["goal_execution"]
        goal_id = "WS-GOAL-EPIC-03-ARTIFACT-TEST-R001"
        manifest = graph.load_json(root / graph.MANIFEST_RELATIVE)
        nodes = self.load_nodes(root, manifest, include_dynamic=True)
        node = nodes[goal_id]
        previous_status = state["status_by_goal"][goal_id]
        state["status_by_goal"][goal_id] = "READY"
        backlog = self.load_binding_json_from_root(
            root,
            checkpoint,
            "IMPLEMENTATION_BACKLOG",
        )
        state["ready_frontier_goal_ids"] = graph.ready_frontier(
            nodes,
            state["status_by_goal"],
            state["materialized_child_goal_ids_by_parent"],
            state["blockers_by_goal"],
            backlog,
        )
        focus_path = root / state["focus_goal_path"]
        dependencies = node["start_requires"]
        basis = []
        for dependency in dependencies:
            event_hash = dependency_event_sha256
            if event_hash is None and dependency == "WS-GOAL-EPIC-01":
                event_hash = state["transition_history"][0]["event_sha256"]
            basis.append({"goal_id": dependency, "event_sha256": event_hash})
        event = {
            "sequence": len(state["transition_history"]) + 1,
            "event_id": "WS-GOAL-GRAPH-GOAL-READY-TEST-001",
            "event_type": "GOAL_READY",
            "subject_goal_id": goal_id,
            "occurred_on": "2026-07-24",
            "occurred_at": "2026-07-24T19:43:00+09:00",
            "previous_focus_goal_id": state["focus_goal_id"],
            "previous_focus_content_sha256": graph.sha256_file(focus_path),
            "focus_goal_id": state["focus_goal_id"],
            "focus_goal_content_sha256": graph.sha256_file(focus_path),
            "from_status": previous_status,
            "to_status": "READY",
            "readiness_basis": {"dependency_completion_events": basis},
            "static_plan_manifest_sha256": state["static_plan_manifest_sha256"],
            "status_changes": {goal_id: "READY"},
            "runtime_after": self.runtime_snapshot(root, checkpoint),
            "blockers_after": json.loads(json.dumps(state["blockers_by_goal"])),
            "blocker_resolution_ids_after": [],
            "source_checkpoint_version": checkpoint["schema_version"],
            "evidence_refs": [],
            "previous_event_sha256": state["transition_history"][-1]["event_sha256"],
        }
        event["event_sha256"] = graph.event_sha256(event)
        state["transition_history"].append(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]

    def add_completion_receipt_binding(
        self,
        root: Path,
        checkpoint: dict,
    ) -> Path:
        state = checkpoint["goal_execution"]
        goal_id = state["focus_goal_id"]
        goal_path = root / state["focus_goal_path"]
        start_event = state["transition_history"][-1]
        role = f"WORK_ITEM_COMPLETION::{goal_id}"
        document_id = "WS-GOAL-GRAPH-WORK-ITEM-COMPLETION-TEST-001"
        relative = "docs/control/execution/graph-test-work-item-completion.json"
        path = root / relative
        self.write_json(
            path,
            {
                "evidence_type": "WORK_ITEM_EXECUTION_RECEIPT",
                "document_id": document_id,
                "status": "ACCEPTED",
                "result": "PASS",
                "target_goal_id": goal_id,
                "target_goal_content_sha256": graph.sha256_file(goal_path),
                "work_item_id": state["focus_work_item_id"],
                "source_policy_ids": ["FP-018"],
                "gap_ids": ["GAP-027"],
                "target_completion_level": "INTERNAL_POLICY_CONFORMANCE_REASSESSED",
                "execution_start_event_sha256": start_event["event_sha256"],
                "execution_window": {
                    "started_at": "2026-07-24T19:43:00+09:00",
                    "ended_at": "2026-07-24T19:45:00+09:00",
                },
                "completed_at": "2026-07-24T19:44:00+09:00",
                "reviewer": {
                    "actor_id": "TEST-REVIEWER",
                    "decided_at": "2026-07-24T19:46:00+09:00",
                },
                "generated_at": "2026-07-24T19:47:00+09:00",
                "result_evidence": [],
            },
        )
        checkpoint["canonical_bindings"].append(
            {
                "role": role,
                "path": relative,
                "document_id": document_id,
                "identity_json_path": "document_id",
                "file_sha256": graph.sha256_file(path),
                "mutable": False,
            }
        )
        self.append_binding_update_event(
            root,
            checkpoint,
            [role],
            occurred_at="2026-07-24T19:43:00+09:00",
            event_id="WS-GOAL-GRAPH-BINDINGS-UPDATED-TEST-001",
        )
        return path

    def append_binding_update_event(
        self,
        root: Path,
        checkpoint: dict,
        changed_roles: list[str],
        *,
        occurred_at: str,
        event_id: str,
    ) -> None:
        state = checkpoint["goal_execution"]
        binding_snapshot = graph.canonical_binding_snapshot(
            graph.canonical_binding_map(checkpoint)
        )
        previous_binding_snapshot = next(
            event["canonical_binding_snapshot_after"]
            for event in reversed(state["transition_history"])
            if "canonical_binding_snapshot_after" in event
        )
        subject_errors, changed_subjects_by_role = (
            graph.canonical_changed_subject_ids_by_role(
                root,
                changed_roles=sorted(changed_roles),
                bindings_before=previous_binding_snapshot,
                bindings_after=binding_snapshot,
            )
        )
        self.assertEqual(subject_errors, [])
        focus_path = root / state["focus_goal_path"]
        event = {
            "sequence": len(state["transition_history"]) + 1,
            "event_id": event_id,
            "event_type": "CANONICAL_BINDINGS_UPDATED",
            "changed_binding_roles": sorted(changed_roles),
            "changed_subject_ids_by_role": changed_subjects_by_role,
            "impact_closure_goal_ids": [],
            "impact_disposition_by_goal": {},
            "reopened_completion_event_sha256_by_goal": {},
            "canonical_binding_snapshot_after": binding_snapshot,
            "occurred_on": occurred_at.split("T", 1)[0],
            "occurred_at": occurred_at,
            "previous_focus_goal_id": state["focus_goal_id"],
            "previous_focus_content_sha256": graph.sha256_file(focus_path),
            "focus_goal_id": state["focus_goal_id"],
            "focus_goal_content_sha256": graph.sha256_file(focus_path),
            "from_status": state["status_by_goal"][state["focus_goal_id"]],
            "to_status": state["status_by_goal"][state["focus_goal_id"]],
            "static_plan_manifest_sha256": state["static_plan_manifest_sha256"],
            "status_changes": {},
            "runtime_after": self.runtime_snapshot(root, checkpoint),
            "blockers_after": json.loads(json.dumps(state["blockers_by_goal"])),
            "blocker_resolution_ids_after": [],
            "source_checkpoint_version": checkpoint["schema_version"],
            "evidence_refs": sorted(changed_roles),
            "previous_event_sha256": state["transition_history"][-1]["event_sha256"],
        }
        event["event_sha256"] = graph.event_sha256(event)
        state["transition_history"].append(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]

    def refresh_latest_binding_hash(
        self,
        checkpoint: dict,
        path: Path,
    ) -> None:
        state = checkpoint["goal_execution"]
        digest = graph.sha256_file(path)
        for binding in checkpoint["canonical_bindings"]:
            if binding["path"].endswith(path.name):
                binding["file_sha256"] = digest
                role = binding["role"]
                break
        else:
            raise AssertionError("test receipt binding not found")
        update_event = state["transition_history"][-1]
        update_event["canonical_binding_snapshot_after"][role][
            "file_sha256"
        ] = digest
        self.rehash_event(checkpoint)

    def complete_focus_work_item(
        self,
        root: Path,
        checkpoint: dict,
    ) -> None:
        state = checkpoint["goal_execution"]
        goal_id = state["focus_goal_id"]
        previous_focus_path = root / state["focus_goal_path"]
        role = f"WORK_ITEM_COMPLETION::{goal_id}"
        binding = graph.canonical_binding_snapshot(
            graph.canonical_binding_map(checkpoint)
        )[role]
        state["status_by_goal"][goal_id] = "COMPLETE_AT_TARGET"
        state["completion_evidence_by_goal"][goal_id] = [role]
        manifest = graph.load_json(root / graph.MANIFEST_RELATIVE)
        nodes = self.load_nodes(root, manifest, include_dynamic=True)
        backlog = self.load_binding_json_from_root(
            root,
            checkpoint,
            "IMPLEMENTATION_BACKLOG",
        )
        state["ready_frontier_goal_ids"] = graph.ready_frontier(
            nodes,
            state["status_by_goal"],
            state["materialized_child_goal_ids_by_parent"],
            state["blockers_by_goal"],
            backlog,
        )
        next_goal_id = state["ready_frontier_goal_ids"][0]
        paths = {}
        for relative in state["goal_document_paths"]:
            metadata, _ = graph.parse_goal(root / relative)
            paths[metadata["goal_id"]] = relative
        next_node = nodes[next_goal_id]
        state["focus_goal_id"] = next_goal_id
        state["focus_goal_path"] = paths[next_goal_id]
        state["focus_work_item_id"] = (
            next_node.get("work_item_id")
            if next_node.get("goal_kind") == "WORK_ITEM"
            else ""
        )
        state["focus_source"] = (
            next_node.get("materialized_from_role")
            if next_node.get("goal_kind") == "WORK_ITEM"
            else "WORKSTREAM_GRAPH"
        )
        next_path = root / state["focus_goal_path"]
        event = {
            "sequence": len(state["transition_history"]) + 1,
            "event_id": "WS-GOAL-GRAPH-GOAL-COMPLETED-TEST-001",
            "event_type": "GOAL_COMPLETED",
            "subject_goal_id": goal_id,
            "occurred_on": "2026-07-24",
            "occurred_at": "2026-07-24T19:48:00+09:00",
            "previous_focus_goal_id": goal_id,
            "previous_focus_content_sha256": graph.sha256_file(
                previous_focus_path
            ),
            "focus_goal_id": next_goal_id,
            "focus_goal_content_sha256": graph.sha256_file(next_path),
            "from_status": "IN_PROGRESS",
            "to_status": "COMPLETE_AT_TARGET",
            "static_plan_manifest_sha256": state["static_plan_manifest_sha256"],
            "status_changes": {goal_id: "COMPLETE_AT_TARGET"},
            "runtime_after": self.runtime_snapshot(root, checkpoint),
            "blockers_after": json.loads(json.dumps(state["blockers_by_goal"])),
            "blocker_resolution_ids_after": [],
            "source_checkpoint_version": checkpoint["schema_version"],
            "evidence_refs": [role],
            "completion_evidence_bindings": {role: binding},
            "completion_receipt_binding": binding,
            "previous_event_sha256": state["transition_history"][-1]["event_sha256"],
        }
        event["event_sha256"] = graph.event_sha256(event)
        state["transition_history"].append(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]

    def supersede_completed_focus_work_item(
        self,
        root: Path,
        checkpoint: dict,
    ) -> None:
        state = checkpoint["goal_execution"]
        old_goal_id = "WS-GOAL-EPIC-02-FP-018-R001"
        new_goal_id = "WS-GOAL-EPIC-02-FP-018-R002"
        old_relative = state["dynamic_goal_inventory"][old_goal_id]["path"]
        old_path = root / old_relative
        old_hash = graph.sha256_file(old_path)
        relative = (
            graph.PACKAGE_RELATIVE
            / "work-items/epic-02/"
            "epic-02-fp018-walk-state-recovery-r002.md"
        ).as_posix()
        path = root / relative
        binding = graph.canonical_binding_map(checkpoint)[
            "IMPLEMENTATION_BACKLOG"
        ]
        path.write_text(
            f"""+++
schema_version = "2.0"
goal_id = "{new_goal_id}"
goal_kind = "WORK_ITEM"
document_version = "2.2.0"
parent_goal_id = "WS-GOAL-EPIC-02"
work_item_type = "POLICY_GAP_WORK"
priority_rank = 1
initial_status = "PLANNED"
target_completion_level = "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
work_item_id = "EPIC-02-FP018-WALK-STATE-RECOVERY"
start_requires = ["WS-GOAL-EPIC-01"]
completion_requires = ["WS-GOAL-EPIC-01"]
child_goal_ids = []
source_policy_ids = ["FP-018"]
gap_ids = ["GAP-027"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
materialized_from_role = "IMPLEMENTATION_BACKLOG"
materialized_from_path = "{binding['path']}"
materialized_from_document_id = "{binding['document_id']}"
materialized_from_sha256 = "{binding['file_sha256']}"
predecessor_goal_id = "{old_goal_id}"
predecessor_goal_content_sha256 = "{old_hash}"
supersedes_goal_id = "{old_goal_id}"
supersedes_goal_content_sha256 = "{old_hash}"
reopen_reason = "REGRESSION_DEFECT_RECEIPT"
reopen_evidence_refs = []
artifact_work_reason = ""
artifact_trigger_evidence_refs = []
source_blocker_ids = []
output_subject_ids_by_role = {{}}
+++

# FP-018 test successor

## 목표

역사 완료 증거 검증을 시험한다.

## 정본 입력

승인 정책과 Backlog를 사용한다.

## 범위와 제외

테스트 fixture만 포함한다.

## 실행 절차

successor를 준비한다.

## 검증

이전 receipt를 다시 검증한다.

## 완료 기준

현재는 PLANNED다.

## 질문·중단 조건

없음.

## 완료 후 인계

GOAL_READY를 기다린다.
""",
            encoding="utf-8",
        )
        digest = graph.sha256_file(path)
        previous_focus_id = state["focus_goal_id"]
        previous_focus_path = root / state["focus_goal_path"]
        state["goal_document_paths"] = sorted(
            set(state["goal_document_paths"]) | {relative}
        )
        state["goal_document_count"] = len(state["goal_document_paths"])
        state["status_by_goal"][old_goal_id] = "SUPERSEDED"
        state["status_by_goal"][new_goal_id] = "PLANNED"
        state["archived_completion_evidence_by_goal"][old_goal_id] = (
            state["completion_evidence_by_goal"].pop(old_goal_id)
        )
        children = state["materialized_child_goal_ids_by_parent"][
            "WS-GOAL-EPIC-02"
        ]
        state["materialized_child_goal_ids_by_parent"][
            "WS-GOAL-EPIC-02"
        ] = sorted(set(children) | {new_goal_id})
        manifest = graph.load_json(root / graph.MANIFEST_RELATIVE)
        nodes = self.load_nodes(root, manifest, include_dynamic=True)
        paths = {}
        for goal_path in state["goal_document_paths"]:
            metadata, _ = graph.parse_goal(root / goal_path)
            paths[metadata["goal_id"]] = goal_path
        backlog = self.load_binding_json_from_root(
            root,
            checkpoint,
            "IMPLEMENTATION_BACKLOG",
        )
        state["ready_frontier_goal_ids"] = graph.ready_frontier(
            nodes,
            state["status_by_goal"],
            state["materialized_child_goal_ids_by_parent"],
            state["blockers_by_goal"],
            backlog,
        )
        next_goal_id = state["ready_frontier_goal_ids"][0]
        next_node = nodes[next_goal_id]
        state["focus_goal_id"] = next_goal_id
        state["focus_goal_path"] = paths[next_goal_id]
        state["focus_work_item_id"] = (
            next_node.get("work_item_id")
            if next_node.get("goal_kind") == "WORK_ITEM"
            else ""
        )
        state["focus_source"] = (
            next_node.get("materialized_from_role")
            if next_node.get("goal_kind") == "WORK_ITEM"
            else "WORKSTREAM_GRAPH"
        )
        next_path = root / state["focus_goal_path"]
        event = {
            "sequence": len(state["transition_history"]) + 1,
            "event_id": "WS-GOAL-GRAPH-GOAL-SUPERSEDED-TEST-001",
            "event_type": "GOAL_SUPERSEDED",
            "subject_goal_id": old_goal_id,
            "materialized_goal_id": new_goal_id,
            "materialized_goal_path": relative,
            "materialized_goal_content_sha256": digest,
            "materialized_from_role": "IMPLEMENTATION_BACKLOG",
            "materialized_from_path": binding["path"],
            "materialized_from_document_id": binding["document_id"],
            "materialized_from_sha256": binding["file_sha256"],
            "predecessor_goal_id": old_goal_id,
            "predecessor_goal_content_sha256": old_hash,
            "supersedes_goal_id": old_goal_id,
            "supersedes_goal_content_sha256": old_hash,
            "artifact_work_reason": "",
            "artifact_trigger_evidence_refs": [],
            "occurred_on": "2026-07-24",
            "occurred_at": "2026-07-24T19:49:00+09:00",
            "previous_focus_goal_id": previous_focus_id,
            "previous_focus_content_sha256": graph.sha256_file(
                previous_focus_path
            ),
            "focus_goal_id": next_goal_id,
            "focus_goal_content_sha256": graph.sha256_file(next_path),
            "from_status": "COMPLETE_AT_TARGET",
            "to_status": "SUPERSEDED",
            "static_plan_manifest_sha256": state[
                "static_plan_manifest_sha256"
            ],
            "status_changes": {
                old_goal_id: "SUPERSEDED",
                new_goal_id: "PLANNED",
            },
            "runtime_after": self.runtime_snapshot(root, checkpoint),
            "blockers_after": json.loads(json.dumps(state["blockers_by_goal"])),
            "blocker_resolution_ids_after": [],
            "source_checkpoint_version": checkpoint["schema_version"],
            "evidence_refs": [],
            "previous_event_sha256": state["transition_history"][-1][
                "event_sha256"
            ],
        }
        event["event_sha256"] = graph.event_sha256(event)
        state["transition_history"].append(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]
        state["dynamic_goal_inventory"][new_goal_id] = {
            "artifact_trigger_evidence_refs": [],
            "artifact_work_reason": "",
            "goal_id": new_goal_id,
            "path": relative,
            "sha256": digest,
            "goal_kind": "WORK_ITEM",
            "work_item_type": "POLICY_GAP_WORK",
            "parent_goal_id": "WS-GOAL-EPIC-02",
            "materialized_event_sha256": event["event_sha256"],
            "materialized_from_role": "IMPLEMENTATION_BACKLOG",
            "materialized_from_path": binding["path"],
            "materialized_from_document_id": binding["document_id"],
            "materialized_from_sha256": binding["file_sha256"],
            "predecessor_goal_id": old_goal_id,
            "predecessor_goal_content_sha256": old_hash,
            "supersedes_goal_id": old_goal_id,
            "supersedes_goal_content_sha256": old_hash,
        }
        package_paths = graph.discover_package_paths(root)
        state["managed_goal_paths"] = package_paths
        state["managed_goal_path_count"] = len(package_paths)
        state["path_set_sha256"], state["content_set_sha256"] = (
            graph.package_hashes(root, package_paths)
        )

    def complete_package_early(self, root: Path, checkpoint: dict) -> None:
        state = checkpoint["goal_execution"]
        root_goal_id = graph.EXPECTED_ROOT_GOAL_ID
        previous_focus_id = state["focus_goal_id"]
        previous_focus_path = root / state["focus_goal_path"]
        state["status_by_goal"][root_goal_id] = "COMPLETE_AT_TARGET"
        state["completion_evidence_by_goal"][root_goal_id] = []
        state["package_status"] = "COMPLETED"
        state["activation_status"] = "COMPLETED"
        state["goal_status"] = "COMPLETE_AT_TARGET"
        state["focus_goal_id"] = ""
        state["focus_goal_path"] = ""
        state["focus_work_item_id"] = ""
        state["focus_source"] = ""
        state["ready_frontier_goal_ids"] = []
        event = {
            "sequence": len(state["transition_history"]) + 1,
            "event_id": "WS-GOAL-GRAPH-PACKAGE-COMPLETED-EARLY-TEST-001",
            "event_type": "PACKAGE_COMPLETED",
            "subject_goal_id": root_goal_id,
            "occurred_on": "2026-07-24",
            "occurred_at": "2026-07-24T19:42:00+09:00",
            "previous_focus_goal_id": previous_focus_id,
            "previous_focus_content_sha256": graph.sha256_file(
                previous_focus_path
            ),
            "focus_goal_id": "",
            "focus_goal_content_sha256": "",
            "from_status": "READY",
            "to_status": "COMPLETE_AT_TARGET",
            "static_plan_manifest_sha256": state[
                "static_plan_manifest_sha256"
            ],
            "status_changes": {root_goal_id: "COMPLETE_AT_TARGET"},
            "runtime_after": self.runtime_snapshot(root, checkpoint),
            "blockers_after": json.loads(json.dumps(state["blockers_by_goal"])),
            "blocker_resolution_ids_after": [],
            "source_checkpoint_version": checkpoint["schema_version"],
            "evidence_refs": [],
            "previous_event_sha256": state["transition_history"][-1][
                "event_sha256"
            ],
        }
        event["event_sha256"] = graph.event_sha256(event)
        state["transition_history"].append(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]

    def record_planned_artifact_blocker(
        self,
        root: Path,
        checkpoint: dict,
        *,
        blocker_id: str = "TEST-BLOCKER-001",
        request_key: str | None = None,
        event_id: str = (
            "WS-GOAL-GRAPH-BLOCKER-RECORDED-TEST-001"
        ),
        occurred_at: str = "2026-07-24T19:43:00+09:00",
    ) -> dict:
        state = checkpoint["goal_execution"]
        goal_id = "WS-GOAL-EPIC-03-ARTIFACT-TEST-R001"
        goal_path = root / state["dynamic_goal_inventory"][goal_id]["path"]
        blocker = {
            "blocker_id": blocker_id,
            "blocks_goal_id": goal_id,
            "condition_code": "EXTERNAL_EVIDENCE_REQUIRED",
            "owner": "EXTERNAL",
            "request_kind": "EXTERNAL_ACTION_EVIDENCE",
            "requested_action": "외부 증거를 준비한다.",
            "required_resolution_evidence_roles": [
                f"BLOCKER_RESOLUTION::{blocker_id}"
            ],
            "authority_requirement": "EXTERNAL_ATTESTATION",
            "request_event_id": event_id,
            "target_goal_path": str(goal_path.relative_to(root)),
            "target_goal_content_sha256": graph.sha256_file(goal_path),
            "target_work_item_type": "ARTIFACT_WORK",
            "created_at": occurred_at,
            "return_status": "PLANNED",
            "prompt": "외부 증거를 준비한다.",
        }
        blocker["request_key"] = (
            request_key
            if request_key is not None
            else graph.deterministic_request_key(blocker)
        )
        blocker["blocker_snapshot_sha256"] = (
            graph.legacy.blocker_snapshot_sha256(blocker)
        )
        state["status_by_goal"][goal_id] = "AWAITING_EXTERNAL"
        state["blockers_by_goal"][goal_id] = [blocker]
        state["blocked_goal_ids"] = [goal_id]
        manifest = graph.load_json(root / graph.MANIFEST_RELATIVE)
        nodes = self.load_nodes(root, manifest, include_dynamic=True)
        backlog = self.load_binding_json_from_root(
            root,
            checkpoint,
            "IMPLEMENTATION_BACKLOG",
        )
        state["ready_frontier_goal_ids"] = graph.ready_frontier(
            nodes,
            state["status_by_goal"],
            state["materialized_child_goal_ids_by_parent"],
            state["blockers_by_goal"],
            backlog,
        )
        focus_path = root / state["focus_goal_path"]
        event = {
            "sequence": len(state["transition_history"]) + 1,
            "event_id": event_id,
            "event_type": "BLOCKER_RECORDED",
            "subject_goal_id": goal_id,
            "blocker_ids": [blocker["blocker_id"]],
            "occurred_on": occurred_at.split("T", 1)[0],
            "occurred_at": occurred_at,
            "previous_focus_goal_id": state["focus_goal_id"],
            "previous_focus_content_sha256": graph.sha256_file(focus_path),
            "focus_goal_id": state["focus_goal_id"],
            "focus_goal_content_sha256": graph.sha256_file(focus_path),
            "from_status": "PLANNED",
            "to_status": "AWAITING_EXTERNAL",
            "static_plan_manifest_sha256": state[
                "static_plan_manifest_sha256"
            ],
            "status_changes": {goal_id: "AWAITING_EXTERNAL"},
            "runtime_after": self.runtime_snapshot(root, checkpoint),
            "blockers_after": json.loads(
                json.dumps(state["blockers_by_goal"])
            ),
            "blocker_resolution_ids_after": [
                resolution["blocker_id"]
                for resolution in state["blocker_resolution_history"]
            ],
            "source_checkpoint_version": checkpoint["schema_version"],
            "evidence_refs": [],
            "previous_event_sha256": state["transition_history"][-1][
                "event_sha256"
            ],
        }
        event["event_sha256"] = graph.event_sha256(event)
        state["transition_history"].append(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]
        return blocker

    def resolve_artifact_blocker(
        self,
        root: Path,
        checkpoint: dict,
        blocker: dict,
    ) -> None:
        state = checkpoint["goal_execution"]
        goal_id = blocker["blocks_goal_id"]
        blocker_event = state["transition_history"][-1]
        receipt_ref = "BLOCKER_RESOLUTION::TEST-BLOCKER-001"
        state["blocker_resolution_history"].append(
            {
                "blocker_id": blocker["blocker_id"],
                "goal_id": goal_id,
                "condition_code": blocker["condition_code"],
                "owner": blocker["owner"],
                "blocker_event_sha256": blocker_event["event_sha256"],
                "blocker_snapshot_sha256": blocker[
                    "blocker_snapshot_sha256"
                ],
                "resolution_receipt_ref": receipt_ref,
            }
        )
        state["status_by_goal"][goal_id] = "PLANNED"
        state["blockers_by_goal"].pop(goal_id)
        state["blocked_goal_ids"] = []
        manifest = graph.load_json(root / graph.MANIFEST_RELATIVE)
        nodes = self.load_nodes(root, manifest, include_dynamic=True)
        backlog = self.load_binding_json_from_root(
            root,
            checkpoint,
            "IMPLEMENTATION_BACKLOG",
        )
        state["ready_frontier_goal_ids"] = graph.ready_frontier(
            nodes,
            state["status_by_goal"],
            state["materialized_child_goal_ids_by_parent"],
            state["blockers_by_goal"],
            backlog,
        )
        focus_path = root / state["focus_goal_path"]
        event = {
            "sequence": len(state["transition_history"]) + 1,
            "event_id": "WS-GOAL-GRAPH-BLOCKER-RESOLVED-TEST-001",
            "event_type": "BLOCKER_RESOLVED",
            "subject_goal_id": goal_id,
            "resolved_blocker_ids": [blocker["blocker_id"]],
            "occurred_on": "2026-07-24",
            "occurred_at": "2026-07-24T19:44:00+09:00",
            "previous_focus_goal_id": state["focus_goal_id"],
            "previous_focus_content_sha256": graph.sha256_file(focus_path),
            "focus_goal_id": state["focus_goal_id"],
            "focus_goal_content_sha256": graph.sha256_file(focus_path),
            "from_status": "AWAITING_EXTERNAL",
            "to_status": "PLANNED",
            "static_plan_manifest_sha256": state[
                "static_plan_manifest_sha256"
            ],
            "status_changes": {goal_id: "PLANNED"},
            "runtime_after": self.runtime_snapshot(root, checkpoint),
            "blockers_after": {},
            "blocker_resolution_ids_after": [blocker["blocker_id"]],
            "source_checkpoint_version": checkpoint["schema_version"],
            "evidence_refs": [receipt_ref],
            "previous_event_sha256": state["transition_history"][-1][
                "event_sha256"
            ],
        }
        event["event_sha256"] = graph.event_sha256(event)
        state["transition_history"].append(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]

    def runtime_snapshot(self, root: Path, checkpoint: dict) -> dict:
        state = checkpoint["goal_execution"]
        manifest = graph.load_json(root / graph.MANIFEST_RELATIVE)
        nodes = self.load_nodes(root, manifest, include_dynamic=True)
        bindings = graph.canonical_binding_map(checkpoint)
        _, artifact_work_queue = graph.derive_artifact_work_queue(
            root,
            bindings,
            nodes,
            state["status_by_goal"],
        )
        _, completion_boundary = graph.derive_completion_boundary(
            nodes,
            state["status_by_goal"],
            state["ready_frontier_goal_ids"],
            state["blockers_by_goal"],
            artifact_work_queue,
            package_status=state["package_status"],
        )
        state["artifact_work_queue"] = artifact_work_queue
        state["completion_boundary"] = completion_boundary
        internal_frontier = [
            goal_id
            for goal_id in state["ready_frontier_goal_ids"]
            if not (
                nodes.get(goal_id, {}).get("goal_kind") == "WORK_ITEM"
                and nodes.get(goal_id, {}).get("work_item_type")
                in graph.DYNAMIC_EXTERNAL_TYPES
            )
        ]
        state["pending_questions"] = graph.pending_user_questions(
            state["blockers_by_goal"],
            delivery_deferred=bool(internal_frontier),
        )
        state["open_question_count"] = len(state["pending_questions"])
        return {
            "focus_goal_id": state["focus_goal_id"],
            "focus_goal_path": state["focus_goal_path"],
            "focus_work_item_id": state["focus_work_item_id"],
            "focus_source": state["focus_source"],
            "ready_frontier_goal_ids": list(state["ready_frontier_goal_ids"]),
            "blocked_goal_ids": list(state["blocked_goal_ids"]),
            "pending_questions": json.loads(
                json.dumps(state["pending_questions"])
            ),
            "open_question_count": state["open_question_count"],
            "artifact_work_queue": json.loads(
                json.dumps(state["artifact_work_queue"])
            ),
            "completion_boundary": json.loads(
                json.dumps(state["completion_boundary"])
            ),
            "activation_status": state["activation_status"],
            "package_status": state["package_status"],
        }

    def policy_gap_catalog_fixture(
        self,
        root: Path,
    ) -> tuple[dict, dict, dict[str, str]]:
        result_items = []
        hashes: dict[str, str] = {}
        for kind in (
            "IMPLEMENTATION_RECORD",
            "VERIFICATION_RESULT",
            "SUCCESSOR_TRACE",
        ):
            path = root / f"{kind.lower()}.json"
            self.write_json(path, {"kind": kind})
            digest = graph.sha256_file(path)
            hashes[kind] = digest
            result_items.append(
                {
                    "kind": kind,
                    "path": path.name,
                    "sha256": digest,
                }
            )
        evidence_record = {
            "evidence_id": "EVD-FP018-CURRENT",
            "kind": "INTERNAL_PRODUCER_RESULT",
            "claim": "FP-018 내부 구현과 검증 결과",
            "producer_goal_id": "GOAL-FP018",
            "producer_completion_receipt_role": (
                "WORK_ITEM_COMPLETION::GOAL-FP018"
            ),
            "result_evidence_sha256_by_kind": {
                kind: hashes[kind]
                for kind in (
                    "IMPLEMENTATION_RECORD",
                    "VERIFICATION_RESULT",
                )
            },
            "files": [
                {
                    "path": "implementation_record.json",
                    "sha256": hashes["IMPLEMENTATION_RECORD"],
                }
            ],
        }
        return (
            {
                "assessments": [
                    {
                        "gap_id": "GAP-027",
                        "evidence_ids": ["EVD-FP018-CURRENT"],
                    }
                ],
                "evidence_catalog": [evidence_record],
            },
            {"result_evidence": result_items},
            hashes,
        )

    def typed_external_blocker(self) -> dict:
        record = {
            "blocker_id": "EXT-1",
            "owner": "EXTERNAL",
            "request_kind": "EXTERNAL_ACTION_EVIDENCE",
            "requested_action": "외부 시험을 수행한다.",
            "required_resolution_evidence_roles": [
                "BLOCKER_RESOLUTION::EXT-1"
            ],
            "authority_requirement": "EXTERNAL_ATTESTATION",
            "request_event_id": "EVENT-EXT-1",
            "target_goal_path": "external.md",
            "target_goal_content_sha256": "1" * 64,
            "target_work_item_type": "FORMAL_TEST_RUN",
            "blocker_snapshot_sha256": "2" * 64,
        }
        record["request_key"] = graph.deterministic_request_key(record)
        return record

    def policy_backlog_bootstrap_fixture(self) -> dict:
        ordered_policies = [
            "FP-017",
            "FP-018",
            "NPC-PERMISSION-SESSION-LIFECYCLE",
        ]
        before = {
            "execution_order": ["EPIC-02"],
            "epics": [
                {
                    "epic_id": "EPIC-02",
                    "ordered_source_policy_ids": ordered_policies,
                    "current_status": "IN_PROGRESS",
                    "current_status_reason": "정책/Gap 구현 진행 중",
                }
            ],
            "next_single_action": {
                "epic_id": "EPIC-02",
                "work_item_id": "GOAL-FP018",
                "source_policy_id": "FP-018",
                "gap_id": "GAP-027",
                "status": "IN_PROGRESS",
                "action": "FP-018 구현",
            },
        }
        after = json.loads(json.dumps(before))
        after["next_single_action"] = {
            "epic_id": "EPIC-02",
            "work_item_id": "GOAL-NPC",
            "source_policy_id": "NPC-PERMISSION-SESSION-LIFECYCLE",
            "gap_id": "GAP-006",
            "status": "PLANNED_NEXT",
            "action": "다음 정책/Gap 구현",
        }
        return {
            "producer_goal_id": "GOAL-FP018",
            "producer_node": {
                "goal_kind": "WORK_ITEM",
                "work_item_type": "POLICY_GAP_WORK",
                "parent_goal_id": "WS-EPIC-02",
                "source_policy_ids": ["FP-018"],
                "gap_ids": ["GAP-027"],
            },
            "backlog_before": before,
            "backlog_after": after,
            "gap_after": {
                "assessments": [
                    {
                        "source_policy_id": "FP-017",
                        "gap_id": "GAP-026",
                    },
                    {
                        "source_policy_id": "FP-018",
                        "gap_id": "GAP-027",
                    },
                    {
                        "source_policy_id": (
                            "NPC-PERMISSION-SESSION-LIFECYCLE"
                        ),
                        "gap_id": "GAP-006",
                    },
                ]
            },
            "nodes": {
                "WS-EPIC-02": {
                    "goal_kind": "WORKSTREAM",
                    "external_key": "EPIC-02",
                    "source_policy_ids": ordered_policies,
                    "target_completion_level": "IMPLEMENTATION_READY",
                },
                "GOAL-FP018": {
                    "goal_kind": "WORK_ITEM",
                    "work_item_type": "POLICY_GAP_WORK",
                    "parent_goal_id": "WS-EPIC-02",
                    "source_policy_ids": ["FP-018"],
                    "gap_ids": ["GAP-027"],
                },
            },
            "statuses_before_update": {
                "WS-EPIC-02": "READY",
                "GOAL-FP018": "IN_PROGRESS",
            },
        }

    def copy_prepared_package(self, root: Path) -> dict:
        manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
        base_paths = sorted(
            {
                graph.MANIFEST_RELATIVE.as_posix(),
                *(
                    record["path"]
                    for record in manifest["protected_files"]
                ),
            }
        )
        self.assertEqual(
            len(base_paths),
            len(manifest["protected_files"]) + 1,
        )
        for relative in base_paths:
            source = ROOT / relative
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        return manifest

    def project_prepared_checkpoint(
        self,
        root: Path,
        checkpoint: dict,
        manifest: dict,
    ) -> None:
        clone = lambda value: json.loads(json.dumps(value))
        state = checkpoint["goal_execution"]
        prepared = clone(state["transition_history"][0])
        self.assertEqual(prepared["sequence"], 1)
        self.assertEqual(prepared["event_type"], "PACKAGE_PREPARED")
        self.assertEqual(
            prepared["event_sha256"],
            graph.EXPECTED_INITIAL_EVENT_SHA256,
        )
        self.assertEqual(prepared["previous_event_sha256"], "")
        self.assertEqual(
            prepared["blocker_resolution_ids_after"],
            [],
        )

        state["transition_history"] = [prepared]
        state["transition_history_anchor_sha256"] = prepared[
            "event_sha256"
        ]
        state["validation_cutoff_at"] = prepared["occurred_at"]
        state["status_by_goal"] = clone(prepared["status_changes"])
        state["goal_status"] = state["status_by_goal"][
            graph.EXPECTED_ROOT_GOAL_ID
        ]
        state["blockers_by_goal"] = clone(prepared["blockers_after"])
        state["blocker_resolution_history"] = []
        for field, value in prepared["runtime_after"].items():
            state[field] = clone(value)
        state["completion_evidence_by_goal"] = clone(
            graph.EXPECTED_PREACTIVATION_COMPLETION_EVIDENCE_BY_GOAL
        )
        state["archived_completion_evidence_by_goal"] = {}
        state["verification_evidence_refs"] = []
        state["pending_reopen_goal_ids"] = []
        state["pending_producer_completion_goal_id"] = ""

        initial_id = graph.EXPECTED_INITIAL_FOCUS_GOAL_ID
        initial_record = clone(
            state["dynamic_goal_inventory"][initial_id]
        )
        state["dynamic_goal_inventory"] = {
            initial_id: initial_record,
        }
        state["materialized_child_goal_ids_by_parent"] = {
            initial_record["parent_goal_id"]: [initial_id],
        }

        rows_by_role = {
            row["role"]: row
            for row in checkpoint["canonical_bindings"]
        }
        checkpoint["canonical_bindings"] = []
        for role, snapshot in prepared[
            "canonical_binding_snapshot_after"
        ].items():
            row = clone(rows_by_role[role])
            row.update(clone(snapshot))
            checkpoint["canonical_bindings"].append(row)

        managed_paths = graph.discover_package_paths(root)
        goal_paths = sorted(
            {
                *(
                    record["path"]
                    for record in manifest["goal_graph"][
                        "static_nodes"
                    ]
                ),
                initial_record["path"],
            }
        )
        state["managed_goal_paths"] = managed_paths
        state["managed_goal_path_count"] = len(managed_paths)
        state["goal_document_paths"] = goal_paths
        state["goal_document_count"] = len(goal_paths)
        state["support_paths"] = sorted(
            set(managed_paths) - set(goal_paths)
        )
        (
            state["path_set_sha256"],
            state["content_set_sha256"],
        ) = graph.package_hashes(root, managed_paths)

    @contextmanager
    def fixture_root(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = self.copy_prepared_package(root)
            shutil.copytree(
                ROOT / graph.V21_PACKAGE_RELATIVE,
                root / graph.V21_PACKAGE_RELATIVE,
            )
            shutil.copytree(
                ROOT
                / "docs/control/goals/walksafe-completion-graph-v2",
                root
                / "docs/control/goals/walksafe-completion-graph-v2",
            )
            shutil.copytree(
                ROOT / "docs/control/goals/walksafe-completion-v1",
                root / "docs/control/goals/walksafe-completion-v1",
            )
            (root / graph.CHECKPOINT_RELATIVE).parent.mkdir(
                parents=True,
                exist_ok=True,
            )
            shutil.copy2(
                ROOT / graph.CHECKPOINT_RELATIVE,
                root / graph.CHECKPOINT_RELATIVE,
            )
            checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
            self.project_prepared_checkpoint(
                root,
                checkpoint,
                manifest,
            )
            self.write_json(
                root / graph.CHECKPOINT_RELATIVE,
                checkpoint,
            )
            for binding in graph.canonical_binding_map(checkpoint).values():
                source = ROOT / binding["path"]
                target = root / binding["path"]
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target)
            for relative in (
                "docs/control/baselines/"
                "walksafe-feature-policy-baseline-1.0.0-manifest-20260721-r001.json",
                "docs/control/decision-interview/"
                "walksafe-feature-policy-comprehensive-draft.json",
                "configs/walksafe_node_toolchain_lock_20260715.json",
            ):
                source = ROOT / relative
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target)
            yield root

    @contextmanager
    def gate_capture_root(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)

            def git(*arguments: str) -> str:
                completed = subprocess.run(
                    ["git", *arguments],
                    cwd=root,
                    check=False,
                    capture_output=True,
                    text=True,
                )
                self.assertEqual(
                    completed.returncode,
                    0,
                    completed.stderr,
                )
                return completed.stdout.strip()

            git("init", "-b", "main")
            git("config", "user.name", "WalkSafe Goal Test")
            git(
                "config",
                "user.email",
                "walksafe-goal@example.invalid",
            )
            files = {
                "controlled.txt": "controlled\n",
                "modified.txt": "before\n",
                "deleted.txt": "delete\n",
                "renamed-old.txt": "rename\n",
                "staged.txt": "before stage\n",
            }
            for relative, content in files.items():
                path = root / relative
                path.write_text(content, encoding="utf-8")
            git("add", ".")
            git("commit", "-m", "gate fixture baseline")

            (root / "modified.txt").write_text(
                "modified bytes\n",
                encoding="utf-8",
            )
            (root / "deleted.txt").unlink()
            git("mv", "renamed-old.txt", "renamed-new.txt")
            (root / "staged.txt").write_text(
                "staged bytes\n",
                encoding="utf-8",
            )
            git("add", "staged.txt")
            (root / "staged.txt").write_text(
                "worktree bytes\n",
                encoding="utf-8",
            )
            (root / "untracked.bin").write_bytes(b"\x00UNTRACKED\xff")

            checkpoint_path = (
                root / graph.GATE_REPOSITORY_CHECKPOINT_PATH
            )
            checkpoint_path.parent.mkdir(parents=True, exist_ok=True)
            managed_paths = ["controlled.txt"]
            path_set_sha256 = hashlib.sha256(
                b"controlled.txt\n"
            ).hexdigest()
            _, content_set_sha256 = (
                graph.continuation.working_snapshot_hashes(
                    root,
                    managed_paths,
                )
            )
            head_commit = git("rev-parse", "HEAD")
            self.write_json(
                checkpoint_path,
                {
                    "working_tree_snapshot": {
                        "base_head": head_commit,
                        "managed_changed_paths": managed_paths,
                        "managed_changed_path_count": 1,
                        "path_set_sha256": path_set_sha256,
                        "content_set_sha256": content_set_sha256,
                    },
                    "session_handoff": {
                        "source_commit_or_snapshot": {
                            "current_head": head_commit,
                        }
                    },
                },
            )
            yield root, checkpoint_path

    def refresh_integrity(self, root: Path) -> None:
        manifest_path = root / graph.MANIFEST_RELATIVE
        manifest = graph.load_json(manifest_path)
        for record in manifest["protected_files"]:
            path = root / record["path"]
            if path.exists():
                record["sha256"] = graph.sha256_file(path)
        self.write_json(manifest_path, manifest)
        manifest_hash = graph.sha256_file(manifest_path)
        checkpoint = graph.load_json(root / graph.CHECKPOINT_RELATIVE)
        state = checkpoint["goal_execution"]
        state["static_plan_manifest_sha256"] = manifest_hash
        event = state["transition_history"][0]
        event["static_plan_manifest_sha256"] = manifest_hash
        focus_path = root / state["focus_goal_path"]
        event["focus_goal_content_sha256"] = graph.sha256_file(focus_path)
        graph.EXPECTED_MANIFEST_SHA256 = manifest_hash
        self.rehash_event(checkpoint)
        for record in state.get("dynamic_goal_inventory", {}).values():
            goal_path = root / record["path"]
            if goal_path.exists():
                record["sha256"] = graph.sha256_file(goal_path)
            if record["goal_id"] == graph.EXPECTED_INITIAL_FOCUS_GOAL_ID:
                record["materialized_event_sha256"] = state["transition_history"][0][
                    "event_sha256"
                ]
        paths = graph.discover_package_paths(root)
        state["managed_goal_paths"] = paths
        state["managed_goal_path_count"] = len(paths)
        state["path_set_sha256"], state["content_set_sha256"] = graph.package_hashes(
            root,
            paths,
        )
        self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

    def rehash_event(self, checkpoint: dict) -> None:
        state = checkpoint["goal_execution"]
        previous = ""
        for event in state["transition_history"]:
            event["previous_event_sha256"] = previous
            event["event_sha256"] = graph.event_sha256(event)
            previous = event["event_sha256"]
        state["transition_history_anchor_sha256"] = previous
        graph.EXPECTED_INITIAL_EVENT_SHA256 = state["transition_history"][0][
            "event_sha256"
        ]

    def gate_repository_payload(
        self,
        checkpoint: dict,
        event_id: str,
    ) -> dict:
        working = checkpoint["working_tree_snapshot"]
        head_commit = working["base_head"]
        empty_hash = graph.canonical_json_sha256([])
        return {
            "schema_version": graph.GATE_REPOSITORY_STATE_SCHEMA_VERSION,
            "evidence_type": graph.GATE_REPOSITORY_STATE_EVIDENCE_TYPE,
            "gate_event_id": event_id,
            "canonicalization": dict(
                graph.GATE_REPOSITORY_STATE_CANONICALIZATION
            ),
            "repository": {
                "root": ".",
                "head_commit": head_commit,
                "branch": checkpoint["repository"]["branch"],
                "object_format": (
                    "sha1" if len(head_commit) == 40 else "sha256"
                ),
            },
            "git_status_raw": {
                "command": list(
                    graph.GATE_REPOSITORY_STATE_GIT_STATUS_COMMAND
                ),
                "config_overrides": dict(
                    graph.GATE_REPOSITORY_STATE_GIT_STATUS_CONFIG
                ),
                "scope": "AFTER_EXACT_TRANSACTION_EXCLUSIONS",
                "sha256": hashlib.sha256(b"").hexdigest(),
                "byte_count": 0,
                "record_count": 0,
            },
            "transaction_exclusions": {
                "allowed_rule_count": 2,
                "checkpoint_exact_path": (
                    graph.GATE_REPOSITORY_CHECKPOINT_PATH
                ),
                "gate_event_exact_prefix": (
                    f"docs/control/execution/goal-gates/{event_id}/"
                ),
            },
            "dirty_snapshot": {
                "dirty_path_count": 0,
                "path_set_sha256": empty_hash,
                "content_set_sha256": empty_hash,
                "index_state_sha256": empty_hash,
                "paths": [],
            },
            "checkpoint_controlled_working_snapshot": {
                "base_head": working["base_head"],
                "managed_changed_path_count": working[
                    "managed_changed_path_count"
                ],
                "path_set_sha256": working["path_set_sha256"],
                "content_set_sha256": working["content_set_sha256"],
            },
        }

    def recorded_gate_repository_payload(
        self,
        root: Path,
        event: dict,
    ) -> dict:
        receipt = graph.load_json(
            root / event["implementation_start_gate_binding"]["path"]
        )
        return graph.load_json(root / receipt["check_runs"][-1]["output_path"])

    def rehash_gate_dirty_payload(self, payload: dict) -> None:
        paths = payload["dirty_snapshot"]["paths"]
        payload["dirty_snapshot"]["path_set_sha256"] = (
            graph.canonical_json_sha256(
                [entry["path"] for entry in paths]
            )
        )
        payload["dirty_snapshot"]["content_set_sha256"] = (
            graph.canonical_json_sha256(
                [
                    {
                        "path": entry["path"],
                        "worktree": entry["worktree"],
                    }
                    for entry in paths
                ]
            )
        )
        payload["dirty_snapshot"]["index_state_sha256"] = (
            graph.canonical_json_sha256(
                [
                    {
                        "path": entry["path"],
                        "path_role": entry["path_role"],
                        "counterpart_path": entry["counterpart_path"],
                        "status": entry["status"],
                        "index_entries": entry["index_entries"],
                    }
                    for entry in paths
                ]
            )
        )

    def save_rehashed_checkpoint(
        self,
        root: Path,
        checkpoint: dict,
    ) -> None:
        self.rehash_event(checkpoint)
        initial_event_hash = checkpoint["goal_execution"][
            "transition_history"
        ][0]["event_sha256"]
        for record in checkpoint["goal_execution"].get(
            "dynamic_goal_inventory",
            {},
        ).values():
            if record.get("goal_id") == graph.EXPECTED_INITIAL_FOCUS_GOAL_ID:
                record["materialized_event_sha256"] = initial_event_hash
        self.write_json(root / graph.CHECKPOINT_RELATIVE, checkpoint)

    def load_nodes(
        self,
        root: Path,
        manifest: dict,
        *,
        include_dynamic: bool = False,
    ) -> dict[str, dict]:
        paths = [
            item["path"] for item in manifest["goal_graph"]["static_nodes"]
        ]
        if include_dynamic:
            paths.extend(
                relative
                for relative in graph.discover_package_paths(root)
                if "/work-items/" in relative and relative.endswith(".md")
            )
        nodes = {}
        for relative in paths:
            metadata, _ = graph.parse_goal(root / relative)
            nodes[metadata["goal_id"]] = metadata
        return nodes

    def load_binding_json(self, checkpoint: dict, role: str) -> dict:
        binding = graph.canonical_binding_map(checkpoint)[role]
        return graph.load_json(ROOT / binding["path"])

    def load_binding_json_from_root(
        self,
        root: Path,
        checkpoint: dict,
        role: str,
    ) -> dict:
        binding = graph.canonical_binding_map(checkpoint)[role]
        return graph.load_json(root / binding["path"])

    def declaration(
        self,
        goal_id: str,
        parent: str,
        kind: str,
        priority: int,
    ) -> dict:
        return {
            "goal_id": goal_id,
            "goal_kind": kind,
            "external_key": goal_id,
            "workstream_type": "PROJECT" if kind == "MASTER" else "IMPLEMENTATION",
            "parent_goal_id": parent,
            "priority_rank": priority,
            "start_requires": [],
            "completion_requires": [],
            "target_completion_level": "TARGET",
        }

    def node(
        self,
        goal_id: str,
        parent: str,
        kind: str,
        priority: int,
        children: list[str],
    ) -> dict:
        return {
            **self.declaration(goal_id, parent, kind, priority),
            "child_goal_ids": children,
        }

    def write_json(self, path: Path, value: dict) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(value, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )


if __name__ == "__main__":
    unittest.main()

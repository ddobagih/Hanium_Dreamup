from __future__ import annotations

import importlib.util
import json
import shutil
import tempfile
import unittest
from contextlib import contextmanager
from pathlib import Path

from scripts import check_walksafe_goal_graph_v2_3 as graph


ROOT = Path(__file__).resolve().parents[1]
FROZEN_TEST_RELATIVE = Path("tests/test_walksafe_goal_graph.py")
FROZEN_CHECKER_RELATIVE = Path("scripts/check_walksafe_goal_graph.py")
EXPECTED_FROZEN_SEMANTIC_TEST_COUNT = 223
FP010_GOAL_ID = "WS-GOAL-EPIC-02-FP-010-R001"
FP011_GOAL_ID = "WS-GOAL-EPIC-02-FP-011-R001"
FP011_GOAL_PATH = (
    "docs/control/goals/walksafe-completion-graph-v2-3/"
    "work-items/epic-02/epic-02-fp011-long-lived-login-r001.md"
)


def _load_v2_3_test_case():
    spec = importlib.util.spec_from_file_location(
        "_walksafe_goal_graph_v2_3_frozen_semantic_tests",
        ROOT / FROZEN_TEST_RELATIVE,
    )
    if spec is None or spec.loader is None:
        raise RuntimeError("frozen v2.2 Goal graph tests cannot be loaded")
    frozen_tests = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(frozen_tests)
    frozen_tests.graph = graph
    base_case = frozen_tests.WalkSafeGoalGraphTest
    frozen_test_names = tuple(
        unittest.defaultTestLoader.getTestCaseNames(base_case)
    )
    checker_spec = importlib.util.spec_from_file_location(
        "_walksafe_goal_graph_v2_2_frozen_checker_for_v2_3_tests",
        ROOT / FROZEN_CHECKER_RELATIVE,
    )
    if checker_spec is None or checker_spec.loader is None:
        raise RuntimeError("frozen v2.2 Goal graph checker cannot be loaded")
    frozen_checker = importlib.util.module_from_spec(checker_spec)
    checker_spec.loader.exec_module(frozen_checker)

    class WalkSafeGoalGraphV23Test(base_case):
        """Run the frozen semantic suite against the v2.3 successor."""

        @staticmethod
        def _clone(value):
            return json.loads(json.dumps(value))

        @staticmethod
        def _imported_workstream(relative: str) -> str:
            return (
                graph.V22_PACKAGE_RELATIVE / "workstreams" / relative
            ).as_posix()

        def copy_repository_reference_closure(
            self,
            root: Path,
            seed: object,
        ) -> None:
            source_root = ROOT.resolve()
            pending = [seed]
            visited_paths: set[str] = set()
            parsed_json_paths: set[str] = set()

            def strings(value: object):
                if isinstance(value, dict):
                    for key in sorted(value):
                        yield from strings(value[key])
                elif isinstance(value, list):
                    for item in value:
                        yield from strings(item)
                elif isinstance(value, str):
                    yield value

            while pending:
                candidates = sorted(set(strings(pending.pop())))
                for candidate in candidates:
                    try:
                        candidate_size = len(
                            candidate.encode("utf-8")
                        )
                        candidate_path = Path(candidate)
                    except (TypeError, UnicodeEncodeError, ValueError):
                        continue
                    raw_parts = candidate.split("/")
                    if (
                        candidate_path.is_absolute()
                        or not candidate_path.parts
                        or any(
                            part in {"", ".", ".."}
                            for part in raw_parts
                        )
                        or any(
                            len(part.encode("utf-8")) > 255
                            for part in raw_parts
                        )
                        or candidate_size
                        > 4095
                        - len(source_root.as_posix().encode("utf-8"))
                        - 1
                        or candidate_path == graph.CHECKPOINT_RELATIVE
                    ):
                        continue
                    relative = candidate_path
                    relative_key = relative.as_posix()
                    if relative_key in visited_paths:
                        continue
                    visited_paths.add(relative_key)
                    try:
                        source = (ROOT / relative).resolve()
                        source.relative_to(source_root)
                        source_is_file = source.is_file()
                    except (OSError, ValueError):
                        continue
                    if not source_is_file:
                        continue
                    target = root / relative
                    try:
                        target_is_file = target.is_file()
                    except OSError:
                        continue
                    if not target_is_file:
                        target.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copy2(source, target)
                    if (
                        source.suffix == ".json"
                        and relative_key.startswith(
                            "docs/control/execution/goal-gates/"
                        )
                        and relative_key not in parsed_json_paths
                    ):
                        parsed_json_paths.add(relative_key)
                        try:
                            pending.append(graph.load_json(target))
                        except (
                            OSError,
                            ValueError,
                            json.JSONDecodeError,
                        ):
                            pass

        @contextmanager
        def fixture_root(self):
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                manifest = self.copy_prepared_package(root)
                for relative in (
                    graph.V22_PACKAGE_RELATIVE,
                    graph.V21_PACKAGE_RELATIVE,
                    Path(
                        "docs/control/goals/"
                        "walksafe-completion-graph-v2"
                    ),
                    Path("docs/control/goals/walksafe-completion-v1"),
                ):
                    shutil.copytree(ROOT / relative, root / relative)
                for relative in (
                    FROZEN_CHECKER_RELATIVE,
                    FROZEN_TEST_RELATIVE,
                ):
                    target = root / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(ROOT / relative, target)
                (root / graph.CHECKPOINT_RELATIVE).parent.mkdir(
                    parents=True,
                    exist_ok=True,
                )
                shutil.copy2(
                    ROOT / graph.CHECKPOINT_RELATIVE,
                    root / graph.CHECKPOINT_RELATIVE,
                )
                checkpoint = graph.load_json(
                    root / graph.CHECKPOINT_RELATIVE
                )
                self.project_prepared_checkpoint(
                    root,
                    checkpoint,
                    manifest,
                )
                self.write_json(
                    root / graph.CHECKPOINT_RELATIVE,
                    checkpoint,
                )
                for binding in graph.canonical_binding_map(
                    checkpoint
                ).values():
                    source = ROOT / binding["path"]
                    target = root / binding["path"]
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(source, target)
                for relative in (
                    "docs/control/baselines/"
                    "walksafe-feature-policy-baseline-1.0.0-"
                    "manifest-20260721-r001.json",
                    "docs/control/decision-interview/"
                    "walksafe-feature-policy-comprehensive-draft.json",
                    "configs/walksafe_node_toolchain_lock_20260715.json",
                ):
                    source = ROOT / relative
                    target = root / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(source, target)
                self.copy_controlled_fixture_files(root, checkpoint)
                archived = graph.load_json(
                    root / graph.V22_ARCHIVED_CHECKPOINT_RELATIVE
                )
                self.copy_repository_reference_closure(
                    root,
                    archived,
                )
                yield root

        def project_prepared_checkpoint(
            self,
            root: Path,
            checkpoint: dict,
            manifest: dict,
        ) -> None:
            state = checkpoint["goal_execution"]
            prepared = self._clone(state["transition_history"][0])
            self.assertEqual(prepared["sequence"], 1)
            self.assertEqual(prepared["event_type"], "PACKAGE_PREPARED")
            self.assertEqual(
                prepared["event_sha256"],
                graph.EXPECTED_INITIAL_EVENT_SHA256,
            )
            self.assertEqual(prepared["previous_event_sha256"], "")

            archived = graph.load_json(
                root / graph.V22_ARCHIVED_CHECKPOINT_RELATIVE
            )
            archived_state = archived["goal_execution"]
            state["transition_history"] = [prepared]
            state["transition_history_anchor_sha256"] = prepared[
                "event_sha256"
            ]
            state["validation_cutoff_at"] = prepared["occurred_at"]
            state["status_by_goal"] = self._clone(
                prepared["status_changes"]
            )
            state["goal_status"] = state["status_by_goal"][
                graph.EXPECTED_ROOT_GOAL_ID
            ]
            for field, value in prepared["runtime_after"].items():
                state[field] = self._clone(value)
            for field in (
                "blockers_by_goal",
                "blocker_resolution_history",
                "completion_evidence_by_goal",
                "archived_completion_evidence_by_goal",
                "dynamic_goal_inventory",
                "materialized_child_goal_ids_by_parent",
                "pending_reopen_goal_ids",
                "pending_producer_completion_goal_id",
                "verification_evidence_refs",
            ):
                state[field] = self._clone(archived_state[field])

            rows_by_role = {
                row["role"]: row
                for row in checkpoint["canonical_bindings"]
            }
            checkpoint["canonical_bindings"] = []
            for role, snapshot in prepared[
                "canonical_binding_snapshot_after"
            ].items():
                row = self._clone(rows_by_role[role])
                row.update(self._clone(snapshot))
                checkpoint["canonical_bindings"].append(row)

            imported_paths = sorted(
                record["path"]
                for record in state[
                    "imported_predecessor_goal_bindings"
                ].values()
            )
            managed_paths = graph.discover_package_paths(root)
            state["managed_goal_paths"] = managed_paths
            state["managed_goal_path_count"] = len(managed_paths)
            state["goal_document_paths"] = imported_paths
            state["goal_document_count"] = len(imported_paths)
            state["support_paths"] = sorted(
                set(managed_paths) - set(imported_paths)
            )
            (
                state["path_set_sha256"],
                state["content_set_sha256"],
            ) = graph.package_hashes(root, managed_paths)

        def refresh_integrity(self, root: Path) -> None:
            manifest_path = root / graph.MANIFEST_RELATIVE
            manifest = graph.load_json(manifest_path)
            for record in manifest["protected_files"]:
                path = root / record["path"]
                if path.exists():
                    record["sha256"] = graph.sha256_file(path)
            self.write_json(manifest_path, manifest)
            manifest_hash = graph.sha256_file(manifest_path)

            checkpoint = graph.load_json(
                root / graph.CHECKPOINT_RELATIVE
            )
            state = checkpoint["goal_execution"]
            state["static_plan_manifest_sha256"] = manifest_hash
            event = state["transition_history"][0]
            event["static_plan_manifest_sha256"] = manifest_hash
            event["focus_goal_content_sha256"] = graph.sha256_file(
                root / state["focus_goal_path"]
            )
            graph.EXPECTED_MANIFEST_SHA256 = manifest_hash
            self.rehash_event(checkpoint)

            managed_paths = graph.discover_package_paths(root)
            imported_paths = {
                record["path"]
                for record in state[
                    "imported_predecessor_goal_bindings"
                ].values()
            }
            inventory_paths = {
                record["path"]
                for record in state.get(
                    "dynamic_goal_inventory",
                    {},
                ).values()
                if isinstance(record, dict)
                and isinstance(record.get("path"), str)
            }
            goal_paths = sorted(imported_paths | inventory_paths)
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
            self.write_json(
                root / graph.CHECKPOINT_RELATIVE,
                checkpoint,
            )

        def save_rehashed_checkpoint(
            self,
            root: Path,
            checkpoint: dict,
        ) -> None:
            self.rehash_event(checkpoint)
            self.write_json(
                root / graph.CHECKPOINT_RELATIVE,
                checkpoint,
            )

        def archived_changed_artifact_errors(
            self,
            root: Path,
        ) -> list[str]:
            hard_errors, deferred_errors = (
                graph.partition_archived_changed_artifact_errors(
                    graph._validate_archived_checkpoint_with_frozen_v22_checker(
                        root
                    )
                )
            )
            self.assertEqual(hard_errors, [])
            self.assertTrue(deferred_errors)
            self.assertEqual(
                len(deferred_errors),
                len(set(deferred_errors)),
            )
            for error in deferred_errors:
                match = (
                    graph.ARCHIVED_CHANGED_ARTIFACT_ERROR_PATTERN.fullmatch(
                        error
                    )
                )
                self.assertIsNotNone(match, error)
            return deferred_errors

        def archived_changed_artifact_path(
            self,
            root: Path,
            error: str,
        ) -> str:
            match = graph.ARCHIVED_CHANGED_ARTIFACT_ERROR_PATTERN.fullmatch(
                error
            )
            self.assertIsNotNone(match, error)
            goal_id = match.group("goal_id")
            artifact_index = int(match.group("index"))
            receipt = graph.load_json(
                root
                / "docs/control/execution/goal-results"
                / goal_id
                / "completion-receipt.json"
            )
            implementation_binding = next(
                item
                for item in receipt["result_evidence"]
                if item["kind"] == "IMPLEMENTATION_RECORD"
            )
            implementation = graph.load_json(
                root / implementation_binding["path"]
            )
            return implementation["changed_artifacts"][
                artifact_index
            ]["path"]

        def stage_fp005_completion_witness(
            self,
            root: Path,
            checkpoint: dict,
            *,
            append_event: bool = True,
        ) -> tuple[dict, dict]:
            goal_id = "WS-GOAL-EPIC-02-FP-005-R001"
            result_relative = Path(
                "docs/control/execution/goal-results"
            ) / goal_id
            source = ROOT / result_relative
            target = root / result_relative
            if source.resolve() != target.resolve():
                shutil.copytree(source, target, dirs_exist_ok=True)
            receipt_relative = (
                result_relative / "completion-receipt.json"
            ).as_posix()
            receipt = graph.load_json(root / receipt_relative)
            role = f"WORK_ITEM_COMPLETION::{goal_id}"
            binding = {
                "role": role,
                "document_id": receipt["document_id"],
                "path": receipt_relative,
                "file_sha256": graph.sha256_file(
                    root / receipt_relative
                ),
            }
            event = {
                "sequence": 4,
                "event_type": "GOAL_COMPLETED",
                "subject_goal_id": goal_id,
                "completion_evidence_bindings": {
                    role: self._clone(binding)
                },
                "completion_receipt_binding": self._clone(binding),
            }
            event["event_sha256"] = graph.event_sha256(event)
            checkpoint["goal_execution"]["status_by_goal"][goal_id] = (
                "COMPLETE_AT_TARGET"
            )
            if append_event:
                checkpoint["goal_execution"]["transition_history"].append(
                    event
                )
            return event, binding

        def deferred_archive_replay_errors(
            self,
            root: Path,
            checkpoint: dict,
        ) -> list[str]:
            state = checkpoint["goal_execution"]
            node_errors, nodes = graph.current_goal_nodes(root, state)
            archive_errors, archived = (
                graph._load_archived_v22_checkpoint(root)
            )
            self.assertEqual(node_errors, [])
            self.assertEqual(archive_errors, [])
            return graph.validate_deferred_archived_implementation_changes(
                root,
                checkpoint=checkpoint,
                nodes=nodes,
                deferred_errors=self.archived_changed_artifact_errors(
                    root
                ),
                archived=archived,
            )

        def activate_package(
            self,
            root: Path,
            checkpoint: dict,
        ) -> None:
            self._activate_package_only(root, checkpoint)

        def supersede_completed_focus_work_item(
            self,
            root: Path,
            checkpoint: dict,
        ) -> None:
            (
                root
                / graph.PACKAGE_RELATIVE
                / "work-items/epic-02"
            ).mkdir(parents=True, exist_ok=True)
            super().supersede_completed_focus_work_item(
                root,
                checkpoint,
            )

        def test_frozen_semantic_regression_count_is_exact(self) -> None:
            self.assertEqual(
                len(frozen_test_names),
                EXPECTED_FROZEN_SEMANTIC_TEST_COUNT,
            )

        def test_archive_changed_artifact_errors_are_deferred_only(
            self,
        ) -> None:
            changed = (
                "archived v2.2: WS-GOAL-EXAMPLE-R001: "
                "implementation changed artifact 7 differs"
            )
            event_changed = (
                "archived v2.2: Goal graph event 11: "
                "WS-GOAL-EXAMPLE-R001: implementation changed artifact "
                "7 differs"
            )
            near_miss = (
                "archived v2.2: Goal graph event 0: "
                "WS-GOAL-EXAMPLE-R001: implementation changed artifact "
                "7 differs"
            )
            hard, deferred = (
                graph.partition_archived_changed_artifact_errors(
                    [
                        changed,
                        event_changed,
                        near_miss,
                        (
                            "archived v2.2: protected file content "
                            "changed: example"
                        ),
                    ]
                )
            )
            self.assertEqual(deferred, [changed, event_changed])
            self.assertEqual(
                graph.archived_changed_artifact_core_error(
                    event_changed
                ),
                (
                    "WS-GOAL-EXAMPLE-R001: implementation changed "
                    "artifact 7 differs"
                ),
            )
            self.assertEqual(
                hard,
                [
                    near_miss,
                    (
                        "archived v2.2: protected file content "
                        "changed: example"
                    )
                ],
            )

        def test_cross_package_completion_order_precedes_raw_sequence(
            self,
        ) -> None:
            predecessor = graph.completion_event_with_package_generation(
                {"sequence": 18},
                graph.V22_PACKAGE_GENERATION,
            )
            successor = graph.completion_event_with_package_generation(
                {"sequence": 4},
                graph.V23_PACKAGE_GENERATION,
            )
            self.assertGreater(
                graph.completion_event_order(successor),
                graph.completion_event_order(predecessor),
            )

        def test_committed_v2_3_history_is_exact_through_fp006_start(
            self,
        ) -> None:
            checkpoint = graph.load_json(
                ROOT / graph.CHECKPOINT_RELATIVE
            )
            history = checkpoint["goal_execution"]["transition_history"]

            self.assertGreaterEqual(
                len(history),
                len(graph.EXPECTED_COMMITTED_V23_HISTORY_PREFIX),
            )
            self.assertEqual(
                [
                    (
                        event["event_id"],
                        event["event_type"],
                        event["event_sha256"],
                    )
                    for event in history[
                        : len(graph.EXPECTED_COMMITTED_V23_HISTORY_PREFIX)
                    ]
                ],
                list(graph.EXPECTED_COMMITTED_V23_HISTORY_PREFIX),
            )
            self.assertEqual(
                graph.validate_committed_v23_history_prefix(history),
                [],
            )

        def test_committed_v2_3_history_rejects_rehashed_prefix_drift(
            self,
        ) -> None:
            checkpoint = graph.load_json(
                ROOT / graph.CHECKPOINT_RELATIVE
            )
            history = self._clone(
                checkpoint["goal_execution"]["transition_history"]
            )
            history[7]["occurred_at"] = "2026-07-24T21:44:04+09:00"
            for index in range(7, len(history)):
                history[index]["previous_event_sha256"] = (
                    history[index - 1]["event_sha256"]
                )
                history[index]["event_sha256"] = graph.event_sha256(
                    history[index]
                )

            self.assertIn(
                "Goal graph v2.3 committed event 8 differs",
                graph.validate_committed_v23_history_prefix(history),
            )

        def test_goal_status_summary_remains_bound_to_master_at_fp010_start(
            self,
        ) -> None:
            checkpoint = graph.load_json(
                ROOT / graph.CHECKPOINT_RELATIVE
            )
            state = self._clone(checkpoint["goal_execution"])
            fp010_goal_id = "WS-GOAL-EPIC-02-FP-010-R001"
            state["status_by_goal"][fp010_goal_id] = "IN_PROGRESS"
            state["focus_goal_id"] = fp010_goal_id
            state["goal_status"] = state["status_by_goal"][
                graph.EXPECTED_ROOT_GOAL_ID
            ]

            self.assertEqual(
                graph.validate_root_goal_status_summary(
                    state,
                    state["status_by_goal"],
                ),
                [],
            )
            state["goal_status"] = "IN_PROGRESS"
            self.assertEqual(
                graph.validate_root_goal_status_summary(
                    state,
                    state["status_by_goal"],
                ),
                ["Goal graph root summary status differs"],
            )

        def test_fp006_to_fp010_suffix_uses_the_generic_transaction_path(
            self,
        ) -> None:
            checkpoint = graph.load_json(
                ROOT / graph.CHECKPOINT_RELATIVE
            )
            prefix = self._clone(
                checkpoint["goal_execution"]["transition_history"][:8]
            )
            fp006_goal_id = "WS-GOAL-EPIC-02-FP-006-R001"
            fp010_goal_id = "WS-GOAL-EPIC-02-FP-010-R001"
            fp010_goal_path = (
                "docs/control/goals/"
                "walksafe-completion-graph-v2-3/work-items/epic-02/"
                "epic-02-fp010-first-run-registration-r001.md"
            )
            suffix = [
                {
                    "sequence": 9,
                    "event_type": "CANONICAL_BINDINGS_UPDATED",
                    "changed_binding_roles": [
                        "IMPLEMENTATION_BACKLOG",
                        "IMPLEMENTATION_GAP",
                        f"WORK_ITEM_COMPLETION::{fp006_goal_id}",
                    ],
                },
                {
                    "sequence": 10,
                    "event_type": "GOAL_COMPLETED",
                    "subject_goal_id": fp006_goal_id,
                },
                {
                    "sequence": 11,
                    "event_type": "GOAL_MATERIALIZED",
                    "materialized_goal_id": fp010_goal_id,
                    "materialized_goal_path": fp010_goal_path,
                },
                {
                    "sequence": 12,
                    "event_type": "GOAL_READY",
                    "subject_goal_id": fp010_goal_id,
                },
                {
                    "sequence": 13,
                    "event_type": "GOAL_STARTED",
                    "subject_goal_id": fp010_goal_id,
                },
            ]

            self.assertEqual(
                graph.validate_committed_v23_history_prefix(
                    prefix + suffix
                ),
                [],
            )
            self.assertTrue(
                all(
                    event["event_type"] in graph.ALLOWED_EVENT_TYPES
                    for event in suffix
                )
            )
            self.assertEqual(
                graph.pending_canonical_transaction_order_errors(
                    pending_producer_completion_goal_id=fp006_goal_id,
                    pending_reopen_goal_ids=set(),
                    event_type=suffix[1]["event_type"],
                    subject_goal_id=suffix[1]["subject_goal_id"],
                ),
                [],
            )
            self.assertEqual(
                suffix[2]["materialized_goal_path"],
                fp010_goal_path,
            )

        def test_fp010_to_fp011_candidate_uses_the_ordered_transaction_path(
            self,
        ) -> None:
            checkpoint = graph.load_json(
                ROOT / graph.CHECKPOINT_RELATIVE
            )
            history = checkpoint["goal_execution"]["transition_history"]
            fp011_is_materialized = (
                FP011_GOAL_ID
                in checkpoint["goal_execution"]["dynamic_goal_inventory"]
            )

            if not fp011_is_materialized:
                self.assertEqual(len(history), 13)
                self.assertEqual(
                    (
                        history[-1]["sequence"],
                        history[-1]["event_type"],
                        history[-1]["subject_goal_id"],
                    ),
                    (13, "GOAL_STARTED", FP010_GOAL_ID),
                )
                return

            self.assertGreaterEqual(len(history), 17)
            suffix = history[13:17]
            self.assertEqual(
                [
                    (
                        event["sequence"],
                        event["event_type"],
                        event.get("subject_goal_id"),
                        event.get("materialized_goal_id"),
                    )
                    for event in suffix
                ],
                [
                    (14, "CANONICAL_BINDINGS_UPDATED", None, None),
                    (15, "GOAL_COMPLETED", FP010_GOAL_ID, None),
                    (16, "GOAL_MATERIALIZED", None, FP011_GOAL_ID),
                    (17, "GOAL_READY", FP011_GOAL_ID, None),
                ],
            )
            self.assertEqual(
                suffix[0]["produced_by_goal_id"],
                FP010_GOAL_ID,
            )
            self.assertEqual(
                suffix[0]["produced_binding_roles"],
                ["IMPLEMENTATION_BACKLOG", "IMPLEMENTATION_GAP"],
            )
            self.assertEqual(
                suffix[0]["changed_binding_roles"],
                [
                    "IMPLEMENTATION_BACKLOG",
                    "IMPLEMENTATION_GAP",
                    f"WORK_ITEM_COMPLETION::{FP010_GOAL_ID}",
                ],
            )
            self.assertEqual(
                suffix[1]["canonical_update_event_sha256"],
                suffix[0]["event_sha256"],
            )
            self.assertEqual(
                suffix[2]["materialized_goal_path"],
                FP011_GOAL_PATH,
            )
            self.assertEqual(
                suffix[2]["predecessor_goal_id"],
                FP010_GOAL_ID,
            )
            self.assertEqual(
                (
                    suffix[2]["from_status"],
                    suffix[2]["to_status"],
                    suffix[2]["status_changes"],
                ),
                ("", "PLANNED", {FP011_GOAL_ID: "PLANNED"}),
            )
            self.assertEqual(
                (
                    suffix[3]["from_status"],
                    suffix[3]["to_status"],
                    suffix[3]["status_changes"],
                ),
                (
                    "PLANNED",
                    "READY",
                    {FP011_GOAL_ID: "READY"},
                ),
            )
            self.assertEqual(
                suffix[3]["readiness_basis"],
                {
                    "dependency_completion_events": [
                        {
                            "goal_id": FP010_GOAL_ID,
                            "event_sha256": suffix[1]["event_sha256"],
                        }
                    ]
                },
            )

        def test_fp010_canonical_update_rejects_out_of_order_fp011_events(
            self,
        ) -> None:
            expected_error = [
                "canonical binding producer must complete before any other event"
            ]
            for event_type, subject_goal_id in (
                ("GOAL_MATERIALIZED", None),
                ("GOAL_READY", FP011_GOAL_ID),
                ("GOAL_COMPLETED", FP011_GOAL_ID),
            ):
                with self.subTest(
                    event_type=event_type,
                    subject_goal_id=subject_goal_id,
                ):
                    self.assertEqual(
                        graph.pending_canonical_transaction_order_errors(
                            pending_producer_completion_goal_id=(
                                FP010_GOAL_ID
                            ),
                            pending_reopen_goal_ids=set(),
                            event_type=event_type,
                            subject_goal_id=subject_goal_id,
                        ),
                        expected_error,
                    )
            self.assertEqual(
                graph.pending_canonical_transaction_order_errors(
                    pending_producer_completion_goal_id=FP010_GOAL_ID,
                    pending_reopen_goal_ids=set(),
                    event_type="GOAL_COMPLETED",
                    subject_goal_id=FP010_GOAL_ID,
                ),
                [],
            )

        def test_unbound_fp006_result_files_do_not_change_graph_discovery(
            self,
        ) -> None:
            with self.fixture_root() as root:
                before_paths = graph.discover_package_paths(root)
                before_errors = graph.validate(
                    root,
                    check_continuation=False,
                )
                self.write_json(
                    root
                    / "docs/control/execution/goal-results/"
                    "WS-GOAL-EPIC-02-FP-006-R001/"
                    "completion-receipt.json",
                    {
                        "document_id": "UNBOUND-FP006-RESULT-FIXTURE",
                        "status": "ACCEPTED",
                    },
                )

                after_paths = graph.discover_package_paths(root)
                after_errors = graph.validate(
                    root,
                    check_continuation=False,
                )

            self.assertEqual(before_paths, after_paths)
            self.assertEqual(before_errors, [])
            self.assertEqual(after_errors, [])

        def test_staged_fp005_completion_preserves_newer_live_bytes(
            self,
        ) -> None:
            with self.fixture_root() as root:
                checkpoint = graph.load_json(
                    root / graph.CHECKPOINT_RELATIVE
                )
                deferred_errors = set(
                    self.archived_changed_artifact_errors(root)
                )
                self.stage_fp005_completion_witness(root, checkpoint)

                errors = set(
                    self.deferred_archive_replay_errors(
                        root,
                        checkpoint,
                    )
                )

            self.assertTrue(errors)
            self.assertLess(errors, deferred_errors)

        def test_staged_fp005_completion_requires_event_and_receipt(
            self,
        ) -> None:
            for missing in ("event", "receipt"):
                with self.subTest(missing=missing), self.fixture_root() as root:
                    checkpoint = graph.load_json(
                        root / graph.CHECKPOINT_RELATIVE
                    )
                    event, binding = self.stage_fp005_completion_witness(
                        root,
                        checkpoint,
                        append_event=missing != "event",
                    )
                    if missing == "receipt":
                        (root / binding["path"]).unlink()

                    deferred_errors = set(
                        self.archived_changed_artifact_errors(root)
                    )
                    errors = self.deferred_archive_replay_errors(
                        root,
                        checkpoint,
                    )

                if missing == "event":
                    self.assertEqual(set(errors), deferred_errors)
                else:
                    self.assertTrue(
                        deferred_errors.issubset(errors),
                        (missing, event, errors),
                    )
                    self.assertTrue(
                        any(
                            error.startswith(
                                "archived v2.2 deferred replay:"
                            )
                            for error in errors
                        ),
                        errors,
                    )

        def test_staged_fp005_completion_rejects_broken_before_hash(
            self,
        ) -> None:
            with self.fixture_root() as root:
                checkpoint = graph.load_json(
                    root / graph.CHECKPOINT_RELATIVE
                )
                event, binding = self.stage_fp005_completion_witness(
                    root,
                    checkpoint,
                )
                valid_errors = set(
                    self.deferred_archive_replay_errors(
                        root,
                        checkpoint,
                    )
                )
                deferred_paths = {
                    self.archived_changed_artifact_path(
                        root,
                        error,
                    )
                    for error in self.archived_changed_artifact_errors(
                        root
                    )
                }
                receipt_path = root / binding["path"]
                receipt = graph.load_json(receipt_path)
                implementation_item = next(
                    item
                    for item in receipt["result_evidence"]
                    if item["kind"] == "IMPLEMENTATION_RECORD"
                )
                implementation_path = (
                    root / implementation_item["path"]
                )
                implementation = graph.load_json(
                    implementation_path
                )
                changed = next(
                    item
                    for item in implementation["changed_artifacts"]
                    if (
                        item.get("before_sha256") is not None
                        and item["path"] in deferred_paths
                        and graph.sha256_file(root / item["path"])
                        == item["after_sha256"]
                    )
                )
                changed["before_sha256"] = "f" * 64
                self.write_json(implementation_path, implementation)
                implementation_item["sha256"] = graph.sha256_file(
                    implementation_path
                )
                self.write_json(receipt_path, receipt)
                binding["file_sha256"] = graph.sha256_file(receipt_path)
                role = next(
                    iter(event["completion_evidence_bindings"])
                )
                event["completion_evidence_bindings"][role] = self._clone(
                    binding
                )
                event["completion_receipt_binding"] = self._clone(binding)
                event["event_sha256"] = graph.event_sha256(event)

                errors = set(
                    self.deferred_archive_replay_errors(
                        root,
                        checkpoint,
                    )
                )

            self.assertLess(valid_errors, errors)

        def test_fixture_projects_immutable_prepared_phase(self) -> None:
            with self.fixture_root() as root:
                checkpoint = graph.load_json(
                    root / graph.CHECKPOINT_RELATIVE
                )
                state = checkpoint["goal_execution"]
                prepared = state["transition_history"][0]

                self.assertEqual(state["schema_version"], "2.1")
                self.assertEqual(len(state["transition_history"]), 1)
                self.assertEqual(
                    prepared["event_type"],
                    "PACKAGE_PREPARED",
                )
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
                self.assertEqual(state["managed_goal_path_count"], 23)
                self.assertEqual(state["goal_document_count"], 17)
                self.assertEqual(
                    len(state["imported_predecessor_goal_bindings"]),
                    17,
                )
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

                errors = graph.validate(
                    root,
                    check_continuation=False,
                )

            self.assertIn(
                "Goal graph event 2: PACKAGE_ACTIVATED event fields differ",
                errors,
            )

        def test_manifest_requires_exact_v21_supersession_binding(
            self,
        ) -> None:
            manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
            manifest["supersedes"]["manifest_sha256"] = "f" * 64

            errors, _ = graph.validate_superseded_v2_2(
                ROOT,
                manifest,
            )

            self.assertTrue(
                any(
                    "active v2.2 supersedes binding differs" in error
                    for error in errors
                )
            )

        def test_v21_supersession_binding_cannot_be_removed(self) -> None:
            with self.fixture_root() as root:
                checkpoint = graph.load_json(
                    root / graph.CHECKPOINT_RELATIVE
                )
                event = checkpoint["goal_execution"][
                    "transition_history"
                ][0]
                event["supersedes_event_sha256"] = ""
                self.rehash_event(checkpoint)
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
                    "does not bind the v2.2 tail" in error
                    or "lacks the active v2.2 tail binding" in error
                    for error in errors
                ),
                errors,
            )

        def test_manifest_requires_exact_bootstrap_consumed_pair(
            self,
        ) -> None:
            checkpoint = graph.load_json(
                ROOT / graph.CHECKPOINT_RELATIVE
            )
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
                    "inherited contract differs: policy_gap_contract"
                    in error
                    for error in errors
                ),
                errors,
            )

        def test_full_start_repository_state_is_the_exact_last_check(
            self,
        ) -> None:
            self.assertEqual(
                graph.CHECK_COMMAND_CONTRACT_VERSION,
                "2026-07-24.3",
            )
            self.assertEqual(
                len(graph.IMPLEMENTATION_START_GATE_CHECKS),
                19,
            )
            self.assertEqual(
                graph.IMPLEMENTATION_START_GATE_CHECK_IDS[-1],
                "REPOSITORY_STATE",
            )
            self.assertEqual(
                graph.IMPLEMENTATION_START_GATE_CHECKS[-1],
                (
                    "REPOSITORY_STATE",
                    ': "${WALKSAFE_GATE_EVENT_ID:?required}" && '
                    "python3 -B scripts/"
                    "check_walksafe_project_continuation_v2_3.py "
                    "--root . --checkpoint docs/control/"
                    "walksafe-project-continuation-checkpoint.json "
                    "--print-gate-repository-state --gate-event-id "
                    '"${WALKSAFE_GATE_EVENT_ID}"',
                ),
            )

        def test_ready_frontier_rejects_a_leaf_under_an_unavailable_ancestor(
            self,
        ) -> None:
            checkpoint = graph.load_json(
                ROOT / graph.CHECKPOINT_RELATIVE
            )
            manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
            nodes = self.load_nodes(
                ROOT,
                manifest,
                include_dynamic=True,
            )
            backlog = self.load_binding_json(
                checkpoint,
                "IMPLEMENTATION_BACKLOG",
            )
            state = checkpoint["goal_execution"]
            focus_id = "WS-GOAL-EPIC-02-FP-006-R001"

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
                        blockers["WS-GOAL-EPIC-02"] = [
                            {"blocker_id": "TEST"}
                        ]
                    calculated = graph.ready_frontier(
                        nodes,
                        statuses,
                        state[
                            "materialized_child_goal_ids_by_parent"
                        ],
                        blockers,
                        backlog,
                    )
                    self.assertNotIn(focus_id, calculated)

        def test_canonical_next_action_wins_without_serializing_other_branches(
            self,
        ) -> None:
            checkpoint = graph.load_json(
                ROOT / graph.CHECKPOINT_RELATIVE
            )
            manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
            nodes = self.load_nodes(
                ROOT,
                manifest,
                include_dynamic=True,
            )
            backlog = self.load_binding_json(
                checkpoint,
                "IMPLEMENTATION_BACKLOG",
            )
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
                    "WS-GOAL-EPIC-02-FP-010-R001",
                    "WS-GOAL-EPIC-03",
                    "WS-GOAL-EPIC-12",
                ],
            )

        def test_policy_gap_work_item_binds_exactly_one_pair(
            self,
        ) -> None:
            checkpoint = graph.load_json(
                ROOT / graph.CHECKPOINT_RELATIVE
            )
            manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
            nodes = self.load_nodes(
                ROOT,
                manifest,
                include_dynamic=True,
            )
            node = nodes[checkpoint["goal_execution"]["focus_goal_id"]]
            self.assertEqual(node["source_policy_ids"], ["FP-010"])
            self.assertEqual(node["gap_ids"], ["GAP-019"])

        def test_manifest_and_goal_metadata_drift_is_rejected_after_rehash(
            self,
        ) -> None:
            with self.fixture_root() as root:
                relative = self._imported_workstream(
                    "epic-03-account-admin-security.md"
                )
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

                errors = graph.validate(
                    root,
                    check_continuation=False,
                )

            self.assertTrue(
                any(
                    "metadata differs from manifest" in error
                    for error in errors
                ),
                errors,
            )

        def test_fixed_stage_field_is_rejected_after_rehash(self) -> None:
            with self.fixture_root() as root:
                relative = self._imported_workstream(
                    "epic-03-account-admin-security.md"
                )
                path = root / relative
                path.write_text(
                    path.read_text(encoding="utf-8").replace(
                        (
                            'parent_goal_id = '
                            '"WS-GOAL-WALKSAFE-COMPLETION-GRAPH-V2"'
                        ),
                        (
                            'phase_id = "A"\n'
                            'parent_goal_id = '
                            '"WS-GOAL-WALKSAFE-COMPLETION-GRAPH-V2"'
                        ),
                        1,
                    ),
                    encoding="utf-8",
                )
                self.refresh_integrity(root)

                errors = graph.validate(
                    root,
                    check_continuation=False,
                )

            self.assertTrue(
                any(
                    "fixed-stage fields are prohibited" in error
                    for error in errors
                ),
                errors,
            )

        def test_focus_with_unmet_dependency_is_rejected(self) -> None:
            with self.fixture_root() as root:
                checkpoint = graph.load_json(
                    root / graph.CHECKPOINT_RELATIVE
                )
                state = checkpoint["goal_execution"]
                state["focus_goal_id"] = "WS-GOAL-EPIC-04"
                state["focus_goal_path"] = self._imported_workstream(
                    "epic-04-navigation-arrival-deviation.md"
                )
                state["focus_work_item_id"] = ""
                event = state["transition_history"][0]
                event["runtime_after"]["focus_goal_id"] = state[
                    "focus_goal_id"
                ]
                event["runtime_after"]["focus_goal_path"] = state[
                    "focus_goal_path"
                ]
                event["runtime_after"]["focus_work_item_id"] = ""
                self.rehash_event(checkpoint)
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
                    "focus is not the deterministic ready selection"
                    in error
                    for error in errors
                ),
                errors,
            )

        def test_policy_duplication_is_rejected_after_integrity_refresh(
            self,
        ) -> None:
            with self.fixture_root() as root:
                relative = self._imported_workstream(
                    "epic-03-account-admin-security.md"
                )
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

                errors = graph.validate(
                    root,
                    check_continuation=False,
                )

            self.assertTrue(
                any("ordered policies differ" in error for error in errors),
                errors,
            )
            self.assertTrue(
                any("not covered exactly once" in error for error in errors),
                errors,
            )

        def test_completed_workstream_reopens_without_rewriting_its_completion(
            self,
        ) -> None:
            checkpoint = graph.load_json(
                ROOT / graph.CHECKPOINT_RELATIVE
            )
            state = checkpoint["goal_execution"]
            manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
            nodes = self.load_nodes(
                ROOT,
                manifest,
                include_dynamic=True,
            )
            epic_id = "WS-GOAL-EPIC-01"
            imported_completion_hash = state[
                "imported_predecessor_goal_bindings"
            ][epic_id]["completion_event_sha256"]

            directly_affected = graph.changed_binding_affected_goals(
                changed_roles={"IMPLEMENTATION_GAP"},
                changed_subject_ids_by_role={
                    "IMPLEMENTATION_GAP": ["FP-001", "GAP-010"],
                },
                nodes=nodes,
                statuses=state["status_by_goal"],
                completion_bindings_by_goal={},
                latest_start_event_by_goal={},
            )
            closure = graph.expand_affected_goal_dependency_closure(
                nodes=nodes,
                statuses=state["status_by_goal"],
                directly_affected_goal_roles=directly_affected,
            )

            self.assertIn(epic_id, directly_affected)
            self.assertIn("IMPLEMENTATION_GAP", directly_affected[epic_id])
            self.assertIn(epic_id, closure)
            self.assertEqual(
                graph.workstream_reopen_target_status(closure[epic_id]),
                "READY",
            )
            self.assertEqual(
                state["imported_predecessor_goal_bindings"][epic_id][
                    "completion_event_sha256"
                ],
                imported_completion_hash,
            )

        def test_protected_goal_change_is_rejected(self) -> None:
            with self.fixture_root() as root:
                path = root / self._imported_workstream(
                    "epic-05-object-detection-safety.md"
                )
                path.write_text(
                    path.read_text(encoding="utf-8") + "\n변조\n",
                    encoding="utf-8",
                )

                errors = graph.validate(
                    root,
                    check_continuation=False,
                )

            self.assertTrue(
                any(
                    "imported Goal binding differs" in error
                    or "archived v2.2: protected file content changed"
                    in error
                    for error in errors
                ),
                errors,
            )

        def test_missing_goal_is_rejected_even_if_runtime_path_list_is_edited(
            self,
        ) -> None:
            with self.fixture_root() as root:
                relative = self._imported_workstream(
                    "epic-05-object-detection-safety.md"
                )
                (root / relative).unlink()
                checkpoint = graph.load_json(
                    root / graph.CHECKPOINT_RELATIVE
                )
                state = checkpoint["goal_execution"]
                state["managed_goal_paths"].remove(relative)
                state["goal_document_paths"].remove(relative)
                state["managed_goal_path_count"] -= 1
                state["goal_document_count"] -= 1
                state["status_by_goal"].pop("WS-GOAL-EPIC-05")
                state["imported_predecessor_goal_bindings"].pop(
                    "WS-GOAL-EPIC-05"
                )
                state["transition_history"][0]["status_changes"].pop(
                    "WS-GOAL-EPIC-05"
                )
                self.rehash_event(checkpoint)
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
                    "archived v2.2 Goal path is missing" in error
                    or "imported predecessor Goal ID set differs" in error
                    or "static Goal document set is incomplete" in error
                    for error in errors
                ),
                errors,
            )

        def test_superseded_v21_archived_event_cannot_be_reanchored(
            self,
        ) -> None:
            with self.fixture_root() as root:
                path = root / (
                    graph.V22_PACKAGE_RELATIVE
                    / "superseded-v2.1.0-package-prepared-event.json"
                )
                event = graph.load_json(path)
                event["event_id"] = "FORGED-V2-1-PACKAGE-PREPARED"
                event["event_sha256"] = graph.event_sha256(event)
                self.write_json(path, event)

                errors = frozen_checker.validate_superseded_v2_1(root)

            self.assertTrue(
                any(
                    "v2.1 preparation event stored hash differs" in error
                    for error in errors
                ),
                errors,
            )
            self.assertTrue(
                any(
                    "v2.1 preparation event canonical hash differs"
                    in error
                    for error in errors
                ),
                errors,
            )

        def test_superseded_v21_package_is_valid(self) -> None:
            manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
            errors, _ = graph.validate_superseded_v2_2(
                ROOT,
                manifest,
            )
            self.assertEqual(errors, [])

        def test_v21_supersession_record_tampering_is_rejected(
            self,
        ) -> None:
            with self.fixture_root() as root:
                path = root / (
                    graph.V22_PACKAGE_RELATIVE
                    / "preactivation-supersession-record-v2.1.0.json"
                )
                record = graph.load_json(path)
                record["successor_plan_version"] = "2.2.1-forged"
                self.write_json(path, record)

                errors = frozen_checker.validate_superseded_v2_1(root)

            self.assertTrue(
                any(
                    "v2.1 supersession record successor binding differs"
                    in error
                    for error in errors
                ),
                errors,
            )

        def test_v2_3_preparation_has_exact_mixed_ownership_layout(
            self,
        ) -> None:
            checkpoint = graph.load_json(
                ROOT / graph.CHECKPOINT_RELATIVE
            )
            state = checkpoint["goal_execution"]
            manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
            fp006_goal_path = (
                "docs/control/goals/walksafe-completion-graph-v2-3/"
                "work-items/epic-02/"
                "epic-02-fp006-solo-walk-phone-mounting-r001.md"
            )
            fp010_goal_path = (
                "docs/control/goals/walksafe-completion-graph-v2-3/"
                "work-items/epic-02/"
                "epic-02-fp010-first-run-registration-r001.md"
            )
            expected_successor_paths_by_goal = {
                "WS-GOAL-EPIC-02-FP-006-R001": fp006_goal_path,
                FP010_GOAL_ID: fp010_goal_path,
            }
            inventory = state["dynamic_goal_inventory"]
            if FP011_GOAL_ID in inventory:
                expected_successor_paths_by_goal[FP011_GOAL_ID] = (
                    FP011_GOAL_PATH
                )
            successor_inventory = {
                goal_id: record
                for goal_id, record in inventory.items()
                if record["path"].startswith(
                    f"{graph.PACKAGE_RELATIVE.as_posix()}/work-items/"
                )
            }
            successor_goal_paths = set(
                expected_successor_paths_by_goal.values()
            )

            self.assertEqual(
                set(graph.native_package_paths(ROOT)),
                graph.EXPECTED_V23_NATIVE_PATHS | successor_goal_paths,
            )
            self.assertEqual(
                {
                    goal_id: record["path"]
                    for goal_id, record in successor_inventory.items()
                },
                expected_successor_paths_by_goal,
            )
            self.assertEqual(
                {record["path"] for record in manifest["protected_files"]},
                graph.EXPECTED_V23_PROTECTED_PATHS,
            )
            self.assertEqual(
                set(graph.discover_package_paths(ROOT)),
                (
                    graph.EXPECTED_V23_NATIVE_PATHS
                    | successor_goal_paths
                    | {
                        record["path"]
                        for record in state[
                            "imported_predecessor_goal_bindings"
                        ].values()
                    }
                ),
            )
            self.assertEqual(
                state["managed_goal_path_count"],
                graph.EXPECTED_MANAGED_GOAL_PATH_COUNT
                + len(successor_goal_paths),
            )
            self.assertEqual(
                state["goal_document_count"],
                graph.EXPECTED_IMPORTED_GOAL_COUNT
                + len(successor_goal_paths),
            )
            if FP011_GOAL_ID in inventory:
                fp011 = inventory[FP011_GOAL_ID]
                self.assertEqual(
                    {
                        "path": fp011["path"],
                        "goal_kind": fp011["goal_kind"],
                        "work_item_type": fp011["work_item_type"],
                        "parent_goal_id": fp011["parent_goal_id"],
                        "materialized_from_role": fp011[
                            "materialized_from_role"
                        ],
                        "predecessor_goal_id": fp011[
                            "predecessor_goal_id"
                        ],
                    },
                    {
                        "path": FP011_GOAL_PATH,
                        "goal_kind": "WORK_ITEM",
                        "work_item_type": "POLICY_GAP_WORK",
                        "parent_goal_id": "WS-GOAL-EPIC-02",
                        "materialized_from_role": "IMPLEMENTATION_BACKLOG",
                        "predecessor_goal_id": FP010_GOAL_ID,
                    },
                )

        def test_v2_3_import_projection_is_exactly_the_active_v2_2_state(
            self,
        ) -> None:
            checkpoint = graph.load_json(
                ROOT / graph.CHECKPOINT_RELATIVE
            )
            state = checkpoint["goal_execution"]
            archive_errors, archived = (
                graph._load_archived_v22_checkpoint(ROOT)
            )
            projection_errors, expected = (
                graph._archived_v22_goal_projection(ROOT, archived)
            )

            self.assertEqual(archive_errors, [])
            self.assertEqual(projection_errors, [])
            self.assertEqual(
                state["imported_predecessor_goal_bindings"],
                expected,
            )
            self.assertEqual(len(expected), 17)
            self.assertTrue(
                all(
                    record["source_package_id"]
                    == graph.EXPECTED_V22_PACKAGE_ID
                    for record in expected.values()
                )
            )

        def test_v2_3_initial_event_is_independent_and_not_activated(
            self,
        ) -> None:
            checkpoint = graph.load_json(
                ROOT / graph.CHECKPOINT_RELATIVE
            )
            state = checkpoint["goal_execution"]
            history = state["transition_history"]
            event = history[0]

            self.assertEqual(event["sequence"], 1)
            self.assertEqual(event["event_type"], "PACKAGE_PREPARED")
            self.assertEqual(event["previous_event_sha256"], "")
            self.assertEqual(
                event["event_sha256"],
                graph.EXPECTED_INITIAL_EVENT_SHA256,
            )
            self.assertEqual(
                event["supersedes_event_sha256"],
                graph.EXPECTED_V22_TRANSITION_HISTORY_ANCHOR_SHA256,
            )
            archive_errors, archived = (
                graph._load_archived_v22_checkpoint(ROOT)
            )
            self.assertEqual(archive_errors, [])
            self.assertEqual(
                event["active_predecessor_import"],
                {
                    "source_package_id": graph.EXPECTED_V22_PACKAGE_ID,
                    "source_plan_version": graph.EXPECTED_V22_PLAN_VERSION,
                    "source_manifest_path": (
                        graph.V22_MANIFEST_RELATIVE.as_posix()
                    ),
                    "source_manifest_sha256": (
                        graph.EXPECTED_V22_MANIFEST_SHA256
                    ),
                    "source_checkpoint_path": (
                        graph.V22_ARCHIVED_CHECKPOINT_RELATIVE.as_posix()
                    ),
                    "source_checkpoint_raw_sha256": (
                        graph.EXPECTED_V22_ARCHIVED_CHECKPOINT_RAW_SHA256
                    ),
                    "source_transition_event_count": (
                        graph.EXPECTED_V22_TRANSITION_EVENT_COUNT
                    ),
                    "source_transition_history_anchor_sha256": (
                        graph.EXPECTED_V22_TRANSITION_HISTORY_ANCHOR_SHA256
                    ),
                    "imported_predecessor_goal_bindings_sha256": (
                        graph.canonical_json_sha256(
                            state["imported_predecessor_goal_bindings"]
                        )
                    ),
                    "canonical_binding_snapshot_sha256": (
                        graph.canonical_json_sha256(
                            graph.canonical_binding_snapshot(
                                graph.canonical_binding_map(archived)
                            )
                        )
                    ),
                },
            )
            if state["activation_status"] == "READY_NOT_ACTIVATED":
                self.assertEqual(len(history), 1)
                self.assertEqual(
                    state["focus_goal_id"],
                    "WS-GOAL-EPIC-02-FP-005-R001",
                )
                self.assertEqual(
                    state["ready_frontier_goal_ids"],
                    [
                        "WS-GOAL-EPIC-02-FP-005-R001",
                        "WS-GOAL-EPIC-03",
                        "WS-GOAL-EPIC-12",
                    ],
                )
                self.assertEqual(
                    state["package_status"],
                    "PREPARED_NOT_ACTIVATED",
                )
                self.assertNotIn(
                    "GOAL_STARTED",
                    {item["event_type"] for item in history},
                )
                self.assertNotIn(
                    "IN_PROGRESS",
                    state["status_by_goal"].values(),
                )

        def test_v2_3_manifest_declares_projection_only_predecessor_import(
            self,
        ) -> None:
            manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
            self.assertEqual(manifest["schema_version"], "2.1")
            self.assertEqual(
                manifest["imported_predecessor_goal_contract"],
                {
                    "source_package_id": graph.EXPECTED_V22_PACKAGE_ID,
                    "source_plan_version": graph.EXPECTED_V22_PLAN_VERSION,
                    "source_checkpoint_path": (
                        graph.V22_ARCHIVED_CHECKPOINT_RELATIVE.as_posix()
                    ),
                    "runtime_binding_field": (
                        "imported_predecessor_goal_bindings"
                    ),
                    "expected_goal_count": 17,
                    "goal_paths_remain_in_predecessor_package": True,
                    "goal_bytes_must_match_archived_projection": True,
                    "materialization_and_completion_lineage_is_imported": (
                        True
                    ),
                },
            )
            transition = manifest["transition_contract"]
            self.assertEqual(
                transition["predecessor_runtime_import_event"],
                "PACKAGE_PREPARED",
            )
            self.assertIs(
                transition[
                    "predecessor_runtime_import_is_projection_only"
                ],
                True,
            )

        def test_v2_3_import_binding_tampering_is_rejected(self) -> None:
            with self.fixture_root() as root:
                checkpoint = graph.load_json(
                    root / graph.CHECKPOINT_RELATIVE
                )
                binding = checkpoint["goal_execution"][
                    "imported_predecessor_goal_bindings"
                ]["WS-GOAL-EPIC-02-FP-005-R001"]
                binding["status"] = "PLANNED"
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
                    "imported Goal binding differs from v2.2 archive"
                    in error
                    for error in errors
                ),
                errors,
            )

        def test_v2_3_archived_active_checkpoint_tampering_is_rejected(
            self,
        ) -> None:
            with self.fixture_root() as root:
                path = root / graph.V22_ARCHIVED_CHECKPOINT_RELATIVE
                path.write_bytes(path.read_bytes() + b"\n")

                errors = graph.validate(
                    root,
                    check_continuation=False,
                )

            self.assertTrue(
                any(
                    "archived active v2.2 checkpoint raw SHA-256 differs"
                    in error
                    for error in errors
                ),
                errors,
            )

    def rejects_frozen_v2_2_prestart_repair(self) -> None:
        with self.fixture_root() as root:
            checkpoint = graph.load_json(
                root / graph.CHECKPOINT_RELATIVE
            )
            self.commit_prestart_control_repair(root, checkpoint)
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
                "must not replay the frozen v2.2 prestart control repair"
                in error
                for error in errors
            ),
            errors,
        )

    v2_2_only_prestart_tests = (
        "test_prestart_control_repair_commits_valid_noop",
        "test_prestart_control_repair_uses_split_semantic_regression_contract",
        "test_prestart_control_repair_archive_tampering_is_rejected",
        "test_prestart_control_repair_rejects_product_path",
        "test_prestart_control_repair_cannot_change_goal_status",
        "test_prestart_control_repair_cannot_repeat",
        "test_prestart_control_repair_is_rejected_after_execution_start",
        "test_non_tail_prestart_repair_after_hash_tampering_is_rejected",
        "test_non_tail_prestart_repair_after_aggregate_must_match_witness",
        (
            "test_non_tail_prestart_repair_witness_cannot_"
            "reanchor_activation_before_root"
        ),
        "test_first_start_gate_cannot_predate_prestart_repair",
        "test_prestart_control_repair_rejects_unexpected_evidence_entries",
        "test_prestart_control_repair_rejects_unreadable_receipt",
        "test_prestart_control_repair_rejects_deep_receipt_json",
        "test_prestart_control_repair_rejects_unsafe_event_id",
    )
    for test_name in v2_2_only_prestart_tests:
        setattr(
            WalkSafeGoalGraphV23Test,
            test_name,
            rejects_frozen_v2_2_prestart_repair,
        )

    WalkSafeGoalGraphV23Test.__name__ = "WalkSafeGoalGraphV23Test"
    WalkSafeGoalGraphV23Test.__qualname__ = "WalkSafeGoalGraphV23Test"
    WalkSafeGoalGraphV23Test.__module__ = __name__
    return WalkSafeGoalGraphV23Test


WalkSafeGoalGraphV23Test = _load_v2_3_test_case()

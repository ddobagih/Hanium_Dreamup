from __future__ import annotations

import base64
import copy
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
from typing import Callable
import unittest
from unittest import mock


sys.path.insert(0, str(Path(__file__).resolve().parent))
import walksafe_fp008_builder_test_support as support

from scripts import apply_walksafe_fp008_goal_completed_seq49_50_20260809 as apply
from scripts import build_walksafe_fp008_admin_review_delivery_trace_20260803 as trace
from scripts import build_walksafe_fp008_artifact_trace_successor_20260803 as artifacts
from scripts import build_walksafe_fp008_strict_review_gate_20260803 as review
from scripts import check_walksafe_project_continuation_v2_4 as contract


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
SOURCE_CHECKPOINT_FIXTURE = (
    REPOSITORY_ROOT
    / "tests/fixtures/"
    "walksafe-project-continuation-checkpoint-seq48-20260809.json.gz.b64"
)


def _sealed_source_checkpoint_bytes() -> bytes:
    encoded = b"".join(SOURCE_CHECKPOINT_FIXTURE.read_bytes().split())
    compressed = base64.b64decode(encoded, validate=True)
    return gzip.decompress(compressed)


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _copy_repository_file(root: Path, relative: Path) -> None:
    target = root / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(REPOSITORY_ROOT / relative, target)


def _replace_bytes(path: Path, content: bytes) -> None:
    replacement = path.with_name(f".{path.name}.fp008-test-replacement")
    replacement.write_bytes(content)
    replacement.chmod(path.stat().st_mode & 0o777)
    os.replace(replacement, path)


class WalkSafeFp008GoalCompletedSeq4950Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.fixture_temporary = tempfile.TemporaryDirectory()
        cls.fixture_root = Path(cls.fixture_temporary.name) / "repository"
        cls.fixture_root.mkdir()

        source_bytes = _sealed_source_checkpoint_bytes()
        if (
            len(source_bytes) != apply.SOURCE_CHECKPOINT_BYTE_COUNT
            or apply.sha256_bytes(source_bytes)
            != apply.SOURCE_CHECKPOINT_RAW_SHA256
        ):
            raise RuntimeError("sealed seq48 checkpoint source differs")
        source = json.loads(source_bytes)
        fixture_checkpoint = cls.fixture_root / apply.CHECKPOINT_RELATIVE
        fixture_checkpoint.parent.mkdir(parents=True, exist_ok=True)
        fixture_checkpoint.write_bytes(source_bytes)
        fixture_checkpoint.chmod(0o600)

        for path_text in source["working_tree_snapshot"]["managed_changed_paths"]:
            _copy_repository_file(cls.fixture_root, Path(path_text))

        support.materialize_complete_fixture(cls.fixture_root)
        for gate_binding in (apply.START_GATE_BINDING, apply.RESUME_GATE_BINDING):
            receipt = json.loads(
                (REPOSITORY_ROOT / Path(gate_binding["path"])).read_bytes()
            )
            closure_paths = [
                Path(gate_binding["path"]),
                Path(receipt["implementation_start_gate_contract_binding"]["path"]),
                *(Path(row["path"]) for row in receipt["runtime_bindings"]),
                *(Path(row["output_path"]) for row in receipt["check_runs"]),
            ]
            for relative in closure_paths:
                _copy_repository_file(cls.fixture_root, relative)

        for relative in apply.SEQUENCE_AUTHORITY_PATHS:
            _copy_repository_file(cls.fixture_root, relative)

        context = review.prepare_review_context(cls.fixture_root)
        attestation = review.build_attestation(
            context,
            "2026-08-09T12:10:00+09:00",
        )
        attestation_raw = review.json_text(attestation).encode("utf-8")
        support.write(trace.REVIEW_ATTESTATION_REL, attestation_raw, cls.fixture_root)
        for relative, content in review.build_post_review_outputs(
            context,
            attestation,
            attestation_raw,
        ).items():
            support.write(relative, content.encode("utf-8"), cls.fixture_root)

        cls.fixture_pins = {
            spec.role: _sha256(cls.fixture_root / spec.path)
            for spec in apply.ARTIFACT_SPECS
        }
        cls.fixture_output_pins = {
            relative: _sha256(cls.fixture_root / relative)
            for relative in apply.ADDITIONAL_OUTPUT_PIN_PATHS
        }

    @classmethod
    def tearDownClass(cls) -> None:
        cls.fixture_temporary.cleanup()

    def setUp(self) -> None:
        self.root = self.fixture_root
        self.checkpoint_path = self.root / apply.CHECKPOINT_RELATIVE
        self.source = json.loads(self.checkpoint_path.read_bytes())
        self.pins = dict(self.fixture_pins)
        self.output_pins = dict(self.fixture_output_pins)
        self.calls: list[tuple[str, Path]] = []

    def _switch_to_clone(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name) / "repository"
        shutil.copytree(self.fixture_root, self.root)
        self.checkpoint_path = self.root / apply.CHECKPOINT_RELATIVE
        self.source = json.loads(self.checkpoint_path.read_bytes())

    def _continuation_checker(self, _root: Path, checkpoint: Path) -> list[str]:
        self.calls.append(("continuation", checkpoint))
        return []

    def _goal_graph_checker(self, _root: Path, checkpoint: Path) -> list[str]:
        self.calls.append(("goal_graph", checkpoint))
        return []

    @staticmethod
    def _runtime_deriver(
        _root: Path,
        checkpoint: dict[str, object],
        ready_frontier: list[str],
    ) -> tuple[dict[str, object], dict[str, object]]:
        bindings = {
            row["role"]: row
            for row in checkpoint["canonical_bindings"]
            if isinstance(row, dict)
        }
        state = checkpoint["goal_execution"]
        queue = {
            "schema_version": "test.v1",
            "source_binding": {
                key: bindings["ARTIFACT_REGISTER"][key]
                for key in ("role", "document_id", "path", "file_sha256")
            },
            "status": state["status_by_goal"][apply.GOAL_ID],
        }
        boundary = {
            "schema_version": "test.v1",
            "internal_runnable_goal_ids": copy.deepcopy(ready_frontier),
            "internal_pending_goal_ids": (
                [apply.GOAL_ID]
                if state["status_by_goal"][apply.GOAL_ID]
                != "COMPLETE_AT_TARGET"
                else []
            ),
        }
        return queue, boundary

    def _prepare(
        self,
        *,
        pins: dict[str, str] | None = None,
        output_pins: dict[Path, str] | None = None,
        continuation_checker: Callable[[Path, Path], list[str]] | None = None,
        goal_graph_checker: Callable[[Path, Path], list[str]] | None = None,
    ) -> apply.PreparedProjection:
        return apply.prepare_projection(
            self.root,
            artifact_sha256_by_role=self.pins if pins is None else pins,
            output_sha256_by_path=(
                self.output_pins if output_pins is None else output_pins
            ),
            runtime_deriver=self._runtime_deriver,
            continuation_checker=(
                self._continuation_checker
                if continuation_checker is None
                else continuation_checker
            ),
            goal_graph_checker=(
                self._goal_graph_checker
                if goal_graph_checker is None
                else goal_graph_checker
            ),
        )

    def _write_arguments(self) -> dict[str, object]:
        return {
            "runtime_deriver": self._runtime_deriver,
            "continuation_checker": self._continuation_checker,
            "goal_graph_checker": self._goal_graph_checker,
        }

    def test_source_snapshot_and_both_gate_closures_are_exact(self) -> None:
        source_bytes = self.checkpoint_path.read_bytes()
        apply.require_exact_source(source_bytes, copy.deepcopy(self.source))
        source_map, physical = apply._source_snapshot_authority(
            self.root,
            self.source,
        )
        source_snapshot = self.source["working_tree_snapshot"]
        self.assertEqual(len(source_map), 631)
        self.assertEqual(
            apply._snapshot_hashes_from_digests(source_map),
            (
                source_snapshot["path_set_sha256"],
                source_snapshot["content_set_sha256"],
            ),
        )
        self.assertEqual(
            physical,
            {
                apply.RESUME_GATE_REPOSITORY_STATE_PATH:
                apply.RESUME_GATE_REPOSITORY_STATE_SHA256
            },
        )

        for binding, state_path, state_sha in (
            (
                apply.START_GATE_BINDING,
                apply.START_GATE_REPOSITORY_STATE_PATH,
                apply.START_GATE_REPOSITORY_STATE_SHA256,
            ),
            (
                apply.RESUME_GATE_BINDING,
                apply.RESUME_GATE_REPOSITORY_STATE_PATH,
                apply.RESUME_GATE_REPOSITORY_STATE_SHA256,
            ),
        ):
            closure = apply._collect_gate_closure(
                self.root,
                binding,
                repository_state_path=state_path,
                repository_state_sha256=state_sha,
                label="test gate",
            )
            self.assertEqual(len(closure), 16)
            self.assertEqual(closure[Path(binding["path"])], binding["file_sha256"])
            self.assertEqual(closure[state_path], state_sha)

    def test_production_pins_bind_current_reviewed_closure_and_fail_closed(self) -> None:
        self.assertEqual(
            set(apply.PINNED_PRODUCTION_SHA256_BY_ROLE),
            set(apply.SPEC_BY_ROLE),
        )
        self.assertEqual(
            apply.PINNED_PRODUCTION_SHA256_BY_ROLE,
            {
                spec.role: _sha256(REPOSITORY_ROOT / spec.path)
                for spec in apply.ARTIFACT_SPECS
            },
        )
        self.assertEqual(
            apply.PINNED_PRODUCTION_SHA256_BY_PATH,
            {
                relative: _sha256(REPOSITORY_ROOT / relative)
                for relative in apply.ADDITIONAL_OUTPUT_PIN_PATHS
            },
        )
        self.assertEqual(
            apply.CONTROLLED_CHECKER_DELTA_SHA256_BY_PATH,
            {
                relative: _sha256(REPOSITORY_ROOT / relative)
                for relative in apply.CONTROLLED_CHECKER_DELTA_SHA256_BY_PATH
            },
        )
        before = self.checkpoint_path.read_bytes()
        for mode in ("--preflight", "--write"):
            with mock.patch("builtins.print") as printer:
                self.assertEqual(
                    apply.main([mode, "--root", str(self.root)]),
                    1,
                )
            self.assertIn("differs", printer.call_args.args[0])
            self.assertNotIn("PENDING", printer.call_args.args[0])
            self.assertEqual(self.checkpoint_path.read_bytes(), before)
        self.assertFalse(
            list(
                self.checkpoint_path.parent.glob(
                    ".walksafe-fp008-seq49-50-preflight.*.json"
                )
            )
        )

    def test_correction_rebase_production_cohort_pins_are_frozen(self) -> None:
        self.assertEqual(
            artifacts.EXPECTED_CORRECTION_REBASE_SOURCE_BY_PATH,
            {
                artifacts.DOC05_REL: {
                    "sha256": "824939433a8ff13eafc5cf6e8b74be20ee2b8a65ede5a74cea1399b4c8a2a655",
                    "byte_length": 120665,
                },
                artifacts.RTM_REL: {
                    "sha256": "c3d42a112768afd61fe14cfa91485ab3ea21e94f719ea901433bd2d0e88a8c7e",
                    "byte_length": 1984351,
                },
                artifacts.DESIGN_REL: {
                    "sha256": "5541e7146de66b465428003bf6770b87422af493fec3834c3b8067b98b6e58fe",
                    "byte_length": 525929,
                },
                artifacts.IMPLEMENTATION_MANIFEST_REL: {
                    "sha256": "a4a0d6f283d8b9399a24cd68c13910db8e54e60596a03eb9d212c2b62b615e22",
                    "byte_length": 327564,
                },
                artifacts.MODULE_REGISTER_REL: {
                    "sha256": "734cb5b50029f75f0a50df01e32316517e29ed4f86bfbe6dee07856e46452bae",
                    "byte_length": 129904,
                },
                artifacts.DOC01_REL: {
                    "sha256": "920f540bbec55ebf6f68787ca19a75b89a05aae5ba3b200a533a2a15a689bf02",
                    "byte_length": 3822733,
                },
            },
        )

    def test_actual_builder_fixture_projects_exact_full_closure(self) -> None:
        before = self.checkpoint_path.read_bytes()
        prepared = self._prepare()
        self.assertEqual(self.checkpoint_path.read_bytes(), before)
        self.assertEqual(
            [label for label, _path in self.calls],
            ["continuation", "goal_graph"],
        )
        update = prepared.update_event
        completion = prepared.completion_event
        self.assertEqual((update["sequence"], completion["sequence"]), (49, 50))
        self.assertEqual(update["previous_event_sha256"], apply.SOURCE_RESUME_EVENT_SHA256)
        self.assertEqual(completion["previous_event_sha256"], update["event_sha256"])
        self.assertEqual(
            completion["canonical_update_event_sha256"],
            update["event_sha256"],
        )
        self.assertEqual(update["occurred_at"], "2026-08-09T12:10:00+09:00")
        self.assertEqual(completion["occurred_at"], "2026-08-09T12:10:01+09:00")
        self.assertEqual(len(update["canonical_binding_snapshot_after"]), 40)
        self.assertEqual(
            update["canonical_binding_snapshot_after"],
            completion["canonical_binding_snapshot_after"],
        )

        receipt = prepared.evidence.receipt
        self.assertEqual(
            tuple(Path(row["path"]) for row in receipt["output_evidence_manifest"]),
            apply.RECEIPT_MANIFEST_PATHS,
        )
        self.assertEqual(
            tuple(row["role"] for row in receipt["downstream_consumer_bindings"]),
            apply.CONSUMER_ROLE_ORDER,
        )
        source_paths = {
            Path(path)
            for path in self.source["working_tree_snapshot"]["managed_changed_paths"]
        }
        expected_final_paths = (
            source_paths
            | {Path(path) for path in trace.IMPLEMENTATION_PATHS}
            | {Path(path) for path in trace.VERIFICATION_INPUT_PATHS}
            | {spec.path for spec in apply.ARTIFACT_SPECS}
            | set(apply.ADDITIONAL_OUTPUT_PIN_PATHS)
            | set(apply.SEQUENCE_AUTHORITY_PATHS)
        )
        final_paths = set(prepared.evidence.final_managed_sha256_by_path)
        self.assertEqual(final_paths, expected_final_paths)
        self.assertEqual(len(final_paths), 712)
        self.assertTrue(prepared.evidence.authorized_delta_by_path)
        self.assertTrue(prepared.evidence.added_managed_paths)
        self.assertTrue(
            set(apply.TRANSITIVE_INPUT_SHA256_BY_PATH).issubset(
                prepared.evidence.physical_sha256_by_path
            )
        )
        self.assertTrue(
            set(apply.TRANSITIVE_INPUT_SHA256_BY_PATH).isdisjoint(final_paths)
        )

        projected = prepared.projected_checkpoint
        state = projected["goal_execution"]
        self.assertEqual(len(state["transition_history"]), 50)
        self.assertEqual(state["status_by_goal"][apply.GOAL_ID], "COMPLETE_AT_TARGET")
        self.assertEqual(state["focus_goal_id"], apply.PARENT_GOAL_ID)
        self.assertEqual(
            state["ready_frontier_goal_ids"],
            [apply.PARENT_GOAL_ID, apply.EPIC12_GOAL_ID],
        )
        self.assertEqual(state["pending_producer_completion_goal_id"], "")
        self.assertEqual(projected["current_work"]["work_item_id"], apply.NEXT_WORK_ITEM_ID)
        self.assertEqual(projected["current_work"]["next_action"], apply.NEXT_ACTION)
        self.assertEqual(projected["approved_state"], self.source["approved_state"])
        self.assertEqual(
            projected["verification_boundary"],
            self.source["verification_boundary"],
        )
        self.assertEqual(receipt["completion_boundary"], apply.EXPECTED_COMPLETION_BOUNDARY)
        snapshot = projected["working_tree_snapshot"]
        handoff = projected["session_handoff"]
        self.assertEqual(
            snapshot["managed_changed_paths"],
            sorted(path.as_posix() for path in expected_final_paths),
        )
        self.assertEqual(handoff["changed_files"], snapshot["managed_changed_paths"])
        self.assertEqual(
            handoff["source_commit_or_snapshot"]["content_set_sha256"],
            snapshot["content_set_sha256"],
        )

    def test_source_log_and_implementation_manifest_tamper_fail_closed(self) -> None:
        self._switch_to_clone()
        repository_state = self.root / apply.RESUME_GATE_REPOSITORY_STATE_PATH
        _replace_bytes(repository_state, repository_state.read_bytes() + b" ")
        with self.assertRaisesRegex(
            apply.CompletionApplyError,
            "repository-state log SHA-256 differs",
        ):
            apply._source_snapshot_authority(self.root, self.source)

        self._switch_to_clone()
        implementation_path = self.root / apply.IMPLEMENTATION_RESULT_PATH
        implementation = json.loads(implementation_path.read_bytes())
        implementation["changed_artifacts"][0]["sha256"] = "0" * 64
        implementation.pop("implementation_record_content_sha256")
        implementation["implementation_record_content_sha256"] = (
            contract.canonical_json_sha256(implementation)
        )
        _replace_bytes(implementation_path, apply.json_bytes(implementation))
        with self.assertRaisesRegex(
            apply.CompletionApplyError,
            "implementation physical path binding differs",
        ):
            apply._validate_result_path_authority(self.root)
        with self.assertRaisesRegex(
            apply.CompletionApplyError,
            "producer/review closure differs",
        ):
            self._prepare()

    def test_output_pin_and_release_credit_promotion_fail_closed(self) -> None:
        bad_output_pins = dict(self.output_pins)
        bad_output_pins[apply.GAP_MARKDOWN_PATH] = "0" * 64
        with self.assertRaisesRegex(
            apply.CompletionApplyError,
            "approved final path SHA-256 differs",
        ):
            self._prepare(output_pins=bad_output_pins)

        documents, bindings = apply._load_evidence_documents(
            self.root,
            self.pins,
        )
        promoted = copy.deepcopy(documents[apply.COMPLETION_ROLE])
        promoted["completion_boundary"]["release_status"] = "ELIGIBLE"
        with self.assertRaisesRegex(
            apply.CompletionApplyError,
            "formal/external/release boundary differs",
        ):
            apply._validate_receipt(
                self.root,
                self.source,
                promoted,
                bindings,
                self.output_pins,
            )

    def test_either_projected_checker_failure_blocks_preflight(self) -> None:
        before = self.checkpoint_path.read_bytes()

        def failure(_root: Path, _checkpoint: Path) -> list[str]:
            return ["synthetic strict failure"]

        for failing_name in ("continuation", "goal_graph"):
            with self.subTest(failing_name=failing_name):
                arguments: dict[str, object] = {}
                arguments[f"{failing_name}_checker"] = failure
                with self.assertRaisesRegex(
                    apply.CompletionApplyError,
                    f"{failing_name.replace('_', '-')} v2.4 check failed",
                ):
                    self._prepare(**arguments)
                self.assertEqual(self.checkpoint_path.read_bytes(), before)
        self.assertFalse(
            list(
                self.checkpoint_path.parent.glob(
                    ".walksafe-fp008-seq49-50-preflight.*.json"
                )
            )
        )

    def test_full_goal_graph_checker_delegates_to_full_validation(self) -> None:
        expected = ["synthetic"]
        with mock.patch.object(
            apply.goal_graph,
            "validate",
            return_value=expected,
        ) as validator:
            self.assertIs(
                apply.run_goal_graph_checker(self.root, apply.CHECKPOINT_RELATIVE),
                expected,
            )
        validator.assert_called_once_with(
            self.root,
            apply.CHECKPOINT_RELATIVE,
            contract.V23_ARCHIVE_RELATIVE,
            contract.V24_MANIFEST_RELATIVE,
            check_continuation=False,
        )

    def test_write_revalidates_and_calls_custom_writer_once(self) -> None:
        prepared = self._prepare()
        self.assertTrue(
            set(prepared.evidence.final_managed_sha256_by_path).issubset(
                prepared.evidence.physical_sha256_by_path
            )
        )
        calls: list[tuple[Path, bytes, bytes]] = []

        def writer(
            path: Path,
            content: bytes,
            *,
            expected_source: bytes,
            commit_guard: Callable[[], None],
        ) -> None:
            commit_guard()
            calls.append((path, content, expected_source))
            commit_guard()

        apply.write_projection(
            prepared,
            **self._write_arguments(),
            atomic_writer=writer,
        )
        self.assertEqual(
            calls,
            [
                (
                    self.checkpoint_path,
                    prepared.projected_checkpoint_bytes,
                    prepared.source_checkpoint_bytes,
                )
            ],
        )
        self.assertEqual(
            self.checkpoint_path.read_bytes(),
            prepared.source_checkpoint_bytes,
        )

    def test_managed_path_swap_inside_writer_is_blocked_by_commit_guard(self) -> None:
        self._switch_to_clone()
        prepared = self._prepare()
        approved = (
            {Path(path) for path in trace.IMPLEMENTATION_PATHS}
            | {Path(path) for path in trace.VERIFICATION_INPUT_PATHS}
            | {spec.path for spec in apply.ARTIFACT_SPECS}
            | set(apply.ADDITIONAL_OUTPUT_PIN_PATHS)
            | set(apply.SEQUENCE_AUTHORITY_PATHS)
        )
        source_only = next(
            relative
            for relative in prepared.evidence.final_managed_sha256_by_path
            if relative not in approved
        )
        target = self.root / source_only

        def writer(
            _path: Path,
            _content: bytes,
            *,
            expected_source: bytes,
            commit_guard: Callable[[], None],
        ) -> None:
            self.assertEqual(expected_source, prepared.source_checkpoint_bytes)
            _replace_bytes(target, target.read_bytes())
            commit_guard()

        with self.assertRaisesRegex(
            apply.CompletionApplyError,
            "physical evidence identity differs",
        ):
            apply.write_projection(
                prepared,
                **self._write_arguments(),
                atomic_writer=writer,
            )
        self.assertEqual(
            self.checkpoint_path.read_bytes(),
            prepared.source_checkpoint_bytes,
        )

    def test_source_drift_blocks_writer_before_commit(self) -> None:
        self._switch_to_clone()
        prepared = self._prepare()
        _replace_bytes(
            self.checkpoint_path,
            prepared.source_checkpoint_bytes + b" ",
        )
        called = False

        def writer(*_args: object, **_kwargs: object) -> None:
            nonlocal called
            called = True

        with self.assertRaisesRegex(
            apply.CompletionApplyError,
            "source changed before write revalidation",
        ):
            apply.write_projection(prepared, atomic_writer=writer)
        self.assertFalse(called)

    def test_gate_and_transitive_predecessor_swaps_are_commit_guarded(self) -> None:
        for relative in (
            Path(apply.START_GATE_BINDING["path"]).parent / "01-CONTINUATION.log",
            next(iter(apply.TRANSITIVE_INPUT_SHA256_BY_PATH)),
        ):
            with self.subTest(relative=relative):
                self._switch_to_clone()
                prepared = self._prepare()
                target = self.root / relative

                def writer(
                    _path: Path,
                    _content: bytes,
                    *,
                    expected_source: bytes,
                    commit_guard: Callable[[], None],
                ) -> None:
                    self.assertEqual(expected_source, prepared.source_checkpoint_bytes)
                    _replace_bytes(target, target.read_bytes())
                    commit_guard()

                with self.assertRaisesRegex(
                    apply.CompletionApplyError,
                    "physical evidence identity differs",
                ):
                    apply.write_projection(
                        prepared,
                        **self._write_arguments(),
                        atomic_writer=writer,
                    )
                self.assertEqual(
                    self.checkpoint_path.read_bytes(),
                    prepared.source_checkpoint_bytes,
                )

    def test_checker_dependency_drift_is_rechecked_inside_commit_guard(self) -> None:
        self._switch_to_clone()
        historical_input = self.root / "synthetic-historical-checker-input.txt"
        historical_input.write_bytes(b"sealed\n")

        def graph_checker(_root: Path, _checkpoint: Path) -> list[str]:
            return (
                []
                if historical_input.read_bytes() == b"sealed\n"
                else ["synthetic historical dependency changed"]
            )

        prepared = self._prepare(goal_graph_checker=graph_checker)

        def writer(
            _path: Path,
            _content: bytes,
            *,
            expected_source: bytes,
            commit_guard: Callable[[], None],
        ) -> None:
            self.assertEqual(expected_source, prepared.source_checkpoint_bytes)
            _replace_bytes(historical_input, b"drifted\n")
            commit_guard()

        with self.assertRaisesRegex(
            apply.CompletionApplyError,
            "goal-graph v2.4 check failed: synthetic historical dependency changed",
        ):
            apply.write_projection(
                prepared,
                runtime_deriver=self._runtime_deriver,
                continuation_checker=self._continuation_checker,
                goal_graph_checker=graph_checker,
                atomic_writer=writer,
            )
        self.assertEqual(
            self.checkpoint_path.read_bytes(),
            prepared.source_checkpoint_bytes,
        )

    def test_sealed_directory_extra_entries_are_commit_guarded(self) -> None:
        self.assertEqual(len(apply.SEALED_INVENTORY_BY_DIRECTORY), 5)
        for relative in (
            apply.LANE_RECEIPT_PATHS[0].parent,
            Path(apply.START_GATE_BINDING["path"]).parent,
            Path(apply.RESUME_GATE_BINDING["path"]).parent,
        ):
            with self.subTest(relative=relative):
                self._switch_to_clone()
                prepared = self._prepare()
                directory = self.root / relative

                def writer(
                    _path: Path,
                    _content: bytes,
                    *,
                    expected_source: bytes,
                    commit_guard: Callable[[], None],
                ) -> None:
                    self.assertEqual(expected_source, prepared.source_checkpoint_bytes)
                    (directory / "unexpected-entry.json").write_bytes(b"{}\n")
                    commit_guard()

                with self.assertRaisesRegex(
                    apply.CompletionApplyError,
                    "evidence directory inventory differs",
                ):
                    apply.write_projection(
                        prepared,
                        **self._write_arguments(),
                        atomic_writer=writer,
                    )
                self.assertEqual(
                    self.checkpoint_path.read_bytes(),
                    prepared.source_checkpoint_bytes,
                )

    def test_test_pin_overrides_cannot_reach_production_atomic_writer(self) -> None:
        prepared = self._prepare()
        with (
            mock.patch.object(apply, "retain_physical_pin_cohort") as retain,
            self.assertRaisesRegex(
                apply.CompletionApplyError,
                "test-only SHA-256 overrides",
            ),
        ):
            apply.write_projection(prepared)
        retain.assert_not_called()

    def test_shared_atomic_transport_performs_one_compare_exchange(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "checkpoint.json"
            source = b'{"sequence":48}\n'
            projected = b'{"sequence":50}\n'
            path.write_bytes(source)
            path.chmod(0o600)
            exchanges: list[tuple[str, str]] = []
            guard_observations: list[bytes] = []

            def exchanger(parent_fd: int, staged: str, target: str) -> None:
                exchanges.append((staged, target))
                apply.cas_transport._rename_exchange_at(parent_fd, staged, target)

            apply.atomic_write(
                path,
                projected,
                expected_source=source,
                exchanger=exchanger,
                commit_guard=lambda: guard_observations.append(path.read_bytes()),
            )
            self.assertEqual(path.read_bytes(), projected)
            self.assertEqual(len(exchanges), 1)
            self.assertEqual(exchanges[0][1], path.name)
            self.assertEqual(
                guard_observations,
                [source, projected, projected],
            )

    def test_parser_rejects_abbreviations_and_pin_overrides(self) -> None:
        for arguments in (
            [],
            ["--preflight", "--write"],
            ["--pre"],
            ["--w"],
            ["--preflight", "--r", str(self.root)],
            ["--preflight", "--artifact-sha256", "0" * 64],
        ):
            with self.subTest(arguments=arguments), self.assertRaises(SystemExit):
                apply.parse_args(arguments)
        parsed = apply.parse_args(["--preflight", "--root", str(self.root)])
        self.assertTrue(parsed.preflight)
        self.assertFalse(parsed.write)
        self.assertEqual(parsed.root, self.root)


if __name__ == "__main__":
    unittest.main()

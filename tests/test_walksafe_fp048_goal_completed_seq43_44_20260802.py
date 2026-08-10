from __future__ import annotations

import copy
import fcntl
import hashlib
import json
import os
from pathlib import Path
import stat
import tempfile
from typing import Callable
import unittest

from scripts import apply_walksafe_fp048_goal_completed_seq43_44_20260802 as apply
from scripts import check_walksafe_project_continuation_v2_4 as contract


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
SOURCE_CHECKPOINT_FIXTURE = (
    REPOSITORY_ROOT
    / "tests/fixtures/walksafe-project-continuation-checkpoint-seq42-20260802.json"
)
SOURCE_CHECKPOINT_FIXTURE_RAW_SHA256 = (
    "17d95f92197797be88ea35c230c5449057f8594938d2d616599f2a01da987d32"
)
SOURCE_CHECKPOINT_FIXTURE_BYTE_COUNT = 1_392_068
EXPECTED_PRODUCTION_SHA256_BY_ROLE = {
    "ARTIFACT_CHANGE_LOG": (
        "5a00d1131f8b7b4ec23aa09be3e68acd500514c8cff743b09223284a9cc1be85"
    ),
    "ARTIFACT_REGISTER": (
        "4489c57336958754955cc83bfa5857bd21dcc05c60013c31d0120818581e4005"
    ),
    "DESIGN_TRACEABILITY": (
        "16792f981821f3782cc2cc9c3eb2de538263c7caddbcfa6916562f43499e2a1d"
    ),
    "IMPLEMENTATION_BACKLOG": (
        "eabd987cff1086c6a45b9b6eee9166213727ab59d63cd5b7075da01e27651021"
    ),
    "IMPLEMENTATION_GAP": (
        "34d4b8a4a05ac346e48293344dbf17e10cb65a792e85c5df42f69a7d63c09f82"
    ),
    "MODULE_REGISTER": (
        "9fce017a938ff163f2f863047e280bf0dff2fe100914db6208bcca5c7ede3284"
    ),
    "REQUIREMENTS_TRACEABILITY": (
        "599c69fc6c97359fecd8ac9a535cb07a652626d3371b803dce71b00fcad54793"
    ),
    apply.COMPLETION_ROLE: (
        "072dfee0086702250a15dbf320d58ba477561c5d1ec999349e59a55a4df56992"
    ),
}


class WalkSafeFp048GoalCompletedSeq4344Test(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.checkpoint_path = self.root / apply.CHECKPOINT_RELATIVE
        self.checkpoint_path.parent.mkdir(parents=True)
        fixture_bytes = SOURCE_CHECKPOINT_FIXTURE.read_bytes()
        if (
            len(fixture_bytes) != SOURCE_CHECKPOINT_FIXTURE_BYTE_COUNT
            or hashlib.sha256(fixture_bytes).hexdigest()
            != SOURCE_CHECKPOINT_FIXTURE_RAW_SHA256
        ):
            raise RuntimeError("sealed seq42 checkpoint fixture differs")
        self.checkpoint_path.write_bytes(fixture_bytes)
        self.checkpoint_path.chmod(0o600)
        self.source = json.loads(self.checkpoint_path.read_text(encoding="utf-8"))
        self.pins: dict[str, str] = {}
        self.calls: list[tuple[str, Path]] = []
        self._write_evidence()

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _write_json(self, relative: Path, value: dict[str, object]) -> str:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        content = apply.json_bytes(value)
        path.write_bytes(content)
        path.chmod(0o600)
        return apply.sha256_bytes(content)

    def _identity_document(self, spec: apply.ArtifactSpec) -> dict[str, object]:
        if spec.identity_json_path == "metadata.artifact_type_ids":
            metadata: dict[str, object] = {"artifact_type_ids": [spec.document_id]}
        else:
            metadata = {spec.identity_json_path.split(".")[-1]: spec.document_id}
        return {"schema_version": "test.v1", "metadata": metadata}

    def _write_evidence(self) -> None:
        for spec in apply.ARTIFACT_SPECS:
            if spec.role == apply.COMPLETION_ROLE:
                continue
            value = self._identity_document(spec)
            if spec.role == "IMPLEMENTATION_GAP":
                value.update(
                    {
                        "reassessment_scope": {
                            "next_adjacent_gap": {
                                "source_policy_id": apply.NEXT_POLICY_ID,
                                "gap_id": apply.NEXT_GAP_ID,
                                "reason": "next exact policy-gap pair",
                            }
                        },
                        "assessments": [
                            {
                                "source_policy_id": "FP-048",
                                "gap_id": "GAP-057",
                                "status": "PARTIAL",
                            }
                        ],
                        "summary": {"status_counts": {"PARTIAL": 1}},
                        "implementation_snapshot": {
                            "scope_kind": "FP048_TEST_SCOPE",
                            "file_count": 1,
                        },
                    }
                )
            elif spec.role == "IMPLEMENTATION_BACKLOG":
                value["next_single_action"] = {
                    "epic_id": "EPIC-03",
                    "source_policy_id": apply.NEXT_POLICY_ID,
                    "gap_id": apply.NEXT_GAP_ID,
                    "status": "PLANNED_NEXT",
                    "action": apply.NEXT_ACTION,
                }
                value["epics"] = [
                    {"epic_id": "EPIC-03", "current_status": "IN_PROGRESS"}
                ]
            self.pins[spec.role] = self._write_json(spec.path, value)

        result_evidence = []
        for kind, relative in (
            ("IMPLEMENTATION_RECORD", apply.IMPLEMENTATION_RESULT_PATH),
            ("VERIFICATION_RESULT", apply.VERIFICATION_RESULT_PATH),
            ("SUCCESSOR_TRACE", apply.SUCCESSOR_TRACE_PATH),
        ):
            digest = self._write_json(
                relative,
                {
                    "schema_version": "test.v1",
                    "goal_id": apply.GOAL_ID,
                    "kind": kind,
                    "status": "PASS",
                },
            )
            result_evidence.append(
                {"kind": kind, "path": relative.as_posix(), "sha256": digest}
            )
        review_sha256 = self._write_json(
            apply.INDEPENDENT_REVIEW_PATH,
            {
                "schema_version": "test.v1",
                "goal_id": apply.GOAL_ID,
                "status": "PASS",
                "decision": "APPROVED",
            },
        )
        reviewer_provenance = {
            "path": apply.INDEPENDENT_REVIEW_PATH.as_posix(),
            "sha256": review_sha256,
        }
        manifest = [
            {
                "path": spec.path.as_posix(),
                "sha256": self.pins[spec.role],
            }
            for spec in apply.ARTIFACT_SPECS
            if spec.role != apply.COMPLETION_ROLE
        ]
        manifest.extend(
            {"path": row["path"], "sha256": row["sha256"]}
            for row in result_evidence
        )
        manifest.append(reviewer_provenance)
        consumers = [
            {
                "role": spec.consumer_role,
                "path": spec.path.as_posix(),
                "schema_version": None,
                "sha256": self.pins[spec.role],
            }
            for spec in apply.ARTIFACT_SPECS
            if spec.role != apply.COMPLETION_ROLE
        ]
        start = self.source["goal_execution"]["transition_history"][-1]
        receipt: dict[str, object] = {
            "schema_version": "1.0",
            "document_id": apply.COMPLETION_DOCUMENT_ID,
            "evidence_type": "WORK_ITEM_EXECUTION_RECEIPT",
            "status": "ACCEPTED",
            "result": "PASS",
            "target_goal_id": apply.GOAL_ID,
            "target_goal_content_sha256": apply.GOAL_SHA256,
            "work_item_id": apply.WORK_ITEM_ID,
            "source_policy_ids": ["FP-048"],
            "gap_ids": ["GAP-057"],
            "target_completion_level": "INTERNAL_POLICY_CONFORMANCE_REASSESSED",
            "execution_start_event_sha256": apply.SOURCE_EVENT_SHA256,
            "execution_session_event": {
                "sequence": 42,
                "event_id": apply.SOURCE_EVENT_ID,
                "event_type": "GOAL_STARTED",
                "event_sha256": apply.SOURCE_EVENT_SHA256,
            },
            "implementation_start_gate_binding": copy.deepcopy(
                start["implementation_start_gate_binding"]
            ),
            "execution_window": {
                "started_at": "2026-08-02T21:42:19.123456+09:00",
                "ended_at": "2026-08-02T21:50:00.654321+09:00",
            },
            "completed_at": "2026-08-02T21:51:00+09:00",
            "executor": {
                "id": "CODEX-FP048-IMPLEMENTER-001",
                "task": "/root",
                "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
                "authority": "GRAPH_V2_4_STANDING_EXECUTION_AUTHORITY",
            },
            "reviewer": {
                "id": "WS-FP048-INDEPENDENT-REVIEWER-001",
                "task": "/root/fp048-independent-review",
                "role": "SEPARATE_INTERNAL_REVIEWER",
                "separate_internal_review_pass": True,
                "external_independence_claimed": False,
                "authority": "INTERNAL_REPOSITORY_CONTROL",
                "decision": "APPROVED",
                "decided_at": "2026-08-02T21:51:00+09:00",
            },
            "reviewer_provenance": reviewer_provenance,
            "result_evidence": result_evidence,
            "downstream_consumer_bindings": consumers,
            "output_evidence_manifest": manifest,
            "output_evidence_manifest_sha256": contract.canonical_json_sha256(
                manifest
            ),
            "completion_boundary": copy.deepcopy(
                apply.EXPECTED_COMPLETION_BOUNDARY
            ),
            "generated_at": "2026-08-02T21:51:00+09:00",
        }
        self.pins[apply.COMPLETION_ROLE] = self._write_json(
            apply.COMPLETION_PATH,
            receipt,
        )

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
        queue = {
            "schema_version": "test.v1",
            "source_binding": {
                key: bindings["ARTIFACT_REGISTER"][key]
                for key in ("role", "document_id", "path", "file_sha256")
            },
            "status": checkpoint["goal_execution"]["status_by_goal"][apply.GOAL_ID],
        }
        boundary = {
            "schema_version": "test.v1",
            "internal_runnable_goal_ids": copy.deepcopy(ready_frontier),
            "internal_pending_goal_ids": (
                [apply.GOAL_ID]
                if checkpoint["goal_execution"]["status_by_goal"][apply.GOAL_ID]
                != "COMPLETE_AT_TARGET"
                else []
            ),
        }
        return queue, boundary

    @staticmethod
    def _snapshot_hasher(
        _root: Path,
        paths: list[str],
    ) -> tuple[str, str]:
        return (
            contract.canonical_json_sha256(paths),
            contract.canonical_json_sha256({"paths": paths}),
        )

    def _prepare(self) -> apply.PreparedProjection:
        return apply.prepare_projection(
            self.root,
            artifact_sha256_by_role=self.pins,
            runtime_deriver=self._runtime_deriver,
            snapshot_hasher=self._snapshot_hasher,
            continuation_checker=self._continuation_checker,
            goal_graph_checker=self._goal_graph_checker,
        )

    def _refresh_pin(self, role: str) -> None:
        spec = apply.SPEC_BY_ROLE[role]
        self.pins[role] = apply.sha256_bytes((self.root / spec.path).read_bytes())

    def test_seq42_source_fixture_is_exact_and_not_a_live_checkpoint_alias(self) -> None:
        fixture_bytes = SOURCE_CHECKPOINT_FIXTURE.read_bytes()
        self.assertTrue(SOURCE_CHECKPOINT_FIXTURE.is_file())
        self.assertFalse(SOURCE_CHECKPOINT_FIXTURE.is_symlink())
        self.assertEqual(SOURCE_CHECKPOINT_FIXTURE.stat().st_nlink, 1)
        self.assertEqual(len(fixture_bytes), SOURCE_CHECKPOINT_FIXTURE_BYTE_COUNT)
        self.assertEqual(
            hashlib.sha256(fixture_bytes).hexdigest(),
            SOURCE_CHECKPOINT_FIXTURE_RAW_SHA256,
        )
        self.assertEqual(
            (
                SOURCE_CHECKPOINT_FIXTURE_BYTE_COUNT,
                SOURCE_CHECKPOINT_FIXTURE_RAW_SHA256,
            ),
            (
                apply.SOURCE_CHECKPOINT_BYTE_COUNT,
                apply.SOURCE_CHECKPOINT_RAW_SHA256,
            ),
        )
        self.assertFalse(
            SOURCE_CHECKPOINT_FIXTURE.samefile(
                REPOSITORY_ROOT / apply.CHECKPOINT_RELATIVE
            )
        )

    def test_projects_exact_adjacent_pair_and_single_atomic_checkpoint_write(self) -> None:
        before = self.checkpoint_path.read_bytes()
        prepared = self._prepare()
        self.assertEqual(self.checkpoint_path.read_bytes(), before)
        self.assertEqual(
            [label for label, _path in self.calls],
            ["continuation", "goal_graph"],
        )
        self.assertTrue(all(path != apply.CHECKPOINT_RELATIVE for _, path in self.calls))

        update = prepared.update_event
        completion = prepared.completion_event
        self.assertEqual(update["sequence"], 43)
        self.assertEqual(completion["sequence"], 44)
        self.assertEqual(completion["previous_event_sha256"], update["event_sha256"])
        self.assertEqual(set(update), apply.UPDATE_EVENT_FIELDS)
        self.assertEqual(set(completion), apply.COMPLETION_EVENT_FIELDS)
        self.assertEqual(update["changed_binding_roles"], apply.CHANGED_ROLES)
        self.assertEqual(update["produced_binding_roles"], apply.PRODUCED_ROLES)
        self.assertTrue(
            apply.FORBIDDEN_CHANGED_ROLES.isdisjoint(update["changed_binding_roles"])
        )
        self.assertEqual(len(update["canonical_binding_snapshot_after"]), 39)
        self.assertEqual(
            update["canonical_binding_snapshot_after"],
            completion["canonical_binding_snapshot_after"],
        )
        self.assertEqual(update["occurred_at"], "2026-08-02T21:51:00+09:00")
        self.assertEqual(completion["occurred_at"], "2026-08-02T21:51:01+09:00")

        replacements: list[tuple[str, str]] = []

        def atomic_writer(
            path: Path,
            content: bytes,
            *,
            expected_source: bytes,
            commit_guard: Callable[[], None],
        ) -> None:
            def exchanger(parent_fd: int, source: str, target: str) -> None:
                replacements.append((source, target))
                apply._rename_exchange_at(parent_fd, source, target)

            apply.atomic_write(
                path,
                content,
                expected_source=expected_source,
                exchanger=exchanger,
                commit_guard=commit_guard,
            )

        apply.write_projection(
            prepared,
            runtime_deriver=self._runtime_deriver,
            snapshot_hasher=self._snapshot_hasher,
            continuation_checker=self._continuation_checker,
            goal_graph_checker=self._goal_graph_checker,
            atomic_writer=atomic_writer,
        )
        self.assertEqual(len(replacements), 1)
        written = json.loads(self.checkpoint_path.read_text(encoding="utf-8"))
        state = written["goal_execution"]
        self.assertEqual(len(state["transition_history"]), 44)
        self.assertEqual(state["status_by_goal"][apply.GOAL_ID], "COMPLETE_AT_TARGET")
        self.assertEqual(state["focus_goal_id"], apply.PARENT_GOAL_ID)
        self.assertEqual(
            state["ready_frontier_goal_ids"],
            [apply.PARENT_GOAL_ID, apply.EPIC12_GOAL_ID],
        )
        self.assertEqual(state["pending_producer_completion_goal_id"], "")
        self.assertEqual(
            state["completion_evidence_by_goal"][apply.GOAL_ID],
            [apply.COMPLETION_ROLE],
        )
        self.assertEqual(len(written["canonical_bindings"]), 39)
        self.assertEqual(written["current_work"]["work_item_id"], apply.NEXT_WORK_ITEM_ID)
        self.assertEqual(written["current_work"]["next_action"], apply.NEXT_ACTION)
        self.assertEqual(written["approved_state"], self.source["approved_state"])
        self.assertEqual(
            written["verification_boundary"],
            self.source["verification_boundary"],
        )

    def test_production_sha_literals_are_exact_and_temp_fixture_needs_override(self) -> None:
        self.assertEqual(
            apply.PINNED_PRODUCTION_SHA256_BY_ROLE,
            EXPECTED_PRODUCTION_SHA256_BY_ROLE,
        )
        before = self.checkpoint_path.read_bytes()
        with self.assertRaisesRegex(
            apply.CompletionApplyError,
            "physical artifact SHA-256 differs",
        ):
            apply.prepare_projection(
                self.root,
                runtime_deriver=self._runtime_deriver,
                snapshot_hasher=self._snapshot_hasher,
                continuation_checker=self._continuation_checker,
                goal_graph_checker=self._goal_graph_checker,
            )
        self.assertEqual(self.calls, [])
        self.assertEqual(self.checkpoint_path.read_bytes(), before)

    def test_production_evidence_accepts_fractional_execution_window_read_only(self) -> None:
        live_checkpoint = REPOSITORY_ROOT / apply.CHECKPOINT_RELATIVE
        live_before = live_checkpoint.read_bytes()
        source_bytes = SOURCE_CHECKPOINT_FIXTURE.read_bytes()
        source = json.loads(source_bytes)
        apply.require_exact_source(source_bytes, source)

        evidence = apply.load_completion_evidence(
            REPOSITORY_ROOT,
            source,
            apply.PINNED_PRODUCTION_SHA256_BY_ROLE,
        )

        self.assertEqual(
            evidence.receipt["execution_window"],
            {
                "started_at": "2026-08-03T01:33:35.801447+09:00",
                "ended_at": "2026-08-03T01:36:00.069186+09:00",
            },
        )
        self.assertEqual(evidence.update_occurred_at, "2026-08-03T12:08:56+09:00")
        self.assertEqual(
            evidence.completion_occurred_at,
            "2026-08-03T12:08:57+09:00",
        )
        self.assertEqual(live_checkpoint.read_bytes(), live_before)

    def test_production_projection_passes_current_v24_checkers_read_only(self) -> None:
        live_checkpoint = REPOSITORY_ROOT / apply.CHECKPOINT_RELATIVE
        live_before = live_checkpoint.read_bytes()
        live_inode = live_checkpoint.stat().st_ino
        source_bytes = SOURCE_CHECKPOINT_FIXTURE.read_bytes()
        source = json.loads(source_bytes)
        apply.require_exact_source(source_bytes, source)
        evidence = apply.load_completion_evidence(
            REPOSITORY_ROOT,
            source,
            apply.PINNED_PRODUCTION_SHA256_BY_ROLE,
        )
        cohort = apply.retain_artifact_pin_cohort(
            REPOSITORY_ROOT,
            apply.PINNED_PRODUCTION_SHA256_BY_ROLE,
        )
        try:
            cohort.verify()
        finally:
            cohort.close()

        projected, update, completion = apply.project_seq43_44(
            REPOSITORY_ROOT,
            source,
            evidence,
        )
        apply.validate_exact_projection(source, projected, evidence)
        apply.validate_projected_with_both_checkers(
            REPOSITORY_ROOT,
            apply.json_bytes(projected),
        )

        self.assertEqual((update["sequence"], completion["sequence"]), (43, 44))
        self.assertEqual(
            projected["goal_execution"]["status_by_goal"][apply.GOAL_ID],
            "COMPLETE_AT_TARGET",
        )
        self.assertEqual(
            projected["current_work"]["work_item_id"],
            apply.NEXT_WORK_ITEM_ID,
        )
        self.assertEqual(live_checkpoint.read_bytes(), live_before)
        self.assertEqual(live_checkpoint.stat().st_ino, live_inode)

    def test_exact_artifact_hash_mismatch_fails_without_checkpoint_mutation(self) -> None:
        before = self.checkpoint_path.read_bytes()
        pins = dict(self.pins)
        pins["MODULE_REGISTER"] = "0" * 64
        with self.assertRaisesRegex(
            apply.CompletionApplyError,
            "physical artifact SHA-256 differs",
        ):
            apply.prepare_projection(
                self.root,
                artifact_sha256_by_role=pins,
                runtime_deriver=self._runtime_deriver,
                snapshot_hasher=self._snapshot_hasher,
                continuation_checker=self._continuation_checker,
                goal_graph_checker=self._goal_graph_checker,
            )
        self.assertEqual(self.checkpoint_path.read_bytes(), before)

    def test_non_exact_fp008_gap017_pointer_fails_closed(self) -> None:
        before = self.checkpoint_path.read_bytes()
        backlog_path = self.root / apply.BACKLOG_PATH
        backlog = json.loads(backlog_path.read_text(encoding="utf-8"))
        backlog["next_single_action"]["status"] = "MISSING"
        backlog_path.write_bytes(apply.json_bytes(backlog))
        self._refresh_pin("IMPLEMENTATION_BACKLOG")
        with self.assertRaisesRegex(
            apply.CompletionApplyError,
            "next_single_action differs",
        ):
            self._prepare()
        self.assertEqual(self.checkpoint_path.read_bytes(), before)

    def test_formal_external_or_release_promotion_fails_closed(self) -> None:
        before = self.checkpoint_path.read_bytes()
        receipt_path = self.root / apply.COMPLETION_PATH
        receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
        receipt["completion_boundary"]["release_status"] = "ELIGIBLE"
        receipt_path.write_bytes(apply.json_bytes(receipt))
        self._refresh_pin(apply.COMPLETION_ROLE)
        with self.assertRaisesRegex(
            apply.CompletionApplyError,
            "formal/external/release boundary differs",
        ):
            self._prepare()
        self.assertEqual(self.checkpoint_path.read_bytes(), before)

    def test_either_v24_checker_failure_prevents_projection_write(self) -> None:
        before = self.checkpoint_path.read_bytes()

        def failing_graph(_root: Path, _checkpoint: Path) -> list[str]:
            return ["synthetic strict graph failure"]

        with self.assertRaisesRegex(
            apply.CompletionApplyError,
            "goal-graph v2.4 check failed",
        ):
            apply.prepare_projection(
                self.root,
                artifact_sha256_by_role=self.pins,
                runtime_deriver=self._runtime_deriver,
                snapshot_hasher=self._snapshot_hasher,
                continuation_checker=self._continuation_checker,
                goal_graph_checker=failing_graph,
            )
        self.assertEqual(self.checkpoint_path.read_bytes(), before)
        self.assertFalse(
            list(
                self.checkpoint_path.parent.glob(
                    ".walksafe-fp048-seq43-44-preflight.*.json"
                )
            )
        )

    def test_cas_change_after_prepare_never_overwrites_checkpoint(self) -> None:
        prepared = self._prepare()
        changed = prepared.source_checkpoint_bytes + b" "
        self.checkpoint_path.write_bytes(changed)
        with self.assertRaisesRegex(
            apply.CompletionApplyError,
            "source changed before write revalidation",
        ):
            apply.write_projection(
                prepared,
                runtime_deriver=self._runtime_deriver,
                snapshot_hasher=self._snapshot_hasher,
                continuation_checker=self._continuation_checker,
                goal_graph_checker=self._goal_graph_checker,
            )
        self.assertEqual(self.checkpoint_path.read_bytes(), changed)

    def test_artifact_inode_swap_at_exchange_rolls_back_checkpoint(self) -> None:
        before = self.checkpoint_path.read_bytes()
        prepared = self._prepare()
        evidence_path = self.root / apply.SPEC_BY_ROLE["ARTIFACT_CHANGE_LOG"].path
        evidence_bytes = evidence_path.read_bytes()
        replacement_path = evidence_path.with_name("artifact-change-log-replacement.json")
        replacement_path.write_bytes(evidence_bytes)
        replacement_path.chmod(0o600)
        replacement_inode = replacement_path.stat().st_ino

        def atomic_writer(
            path: Path,
            content: bytes,
            *,
            expected_source: bytes,
            commit_guard: Callable[[], None],
        ) -> None:
            def exchange_after_artifact_swap(
                parent_fd: int,
                source: str,
                target: str,
            ) -> None:
                os.replace(replacement_path, evidence_path)
                apply._rename_exchange_at(parent_fd, source, target)

            apply.atomic_write(
                path,
                content,
                expected_source=expected_source,
                exchanger=exchange_after_artifact_swap,
                commit_guard=commit_guard,
            )

        with self.assertRaisesRegex(
            apply.CompletionApplyError,
            "retained physical artifact identity differs",
        ):
            apply.write_projection(
                prepared,
                runtime_deriver=self._runtime_deriver,
                snapshot_hasher=self._snapshot_hasher,
                continuation_checker=self._continuation_checker,
                goal_graph_checker=self._goal_graph_checker,
                atomic_writer=atomic_writer,
            )

        self.assertEqual(self.checkpoint_path.read_bytes(), before)
        self.assertEqual(evidence_path.read_bytes(), evidence_bytes)
        self.assertEqual(evidence_path.stat().st_ino, replacement_inode)
        self.assertFalse(
            list(
                self.checkpoint_path.parent.glob(
                    f".{self.checkpoint_path.name}.fp048-seq43-44.*.tmp"
                )
            )
        )

    def test_atomic_write_locks_parent_and_rejects_same_byte_inode_swap(self) -> None:
        before = self.checkpoint_path.read_bytes()
        parent_fd = os.open(self.checkpoint_path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            fcntl.flock(parent_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with self.assertRaisesRegex(
                apply.CompletionApplyError,
                "publication parent is already locked",
            ):
                apply.atomic_write(
                    self.checkpoint_path,
                    b"replacement",
                    expected_source=before,
                )
        finally:
            fcntl.flock(parent_fd, fcntl.LOCK_UN)
            os.close(parent_fd)
        self.assertEqual(self.checkpoint_path.read_bytes(), before)

        foreign = self.checkpoint_path.with_name("foreign-checkpoint.json")
        foreign.write_bytes(before)
        foreign_inode = foreign.stat().st_ino

        def exchange_after_same_byte_swap(
            parent_descriptor: int,
            source: str,
            target: str,
        ) -> None:
            os.replace(foreign, self.checkpoint_path)
            apply._rename_exchange_at(parent_descriptor, source, target)

        with self.assertRaisesRegex(
            apply.CompletionApplyError,
            "atomic compare-exchange boundary",
        ):
            apply.atomic_write(
                self.checkpoint_path,
                b"replacement",
                expected_source=before,
                exchanger=exchange_after_same_byte_swap,
            )
        self.assertEqual(self.checkpoint_path.read_bytes(), before)
        self.assertEqual(self.checkpoint_path.stat().st_ino, foreign_inode)
        self.assertFalse(
            list(
                self.checkpoint_path.parent.glob(
                    f".{self.checkpoint_path.name}.fp048-seq43-44.*.tmp"
                )
            )
        )

    def test_atomic_write_rejects_noncanonical_source_mode(self) -> None:
        before = self.checkpoint_path.read_bytes()
        self.checkpoint_path.chmod(0o640)

        with self.assertRaisesRegex(
            apply.CompletionApplyError,
            "source checkpoint authority differs",
        ):
            apply.atomic_write(
                self.checkpoint_path,
                b"replacement",
                expected_source=before,
            )

        self.assertEqual(self.checkpoint_path.read_bytes(), before)
        self.assertEqual(stat.S_IMODE(self.checkpoint_path.stat().st_mode), 0o640)
        self.assertFalse(
            list(
                self.checkpoint_path.parent.glob(
                    f".{self.checkpoint_path.name}.fp048-seq43-44.*.tmp"
                )
            )
        )

    def test_partial_temp_write_failure_preserves_checkpoint_and_cleans_temp(self) -> None:
        before = self.checkpoint_path.read_bytes()

        def partial_writer(stream: object, content: bytes) -> None:
            stream.write(content[: max(1, len(content) // 2)])
            stream.flush()
            raise OSError("synthetic partial write")

        with self.assertRaisesRegex(OSError, "synthetic partial write"):
            apply.atomic_write(
                self.checkpoint_path,
                b"replacement",
                expected_source=before,
                writer=partial_writer,
            )
        self.assertEqual(self.checkpoint_path.read_bytes(), before)
        self.assertFalse(
            list(
                self.checkpoint_path.parent.glob(
                    f".{self.checkpoint_path.name}.fp048-seq43-44.*.tmp"
                )
            )
        )

    def test_atomic_replace_failure_preserves_checkpoint_and_cleans_temp(self) -> None:
        before = self.checkpoint_path.read_bytes()

        def failing_exchange(_parent_fd: int, _source: str, _target: str) -> None:
            raise OSError("synthetic exchange failure")

        with self.assertRaisesRegex(OSError, "synthetic exchange failure"):
            apply.atomic_write(
                self.checkpoint_path,
                b"replacement",
                expected_source=before,
                exchanger=failing_exchange,
            )
        self.assertEqual(self.checkpoint_path.read_bytes(), before)
        self.assertFalse(
            list(
                self.checkpoint_path.parent.glob(
                    f".{self.checkpoint_path.name}.fp048-seq43-44.*.tmp"
                )
            )
        )

    def test_atomic_write_preserves_exact_mode_under_owner_masking_umask(self) -> None:
        before = self.checkpoint_path.read_bytes()
        source_mode = stat.S_IMODE(self.checkpoint_path.stat().st_mode)
        previous_umask = os.umask(0o200)
        try:
            apply.atomic_write(
                self.checkpoint_path,
                b"replacement",
                expected_source=before,
            )
        finally:
            os.umask(previous_umask)

        self.assertEqual(self.checkpoint_path.read_bytes(), b"replacement")
        self.assertEqual(
            stat.S_IMODE(self.checkpoint_path.stat().st_mode),
            source_mode,
        )

    def test_atomic_write_rejects_detached_parent_publication_and_rolls_back(self) -> None:
        before = self.checkpoint_path.read_bytes()
        canonical_parent = self.checkpoint_path.parent
        detached_parent = canonical_parent.with_name("control-detached")
        foreign = b"foreign canonical checkpoint"
        sync_count = 0

        def exchange_after_parent_replacement(
            parent_fd: int,
            source: str,
            target: str,
        ) -> None:
            canonical_parent.rename(detached_parent)
            canonical_parent.mkdir()
            (canonical_parent / self.checkpoint_path.name).write_bytes(foreign)
            apply._rename_exchange_at(parent_fd, source, target)

        def count_sync(parent_fd: int) -> None:
            nonlocal sync_count
            sync_count += 1
            os.fsync(parent_fd)

        with self.assertRaisesRegex(
            apply.CompletionApplyError,
            "publication parent changed at atomic compare-exchange boundary",
        ):
            apply.atomic_write(
                self.checkpoint_path,
                b"replacement",
                expected_source=before,
                exchanger=exchange_after_parent_replacement,
                directory_syncer=count_sync,
            )

        self.assertEqual(sync_count, 1)
        self.assertEqual(self.checkpoint_path.read_bytes(), foreign)
        self.assertEqual(
            (detached_parent / self.checkpoint_path.name).read_bytes(),
            before,
        )
        self.assertFalse(
            list(
                detached_parent.glob(
                    f".{self.checkpoint_path.name}.fp048-seq43-44.*.tmp"
                )
            )
        )

    def test_failed_cas_rollback_preserves_recovery_entry(self) -> None:
        before = self.checkpoint_path.read_bytes()
        sync_count = 0

        def exchange_then_corrupt_published_stage(
            parent_fd: int,
            source: str,
            target: str,
        ) -> None:
            apply._rename_exchange_at(parent_fd, source, target)
            os.unlink(target, dir_fd=parent_fd)
            descriptor = os.open(
                target,
                os.O_WRONLY | os.O_CREAT | os.O_EXCL,
                0o600,
                dir_fd=parent_fd,
            )
            try:
                os.write(descriptor, b"foreign")
            finally:
                os.close(descriptor)

        def count_sync(parent_fd: int) -> None:
            nonlocal sync_count
            sync_count += 1
            os.fsync(parent_fd)

        with self.assertRaisesRegex(
            apply.CompletionApplyError,
            "atomic compare-exchange boundary",
        ):
            apply.atomic_write(
                self.checkpoint_path,
                b"replacement",
                expected_source=before,
                exchanger=exchange_then_corrupt_published_stage,
                directory_syncer=count_sync,
            )

        self.assertEqual(sync_count, 1)
        retained = list(
            self.checkpoint_path.parent.glob(
                f".{self.checkpoint_path.name}.fp048-seq43-44.*.tmp"
            )
        )
        self.assertEqual(len(retained), 1)
        self.assertEqual(retained[0].read_bytes(), before)
        self.assertEqual(self.checkpoint_path.read_bytes(), b"foreign")

    def test_failure_state_fsync_error_is_noted_without_replacing_primary(self) -> None:
        before = self.checkpoint_path.read_bytes()

        def exchange_then_corrupt_published_stage(
            parent_fd: int,
            source: str,
            target: str,
        ) -> None:
            apply._rename_exchange_at(parent_fd, source, target)
            os.unlink(target, dir_fd=parent_fd)
            descriptor = os.open(
                target,
                os.O_WRONLY | os.O_CREAT | os.O_EXCL,
                0o600,
                dir_fd=parent_fd,
            )
            os.close(descriptor)

        def failing_sync(_parent_fd: int) -> None:
            raise OSError("synthetic failure-state fsync failure")

        with self.assertRaisesRegex(
            apply.CompletionApplyError,
            "atomic compare-exchange boundary",
        ) as captured:
            apply.atomic_write(
                self.checkpoint_path,
                b"replacement",
                expected_source=before,
                exchanger=exchange_then_corrupt_published_stage,
                directory_syncer=failing_sync,
            )

        notes = getattr(captured.exception, "__notes__", [])
        self.assertTrue(
            any("failure-state parent fsync also failed" in note for note in notes)
        )
        retained = list(
            self.checkpoint_path.parent.glob(
                f".{self.checkpoint_path.name}.fp048-seq43-44.*.tmp"
            )
        )
        self.assertEqual(len(retained), 1)
        self.assertEqual(retained[0].read_bytes(), before)

    def test_atomic_write_fsyncs_parent_after_checkpoint_replace(self) -> None:
        before = self.checkpoint_path.read_bytes()
        replacement = b"replacement"
        events: list[str] = []

        def exchanging(parent_fd: int, source: str, target: str) -> None:
            events.append("exchange")
            apply._rename_exchange_at(parent_fd, source, target)

        def syncing(parent_fd: int) -> None:
            self.assertTrue(stat.S_ISDIR(os.fstat(parent_fd).st_mode))
            self.assertEqual(self.checkpoint_path.read_bytes(), replacement)
            events.append("parent_fsync")
            os.fsync(parent_fd)

        apply.atomic_write(
            self.checkpoint_path,
            replacement,
            expected_source=before,
            exchanger=exchanging,
            directory_syncer=syncing,
        )

        self.assertEqual(events, ["exchange", "parent_fsync"])

    def test_parent_fsync_failure_is_explicitly_postcommit_and_not_retried(self) -> None:
        before = self.checkpoint_path.read_bytes()
        replacement = b"replacement"

        def failing_directory_sync(_parent_fd: int) -> None:
            raise OSError("synthetic parent fsync failure")

        with self.assertRaisesRegex(
            apply.CompletionPostCommitError,
            "replacement completed.*durability is uncertain",
        ):
            apply.atomic_write(
                self.checkpoint_path,
                replacement,
                expected_source=before,
                directory_syncer=failing_directory_sync,
            )

        self.assertEqual(self.checkpoint_path.read_bytes(), replacement)
        self.assertFalse(
            list(
                self.checkpoint_path.parent.glob(
                    f".{self.checkpoint_path.name}.fp048-seq43-44.*.tmp"
                )
            )
        )
        with self.assertRaisesRegex(
            apply.CompletionApplyError,
            "source checkpoint bytes changed under publication lock",
        ):
            apply.atomic_write(
                self.checkpoint_path,
                b"second replacement",
                expected_source=before,
            )


if __name__ == "__main__":
    unittest.main()

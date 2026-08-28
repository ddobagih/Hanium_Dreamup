"""Focused active-package regressions for the WalkSafe v2.4 Goal graph."""

from __future__ import annotations

import base64
import copy
from contextlib import contextmanager
from datetime import datetime, timedelta
import gzip
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from typing import Callable
from unittest import mock

from scripts import (
    apply_walksafe_fp046_npc_r002_reopen_20260815 as r002_preflight,
)
from scripts import (
    apply_walksafe_fp046_npc_r002_reopen_seq72_76_20260815
    as r002_transaction,
)
from scripts import (
    build_walksafe_fp022_completion_seq70_71_review_20260814
    as completion_review,
)
from scripts import (
    apply_walksafe_fp048_r002_start_gate_snapshot_hygiene_correction_seq97_20260826
    as snapshot_hygiene_correction,
)
from scripts import check_walksafe_goal_graph_v2_4 as graph


ROOT = Path(__file__).resolve().parents[1]
PACKAGE_RELATIVE = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4"
)
MANIFEST_RELATIVE = PACKAGE_RELATIVE / "static-plan-manifest-v2.4.0.json"
ARCHIVE_RELATIVE = (
    PACKAGE_RELATIVE / "superseded-v2.3.0-active-checkpoint.json"
)
EXPECTED_ARCHIVE_RAW_SHA256 = (
    "7f62b09941e614f11d9c21c84e5f63df2ff070b00c569550faf2de7807de67b6"
)
EXPECTED_PACKAGE_ID = (
    "WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-4"
)
EXPECTED_PLAN_VERSION = "2.4.0"
EXPECTED_FOCUS_GOAL_ID = "WS-GOAL-EPIC-02-FP-011-R001"
EXPECTED_NATIVE_SUPPORT_PATHS = {
    (PACKAGE_RELATIVE / "README.md").as_posix(),
    (
        PACKAGE_RELATIVE / "active-supersession-record-v2.3.0.json"
    ).as_posix(),
    MANIFEST_RELATIVE.as_posix(),
    ARCHIVE_RELATIVE.as_posix(),
    (
        PACKAGE_RELATIVE / "templates/dynamic-node-template.md"
    ).as_posix(),
    (
        PACKAGE_RELATIVE / "templates/policy-gap-work-item.md"
    ).as_posix(),
}


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_json(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"JSON root must be an object: {path}")
    return value


def json_bytes(value: object) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    ).encode("utf-8")


def synthetic_approved_r008_context() -> completion_review.ControlSuccessorR008Context:
    """Model the reviewed 23-path R008 cohort without publishing its triad."""
    r007, r007_bindings = completion_review.prepare_frozen_control_successor_r007(
        ROOT
    )
    current = completion_review._r008_live_current_review_context(ROOT)
    before_by_path = {
        row["path"]: row for row in r007.current.control_code_cohort
    }
    successors = tuple(
        {
            "path": row["path"],
            "predecessor": before_by_path[row["path"]],
            "successor": row,
        }
        for row in current.control_code_cohort
        if row["path"] in before_by_path
        and row != before_by_path[row["path"]]
    )
    added = tuple(
        completion_review._binding(
            relative,
            completion_review._raw(ROOT, relative),
        )
        for relative in completion_review.CONTROL_SUCCESSOR_R008_ADDED_CONTROL_PATHS
    )
    return completion_review.ControlSuccessorR008Context(
        root=ROOT,
        current=current,
        predecessor=r007,
        predecessor_review_bindings=r007_bindings,
        control_code_successors=successors,
        added_control_code_bindings=added,
    )


def r008_snapshot_projection(
    checkpoint: dict,
    context: completion_review.ControlSuccessorR008Context,
) -> dict:
    """Keep a seq71 fixture's source mirror coherent with synthetic R008."""
    projected = copy.deepcopy(checkpoint)
    snapshot = projected["working_tree_snapshot"]
    paths = sorted(
        set(snapshot["managed_changed_paths"])
        | {row["path"] for row in context.current.control_code_cohort}
    )
    content = hashlib.sha256()
    for relative in paths:
        content.update(relative.encode("utf-8"))
        content.update(b"\0")
        content.update(sha256_file(ROOT / relative).encode("ascii"))
        content.update(b"\n")
    path_hash = hashlib.sha256(
        ("\n".join(paths) + "\n").encode("utf-8")
    ).hexdigest()
    content_hash = content.hexdigest()
    snapshot.update(
        {
            "managed_changed_paths": paths,
            "managed_changed_path_count": len(paths),
            "path_set_sha256": path_hash,
            "content_set_sha256": content_hash,
        }
    )
    handoff = projected["session_handoff"]
    handoff["changed_files"] = paths
    handoff["source_commit_or_snapshot"].update(
        {
            "file_count": len(paths),
            "path_set_sha256": path_hash,
            "content_set_sha256": content_hash,
        }
    )
    return projected


def r008_checkpoint_loader(checkpoint: dict) -> Callable[[Path], dict]:
    original = graph.continuation.load_json
    checkpoint_file = (ROOT / graph.CHECKPOINT_RELATIVE).resolve()

    def load(path: Path) -> dict:
        if Path(path).resolve() == checkpoint_file:
            return copy.deepcopy(checkpoint)
        return original(path)

    return load


def write_json(root: Path, relative: str, value: object) -> str:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = json_bytes(value)
    path.write_bytes(payload)
    return hashlib.sha256(payload).hexdigest()


def predecessor_goal_projection(archive: dict) -> dict[str, dict[str, str]]:
    state = archive["goal_execution"]
    projection: dict[str, dict[str, str]] = {}
    for field in (
        "imported_predecessor_goal_bindings",
        "dynamic_goal_inventory",
    ):
        for goal_id, record in state[field].items():
            projection[goal_id] = {
                "path": record["path"],
                "sha256": record["sha256"],
            }
    return projection


def predecessor_completion_projection(archive: dict) -> dict[str, str]:
    state = archive["goal_execution"]
    projection = {
        goal_id: record["completion_event_sha256"]
        for goal_id, record in state[
            "imported_predecessor_goal_bindings"
        ].items()
        if isinstance(record.get("completion_event_sha256"), str)
    }
    for event in state["transition_history"]:
        if (
            event["event_type"] in {"GOAL_COMPLETED", "PACKAGE_COMPLETED"}
            and isinstance(event.get("subject_goal_id"), str)
        ):
            projection[event["subject_goal_id"]] = event["event_sha256"]
    return projection


def frozen_requirements_traceability_errors() -> set[str]:
    return {
        *(
            "Goal graph event "
            f"{sequence}: canonical binding SHA-256 differs: "
            "REQUIREMENTS_TRACEABILITY"
            for sequence in (1, 4, 9, 14)
        ),
        *(
            "archived v2.2: Goal graph event "
            f"{sequence}: canonical binding SHA-256 differs: "
            "REQUIREMENTS_TRACEABILITY"
            for sequence in (1, 6, 11, 17)
        ),
    }


def fp008_seq50_checkpoint() -> dict:
    checkpoint = load_json(ROOT / graph.CHECKPOINT_RELATIVE)
    state = checkpoint["goal_execution"]
    history = state["transition_history"]
    if (
        len(history) >= 50
        and history[48].get("event_id")
        == graph.FP008_ADMIN_REVIEW_CANONICAL_UPDATE_EVENT_ID
        and history[49].get("event_id")
        == graph.FP008_ADMIN_REVIEW_COMPLETION_EVENT_ID
    ):
        snapshot = history[48]["canonical_binding_snapshot_after"]
        historical_bindings = []
        for binding in checkpoint["canonical_bindings"]:
            role = binding["role"]
            if role not in snapshot:
                continue
            historical = copy.deepcopy(binding)
            historical.update(snapshot[role])
            historical_bindings.append(historical)
        if (
            len(historical_bindings) != 40
            or graph.continuation.canonical_json_sha256(
                historical_bindings
            )
            != graph.FP008_ADMIN_REVIEW_CANONICAL_BINDINGS_SHA256
        ):
            raise AssertionError("FP008 historical canonical fixture differs")
        checkpoint["canonical_bindings"] = historical_bindings
        state["transition_history"] = history[:50]

        boundary = state["completion_boundary"]
        boundary.update(
            copy.deepcopy(
                graph.FP008_ADMIN_REVIEW_COMPLETION_BOUNDARY_SAFE_FIELDS
            )
        )
        runtime = copy.deepcopy(graph.FP008_ADMIN_REVIEW_COMPLETION_RUNTIME)
        runtime["artifact_work_queue_sha256"] = (
            graph.continuation.canonical_json_sha256(
                state["artifact_work_queue"]
            )
        )
        runtime["completion_boundary_sha256"] = (
            graph.continuation.canonical_json_sha256(boundary)
        )
        for field in (
            "focus_goal_id",
            "focus_goal_path",
            "focus_work_item_id",
            "focus_source",
            "ready_frontier_goal_ids",
            "blocked_goal_ids",
            "pending_questions",
            "open_question_count",
            "activation_status",
            "package_status",
        ):
            state[field] = copy.deepcopy(runtime[field])
        state["blockers_by_goal"] = {}

        occurred_at = "2026-08-09T16:48:00+09:00"
        event = {
            "sequence": 51,
            "event_id": (
                "WS-GOAL-GRAPH-V2-4-WORK-SESSION-RESUMED-"
                "SAFE-FUTURE-20260809-001"
            ),
            "event_type": "WORK_SESSION_RESUMED",
            "occurred_on": "2026-08-09",
            "occurred_at": occurred_at,
            "previous_focus_goal_id": (
                graph.FP008_ADMIN_REVIEW_PARENT_GOAL_ID
            ),
            "previous_focus_content_sha256": (
                graph.FP008_ADMIN_REVIEW_PARENT_GOAL_SHA256
            ),
            "focus_goal_id": graph.FP008_ADMIN_REVIEW_PARENT_GOAL_ID,
            "focus_goal_content_sha256": (
                graph.FP008_ADMIN_REVIEW_PARENT_GOAL_SHA256
            ),
            "from_status": "READY",
            "to_status": "READY",
            "static_plan_manifest_sha256": (
                graph.FP008_ADMIN_REVIEW_MANIFEST_SHA256
            ),
            "status_changes": {},
            "runtime_after": runtime,
            "blockers_after": {},
            "blocker_resolution_ids_after": [],
            "source_checkpoint_version": (
                graph.FP008_ADMIN_REVIEW_SOURCE_CHECKPOINT_VERSION
            ),
            "evidence_refs": [],
            "previous_event_sha256": state["transition_history"][49][
                "event_sha256"
            ],
        }
        event["event_sha256"] = graph.continuation.event_sha256(event)
        state["transition_history"].append(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]
        state["validation_cutoff_at"] = occurred_at

        current = checkpoint["current_work"]
        current.update(graph.FP008_ADMIN_REVIEW_CURRENT_WORK_SAFE_FIELDS)
        current["status"] = "READY"
        current["current_focus"] = "EPIC-03 READY; FP046 PLANNED_NEXT"
        current["next_action"] = (
            "continue the next repository-scoped implementation item"
        )
        current["deferred_release_gate_ids"] = copy.deepcopy(
            graph.FP008_ADMIN_REVIEW_DEFERRED_RELEASE_GATE_IDS
        )
        handoff = checkpoint["session_handoff"]
        handoff.update(graph.FP008_ADMIN_REVIEW_HANDOFF_SAFE_FIELDS)
        handoff["current_epic"] = "EPIC-03 / FP046 PLANNED_NEXT"
        handoff["last_updated_by_work_item"] = (
            "EPIC-03-FUTURE-SAFE-PROGRESS"
        )
        handoff["next_single_action"] = current["next_action"]
        return checkpoint
    if len(history) != 48:
        raise AssertionError("source checkpoint is not seq48 or FP008 seq50+")
    from scripts import (  # noqa: PLC0415
        apply_walksafe_fp008_goal_completed_seq49_50_20260809 as apply,
    )

    controlled = {
        relative: sha256_file(ROOT / relative)
        for relative in apply.CONTROLLED_CHECKER_DELTA_SHA256_BY_PATH
    }
    with mock.patch.dict(
        apply.CONTROLLED_CHECKER_DELTA_SHA256_BY_PATH,
        controlled,
        clear=True,
    ):
        prepared = apply.prepare_projection(
            ROOT,
            continuation_checker=lambda *_: [],
            goal_graph_checker=lambda *_: [],
        )
    return prepared.projected_checkpoint


def enter_fp008_historical_physical_bindings(
    test: unittest.TestCase,
) -> object:
    historical_rtm = mock.Mock()
    historical_rtm.stat.return_value.st_size = (
        graph.FP008_REQUIREMENTS_TRACEABILITY_BYTE_COUNT
    )
    original_exact = graph._exact_repo_file
    original_sha256 = graph.continuation.sha256_file
    original_filter = graph.filter_frozen_v23_successor_errors

    def exact_file(root: Path, relative: object) -> Path | object | None:
        if relative == graph.FP008_REQUIREMENTS_TRACEABILITY_BINDING["path"]:
            return historical_rtm
        return original_exact(root, relative)

    def exact_sha256(path: object) -> str:
        if path is historical_rtm:
            return graph.FP008_REQUIREMENTS_TRACEABILITY_BINDING["sha256"]
        return original_sha256(path)

    def focused_successor_filter(
        root: Path,
        errors: list[str],
        *,
        checkpoint: dict | None = None,
        archive: dict | None = None,
    ) -> list[str]:
        focused = [
            error
            for error in errors
            if graph.FROZEN_CHANGED_ARTIFACT_ERROR_RE.fullmatch(error) is None
        ]
        return original_filter(
            root,
            focused,
            checkpoint=checkpoint,
            archive=archive,
        )

    test.enterContext(
        mock.patch.object(graph, "_exact_repo_file", side_effect=exact_file)
    )
    test.enterContext(
        mock.patch.object(
            graph.continuation,
            "sha256_file",
            side_effect=exact_sha256,
        )
    )
    test.enterContext(
        mock.patch.object(
            graph,
            "filter_frozen_v23_successor_errors",
            side_effect=focused_successor_filter,
        )
    )
    return historical_rtm


def fp046_seq55_checkpoint() -> dict:
    checkpoint = load_json(ROOT / graph.CHECKPOINT_RELATIVE)
    history = checkpoint["goal_execution"]["transition_history"]
    if (
        len(history) >= 55
        and history[53].get("event_id")
        == graph.FP046_CANONICAL_UPDATE_EVENT_ID
        and history[54].get("event_id") == graph.FP046_COMPLETION_EVENT_ID
    ):
        return checkpoint
    if len(history) != 53:
        raise AssertionError("source checkpoint is not seq53 or FP046 seq55+")
    from scripts import (  # noqa: PLC0415
        apply_walksafe_fp046_goal_completed_seq54_55_20260810 as apply,
    )

    controlled = {
        relative: sha256_file(ROOT / relative)
        for relative in apply.CONTROLLED_CHECKER_DELTA_SHA256_BY_PATH
    }
    for relative in (
        Path("scripts/check_walksafe_goal_graph_v2_4.py"),
        Path("tests/test_walksafe_goal_graph_v2_4.py"),
    ):
        controlled[relative] = sha256_file(ROOT / relative)
    with mock.patch.dict(
        apply.CONTROLLED_CHECKER_DELTA_SHA256_BY_PATH,
        controlled,
        clear=True,
    ):
        prepared = apply.prepare_projection(
            ROOT,
            continuation_checker=lambda *_: [],
            goal_graph_checker=lambda *_: [],
        )
    return prepared.projected_checkpoint


class WalkSafeFp046R014SuccessorRegressionTest(unittest.TestCase):
    def test_seq55_r014_authority_binds_exact_zero_credit_successors(
        self,
    ) -> None:
        errors, artifact_bindings, transitions = (
            graph.validate_fp046_r014_successor_authority(
                ROOT,
                fp046_seq55_checkpoint(),
            )
        )

        self.assertEqual(errors, [])
        self.assertEqual(len(artifact_bindings), 6)
        self.assertEqual(len(transitions), 67)
        self.assertEqual(
            artifact_bindings,
            {
                binding["path"]: binding["sha256"]
                for binding in (
                    graph.FP046_R014_ARTIFACT_BINDING_BY_ROLE.values()
                )
            },
        )
        npc_successors = (
            graph._npc_single_admin_recovery_live_compatibility_artifacts(
                ROOT,
                fp046_seq55_checkpoint(),
            )
        )
        self.assertIsInstance(npc_successors, dict)
        for relative, (before_sha256, after_sha256) in transitions.items():
            self.assertNotEqual(before_sha256, after_sha256)
            successor = npc_successors.get(relative)
            expected_live = (
                successor[1]
                if successor is not None and successor[0] == after_sha256
                else after_sha256
            )
            self.assertEqual(sha256_file(ROOT / relative), expected_live)

    def test_seq55_r014_authority_rejects_pinned_ledger_drift(
        self,
    ) -> None:
        checkpoint = fp046_seq55_checkpoint()
        ledger = ROOT / graph.FP046_R014_BINDING_BY_ROLE[
            "EXACT257_R014_LEDGER"
        ]["path"]
        original_sha256 = graph.continuation.sha256_file

        def drift(path: Path) -> str:
            if path == ledger:
                return "0" * 64
            return original_sha256(path)

        with mock.patch.object(
            graph.continuation,
            "sha256_file",
            side_effect=drift,
        ):
            errors, artifact_bindings, transitions = (
                graph.validate_fp046_r014_successor_authority(
                    ROOT,
                    checkpoint,
                )
            )

        self.assertEqual(
            errors,
            ["FP046 R014 EXACT257_R014_LEDGER physical binding differs"],
        )
        self.assertEqual(artifact_bindings, {})
        self.assertEqual(transitions, {})

    def test_seq55_projected_graph_accepts_complete_successor_lineage(
        self,
    ) -> None:
        checkpoint = fp046_seq55_checkpoint()
        npc = graph._npc_single_admin_recovery_live_compatibility_artifacts(
            ROOT,
            checkpoint,
        )
        self.assertIsInstance(npc, dict)
        successors = npc
        with tempfile.TemporaryDirectory() as temporary:
            checkpoint_path = Path(temporary) / "fp046-seq55.json"
            checkpoint_path.write_bytes(json_bytes(checkpoint))
            checkpoint_relative = Path("fp046-seq55-fixture.json")
            original_resolve_repo_file = graph.continuation.resolve_repo_file

            def resolve_repo_file(root: Path, relative: Path) -> Path | None:
                if relative == checkpoint_relative:
                    return checkpoint_path
                return original_resolve_repo_file(root, relative)

            original_filter = graph.filter_frozen_v23_successor_errors

            def product_only_filter(root, errors, **kwargs):
                return original_filter(
                    root,
                    [
                        error
                        for error in errors
                        if graph.FROZEN_CHANGED_ARTIFACT_ERROR_RE.fullmatch(
                            error
                        )
                        is None
                    ],
                    **kwargs,
                )

            completion_context = synthetic_approved_r008_context()
            with (
                mock.patch.object(
                    graph,
                    "_npc_single_admin_recovery_live_compatibility_artifacts",
                    return_value=successors,
                ),
                mock.patch.object(
                    graph,
                    "_npc_single_admin_recovery_sealed_product_successor_artifacts",
                    return_value=npc,
                ),
                mock.patch.object(
                    graph,
                    "filter_frozen_v23_successor_errors",
                    side_effect=product_only_filter,
                ),
                mock.patch.object(
                    completion_review,
                    "validated_control_successor_r008_context",
                    return_value=completion_context,
                ),
                mock.patch.object(
                    completion_review,
                    "validate_post_review",
                    return_value=completion_context.current,
                ),
                mock.patch.object(
                    graph.continuation,
                    "resolve_repo_file",
                    side_effect=resolve_repo_file,
                ),
            ):
                self.assertEqual(
                    graph.validate(
                        ROOT,
                        checkpoint_relative,
                        check_continuation=False,
                    ),
                    [],
                )


class WalkSafeHistoricalBindingRegressionTest(unittest.TestCase):
    def _copied_git_witness_root(self) -> Path:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        source = ROOT / graph.HISTORICAL_GIT_WITNESS_ROOT_RELATIVE
        target = root / graph.HISTORICAL_GIT_WITNESS_ROOT_RELATIVE
        shutil.copytree(source, target)
        return root

    def test_historical_git_witness_is_exact_and_history_stays_absent(
        self,
    ) -> None:
        archive = load_json(ROOT / ARCHIVE_RELATIVE)
        errors, lookup = graph.validate_historical_git_witness(ROOT, archive)
        base_check = subprocess.run(
            [
                "git",
                "cat-file",
                "-e",
                f"{graph.HISTORICAL_GIT_WITNESS_COMMIT}^{{commit}}",
            ],
            cwd=ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )

        self.assertEqual(errors, [])
        self.assertEqual(len(lookup), 65)
        self.assertEqual(sum(state[0] for state in lookup.values()), 15)
        self.assertEqual(base_check.returncode, 128)

    def test_historical_git_witness_rejects_manifest_tamper(self) -> None:
        root = self._copied_git_witness_root()
        manifest = root / graph.HISTORICAL_GIT_WITNESS_MANIFEST_RELATIVE
        manifest.write_bytes(manifest.read_bytes() + b"\n")

        errors, lookup = graph.validate_historical_git_witness(root)

        self.assertEqual(
            errors,
            ["historical Git witness manifest SHA-256 differs"],
        )
        self.assertEqual(lookup, {})

    def test_historical_git_witness_rejects_missing_blob(self) -> None:
        root = self._copied_git_witness_root()
        manifest = load_json(
            root / graph.HISTORICAL_GIT_WITNESS_MANIFEST_RELATIVE
        )
        blob = next(
            row for row in manifest["objects"] if row["object_type"] == "blob"
        )
        (root / blob["fixture_path"]).unlink()

        errors, lookup = graph.validate_historical_git_witness(root)

        self.assertIn(
            f"historical Git witness object is missing: {blob['object_id']}",
            errors,
        )
        self.assertEqual(lookup, {})

    def test_historical_git_witness_rejects_third_digest(self) -> None:
        root = self._copied_git_witness_root()
        manifest_path = root / graph.HISTORICAL_GIT_WITNESS_MANIFEST_RELATIVE
        manifest = load_json(manifest_path)
        blob = next(
            row for row in manifest["objects"] if row["object_type"] == "blob"
        )
        third_payload = b"walksafe-third-digest-must-fail-closed\n"
        encoded = base64.b64encode(gzip.compress(third_payload, mtime=0)) + b"\n"
        (root / blob["fixture_path"]).write_bytes(encoded)
        blob.update(
            {
                "raw_byte_count": len(third_payload),
                "raw_sha256": hashlib.sha256(third_payload).hexdigest(),
                "fixture_byte_count": len(encoded),
                "fixture_sha256": hashlib.sha256(encoded).hexdigest(),
            }
        )
        manifest_path.write_bytes(json_bytes(manifest))
        tampered_manifest_sha256 = sha256_file(manifest_path)

        with mock.patch.object(
            graph,
            "HISTORICAL_GIT_WITNESS_MANIFEST_SHA256",
            tampered_manifest_sha256,
        ):
            errors, lookup = graph.validate_historical_git_witness(root)

        self.assertIn(
            f"historical Git witness raw object differs: {blob['object_id']}",
            errors,
        )
        self.assertEqual(lookup, {})

    def _copied_preimage_root(self) -> Path:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        source = (
            ROOT
            / graph.CANONICAL_PREIMAGE_MANIFEST_RELATIVE
        ).parent.parent
        target = (
            root
            / graph.CANONICAL_PREIMAGE_MANIFEST_RELATIVE
        ).parent.parent
        shutil.copytree(source, target)
        return root

    def test_canonical_preimage_archive_maps_exact_five_roles(
        self,
    ) -> None:
        errors, lookup = graph.validate_canonical_preimage_archive(ROOT)
        expected = {
            (
                requirement["live_path"],
                requirement["historical_sha256"],
            )
            for requirement in graph.CANONICAL_PREIMAGE_EXPECTED.values()
        }

        self.assertEqual(errors, [])
        self.assertEqual(set(lookup), expected)
        self.assertEqual(len(lookup), 5)
        self.assertNotIn(
            graph.REQUIREMENTS_TRACEABILITY_LIVE_SHA256,
            {digest for _, digest in lookup},
        )
        for pair, path in lookup.items():
            self.assertEqual(sha256_file(path), pair[1])

    def test_canonical_binding_resolver_separates_live_and_history(
        self,
    ) -> None:
        errors, lookup = graph.validate_canonical_preimage_archive(ROOT)
        self.assertEqual(errors, [])
        requirement = graph.CANONICAL_PREIMAGE_EXPECTED[
            "ARTIFACT_REGISTER"
        ]
        live = ROOT / requirement["live_path"]
        live_sha256 = sha256_file(live)

        self.assertEqual(
            graph.resolve_canonical_binding_file(
                ROOT,
                requirement["live_path"],
                live_sha256,
                mode="CURRENT_LIVE",
                preimages=lookup,
            ),
            live,
        )
        self.assertIsNone(
            graph.resolve_canonical_binding_file(
                ROOT,
                requirement["live_path"],
                requirement["historical_sha256"],
                mode="CURRENT_LIVE",
                preimages=lookup,
            )
        )
        self.assertEqual(
            graph.resolve_canonical_binding_file(
                ROOT,
                requirement["live_path"],
                requirement["historical_sha256"],
                mode="SEALED_HISTORY",
                preimages=lookup,
            ),
            lookup[
                (
                    requirement["live_path"],
                    requirement["historical_sha256"],
                )
            ],
        )
        for mode, path, digest in (
            ("UNKNOWN", requirement["live_path"], live_sha256),
            ("SEALED_HISTORY", "../artifact-register.json", live_sha256),
            ("SEALED_HISTORY", requirement["live_path"], "0" * 64),
            ("SEALED_HISTORY", requirement["live_path"], "invalid"),
        ):
            with self.subTest(mode=mode, path=path, digest=digest):
                self.assertIsNone(
                    graph.resolve_canonical_binding_file(
                        ROOT,
                        path,
                        digest,
                        mode=mode,
                        preimages=lookup,
                    )
                )

    def test_canonical_preimage_archive_rejects_tamper_and_symlink(
        self,
    ) -> None:
        requirement = graph.CANONICAL_PREIMAGE_EXPECTED[
            "ARTIFACT_CHANGE_LOG"
        ]
        blob_relative = (
            "docs/control/history/canonical-preimages/sha256/"
            f"{requirement['historical_sha256']}.json"
        )

        root = self._copied_preimage_root()
        (root / blob_relative).write_bytes(b"{}\n")
        errors, lookup = graph.validate_canonical_preimage_archive(root)
        self.assertTrue(errors)
        self.assertNotIn(
            (
                requirement["live_path"],
                requirement["historical_sha256"],
            ),
            lookup,
        )

        root = self._copied_preimage_root()
        blob = root / blob_relative
        payload = blob.with_name("preserved-payload.json")
        payload.write_bytes(blob.read_bytes())
        blob.unlink()
        blob.symlink_to(payload.name)
        errors, lookup = graph.validate_canonical_preimage_archive(root)
        self.assertIn(
            "canonical preimage blob uses a symlink: "
            "ARTIFACT_CHANGE_LOG",
            errors,
        )
        self.assertNotIn(
            (
                requirement["live_path"],
                requirement["historical_sha256"],
            ),
            lookup,
        )

        root = self._copied_preimage_root()
        manifest = root / graph.CANONICAL_PREIMAGE_MANIFEST_RELATIVE
        payload = manifest.with_name("preserved-manifest.json")
        payload.write_bytes(manifest.read_bytes())
        manifest.unlink()
        manifest.symlink_to(payload.name)
        errors, lookup = graph.validate_canonical_preimage_archive(root)
        self.assertEqual(
            errors,
            ["canonical preimage manifest uses a symlink"],
        )
        self.assertEqual(lookup, {})

    def test_canonical_preimage_archive_rejects_identity_and_membership(
        self,
    ) -> None:
        original = graph.continuation.load_json
        target_digest = graph.CANONICAL_PREIMAGE_EXPECTED[
            "ARTIFACT_CHANGE_LOG"
        ]["historical_sha256"]

        def wrong_identity(path: Path) -> dict:
            document = original(path)
            if path.name == f"{target_digest}.json":
                document = copy.deepcopy(document)
                document["metadata"]["register_id"] = "WRONG"
            return document

        with mock.patch.object(
            graph.continuation,
            "load_json",
            side_effect=wrong_identity,
        ):
            errors, lookup = graph.validate_canonical_preimage_archive(ROOT)
        self.assertIn(
            "canonical preimage document identity differs: "
            "ARTIFACT_CHANGE_LOG",
            errors,
        )
        self.assertEqual(len(lookup), 4)

        manifest_path = ROOT / graph.CANONICAL_PREIMAGE_MANIFEST_RELATIVE

        def duplicate_entry(path: Path) -> dict:
            document = original(path)
            if path == manifest_path:
                document = copy.deepcopy(document)
                document["entries"][-1] = copy.deepcopy(
                    document["entries"][0]
                )
            return document

        with mock.patch.object(
            graph.continuation,
            "load_json",
            side_effect=duplicate_entry,
        ):
            errors, lookup = graph.validate_canonical_preimage_archive(ROOT)
        self.assertTrue(
            any(
                "role order or membership differs" in error
                for error in errors
            )
        )
        self.assertLess(len(lookup), 5)

    def test_android_report_packet_is_exact_zero_credit_binding(
        self,
    ) -> None:
        checkpoint = load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        errors, bindings = (
            graph.validate_phase1_android_report_successor_binding(
                ROOT,
                checkpoint,
            )
        )
        fp046_errors, _artifacts, fp046_transitions = (
            graph.validate_fp046_r014_successor_authority(
                ROOT,
                checkpoint,
            )
        )
        self.assertEqual(errors, [])
        self.assertEqual(fp046_errors, [])
        self.assertEqual(len(bindings), 32)
        completion_transitions = (
            graph._npc_single_admin_recovery_live_compatibility_artifacts(
                ROOT,
                checkpoint,
            )
        )
        self.assertIsInstance(completion_transitions, dict)
        for relative, bridge in (
            graph.PHASE1_ANDROID_REPORT_SUCCESSOR_BRIDGES.items()
        ):
            fp048 = graph.FP048_ANDROID_REPORT_SUCCESSOR_BY_PATH.get(
                relative
            )
            current_sha256 = (
                fp048["after_sha256"]
                if fp048 is not None
                else bridge["current_sha256"]
            )
            successor = fp046_transitions.get(relative)
            expected_live = (
                successor[1]
                if successor is not None and successor[0] == current_sha256
                else current_sha256
            )
            completion_successor = completion_transitions.get(relative)
            expected_live = (
                completion_successor[1]
                if completion_successor is not None
                and completion_successor[0] == expected_live
                else expected_live
            )
            self.assertEqual(bindings[relative], expected_live)
            self.assertEqual(
                graph._phase1_android_report_successor_bridge(
                    ROOT,
                    checkpoint,
                    relative,
                    bridge["predecessor_sha256"],
                    current_bindings=bindings,
                ),
                (
                    bridge["predecessor_sha256"],
                    expected_live,
                ),
            )
            self.assertIsNone(
                graph._phase1_android_report_successor_bridge(
                    ROOT,
                    checkpoint,
                    relative,
                    "0" * 64,
                    current_bindings=bindings,
                )
            )

    def test_android_report_fp048_successor_is_exact_five_path_chain(
        self,
    ) -> None:
        checkpoint = load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        packet = load_json(
            ROOT / graph.PHASE1_ANDROID_REPORT_SUCCESSOR_EVIDENCE_RELATIVE
        )
        binding = packet["source_test_binding"]
        packet_bindings = {
            row["exact_path"]: row["sha256"]
            for row in binding["sources"] + binding["tests"]
        }
        successors = graph._fp048_android_report_successor_bindings(
            ROOT,
            checkpoint,
            packet_bindings,
            require_live_after=False,
        )
        fp046_errors, _artifacts, fp046_transitions = (
            graph.validate_fp046_r014_successor_authority(
                ROOT,
                checkpoint,
            )
        )
        self.assertEqual(fp046_errors, [])
        self.assertIsInstance(successors, dict)
        self.assertEqual(
            set(successors),
            set(graph.FP048_ANDROID_REPORT_SUCCESSOR_BY_PATH),
        )
        self.assertEqual(len(successors), 5)
        completion_transitions = (
            graph._npc_single_admin_recovery_live_compatibility_artifacts(
                ROOT,
                checkpoint,
            )
        )
        self.assertIsInstance(completion_transitions, dict)
        for relative, transition in (
            graph.FP048_ANDROID_REPORT_SUCCESSOR_BY_PATH.items()
        ):
            self.assertEqual(
                packet_bindings[relative],
                transition["before_sha256"],
            )
            self.assertEqual(
                successors[relative],
                transition["after_sha256"],
            )
            self.assertEqual(
                fp046_transitions[relative],
                (
                    transition["after_sha256"],
                    (
                        completion_transitions[relative][0]
                        if relative in completion_transitions
                        else sha256_file(ROOT / relative)
                    ),
                ),
            )
            if relative in completion_transitions:
                self.assertEqual(
                    completion_transitions[relative][1],
                    sha256_file(ROOT / relative),
                )

    def test_android_report_fp048_successor_rejects_mixed_and_unlisted_live_drift(
        self,
    ) -> None:
        checkpoint = load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        packet = load_json(
            ROOT / graph.PHASE1_ANDROID_REPORT_SUCCESSOR_EVIDENCE_RELATIVE
        )
        rows = (
            packet["source_test_binding"]["sources"]
            + packet["source_test_binding"]["tests"]
        )
        authorized = next(
            iter(graph.FP048_ANDROID_REPORT_SUCCESSOR_BY_PATH)
        )
        unlisted = next(
            row["exact_path"]
            for row in rows
            if row["exact_path"]
            not in graph.FP048_ANDROID_REPORT_SUCCESSOR_BY_PATH
        )
        original_sha256 = graph.continuation.sha256_file
        for label, relative, digest in (
            (
                "mixed",
                authorized,
                graph.FP048_ANDROID_REPORT_SUCCESSOR_BY_PATH[authorized][
                    "before_sha256"
                ],
            ),
            ("unlisted", unlisted, "0" * 64),
        ):
            with self.subTest(label=label):
                target = ROOT / relative

                def drift(path: Path) -> str:
                    if path == target:
                        return digest
                    return original_sha256(path)

                with mock.patch.object(
                    graph.continuation,
                    "sha256_file",
                    side_effect=drift,
                ):
                    errors, bindings = (
                        graph.validate_phase1_android_report_successor_binding(
                            ROOT,
                            checkpoint,
                        )
                    )
                self.assertTrue(errors)
                self.assertEqual(bindings, {})

    def test_android_report_fp048_successor_rejects_after_and_review_tamper(
        self,
    ) -> None:
        checkpoint = load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        implementation_path = (
            graph.FP048_ANDROID_REPORT_RESULT_PATH_BY_KIND[
                "IMPLEMENTATION_RECORD"
            ]
        )
        original_load = graph._load_exact_json

        def wrong_after(root: Path, relative: object) -> dict | None:
            document = original_load(root, relative)
            if document is None:
                return None
            result = copy.deepcopy(document)
            if relative == implementation_path:
                target = next(
                    row
                    for row in result["changed_artifacts"]
                    if row["path"]
                    in graph.FP048_ANDROID_REPORT_SUCCESSOR_BY_PATH
                )
                target["after_sha256"] = "0" * 64
            return result

        with mock.patch.object(
            graph,
            "_load_exact_json",
            side_effect=wrong_after,
        ):
            errors, bindings = (
                graph.validate_phase1_android_report_successor_binding(
                    ROOT,
                    checkpoint,
                )
            )
        self.assertIn(
            "phase1 Android report FP048 successor evidence differs",
            errors,
        )
        self.assertEqual(bindings, {})

        packet = load_json(
            ROOT / graph.PHASE1_ANDROID_REPORT_SUCCESSOR_EVIDENCE_RELATIVE
        )
        packet_rows = (
            packet["source_test_binding"]["sources"]
            + packet["source_test_binding"]["tests"]
        )
        unlisted = next(
            row["exact_path"]
            for row in packet_rows
            if row["exact_path"]
            not in graph.FP048_ANDROID_REPORT_SUCCESSOR_BY_PATH
        )

        def wrong_path(root: Path, relative: object) -> dict | None:
            document = original_load(root, relative)
            if document is None:
                return None
            result = copy.deepcopy(document)
            if relative == implementation_path:
                target = next(
                    row
                    for row in result["changed_artifacts"]
                    if row["path"]
                    not in graph.FP048_ANDROID_REPORT_SUCCESSOR_BY_PATH
                )
                target["path"] = unlisted
            return result

        with mock.patch.object(
            graph,
            "_load_exact_json",
            side_effect=wrong_path,
        ):
            errors, bindings = (
                graph.validate_phase1_android_report_successor_binding(
                    ROOT,
                    checkpoint,
                )
            )
        self.assertIn(
            "phase1 Android report FP048 successor evidence differs",
            errors,
        )
        self.assertEqual(bindings, {})

        original_sha256 = graph.continuation.sha256_file
        for relative in (
            graph.FP048_ANDROID_REPORT_COMPLETION_PATH,
            graph.FP048_ANDROID_REPORT_REVIEW_SUBJECT_PATH,
            graph.FP048_ANDROID_REPORT_REVIEW_ATTESTATION_PATH,
            graph.FP048_ANDROID_REPORT_REVIEW_PATH,
        ):
            with self.subTest(relative=relative):
                target = ROOT / relative

                def wrong_evidence(path: Path) -> str:
                    if path == target:
                        return "0" * 64
                    return original_sha256(path)

                with mock.patch.object(
                    graph.continuation,
                    "sha256_file",
                    side_effect=wrong_evidence,
                ):
                    errors, bindings = (
                        graph.validate_phase1_android_report_successor_binding(
                            ROOT,
                            checkpoint,
                        )
                    )
                self.assertIn(
                    "phase1 Android report FP048 successor evidence differs",
                    errors,
                )
                self.assertEqual(bindings, {})

    def test_current_nonfrozen_goal_graph_accepts_sealed_fp048_suffix(
        self,
    ) -> None:
        context = synthetic_approved_r008_context()
        checkpoint = r008_snapshot_projection(
            load_json(ROOT / graph.CHECKPOINT_RELATIVE), context
        )
        with (
            mock.patch.object(
                completion_review,
                "validated_control_successor_r008_context",
                return_value=context,
            ),
            mock.patch.object(
                completion_review,
                "validate_post_review",
                return_value=context.current,
            ),
            mock.patch.object(
                graph.continuation,
                "load_json",
                side_effect=r008_checkpoint_loader(checkpoint),
            ),
        ):
            self.assertEqual(
                graph.validate(
                    ROOT,
                    check_continuation=False,
                    run_frozen_semantics=False,
                ),
                [],
            )

    def test_android_report_packet_rejects_hash_and_boundary_tamper(
        self,
    ) -> None:
        evidence = (
            ROOT
            / graph.PHASE1_ANDROID_REPORT_SUCCESSOR_EVIDENCE_RELATIVE
        )
        original_sha256 = graph.continuation.sha256_file

        def wrong_hash(path: Path) -> str:
            if path == evidence:
                return "0" * 64
            return original_sha256(path)

        with mock.patch.object(
            graph.continuation,
            "sha256_file",
            side_effect=wrong_hash,
        ):
            errors, bindings = (
                graph.validate_phase1_android_report_successor_binding(ROOT)
            )
        self.assertIn(
            "phase1 Android report successor evidence SHA-256 differs",
            errors,
        )
        self.assertEqual(bindings, {})

        original_load = graph.continuation.load_json

        def wrong_boundary(path: Path) -> dict:
            document = original_load(path)
            if path == evidence:
                document = copy.deepcopy(document)
                document["claim_boundary"]["product_release_claimed"] = True
            return document

        with mock.patch.object(
            graph.continuation,
            "load_json",
            side_effect=wrong_boundary,
        ):
            errors, bindings = (
                graph.validate_phase1_android_report_successor_binding(ROOT)
            )
        self.assertIn(
            "phase1 Android report successor claim boundary differs",
            errors,
        )
        self.assertEqual(bindings, {})

    def test_phone_mounting_supplement_binds_only_exact_state_pair(
        self,
    ) -> None:
        errors, bridge = (
            graph.validate_phone_mounting_current_state_successor_binding(
                ROOT
            )
        )
        self.assertEqual(errors, [])
        self.assertEqual(
            bridge,
            (
                graph.PHONE_MOUNTING_SUCCESSOR_PREDECESSOR_SHA256,
                graph.PHONE_MOUNTING_SUCCESSOR_CURRENT_SHA256,
            ),
        )
        self.assertEqual(
            graph._phone_mounting_current_state_successor_bridge(
                ROOT,
                graph.PHONE_MOUNTING_SUCCESSOR_PATH,
                graph.PHONE_MOUNTING_SUCCESSOR_PREDECESSOR_SHA256,
            ),
            bridge,
        )
        for relative, digest in (
            ("README.md", graph.PHONE_MOUNTING_SUCCESSOR_PREDECESSOR_SHA256),
            (graph.PHONE_MOUNTING_SUCCESSOR_PATH, "0" * 64),
        ):
            with self.subTest(relative=relative, digest=digest):
                self.assertIsNone(
                    graph._phone_mounting_current_state_successor_bridge(
                        ROOT,
                        relative,
                        digest,
                    )
                )

    def test_phone_mounting_supplement_rejects_review_and_anchor_tamper(
        self,
    ) -> None:
        review = ROOT / graph.PHONE_MOUNTING_SUCCESSOR_REVIEW_RELATIVE
        original_sha256 = graph.continuation.sha256_file

        def wrong_review(path: Path) -> str:
            if path == review:
                return "0" * 64
            return original_sha256(path)

        with mock.patch.object(
            graph.continuation,
            "sha256_file",
            side_effect=wrong_review,
        ):
            errors, bridge = (
                graph.validate_phone_mounting_current_state_successor_binding(
                    ROOT
                )
            )
        self.assertIn(
            "phone mounting successor review SHA-256 differs",
            errors,
        )
        self.assertIsNone(bridge)

        anchor = (
            ROOT
            / "docs/control/execution/goal-results/"
            "WS-GOAL-EPIC-02-FP-015-R001/implementation-record.json"
        )

        def wrong_anchor(path: Path) -> str:
            if path == anchor:
                return "0" * 64
            return original_sha256(path)

        with mock.patch.object(
            graph.continuation,
            "sha256_file",
            side_effect=wrong_anchor,
        ):
            errors, bridge = (
                graph.validate_phone_mounting_current_state_successor_binding(
                    ROOT
                )
            )
        self.assertTrue(
            any(
                "anchor binding differs" in error
                for error in errors
            )
        )
        self.assertIsNone(bridge)

    def test_resource_pilot_packet_binds_only_four_exact_state_pairs(
        self,
    ) -> None:
        errors, bindings = (
            graph.validate_resource_pilot_current_state_successor_binding(
                ROOT
            )
        )
        checkpoint = load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        self.assertEqual(errors, [])
        self.assertEqual(len(bindings), 4)
        for relative, bridge in (
            graph.RESOURCE_PILOT_SUCCESSOR_BRIDGES.items()
        ):
            expected = (
                bridge["predecessor_sha256"],
                bridge["current_sha256"],
            )
            self.assertEqual(bindings[relative], expected)
            self.assertEqual(
                graph._resource_pilot_current_state_successor_bridge(
                    ROOT,
                    checkpoint,
                    relative,
                    bridge["predecessor_sha256"],
                    current_bindings=bindings,
                ),
                expected,
            )
            self.assertIsNone(
                graph._resource_pilot_current_state_successor_bridge(
                    ROOT,
                    checkpoint,
                    relative,
                    "0" * 64,
                    current_bindings=bindings,
                )
            )
        network_policy = (
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
            "network/AndroidNetworkTransferPolicy.kt"
        )
        self.assertIsNone(
            graph._resource_pilot_current_state_successor_bridge(
                ROOT,
                checkpoint,
                network_policy,
                (
                    "5579d99f737f434d050ad5fe79b14d15d7b1f86f8a86df58"
                    "c4963708c50b0502"
                ),
                current_bindings=bindings,
            )
        )

    def test_resource_pilot_packet_rejects_review_and_boundary_tamper(
        self,
    ) -> None:
        review = ROOT / graph.RESOURCE_PILOT_SUCCESSOR_REVIEW_RELATIVE
        original_sha256 = graph.continuation.sha256_file

        def wrong_review(path: Path) -> str:
            if path == review:
                return "0" * 64
            return original_sha256(path)

        with mock.patch.object(
            graph.continuation,
            "sha256_file",
            side_effect=wrong_review,
        ):
            errors, bindings = (
                graph.validate_resource_pilot_current_state_successor_binding(
                    ROOT
                )
            )
        self.assertIn(
            "resource pilot successor review SHA-256 differs",
            errors,
        )
        self.assertEqual(bindings, {})

        boundary_path = ROOT / graph.RESOURCE_PILOT_SUCCESSOR_BOUNDARY_PATH
        original_load = graph.continuation.load_json

        def wrong_boundary(path: Path) -> dict:
            document = original_load(path)
            if path == boundary_path:
                document = copy.deepcopy(document)
                document["admission_decision"]["release_credit"] = True
            return document

        with mock.patch.object(
            graph.continuation,
            "load_json",
            side_effect=wrong_boundary,
        ):
            errors, bindings = (
                graph.validate_resource_pilot_current_state_successor_binding(
                    ROOT
                )
            )
        self.assertIn(
            "resource pilot successor boundary contract differs",
            errors,
        )
        self.assertEqual(bindings, {})

    def test_resource_pilot_packet_rejects_snapshot_and_manifest_tamper(
        self,
    ) -> None:
        original_load = graph.continuation.load_json
        snapshot_path = (
            ROOT / graph.RESOURCE_PILOT_SUCCESSOR_FP047_SNAPSHOT_PATH
        )
        manifest_path = ROOT / graph.RESOURCE_PILOT_SUCCESSOR_MANIFEST_PATH

        for target, mutate, expected_error in (
            (
                snapshot_path,
                lambda document: document["dirty_snapshot"]["paths"][93][
                    "worktree"
                ].update({"sha256": "0" * 64}),
                "resource pilot successor FP047 row differs: "
                "apps/android/USER_GUIDE.md",
            ),
            (
                manifest_path,
                lambda document: document["source_set"]["entries"][7].update(
                    {"sha256": "0" * 64}
                ),
                "resource pilot successor manifest row differs: "
                "apps/android/USER_GUIDE.md",
            ),
        ):
            with self.subTest(target=target):
                def tampered(path: Path) -> dict:
                    document = original_load(path)
                    if path == target:
                        document = copy.deepcopy(document)
                        mutate(document)
                    return document

                with mock.patch.object(
                    graph.continuation,
                    "load_json",
                    side_effect=tampered,
                ):
                    errors, bindings = (
                        graph
                        .validate_resource_pilot_current_state_successor_binding(
                            ROOT
                        )
                    )
                self.assertIn(expected_error, errors)
                self.assertEqual(bindings, {})


class WalkSafeGoalGraphV24CurrentSessionTest(unittest.TestCase):
    def fixture(
        self,
        root: Path,
    ) -> tuple[dict, Path, str, dict]:
        goal_id = "WS-GOAL-EPIC-02-FP-011-R001"
        event_id = (
            "WS-GOAL-GRAPH-V2-4-WORK-SESSION-RESUMED-"
            "FP011-20260725-TEST"
        )
        event_dir = (
            root / "docs/control/execution/goal-gates" / event_id
        )
        event_dir.mkdir(parents=True)
        repository_payload = {"evidence_type": "GATE_REPOSITORY_STATE"}
        repository_relative = (
            f"docs/control/execution/goal-gates/{event_id}/"
            "19-REPOSITORY_STATE.log"
        )
        repository_sha256 = write_json(
            root,
            repository_relative,
            repository_payload,
        )
        receipt_relative = (
            f"docs/control/execution/goal-gates/{event_id}/"
            "implementation-resume-gate-receipt.json"
        )
        receipt = {
            "document_id": "TEST-V24-FP011-RESUME-GATE",
            "check_runs": [
                {
                    "check_id": "REPOSITORY_STATE",
                    "output_path": repository_relative,
                    "output_sha256": repository_sha256,
                }
            ],
        }
        receipt_sha256 = write_json(root, receipt_relative, receipt)
        checkpoint = {
            "goal_execution": {
                "focus_goal_id": goal_id,
                "status_by_goal": {goal_id: "IN_PROGRESS"},
                "transition_history": [
                    {
                        "event_type": "GOAL_STARTED",
                        "subject_goal_id": goal_id,
                        "event_sha256": "a" * 64,
                    },
                    {
                        "event_id": event_id,
                        "event_type": "WORK_SESSION_RESUMED",
                        "subject_goal_id": goal_id,
                        "event_sha256": "b" * 64,
                        "previous_execution_session_event_sha256": "a" * 64,
                        "implementation_start_gate_binding": {
                            "document_id": receipt["document_id"],
                            "path": receipt_relative,
                            "file_sha256": receipt_sha256,
                        },
                    },
                ],
            }
        }
        checkpoint_file = (
            root / "docs/control/walksafe-project-continuation-checkpoint.json"
        )
        return checkpoint, checkpoint_file, event_id, repository_payload

    def test_current_resume_session_binds_live_repository_state(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            checkpoint, checkpoint_file, event_id, payload = self.fixture(
                root
            )
            with mock.patch.object(
                graph.continuation,
                "capture_gate_repository_state",
                return_value=payload,
            ):
                self.assertEqual(
                    graph.validate_current_work_session(
                        root,
                        checkpoint,
                        checkpoint_file,
                        event_id,
                    ),
                    [],
                )

    def test_current_resume_session_rejects_wrong_predecessor(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            checkpoint, checkpoint_file, event_id, payload = self.fixture(
                root
            )
            checkpoint["goal_execution"]["transition_history"][-1][
                "previous_execution_session_event_sha256"
            ] = "c" * 64
            with mock.patch.object(
                graph.continuation,
                "capture_gate_repository_state",
                return_value=payload,
            ):
                errors = graph.validate_current_work_session(
                    root,
                    checkpoint,
                    checkpoint_file,
                    event_id,
                )
            self.assertIn(
                "current work session predecessor execution hash differs",
                errors,
            )


class WalkSafeGoalGraphV24Test(unittest.TestCase):
    def scope45_queue_fixture(self) -> dict:
        in_scope_codes = [
            f"DLV-SCOPE-{index:02d}"
            for index in range(1, 44)
        ]
        rows = [
            {
                "artifact_type_code": code,
                "applicability": "IN_SCOPE",
                "activation_result": "PENDING_EVALUATION",
                "priority": "P1",
                "scope45_current_scope": {
                    "normalized_decision": "IN_SCOPE",
                    "scope_decision_status": (
                        "APPLIED_TO_NEXT_ACTION_ROUTE"
                    ),
                    "queue_route": "INTERNAL_READY",
                    "queue_open": True,
                    "artifact_closure_status": "OPEN",
                },
                "state": {
                    "lifecycle_status": "PLANNED",
                    "blockers": ["REAL_TRIGGER_PENDING"],
                },
                "trace": {
                    "upstream_types": [],
                    "downstream_types": [],
                },
            }
            for code in in_scope_codes
        ]
        rows.extend(
            {
                "artifact_type_code": f"DLV-N-A-{index:02d}",
                "applicability": "CONDITIONAL",
                "activation_result": (
                    "NOT_ACTIVE_CURRENT_BASELINE"
                ),
                "priority": "P1",
                "scope45_current_scope": {
                    "normalized_decision": "OUT_OF_SCOPE_N_A",
                    "scope_decision_status": (
                        "APPLIED_WITH_DOWNSTREAM_COMPATIBILITY_PASS"
                    ),
                    "queue_route": "SCOPE_N_A_APPROVED",
                    "queue_open": False,
                    "artifact_closure_status": (
                        "CLOSED_N_A_FOR_CURRENT_SCOPE"
                    ),
                },
                "state": {
                    "lifecycle_status": "PLANNED",
                    "blockers": [
                        "NOT_ACTIVE_UNDER_CURRENT_BASELINE"
                    ],
                },
                "trace": {
                    "upstream_types": [],
                    "downstream_types": [],
                },
            }
            for index in range(1, 3)
        )
        return {
            "scope45_current_scope": {
                "transition_count": 45,
                "in_scope_count": 43,
                "out_of_scope_n_a_count": 2,
                "in_scope_artifact_ids": in_scope_codes,
            },
            "artifacts": rows,
        }

    def test_scope45_applied_routes_do_not_reopen_applicability(
        self,
    ) -> None:
        register = self.scope45_queue_fixture()
        errors, queue = (
            graph.derive_v24_artifact_work_queue_from_register(
                {},
                register,
                {},
                {},
            )
        )

        self.assertEqual(errors, [])
        self.assertEqual(
            queue["counts_by_status"],
            {
                "INACTIVE": 2,
                "LIVE_GOAL": 0,
                "STRUCTURALLY_DUE": 0,
                "TERMINAL": 0,
                "WAITING_APPLICABILITY": 0,
                "WAITING_TRIGGER": 43,
                "WAITING_UPSTREAM": 0,
            },
        )
        self.assertIsNone(queue["next_assessment_target_id"])
        self.assertIsNone(
            queue["next_assessment_required_work_reason"]
        )

        legacy_row = copy.deepcopy(register["artifacts"][0])
        legacy_row.pop("scope45_current_scope")
        errors, legacy_queue = (
            graph.derive_v24_artifact_work_queue_from_register(
                {},
                {"artifacts": [legacy_row]},
                {},
                {},
            )
        )
        self.assertEqual(errors, [])
        self.assertEqual(
            legacy_queue["partition_by_status"][
                "WAITING_APPLICABILITY"
            ],
            [legacy_row["artifact_type_code"]],
        )
        self.assertEqual(
            legacy_queue[
                "next_assessment_required_work_reason"
            ],
            "APPLICABILITY_DECISION",
        )

    def test_scope45_applied_route_conflicts_fail_closed(
        self,
    ) -> None:
        register = self.scope45_queue_fixture()
        target = register["artifacts"][0]
        target["applicability"] = "CONDITIONAL"
        node = {
            "goal_kind": "WORK_ITEM",
            "work_item_type": "ARTIFACT_WORK",
            "artifact_work_reason": "APPLICABILITY_DECISION",
            "artifact_trigger_evidence_refs": [],
            "output_subject_ids_by_role": {
                "ARTIFACT_REGISTER": [
                    target["artifact_type_code"]
                ],
            },
        }

        errors, _queue = (
            graph.derive_v24_artifact_work_queue_from_register(
                {},
                register,
                {"INVALID-APPLICABILITY-GOAL": node},
                {"INVALID-APPLICABILITY-GOAL": "READY"},
            )
        )

        self.assertTrue(
            any(
                "conflicting root applicability" in error
                for error in errors
            )
        )
        self.assertTrue(
            any(
                "reason/register lifecycle is incompatible"
                in error
                for error in errors
            )
        )

    def fp011_successor_fixture(
        self,
        root: Path,
        *,
        historical_path: str = (
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/"
            "walksafe/network/GatewayFieldSession.kt"
        ),
        successor_path: str | None = None,
        successor_before_sha256: str | None = None,
        transitive_history: bool = False,
        break_historical_lineage: bool = False,
        frozen_error_prefix: str = "",
        include_receipt: bool = True,
        fp011_status: str = "IN_PROGRESS",
    ) -> tuple[dict, dict, str]:
        predecessor_goal_id = "WS-GOAL-EPIC-02-FP-010-R001"
        historical_goal_ids = (
            [
                "WS-GOAL-EPIC-02-FP-005-R001",
                predecessor_goal_id,
            ]
            if transitive_history
            else [predecessor_goal_id]
        )
        successor_path = successor_path or historical_path
        historical_sha256s = [
            hashlib.sha256(b"historical product bytes\n").hexdigest()
        ]
        if transitive_history:
            historical_sha256s.append(
                hashlib.sha256(
                    b"newer historical product bytes\n"
                ).hexdigest()
            )
        for relative in {historical_path, successor_path}:
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"FP011 exact successor bytes\n")
        successor_sha256 = sha256_file(root / successor_path)

        historical_bindings = []
        historical_completion: dict[str, list[str]] = {}
        historical_inventory = {}
        for index, goal_id in enumerate(historical_goal_ids):
            after_sha256 = historical_sha256s[index]
            before_sha256 = (
                "0" * 64
                if index == 0
                else historical_sha256s[index - 1]
            )
            if break_historical_lineage and index == 1:
                before_sha256 = "e" * 64
            historical_changed = [
                {
                    "path": historical_path,
                    "after_sha256": after_sha256,
                    "before_sha256": before_sha256,
                }
            ]
            historical_implementation = {
                "schema_version": "1.0",
                "document_id": (
                    f"HISTORICAL-IMPLEMENTATION-{index + 1:03d}"
                ),
                "goal_id": goal_id,
                "kind": "IMPLEMENTATION_RECORD",
                "status": "PASS",
                "implementation_content_set_sha256": (
                    graph.continuation.canonical_json_sha256(
                        [
                            {
                                "path": historical_path,
                                "sha256": after_sha256,
                            }
                        ]
                    )
                ),
                "changed_artifacts": historical_changed,
            }
            historical_implementation_path = (
                "docs/control/execution/goal-results/"
                f"{goal_id}/implementation-record.json"
            )
            historical_implementation_sha256 = write_json(
                root,
                historical_implementation_path,
                historical_implementation,
            )
            historical_receipt = {
                "schema_version": "1.0",
                "document_id": (
                    f"HISTORICAL-COMPLETION-{index + 1:03d}"
                ),
                "evidence_type": "WORK_ITEM_EXECUTION_RECEIPT",
                "status": "ACCEPTED",
                "result": "PASS",
                "target_goal_id": goal_id,
                "result_evidence": [
                    {
                        "kind": "IMPLEMENTATION_RECORD",
                        "path": historical_implementation_path,
                        "sha256": historical_implementation_sha256,
                    }
                ],
            }
            historical_receipt_path = (
                "docs/control/execution/goal-results/"
                f"{goal_id}/completion-receipt.json"
            )
            historical_receipt_sha256 = write_json(
                root,
                historical_receipt_path,
                historical_receipt,
            )
            role = f"WORK_ITEM_COMPLETION::{goal_id}"
            historical_bindings.append(
                {
                    "role": role,
                    "document_id": historical_receipt["document_id"],
                    "path": historical_receipt_path,
                    "file_sha256": historical_receipt_sha256,
                }
            )
            historical_completion[goal_id] = [role]
            historical_inventory[goal_id] = {
                "goal_id": goal_id,
                "predecessor_goal_id": (
                    historical_goal_ids[index - 1]
                    if index > 0
                    else ""
                ),
            }

        changed = [
            {
                "path": successor_path,
                "after_sha256": successor_sha256,
                "before_sha256": (
                    successor_before_sha256
                    or historical_sha256s[-1]
                ),
                "change_kind": "MODIFIED",
            }
        ]
        implementation = {
            "schema_version": "1.0",
            "document_id": (
                graph.FP011_RESULT_DOCUMENT_ID_BY_KIND[
                    "IMPLEMENTATION_RECORD"
                ]
            ),
            "goal_id": graph.FP011_GOAL_ID,
            "kind": "IMPLEMENTATION_RECORD",
            "status": "PASS",
            "implementation_content_set_sha256": (
                graph.continuation.canonical_json_sha256(
                    [
                        {
                            "path": successor_path,
                            "sha256": successor_sha256,
                        }
                    ]
                )
            ),
            "changed_artifacts": changed,
        }
        result_bindings = []
        result_by_kind = {
            "IMPLEMENTATION_RECORD": implementation,
            "VERIFICATION_RESULT": {
                "schema_version": "1.0",
                "document_id": (
                    graph.FP011_RESULT_DOCUMENT_ID_BY_KIND[
                        "VERIFICATION_RESULT"
                    ]
                ),
                "goal_id": graph.FP011_GOAL_ID,
                "kind": "VERIFICATION_RESULT",
                "status": "PASS",
            },
            "SUCCESSOR_TRACE": {
                "schema_version": "1.0",
                "document_id": (
                    graph.FP011_RESULT_DOCUMENT_ID_BY_KIND[
                        "SUCCESSOR_TRACE"
                    ]
                ),
                "goal_id": graph.FP011_GOAL_ID,
                "kind": "SUCCESSOR_TRACE",
                "status": "PASS",
            },
        }
        for kind, value in result_by_kind.items():
            relative = graph.FP011_RESULT_PATH_BY_KIND[kind]
            result_bindings.append(
                {
                    "kind": kind,
                    "path": relative,
                    "sha256": write_json(root, relative, value),
                }
            )
        review_sha256 = write_json(
            root,
            graph.FP011_REVIEW_PATH,
            {
                "schema_version": "1.0",
                "document_id": graph.FP011_REVIEW_DOCUMENT_ID,
                "goal_id": graph.FP011_GOAL_ID,
                "evidence_type": "INDEPENDENT_INTERNAL_REVIEW",
                "status": "PASS",
                "reviewed_result_sha256_by_kind": {
                    binding["kind"]: binding["sha256"]
                    for binding in result_bindings
                },
            },
        )
        start_gate_source = ROOT / graph.FP011_START_GATE_PATH
        start_gate_target = root / graph.FP011_START_GATE_PATH
        start_gate_target.parent.mkdir(parents=True, exist_ok=True)
        start_gate_target.write_bytes(start_gate_source.read_bytes())
        self.assertEqual(
            sha256_file(start_gate_target),
            graph.FP011_START_GATE_SHA256,
        )
        start_sha256 = "1" * 64
        receipt = {
            "schema_version": "1.0",
            "document_id": graph.FP011_COMPLETION_DOCUMENT_ID,
            "evidence_type": "WORK_ITEM_EXECUTION_RECEIPT",
            "status": "ACCEPTED",
            "result": "PASS",
            "target_goal_id": graph.FP011_GOAL_ID,
            "target_goal_content_sha256": graph.FP011_GOAL_SHA256,
            "work_item_id": graph.FP011_WORK_ITEM_ID,
            "execution_start_event_sha256": start_sha256,
            "execution_session_event": {
                "sequence": 3,
                "event_id": "FP011-START-001",
                "event_type": "GOAL_STARTED",
                "event_sha256": start_sha256,
            },
            "source_policy_ids": ["FP-011"],
            "gap_ids": ["GAP-020"],
            "target_completion_level": (
                "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
            ),
            "implementation_start_gate_binding": {
                "document_id": graph.FP011_START_GATE_DOCUMENT_ID,
                "path": graph.FP011_START_GATE_PATH,
                "file_sha256": graph.FP011_START_GATE_SHA256,
            },
            "result_evidence": result_bindings,
            "reviewer_provenance": {
                "path": graph.FP011_REVIEW_PATH,
                "sha256": review_sha256,
            },
            "completion_boundary": graph.FP011_COMPLETION_BOUNDARY,
        }
        receipt_sha256 = (
            write_json(root, graph.FP011_COMPLETION_PATH, receipt)
            if include_receipt
            else None
        )

        archive = {
            "canonical_bindings": historical_bindings,
            "goal_execution": {
                "completion_evidence_by_goal": historical_completion,
                "dynamic_goal_inventory": {
                    **historical_inventory,
                    graph.FP011_GOAL_ID: {
                        "goal_id": graph.FP011_GOAL_ID,
                        "path": graph.FP011_GOAL_PATH,
                        "sha256": graph.FP011_GOAL_SHA256,
                        "predecessor_goal_id": predecessor_goal_id,
                    },
                },
            },
        }
        completion_binding = {
            "role": graph.FP011_COMPLETION_ROLE,
            "document_id": receipt["document_id"],
            "path": graph.FP011_COMPLETION_PATH,
            "file_sha256": receipt_sha256,
        }
        checkpoint = {
            "canonical_bindings": (
                [completion_binding]
                if fp011_status == "COMPLETE_AT_TARGET"
                and receipt_sha256 is not None
                else []
            ),
            "goal_execution": {
                "status_by_goal": {
                    graph.FP011_GOAL_ID: fp011_status,
                },
                "imported_predecessor_goal_bindings": {
                    graph.FP011_GOAL_ID: {
                        "path": graph.FP011_GOAL_PATH,
                        "sha256": graph.FP011_GOAL_SHA256,
                    },
                },
                "completion_evidence_by_goal": (
                    {
                        graph.FP011_GOAL_ID: [
                            graph.FP011_COMPLETION_ROLE
                        ]
                    }
                    if fp011_status == "COMPLETE_AT_TARGET"
                    else {}
                ),
                "transition_history": [
                    {
                        "sequence": 3,
                        "event_id": "FP011-START-001",
                        "event_type": "GOAL_STARTED",
                        "subject_goal_id": graph.FP011_GOAL_ID,
                        "event_sha256": start_sha256,
                    }
                ],
            },
        }
        frozen_error = (
            f"{frozen_error_prefix}{historical_goal_ids[0]}: "
            "implementation changed artifact 0 differs"
        )
        return checkpoint, archive, frozen_error

    def test_current_v24_goal_graph_is_valid_after_seq39(self) -> None:
        context = synthetic_approved_r008_context()
        checkpoint = r008_snapshot_projection(
            load_json(ROOT / graph.CHECKPOINT_RELATIVE), context
        )
        with (
            mock.patch.object(
                completion_review,
                "validated_control_successor_r008_context",
                return_value=context,
            ),
            mock.patch.object(
                completion_review,
                "validate_post_review",
                return_value=context.current,
            ),
            mock.patch.object(
                graph.continuation,
                "load_json",
                side_effect=r008_checkpoint_loader(checkpoint),
            ),
        ):
            self.assertEqual(
                graph.validate(ROOT, check_continuation=False),
                [],
            )

    def test_frozen_gateway_lineage_requires_fp047_before_fp048(
        self,
    ) -> None:
        checkpoint = load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        archive = load_json(ROOT / graph.V23_ARCHIVE_RELATIVE)
        relative = "apps/android-gateway/test/gateway-contract.test.ts"
        frozen_error = (
            "archived v2.2: "
            "WS-GOAL-EPIC-02-NPC-PERMISSION-SESSION-LIFECYCLE-R001: "
            "implementation changed artifact 22 differs"
        )
        fp047 = graph._fp047_sealed_product_successor_artifacts(
            ROOT,
            checkpoint,
        )
        fp048 = graph._fp048_sealed_product_successor_artifacts(
            ROOT,
            checkpoint,
        )
        self.assertIsInstance(fp047, dict)
        self.assertIsInstance(fp048, dict)
        self.assertEqual(len(fp047), 26)
        self.assertEqual(
            fp047[relative],
            (
                "ff8fd1ff4f93b23f3873e25c86daae8059b82e3fc5c78cb72f22899f1c3217e4",
                "77df172ec22fc38919cf94ef413fba880f61a5765d4a14ca38bcd31cc37d2307",
            ),
        )
        self.assertEqual(fp047[relative][1], fp048[relative][0])
        fp046_errors, _artifacts, fp046_transitions = (
            graph.validate_fp046_r014_successor_authority(
                ROOT,
                checkpoint,
            )
        )
        self.assertEqual(fp046_errors, [])
        self.assertEqual(
            fp046_transitions[relative],
            (fp048[relative][1], sha256_file(ROOT / relative)),
        )
        self.assertEqual(
            graph.filter_frozen_v23_successor_errors(
                ROOT,
                [frozen_error],
                checkpoint=checkpoint,
                archive=archive,
            ),
            [],
        )

        implementation_path = graph.FP047_RESULT_EVIDENCE_CONTRACT[0][1]
        original_load = graph._load_exact_json

        def wrong_fp047_before(
            root: Path,
            path: object,
        ) -> dict | None:
            document = original_load(root, path)
            if document is None:
                return None
            result = copy.deepcopy(document)
            if path == implementation_path:
                target = next(
                    row
                    for row in result["changed_artifacts"]
                    if row["path"] == relative
                )
                target["before_sha256"] = "0" * 64
            return result

        with mock.patch.object(
            graph,
            "_load_exact_json",
            side_effect=wrong_fp047_before,
        ):
            self.assertIsNone(
                graph._fp047_sealed_product_successor_artifacts(
                    ROOT,
                    checkpoint,
                )
            )
            self.assertEqual(
                graph.filter_frozen_v23_successor_errors(
                    ROOT,
                    [frozen_error],
                    checkpoint=checkpoint,
                    archive=archive,
                ),
                [frozen_error],
            )

    def test_fp048_frozen_rtm_allowance_remains_archive_derived(
        self,
    ) -> None:
        checkpoint = load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        archive = load_json(ROOT / graph.V23_ARCHIVE_RELATIVE)
        expected = frozen_requirements_traceability_errors()
        fp048_receipt = {
            "downstream_consumer_bindings": [
                copy.deepcopy(graph.FP048_REQUIREMENTS_TRACEABILITY_BINDING)
            ]
        }
        fake_rtm_path = mock.Mock()
        fake_rtm_path.stat.return_value.st_size = (
            graph.FP048_REQUIREMENTS_TRACEABILITY_BYTE_COUNT
        )
        original_exact = graph._exact_repo_file
        original_load = graph._load_exact_json
        original_sha256 = graph.continuation.sha256_file

        def exact_file(root: Path, relative: object) -> Path | None:
            if relative == graph.FP048_REQUIREMENTS_TRACEABILITY_BINDING["path"]:
                return fake_rtm_path
            return original_exact(root, relative)

        def exact_json(root: Path, relative: object) -> dict | None:
            if relative == graph.FP048_ANDROID_REPORT_COMPLETION_PATH:
                return copy.deepcopy(fp048_receipt)
            return original_load(root, relative)

        def exact_sha256(path: Path) -> str:
            if path is fake_rtm_path:
                return graph.FP048_REQUIREMENTS_TRACEABILITY_BINDING["sha256"]
            return original_sha256(path)

        with (
            mock.patch.object(
                graph,
                "_fp048_sealed_product_successor_artifacts",
                return_value={},
            ),
            mock.patch.object(
                graph,
                "_exact_repo_file",
                side_effect=exact_file,
            ),
            mock.patch.object(
                graph,
                "_load_exact_json",
                side_effect=exact_json,
            ),
            mock.patch.object(
                graph.continuation,
                "sha256_file",
                side_effect=exact_sha256,
            ),
        ):
            allowed = (
                graph._fp048_archived_requirements_traceability_successor_errors(
                    ROOT,
                    checkpoint,
                    archive,
                )
            )
            self.assertEqual(allowed, expected)
            self.assertEqual(
                graph.filter_frozen_v23_successor_errors(
                    ROOT,
                    sorted(expected),
                    checkpoint=checkpoint,
                    archive=archive,
                ),
                [],
            )

            tampered_archive = copy.deepcopy(archive)
            tampered_archive["goal_execution"]["transition_history"][0][
                "canonical_binding_snapshot_after"
            ]["REQUIREMENTS_TRACEABILITY"]["file_sha256"] = "0" * 64
            exact_error = (
                "Goal graph event 1: canonical binding SHA-256 differs: "
                "REQUIREMENTS_TRACEABILITY"
            )
            self.assertEqual(
                graph.filter_frozen_v23_successor_errors(
                    ROOT,
                    [exact_error],
                    checkpoint=checkpoint,
                    archive=tampered_archive,
                ),
                [exact_error],
            )

        near_miss = next(iter(expected)) + " unexpected"
        self.assertEqual(
            graph.filter_frozen_v23_successor_errors(
                ROOT,
                [near_miss],
                checkpoint=checkpoint,
                archive=archive,
            ),
            [near_miss],
        )

    def test_fp008_seq50_rtm_allowance_requires_exact_completion_chain(
        self,
    ) -> None:
        historical_rtm = enter_fp008_historical_physical_bindings(self)
        checkpoint = fp008_seq50_checkpoint()
        archive = load_json(ROOT / graph.V23_ARCHIVE_RELATIVE)
        expected = frozen_requirements_traceability_errors()
        expected_list = sorted(expected)
        self.assertTrue(
            graph._fp008_admin_review_completion_is_declared(ROOT, checkpoint)
        )
        self.assertEqual(
            graph._fp008_archived_requirements_traceability_successor_errors(
                ROOT,
                checkpoint,
                archive,
            ),
            expected,
        )
        self.assertEqual(
            graph.filter_frozen_v23_successor_errors(
                ROOT,
                expected_list,
                checkpoint=checkpoint,
                archive=archive,
            ),
            [],
        )

        def assert_restored(candidate: dict) -> None:
            self.assertFalse(
                graph._fp008_admin_review_completion_is_declared(
                    ROOT,
                    candidate,
                )
            )
            with mock.patch.object(
                graph,
                "_fp048_archived_requirements_traceability_successor_errors",
                return_value=set(),
            ):
                self.assertEqual(
                    graph.filter_frozen_v23_successor_errors(
                        ROOT,
                        expected_list,
                        checkpoint=candidate,
                        archive=archive,
                    ),
                    expected_list,
                )

        declaration_tamper = copy.deepcopy(checkpoint)
        declaration_tamper["goal_execution"]["status_by_goal"][
            graph.FP008_ADMIN_REVIEW_GOAL_ID
        ] = "IN_PROGRESS"
        with self.subTest(tamper="completion declaration"):
            assert_restored(declaration_tamper)

        event_seal_tamper = copy.deepcopy(checkpoint)
        event_seal_tamper["goal_execution"]["transition_history"][49][
            "event_sha256"
        ] = "0" * 64
        with self.subTest(tamper="completion event seal"):
            assert_restored(event_seal_tamper)

        original_load = graph._load_exact_json

        def tampered_receipt_loader(
            field: str,
            value: object,
        ) -> Callable[[Path, object], dict | None]:
            def load(root: Path, relative: object) -> dict | None:
                document = original_load(root, relative)
                if document is None:
                    return None
                result = copy.deepcopy(document)
                if relative == graph.FP008_ADMIN_REVIEW_COMPLETION_PATH:
                    if field == "downstream_rtm":
                        target = next(
                            row
                            for row in result["downstream_consumer_bindings"]
                            if row["role"] == "REQUIREMENTS_TRACEABILITY"
                        )
                        target["sha256"] = value
                    elif field == "completion_boundary":
                        result[field] = value
                    else:
                        result[field] = value
                return result

            return load

        for label, field, value in (
            ("receipt identity", "document_id", "WRONG"),
            ("receipt status", "status", "REJECTED"),
            ("downstream RTM", "downstream_rtm", "0" * 64),
            ("receipt completion boundary", "completion_boundary", {}),
        ):
            with self.subTest(tamper=label), mock.patch.object(
                graph,
                "_load_exact_json",
                side_effect=tampered_receipt_loader(field, value),
            ):
                assert_restored(checkpoint)

        original_sha256 = graph.continuation.sha256_file
        completion_path = ROOT / graph.FP008_ADMIN_REVIEW_COMPLETION_PATH
        rtm_path = historical_rtm

        def wrong_physical_sha256(target: object) -> Callable[[object], str]:
            def sha256(path: object) -> str:
                return "0" * 64 if path == target else original_sha256(path)

            return sha256

        for label, target in (
            ("completion receipt physical hash", completion_path),
            ("RTM physical hash", rtm_path),
        ):
            with self.subTest(tamper=label), mock.patch.object(
                graph.continuation,
                "sha256_file",
                side_effect=wrong_physical_sha256(target),
            ):
                assert_restored(checkpoint)

        canonical_tamper = copy.deepcopy(checkpoint)
        next(
            binding
            for binding in canonical_tamper["canonical_bindings"]
            if binding["role"] == "REQUIREMENTS_TRACEABILITY"
        )["file_sha256"] = "0" * 64
        with self.subTest(tamper="live canonical RTM"):
            assert_restored(canonical_tamper)

    def test_fp008_seq50_resealed_semantic_and_safety_tamper_is_rejected(
        self,
    ) -> None:
        enter_fp008_historical_physical_bindings(self)
        checkpoint = fp008_seq50_checkpoint()
        archive = load_json(ROOT / graph.V23_ARCHIVE_RELATIVE)
        expected = sorted(frozen_requirements_traceability_errors())
        expected_checker = sorted(
            f"frozen v2.3: {error}"
            for error in expected
        )
        self.assertTrue(
            graph._fp008_admin_review_completion_is_declared(ROOT, checkpoint)
        )
        self.assertEqual(
            graph.validate_frozen_v23_semantics(
                ROOT,
                checkpoint=checkpoint,
                archive=archive,
            ),
            [],
        )

        def update_event(candidate: dict) -> dict:
            return candidate["goal_execution"]["transition_history"][48]

        def completion_event(candidate: dict) -> dict:
            return candidate["goal_execution"]["transition_history"][49]

        def reseal_pair(candidate: dict) -> None:
            update = update_event(candidate)
            completion = completion_event(candidate)
            update["event_sha256"] = graph.continuation.event_sha256(update)
            completion["previous_event_sha256"] = update["event_sha256"]
            completion["canonical_update_event_sha256"] = update[
                "event_sha256"
            ]
            completion["event_sha256"] = graph.continuation.event_sha256(
                completion
            )
            candidate["goal_execution"][
                "transition_history_anchor_sha256"
            ] = completion["event_sha256"]

        def assert_rejected(
            label: str,
            candidate: dict,
            *,
            full_checker: bool = False,
        ) -> None:
            with self.subTest(tamper=label):
                self.assertFalse(
                    graph._fp008_admin_review_completion_is_declared(
                        ROOT,
                        candidate,
                    )
                )
                with mock.patch.object(
                    graph,
                    "_fp048_archived_requirements_traceability_successor_errors",
                    return_value=set(),
                ):
                    self.assertEqual(
                        graph.filter_frozen_v23_successor_errors(
                            ROOT,
                            expected,
                            checkpoint=candidate,
                            archive=archive,
                        ),
                        expected,
                    )
                if full_checker:
                    self.assertEqual(
                        sorted(
                            graph.validate_frozen_v23_semantics(
                                ROOT,
                                checkpoint=candidate,
                                archive=archive,
                            )
                        ),
                        expected_checker,
                    )

        def event_candidate(
            mutator: Callable[[dict, dict, dict], None],
        ) -> dict:
            candidate = copy.deepcopy(checkpoint)
            mutator(candidate, update_event(candidate), completion_event(candidate))
            reseal_pair(candidate)
            return candidate

        def changed_roles_extra(_candidate: dict, update: dict, _completion: dict) -> None:
            update["changed_binding_roles"].append("UNAUTHORIZED_ROLE")

        def changed_roles_duplicate(
            _candidate: dict,
            update: dict,
            _completion: dict,
        ) -> None:
            update["changed_binding_roles"].append(
                update["changed_binding_roles"][0]
            )

        def changed_roles_reversed(
            _candidate: dict,
            update: dict,
            _completion: dict,
        ) -> None:
            update["changed_binding_roles"].reverse()

        def produced_roles_extra(_candidate: dict, update: dict, _completion: dict) -> None:
            update["produced_binding_roles"].append("ARTIFACT_REGISTER")

        def produced_roles_empty(_candidate: dict, update: dict, _completion: dict) -> None:
            update["produced_binding_roles"] = []

        def evidence_refs_extra(_candidate: dict, update: dict, _completion: dict) -> None:
            update["evidence_refs"].append("UNAUTHORIZED_ROLE")

        def changed_subjects_extra(
            _candidate: dict,
            update: dict,
            _completion: dict,
        ) -> None:
            update["changed_subject_ids_by_role"]["UNAUTHORIZED_ROLE"] = [
                "FAKE-SUBJECT"
            ]

        def changed_subjects_missing(
            _candidate: dict,
            update: dict,
            _completion: dict,
        ) -> None:
            del update["changed_subject_ids_by_role"]["MODULE_REGISTER"]

        def producer_subjects_extra(
            _candidate: dict,
            update: dict,
            _completion: dict,
        ) -> None:
            update["producer_output_subject_ids_by_role"][
                "ARTIFACT_REGISTER"
            ] = ["FAKE-SUBJECT"]

        def fake_impact(_candidate: dict, update: dict, _completion: dict) -> None:
            update["impact_closure_goal_ids"].append("WS-GOAL-FAKE")
            update["impact_disposition_by_goal"]["WS-GOAL-FAKE"] = {
                "result": "COMPLETE",
                "target_status": "COMPLETE_AT_TARGET",
            }

        def fake_completion_evidence(
            candidate: dict,
            _update: dict,
            completion: dict,
        ) -> None:
            fake = ["WORK_ITEM_COMPLETION::WS-GOAL-FAKE"]
            completion["completion_evidence_by_goal_after"][
                "WS-GOAL-FAKE"
            ] = fake
            candidate["goal_execution"]["completion_evidence_by_goal"][
                "WS-GOAL-FAKE"
            ] = fake

        def update_runtime_tamper(
            _candidate: dict,
            update: dict,
            _completion: dict,
        ) -> None:
            update["runtime_after"]["ready_frontier_goal_ids"].reverse()

        def completion_runtime_tamper(
            _candidate: dict,
            _update: dict,
            completion: dict,
        ) -> None:
            completion["runtime_after"]["ready_frontier_goal_ids"].reverse()

        def full_snapshot_tamper(
            _candidate: dict,
            update: dict,
            completion: dict,
        ) -> None:
            for event in (update, completion):
                event["canonical_binding_snapshot_after"][
                    "ARTIFACT_CHANGE_LOG"
                ]["file_sha256"] = "0" * 64

        def update_field_extra(_candidate: dict, update: dict, _completion: dict) -> None:
            update["unauthorized_field"] = True

        event_tampers = (
            ("changed roles extra", changed_roles_extra),
            ("changed roles duplicate", changed_roles_duplicate),
            ("changed roles reversed", changed_roles_reversed),
            ("produced roles extra", produced_roles_extra),
            ("produced roles empty", produced_roles_empty),
            ("evidence refs extra", evidence_refs_extra),
            ("changed subjects extra", changed_subjects_extra),
            ("changed subjects missing", changed_subjects_missing),
            ("producer subjects extra", producer_subjects_extra),
            ("fake impact goal", fake_impact),
            ("fake completion evidence", fake_completion_evidence),
            ("update runtime", update_runtime_tamper),
            ("completion runtime", completion_runtime_tamper),
            ("full canonical snapshot", full_snapshot_tamper),
            ("update exact field set", update_field_extra),
        )
        for label, mutator in event_tampers:
            assert_rejected(
                label,
                event_candidate(mutator),
                full_checker=label == "changed roles extra",
            )

        def state_candidate(mutator: Callable[[dict], None]) -> dict:
            candidate = copy.deepcopy(checkpoint)
            mutator(candidate)
            return candidate

        def deployment_action(candidate: dict) -> None:
            action = "production deployment and release"
            candidate["current_work"]["next_action"] = action
            candidate["session_handoff"]["next_single_action"] = action

        state_tampers: tuple[tuple[str, Callable[[dict], None]], ...] = (
            (
                "metadata release status",
                lambda candidate: candidate["metadata"].__setitem__(
                    "status", "RELEASE_APPROVED"
                ),
            ),
            (
                "repository destructive cleanup",
                lambda candidate: candidate["repository"].__setitem__(
                    "destructive_cleanup_forbidden", False
                ),
            ),
            (
                "canonical mutable metadata",
                lambda candidate: next(
                    binding
                    for binding in candidate["canonical_bindings"]
                    if binding["role"] == "ARTIFACT_CHANGE_LOG"
                ).__setitem__("mutable", False),
            ),
            (
                "canonical identity metadata",
                lambda candidate: next(
                    binding
                    for binding in candidate["canonical_bindings"]
                    if binding["role"] == "ARTIFACT_CHANGE_LOG"
                ).__setitem__("identity_json_path", "WRONG"),
            ),
            (
                "authority normative",
                lambda candidate: candidate["authority_boundary"].__setitem__(
                    "normative_policy_source", True
                ),
            ),
            (
                "authority artifact creation",
                lambda candidate: candidate["authority_boundary"].__setitem__(
                    "creates_artifact_type", True
                ),
            ),
            (
                "approved release status",
                lambda candidate: candidate["approved_state"].__setitem__(
                    "release_status", "ELIGIBLE"
                ),
            ),
            (
                "approved gates waived",
                lambda candidate: candidate["approved_state"].__setitem__(
                    "remaining_gates_waived", True
                ),
            ),
            (
                "approved formal counts",
                lambda candidate: candidate["approved_state"].__setitem__(
                    "formal_test_not_run_count", 0
                ),
            ),
            (
                "verification formal claim",
                lambda candidate: candidate["verification_boundary"].__setitem__(
                    "formal_test_pass_claimed", True
                ),
            ),
            (
                "verification actual device",
                lambda candidate: candidate["verification_boundary"].__setitem__(
                    "actual_device_test_status", "PASS"
                ),
            ),
            (
                "verification conformance",
                lambda candidate: candidate["verification_boundary"].__setitem__(
                    "implementation_conformance_claimed", True
                ),
            ),
            (
                "verification production profile",
                lambda candidate: candidate["verification_boundary"].__setitem__(
                    "approved_production_profile_count", 1
                ),
            ),
            (
                "verification remaining gates",
                lambda candidate: candidate["verification_boundary"].__setitem__(
                    "all_remaining_gate_status", "PASS"
                ),
            ),
            (
                "verification release eligible",
                lambda candidate: candidate["verification_boundary"].__setitem__(
                    "release_eligible", True
                ),
            ),
            (
                "standing authority expansion",
                lambda candidate: candidate["goal_execution"][
                    "standing_execution_authority"
                ].append("FORMAL_RELEASE_APPROVAL"),
            ),
            (
                "current work release claim",
                lambda candidate: candidate["current_work"].__setitem__(
                    "release_completion_claimed", True
                ),
            ),
            (
                "current work release target",
                lambda candidate: candidate["current_work"].__setitem__(
                    "target_completion_level", "RELEASE_READY"
                ),
            ),
            (
                "current work deferred release gates",
                lambda candidate: candidate["current_work"].__setitem__(
                    "deferred_release_gate_ids", []
                ),
            ),
            ("synchronized deployment action", deployment_action),
            (
                "handoff formal pass",
                lambda candidate: candidate["session_handoff"].__setitem__(
                    "last_verification_status", "PASS_FORMAL_RELEASE_READY"
                ),
            ),
            (
                "handoff release field",
                lambda candidate: candidate["session_handoff"].__setitem__(
                    "release_eligible", True
                ),
            ),
        )
        for label, mutator in state_tampers:
            assert_rejected(
                label,
                state_candidate(mutator),
                full_checker=label == "approved release status",
            )

    def test_fp008_future_suffix_allows_progress_but_preserves_safety(
        self,
    ) -> None:
        enter_fp008_historical_physical_bindings(self)
        checkpoint = fp008_seq50_checkpoint()
        archive = load_json(ROOT / graph.V23_ARCHIVE_RELATIVE)
        expected = sorted(frozen_requirements_traceability_errors())

        future = copy.deepcopy(checkpoint)
        state = future["goal_execution"]

        self.assertTrue(
            graph._fp008_admin_review_completion_is_declared(ROOT, future)
        )
        self.assertEqual(
            graph.filter_frozen_v23_successor_errors(
                ROOT,
                expected,
                checkpoint=future,
                archive=archive,
            ),
            [],
        )
        self.assertEqual(
            graph.validate_frozen_v23_semantics(
                ROOT,
                checkpoint=future,
                archive=archive,
            ),
            [],
        )

        def reseal_tail(candidate: dict) -> None:
            tail = candidate["goal_execution"]["transition_history"][-1]
            tail["event_sha256"] = graph.continuation.event_sha256(tail)
            candidate["goal_execution"][
                "transition_history_anchor_sha256"
            ] = tail["event_sha256"]

        in_progress = copy.deepcopy(future)
        in_progress_state = in_progress["goal_execution"]
        progress_event = copy.deepcopy(
            in_progress_state["transition_history"][-1]
        )
        progress_event["sequence"] = 52
        progress_event["event_id"] = (
            "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-"
            "SAFE-FUTURE-20260809-001"
        )
        progress_event["event_type"] = "GOAL_STARTED"
        progress_event["occurred_at"] = "2026-08-09T16:49:00+09:00"
        progress_event["from_status"] = "READY"
        progress_event["to_status"] = "IN_PROGRESS"
        progress_event["subject_goal_id"] = (
            graph.FP008_ADMIN_REVIEW_PARENT_GOAL_ID
        )
        progress_event["status_changes"] = {
            graph.FP008_ADMIN_REVIEW_PARENT_GOAL_ID: "IN_PROGRESS"
        }
        progress_event["previous_event_sha256"] = in_progress_state[
            "transition_history"
        ][-1]["event_sha256"]
        progress_event["runtime_after"]["focus_work_item_id"] = (
            in_progress["current_work"]["work_item_id"]
        )
        progress_event["runtime_after"]["focus_source"] = (
            "IMPLEMENTATION_BACKLOG"
        )
        in_progress_state["transition_history"].append(progress_event)
        for field in (
            "focus_goal_id",
            "focus_goal_path",
            "focus_work_item_id",
            "focus_source",
            "ready_frontier_goal_ids",
            "blocked_goal_ids",
            "pending_questions",
            "open_question_count",
            "activation_status",
            "package_status",
        ):
            in_progress_state[field] = copy.deepcopy(
                progress_event["runtime_after"][field]
            )
        in_progress_state["status_by_goal"][
            graph.FP008_ADMIN_REVIEW_PARENT_GOAL_ID
        ] = "IN_PROGRESS"
        in_progress_state["validation_cutoff_at"] = progress_event[
            "occurred_at"
        ]
        in_progress["current_work"]["status"] = "IN_PROGRESS"
        in_progress["session_handoff"]["last_updated_by_work_item"] = (
            in_progress["current_work"]["work_item_id"]
        )
        reseal_tail(in_progress)
        self.assertTrue(
            graph._fp008_admin_review_completion_is_declared(
                ROOT,
                in_progress,
            )
        )
        self.assertEqual(
            graph.filter_frozen_v23_successor_errors(
                ROOT,
                expected,
                checkpoint=in_progress,
                archive=archive,
            ),
            [],
        )

        def assert_rejected(
            label: str,
            candidate: dict,
            *,
            full_checker: bool = False,
        ) -> None:
            with self.subTest(tamper=label):
                self.assertFalse(
                    graph._fp008_admin_review_completion_is_declared(
                        ROOT,
                        candidate,
                    )
                )
                with mock.patch.object(
                    graph,
                    "_fp048_archived_requirements_traceability_successor_errors",
                    return_value=set(),
                ):
                    self.assertEqual(
                        graph.filter_frozen_v23_successor_errors(
                            ROOT,
                            expected,
                            checkpoint=candidate,
                            archive=archive,
                        ),
                        expected,
                    )
                if full_checker:
                    self.assertEqual(
                        sorted(
                            graph.validate_frozen_v23_semantics(
                                ROOT,
                                checkpoint=candidate,
                                archive=archive,
                            )
                        ),
                        sorted(f"frozen v2.3: {error}" for error in expected),
                    )

        metadata_tamper = copy.deepcopy(future)
        metadata_tamper["metadata"]["status"] = "RELEASE_APPROVED"
        assert_rejected(
            "future metadata release status",
            metadata_tamper,
            full_checker=True,
        )

        cleanup_tamper = copy.deepcopy(future)
        cleanup_tamper["repository"]["destructive_cleanup_forbidden"] = False
        assert_rejected("future destructive cleanup", cleanup_tamper)

        deferred_gate_tamper = copy.deepcopy(future)
        deferred_gate_tamper["current_work"]["deferred_release_gate_ids"] = []
        assert_rejected("future deferred release gates", deferred_gate_tamper)

        deployment_tamper = copy.deepcopy(future)
        deployment_action = "production deployment and release"
        deployment_tamper["current_work"]["next_action"] = deployment_action
        deployment_tamper["session_handoff"][
            "next_single_action"
        ] = deployment_action
        assert_rejected("future synchronized deployment action", deployment_tamper)

        release_gate_tamper = copy.deepcopy(future)
        next(
            record
            for record in release_gate_tamper["session_handoff"][
                "remaining_blockers_and_gates"
            ]
            if record.get("kind") == "RELEASE_GATE"
        )["waived"] = True
        assert_rejected("future waived release gate", release_gate_tamper)

        runtime_tamper = copy.deepcopy(future)
        runtime_tamper["goal_execution"]["focus_source"] = "UNSEALED_SOURCE"
        assert_rejected("future tail runtime mismatch", runtime_tamper)

        focus_status_tamper = copy.deepcopy(in_progress)
        focus_status_tamper["current_work"]["status"] = "READY"
        assert_rejected(
            "future focus status mismatch",
            focus_status_tamper,
        )

        boundary_tamper = copy.deepcopy(future)
        boundary = boundary_tamper["goal_execution"]["completion_boundary"]
        boundary["project_status"] = "COMPLETE"
        tail = boundary_tamper["goal_execution"]["transition_history"][-1]
        tail["runtime_after"]["completion_boundary_sha256"] = (
            graph.continuation.canonical_json_sha256(boundary)
        )
        reseal_tail(boundary_tamper)
        assert_rejected("future completed project boundary", boundary_tamper)

        sequence_tamper = copy.deepcopy(future)
        sequence_tamper["goal_execution"]["transition_history"][-1][
            "sequence"
        ] = 52
        reseal_tail(sequence_tamper)
        assert_rejected("future suffix sequence gap", sequence_tamper)

    def test_seq39_queue_is_source_binding_only_and_tamper_safe(
        self,
    ) -> None:
        checkpoint = graph.continuation.load_json(
            ROOT / graph.continuation.V24_CHECKPOINT_RELATIVE
        )
        self.assertEqual(
            graph.continuation.validate_seq39_canonical_binding_update(
                ROOT,
                checkpoint,
            ),
            [],
        )
        history = checkpoint["goal_execution"]["transition_history"]
        source_queue = history[37]["runtime_after"]["artifact_work_queue"]
        seq39_queue = history[38]["runtime_after"]["artifact_work_queue"]
        self.assertEqual(
            seq39_queue["partition_by_status"],
            source_queue["partition_by_status"],
        )
        self.assertEqual(
            seq39_queue["source_binding"]["file_sha256"],
            graph.continuation._seq39_register_update()["after_sha256"],
        )

        history[38]["runtime_after"]["artifact_work_queue"][
            "counts_by_status"
        ]["WAITING_TRIGGER"] += 1
        self.assertTrue(
            graph.continuation.validate_seq39_canonical_binding_update(
                ROOT,
                checkpoint,
            )
        )

    def test_fp013_successor_uses_byte_bound_start_repository_state(
        self,
    ) -> None:
        log_relative = (
            graph.FP013_START_GATE_PATH.rsplit("/", 1)[0]
            + "/19-REPOSITORY_STATE.log"
        )
        binding = {
            "document_id": graph.FP013_START_GATE_DOCUMENT_ID,
            "path": graph.FP013_START_GATE_PATH,
            "file_sha256": graph.FP013_START_GATE_SHA256,
        }
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for relative in (graph.FP013_START_GATE_PATH, log_relative):
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((ROOT / relative).read_bytes())

            start_state = graph._fp013_start_repository_state_snapshot(
                root,
                binding,
            )
            self.assertIsNotNone(start_state)
            snapshot, head_commit = start_state
            self.assertEqual(
                head_commit,
                "a3ad7eead6b5d834d3e0675422475a9aad351e3d",
            )
            self.assertEqual(
                snapshot[
                    "apps/android/app/src/main/java/kr/co/hanium/"
                    "dreamup/walksafe/MainActivity.kt"
                ]["sha256"],
                "91039b0ab58b3564082418eabdfdb50f32af9807a47c7e03"
                "d199b8a7ce880d4c",
            )

            (root / log_relative).write_bytes(b'{"tampered":true}\n')
            self.assertIsNone(
                graph._fp013_start_repository_state_snapshot(
                    root,
                    binding,
                )
            )

    def test_fp013_successor_composes_start_head_and_live_bytes(self) -> None:
        checkpoint = load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        archive = load_json(ROOT / ARCHIVE_RELATIVE)
        transitions = graph._fp013_successor_product_artifacts(
            ROOT,
            checkpoint,
            require_live_after=False,
        )
        report_uploader = (
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/"
            "walksafe/report/AndroidReportUploader.kt"
        )
        self.assertEqual(
            transitions[report_uploader],
            (
                "ac8186277ec3f88e0ff848818ef15059a6512ff5f8a7bd24d"
                "eb1f00dd9259205",
                "fdd7890d7867b2e7a7045dcf22612a20a4c9a82d7b2dac0b"
                "c42f4b811ebf516a",
            ),
        )
        self.assertNotIn(
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/"
            "walksafe/MainActivityIntegratedConsentStaticTest.kt",
            transitions,
        )
        historical_goal_id = "WS-GOAL-EPIC-02-FP-018-R001"
        historical = graph._historical_changed_artifacts(
            ROOT,
            archive,
            goal_id=historical_goal_id,
        )
        historical_main_activity = [
            record
            for record in historical
            if record["path"]
            == "apps/android/app/src/main/java/kr/co/hanium/dreamup/"
            "walksafe/MainActivity.kt"
        ]
        self.assertEqual(len(historical_main_activity), 1)
        self.assertTrue(
            graph.fp011_successor_artifact_reaches_live(
                ROOT,
                checkpoint=checkpoint,
                archive=archive,
                goal_id=historical_goal_id,
                relative_path=historical_main_activity[0]["path"],
                historical_sha256=historical_main_activity[0][
                    "after_sha256"
                ],
            )
        )

    def test_fp015_successor_uses_byte_bound_start_repository_state(
        self,
    ) -> None:
        log_relative = (
            graph.FP015_START_GATE_PATH.rsplit("/", 1)[0]
            + "/19-REPOSITORY_STATE.log"
        )
        binding = {
            "document_id": graph.FP015_START_GATE_DOCUMENT_ID,
            "path": graph.FP015_START_GATE_PATH,
            "file_sha256": graph.FP015_START_GATE_SHA256,
        }
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source_bytes = {}
            for relative in (graph.FP015_START_GATE_PATH, log_relative):
                payload = (ROOT / relative).read_bytes()
                source_bytes[relative] = payload
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(payload)

            start_state = graph._fp015_start_repository_state_snapshot(
                root,
                binding,
            )
            self.assertIsNotNone(start_state)
            snapshot, head_commit = start_state
            self.assertRegex(head_commit, r"^[0-9a-f]{40}$")
            self.assertEqual(
                snapshot[
                    "apps/android/app/src/main/java/kr/co/hanium/"
                    "dreamup/walksafe/MainActivity.kt"
                ]["sha256"],
                "25e749d03fcff6cf5c00ae73b00d239c4b4d9c8f392f5d1e"
                "2fca2fbabad38133",
            )

            (root / log_relative).write_bytes(b'{"tampered":true}\n')
            self.assertIsNone(
                graph._fp015_start_repository_state_snapshot(root, binding)
            )
            (root / log_relative).write_bytes(source_bytes[log_relative])
            (root / graph.FP015_START_GATE_PATH).write_bytes(
                b'{"tampered":true}\n'
            )
            self.assertIsNone(
                graph._fp015_start_repository_state_snapshot(root, binding)
            )

    def test_fp015_successor_composes_exact_chain_and_rejects_tamper(
        self,
    ) -> None:
        checkpoint = load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        archive = load_json(ROOT / ARCHIVE_RELATIVE)
        fp014 = graph._fp014_successor_product_artifacts(
            ROOT,
            checkpoint,
            require_live_successors=False,
        )
        self.assertIsInstance(fp014, dict)
        transitions = graph._fp015_successor_product_artifacts(
            ROOT,
            checkpoint,
            successor_artifacts=fp014,
            require_live_successors=False,
        )
        self.assertIsNotNone(transitions)
        self.assertEqual(len(transitions), 16)
        main_activity = (
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/"
            "walksafe/MainActivity.kt"
        )
        self.assertEqual(
            transitions[main_activity],
            (
                "25e749d03fcff6cf5c00ae73b00d239c4b4d9c8f392f5d1e"
                "2fca2fbabad38133",
                "7646f8cf176d900e2e9765effe3a838e4915073076c2d771"
                "911679738ee3f652",
            ),
        )
        historical_goal_id = "WS-GOAL-EPIC-02-FP-018-R001"
        historical = graph._historical_changed_artifacts(
            ROOT,
            archive,
            goal_id=historical_goal_id,
        )
        historical_main_activity = [
            record
            for record in historical
            if record["path"] == main_activity
        ]
        self.assertEqual(len(historical_main_activity), 1)
        self.assertTrue(
            graph.fp011_successor_artifact_reaches_live(
                ROOT,
                checkpoint=checkpoint,
                archive=archive,
                goal_id=historical_goal_id,
                relative_path=main_activity,
                historical_sha256=historical_main_activity[0][
                    "after_sha256"
                ],
            )
        )
        self.assertFalse(
            graph.fp011_successor_artifact_reaches_live(
                ROOT,
                checkpoint=checkpoint,
                archive=archive,
                goal_id=historical_goal_id,
                relative_path=main_activity,
                historical_sha256="0" * 64,
            )
        )

        implementation_path = graph.FP015_RESULT_PATH_BY_KIND[
            "IMPLEMENTATION_RECORD"
        ]
        implementation = load_json(ROOT / implementation_path)
        original_loader = graph._load_exact_json

        def rejects_implementation_tamper(mutator) -> None:
            tampered = copy.deepcopy(implementation)
            mutator(tampered["changed_artifacts"])

            def load_with_tamper(root, relative):
                if relative == implementation_path:
                    return tampered
                return original_loader(root, relative)

            with mock.patch.object(
                graph,
                "_load_exact_json",
                side_effect=load_with_tamper,
            ):
                self.assertIsNone(
                    graph._fp015_successor_product_artifacts(
                        ROOT,
                        checkpoint,
                    )
                )

        for label, mutator in (
            (
                "before_sha256",
                lambda rows: rows[0].__setitem__(
                    "before_sha256",
                    "0" * 64,
                ),
            ),
            (
                "after_sha256",
                lambda rows: rows[0].__setitem__(
                    "after_sha256",
                    "0" * 64,
                ),
            ),
            ("path_set", lambda rows: rows.pop()),
            (
                "change_kind",
                lambda rows: rows[0].__setitem__("change_kind", "ADDED"),
            ),
        ):
            with self.subTest(tamper=label):
                rejects_implementation_tamper(mutator)

        for constant in (
            "FP015_REVIEW_SUBJECT_SHA256",
            "FP015_REVIEW_ATTESTATION_SHA256",
        ):
            with self.subTest(tamper=constant), mock.patch.object(
                graph,
                constant,
                "0" * 64,
            ):
                self.assertIsNone(
                    graph._fp015_successor_product_artifacts(
                        ROOT,
                        checkpoint,
                    )
                )

        original_sha256_file = graph.continuation.sha256_file

        def sha256_with_tampered_completion(path):
            if path == ROOT / graph.FP015_COMPLETION_PATH:
                return "0" * 64
            return original_sha256_file(path)

        with mock.patch.object(
            graph.continuation,
            "sha256_file",
            side_effect=sha256_with_tampered_completion,
        ):
            self.assertIsNone(
                graph._fp015_successor_product_artifacts(
                    ROOT,
                    checkpoint,
                )
            )

        role_tamper = copy.deepcopy(checkpoint)
        completion_binding = next(
            binding
            for binding in role_tamper["canonical_bindings"]
            if binding["role"] == graph.FP015_COMPLETION_ROLE
        )
        completion_binding["role"] = "TAMPERED"
        self.assertIsNone(
            graph._fp015_successor_product_artifacts(
                ROOT,
                role_tamper,
            )
        )

        historical_index = next(
            index
            for index, record in enumerate(historical)
            if record["path"] == main_activity
        )
        frozen_error = (
            f"{historical_goal_id}: implementation changed artifact "
            f"{historical_index} differs"
        )
        original_git = graph._run_git_bytes
        for returncode in (128, 2):
            def controlled_git(root, args, **kwargs):
                if args == [
                    "cat-file",
                    "-e",
                    f"{graph.HISTORICAL_GIT_WITNESS_COMMIT}^{{commit}}",
                ]:
                    return subprocess.CompletedProcess(
                        args=["git", *args],
                        returncode=returncode,
                        stdout=b"",
                        stderr=b"invalid pinned HEAD",
                    )
                return original_git(root, args, **kwargs)

            with self.subTest(
                pinned_head_returncode=returncode
            ), mock.patch.object(
                graph,
                "_run_git_bytes",
                side_effect=controlled_git,
            ):
                transitions = graph._fp015_successor_product_artifacts(
                    ROOT,
                    checkpoint,
                    successor_artifacts=fp014,
                    require_live_successors=False,
                )
                filtered = graph.filter_frozen_v23_successor_errors(
                    ROOT,
                    [frozen_error],
                    checkpoint=checkpoint,
                    archive=archive,
                )
                if returncode == 128:
                    self.assertIsInstance(transitions, dict)
                    self.assertEqual(filtered, [])
                else:
                    self.assertIsNone(transitions)
                    self.assertEqual(filtered, [frozen_error])

    def test_v24_runbook_successor_override_is_byte_exact(self) -> None:
        frozen_error = (
            "WS-GOAL-EPIC-02-FP-005-R001: "
            "implementation changed artifact 23 differs"
        )

        self.assertEqual(
            graph.filter_frozen_v23_successor_errors(
                ROOT,
                [frozen_error],
            ),
            [],
        )
        self.assertEqual(
            graph.filter_frozen_v23_successor_errors(
                ROOT,
                [f"{frozen_error} unexpected"],
            ),
            [f"{frozen_error} unexpected"],
        )

    def test_fp011_exact_receipt_successor_allows_only_bound_product_bytes(
        self,
    ) -> None:
        for fp011_status in ("IN_PROGRESS", "COMPLETE_AT_TARGET"):
            with self.subTest(fp011_status=fp011_status):
                with tempfile.TemporaryDirectory() as temporary:
                    root = Path(temporary)
                    checkpoint, archive, frozen_error = (
                        self.fp011_successor_fixture(
                            root,
                            fp011_status=fp011_status,
                        )
                    )

                    self.assertEqual(
                        graph.filter_frozen_v23_successor_errors(
                            root,
                            [frozen_error],
                            checkpoint=checkpoint,
                            archive=archive,
                        ),
                        [],
                    )
                    historical_sha256 = hashlib.sha256(
                        b"historical product bytes\n"
                    ).hexdigest()
                    self.assertTrue(
                        graph.fp011_successor_artifact_reaches_live(
                            root,
                            checkpoint=checkpoint,
                            archive=archive,
                            goal_id="WS-GOAL-EPIC-02-FP-010-R001",
                            relative_path=(
                                "apps/android/app/src/main/java/kr/co/"
                                "hanium/dreamup/walksafe/network/"
                                "GatewayFieldSession.kt"
                            ),
                            historical_sha256=historical_sha256,
                        )
                    )
                    self.assertFalse(
                        graph.fp011_successor_artifact_reaches_live(
                            root,
                            checkpoint=checkpoint,
                            archive=archive,
                            goal_id="WS-GOAL-EPIC-02-FP-010-R001",
                            relative_path=(
                                "apps/android/app/src/main/java/kr/co/"
                                "hanium/dreamup/walksafe/network/"
                                "GatewayFieldSession.kt"
                            ),
                            historical_sha256="0" * 64,
                        )
                    )

    def test_fp011_successor_rejects_hash_and_path_overreach(self) -> None:
        with self.subTest(case="current bytes exceed receipt hash"):
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                checkpoint, archive, frozen_error = (
                    self.fp011_successor_fixture(root)
                )
                product_path = (
                    "apps/android/app/src/main/java/kr/co/hanium/"
                    "dreamup/walksafe/network/GatewayFieldSession.kt"
                )
                (root / product_path).write_bytes(b"unsealed extra change\n")

                self.assertEqual(
                    graph.filter_frozen_v23_successor_errors(
                        root,
                        [frozen_error],
                        checkpoint=checkpoint,
                        archive=archive,
                    ),
                    [frozen_error],
                )

        with self.subTest(case="different exact path is not transitive"):
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                checkpoint, archive, frozen_error = (
                    self.fp011_successor_fixture(
                        root,
                        successor_path=(
                            "apps/android/app/src/main/java/kr/co/hanium/"
                            "dreamup/walksafe/network/"
                            "AndroidGatewaySessionStore.kt"
                        ),
                    )
                )

                self.assertEqual(
                    graph.filter_frozen_v23_successor_errors(
                        root,
                        [frozen_error],
                        checkpoint=checkpoint,
                        archive=archive,
                    ),
                    [frozen_error],
                )

    def test_fp011_successor_rejects_unrelated_frozen_control_file(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            checkpoint, archive, frozen_error = (
                self.fp011_successor_fixture(
                    root,
                    historical_path=(
                        "scripts/check_walksafe_goal_graph_v2_3.py"
                    ),
                )
            )

            self.assertEqual(
                graph.filter_frozen_v23_successor_errors(
                    root,
                    [frozen_error],
                    checkpoint=checkpoint,
                    archive=archive,
                ),
                [frozen_error],
            )

    def test_fp011_successor_requires_the_final_completion_receipt(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            checkpoint, archive, frozen_error = (
                self.fp011_successor_fixture(
                    root,
                    include_receipt=False,
                )
            )

            self.assertEqual(
                graph.filter_frozen_v23_successor_errors(
                    root,
                    [frozen_error],
                    checkpoint=checkpoint,
                    archive=archive,
                ),
                [frozen_error],
            )

    def test_fp011_successor_preserves_historical_receipt_hash(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            checkpoint, archive, frozen_error = (
                self.fp011_successor_fixture(root)
            )
            predecessor_receipt = (
                "docs/control/execution/goal-results/"
                "WS-GOAL-EPIC-02-FP-010-R001/completion-receipt.json"
            )
            (root / predecessor_receipt).write_bytes(
                b'{"tampered":true}\n'
            )

            self.assertEqual(
                graph.filter_frozen_v23_successor_errors(
                    root,
                    [frozen_error],
                    checkpoint=checkpoint,
                    archive=archive,
                ),
                [frozen_error],
            )

    def test_fp011_successor_requires_exact_hash_lineage(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            checkpoint, archive, frozen_error = (
                self.fp011_successor_fixture(
                    root,
                    successor_before_sha256="f" * 64,
                )
            )

            self.assertEqual(
                graph.filter_frozen_v23_successor_errors(
                    root,
                    [frozen_error],
                    checkpoint=checkpoint,
                    archive=archive,
                ),
                [frozen_error],
            )

    def test_fp011_successor_accepts_nested_v22_transitive_lineage(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            checkpoint, archive, frozen_error = (
                self.fp011_successor_fixture(
                    root,
                    transitive_history=True,
                    frozen_error_prefix="archived v2.2: ",
                )
            )

            self.assertEqual(
                graph.filter_frozen_v23_successor_errors(
                    root,
                    [frozen_error],
                    checkpoint=checkpoint,
                    archive=archive,
                ),
                [],
            )

    def test_fp011_successor_rejects_broken_transitive_lineage(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            checkpoint, archive, frozen_error = (
                self.fp011_successor_fixture(
                    root,
                    transitive_history=True,
                    break_historical_lineage=True,
                    frozen_error_prefix="archived v2.2: ",
                )
            )

            self.assertEqual(
                graph.filter_frozen_v23_successor_errors(
                    root,
                    [frozen_error],
                    checkpoint=checkpoint,
                    archive=archive,
                ),
                [frozen_error],
            )

    def test_fp011_successor_deployment_config_scope_is_exact(self) -> None:
        exact_path = next(iter(graph.FP011_PRODUCT_EXACT_PATHS))
        for path, expected_allowed in (
            (exact_path, True),
            ("deploy/config/unrelated.env.example", False),
        ):
            with self.subTest(path=path):
                with tempfile.TemporaryDirectory() as temporary:
                    root = Path(temporary)
                    checkpoint, archive, frozen_error = (
                        self.fp011_successor_fixture(
                            root,
                            historical_path=path,
                            frozen_error_prefix="archived v2.2: ",
                        )
                    )

                    self.assertEqual(
                        graph.filter_frozen_v23_successor_errors(
                            root,
                            [frozen_error],
                            checkpoint=checkpoint,
                            archive=archive,
                        ),
                        [] if expected_allowed else [frozen_error],
                    )

    def test_fp011_successor_requires_execution_status(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            checkpoint, archive, frozen_error = (
                self.fp011_successor_fixture(
                    root,
                    fp011_status="PLANNED",
                )
            )

            self.assertEqual(
                graph.filter_frozen_v23_successor_errors(
                    root,
                    [frozen_error],
                    checkpoint=checkpoint,
                    archive=archive,
                ),
                [frozen_error],
            )

    def test_fp011_malformed_predecessor_chain_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            checkpoint, archive, frozen_error = (
                self.fp011_successor_fixture(root)
            )
            archive["goal_execution"]["dynamic_goal_inventory"][
                graph.FP011_GOAL_ID
            ]["predecessor_goal_id"] = graph.FP011_GOAL_ID

            self.assertEqual(
                graph.filter_frozen_v23_successor_errors(
                    root,
                    [frozen_error],
                    checkpoint=checkpoint,
                    archive=archive,
                ),
                [frozen_error],
            )

    def test_v24_package_tracks_imported_and_materialized_goals(
        self,
    ) -> None:
        checkpoint = load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        state = checkpoint["goal_execution"]
        manifest = load_json(ROOT / MANIFEST_RELATIVE)
        imported_paths = {
            binding["path"]
            for binding in state["imported_predecessor_goal_bindings"].values()
        }
        materialized_paths = {
            binding["path"]
            for binding in state["dynamic_goal_inventory"].values()
        }
        expected_goal_paths = imported_paths | materialized_paths

        self.assertEqual(state["package_id"], EXPECTED_PACKAGE_ID)
        self.assertEqual(
            state["static_plan_version"],
            EXPECTED_PLAN_VERSION,
        )
        self.assertEqual(set(state["goal_document_paths"]), expected_goal_paths)
        self.assertEqual(state["goal_document_count"], len(expected_goal_paths))
        self.assertEqual(
            set(state["support_paths"]),
            EXPECTED_NATIVE_SUPPORT_PATHS,
        )
        self.assertEqual(
            set(state["managed_goal_paths"]),
            expected_goal_paths | EXPECTED_NATIVE_SUPPORT_PATHS,
        )
        self.assertEqual(
            state["managed_goal_path_count"],
            len(expected_goal_paths | EXPECTED_NATIVE_SUPPORT_PATHS),
        )
        self.assertTrue(
            all(
                not path.startswith(f"{PACKAGE_RELATIVE.as_posix()}/")
                for path in imported_paths
            ),
            "v2.3 Goal documents must remain imported at original paths",
        )
        protected = {
            record["path"] for record in manifest["protected_files"]
        }
        self.assertEqual(
            protected,
            EXPECTED_NATIVE_SUPPORT_PATHS - {MANIFEST_RELATIVE.as_posix()},
        )

    def test_imported_goal_paths_and_bytes_match_frozen_v23_projection(
        self,
    ) -> None:
        checkpoint = load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        archive = load_json(ROOT / ARCHIVE_RELATIVE)
        state = checkpoint["goal_execution"]
        expected = predecessor_goal_projection(archive)
        imported = state["imported_predecessor_goal_bindings"]

        self.assertEqual(
            sha256_file(ROOT / ARCHIVE_RELATIVE),
            EXPECTED_ARCHIVE_RAW_SHA256,
        )
        self.assertEqual(set(expected), set(imported))
        self.assertEqual(len(imported), 20)
        for goal_id, predecessor in expected.items():
            with self.subTest(goal_id=goal_id):
                binding = imported[goal_id]
                self.assertEqual(binding["path"], predecessor["path"])
                self.assertEqual(binding["sha256"], predecessor["sha256"])
                self.assertEqual(
                    sha256_file(ROOT / binding["path"]),
                    predecessor["sha256"],
                )
                self.assertEqual(
                    binding["source_package_id"],
                    "WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-3",
                )

    def test_imported_goal_projection_rejects_joint_live_and_binding_sha_drift(
        self,
    ) -> None:
        checkpoint = copy.deepcopy(
            load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        )
        archive = load_json(ROOT / ARCHIVE_RELATIVE)
        goal_id = next(
            iter(
                checkpoint["goal_execution"][
                    "imported_predecessor_goal_bindings"
                ]
            )
        )
        checkpoint["goal_execution"]["imported_predecessor_goal_bindings"][
            goal_id
        ]["sha256"] = "f" * 64

        with mock.patch.object(
            graph.continuation,
            "sha256_file",
            return_value="f" * 64,
        ):
            errors = graph.validate_imported_goal_projection(
                ROOT,
                checkpoint,
                archive,
            )

        self.assertIn(
            f"{goal_id}: predecessor Goal SHA-256 differs",
            errors,
        )

    def test_imported_completion_lineage_matches_frozen_v23_events(
        self,
    ) -> None:
        checkpoint = load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        archive = load_json(ROOT / ARCHIVE_RELATIVE)
        imported = checkpoint["goal_execution"][
            "imported_predecessor_goal_bindings"
        ]
        expected = predecessor_completion_projection(archive)

        self.assertTrue(expected)
        for goal_id, event_sha256 in expected.items():
            with self.subTest(goal_id=goal_id):
                self.assertEqual(
                    imported[goal_id]["completion_event_sha256"],
                    event_sha256,
                )

    def test_runtime_focus_follows_tail_without_restarting_sequence_numbers(
        self,
    ) -> None:
        checkpoint = load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        state = checkpoint["goal_execution"]
        history = state["transition_history"]

        self.assertEqual(
            state["focus_goal_id"],
            history[-1]["runtime_after"]["focus_goal_id"],
        )
        self.assertEqual(
            history[0]["event_type"],
            "PACKAGE_PREPARED",
        )
        self.assertEqual(history[0]["sequence"], 1)
        self.assertLessEqual(history[0]["sequence"], len(history))
        self.assertEqual(
            [event["sequence"] for event in history],
            list(range(1, len(history) + 1)),
        )
        if len(history) >= 2:
            self.assertEqual(history[1]["event_type"], "PACKAGE_ACTIVATED")
        if len(history) >= 3:
            self.assertEqual(history[2]["event_type"], "GOAL_STARTED")
            self.assertEqual(
                history[2]["subject_goal_id"],
                EXPECTED_FOCUS_GOAL_ID,
            )


class FP014CanonicalCompletionTests(unittest.TestCase):
    EXACT_IMPLEMENTATION_PATHS = (
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
        (
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
            "session/PermissionSessionPolicy.kt"
        ),
        (
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
            "fieldlog/FieldSessionLog.kt"
        ),
        (
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
            "session/PermissionSessionPolicyTest.kt"
        ),
        (
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
            "PermissionSessionLifecycleStaticTest.kt"
        ),
        (
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
            "MainActivityAccessibilityStaticTest.kt"
        ),
        (
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
            "MainActivityWalkSessionLifecycleStaticTest.kt"
        ),
        (
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
            "fieldlog/PersistentFieldSessionLogTest.kt"
        ),
        (
            "scripts/build_walksafe_fp014_permission_denial_"
            "revocation_trace_20260726.py"
        ),
        (
            "tests/test_walksafe_fp014_permission_denial_"
            "revocation_trace_20260726.py"
        ),
        "scripts/run_walksafe_test_layers_20260711.sh",
    )
    EXACT_LOG_PATHS = (
        (
            "docs/control/execution/goal-results/"
            "WS-GOAL-EPIC-02-FP-014-R001/logs/focused-android-tests.log"
        ),
        (
            "docs/control/execution/goal-results/"
            "WS-GOAL-EPIC-02-FP-014-R001/logs/full-android-verification.log"
        ),
        (
            "docs/control/execution/goal-results/"
            "WS-GOAL-EPIC-02-FP-014-R001/logs/"
            "android-gateway-verification.log"
        ),
        (
            "docs/control/execution/goal-results/"
            "WS-GOAL-EPIC-02-FP-014-R001/logs/"
            "android-gateway-boundary.log"
        ),
        (
            "docs/control/execution/goal-results/"
            "WS-GOAL-EPIC-02-FP-014-R001/logs/"
            "control-plane-verification.log"
        ),
    )

    def setUp(self) -> None:
        self.checkpoint = load_json(ROOT / graph.CHECKPOINT_RELATIVE)

    def _current_successor_artifacts(
        self,
    ) -> tuple[dict, dict, dict, dict]:
        npc = graph._npc_single_admin_recovery_live_compatibility_artifacts(
            ROOT,
            self.checkpoint,
        )
        self.assertIsInstance(npc, dict)
        errors, _, fp046 = graph.validate_fp046_r014_successor_authority(
            ROOT,
            self.checkpoint,
            successor_artifacts=npc,
        )
        self.assertEqual(errors, [])
        fp012 = graph._fp012_successor_product_artifacts(
            ROOT,
            self.checkpoint,
            successor_artifacts=fp046,
        )
        self.assertIsInstance(fp012, dict)
        fp016 = graph._fp016_successor_product_artifacts(
            ROOT,
            self.checkpoint,
            successor_artifacts={**fp046, **fp012},
        )
        self.assertIsInstance(fp016, dict)
        return npc, fp046, fp012, fp016

    def _fp047_precompletion_checkpoint(self) -> dict:
        checkpoint = copy.deepcopy(self.checkpoint)
        state = checkpoint["goal_execution"]
        history = [
            event
            for event in state["transition_history"]
            if event["sequence"] <= 36
        ]
        resume = history[-1]
        self.assertEqual(resume["sequence"], 36)
        runtime = resume["runtime_after"]
        state["transition_history"] = history
        state["transition_history_anchor_sha256"] = resume["event_sha256"]
        state["status_by_goal"][graph.FP047_GOAL_ID] = "IN_PROGRESS"
        state["goal_status"] = "IN_PROGRESS"
        for field in (
            "focus_goal_id",
            "focus_goal_path",
            "focus_work_item_id",
            "focus_source",
            "ready_frontier_goal_ids",
            "artifact_work_queue",
            "completion_boundary",
        ):
            state[field] = copy.deepcopy(runtime[field])
        state["completion_evidence_by_goal"].pop(
            graph.FP047_GOAL_ID,
            None,
        )
        state["validation_cutoff_at"] = resume["occurred_at"]
        checkpoint["canonical_bindings"] = [
            binding
            for binding in checkpoint["canonical_bindings"]
            if binding["role"] != graph.FP047_COMPLETION_ROLE
        ]
        return checkpoint

    def test_fp014_package_and_literal_scope_are_pinned(self) -> None:
        artifacts = graph._fp014_successor_product_artifacts(
            ROOT,
            self.checkpoint,
            require_live_successors=False,
        )
        self.assertIsInstance(artifacts, dict)
        self.assertEqual(
            graph.validate_fp014_canonical_completion(
                ROOT,
                self.checkpoint,
            ),
            [],
        )
        self.assertEqual(
            graph.FP014_IMPLEMENTATION_PATHS,
            self.EXACT_IMPLEMENTATION_PATHS,
        )
        self.assertEqual(
            tuple(graph.FP014_LOG_SHA256_BY_PATH),
            self.EXACT_LOG_PATHS,
        )
        self.assertEqual(len(graph.FP014_JSON_SHA256_BY_PATH), 7)
        self.assertFalse(hasattr(graph, "FP016_GOAL_PATH"))
        self.assertFalse(hasattr(graph, "FP016_GOAL_TITLE"))

    def test_fp014_tail_rejects_path_dependency_and_reordering(self) -> None:
        for mutation in ("path", "dependency", "order"):
            with self.subTest(mutation=mutation):
                checkpoint = copy.deepcopy(self.checkpoint)
                history = checkpoint["goal_execution"]["transition_history"]
                by_sequence = {
                    event["sequence"]: event
                    for event in history
                }
                if mutation == "path":
                    by_sequence[22]["materialized_goal_path"] += ".stale"
                elif mutation == "dependency":
                    by_sequence[23]["readiness_basis"] = {}
                else:
                    index22 = history.index(by_sequence[22])
                    index23 = history.index(by_sequence[23])
                    history[index22], history[index23] = (
                        history[index23],
                        history[index22],
                    )
                self.assertIsNone(
                    graph._fp014_successor_product_artifacts(
                        ROOT,
                        checkpoint,
                        require_live_successors=False,
                    )
                )

    def test_fp014_canonical_tail_is_independent_of_later_live_suffix(
        self,
    ) -> None:
        completion_binding = graph._binding_by_role(
            self.checkpoint,
            graph.FP014_COMPLETION_ROLE,
        )
        self.assertTrue(
            graph._fp014_completion_transition_matches(
                ROOT,
                self.checkpoint,
                completion_binding,
            )
        )
        self.assertEqual(
            graph.validate_fp014_canonical_completion(
                ROOT,
                self.checkpoint,
            ),
            [],
        )

    def test_fp016_completion_extends_product_lineage(self) -> None:
        projection = graph._materialization_projection_at_sequence(
            self.checkpoint["goal_execution"],
            self.checkpoint["goal_execution"]["transition_history"],
            28,
        )
        npc, _, _, fp016 = self._current_successor_artifacts()
        combined = graph._fp014_successor_product_artifacts(
            ROOT,
            self.checkpoint,
            require_live_successors=False,
            successor_artifacts=npc,
        )
        self.assertIsInstance(fp016, dict)
        self.assertEqual(len(fp016), 10)
        self.assertIsInstance(combined, dict)
        self.assertTrue(set(fp016).issubset(combined))
        self.assertIsInstance(projection, tuple)
        self.assertNotIn(graph.FP047_PARENT_GOAL_ID, projection[1])

    def test_fp016_runtime_amendment_rejects_third_digest(self) -> None:
        relative = "apps/android/gradle/verification-metadata.xml"
        sealed = (
            "0f2fc21ad52bd81b877f4a2cecdf587841f4dcac2c87e0e3139a0f94374c0084"
        )
        expected = (
            sealed,
            "eaa662a434257a71c595ab510889657579e5e5a107041813338a257b56674d17",
        )
        self.assertEqual(
            graph._sealed_start_gate_runtime_successor(ROOT, relative, sealed),
            expected,
        )

        original = graph.continuation.sha256_file

        def third_digest(path: Path) -> str:
            if path == ROOT / relative:
                return "f" * 64
            return original(path)

        with mock.patch.object(
            graph.continuation,
            "sha256_file",
            side_effect=third_digest,
        ):
            self.assertIsNone(
                graph._sealed_start_gate_runtime_successor(
                    ROOT,
                    relative,
                    sealed,
                )
            )

    def test_fp016_transition_tamper_is_rejected(self) -> None:
        checkpoint = copy.deepcopy(self.checkpoint)
        event = next(
            row
            for row in checkpoint["goal_execution"]["transition_history"]
            if row["sequence"] == 27
        )
        event["predecessor_goal_id"] = graph.FP014_GOAL_ID
        self.assertIsNone(
            graph._fp016_successor_product_artifacts(
                ROOT,
                checkpoint,
            )
        )

    def test_fp047_runner_gate_remediation_binds_successful_start(
        self,
    ) -> None:
        after_sha256 = graph._fp047_gate_remediation_after_sha256(
            ROOT,
            self.checkpoint,
            graph.FP047_GATE_REMEDIATION_PRE_SHA256,
        )
        _, _, _, artifacts = self._current_successor_artifacts()

        self.assertEqual(
            after_sha256,
            graph.FP047_GATE_REMEDIATION_POST_SHA256,
        )
        self.assertIsInstance(artifacts, dict)
        self.assertEqual(
            artifacts[graph.FP047_GATE_REMEDIATION_RUNNER_PATH][1],
            graph.FP047_GATE_REMEDIATION_POST_SHA256,
        )

    def test_fp047_runner_gate_remediation_allows_scoped_live_change(
        self,
    ) -> None:
        original = graph.continuation.sha256_file

        def tampered(path: Path) -> str:
            if path == ROOT / graph.FP047_GATE_REMEDIATION_RUNNER_PATH:
                return "0" * 64
            return original(path)

        with mock.patch.object(
            graph.continuation,
            "sha256_file",
            side_effect=tampered,
        ):
            self.assertEqual(
                graph._fp047_gate_remediation_after_sha256(
                    ROOT,
                    self.checkpoint,
                    graph.FP047_GATE_REMEDIATION_PRE_SHA256,
                ),
                graph.FP047_GATE_REMEDIATION_POST_SHA256,
            )

    def test_fp047_runner_gate_remediation_rejects_log_tamper(
        self,
    ) -> None:
        original = graph.continuation.sha256_file
        target = ROOT / next(
            iter(graph.FP047_FAILED_GATE_LOG_SHA256_BY_PATH)
        )

        def tampered(path: Path) -> str:
            if path == target:
                return "0" * 64
            return original(path)

        with mock.patch.object(
            graph.continuation,
            "sha256_file",
            side_effect=tampered,
        ):
            self.assertIsNone(
                graph._fp047_gate_remediation_after_sha256(
                    ROOT,
                    self.checkpoint,
                    graph.FP047_GATE_REMEDIATION_PRE_SHA256,
                )
            )

    def test_fp047_runner_gate_remediation_rejects_status_tamper(
        self,
    ) -> None:
        for status in ("PLANNED", "READY", "COMPLETE_AT_TARGET"):
            with self.subTest(status=status):
                checkpoint = self._fp047_precompletion_checkpoint()
                checkpoint["goal_execution"]["status_by_goal"][
                    graph.FP047_GOAL_ID
                ] = status
                self.assertIsNone(
                    graph._fp047_gate_remediation_after_sha256(
                        ROOT,
                        checkpoint,
                        graph.FP047_GATE_REMEDIATION_PRE_SHA256,
                    )
                )

    def _fp047_successful_start_fixture(
        self,
    ) -> tuple[dict, dict, dict, str, str, str]:
        checkpoint = self._fp047_precompletion_checkpoint()
        state = checkpoint["goal_execution"]
        start_index = next(
            (
                index
                for index, event in enumerate(
                    state["transition_history"]
                )
                if (
                    event.get("event_type") == "GOAL_STARTED"
                    and event.get("subject_goal_id")
                    == graph.FP047_GOAL_ID
                )
            ),
            None,
        )
        if start_index is not None:
            del state["transition_history"][start_index:]
            state["status_by_goal"][graph.FP047_GOAL_ID] = "READY"
        ready = state["transition_history"][-1]
        event_id = (
            "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP047-20260726-003"
        )
        gate_directory = f"docs/control/execution/goal-gates/{event_id}"
        receipt_path = (
            f"{gate_directory}/implementation-start-gate-receipt.json"
        )
        repository_state_path = (
            f"{gate_directory}/19-REPOSITORY_STATE.log"
        )
        receipt_sha256 = "1" * 64
        repository_state_sha256 = "2" * 64
        snapshot = {
            "gate_event_id": event_id,
            "gate_repository_state_output_sha256": (
                repository_state_sha256
            ),
        }
        document_id = event_id.replace(
            "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-",
            "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-",
            1,
        )
        check_runs = [
            {
                "check_id": f"CHECK_{index:02d}",
                "exit_code": 0,
            }
            for index in range(1, 19)
        ]
        check_runs.append(
            {
                "check_id": "REPOSITORY_STATE",
                "exit_code": 0,
                "output_path": repository_state_path,
                "output_sha256": repository_state_sha256,
            }
        )
        receipt = {
            "schema_version": "1.0",
            "document_id": document_id,
            "evidence_type": "IMPLEMENTATION_START_OR_RESUME_GATE",
            "gate_purpose": "INITIAL_START",
            "status": "PASS",
            "package_id": graph.V24_PACKAGE_ID,
            "target_transition_event_id": event_id,
            "target_goal_id": graph.FP047_GOAL_ID,
            "target_goal_content_sha256": graph.FP047_GOAL_SHA256,
            "repository_snapshot": snapshot,
            "check_runs": check_runs,
        }
        started = {
            "sequence": ready["sequence"] + 1,
            "event_id": event_id,
            "event_type": "GOAL_STARTED",
            "subject_goal_id": graph.FP047_GOAL_ID,
            "from_status": "READY",
            "to_status": "IN_PROGRESS",
            "status_changes": {graph.FP047_GOAL_ID: "IN_PROGRESS"},
            "previous_event_sha256": ready["event_sha256"],
            "implementation_start_gate_binding": {
                "document_id": document_id,
                "path": receipt_path,
                "file_sha256": receipt_sha256,
            },
            "repository_snapshot_before": snapshot,
        }
        started["event_sha256"] = graph.continuation.event_sha256(started)
        state["transition_history"].append(started)
        state["status_by_goal"][graph.FP047_GOAL_ID] = "IN_PROGRESS"
        repository_state = {
            "schema_version": "1.0.0",
            "evidence_type": "GATE_REPOSITORY_STATE",
            "gate_event_id": event_id,
            "dirty_snapshot": {
                "records": [
                    {
                        "path": graph.FP047_GATE_REMEDIATION_RUNNER_PATH,
                        "worktree": {
                            "state": "PRESENT",
                            "type": "REGULAR_FILE",
                            "sha256": (
                                graph.FP047_GATE_REMEDIATION_POST_SHA256
                            ),
                        },
                    }
                ],
            },
        }
        return (
            checkpoint,
            receipt,
            repository_state,
            receipt_path,
            repository_state_path,
            repository_state_sha256,
        )

    def test_fp047_runner_gate_remediation_binds_success_snapshot(
        self,
    ) -> None:
        (
            checkpoint,
            receipt,
            repository_state,
            receipt_path,
            repository_state_path,
            repository_state_sha256,
        ) = self._fp047_successful_start_fixture()
        original_load = graph._load_exact_json
        original_exact = graph._exact_repo_file
        original_sha256 = graph.continuation.sha256_file

        def load(root: Path, relative: object) -> dict | None:
            if relative == receipt_path:
                return copy.deepcopy(receipt)
            if relative == repository_state_path:
                return copy.deepcopy(repository_state)
            return original_load(root, relative)

        def exact(root: Path, relative: object) -> Path | None:
            if relative in {receipt_path, repository_state_path}:
                return root / str(relative)
            return original_exact(root, relative)

        def sha256(path: Path) -> str:
            if path == ROOT / receipt_path:
                return "1" * 64
            if path == ROOT / repository_state_path:
                return repository_state_sha256
            return original_sha256(path)

        with (
            mock.patch.object(graph, "_load_exact_json", side_effect=load),
            mock.patch.object(graph, "_exact_repo_file", side_effect=exact),
            mock.patch.object(
                graph.continuation,
                "sha256_file",
                side_effect=sha256,
            ),
        ):
            self.assertEqual(
                graph._fp047_gate_remediation_after_sha256(
                    ROOT,
                    checkpoint,
                    graph.FP047_GATE_REMEDIATION_PRE_SHA256,
                ),
                graph.FP047_GATE_REMEDIATION_POST_SHA256,
            )

            receipt["repository_snapshot"] = {
                **receipt["repository_snapshot"],
                "gate_repository_state_output_sha256": "0" * 64,
            }
            self.assertIsNone(
                graph._fp047_gate_remediation_after_sha256(
                    ROOT,
                    checkpoint,
                    graph.FP047_GATE_REMEDIATION_PRE_SHA256,
                )
            )

    def test_fp047_successful_start_snapshot_builds_scoped_live_overlay(
        self,
    ) -> None:
        relative = "apps/android-gateway/src/routes.ts"
        artifacts = graph._fp047_successful_start_snapshot_artifacts(
            ROOT,
            self.checkpoint,
        )
        live_sha256 = graph.continuation.sha256_file(ROOT / relative)

        self.assertIsInstance(artifacts, dict)
        self.assertEqual(
            graph._fp047_start_snapshot_successor(
                ROOT,
                self.checkpoint,
                relative,
                artifacts[relative],
            ),
            (artifacts[relative], live_sha256),
        )
        self.assertIsNone(
            graph._fp047_start_snapshot_successor(
                ROOT,
                self.checkpoint,
                relative,
                "0" * 64,
            )
        )
        runner = graph.FP047_GATE_REMEDIATION_RUNNER_PATH
        _, _, _, fp016 = self._current_successor_artifacts()
        self.assertEqual(
            fp016[runner][1],
            graph.FP047_GATE_REMEDIATION_POST_SHA256,
        )
        self.assertEqual(
            artifacts[runner],
            graph.FP047_GATE_REMEDIATION_POST_SHA256,
        )
        self.assertEqual(
            graph._fp047_start_snapshot_successor(
                ROOT,
                self.checkpoint,
                runner,
                artifacts[runner],
            ),
            (
                artifacts[runner],
                graph.continuation.sha256_file(ROOT / runner),
            ),
        )
        self.assertEqual(len(graph.FP047_ACTIVE_SCOPE_PATHS), 31)
        self.assertEqual(
            len(set(graph.FP047_ACTIVE_SCOPE_PATHS)),
            len(graph.FP047_ACTIVE_SCOPE_PATHS),
        )

    def test_npc_precompletion_successor_uses_frozen_r003_context(self) -> None:
        with mock.patch.object(
            graph.npc_review,
            "prepare_review_context",
            side_effect=AssertionError("mutable R003 context must not be used"),
        ):
            artifacts = (
                graph._npc_single_admin_recovery_precompletion_live_compatibility_artifacts(
                    ROOT
                )
            )
        self.assertIsInstance(artifacts, dict)
        self.assertEqual(len(artifacts), 55)

    def test_live_product_compatibility_does_not_replay_completion_credit(
        self,
    ) -> None:
        npc = {"npc": ("1" * 64, "2" * 64)}
        fp022 = {"fp022": ("3" * 64, "4" * 64)}
        security = {"security": ("5" * 64, "6" * 64)}
        with (
            mock.patch.object(
                graph,
                "_npc_single_admin_recovery_precompletion_live_compatibility_artifacts",
                return_value=npc,
            ) as product_loader,
            mock.patch.object(
                graph,
                "_npc_single_admin_recovery_sealed_product_successor_artifacts",
                side_effect=AssertionError("completion credit was replayed"),
            ) as sealed_loader,
            mock.patch.object(
                graph,
                "_fp022_completion_start_to_final_transitions",
                return_value=fp022,
            ) as fp022_loader,
            mock.patch.object(
                graph,
                "_current_security_database_compatibility_artifacts",
                return_value=security,
            ) as security_loader,
        ):
            self.assertEqual(
                graph._npc_single_admin_recovery_live_compatibility_artifacts(
                    ROOT,
                    self.checkpoint,
                ),
                {**npc, **fp022, **security},
            )
        product_loader.assert_called_once_with(ROOT)
        fp022_loader.assert_called_once_with(
            ROOT,
            self.checkpoint,
            require_completion_review=False,
        )
        security_loader.assert_called_once_with(ROOT)
        sealed_loader.assert_not_called()

    def test_fp047_successful_start_snapshot_rejects_payload_tamper(
        self,
    ) -> None:
        event = next(
            row
            for row in self.checkpoint["goal_execution"][
                "transition_history"
            ]
            if (
                row.get("event_type") == "GOAL_STARTED"
                and row.get("subject_goal_id") == graph.FP047_GOAL_ID
            )
        )
        state_path = (
            "docs/control/execution/goal-gates/"
            f"{event['event_id']}/19-REPOSITORY_STATE.log"
        )
        original = graph._load_exact_json

        def tampered(root: Path, relative: object) -> dict | None:
            document = original(root, relative)
            if document is None:
                return None
            result = copy.deepcopy(document)
            if relative == state_path:
                result["dirty_snapshot"]["content_set_sha256"] = "0" * 64
            return result

        with mock.patch.object(
            graph,
            "_load_exact_json",
            side_effect=tampered,
        ):
            self.assertIsNone(
                graph._fp047_successful_start_snapshot_artifacts(
                    ROOT,
                    self.checkpoint,
                )
            )

    def test_fp047_successful_start_snapshot_rejects_event_and_receipt_tamper(
        self,
    ) -> None:
        checkpoint = copy.deepcopy(self.checkpoint)
        event = next(
            row
            for row in checkpoint["goal_execution"]["transition_history"]
            if (
                row.get("event_type") == "GOAL_STARTED"
                and row.get("subject_goal_id") == graph.FP047_GOAL_ID
            )
        )
        event["repository_snapshot_before"]["content_set_sha256"] = "0" * 64
        self.assertIsNone(
            graph._fp047_successful_start_snapshot_artifacts(
                ROOT,
                checkpoint,
            )
        )

        event = next(
            row
            for row in self.checkpoint["goal_execution"][
                "transition_history"
            ]
            if (
                row.get("event_type") == "GOAL_STARTED"
                and row.get("subject_goal_id") == graph.FP047_GOAL_ID
            )
        )
        receipt_path = event["implementation_start_gate_binding"]["path"]
        original = graph._load_exact_json

        def tampered(root: Path, relative: object) -> dict | None:
            document = original(root, relative)
            if document is None:
                return None
            result = copy.deepcopy(document)
            if relative == receipt_path:
                result["repository_snapshot"]["content_set_sha256"] = (
                    "0" * 64
                )
            return result

        with mock.patch.object(
            graph,
            "_load_exact_json",
            side_effect=tampered,
        ):
            self.assertIsNone(
                graph._fp047_successful_start_snapshot_artifacts(
                    ROOT,
                    self.checkpoint,
                )
            )

    def test_fp047_start_snapshot_successor_rejects_missing_mismatch_and_scope(
        self,
    ) -> None:
        relative = "apps/android-gateway/src/routes.ts"
        with mock.patch.object(
            graph,
            "_fp047_successful_start_snapshot_artifacts",
            return_value={},
        ):
            self.assertIsNone(
                graph._fp047_start_snapshot_successor(
                    ROOT,
                    self.checkpoint,
                    relative,
                    "0" * 64,
                )
            )

        with mock.patch.object(
            graph,
            "_fp047_successful_start_snapshot_artifacts",
            return_value={relative: "2" * 64},
        ):
            self.assertIsNone(
                graph._fp047_start_snapshot_successor(
                    ROOT,
                    self.checkpoint,
                    relative,
                    "1" * 64,
                )
            )
            self.assertIsNone(
                graph._fp047_start_snapshot_successor(
                    ROOT,
                    self.checkpoint,
                    "README.md",
                    "1" * 64,
                )
            )

    def _fp047_completion_fixture(self) -> tuple[Path, dict]:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        checkpoint = self._fp047_precompletion_checkpoint()
        checkpoint["schema_version"] = "1.24.0"
        state = checkpoint["goal_execution"]
        history = state["transition_history"]
        resume = history[-1]
        self.assertEqual(resume["sequence"], 36)

        parent_path = root / graph.FP047_PARENT_GOAL_RELATIVE
        parent_path.parent.mkdir(parents=True, exist_ok=True)
        parent_path.write_text("FP047 parent workstream\n", encoding="utf-8")
        parent_sha256 = sha256_file(parent_path)

        gap = {
            "metadata": {
                "report_id": (
                    "WS-IMPLEMENTATION-GAP-ANALYSIS-20260726-021"
                ),
                "version": "0.21.0",
            },
            "summary": {
                "status_counts": {
                    "BLOCKED": 5,
                    "CONFLICTING": 16,
                    "EVIDENCE_MISSING": 4,
                    "MISSING": 11,
                    "PARTIAL": 32,
                    "IMPLEMENTED": 0,
                }
            },
            "assessments": [
                {
                    "gap_id": "GAP-056",
                    "source_policy_id": "FP-047",
                    "status": "PARTIAL",
                }
            ],
            "reassessment_scope": {
                "directly_reassessed_gap_ids": ["GAP-056"],
                "next_adjacent_gap": {
                    "gap_id": "GAP-057",
                    "source_policy_id": "FP-048",
                },
            },
            "report_content_sha256": "a" * 64,
        }
        backlog = {
            "metadata": {
                "backlog_id": (
                    "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-"
                    "20260726-021"
                ),
                "version": "0.21.0",
            },
            "gap_report_content_sha256": gap["report_content_sha256"],
            "next_action_sequence": [
                {
                    "source_policy_id": "FP-047",
                    "status": "PARTIAL",
                },
                {
                    "source_policy_id": "FP-048",
                    "status": "PARTIAL",
                },
            ],
            "next_single_action": {
                "source_policy_id": "FP-048",
                "gap_id": "GAP-057",
            },
        }
        gap_requirement = graph.FP047_R021_BINDING_REQUIREMENTS[
            "IMPLEMENTATION_GAP"
        ]
        backlog_requirement = graph.FP047_R021_BINDING_REQUIREMENTS[
            "IMPLEMENTATION_BACKLOG"
        ]
        gap_sha256 = write_json(root, gap_requirement["path"], gap)
        backlog_sha256 = write_json(
            root,
            backlog_requirement["path"],
            backlog,
        )

        changed_artifacts = []
        for relative in graph.FP047_ACTIVE_SCOPE_PATHS:
            artifact_path = root / relative
            artifact_path.parent.mkdir(parents=True, exist_ok=True)
            artifact_path.write_text(
                f"FP047 active fixture: {relative}\n",
                encoding="utf-8",
            )
            changed_artifacts.append(
                {
                    "path": relative,
                    "after_sha256": sha256_file(artifact_path),
                }
            )
        content_set_sha256 = graph._implementation_content_set_sha256(
            changed_artifacts
        )
        verification_checks = [
            {
                "check_id": "FP047-FIXTURE-CHECK-001",
                "status": "PASS",
                "implementation_content_set_sha256": content_set_sha256,
            }
        ]
        result_documents = {
            "IMPLEMENTATION_RECORD": {
                "schema_version": "1.0",
                "document_id": (
                    graph.FP047_RESULT_EVIDENCE_CONTRACT[0][2]
                ),
                "goal_id": graph.FP047_GOAL_ID,
                "kind": "IMPLEMENTATION_RECORD",
                "status": "PASS",
                "changed_artifacts": changed_artifacts,
                "implementation_content_set_sha256": content_set_sha256,
                "completion_boundary": copy.deepcopy(
                    graph.FP047_REVIEW_SUBJECT_BOUNDARY
                ),
            },
            "VERIFICATION_RESULT": {
                "schema_version": "1.0",
                "document_id": (
                    graph.FP047_RESULT_EVIDENCE_CONTRACT[1][2]
                ),
                "goal_id": graph.FP047_GOAL_ID,
                "kind": "VERIFICATION_RESULT",
                "status": "PASS",
                "checks": verification_checks,
                "evidence_boundary": copy.deepcopy(
                    graph.FP047_REVIEW_SUBJECT_BOUNDARY
                ),
            },
            "SUCCESSOR_TRACE": {
                "schema_version": "1.0",
                "document_id": (
                    graph.FP047_RESULT_EVIDENCE_CONTRACT[2][2]
                ),
                "goal_id": graph.FP047_GOAL_ID,
                "kind": "SUCCESSOR_TRACE",
                "status": "PASS",
                "canonical_update_event_type": (
                    "CANONICAL_BINDINGS_UPDATED"
                ),
                "changed_subject_ids_by_role": {
                    "IMPLEMENTATION_BACKLOG": ["FP-047"],
                    "IMPLEMENTATION_GAP": ["FP-047", "GAP-056"],
                },
                "next_policy_gap_pair": {
                    "source_policy_id": "FP-048",
                    "gap_id": "GAP-057",
                },
                "completion_boundary": copy.deepcopy(
                    graph.FP047_REVIEW_SUBJECT_BOUNDARY
                ),
                "resulting_canonical_bindings": {
                    "IMPLEMENTATION_BACKLOG": {
                        "role": "IMPLEMENTATION_BACKLOG",
                        "document_id": backlog_requirement["document_id"],
                        "path": backlog_requirement["path"],
                        "file_sha256": backlog_sha256,
                    },
                    "IMPLEMENTATION_GAP": {
                        "role": "IMPLEMENTATION_GAP",
                        "document_id": gap_requirement["document_id"],
                        "path": gap_requirement["path"],
                        "file_sha256": gap_sha256,
                    },
                },
            },
        }
        result_evidence = []
        result_hashes = {}
        for kind, relative, _ in graph.FP047_RESULT_EVIDENCE_CONTRACT:
            digest = write_json(root, relative, result_documents[kind])
            result_hashes[kind] = digest
            result_evidence.append(
                {
                    "kind": kind,
                    "path": relative,
                    "sha256": digest,
                }
            )

        review_subject = {
            "schema_version": "1.0",
            "evidence_type": "INTERNAL_REVIEW_SUBJECT",
            "goal_id": graph.FP047_GOAL_ID,
            "reviewed_result_sha256_by_kind": result_hashes,
            "implementation_scope": {
                "scope": "EXACT_31_PATH_SET",
                "exact_path_count": 31,
                "product_path_count": 26,
                "tooling_path_count": 5,
                "product_paths": list(
                    graph.FP047_ACTIVE_SCOPE_PATHS[:26]
                ),
                "tooling_paths": list(
                    graph.FP047_ACTIVE_SCOPE_PATHS[26:]
                ),
                "content_set_sha256": content_set_sha256,
            },
            "adminapp_junit_evidence": {},
            "verification_receipts": verification_checks,
            "completion_boundary": copy.deepcopy(
                graph.FP047_REVIEW_SUBJECT_BOUNDARY
            ),
        }
        review_subject_sha256 = write_json(
            root,
            graph.FP047_REVIEW_SUBJECT_PATH,
            review_subject,
        )
        reviewed_at = "2026-07-26T18:00:00+09:00"
        findings = {"blocking": 0, "major_open": 0}
        attestation = {
            "schema_version": "1.0",
            "evidence_type": "INTERNAL_REVIEW_ATTESTATION",
            "goal_id": graph.FP047_GOAL_ID,
            "decision": "APPROVED",
            "reviewer_id": graph.FP047_REVIEWER_ID,
            "reviewer_task": graph.FP047_REVIEWER_TASK,
            "review_subject_sha256": review_subject_sha256,
            "reviewed_result_sha256_by_kind": result_hashes,
            "reviewed_at": reviewed_at,
            "findings": findings,
            "review_boundary": copy.deepcopy(
                graph.FP047_REVIEW_BOUNDARY
            ),
        }
        attestation_sha256 = write_json(
            root,
            graph.FP047_REVIEW_ATTESTATION_PATH,
            attestation,
        )
        reviewer_path = graph.FP047_REVIEW_PATH
        review = {
            "schema_version": "1.0",
            "document_id": graph.FP047_REVIEW_DOCUMENT_ID,
            "evidence_type": "INDEPENDENT_INTERNAL_REVIEW",
            "goal_id": graph.FP047_GOAL_ID,
            "status": "PASS",
            "reviewer_id": graph.FP047_REVIEWER_ID,
            "reviewer_task": graph.FP047_REVIEWER_TASK,
            "review_subject_sha256": review_subject_sha256,
            "reviewed_result_sha256_by_kind": result_hashes,
            "reviewed_at": reviewed_at,
            "attestation_provenance": {
                "path": graph.FP047_REVIEW_ATTESTATION_PATH,
                "sha256": attestation_sha256,
            },
            "findings": findings,
            "review_boundary": copy.deepcopy(
                graph.FP047_REVIEW_BOUNDARY
            ),
        }
        reviewer_sha256 = write_json(root, reviewer_path, review)

        for relative in graph.FP047_OUTPUT_MANIFEST_PATHS:
            path = root / relative
            if path.exists():
                continue
            path.parent.mkdir(parents=True, exist_ok=True)
            if path.suffix == ".json":
                write_json(root, relative, {"fixture_path": relative})
            else:
                path.write_text(
                    f"FP047 fixture: {relative}\n",
                    encoding="utf-8",
                )
        output_manifest = [
            {
                "path": relative,
                "sha256": sha256_file(root / relative),
            }
            for relative in graph.FP047_OUTPUT_MANIFEST_PATHS
        ]
        start = next(
            event
            for event in history
            if (
                event.get("event_type") == "GOAL_STARTED"
                and event.get("subject_goal_id") == graph.FP047_GOAL_ID
            )
        )
        receipt = {
            "schema_version": "1.0",
            "document_id": graph.FP047_COMPLETION_DOCUMENT_ID,
            "evidence_type": "WORK_ITEM_EXECUTION_RECEIPT",
            "status": "ACCEPTED",
            "result": "PASS",
            "target_goal_id": graph.FP047_GOAL_ID,
            "target_goal_content_sha256": graph.FP047_GOAL_SHA256,
            "work_item_id": graph.FP047_WORK_ITEM_ID,
            "source_policy_ids": ["FP-047"],
            "gap_ids": ["GAP-056"],
            "target_completion_level": (
                "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
            ),
            "execution_start_event_sha256": start["event_sha256"],
            "execution_session_event": {
                "sequence": resume["sequence"],
                "event_id": resume["event_id"],
                "event_type": resume["event_type"],
                "event_sha256": resume["event_sha256"],
            },
            "implementation_start_gate_binding": start[
                "implementation_start_gate_binding"
            ],
            "execution_window": {
                "started_at": resume["occurred_at"],
                "ended_at": reviewed_at,
            },
            "completed_at": reviewed_at,
            "executor": {
                "id": (
                    "CODEX-FP047-USER-ADMIN-LOGIN-AUTHORIZATION-"
                    "SEPARATION-IMPLEMENTER-20260726-001"
                ),
                "task": "/root",
                "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
                "authority": (
                    "GRAPH_V2_4_STANDING_EXECUTION_AUTHORITY"
                ),
            },
            "reviewer": {
                "id": graph.FP047_REVIEWER_ID,
                "task": graph.FP047_REVIEWER_TASK,
                "role": "SEPARATE_INTERNAL_REVIEWER",
                "separate_internal_review_pass": True,
                "external_independence_claimed": False,
                "authority": "INTERNAL_REPOSITORY_CONTROL",
                "decision": "APPROVED",
                "decided_at": reviewed_at,
            },
            "reviewer_provenance": {
                "path": reviewer_path,
                "sha256": reviewer_sha256,
            },
            "result_evidence": result_evidence,
            "output_evidence_manifest": output_manifest,
            "output_evidence_manifest_sha256": (
                graph.continuation.canonical_json_sha256(
                    output_manifest
                )
            ),
            "completion_boundary": copy.deepcopy(
                graph.FP047_COMPLETION_BOUNDARY
            ),
            "generated_at": reviewed_at,
        }
        receipt_sha256 = write_json(
            root,
            graph.FP047_COMPLETION_PATH,
            receipt,
        )

        replacements = {
            "IMPLEMENTATION_BACKLOG": {
                "role": "IMPLEMENTATION_BACKLOG",
                "document_id": backlog_requirement["document_id"],
                "path": backlog_requirement["path"],
                "identity_json_path": backlog_requirement[
                    "identity_json_path"
                ],
                "file_sha256": backlog_sha256,
                "mutable": False,
            },
            "IMPLEMENTATION_GAP": {
                "role": "IMPLEMENTATION_GAP",
                "document_id": gap_requirement["document_id"],
                "path": gap_requirement["path"],
                "identity_json_path": gap_requirement[
                    "identity_json_path"
                ],
                "file_sha256": gap_sha256,
                "mutable": False,
            },
        }
        checkpoint["canonical_bindings"] = [
            replacements.get(row["role"], row)
            for row in checkpoint["canonical_bindings"]
        ]
        completion_binding = {
            "role": graph.FP047_COMPLETION_ROLE,
            "document_id": graph.FP047_COMPLETION_DOCUMENT_ID,
            "path": graph.FP047_COMPLETION_PATH,
            "identity_json_path": "document_id",
            "file_sha256": receipt_sha256,
            "mutable": False,
        }
        checkpoint["canonical_bindings"].append(completion_binding)
        canonical_snapshot = (
            graph.continuation.canonical_binding_snapshot(checkpoint)
        )
        completion_projection = {
            key: completion_binding[key]
            for key in ("role", "document_id", "path", "file_sha256")
        }

        update = copy.deepcopy(
            next(event for event in history if event["sequence"] == 30)
        )
        update.update(
            {
                "sequence": 37,
                "event_id": graph.FP047_CANONICAL_UPDATE_EVENT_ID,
                "event_type": "CANONICAL_BINDINGS_UPDATED",
                "occurred_on": "2026-07-26",
                "occurred_at": "2026-07-26T18:00:00+09:00",
                "previous_focus_goal_id": graph.FP047_GOAL_ID,
                "previous_focus_content_sha256": (
                    graph.FP047_GOAL_SHA256
                ),
                "focus_goal_id": graph.FP047_GOAL_ID,
                "focus_goal_content_sha256": graph.FP047_GOAL_SHA256,
                "from_status": "IN_PROGRESS",
                "to_status": "IN_PROGRESS",
                "status_changes": {},
                "runtime_after": copy.deepcopy(resume["runtime_after"]),
                "blockers_after": {},
                "blocker_resolution_ids_after": [],
                "source_checkpoint_version": "1.23.0",
                "evidence_refs": [
                    "IMPLEMENTATION_BACKLOG",
                    "IMPLEMENTATION_GAP",
                    graph.FP047_COMPLETION_ROLE,
                ],
                "changed_binding_roles": [
                    "IMPLEMENTATION_BACKLOG",
                    "IMPLEMENTATION_GAP",
                    graph.FP047_COMPLETION_ROLE,
                ],
                "changed_subject_ids_by_role": {
                    "IMPLEMENTATION_BACKLOG": ["FP-047"],
                    "IMPLEMENTATION_GAP": ["FP-047", "GAP-056"],
                },
                "impact_closure_goal_ids": [
                    graph.FP047_PARENT_GOAL_ID
                ],
                "impact_disposition_by_goal": {
                    graph.FP047_PARENT_GOAL_ID: {
                        "result": "REVALIDATION_REFRESH_REQUIRED",
                        "target_status": "READY",
                    }
                },
                "reopened_completion_event_sha256_by_goal": {},
                "canonical_binding_snapshot_after": canonical_snapshot,
                "produced_by_goal_id": graph.FP047_GOAL_ID,
                "produced_binding_roles": [
                    "IMPLEMENTATION_BACKLOG",
                    "IMPLEMENTATION_GAP",
                ],
                "producer_output_subject_ids_by_role": {
                    "IMPLEMENTATION_BACKLOG": ["FP-047"],
                    "IMPLEMENTATION_GAP": ["FP-047", "GAP-056"],
                },
                "producer_completion_receipt_binding": (
                    completion_projection
                ),
                "previous_event_sha256": resume["event_sha256"],
            }
        )
        update["event_sha256"] = (
            graph.continuation.event_sha256(update)
        )

        completion_evidence = copy.deepcopy(
            state["completion_evidence_by_goal"]
        )
        completion_evidence[graph.FP047_GOAL_ID] = [
            graph.FP047_COMPLETION_ROLE
        ]
        resume_boundary = resume["runtime_after"]["completion_boundary"]
        completion_boundary = {
            **copy.deepcopy(resume_boundary),
            "internal_runnable_goal_ids": [
                graph.FP047_PARENT_GOAL_ID,
                "WS-GOAL-EPIC-12",
            ],
            "internal_pending_goal_ids": [
                goal_id
                for goal_id in resume_boundary["internal_pending_goal_ids"]
                if goal_id != graph.FP047_GOAL_ID
            ],
        }
        completion_runtime = {
            **copy.deepcopy(resume["runtime_after"]),
            "focus_goal_id": graph.FP047_PARENT_GOAL_ID,
            "focus_goal_path": graph.FP047_PARENT_GOAL_RELATIVE,
            "focus_work_item_id": "",
            "focus_source": "WORKSTREAM_GRAPH",
            "ready_frontier_goal_ids": [
                graph.FP047_PARENT_GOAL_ID,
                "WS-GOAL-EPIC-12",
            ],
            "completion_boundary": completion_boundary,
        }
        completion = copy.deepcopy(
            next(event for event in history if event["sequence"] == 31)
        )
        completion.update(
            {
                "sequence": 38,
                "event_id": graph.FP047_COMPLETION_EVENT_ID,
                "event_type": "GOAL_COMPLETED",
                "occurred_on": "2026-07-26",
                "occurred_at": "2026-07-26T18:00:01+09:00",
                "previous_focus_goal_id": graph.FP047_GOAL_ID,
                "previous_focus_content_sha256": (
                    graph.FP047_GOAL_SHA256
                ),
                "focus_goal_id": graph.FP047_PARENT_GOAL_ID,
                "focus_goal_content_sha256": parent_sha256,
                "from_status": "IN_PROGRESS",
                "to_status": "COMPLETE_AT_TARGET",
                "status_changes": {
                    graph.FP047_GOAL_ID: "COMPLETE_AT_TARGET"
                },
                "runtime_after": completion_runtime,
                "blockers_after": {},
                "blocker_resolution_ids_after": [],
                "source_checkpoint_version": "1.23.0",
                "evidence_refs": [graph.FP047_COMPLETION_ROLE],
                "subject_goal_id": graph.FP047_GOAL_ID,
                "completion_evidence_bindings": {
                    graph.FP047_COMPLETION_ROLE: completion_projection
                },
                "completion_receipt_binding": completion_projection,
                "completion_evidence_by_goal_after": (
                    completion_evidence
                ),
                "canonical_update_event_sha256": (
                    update["event_sha256"]
                ),
                "previous_event_sha256": update["event_sha256"],
                "canonical_binding_snapshot_after": canonical_snapshot,
            }
        )
        completion["event_sha256"] = (
            graph.continuation.event_sha256(completion)
        )
        history.extend([update, completion])
        state["status_by_goal"][graph.FP047_GOAL_ID] = (
            "COMPLETE_AT_TARGET"
        )
        state["goal_status"] = "READY"
        state["focus_goal_id"] = graph.FP047_PARENT_GOAL_ID
        state["focus_goal_path"] = graph.FP047_PARENT_GOAL_RELATIVE
        state["focus_work_item_id"] = ""
        state["focus_source"] = "WORKSTREAM_GRAPH"
        state["ready_frontier_goal_ids"] = completion_runtime[
            "ready_frontier_goal_ids"
        ]
        state["artifact_work_queue"] = completion_runtime[
            "artifact_work_queue"
        ]
        state["completion_boundary"] = completion_boundary
        state["completion_evidence_by_goal"] = completion_evidence
        state["pending_producer_completion_goal_id"] = ""
        state["transition_history_anchor_sha256"] = completion[
            "event_sha256"
        ]
        state["validation_cutoff_at"] = completion["occurred_at"]
        return root, checkpoint

    def _fp047_fixture_json(self, root: Path, relative: str) -> dict:
        return graph.load_json(root / relative)

    def _fp047_rebind_receipt(
        self,
        root: Path,
        checkpoint: dict,
        receipt: dict,
    ) -> None:
        receipt_sha256 = write_json(
            root,
            graph.FP047_COMPLETION_PATH,
            receipt,
        )
        binding = next(
            row
            for row in checkpoint["canonical_bindings"]
            if row["role"] == graph.FP047_COMPLETION_ROLE
        )
        binding["file_sha256"] = receipt_sha256
        projection = {
            key: binding[key]
            for key in ("role", "document_id", "path", "file_sha256")
        }
        snapshot = graph.continuation.canonical_binding_snapshot(
            checkpoint
        )
        by_sequence = {
            event["sequence"]: event
            for event in checkpoint["goal_execution"][
                "transition_history"
            ]
        }
        update = by_sequence[37]
        completion = by_sequence[38]
        update["canonical_binding_snapshot_after"] = copy.deepcopy(
            snapshot
        )
        update["producer_completion_receipt_binding"] = copy.deepcopy(
            projection
        )
        completion["canonical_binding_snapshot_after"] = copy.deepcopy(
            snapshot
        )
        completion["completion_evidence_bindings"][
            graph.FP047_COMPLETION_ROLE
        ] = copy.deepcopy(projection)
        completion["completion_receipt_binding"] = copy.deepcopy(
            projection
        )
        self._rehash_fp047_completion_fixture(checkpoint)

    def _fp047_reseal_evidence(
        self,
        root: Path,
        checkpoint: dict,
        *,
        sync_result_maps: bool = False,
        sync_subject_hash: bool = False,
        sync_attestation_provenance: bool = True,
    ) -> None:
        receipt = self._fp047_fixture_json(
            root,
            graph.FP047_COMPLETION_PATH,
        )
        result_hashes = {}
        for row in receipt["result_evidence"]:
            digest = sha256_file(root / row["path"])
            row["sha256"] = digest
            result_hashes[row["kind"]] = digest

        subject = self._fp047_fixture_json(
            root,
            graph.FP047_REVIEW_SUBJECT_PATH,
        )
        if sync_result_maps:
            subject["reviewed_result_sha256_by_kind"] = result_hashes
        subject_sha256 = write_json(
            root,
            graph.FP047_REVIEW_SUBJECT_PATH,
            subject,
        )
        attestation = self._fp047_fixture_json(
            root,
            graph.FP047_REVIEW_ATTESTATION_PATH,
        )
        review = self._fp047_fixture_json(
            root,
            graph.FP047_REVIEW_PATH,
        )
        if sync_result_maps:
            attestation[
                "reviewed_result_sha256_by_kind"
            ] = result_hashes
            review["reviewed_result_sha256_by_kind"] = result_hashes
        if sync_subject_hash:
            attestation["review_subject_sha256"] = subject_sha256
            review["review_subject_sha256"] = subject_sha256
        attestation_sha256 = write_json(
            root,
            graph.FP047_REVIEW_ATTESTATION_PATH,
            attestation,
        )
        if sync_attestation_provenance:
            review["attestation_provenance"] = {
                "path": graph.FP047_REVIEW_ATTESTATION_PATH,
                "sha256": attestation_sha256,
            }
        review_sha256 = write_json(
            root,
            graph.FP047_REVIEW_PATH,
            review,
        )
        for row in receipt["output_evidence_manifest"]:
            row["sha256"] = sha256_file(root / row["path"])
        receipt["reviewer_provenance"]["sha256"] = review_sha256
        receipt["output_evidence_manifest_sha256"] = (
            graph.continuation.canonical_json_sha256(
                receipt["output_evidence_manifest"]
            )
        )
        self._fp047_rebind_receipt(root, checkpoint, receipt)

    def _rehash_fp047_completion_fixture(
        self,
        checkpoint: dict,
        *,
        include_resume: bool = False,
    ) -> None:
        state = checkpoint["goal_execution"]
        by_sequence = {
            event["sequence"]: event
            for event in state["transition_history"]
        }
        resume = by_sequence[36]
        update = by_sequence[37]
        completion = by_sequence[38]
        if include_resume:
            resume["event_sha256"] = (
                graph.continuation.event_sha256(resume)
            )
        update["previous_event_sha256"] = resume["event_sha256"]
        update["event_sha256"] = (
            graph.continuation.event_sha256(update)
        )
        completion["previous_event_sha256"] = update["event_sha256"]
        completion["canonical_update_event_sha256"] = update[
            "event_sha256"
        ]
        completion["event_sha256"] = (
            graph.continuation.event_sha256(completion)
        )
        state["transition_history_anchor_sha256"] = completion[
            "event_sha256"
        ]

    def test_fp047_completion_is_inert_before_declaration(self) -> None:
        checkpoint = self._fp047_precompletion_checkpoint()
        self.assertFalse(
            graph._fp047_is_declared_complete(checkpoint)
        )
        self.assertEqual(
            graph.validate_fp047_canonical_completion(
                ROOT,
                checkpoint,
            ),
            [],
        )

    def test_fp047_completion_accepts_planned_seq37_38(self) -> None:
        root, checkpoint = self._fp047_completion_fixture()
        self.assertTrue(
            graph._fp047_completion_transition_matches(
                root,
                checkpoint,
            )
        )
        self.assertEqual(
            graph.validate_fp047_canonical_completion(
                root,
                checkpoint,
            ),
            [],
        )

    def test_fp047_completion_uses_seq37_history_after_canonical_successor(
        self,
    ) -> None:
        root, checkpoint = self._fp047_completion_fixture()
        successor_by_role = {
            "IMPLEMENTATION_BACKLOG": {
                "document_id": (
                    "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260802-023"
                ),
                "path": (
                    "docs/control/audits/"
                    "walksafe-implementation-remediation-backlog-"
                    "20260802-r023.json"
                ),
                "file_sha256": "1" * 64,
            },
            "IMPLEMENTATION_GAP": {
                "document_id": (
                    "WS-IMPLEMENTATION-GAP-ANALYSIS-20260802-023"
                ),
                "path": (
                    "docs/control/audits/"
                    "walksafe-implementation-gap-analysis-"
                    "20260802-r023.json"
                ),
                "file_sha256": "2" * 64,
            },
        }
        for binding in checkpoint["canonical_bindings"]:
            successor = successor_by_role.get(binding["role"])
            if successor is not None:
                binding.update(successor)

        self.assertTrue(
            graph._fp047_completion_transition_matches(root, checkpoint)
        )
        self.assertEqual(
            graph.validate_fp047_canonical_completion(root, checkpoint),
            [],
        )

        update = next(
            event
            for event in checkpoint["goal_execution"]["transition_history"]
            if event["sequence"] == 37
        )
        update["canonical_binding_snapshot_after"][
            "IMPLEMENTATION_GAP"
        ]["file_sha256"] = "3" * 64
        update["event_sha256"] = graph.continuation.event_sha256(update)
        completion = checkpoint["goal_execution"]["transition_history"][37]
        completion["previous_event_sha256"] = update["event_sha256"]
        completion["canonical_update_event_sha256"] = update["event_sha256"]
        completion["event_sha256"] = graph.continuation.event_sha256(
            completion
        )
        checkpoint["goal_execution"][
            "transition_history_anchor_sha256"
        ] = completion["event_sha256"]
        self.assertFalse(
            graph._fp047_completion_transition_matches(root, checkpoint)
        )

    def _checkpoint_with_later_event(self) -> dict:
        checkpoint = copy.deepcopy(self.checkpoint)
        state = checkpoint["goal_execution"]
        history = state["transition_history"][:38]
        state["transition_history"] = history
        state["transition_history_anchor_sha256"] = history[-1][
            "event_sha256"
        ]
        later_goals = (
            graph.FP048_ANDROID_REPORT_GOAL_ID,
            graph.FP008_ADMIN_REVIEW_GOAL_ID,
            graph.FP046_GOAL_ID,
            graph.NPC_SINGLE_ADMIN_RECOVERY_GOAL_ID,
        )
        for goal_id in later_goals:
            state["status_by_goal"].pop(goal_id, None)
            state["dynamic_goal_inventory"].pop(goal_id, None)
            state["completion_evidence_by_goal"].pop(goal_id, None)
        checkpoint["canonical_bindings"] = [
            binding
            for binding in checkpoint["canonical_bindings"]
            if binding.get("role")
            not in {
                graph.FP048_ANDROID_REPORT_COMPLETION_ROLE,
                graph.FP008_ADMIN_REVIEW_COMPLETION_ROLE,
                graph.FP046_COMPLETION_ROLE,
                graph.NPC_SINGLE_ADMIN_RECOVERY_COMPLETION_ROLE,
            }
        ]
        state["materialized_child_goal_ids_by_parent"][
            "WS-GOAL-EPIC-03"
        ] = ["WS-GOAL-EPIC-03-FP-047-R001"]
        predecessor = history[-1]
        self.assertEqual(predecessor["sequence"], 38)
        later = {
            "sequence": 39,
            "event_id": "WS-GOAL-GRAPH-V2-4-LATER-EVENT-TEST",
            "event_type": "CANONICAL_BINDINGS_UPDATED",
            "occurred_on": "2026-07-29",
            "occurred_at": "2026-07-29T03:00:00+09:00",
            "previous_event_sha256": predecessor["event_sha256"],
            "focus_goal_id": "WS-GOAL-EPIC-12",
            "status_changes": {},
        }
        later["event_sha256"] = graph.continuation.event_sha256(later)
        history.append(later)
        checkpoint["schema_version"] = "1.25.0"
        state["transition_history_anchor_sha256"] = later["event_sha256"]
        state["focus_goal_id"] = "WS-GOAL-EPIC-12"
        return checkpoint

    def test_fp047_completion_accepts_valid_later_event_suffix(
        self,
    ) -> None:
        checkpoint = self._checkpoint_with_later_event()

        self.assertTrue(
            graph._fp047_completion_transition_matches(ROOT, checkpoint)
        )
        self.assertEqual(
            graph.validate_fp047_canonical_completion(ROOT, checkpoint),
            [],
        )
        self.assertTrue(graph._fp014_is_declared_complete(checkpoint))
        self.assertFalse(
            graph._fp048_android_report_successor_is_declared(checkpoint)
        )

    def test_pre_fp048_checkpoint_retains_exact_packet_live_binding(
        self,
    ) -> None:
        checkpoint = self._checkpoint_with_later_event()
        packet = load_json(
            ROOT / graph.PHASE1_ANDROID_REPORT_SUCCESSOR_EVIDENCE_RELATIVE
        )
        rows = (
            packet["source_test_binding"]["sources"]
            + packet["source_test_binding"]["tests"]
        )
        packet_bindings = {
            row["exact_path"]: row["sha256"] for row in rows
        }
        historical_sizes = {
            row["exact_path"]: row["byte_length"]
            for row in rows
        }
        original_sha256 = graph.continuation.sha256_file
        original_stat = Path.stat

        def historical_sha256(path: Path) -> str:
            try:
                relative = path.relative_to(ROOT).as_posix()
            except ValueError:
                return original_sha256(path)
            return packet_bindings.get(relative, original_sha256(path))

        def historical_stat(path: Path, *args, **kwargs):
            result = original_stat(path, *args, **kwargs)
            try:
                relative = path.relative_to(ROOT).as_posix()
            except ValueError:
                return result
            expected_size = historical_sizes.get(relative)
            if expected_size is None:
                return result
            values = list(result)
            values[6] = expected_size
            return os.stat_result(values)

        with mock.patch.object(
            graph.continuation,
            "sha256_file",
            side_effect=historical_sha256,
        ), mock.patch.object(
            Path,
            "stat",
            side_effect=historical_stat,
            autospec=True,
        ), mock.patch.object(
            graph,
            "validate_fp046_r014_successor_authority",
            return_value=([], {}, {}),
        ):
            errors, bindings = (
                graph.validate_phase1_android_report_successor_binding(
                    ROOT,
                    checkpoint,
                )
            )
        self.assertEqual(errors, [])
        self.assertEqual(bindings, packet_bindings)

    def test_fp047_completion_rejects_tampered_later_event_suffix(
        self,
    ) -> None:
        for mutation in ("event_hash", "previous_hash", "anchor"):
            with self.subTest(mutation=mutation):
                checkpoint = self._checkpoint_with_later_event()
                state = checkpoint["goal_execution"]
                later = state["transition_history"][-1]
                if mutation == "event_hash":
                    later["event_sha256"] = "0" * 64
                    state["transition_history_anchor_sha256"] = "0" * 64
                elif mutation == "previous_hash":
                    later["previous_event_sha256"] = "0" * 64
                    later["event_sha256"] = (
                        graph.continuation.event_sha256(later)
                    )
                    state["transition_history_anchor_sha256"] = later[
                        "event_sha256"
                    ]
                else:
                    state["transition_history_anchor_sha256"] = "0" * 64
                self.assertFalse(
                    graph._fp047_completion_transition_matches(
                        ROOT,
                        checkpoint,
                    )
                )

    def test_fp047_completion_rejects_semantic_tampering(self) -> None:
        for mutation in (
            "producer_subjects",
            "stale_r020",
            "canonical_snapshot",
            "fake_fp048_focus",
            "runtime_frontier",
            "evidence_map",
            "seq36_session",
            "event_order",
        ):
            with self.subTest(mutation=mutation):
                root, checkpoint = self._fp047_completion_fixture()
                state = checkpoint["goal_execution"]
                by_sequence = {
                    event["sequence"]: event
                    for event in state["transition_history"]
                }
                update = by_sequence[37]
                completion = by_sequence[38]
                if mutation == "producer_subjects":
                    update["producer_output_subject_ids_by_role"][
                        "IMPLEMENTATION_GAP"
                    ] = ["FP-047"]
                elif mutation == "stale_r020":
                    for event in (update, completion):
                        event["canonical_binding_snapshot_after"][
                            "IMPLEMENTATION_GAP"
                        ]["path"] = (
                            "docs/control/audits/"
                            "walksafe-implementation-gap-analysis-"
                            "20260726-r020.json"
                        )
                elif mutation == "canonical_snapshot":
                    completion["canonical_binding_snapshot_after"][
                        "IMPLEMENTATION_BACKLOG"
                    ]["file_sha256"] = "0" * 64
                elif mutation == "fake_fp048_focus":
                    completion["focus_goal_id"] = (
                        "WS-GOAL-EPIC-03-FP-048-R001"
                    )
                    completion["runtime_after"]["focus_goal_id"] = (
                        "WS-GOAL-EPIC-03-FP-048-R001"
                    )
                    state["focus_goal_id"] = (
                        "WS-GOAL-EPIC-03-FP-048-R001"
                    )
                elif mutation == "runtime_frontier":
                    completion["runtime_after"][
                        "ready_frontier_goal_ids"
                    ] = ["WS-GOAL-EPIC-12"]
                    state["ready_frontier_goal_ids"] = [
                        "WS-GOAL-EPIC-12"
                    ]
                elif mutation == "evidence_map":
                    completion["completion_evidence_by_goal_after"].pop(
                        graph.FP047_GOAL_ID
                    )
                    state["completion_evidence_by_goal"].pop(
                        graph.FP047_GOAL_ID,
                        None,
                    )
                elif mutation == "seq36_session":
                    by_sequence[36][
                        "previous_execution_session_event_sha256"
                    ] = "0" * 64
                else:
                    history = state["transition_history"]
                    update_index = history.index(update)
                    completion_index = history.index(completion)
                    history[update_index], history[completion_index] = (
                        history[completion_index],
                        history[update_index],
                    )
                self._rehash_fp047_completion_fixture(
                    checkpoint,
                    include_resume=mutation == "seq36_session",
                )
                self.assertFalse(
                    graph._fp047_completion_transition_matches(
                        root,
                        checkpoint,
                    )
                )

    def test_fp047_completion_rejects_receipt_hash_tamper(self) -> None:
        root, checkpoint = self._fp047_completion_fixture()
        original = graph.continuation.sha256_file
        receipt_path = root / graph.FP047_COMPLETION_PATH

        def tampered(path: Path) -> str:
            if path == receipt_path:
                return "0" * 64
            return original(path)

        with mock.patch.object(
            graph.continuation,
            "sha256_file",
            side_effect=tampered,
        ):
            self.assertFalse(
                graph._fp047_completion_transition_matches(
                    root,
                    checkpoint,
                )
            )

    def test_fp047_completion_rejects_rehashed_boundary_and_results(
        self,
    ) -> None:
        for mutation in (
            "boundary",
            "result_path",
            "result_duplicate",
            "result_order",
        ):
            with self.subTest(mutation=mutation):
                root, checkpoint = self._fp047_completion_fixture()
                receipt = self._fp047_fixture_json(
                    root,
                    graph.FP047_COMPLETION_PATH,
                )
                if mutation == "boundary":
                    receipt["completion_boundary"][
                        "formal_test_status"
                    ] = "PASS"
                elif mutation == "result_path":
                    row = receipt["result_evidence"][0]
                    row["path"] = graph.FP047_REVIEW_SUBJECT_PATH
                    row["sha256"] = sha256_file(
                        root / graph.FP047_REVIEW_SUBJECT_PATH
                    )
                elif mutation == "result_duplicate":
                    receipt["result_evidence"][1] = copy.deepcopy(
                        receipt["result_evidence"][0]
                    )
                else:
                    receipt["result_evidence"][0:2] = reversed(
                        receipt["result_evidence"][0:2]
                    )
                self._fp047_rebind_receipt(
                    root,
                    checkpoint,
                    receipt,
                )
                self.assertFalse(
                    graph._fp047_completion_transition_matches(
                        root,
                        checkpoint,
                    )
                )

    def test_fp047_completion_rejects_rehashed_manifest_tampering(
        self,
    ) -> None:
        for mutation in (
            "membership",
            "order",
            "path",
            "hash",
        ):
            with self.subTest(mutation=mutation):
                root, checkpoint = self._fp047_completion_fixture()
                receipt = self._fp047_fixture_json(
                    root,
                    graph.FP047_COMPLETION_PATH,
                )
                manifest = receipt["output_evidence_manifest"]
                if mutation == "membership":
                    manifest.pop()
                elif mutation == "order":
                    manifest[0], manifest[1] = (
                        manifest[1],
                        manifest[0],
                    )
                elif mutation == "path":
                    manifest[0]["path"] = (
                        graph.FP047_REVIEW_SUBJECT_PATH
                    )
                    manifest[0]["sha256"] = sha256_file(
                        root / graph.FP047_REVIEW_SUBJECT_PATH
                    )
                else:
                    manifest[0]["sha256"] = "0" * 64
                receipt["output_evidence_manifest_sha256"] = (
                    graph.continuation.canonical_json_sha256(manifest)
                )
                self._fp047_rebind_receipt(
                    root,
                    checkpoint,
                    receipt,
                )
                self.assertFalse(
                    graph._fp047_completion_transition_matches(
                        root,
                        checkpoint,
                    )
                )

    def test_fp047_completion_rejects_rehashed_review_provenance(
        self,
    ) -> None:
        for mutation in ("reviewer", "attestation"):
            with self.subTest(mutation=mutation):
                root, checkpoint = self._fp047_completion_fixture()
                if mutation == "reviewer":
                    receipt = self._fp047_fixture_json(
                        root,
                        graph.FP047_COMPLETION_PATH,
                    )
                    receipt["reviewer_provenance"]["path"] = (
                        graph.FP047_REVIEW_SUBJECT_PATH
                    )
                    receipt["reviewer_provenance"]["sha256"] = (
                        sha256_file(
                            root / graph.FP047_REVIEW_SUBJECT_PATH
                        )
                    )
                    self._fp047_rebind_receipt(
                        root,
                        checkpoint,
                        receipt,
                    )
                else:
                    review = self._fp047_fixture_json(
                        root,
                        graph.FP047_REVIEW_PATH,
                    )
                    review["attestation_provenance"] = {
                        "path": graph.FP047_REVIEW_SUBJECT_PATH,
                        "sha256": sha256_file(
                            root / graph.FP047_REVIEW_SUBJECT_PATH
                        ),
                    }
                    write_json(
                        root,
                        graph.FP047_REVIEW_PATH,
                        review,
                    )
                    self._fp047_reseal_evidence(
                        root,
                        checkpoint,
                        sync_attestation_provenance=False,
                    )
                self.assertFalse(
                    graph._fp047_completion_transition_matches(
                        root,
                        checkpoint,
                    )
                )

    def test_fp047_completion_rejects_rehashed_subject_tampering(
        self,
    ) -> None:
        for mutation in ("content_set", "result_hash"):
            with self.subTest(mutation=mutation):
                root, checkpoint = self._fp047_completion_fixture()
                subject = self._fp047_fixture_json(
                    root,
                    graph.FP047_REVIEW_SUBJECT_PATH,
                )
                if mutation == "content_set":
                    subject["implementation_scope"][
                        "content_set_sha256"
                    ] = "0" * 64
                else:
                    subject["reviewed_result_sha256_by_kind"][
                        "IMPLEMENTATION_RECORD"
                    ] = "0" * 64
                write_json(
                    root,
                    graph.FP047_REVIEW_SUBJECT_PATH,
                    subject,
                )
                self._fp047_reseal_evidence(
                    root,
                    checkpoint,
                    sync_subject_hash=True,
                )
                self.assertFalse(
                    graph._fp047_completion_transition_matches(
                        root,
                        checkpoint,
                    )
                )

    def test_fp047_completion_rejects_resealed_disk_hash_mismatch(
        self,
    ) -> None:
        root, checkpoint = self._fp047_completion_fixture()
        implementation_path = (
            graph.FP047_RESULT_EVIDENCE_CONTRACT[0][1]
        )
        implementation = self._fp047_fixture_json(
            root,
            implementation_path,
        )
        implementation["changed_artifacts"][0][
            "after_sha256"
        ] = "0" * 64
        forged_content_set = graph._implementation_content_set_sha256(
            implementation["changed_artifacts"]
        )
        implementation[
            "implementation_content_set_sha256"
        ] = forged_content_set
        write_json(root, implementation_path, implementation)

        verification_path = (
            graph.FP047_RESULT_EVIDENCE_CONTRACT[1][1]
        )
        verification = self._fp047_fixture_json(
            root,
            verification_path,
        )
        for check in verification["checks"]:
            check[
                "implementation_content_set_sha256"
            ] = forged_content_set
        write_json(root, verification_path, verification)

        subject = self._fp047_fixture_json(
            root,
            graph.FP047_REVIEW_SUBJECT_PATH,
        )
        subject["implementation_scope"][
            "content_set_sha256"
        ] = forged_content_set
        write_json(
            root,
            graph.FP047_REVIEW_SUBJECT_PATH,
            subject,
        )
        self._fp047_reseal_evidence(
            root,
            checkpoint,
            sync_result_maps=True,
            sync_subject_hash=True,
        )
        self.assertFalse(
            graph._fp047_completion_transition_matches(
                root,
                checkpoint,
            )
        )

    def test_fp047_completion_rejects_resealed_result_boundaries(
        self,
    ) -> None:
        for mutation in (
            "implementation",
            "successor",
            "successor_deleted",
        ):
            with self.subTest(mutation=mutation):
                root, checkpoint = self._fp047_completion_fixture()
                contract_index = 0 if mutation == "implementation" else 2
                result_path = (
                    graph.FP047_RESULT_EVIDENCE_CONTRACT[
                        contract_index
                    ][1]
                )
                result = self._fp047_fixture_json(root, result_path)
                if mutation == "successor_deleted":
                    result.pop("completion_boundary")
                else:
                    boundary = copy.deepcopy(
                        graph.FP047_REVIEW_SUBJECT_BOUNDARY
                    )
                    boundary["release_status"] = "ELIGIBLE"
                    result["completion_boundary"] = boundary
                write_json(root, result_path, result)
                self._fp047_reseal_evidence(
                    root,
                    checkpoint,
                    sync_result_maps=True,
                    sync_subject_hash=True,
                )
                self.assertFalse(
                    graph._fp047_completion_transition_matches(
                        root,
                        checkpoint,
                    )
                )

    def test_fp047_completion_rejects_resealed_execution_window(
        self,
    ) -> None:
        for mutation in (
            "missing_started_at",
            "bogus_started_at",
            "timezone_naive",
            "invalid_order",
        ):
            with self.subTest(mutation=mutation):
                root, checkpoint = self._fp047_completion_fixture()
                receipt = self._fp047_fixture_json(
                    root,
                    graph.FP047_COMPLETION_PATH,
                )
                if mutation == "missing_started_at":
                    receipt["execution_window"].pop("started_at")
                    self._fp047_rebind_receipt(
                        root,
                        checkpoint,
                        receipt,
                    )
                elif mutation == "bogus_started_at":
                    receipt["execution_window"]["started_at"] = (
                        "2026-07-26T00:00:00+09:00"
                    )
                    self._fp047_rebind_receipt(
                        root,
                        checkpoint,
                        receipt,
                    )
                else:
                    timestamp = (
                        "2026-07-26T18:00:00"
                        if mutation == "timezone_naive"
                        else "2026-07-25T00:00:00+09:00"
                    )
                    receipt["execution_window"]["ended_at"] = timestamp
                    receipt["completed_at"] = timestamp
                    receipt["generated_at"] = timestamp
                    receipt["reviewer"]["decided_at"] = timestamp
                    write_json(
                        root,
                        graph.FP047_COMPLETION_PATH,
                        receipt,
                    )
                    review = self._fp047_fixture_json(
                        root,
                        graph.FP047_REVIEW_PATH,
                    )
                    review["reviewed_at"] = timestamp
                    write_json(
                        root,
                        graph.FP047_REVIEW_PATH,
                        review,
                    )
                    attestation = self._fp047_fixture_json(
                        root,
                        graph.FP047_REVIEW_ATTESTATION_PATH,
                    )
                    attestation["reviewed_at"] = timestamp
                    write_json(
                        root,
                        graph.FP047_REVIEW_ATTESTATION_PATH,
                        attestation,
                    )
                    self._fp047_reseal_evidence(root, checkpoint)
                self.assertFalse(
                    graph._fp047_completion_transition_matches(
                        root,
                        checkpoint,
                    )
                )

    def test_fp012_completion_extends_product_lineage(self) -> None:
        npc, _, fp012, _ = self._current_successor_artifacts()
        combined = graph._fp014_successor_product_artifacts(
            ROOT,
            self.checkpoint,
            require_live_successors=False,
            successor_artifacts=npc,
        )
        receipt = graph._load_exact_json(ROOT, graph.FP012_COMPLETION_PATH)
        implementation_binding = next(
            row
            for row in receipt["result_evidence"]
            if row["kind"] == "IMPLEMENTATION_RECORD"
        )
        implementation = graph._load_exact_json(
            ROOT,
            implementation_binding["path"],
        )
        expected_count = sum(
            row["change_kind"] == "MODIFIED"
            for row in implementation["changed_artifacts"]
        )
        self.assertIsInstance(fp012, dict)
        self.assertEqual(len(fp012), expected_count)
        self.assertIsInstance(combined, dict)
        self.assertTrue(set(fp012).issubset(combined))
        main_activity = self.EXACT_IMPLEMENTATION_PATHS[0]
        self.assertEqual(combined[main_activity][1], fp012[main_activity][1])

    def test_fp012_lineage_accepts_only_legal_fp047_successor_states(
        self,
    ) -> None:
        _, fp046, _, _ = self._current_successor_artifacts()
        completed = copy.deepcopy(self.checkpoint)
        completed["goal_execution"]["status_by_goal"][
            graph.FP047_GOAL_ID
        ] = "COMPLETE_AT_TARGET"
        completed["goal_execution"]["focus_goal_id"] = (
            graph.FP047_PARENT_GOAL_ID
        )
        with mock.patch.object(
            graph,
            "_fp047_completion_transition_matches",
            return_value=True,
        ):
            self.assertIsInstance(
                graph._fp012_successor_product_artifacts(
                    ROOT,
                    completed,
                    successor_artifacts=fp046,
                ),
                dict,
            )

        for status in ("BLOCKED", "PLANNED"):
            with self.subTest(status=status):
                invalid = copy.deepcopy(self.checkpoint)
                invalid["goal_execution"]["status_by_goal"][
                    graph.FP047_GOAL_ID
                ] = status
                self.assertIsNone(
                    graph._fp012_successor_product_artifacts(
                        ROOT,
                        invalid,
                        successor_artifacts=fp046,
                    )
                )

    def test_fp012_completion_receipt_tamper_is_rejected(self) -> None:
        original = graph._load_exact_json

        def tampered(root: Path, relative: object) -> dict | None:
            document = original(root, relative)
            if document is None:
                return None
            result = copy.deepcopy(document)
            if relative == graph.FP012_COMPLETION_PATH:
                result["document_id"] = graph.FP016_COMPLETION_DOCUMENT_ID
            return result

        with mock.patch.object(graph, "_load_exact_json", side_effect=tampered):
            self.assertIsNone(
                graph._fp012_successor_product_artifacts(
                    ROOT,
                    self.checkpoint,
                )
            )

    def test_fp012_r020_canonical_binding_tamper_is_rejected(self) -> None:
        checkpoint = copy.deepcopy(self.checkpoint)
        event = next(
            row
            for row in checkpoint["goal_execution"]["transition_history"]
            if row["sequence"] == 30
        )
        event["canonical_binding_snapshot_after"][
            "IMPLEMENTATION_BACKLOG"
        ]["file_sha256"] = "0" * 64
        self.assertIsNone(
            graph._fp012_successor_product_artifacts(ROOT, checkpoint)
        )

    def test_fp012_fp047_readiness_tamper_is_rejected(self) -> None:
        checkpoint = copy.deepcopy(self.checkpoint)
        event = next(
            row
            for row in checkpoint["goal_execution"]["transition_history"]
            if row["sequence"] == 33
        )
        event["readiness_basis"] = {}
        self.assertIsNone(
            graph._fp012_successor_product_artifacts(ROOT, checkpoint)
        )

    def test_fp012_fp047_predecessor_tamper_is_rejected(self) -> None:
        for mutation in ("event_sha", "inventory_parent", "inventory_sha"):
            with self.subTest(mutation=mutation):
                checkpoint = copy.deepcopy(self.checkpoint)
                if mutation == "event_sha":
                    event = next(
                        row
                        for row in checkpoint["goal_execution"][
                            "transition_history"
                        ]
                        if row["sequence"] == 32
                    )
                    event["predecessor_goal_content_sha256"] = (
                        graph.FP016_GOAL_SHA256
                    )
                else:
                    fp047 = checkpoint["goal_execution"][
                        "dynamic_goal_inventory"
                    ][graph.FP047_GOAL_ID]
                    if mutation == "inventory_parent":
                        fp047["parent_goal_id"] = "WS-GOAL-EPIC-02"
                    else:
                        fp047["predecessor_goal_content_sha256"] = (
                            graph.FP016_GOAL_SHA256
                        )
                self.assertIsNone(
                    graph._fp012_successor_product_artifacts(
                        ROOT,
                        checkpoint,
                    )
                )

    def test_fp014_review_chain_rejects_same_actor(self) -> None:
        original = graph._load_exact_json

        def tampered(root: Path, relative: object) -> dict | None:
            document = original(root, relative)
            if document is None:
                return None
            result = copy.deepcopy(document)
            if relative == graph.FP014_COMPLETION_PATH:
                result["reviewer"]["id"] = result["executor"]["id"]
            return result

        with mock.patch.object(graph, "_load_exact_json", side_effect=tampered):
            self.assertIsNone(
                graph._fp014_successor_product_artifacts(
                    ROOT,
                    self.checkpoint,
                )
            )

    def test_fp014_review_subject_scope_tamper_is_rejected(self) -> None:
        original = graph._load_exact_json

        def tampered(root: Path, relative: object) -> dict | None:
            document = original(root, relative)
            if document is None:
                return None
            result = copy.deepcopy(document)
            if relative == graph.FP014_REVIEW_SUBJECT_PATH:
                result["implementation_scope"]["exact_path_count"] = 10
            return result

        with mock.patch.object(graph, "_load_exact_json", side_effect=tampered):
            self.assertIsNone(
                graph._fp014_successor_product_artifacts(
                    ROOT,
                    self.checkpoint,
                )
            )

    def test_fp014_pinned_head_lookup_fails_closed(self) -> None:
        with mock.patch.object(
            graph,
            "_run_git_bytes",
            side_effect=subprocess.CalledProcessError(1, ["git"]),
        ):
            self.assertIsNone(
                graph._fp014_successor_product_artifacts(
                    ROOT,
                    self.checkpoint,
                )
            )


class WalkSafeGoalGraphV24CompletedSuccessorLineageTest(unittest.TestCase):
    def test_fp047_completed_lineage_requires_strict_completion(self) -> None:
        import copy
        import json
        from unittest import mock

        checkpoint = json.loads(
            (
                ROOT
                / "docs/control/walksafe-project-continuation-checkpoint.json"
            ).read_text(encoding="utf-8")
        )
        npc = graph._npc_single_admin_recovery_live_compatibility_artifacts(
            ROOT,
            checkpoint,
        )
        self.assertIsInstance(npc, dict)
        completed = copy.deepcopy(checkpoint)
        state = completed["goal_execution"]
        state["status_by_goal"][graph.FP047_GOAL_ID] = "COMPLETE_AT_TARGET"
        state["focus_goal_id"] = graph.FP047_PARENT_GOAL_ID

        with mock.patch.object(
            graph,
            "_fp047_completion_transition_matches",
            return_value=True,
        ):
            artifacts = graph._fp014_successor_product_artifacts(
                ROOT,
                completed,
                require_live_successors=False,
                successor_artifacts=npc,
            )
        self.assertIsInstance(artifacts, dict)

        with mock.patch.object(
            graph,
            "_fp047_completion_transition_matches",
            return_value=False,
        ):
            self.assertIsNone(
                graph._fp014_successor_product_artifacts(
                    ROOT,
                    completed,
                    require_live_successors=False,
                    successor_artifacts=npc,
                )
            )

        for invalid_status in ("BLOCKED", "PLANNED"):
            invalid = copy.deepcopy(checkpoint)
            invalid_state = invalid["goal_execution"]
            invalid_state["status_by_goal"][graph.FP047_GOAL_ID] = invalid_status
            invalid_state["focus_goal_id"] = graph.FP047_GOAL_ID
            with self.subTest(status=invalid_status), mock.patch.object(
                graph,
                "_fp047_completion_transition_matches",
                return_value=True,
            ):
                self.assertIsNone(
                    graph._fp014_successor_product_artifacts(
                        ROOT,
                        invalid,
                        require_live_successors=False,
                        successor_artifacts=npc,
                    )
                )

        later_focus = copy.deepcopy(completed)
        later_focus["goal_execution"]["focus_goal_id"] = "WS-GOAL-EPIC-12"
        with mock.patch.object(
            graph,
            "_fp047_completion_transition_matches",
            return_value=True,
        ):
            self.assertIsInstance(
                graph._fp014_successor_product_artifacts(
                    ROOT,
                    later_focus,
                    require_live_successors=False,
                    successor_artifacts=npc,
                ),
                dict,
            )


class WalkSafeFp022Seq66Seq67SuccessorTest(unittest.TestCase):
    @staticmethod
    def _projected_checkpoint() -> dict:
        from scripts import apply_walksafe_fp022_goal_seq66_67_20260813 as fp022

        checkpoint = load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        raw = (ROOT / graph.CHECKPOINT_RELATIVE).read_bytes()
        if hashlib.sha256(raw).hexdigest() == fp022.SOURCE_SHA256:
            digests = fp022.aggregate._snapshot_digests(ROOT, checkpoint)
            checkpoint, _ = fp022.project(ROOT, checkpoint, digests)
        return checkpoint

    def test_exact_fp022_materialized_ready_successor_is_accepted(self) -> None:
        checkpoint = self._projected_checkpoint()

        self.assertTrue(
            graph._fp022_seq66_67_successor_matches(ROOT, checkpoint)
        )

    def test_fp022_successor_replays_the_frozen_seq66_67_review(self) -> None:
        from scripts import (
            build_walksafe_fp022_seq66_67_review_20260814 as fp022_review,
        )

        with mock.patch.object(
            fp022_review,
            "transition_review_binding",
            side_effect=AssertionError("live predecessor review was replayed"),
        ):
            binding = graph._fp022_transition_review_binding(ROOT)
        checkpoint = load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        self.assertEqual(
            binding,
            checkpoint["goal_execution"]["transition_history"][65][
                "transition_control_review_binding"
            ],
        )

    def test_fp022_ready_event_or_review_binding_tamper_fails_closed(self) -> None:
        checkpoint = self._projected_checkpoint()
        tampered = copy.deepcopy(checkpoint)
        tampered["goal_execution"]["transition_history"][66][
            "readiness_basis"
        ]["dependency_completion_events"][0]["event_sha256"] = "0" * 64
        self.assertFalse(
            graph._fp022_seq66_67_successor_matches(ROOT, tampered)
        )

        aggregate_path = next(
            iter(
                graph.WORKSTREAM_AGGREGATE_R003_TRANSITION_REVIEW_BINDING.values()
            )
        )["path"]
        original = graph.continuation.sha256_file

        def drift(path: Path) -> str:
            if path == ROOT / aggregate_path:
                return "0" * 64
            return original(path)

        with mock.patch.object(
            graph.continuation,
            "sha256_file",
            side_effect=drift,
        ):
            self.assertFalse(
                graph._fp022_seq66_67_successor_matches(ROOT, checkpoint)
            )

    @staticmethod
    def _with_completion_suffix(checkpoint: dict) -> dict:
        candidate = copy.deepcopy(checkpoint)
        history = candidate["goal_execution"]["transition_history"]
        update = {
            "sequence": 70,
            "event_id": graph.continuation.FP022_COMPLETION_UPDATE_EVENT_ID,
            "event_type": "CANONICAL_BINDINGS_UPDATED",
            "previous_event_sha256": history[-1]["event_sha256"],
            "status_changes": {},
        }
        update["event_sha256"] = graph.continuation.event_sha256(update)
        completion = {
            "sequence": 71,
            "event_id": graph.continuation.FP022_COMPLETION_EVENT_ID,
            "event_type": "GOAL_COMPLETED",
            "subject_goal_id": graph.continuation.FP022_GOAL_ID,
            "from_status": "IN_PROGRESS",
            "to_status": "COMPLETE_AT_TARGET",
            "status_changes": {
                graph.continuation.FP022_GOAL_ID: "COMPLETE_AT_TARGET"
            },
            "previous_event_sha256": update["event_sha256"],
        }
        completion["event_sha256"] = graph.continuation.event_sha256(
            completion
        )
        history.extend((update, completion))
        return candidate

    def test_completion_suffix_replays_historical_fp022_and_r027_authority(
        self,
    ) -> None:
        checkpoint = self._with_completion_suffix(
            self._projected_checkpoint()
        )
        self.assertTrue(
            graph._fp022_seq66_67_successor_matches(ROOT, checkpoint)
        )
        self.assertTrue(
            graph._npc_single_admin_recovery_r027_backlog_projection_matches(
                ROOT,
                checkpoint,
                require_operational_pointer=False,
            )
        )

        tampered = copy.deepcopy(checkpoint)
        tampered["goal_execution"]["transition_history"][60][
            "canonical_binding_snapshot_after"
        ]["IMPLEMENTATION_BACKLOG"]["file_sha256"] = "0" * 64
        self.assertFalse(
            graph._npc_single_admin_recovery_r027_backlog_projection_matches(
                ROOT,
                tampered,
                require_operational_pointer=False,
            )
        )

    def test_completion_suffix_rewinds_final_runtime_to_seq67_authority(
        self,
    ) -> None:
        checkpoint = self._with_completion_suffix(
            self._projected_checkpoint()
        )
        state = checkpoint["goal_execution"]
        state["status_by_goal"][graph.continuation.FP022_GOAL_ID] = (
            "COMPLETE_AT_TARGET"
        )
        state.update(
            {
                "focus_goal_id": "WS-GOAL-EPIC-04",
                "focus_goal_path": "final-focus",
                "focus_work_item_id": "",
                "focus_source": "WORKSTREAM_GRAPH",
                "ready_frontier_goal_ids": [
                    "WS-GOAL-EPIC-04",
                    "WS-GOAL-EPIC-12",
                ],
            }
        )
        checkpoint["current_work"]["status"] = "READY"
        checkpoint["current_work"]["work_item_id"] = ""
        checkpoint["current_work"]["current_focus"] = "final focus"
        checkpoint["session_handoff"]["current_epic"] = "final handoff"
        self.assertTrue(
            graph._fp022_seq66_67_successor_matches(ROOT, checkpoint)
        )

    def test_completion_product_manifest_yields_exact_nine_successors(
        self,
    ) -> None:
        checkpoint = self._with_completion_suffix(
            self._projected_checkpoint()
        )
        with (
            mock.patch.object(
                graph.continuation,
                "validate_fp022_completion_seq70_71",
                return_value=[],
            ),
            mock.patch(
                "scripts.build_walksafe_fp022_completion_seq70_71_review_20260814."
                "validate_post_review",
                return_value=mock.Mock(),
            ),
        ):
            transitions = (
                graph._fp022_completion_start_to_final_transitions(
                    ROOT, checkpoint
                )
            )
        self.assertIsInstance(transitions, dict)
        self.assertEqual(len(transitions), 9)
        self.assertEqual(
            transitions[
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/"
                "walksafe/MainActivity.kt"
            ],
            (
                "fd4c89497f99f215a0ef7f057aaf9968698156e2c941160399b573c06645ea55",
                "7cf49ad23b268ebd52809055879b734d81e7cf7215c44c94f9196b20d5b4e865",
            ),
        )

        tampered = copy.deepcopy(checkpoint)
        tampered["goal_execution"]["transition_history"][68][
            "implementation_start_gate_binding"
        ]["file_sha256"] = "0" * 64
        with (
            mock.patch.object(
                graph.continuation,
                "validate_fp022_completion_seq70_71",
                return_value=[],
            ),
            mock.patch(
                "scripts.build_walksafe_fp022_completion_seq70_71_review_20260814."
                "validate_post_review",
                return_value=mock.Mock(),
            ),
        ):
            self.assertIsNone(
                graph._fp022_completion_start_to_final_transitions(
                    ROOT, tampered
                )
            )


class WalkSafeNpcSingleAdminRecoverySuccessorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.checkpoint = load_json(
            ROOT / "docs/control/walksafe-project-continuation-checkpoint.json"
        )

    def test_seq61_62_package_uses_its_historical_snapshot_after_fp022_update(
        self,
    ) -> None:
        checkpoint = self._npc_checkpoint_at_sequence(71)
        backlog = next(
            row
            for row in checkpoint["canonical_bindings"]
            if row["role"] == "IMPLEMENTATION_BACKLOG"
        )
        backlog.update(
            {
                "document_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260814-028",
                "path": (
                    "docs/control/audits/"
                    "walksafe-implementation-remediation-backlog-20260814-r028.json"
                ),
                "file_sha256": "0" * 64,
            }
        )
        with mock.patch.object(
            graph,
            "_npc_single_admin_recovery_completion_managed_closure_matches",
            return_value=True,
        ):
            self.assertIsNotNone(
                graph._npc_single_admin_recovery_completion_package(
                    ROOT, checkpoint
                )
            )

    def test_completion_managed_closure_rejects_self_consistent_history_omission(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            def write(relative: Path, raw: bytes) -> None:
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(raw)

            history_path = Path("fixture/immutable-history.json")
            history_raw = b"immutable-history\n"
            write(history_path, history_raw)
            for relative in graph.npc_review.R002_REVIEW_HISTORY_PATHS:
                write(relative, (ROOT / relative).read_bytes())
            control_path = Path("scripts/current-control.py")
            control_raw = b"current-control\n"
            write(control_path, control_raw)
            superseded_path = Path("fixture/superseded-assignment.json")
            superseded_raw = b"superseded-assignment\n"
            write(superseded_path, superseded_raw)
            assignment_raw = b"assignment\n"
            result_raw = b"result\n"
            post_review = {
                graph.npc_review.INDEPENDENT_REVIEW_REL: "independent\n",
                graph.npc_review.COMPLETION_RECEIPT_REL: "completion\n",
            }
            write(graph.npc_review.REVIEW_ASSIGNMENT_REL, assignment_raw)
            write(graph.npc_review.REVIEW_RESULT_REL, result_raw)
            for relative, text in post_review.items():
                write(relative, text.encode())
            followup_assignment_raw = b"followup-assignment\n"
            followup_result_raw = b"followup-result\n"
            followup_independent_raw = b"followup-independent\n"
            predecessor_raw_by_path = {
                path: f"approved-r008-{index}\n".encode()
                for index, path in enumerate(
                    graph.npc_r011_review.PREDECESSOR_PINS,
                    start=1,
                )
            }
            for relative, raw in predecessor_raw_by_path.items():
                write(relative, raw)
            write(
                graph.npc_r011_review.REVIEW_ASSIGNMENT_REL,
                followup_assignment_raw,
            )
            write(
                graph.npc_r011_review.REVIEW_RESULT_REL,
                followup_result_raw,
            )
            write(
                graph.npc_r011_review.INDEPENDENT_REVIEW_REL,
                followup_independent_raw,
            )
            aggregate_assignment_raw = json_bytes(
                {
                    "review_scope": {
                        "reviewed_control_code_cohort": [
                            {
                                "path": control_path.as_posix(),
                                "sha256": hashlib.sha256(control_raw).hexdigest(),
                                "byte_length": len(control_raw),
                            }
                        ]
                    }
                }
            )
            aggregate_result_raw = b"aggregate-result\n"
            aggregate_independent_raw = b"aggregate-independent\n"
            write(
                graph.workstream_aggregate_review.ASSIGNMENT_REL,
                aggregate_assignment_raw,
            )
            write(
                graph.workstream_aggregate_review.RESULT_REL,
                aggregate_result_raw,
            )
            write(
                graph.workstream_aggregate_review.INDEPENDENT_REL,
                aggregate_independent_raw,
            )

            context = mock.Mock()
            context.control_code_cohort = (
                {
                    "path": control_path.as_posix(),
                    "sha256": hashlib.sha256(b"r003-control\n").hexdigest(),
                    "byte_length": len(b"r003-control\n"),
                },
            )
            followup_context = mock.Mock()
            followup_context.control_code_cohort = (
                {
                    "path": control_path.as_posix(),
                    "sha256": hashlib.sha256(control_raw).hexdigest(),
                    "byte_length": len(control_raw),
                },
            )
            followup_context.superseded_assignment_bindings = (
                {
                    "path": superseded_path.as_posix(),
                    "sha256": hashlib.sha256(superseded_raw).hexdigest(),
                    "byte_length": len(superseded_raw),
                    "status": "SUPERSEDED_BEFORE_REVIEW",
                    "reason": "fixture",
                },
            )
            followup_context.predecessor_bindings = tuple(
                {
                    "path": path.as_posix(),
                    "sha256": hashlib.sha256(raw).hexdigest(),
                    "byte_length": len(raw),
                }
                for path, raw in predecessor_raw_by_path.items()
            )
            mandatory = sorted(
                {
                    history_path.as_posix(),
                    control_path.as_posix(),
                    superseded_path.as_posix(),
                    *(path.as_posix() for path in graph.npc_review.R002_REVIEW_HISTORY_PATHS),
                    graph.npc_review.REVIEW_ASSIGNMENT_REL.as_posix(),
                    graph.npc_review.REVIEW_RESULT_REL.as_posix(),
                    graph.npc_review.INDEPENDENT_REVIEW_REL.as_posix(),
                    graph.npc_review.COMPLETION_RECEIPT_REL.as_posix(),
                    *(path.as_posix() for path in predecessor_raw_by_path),
                    graph.npc_r011_review.REVIEW_ASSIGNMENT_REL.as_posix(),
                    graph.npc_r011_review.REVIEW_RESULT_REL.as_posix(),
                    graph.npc_r011_review.INDEPENDENT_REVIEW_REL.as_posix(),
                    graph.workstream_aggregate_review.ASSIGNMENT_REL.as_posix(),
                    graph.workstream_aggregate_review.RESULT_REL.as_posix(),
                    graph.workstream_aggregate_review.INDEPENDENT_REL.as_posix(),
                }
            )

            def checkpoint(paths: list[str]) -> dict:
                path_hash, content_hash = (
                    graph.continuation.working_snapshot_hashes(root, paths)
                )
                return {
                    "working_tree_snapshot": {
                        "managed_changed_paths": paths,
                        "managed_changed_path_count": len(paths),
                        "path_set_sha256": path_hash,
                        "content_set_sha256": content_hash,
                    },
                    "session_handoff": {
                        "changed_files": copy.deepcopy(paths),
                        "source_commit_or_snapshot": {
                            "file_count": len(paths),
                            "path_set_sha256": path_hash,
                            "content_set_sha256": content_hash,
                        },
                    },
                }

            patches = (
                mock.patch.object(
                    graph.npc_r004_review,
                    "prepare_frozen_r003_context",
                    return_value=context,
                ),
                mock.patch.object(
                    graph.workstream_aggregate_review,
                    "prepare_frozen_r011_context",
                    return_value=followup_context,
                ),
                mock.patch.object(
                    graph,
                    "_frozen_workstream_aggregate_r003_review_binding",
                    return_value={},
                ),
                mock.patch.object(
                    graph.npc_r011_review,
                    "load_review_inputs",
                    return_value=(
                        {},
                        followup_assignment_raw,
                        {},
                        followup_result_raw,
                    ),
                ),
                mock.patch.object(
                    graph.npc_r011_review,
                    "build_independent_review",
                    return_value=followup_independent_raw.decode(),
                ),
                mock.patch.object(
                    graph.npc_review,
                    "load_review_inputs",
                    return_value=({}, assignment_raw, {}, result_raw),
                ),
                mock.patch.object(
                    graph.npc_review,
                    "build_post_review_outputs",
                    return_value=post_review,
                ),
                mock.patch.object(
                    graph.npc_review,
                    "load_immutable_predecessor_history_sha256_by_path",
                    return_value={
                        history_path: hashlib.sha256(history_raw).hexdigest()
                    },
                ),
            )
            with (
                patches[0],
                patches[1],
                patches[2],
                patches[3],
                patches[4],
                patches[5],
                patches[6],
                patches[7],
            ):
                self.assertTrue(
                    graph._npc_single_admin_recovery_completion_managed_closure_matches(
                        root,
                        checkpoint(mandatory),
                    )
                )
                omitted = [
                    path for path in mandatory if path != history_path.as_posix()
                ]
                self.assertFalse(
                    graph._npc_single_admin_recovery_completion_managed_closure_matches(
                        root,
                        checkpoint(omitted),
                    )
                )

    def test_seq68_managed_closure_loads_the_start_review_authority(self) -> None:
        checkpoint = copy.deepcopy(self.checkpoint)
        history = checkpoint["goal_execution"]["transition_history"][:67]
        checkpoint["goal_execution"]["transition_history"] = history
        history.append(
            {
                "event_id": (
                    "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-"
                    "REANCHORED-FP022-20260814-001"
                )
            }
        )
        r003_context = mock.Mock()
        followup_context = mock.Mock()
        start_context = mock.Mock(control_code_cohort=())
        with (
            mock.patch.object(
                graph.npc_r004_review,
                "prepare_frozen_r003_context",
                return_value=r003_context,
            ),
            mock.patch.object(
                graph.workstream_aggregate_review,
                "prepare_frozen_r011_context",
                return_value=followup_context,
            ),
            mock.patch.object(
                graph.npc_r011_review,
                "load_review_inputs",
                return_value=({}, b"assignment", {}, b"result"),
            ),
            mock.patch.object(
                graph.npc_r011_review,
                "build_independent_review",
                return_value="followup",
            ),
            mock.patch.object(
                graph,
                "_npc_exact_live_bytes",
                return_value=b"followup",
            ),
            mock.patch.object(
                graph,
                "_frozen_workstream_aggregate_r003_review_binding",
                return_value={},
            ),
            mock.patch.object(
                graph,
                "_load_exact_json",
                return_value={
                    "review_scope": {"reviewed_control_code_cohort": []}
                },
            ),
            mock.patch(
                "scripts.build_walksafe_fp022_seq66_67_review_20260814."
                "prepare_frozen_review_context",
                return_value=mock.Mock(control_code_cohort=()),
            ),
            mock.patch(
                "scripts.build_walksafe_fp022_seq68_69_review_20260814."
                "validate_post_review",
                return_value=start_context,
            ) as validate_post_review,
            mock.patch.object(
                graph.npc_review,
                "load_review_inputs",
                side_effect=RuntimeError("stop after start review"),
            ),
        ):
            self.assertFalse(
                graph._npc_single_admin_recovery_completion_managed_closure_matches(
                    ROOT,
                    checkpoint,
                )
            )
        validate_post_review.assert_called_once_with(ROOT)

    def test_seq71_managed_closure_uses_frozen_r011_and_pre_review_delta(
        self,
    ) -> None:
        source = copy.deepcopy(self.checkpoint)
        source["goal_execution"]["transition_history"] = source[
            "goal_execution"
        ]["transition_history"][:69]
        checkpoint = (
            WalkSafeFp022Seq66Seq67SuccessorTest._with_completion_suffix(
                source
            )
        )
        r003_context = mock.Mock()
        followup_context = mock.Mock()
        frozen_start = mock.Mock(control_code_cohort=())
        completion_context = mock.Mock(
            control_code_cohort=(),
            completion_evidence_bindings=(),
            superseded_assignment_bindings=(),
        )
        completion_successor_context = mock.Mock(current=completion_context)
        with (
            mock.patch.object(
                graph.npc_r004_review,
                "prepare_frozen_r003_context",
                return_value=r003_context,
            ),
            mock.patch.object(
                graph.workstream_aggregate_review,
                "prepare_frozen_r011_context",
                return_value=followup_context,
            ),
            mock.patch.object(
                graph.npc_r011_review,
                "load_review_inputs",
                return_value=({}, b"assignment", {}, b"result"),
            ),
            mock.patch.object(
                graph.npc_r011_review,
                "build_independent_review",
                return_value="followup",
            ),
            mock.patch.object(
                graph,
                "_npc_exact_live_bytes",
                return_value=b"followup",
            ),
            mock.patch.object(
                graph,
                "_frozen_workstream_aggregate_r003_review_binding",
                return_value={},
            ),
            mock.patch.object(
                graph,
                "_load_exact_json",
                return_value={
                    "review_scope": {"reviewed_control_code_cohort": []}
                },
            ),
            mock.patch(
                "scripts.build_walksafe_fp022_seq66_67_review_20260814."
                "prepare_frozen_review_context",
                return_value=mock.Mock(control_code_cohort=()),
            ),
            mock.patch(
                "scripts.build_walksafe_fp022_seq68_69_review_20260814."
                "validate_post_review",
                side_effect=AssertionError("live R031 review was replayed"),
            ),
            mock.patch(
                "scripts.build_walksafe_fp022_completion_seq70_71_review_20260814."
                "prepare_frozen_start_review_context",
                return_value=frozen_start,
            ) as frozen_loader,
            mock.patch.object(
                graph,
                "_fp046_r002_optional_frozen_r011_authority",
                return_value={
                    "context": completion_successor_context,
                    "managed_sources_by_round": {
                        "R002": (),
                        "R003": (),
                        "R004": (),
                    },
                },
            ) as frozen_r011_loader,
            mock.patch.object(
                graph,
                "_fp046_r002_pre_review_modified_control_cohort",
                return_value=(),
            ) as pre_review_loader,
            mock.patch(
                "scripts.build_walksafe_fp022_completion_seq70_71_review_20260814."
                "validated_control_successor_r011_context",
                return_value=completion_successor_context,
            ) as completion_loader,
            mock.patch(
                "scripts.build_walksafe_fp022_completion_seq70_71_review_20260814."
                "validated_control_successor_managed_closure_sources_by_round",
                return_value={"R002": (), "R003": (), "R004": ()},
            ) as closure_loader,
            mock.patch.object(
                graph.npc_review,
                "load_review_inputs",
                side_effect=RuntimeError("stop after completion review"),
            ),
        ):
            self.assertFalse(
                graph._npc_single_admin_recovery_completion_managed_closure_matches(
                    ROOT,
                    checkpoint,
                )
            )
        frozen_loader.assert_called_once_with(ROOT)
        frozen_r011_loader.assert_called_once_with(ROOT)
        pre_review_loader.assert_called_once()
        completion_loader.assert_not_called()
        closure_loader.assert_not_called()

    def test_seq71_managed_closure_rejects_frozen_r011_integrity_failure(
        self,
    ) -> None:
        checkpoint = load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        for failure in (
            ValueError("malformed frozen R011"),
            OSError("unreadable frozen R011"),
        ):
            with self.subTest(failure=type(failure).__name__), mock.patch.object(
                graph,
                "_fp046_r002_frozen_r011_authority",
                side_effect=failure,
            ), mock.patch.object(
                completion_review,
                "validated_control_successor_r011_context",
            ) as legacy_loader:
                self.assertFalse(
                    graph._npc_single_admin_recovery_completion_managed_closure_matches(
                        ROOT,
                        checkpoint,
                    )
                )
                legacy_loader.assert_not_called()

    def _r027_projection_fixture(self) -> tuple[Path, dict]:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        epic = {
            **copy.deepcopy(
                graph.NPC_SINGLE_ADMIN_RECOVERY_EPIC04_PROJECTION
            ),
            "dependencies": ["EPIC-02"],
        }
        backlog = {
            "metadata": {
                "backlog_id": (
                    graph.NPC_SINGLE_ADMIN_RECOVERY_R027_BACKLOG_DOCUMENT_ID
                ),
                "version": "0.27.0",
            },
            "epics": [epic],
            "next_single_action": copy.deepcopy(
                graph.NPC_SINGLE_ADMIN_RECOVERY_NEXT_ACTION
            ),
            "npc_single_admin_recovery_evidence_correction": {
                "kind": "ADDITIVE_INTERNAL_EVIDENCE_CORRECTION"
            },
        }
        backlog["backlog_content_sha256"] = (
            graph.continuation.canonical_json_sha256(backlog)
        )
        relative = Path(graph.NPC_SINGLE_ADMIN_RECOVERY_R027_BACKLOG_PATH)
        target = root / relative
        target.parent.mkdir(parents=True)
        target.write_text(
            json.dumps(backlog, ensure_ascii=False, sort_keys=True),
            encoding="utf-8",
        )
        action = graph.NPC_SINGLE_ADMIN_RECOVERY_NEXT_ACTION
        projection = graph.NPC_SINGLE_ADMIN_RECOVERY_EPIC04_PROJECTION
        checkpoint = {
            "canonical_bindings": [
                {
                    "role": "IMPLEMENTATION_BACKLOG",
                    "document_id": (
                        graph.NPC_SINGLE_ADMIN_RECOVERY_R027_BACKLOG_DOCUMENT_ID
                    ),
                    "path": relative.as_posix(),
                    "file_sha256": sha256_file(target),
                    "identity_json_path": "metadata.backlog_id",
                    "mutable": False,
                }
            ],
            "current_work": {
                "current_focus": (
                    "FP-022/GAP-031 PLANNED_NEXT; EPIC-04 canonical Backlog "
                    "aggregate"
                ),
                "deferred_release_gate_ids": copy.deepcopy(
                    projection["deferred_release_gate_ids"]
                ),
                "epic_id": "WS-GOAL-EPIC-04",
                "gap_ids": copy.deepcopy(projection["gap_ids"]),
                "gap_ids_semantics": (
                    "BACKLOG_EPIC_AGGREGATE_NOT_FOCUS_GOAL_COMPLETION_SCOPE"
                ),
                "last_completed_work_summary": (
                    "NPC-SINGLE-ADMIN-RECOVERY/GAP-008 repository-internal "
                    "implementation, regression, review and successor evidence "
                    "completed"
                ),
                "next_action": action["action"],
                "policy_change_required": False,
                "release_completion_claimed": False,
                "scope_kind": "BACKLOG_EPIC_AGGREGATE",
                "source_policy_ids": copy.deepcopy(
                    projection["source_policy_ids"]
                ),
                "source_policy_ids_semantics": (
                    "BACKLOG_EPIC_AGGREGATE_NOT_FOCUS_GOAL_COMPLETION_SCOPE"
                ),
                "status": "PLANNED",
                "status_scope": (
                    "IMPLEMENTATION_BACKLOG_EPIC_STATUS_NOT_GOAL_STATUS"
                ),
                "target_completion_level": "IMPLEMENTATION_READY",
                "title": projection["title"],
                "work_item_id": action["work_item_id"],
                "work_item_id_semantics": "NEXT_ACTION_POINTER_ONLY",
            },
            "session_handoff": {
                "current_epic": "EPIC-04 / FP-022/GAP-031 PLANNED_NEXT",
                "next_single_action": action["action"],
                "last_updated_by_work_item": graph.npc_recovery.WORK_ITEM_ID,
                "last_verification_status": (
                    "PASS_INTERNAL_ONLY_FORMAL_EXTERNAL_DEVICE_RELEASE_NOT_RUN"
                ),
            },
        }
        return root, checkpoint

    def _npc_checkpoint_at_sequence(self, sequence: int) -> dict:
        from scripts import (
            apply_walksafe_fp046_goal_completed_seq54_55_20260810 as fp046,
        )
        if sequence not in {60, 62, 71}:
            raise ValueError("NPC fixture sequence must be 60, 62, or 71")
        checkpoint = copy.deepcopy(self.checkpoint)
        state = checkpoint["goal_execution"]
        history = state["transition_history"]
        current_inventory = copy.deepcopy(state["dynamic_goal_inventory"])
        if len(history) < sequence:
            raise ValueError("NPC fixture source history is too short")
        for event in reversed(history[sequence:]):
            for goal_id in event.get("status_changes", {}):
                if event["from_status"] is None:
                    state["status_by_goal"].pop(goal_id, None)
                else:
                    state["status_by_goal"][goal_id] = event["from_status"]
            if event.get("event_type") == "GOAL_COMPLETED":
                state["completion_evidence_by_goal"].pop(
                    event.get("subject_goal_id"), None
                )
        del history[sequence:]
        for state_key, event_key, default in (
            (
                "completion_evidence_by_goal",
                "completion_evidence_by_goal_after",
                {},
            ),
            (
                "archived_completion_evidence_by_goal",
                "archived_completion_evidence_by_goal_after",
                {},
            ),
            ("dynamic_goal_inventory", "dynamic_goal_inventory_after", {}),
            (
                "materialized_child_goal_ids_by_parent",
                "materialized_child_goal_ids_by_parent_after",
                {},
            ),
        ):
            snapshot = next(
                (
                    event[event_key]
                    for event in reversed(history)
                    if event_key in event
                ),
                default,
            )
            state[state_key] = copy.deepcopy(snapshot)
        future_goal_paths = {
            row["path"]
            for goal_id, row in current_inventory.items()
            if goal_id not in state["dynamic_goal_inventory"]
        }
        for relative in future_goal_paths:
            if relative in state["goal_document_paths"]:
                state["goal_document_paths"].remove(relative)
            if relative in state["managed_goal_paths"]:
                state["managed_goal_paths"].remove(relative)
        if future_goal_paths:
            state["goal_document_count"] = len(state["goal_document_paths"])
            state["managed_goal_path_count"] = len(state["managed_goal_paths"])
            state["path_set_sha256"], state["content_set_sha256"] = (
                graph.continuation.package_hashes(
                    ROOT, state["managed_goal_paths"]
                )
            )
        checkpoint["current_work"]["status"] = "PLANNED"
        checkpoint["current_work"]["current_focus"] = (
            "FP-022/GAP-031 PLANNED_NEXT; EPIC-04 canonical Backlog aggregate"
        )
        checkpoint["session_handoff"]["current_epic"] = (
            "EPIC-04 / FP-022/GAP-031 PLANNED_NEXT"
        )
        checkpoint["session_handoff"]["last_updated_by_work_item"] = (
            graph.npc_recovery.WORK_ITEM_ID
        )
        tail = history[-1]
        snapshot = tail.get("canonical_binding_snapshot_after")
        if isinstance(snapshot, dict):
            current_by_role = {
                row["role"]: row for row in checkpoint["canonical_bindings"]
            }
            checkpoint["canonical_bindings"] = [
                {**current_by_role.get(role, {}), **binding}
                for role, binding in snapshot.items()
            ]
        state.update(copy.deepcopy(tail["runtime_after"]))
        if sequence == 60:
            checkpoint["canonical_bindings"] = [
                binding
                for binding in checkpoint["canonical_bindings"]
                if binding.get("role")
                != graph.NPC_SINGLE_ADMIN_RECOVERY_COMPLETION_ROLE
            ]
        if sequence == 62:
            queue, boundary = fp046.derive_runtime(
                ROOT,
                checkpoint,
                state["ready_frontier_goal_ids"],
            )
            self.assertEqual(
                graph.continuation.canonical_json_sha256(queue),
                tail["runtime_after"]["artifact_work_queue_sha256"],
            )
            self.assertEqual(
                graph.continuation.canonical_json_sha256(boundary),
                tail["runtime_after"]["completion_boundary_sha256"],
            )
            state["artifact_work_queue"] = queue
            state["completion_boundary"] = boundary
        state["transition_history_anchor_sha256"] = tail["event_sha256"]
        state["validation_cutoff_at"] = tail["occurred_at"]
        return checkpoint

    @staticmethod
    def _reseal_synthetic_seq62(checkpoint: dict) -> None:
        state = checkpoint["goal_execution"]
        update, completion = state["transition_history"][-2:]
        update.pop("event_sha256", None)
        update["event_sha256"] = graph.continuation.event_sha256(update)
        completion["previous_event_sha256"] = update["event_sha256"]
        completion["canonical_update_event_sha256"] = update["event_sha256"]
        completion.pop("event_sha256", None)
        completion["event_sha256"] = graph.continuation.event_sha256(
            completion
        )
        state["transition_history_anchor_sha256"] = completion["event_sha256"]

    def test_r027_planned_projection_requires_exact_backlog_and_handoff(
        self,
    ) -> None:
        root, checkpoint = self._r027_projection_fixture()
        self.assertTrue(
            graph._npc_single_admin_recovery_r027_backlog_projection_matches(
                root,
                checkpoint,
            )
        )

        tampers = (
            (
                "epic",
                lambda candidate: candidate["current_work"].__setitem__(
                    "epic_id", "WS-GOAL-EPIC-02"
                ),
            ),
            (
                "status",
                lambda candidate: candidate["current_work"].__setitem__(
                    "status", "READY"
                ),
            ),
            (
                "pointer",
                lambda candidate: candidate["current_work"].__setitem__(
                    "work_item_id", "WS-GOAL-EPIC-04-FP-023-R001"
                ),
            ),
            (
                "action",
                lambda candidate: candidate["current_work"].__setitem__(
                    "next_action", "unbound action"
                ),
            ),
            (
                "gates",
                lambda candidate: candidate["current_work"].__setitem__(
                    "deferred_release_gate_ids", []
                ),
            ),
            (
                "policies",
                lambda candidate: candidate["current_work"].__setitem__(
                    "source_policy_ids", ["FP-022"]
                ),
            ),
            (
                "gaps",
                lambda candidate: candidate["current_work"].__setitem__(
                    "gap_ids", ["GAP-031"]
                ),
            ),
            (
                "handoff epic",
                lambda candidate: candidate["session_handoff"].__setitem__(
                    "current_epic", "EPIC-02"
                ),
            ),
            (
                "handoff action",
                lambda candidate: candidate["session_handoff"].__setitem__(
                    "next_single_action", "unbound action"
                ),
            ),
            (
                "handoff updater",
                lambda candidate: candidate["session_handoff"].__setitem__(
                    "last_updated_by_work_item", "EPIC-04-FP022"
                ),
            ),
            (
                "handoff verification",
                lambda candidate: candidate["session_handoff"].__setitem__(
                    "last_verification_status", "PASS_RELEASE"
                ),
            ),
        )
        for label, mutate in tampers:
            with self.subTest(tamper=label):
                candidate = copy.deepcopy(checkpoint)
                mutate(candidate)
                self.assertFalse(
                    graph._npc_single_admin_recovery_r027_backlog_projection_matches(
                        root,
                        candidate,
                    )
                )

        relative = Path(graph.NPC_SINGLE_ADMIN_RECOVERY_R027_BACKLOG_PATH)
        backlog_path = root / relative
        backlog = json.loads(backlog_path.read_text(encoding="utf-8"))
        backlog["next_single_action"]["source_policy_id"] = "FP-023"
        backlog.pop("backlog_content_sha256")
        backlog["backlog_content_sha256"] = (
            graph.continuation.canonical_json_sha256(backlog)
        )
        backlog_path.write_text(
            json.dumps(backlog, ensure_ascii=False, sort_keys=True),
            encoding="utf-8",
        )
        checkpoint["canonical_bindings"][0]["file_sha256"] = sha256_file(
            backlog_path
        )
        self.assertFalse(
            graph._npc_single_admin_recovery_r027_backlog_projection_matches(
                root,
                checkpoint,
            )
        )

    def test_planned_suffix_without_exact_seq62_is_rejected(self) -> None:
        checkpoint = fp008_seq50_checkpoint()
        checkpoint["current_work"]["status"] = "PLANNED"
        self.assertFalse(
            graph._fp008_admin_review_completion_is_declared(ROOT, checkpoint)
        )

    def test_fp008_suffix_allows_planned_only_for_proven_npc_seq62(
        self,
    ) -> None:
        _root, projection = self._r027_projection_fixture()
        checkpoint = self._npc_checkpoint_at_sequence(62)
        checkpoint["current_work"] = copy.deepcopy(projection["current_work"])
        checkpoint["session_handoff"].update(
            copy.deepcopy(projection["session_handoff"])
        )

        with mock.patch.object(
            graph,
            "_npc_single_admin_recovery_completion_package",
            return_value={"evidence_schema": "V2_CORRECTION_ONLY"},
        ):
            self.assertTrue(
                graph._fp008_admin_review_completion_is_declared(
                    ROOT,
                    checkpoint,
                )
            )
        with mock.patch.object(
            graph,
            "_npc_single_admin_recovery_completion_package",
            return_value=None,
        ):
            self.assertFalse(
                graph._fp008_admin_review_completion_is_declared(
                    ROOT,
                    checkpoint,
                )
            )

    def test_seq61_62_subject_producer_evidence_and_impact_are_exact(
        self,
    ) -> None:
        checkpoint = self._npc_checkpoint_at_sequence(62)
        patches = (
            mock.patch.object(graph, "_sha256_binding_matches", return_value=True),
            mock.patch.object(
                graph,
                "_npc_single_admin_recovery_r027_backlog_projection_matches",
                return_value=True,
            ),
            mock.patch.object(
                graph,
                "_npc_single_admin_recovery_v2_completion_evidence",
                return_value={"evidence_schema": "V2_CORRECTION_ONLY"},
            ),
            mock.patch.object(
                graph,
                "_npc_single_admin_recovery_completion_managed_closure_matches",
                return_value=True,
            ),
        )
        with patches[0], patches[1], patches[2], patches[3]:
            self.assertIsNotNone(
                graph._npc_single_admin_recovery_completion_package(
                    ROOT,
                    checkpoint,
                )
            )

            def changed_subject(update: dict, _completion: dict) -> None:
                update["changed_subject_ids_by_role"][
                    "IMPLEMENTATION_GAP"
                ] = ["GAP-008"]

            def producer_subject(update: dict, _completion: dict) -> None:
                update["producer_output_subject_ids_by_role"][
                    "IMPLEMENTATION_GAP"
                ] = ["GAP-008"]

            def changed_roles(update: dict, _completion: dict) -> None:
                update["changed_binding_roles"] = ["IMPLEMENTATION_GAP"]

            def produced_roles(update: dict, _completion: dict) -> None:
                update["produced_binding_roles"] = ["IMPLEMENTATION_GAP"]

            def update_evidence(update: dict, _completion: dict) -> None:
                update["evidence_refs"] = ["IMPLEMENTATION_GAP"]

            def completion_evidence(_update: dict, completion: dict) -> None:
                completion["evidence_refs"] = []

            def impact_closure(update: dict, _completion: dict) -> None:
                update["impact_closure_goal_ids"] = []

            def impact_disposition(update: dict, _completion: dict) -> None:
                update["impact_disposition_by_goal"] = {}

            tampers = (
                ("changed subjects", changed_subject),
                ("producer subjects", producer_subject),
                ("changed roles", changed_roles),
                ("produced roles", produced_roles),
                ("update evidence", update_evidence),
                ("completion evidence", completion_evidence),
                ("impact closure", impact_closure),
                ("impact disposition", impact_disposition),
            )
            for label, mutate in tampers:
                with self.subTest(tamper=label):
                    candidate = copy.deepcopy(checkpoint)
                    update, completion = candidate["goal_execution"][
                        "transition_history"
                    ][-2:]
                    mutate(update, completion)
                    self._reseal_synthetic_seq62(candidate)
                    self.assertIsNone(
                        graph._npc_single_admin_recovery_completion_package(
                            ROOT,
                            candidate,
                        )
                    )

    def _run_v2_completion_rebuild(
        self,
        *,
        r001_decision: str = "REJECTED",
        tamper_observation: bool = False,
        tamper_r016: bool = False,
        legacy_consumer: str | None = None,
    ) -> dict | None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)

        def payload(label: str, **extra: object) -> str:
            return graph.npc_recovery.json_text({"label": label, **extra})

        def write(relative: Path, content: str | bytes) -> bytes:
            raw = content.encode("utf-8") if type(content) is str else content
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(raw)
            return raw

        observation_raw = write(
            graph.npc_recovery.V2_OBSERVATION_MANIFEST_REL,
            payload("v2-observation"),
        )
        result_outputs: dict[Path, str] = {
            lane.receipt_rel: payload(f"lane:{lane.lane_id}")
            for lane in graph.npc_recovery.LANES
        }
        result_outputs.update(
            {
                graph.npc_recovery.V2_IMPLEMENTATION_REL: payload(
                    "v2-implementation",
                    evidence_schema="V2_CORRECTION_ONLY",
                    final_content_manifest={"files": []},
                ),
                graph.npc_recovery.V2_VERIFICATION_REL: payload(
                    "v2-verification"
                ),
                graph.npc_recovery.V2_SUCCESSOR_REL: payload(
                    "v2-successor"
                ),
                graph.npc_recovery.V2_REVIEW_SUBJECT_REL: payload(
                    "v2-review-subject"
                ),
            }
        )
        result_raw = {
            relative: write(relative, content)
            for relative, content in result_outputs.items()
        }

        expected_consumers = tuple(graph.npc_recovery.CONSUMER_SPECS)
        consumer_bindings: list[dict[str, object]] = []
        consumer_raw: dict[Path, bytes] = {}
        for role, relative in expected_consumers:
            content = (
                payload(
                    "r001-review",
                    goal_id=graph.NPC_SINGLE_ADMIN_RECOVERY_GOAL_ID,
                    decision=r001_decision,
                )
                if relative == graph.npc_review.R001_REVIEW_RESULT_REL
                else payload(f"consumer:{role}")
            )
            raw = write(relative, content)
            consumer_raw[relative] = raw
            consumer_bindings.append(
                {
                    "role": role,
                    "path": relative.as_posix(),
                    "sha256": hashlib.sha256(raw).hexdigest(),
                    "byte_length": len(raw),
                }
            )

        if legacy_consumer == "R026":
            role, relative = "GAP_R026", graph.npc_recovery.GAP_R026_REL
            raw = write(relative, payload("legacy-r026"))
            consumer_bindings[0] = {
                "role": role,
                "path": relative.as_posix(),
                "sha256": hashlib.sha256(raw).hexdigest(),
                "byte_length": len(raw),
            }
        elif legacy_consumer == "R015":
            role, relative = graph.npc_recovery.R015_CONSUMERS[0]
            raw = write(relative, payload("legacy-r015"))
            consumer_bindings[8] = {
                "role": role,
                "path": relative.as_posix(),
                "sha256": hashlib.sha256(raw).hexdigest(),
                "byte_length": len(raw),
            }

        if tamper_r016:
            r016_path = graph.npc_recovery.R016_CONSUMERS[0][1]
            write(r016_path, payload("third-r016-digest"))
        if tamper_observation:
            write(
                graph.npc_recovery.V2_OBSERVATION_MANIFEST_REL,
                payload("third-observation-digest"),
            )

        r001_raw = consumer_raw[graph.npc_review.R001_REVIEW_RESULT_REL]

        assignment_raw = write(
            graph.npc_review.REVIEW_ASSIGNMENT_REL,
            payload("r002-assignment"),
        )
        review_result_raw = write(
            graph.npc_review.REVIEW_RESULT_REL,
            payload("r002-result"),
        )
        post_review_outputs = {
            graph.npc_review.INDEPENDENT_REVIEW_REL: payload(
                "r002-independent-review"
            ),
            graph.npc_review.COMPLETION_RECEIPT_REL: payload(
                "v2-completion"
            ),
        }
        for relative, content in post_review_outputs.items():
            write(relative, content)

        review_context = mock.Mock()
        review_context.result_raw = result_raw
        review_context.consumer_bindings = tuple(consumer_bindings)
        history = {
            Path(f"fixture/history-{index:02d}.json"): f"{index:064x}"
            for index in range(20)
        }
        review_context.evidence_manifest = (
            {
                "path": (
                    graph.npc_recovery.V2_OBSERVATION_MANIFEST_REL.as_posix()
                ),
                "sha256": hashlib.sha256(observation_raw).hexdigest(),
            },
            *(
                {"path": path.as_posix(), "sha256": digest}
                for path, digest in history.items()
            ),
        )
        review_context.predecessor_review = {
            "path": graph.npc_review.R001_REVIEW_RESULT_REL.as_posix(),
            "sha256": hashlib.sha256(r001_raw).hexdigest(),
            "decision": r001_decision,
        }
        review_context.prior_approved_review = {
            "round_id": graph.npc_review.R002_ROUND_ID,
            "decision": "APPROVED",
        }
        assignment: dict[str, object] = {}
        review_result: dict[str, object] = {}

        with mock.patch.object(
            graph.npc_r004_review,
            "prepare_frozen_r003_context",
            return_value=review_context,
        ), mock.patch.object(
            graph.npc_review,
            "load_review_inputs",
            return_value=(
                assignment,
                assignment_raw,
                review_result,
                review_result_raw,
            ),
        ), mock.patch.object(
            graph.npc_review,
            "build_post_review_outputs",
            return_value=post_review_outputs,
        ), mock.patch.object(
            graph.npc_review,
            "IMMUTABLE_PREDECESSOR_HISTORY_PATHS",
            tuple(history),
        ), mock.patch.object(
            graph.npc_review,
            "load_immutable_predecessor_history_sha256_by_path",
            return_value=history,
        ), mock.patch.object(
            graph.npc_review,
            "load_attestation",
            create=True,
        ) as legacy_attestation:
            rebuilt = graph._npc_single_admin_recovery_v2_completion_evidence(
                root
            )
            legacy_attestation.assert_not_called()
            return rebuilt

    def test_completion_credit_rebuilds_only_the_exact_v2_chain(self) -> None:
        implementation = self._run_v2_completion_rebuild()

        self.assertIsInstance(implementation, dict)
        self.assertEqual(implementation["label"], "v2-implementation")

    def test_completion_credit_rejects_rebuilt_r016_byte_drift(self) -> None:
        self.assertIsNone(
            self._run_v2_completion_rebuild(tamper_r016=True)
        )

    def test_completion_credit_rejects_v2_observation_byte_drift(self) -> None:
        self.assertIsNone(
            self._run_v2_completion_rebuild(tamper_observation=True)
        )

    def test_completion_credit_rejects_r026_or_r015_consumer_authority(
        self,
    ) -> None:
        for legacy_consumer in ("R026", "R015"):
            with self.subTest(legacy_consumer=legacy_consumer):
                self.assertIsNone(
                    self._run_v2_completion_rebuild(
                        legacy_consumer=legacy_consumer
                    )
                )

    def test_completion_credit_fails_closed_when_strict_v2_rebuild_fails(
        self,
    ) -> None:
        with mock.patch.object(
            graph.npc_r004_review,
            "prepare_frozen_r003_context",
            side_effect=graph.npc_recovery.BuildError("R016 rebuild differs"),
        ):
            self.assertIsNone(
                graph._npc_single_admin_recovery_v2_completion_evidence(ROOT)
            )

    def test_completion_credit_requires_r001_to_remain_rejected(self) -> None:
        self.assertIsNone(
            self._run_v2_completion_rebuild(r001_decision="APPROVED")
        )

    def test_precompletion_does_not_grant_successor_credit(self) -> None:
        checkpoint = self._npc_checkpoint_at_sequence(60)
        self.assertFalse(
            graph._npc_single_admin_recovery_successor_is_declared(
                checkpoint
            )
        )
        self.assertEqual(
            graph._npc_single_admin_recovery_sealed_product_successor_artifacts(
                ROOT,
                checkpoint,
            ),
            {},
        )
        self.assertEqual(
            graph.validate_npc_single_admin_recovery_canonical_completion(
                ROOT,
                checkpoint,
            ),
            [],
        )

    def test_partial_or_tampered_completion_declaration_fails_closed(self) -> None:
        tampered = self._npc_checkpoint_at_sequence(60)
        tampered["goal_execution"]["status_by_goal"][
            graph.NPC_SINGLE_ADMIN_RECOVERY_GOAL_ID
        ] = "COMPLETE_AT_TARGET"

        self.assertTrue(
            graph._npc_single_admin_recovery_successor_is_declared(tampered)
        )
        self.assertIsNone(
            graph._npc_single_admin_recovery_sealed_product_successor_artifacts(
                ROOT,
                tampered,
            )
        )
        self.assertEqual(
            graph.validate_npc_single_admin_recovery_canonical_completion(
                ROOT,
                tampered,
            ),
            [
                (
                    "NPC single-admin recovery completion package or source "
                    "successor differs"
                )
            ],
        )

    def test_start_to_final_transition_is_exact_55_and_rejects_tamper(self) -> None:
        added = {
            (
                "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/"
                "walksafe/admin/security/AdminRecoveryCustodyState.java"
            ),
            "backend/alembic/versions/202608120001_admin_recovery_custody.py",
            "backend/app/services/admin_credential_issuer_key.py",
            "backend/tests/test_admin_credential_issuer_binding.py",
            "backend/tests/test_admin_credential_issuer_key.py",
            "backend/tests/test_admin_runtime_acl_hardening.py",
            "deploy/config/walksafe-backend-migration.env.example",
            "deploy/systemd/walksafe-admin-issuer-bind.service",
            "deploy/sysusers.d/walksafe-backend.conf",
            "scripts/bind_walksafe_admin_credential_issuer_key.py",
            "tests/test_bind_walksafe_admin_credential_issuer_key.py",
        }
        unchanged = {
            "backend/tests/test_health_readiness.py",
            "backend/tests/test_report_image_keyring.py",
        }
        before_raw = {
            path: f"before:{path}".encode()
            for path in graph.NPC_SINGLE_ADMIN_RECOVERY_PRODUCT_PATHS
            if path not in added
        }
        after_raw = {
            path: (
                before_raw[path]
                if path in unchanged
                else f"after:{path}".encode()
            )
            for path in graph.NPC_SINGLE_ADMIN_RECOVERY_PRODUCT_PATHS
        }
        implementation = {
            "final_content_manifest": {
                "files": [
                    {
                        "path": path,
                        "sha256": hashlib.sha256(after_raw[path]).hexdigest(),
                        "byte_count": len(after_raw[path]),
                    }
                    for path in graph.NPC_SINGLE_ADMIN_RECOVERY_PRODUCT_PATHS
                ]
            }
        }

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            relative = graph.npc_recovery.START_GATE_REPOSITORY_STATE_REL
            state_path = root / relative
            state_path.parent.mkdir(parents=True)
            state_path.write_text(
                json.dumps(
                    {
                        "evidence_type": "GATE_REPOSITORY_STATE",
                        "gate_event_id": graph.npc_recovery.EXPECTED_START_EVENT_ID,
                        "repository": {"head_commit": "a" * 40},
                        "dirty_snapshot": {"paths": []},
                    },
                    sort_keys=True,
                    separators=(",", ":"),
                )
            )

            def git_result(
                _root: Path,
                argv: list[str],
                **_kwargs: object,
            ) -> subprocess.CompletedProcess[bytes]:
                if argv[:2] == ["cat-file", "-e"]:
                    return subprocess.CompletedProcess(argv, 0, stdout=b"", stderr=b"")
                path = argv[2].split(":", 1)[1]
                if path in added:
                    return subprocess.CompletedProcess(argv, 128, stdout=b"", stderr=b"")
                return subprocess.CompletedProcess(
                    argv,
                    0,
                    stdout=before_raw[path],
                    stderr=b"",
                )

            with mock.patch.object(
                graph.npc_recovery,
                "EXPECTED_START_GATE_REPOSITORY_STATE_SHA256",
                sha256_file(state_path),
            ), mock.patch.object(graph, "_run_git_bytes", side_effect=git_result):
                transitions = (
                    graph._npc_single_admin_recovery_start_to_final_transitions(
                        root,
                        implementation,
                    )
                )
                self.assertIsInstance(transitions, dict)
                self.assertEqual(len(transitions), 55)

                modified = next(iter(transitions))
                altered = copy.deepcopy(implementation)
                row = next(
                    item
                    for item in altered["final_content_manifest"]["files"]
                    if item["path"] == modified
                )
                row["sha256"] = hashlib.sha256(before_raw[modified]).hexdigest()
                self.assertIsNone(
                    graph._npc_single_admin_recovery_start_to_final_transitions(
                        root,
                        altered,
                    )
                )

    def test_npc_edge_extends_older_lineage_and_rejects_third_digest(self) -> None:
        relative = "backend/app/main.py"
        old = hashlib.sha256(b"old").hexdigest()
        fp047 = hashlib.sha256(b"fp047").hexdigest()
        fp048 = hashlib.sha256(b"fp048").hexdigest()
        fp046 = hashlib.sha256(b"fp046").hexdigest()
        npc_raw = b"npc-final"
        npc = hashlib.sha256(npc_raw).hexdigest()

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            live = root / relative
            live.parent.mkdir(parents=True)
            live.write_bytes(npc_raw)
            common = {
                "checkpoint": {},
                "fp011_live_artifacts": {},
                "fp011_transitions": {},
                "fp013_artifacts": {},
                "fp015_artifacts": {},
                "fp014_artifacts": {},
                "fp047_artifacts": {relative: (old, fp047)},
                "fp048_artifacts": {relative: (fp047, fp048)},
                "fp046_artifacts": {relative: (fp048, fp046)},
                "npc_recovery_artifacts": {relative: (fp046, npc)},
            }
            with mock.patch.object(
                graph,
                "_historical_artifact_lineage_head",
                return_value=old,
            ):
                self.assertTrue(
                    graph._successor_lineage_reaches_live(
                        root,
                        {},
                        (relative, old),
                        **common,
                    )
                )
                live.write_bytes(b"third-digest")
                self.assertFalse(
                    graph._successor_lineage_reaches_live(
                        root,
                        {},
                        (relative, old),
                        **common,
                    )
                )

    def test_declared_successor_edge_before_mismatch_fails_closed(self) -> None:
        relative = "backend/app/main.py"
        old_raw = b"old"
        old = hashlib.sha256(old_raw).hexdigest()
        wrong = hashlib.sha256(b"wrong").hexdigest()
        after = hashlib.sha256(b"after").hexdigest()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            live = root / relative
            live.parent.mkdir(parents=True)
            live.write_bytes(old_raw)
            with mock.patch.object(
                graph,
                "_historical_artifact_lineage_head",
                return_value=old,
            ):
                self.assertFalse(
                    graph._successor_lineage_reaches_live(
                        root,
                        {},
                        (relative, old),
                        checkpoint={},
                        fp011_live_artifacts={},
                        fp011_transitions={},
                        fp013_artifacts={},
                        fp015_artifacts={},
                        fp014_artifacts={},
                        fp047_artifacts={relative: (wrong, after)},
                        fp048_artifacts={},
                        fp046_artifacts={},
                        npc_recovery_artifacts={},
                    )
                )

    def test_composed_lineage_does_not_hide_mismatched_later_edge(self) -> None:
        relative = "backend/app/main.py"
        old = hashlib.sha256(b"old").hexdigest()
        live_raw = b"live"
        live = hashlib.sha256(live_raw).hexdigest()
        wrong = hashlib.sha256(b"wrong").hexdigest()
        later = hashlib.sha256(b"later").hexdigest()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / relative
            target.parent.mkdir(parents=True)
            target.write_bytes(live_raw)
            with mock.patch.object(
                graph,
                "_historical_artifact_lineage_head",
                return_value=old,
            ):
                self.assertFalse(
                    graph._successor_lineage_reaches_live(
                        root,
                        {},
                        (relative, old),
                        checkpoint={},
                        fp011_live_artifacts={},
                        fp011_transitions={},
                        fp013_artifacts={},
                        fp015_artifacts={},
                        fp014_artifacts={relative: (old, live)},
                        fp047_artifacts={relative: (wrong, later)},
                        fp048_artifacts={},
                        fp046_artifacts={},
                        npc_recovery_artifacts={},
                    )
                )

    def test_fp047_snapshot_overlay_must_end_at_fp046_final_digest(self) -> None:
        relative = "apps/android-gateway/src/routes.ts"
        old = hashlib.sha256(b"old").hexdigest()
        fp047_after = hashlib.sha256(b"fp047-after").hexdigest()
        fp046_before = hashlib.sha256(b"fp046-before").hexdigest()
        final_raw = b"fp046-final"
        final = hashlib.sha256(final_raw).hexdigest()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / relative
            target.parent.mkdir(parents=True)
            target.write_bytes(final_raw)
            common = {
                "checkpoint": {},
                "fp011_live_artifacts": {},
                "fp011_transitions": {},
                "fp013_artifacts": {},
                "fp015_artifacts": {},
                "fp014_artifacts": {},
                "fp047_artifacts": {relative: (old, fp047_after)},
                "fp048_artifacts": {},
                "fp046_artifacts": {relative: (fp046_before, final)},
                "npc_recovery_artifacts": {},
            }
            with mock.patch.object(
                graph,
                "_historical_artifact_lineage_head",
                return_value=old,
            ), mock.patch.object(
                graph,
                "_fp047_start_snapshot_successor",
                return_value=(old, final),
            ):
                self.assertTrue(
                    graph._successor_lineage_reaches_live(
                        root,
                        {},
                        (relative, old),
                        **common,
                    )
                )
            with mock.patch.object(
                graph,
                "_historical_artifact_lineage_head",
                return_value=old,
            ), mock.patch.object(
                graph,
                "_fp047_start_snapshot_successor",
                return_value=(old, hashlib.sha256(b"wrong-final").hexdigest()),
            ):
                self.assertFalse(
                    graph._successor_lineage_reaches_live(
                        root,
                        {},
                        (relative, old),
                        **common,
                    )
                )

    def test_npc_does_not_mask_unrelated_fp048_android_drift(self) -> None:
        relative = (
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/"
            "walksafe/MainActivity.kt"
        )
        before = hashlib.sha256(b"before").hexdigest()
        fp048_after = hashlib.sha256(b"fp048-after").hexdigest()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            live = root / relative
            live.parent.mkdir(parents=True)
            live.write_bytes(b"unrelated-drift")
            with mock.patch.object(
                graph,
                "_historical_artifact_lineage_head",
                return_value=before,
            ), mock.patch.object(
                graph,
                "_fp047_start_snapshot_successor",
                return_value=None,
            ), mock.patch.object(
                graph,
                "_resource_pilot_current_state_successor_bridge",
                return_value=None,
            ):
                self.assertFalse(
                    graph._successor_lineage_reaches_live(
                        root,
                        {},
                        (relative, before),
                        checkpoint={},
                        fp011_live_artifacts={},
                        fp011_transitions={},
                        fp013_artifacts={},
                        fp015_artifacts={},
                        fp014_artifacts={},
                        fp047_artifacts={},
                        fp048_artifacts={relative: (before, fp048_after)},
                        fp046_artifacts={},
                        npc_recovery_artifacts={
                            "backend/app/main.py": (
                                hashlib.sha256(b"backend-before").hexdigest(),
                                hashlib.sha256(b"backend-after").hexdigest(),
                            )
                        },
                    )
                )

    def test_historical_implementation_seals_remain_immutable(self) -> None:
        expected = {
            "WS-GOAL-EPIC-03-FP-008-R001": (
                "d5d7eca6873b5b5744c6ad8a0f55b41f0b4a94cb0aa109381a9b05c6b64aee9b"
            ),
            "WS-GOAL-EPIC-03-FP-046-R001": graph.FP046_IMPLEMENTATION_SHA256,
            "WS-GOAL-EPIC-03-FP-048-R001": (
                graph.FP048_ANDROID_REPORT_RESULT_SHA256_BY_KIND[
                    "IMPLEMENTATION_RECORD"
                ]
            ),
        }
        for goal_id, digest in expected.items():
            with self.subTest(goal_id=goal_id):
                self.assertEqual(
                    sha256_file(
                        ROOT
                        / "docs/control/execution/goal-results"
                        / goal_id
                        / "implementation-record.json"
                    ),
                    digest,
                )


class WalkSafeFp022CompletionGraphSuffixTest(unittest.TestCase):
    def test_seq69_source_has_no_completion_semantics(self) -> None:
        checkpoint = load_json(ROOT / graph.V24_CHECKPOINT_RELATIVE)
        self.assertEqual(
            graph.validate_fp022_completion_seq70_71(ROOT, checkpoint),
            [],
        )

    def test_resealed_incomplete_completion_suffix_fails_closed(self) -> None:
        checkpoint = load_json(ROOT / graph.V24_CHECKPOINT_RELATIVE)
        state = checkpoint["goal_execution"]
        if len(state["transition_history"]) >= 71:
            del state["transition_history"][69:]
        for sequence, event_type in ((70, "CANONICAL_BINDINGS_UPDATED"), (71, "GOAL_COMPLETED")):
            event = copy.deepcopy(state["transition_history"][-1])
            event.update(
                {
                    "sequence": sequence,
                    "event_id": f"WS-FORGED-FP022-{sequence}",
                    "event_type": event_type,
                    "previous_event_sha256": state["transition_history"][-1]["event_sha256"],
                }
            )
            event["event_sha256"] = graph.continuation.event_sha256(event)
            state["transition_history"].append(event)
        errors = graph.validate_fp022_completion_seq70_71(ROOT, checkpoint)
        joined = "\n".join(errors)
        self.assertIn("FP022 completion seq70 ID", joined)


class WalkSafeFp046R002CompletionGraphSuffixTest(unittest.TestCase):
    def _completion_checkpoint(self) -> tuple[Path, dict]:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        checkpoint = load_json(ROOT / graph.V24_CHECKPOINT_RELATIVE)
        state = checkpoint["goal_execution"]
        history = state["transition_history"]
        del history[85:]
        source = history[84]

        review_binding = {}
        for role, relative in (
            graph.FP046_R002_COMPLETION_REVIEW_PATH_BY_ROLE.items()
        ):
            raw = json_bytes(
                {
                    "document_id": (
                        graph.FP046_R002_COMPLETION_REVIEW_DOCUMENT_ID_BY_ROLE[
                            role
                        ]
                    ),
                    "role": role,
                }
            )
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(raw)
            review_binding[role] = {
                "path": relative,
                "sha256": hashlib.sha256(raw).hexdigest(),
                "byte_length": len(raw),
            }

        goal_review_bindings = []
        for kind, identity in (
            graph.FP046_R002_COMPLETION_GOAL_REVIEW_BY_KIND.items()
        ):
            raw = json_bytes({"kind": kind})
            path = root / identity["path"]
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(raw)
            goal_review_bindings.append(
                {
                    "document_id": identity["document_id"],
                    "path": identity["path"],
                    "file_sha256": hashlib.sha256(raw).hexdigest(),
                    "byte_count": len(raw),
                    "mutable": False,
                }
            )

        changed_bindings = {}
        for role, identity in graph.FP046_R002_R030_BINDING_IDENTITY.items():
            digest = write_json(root, identity["path"], {"role": role})
            changed_bindings[role] = {
                "role": role,
                "document_id": identity["document_id"],
                "path": identity["path"],
                "file_sha256": digest,
            }
        receipt = {
            "document_id": graph.FP046_R002_COMPLETION_DOCUMENT_ID,
            "goal_id": graph.FP046_R002_GOAL_ID,
            "goal_status": "COMPLETE_AT_TARGET",
            "source_sequence": 85,
            "canonical_update_sequence": 86,
            "completion_sequence": 87,
            "evidence_bindings": goal_review_bindings,
            "transition_control_review_binding": review_binding,
            "successor": {
                "goal_id": graph.FP046_R002_NEXT_GOAL_ID,
                "work_item_id": (
                    "EPIC-03-FP048-ENCRYPTION-CONNECTION-SECURITY-INCIDENT"
                ),
                "status": "PLANNED_NOT_ACTIVATED",
            },
            "completion_boundary": copy.deepcopy(
                graph.FP046_R002_ZERO_CREDIT_BOUNDARY
            ),
            "release_completion_claimed": False,
        }
        completion_digest = write_json(
            root,
            graph.FP046_R002_COMPLETION_PATH,
            receipt,
        )
        completion_binding = {
            "role": graph.FP046_R002_COMPLETION_ROLE,
            "document_id": graph.FP046_R002_COMPLETION_DOCUMENT_ID,
            "path": graph.FP046_R002_COMPLETION_PATH,
            "file_sha256": completion_digest,
        }
        changed_bindings[graph.FP046_R002_COMPLETION_ROLE] = completion_binding
        canonical_snapshot = copy.deepcopy(
            history[83]["canonical_binding_snapshot_after"]
        )
        canonical_snapshot.update(changed_bindings)

        completed = copy.deepcopy(
            history[73]["completion_evidence_by_goal_after"]
        )
        completed[graph.FP046_R002_GOAL_ID] = [
            graph.FP046_R002_COMPLETION_ROLE
        ]
        update_at = (
            datetime.fromisoformat(source["occurred_at"])
            + timedelta(seconds=1)
        )
        completion_at = update_at + timedelta(seconds=1)
        update = {
            "sequence": 86,
            "event_id": graph.FP046_R002_COMPLETION_UPDATE_EVENT_ID,
            "event_type": "CANONICAL_BINDINGS_UPDATED",
            "occurred_on": update_at.date().isoformat(),
            "occurred_at": update_at.isoformat(),
            "previous_focus_goal_id": graph.FP046_R002_GOAL_ID,
            "previous_focus_content_sha256": graph.FP046_R002_GOAL_SHA256,
            "focus_goal_id": graph.FP046_R002_GOAL_ID,
            "focus_goal_content_sha256": graph.FP046_R002_GOAL_SHA256,
            "from_status": "IN_PROGRESS",
            "to_status": "IN_PROGRESS",
            "static_plan_manifest_sha256": (
                graph.continuation.EXPECTED_V24_MANIFEST_SHA256
            ),
            "status_changes": {},
            "runtime_after": copy.deepcopy(source["runtime_after"]),
            "blockers_after": {},
            "blocker_resolution_ids_after": [],
            "source_checkpoint_version": "1.25.0",
            "evidence_refs": copy.deepcopy(
                graph.FP046_R002_COMPLETION_CHANGED_ROLES
            ),
            "previous_event_sha256": source["event_sha256"],
            "produced_by_goal_id": graph.FP046_R002_GOAL_ID,
            "produced_binding_roles": copy.deepcopy(
                graph.FP046_R002_COMPLETION_PRODUCED_ROLES
            ),
            "producer_completion_receipt_binding": completion_binding,
            "changed_binding_roles": copy.deepcopy(
                graph.FP046_R002_COMPLETION_CHANGED_ROLES
            ),
            "changed_subject_ids_by_role": {
                "IMPLEMENTATION_BACKLOG": ["FP-046", "FP-048"],
                "IMPLEMENTATION_GAP": [
                    "FP-046", "FP-048", "GAP-055", "GAP-057"
                ],
                graph.FP046_R002_COMPLETION_ROLE: [
                    graph.FP046_R002_GOAL_ID
                ],
            },
            "producer_output_subject_ids_by_role": {
                "IMPLEMENTATION_BACKLOG": ["FP-046", "FP-048"],
                "IMPLEMENTATION_GAP": [
                    "FP-046", "FP-048", "GAP-055", "GAP-057"
                ],
            },
            "impact_closure_goal_ids": [
                graph.R002_REOPEN_PARENT_GOAL_ID,
                graph.FP048_ANDROID_REPORT_GOAL_ID,
            ],
            "impact_disposition_by_goal": {
                graph.R002_REOPEN_PARENT_GOAL_ID: {
                    "result": "REVALIDATION_REFRESH_REQUIRED",
                    "target_status": "READY",
                },
                graph.FP048_ANDROID_REPORT_GOAL_ID: {
                    "result": "REOPEN_REQUIRED",
                    "target_status": "SUPERSEDED",
                },
            },
            "reopened_completion_event_sha256_by_goal": {
                graph.FP048_ANDROID_REPORT_GOAL_ID: (
                    graph.FP048_ANDROID_REPORT_COMPLETION_EVENT_SHA256
                )
            },
            "canonical_binding_snapshot_after": canonical_snapshot,
            "transition_control_review_binding": review_binding,
        }
        update["event_sha256"] = graph.continuation.event_sha256(update)

        completion_runtime = copy.deepcopy(source["runtime_after"])
        completion_runtime.update(
            {
                "focus_goal_id": graph.R002_REOPEN_PARENT_GOAL_ID,
                "focus_goal_path": graph.FP046_R002_PARENT_GOAL_PATH,
                "focus_work_item_id": "",
                "focus_source": "WORKSTREAM_GRAPH",
                "ready_frontier_goal_ids": [
                    graph.R002_REOPEN_PARENT_GOAL_ID,
                    "WS-GOAL-EPIC-04",
                    "WS-GOAL-EPIC-12",
                ],
                "artifact_work_queue_sha256": (
                    graph.continuation.canonical_json_sha256(
                        state["artifact_work_queue"]
                    )
                ),
                "completion_boundary_sha256": (
                    graph.continuation.canonical_json_sha256(
                        state["completion_boundary"]
                    )
                ),
            }
        )
        completion = {
            "sequence": 87,
            "event_id": graph.FP046_R002_COMPLETION_EVENT_ID,
            "event_type": "GOAL_COMPLETED",
            "occurred_on": completion_at.date().isoformat(),
            "occurred_at": completion_at.isoformat(),
            "previous_focus_goal_id": graph.FP046_R002_GOAL_ID,
            "previous_focus_content_sha256": graph.FP046_R002_GOAL_SHA256,
            "focus_goal_id": graph.R002_REOPEN_PARENT_GOAL_ID,
            "focus_goal_content_sha256": graph.FP046_R002_PARENT_GOAL_SHA256,
            "subject_goal_id": graph.FP046_R002_GOAL_ID,
            "from_status": "IN_PROGRESS",
            "to_status": "COMPLETE_AT_TARGET",
            "static_plan_manifest_sha256": (
                graph.continuation.EXPECTED_V24_MANIFEST_SHA256
            ),
            "status_changes": {
                graph.FP046_R002_GOAL_ID: "COMPLETE_AT_TARGET"
            },
            "runtime_after": completion_runtime,
            "blockers_after": {},
            "blocker_resolution_ids_after": [],
            "source_checkpoint_version": "1.25.0",
            "evidence_refs": [graph.FP046_R002_COMPLETION_ROLE],
            "previous_event_sha256": update["event_sha256"],
            "canonical_update_event_sha256": update["event_sha256"],
            "completion_receipt_binding": completion_binding,
            "completion_evidence_bindings": {
                graph.FP046_R002_COMPLETION_ROLE: completion_binding
            },
            "completion_evidence_by_goal_after": completed,
            "canonical_binding_snapshot_after": canonical_snapshot,
        }
        completion["event_sha256"] = graph.continuation.event_sha256(
            completion
        )
        history.extend((update, completion))
        checkpoint["canonical_bindings"] = sorted(
            copy.deepcopy(list(canonical_snapshot.values())),
            key=lambda row: row["role"],
        )
        statuses = state["status_by_goal"]
        statuses[graph.FP046_R002_GOAL_ID] = "COMPLETE_AT_TARGET"
        statuses[graph.FP048_ANDROID_REPORT_GOAL_ID] = "COMPLETE_AT_TARGET"
        statuses[graph.R002_REOPEN_PARENT_GOAL_ID] = "READY"
        statuses.pop(graph.FP046_R002_NEXT_GOAL_ID, None)
        state.update(
            {
                "focus_goal_id": graph.R002_REOPEN_PARENT_GOAL_ID,
                "focus_goal_path": graph.FP046_R002_PARENT_GOAL_PATH,
                "focus_work_item_id": "",
                "focus_source": "WORKSTREAM_GRAPH",
                "ready_frontier_goal_ids": [
                    graph.R002_REOPEN_PARENT_GOAL_ID,
                    "WS-GOAL-EPIC-04",
                    "WS-GOAL-EPIC-12",
                ],
                "completion_evidence_by_goal": completed,
                "pending_producer_completion_goal_id": "",
                "transition_history_anchor_sha256": completion[
                    "event_sha256"
                ],
                "validation_cutoff_at": completion["occurred_at"],
            }
        )
        return root, checkpoint

    @staticmethod
    def _reseal_pair(checkpoint: dict) -> None:
        history = checkpoint["goal_execution"]["transition_history"]
        update, completion = history[85:87]
        update["event_sha256"] = graph.continuation.event_sha256(update)
        completion["previous_event_sha256"] = update["event_sha256"]
        completion["canonical_update_event_sha256"] = update[
            "event_sha256"
        ]
        completion["event_sha256"] = graph.continuation.event_sha256(
            completion
        )
        if len(history) > 87:
            history[87]["previous_event_sha256"] = completion["event_sha256"]
            history[87]["event_sha256"] = graph.continuation.event_sha256(
                history[87]
            )

    def test_seq85_is_dormant_and_seq86_only_fails_closed(self) -> None:
        checkpoint = load_json(ROOT / graph.V24_CHECKPOINT_RELATIVE)
        history = checkpoint["goal_execution"]["transition_history"]
        del history[85:]
        self.assertEqual(
            graph.validate_fp046_r002_completion_seq86_87(
                ROOT, checkpoint
            ),
            [],
        )
        history.append({})
        self.assertEqual(
            graph.validate_fp046_r002_completion_seq86_87(
                ROOT, checkpoint
            ),
            [
                "FP046 R002 seq86 producer transaction lacks adjacent "
                "seq87 completion"
            ],
        )

    def test_exact_pair_and_descendant_history_are_accepted(self) -> None:
        root, checkpoint = self._completion_checkpoint()
        self.assertEqual(
            graph.validate_fp046_r002_completion_seq86_87(
                root, checkpoint
            ),
            [],
        )
        history = checkpoint["goal_execution"]["transition_history"]
        descendant = {
            "sequence": 88,
            "event_id": "WS-SYNTHETIC-FP048-R002-SEQ88",
            "event_type": "GOAL_SUPERSEDED",
            "previous_event_sha256": history[-1]["event_sha256"],
        }
        descendant["event_sha256"] = graph.continuation.event_sha256(
            descendant
        )
        history.append(descendant)
        checkpoint["goal_execution"]["focus_goal_id"] = (
            graph.FP046_R002_NEXT_GOAL_ID
        )
        self.assertEqual(
            graph.validate_fp046_r002_completion_seq86_87(
                root, checkpoint
            ),
            [],
        )

    def test_seq87_projection_preserves_historical_byte_witness(self) -> None:
        _root, checkpoint = self._completion_checkpoint()
        order_preserving_raw = (
            json.dumps(
                checkpoint,
                ensure_ascii=False,
                indent=2,
                sort_keys=False,
            )
            + "\n"
        ).encode("utf-8")
        sort_rewritten_raw = (
            json.dumps(
                checkpoint,
                ensure_ascii=False,
                indent=2,
                sort_keys=True,
            )
            + "\n"
        ).encode("utf-8")
        self.assertEqual(
            json.loads(order_preserving_raw),
            json.loads(sort_rewritten_raw),
        )
        self.assertNotEqual(order_preserving_raw, sort_rewritten_raw)

        review, correction, _started = (
            graph._fp046_r002_recovery_control_modules()
        )
        original_reader = review._read_regular
        original_safe_file = correction._safe_file

        def binding_for(raw: bytes) -> dict:
            def review_reader(root: Path, relative: Path, **kwargs: object):
                if relative == review.CHECKPOINT_REL:
                    return raw
                return original_reader(root, relative, **kwargs)

            def correction_file(
                root: Path,
                relative: Path,
                **kwargs: object,
            ):
                if relative == correction.CHECKPOINT_RELATIVE:
                    return mock.Mock(read_bytes=mock.Mock(return_value=raw))
                return original_safe_file(root, relative, **kwargs)

            with mock.patch.object(
                review,
                "_read_regular",
                side_effect=review_reader,
            ), mock.patch.object(
                correction,
                "_safe_file",
                side_effect=correction_file,
            ):
                return review.review_source_checkpoint_binding(ROOT)

        self.assertEqual(
            binding_for(order_preserving_raw)["sha256"],
            review.REVIEW_SOURCE_CHECKPOINT_SHA256,
        )
        with self.assertRaisesRegex(
            review.ReviewError,
            "reconstructed seq83 review source checkpoint differs",
        ):
            binding_for(sort_rewritten_raw)

    def test_resealed_field_and_fp048_impact_tamper_fail_closed(self) -> None:
        root, checkpoint = self._completion_checkpoint()
        checkpoint["goal_execution"]["transition_history"][85][
            "unexpected"
        ] = True
        self._reseal_pair(checkpoint)
        errors = graph.validate_fp046_r002_completion_seq86_87(
            root, checkpoint
        )
        self.assertIn(
            "FP046 R002 completion seq86 fields differs",
            errors,
        )

        root, checkpoint = self._completion_checkpoint()
        checkpoint["goal_execution"]["transition_history"][85][
            "impact_disposition_by_goal"
        ][graph.FP048_ANDROID_REPORT_GOAL_ID] = {
            "result": "REVALIDATION_REFRESH_REQUIRED",
            "target_status": "READY",
        }
        self._reseal_pair(checkpoint)
        errors = graph.validate_fp046_r002_completion_seq86_87(
            root, checkpoint
        )
        self.assertIn(
            "FP046 R002 completion seq86 impact disposition differs",
            errors,
        )

        for stale_round in ("R001", "R002", "R003", "R004"):
            with self.subTest(stale_transition_round=stale_round):
                root, checkpoint = self._completion_checkpoint()
                checkpoint["goal_execution"]["transition_history"][85][
                    "transition_control_review_binding"
                ]["assignment"]["path"] = (
                    "docs/control/execution/workstream-transitions/seq86-87/"
                    f"review-rounds/{stale_round}/assignment.json"
                )
                self._reseal_pair(checkpoint)
                errors = graph.validate_fp046_r002_completion_seq86_87(
                    root, checkpoint
                )
                self.assertIn(
                    "FP046 R002 completion R005 transition review differs: "
                    "assignment",
                    errors,
                )

        root, checkpoint = self._completion_checkpoint()
        assignment_path = (
            graph.FP046_R002_COMPLETION_REVIEW_PATH_BY_ROLE["assignment"]
        )
        assignment = load_json(root / assignment_path)
        assignment["document_id"] = (
            "WS-FP046-R002-SEQ86-87-REVIEW-ASSIGNMENT-R002"
        )
        assignment_digest = write_json(root, assignment_path, assignment)
        assignment_binding = checkpoint["goal_execution"][
            "transition_history"
        ][85]["transition_control_review_binding"]["assignment"]
        assignment_binding["sha256"] = assignment_digest
        assignment_binding["byte_length"] = (
            root / assignment_path
        ).stat().st_size
        self._reseal_pair(checkpoint)
        errors = graph.validate_fp046_r002_completion_seq86_87(
            root, checkpoint
        )
        self.assertIn(
            "FP046 R002 completion R005 transition review differs: assignment",
            errors,
        )

    def test_resealed_completion_evidence_tamper_fails_closed(self) -> None:
        root, checkpoint = self._completion_checkpoint()
        completion = checkpoint["goal_execution"]["transition_history"][86]
        completion["evidence_refs"] = []
        self._reseal_pair(checkpoint)
        errors = graph.validate_fp046_r002_completion_seq86_87(
            root, checkpoint
        )
        self.assertIn(
            "FP046 R002 completion seq87 evidence differs",
            errors,
        )

    def test_r001_r002_r003_r004_goal_review_and_receipt_paths_fail_closed(
        self,
    ) -> None:
        stale_goal_paths = (
            (
                "R001",
                "docs/control/execution/goal-results/"
                f"{graph.FP046_R002_GOAL_ID}/review-subject.json",
            ),
            (
                "R002",
                "docs/control/execution/goal-results/"
                f"{graph.FP046_R002_GOAL_ID}/review-rounds/R002/"
                "review-subject.json",
            ),
            (
                "R003",
                "docs/control/execution/goal-results/"
                f"{graph.FP046_R002_GOAL_ID}/review-rounds/R003/"
                "review-subject.json",
            ),
            (
                "R004",
                "docs/control/execution/goal-results/"
                f"{graph.FP046_R002_GOAL_ID}/review-rounds/R004/"
                "review-subject.json",
            ),
        )
        for stale_round, stale_path in stale_goal_paths:
            with self.subTest(stale_goal_round=stale_round):
                root, checkpoint = self._completion_checkpoint()
                receipt_path = root / graph.FP046_R002_COMPLETION_PATH
                receipt = load_json(receipt_path)
                receipt["evidence_bindings"][0]["path"] = stale_path
                digest = write_json(
                    root,
                    graph.FP046_R002_COMPLETION_PATH,
                    receipt,
                )
                history = checkpoint["goal_execution"]["transition_history"]
                history[85]["producer_completion_receipt_binding"][
                    "file_sha256"
                ] = digest
                for row in checkpoint["canonical_bindings"]:
                    if row["role"] == graph.FP046_R002_COMPLETION_ROLE:
                        row["file_sha256"] = digest
                self._reseal_pair(checkpoint)
                errors = graph.validate_fp046_r002_completion_seq86_87(
                    root, checkpoint
                )
                self.assertIn(
                    "FP046 R002 completion stale Goal review path is forbidden",
                    errors,
                )

        stale_receipts = (
            (
                "R002",
                "WS-FP046-R002-CONSENT-WITHDRAWAL-DELETION-WORK-ITEM-"
                "COMPLETION-20260825-001",
                "completion-receipt.json",
            ),
            (
                "R003",
                "WS-FP046-R002-CONSENT-WITHDRAWAL-DELETION-WORK-ITEM-"
                "COMPLETION-20260825-R003",
                "completion-receipt-r003.json",
            ),
            (
                "R004",
                "WS-FP046-R002-CONSENT-WITHDRAWAL-DELETION-WORK-ITEM-"
                "COMPLETION-20260825-R004",
                "completion-receipt-r004.json",
            ),
        )
        for stale_round, old_document_id, filename in stale_receipts:
            with self.subTest(stale_receipt_round=stale_round):
                root, checkpoint = self._completion_checkpoint()
                receipt = load_json(root / graph.FP046_R002_COMPLETION_PATH)
                old_path = (
                    "docs/control/execution/goal-results/"
                    f"{graph.FP046_R002_GOAL_ID}/{filename}"
                )
                receipt["document_id"] = old_document_id
                old_digest = write_json(root, old_path, receipt)
                old_binding = {
                    "role": graph.FP046_R002_COMPLETION_ROLE,
                    "document_id": old_document_id,
                    "path": old_path,
                    "file_sha256": old_digest,
                }
                history = checkpoint["goal_execution"]["transition_history"]
                history[85]["producer_completion_receipt_binding"] = old_binding
                history[85]["canonical_binding_snapshot_after"][
                    graph.FP046_R002_COMPLETION_ROLE
                ] = old_binding
                history[86]["completion_receipt_binding"] = old_binding
                history[86]["completion_evidence_bindings"] = {
                    graph.FP046_R002_COMPLETION_ROLE: old_binding
                }
                history[86]["canonical_binding_snapshot_after"][
                    graph.FP046_R002_COMPLETION_ROLE
                ] = old_binding
                for index, row in enumerate(checkpoint["canonical_bindings"]):
                    if row["role"] == graph.FP046_R002_COMPLETION_ROLE:
                        checkpoint["canonical_bindings"][index] = old_binding
                self._reseal_pair(checkpoint)
                errors = graph.validate_fp046_r002_completion_seq86_87(
                    root, checkpoint
                )
                self.assertIn(
                    "FP046 R002 completion receipt binding differs",
                    errors,
                )


class WalkSafeFp046NpcR002ReopenGraphTest(unittest.TestCase):
    def _review_authority_fixture(
        self,
    ) -> tuple[Path, dict, list[dict], dict[Path, bytes]]:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        review_paths = (
            *(Path(path) for path in r002_preflight.TRANSITION_R001_PATHS),
            *(Path(path) for path in r002_preflight.TRANSITION_R002_PATHS),
            *(Path(path) for path in r002_preflight.TRANSITION_R003_PATHS),
            *(Path(path) for path in r002_preflight.TRANSITION_R004_PATHS),
            *(
                Path(path)
                for path in completion_review.CONTROL_SUCCESSOR_R009_PATHS
            ),
            *(
                Path(path)
                for path in completion_review.CONTROL_SUCCESSOR_R010_PATHS
            ),
            *(
                Path(path)
                for path in completion_review.CONTROL_SUCCESSOR_R011_PATHS
            ),
        )
        frozen_raw: dict[Path, bytes] = {}
        for index, relative in enumerate(review_paths):
            raw = f'{{"review":{index}}}\n'.encode("utf-8")
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(raw)
            frozen_raw[relative] = raw
        for specification in graph.R002_REOPEN_SUCCESSORS:
            target = root / specification["goal_path"]
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(
                f"goal_id = {specification['goal_id']}\n",
                encoding="utf-8",
            )
        suffix = [
            {
                "sequence": sequence,
                "event_id": f"WS-TEST-{sequence}",
                "event_type": event_type,
                "status_changes": {},
            }
            for sequence, event_type in zip(
                range(72, 77),
                graph.R002_REOPEN_EVENT_TYPES,
                strict=True,
            )
        ]
        checkpoint = {
            "goal_execution": {
                "transition_history": [],
                "status_by_goal": {},
                "completion_evidence_by_goal": {},
                "archived_completion_evidence_by_goal": {},
                "dynamic_goal_inventory": {},
                "materialized_child_goal_ids_by_parent": {},
                "ready_frontier_goal_ids": [],
                "focus_goal_id": "WS-TEST-FOCUS",
                "artifact_work_queue": {},
                "completion_boundary": {},
            },
            "working_tree_snapshot": {
                "managed_changed_paths": sorted(
                    path.as_posix() for path in review_paths
                ),
            },
        }
        return root, checkpoint, suffix, frozen_raw

    def _review_authority_errors(
        self,
        root: Path,
        checkpoint: dict,
        suffix: list[dict],
        frozen_raw: dict[Path, bytes],
        *,
        frozen_failure: Exception | None = None,
    ) -> list[str]:
        roles = ("assignment", "review_result", "independent_review")

        def binding(paths: tuple[Path, ...], raw_by_path: dict[Path, bytes]) -> dict:
            return {
                role: {
                    "path": path.as_posix(),
                    "sha256": hashlib.sha256(raw_by_path[path]).hexdigest(),
                    "byte_length": len(raw_by_path[path]),
                }
                for role, path in zip(roles, paths, strict=True)
            }

        r001_paths = tuple(
            Path(path) for path in r002_preflight.TRANSITION_R001_PATHS
        )
        r002_paths = tuple(
            Path(path) for path in r002_preflight.TRANSITION_R002_PATHS
        )
        r003_paths = tuple(
            Path(path) for path in r002_preflight.TRANSITION_R003_PATHS
        )
        r004_paths = tuple(
            Path(path) for path in r002_preflight.TRANSITION_R004_PATHS
        )
        r009_paths = tuple(
            Path(path)
            for path in completion_review.CONTROL_SUCCESSOR_R009_PATHS
        )
        r010_paths = tuple(
            Path(path)
            for path in completion_review.CONTROL_SUCCESSOR_R010_PATHS
        )
        r011_paths = tuple(
            Path(path)
            for path in completion_review.CONTROL_SUCCESSOR_R011_PATHS
        )

        def load(paths: tuple[Path, ...]) -> tuple[dict, dict[Path, bytes]]:
            raw_by_path = {
                path: (root / path).read_bytes() for path in paths
            }
            if any(raw_by_path[path] != frozen_raw[path] for path in paths):
                raise ValueError("frozen review bytes differ")
            return binding(paths, raw_by_path), raw_by_path

        r001_binding = binding(
            r001_paths,
            {path: frozen_raw[path] for path in r001_paths},
        )
        r002_binding = binding(
            r002_paths,
            {path: frozen_raw[path] for path in r002_paths},
        )
        r003_binding = binding(
            r003_paths,
            {path: frozen_raw[path] for path in r003_paths},
        )
        r004_binding = binding(
            r004_paths,
            {path: frozen_raw[path] for path in r004_paths},
        )
        r009_binding = binding(
            r009_paths,
            {path: frozen_raw[path] for path in r009_paths},
        )
        r010_binding = binding(
            r010_paths,
            {path: frozen_raw[path] for path in r010_paths},
        )
        r011_binding = binding(
            r011_paths,
            {path: frozen_raw[path] for path in r011_paths},
        )

        def neutral(plan: dict) -> dict:
            result = copy.deepcopy(plan)
            first = result["events"][0]
            first.pop("transition_review_binding", None)
            first.pop("transition_review_subject_binding", None)
            for event in result["events"]:
                event.pop("event_sha256", None)
                event.pop("previous_event_sha256", None)
            return result

        def core_binding(plan: dict) -> dict:
            raw = json.dumps(
                plan,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            ).encode("utf-8")
            return {
                "content_type": (
                    "FP046_NPC_R002_APPROVAL_NEUTRAL_SEQ72_76_PLAN_CORE"
                ),
                "sha256": hashlib.sha256(raw).hexdigest(),
                "byte_length": len(raw),
            }

        first = suffix[0]
        first.setdefault(
            "predecessor_transition_review_binding",
            r003_binding,
        )
        first.setdefault("r009_control_review_binding", r009_binding)
        first.setdefault("r010_control_review_binding", r010_binding)
        first.setdefault("r011_control_review_binding", r011_binding)
        plan = graph._r002_checkpoint_review_plan(
            root,
            checkpoint,
            suffix,
            r002_preflight,
        )
        self.assertIsNotNone(plan)
        expected_core = checkpoint.setdefault(
            "_test_review_core_binding",
            core_binding(neutral(plan)),
        )
        first.setdefault("transition_review_binding", r004_binding)
        first.setdefault("transition_review_subject_binding", expected_core)

        assignment = {
            "review_scope": {
                "predecessor_transition_review_bindings": r003_binding,
                "r009_control_successor_review_bindings": r009_binding,
                "r010_control_successor_review_bindings": r010_binding,
                "r011_control_successor_review_bindings": r011_binding,
                "corrected_plan_core_binding": expected_core,
            }
        }

        def strict_json_bytes(raw: bytes, _label: str) -> dict:
            del raw
            return copy.deepcopy(assignment)

        def validate_plan(
            reviewed_plan: dict,
            scope: dict,
            transition_binding: dict,
        ) -> None:
            if (
                transition_binding != r004_binding
                or reviewed_plan["events"][0].get(
                    "predecessor_transition_review_binding"
                )
                != r003_binding
                or reviewed_plan["events"][0].get(
                    "r009_control_review_binding"
                )
                != r009_binding
                or reviewed_plan["events"][0].get(
                    "r010_control_review_binding"
                )
                != r010_binding
                or reviewed_plan["events"][0].get(
                    "r011_control_review_binding"
                )
                != r011_binding
                or reviewed_plan["events"][0].get(
                    "transition_review_subject_binding"
                )
                != scope.get("corrected_plan_core_binding")
                or core_binding(neutral(reviewed_plan))
                != scope.get("corrected_plan_core_binding")
            ):
                raise ValueError("reviewed core differs")

        frozen_authority = (
            mock.patch.object(
                graph,
                "_fp046_r002_optional_frozen_r011_authority",
                side_effect=frozen_failure,
            )
            if frozen_failure is not None
            else mock.patch.object(
                graph,
                "_fp046_r002_optional_frozen_r011_authority",
                return_value=None,
            )
        )
        with frozen_authority, mock.patch.object(
            r002_preflight,
            "load_frozen_transition_r001",
            side_effect=lambda _root: load(r001_paths),
        ), mock.patch.object(
            r002_preflight,
            "load_frozen_transition_r002",
            side_effect=lambda _root: load(r002_paths),
        ), mock.patch.object(
            r002_preflight,
            "load_frozen_transition_r003",
            side_effect=lambda _root: load(r003_paths),
        ), mock.patch.object(
            r002_preflight,
            "load_validated_transition_r004",
            side_effect=lambda _root: load(r004_paths),
        ), mock.patch.object(
            r002_preflight,
            "load_frozen_control_successor_r009",
            side_effect=lambda _root: load(r009_paths),
        ), mock.patch.object(
            r002_preflight,
            "load_frozen_control_successor_r010",
            side_effect=lambda _root: load(r010_paths),
        ), mock.patch.object(
            r002_preflight,
            "load_validated_control_successor_r011",
            side_effect=lambda _root: load(r011_paths),
        ), mock.patch.object(
            r002_preflight,
            "strict_json_bytes",
            side_effect=strict_json_bytes,
        ), mock.patch.object(
            r002_preflight,
            "approval_neutral_plan_core",
            side_effect=neutral,
        ), mock.patch.object(
            r002_preflight,
            "transition_plan_core_binding",
            side_effect=core_binding,
        ), mock.patch.object(
            r002_preflight,
            "validate_reviewed_transition_plan",
            side_effect=validate_plan,
        ):
            return graph._r002_review_authority_errors(
                root,
                checkpoint,
                first,
                suffix,
            )

    def _checkpoint(self) -> tuple[Path, dict]:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        source_checkpoint = load_json(ROOT / r002_preflight.CHECKPOINT_REL)
        control_review_support_paths = {
            *completion_review.START_REVIEW_PATHS,
            *completion_review.EVIDENCE_PATHS,
            *completion_review.r028_builder.R027_INPUT_PATHS,
            completion_review.evidence.CONTRACT_REL,
            completion_review.evidence.START_GATE_REL,
            completion_review.evidence.GOAL_REL,
            *(
                Path(relative)
                for group in completion_review.evidence.IMPLEMENTATION_SOURCE_GROUPS
                for relative in group.paths
            ),
            completion_review.ASSIGNMENT_REL,
            completion_review.RESULT_REL,
            completion_review.INDEPENDENT_REL,
            *(
                Path(row["path"])
                for row in completion_review.SUPERSEDED_ASSIGNMENTS
            ),
            *completion_review.CONTROL_SUCCESSOR_EVIDENCE_PATHS,
            *completion_review.CONTROL_SUCCESSOR_R001_PATHS,
            *completion_review.CONTROL_SUCCESSOR_R002_EVIDENCE_PATHS,
            *completion_review.CONTROL_SUCCESSOR_R002_PATHS,
            *completion_review.CONTROL_SUCCESSOR_R003_EVIDENCE_PATHS,
            *completion_review.CONTROL_SUCCESSOR_R003_PATHS,
            *completion_review.CONTROL_SUCCESSOR_R004_EVIDENCE_PATHS,
            *completion_review.CONTROL_SUCCESSOR_R004_PATHS,
            *completion_review.CONTROL_SUCCESSOR_R005_PATHS,
            *completion_review.CONTROL_SUCCESSOR_R006_PATHS,
            *completion_review.CONTROL_SUCCESSOR_R007_PATHS,
            *completion_review.CONTROL_SUCCESSOR_R008_PATHS,
            *completion_review.CONTROL_SUCCESSOR_R009_PATHS,
            *completion_review.CONTROL_SUCCESSOR_R010_PATHS,
        }
        source_paths = (
            r002_preflight.CHECKPOINT_REL,
            *r002_preflight.R028_PATHS,
            r002_preflight.FP046_R001_REL,
            r002_preflight.NPC_R001_REL,
            r002_preflight.FP022_R001_REL,
            r002_preflight.EPIC03_REL,
            *r002_preflight.r029_candidate.CURRENT_SOURCE_PATHS,
            *r002_preflight.R007_REVIEW_PINS,
            *completion_review.CONTROL_SUCCESSOR_R011_COHORT_PATHS,
            *completion_review.CONTROL_SUCCESSOR_R011_PATHS,
            *control_review_support_paths,
            *r002_transaction.CATALOG_RELATIVES,
            *(
                Path(path)
                for path in source_checkpoint["working_tree_snapshot"][
                    "managed_changed_paths"
                ]
            ),
            *(
                Path(path)
                for path in source_checkpoint["goal_execution"][
                    "managed_goal_paths"
                ]
            ),
            *(
                Path(binding["path"])
                for binding in source_checkpoint["canonical_bindings"]
            ),
        )
        for relative in sorted(set(source_paths)):
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            source = ROOT / relative
            target.write_bytes(source.read_bytes())
            target.chmod(source.stat().st_mode & 0o777)
        from scripts import (
            build_walksafe_fp046_r002_seq77_78_review_20260823 as start_review,
        )

        start_review.validated_frozen_r011_context(root)
        checkpoint = load_json(root / r002_preflight.CHECKPOINT_REL)
        history = checkpoint["goal_execution"]["transition_history"]
        if len(history) >= 76:
            self.assertEqual(
                [event["sequence"] for event in history[71:76]],
                [72, 73, 74, 75, 76],
            )
            plan = graph._r002_checkpoint_review_plan(
                root,
                checkpoint,
                history[71:76],
                r002_preflight,
            )
            self.assertIsNotNone(plan)
            self._transaction_preflight = copy.deepcopy(plan)
            return root, checkpoint
        historical_review_overlay = {
            Path(path): (ROOT / path).read_bytes()
            for path in (
                *r002_preflight.TRANSITION_R001_PATHS,
                *r002_preflight.TRANSITION_R002_PATHS,
            )
        }
        predecessor_review_overlay = {
            Path(path): (ROOT / path).read_bytes()
            for path in r002_preflight.TRANSITION_R003_PATHS
        }
        transition_review_overlay = {
            Path(path): f"transition-R004-{role}".encode("utf-8")
            for role, path in zip(
                ("assignment", "review-result", "independent-review"),
                r002_preflight.TRANSITION_R004_PATHS,
                strict=True,
            )
        }
        for relative, raw in transition_review_overlay.items():
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(raw)
        r009_control_review_overlay = {
            Path(path): (ROOT / path).read_bytes()
            for path in completion_review.CONTROL_SUCCESSOR_R009_PATHS
        }
        r010_control_review_overlay = {
            Path(path): (ROOT / path).read_bytes()
            for path in completion_review.CONTROL_SUCCESSOR_R010_PATHS
        }
        r011_control_review_overlay = {
            Path(path): (root / path).read_bytes()
            for path in completion_review.CONTROL_SUCCESSOR_R011_PATHS
        }
        review_closure_paths = {
            *historical_review_overlay,
            *predecessor_review_overlay,
            *transition_review_overlay,
            *r009_control_review_overlay,
            *r010_control_review_overlay,
            *r011_control_review_overlay,
        }
        compact_paths = sorted(
            {
                path.as_posix()
                for path in (
                    *r002_transaction.SOURCE_CONTROL_PATHS,
                    *r002_transaction.CATALOG_RELATIVES,
                    *completion_review.CONTROL_SUCCESSOR_R011_COHORT_PATHS,
                    *review_closure_paths,
                    *(
                        Path(path)
                        for path in source_checkpoint[
                            "working_tree_snapshot"
                        ]["managed_changed_paths"]
                    ),
                )
            }
        )
        snapshot = checkpoint["working_tree_snapshot"]
        snapshot["managed_changed_paths"] = compact_paths
        snapshot["managed_changed_path_count"] = len(compact_paths)
        snapshot["path_set_sha256"] = "0" * 64
        snapshot["content_set_sha256"] = "0" * 64
        handoff = checkpoint["session_handoff"]
        handoff["changed_files"] = compact_paths
        handoff["source_commit_or_snapshot"].update(
            {
                "file_count": len(compact_paths),
                "path_set_sha256": "0" * 64,
                "content_set_sha256": "0" * 64,
            }
        )
        (root / r002_preflight.CHECKPOINT_REL).write_text(
            r002_preflight.json_text(checkpoint), encoding="utf-8"
        )
        plan = r002_transaction._bind_review_to_preflight(
            r002_preflight.build_canonical_preflight(root),
            r002_transaction._transition_review_binding(
                transition_review_overlay
            ),
            r002_transaction._r009_control_review_binding(
                r009_control_review_overlay
            ),
            r002_transaction._r010_control_review_binding(
                r010_control_review_overlay
            ),
            r002_transaction._r011_control_review_binding(
                r011_control_review_overlay
            ),
            predecessor_review_binding=(
                r002_transaction._predecessor_transition_review_binding(
                    predecessor_review_overlay
                )
            ),
        )
        self._transaction_preflight = copy.deepcopy(plan)
        projection = r002_transaction.project_transaction(
            root,
            preflight=plan,
            staged_outputs=r002_transaction.build_staged_outputs(root, plan),
            source_checkpoint=checkpoint,
            catalog_overlay={
                path: (ROOT / path).read_bytes()
                for path in r002_transaction.CATALOG_RELATIVES
            },
            historical_transition_review_overlay=(
                historical_review_overlay
            ),
            predecessor_transition_review_overlay=(
                predecessor_review_overlay
            ),
            transition_review_overlay=transition_review_overlay,
            r009_control_review_overlay=r009_control_review_overlay,
            r010_control_review_overlay=r010_control_review_overlay,
            r011_control_review_overlay=r011_control_review_overlay,
            r011_control_cohort_paths=(
                completion_review.CONTROL_SUCCESSOR_R011_COHORT_PATHS
            ),
        )
        for relative, raw in projection["output_bytes"].items():
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(raw)
        return root, copy.deepcopy(projection["checkpoint"])

    @staticmethod
    def _reseal_suffix(checkpoint: dict) -> None:
        state = checkpoint["goal_execution"]
        cbu, fp046, npc, parent_ready, fp046_ready = state[
            "transition_history"
        ][71:76]
        cbu["previous_event_sha256"] = state["transition_history"][70][
            "event_sha256"
        ]
        cbu["event_sha256"] = graph.continuation.event_sha256(cbu)
        for event in (fp046, npc):
            event["previous_event_sha256"] = (
                cbu["event_sha256"]
                if event is fp046
                else fp046["event_sha256"]
            )
            event["reopen_trigger"]["canonical_update_event_sha256"] = cbu[
                "event_sha256"
            ]
            event["event_sha256"] = graph.continuation.event_sha256(event)
        parent_ready["previous_event_sha256"] = npc["event_sha256"]
        parent_ready["readiness_basis"]["canonical_update_event_sha256"] = cbu[
            "event_sha256"
        ]
        parent_ready["readiness_basis"]["successor_event_sha256_by_goal"] = {
            graph.R002_REOPEN_SUCCESSORS[0]["goal_id"]: fp046["event_sha256"],
            graph.R002_REOPEN_SUCCESSORS[1]["goal_id"]: npc["event_sha256"],
        }
        parent_ready["event_sha256"] = graph.continuation.event_sha256(
            parent_ready
        )
        fp046_ready["previous_event_sha256"] = parent_ready["event_sha256"]
        fp046_ready["reopened_container_ready_event_sha256"] = parent_ready[
            "event_sha256"
        ]
        fp046_ready["event_sha256"] = graph.continuation.event_sha256(
            fp046_ready
        )
        inventory = parent_ready["dynamic_goal_inventory_after"]
        inventory[graph.R002_REOPEN_SUCCESSORS[0]["goal_id"]][
            "materialized_event_sha256"
        ] = fp046["event_sha256"]
        inventory[graph.R002_REOPEN_SUCCESSORS[1]["goal_id"]][
            "materialized_event_sha256"
        ] = npc["event_sha256"]
        state["dynamic_goal_inventory"] = copy.deepcopy(inventory)
        state["transition_history_anchor_sha256"] = fp046_ready["event_sha256"]

    def test_pre_seq72_current_graph_remains_valid(self) -> None:
        checkpoint = load_json(ROOT / graph.CHECKPOINT_RELATIVE)

        self.assertEqual(
            graph.validate_fp046_npc_r002_reopen_seq72_76(ROOT, checkpoint),
            [],
        )

    def test_seq72_76_projection_missing_actual_r004_review_fails_closed(
        self,
    ) -> None:
        root, checkpoint = self._checkpoint()
        (root / r002_preflight.TRANSITION_R004_INDEPENDENT_REL).unlink()

        errors = graph.validate_fp046_npc_r002_reopen_seq72_76(
            root, checkpoint
        )
        joined = "\n".join(errors)
        self.assertIn(
            "FP046/NPC R002 reviewed authority differs",
            joined,
        )

    def test_direct_review_authority_accepts_exact_twenty_one_file_core(self) -> None:
        root, checkpoint, suffix, frozen_raw = (
            self._review_authority_fixture()
        )

        self.assertEqual(
            self._review_authority_errors(
                root,
                checkpoint,
                suffix,
                frozen_raw,
            ),
            [],
        )

    def test_direct_review_authority_rejects_frozen_r011_integrity_failure(
        self,
    ) -> None:
        for failure in (
            ValueError("frozen R011 binding differs"),
            OSError("frozen R011 bytes unavailable"),
        ):
            with self.subTest(failure=type(failure).__name__):
                root, checkpoint, suffix, frozen_raw = (
                    self._review_authority_fixture()
                )
                errors = self._review_authority_errors(
                    root,
                    checkpoint,
                    suffix,
                    frozen_raw,
                    frozen_failure=failure,
                )
                self.assertIn(str(failure), "\n".join(errors))

    def test_direct_review_authority_rejects_each_missing_review_file(
        self,
    ) -> None:
        review_paths = (
            *(Path(path) for path in r002_preflight.TRANSITION_R001_PATHS),
            *(Path(path) for path in r002_preflight.TRANSITION_R002_PATHS),
            *(Path(path) for path in r002_preflight.TRANSITION_R003_PATHS),
            *(Path(path) for path in r002_preflight.TRANSITION_R004_PATHS),
            *(
                Path(path)
                for path in completion_review.CONTROL_SUCCESSOR_R009_PATHS
            ),
            *(
                Path(path)
                for path in completion_review.CONTROL_SUCCESSOR_R010_PATHS
            ),
            *(
                Path(path)
                for path in completion_review.CONTROL_SUCCESSOR_R011_PATHS
            ),
        )
        for relative in review_paths:
            with self.subTest(relative=relative):
                root, checkpoint, suffix, frozen_raw = (
                    self._review_authority_fixture()
                )
                (root / relative).unlink()

                self.assertTrue(
                    self._review_authority_errors(
                        root,
                        checkpoint,
                        suffix,
                        frozen_raw,
                    )
                )

    def test_direct_review_authority_rejects_tamper_and_event_reseal(
        self,
    ) -> None:
        for relative in (
            Path(r002_preflight.TRANSITION_R001_RESULT_REL),
            Path(r002_preflight.TRANSITION_R002_RESULT_REL),
            Path(r002_preflight.TRANSITION_R003_RESULT_REL),
            Path(r002_preflight.TRANSITION_R004_RESULT_REL),
            Path(completion_review.CONTROL_SUCCESSOR_R009_RESULT_REL),
            Path(completion_review.CONTROL_SUCCESSOR_R010_RESULT_REL),
            Path(completion_review.CONTROL_SUCCESSOR_R011_RESULT_REL),
        ):
            with self.subTest(relative=relative):
                root, checkpoint, suffix, frozen_raw = (
                    self._review_authority_fixture()
                )
                self.assertEqual(
                    self._review_authority_errors(
                        root,
                        checkpoint,
                        suffix,
                        frozen_raw,
                    ),
                    [],
                )
                target = root / relative
                tampered = target.read_bytes() + b" "
                target.write_bytes(tampered)
                for field in (
                    "predecessor_transition_review_binding",
                    "transition_review_binding",
                    "r009_control_review_binding",
                    "r010_control_review_binding",
                    "r011_control_review_binding",
                ):
                    rows = suffix[0].get(field)
                    if not isinstance(rows, dict):
                        continue
                    for row in rows.values():
                        if row.get("path") == relative.as_posix():
                            row["sha256"] = hashlib.sha256(tampered).hexdigest()
                            row["byte_length"] = len(tampered)
                previous_sha256 = "0" * 64
                for event in suffix:
                    event["previous_event_sha256"] = previous_sha256
                    event["event_sha256"] = graph.continuation.event_sha256(
                        event
                    )
                    previous_sha256 = event["event_sha256"]

                self.assertTrue(
                    self._review_authority_errors(
                        root,
                        checkpoint,
                        suffix,
                        frozen_raw,
                    )
                )

    def test_direct_review_authority_rejects_six_binding_and_core_reseal(
        self,
    ) -> None:
        binding_fields = (
            "predecessor_transition_review_binding",
            "transition_review_binding",
            "transition_review_subject_binding",
            "r009_control_review_binding",
            "r010_control_review_binding",
            "r011_control_review_binding",
        )
        for field in (*binding_fields, "core"):
            with self.subTest(field=field):
                root, checkpoint, suffix, frozen_raw = (
                    self._review_authority_fixture()
                )
                self.assertEqual(
                    self._review_authority_errors(
                        root,
                        checkpoint,
                        suffix,
                        frozen_raw,
                    ),
                    [],
                )
                if field == "core":
                    suffix[-1]["status_changes"]["WS-FORGED"] = "READY"
                else:
                    binding = suffix[0][field]
                    if field.endswith("subject_binding"):
                        binding["sha256"] = "0" * 64
                    else:
                        binding["assignment"]["sha256"] = "0" * 64
                previous_sha256 = "0" * 64
                for event in suffix:
                    event["previous_event_sha256"] = previous_sha256
                    event["event_sha256"] = graph.continuation.event_sha256(
                        event
                    )
                    previous_sha256 = event["event_sha256"]

                self.assertTrue(
                    self._review_authority_errors(
                        root,
                        checkpoint,
                        suffix,
                        frozen_raw,
                    )
                )

    def test_direct_review_authority_rejects_superseded_r008_binding(
        self,
    ) -> None:
        root, checkpoint, suffix, frozen_raw = self._review_authority_fixture()
        self.assertEqual(
            self._review_authority_errors(
                root,
                checkpoint,
                suffix,
                frozen_raw,
            ),
            [],
        )
        suffix[0]["r008_control_review_binding"] = {
            "assignment": {"sha256": "0" * 64}
        }
        previous_sha256 = "0" * 64
        for event in suffix:
            event["previous_event_sha256"] = previous_sha256
            event["event_sha256"] = graph.continuation.event_sha256(event)
            previous_sha256 = event["event_sha256"]

        self.assertTrue(
            self._review_authority_errors(
                root,
                checkpoint,
                suffix,
                frozen_raw,
            )
        )

    def test_direct_review_authority_rejects_unknown_review_binding_reseal(
        self,
    ) -> None:
        root, checkpoint, suffix, frozen_raw = self._review_authority_fixture()
        self.assertEqual(
            self._review_authority_errors(
                root,
                checkpoint,
                suffix,
                frozen_raw,
            ),
            [],
        )
        suffix[0]["r012_control_review_binding"] = copy.deepcopy(
            suffix[0]["r011_control_review_binding"]
        )
        previous_sha256 = "0" * 64
        for event in suffix:
            event["previous_event_sha256"] = previous_sha256
            event["event_sha256"] = graph.continuation.event_sha256(event)
            previous_sha256 = event["event_sha256"]

        self.assertTrue(
            self._review_authority_errors(
                root,
                checkpoint,
                suffix,
                frozen_raw,
            )
        )

    def test_transaction_built_seq76_review_plan_uses_checkpoint_bodies(
        self,
    ) -> None:
        root, checkpoint = self._checkpoint()
        state = checkpoint["goal_execution"]
        suffix = state["transition_history"][71:76]

        plan = graph._r002_checkpoint_review_plan(
            root,
            checkpoint,
            suffix,
            r002_preflight,
        )

        self.assertIsNotNone(plan)
        self.assertEqual(
            plan["final_state"]["artifact_work_queue"],
            state["artifact_work_queue"],
        )
        self.assertEqual(
            plan["final_state"]["completion_boundary"],
            state["completion_boundary"],
        )
        self.assertEqual(
            plan["candidate_paths"],
            self._transaction_preflight["candidate_paths"],
        )
        self.assertEqual(
            r002_preflight.transition_plan_core_binding(
                r002_preflight.approval_neutral_plan_core(plan)
            ),
            r002_preflight.transition_plan_core_binding(
                r002_preflight.approval_neutral_plan_core(
                    self._transaction_preflight
                )
            ),
        )

    def test_actual_projected_seq77_then_seq78_replay_reviewed_completion_cascade(
        self,
    ) -> None:
        root, checkpoint = self._checkpoint()
        source_paths = {
            Path(path)
            for path in r002_transaction.catalogs.discover_source_paths(ROOT)
        } | {
            Path(path)
            for path in r002_transaction._control_successor_module().UNMANAGED_EVIDENCE_PATHS
        }
        for relative in source_paths:
            target = root / relative
            source = ROOT / relative
            if not target.exists():
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(source.read_bytes())
            target.chmod(source.stat().st_mode & 0o777)
        product_prefixes = {
            "apps",
            "backend",
            "data_sources",
            "datasets",
            "model",
            "voice",
        }
        product_sha256_before = {
            relative: sha256_file(root / relative)
            for relative in source_paths
            if relative.parts and relative.parts[0] in product_prefixes
        }
        git_dir, _git_state = r002_transaction._git_state(ROOT)
        os.symlink(str(git_dir), root / ".git", target_is_directory=True)
        (root / r002_preflight.CHECKPOINT_REL).chmod(0o600)
        suffix = checkpoint["goal_execution"]["transition_history"][71:76]
        plan = graph._r002_checkpoint_review_plan(
            root,
            checkpoint,
            suffix,
            r002_preflight,
        )
        self.assertIsNotNone(plan)

        self.assertEqual(
            plan["candidate_paths"],
            self._transaction_preflight["candidate_paths"],
        )
        self.assertEqual(
            r002_preflight.transition_plan_core_binding(
                r002_preflight.approval_neutral_plan_core(plan)
            ),
            r002_preflight.transition_plan_core_binding(
                r002_preflight.approval_neutral_plan_core(
                    self._transaction_preflight
                )
            ),
        )
        from scripts import (
            build_walksafe_fp046_r002_seq77_78_review_20260823 as start_review,
        )

        r011_context = start_review.validated_frozen_r011_context(root)
        managed_sources_by_round = (
            start_review.validated_frozen_r011_managed_sources_by_round(root)
        )
        self.assertIsInstance(
            r011_context,
            start_review.FrozenR011Context,
        )
        self.assertIsInstance(
            r011_context.current,
            start_review.FrozenControlContext,
        )
        self.assertEqual(
            r011_context.managed_sources_by_round,
            managed_sources_by_round,
        )
        self.assertEqual(
            tuple(row["path"] for row in r011_context.review_bindings),
            tuple(path.as_posix() for path in start_review.FROZEN_R011_PATHS),
        )
        self.assertEqual(
            {
                round_id: len(rows)
                for round_id, rows in managed_sources_by_round.items()
            },
            {"R002": 2, "R003": 2, "R004": 1},
        )
        if not (root / start_review.AUTHORIZATION_REL).exists():
            start_review.write_authorization(root)
        if not (root / start_review.CONTRACT_REL).exists():
            start_review.write_contract(root)
        review_context = start_review.prepare_review_context(root)
        historical_r001 = start_review.R001_ASSIGNMENT_REL
        self.assertTrue((root / historical_r001).is_file())
        historical_r001_raw = (root / historical_r001).read_bytes()
        assignment_path = root / start_review.ASSIGNMENT_REL
        if assignment_path.exists():
            assignment, assignment_raw = start_review.document(
                root, start_review.ASSIGNMENT_REL
            )
            start_review.validate_assignment(
                assignment,
                assignment_raw,
                review_context,
            )
        else:
            start_review.write_assignment(root)
            assignment, assignment_raw = start_review.document(
                root, start_review.ASSIGNMENT_REL
            )

        result_path = root / start_review.RESULT_REL
        if result_path.exists():
            review_result, result_raw = start_review.document(
                root, start_review.RESULT_REL
            )
            start_review.validate_review_result(
                review_result,
                result_raw,
                assignment,
                assignment_raw,
                review_context,
            )
        else:
            review_result = {
                "schema_version": "1.0",
                "document_id": start_review.RESULT_DOCUMENT_ID,
                "evidence_type": (
                    "FP046_R002_SEQ77_78_REVIEWER_AUTHORED_RESULT"
                ),
                "goal_id": start_review.GOAL_ID,
                "round_id": start_review.ROUND_ID,
                "reviewed_at": assignment["assigned_at"],
                "reviewer": copy.deepcopy(assignment["reviewer"]),
                "assignment_binding": start_review.binding(
                    start_review.ASSIGNMENT_REL,
                    assignment_raw,
                ),
                "review_scope": start_review.review_scope(review_context),
                "decision": "APPROVED",
                "findings": {
                    "blocking": [],
                    "major_open": [],
                    "minor_open": [],
                },
                "finding_dispositions": [],
                "review_boundary": copy.deepcopy(start_review.BOUNDARY),
            }
            result_raw = start_review.json_text(review_result).encode()
            result_path.parent.mkdir(parents=True, exist_ok=True)
            result_path.write_bytes(result_raw)
            result_path.chmod(0o644)
        independent_raw = start_review.build_independent_review(
            review_context,
            assignment,
            assignment_raw,
            review_result,
            result_raw,
        ).encode()
        independent_path = root / start_review.INDEPENDENT_REL
        if independent_path.exists():
            self.assertEqual(independent_path.read_bytes(), independent_raw)
        else:
            independent_path.write_bytes(independent_raw)
            independent_path.chmod(0o644)
        start_review.validate_post_review(root)
        self.assertEqual(
            (root / historical_r001).read_bytes(),
            historical_r001_raw,
        )

        from scripts import (
            apply_walksafe_fp046_r002_goal_start_control_reanchor_seq77_20260823
            as reanchor,
        )
        from scripts import (
            apply_walksafe_fp046_r002_goal_started_seq78_20260823 as started,
        )

        source_managed_paths = {
            Path(path)
            for path in checkpoint["working_tree_snapshot"][
                "managed_changed_paths"
            ]
        }
        reviewed_delta_paths = (
            set(reanchor.REQUIRED_CONTROL_PATHS) - source_managed_paths
        )
        with mock.patch.object(
            reanchor.npc,
            "_git_visible_managed_paths",
            return_value=reviewed_delta_paths,
        ):
            prepared = reanchor.prepare(
                root,
                allow_stale_catalogs=True,
                event_occurred_at=(
                    datetime.fromisoformat(review_result["reviewed_at"])
                    + timedelta(seconds=1)
                ).isoformat(),
            )
        for relative, raw in prepared.candidate_catalogs.items():
            (root / relative).write_bytes(raw)

        seq77 = prepared.projected
        seq77_event = seq77["goal_execution"]["transition_history"][-1]
        self.assertEqual(len(seq77["goal_execution"]["transition_history"]), 77)
        self.assertEqual(
            seq77["goal_execution"]["status_by_goal"][graph.FP046_R002_GOAL_ID],
            "READY",
        )
        self.assertEqual(seq77_event["from_status"], "READY")
        self.assertEqual(seq77_event["to_status"], "READY")
        self.assertEqual(seq77_event["status_changes"], {})
        self.assertEqual(seq77_event["claim_boundary"], reanchor.CLAIM_BOUNDARY)
        self.assertEqual(
            {
                relative: sha256_file(root / relative)
                for relative in product_sha256_before
            },
            product_sha256_before,
        )
        self.assertEqual(
            graph.validate_fp046_r002_seq77_78(root, seq77),
            [],
        )
        repository_snapshot = copy.deepcopy(
            seq77["goal_execution"]["transition_history"][-1][
                "repository_context_reanchor"
            ]["after"]
        )
        receipt = {
            "document_id": started.gate._document_id(started.EVENT_ID),
            "generated_at": "2026-08-23T00:01:00+09:00",
            "repository_snapshot": repository_snapshot,
        }
        receipt_raw = json_bytes(receipt)
        evidence = started.GateEvidence(
            receipt=receipt,
            receipt_bytes=receipt_raw,
            receipt_binding={
                "document_id": started.gate._document_id(started.EVENT_ID),
                "path": (
                    "docs/control/execution/goal-gates/"
                    f"{started.EVENT_ID}/implementation-start-gate-receipt.json"
                ),
                "file_sha256": hashlib.sha256(receipt_raw).hexdigest(),
            },
            repository_payload=repository_snapshot,
            event_occurred_at="2026-08-23T00:01:01+09:00",
        )
        checkpoint, started_event = started.project_seq78(
            root,
            prepared.projected,
            evidence,
            event_id=started.EVENT_ID,
            final_sha256_by_path=prepared.final_sha256_by_path,
        )
        self.assertEqual(started_event["sequence"], 78)
        self.assertEqual(
            started_event["status_changes"],
            {graph.FP046_R002_GOAL_ID: "IN_PROGRESS"},
        )
        self.assertTrue(
            graph._npc_single_admin_recovery_completion_managed_closure_matches(
                root,
                checkpoint,
            )
        )
        self.assertIsNotNone(
            graph._npc_single_admin_recovery_completion_package(
                root,
                checkpoint,
            )
        )
        self.assertIsNotNone(
            graph._npc_single_admin_recovery_sealed_product_successor_artifacts(
                root,
                checkpoint,
            )
        )
        self.assertEqual(
            graph.validate_npc_single_admin_recovery_canonical_completion(
                root,
                checkpoint,
            ),
            [],
        )
        self.assertIsNotNone(
            graph._r002_legacy_completion_overlay(root, checkpoint)
        )
        self.assertEqual(
            graph.validate_fp046_r014_successor_authority(
                root,
                checkpoint,
            )[0],
            [],
        )
        self.assertEqual(
            graph.validate_fp014_canonical_completion(root, checkpoint),
            [],
        )
        self.assertEqual(
            graph.validate_phase1_android_report_successor_binding(
                root,
                checkpoint,
            )[0],
            [],
        )

    def test_transaction_built_seq77_review_plan_rewinds_seq76_bodies(
        self,
    ) -> None:
        root, checkpoint = self._checkpoint()
        suffix = checkpoint["goal_execution"]["transition_history"][71:76]
        expected = graph._r002_checkpoint_review_plan(
            root,
            checkpoint,
            suffix,
            r002_preflight,
        )
        self.assertIsNotNone(expected)
        later = copy.deepcopy(checkpoint)
        state = later["goal_execution"]
        goal_id = graph.R002_REOPEN_SUCCESSORS[0]["goal_id"]
        state["status_by_goal"][goal_id] = "IN_PROGRESS"
        state["artifact_work_queue"] = {"projected_at_sequence": 77}
        state["completion_boundary"] = {"projected_at_sequence": 77}
        state["transition_history"].append(
            {
                "sequence": 77,
                "event_id": "WS-TEST-R002-SEQ77",
                "event_type": "GOAL_STARTED",
                "subject_goal_id": goal_id,
                "from_status": "READY",
                "to_status": "IN_PROGRESS",
                "status_changes": {goal_id: "IN_PROGRESS"},
            }
        )

        actual = graph._r002_checkpoint_review_plan(
            root,
            later,
            suffix,
            r002_preflight,
        )

        self.assertEqual(actual, expected)

    def test_transaction_built_review_plan_requires_runtime_body_hashes(
        self,
    ) -> None:
        root, checkpoint = self._checkpoint()
        for field in (
            "artifact_work_queue_sha256",
            "completion_boundary_sha256",
        ):
            for mutation in ("missing", "mismatched"):
                with self.subTest(field=field, mutation=mutation):
                    projected = copy.deepcopy(checkpoint)
                    suffix = projected["goal_execution"]["transition_history"][
                        71:76
                    ]
                    runtime = suffix[-1]["runtime_after"]
                    if mutation == "missing":
                        runtime.pop(field)
                    else:
                        runtime[field] = "0" * 64

                    self.assertIsNone(
                        graph._r002_checkpoint_review_plan(
                            root,
                            projected,
                            suffix,
                            r002_preflight,
                        )
                    )

    def test_transaction_built_seq76_body_must_match_runtime_hash(self) -> None:
        root, checkpoint = self._checkpoint()
        for field in ("artifact_work_queue", "completion_boundary"):
            with self.subTest(field=field):
                projected = copy.deepcopy(checkpoint)
                state = projected["goal_execution"]
                suffix = state["transition_history"][71:76]
                state[field] = {"forged": True}

                self.assertIsNone(
                    graph._r002_checkpoint_review_plan(
                        root,
                        projected,
                        suffix,
                        r002_preflight,
                    )
                )

    def test_materialization_projection_excludes_later_superseded_successors(
        self,
    ) -> None:
        state = {
            "dynamic_goal_inventory": {
                "WS-EXISTING": {"goal_id": "WS-EXISTING"},
                "WS-FUTURE-R002": {"goal_id": "WS-FUTURE-R002"},
            },
            "materialized_child_goal_ids_by_parent": {
                "WS-PARENT": ["WS-EXISTING", "WS-FUTURE-R002"],
            },
        }
        history = [
            {
                "sequence": 73,
                "event_type": "GOAL_SUPERSEDED",
                "materialized_goal_id": "WS-FUTURE-R002",
            }
        ]
        projection = graph._materialization_projection_at_sequence(
            state,
            history,
            23,
        )

        self.assertIsNotNone(projection)
        inventory, children = projection
        self.assertEqual(
            inventory,
            {"WS-EXISTING": {"goal_id": "WS-EXISTING"}},
        )
        self.assertEqual(
            children,
            {"WS-PARENT": ["WS-EXISTING"]},
        )

    def test_seq72_76_suffix_rewinds_to_exact_seq67_state(self) -> None:
        checkpoint = load_json(ROOT / graph.CHECKPOINT_RELATIVE)
        history = checkpoint["goal_execution"]["transition_history"]

        self.assertEqual(
            [event["sequence"] for event in history[71:76]],
            [72, 73, 74, 75, 76],
        )

        self.assertTrue(
            graph._fp022_seq66_67_successor_matches(ROOT, checkpoint)
        )

    def test_seq75_parent_ready_transition_is_required(self) -> None:
        root, checkpoint = self._checkpoint()
        parent_ready = checkpoint["goal_execution"]["transition_history"][74]
        parent_ready["to_status"] = "PLANNED"
        self._reseal_suffix(checkpoint)

        self.assertIn(
            "FP046/NPC R002 reopen parent readiness differs",
            "\n".join(
                graph.validate_fp046_npc_r002_reopen_seq72_76(
                    root, checkpoint
                )
            ),
        )

    def test_canonical_binding_and_r002_inventory_tamper_fail_closed(self) -> None:
        def forge_backlog_document_id(events: list[dict], _state: dict) -> None:
            events[0]["canonical_binding_snapshot_after"][
                "IMPLEMENTATION_BACKLOG"
            ]["document_id"] = "WS-FORGED-BACKLOG-029"

        def forge_gap_binding(events: list[dict], _state: dict) -> None:
            events[0]["canonical_binding_snapshot_after"]["IMPLEMENTATION_GAP"][
                "file_sha256"
            ] = "0" * 64

        def forge_foreign_binding(events: list[dict], _state: dict) -> None:
            events[0]["canonical_binding_snapshot_after"]["FORGED"] = {
                "role": "FORGED",
                "document_id": "WS-FORGED",
                "path": "forged.json",
                "file_sha256": "0" * 64,
            }

        def forge_inventory_hash(events: list[dict], _state: dict) -> None:
            events[3]["dynamic_goal_inventory_after"][
                graph.R002_REOPEN_SUCCESSORS[0]["goal_id"]
            ]["sha256"] = "0" * 64

        for label, mutate in (
            ("Backlog document ID", forge_backlog_document_id),
            ("Gap binding", forge_gap_binding),
            ("foreign canonical role", forge_foreign_binding),
            ("R002 inventory SHA-256", forge_inventory_hash),
        ):
            with self.subTest(label=label):
                root, checkpoint = self._checkpoint()
                state = checkpoint["goal_execution"]
                mutate(state["transition_history"][71:76], state)
                self._reseal_suffix(checkpoint)

                self.assertTrue(
                    graph.validate_fp046_npc_r002_reopen_seq72_76(
                        root, checkpoint
                    )
                )

    def test_archive_role_and_completion_hash_tamper_fail_closed(self) -> None:
        for label, mutate in (
            (
                "archive",
                lambda events: events[2][
                    "archived_completion_evidence_by_goal_after"
                ].__setitem__(graph.NPC_SINGLE_ADMIN_RECOVERY_GOAL_ID, ["forged"]),
            ),
            (
                "completion hash",
                lambda events: events[1]["reopen_trigger"].__setitem__(
                    "target_completion_event_sha256", "0" * 64
                ),
            ),
        ):
            with self.subTest(label=label):
                root, checkpoint = self._checkpoint()
                mutate(checkpoint["goal_execution"]["transition_history"][71:76])
                self._reseal_suffix(checkpoint)

                errors = graph.validate_fp046_npc_r002_reopen_seq72_76(
                    root, checkpoint
                )

                self.assertTrue(errors)
                self.assertIsNone(
                    graph._r002_legacy_completion_overlay(root, checkpoint)
                )

    def test_supersede_event_and_r002_document_identity_tamper_fail_closed(self) -> None:
        root, checkpoint = self._checkpoint()
        checkpoint["goal_execution"]["transition_history"][73][
            "materialized_goal_id"
        ] = graph.R002_REOPEN_SUCCESSORS[0]["goal_id"]
        self._reseal_suffix(checkpoint)

        self.assertTrue(
            graph.validate_fp046_npc_r002_reopen_seq72_76(root, checkpoint)
        )

        root, checkpoint = self._checkpoint()
        goal_path = root / graph.R002_REOPEN_SUCCESSORS[0]["goal_path"]
        goal_path.write_text(
            goal_path.read_text(encoding="utf-8").replace(
                graph.R002_REOPEN_SUCCESSORS[0]["goal_id"],
                "WS-GOAL-EPIC-03-FP-046-R999",
                1,
            ),
            encoding="utf-8",
        )

        self.assertTrue(
            graph.validate_fp046_npc_r002_reopen_seq72_76(root, checkpoint)
        )

        for field, value in (
            ("materialized_goal_path", "forged/r002.md"),
            ("materialized_goal_content_sha256", "0" * 64),
        ):
            with self.subTest(field=field):
                root, checkpoint = self._checkpoint()
                checkpoint["goal_execution"]["transition_history"][72][
                    field
                ] = value
                self._reseal_suffix(checkpoint)
                self.assertTrue(
                    graph.validate_fp046_npc_r002_reopen_seq72_76(
                        root, checkpoint
                    )
                )

    def test_r029_gap_and_backlog_actual_bytes_fail_with_specific_error(self) -> None:
        for role, label in (
            ("IMPLEMENTATION_GAP", "Gap"),
            ("IMPLEMENTATION_BACKLOG", "Backlog"),
        ):
            with self.subTest(role=role):
                root, checkpoint = self._checkpoint()
                relative = graph.R002_REOPEN_CANONICAL_BINDING_UPDATES[role][
                    "path"
                ]
                (root / relative).write_bytes(b"{}\n")

                self.assertIn(
                    f"FP046/NPC R002 reopen {label} binding differs",
                    "\n".join(
                        graph.validate_fp046_npc_r002_reopen_seq72_76(
                            root, checkpoint
                        )
                    ),
                )

    def test_malformed_source_prefix_returns_error(self) -> None:
        root, checkpoint = self._checkpoint()
        checkpoint["goal_execution"]["transition_history"][0] = None

        errors = graph.validate_fp046_npc_r002_reopen_seq72_76(
            root, checkpoint
        )

        self.assertTrue(errors)

    def test_extra_successor_inventory_and_child_delta_fail_closed(self) -> None:
        def extra_inventory(events: list[dict], state: dict) -> None:
            events[3]["dynamic_goal_inventory_after"]["WS-GOAL-FORGED"] = {}
            state["dynamic_goal_inventory"] = copy.deepcopy(
                events[3]["dynamic_goal_inventory_after"]
            )

        def extra_child(events: list[dict], state: dict) -> None:
            children = events[3][
                "materialized_child_goal_ids_by_parent_after"
            ]
            children[graph.R002_REOPEN_PARENT_GOAL_ID].append(
                "WS-GOAL-FORGED"
            )
            children[graph.R002_REOPEN_PARENT_GOAL_ID].sort()
            state["materialized_child_goal_ids_by_parent"] = copy.deepcopy(
                children
            )

        for label, mutate in (
            ("inventory", extra_inventory),
            ("child", extra_child),
        ):
            with self.subTest(label=label):
                root, checkpoint = self._checkpoint()
                state = checkpoint["goal_execution"]
                events = state["transition_history"][71:76]
                mutate(events, state)
                self._reseal_suffix(checkpoint)

                self.assertTrue(
                    graph.validate_fp046_npc_r002_reopen_seq72_76(
                        root, checkpoint
                    )
                )


class WalkSafeFp046R002StartGraphTest(unittest.TestCase):
    @staticmethod
    def _checkpoint(*, sequence: int = 83) -> tuple[dict, dict]:
        binding = {
            "assignment": {
                "path": "review/assignment.json",
                "sha256": "1" * 64,
                "byte_length": 1,
            },
            "review_result": {
                "path": "review/result.json",
                "sha256": "2" * 64,
                "byte_length": 1,
            },
            "independent_review": {
                "path": "review/independent.json",
                "sha256": "3" * 64,
                "byte_length": 1,
            },
        }
        history = [
            {
                "sequence": index,
                "event_id": f"WS-HISTORICAL-{index}",
                "event_type": "GOAL_READY",
            }
            for index in range(1, 77)
        ]
        history.append(
            {
                "sequence": 77,
                "event_id": graph.FP046_R002_CONTROL_REANCHOR_EVENT_ID,
                "event_type": "GOAL_START_CONTROL_REANCHORED",
                "transition_control_review_binding": copy.deepcopy(
                    graph.continuation.FP046_R002_R006_REVIEW_BINDING
                ),
            }
        )
        if sequence >= 78:
            history.append(
                {
                    "sequence": 78,
                    "event_id": graph.FP046_R002_CONTROL_CORRECTION_EVENT_ID,
                    "event_type": "GOAL_START_CONTROL_REANCHORED",
                    "transition_control_review_binding": copy.deepcopy(binding),
                }
            )
        if sequence >= 79:
            history.append(
                {
                    "sequence": 79,
                    "event_id": graph.FP046_R002_RECOVERY_CONTROL_REANCHOR_EVENT_ID,
                    "event_type": "GOAL_START_CONTROL_REANCHORED",
                    "transition_control_review_binding": copy.deepcopy(
                        graph.continuation.FP046_R002_RECOVERY_APPROVED_R007_REVIEW_BINDING
                    ),
                }
            )
        if sequence >= 80:
            history.append(
                {
                    "sequence": 80,
                    "event_id": (
                        graph.FP046_R002_SECOND_RECOVERY_CONTROL_REANCHOR_EVENT_ID
                    ),
                    "event_type": "GOAL_START_CONTROL_REANCHORED",
                    "transition_control_review_binding": copy.deepcopy(
                        graph.continuation.FP046_R002_RECOVERY_APPROVED_R008_REVIEW_BINDING
                    ),
                }
            )
        if sequence >= 81:
            history.append(
                {
                    "sequence": 81,
                    "event_id": (
                        graph.FP046_R002_THIRD_RECOVERY_CONTROL_REANCHOR_EVENT_ID
                    ),
                    "event_type": "GOAL_START_CONTROL_REANCHORED",
                    "transition_control_review_binding": copy.deepcopy(
                        graph.continuation.FP046_R002_RECOVERY_APPROVED_R009_REVIEW_BINDING
                    ),
                }
            )
        if sequence >= 82:
            history.append(
                {
                    "sequence": 82,
                    "event_id": (
                        graph.FP046_R002_FOURTH_RECOVERY_CONTROL_REANCHOR_EVENT_ID
                    ),
                    "event_type": "GOAL_START_CONTROL_REANCHORED",
                    "transition_control_review_binding": copy.deepcopy(
                        graph.continuation.FP046_R002_RECOVERY_APPROVED_R011_REVIEW_BINDING
                    ),
                }
            )
        if sequence >= 83:
            history.append(
                {
                    "sequence": 83,
                    "event_id": (
                        graph.FP046_R002_FIFTH_RECOVERY_CONTROL_REANCHOR_EVENT_ID
                    ),
                    "event_type": "GOAL_START_CONTROL_REANCHORED",
                    "transition_control_review_binding": copy.deepcopy(
                        graph.continuation.FP046_R002_RECOVERY_APPROVED_R012_REVIEW_BINDING
                    ),
                }
            )
        if sequence >= 84:
            history.append(
                {
                    "sequence": 84,
                    "event_id": (
                        graph.FP046_R002_SIXTH_RECOVERY_CONTROL_REANCHOR_EVENT_ID
                    ),
                    "event_type": "GOAL_START_CONTROL_REANCHORED",
                    "transition_control_review_binding": copy.deepcopy(binding),
                }
            )
        if sequence >= 85:
            history.append(
                {
                    "sequence": 85,
                    "event_id": graph.FP046_R002_STARTED_EVENT_ID,
                    "event_type": "GOAL_STARTED",
                }
            )
        target_managed_count = (
            988
            if sequence == 78
            else graph.continuation.FP046_R002_RECOVERY_SEQ79_MANAGED_PATH_COUNT
            if sequence == 79
            else graph.continuation.FP046_R002_RECOVERY_HISTORICAL_MANAGED_PATH_COUNT
            if sequence == 80
            else graph.continuation.FP046_R002_RECOVERY_SEQ81_MANAGED_PATH_COUNT
            if sequence == 81
            else graph.continuation.FP046_R002_RECOVERY_SEQ82_MANAGED_PATH_COUNT
            if sequence == 82
            else graph.continuation.FP046_R002_RECOVERY_SEQ83_MANAGED_PATH_COUNT
            if sequence == 83
            else graph.continuation.FP046_R002_RECOVERY_MANAGED_PATH_COUNT
        )
        managed = sorted(
            {
                "review/assignment.json",
                "review/result.json",
                "review/independent.json",
                "review/r001-assignment.json",
                "review/r002-assignment.json",
                "review/r003-assignment.json",
                "review/r004-assignment.json",
                "review/r005-assignment.json",
                "review/r005-result.json",
                "review/r005-independent.json",
                "review/rejected-r001-assignment.json",
                graph.continuation.FP046_R002_RECOVERY_REJECTED_R001_ASSIGNMENT[
                    "path"
                ],
                "session/artifact-1",
                "session/artifact-2",
                "session/artifact-3",
                "session/artifact-4",
                "scripts/current-control.py",
                *(
                    (
                        row["path"]
                        for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R012_REVIEW_BINDING.values()
                    )
                    if sequence >= 83
                    else ()
                ),
                *(
                    (
                        graph.continuation.FP046_R002_RECOVERY_REJECTED_R013_ASSIGNMENT[
                            "path"
                        ],
                    )
                    if sequence >= 84
                    else ()
                ),
                *(
                    row["path"]
                    for row in graph.continuation.FP046_R002_R006_REVIEW_BINDING.values()
                ),
                *(
                    row["path"]
                    for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R002_REVIEW_BINDING.values()
                ),
                *(
                    row["path"]
                    for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R003_REVIEW_BINDING.values()
                ),
                *(
                    row["path"]
                    for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R004_REVIEW_BINDING.values()
                ),
                *(
                    row["path"]
                    for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R005_REVIEW_BINDING.values()
                ),
                *(
                    row["path"]
                    for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R006_REVIEW_BINDING.values()
                ),
                *(
                    (
                        row["path"]
                        for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R007_REVIEW_BINDING.values()
                    )
                    if sequence >= 79
                    else ()
                ),
                *(
                    (
                        row["path"]
                        for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R008_REVIEW_BINDING.values()
                    )
                    if sequence >= 80
                    else ()
                ),
                *(
                    (
                        row["path"]
                        for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R009_REVIEW_BINDING.values()
                    )
                    if sequence >= 81
                    else ()
                ),
                *(
                    (
                        graph.continuation.FP046_R002_RECOVERY_REJECTED_R010_ASSIGNMENT[
                            "path"
                        ],
                    )
                    if sequence >= 82
                    else ()
                ),
                *(
                    (
                        row["path"]
                        for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R011_REVIEW_BINDING.values()
                    )
                    if sequence >= 82
                    else ()
                ),
            }
        )
        managed = sorted(
            {
                *managed,
                *(
                    f"managed/filler-{index:04d}"
                    for index in range(
                        target_managed_count - len(managed)
                    )
                ),
            }
        )
        return (
            {
                "goal_execution": {"transition_history": history},
                "working_tree_snapshot": {
                    "managed_changed_paths": managed,
                    "managed_changed_path_count": len(managed),
                },
            },
            binding,
        )

    @staticmethod
    def _authority(binding: dict) -> dict:
        return {
            "binding": copy.deepcopy(binding),
            "review_paths": (
                Path("review/assignment.json"),
                Path("review/result.json"),
                Path("review/independent.json"),
            ),
            "preserved_review_paths": (
                *(
                    Path(row["path"])
                    for row in graph.continuation.FP046_R002_R006_REVIEW_BINDING.values()
                ),
                Path("review/rejected-r001-assignment.json"),
                *(
                    Path(row["path"])
                    for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R002_REVIEW_BINDING.values()
                ),
                *(
                    Path(row["path"])
                    for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R003_REVIEW_BINDING.values()
                ),
                *(
                    Path(row["path"])
                    for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R004_REVIEW_BINDING.values()
                ),
                *(
                    Path(row["path"])
                    for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R005_REVIEW_BINDING.values()
                ),
                *(
                    Path(row["path"])
                    for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R006_REVIEW_BINDING.values()
                ),
                *(
                    Path(row["path"])
                    for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R007_REVIEW_BINDING.values()
                ),
                *(
                    Path(row["path"])
                    for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R008_REVIEW_BINDING.values()
                ),
                *(
                    Path(row["path"])
                    for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R009_REVIEW_BINDING.values()
                ),
                Path(
                    graph.continuation.FP046_R002_RECOVERY_REJECTED_R010_ASSIGNMENT[
                        "path"
                    ]
                ),
                *(
                    Path(row["path"])
                    for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R011_REVIEW_BINDING.values()
                ),
                *(
                    Path(row["path"])
                    for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R012_REVIEW_BINDING.values()
                ),
                Path(
                    graph.continuation.FP046_R002_RECOVERY_REJECTED_R013_ASSIGNMENT[
                        "path"
                    ]
                ),
            ),
            "approved_r005_review_paths": (
                Path("review/r005-assignment.json"),
                Path("review/r005-result.json"),
                Path("review/r005-independent.json"),
            ),
            "approved_r006_review_paths": (
                Path("review/r006-assignment.json"),
                Path("review/r006-result.json"),
                Path("review/r006-independent.json"),
            ),
            "approved_r007_review_paths": tuple(
                Path(row["path"])
                for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R007_REVIEW_BINDING.values()
            ),
            "approved_r008_review_paths": tuple(
                Path(row["path"])
                for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R008_REVIEW_BINDING.values()
            ),
            "approved_r009_review_paths": tuple(
                Path(row["path"])
                for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R009_REVIEW_BINDING.values()
            ),
            "approved_r011_review_bindings": tuple(
                graph.continuation.FP046_R002_RECOVERY_APPROVED_R011_REVIEW_BINDING.values()
            ),
            "approved_r012_review_bindings": tuple(
                graph.continuation.FP046_R002_RECOVERY_APPROVED_R012_REVIEW_BINDING.values()
            ),
            "rejected_r013_assignment_binding": copy.deepcopy(
                graph.continuation.FP046_R002_RECOVERY_REJECTED_R013_ASSIGNMENT
            ),
            "rejected_r010_assignment_binding": copy.deepcopy(
                graph.continuation.FP046_R002_RECOVERY_REJECTED_R010_ASSIGNMENT
            ),
            "session_artifact_paths": (
                Path("session/artifact-1"),
                Path("session/artifact-2"),
                Path("session/artifact-3"),
                Path("session/artifact-4"),
            ),
            "control_code_cohort": (
                {
                    "path": "scripts/current-control.py",
                    "sha256": "4" * 64,
                    "byte_length": 1,
                },
            ),
        }

    def test_current_control_authority_requires_exact_23_31_15_delta(
        self,
    ) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)

        def row(relative: str, raw: bytes) -> dict:
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(raw)
            return {
                "path": relative,
                "sha256": hashlib.sha256(raw).hexdigest(),
                "byte_length": len(raw),
            }

        frozen_rows = []
        current_rows = []
        modified_relatives = sorted(
            graph._FP046_R002_PRE_REVIEW_MODIFIED_CONTROL_PATHS
        )
        for index in range(23):
            relative = (
                modified_relatives[index]
                if index < len(modified_relatives)
                else f"controls/existing-{index:02}.py"
            )
            frozen_raw = f"frozen-{index}\n".encode()
            current_raw = (
                f"current-{index}\n".encode()
                if index < len(modified_relatives)
                else frozen_raw
            )
            frozen_rows.append(
                {
                    "path": relative,
                    "sha256": hashlib.sha256(frozen_raw).hexdigest(),
                    "byte_length": len(frozen_raw),
                }
            )
            current_rows.append(row(relative, current_raw))
        for index in range(8):
            current_rows.append(
                row(f"controls/added-{index:02}.py", f"added-{index}\n".encode())
            )
        approved_r005_relatives = tuple(
            graph._FP046_R002_R005_REVIEW_PINS
        )
        approved_r005_bindings = tuple(
            row(relative.as_posix(), (ROOT / relative).read_bytes())
            for relative in approved_r005_relatives
        )
        session_artifact_relatives = tuple(
            graph._FP046_R002_SESSION_ARTIFACT_PINS
        )
        session_artifact_bindings = tuple(
            row(relative.as_posix(), (ROOT / relative).read_bytes())
            for relative in session_artifact_relatives
        )
        active_review_scope = {
            "approved_r005_review_bindings": list(
                copy.deepcopy(approved_r005_bindings)
            ),
            "approved_r005_supersession_reason_code": (
                graph._FP046_R002_R005_SUPERSESSION_REASON
            ),
            "session_artifact_bindings": list(
                copy.deepcopy(session_artifact_bindings)
            ),
            "session_artifact_path_count": 4,
        }
        review_paths = graph._FP046_R002_R006_REVIEW_PATHS
        review_binding = {}
        for role, relative in zip(
            ("assignment", "review_result", "independent_review"),
            review_paths,
            strict=True,
        ):
            raw = (
                json_bytes({"review_scope": active_review_scope})
                if role == "assignment"
                else role.encode()
            )
            row(relative.as_posix(), raw)
            review_binding[role] = {
                "path": relative.as_posix(),
                "sha256": hashlib.sha256(raw).hexdigest(),
                "byte_length": len(raw),
            }
        superseded_relatives = tuple(
            graph._FP046_R002_PRESERVED_REVIEW_ASSIGNMENT_PINS
        )
        superseded_raws = tuple(
            (ROOT / relative).read_bytes()
            for relative in superseded_relatives
        )
        superseded_bindings = tuple(
            row(relative.as_posix(), raw)
            for relative, raw in zip(
                superseded_relatives, superseded_raws, strict=True
            )
        )

        frozen_context = mock.Mock()
        frozen_context.current.control_code_cohort = tuple(frozen_rows)
        current_context = mock.Mock(
            spec=[
                "current_control_cohort",
                "control_code_successors",
                "added_control_code_bindings",
                "superseded_review_assignments",
                "approved_r005_review_bindings",
                "session_artifact_bindings",
            ]
        )
        current_context.current_control_cohort = tuple(current_rows)
        current_context.control_code_successors = tuple(
            {"path": row["path"]} for row in current_rows[:7]
        )
        current_context.added_control_code_bindings = tuple(
            {"path": row["path"]} for row in current_rows[-8:]
        )
        current_context.approved_r005_review_bindings = (
            approved_r005_bindings
        )
        current_context.session_artifact_bindings = session_artifact_bindings
        current_context.superseded_review_assignments = (
            {
                "round_id": "R001",
                "assignment_binding": superseded_bindings[0],
                "disposition": "SUPERSEDED_WITHOUT_APPROVAL",
                "review_result_status": "NOT_CREATED",
                "independent_review_status": "NOT_CREATED",
                "supersession_reason_code": "R001_BLOCKERS_REQUIRE_R002",
            },
            {
                "round_id": "R002",
                "assignment_binding": superseded_bindings[1],
                "disposition": "SUPERSEDED_WITHOUT_APPROVAL",
                "review_result_status": "NOT_CREATED",
                "independent_review_status": "NOT_CREATED",
                "supersession_reason_code": "R002_BLOCKERS_REQUIRE_R003",
                "confirmed_rejection_findings": [
                    f"R002-CONFIRMED-{index}"
                    for index in range(6)
                ],
            },
            {
                "round_id": "R003",
                "assignment_binding": superseded_bindings[2],
                "disposition": "SUPERSEDED_WITHOUT_APPROVAL",
                "review_result_status": "NOT_CREATED",
                "independent_review_status": "NOT_CREATED",
                "supersession_reason_code": (
                    "R003_REVIEW_INPUT_COHORT_ABA_RETAINED_DESCRIPTOR_REQUIRED"
                ),
                "confirmed_rejection_findings": [
                    "REVIEW_INPUT_COHORT_ABA_CAN_PUBLISH_SELF_INVALID_EVIDENCE"
                ],
            },
            {
                "round_id": "R004",
                "assignment_binding": superseded_bindings[3],
                "disposition": "SUPERSEDED_WITHOUT_APPROVAL",
                "review_result_status": "NOT_CREATED",
                "independent_review_status": "NOT_CREATED",
                "supersession_reason_code": (
                    "R004_POST_PUBLICATION_REGRESSION_NOT_RERUNNABLE"
                ),
            },
        )
        review = mock.Mock(
            spec=[
                "validate_post_review",
                "current_control_cohort",
                "transition_review_binding",
                "validated_frozen_r011_context",
                "REVIEW_DIR",
                "ASSIGNMENT_REL",
                "RESULT_REL",
                "INDEPENDENT_REL",
                "REVIEW_PATHS",
                "ROUND_ID",
                "CURRENT_CONTROL_PATHS",
                "MODIFIED_CONTROL_PATHS",
                "ADDED_CONTROL_PATHS",
                "PRESERVED_REVIEW_PATHS",
                "R001_ASSIGNMENT_REL",
                "R001_ASSIGNMENT_SHA256",
                "R001_ASSIGNMENT_BYTE_LENGTH",
                "R001_RESULT_REL",
                "R001_INDEPENDENT_REL",
                "R001_ROUND_ID",
                "R001_SUPERSESSION_REASON_CODE",
                "R002_ASSIGNMENT_REL",
                "R002_ASSIGNMENT_SHA256",
                "R002_ASSIGNMENT_BYTE_LENGTH",
                "R002_RESULT_REL",
                "R002_INDEPENDENT_REL",
                "R002_ROUND_ID",
                "R002_SUPERSESSION_REASON_CODE",
                "R002_CONFIRMED_REJECTION_FINDINGS",
                "R003_ASSIGNMENT_REL",
                "R003_RESULT_REL",
                "R003_INDEPENDENT_REL",
                "R003_ASSIGNMENT_SHA256",
                "R003_ASSIGNMENT_BYTE_LENGTH",
                "R003_ROUND_ID",
                "R003_SUPERSESSION_REASON_CODE",
                "R003_CONFIRMED_REJECTION_FINDINGS",
                "R004_ASSIGNMENT_REL",
                "R004_RESULT_REL",
                "R004_INDEPENDENT_REL",
                "R004_ASSIGNMENT_SHA256",
                "R004_ASSIGNMENT_BYTE_LENGTH",
                "R004_ROUND_ID",
                "R004_SUPERSESSION_REASON_CODE",
                "R005_ASSIGNMENT_REL",
                "R005_RESULT_REL",
                "R005_INDEPENDENT_REL",
                "R005_REVIEW_PATHS",
                "R005_REVIEW_PINS",
                "R005_SUPERSESSION_REASON_CODE",
                "SESSION_ARTIFACT_PATHS",
                "SESSION_ARTIFACT_PINS",
            ]
        )
        review.validate_post_review.return_value = current_context
        review.transition_review_binding.return_value = review_binding
        review.validated_frozen_r011_context.return_value = frozen_context
        review.REVIEW_DIR = graph._FP046_R002_R006_REVIEW_DIR
        review.ASSIGNMENT_REL = review_paths[0]
        review.RESULT_REL = review_paths[1]
        review.INDEPENDENT_REL = review_paths[2]
        review.REVIEW_PATHS = review_paths
        review.ROUND_ID = graph._FP046_R002_R006_ROUND_ID
        review.CURRENT_CONTROL_PATHS = tuple(
            Path(row["path"]) for row in current_rows
        )
        review.MODIFIED_CONTROL_PATHS = {
            Path(row["path"]) for row in current_rows[:7]
        }
        review.ADDED_CONTROL_PATHS = tuple(
            Path(row["path"]) for row in current_rows[-8:]
        )
        review.PRESERVED_REVIEW_PATHS = superseded_relatives
        review.R001_ASSIGNMENT_REL = superseded_relatives[0]
        review.R001_ASSIGNMENT_SHA256 = superseded_bindings[0]["sha256"]
        review.R001_ASSIGNMENT_BYTE_LENGTH = superseded_bindings[0][
            "byte_length"
        ]
        review.R001_RESULT_REL = superseded_relatives[0].with_name(
            "review-result.json"
        )
        review.R001_INDEPENDENT_REL = superseded_relatives[0].with_name(
            "independent-review.json"
        )
        review.R001_ROUND_ID = "R001"
        review.R001_SUPERSESSION_REASON_CODE = "R001_BLOCKERS_REQUIRE_R002"
        review.R002_ASSIGNMENT_REL = superseded_relatives[1]
        review.R002_ASSIGNMENT_SHA256 = superseded_bindings[1]["sha256"]
        review.R002_ASSIGNMENT_BYTE_LENGTH = superseded_bindings[1][
            "byte_length"
        ]
        review.R002_RESULT_REL = superseded_relatives[1].with_name(
            "review-result.json"
        )
        review.R002_INDEPENDENT_REL = superseded_relatives[1].with_name(
            "independent-review.json"
        )
        review.R002_ROUND_ID = "R002"
        review.R002_SUPERSESSION_REASON_CODE = "R002_BLOCKERS_REQUIRE_R003"
        review.R002_CONFIRMED_REJECTION_FINDINGS = tuple(
            f"R002-CONFIRMED-{index}" for index in range(6)
        )
        review.R003_ASSIGNMENT_REL = superseded_relatives[2]
        review.R003_RESULT_REL = superseded_relatives[2].with_name(
            "review-result.json"
        )
        review.R003_INDEPENDENT_REL = superseded_relatives[2].with_name(
            "independent-review.json"
        )
        review.R003_ASSIGNMENT_SHA256 = superseded_bindings[2]["sha256"]
        review.R003_ASSIGNMENT_BYTE_LENGTH = superseded_bindings[2][
            "byte_length"
        ]
        review.R003_ROUND_ID = "R003"
        review.R003_SUPERSESSION_REASON_CODE = (
            "R003_REVIEW_INPUT_COHORT_ABA_RETAINED_DESCRIPTOR_REQUIRED"
        )
        review.R003_CONFIRMED_REJECTION_FINDINGS = (
            "REVIEW_INPUT_COHORT_ABA_CAN_PUBLISH_SELF_INVALID_EVIDENCE",
        )
        review.R004_ASSIGNMENT_REL = superseded_relatives[3]
        review.R004_RESULT_REL = superseded_relatives[3].with_name(
            "review-result.json"
        )
        review.R004_INDEPENDENT_REL = superseded_relatives[3].with_name(
            "independent-review.json"
        )
        review.R004_ASSIGNMENT_SHA256 = superseded_bindings[3]["sha256"]
        review.R004_ASSIGNMENT_BYTE_LENGTH = superseded_bindings[3][
            "byte_length"
        ]
        review.R004_ROUND_ID = "R004"
        review.R004_SUPERSESSION_REASON_CODE = (
            "R004_POST_PUBLICATION_REGRESSION_NOT_RERUNNABLE"
        )
        (
            review.R005_ASSIGNMENT_REL,
            review.R005_RESULT_REL,
            review.R005_INDEPENDENT_REL,
        ) = approved_r005_relatives
        review.R005_REVIEW_PATHS = approved_r005_relatives
        review.R005_REVIEW_PINS = copy.deepcopy(
            graph._FP046_R002_R005_REVIEW_PINS
        )
        review.R005_SUPERSESSION_REASON_CODE = (
            graph._FP046_R002_R005_SUPERSESSION_REASON
        )
        review.SESSION_ARTIFACT_PATHS = session_artifact_relatives
        review.SESSION_ARTIFACT_PINS = copy.deepcopy(
            graph._FP046_R002_SESSION_ARTIFACT_PINS
        )

        with mock.patch.object(
            graph,
            "_fp046_r002_start_review_module",
            return_value=review,
        ), mock.patch.object(
            graph,
            "_fp046_r002_frozen_r011_authority",
            return_value={"context": frozen_context},
        ):
            authority = graph._fp046_r002_current_control_review_authority(
                root
            )

        self.assertEqual(len(authority["control_code_cohort"]), 31)
        self.assertEqual(len(authority["changed_paths"]), 7)
        self.assertEqual(len(authority["added_paths"]), 8)
        self.assertEqual(
            authority["preserved_review_paths"],
            superseded_relatives,
        )
        self.assertEqual(
            authority["approved_r005_review_paths"],
            approved_r005_relatives,
        )
        self.assertEqual(
            authority["session_artifact_paths"],
            session_artifact_relatives,
        )
        self.assertTrue(
            {path.as_posix() for path in superseded_relatives}.isdisjoint(
                row["path"] for row in authority["binding"].values()
            )
        )
        self.assertTrue(
            authority["added_paths"].isdisjoint(
                row["path"] for row in frozen_rows
            )
        )
        review.current_control_cohort.assert_not_called()

        path_typed = copy.deepcopy(review_binding)
        path_typed["assignment"]["path"] = review_paths[0]
        float_length = copy.deepcopy(review_binding)
        float_length["assignment"]["byte_length"] = float(
            float_length["assignment"]["byte_length"]
        )
        for label, malformed_binding in (
            ("path_type", path_typed),
            ("byte_length_type", float_length),
        ):
            with self.subTest(malformed_active_binding=label):
                review.transition_review_binding.return_value = (
                    malformed_binding
                )
                try:
                    with mock.patch.object(
                        graph,
                        "_fp046_r002_start_review_module",
                        return_value=review,
                    ), self.assertRaisesRegex(
                        ValueError,
                        "current review assignment binding differs",
                    ):
                        graph._fp046_r002_current_control_review_authority(
                            root
                        )
                finally:
                    review.transition_review_binding.return_value = (
                        review_binding
                    )

        with mock.patch.object(
            review,
            "REVIEW_DIR",
            review.REVIEW_DIR.parent / "R007",
        ), mock.patch.object(
            graph,
            "_fp046_r002_start_review_module",
            return_value=review,
        ), self.assertRaisesRegex(
            ValueError,
            "active R006 review constants differ",
        ):
            graph._fp046_r002_current_control_review_authority(root)

        for index, relative in enumerate(superseded_relatives, start=1):
            with self.subTest(tampered_preserved_round=f"R00{index}"):
                preserved_path = root / relative
                preserved_raw = preserved_path.read_bytes()
                preserved_path.write_bytes(
                    f"tampered-r00{index}-assignment".encode()
                )
                try:
                    with mock.patch.object(
                        graph,
                        "_fp046_r002_start_review_module",
                        return_value=review,
                    ), self.assertRaisesRegex(
                        ValueError,
                        f"superseded R00{index} assignment differs",
                    ):
                        graph._fp046_r002_current_control_review_authority(
                            root
                        )
                finally:
                    preserved_path.write_bytes(preserved_raw)

        for index, relative in enumerate(superseded_relatives, start=1):
            for output_name in (
                "review-result.json",
                "independent-review.json",
            ):
                with self.subTest(
                    forbidden_rejected_output=f"R00{index}/{output_name}"
                ):
                    forbidden_output = root / relative.with_name(output_name)
                    forbidden_output.write_bytes(b"must-remain-absent")
                    try:
                        with mock.patch.object(
                            graph,
                            "_fp046_r002_start_review_module",
                            return_value=review,
                        ), self.assertRaisesRegex(
                            ValueError,
                            f"superseded R00{index} assignment differs",
                        ):
                            graph._fp046_r002_current_control_review_authority(
                                root
                            )
                    finally:
                        forbidden_output.unlink()

        for label, binding_rows in (
            ("approved R005 review", approved_r005_bindings),
            ("session artifact", session_artifact_bindings),
        ):
            for binding_row in binding_rows:
                relative = Path(binding_row["path"])
                with self.subTest(
                    tampered_bound_input=f"{label}/{relative}"
                ):
                    bound_path = root / relative
                    bound_raw = bound_path.read_bytes()
                    bound_path.write_bytes(b"tampered-bound-input")
                    try:
                        with mock.patch.object(
                            graph,
                            "_fp046_r002_start_review_module",
                            return_value=review,
                        ), self.assertRaisesRegex(
                            ValueError,
                            f"{label} binding bytes differ",
                        ):
                            graph._fp046_r002_current_control_review_authority(
                                root
                            )
                    finally:
                        bound_path.write_bytes(bound_raw)

        active_assignment_path = root / review_paths[0]
        active_assignment_raw = active_assignment_path.read_bytes()
        for label, scope_key, forged_value in (
            (
                "approved_r005_order",
                "approved_r005_review_bindings",
                list(reversed(approved_r005_bindings)),
            ),
            (
                "approved_r005_reason",
                "approved_r005_supersession_reason_code",
                "R005_FORGED_SUPERSESSION_REASON",
            ),
            (
                "session_artifact_inventory",
                "session_artifact_bindings",
                list(session_artifact_bindings[:-1]),
            ),
        ):
            with self.subTest(forged_active_r006_scope=label):
                assignment_document = json.loads(
                    active_assignment_raw.decode("utf-8")
                )
                assignment_document["review_scope"][scope_key] = forged_value
                forged_raw = json_bytes(assignment_document)
                active_assignment_path.write_bytes(forged_raw)
                forged_binding = copy.deepcopy(review_binding)
                forged_binding["assignment"] = {
                    "path": review_paths[0].as_posix(),
                    "sha256": hashlib.sha256(forged_raw).hexdigest(),
                    "byte_length": len(forged_raw),
                }
                review.transition_review_binding.return_value = forged_binding
                try:
                    with mock.patch.object(
                        graph,
                        "_fp046_r002_start_review_module",
                        return_value=review,
                    ), self.assertRaisesRegex(
                        ValueError,
                        "active R006 review scope differs",
                    ):
                        graph._fp046_r002_current_control_review_authority(
                            root
                        )
                finally:
                    active_assignment_path.write_bytes(active_assignment_raw)
                    review.transition_review_binding.return_value = (
                        review_binding
                    )

        approved_path = root / current_rows[0]["path"]

        def approve_then_drift(_root: Path) -> mock.Mock:
            approved_path.write_bytes(b"post-review-drift\n")
            return current_context

        review.validate_post_review.side_effect = approve_then_drift
        with mock.patch.object(
            graph,
            "_fp046_r002_start_review_module",
            return_value=review,
        ), mock.patch.object(
            graph,
            "_fp046_r002_frozen_r011_authority",
            return_value={"context": frozen_context},
        ), self.assertRaisesRegex(
            ValueError,
            "current control cohort bytes differ",
        ):
            graph._fp046_r002_current_control_review_authority(root)
        review.current_control_cohort.assert_not_called()

    def test_seq76_pre_review_overlay_is_exactly_the_modified_seven(self) -> None:
        review = graph._fp046_r002_start_review_module()
        frozen = graph._fp046_r002_frozen_r011_authority(ROOT)

        rows = graph._fp046_r002_pre_review_modified_control_cohort(
            ROOT,
            frozen,
        )

        self.assertEqual(
            {row["path"] for row in rows},
            {path.as_posix() for path in review.MODIFIED_CONTROL_PATHS},
        )
        self.assertEqual(len(rows), 7)
        self.assertTrue(
            {row["path"] for row in rows}.isdisjoint(
                path.as_posix() for path in review.ADDED_CONTROL_PATHS
            )
        )
        for attribute, value in (
            ("REVIEW_DIR", review.REVIEW_DIR.parent / "R007"),
            ("PRESERVED_REVIEW_PATHS", review.PRESERVED_REVIEW_PATHS[:3]),
            (
                "R004_SUPERSESSION_REASON_CODE",
                "R004_FORGED_SUPERSESSION_REASON",
            ),
            (
                "R004_RESULT_REL",
                Path("review-rounds/R004/relocated-result.json"),
            ),
            (
                "R005_SUPERSESSION_REASON_CODE",
                "R005_FORGED_SUPERSESSION_REASON",
            ),
            (
                "SESSION_ARTIFACT_PATHS",
                review.SESSION_ARTIFACT_PATHS[:3],
            ),
        ):
            with self.subTest(misaligned_constant=attribute), mock.patch.object(
                review,
                attribute,
                value,
            ), self.assertRaisesRegex(
                ValueError,
                "active R006 review constants differ",
            ):
                graph._fp046_r002_pre_review_control_cohort_capability()
        with mock.patch.object(
            review,
            "MODIFIED_CONTROL_PATHS",
            {
                *review.MODIFIED_CONTROL_PATHS,
                Path("backend/app/main.py"),
            },
        ), self.assertRaisesRegex(
            ValueError,
            "modified control path set differs",
        ):
            graph._fp046_r002_pre_review_modified_control_cohort(
                ROOT,
                frozen,
            )

        capability_failure = (
            graph._Fp046R002StartReviewCapabilityUnavailable(
                "current cohort loader failed"
            )
        )
        with self.assertRaisesRegex(
            graph._Fp046R002StartReviewCapabilityUnavailable,
            "current cohort loader failed",
        ):
            graph._fp046_r002_pre_review_modified_control_cohort(
                ROOT,
                frozen,
                capability=(
                    review,
                    mock.Mock(side_effect=capability_failure),
                ),
            )

    def test_frozen_r011_integrity_errors_are_not_legacy_fallbacks(self) -> None:
        for failure in (
            ValueError("frozen R011 review binding differs"),
            OSError("frozen R011 bytes unavailable"),
        ):
            with self.subTest(failure=type(failure).__name__), mock.patch.object(
                graph,
                "_fp046_r002_frozen_r011_authority",
                side_effect=failure,
            ):
                with self.assertRaises(type(failure)):
                    graph._fp046_r002_optional_frozen_r011_authority(ROOT)

        with mock.patch.object(
            graph,
            "_fp046_r002_frozen_r011_capability",
            side_effect=graph._Fp046R002StartReviewCapabilityUnavailable(
                "legacy checkout"
            ),
        ):
            self.assertIsNone(
                graph._fp046_r002_optional_frozen_r011_authority(ROOT)
            )

        def broken_loader(_root: Path) -> None:
            raise graph._Fp046R002StartReviewCapabilityUnavailable(
                "loader integrity failure"
            )

        with mock.patch.object(
            graph,
            "_fp046_r002_frozen_r011_capability",
            return_value=(
                graph._fp046_r002_start_review_module(),
                broken_loader,
                mock.Mock(),
            ),
        ), self.assertRaisesRegex(
            graph._Fp046R002StartReviewCapabilityUnavailable,
            "loader integrity failure",
        ):
            graph._fp046_r002_optional_frozen_r011_authority(ROOT)

        import_failure = ModuleNotFoundError(
            "review module body failed",
            name=graph._FP046_R002_START_REVIEW_MODULE,
        )
        with mock.patch.object(
            graph.importlib,
            "import_module",
            side_effect=import_failure,
        ), mock.patch.object(
            graph.importlib.util,
            "find_spec",
            return_value=mock.Mock(),
        ), self.assertRaisesRegex(
            ModuleNotFoundError,
            "review module body failed",
        ):
            graph._fp046_r002_start_review_module()

    def test_actual_seq77_review_replays_the_physical_r011_lineage(
        self,
    ) -> None:
        from scripts import (
            build_walksafe_fp046_r002_seq77_78_review_20260823 as review,
        )

        context = review.prepare_review_context(
            ROOT,
            require_exact_source=False,
        )
        frozen_r011 = context.frozen_r011_context
        managed_sources_by_round = (
            review.validated_frozen_r011_managed_sources_by_round(ROOT)
        )
        self.assertIsInstance(
            frozen_r011,
            review.FrozenR011Context,
        )
        self.assertIsInstance(
            frozen_r011.current,
            review.FrozenControlContext,
        )
        self.assertEqual(
            frozen_r011.managed_sources_by_round,
            managed_sources_by_round,
        )
        self.assertEqual(
            frozen_r011.current.control_code_cohort,
            context.frozen_r011_cohort,
        )
        self.assertEqual(
            (
                len(context.frozen_r011_cohort),
                len(context.current_control_cohort),
                len(context.control_code_successors),
                len(context.added_control_code_bindings),
            ),
            (23, 31, 7, 8),
        )
        self.assertEqual(
            {row["path"] for row in context.control_code_successors},
            {path.as_posix() for path in review.MODIFIED_CONTROL_PATHS},
        )
        self.assertEqual(
            tuple(row["path"] for row in context.added_control_code_bindings),
            tuple(path.as_posix() for path in review.ADDED_CONTROL_PATHS),
        )

    def test_goal_consumer_configures_live_r014_with_rejected_r013_predecessor(
        self,
    ) -> None:
        review = graph._fp046_r002_recovery_control_modules()[0]
        expected_preserved = (
            *(
                Path(row["path"])
                for row in graph.continuation.FP046_R002_R006_REVIEW_BINDING.values()
            ),
            Path(
                graph.continuation.FP046_R002_RECOVERY_REJECTED_R001_ASSIGNMENT[
                    "path"
                ]
            ),
            *(
                Path(row["path"])
                for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R002_REVIEW_BINDING.values()
            ),
            *(
                Path(row["path"])
                for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R003_REVIEW_BINDING.values()
            ),
            *(
                Path(row["path"])
                for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R004_REVIEW_BINDING.values()
            ),
            *(
                Path(row["path"])
                for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R005_REVIEW_BINDING.values()
            ),
            *(
                Path(row["path"])
                for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R006_REVIEW_BINDING.values()
            ),
            *(
                Path(row["path"])
                for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R007_REVIEW_BINDING.values()
            ),
            *(
                Path(row["path"])
                for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R008_REVIEW_BINDING.values()
            ),
            *(
                Path(row["path"])
                for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R009_REVIEW_BINDING.values()
            ),
            Path(
                graph.continuation.FP046_R002_RECOVERY_REJECTED_R010_ASSIGNMENT[
                    "path"
                ]
            ),
            *(
                Path(row["path"])
                for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R011_REVIEW_BINDING.values()
            ),
            *(
                Path(row["path"])
                for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R012_REVIEW_BINDING.values()
            ),
            Path(
                graph.continuation.FP046_R002_RECOVERY_REJECTED_R013_ASSIGNMENT[
                    "path"
                ]
            ),
        )

        self.assertEqual(
            review.REVIEW_DIR,
            graph.continuation.FP046_R002_RECOVERY_REVIEW_DIR,
        )
        self.assertEqual(
            tuple(review.REVIEW_PATHS),
            graph.continuation.FP046_R002_RECOVERY_REVIEW_PATHS,
        )
        self.assertEqual(tuple(review.PRESERVED_REVIEW_PATHS), expected_preserved)
        self.assertEqual(
            review.REJECTED_R001_ASSIGNMENT_REL,
            expected_preserved[3],
        )
        self.assertEqual(
            review.REJECTED_R001_ASSIGNMENT_SHA256,
            graph.continuation.FP046_R002_RECOVERY_REJECTED_R001_ASSIGNMENT[
                "sha256"
            ],
        )
        self.assertEqual(
            review.REJECTED_R001_ASSIGNMENT_BYTE_LENGTH,
            graph.continuation.FP046_R002_RECOVERY_REJECTED_R001_ASSIGNMENT[
                "byte_length"
            ],
        )
        self.assertFalse((ROOT / review.REJECTED_R001_RESULT_REL).exists())
        self.assertFalse((ROOT / review.REJECTED_R001_INDEPENDENT_REL).exists())
        self.assertEqual(
            tuple(review.APPROVED_R002_REVIEW_PATHS),
            tuple(
                Path(row["path"])
                for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R002_REVIEW_BINDING.values()
            ),
        )
        self.assertEqual(
            review.APPROVED_R002_REVIEW_PINS,
            {
                Path(row["path"]): (row["sha256"], row["byte_length"])
                for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R002_REVIEW_BINDING.values()
            },
        )
        self.assertEqual(
            tuple(review.APPROVED_R003_REVIEW_PATHS),
            tuple(
                Path(row["path"])
                for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R003_REVIEW_BINDING.values()
            ),
        )
        self.assertEqual(
            review.APPROVED_R003_REVIEW_PINS,
            {
                Path(row["path"]): (row["sha256"], row["byte_length"])
                for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R003_REVIEW_BINDING.values()
            },
        )
        self.assertEqual(
            tuple(review.APPROVED_R004_REVIEW_PATHS),
            tuple(
                Path(row["path"])
                for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R004_REVIEW_BINDING.values()
            ),
        )
        self.assertEqual(
            tuple(review.APPROVED_R005_REVIEW_PATHS),
            tuple(
                Path(row["path"])
                for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R005_REVIEW_BINDING.values()
            ),
        )
        self.assertEqual(
            tuple(review.APPROVED_R006_REVIEW_PATHS),
            tuple(
                Path(row["path"])
                for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R006_REVIEW_BINDING.values()
            ),
        )
        self.assertEqual(
            tuple(review.APPROVED_R007_REVIEW_PATHS),
            tuple(
                Path(row["path"])
                for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R007_REVIEW_BINDING.values()
            ),
        )
        self.assertEqual(
            tuple(review.APPROVED_R008_REVIEW_PATHS),
            tuple(
                Path(row["path"])
                for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R008_REVIEW_BINDING.values()
            ),
        )
        self.assertEqual(
            review.APPROVED_R008_REVIEW_PINS,
            {
                Path(row["path"]): (row["sha256"], row["byte_length"])
                for row in graph.continuation.FP046_R002_RECOVERY_APPROVED_R008_REVIEW_BINDING.values()
            },
        )
        self.assertEqual(
            review.approved_r008_review_bindings(ROOT),
            tuple(
                graph.continuation.FP046_R002_RECOVERY_APPROVED_R008_REVIEW_BINDING.values()
            ),
        )
        self.assertEqual(
            review.approved_r009_review_bindings(ROOT),
            tuple(
                graph.continuation.FP046_R002_RECOVERY_APPROVED_R009_REVIEW_BINDING.values()
            ),
        )
        self.assertEqual(
            review.rejected_r010_assignment_binding(ROOT),
            graph.continuation.FP046_R002_RECOVERY_REJECTED_R010_ASSIGNMENT,
        )
        self.assertEqual(
            review.approved_r011_review_bindings(ROOT),
            tuple(
                graph.continuation.FP046_R002_RECOVERY_APPROVED_R011_REVIEW_BINDING.values()
            ),
        )
        self.assertEqual(
            review.approved_r012_review_bindings(ROOT),
            tuple(
                graph.continuation.FP046_R002_RECOVERY_APPROVED_R012_REVIEW_BINDING.values()
            ),
        )
        self.assertEqual(
            review.rejected_r013_assignment_binding(ROOT),
            graph.continuation.FP046_R002_RECOVERY_REJECTED_R013_ASSIGNMENT,
        )

    def test_r014_authority_requires_the_exact_rejected_r013_loader(self) -> None:
        review = graph._fp046_r002_recovery_control_modules()[0]
        binding = self._checkpoint()[1]
        context = mock.Mock(current_control_cohort=())
        patched_loaders = {
            "transition_review_binding": mock.Mock(return_value=binding),
            "validated_reviewed_at": mock.Mock(
                return_value=datetime.fromisoformat("2026-08-25T00:00:00+09:00")
            ),
            "validate_post_review": mock.Mock(return_value=context),
            "rejected_r001_assignment_binding": mock.Mock(
                return_value=graph.continuation.FP046_R002_RECOVERY_REJECTED_R001_ASSIGNMENT
            ),
            "rejected_r010_assignment_binding": mock.Mock(
                return_value=graph.continuation.FP046_R002_RECOVERY_REJECTED_R010_ASSIGNMENT
            ),
            "approved_r011_review_bindings": mock.Mock(
                return_value=tuple(
                    graph.continuation.FP046_R002_RECOVERY_APPROVED_R011_REVIEW_BINDING.values()
                )
            ),
            "approved_r012_review_bindings": mock.Mock(
                return_value=tuple(
                    graph.continuation.FP046_R002_RECOVERY_APPROVED_R012_REVIEW_BINDING.values()
                )
            ),
            "rejected_r013_assignment_binding": mock.Mock(
                return_value=graph.continuation.FP046_R002_RECOVERY_REJECTED_R013_ASSIGNMENT
            ),
        }
        for round_id in range(2, 10):
            constant = getattr(
                graph.continuation,
                f"FP046_R002_RECOVERY_APPROVED_R{round_id:03d}_REVIEW_BINDING",
            )
            patched_loaders[f"approved_r{round_id:03d}_review_bindings"] = (
                mock.Mock(return_value=tuple(constant.values()))
            )
        with mock.patch.object(
            graph,
            "_fp046_r002_recovery_control_modules",
            return_value=(review, mock.Mock(), mock.Mock()),
        ), mock.patch.multiple(review, **patched_loaders):
            authority = graph._fp046_r002_recovery_review_authority(ROOT)
        self.assertEqual(
            authority["approved_r009_review_bindings"],
            tuple(
                graph.continuation.FP046_R002_RECOVERY_APPROVED_R009_REVIEW_BINDING.values()
            ),
        )
        self.assertEqual(
            authority["rejected_r010_assignment_binding"],
            graph.continuation.FP046_R002_RECOVERY_REJECTED_R010_ASSIGNMENT,
        )
        self.assertEqual(
            authority["approved_r011_review_bindings"],
            tuple(
                graph.continuation.FP046_R002_RECOVERY_APPROVED_R011_REVIEW_BINDING.values()
            ),
        )
        self.assertEqual(
            authority["approved_r012_review_bindings"],
            tuple(
                graph.continuation.FP046_R002_RECOVERY_APPROVED_R012_REVIEW_BINDING.values()
            ),
        )
        self.assertEqual(
            authority["rejected_r013_assignment_binding"],
            graph.continuation.FP046_R002_RECOVERY_REJECTED_R013_ASSIGNMENT,
        )
        for loader_name in (
            "transition_review_binding",
            "validated_reviewed_at",
            "validate_post_review",
        ):
            patched_loaders[loader_name].assert_called_once_with(
                ROOT, require_live_snapshot=False
            )

        with mock.patch.object(
            graph,
            "_fp046_r002_recovery_control_modules",
            return_value=(review, mock.Mock(), mock.Mock()),
        ), mock.patch.object(
            review,
            "rejected_r013_assignment_binding",
            None,
        ), self.assertRaisesRegex(ValueError, "capability is unavailable"):
            graph._fp046_r002_recovery_review_authority(ROOT)

    def test_seq83_prefix_uses_exact_r012_without_active_r013(self) -> None:
        checkpoint, _binding = self._checkpoint()
        self.assertEqual(
            graph.FP046_R002_BURNED_STARTED_EVENT_IDS,
            frozenset(
                {
                    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-001",
                    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-002",
                    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-003",
                    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-004",
                }
            ),
        )
        correction = mock.Mock()
        correction.validate_seq78_history_suffix.return_value = checkpoint[
            "goal_execution"
        ]["transition_history"][77]
        correction.validate_seq79_history_suffix.return_value = checkpoint[
            "goal_execution"
        ]["transition_history"][78]
        correction.validate_seq80_history_suffix.return_value = checkpoint[
            "goal_execution"
        ]["transition_history"][79]
        correction.validate_seq81_history_suffix.return_value = checkpoint[
            "goal_execution"
        ]["transition_history"][80]
        correction.validate_seq82_history_suffix.return_value = checkpoint[
            "goal_execution"
        ]["transition_history"][81]
        correction.validate_seq83_history_suffix.return_value = checkpoint[
            "goal_execution"
        ]["transition_history"][82]
        correction.strict_json_equal.side_effect = lambda actual, expected: (
            actual == expected
        )
        started = mock.Mock()
        started.validate_history_suffix.return_value = []
        with mock.patch.object(
            graph,
            "_fp046_r002_recovery_control_modules",
            return_value=(mock.Mock(), correction, started),
        ), mock.patch.object(
            graph,
            "_fp046_r002_recovery_review_authority",
            side_effect=AssertionError("seq83 loaded active R014"),
        ), mock.patch.object(
            graph.continuation,
            "validate_fp046_r002_seq77_78_boundary",
            return_value=[],
        ):
            self.assertEqual(
                graph.validate_fp046_r002_seq77_78(ROOT, checkpoint),
                [],
            )
        correction.validate_seq83_history_suffix.assert_called_once_with(
            ROOT,
            checkpoint,
            require_live_snapshot=False,
        )
        correction.validate_history_suffix.assert_not_called()
        correction.validate_seq79_history_suffix.assert_called_once_with(
            ROOT,
            checkpoint,
            require_live_snapshot=False,
        )
        correction.validate_seq80_history_suffix.assert_called_once_with(
            ROOT,
            checkpoint,
            require_live_snapshot=False,
        )
        correction.validate_seq81_history_suffix.assert_called_once_with(
            ROOT,
            checkpoint,
            require_live_snapshot=False,
        )
        correction.validate_seq82_history_suffix.assert_called_once_with(
            ROOT,
            checkpoint,
            require_live_snapshot=False,
        )
        started.validate_history_suffix.assert_not_called()

    def test_seq84_uses_active_r013_and_seq85_uses_start_validator(self) -> None:
        checkpoint, binding = self._checkpoint(sequence=85)
        history = checkpoint["goal_execution"]["transition_history"]
        correction = mock.Mock()
        for sequence in range(78, 84):
            validator = getattr(correction, f"validate_seq{sequence}_history_suffix")
            validator.return_value = history[sequence - 1]
        correction.validate_history_suffix.return_value = history[83]
        correction.strict_json_equal.side_effect = lambda actual, expected: (
            actual == expected
        )
        started = mock.Mock()
        started.validate_history_suffix.return_value = []
        authority = self._authority(binding)
        with mock.patch.object(
            graph,
            "_fp046_r002_recovery_control_modules",
            return_value=(mock.Mock(), correction, started),
        ), mock.patch.object(
            graph,
            "_fp046_r002_recovery_review_authority",
            return_value=authority,
        ), mock.patch.object(
            graph.continuation,
            "validate_fp046_r002_seq77_78_boundary",
            return_value=[],
        ):
            self.assertEqual(
                graph.validate_fp046_r002_seq77_78(ROOT, checkpoint),
                [],
            )
        correction.validate_seq83_history_suffix.assert_called_once_with(
            ROOT, checkpoint, require_live_snapshot=False
        )
        correction.validate_history_suffix.assert_called_once_with(
            ROOT, checkpoint, require_live_snapshot=False
        )
        started.validate_history_suffix.assert_called_once_with(ROOT, checkpoint)

    def test_seq78_recovery_requires_exact_988_managed_paths(self) -> None:
        checkpoint, binding = self._checkpoint(sequence=78)
        checkpoint["working_tree_snapshot"]["managed_changed_paths"].pop()
        correction = mock.Mock()
        correction.validate_seq78_history_suffix.return_value = checkpoint[
            "goal_execution"
        ]["transition_history"][77]
        correction.validate_history_suffix.return_value = checkpoint[
            "goal_execution"
        ]["transition_history"][77]
        correction.strict_json_equal.side_effect = lambda actual, expected: (
            actual == expected
        )
        started = mock.Mock()
        with mock.patch.object(
            graph,
            "_fp046_r002_recovery_control_modules",
            return_value=(mock.Mock(), correction, started),
        ), mock.patch.object(
            graph,
            "_fp046_r002_recovery_review_authority",
            return_value=self._authority(binding),
        ), mock.patch.object(
            graph.continuation,
            "validate_fp046_r002_seq77_78_boundary",
            return_value=[],
        ):
            errors = graph.validate_fp046_r002_seq77_78(ROOT, checkpoint)

        self.assertIn("current review managed paths differ", "\n".join(errors))

    def test_seq79_through_seq85_use_exact_managed_path_counts(self) -> None:
        for sequence in (79, 80, 81, 82, 83, 84, 85):
            with self.subTest(sequence=sequence):
                checkpoint, binding = self._checkpoint(sequence=sequence)
                snapshot = checkpoint["working_tree_snapshot"]
                snapshot["managed_changed_paths"].pop()
                snapshot["managed_changed_path_count"] = len(
                    snapshot["managed_changed_paths"]
                )
                correction = mock.Mock()
                correction.validate_seq78_history_suffix.return_value = checkpoint[
                    "goal_execution"
                ]["transition_history"][77]
                correction.validate_seq79_history_suffix.return_value = checkpoint[
                    "goal_execution"
                ]["transition_history"][78]
                if sequence >= 80:
                    correction.validate_seq80_history_suffix.return_value = checkpoint[
                        "goal_execution"
                    ]["transition_history"][79]
                if sequence >= 81:
                    correction.validate_seq81_history_suffix.return_value = checkpoint[
                        "goal_execution"
                    ]["transition_history"][80]
                if sequence >= 82:
                    correction.validate_seq82_history_suffix.return_value = checkpoint[
                        "goal_execution"
                    ]["transition_history"][81]
                if sequence >= 83:
                    correction.validate_seq83_history_suffix.return_value = checkpoint[
                        "goal_execution"
                    ]["transition_history"][82]
                if sequence >= 84:
                    correction.validate_history_suffix.return_value = checkpoint[
                        "goal_execution"
                    ]["transition_history"][83]
                correction.strict_json_equal.side_effect = lambda actual, expected: (
                    actual == expected
                )
                started = mock.Mock()
                started.validate_history_suffix.return_value = []
                with mock.patch.object(
                    graph,
                    "_fp046_r002_recovery_control_modules",
                    return_value=(mock.Mock(), correction, started),
                ), mock.patch.object(
                    graph,
                    "_fp046_r002_recovery_review_authority",
                    return_value=self._authority(binding),
                ), mock.patch.object(
                    graph.continuation,
                    "validate_fp046_r002_seq77_78_boundary",
                    return_value=[],
                ):
                    errors = graph.validate_fp046_r002_seq77_78(
                        ROOT, checkpoint
                    )

                self.assertIn(
                    "current review managed paths differ", "\n".join(errors)
                )

    def test_seq77_review_binding_and_managed_cohort_fail_closed(self) -> None:
        required_path = next(
            iter(graph.continuation.FP046_R002_R006_REVIEW_BINDING.values())
        )["path"]
        for mutation in ("binding", "managed"):
            with self.subTest(mutation=mutation):
                checkpoint, _binding = self._checkpoint(sequence=77)
                if mutation == "binding":
                    checkpoint["goal_execution"]["transition_history"][76][
                        "transition_control_review_binding"
                    ]["assignment"]["sha256"] = "0" * 64
                else:
                    checkpoint["working_tree_snapshot"][
                        "managed_changed_paths"
                    ].remove(required_path)
                with mock.patch.object(
                    graph.continuation,
                    "validate_fp046_r002_seq77_78_boundary",
                    return_value=[],
                ):
                    self.assertTrue(
                        graph.validate_fp046_r002_seq77_78(
                            ROOT, checkpoint
                        )
                    )

    def test_seq80_uses_historical_validator_without_live_r011(self) -> None:
        checkpoint, _binding = self._checkpoint(sequence=80)
        correction = mock.Mock()
        correction.validate_seq78_history_suffix.return_value = checkpoint[
            "goal_execution"
        ]["transition_history"][77]
        correction.validate_seq79_history_suffix.return_value = checkpoint[
            "goal_execution"
        ]["transition_history"][78]
        correction.validate_seq80_history_suffix.return_value = checkpoint[
            "goal_execution"
        ]["transition_history"][79]
        correction.strict_json_equal.side_effect = lambda actual, expected: (
            actual == expected
        )
        started = mock.Mock()
        started.validate_history_suffix.return_value = []
        with mock.patch.object(
            graph,
            "_fp046_r002_recovery_control_modules",
            return_value=(mock.Mock(), correction, started),
        ), mock.patch.object(
            graph,
            "_fp046_r002_recovery_review_authority",
            side_effect=AssertionError("seq80 loaded active R011"),
        ), mock.patch.object(
            graph.continuation,
            "validate_fp046_r002_seq77_78_boundary",
            return_value=[],
        ):
            self.assertEqual(
                graph.validate_fp046_r002_seq77_78(ROOT, checkpoint),
                [],
            )
        correction.validate_seq80_history_suffix.assert_called_once_with(
            ROOT,
            checkpoint,
            require_live_snapshot=False,
        )
        correction.validate_history_suffix.assert_not_called()
        started.validate_history_suffix.assert_not_called()

        correction.validate_seq80_history_suffix.side_effect = ValueError(
            "seq79 source binding differs"
        )
        with mock.patch.object(
            graph,
            "_fp046_r002_recovery_control_modules",
            return_value=(mock.Mock(), correction, started),
        ), mock.patch.object(
            graph,
            "_fp046_r002_recovery_review_authority",
            side_effect=AssertionError("seq80 loaded active R011"),
        ), mock.patch.object(
            graph.continuation,
            "validate_fp046_r002_seq77_78_boundary",
            return_value=[],
        ):
            self.assertIn(
                "seq79 source binding differs",
                "\n".join(
                    graph.validate_fp046_r002_seq77_78(ROOT, checkpoint)
                ),
            )

    def test_historical_seq81_does_not_require_active_r012(self) -> None:
        checkpoint = graph.continuation.load_json(
            ROOT / "docs/control/walksafe-project-continuation-checkpoint.json"
        )
        history = checkpoint["goal_execution"]["transition_history"]
        if len(history) > 81:
            correction = graph._fp046_r002_recovery_control_modules()[1]
            checkpoint = json.loads(
                correction.reconstructed_seq81_checkpoint_bytes(
                    ROOT,
                    history[80],
                )
            )
        with mock.patch.object(
            graph,
            "_fp046_r002_recovery_review_authority",
            side_effect=AssertionError("seq81 loaded active R012"),
        ), mock.patch.object(
            graph.continuation,
            "validate_fp046_r002_seq77_78_boundary",
            return_value=[],
        ):
            self.assertEqual(
                graph.validate_fp046_r002_seq77_79(ROOT, checkpoint),
                [],
            )

    def test_malformed_seq77_78_identity_is_rejected_before_import(self) -> None:
        checkpoint, _binding = self._checkpoint()
        checkpoint["goal_execution"]["transition_history"][76][
            "event_id"
        ] = "WS-FORGED-SEQ77"
        with mock.patch.object(
            graph,
            "_fp046_r002_recovery_control_modules",
            side_effect=AssertionError("malformed suffix reached imports"),
        ):
            self.assertEqual(
                graph.validate_fp046_r002_seq77_78(ROOT, checkpoint),
                [
                    "FP046 R002 seq77/78/79/80/81/82/83/84/85 suffix is incomplete or malformed"
                ],
            )


class Fp048R002RepositoryContextCallCacheTests(unittest.TestCase):
    @staticmethod
    def _checkpoint(marker: str) -> dict:
        tail = {
            "sequence": 98,
            "event_id": f"EVENT-{marker}",
            "event_sha256": marker * 64,
        }
        return {
            "goal_execution": {
                "transition_history": [tail],
                "transition_history_anchor_sha256": marker * 64,
                "goal_status": "READY",
            },
            "working_tree_snapshot": {
                "path_set_sha256": marker * 64,
                "content_set_sha256": marker * 64,
            },
        }

    def test_one_validate_call_caches_success_and_none(self) -> None:
        for result in ({"control.py": "1" * 64}, None):
            with self.subTest(result=result):
                checkpoint = self._checkpoint("1")

                def validate_body(*_args, **_kwargs) -> list[str]:
                    first = graph._fp048_r002_repository_context_live_successors(
                        ROOT,
                        checkpoint,
                    )
                    second = graph._fp048_r002_repository_context_live_successors(
                        ROOT,
                        checkpoint,
                    )
                    self.assertEqual(first, result)
                    self.assertEqual(second, result)
                    return []

                with mock.patch.object(
                    graph,
                    "_validate",
                    side_effect=validate_body,
                ), mock.patch.object(
                    graph,
                    "_compute_fp048_r002_repository_context_live_successors",
                    return_value=result,
                ) as compute:
                    self.assertEqual(graph.validate(ROOT), [])
                compute.assert_called_once_with(ROOT, checkpoint)
                self.assertIsNone(
                    graph._FP048_R002_REPOSITORY_CONTEXT_CALL_CACHE.get()
                )

    def test_separate_validate_calls_recompute(self) -> None:
        checkpoint = self._checkpoint("2")

        def validate_body(*_args, **_kwargs) -> list[str]:
            graph._fp048_r002_repository_context_live_successors(
                ROOT,
                checkpoint,
            )
            graph._fp048_r002_repository_context_live_successors(
                ROOT,
                checkpoint,
            )
            return []

        with mock.patch.object(
            graph,
            "_validate",
            side_effect=validate_body,
        ), mock.patch.object(
            graph,
            "_compute_fp048_r002_repository_context_live_successors",
            return_value={},
        ) as compute:
            self.assertEqual(graph.validate(ROOT), [])
            self.assertEqual(graph.validate(ROOT), [])
        self.assertEqual(compute.call_count, 2)

    def test_call_cache_separates_checkpoint_identity_and_phase_seal(self) -> None:
        first = self._checkpoint("3")
        second = copy.deepcopy(first)

        def validate_body(*_args, **_kwargs) -> list[str]:
            for checkpoint in (first, first, second, second):
                graph._fp048_r002_repository_context_live_successors(
                    ROOT,
                    checkpoint,
                )
            first["goal_execution"]["transition_history"][-1][
                "event_sha256"
            ] = "4" * 64
            graph._fp048_r002_repository_context_live_successors(
                ROOT,
                first,
            )
            return []

        with mock.patch.object(
            graph,
            "_validate",
            side_effect=validate_body,
        ), mock.patch.object(
            graph,
            "_compute_fp048_r002_repository_context_live_successors",
            return_value={},
        ) as compute:
            self.assertEqual(graph.validate(ROOT), [])
        self.assertEqual(compute.call_count, 3)

    def test_nested_validate_restores_outer_call_cache(self) -> None:
        checkpoint = self._checkpoint("5")
        depth = 0

        def validate_body(*_args, **_kwargs) -> list[str]:
            nonlocal depth
            graph._fp048_r002_repository_context_live_successors(
                ROOT,
                checkpoint,
            )
            if depth == 0:
                depth = 1
                try:
                    self.assertEqual(graph.validate(ROOT), [])
                finally:
                    depth = 0
            graph._fp048_r002_repository_context_live_successors(
                ROOT,
                checkpoint,
            )
            return []

        with mock.patch.object(
            graph,
            "_validate",
            side_effect=validate_body,
        ), mock.patch.object(
            graph,
            "_compute_fp048_r002_repository_context_live_successors",
            return_value={},
        ) as compute:
            self.assertEqual(graph.validate(ROOT), [])
        self.assertEqual(compute.call_count, 2)
        self.assertIsNone(
            graph._FP048_R002_REPOSITORY_CONTEXT_CALL_CACHE.get()
        )

    def test_validate_exception_restores_absent_call_cache(self) -> None:
        with mock.patch.object(
            graph,
            "_validate",
            side_effect=RuntimeError("validation aborted"),
        ):
            with self.assertRaisesRegex(RuntimeError, "validation aborted"):
                graph.validate(ROOT)
        self.assertIsNone(
            graph._FP048_R002_REPOSITORY_CONTEXT_CALL_CACHE.get()
        )

    def test_direct_calls_without_validate_context_always_recompute(self) -> None:
        checkpoint = self._checkpoint("6")
        with mock.patch.object(
            graph,
            "_compute_fp048_r002_repository_context_live_successors",
            return_value={},
        ) as compute:
            graph._fp048_r002_repository_context_live_successors(
                ROOT,
                checkpoint,
            )
            graph._fp048_r002_repository_context_live_successors(
                ROOT,
                checkpoint,
            )
        self.assertEqual(compute.call_count, 2)

    def test_validate_enters_and_restores_seq99_source_scope(self) -> None:
        depth = 0

        @contextmanager
        def source_scope():
            nonlocal depth
            depth += 1
            try:
                yield
            finally:
                depth -= 1

        authority = mock.Mock()
        authority.seq98_source_validation_call_scope = source_scope

        def validate_body(*_args, **_kwargs) -> list[str]:
            self.assertEqual(depth, 1)
            return []

        with mock.patch.object(
            graph,
            "_fp048_r002_successor_correction_authority",
            return_value=authority,
        ), mock.patch.object(
            graph,
            "_validate",
            side_effect=validate_body,
        ):
            self.assertEqual(graph.validate(ROOT), [])
        self.assertEqual(depth, 0)

    def test_seq99_source_scope_enter_failure_is_fail_closed(self) -> None:
        @contextmanager
        def broken_source_scope():
            raise RuntimeError("source scope unavailable")
            yield

        authority = mock.Mock()
        authority.seq98_source_validation_call_scope = broken_source_scope

        with mock.patch.object(
            graph,
            "_fp048_r002_successor_correction_authority",
            return_value=authority,
        ), mock.patch.object(graph, "_validate") as validate:
            with self.assertRaisesRegex(RuntimeError, "source scope unavailable"):
                graph.validate(ROOT)
        validate.assert_not_called()
        self.assertIsNone(
            graph._FP048_R002_REPOSITORY_CONTEXT_CALL_CACHE.get()
        )


class Fp048R002Seq91OutcomeCallCacheTests(unittest.TestCase):
    @staticmethod
    def _checkpoint(marker: str) -> dict:
        checkpoint = Fp048R002RepositoryContextCallCacheTests._checkpoint(marker)
        checkpoint["current_work"] = {"marker": marker}
        return checkpoint

    def test_aliases_compute_once_and_return_defensive_lists(self) -> None:
        checkpoint = self._checkpoint("a")
        aliases = (
            graph.validate_fp048_r002_seq91_93,
            graph.validate_fp048_r002_seq91_94,
            graph.validate_fp048_r002_seq91_95,
            graph.validate_fp048_r002_seq91_96,
            graph.validate_fp048_r002_seq91_97,
            graph.validate_fp048_r002_seq91_98,
        )

        def validate_body(*_args, **_kwargs) -> list[str]:
            first = aliases[0](ROOT, checkpoint)
            first.append("mutated")
            for validator in aliases:
                self.assertEqual(validator(ROOT, checkpoint), ["problem"])
            return []

        with mock.patch.object(
            graph,
            "_validate",
            side_effect=validate_body,
        ), mock.patch.object(
            graph,
            "_validate_fp048_r002_seq91_93",
            return_value=["problem"],
        ) as compute:
            self.assertEqual(graph.validate(ROOT), [])
        compute.assert_called_once_with(
            ROOT,
            checkpoint,
            historical_successor_source=False,
        )

    def test_exception_is_cached_but_keyboard_interrupt_is_not(self) -> None:
        checkpoint = self._checkpoint("b")

        def failed_validate(*_args, **_kwargs) -> list[str]:
            for _ in range(2):
                with self.assertRaisesRegex(ValueError, "invalid outcome"):
                    graph.validate_fp048_r002_seq91_93(ROOT, checkpoint)
            return []

        with mock.patch.object(
            graph,
            "_validate",
            side_effect=failed_validate,
        ), mock.patch.object(
            graph,
            "_validate_fp048_r002_seq91_93",
            side_effect=ValueError("invalid outcome"),
        ) as compute:
            self.assertEqual(graph.validate(ROOT), [])
        self.assertEqual(compute.call_count, 1)

        def interrupted_validate(*_args, **_kwargs) -> list[str]:
            for _ in range(2):
                with self.assertRaisesRegex(KeyboardInterrupt, "stopped"):
                    graph.validate_fp048_r002_seq91_93(ROOT, checkpoint)
            return []

        with mock.patch.object(
            graph,
            "_validate",
            side_effect=interrupted_validate,
        ), mock.patch.object(
            graph,
            "_validate_fp048_r002_seq91_93",
            side_effect=KeyboardInterrupt("stopped"),
        ) as compute:
            self.assertEqual(graph.validate(ROOT), [])
        self.assertEqual(compute.call_count, 2)

    def test_key_separates_identity_root_and_full_checkpoint_content(self) -> None:
        first = self._checkpoint("c")
        second = copy.deepcopy(first)

        def validate_body(*_args, **_kwargs) -> list[str]:
            graph.validate_fp048_r002_seq91_93(ROOT, first)
            graph.validate_fp048_r002_seq91_93(ROOT, first)
            graph.validate_fp048_r002_seq91_93(ROOT, second)
            first["current_work"]["marker"] = "changed"
            graph.validate_fp048_r002_seq91_93(ROOT, first)
            graph.validate_fp048_r002_seq91_93(ROOT.parent, first)
            return []

        with mock.patch.object(
            graph,
            "_validate",
            side_effect=validate_body,
        ), mock.patch.object(
            graph,
            "_validate_fp048_r002_seq91_93",
            return_value=[],
        ) as compute:
            self.assertEqual(graph.validate(ROOT), [])
        self.assertEqual(compute.call_count, 4)

    def test_direct_calls_without_validate_context_always_recompute(self) -> None:
        checkpoint = self._checkpoint("d")
        with mock.patch.object(
            graph,
            "_validate_fp048_r002_seq91_93",
            return_value=[],
        ) as compute:
            graph.validate_fp048_r002_seq91_93(ROOT, checkpoint)
            graph.validate_fp048_r002_seq91_93(ROOT, checkpoint)
        self.assertEqual(compute.call_count, 2)

    def test_private_recursive_compute_shares_one_seq97_scope(self) -> None:
        checkpoint = self._checkpoint("scope")
        scope_depth = 0

        @contextmanager
        def scope():
            nonlocal scope_depth
            scope_depth += 1
            try:
                yield
            finally:
                scope_depth -= 1

        authority = mock.Mock()
        authority.seq97_source_validation_call_scope = scope

        def compute(*_args, **_kwargs) -> list[str]:
            self.assertEqual(scope_depth, 1)
            return []

        with mock.patch.object(
            graph,
            "_fp048_r002_r009_execution_correction_authority",
            return_value=authority,
        ), mock.patch.object(
            graph,
            "_validate_fp048_r002_seq91_93",
            side_effect=compute,
        ):
            self.assertEqual(
                graph.validate_fp048_r002_seq91_93(ROOT, checkpoint),
                [],
            )
        self.assertEqual(scope_depth, 0)


class Fp048R002ReviewedNoncreditSuccessorTests(unittest.TestCase):
    @staticmethod
    def _digest(raw: bytes) -> str:
        return hashlib.sha256(raw).hexdigest()

    def _fixture(
        self,
    ) -> tuple[
        tempfile.TemporaryDirectory[str],
        Path,
        dict,
        dict,
        mock.Mock,
    ]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        modified_path = "product/modified.bin"
        added_path = "product/added.bin"
        predecessor_raw = b"reviewed predecessor\n"
        successor_raw = b"reviewed successor\n"
        added_raw = b"reviewed added source\n"
        for relative, raw in (
            (modified_path, successor_raw),
            (added_path, added_raw),
        ):
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(raw)

        review_binding: dict[str, dict] = {}
        for role in ("assignment", "review_result", "independent_review"):
            relative = f"review/{role}.json"
            raw = (role + "\n").encode("utf-8")
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(raw)
            review_binding[role] = {
                "path": relative,
                "sha256": self._digest(raw),
                "byte_length": len(raw),
            }

        edges = {
            "modified": [
                {
                    "path": modified_path,
                    "predecessor": {
                        "path": modified_path,
                        "sha256": self._digest(predecessor_raw),
                        "byte_length": len(predecessor_raw),
                    },
                    "successor": {
                        "path": modified_path,
                        "sha256": self._digest(successor_raw),
                        "byte_length": len(successor_raw),
                    },
                }
            ],
            "added": [
                {
                    "path": added_path,
                    "successor": {
                        "path": added_path,
                        "sha256": self._digest(added_raw),
                        "byte_length": len(added_raw),
                    },
                }
            ],
        }
        history = [{} for _ in range(89)]
        history[88] = {"event_sha256": "a" * 64}
        event = {
            "sequence": graph.FP048_R002_CONTROL_REANCHOR_SEQUENCE,
            "event_id": graph.FP048_R002_CONTROL_REANCHOR_EVENT_ID,
            "event_type": "GOAL_START_CONTROL_REANCHORED",
            "from_status": "READY",
            "to_status": "READY",
            "status_changes": {},
            "previous_event_sha256": history[88]["event_sha256"],
            "claim_boundary": copy.deepcopy(
                graph.FP048_R002_CONTROL_REANCHOR_CLAIM_BOUNDARY
            ),
            "transition_control_review_binding": review_binding,
            "noncredit_successor_edges": copy.deepcopy(edges),
        }
        event["event_sha256"] = graph.continuation.event_sha256(event)
        history.append(event)
        checkpoint = {"goal_execution": {"transition_history": history}}
        module = mock.Mock()
        module.SOURCE_PRODUCT_MEMBERSHIP = {
            modified_path: True,
            added_path: False,
        }
        module.require_control_reanchored_checkpoint.return_value = None
        module.validated_noncredit_successor_edges.return_value = copy.deepcopy(
            edges
        )
        completion_edges = {
            "modified": [
                {
                    "path": f"completion/modified-{index:02d}.bin",
                    "predecessor": {
                        "path": f"completion/modified-{index:02d}.bin",
                        "sha256": f"{index + 1:064x}",
                        "byte_length": index + 1,
                    },
                    "scope": "CONCURRENT_LIVE_MANAGED_NONCREDIT",
                    "successor": {
                        "path": f"completion/modified-{index:02d}.bin",
                        "sha256": f"{index + 101:064x}",
                        "byte_length": index + 101,
                    },
                }
                for index in range(11)
            ],
            "added": [
                {
                    "path": f"completion/added-{index:02d}.bin",
                    "scope": "FP046_R002_DIRECT_INTERNAL_STATIC_ONLY",
                    "successor": {
                        "path": f"completion/added-{index:02d}.bin",
                        "sha256": f"{index + 201:064x}",
                        "byte_length": index + 201,
                    },
                }
                for index in range(4)
            ],
        }
        module.validated_seq85_to_seq87_successor_edges.return_value = (
            completion_edges
        )
        return temporary, root, checkpoint, edges, module

    def test_valid_reviewed_edge_composes_without_promoting_added_source(
        self,
    ) -> None:
        temporary, root, checkpoint, edges, module = self._fixture()
        self.addCleanup(temporary.cleanup)
        relative = edges["modified"][0]["path"]
        origin = "b" * 64
        predecessor = edges["modified"][0]["predecessor"]["sha256"]
        with mock.patch.object(
            graph,
            "_fp048_r002_control_reanchor_module",
            return_value=module,
        ):
            composed = graph._compose_fp048_r002_reviewed_noncredit_successors(
                root,
                checkpoint,
                {relative: (origin, predecessor)},
            )
            self.assertEqual(
                composed,
                {
                    relative: (
                        origin,
                        edges["modified"][0]["successor"]["sha256"],
                    )
                },
            )
            self.assertNotIn(edges["added"][0]["path"], composed)
            self.assertEqual(
                graph.validate_fp048_r002_reviewed_noncredit_successors(
                    root,
                    checkpoint,
                ),
                [],
            )

    def test_seq85_to_seq87_edge_is_composed_before_seq90(self) -> None:
        temporary, root, checkpoint, edges, module = self._fixture()
        self.addCleanup(temporary.cleanup)
        row = edges["modified"][0]
        completion_edges = copy.deepcopy(
            module.validated_seq85_to_seq87_successor_edges.return_value
        )
        completion_predecessor = "d" * 64
        completion_edges["modified"][0] = {
            "path": row["path"],
            "predecessor": {
                "path": row["path"],
                "sha256": completion_predecessor,
                "byte_length": 7,
            },
            "scope": "CONCURRENT_LIVE_MANAGED_NONCREDIT",
            "successor": copy.deepcopy(row["predecessor"]),
        }
        completion_edges["modified"].sort(key=lambda value: value["path"])
        module.validated_seq85_to_seq87_successor_edges.return_value = (
            completion_edges
        )
        with mock.patch.object(
            graph,
            "_fp048_r002_control_reanchor_module",
            return_value=module,
        ):
            self.assertEqual(
                graph._compose_fp048_r002_reviewed_noncredit_successors(
                    root,
                    checkpoint,
                    {row["path"]: ("c" * 64, completion_predecessor)},
                ),
                {
                    row["path"]: (
                        "c" * 64,
                        row["successor"]["sha256"],
                    )
                },
            )

    def test_missing_or_extra_reviewed_inventory_fails_closed(self) -> None:
        for mutation in ("missing", "extra"):
            with self.subTest(mutation=mutation):
                temporary, root, checkpoint, edges, module = self._fixture()
                self.addCleanup(temporary.cleanup)
                forged = copy.deepcopy(edges)
                if mutation == "missing":
                    forged["added"] = []
                else:
                    forged["modified"].append(
                        {
                            "path": "product/unreviewed.bin",
                            "predecessor": {
                                "path": "product/unreviewed.bin",
                                "sha256": "1" * 64,
                                "byte_length": 1,
                            },
                            "successor": {
                                "path": "product/unreviewed.bin",
                                "sha256": "2" * 64,
                                "byte_length": 2,
                            },
                        }
                    )
                    forged["modified"].sort(
                        key=lambda value: value["path"]
                    )
                event = checkpoint["goal_execution"]["transition_history"][-1]
                event["noncredit_successor_edges"] = copy.deepcopy(forged)
                event["event_sha256"] = graph.continuation.event_sha256(event)
                module.validated_noncredit_successor_edges.return_value = forged
                with mock.patch.object(
                    graph,
                    "_fp048_r002_control_reanchor_module",
                    return_value=module,
                ):
                    self.assertTrue(
                        graph.validate_fp048_r002_reviewed_noncredit_successors(
                            root,
                            checkpoint,
                        )
                    )

    def test_malformed_active_work_anchor_fails_closed(self) -> None:
        temporary, root, checkpoint, _edges, module = self._fixture()
        self.addCleanup(temporary.cleanup)
        checkpoint["goal_execution"]["transition_history"].append(
            {"sequence": 91, "event_id": "WS-FORGED-SEQ91"}
        )
        module.require_control_reanchored_checkpoint.side_effect = ValueError(
            "seq91 active-work anchor differs"
        )
        with mock.patch.object(
            graph,
            "_fp048_r002_control_reanchor_module",
            return_value=module,
        ):
            self.assertTrue(
                graph.validate_fp048_r002_reviewed_noncredit_successors(
                    root,
                    checkpoint,
                )
            )

    def test_tampered_successor_and_disconnected_predecessor_fail_closed(
        self,
    ) -> None:
        temporary, root, checkpoint, edges, module = self._fixture()
        self.addCleanup(temporary.cleanup)
        row = edges["modified"][0]
        with mock.patch.object(
            graph,
            "_fp048_r002_control_reanchor_module",
            return_value=module,
        ):
            self.assertIsNone(
                graph._compose_fp048_r002_reviewed_noncredit_successors(
                    root,
                    checkpoint,
                    {row["path"]: ("b" * 64, "c" * 64)},
                )
            )
            (root / row["path"]).write_bytes(b"tampered\n")
            self.assertIsNone(
                graph._compose_fp048_r002_reviewed_noncredit_successors(
                    root,
                    checkpoint,
                    {
                        row["path"]: (
                            "b" * 64,
                            row["predecessor"]["sha256"],
                        )
                    },
                )
            )
            self.assertTrue(
                graph.validate_fp048_r002_reviewed_noncredit_successors(
                    root,
                    checkpoint,
                )
            )

    def test_unreviewed_manifest_projection_fails_closed(self) -> None:
        temporary, root, checkpoint, edges, module = self._fixture()
        self.addCleanup(temporary.cleanup)
        module.validated_noncredit_successor_edges.return_value = {
            "modified": [],
            "added": [],
        }
        with mock.patch.object(
            graph,
            "_fp048_r002_control_reanchor_module",
            return_value=module,
        ):
            self.assertTrue(
                graph.validate_fp048_r002_reviewed_noncredit_successors(
                    root,
                    checkpoint,
                )
            )
            self.assertIsNone(
                graph._compose_fp048_r002_reviewed_noncredit_successors(
                    root,
                    checkpoint,
                    {
                        edges["modified"][0]["path"]: (
                            "b" * 64,
                            edges["modified"][0]["predecessor"]["sha256"],
                        )
                    },
                )
            )

    def test_creditful_reanchor_event_fails_before_composition(self) -> None:
        temporary, root, checkpoint, edges, module = self._fixture()
        self.addCleanup(temporary.cleanup)
        event = checkpoint["goal_execution"]["transition_history"][-1]
        event["claim_boundary"]["product_implementation_credit_delta"] = 1
        event["event_sha256"] = graph.continuation.event_sha256(event)
        with mock.patch.object(
            graph,
            "_fp048_r002_control_reanchor_module",
            return_value=module,
        ):
            self.assertTrue(
                graph.validate_fp048_r002_reviewed_noncredit_successors(
                    root,
                    checkpoint,
                )
            )
            self.assertIsNone(
                graph._compose_fp048_r002_reviewed_noncredit_successors(
                    root,
                    checkpoint,
                    {
                        edges["modified"][0]["path"]: (
                            "b" * 64,
                            edges["modified"][0]["predecessor"]["sha256"],
                        )
                    },
                )
            )
        module.require_control_reanchored_checkpoint.assert_not_called()

    def test_phase1_bridge_keeps_its_sealed_historical_terminal(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        relative, bridge = next(
            iter(graph.PHASE1_ANDROID_REPORT_SUCCESSOR_BRIDGES.items())
        )
        live_raw = b"later reviewed live bytes\n"
        live_path = root / relative
        live_path.parent.mkdir(parents=True, exist_ok=True)
        live_path.write_bytes(live_raw)
        live_sha256 = self._digest(live_raw)
        current_bindings = {relative: live_sha256}

        with mock.patch.object(
            graph,
            "_fp047_successful_start_snapshot_artifacts",
            return_value={relative: bridge["predecessor_sha256"]},
        ), mock.patch.object(
            graph,
            "validate_phase1_android_report_successor_binding",
            return_value=([], current_bindings),
        ):
            self.assertEqual(
                graph._phase1_android_report_successor_bridge(
                    root,
                    {},
                    relative,
                    bridge["predecessor_sha256"],
                    current_bindings=current_bindings,
                ),
                (
                    bridge["predecessor_sha256"],
                    bridge["current_sha256"],
                ),
            )
        self.assertNotEqual(bridge["current_sha256"], live_sha256)

    def test_fp047_overlay_is_decomposed_only_by_exact_reviewed_edge(
        self,
    ) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        relative = "product/routes.ts"
        live_raw = b"reviewed seq90 live bytes\n"
        live_path = root / relative
        live_path.parent.mkdir(parents=True, exist_ok=True)
        live_path.write_bytes(live_raw)
        live_sha256 = self._digest(live_raw)
        fp047_before = "1" * 64
        fp047_after = "2" * 64
        fp046_before = "3" * 64
        fp046_after = "4" * 64
        common = {
            "checkpoint": {},
            "fp011_live_artifacts": {},
            "fp011_transitions": {},
            "fp013_artifacts": {},
            "fp015_artifacts": {},
            "fp014_artifacts": {},
            "fp047_artifacts": {
                relative: (fp047_before, fp047_after)
            },
            "fp048_artifacts": {},
            "fp046_artifacts": {
                relative: (fp046_before, fp046_after)
            },
            "npc_recovery_artifacts": {},
        }

        with mock.patch.object(
            graph,
            "_historical_artifact_lineage_head",
            return_value=fp047_before,
        ), mock.patch.object(
            graph,
            "_fp047_start_snapshot_successor",
            return_value=(fp047_before, live_sha256),
        ), mock.patch.object(
            graph,
            "_fp048_r002_reviewed_noncredit_edge",
            return_value=(True, (fp046_after, live_sha256)),
        ):
            self.assertTrue(
                graph._successor_lineage_reaches_live(
                    root,
                    {},
                    (relative, "0" * 64),
                    **common,
                )
            )

        with mock.patch.object(
            graph,
            "_historical_artifact_lineage_head",
            return_value=fp047_before,
        ), mock.patch.object(
            graph,
            "_fp047_start_snapshot_successor",
            return_value=(fp047_before, live_sha256),
        ), mock.patch.object(
            graph,
            "_fp048_r002_reviewed_noncredit_edge",
            return_value=(True, (fp046_after, "5" * 64)),
        ):
            self.assertFalse(
                graph._successor_lineage_reaches_live(
                    root,
                    {},
                    (relative, "0" * 64),
                    **common,
                )
            )

    def test_seq91_seq92_overlay_replays_sealed_history_without_r005(
        self,
    ) -> None:
        root = ROOT
        goal_ids = (
            graph.FP046_GOAL_ID,
            graph.NPC_SINGLE_ADMIN_RECOVERY_GOAL_ID,
            graph.FP048_ANDROID_REPORT_GOAL_ID,
        )
        for sequence in (91, 92):
            with self.subTest(sequence=sequence):
                checkpoint, correction = (
                    Fp048R002Seq91CorrectionTests._checkpoint()
                )
                if sequence == 92:
                    Fp048R002Seq91CorrectionTests._append_seq92(
                        checkpoint,
                        correction,
                    )
                ready_validator = mock.Mock()
                with mock.patch.object(
                    graph,
                    "_r002_reopen_suffix",
                    return_value=[{}],
                ), mock.patch.object(
                    graph,
                    "validate_fp046_npc_r002_reopen_seq72_76",
                    return_value=[],
                ), mock.patch.object(
                    graph,
                    "_fp048_r002_control_reanchor_module",
                    side_effect=AssertionError(
                        "physical seq90 R005 was reopened"
                    ),
                ), mock.patch(
                    "scripts.apply_walksafe_fp048_r002_goal_seq88_89_20260825."
                    "require_exact_ready_source",
                    ready_validator,
                ):
                    overlay = graph._r002_legacy_completion_overlay(
                        root,
                        checkpoint,
                    )

                self.assertIsNotNone(overlay)
                replayed = ready_validator.call_args.args[1]
                self.assertEqual(
                    len(replayed["goal_execution"]["transition_history"]),
                    89,
                )
                self.assertEqual(
                    replayed["goal_execution"]["status_by_goal"][
                        graph.FP046_R002_NEXT_GOAL_ID
                    ],
                    "READY",
                )
                for goal_id in goal_ids:
                    self.assertEqual(
                        overlay["goal_execution"]["status_by_goal"][goal_id],
                        "COMPLETE_AT_TARGET",
                    )


class Fp048R002Seq91CorrectionTests(unittest.TestCase):
    @staticmethod
    def _exact_seq91_checkpoint() -> dict:
        from scripts import (
            apply_walksafe_fp048_r002_goal_start_branch_semantics_reanchor_seq93_20260826
            as reanchor,
        )
        from scripts import (
            apply_walksafe_fp048_r002_goal_started_seq93_20260826
            as retired_starter,
        )
        from scripts import (
            apply_walksafe_fp048_r002_goal_started_seq94_20260826
            as starter,
        )
        from scripts import (
            apply_walksafe_fp048_r002_start_gate_contract_correction_seq94_20260826
            as r006_correction,
        )
        from scripts import (
            apply_walksafe_fp048_r002_start_gate_contract_correction_seq92_20260826
            as correction,
        )
        from scripts import (
            apply_walksafe_fp048_r002_start_gate_contract_correction_seq95_20260826
            as r007_correction,
        )
        from scripts import (
            apply_walksafe_fp048_r002_start_gate_contract_correction_seq96_20260826
            as r008_correction,
        )

        checkpoint = load_json(ROOT / graph.V24_CHECKPOINT_RELATIVE)
        history = checkpoint["goal_execution"]["transition_history"]
        if (
            len(history) == graph.FP048_R002_R008_STARTED_SEQUENCE
            and history[-1].get("event_id")
            == graph.FP048_R002_R008_STARTED_EVENT_ID
            and history[-1].get("event_type") == "GOAL_STARTED"
        ):
            from scripts import (
                apply_walksafe_fp048_r002_goal_started_seq97_20260826
                as r008_starter,
            )

            checkpoint = r008_starter._restored_seq96_checkpoint(checkpoint)
            history = checkpoint["goal_execution"]["transition_history"]
        if (
            len(history)
            == graph.FP048_R002_R008_CONTRACT_CORRECTION_SEQUENCE
            and history[-1].get("event_id")
            == graph.FP048_R002_R008_CONTRACT_CORRECTION_EVENT_ID
        ):
            checkpoint = r008_correction._restored_seq95_checkpoint(checkpoint)
            history = checkpoint["goal_execution"]["transition_history"]
        if (
            len(history)
            == graph.FP048_R002_R007_CONTRACT_CORRECTION_SEQUENCE
            and history[-1].get("event_id")
            == graph.FP048_R002_R007_CONTRACT_CORRECTION_EVENT_ID
        ):
            checkpoint = r007_correction._restored_seq94_checkpoint(checkpoint)
            history = checkpoint["goal_execution"]["transition_history"]
        if (
            len(history) == graph.FP048_R002_R006_STARTED_SEQUENCE
            and history[-1].get("event_id")
            == graph.FP048_R002_R006_STARTED_EVENT_ID
        ):
            from scripts import (
                apply_walksafe_fp048_r002_goal_started_seq95_20260826
                as r006_starter,
            )

            checkpoint = r006_starter._restored_seq94_checkpoint(checkpoint)
            history = checkpoint["goal_execution"]["transition_history"]
        if (
            len(history)
            == graph.FP048_R002_R006_CONTRACT_CORRECTION_SEQUENCE
            and history[-1].get("event_id")
            == graph.FP048_R002_R006_CONTRACT_CORRECTION_EVENT_ID
        ):
            checkpoint = r006_correction._restored_seq93_checkpoint(checkpoint)
            history = checkpoint["goal_execution"]["transition_history"]
        if (
            len(history) == graph.FP048_R002_R005_STARTED_SEQUENCE
            and history[-1].get("event_id")
            == graph.FP048_R002_R005_STARTED_EVENT_ID
        ):
            checkpoint = starter._restored_seq93_checkpoint(checkpoint)
            history = checkpoint["goal_execution"]["transition_history"]
        if (
            len(history)
            == graph.FP048_R002_BRANCH_SEMANTICS_REANCHOR_SEQUENCE
            and history[-1].get("event_id")
            == graph.FP048_R002_BRANCH_SEMANTICS_REANCHOR_EVENT_ID
        ):
            checkpoint = reanchor._restored_seq92_checkpoint(checkpoint)
            history = checkpoint["goal_execution"]["transition_history"]
        elif (
            len(history) == graph.FP048_R002_R004_STARTED_SEQUENCE
            and history[-1].get("event_id")
            == graph.FP048_R002_R004_STARTED_EVENT_ID
        ):
            checkpoint = retired_starter._restored_seq92_checkpoint(checkpoint)
            history = checkpoint["goal_execution"]["transition_history"]
        if (
            len(history)
            == graph.FP048_R002_START_GATE_CONTRACT_CORRECTION_SEQUENCE
            and history[-1].get("event_id")
            == graph.FP048_R002_START_GATE_CONTRACT_CORRECTION_EVENT_ID
        ):
            checkpoint = correction._restored_seq91_checkpoint(checkpoint)
            history = checkpoint["goal_execution"]["transition_history"]
        if len(history) != graph.FP048_R002_CONTROL_CORRECTION_SEQUENCE:
            raise AssertionError("exact seq91 fixture authority is unavailable")
        return checkpoint

    @classmethod
    def _checkpoint(cls) -> tuple[dict, dict]:
        checkpoint = cls._exact_seq91_checkpoint()
        checkpoint = copy.deepcopy(checkpoint)
        snapshot = checkpoint["working_tree_snapshot"]
        paths = sorted(
            set(snapshot["managed_changed_paths"])
            | set(graph.FP048_R002_STARTED_MANAGED_PATHS)
        )
        path_sha256, content_sha256 = graph.continuation.working_snapshot_hashes(
            ROOT,
            paths,
        )
        snapshot["managed_changed_paths"] = paths
        snapshot["managed_changed_path_count"] = len(paths)
        snapshot["path_set_sha256"] = path_sha256
        snapshot["content_set_sha256"] = content_sha256
        handoff = checkpoint["session_handoff"]
        handoff["changed_files"] = copy.deepcopy(paths)
        mirror = handoff["source_commit_or_snapshot"]
        mirror["file_count"] = len(paths)
        mirror["path_set_sha256"] = path_sha256
        mirror["content_set_sha256"] = content_sha256
        return checkpoint, checkpoint["goal_execution"]["transition_history"][90]

    @staticmethod
    def _append_seq92(checkpoint: dict, correction: dict) -> dict:
        occurred_at = "2026-08-26T05:30:01+09:00"
        state = checkpoint["goal_execution"]
        event = {
            field: None
            for field in graph.continuation.V24_FIRST_START_EVENT_FIELDS
        }
        event.update(
            {
                "sequence": graph.FP048_R002_CORRECTED_STARTED_SEQUENCE,
                "event_id": graph.FP048_R002_CORRECTED_STARTED_EVENT_ID,
                "event_type": "GOAL_STARTED",
                "occurred_on": "2026-08-26",
                "occurred_at": occurred_at,
                "previous_focus_goal_id": graph.FP046_R002_NEXT_GOAL_ID,
                "previous_focus_content_sha256": (
                    graph.FP048_R002_CORRECTED_STARTED_GOAL_SHA256
                ),
                "focus_goal_id": graph.FP046_R002_NEXT_GOAL_ID,
                "focus_goal_content_sha256": (
                    graph.FP048_R002_CORRECTED_STARTED_GOAL_SHA256
                ),
                "subject_goal_id": graph.FP046_R002_NEXT_GOAL_ID,
                "from_status": "READY",
                "to_status": "IN_PROGRESS",
                "static_plan_manifest_sha256": (
                    graph.FP048_R002_CORRECTED_STARTED_MANIFEST_SHA256
                ),
                "status_changes": {
                    graph.FP046_R002_NEXT_GOAL_ID: "IN_PROGRESS"
                },
                "runtime_after": copy.deepcopy(correction["runtime_after"]),
                "repository_snapshot_before": {},
                "implementation_start_gate_binding": {
                    "document_id": graph.FP048_R002_STARTED_GATE_DOCUMENT_ID,
                    "path": graph.FP048_R002_STARTED_GATE_RECEIPT_PATH,
                    "file_sha256": "f" * 64,
                },
                "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
                "blocker_resolution_ids_after": [
                    record["resolution_id"]
                    for record in state["blocker_resolution_history"]
                ],
                "source_checkpoint_version": checkpoint["schema_version"],
                "evidence_refs": [],
                "previous_event_sha256": correction["event_sha256"],
            }
        )
        event["event_sha256"] = graph.continuation.event_sha256(event)
        state["transition_history"].append(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]
        state["validation_cutoff_at"] = occurred_at
        state["status_by_goal"][graph.FP046_R002_NEXT_GOAL_ID] = "IN_PROGRESS"
        state["goal_status"] = "IN_PROGRESS"
        current = checkpoint["current_work"]
        current["work_item_id"] = graph.FP048_R002_WORK_ITEM_ID
        current["status"] = "IN_PROGRESS"
        current["current_focus"] = graph.FP048_R002_STARTED_CURRENT_FOCUS
        current["next_action"] = graph.FP048_R002_WORK_NEXT_ACTION
        current["release_completion_claimed"] = False
        snapshot = checkpoint["working_tree_snapshot"]
        snapshot["scope"] = graph.FP048_R002_STARTED_SCOPE
        paths = snapshot["managed_changed_paths"]
        handoff = checkpoint["session_handoff"]
        handoff["changed_files"] = copy.deepcopy(paths)
        handoff["current_epic"] = graph.FP048_R002_STARTED_HANDOFF_EPIC
        handoff["last_updated_by_work_item"] = graph.FP048_R002_WORK_ITEM_ID
        handoff["last_verification_status"] = (
            graph.FP048_R002_STARTED_VERIFICATION_STATUS
        )
        handoff["next_single_action"] = graph.FP048_R002_CORRECTION_NEXT_ACTION
        mirror = handoff["source_commit_or_snapshot"]
        mirror["file_count"] = len(paths)
        mirror["path_set_sha256"] = snapshot["path_set_sha256"]
        mirror["content_set_sha256"] = snapshot["content_set_sha256"]
        return event

    def test_seq91_is_exact_ready_zero_credit_without_live_review_reads(
        self,
    ) -> None:
        checkpoint, _event = self._checkpoint()
        with mock.patch.object(
            graph,
            "_fp048_r002_control_reanchor_module",
            side_effect=AssertionError("historical review was reopened"),
        ):
            self.assertEqual(
                graph.validate_fp048_r002_seq91_92(ROOT, checkpoint),
                [],
            )
            self.assertEqual(
                graph.validate_fp048_r002_seq91_93(ROOT, checkpoint),
                [],
            )

    def test_seq92_is_the_only_exact_single_goal_start(self) -> None:
        checkpoint, correction = self._checkpoint()
        self._append_seq92(checkpoint, correction)
        self.assertEqual(
            graph.validate_fp048_r002_seq91_92(ROOT, checkpoint),
            [],
        )
        self.assertEqual(
            graph.validate_fp048_r002_seq91_93(ROOT, checkpoint),
            [],
        )

        started = checkpoint["goal_execution"]["transition_history"][91]
        started["status_changes"]["WS-FORGED-GOAL"] = "IN_PROGRESS"
        started["event_sha256"] = graph.continuation.event_sha256(started)
        self.assertTrue(graph.validate_fp048_r002_seq91_92(ROOT, checkpoint))

    def test_legacy_seq92_rejects_unowned_descendant(self) -> None:
        checkpoint, correction = self._checkpoint()
        self._append_seq92(checkpoint, correction)
        checkpoint["goal_execution"]["transition_history"].append(
            {"sequence": 93, "event_id": "WS-FORGED-DESCENDANT"}
        )
        self.assertEqual(
            graph.validate_fp048_r002_seq91_94(ROOT, checkpoint),
            ["FP048 R002 legacy seq92 descendant dispatch differs"],
        )

    def test_seq92_rejects_each_forged_inverse_delta_field(self) -> None:
        mutations = (
            "event_extra",
            "event_focus_content",
            "event_binding",
            "additional_in_progress",
            "goal_status",
            "anchor",
            "cutoff",
            "work_item",
            "work_status",
            "work_focus",
            "work_next_action",
            "work_release",
            "snapshot_scope",
            "snapshot_count",
            "snapshot_path_hash",
            "snapshot_content_hash",
            "handoff_changed_files",
            "handoff_current_epic",
            "handoff_last_updated",
            "handoff_last_verification",
            "handoff_next_action",
            "mirror_count",
            "mirror_path_hash",
            "mirror_content_hash",
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                checkpoint, correction = self._checkpoint()
                event = self._append_seq92(checkpoint, correction)
                state = checkpoint["goal_execution"]
                current = checkpoint["current_work"]
                snapshot = checkpoint["working_tree_snapshot"]
                handoff = checkpoint["session_handoff"]
                mirror = handoff["source_commit_or_snapshot"]
                if mutation == "event_extra":
                    event["forged"] = True
                elif mutation == "event_focus_content":
                    event["focus_goal_content_sha256"] = "0" * 64
                elif mutation == "event_binding":
                    event["implementation_start_gate_binding"]["document_id"] = "FORGED"
                elif mutation == "additional_in_progress":
                    state["status_by_goal"]["WS-FORGED"] = "IN_PROGRESS"
                elif mutation == "goal_status":
                    state["goal_status"] = "READY"
                elif mutation == "anchor":
                    state["transition_history_anchor_sha256"] = "0" * 64
                elif mutation == "cutoff":
                    state["validation_cutoff_at"] = "2026-08-26T05:30:02+09:00"
                elif mutation == "work_item":
                    current["work_item_id"] = "FORGED"
                elif mutation == "work_status":
                    current["status"] = "READY"
                elif mutation == "work_focus":
                    current["current_focus"] = "FORGED"
                elif mutation == "work_next_action":
                    current["next_action"] = "FORGED"
                elif mutation == "work_release":
                    current["release_completion_claimed"] = True
                elif mutation == "snapshot_scope":
                    snapshot["scope"] = "FORGED"
                elif mutation == "snapshot_count":
                    snapshot["managed_changed_path_count"] += 1
                elif mutation == "snapshot_path_hash":
                    snapshot["path_set_sha256"] = "0" * 64
                elif mutation == "snapshot_content_hash":
                    snapshot["content_set_sha256"] = "0" * 64
                elif mutation == "handoff_changed_files":
                    handoff["changed_files"] = list(reversed(handoff["changed_files"]))
                elif mutation == "handoff_current_epic":
                    handoff["current_epic"] = "FORGED"
                elif mutation == "handoff_last_updated":
                    handoff["last_updated_by_work_item"] = "FORGED"
                elif mutation == "handoff_last_verification":
                    handoff["last_verification_status"] = "FORGED"
                elif mutation == "handoff_next_action":
                    handoff["next_single_action"] = "FORGED"
                elif mutation == "mirror_count":
                    mirror["file_count"] += 1
                elif mutation == "mirror_path_hash":
                    mirror["path_set_sha256"] = "0" * 64
                else:
                    mirror["content_set_sha256"] = "0" * 64
                if mutation.startswith("event_"):
                    event["event_sha256"] = graph.continuation.event_sha256(event)
                    state["transition_history_anchor_sha256"] = event[
                        "event_sha256"
                    ]
                self.assertTrue(
                    graph.validate_fp048_r002_seq91_92(ROOT, checkpoint)
                )

    def test_seq91_credit_reason_and_frozen_edge_drift_fail_closed(self) -> None:
        for mutation in ("credit", "reason", "edge"):
            with self.subTest(mutation=mutation):
                checkpoint, event = self._checkpoint()
                if mutation == "credit":
                    event["status_changes"] = {
                        graph.FP046_R002_NEXT_GOAL_ID: "IN_PROGRESS"
                    }
                elif mutation == "reason":
                    event["correction_reason"] = {}
                else:
                    event["noncredit_successor_edges"] = {
                        "modified": [],
                        "added": [],
                    }
                event["event_sha256"] = graph.continuation.event_sha256(event)
                self.assertTrue(
                    graph.validate_fp048_r002_seq91_92(ROOT, checkpoint)
                )

    def test_descendant_uses_frozen_seq90_edges_not_physical_review(self) -> None:
        checkpoint, correction = self._checkpoint()
        self._append_seq92(checkpoint, correction)
        checkpoint["goal_execution"]["transition_history"].append(
            {"sequence": 93, "event_id": "WS-SYNTHETIC-DESCENDANT"}
        )
        edges = checkpoint["goal_execution"]["transition_history"][89][
            "noncredit_successor_edges"
        ]
        membership = {
            row["path"]: kind == "modified"
            for kind in ("modified", "added")
            for row in edges[kind]
        }
        self.assertEqual(len(membership), 35)
        with mock.patch.object(
            graph,
            "_fp048_r002_control_reanchor_module",
            side_effect=AssertionError("mutable seq90 module was imported"),
        ):
            self.assertEqual(
                graph._fp048_r002_noncredit_successor_edges(ROOT, checkpoint),
                edges,
            )

    def test_descendant_uses_frozen_seq87_edges_without_seq90_r005(self) -> None:
        for sequence in (91, 92):
            with self.subTest(sequence=sequence):
                checkpoint, correction = self._checkpoint()
                if sequence == 92:
                    self._append_seq92(checkpoint, correction)
                with mock.patch.object(
                    graph,
                    "_fp048_r002_control_reanchor_module",
                    side_effect=AssertionError(
                        "mutable seq90 module was imported"
                    ),
                ):
                    edges = graph._fp048_r002_seq85_to_seq87_successor_edges(
                        ROOT,
                        checkpoint,
                    )
                self.assertIsNotNone(edges)
                self.assertEqual(
                    (len(edges["modified"]), len(edges["added"])),
                    (11, 4),
                )

    def test_forged_local_seq87_edge_literal_fails_closed(self) -> None:
        forged = list(graph.FP048_R002_SEQ85_TO_SEQ87_MODIFIED_BINDINGS)
        row = list(forged[0])
        row[4] = "f" * 64
        forged[0] = tuple(row)
        with mock.patch.object(
            graph,
            "FP048_R002_SEQ85_TO_SEQ87_MODIFIED_BINDINGS",
            tuple(forged),
        ), self.assertRaisesRegex(ValueError, "frozen edge set differs"):
            graph._fp048_r002_frozen_seq85_to_seq87_successor_edges()


class Fp048R002Seq92R004Seq93Tests(unittest.TestCase):
    @staticmethod
    def _canonical(checkpoint: dict) -> bytes:
        return json.dumps(
            checkpoint,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")

    @staticmethod
    def _sealed(path: str) -> dict:
        return {"path": path, "sha256": "d" * 64, "byte_length": 1}

    @classmethod
    def _append_seq92(cls, checkpoint: dict) -> dict:
        state = checkpoint["goal_execution"]
        seq91 = state["transition_history"][90]
        occurred_at = (
            datetime.fromisoformat(seq91["occurred_at"])
            + timedelta(seconds=1)
        )
        event = copy.deepcopy(seq91)
        event.update(
            {
                "sequence": graph.FP048_R002_START_GATE_CONTRACT_CORRECTION_SEQUENCE,
                "event_id": graph.FP048_R002_START_GATE_CONTRACT_CORRECTION_EVENT_ID,
                "event_type": "GOAL_START_GATE_CONTRACT_CORRECTED",
                "occurred_on": occurred_at.date().isoformat(),
                "occurred_at": occurred_at.isoformat(),
                "from_status": "READY",
                "to_status": "READY",
                "status_changes": {},
                "runtime_after": copy.deepcopy(seq91["runtime_after"]),
                "claim_boundary": copy.deepcopy(
                    graph.FP048_R002_CONTROL_REANCHOR_CLAIM_BOUNDARY
                ),
                "source_checkpoint_binding": {
                    **cls._sealed(graph.V24_CHECKPOINT_RELATIVE.as_posix()),
                    "sequence": graph.FP048_R002_CONTROL_CORRECTION_SEQUENCE,
                    "tail_event_id": graph.FP048_R002_CONTROL_CORRECTION_EVENT_ID,
                    "tail_event_sha256": seq91["event_sha256"],
                },
                "contract_supersession": {
                    "previous_contract_binding": copy.deepcopy(
                        seq91["contract_supersession"][
                            "replacement_contract_binding"
                        ]
                    ),
                    "reason_code": "R003_SUPERSEDED_BY_STAGE_AWARE_R004",
                    "replacement_contract_binding": {
                        "schema_version": "1.3",
                        "document_id": graph.FP048_R002_R004_CONTRACT_DOCUMENT_ID,
                        "path": graph.FP048_R002_R004_CONTRACT_PATH,
                        "file_sha256": "e" * 64,
                        "contract_id": graph.FP048_R002_R004_CONTRACT_ID,
                        "contract_version": graph.FP048_R002_R004_CONTRACT_VERSION,
                        "canonical_contract_sha256": "f" * 64,
                    },
                },
                "start_gate_runner_binding": cls._sealed(
                    graph.FP048_R002_R004_START_GATE_RUNNER_PATH
                ),
                "previous_event_sha256": seq91["event_sha256"],
            }
        )
        event["event_sha256"] = graph.continuation.event_sha256(event)
        state["transition_history"].append(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]
        state["validation_cutoff_at"] = event["occurred_at"]
        return event

    @staticmethod
    def _receipt() -> dict:
        return {
            "document_id": "WS-FP048-R002-R004-GOAL-START-RECEIPT",
            "path": "docs/control/execution/goal-gates/fp048-r002-r004/receipt.json",
            "file_sha256": "a" * 64,
        }

    @classmethod
    def _append_seq93(cls, checkpoint: dict, receipt: dict) -> dict:
        state = checkpoint["goal_execution"]
        correction = state["transition_history"][91]
        occurred_at = "2026-08-26T05:30:03+09:00"
        event = {
            field: None
            for field in graph.continuation.V24_FIRST_START_EVENT_FIELDS
        }
        event.update(
            {
                "sequence": graph.FP048_R002_R004_STARTED_SEQUENCE,
                "event_id": graph.FP048_R002_R004_STARTED_EVENT_ID,
                "event_type": "GOAL_STARTED",
                "occurred_on": "2026-08-26",
                "occurred_at": occurred_at,
                "previous_focus_goal_id": graph.FP046_R002_NEXT_GOAL_ID,
                "previous_focus_content_sha256": "b" * 64,
                "focus_goal_id": graph.FP046_R002_NEXT_GOAL_ID,
                "focus_goal_content_sha256": "b" * 64,
                "subject_goal_id": graph.FP046_R002_NEXT_GOAL_ID,
                "from_status": "READY",
                "to_status": "IN_PROGRESS",
                "static_plan_manifest_sha256": "c" * 64,
                "status_changes": {
                    graph.FP046_R002_NEXT_GOAL_ID: "IN_PROGRESS"
                },
                "runtime_after": copy.deepcopy(correction["runtime_after"]),
                "repository_snapshot_before": {},
                "implementation_start_gate_binding": copy.deepcopy(receipt),
                "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
                "blocker_resolution_ids_after": [
                    row["resolution_id"]
                    for row in state["blocker_resolution_history"]
                ],
                "source_checkpoint_version": checkpoint["schema_version"],
                "evidence_refs": [],
                "previous_event_sha256": correction["event_sha256"],
            }
        )
        event["event_sha256"] = graph.continuation.event_sha256(event)
        state["transition_history"].append(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]
        state["validation_cutoff_at"] = occurred_at
        state["status_by_goal"][graph.FP046_R002_NEXT_GOAL_ID] = "IN_PROGRESS"
        state["goal_status"] = "IN_PROGRESS"
        checkpoint["current_work"]["status"] = "IN_PROGRESS"
        return event

    @staticmethod
    def _correction_authority() -> mock.Mock:
        authority = mock.Mock()
        authority.canonical_seq92_checkpoint_bytes.side_effect = (
            lambda _root, value: Fp048R002Seq92R004Seq93Tests._canonical(value)
        )
        return authority

    @classmethod
    def _producer_seq92_projection(cls) -> tuple[object, dict, bytes]:
        from scripts import (
            apply_walksafe_fp048_r002_start_gate_contract_correction_seq92_20260826
            as correction,
        )

        source = Fp048R002Seq91CorrectionTests._exact_seq91_checkpoint()
        current, _seq91 = Fp048R002Seq91CorrectionTests._checkpoint()
        snapshot = current["working_tree_snapshot"]
        source_at = datetime.fromisoformat(
            source["goal_execution"]["transition_history"][-1]["occurred_at"]
        )
        authorization = cls._sealed(
            correction.AUTHORIZATION_REL.as_posix()
        )
        review = {
            role: cls._sealed(relative.as_posix())
            for role, relative in (
                ("assignment", correction.REVIEW_ASSIGNMENT_REL),
                ("review_result", correction.REVIEW_RESULT_REL),
                ("independent_review", correction.INDEPENDENT_REVIEW_REL),
            )
        }
        projected, _event = correction.project_seq92(
            ROOT,
            source,
            managed_paths=snapshot["managed_changed_paths"],
            path_set_sha256=snapshot["path_set_sha256"],
            content_set_sha256=snapshot["content_set_sha256"],
            occurred_at=(source_at + timedelta(seconds=1)).isoformat(),
            authorization_binding_value=authorization,
            review_binding=review,
            replacement_contract_binding=correction.r004_contract_binding(ROOT),
            replacement_runner_binding=correction.r004_runner_binding(ROOT),
        )
        return correction, projected, correction.checkpoint_json_bytes(projected)

    def test_seq92_is_ready_zero_credit_r003_to_r004(self) -> None:
        checkpoint, _seq91 = Fp048R002Seq91CorrectionTests._checkpoint()
        event = self._append_seq92(checkpoint)
        authority = self._correction_authority()
        with mock.patch.object(
            graph,
            "_fp048_r002_start_gate_contract_correction_authority",
            return_value=authority,
        ), mock.patch.object(
            graph,
            "_fp048_r002_r004_started_authority",
            side_effect=AssertionError("seq93 authority loaded for exact seq92"),
        ):
            self.assertEqual(
                graph.validate_fp048_r002_seq91_93(ROOT, checkpoint), []
            )

        state = checkpoint["goal_execution"]
        supersession = event["contract_supersession"]
        self.assertEqual(state["goal_status"], "READY")
        self.assertEqual(list(state["status_by_goal"].values()).count("IN_PROGRESS"), 0)
        self.assertEqual(event["status_changes"], {})
        self.assertEqual(
            supersession["previous_contract_binding"]["contract_id"],
            "WS-FP048-R002-INTERNAL-START-GATE-R003",
        )
        self.assertEqual(
            supersession["replacement_contract_binding"]["contract_id"],
            graph.FP048_R002_R004_CONTRACT_ID,
        )
        authority.require_contract_corrected_checkpoint.assert_called_once_with(
            ROOT, checkpoint, require_live_snapshot=True
        )

    def test_r004_pass_003_cannot_publish_seq93_goal_started(self) -> None:
        checkpoint, _seq91 = Fp048R002Seq91CorrectionTests._checkpoint()
        self._append_seq92(checkpoint)
        receipt = self._receipt()
        self._append_seq93(checkpoint, receipt)
        with mock.patch.object(
            graph,
            "_fp048_r002_start_gate_contract_correction_authority",
            return_value=self._correction_authority(),
        ):
            errors = graph.validate_fp048_r002_seq91_93(ROOT, checkpoint)

        self.assertEqual(len(errors), 1)
        self.assertIn(
            "-003 PASS gate must remain PASS_UNCONSUMED",
            errors[0],
        )

    def test_seq92_current_branch_does_not_load_retired_r004_starter(
        self,
    ) -> None:
        checkpoint, _seq91 = Fp048R002Seq91CorrectionTests._checkpoint()
        self._append_seq92(checkpoint)
        with mock.patch.object(
            graph,
            "_fp048_r002_start_gate_contract_correction_authority",
            return_value=self._correction_authority(),
        ), mock.patch.object(
            graph,
            "_fp048_r002_r004_started_authority",
            side_effect=AssertionError("retired -003 starter was loaded"),
        ):
            self.assertEqual(
                graph.validate_fp048_r002_seq91_94(ROOT, checkpoint), []
            )

    def test_seq92_and_seq93_projection_drift_is_rejected(self) -> None:
        for mutation in ("r004", "status", "frontier", "credit"):
            with self.subTest(sequence=92, mutation=mutation):
                checkpoint, _seq91 = Fp048R002Seq91CorrectionTests._checkpoint()
                event = self._append_seq92(checkpoint)
                state = checkpoint["goal_execution"]
                if mutation == "r004":
                    event["contract_supersession"]["replacement_contract_binding"][
                        "contract_version"
                    ] = "FORGED"
                    event["event_sha256"] = graph.continuation.event_sha256(event)
                    state["transition_history_anchor_sha256"] = event["event_sha256"]
                elif mutation == "status":
                    state["status_by_goal"][graph.FP046_R002_NEXT_GOAL_ID] = "IN_PROGRESS"
                elif mutation == "frontier":
                    state["ready_frontier_goal_ids"] = list(
                        reversed(state["ready_frontier_goal_ids"])
                    )
                else:
                    checkpoint["approved_state"]["formal_test_count"] += 1
                with mock.patch.object(
                    graph,
                    "_fp048_r002_start_gate_contract_correction_authority",
                    return_value=self._correction_authority(),
                ):
                    self.assertTrue(
                        graph.validate_fp048_r002_seq91_93(ROOT, checkpoint)
                    )

    def test_r004_overlay_replays_current_seq92(self) -> None:
        restored_goal_ids = (
            graph.FP046_GOAL_ID,
            graph.NPC_SINGLE_ADMIN_RECOVERY_GOAL_ID,
            graph.FP048_ANDROID_REPORT_GOAL_ID,
        )
        checkpoint, _seq91 = Fp048R002Seq91CorrectionTests._checkpoint()
        self._append_seq92(checkpoint)
        correction = self._correction_authority()
        ready_validator = mock.Mock()
        original = copy.deepcopy(checkpoint)
        with mock.patch.object(
            graph,
            "_r002_reopen_suffix",
            return_value=[{}],
        ), mock.patch.object(
            graph,
            "validate_fp046_npc_r002_reopen_seq72_76",
            return_value=[],
        ), mock.patch.object(
            graph,
            "_fp048_r002_start_gate_contract_correction_authority",
            return_value=correction,
        ), mock.patch(
            "scripts.apply_walksafe_fp048_r002_goal_seq88_89_20260825."
            "require_exact_ready_source",
            ready_validator,
        ):
            overlay = graph._r002_legacy_completion_overlay(ROOT, checkpoint)

        self.assertIsNotNone(overlay)
        replayed = ready_validator.call_args.args[1]
        replayed_state = replayed["goal_execution"]
        self.assertEqual(len(replayed_state["transition_history"]), 89)
        self.assertEqual(
            replayed_state["status_by_goal"][graph.FP046_R002_NEXT_GOAL_ID],
            "READY",
        )
        self.assertEqual(replayed["current_work"]["status"], "READY")
        self.assertEqual(checkpoint, original)
        for goal_id in restored_goal_ids:
            self.assertEqual(
                overlay["goal_execution"]["status_by_goal"][goal_id],
                "COMPLETE_AT_TARGET",
            )

    def test_r004_overlay_rejects_drift_before_seq89_replay(self) -> None:
        checkpoint, _seq91 = Fp048R002Seq91CorrectionTests._checkpoint()
        event = self._append_seq92(checkpoint)
        event["contract_supersession"]["replacement_contract_binding"][
            "contract_version"
        ] = "FORGED"
        event["event_sha256"] = graph.continuation.event_sha256(event)
        checkpoint["goal_execution"]["transition_history_anchor_sha256"] = event[
            "event_sha256"
        ]
        ready_validator = mock.Mock()
        with mock.patch.object(
            graph,
            "_r002_reopen_suffix",
            return_value=[{}],
        ), mock.patch.object(
            graph,
            "validate_fp046_npc_r002_reopen_seq72_76",
            return_value=[],
        ), mock.patch.object(
            graph,
            "_fp048_r002_start_gate_contract_correction_authority",
            return_value=self._correction_authority(),
        ), mock.patch(
            "scripts.apply_walksafe_fp048_r002_goal_seq88_89_20260825."
            "require_exact_ready_source",
            ready_validator,
        ):
            self.assertIsNone(
                graph._r002_legacy_completion_overlay(ROOT, checkpoint)
            )
        ready_validator.assert_not_called()

    def test_producer_seq92_projection_passes_full_goal_graph_validate(self) -> None:
        correction, projected, raw = self._producer_seq92_projection()
        frozen = mock.Mock()
        frozen.load_exact_seq92_source.return_value = (raw, projected)
        with mock.patch.object(
            graph.continuation,
            "load_json",
            side_effect=r008_checkpoint_loader(projected),
        ), mock.patch.object(
            graph.continuation,
            "_fp048_r002_branch_semantics_reanchor_authority",
            return_value=frozen,
        ), mock.patch.object(
            correction,
            "require_contract_corrected_checkpoint",
            return_value=None,
        ), mock.patch.object(
            correction,
            "canonical_seq92_checkpoint_bytes",
            return_value=raw,
        ):
            self.assertEqual(
                graph.validate(ROOT, check_continuation=False),
                [],
            )


class Fp048R002Seq93BranchSemanticsSeq94Tests(unittest.TestCase):
    @staticmethod
    def _canonical(checkpoint: dict) -> bytes:
        return json.dumps(
            checkpoint,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")

    @staticmethod
    def _passed_attempt() -> dict:
        root = (
            "docs/control/execution/goal-gates/"
            f"{graph.FP048_R002_R004_STARTED_EVENT_ID}"
        )
        return {
            "event_id": graph.FP048_R002_R004_STARTED_EVENT_ID,
            "directory": root,
            "directory_mode": "0700",
            "status": "PASS_UNCONSUMED",
            "files": [
                {
                    "path": f"{root}/{index:02d}.log",
                    "sha256": f"{index:x}" * 64,
                    "byte_length": index,
                }
                for index in range(1, 7)
            ],
        }

    @staticmethod
    def _r005_contract() -> dict:
        return {
            "schema_version": "1.2",
            "document_id": graph.FP048_R002_R005_CONTRACT_DOCUMENT_ID,
            "path": graph.FP048_R002_R005_CONTRACT_PATH,
            "file_sha256": "5" * 64,
            "contract_id": graph.FP048_R002_R005_CONTRACT_ID,
            "contract_version": graph.FP048_R002_R005_CONTRACT_VERSION,
            "canonical_contract_sha256": "6" * 64,
        }

    @classmethod
    def _append_seq93(
        cls,
        checkpoint: dict,
        passed_attempt: dict,
        r005_contract: dict,
    ) -> dict:
        state = checkpoint["goal_execution"]
        seq92 = state["transition_history"][91]
        occurred_at = (
            datetime.fromisoformat(seq92["occurred_at"])
            + timedelta(seconds=1)
        )
        event = copy.deepcopy(seq92)
        event.update(
            {
                "sequence": graph.FP048_R002_BRANCH_SEMANTICS_REANCHOR_SEQUENCE,
                "event_id": graph.FP048_R002_BRANCH_SEMANTICS_REANCHOR_EVENT_ID,
                "event_type": "GOAL_START_CONTROL_REANCHORED",
                "occurred_at": occurred_at.isoformat(),
                "occurred_on": occurred_at.date().isoformat(),
                "from_status": "READY",
                "to_status": "READY",
                "status_changes": {},
                "runtime_after": copy.deepcopy(seq92["runtime_after"]),
                "source_checkpoint_binding": {
                    **cls._sealed_checkpoint_source(seq92),
                    "passed_gate_attempt_003": copy.deepcopy(passed_attempt),
                },
                "contract_supersession": {
                    "previous_contract_binding": copy.deepcopy(
                        seq92["contract_supersession"][
                            "replacement_contract_binding"
                        ]
                    ),
                    "reason_code": (
                        "SEQ93_BRANCH_SEMANTICS_REANCHOR_AND_FRESH_GATE_REQUIRED"
                    ),
                    "replacement_contract_binding": copy.deepcopy(
                        r005_contract
                    ),
                },
                "start_gate_runner_binding": {
                    "path": graph.FP048_R002_R005_START_GATE_RUNNER_PATH,
                    "sha256": "7" * 64,
                    "byte_length": 1,
                },
                "repository_context_reanchor": {
                    "before": {},
                    "after": {
                        "base_commit": "8" * 40,
                        "branch": "current",
                        "logical_branch": "current",
                        "logical_branch_semantics": "WORKSTREAM_LABEL",
                        "physical_git_branch": (
                            "recovery/fp046-r008-wip-20260822"
                        ),
                        "branch_mismatch_reason_code": (
                            "LOGICAL_CURRENT_LABEL_IS_NOT_PHYSICAL_GIT_BRANCH"
                        ),
                        "current_head": "9" * 40,
                        "managed_changed_path_count": 1,
                        "path_set_sha256": "a" * 64,
                        "content_set_sha256": "b" * 64,
                    },
                },
                "correction_reason": {
                    "branch_mismatch_reason_code": (
                        "LOGICAL_CURRENT_LABEL_IS_NOT_PHYSICAL_GIT_BRANCH"
                    ),
                    "logical_branch": "current",
                    "logical_branch_semantics": "WORKSTREAM_LABEL",
                    "observed_gate_event_id": (
                        graph.FP048_R002_R004_STARTED_EVENT_ID
                    ),
                    "observed_physical_git_branch": (
                        "recovery/fp046-r008-wip-20260822"
                    ),
                    "reason_code": "R004_PASS_BRANCH_SEMANTICS_AMBIGUOUS",
                    "remediation": (
                        "SEAL_R004_PASS_AS_UNCONSUMED_AND_RUN_BRANCH_AWARE_"
                        "R005_GATE"
                    ),
                },
                "claim_boundary": copy.deepcopy(
                    graph.FP048_R002_CONTROL_REANCHOR_CLAIM_BOUNDARY
                ),
                "previous_event_sha256": seq92["event_sha256"],
            }
        )
        event["event_sha256"] = graph.continuation.event_sha256(event)
        state["transition_history"].append(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]
        state["validation_cutoff_at"] = event["occurred_at"]
        return event

    @staticmethod
    def _sealed_checkpoint_source(seq92: dict) -> dict:
        return {
            "path": graph.V24_CHECKPOINT_RELATIVE.as_posix(),
            "sha256": "c" * 64,
            "byte_length": 1,
            "sequence": graph.FP048_R002_START_GATE_CONTRACT_CORRECTION_SEQUENCE,
            "tail_event_id": graph.FP048_R002_START_GATE_CONTRACT_CORRECTION_EVENT_ID,
            "tail_event_sha256": seq92["event_sha256"],
        }

    @staticmethod
    def _receipt() -> dict:
        return {
            "document_id": graph.FP048_R002_R005_STARTED_GATE_DOCUMENT_ID,
            "path": graph.FP048_R002_R005_STARTED_GATE_RECEIPT_PATH,
            "file_sha256": "d" * 64,
        }

    @classmethod
    def _append_seq94(cls, checkpoint: dict, receipt: dict) -> dict:
        state = checkpoint["goal_execution"]
        control = state["transition_history"][92]
        occurred_at = (
            datetime.fromisoformat(control["occurred_at"])
            + timedelta(seconds=1)
        )
        event = {
            field: None
            for field in graph.continuation.V24_FIRST_START_EVENT_FIELDS
        }
        event.update(
            {
                "sequence": graph.FP048_R002_R005_STARTED_SEQUENCE,
                "event_id": graph.FP048_R002_R005_STARTED_EVENT_ID,
                "event_type": "GOAL_STARTED",
                "occurred_on": occurred_at.date().isoformat(),
                "occurred_at": occurred_at.isoformat(),
                "previous_focus_goal_id": graph.FP046_R002_NEXT_GOAL_ID,
                "previous_focus_content_sha256": "e" * 64,
                "focus_goal_id": graph.FP046_R002_NEXT_GOAL_ID,
                "focus_goal_content_sha256": "e" * 64,
                "subject_goal_id": graph.FP046_R002_NEXT_GOAL_ID,
                "from_status": "READY",
                "to_status": "IN_PROGRESS",
                "static_plan_manifest_sha256": "f" * 64,
                "status_changes": {
                    graph.FP046_R002_NEXT_GOAL_ID: "IN_PROGRESS"
                },
                "runtime_after": copy.deepcopy(control["runtime_after"]),
                "repository_snapshot_before": {},
                "implementation_start_gate_binding": copy.deepcopy(receipt),
                "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
                "blocker_resolution_ids_after": [
                    row["resolution_id"]
                    for row in state["blocker_resolution_history"]
                ],
                "source_checkpoint_version": checkpoint["schema_version"],
                "evidence_refs": [],
                "previous_event_sha256": control["event_sha256"],
            }
        )
        event["event_sha256"] = graph.continuation.event_sha256(event)
        state["transition_history"].append(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]
        state["validation_cutoff_at"] = event["occurred_at"]
        state["status_by_goal"][graph.FP046_R002_NEXT_GOAL_ID] = (
            "IN_PROGRESS"
        )
        state["goal_status"] = "IN_PROGRESS"
        checkpoint["current_work"]["status"] = "IN_PROGRESS"
        return event

    @staticmethod
    def _reanchor_authority(
        event: dict,
        passed_attempt: dict,
        r005_contract: dict,
        seq92_raw: bytes,
        seq93_raw: bytes,
    ) -> mock.Mock:
        authority = mock.Mock()
        authority.EVENT_FIELDS = frozenset(event)
        authority.CLAIM_BOUNDARY = copy.deepcopy(event["claim_boundary"])
        authority.CORRECTION_REASON = copy.deepcopy(event["correction_reason"])
        authority.LOGICAL_BRANCH = "current"
        authority.LOGICAL_BRANCH_SEMANTICS = "WORKSTREAM_LABEL"
        authority.PHYSICAL_GIT_BRANCH = "recovery/fp046-r008-wip-20260822"
        authority.BRANCH_MISMATCH_REASON_CODE = (
            "LOGICAL_CURRENT_LABEL_IS_NOT_PHYSICAL_GIT_BRANCH"
        )
        authority.R005_SUCCESSOR_REASON_CODE = (
            "SEQ93_BRANCH_SEMANTICS_REANCHOR_AND_FRESH_GATE_REQUIRED"
        )
        authority.passed_gate_attempt_003_binding.return_value = (
            copy.deepcopy(passed_attempt)
        )
        authority.r005_contract_binding.return_value = copy.deepcopy(
            r005_contract
        )
        authority.checkpoint_json_bytes.side_effect = (
            Fp048R002Seq93BranchSemanticsSeq94Tests._canonical
        )
        authority.canonical_seq93_checkpoint_bytes.return_value = seq93_raw
        authority.reconstructed_seq93_checkpoint_bytes.return_value = seq93_raw
        authority.reconstructed_seq92_checkpoint_bytes.return_value = seq92_raw
        authority.require_exact_seq92_source.return_value = None
        authority.require_branch_semantics_reanchored_checkpoint.return_value = (
            None
        )
        return authority

    @classmethod
    def _successor_correction_authority(
        cls,
        seq93_raw: bytes,
    ) -> mock.Mock:
        authority = mock.Mock()
        authority.reconstructed_seq93_checkpoint_bytes.return_value = (
            seq93_raw
        )

        def require_exact_seq93_source(
            raw: bytes,
            checkpoint: dict,
            _root: Path,
        ) -> None:
            if raw != seq93_raw or cls._canonical(checkpoint) != seq93_raw:
                raise RuntimeError("seq93 frozen source differs")

        authority.require_exact_seq93_source.side_effect = (
            require_exact_seq93_source
        )
        return authority

    def _seq93_fixture(
        self,
    ) -> tuple[dict, bytes, bytes, mock.Mock, mock.Mock, dict]:
        checkpoint, _seq91 = Fp048R002Seq91CorrectionTests._checkpoint()
        Fp048R002Seq92R004Seq93Tests._append_seq92(checkpoint)
        seq92_raw = self._canonical(checkpoint)
        passed_attempt = self._passed_attempt()
        r005_contract = self._r005_contract()
        event = self._append_seq93(
            checkpoint,
            passed_attempt,
            r005_contract,
        )
        seq93_raw = self._canonical(checkpoint)
        correction = (
            Fp048R002Seq92R004Seq93Tests._correction_authority()
        )
        correction.canonical_seq92_checkpoint_bytes.return_value = seq92_raw
        reanchor = self._reanchor_authority(
            event,
            passed_attempt,
            r005_contract,
            seq92_raw,
            seq93_raw,
        )
        return checkpoint, seq92_raw, seq93_raw, correction, reanchor, event

    def test_seq93_physical_branch_differs_from_logical_current_and_both_validators_pass(
        self,
    ) -> None:
        checkpoint, _seq92_raw, seq93_raw, correction, reanchor, event = (
            self._seq93_fixture()
        )
        successor = self._successor_correction_authority(seq93_raw)
        history = checkpoint["goal_execution"]["transition_history"]
        with mock.patch.object(
            graph,
            "_fp048_r002_start_gate_contract_correction_authority",
            return_value=correction,
        ), mock.patch.object(
            graph,
            "_fp048_r002_branch_semantics_reanchor_authority",
            return_value=reanchor,
        ), mock.patch.object(
            graph.continuation,
            "_fp048_r002_branch_semantics_reanchor_authority",
            return_value=reanchor,
        ), mock.patch.object(
            graph,
            "_fp048_r002_r006_contract_correction_authority",
            return_value=successor,
        ), mock.patch.object(
            graph.continuation,
            "_fp048_r002_r006_contract_correction_authority",
            return_value=successor,
        ):
            continuation_errors = (
                graph.continuation._validate_fp048_r002_branch_semantics_reanchor_seq93(
                    ROOT,
                    event=event,
                    checkpoint=checkpoint,
                    history=history,
                )
            )
            graph_errors = graph.validate_fp048_r002_seq91_94(
                ROOT,
                checkpoint,
            )

        self.assertEqual(continuation_errors, [])
        self.assertEqual(graph_errors, [])
        after = event["repository_context_reanchor"]["after"]
        self.assertEqual(checkpoint["session_handoff"]["branch"], "current")
        self.assertEqual(after["logical_branch"], "current")
        self.assertNotEqual(
            after["physical_git_branch"],
            after["logical_branch"],
        )
        reanchor.require_branch_semantics_reanchored_checkpoint.assert_not_called()
        self.assertEqual(
            event["source_checkpoint_binding"]["passed_gate_attempt_003"][
                "status"
            ],
            "PASS_UNCONSUMED",
        )

    def test_seq93_frozen_successor_rejects_forgery_and_absent_successor_uses_legacy(
        self,
    ) -> None:
        with self.subTest("present successor rejects forged state"):
            checkpoint, _a, seq93_raw, correction, reanchor, _event = (
                self._seq93_fixture()
            )
            checkpoint["current_work"]["status"] = "IN_PROGRESS"
            successor = self._successor_correction_authority(seq93_raw)
            with mock.patch.object(
                graph,
                "_fp048_r002_start_gate_contract_correction_authority",
                return_value=correction,
            ), mock.patch.object(
                graph,
                "_fp048_r002_branch_semantics_reanchor_authority",
                return_value=reanchor,
            ), mock.patch.object(
                graph,
                "_fp048_r002_r006_contract_correction_authority",
                return_value=successor,
            ):
                self.assertEqual(
                    graph.validate_fp048_r002_seq91_94(ROOT, checkpoint),
                    [
                        "FP048 R002 seq93 branch-semantics authority differs: "
                        "seq93 frozen source differs"
                    ],
                )
            reanchor.require_branch_semantics_reanchored_checkpoint.assert_not_called()

        with self.subTest("absent successor retains standalone validator"):
            checkpoint, _a, _b, correction, reanchor, _event = (
                self._seq93_fixture()
            )
            missing = ModuleNotFoundError(
                "successor correction module is absent",
                name=graph.FP048_R002_R006_CONTRACT_CORRECTION_MODULE,
            )
            load_error = RuntimeError(
                "FP048 R002 seq94 R006 contract-correction authority "
                "cannot be loaded"
            )
            load_error.__cause__ = missing
            with mock.patch.object(
                graph,
                "_fp048_r002_start_gate_contract_correction_authority",
                return_value=correction,
            ), mock.patch.object(
                graph,
                "_fp048_r002_branch_semantics_reanchor_authority",
                return_value=reanchor,
            ), mock.patch.object(
                graph,
                "_fp048_r002_r006_contract_correction_authority",
                side_effect=load_error,
            ):
                self.assertEqual(
                    graph.validate_fp048_r002_seq91_94(ROOT, checkpoint),
                    [],
                )
            reanchor.require_branch_semantics_reanchored_checkpoint.assert_called_once()

    def test_legacy_r005_direct_seq94_start_is_rejected_without_consume(
        self,
    ) -> None:
        checkpoint, _seq92_raw, seq93_raw, correction, reanchor, _event = (
            self._seq93_fixture()
        )
        receipt = self._receipt()
        started = self._append_seq94(checkpoint, receipt)
        starter = mock.Mock()
        starter.EVENT_FIELDS = frozenset(started)
        starter.goal_start_gate_receipt_binding.return_value = receipt
        starter.reconstructed_seq93_checkpoint_bytes.return_value = seq93_raw
        starter.require_exact_seq94_projection.return_value = None
        with mock.patch.object(
            graph,
            "_fp048_r002_start_gate_contract_correction_authority",
            return_value=correction,
        ), mock.patch.object(
            graph,
            "_fp048_r002_branch_semantics_reanchor_authority",
            return_value=reanchor,
        ), mock.patch.object(
            graph,
            "_fp048_r002_r005_started_authority",
            return_value=starter,
        ):
            self.assertEqual(
                graph.validate_fp048_r002_seq91_94(ROOT, checkpoint),
                [
                    "FP048 R002 legacy R005 direct seq94 GOAL_STARTED "
                    "is forbidden"
                ],
            )

        state = checkpoint["goal_execution"]
        self.assertEqual(
            started["implementation_start_gate_binding"],
            receipt,
        )
        self.assertEqual(
            list(state["status_by_goal"].values()).count("IN_PROGRESS"),
            1,
        )
        self.assertFalse(
            any(
                event.get("event_id")
                == graph.FP048_R002_R004_STARTED_EVENT_ID
                for event in state["transition_history"]
            )
        )
        starter.require_exact_seq94_projection.assert_not_called()

    def test_seq93_rejects_consumed_003_and_collapsed_branch_semantics(
        self,
    ) -> None:
        for mutation in ("consumed_003", "same_physical_branch"):
            with self.subTest(mutation=mutation):
                checkpoint, _a, _b, correction, reanchor, event = (
                    self._seq93_fixture()
                )
                if mutation == "consumed_003":
                    event["source_checkpoint_binding"][
                        "passed_gate_attempt_003"
                    ]["status"] = "PASS_CONSUMED"
                else:
                    event["repository_context_reanchor"]["after"][
                        "physical_git_branch"
                    ] = "current"
                event["event_sha256"] = graph.continuation.event_sha256(
                    event
                )
                checkpoint["goal_execution"][
                    "transition_history_anchor_sha256"
                ] = event["event_sha256"]
                with mock.patch.object(
                    graph,
                    "_fp048_r002_start_gate_contract_correction_authority",
                    return_value=correction,
                ), mock.patch.object(
                    graph,
                    "_fp048_r002_branch_semantics_reanchor_authority",
                    return_value=reanchor,
                ):
                    self.assertTrue(
                        graph.validate_fp048_r002_seq91_94(
                            ROOT,
                            checkpoint,
                        )
                    )


class Fp048R002Seq94CorrectionSeq95Tests(unittest.TestCase):
    @staticmethod
    def _r006_contract() -> dict:
        return graph._fp048_r002_r006_contract_binding()

    @staticmethod
    def _r006_runner() -> dict:
        return {
            "path": graph.FP048_R002_R006_START_GATE_RUNNER_PATH,
            "sha256": graph.FP048_R002_R006_START_GATE_RUNNER_SHA256,
            "byte_length": (
                graph.FP048_R002_R006_START_GATE_RUNNER_BYTE_LENGTH
            ),
        }

    @classmethod
    def _append_seq94(cls, checkpoint: dict) -> dict:
        state = checkpoint["goal_execution"]
        control = state["transition_history"][92]
        occurred_at = (
            datetime.fromisoformat(control["occurred_at"])
            + timedelta(seconds=1)
        )
        event = copy.deepcopy(control)
        event.update(
            {
                "sequence": graph.FP048_R002_R006_CONTRACT_CORRECTION_SEQUENCE,
                "event_id": graph.FP048_R002_R006_CONTRACT_CORRECTION_EVENT_ID,
                "event_type": "GOAL_START_GATE_CONTRACT_CORRECTED",
                "occurred_on": occurred_at.date().isoformat(),
                "occurred_at": occurred_at.isoformat(),
                "previous_focus_content_sha256": (
                    graph.FP048_R002_CORRECTED_STARTED_GOAL_SHA256
                ),
                "focus_goal_content_sha256": (
                    graph.FP048_R002_CORRECTED_STARTED_GOAL_SHA256
                ),
                "static_plan_manifest_sha256": (
                    graph.FP048_R002_CORRECTED_STARTED_MANIFEST_SHA256
                ),
                "from_status": "READY",
                "to_status": "READY",
                "status_changes": {},
                "runtime_after": copy.deepcopy(control["runtime_after"]),
                "blockers_after": copy.deepcopy(control["blockers_after"]),
                "blocker_resolution_ids_after": copy.deepcopy(
                    control["blocker_resolution_ids_after"]
                ),
                "source_checkpoint_version": checkpoint["schema_version"],
                "evidence_refs": [
                    "FP048-R002_EXACT_SEQ93_BRANCH_SEMANTICS_SOURCE",
                    "FP048-R002_SEQ93_R003_FROZEN_REVIEW_AUTHORITY",
                    "FP048-R002_R004_PASS_003_REMAINS_PASS_UNCONSUMED",
                    "FP048-R002_R005_PREFLIGHT_004_NO_NAMESPACE_NONAUTHORITY",
                    "FP048-R002_R006_STAGE_AWARE_GATE_CONTRACT",
                    "FP048-R002_SEQ94_95_TRANSITION_CONTROL_REVIEW",
                ],
                "source_checkpoint_binding": {
                    "path": graph.V24_CHECKPOINT_RELATIVE.as_posix(),
                    "sha256": "1" * 64,
                    "byte_length": 1,
                    "sequence": (
                        graph.FP048_R002_BRANCH_SEMANTICS_REANCHOR_SEQUENCE
                    ),
                    "tail_event_id": (
                        graph.FP048_R002_BRANCH_SEMANTICS_REANCHOR_EVENT_ID
                    ),
                    "tail_event_sha256": control["event_sha256"],
                    "passed_gate_attempt_003": copy.deepcopy(
                        control["source_checkpoint_binding"]
                        ["passed_gate_attempt_003"]
                    ),
                    "preflight_attempt_004": copy.deepcopy(
                        graph.FP048_R002_R005_PREFLIGHT_ATTEMPT
                    ),
                },
                "source_ready_event_binding": copy.deepcopy(
                    control["source_ready_event_binding"]
                ),
                "contract_supersession": {
                    "previous_contract_binding": copy.deepcopy(
                        control["contract_supersession"]
                        ["replacement_contract_binding"]
                    ),
                    "reason_code": (
                        graph.FP048_R002_R006_SUCCESSOR_REASON_CODE
                    ),
                    "replacement_contract_binding": cls._r006_contract(),
                },
                "start_gate_runner_binding": cls._r006_runner(),
                "noncredit_successor_edges": copy.deepcopy(
                    control["noncredit_successor_edges"]
                ),
                "claim_boundary": copy.deepcopy(
                    graph.FP048_R002_CONTROL_REANCHOR_CLAIM_BOUNDARY
                ),
                "canonical_binding_snapshot_after": copy.deepcopy(
                    control["canonical_binding_snapshot_after"]
                ),
                "correction_reason": copy.deepcopy(
                    graph.FP048_R002_R006_CORRECTION_REASON
                ),
                "previous_event_sha256": control["event_sha256"],
            }
        )
        if set(event) != graph.FP048_R002_ZERO_CREDIT_CONTROL_EVENT_FIELDS:
            raise AssertionError("seq94 fixture field set differs")
        event["event_sha256"] = graph.continuation.event_sha256(event)
        state["transition_history"].append(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]
        state["validation_cutoff_at"] = event["occurred_at"]
        return event

    @staticmethod
    def _correction_authority(seq93_raw: bytes, seq94_raw: bytes) -> mock.Mock:
        authority = mock.Mock()
        authority.require_contract_corrected_checkpoint.return_value = None
        authority.canonical_seq94_checkpoint_bytes.return_value = seq94_raw
        authority.reconstructed_seq93_checkpoint_bytes.return_value = seq93_raw

        def require_exact_seq93_source(
            raw: bytes,
            _source: dict,
            _root: Path,
        ) -> None:
            if raw != seq93_raw:
                raise RuntimeError("seq93 frozen source differs")

        authority.require_exact_seq93_source.side_effect = (
            require_exact_seq93_source
        )
        return authority

    @staticmethod
    def _receipt_binding() -> dict:
        return {
            "document_id": graph.FP048_R002_R006_STARTED_GATE_DOCUMENT_ID,
            "path": graph.FP048_R002_R006_STARTED_GATE_RECEIPT_PATH,
            "file_sha256": "d" * 64,
        }

    @classmethod
    def _append_seq95(
        cls,
        checkpoint: dict,
        receipt_binding: dict,
        repository_snapshot: dict,
    ) -> dict:
        state = checkpoint["goal_execution"]
        correction = state["transition_history"][93]
        occurred_at = (
            datetime.fromisoformat(correction["occurred_at"])
            + timedelta(seconds=1)
        )
        event = {
            field: None
            for field in graph.continuation.V24_FIRST_START_EVENT_FIELDS
        }
        event.update(
            {
                "sequence": graph.FP048_R002_R006_STARTED_SEQUENCE,
                "event_id": graph.FP048_R002_R006_STARTED_EVENT_ID,
                "event_type": "GOAL_STARTED",
                "occurred_on": occurred_at.date().isoformat(),
                "occurred_at": occurred_at.isoformat(),
                "previous_focus_goal_id": graph.FP046_R002_NEXT_GOAL_ID,
                "previous_focus_content_sha256": (
                    graph.FP048_R002_CORRECTED_STARTED_GOAL_SHA256
                ),
                "focus_goal_id": graph.FP046_R002_NEXT_GOAL_ID,
                "focus_goal_content_sha256": (
                    graph.FP048_R002_CORRECTED_STARTED_GOAL_SHA256
                ),
                "subject_goal_id": graph.FP046_R002_NEXT_GOAL_ID,
                "from_status": "READY",
                "to_status": "IN_PROGRESS",
                "static_plan_manifest_sha256": (
                    graph.FP048_R002_CORRECTED_STARTED_MANIFEST_SHA256
                ),
                "status_changes": {
                    graph.FP046_R002_NEXT_GOAL_ID: "IN_PROGRESS"
                },
                "runtime_after": copy.deepcopy(correction["runtime_after"]),
                "repository_snapshot_before": copy.deepcopy(
                    repository_snapshot
                ),
                "implementation_start_gate_binding": copy.deepcopy(
                    receipt_binding
                ),
                "blockers_after": copy.deepcopy(correction["blockers_after"]),
                "blocker_resolution_ids_after": copy.deepcopy(
                    correction["blocker_resolution_ids_after"]
                ),
                "source_checkpoint_version": checkpoint["schema_version"],
                "evidence_refs": [],
                "previous_event_sha256": correction["event_sha256"],
            }
        )
        event["event_sha256"] = graph.continuation.event_sha256(event)
        state["transition_history"].append(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]
        state["validation_cutoff_at"] = event["occurred_at"]
        state["status_by_goal"][graph.FP046_R002_NEXT_GOAL_ID] = (
            "IN_PROGRESS"
        )
        state["goal_status"] = "IN_PROGRESS"
        checkpoint["current_work"]["status"] = "IN_PROGRESS"
        return event

    @staticmethod
    def _started_authority(seq94_raw: bytes) -> mock.Mock:
        authority = mock.Mock()
        authority.require_started_checkpoint.return_value = None
        authority.reconstructed_seq94_checkpoint_bytes.return_value = seq94_raw
        return authority

    def _seq94_fixture(
        self,
    ) -> tuple[dict, bytes, bytes, mock.Mock, mock.Mock, mock.Mock, dict]:
        checkpoint, _seq92_raw, seq93_raw, old_correction, reanchor, _event = (
            Fp048R002Seq93BranchSemanticsSeq94Tests()._seq93_fixture()
        )
        event = self._append_seq94(checkpoint)
        seq94_raw = Fp048R002Seq93BranchSemanticsSeq94Tests._canonical(
            checkpoint
        )
        authority = self._correction_authority(seq93_raw, seq94_raw)
        return (
            checkpoint,
            seq93_raw,
            seq94_raw,
            old_correction,
            reanchor,
            authority,
            event,
        )

    def test_seq94_exact_correction_reconstructs_seq93_and_stays_ready(
        self,
    ) -> None:
        checkpoint, _a, _b, old_correction, reanchor, authority, _event = (
            self._seq94_fixture()
        )
        with mock.patch.object(
            graph,
            "_fp048_r002_start_gate_contract_correction_authority",
            return_value=old_correction,
        ), mock.patch.object(
            graph,
            "_fp048_r002_branch_semantics_reanchor_authority",
            return_value=reanchor,
        ), mock.patch.object(
            graph,
            "_fp048_r002_r006_contract_correction_authority",
            return_value=authority,
        ):
            self.assertEqual(
                graph.validate_fp048_r002_seq91_95(ROOT, checkpoint),
                [],
            )

        state = checkpoint["goal_execution"]
        self.assertEqual(state["goal_status"], "READY")
        self.assertEqual(
            list(state["status_by_goal"].values()).count("IN_PROGRESS"),
            0,
        )
        authority.reconstructed_seq93_checkpoint_bytes.assert_called_once()

    def test_seq94_tamper_fails_even_when_producer_only_accepts(self) -> None:
        for mutation in ("extra_field", "preflight_authority", "status"):
            with self.subTest(mutation=mutation):
                checkpoint, _a, _b, old_correction, reanchor, authority, event = (
                    self._seq94_fixture()
                )
                if mutation == "extra_field":
                    event["forged"] = True
                elif mutation == "preflight_authority":
                    event["source_checkpoint_binding"][
                        "preflight_attempt_004"
                    ]["authority_status"] = "AUTHORITY"
                else:
                    event["to_status"] = "IN_PROGRESS"
                event["event_sha256"] = graph.continuation.event_sha256(event)
                checkpoint["goal_execution"][
                    "transition_history_anchor_sha256"
                ] = event["event_sha256"]
                with mock.patch.object(
                    graph,
                    "_fp048_r002_start_gate_contract_correction_authority",
                    return_value=old_correction,
                ), mock.patch.object(
                    graph,
                    "_fp048_r002_branch_semantics_reanchor_authority",
                    return_value=reanchor,
                ), mock.patch.object(
                    graph,
                    "_fp048_r002_r006_contract_correction_authority",
                    return_value=authority,
                ):
                    self.assertEqual(
                        graph.validate_fp048_r002_seq91_95(ROOT, checkpoint),
                        [
                            "FP048 R002 seq94 R006 contract-correction "
                            "event differs"
                        ],
                    )
                authority.require_contract_corrected_checkpoint.assert_not_called()

    def test_seq94_rejects_nonexact_seq93_inverse(self) -> None:
        checkpoint, _a, _b, old_correction, reanchor, authority, _event = (
            self._seq94_fixture()
        )
        authority.reconstructed_seq93_checkpoint_bytes.return_value = b"{}"
        with mock.patch.object(
            graph,
            "_fp048_r002_start_gate_contract_correction_authority",
            return_value=old_correction,
        ), mock.patch.object(
            graph,
            "_fp048_r002_branch_semantics_reanchor_authority",
            return_value=reanchor,
        ), mock.patch.object(
            graph,
            "_fp048_r002_r006_contract_correction_authority",
            return_value=authority,
        ):
            errors = graph.validate_fp048_r002_seq91_95(ROOT, checkpoint)
        self.assertTrue(errors)
        self.assertIn("seq94 R006 correction authority differs", errors[0])

    def test_r006_receipt_requires_exact_five_check_pass_order(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            receipt_path = (
                root / graph.FP048_R002_R006_STARTED_GATE_RECEIPT_PATH
            )
            receipt_path.parent.mkdir(parents=True)
            source_raw = b"exact seq94 source\n"
            correction = {
                "event_sha256": "a" * 64,
                "source_ready_event_binding": {"event_sha256": "b" * 64},
            }
            repository_snapshot = {"head_commit": "c" * 40}
            runs = [
                {
                    "check_id": check_id,
                    "command": f"command-{index}",
                    "executed_at": f"2026-08-26T15:40:0{index}+09:00",
                    "exit_code": 0,
                    "output_path": (
                        "docs/control/execution/goal-gates/"
                        f"{graph.FP048_R002_R006_STARTED_EVENT_ID}/"
                        f"{index:02d}-{check_id}.log"
                    ),
                    "output_sha256": f"{index:x}" * 64,
                }
                for index, check_id in enumerate(
                    graph.FP048_R002_R006_EXPECTED_CHECK_IDS,
                    start=1,
                )
            ]
            receipt = {
                "schema_version": "1.1",
                "document_id": (
                    graph.FP048_R002_R006_STARTED_GATE_DOCUMENT_ID
                ),
                "evidence_type": "IMPLEMENTATION_START_OR_RESUME_GATE",
                "gate_purpose": "INITIAL_START",
                "status": "PASS",
                "package_id": "walksafe-completion-graph-v2-4",
                "target_transition_event_id": (
                    graph.FP048_R002_R006_STARTED_EVENT_ID
                ),
                "target_goal_id": graph.FP046_R002_NEXT_GOAL_ID,
                "target_goal_content_sha256": (
                    graph.FP048_R002_CORRECTED_STARTED_GOAL_SHA256
                ),
                "static_plan_manifest_sha256": (
                    graph.FP048_R002_CORRECTED_STARTED_MANIFEST_SHA256
                ),
                "source_activation_event_sha256": correction[
                    "event_sha256"
                ],
                "source_checkpoint_sha256": (
                    graph.continuation.sha256_bytes(source_raw)
                ),
                "source_ready_event_sha256": "b" * 64,
                "check_command_contract_version": (
                    graph.FP048_R002_R006_CONTRACT_VERSION
                ),
                "check_command_contract_sha256": (
                    graph.FP048_R002_R006_CONTRACT_CANONICAL_SHA256
                ),
                "implementation_start_gate_contract_binding": (
                    self._r006_contract()
                ),
                "runtime_bindings": [],
                "execution_window": {
                    "started_at": "2026-08-26T15:40:00+09:00",
                    "ended_at": "2026-08-26T15:40:06+09:00",
                },
                "check_runs": runs,
                "repository_snapshot": repository_snapshot,
                "generated_at": "2026-08-26T15:40:07+09:00",
            }

            def publish() -> dict:
                raw = (json.dumps(receipt, ensure_ascii=False, indent=2) + "\n").encode()
                receipt_path.write_bytes(raw)
                return {
                    "document_id": (
                        graph.FP048_R002_R006_STARTED_GATE_DOCUMENT_ID
                    ),
                    "path": graph.FP048_R002_R006_STARTED_GATE_RECEIPT_PATH,
                    "file_sha256": graph.continuation.sha256_bytes(raw),
                }

            binding = publish()
            started = {
                "implementation_start_gate_binding": binding,
                "repository_snapshot_before": repository_snapshot,
            }
            self.assertIsNotNone(
                graph._fp048_r002_r006_receipt_authority(
                    root,
                    correction,
                    started,
                    source_raw,
                )
            )

            receipt["check_runs"][0]["check_id"] = "FORGED"
            started["implementation_start_gate_binding"] = publish()
            self.assertIsNone(
                graph._fp048_r002_r006_receipt_authority(
                    root,
                    correction,
                    started,
                    source_raw,
                )
            )

    def test_legacy_direct_seq95_started_is_rejected_without_loader(self) -> None:
        checkpoint, _a, seq94_raw, old_correction, reanchor, correction, _event = (
            self._seq94_fixture()
        )
        receipt_binding = self._receipt_binding()
        receipt = {"repository_snapshot": {"head_commit": "a" * 40}}
        started = self._append_seq95(
            checkpoint,
            receipt_binding,
            receipt["repository_snapshot"],
        )
        starter = self._started_authority(seq94_raw)
        with mock.patch.object(
            graph,
            "_fp048_r002_start_gate_contract_correction_authority",
            return_value=old_correction,
        ), mock.patch.object(
            graph,
            "_fp048_r002_branch_semantics_reanchor_authority",
            return_value=reanchor,
        ), mock.patch.object(
            graph,
            "_fp048_r002_r006_contract_correction_authority",
            return_value=correction,
        ), mock.patch.object(
            graph,
            "_fp048_r002_r006_started_authority",
            return_value=starter,
        ), mock.patch.object(
            graph,
            "_fp048_r002_r006_receipt_authority",
            return_value=(receipt_binding, receipt),
        ):
            self.assertEqual(
                graph.validate_fp048_r002_seq91_95(ROOT, checkpoint),
                [
                    "FP048 R002 legacy R006 direct seq95 GOAL_STARTED "
                    "is forbidden"
                ],
            )

        self.assertEqual(started["previous_event_sha256"], _event["event_sha256"])
        self.assertEqual(
            list(
                checkpoint["goal_execution"]["status_by_goal"].values()
            ).count("IN_PROGRESS"),
            1,
        )
        starter.require_started_checkpoint.assert_not_called()

    def test_legacy_direct_seq95_bypass_remains_rejected_after_tamper(
        self,
    ) -> None:
        for mutation in ("adjacency", "binding", "status", "frontier"):
            with self.subTest(mutation=mutation):
                (
                    checkpoint,
                    _a,
                    seq94_raw,
                    old_correction,
                    reanchor,
                    correction,
                    _event,
                ) = self._seq94_fixture()
                receipt_binding = self._receipt_binding()
                receipt = {"repository_snapshot": {"head_commit": "a" * 40}}
                started = self._append_seq95(
                    checkpoint,
                    receipt_binding,
                    receipt["repository_snapshot"],
                )
                if mutation == "adjacency":
                    started["previous_event_sha256"] = "0" * 64
                    started["event_sha256"] = graph.continuation.event_sha256(
                        started
                    )
                    checkpoint["goal_execution"][
                        "transition_history_anchor_sha256"
                    ] = started["event_sha256"]
                elif mutation == "binding":
                    started["implementation_start_gate_binding"][
                        "document_id"
                    ] = "FORGED"
                    started["event_sha256"] = graph.continuation.event_sha256(
                        started
                    )
                    checkpoint["goal_execution"][
                        "transition_history_anchor_sha256"
                    ] = started["event_sha256"]
                elif mutation == "status":
                    checkpoint["goal_execution"]["status_by_goal"][
                        "WS-FORGED"
                    ] = "IN_PROGRESS"
                else:
                    checkpoint["goal_execution"][
                        "ready_frontier_goal_ids"
                    ] = ["WS-FORGED"]
                starter = self._started_authority(seq94_raw)
                with mock.patch.object(
                    graph,
                    "_fp048_r002_start_gate_contract_correction_authority",
                    return_value=old_correction,
                ), mock.patch.object(
                    graph,
                    "_fp048_r002_branch_semantics_reanchor_authority",
                    return_value=reanchor,
                ), mock.patch.object(
                    graph,
                    "_fp048_r002_r006_contract_correction_authority",
                    return_value=correction,
                ), mock.patch.object(
                    graph,
                    "_fp048_r002_r006_started_authority",
                    return_value=starter,
                ), mock.patch.object(
                    graph,
                    "_fp048_r002_r006_receipt_authority",
                    return_value=(receipt_binding, receipt),
                ):
                    errors = graph.validate_fp048_r002_seq91_95(
                        ROOT,
                        checkpoint,
                    )
                self.assertEqual(
                    errors,
                    [
                        "FP048 R002 legacy R006 direct seq95 "
                        "GOAL_STARTED is forbidden"
                    ],
                )
                starter.require_started_checkpoint.assert_not_called()


class Fp048R002Seq95CorrectionSeq96Tests(
    Fp048R002Seq94CorrectionSeq95Tests
):
    @staticmethod
    def _r007_contract() -> dict:
        return graph._fp048_r002_r007_contract_binding()

    @staticmethod
    def _r007_runner() -> dict:
        return {
            "path": graph.FP048_R002_R007_START_GATE_RUNNER_PATH,
            "sha256": graph.FP048_R002_R007_START_GATE_RUNNER_SHA256,
            "byte_length": (
                graph.FP048_R002_R007_START_GATE_RUNNER_BYTE_LENGTH
            ),
        }

    @classmethod
    def _append_seq95_correction(cls, checkpoint: dict) -> dict:
        state = checkpoint["goal_execution"]
        control = state["transition_history"][93]
        occurred_at = (
            datetime.fromisoformat(control["occurred_at"])
            + timedelta(seconds=1)
        )
        event = copy.deepcopy(control)
        event.update(
            {
                "sequence": (
                    graph.FP048_R002_R007_CONTRACT_CORRECTION_SEQUENCE
                ),
                "event_id": (
                    graph.FP048_R002_R007_CONTRACT_CORRECTION_EVENT_ID
                ),
                "event_type": "GOAL_START_GATE_CONTRACT_CORRECTED",
                "occurred_on": occurred_at.date().isoformat(),
                "occurred_at": occurred_at.isoformat(),
                "previous_focus_content_sha256": (
                    graph.FP048_R002_CORRECTED_STARTED_GOAL_SHA256
                ),
                "focus_goal_content_sha256": (
                    graph.FP048_R002_CORRECTED_STARTED_GOAL_SHA256
                ),
                "static_plan_manifest_sha256": (
                    graph.FP048_R002_CORRECTED_STARTED_MANIFEST_SHA256
                ),
                "from_status": "READY",
                "to_status": "READY",
                "status_changes": {},
                "runtime_after": copy.deepcopy(control["runtime_after"]),
                "blockers_after": copy.deepcopy(control["blockers_after"]),
                "blocker_resolution_ids_after": copy.deepcopy(
                    control["blocker_resolution_ids_after"]
                ),
                "source_checkpoint_version": checkpoint["schema_version"],
                "evidence_refs": [
                    "FP048-R002_EXACT_PUBLISHED_SEQ94_SOURCE",
                    "FP048-R002_SEQ94_R002_FROZEN_REVIEW_AUTHORITY",
                    "FP048-R002_R004_PASS_003_REMAINS_PASS_UNCONSUMED",
                    "FP048-R002_R005_PREFLIGHT_004_HISTORICAL_NONAUTHORITY",
                    "FP048-R002_R006_PREFLIGHT_004_NO_NAMESPACE_NONAUTHORITY",
                    "FP048-R002_R007_STAGE_AWARE_GATE_CONTRACT",
                    "FP048-R002_SEQ95_96_TRANSITION_CONTROL_REVIEW",
                ],
                "source_checkpoint_binding": {
                    "path": graph.V24_CHECKPOINT_RELATIVE.as_posix(),
                    "sha256": "2" * 64,
                    "byte_length": 2,
                    "sequence": (
                        graph.FP048_R002_R006_CONTRACT_CORRECTION_SEQUENCE
                    ),
                    "tail_event_id": (
                        graph.FP048_R002_R006_CONTRACT_CORRECTION_EVENT_ID
                    ),
                    "tail_event_sha256": control["event_sha256"],
                    "passed_gate_attempt_003": copy.deepcopy(
                        control["source_checkpoint_binding"]
                        ["passed_gate_attempt_003"]
                    ),
                    "preflight_attempt_004": copy.deepcopy(
                        control["source_checkpoint_binding"]
                        ["preflight_attempt_004"]
                    ),
                    "r006_preflight_attempt_004": copy.deepcopy(
                        graph.FP048_R002_R006_PREFLIGHT_ATTEMPT
                    ),
                },
                "source_ready_event_binding": copy.deepcopy(
                    control["source_ready_event_binding"]
                ),
                "contract_supersession": {
                    "previous_contract_binding": (
                        graph._fp048_r002_r006_contract_binding()
                    ),
                    "reason_code": (
                        graph.FP048_R002_R007_SUCCESSOR_REASON_CODE
                    ),
                    "replacement_contract_binding": cls._r007_contract(),
                },
                "start_gate_runner_binding": cls._r007_runner(),
                "noncredit_successor_edges": copy.deepcopy(
                    control["noncredit_successor_edges"]
                ),
                "claim_boundary": copy.deepcopy(
                    graph.FP048_R002_CONTROL_REANCHOR_CLAIM_BOUNDARY
                ),
                "canonical_binding_snapshot_after": copy.deepcopy(
                    control["canonical_binding_snapshot_after"]
                ),
                "correction_reason": copy.deepcopy(
                    graph.FP048_R002_R007_CORRECTION_REASON
                ),
                "previous_event_sha256": control["event_sha256"],
            }
        )
        if set(event) != graph.FP048_R002_ZERO_CREDIT_CONTROL_EVENT_FIELDS:
            raise AssertionError("seq95 correction fixture field set differs")
        event["event_sha256"] = graph.continuation.event_sha256(event)
        state["transition_history"].append(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]
        state["validation_cutoff_at"] = event["occurred_at"]
        return event

    @staticmethod
    def _seq95_authority(seq94_raw: bytes, seq95_raw: bytes) -> mock.Mock:
        authority = mock.Mock()
        authority.require_contract_corrected_checkpoint.return_value = None
        authority.canonical_frozen_seq94_checkpoint_bytes.return_value = seq94_raw
        authority.canonical_seq95_checkpoint_bytes.return_value = seq95_raw
        authority.reconstructed_seq94_checkpoint_bytes.return_value = seq94_raw
        return authority

    @staticmethod
    def _seq96_authority(
        seq95_raw: bytes,
        receipt_binding: dict,
    ) -> mock.Mock:
        authority = mock.Mock()
        authority.require_exact_seq96_projection.return_value = None
        authority.reconstructed_seq95_checkpoint_bytes.return_value = seq95_raw
        authority.goal_start_gate_receipt_binding.return_value = copy.deepcopy(
            receipt_binding
        )
        return authority

    @classmethod
    def _append_seq96(
        cls,
        checkpoint: dict,
        receipt_binding: dict,
        repository_snapshot: dict,
    ) -> dict:
        state = checkpoint["goal_execution"]
        correction = state["transition_history"][94]
        occurred_at = (
            datetime.fromisoformat(correction["occurred_at"])
            + timedelta(seconds=1)
        )
        event = {
            field: None
            for field in graph.continuation.V24_FIRST_START_EVENT_FIELDS
        }
        event.update(
            {
                "sequence": graph.FP048_R002_R007_STARTED_SEQUENCE,
                "event_id": graph.FP048_R002_R007_STARTED_EVENT_ID,
                "event_type": "GOAL_STARTED",
                "occurred_on": occurred_at.date().isoformat(),
                "occurred_at": occurred_at.isoformat(),
                "previous_focus_goal_id": graph.FP046_R002_NEXT_GOAL_ID,
                "previous_focus_content_sha256": (
                    graph.FP048_R002_CORRECTED_STARTED_GOAL_SHA256
                ),
                "focus_goal_id": graph.FP046_R002_NEXT_GOAL_ID,
                "focus_goal_content_sha256": (
                    graph.FP048_R002_CORRECTED_STARTED_GOAL_SHA256
                ),
                "subject_goal_id": graph.FP046_R002_NEXT_GOAL_ID,
                "from_status": "READY",
                "to_status": "IN_PROGRESS",
                "static_plan_manifest_sha256": (
                    graph.FP048_R002_CORRECTED_STARTED_MANIFEST_SHA256
                ),
                "status_changes": {
                    graph.FP046_R002_NEXT_GOAL_ID: "IN_PROGRESS"
                },
                "runtime_after": copy.deepcopy(correction["runtime_after"]),
                "repository_snapshot_before": copy.deepcopy(
                    repository_snapshot
                ),
                "implementation_start_gate_binding": copy.deepcopy(
                    receipt_binding
                ),
                "blockers_after": copy.deepcopy(correction["blockers_after"]),
                "blocker_resolution_ids_after": copy.deepcopy(
                    correction["blocker_resolution_ids_after"]
                ),
                "source_checkpoint_version": checkpoint["schema_version"],
                "evidence_refs": [],
                "previous_event_sha256": correction["event_sha256"],
            }
        )
        event["event_sha256"] = graph.continuation.event_sha256(event)
        state["transition_history"].append(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]
        state["validation_cutoff_at"] = event["occurred_at"]
        state["status_by_goal"][graph.FP046_R002_NEXT_GOAL_ID] = (
            "IN_PROGRESS"
        )
        state["goal_status"] = "IN_PROGRESS"
        checkpoint["current_work"]["status"] = "IN_PROGRESS"
        return event

    def _seq95_fixture(
        self,
    ) -> tuple[
        dict,
        bytes,
        bytes,
        mock.Mock,
        mock.Mock,
        mock.Mock,
        mock.Mock,
        dict,
    ]:
        (
            checkpoint,
            _seq93_raw,
            seq94_raw,
            old_correction,
            reanchor,
            r006_authority,
            _seq94_event,
        ) = super()._seq94_fixture()
        event = self._append_seq95_correction(checkpoint)
        seq95_raw = Fp048R002Seq93BranchSemanticsSeq94Tests._canonical(
            checkpoint
        )
        r007_authority = self._seq95_authority(seq94_raw, seq95_raw)
        return (
            checkpoint,
            seq94_raw,
            seq95_raw,
            old_correction,
            reanchor,
            r006_authority,
            r007_authority,
            event,
        )

    @staticmethod
    def _validate(
        checkpoint: dict,
        old_correction: mock.Mock,
        reanchor: mock.Mock,
        r006_authority: mock.Mock,
        r007_authority: mock.Mock,
        *,
        starter: mock.Mock | None = None,
        receipt_authority: tuple[dict, dict] | None = None,
    ) -> list[str]:
        if starter is None:
            starter = mock.Mock()
        with mock.patch.object(
            graph,
            "_fp048_r002_start_gate_contract_correction_authority",
            return_value=old_correction,
        ), mock.patch.object(
            graph,
            "_fp048_r002_branch_semantics_reanchor_authority",
            return_value=reanchor,
        ), mock.patch.object(
            graph,
            "_fp048_r002_r006_contract_correction_authority",
            return_value=r006_authority,
        ), mock.patch.object(
            graph,
            "_fp048_r002_r007_contract_correction_authority",
            return_value=r007_authority,
        ), mock.patch.object(
            graph,
            "_fp048_r002_r007_started_authority",
            return_value=starter,
        ), mock.patch.object(
            graph,
            "_fp048_r002_r007_receipt_authority",
            return_value=receipt_authority,
        ):
            return graph.validate_fp048_r002_seq91_96(ROOT, checkpoint)

    def test_seq95_exact_correction_reconstructs_frozen_seq94_and_stays_ready(
        self,
    ) -> None:
        (
            checkpoint,
            _seq94_raw,
            _seq95_raw,
            old_correction,
            reanchor,
            r006_authority,
            r007_authority,
            _event,
        ) = self._seq95_fixture()
        self.assertEqual(
            self._validate(
                checkpoint,
                old_correction,
                reanchor,
                r006_authority,
                r007_authority,
            ),
            [],
        )
        state = checkpoint["goal_execution"]
        self.assertEqual(state["goal_status"], "READY")
        self.assertEqual(list(state["status_by_goal"].values()).count("IN_PROGRESS"), 0)
        r007_authority.reconstructed_seq94_checkpoint_bytes.assert_called_once()
        r007_authority.canonical_frozen_seq94_checkpoint_bytes.assert_called_once()
        r006_authority.require_contract_corrected_checkpoint.assert_not_called()
        r006_authority.canonical_seq94_checkpoint_bytes.assert_not_called()

    def test_seq95_inverse_never_replays_dynamic_r006_review(self) -> None:
        (
            checkpoint,
            _seq94_raw,
            _seq95_raw,
            old_correction,
            reanchor,
            r006_authority,
            r007_authority,
            _event,
        ) = self._seq95_fixture()
        r006_authority.require_contract_corrected_checkpoint.side_effect = (
            AssertionError("dynamic R006 review replay is forbidden")
        )
        r006_authority.canonical_seq94_checkpoint_bytes.side_effect = (
            AssertionError("dynamic R006 canonical replay is forbidden")
        )
        self.assertEqual(
            self._validate(
                checkpoint,
                old_correction,
                reanchor,
                r006_authority,
                r007_authority,
            ),
            [],
        )
        r007_authority.canonical_frozen_seq94_checkpoint_bytes.assert_called_once()

    def test_seq95_tamper_fails_before_producer_only_acceptance(self) -> None:
        for mutation in (
            "extra_field",
            "receipt_path",
            "previous_contract",
            "status",
        ):
            with self.subTest(mutation=mutation):
                (
                    checkpoint,
                    _seq94_raw,
                    _seq95_raw,
                    old_correction,
                    reanchor,
                    r006_authority,
                    r007_authority,
                    event,
                ) = self._seq95_fixture()
                if mutation == "extra_field":
                    event["forged"] = True
                elif mutation == "receipt_path":
                    event["source_checkpoint_binding"][
                        "r006_preflight_attempt_004"
                    ]["receipt_path"] = "receipt.json"
                elif mutation == "previous_contract":
                    event["contract_supersession"][
                        "previous_contract_binding"
                    ]["contract_id"] = "FORGED"
                else:
                    event["to_status"] = "IN_PROGRESS"
                event["event_sha256"] = graph.continuation.event_sha256(event)
                checkpoint["goal_execution"][
                    "transition_history_anchor_sha256"
                ] = event["event_sha256"]
                self.assertEqual(
                    self._validate(
                        checkpoint,
                        old_correction,
                        reanchor,
                        r006_authority,
                        r007_authority,
                    ),
                    [
                        "FP048 R002 seq95 R007 contract-correction event "
                        "differs"
                    ],
                )
                r007_authority.require_contract_corrected_checkpoint.assert_not_called()

    def test_seq95_rejects_nonexact_frozen_seq94_inverse(self) -> None:
        (
            checkpoint,
            _seq94_raw,
            _seq95_raw,
            old_correction,
            reanchor,
            r006_authority,
            r007_authority,
            _event,
        ) = self._seq95_fixture()
        r007_authority.reconstructed_seq94_checkpoint_bytes.return_value = b"{}"
        errors = self._validate(
            checkpoint,
            old_correction,
            reanchor,
            r006_authority,
            r007_authority,
        )
        self.assertTrue(errors)
        self.assertIn("seq95 R007 correction authority differs", errors[0])

    def test_seq95_frontier_tamper_fails_after_producer_acceptance(self) -> None:
        (
            checkpoint,
            _seq94_raw,
            _seq95_raw,
            old_correction,
            reanchor,
            r006_authority,
            r007_authority,
            _event,
        ) = self._seq95_fixture()
        checkpoint["goal_execution"]["ready_frontier_goal_ids"] = [
            "WS-FORGED"
        ]
        self.assertEqual(
            self._validate(
                checkpoint,
                old_correction,
                reanchor,
                r006_authority,
                r007_authority,
            ),
            ["FP048 R002 seq95 READY zero-credit projection differs"],
        )
        r007_authority.require_contract_corrected_checkpoint.assert_called_once()

    def test_r007_receipt_requires_private_canonical_five_check_pass(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            contract_path = root / graph.FP048_R002_R007_CONTRACT_PATH
            contract_path.parent.mkdir(parents=True)
            shutil.copyfile(
                ROOT / graph.FP048_R002_R007_CONTRACT_PATH,
                contract_path,
            )
            contract = json.loads(contract_path.read_text(encoding="utf-8"))
            commands = {
                row["check_id"]: row["command"]
                for row in contract["ordered_checks"]
            }
            receipt_path = (
                root / graph.FP048_R002_R007_STARTED_GATE_RECEIPT_PATH
            )
            receipt_path.parent.mkdir(parents=True)
            source_raw = b"exact seq95 source\n"
            correction = {
                "event_sha256": "a" * 64,
                "source_ready_event_binding": {"event_sha256": "b" * 64},
            }
            repository_snapshot = {"head_commit": "c" * 40}
            runs = [
                {
                    "check_id": check_id,
                    "command": commands[check_id],
                    "executed_at": f"2026-08-26T17:40:0{index}+09:00",
                    "exit_code": 0,
                    "output_path": (
                        "docs/control/execution/goal-gates/"
                        f"{graph.FP048_R002_R007_STARTED_EVENT_ID}/"
                        f"{index:02d}-{check_id}.log"
                    ),
                    "output_sha256": f"{index:x}" * 64,
                }
                for index, check_id in enumerate(
                    graph.FP048_R002_R007_EXPECTED_CHECK_IDS,
                    start=1,
                )
            ]
            receipt = {
                "schema_version": "1.1",
                "document_id": graph.FP048_R002_R007_STARTED_GATE_DOCUMENT_ID,
                "evidence_type": "IMPLEMENTATION_START_OR_RESUME_GATE",
                "gate_purpose": "INITIAL_START",
                "status": "PASS",
                "package_id": graph.V24_PACKAGE_ID,
                "target_transition_event_id": (
                    graph.FP048_R002_R007_STARTED_EVENT_ID
                ),
                "target_goal_id": graph.FP046_R002_NEXT_GOAL_ID,
                "target_goal_content_sha256": (
                    graph.FP048_R002_CORRECTED_STARTED_GOAL_SHA256
                ),
                "static_plan_manifest_sha256": (
                    graph.FP048_R002_CORRECTED_STARTED_MANIFEST_SHA256
                ),
                "source_activation_event_sha256": correction["event_sha256"],
                "source_checkpoint_sha256": (
                    graph.continuation.sha256_bytes(source_raw)
                ),
                "source_ready_event_sha256": "b" * 64,
                "check_command_contract_version": (
                    graph.FP048_R002_R007_CONTRACT_VERSION
                ),
                "check_command_contract_sha256": (
                    graph.FP048_R002_R007_CONTRACT_CANONICAL_SHA256
                ),
                "implementation_start_gate_contract_binding": (
                    self._r007_contract()
                ),
                "runtime_bindings": [],
                "execution_window": {
                    "started_at": "2026-08-26T17:40:00+09:00",
                    "ended_at": "2026-08-26T17:40:06+09:00",
                },
                "check_runs": runs,
                "repository_snapshot": repository_snapshot,
                "generated_at": "2026-08-26T17:40:07+09:00",
            }

            def publish(*, canonical: bool = True, mode: int = 0o600) -> dict:
                if canonical:
                    raw = (
                        json.dumps(receipt, ensure_ascii=False, indent=2) + "\n"
                    ).encode()
                else:
                    raw = json.dumps(receipt, ensure_ascii=False).encode()
                receipt_path.write_bytes(raw)
                receipt_path.chmod(mode)
                return {
                    "document_id": (
                        graph.FP048_R002_R007_STARTED_GATE_DOCUMENT_ID
                    ),
                    "path": graph.FP048_R002_R007_STARTED_GATE_RECEIPT_PATH,
                    "file_sha256": graph.continuation.sha256_bytes(raw),
                }

            started = {
                "implementation_start_gate_binding": publish(),
                "repository_snapshot_before": repository_snapshot,
            }
            self.assertIsNotNone(
                graph._fp048_r002_r007_receipt_authority(
                    root,
                    correction,
                    started,
                    source_raw,
                )
            )
            for mutation in ("mode", "canonical", "check_id"):
                with self.subTest(mutation=mutation):
                    receipt["check_runs"][0]["check_id"] = (
                        "FORGED" if mutation == "check_id" else "CONTINUATION"
                    )
                    started["implementation_start_gate_binding"] = publish(
                        canonical=mutation != "canonical",
                        mode=0o644 if mutation == "mode" else 0o600,
                    )
                    self.assertIsNone(
                        graph._fp048_r002_r007_receipt_authority(
                            root,
                            correction,
                            started,
                            source_raw,
                        )
                    )

    def test_legacy_direct_seq96_r007_start_bypass_is_rejected(self) -> None:
        (
            checkpoint,
            _seq94_raw,
            seq95_raw,
            old_correction,
            reanchor,
            r006_authority,
            r007_authority,
            _correction,
        ) = self._seq95_fixture()
        receipt_binding = {
            "document_id": graph.FP048_R002_R007_STARTED_GATE_DOCUMENT_ID,
            "path": graph.FP048_R002_R007_STARTED_GATE_RECEIPT_PATH,
            "file_sha256": "e" * 64,
        }
        receipt = {"repository_snapshot": {"head_commit": "a" * 40}}
        self._append_seq96(
            checkpoint,
            receipt_binding,
            receipt["repository_snapshot"],
        )
        starter = self._seq96_authority(seq95_raw, receipt_binding)
        with mock.patch.object(
            graph,
            "_fp048_r002_r008_contract_correction_authority",
            side_effect=AssertionError("R008 correction authority loaded"),
        ), mock.patch.object(
            graph,
            "_fp048_r002_r008_started_authority",
            side_effect=AssertionError("R008 starter authority loaded"),
        ):
            errors = self._validate(
                checkpoint,
                old_correction,
                reanchor,
                r006_authority,
                r007_authority,
                starter=starter,
                receipt_authority=(receipt_binding, receipt),
            )
        self.assertEqual(
            errors,
            ["FP048 R002 legacy R007 direct seq96 GOAL_STARTED is forbidden"],
        )
        starter.require_exact_seq96_projection.assert_not_called()


class Fp048R002Seq96CorrectionSeq97Tests(
    Fp048R002Seq95CorrectionSeq96Tests
):
    REANCHOR_TEST_PATH = (
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
        "MainActivity.kt"
    )

    @classmethod
    def _seal_repository_context(cls, checkpoint: dict) -> str:
        authority = graph._fp048_r002_r008_contract_correction_authority()
        paths = sorted(
            path.as_posix()
            for path in authority.NONCREDIT_FP023_PRODUCT_PATHS
        )
        path_set_sha256, content_set_sha256 = (
            graph.continuation.working_snapshot_hashes(ROOT, paths)
        )
        digest = graph.continuation.sha256_file(
            ROOT / cls.REANCHOR_TEST_PATH
        )
        snapshot = checkpoint["working_tree_snapshot"]
        snapshot.update(
            {
                "managed_changed_paths": paths,
                "managed_changed_path_count": len(paths),
                "path_set_sha256": path_set_sha256,
                "content_set_sha256": content_set_sha256,
            }
        )
        handoff = checkpoint["session_handoff"]
        handoff["changed_files"] = paths
        mirror = handoff["source_commit_or_snapshot"]
        mirror.update(
            {
                "file_count": len(paths),
                "path_set_sha256": path_set_sha256,
                "content_set_sha256": content_set_sha256,
            }
        )
        correction = checkpoint["goal_execution"]["transition_history"][95]
        prior_after = correction["repository_context_reanchor"]["after"]
        correction["repository_context_reanchor"]["after"] = {
            "base_commit": snapshot["base_head"],
            "branch": prior_after["logical_branch"],
            "logical_branch": prior_after["logical_branch"],
            "logical_branch_semantics": prior_after[
                "logical_branch_semantics"
            ],
            "physical_git_branch": prior_after["physical_git_branch"],
            "branch_mismatch_reason_code": prior_after[
                "branch_mismatch_reason_code"
            ],
            "current_head": mirror["current_head"],
            "managed_changed_path_count": len(paths),
            "path_set_sha256": path_set_sha256,
            "content_set_sha256": content_set_sha256,
        }
        correction["event_sha256"] = graph.continuation.event_sha256(
            correction
        )
        state = checkpoint["goal_execution"]
        state["transition_history_anchor_sha256"] = correction[
            "event_sha256"
        ]
        return digest

    @classmethod
    def _repository_context_correction_authority(cls) -> mock.Mock:
        source = graph._fp048_r002_r008_contract_correction_authority()
        authority = mock.Mock()
        authority.NONCREDIT_FP023_PRODUCT_PATHS = (
            source.NONCREDIT_FP023_PRODUCT_PATHS
        )
        authority.noncredit_fp023_product_successor_bindings.return_value = {
            "authority_label": "NONCREDIT_REPOSITORY_CONTEXT_ONLY",
            "bindings": [
                {
                    "path": path.as_posix(),
                    "sha256": graph.continuation.sha256_file(ROOT / path),
                    "byte_length": (ROOT / path).stat().st_size,
                }
                for path in source.NONCREDIT_FP023_PRODUCT_PATHS
            ],
            "credit_boundary": {
                "actual_device_test_credit_delta": 0,
                "deployment_credit_delta": 0,
                "external_review_credit_delta": 0,
                "formal_test_credit_delta": 0,
                "implementation_completion_credit_delta": 0,
                "release_credit_delta": 0,
            },
        }
        return authority

    @staticmethod
    def _r008_contract() -> dict:
        return graph._fp048_r002_r008_contract_binding()

    @staticmethod
    def _r008_runner() -> dict:
        return {
            "path": graph.FP048_R002_R008_START_GATE_RUNNER_PATH,
            "sha256": graph.FP048_R002_R008_START_GATE_RUNNER_SHA256,
            "byte_length": graph.FP048_R002_R008_START_GATE_RUNNER_BYTE_LENGTH,
        }

    @classmethod
    def _append_seq96_correction(cls, checkpoint: dict) -> dict:
        state = checkpoint["goal_execution"]
        source_event = state["transition_history"][94]
        source = source_event["source_checkpoint_binding"]
        occurred_at = (
            datetime.fromisoformat(source_event["occurred_at"])
            + timedelta(seconds=1)
        )
        event = copy.deepcopy(source_event)
        event.update(
            {
                "sequence": graph.FP048_R002_R008_CONTRACT_CORRECTION_SEQUENCE,
                "event_id": graph.FP048_R002_R008_CONTRACT_CORRECTION_EVENT_ID,
                "event_type": "GOAL_START_GATE_CONTRACT_CORRECTED",
                "occurred_on": occurred_at.date().isoformat(),
                "occurred_at": occurred_at.isoformat(),
                "from_status": "READY",
                "to_status": "READY",
                "status_changes": {},
                "runtime_after": copy.deepcopy(source_event["runtime_after"]),
                "blockers_after": copy.deepcopy(source_event["blockers_after"]),
                "blocker_resolution_ids_after": copy.deepcopy(
                    source_event["blocker_resolution_ids_after"]
                ),
                "source_checkpoint_version": checkpoint["schema_version"],
                "evidence_refs": [
                    "FP048-R002_EXACT_PUBLISHED_SEQ95_SOURCE",
                    "FP048-R002_SEQ95_R002_FROZEN_REVIEW_AUTHORITY",
                    "FP048-R002_R007_ROOT_REGRESSION_PREVIEW_NONAUTHORITY",
                    "FP048-R002_R008_STAGE_AWARE_GATE_CONTRACT",
                    "FP048-R002_SEQ96_97_TRANSITION_CONTROL_REVIEW",
                ],
                "source_checkpoint_binding": {
                    "path": graph.V24_CHECKPOINT_RELATIVE.as_posix(),
                    "sha256": "3" * 64,
                    "byte_length": 3,
                    "sequence": graph.FP048_R002_R007_CONTRACT_CORRECTION_SEQUENCE,
                    "tail_event_id": graph.FP048_R002_R007_CONTRACT_CORRECTION_EVENT_ID,
                    "tail_event_sha256": source_event["event_sha256"],
                    "passed_gate_attempt_003": copy.deepcopy(
                        source["passed_gate_attempt_003"]
                    ),
                    "preflight_attempt_004": copy.deepcopy(
                        source["preflight_attempt_004"]
                    ),
                    "r006_preflight_attempt_004": copy.deepcopy(
                        source["r006_preflight_attempt_004"]
                    ),
                    "r007_preflight_attempt_004": copy.deepcopy(
                        graph.FP048_R002_R007_PREFLIGHT_ATTEMPT
                    ),
                },
                "source_ready_event_binding": copy.deepcopy(
                    source_event["source_ready_event_binding"]
                ),
                "contract_supersession": {
                    "previous_contract_binding": graph._fp048_r002_r007_contract_binding(),
                    "reason_code": graph.FP048_R002_R008_SUCCESSOR_REASON_CODE,
                    "replacement_contract_binding": cls._r008_contract(),
                },
                "start_gate_runner_binding": cls._r008_runner(),
                "noncredit_successor_edges": copy.deepcopy(
                    source_event["noncredit_successor_edges"]
                ),
                "claim_boundary": copy.deepcopy(
                    graph.FP048_R002_CONTROL_REANCHOR_CLAIM_BOUNDARY
                ),
                "canonical_binding_snapshot_after": copy.deepcopy(
                    source_event["canonical_binding_snapshot_after"]
                ),
                "correction_reason": copy.deepcopy(
                    graph.FP048_R002_R008_CORRECTION_REASON
                ),
                "previous_event_sha256": source_event["event_sha256"],
            }
        )
        if set(event) != graph.FP048_R002_ZERO_CREDIT_CONTROL_EVENT_FIELDS:
            raise AssertionError("seq96 correction fixture field set differs")
        event["event_sha256"] = graph.continuation.event_sha256(event)
        state["transition_history"].append(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]
        state["validation_cutoff_at"] = event["occurred_at"]
        return event

    @classmethod
    def _append_seq97(
        cls,
        checkpoint: dict,
        receipt_binding: dict,
    ) -> dict:
        state = checkpoint["goal_execution"]
        correction = state["transition_history"][95]
        occurred_at = (
            datetime.fromisoformat(correction["occurred_at"])
            + timedelta(seconds=1)
        )
        event = {
            field: None
            for field in graph.continuation.V24_FIRST_START_EVENT_FIELDS
        }
        event.update(
            {
                "sequence": graph.FP048_R002_R008_STARTED_SEQUENCE,
                "event_id": graph.FP048_R002_R008_STARTED_EVENT_ID,
                "event_type": "GOAL_STARTED",
                "occurred_on": occurred_at.date().isoformat(),
                "occurred_at": occurred_at.isoformat(),
                "previous_focus_goal_id": graph.FP046_R002_NEXT_GOAL_ID,
                "previous_focus_content_sha256": graph.FP048_R002_CORRECTED_STARTED_GOAL_SHA256,
                "focus_goal_id": graph.FP046_R002_NEXT_GOAL_ID,
                "focus_goal_content_sha256": graph.FP048_R002_CORRECTED_STARTED_GOAL_SHA256,
                "subject_goal_id": graph.FP046_R002_NEXT_GOAL_ID,
                "from_status": "READY",
                "to_status": "IN_PROGRESS",
                "static_plan_manifest_sha256": graph.FP048_R002_CORRECTED_STARTED_MANIFEST_SHA256,
                "status_changes": {
                    graph.FP046_R002_NEXT_GOAL_ID: "IN_PROGRESS"
                },
                "runtime_after": copy.deepcopy(correction["runtime_after"]),
                "repository_snapshot_before": {"head_commit": "a" * 40},
                "implementation_start_gate_binding": copy.deepcopy(
                    receipt_binding
                ),
                "blockers_after": copy.deepcopy(correction["blockers_after"]),
                "blocker_resolution_ids_after": copy.deepcopy(
                    correction["blocker_resolution_ids_after"]
                ),
                "source_checkpoint_version": checkpoint["schema_version"],
                "evidence_refs": [],
                "previous_event_sha256": correction["event_sha256"],
            }
        )
        event["event_sha256"] = graph.continuation.event_sha256(event)
        state["transition_history"].append(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]
        state["validation_cutoff_at"] = event["occurred_at"]
        state["status_by_goal"][graph.FP046_R002_NEXT_GOAL_ID] = "IN_PROGRESS"
        state["goal_status"] = "IN_PROGRESS"
        checkpoint["current_work"]["status"] = "IN_PROGRESS"
        return event

    @classmethod
    def _r008_correction_authority(
        cls,
        seq95_raw: bytes,
        seq96_raw: bytes,
    ) -> mock.Mock:
        authority = mock.Mock()
        authority.require_contract_corrected_checkpoint.return_value = None
        authority.canonical_frozen_seq95_checkpoint_bytes.return_value = seq95_raw
        authority.canonical_seq96_checkpoint_bytes.return_value = seq96_raw
        authority.reconstructed_seq95_checkpoint_bytes.return_value = seq95_raw
        authority.reconstructed_seq96_checkpoint_bytes.return_value = seq96_raw
        authority.r007_contract_binding.return_value = cls._r007_contract()
        authority.r008_contract_binding.return_value = cls._r008_contract()
        authority.r008_runner_binding.return_value = cls._r008_runner()
        return authority

    @classmethod
    def _seq97_authority(
        cls,
        seq96_raw: bytes,
        receipt_binding: dict | None,
    ) -> mock.Mock:
        authority = mock.Mock()
        authority.require_exact_seq97_projection.return_value = None
        authority.reconstructed_seq96_checkpoint_bytes.return_value = seq96_raw
        authority.goal_start_gate_receipt_binding.return_value = copy.deepcopy(
            receipt_binding
        )
        authority.require_published_r008_gate_for_seq96.return_value = (
            copy.deepcopy(receipt_binding)
        )
        return authority

    @classmethod
    def _gate_authority(cls) -> mock.Mock:
        authority = mock.Mock()
        authority.expected_r008_binding.return_value = cls._r008_contract()
        authority.expected_preflight_attempt_004_binding.return_value = (
            copy.deepcopy(graph.FP048_R002_R007_PREFLIGHT_ATTEMPT)
        )
        bound = mock.Mock(contract_binding=cls._r008_contract())
        authority.bind_contract_corrected_source.return_value = bound
        authority.bind_published_contract_corrected_source.return_value = bound
        return authority

    def _seq96_fixture(self) -> tuple:
        (
            checkpoint,
            _seq94_raw,
            seq95_raw,
            old_correction,
            reanchor,
            r006_authority,
            r007_authority,
            _event,
        ) = super()._seq95_fixture()
        event = self._append_seq96_correction(checkpoint)
        seq96_raw = Fp048R002Seq93BranchSemanticsSeq94Tests._canonical(
            checkpoint
        )
        return (
            checkpoint,
            seq95_raw,
            seq96_raw,
            old_correction,
            reanchor,
            r006_authority,
            r007_authority,
            self._r008_correction_authority(seq95_raw, seq96_raw),
            event,
        )

    @staticmethod
    def _validate_r008(
        checkpoint: dict,
        old_correction: mock.Mock,
        reanchor: mock.Mock,
        r006_authority: mock.Mock,
        r007_authority: mock.Mock,
        r008_authority: mock.Mock,
        starter: mock.Mock,
        gate_authority: mock.Mock,
        *,
        gate_namespace_present: bool = False,
    ) -> list[str]:
        with mock.patch.object(
            graph,
            "_fp048_r002_start_gate_contract_correction_authority",
            return_value=old_correction,
        ), mock.patch.object(
            graph,
            "_fp048_r002_branch_semantics_reanchor_authority",
            return_value=reanchor,
        ), mock.patch.object(
            graph,
            "_fp048_r002_r006_contract_correction_authority",
            return_value=r006_authority,
        ), mock.patch.object(
            graph,
            "_fp048_r002_r007_contract_correction_authority",
            return_value=r007_authority,
        ), mock.patch.object(
            graph,
            "_fp048_r002_r008_contract_correction_authority",
            return_value=r008_authority,
        ), mock.patch.object(
            graph,
            "_fp048_r002_r008_started_authority",
            return_value=starter,
        ), mock.patch.object(
            graph,
            "_fp048_r002_r008_gate_authority",
            return_value=gate_authority,
        ), mock.patch.object(
            graph,
            "_fp048_r002_r008_gate_namespace_present",
            return_value=gate_namespace_present,
        ):
            return graph.validate_fp048_r002_seq91_97(ROOT, checkpoint)

    def test_seq96_exact_r007_to_r008_correction_stays_ready(self) -> None:
        (
            checkpoint,
            seq95_raw,
            _seq96_raw,
            old_correction,
            reanchor,
            r006_authority,
            r007_authority,
            r008_authority,
            _event,
        ) = self._seq96_fixture()
        starter = self._seq97_authority(b"", None)
        gate_authority = self._gate_authority()
        self.assertEqual(
            self._validate_r008(
                checkpoint,
                old_correction,
                reanchor,
                r006_authority,
                r007_authority,
                r008_authority,
                starter,
                gate_authority,
            ),
            [],
        )
        state = checkpoint["goal_execution"]
        self.assertEqual(state["goal_status"], "READY")
        self.assertNotIn("IN_PROGRESS", state["status_by_goal"].values())
        r008_authority.canonical_frozen_seq95_checkpoint_bytes.assert_called_once()
        self.assertEqual(
            r008_authority.reconstructed_seq95_checkpoint_bytes.return_value,
            seq95_raw,
        )
        gate_authority.bind_contract_corrected_source.assert_called_once()
        starter.require_published_r008_gate_for_seq96.assert_not_called()
        r007_authority.require_contract_corrected_checkpoint.assert_not_called()

    def test_seq96_uses_exact_repository_context_and_legacy_seq97_does_not(
        self,
    ) -> None:
        checkpoint = self._seq96_fixture()[0]
        digest = self._seal_repository_context(checkpoint)
        correction_authority = (
            self._repository_context_correction_authority()
        )
        expected_live = {
            binding["path"]: binding["sha256"]
            for binding in (
                correction_authority
                .noncredit_fp023_product_successor_bindings.return_value[
                    "bindings"
                ]
            )
        }
        started_authority = mock.Mock()
        with mock.patch.object(
            graph,
            "_fp048_r002_r008_contract_correction_authority",
            return_value=correction_authority,
        ), mock.patch.object(
            graph,
            "_fp048_r002_r008_started_authority",
            return_value=started_authority,
        ):
            self.assertEqual(
                graph._fp048_r002_repository_context_live_successors(
                    ROOT,
                    checkpoint,
                ),
                expected_live,
            )
            self.assertEqual(
                graph._compose_fp048_r002_reviewed_noncredit_successors(
                    ROOT,
                    checkpoint,
                    {
                        self.REANCHOR_TEST_PATH: (
                            "0" * 64,
                            "7cf49ad23b268ebd52809055879b734d81e7cf7215c44c94f9196b20d5b4e865",
                        )
                    },
                ),
                {self.REANCHOR_TEST_PATH: ("0" * 64, digest)},
            )

            self._append_seq97(
                checkpoint,
                {
                    "document_id": graph.FP048_R002_R008_STARTED_GATE_DOCUMENT_ID,
                    "path": graph.FP048_R002_R008_STARTED_GATE_RECEIPT_PATH,
                    "file_sha256": "e" * 64,
                },
            )
            self.assertIsNone(
                graph._fp048_r002_repository_context_live_successors(
                    ROOT,
                    checkpoint,
                )
            )
        self.assertEqual(
            correction_authority.require_contract_corrected_checkpoint.call_count,
            2,
        )
        self.assertTrue(
            all(
                call.args[0] == ROOT
                and call.kwargs
                == {
                    "require_live_snapshot": False,
                    "run_external_validators": False,
                }
                for call in (
                    correction_authority
                    .require_contract_corrected_checkpoint.call_args_list
                )
            )
        )
        started_authority.require_started_checkpoint.assert_not_called()

    def test_repository_context_preserves_historical_successor_composition(
        self,
    ) -> None:
        checkpoint = self._seq96_fixture()[0]
        self._seal_repository_context(checkpoint)
        correction_authority = (
            self._repository_context_correction_authority()
        )
        product_paths = {
            path.as_posix()
            for path in correction_authority.NONCREDIT_FP023_PRODUCT_PATHS
        }
        edge_sets = (
            graph._fp048_r002_seq85_to_seq87_successor_edges(
                ROOT,
                checkpoint,
            ),
            graph._fp048_r002_noncredit_successor_edges(ROOT, checkpoint),
        )
        chains: dict[str, list[str]] = {}
        for edge_set in edge_sets:
            self.assertIsNotNone(edge_set)
            for row in edge_set["modified"]:
                relative = row["path"]
                if relative in product_paths:
                    continue
                before = row["predecessor"]["sha256"]
                after = row["successor"]["sha256"]
                chain = chains.setdefault(relative, [before, before])
                if chain[1] == before:
                    chain[1] = after
        relative, (before, after) = next(
            (relative, chain)
            for relative, chain in chains.items()
            if chain[0] != chain[1]
        )
        with mock.patch.object(
            graph,
            "_fp048_r002_r008_contract_correction_authority",
            return_value=correction_authority,
        ):
            self.assertEqual(
                graph._compose_fp048_r002_reviewed_noncredit_successors(
                    ROOT,
                    checkpoint,
                    {relative: ("0" * 64, before)},
                ),
                {relative: ("0" * 64, after)},
            )

    def test_repository_context_live_successor_tamper_fails_closed(self) -> None:
        for mutation in (
            "event",
            "event_hash",
            "state",
            "snapshot",
            "content_set",
            "reviewed_product",
            "stage",
        ):
            with self.subTest(mutation=mutation):
                checkpoint = self._seq96_fixture()[0]
                self._seal_repository_context(checkpoint)
                state = checkpoint["goal_execution"]
                correction = state["transition_history"][95]
                if mutation == "event":
                    correction["event_id"] = "FORGED"
                    correction["event_sha256"] = (
                        graph.continuation.event_sha256(correction)
                    )
                    state["transition_history_anchor_sha256"] = correction[
                        "event_sha256"
                    ]
                elif mutation == "event_hash":
                    correction["event_sha256"] = "0" * 64
                    state["transition_history_anchor_sha256"] = "0" * 64
                elif mutation == "state":
                    state["goal_status"] = "IN_PROGRESS"
                elif mutation == "snapshot":
                    checkpoint["session_handoff"]["changed_files"] = []
                elif mutation == "content_set":
                    checkpoint["working_tree_snapshot"][
                        "content_set_sha256"
                    ] = "f" * 64
                    checkpoint["session_handoff"][
                        "source_commit_or_snapshot"
                    ]["content_set_sha256"] = "f" * 64
                    correction["repository_context_reanchor"]["after"][
                        "content_set_sha256"
                    ] = "f" * 64
                    correction["event_sha256"] = (
                        graph.continuation.event_sha256(correction)
                    )
                    state["transition_history_anchor_sha256"] = correction[
                        "event_sha256"
                    ]
                elif mutation == "reviewed_product":
                    pass
                else:
                    state["transition_history"].append(copy.deepcopy(correction))
                authority = self._repository_context_correction_authority()
                if mutation == "reviewed_product":
                    authority.noncredit_fp023_product_successor_bindings.return_value[
                        "bindings"
                    ][0]["sha256"] = "0" * 64
                with mock.patch.object(
                    graph,
                    "_fp048_r002_r008_contract_correction_authority",
                    return_value=authority,
                ):
                    self.assertIsNone(
                        graph._fp048_r002_repository_context_live_successors(
                            ROOT,
                            checkpoint,
                        )
                    )

    def test_repository_context_requires_full_physical_stage_authority(
        self,
    ) -> None:
        for failure in (
            "physical review triad deleted",
            "physical review self-resealed",
            "seq95 inverse CAS differs",
        ):
            with self.subTest(failure=failure):
                checkpoint = self._seq96_fixture()[0]
                self._seal_repository_context(checkpoint)
                authority = self._repository_context_correction_authority()
                authority.require_contract_corrected_checkpoint.side_effect = (
                    RuntimeError(failure)
                )
                with mock.patch.object(
                    graph,
                    "_fp048_r002_r008_contract_correction_authority",
                    return_value=authority,
                ):
                    self.assertIsNone(
                        graph._fp048_r002_repository_context_live_successors(
                            ROOT,
                            checkpoint,
                        )
                    )

        checkpoint = self._seq96_fixture()[0]
        self._seal_repository_context(checkpoint)
        self._append_seq97(
            checkpoint,
            {
                "document_id": graph.FP048_R002_R008_STARTED_GATE_DOCUMENT_ID,
                "path": graph.FP048_R002_R008_STARTED_GATE_RECEIPT_PATH,
                "file_sha256": "e" * 64,
            },
        )
        authority = mock.Mock()
        authority.require_started_checkpoint.side_effect = RuntimeError(
            "seq97 receipt differs"
        )
        correction_authority = (
            self._repository_context_correction_authority()
        )
        with mock.patch.object(
            graph,
            "_fp048_r002_r008_started_authority",
            return_value=authority,
        ), mock.patch.object(
            graph,
            "_fp048_r002_r008_contract_correction_authority",
            return_value=correction_authority,
        ):
            self.assertIsNone(
                graph._fp048_r002_repository_context_live_successors(
                    ROOT,
                    checkpoint,
                )
            )

        authority.require_started_checkpoint.assert_not_called()

    def test_repository_context_warm_cache_rechecks_reviewed_product_size(
        self,
    ) -> None:
        checkpoint = self._seq96_fixture()[0]
        self._seal_repository_context(checkpoint)
        authority = self._repository_context_correction_authority()
        with mock.patch.object(
            graph,
            "_fp048_r002_r008_contract_correction_authority",
            return_value=authority,
        ):
            self.assertIsNotNone(
                graph._fp048_r002_repository_context_live_successors(
                    ROOT,
                    checkpoint,
                )
            )
            reviewed = (
                authority
                .noncredit_fp023_product_successor_bindings.return_value
            )
            reviewed["bindings"][0]["byte_length"] += 1
            self.assertIsNone(
                graph._fp048_r002_repository_context_live_successors(
                    ROOT,
                    checkpoint,
                )
            )
        self.assertEqual(
            authority.require_contract_corrected_checkpoint.call_count,
            2,
        )

    def test_legacy_direct_seq96_has_no_repository_context_authority(
        self,
    ) -> None:
        checkpoint, *_rest = super()._seq95_fixture()
        self._append_seq96(
            checkpoint,
            {
                "document_id": graph.FP048_R002_R007_STARTED_GATE_DOCUMENT_ID,
                "path": graph.FP048_R002_R007_STARTED_GATE_RECEIPT_PATH,
                "file_sha256": "e" * 64,
            },
            {"head_commit": "a" * 40},
        )
        self.assertIsNone(
            graph._fp048_r002_repository_context_live_successors(
                ROOT,
                checkpoint,
            )
        )

    def test_seq96_projected_checkpoint_does_not_read_live_seq95(self) -> None:
        (
            checkpoint,
            _seq95_raw,
            _seq96_raw,
            old_correction,
            reanchor,
            r006_authority,
            r007_authority,
            r008_authority,
            _event,
        ) = self._seq96_fixture()
        starter = self._seq97_authority(b"", None)
        starter.require_published_r008_gate_for_seq96.side_effect = (
            AssertionError("projected seq96 must not read the live checkpoint")
        )
        gate_authority = self._gate_authority()

        self.assertEqual(
            self._validate_r008(
                checkpoint,
                old_correction,
                reanchor,
                r006_authority,
                r007_authority,
                r008_authority,
                starter,
                gate_authority,
            ),
            [],
        )
        gate_authority.bind_contract_corrected_source.assert_called_once_with(
            ROOT,
            checkpoint,
            require_gate_namespace_absent=True,
        )
        gate_authority.bind_published_contract_corrected_source.assert_not_called()
        starter.require_published_r008_gate_for_seq96.assert_not_called()

    def test_seq96_published_namespace_uses_only_live_cas_helper(self) -> None:
        (
            checkpoint,
            _seq95_raw,
            _seq96_raw,
            old_correction,
            reanchor,
            r006_authority,
            r007_authority,
            r008_authority,
            _event,
        ) = self._seq96_fixture()
        receipt_binding = {
            "document_id": graph.FP048_R002_R008_STARTED_GATE_DOCUMENT_ID,
            "path": graph.FP048_R002_R008_STARTED_GATE_RECEIPT_PATH,
            "file_sha256": "e" * 64,
        }
        starter = self._seq97_authority(b"", receipt_binding)
        gate_authority = self._gate_authority()

        self.assertEqual(
            self._validate_r008(
                checkpoint,
                old_correction,
                reanchor,
                r006_authority,
                r007_authority,
                r008_authority,
                starter,
                gate_authority,
                gate_namespace_present=True,
            ),
            [],
        )
        starter.require_published_r008_gate_for_seq96.assert_called_once_with(
            ROOT,
            checkpoint,
        )
        gate_authority.bind_published_contract_corrected_source.assert_called_once_with(
            ROOT,
            checkpoint,
        )
        gate_authority.bind_contract_corrected_source.assert_not_called()
        r008_authority.require_contract_corrected_checkpoint.assert_called_once_with(
            ROOT,
            checkpoint,
            require_live_snapshot=False,
            run_external_validators=False,
        )

    def test_seq96_correction_uses_sealed_product_successor_composition(
        self,
    ) -> None:
        checkpoint = self._seq96_fixture()[0]
        with mock.patch.object(
            graph,
            "_fp048_r002_reviewed_noncredit_edge",
            side_effect=AssertionError(
                "READY correction reopened current product successors"
            ),
        ):
            transitions = graph._fp022_completion_start_to_final_transitions(
                ROOT,
                checkpoint,
                require_completion_review=False,
            )

        self.assertIsInstance(transitions, dict)
        self.assertEqual(len(transitions), 9)

        checkpoint = self._seq96_fixture()[0]
        self._append_seq97(
            checkpoint,
            {
                "document_id": graph.FP048_R002_R008_STARTED_GATE_DOCUMENT_ID,
                "path": graph.FP048_R002_R008_STARTED_GATE_RECEIPT_PATH,
                "file_sha256": "e" * 64,
            },
        )
        self.assertIsNone(
            graph._fp048_r002_repository_context_live_successors(
                ROOT,
                checkpoint,
            )
        )

        checkpoint = self._seq96_fixture()[0]
        correction = checkpoint["goal_execution"]["transition_history"][-1]
        correction["to_status"] = "IN_PROGRESS"
        correction["event_sha256"] = graph.continuation.event_sha256(
            correction
        )
        with mock.patch.object(
            graph,
            "_fp048_r002_reviewed_noncredit_edge",
            side_effect=AssertionError("non-correction must compose live"),
        ), self.assertRaisesRegex(AssertionError, "must compose live"):
            graph._fp022_completion_start_to_final_transitions(
                ROOT,
                checkpoint,
                require_completion_review=False,
            )

    def test_seq96_static_tamper_fails_before_r008_producer_acceptance(self) -> None:
        for mutation in ("sequence_type", "preflight_type", "runner", "status", "extra"):
            with self.subTest(mutation=mutation):
                (
                    checkpoint,
                    _seq95_raw,
                    _seq96_raw,
                    old_correction,
                    reanchor,
                    r006_authority,
                    r007_authority,
                    r008_authority,
                    event,
                ) = self._seq96_fixture()
                if mutation == "sequence_type":
                    event["sequence"] = 96.0
                elif mutation == "preflight_type":
                    event["source_checkpoint_binding"][
                        "r007_preflight_attempt_004"
                    ]["exit_code"] = 1.0
                elif mutation == "runner":
                    event["start_gate_runner_binding"]["sha256"] = "0" * 64
                elif mutation == "status":
                    event["to_status"] = "IN_PROGRESS"
                else:
                    event["forged"] = True
                event["event_sha256"] = graph.continuation.event_sha256(event)
                checkpoint["goal_execution"][
                    "transition_history_anchor_sha256"
                ] = event["event_sha256"]
                errors = self._validate_r008(
                    checkpoint,
                    old_correction,
                    reanchor,
                    r006_authority,
                    r007_authority,
                    r008_authority,
                    self._seq97_authority(b"", None),
                    self._gate_authority(),
                )
                self.assertTrue(errors)
                r008_authority.require_contract_corrected_checkpoint.assert_not_called()

    def test_seq96_rejects_nonexact_frozen_seq95_inverse(self) -> None:
        (
            checkpoint,
            _seq95_raw,
            _seq96_raw,
            old_correction,
            reanchor,
            r006_authority,
            r007_authority,
            r008_authority,
            _event,
        ) = self._seq96_fixture()
        r008_authority.reconstructed_seq95_checkpoint_bytes.return_value = b"{}"
        errors = self._validate_r008(
            checkpoint,
            old_correction,
            reanchor,
            r006_authority,
            r007_authority,
            r008_authority,
            self._seq97_authority(b"", None),
            self._gate_authority(),
        )
        self.assertTrue(errors)
        self.assertIn("seq96 R008 correction authority differs", errors[0])

    def test_legacy_direct_seq97_r008_start_is_rejected(self) -> None:
        (
            checkpoint,
            _seq95_raw,
            seq96_raw,
            old_correction,
            reanchor,
            r006_authority,
            r007_authority,
            r008_authority,
            correction,
        ) = self._seq96_fixture()
        receipt_binding = {
            "document_id": graph.FP048_R002_R008_STARTED_GATE_DOCUMENT_ID,
            "path": graph.FP048_R002_R008_STARTED_GATE_RECEIPT_PATH,
            "file_sha256": "e" * 64,
        }
        started = self._append_seq97(checkpoint, receipt_binding)
        starter = self._seq97_authority(seq96_raw, receipt_binding)
        gate_authority = self._gate_authority()
        self.assertEqual(
            self._validate_r008(
                checkpoint,
                old_correction,
                reanchor,
                r006_authority,
                r007_authority,
                r008_authority,
                starter,
                gate_authority,
            ),
            ["FP048 R002 legacy R008 direct seq97 GOAL_STARTED is forbidden"],
        )
        self.assertEqual(
            started["previous_event_sha256"],
            correction["event_sha256"],
        )
        self.assertEqual(
            list(
                checkpoint["goal_execution"]["status_by_goal"].values()
            ).count("IN_PROGRESS"),
            1,
        )
        starter.require_exact_seq97_projection.assert_not_called()
        gate_authority.bind_published_contract_corrected_source.assert_not_called()
        r007_authority.require_contract_corrected_checkpoint.assert_not_called()

    def test_seq97_tamper_and_receipt_disagreement_fail_closed(self) -> None:
        for mutation in ("adjacency", "status", "frontier", "receipt"):
            with self.subTest(mutation=mutation):
                (
                    checkpoint,
                    _seq95_raw,
                    seq96_raw,
                    old_correction,
                    reanchor,
                    r006_authority,
                    r007_authority,
                    r008_authority,
                    _correction,
                ) = self._seq96_fixture()
                receipt_binding = {
                    "document_id": graph.FP048_R002_R008_STARTED_GATE_DOCUMENT_ID,
                    "path": graph.FP048_R002_R008_STARTED_GATE_RECEIPT_PATH,
                    "file_sha256": "e" * 64,
                }
                started = self._append_seq97(checkpoint, receipt_binding)
                starter = self._seq97_authority(seq96_raw, receipt_binding)
                if mutation == "adjacency":
                    started["previous_event_sha256"] = "0" * 64
                    started["event_sha256"] = graph.continuation.event_sha256(
                        started
                    )
                    checkpoint["goal_execution"][
                        "transition_history_anchor_sha256"
                    ] = started["event_sha256"]
                elif mutation == "status":
                    checkpoint["goal_execution"]["status_by_goal"][
                        "WS-FORGED"
                    ] = "IN_PROGRESS"
                elif mutation == "frontier":
                    checkpoint["goal_execution"][
                        "ready_frontier_goal_ids"
                    ] = ["WS-FORGED"]
                else:
                    starter.require_published_r008_gate_for_seq96.return_value = {
                        **receipt_binding,
                        "file_sha256": "f" * 64,
                    }
                errors = self._validate_r008(
                    checkpoint,
                    old_correction,
                    reanchor,
                    r006_authority,
                    r007_authority,
                    r008_authority,
                    starter,
                    self._gate_authority(),
                )
                self.assertTrue(errors)
                self.assertEqual(
                    errors,
                    [
                        "FP048 R002 legacy R008 direct seq97 GOAL_STARTED "
                        "is forbidden"
                    ],
                )

    def test_r008_authority_loaders_require_public_apis(self) -> None:
        for loader, label in (
            (
                graph._fp048_r002_r008_contract_correction_authority,
                "contract-correction",
            ),
            (graph._fp048_r002_r008_started_authority, "started"),
            (graph._fp048_r002_r008_gate_authority, "gate"),
        ):
            with self.subTest(label=label), mock.patch.object(
                graph.importlib,
                "import_module",
                return_value=object(),
            ):
                with self.assertRaisesRegex(RuntimeError, "API is missing"):
                    loader()


class Fp048R002Seq97CorrectionSeq98Tests(unittest.TestCase):
    HYGIENE_TEST_REL = Path(
        "tests/test_apply_walksafe_fp048_r002_start_gate_contract_correction_"
        "seq96_20260826.py"
    )

    @staticmethod
    def _canonical(checkpoint: dict) -> bytes:
        return (
            json.dumps(checkpoint, ensure_ascii=False, indent=2) + "\n"
        ).encode("utf-8")

    @classmethod
    def _append_seq97_correction(cls, checkpoint: dict) -> dict:
        state = checkpoint["goal_execution"]
        source = state["transition_history"][95]
        occurred_at = (
            datetime.fromisoformat(source["occurred_at"])
            + timedelta(seconds=1)
        )
        event = copy.deepcopy(source)
        source_binding = {
            "path": graph.V24_CHECKPOINT_RELATIVE.as_posix(),
            "sha256": "4" * 64,
            "byte_length": 4,
            "sequence": graph.FP048_R002_R008_CONTRACT_CORRECTION_SEQUENCE,
            "tail_event_id": graph.FP048_R002_R008_CONTRACT_CORRECTION_EVENT_ID,
            "tail_event_sha256": source["event_sha256"],
            "r008_preflight_attempt_004": {"exit_code": 2},
        }
        event.update(
            {
                "sequence": graph.FP048_R002_R009_CONTRACT_CORRECTION_SEQUENCE,
                "event_id": graph.FP048_R002_R009_CONTRACT_CORRECTION_EVENT_ID,
                "event_type": "GOAL_START_GATE_CONTRACT_CORRECTED",
                "occurred_on": occurred_at.date().isoformat(),
                "occurred_at": occurred_at.isoformat(),
                "from_status": "READY",
                "to_status": "READY",
                "status_changes": {},
                "runtime_after": copy.deepcopy(source["runtime_after"]),
                "blockers_after": copy.deepcopy(source["blockers_after"]),
                "blocker_resolution_ids_after": copy.deepcopy(
                    source["blocker_resolution_ids_after"]
                ),
                "source_checkpoint_version": checkpoint["schema_version"],
                "evidence_refs": [
                    "FP048-R002_EXACT_PUBLISHED_SEQ96_SOURCE",
                    "FP048-R002_R008_SNAPSHOT_ANCESTOR_PREVIEW_NONAUTHORITY",
                    "FP048-R002_R009_SNAPSHOT_SAFE_GATE_CONTRACT",
                    "FP048-R002_SEQ97_98_TRANSITION_CONTROL_REVIEW",
                ],
                "source_checkpoint_binding": source_binding,
                "authorization_binding": {
                    "path": "docs/control/execution/workstream-transitions/"
                    "seq97-98/authorization.json",
                    "sha256": "a" * 64,
                    "byte_length": 1,
                },
                "contract_supersession": {
                    "previous_contract_binding": copy.deepcopy(
                        source["contract_supersession"][
                            "replacement_contract_binding"
                        ]
                    ),
                    "reason_code": "R008_RETAINED_ANCESTOR_IDENTITY_CHANGED",
                    "replacement_contract_binding": {
                        "schema_version": "1.2",
                        "document_id": "WS-FP048-R002-INITIAL-START-GATE-"
                        "CONTRACT-20260826-009",
                        "path": "docs/control/execution/goal-contracts/"
                        "WS-GOAL-EPIC-03-FP-048-R002/"
                        "initial-start-gate-contract-r009.json",
                        "file_sha256": "b" * 64,
                        "contract_id": "WS-FP048-R002-INTERNAL-START-GATE-R009",
                        "contract_version": "2026-08-26.8",
                        "canonical_contract_sha256": "c" * 64,
                    },
                },
                "start_gate_runner_binding": {
                    "path": "scripts/run_walksafe_fp048_r002_goal_start_gate_"
                    "r009_20260826.py",
                    "sha256": "d" * 64,
                    "byte_length": 1,
                },
                "transition_control_review_binding": {
                    role: {
                        "path": "docs/control/execution/workstream-transitions/"
                        f"seq97-98/review-rounds/R001/{name}.json",
                        "sha256": digest * 64,
                        "byte_length": 1,
                    }
                    for role, name, digest in (
                        ("assignment", "review-assignment", "e"),
                        ("review_result", "review-result", "f"),
                        ("independent_review", "independent-review", "1"),
                    )
                },
                "repository_context_reanchor": {
                    "before": {
                        **copy.deepcopy(source_binding),
                        "current_work": copy.deepcopy(checkpoint["current_work"]),
                        "working_tree_snapshot": copy.deepcopy(
                            checkpoint["working_tree_snapshot"]
                        ),
                        "session_handoff": copy.deepcopy(
                            checkpoint["session_handoff"]
                        ),
                    },
                    "after": copy.deepcopy(
                        source["repository_context_reanchor"]["after"]
                    ),
                },
                "claim_boundary": copy.deepcopy(
                    graph.FP048_R002_CONTROL_REANCHOR_CLAIM_BOUNDARY
                ),
                "correction_reason": {
                    "r008_preflight_attempt_004": {"exit_code": 2}
                },
                "previous_event_sha256": source["event_sha256"],
            }
        )
        if set(event) != graph.FP048_R002_ZERO_CREDIT_CONTROL_EVENT_FIELDS:
            raise AssertionError("seq97 correction fixture field set differs")
        event["event_sha256"] = graph.continuation.event_sha256(event)
        state["transition_history"].append(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]
        state["validation_cutoff_at"] = event["occurred_at"]
        return event

    @classmethod
    def _append_seq98_started(
        cls,
        checkpoint: dict,
        receipt_binding: dict,
    ) -> dict:
        state = checkpoint["goal_execution"]
        correction = state["transition_history"][96]
        occurred_at = (
            datetime.fromisoformat(correction["occurred_at"])
            + timedelta(seconds=1)
        )
        event = {
            field: None
            for field in graph.continuation.V24_FIRST_START_EVENT_FIELDS
        }
        event.update(
            {
                "sequence": graph.FP048_R002_R009_STARTED_SEQUENCE,
                "event_id": graph.FP048_R002_R009_STARTED_EVENT_ID,
                "event_type": "GOAL_STARTED",
                "occurred_on": occurred_at.date().isoformat(),
                "occurred_at": occurred_at.isoformat(),
                "previous_focus_goal_id": graph.FP046_R002_NEXT_GOAL_ID,
                "previous_focus_content_sha256": graph.FP048_R002_CORRECTED_STARTED_GOAL_SHA256,
                "focus_goal_id": graph.FP046_R002_NEXT_GOAL_ID,
                "focus_goal_content_sha256": graph.FP048_R002_CORRECTED_STARTED_GOAL_SHA256,
                "subject_goal_id": graph.FP046_R002_NEXT_GOAL_ID,
                "from_status": "READY",
                "to_status": "IN_PROGRESS",
                "static_plan_manifest_sha256": graph.FP048_R002_CORRECTED_STARTED_MANIFEST_SHA256,
                "status_changes": {
                    graph.FP046_R002_NEXT_GOAL_ID: "IN_PROGRESS"
                },
                "runtime_after": copy.deepcopy(correction["runtime_after"]),
                "repository_snapshot_before": {"head_commit": "a" * 40},
                "implementation_start_gate_binding": copy.deepcopy(
                    receipt_binding
                ),
                "blockers_after": copy.deepcopy(correction["blockers_after"]),
                "blocker_resolution_ids_after": copy.deepcopy(
                    correction["blocker_resolution_ids_after"]
                ),
                "source_checkpoint_version": checkpoint["schema_version"],
                "evidence_refs": [],
                "previous_event_sha256": correction["event_sha256"],
            }
        )
        event["event_sha256"] = graph.continuation.event_sha256(event)
        state["transition_history"].append(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]
        state["validation_cutoff_at"] = event["occurred_at"]
        state["status_by_goal"][graph.FP046_R002_NEXT_GOAL_ID] = "IN_PROGRESS"
        state["goal_status"] = "IN_PROGRESS"
        checkpoint["current_work"]["status"] = "IN_PROGRESS"
        return event

    @classmethod
    def _correction_authority(
        cls,
        seq96_raw: bytes,
        seq97_raw: bytes,
    ) -> mock.Mock:
        authority = mock.Mock()
        authority.CORRECTED_SEQ96_TEST_REL = cls.HYGIENE_TEST_REL
        authority.REVIEWED_CONTROL_PATHS = (
            snapshot_hygiene_correction.REVIEWED_CONTROL_PATHS
        )
        authority.require_snapshot_hygiene_corrected_checkpoint.return_value = None
        authority.reconstructed_seq96_checkpoint_bytes.return_value = seq96_raw
        authority.canonical_seq97_checkpoint_bytes.return_value = seq97_raw
        authority.noncredit_snapshot_hygiene_successor_binding.return_value = {
            "authority_label": "NONCREDIT_SNAPSHOT_HYGIENE_CONTROL_ONLY",
            "binding": {
                "path": cls.HYGIENE_TEST_REL.as_posix(),
                "sha256": graph.continuation.sha256_file(
                    ROOT / cls.HYGIENE_TEST_REL
                ),
                "byte_length": (ROOT / cls.HYGIENE_TEST_REL).stat().st_size,
            },
            "credit_boundary": {
                "actual_device_test_credit_delta": 0,
                "deployment_credit_delta": 0,
                "external_review_credit_delta": 0,
                "formal_test_credit_delta": 0,
                "implementation_completion_credit_delta": 0,
                "release_credit_delta": 0,
            },
        }
        authority.noncredit_reviewed_control_successor_bindings.return_value = {
            "authority_label": "NONCREDIT_REVIEWED_CONTROL_CONTEXT_ONLY",
            "bindings": [
                {
                    "path": relative.as_posix(),
                    "sha256": graph.continuation.sha256_file(ROOT / relative),
                    "byte_length": (ROOT / relative).stat().st_size,
                }
                for relative in authority.REVIEWED_CONTROL_PATHS
            ],
            "credit_boundary": {
                "actual_device_test_credit_delta": 0,
                "deployment_credit_delta": 0,
                "external_review_credit_delta": 0,
                "formal_test_credit_delta": 0,
                "implementation_completion_credit_delta": 0,
                "release_credit_delta": 0,
            },
        }
        return authority

    @staticmethod
    def _started_authority(
        seq97_raw: bytes,
        receipt_binding: dict,
    ) -> mock.Mock:
        authority = mock.Mock()
        authority.require_started_checkpoint.return_value = None
        authority.reconstructed_seq97_checkpoint_bytes.return_value = seq97_raw
        authority.goal_start_gate_receipt_binding.return_value = copy.deepcopy(
            receipt_binding
        )
        authority.require_published_r009_gate_for_seq97.return_value = mock.Mock(
            receipt_binding=copy.deepcopy(receipt_binding)
        )
        return authority

    def _fixture(self) -> tuple:
        harness = Fp048R002Seq96CorrectionSeq97Tests(methodName="runTest")
        (
            checkpoint,
            _seq95_raw,
            seq96_raw,
            old_correction,
            reanchor,
            r006_authority,
            r007_authority,
            r008_authority,
            _r008_event,
        ) = harness._seq96_fixture()
        self._append_seq97_correction(checkpoint)
        seq97_raw = self._canonical(checkpoint)
        return (
            checkpoint,
            seq96_raw,
            seq97_raw,
            old_correction,
            reanchor,
            r006_authority,
            r007_authority,
            r008_authority,
            self._correction_authority(seq96_raw, seq97_raw),
            harness,
        )

    @staticmethod
    def _validate(
        fixture: tuple,
        starter: mock.Mock | None = None,
    ) -> list[str]:
        (
            checkpoint,
            _seq96_raw,
            _seq97_raw,
            old_correction,
            reanchor,
            r006_authority,
            r007_authority,
            r008_authority,
            r009_authority,
            harness,
        ) = fixture
        with mock.patch.object(
            graph,
            "_fp048_r002_r009_contract_correction_authority",
            return_value=r009_authority,
        ), mock.patch.object(
            graph,
            "_fp048_r002_r009_started_authority",
            return_value=starter or mock.Mock(),
        ):
            return harness._validate_r008(
                checkpoint,
                old_correction,
                reanchor,
                r006_authority,
                r007_authority,
                r008_authority,
                harness._seq97_authority(b"", None),
                harness._gate_authority(),
            )

    def _seal_context(self, fixture: tuple) -> dict[str, str]:
        checkpoint, *_rest, r009_authority, _harness = fixture
        r008_authority = (
            Fp048R002Seq96CorrectionSeq97Tests
            ._repository_context_correction_authority()
        )
        paths = sorted(
            {
                *(path.as_posix() for path in r008_authority.NONCREDIT_FP023_PRODUCT_PATHS),
                *(
                    path.as_posix()
                    for path in r009_authority.REVIEWED_CONTROL_PATHS
                ),
            }
        )
        path_set, content_set = graph.continuation.working_snapshot_hashes(
            ROOT,
            paths,
        )
        snapshot = checkpoint["working_tree_snapshot"]
        snapshot.update(
            {
                "managed_changed_paths": paths,
                "managed_changed_path_count": len(paths),
                "path_set_sha256": path_set,
                "content_set_sha256": content_set,
            }
        )
        handoff = checkpoint["session_handoff"]
        handoff["changed_files"] = paths
        mirror = handoff["source_commit_or_snapshot"]
        mirror.update(
            {
                "file_count": len(paths),
                "path_set_sha256": path_set,
                "content_set_sha256": content_set,
            }
        )
        event = checkpoint["goal_execution"]["transition_history"][96]
        after = event["repository_context_reanchor"]["after"]
        after.update(
            {
                "base_commit": snapshot["base_head"],
                "branch": after["logical_branch"],
                "current_head": mirror["current_head"],
                "managed_changed_path_count": len(paths),
                "path_set_sha256": path_set,
                "content_set_sha256": content_set,
            }
        )
        event["event_sha256"] = graph.continuation.event_sha256(event)
        state = checkpoint["goal_execution"]
        state["transition_history_anchor_sha256"] = event["event_sha256"]
        state["validation_cutoff_at"] = event["occurred_at"]
        expected = {
            binding["path"]: binding["sha256"]
            for binding in r008_authority
            .noncredit_fp023_product_successor_bindings.return_value["bindings"]
        }
        hygiene = r009_authority
        hygiene_binding = (
            hygiene.noncredit_snapshot_hygiene_successor_binding.return_value[
                "binding"
            ]
        )
        controls = (
            hygiene.noncredit_reviewed_control_successor_bindings.return_value[
                "bindings"
            ]
        )
        expected.update(
            {binding["path"]: binding["sha256"] for binding in controls}
        )
        self.assertEqual(expected[hygiene_binding["path"]], hygiene_binding["sha256"])
        return expected

    def test_exact_seq97_correction_stays_ready_and_reconstructs_seq96(self) -> None:
        fixture = self._fixture()
        checkpoint, seq96_raw, *_rest, r009_authority, _harness = fixture
        self.assertEqual(self._validate(fixture), [])
        self.assertEqual(checkpoint["goal_execution"]["goal_status"], "READY")
        self.assertNotIn(
            "IN_PROGRESS",
            checkpoint["goal_execution"]["status_by_goal"].values(),
        )
        r009_authority.require_snapshot_hygiene_corrected_checkpoint.assert_called_once_with(
            ROOT,
            checkpoint,
            require_live_snapshot=True,
            run_external_validators=False,
        )
        self.assertEqual(
            r009_authority.reconstructed_seq96_checkpoint_bytes.return_value,
            seq96_raw,
        )

    def test_seq97_event_state_and_inverse_tamper_fail_closed(self) -> None:
        for mutation in ("event", "state", "inverse", "review_authority"):
            with self.subTest(mutation=mutation):
                fixture = self._fixture()
                checkpoint, seq96_raw, *_rest, r009_authority, _harness = fixture
                event = checkpoint["goal_execution"]["transition_history"][96]
                if mutation == "event":
                    event["sequence"] = 97.0
                elif mutation == "state":
                    checkpoint["goal_execution"]["goal_status"] = "IN_PROGRESS"
                elif mutation == "inverse":
                    r009_authority.reconstructed_seq96_checkpoint_bytes.return_value = (
                        seq96_raw + b" "
                    )
                else:
                    r009_authority.require_snapshot_hygiene_corrected_checkpoint.side_effect = RuntimeError(
                        "R009 failure observation or independent review differs"
                    )
                if mutation == "event":
                    event["event_sha256"] = graph.continuation.event_sha256(event)
                    checkpoint["goal_execution"][
                        "transition_history_anchor_sha256"
                    ] = event["event_sha256"]
                self.assertTrue(self._validate(fixture))

    def test_seq97_self_resealed_snapshot_hash_tamper_fails_closed(self) -> None:
        fixture = self._fixture()
        checkpoint, *_rest, r009_authority, _harness = fixture
        self._seal_context(fixture)
        snapshot = checkpoint["working_tree_snapshot"]
        snapshot["content_set_sha256"] = "0" * 64
        checkpoint["session_handoff"]["source_commit_or_snapshot"][
            "content_set_sha256"
        ] = "0" * 64
        event = checkpoint["goal_execution"]["transition_history"][96]
        event["repository_context_reanchor"]["after"][
            "content_set_sha256"
        ] = "0" * 64
        event["event_sha256"] = graph.continuation.event_sha256(event)
        state = checkpoint["goal_execution"]
        state["transition_history_anchor_sha256"] = event["event_sha256"]
        r008_authority = (
            Fp048R002Seq96CorrectionSeq97Tests
            ._repository_context_correction_authority()
        )
        with mock.patch.object(
            graph,
            "_fp048_r002_r008_contract_correction_authority",
            return_value=r008_authority,
        ), mock.patch.object(
            graph,
            "_fp048_r002_r009_contract_correction_authority",
            return_value=r009_authority,
        ):
            self.assertIsNone(
                graph._fp048_r002_repository_context_live_successors(
                    ROOT,
                    checkpoint,
                )
            )

    def test_exact_seq98_consumes_only_fresh_r009_receipt(self) -> None:
        fixture = self._fixture()
        checkpoint, _seq96_raw, seq97_raw, *_rest = fixture
        receipt_binding = {
            "document_id": "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-"
            "FP048-R002-20260826-004",
            "path": "docs/control/execution/goal-gates/"
            f"{graph.FP048_R002_R009_STARTED_EVENT_ID}/"
            "implementation-start-gate-receipt.json",
            "file_sha256": "9" * 64,
        }
        self._append_seq98_started(checkpoint, receipt_binding)
        starter = self._started_authority(seq97_raw, receipt_binding)
        self.assertEqual(self._validate(fixture, starter), [])
        starter.require_started_checkpoint.assert_called_once_with(
            ROOT,
            checkpoint,
            require_live_snapshot=True,
            run_external_validators=False,
        )
        self.assertEqual(
            checkpoint["approved_state"]["release_status"],
            "NOT_ELIGIBLE",
        )

    def test_seq98_receipt_inverse_and_credit_tamper_fail_closed(self) -> None:
        for mutation in ("receipt", "inverse", "credit"):
            with self.subTest(mutation=mutation):
                fixture = self._fixture()
                checkpoint, _seq96_raw, seq97_raw, *_rest = fixture
                receipt_binding = {
                    "document_id": "R009",
                    "path": "r009/implementation-start-gate-receipt.json",
                    "file_sha256": "9" * 64,
                }
                self._append_seq98_started(checkpoint, receipt_binding)
                starter = self._started_authority(seq97_raw, receipt_binding)
                if mutation == "receipt":
                    starter.require_published_r009_gate_for_seq97.return_value = (
                        mock.Mock(
                            receipt_binding={
                                **receipt_binding,
                                "file_sha256": "0" * 64,
                            }
                        )
                    )
                elif mutation == "inverse":
                    starter.reconstructed_seq97_checkpoint_bytes.return_value = (
                        seq97_raw + b" "
                    )
                else:
                    checkpoint["approved_state"]["release_status"] = "ELIGIBLE"
                self.assertTrue(self._validate(fixture, starter))

    def test_seq97_and_seq98_terminal_overlay_is_exact_reviewed_zero_credit(
        self,
    ) -> None:
        fixture = self._fixture()
        checkpoint, _seq96_raw, seq97_raw, *_rest, r009_authority, _harness = fixture
        expected = self._seal_context(fixture)
        r008_authority = (
            Fp048R002Seq96CorrectionSeq97Tests
            ._repository_context_correction_authority()
        )
        starter = self._started_authority(seq97_raw, {"path": "r009"})
        with mock.patch.object(
            graph,
            "_fp048_r002_r008_contract_correction_authority",
            return_value=r008_authority,
        ), mock.patch.object(
            graph,
            "_fp048_r002_r009_contract_correction_authority",
            return_value=r009_authority,
        ), mock.patch.object(
            graph,
            "_fp048_r002_r009_started_authority",
            return_value=starter,
        ):
            self.assertEqual(
                graph._fp048_r002_repository_context_live_successors(
                    ROOT,
                    checkpoint,
                ),
                expected,
            )
            self.assertEqual(len(expected), 29)
            self._append_seq98_started(checkpoint, {"path": "r009"})
            self.assertEqual(
                graph._fp048_r002_repository_context_live_successors(
                    ROOT,
                    checkpoint,
                ),
                expected,
            )
            controls = (
                r009_authority
                .noncredit_reviewed_control_successor_bindings.return_value[
                    "bindings"
                ]
            )
            controls[0]["byte_length"] += 1
            self.assertIsNone(
                graph._fp048_r002_repository_context_live_successors(
                    ROOT,
                    checkpoint,
                )
            )
            controls[0]["byte_length"] -= 1
            hygiene = (
                r009_authority
                .noncredit_snapshot_hygiene_successor_binding.return_value
            )
            hygiene["binding"]["byte_length"] += 1
            self.assertIsNone(
                graph._fp048_r002_repository_context_live_successors(
                    ROOT,
                    checkpoint,
                )
            )

    def test_r009_authority_loaders_require_public_apis(self) -> None:
        for loader in (
            graph._fp048_r002_r009_contract_correction_authority,
            graph._fp048_r002_r009_started_authority,
        ):
            with self.subTest(loader=loader.__name__), mock.patch.object(
                graph.importlib,
                "import_module",
                return_value=object(),
            ):
                with self.assertRaisesRegex(RuntimeError, "API is missing"):
                    loader()


class Fp048R002CompletionSeq99Seq100Tests(unittest.TestCase):
    @staticmethod
    def _checkpoint_bytes(value: dict) -> bytes:
        return (
            json.dumps(value, ensure_ascii=False, indent=2) + "\n"
        ).encode("utf-8")

    def _fixture(self) -> tuple:
        harness = Fp048R002Seq97CorrectionSeq98Tests(methodName="runTest")
        start_fixture = harness._fixture()
        checkpoint = start_fixture[0]
        harness._seal_context(start_fixture)
        receipt_binding = {
            "document_id": "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-"
            "FP048-R002-20260826-004",
            "path": "docs/control/execution/goal-gates/"
            f"{graph.FP048_R002_R009_STARTED_EVENT_ID}/"
            "implementation-start-gate-receipt.json",
            "file_sha256": "e" * 64,
        }
        harness._append_seq98_started(checkpoint, receipt_binding)
        source = copy.deepcopy(checkpoint)
        source_raw = self._checkpoint_bytes(source)
        source_tail = source["goal_execution"]["transition_history"][-1]
        update_at = datetime.fromisoformat(source_tail["occurred_at"]) + timedelta(
            seconds=1
        )
        update = {
            "sequence": graph.FP048_R002_COMPLETION_EVIDENCE_SEQUENCE,
            "event_id": graph.FP048_R002_COMPLETION_EVIDENCE_EVENT_ID,
            "event_type": "CANONICAL_BINDINGS_UPDATED",
            "occurred_on": update_at.date().isoformat(),
            "occurred_at": update_at.isoformat(),
            "previous_event_sha256": source_tail["event_sha256"],
            "source_checkpoint_binding": {
                "path": graph.V24_CHECKPOINT_RELATIVE.as_posix(),
                "sha256": hashlib.sha256(source_raw).hexdigest(),
                "byte_length": len(source_raw),
            },
        }
        update["event_sha256"] = graph.continuation.event_sha256(update)
        completion_at = update_at + timedelta(seconds=1)
        completion = {
            "sequence": graph.FP048_R002_COMPLETION_SEQUENCE,
            "event_id": graph.FP048_R002_COMPLETION_EVENT_ID,
            "event_type": "GOAL_COMPLETED",
            "occurred_on": completion_at.date().isoformat(),
            "occurred_at": completion_at.isoformat(),
            "previous_event_sha256": update["event_sha256"],
            "canonical_update_event_sha256": update["event_sha256"],
        }
        completion["event_sha256"] = graph.continuation.event_sha256(completion)
        state = checkpoint["goal_execution"]
        state["transition_history"].extend((update, completion))
        state["transition_history_anchor_sha256"] = completion["event_sha256"]
        state["validation_cutoff_at"] = completion["occurred_at"]
        review_source_binding = copy.deepcopy(update["source_checkpoint_binding"])
        authority = mock.Mock()
        authority.CHECKPOINT_REL = graph.V24_CHECKPOINT_RELATIVE
        authority.started_authority = mock.Mock()
        authority.reconstructed_seq98_checkpoint_bytes.return_value = source_raw
        authority.strict_json.side_effect = lambda raw, _label: json.loads(raw)
        authority.checkpoint_bytes.side_effect = self._checkpoint_bytes
        authority.sha256_bytes.side_effect = lambda raw: hashlib.sha256(raw).hexdigest()
        return (
            checkpoint,
            source,
            source_raw,
            review_source_binding,
            authority,
            start_fixture,
        )

    @staticmethod
    def _validate(
        checkpoint: dict,
        authority: mock.Mock,
        review_source_binding: dict,
    ) -> list[str]:
        with mock.patch.object(
            graph,
            "_fp048_r002_completion_authority",
            return_value=authority,
        ), mock.patch.object(
            graph,
            "_fp048_r002_r009_started_authority",
            return_value=authority.started_authority,
        ), mock.patch.object(
            graph,
            "_fp048_r002_completion_evidence_authority",
            return_value=(mock.sentinel.evidence, review_source_binding),
        ):
            return graph.validate_fp048_r002_completion_seq99_100(ROOT, checkpoint)

    def test_seq99_without_adjacent_seq100_is_rejected(self) -> None:
        checkpoint, *_rest = self._fixture()
        state = checkpoint["goal_execution"]
        state["transition_history"].pop()

        self.assertEqual(
            graph.validate_fp048_r002_completion_seq99_100(ROOT, checkpoint),
            [
                "FP048 R002 seq99 evidence transaction lacks adjacent "
                "seq100 completion"
            ],
        )

    def test_nonexact_seq101_descendant_is_rejected(self) -> None:
        checkpoint, *_rest = self._fixture()
        checkpoint["goal_execution"]["transition_history"].append(
            {"sequence": 101, "event_id": "FORGED"}
        )
        self.assertEqual(
            graph.validate_fp048_r002_completion_seq99_100(ROOT, checkpoint),
            ["FP048 R002 seq99/100 descendant dispatch differs"],
        )

    def test_exact_seq100_uses_public_inverse_and_projection_authority(self) -> None:
        (
            checkpoint,
            source,
            source_raw,
            review_source_binding,
            authority,
            _start_fixture,
        ) = self._fixture()

        self.assertEqual(
            self._validate(checkpoint, authority, review_source_binding),
            [],
        )
        authority.reconstructed_seq98_checkpoint_bytes.assert_called_once_with(
            ROOT,
            checkpoint,
        )
        authority.started_authority.require_started_checkpoint.assert_called_once_with(
            ROOT,
            source,
            require_live_snapshot=False,
            run_external_validators=False,
        )
        authority.validate_projection.assert_called_once_with(
            ROOT,
            source,
            checkpoint,
            mock.sentinel.evidence,
        )
        self.assertEqual(authority.checkpoint_bytes(source), source_raw)

    def test_seq98_source_cas_and_review_tail_tamper_fail_closed(self) -> None:
        for mutation in ("event_sha256", "event_length", "review_sha256", "review_length"):
            with self.subTest(mutation=mutation):
                (
                    checkpoint,
                    _source,
                    _source_raw,
                    review_source_binding,
                    authority,
                    *_rest,
                ) = self._fixture()
                update, completion = checkpoint["goal_execution"][
                    "transition_history"
                ][-2:]
                if mutation == "event_sha256":
                    update["source_checkpoint_binding"]["sha256"] = "0" * 64
                elif mutation == "event_length":
                    update["source_checkpoint_binding"]["byte_length"] += 1
                elif mutation == "review_sha256":
                    review_source_binding["sha256"] = "0" * 64
                else:
                    review_source_binding["byte_length"] += 1
                if mutation.startswith("event_"):
                    update["event_sha256"] = graph.continuation.event_sha256(update)
                    completion["previous_event_sha256"] = update["event_sha256"]
                    completion["canonical_update_event_sha256"] = update[
                        "event_sha256"
                    ]
                    completion["event_sha256"] = graph.continuation.event_sha256(
                        completion
                    )

                errors = self._validate(
                    checkpoint,
                    authority,
                    review_source_binding,
                )

                self.assertTrue(errors)
                self.assertIn("source CAS differs", errors[0])
                authority.validate_projection.assert_not_called()

    def test_exact_seq100_terminal_reconstructs_exact_seq98_authority(self) -> None:
        (
            checkpoint,
            _source,
            source_raw,
            _review_source_binding,
            authority,
            start_fixture,
        ) = self._fixture()
        r009_authority = start_fixture[8]
        r008_authority = (
            Fp048R002Seq96CorrectionSeq97Tests
            ._repository_context_correction_authority()
        )
        expected = {
            binding["path"]: binding["sha256"]
            for binding in r008_authority
            .noncredit_fp023_product_successor_bindings.return_value["bindings"]
        }
        hygiene = (
            r009_authority
            .noncredit_snapshot_hygiene_successor_binding.return_value[
                "binding"
            ]
        )
        controls = (
            r009_authority
            .noncredit_reviewed_control_successor_bindings.return_value[
                "bindings"
            ]
        )
        expected.update(
            {binding["path"]: binding["sha256"] for binding in controls}
        )
        self.assertEqual(expected[hygiene["path"]], hygiene["sha256"])
        with mock.patch.object(
            graph,
            "validate_fp048_r002_completion_seq99_100",
            return_value=[],
        ), mock.patch.object(
            graph,
            "_fp048_r002_completion_authority",
            return_value=authority,
        ), mock.patch.object(
            graph,
            "_fp048_r002_r008_contract_correction_authority",
            return_value=r008_authority,
        ), mock.patch.object(
            graph,
            "_fp048_r002_r009_contract_correction_authority",
            return_value=r009_authority,
        ), mock.patch.object(
            graph,
            "_fp048_r002_r009_started_authority",
            return_value=authority.started_authority,
        ):
            self.assertEqual(
                graph._fp048_r002_repository_context_live_successors(
                    ROOT,
                    checkpoint,
                ),
                expected,
            )
            authority.reconstructed_seq98_checkpoint_bytes.return_value = (
                source_raw + b" "
            )
            self.assertIsNone(
                graph._fp048_r002_repository_context_live_successors(
                    ROOT,
                    checkpoint,
                )
            )


class Fp048R002Seq98CorrectionSeq99Tests(unittest.TestCase):
    CORRECTION_TYPE = "GOAL_START_GATE_EXECUTION_CORRECTED"

    def test_runtime_mapping_guard_is_imported(self) -> None:
        self.assertTrue(isinstance({}, graph.Mapping))

    def test_seq98_live_phase_is_private_and_direct_drift_fails(self) -> None:
        authority = mock.Mock()
        graph._require_fp048_r002_seq98_execution_correction(
            ROOT,
            {},
            authority,
            historical_successor_source=True,
        )
        graph._require_fp048_r002_seq98_execution_correction(
            ROOT,
            {},
            authority,
            historical_successor_source=False,
        )
        self.assertEqual(
            [
                call.kwargs["require_live_snapshot"]
                for call in authority
                .require_start_gate_execution_corrected_checkpoint.call_args_list
            ],
            [False, True],
        )
        authority.require_start_gate_execution_corrected_checkpoint.side_effect = (
            RuntimeError("direct seq98 live drift")
        )
        with self.assertRaisesRegex(RuntimeError, "direct seq98 live drift"):
            graph._require_fp048_r002_seq98_execution_correction(
                ROOT,
                {},
                authority,
                historical_successor_source=False,
            )

    def test_reviewed_control_phase_overlay_keeps_active_digest(self) -> None:
        path = Path("control/shared.py")
        zero_credit = {
            "actual_device_test_credit_delta": 0,
            "deployment_credit_delta": 0,
            "external_review_credit_delta": 0,
            "formal_test_credit_delta": 0,
            "implementation_completion_credit_delta": 0,
            "release_credit_delta": 0,
        }

        def authority(digest: str) -> mock.Mock:
            value = mock.Mock()
            value.REVIEWED_CONTROL_PATHS = (path,)
            value.noncredit_reviewed_control_successor_bindings.return_value = {
                "authority_label": "NONCREDIT_REVIEWED_CONTROL_CONTEXT_ONLY",
                "bindings": [
                    {
                        "path": path.as_posix(),
                        "sha256": digest,
                        "byte_length": 3,
                    }
                ],
                "credit_boundary": copy.deepcopy(zero_credit),
            }
            return value

        historical = authority("1" * 64)
        active = authority("2" * 64)
        old_rows, active_rows = graph._fp048_r002_reviewed_control_phase_overlays(
            ROOT,
            historical,
            {"phase": "seq98"},
            active,
            {"phase": "seq99"},
            active_require_live_snapshot=True,
        )
        bindings: list[dict] = []
        for owner, rows in ((historical, old_rows), (active, active_rows)):
            overlaid = graph._fp048_r002_overlay_reviewed_control_successors(
                bindings,
                owner,
                rows,
            )
            self.assertIsNotNone(overlaid)
            bindings = overlaid[0]
        self.assertEqual(bindings[0]["sha256"], "2" * 64)
        self.assertFalse(
            historical.noncredit_reviewed_control_successor_bindings
            .call_args.kwargs["require_live_snapshot"]
        )
        self.assertTrue(
            active.noncredit_reviewed_control_successor_bindings
            .call_args.kwargs["require_live_snapshot"]
        )

    def test_reviewed_control_phase_overlay_marks_projection_nonlive(self) -> None:
        active = mock.Mock()
        active.noncredit_reviewed_control_successor_bindings.return_value = {}

        _historical_rows, active_rows = (
            graph._fp048_r002_reviewed_control_phase_overlays(
                ROOT,
                None,
                {"phase": "seq98"},
                active,
                {"phase": "seq99-projected"},
                active_require_live_snapshot=False,
            )
        )

        self.assertEqual(active_rows, {})
        self.assertFalse(
            active.noncredit_reviewed_control_successor_bindings
            .call_args.kwargs["require_live_snapshot"]
        )

    def test_required_repository_aggregate_failure_propagates(self) -> None:
        active = {
            "goal_execution": {
                "transition_history": [
                    {"sequence": sequence}
                    for sequence in range(
                        1,
                        graph.FP048_R002_R009_EXECUTION_CORRECTION_SEQUENCE
                        + 1,
                    )
                ]
            }
        }
        predecessor = {"control/shared.py": ("1" * 64, "1" * 64)}
        empty_edges = {"modified": []}
        with (
            mock.patch.object(
                graph,
                "_fp048_r002_seq85_to_seq87_successor_edges",
                return_value=empty_edges,
            ),
            mock.patch.object(
                graph,
                "_fp048_r002_noncredit_successor_edges",
                return_value=empty_edges,
            ),
            mock.patch.object(
                graph,
                "_fp048_r002_repository_context_live_successors",
                return_value=None,
            ),
        ):
            self.assertIsNone(
                graph._compose_fp048_r002_reviewed_noncredit_successors(
                    ROOT,
                    active,
                    predecessor,
                )
            )
            with mock.patch.object(
                graph,
                "_fp048_r002_control_reanchor_is_declared",
                return_value=True,
            ):
                self.assertEqual(
                    graph.validate_fp048_r002_reviewed_noncredit_successors(
                        ROOT,
                        active,
                    ),
                    [
                        "FP048 R002 reviewed noncredit successor authority differs"
                    ],
                )

        historical = copy.deepcopy(active)
        historical["goal_execution"]["transition_history"] = historical[
            "goal_execution"
        ]["transition_history"][:95]
        with (
            mock.patch.object(
                graph,
                "_fp048_r002_seq85_to_seq87_successor_edges",
                return_value=empty_edges,
            ),
            mock.patch.object(
                graph,
                "_fp048_r002_noncredit_successor_edges",
                return_value=empty_edges,
            ),
            mock.patch.object(
                graph,
                "_fp048_r002_repository_context_live_successors",
                return_value=None,
            ),
        ):
            self.assertEqual(
                graph._compose_fp048_r002_reviewed_noncredit_successors(
                    ROOT,
                    historical,
                    predecessor,
                ),
                predecessor,
            )

    @classmethod
    def _seq98(cls) -> tuple[dict, mock.Mock]:
        history = [
            {
                "sequence": sequence,
                "event_id": f"SYNTHETIC-{sequence}",
                "event_type": "SYNTHETIC",
                "event_sha256": f"{sequence % 10}" * 64,
            }
            for sequence in range(1, 98)
        ]
        source = history[-1]
        event = {
            "sequence": graph.FP048_R002_R009_EXECUTION_CORRECTION_SEQUENCE,
            "event_id": graph.FP048_R002_R009_EXECUTION_CORRECTION_EVENT_ID,
            "event_type": cls.CORRECTION_TYPE,
            "from_status": "READY",
            "to_status": "READY",
            "status_changes": {},
            "previous_event_sha256": source["event_sha256"],
        }
        event["event_sha256"] = graph.continuation.event_sha256(event)
        history.append(event)
        authority = mock.Mock()
        authority.CORRECTION_EVENT_TYPE = cls.CORRECTION_TYPE
        return {"goal_execution": {"transition_history": history}}, authority

    @staticmethod
    def _append_seq99(checkpoint: dict, receipt: dict) -> dict:
        history = checkpoint["goal_execution"]["transition_history"]
        correction = history[-1]
        event = {
            field: None for field in graph.continuation.V24_FIRST_START_EVENT_FIELDS
        }
        event.update(
            {
                "sequence": graph.FP048_R002_R010_STARTED_SEQUENCE,
                "event_id": graph.FP048_R002_R010_STARTED_EVENT_ID,
                "event_type": "GOAL_STARTED",
                "subject_goal_id": graph.FP046_R002_NEXT_GOAL_ID,
                "from_status": "READY",
                "to_status": "IN_PROGRESS",
                "status_changes": {
                    graph.FP046_R002_NEXT_GOAL_ID: "IN_PROGRESS"
                },
                "implementation_start_gate_binding": copy.deepcopy(receipt),
                "previous_event_sha256": correction["event_sha256"],
            }
        )
        event["event_sha256"] = graph.continuation.event_sha256(event)
        history.append(event)
        return event

    def test_exact_seq98_correction_and_seq99_start_dispatch(self) -> None:
        checkpoint, authority = self._seq98()
        correction = graph._fp048_r002_seq98_r010_dispatch_event(
            checkpoint,
            authority,
        )
        self.assertEqual(
            correction["event_id"],
            graph.FP048_R002_R009_EXECUTION_CORRECTION_EVENT_ID,
        )
        receipt = {"path": "r010/implementation-start-gate-receipt.json"}
        started = self._append_seq99(checkpoint, receipt)
        self.assertIs(
            graph._fp048_r002_seq99_r010_started_event(checkpoint, receipt),
            started,
        )

    def test_legacy_seq98_and_resealed_tamper_fail_closed(self) -> None:
        checkpoint, authority = self._seq98()
        checkpoint["goal_execution"]["transition_history"][-1] = {
            "sequence": graph.FP048_R002_R009_STARTED_SEQUENCE,
            "event_id": graph.FP048_R002_R009_STARTED_EVENT_ID,
            "event_type": "GOAL_STARTED",
        }
        with self.assertRaisesRegex(RuntimeError, "legacy direct seq98 R009"):
            graph._fp048_r002_seq98_r010_dispatch_event(checkpoint, authority)

        checkpoint, authority = self._seq98()
        event = checkpoint["goal_execution"]["transition_history"][-1]
        event["sequence"] = 98.0
        event["event_sha256"] = graph.continuation.event_sha256(event)
        with self.assertRaisesRegex(RuntimeError, "execution-correction"):
            graph._fp048_r002_seq98_r010_dispatch_event(checkpoint, authority)

    def test_new_authority_loaders_require_complete_public_surfaces(self) -> None:
        for loader in (
            graph._fp048_r002_r009_execution_correction_authority,
            graph._fp048_r002_r010_started_authority,
        ):
            with self.subTest(loader=loader.__name__), mock.patch.object(
                graph.importlib,
                "import_module",
                return_value=object(),
            ):
                with self.assertRaisesRegex(RuntimeError, "API is missing"):
                    loader()

    def test_reviewed_control_overlay_is_exact_and_zero_credit(self) -> None:
        authority = mock.Mock()
        authority.REVIEWED_CONTROL_PATHS = (
            Path("control/shared.py"),
            Path("control/successor.py"),
        )
        predecessor = [
            {
                "path": "control/shared.py",
                "sha256": "1" * 64,
                "byte_length": 1,
            },
            {
                "path": "product/kept.py",
                "sha256": "2" * 64,
                "byte_length": 2,
            },
        ]
        reviewed = {
            "authority_label": "NONCREDIT_REVIEWED_CONTROL_CONTEXT_ONLY",
            "bindings": [
                {
                    "path": "control/shared.py",
                    "sha256": "3" * 64,
                    "byte_length": 3,
                },
                {
                    "path": "control/successor.py",
                    "sha256": "4" * 64,
                    "byte_length": 4,
                },
            ],
            "credit_boundary": {
                "actual_device_test_credit_delta": 0,
                "deployment_credit_delta": 0,
                "external_review_credit_delta": 0,
                "formal_test_credit_delta": 0,
                "implementation_completion_credit_delta": 0,
                "release_credit_delta": 0,
            },
        }
        expected = [predecessor[1], *reviewed["bindings"]]
        self.assertEqual(
            graph._fp048_r002_overlay_reviewed_control_successors(
                predecessor,
                authority,
                reviewed,
            ),
            (expected, authority.REVIEWED_CONTROL_PATHS),
        )
        for mutation in ("credit", "path", "size"):
            with self.subTest(mutation=mutation):
                tampered = copy.deepcopy(reviewed)
                if mutation == "credit":
                    tampered["credit_boundary"]["release_credit_delta"] = True
                elif mutation == "path":
                    tampered["bindings"][0]["path"] = "control/forged.py"
                else:
                    tampered["bindings"][0]["byte_length"] = True
                self.assertIsNone(
                    graph._fp048_r002_overlay_reviewed_control_successors(
                        predecessor,
                        authority,
                        tampered,
                    )
                )


class Fp048R002SuccessorSeq99Seq102Tests(unittest.TestCase):
    @classmethod
    def _checkpoint(cls, length: int) -> dict[str, object]:
        history: list[dict[str, object]] = [
            {"sequence": sequence, "event_id": f"SYNTHETIC-{sequence}"}
            for sequence in range(1, length + 1)
        ]
        history[97] = {
            "sequence": 98,
            "event_id": graph.FP048_R002_R009_EXECUTION_CORRECTION_EVENT_ID,
            "event_type": "GOAL_START_GATE_EXECUTION_CORRECTED",
            "subject_goal_id": graph.FP046_R002_NEXT_GOAL_ID,
            "from_status": "READY",
            "to_status": "READY",
            "status_changes": {},
        }
        if length >= 99:
            history[98] = {
                "sequence": 99,
                "event_id": graph.FP048_R002_SUCCESSOR_CORRECTION_EVENT_ID,
                "event_type": "GOAL_START_GATE_EXECUTION_CORRECTED",
                "subject_goal_id": graph.FP046_R002_NEXT_GOAL_ID,
                "from_status": "READY",
                "to_status": "READY",
                "status_changes": {},
            }
        if length >= 100:
            history[99] = {
                "sequence": 100,
                "event_id": graph.FP048_R002_SUCCESSOR_STARTED_EVENT_ID,
                "event_type": "GOAL_STARTED",
                "subject_goal_id": graph.FP046_R002_NEXT_GOAL_ID,
                "from_status": "READY",
                "to_status": "IN_PROGRESS",
                "status_changes": {
                    graph.FP046_R002_NEXT_GOAL_ID: "IN_PROGRESS"
                },
            }
        if length >= 101:
            history[100] = {
                "sequence": 101,
                "event_id": graph.FP048_R002_SUCCESSOR_EVIDENCE_EVENT_ID,
                "event_type": "CANONICAL_BINDINGS_UPDATED",
                "produced_by_goal_id": graph.FP046_R002_NEXT_GOAL_ID,
                "from_status": "IN_PROGRESS",
                "to_status": "IN_PROGRESS",
                "status_changes": {},
            }
        if length >= 102:
            history[101] = {
                "sequence": 102,
                "event_id": graph.FP048_R002_SUCCESSOR_COMPLETION_EVENT_ID,
                "event_type": "GOAL_COMPLETED",
                "subject_goal_id": graph.FP046_R002_NEXT_GOAL_ID,
                "from_status": "IN_PROGRESS",
                "to_status": "COMPLETE_AT_TARGET",
                "status_changes": {
                    graph.FP046_R002_NEXT_GOAL_ID: "COMPLETE_AT_TARGET"
                },
            }
        return {"goal_execution": {"transition_history": history}}

    @staticmethod
    def _raw(checkpoint: dict[str, object]) -> bytes:
        return (
            json.dumps(checkpoint, ensure_ascii=False, indent=2) + "\n"
        ).encode("utf-8")

    def _authorities(self) -> tuple[mock.Mock, mock.Mock, mock.Mock]:
        correction = mock.Mock()
        correction.reconstructed_seq98_checkpoint_bytes.return_value = self._raw(
            self._checkpoint(98)
        )
        starter = mock.Mock()
        starter.reconstructed_seq99_checkpoint_bytes.return_value = self._raw(
            self._checkpoint(99)
        )
        completion = mock.Mock()
        completion.reconstructed_seq100_checkpoint_bytes.return_value = self._raw(
            self._checkpoint(100)
        )
        return correction, starter, completion

    def test_seq99_seq100_and_adjacent_seq102_are_dispatched(self) -> None:
        for length in (99, 100, 102):
            with self.subTest(length=length):
                correction, starter, completion = self._authorities()
                with (
                    mock.patch.object(
                        graph,
                        "_fp048_r002_successor_correction_authority",
                        return_value=correction,
                    ),
                    mock.patch.object(
                        graph,
                        "_fp048_r002_successor_started_authority",
                        return_value=starter,
                    ),
                    mock.patch.object(
                        graph,
                        "_fp048_r002_successor_completion_authority",
                        return_value=completion,
                    ),
                ):
                    self.assertEqual(
                        graph.validate_fp048_r002_successor_seq99_102(
                            ROOT,
                            self._checkpoint(length),
                            require_live_snapshot=True,
                        ),
                        [],
                    )
                self.assertEqual(
                    correction.require_start_gate_execution_corrected_checkpoint.call_count,
                    int(length == 99),
                )
                if length == 99:
                    self.assertTrue(
                        correction.require_start_gate_execution_corrected_checkpoint
                        .call_args.kwargs["require_live_snapshot"]
                    )
                self.assertEqual(
                    starter.require_started_checkpoint.call_count,
                    int(length >= 100),
                )
                if length >= 100:
                    self.assertEqual(
                        starter.require_started_checkpoint.call_args.kwargs[
                            "require_live_snapshot"
                        ],
                        length == 100,
                    )
                self.assertEqual(
                    completion.require_completed_checkpoint.call_count,
                    int(length == 102),
                )
                if length == 102:
                    self.assertTrue(
                        completion.require_completed_checkpoint.call_args.kwargs[
                            "require_live_snapshot"
                        ]
                    )

    def test_projected_seq102_uses_nonlive_chain_and_live_drift_fails(self) -> None:
        correction, starter, completion = self._authorities()
        checkpoint = self._checkpoint(102)
        with (
            mock.patch.object(
                graph,
                "_fp048_r002_successor_correction_authority",
                return_value=correction,
            ),
            mock.patch.object(
                graph,
                "_fp048_r002_successor_started_authority",
                return_value=starter,
            ),
            mock.patch.object(
                graph,
                "_fp048_r002_successor_completion_authority",
                return_value=completion,
            ),
            mock.patch.object(
                graph,
                "_fp048_r002_successor_is_live_checkpoint",
                return_value=False,
            ),
        ):
            self.assertEqual(
                graph.validate_fp048_r002_successor_seq99_102(
                    ROOT,
                    checkpoint,
                ),
                [],
            )
        correction.require_start_gate_execution_corrected_checkpoint.assert_not_called()
        for validator in (
            starter.require_started_checkpoint,
            completion.require_completed_checkpoint,
        ):
            self.assertFalse(
                validator.call_args.kwargs["require_live_snapshot"]
            )

        completion.require_completed_checkpoint.side_effect = RuntimeError(
            "live checkpoint drift"
        )
        with (
            mock.patch.object(
                graph,
                "_fp048_r002_successor_correction_authority",
                return_value=correction,
            ),
            mock.patch.object(
                graph,
                "_fp048_r002_successor_started_authority",
                return_value=starter,
            ),
            mock.patch.object(
                graph,
                "_fp048_r002_successor_completion_authority",
                return_value=completion,
            ),
            mock.patch.object(
                graph,
                "_fp048_r002_successor_is_live_checkpoint",
                return_value=True,
            ),
        ):
            errors = graph.validate_fp048_r002_successor_seq99_102(
                ROOT,
                checkpoint,
            )
        self.assertTrue(any("live checkpoint drift" in error for error in errors))

    def test_projected_seq99_uses_inverse_validation_without_precheck(self) -> None:
        correction, starter, completion = self._authorities()
        with (
            mock.patch.object(
                graph,
                "_fp048_r002_successor_correction_authority",
                return_value=correction,
            ),
            mock.patch.object(
                graph,
                "_fp048_r002_successor_started_authority",
                return_value=starter,
            ),
            mock.patch.object(
                graph,
                "_fp048_r002_successor_completion_authority",
                return_value=completion,
            ),
            mock.patch.object(
                graph,
                "_fp048_r002_successor_is_live_checkpoint",
                return_value=False,
            ),
        ):
            self.assertEqual(
                graph.validate_fp048_r002_successor_seq99_102(
                    ROOT,
                    self._checkpoint(99),
                    require_live_snapshot=False,
                ),
                [],
            )
        correction.require_start_gate_execution_corrected_checkpoint.assert_not_called()
        correction.reconstructed_seq98_checkpoint_bytes.assert_called_once()

    def test_explicit_false_cannot_downgrade_live_and_io_fails_closed(self) -> None:
        checkpoint = self._checkpoint(99)
        correction, starter, completion = self._authorities()
        with (
            mock.patch.object(
                graph,
                "_fp048_r002_successor_correction_authority",
                return_value=correction,
            ),
            mock.patch.object(
                graph,
                "_fp048_r002_successor_started_authority",
                return_value=starter,
            ),
            mock.patch.object(
                graph,
                "_fp048_r002_successor_completion_authority",
                return_value=completion,
            ),
            mock.patch.object(
                graph,
                "_fp048_r002_successor_is_live_checkpoint",
                return_value=True,
            ),
        ):
            self.assertEqual(
                graph.validate_fp048_r002_successor_seq99_102(
                    ROOT,
                    checkpoint,
                    require_live_snapshot=False,
                ),
                [],
            )
        self.assertTrue(
            correction.require_start_gate_execution_corrected_checkpoint
            .call_args.kwargs["require_live_snapshot"]
        )

        with mock.patch.object(
            graph,
            "_fp048_r002_successor_is_live_checkpoint",
            side_effect=ValueError("live checkpoint cannot be read"),
        ), mock.patch.object(
            graph.importlib,
            "import_module",
            side_effect=AssertionError("authority load must remain fail-closed"),
        ):
            errors = graph.validate_fp048_r002_successor_seq99_102(
                ROOT,
                checkpoint,
                require_live_snapshot=False,
            )
        self.assertTrue(
            any("live checkpoint cannot be read" in error for error in errors)
        )

    def test_noncanonical_successor_inverse_is_rejected(self) -> None:
        correction, starter, completion = self._authorities()
        correction.reconstructed_seq98_checkpoint_bytes.return_value += b" "
        with (
            mock.patch.object(
                graph,
                "_fp048_r002_successor_correction_authority",
                return_value=correction,
            ),
            mock.patch.object(
                graph,
                "_fp048_r002_successor_started_authority",
                return_value=starter,
            ),
            mock.patch.object(
                graph,
                "_fp048_r002_successor_completion_authority",
                return_value=completion,
            ),
        ):
            errors = graph.validate_fp048_r002_successor_seq99_102(
                ROOT,
                self._checkpoint(99),
                require_live_snapshot=False,
            )
        self.assertTrue(any("noncanonical" in error for error in errors))

    def test_seq101_without_adjacent_seq102_is_rejected_before_import(self) -> None:
        with mock.patch.object(
            graph.importlib,
            "import_module",
            side_effect=AssertionError("successor authority must stay lazy"),
        ):
            errors = graph.validate_fp048_r002_successor_seq99_102(
                ROOT,
                self._checkpoint(101),
            )
        self.assertTrue(any("lacks adjacent seq102" in error for error in errors))

    def test_bool_sequence_and_wrong_identity_are_rejected(self) -> None:
        mutations = {
            "bool sequence": {"sequence": True},
            "wrong event ID": {"event_id": "FORGED-SEQ99"},
            "wrong event type": {"event_type": "GOAL_STARTED"},
        }
        for label, mutation in mutations.items():
            with self.subTest(label=label):
                checkpoint = self._checkpoint(99)
                checkpoint["goal_execution"]["transition_history"][-1].update(
                    mutation
                )
                errors = graph.validate_fp048_r002_successor_seq99_102(
                    ROOT,
                    checkpoint,
                )
                self.assertTrue(errors)
                self.assertIn("identity or status differs", errors[0])

    def test_failed_r010_direct_seq99_is_rejected(self) -> None:
        checkpoint = self._checkpoint(99)
        checkpoint["goal_execution"]["transition_history"][-1].update(
            {
                "event_id": graph.FP048_R002_R010_STARTED_EVENT_ID,
                "event_type": "GOAL_STARTED",
            }
        )
        errors = graph.validate_fp048_r002_successor_seq99_102(ROOT, checkpoint)
        self.assertTrue(any("legacy failed R010" in error for error in errors))

    def test_historical_seq98_does_not_import_successor_modules(self) -> None:
        with mock.patch.object(
            graph.importlib,
            "import_module",
            side_effect=AssertionError("historical seq98 must remain isolated"),
        ):
            self.assertEqual(
                graph.validate_fp048_r002_successor_seq99_102(
                    ROOT,
                    self._checkpoint(98),
                ),
                [],
            )

    def test_authority_loaders_import_only_the_exact_successor_modules(self) -> None:
        modules = {
            graph.FP048_R002_SUCCESSOR_CORRECTION_MODULE: mock.Mock(),
            graph.FP048_R002_SUCCESSOR_STARTED_MODULE: mock.Mock(),
            graph.FP048_R002_SUCCESSOR_COMPLETION_MODULE: mock.Mock(),
        }
        with mock.patch.object(
            graph.importlib,
            "import_module",
            side_effect=modules.__getitem__,
        ) as importer:
            self.assertIs(
                graph._fp048_r002_successor_correction_authority(),
                modules[graph.FP048_R002_SUCCESSOR_CORRECTION_MODULE],
            )
            self.assertIs(
                graph._fp048_r002_successor_started_authority(),
                modules[graph.FP048_R002_SUCCESSOR_STARTED_MODULE],
            )
            self.assertIs(
                graph._fp048_r002_successor_completion_authority(),
                modules[graph.FP048_R002_SUCCESSOR_COMPLETION_MODULE],
            )
        self.assertEqual(
            importer.call_args_list,
            [
                mock.call(graph.FP048_R002_SUCCESSOR_CORRECTION_MODULE),
                mock.call(graph.FP048_R002_SUCCESSOR_STARTED_MODULE),
                mock.call(graph.FP048_R002_SUCCESSOR_COMPLETION_MODULE),
            ],
        )

    def test_completion_producer_hook_uses_the_successor_dispatch(self) -> None:
        checkpoint = self._checkpoint(102)
        with mock.patch.object(
            graph,
            "validate_fp048_r002_successor_seq99_102",
            return_value=["sentinel"],
        ) as validator:
            self.assertEqual(
                graph.validate_fp048_r002_completion_seq101_102(
                    ROOT,
                    checkpoint,
                ),
                ["sentinel"],
            )
        validator.assert_called_once_with(
            ROOT,
            checkpoint,
            require_live_snapshot=False,
        )


class Fp048R002CompletionSeq100Seq101Tests(unittest.TestCase):
    @staticmethod
    def _checkpoint(length: int) -> dict:
        history = [
            {"sequence": sequence, "event_id": f"SYNTHETIC-{sequence}"}
            for sequence in range(1, length + 1)
        ]
        if length >= 99:
            source = {
                "sequence": 99,
                "event_id": graph.FP048_R002_R010_STARTED_EVENT_ID,
                "event_type": "GOAL_STARTED",
            }
            source["event_sha256"] = graph.continuation.event_sha256(source)
            history[98] = source
        if length >= 100:
            update = {
                "sequence": 100,
                "event_id": graph.FP048_R002_COMPLETION_EVIDENCE_EVENT_ID,
                "event_type": "CANONICAL_BINDINGS_UPDATED",
                "occurred_at": "2026-08-27T02:00:00+09:00",
                "previous_event_sha256": history[98]["event_sha256"],
                "source_checkpoint_binding": {"path": "checkpoint"},
            }
            update["event_sha256"] = graph.continuation.event_sha256(update)
            history[99] = update
        if length >= 101:
            completion = {
                "sequence": 101,
                "event_id": graph.FP048_R002_COMPLETION_EVENT_ID,
                "event_type": "GOAL_COMPLETED",
                "occurred_at": "2026-08-27T02:00:01+09:00",
                "previous_event_sha256": history[99]["event_sha256"],
                "canonical_update_event_sha256": history[99]["event_sha256"],
            }
            completion["event_sha256"] = graph.continuation.event_sha256(
                completion
            )
            history[100] = completion
        return {"goal_execution": {"transition_history": history}}

    def test_seq100_requires_adjacent_seq101(self) -> None:
        self.assertEqual(
            graph.validate_fp048_r002_completion_seq100_101(
                ROOT,
                self._checkpoint(100),
            ),
            [
                "FP048 R002 seq100 evidence transaction lacks adjacent "
                "seq101 completion"
            ],
        )

    def test_completion_evidence_loader_matches_sealed_assignment_cas_subset(
        self,
    ) -> None:
        authority = mock.Mock()
        authority.COMPLETION_RECEIPT_REL = Path("reviews/completion-receipt.json")
        authority.COMPLETION_ROLE = "FP048_COMPLETION"
        authority.COMPLETION_DOCUMENT_ID = "FP048-COMPLETION-RECEIPT"
        authority.GOAL_ID = graph.FP046_R002_NEXT_GOAL_ID
        authority.ZERO_CREDIT_BOUNDARY = {"release_credit_delta": 0}
        authority.REVIEW_ASSIGNMENT_REL = Path("reviews/assignment.json")
        authority.REVIEW_RESULT_REL = Path("reviews/result.json")
        authority.INDEPENDENT_REVIEW_REL = Path("reviews/independent.json")
        authority.CHECKPOINT_REL = graph.V24_CHECKPOINT_RELATIVE
        authority.sha256_bytes.side_effect = (
            lambda raw: hashlib.sha256(raw).hexdigest()
        )
        authority.json_bytes.side_effect = (
            lambda value: (json.dumps(value, sort_keys=True) + "\n").encode()
        )
        authority.CompletionEvidence.side_effect = lambda **values: values

        restored_raw = b'{"source":"seq99"}\n'
        common_cas = {
            "path": authority.CHECKPOINT_REL.as_posix(),
            "sha256": hashlib.sha256(restored_raw).hexdigest(),
            "byte_length": len(restored_raw),
        }
        assignment = {
            "source_checkpoint_binding": {
                **common_cas,
                "sequence": graph.FP048_R002_COMPLETION_SOURCE_SEQUENCE,
                "tail_event_id": graph.FP048_R002_R010_STARTED_EVENT_ID,
                "tail_event_sha256": "a" * 64,
            }
        }
        authority.expected_review_assignment.return_value = assignment
        receipt = {
            "document_id": authority.COMPLETION_DOCUMENT_ID,
            "goal_id": authority.GOAL_ID,
            "completion_boundary": authority.ZERO_CREDIT_BOUNDARY,
            "verification_evidence": {
                "execution_window": {
                    "ended_at": "2026-08-27T03:00:00+09:00"
                }
            },
        }
        result = {"decision": "APPROVED"}
        independent = {"decision": "CONCUR"}
        documents = {
            authority.COMPLETION_RECEIPT_REL: receipt,
            authority.REVIEW_ASSIGNMENT_REL: assignment,
            authority.REVIEW_RESULT_REL: result,
            authority.INDEPENDENT_REVIEW_REL: independent,
        }
        raws = {
            path: authority.json_bytes(value)
            for path, value in documents.items()
        }
        review_binding = {
            role: {
                "path": path.as_posix(),
                "sha256": hashlib.sha256(raws[path]).hexdigest(),
                "byte_length": len(raws[path]),
            }
            for role, path in {
                "assignment": authority.REVIEW_ASSIGNMENT_REL,
                "review_result": authority.REVIEW_RESULT_REL,
                "independent_review": authority.INDEPENDENT_REVIEW_REL,
            }.items()
        }
        reviewed = mock.Mock(
            binding=copy.deepcopy(review_binding),
            latest_review_at="2026-08-27T03:01:00+09:00",
        )
        authority.validate_review_authority.return_value = reviewed
        receipt_binding = {
            "role": authority.COMPLETION_ROLE,
            "document_id": authority.COMPLETION_DOCUMENT_ID,
            "path": authority.COMPLETION_RECEIPT_REL.as_posix(),
            "file_sha256": hashlib.sha256(
                raws[authority.COMPLETION_RECEIPT_REL]
            ).hexdigest(),
        }
        update = {
            "producer_completion_receipt_binding": receipt_binding,
            "transition_control_review_binding": review_binding,
        }
        completion = {
            "completion_receipt_binding": receipt_binding,
            "completion_evidence_bindings": {
                authority.COMPLETION_ROLE: receipt_binding
            },
        }

        def load_document(
            _root: Path,
            relative: Path,
            _authority: object,
            _label: str,
        ) -> tuple[bytes, dict]:
            return raws[relative], copy.deepcopy(documents[relative])

        with mock.patch.object(
            graph,
            "_fp048_r002_completion_private_json",
            side_effect=load_document,
        ):
            _evidence, source_cas = (
                graph._fp048_r002_completion_evidence_authority(
                    ROOT,
                    {},
                    authority,
                    update,
                    completion,
                    restored_raw,
                    {},
                )
            )
            self.assertEqual(source_cas, common_cas)

            assignment["source_checkpoint_binding"]["sha256"] = "0" * 64
            authority.expected_review_assignment.return_value = assignment
            raws[authority.REVIEW_ASSIGNMENT_REL] = authority.json_bytes(
                assignment
            )
            update["transition_control_review_binding"]["assignment"] = {
                "path": authority.REVIEW_ASSIGNMENT_REL.as_posix(),
                "sha256": hashlib.sha256(
                    raws[authority.REVIEW_ASSIGNMENT_REL]
                ).hexdigest(),
                "byte_length": len(raws[authority.REVIEW_ASSIGNMENT_REL]),
            }
            authority.validate_review_authority.return_value.binding = copy.deepcopy(
                update["transition_control_review_binding"]
            )
            with self.assertRaisesRegex(
                RuntimeError,
                "completion independent review authority differs",
            ):
                graph._fp048_r002_completion_evidence_authority(
                    ROOT,
                    {},
                    authority,
                    update,
                    completion,
                    restored_raw,
                    {},
                )

    def test_completion_loader_pins_receipt_and_review_paths(self) -> None:
        authority = mock.Mock()
        authority.SOURCE_SEQUENCE = graph.FP048_R002_COMPLETION_SOURCE_SEQUENCE
        authority.EVIDENCE_SEQUENCE = graph.FP048_R002_COMPLETION_EVIDENCE_SEQUENCE
        authority.COMPLETION_SEQUENCE = graph.FP048_R002_COMPLETION_SEQUENCE
        authority.EVIDENCE_EVENT_ID = graph.FP048_R002_COMPLETION_EVIDENCE_EVENT_ID
        authority.COMPLETION_EVENT_ID = graph.FP048_R002_COMPLETION_EVENT_ID
        authority.COMPLETION_RECEIPT_REL = graph.FP048_R002_COMPLETION_RECEIPT_REL
        authority.REVIEW_ROOT = graph.FP048_R002_COMPLETION_REVIEW_ROOT
        with mock.patch.object(
            graph.importlib,
            "import_module",
            return_value=authority,
        ):
            self.assertIs(graph._fp048_r002_completion_authority(), authority)
            authority.REVIEW_ROOT = Path("forged-review-root")
            with self.assertRaisesRegex(RuntimeError, "constant differs"):
                graph._fp048_r002_completion_authority()

    def test_exact_seq101_uses_seq99_inverse_and_projection(self) -> None:
        checkpoint = self._checkpoint(101)
        source = self._checkpoint(99)
        raw = (json.dumps(source, sort_keys=True) + "\n").encode()
        binding = {
            "path": graph.V24_CHECKPOINT_RELATIVE.as_posix(),
            "sha256": hashlib.sha256(raw).hexdigest(),
            "byte_length": len(raw),
        }
        checkpoint["goal_execution"]["transition_history"][99][
            "source_checkpoint_binding"
        ] = copy.deepcopy(binding)
        checkpoint["goal_execution"]["transition_history"][99][
            "event_sha256"
        ] = graph.continuation.event_sha256(
            checkpoint["goal_execution"]["transition_history"][99]
        )
        update = checkpoint["goal_execution"]["transition_history"][99]
        completion = checkpoint["goal_execution"]["transition_history"][100]
        completion["previous_event_sha256"] = update["event_sha256"]
        completion["canonical_update_event_sha256"] = update["event_sha256"]
        completion["event_sha256"] = graph.continuation.event_sha256(completion)
        authority = mock.Mock()
        authority.CHECKPOINT_REL = graph.V24_CHECKPOINT_RELATIVE
        authority.reconstructed_seq99_checkpoint_bytes.return_value = raw
        authority.checkpoint_bytes.side_effect = (
            lambda value: (json.dumps(value, sort_keys=True) + "\n").encode()
        )
        authority.sha256_bytes.side_effect = (
            lambda value: hashlib.sha256(value).hexdigest()
        )
        starter = mock.Mock()
        with (
            mock.patch.object(
                graph,
                "_fp048_r002_completion_authority",
                return_value=authority,
            ),
            mock.patch.object(
                graph,
                "_fp048_r002_r010_started_authority",
                return_value=starter,
            ),
            mock.patch.object(
                graph,
                "_fp048_r002_completion_evidence_authority",
                return_value=(mock.sentinel.evidence, binding),
            ),
            mock.patch.object(
                graph,
                "_fp048_r002_zero_credit_boundary_matches",
                return_value=True,
            ),
        ):
            self.assertEqual(
                graph.validate_fp048_r002_completion_seq100_101(
                    ROOT,
                    checkpoint,
                ),
                [],
            )
        authority.validate_projection.assert_called_once_with(
            ROOT,
            source,
            checkpoint,
            mock.sentinel.evidence,
        )

    def test_seq101_projection_tamper_fails_closed(self) -> None:
        checkpoint = self._checkpoint(101)
        checkpoint["goal_execution"]["transition_history"][-1][
            "canonical_update_event_sha256"
        ] = "0" * 64
        errors = graph.validate_fp048_r002_completion_seq100_101(
            ROOT,
            checkpoint,
        )
        self.assertTrue(errors)
        self.assertIn("identity or adjacency differs", errors[0])


if __name__ == "__main__":
    unittest.main()

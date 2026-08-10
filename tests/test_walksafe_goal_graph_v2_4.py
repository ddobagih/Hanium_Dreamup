"""Focused active-package regressions for the WalkSafe v2.4 Goal graph."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import copy
import shutil
import subprocess
import tempfile
from typing import Callable
import unittest
from unittest import mock

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
    history = checkpoint["goal_execution"]["transition_history"]
    if (
        len(history) >= 50
        and history[48].get("event_id")
        == graph.FP008_ADMIN_REVIEW_CANONICAL_UPDATE_EVENT_ID
        and history[49].get("event_id")
        == graph.FP008_ADMIN_REVIEW_COMPLETION_EVENT_ID
    ):
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
        for relative, (before_sha256, after_sha256) in transitions.items():
            self.assertNotEqual(before_sha256, after_sha256)
            self.assertEqual(sha256_file(ROOT / relative), after_sha256)

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
        with tempfile.NamedTemporaryFile(
            "wb",
            dir=ROOT / "docs/control",
            prefix=".fp046-seq55-test-",
            suffix=".json",
            delete=False,
        ) as stream:
            stream.write(json_bytes(checkpoint))
            checkpoint_path = Path(stream.name)
        try:
            self.assertEqual(
                graph.validate(
                    ROOT,
                    checkpoint_path.relative_to(ROOT),
                    check_continuation=False,
                ),
                [],
            )
        finally:
            checkpoint_path.unlink()


class WalkSafeHistoricalBindingRegressionTest(unittest.TestCase):
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
        self.assertEqual(errors, [])
        self.assertEqual(len(bindings), 32)
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
            self.assertEqual(bindings[relative], current_sha256)
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
                    current_sha256,
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
        )
        self.assertIsInstance(successors, dict)
        self.assertEqual(
            set(successors),
            set(graph.FP048_ANDROID_REPORT_SUCCESSOR_BY_PATH),
        )
        self.assertEqual(len(successors), 5)
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
                sha256_file(ROOT / relative),
                transition["after_sha256"],
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
        self.assertEqual(
            fp048[relative][1],
            sha256_file(ROOT / relative),
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
        rtm_path = ROOT / graph.FP008_REQUIREMENTS_TRACEABILITY_BINDING["path"]

        def wrong_physical_sha256(target: Path) -> Callable[[Path], str]:
            def sha256(path: Path) -> str:
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
        checkpoint = fp008_seq50_checkpoint()
        archive = load_json(ROOT / graph.V23_ARCHIVE_RELATIVE)
        expected = sorted(frozen_requirements_traceability_errors())

        future = copy.deepcopy(checkpoint)
        state = future["goal_execution"]
        completion = state["transition_history"][49]
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
            "previous_focus_goal_id": graph.FP008_ADMIN_REVIEW_PARENT_GOAL_ID,
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
            "runtime_after": copy.deepcopy(
                graph.FP008_ADMIN_REVIEW_COMPLETION_RUNTIME
            ),
            "blockers_after": {},
            "blocker_resolution_ids_after": [],
            "source_checkpoint_version": (
                graph.FP008_ADMIN_REVIEW_SOURCE_CHECKPOINT_VERSION
            ),
            "evidence_refs": [],
            "previous_event_sha256": completion["event_sha256"],
        }
        event["event_sha256"] = graph.continuation.event_sha256(event)
        state["transition_history"].append(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]
        state["validation_cutoff_at"] = occurred_at
        future["current_work"]["status"] = "READY"
        future["current_work"]["current_focus"] = (
            "EPIC-03 READY; FP046 PLANNED_NEXT"
        )
        future["session_handoff"]["current_epic"] = (
            "EPIC-03 / FP046 PLANNED_NEXT"
        )
        future["session_handoff"]["last_updated_by_work_item"] = (
            "EPIC-03-FUTURE-SAFE-PROGRESS"
        )

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
            graph.validate_v24_artifact_work_queue(ROOT, checkpoint),
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

        checkpoint["goal_execution"]["artifact_work_queue"][
            "counts_by_status"
        ]["WAITING_TRIGGER"] += 1
        self.assertTrue(
            graph.validate_v24_artifact_work_queue(ROOT, checkpoint)
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
        transitions = graph._fp015_successor_product_artifacts(
            ROOT,
            checkpoint,
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
        for returncode in (128, 2):
            failed_git = subprocess.CompletedProcess(
                args=["git", "cat-file", "-e"],
                returncode=returncode,
                stdout=b"",
                stderr=b"invalid pinned HEAD",
            )
            with self.subTest(
                pinned_head_returncode=returncode
            ), mock.patch.object(
                graph.continuation._v23_utility,
                "_run_git_bytes",
                return_value=failed_git,
            ):
                self.assertIsNone(
                    graph._fp015_successor_product_artifacts(
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
        fp016 = graph._fp016_successor_product_artifacts(
            ROOT,
            self.checkpoint,
        )
        combined = graph._fp014_successor_product_artifacts(
            ROOT,
            self.checkpoint,
        )
        self.assertIsInstance(fp016, dict)
        self.assertEqual(len(fp016), 10)
        self.assertIsInstance(combined, dict)
        self.assertTrue(set(fp016).issubset(combined))
        self.assertIsInstance(projection, tuple)
        self.assertNotIn(graph.FP047_PARENT_GOAL_ID, projection[1])

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
        artifacts = graph._fp016_successor_product_artifacts(
            ROOT,
            self.checkpoint,
        )

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
        fp016 = graph._fp016_successor_product_artifacts(
            ROOT,
            self.checkpoint,
        )
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
        state["status_by_goal"].pop(
            "WS-GOAL-EPIC-03-FP-048-R001",
            None,
        )
        state["dynamic_goal_inventory"].pop(
            "WS-GOAL-EPIC-03-FP-048-R001",
            None,
        )
        state["status_by_goal"].pop(
            "WS-GOAL-EPIC-03-FP-008-R001",
            None,
        )
        state["dynamic_goal_inventory"].pop(
            "WS-GOAL-EPIC-03-FP-008-R001",
            None,
        )
        state["completion_evidence_by_goal"].pop(
            "WS-GOAL-EPIC-03-FP-008-R001",
            None,
        )
        state["completion_evidence_by_goal"].pop(
            graph.FP048_ANDROID_REPORT_GOAL_ID,
            None,
        )
        checkpoint["canonical_bindings"] = [
            binding
            for binding in checkpoint["canonical_bindings"]
            if binding.get("role")
            != graph.FP048_ANDROID_REPORT_COMPLETION_ROLE
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
        self.assertIsInstance(
            graph._fp014_successor_product_artifacts(
                ROOT,
                checkpoint,
                require_live_successors=False,
            ),
            dict,
        )
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
            if row["exact_path"]
            in graph.FP048_ANDROID_REPORT_SUCCESSOR_BY_PATH
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
        fp012 = graph._fp012_successor_product_artifacts(
            ROOT,
            self.checkpoint,
        )
        combined = graph._fp014_successor_product_artifacts(
            ROOT,
            self.checkpoint,
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
        completed = copy.deepcopy(checkpoint)
        state = completed["goal_execution"]
        state["status_by_goal"][graph.FP047_GOAL_ID] = "COMPLETE_AT_TARGET"
        state["focus_goal_id"] = graph.FP047_PARENT_GOAL_ID

        with mock.patch.object(
            graph,
            "_fp047_completion_transition_matches",
            return_value=True,
        ):
            artifacts = graph._fp014_successor_product_artifacts(ROOT, completed)
        self.assertIsInstance(artifacts, dict)

        with mock.patch.object(
            graph,
            "_fp047_completion_transition_matches",
            return_value=False,
        ):
            self.assertIsNone(
                graph._fp014_successor_product_artifacts(ROOT, completed)
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
                    graph._fp014_successor_product_artifacts(ROOT, invalid)
                )

        later_focus = copy.deepcopy(completed)
        later_focus["goal_execution"]["focus_goal_id"] = "WS-GOAL-EPIC-12"
        with mock.patch.object(
            graph,
            "_fp047_completion_transition_matches",
            return_value=True,
        ):
            self.assertIsInstance(
                graph._fp014_successor_product_artifacts(ROOT, later_focus),
                dict,
            )


if __name__ == "__main__":
    unittest.main()

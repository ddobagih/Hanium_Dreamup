#!/usr/bin/env python3
"""Validate the WalkSafe v2.4 successor Goal package and runtime checkpoint.

The migration validates the byte-exact v2.3 archive with the frozen v2.3
checker, imports its 20-Goal projection, and keeps all new control logic at
v2.4 paths.
"""

from __future__ import annotations

import argparse
import copy
import importlib.util
import json
import re
import subprocess
import sys
from datetime import datetime
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import check_walksafe_project_continuation_v2_4 as continuation  # noqa: E402
from scripts import check_walksafe_goal_graph_v2_3 as frozen_goal  # noqa: E402


def _run_git_bytes(*args, **kwargs):
    return continuation._v23_utility._run_git_bytes(*args, **kwargs)


def _json_path_value(value: Any, path: str) -> Any:
    current = value
    for part in path.split("."):
        if not isinstance(current, dict) or part not in current:
            return None
        current = current[part]
    return current


def _contains_symlink(root: Path, relative: str) -> bool:
    current = root.resolve()
    for part in Path(relative).parts:
        current /= part
        if current.is_symlink():
            return True
    return False


def validate_canonical_preimage_archive(
    root: Path,
) -> tuple[list[str], dict[tuple[str, str], Path]]:
    """Validate the exact seq38 archive without treating it as live state."""
    errors: list[str] = []
    if _contains_symlink(
        root,
        CANONICAL_PREIMAGE_MANIFEST_RELATIVE.as_posix(),
    ):
        return ["canonical preimage manifest uses a symlink"], {}
    manifest_path = continuation.resolve_repo_file(
        root,
        CANONICAL_PREIMAGE_MANIFEST_RELATIVE,
    )
    if manifest_path is None:
        return ["canonical preimage manifest is missing or unsafe"], {}
    if (
        continuation.sha256_file(manifest_path)
        != CANONICAL_PREIMAGE_MANIFEST_SHA256
    ):
        errors.append("canonical preimage manifest SHA-256 differs")
    try:
        manifest = continuation.load_json(manifest_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [f"canonical preimage manifest cannot be loaded: {exc}"], {}

    expected_top_fields = {
        "schema_version",
        "archive_id",
        "created_on",
        "status",
        "source_checkpoint",
        "source_event",
        "recovery_provenance",
        "entries",
        "integrity",
        "claim_boundary",
    }
    if set(manifest) != expected_top_fields:
        errors.append("canonical preimage manifest field set differs")
    expected_boundary = {
        "canonical_binding_update_authorized": False,
        "canonical_checkpoint_modified": False,
        "historical_content_approved": False,
        "artifact_completion_credit_delta": 0,
        "approval_credit_delta": 0,
        "test_credit_delta": 0,
        "actual_event_credit_delta": 0,
        "release_status": "NOT_ELIGIBLE",
        "intended_use": "HISTORICAL_REPLAY_AND_PREIMAGE_PRESERVATION_ONLY",
    }
    expected_source_event = {
        "sequence": 38,
        "event_id": (
            "WS-GOAL-GRAPH-V2-4-GOAL-COMPLETED-FP047-20260726-001"
        ),
        "event_sha256": CANONICAL_PREIMAGE_SOURCE_EVENT_SHA256,
        "occurred_at": "2026-07-26T19:22:41+09:00",
    }
    expected_source_checkpoint = {
        "path": CHECKPOINT_RELATIVE.as_posix(),
        "schema_version": "1.24.0",
        "observed_file_sha256": (
            "e61d919b3995f364760007c43c7bc462f1fd64f401b30d2f1f7ceeda86ab7e72"
        ),
        "observed_byte_count": 1291260,
    }
    expected_recovery = {
        "source_kind": "LOCAL_VALIDATION_COPY",
        "source_root": (
            "/home/ddobagi/.cache/walksafe-v24-synth2-source.eCmu8k"
        ),
        "selection_rule": "EXACT_LIVE_PATH_AND_SOURCE_EVENT_SHA256_MATCH",
        "source_is_canonical": False,
    }
    expected_roles = list(CANONICAL_PREIMAGE_EXPECTED)
    expected_integrity = {
        "entry_count": 5,
        "historical_total_byte_count": 6708023,
        "role_order": expected_roles,
        "blob_naming": "LOWERCASE_SHA256_DOT_JSON",
        "blob_content_contract": "BYTE_IDENTICAL_TO_SOURCE_EVENT_LIVE_PATH",
    }
    for label, actual, expected in (
        ("schema", manifest.get("schema_version"), "walksafe.canonical-preimage-archive.v1"),
        (
            "archive ID",
            manifest.get("archive_id"),
            "WS-CANONICAL-PREIMAGE-ARCHIVE-V2-4-SEQ38-20260729-001",
        ),
        ("created_on", manifest.get("created_on"), "2026-07-29"),
        (
            "status",
            manifest.get("status"),
            "PREIMAGE_BYTES_PRESERVED_NO_CANONICAL_UPDATE_AUTHORITY",
        ),
        (
            "source checkpoint",
            manifest.get("source_checkpoint"),
            expected_source_checkpoint,
        ),
        ("source event", manifest.get("source_event"), expected_source_event),
        (
            "recovery provenance",
            manifest.get("recovery_provenance"),
            expected_recovery,
        ),
        ("integrity", manifest.get("integrity"), expected_integrity),
        ("claim boundary", manifest.get("claim_boundary"), expected_boundary),
    ):
        if actual != expected:
            errors.append(f"canonical preimage {label} differs")

    entries = manifest.get("entries")
    if not isinstance(entries, list):
        return errors + ["canonical preimage entries must be a list"], {}
    if len(entries) != len(expected_roles):
        errors.append("canonical preimage entry count differs")
    if [
        entry.get("role") if isinstance(entry, dict) else None
        for entry in entries
    ] != expected_roles:
        errors.append("canonical preimage role order or membership differs")

    lookup: dict[tuple[str, str], Path] = {}
    for entry in entries:
        if not isinstance(entry, dict):
            errors.append("canonical preimage entry is malformed")
            continue
        role = entry.get("role")
        expected = CANONICAL_PREIMAGE_EXPECTED.get(role)
        if expected is None:
            errors.append(f"canonical preimage role is unexpected: {role}")
            continue
        if set(entry) != CANONICAL_PREIMAGE_ENTRY_FIELDS:
            errors.append(f"canonical preimage entry field set differs: {role}")
            continue
        digest = expected["historical_sha256"]
        blob_path_value = (
            "docs/control/history/canonical-preimages/sha256/"
            f"{digest}.json"
        )
        expected_entry = {
            "role": role,
            **expected,
            "identity_expected": expected["document_id"],
            "blob_path": blob_path_value,
            "blob_sha256": digest,
        }
        if entry != expected_entry:
            errors.append(f"canonical preimage entry differs: {role}")
            continue
        pair = (expected["live_path"], digest)
        if pair in lookup:
            errors.append(f"canonical preimage pair is duplicated: {role}")
            continue
        if _contains_symlink(root, blob_path_value):
            errors.append(f"canonical preimage blob uses a symlink: {role}")
            continue
        blob_path = continuation.resolve_repo_file(root, blob_path_value)
        if blob_path is None:
            errors.append(f"canonical preimage blob is missing or unsafe: {role}")
            continue
        if blob_path.stat().st_size != expected["historical_byte_count"]:
            errors.append(f"canonical preimage blob byte count differs: {role}")
            continue
        if continuation.sha256_file(blob_path) != digest:
            errors.append(f"canonical preimage blob SHA-256 differs: {role}")
            continue
        try:
            document = continuation.load_json(blob_path)
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            errors.append(f"canonical preimage blob cannot be loaded: {role}: {exc}")
            continue
        identity = _json_path_value(document, expected["identity_json_path"])
        if expected["identity_match"] == "EXACT_STRING":
            identity_matches = identity == expected["document_id"]
        else:
            identity_matches = (
                isinstance(identity, list)
                and identity.count(expected["document_id"]) == 1
                and all(isinstance(value, str) for value in identity)
            )
        if not identity_matches:
            errors.append(f"canonical preimage document identity differs: {role}")
            continue
        lookup[pair] = blob_path
    if len(lookup) != len(expected_roles):
        errors.append("canonical preimage exact lookup set differs")
    return errors, lookup


def resolve_canonical_binding_file(
    root: Path,
    live_path: str,
    expected_sha256: str,
    *,
    mode: str,
    preimages: dict[tuple[str, str], Path] | None = None,
) -> Path | None:
    """Resolve current bytes or an exact sealed historical `(path, SHA)`."""
    if (
        mode not in CANONICAL_PREIMAGE_MODES
        or not isinstance(live_path, str)
        or not isinstance(expected_sha256, str)
        or continuation.SHA256_RE.fullmatch(expected_sha256) is None
    ):
        return None
    live = continuation.resolve_repo_file(root, live_path)
    if (
        live is not None
        and continuation.sha256_file(live) == expected_sha256
    ):
        return live
    if mode == "CURRENT_LIVE":
        return None
    if preimages is None:
        archive_errors, preimages = validate_canonical_preimage_archive(root)
        if archive_errors:
            return None
    return preimages.get((live_path, expected_sha256))


def validate_phase1_android_report_successor_binding(
    root: Path,
    checkpoint: dict[str, Any] | None = None,
) -> tuple[list[str], dict[str, str]]:
    """Validate the packet and its exact sealed FP048 live successor."""
    errors: list[str] = []
    evidence_relative = (
        PHASE1_ANDROID_REPORT_SUCCESSOR_EVIDENCE_RELATIVE.as_posix()
    )
    review_relative = (
        PHASE1_ANDROID_REPORT_SUCCESSOR_REVIEW_RELATIVE.as_posix()
    )
    if _contains_symlink(root, evidence_relative):
        errors.append("phase1 Android report successor evidence uses a symlink")
    if _contains_symlink(root, review_relative):
        errors.append("phase1 Android report successor review uses a symlink")
    evidence_path = continuation.resolve_repo_file(root, evidence_relative)
    review_path = continuation.resolve_repo_file(root, review_relative)
    if evidence_path is None:
        errors.append("phase1 Android report successor evidence is missing")
    if review_path is None:
        errors.append("phase1 Android report successor review is missing")
    if errors:
        return errors, {}
    try:
        evidence_size = evidence_path.stat().st_size
        review_size = review_path.stat().st_size
    except OSError as exc:
        return [f"phase1 Android report successor binding cannot be read: {exc}"], {}
    if evidence_size != PHASE1_ANDROID_REPORT_SUCCESSOR_EVIDENCE_BYTE_COUNT:
        errors.append("phase1 Android report successor evidence byte count differs")
    if (
        continuation.sha256_file(evidence_path)
        != PHASE1_ANDROID_REPORT_SUCCESSOR_EVIDENCE_SHA256
    ):
        errors.append("phase1 Android report successor evidence SHA-256 differs")
    if review_size != PHASE1_ANDROID_REPORT_SUCCESSOR_REVIEW_BYTE_COUNT:
        errors.append("phase1 Android report successor review byte count differs")
    if (
        continuation.sha256_file(review_path)
        != PHASE1_ANDROID_REPORT_SUCCESSOR_REVIEW_SHA256
    ):
        errors.append("phase1 Android report successor review SHA-256 differs")
    try:
        packet = continuation.load_json(evidence_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [
            f"phase1 Android report successor evidence cannot be loaded: {exc}"
        ], {}

    expected_packet_fields = {
        "capture_time",
        "claim_bindings",
        "claim_boundary",
        "current_validation",
        "exact_count_self_check",
        "implementation_facts",
        "packet_content_fingerprint",
        "packet_id",
        "packet_status",
        "prepared_on",
        "preserved_failure_history",
        "residual_risks",
        "schema_version",
        "scope",
        "source_test_binding",
        "validation_evidence_binding",
    }
    expected_scope = {
        "canonical_docs_or_code_modified": False,
        "git_used": False,
        "release_eligible": False,
        "subject": "Android report/privacy successor",
        "tests_run_by_packet_authoring": False,
    }
    expected_boundary = {
        "co_binding_note": (
            "This packet content-addresses current source/test files and "
            "receipts together; receipts do not prove those exact file bytes "
            "were the execution-time snapshot."
        ),
        "execution_receipts_embed_source_manifest": False,
        "formal279_claimed": False,
        "host_unit_and_static_lint_only": True,
        "instrumentation_claimed": False,
        "pass_interpretation": (
            "PASS applies only to the exact recorded host Gradle/JUnit/lint "
            "commands and counts; it is not device, server, field, product, "
            "or release evidence."
        ),
        "physical_device_claimed": False,
        "product_release_claimed": False,
        "separate_process_claimed": False,
        "server_contract_claimed": False,
    }
    expected_fingerprint = {
        "algorithm": "SHA-256",
        "canonicalization": (
            "UTF-8 recursive lexicographic JSON key order, compact form"
        ),
        "excluded_json_path": "$.packet_content_fingerprint",
        "sha256": PHASE1_ANDROID_REPORT_SUCCESSOR_CONTENT_SHA256,
    }
    expected_self_check = {
        "expected_log_count": 10,
        "expected_receipt_count": 9,
        "expected_source_count": 12,
        "expected_test_source_count": 20,
        "observed_log_count": 10,
        "observed_receipt_count": 9,
        "observed_source_count": 12,
        "observed_test_source_count": 20,
        "status": "PASS",
    }
    for label, actual, expected in (
        ("field set", set(packet), expected_packet_fields),
        (
            "schema",
            packet.get("schema_version"),
            "walksafe.phase1-android-report-successor-evidence.v1",
        ),
        (
            "packet ID",
            packet.get("packet_id"),
            "PHASE1-ANDROID-REPORT-SUCCESSOR-20260727-001",
        ),
        (
            "packet status",
            packet.get("packet_status"),
            (
                "PARTIAL_IMPLEMENTATION_HOST_VALIDATION_PASS_"
                "OPEN_GATES_PRESERVED"
            ),
        ),
        ("prepared_on", packet.get("prepared_on"), "2026-07-28"),
        (
            "capture_time",
            packet.get("capture_time"),
            "2026-07-28T00:16:10+09:00",
        ),
        ("scope", packet.get("scope"), expected_scope),
        ("claim boundary", packet.get("claim_boundary"), expected_boundary),
        (
            "packet content fingerprint",
            packet.get("packet_content_fingerprint"),
            expected_fingerprint,
        ),
        (
            "exact count self-check",
            packet.get("exact_count_self_check"),
            expected_self_check,
        ),
    ):
        if actual != expected:
            errors.append(f"phase1 Android report successor {label} differs")
    if (
        continuation.canonical_json_sha256(
            packet,
            omit={"packet_content_fingerprint"},
        )
        != PHASE1_ANDROID_REPORT_SUCCESSOR_CONTENT_SHA256
    ):
        errors.append(
            "phase1 Android report successor content fingerprint differs"
        )

    binding = packet.get("source_test_binding")
    expected_binding_fields = {
        "capture_mode",
        "hash_algorithm",
        "manifest_sha256",
        "source_count",
        "source_total_bytes",
        "sources",
        "test_source_count",
        "test_source_total_bytes",
        "tests",
    }
    if (
        not isinstance(binding, dict)
        or set(binding) != expected_binding_fields
        or binding.get("capture_mode")
        != (
            "CURRENT_FILES_CONTENT_ADDRESSED_WITHOUT_PROOF_OF_"
            "EXECUTION_TIME_IDENTITY"
        )
        or binding.get("hash_algorithm") != "SHA-256"
        or binding.get("source_count") != 12
        or binding.get("source_total_bytes") != 875532
        or binding.get("test_source_count") != 20
        or binding.get("test_source_total_bytes") != 198101
    ):
        return errors + [
            "phase1 Android report successor source/test contract differs"
        ], {}
    sources = binding.get("sources")
    tests = binding.get("tests")
    if (
        not isinstance(sources, list)
        or not isinstance(tests, list)
        or len(sources) != 12
        or len(tests) != 20
        or any(not isinstance(row, dict) for row in sources + tests)
        or any(
            set(row) != {"byte_length", "exact_path", "sha256"}
            for row in sources + tests
        )
    ):
        return errors + [
            "phase1 Android report successor source/test rows differ"
        ], {}
    if (
        continuation.canonical_json_sha256(
            {"sources": sources, "tests": tests}
        )
        != PHASE1_ANDROID_REPORT_SUCCESSOR_MANIFEST_SHA256
        or binding.get("manifest_sha256")
        != PHASE1_ANDROID_REPORT_SUCCESSOR_MANIFEST_SHA256
    ):
        errors.append(
            "phase1 Android report successor source/test manifest differs"
        )
    all_rows = sources + tests
    paths = [row.get("exact_path") for row in all_rows]
    if (
        any(not isinstance(path, str) for path in paths)
        or len(set(paths)) != len(paths)
        or [row.get("exact_path") for row in sources]
        != sorted(row.get("exact_path") for row in sources)
        or [row.get("exact_path") for row in tests]
        != sorted(row.get("exact_path") for row in tests)
        or sum(row.get("byte_length", -1) for row in sources) != 875532
        or sum(row.get("byte_length", -1) for row in tests) != 198101
    ):
        errors.append(
            "phase1 Android report successor source/test set differs"
        )

    packet_bindings = {
        row["exact_path"]: row["sha256"]
        for row in all_rows
        if isinstance(row.get("exact_path"), str)
        and isinstance(row.get("sha256"), str)
    }
    if checkpoint is None:
        checkpoint_path = continuation.resolve_repo_file(
            root,
            CHECKPOINT_RELATIVE,
        )
        if checkpoint_path is not None:
            try:
                loaded_checkpoint = continuation.load_json(checkpoint_path)
            except (OSError, ValueError, json.JSONDecodeError):
                loaded_checkpoint = None
            if isinstance(loaded_checkpoint, dict):
                checkpoint = loaded_checkpoint
    fp046_transitions: dict[str, tuple[str, str]] = {}
    if isinstance(checkpoint, dict):
        (
            fp046_authority_errors,
            _fp046_artifact_bindings,
            fp046_transitions,
        ) = validate_fp046_r014_successor_authority(root, checkpoint)
        errors.extend(fp046_authority_errors)

    fp048_bindings: dict[str, str] = {}
    if (
        isinstance(checkpoint, dict)
        and _fp048_android_report_successor_is_declared(checkpoint)
    ):
        validated_fp048 = _fp048_android_report_successor_bindings(
            root,
            checkpoint,
            packet_bindings,
            require_live_after=not bool(fp046_transitions),
        )
        if validated_fp048 is None:
            errors.append(
                "phase1 Android report FP048 successor evidence differs"
            )
        else:
            fp048_bindings = validated_fp048

    current_bindings: dict[str, str] = {}
    expected_live_by_path: dict[str, str] = {}
    for row in all_rows:
        relative = row.get("exact_path")
        digest = row.get("sha256")
        byte_length = row.get("byte_length")
        if (
            not isinstance(relative, str)
            or not isinstance(digest, str)
            or continuation.SHA256_RE.fullmatch(digest) is None
            or not isinstance(byte_length, int)
            or isinstance(byte_length, bool)
            or byte_length < 0
            or _contains_symlink(root, relative)
        ):
            errors.append(
                "phase1 Android report successor source/test row is unsafe"
            )
            continue
        path = continuation.resolve_repo_file(root, relative)
        if path is None:
            errors.append(
                f"phase1 Android report successor source/test file is missing: "
                f"{relative}"
            )
            continue
        try:
            actual_size = path.stat().st_size
        except OSError:
            errors.append(
                f"phase1 Android report successor source/test file is unreadable: "
                f"{relative}"
            )
            continue
        expected_live_digest = fp048_bindings.get(relative, digest)
        fp046_transition = fp046_transitions.get(relative)
        if fp046_transition is not None:
            if fp046_transition[0] != expected_live_digest:
                errors.append(
                    "phase1 Android report FP046 successor lineage differs: "
                    f"{relative}"
                )
                continue
            expected_live_digest = fp046_transition[1]
        expected_live_by_path[relative] = expected_live_digest
        if (
            (
                relative not in fp048_bindings
                and relative not in fp046_transitions
                and actual_size != byte_length
            )
            or continuation.sha256_file(path) != expected_live_digest
        ):
            errors.append(
                f"phase1 Android report successor live binding differs: "
                f"{relative}"
            )
            continue
        current_bindings[relative] = expected_live_digest
    if len(current_bindings) != 32:
        errors.append(
            "phase1 Android report successor live binding set differs"
        )
    for relative, bridge in PHASE1_ANDROID_REPORT_SUCCESSOR_BRIDGES.items():
        bridge_rows = [
            row for row in all_rows if row.get("exact_path") == relative
        ]
        if (
            bridge_rows
            != [
                {
                    "byte_length": bridge["current_byte_count"],
                    "exact_path": relative,
                    "sha256": bridge["current_sha256"],
                }
            ]
            or current_bindings.get(relative)
            != expected_live_by_path.get(relative, bridge["current_sha256"])
        ):
            errors.append(
                f"phase1 Android report successor bridge differs: {relative}"
            )
    return (errors, {}) if errors else ([], current_bindings)


def _phase1_android_report_successor_bridge(
    root: Path,
    checkpoint: dict[str, Any],
    relative: str,
    predecessor_sha256: str,
    *,
    current_bindings: dict[str, str] | None = None,
) -> tuple[str, str] | None:
    bridge = PHASE1_ANDROID_REPORT_SUCCESSOR_BRIDGES.get(relative)
    if (
        bridge is None
        or predecessor_sha256 != bridge["predecessor_sha256"]
    ):
        return None
    start_snapshot = _fp047_successful_start_snapshot_artifacts(
        root,
        checkpoint,
    )
    if (
        start_snapshot is None
        or start_snapshot.get(relative) != predecessor_sha256
    ):
        return None
    errors, validated_bindings = (
        validate_phase1_android_report_successor_binding(
            root,
            checkpoint,
        )
    )
    if errors or (
        current_bindings is not None
        and current_bindings != validated_bindings
    ):
        return None
    current_bindings = validated_bindings
    current_sha256 = current_bindings.get(relative)
    live = continuation.resolve_repo_file(root, relative)
    if (
        not isinstance(current_sha256, str)
        or live is None
        or continuation.sha256_file(live) != current_sha256
    ):
        return None
    return predecessor_sha256, current_sha256


def validate_phone_mounting_current_state_successor_binding(
    root: Path,
) -> tuple[list[str], tuple[str, str] | None]:
    """Validate the reviewed exact-path, zero-credit state bridge."""
    errors: list[str] = []
    evidence_relative = PHONE_MOUNTING_SUCCESSOR_EVIDENCE_RELATIVE.as_posix()
    review_relative = PHONE_MOUNTING_SUCCESSOR_REVIEW_RELATIVE.as_posix()
    for label, relative in (
        ("evidence", evidence_relative),
        ("review", review_relative),
    ):
        if _contains_symlink(root, relative):
            errors.append(f"phone mounting successor {label} uses a symlink")
    evidence_path = continuation.resolve_repo_file(root, evidence_relative)
    review_path = continuation.resolve_repo_file(root, review_relative)
    if evidence_path is None:
        errors.append("phone mounting successor evidence is missing")
    if review_path is None:
        errors.append("phone mounting successor review is missing")
    if errors:
        return errors, None
    try:
        evidence_size = evidence_path.stat().st_size
        review_size = review_path.stat().st_size
    except OSError as exc:
        return [f"phone mounting successor binding cannot be read: {exc}"], None
    if evidence_size != PHONE_MOUNTING_SUCCESSOR_EVIDENCE_BYTE_COUNT:
        errors.append("phone mounting successor evidence byte count differs")
    if (
        continuation.sha256_file(evidence_path)
        != PHONE_MOUNTING_SUCCESSOR_EVIDENCE_SHA256
    ):
        errors.append("phone mounting successor evidence SHA-256 differs")
    if review_size != PHONE_MOUNTING_SUCCESSOR_REVIEW_BYTE_COUNT:
        errors.append("phone mounting successor review byte count differs")
    if (
        continuation.sha256_file(review_path)
        != PHONE_MOUNTING_SUCCESSOR_REVIEW_SHA256
    ):
        errors.append("phone mounting successor review SHA-256 differs")
    try:
        packet = continuation.load_json(evidence_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [
            f"phone mounting successor evidence cannot be loaded: {exc}"
        ], None

    expected_subject = {
        "path": PHONE_MOUNTING_SUCCESSOR_PATH,
        "predecessor_sha256": (
            PHONE_MOUNTING_SUCCESSOR_PREDECESSOR_SHA256
        ),
        "current_sha256": PHONE_MOUNTING_SUCCESSOR_CURRENT_SHA256,
        "current_byte_count": PHONE_MOUNTING_SUCCESSOR_CURRENT_BYTE_COUNT,
        "bridge_kind": "NON_CAUSAL_ZERO_CREDIT_CURRENT_STATE_BINDING",
    }
    expected_anchors = [
        {
            "anchor_kind": "FP015_IMPLEMENTATION_RECORD_CHANGED_ARTIFACT",
            "document_path": (
                "docs/control/execution/goal-results/"
                "WS-GOAL-EPIC-02-FP-015-R001/implementation-record.json"
            ),
            "document_sha256": (
                "696b89c8e95c979e40174cc7e6423fabad54fb137ce6cf75fe4724004f12f8aa"
            ),
            "document_byte_count": 14885,
            "json_pointer": "/changed_artifacts/23",
            "row_projection": {
                "path": PHONE_MOUNTING_SUCCESSOR_PATH,
                "before_sha256": (
                    "99cfd48ca29a8036d4b9b0e3cdb0290298114f1ae00fbe3e0172d2cb29ce15b6"
                ),
                "after_sha256": (
                    PHONE_MOUNTING_SUCCESSOR_PREDECESSOR_SHA256
                ),
                "before_source": "GATE_DIRTY_SNAPSHOT",
                "change_kind": "MODIFIED",
            },
        },
        {
            "anchor_kind": "FP047_START_REPOSITORY_STATE_DIRTY_PATH",
            "document_path": (
                "docs/control/execution/goal-gates/"
                "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP047-20260726-005/"
                "19-REPOSITORY_STATE.log"
            ),
            "document_sha256": (
                "85a159407968746aeccd9031a4431dc87c6dc80db2791580db989c878de1e457"
            ),
            "document_byte_count": 599492,
            "json_pointer": "/dirty_snapshot/paths/169",
            "row_projection": {
                "path": PHONE_MOUNTING_SUCCESSOR_PATH,
                "path_role": "CURRENT",
                "status_kind": "UNTRACKED",
                "worktree_state": "PRESENT",
                "worktree_type": "REGULAR_FILE",
                "worktree_byte_count": 24106,
                "worktree_sha256": (
                    PHONE_MOUNTING_SUCCESSOR_PREDECESSOR_SHA256
                ),
            },
        },
    ]
    expected_observation = {
        "observed_at": "2026-07-29T02:34:05+09:00",
        "path": PHONE_MOUNTING_SUCCESSOR_PATH,
        "file_type": "REGULAR_FILE",
        "byte_count": PHONE_MOUNTING_SUCCESSOR_CURRENT_BYTE_COUNT,
        "sha256": PHONE_MOUNTING_SUCCESSOR_CURRENT_SHA256,
        "source_mutation_performed": False,
    }
    expected_boundary = {
        "causal_transition_proven": False,
        "execution_time_source_identity_proven": False,
        "test_execution_claimed": False,
        "artifact_completion_credit_delta": 0,
        "approval_credit_delta": 0,
        "formal_test_credit_delta": 0,
        "actual_event_credit_delta": 0,
        "release_status": "NOT_ELIGIBLE",
        "intended_use": (
            "EXACT_PATH_PREDECESSOR_TO_CURRENT_STATE_BRIDGE_ONLY"
        ),
    }
    expected_integrity = {
        "algorithm": "SHA-256",
        "canonicalization": (
            "UTF-8 recursive lexicographic JSON key order, compact form"
        ),
        "excluded_json_path": "$.integrity",
        "sha256": PHONE_MOUNTING_SUCCESSOR_NONSELF_SHA256,
    }
    expected_fields = {
        "schema_version",
        "packet_id",
        "created_on",
        "status",
        "subject",
        "historical_anchors",
        "current_observation",
        "claim_boundary",
        "integrity",
    }
    for label, actual, expected in (
        ("field set", set(packet), expected_fields),
        (
            "schema",
            packet.get("schema_version"),
            "walksafe.controlled-current-state-successor.v1",
        ),
        (
            "packet ID",
            packet.get("packet_id"),
            (
                "PHASE1-PHONE-MOUNTING-CURRENT-STATE-SUCCESSOR-"
                "20260729-R001"
            ),
        ),
        ("created_on", packet.get("created_on"), "2026-07-29"),
        (
            "status",
            packet.get("status"),
            "CONTROLLED_CURRENT_STATE_BINDING_ONLY",
        ),
        ("subject", packet.get("subject"), expected_subject),
        (
            "historical anchors",
            packet.get("historical_anchors"),
            expected_anchors,
        ),
        (
            "current observation",
            packet.get("current_observation"),
            expected_observation,
        ),
        ("claim boundary", packet.get("claim_boundary"), expected_boundary),
        ("integrity", packet.get("integrity"), expected_integrity),
    ):
        if actual != expected:
            errors.append(f"phone mounting successor {label} differs")
    if (
        continuation.canonical_json_sha256(packet, omit={"integrity"})
        != PHONE_MOUNTING_SUCCESSOR_NONSELF_SHA256
    ):
        errors.append("phone mounting successor non-self digest differs")

    for anchor in expected_anchors:
        relative = anchor["document_path"]
        if _contains_symlink(root, relative):
            errors.append(
                f"phone mounting successor anchor uses a symlink: {relative}"
            )
            continue
        path = continuation.resolve_repo_file(root, relative)
        if path is None:
            errors.append(
                f"phone mounting successor anchor is missing: {relative}"
            )
            continue
        try:
            actual_size = path.stat().st_size
        except OSError:
            errors.append(
                f"phone mounting successor anchor is unreadable: {relative}"
            )
            continue
        if (
            actual_size != anchor["document_byte_count"]
            or continuation.sha256_file(path) != anchor["document_sha256"]
        ):
            errors.append(
                f"phone mounting successor anchor binding differs: {relative}"
            )
    try:
        fp015 = continuation.load_json(
            root / expected_anchors[0]["document_path"]
        )
        repository_state = continuation.load_json(
            root / expected_anchors[1]["document_path"]
        )
        fp015_row = fp015["changed_artifacts"][23]
        repository_row = repository_state["dirty_snapshot"]["paths"][169]
        repository_projection = {
            "path": repository_row.get("path"),
            "path_role": repository_row.get("path_role"),
            "status_kind": repository_row.get("status", {}).get("kind"),
            "worktree_state": repository_row.get("worktree", {}).get("state"),
            "worktree_type": repository_row.get("worktree", {}).get("type"),
            "worktree_byte_count": repository_row.get("worktree", {}).get(
                "byte_count"
            ),
            "worktree_sha256": repository_row.get("worktree", {}).get(
                "sha256"
            ),
        }
    except (KeyError, IndexError, TypeError, OSError, ValueError) as exc:
        errors.append(f"phone mounting successor anchor row cannot load: {exc}")
    else:
        if fp015_row != expected_anchors[0]["row_projection"]:
            errors.append("phone mounting successor FP015 row differs")
        if repository_projection != expected_anchors[1]["row_projection"]:
            errors.append(
                "phone mounting successor FP047 repository row differs"
            )

    if _contains_symlink(root, PHONE_MOUNTING_SUCCESSOR_PATH):
        errors.append("phone mounting successor live path uses a symlink")
    live = continuation.resolve_repo_file(root, PHONE_MOUNTING_SUCCESSOR_PATH)
    if live is None:
        errors.append("phone mounting successor live path is missing")
    else:
        try:
            live_size = live.stat().st_size
        except OSError:
            errors.append("phone mounting successor live path is unreadable")
        else:
            if (
                live_size != PHONE_MOUNTING_SUCCESSOR_CURRENT_BYTE_COUNT
                or continuation.sha256_file(live)
                != PHONE_MOUNTING_SUCCESSOR_CURRENT_SHA256
            ):
                errors.append("phone mounting successor live binding differs")
    return (
        (errors, None)
        if errors
        else (
            [],
            (
                PHONE_MOUNTING_SUCCESSOR_PREDECESSOR_SHA256,
                PHONE_MOUNTING_SUCCESSOR_CURRENT_SHA256,
            ),
        )
    )


def _phone_mounting_current_state_successor_bridge(
    root: Path,
    relative: str,
    predecessor_sha256: str,
) -> tuple[str, str] | None:
    if (
        relative != PHONE_MOUNTING_SUCCESSOR_PATH
        or predecessor_sha256
        != PHONE_MOUNTING_SUCCESSOR_PREDECESSOR_SHA256
    ):
        return None
    errors, bridge = (
        validate_phone_mounting_current_state_successor_binding(root)
    )
    return None if errors else bridge


def validate_resource_pilot_current_state_successor_binding(
    root: Path,
) -> tuple[list[str], dict[str, tuple[str, str]]]:
    """Validate four reviewed post-hoc, zero-credit state bridges."""
    errors: list[str] = []
    evidence_relative = RESOURCE_PILOT_SUCCESSOR_EVIDENCE_RELATIVE.as_posix()
    review_relative = RESOURCE_PILOT_SUCCESSOR_REVIEW_RELATIVE.as_posix()
    for label, relative in (
        ("evidence", evidence_relative),
        ("review", review_relative),
    ):
        if _contains_symlink(root, relative):
            errors.append(
                f"resource pilot successor {label} uses a symlink"
            )
    evidence_path = continuation.resolve_repo_file(root, evidence_relative)
    review_path = continuation.resolve_repo_file(root, review_relative)
    if evidence_path is None:
        errors.append("resource pilot successor evidence is missing")
    if review_path is None:
        errors.append("resource pilot successor review is missing")
    if errors:
        return errors, {}
    try:
        evidence_size = evidence_path.stat().st_size
        review_size = review_path.stat().st_size
    except OSError as exc:
        return [
            f"resource pilot successor binding cannot be read: {exc}"
        ], {}
    if evidence_size != RESOURCE_PILOT_SUCCESSOR_EVIDENCE_BYTE_COUNT:
        errors.append("resource pilot successor evidence byte count differs")
    if (
        continuation.sha256_file(evidence_path)
        != RESOURCE_PILOT_SUCCESSOR_EVIDENCE_SHA256
    ):
        errors.append("resource pilot successor evidence SHA-256 differs")
    if review_size != RESOURCE_PILOT_SUCCESSOR_REVIEW_BYTE_COUNT:
        errors.append("resource pilot successor review byte count differs")
    if (
        continuation.sha256_file(review_path)
        != RESOURCE_PILOT_SUCCESSOR_REVIEW_SHA256
    ):
        errors.append("resource pilot successor review SHA-256 differs")
    try:
        packet = continuation.load_json(evidence_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [
            f"resource pilot successor evidence cannot be loaded: {exc}"
        ], {}

    expected_subjects = [
        {
            "path": relative,
            "predecessor_sha256": bridge["predecessor_sha256"],
            "predecessor_byte_count": bridge["predecessor_byte_count"],
            "current_sha256": bridge["current_sha256"],
            "current_byte_count": bridge["current_byte_count"],
            "bridge_kind": (
                "NON_CAUSAL_ZERO_CREDIT_CURRENT_STATE_BINDING"
            ),
        }
        for relative, bridge in RESOURCE_PILOT_SUCCESSOR_BRIDGES.items()
    ]
    expected_scope = {
        "subject_count": 4,
        "subject_paths": list(RESOURCE_PILOT_SUCCESSOR_BRIDGES),
        "subject_paths_are_exhaustive": True,
        "unlisted_path_or_whole_manifest_bridge_allowed": False,
        "source_mutation_performed": False,
    }
    expected_historical_rows = [
        {
            "json_pointer": (
                f"/dirty_snapshot/paths/{bridge['snapshot_index']}"
            ),
            "row_projection": {
                "path": relative,
                "path_role": "CURRENT",
                "status_kind": bridge["snapshot_status_kind"],
                "worktree_state": "PRESENT",
                "worktree_type": "REGULAR_FILE",
                "worktree_byte_count": bridge["predecessor_byte_count"],
                "worktree_sha256": bridge["predecessor_sha256"],
            },
        }
        for relative, bridge in RESOURCE_PILOT_SUCCESSOR_BRIDGES.items()
    ]
    expected_historical_anchor = {
        "anchor_kind": "FP047_START_REPOSITORY_STATE_DIRTY_PATHS",
        "document_path": RESOURCE_PILOT_SUCCESSOR_FP047_SNAPSHOT_PATH,
        "document_sha256": RESOURCE_PILOT_SUCCESSOR_FP047_SNAPSHOT_SHA256,
        "document_byte_count": (
            RESOURCE_PILOT_SUCCESSOR_FP047_SNAPSHOT_BYTE_COUNT
        ),
        "event_id": (
            "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP047-20260726-005"
        ),
        "dirty_path_count": 983,
        "rows": expected_historical_rows,
    }
    expected_manifest_rows = [
        {
            "json_pointer": (
                f"/source_set/entries/{bridge['manifest_index']}"
            ),
            "row_projection": {
                "byte_length": bridge["current_byte_count"],
                "path": relative,
                "sha256": bridge["current_sha256"],
            },
        }
        for relative, bridge in RESOURCE_PILOT_SUCCESSOR_BRIDGES.items()
    ]
    expected_manifest_anchor = {
        "document_path": RESOURCE_PILOT_SUCCESSOR_MANIFEST_PATH,
        "document_sha256": RESOURCE_PILOT_SUCCESSOR_MANIFEST_SHA256,
        "document_byte_count": RESOURCE_PILOT_SUCCESSOR_MANIFEST_BYTE_COUNT,
        "schema_version": "walksafe.resource-pilot-subject-manifest.v1",
        "manifest_id": "WS-RP-SUBJECT-ANDROID-20260727-001",
        "captured_at": "2026-07-27T19:41:59+09:00",
        "capture_relation_to_execution": "POST_HOC_CURRENT_STATE_ONLY",
        "execution_time_source_bytes_status": "NOT_CAPTURED",
        "source_set_entry_count": 253,
        "source_set_fingerprint_sha256": (
            "8d6d7bb7bd5a3edd30d0a2e2e72934015f92cad07c74c1843a24f0084dc39105"
        ),
        "nonself_integrity_sha256": (
            "e16aa531b2928025de8932b10f43ca889d8acd953b8a420d45baadb9c1e52b88"
        ),
        "rows": expected_manifest_rows,
    }
    expected_admission_decision = {
        "actual_device_credit": False,
        "admission_status": "CONDITIONAL",
        "evidence_credit": False,
        "formal_test_credit": False,
        "heavy_execution_allowed": False,
        "next_allowed_level": "MODERATE_SINGLE_LANE",
        "production_credit": False,
        "release_credit": False,
        "resource_pilot_run": True,
        "single_lane_only": True,
    }
    expected_manifest_binding = {
        "byte_length": RESOURCE_PILOT_SUCCESSOR_MANIFEST_BYTE_COUNT,
        "captured_at": "2026-07-27T19:41:59+09:00",
        "path": RESOURCE_PILOT_SUCCESSOR_MANIFEST_PATH,
        "sha256": RESOURCE_PILOT_SUCCESSOR_MANIFEST_SHA256,
        "subject_role": (
            "POST_HOC_CURRENT_SOURCE_MANIFEST_NOT_EXECUTION_TIME_PROOF"
        ),
    }
    expected_boundary_anchor = {
        "document_path": RESOURCE_PILOT_SUCCESSOR_BOUNDARY_PATH,
        "document_sha256": RESOURCE_PILOT_SUCCESSOR_BOUNDARY_SHA256,
        "document_byte_count": RESOURCE_PILOT_SUCCESSOR_BOUNDARY_BYTE_COUNT,
        "schema_version": "walksafe.resource-pilot-boundary.v1",
        "boundary_id": "WS-RESOURCE-PILOT-BOUNDARY-20260727-001",
        "nonself_integrity_sha256": (
            "a29f192976f9cd99a630cd35d0a338824534c4e11a225d27c2061138b78042d9"
        ),
        "execution_time_subject_bytes": "NOT_CAPTURED",
        "subject_manifest_binding_json_pointer": (
            "/subject_manifest_bindings/1"
        ),
        "subject_manifest_binding": expected_manifest_binding,
        "admission_decision": expected_admission_decision,
    }
    expected_resource_anchors = {
        "subject_manifest": expected_manifest_anchor,
        "boundary": expected_boundary_anchor,
    }
    expected_observations = {
        "observed_at": "2026-07-29T02:45:37+09:00",
        "source_mutation_performed": False,
        "rows": [
            {
                "path": relative,
                "file_type": "REGULAR_FILE",
                "byte_count": bridge["current_byte_count"],
                "sha256": bridge["current_sha256"],
            }
            for relative, bridge in RESOURCE_PILOT_SUCCESSOR_BRIDGES.items()
        ],
    }
    expected_claim_boundary = {
        "causal_transition_proven": False,
        "execution_time_source_identity_proven": False,
        "test_execution_claimed": False,
        "boundary_admission_decision_is_historical_projection_only": True,
        "current_admission_or_execution_authority_granted": False,
        "current_approval_claimed": False,
        "current_actual_event_claimed": False,
        "next_allowed_level_reissued": False,
        "resource_pilot_run_recredited": False,
        "subject_paths_are_exhaustive": True,
        "unlisted_path_or_whole_manifest_bridge_allowed": False,
        "resource_pilot_evidence_credit": False,
        "resource_pilot_formal_test_credit": False,
        "resource_pilot_actual_device_credit": False,
        "resource_pilot_production_credit": False,
        "artifact_completion_credit_delta": 0,
        "approval_credit_delta": 0,
        "formal_test_credit_delta": 0,
        "actual_event_credit_delta": 0,
        "release_status": "NOT_ELIGIBLE",
        "intended_use": (
            "EXACT_PATH_PREDECESSOR_TO_CURRENT_STATE_BRIDGE_ONLY"
        ),
    }
    expected_integrity = {
        "algorithm": "SHA-256",
        "canonicalization": (
            "UTF-8 recursive lexicographic JSON key order, compact form"
        ),
        "excluded_json_path": "$.integrity",
        "sha256": RESOURCE_PILOT_SUCCESSOR_NONSELF_SHA256,
    }
    expected_fields = {
        "schema_version",
        "packet_id",
        "created_on",
        "status",
        "scope",
        "subjects",
        "historical_anchor",
        "resource_pilot_anchors",
        "current_observations",
        "claim_boundary",
        "integrity",
    }
    for label, actual, expected in (
        ("field set", set(packet), expected_fields),
        (
            "schema",
            packet.get("schema_version"),
            (
                "walksafe.controlled-resource-pilot-current-state-"
                "successor.v1"
            ),
        ),
        (
            "packet ID",
            packet.get("packet_id"),
            (
                "PHASE1-RESOURCE-PILOT-CURRENT-STATE-SUCCESSOR-"
                "20260729-R001"
            ),
        ),
        ("created_on", packet.get("created_on"), "2026-07-29"),
        (
            "status",
            packet.get("status"),
            "CONTROLLED_CURRENT_STATE_BINDING_ONLY",
        ),
        ("scope", packet.get("scope"), expected_scope),
        ("subjects", packet.get("subjects"), expected_subjects),
        (
            "historical anchor",
            packet.get("historical_anchor"),
            expected_historical_anchor,
        ),
        (
            "resource pilot anchors",
            packet.get("resource_pilot_anchors"),
            expected_resource_anchors,
        ),
        (
            "current observations",
            packet.get("current_observations"),
            expected_observations,
        ),
        (
            "claim boundary",
            packet.get("claim_boundary"),
            expected_claim_boundary,
        ),
        ("integrity", packet.get("integrity"), expected_integrity),
    ):
        if actual != expected:
            errors.append(f"resource pilot successor {label} differs")
    if (
        continuation.canonical_json_sha256(packet, omit={"integrity"})
        != RESOURCE_PILOT_SUCCESSOR_NONSELF_SHA256
    ):
        errors.append("resource pilot successor non-self digest differs")

    physical_anchors = (
        (
            "FP047 snapshot",
            RESOURCE_PILOT_SUCCESSOR_FP047_SNAPSHOT_PATH,
            RESOURCE_PILOT_SUCCESSOR_FP047_SNAPSHOT_SHA256,
            RESOURCE_PILOT_SUCCESSOR_FP047_SNAPSHOT_BYTE_COUNT,
        ),
        (
            "subject manifest",
            RESOURCE_PILOT_SUCCESSOR_MANIFEST_PATH,
            RESOURCE_PILOT_SUCCESSOR_MANIFEST_SHA256,
            RESOURCE_PILOT_SUCCESSOR_MANIFEST_BYTE_COUNT,
        ),
        (
            "boundary",
            RESOURCE_PILOT_SUCCESSOR_BOUNDARY_PATH,
            RESOURCE_PILOT_SUCCESSOR_BOUNDARY_SHA256,
            RESOURCE_PILOT_SUCCESSOR_BOUNDARY_BYTE_COUNT,
        ),
    )
    anchor_paths: dict[str, Path] = {}
    for label, relative, expected_sha256, expected_size in physical_anchors:
        if _contains_symlink(root, relative):
            errors.append(
                f"resource pilot successor {label} uses a symlink"
            )
            continue
        path = continuation.resolve_repo_file(root, relative)
        if path is None:
            errors.append(f"resource pilot successor {label} is missing")
            continue
        try:
            actual_size = path.stat().st_size
        except OSError:
            errors.append(f"resource pilot successor {label} is unreadable")
            continue
        if (
            actual_size != expected_size
            or continuation.sha256_file(path) != expected_sha256
        ):
            errors.append(
                f"resource pilot successor {label} binding differs"
            )
            continue
        anchor_paths[label] = path

    try:
        repository_state = continuation.load_json(anchor_paths["FP047 snapshot"])
        subject_manifest = continuation.load_json(
            anchor_paths["subject manifest"]
        )
        boundary = continuation.load_json(anchor_paths["boundary"])
    except (KeyError, OSError, ValueError, json.JSONDecodeError) as exc:
        errors.append(
            f"resource pilot successor anchor document cannot load: {exc}"
        )
    else:
        dirty = repository_state.get("dirty_snapshot")
        dirty_paths = dirty.get("paths") if isinstance(dirty, dict) else None
        if (
            not isinstance(dirty_paths, list)
            or len(dirty_paths) != 983
            or dirty.get("dirty_path_count") != 983
        ):
            errors.append(
                "resource pilot successor FP047 snapshot set differs"
            )
        else:
            for relative, bridge in (
                RESOURCE_PILOT_SUCCESSOR_BRIDGES.items()
            ):
                row = dirty_paths[bridge["snapshot_index"]]
                worktree = (
                    row.get("worktree") if isinstance(row, dict) else None
                )
                status = row.get("status") if isinstance(row, dict) else None
                projection = {
                    "path": row.get("path") if isinstance(row, dict) else None,
                    "path_role": (
                        row.get("path_role")
                        if isinstance(row, dict)
                        else None
                    ),
                    "status_kind": (
                        status.get("kind")
                        if isinstance(status, dict)
                        else None
                    ),
                    "worktree_state": (
                        worktree.get("state")
                        if isinstance(worktree, dict)
                        else None
                    ),
                    "worktree_type": (
                        worktree.get("type")
                        if isinstance(worktree, dict)
                        else None
                    ),
                    "worktree_byte_count": (
                        worktree.get("byte_count")
                        if isinstance(worktree, dict)
                        else None
                    ),
                    "worktree_sha256": (
                        worktree.get("sha256")
                        if isinstance(worktree, dict)
                        else None
                    ),
                }
                if projection != next(
                    item["row_projection"]
                    for item in expected_historical_rows
                    if item["row_projection"]["path"] == relative
                ):
                    errors.append(
                        "resource pilot successor FP047 row differs: "
                        f"{relative}"
                    )

        source_set = subject_manifest.get("source_set")
        entries = (
            source_set.get("entries")
            if isinstance(source_set, dict)
            else None
        )
        manifest_integrity = subject_manifest.get("integrity")
        if (
            subject_manifest.get("schema_version")
            != "walksafe.resource-pilot-subject-manifest.v1"
            or subject_manifest.get("manifest_id")
            != "WS-RP-SUBJECT-ANDROID-20260727-001"
            or subject_manifest.get("captured_at")
            != "2026-07-27T19:41:59+09:00"
            or subject_manifest.get("capture_relation_to_execution")
            != "POST_HOC_CURRENT_STATE_ONLY"
            or subject_manifest.get("execution_time_source_bytes_status")
            != "NOT_CAPTURED"
            or not isinstance(entries, list)
            or len(entries) != 253
            or source_set.get("entry_count") != 253
            or source_set.get("fingerprint_sha256")
            != expected_manifest_anchor["source_set_fingerprint_sha256"]
            or not isinstance(manifest_integrity, dict)
            or manifest_integrity.get("sha256")
            != expected_manifest_anchor["nonself_integrity_sha256"]
        ):
            errors.append(
                "resource pilot successor subject manifest contract differs"
            )
        else:
            for relative, bridge in (
                RESOURCE_PILOT_SUCCESSOR_BRIDGES.items()
            ):
                expected_row = {
                    "byte_length": bridge["current_byte_count"],
                    "path": relative,
                    "sha256": bridge["current_sha256"],
                }
                if entries[bridge["manifest_index"]] != expected_row:
                    errors.append(
                        "resource pilot successor manifest row differs: "
                        f"{relative}"
                    )

        boundary_integrity = boundary.get("integrity")
        capture_limits = boundary.get("capture_limits")
        manifest_bindings = boundary.get("subject_manifest_bindings")
        if (
            boundary.get("schema_version")
            != "walksafe.resource-pilot-boundary.v1"
            or boundary.get("boundary_id")
            != "WS-RESOURCE-PILOT-BOUNDARY-20260727-001"
            or not isinstance(boundary_integrity, dict)
            or boundary_integrity.get("sha256")
            != expected_boundary_anchor["nonself_integrity_sha256"]
            or not isinstance(capture_limits, dict)
            or capture_limits.get("execution_time_subject_bytes")
            != "NOT_CAPTURED"
            or not isinstance(manifest_bindings, list)
            or len(manifest_bindings) != 2
            or manifest_bindings[1] != expected_manifest_binding
            or boundary.get("admission_decision")
            != expected_admission_decision
        ):
            errors.append(
                "resource pilot successor boundary contract differs"
            )

    current_bindings: dict[str, tuple[str, str]] = {}
    for relative, bridge in RESOURCE_PILOT_SUCCESSOR_BRIDGES.items():
        if _contains_symlink(root, relative):
            errors.append(
                f"resource pilot successor live path uses a symlink: {relative}"
            )
            continue
        live = continuation.resolve_repo_file(root, relative)
        if live is None:
            errors.append(
                f"resource pilot successor live path is missing: {relative}"
            )
            continue
        try:
            live_size = live.stat().st_size
        except OSError:
            errors.append(
                f"resource pilot successor live path is unreadable: {relative}"
            )
            continue
        if (
            live_size != bridge["current_byte_count"]
            or continuation.sha256_file(live) != bridge["current_sha256"]
        ):
            errors.append(
                f"resource pilot successor live binding differs: {relative}"
            )
            continue
        current_bindings[relative] = (
            bridge["predecessor_sha256"],
            bridge["current_sha256"],
        )
    if len(current_bindings) != len(RESOURCE_PILOT_SUCCESSOR_BRIDGES):
        errors.append("resource pilot successor live binding set differs")
    return (errors, {}) if errors else ([], current_bindings)


def _resource_pilot_current_state_successor_bridge(
    root: Path,
    checkpoint: dict[str, Any],
    relative: str,
    predecessor_sha256: str,
    *,
    current_bindings: dict[str, tuple[str, str]] | None = None,
) -> tuple[str, str] | None:
    bridge = RESOURCE_PILOT_SUCCESSOR_BRIDGES.get(relative)
    if (
        bridge is None
        or predecessor_sha256 != bridge["predecessor_sha256"]
    ):
        return None
    start_snapshot = _fp047_successful_start_snapshot_artifacts(
        root,
        checkpoint,
    )
    if (
        start_snapshot is None
        or start_snapshot.get(relative) != predecessor_sha256
    ):
        return None
    if current_bindings is None:
        errors, current_bindings = (
            validate_resource_pilot_current_state_successor_binding(root)
        )
        if errors:
            return None
    expected = (
        predecessor_sha256,
        bridge["current_sha256"],
    )
    live = continuation.resolve_repo_file(root, relative)
    if (
        current_bindings.get(relative) != expected
        or live is None
        or continuation.sha256_file(live) != bridge["current_sha256"]
    ):
        return None
    return expected


def _frozen_preimage_redirect(
    root: Path,
    path: Path,
    preimages: dict[tuple[str, str], Path],
) -> Path:
    try:
        resolved = path.resolve(strict=True)
        resolved.relative_to(root.resolve(strict=True))
    except (OSError, RuntimeError, ValueError):
        return path
    for role, expected in CANONICAL_PREIMAGE_EXPECTED.items():
        live = continuation.resolve_repo_file(root, expected["live_path"])
        if live is None or resolved != live:
            continue
        historical = resolve_canonical_binding_file(
            root,
            expected["live_path"],
            expected["historical_sha256"],
            mode="SEALED_HISTORY",
            preimages=preimages,
        )
        if historical is not None:
            return historical
        raise ValueError(f"sealed historical preimage cannot be resolved: {role}")
    return path

V23_PACKAGE_ID = continuation.V23_PACKAGE_ID
V23_PLAN_VERSION = continuation.V23_PLAN_VERSION
V23_MANIFEST_RELATIVE = continuation.V23_MANIFEST_RELATIVE
V23_MANIFEST_SHA256 = continuation.V23_MANIFEST_SHA256
V23_ARCHIVE_RELATIVE = continuation.V23_ARCHIVE_RELATIVE
V23_ARCHIVE_RAW_SHA256 = continuation.V23_ARCHIVE_RAW_SHA256
V23_EVENT_COUNT = continuation.V23_EVENT_COUNT
V23_TAIL_SHA256 = continuation.V23_TAIL_SHA256
V24_PACKAGE_ID = continuation.V24_PACKAGE_ID
V24_PLAN_VERSION = continuation.V24_PLAN_VERSION
V24_MANIFEST_RELATIVE = continuation.V24_MANIFEST_RELATIVE
V24_CHECKPOINT_RELATIVE = continuation.V24_CHECKPOINT_RELATIVE

V23_CHECKER_RELATIVE = Path("scripts/check_walksafe_goal_graph_v2_3.py")
V24_PACKAGE_RELATIVE = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4"
)
V24_SUPERSESSION_RELATIVE = (
    V24_PACKAGE_RELATIVE / "active-supersession-record-v2.3.0.json"
)
CANONICAL_PREIMAGE_MANIFEST_RELATIVE = Path(
    "docs/control/history/canonical-preimages/manifests/"
    "v2.4-seq38-canonical-preimages.json"
)
CANONICAL_PREIMAGE_MANIFEST_SHA256 = (
    "6df22eb89b0e2fa571d34e3bd17001980ddf03e67726208e8ca4aee33ff3c349"
)
CANONICAL_PREIMAGE_SOURCE_EVENT_SHA256 = (
    "aca93931b1cd8dcc06508f4078a7983d729df43f6a6690fceb1b3e875090152f"
)
CANONICAL_PREIMAGE_EXPECTED = {
    "ARTIFACT_CHANGE_LOG": {
        "document_id": "ART-DOC-05-001",
        "live_path": (
            "docs/deliverables/00-control/artifact-change-log.json"
        ),
        "historical_sha256": (
            "c4b63a01c3ae30efecb0f08c21678057626af10ed8cfef258ddf90136d0aca1c"
        ),
        "historical_byte_count": 96734,
        "identity_json_path": "metadata.register_id",
        "identity_match": "EXACT_STRING",
        "successor_observed_sha256": (
            "cea8b58fc5afa527614522481df56d69618c6fd1015c10b63d6cc8b6e0678c67"
        ),
    },
    "ARTIFACT_REGISTER": {
        "document_id": "ART-DOC-01-001",
        "live_path": "docs/deliverables/00-control/artifact-register.json",
        "historical_sha256": (
            "c4a5259744febc9cc442785be1748a0e0da88dbc86ef6459f1f6ee74fcaf62b6"
        ),
        "historical_byte_count": 3499550,
        "identity_json_path": "metadata.register_id",
        "identity_match": "EXACT_STRING",
        "successor_observed_sha256": (
            "a0c993257c1b80211c2e8e4db5aa464708ac4aba9d47ed9b434eda277ee6547f"
        ),
    },
    "DESIGN_TRACEABILITY": {
        "document_id": "WS-DESIGN-TRACEABILITY-20260721-001",
        "live_path": (
            "docs/deliverables/04-design/design-traceability-register.json"
        ),
        "historical_sha256": (
            "1ffb5861887d8edb26efbf0019cd4547baa6a24d8a73a576c3a5e9adf1e892aa"
        ),
        "historical_byte_count": 459175,
        "identity_json_path": "metadata.register_id",
        "identity_match": "EXACT_STRING",
        "successor_observed_sha256": (
            "18775a3f4d5d1faf0ae692b27888613c3d43f0b276d5a71dfbb08aad47c6b4ae"
        ),
    },
    "MODULE_REGISTER": {
        "document_id": "DEV-18",
        "live_path": (
            "docs/deliverables/05-implementation/module-register.json"
        ),
        "historical_sha256": (
            "54f2119ccb8598f06261590f8855c8d4c442cd662c4502501b62164a2cc0fe49"
        ),
        "historical_byte_count": 62222,
        "identity_json_path": "metadata.artifact_type_ids",
        "identity_match": "STRING_LIST_CONTAINS_EXACT",
        "successor_observed_sha256": (
            "c0727ae24e53ce3142aa5c55db7d6273628655069c3ac0821fa7d03ad8f08b48"
        ),
    },
    "PLANNED_TEST_CASES": {
        "document_id": "TST-05",
        "live_path": (
            "docs/deliverables/06-testing/registers/test-cases.json"
        ),
        "historical_sha256": (
            "19a6b425bf3a0ee880f1c9229a42b96845ab4c3a1f99037783fe872426e0deee"
        ),
        "historical_byte_count": 2590342,
        "identity_json_path": "metadata.artifact_type_ids",
        "identity_match": "STRING_LIST_CONTAINS_EXACT",
        "successor_observed_sha256": (
            "fc51836c1dddd8852a74805e7fc5b1f6e647f461168139ae2565318745f87f2e"
        ),
    },
}
CANONICAL_PREIMAGE_ENTRY_FIELDS = {
    "role",
    "document_id",
    "live_path",
    "historical_sha256",
    "historical_byte_count",
    "identity_json_path",
    "identity_match",
    "identity_expected",
    "blob_path",
    "blob_sha256",
    "successor_observed_sha256",
}
CANONICAL_PREIMAGE_MODES = {"CURRENT_LIVE", "SEALED_HISTORY"}
PHASE1_ANDROID_REPORT_SUCCESSOR_EVIDENCE_RELATIVE = Path(
    "docs/control/execution/artifact-closure/run-20260727-001/packets/"
    "phase1-android-report-successor/evidence.json"
)
PHASE1_ANDROID_REPORT_SUCCESSOR_EVIDENCE_SHA256 = (
    "1e6aa72ef3306e4385d45336e90cecb699694aea6b19a8f8313c0989414adaa8"
)
PHASE1_ANDROID_REPORT_SUCCESSOR_EVIDENCE_BYTE_COUNT = 30287
PHASE1_ANDROID_REPORT_SUCCESSOR_REVIEW_RELATIVE = Path(
    "docs/control/execution/artifact-closure/run-20260727-001/"
    "phase1-android-report-successor-independent-review-r001.md"
)
PHASE1_ANDROID_REPORT_SUCCESSOR_REVIEW_SHA256 = (
    "b8f7bfed1f09566df66df19450c5a8e3c5bc171e21db0defbd18c98c60709ea3"
)
PHASE1_ANDROID_REPORT_SUCCESSOR_REVIEW_BYTE_COUNT = 2236
PHASE1_ANDROID_REPORT_SUCCESSOR_CONTENT_SHA256 = (
    "0f0eec806a5168151b361185c884dd850aa8239d32e555c3268fe2d8e5c2e5db"
)
PHASE1_ANDROID_REPORT_SUCCESSOR_MANIFEST_SHA256 = (
    "d22bbc3f5f4a007f8f848356104800a3180de7f05a9cb103bd2ecc0526931c9f"
)
PHASE1_ANDROID_REPORT_SUCCESSOR_BRIDGES = {
    (
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
        "MainActivity.kt"
    ): {
        "predecessor_sha256": (
            "da37fe07d2f849fd1af7aa9f335a1a9bdf3fee34443d037bb64fff3d069f9481"
        ),
        "current_sha256": (
            "17f5fe510c41e6a89f4ccc02693ed346388b357120247af365cd50dbc41f757e"
        ),
        "current_byte_count": 757627,
    },
    (
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
        "report/AndroidReportUploader.kt"
    ): {
        "predecessor_sha256": (
            "3bf6116f5598839edc5e2348b3e54d891360ad03c9c6013fa73a9089a4d37d5d"
        ),
        "current_sha256": (
            "5da74e5af5de0f2e977dabdd20a71541de3177eb4a35dd98a2c11f79e5bde264"
        ),
        "current_byte_count": 13861,
    },
    (
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
        "MainActivityAccountDeletionStaticTest.kt"
    ): {
        "predecessor_sha256": (
            "44b49ab6add861b948c63178ef46537b89df6f51795cbc1714aff5b68b275155"
        ),
        "current_sha256": (
            "1be7bbcd8854f8f1b3fb050c1206fd071e3daecf4eccef6e46b29ef8968f2a7e"
        ),
        "current_byte_count": 12989,
    },
    (
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
        "report/AndroidReportUploaderTest.kt"
    ): {
        "predecessor_sha256": (
            "96ec030a7178497dd42fddfc1a473c87b33b584ffa278b5025f0b714cb517e77"
        ),
        "current_sha256": (
            "c3d4cf89a8a264435f232916eaf91e39bc03108518fcaa499ada764fa8ea7ff2"
        ),
        "current_byte_count": 20790,
    },
    (
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
        "report/ReportPrivacyConsentSessionTest.kt"
    ): {
        "predecessor_sha256": (
            "ddeed74408ef64d0fa79d3d12d882a4d15cfb544ad9cffaec5c4c9c7296450a8"
        ),
        "current_sha256": (
            "aa18d2fc648b713751481f152d78638b2a0c5293fd435607bc86227e0f1bd4de"
        ),
        "current_byte_count": 22770,
    },
    (
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
        "MainActivityWithdrawalRestartStaticTest.kt"
    ): {
        "predecessor_sha256": (
            "a5f886f847e6ec4f483f096db30cac74e7a89de4956411ff279b02be27fb3fed"
        ),
        "current_sha256": (
            "46c1fba3167b9fb92b1c61f77645bc283b0b262f146b705ad0dd0b13f5518bc3"
        ),
        "current_byte_count": 8732,
    },
}
FP048_ANDROID_REPORT_GOAL_ID = "WS-GOAL-EPIC-03-FP-048-R001"
FP048_ANDROID_REPORT_GOAL_PATH = (
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/"
    "epic-03/epic-03-fp048-encryption-connection-security-incident-r001.md"
)
FP048_ANDROID_REPORT_GOAL_SHA256 = (
    "043b5a463914015a5918b885c3f69c242ece190358bd18ab1f884de46f553740"
)
FP048_ANDROID_REPORT_RESULT_DIRECTORY = (
    "docs/control/execution/goal-results/"
    f"{FP048_ANDROID_REPORT_GOAL_ID}"
)
FP048_ANDROID_REPORT_COMPLETION_ROLE = (
    f"WORK_ITEM_COMPLETION::{FP048_ANDROID_REPORT_GOAL_ID}"
)
FP048_ANDROID_REPORT_COMPLETION_DOCUMENT_ID = (
    "WS-FP048-ENCRYPTION-CONNECTION-SECURITY-WORK-ITEM-"
    "COMPLETION-20260802-001"
)
FP048_ANDROID_REPORT_COMPLETION_PATH = (
    f"{FP048_ANDROID_REPORT_RESULT_DIRECTORY}/completion-receipt.json"
)
FP048_ANDROID_REPORT_RESULT_PATH_BY_KIND = {
    "IMPLEMENTATION_RECORD": (
        f"{FP048_ANDROID_REPORT_RESULT_DIRECTORY}/implementation-record.json"
    ),
    "VERIFICATION_RESULT": (
        f"{FP048_ANDROID_REPORT_RESULT_DIRECTORY}/verification-result.json"
    ),
    "SUCCESSOR_TRACE": (
        f"{FP048_ANDROID_REPORT_RESULT_DIRECTORY}/successor-trace.json"
    ),
}
FP048_ANDROID_REPORT_REVIEW_SUBJECT_PATH = (
    f"{FP048_ANDROID_REPORT_RESULT_DIRECTORY}/review-subject.json"
)
FP048_ANDROID_REPORT_REVIEW_ATTESTATION_PATH = (
    f"{FP048_ANDROID_REPORT_RESULT_DIRECTORY}/review-attestation.json"
)
FP048_ANDROID_REPORT_REVIEW_PATH = (
    f"{FP048_ANDROID_REPORT_RESULT_DIRECTORY}/independent-review.json"
)
FP048_ANDROID_REPORT_RESULT_SHA256_BY_KIND = {
    "IMPLEMENTATION_RECORD": (
        "1990e550ffaab170dded19d4db9ac35fcff13594efd9c553e563fc76ee35fb39"
    ),
    "VERIFICATION_RESULT": (
        "499b2dd4651b7efa73a2d8904daa4aaea0eca6ac94e6734d4c6660fe0dc3160c"
    ),
    "SUCCESSOR_TRACE": (
        "5a3e4f2202bd5e83356fdc0886fbda9e3689d282f30bab08130208a20eb2231b"
    ),
}
FP048_ANDROID_REPORT_PINNED_JSON_SHA256_BY_PATH = {
    FP048_ANDROID_REPORT_COMPLETION_PATH: (
        "072dfee0086702250a15dbf320d58ba477561c5d1ec999349e59a55a4df56992"
    ),
    **{
        path: FP048_ANDROID_REPORT_RESULT_SHA256_BY_KIND[kind]
        for kind, path in FP048_ANDROID_REPORT_RESULT_PATH_BY_KIND.items()
    },
    FP048_ANDROID_REPORT_REVIEW_SUBJECT_PATH: (
        "f0acb113e28aefa4ccf1226b9fe713e04cb47350f41bf66d8a95b79e358cba92"
    ),
    FP048_ANDROID_REPORT_REVIEW_ATTESTATION_PATH: (
        "3ae10d0b35da3a392f8b16865ecda2d9e715f6ec0a6c40ddca8c3a1cd56e315c"
    ),
    FP048_ANDROID_REPORT_REVIEW_PATH: (
        "8744e885d9b84978b4689041b80872a4a9e4dc319e36fb25832fab29ca8d6893"
    ),
}
FP048_ANDROID_REPORT_CANONICAL_UPDATE_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-CANONICAL-BINDINGS-UPDATED-FP048-20260802-001"
)
FP048_ANDROID_REPORT_CANONICAL_UPDATE_EVENT_SHA256 = (
    "8b0dcfeee1934c99042114c727388cc3b0a6970c9dac4203bd86062d05d57996"
)
FP048_ANDROID_REPORT_COMPLETION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-COMPLETED-FP048-20260802-001"
)
FP048_ANDROID_REPORT_COMPLETION_EVENT_SHA256 = (
    "ad4addfbb44baa7a33e9e6640f591f20bbce22397fb3a120e26b8844a3f8b665"
)
FP048_ANDROID_REPORT_SUCCESSOR_BY_PATH = {
    (
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
        "MainActivity.kt"
    ): {
        "before_sha256": (
            "17f5fe510c41e6a89f4ccc02693ed346388b357120247af365cd50dbc41f757e"
        ),
        "after_sha256": (
            "f565b7b7c394726361b349ab3a20fa8517c62fdc525ce47a89b226fbfcd5d668"
        ),
    },
    (
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
        "report/AndroidAccountDeletionFallbackMarker.kt"
    ): {
        "before_sha256": (
            "746907805a196b3d73dcd4299db2706350953c691e6b23f42e8afee8859dfb07"
        ),
        "after_sha256": (
            "9f892970fec6b7d8b98b6958df6d2b3bae6aa1b172adf4d3e44ebeed5ab8d1e3"
        ),
    },
    (
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
        "MainActivityAccountDeletionStaticTest.kt"
    ): {
        "before_sha256": (
            "1be7bbcd8854f8f1b3fb050c1206fd071e3daecf4eccef6e46b29ef8968f2a7e"
        ),
        "after_sha256": (
            "07ab565dd0b6ffee22f1a1f0828fdde03e107d02ec505216b7570239fcb625f2"
        ),
    },
    (
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
        "MainActivityWithdrawalRestartStaticTest.kt"
    ): {
        "before_sha256": (
            "46c1fba3167b9fb92b1c61f77645bc283b0b262f146b705ad0dd0b13f5518bc3"
        ),
        "after_sha256": (
            "bfa955ae3723beb6fff649b95600fe5a61d718443201cd8a4289c8210dc9a8bf"
        ),
    },
    (
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
        "report/AndroidAccountDeletionFallbackMarkerTest.kt"
    ): {
        "before_sha256": (
            "c83ba6ce541b1639d16cbd6741c39705e8f50f29be32e284c797b46b090b5686"
        ),
        "after_sha256": (
            "eb90470d57d7a99c75ad8967ebe230cf42d7856d616b761bf1efa8de42ece50e"
        ),
    },
}
FP046_GOAL_ID = "WS-GOAL-EPIC-03-FP-046-R001"
FP046_COMPLETION_ROLE = f"WORK_ITEM_COMPLETION::{FP046_GOAL_ID}"
FP046_CANONICAL_UPDATE_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-CANONICAL-BINDINGS-UPDATED-FP046-20260810-001"
)
FP046_COMPLETION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-COMPLETED-FP046-20260810-001"
)
FP046_RESULT_DIRECTORY = f"docs/control/execution/goal-results/{FP046_GOAL_ID}"
FP046_IMPLEMENTATION_PATH = f"{FP046_RESULT_DIRECTORY}/implementation-record.json"
FP046_IMPLEMENTATION_SHA256 = (
    "ab15bcb8c733b10ba2465a5a6922293968f84db452c5a83094a0bc0112eb8bb6"
)
FP046_IMPLEMENTATION_BYTE_COUNT = 36254
FP046_COMPLETION_PATH = f"{FP046_RESULT_DIRECTORY}/completion-receipt.json"
FP046_COMPLETION_SHA256 = (
    "b3f7e5e94e5ce2beeeabdbc62fb5b871c38df3d6747362500193dc4269fa041f"
)
FP046_COMPLETION_BYTE_COUNT = 10986
FP046_START_GATE_PATH = (
    "docs/control/execution/goal-gates/"
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-20260809-005/"
    "implementation-start-gate-receipt.json"
)
FP046_START_GATE_SHA256 = (
    "ec17d2ad7f9a1216a9f0174588fe39e3411e9a9757c2dce6b3db48915978801e"
)
FP046_START_GATE_REPOSITORY_STATE_PATH = (
    "docs/control/execution/goal-gates/"
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-20260809-005/"
    "09-REPOSITORY_STATE.log"
)
FP046_START_GATE_REPOSITORY_STATE_SHA256 = (
    "f46e42faf5e824322036713759954bb38277cdf9a3f66752e79459164f6e9087"
)
FP046_R014_DIRECTORY = (
    "docs/control/execution/artifact-closure/run-20260727-001/packets/"
    "phase1-exact257-successor-r014"
)
FP046_R014_BINDING_BY_ROLE = {
    "EXACT257_R014_LEDGER": {
        "path": f"{FP046_R014_DIRECTORY}/phase1-exact257-successor-ledger-r014.json",
        "sha256": (
            "275e0f193f367b409c9498d44ed229736cad15f952cc3e7dd8c69d4f3d78ad5d"
        ),
        "byte_count": 2699594,
        "schema_version": "walksafe.phase1-exact257-successor-ledger.v14",
    },
    "EXACT257_R014_EVIDENCE": {
        "path": f"{FP046_R014_DIRECTORY}/evidence.json",
        "sha256": (
            "047d2f06203a9438c666da37c8c9f57827018fde08b5a7efcedc673fc4c7217f"
        ),
        "byte_count": 7485,
        "schema_version": "walksafe.phase1-exact257-successor-evidence.v14",
    },
    "EXACT257_R014_CHECK_RECEIPT": {
        "path": (
            f"{FP046_R014_DIRECTORY}/"
            "phase1-exact257-successor-check-receipt-r014.json"
        ),
        "sha256": (
            "5d0988a25d418cf1a801fbec47a3452255c8ab4065b18bd1ac80fbdbf91963bc"
        ),
        "byte_count": 8911,
        "schema_version": (
            "walksafe.phase1-exact257-successor-check-receipt.v14"
        ),
    },
}
FP046_R014_ARTIFACT_BINDING_BY_ROLE = {
    "ARTIFACT_CHANGE_LOG": {
        "path": "docs/deliverables/00-control/artifact-change-log.json",
        "sha256": "e25bc981af11837741161b55367253246e4e416816af9d2ba8a27ca8cee27437",
        "byte_count": 128839,
        "binding_id": "R014-SRC-004",
    },
    "ARTIFACT_REGISTER": {
        "path": "docs/deliverables/00-control/artifact-register.json",
        "sha256": "14f7d25e6896e8c00c6a1b33986725130a81698fb1c91264b78f2bb534f9baf8",
        "byte_count": 3831653,
        "binding_id": "R014-SRC-005",
    },
    "REQUIREMENTS_TRACEABILITY": {
        "path": "docs/deliverables/03-requirements/rtm.json",
        "sha256": "4086aecbc86ceb2f3d726e7458a289eddb0d5cbe0ce50f7cf6495985b6d9f13e",
        "byte_count": 2032711,
        "binding_id": "R014-SRC-006",
    },
    "DESIGN_TRACEABILITY": {
        "path": "docs/deliverables/04-design/design-traceability-register.json",
        "sha256": "7e53957c45ee607dcaf5a43d39abb4faea229eaeaa5fe3c4c291a86d29d878f3",
        "byte_count": 568875,
        "binding_id": "R014-SRC-007",
    },
    "IMPLEMENTATION_MANIFEST": {
        "path": "docs/deliverables/05-implementation/implementation-manifest.json",
        "sha256": "2f168b185fc15fcccbd99c3daa37300f08f6581e79374f854876d18608f64d79",
        "byte_count": 367435,
        "binding_id": "R014-SRC-008",
    },
    "MODULE_REGISTER": {
        "path": "docs/deliverables/05-implementation/module-register.json",
        "sha256": "4e4dbdddff0b90ed6b80f866b7952f1093bd2fa4db1dab07a0b78d91572af070",
        "byte_count": 171597,
        "binding_id": "R014-SRC-009",
    },
}
FP046_ZERO_CREDIT_BOUNDARY = {
    "actual_device_credit_count": 0,
    "actual_device_status": "NOT_RUN",
    "actual_personal_data_deletion_status": "NOT_RUN",
    "actual_rights_request_status": "NOT_RUN",
    "actual_user_or_guardian_status": "NOT_RUN",
    "artifact_approval_claimed": False,
    "external_legal_review_status": "NOT_RUN",
    "external_privacy_review_status": "NOT_RUN",
    "external_processor_status": "NOT_RUN",
    "formal_test_credit_count": 0,
    "formal_test_status": "NOT_RUN",
    "operational_backup_restore_status": "NOT_RUN",
    "operational_database_status": "NOT_RUN",
    "planned_test_ids": [f"TC-FP-046-{number:02d}" for number in range(1, 6)],
    "production_deployment_status": "NOT_RUN",
    "release_credit_count": 0,
    "release_gate_status": "NOT_RUN",
    "release_gates_waived": False,
    "release_status": "NOT_ELIGIBLE",
    "scope": "REPOSITORY_INTERNAL_FP046_IMPLEMENTATION_AND_AUTOMATED_VERIFICATION_ONLY",
}
PHONE_MOUNTING_SUCCESSOR_EVIDENCE_RELATIVE = Path(
    "docs/control/execution/artifact-closure/run-20260727-001/packets/"
    "phase1-phone-mounting-current-state-successor-r001/evidence.json"
)
PHONE_MOUNTING_SUCCESSOR_EVIDENCE_SHA256 = (
    "db166bed6a5ef7020d57c05445fe31f85b99a8b015f296891ea6d1f090fd554c"
)
PHONE_MOUNTING_SUCCESSOR_EVIDENCE_BYTE_COUNT = 3346
PHONE_MOUNTING_SUCCESSOR_NONSELF_SHA256 = (
    "55755953db29fd1626040e3be331c24cca66f9ec0bff89534a9f478cd9208028"
)
PHONE_MOUNTING_SUCCESSOR_REVIEW_RELATIVE = Path(
    "docs/control/execution/artifact-closure/run-20260727-001/"
    "phase1-phone-mounting-current-state-successor-independent-review-r001.md"
)
PHONE_MOUNTING_SUCCESSOR_REVIEW_SHA256 = (
    "745d29873e95d807860e39016e2471a6778876841f98a7f1e7bfbab407424114"
)
PHONE_MOUNTING_SUCCESSOR_REVIEW_BYTE_COUNT = 2418
PHONE_MOUNTING_SUCCESSOR_PATH = (
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "MainActivityPhoneMountingStaticTest.kt"
)
PHONE_MOUNTING_SUCCESSOR_PREDECESSOR_SHA256 = (
    "feea182c229e7bc6eec74afc571e4690b597937eaf20852f4bcdafe9bf5f4d3b"
)
PHONE_MOUNTING_SUCCESSOR_CURRENT_SHA256 = (
    "cf42f540e32b9228f094d9b4e6e742eb4e180abc818ccde0b5a9944315c2b7bd"
)
PHONE_MOUNTING_SUCCESSOR_CURRENT_BYTE_COUNT = 24190
RESOURCE_PILOT_SUCCESSOR_EVIDENCE_RELATIVE = Path(
    "docs/control/execution/artifact-closure/run-20260727-001/packets/"
    "phase1-resource-pilot-current-state-successor-r001/evidence.json"
)
RESOURCE_PILOT_SUCCESSOR_EVIDENCE_SHA256 = (
    "6d5e1d2a444fc0530a9e41336ff8c2e273cfc4fe54c142cb192638d02b08f488"
)
RESOURCE_PILOT_SUCCESSOR_EVIDENCE_BYTE_COUNT = 11316
RESOURCE_PILOT_SUCCESSOR_NONSELF_SHA256 = (
    "445da8b9eb8cfcab0a07878ec79bb6040ba2e92feddd4933d42d9486c0576579"
)
RESOURCE_PILOT_SUCCESSOR_REVIEW_RELATIVE = Path(
    "docs/control/execution/artifact-closure/run-20260727-001/"
    "phase1-resource-pilot-current-state-successor-independent-review-r001.md"
)
RESOURCE_PILOT_SUCCESSOR_REVIEW_SHA256 = (
    "ab79e565f20567a10b70fee230b04ff32372892498397ffa1188196467a8c0a2"
)
RESOURCE_PILOT_SUCCESSOR_REVIEW_BYTE_COUNT = 4869
RESOURCE_PILOT_SUCCESSOR_FP047_SNAPSHOT_PATH = (
    "docs/control/execution/goal-gates/"
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP047-20260726-005/"
    "19-REPOSITORY_STATE.log"
)
RESOURCE_PILOT_SUCCESSOR_FP047_SNAPSHOT_SHA256 = (
    "85a159407968746aeccd9031a4431dc87c6dc80db2791580db989c878de1e457"
)
RESOURCE_PILOT_SUCCESSOR_FP047_SNAPSHOT_BYTE_COUNT = 599492
RESOURCE_PILOT_SUCCESSOR_MANIFEST_PATH = (
    "docs/control/execution/artifact-closure/run-20260727-001/"
    "resource-pilot/subject-manifest-android.json"
)
RESOURCE_PILOT_SUCCESSOR_MANIFEST_SHA256 = (
    "c99fe6e6f009d084e688995ca9e19f083065d25af6a830785ee7f55d75bb5913"
)
RESOURCE_PILOT_SUCCESSOR_MANIFEST_BYTE_COUNT = 61975
RESOURCE_PILOT_SUCCESSOR_BOUNDARY_PATH = (
    "docs/control/execution/artifact-closure/run-20260727-001/"
    "resource-pilot/resource-pilot-boundary.json"
)
RESOURCE_PILOT_SUCCESSOR_BOUNDARY_SHA256 = (
    "d7e38de6a97c1ebb2bec5bb9550a30910ae6be7b2322b8f8c8198aa80698c79a"
)
RESOURCE_PILOT_SUCCESSOR_BOUNDARY_BYTE_COUNT = 5135
RESOURCE_PILOT_SUCCESSOR_BRIDGES = {
    "apps/android/USER_GUIDE.md": {
        "predecessor_sha256": (
            "d1aaab83217c88c6d86161172575ad4d42fcbf0c0c30063d7619b27a3256e61d"
        ),
        "predecessor_byte_count": 12232,
        "current_sha256": (
            "746d5c13b99171e8da2d562efcdfb5ec0de47ba9fe5d167b11d9edfcb2f2a82b"
        ),
        "current_byte_count": 13833,
        "snapshot_index": 93,
        "snapshot_status_kind": "UNTRACKED",
        "manifest_index": 7,
    },
    (
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
        "navigation/AndroidStepTracker.kt"
    ): {
        "predecessor_sha256": (
            "2a539912516c337c2acb346dacd3a9694368bd7ae1dbc7ee1be0df54be9b0d06"
        ),
        "predecessor_byte_count": 3933,
        "current_sha256": (
            "489b16b47928ddc4a67d29c4526ecd6afae179ba7a9884e748e25481f0b905f9"
        ),
        "current_byte_count": 4176,
        "snapshot_index": 136,
        "snapshot_status_kind": "ORDINARY",
        "manifest_index": 105,
    },
    (
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
        "network/AndroidNetworkTransferPolicy.kt"
    ): {
        "predecessor_sha256": (
            "5609f9b0cd912b0e00315b16dd27ddd32ee55dce9ee58e085cb754020aba1687"
        ),
        "predecessor_byte_count": 3524,
        "current_sha256": (
            "7befaf2a3f939c8bd7c382467d9b1bb2c5015f8324e103fcdfc84c0d17ef13e6"
        ),
        "current_byte_count": 12485,
        "snapshot_index": 142,
        "snapshot_status_kind": "UNTRACKED",
        "manifest_index": 120,
    },
    (
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
        "network/AndroidNetworkTransferPolicyTest.kt"
    ): {
        "predecessor_sha256": (
            "9b0419c5e055860c960ff6c5297860bff97cf4b2368feba188cc6af4bd3df63d"
        ),
        "predecessor_byte_count": 1600,
        "current_sha256": (
            "1eb282bad885e4aacf2d00ad880210cfadb0f87428825f473ecf9e19522e77b6"
        ),
        "current_byte_count": 16734,
        "snapshot_index": 195,
        "snapshot_status_kind": "UNTRACKED",
        "manifest_index": 215,
    },
}
REQUIREMENTS_TRACEABILITY_LIVE_SHA256 = (
    "1d73d1e6e0a47ea3833df779c945397d799b14e9b26fdac4bb577197a660a7bd"
)
ARCHIVED_REQUIREMENTS_TRACEABILITY_BINDING = {
    "role": "REQUIREMENTS_TRACEABILITY",
    "document_id": "WS-REQ-RTM-DRAFT-20260721-R001",
    "path": "docs/deliverables/03-requirements/rtm.json",
    "file_sha256": REQUIREMENTS_TRACEABILITY_LIVE_SHA256,
}
V23_REQUIREMENTS_TRACEABILITY_EVENT_SEQUENCES = (1, 4, 9, 14)
V22_REQUIREMENTS_TRACEABILITY_EVENT_SEQUENCES = (1, 6, 11, 17)
V22_ARCHIVE_RELATIVE = Path(
    "docs/control/goals/walksafe-completion-graph-v2-3/"
    "superseded-v2.2.0-active-checkpoint.json"
)
V22_ARCHIVE_RAW_SHA256 = (
    "c15d677c9d8f9b68227cb0b2ffaac3aef4794e2bdb031b8cc4ca4a4790fc6024"
)
FP048_REQUIREMENTS_TRACEABILITY_BINDING = {
    "role": "REQUIREMENTS_TRACEABILITY",
    "path": ARCHIVED_REQUIREMENTS_TRACEABILITY_BINDING["path"],
    "schema_version": "walksafe.requirements-traceability-draft.v1",
    "sha256": (
        "599c69fc6c97359fecd8ac9a535cb07a652626d3371b803dce71b00fcad54793"
    ),
}
FP048_REQUIREMENTS_TRACEABILITY_BYTE_COUNT = 1947842
FP008_ADMIN_REVIEW_GOAL_ID = "WS-GOAL-EPIC-03-FP-008-R001"
FP008_ADMIN_REVIEW_GOAL_SHA256 = (
    "2654fe5f595aefaecf974a4de70bad7946c2385cba9716200f2d2f49d849dd8e"
)
FP008_ADMIN_REVIEW_COMPLETION_ROLE = (
    f"WORK_ITEM_COMPLETION::{FP008_ADMIN_REVIEW_GOAL_ID}"
)
FP008_ADMIN_REVIEW_COMPLETION_DOCUMENT_ID = (
    "WS-FP008-ADMIN-REVIEW-DELIVERY-WORK-ITEM-COMPLETION-20260809-001"
)
FP008_ADMIN_REVIEW_COMPLETION_PATH = (
    "docs/control/execution/goal-results/"
    f"{FP008_ADMIN_REVIEW_GOAL_ID}/completion-receipt.json"
)
FP008_ADMIN_REVIEW_COMPLETION_SHA256 = (
    "d5592127c5b2bb30c0d0927554c91b17b1eb1c4b31f470c7aeef07030f43c608"
)
FP008_ADMIN_REVIEW_COMPLETION_BYTE_COUNT = 11461
FP008_ADMIN_REVIEW_CANONICAL_UPDATE_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-CANONICAL-BINDINGS-UPDATED-FP008-20260809-001"
)
FP008_ADMIN_REVIEW_COMPLETION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-COMPLETED-FP008-20260809-001"
)
FP008_ADMIN_REVIEW_COMPLETION_BINDING = {
    "role": FP008_ADMIN_REVIEW_COMPLETION_ROLE,
    "document_id": FP008_ADMIN_REVIEW_COMPLETION_DOCUMENT_ID,
    "path": FP008_ADMIN_REVIEW_COMPLETION_PATH,
    "file_sha256": FP008_ADMIN_REVIEW_COMPLETION_SHA256,
}
FP008_ADMIN_REVIEW_COMPLETION_CANONICAL_BINDING = {
    **FP008_ADMIN_REVIEW_COMPLETION_BINDING,
    "identity_json_path": "document_id",
    "mutable": False,
}
FP008_REQUIREMENTS_TRACEABILITY_BINDING = {
    "role": "REQUIREMENTS_TRACEABILITY",
    "path": ARCHIVED_REQUIREMENTS_TRACEABILITY_BINDING["path"],
    "schema_version": "walksafe.requirements-traceability-draft.v1",
    "sha256": (
        "eff6b14788626add6b774080674f18fc53cce81944330665a841db240a25ccf9"
    ),
}
FP008_REQUIREMENTS_TRACEABILITY_CANONICAL_BINDING = {
    "artifact_code": "REQ-16",
    "document_id": "WS-REQ-RTM-DRAFT-20260721-R001",
    "file_sha256": FP008_REQUIREMENTS_TRACEABILITY_BINDING["sha256"],
    "identity_json_path": "metadata.document_id",
    "mutable": True,
    "path": FP008_REQUIREMENTS_TRACEABILITY_BINDING["path"],
    "role": "REQUIREMENTS_TRACEABILITY",
}
FP008_REQUIREMENTS_TRACEABILITY_BYTE_COUNT = 1984351
FP008_ADMIN_REVIEW_WORK_ITEM_ID = "EPIC-03-FP008-ADMIN-REVIEW-DELIVERY"
FP008_ADMIN_REVIEW_GOAL_PATH = (
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/epic-03/"
    "epic-03-fp008-admin-review-delivery-r001.md"
)
FP008_ADMIN_REVIEW_PARENT_GOAL_ID = "WS-GOAL-EPIC-03"
FP008_ADMIN_REVIEW_PARENT_GOAL_PATH = (
    "docs/control/goals/walksafe-completion-graph-v2-2/workstreams/"
    "epic-03-account-admin-security.md"
)
FP008_ADMIN_REVIEW_PARENT_GOAL_SHA256 = (
    "7d2dfb7fe89bd30fc1f6113acb0b533a9fd91a6106cc2ab4798091763beae832"
)
FP008_ADMIN_REVIEW_SOURCE_RESUME_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-WORK-SESSION-RESUMED-FP008-20260809-005"
)
FP008_ADMIN_REVIEW_SOURCE_RESUME_EVENT_SHA256 = (
    "7674013bfab4cbdac572a0c809507aed0af36fa4f4fef91e0a7081426e62d7b0"
)
FP008_ADMIN_REVIEW_MANIFEST_SHA256 = (
    "7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07"
)
FP008_ADMIN_REVIEW_SOURCE_CHECKPOINT_VERSION = "1.25.0"
FP008_ADMIN_REVIEW_UPDATE_OCCURRED_AT = "2026-08-09T16:46:59+09:00"
FP008_ADMIN_REVIEW_COMPLETION_OCCURRED_AT = "2026-08-09T16:47:00+09:00"
FP008_ADMIN_REVIEW_UPDATE_EVENT_SHA256 = (
    "bc649dda9d6fe97480be1450c0b41885e9d6fc9d17c79b25d22ed2623bca5a21"
)
FP008_ADMIN_REVIEW_COMPLETION_EVENT_SHA256 = (
    "caa5d73aa19416d997e7ef416bfa9a9af5383bde95541ad93fd31e44ee6cafd4"
)
FP008_ADMIN_REVIEW_CANONICAL_SNAPSHOT_SHA256 = (
    "787ef584ba7be2c82519221f3796bfb3fb9dd64900275993cac82d270d3d6de0"
)
FP008_ADMIN_REVIEW_CANONICAL_BINDINGS_SHA256 = (
    "f18b4c5ed75f2fdc026fda28853baba6c59c04815a3103ce1fa8a51ecdac8e4c"
)
FP008_ADMIN_REVIEW_CHANGED_ROLES = [
    "ARTIFACT_CHANGE_LOG",
    "ARTIFACT_REGISTER",
    "DESIGN_TRACEABILITY",
    "IMPLEMENTATION_BACKLOG",
    "IMPLEMENTATION_GAP",
    "MODULE_REGISTER",
    "REQUIREMENTS_TRACEABILITY",
    FP008_ADMIN_REVIEW_COMPLETION_ROLE,
]
FP008_ADMIN_REVIEW_PRODUCED_ROLES = [
    "IMPLEMENTATION_BACKLOG",
    "IMPLEMENTATION_GAP",
]
FP008_ADMIN_REVIEW_ARTIFACT_SUBJECT_IDS = [
    "DLV-DES-06",
    "DLV-DEV-01",
    "DLV-DEV-18",
    "DLV-DOC-01",
    "DLV-DOC-05",
    "DLV-REQ-16",
]
FP008_ADMIN_REVIEW_CHANGED_SUBJECT_IDS_BY_ROLE = {
    "ARTIFACT_CHANGE_LOG": FP008_ADMIN_REVIEW_ARTIFACT_SUBJECT_IDS,
    "ARTIFACT_REGISTER": FP008_ADMIN_REVIEW_ARTIFACT_SUBJECT_IDS,
    "DESIGN_TRACEABILITY": ["FP-008"],
    "IMPLEMENTATION_BACKLOG": ["FP-008"],
    "IMPLEMENTATION_GAP": ["FP-008", "GAP-017"],
    "MODULE_REGISTER": ["FP-008"],
    "REQUIREMENTS_TRACEABILITY": ["FP-008"],
}
FP008_ADMIN_REVIEW_PRODUCER_SUBJECT_IDS_BY_ROLE = {
    role: FP008_ADMIN_REVIEW_CHANGED_SUBJECT_IDS_BY_ROLE[role]
    for role in FP008_ADMIN_REVIEW_PRODUCED_ROLES
}
FP008_ADMIN_REVIEW_UPDATE_EVENT_FIELDS = {
    "sequence",
    "event_id",
    "event_type",
    "occurred_on",
    "occurred_at",
    "previous_focus_goal_id",
    "previous_focus_content_sha256",
    "focus_goal_id",
    "focus_goal_content_sha256",
    "from_status",
    "to_status",
    "static_plan_manifest_sha256",
    "status_changes",
    "runtime_after",
    "blockers_after",
    "blocker_resolution_ids_after",
    "source_checkpoint_version",
    "evidence_refs",
    "previous_event_sha256",
    "produced_by_goal_id",
    "produced_binding_roles",
    "producer_completion_receipt_binding",
    "changed_binding_roles",
    "changed_subject_ids_by_role",
    "producer_output_subject_ids_by_role",
    "impact_closure_goal_ids",
    "impact_disposition_by_goal",
    "reopened_completion_event_sha256_by_goal",
    "canonical_binding_snapshot_after",
    "event_sha256",
}
FP008_ADMIN_REVIEW_COMPLETION_EVENT_FIELDS = {
    "sequence",
    "event_id",
    "event_type",
    "occurred_on",
    "occurred_at",
    "previous_focus_goal_id",
    "previous_focus_content_sha256",
    "focus_goal_id",
    "focus_goal_content_sha256",
    "subject_goal_id",
    "from_status",
    "to_status",
    "static_plan_manifest_sha256",
    "status_changes",
    "runtime_after",
    "blockers_after",
    "blocker_resolution_ids_after",
    "source_checkpoint_version",
    "evidence_refs",
    "previous_event_sha256",
    "canonical_update_event_sha256",
    "completion_receipt_binding",
    "completion_evidence_bindings",
    "completion_evidence_by_goal_after",
    "canonical_binding_snapshot_after",
    "event_sha256",
}
FP008_ADMIN_REVIEW_UPDATE_RUNTIME = {
    "focus_goal_id": FP008_ADMIN_REVIEW_GOAL_ID,
    "focus_goal_path": FP008_ADMIN_REVIEW_GOAL_PATH,
    "focus_work_item_id": FP008_ADMIN_REVIEW_WORK_ITEM_ID,
    "focus_source": "IMPLEMENTATION_BACKLOG",
    "ready_frontier_goal_ids": [
        FP008_ADMIN_REVIEW_GOAL_ID,
        FP008_ADMIN_REVIEW_PARENT_GOAL_ID,
        "WS-GOAL-EPIC-12",
    ],
    "blocked_goal_ids": [],
    "pending_questions": [],
    "open_question_count": 0,
    "artifact_work_queue_sha256": (
        "2f6b3fdfed8d7a70a582d861edd4feee7ed35dab30d2803c061f4bf716340314"
    ),
    "completion_boundary_sha256": (
        "3cf29ba9c32ccfeeaa4881ddd6b14b20538e340b01f2725658efc8fbde521bed"
    ),
    "activation_status": "ACTIVE",
    "package_status": "ACTIVE",
}
FP008_ADMIN_REVIEW_COMPLETION_RUNTIME = {
    **FP008_ADMIN_REVIEW_UPDATE_RUNTIME,
    "focus_goal_id": FP008_ADMIN_REVIEW_PARENT_GOAL_ID,
    "focus_goal_path": FP008_ADMIN_REVIEW_PARENT_GOAL_PATH,
    "focus_work_item_id": "",
    "focus_source": "WORKSTREAM_GRAPH",
    "ready_frontier_goal_ids": [
        FP008_ADMIN_REVIEW_PARENT_GOAL_ID,
        "WS-GOAL-EPIC-12",
    ],
    "completion_boundary_sha256": (
        "c895b07f5635813e67415c990cc6d9203bb7ad680837a1e2e216ca39af35e453"
    ),
}
_FP008_COMPLETED_WORK_ITEM_GOAL_IDS = (
    "WS-GOAL-EPIC-02-FP-004-R001",
    "WS-GOAL-EPIC-02-FP-005-R001",
    "WS-GOAL-EPIC-02-FP-006-R001",
    "WS-GOAL-EPIC-02-FP-010-R001",
    "WS-GOAL-EPIC-02-FP-011-R001",
    "WS-GOAL-EPIC-02-FP-012-R001",
    "WS-GOAL-EPIC-02-FP-013-R001",
    "WS-GOAL-EPIC-02-FP-014-R001",
    "WS-GOAL-EPIC-02-FP-015-R001",
    "WS-GOAL-EPIC-02-FP-016-R001",
    "WS-GOAL-EPIC-02-FP-018-R001",
    "WS-GOAL-EPIC-02-NPC-PERMISSION-SESSION-LIFECYCLE-R001",
    "WS-GOAL-EPIC-03-FP-047-R001",
    "WS-GOAL-EPIC-03-FP-048-R001",
    FP008_ADMIN_REVIEW_GOAL_ID,
)
FP008_ADMIN_REVIEW_COMPLETION_EVIDENCE_BY_GOAL = {
    "WS-GOAL-EPIC-01": [
        "EPIC01_PHASE_G_RECORD",
        "IMPLEMENTATION_BACKLOG",
    ],
    **{
        goal_id: [f"WORK_ITEM_COMPLETION::{goal_id}"]
        for goal_id in _FP008_COMPLETED_WORK_ITEM_GOAL_IDS
    },
}
FP008_ADMIN_REVIEW_COMPLETION_BOUNDARY = {
    "scope": (
        "REPOSITORY_INTERNAL_FP008_IMPLEMENTATION_AND_"
        "AUTOMATED_VERIFICATION_ONLY"
    ),
    "planned_test_ids": [f"TC-FP-008-{number:02d}" for number in range(1, 5)],
    "formal_test_status": "NOT_RUN",
    "formal_test_credit_count": 0,
    "actual_device_status": "NOT_RUN",
    "actual_device_credit_count": 0,
    "external_institution_status": "NOT_RUN",
    "external_authentication_status": "NOT_RUN",
    "external_security_review_status": "NOT_RUN",
    "external_privacy_review_status": "NOT_RUN",
    "operational_database_status": "NOT_RUN",
    "production_deployment_status": "NOT_RUN",
    "release_gate_status": "NOT_RUN",
    "release_gates_waived": False,
    "artifact_approval_claimed": False,
    "release_status": "NOT_ELIGIBLE",
    "release_credit_count": 0,
}
FP008_ADMIN_REVIEW_AUTHORITY_BOUNDARY = {
    "creates_artifact_type": False,
    "normative_policy_source": False,
    "precedence": [
        "COMMITTED_ARTIFACT_APPLICATION_RECEIPT",
        "EFFECTIVE_POLICY_BASELINE",
        "DOC_01_CURRENT_STATE",
        "DOC_05_CHANGE_HISTORY",
        "LATEST_IMPLEMENTATION_GAP_AND_BACKLOG",
        "THIS_CHECKPOINT",
    ],
    "supported_control_artifact_ids": ["DOC-01", "DOC-03", "DOC-04"],
}
FP008_ADMIN_REVIEW_VERIFICATION_BOUNDARY = {
    "actual_device_test_status": "NOT_RUN",
    "all_remaining_gate_status": "NOT_RUN",
    "approved_production_profile_count": 0,
    "formal_test_not_run_count": 279,
    "formal_test_pass_claimed": False,
    "formal_test_total": 279,
    "implementation_conformance_claimed": False,
    "release_eligible": False,
    "remaining_gate_ids": [
        "GATE-PHONE-QUEUE-BYTE-LIMIT",
        "GATE-SERVER-CAPACITY-STATE-CONTRACT",
        "GATE-RAW-COLLECTION-RELEASE-REVIEW",
        "GATE-CLOUD-COST-MEASUREMENT",
        "GATE-SINGLE-ADMIN-RECOVERY-DRILL",
    ],
}
FP008_ADMIN_REVIEW_APPROVED_STATE = {
    "application_id": "WS-ARTIFACT-BASELINE-APPLICATION-20260722-001",
    "application_receipt_id": (
        "WS-ARTIFACT-BASELINE-APPLICATION-RECEIPT-20260722-001"
    ),
    "artifact_state_counts": {
        "ACTIVE": 27,
        "APPROVED_BASELINED": 102,
        "DRAFT": 53,
        "PLANNED_NOT_RUN": 75,
    },
    "formal_test_count": 279,
    "formal_test_not_run_count": 279,
    "policy_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
    "policy_baseline_version": "1.0.1",
    "release_status": "NOT_ELIGIBLE",
    "remaining_gate_count": 5,
    "remaining_gates_waived": False,
    "transaction_status": "COMMITTED",
}
FP008_ADMIN_REVIEW_STANDING_EXECUTION_AUTHORITY = [
    "REPOSITORY_SCOPED_IMPLEMENTATION",
    "INTERNAL_VERIFICATION",
    "DRAFT_AUTHORING",
    "ACTIVE_FACT_RECORDING",
    "DELEGATED_INTERNAL_DOCUMENT_APPROVAL",
    "SUCCESSOR_TRACE_GENERATION",
    "CHECKPOINT_DAYLOG_MEMORY_UPDATE",
    "DEPENDENCY_READY_NEXT_GOAL_START",
]
FP008_ADMIN_REVIEW_CURRENT_WORK_SAFE_FIELDS = {
    "release_completion_claimed": False,
    "target_completion_level": "IMPLEMENTATION_READY",
    "policy_change_required": False,
    "status": "IN_PROGRESS",
    "work_item_id": "EPIC-03-FP046-CONSENT-WITHDRAWAL-DELETION",
    "current_focus": (
        "FP008/GAP-017 COMPLETE_AT_TARGET; FP046/GAP-055 PLANNED_NEXT"
    ),
}
FP008_ADMIN_REVIEW_HANDOFF_SAFE_FIELDS = {
    "current_epic": "EPIC-03 / FP046/GAP-055 PLANNED_NEXT",
    "last_updated_by_work_item": FP008_ADMIN_REVIEW_WORK_ITEM_ID,
    "last_verification_status": (
        "PASS_INTERNAL_ONLY_FORMAL_EXTERNAL_DEVICE_RELEASE_NOT_RUN"
    ),
}
FP008_ADMIN_REVIEW_CURRENT_WORK_SHA256 = (
    "b5981bdd631c556ea752cfce599c82113ce9fe529ad1b1b56867c87e52b81426"
)
FP008_ADMIN_REVIEW_HANDOFF_SHA256 = (
    "1059241083236866e4017f1ae1bf4f5d282d55bbc5897903a8507ad28015ef70"
)
FP008_ADMIN_REVIEW_SUFFIX_CURRENT_WORK_SAFE_FIELDS = {
    key: FP008_ADMIN_REVIEW_CURRENT_WORK_SAFE_FIELDS[key]
    for key in (
        "release_completion_claimed",
        "target_completion_level",
        "policy_change_required",
    )
}
FP008_ADMIN_REVIEW_DEFERRED_RELEASE_GATE_IDS = [
    "GATE-CLOUD-COST-MEASUREMENT",
    "GATE-RAW-COLLECTION-RELEASE-REVIEW",
    "GATE-SERVER-CAPACITY-STATE-CONTRACT",
    "GATE-SINGLE-ADMIN-RECOVERY-DRILL",
]
FP008_ADMIN_REVIEW_HANDOFF_RELEASE_GATES = [
    {
        "id": gate_id,
        "kind": "RELEASE_GATE",
        "status": "NOT_RUN",
        "waived": False,
    }
    for gate_id in FP008_ADMIN_REVIEW_VERIFICATION_BOUNDARY[
        "remaining_gate_ids"
    ]
]
FP008_ADMIN_REVIEW_FORBIDDEN_FUTURE_ACTION_TOKENS = (
    "actual device",
    "deploy",
    "formal",
    "production",
    "release",
    "게이트 면제",
    "릴리스",
    "배포",
    "실기기",
    "실제 기기",
    "정식 시험",
    "출시",
)
FP008_ADMIN_REVIEW_COMPLETION_BOUNDARY_SAFE_FIELDS = {
    "repository_scope_status": "IN_PROGRESS",
    "project_status": "NOT_COMPLETE",
}
FP008_ADMIN_REVIEW_FORBIDDEN_RELEASE_FIELDS = {
    "formal_test_pass_claimed",
    "release_eligible",
    "release_status",
    "release_completion_claimed",
    "target_release",
}
V24_NATIVE_PATHS = {
    V24_MANIFEST_RELATIVE.as_posix(),
    (V24_PACKAGE_RELATIVE / "README.md").as_posix(),
    V24_SUPERSESSION_RELATIVE.as_posix(),
    V23_ARCHIVE_RELATIVE.as_posix(),
    (
        V24_PACKAGE_RELATIVE / "templates/dynamic-node-template.md"
    ).as_posix(),
    (
        V24_PACKAGE_RELATIVE / "templates/policy-gap-work-item.md"
    ).as_posix(),
}

CHECKPOINT_RELATIVE = V24_CHECKPOINT_RELATIVE
PACKAGE_RELATIVE = V24_PACKAGE_RELATIVE
MANIFEST_RELATIVE = V24_MANIFEST_RELATIVE
EXPECTED_PACKAGE_ID = V24_PACKAGE_ID
EXPECTED_PLAN_VERSION = V24_PLAN_VERSION
load_json = continuation.load_json

SCOPE45_APPLIED_TRIGGER_SENTINEL = (
    "SCOPE45_APPLIED_ACTIVATION_PREDICATE_PENDING"
)
SCOPE45_APPLIED_QUEUE_ROUTES = {
    "INTERNAL_READY",
    "INTERNAL_RUN_REQUIRED",
    "REAL_EVENT_PENDING",
}

V24_SUCCESSOR_CONTROLLED_FROZEN_ERRORS = {
    (
        "WS-GOAL-EPIC-02-FP-005-R001: "
        "implementation changed artifact 22 differs"
    ): {
        "path": "tests/test_walksafe_epic02_trace_v2_2_history.py",
        "sha256": (
            "72be672e43b587aa5933ae9fbe899bfd6d7dc3a0115678faa747d0463da3b92c"
        ),
    },
    (
        "WS-GOAL-EPIC-02-FP-005-R001: "
        "implementation changed artifact 23 differs"
    ): {
        "path": "docs/control/walksafe-project-resumption-runbook.md",
        "sha256": (
            "4202bb0da242b3c9a9b228ca90a35f4edc6f99bc92e12e792574e8f0b404613f"
        ),
    },
}
FP011_GOAL_ID = "WS-GOAL-EPIC-02-FP-011-R001"
FP011_WORK_ITEM_ID = "EPIC-02-FP011-LONG-LIVED-LOGIN"
FP011_GOAL_PATH = (
    "docs/control/goals/walksafe-completion-graph-v2-3/work-items/"
    "epic-02/epic-02-fp011-long-lived-login-r001.md"
)
FP011_GOAL_SHA256 = (
    "55dbce00b3092053a2766c078f1de83ef79e9193bfb372c7bcf30836c596e56f"
)
FP011_COMPLETION_ROLE = f"WORK_ITEM_COMPLETION::{FP011_GOAL_ID}"
FP011_COMPLETION_DOCUMENT_ID = (
    "WS-FP011-LONG-LIVED-LOGIN-WORK-ITEM-COMPLETION-20260725-001"
)
FP011_RESULT_DIRECTORY = (
    "docs/control/execution/goal-results/"
    "WS-GOAL-EPIC-02-FP-011-R001"
)
FP011_COMPLETION_PATH = f"{FP011_RESULT_DIRECTORY}/completion-receipt.json"
FP011_RESULT_PATH_BY_KIND = {
    "IMPLEMENTATION_RECORD": f"{FP011_RESULT_DIRECTORY}/implementation-record.json",
    "VERIFICATION_RESULT": f"{FP011_RESULT_DIRECTORY}/verification-result.json",
    "SUCCESSOR_TRACE": f"{FP011_RESULT_DIRECTORY}/successor-trace.json",
}
FP011_RESULT_DOCUMENT_ID_BY_KIND = {
    "IMPLEMENTATION_RECORD": (
        "WS-FP011-LONG-LIVED-LOGIN-IMPLEMENTATION-20260725-001"
    ),
    "VERIFICATION_RESULT": (
        "WS-FP011-LONG-LIVED-LOGIN-VERIFICATION-20260725-001"
    ),
    "SUCCESSOR_TRACE": (
        "WS-FP011-LONG-LIVED-LOGIN-SUCCESSOR-20260725-001"
    ),
}
FP011_REVIEW_PATH = f"{FP011_RESULT_DIRECTORY}/independent-review.json"
FP011_REVIEW_DOCUMENT_ID = (
    "WS-FP011-LONG-LIVED-LOGIN-INTERNAL-REVIEW-20260725-001"
)
FP011_START_GATE_PATH = (
    "docs/control/execution/goal-gates/"
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP011-20260725-001/"
    "implementation-start-gate-receipt.json"
)
FP011_START_GATE_DOCUMENT_ID = (
    "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-FP011-20260725-001"
)
FP011_START_GATE_SHA256 = (
    "ef8f0918f4a415fd3b4d2f346cb0a7828eaddf459bba32fe8da25b07762733ed"
)
FP011_COMPLETION_BOUNDARY = {
    "formal_test_status": "NOT_RUN",
    "planned_formal_test_total": 279,
    "planned_formal_test_total_status": "NOT_RUN",
    "actual_user_status": "NOT_RUN",
    "actual_device_status": "NOT_RUN",
    "actual_network_status": "NOT_RUN",
    "production_credentials_status": "NOT_RUN",
    "production_deployment_status": "NOT_RUN",
    "remote_device_revoke_drill_status": "NOT_RUN",
    "account_lock_drill_status": "NOT_RUN",
    "security_incident_drill_status": "NOT_RUN",
    "production_long_lived_login_enabled": False,
    "release_gate_status": "NOT_RUN",
    "release_gates_waived": False,
    "release_status": "NOT_ELIGIBLE",
}
FP011_PRODUCT_PATH_PREFIXES = (
    "apps/android/",
    "apps/android-gateway/",
)
FP011_PRODUCT_EXACT_PATHS = {
    "deploy/config/walksafe-android-gateway.env.example",
}
FP013_GOAL_ID = "WS-GOAL-EPIC-02-FP-013-R001"
FP013_WORK_ITEM_ID = "EPIC-02-FP013-FIRST-RUN-INTEGRATED-CONSENT"
FP013_GOAL_PATH = (
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/"
    "epic-02/epic-02-fp013-first-run-integrated-consent-r001.md"
)
FP013_GOAL_SHA256 = (
    "0c3ac09bb4541fa0a881995f49d701cc7dc0c520009b1b68f382e2a54030e217"
)
FP013_COMPLETION_ROLE = f"WORK_ITEM_COMPLETION::{FP013_GOAL_ID}"
FP013_COMPLETION_DOCUMENT_ID = (
    "WS-FP013-INTEGRATED-CONSENT-WORK-ITEM-COMPLETION-20260725-001"
)
FP013_RESULT_DIRECTORY = (
    "docs/control/execution/goal-results/"
    "WS-GOAL-EPIC-02-FP-013-R001"
)
FP013_COMPLETION_PATH = f"{FP013_RESULT_DIRECTORY}/completion-receipt.json"
FP013_COMPLETION_SHA256 = (
    "de0a6f4a431bebb8b09ea9989ff1f880a84b5d1d426d33befde135d0d09fce0c"
)
FP013_RESULT_PATH_BY_KIND = {
    "IMPLEMENTATION_RECORD": f"{FP013_RESULT_DIRECTORY}/implementation-record.json",
    "VERIFICATION_RESULT": f"{FP013_RESULT_DIRECTORY}/verification-result.json",
    "SUCCESSOR_TRACE": f"{FP013_RESULT_DIRECTORY}/successor-trace.json",
}
FP013_RESULT_DOCUMENT_ID_BY_KIND = {
    "IMPLEMENTATION_RECORD": (
        "WS-FP013-INTEGRATED-CONSENT-IMPLEMENTATION-20260725-001"
    ),
    "VERIFICATION_RESULT": (
        "WS-FP013-INTEGRATED-CONSENT-VERIFICATION-20260725-001"
    ),
    "SUCCESSOR_TRACE": (
        "WS-FP013-INTEGRATED-CONSENT-SUCCESSOR-20260725-001"
    ),
}
FP013_RESULT_SHA256_BY_KIND = {
    "IMPLEMENTATION_RECORD": (
        "94e8e4aff7350f673051d8ca38df3459d6bb1cde57656bb323556fdf1c54d72f"
    ),
    "VERIFICATION_RESULT": (
        "cbe4c2c0bfce2956e76c7e798337f7e2168f47bf6d39b59adbc206317d44fe6b"
    ),
    "SUCCESSOR_TRACE": (
        "4d8f305abb8e775aeca0de72b9454799869e64e7d19f777ac2e5b5d8a0296e47"
    ),
}
FP013_REVIEW_PATH = f"{FP013_RESULT_DIRECTORY}/independent-review.json"
FP013_REVIEW_DOCUMENT_ID = (
    "WS-FP013-INTEGRATED-CONSENT-INTERNAL-REVIEW-20260725-001"
)
FP013_REVIEW_SHA256 = (
    "29677b28b44b26dbeeef0d630df9a36e37ef41e0ebc566f25ba4eb664b05b0c2"
)
FP013_START_GATE_PATH = (
    "docs/control/execution/goal-gates/"
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP013-20260725-008/"
    "implementation-start-gate-receipt.json"
)
FP013_START_GATE_DOCUMENT_ID = (
    "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-FP013-20260725-008"
)
FP013_START_GATE_SHA256 = (
    "4be7948fae41f216d93dbce24bbad1fb6ca01f6f074b7a99af0b49035777c902"
)
FP013_START_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP013-20260725-008"
)
FP013_START_EVENT_SHA256 = (
    "da786e90dd386a711e9b21280fd2e8ce8b7aae17c0cf2180fdd6141aabd3bd9c"
)
FP013_CANONICAL_UPDATE_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-CANONICAL-BINDINGS-UPDATED-FP013-20260725-001"
)
FP013_CANONICAL_UPDATE_EVENT_SHA256 = (
    "8e1f8ea26861d9813128d8e78b77d07a4ea753d61a90dad9675e1a40fb207c08"
)
FP013_COMPLETION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-COMPLETED-FP013-20260725-001"
)
FP013_COMPLETION_EVENT_SHA256 = (
    "fcab536bbb408e44a580192a6e791edc7c8bf2ccfdb1aa8c6ef8a5d7a077eda2"
)
FP013_IMPLEMENTATION_CONTENT_SET_SHA256 = (
    "0f47f11ae202e6dbae13c5ce5079eafca19a6009ca00bb88e57e75d4ddd1d3af"
)
FP013_COMPLETION_BOUNDARY = {
    "formal_test_status": "NOT_RUN",
    "planned_formal_test_total": 279,
    "planned_formal_test_total_status": "NOT_RUN",
    "legal_privacy_review_status": "NOT_RUN",
    "approved_final_consent_copy_status": "NOT_RUN",
    "actual_user_status": "NOT_RUN",
    "actual_guardian_status": "NOT_RUN",
    "actual_talkback_user_status": "NOT_RUN",
    "actual_device_status": "NOT_RUN",
    "actual_network_status": "NOT_RUN",
    "production_credentials_status": "NOT_RUN",
    "production_deployment_status": "NOT_RUN",
    "release_gate_status": "NOT_RUN",
    "release_gates_waived": False,
    "release_status": "NOT_ELIGIBLE",
}
FP015_GOAL_ID = "WS-GOAL-EPIC-02-FP-015-R001"
FP015_WORK_ITEM_ID = "EPIC-02-FP015-WITHDRAWAL-ACCOUNT-DELETION"
FP015_GOAL_PATH = (
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/"
    "epic-02/epic-02-fp015-withdrawal-account-deletion-r001.md"
)
FP015_GOAL_SHA256 = (
    "9b5374de0e8a95140ec83b5f70bfe0c85cc0353c474c93ccdf4dbe9d27a9a0f3"
)
FP015_COMPLETION_ROLE = f"WORK_ITEM_COMPLETION::{FP015_GOAL_ID}"
FP015_COMPLETION_DOCUMENT_ID = (
    "WS-FP015-WITHDRAWAL-ACCOUNT-DELETION-WORK-ITEM-COMPLETION-"
    "20260725-001"
)
FP015_RESULT_DIRECTORY = (
    "docs/control/execution/goal-results/"
    "WS-GOAL-EPIC-02-FP-015-R001"
)
FP015_COMPLETION_PATH = f"{FP015_RESULT_DIRECTORY}/completion-receipt.json"
FP015_COMPLETION_SHA256 = (
    "aaef00a3c67dfd74202c01ed9d4eacb71e7efdd4d8bd688a795efca0f4d38e24"
)
FP015_RESULT_PATH_BY_KIND = {
    "IMPLEMENTATION_RECORD": (
        f"{FP015_RESULT_DIRECTORY}/implementation-record.json"
    ),
    "VERIFICATION_RESULT": (
        f"{FP015_RESULT_DIRECTORY}/verification-result.json"
    ),
    "SUCCESSOR_TRACE": f"{FP015_RESULT_DIRECTORY}/successor-trace.json",
}
FP015_RESULT_SHA256_BY_KIND = {
    "IMPLEMENTATION_RECORD": (
        "696b89c8e95c979e40174cc7e6423fabad54fb137ce6cf75fe4724004f12f8aa"
    ),
    "VERIFICATION_RESULT": (
        "c56005ea9ecf553caf83f4e7f0c31d6e5e269154aee4a9f5ff7a8d4183682b37"
    ),
    "SUCCESSOR_TRACE": (
        "ee985071f60efaa33d5695e6e193dbcbac2635bed0e3b0f06f88a78969affb6c"
    ),
}
FP015_IMPLEMENTATION_DOCUMENT_ID = (
    "WS-FP015-WITHDRAWAL-ACCOUNT-DELETION-IMPLEMENTATION-20260725-001"
)
FP015_REVIEW_PATH = f"{FP015_RESULT_DIRECTORY}/independent-review.json"
FP015_REVIEW_DOCUMENT_ID = (
    "WS-FP015-WITHDRAWAL-ACCOUNT-DELETION-INTERNAL-REVIEW-20260725-001"
)
FP015_REVIEW_SHA256 = (
    "468e0c944c8a02ee2c911a84fdfd8cc9674f8e23c02a3ab3c08d147d33d5b08c"
)
FP015_REVIEW_SUBJECT_PATH = f"{FP015_RESULT_DIRECTORY}/review-subject.json"
FP015_REVIEW_SUBJECT_SHA256 = (
    "1059ebd038bde93f0bb26b68870f8e5d88cc7dcc19ea96b2db43ba7913bd2fd0"
)
FP015_REVIEW_ATTESTATION_PATH = (
    f"{FP015_RESULT_DIRECTORY}/review-attestation.json"
)
FP015_REVIEW_ATTESTATION_SHA256 = (
    "932c6692ca2729f1fff420e108dd26ef77fe308ecc7785bf82a64d1e22f695a4"
)
FP015_REVIEWER_ID = (
    "CODEX-FP015-FINAL-STAGE2-SEPARATE-REVIEW-20260726-001"
)
FP015_REVIEWED_AT = "2026-07-26T02:09:31+09:00"
FP015_START_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP015-20260725-001"
)
FP015_START_EVENT_SHA256 = (
    "d4402d0dcecc7c622bc58af6cbe7c0bc3523f3baa712dadc7ef508a2d54330d3"
)
FP015_START_GATE_PATH = (
    "docs/control/execution/goal-gates/"
    f"{FP015_START_EVENT_ID}/implementation-start-gate-receipt.json"
)
FP015_START_GATE_DOCUMENT_ID = (
    "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-FP015-20260725-001"
)
FP015_START_GATE_SHA256 = (
    "6abcad07e31d2426624f6a5fab429cdc3aaf363cc291fec60e63cbe9de8c6dcf"
)
FP015_CANONICAL_UPDATE_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-CANONICAL-BINDINGS-UPDATED-FP015-20260726-001"
)
FP015_CANONICAL_UPDATE_EVENT_SHA256 = (
    "644913d74b056b947a6acaddf68a55124c561861df9b55d6193a15c0ca8eb200"
)
FP015_COMPLETION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-COMPLETED-FP015-20260726-001"
)
FP015_COMPLETION_EVENT_SHA256 = (
    "7e6b7aed781dca9f431c86de8bad61c57386d9206a95dc11db03b7c9a74a87eb"
)
FP015_IMPLEMENTATION_CONTENT_SET_SHA256 = (
    "b23fe77ae83b63092b3d27fee8762c6f52066b368c4714263dfd97e4df164054"
)
FP015_CANONICAL_BINDINGS = {
    "IMPLEMENTATION_BACKLOG": {
        "role": "IMPLEMENTATION_BACKLOG",
        "document_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260725-017",
        "path": (
            "docs/control/audits/"
            "walksafe-implementation-remediation-backlog-20260725-r017.json"
        ),
        "file_sha256": (
            "0ca4724ffad40891503c7df1578e4bbb274991276ef852d1dd3b74ca3011dc35"
        ),
    },
    "IMPLEMENTATION_GAP": {
        "role": "IMPLEMENTATION_GAP",
        "document_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260725-017",
        "path": (
            "docs/control/audits/"
            "walksafe-implementation-gap-analysis-20260725-r017.json"
        ),
        "file_sha256": (
            "eb155dbec7228882871dea7df76c8e24cfef0fe51be9ec95e7f91c5e5b1e7be0"
        ),
    },
    FP015_COMPLETION_ROLE: {
        "role": FP015_COMPLETION_ROLE,
        "document_id": FP015_COMPLETION_DOCUMENT_ID,
        "path": FP015_COMPLETION_PATH,
        "file_sha256": FP015_COMPLETION_SHA256,
    },
}
FP015_COMPLETION_BOUNDARY = {
    "formal_test_status": "NOT_RUN",
    "planned_formal_test_total": 279,
    "planned_formal_test_total_status": "NOT_RUN",
    "legal_privacy_review_status": "NOT_RUN",
    "legal_hold_determination_status": "NOT_RUN",
    "approved_final_privacy_copy_status": "NOT_RUN",
    "actual_user_status": "NOT_RUN",
    "actual_guardian_status": "NOT_RUN",
    "actual_talkback_user_status": "NOT_RUN",
    "actual_device_status": "NOT_RUN",
    "actual_network_status": "NOT_RUN",
    "actual_external_storage_deletion_status": "NOT_RUN",
    "actual_backup_deletion_status": "NOT_RUN",
    "external_rights_intake_provisioning_status": "NOT_RUN",
    "deletion_sla_certification_status": "NOT_RUN",
    "production_credentials_status": "NOT_RUN",
    "production_deployment_status": "NOT_RUN",
    "release_gate_status": "NOT_RUN",
    "release_gates_waived": False,
    "release_status": "NOT_ELIGIBLE",
}
FP014_GOAL_ID = "WS-GOAL-EPIC-02-FP-014-R001"
FP014_WORK_ITEM_ID = "EPIC-02-FP014-PERMISSION-DENIAL-REVOCATION"
FP014_GOAL_PATH = (
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/"
    "epic-02/epic-02-fp014-permission-denial-revocation-r001.md"
)
FP014_GOAL_SHA256 = (
    "8790f46e43d410c1fb4dc5c6c0b7013fa2dfc87b511a249068df4bdddbc2c06e"
)
FP014_COMPLETION_ROLE = f"WORK_ITEM_COMPLETION::{FP014_GOAL_ID}"
FP014_COMPLETION_DOCUMENT_ID = (
    "WS-FP014-PERMISSION-DENIAL-REVOCATION-WORK-ITEM-COMPLETION-"
    "20260726-001"
)
FP014_RESULT_DIRECTORY = (
    "docs/control/execution/goal-results/"
    "WS-GOAL-EPIC-02-FP-014-R001"
)
FP014_COMPLETION_PATH = f"{FP014_RESULT_DIRECTORY}/completion-receipt.json"
FP014_COMPLETION_SHA256 = (
    "9eff1f58ddafca0475ebd765bddbeb712c7748db32bcb88259ae8228a3361170"
)
FP014_RESULT_PATH_BY_KIND = {
    "IMPLEMENTATION_RECORD": (
        f"{FP014_RESULT_DIRECTORY}/implementation-record.json"
    ),
    "VERIFICATION_RESULT": (
        f"{FP014_RESULT_DIRECTORY}/verification-result.json"
    ),
    "SUCCESSOR_TRACE": f"{FP014_RESULT_DIRECTORY}/successor-trace.json",
}
FP014_RESULT_SHA256_BY_KIND = {
    "IMPLEMENTATION_RECORD": (
        "b200db586fd9c16bed10b6895bb8f502276cb2ebca4abea64fbdddd8d010fced"
    ),
    "VERIFICATION_RESULT": (
        "e70c039b52457d1cec6031e11047e8b223020ca4b46312f9a8cc05fdee0e7e52"
    ),
    "SUCCESSOR_TRACE": (
        "8ed523061652c1d819a6296bd50ed3b16e90503d66e47979512ac1d4f932a92c"
    ),
}
FP014_RESULT_DOCUMENT_ID_BY_KIND = {
    "IMPLEMENTATION_RECORD": (
        "WS-FP014-PERMISSION-DENIAL-REVOCATION-IMPLEMENTATION-20260726-001"
    ),
    "VERIFICATION_RESULT": (
        "WS-FP014-PERMISSION-DENIAL-REVOCATION-VERIFICATION-20260726-001"
    ),
    "SUCCESSOR_TRACE": (
        "WS-FP014-PERMISSION-DENIAL-REVOCATION-SUCCESSOR-20260726-001"
    ),
}
FP014_REVIEW_SUBJECT_PATH = f"{FP014_RESULT_DIRECTORY}/review-subject.json"
FP014_REVIEW_SUBJECT_SHA256 = (
    "3809992833e63d6a32a090a5816ed3f380411071a9293c9e162e972bba1db2ae"
)
FP014_REVIEW_ATTESTATION_PATH = (
    f"{FP014_RESULT_DIRECTORY}/review-attestation.json"
)
FP014_REVIEW_ATTESTATION_SHA256 = (
    "67fb8ad978cd1f9678d34a553cb054e79900ca4a1a4e0082cca799d479cc5fde"
)
FP014_REVIEW_PATH = f"{FP014_RESULT_DIRECTORY}/independent-review.json"
FP014_REVIEW_DOCUMENT_ID = (
    "WS-FP014-PERMISSION-DENIAL-REVOCATION-INTERNAL-REVIEW-20260726-001"
)
FP014_REVIEW_SHA256 = (
    "f4f3ea2d051a2ac6fdba0146212f13167b51a33ba2b1fe027b1290c686b0e2f4"
)
FP014_JSON_SHA256_BY_PATH = {
    FP014_COMPLETION_PATH: FP014_COMPLETION_SHA256,
    FP014_RESULT_PATH_BY_KIND["IMPLEMENTATION_RECORD"]: (
        FP014_RESULT_SHA256_BY_KIND["IMPLEMENTATION_RECORD"]
    ),
    FP014_RESULT_PATH_BY_KIND["VERIFICATION_RESULT"]: (
        FP014_RESULT_SHA256_BY_KIND["VERIFICATION_RESULT"]
    ),
    FP014_RESULT_PATH_BY_KIND["SUCCESSOR_TRACE"]: (
        FP014_RESULT_SHA256_BY_KIND["SUCCESSOR_TRACE"]
    ),
    FP014_REVIEW_SUBJECT_PATH: FP014_REVIEW_SUBJECT_SHA256,
    FP014_REVIEW_ATTESTATION_PATH: FP014_REVIEW_ATTESTATION_SHA256,
    FP014_REVIEW_PATH: FP014_REVIEW_SHA256,
}
FP014_REVIEWER_ID = "WS-FP014-INDEPENDENT-REVIEWER-001"
FP014_REVIEWER_TASK = "/root/fp014_final_evidence_review"
FP014_REVIEWED_AT = "2026-07-26T05:11:01+09:00"
FP014_EXECUTOR = {
    "id": (
        "CODEX-FP014-PERMISSION-DENIAL-REVOCATION-IMPLEMENTER-"
        "20260726-001"
    ),
    "task": "FP014_PERMISSION_DENIAL_REVOCATION_IMPLEMENTATION",
    "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
    "authority": "GRAPH_V2_4_STANDING_EXECUTION_AUTHORITY",
}
FP014_START_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP014-20260726-001"
)
FP014_START_EVENT_SHA256 = (
    "2158aa372a00af3024f2a80eeb6e98471fd07b97ec570c07edd5b5ce07c3551e"
)
FP014_START_GATE_PATH = (
    "docs/control/execution/goal-gates/"
    f"{FP014_START_EVENT_ID}/implementation-start-gate-receipt.json"
)
FP014_START_GATE_DOCUMENT_ID = (
    "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-FP014-20260726-001"
)
FP014_START_GATE_SHA256 = (
    "5399d1c272d4c2a2d0d7fe4cd75c3e31de82a32362dd12ccc4dee454b4426720"
)
FP014_REPOSITORY_STATE_PATH = (
    FP014_START_GATE_PATH.rsplit("/", 1)[0] + "/19-REPOSITORY_STATE.log"
)
FP014_REPOSITORY_STATE_SHA256 = (
    "3301e0793102e002aaa43067f133b86af5ccb1f0d5b9a97b9eedb220da8205a2"
)
FP014_START_HEAD_COMMIT = "a3ad7eead6b5d834d3e0675422475a9aad351e3d"
FP014_CANONICAL_UPDATE_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-CANONICAL-BINDINGS-UPDATED-FP014-20260726-001"
)
FP014_COMPLETION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-COMPLETED-FP014-20260726-001"
)
FP016_GOAL_ID = "WS-GOAL-EPIC-02-FP-016-R001"
FP016_MATERIALIZED_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-MATERIALIZED-FP016-20260726-001"
)
FP016_READY_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-READY-FP016-20260726-001"
)
FP016_GOAL_RELATIVE = (
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/"
    "epic-02/epic-02-fp016-camera-centered-buttonless-screen-r001.md"
)
FP016_GOAL_SHA256 = (
    "d7fe0204199c22acdc3bc6b748cde67b2b3b6227b726ce6a50ddf2c3f019af81"
)
FP016_WORK_ITEM_ID = "EPIC-02-FP016-CAMERA-CENTERED-BUTTONLESS-SCREEN"
FP016_COMPLETION_ROLE = f"WORK_ITEM_COMPLETION::{FP016_GOAL_ID}"
FP016_RESULT_DIRECTORY = (
    "docs/control/execution/goal-results/"
    "WS-GOAL-EPIC-02-FP-016-R001"
)
FP016_COMPLETION_PATH = f"{FP016_RESULT_DIRECTORY}/completion-receipt.json"
FP016_COMPLETION_DOCUMENT_ID = (
    "WS-FP016-CAMERA-CENTERED-BUTTONLESS-SCREEN-"
    "WORK-ITEM-COMPLETION-20260726-001"
)
FP016_COMPLETION_SHA256 = (
    "389a586e23152a84efd6ec8e0b7d31b5379de6ce8cd886b62b8bed587cff5ecf"
)
FP016_RESULT_PATH_BY_KIND = {
    "IMPLEMENTATION_RECORD": (
        f"{FP016_RESULT_DIRECTORY}/implementation-record.json"
    ),
    "VERIFICATION_RESULT": (
        f"{FP016_RESULT_DIRECTORY}/verification-result.json"
    ),
    "SUCCESSOR_TRACE": f"{FP016_RESULT_DIRECTORY}/successor-trace.json",
}
FP016_RESULT_SHA256_BY_KIND = {
    "IMPLEMENTATION_RECORD": (
        "986c65cb7dfcbdb0e74c45c163a3b6428eae2d8dc201d1ca3a978f8348bf768e"
    ),
    "VERIFICATION_RESULT": (
        "2b803c3246e3a92a98d5a0f7ab879fe1ad0e72717c372d7b0ca658382b7c1166"
    ),
    "SUCCESSOR_TRACE": (
        "91e835f621de2ac32089a90bcdbdd9457ec84e8f5498230cbdddb3de3ae1fa28"
    ),
}
FP016_START_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP016-20260726-001"
)
FP016_START_EVENT_SHA256 = (
    "4939c7612a7b1b452df2273977305f057ff856e7f95633f5b2922d7052bc71f6"
)
FP016_START_GATE_PATH = (
    "docs/control/execution/goal-gates/"
    f"{FP016_START_EVENT_ID}/implementation-start-gate-receipt.json"
)
FP016_START_GATE_DOCUMENT_ID = (
    "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-FP016-20260726-001"
)
FP016_START_GATE_SHA256 = (
    "0f1d2be216c0aeb758c0b12a658d9a0ef0bce35902ea14fab8a9a9dd089bb736"
)
FP016_CANONICAL_UPDATE_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-CANONICAL-BINDINGS-UPDATED-FP016-20260726-001"
)
FP016_CANONICAL_UPDATE_EVENT_SHA256 = (
    "0fd47ccdd7ba7ebb30b726556120e11c57fc039481875e91d21e050fd211cb80"
)
FP016_COMPLETION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-COMPLETED-FP016-20260726-001"
)
FP016_COMPLETION_EVENT_SHA256 = (
    "bd9f82f1b77057d2f1728d6755e3e7d9d429910ee436197a5898dd1e41b95c45"
)
FP012_GOAL_ID = "WS-GOAL-EPIC-02-FP-012-R001"
FP012_GOAL_RELATIVE = (
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/"
    "epic-02/epic-02-fp012-multi-device-session-ledger-r001.md"
)
FP012_GOAL_SHA256 = (
    "4f85f016683176ca38de6024cd7fea4c09b9cf65e8c3318d27b785120d634c3f"
)
FP012_MATERIALIZED_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-MATERIALIZED-FP012-20260726-001"
)
FP012_MATERIALIZED_EVENT_SHA256 = (
    "695d3a50d26c9a9c68e42e7282c61800c6dede42c094f2dca8690462b1be8a47"
)
FP012_READY_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-READY-FP012-20260726-001"
)
FP012_READY_EVENT_SHA256 = (
    "d48829b90d1589b80f665d59c22fa4f2d86bee7a8f95fdbe3addf1bf2989adfa"
)
FP012_WORK_ITEM_ID = "EPIC-02-FP012-MULTI-DEVICE-SESSION-LEDGER"
FP012_COMPLETION_ROLE = f"WORK_ITEM_COMPLETION::{FP012_GOAL_ID}"
FP012_RESULT_DIRECTORY = (
    "docs/control/execution/goal-results/"
    "WS-GOAL-EPIC-02-FP-012-R001"
)
FP012_COMPLETION_PATH = f"{FP012_RESULT_DIRECTORY}/completion-receipt.json"
FP012_COMPLETION_DOCUMENT_ID = (
    "WS-FP012-MULTI-DEVICE-SESSION-LEDGER-"
    "WORK-ITEM-COMPLETION-20260726-001"
)
FP012_COMPLETION_SHA256 = (
    "a8cde08850f9bb92fcee01e562922d1f9dfcaab483d6b2b20022adffee532af2"
)
FP012_CANONICAL_UPDATE_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-CANONICAL-BINDINGS-UPDATED-FP012-20260726-001"
)
FP012_COMPLETION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-COMPLETED-FP012-20260726-001"
)
FP047_GOAL_ID = "WS-GOAL-EPIC-03-FP-047-R001"
FP047_PARENT_GOAL_ID = "WS-GOAL-EPIC-03"
FP047_MATERIALIZED_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-MATERIALIZED-FP047-20260726-001"
)
FP047_READY_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-READY-FP047-20260726-001"
)
FP047_GOAL_SHA256 = (
    "2ff79dde64cb113d755855f05dafb6bab1393717f060b4db387bb2d48bc53b06"
)
FP047_PARENT_GOAL_RELATIVE = (
    "docs/control/goals/walksafe-completion-graph-v2-2/workstreams/"
    "epic-03-account-admin-security.md"
)
FP047_WORK_ITEM_ID = (
    "EPIC-03-FP047-USER-ADMIN-LOGIN-AUTHORIZATION-SEPARATION"
)
FP047_RESUME_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-WORK-SESSION-RESUMED-FP047-20260726-002"
)
FP047_CANONICAL_UPDATE_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-CANONICAL-BINDINGS-UPDATED-FP047-20260726-001"
)
FP047_COMPLETION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-COMPLETED-FP047-20260726-001"
)
FP047_COMPLETION_ROLE = f"WORK_ITEM_COMPLETION::{FP047_GOAL_ID}"
FP047_RESULT_DIRECTORY = (
    f"docs/control/execution/goal-results/{FP047_GOAL_ID}"
)
FP047_COMPLETION_PATH = (
    f"{FP047_RESULT_DIRECTORY}/completion-receipt.json"
)
FP047_COMPLETION_DOCUMENT_ID = (
    "WS-FP047-USER-ADMIN-LOGIN-AUTHORIZATION-SEPARATION-"
    "WORK-ITEM-COMPLETION-20260726-001"
)
FP047_RESULT_EVIDENCE_CONTRACT = (
    (
        "IMPLEMENTATION_RECORD",
        f"{FP047_RESULT_DIRECTORY}/implementation-record.json",
        (
            "WS-FP047-USER-ADMIN-LOGIN-AUTHORIZATION-SEPARATION-"
            "IMPLEMENTATION-20260726-001"
        ),
    ),
    (
        "VERIFICATION_RESULT",
        f"{FP047_RESULT_DIRECTORY}/verification-result.json",
        (
            "WS-FP047-USER-ADMIN-LOGIN-AUTHORIZATION-SEPARATION-"
            "VERIFICATION-20260726-001"
        ),
    ),
    (
        "SUCCESSOR_TRACE",
        f"{FP047_RESULT_DIRECTORY}/successor-trace.json",
        (
            "WS-FP047-USER-ADMIN-LOGIN-AUTHORIZATION-SEPARATION-"
            "SUCCESSOR-20260726-001"
        ),
    ),
)
FP047_REVIEW_SUBJECT_PATH = f"{FP047_RESULT_DIRECTORY}/review-subject.json"
FP047_REVIEW_PATH = f"{FP047_RESULT_DIRECTORY}/independent-review.json"
FP047_REVIEW_ATTESTATION_PATH = (
    f"{FP047_RESULT_DIRECTORY}/review-attestation.json"
)
FP047_REVIEW_DOCUMENT_ID = (
    "WS-FP047-USER-ADMIN-LOGIN-AUTHORIZATION-SEPARATION-"
    "INTERNAL-REVIEW-20260726-001"
)
FP047_REVIEWER_ID = "WS-FP047-INDEPENDENT-REVIEWER-001"
FP047_REVIEWER_TASK = "/root/fp047_final_evidence_review"
FP047_OUTPUT_MANIFEST_PATHS = (
    *(path for _, path, _ in FP047_RESULT_EVIDENCE_CONTRACT),
    FP047_REVIEW_SUBJECT_PATH,
    FP047_REVIEW_PATH,
    (
        "docs/control/audits/"
        "walksafe-implementation-gap-analysis-20260726-r021.json"
    ),
    (
        "docs/control/audits/"
        "walksafe-implementation-gap-analysis-20260726-r021.md"
    ),
    (
        "docs/control/audits/"
        "walksafe-implementation-remediation-backlog-20260726-r021.json"
    ),
    (
        "docs/control/audits/"
        "walksafe-implementation-remediation-backlog-20260726-r021.md"
    ),
    (
        "docs/control/execution/"
        "walksafe-epic-03-fp047-user-admin-login-authorization-separation-"
        "active-ledger-overlay-20260726-r001.json"
    ),
    (
        "docs/control/execution/"
        "walksafe-epic-03-fp047-user-admin-login-authorization-separation-"
        "active-ledger-overlay-20260726-r001.md"
    ),
)
FP047_RECEIPT_FIELDS = {
    "schema_version",
    "document_id",
    "evidence_type",
    "status",
    "result",
    "target_goal_id",
    "target_goal_content_sha256",
    "work_item_id",
    "source_policy_ids",
    "gap_ids",
    "target_completion_level",
    "execution_start_event_sha256",
    "execution_session_event",
    "implementation_start_gate_binding",
    "execution_window",
    "completed_at",
    "executor",
    "reviewer",
    "reviewer_provenance",
    "result_evidence",
    "output_evidence_manifest",
    "output_evidence_manifest_sha256",
    "completion_boundary",
    "generated_at",
}
FP047_COMPLETION_BOUNDARY = {
    "formal_test_status": "NOT_RUN",
    "external_postgis_integration_status": "NOT_RUN",
    "external_reports_integration_status": "NOT_RUN",
    "postgresql_concurrency_status": "NOT_RUN",
    "actual_user_status": "NOT_RUN",
    "actual_admin_status": "NOT_RUN",
    "actual_device_status": "NOT_RUN",
    "single_admin_recovery_drill_status": "NOT_RUN",
    "external_security_review_status": "NOT_RUN",
    "external_legal_review_status": "NOT_RUN",
    "external_privacy_review_status": "NOT_RUN",
    "external_accessibility_review_status": "NOT_RUN",
    "production_deployment_status": "NOT_RUN",
    "release_gate_status": "NOT_RUN",
    "release_gates_waived": False,
    "external_independence_claimed": False,
    "release_status": "NOT_ELIGIBLE",
}
FP047_REVIEW_SUBJECT_BOUNDARY = {
    "formal_test_ids": [f"TC-FP-047-{index:02d}" for index in range(1, 8)],
    **FP047_COMPLETION_BOUNDARY,
    "release_gate_count": 5,
}
FP047_REVIEW_BOUNDARY = {
    "separate_internal_review_pass": True,
    "external_independence_claimed": False,
    "formal_tests_remain_not_run": True,
    "external_postgis_integration_remains_not_run": True,
    "external_reports_integration_remains_not_run": True,
    "postgresql_concurrency_verification_remains_not_run": True,
    "actual_user_tests_remain_not_run": True,
    "actual_admin_tests_remain_not_run": True,
    "actual_device_tests_remain_not_run": True,
    "single_admin_recovery_drill_remains_not_run": True,
    "external_security_review_remains_not_run": True,
    "external_legal_review_remains_not_run": True,
    "external_privacy_review_remains_not_run": True,
    "external_accessibility_review_remains_not_run": True,
    "production_deployment_remains_not_run": True,
    "release_remains_not_eligible": True,
}
FP047_R021_BINDING_REQUIREMENTS = {
    "IMPLEMENTATION_BACKLOG": {
        "document_id": (
            "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260726-021"
        ),
        "path": (
            "docs/control/audits/"
            "walksafe-implementation-remediation-backlog-20260726-r021.json"
        ),
        "identity_json_path": "metadata.backlog_id",
    },
    "IMPLEMENTATION_GAP": {
        "document_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260726-021",
        "path": (
            "docs/control/audits/"
            "walksafe-implementation-gap-analysis-20260726-r021.json"
        ),
        "identity_json_path": "metadata.report_id",
    },
}
FP047_GATE_REMEDIATION_RUNNER_PATH = (
    "scripts/run_walksafe_test_layers_20260711.sh"
)
FP047_GATE_REMEDIATION_PRE_SHA256 = (
    "be5c434f35ce5fc9b673f67ef05309280a06a9fcb272dcd2f6aef5db10043ca3"
)
FP047_GATE_REMEDIATION_POST_SHA256 = (
    "c6eae1d06d88d53957dcc2f2598270a7620fd7647e50e1089153a3496c71a71b"
)
FP047_ACTIVE_SCOPE_PATHS = (
    "apps/android-gateway/src/exclusive-file-lock.ts",
    "apps/android-gateway/src/auth.ts",
    "apps/android-gateway/src/backend.ts",
    "apps/android-gateway/src/routes.ts",
    "apps/android-gateway/src/field-long-session.ts",
    "apps/android-gateway/src/field-walk-ledger.ts",
    "apps/android-gateway/test/field-long-session.test.ts",
    "apps/android-gateway/test/field-walk-ledger.test.ts",
    "apps/android-gateway/test/exclusive-file-lock.test.ts",
    "apps/android-gateway/test/gateway-contract.test.ts",
    "backend/app/field_test_security.py",
    "backend/app/services/admin_security.py",
    "backend/app/api/admin_security.py",
    "backend/app/api/reports.py",
    "backend/app/models.py",
    "backend/app/api/health.py",
    (
        "backend/alembic/versions/"
        "202607260001_admin_security_action_bound_audit.py"
    ),
    "backend/tests/test_admin_security.py",
    "contracts/walksafe.openapi.json",
    (
        "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/"
        "walksafe/admin/security/AdminSecurityApi.java"
    ),
    (
        "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/"
        "walksafe/admin/security/AdminSecurityHttpClient.java"
    ),
    (
        "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/"
        "walksafe/admin/security/AdminSecurityController.java"
    ),
    (
        "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/"
        "walksafe/admin/security/AdminHighRiskActionGate.java"
    ),
    (
        "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/"
        "walksafe/admin/security/AdminSecurityHttpClientTest.java"
    ),
    (
        "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/"
        "walksafe/admin/security/AdminSecurityControllerTest.java"
    ),
    (
        "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/"
        "walksafe/admin/security/AdminHighRiskActionGateTest.java"
    ),
    (
        "scripts/"
        "build_walksafe_fp047_user_admin_login_authorization_separation_"
        "trace_20260726.py"
    ),
    (
        "tests/"
        "test_walksafe_fp047_user_admin_login_authorization_separation_"
        "trace_20260726.py"
    ),
    FP047_GATE_REMEDIATION_RUNNER_PATH,
    "scripts/check_walksafe_goal_graph_v2_4.py",
    "tests/test_walksafe_goal_graph_v2_4.py",
)
FP047_FAILED_GATE_DIRECTORY_BY_ATTEMPT = {
    "001": (
        "docs/control/execution/goal-gates/"
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP047-20260726-001"
    ),
    "002": (
        "docs/control/execution/goal-gates/"
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP047-20260726-002"
    ),
}
FP047_FAILED_GATE_FILE_NAMES_BY_ATTEMPT = {
    "001": tuple(
        f"{number:02d}-{name}.log"
        for number, name in (
            (1, "CONTINUATION"),
            (2, "GOAL_GRAPH"),
            (3, "BASELINE_MATERIALIZATION"),
            (4, "ANDROID_GATEWAY_BOUNDARY"),
            (5, "NODE_TOOLCHAIN_PRE"),
            (6, "GATEWAY_TYPECHECK"),
            (7, "GATEWAY_TEST"),
            (8, "GATEWAY_BUILD"),
            (9, "WEB_TEST"),
            (10, "WEB_LINT"),
            (11, "WEB_TYPECHECK"),
            (12, "WEB_BUILD"),
            (13, "NODE_TOOLCHAIN_POST"),
            (14, "ANDROID_UNIT_ASSEMBLE_LINT"),
            (15, "TEST_LAYER_REGISTRY_VALIDATE"),
        )
    ),
    "002": (
        "01-CONTINUATION.log",
        "02-GOAL_GRAPH.log",
    ),
}
FP047_FAILED_GATE_LOG_SHA256_BY_PATH = {
    (
        FP047_FAILED_GATE_DIRECTORY_BY_ATTEMPT["001"]
        + "/15-TEST_LAYER_REGISTRY_VALIDATE.log"
    ): "9c32e06566cae9708abb1c1136d0d8740725063e594b20ff4640105ed867acab",
    (
        FP047_FAILED_GATE_DIRECTORY_BY_ATTEMPT["002"]
        + "/01-CONTINUATION.log"
    ): "25b5c8cf65854e11a04a9d0810dd2503585037683809c153f578c08a076ac5a7",
    (
        FP047_FAILED_GATE_DIRECTORY_BY_ATTEMPT["002"]
        + "/02-GOAL_GRAPH.log"
    ): "13ef3db1576e00a3dd2a4d050e8f35aced5ac66dba440410a5f6df58b783ac26",
}
FP047_START_EVENT_ID_RE = re.compile(
    r"^WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP047-[0-9]{8}-[0-9]{3}$"
)
FP012_CANONICAL_BINDINGS = {
    "IMPLEMENTATION_BACKLOG": {
        "role": "IMPLEMENTATION_BACKLOG",
        "document_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260726-020",
        "path": (
            "docs/control/audits/"
            "walksafe-implementation-remediation-backlog-20260726-r020.json"
        ),
        "file_sha256": (
            "20b103c83f397894a7b9d5c9eedd696b0aa977b02552f49614876714b540dd6f"
        ),
    },
    "IMPLEMENTATION_GAP": {
        "role": "IMPLEMENTATION_GAP",
        "document_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260726-020",
        "path": (
            "docs/control/audits/"
            "walksafe-implementation-gap-analysis-20260726-r020.json"
        ),
        "file_sha256": (
            "6c6bd3e1db80cfcca05361afedb6f2eb4de0cc4a67bf76b9a6830c71cf2a70b7"
        ),
    },
    FP012_COMPLETION_ROLE: {
        "role": FP012_COMPLETION_ROLE,
        "document_id": FP012_COMPLETION_DOCUMENT_ID,
        "path": FP012_COMPLETION_PATH,
        "file_sha256": FP012_COMPLETION_SHA256,
    },
}
FP016_IMPLEMENTATION_CONTENT_SET_SHA256 = (
    "ff9f07c1b811d11bda64a0b52fbc58844c917197f6b0e8d82b0c11847f662fd6"
)
FP016_IMPLEMENTATION_PATHS = (
    "apps/android/app/build.gradle.kts",
    "apps/android/app/gradle.lockfile",
    "apps/android/gradle/verification-metadata.xml",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    "apps/android/app/src/main/res/values/strings.xml",
    (
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
        "MainActivityFp016StaticTest.kt"
    ),
    (
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
        "CameraXFallbackCompositionStaticTest.kt"
    ),
    (
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
        "MainActivityAccessibilityStaticTest.kt"
    ),
    (
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
        "MainActivityFirstRunRegistrationStaticTest.kt"
    ),
    (
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
        "RuntimeMetricMainActivityStaticTest.kt"
    ),
    (
        "scripts/build_walksafe_fp016_camera_centered_buttonless_"
        "screen_trace_20260726.py"
    ),
    (
        "tests/test_walksafe_fp016_camera_centered_buttonless_"
        "screen_trace_20260726.py"
    ),
    "scripts/run_walksafe_test_layers_20260711.sh",
)
FP014_IMPLEMENTATION_CONTENT_SET_SHA256 = (
    "c76a1f9f57fadde5e0304d91b94f918102e01656733e5bc2b5c0c1bd5ae44fb9"
)
FP014_IMPLEMENTATION_PATHS = (
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
    "scripts/build_walksafe_fp014_permission_denial_revocation_trace_20260726.py",
    "tests/test_walksafe_fp014_permission_denial_revocation_trace_20260726.py",
    "scripts/run_walksafe_test_layers_20260711.sh",
)
FP014_LOG_SHA256_BY_PATH = {
    f"{FP014_RESULT_DIRECTORY}/logs/focused-android-tests.log": (
        "909a925b5f6df5f1460a9f913a2fbbc8c8db5c0f14e18ca591b173d533470c5c"
    ),
    f"{FP014_RESULT_DIRECTORY}/logs/full-android-verification.log": (
        "ae1349328a4c427048e1344592197b44126007251ee6e0cc97a79ce993f6771b"
    ),
    f"{FP014_RESULT_DIRECTORY}/logs/android-gateway-verification.log": (
        "a43110b36f2ec2c83bed4bbea43d4494539a2bf8b45da8f46b62637a25a3aed8"
    ),
    f"{FP014_RESULT_DIRECTORY}/logs/android-gateway-boundary.log": (
        "15b0b3f564febeb394f23a015fd552c3c1f9f7ac7df11e70f14ea6e13a816a8b"
    ),
    f"{FP014_RESULT_DIRECTORY}/logs/control-plane-verification.log": (
        "9e4ca4e54f7bf1e10393e21a2541da94150e9a1e4197d84b2d0dd8c27152807c"
    ),
}
FP014_OUTPUT_EVIDENCE_PATHS = (
    FP014_RESULT_PATH_BY_KIND["IMPLEMENTATION_RECORD"],
    FP014_RESULT_PATH_BY_KIND["VERIFICATION_RESULT"],
    FP014_RESULT_PATH_BY_KIND["SUCCESSOR_TRACE"],
    FP014_REVIEW_SUBJECT_PATH,
    FP014_REVIEW_PATH,
    "docs/control/audits/walksafe-implementation-gap-analysis-20260726-r018.json",
    "docs/control/audits/walksafe-implementation-gap-analysis-20260726-r018.md",
    (
        "docs/control/audits/"
        "walksafe-implementation-remediation-backlog-20260726-r018.json"
    ),
    (
        "docs/control/audits/"
        "walksafe-implementation-remediation-backlog-20260726-r018.md"
    ),
    (
        "docs/control/execution/"
        "walksafe-epic-02-fp014-permission-denial-revocation-"
        "active-ledger-overlay-20260726-r001.json"
    ),
    (
        "docs/control/execution/"
        "walksafe-epic-02-fp014-permission-denial-revocation-"
        "active-ledger-overlay-20260726-r001.md"
    ),
)
FP014_CANONICAL_BINDINGS = {
    "IMPLEMENTATION_BACKLOG": {
        "role": "IMPLEMENTATION_BACKLOG",
        "document_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260726-018",
        "path": (
            "docs/control/audits/"
            "walksafe-implementation-remediation-backlog-20260726-r018.json"
        ),
        "file_sha256": (
            "667ae83090e73d39e8ea371f2543e560a5d8465505dad323c84e901f369e31b0"
        ),
    },
    "IMPLEMENTATION_GAP": {
        "role": "IMPLEMENTATION_GAP",
        "document_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260726-018",
        "path": (
            "docs/control/audits/"
            "walksafe-implementation-gap-analysis-20260726-r018.json"
        ),
        "file_sha256": (
            "833ba9a691ec5b7de66c8e16e2bd988f63781fc9f38869e63270b24da9d8bbcd"
        ),
    },
    FP014_COMPLETION_ROLE: {
        "role": FP014_COMPLETION_ROLE,
        "document_id": FP014_COMPLETION_DOCUMENT_ID,
        "path": FP014_COMPLETION_PATH,
        "file_sha256": FP014_COMPLETION_SHA256,
    },
}
FP016_CANONICAL_BINDINGS = {
    "IMPLEMENTATION_BACKLOG": {
        "role": "IMPLEMENTATION_BACKLOG",
        "document_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260726-019",
        "path": (
            "docs/control/audits/"
            "walksafe-implementation-remediation-backlog-20260726-r019.json"
        ),
        "file_sha256": (
            "0c95156951ffcf7004fc92fbd6ca2cffe04febcea86ac457195fb10551ee6e62"
        ),
    },
    "IMPLEMENTATION_GAP": {
        "role": "IMPLEMENTATION_GAP",
        "document_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260726-019",
        "path": (
            "docs/control/audits/"
            "walksafe-implementation-gap-analysis-20260726-r019.json"
        ),
        "file_sha256": (
            "99b3b53eaa7b86fbead93db74a86ac25199ff3c0c6cf443d7d19af0d8fe7a9f1"
        ),
    },
    FP016_COMPLETION_ROLE: {
        "role": FP016_COMPLETION_ROLE,
        "document_id": FP016_COMPLETION_DOCUMENT_ID,
        "path": FP016_COMPLETION_PATH,
        "file_sha256": FP016_COMPLETION_SHA256,
    },
}
FP014_COMPLETION_BOUNDARY = {
    "formal_test_status": "NOT_RUN",
    "planned_formal_test_total": 279,
    "planned_formal_test_total_status": "NOT_RUN",
    "actual_user_status": "NOT_RUN",
    "actual_talkback_user_status": "NOT_RUN",
    "actual_device_status": "NOT_RUN",
    "android_platform_review_status": "NOT_RUN",
    "operational_permission_profile_approval_status": "NOT_RUN",
    "production_deployment_status": "NOT_RUN",
    "release_gate_status": "NOT_RUN",
    "release_gates_waived": False,
    "release_status": "NOT_ELIGIBLE",
}
FP014_DETAILED_BOUNDARY = {
    "formal_test_ids": [
        "TC-FP-014-01",
        "TC-FP-014-02",
        "TC-FP-014-03",
    ],
    **FP014_COMPLETION_BOUNDARY,
    "release_gate_count": 5,
}
FP014_REVIEW_BOUNDARY = {
    "separate_internal_review_pass": True,
    "external_independence_claimed": False,
    "formal_tests_remain_not_run": True,
    "actual_user_tests_remain_not_run": True,
    "actual_talkback_user_tests_remain_not_run": True,
    "actual_device_tests_remain_not_run": True,
    "android_platform_review_remains_not_run": True,
    "operational_permission_profile_approval_remains_not_run": True,
    "production_deployment_remains_not_run": True,
    "release_remains_not_eligible": True,
}
FROZEN_CHANGED_ARTIFACT_ERROR_RE = re.compile(
    r"^(?:archived v2\.2: )?(?P<goal_id>[A-Za-z0-9._-]+): "
    r"implementation changed artifact (?P<index>[0-9]+) differs$"
)
FROZEN_REQUIREMENTS_TRACEABILITY_ERROR_RE = re.compile(
    r"^(?:archived v2\.2: )?Goal graph event [0-9]+: "
    r"canonical binding SHA-256 differs: REQUIREMENTS_TRACEABILITY$"
)


def _require_equal(
    errors: list[str],
    label: str,
    actual: Any,
    expected: Any,
) -> None:
    if actual != expected:
        errors.append(f"{label} differs")


def _exact_repo_file(root: Path, relative: Any) -> Path | None:
    if not isinstance(relative, str):
        return None
    relative_path = Path(relative)
    if (
        relative_path.is_absolute()
        or not relative_path.parts
        or ".." in relative_path.parts
    ):
        return None
    candidate = root / relative_path
    try:
        resolved_root = root.resolve(strict=True)
        resolved = candidate.resolve(strict=True)
        resolved.relative_to(resolved_root)
    except (OSError, RuntimeError, ValueError):
        return None
    if candidate.is_symlink() or not candidate.is_file():
        return None
    return candidate


def _load_exact_json(root: Path, relative: Any) -> dict[str, Any] | None:
    path = _exact_repo_file(root, relative)
    if path is None:
        return None
    try:
        value = continuation.load_json(path)
    except (OSError, ValueError, json.JSONDecodeError):
        return None
    return value if isinstance(value, dict) else None


def _binding_by_role(
    checkpoint: dict[str, Any],
    role: str,
) -> dict[str, Any] | None:
    bindings = checkpoint.get("canonical_bindings")
    if not isinstance(bindings, list):
        return None
    matches = [
        binding
        for binding in bindings
        if isinstance(binding, dict) and binding.get("role") == role
    ]
    return matches[0] if len(matches) == 1 else None


def _sha256_binding_matches(
    root: Path,
    binding: Any,
    *,
    expected_path: str | None = None,
) -> bool:
    if not isinstance(binding, dict):
        return False
    relative = binding.get("path")
    path = _exact_repo_file(root, relative)
    digest = binding.get("sha256", binding.get("file_sha256"))
    return bool(
        path is not None
        and (expected_path is None or relative == expected_path)
        and isinstance(digest, str)
        and continuation.SHA256_RE.fullmatch(digest)
        and continuation.sha256_file(path) == digest
    )


def _implementation_content_set_sha256(
    changed_artifacts: list[dict[str, Any]],
) -> str:
    return continuation.canonical_json_sha256(
        [
            {
                "path": row.get("path"),
                "sha256": row.get("after_sha256"),
            }
            for row in changed_artifacts
        ]
    )


def _fp048_android_report_successor_is_declared(
    checkpoint: dict[str, Any],
) -> bool:
    state = checkpoint.get("goal_execution")
    statuses = (
        state.get("status_by_goal")
        if isinstance(state, dict)
        else None
    )
    history = (
        state.get("transition_history")
        if isinstance(state, dict)
        else None
    )
    return bool(
        (
            isinstance(statuses, dict)
            and statuses.get(FP048_ANDROID_REPORT_GOAL_ID)
            == "COMPLETE_AT_TARGET"
        )
        or _binding_by_role(
            checkpoint,
            FP048_ANDROID_REPORT_COMPLETION_ROLE,
        )
        is not None
        or (
            isinstance(history, list)
            and any(
                isinstance(event, dict)
                and event.get("subject_goal_id")
                == FP048_ANDROID_REPORT_GOAL_ID
                and event.get("sequence", 0) >= 43
                for event in history
            )
        )
    )


def _fp048_android_report_successor_bindings(
    root: Path,
    checkpoint: dict[str, Any],
    packet_bindings: dict[str, str],
    *,
    require_live_after: bool = True,
) -> dict[str, str] | None:
    """Prove the exact five packet hashes superseded by sealed FP048."""
    expected = FP048_ANDROID_REPORT_SUCCESSOR_BY_PATH
    if (
        not isinstance(checkpoint, dict)
        or not isinstance(packet_bindings, dict)
        or len(expected) != 5
        or any(
            packet_bindings.get(relative) != transition["before_sha256"]
            for relative, transition in expected.items()
        )
    ):
        return None

    state = checkpoint.get("goal_execution")
    statuses = (
        state.get("status_by_goal")
        if isinstance(state, dict)
        else None
    )
    inventory = (
        state.get("dynamic_goal_inventory")
        if isinstance(state, dict)
        else None
    )
    completion_roles = (
        state.get("completion_evidence_by_goal")
        if isinstance(state, dict)
        else None
    )
    history = (
        state.get("transition_history")
        if isinstance(state, dict)
        else None
    )
    goal = (
        inventory.get(FP048_ANDROID_REPORT_GOAL_ID)
        if isinstance(inventory, dict)
        else None
    )
    goal_path = _exact_repo_file(root, FP048_ANDROID_REPORT_GOAL_PATH)
    completion_binding = _binding_by_role(
        checkpoint,
        FP048_ANDROID_REPORT_COMPLETION_ROLE,
    )
    completion_sha256 = FP048_ANDROID_REPORT_PINNED_JSON_SHA256_BY_PATH[
        FP048_ANDROID_REPORT_COMPLETION_PATH
    ]
    completion_projection = {
        "role": FP048_ANDROID_REPORT_COMPLETION_ROLE,
        "document_id": FP048_ANDROID_REPORT_COMPLETION_DOCUMENT_ID,
        "path": FP048_ANDROID_REPORT_COMPLETION_PATH,
        "file_sha256": completion_sha256,
    }
    actual_projection = (
        {
            key: completion_binding.get(key)
            for key in ("role", "document_id", "path", "file_sha256")
        }
        if isinstance(completion_binding, dict)
        else None
    )
    if (
        not isinstance(state, dict)
        or not isinstance(statuses, dict)
        or statuses.get(FP048_ANDROID_REPORT_GOAL_ID)
        != "COMPLETE_AT_TARGET"
        or not isinstance(goal, dict)
        or goal.get("goal_id") != FP048_ANDROID_REPORT_GOAL_ID
        or goal.get("path") != FP048_ANDROID_REPORT_GOAL_PATH
        or goal.get("sha256") != FP048_ANDROID_REPORT_GOAL_SHA256
        or goal_path is None
        or continuation.sha256_file(goal_path)
        != FP048_ANDROID_REPORT_GOAL_SHA256
        or actual_projection != completion_projection
        or not _sha256_binding_matches(
            root,
            completion_binding,
            expected_path=FP048_ANDROID_REPORT_COMPLETION_PATH,
        )
        or not isinstance(completion_roles, dict)
        or completion_roles.get(FP048_ANDROID_REPORT_GOAL_ID)
        != [FP048_ANDROID_REPORT_COMPLETION_ROLE]
        or not isinstance(history, list)
    ):
        return None

    update_matches = [
        event
        for event in history
        if isinstance(event, dict)
        and (
            event.get("sequence") == 43
            or event.get("event_id")
            == FP048_ANDROID_REPORT_CANONICAL_UPDATE_EVENT_ID
        )
    ]
    completion_matches = [
        event
        for event in history
        if isinstance(event, dict)
        and (
            event.get("sequence") == 44
            or event.get("event_id")
            == FP048_ANDROID_REPORT_COMPLETION_EVENT_ID
        )
    ]
    if len(update_matches) != 1 or len(completion_matches) != 1:
        return None
    update = update_matches[0]
    completion = completion_matches[0]
    if not (
        update.get("sequence") == 43
        and update.get("event_id")
        == FP048_ANDROID_REPORT_CANONICAL_UPDATE_EVENT_ID
        and update.get("event_type") == "CANONICAL_BINDINGS_UPDATED"
        and update.get("event_sha256")
        == FP048_ANDROID_REPORT_CANONICAL_UPDATE_EVENT_SHA256
        and continuation.event_sha256(update)
        == FP048_ANDROID_REPORT_CANONICAL_UPDATE_EVENT_SHA256
        and update.get("produced_by_goal_id")
        == FP048_ANDROID_REPORT_GOAL_ID
        and update.get("producer_completion_receipt_binding")
        == completion_projection
        and completion.get("sequence") == 44
        and completion.get("event_id")
        == FP048_ANDROID_REPORT_COMPLETION_EVENT_ID
        and completion.get("event_type") == "GOAL_COMPLETED"
        and completion.get("event_sha256")
        == FP048_ANDROID_REPORT_COMPLETION_EVENT_SHA256
        and continuation.event_sha256(completion)
        == FP048_ANDROID_REPORT_COMPLETION_EVENT_SHA256
        and completion.get("previous_event_sha256")
        == FP048_ANDROID_REPORT_CANONICAL_UPDATE_EVENT_SHA256
        and completion.get("canonical_update_event_sha256")
        == FP048_ANDROID_REPORT_CANONICAL_UPDATE_EVENT_SHA256
        and completion.get("subject_goal_id")
        == FP048_ANDROID_REPORT_GOAL_ID
        and completion.get("from_status") == "IN_PROGRESS"
        and completion.get("to_status") == "COMPLETE_AT_TARGET"
        and completion.get("status_changes")
        == {FP048_ANDROID_REPORT_GOAL_ID: "COMPLETE_AT_TARGET"}
        and completion.get("evidence_refs")
        == [FP048_ANDROID_REPORT_COMPLETION_ROLE]
        and completion.get("completion_receipt_binding")
        == completion_projection
        and completion.get("completion_evidence_bindings")
        == {FP048_ANDROID_REPORT_COMPLETION_ROLE: completion_projection}
    ):
        return None

    documents: dict[str, dict[str, Any]] = {}
    for relative, digest in (
        FP048_ANDROID_REPORT_PINNED_JSON_SHA256_BY_PATH.items()
    ):
        path = _exact_repo_file(root, relative)
        document = _load_exact_json(root, relative)
        if (
            path is None
            or continuation.sha256_file(path) != digest
            or not isinstance(document, dict)
        ):
            return None
        documents[relative] = document

    receipt = documents[FP048_ANDROID_REPORT_COMPLETION_PATH]
    expected_result_evidence = [
        {
            "kind": kind,
            "path": path,
            "sha256": FP048_ANDROID_REPORT_RESULT_SHA256_BY_KIND[kind],
        }
        for kind, path in FP048_ANDROID_REPORT_RESULT_PATH_BY_KIND.items()
    ]
    reviewer = receipt.get("reviewer")
    manifest = receipt.get("output_evidence_manifest")
    if not (
        receipt.get("schema_version") == "1.0"
        and receipt.get("document_id")
        == FP048_ANDROID_REPORT_COMPLETION_DOCUMENT_ID
        and receipt.get("evidence_type") == "WORK_ITEM_EXECUTION_RECEIPT"
        and receipt.get("target_goal_id")
        == FP048_ANDROID_REPORT_GOAL_ID
        and receipt.get("target_goal_content_sha256")
        == FP048_ANDROID_REPORT_GOAL_SHA256
        and receipt.get("status") == "ACCEPTED"
        and receipt.get("result") == "PASS"
        and receipt.get("result_evidence") == expected_result_evidence
        and isinstance(reviewer, dict)
        and reviewer.get("id") == "WS-FP048-INDEPENDENT-REVIEWER-001"
        and reviewer.get("task") == "/root/fp048_independent_review"
        and reviewer.get("decision") == "APPROVED"
        and receipt.get("reviewer_provenance")
        == {
            "path": FP048_ANDROID_REPORT_REVIEW_PATH,
            "sha256": FP048_ANDROID_REPORT_PINNED_JSON_SHA256_BY_PATH[
                FP048_ANDROID_REPORT_REVIEW_PATH
            ],
        }
        and isinstance(manifest, list)
        and all(isinstance(row, dict) for row in manifest)
        and continuation.canonical_json_sha256(manifest)
        == receipt.get("output_evidence_manifest_sha256")
        and all(
            _sha256_binding_matches(
                root,
                row,
                expected_path=row.get("path"),
            )
            for row in manifest
        )
    ):
        return None

    implementation_path = FP048_ANDROID_REPORT_RESULT_PATH_BY_KIND[
        "IMPLEMENTATION_RECORD"
    ]
    implementation = documents[implementation_path]
    changed = implementation.get("changed_artifacts")
    if (
        implementation.get("schema_version") != "1.0"
        or implementation.get("document_id")
        != "WS-FP048-ENCRYPTION-CONNECTION-SECURITY-IMPLEMENTATION-20260802-001"
        or implementation.get("goal_id") != FP048_ANDROID_REPORT_GOAL_ID
        or implementation.get("kind") != "IMPLEMENTATION_RECORD"
        or implementation.get("status") != "PASS"
        or implementation.get("scope_kind")
        != "EXACT_ORDERED_FP048_IMPLEMENTATION_PATH_SET"
        or implementation.get("exact_path_count") != 106
        or not isinstance(changed, list)
        or len(changed) != 106
        or any(not isinstance(row, dict) for row in changed)
        or len({row.get("path") for row in changed}) != 106
        or implementation.get("implementation_content_set_sha256")
        != _implementation_content_set_sha256(changed)
    ):
        return None
    by_path = {row["path"]: row for row in changed}
    if set(by_path).intersection(packet_bindings) != set(expected):
        return None
    result: dict[str, str] = {}
    for relative, transition in expected.items():
        if by_path.get(relative) != {
            "path": relative,
            "before_sha256": transition["before_sha256"],
            "after_sha256": transition["after_sha256"],
            "before_source": "SEQ42_START_GATE_DIRTY_SNAPSHOT",
            "change_kind": "MODIFIED",
        }:
            return None
        live = _exact_repo_file(root, relative)
        if (
            live is None
            or (
                require_live_after
                and continuation.sha256_file(live)
                != transition["after_sha256"]
            )
        ):
            return None
        result[relative] = transition["after_sha256"]

    subject = documents[FP048_ANDROID_REPORT_REVIEW_SUBJECT_PATH]
    attestation = documents[FP048_ANDROID_REPORT_REVIEW_ATTESTATION_PATH]
    review = documents[FP048_ANDROID_REPORT_REVIEW_PATH]
    subject_sha256 = FP048_ANDROID_REPORT_PINNED_JSON_SHA256_BY_PATH[
        FP048_ANDROID_REPORT_REVIEW_SUBJECT_PATH
    ]
    attestation_sha256 = FP048_ANDROID_REPORT_PINNED_JSON_SHA256_BY_PATH[
        FP048_ANDROID_REPORT_REVIEW_ATTESTATION_PATH
    ]
    implementation_scope = subject.get("implementation_scope")
    if not (
        subject.get("schema_version") == "1.0"
        and subject.get("evidence_type") == "INTERNAL_REVIEW_SUBJECT"
        and subject.get("goal_id") == FP048_ANDROID_REPORT_GOAL_ID
        and subject.get("reviewed_result_sha256_by_kind")
        == FP048_ANDROID_REPORT_RESULT_SHA256_BY_KIND
        and isinstance(implementation_scope, dict)
        and implementation_scope.get("scope_kind")
        == implementation.get("scope_kind")
        and implementation_scope.get("exact_path_count") == 106
        and implementation_scope.get("paths")
        == [row["path"] for row in changed]
        and implementation_scope.get("content_set_sha256")
        == implementation.get("implementation_content_set_sha256")
        and attestation.get("schema_version") == "1.0"
        and attestation.get("evidence_type")
        == "INTERNAL_REVIEW_ATTESTATION"
        and attestation.get("goal_id") == FP048_ANDROID_REPORT_GOAL_ID
        and attestation.get("review_subject_sha256") == subject_sha256
        and attestation.get("reviewed_result_sha256_by_kind")
        == FP048_ANDROID_REPORT_RESULT_SHA256_BY_KIND
        and attestation.get("reviewer_id")
        == "WS-FP048-INDEPENDENT-REVIEWER-001"
        and attestation.get("reviewer_task")
        == "/root/fp048_independent_review"
        and attestation.get("decision") == "APPROVED"
        and review.get("schema_version") == "1.0"
        and review.get("document_id")
        == "WS-FP048-ENCRYPTION-CONNECTION-SECURITY-INTERNAL-REVIEW-20260802-001"
        and review.get("evidence_type") == "INDEPENDENT_INTERNAL_REVIEW"
        and review.get("goal_id") == FP048_ANDROID_REPORT_GOAL_ID
        and review.get("status") == "PASS"
        and review.get("review_subject_sha256") == subject_sha256
        and review.get("reviewed_result_sha256_by_kind")
        == FP048_ANDROID_REPORT_RESULT_SHA256_BY_KIND
        and review.get("reviewer_id")
        == "WS-FP048-INDEPENDENT-REVIEWER-001"
        and review.get("reviewer_task") == "/root/fp048_independent_review"
        and review.get("attestation_provenance")
        == {
            "path": FP048_ANDROID_REPORT_REVIEW_ATTESTATION_PATH,
            "sha256": attestation_sha256,
        }
        and review.get("findings") == attestation.get("findings")
        and review.get("review_boundary")
        == attestation.get("review_boundary")
    ):
        return None
    return result if set(result) == set(expected) else None


def _fp048_sealed_product_successor_artifacts(
    root: Path,
    checkpoint: dict[str, Any],
) -> dict[str, tuple[str, str]] | None:
    """Return sealed FP048 modified transitions after the strict proof."""
    if not _fp048_android_report_successor_is_declared(checkpoint):
        return {}
    packet_bindings = {
        relative: transition["before_sha256"]
        for relative, transition
        in FP048_ANDROID_REPORT_SUCCESSOR_BY_PATH.items()
    }
    fp046_errors, _, fp046_transitions = (
        validate_fp046_r014_successor_authority(root, checkpoint)
    )
    if fp046_errors:
        return None
    if (
        _fp048_android_report_successor_bindings(
            root,
            checkpoint,
            packet_bindings,
            require_live_after=not bool(fp046_transitions),
        )
        is None
    ):
        return None
    implementation = _load_exact_json(
        root,
        FP048_ANDROID_REPORT_RESULT_PATH_BY_KIND[
            "IMPLEMENTATION_RECORD"
        ],
    )
    changed = (
        implementation.get("changed_artifacts")
        if isinstance(implementation, dict)
        else None
    )
    if not isinstance(changed, list):
        return None
    result: dict[str, tuple[str, str]] = {}
    for record in changed:
        if not isinstance(record, dict):
            return None
        if record.get("change_kind") != "MODIFIED":
            continue
        relative = record.get("path")
        before_sha256 = record.get("before_sha256")
        after_sha256 = record.get("after_sha256")
        if (
            not isinstance(relative, str)
            or relative in result
            or not isinstance(before_sha256, str)
            or not continuation.SHA256_RE.fullmatch(before_sha256)
            or not isinstance(after_sha256, str)
            or not continuation.SHA256_RE.fullmatch(after_sha256)
            or before_sha256 == after_sha256
        ):
            return None
        result[relative] = before_sha256, after_sha256
    return result if len(result) == 78 else None


def _fp046_completion_is_present(checkpoint: dict[str, Any]) -> bool:
    state = checkpoint.get("goal_execution")
    if not isinstance(state, dict):
        return False
    statuses = state.get("status_by_goal")
    history = state.get("transition_history")
    return bool(
        isinstance(statuses, dict)
        and statuses.get(FP046_GOAL_ID) == "COMPLETE_AT_TARGET"
        or isinstance(history, list)
        and any(
            isinstance(event, dict)
            and event.get("event_id")
            in {FP046_CANONICAL_UPDATE_EVENT_ID, FP046_COMPLETION_EVENT_ID}
            for event in history
        )
    )


def _fp046_completion_is_declared(
    root: Path,
    checkpoint: dict[str, Any],
) -> bool:
    state = checkpoint.get("goal_execution")
    if not isinstance(state, dict):
        return False
    statuses = state.get("status_by_goal")
    evidence = state.get("completion_evidence_by_goal")
    history = state.get("transition_history")
    completion_binding = _binding_by_role(checkpoint, FP046_COMPLETION_ROLE)
    expected_completion_binding = {
        "role": FP046_COMPLETION_ROLE,
        "document_id": (
            "WS-FP046-CONSENT-WITHDRAWAL-DELETION-WORK-ITEM-"
            "COMPLETION-20260810-001"
        ),
        "path": FP046_COMPLETION_PATH,
        "file_sha256": FP046_COMPLETION_SHA256,
        "identity_json_path": "document_id",
        "mutable": False,
    }
    if (
        not isinstance(statuses, dict)
        or statuses.get(FP046_GOAL_ID) != "COMPLETE_AT_TARGET"
        or not isinstance(evidence, dict)
        or evidence.get(FP046_GOAL_ID) != [FP046_COMPLETION_ROLE]
        or completion_binding != expected_completion_binding
        or not isinstance(history, list)
        or len(history) < 55
    ):
        return False
    update, completion = history[53:55]
    if not isinstance(update, dict) or not isinstance(completion, dict):
        return False
    return bool(
        update.get("sequence") == 54
        and update.get("event_id") == FP046_CANONICAL_UPDATE_EVENT_ID
        and update.get("event_type") == "CANONICAL_BINDINGS_UPDATED"
        and update.get("produced_by_goal_id") == FP046_GOAL_ID
        and update.get("event_sha256") == continuation.event_sha256(update)
        and completion.get("sequence") == 55
        and completion.get("event_id") == FP046_COMPLETION_EVENT_ID
        and completion.get("event_type") == "GOAL_COMPLETED"
        and completion.get("subject_goal_id") == FP046_GOAL_ID
        and completion.get("previous_event_sha256")
        == update.get("event_sha256")
        and completion.get("canonical_update_event_sha256")
        == update.get("event_sha256")
        and completion.get("completion_receipt_binding")
        == {
            key: expected_completion_binding[key]
            for key in ("role", "document_id", "path", "file_sha256")
        }
        and completion.get("event_sha256")
        == continuation.event_sha256(completion)
    )


def validate_fp046_r014_successor_authority(
    root: Path,
    checkpoint: dict[str, Any],
) -> tuple[
    list[str],
    dict[str, str],
    dict[str, tuple[str, str]],
]:
    """Consume sealed R014 exact6 authority without granting completion credit."""
    if not _fp046_completion_is_present(checkpoint):
        return [], {}, {}
    if not _fp046_completion_is_declared(root, checkpoint):
        return ["FP046 R014 successor declaration differs"], {}, {}

    expected_source_bindings = [
        {
            "binding_id": "R014-SRC-001",
            "byte_length": FP046_IMPLEMENTATION_BYTE_COUNT,
            "path": FP046_IMPLEMENTATION_PATH,
            "sha256": FP046_IMPLEMENTATION_SHA256,
            "subject_role": "FP046_IMPLEMENTATION_RESULT",
        },
        {
            "binding_id": "R014-SRC-002",
            "byte_length": 3809,
            "path": f"{FP046_RESULT_DIRECTORY}/verification-result.json",
            "sha256": (
                "98d66bba03ea49eae53fb212ba30ccb7f2d301e75d62f0f600f50fe5a61f7f84"
            ),
            "subject_role": "FP046_VERIFICATION_RESULT",
        },
        {
            "binding_id": "R014-SRC-003",
            "byte_length": 533282,
            "path": (
                "docs/control/audits/"
                "walksafe-implementation-gap-analysis-20260810-r025.json"
            ),
            "sha256": (
                "082a688ae32b068418d007111560802be3376deeee6cad526432a69b9d0321a1"
            ),
            "subject_role": "GAP055_R025_SUCCESSOR",
        },
        *[
            {
                "binding_id": binding["binding_id"],
                "byte_length": binding["byte_count"],
                "path": binding["path"],
                "sha256": binding["sha256"],
                "subject_role": (
                    "FP046_DLV-"
                    + {
                        "ARTIFACT_CHANGE_LOG": "DOC-05",
                        "ARTIFACT_REGISTER": "DOC-01",
                        "REQUIREMENTS_TRACEABILITY": "REQ-16",
                        "DESIGN_TRACEABILITY": "DES-06",
                        "IMPLEMENTATION_MANIFEST": "DEV-01",
                        "MODULE_REGISTER": "DEV-18",
                    }[role]
                    + "_PHYSICAL_SUCCESSOR"
                ),
            }
            for role, binding in FP046_R014_ARTIFACT_BINDING_BY_ROLE.items()
        ],
    ]
    documents: dict[str, dict[str, Any]] = {}
    for role, binding in FP046_R014_BINDING_BY_ROLE.items():
        path = _exact_repo_file(root, binding["path"])
        if (
            path is None
            or path.stat().st_size != binding["byte_count"]
            or continuation.sha256_file(path) != binding["sha256"]
        ):
            return [f"FP046 R014 {role} physical binding differs"], {}, {}
        document = _load_exact_json(root, binding["path"])
        if (
            not isinstance(document, dict)
            or document.get("schema_version") != binding["schema_version"]
        ):
            return [f"FP046 R014 {role} identity differs"], {}, {}
        documents[role] = document

    ledger = documents["EXACT257_R014_LEDGER"]
    evidence = documents["EXACT257_R014_EVIDENCE"]
    receipt = documents["EXACT257_R014_CHECK_RECEIPT"]
    progress = ledger.get(
        "r014_fp046_gap055_r025_artifact_progress_application"
    )
    zero_credits = progress.get("zero_credits") if isinstance(progress, dict) else None
    if (
        ledger.get("ledger_id")
        != "WS-PHASE1-EXACT257-SUCCESSOR-LEDGER-20260810-R014"
        or not isinstance(ledger.get("records"), list)
        or len(ledger["records"]) != 257
        or ledger.get("r014_source_bindings") != expected_source_bindings
        or not isinstance(progress, dict)
        or progress.get("target_artifact_ids")
        != [
            "DLV-DOC-05",
            "DLV-DOC-01",
            "DLV-REQ-16",
            "DLV-DES-06",
            "DLV-DEV-01",
            "DLV-DEV-18",
        ]
        or progress.get("unchanged_record_count") != 251
        or progress.get("progress_binding_record_count") != 6
        or progress.get("authorization_delta_count") != 0
        or not isinstance(zero_credits, dict)
        or any(value != 0 for value in zero_credits.values())
        or progress.get("formal_test_status") != "NOT_RUN"
        or progress.get("actual_device_status") != "NOT_RUN"
        or progress.get("external_evidence_status") != "NOT_RUN"
        or progress.get("production_deployment_status") != "NOT_RUN"
        or progress.get("release_status") != "NOT_ELIGIBLE"
        or evidence.get("verdict")
        != "PASS_FOR_FP046_GAP055_R025_EXACT6_RECORD_PROGRESS_BINDING_WITH_ZERO_CREDIT_ONLY"
        or receipt.get("status") != "PASS"
        or receipt.get("verdict") != evidence.get("verdict")
        or receipt.get("source_bindings") != expected_source_bindings
    ):
        return ["FP046 R014 exact257 zero-credit authority differs"], {}, {}

    completion_path = _exact_repo_file(root, FP046_COMPLETION_PATH)
    completion = _load_exact_json(root, FP046_COMPLETION_PATH)
    expected_downstream = {
        role: {
            "path": binding["path"],
            "sha256": binding["sha256"],
            "schema_version": binding["schema_version"],
        }
        for role, binding in FP046_R014_BINDING_BY_ROLE.items()
    }
    downstream = completion.get("downstream_consumer_bindings") if isinstance(completion, dict) else None
    downstream_by_role = {
        row.get("role"): {
            key: row.get(key) for key in ("path", "sha256", "schema_version")
        }
        for row in downstream
        if isinstance(row, dict) and row.get("role") in expected_downstream
    } if isinstance(downstream, list) else {}
    if (
        completion_path is None
        or completion_path.stat().st_size != FP046_COMPLETION_BYTE_COUNT
        or continuation.sha256_file(completion_path) != FP046_COMPLETION_SHA256
        or not isinstance(completion, dict)
        or completion.get("target_goal_id") != FP046_GOAL_ID
        or completion.get("status") != "ACCEPTED"
        or completion.get("result") != "PASS"
        or completion.get("completion_boundary") != FP046_ZERO_CREDIT_BOUNDARY
        or downstream_by_role != expected_downstream
    ):
        return ["FP046 R014 completion authority differs"], {}, {}

    implementation_path = _exact_repo_file(root, FP046_IMPLEMENTATION_PATH)
    implementation = _load_exact_json(root, FP046_IMPLEMENTATION_PATH)
    manifest = implementation.get("final_content_manifest") if isinstance(implementation, dict) else None
    rows = manifest.get("files") if isinstance(manifest, dict) else None
    if (
        implementation_path is None
        or implementation_path.stat().st_size != FP046_IMPLEMENTATION_BYTE_COUNT
        or continuation.sha256_file(implementation_path) != FP046_IMPLEMENTATION_SHA256
        or not isinstance(implementation, dict)
        or implementation.get("goal_id") != FP046_GOAL_ID
        or implementation.get("kind") != "IMPLEMENTATION_RECORD"
        or implementation.get("status") != "PASS"
        or implementation.get("completion_boundary") != FP046_ZERO_CREDIT_BOUNDARY
        or not isinstance(manifest, dict)
        or manifest.get("schema_version")
        != "walksafe.fp046-final-content-manifest.v1"
        or not isinstance(rows, list)
        or len(rows) != manifest.get("exact_path_count")
        or len(rows) != 103
    ):
        return ["FP046 103-source implementation authority differs"], {}, {}
    paths = [row.get("path") for row in rows if isinstance(row, dict)]
    if (
        len(paths) != 103
        or len(set(paths)) != 103
        or any(
            not isinstance(row, dict)
            or set(row) != {"role", "path", "byte_length", "sha256", "group_id"}
            or row.get("role") != "FP046_FINAL_SOURCE_CONTENT"
            or not isinstance(row.get("path"), str)
            or not isinstance(row.get("byte_length"), int)
            or isinstance(row.get("byte_length"), bool)
            or row["byte_length"] < 0
            or not isinstance(row.get("sha256"), str)
            or continuation.SHA256_RE.fullmatch(row["sha256"]) is None
            for row in rows
        )
        or manifest.get("path_set_sha256")
        != continuation.canonical_json_sha256(paths)
        or manifest.get("content_set_sha256")
        != continuation.canonical_json_sha256(rows)
        or implementation.get("implementation_content_set_sha256")
        != manifest.get("content_set_sha256")
    ):
        return ["FP046 103-source manifest differs"], {}, {}

    start_gate_path = _exact_repo_file(root, FP046_START_GATE_PATH)
    repository_state_path = _exact_repo_file(
        root, FP046_START_GATE_REPOSITORY_STATE_PATH
    )
    repository_state = _load_exact_json(
        root, FP046_START_GATE_REPOSITORY_STATE_PATH
    )
    authority = implementation.get("authority_bindings")
    start_authority = authority.get("start_gate") if isinstance(authority, dict) else None
    dirty = repository_state.get("dirty_snapshot") if isinstance(repository_state, dict) else None
    dirty_rows = dirty.get("paths") if isinstance(dirty, dict) else None
    if (
        start_gate_path is None
        or continuation.sha256_file(start_gate_path) != FP046_START_GATE_SHA256
        or repository_state_path is None
        or continuation.sha256_file(repository_state_path)
        != FP046_START_GATE_REPOSITORY_STATE_SHA256
        or not isinstance(start_authority, dict)
        or start_authority.get("path") != FP046_START_GATE_PATH
        or start_authority.get("sha256") != FP046_START_GATE_SHA256
        or start_authority.get("repository_state_path")
        != FP046_START_GATE_REPOSITORY_STATE_PATH
        or start_authority.get("repository_state_sha256")
        != FP046_START_GATE_REPOSITORY_STATE_SHA256
        or not isinstance(repository_state, dict)
        or repository_state.get("evidence_type") != "GATE_REPOSITORY_STATE"
        or repository_state.get("gate_event_id")
        != "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-20260809-005"
        or not isinstance(dirty_rows, list)
    ):
        return ["FP046 start-snapshot authority differs"], {}, {}
    before_by_path = {
        row["path"]: row["worktree"]["sha256"]
        for row in dirty_rows
        if isinstance(row, dict)
        and row.get("path_role") == "CURRENT"
        and isinstance(row.get("path"), str)
        and isinstance(row.get("worktree"), dict)
        and row["worktree"].get("state") == "PRESENT"
        and row["worktree"].get("type") == "REGULAR_FILE"
        and isinstance(row["worktree"].get("sha256"), str)
        and continuation.SHA256_RE.fullmatch(row["worktree"]["sha256"])
    }
    final_bindings: dict[str, str] = {}
    transitions: dict[str, tuple[str, str]] = {}
    missing = unchanged = 0
    for row in rows:
        relative = row["path"]
        path = _exact_repo_file(root, relative)
        if (
            path is None
            or path.stat().st_size != row["byte_length"]
            or continuation.sha256_file(path) != row["sha256"]
        ):
            return [f"FP046 final source binding differs: {relative}"], {}, {}
        final_bindings[relative] = row["sha256"]
        before = before_by_path.get(relative)
        if before is None:
            missing += 1
        elif before == row["sha256"]:
            unchanged += 1
        else:
            transitions[relative] = (before, row["sha256"])
    if (len(final_bindings), len(transitions), missing, unchanged) != (103, 67, 29, 7):
        return ["FP046 start-to-final source split differs"], {}, {}

    artifact_bindings = {
        binding["path"]: binding["sha256"]
        for binding in FP046_R014_ARTIFACT_BINDING_BY_ROLE.values()
    }
    for relative, digest in artifact_bindings.items():
        path = _exact_repo_file(root, relative)
        if path is None or continuation.sha256_file(path) != digest:
            return [f"FP046 R014 artifact successor differs: {relative}"], {}, {}
    return [], artifact_bindings, transitions


def _archived_requirements_traceability_errors(
    archive: dict[str, Any],
    *,
    sequences: tuple[int, ...],
    prefix: str,
) -> set[str] | None:
    """Derive the exact frozen diagnostics from sealed event snapshots."""
    state = archive.get("goal_execution")
    history = (
        state.get("transition_history")
        if isinstance(state, dict)
        else None
    )
    if not isinstance(history, list):
        return None
    observed: list[tuple[int, int, Any]] = []
    for index, event in enumerate(history, start=1):
        if not isinstance(event, dict):
            return None
        snapshot = event.get("canonical_binding_snapshot_after")
        if (
            isinstance(snapshot, dict)
            and "REQUIREMENTS_TRACEABILITY" in snapshot
        ):
            observed.append(
                (
                    index,
                    event.get("sequence"),
                    snapshot["REQUIREMENTS_TRACEABILITY"],
                )
            )
    if (
        tuple(index for index, _, _ in observed) != sequences
        or any(sequence != index for index, sequence, _ in observed)
        or any(
            binding != ARCHIVED_REQUIREMENTS_TRACEABILITY_BINDING
            for _, _, binding in observed
        )
    ):
        return None
    return {
        f"{prefix}Goal graph event {sequence}: canonical binding "
        "SHA-256 differs: REQUIREMENTS_TRACEABILITY"
        for sequence in sequences
    }


def _sealed_archived_requirements_traceability_errors(
    root: Path,
    archive: dict[str, Any],
) -> set[str]:
    """Derive the exact eight sealed v2.3/v2.2 RTM diagnostics."""
    v23_errors = _archived_requirements_traceability_errors(
        archive,
        sequences=V23_REQUIREMENTS_TRACEABILITY_EVENT_SEQUENCES,
        prefix="",
    )
    v22_path = _exact_repo_file(root, V22_ARCHIVE_RELATIVE.as_posix())
    if (
        v23_errors is None
        or v22_path is None
        or continuation.sha256_file(v22_path) != V22_ARCHIVE_RAW_SHA256
    ):
        return set()
    v22_archive = _load_exact_json(root, V22_ARCHIVE_RELATIVE.as_posix())
    if not isinstance(v22_archive, dict):
        return set()
    v22_errors = _archived_requirements_traceability_errors(
        v22_archive,
        sequences=V22_REQUIREMENTS_TRACEABILITY_EVENT_SEQUENCES,
        prefix="archived v2.2: ",
    )
    if v22_errors is None:
        return set()
    result = v23_errors | v22_errors
    return result if len(result) == 8 else set()


def _fp008_archived_requirements_traceability_successor_errors(
    root: Path,
    checkpoint: dict[str, Any],
    archive: dict[str, Any],
) -> set[str]:
    """Return only the eight RTM diagnostics discharged by sealed FP008."""
    if not _fp008_admin_review_completion_is_declared(root, checkpoint):
        return set()
    receipt = _load_exact_json(
        root,
        FP008_ADMIN_REVIEW_COMPLETION_PATH,
    )
    downstream = (
        receipt.get("downstream_consumer_bindings")
        if isinstance(receipt, dict)
        else None
    )
    rtm_bindings = (
        [
            binding
            for binding in downstream
            if isinstance(binding, dict)
            and binding.get("role") == "REQUIREMENTS_TRACEABILITY"
        ]
        if isinstance(downstream, list)
        else []
    )
    live_path = _exact_repo_file(
        root,
        FP008_REQUIREMENTS_TRACEABILITY_BINDING["path"],
    )
    if (
        rtm_bindings != [FP008_REQUIREMENTS_TRACEABILITY_BINDING]
        or live_path is None
        or continuation.sha256_file(live_path)
        != FP008_REQUIREMENTS_TRACEABILITY_BINDING["sha256"]
        or live_path.stat().st_size
        != FP008_REQUIREMENTS_TRACEABILITY_BYTE_COUNT
    ):
        return set()
    return _sealed_archived_requirements_traceability_errors(root, archive)


def _fp008_admin_review_completion_is_declared(
    root: Path,
    checkpoint: dict[str, Any],
) -> bool:
    """Prove the exact sealed seq49/50 declaration and its safe boundary."""
    state = checkpoint.get("goal_execution")
    statuses = (
        state.get("status_by_goal")
        if isinstance(state, dict)
        else None
    )
    completion_roles = (
        state.get("completion_evidence_by_goal")
        if isinstance(state, dict)
        else None
    )
    history = (
        state.get("transition_history")
        if isinstance(state, dict)
        else None
    )
    canonical_bindings = checkpoint.get("canonical_bindings")
    canonical_roles = (
        [
            binding.get("role")
            for binding in canonical_bindings
            if isinstance(binding, dict)
        ]
        if isinstance(canonical_bindings, list)
        else []
    )
    completion_binding = _binding_by_role(
        checkpoint,
        FP008_ADMIN_REVIEW_COMPLETION_ROLE,
    )
    rtm_binding = _binding_by_role(
        checkpoint,
        "REQUIREMENTS_TRACEABILITY",
    )
    current_work = checkpoint.get("current_work")
    handoff = checkpoint.get("session_handoff")
    metadata = checkpoint.get("metadata")
    repository = checkpoint.get("repository")
    if (
        not isinstance(state, dict)
        or not isinstance(statuses, dict)
        or statuses.get(FP008_ADMIN_REVIEW_GOAL_ID)
        != "COMPLETE_AT_TARGET"
        or not isinstance(completion_roles, dict)
        or completion_binding
        != FP008_ADMIN_REVIEW_COMPLETION_CANONICAL_BINDING
        or rtm_binding
        != FP008_REQUIREMENTS_TRACEABILITY_CANONICAL_BINDING
        or not isinstance(history, list)
        or len(history) < 50
        or not isinstance(canonical_bindings, list)
        or len(canonical_bindings) != 40
        or len(canonical_roles) != 40
        or len(set(canonical_roles)) != 40
        or continuation.canonical_json_sha256(canonical_bindings)
        != FP008_ADMIN_REVIEW_CANONICAL_BINDINGS_SHA256
        or checkpoint.get("authority_boundary")
        != FP008_ADMIN_REVIEW_AUTHORITY_BOUNDARY
        or checkpoint.get("verification_boundary")
        != FP008_ADMIN_REVIEW_VERIFICATION_BOUNDARY
        or checkpoint.get("approved_state")
        != FP008_ADMIN_REVIEW_APPROVED_STATE
        or state.get("standing_execution_authority")
        != FP008_ADMIN_REVIEW_STANDING_EXECUTION_AUTHORITY
        or not isinstance(current_work, dict)
        or not isinstance(handoff, dict)
        or not isinstance(metadata, dict)
        or metadata.get("status") != "ACTIVE_WORKING_CHECKPOINT"
        or not isinstance(repository, dict)
        or repository.get("destructive_cleanup_forbidden") is not True
        or any(
            field in current_work
            for field in FP008_ADMIN_REVIEW_FORBIDDEN_RELEASE_FIELDS
            if field != "release_completion_claimed"
        )
        or any(
            field in handoff
            for field in FP008_ADMIN_REVIEW_FORBIDDEN_RELEASE_FIELDS
        )
    ):
        return False

    selected = [
        (index, event)
        for index, event in enumerate(history)
        if isinstance(event, dict) and event.get("sequence") in {49, 50}
    ]
    if (
        len(selected) != 2
        or [index for index, _ in selected] != [48, 49]
        or [event.get("sequence") for _, event in selected] != [49, 50]
        or not isinstance(history[47], dict)
    ):
        return False
    predecessor = history[47]
    update, completion = [event for _, event in selected]
    completion_snapshot = completion.get("canonical_binding_snapshot_after")
    update_snapshot = update.get("canonical_binding_snapshot_after")
    update_sha256 = update.get("event_sha256")
    completion_sha256 = completion.get("event_sha256")
    history_suffix = history[49:]
    if (
        predecessor.get("sequence") != 48
        or predecessor.get("event_id")
        != FP008_ADMIN_REVIEW_SOURCE_RESUME_EVENT_ID
        or predecessor.get("event_type") != "WORK_SESSION_RESUMED"
        or predecessor.get("subject_goal_id")
        != FP008_ADMIN_REVIEW_GOAL_ID
        or predecessor.get("event_sha256")
        != FP008_ADMIN_REVIEW_SOURCE_RESUME_EVENT_SHA256
        or predecessor.get("event_sha256")
        != continuation.event_sha256(predecessor)
        or any(not isinstance(event, dict) for event in history_suffix)
        or [event.get("sequence") for event in history_suffix]
        != list(range(50, len(history) + 1))
        or any(
            event.get("event_sha256") != continuation.event_sha256(event)
            for event in history_suffix
        )
        or any(
            event.get("previous_event_sha256")
            != history_suffix[index - 1].get("event_sha256")
            for index, event in enumerate(history_suffix[1:], start=1)
        )
        or state.get("transition_history_anchor_sha256")
        != history_suffix[-1].get("event_sha256")
    ):
        return False

    snapshot_fields = {"role", "document_id", "path", "file_sha256"}
    if (
        not isinstance(update_snapshot, dict)
        or update_snapshot != completion_snapshot
        or len(update_snapshot) != 40
        or continuation.canonical_json_sha256(update_snapshot)
        != FP008_ADMIN_REVIEW_CANONICAL_SNAPSHOT_SHA256
        or any(
            not isinstance(binding, dict)
            or set(binding) != snapshot_fields
            or binding.get("role") != role
            for role, binding in update_snapshot.items()
        )
        or update_snapshot.get(FP008_ADMIN_REVIEW_COMPLETION_ROLE)
        != FP008_ADMIN_REVIEW_COMPLETION_BINDING
        or update_snapshot.get("REQUIREMENTS_TRACEABILITY")
        != {
            key: FP008_REQUIREMENTS_TRACEABILITY_CANONICAL_BINDING[key]
            for key in ("role", "document_id", "path", "file_sha256")
        }
    ):
        return False

    if not (
        set(update) == FP008_ADMIN_REVIEW_UPDATE_EVENT_FIELDS
        and update.get("sequence") == 49
        and update.get("event_id")
        == FP008_ADMIN_REVIEW_CANONICAL_UPDATE_EVENT_ID
        and update.get("event_type") == "CANONICAL_BINDINGS_UPDATED"
        and update.get("occurred_on") == "2026-08-09"
        and update.get("occurred_at")
        == FP008_ADMIN_REVIEW_UPDATE_OCCURRED_AT
        and update.get("previous_focus_goal_id")
        == FP008_ADMIN_REVIEW_GOAL_ID
        and update.get("previous_focus_content_sha256")
        == FP008_ADMIN_REVIEW_GOAL_SHA256
        and update.get("focus_goal_id") == FP008_ADMIN_REVIEW_GOAL_ID
        and update.get("focus_goal_content_sha256")
        == FP008_ADMIN_REVIEW_GOAL_SHA256
        and update.get("from_status") == "IN_PROGRESS"
        and update.get("to_status") == "IN_PROGRESS"
        and update.get("static_plan_manifest_sha256")
        == FP008_ADMIN_REVIEW_MANIFEST_SHA256
        and update.get("status_changes") == {}
        and update.get("runtime_after")
        == FP008_ADMIN_REVIEW_UPDATE_RUNTIME
        and update.get("blockers_after") == {}
        and update.get("blocker_resolution_ids_after") == []
        and update.get("source_checkpoint_version")
        == FP008_ADMIN_REVIEW_SOURCE_CHECKPOINT_VERSION
        and update.get("evidence_refs")
        == FP008_ADMIN_REVIEW_CHANGED_ROLES
        and update.get("previous_event_sha256")
        == FP008_ADMIN_REVIEW_SOURCE_RESUME_EVENT_SHA256
        and update.get("produced_by_goal_id")
        == FP008_ADMIN_REVIEW_GOAL_ID
        and update.get("produced_binding_roles")
        == FP008_ADMIN_REVIEW_PRODUCED_ROLES
        and update.get("producer_completion_receipt_binding")
        == FP008_ADMIN_REVIEW_COMPLETION_BINDING
        and update.get("changed_binding_roles")
        == FP008_ADMIN_REVIEW_CHANGED_ROLES
        and update.get("changed_subject_ids_by_role")
        == FP008_ADMIN_REVIEW_CHANGED_SUBJECT_IDS_BY_ROLE
        and update.get("producer_output_subject_ids_by_role")
        == FP008_ADMIN_REVIEW_PRODUCER_SUBJECT_IDS_BY_ROLE
        and update.get("impact_closure_goal_ids")
        == [FP008_ADMIN_REVIEW_PARENT_GOAL_ID]
        and update.get("impact_disposition_by_goal")
        == {
            FP008_ADMIN_REVIEW_PARENT_GOAL_ID: {
                "result": "REVALIDATION_REFRESH_REQUIRED",
                "target_status": "READY",
            }
        }
        and update.get("reopened_completion_event_sha256_by_goal") == {}
        and update_sha256 == FP008_ADMIN_REVIEW_UPDATE_EVENT_SHA256
        and update_sha256 == continuation.event_sha256(update)
        and set(completion) == FP008_ADMIN_REVIEW_COMPLETION_EVENT_FIELDS
        and completion.get("sequence") == 50
        and completion.get("event_id")
        == FP008_ADMIN_REVIEW_COMPLETION_EVENT_ID
        and completion.get("event_type") == "GOAL_COMPLETED"
        and completion.get("occurred_on") == "2026-08-09"
        and completion.get("occurred_at")
        == FP008_ADMIN_REVIEW_COMPLETION_OCCURRED_AT
        and completion.get("previous_focus_goal_id")
        == FP008_ADMIN_REVIEW_GOAL_ID
        and completion.get("previous_focus_content_sha256")
        == FP008_ADMIN_REVIEW_GOAL_SHA256
        and completion.get("focus_goal_id")
        == FP008_ADMIN_REVIEW_PARENT_GOAL_ID
        and completion.get("focus_goal_content_sha256")
        == FP008_ADMIN_REVIEW_PARENT_GOAL_SHA256
        and completion.get("subject_goal_id")
        == FP008_ADMIN_REVIEW_GOAL_ID
        and completion.get("from_status") == "IN_PROGRESS"
        and completion.get("to_status") == "COMPLETE_AT_TARGET"
        and completion.get("static_plan_manifest_sha256")
        == FP008_ADMIN_REVIEW_MANIFEST_SHA256
        and completion.get("status_changes")
        == {FP008_ADMIN_REVIEW_GOAL_ID: "COMPLETE_AT_TARGET"}
        and completion.get("runtime_after")
        == FP008_ADMIN_REVIEW_COMPLETION_RUNTIME
        and completion.get("blockers_after") == {}
        and completion.get("blocker_resolution_ids_after") == []
        and completion.get("source_checkpoint_version")
        == FP008_ADMIN_REVIEW_SOURCE_CHECKPOINT_VERSION
        and completion.get("evidence_refs")
        == [FP008_ADMIN_REVIEW_COMPLETION_ROLE]
        and completion.get("previous_event_sha256") == update_sha256
        and completion.get("canonical_update_event_sha256") == update_sha256
        and completion.get("completion_receipt_binding")
        == FP008_ADMIN_REVIEW_COMPLETION_BINDING
        and completion.get("completion_evidence_bindings")
        == {
            FP008_ADMIN_REVIEW_COMPLETION_ROLE: (
                FP008_ADMIN_REVIEW_COMPLETION_BINDING
            )
        }
        and completion.get("completion_evidence_by_goal_after")
        == FP008_ADMIN_REVIEW_COMPLETION_EVIDENCE_BY_GOAL
        and completion_sha256
        == FP008_ADMIN_REVIEW_COMPLETION_EVENT_SHA256
        and completion_sha256 == continuation.event_sha256(completion)
    ):
        return False

    receipt_path = _exact_repo_file(
        root,
        FP008_ADMIN_REVIEW_COMPLETION_PATH,
    )
    receipt = _load_exact_json(
        root,
        FP008_ADMIN_REVIEW_COMPLETION_PATH,
    )
    downstream = (
        receipt.get("downstream_consumer_bindings")
        if isinstance(receipt, dict)
        else None
    )
    rtm_bindings = (
        [
            binding
            for binding in downstream
            if isinstance(binding, dict)
            and binding.get("role") == "REQUIREMENTS_TRACEABILITY"
        ]
        if isinstance(downstream, list)
        else []
    )
    rtm_path = _exact_repo_file(
        root,
        FP008_REQUIREMENTS_TRACEABILITY_BINDING["path"],
    )
    if (
        receipt_path is None
        or continuation.sha256_file(receipt_path)
        != FP008_ADMIN_REVIEW_COMPLETION_SHA256
        or receipt_path.stat().st_size
        != FP008_ADMIN_REVIEW_COMPLETION_BYTE_COUNT
        or not isinstance(receipt, dict)
        or receipt.get("schema_version") != "1.0"
        or receipt.get("document_id")
        != FP008_ADMIN_REVIEW_COMPLETION_DOCUMENT_ID
        or receipt.get("evidence_type") != "WORK_ITEM_EXECUTION_RECEIPT"
        or receipt.get("target_goal_id") != FP008_ADMIN_REVIEW_GOAL_ID
        or receipt.get("target_goal_content_sha256")
        != FP008_ADMIN_REVIEW_GOAL_SHA256
        or receipt.get("status") != "ACCEPTED"
        or receipt.get("result") != "PASS"
        or receipt.get("completed_at")
        != FP008_ADMIN_REVIEW_UPDATE_OCCURRED_AT
        or receipt.get("generated_at")
        != FP008_ADMIN_REVIEW_UPDATE_OCCURRED_AT
        or receipt.get("completion_boundary")
        != FP008_ADMIN_REVIEW_COMPLETION_BOUNDARY
        or rtm_bindings != [FP008_REQUIREMENTS_TRACEABILITY_BINDING]
        or rtm_path is None
        or continuation.sha256_file(rtm_path)
        != FP008_REQUIREMENTS_TRACEABILITY_BINDING["sha256"]
        or rtm_path.stat().st_size
        != FP008_REQUIREMENTS_TRACEABILITY_BYTE_COUNT
    ):
        return False

    runtime_state_field_names = (
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
    )
    state_runtime_fields = {
        key: state.get(key)
        for key in runtime_state_field_names
    }
    completion_boundary = state.get("completion_boundary")
    if (
        not isinstance(completion_boundary, dict)
        or any(
            completion_boundary.get(field) != expected
            for field, expected
            in FP008_ADMIN_REVIEW_COMPLETION_BOUNDARY_SAFE_FIELDS.items()
        )
    ):
        return False

    if len(history) == 50:
        current_snapshot = continuation.canonical_binding_snapshot(checkpoint)
        final_runtime = FP008_ADMIN_REVIEW_COMPLETION_RUNTIME
        source_snapshot = handoff.get("source_commit_or_snapshot")
        pinned_handoff = {
            **handoff,
            "source_commit_or_snapshot": (
                {
                    key: value
                    for key, value in source_snapshot.items()
                    if key != "content_set_sha256"
                }
                if isinstance(source_snapshot, dict)
                else None
            ),
        }
        expected_state_runtime_fields = {
            key: final_runtime[key]
            for key in state_runtime_fields
        }
        if (
            not isinstance(source_snapshot, dict)
            or not continuation.SHA256_RE.fullmatch(
                source_snapshot.get("content_set_sha256", "")
            )
            or continuation.canonical_json_sha256(current_work)
            != FP008_ADMIN_REVIEW_CURRENT_WORK_SHA256
            or continuation.canonical_json_sha256(pinned_handoff)
            != FP008_ADMIN_REVIEW_HANDOFF_SHA256
            or current_snapshot != update_snapshot
            or completion_roles
            != FP008_ADMIN_REVIEW_COMPLETION_EVIDENCE_BY_GOAL
            or state_runtime_fields != expected_state_runtime_fields
            or continuation.canonical_json_sha256(
                state.get("artifact_work_queue")
            ) != final_runtime["artifact_work_queue_sha256"]
            or continuation.canonical_json_sha256(
                state.get("completion_boundary")
            ) != final_runtime["completion_boundary_sha256"]
            or state.get("blockers_by_goal") != {}
            or state.get("blocker_resolution_history") != []
            or state.get("pending_producer_completion_goal_id") not in {None, ""}
            or state.get("transition_history_anchor_sha256")
            != FP008_ADMIN_REVIEW_COMPLETION_EVENT_SHA256
            or state.get("validation_cutoff_at")
            != FP008_ADMIN_REVIEW_COMPLETION_OCCURRED_AT
        ):
            return False
    else:
        latest = history[-1]
        latest_runtime = latest.get("runtime_after")
        remaining = handoff.get("remaining_blockers_and_gates")
        release_gates = (
            [
                record
                for record in remaining
                if isinstance(record, dict)
                and record.get("kind") == "RELEASE_GATE"
            ]
            if isinstance(remaining, list)
            else []
        )
        progress_texts = (
            current_work.get("current_focus"),
            current_work.get("next_action"),
            handoff.get("current_epic"),
            handoff.get("next_single_action"),
        )
        action_text = current_work.get("next_action")
        runtime_work_item_id = (
            latest_runtime.get("focus_work_item_id")
            if isinstance(latest_runtime, dict)
            else None
        )
        if (
            any(
                completion_roles.get(goal_id) != evidence_roles
                for goal_id, evidence_roles
                in FP008_ADMIN_REVIEW_COMPLETION_EVIDENCE_BY_GOAL.items()
            )
            or any(
                current_work.get(field) != expected
                for field, expected
                in FP008_ADMIN_REVIEW_SUFFIX_CURRENT_WORK_SAFE_FIELDS.items()
            )
            or current_work.get("deferred_release_gate_ids")
            != FP008_ADMIN_REVIEW_DEFERRED_RELEASE_GATE_IDS
            or handoff.get("last_verification_status")
            != FP008_ADMIN_REVIEW_HANDOFF_SAFE_FIELDS[
                "last_verification_status"
            ]
            or release_gates != FP008_ADMIN_REVIEW_HANDOFF_RELEASE_GATES
            or not all(isinstance(value, str) for value in progress_texts)
            or not isinstance(action_text, str)
            or action_text != handoff.get("next_single_action")
            or any(
                token in value.casefold()
                for value in progress_texts
                for token in FP008_ADMIN_REVIEW_FORBIDDEN_FUTURE_ACTION_TOKENS
            )
            or current_work.get("status") not in {"READY", "IN_PROGRESS"}
            or not isinstance(latest_runtime, dict)
            or any(
                field not in latest_runtime
                for field in runtime_state_field_names
            )
            or state_runtime_fields
            != {
                key: latest_runtime[key]
                for key in runtime_state_field_names
            }
            or continuation.canonical_json_sha256(
                state.get("artifact_work_queue")
            ) != latest_runtime.get("artifact_work_queue_sha256")
            or continuation.canonical_json_sha256(completion_boundary)
            != latest_runtime.get("completion_boundary_sha256")
            or state.get("blockers_by_goal") != latest.get("blockers_after")
            or state.get("validation_cutoff_at") != latest.get("occurred_at")
            or (
                isinstance(latest.get("status_changes"), dict)
                and any(
                    statuses.get(goal_id) != status
                    for goal_id, status
                    in latest["status_changes"].items()
                )
            )
            or (
                isinstance(runtime_work_item_id, str)
                and runtime_work_item_id
                and (
                    current_work.get("work_item_id")
                    != runtime_work_item_id
                    or handoff.get("last_updated_by_work_item")
                    != runtime_work_item_id
                    or current_work.get("status")
                    != statuses.get(latest_runtime.get("focus_goal_id"))
                )
            )
        ):
            return False
    return True


def _fp048_archived_requirements_traceability_successor_errors(
    root: Path,
    checkpoint: dict[str, Any],
    archive: dict[str, Any],
) -> set[str]:
    """Return only the eight RTM diagnostics discharged by sealed FP048."""
    if (
        not _fp048_android_report_successor_is_declared(checkpoint)
        or _fp048_sealed_product_successor_artifacts(root, checkpoint) is None
    ):
        return set()
    receipt = _load_exact_json(
        root,
        FP048_ANDROID_REPORT_COMPLETION_PATH,
    )
    downstream = (
        receipt.get("downstream_consumer_bindings")
        if isinstance(receipt, dict)
        else None
    )
    rtm_bindings = (
        [
            binding
            for binding in downstream
            if isinstance(binding, dict)
            and binding.get("role") == "REQUIREMENTS_TRACEABILITY"
        ]
        if isinstance(downstream, list)
        else []
    )
    live_path = _exact_repo_file(
        root,
        FP048_REQUIREMENTS_TRACEABILITY_BINDING["path"],
    )
    if (
        rtm_bindings != [FP048_REQUIREMENTS_TRACEABILITY_BINDING]
        or live_path is None
        or continuation.sha256_file(live_path)
        != FP048_REQUIREMENTS_TRACEABILITY_BINDING["sha256"]
        or live_path.stat().st_size
        != FP048_REQUIREMENTS_TRACEABILITY_BYTE_COUNT
    ):
        return set()

    return _sealed_archived_requirements_traceability_errors(root, archive)


def _fp046_archived_requirements_traceability_successor_errors(
    root: Path,
    checkpoint: dict[str, Any],
    archive: dict[str, Any],
) -> set[str]:
    """Discharge only sealed RTM diagnostics covered by exact R014."""
    authority_errors, artifact_bindings, _ = (
        validate_fp046_r014_successor_authority(root, checkpoint)
    )
    expected = FP046_R014_ARTIFACT_BINDING_BY_ROLE[
        "REQUIREMENTS_TRACEABILITY"
    ]
    if (
        authority_errors
        or artifact_bindings.get(expected["path"]) != expected["sha256"]
    ):
        return set()
    return _sealed_archived_requirements_traceability_errors(root, archive)


def _fp011_predecessor_goal_ids(
    archive: dict[str, Any],
) -> list[str]:
    state = archive.get("goal_execution")
    inventory = (
        state.get("dynamic_goal_inventory")
        if isinstance(state, dict)
        else None
    )
    if not isinstance(inventory, dict) or FP011_GOAL_ID not in inventory:
        return []
    result: list[str] = []
    seen = {FP011_GOAL_ID}
    cursor = FP011_GOAL_ID
    while True:
        record = inventory.get(cursor)
        predecessor = (
            record.get("predecessor_goal_id")
            if isinstance(record, dict)
            else None
        )
        if predecessor == "":
            return result
        if (
            not isinstance(predecessor, str)
            or predecessor in seen
            or predecessor not in inventory
        ):
            return []
        result.append(predecessor)
        seen.add(predecessor)
        cursor = predecessor


def _historical_changed_artifacts(
    root: Path,
    archive: dict[str, Any],
    *,
    goal_id: str,
) -> list[dict[str, Any]] | None:
    if goal_id not in _fp011_predecessor_goal_ids(archive):
        return None
    state = archive.get("goal_execution")
    completion = (
        state.get("completion_evidence_by_goal")
        if isinstance(state, dict)
        else None
    )
    roles = completion.get(goal_id) if isinstance(completion, dict) else None
    if (
        not isinstance(roles, list)
        or len(roles) != 1
        or not isinstance(roles[0], str)
    ):
        return None
    receipt_binding = _binding_by_role(archive, roles[0])
    if (
        receipt_binding is None
        or not _sha256_binding_matches(root, receipt_binding)
    ):
        return None
    receipt = _load_exact_json(root, receipt_binding.get("path"))
    if (
        receipt is None
        or receipt.get("document_id") != receipt_binding.get("document_id")
        or receipt.get("target_goal_id") != goal_id
        or receipt.get("evidence_type") != "WORK_ITEM_EXECUTION_RECEIPT"
        or receipt.get("status") != "ACCEPTED"
        or receipt.get("result") != "PASS"
    ):
        return None
    result_evidence = receipt.get("result_evidence")
    implementation_bindings = [
        binding
        for binding in (
            result_evidence if isinstance(result_evidence, list) else []
        )
        if (
            isinstance(binding, dict)
            and binding.get("kind") == "IMPLEMENTATION_RECORD"
        )
    ]
    if len(implementation_bindings) != 1:
        return None
    implementation_binding = implementation_bindings[0]
    if not _sha256_binding_matches(root, implementation_binding):
        return None
    implementation = _load_exact_json(
        root,
        implementation_binding.get("path"),
    )
    changed = (
        implementation.get("changed_artifacts")
        if isinstance(implementation, dict)
        else None
    )
    if (
        implementation is None
        or implementation.get("goal_id") != goal_id
        or implementation.get("kind") != "IMPLEMENTATION_RECORD"
        or implementation.get("status") != "PASS"
        or not isinstance(changed, list)
        or any(not isinstance(row, dict) for row in changed)
        or (
            implementation.get("implementation_content_set_sha256")
            is not None
            and implementation.get("implementation_content_set_sha256")
            != _implementation_content_set_sha256(changed)
        )
    ):
        return None
    if (
        len(
            {
                row.get("path")
                for row in changed
                if isinstance(row.get("path"), str)
            }
        )
        != len(changed)
        or any(
            not isinstance(row.get("path"), str)
            or not isinstance(row.get("after_sha256"), str)
            or not continuation.SHA256_RE.fullmatch(
                row["after_sha256"]
            )
            or (
                row.get("before_sha256") is not None
                and (
                    not isinstance(row.get("before_sha256"), str)
                    or not continuation.SHA256_RE.fullmatch(
                        row["before_sha256"]
                    )
                    or row["before_sha256"] == row["after_sha256"]
                )
            )
            for row in changed
        )
    ):
        return None
    return changed


def _historical_changed_artifact(
    root: Path,
    archive: dict[str, Any],
    *,
    goal_id: str,
    artifact_index: int,
) -> tuple[str, str] | None:
    changed = _historical_changed_artifacts(
        root,
        archive,
        goal_id=goal_id,
    )
    if changed is None or artifact_index >= len(changed):
        return None
    record = changed[artifact_index]
    return record["path"], record["after_sha256"]


def _historical_artifact_lineage_head(
    root: Path,
    archive: dict[str, Any],
    relative: str,
) -> str | None:
    head: str | None = None
    for goal_id in reversed(_fp011_predecessor_goal_ids(archive)):
        changed = _historical_changed_artifacts(
            root,
            archive,
            goal_id=goal_id,
        )
        if changed is None:
            return None
        records = [
            record
            for record in changed
            if record.get("path") == relative
        ]
        if not records:
            continue
        if len(records) != 1:
            return None
        record = records[0]
        if (
            head is not None
            and record.get("before_sha256") != head
        ):
            return None
        head = record["after_sha256"]
    return head


def _fp011_execution_session_matches(
    state: dict[str, Any],
    receipt: dict[str, Any],
) -> bool:
    history = state.get("transition_history")
    if not isinstance(history, list):
        return False
    sessions = [
        event
        for event in history
        if (
            isinstance(event, dict)
            and event.get("event_type")
            in {"GOAL_STARTED", "WORK_SESSION_RESUMED"}
            and event.get("subject_goal_id") == FP011_GOAL_ID
        )
    ]
    starts = [
        event
        for event in sessions
        if event.get("event_type") == "GOAL_STARTED"
    ]
    if not sessions or len(starts) != 1:
        return False
    latest = sessions[-1]
    session_binding = receipt.get("execution_session_event")
    expected_session = {
        "sequence": latest.get("sequence"),
        "event_id": latest.get("event_id"),
        "event_type": latest.get("event_type"),
        "event_sha256": latest.get("event_sha256"),
    }
    return bool(
        isinstance(session_binding, dict)
        and all(
            session_binding.get(key) == expected
            for key, expected in expected_session.items()
        )
        and receipt.get("execution_start_event_sha256")
        == starts[0].get("event_sha256")
    )


def _fp011_bound_product_artifacts(
    root: Path,
    checkpoint: dict[str, Any],
    archive: dict[str, Any],
    *,
    require_live_after: bool,
) -> dict[str, tuple[str, str]]:
    state = checkpoint.get("goal_execution")
    archived_state = archive.get("goal_execution")
    if not isinstance(state, dict) or not isinstance(archived_state, dict):
        return {}
    status_by_goal = state.get("status_by_goal")
    status = (
        status_by_goal.get(FP011_GOAL_ID)
        if isinstance(status_by_goal, dict)
        else None
    )
    if status not in {"IN_PROGRESS", "COMPLETE_AT_TARGET"}:
        return {}
    imported = state.get("imported_predecessor_goal_bindings")
    archived_inventory = archived_state.get("dynamic_goal_inventory")
    imported_fp011 = (
        imported.get(FP011_GOAL_ID)
        if isinstance(imported, dict)
        else None
    )
    archived_fp011 = (
        archived_inventory.get(FP011_GOAL_ID)
        if isinstance(archived_inventory, dict)
        else None
    )
    if (
        not isinstance(imported_fp011, dict)
        or not isinstance(archived_fp011, dict)
        or imported_fp011.get("path") != FP011_GOAL_PATH
        or imported_fp011.get("sha256") != FP011_GOAL_SHA256
        or archived_fp011.get("path") != FP011_GOAL_PATH
        or archived_fp011.get("sha256") != FP011_GOAL_SHA256
    ):
        return {}

    receipt_path = _exact_repo_file(root, FP011_COMPLETION_PATH)
    receipt = _load_exact_json(root, FP011_COMPLETION_PATH)
    if receipt_path is None or receipt is None:
        return {}
    expected_receipt = {
        "schema_version": "1.0",
        "document_id": FP011_COMPLETION_DOCUMENT_ID,
        "evidence_type": "WORK_ITEM_EXECUTION_RECEIPT",
        "status": "ACCEPTED",
        "result": "PASS",
        "target_goal_id": FP011_GOAL_ID,
        "target_goal_content_sha256": FP011_GOAL_SHA256,
        "work_item_id": FP011_WORK_ITEM_ID,
        "source_policy_ids": ["FP-011"],
        "gap_ids": ["GAP-020"],
        "target_completion_level": (
            "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
        ),
        "completion_boundary": FP011_COMPLETION_BOUNDARY,
    }
    if any(
        receipt.get(key) != expected
        for key, expected in expected_receipt.items()
    ) or not _fp011_execution_session_matches(state, receipt):
        return {}
    start_gate_binding = receipt.get("implementation_start_gate_binding")
    if (
        not isinstance(start_gate_binding, dict)
        or start_gate_binding.get("document_id")
        != FP011_START_GATE_DOCUMENT_ID
        or start_gate_binding.get("file_sha256") != FP011_START_GATE_SHA256
        or not _sha256_binding_matches(
            root,
            start_gate_binding,
            expected_path=FP011_START_GATE_PATH,
        )
    ):
        return {}

    raw_bindings = checkpoint.get("canonical_bindings")
    matching_completion_bindings = [
        binding
        for binding in (
            raw_bindings if isinstance(raw_bindings, list) else []
        )
        if (
            isinstance(binding, dict)
            and binding.get("role") == FP011_COMPLETION_ROLE
        )
    ]
    if len(matching_completion_bindings) > 1:
        return {}
    completion_binding = (
        matching_completion_bindings[0]
        if matching_completion_bindings
        else None
    )
    completion_roles = state.get("completion_evidence_by_goal")
    if completion_binding is not None:
        if (
            completion_binding.get("path") != FP011_COMPLETION_PATH
            or completion_binding.get("document_id")
            != receipt.get("document_id")
            or not _sha256_binding_matches(
                root,
                completion_binding,
                expected_path=FP011_COMPLETION_PATH,
            )
        ):
            return {}
    if status == "COMPLETE_AT_TARGET":
        if (
            completion_binding is None
            or not isinstance(completion_roles, dict)
            or completion_roles.get(FP011_GOAL_ID)
            != [FP011_COMPLETION_ROLE]
        ):
            return {}

    result_evidence = receipt.get("result_evidence")
    expected_kinds = list(FP011_RESULT_PATH_BY_KIND)
    if (
        not isinstance(result_evidence, list)
        or len(result_evidence) != len(expected_kinds)
        or any(not isinstance(binding, dict) for binding in result_evidence)
        or [
            binding.get("kind")
            for binding in result_evidence
        ]
        != expected_kinds
    ):
        return {}
    by_kind: dict[str, dict[str, Any]] = {}
    result_documents: dict[str, dict[str, Any]] = {}
    for binding in result_evidence:
        kind = binding.get("kind")
        expected_path = FP011_RESULT_PATH_BY_KIND.get(str(kind))
        if (
            not isinstance(kind, str)
            or expected_path is None
            or not _sha256_binding_matches(
                root,
                binding,
                expected_path=expected_path,
            )
        ):
            return {}
        by_kind[kind] = binding
        result_document = _load_exact_json(root, expected_path)
        if (
            result_document is None
            or result_document.get("schema_version") != "1.0"
            or result_document.get("document_id")
            != FP011_RESULT_DOCUMENT_ID_BY_KIND[kind]
            or result_document.get("goal_id") != FP011_GOAL_ID
            or result_document.get("kind") != kind
            or result_document.get("status") != "PASS"
        ):
            return {}
        result_documents[kind] = result_document
    review_binding = receipt.get("reviewer_provenance")
    if not _sha256_binding_matches(
        root,
        review_binding,
        expected_path=FP011_REVIEW_PATH,
    ):
        return {}
    review = _load_exact_json(root, FP011_REVIEW_PATH)
    if (
        review is None
        or review.get("schema_version") != "1.0"
        or review.get("document_id") != FP011_REVIEW_DOCUMENT_ID
        or review.get("evidence_type") != "INDEPENDENT_INTERNAL_REVIEW"
        or review.get("goal_id") != FP011_GOAL_ID
        or review.get("status") != "PASS"
        or review.get("reviewed_result_sha256_by_kind")
        != {
            kind: by_kind[kind].get("sha256")
            for kind in expected_kinds
        }
    ):
        return {}

    implementation = result_documents["IMPLEMENTATION_RECORD"]
    changed = (
        implementation.get("changed_artifacts")
        if isinstance(implementation, dict)
        else None
    )
    if (
        implementation is None
        or implementation.get("goal_id") != FP011_GOAL_ID
        or implementation.get("kind") != "IMPLEMENTATION_RECORD"
        or implementation.get("status") != "PASS"
        or not isinstance(changed, list)
        or not changed
        or any(not isinstance(row, dict) for row in changed)
        or implementation.get("implementation_content_set_sha256")
        != _implementation_content_set_sha256(changed)
    ):
        return {}
    result: dict[str, tuple[str, str]] = {}
    seen_paths: set[str] = set()
    for record in changed:
        relative = record.get("path")
        after_sha256 = record.get("after_sha256")
        before_sha256 = record.get("before_sha256")
        path = _exact_repo_file(root, relative)
        snapshot_successor = (
            _fp047_start_snapshot_successor(
                root,
                checkpoint,
                relative,
                after_sha256,
            )
            if require_live_after
            and isinstance(relative, str)
            and isinstance(after_sha256, str)
            else None
        )
        if (
            not isinstance(relative, str)
            or relative in seen_paths
            or path is None
            or not isinstance(after_sha256, str)
            or not continuation.SHA256_RE.fullmatch(after_sha256)
            or (
                require_live_after
                and continuation.sha256_file(path) != after_sha256
                and snapshot_successor is None
            )
            or (
                before_sha256 is not None
                and (
                    not isinstance(before_sha256, str)
                    or not continuation.SHA256_RE.fullmatch(before_sha256)
                    or before_sha256 == after_sha256
                )
            )
            or (
                record.get("change_kind")
                != ("MODIFIED" if before_sha256 is not None else "ADDED")
            )
        ):
            return {}
        seen_paths.add(relative)
        if (
            isinstance(before_sha256, str)
            and (
                relative.startswith(FP011_PRODUCT_PATH_PREFIXES)
                or relative in FP011_PRODUCT_EXACT_PATHS
            )
        ):
            result[relative] = before_sha256, after_sha256
    return result


def _fp011_successor_product_artifacts(
    root: Path,
    checkpoint: dict[str, Any],
    archive: dict[str, Any],
) -> dict[str, tuple[str, str]]:
    return _fp011_bound_product_artifacts(
        root,
        checkpoint,
        archive,
        require_live_after=True,
    )


def _fp013_execution_session_matches(
    state: dict[str, Any],
    receipt: dict[str, Any],
) -> bool:
    history = state.get("transition_history")
    if not isinstance(history, list):
        return False
    sessions = [
        event
        for event in history
        if (
            isinstance(event, dict)
            and event.get("event_type")
            in {"GOAL_STARTED", "WORK_SESSION_RESUMED"}
            and event.get("subject_goal_id") == FP013_GOAL_ID
        )
    ]
    starts = [
        event
        for event in sessions
        if event.get("event_type") == "GOAL_STARTED"
    ]
    if len(sessions) != 1 or len(starts) != 1:
        return False
    start = starts[0]
    expected_session = {
        "sequence": start.get("sequence"),
        "event_id": start.get("event_id"),
        "event_type": start.get("event_type"),
        "event_sha256": start.get("event_sha256"),
    }
    start_gate_binding = receipt.get("implementation_start_gate_binding")
    return bool(
        receipt.get("execution_session_event") == expected_session
        and receipt.get("execution_start_event_sha256")
        == FP013_START_EVENT_SHA256
        and start.get("event_id") == FP013_START_EVENT_ID
        and start.get("event_sha256") == FP013_START_EVENT_SHA256
        and continuation.event_sha256(start) == FP013_START_EVENT_SHA256
        and start.get("implementation_start_gate_binding")
        == start_gate_binding
    )


def _fp013_completion_transition_matches(
    state: dict[str, Any],
    completion_binding: dict[str, Any],
) -> bool:
    history = state.get("transition_history")
    if not isinstance(history, list):
        return False
    completions = [
        (index, event)
        for index, event in enumerate(history)
        if (
            isinstance(event, dict)
            and event.get("event_type") == "GOAL_COMPLETED"
            and event.get("subject_goal_id") == FP013_GOAL_ID
        )
    ]
    if len(completions) != 1:
        return False
    index, completion = completions[0]
    if index == 0 or not isinstance(history[index - 1], dict):
        return False
    canonical_update = history[index - 1]
    binding_projection = {
        key: completion_binding.get(key)
        for key in ("role", "document_id", "path", "file_sha256")
    }
    canonical_snapshot = canonical_update.get(
        "canonical_binding_snapshot_after"
    )
    expected_roles = [
        "IMPLEMENTATION_BACKLOG",
        "IMPLEMENTATION_GAP",
        FP013_COMPLETION_ROLE,
    ]
    return bool(
        canonical_update.get("sequence") == 10
        and canonical_update.get("event_id")
        == FP013_CANONICAL_UPDATE_EVENT_ID
        and canonical_update.get("event_type")
        == "CANONICAL_BINDINGS_UPDATED"
        and canonical_update.get("produced_by_goal_id") == FP013_GOAL_ID
        and canonical_update.get("changed_binding_roles") == expected_roles
        and canonical_update.get("evidence_refs") == expected_roles
        and canonical_update.get("producer_completion_receipt_binding")
        == binding_projection
        and isinstance(canonical_snapshot, dict)
        and canonical_snapshot.get(FP013_COMPLETION_ROLE)
        == binding_projection
        and canonical_update.get("event_sha256")
        == FP013_CANONICAL_UPDATE_EVENT_SHA256
        and continuation.event_sha256(canonical_update)
        == FP013_CANONICAL_UPDATE_EVENT_SHA256
        and completion.get("sequence") == 11
        and completion.get("event_id") == FP013_COMPLETION_EVENT_ID
        and completion.get("from_status") == "IN_PROGRESS"
        and completion.get("to_status") == "COMPLETE_AT_TARGET"
        and completion.get("status_changes")
        == {FP013_GOAL_ID: "COMPLETE_AT_TARGET"}
        and completion.get("evidence_refs") == [FP013_COMPLETION_ROLE]
        and completion.get("completion_evidence_bindings")
        == {FP013_COMPLETION_ROLE: binding_projection}
        and completion.get("completion_receipt_binding")
        == binding_projection
        and completion.get("canonical_update_event_sha256")
        == FP013_CANONICAL_UPDATE_EVENT_SHA256
        and completion.get("previous_event_sha256")
        == FP013_CANONICAL_UPDATE_EVENT_SHA256
        and completion.get("event_sha256")
        == FP013_COMPLETION_EVENT_SHA256
        and continuation.event_sha256(completion)
        == FP013_COMPLETION_EVENT_SHA256
    )


def _fp015_execution_session_matches(
    state: dict[str, Any],
    receipt: dict[str, Any],
) -> bool:
    history = state.get("transition_history")
    if not isinstance(history, list):
        return False
    sessions = [
        event
        for event in history
        if (
            isinstance(event, dict)
            and event.get("event_type")
            in {"GOAL_STARTED", "WORK_SESSION_RESUMED"}
            and event.get("subject_goal_id") == FP015_GOAL_ID
        )
    ]
    starts = [
        event
        for event in sessions
        if event.get("event_type") == "GOAL_STARTED"
    ]
    if len(sessions) != 1 or len(starts) != 1:
        return False
    start = starts[0]
    expected_session = {
        "sequence": start.get("sequence"),
        "event_id": start.get("event_id"),
        "event_type": start.get("event_type"),
        "event_sha256": start.get("event_sha256"),
    }
    start_gate_binding = receipt.get("implementation_start_gate_binding")
    return bool(
        receipt.get("execution_session_event") == expected_session
        and receipt.get("execution_start_event_sha256")
        == FP015_START_EVENT_SHA256
        and start.get("sequence") == 14
        and start.get("event_id") == FP015_START_EVENT_ID
        and start.get("event_sha256") == FP015_START_EVENT_SHA256
        and continuation.event_sha256(start) == FP015_START_EVENT_SHA256
        and start.get("implementation_start_gate_binding")
        == start_gate_binding
    )


def _fp015_completion_transition_matches(
    state: dict[str, Any],
    completion_binding: dict[str, Any],
) -> bool:
    history = state.get("transition_history")
    if not isinstance(history, list):
        return False
    completions = [
        (index, event)
        for index, event in enumerate(history)
        if (
            isinstance(event, dict)
            and event.get("event_type") == "GOAL_COMPLETED"
            and event.get("subject_goal_id") == FP015_GOAL_ID
        )
    ]
    if len(completions) != 1:
        return False
    index, completion = completions[0]
    if index == 0 or not isinstance(history[index - 1], dict):
        return False
    canonical_update = history[index - 1]
    binding_projection = {
        key: completion_binding.get(key)
        for key in ("role", "document_id", "path", "file_sha256")
    }
    canonical_snapshot = canonical_update.get(
        "canonical_binding_snapshot_after"
    )
    expected_roles = list(FP015_CANONICAL_BINDINGS)
    return bool(
        binding_projection
        == FP015_CANONICAL_BINDINGS[FP015_COMPLETION_ROLE]
        and canonical_update.get("sequence") == 15
        and canonical_update.get("event_id")
        == FP015_CANONICAL_UPDATE_EVENT_ID
        and canonical_update.get("event_type")
        == "CANONICAL_BINDINGS_UPDATED"
        and canonical_update.get("previous_event_sha256")
        == FP015_START_EVENT_SHA256
        and canonical_update.get("from_status") == "IN_PROGRESS"
        and canonical_update.get("to_status") == "IN_PROGRESS"
        and canonical_update.get("produced_by_goal_id") == FP015_GOAL_ID
        and canonical_update.get("changed_binding_roles") == expected_roles
        and canonical_update.get("evidence_refs") == expected_roles
        and canonical_update.get("producer_completion_receipt_binding")
        == binding_projection
        and isinstance(canonical_snapshot, dict)
        and all(
            canonical_snapshot.get(role) == binding
            for role, binding in FP015_CANONICAL_BINDINGS.items()
        )
        and canonical_update.get("event_sha256")
        == FP015_CANONICAL_UPDATE_EVENT_SHA256
        and continuation.event_sha256(canonical_update)
        == FP015_CANONICAL_UPDATE_EVENT_SHA256
        and completion.get("sequence") == 16
        and completion.get("event_id") == FP015_COMPLETION_EVENT_ID
        and completion.get("event_type") == "GOAL_COMPLETED"
        and completion.get("from_status") == "IN_PROGRESS"
        and completion.get("to_status") == "COMPLETE_AT_TARGET"
        and completion.get("status_changes")
        == {FP015_GOAL_ID: "COMPLETE_AT_TARGET"}
        and completion.get("evidence_refs") == [FP015_COMPLETION_ROLE]
        and completion.get("completion_evidence_bindings")
        == {FP015_COMPLETION_ROLE: binding_projection}
        and completion.get("completion_receipt_binding")
        == binding_projection
        and completion.get("completion_evidence_by_goal_after", {}).get(
            FP015_GOAL_ID
        )
        == [FP015_COMPLETION_ROLE]
        and completion.get("canonical_update_event_sha256")
        == FP015_CANONICAL_UPDATE_EVENT_SHA256
        and completion.get("previous_event_sha256")
        == FP015_CANONICAL_UPDATE_EVENT_SHA256
        and completion.get("event_sha256") == FP015_COMPLETION_EVENT_SHA256
        and continuation.event_sha256(completion)
        == FP015_COMPLETION_EVENT_SHA256
    )


def _fp013_start_repository_state_snapshot(root, start_gate_binding):
    import hashlib as _hashlib
    import json as _json

    output_name = "19-REPOSITORY_STATE.log"
    output_relative_path = (
        FP013_START_GATE_PATH.rsplit("/", 1)[0] + "/" + output_name
    )
    start_gate_path = root / FP013_START_GATE_PATH
    output_path = root / output_relative_path
    if not start_gate_path.is_file() or not output_path.is_file():
        return None
    try:
        start_gate_bytes = start_gate_path.read_bytes()
        start_gate = _json.loads(start_gate_bytes.decode("utf-8"))
        output_bytes = output_path.read_bytes()
        output = _json.loads(output_bytes.decode("utf-8"))
    except (OSError, UnicodeDecodeError, _json.JSONDecodeError):
        return None
    if (
        not isinstance(start_gate_binding, dict)
        or start_gate_binding.get("document_id")
        != FP013_START_GATE_DOCUMENT_ID
        or start_gate_binding.get("file_sha256") != FP013_START_GATE_SHA256
        or _hashlib.sha256(start_gate_bytes).hexdigest()
        != FP013_START_GATE_SHA256
        or not isinstance(start_gate, dict)
        or not isinstance(output, dict)
    ):
        return None
    output_sha256 = _hashlib.sha256(output_bytes).hexdigest()

    def string_leaves(value):
        if isinstance(value, str):
            return [value]
        if isinstance(value, dict):
            leaves = []
            for key, child in value.items():
                if isinstance(key, str):
                    leaves.append(key)
                leaves.extend(string_leaves(child))
            return leaves
        if isinstance(value, list):
            leaves = []
            for child in value:
                leaves.extend(string_leaves(child))
            return leaves
        return []

    def has_bound_output(value):
        if isinstance(value, dict):
            direct_values = [
                child for child in value.values() if isinstance(child, str)
            ]
            direct_path = (
                output_relative_path in direct_values or output_name in direct_values
            )
            keyed_path = output_relative_path in value or output_name in value
            if direct_path or keyed_path:
                leaves = string_leaves(value)
                if output_sha256 in leaves:
                    return True
            return any(has_bound_output(child) for child in value.values())
        if isinstance(value, list):
            return any(has_bound_output(child) for child in value)
        return False

    if not has_bound_output(start_gate):
        return None
    if (
        output.get("evidence_type") != "GATE_REPOSITORY_STATE"
        or output.get("gate_event_id") != FP013_START_EVENT_ID
    ):
        return None
    repository = output.get("repository")
    if (
        not isinstance(repository, dict)
        or repository.get("root") != "."
        or repository.get("object_format") not in {"sha1", "sha256"}
    ):
        return None
    head_commit = repository.get("head_commit")
    expected_head_length = (
        40 if repository["object_format"] == "sha1" else 64
    )
    if (
        not isinstance(head_commit, str)
        or len(head_commit) != expected_head_length
        or any(character not in "0123456789abcdef" for character in head_commit)
    ):
        return None
    dirty_snapshot = output.get("dirty_snapshot")
    if not isinstance(dirty_snapshot, dict):
        return None
    paths = dirty_snapshot.get("paths")
    if (
        not isinstance(paths, list)
        or dirty_snapshot.get("dirty_path_count") != len(paths)
    ):
        return None
    snapshot_by_path = {}
    for entry in paths:
        if not isinstance(entry, dict):
            return None
        artifact_path = entry.get("path")
        worktree = entry.get("worktree")
        if (
            not isinstance(artifact_path, str)
            or not artifact_path
            or artifact_path in snapshot_by_path
            or not isinstance(worktree, dict)
        ):
            return None
        snapshot_by_path[artifact_path] = worktree
    return snapshot_by_path, head_commit


def _fp013_start_head_path_sha256(
    root: Path,
    head_commit: str,
    relative: str,
) -> tuple[bool, str | None] | None:
    import hashlib as _hashlib

    if (
        not relative
        or relative.startswith("/")
        or ":" in relative
        or any(part in {"", ".", ".."} for part in relative.split("/"))
    ):
        return None
    try:
        continuation._v23_utility._reject_gate_git_environment_overrides()
        commit_check = _run_git_bytes(
            root,
            ["cat-file", "-e", f"{head_commit}^{{commit}}"],
            accepted_returncodes=(0,),
        )
        if commit_check.returncode != 0:
            return None
        tree = _run_git_bytes(
            root,
            ["ls-tree", "-z", head_commit, "--", relative],
            accepted_returncodes=(0,),
        )
        if tree.returncode != 0:
            return None
        if not tree.stdout:
            return False, None
        entries = tree.stdout.split(b"\0")
        if len(entries) != 2 or entries[1] != b"":
            return None
        metadata_and_path = entries[0].split(b"\t", 1)
        if (
            len(metadata_and_path) != 2
            or metadata_and_path[1] != relative.encode("utf-8")
        ):
            return None
        metadata = metadata_and_path[0].split(b" ")
        if (
            len(metadata) != 3
            or metadata[1] != b"blob"
            or len(metadata[2]) not in {40, 64}
            or any(
                character not in b"0123456789abcdef"
                for character in metadata[2]
            )
        ):
            return None
        object_id = metadata[2].decode("ascii")
        blob = _run_git_bytes(
            root,
            ["cat-file", "blob", object_id],
            accepted_returncodes=(0,),
        )
        if blob.returncode != 0:
            return None
    except (OSError, ValueError, subprocess.SubprocessError):
        return None
    return True, _hashlib.sha256(blob.stdout).hexdigest()


def _fp015_start_repository_state_snapshot(root, start_gate_binding):
    import hashlib as _hashlib
    import json as _json

    output_name = "19-REPOSITORY_STATE.log"
    output_relative_path = (
        FP015_START_GATE_PATH.rsplit("/", 1)[0] + "/" + output_name
    )
    start_gate_path = root / FP015_START_GATE_PATH
    output_path = root / output_relative_path
    if not start_gate_path.is_file() or not output_path.is_file():
        return None
    try:
        start_gate_bytes = start_gate_path.read_bytes()
        start_gate = _json.loads(start_gate_bytes.decode("utf-8"))
        output_bytes = output_path.read_bytes()
        output = _json.loads(output_bytes.decode("utf-8"))
    except (OSError, UnicodeDecodeError, _json.JSONDecodeError):
        return None
    expected_binding = {
        "document_id": FP015_START_GATE_DOCUMENT_ID,
        "path": FP015_START_GATE_PATH,
        "file_sha256": FP015_START_GATE_SHA256,
    }
    if (
        start_gate_binding != expected_binding
        or _hashlib.sha256(start_gate_bytes).hexdigest()
        != FP015_START_GATE_SHA256
        or not isinstance(start_gate, dict)
        or not isinstance(output, dict)
    ):
        return None
    output_sha256 = _hashlib.sha256(output_bytes).hexdigest()

    def string_leaves(value):
        if isinstance(value, str):
            return [value]
        if isinstance(value, dict):
            leaves = []
            for key, child in value.items():
                if isinstance(key, str):
                    leaves.append(key)
                leaves.extend(string_leaves(child))
            return leaves
        if isinstance(value, list):
            leaves = []
            for child in value:
                leaves.extend(string_leaves(child))
            return leaves
        return []

    def has_bound_output(value):
        if isinstance(value, dict):
            direct_values = [
                child for child in value.values() if isinstance(child, str)
            ]
            direct_path = (
                output_relative_path in direct_values or output_name in direct_values
            )
            keyed_path = output_relative_path in value or output_name in value
            if direct_path or keyed_path:
                if output_sha256 in string_leaves(value):
                    return True
            return any(has_bound_output(child) for child in value.values())
        if isinstance(value, list):
            return any(has_bound_output(child) for child in value)
        return False

    if not has_bound_output(start_gate):
        return None
    if (
        output.get("evidence_type") != "GATE_REPOSITORY_STATE"
        or output.get("gate_event_id") != FP015_START_EVENT_ID
    ):
        return None
    repository = output.get("repository")
    if (
        not isinstance(repository, dict)
        or repository.get("root") != "."
        or repository.get("object_format") not in {"sha1", "sha256"}
    ):
        return None
    head_commit = repository.get("head_commit")
    expected_head_length = (
        40 if repository["object_format"] == "sha1" else 64
    )
    if (
        not isinstance(head_commit, str)
        or len(head_commit) != expected_head_length
        or any(character not in "0123456789abcdef" for character in head_commit)
    ):
        return None
    dirty_snapshot = output.get("dirty_snapshot")
    if not isinstance(dirty_snapshot, dict):
        return None
    paths = dirty_snapshot.get("paths")
    if (
        not isinstance(paths, list)
        or dirty_snapshot.get("dirty_path_count") != len(paths)
    ):
        return None
    snapshot_by_path = {}
    for entry in paths:
        if not isinstance(entry, dict):
            return None
        artifact_path = entry.get("path")
        worktree = entry.get("worktree")
        if (
            not isinstance(artifact_path, str)
            or not artifact_path
            or artifact_path in snapshot_by_path
            or not isinstance(worktree, dict)
        ):
            return None
        snapshot_by_path[artifact_path] = worktree
    return snapshot_by_path, head_commit


def _fp013_successor_product_artifacts(
    root: Path,
    checkpoint: dict[str, Any],
    *,
    require_live_after: bool = True,
) -> dict[str, tuple[str, str]]:
    state = checkpoint.get("goal_execution")
    if not isinstance(state, dict):
        return {}
    status_by_goal = state.get("status_by_goal")
    inventory = state.get("dynamic_goal_inventory")
    fp013 = (
        inventory.get(FP013_GOAL_ID)
        if isinstance(inventory, dict)
        else None
    )
    goal_path = _exact_repo_file(root, FP013_GOAL_PATH)
    if (
        not isinstance(status_by_goal, dict)
        or status_by_goal.get(FP013_GOAL_ID) != "COMPLETE_AT_TARGET"
        or not isinstance(fp013, dict)
        or fp013.get("goal_id") != FP013_GOAL_ID
        or fp013.get("path") != FP013_GOAL_PATH
        or fp013.get("sha256") != FP013_GOAL_SHA256
        or goal_path is None
        or continuation.sha256_file(goal_path) != FP013_GOAL_SHA256
    ):
        return {}

    completion_binding = _binding_by_role(
        checkpoint,
        FP013_COMPLETION_ROLE,
    )
    completion_roles = state.get("completion_evidence_by_goal")
    if (
        completion_binding is None
        or completion_binding.get("document_id")
        != FP013_COMPLETION_DOCUMENT_ID
        or completion_binding.get("file_sha256")
        != FP013_COMPLETION_SHA256
        or not _sha256_binding_matches(
            root,
            completion_binding,
            expected_path=FP013_COMPLETION_PATH,
        )
        or not isinstance(completion_roles, dict)
        or completion_roles.get(FP013_GOAL_ID)
        != [FP013_COMPLETION_ROLE]
        or not _fp013_completion_transition_matches(
            state,
            completion_binding,
        )
    ):
        return {}

    receipt = _load_exact_json(root, FP013_COMPLETION_PATH)
    expected_receipt = {
        "schema_version": "1.0",
        "document_id": FP013_COMPLETION_DOCUMENT_ID,
        "evidence_type": "WORK_ITEM_EXECUTION_RECEIPT",
        "status": "ACCEPTED",
        "result": "PASS",
        "target_goal_id": FP013_GOAL_ID,
        "target_goal_content_sha256": FP013_GOAL_SHA256,
        "work_item_id": FP013_WORK_ITEM_ID,
        "source_policy_ids": ["FP-013"],
        "gap_ids": ["GAP-022"],
        "target_completion_level": (
            "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
        ),
        "completion_boundary": FP013_COMPLETION_BOUNDARY,
    }
    if (
        receipt is None
        or any(
            receipt.get(key) != expected
            for key, expected in expected_receipt.items()
        )
        or not _fp013_execution_session_matches(state, receipt)
    ):
        return {}
    start_gate_binding = receipt.get("implementation_start_gate_binding")
    if (
        not isinstance(start_gate_binding, dict)
        or start_gate_binding.get("document_id")
        != FP013_START_GATE_DOCUMENT_ID
        or start_gate_binding.get("file_sha256")
        != FP013_START_GATE_SHA256
        or not _sha256_binding_matches(
            root,
            start_gate_binding,
            expected_path=FP013_START_GATE_PATH,
        )
    ):
        return {}

    result_evidence = receipt.get("result_evidence")
    expected_kinds = list(FP013_RESULT_PATH_BY_KIND)
    if (
        not isinstance(result_evidence, list)
        or len(result_evidence) != len(expected_kinds)
        or any(not isinstance(binding, dict) for binding in result_evidence)
        or [binding.get("kind") for binding in result_evidence]
        != expected_kinds
    ):
        return {}
    by_kind: dict[str, dict[str, Any]] = {}
    result_documents: dict[str, dict[str, Any]] = {}
    for binding in result_evidence:
        kind = binding.get("kind")
        expected_path = FP013_RESULT_PATH_BY_KIND.get(str(kind))
        expected_sha256 = FP013_RESULT_SHA256_BY_KIND.get(str(kind))
        if (
            not isinstance(kind, str)
            or expected_path is None
            or expected_sha256 is None
            or binding.get("sha256") != expected_sha256
            or not _sha256_binding_matches(
                root,
                binding,
                expected_path=expected_path,
            )
        ):
            return {}
        document = _load_exact_json(root, expected_path)
        if (
            document is None
            or document.get("schema_version") != "1.0"
            or document.get("document_id")
            != FP013_RESULT_DOCUMENT_ID_BY_KIND[kind]
            or document.get("goal_id") != FP013_GOAL_ID
            or document.get("kind") != kind
            or document.get("status") != "PASS"
        ):
            return {}
        by_kind[kind] = binding
        result_documents[kind] = document

    review_binding = receipt.get("reviewer_provenance")
    review = _load_exact_json(root, FP013_REVIEW_PATH)
    reviewer = receipt.get("reviewer")
    if (
        not isinstance(review_binding, dict)
        or review_binding.get("sha256") != FP013_REVIEW_SHA256
        or not _sha256_binding_matches(
            root,
            review_binding,
            expected_path=FP013_REVIEW_PATH,
        )
        or review is None
        or review.get("schema_version") != "1.0"
        or review.get("document_id") != FP013_REVIEW_DOCUMENT_ID
        or review.get("evidence_type") != "INDEPENDENT_INTERNAL_REVIEW"
        or review.get("goal_id") != FP013_GOAL_ID
        or review.get("status") != "PASS"
        or review.get("reviewed_result_sha256_by_kind")
        != FP013_RESULT_SHA256_BY_KIND
        or not isinstance(reviewer, dict)
        or reviewer.get("id") != review.get("reviewer_id")
        or reviewer.get("decision") != "APPROVED"
        or reviewer.get("decided_at") != review.get("reviewed_at")
    ):
        return {}

    implementation = result_documents["IMPLEMENTATION_RECORD"]
    changed = implementation.get("changed_artifacts")
    if (
        not isinstance(changed, list)
        or not changed
        or any(not isinstance(row, dict) for row in changed)
        or implementation.get("implementation_content_set_sha256")
        != FP013_IMPLEMENTATION_CONTENT_SET_SHA256
        or _implementation_content_set_sha256(changed)
        != FP013_IMPLEMENTATION_CONTENT_SET_SHA256
    ):
        return {}
    start_state = _fp013_start_repository_state_snapshot(
        root, start_gate_binding
    )
    if start_state is None:
        return {}
    start_snapshot, start_head_commit = start_state
    result: dict[str, tuple[str, str]] = {}
    seen_paths: set[str] = set()
    for record in changed:
        relative = record.get("path")
        after_sha256 = record.get("after_sha256")
        change_kind = record.get("change_kind")
        path = _exact_repo_file(root, relative)
        snapshot_successor = (
            _fp047_start_snapshot_successor(
                root,
                checkpoint,
                relative,
                after_sha256,
            )
            if require_live_after
            and isinstance(relative, str)
            and isinstance(after_sha256, str)
            else None
        )
        if (
            not isinstance(relative, str)
            or relative in seen_paths
            or path is None
            or not isinstance(after_sha256, str)
            or not continuation.SHA256_RE.fullmatch(after_sha256)
            or (
                require_live_after
                and continuation.sha256_file(path) != after_sha256
                and snapshot_successor is None
            )
            or change_kind not in {"ADDED", "MODIFIED"}
        ):
            return {}
        seen_paths.add(relative)
        start_worktree = start_snapshot.get(relative)
        if change_kind == "ADDED":
            head_path = (
                _fp013_start_head_path_sha256(
                    root,
                    start_head_commit,
                    relative,
                )
                if start_worktree is None
                else None
            )
            if (
                record.get("before_sha256") is not None
                or (
                    start_worktree is not None
                    and start_worktree.get("state") == "PRESENT"
                )
                or (start_worktree is None and head_path is None)
                or (
                    start_worktree is None
                    and head_path is not None
                    and head_path[0]
                )
            ):
                return {}
            continue
        if start_worktree is None:
            head_path = _fp013_start_head_path_sha256(
                root,
                start_head_commit,
                relative,
            )
            if (
                head_path == (False, None)
                and record.get("before_sha256") is None
                and relative
                == "apps/android/app/src/test/java/kr/co/hanium/dreamup/"
                "walksafe/MainActivityIntegratedConsentStaticTest.kt"
            ):
                continue
            if (
                head_path is None
                or not head_path[0]
                or head_path[1] is None
            ):
                return {}
            before_sha256 = head_path[1]
        else:
            if (
                not isinstance(start_worktree, dict)
                or start_worktree.get("state") != "PRESENT"
            ):
                return {}
            before_sha256 = start_worktree.get("sha256")
        if (
            not isinstance(before_sha256, str)
            or not continuation.SHA256_RE.fullmatch(before_sha256)
            or before_sha256 == after_sha256
            or record.get("before_sha256") not in {None, before_sha256}
        ):
            return {}
        result[relative] = before_sha256, after_sha256
    return result if result else {}


def _fp015_review_chain_matches(
    root: Path,
    receipt: dict[str, Any],
) -> bool:
    subject_path = _exact_repo_file(root, FP015_REVIEW_SUBJECT_PATH)
    attestation_path = _exact_repo_file(root, FP015_REVIEW_ATTESTATION_PATH)
    review_path = _exact_repo_file(root, FP015_REVIEW_PATH)
    if (
        subject_path is None
        or attestation_path is None
        or review_path is None
        or continuation.sha256_file(subject_path)
        != FP015_REVIEW_SUBJECT_SHA256
        or continuation.sha256_file(attestation_path)
        != FP015_REVIEW_ATTESTATION_SHA256
        or continuation.sha256_file(review_path) != FP015_REVIEW_SHA256
    ):
        return False
    subject = _load_exact_json(root, FP015_REVIEW_SUBJECT_PATH)
    attestation = _load_exact_json(root, FP015_REVIEW_ATTESTATION_PATH)
    review = _load_exact_json(root, FP015_REVIEW_PATH)
    if not all(
        isinstance(document, dict)
        for document in (subject, attestation, review)
    ):
        return False
    reviewer = receipt.get("reviewer")
    expected_provenance = {
        "path": FP015_REVIEW_PATH,
        "sha256": FP015_REVIEW_SHA256,
    }
    expected_attestation_provenance = {
        "path": FP015_REVIEW_ATTESTATION_PATH,
        "sha256": FP015_REVIEW_ATTESTATION_SHA256,
    }
    return bool(
        receipt.get("reviewer_provenance") == expected_provenance
        and isinstance(reviewer, dict)
        and reviewer.get("id") == FP015_REVIEWER_ID
        and reviewer.get("decision") == "APPROVED"
        and reviewer.get("decided_at") == FP015_REVIEWED_AT
        and subject.get("schema_version") == "1.0"
        and subject.get("evidence_type") == "INTERNAL_REVIEW_SUBJECT"
        and subject.get("goal_id") == FP015_GOAL_ID
        and subject.get("reviewed_result_sha256_by_kind")
        == FP015_RESULT_SHA256_BY_KIND
        and subject.get("implementation_scope")
        == {
            "scope": "EXACT_34_PATH_SET",
            "exact_path_count": 34,
            "content_set_sha256": FP015_IMPLEMENTATION_CONTENT_SET_SHA256,
        }
        and attestation.get("schema_version") == "1.0"
        and attestation.get("evidence_type") == "INTERNAL_REVIEW_ATTESTATION"
        and attestation.get("goal_id") == FP015_GOAL_ID
        and attestation.get("decision") == "APPROVED"
        and attestation.get("review_subject_sha256")
        == FP015_REVIEW_SUBJECT_SHA256
        and attestation.get("reviewed_result_sha256_by_kind")
        == FP015_RESULT_SHA256_BY_KIND
        and attestation.get("reviewer_id") == FP015_REVIEWER_ID
        and attestation.get("reviewer_task")
        == "/root/fp015_final_evidence_review"
        and attestation.get("reviewed_at") == FP015_REVIEWED_AT
        and review.get("schema_version") == "1.0"
        and review.get("document_id") == FP015_REVIEW_DOCUMENT_ID
        and review.get("evidence_type") == "INDEPENDENT_INTERNAL_REVIEW"
        and review.get("goal_id") == FP015_GOAL_ID
        and review.get("status") == "PASS"
        and review.get("review_subject_sha256")
        == FP015_REVIEW_SUBJECT_SHA256
        and review.get("attestation_provenance")
        == expected_attestation_provenance
        and review.get("reviewed_result_sha256_by_kind")
        == FP015_RESULT_SHA256_BY_KIND
        and review.get("reviewer_id") == FP015_REVIEWER_ID
        and review.get("reviewed_at") == FP015_REVIEWED_AT
        and review.get("findings") == attestation.get("findings")
        and review.get("review_boundary") == attestation.get("review_boundary")
    )


def _fp015_successor_product_artifacts(
    root: Path,
    checkpoint: dict[str, Any],
    *,
    successor_artifacts: dict[str, tuple[str, str]] | None = None,
    require_live_successors: bool = True,
) -> dict[str, tuple[str, str]] | None:
    state = checkpoint.get("goal_execution")
    if not isinstance(state, dict):
        return None
    if (
        successor_artifacts is None
        and _fp014_is_declared_complete(checkpoint)
    ):
        successor_artifacts = _fp014_successor_product_artifacts(
            root,
            checkpoint,
        )
        if successor_artifacts is None:
            return None
    fp048_required = _fp048_android_report_successor_is_declared(
        checkpoint
    )
    fp048_artifacts = (
        _fp048_sealed_product_successor_artifacts(root, checkpoint)
        if fp048_required
        else {}
    )
    if fp048_required and fp048_artifacts is None:
        return None
    status_by_goal = state.get("status_by_goal")
    inventory = state.get("dynamic_goal_inventory")
    fp015 = (
        inventory.get(FP015_GOAL_ID)
        if isinstance(inventory, dict)
        else None
    )
    goal_path = _exact_repo_file(root, FP015_GOAL_PATH)
    if (
        not isinstance(status_by_goal, dict)
        or status_by_goal.get(FP015_GOAL_ID) != "COMPLETE_AT_TARGET"
        or not isinstance(fp015, dict)
        or fp015.get("goal_id") != FP015_GOAL_ID
        or fp015.get("path") != FP015_GOAL_PATH
        or fp015.get("sha256") != FP015_GOAL_SHA256
        or goal_path is None
        or continuation.sha256_file(goal_path) != FP015_GOAL_SHA256
    ):
        return None

    completion_binding = _binding_by_role(checkpoint, FP015_COMPLETION_ROLE)
    completion_roles = state.get("completion_evidence_by_goal")
    completion_projection = (
        {
            key: completion_binding.get(key)
            for key in ("role", "document_id", "path", "file_sha256")
        }
        if isinstance(completion_binding, dict)
        else None
    )
    if (
        completion_binding is None
        or completion_projection
        != FP015_CANONICAL_BINDINGS[FP015_COMPLETION_ROLE]
        or not _sha256_binding_matches(
            root,
            completion_binding,
            expected_path=FP015_COMPLETION_PATH,
        )
        or not isinstance(completion_roles, dict)
        or completion_roles.get(FP015_GOAL_ID) != [FP015_COMPLETION_ROLE]
        or not _fp015_completion_transition_matches(
            state,
            completion_binding,
        )
    ):
        return None

    receipt = _load_exact_json(root, FP015_COMPLETION_PATH)
    expected_receipt = {
        "schema_version": "1.0",
        "document_id": FP015_COMPLETION_DOCUMENT_ID,
        "evidence_type": "WORK_ITEM_EXECUTION_RECEIPT",
        "status": "ACCEPTED",
        "result": "PASS",
        "target_goal_id": FP015_GOAL_ID,
        "target_goal_content_sha256": FP015_GOAL_SHA256,
        "work_item_id": FP015_WORK_ITEM_ID,
        "source_policy_ids": ["FP-015"],
        "gap_ids": ["GAP-024"],
        "target_completion_level": (
            "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
        ),
        "completion_boundary": FP015_COMPLETION_BOUNDARY,
    }
    if (
        receipt is None
        or any(
            receipt.get(key) != expected
            for key, expected in expected_receipt.items()
        )
        or not _fp015_execution_session_matches(state, receipt)
        or not _fp015_review_chain_matches(root, receipt)
    ):
        return None

    start_gate_binding = receipt.get("implementation_start_gate_binding")
    expected_start_gate_binding = {
        "document_id": FP015_START_GATE_DOCUMENT_ID,
        "path": FP015_START_GATE_PATH,
        "file_sha256": FP015_START_GATE_SHA256,
    }
    if (
        start_gate_binding != expected_start_gate_binding
        or not _sha256_binding_matches(
            root,
            start_gate_binding,
            expected_path=FP015_START_GATE_PATH,
        )
    ):
        return None

    result_evidence = receipt.get("result_evidence")
    expected_kinds = list(FP015_RESULT_PATH_BY_KIND)
    if (
        not isinstance(result_evidence, list)
        or len(result_evidence) != len(expected_kinds)
        or any(not isinstance(binding, dict) for binding in result_evidence)
        or [binding.get("kind") for binding in result_evidence]
        != expected_kinds
    ):
        return None
    result_documents: dict[str, dict[str, Any]] = {}
    for binding in result_evidence:
        kind = binding.get("kind")
        expected_path = FP015_RESULT_PATH_BY_KIND.get(str(kind))
        expected_sha256 = FP015_RESULT_SHA256_BY_KIND.get(str(kind))
        if (
            not isinstance(kind, str)
            or expected_path is None
            or expected_sha256 is None
            or binding
            != {
                "kind": kind,
                "path": expected_path,
                "sha256": expected_sha256,
            }
            or not _sha256_binding_matches(
                root,
                binding,
                expected_path=expected_path,
            )
        ):
            return None
        document = _load_exact_json(root, expected_path)
        if (
            document is None
            or document.get("schema_version") != "1.0"
            or document.get("goal_id") != FP015_GOAL_ID
            or document.get("kind") != kind
            or document.get("status") != "PASS"
            or (
                kind == "IMPLEMENTATION_RECORD"
                and document.get("document_id")
                != FP015_IMPLEMENTATION_DOCUMENT_ID
            )
        ):
            return None
        result_documents[kind] = document

    implementation = result_documents["IMPLEMENTATION_RECORD"]
    changed = implementation.get("changed_artifacts")
    if (
        not isinstance(changed, list)
        or len(changed) != 34
        or any(not isinstance(row, dict) for row in changed)
        or implementation.get("implementation_content_set_sha256")
        != FP015_IMPLEMENTATION_CONTENT_SET_SHA256
        or _implementation_content_set_sha256(changed)
        != FP015_IMPLEMENTATION_CONTENT_SET_SHA256
    ):
        return None
    start_state = _fp015_start_repository_state_snapshot(
        root,
        start_gate_binding,
    )
    if start_state is None:
        return None
    start_snapshot, start_head_commit = start_state
    result: dict[str, tuple[str, str]] = {}
    seen_paths: set[str] = set()
    controlled_current_bindings: dict[str, str] | None = None
    for record in changed:
        relative = record.get("path")
        before_sha256 = record.get("before_sha256")
        after_sha256 = record.get("after_sha256")
        before_source = record.get("before_source")
        change_kind = record.get("change_kind")
        path = _exact_repo_file(root, relative)
        successor = (
            successor_artifacts.get(relative)
            if (
                isinstance(relative, str)
                and isinstance(successor_artifacts, dict)
            )
            else None
        )
        if (
            successor is None
            and isinstance(relative, str)
            and isinstance(after_sha256, str)
        ):
            successor = _fp047_start_snapshot_successor(
                root,
                checkpoint,
                relative,
                after_sha256,
            )
        controlled_successor = None
        if (
            successor is None
            and isinstance(relative, str)
            and isinstance(after_sha256, str)
            and path is not None
            and continuation.sha256_file(path) != after_sha256
        ):
            if controlled_current_bindings is None:
                binding_errors, controlled_current_bindings = (
                    validate_phase1_android_report_successor_binding(
                        root,
                        checkpoint,
                    )
                )
                if binding_errors:
                    controlled_current_bindings = {}
            controlled_successor = (
                _phase1_android_report_successor_bridge(
                    root,
                    checkpoint,
                    relative,
                    after_sha256,
                    current_bindings=controlled_current_bindings,
                )
            )
            if controlled_successor is None:
                controlled_successor = (
                    _phone_mounting_current_state_successor_bridge(
                        root,
                        relative,
                        after_sha256,
                    )
                )
            if controlled_successor is not None:
                successor = controlled_successor
        fp048_successor = (
            fp048_artifacts.get(relative)
            if isinstance(fp048_artifacts, dict)
            and isinstance(relative, str)
            else None
        )
        if fp048_successor is not None:
            if successor is None and fp048_successor[0] == after_sha256:
                successor = fp048_successor
            elif (
                successor is not None
                and successor[1] == fp048_successor[0]
            ):
                successor = successor[0], fp048_successor[1]
        if (
            not isinstance(relative, str)
            or relative in seen_paths
            or not isinstance(after_sha256, str)
            or not continuation.SHA256_RE.fullmatch(after_sha256)
            or path is None
            or (
                successor is None
                and require_live_successors
                and continuation.sha256_file(path) != after_sha256
            )
            or (
                successor is not None
                and successor[0] != after_sha256
            )
            or change_kind not in {"ADDED", "MODIFIED"}
        ):
            return None
        seen_paths.add(relative)
        start_worktree = start_snapshot.get(relative)
        if change_kind == "ADDED":
            head_path = (
                _fp013_start_head_path_sha256(
                    root,
                    start_head_commit,
                    relative,
                )
                if start_worktree is None
                else None
            )
            if (
                before_sha256 is not None
                or before_source != "GATE_PINNED_HEAD_ABSENT"
                or start_worktree is not None
                or head_path != (False, None)
            ):
                return None
            continue
        if (
            not isinstance(before_sha256, str)
            or not continuation.SHA256_RE.fullmatch(before_sha256)
            or before_sha256 == after_sha256
        ):
            return None
        if start_worktree is None:
            head_path = _fp013_start_head_path_sha256(
                root,
                start_head_commit,
                relative,
            )
            if (
                head_path is None
                or not head_path[0]
                or head_path[1] != before_sha256
                or before_source != "GATE_PINNED_HEAD"
            ):
                return None
        elif (
            start_worktree.get("state") != "PRESENT"
            or start_worktree.get("sha256") != before_sha256
            or before_source != "GATE_DIRTY_SNAPSHOT"
        ):
            return None
        result[relative] = (
            before_sha256,
            (
                controlled_successor[1]
                if controlled_successor is not None
                else after_sha256
            ),
        )
    return result if len(result) == 16 else None


def _fp015_is_declared_complete(checkpoint: dict[str, Any]) -> bool:
    state = checkpoint.get("goal_execution")
    status_by_goal = (
        state.get("status_by_goal")
        if isinstance(state, dict)
        else None
    )
    return bool(
        isinstance(status_by_goal, dict)
        and status_by_goal.get(FP015_GOAL_ID) == "COMPLETE_AT_TARGET"
    )


def _fp014_execution_session_matches(
    state: dict[str, Any],
    receipt: dict[str, Any],
) -> bool:
    history = state.get("transition_history")
    if not isinstance(history, list):
        return False
    sessions = [
        event
        for event in history
        if (
            isinstance(event, dict)
            and event.get("event_type")
            in {"GOAL_STARTED", "WORK_SESSION_RESUMED"}
            and event.get("subject_goal_id") == FP014_GOAL_ID
        )
    ]
    if len(sessions) != 1 or sessions[0].get("event_type") != "GOAL_STARTED":
        return False
    start = sessions[0]
    return bool(
        receipt.get("execution_session_event")
        == {
            "sequence": 19,
            "event_id": FP014_START_EVENT_ID,
            "event_type": "GOAL_STARTED",
            "event_sha256": FP014_START_EVENT_SHA256,
        }
        and receipt.get("execution_start_event_sha256")
        == FP014_START_EVENT_SHA256
        and start.get("sequence") == 19
        and start.get("event_id") == FP014_START_EVENT_ID
        and start.get("event_sha256") == FP014_START_EVENT_SHA256
        and continuation.event_sha256(start) == FP014_START_EVENT_SHA256
        and start.get("implementation_start_gate_binding")
        == receipt.get("implementation_start_gate_binding")
    )


def _fp014_start_repository_state_snapshot(
    root: Path,
    start_gate_binding: Any,
) -> tuple[dict[str, dict[str, Any]], str] | None:
    gate_path = _exact_repo_file(root, FP014_START_GATE_PATH)
    output_path = _exact_repo_file(root, FP014_REPOSITORY_STATE_PATH)
    if gate_path is None or output_path is None:
        return None
    try:
        gate_bytes = gate_path.read_bytes()
        output_bytes = output_path.read_bytes()
        gate = json.loads(gate_bytes.decode("utf-8"))
        output = json.loads(output_bytes.decode("utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return None
    expected_binding = {
        "document_id": FP014_START_GATE_DOCUMENT_ID,
        "path": FP014_START_GATE_PATH,
        "file_sha256": FP014_START_GATE_SHA256,
    }
    output_name = FP014_REPOSITORY_STATE_PATH.rsplit("/", 1)[1]

    def bound_output(value: Any) -> bool:
        if isinstance(value, dict):
            direct = [
                child for child in value.values() if isinstance(child, str)
            ]
            if (
                FP014_REPOSITORY_STATE_PATH in direct
                or output_name in direct
                or FP014_REPOSITORY_STATE_PATH in value
                or output_name in value
            ):
                encoded = json.dumps(value, ensure_ascii=False)
                if FP014_REPOSITORY_STATE_SHA256 in encoded:
                    return True
            return any(bound_output(child) for child in value.values())
        if isinstance(value, list):
            return any(bound_output(child) for child in value)
        return False

    repository = output.get("repository") if isinstance(output, dict) else None
    dirty = output.get("dirty_snapshot") if isinstance(output, dict) else None
    paths = dirty.get("paths") if isinstance(dirty, dict) else None
    if (
        start_gate_binding != expected_binding
        or continuation.sha256_bytes(gate_bytes) != FP014_START_GATE_SHA256
        or continuation.sha256_bytes(output_bytes)
        != FP014_REPOSITORY_STATE_SHA256
        or not bound_output(gate)
        or output.get("evidence_type") != "GATE_REPOSITORY_STATE"
        or output.get("gate_event_id") != FP014_START_EVENT_ID
        or not isinstance(repository, dict)
        or repository.get("root") != "."
        or repository.get("head_commit") != FP014_START_HEAD_COMMIT
        or not isinstance(paths, list)
        or dirty.get("dirty_path_count") != len(paths)
    ):
        return None
    snapshot: dict[str, dict[str, Any]] = {}
    for entry in paths:
        if not isinstance(entry, dict):
            return None
        relative = entry.get("path")
        worktree = entry.get("worktree")
        if (
            not isinstance(relative, str)
            or not relative
            or relative in snapshot
            or not isinstance(worktree, dict)
        ):
            return None
        snapshot[relative] = worktree
    return snapshot, FP014_START_HEAD_COMMIT


def _fp014_pinned_documents(
    root: Path,
) -> dict[str, dict[str, Any]] | None:
    result: dict[str, dict[str, Any]] = {}
    for relative, expected_sha256 in FP014_JSON_SHA256_BY_PATH.items():
        path = _exact_repo_file(root, relative)
        if (
            path is None
            or continuation.sha256_file(path) != expected_sha256
            or not isinstance(
                document := _load_exact_json(root, relative),
                dict,
            )
        ):
            return None
        result[relative] = document
    return result


def _fp014_verification_evidence_matches(
    root: Path,
    verification: dict[str, Any],
    subject: dict[str, Any],
) -> bool:
    checks = verification.get("checks")
    if (
        not isinstance(checks, list)
        or len(checks) != len(FP014_LOG_SHA256_BY_PATH)
        or any(not isinstance(check, dict) for check in checks)
        or [check.get("output_path") for check in checks]
        != list(FP014_LOG_SHA256_BY_PATH)
        or subject.get("verification_receipts") != checks
        or verification.get("evidence_boundary") != FP014_DETAILED_BOUNDARY
        or subject.get("completion_boundary") != FP014_DETAILED_BOUNDARY
    ):
        return False
    for check in checks:
        relative = check.get("output_path")
        path = _exact_repo_file(root, relative)
        expected_sha256 = FP014_LOG_SHA256_BY_PATH.get(str(relative))
        command = check.get("command")
        if (
            path is None
            or expected_sha256 is None
            or continuation.sha256_file(path) != expected_sha256
            or check.get("output_sha256") != expected_sha256
            or not isinstance(command, str)
            or continuation.sha256_bytes(command.encode("utf-8"))
            != check.get("command_sha256")
            or check.get("exit_code") != 0
            or check.get("execution_event_sequence") != 19
            or check.get("execution_event_id") != FP014_START_EVENT_ID
            or check.get("execution_event_sha256")
            != FP014_START_EVENT_SHA256
            or check.get("implementation_content_set_sha256")
            != FP014_IMPLEMENTATION_CONTENT_SET_SHA256
            or check.get("binding_markers_exact_once") is not True
        ):
            return False
        try:
            log = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            return False
        markers = {
            "WALKSAFE_RUN_ID": check.get("run_id"),
            "WALKSAFE_COMMAND_SHA256": check.get("command_sha256"),
            "WALKSAFE_EXECUTION_EVENT_SEQUENCE": 19,
            "WALKSAFE_EXECUTION_EVENT_ID": FP014_START_EVENT_ID,
            "WALKSAFE_EXECUTION_EVENT_SHA256": FP014_START_EVENT_SHA256,
            "WALKSAFE_IMPLEMENTATION_EXACT_11_CONTENT_SET_SHA256": (
                FP014_IMPLEMENTATION_CONTENT_SET_SHA256
            ),
            "WALKSAFE_COMMAND_STARTED_AT": check.get("started_at"),
            "WALKSAFE_COMMAND_ENDED_AT": check.get("executed_at"),
            "WALKSAFE_COMMAND_EXIT_CODE": 0,
        }
        if any(
            log.count(f"{name}={value}") != 1
            for name, value in markers.items()
        ):
            return False
    expected_junit = {
        "focused": {
            "canonical_log_path": next(iter(FP014_LOG_SHA256_BY_PATH)),
            "canonical_log_sha256": next(
                iter(FP014_LOG_SHA256_BY_PATH.values())
            ),
            "markers_exact_once": True,
            "xml_files": 5,
            "tests": 73,
            "failures": 0,
            "errors": 0,
            "skipped": 0,
            "xml_content_set_sha256": (
                "c0cd62e0e11768e9efc71f63387237656c5d95d952796701d9c7428ce1119420"
            ),
            "summarizer_path": (
                "scripts/build_walksafe_fp014_permission_denial_"
                "revocation_trace_20260726.py"
            ),
            "summarizer_sha256": (
                "934d805830e5e311af8d1cb97a342bcbccfdf89403e68d9e246b4f71430f90fd"
            ),
        },
        "full": {
            "canonical_log_path": list(FP014_LOG_SHA256_BY_PATH)[1],
            "canonical_log_sha256": list(
                FP014_LOG_SHA256_BY_PATH.values()
            )[1],
            "markers_exact_once": True,
            "xml_files": 91,
            "tests": 686,
            "failures": 0,
            "errors": 0,
            "skipped": 0,
            "xml_content_set_sha256": (
                "6d4b3db46da46fc16fb56eeedd3c29d05e28586d17a7ad18fce7bd4bd3173d5c"
            ),
            "summarizer_path": (
                "scripts/build_walksafe_fp014_permission_denial_"
                "revocation_trace_20260726.py"
            ),
            "summarizer_sha256": (
                "934d805830e5e311af8d1cb97a342bcbccfdf89403e68d9e246b4f71430f90fd"
            ),
        },
    }
    junit = subject.get("android_junit_evidence")
    summary = verification.get("android_test_summary")
    summarizer = _exact_repo_file(
        root,
        expected_junit["focused"]["summarizer_path"],
    )
    return bool(
        junit == expected_junit
        and isinstance(summary, dict)
        and summary.get("focused_tests") == "PASS"
        and summary.get("focused_test_count") == 73
        and summary.get("focused_test_failures") == 0
        and summary.get("focused_test_errors") == 0
        and summary.get("focused_test_skipped") == 0
        and summary.get("full_unit_tests") == "PASS"
        and summary.get("full_unit_test_count") == 686
        and summary.get("full_unit_test_failures") == 0
        and summary.get("full_unit_test_errors") == 0
        and summary.get("full_unit_test_skipped") == 0
        and summarizer is not None
        and continuation.sha256_file(summarizer)
        == expected_junit["focused"]["summarizer_sha256"]
    )


def _fp014_review_chain_matches(
    root: Path,
    receipt: dict[str, Any],
    documents: dict[str, dict[str, Any]],
) -> bool:
    subject = documents[FP014_REVIEW_SUBJECT_PATH]
    attestation = documents[FP014_REVIEW_ATTESTATION_PATH]
    review = documents[FP014_REVIEW_PATH]
    reviewer = receipt.get("reviewer")
    executor = receipt.get("executor")
    findings = attestation.get("findings")
    expected_reviewer = {
        "id": FP014_REVIEWER_ID,
        "task": FP014_REVIEWER_TASK,
        "role": "SEPARATE_INTERNAL_REVIEWER",
        "separate_internal_review_pass": True,
        "external_independence_claimed": False,
        "authority": "INTERNAL_REPOSITORY_CONTROL",
        "decision": "APPROVED",
        "decided_at": FP014_REVIEWED_AT,
    }
    return bool(
        executor == FP014_EXECUTOR
        and reviewer == expected_reviewer
        and str(executor.get("id")).strip().casefold()
        != str(reviewer.get("id")).strip().casefold()
        and str(executor.get("task")).strip().casefold()
        != str(reviewer.get("task")).strip().casefold()
        and receipt.get("reviewer_provenance")
        == {"path": FP014_REVIEW_PATH, "sha256": FP014_REVIEW_SHA256}
        and subject.get("schema_version") == "1.0"
        and subject.get("evidence_type") == "INTERNAL_REVIEW_SUBJECT"
        and subject.get("goal_id") == FP014_GOAL_ID
        and subject.get("reviewed_result_sha256_by_kind")
        == FP014_RESULT_SHA256_BY_KIND
        and subject.get("implementation_scope")
        == {
            "scope": "EXACT_11_PATH_SET",
            "exact_path_count": 11,
            "content_set_sha256": FP014_IMPLEMENTATION_CONTENT_SET_SHA256,
        }
        and _fp014_verification_evidence_matches(
            root,
            documents[FP014_RESULT_PATH_BY_KIND["VERIFICATION_RESULT"]],
            subject,
        )
        and attestation.get("schema_version") == "1.0"
        and attestation.get("evidence_type") == "INTERNAL_REVIEW_ATTESTATION"
        and attestation.get("goal_id") == FP014_GOAL_ID
        and attestation.get("reviewer_id") == FP014_REVIEWER_ID
        and attestation.get("reviewer_task") == FP014_REVIEWER_TASK
        and attestation.get("decision") == "APPROVED"
        and attestation.get("reviewed_at") == FP014_REVIEWED_AT
        and attestation.get("review_subject_sha256")
        == FP014_REVIEW_SUBJECT_SHA256
        and attestation.get("reviewed_result_sha256_by_kind")
        == FP014_RESULT_SHA256_BY_KIND
        and attestation.get("review_boundary") == FP014_REVIEW_BOUNDARY
        and isinstance(findings, dict)
        and findings.get("blocking") == 0
        and findings.get("major_open") == 0
        and review.get("schema_version") == "1.0"
        and review.get("document_id") == FP014_REVIEW_DOCUMENT_ID
        and review.get("evidence_type") == "INDEPENDENT_INTERNAL_REVIEW"
        and review.get("goal_id") == FP014_GOAL_ID
        and review.get("status") == "PASS"
        and review.get("reviewer_id") == FP014_REVIEWER_ID
        and review.get("reviewer_task") == FP014_REVIEWER_TASK
        and review.get("reviewed_at") == FP014_REVIEWED_AT
        and review.get("review_subject_sha256")
        == FP014_REVIEW_SUBJECT_SHA256
        and review.get("reviewed_result_sha256_by_kind")
        == FP014_RESULT_SHA256_BY_KIND
        and review.get("attestation_provenance")
        == {
            "path": FP014_REVIEW_ATTESTATION_PATH,
            "sha256": FP014_REVIEW_ATTESTATION_SHA256,
        }
        and review.get("findings") == findings
        and review.get("review_boundary") == FP014_REVIEW_BOUNDARY
    )


def _materialization_projection_at_sequence(
    state: dict[str, Any],
    history: list[Any],
    cutoff_sequence: int,
) -> tuple[dict[str, Any], dict[str, list[str]]] | None:
    inventory = state.get("dynamic_goal_inventory")
    children = state.get("materialized_child_goal_ids_by_parent")
    if not isinstance(inventory, dict) or not isinstance(children, dict):
        return None
    future_goal_ids = {
        event.get("materialized_goal_id")
        for event in history
        if (
            isinstance(event, dict)
            and event.get("event_type") == "GOAL_MATERIALIZED"
            and isinstance(event.get("sequence"), int)
            and event["sequence"] > cutoff_sequence
            and isinstance(event.get("materialized_goal_id"), str)
        )
    }
    projected_inventory = {
        goal_id: record
        for goal_id, record in inventory.items()
        if goal_id not in future_goal_ids
    }
    projected_children: dict[str, list[str]] = {}
    for parent_goal_id, child_goal_ids in children.items():
        if not isinstance(parent_goal_id, str) or not isinstance(
            child_goal_ids, list
        ):
            return None
        projected = [
            goal_id
            for goal_id in child_goal_ids
            if goal_id not in future_goal_ids
        ]
        if projected:
            projected_children[parent_goal_id] = projected
    return projected_inventory, projected_children


def _fp014_completion_transition_matches(
    root: Path,
    checkpoint: dict[str, Any],
    completion_binding: dict[str, Any],
) -> bool:
    state = checkpoint.get("goal_execution")
    history = (
        state.get("transition_history")
        if isinstance(state, dict)
        else None
    )
    completion_tail = (
        [
            event
            for event in history
            if (
                isinstance(event, dict)
                and event.get("sequence") in {20, 21, 22, 23}
            )
        ]
        if isinstance(history, list)
        else []
    )
    if (
        not isinstance(history, list)
        or len(completion_tail) != 4
    ):
        return False
    update, completion, materialized, ready = completion_tail
    if [event.get("sequence") for event in completion_tail] != [20, 21, 22, 23]:
        return False
    if any(
        event.get("event_sha256") != continuation.event_sha256(event)
        for event in completion_tail
    ):
        return False
    if any(
        event.get("previous_event_sha256")
        != completion_tail[index - 1].get("event_sha256")
        for index, event in enumerate(completion_tail[1:], start=1)
    ):
        return False
    binding_projection = {
        key: completion_binding.get(key)
        for key in ("role", "document_id", "path", "file_sha256")
    }
    expected_roles = list(FP014_CANONICAL_BINDINGS)
    canonical_snapshot = update.get("canonical_binding_snapshot_after")
    projection = _materialization_projection_at_sequence(state, history, 23)
    if projection is None:
        return False
    inventory, children = projection
    fp016 = (
        inventory.get(FP016_GOAL_ID)
        if isinstance(inventory, dict)
        else None
    )
    if not isinstance(fp016, dict):
        return False
    fp016_path = fp016.get("path")
    fp016_sha256 = fp016.get("sha256")
    goal_path = _exact_repo_file(root, fp016_path)
    readiness_basis = ready.get("readiness_basis")
    readiness_text = json.dumps(
        readiness_basis,
        ensure_ascii=False,
        sort_keys=True,
    )
    return bool(
        update.get("event_id") == FP014_CANONICAL_UPDATE_EVENT_ID
        and update.get("event_type") == "CANONICAL_BINDINGS_UPDATED"
        and update.get("previous_event_sha256") == FP014_START_EVENT_SHA256
        and update.get("from_status") == "IN_PROGRESS"
        and update.get("to_status") == "IN_PROGRESS"
        and update.get("status_changes") == {}
        and update.get("produced_by_goal_id") == FP014_GOAL_ID
        and update.get("changed_binding_roles") == expected_roles
        and update.get("evidence_refs") == expected_roles
        and update.get("produced_binding_roles")
        == ["IMPLEMENTATION_BACKLOG", "IMPLEMENTATION_GAP"]
        and update.get("changed_subject_ids_by_role")
        == {
            "IMPLEMENTATION_BACKLOG": ["FP-014"],
            "IMPLEMENTATION_GAP": ["FP-014", "GAP-023"],
        }
        and update.get("producer_output_subject_ids_by_role")
        == {
            "IMPLEMENTATION_BACKLOG": ["FP-014"],
            "IMPLEMENTATION_GAP": ["FP-014", "GAP-023"],
        }
        and update.get("producer_completion_receipt_binding")
        == binding_projection
        and isinstance(canonical_snapshot, dict)
        and all(
            canonical_snapshot.get(role) == binding
            for role, binding in FP014_CANONICAL_BINDINGS.items()
        )
        and completion.get("event_id") == FP014_COMPLETION_EVENT_ID
        and completion.get("event_type") == "GOAL_COMPLETED"
        and completion.get("subject_goal_id") == FP014_GOAL_ID
        and completion.get("from_status") == "IN_PROGRESS"
        and completion.get("to_status") == "COMPLETE_AT_TARGET"
        and completion.get("status_changes")
        == {FP014_GOAL_ID: "COMPLETE_AT_TARGET"}
        and completion.get("evidence_refs") == [FP014_COMPLETION_ROLE]
        and completion.get("completion_evidence_bindings")
        == {FP014_COMPLETION_ROLE: binding_projection}
        and completion.get("completion_receipt_binding")
        == binding_projection
        and completion.get("completion_evidence_by_goal_after", {}).get(
            FP014_GOAL_ID
        )
        == [FP014_COMPLETION_ROLE]
        and completion.get("canonical_update_event_sha256")
        == update.get("event_sha256")
        and materialized.get("event_id") == FP016_MATERIALIZED_EVENT_ID
        and materialized.get("event_type") == "GOAL_MATERIALIZED"
        and materialized.get("materialized_goal_id") == FP016_GOAL_ID
        and materialized.get("materialized_goal_path") == fp016_path
        and materialized.get("materialized_goal_content_sha256")
        == fp016_sha256
        and materialized.get("materialized_from_role")
        == "IMPLEMENTATION_BACKLOG"
        and materialized.get("materialized_from_path")
        == FP014_CANONICAL_BINDINGS["IMPLEMENTATION_BACKLOG"]["path"]
        and materialized.get("materialized_from_document_id")
        == FP014_CANONICAL_BINDINGS["IMPLEMENTATION_BACKLOG"]["document_id"]
        and materialized.get("materialized_from_sha256")
        == FP014_CANONICAL_BINDINGS["IMPLEMENTATION_BACKLOG"]["file_sha256"]
        and materialized.get("predecessor_goal_id") == FP014_GOAL_ID
        and materialized.get("predecessor_goal_content_sha256")
        == FP014_GOAL_SHA256
        and materialized.get("from_status") is None
        and materialized.get("to_status") == "PLANNED"
        and materialized.get("status_changes") == {FP016_GOAL_ID: "PLANNED"}
        and ready.get("event_id") == FP016_READY_EVENT_ID
        and ready.get("event_type") == "GOAL_READY"
        and ready.get("subject_goal_id") == FP016_GOAL_ID
        and ready.get("from_status") == "PLANNED"
        and ready.get("to_status") == "READY"
        and ready.get("status_changes") == {FP016_GOAL_ID: "READY"}
        and FP014_GOAL_ID in readiness_text
        and str(completion.get("event_sha256")) in readiness_text
        and ready.get("dynamic_goal_inventory_after") == inventory
        and ready.get("materialized_child_goal_ids_by_parent_after")
        == children
        and fp016.get("goal_id") == FP016_GOAL_ID
        and fp016.get("parent_goal_id") == "WS-GOAL-EPIC-02"
        and fp016.get("goal_kind") == "WORK_ITEM"
        and fp016.get("work_item_type") == "POLICY_GAP_WORK"
        and fp016.get("initial_status") == "PLANNED"
        and fp016.get("materialized_from_role") == "IMPLEMENTATION_BACKLOG"
        and fp016.get("materialized_from_path")
        == FP014_CANONICAL_BINDINGS["IMPLEMENTATION_BACKLOG"]["path"]
        and fp016.get("materialized_from_document_id")
        == FP014_CANONICAL_BINDINGS["IMPLEMENTATION_BACKLOG"]["document_id"]
        and fp016.get("materialized_from_sha256")
        == FP014_CANONICAL_BINDINGS["IMPLEMENTATION_BACKLOG"]["file_sha256"]
        and fp016.get("predecessor_goal_id") == FP014_GOAL_ID
        and fp016.get("predecessor_goal_content_sha256") == FP014_GOAL_SHA256
        and fp016.get("materialized_event_sha256")
        == materialized.get("event_sha256")
        and goal_path is not None
        and isinstance(fp016_sha256, str)
        and continuation.sha256_file(goal_path) == fp016_sha256
    )


def _fp016_is_declared_complete(checkpoint: dict[str, Any]) -> bool:
    state = checkpoint.get("goal_execution")
    status = state.get("status_by_goal") if isinstance(state, dict) else None
    return bool(
        isinstance(status, dict)
        and status.get(FP016_GOAL_ID) == "COMPLETE_AT_TARGET"
    )


def _fp016_completion_transition_matches(
    root: Path,
    checkpoint: dict[str, Any],
    completion_binding: dict[str, Any],
) -> bool:
    state = checkpoint.get("goal_execution")
    history = (
        state.get("transition_history")
        if isinstance(state, dict)
        else None
    )
    tail = (
        [
            event
            for event in history
            if isinstance(event, dict)
            and event.get("sequence") in {25, 26, 27, 28}
        ]
        if isinstance(history, list)
        else []
    )
    if (
        not isinstance(state, dict)
        or not isinstance(history, list)
        or len(tail) != 4
        or [event.get("sequence") for event in tail] != [25, 26, 27, 28]
        or [event.get("event_sha256") for event in tail]
        != [
            FP016_CANONICAL_UPDATE_EVENT_SHA256,
            FP016_COMPLETION_EVENT_SHA256,
            FP012_MATERIALIZED_EVENT_SHA256,
            FP012_READY_EVENT_SHA256,
        ]
        or any(
            event.get("event_sha256") != continuation.event_sha256(event)
            for event in tail
        )
        or any(
            event.get("previous_event_sha256")
            != tail[index - 1].get("event_sha256")
            for index, event in enumerate(tail[1:], start=1)
        )
    ):
        return False
    update, completion, materialized, ready = tail
    projection = _materialization_projection_at_sequence(state, history, 28)
    if projection is None:
        return False
    inventory, children = projection
    fp012 = inventory.get(FP012_GOAL_ID)
    binding_projection = {
        key: completion_binding.get(key)
        for key in ("role", "document_id", "path", "file_sha256")
    }
    canonical_snapshot = update.get("canonical_binding_snapshot_after")
    readiness_text = json.dumps(
        ready.get("readiness_basis"),
        ensure_ascii=False,
        sort_keys=True,
    )
    fp012_path = _exact_repo_file(root, FP012_GOAL_RELATIVE)
    expected_roles = list(FP016_CANONICAL_BINDINGS)
    return bool(
        update.get("event_id") == FP016_CANONICAL_UPDATE_EVENT_ID
        and update.get("event_type") == "CANONICAL_BINDINGS_UPDATED"
        and update.get("previous_event_sha256") == FP016_START_EVENT_SHA256
        and update.get("from_status") == "IN_PROGRESS"
        and update.get("to_status") == "IN_PROGRESS"
        and update.get("status_changes") == {}
        and update.get("produced_by_goal_id") == FP016_GOAL_ID
        and update.get("changed_binding_roles") == expected_roles
        and update.get("evidence_refs") == expected_roles
        and update.get("produced_binding_roles")
        == ["IMPLEMENTATION_BACKLOG", "IMPLEMENTATION_GAP"]
        and update.get("changed_subject_ids_by_role")
        == {
            "IMPLEMENTATION_BACKLOG": ["FP-016"],
            "IMPLEMENTATION_GAP": ["FP-016", "GAP-025"],
        }
        and update.get("producer_completion_receipt_binding")
        == binding_projection
        and isinstance(canonical_snapshot, dict)
        and all(
            canonical_snapshot.get(role) == binding
            for role, binding in FP016_CANONICAL_BINDINGS.items()
        )
        and completion.get("event_id") == FP016_COMPLETION_EVENT_ID
        and completion.get("event_type") == "GOAL_COMPLETED"
        and completion.get("subject_goal_id") == FP016_GOAL_ID
        and completion.get("from_status") == "IN_PROGRESS"
        and completion.get("to_status") == "COMPLETE_AT_TARGET"
        and completion.get("status_changes")
        == {FP016_GOAL_ID: "COMPLETE_AT_TARGET"}
        and completion.get("evidence_refs") == [FP016_COMPLETION_ROLE]
        and completion.get("completion_evidence_bindings")
        == {FP016_COMPLETION_ROLE: binding_projection}
        and completion.get("completion_receipt_binding")
        == binding_projection
        and completion.get("completion_evidence_by_goal_after", {}).get(
            FP016_GOAL_ID
        )
        == [FP016_COMPLETION_ROLE]
        and completion.get("canonical_update_event_sha256")
        == update.get("event_sha256")
        and materialized.get("event_id") == FP012_MATERIALIZED_EVENT_ID
        and materialized.get("event_type") == "GOAL_MATERIALIZED"
        and materialized.get("materialized_goal_id") == FP012_GOAL_ID
        and materialized.get("materialized_goal_path")
        == FP012_GOAL_RELATIVE
        and materialized.get("materialized_goal_content_sha256")
        == FP012_GOAL_SHA256
        and materialized.get("materialized_from_role")
        == "IMPLEMENTATION_BACKLOG"
        and materialized.get("materialized_from_path")
        == FP016_CANONICAL_BINDINGS["IMPLEMENTATION_BACKLOG"]["path"]
        and materialized.get("materialized_from_document_id")
        == FP016_CANONICAL_BINDINGS["IMPLEMENTATION_BACKLOG"]["document_id"]
        and materialized.get("materialized_from_sha256")
        == FP016_CANONICAL_BINDINGS["IMPLEMENTATION_BACKLOG"]["file_sha256"]
        and materialized.get("predecessor_goal_id") == FP016_GOAL_ID
        and materialized.get("predecessor_goal_content_sha256")
        == FP016_GOAL_SHA256
        and materialized.get("from_status") is None
        and materialized.get("to_status") == "PLANNED"
        and materialized.get("status_changes") == {FP012_GOAL_ID: "PLANNED"}
        and ready.get("event_id") == FP012_READY_EVENT_ID
        and ready.get("event_type") == "GOAL_READY"
        and ready.get("subject_goal_id") == FP012_GOAL_ID
        and ready.get("from_status") == "PLANNED"
        and ready.get("to_status") == "READY"
        and ready.get("status_changes") == {FP012_GOAL_ID: "READY"}
        and FP016_GOAL_ID in readiness_text
        and FP016_COMPLETION_EVENT_SHA256 in readiness_text
        and ready.get("dynamic_goal_inventory_after") == inventory
        and ready.get("materialized_child_goal_ids_by_parent_after")
        == children
        and isinstance(fp012, dict)
        and fp012.get("goal_id") == FP012_GOAL_ID
        and fp012.get("path") == FP012_GOAL_RELATIVE
        and fp012.get("sha256") == FP012_GOAL_SHA256
        and fp012.get("predecessor_goal_id") == FP016_GOAL_ID
        and fp012.get("predecessor_goal_content_sha256")
        == FP016_GOAL_SHA256
        and fp012.get("materialized_event_sha256")
        == FP012_MATERIALIZED_EVENT_SHA256
        and fp012_path is not None
        and continuation.sha256_file(fp012_path) == FP012_GOAL_SHA256
    )


def _fp012_is_declared_complete(checkpoint: dict[str, Any]) -> bool:
    state = checkpoint.get("goal_execution")
    status = state.get("status_by_goal") if isinstance(state, dict) else None
    return bool(
        isinstance(status, dict)
        and status.get(FP012_GOAL_ID) == "COMPLETE_AT_TARGET"
    )


def _fp012_completion_transition_matches(
    root: Path,
    checkpoint: dict[str, Any],
    completion_binding: dict[str, Any],
) -> bool:
    state = checkpoint.get("goal_execution")
    history = (
        state.get("transition_history")
        if isinstance(state, dict)
        else None
    )
    tail = (
        [
            event
            for event in history
            if isinstance(event, dict)
            and event.get("sequence") in {30, 31, 32, 33}
        ]
        if isinstance(history, list)
        else []
    )
    if (
        not isinstance(state, dict)
        or not isinstance(history, list)
        or len(tail) != 4
        or [event.get("sequence") for event in tail] != [30, 31, 32, 33]
        or [event.get("event_id") for event in tail]
        != [
            FP012_CANONICAL_UPDATE_EVENT_ID,
            FP012_COMPLETION_EVENT_ID,
            FP047_MATERIALIZED_EVENT_ID,
            FP047_READY_EVENT_ID,
        ]
        or any(
            event.get("event_sha256") != continuation.event_sha256(event)
            for event in tail
        )
        or any(
            event.get("previous_event_sha256")
            != tail[index - 1].get("event_sha256")
            for index, event in enumerate(tail[1:], start=1)
        )
    ):
        return False
    update, completion, materialized, ready = tail
    projection = _materialization_projection_at_sequence(state, history, 33)
    if projection is None:
        return False
    inventory, children = projection
    fp047 = inventory.get(FP047_GOAL_ID)
    if not isinstance(fp047, dict):
        return False
    fp047_path = fp047.get("path")
    fp047_sha256 = fp047.get("sha256")
    goal_path = _exact_repo_file(root, fp047_path)
    goal_text = (
        goal_path.read_text(encoding="utf-8")
        if goal_path is not None
        else ""
    )
    frontmatter_parts = goal_text.split("+++", 2)
    frontmatter_lines = (
        {
            line.strip().replace('"', "").replace("'", "")
            for line in frontmatter_parts[1].splitlines()
        }
        if len(frontmatter_parts) == 3
        else set()
    )
    binding_projection = {
        key: completion_binding.get(key)
        for key in ("role", "document_id", "path", "file_sha256")
    }
    canonical_snapshot = update.get("canonical_binding_snapshot_after")
    readiness_text = json.dumps(
        ready.get("readiness_basis"),
        ensure_ascii=False,
        sort_keys=True,
    )
    expected_roles = list(FP012_CANONICAL_BINDINGS)
    return bool(
        update.get("event_type") == "CANONICAL_BINDINGS_UPDATED"
        and update.get("from_status") == "IN_PROGRESS"
        and update.get("to_status") == "IN_PROGRESS"
        and update.get("status_changes") == {}
        and update.get("produced_by_goal_id") == FP012_GOAL_ID
        and update.get("changed_binding_roles") == expected_roles
        and update.get("evidence_refs") == expected_roles
        and update.get("produced_binding_roles")
        == ["IMPLEMENTATION_BACKLOG", "IMPLEMENTATION_GAP"]
        and update.get("changed_subject_ids_by_role")
        == {
            "IMPLEMENTATION_BACKLOG": ["FP-012"],
            "IMPLEMENTATION_GAP": ["FP-012", "GAP-021"],
        }
        and update.get("producer_completion_receipt_binding")
        == binding_projection
        and isinstance(canonical_snapshot, dict)
        and all(
            canonical_snapshot.get(role) == binding
            for role, binding in FP012_CANONICAL_BINDINGS.items()
        )
        and completion.get("event_type") == "GOAL_COMPLETED"
        and completion.get("subject_goal_id") == FP012_GOAL_ID
        and completion.get("from_status") == "IN_PROGRESS"
        and completion.get("to_status") == "COMPLETE_AT_TARGET"
        and completion.get("status_changes")
        == {FP012_GOAL_ID: "COMPLETE_AT_TARGET"}
        and completion.get("evidence_refs") == [FP012_COMPLETION_ROLE]
        and completion.get("completion_evidence_bindings")
        == {FP012_COMPLETION_ROLE: binding_projection}
        and completion.get("completion_receipt_binding")
        == binding_projection
        and completion.get("completion_evidence_by_goal_after", {}).get(
            FP012_GOAL_ID
        )
        == [FP012_COMPLETION_ROLE]
        and completion.get("canonical_update_event_sha256")
        == update.get("event_sha256")
        and materialized.get("event_type") == "GOAL_MATERIALIZED"
        and materialized.get("materialized_goal_id") == FP047_GOAL_ID
        and materialized.get("materialized_goal_path") == fp047_path
        and materialized.get("materialized_goal_content_sha256")
        == fp047_sha256
        and materialized.get("materialized_from_role")
        == "IMPLEMENTATION_BACKLOG"
        and materialized.get("materialized_from_path")
        == FP012_CANONICAL_BINDINGS["IMPLEMENTATION_BACKLOG"]["path"]
        and materialized.get("materialized_from_document_id")
        == FP012_CANONICAL_BINDINGS["IMPLEMENTATION_BACKLOG"]["document_id"]
        and materialized.get("materialized_from_sha256")
        == FP012_CANONICAL_BINDINGS["IMPLEMENTATION_BACKLOG"]["file_sha256"]
        and materialized.get("predecessor_goal_id") == FP012_GOAL_ID
        and materialized.get("predecessor_goal_content_sha256")
        == FP012_GOAL_SHA256
        and materialized.get("from_status") is None
        and materialized.get("to_status") == "PLANNED"
        and materialized.get("status_changes") == {FP047_GOAL_ID: "PLANNED"}
        and ready.get("event_type") == "GOAL_READY"
        and ready.get("subject_goal_id") == FP047_GOAL_ID
        and ready.get("from_status") == "PLANNED"
        and ready.get("to_status") == "READY"
        and ready.get("status_changes") == {FP047_GOAL_ID: "READY"}
        and FP012_GOAL_ID in readiness_text
        and completion.get("event_sha256") in readiness_text
        and ready.get("dynamic_goal_inventory_after") == inventory
        and ready.get("materialized_child_goal_ids_by_parent_after")
        == children
        and FP047_GOAL_ID in children.get(FP047_PARENT_GOAL_ID, [])
        and (
            (
                state.get("focus_goal_id") == FP047_GOAL_ID
                and state.get("status_by_goal", {}).get(FP047_GOAL_ID)
                in {"READY", "IN_PROGRESS"}
            )
            or (
                state.get("status_by_goal", {}).get(FP047_GOAL_ID)
                == "COMPLETE_AT_TARGET"
                and _fp047_completion_transition_matches(root, checkpoint)
            )
        )
        and fp047.get("goal_id") == FP047_GOAL_ID
        and isinstance(fp047_sha256, str)
        and continuation.SHA256_RE.fullmatch(fp047_sha256)
        and fp047.get("predecessor_goal_id") == FP012_GOAL_ID
        and fp047.get("predecessor_goal_content_sha256")
        == FP012_GOAL_SHA256
        and fp047.get("parent_goal_id") == FP047_PARENT_GOAL_ID
        and fp047.get("materialized_event_sha256")
        == materialized.get("event_sha256")
        and f"goal_id = {FP047_GOAL_ID}" in frontmatter_lines
        and f"parent_goal_id = {FP047_PARENT_GOAL_ID}" in frontmatter_lines
        and f"predecessor_goal_id = {FP012_GOAL_ID}" in frontmatter_lines
        and (
            "predecessor_goal_content_sha256 = "
            f"{FP012_GOAL_SHA256}"
        )
        in frontmatter_lines
        and "source_policy_ids = [FP-047]" in frontmatter_lines
        and "gap_ids = [GAP-056]" in frontmatter_lines
        and goal_path is not None
        and continuation.sha256_file(goal_path) == fp047_sha256
    )


def _fp047_is_declared_complete(checkpoint: dict[str, Any]) -> bool:
    state = checkpoint.get("goal_execution")
    if not isinstance(state, dict):
        return False
    statuses = state.get("status_by_goal")
    history = state.get("transition_history")
    return bool(
        (
            isinstance(statuses, dict)
            and statuses.get(FP047_GOAL_ID) == "COMPLETE_AT_TARGET"
        )
        or _binding_by_role(checkpoint, FP047_COMPLETION_ROLE) is not None
        or (
            isinstance(history, list)
            and any(
                isinstance(event, dict)
                and event.get("sequence", 0) >= 37
                and (
                    event.get("subject_goal_id") == FP047_GOAL_ID
                    or event.get("produced_by_goal_id") == FP047_GOAL_ID
                )
                for event in history
            )
        )
    )


def _fp047_completion_has_sealed_history(
    root: Path,
    completion: dict[str, Any],
) -> bool:
    manifest_path = continuation.resolve_repo_file(
        root,
        CANONICAL_PREIMAGE_MANIFEST_RELATIVE,
    )
    if manifest_path is None:
        return False
    archive_errors, _ = validate_canonical_preimage_archive(root)
    return bool(
        not archive_errors
        and completion.get("sequence") == 38
        and completion.get("event_sha256")
        == CANONICAL_PREIMAGE_SOURCE_EVENT_SHA256
    )


def _fp047_strict_completion_evidence_matches(
    root: Path,
    receipt: Any,
    *,
    resume: dict[str, Any],
    update: dict[str, Any],
    completion: dict[str, Any],
) -> bool:
    if (
        not isinstance(receipt, dict)
        or set(receipt) != FP047_RECEIPT_FIELDS
        or receipt.get("completion_boundary") != FP047_COMPLETION_BOUNDARY
    ):
        return False

    result_evidence = receipt.get("result_evidence")
    if (
        not isinstance(result_evidence, list)
        or len(result_evidence) != len(FP047_RESULT_EVIDENCE_CONTRACT)
    ):
        return False
    result_documents: dict[str, dict[str, Any]] = {}
    result_hashes: dict[str, str] = {}
    for row, (kind, path, document_id) in zip(
        result_evidence,
        FP047_RESULT_EVIDENCE_CONTRACT,
    ):
        if (
            not isinstance(row, dict)
            or set(row) != {"kind", "path", "sha256"}
            or row.get("kind") != kind
            or row.get("path") != path
            or not _sha256_binding_matches(root, row, expected_path=path)
        ):
            return False
        document = _load_exact_json(root, path)
        if (
            not isinstance(document, dict)
            or document.get("schema_version") != "1.0"
            or document.get("document_id") != document_id
            or document.get("goal_id") != FP047_GOAL_ID
            or document.get("kind") != kind
            or document.get("status") != "PASS"
        ):
            return False
        result_documents[kind] = document
        result_hashes[kind] = row["sha256"]

    implementation = result_documents["IMPLEMENTATION_RECORD"]
    changed = implementation.get("changed_artifacts")
    if (
        not isinstance(changed, list)
        or any(not isinstance(item, dict) for item in changed)
        or [item.get("path") for item in changed]
        != list(FP047_ACTIVE_SCOPE_PATHS)
        or len({item.get("path") for item in changed}) != len(changed)
        or any(
            not isinstance(item.get("after_sha256"), str)
            or continuation.SHA256_RE.fullmatch(item["after_sha256"]) is None
            for item in changed
        )
    ):
        return False
    sealed_history = _fp047_completion_has_sealed_history(
        root,
        completion,
    )
    recorded_changed_artifacts = []
    for item in changed:
        artifact_path = root / item["path"]
        if not artifact_path.is_file():
            return False
        actual_sha256 = continuation.sha256_file(artifact_path)
        if item["after_sha256"] != actual_sha256 and not sealed_history:
            return False
        recorded_changed_artifacts.append(
            {
                "path": item["path"],
                "after_sha256": item["after_sha256"],
            }
        )
    content_set_sha256 = _implementation_content_set_sha256(
        recorded_changed_artifacts
    )
    if (
        implementation.get("implementation_content_set_sha256")
        != content_set_sha256
        or implementation.get("completion_boundary")
        != FP047_REVIEW_SUBJECT_BOUNDARY
    ):
        return False

    verification = result_documents["VERIFICATION_RESULT"]
    verification_checks = verification.get("checks")
    if (
        not isinstance(verification_checks, list)
        or not verification_checks
        or any(
            not isinstance(check, dict)
            or check.get("implementation_content_set_sha256")
            != content_set_sha256
            for check in verification_checks
        )
        or verification.get("evidence_boundary")
        != FP047_REVIEW_SUBJECT_BOUNDARY
    ):
        return False

    successor = result_documents["SUCCESSOR_TRACE"]
    successor_bindings = successor.get("resulting_canonical_bindings")
    if (
        successor.get("canonical_update_event_type")
        != "CANONICAL_BINDINGS_UPDATED"
        or successor.get("changed_subject_ids_by_role")
        != {
            "IMPLEMENTATION_BACKLOG": ["FP-047"],
            "IMPLEMENTATION_GAP": ["FP-047", "GAP-056"],
        }
        or successor.get("next_policy_gap_pair")
        != {"source_policy_id": "FP-048", "gap_id": "GAP-057"}
        or successor.get("completion_boundary")
        != FP047_REVIEW_SUBJECT_BOUNDARY
        or not isinstance(successor_bindings, dict)
        or set(successor_bindings) != set(FP047_R021_BINDING_REQUIREMENTS)
    ):
        return False
    if (
        "evidence_boundary" in successor
        and successor.get("evidence_boundary")
        != FP047_REVIEW_SUBJECT_BOUNDARY
    ):
        return False
    for role, requirement in FP047_R021_BINDING_REQUIREMENTS.items():
        successor_binding = successor_bindings.get(role)
        if (
            not isinstance(successor_binding, dict)
            or successor_binding.get("role") != role
            or successor_binding.get("document_id")
            != requirement["document_id"]
            or successor_binding.get("path") != requirement["path"]
            or not _sha256_binding_matches(
                root,
                successor_binding,
                expected_path=requirement["path"],
            )
        ):
            return False

    review_subject = _load_exact_json(root, FP047_REVIEW_SUBJECT_PATH)
    expected_scope = {
        "scope": "EXACT_31_PATH_SET",
        "exact_path_count": 31,
        "product_path_count": 26,
        "tooling_path_count": 5,
        "product_paths": list(FP047_ACTIVE_SCOPE_PATHS[:26]),
        "tooling_paths": list(FP047_ACTIVE_SCOPE_PATHS[26:]),
        "content_set_sha256": content_set_sha256,
    }
    if (
        not isinstance(review_subject, dict)
        or review_subject.get("schema_version") != "1.0"
        or review_subject.get("evidence_type")
        != "INTERNAL_REVIEW_SUBJECT"
        or review_subject.get("goal_id") != FP047_GOAL_ID
        or review_subject.get("reviewed_result_sha256_by_kind")
        != result_hashes
        or review_subject.get("implementation_scope") != expected_scope
        or review_subject.get("verification_receipts")
        != verification_checks
        or review_subject.get("completion_boundary")
        != FP047_REVIEW_SUBJECT_BOUNDARY
    ):
        return False

    manifest = receipt.get("output_evidence_manifest")
    if (
        not isinstance(manifest, list)
        or len(manifest) != len(FP047_OUTPUT_MANIFEST_PATHS)
        or continuation.canonical_json_sha256(manifest)
        != receipt.get("output_evidence_manifest_sha256")
    ):
        return False
    manifest_hashes: dict[str, str] = {}
    for row, path in zip(manifest, FP047_OUTPUT_MANIFEST_PATHS):
        if (
            not isinstance(row, dict)
            or set(row) != {"path", "sha256"}
            or row.get("path") != path
            or path in manifest_hashes
            or not _sha256_binding_matches(root, row, expected_path=path)
        ):
            return False
        manifest_hashes[path] = row["sha256"]
    if any(
        manifest_hashes[path] != result_hashes[kind]
        for kind, path, _ in FP047_RESULT_EVIDENCE_CONTRACT
    ):
        return False

    reviewer_provenance = receipt.get("reviewer_provenance")
    if (
        not isinstance(reviewer_provenance, dict)
        or set(reviewer_provenance) != {"path", "sha256"}
        or reviewer_provenance.get("path") != FP047_REVIEW_PATH
        or reviewer_provenance.get("sha256")
        != manifest_hashes[FP047_REVIEW_PATH]
        or not _sha256_binding_matches(
            root,
            reviewer_provenance,
            expected_path=FP047_REVIEW_PATH,
        )
    ):
        return False
    review_subject_sha256 = manifest_hashes[FP047_REVIEW_SUBJECT_PATH]
    review = _load_exact_json(root, FP047_REVIEW_PATH)
    if (
        not isinstance(review, dict)
        or review.get("schema_version") != "1.0"
        or review.get("document_id") != FP047_REVIEW_DOCUMENT_ID
        or review.get("evidence_type") != "INDEPENDENT_INTERNAL_REVIEW"
        or review.get("goal_id") != FP047_GOAL_ID
        or review.get("status") != "PASS"
        or review.get("reviewer_id") != FP047_REVIEWER_ID
        or review.get("reviewer_task") != FP047_REVIEWER_TASK
        or review.get("review_subject_sha256") != review_subject_sha256
        or review.get("reviewed_result_sha256_by_kind") != result_hashes
        or review.get("findings", {}).get("blocking") != 0
        or review.get("findings", {}).get("major_open") != 0
        or review.get("review_boundary") != FP047_REVIEW_BOUNDARY
    ):
        return False
    attestation_provenance = review.get("attestation_provenance")
    if (
        not isinstance(attestation_provenance, dict)
        or set(attestation_provenance) != {"path", "sha256"}
        or attestation_provenance.get("path")
        != FP047_REVIEW_ATTESTATION_PATH
        or not _sha256_binding_matches(
            root,
            attestation_provenance,
            expected_path=FP047_REVIEW_ATTESTATION_PATH,
        )
    ):
        return False
    attestation = _load_exact_json(root, FP047_REVIEW_ATTESTATION_PATH)
    if (
        not isinstance(attestation, dict)
        or attestation.get("schema_version") != "1.0"
        or attestation.get("evidence_type")
        != "INTERNAL_REVIEW_ATTESTATION"
        or attestation.get("goal_id") != FP047_GOAL_ID
        or attestation.get("decision") != "APPROVED"
        or attestation.get("reviewer_id") != FP047_REVIEWER_ID
        or attestation.get("reviewer_task") != FP047_REVIEWER_TASK
        or attestation.get("review_subject_sha256")
        != review_subject_sha256
        or attestation.get("reviewed_result_sha256_by_kind")
        != result_hashes
        or attestation.get("findings") != review.get("findings")
        or attestation.get("findings", {}).get("blocking") != 0
        or attestation.get("findings", {}).get("major_open") != 0
        or attestation.get("review_boundary") != FP047_REVIEW_BOUNDARY
        or attestation.get("reviewed_at") != review.get("reviewed_at")
    ):
        return False

    reviewed_at = review.get("reviewed_at")
    execution_window = receipt.get("execution_window")
    timestamp_values = (
        resume.get("occurred_at"),
        (
            execution_window.get("started_at")
            if isinstance(execution_window, dict)
            else None
        ),
        (
            execution_window.get("ended_at")
            if isinstance(execution_window, dict)
            else None
        ),
        receipt.get("completed_at"),
        receipt.get("generated_at"),
        reviewed_at,
        update.get("occurred_at"),
        completion.get("occurred_at"),
    )
    parsed_timestamps = []
    for value in timestamp_values:
        if not isinstance(value, str):
            return False
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            return False
        if parsed.tzinfo is None or parsed.utcoffset() is None:
            return False
        parsed_timestamps.append(parsed)
    (
        resume_at,
        window_started_at,
        window_ended_at,
        completed_at,
        generated_at,
        reviewed_at_value,
        update_at,
        completion_at,
    ) = parsed_timestamps
    return bool(
        isinstance(reviewed_at, str)
        and receipt.get("executor")
        == {
            "id": (
                "CODEX-FP047-USER-ADMIN-LOGIN-AUTHORIZATION-SEPARATION-"
                "IMPLEMENTER-20260726-001"
            ),
            "task": "/root",
            "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            "authority": "GRAPH_V2_4_STANDING_EXECUTION_AUTHORITY",
        }
        and receipt.get("reviewer")
        == {
            "id": FP047_REVIEWER_ID,
            "task": FP047_REVIEWER_TASK,
            "role": "SEPARATE_INTERNAL_REVIEWER",
            "separate_internal_review_pass": True,
            "external_independence_claimed": False,
            "authority": "INTERNAL_REPOSITORY_CONTROL",
            "decision": "APPROVED",
            "decided_at": reviewed_at,
        }
        and receipt.get("completed_at") == reviewed_at
        and receipt.get("generated_at") == reviewed_at
        and isinstance(execution_window, dict)
        and set(execution_window) == {"started_at", "ended_at"}
        and execution_window.get("started_at")
        == resume.get("occurred_at")
        and execution_window.get("ended_at") == reviewed_at
        and resume_at
        == window_started_at
        <= window_ended_at
        == completed_at
        == generated_at
        == reviewed_at_value
        <= update_at
        <= completion_at
    )


def _fp047_completion_transition_matches(
    root: Path,
    checkpoint: dict[str, Any],
) -> bool:
    state = checkpoint.get("goal_execution")
    history = (
        state.get("transition_history")
        if isinstance(state, dict)
        else None
    )
    if not isinstance(state, dict) or not isinstance(history, list):
        return False
    selected = [
        (index, event)
        for index, event in enumerate(history)
        if isinstance(event, dict)
        and event.get("sequence") in {36, 37, 38}
    ]
    if (
        len(selected) != 3
        or [event.get("sequence") for _, event in selected]
        != [36, 37, 38]
        or [index for index, _ in selected]
        != list(range(selected[0][0], selected[0][0] + 3))
    ):
        return False
    resume_index = selected[0][0]
    resume, update, completion = [event for _, event in selected]
    if resume_index == 0:
        return False
    predecessor = history[resume_index - 1]
    prior_sessions = [
        event
        for event in history[:resume_index]
        if isinstance(event, dict)
        and event.get("event_type")
        in {"GOAL_STARTED", "WORK_SESSION_RESUMED"}
        and event.get("subject_goal_id") == FP047_GOAL_ID
    ]
    starts = [
        event
        for event in history[:resume_index]
        if isinstance(event, dict)
        and event.get("event_type") == "GOAL_STARTED"
        and event.get("subject_goal_id") == FP047_GOAL_ID
    ]
    if (
        not isinstance(predecessor, dict)
        or not prior_sessions
        or len(starts) != 1
        or resume.get("sequence") != 36
        or resume.get("event_id") != FP047_RESUME_EVENT_ID
        or resume.get("event_type") != "WORK_SESSION_RESUMED"
        or resume.get("subject_goal_id") != FP047_GOAL_ID
        or resume.get("focus_goal_id") != FP047_GOAL_ID
        or resume.get("from_status") != "IN_PROGRESS"
        or resume.get("to_status") != "IN_PROGRESS"
        or resume.get("status_changes") != {}
        or resume.get("source_checkpoint_version") != "1.22.0"
        or resume.get("previous_event_sha256")
        != predecessor.get("event_sha256")
        or resume.get("previous_execution_session_event_sha256")
        != prior_sessions[-1].get("event_sha256")
        or resume.get("event_sha256")
        != continuation.event_sha256(resume)
        or update.get("previous_event_sha256")
        != resume.get("event_sha256")
        or completion.get("previous_event_sha256")
        != update.get("event_sha256")
        or update.get("event_sha256")
        != continuation.event_sha256(update)
        or completion.get("event_sha256")
        != continuation.event_sha256(completion)
    ):
        return False
    completion_index = selected[2][0]
    history_suffix = history[completion_index:]
    if (
        not history_suffix
        or any(not isinstance(event, dict) for event in history_suffix)
        or any(
            event.get("event_sha256")
            != continuation.event_sha256(event)
            for event in history_suffix
        )
        or any(
            event.get("previous_event_sha256")
            != history_suffix[index - 1].get("event_sha256")
            for index, event in enumerate(history_suffix[1:], start=1)
        )
        or state.get("transition_history_anchor_sha256")
        != history_suffix[-1].get("event_sha256")
    ):
        return False

    completion_binding = _binding_by_role(
        checkpoint,
        FP047_COMPLETION_ROLE,
    )
    if (
        not isinstance(completion_binding, dict)
        or completion_binding.get("role") != FP047_COMPLETION_ROLE
        or completion_binding.get("document_id")
        != FP047_COMPLETION_DOCUMENT_ID
        or completion_binding.get("path") != FP047_COMPLETION_PATH
        or completion_binding.get("identity_json_path") != "document_id"
        or completion_binding.get("mutable") is not False
        or not _sha256_binding_matches(
            root,
            completion_binding,
            expected_path=FP047_COMPLETION_PATH,
        )
    ):
        return False
    binding_projection = {
        key: completion_binding.get(key)
        for key in ("role", "document_id", "path", "file_sha256")
    }
    canonical_snapshot = update.get("canonical_binding_snapshot_after")
    if not isinstance(canonical_snapshot, dict):
        return False
    canonical_bindings: dict[str, dict[str, Any]] = {}
    canonical_documents: dict[str, dict[str, Any]] = {}
    for role, requirement in FP047_R021_BINDING_REQUIREMENTS.items():
        # FP047's r021 Gap/Backlog were the canonical head at seq37, but a
        # later producer is allowed to advance those roles.  Revalidate the
        # immutable historical binding sealed by seq37 instead of incorrectly
        # requiring r021 to remain the current canonical head forever.
        historical = canonical_snapshot.get(role)
        binding = (
            {
                **historical,
                "identity_json_path": requirement["identity_json_path"],
                "mutable": False,
            }
            if isinstance(historical, dict)
            else None
        )
        if (
            not isinstance(binding, dict)
            or binding.get("role") != role
            or binding.get("document_id") != requirement["document_id"]
            or binding.get("path") != requirement["path"]
            or binding.get("identity_json_path")
            != requirement["identity_json_path"]
            or binding.get("mutable") is not False
            or not _sha256_binding_matches(
                root,
                binding,
                expected_path=requirement["path"],
            )
        ):
            return False
        document = _load_exact_json(root, requirement["path"])
        if not isinstance(document, dict):
            return False
        canonical_bindings[role] = binding
        canonical_documents[role] = document
    gap = canonical_documents["IMPLEMENTATION_GAP"]
    backlog = canonical_documents["IMPLEMENTATION_BACKLOG"]
    gap_assessments = gap.get("assessments")
    backlog_actions = backlog.get("next_action_sequence")
    gap_row = (
        [
            row
            for row in gap_assessments
            if isinstance(row, dict) and row.get("gap_id") == "GAP-056"
        ]
        if isinstance(gap_assessments, list)
        else []
    )
    fp047_actions = (
        [
            row
            for row in backlog_actions
            if isinstance(row, dict)
            and row.get("source_policy_id") == "FP-047"
        ]
        if isinstance(backlog_actions, list)
        else []
    )
    if (
        gap.get("metadata", {}).get("report_id")
        != FP047_R021_BINDING_REQUIREMENTS["IMPLEMENTATION_GAP"][
            "document_id"
        ]
        or gap.get("metadata", {}).get("version") != "0.21.0"
        or gap.get("summary", {}).get("status_counts")
        != {
            "BLOCKED": 5,
            "CONFLICTING": 16,
            "EVIDENCE_MISSING": 4,
            "MISSING": 11,
            "PARTIAL": 32,
            "IMPLEMENTED": 0,
        }
        or len(gap_row) != 1
        or gap_row[0].get("source_policy_id") != "FP-047"
        or gap_row[0].get("status") != "PARTIAL"
        or gap.get("reassessment_scope", {}).get(
            "directly_reassessed_gap_ids"
        )
        != ["GAP-056"]
        or gap.get("reassessment_scope", {})
        .get("next_adjacent_gap", {})
        .get("source_policy_id")
        != "FP-048"
        or backlog.get("metadata", {}).get("backlog_id")
        != FP047_R021_BINDING_REQUIREMENTS["IMPLEMENTATION_BACKLOG"][
            "document_id"
        ]
        or backlog.get("metadata", {}).get("version") != "0.21.0"
        or backlog.get("gap_report_content_sha256")
        != gap.get("report_content_sha256")
        or len(fp047_actions) != 1
        or fp047_actions[0].get("status") != "PARTIAL"
        or backlog.get("next_single_action", {}).get("source_policy_id")
        != "FP-048"
        or backlog.get("next_single_action", {}).get("gap_id")
        != "GAP-057"
    ):
        return False

    receipt = _load_exact_json(root, FP047_COMPLETION_PATH)
    start = starts[0]
    expected_receipt = {
        "schema_version": "1.0",
        "document_id": FP047_COMPLETION_DOCUMENT_ID,
        "evidence_type": "WORK_ITEM_EXECUTION_RECEIPT",
        "status": "ACCEPTED",
        "result": "PASS",
        "target_goal_id": FP047_GOAL_ID,
        "target_goal_content_sha256": FP047_GOAL_SHA256,
        "work_item_id": FP047_WORK_ITEM_ID,
        "source_policy_ids": ["FP-047"],
        "gap_ids": ["GAP-056"],
        "target_completion_level": (
            "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
        ),
        "execution_start_event_sha256": start.get("event_sha256"),
        "execution_session_event": {
            "sequence": resume.get("sequence"),
            "event_id": resume.get("event_id"),
            "event_type": resume.get("event_type"),
            "event_sha256": resume.get("event_sha256"),
        },
        "implementation_start_gate_binding": start.get(
            "implementation_start_gate_binding"
        ),
    }
    result_evidence = (
        receipt.get("result_evidence")
        if isinstance(receipt, dict)
        else None
    )
    manifest = (
        receipt.get("output_evidence_manifest")
        if isinstance(receipt, dict)
        else None
    )
    reviewer = receipt.get("reviewer") if isinstance(receipt, dict) else None
    executor = receipt.get("executor") if isinstance(receipt, dict) else None
    reviewer_provenance = (
        receipt.get("reviewer_provenance")
        if isinstance(receipt, dict)
        else None
    )
    if (
        not isinstance(receipt, dict)
        or not _fp047_strict_completion_evidence_matches(
            root,
            receipt,
            resume=resume,
            update=update,
            completion=completion,
        )
        or any(
            receipt.get(key) != expected
            for key, expected in expected_receipt.items()
        )
        or not isinstance(result_evidence, list)
        or {
            row.get("kind")
            for row in result_evidence
            if isinstance(row, dict)
        }
        != {
            "IMPLEMENTATION_RECORD",
            "VERIFICATION_RESULT",
            "SUCCESSOR_TRACE",
        }
        or any(
            not isinstance(row, dict)
            or not _sha256_binding_matches(
                root,
                row,
                expected_path=row.get("path"),
            )
            for row in result_evidence
        )
        or not isinstance(manifest, list)
        or not manifest
        or continuation.canonical_json_sha256(manifest)
        != receipt.get("output_evidence_manifest_sha256")
        or any(
            not isinstance(row, dict)
            or not _sha256_binding_matches(
                root,
                row,
                expected_path=row.get("path"),
            )
            for row in manifest
        )
        or not isinstance(reviewer, dict)
        or not isinstance(executor, dict)
        or reviewer.get("id") == executor.get("id")
        or reviewer.get("separate_internal_review_pass") is not True
        or reviewer.get("external_independence_claimed") is not False
        or reviewer.get("decision") != "APPROVED"
        or not isinstance(reviewer_provenance, dict)
        or not _sha256_binding_matches(
            root,
            reviewer_provenance,
            expected_path=reviewer_provenance.get("path"),
        )
    ):
        return False

    reference_update = next(
        (
            event
            for event in history
            if isinstance(event, dict) and event.get("sequence") == 30
        ),
        None,
    )
    reference_completion = next(
        (
            event
            for event in history
            if isinstance(event, dict) and event.get("sequence") == 31
        ),
        None,
    )
    expected_roles = [
        "IMPLEMENTATION_BACKLOG",
        "IMPLEMENTATION_GAP",
        FP047_COMPLETION_ROLE,
    ]
    expected_subjects = {
        "IMPLEMENTATION_BACKLOG": ["FP-047"],
        "IMPLEMENTATION_GAP": ["FP-047", "GAP-056"],
    }
    historical_archive_present = continuation.resolve_repo_file(
        root,
        CANONICAL_PREIMAGE_MANIFEST_RELATIVE,
    ) is not None
    if historical_archive_present:
        archive_errors, _ = validate_canonical_preimage_archive(root)
        if archive_errors:
            return False
        for role, expected in CANONICAL_PREIMAGE_EXPECTED.items():
            if (
                not isinstance(canonical_snapshot, dict)
                or canonical_snapshot.get(role)
                != {
                    "role": role,
                    "document_id": expected["document_id"],
                    "path": expected["live_path"],
                    "file_sha256": expected["historical_sha256"],
                }
            ):
                return False
        if (
            completion.get("event_sha256")
            != CANONICAL_PREIMAGE_SOURCE_EVENT_SHA256
        ):
            return False
    if (
        not isinstance(reference_update, dict)
        or not isinstance(reference_completion, dict)
        or set(update) != set(reference_update)
        or set(completion) != set(reference_completion)
        or update.get("sequence") != 37
        or update.get("event_id") != FP047_CANONICAL_UPDATE_EVENT_ID
        or update.get("event_type") != "CANONICAL_BINDINGS_UPDATED"
        or update.get("previous_focus_goal_id") != FP047_GOAL_ID
        or update.get("previous_focus_content_sha256")
        != FP047_GOAL_SHA256
        or update.get("focus_goal_id") != FP047_GOAL_ID
        or update.get("focus_goal_content_sha256") != FP047_GOAL_SHA256
        or update.get("from_status") != "IN_PROGRESS"
        or update.get("to_status") != "IN_PROGRESS"
        or update.get("status_changes") != {}
        or update.get("source_checkpoint_version") != "1.23.0"
        or update.get("static_plan_manifest_sha256")
        != resume.get("static_plan_manifest_sha256")
        or update.get("evidence_refs") != expected_roles
        or update.get("changed_binding_roles") != expected_roles
        or update.get("produced_binding_roles")
        != ["IMPLEMENTATION_BACKLOG", "IMPLEMENTATION_GAP"]
        or update.get("produced_by_goal_id") != FP047_GOAL_ID
        or update.get("changed_subject_ids_by_role")
        != expected_subjects
        or update.get("producer_output_subject_ids_by_role")
        != expected_subjects
        or update.get("producer_completion_receipt_binding")
        != binding_projection
        or update.get("impact_closure_goal_ids")
        != [FP047_PARENT_GOAL_ID]
        or update.get("impact_disposition_by_goal")
        != {
            FP047_PARENT_GOAL_ID: {
                "result": "REVALIDATION_REFRESH_REQUIRED",
                "target_status": "READY",
            }
        }
        or update.get("reopened_completion_event_sha256_by_goal") != {}
        or not isinstance(canonical_snapshot, dict)
        or any(
            canonical_snapshot.get(role)
            != {
                key: binding.get(key)
                for key in ("role", "document_id", "path", "file_sha256")
            }
            for role, binding in {
                **canonical_bindings,
                FP047_COMPLETION_ROLE: completion_binding,
            }.items()
        )
        or update.get("runtime_after") != resume.get("runtime_after")
        or update.get("blockers_after") != {}
        or update.get("blocker_resolution_ids_after") != []
    ):
        return False

    parent_path = _exact_repo_file(root, FP047_PARENT_GOAL_RELATIVE)
    resume_runtime = resume.get("runtime_after")
    resume_boundary = (
        resume_runtime.get("completion_boundary")
        if isinstance(resume_runtime, dict)
        else None
    )
    if (
        parent_path is None
        or not isinstance(resume_runtime, dict)
        or not isinstance(resume_boundary, dict)
    ):
        return False
    expected_boundary = {
        **resume_boundary,
        "internal_runnable_goal_ids": [
            FP047_PARENT_GOAL_ID,
            "WS-GOAL-EPIC-12",
        ],
        "internal_pending_goal_ids": [
            goal_id
            for goal_id in resume_boundary.get(
                "internal_pending_goal_ids",
                [],
            )
            if goal_id != FP047_GOAL_ID
        ],
    }
    expected_runtime = {
        **resume_runtime,
        "focus_goal_id": FP047_PARENT_GOAL_ID,
        "focus_goal_path": FP047_PARENT_GOAL_RELATIVE,
        "focus_work_item_id": "",
        "focus_source": "WORKSTREAM_GRAPH",
        "ready_frontier_goal_ids": [
            FP047_PARENT_GOAL_ID,
            "WS-GOAL-EPIC-12",
        ],
        "completion_boundary": expected_boundary,
    }
    completion_evidence = state.get("completion_evidence_by_goal")
    completion_evidence_at_event = completion.get(
        "completion_evidence_by_goal_after"
    )
    occurred_at = [
        event.get("occurred_at")
        for event in (resume, update, completion)
    ]
    return bool(
        all(isinstance(value, str) for value in occurred_at)
        and occurred_at[0] < occurred_at[1] < occurred_at[2]
        and completion.get("sequence") == 38
        and completion.get("event_id") == FP047_COMPLETION_EVENT_ID
        and completion.get("event_type") == "GOAL_COMPLETED"
        and completion.get("subject_goal_id") == FP047_GOAL_ID
        and completion.get("previous_focus_goal_id") == FP047_GOAL_ID
        and completion.get("previous_focus_content_sha256")
        == FP047_GOAL_SHA256
        and completion.get("focus_goal_id") == FP047_PARENT_GOAL_ID
        and completion.get("focus_goal_content_sha256")
        == continuation.sha256_file(parent_path)
        and completion.get("from_status") == "IN_PROGRESS"
        and completion.get("to_status") == "COMPLETE_AT_TARGET"
        and completion.get("status_changes")
        == {FP047_GOAL_ID: "COMPLETE_AT_TARGET"}
        and completion.get("source_checkpoint_version") == "1.23.0"
        and completion.get("static_plan_manifest_sha256")
        == resume.get("static_plan_manifest_sha256")
        and completion.get("evidence_refs") == [FP047_COMPLETION_ROLE]
        and completion.get("completion_evidence_bindings")
        == {FP047_COMPLETION_ROLE: binding_projection}
        and completion.get("completion_receipt_binding")
        == binding_projection
        and isinstance(completion_evidence_at_event, dict)
        and completion_evidence_at_event.get(FP047_GOAL_ID)
        == [FP047_COMPLETION_ROLE]
        and isinstance(completion_evidence, dict)
        and completion_evidence.get(FP047_GOAL_ID)
        == [FP047_COMPLETION_ROLE]
        and completion.get("canonical_update_event_sha256")
        == update.get("event_sha256")
        and completion.get("canonical_binding_snapshot_after")
        == canonical_snapshot
        and completion.get("runtime_after") == expected_runtime
        and completion.get("blockers_after") == {}
        and completion.get("blocker_resolution_ids_after") == []
        and state.get("status_by_goal", {}).get(FP047_GOAL_ID)
        == "COMPLETE_AT_TARGET"
    )


def _fp012_successor_product_artifacts(
    root: Path,
    checkpoint: dict[str, Any],
    *,
    successor_artifacts: dict[str, tuple[str, str]] | None = None,
) -> dict[str, tuple[str, str]] | None:
    state = checkpoint.get("goal_execution")
    if not isinstance(state, dict) or not _fp012_is_declared_complete(
        checkpoint
    ):
        return None
    status_by_goal = state.get("status_by_goal")
    completion_binding = _binding_by_role(checkpoint, FP012_COMPLETION_ROLE)
    completion_roles = state.get("completion_evidence_by_goal")
    binding_projection = (
        {
            key: completion_binding.get(key)
            for key in ("role", "document_id", "path", "file_sha256")
        }
        if isinstance(completion_binding, dict)
        else None
    )
    if (
        not isinstance(status_by_goal, dict)
        or status_by_goal.get(FP012_GOAL_ID) != "COMPLETE_AT_TARGET"
        or status_by_goal.get(FP047_GOAL_ID)
        not in {"READY", "IN_PROGRESS", "COMPLETE_AT_TARGET"}
        or (
            status_by_goal.get(FP047_GOAL_ID) == "IN_PROGRESS"
            and _fp047_gate_remediation_after_sha256(
                root,
                checkpoint,
                FP047_GATE_REMEDIATION_PRE_SHA256,
            )
            is None
        )
        or binding_projection
        != FP012_CANONICAL_BINDINGS[FP012_COMPLETION_ROLE]
        or not _sha256_binding_matches(
            root,
            completion_binding,
            expected_path=FP012_COMPLETION_PATH,
        )
        or not isinstance(completion_roles, dict)
        or completion_roles.get(FP012_GOAL_ID) != [FP012_COMPLETION_ROLE]
        or not _fp012_completion_transition_matches(
            root,
            checkpoint,
            completion_binding,
        )
    ):
        return None
    receipt = _load_exact_json(root, FP012_COMPLETION_PATH)
    receipt_path = _exact_repo_file(root, FP012_COMPLETION_PATH)
    expected_receipt = {
        "schema_version": "1.0",
        "document_id": FP012_COMPLETION_DOCUMENT_ID,
        "evidence_type": "WORK_ITEM_EXECUTION_RECEIPT",
        "status": "ACCEPTED",
        "result": "PASS",
        "target_goal_id": FP012_GOAL_ID,
        "target_goal_content_sha256": FP012_GOAL_SHA256,
        "work_item_id": FP012_WORK_ITEM_ID,
        "source_policy_ids": ["FP-012"],
        "gap_ids": ["GAP-021"],
    }
    if (
        receipt is None
        or receipt_path is None
        or continuation.sha256_file(receipt_path) != FP012_COMPLETION_SHA256
        or any(
            receipt.get(key) != expected
            for key, expected in expected_receipt.items()
        )
    ):
        return None
    result_evidence = receipt.get("result_evidence")
    if (
        not isinstance(result_evidence, list)
        or any(not isinstance(row, dict) for row in result_evidence)
        or any(
            not _sha256_binding_matches(
                root,
                row,
                expected_path=row.get("path"),
            )
            for row in result_evidence
        )
    ):
        return None
    implementations = [
        row
        for row in result_evidence
        if row.get("kind") == "IMPLEMENTATION_RECORD"
    ]
    if len(implementations) != 1:
        return None
    implementation_path = implementations[0].get("path")
    implementation = _load_exact_json(root, implementation_path)
    changed = (
        implementation.get("changed_artifacts")
        if isinstance(implementation, dict)
        else None
    )
    if (
        implementation is None
        or implementation.get("goal_id") != FP012_GOAL_ID
        or implementation.get("kind") != "IMPLEMENTATION_RECORD"
        or implementation.get("status") != "PASS"
        or not isinstance(changed, list)
        or not changed
        or any(not isinstance(row, dict) for row in changed)
        or len({row.get("path") for row in changed}) != len(changed)
        or implementation.get("implementation_content_set_sha256")
        != _implementation_content_set_sha256(changed)
    ):
        return None
    manifest = receipt.get("output_evidence_manifest")
    if (
        not isinstance(manifest, list)
        or any(not isinstance(row, dict) for row in manifest)
        or continuation.canonical_json_sha256(manifest)
        != receipt.get("output_evidence_manifest_sha256")
        or any(
            not _sha256_binding_matches(
                root,
                row,
                expected_path=row.get("path"),
            )
            for row in manifest
        )
    ):
        return None
    result: dict[str, tuple[str, str]] = {}
    controlled_current_bindings: dict[str, str] | None = None
    for record in changed:
        relative = record.get("path")
        before_sha256 = record.get("before_sha256")
        after_sha256 = record.get("after_sha256")
        change_kind = record.get("change_kind")
        path = _exact_repo_file(root, relative)
        live_sha256 = (
            continuation.sha256_file(path)
            if path is not None
            else None
        )
        declared_successor = (
            successor_artifacts.get(relative)
            if isinstance(successor_artifacts, dict)
            and isinstance(relative, str)
            else None
        )
        if (
            declared_successor is not None
            and declared_successor[0] != after_sha256
        ):
            declared_successor = None
        snapshot_successor = (
            _fp047_start_snapshot_successor(
                root,
                checkpoint,
                relative,
                after_sha256,
            )
            if isinstance(relative, str)
            and isinstance(after_sha256, str)
            else None
        )
        controlled_successor = None
        if (
            isinstance(relative, str)
            and isinstance(after_sha256, str)
            and live_sha256 != after_sha256
            and snapshot_successor is None
        ):
            if controlled_current_bindings is None:
                binding_errors, controlled_current_bindings = (
                    validate_phase1_android_report_successor_binding(
                        root,
                        checkpoint,
                    )
                )
                if binding_errors:
                    controlled_current_bindings = {}
            controlled_successor = (
                _phase1_android_report_successor_bridge(
                    root,
                    checkpoint,
                    relative,
                    after_sha256,
                    current_bindings=controlled_current_bindings,
                )
            )
        if (
            path is None
            or not isinstance(after_sha256, str)
            or not continuation.SHA256_RE.fullmatch(after_sha256)
            or (
                live_sha256 != after_sha256
                and snapshot_successor is None
                and controlled_successor is None
                and declared_successor is None
            )
            or change_kind not in {"ADDED", "MODIFIED"}
        ):
            return None
        if change_kind == "ADDED":
            if (
                before_sha256 is not None
                or record.get("before_source")
                != "GATE_PINNED_HEAD_ABSENT"
            ):
                return None
            continue
        if (
            not isinstance(before_sha256, str)
            or not continuation.SHA256_RE.fullmatch(before_sha256)
            or before_sha256 == after_sha256
            or record.get("before_source")
            not in {"GATE_DIRTY_SNAPSHOT", "GATE_PINNED_HEAD"}
        ):
            return None
        result[relative] = (
            before_sha256,
            (
                controlled_successor[1]
                if controlled_successor is not None
                else after_sha256
            ),
        )
    return result if result else None


def _fp047_failed_gate_remediation_evidence_matches(root: Path) -> bool:
    for attempt, relative in FP047_FAILED_GATE_DIRECTORY_BY_ATTEMPT.items():
        directory = root / relative
        if (
            not directory.is_dir()
            or {path.name for path in directory.iterdir()}
            != set(FP047_FAILED_GATE_FILE_NAMES_BY_ATTEMPT[attempt])
            or _exact_repo_file(
                root,
                f"{relative}/implementation-start-gate-receipt.json",
            )
            is not None
            or _exact_repo_file(root, f"{relative}/19-REPOSITORY_STATE.log")
            is not None
        ):
            return False
    return all(
        (
            (path := _exact_repo_file(root, relative)) is not None
            and continuation.sha256_file(path) == expected
        )
        for relative, expected
        in FP047_FAILED_GATE_LOG_SHA256_BY_PATH.items()
    )


def _fp047_repository_state_runner_sha256(
    document: Any,
) -> str | None:
    matches: list[dict[str, Any]] = []

    def collect(value: Any) -> None:
        if isinstance(value, dict):
            if value.get("path") == FP047_GATE_REMEDIATION_RUNNER_PATH:
                matches.append(value)
            for child in value.values():
                collect(child)
        elif isinstance(value, list):
            for child in value:
                collect(child)

    collect(document)
    if len(matches) != 1:
        return None
    worktree = matches[0].get("worktree")
    if (
        not isinstance(worktree, dict)
        or worktree.get("state") != "PRESENT"
        or worktree.get("type") != "REGULAR_FILE"
    ):
        return None
    value = worktree.get("sha256")
    return value if isinstance(value, str) else None


def _fp047_initial_start_events(
    checkpoint: dict[str, Any],
) -> tuple[dict[str, Any], dict[str, Any]] | None:
    state = checkpoint.get("goal_execution")
    history = (
        state.get("transition_history")
        if isinstance(state, dict)
        else None
    )
    if not isinstance(history, list) or len(history) < 2:
        return None
    starts = [
        (index, event)
        for index, event in enumerate(history)
        if (
            isinstance(event, dict)
            and event.get("event_type") == "GOAL_STARTED"
            and event.get("subject_goal_id") == FP047_GOAL_ID
        )
    ]
    if len(starts) != 1 or starts[0][0] == 0:
        return None
    start_index, started = starts[0]
    ready = history[start_index - 1]
    return ready, started


def _fp047_successful_start_snapshot_matches(
    root: Path,
    checkpoint: dict[str, Any],
) -> bool:
    pair = _fp047_initial_start_events(checkpoint)
    if pair is None:
        return False
    ready, started = pair
    event_id = started.get("event_id")
    if (
        not isinstance(ready, dict)
        or not isinstance(started, dict)
        or ready.get("event_id") != FP047_READY_EVENT_ID
        or ready.get("event_type") != "GOAL_READY"
        or started.get("sequence") != ready.get("sequence") + 1
        or started.get("event_type") != "GOAL_STARTED"
        or started.get("subject_goal_id") != FP047_GOAL_ID
        or started.get("from_status") != "READY"
        or started.get("to_status") != "IN_PROGRESS"
        or started.get("status_changes")
        != {FP047_GOAL_ID: "IN_PROGRESS"}
        or not isinstance(event_id, str)
        or FP047_START_EVENT_ID_RE.fullmatch(event_id) is None
        or started.get("previous_event_sha256")
        != ready.get("event_sha256")
        or started.get("event_sha256")
        != continuation.event_sha256(started)
    ):
        return False
    gate_directory = f"docs/control/execution/goal-gates/{event_id}"
    receipt_path = f"{gate_directory}/implementation-start-gate-receipt.json"
    state_path = f"{gate_directory}/19-REPOSITORY_STATE.log"
    binding = started.get("implementation_start_gate_binding")
    expected_document_id = event_id.replace(
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-",
        "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-",
        1,
    )
    if (
        not isinstance(binding, dict)
        or binding.get("document_id") != expected_document_id
        or binding.get("path") != receipt_path
        or not _sha256_binding_matches(
            root,
            binding,
            expected_path=receipt_path,
        )
    ):
        return False
    receipt = _load_exact_json(root, receipt_path)
    snapshot = started.get("repository_snapshot_before")
    if (
        not isinstance(receipt, dict)
        or receipt.get("schema_version") != "1.0"
        or receipt.get("document_id") != expected_document_id
        or receipt.get("evidence_type")
        != "IMPLEMENTATION_START_OR_RESUME_GATE"
        or receipt.get("gate_purpose") != "INITIAL_START"
        or receipt.get("status") != "PASS"
        or receipt.get("package_id") != V24_PACKAGE_ID
        or receipt.get("target_transition_event_id") != event_id
        or receipt.get("target_goal_id") != FP047_GOAL_ID
        or receipt.get("target_goal_content_sha256")
        != FP047_GOAL_SHA256
        or not isinstance(snapshot, dict)
        or receipt.get("repository_snapshot") != snapshot
        or snapshot.get("gate_event_id") != event_id
    ):
        return False
    runs = receipt.get("check_runs")
    if (
        not isinstance(runs, list)
        or len(runs) != 19
        or any(not isinstance(run, dict) for run in runs)
        or any(run.get("exit_code") != 0 for run in runs)
        or len({run.get("check_id") for run in runs}) != 19
    ):
        return False
    repository_run = runs[-1]
    output_sha256 = repository_run.get("output_sha256")
    output_file = _exact_repo_file(root, state_path)
    if (
        repository_run.get("check_id") != "REPOSITORY_STATE"
        or repository_run.get("output_path") != state_path
        or not isinstance(output_sha256, str)
        or continuation.SHA256_RE.fullmatch(output_sha256) is None
        or output_file is None
        or continuation.sha256_file(output_file) != output_sha256
        or snapshot.get("gate_repository_state_output_sha256")
        != output_sha256
    ):
        return False
    repository_state = _load_exact_json(root, state_path)
    return bool(
        isinstance(repository_state, dict)
        and repository_state.get("schema_version") == "1.0.0"
        and repository_state.get("evidence_type") == "GATE_REPOSITORY_STATE"
        and repository_state.get("gate_event_id") == event_id
        and _fp047_repository_state_runner_sha256(repository_state)
        == FP047_GATE_REMEDIATION_POST_SHA256
    )


def _fp047_successful_start_snapshot_artifacts(
    root: Path,
    checkpoint: dict[str, Any],
) -> dict[str, str] | None:
    if (
        not _fp047_successful_start_snapshot_matches(root, checkpoint)
        or not _fp047_failed_gate_remediation_evidence_matches(root)
    ):
        return None
    pair = _fp047_initial_start_events(checkpoint)
    if pair is None:
        return None
    _, event = pair
    event_id = event.get("event_id")
    snapshot = event.get("repository_snapshot_before")
    if not isinstance(event_id, str) or not isinstance(snapshot, dict):
        return None
    gate_directory = f"docs/control/execution/goal-gates/{event_id}"
    state_path = f"{gate_directory}/19-REPOSITORY_STATE.log"
    repository_state = _load_exact_json(root, state_path)
    if (
        not isinstance(repository_state, dict)
        or continuation._validate_repository_payload(
            label="FP047 implementation start gate",
            event_id=event_id,
            payload=repository_state,
            snapshot=snapshot,
        )
    ):
        return None
    dirty = repository_state.get("dirty_snapshot")
    paths = dirty.get("paths") if isinstance(dirty, dict) else None
    if (
        not isinstance(paths, list)
        or len(paths) != dirty.get("dirty_path_count")
        or any(not isinstance(row, dict) for row in paths)
        or len({row.get("path") for row in paths}) != len(paths)
    ):
        return None
    result: dict[str, str] = {}
    for row in paths:
        relative = row.get("path")
        worktree = row.get("worktree")
        if (
            row.get("path_role") != "CURRENT"
            or not isinstance(relative, str)
            or not isinstance(worktree, dict)
            or worktree.get("state") != "PRESENT"
            or worktree.get("type") != "REGULAR_FILE"
        ):
            continue
        after_sha256 = worktree.get("sha256")
        if (
            not isinstance(after_sha256, str)
            or continuation.SHA256_RE.fullmatch(after_sha256) is None
        ):
            return None
        result[relative] = after_sha256
    return result if result else None


def _fp047_sealed_product_successor_artifacts(
    root: Path,
    checkpoint: dict[str, Any],
) -> dict[str, tuple[str, str]] | None:
    """Return exact FP047 transitions after its completion and start seal."""
    if not _fp047_is_declared_complete(checkpoint):
        return {}
    start_artifacts = _fp047_successful_start_snapshot_artifacts(
        root,
        checkpoint,
    )
    if (
        not _fp047_completion_transition_matches(root, checkpoint)
        or start_artifacts is None
    ):
        return None
    implementation_path = FP047_RESULT_EVIDENCE_CONTRACT[0][1]
    implementation = _load_exact_json(root, implementation_path)
    changed = (
        implementation.get("changed_artifacts")
        if isinstance(implementation, dict)
        else None
    )
    if (
        not isinstance(changed, list)
        or len(changed) != len(FP047_ACTIVE_SCOPE_PATHS)
        or [
            record.get("path") if isinstance(record, dict) else None
            for record in changed
        ]
        != list(FP047_ACTIVE_SCOPE_PATHS)
    ):
        return None
    result: dict[str, tuple[str, str]] = {}
    added_count = 0
    required_fields = {
        "path",
        "before_sha256",
        "after_sha256",
        "before_source",
        "change_kind",
    }
    for record in changed:
        if not isinstance(record, dict) or set(record) != required_fields:
            return None
        relative = record["path"]
        before_sha256 = record["before_sha256"]
        after_sha256 = record["after_sha256"]
        if (
            not isinstance(relative, str)
            or not isinstance(after_sha256, str)
            or continuation.SHA256_RE.fullmatch(after_sha256) is None
        ):
            return None
        if record["change_kind"] == "ADDED":
            if (
                before_sha256 is not None
                or record["before_source"] != "GATE_PINNED_HEAD_ABSENT"
                or relative in start_artifacts
            ):
                return None
            added_count += 1
            continue
        if (
            record["change_kind"] != "MODIFIED"
            or record["before_source"] != "GATE_DIRTY_SNAPSHOT"
            or not isinstance(before_sha256, str)
            or continuation.SHA256_RE.fullmatch(before_sha256) is None
            or before_sha256 == after_sha256
            or start_artifacts.get(relative) != before_sha256
            or relative in result
        ):
            return None
        result[relative] = before_sha256, after_sha256
    return result if len(result) == 26 and added_count == 5 else None


def _fp047_start_snapshot_successor(
    root: Path,
    checkpoint: dict[str, Any],
    relative: str,
    predecessor_after_sha256: str,
) -> tuple[str, str] | None:
    if (
        continuation.SHA256_RE.fullmatch(predecessor_after_sha256) is None
        or relative not in FP047_ACTIVE_SCOPE_PATHS
        or (
            artifacts := _fp047_successful_start_snapshot_artifacts(
                root,
                checkpoint,
            )
        )
        is None
    ):
        return None
    snapshot_after_sha256 = artifacts.get(relative)
    live_path = _exact_repo_file(root, relative)
    if (
        snapshot_after_sha256 != predecessor_after_sha256
        or live_path is None
    ):
        return None
    return predecessor_after_sha256, continuation.sha256_file(live_path)


def _fp047_gate_remediation_after_sha256(
    root: Path,
    checkpoint: dict[str, Any],
    predecessor_after_sha256: str,
) -> str | None:
    state = checkpoint.get("goal_execution")
    status_by_goal = (
        state.get("status_by_goal")
        if isinstance(state, dict)
        else None
    )
    history = (
        state.get("transition_history")
        if isinstance(state, dict)
        else None
    )
    runner = _exact_repo_file(root, FP047_GATE_REMEDIATION_RUNNER_PATH)
    if (
        predecessor_after_sha256 != FP047_GATE_REMEDIATION_PRE_SHA256
        or not isinstance(status_by_goal, dict)
        or status_by_goal.get(FP012_GOAL_ID) != "COMPLETE_AT_TARGET"
        or not (
            (
                status_by_goal.get(FP047_GOAL_ID) in {"READY", "IN_PROGRESS"}
                and state.get("focus_goal_id") == FP047_GOAL_ID
            )
            or (
                status_by_goal.get(FP047_GOAL_ID) == "COMPLETE_AT_TARGET"
                and _fp047_completion_transition_matches(root, checkpoint)
            )
        )
        or not isinstance(history, list)
        or not history
        or runner is None
        or not _fp047_failed_gate_remediation_evidence_matches(root)
    ):
        return None
    if status_by_goal.get(FP047_GOAL_ID) == "READY":
        tail = history[-1]
        if (
            continuation.sha256_file(runner)
            != FP047_GATE_REMEDIATION_POST_SHA256
            or
            not isinstance(tail, dict)
            or tail.get("event_id") != FP047_READY_EVENT_ID
            or tail.get("event_type") != "GOAL_READY"
            or tail.get("subject_goal_id") != FP047_GOAL_ID
            or tail.get("to_status") != "READY"
            or tail.get("event_sha256")
            != continuation.event_sha256(tail)
        ):
            return None
    elif not _fp047_successful_start_snapshot_matches(root, checkpoint):
        return None
    return FP047_GATE_REMEDIATION_POST_SHA256


def _fp016_successor_product_artifacts(
    root: Path,
    checkpoint: dict[str, Any],
    *,
    successor_artifacts: dict[str, tuple[str, str]] | None = None,
) -> dict[str, tuple[str, str]] | None:
    state = checkpoint.get("goal_execution")
    if not isinstance(state, dict) or not _fp016_is_declared_complete(
        checkpoint
    ):
        return None
    if (
        successor_artifacts is None
        and _fp012_is_declared_complete(checkpoint)
    ):
        successor_artifacts = _fp012_successor_product_artifacts(
            root,
            checkpoint,
        )
        if successor_artifacts is None:
            return None
    status_by_goal = state.get("status_by_goal")
    inventory = state.get("dynamic_goal_inventory")
    fp016 = (
        inventory.get(FP016_GOAL_ID)
        if isinstance(inventory, dict)
        else None
    )
    goal_path = _exact_repo_file(root, FP016_GOAL_RELATIVE)
    completion_binding = _binding_by_role(checkpoint, FP016_COMPLETION_ROLE)
    completion_roles = state.get("completion_evidence_by_goal")
    binding_projection = (
        {
            key: completion_binding.get(key)
            for key in ("role", "document_id", "path", "file_sha256")
        }
        if isinstance(completion_binding, dict)
        else None
    )
    if (
        not isinstance(status_by_goal, dict)
        or status_by_goal.get(FP016_GOAL_ID) != "COMPLETE_AT_TARGET"
        or status_by_goal.get(FP012_GOAL_ID)
        not in {"READY", "IN_PROGRESS", "COMPLETE_AT_TARGET"}
        or not isinstance(fp016, dict)
        or fp016.get("path") != FP016_GOAL_RELATIVE
        or fp016.get("sha256") != FP016_GOAL_SHA256
        or goal_path is None
        or continuation.sha256_file(goal_path) != FP016_GOAL_SHA256
        or binding_projection
        != FP016_CANONICAL_BINDINGS[FP016_COMPLETION_ROLE]
        or not _sha256_binding_matches(
            root,
            completion_binding,
            expected_path=FP016_COMPLETION_PATH,
        )
        or not isinstance(completion_roles, dict)
        or completion_roles.get(FP016_GOAL_ID) != [FP016_COMPLETION_ROLE]
        or not _fp016_completion_transition_matches(
            root,
            checkpoint,
            completion_binding,
        )
    ):
        return None
    receipt = _load_exact_json(root, FP016_COMPLETION_PATH)
    expected_receipt = {
        "schema_version": "1.0",
        "document_id": FP016_COMPLETION_DOCUMENT_ID,
        "evidence_type": "WORK_ITEM_EXECUTION_RECEIPT",
        "status": "ACCEPTED",
        "result": "PASS",
        "target_goal_id": FP016_GOAL_ID,
        "target_goal_content_sha256": FP016_GOAL_SHA256,
        "work_item_id": FP016_WORK_ITEM_ID,
        "source_policy_ids": ["FP-016"],
        "gap_ids": ["GAP-025"],
        "target_completion_level": (
            "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
        ),
        "completion_boundary": FP014_COMPLETION_BOUNDARY,
    }
    expected_start_gate = {
        "document_id": FP016_START_GATE_DOCUMENT_ID,
        "path": FP016_START_GATE_PATH,
        "file_sha256": FP016_START_GATE_SHA256,
    }
    expected_session = {
        "sequence": 24,
        "event_id": FP016_START_EVENT_ID,
        "event_type": "GOAL_STARTED",
        "event_sha256": FP016_START_EVENT_SHA256,
    }
    if (
        receipt is None
        or continuation.sha256_file(
            _exact_repo_file(root, FP016_COMPLETION_PATH)
        )
        != FP016_COMPLETION_SHA256
        or any(
            receipt.get(key) != expected
            for key, expected in expected_receipt.items()
        )
        or receipt.get("implementation_start_gate_binding")
        != expected_start_gate
        or not _sha256_binding_matches(
            root,
            expected_start_gate,
            expected_path=FP016_START_GATE_PATH,
        )
        or receipt.get("execution_session_event") != expected_session
        or receipt.get("execution_start_event_sha256")
        != FP016_START_EVENT_SHA256
    ):
        return None
    result_evidence = receipt.get("result_evidence")
    expected_result_evidence = [
        {
            "kind": kind,
            "path": path,
            "sha256": FP016_RESULT_SHA256_BY_KIND[kind],
        }
        for kind, path in FP016_RESULT_PATH_BY_KIND.items()
    ]
    if (
        result_evidence != expected_result_evidence
        or any(
            not _sha256_binding_matches(
                root,
                binding,
                expected_path=binding.get("path"),
            )
            for binding in expected_result_evidence
        )
    ):
        return None
    manifest = receipt.get("output_evidence_manifest")
    if (
        not isinstance(manifest, list)
        or any(not isinstance(row, dict) for row in manifest)
        or continuation.canonical_json_sha256(manifest)
        != receipt.get("output_evidence_manifest_sha256")
        or any(
            not _sha256_binding_matches(
                root,
                row,
                expected_path=row.get("path"),
            )
            for row in manifest
        )
    ):
        return None
    implementation = _load_exact_json(
        root,
        FP016_RESULT_PATH_BY_KIND["IMPLEMENTATION_RECORD"],
    )
    changed = (
        implementation.get("changed_artifacts")
        if isinstance(implementation, dict)
        else None
    )
    if (
        implementation is None
        or implementation.get("goal_id") != FP016_GOAL_ID
        or implementation.get("kind") != "IMPLEMENTATION_RECORD"
        or implementation.get("status") != "PASS"
        or not isinstance(changed, list)
        or len(changed) != len(FP016_IMPLEMENTATION_PATHS)
        or any(not isinstance(row, dict) for row in changed)
        or tuple(row.get("path") for row in changed)
        != FP016_IMPLEMENTATION_PATHS
        or implementation.get("implementation_content_set_sha256")
        != FP016_IMPLEMENTATION_CONTENT_SET_SHA256
        or _implementation_content_set_sha256(changed)
        != FP016_IMPLEMENTATION_CONTENT_SET_SHA256
    ):
        return None
    result: dict[str, tuple[str, str]] = {}
    for record in changed:
        relative = record.get("path")
        before_sha256 = record.get("before_sha256")
        after_sha256 = record.get("after_sha256")
        change_kind = record.get("change_kind")
        path = _exact_repo_file(root, relative)
        successor = (
            successor_artifacts.get(relative)
            if isinstance(successor_artifacts, dict)
            and isinstance(relative, str)
            else None
        )
        if successor is not None and successor[0] != after_sha256:
            successor = None
        canonical_successor = successor
        if (
            successor is None
            and relative == FP047_GATE_REMEDIATION_RUNNER_PATH
        ):
            remediated_after_sha256 = (
                _fp047_gate_remediation_after_sha256(
                    root,
                    checkpoint,
                    after_sha256,
                )
            )
            if remediated_after_sha256 is not None:
                successor = after_sha256, remediated_after_sha256
                if status_by_goal.get(FP047_GOAL_ID) in {
                    "READY",
                    "COMPLETE_AT_TARGET",
                }:
                    canonical_successor = successor
        if (
            successor is None
            and isinstance(relative, str)
            and isinstance(after_sha256, str)
        ):
            successor = _fp047_start_snapshot_successor(
                root,
                checkpoint,
                relative,
                after_sha256,
            )
        if (
            path is None
            or not isinstance(after_sha256, str)
            or not continuation.SHA256_RE.fullmatch(after_sha256)
            or (
                successor is None
                and continuation.sha256_file(path) != after_sha256
            )
            or (successor is not None and successor[0] != after_sha256)
            or change_kind not in {"ADDED", "MODIFIED"}
        ):
            return None
        if change_kind == "ADDED":
            if (
                before_sha256 is not None
                or record.get("before_source")
                != "GATE_PINNED_HEAD_ABSENT"
            ):
                return None
            continue
        if (
            not isinstance(before_sha256, str)
            or not continuation.SHA256_RE.fullmatch(before_sha256)
            or before_sha256 == after_sha256
            or record.get("before_source")
            not in {"GATE_DIRTY_SNAPSHOT", "GATE_PINNED_HEAD"}
        ):
            return None
        result[relative] = (
            before_sha256,
            (
                canonical_successor[1]
                if canonical_successor is not None
                else after_sha256
            ),
        )
    return result if len(result) == 10 else None


def _fp014_successor_product_artifacts(
    root: Path,
    checkpoint: dict[str, Any],
    *,
    require_live_successors: bool = True,
) -> dict[str, tuple[str, str]] | None:
    state = checkpoint.get("goal_execution")
    fp046_required = _fp046_completion_is_present(checkpoint)
    fp046_errors: list[str] = []
    fp046_artifacts: dict[str, tuple[str, str]] = {}
    if fp046_required:
        fp046_errors, _, fp046_artifacts = (
            validate_fp046_r014_successor_authority(root, checkpoint)
        )
    if fp046_required and fp046_errors:
        return None
    fp012_required = _fp012_is_declared_complete(checkpoint)
    fp012_artifacts = (
        _fp012_successor_product_artifacts(
            root,
            checkpoint,
            successor_artifacts=fp046_artifacts,
        )
        if fp012_required
        else {}
    )
    if fp012_required and fp012_artifacts is None:
        return None
    fp016_required = _fp016_is_declared_complete(checkpoint)
    fp016_artifacts = (
        _fp016_successor_product_artifacts(
            root,
            checkpoint,
            successor_artifacts={
                **fp046_artifacts,
                **(
                    fp012_artifacts
                    if isinstance(fp012_artifacts, dict)
                    else {}
                ),
            },
        )
        if fp016_required
        else {}
    )
    if fp016_required and fp016_artifacts is None:
        return None
    fp048_required = _fp048_android_report_successor_is_declared(checkpoint)
    fp048_artifacts = (
        _fp048_sealed_product_successor_artifacts(root, checkpoint)
        if fp048_required
        else {}
    )
    if fp048_required and fp048_artifacts is None:
        return None
    status_by_goal = (
        state.get("status_by_goal")
        if isinstance(state, dict)
        else None
    )
    inventory = (
        state.get("dynamic_goal_inventory")
        if isinstance(state, dict)
        else None
    )
    fp014 = (
        inventory.get(FP014_GOAL_ID)
        if isinstance(inventory, dict)
        else None
    )
    goal_path = _exact_repo_file(root, FP014_GOAL_PATH)
    if (
        not isinstance(status_by_goal, dict)
        or status_by_goal.get(FP014_GOAL_ID) != "COMPLETE_AT_TARGET"
        or status_by_goal.get(FP016_GOAL_ID)
        not in {"READY", "IN_PROGRESS", "COMPLETE_AT_TARGET"}
        or not isinstance(fp014, dict)
        or fp014.get("path") != FP014_GOAL_PATH
        or fp014.get("sha256") != FP014_GOAL_SHA256
        or goal_path is None
        or continuation.sha256_file(goal_path) != FP014_GOAL_SHA256
    ):
        return None
    completion_binding = _binding_by_role(checkpoint, FP014_COMPLETION_ROLE)
    completion_roles = state.get("completion_evidence_by_goal")
    completion_projection = (
        {
            key: completion_binding.get(key)
            for key in ("role", "document_id", "path", "file_sha256")
        }
        if isinstance(completion_binding, dict)
        else None
    )
    if (
        completion_projection != FP014_CANONICAL_BINDINGS[FP014_COMPLETION_ROLE]
        or not _sha256_binding_matches(
            root,
            completion_binding,
            expected_path=FP014_COMPLETION_PATH,
        )
        or not isinstance(completion_roles, dict)
        or completion_roles.get(FP014_GOAL_ID) != [FP014_COMPLETION_ROLE]
        or not _fp014_completion_transition_matches(
            root,
            checkpoint,
            completion_binding,
        )
    ):
        return None
    documents = _fp014_pinned_documents(root)
    if documents is None:
        return None
    receipt = documents[FP014_COMPLETION_PATH]
    expected_receipt = {
        "schema_version": "1.0",
        "document_id": FP014_COMPLETION_DOCUMENT_ID,
        "evidence_type": "WORK_ITEM_EXECUTION_RECEIPT",
        "status": "ACCEPTED",
        "result": "PASS",
        "target_goal_id": FP014_GOAL_ID,
        "target_goal_content_sha256": FP014_GOAL_SHA256,
        "work_item_id": FP014_WORK_ITEM_ID,
        "source_policy_ids": ["FP-014"],
        "gap_ids": ["GAP-023"],
        "target_completion_level": (
            "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
        ),
        "completion_boundary": FP014_COMPLETION_BOUNDARY,
    }
    start_gate_binding = receipt.get("implementation_start_gate_binding")
    if (
        any(
            receipt.get(key) != expected
            for key, expected in expected_receipt.items()
        )
        or not _fp014_execution_session_matches(state, receipt)
        or start_gate_binding
        != {
            "document_id": FP014_START_GATE_DOCUMENT_ID,
            "path": FP014_START_GATE_PATH,
            "file_sha256": FP014_START_GATE_SHA256,
        }
        or not _sha256_binding_matches(
            root,
            start_gate_binding,
            expected_path=FP014_START_GATE_PATH,
        )
        or not _fp014_review_chain_matches(root, receipt, documents)
    ):
        return None
    result_evidence = receipt.get("result_evidence")
    if (
        not isinstance(result_evidence, list)
        or result_evidence
        != [
            {
                "kind": kind,
                "path": path,
                "sha256": FP014_RESULT_SHA256_BY_KIND[kind],
            }
            for kind, path in FP014_RESULT_PATH_BY_KIND.items()
        ]
    ):
        return None
    for kind, path in FP014_RESULT_PATH_BY_KIND.items():
        document = documents[path]
        if (
            document.get("schema_version") != "1.0"
            or document.get("document_id")
            != FP014_RESULT_DOCUMENT_ID_BY_KIND[kind]
            or document.get("goal_id") != FP014_GOAL_ID
            or document.get("kind") != kind
            or document.get("status") != "PASS"
        ):
            return None
    manifest = receipt.get("output_evidence_manifest")
    if (
        not isinstance(manifest, list)
        or [row.get("path") for row in manifest if isinstance(row, dict)]
        != list(FP014_OUTPUT_EVIDENCE_PATHS)
        or continuation.canonical_json_sha256(manifest)
        != receipt.get("output_evidence_manifest_sha256")
    ):
        return None
    for row in manifest:
        if (
            not isinstance(row, dict)
            or not _sha256_binding_matches(
                root,
                row,
                expected_path=row.get("path"),
            )
        ):
            return None
    implementation = documents[
        FP014_RESULT_PATH_BY_KIND["IMPLEMENTATION_RECORD"]
    ]
    changed = implementation.get("changed_artifacts")
    if (
        not isinstance(changed, list)
        or len(changed) != 11
        or any(not isinstance(row, dict) for row in changed)
        or tuple(row.get("path") for row in changed)
        != FP014_IMPLEMENTATION_PATHS
        or implementation.get("implementation_content_set_sha256")
        != FP014_IMPLEMENTATION_CONTENT_SET_SHA256
        or _implementation_content_set_sha256(changed)
        != FP014_IMPLEMENTATION_CONTENT_SET_SHA256
        or implementation.get("completion_boundary") != FP014_DETAILED_BOUNDARY
    ):
        return None
    start_state = _fp014_start_repository_state_snapshot(
        root,
        start_gate_binding,
    )
    if start_state is None:
        return None
    start_snapshot, start_head_commit = start_state
    result: dict[str, tuple[str, str]] = {}
    for record in changed:
        relative = record.get("path")
        before_sha256 = record.get("before_sha256")
        after_sha256 = record.get("after_sha256")
        before_source = record.get("before_source")
        change_kind = record.get("change_kind")
        path = _exact_repo_file(root, relative)
        successor = (
            fp016_artifacts.get(relative)
            if isinstance(fp016_artifacts, dict)
            and isinstance(relative, str)
            else None
        )
        if (
            successor is None
            and isinstance(fp012_artifacts, dict)
            and isinstance(relative, str)
        ):
            successor = fp012_artifacts.get(relative)
        canonical_successor = successor
        if (
            successor is None
            and isinstance(relative, str)
            and isinstance(after_sha256, str)
        ):
            successor = _fp047_start_snapshot_successor(
                root,
                checkpoint,
                relative,
                after_sha256,
            )
        fp048_successor = (
            fp048_artifacts.get(relative)
            if isinstance(fp048_artifacts, dict)
            and isinstance(relative, str)
            else None
        )
        if fp048_successor is not None:
            if successor is None and fp048_successor[0] == after_sha256:
                successor = fp048_successor
                canonical_successor = fp048_successor
            elif (
                successor is not None
                and successor[1] == fp048_successor[0]
            ):
                successor = successor[0], fp048_successor[1]
                canonical_successor = successor
        if (
            path is None
            or not isinstance(after_sha256, str)
            or not continuation.SHA256_RE.fullmatch(after_sha256)
            or (
                successor is None
                and require_live_successors
                and continuation.sha256_file(path) != after_sha256
            )
            or (successor is not None and successor[0] != after_sha256)
            or change_kind not in {"ADDED", "MODIFIED"}
        ):
            return None
        start_worktree = start_snapshot.get(relative)
        if change_kind == "ADDED":
            head_path = (
                _fp013_start_head_path_sha256(
                    root,
                    start_head_commit,
                    relative,
                )
                if start_worktree is None
                else None
            )
            if (
                before_sha256 is not None
                or before_source != "GATE_PINNED_HEAD_ABSENT"
                or start_worktree is not None
                or head_path != (False, None)
            ):
                return None
            continue
        if (
            not isinstance(before_sha256, str)
            or not continuation.SHA256_RE.fullmatch(before_sha256)
            or before_sha256 == after_sha256
        ):
            return None
        if start_worktree is None:
            head_path = _fp013_start_head_path_sha256(
                root,
                start_head_commit,
                relative,
            )
            if (
                head_path is None
                or not head_path[0]
                or head_path[1] != before_sha256
                or before_source != "GATE_PINNED_HEAD"
            ):
                return None
        elif (
            start_worktree.get("state") != "PRESENT"
            or start_worktree.get("sha256") != before_sha256
            or before_source != "GATE_DIRTY_SNAPSHOT"
        ):
            return None
        result[relative] = (
            before_sha256,
            (
                canonical_successor[1]
                if canonical_successor is not None
                else after_sha256
            ),
        )
    if len(result) != 9:
        return None
    if isinstance(fp016_artifacts, dict):
        for relative, transition in fp016_artifacts.items():
            if relative in result:
                result[relative] = result[relative][0], transition[1]
            else:
                result[relative] = transition
    if isinstance(fp012_artifacts, dict):
        for relative, transition in fp012_artifacts.items():
            if relative in result:
                if result[relative][1] == transition[0]:
                    result[relative] = result[relative][0], transition[1]
                elif result[relative][1] != transition[1]:
                    return None
            else:
                result[relative] = transition
    return result


def _fp014_is_declared_complete(checkpoint: dict[str, Any]) -> bool:
    state = checkpoint.get("goal_execution")
    status = (
        state.get("status_by_goal")
        if isinstance(state, dict)
        else None
    )
    history = (
        state.get("transition_history")
        if isinstance(state, dict)
        else None
    )
    return bool(
        (
            isinstance(status, dict)
            and status.get(FP014_GOAL_ID) == "COMPLETE_AT_TARGET"
        )
        or _binding_by_role(checkpoint, FP014_COMPLETION_ROLE) is not None
        or (
            isinstance(history, list)
            and any(
                isinstance(event, dict)
                and (
                    event.get("subject_goal_id") == FP014_GOAL_ID
                    or event.get("produced_by_goal_id") == FP014_GOAL_ID
                )
                and event.get("sequence", 0) >= 20
                for event in history
            )
        )
    )


def validate_fp014_canonical_completion(
    root: Path,
    checkpoint: dict[str, Any],
) -> list[str]:
    if not _fp014_is_declared_complete(checkpoint):
        return []
    if (
        _fp014_successor_product_artifacts(
            root,
            checkpoint,
            require_live_successors=False,
        )
        is None
    ):
        return ["FP014 canonical completion package or seq20-23 tail differs"]
    return []


def validate_fp047_canonical_completion(
    root: Path,
    checkpoint: dict[str, Any],
) -> list[str]:
    if not _fp047_is_declared_complete(checkpoint):
        return []
    if not _fp047_completion_transition_matches(root, checkpoint):
        return ["FP047 canonical completion package or seq37-38 tail differs"]
    return []


def _successor_lineage_reaches_live(
    root: Path,
    archive: dict[str, Any],
    historical_artifact: tuple[str, str],
    *,
    checkpoint: dict[str, Any],
    fp011_live_artifacts: dict[str, tuple[str, str]],
    fp011_transitions: dict[str, tuple[str, str]],
    fp013_artifacts: dict[str, tuple[str, str]],
    fp015_artifacts: dict[str, tuple[str, str]],
    fp014_artifacts: dict[str, tuple[str, str]],
    fp047_artifacts: dict[str, tuple[str, str]],
    fp048_artifacts: dict[str, tuple[str, str]],
    fp046_artifacts: dict[str, tuple[str, str]] | None = None,
    resource_pilot_current_bindings: (
        dict[str, tuple[str, str]] | None
    ) = None,
) -> bool:
    relative = historical_artifact[0]
    lineage_head = _historical_artifact_lineage_head(
        root,
        archive,
        relative,
    )
    if lineage_head is None:
        return False
    fp013 = fp013_artifacts.get(relative)
    fp015 = fp015_artifacts.get(relative)
    fp014 = fp014_artifacts.get(relative)
    fp047 = fp047_artifacts.get(relative)
    fp048 = fp048_artifacts.get(relative)
    fp046 = (fp046_artifacts or {}).get(relative)
    fp011 = fp011_transitions.get(relative)
    if fp013 is None and fp015 is None and fp014 is None:
        if fp011 is None:
            fp011 = fp011_live_artifacts.get(relative)
    if fp011 is not None:
        if fp011[0] != lineage_head:
            return False
        lineage_head = fp011[1]
    if fp013 is not None:
        if fp013[0] != lineage_head:
            return False
        lineage_head = fp013[1]
    if fp015 is not None:
        if fp015[0] != lineage_head:
            return False
        lineage_head = fp015[1]
    if fp014 is not None:
        if fp014[0] != lineage_head:
            return False
        lineage_head = fp014[1]
    if fp047 is not None:
        if lineage_head == fp047[0]:
            lineage_head = fp047[1]
    if fp048 is not None:
        if lineage_head == fp048[0]:
            lineage_head = fp048[1]
    if fp046 is not None:
        if lineage_head == fp046[0]:
            lineage_head = fp046[1]
    live_path = _exact_repo_file(root, relative)
    if live_path is None:
        return False
    live_sha256 = continuation.sha256_file(live_path)
    if live_sha256 == lineage_head:
        return True
    # Preserve the pre-existing exact FP047 start-snapshot overlay for paths
    # that FP048 did not supersede.  The explicit FP047 edge above is still
    # mandatory and validated; this only retains its previously accepted
    # start-snapshot-to-current projection after that intermediate edge.
    snapshot_predecessor = (
        fp047[0]
        if (
            fp047 is not None
            and fp048 is None
            and lineage_head == fp047[1]
        )
        else lineage_head
    )
    snapshot_successor = _fp047_start_snapshot_successor(
        root,
        checkpoint,
        relative,
        snapshot_predecessor,
    )
    if (
        snapshot_successor is not None
        and live_sha256 == snapshot_successor[1]
    ):
        return True
    resource_successor = _resource_pilot_current_state_successor_bridge(
        root,
        checkpoint,
        relative,
        lineage_head,
        current_bindings=resource_pilot_current_bindings,
    )
    return bool(
        resource_successor is not None
        and live_sha256 == resource_successor[1]
    )


def fp011_successor_artifact_reaches_live(
    root: Path,
    *,
    checkpoint: dict[str, Any],
    archive: dict[str, Any],
    goal_id: str,
    relative_path: str,
    historical_sha256: str,
) -> bool:
    """Verify one historical artifact through completed live successors."""
    historical = _historical_changed_artifacts(
        root,
        archive,
        goal_id=goal_id,
    )
    if (
        historical is None
        or len(
            [
                record
                for record in historical
                if record.get("path") == relative_path
                and record.get("after_sha256") == historical_sha256
            ]
        )
        != 1
    ):
        return False
    fp011_live_artifacts = _fp011_successor_product_artifacts(
        root,
        checkpoint,
        archive,
    )
    fp046_errors, _, fp046_artifacts = (
        validate_fp046_r014_successor_authority(root, checkpoint)
    )
    if fp046_errors:
        return False
    fp014_required = _fp014_is_declared_complete(checkpoint)
    fp014_artifacts = (
        _fp014_successor_product_artifacts(
            root,
            checkpoint,
            require_live_successors=not bool(fp046_artifacts),
        )
        if fp014_required
        else {}
    )
    if fp014_required and fp014_artifacts is None:
        return False
    fp015_required = _fp015_is_declared_complete(checkpoint)
    fp015_artifacts = (
        _fp015_successor_product_artifacts(
            root,
            checkpoint,
            successor_artifacts=(
                fp014_artifacts
                if isinstance(fp014_artifacts, dict)
                else {}
            ),
            require_live_successors=not bool(fp046_artifacts),
        )
        if fp015_required
        else {}
    )
    if fp015_required and fp015_artifacts is None:
        return False
    fp013_artifacts = _fp013_successor_product_artifacts(
        root,
        checkpoint,
        require_live_after=not bool(
            fp015_artifacts or fp014_artifacts or fp046_artifacts
        ),
    )
    fp011_transitions = (
        _fp011_bound_product_artifacts(
            root,
            checkpoint,
            archive,
            require_live_after=False,
        )
        if fp013_artifacts or fp015_artifacts or fp014_artifacts or fp046_artifacts
        else {}
    )
    fp048_required = _fp048_android_report_successor_is_declared(
        checkpoint
    )
    fp048_artifacts = (
        _fp048_sealed_product_successor_artifacts(root, checkpoint)
        if fp048_required
        else {}
    )
    if fp048_required and fp048_artifacts is None:
        return False
    fp047_required = _fp047_is_declared_complete(checkpoint)
    fp047_artifacts = (
        _fp047_sealed_product_successor_artifacts(root, checkpoint)
        if fp047_required
        else {}
    )
    if fp047_required and fp047_artifacts is None:
        return False
    resource_errors, resource_pilot_current_bindings = (
        validate_resource_pilot_current_state_successor_binding(root)
    )
    if resource_errors:
        resource_pilot_current_bindings = {}
    return _successor_lineage_reaches_live(
        root,
        archive,
        (relative_path, historical_sha256),
        checkpoint=checkpoint,
        fp011_live_artifacts=fp011_live_artifacts,
        fp011_transitions=fp011_transitions,
        fp013_artifacts=fp013_artifacts,
        fp015_artifacts=fp015_artifacts,
        fp014_artifacts=(
            fp014_artifacts
            if isinstance(fp014_artifacts, dict)
            else {}
        ),
        fp047_artifacts=(
            fp047_artifacts
            if isinstance(fp047_artifacts, dict)
            else {}
        ),
        fp048_artifacts=(
            fp048_artifacts
            if isinstance(fp048_artifacts, dict)
            else {}
        ),
        fp046_artifacts=fp046_artifacts,
        resource_pilot_current_bindings=resource_pilot_current_bindings,
    )


def filter_frozen_v23_successor_errors(
    root: Path,
    errors: list[str],
    *,
    checkpoint: dict[str, Any] | None = None,
    archive: dict[str, Any] | None = None,
) -> list[str]:
    """Allow only byte-pinned control or verified successor product changes."""
    dynamic_errors = [
        error
        for error in errors
        if FROZEN_CHANGED_ARTIFACT_ERROR_RE.fullmatch(error)
    ]
    rtm_errors = [
        error
        for error in errors
        if FROZEN_REQUIREMENTS_TRACEABILITY_ERROR_RE.fullmatch(error)
    ]
    successor_errors_present = bool(dynamic_errors or rtm_errors)
    if successor_errors_present and checkpoint is None:
        checkpoint = _load_exact_json(
            root,
            CHECKPOINT_RELATIVE.as_posix(),
        )
    if successor_errors_present and archive is None:
        archive = _load_exact_json(
            root,
            V23_ARCHIVE_RELATIVE.as_posix(),
        )
    allowed_rtm_errors = (
        _fp048_archived_requirements_traceability_successor_errors(
            root,
            checkpoint,
            archive,
        )
        | _fp008_archived_requirements_traceability_successor_errors(
            root,
            checkpoint,
            archive,
        )
        | _fp046_archived_requirements_traceability_successor_errors(
            root,
            checkpoint,
            archive,
        )
        if (
            rtm_errors
            and isinstance(checkpoint, dict)
            and isinstance(archive, dict)
        )
        else set()
    )
    fp046_required = bool(
        dynamic_errors
        and isinstance(checkpoint, dict)
        and _fp046_completion_is_present(checkpoint)
    )
    fp046_errors: list[str] = []
    fp046_artifacts: dict[str, tuple[str, str]] = {}
    if fp046_required:
        fp046_errors, _, fp046_artifacts = (
            validate_fp046_r014_successor_authority(root, checkpoint)
        )
    fp011_live_artifacts = (
        _fp011_successor_product_artifacts(root, checkpoint, archive)
        if (
            dynamic_errors
            and isinstance(checkpoint, dict)
            and isinstance(archive, dict)
        )
        else {}
    )
    fp014_required = bool(
        dynamic_errors
        and isinstance(checkpoint, dict)
        and _fp014_is_declared_complete(checkpoint)
    )
    fp014_artifacts = (
        _fp014_successor_product_artifacts(
            root,
            checkpoint,
            require_live_successors=not bool(fp046_artifacts),
        )
        if fp014_required
        else {}
    )
    fp015_required = bool(
        dynamic_errors
        and isinstance(checkpoint, dict)
        and _fp015_is_declared_complete(checkpoint)
    )
    fp015_artifacts = (
        _fp015_successor_product_artifacts(
            root,
            checkpoint,
            successor_artifacts=(
                fp014_artifacts
                if isinstance(fp014_artifacts, dict)
                else {}
            ),
            require_live_successors=not bool(fp046_artifacts),
        )
        if fp015_required
        else {}
    )
    fp013_artifacts = (
        _fp013_successor_product_artifacts(
            root,
            checkpoint,
            require_live_after=not bool(
                fp015_artifacts or fp014_artifacts or fp046_artifacts
            ),
        )
        if dynamic_errors and isinstance(checkpoint, dict)
        else {}
    )
    fp011_transitions = (
        _fp011_bound_product_artifacts(
            root,
            checkpoint,
            archive,
            require_live_after=False,
        )
        if (
            (
                fp013_artifacts
                or fp015_artifacts
                or fp014_artifacts
                or fp046_artifacts
            )
            and isinstance(checkpoint, dict)
            and isinstance(archive, dict)
        )
        else {}
    )
    fp047_required = bool(
        dynamic_errors
        and isinstance(checkpoint, dict)
        and _fp047_is_declared_complete(checkpoint)
    )
    fp047_artifacts = (
        _fp047_sealed_product_successor_artifacts(root, checkpoint)
        if fp047_required
        else {}
    )
    fp048_required = bool(
        dynamic_errors
        and isinstance(checkpoint, dict)
        and _fp048_android_report_successor_is_declared(checkpoint)
    )
    fp048_artifacts = (
        _fp048_sealed_product_successor_artifacts(root, checkpoint)
        if fp048_required
        else {}
    )
    resource_pilot_current_bindings: dict[str, tuple[str, str]] = {}
    if dynamic_errors:
        resource_errors, resource_pilot_current_bindings = (
            validate_resource_pilot_current_state_successor_binding(root)
        )
        if resource_errors:
            resource_pilot_current_bindings = {}
    remaining: list[str] = []
    for error in errors:
        if error in allowed_rtm_errors:
            continue
        override = V24_SUCCESSOR_CONTROLLED_FROZEN_ERRORS.get(error)
        if override is not None:
            path = continuation.resolve_repo_file(root, override["path"])
            if (
                path is None
                or continuation.sha256_file(path) != override["sha256"]
                or override["path"]
                not in continuation.EXPECTED_CONTROLLED_PATHS
            ):
                remaining.append(error)
            continue
        match = FROZEN_CHANGED_ARTIFACT_ERROR_RE.fullmatch(error)
        if (
            match is None
            or not isinstance(archive, dict)
            or not isinstance(checkpoint, dict)
            or (
                historical_artifact := _historical_changed_artifact(
                    root,
                    archive,
                    goal_id=match.group("goal_id"),
                    artifact_index=int(match.group("index")),
                )
            )
            is None
            or not _successor_lineage_reaches_live(
                root,
                archive,
                historical_artifact,
                checkpoint=checkpoint,
                fp011_live_artifacts=fp011_live_artifacts,
                fp011_transitions=fp011_transitions,
                fp013_artifacts=fp013_artifacts,
                fp015_artifacts=(
                    fp015_artifacts
                    if isinstance(fp015_artifacts, dict)
                    else {}
                ),
                fp014_artifacts=(
                    fp014_artifacts
                    if isinstance(fp014_artifacts, dict)
                    else {}
                ),
                fp047_artifacts=(
                    fp047_artifacts
                    if isinstance(fp047_artifacts, dict)
                    else {}
                ),
                fp048_artifacts=(
                    fp048_artifacts
                    if isinstance(fp048_artifacts, dict)
                    else {}
                ),
                fp046_artifacts=fp046_artifacts,
                resource_pilot_current_bindings=(
                    resource_pilot_current_bindings
                ),
            )
            or (fp015_required and fp015_artifacts is None)
            or (fp014_required and fp014_artifacts is None)
            or (fp047_required and fp047_artifacts is None)
            or (fp048_required and fp048_artifacts is None)
            or (fp046_required and bool(fp046_errors))
        ):
            remaining.append(error)
    return remaining


def _archived_state(archive: dict[str, Any]) -> dict[str, Any]:
    state = archive.get("goal_execution")
    return state if isinstance(state, dict) else {}


def _expected_goal_path_records(
    archive: dict[str, Any],
) -> dict[str, dict[str, Any]]:
    state = _archived_state(archive)
    result: dict[str, dict[str, Any]] = {}
    imported = state.get("imported_predecessor_goal_bindings")
    if isinstance(imported, dict):
        for goal_id, record in imported.items():
            if isinstance(goal_id, str) and isinstance(record, dict):
                result[goal_id] = dict(record)
    inventory = state.get("dynamic_goal_inventory")
    if isinstance(inventory, dict):
        for goal_id, record in inventory.items():
            if isinstance(goal_id, str) and isinstance(record, dict):
                merged = dict(result.get(goal_id, {}))
                merged.update(record)
                result[goal_id] = merged
    return result


def _completion_event_hashes(
    archive: dict[str, Any],
) -> dict[str, str]:
    state = _archived_state(archive)
    result: dict[str, str] = {}
    imported = state.get("imported_predecessor_goal_bindings")
    if isinstance(imported, dict):
        for goal_id, record in imported.items():
            digest = (
                record.get("completion_event_sha256")
                if isinstance(record, dict)
                else None
            )
            if isinstance(goal_id, str) and isinstance(digest, str):
                result[goal_id] = digest
    history = state.get("transition_history")
    if isinstance(history, list):
        for event in history:
            if (
                not isinstance(event, dict)
                or event.get("event_type")
                not in {"GOAL_COMPLETED", "PACKAGE_COMPLETED"}
            ):
                continue
            digest = event.get("event_sha256")
            changes = event.get("status_changes")
            if not isinstance(digest, str) or not isinstance(changes, dict):
                continue
            for goal_id, status in changes.items():
                if (
                    isinstance(goal_id, str)
                    and status == "COMPLETE_AT_TARGET"
                ):
                    result[goal_id] = digest
    return result


def validate_frozen_v23_semantics(
    root: Path,
    archive_path: Path = V23_ARCHIVE_RELATIVE,
    *,
    checkpoint: dict[str, Any] | None = None,
    archive: dict[str, Any] | None = None,
    canonical_preimages: dict[tuple[str, str], Path] | None = None,
) -> list[str]:
    """Run the frozen v2.3 Goal checker against the archived checkpoint."""
    if canonical_preimages is None:
        preimage_errors, canonical_preimages = (
            validate_canonical_preimage_archive(root)
        )
        if preimage_errors:
            return preimage_errors
    checker_path = continuation.resolve_repo_file(root, V23_CHECKER_RELATIVE)
    if checker_path is None:
        return ["frozen v2.3 Goal checker is missing"]
    expected_checker_hash = continuation.V23_FROZEN_FILE_SHA256[
        V23_CHECKER_RELATIVE.as_posix()
    ]
    if continuation.sha256_file(checker_path) != expected_checker_hash:
        return ["frozen v2.3 Goal checker SHA-256 differs"]
    archive_file = continuation.resolve_repo_file(root, archive_path)
    if archive_file is None:
        return ["frozen v2.3 archive is missing"]
    try:
        archive_relative = archive_file.relative_to(root.resolve())
    except ValueError:
        return ["frozen v2.3 archive is outside the repository"]

    module_name = (
        "_walksafe_goal_graph_v23_frozen_for_v24_"
        + continuation.canonical_json_sha256(
            {"root": root.resolve().as_posix()}
        )[:16]
    )
    spec = importlib.util.spec_from_file_location(module_name, checker_path)
    if spec is None or spec.loader is None:
        return ["frozen v2.3 Goal checker cannot be imported"]
    module = importlib.util.module_from_spec(spec)
    legacy_originals: dict[str, Any] = {}
    module_originals: dict[str, Any] = {}
    try:
        sys.modules[module_name] = module
        spec.loader.exec_module(module)
        module.CHECKPOINT_RELATIVE = archive_relative
        legacy = module.legacy
        for name in ("load_json", "sha256_file", "resolve_safe_repo_file"):
            legacy_originals[name] = getattr(legacy, name)
            module_originals[name] = getattr(module, name)

        def historical_path(path: Path) -> Path:
            return _frozen_preimage_redirect(
                root,
                path,
                canonical_preimages,
            )

        def historical_load_json(path: Path) -> dict[str, Any]:
            return legacy_originals["load_json"](historical_path(path))

        def historical_sha256_file(path: Path) -> str:
            return legacy_originals["sha256_file"](historical_path(path))

        def historical_resolve(
            call_root: Path,
            relative: Any,
        ) -> Path | None:
            resolved = legacy_originals["resolve_safe_repo_file"](
                call_root,
                relative,
            )
            if resolved is None or call_root.resolve() != root.resolve():
                return resolved
            return historical_path(resolved)

        module.load_json = historical_load_json
        module.sha256_file = historical_sha256_file
        module.resolve_safe_repo_file = historical_resolve
        legacy.load_json = historical_load_json
        legacy.sha256_file = historical_sha256_file
        legacy.resolve_safe_repo_file = historical_resolve
        frozen_errors = module.validate(
            root,
            check_continuation=False,
        )
    except Exception as exc:  # fail closed at the historical trust boundary
        return [f"frozen v2.3 semantic validation failed to execute: {exc}"]
    finally:
        if "legacy" in locals():
            for name, value in legacy_originals.items():
                setattr(legacy, name, value)
            for name, value in module_originals.items():
                setattr(module, name, value)
        sys.modules.pop(module_name, None)
    return [
        f"frozen v2.3: {error}"
        for error in filter_frozen_v23_successor_errors(
            root,
            frozen_errors,
            checkpoint=checkpoint,
            archive=archive,
        )
    ]


def validate_manifest_successor_boundary(
    root: Path,
    manifest: dict[str, Any],
    archive: dict[str, Any],
) -> list[str]:
    errors: list[str] = []
    expected_supersedes = {
        "package_id": V23_PACKAGE_ID,
        "plan_version": V23_PLAN_VERSION,
        "activation_status": "ACTIVE",
        "manifest_path": V23_MANIFEST_RELATIVE.as_posix(),
        "manifest_sha256": V23_MANIFEST_SHA256,
        "archived_checkpoint_path": V23_ARCHIVE_RELATIVE.as_posix(),
        "archived_checkpoint_raw_sha256": V23_ARCHIVE_RAW_SHA256,
        "transition_event_count": V23_EVENT_COUNT,
        "transition_history_anchor_sha256": V23_TAIL_SHA256,
        "supersession_record_path": V24_SUPERSESSION_RELATIVE.as_posix(),
    }
    _require_equal(
        errors,
        "v2.4 manifest supersedes binding",
        manifest.get("supersedes"),
        expected_supersedes,
    )
    contract = manifest.get("imported_predecessor_goal_contract")
    if not isinstance(contract, dict):
        errors.append("v2.4 imported predecessor Goal contract is missing")
    else:
        for key, expected in (
            ("source_package_id", V23_PACKAGE_ID),
            ("source_plan_version", V23_PLAN_VERSION),
            ("source_checkpoint_path", V23_ARCHIVE_RELATIVE.as_posix()),
            ("runtime_binding_field", "imported_predecessor_goal_bindings"),
            ("expected_goal_count", 20),
            ("goal_paths_remain_in_predecessor_packages", True),
            ("goal_bytes_must_match_archived_projection", True),
            ("materialization_and_completion_lineage_is_imported", True),
        ):
            _require_equal(
                errors,
                f"v2.4 imported predecessor contract {key}",
                contract.get(key),
                expected,
            )

    protected = manifest.get("protected_files")
    if not isinstance(protected, list):
        errors.append("v2.4 protected file list is missing")
    else:
        protected_map = {
            row.get("path"): row.get("sha256")
            for row in protected
            if isinstance(row, dict)
        }
        expected_protected = V24_NATIVE_PATHS - {
            V24_MANIFEST_RELATIVE.as_posix()
        }
        if set(protected_map) != expected_protected:
            errors.append("v2.4 protected file path set differs")
        for relative in sorted(expected_protected):
            path = continuation.resolve_repo_file(root, relative)
            if path is None:
                errors.append(f"v2.4 protected file is missing: {relative}")
            elif protected_map.get(relative) != continuation.sha256_file(path):
                errors.append(
                    f"v2.4 protected file SHA-256 differs: {relative}"
                )

    record_file = continuation.resolve_repo_file(
        root,
        V24_SUPERSESSION_RELATIVE,
    )
    if record_file is None:
        errors.append("v2.4 active supersession record is missing")
    else:
        try:
            record = continuation.load_json(record_file)
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            errors.append(f"v2.4 supersession record cannot be loaded: {exc}")
        else:
            _require_equal(
                errors,
                "v2.4 supersession record predecessor",
                record.get("superseded_package"),
                expected_supersedes,
            )
            _require_equal(
                errors,
                "v2.4 supersession successor package",
                record.get("successor_package_id"),
                V24_PACKAGE_ID,
            )
            _require_equal(
                errors,
                "v2.4 supersession successor plan",
                record.get("successor_plan_version"),
                V24_PLAN_VERSION,
            )
            frozen = record.get("frozen_control_history")
            if not isinstance(frozen, dict):
                errors.append(
                    "v2.4 supersession frozen control history is missing"
                )
            else:
                expected_frozen = {
                    "builder_path": (
                        "scripts/build_walksafe_goal_graph_v2_3.py"
                    ),
                    "builder_sha256": continuation.V23_FROZEN_FILE_SHA256[
                        "scripts/build_walksafe_goal_graph_v2_3.py"
                    ],
                    "checker_path": (
                        "scripts/check_walksafe_goal_graph_v2_3.py"
                    ),
                    "checker_sha256": continuation.V23_FROZEN_FILE_SHA256[
                        "scripts/check_walksafe_goal_graph_v2_3.py"
                    ],
                    "test_path": "tests/test_walksafe_goal_graph_v2_3.py",
                    "test_sha256": continuation.V23_FROZEN_FILE_SHA256[
                        "tests/test_walksafe_goal_graph_v2_3.py"
                    ],
                    "continuation_checker_path": (
                        "scripts/check_walksafe_project_continuation_v2_3.py"
                    ),
                    "continuation_checker_sha256": (
                        continuation.V23_FROZEN_FILE_SHA256[
                            "scripts/check_walksafe_project_continuation_v2_3.py"
                        ]
                    ),
                    "continuation_test_path": (
                        "tests/test_walksafe_project_continuation_v2_3.py"
                    ),
                    "continuation_test_sha256": (
                        continuation.V23_FROZEN_FILE_SHA256[
                            "tests/test_walksafe_project_continuation_v2_3.py"
                        ]
                    ),
                }
                for key, expected in expected_frozen.items():
                    _require_equal(
                        errors,
                        f"v2.4 frozen control history {key}",
                        frozen.get(key),
                        expected,
                    )

    archived_state = _archived_state(archive)
    if len(archived_state.get("status_by_goal", {})) != 20:
        errors.append("v2.3 archive does not project exactly 20 Goals")
    return errors


def validate_imported_goal_projection(
    root: Path,
    checkpoint: dict[str, Any],
    archive: dict[str, Any],
) -> list[str]:
    """Bind all imported Goal bytes/status/completion lineage to v2.3."""
    errors: list[str] = []
    state = checkpoint.get("goal_execution")
    archived_state = _archived_state(archive)
    if not isinstance(state, dict):
        return ["v2.4 goal_execution is missing"]
    statuses = archived_state.get("status_by_goal")
    actual = state.get("imported_predecessor_goal_bindings")
    if not isinstance(statuses, dict):
        return ["v2.3 status projection is missing"]
    if not isinstance(actual, dict):
        return ["v2.4 imported predecessor Goal bindings are missing"]
    if set(actual) != set(statuses):
        errors.append("v2.4 imported Goal ID set differs from v2.3")
        return errors

    source_records = _expected_goal_path_records(archive)
    completion_hashes = _completion_event_hashes(archive)
    completion_refs = archived_state.get("completion_evidence_by_goal")
    if not isinstance(completion_refs, dict):
        completion_refs = {}
    for goal_id in sorted(statuses):
        record = actual.get(goal_id)
        source = source_records.get(goal_id)
        if not isinstance(record, dict) or not isinstance(source, dict):
            errors.append(f"{goal_id}: imported Goal record is missing")
            continue
        path_value = source.get("path")
        path = continuation.resolve_repo_file(root, path_value)
        expected_sha = (
            continuation.sha256_file(path) if path is not None else None
        )
        if path is None:
            errors.append(f"{goal_id}: predecessor Goal path is missing")
        expected_base = {
            "goal_id": goal_id,
            "path": path_value,
            "sha256": expected_sha,
            "goal_kind": source.get("goal_kind"),
            "status": statuses[goal_id],
            "source_package_id": V23_PACKAGE_ID,
        }
        for key, expected in expected_base.items():
            _require_equal(
                errors,
                f"{goal_id}: imported Goal {key}",
                record.get(key),
                expected,
            )
        if source.get("goal_kind") == "WORK_ITEM":
            _require_equal(
                errors,
                f"{goal_id}: imported materialization lineage",
                record.get("materialized_event_sha256"),
                source.get("materialized_event_sha256"),
            )
        if statuses[goal_id] == "COMPLETE_AT_TARGET":
            _require_equal(
                errors,
                f"{goal_id}: imported completion event",
                record.get("completion_event_sha256"),
                completion_hashes.get(goal_id),
            )
            _require_equal(
                errors,
                f"{goal_id}: imported completion evidence",
                record.get("completion_evidence_refs"),
                completion_refs.get(goal_id),
            )
    return errors


def validate_imported_activation_lineage(
    checkpoint: dict[str, Any],
    archive: dict[str, Any],
) -> list[str]:
    """Seq2 carries every predecessor completion without re-completing it."""
    errors: list[str] = []
    state = checkpoint.get("goal_execution")
    archived_state = _archived_state(archive)
    if not isinstance(state, dict):
        return ["v2.4 goal_execution is missing"]
    history = state.get("transition_history")
    if not isinstance(history, list) or len(history) < 2:
        return []
    activation = history[1] if isinstance(history[1], dict) else {}
    statuses = archived_state.get("status_by_goal")
    refs = archived_state.get("completion_evidence_by_goal")
    if not isinstance(statuses, dict):
        return ["v2.3 archived status map is missing"]
    if not isinstance(refs, dict):
        refs = {}
    completed_ids = {
        goal_id
        for goal_id, status in statuses.items()
        if status == "COMPLETE_AT_TARGET"
    }
    expected_refs = {
        goal_id: refs.get(goal_id)
        for goal_id in sorted(completed_ids)
    }
    expected_hashes = {
        goal_id: (
            history[0].get("event_sha256")
            if isinstance(history[0], dict)
            else None
        )
        for goal_id in sorted(completed_ids)
    }
    _require_equal(
        errors,
        "v2.4 activation imported completion refs",
        activation.get("imported_completion_evidence_refs_by_goal"),
        expected_refs,
    )
    _require_equal(
        errors,
        "v2.4 activation imported completion event hashes",
        activation.get("imported_completion_event_sha256_by_goal"),
        expected_hashes,
    )
    bindings = activation.get(
        "imported_completion_evidence_bindings_by_goal"
    )
    if not isinstance(bindings, dict) or set(bindings) != completed_ids:
        errors.append(
            "v2.4 activation imported completion binding Goal set differs"
        )
    return errors


def validate_current_work_session(
    root: Path,
    checkpoint: dict[str, Any],
    checkpoint_file: Path,
    current_work_session_id: str,
) -> list[str]:
    """Bind active work to the latest fresh execution-session gate."""
    errors: list[str] = []
    state = checkpoint.get("goal_execution")
    if not isinstance(state, dict):
        return ["current work session goal_execution is missing"]
    history = state.get("transition_history")
    statuses = state.get("status_by_goal")
    focus_id = state.get("focus_goal_id")
    if (
        not isinstance(history, list)
        or not history
        or not isinstance(statuses, dict)
        or not isinstance(focus_id, str)
    ):
        return ["current work session state is malformed"]
    latest = history[-1] if isinstance(history[-1], dict) else {}
    if (
        not continuation.SAFE_ID_RE.fullmatch(current_work_session_id)
        or latest.get("event_id") != current_work_session_id
        or latest.get("event_type")
        not in {"GOAL_STARTED", "WORK_SESSION_RESUMED"}
        or latest.get("subject_goal_id") != focus_id
        or statuses.get(focus_id) != "IN_PROGRESS"
    ):
        return [
            "current work session is not bound to the latest "
            "IN_PROGRESS execution event"
        ]

    if latest.get("event_type") == "WORK_SESSION_RESUMED":
        previous_sessions = [
            event
            for event in history[:-1]
            if (
                isinstance(event, dict)
                and event.get("event_type")
                in {"GOAL_STARTED", "WORK_SESSION_RESUMED"}
                and event.get("subject_goal_id") == focus_id
            )
        ]
        expected_previous = (
            previous_sessions[-1].get("event_sha256")
            if previous_sessions
            else None
        )
        if (
            expected_previous is None
            or latest.get("previous_execution_session_event_sha256")
            != expected_previous
        ):
            errors.append(
                "current work session predecessor execution hash differs"
            )

    binding = latest.get("implementation_start_gate_binding")
    receipt_name = (
        "implementation-start-gate-receipt.json"
        if latest.get("event_type") == "GOAL_STARTED"
        else "implementation-resume-gate-receipt.json"
    )
    expected_receipt_path = (
        "docs/control/execution/goal-gates/"
        f"{current_work_session_id}/{receipt_name}"
    )
    if (
        not isinstance(binding, dict)
        or binding.get("path") != expected_receipt_path
    ):
        return errors + ["current work session gate binding differs"]
    receipt_file = continuation.resolve_repo_file(
        root,
        expected_receipt_path,
    )
    if (
        receipt_file is None
        or binding.get("file_sha256")
        != continuation.sha256_file(receipt_file)
    ):
        return errors + ["current work session gate receipt hash differs"]
    try:
        receipt = continuation.load_json(receipt_file)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [
            f"current work session gate receipt cannot be loaded: {exc}"
        ]
    if receipt.get("document_id") != binding.get("document_id"):
        errors.append("current work session gate document ID differs")
    runs = receipt.get("check_runs")
    repository_run = (
        runs[-1]
        if isinstance(runs, list)
        and runs
        and isinstance(runs[-1], dict)
        else None
    )
    if (
        not isinstance(repository_run, dict)
        or repository_run.get("check_id") != "REPOSITORY_STATE"
    ):
        return errors + [
            "current work session repository-state run is missing"
        ]
    repository_file = continuation.resolve_repo_file(
        root,
        repository_run.get("output_path"),
    )
    if (
        repository_file is None
        or repository_run.get("output_sha256")
        != continuation.sha256_file(repository_file)
    ):
        return errors + [
            "current work session repository-state output hash differs"
        ]
    try:
        recorded_payload = continuation.load_json(repository_file)
        live_payload = continuation.capture_gate_repository_state(
            root,
            checkpoint_file,
            current_work_session_id,
        )
    except (OSError, RuntimeError, ValueError, json.JSONDecodeError) as exc:
        return errors + [
            f"current work session repository state cannot be captured: {exc}"
        ]
    if live_payload != recorded_payload:
        errors.append(
            "current work session live repository state differs from its gate"
        )
    return errors


def scope45_in_scope_route_is_applied(
    row: dict[str, Any],
) -> bool:
    current_scope = row.get("scope45_current_scope")
    return (
        isinstance(current_scope, dict)
        and current_scope.get("normalized_decision") == "IN_SCOPE"
        and current_scope.get("scope_decision_status")
        == "APPLIED_TO_NEXT_ACTION_ROUTE"
        and current_scope.get("queue_open") is True
        and current_scope.get("queue_route")
        in SCOPE45_APPLIED_QUEUE_ROUTES
        and current_scope.get("artifact_closure_status") == "OPEN"
    )


def derive_v24_artifact_work_queue_from_register(
    source_binding: dict[str, Any],
    register: dict[str, Any],
    nodes: dict[str, dict[str, Any]],
    statuses: dict[str, str],
) -> tuple[list[str], dict[str, Any]]:
    errors: list[str] = []
    projected_register = json.loads(
        json.dumps(register, ensure_ascii=False)
    )
    artifacts = projected_register.get("artifacts")
    if not isinstance(artifacts, list):
        return ["v2.4 artifact register is malformed"], {}
    rows_by_code = {
        row.get("artifact_type_code"): row
        for row in artifacts
        if isinstance(row, dict)
        and isinstance(row.get("artifact_type_code"), str)
    }
    if len(rows_by_code) != len(artifacts):
        return [
            "v2.4 artifact register codes are missing or duplicated"
        ], {}

    applied_codes = {
        code
        for code, row in rows_by_code.items()
        if scope45_in_scope_route_is_applied(row)
    }
    scope45_summary = projected_register.get(
        "scope45_current_scope"
    )
    if scope45_summary is None:
        if any(
            isinstance(
                row.get("scope45_current_scope"),
                dict,
            )
            for row in rows_by_code.values()
        ):
            errors.append(
                "v2.4 scope45 row overlays lack their aggregate"
            )
    elif not isinstance(scope45_summary, dict):
        errors.append("v2.4 scope45 aggregate is malformed")
    else:
        declared_in_scope = scope45_summary.get(
            "in_scope_artifact_ids"
        )
        if (
            not isinstance(declared_in_scope, list)
            or len(declared_in_scope)
            != len(set(declared_in_scope))
            or set(declared_in_scope) != applied_codes
            or scope45_summary.get("transition_count") != 45
            or scope45_summary.get("in_scope_count") != 43
            or scope45_summary.get("out_of_scope_n_a_count")
            != 2
            or len(applied_codes) != 43
        ):
            errors.append(
                "v2.4 applied scope45 aggregate/row projection differs"
            )

    for code in sorted(applied_codes):
        row = rows_by_code[code]
        if row.get("applicability") != "IN_SCOPE":
            errors.append(
                "v2.4 applied scope45 row has conflicting root "
                f"applicability: {code}"
            )
        if row.get("activation_result") not in {
            "ACTIVE",
            "PENDING_EVALUATION",
        }:
            errors.append(
                "v2.4 applied scope45 row has conflicting activation "
                f"predicate: {code}"
            )
        row["activation_result"] = (
            SCOPE45_APPLIED_TRIGGER_SENTINEL
        )

    queue_errors, queue = (
        frozen_goal.derive_artifact_work_queue_from_register(
            source_binding,
            projected_register,
            nodes,
            statuses,
        )
    )
    synthetic_classifier_errors = {
        (
            "artifact work queue cannot classify register subject: "
            f"{code}"
        )
        for code in applied_codes
    }
    errors.extend(
        error
        for error in queue_errors
        if error not in synthetic_classifier_errors
    )
    partition = queue.get("partition_by_status")
    if isinstance(partition, dict):
        waiting_applicability = set(
            partition.get("WAITING_APPLICABILITY", [])
        )
        live = set(partition.get("LIVE_GOAL", []))
        waiting_trigger = set(
            partition.get("WAITING_TRIGGER", [])
        )
        if applied_codes & waiting_applicability:
            errors.append(
                "v2.4 applied scope45 rows reopened applicability"
            )
        if applied_codes - live - waiting_trigger:
            errors.append(
                "v2.4 applied scope45 rows escaped live/trigger queue"
            )
    return errors, queue


def validate_v24_artifact_work_queue(
    root: Path,
    checkpoint: dict[str, Any],
) -> list[str]:
    errors: list[str] = []
    frozen_checker_path = continuation.resolve_repo_file(
        root,
        V23_CHECKER_RELATIVE.as_posix(),
    )
    if (
        frozen_checker_path is None
        or continuation.sha256_file(frozen_checker_path)
        != continuation.V23_FROZEN_FILE_SHA256[
            V23_CHECKER_RELATIVE.as_posix()
        ]
    ):
        return ["v2.4 queue frozen v2.3 checker binding differs"]
    state = checkpoint.get("goal_execution")
    if not isinstance(state, dict):
        return ["v2.4 queue runtime is missing"]
    bindings = frozen_goal.canonical_binding_map(checkpoint)
    binding = bindings.get("ARTIFACT_REGISTER")
    if not isinstance(binding, dict):
        return ["v2.4 queue lacks ARTIFACT_REGISTER binding"]
    register_path = continuation.resolve_repo_file(
        root,
        binding.get("path"),
    )
    if (
        register_path is None
        or binding.get("file_sha256")
        != continuation.sha256_file(register_path)
    ):
        return ["v2.4 queue ARTIFACT_REGISTER binding differs"]
    history = state.get("transition_history")
    tail = (
        history[-1]
        if isinstance(history, list)
        and history
        and isinstance(history[-1], dict)
        else None
    )
    if (
        isinstance(tail, dict)
        and tail.get("sequence") == 39
        and tail.get("event_id") == continuation.V24_SEQ39_EVENT_ID
    ):
        seq39_errors = (
            continuation.validate_seq39_canonical_binding_update(
                root,
                checkpoint,
            )
        )
        if seq39_errors:
            return [
                f"v2.4 seq39 queue projection: {error}"
                for error in seq39_errors
            ]
        source_event = next(
            (
                event
                for event in history
                if isinstance(event, dict)
                and event.get("sequence") == 38
                and event.get("event_sha256")
                == continuation.V24_SEQ39_SOURCE_EVENT_SHA256
            ),
            None,
        )
        source_runtime = (
            source_event.get("runtime_after")
            if isinstance(source_event, dict)
            else None
        )
        source_queue = (
            source_runtime.get("artifact_work_queue")
            if isinstance(source_runtime, dict)
            else None
        )
        source_boundary = (
            source_runtime.get("completion_boundary")
            if isinstance(source_runtime, dict)
            else None
        )
        if (
            not isinstance(source_queue, dict)
            or not isinstance(source_boundary, dict)
        ):
            return ["v2.4 seq39 queue source projection is missing"]
        expected_queue = copy.deepcopy(source_queue)
        expected_queue["source_binding"] = {
            key: binding.get(key)
            for key in ("role", "document_id", "path", "file_sha256")
        }
        if state.get("artifact_work_queue") != expected_queue:
            errors.append(
                "v2.4 seq39 artifact queue exceeds source-binding-only "
                "projection"
            )
        if state.get("completion_boundary") != source_boundary:
            errors.append(
                "v2.4 seq39 completion boundary changed during "
                "source-binding-only projection"
            )
        return errors
    try:
        register = continuation.load_json(register_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return [f"v2.4 queue register cannot be loaded: {exc}"]
    node_errors, nodes = frozen_goal.current_goal_nodes(
        root,
        state,
    )
    errors.extend(node_errors)
    statuses = state.get("status_by_goal")
    if not isinstance(statuses, dict):
        return errors + ["v2.4 queue status map is malformed"]
    queue_errors, expected_queue = (
        derive_v24_artifact_work_queue_from_register(
            binding,
            register,
            nodes,
            statuses,
        )
    )
    errors.extend(queue_errors)
    if state.get("artifact_work_queue") != expected_queue:
        errors.append(
            "v2.4 artifact work queue differs from the current "
            "scope/activation projection"
        )
    ready_frontier = state.get("ready_frontier_goal_ids")
    blockers = state.get("blockers_by_goal")
    if not isinstance(ready_frontier, list):
        errors.append("v2.4 queue ready frontier is malformed")
        ready_frontier = []
    if not isinstance(blockers, dict):
        errors.append("v2.4 queue blocker map is malformed")
        blockers = {}
    boundary_errors, expected_boundary = (
        frozen_goal.derive_completion_boundary(
            nodes,
            statuses,
            ready_frontier,
            blockers,
            expected_queue,
            package_status=str(state.get("package_status", "")),
        )
    )
    errors.extend(boundary_errors)
    if state.get("completion_boundary") != expected_boundary:
        errors.append(
            "v2.4 completion boundary differs from the current "
            "scope-aware artifact queue"
        )
    return errors


def validate(
    root: Path = ROOT,
    checkpoint_path: Path = V24_CHECKPOINT_RELATIVE,
    archive_path: Path = V23_ARCHIVE_RELATIVE,
    manifest_path: Path = V24_MANIFEST_RELATIVE,
    *,
    check_continuation: bool = True,
    current_work_session_id: str | None = None,
    run_frozen_semantics: bool = True,
) -> list[str]:
    errors: list[str] = []
    if check_continuation:
        errors.extend(
            f"continuation: {error}"
            for error in continuation.validate(
                root,
                checkpoint_path,
                archive_path,
                manifest_path,
            )
        )
    predecessor_errors, archive = continuation.validate_frozen_v23_boundary(
        root,
        archive_path,
    )
    errors.extend(predecessor_errors)
    manifest_file = continuation.resolve_repo_file(root, manifest_path)
    checkpoint_file = continuation.resolve_repo_file(root, checkpoint_path)
    if manifest_file is None:
        return errors + ["v2.4 manifest is missing"]
    if checkpoint_file is None:
        return errors + ["v2.4 checkpoint is missing"]
    try:
        manifest = continuation.load_json(manifest_file)
        checkpoint = continuation.load_json(checkpoint_file)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [f"v2.4 input cannot be loaded: {exc}"]
    preimage_errors, canonical_preimages = (
        validate_canonical_preimage_archive(root)
    )
    errors.extend(preimage_errors)
    android_successor_errors, _ = (
        validate_phase1_android_report_successor_binding(
            root,
            checkpoint,
        )
    )
    errors.extend(android_successor_errors)
    phone_mounting_successor_errors, _ = (
        validate_phone_mounting_current_state_successor_binding(root)
    )
    errors.extend(phone_mounting_successor_errors)
    resource_pilot_successor_errors, _ = (
        validate_resource_pilot_current_state_successor_binding(root)
    )
    errors.extend(resource_pilot_successor_errors)
    errors.extend(
        validate_v24_artifact_work_queue(
            root,
            checkpoint,
        )
    )
    errors.extend(validate_fp014_canonical_completion(root, checkpoint))
    errors.extend(validate_fp047_canonical_completion(root, checkpoint))
    if archive:
        errors.extend(
            validate_manifest_successor_boundary(
                root,
                manifest,
                archive,
            )
        )
        errors.extend(
            validate_imported_goal_projection(
                root,
                checkpoint,
                archive,
            )
        )
        errors.extend(
            validate_imported_activation_lineage(
                checkpoint,
                archive,
            )
        )
        if not check_continuation:
            authorization_anchor_value = getattr(
                continuation,
                "EXPECTED_PACKAGE_ACTIVATION_AUTHORIZATION_SHA256",
            )
            authorization_anchor = (
                None
                if authorization_anchor_value.startswith("__FINALIZE_")
                else authorization_anchor_value
            )
            errors.extend(
                continuation.validate_transition_replay(
                    root,
                    checkpoint,
                    archive,
                    manifest_path,
                    expected_prepared_sha256=(
                        continuation.EXPECTED_V24_PREPARED_EVENT_SHA256
                    ),
                    expected_authorization_sha256=authorization_anchor,
                )
            )
        if run_frozen_semantics and not preimage_errors:
            errors.extend(
                validate_frozen_v23_semantics(
                    root,
                    archive_path,
                    checkpoint=checkpoint,
                    archive=archive,
                    canonical_preimages=canonical_preimages,
                )
            )
    if current_work_session_id is not None:
        errors.extend(
            validate_current_work_session(
                root,
                checkpoint,
                checkpoint_file,
                current_work_session_id,
            )
        )
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--checkpoint", type=Path, default=V24_CHECKPOINT_RELATIVE)
    parser.add_argument("--archive", type=Path, default=V23_ARCHIVE_RELATIVE)
    parser.add_argument("--manifest", type=Path, default=V24_MANIFEST_RELATIVE)
    parser.add_argument("--skip-continuation", action="store_true")
    parser.add_argument("--skip-frozen-semantics", action="store_true")
    parser.add_argument("--current-work-session-id")
    args = parser.parse_args()
    errors = validate(
        args.root.resolve(),
        args.checkpoint,
        args.archive,
        args.manifest,
        check_continuation=not args.skip_continuation,
        current_work_session_id=args.current_work_session_id,
        run_frozen_semantics=not args.skip_frozen_semantics,
    )
    if errors:
        print("WalkSafe v2.4 Goal graph check: FAIL", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    checkpoint = continuation.load_json(
        args.root.resolve() / V24_CHECKPOINT_RELATIVE
    )
    state = checkpoint["goal_execution"]
    print(
        "WalkSafe v2.4 Goal graph check: PASS "
        f"({state['goal_document_count']} managed Goals, "
        f"ready {len(state['ready_frontier_goal_ids'])}, "
        f"focus {state['focus_goal_id']}, {state['activation_status']})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

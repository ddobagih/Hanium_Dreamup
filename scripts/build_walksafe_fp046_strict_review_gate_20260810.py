#!/usr/bin/env python3
"""Apply the separate internal review gate to the complete FP-046 evidence set.

The gate accepts repository-internal evidence only.  It deliberately leaves
formal tests, device evidence, external review, deployment, and release gates
uncredited and NOT_RUN/NOT_ELIGIBLE.
"""

from __future__ import annotations

import argparse
from copy import deepcopy
from dataclasses import dataclass
import fcntl
import importlib
import os
from pathlib import Path
import sys
from typing import Any, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))


def _load(module_name: str) -> Any:
    try:
        return importlib.import_module(f"scripts.{module_name}")
    except ModuleNotFoundError:
        sys.path.insert(0, str(ROOT))
        return importlib.import_module(f"scripts.{module_name}")


trace = _load("build_walksafe_fp046_consent_withdrawal_deletion_trace_20260810")
gap_builder = _load("build_walksafe_fp046_gap_backlog_r025_20260810")
artifact_builder = _load("build_walksafe_fp046_artifact_trace_successor_20260810")
r014_builder = _load("build_walksafe_phase1_exact257_successor_r014_20260810")
transaction_io = artifact_builder.fp008_builder

BuildError = trace.BuildError
require = trace.require
bytes_sha256 = trace.bytes_sha256
json_text = trace.json_text

EXECUTOR_ID = "CODEX-FP046-CONSENT-WITHDRAWAL-DELETION-IMPLEMENTER-20260810-001"
EXECUTOR_TASK = "/root"
EXPECTED_REVIEWER_ID = "WS-FP046-INDEPENDENT-REVIEWER-001"
EXPECTED_REVIEWER_TASK = "/root/fp046_independent_review"
EXPECTED_START_EVENT = {
    "sequence": 53,
    "event_id": "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-20260809-005",
    "event_type": "GOAL_STARTED",
    "event_sha256": "2a936233c36197b42bcd1fb0e6e54836419908ea5d3d712fbc26137e6af1fa16",
}
EXPECTED_START_GATE = {
    "document_id": "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-FP046-20260809-005",
    "path": (
        "docs/control/execution/goal-gates/"
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-20260809-005/"
        "implementation-start-gate-receipt.json"
    ),
    "file_sha256": "ec17d2ad7f9a1216a9f0174588fe39e3411e9a9757c2dce6b3db48915978801e",
    "repository_state_path": (
        "docs/control/execution/goal-gates/"
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-20260809-005/09-REPOSITORY_STATE.log"
    ),
    "repository_state_sha256": "f46e42faf5e824322036713759954bb38277cdf9a3f66752e79459164f6e9087",
}
REVIEW_ATTESTATION_REL = trace.RESULT_DIR_REL / "review-attestation.json"
INDEPENDENT_REVIEW_REL = trace.RESULT_DIR_REL / "independent-review.json"
COMPLETION_RECEIPT_REL = trace.RESULT_DIR_REL / "completion-receipt.json"
POST_REVIEW_OUTPUT_PATHS = (INDEPENDENT_REVIEW_REL, COMPLETION_RECEIPT_REL)
POST_REVIEW_JOURNAL_REL = Path(".walksafe-fp046-post-review-20260810.transaction.json")
POST_REVIEW_TRANSACTION_TOKEN = "walksafe-fp046-post-review-20260810-v1"


@dataclass(frozen=True)
class ReviewContext:
    result_raw: Mapping[Path, bytes]
    result_documents: Mapping[Path, Mapping[str, Any]]
    consumer_bindings: tuple[dict[str, Any], ...]
    evidence_manifest: tuple[dict[str, Any], ...]
    review_subject_sha256: str
    reviewed_result_sha256_by_kind: Mapping[str, str]
    implementation_started_at: str
    implementation_ended_at: str


def _consumer_specs() -> tuple[tuple[str, Path], ...]:
    return (
        ("GAP_R025", gap_builder.R025_GAP_JSON_REL),
        ("BACKLOG_R025", gap_builder.R025_BACKLOG_JSON_REL),
        ("ARTIFACT_CHANGE_LOG", artifact_builder.DOC05_REL),
        ("ARTIFACT_REGISTER", artifact_builder.DOC01_REL),
        ("REQUIREMENTS_TRACEABILITY", artifact_builder.RTM_REL),
        ("DESIGN_TRACEABILITY", artifact_builder.DESIGN_REL),
        ("IMPLEMENTATION_MANIFEST", artifact_builder.IMPLEMENTATION_MANIFEST_REL),
        ("MODULE_REGISTER", artifact_builder.MODULE_REGISTER_REL),
        ("EXACT257_R014_LEDGER", r014_builder.R014_LEDGER_REL),
        ("EXACT257_R014_EVIDENCE", r014_builder.R014_EVIDENCE_REL),
        ("EXACT257_R014_CHECK_RECEIPT", r014_builder.R014_RECEIPT_REL),
    )


def _result_paths() -> tuple[Path, ...]:
    return (
        *(lane.receipt_rel for lane in trace.LANES),
        trace.IMPLEMENTATION_REL,
        trace.VERIFICATION_REL,
        trace.SUCCESSOR_REL,
        trace.REVIEW_SUBJECT_REL,
    )


def _schema(document: Mapping[str, Any]) -> str:
    value = document.get("schema_version")
    require(type(value) is str and value, "consumer schema_version missing")
    return value


def _strict_equal(actual: Any, expected: Any, *, label: str) -> None:
    require(type(actual) is type(expected), f"{label} type differs")
    if isinstance(expected, dict):
        require(set(actual) == set(expected), f"{label} fields differ")
        for key in expected:
            _strict_equal(actual[key], expected[key], label=f"{label}.{key}")
        return
    if isinstance(expected, list):
        require(len(actual) == len(expected), f"{label} length differs")
        for index, (left, right) in enumerate(zip(actual, expected, strict=True)):
            _strict_equal(left, right, label=f"{label}[{index}]")
        return
    require(actual == expected, f"{label} value differs")


def _assert_internal_only_boundary(boundary: Any, *, label: str) -> None:
    require(type(boundary) is dict, f"{label} missing")
    expected = {
        "formal_test_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "actual_user_or_guardian_status": "NOT_RUN",
        "actual_rights_request_status": "NOT_RUN",
        "actual_personal_data_deletion_status": "NOT_RUN",
        "external_legal_review_status": "NOT_RUN",
        "external_privacy_review_status": "NOT_RUN",
        "external_processor_status": "NOT_RUN",
        "operational_database_status": "NOT_RUN",
        "operational_backup_restore_status": "NOT_RUN",
        "production_deployment_status": "NOT_RUN",
        "release_gate_status": "NOT_RUN",
        "release_status": "NOT_ELIGIBLE",
    }
    for field, value in expected.items():
        require(boundary.get(field) == value, f"{label} {field} differs")
    require(boundary.get("release_gates_waived") is False, f"{label} release waiver differs")


def _validate_exact_result_inventory(root: Path) -> None:
    result_dir = root.resolve(strict=True) / trace.RESULT_DIR_REL
    require(result_dir.is_dir() and not result_dir.is_symlink(), "FP-046 result directory authority differs")
    expected_files = {
        relative.relative_to(trace.RESULT_DIR_REL).as_posix()
        for relative in (*_result_paths(), *(lane.log_rel for lane in trace.LANES))
    }
    attested = {*expected_files, REVIEW_ATTESTATION_REL.relative_to(trace.RESULT_DIR_REL).as_posix()}
    final = {
        *attested,
        INDEPENDENT_REVIEW_REL.relative_to(trace.RESULT_DIR_REL).as_posix(),
        COMPLETION_RECEIPT_REL.relative_to(trace.RESULT_DIR_REL).as_posix(),
    }
    actual_files = {
        path.relative_to(result_dir).as_posix()
        for path in result_dir.rglob("*")
        if path.is_file() or path.is_symlink()
    }
    require(actual_files in (expected_files, attested, final), "FP-046 result inventory has an extra or partial review entry")


def _assert_exact_producer_outputs(
    root: Path,
    expected: Mapping[Path, str | bytes],
    paths: Sequence[Path],
    *,
    label: str,
) -> None:
    require(set(expected) >= set(paths), f"{label} producer output set differs")
    for relative in paths:
        value = expected[relative]
        raw = value if isinstance(value, bytes) else value.encode("utf-8")
        require(trace.read_bytes(root, relative) == raw, f"{label} full producer rebuild differs: {relative}")


def prepare_review_context(root: Path = ROOT) -> ReviewContext:
    root = root.resolve(strict=True)
    _validate_exact_result_inventory(root)
    authority = trace.validate_authority(root)
    result_raw = {path: trace.read_bytes(root, path) for path in _result_paths()}
    result_documents = {
        path: trace.strict_json_bytes(raw, path.as_posix())
        for path, raw in result_raw.items()
    }
    log_raw = {lane.log_rel: trace.read_bytes(root, lane.log_rel) for lane in trace.LANES}

    implementation = result_documents[trace.IMPLEMENTATION_REL]
    verification = result_documents[trace.VERIFICATION_REL]
    successor = result_documents[trace.SUCCESSOR_REL]
    subject = result_documents[trace.REVIEW_SUBJECT_REL]
    for document, field, label in (
        (implementation, "implementation_record_content_sha256", "FP-046 implementation"),
        (verification, "verification_result_content_sha256", "FP-046 verification"),
        (successor, "successor_trace_content_sha256", "FP-046 successor"),
        (subject, "review_subject_content_sha256", "FP-046 review subject"),
    ):
        trace.verify_seal(document, field, label)
    for document, label in (
        (implementation, "implementation"),
        (verification, "verification"),
        (successor, "successor"),
        (subject, "review subject"),
    ):
        _assert_internal_only_boundary(document.get("completion_boundary"), label=label)
        _strict_equal(document["completion_boundary"], trace.completion_boundary(), label=f"{label} completion boundary")

    expected_result_hashes = {
        "IMPLEMENTATION_RECORD": bytes_sha256(result_raw[trace.IMPLEMENTATION_REL]),
        "VERIFICATION_RESULT": bytes_sha256(result_raw[trace.VERIFICATION_REL]),
        "SUCCESSOR_TRACE": bytes_sha256(result_raw[trace.SUCCESSOR_REL]),
    }
    require(subject.get("reviewed_result_sha256_by_kind") == expected_result_hashes, "review subject result hashes differ")
    for lane in trace.LANES:
        receipt = result_documents[lane.receipt_rel]
        require(
            receipt.get("raw_output_binding")
            == {
                "path": lane.log_rel.as_posix(),
                "byte_length": len(log_raw[lane.log_rel]),
                "sha256": bytes_sha256(log_raw[lane.log_rel]),
            },
            f"lane log binding differs: {lane.lane_id}",
        )
    expected_pre_review = trace.build_pre_review_outputs(
        root=root,
        lane_observations={
            lane.lane_id: result_documents[lane.receipt_rel]["observation"]
            for lane in trace.LANES
        },
        lane_raw_outputs={lane.lane_id: log_raw[lane.log_rel] for lane in trace.LANES},
        source_groups=trace.IMPLEMENTATION_SOURCE_GROUPS,
        authority=authority,
    )
    actual_pre_review = {**result_raw, **log_raw}
    require(set(expected_pre_review) == set(actual_pre_review), "full FP-046 pre-review producer output set differs")
    for relative, expected in expected_pre_review.items():
        require(actual_pre_review[relative] == expected.encode("utf-8"), f"full FP-046 pre-review producer rebuild differs: {relative}")

    consumer_raw: dict[Path, bytes] = {}
    consumer_documents: dict[Path, Mapping[str, Any]] = {}
    consumer_bindings: list[dict[str, Any]] = []
    for role, relative in _consumer_specs():
        raw = trace.read_bytes(root, relative)
        document = trace.strict_json_bytes(raw, relative.as_posix())
        consumer_raw[relative] = raw
        consumer_documents[relative] = document
        consumer_bindings.append(
            {
                "role": role,
                "path": relative.as_posix(),
                "schema_version": _schema(document),
                "sha256": bytes_sha256(raw),
            }
        )

    _assert_exact_producer_outputs(
        root,
        gap_builder.build_outputs(root),
        gap_builder.OUTPUT_PATHS,
        label="R025 final",
    )
    _assert_exact_producer_outputs(
        root,
        artifact_builder.check_successor(root),
        artifact_builder.OUTPUT_PATHS,
        label="six-artifact successor",
    )
    _assert_exact_producer_outputs(
        root,
        r014_builder.build_outputs(root),
        r014_builder.OUTPUT_PATHS,
        label="R014 exact257 successor",
    )
    require(len(consumer_bindings) == 11, "review consumer count differs")

    receipt = consumer_documents[r014_builder.R014_RECEIPT_REL]
    require(receipt.get("status") == "PASS", "R014 receipt status differs")
    summary = receipt.get("summary")
    require(
        type(summary) is dict
        and summary.get("unchanged_record_count") == 251
        and summary.get("progress_binding_record_count") == 6,
        "R014 receipt exact251+6 summary differs",
    )
    evidence_manifest = tuple(
        {"path": path.as_posix(), "sha256": bytes_sha256(raw)}
        for path, raw in (*result_raw.items(), *log_raw.items())
    )
    starts = [result_documents[lane.receipt_rel]["observation"]["started_at"] for lane in trace.LANES]
    ends = [result_documents[lane.receipt_rel]["observation"]["ended_at"] for lane in trace.LANES]
    return ReviewContext(
        result_raw=result_raw,
        result_documents=result_documents,
        consumer_bindings=tuple(consumer_bindings),
        evidence_manifest=evidence_manifest,
        review_subject_sha256=bytes_sha256(result_raw[trace.REVIEW_SUBJECT_REL]),
        reviewed_result_sha256_by_kind=expected_result_hashes,
        implementation_started_at=min(starts),
        implementation_ended_at=max(ends),
    )


def _review_boundary() -> dict[str, Any]:
    boundary = {
        **trace.completion_boundary(),
        "separate_internal_review_pass": True,
        "external_independence_claimed": False,
    }
    _assert_internal_only_boundary(boundary, label="review boundary")
    return boundary


def build_attestation(
    context: ReviewContext,
    reviewed_at: str,
    *,
    reviewer_id: str = EXPECTED_REVIEWER_ID,
    reviewer_task: str = EXPECTED_REVIEWER_TASK,
) -> dict[str, Any]:
    reviewed = trace._parse_time(reviewed_at, "reviewed_at")
    require(reviewed.microsecond == 0, "reviewed_at must use second precision")
    require(reviewed >= trace._parse_time(context.implementation_ended_at, "implementation end"), "review predates evidence")
    require(reviewer_id != EXECUTOR_ID and reviewer_task != EXECUTOR_TASK, "executor cannot review own work")
    require(reviewer_id == EXPECTED_REVIEWER_ID and reviewer_task == EXPECTED_REVIEWER_TASK, "reviewer authority differs")
    return {
        "schema_version": "1.0",
        "evidence_type": "INTERNAL_REVIEW_ATTESTATION",
        "goal_id": trace.GOAL_ID,
        "reviewer_id": reviewer_id,
        "reviewer_task": reviewer_task,
        "reviewed_at": reviewed_at,
        "review_subject_sha256": context.review_subject_sha256,
        "reviewed_result_sha256_by_kind": deepcopy(dict(context.reviewed_result_sha256_by_kind)),
        "reviewed_consumer_bindings": deepcopy(list(context.consumer_bindings)),
        "decision": "APPROVED",
        "findings": {"blocking": 0, "major_open": 0, "minor_open": 0},
        "review_boundary": _review_boundary(),
    }


def validate_attestation(attestation: Mapping[str, Any], context: ReviewContext) -> None:
    expected = build_attestation(
        context,
        attestation.get("reviewed_at"),
        reviewer_id=attestation.get("reviewer_id"),
        reviewer_task=attestation.get("reviewer_task"),
    )
    _strict_equal(dict(attestation), expected, label="review attestation")


def build_post_review_outputs(
    context: ReviewContext,
    attestation: Mapping[str, Any],
    attestation_raw: bytes,
) -> dict[Path, str]:
    validate_attestation(attestation, context)
    require(attestation_raw == json_text(attestation).encode("utf-8"), "review attestation JSON is noncanonical")
    reviewed_at = attestation["reviewed_at"]
    provenance = {
        "path": REVIEW_ATTESTATION_REL.as_posix(),
        "sha256": bytes_sha256(attestation_raw),
    }
    review = {
        "schema_version": "1.0",
        "document_id": "WS-FP046-CONSENT-WITHDRAWAL-DELETION-INTERNAL-REVIEW-20260810-001",
        "evidence_type": "INDEPENDENT_INTERNAL_REVIEW",
        "goal_id": trace.GOAL_ID,
        "status": "PASS",
        "reviewer_id": attestation["reviewer_id"],
        "reviewer_task": attestation["reviewer_task"],
        "reviewed_at": reviewed_at,
        "review_subject_sha256": context.review_subject_sha256,
        "reviewed_result_sha256_by_kind": deepcopy(dict(context.reviewed_result_sha256_by_kind)),
        "reviewed_consumer_bindings": deepcopy(list(context.consumer_bindings)),
        "attestation_provenance": provenance,
        "findings": {"blocking": 0, "major_open": 0, "minor_open": 0},
        "review_boundary": _review_boundary(),
    }
    review_text = json_text(review)
    result_paths = {
        "IMPLEMENTATION_RECORD": trace.IMPLEMENTATION_REL,
        "VERIFICATION_RESULT": trace.VERIFICATION_REL,
        "SUCCESSOR_TRACE": trace.SUCCESSOR_REL,
    }
    completion_manifest = [
        *deepcopy(list(context.evidence_manifest)),
        provenance,
        {
            "path": INDEPENDENT_REVIEW_REL.as_posix(),
            "sha256": bytes_sha256(review_text.encode("utf-8")),
        },
    ]
    completion = {
        "schema_version": "1.0",
        "document_id": "WS-FP046-CONSENT-WITHDRAWAL-DELETION-WORK-ITEM-COMPLETION-20260810-001",
        "evidence_type": "WORK_ITEM_EXECUTION_RECEIPT",
        "target_goal_id": trace.GOAL_ID,
        "status": "ACCEPTED",
        "result": "PASS",
        "target_goal_content_sha256": trace.EXPECTED_GOAL_SHA256,
        "work_item_id": "EPIC-03-FP046-CONSENT-WITHDRAWAL-DELETION",
        "source_policy_ids": [trace.POLICY_ID],
        "gap_ids": [trace.GAP_ID],
        "target_completion_level": "INTERNAL_POLICY_CONFORMANCE_REASSESSED",
        "execution_start_event_sha256": EXPECTED_START_EVENT["event_sha256"],
        "execution_start_event": deepcopy(EXPECTED_START_EVENT),
        "implementation_start_gate_binding": deepcopy(EXPECTED_START_GATE),
        "execution_window": {
            "started_at": context.implementation_started_at,
            "ended_at": context.implementation_ended_at,
        },
        "completed_at": reviewed_at,
        "executor": {
            "id": EXECUTOR_ID,
            "task": EXECUTOR_TASK,
            "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            "authority": "GRAPH_V2_4_STANDING_EXECUTION_AUTHORITY",
        },
        "reviewer": {
            "id": attestation["reviewer_id"],
            "task": attestation["reviewer_task"],
            "role": "SEPARATE_INTERNAL_REVIEWER",
            "separate_internal_review_pass": True,
            "external_independence_claimed": False,
            "authority": "INTERNAL_REPOSITORY_CONTROL",
            "decision": "APPROVED",
            "decided_at": reviewed_at,
        },
        "reviewer_provenance": {
            "path": INDEPENDENT_REVIEW_REL.as_posix(),
            "sha256": bytes_sha256(review_text.encode("utf-8")),
        },
        "review_attestation_provenance": provenance,
        "result_evidence": [
            {"kind": kind, "path": result_paths[kind].as_posix(), "sha256": digest}
            for kind, digest in context.reviewed_result_sha256_by_kind.items()
        ],
        "downstream_consumer_bindings": deepcopy(list(context.consumer_bindings)),
        "output_evidence_manifest": completion_manifest,
        "output_evidence_manifest_sha256": trace.object_sha256(completion_manifest),
        "self_excluded_from_output_manifest": True,
        "completion_boundary": trace.completion_boundary(),
        "generated_at": reviewed_at,
    }
    _assert_internal_only_boundary(completion["completion_boundary"], label="completion receipt")
    return {
        INDEPENDENT_REVIEW_REL: review_text,
        COMPLETION_RECEIPT_REL: json_text(completion),
    }


def _post_review_stage(relative: Path) -> Path:
    return relative.parent / f".{relative.name}.walksafe-fp046-post-review-stage-20260810"


def _post_review_manifest(outputs: Mapping[Path, str]) -> tuple[dict[str, Any], bytes]:
    require(set(outputs) == set(POST_REVIEW_OUTPUT_PATHS), "post-review output set differs")
    value = trace.sealed(
        {
            "schema_version": "walksafe.fp046-post-review-forward-transaction.v1",
            "transaction_token": POST_REVIEW_TRANSACTION_TOKEN,
            "goal_id": trace.GOAL_ID,
            "outputs": [
                {
                    "path": relative.as_posix(),
                    "sha256": bytes_sha256(outputs[relative].encode("utf-8")),
                    "byte_length": len(outputs[relative].encode("utf-8")),
                    "stage_path": _post_review_stage(relative).as_posix(),
                }
                for relative in POST_REVIEW_OUTPUT_PATHS
            ],
        },
        "transaction_content_sha256",
    )
    raw = json_text(value).encode("utf-8")
    return value, raw


def _validate_post_review_manifest(value: Mapping[str, Any], raw: bytes) -> dict[Path, dict[str, Any]]:
    require(raw == json_text(value).encode("utf-8"), "noncanonical post-review transaction journal")
    trace.verify_seal(value, "transaction_content_sha256", "post-review transaction journal")
    require(
        value.get("schema_version") == "walksafe.fp046-post-review-forward-transaction.v1"
        and value.get("transaction_token") == POST_REVIEW_TRANSACTION_TOKEN
        and value.get("goal_id") == trace.GOAL_ID,
        "post-review transaction identity differs",
    )
    rows = value.get("outputs")
    require(type(rows) is list and len(rows) == len(POST_REVIEW_OUTPUT_PATHS), "post-review transaction output count differs")
    result: dict[Path, dict[str, Any]] = {}
    for relative, row in zip(POST_REVIEW_OUTPUT_PATHS, rows, strict=True):
        require(
            type(row) is dict
            and set(row) == {"path", "sha256", "byte_length", "stage_path"}
            and row.get("path") == relative.as_posix()
            and row.get("stage_path") == _post_review_stage(relative).as_posix()
            and type(row.get("byte_length")) is int
            and row["byte_length"] > 0
            and type(row.get("sha256")) is str
            and trace.SHA256_RE.fullmatch(row["sha256"]),
            f"post-review transaction binding differs: {relative}",
        )
        result[relative] = dict(row)
    return result


def _recover_post_review_transaction_locked(root: Path) -> str:
    journal_raw = transaction_io._optional_bound_raw(root, POST_REVIEW_JOURNAL_REL)
    if journal_raw is None:
        return "NONE"
    journal = trace.strict_json_bytes(journal_raw, POST_REVIEW_JOURNAL_REL.as_posix())
    rows = _validate_post_review_manifest(journal, journal_raw)
    present = [transaction_io._optional_bound_raw(root, relative) for relative in POST_REVIEW_OUTPUT_PATHS]
    for relative, raw in zip(POST_REVIEW_OUTPUT_PATHS, present, strict=True):
        if raw is not None:
            require(bytes_sha256(raw) == rows[relative]["sha256"] and len(raw) == rows[relative]["byte_length"], f"post-review committed output differs: {relative}")
    if any(raw is not None for raw in present):
        for relative, raw in zip(POST_REVIEW_OUTPUT_PATHS, present, strict=True):
            if raw is not None:
                continue
            stage = Path(rows[relative]["stage_path"])
            stage_raw = trace.read_bytes(root, stage)
            require(bytes_sha256(stage_raw) == rows[relative]["sha256"] and len(stage_raw) == rows[relative]["byte_length"], f"post-review recovery stage differs: {relative}")
            os.replace(root / stage, root / relative)
            transaction_io._fsync_directory((root / relative).parent)
    for relative in POST_REVIEW_OUTPUT_PATHS:
        transaction_io._unlink_transaction_member(root, Path(rows[relative]["stage_path"]), rows[relative]["sha256"])
    transaction_io._unlink_transaction_member(root, POST_REVIEW_JOURNAL_REL, bytes_sha256(journal_raw))
    return "COMPLETED_POST_REVIEW" if any(raw is not None for raw in present) else "ROLLED_BACK_PREPARATION"


def recover_post_review_transaction(root: Path = ROOT) -> str:
    root = root.resolve(strict=True)
    descriptor = os.open(root, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        return _recover_post_review_transaction_locked(root)
    finally:
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        os.close(descriptor)


def write_post_review_outputs(root: Path, outputs: Mapping[Path, str]) -> str:
    require(set(outputs) == set(POST_REVIEW_OUTPUT_PATHS), "post-review output set differs")
    root = root.resolve(strict=True)
    descriptor = os.open(root, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        recovery = _recover_post_review_transaction_locked(root)
        require(recovery in {"NONE", "ROLLED_BACK_PREPARATION"}, "post-review transaction completed before requested write; rerun check")
        existing = {relative: transaction_io._optional_bound_raw(root, relative) for relative in POST_REVIEW_OUTPUT_PATHS}
        if all(raw is not None for raw in existing.values()):
            for relative in POST_REVIEW_OUTPUT_PATHS:
                require(existing[relative] == outputs[relative].encode("utf-8"), f"existing post-review output differs: {relative}")
            return "ALREADY_CURRENT"
        require(all(raw is None for raw in existing.values()), "post-review output set is partial without a transaction journal")
        manifest, journal_raw = _post_review_manifest(outputs)
        rows = _validate_post_review_manifest(manifest, journal_raw)
        transaction_io._write_private_stage(root / POST_REVIEW_JOURNAL_REL, journal_raw, 0o600)
        transaction_io._fsync_directory(root)
        try:
            for relative in POST_REVIEW_OUTPUT_PATHS:
                stage = Path(rows[relative]["stage_path"])
                transaction_io._write_private_stage(root / stage, outputs[relative].encode("utf-8"), 0o600)
                transaction_io._fsync_directory((root / stage).parent)
            for relative in POST_REVIEW_OUTPUT_PATHS:
                stage = Path(rows[relative]["stage_path"])
                require(trace.read_bytes(root, stage) == outputs[relative].encode("utf-8"), f"post-review stage bytes differ: {relative}")
                require(transaction_io._optional_bound_raw(root, relative) is None, f"post-review output appeared before commit: {relative}")
                os.replace(root / stage, root / relative)
                transaction_io._fsync_directory((root / relative).parent)
            require(_recover_post_review_transaction_locked(root) == "COMPLETED_POST_REVIEW", "post-review transaction cleanup differs")
        except BaseException as publication_error:
            try:
                _recover_post_review_transaction_locked(root)
            except BaseException:
                raise publication_error
            raise
        return "PUBLISHED_POST_REVIEW"
    finally:
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        os.close(descriptor)


def load_attestation(root: Path, context: ReviewContext) -> tuple[dict[str, Any], bytes]:
    raw = trace.read_bytes(root, REVIEW_ATTESTATION_REL)
    attestation = trace.strict_json_bytes(raw, REVIEW_ATTESTATION_REL.as_posix())
    validate_attestation(attestation, context)
    require(raw == json_text(attestation).encode("utf-8"), "review attestation JSON is noncanonical")
    return attestation, raw


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--reviewed-at")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write-attestation", action="store_true")
    mode.add_argument("--check-attestation", action="store_true")
    mode.add_argument("--write-post-review", action="store_true")
    mode.add_argument("--check-post-review", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.write_post_review:
            recover_post_review_transaction(args.root)
        context = prepare_review_context(args.root)
        if args.write_attestation:
            require(type(args.reviewed_at) is str, "--reviewed-at is required for attestation creation")
            attestation = build_attestation(context, args.reviewed_at)
            trace.write_or_check_outputs(args.root, {REVIEW_ATTESTATION_REL: json_text(attestation)}, write=True)
            detail = "WRITE_ATTESTATION"
        elif args.check_attestation:
            load_attestation(args.root, context)
            detail = "CHECK_ATTESTATION"
        else:
            attestation, raw = load_attestation(args.root, context)
            outputs = build_post_review_outputs(context, attestation, raw)
            if args.write_post_review:
                detail = write_post_review_outputs(args.root, outputs)
            else:
                trace.write_or_check_outputs(args.root, outputs, write=False)
                detail = "CHECK_POST_REVIEW"
    except (BuildError, OSError, ValueError, TypeError) as exc:
        print(f"FP-046 strict review gate: FAIL: {exc}")
        return 1
    print(f"FP-046 strict review gate: PASS mode={detail}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

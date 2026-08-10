#!/usr/bin/env python3
"""Apply the separate internal review gate to the complete FP-008 evidence set.

This gate only reviews repository-internal implementation evidence.  It cannot
convert the four planned tests, device work, external institution/authentication
work, deployment, or release gates into completed evidence.  Attestation and
post-review publication are separate explicit add-only operations.
"""

from __future__ import annotations

import argparse
from copy import deepcopy
from dataclasses import dataclass
from datetime import datetime
import fcntl
import importlib
import os
from pathlib import Path
import sys
from typing import Any, Mapping, Sequence


def _load(name: str) -> Any:
    filename = {
        "trace": "build_walksafe_fp008_admin_review_delivery_trace_20260803.py",
        "gap": "build_walksafe_fp008_gap_backlog_r024_20260803.py",
        "artifact": "build_walksafe_fp008_artifact_trace_successor_20260803.py",
        "r013": "build_walksafe_phase1_exact257_successor_r013_20260803.py",
    }[name]
    try:
        if name == "trace":
            from scripts import build_walksafe_fp008_admin_review_delivery_trace_20260803 as module
        elif name == "gap":
            from scripts import build_walksafe_fp008_gap_backlog_r024_20260803 as module
        elif name == "artifact":
            from scripts import build_walksafe_fp008_artifact_trace_successor_20260803 as module
        else:
            from scripts import build_walksafe_phase1_exact257_successor_r013_20260803 as module
        return module
    except ModuleNotFoundError:
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        return importlib.import_module(f"scripts.{filename.removesuffix('.py')}")


trace = _load("trace")
gap_builder = _load("gap")
artifact_builder = _load("artifact")
r013_builder = _load("r013")
ROOT = Path(__file__).resolve().parents[1]
EXECUTOR_ID = "CODEX-FP008-ADMIN-REVIEW-DELIVERY-IMPLEMENTER-20260803-001"
EXECUTOR_TASK = "/root"
EXPECTED_REVIEWER_ID = "WS-FP008-INDEPENDENT-REVIEWER-001"
EXPECTED_REVIEWER_TASK = "/root/fp008_independent_review"
EXPECTED_INITIAL_EVENT_ID = "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP008-20260803-002"
EXPECTED_INITIAL_EVENT_SHA256 = "82ad77e33eaa55530f53f5ee315807ef66e21fbfc8be2511b004abe99505db90"
EXPECTED_RESUME_EVENT_SHA256 = "7674013bfab4cbdac572a0c809507aed0af36fa4f4fef91e0a7081426e62d7b0"
POST_REVIEW_OUTPUT_PATHS = (
    trace.INDEPENDENT_REVIEW_REL,
    trace.COMPLETION_RECEIPT_REL,
)
POST_REVIEW_JOURNAL_REL = Path(
    ".walksafe-fp008-post-review-20260809.transaction.json"
)
POST_REVIEW_TRANSACTION_TOKEN = "walksafe-fp008-post-review-20260809-v1"

BuildError = trace.BuildError
require = trace.require
bytes_sha256 = trace.bytes_sha256
json_text = trace.json_text


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


def _schema(document: Mapping[str, Any], *, markdown: bool = False) -> str:
    if markdown:
        return "text/markdown"
    value = document.get("schema_version")
    require(type(value) is str and value, "consumer schema_version missing")
    return value


def _consumer_specs() -> tuple[tuple[str, Path, bool], ...]:
    return (
        ("GAP_R024", gap_builder.R024_GAP_JSON_REL, False),
        ("BACKLOG_R024", gap_builder.R024_BACKLOG_JSON_REL, False),
        ("ARTIFACT_CHANGE_LOG", artifact_builder.DOC05_REL, False),
        ("ARTIFACT_REGISTER", artifact_builder.DOC01_REL, False),
        ("REQUIREMENTS_TRACEABILITY", artifact_builder.RTM_REL, False),
        ("DESIGN_TRACEABILITY", artifact_builder.DESIGN_REL, False),
        ("IMPLEMENTATION_MANIFEST", artifact_builder.IMPLEMENTATION_MANIFEST_REL, False),
        ("MODULE_REGISTER", artifact_builder.MODULE_REGISTER_REL, False),
        ("EXACT257_R013_LEDGER", r013_builder.R013_LEDGER_REL, False),
        ("EXACT257_R013_EVIDENCE", r013_builder.R013_EVIDENCE_REL, False),
        ("EXACT257_R013_CHECK_RECEIPT", r013_builder.R013_RECEIPT_REL, False),
    )


def _result_paths() -> tuple[Path, ...]:
    return (trace.POLICY_CONTRACT_REL,) + tuple(lane.receipt_rel for lane in trace.LANES) + (
        trace.IMPLEMENTATION_REL,
        trace.VERIFICATION_REL,
        trace.SUCCESSOR_REL,
        trace.REVIEW_SUBJECT_REL,
    )


def _validate_exact_result_inventory(root: Path) -> None:
    result_dir = root.resolve(strict=True) / trace.RESULT_DIR_REL
    require(result_dir.is_dir() and not result_dir.is_symlink(), "FP-008 result directory authority differs")
    base = {
        "policy-contract.json",
        "implementation-record.json",
        "verification-result.json",
        "successor-trace.json",
        "review-subject.json",
        "evidence",
        "logs",
    }
    attested = {*base, "review-attestation.json"}
    final = {*attested, "independent-review.json", "completion-receipt.json"}
    names = {entry.name for entry in result_dir.iterdir()}
    require(names in (base, attested, final), "FP-008 result inventory has an extra or partial review entry")
    evidence = result_dir / "evidence"
    require(evidence.is_dir() and not evidence.is_symlink(), "FP-008 evidence directory authority differs")
    expected_evidence = {lane.receipt_rel.name for lane in trace.LANES}
    require({entry.name for entry in evidence.iterdir()} == expected_evidence, "FP-008 evidence inventory differs")
    logs = result_dir / "logs"
    require(logs.is_dir() and not logs.is_symlink(), "FP-008 log directory authority differs")
    expected_logs = {lane.log_rel.name for lane in trace.LANES}
    require({entry.name for entry in logs.iterdir()} == expected_logs, "FP-008 log inventory differs")


def prepare_review_context(root: Path = ROOT) -> ReviewContext:
    _validate_exact_result_inventory(root)
    authority = trace.validate_authority(root)
    result_raw = {path: trace.read_bytes(root, path) for path in _result_paths()}
    result_documents = {path: trace.strict_json_bytes(raw, path.as_posix()) for path, raw in result_raw.items()}
    log_raw = {lane.log_rel: trace.read_bytes(root, lane.log_rel) for lane in trace.LANES}
    implementation = result_documents[trace.IMPLEMENTATION_REL]
    verification = result_documents[trace.VERIFICATION_REL]
    successor = result_documents[trace.SUCCESSOR_REL]
    subject = result_documents[trace.REVIEW_SUBJECT_REL]
    policy = result_documents[trace.POLICY_CONTRACT_REL]
    integrity = policy.get("integrity")
    require(type(integrity) is dict and type(integrity.get("content_sha256")) is str, "policy contract self-seal missing")
    policy_projection = deepcopy(policy)
    policy_projection["integrity"].pop("content_sha256")
    require(trace.object_sha256(policy_projection) == integrity["content_sha256"], "policy contract self-seal differs")
    policy_binding = {
        "role": "FP008_POLICY_CONTRACT",
        "path": trace.POLICY_CONTRACT_REL.as_posix(),
        "byte_length": len(result_raw[trace.POLICY_CONTRACT_REL]),
        "sha256": bytes_sha256(result_raw[trace.POLICY_CONTRACT_REL]),
    }
    require(policy_binding["sha256"] == trace.EXPECTED_POLICY_CONTRACT_SHA256, "pinned immutable policy contract bytes differ")
    trace.verify_seal(implementation, "implementation_record_content_sha256", "FP-008 implementation")
    trace.verify_seal(verification, "verification_result_content_sha256", "FP-008 verification")
    trace.verify_seal(successor, "successor_trace_content_sha256", "FP-008 successor")
    trace.verify_seal(subject, "review_subject_content_sha256", "FP-008 review subject")
    require(implementation.get("completion_boundary") == trace.completion_boundary(), "implementation completion boundary differs")
    require(verification.get("completion_boundary") == trace.completion_boundary(), "verification completion boundary differs")
    require(successor.get("completion_boundary") == trace.completion_boundary(), "successor completion boundary differs")
    expected_result_hashes = {
        "IMPLEMENTATION_RECORD": bytes_sha256(result_raw[trace.IMPLEMENTATION_REL]),
        "VERIFICATION_RESULT": bytes_sha256(result_raw[trace.VERIFICATION_REL]),
        "SUCCESSOR_TRACE": bytes_sha256(result_raw[trace.SUCCESSOR_REL]),
    }
    require(subject.get("reviewed_result_sha256_by_kind") == expected_result_hashes, "review subject result hashes differ")
    require(subject.get("policy_contract_binding") == policy_binding, "review subject does not bind immutable policy contract")
    expected_contracts = [
        {"role": role, "path": relative.as_posix(), "binding_stage": "POST_IMPLEMENTATION_PRE_REVIEW"}
        for role, relative, _ in _consumer_specs()
    ]
    require(subject.get("downstream_consumer_contracts") == expected_contracts, "review subject exact11 consumer contract differs")
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
        implementation_paths=trace.IMPLEMENTATION_PATHS,
        verification_input_paths=trace.VERIFICATION_INPUT_PATHS,
        authority=authority,
    )
    require(
        tuple(row.get("path") for row in implementation.get("changed_artifacts", []))
        == trace.IMPLEMENTATION_PATHS,
        "implementation exact ordered canonical path scope differs",
    )
    require(
        tuple(row.get("path") for row in verification.get("verification_input_manifest", []))
        == trace.VERIFICATION_INPUT_PATHS,
        "verification exact ordered canonical input scope differs",
    )
    actual_pre_review = {**result_raw, **log_raw}
    for relative, expected in expected_pre_review.items():
        require(
            actual_pre_review.get(relative) == expected.encode("utf-8"),
            f"full FP-008 pre-review producer rebuild differs: {relative}",
        )
    consumer_bindings: list[dict[str, Any]] = []
    consumer_documents: dict[Path, Mapping[str, Any]] = {}
    consumer_raw: dict[Path, bytes] = {}
    for role, relative, markdown in _consumer_specs():
        require(not trace._path_forbidden(relative.as_posix()), f"forbidden review consumer: {relative}")
        raw = trace.read_bytes(root, relative)
        consumer_raw[relative] = raw
        document: Mapping[str, Any]
        if markdown:
            try:
                raw.decode("utf-8")
            except UnicodeDecodeError as exc:
                raise BuildError(f"consumer markdown is not UTF-8: {relative}") from exc
            document = {}
        else:
            document = trace.strict_json_bytes(raw, relative.as_posix())
            consumer_documents[relative] = document
        consumer_bindings.append(
            {
                "role": role,
                "path": relative.as_posix(),
                "schema_version": _schema(document, markdown=markdown),
                "sha256": bytes_sha256(raw),
            }
        )
    gap = consumer_documents[gap_builder.R024_GAP_JSON_REL]
    backlog = consumer_documents[gap_builder.R024_BACKLOG_JSON_REL]
    trace.verify_seal(gap, "report_content_sha256", "R024 gap")
    trace.verify_seal(backlog, "backlog_content_sha256", "R024 backlog")
    require(gap["summary"]["status_counts"] == gap_builder.EXPECTED_AFTER_COUNTS, "R024 counts differ")
    require(backlog.get("next_single_action") == {
        **backlog["next_single_action"],
        "source_policy_id": "FP-046",
        "gap_id": "GAP-055",
        "status": "PLANNED_NEXT",
    }, "R024 next action differs")
    epic = next(row for row in backlog["epics"] if row.get("epic_id") == "EPIC-03")
    require(epic.get("current_status") == "IN_PROGRESS", "R024 EPIC-03 status differs")
    expected_r024 = gap_builder.build_outputs(root)
    for relative in (
        gap_builder.R024_GAP_JSON_REL,
        gap_builder.R024_BACKLOG_JSON_REL,
    ):
        expected = expected_r024[relative]
        require(trace.read_bytes(root, relative) == expected.encode("utf-8"), f"R024 full producer rebuild differs: {relative}")
    for relative in artifact_builder.OUTPUT_PATHS:
        marker = consumer_documents[relative].get("fp008_artifact_trace_successor")
        require(type(marker) is dict and marker.get("successor_id") == artifact_builder.SUCCESSOR_ID, f"artifact successor marker differs: {relative}")
    artifact_builder.validate_successor_documents(
        {relative: consumer_documents[relative] for relative in artifact_builder.OUTPUT_PATHS},
        {relative: consumer_raw[relative] for relative in artifact_builder.OUTPUT_PATHS},
        implementation,
        verification,
        gap,
        implementation_raw=result_raw[trace.IMPLEMENTATION_REL],
        verification_raw=result_raw[trace.VERIFICATION_REL],
        gap_raw=consumer_raw[gap_builder.R024_GAP_JSON_REL],
    )
    module = consumer_documents[artifact_builder.MODULE_REGISTER_REL]
    admin = [row for row in module["modules"] if row.get("module_id") == "MOD-ANDROID-ADMIN"]
    require(len(admin) == 1 and admin[0].get("paths") == ["apps/android/adminapp"], "DEV-18 adminapp boundary differs")
    r013_builder.verify_nonself(consumer_documents[r013_builder.R013_LEDGER_REL], r013_builder.R013_LEDGER_REL)
    r013_builder.verify_nonself(consumer_documents[r013_builder.R013_EVIDENCE_REL], r013_builder.R013_EVIDENCE_REL)
    r013_builder.verify_nonself(consumer_documents[r013_builder.R013_RECEIPT_REL], r013_builder.R013_RECEIPT_REL)
    receipt = consumer_documents[r013_builder.R013_RECEIPT_REL]
    require(receipt.get("status") == "PASS" and receipt.get("summary", {}).get("unchanged_record_count") == 251 and receipt.get("summary", {}).get("progress_binding_record_count") == 6, "R013 receipt differs")
    expected_r013_sources = [
        (trace.IMPLEMENTATION_REL, result_raw[trace.IMPLEMENTATION_REL]),
        (trace.VERIFICATION_REL, result_raw[trace.VERIFICATION_REL]),
        (gap_builder.R024_GAP_JSON_REL, consumer_raw[gap_builder.R024_GAP_JSON_REL]),
        *[(relative, consumer_raw[relative]) for _, relative in r013_builder.TARGET_ARTIFACT_PATHS],
    ]
    require(
        [(row.get("path"), row.get("sha256")) for row in receipt.get("source_bindings", [])]
        == [(relative.as_posix(), bytes_sha256(raw)) for relative, raw in expected_r013_sources],
        "R013 exact9 physical source bindings differ",
    )
    expected_r013 = r013_builder.build_outputs(root)
    for relative, expected in expected_r013.items():
        require(consumer_raw[relative] == expected, f"R013 full producer rebuild differs: {relative}")
    evidence_manifest = tuple(
        {"path": path.as_posix(), "sha256": bytes_sha256(raw)}
        for path, raw in [*result_raw.items(), *log_raw.items()]
    )
    starts = [document["observation"]["started_at"] for path, document in result_documents.items() if path.parent.name == "evidence"]
    ends = [document["observation"]["ended_at"] for path, document in result_documents.items() if path.parent.name == "evidence"]
    require(len(starts) == len(ends) == len(trace.LANES), "lane time cohort differs")
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
    return {**trace.completion_boundary(), "separate_internal_review_pass": True, "external_independence_claimed": False}


def build_attestation(
    context: ReviewContext,
    reviewed_at: str,
    *,
    reviewer_id: str = EXPECTED_REVIEWER_ID,
    reviewer_task: str = EXPECTED_REVIEWER_TASK,
) -> dict[str, Any]:
    reviewed = trace._parse_time(reviewed_at, "reviewed_at")
    require(
        reviewed.microsecond == 0,
        "reviewed_at must use second precision",
    )
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
    require(dict(attestation) == expected, "review attestation fields or values differ")


def build_post_review_outputs(context: ReviewContext, attestation: Mapping[str, Any], attestation_raw: bytes) -> dict[Path, str]:
    validate_attestation(attestation, context)
    reviewed_at = attestation["reviewed_at"]
    review = {
        "schema_version": "1.0",
        "document_id": "WS-FP008-ADMIN-REVIEW-DELIVERY-INTERNAL-REVIEW-20260803-001",
        "evidence_type": "INDEPENDENT_INTERNAL_REVIEW",
        "goal_id": trace.GOAL_ID,
        "status": "PASS",
        "reviewer_id": attestation["reviewer_id"],
        "reviewer_task": attestation["reviewer_task"],
        "reviewed_at": reviewed_at,
        "review_subject_sha256": context.review_subject_sha256,
        "reviewed_result_sha256_by_kind": deepcopy(dict(context.reviewed_result_sha256_by_kind)),
        "reviewed_consumer_bindings": deepcopy(list(context.consumer_bindings)),
        "attestation_provenance": {"path": trace.REVIEW_ATTESTATION_REL.as_posix(), "sha256": bytes_sha256(attestation_raw)},
        "findings": {"blocking": 0, "major_open": 0, "minor_open": 0},
        "review_boundary": _review_boundary(),
    }
    review_text = json_text(review)
    result_paths = {
        "IMPLEMENTATION_RECORD": trace.IMPLEMENTATION_REL,
        "VERIFICATION_RESULT": trace.VERIFICATION_REL,
        "SUCCESSOR_TRACE": trace.SUCCESSOR_REL,
    }
    completion_manifest = [*deepcopy(list(context.evidence_manifest)), {"path": trace.INDEPENDENT_REVIEW_REL.as_posix(), "sha256": bytes_sha256(review_text.encode("utf-8"))}]
    completion = {
        "schema_version": "1.0",
        "document_id": "WS-FP008-ADMIN-REVIEW-DELIVERY-WORK-ITEM-COMPLETION-20260809-001",
        "evidence_type": "WORK_ITEM_EXECUTION_RECEIPT",
        "target_goal_id": trace.GOAL_ID,
        "status": "ACCEPTED",
        "result": "PASS",
        "target_goal_content_sha256": trace.EXPECTED_GOAL_SHA256,
        "work_item_id": "EPIC-03-FP008-ADMIN-REVIEW-DELIVERY",
        "source_policy_ids": [trace.POLICY_ID],
        "gap_ids": [trace.GAP_ID],
        "target_completion_level": "INTERNAL_POLICY_CONFORMANCE_REASSESSED",
        "execution_start_event_sha256": EXPECTED_INITIAL_EVENT_SHA256,
        "execution_start_event": {
            "sequence": 47,
            "event_id": EXPECTED_INITIAL_EVENT_ID,
            "event_type": "GOAL_STARTED",
            "event_sha256": EXPECTED_INITIAL_EVENT_SHA256,
        },
        "execution_session_event": {
            "sequence": trace.EXPECTED_RESUME_EVENT_SEQUENCE,
            "event_id": trace.EXPECTED_RESUME_EVENT_ID,
            "event_type": "WORK_SESSION_RESUMED",
            "event_sha256": EXPECTED_RESUME_EVENT_SHA256,
        },
        "implementation_start_gate_binding": {
            "document_id": "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-FP008-20260803-002",
            "path": trace.INITIAL_GATE_REL.as_posix(),
            "file_sha256": trace.EXPECTED_INITIAL_GATE_SHA256,
            "repository_state_path": trace.INITIAL_GATE_REPOSITORY_STATE_REL.as_posix(),
            "repository_state_sha256": trace.EXPECTED_INITIAL_GATE_REPOSITORY_STATE_SHA256,
        },
        "execution_session_gate_binding": {
            "document_id": "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-RESUME-GATE-FP008-20260809-005",
            "path": trace.RESUME_GATE_REL.as_posix(),
            "file_sha256": trace.EXPECTED_RESUME_GATE_SHA256,
            "repository_state_path": trace.RESUME_GATE_REPOSITORY_STATE_REL.as_posix(),
            "repository_state_sha256": trace.EXPECTED_RESUME_GATE_REPOSITORY_STATE_SHA256,
        },
        "execution_window": {"started_at": context.implementation_started_at, "ended_at": context.implementation_ended_at},
        "completed_at": reviewed_at,
        "executor": {"id": EXECUTOR_ID, "task": EXECUTOR_TASK, "role": "INTERNAL_IMPLEMENTATION_EXECUTOR", "authority": "GRAPH_V2_4_STANDING_EXECUTION_AUTHORITY"},
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
        "reviewer_provenance": {"path": trace.INDEPENDENT_REVIEW_REL.as_posix(), "sha256": bytes_sha256(review_text.encode("utf-8"))},
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
    return {trace.INDEPENDENT_REVIEW_REL: review_text, trace.COMPLETION_RECEIPT_REL: json_text(completion)}


def _post_review_stage(relative: Path) -> Path:
    return relative.parent / f".{relative.name}.walksafe-fp008-post-review-stage-20260809"


def _post_review_manifest(
    outputs: Mapping[Path, str],
) -> tuple[dict[str, Any], bytes]:
    require(set(outputs) == set(POST_REVIEW_OUTPUT_PATHS), "post-review output set differs")
    value = trace.sealed(
        {
            "schema_version": "walksafe.fp008-post-review-forward-transaction.v1",
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
    return value, json_text(value).encode("utf-8")


def _validate_post_review_manifest(
    value: Mapping[str, Any], raw: bytes
) -> dict[Path, dict[str, Any]]:
    require(raw == json_text(value).encode("utf-8"), "noncanonical post-review transaction journal")
    trace.verify_seal(value, "transaction_content_sha256", "post-review transaction journal")
    require(
        value.get("schema_version")
        == "walksafe.fp008-post-review-forward-transaction.v1"
        and value.get("transaction_token") == POST_REVIEW_TRANSACTION_TOKEN
        and value.get("goal_id") == trace.GOAL_ID,
        "post-review transaction identity differs",
    )
    rows = value.get("outputs")
    require(
        type(rows) is list and len(rows) == len(POST_REVIEW_OUTPUT_PATHS),
        "post-review transaction output count differs",
    )
    result: dict[Path, dict[str, Any]] = {}
    for relative, row in zip(POST_REVIEW_OUTPUT_PATHS, rows, strict=True):
        require(type(row) is dict, f"post-review transaction row differs: {relative}")
        require(
            set(row) == {"path", "sha256", "byte_length", "stage_path"}
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
    journal_raw = artifact_builder._optional_bound_raw(root, POST_REVIEW_JOURNAL_REL)
    if journal_raw is None:
        return "NONE"
    journal = trace.strict_json_bytes(
        journal_raw, POST_REVIEW_JOURNAL_REL.as_posix()
    )
    rows = _validate_post_review_manifest(journal, journal_raw)
    present: list[bool] = []
    for relative in POST_REVIEW_OUTPUT_PATHS:
        raw = artifact_builder._optional_bound_raw(root, relative)
        present.append(raw is not None)
        if raw is not None:
            require(
                bytes_sha256(raw) == rows[relative]["sha256"]
                and len(raw) == rows[relative]["byte_length"],
                f"post-review committed output differs: {relative}",
            )
    if any(present):
        for relative, exists in zip(POST_REVIEW_OUTPUT_PATHS, present, strict=True):
            if exists:
                continue
            stage_relative = Path(rows[relative]["stage_path"])
            stage_raw = trace.read_bytes(root, stage_relative)
            require(
                bytes_sha256(stage_raw) == rows[relative]["sha256"]
                and len(stage_raw) == rows[relative]["byte_length"],
                f"post-review recovery stage differs: {relative}",
            )
            os.replace(root / stage_relative, root / relative)
            artifact_builder._fsync_directory((root / relative).parent)
    for relative in POST_REVIEW_OUTPUT_PATHS:
        artifact_builder._unlink_transaction_member(
            root,
            Path(rows[relative]["stage_path"]),
            rows[relative]["sha256"],
        )
    artifact_builder._unlink_transaction_member(
        root, POST_REVIEW_JOURNAL_REL, bytes_sha256(journal_raw)
    )
    return "COMPLETED_POST_REVIEW" if any(present) else "ROLLED_BACK_PREPARATION"


def recover_post_review_transaction(root: Path = ROOT) -> str:
    root = root.resolve(strict=True)
    descriptor = os.open(root, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        return _recover_post_review_transaction_locked(root)
    finally:
        try:
            fcntl.flock(descriptor, fcntl.LOCK_UN)
        finally:
            os.close(descriptor)


def write_post_review_outputs(
    root: Path, outputs: Mapping[Path, str]
) -> str:
    require(set(outputs) == set(POST_REVIEW_OUTPUT_PATHS), "post-review output set differs")
    root = root.resolve(strict=True)
    descriptor = os.open(root, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        recovery = _recover_post_review_transaction_locked(root)
        require(
            recovery in {"NONE", "ROLLED_BACK_PREPARATION"},
            "post-review transaction completed before requested write; rerun check",
        )
        existing = {
            relative: artifact_builder._optional_bound_raw(root, relative)
            for relative in POST_REVIEW_OUTPUT_PATHS
        }
        if all(raw is not None for raw in existing.values()):
            for relative in POST_REVIEW_OUTPUT_PATHS:
                require(
                    existing[relative] == outputs[relative].encode("utf-8"),
                    f"existing post-review output differs: {relative}",
                )
            return "ALREADY_CURRENT"
        require(
            all(raw is None for raw in existing.values()),
            "post-review output set is partial without a transaction journal",
        )
        manifest, journal_raw = _post_review_manifest(outputs)
        rows = _validate_post_review_manifest(manifest, journal_raw)
        require(
            artifact_builder._optional_bound_raw(root, POST_REVIEW_JOURNAL_REL)
            is None,
            "post-review transaction journal collision",
        )
        for relative in POST_REVIEW_OUTPUT_PATHS:
            require(
                artifact_builder._optional_bound_raw(
                    root, Path(rows[relative]["stage_path"])
                )
                is None,
                f"post-review stage collision: {relative}",
            )
        artifact_builder._write_private_stage(
            root / POST_REVIEW_JOURNAL_REL, journal_raw, 0o600
        )
        artifact_builder._fsync_directory(root)
        try:
            for relative in POST_REVIEW_OUTPUT_PATHS:
                artifact_builder._write_private_stage(
                    root / Path(rows[relative]["stage_path"]),
                    outputs[relative].encode("utf-8"),
                    0o600,
                )
                artifact_builder._fsync_directory((root / relative).parent)
            for relative in POST_REVIEW_OUTPUT_PATHS:
                stage_relative = Path(rows[relative]["stage_path"])
                require(
                    trace.read_bytes(root, stage_relative)
                    == outputs[relative].encode("utf-8"),
                    f"post-review stage bytes differ: {relative}",
                )
                try:
                    (root / relative).lstat()
                except FileNotFoundError:
                    pass
                else:
                    raise BuildError(f"post-review output appeared before commit: {relative}")
                os.replace(root / stage_relative, root / relative)
                artifact_builder._fsync_directory((root / relative).parent)
            cleanup = _recover_post_review_transaction_locked(root)
            require(
                cleanup == "COMPLETED_POST_REVIEW",
                "post-review transaction cleanup differs",
            )
        except BaseException as publication_error:
            try:
                _recover_post_review_transaction_locked(root)
            except BaseException:
                raise publication_error
            raise
        return "PUBLISHED_POST_REVIEW"
    finally:
        try:
            fcntl.flock(descriptor, fcntl.LOCK_UN)
        finally:
            os.close(descriptor)


def load_attestation(root: Path, context: ReviewContext) -> tuple[dict[str, Any], bytes]:
    raw = trace.read_bytes(root, trace.REVIEW_ATTESTATION_REL)
    attestation = trace.strict_json_bytes(raw, trace.REVIEW_ATTESTATION_REL.as_posix())
    validate_attestation(attestation, context)
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
            trace.write_or_check_outputs(args.root, {trace.REVIEW_ATTESTATION_REL: json_text(attestation)}, write=True)
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
        print(f"FP-008 strict review gate: FAIL: {exc}")
        return 1
    print(f"FP-008 strict review gate: PASS mode={detail}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

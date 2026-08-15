#!/usr/bin/env python3
"""Build add-only R027 GAP-008/backlog evidence corrections.

R027 keeps the exact R026 bytes as its immutable predecessor, records why the
v1 internal evidence is no longer current, and binds the corrected v2 producer
results plus the rejected R001 review.  It never grants formal, device, drill,
external, deployment, approval, or release credit.
"""

from __future__ import annotations

import argparse
from copy import deepcopy
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence

from scripts import build_walksafe_npc_single_admin_recovery_trace_20260812 as trace


ROOT = Path(__file__).resolve().parents[1]
GOAL_ID = trace.GOAL_ID
POLICY_ID = trace.POLICY_ID
GAP_ID = trace.GAP_ID
R026_GAP_JSON_REL = trace.GAP_R026_REL
R026_GAP_MD_REL = R026_GAP_JSON_REL.with_suffix(".md")
R026_BACKLOG_JSON_REL = trace.BACKLOG_R026_REL
R026_BACKLOG_MD_REL = R026_BACKLOG_JSON_REL.with_suffix(".md")
R027_GAP_JSON_REL = trace.GAP_R027_REL
R027_GAP_MD_REL = R027_GAP_JSON_REL.with_suffix(".md")
R027_BACKLOG_JSON_REL = trace.BACKLOG_R027_REL
R027_BACKLOG_MD_REL = R027_BACKLOG_JSON_REL.with_suffix(".md")
R001_REJECTED_REVIEW_REL = trace.R001_REJECTED_REVIEW_REL
OUTPUT_PATHS = (
    R027_GAP_JSON_REL,
    R027_GAP_MD_REL,
    R027_BACKLOG_JSON_REL,
    R027_BACKLOG_MD_REL,
)
R026_INPUT_PATHS = (
    R026_GAP_JSON_REL,
    R026_GAP_MD_REL,
    R026_BACKLOG_JSON_REL,
    R026_BACKLOG_MD_REL,
)
EXPECTED_R026_SHA256_BY_PATH = {
    R026_GAP_JSON_REL: "eb97ed79d544591bf577702521acdec8c87378405397b7270b24f645b81ea8e1",
    R026_GAP_MD_REL: "c8973914959559d451ab4c0c0aa4cfa2b040a69bea6c47d2fa06d30f924842f3",
    R026_BACKLOG_JSON_REL: "4d3b91b3db17e154a97b2e4913045a546e73afcc5c6b84a16979de3f666cdd6c",
    R026_BACKLOG_MD_REL: "9fae56e8d1eb2816961082a08a110d51994016fe6ff5a9f8dafbc0c2445df684",
}
EXPECTED_STATUS_COUNTS = {
    "BLOCKED": 5,
    "CONFLICTING": 16,
    "EVIDENCE_MISSING": 4,
    "IMPLEMENTED": 0,
    "MISSING": 8,
    "PARTIAL": 35,
}
R001_ROUND_ID = "WS-NPC-SINGLE-ADMIN-RECOVERY-REVIEW-20260813-R001"
R001_DOCUMENT_ID = (
    "WS-NPC-SINGLE-ADMIN-RECOVERY-INTERNAL-REVIEW-REJECTED-20260813-001"
)
PENDING_R001_REVIEW_SHA256 = "PENDING_R001_REJECTED_REVIEW_SHA256"
EXPECTED_R001_REVIEW_SHA256 = (
    "562d4a0bbf30c5c93bd5291fe046acd911d801f5798055a1e310da120e508a36"
)
R001_FINDING_COUNTS = {"blocking": 7, "major_open": 2, "minor_open": 0}
CORRECTED_EVIDENCE_ID = "EVD-NPC-SINGLE-ADMIN-RECOVERY-INTERNAL-CORRECTION-V2-20260813"

_R001_LANE_FILE_STEM_BY_ID = {
    "ADMIN_ANDROID_UNIT": "admin_android_unit",
    "ADMIN_ANDROID_ASSEMBLE_LINT": "admin_android_assemble_lint",
    "BACKEND_RECOVERY_PYTEST": "backend_recovery_pytest",
    "RECOVERY_GATE_CLI_PYTEST": "recovery_gate_cli_pytest",
}
_R001_LANE_RECEIPT_PATH_BY_ID = {
    lane_id: trace.RESULT_DIR_REL / "lanes" / f"{stem}.json"
    for lane_id, stem in _R001_LANE_FILE_STEM_BY_ID.items()
}
_R001_LOG_PATH_BY_ID = {
    lane_id: trace.RESULT_DIR_REL / "logs" / f"{stem}.log"
    for lane_id, stem in _R001_LANE_FILE_STEM_BY_ID.items()
}
_R001_REVIEW_MANIFEST_HISTORY_PATHS = (
    trace.IMPLEMENTATION_REL,
    trace.VERIFICATION_REL,
    trace.SUCCESSOR_REL,
    trace.REVIEW_SUBJECT_REL,
    trace.OBSERVATION_MANIFEST_REL,
    *(_R001_LOG_PATH_BY_ID[lane_id] for lane_id in _R001_LANE_FILE_STEM_BY_ID),
)
R001_HISTORY_PATHS = (
    trace.IMPLEMENTATION_REL,
    trace.VERIFICATION_REL,
    trace.SUCCESSOR_REL,
    trace.REVIEW_SUBJECT_REL,
    trace.OBSERVATION_MANIFEST_REL,
    *(
        _R001_LANE_RECEIPT_PATH_BY_ID[lane_id]
        for lane_id in _R001_LANE_FILE_STEM_BY_ID
    ),
    *(_R001_LOG_PATH_BY_ID[lane_id] for lane_id in _R001_LANE_FILE_STEM_BY_ID),
)

BuildError = trace.BuildError
require = trace.require
bytes_sha256 = trace.bytes_sha256
object_sha256 = trace.object_sha256
json_text = trace.json_text


@dataclass(frozen=True)
class R001ReviewInputs:
    review: dict[str, Any]
    review_raw: bytes
    review_subject: dict[str, Any]
    review_subject_raw: bytes
    v1_implementation_raw: bytes
    v1_verification_raw: bytes
    v1_successor_raw: bytes
    reviewed_evidence_raw_by_path: Mapping[Path, bytes]


def _binding(
    name: str,
    path: Path,
    raw: bytes,
    relation: str,
    document_id: str,
) -> dict[str, Any]:
    return {
        "name": name,
        "path": path.as_posix(),
        "sha256": bytes_sha256(raw),
        "byte_length": len(raw),
        "relation": relation,
        "document_id": document_id,
    }


def _document_id(value: Mapping[str, Any], label: str) -> str:
    candidate = value.get("document_id")
    require(type(candidate) is str and candidate, f"{label} document ID differs")
    return candidate


def _validate_r026_inputs(
    gap: Mapping[str, Any],
    backlog: Mapping[str, Any],
    raw_by_path: Mapping[Path, bytes],
    expected_sha256_by_path: Mapping[Path, str],
) -> None:
    require(set(raw_by_path) == set(R026_INPUT_PATHS), "R026 predecessor inventory differs")
    require(
        set(expected_sha256_by_path) == set(R026_INPUT_PATHS),
        "R026 predecessor pin inventory differs",
    )
    for path in R026_INPUT_PATHS:
        require(
            bytes_sha256(raw_by_path[path]) == expected_sha256_by_path[path],
            f"canonical R026 predecessor bytes differ: {path}",
        )
    require(
        raw_by_path[R026_GAP_JSON_REL] == json_text(gap).encode("utf-8")
        and raw_by_path[R026_BACKLOG_JSON_REL] == json_text(backlog).encode("utf-8"),
        "R026 predecessor JSON is noncanonical",
    )
    trace.verify_seal(gap, "report_content_sha256", "R026 gap")
    trace.verify_seal(backlog, "backlog_content_sha256", "R026 backlog")
    require(
        gap.get("metadata", {}).get("report_id")
        == "WS-IMPLEMENTATION-GAP-ANALYSIS-20260812-026"
        and backlog.get("metadata", {}).get("backlog_id")
        == "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260812-026",
        "R026 predecessor identity differs",
    )
    require(
        gap.get("summary", {}).get("status_counts") == EXPECTED_STATUS_COUNTS
        and gap.get("summary", {}).get("implemented_and_formally_verified_count") == 0
        and gap.get("summary", {}).get("release_status") == "NOT_ELIGIBLE",
        "R026 status, formal credit, or release boundary differs",
    )


def _validate_v2_results(
    implementation: Mapping[str, Any],
    verification: Mapping[str, Any],
    implementation_raw: bytes,
    verification_raw: bytes,
    rebuilt_v2_outputs: Mapping[Path, str],
) -> None:
    require(
        trace.V2_IMPLEMENTATION_REL in rebuilt_v2_outputs
        and trace.V2_VERIFICATION_REL in rebuilt_v2_outputs,
        "deeply rebuilt v2 producer outputs are missing",
    )
    require(
        implementation_raw
        == rebuilt_v2_outputs[trace.V2_IMPLEMENTATION_REL].encode("utf-8")
        and verification_raw
        == rebuilt_v2_outputs[trace.V2_VERIFICATION_REL].encode("utf-8"),
        "stored v2 producer results do not reproduce from the sealed observation, lane logs, receipts, source manifest, and start authority",
    )
    trace.verify_seal(
        implementation, "implementation_record_content_sha256", "v2 implementation"
    )
    trace.verify_seal(
        verification, "verification_result_content_sha256", "v2 verification"
    )
    require(
        implementation_raw == json_text(implementation).encode("utf-8")
        and verification_raw == json_text(verification).encode("utf-8"),
        "v2 producer JSON is noncanonical",
    )
    require(
        implementation.get("evidence_schema") == "V2_CORRECTION_ONLY"
        and implementation.get("goal_id") == GOAL_ID
        and implementation.get("policy_id") == POLICY_ID
        and implementation.get("gap_id") == GAP_ID
        and implementation.get("status") == "PASS_INTERNAL",
        "v2 implementation identity/status differs",
    )
    require(
        verification.get("evidence_schema") == "V2_CORRECTION_ONLY"
        and verification.get("goal_id") == GOAL_ID
        and verification.get("status") == "PASS_INTERNAL"
        and verification.get("implementation_record_sha256")
        == bytes_sha256(implementation_raw),
        "v2 verification identity/input differs",
    )
    boundary = trace.completion_boundary()
    require(
        implementation.get("completion_boundary") == boundary
        and verification.get("completion_boundary") == boundary,
        "v2 producer completion boundary differs",
    )
    observed_at = implementation.get("observed_at")
    require(
        observed_at == verification.get("observed_at"),
        "v2 implementation/verification timeline differs",
    )
    trace._parse_time(observed_at, "v2 producer observed_at")
    manifest = implementation.get("final_content_manifest")
    require(type(manifest) is dict, "v2 implementation manifest missing")
    sealed_manifest = deepcopy(manifest)
    observed_manifest_seal = sealed_manifest.pop("manifest_content_sha256", None)
    require(
        type(observed_manifest_seal) is str
        and observed_manifest_seal == object_sha256(sealed_manifest),
        "v2 implementation manifest seal differs",
    )


def _review_boundary_preserved(review: Mapping[str, Any]) -> bool:
    boundary = review.get("completion_boundary")
    if type(boundary) is dict:
        return boundary == trace.completion_boundary()
    review_boundary = review.get("review_boundary")
    return type(review_boundary) is dict and all(
        review_boundary.get(key) == value
        for key, value in trace.completion_boundary().items()
    )


def _validate_rejected_review(
    review: Mapping[str, Any],
    review_raw: bytes,
    review_subject: Mapping[str, Any],
    review_subject_raw: bytes,
    v1_implementation_raw: bytes,
    v1_verification_raw: bytes,
    v1_successor_raw: bytes,
    reviewed_evidence_raw_by_path: Mapping[Path, bytes],
    predecessor_gap: Mapping[str, Any],
    v2_producer_observed_at: str,
    expected_review_sha256: str,
) -> None:
    require(
        expected_review_sha256 != PENDING_R001_REVIEW_SHA256
        and trace.SHA256_RE.fullmatch(expected_review_sha256) is not None
        and bytes_sha256(review_raw) == expected_review_sha256,
        "R001 rejected review exact raw SHA-256 differs or is unresolved",
    )
    require(review_raw == json_text(review).encode("utf-8"), "R001 review JSON is noncanonical")
    require(
        review.get("schema_version")
        == "walksafe.npc-single-admin-recovery.review-result.v2"
        and review.get("document_id") == R001_DOCUMENT_ID
        and review.get("goal_id") == GOAL_ID
        and review.get("round_id") == R001_ROUND_ID
        and review.get("assignment_status") == "NOT_ISSUED_LEGACY_ROUND"
        and review.get("decision") == "REJECTED",
        "R001 rejected review identity differs",
    )
    reviewer = review.get("reviewer")
    require(
        type(reviewer) is dict
        and set(reviewer) == {"agent_instance_id", "canonical_task", "role"}
        and reviewer.get("role") == "SEPARATE_INTERNAL_REVIEWER"
        and type(reviewer.get("agent_instance_id")) is str
        and bool(reviewer.get("agent_instance_id"))
        and type(reviewer.get("canonical_task")) is str
        and reviewer.get("canonical_task", "").startswith("/root"),
        "R001 rejected review reviewer identity differs",
    )
    require(review.get("finding_counts") == R001_FINDING_COUNTS, "R001 finding counts differ")
    findings = review.get("findings")
    require(
        type(findings) is dict
        and set(findings) == set(R001_FINDING_COUNTS)
        and all(type(findings[key]) is list for key in R001_FINDING_COUNTS)
        and all(len(findings[key]) == count for key, count in R001_FINDING_COUNTS.items()),
        "R001 finding inventory differs",
    )
    finding_ids = [
        row.get("finding_id")
        for rows in findings.values()
        for row in rows
        if type(row) is dict
    ]
    require(
        len(finding_ids) == 9
        and all(type(value) is str and value for value in finding_ids)
        and len(set(finding_ids)) == 9,
        "R001 finding identities differ",
    )
    require(_review_boundary_preserved(review), "R001 completion boundary differs")
    target = next(
        (row for row in predecessor_gap.get("assessments", []) if row.get("gap_id") == GAP_ID),
        None,
    )
    reassessment = target.get("npc_single_admin_recovery_reassessment") if type(target) is dict else None
    result_hashes = review.get("reviewed_result_sha256_by_kind")
    require(
        type(reassessment) is dict
        and type(result_hashes) is dict
        and result_hashes.get("IMPLEMENTATION_RECORD")
        == reassessment.get("implementation_record_sha256")
        and result_hashes.get("VERIFICATION_RESULT")
        == reassessment.get("verification_result_sha256"),
        "R001 review does not bind the R026 v1 producer results",
    )
    trace.verify_seal(
        review_subject, "review_subject_content_sha256", "v1 review subject"
    )
    require(
        review_subject_raw == json_text(review_subject).encode("utf-8")
        and review_subject.get("goal_id") == GOAL_ID
        and review_subject.get("status") == "READY_FOR_SEPARATE_INTERNAL_REVIEW",
        "v1 review subject identity or canonical bytes differ",
    )
    require(
        review.get("review_subject_binding")
        == {
            "path": trace.REVIEW_SUBJECT_REL.as_posix(),
            "sha256": bytes_sha256(review_subject_raw),
        },
        "R001 review subject physical binding differs",
    )
    expected_result_hashes = {
        "IMPLEMENTATION_RECORD": bytes_sha256(v1_implementation_raw),
        "VERIFICATION_RESULT": bytes_sha256(v1_verification_raw),
        "SUCCESSOR_TRACE": bytes_sha256(v1_successor_raw),
    }
    require(
        review.get("reviewed_result_sha256_by_kind") == expected_result_hashes
        and review_subject.get("reviewed_result_sha256_by_kind")
        == expected_result_hashes,
        "R001 reviewed v1 result physical bindings differ",
    )
    v1_implementation = trace.strict_json_bytes(
        v1_implementation_raw, trace.IMPLEMENTATION_REL.as_posix()
    )
    v1_verification = trace.strict_json_bytes(
        v1_verification_raw, trace.VERIFICATION_REL.as_posix()
    )
    v1_successor = trace.strict_json_bytes(
        v1_successor_raw, trace.SUCCESSOR_REL.as_posix()
    )
    trace.verify_seal(
        v1_implementation,
        "implementation_record_content_sha256",
        "v1 implementation",
    )
    trace.verify_seal(
        v1_verification, "verification_result_content_sha256", "v1 verification"
    )
    trace.verify_seal(v1_successor, "successor_trace_content_sha256", "v1 successor")
    require(
        v1_implementation_raw == json_text(v1_implementation).encode("utf-8")
        and v1_verification_raw == json_text(v1_verification).encode("utf-8")
        and v1_successor_raw == json_text(v1_successor).encode("utf-8")
        and v1_verification.get("implementation_record_sha256")
        == bytes_sha256(v1_implementation_raw),
        "v1 producer/successor canonical physical chain differs",
    )
    evidence_manifest = review.get("reviewed_evidence_manifest")
    require(type(evidence_manifest) is list and evidence_manifest, "R001 evidence manifest differs")
    evidence_paths: list[str] = []
    for row in evidence_manifest:
        require(
            type(row) is dict
            and set(row) == {"path", "sha256"}
            and type(row.get("path")) is str
            and row["path"]
            and not row["path"].startswith("/")
            and ".." not in Path(row["path"]).parts
            and type(row.get("sha256")) is str
            and trace.SHA256_RE.fullmatch(row["sha256"]) is not None,
            "R001 evidence manifest row differs",
        )
        evidence_paths.append(row["path"])
    require(
        evidence_paths == sorted(set(evidence_paths))
        and {Path(path) for path in evidence_paths}.issubset(
            reviewed_evidence_raw_by_path
        ),
        "R001 evidence manifest inventory differs",
    )
    require(
        all(
            row["sha256"]
            == bytes_sha256(reviewed_evidence_raw_by_path[Path(row["path"])])
            for row in evidence_manifest
        ),
        "R001 evidence manifest physical bytes differ",
    )
    reviewed_consumer_bindings = review_subject.get("reviewed_consumer_bindings")
    require(
        type(reviewed_consumer_bindings) is list
        and all(
            type(binding) is dict
            and type(binding.get("path")) is str
            and Path(binding["path"]) in reviewed_evidence_raw_by_path
            and binding.get("sha256")
            == bytes_sha256(reviewed_evidence_raw_by_path[Path(binding["path"])])
            and binding.get("byte_length")
            == len(reviewed_evidence_raw_by_path[Path(binding["path"])])
            for binding in reviewed_consumer_bindings
        ),
        "v1 review subject consumer physical bindings differ",
    )
    for rows in findings.values():
        for row in rows:
            require(
                type(row) is dict
                and type(row.get("finding_id")) is str
                and type(row.get("summary")) is str
                and bool(row["summary"])
                and type(row.get("evidence_paths")) is list
                and bool(row["evidence_paths"])
                and all(
                    type(path) is str and path in evidence_paths
                    for path in row["evidence_paths"]
                ),
                "R001 finding content or physical evidence path differs",
            )
    reviewed_at = trace._parse_time(review.get("reviewed_at"), "R001 reviewed_at")
    predecessor_prepared_at = trace._parse_time(
        predecessor_gap.get("metadata", {}).get("prepared_at"),
        "R026 prepared_at",
    )
    v2_observed_at = trace._parse_time(
        v2_producer_observed_at, "v2 producer observed_at"
    )
    require(
        predecessor_prepared_at <= reviewed_at <= v2_observed_at,
        "R001 review chronology differs from the v1 predecessor and v2 correction producer",
    )


def _correction_lineage(
    predecessor_gap: Mapping[str, Any],
    implementation: Mapping[str, Any],
    verification: Mapping[str, Any],
    review: Mapping[str, Any],
    *,
    predecessor_raw_by_path: Mapping[Path, bytes],
    implementation_raw: bytes,
    verification_raw: bytes,
    review_raw: bytes,
) -> dict[str, Any]:
    return {
        "kind": "ADDITIVE_INTERNAL_EVIDENCE_CORRECTION",
        "scope": "CURRENT_REPOSITORY_INTERNAL_EVIDENCE_ONLY",
        "predecessor_report": {
            "path": R026_GAP_JSON_REL.as_posix(),
            "document_id": predecessor_gap["metadata"]["report_id"],
            "sha256": bytes_sha256(predecessor_raw_by_path[R026_GAP_JSON_REL]),
        },
        "predecessor_markdown": {
            "path": R026_GAP_MD_REL.as_posix(),
            "sha256": bytes_sha256(predecessor_raw_by_path[R026_GAP_MD_REL]),
        },
        "predecessor_must_remain_add_only": True,
        "predecessor_current_internal_evidence_disposition": "SUPERSEDED_BY_V2_CORRECTION_ONLY",
        "superseded_v1_result_sha256_by_kind": deepcopy(
            dict(review["reviewed_result_sha256_by_kind"])
        ),
        "rejection_binding": {
            "path": R001_REJECTED_REVIEW_REL.as_posix(),
            "document_id": _document_id(review, "R001 review"),
            "round_id": review["round_id"],
            "decision": "REJECTED",
            "sha256": bytes_sha256(review_raw),
        },
        "current_v2_result_sha256_by_kind": {
            "IMPLEMENTATION_RECORD": bytes_sha256(implementation_raw),
            "VERIFICATION_RESULT": bytes_sha256(verification_raw),
        },
        "current_v2_document_id_by_kind": {
            "IMPLEMENTATION_RECORD": _document_id(implementation, "v2 implementation"),
            "VERIFICATION_RESULT": _document_id(verification, "v2 verification"),
        },
        "formal_device_drill_external_deployment_approval_release_credit_promoted": False,
    }


def build_gap(
    predecessor: Mapping[str, Any],
    implementation: Mapping[str, Any],
    verification: Mapping[str, Any],
    review: Mapping[str, Any],
    *,
    predecessor_raw_by_path: Mapping[Path, bytes],
    implementation_raw: bytes,
    verification_raw: bytes,
    review_raw: bytes,
) -> dict[str, Any]:
    before = deepcopy(dict(predecessor))
    report = deepcopy(before)
    report.pop("report_content_sha256", None)
    before_by_id = {row["gap_id"]: deepcopy(row) for row in before["assessments"]}
    require(len(before_by_id) == 68, "R026 assessment inventory differs")
    target = next((row for row in report["assessments"] if row.get("gap_id") == GAP_ID), None)
    require(
        type(target) is dict
        and target.get("source_policy_id") == POLICY_ID
        and target.get("status") == "PARTIAL"
        and target.get("formal_test_status") == "NOT_RUN"
        and target.get("waived") is False,
        "R026 GAP-008 boundary differs",
    )
    gap068_before = before_by_id.get("GAP-068")
    require(
        type(gap068_before) is dict
        and gap068_before.get("status") == "BLOCKED"
        and gap068_before.get("formal_test_status") == "NOT_RUN",
        "R026 GAP-068 drill boundary differs",
    )
    prepared_at = implementation["observed_at"]
    metadata = deepcopy(report["metadata"])
    metadata.update(
        {
            "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260813-027",
            "version": "0.27.0",
            "prepared_at": prepared_at,
            "predecessor_report_id": before["metadata"]["report_id"],
        }
    )
    report["metadata"] = metadata
    report["purpose"] = (
        "Correct only the current repository-internal GAP-008 evidence lineage: retain R026 add-only, "
        "record its v1 evidence as rejected/superseded for current use, and bind corrected v2 implementation "
        "and verification. No assessment status or formal/release credit is promoted."
    )
    report["source_predecessor"] = {
        "path": R026_GAP_JSON_REL.as_posix(),
        "file_sha256": bytes_sha256(predecessor_raw_by_path[R026_GAP_JSON_REL]),
        "markdown_path": R026_GAP_MD_REL.as_posix(),
        "markdown_file_sha256": bytes_sha256(predecessor_raw_by_path[R026_GAP_MD_REL]),
        "preserved_unchanged": True,
    }
    report["source_bindings"] = [
        *deepcopy(before.get("source_bindings", [])),
        _binding(
            "r026_gap_predecessor",
            R026_GAP_JSON_REL,
            predecessor_raw_by_path[R026_GAP_JSON_REL],
            "IMMUTABLE_R026_PREDECESSOR",
            before["metadata"]["report_id"],
        ),
        _binding(
            "npc_single_admin_recovery_rejected_review_r001",
            R001_REJECTED_REVIEW_REL,
            review_raw,
            "REJECTED_V1_INTERNAL_EVIDENCE_REVIEW",
            _document_id(review, "R001 review"),
        ),
        _binding(
            "npc_single_admin_recovery_implementation_result_v2",
            trace.V2_IMPLEMENTATION_REL,
            implementation_raw,
            "CURRENT_V2_INTERNAL_IMPLEMENTATION_RESULT",
            _document_id(implementation, "v2 implementation"),
        ),
        _binding(
            "npc_single_admin_recovery_verification_result_v2",
            trace.V2_VERIFICATION_REL,
            verification_raw,
            "CURRENT_V2_INTERNAL_VERIFICATION_RESULT",
            _document_id(verification, "v2 verification"),
        ),
    ]
    report["source_binding_sha256"] = object_sha256(report["source_bindings"])
    manifest = deepcopy(implementation["final_content_manifest"])
    report["implementation_snapshot"] = {
        "evidence_schema": "V2_CORRECTION_ONLY",
        "scope_kind": implementation["scope_kind"],
        "focused_scope_only": True,
        "whole_repository_frozen": False,
        "file_count": manifest["file_count"],
        "path_set_sha256": manifest["path_set_sha256"],
        "content_set_sha256": manifest["content_set_sha256"],
        "manifest_content_sha256": manifest["manifest_content_sha256"],
        "paths": [row["path"] for row in manifest["files"]],
        "files": manifest["files"],
    }
    report["implementation_snapshot"]["snapshot_sha256"] = object_sha256(
        report["implementation_snapshot"]
    )
    report["evidence_catalog"] = [
        *deepcopy(before.get("evidence_catalog", [])),
        {
            "evidence_id": CORRECTED_EVIDENCE_ID,
            "title": "NPC single-admin recovery corrected v2 repository-internal evidence",
            "evidence_type": "INTERNAL_IMPLEMENTATION_AND_AUTOMATED_VERIFICATION_CORRECTION_V2",
            "status": "PASS",
            "evidence_schema": "V2_CORRECTION_ONLY",
            "source_bindings": [
                "npc_single_admin_recovery_rejected_review_r001",
                "npc_single_admin_recovery_implementation_result_v2",
                "npc_single_admin_recovery_verification_result_v2",
            ],
            "result_evidence_sha256_by_kind": {
                "IMPLEMENTATION_RECORD": bytes_sha256(implementation_raw),
                "VERIFICATION_RESULT": bytes_sha256(verification_raw),
            },
            "completion_boundary": trace.completion_boundary(),
        },
    ]
    target["evidence_ids"] = list(
        dict.fromkeys([*target.get("evidence_ids", []), CORRECTED_EVIDENCE_ID])
    )
    target["npc_single_admin_recovery_reassessment"] = {
        "goal_id": GOAL_ID,
        "evidence_schema": "V2_CORRECTION_ONLY",
        "start_event_sequence": trace.EXPECTED_START_EVENT_SEQUENCE,
        "start_event_id": trace.EXPECTED_START_EVENT_ID,
        "implementation_record_sha256": bytes_sha256(implementation_raw),
        "verification_result_sha256": bytes_sha256(verification_raw),
        "internal_implementation_status": "PASS",
        "internal_verification_status": "PASS",
        **trace.completion_boundary(),
    }
    target["npc_single_admin_recovery_evidence_correction"] = {
        "predecessor_report_id": before["metadata"]["report_id"],
        "predecessor_current_internal_evidence_disposition": "SUPERSEDED_BY_V2_CORRECTION_ONLY",
        "rejected_review_round_id": review["round_id"],
        "rejected_review_sha256": bytes_sha256(review_raw),
        "current_evidence_schema": "V2_CORRECTION_ONLY",
        "assessment_status_unchanged": True,
    }
    target.pop("assessment_sha256", None)
    target["assessment_sha256"] = object_sha256(target)

    for row in report["assessments"]:
        if row["gap_id"] != GAP_ID:
            require(
                row == before_by_id[row["gap_id"]],
                f"non-target assessment changed: {row['gap_id']}",
            )
    require(
        next(row for row in report["assessments"] if row["gap_id"] == "GAP-068")
        == gap068_before,
        "GAP-068 recovery drill changed",
    )
    report["summary"] = deepcopy(before["summary"])
    report["summary"]["headline"] = (
        "GAP-008 remains PARTIAL with corrected v2 repository-internal evidence. R026 remains add-only but "
        "its rejected v1 evidence is superseded only for current internal use; every formal and release boundary remains unchanged."
    )
    require(
        report["summary"]["status_counts"] == EXPECTED_STATUS_COUNTS
        and report["summary"]["implemented_and_formally_verified_count"] == 0
        and report["summary"]["release_status"] == "NOT_ELIGIBLE",
        "R027 status, formal credit, or release boundary changed",
    )
    report["reassessment_scope"] = deepcopy(before["reassessment_scope"])
    report["reassessment_scope"]["mode"] = (
        "FOCUSED_GAP008_CURRENT_INTERNAL_EVIDENCE_CORRECTION_V2_WITH_R026_CARRY_FORWARD"
    )
    report["npc_single_admin_recovery_verification_boundary"] = trace.completion_boundary()
    report["npc_single_admin_recovery_evidence_correction"] = _correction_lineage(
        before,
        implementation,
        verification,
        review,
        predecessor_raw_by_path=predecessor_raw_by_path,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
        review_raw=review_raw,
    )
    report["ad_hoc_validation"] = {
        **deepcopy(before["ad_hoc_validation"]),
        "formal_evidence": False,
        "actual_device_evidence": False,
        "actual_recovery_drill_evidence": False,
        "external_security_review_evidence": False,
        "external_legal_review_evidence": False,
        "external_accessibility_review_evidence": False,
        "production_deployment_evidence": False,
        "source": trace.V2_VERIFICATION_REL.as_posix(),
        "verification_result_sha256": bytes_sha256(verification_raw),
        "evidence_schema": "V2_CORRECTION_ONLY",
    }
    report["limitations"] = [
        "Only GAP-008 current repository-internal evidence lineage is corrected; all statuses and counts are unchanged from R026.",
        "R026 and the rejected R001 review remain immutable add-only history.",
        "TC-NPC-SINGLE-ADMIN-RECOVERY-01 remains NOT_RUN.",
        "The actual lost-phone recovery drill and GAP-068 remain BLOCKED/NOT_RUN.",
        "Device, external review, deployment, approval and release evidence remain NOT_RUN and unwaived.",
    ]
    report["report_content_sha256"] = object_sha256(report)
    return report


def build_backlog(
    predecessor: Mapping[str, Any],
    gap: Mapping[str, Any],
    review: Mapping[str, Any],
    *,
    predecessor_raw_by_path: Mapping[Path, bytes],
    implementation_raw: bytes,
    verification_raw: bytes,
    review_raw: bytes,
) -> dict[str, Any]:
    before = deepcopy(dict(predecessor))
    backlog = deepcopy(before)
    backlog.pop("backlog_content_sha256", None)
    metadata = deepcopy(backlog["metadata"])
    metadata.update(
        {
            "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260813-027",
            "version": "0.27.0",
            "prepared_at": gap["metadata"]["prepared_at"],
            "predecessor_backlog_id": before["metadata"]["backlog_id"],
        }
    )
    backlog["metadata"] = metadata
    backlog["source_predecessor"] = {
        "path": R026_BACKLOG_JSON_REL.as_posix(),
        "file_sha256": bytes_sha256(predecessor_raw_by_path[R026_BACKLOG_JSON_REL]),
        "markdown_path": R026_BACKLOG_MD_REL.as_posix(),
        "markdown_file_sha256": bytes_sha256(predecessor_raw_by_path[R026_BACKLOG_MD_REL]),
        "preserved_unchanged": True,
    }
    backlog["gap_report_content_sha256"] = gap["report_content_sha256"]
    backlog["npc_single_admin_recovery_verification_boundary"] = trace.completion_boundary()
    backlog["npc_single_admin_recovery_evidence_correction"] = {
        "kind": "ADDITIVE_INTERNAL_EVIDENCE_CORRECTION",
        "scope": "CURRENT_REPOSITORY_INTERNAL_EVIDENCE_ONLY",
        "predecessor_backlog": {
            "path": R026_BACKLOG_JSON_REL.as_posix(),
            "document_id": before["metadata"]["backlog_id"],
            "sha256": bytes_sha256(predecessor_raw_by_path[R026_BACKLOG_JSON_REL]),
        },
        "predecessor_markdown": {
            "path": R026_BACKLOG_MD_REL.as_posix(),
            "sha256": bytes_sha256(predecessor_raw_by_path[R026_BACKLOG_MD_REL]),
        },
        "predecessor_must_remain_add_only": True,
        "predecessor_current_internal_evidence_disposition": "SUPERSEDED_BY_V2_CORRECTION_ONLY",
        "rejected_review": {
            "path": R001_REJECTED_REVIEW_REL.as_posix(),
            "round_id": review["round_id"],
            "sha256": bytes_sha256(review_raw),
        },
        "current_v2_result_sha256_by_kind": {
            "IMPLEMENTATION_RECORD": bytes_sha256(implementation_raw),
            "VERIFICATION_RESULT": bytes_sha256(verification_raw),
        },
        "status_counts_changed": False,
        "next_action_changed": False,
        "formal_device_drill_external_deployment_approval_release_credit_promoted": False,
    }
    require(
        backlog["next_action_sequence"] == before["next_action_sequence"]
        and backlog["epics"] == before["epics"]
        and backlog["next_single_action"] == before["next_single_action"],
        "R027 backlog planning state changed",
    )
    require(
        backlog["next_single_action"].get("source_policy_id") == "FP-022"
        and backlog["next_single_action"].get("gap_id") == "GAP-031"
        and backlog["next_single_action"].get("priority_rank") == 24
        and backlog["next_single_action"].get("work_item_id")
        == "WS-GOAL-EPIC-04-FP-022-R001",
        "R026 next single action differs",
    )
    backlog["backlog_content_sha256"] = object_sha256(backlog)
    return backlog


def gap_markdown(gap: Mapping[str, Any]) -> str:
    counts = gap["summary"]["status_counts"]
    return (
        "# WalkSafe implementation gap analysis R027\n\n"
        f"- Report: `{gap['metadata']['report_id']}`\n"
        f"- Focus: `{GAP_ID}` / `{POLICY_ID}` stays `PARTIAL`.\n"
        f"- Counts unchanged: MISSING `{counts['MISSING']}`, PARTIAL `{counts['PARTIAL']}`, IMPLEMENTED `{counts['IMPLEMENTED']}`.\n"
        "- Correction: R026 remains add-only; its rejected v1 evidence is superseded only for current internal use by sealed v2 evidence.\n"
        "- Boundary: actual recovery drill, formal, device, external, deployment, approval and release evidence remain `NOT_RUN`; release remains `NOT_ELIGIBLE`.\n"
    )


def backlog_markdown(backlog: Mapping[str, Any]) -> str:
    action = backlog["next_single_action"]
    return (
        "# WalkSafe implementation remediation backlog R027\n\n"
        "- `NPC-SINGLE-ADMIN-RECOVERY / GAP-008`: `PARTIAL`; only its current internal evidence lineage is corrected to v2.\n"
        "- R026 remains immutable add-only history and its v1 evidence has zero current promotion authority.\n"
        f"- Next rank `{action['priority_rank']}` stays `{action['source_policy_id']} / {action['gap_id']}` — `{action['status']}`.\n"
        "- Formal, device, recovery-drill, external, deployment, approval and release credit: zero.\n"
    )


def build_documents(
    predecessor_gap: Mapping[str, Any],
    predecessor_backlog: Mapping[str, Any],
    implementation: Mapping[str, Any],
    verification: Mapping[str, Any],
    review: Mapping[str, Any],
    *,
    predecessor_raw_by_path: Mapping[Path, bytes],
    implementation_raw: bytes,
    verification_raw: bytes,
    review_raw: bytes,
    review_subject: Mapping[str, Any],
    review_subject_raw: bytes,
    v1_implementation_raw: bytes,
    v1_verification_raw: bytes,
    v1_successor_raw: bytes,
    reviewed_evidence_raw_by_path: Mapping[Path, bytes],
    rebuilt_v2_outputs: Mapping[Path, str],
    expected_predecessor_sha256_by_path: Mapping[Path, str] = EXPECTED_R026_SHA256_BY_PATH,
    expected_review_sha256: str = EXPECTED_R001_REVIEW_SHA256,
) -> dict[Path, str]:
    _validate_r026_inputs(
        predecessor_gap,
        predecessor_backlog,
        predecessor_raw_by_path,
        expected_predecessor_sha256_by_path,
    )
    _validate_v2_results(
        implementation,
        verification,
        implementation_raw,
        verification_raw,
        rebuilt_v2_outputs,
    )
    _validate_rejected_review(
        review,
        review_raw,
        review_subject,
        review_subject_raw,
        v1_implementation_raw,
        v1_verification_raw,
        v1_successor_raw,
        reviewed_evidence_raw_by_path,
        predecessor_gap,
        implementation["observed_at"],
        expected_review_sha256,
    )
    gap = build_gap(
        predecessor_gap,
        implementation,
        verification,
        review,
        predecessor_raw_by_path=predecessor_raw_by_path,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
        review_raw=review_raw,
    )
    backlog = build_backlog(
        predecessor_backlog,
        gap,
        review,
        predecessor_raw_by_path=predecessor_raw_by_path,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
        review_raw=review_raw,
    )
    trace.verify_seal(gap, "report_content_sha256", "R027 gap")
    trace.verify_seal(backlog, "backlog_content_sha256", "R027 backlog")
    return {
        R027_GAP_JSON_REL: json_text(gap),
        R027_GAP_MD_REL: gap_markdown(gap),
        R027_BACKLOG_JSON_REL: json_text(backlog),
        R027_BACKLOG_MD_REL: backlog_markdown(backlog),
    }


def _reviewed_v1_artifact_overrides(root: Path) -> dict[Path, bytes]:
    # Runtime import avoids the module initialization cycle: the exact-six
    # successor imports this R027 producer while it is being initialized.
    from scripts import (  # noqa: PLC0415
        build_walksafe_npc_single_admin_recovery_artifact_trace_correction_v2_20260813
        as artifact_v2,
    )

    return artifact_v2.project_r001_reviewed_v1_artifacts(root)


def _load_r001_review_inputs(root: Path) -> R001ReviewInputs:
    reviewed_v1_overrides = _reviewed_v1_artifact_overrides(root)
    review_raw = trace.read_bytes(root, R001_REJECTED_REVIEW_REL)
    review = trace.strict_json_bytes(review_raw, R001_REJECTED_REVIEW_REL.as_posix())
    review_subject_raw = trace.read_bytes(root, trace.REVIEW_SUBJECT_REL)
    review_subject = trace.strict_json_bytes(
        review_subject_raw, trace.REVIEW_SUBJECT_REL.as_posix()
    )
    reviewed_evidence_raw_by_path: dict[Path, bytes] = {}
    evidence_manifest = review.get("reviewed_evidence_manifest")
    require(type(evidence_manifest) is list, "R001 evidence manifest differs")
    rows = [*evidence_manifest]
    consumer_bindings = review_subject.get("reviewed_consumer_bindings")
    require(type(consumer_bindings) is list, "v1 review subject consumers differ")
    rows.extend(consumer_bindings)
    for row in rows:
        require(
            type(row) is dict and type(row.get("path")) is str,
            "R001 evidence or consumer path differs",
        )
        relative = Path(row["path"])
        require(
            relative.as_posix() == row["path"]
            and not relative.is_absolute()
            and ".." not in relative.parts,
            "R001 evidence or consumer path differs",
        )
        raw = reviewed_v1_overrides.get(relative)
        if raw is None:
            raw = trace.read_bytes(root, relative)
        previous = reviewed_evidence_raw_by_path.setdefault(relative, raw)
        require(previous == raw, "R001 evidence path resolves to conflicting bytes")
    require(
        set(reviewed_v1_overrides).issubset(reviewed_evidence_raw_by_path),
        "historical R001 artifact override inventory differs",
    )
    return R001ReviewInputs(
        review=review,
        review_raw=review_raw,
        review_subject=review_subject,
        review_subject_raw=review_subject_raw,
        v1_implementation_raw=trace.read_bytes(root, trace.IMPLEMENTATION_REL),
        v1_verification_raw=trace.read_bytes(root, trace.VERIFICATION_REL),
        v1_successor_raw=trace.read_bytes(root, trace.SUCCESSOR_REL),
        reviewed_evidence_raw_by_path=reviewed_evidence_raw_by_path,
    )


def load_validated_r001_review(root: Path = ROOT) -> tuple[dict[str, Any], bytes]:
    """Load the exact R001 rejection only after its physical v1 chain validates."""
    root = root.resolve()
    inputs = _load_r001_review_inputs(root)
    predecessor_gap_raw = trace.read_bytes(root, R026_GAP_JSON_REL)
    predecessor_gap = trace.strict_json_bytes(
        predecessor_gap_raw, R026_GAP_JSON_REL.as_posix()
    )
    require(
        bytes_sha256(predecessor_gap_raw)
        == EXPECTED_R026_SHA256_BY_PATH[R026_GAP_JSON_REL],
        "canonical R026 gap predecessor bytes differ",
    )
    v2_implementation_raw = trace.read_bytes(root, trace.V2_IMPLEMENTATION_REL)
    v2_implementation = trace.strict_json_bytes(
        v2_implementation_raw, trace.V2_IMPLEMENTATION_REL.as_posix()
    )
    _validate_rejected_review(
        inputs.review,
        inputs.review_raw,
        inputs.review_subject,
        inputs.review_subject_raw,
        inputs.v1_implementation_raw,
        inputs.v1_verification_raw,
        inputs.v1_successor_raw,
        inputs.reviewed_evidence_raw_by_path,
        predecessor_gap,
        v2_implementation.get("observed_at"),
        EXPECTED_R001_REVIEW_SHA256,
    )
    return deepcopy(inputs.review), inputs.review_raw


def _validated_r001_history_sha256_by_path(
    review: Mapping[str, Any],
    raw_by_path: Mapping[Path, bytes],
) -> dict[Path, str]:
    require(
        len(R001_HISTORY_PATHS) == 13
        and len(set(R001_HISTORY_PATHS)) == 13
        and sum(path.suffix == ".json" for path in R001_HISTORY_PATHS) == 9
        and sum(path.suffix == ".log" for path in R001_HISTORY_PATHS) == 4,
        "R001 history declared inventory differs",
    )
    require(
        set(raw_by_path) == set(R001_HISTORY_PATHS),
        "R001 history physical inventory differs",
    )

    evidence_manifest = review.get("reviewed_evidence_manifest")
    require(type(evidence_manifest) is list, "R001 history review manifest differs")
    manifest_sha256_by_path: dict[Path, str] = {}
    for row in evidence_manifest:
        require(
            type(row) is dict
            and set(row) == {"path", "sha256"}
            and type(row.get("path")) is str
            and type(row.get("sha256")) is str,
            "R001 history review manifest row differs",
        )
        path = Path(row["path"])
        require(
            path.as_posix() == row["path"]
            and not path.is_absolute()
            and ".." not in path.parts
            and trace.SHA256_RE.fullmatch(row["sha256"]) is not None
            and path not in manifest_sha256_by_path,
            "R001 history review manifest path or digest differs",
        )
        manifest_sha256_by_path[path] = row["sha256"]
    require(
        set(_R001_REVIEW_MANIFEST_HISTORY_PATHS).issubset(
            manifest_sha256_by_path
        ),
        "R001 history review-manifest inventory differs",
    )

    verification_raw = raw_by_path[trace.VERIFICATION_REL]
    verification = trace.strict_json_bytes(
        verification_raw, trace.VERIFICATION_REL.as_posix()
    )
    trace.verify_seal(
        verification, "verification_result_content_sha256", "v1 verification"
    )
    lane_rows = verification.get("lane_receipts")
    require(
        type(lane_rows) is list
        and [row.get("lane_id") for row in lane_rows if type(row) is dict]
        == list(_R001_LANE_FILE_STEM_BY_ID),
        "R001 history lane receipt inventory differs",
    )

    expected_sha256_by_path = {
        path: manifest_sha256_by_path[path]
        for path in _R001_REVIEW_MANIFEST_HISTORY_PATHS
    }
    for row in lane_rows:
        require(
            type(row) is dict
            and set(row) == {"lane_id", "path", "sha256"}
            and row.get("lane_id") in _R001_LANE_FILE_STEM_BY_ID
            and type(row.get("path")) is str
            and type(row.get("sha256")) is str
            and trace.SHA256_RE.fullmatch(row["sha256"]) is not None,
            "R001 history lane receipt row differs",
        )
        lane_id = row["lane_id"]
        receipt_path = _R001_LANE_RECEIPT_PATH_BY_ID[lane_id]
        log_path = _R001_LOG_PATH_BY_ID[lane_id]
        require(
            row["path"] == receipt_path.as_posix(),
            "R001 history lane receipt path differs",
        )
        expected_sha256_by_path[receipt_path] = row["sha256"]

        receipt_raw = raw_by_path[receipt_path]
        receipt = trace.strict_json_bytes(receipt_raw, receipt_path.as_posix())
        trace.verify_seal(receipt, "receipt_content_sha256", f"v1 lane {lane_id}")
        raw_output_binding = receipt.get("raw_output_binding")
        observation = receipt.get("observation")
        log_raw = raw_by_path[log_path]
        log_sha256 = bytes_sha256(log_raw)
        require(
            receipt.get("goal_id") == GOAL_ID
            and receipt.get("status") == "PASS"
            and type(raw_output_binding) is dict
            and raw_output_binding
            == {
                "byte_length": len(log_raw),
                "path": log_path.as_posix(),
                "sha256": log_sha256,
            }
            and type(observation) is dict
            and observation.get("lane_id") == lane_id
            and observation.get("log_path") == log_path.as_posix()
            and observation.get("log_sha256") == log_sha256
            and observation.get("log_byte_count") == len(log_raw)
            and manifest_sha256_by_path[log_path] == log_sha256,
            f"R001 history lane receipt/log binding differs: {lane_id}",
        )

    require(
        set(expected_sha256_by_path) == set(R001_HISTORY_PATHS),
        "R001 history expected digest inventory differs",
    )
    observed_sha256_by_path = {
        path: bytes_sha256(raw_by_path[path]) for path in R001_HISTORY_PATHS
    }
    require(
        observed_sha256_by_path == expected_sha256_by_path,
        "R001 history physical bytes differ from reviewed digest chain",
    )
    return observed_sha256_by_path


def load_validated_r001_history_sha256_by_path(
    root: Path = ROOT,
) -> dict[Path, str]:
    """Return the exact immutable 9-JSON/4-log R001 history digest map."""
    root = root.resolve()
    review, review_raw = load_validated_r001_review(root)
    raw_by_path = {path: trace.read_bytes(root, path) for path in R001_HISTORY_PATHS}
    result = _validated_r001_history_sha256_by_path(review, raw_by_path)
    require(
        trace.read_bytes(root, R001_REJECTED_REVIEW_REL) == review_raw
        and all(
            trace.read_bytes(root, path) == raw_by_path[path]
            for path in R001_HISTORY_PATHS
        ),
        "R001 history cohort changed during validation",
    )
    return result


def build_outputs(root: Path = ROOT) -> dict[Path, str]:
    reviewed_v1_overrides = _reviewed_v1_artifact_overrides(root)
    predecessor_raw = {path: trace.read_bytes(root, path) for path in R026_INPUT_PATHS}
    observation_raw = trace.read_bytes(root, trace.V2_OBSERVATION_MANIFEST_REL)
    observation = trace.strict_json_bytes(
        observation_raw, trace.V2_OBSERVATION_MANIFEST_REL.as_posix()
    )
    rebuilt_v2_outputs = trace.build_results_outputs(
        observation,
        observation_raw,
        root=root,
    )
    implementation_raw = trace.read_bytes(root, trace.V2_IMPLEMENTATION_REL)
    verification_raw = trace.read_bytes(root, trace.V2_VERIFICATION_REL)
    r001 = _load_r001_review_inputs(root)
    require(
        all(
            r001.reviewed_evidence_raw_by_path.get(path) == raw
            for path, raw in reviewed_v1_overrides.items()
        ),
        "historical R001 artifact bytes changed during build",
    )
    outputs = build_documents(
        trace.strict_json_bytes(predecessor_raw[R026_GAP_JSON_REL], R026_GAP_JSON_REL.as_posix()),
        trace.strict_json_bytes(
            predecessor_raw[R026_BACKLOG_JSON_REL], R026_BACKLOG_JSON_REL.as_posix()
        ),
        trace.strict_json_bytes(implementation_raw, trace.V2_IMPLEMENTATION_REL.as_posix()),
        trace.strict_json_bytes(verification_raw, trace.V2_VERIFICATION_REL.as_posix()),
        r001.review,
        predecessor_raw_by_path=predecessor_raw,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
        review_raw=r001.review_raw,
        review_subject=r001.review_subject,
        review_subject_raw=r001.review_subject_raw,
        v1_implementation_raw=r001.v1_implementation_raw,
        v1_verification_raw=r001.v1_verification_raw,
        v1_successor_raw=r001.v1_successor_raw,
        reviewed_evidence_raw_by_path=r001.reviewed_evidence_raw_by_path,
        rebuilt_v2_outputs=rebuilt_v2_outputs,
    )
    direct_raw_by_path = {
        **predecessor_raw,
        trace.V2_OBSERVATION_MANIFEST_REL: observation_raw,
        trace.V2_IMPLEMENTATION_REL: implementation_raw,
        trace.V2_VERIFICATION_REL: verification_raw,
        R001_REJECTED_REVIEW_REL: r001.review_raw,
        trace.REVIEW_SUBJECT_REL: r001.review_subject_raw,
        trace.IMPLEMENTATION_REL: r001.v1_implementation_raw,
        trace.VERIFICATION_REL: r001.v1_verification_raw,
        trace.SUCCESSOR_REL: r001.v1_successor_raw,
        **r001.reviewed_evidence_raw_by_path,
    }
    require(
        all(
            reviewed_v1_overrides.get(path, trace.read_bytes(root, path)) == raw
            for path, raw in direct_raw_by_path.items()
        ),
        "R027 input cohort changed before publication",
    )
    require(
        _reviewed_v1_artifact_overrides(root) == reviewed_v1_overrides,
        "historical R001 artifact projection changed before publication",
    )
    rebuilt_at_end = trace.build_results_outputs(
        observation,
        observation_raw,
        root=root,
    )
    require(
        rebuilt_at_end == rebuilt_v2_outputs,
        "R027 transitive v2 input cohort changed before publication",
    )
    return outputs


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--root", type=Path, default=ROOT)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        outputs = build_outputs(args.root.resolve())
        trace.write_or_check_outputs(args.root.resolve(), outputs, write=args.write)
    except (BuildError, OSError, ValueError, TypeError) as exc:
        print(f"NPC single-admin recovery R027 gap/backlog: FAIL: {exc}")
        return 1
    print("NPC single-admin recovery R027 gap/backlog: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

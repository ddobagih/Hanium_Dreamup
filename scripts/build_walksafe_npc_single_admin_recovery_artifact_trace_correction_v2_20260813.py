#!/usr/bin/env python3
"""Publish the add-only v2 correction marker to the six NPC trace artifacts."""

from __future__ import annotations

import argparse
from copy import deepcopy
import fcntl
import json
import os
from pathlib import Path
import stat
from typing import Any, Mapping, Sequence

from scripts import build_walksafe_npc_single_admin_recovery_artifact_trace_successor_20260812 as v1
from scripts import build_walksafe_npc_single_admin_recovery_gap_backlog_r027_20260813 as r027
from scripts import build_walksafe_npc_single_admin_recovery_trace_20260812 as trace


ROOT = Path(__file__).resolve().parents[1]
GOAL_ID = trace.GOAL_ID
SUCCESSOR_ID = (
    "WS-NPC-SINGLE-ADMIN-RECOVERY-ARTIFACT-TRACE-CORRECTION-20260813-002"
)
CHANGE_ID = "CHG-DOC-0018"
PREPARED_ON = "2026-08-13"
MARKER_FIELD = "npc_single_admin_recovery_artifact_trace_correction_v2"
OUTPUT_PATHS = v1.OUTPUT_PATHS
DOC05_REL = v1.DOC05_REL
DOC01_REL = v1.DOC01_REL
RTM_REL = v1.RTM_REL
DESIGN_REL = v1.DESIGN_REL
IMPLEMENTATION_MANIFEST_REL = v1.IMPLEMENTATION_MANIFEST_REL
MODULE_REGISTER_REL = v1.MODULE_REGISTER_REL
SEAL_FIELD_BY_PATH = v1.SEAL_FIELD_BY_PATH
TARGET_ARTIFACT_PATHS = v1.TARGET_ARTIFACT_PATHS
V1_MARKER_FIELD = "npc_single_admin_recovery_artifact_trace_successor"
V1_SUCCESSOR_ID = v1.SUCCESSOR_ID
EXPECTED_V1_SHA256_BY_PATH = {
    DOC05_REL: "09c5fdb8eee426a7d60cf10639bf7d6ec9fc41ef2862bec8912282635a5ba96c",
    DOC01_REL: "70305918b52204097db9376de642f191b19b4c4f7925d2a1bedf0f5e115465b0",
    RTM_REL: "2bfb1420660e48fb9df1a0d8311da98fa95381a0ea932dc4fc942f84b3695cab",
    DESIGN_REL: "4159a80cc7cdf74011db80c08dbfcbf68eade6f652a543d8cc6979b168a0dea6",
    IMPLEMENTATION_MANIFEST_REL: "b2b4f25dbd21781dbd3624bc4ba90c70080cd05bbd4b247bb5dc1f5c64408a03",
    MODULE_REGISTER_REL: "2e4b3dc105efc07adcd851d770a20cf79925a06bcb7c128381f87bd8f96bb709",
}
R001_REJECTED_REVIEW_REL = trace.R001_REJECTED_REVIEW_REL
R027_OUTPUT_PATHS = r027.OUTPUT_PATHS
R027_GAP_REL = r027.R027_GAP_JSON_REL
BUILDER_REL = Path(
    "scripts/build_walksafe_npc_single_admin_recovery_artifact_trace_correction_v2_20260813.py"
)
LIVE_INPUT_PATHS = (
    trace.V2_IMPLEMENTATION_REL,
    trace.V2_VERIFICATION_REL,
    *R027_OUTPUT_PATHS,
    R001_REJECTED_REVIEW_REL,
    BUILDER_REL,
)
TRANSACTION_JOURNAL_REL = Path(
    ".walksafe-npc-single-admin-recovery-artifact-correction-v2-20260813.transaction.json"
)
TRANSACTION_TOKEN = "walksafe-npc-single-admin-recovery-artifact-correction-v2-20260813-v1"

BuildError = trace.BuildError
require = trace.require
bytes_sha256 = trace.bytes_sha256
json_text = trace.json_text


def boundary() -> dict[str, Any]:
    return {
        "scope": "REPOSITORY_INTERNAL_NPC_SINGLE_ADMIN_RECOVERY_V2_CORRECTION_TRACE_ONLY",
        "internal_implementation_status": "PASS",
        "internal_verification_status": "PASS",
        "formal_test_status": "NOT_RUN",
        "formal_test_credit_count": 0,
        "actual_device_status": "NOT_RUN",
        "actual_recovery_drill_status": "NOT_RUN",
        "external_evidence_status": "NOT_RUN",
        "external_evidence_credit_count": 0,
        "production_deployment_status": "NOT_RUN",
        "artifact_approval_claimed": False,
        "approval_credit_count": 0,
        "release_status": "NOT_ELIGIBLE",
        "release_credit_count": 0,
        "review_or_completion_inputs_included": False,
    }


def _document_id(value: Mapping[str, Any]) -> str:
    metadata = value.get("metadata")
    for candidate in (
        value.get("document_id"),
        metadata.get("report_id") if type(metadata) is dict else None,
        metadata.get("backlog_id") if type(metadata) is dict else None,
    ):
        if type(candidate) is str and candidate:
            return candidate
    return "UNKNOWN"


def _binding(
    name: str,
    path: Path,
    value: Mapping[str, Any],
    raw: bytes,
    relation: str,
) -> dict[str, Any]:
    return {
        "name": name,
        "path": path.as_posix(),
        "sha256": bytes_sha256(raw),
        "byte_length": len(raw),
        "relation": relation,
        "document_id": _document_id(value),
    }


def _input_bindings(
    implementation: Mapping[str, Any],
    verification: Mapping[str, Any],
    gap: Mapping[str, Any],
    review: Mapping[str, Any],
    *,
    implementation_raw: bytes,
    verification_raw: bytes,
    gap_raw: bytes,
    review_raw: bytes,
    r027_raw_by_path: Mapping[Path, bytes],
) -> list[dict[str, Any]]:
    require(
        set(r027_raw_by_path) == set(R027_OUTPUT_PATHS)
        and r027_raw_by_path[R027_GAP_REL] == gap_raw,
        "R027 four-output provenance inventory differs",
    )
    backlog_raw = r027_raw_by_path[r027.R027_BACKLOG_JSON_REL]
    backlog = trace.strict_json_bytes(
        backlog_raw, r027.R027_BACKLOG_JSON_REL.as_posix()
    )
    require(
        backlog_raw == json_text(backlog).encode("utf-8"),
        "R027 backlog JSON is noncanonical",
    )
    return [
        _binding(
            "npc_single_admin_recovery_v2_implementation_result",
            trace.V2_IMPLEMENTATION_REL,
            implementation,
            implementation_raw,
            "NPC_V2_INTERNAL_IMPLEMENTATION_RESULT",
        ),
        _binding(
            "npc_single_admin_recovery_v2_verification_result",
            trace.V2_VERIFICATION_REL,
            verification,
            verification_raw,
            "NPC_V2_INTERNAL_VERIFICATION_RESULT",
        ),
        _binding(
            "npc_gap008_r027_correction_successor",
            R027_GAP_REL,
            gap,
            gap_raw,
            "GAP008_R027_V2_CORRECTION_RESULT",
        ),
        _binding(
            "npc_gap008_r027_correction_successor_markdown",
            r027.R027_GAP_MD_REL,
            gap,
            r027_raw_by_path[r027.R027_GAP_MD_REL],
            "GAP008_R027_V2_CORRECTION_MARKDOWN",
        ),
        _binding(
            "npc_gap008_r027_backlog_successor",
            r027.R027_BACKLOG_JSON_REL,
            backlog,
            backlog_raw,
            "GAP008_R027_V2_CORRECTION_BACKLOG",
        ),
        _binding(
            "npc_gap008_r027_backlog_successor_markdown",
            r027.R027_BACKLOG_MD_REL,
            backlog,
            r027_raw_by_path[r027.R027_BACKLOG_MD_REL],
            "GAP008_R027_V2_CORRECTION_BACKLOG_MARKDOWN",
        ),
        _binding(
            "npc_rejected_internal_review_r001",
            R001_REJECTED_REVIEW_REL,
            review,
            review_raw,
            "REJECTED_PREDECESSOR_REVIEW_ADD_ONLY",
        ),
    ]


def _review_decision(review: Mapping[str, Any]) -> Any:
    return review.get("decision", review.get("verdict", review.get("status")))


def validate_inputs(
    implementation: Mapping[str, Any],
    verification: Mapping[str, Any],
    gap: Mapping[str, Any],
    review: Mapping[str, Any],
    *,
    implementation_raw: bytes,
    verification_raw: bytes,
    gap_raw: bytes,
    review_raw: bytes,
    rebuilt_v2_outputs: Mapping[Path, str] | None = None,
) -> None:
    for value, raw, field, label in (
        (
            implementation,
            implementation_raw,
            "implementation_record_content_sha256",
            "v2 implementation",
        ),
        (
            verification,
            verification_raw,
            "verification_result_content_sha256",
            "v2 verification",
        ),
        (gap, gap_raw, "report_content_sha256", "R027 gap"),
    ):
        require(raw == json_text(value).encode("utf-8"), f"{label} JSON is noncanonical")
        trace.verify_seal(value, field, label)
    require(
        review_raw == json_text(review).encode("utf-8"),
        "R001 rejected review JSON is noncanonical",
    )
    require(
        implementation.get("goal_id") == GOAL_ID
        and implementation.get("status") == "PASS_INTERNAL"
        and implementation.get("evidence_schema") == "V2_CORRECTION_ONLY"
        and implementation.get("completion_boundary") == trace.completion_boundary(),
        "v2 implementation identity/status/boundary differs",
    )
    require(
        verification.get("goal_id") == GOAL_ID
        and verification.get("status") == "PASS_INTERNAL"
        and verification.get("evidence_schema") == "V2_CORRECTION_ONLY"
        and verification.get("implementation_record_sha256")
        == bytes_sha256(implementation_raw)
        and verification.get("completion_boundary") == trace.completion_boundary(),
        "v2 verification identity/status/boundary differs",
    )
    require(
        review.get("goal_id") == GOAL_ID
        and _review_decision(review) == "REJECTED",
        "R001 predecessor review is not rejected",
    )
    metadata = gap.get("metadata")
    require(
        type(metadata) is dict
        and type(metadata.get("report_id")) is str
        and metadata["report_id"].endswith("-027"),
        "R027 report identity differs",
    )
    predecessor = gap.get("source_predecessor")
    require(
        type(predecessor) is dict
        and predecessor.get("path") == r027.R026_GAP_JSON_REL.as_posix()
        and predecessor.get("file_sha256")
        == r027.EXPECTED_R026_SHA256_BY_PATH[r027.R026_GAP_JSON_REL]
        and predecessor.get("preserved_unchanged") is True,
        "R027 exact R026 predecessor binding differs",
    )
    correction = gap.get("npc_single_admin_recovery_evidence_correction")
    require(
        type(correction) is dict
        and correction.get("predecessor_current_internal_evidence_disposition")
        == "SUPERSEDED_BY_V2_CORRECTION_ONLY"
        and correction.get(
            "formal_device_drill_external_deployment_approval_release_credit_promoted"
        )
        is False,
        "R027 add-only correction disposition differs",
    )
    assessments = gap.get("assessments")
    require(type(assessments) is list, "R027 assessments missing")
    target = [row for row in assessments if row.get("gap_id") == trace.GAP_ID]
    drill = [row for row in assessments if row.get("gap_id") == "GAP-068"]
    require(
        len(target) == 1
        and target[0].get("status") == "PARTIAL"
        and target[0].get("formal_test_status") == "NOT_RUN",
        "R027 GAP-008 boundary differs",
    )
    require(
        len(drill) == 1
        and drill[0].get("status") == "BLOCKED"
        and drill[0].get("formal_test_status") == "NOT_RUN",
        "R027 GAP-068 boundary differs",
    )
    summary = gap.get("summary")
    require(
        type(summary) is dict
        and summary.get("status_counts") == r027.EXPECTED_STATUS_COUNTS
        and summary.get("release_status") == "NOT_ELIGIBLE"
        and summary.get("implemented_and_formally_verified_count") == 0,
        "R027 release/credit boundary differs",
    )
    if rebuilt_v2_outputs is not None:
        r027._validate_v2_results(
            implementation,
            verification,
            implementation_raw,
            verification_raw,
            rebuilt_v2_outputs,
        )


def validate_v1_predecessors(
    predecessors: Mapping[Path, Mapping[str, Any]],
    predecessor_raw: Mapping[Path, bytes],
    *,
    expected_sha256_by_path: Mapping[Path, str] = EXPECTED_V1_SHA256_BY_PATH,
) -> None:
    require(
        set(predecessors) == set(predecessor_raw) == set(OUTPUT_PATHS),
        "six-artifact v1 predecessor inventory differs",
    )
    require(
        dict(expected_sha256_by_path) == dict(EXPECTED_V1_SHA256_BY_PATH),
        "six-artifact v1 pin set differs",
    )
    for relative in OUTPUT_PATHS:
        raw = predecessor_raw[relative]
        document = predecessors[relative]
        require(
            bytes_sha256(raw) == expected_sha256_by_path[relative],
            f"v1 predecessor SHA-256 differs: {relative}",
        )
        require(
            raw == json_text(document).encode("utf-8"),
            f"v1 predecessor JSON is noncanonical: {relative}",
        )
        v1.verify_projection_seal(
            document, SEAL_FIELD_BY_PATH[relative], f"v1 predecessor {relative}"
        )
        marker = document.get(V1_MARKER_FIELD)
        require(
            type(marker) is dict
            and marker.get("successor_id") == V1_SUCCESSOR_ID
            and marker.get("physical_path") == relative.as_posix()
            and MARKER_FIELD not in document,
            f"v1 predecessor marker differs: {relative}",
        )


def _marker(
    relative: Path,
    predecessor_sha256: str,
    v1_marker: Mapping[str, Any],
    bindings: list[dict[str, Any]],
) -> dict[str, Any]:
    return {
        "successor_id": SUCCESSOR_ID,
        "goal_id": GOAL_ID,
        "policy_id": trace.POLICY_ID,
        "gap_id": trace.GAP_ID,
        "change_id": CHANGE_ID,
        "physical_path": relative.as_posix(),
        "predecessor_sha256": predecessor_sha256,
        "preserved_v1_successor_id": V1_SUCCESSOR_ID,
        "preserved_v1_marker_sha256": v1.artifact_object_sha256(v1_marker),
        "v1_marker_remains_add_only": True,
        "semantic_change": "V2_CORRECTION_PROGRESS_BINDING_ONLY",
        "input_bindings": deepcopy(bindings),
        "trace_boundary": boundary(),
        "publication_order": [path.as_posix() for path in OUTPUT_PATHS],
    }


def build_documents(
    predecessors: Mapping[Path, Mapping[str, Any]],
    predecessor_raw: Mapping[Path, bytes],
    implementation: Mapping[str, Any],
    verification: Mapping[str, Any],
    gap: Mapping[str, Any],
    review: Mapping[str, Any],
    *,
    implementation_raw: bytes,
    verification_raw: bytes,
    gap_raw: bytes,
    review_raw: bytes,
    generator_sha256: str,
    r027_raw_by_path: Mapping[Path, bytes],
    validate_input_contract: bool = True,
) -> dict[Path, str]:
    validate_v1_predecessors(predecessors, predecessor_raw)
    if validate_input_contract:
        validate_inputs(
            implementation,
            verification,
            gap,
            review,
            implementation_raw=implementation_raw,
            verification_raw=verification_raw,
            gap_raw=gap_raw,
            review_raw=review_raw,
        )
    require(trace.SHA256_RE.fullmatch(generator_sha256) is not None, "generator SHA-256 differs")
    bindings = _input_bindings(
        implementation,
        verification,
        gap,
        review,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
        gap_raw=gap_raw,
        review_raw=review_raw,
        r027_raw_by_path=r027_raw_by_path,
    )
    documents = {path: deepcopy(dict(predecessors[path])) for path in OUTPUT_PATHS}
    for relative in OUTPUT_PATHS:
        document = documents[relative]
        document[MARKER_FIELD] = _marker(
            relative,
            EXPECTED_V1_SHA256_BY_PATH[relative],
            document[V1_MARKER_FIELD],
            bindings,
        )
    changes = documents[DOC05_REL].get("changes")
    require(
        type(changes) is list
        and all(row.get("change_id") != CHANGE_ID for row in changes),
        "DOC-05 correction change already exists",
    )
    changes.append(
        {
            "change_id": CHANGE_ID,
            "date": PREPARED_ON,
            "change_type": "NPC_SINGLE_ADMIN_RECOVERY_V2_ARTIFACT_TRACE_CORRECTION",
            "title": "NPC 단일 관리자 복구 v2 교정 증거를 현재 추적 산출물에 결속",
            "reason": "R001 거부 검토를 보존하면서 v2 구현·검증과 R027 교정만 add-only로 추적하기 위함",
            "before_summary": "여섯 산출물은 거부된 R001의 v1 증거 계보까지만 결속한다.",
            "after_summary": "v1 표지를 보존하고 v2 내부 증거·R027·R001 거부 결과를 교정 진척으로 결속한다.",
            "affected_artifact_codes": list(TARGET_ARTIFACT_PATHS),
            "affected_paths": [path.as_posix() for path in OUTPUT_PATHS],
            "affected_requirement_ids": ["RQ-NPC-SINGLE-ADMIN-RECOVERY-001"],
            "affected_test_ids": ["TC-NPC-SINGLE-ADMIN-RECOVERY-01"],
            "lifecycle_status": "DRAFT",
            "reviewer": None,
            "approved_at": None,
            "review": {
                "review_status": "PENDING",
                "reviewer": None,
                "approval_status": "NOT_APPROVED",
                "approval_record": None,
            },
            "application": {
                "successor_id": SUCCESSOR_ID,
                "input_bindings": deepcopy(bindings),
                "preserved_v1_successor_id": V1_SUCCESSOR_ID,
                "formal_evidence_credit_count": 0,
                "actual_device_credit_count": 0,
                "external_evidence_credit_count": 0,
                "release_credit_count": 0,
            },
            "rollback_or_supersedes": None,
        }
    )
    summary = documents[DOC05_REL].get("summary")
    if type(summary) is dict:
        summary["change_count"] = len(changes)
        summary["last_change_id"] = CHANGE_ID
    for relative in OUTPUT_PATHS:
        v1._projection_seal(documents[relative], SEAL_FIELD_BY_PATH[relative])
    raw_without_doc01_binding = {
        path: json_text(documents[path]).encode("utf-8")
        for path in OUTPUT_PATHS
        if path != DOC01_REL
    }
    doc01_marker = documents[DOC01_REL][MARKER_FIELD]
    doc01_marker["bound_corrected_outputs"] = {
        path.as_posix(): {
            "sha256": bytes_sha256(raw),
            "byte_length": len(raw),
        }
        for path, raw in raw_without_doc01_binding.items()
    }
    doc01_marker["self_physical_sha256_excluded"] = True
    doc01_marker["generator"] = {
        "path": BUILDER_REL.as_posix(),
        "sha256": generator_sha256,
    }
    v1._projection_seal(documents[DOC01_REL], SEAL_FIELD_BY_PATH[DOC01_REL])
    return {path: json_text(documents[path]) for path in OUTPUT_PATHS}


def validate_correction_documents(
    successors: Mapping[Path, Mapping[str, Any]],
    successor_raw: Mapping[Path, bytes],
    *,
    expected_bindings: list[dict[str, Any]] | None = None,
) -> None:
    require(
        set(successors) == set(successor_raw) == set(OUTPUT_PATHS),
        "six-artifact correction inventory differs",
    )
    shared: list[dict[str, Any]] | None = None
    for relative in OUTPUT_PATHS:
        document = successors[relative]
        require(
            successor_raw[relative] == json_text(document).encode("utf-8"),
            f"correction JSON is noncanonical: {relative}",
        )
        v1.verify_projection_seal(
            document, SEAL_FIELD_BY_PATH[relative], f"v2 correction {relative}"
        )
        v1_marker = document.get(V1_MARKER_FIELD)
        marker = document.get(MARKER_FIELD)
        require(
            type(v1_marker) is dict
            and v1_marker.get("successor_id") == V1_SUCCESSOR_ID
            and type(marker) is dict
            and marker.get("successor_id") == SUCCESSOR_ID
            and marker.get("change_id") == CHANGE_ID
            and marker.get("physical_path") == relative.as_posix()
            and marker.get("predecessor_sha256")
            == EXPECTED_V1_SHA256_BY_PATH[relative]
            and marker.get("preserved_v1_successor_id") == V1_SUCCESSOR_ID
            and marker.get("preserved_v1_marker_sha256")
            == v1.artifact_object_sha256(v1_marker)
            and marker.get("v1_marker_remains_add_only") is True
            and marker.get("semantic_change")
            == "V2_CORRECTION_PROGRESS_BINDING_ONLY"
            and marker.get("trace_boundary") == boundary(),
            f"v2 correction marker differs: {relative}",
        )
        bindings = marker.get("input_bindings")
        require(
            type(bindings) is list and len(bindings) == 7,
            f"v2 correction inputs differ: {relative}",
        )
        if expected_bindings is not None:
            require(bindings == expected_bindings, f"live correction inputs differ: {relative}")
        if shared is None:
            shared = bindings
        else:
            require(bindings == shared, f"correction input cohort differs: {relative}")
    changes = successors[DOC05_REL].get("changes")
    require(
        type(changes) is list
        and changes[-1].get("change_id") == CHANGE_ID
        and sum(row.get("change_id") == CHANGE_ID for row in changes) == 1,
        "DOC-05 correction is not the unique append-only tail",
    )
    doc01_marker = successors[DOC01_REL][MARKER_FIELD]
    expected_outputs = {
        path.as_posix(): {
            "sha256": bytes_sha256(successor_raw[path]),
            "byte_length": len(successor_raw[path]),
        }
        for path in OUTPUT_PATHS
        if path != DOC01_REL
    }
    require(
        doc01_marker.get("bound_corrected_outputs") == expected_outputs
        and doc01_marker.get("self_physical_sha256_excluded") is True,
        "DOC-01 corrected physical output bindings differ",
    )


def _parse_six(raw: Mapping[Path, bytes]) -> dict[Path, dict[str, Any]]:
    return {
        path: trace.strict_json_bytes(raw[path], path.as_posix())
        for path in OUTPUT_PATHS
    }


def _live_values(raw: Mapping[Path, bytes]) -> tuple[dict[str, Any], ...]:
    return (
        trace.strict_json_bytes(
            raw[trace.V2_IMPLEMENTATION_REL], trace.V2_IMPLEMENTATION_REL.as_posix()
        ),
        trace.strict_json_bytes(
            raw[trace.V2_VERIFICATION_REL], trace.V2_VERIFICATION_REL.as_posix()
        ),
        trace.strict_json_bytes(raw[R027_GAP_REL], R027_GAP_REL.as_posix()),
        trace.strict_json_bytes(
            raw[R001_REJECTED_REVIEW_REL], R001_REJECTED_REVIEW_REL.as_posix()
        ),
    )


def _expected_bindings_from_live(raw: Mapping[Path, bytes]) -> list[dict[str, Any]]:
    implementation, verification, gap, review = _live_values(raw)
    validate_inputs(
        implementation,
        verification,
        gap,
        review,
        implementation_raw=raw[trace.V2_IMPLEMENTATION_REL],
        verification_raw=raw[trace.V2_VERIFICATION_REL],
        gap_raw=raw[R027_GAP_REL],
        review_raw=raw[R001_REJECTED_REVIEW_REL],
    )
    return _input_bindings(
        implementation,
        verification,
        gap,
        review,
        implementation_raw=raw[trace.V2_IMPLEMENTATION_REL],
        verification_raw=raw[trace.V2_VERIFICATION_REL],
        gap_raw=raw[R027_GAP_REL],
        review_raw=raw[R001_REJECTED_REVIEW_REL],
        r027_raw_by_path={path: raw[path] for path in R027_OUTPUT_PATHS},
    )


def _deep_validate_live_inputs(root: Path, raw: Mapping[Path, bytes]) -> None:
    require(set(raw) == set(LIVE_INPUT_PATHS), "v2 live input inventory differs")
    observation_raw = trace.read_bytes(root, trace.V2_OBSERVATION_MANIFEST_REL)
    observation = trace.strict_json_bytes(
        observation_raw, trace.V2_OBSERVATION_MANIFEST_REL.as_posix()
    )
    rebuilt_v2_outputs = trace.build_results_outputs(
        observation,
        observation_raw,
        root=root,
    )
    implementation, verification, gap, review = _live_values(raw)
    validate_inputs(
        implementation,
        verification,
        gap,
        review,
        implementation_raw=raw[trace.V2_IMPLEMENTATION_REL],
        verification_raw=raw[trace.V2_VERIFICATION_REL],
        gap_raw=raw[R027_GAP_REL],
        review_raw=raw[R001_REJECTED_REVIEW_REL],
        rebuilt_v2_outputs=rebuilt_v2_outputs,
    )
    rebuilt_r027 = r027.build_outputs(root)
    require(
        set(rebuilt_r027) == set(R027_OUTPUT_PATHS),
        "rebuilt R027 four-output inventory differs",
    )
    for path in R027_OUTPUT_PATHS:
        require(
            rebuilt_r027[path].encode("utf-8") == raw[path],
            f"stored R027 output does not reproduce from exact R026, R001 rejection, and v2 evidence: {path}",
        )


def _build_from_raw(
    predecessor_raw: Mapping[Path, bytes], live_raw: Mapping[Path, bytes]
) -> dict[Path, str]:
    require(set(live_raw) == set(LIVE_INPUT_PATHS), "v2 live input inventory differs")
    implementation, verification, gap, review = _live_values(live_raw)
    return build_documents(
        _parse_six(predecessor_raw),
        predecessor_raw,
        implementation,
        verification,
        gap,
        review,
        implementation_raw=live_raw[trace.V2_IMPLEMENTATION_REL],
        verification_raw=live_raw[trace.V2_VERIFICATION_REL],
        gap_raw=live_raw[R027_GAP_REL],
        review_raw=live_raw[R001_REJECTED_REVIEW_REL],
        generator_sha256=bytes_sha256(live_raw[BUILDER_REL]),
        r027_raw_by_path={path: live_raw[path] for path in R027_OUTPUT_PATHS},
    )


def _fixed_v1_predecessors(
    current_raw: Mapping[Path, bytes],
) -> tuple[dict[Path, bytes], bool]:
    require(
        set(current_raw) == set(OUTPUT_PATHS),
        "six-artifact current byte inventory differs",
    )
    current = _parse_six(current_raw)
    has_v2_marker = {
        path: MARKER_FIELD in current[path] for path in OUTPUT_PATHS
    }
    require(
        len(set(has_v2_marker.values())) == 1,
        "six-artifact v2 marker stage is partial",
    )
    if not next(iter(has_v2_marker.values())):
        validate_v1_predecessors(current, current_raw)
        return dict(current_raw), False

    predecessors = {path: deepcopy(current[path]) for path in OUTPUT_PATHS}
    for path in OUTPUT_PATHS:
        marker = predecessors[path].pop(MARKER_FIELD, None)
        require(type(marker) is dict, f"v2 correction marker missing: {path}")
    changes = predecessors[DOC05_REL].get("changes")
    require(
        type(changes) is list
        and bool(changes)
        and all(type(row) is dict for row in changes)
        and changes[-1].get("change_id") == CHANGE_ID
        and sum(row.get("change_id") == CHANGE_ID for row in changes) == 1,
        "DOC-05 correction cannot be reversed to the fixed v1 predecessor",
    )
    changes.pop()
    summary = predecessors[DOC05_REL].get("summary")
    if type(summary) is dict:
        require(bool(changes), "DOC-05 fixed v1 change history is empty")
        summary["change_count"] = len(changes)
        summary["last_change_id"] = changes[-1]["change_id"]
    for path in OUTPUT_PATHS:
        v1._projection_seal(predecessors[path], SEAL_FIELD_BY_PATH[path])
    predecessor_raw = {
        path: json_text(predecessors[path]).encode("utf-8") for path in OUTPUT_PATHS
    }
    validate_v1_predecessors(predecessors, predecessor_raw)
    return predecessor_raw, True


def project_r001_reviewed_v1_artifacts(root: Path = ROOT) -> dict[Path, bytes]:
    """Return historical R001 bytes after the exact v2 successor is published."""
    current_raw = {path: trace.read_bytes(root, path) for path in OUTPUT_PATHS}
    predecessor_raw, is_successor = _fixed_v1_predecessors(current_raw)
    if not is_successor:
        return {}
    live_raw = {path: trace.read_bytes(root, path) for path in LIVE_INPUT_PATHS}
    rebuilt = _build_from_raw(predecessor_raw, live_raw)
    require(
        all(current_raw[path] == rebuilt[path].encode("utf-8") for path in OUTPUT_PATHS),
        "historical R001 projection is not an exact v2 successor",
    )
    require(
        {path: trace.read_bytes(root, path) for path in OUTPUT_PATHS} == current_raw
        and {path: trace.read_bytes(root, path) for path in LIVE_INPUT_PATHS} == live_raw,
        "historical R001 projection inputs changed during validation",
    )
    return predecessor_raw


def _rebuild_exact_v2(
    current_raw: Mapping[Path, bytes],
    live_raw: Mapping[Path, bytes],
    *,
    require_successor: bool = False,
) -> tuple[dict[Path, str], dict[Path, bytes], bool]:
    predecessor_raw, is_successor = _fixed_v1_predecessors(current_raw)
    outputs = _build_from_raw(predecessor_raw, live_raw)
    if require_successor:
        require(is_successor, "six-artifact v2 correction marker is missing")
    if is_successor:
        for path in OUTPUT_PATHS:
            require(
                current_raw[path] == outputs[path].encode("utf-8"),
                f"stored v2 correction differs from fixed-v1 exact rebuild: {path}",
            )
    return outputs, predecessor_raw, is_successor


def _read_live_at(root_descriptor: int) -> dict[Path, bytes]:
    return {
        path: v1.fp008_builder._read_relative_at(root_descriptor, path)
        for path in LIVE_INPUT_PATHS
    }


def _transaction_member(relative: Path, kind: str) -> Path:
    require(kind in {"stage", "backup"}, "transaction member kind differs")
    return relative.parent / f".{relative.name}.walksafe-npc-v2-{kind}-20260813"


def _transaction_manifest(
    source_raw: Mapping[Path, bytes],
    outputs: Mapping[Path, str],
    live_raw: Mapping[Path, bytes],
) -> tuple[dict[str, Any], bytes]:
    validate_v1_predecessors(_parse_six(source_raw), source_raw)
    rebuilt = _build_from_raw(source_raw, live_raw)
    require(
        dict(outputs) == rebuilt,
        "transaction successor differs from fixed-v1 exact rebuild",
    )
    successor_raw = {path: outputs[path].encode("utf-8") for path in OUTPUT_PATHS}
    validate_correction_documents(_parse_six(successor_raw), successor_raw)
    value = trace._seal(
        {
            "schema_version": "walksafe.npc-recovery-six-artifact-correction-transaction.v1",
            "transaction_token": TRANSACTION_TOKEN,
            "successor_id": SUCCESSOR_ID,
            "outputs": [
                {
                    "path": path.as_posix(),
                    "source_sha256": bytes_sha256(source_raw[path]),
                    "source_byte_length": len(source_raw[path]),
                    "successor_sha256": bytes_sha256(successor_raw[path]),
                    "successor_byte_length": len(successor_raw[path]),
                    "stage_path": _transaction_member(path, "stage").as_posix(),
                    "backup_path": _transaction_member(path, "backup").as_posix(),
                }
                for path in OUTPUT_PATHS
            ],
        },
        "transaction_content_sha256",
    )
    return value, json_text(value).encode("utf-8")


def _transaction_rows(value: Mapping[str, Any], raw: bytes) -> dict[Path, dict[str, Any]]:
    require(raw == json_text(value).encode("utf-8"), "transaction journal is noncanonical")
    trace.verify_seal(value, "transaction_content_sha256", "v2 correction transaction")
    require(
        value.get("transaction_token") == TRANSACTION_TOKEN
        and value.get("successor_id") == SUCCESSOR_ID,
        "transaction identity differs",
    )
    rows = value.get("outputs")
    require(type(rows) is list and len(rows) == len(OUTPUT_PATHS), "transaction output count differs")
    result: dict[Path, dict[str, Any]] = {}
    for path, row in zip(OUTPUT_PATHS, rows, strict=True):
        require(
            type(row) is dict
            and row.get("path") == path.as_posix()
            and row.get("stage_path") == _transaction_member(path, "stage").as_posix()
            and row.get("backup_path") == _transaction_member(path, "backup").as_posix()
            and all(
                type(row.get(key)) is str and trace.SHA256_RE.fullmatch(row[key])
                for key in ("source_sha256", "successor_sha256")
            )
            and all(
                type(row.get(key)) is int and row[key] > 0
                for key in ("source_byte_length", "successor_byte_length")
            ),
            f"transaction row differs: {path}",
        )
        result[path] = dict(row)
    return result


def _matches(raw: bytes, row: Mapping[str, Any], kind: str) -> bool:
    return bytes_sha256(raw) == row[f"{kind}_sha256"] and len(raw) == row[f"{kind}_byte_length"]


def _cleanup(
    root_descriptor: int,
    held: Mapping[Path, tuple[int, Sequence[tuple[int, str, tuple[int, int]]]]],
    rows: Mapping[Path, Mapping[str, Any]],
    journal_raw: bytes,
) -> None:
    for path in OUTPUT_PATHS:
        for kind in ("stage", "backup"):
            member = Path(rows[path][f"{kind}_path"])
            v1.fp008_builder._unlink_entry_at(
                held[path][0], member.name, None, member.as_posix()
            )
    v1.fp008_builder._unlink_entry_at(
        root_descriptor,
        TRANSACTION_JOURNAL_REL.name,
        bytes_sha256(journal_raw),
        TRANSACTION_JOURNAL_REL.as_posix(),
    )


def _recover_locked(
    root: Path,
    root_descriptor: int,
    held: Mapping[Path, tuple[int, Sequence[tuple[int, str, tuple[int, int]]]]],
    *,
    force_rollback: bool = False,
) -> str:
    v1._validate_namespace(root, root_descriptor, held)
    entry = v1.fp008_builder._try_read_regular_at(
        root_descriptor, TRANSACTION_JOURNAL_REL.name, TRANSACTION_JOURNAL_REL.as_posix()
    )
    if entry is None:
        return "NONE"
    journal_raw, _ = entry
    journal = trace.strict_json_bytes(journal_raw, TRANSACTION_JOURNAL_REL.as_posix())
    rows = _transaction_rows(journal, journal_raw)
    current_raw, _ = v1._read_held_outputs(held)
    source = {path: _matches(current_raw[path], rows[path], "source") for path in OUTPUT_PATHS}
    successor = {path: _matches(current_raw[path], rows[path], "successor") for path in OUTPUT_PATHS}
    if all(successor.values()) and not force_rollback:
        live_raw = _read_live_at(root_descriptor)
        _deep_validate_live_inputs(root, live_raw)
        outputs, predecessor_raw, _ = _rebuild_exact_v2(
            current_raw, live_raw, require_successor=True
        )
        expected_raw = {
            path: outputs[path].encode("utf-8") for path in OUTPUT_PATHS
        }
        require(
            all(_matches(predecessor_raw[path], rows[path], "source") for path in OUTPUT_PATHS)
            and all(_matches(expected_raw[path], rows[path], "successor") for path in OUTPUT_PATHS),
            "transaction journal differs from the fixed-v1 exact rebuild",
        )
        require(
            v1._read_held_outputs(held)[0] == current_raw,
            "correction changed during recovery rebuild",
        )
        require(
            _read_live_at(root_descriptor) == live_raw,
            "live inputs changed during recovery rebuild",
        )
        _cleanup(root_descriptor, held, rows, journal_raw)
        return "COMPLETED_SUCCESSOR"
    for path in OUTPUT_PATHS:
        if not successor[path]:
            continue
        parent = held[path][0]
        backup = Path(rows[path]["backup_path"])
        stage = Path(rows[path]["stage_path"])
        backup_entry = v1.fp008_builder._try_read_regular_at(parent, backup.name, backup.as_posix())
        require(backup_entry is not None and _matches(backup_entry[0], rows[path], "source"), f"source backup differs: {path}")
        target_raw, target_info = v1.fp008_builder._read_regular_at(parent, path.name, path.as_posix())
        require(_matches(target_raw, rows[path], "successor"), f"transaction rollback retained after CAS: {path}")
        stage_entry = v1.fp008_builder._try_read_regular_at(parent, stage.name, stage.as_posix())
        if stage_entry is None:
            v1.fp008_builder._write_private_stage_at(
                parent, stage.name, backup_entry[0], stat.S_IMODE(backup_entry[1].st_mode)
            )
            stage_entry = v1.fp008_builder._read_regular_at(parent, stage.name, stage.as_posix())
        require(_matches(stage_entry[0], rows[path], "source"), f"rollback stage differs: {path}")
        v1._exchange_cas(
            parent,
            stage.name,
            path.name,
            expected_stage_raw=stage_entry[0],
            expected_stage_info=stage_entry[1],
            expected_target_raw=target_raw,
            expected_target_info=target_info,
            label=f"v2 rollback {path}",
        )
    current_raw, _ = v1._read_held_outputs(held)
    require(
        all(_matches(current_raw[path], rows[path], "source") for path in OUTPUT_PATHS),
        "transaction rollback retained after CAS/foreign update",
    )
    validate_v1_predecessors(_parse_six(current_raw), current_raw)
    _cleanup(root_descriptor, held, rows, journal_raw)
    return "ROLLED_BACK"


def _publish_locked(
    root: Path,
    root_descriptor: int,
    held: Mapping[Path, tuple[int, Sequence[tuple[int, str, tuple[int, int]]]]],
    source_raw: Mapping[Path, bytes],
    outputs: Mapping[Path, str],
    live_raw: Mapping[Path, bytes],
) -> None:
    observed_raw, observed_info = v1._read_held_outputs(held)
    require(observed_raw == dict(source_raw), "publication source bytes differ")
    identities = {
        path: v1.fp008_builder._authority_identity(observed_info[path])
        for path in OUTPUT_PATHS
    }
    manifest, journal_raw = _transaction_manifest(source_raw, outputs, live_raw)
    rows = _transaction_rows(manifest, journal_raw)
    try:
        v1.fp008_builder._write_private_journal_at(
            root_descriptor, TRANSACTION_JOURNAL_REL.name, journal_raw
        )
        for path in OUTPUT_PATHS:
            parent = held[path][0]
            mode = stat.S_IMODE(observed_info[path].st_mode)
            v1.fp008_builder._write_private_stage_at(
                parent, Path(rows[path]["backup_path"]).name, source_raw[path], mode
            )
            v1.fp008_builder._write_private_stage_at(
                parent,
                Path(rows[path]["stage_path"]).name,
                outputs[path].encode("utf-8"),
                mode,
            )
        require(_read_live_at(root_descriptor) == dict(live_raw), "live inputs changed before commit")
        for path in OUTPUT_PATHS:
            parent, ancestry = held[path]
            v1.fp008_builder._validate_held_ancestry(ancestry, path)
            target_raw, target_info = v1.fp008_builder._read_regular_at(parent, path.name, path.as_posix())
            require(
                target_raw == source_raw[path]
                and v1.fp008_builder._authority_identity(target_info) == identities[path],
                f"source CAS changed before exchange: {path}",
            )
            stage = Path(rows[path]["stage_path"])
            stage_raw, stage_info = v1.fp008_builder._read_regular_at(parent, stage.name, stage.as_posix())
            require(_matches(stage_raw, rows[path], "successor"), f"successor stage differs: {path}")
            v1._exchange_cas(
                parent,
                stage.name,
                path.name,
                expected_stage_raw=stage_raw,
                expected_stage_info=stage_info,
                expected_target_raw=target_raw,
                expected_target_info=target_info,
                label=f"v2 publication {path}",
            )
        require(_read_live_at(root_descriptor) == dict(live_raw), "live inputs changed during commit")
        require(
            _recover_locked(root, root_descriptor, held) == "COMPLETED_SUCCESSOR",
            "completed transaction cleanup differs",
        )
    except BaseException as publication_error:
        try:
            recovery = _recover_locked(root, root_descriptor, held, force_rollback=True)
            require(recovery in {"NONE", "ROLLED_BACK"}, "publication rollback differs")
        except BaseException as rollback_error:
            if hasattr(rollback_error, "add_note"):
                rollback_error.add_note(f"original publication failure: {publication_error!r}")
            raise rollback_error from publication_error
        raise


def _locked_root(root: Path) -> tuple[Path, int, dict[Path, Any], list[int]]:
    absolute = Path(os.path.abspath(os.fspath(root)))
    root_descriptor = v1.fp008_builder._open_locked_root(absolute)
    try:
        held, descriptors = v1._open_held_outputs(root_descriptor)
    except BaseException:
        fcntl.flock(root_descriptor, fcntl.LOCK_UN)
        os.close(root_descriptor)
        raise
    return absolute, root_descriptor, held, descriptors


def _translate_base_error(exc: BaseException) -> BuildError:
    return BuildError(str(exc))


def build_outputs(root: Path = ROOT) -> dict[Path, str]:
    try:
        absolute, root_descriptor, held, descriptors = _locked_root(root)
    except v1.fp008_builder.BuildError as exc:
        raise _translate_base_error(exc) from exc
    try:
        require(
            v1.fp008_builder._try_read_regular_at(
                root_descriptor,
                TRANSACTION_JOURNAL_REL.name,
                TRANSACTION_JOURNAL_REL.as_posix(),
            )
            is None,
            "pending transaction requires --write recovery",
        )
        raw, _ = v1._read_held_outputs(held)
        live_raw = _read_live_at(root_descriptor)
        _deep_validate_live_inputs(absolute, live_raw)
        outputs, _, _ = _rebuild_exact_v2(raw, live_raw)
        require(v1._read_held_outputs(held)[0] == raw, "artifacts changed during build")
        require(_read_live_at(root_descriptor) == live_raw, "live inputs changed during build")
        return outputs
    except v1.fp008_builder.BuildError as exc:
        raise _translate_base_error(exc) from exc
    finally:
        v1._close_locked_context(root_descriptor, descriptors)


def check_successor(root: Path = ROOT) -> dict[Path, str]:
    try:
        absolute, root_descriptor, held, descriptors = _locked_root(root)
    except v1.fp008_builder.BuildError as exc:
        raise _translate_base_error(exc) from exc
    try:
        raw, info = v1._read_held_outputs(held)
        live_raw = _read_live_at(root_descriptor)
        _deep_validate_live_inputs(absolute, live_raw)
        outputs, _, _ = _rebuild_exact_v2(
            raw, live_raw, require_successor=True
        )
        final_raw, final_info = v1._read_held_outputs(held)
        require(
            final_raw == raw
            and all(
                v1.fp008_builder._authority_identity(final_info[path])
                == v1.fp008_builder._authority_identity(info[path])
                for path in OUTPUT_PATHS
            ),
            "correction changed during check",
        )
        require(_read_live_at(root_descriptor) == live_raw, "live inputs changed during check")
        return outputs
    except v1.fp008_builder.BuildError as exc:
        raise _translate_base_error(exc) from exc
    finally:
        v1._close_locked_context(root_descriptor, descriptors)


def write_successor(root: Path = ROOT) -> None:
    try:
        absolute, root_descriptor, held, descriptors = _locked_root(root)
    except v1.fp008_builder.BuildError as exc:
        raise _translate_base_error(exc) from exc
    try:
        _recover_locked(absolute, root_descriptor, held)
        raw, _ = v1._read_held_outputs(held)
        live_raw = _read_live_at(root_descriptor)
        _deep_validate_live_inputs(absolute, live_raw)
        outputs, _, is_successor = _rebuild_exact_v2(raw, live_raw)
        if is_successor:
            return
        require(_read_live_at(root_descriptor) == live_raw, "live inputs changed before publication")
        _publish_locked(absolute, root_descriptor, held, raw, outputs, live_raw)
    except v1.fp008_builder.BuildError as exc:
        raise _translate_base_error(exc) from exc
    finally:
        v1._close_locked_context(root_descriptor, descriptors)


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
        if args.write:
            write_successor(args.root)
        else:
            check_successor(args.root)
    except (BuildError, OSError, ValueError, TypeError) as exc:
        print(f"NPC single-admin recovery six-artifact v2 correction: FAIL: {exc}")
        return 1
    print("NPC single-admin recovery six-artifact v2 correction: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

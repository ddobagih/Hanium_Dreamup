#!/usr/bin/env python3
"""Validate a separately assigned, reviewer-authored NPC recovery review.

This module never chooses a reviewer, review decision, or finding count.  An
assigner writes ``review-assignment.json`` and the assigned reviewer writes
``review-result.json``.  The CLI validates those immutable inputs and may only
derive the normalized internal-review and completion documents from an
approved, finding-free result.
"""

from __future__ import annotations

import argparse
from copy import deepcopy
from dataclasses import dataclass
import fcntl
import os
from pathlib import Path
import re
import stat
from typing import Any, Mapping, Sequence

from scripts import build_walksafe_npc_single_admin_recovery_trace_20260812 as trace
from scripts import build_walksafe_npc_single_admin_recovery_gap_backlog_r027_20260813 as gap_builder
from scripts import build_walksafe_npc_single_admin_recovery_artifact_trace_correction_v2_20260813 as artifact_builder
from scripts import build_walksafe_phase1_exact257_successor_r016_20260813 as r016_builder


ROOT = Path(__file__).resolve().parents[1]
R001_ROUND_ID = "WS-NPC-SINGLE-ADMIN-RECOVERY-REVIEW-20260813-R001"
R002_ROUND_ID = "WS-NPC-SINGLE-ADMIN-RECOVERY-REVIEW-20260813-R002"
R003_ROUND_ID = "WS-NPC-SINGLE-ADMIN-RECOVERY-REVIEW-20260813-R003"
R001_REVIEW_RESULT_REL = (
    trace.RESULT_DIR_REL / "review-rounds" / "R001" / "review-result.json"
)
R002_REVIEW_DIR_REL = trace.RESULT_DIR_REL / "review-rounds" / "R002"
R002_REVIEW_ASSIGNMENT_REL = R002_REVIEW_DIR_REL / "review-assignment.json"
R002_REVIEW_RESULT_REL = R002_REVIEW_DIR_REL / "review-result.json"
R002_INDEPENDENT_REVIEW_REL = R002_REVIEW_DIR_REL / "independent-review.json"
R002_COMPLETION_RECEIPT_REL = (
    trace.CORRECTION_DIR_REL / "completion-receipt-v2.json"
)
R003_REVIEW_DIR_REL = trace.RESULT_DIR_REL / "review-rounds" / "R003"
REVIEW_ASSIGNMENT_REL = R003_REVIEW_DIR_REL / "review-assignment.json"
REVIEW_RESULT_REL = R003_REVIEW_DIR_REL / "review-result.json"
INDEPENDENT_REVIEW_REL = R003_REVIEW_DIR_REL / "independent-review.json"
COMPLETION_RECEIPT_REL = trace.CORRECTION_DIR_REL / "completion-receipt-v3.json"
POST_REVIEW_OUTPUT_PATHS = (INDEPENDENT_REVIEW_REL, COMPLETION_RECEIPT_REL)
R002_REVIEW_HISTORY_SHA256_BY_PATH = {
    R002_REVIEW_ASSIGNMENT_REL: ("7c12d9de14ac81a053f8431193ff04f6f58a8cb7098c451f519c643173b28334", 44_686),
    R002_REVIEW_RESULT_REL: ("ff461e1432694cd2c3e12bbaafb40cfdc30b9ab29e99649cc09ab0cdc71306e9", 59_558),
    R002_INDEPENDENT_REVIEW_REL: ("8d09bc4feb0a3ab71925b4c395329f6e2d4717e623fb26fa4a8736788ca34aca", 59_845),
    R002_COMPLETION_RECEIPT_REL: ("4ddeb3b025146a77e7a2dcb8ae382f7079eb8e8728252f1a65dbe86ef0e775a3", 52_796),
}
R002_REVIEW_HISTORY_PATHS = tuple(R002_REVIEW_HISTORY_SHA256_BY_PATH)
IMMUTABLE_PREDECESSOR_HISTORY_PATHS = (
    *gap_builder.R026_INPUT_PATHS,
    *r016_builder.PREDECESSOR_PATHS,
    *gap_builder.R001_HISTORY_PATHS,
)
REVIEW_SOURCE_KIND = "SEPARATE_INTERNAL_AGENT_REVIEW"
ALLOWED_DECISIONS = {"APPROVED", "CHANGES_REQUESTED", "REJECTED"}
ROUND_ID_RE = re.compile(r"^[A-Z0-9][A-Z0-9._-]{7,127}$")
AGENT_INSTANCE_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$")
TIME_FIELDS = {
    "assigned_at",
    "created_at",
    "ended_at",
    "generated_at",
    "observed_at",
    "occurred_at",
    "prepared_at",
    "produced_at",
    "recorded_at",
    "reviewed_at",
    "started_at",
    "updated_at",
    "verified_at",
}
CONTROL_CODE_PATHS = (
    Path("scripts/build_walksafe_npc_single_admin_recovery_trace_20260812.py"),
    Path("tests/test_build_walksafe_npc_single_admin_recovery_trace_20260812.py"),
    Path("scripts/build_walksafe_npc_single_admin_recovery_gap_backlog_r026_20260812.py"),
    Path("tests/test_build_walksafe_npc_single_admin_recovery_gap_backlog_r026_20260812.py"),
    Path("scripts/build_walksafe_npc_single_admin_recovery_artifact_trace_successor_20260812.py"),
    Path("tests/test_build_walksafe_npc_single_admin_recovery_artifact_trace_successor_20260812.py"),
    Path("scripts/build_walksafe_phase1_exact257_successor_r015_20260812.py"),
    Path("tests/test_build_walksafe_phase1_exact257_successor_r015_20260812.py"),
    Path("scripts/build_walksafe_npc_single_admin_recovery_gap_backlog_r027_20260813.py"),
    Path("tests/test_build_walksafe_npc_single_admin_recovery_gap_backlog_r027_20260813.py"),
    Path("scripts/build_walksafe_npc_single_admin_recovery_artifact_trace_correction_v2_20260813.py"),
    Path("tests/test_build_walksafe_npc_single_admin_recovery_artifact_trace_correction_v2_20260813.py"),
    Path("scripts/build_walksafe_phase1_exact257_successor_r016_20260813.py"),
    Path("tests/test_build_walksafe_phase1_exact257_successor_r016_20260813.py"),
    Path("scripts/build_walksafe_npc_single_admin_recovery_strict_review_gate_20260812.py"),
    Path("tests/test_build_walksafe_npc_single_admin_recovery_strict_review_gate_20260812.py"),
    Path("scripts/run_walksafe_npc_single_admin_recovery_verification_20260813.py"),
    Path("tests/test_run_walksafe_npc_single_admin_recovery_verification_20260813.py"),
    Path("scripts/check_walksafe_goal_graph_v2_4.py"),
    Path("tests/test_walksafe_goal_graph_v2_4.py"),
    Path("scripts/check_walksafe_project_continuation_v2_4.py"),
    Path("tests/test_walksafe_project_continuation_v2_4.py"),
    Path("scripts/generate_repository_catalogs.py"),
    Path("tests/test_repository_catalogs.py"),
    Path("scripts/run_walksafe_test_layers_current.sh"),
    Path("scripts/apply_walksafe_npc_single_admin_recovery_goal_completed_seq61_62_20260812.py"),
    Path("tests/test_apply_walksafe_npc_single_admin_recovery_goal_completed_seq61_62_20260812.py"),
)
ADDITIONAL_V2_AUDIT_FINDINGS = (
    {
        "finding_id": "NPC-V2-AUDIT-P1-001",
        "severity": "P1",
        "title": "fake executor or publisher acceptance",
    },
    {
        "finding_id": "NPC-V2-AUDIT-P1-002",
        "severity": "P1",
        "title": "incomplete lane runtime input closure",
    },
    {
        "finding_id": "NPC-V2-AUDIT-P1-003",
        "severity": "P1",
        "title": "incomplete toolchain or ambient input closure",
    },
    {
        "finding_id": "NPC-V2-AUDIT-P1-004",
        "severity": "P1",
        "title": "namespace hardlink or forged journal acceptance",
    },
    {
        "finding_id": "NPC-V2-AUDIT-P1-005",
        "severity": "P1",
        "title": "prepublication time-of-check time-of-use race",
    },
    {
        "finding_id": "NPC-V2-AUDIT-P1-006",
        "severity": "P1",
        "title": "forged successor producer acceptance",
    },
    {
        "finding_id": "NPC-V2-AUDIT-P2-001",
        "severity": "P2",
        "title": "fixed v1 lineage acceptance",
    },
    {
        "finding_id": "NPC-V2-AUDIT-P2-002",
        "severity": "P2",
        "title": "partial-stage retry wedge",
    },
    {
        "finding_id": "NPC-V2-AUDIT-P2-003",
        "severity": "P2",
        "title": "owner or read time-of-check time-of-use race",
    },
    {
        "finding_id": "NPC-R003-AUDIT-P1-001",
        "severity": "P1",
        "title": "immutable predecessor history omitted from the final managed closure",
    },
)


def control_code_paths() -> tuple[Path, ...]:
    """Return the exact completion authority cohort without duplicating it."""
    try:
        from scripts import apply_walksafe_npc_single_admin_recovery_goal_completed_seq61_62_20260812 as completion_apply
    except (AttributeError, ImportError):
        return CONTROL_CODE_PATHS
    paths = tuple(completion_apply.SEQUENCE_AUTHORITY_PATHS)
    require(
        paths == CONTROL_CODE_PATHS,
        "review/control completion authority inventory differs",
    )
    return paths

BuildError = trace.BuildError
require = trace.require
bytes_sha256 = trace.bytes_sha256
json_text = trace.json_text


def _read_review_bytes(root: Path, relative: Path) -> bytes:
    require(
        not relative.is_absolute()
        and bool(relative.parts)
        and all(part not in {"", ".", ".."} for part in relative.parts),
        f"review evidence path differs: {relative}",
    )
    directory_flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_DIRECTORY", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    file_flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0)
        | getattr(os, "O_NONBLOCK", 0)
    )
    require(
        getattr(os, "O_DIRECTORY", 0)
        and getattr(os, "O_NOFOLLOW", 0)
        and getattr(os, "O_NONBLOCK", 0),
        "descriptor-anchored review reads are unsupported",
    )
    root = root.resolve(strict=True)
    root_before = root.lstat()
    descriptors: list[int] = []
    ancestry: list[tuple[int, str, tuple[int, int]]] = []
    try:
        current = os.open(root, directory_flags)
        descriptors.append(current)
        require(
            (root_before.st_dev, root_before.st_ino)
            == (os.fstat(current).st_dev, os.fstat(current).st_ino),
            "review evidence root changed while opening",
        )
        for component in relative.parent.parts:
            child = os.open(component, directory_flags, dir_fd=current)
            descriptors.append(child)
            opened = os.fstat(child)
            entry = os.stat(component, dir_fd=current, follow_symlinks=False)
            require(
                stat.S_ISDIR(opened.st_mode)
                and (opened.st_dev, opened.st_ino)
                == (entry.st_dev, entry.st_ino),
                f"review evidence parent authority differs: {relative}",
            )
            ancestry.append(
                (current, component, (opened.st_dev, opened.st_ino))
            )
            current = child
        descriptor = os.open(relative.name, file_flags, dir_fd=current)
        descriptors.append(descriptor)
        before = os.fstat(descriptor)
        authority = (
            before.st_dev,
            before.st_ino,
            before.st_mode,
            before.st_uid,
            before.st_gid,
            before.st_nlink,
            before.st_size,
            before.st_mtime_ns,
            before.st_ctime_ns,
        )
        require(
            stat.S_ISREG(before.st_mode)
            and before.st_uid == os.geteuid()
            and before.st_nlink == 1
            and not (before.st_mode & (stat.S_ISUID | stat.S_ISGID)),
            f"review evidence file authority differs: {relative}",
        )
        chunks: list[bytes] = []
        while chunk := os.read(descriptor, 1024 * 1024):
            chunks.append(chunk)
        after = os.fstat(descriptor)
        entry = os.stat(relative.name, dir_fd=current, follow_symlinks=False)
        require(
            authority
            == (
                after.st_dev,
                after.st_ino,
                after.st_mode,
                after.st_uid,
                after.st_gid,
                after.st_nlink,
                after.st_size,
                after.st_mtime_ns,
                after.st_ctime_ns,
            )
            and (entry.st_dev, entry.st_ino) == (after.st_dev, after.st_ino),
            f"review evidence file changed while reading: {relative}",
        )
        for parent, component, expected_identity in reversed(ancestry):
            entry = os.stat(component, dir_fd=parent, follow_symlinks=False)
            require(
                stat.S_ISDIR(entry.st_mode)
                and (entry.st_dev, entry.st_ino) == expected_identity,
                f"review evidence parent changed while reading: {relative}",
            )
        root_after = root.lstat()
        require(
            (root_after.st_dev, root_after.st_ino)
            == (root_before.st_dev, root_before.st_ino),
            "review evidence root changed while reading",
        )
        return b"".join(chunks)
    except OSError as exc:
        raise BuildError(f"cannot safely read review evidence {relative}: {exc}") from exc
    finally:
        for descriptor in reversed(descriptors):
            os.close(descriptor)


@dataclass(frozen=True)
class ReviewContext:
    result_raw: Mapping[Path, bytes]
    consumer_bindings: tuple[dict[str, Any], ...]
    evidence_manifest: tuple[dict[str, Any], ...]
    review_subject_sha256: str
    reviewed_result_sha256_by_kind: Mapping[str, str]
    implementation_started_at: str
    implementation_ended_at: str
    chronology: tuple[dict[str, str], ...]
    latest_reviewable_at: str
    control_code_cohort: tuple[dict[str, Any], ...]
    control_code_cohort_sha256: str
    prior_control_code_cohort_sha256: str
    control_code_changes_from_r002: tuple[dict[str, Any], ...]
    predecessor_review: Mapping[str, Any]
    prior_approved_review: Mapping[str, Any]


def _strict_equal(actual: Any, expected: Any, label: str) -> None:
    require(type(actual) is type(expected), f"{label} type differs")
    if isinstance(expected, dict):
        require(set(actual) == set(expected), f"{label} fields differ")
        for key in expected:
            _strict_equal(actual[key], expected[key], f"{label}.{key}")
    elif isinstance(expected, list):
        require(len(actual) == len(expected), f"{label} length differs")
        for index, (left, right) in enumerate(zip(actual, expected, strict=True)):
            _strict_equal(left, right, f"{label}[{index}]")
    else:
        require(actual == expected, f"{label} value differs")


def _review_boundary() -> dict[str, Any]:
    return {
        **trace.completion_boundary(),
        "review_scope": "INTERNAL_ONLY",
        "separate_internal_review_required": True,
        "external_independence_claimed": False,
    }


def _result_paths() -> tuple[Path, ...]:
    return (
        *(lane.receipt_rel for lane in trace.LANES),
        trace.V2_IMPLEMENTATION_REL,
        trace.V2_VERIFICATION_REL,
        trace.V2_SUCCESSOR_REL,
        trace.V2_REVIEW_SUBJECT_REL,
    )


def _identity(value: Any, label: str, expected_role: str) -> dict[str, str]:
    require(type(value) is dict, f"{label} identity missing")
    require(
        set(value) == {"agent_instance_id", "canonical_task", "role"},
        f"{label} identity fields differ",
    )
    instance = value.get("agent_instance_id")
    task = value.get("canonical_task")
    require(
        type(instance) is str
        and AGENT_INSTANCE_ID_RE.fullmatch(instance) is not None
        and instance.lower() not in {"unknown", "placeholder", "reviewer", "executor"},
        f"{label} actual agent instance id differs",
    )
    require(
        type(task) is str and task.startswith("/root") and " " not in task,
        f"{label} canonical task differs",
    )
    require(value.get("role") == expected_role, f"{label} role differs")
    return dict(value)


def _separate_reviewer(
    executor: Mapping[str, str], reviewer: Mapping[str, str]
) -> None:
    require(
        executor["agent_instance_id"] != reviewer["agent_instance_id"]
        and executor["canonical_task"] != reviewer["canonical_task"],
        "executor cannot review own work",
    )


def _collect_chronology(
    rows: list[dict[str, str]], path: Path, value: Any, location: str = ""
) -> None:
    if type(value) is dict:
        for key, child in value.items():
            pointer = f"{location}/{key}"
            if key in TIME_FIELDS and type(child) is str and "T" in child:
                trace._parse_time(child, f"chronology {path}{pointer}")
                rows.append(
                    {"path": path.as_posix(), "json_pointer": pointer, "timestamp": child}
                )
            _collect_chronology(rows, path, child, pointer)
    elif type(value) is list:
        for index, child in enumerate(value):
            _collect_chronology(rows, path, child, f"{location}/{index}")


def _control_code_cohort(root: Path) -> tuple[tuple[dict[str, Any], ...], str]:
    rows: list[dict[str, Any]] = []
    paths = control_code_paths()
    for relative in paths:
        raw = _read_review_bytes(root, relative)
        rows.append(
            {
                "path": relative.as_posix(),
                "sha256": bytes_sha256(raw),
                "byte_length": len(raw),
            }
        )
    require(len(rows) == len(set(paths)), "control-code inventory differs")
    cohort = tuple(rows)
    return cohort, trace.object_sha256(list(cohort))


def _review_scope(context: ReviewContext) -> dict[str, Any]:
    return {
        "completion_authority": {
            "completion_only_successor": True,
            "product_execution_authority_round_id": R002_ROUND_ID,
            "product_execution_rerun_claimed": False,
        },
        "review_subject": {
            "path": trace.V2_REVIEW_SUBJECT_REL.as_posix(),
            "sha256": context.review_subject_sha256,
        },
        "reviewed_result_sha256_by_kind": deepcopy(
            dict(context.reviewed_result_sha256_by_kind)
        ),
        "reviewed_consumer_bindings": deepcopy(list(context.consumer_bindings)),
        "reviewed_evidence_manifest": deepcopy(list(context.evidence_manifest)),
        "reviewed_evidence_manifest_sha256": trace.object_sha256(
            list(context.evidence_manifest)
        ),
        "reviewed_chronology": deepcopy(list(context.chronology)),
        "latest_reviewable_at": context.latest_reviewable_at,
        "reviewed_control_code_cohort": deepcopy(list(context.control_code_cohort)),
        "reviewed_control_code_cohort_sha256": context.control_code_cohort_sha256,
        "prior_control_code_cohort": {
            "round_id": R002_ROUND_ID,
            "sha256": context.prior_control_code_cohort_sha256,
        },
        "control_code_changes_from_r002": deepcopy(
            list(context.control_code_changes_from_r002)
        ),
    }


def _load_predecessor_review(root: Path) -> dict[str, Any]:
    value, raw = gap_builder.load_validated_r001_review(root)
    require(
        R001_REVIEW_RESULT_REL == gap_builder.R001_REJECTED_REVIEW_REL,
        "R001 review path authority differs",
    )
    require(
        gap_builder.EXPECTED_R001_REVIEW_SHA256
        != gap_builder.PENDING_R001_REVIEW_SHA256
        and bytes_sha256(raw) == gap_builder.EXPECTED_R001_REVIEW_SHA256,
        "R001 rejected review exact raw SHA-256 differs or is unresolved",
    )
    require(
        value.get("schema_version")
        == "walksafe.npc-single-admin-recovery.review-result.v2"
        and value.get("goal_id") == trace.GOAL_ID
        and value.get("round_id") == R001_ROUND_ID
        and value.get("decision") == "REJECTED"
        and value.get("assignment_status") == "NOT_ISSUED_LEGACY_ROUND",
        "R001 rejected review identity differs",
    )
    _identity(value.get("reviewer"), "R001 reviewer", "SEPARATE_INTERNAL_REVIEWER")
    reviewed_at = value.get("reviewed_at")
    trace._parse_time(reviewed_at, "R001 reviewed_at")
    counts = value.get("finding_counts")
    require(
        counts == {"blocking": 7, "major_open": 2, "minor_open": 0},
        "R001 rejected review finding counts differ",
    )
    findings = value.get("findings")
    require(
        type(findings) is dict
        and set(findings) == {"blocking", "major_open", "minor_open"}
        and all(type(findings[key]) is list for key in findings)
        and len(findings["blocking"]) == 7
        and len(findings["major_open"]) == 2
        and len(findings["minor_open"]) == 0,
        "R001 rejected review findings differ",
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
        "R001 rejected review finding identities differ",
    )
    require(
        type(value.get("review_subject_binding")) is dict
        and type(value.get("reviewed_result_sha256_by_kind")) is dict
        and type(value.get("reviewed_evidence_manifest")) is list,
        "R001 rejected review bindings differ",
    )
    return {
        "path": R001_REVIEW_RESULT_REL.as_posix(),
        "sha256": bytes_sha256(raw),
        "document_id": value.get("document_id"),
        "round_id": R001_ROUND_ID,
        "decision": "REJECTED",
        "reviewed_at": reviewed_at,
        "finding_ids": sorted(finding_ids),
    }


def _load_validated_r002_review_history(
    root: Path,
) -> tuple[dict[Path, bytes], dict[Path, dict[str, Any]]]:
    raw_by_path = {
        path: _read_review_bytes(root, path) for path in R002_REVIEW_HISTORY_PATHS
    }
    for path, (expected_sha256, expected_byte_length) in (
        R002_REVIEW_HISTORY_SHA256_BY_PATH.items()
    ):
        require(
            len(raw_by_path[path]) == expected_byte_length
            and bytes_sha256(raw_by_path[path]) == expected_sha256,
            f"R002 approved review history differs: {path}",
        )
    documents = {
        path: trace.strict_json_bytes(raw, path.as_posix())
        for path, raw in raw_by_path.items()
    }
    require(
        all(
            raw_by_path[path] == json_text(documents[path]).encode("utf-8")
            for path in R002_REVIEW_HISTORY_PATHS
        ),
        "R002 approved review history JSON is noncanonical",
    )
    assignment = documents[R002_REVIEW_ASSIGNMENT_REL]
    result = documents[R002_REVIEW_RESULT_REL]
    independent = documents[R002_INDEPENDENT_REVIEW_REL]
    completion = documents[R002_COMPLETION_RECEIPT_REL]
    assignment_provenance = {
        "path": R002_REVIEW_ASSIGNMENT_REL.as_posix(),
        "sha256": R002_REVIEW_HISTORY_SHA256_BY_PATH[R002_REVIEW_ASSIGNMENT_REL][0],
    }
    result_provenance = {
        "path": R002_REVIEW_RESULT_REL.as_posix(),
        "sha256": R002_REVIEW_HISTORY_SHA256_BY_PATH[R002_REVIEW_RESULT_REL][0],
    }
    require(
        set(assignment)
        == {
            "schema_version",
            "evidence_type",
            "goal_id",
            "round_id",
            "assigned_at",
            "assigner",
            "executor",
            "reviewer",
            "review_source_kind",
            "review_lineage",
            "additional_v2_audit_findings",
            "review_scope",
            "review_boundary",
        }
        and assignment.get("schema_version") == "2.0"
        and assignment.get("evidence_type") == "INTERNAL_REVIEW_ASSIGNMENT"
        and assignment.get("goal_id") == trace.GOAL_ID
        and assignment.get("round_id") == R002_ROUND_ID
        and result.get("round_id") == R002_ROUND_ID
        and independent.get("round_id") == R002_ROUND_ID
        and result.get("goal_id") == trace.GOAL_ID
        and independent.get("goal_id") == trace.GOAL_ID
        and completion.get("target_goal_id") == trace.GOAL_ID
        and completion.get("target_goal_content_sha256")
        == trace.EXPECTED_GOAL_SHA256
        and result.get("decision") == "APPROVED"
        and independent.get("status") == "PASS"
        and independent.get("decision") == "APPROVED"
        and completion.get("status") == "ACCEPTED"
        and completion.get("result") == "PASS",
        "R002 approved review history identity differs",
    )
    assigner = _identity(
        assignment.get("assigner"), "R002 assigner", "INTERNAL_REVIEW_ASSIGNER"
    )
    executor = _identity(
        assignment.get("executor"), "R002 executor", "INTERNAL_IMPLEMENTATION_EXECUTOR"
    )
    reviewer = _identity(
        assignment.get("reviewer"), "R002 reviewer", "SEPARATE_INTERNAL_REVIEWER"
    )
    _separate_reviewer(executor, reviewer)
    require(
        assignment.get("review_source_kind") == REVIEW_SOURCE_KIND
        and result.get("review_source_kind") == REVIEW_SOURCE_KIND
        and independent.get("review_source_kind") == REVIEW_SOURCE_KIND
        and result.get("assignment_participants")
        == {"assigner": assigner, "executor": executor}
        and result.get("reviewer") == reviewer
        and independent.get("assigner") == assigner
        and independent.get("executor") == executor
        and independent.get("reviewer") == reviewer,
        "R002 approved review participant binding differs",
    )
    require(
        result.get("assignment_binding") == assignment_provenance
        and independent.get("assignment_provenance") == assignment_provenance
        and independent.get("review_result_provenance") == result_provenance
        and completion.get("review_assignment_provenance") == assignment_provenance
        and completion.get("review_result_provenance") == result_provenance,
        "R002 approved review history provenance differs",
    )
    reviewed_at = result.get("reviewed_at")
    require(
        independent.get("reviewed_at") == reviewed_at
        and completion.get("completed_at") == reviewed_at,
        "R002 approved review history chronology differs",
    )
    reviewed_time = trace._parse_time(reviewed_at, "R002 reviewed_at")
    require(
        trace._parse_time(assignment.get("assigned_at"), "R002 assigned_at")
        <= reviewed_time
        and trace._parse_time(completion.get("generated_at"), "R002 generated_at")
        == reviewed_time,
        "R002 approved review ordering differs",
    )
    scope = assignment.get("review_scope")
    require(
        type(scope) is dict
        and set(scope)
        == {
            "review_subject",
            "reviewed_result_sha256_by_kind",
            "reviewed_consumer_bindings",
            "reviewed_evidence_manifest",
            "reviewed_evidence_manifest_sha256",
            "reviewed_chronology",
            "latest_reviewable_at",
            "reviewed_control_code_cohort",
            "reviewed_control_code_cohort_sha256",
        },
        "R002 approved review scope differs",
    )
    _strict_equal(result.get("review_scope"), scope, "R002 result review scope")
    _strict_equal(
        independent.get("review_scope"), scope, "R002 independent review scope"
    )
    _strict_equal(
        assignment.get("review_boundary"), _review_boundary(), "R002 assignment boundary"
    )
    _strict_equal(result.get("review_boundary"), _review_boundary(), "R002 result boundary")
    _strict_equal(
        independent.get("review_boundary"),
        _review_boundary(),
        "R002 independent review boundary",
    )
    _strict_equal(
        completion.get("completion_boundary"),
        trace.completion_boundary(),
        "R002 completion boundary",
    )
    _strict_equal(
        result.get("review_lineage"),
        assignment.get("review_lineage"),
        "R002 result review lineage",
    )
    _strict_equal(
        independent.get("review_lineage"),
        assignment.get("review_lineage"),
        "R002 independent review lineage",
    )
    _strict_equal(
        completion.get("review_lineage"),
        assignment.get("review_lineage"),
        "R002 completion review lineage",
    )
    expected_audit_findings = list(ADDITIONAL_V2_AUDIT_FINDINGS[:-1])
    _strict_equal(
        assignment.get("additional_v2_audit_findings"),
        expected_audit_findings,
        "R002 audit finding inventory",
    )
    _strict_equal(
        result.get("additional_v2_audit_findings"),
        expected_audit_findings,
        "R002 result audit finding inventory",
    )
    _strict_equal(
        independent.get("additional_v2_audit_findings"),
        expected_audit_findings,
        "R002 independent audit finding inventory",
    )
    _strict_equal(
        completion.get("additional_v2_audit_findings"),
        expected_audit_findings,
        "R002 completion audit finding inventory",
    )
    require(
        result.get("findings")
        == {"blocking": [], "major_open": [], "minor_open": []}
        and independent.get("findings") == result.get("findings"),
        "R002 approved review has open findings",
    )
    predecessor_ids = assignment.get("review_lineage", {}).get(
        "predecessor_result", {}
    ).get("finding_ids")
    predecessor_dispositions = result.get("predecessor_finding_dispositions")
    audit_dispositions = result.get("additional_v2_audit_finding_dispositions")
    require(
        type(predecessor_ids) is list
        and len(predecessor_ids) == len(set(predecessor_ids)) == 9
        and type(predecessor_dispositions) is list
        and [row.get("finding_id") for row in predecessor_dispositions]
        == predecessor_ids
        and all(row.get("disposition") == "CLOSED" for row in predecessor_dispositions)
        and type(audit_dispositions) is list
        and [row.get("finding_id") for row in audit_dispositions]
        == [row["finding_id"] for row in expected_audit_findings]
        and all(
            row.get("disposition") in {"CLOSED", "NOT_APPLICABLE"}
            for row in audit_dispositions
        ),
        "R002 approved review finding dispositions differ",
    )
    _strict_equal(
        independent.get("predecessor_finding_dispositions"),
        predecessor_dispositions,
        "R002 independent predecessor dispositions",
    )
    _strict_equal(
        completion.get("predecessor_finding_dispositions"),
        predecessor_dispositions,
        "R002 completion predecessor dispositions",
    )
    _strict_equal(
        independent.get("additional_v2_audit_finding_dispositions"),
        audit_dispositions,
        "R002 independent audit dispositions",
    )
    _strict_equal(
        completion.get("additional_v2_audit_finding_dispositions"),
        audit_dispositions,
        "R002 completion audit dispositions",
    )
    require(
        scope.get("reviewed_evidence_manifest_sha256")
        == trace.object_sha256(scope.get("reviewed_evidence_manifest"))
        and scope.get("reviewed_control_code_cohort_sha256")
        == trace.object_sha256(scope.get("reviewed_control_code_cohort")),
        "R002 approved review scope seal differs",
    )
    independent_provenance = {
        "path": R002_INDEPENDENT_REVIEW_REL.as_posix(),
        "sha256": R002_REVIEW_HISTORY_SHA256_BY_PATH[
            R002_INDEPENDENT_REVIEW_REL
        ][0],
    }
    require(
        completion.get("reviewer_provenance") == independent_provenance
        and completion.get("reviewed_control_code_cohort")
        == scope.get("reviewed_control_code_cohort")
        and completion.get("reviewed_control_code_cohort_sha256")
        == scope.get("reviewed_control_code_cohort_sha256")
        and completion.get("downstream_consumer_bindings")
        == scope.get("reviewed_consumer_bindings"),
        "R002 completion review scope binding differs",
    )
    result_paths = {
        "IMPLEMENTATION_RECORD": trace.V2_IMPLEMENTATION_REL,
        "VERIFICATION_RESULT": trace.V2_VERIFICATION_REL,
        "SUCCESSOR_TRACE": trace.V2_SUCCESSOR_REL,
    }
    require(
        completion.get("result_evidence")
        == [
            {
                "kind": kind,
                "path": path.as_posix(),
                "sha256": scope["reviewed_result_sha256_by_kind"][kind],
            }
            for kind, path in result_paths.items()
        ]
        and completion.get("self_excluded_from_output_manifest") is True,
        "R002 completion result evidence differs",
    )
    expected_manifest = [
        *deepcopy(scope["reviewed_evidence_manifest"]),
        assignment_provenance,
        result_provenance,
        independent_provenance,
    ]
    require(
        completion.get("output_evidence_manifest") == expected_manifest
        and completion.get("output_evidence_manifest_sha256")
        == trace.object_sha256(expected_manifest),
        "R002 completion output evidence binding differs",
    )
    return raw_by_path, documents


def _prior_approved_review_summary(
    documents: Mapping[Path, Mapping[str, Any]],
) -> dict[str, Any]:
    result = documents[R002_REVIEW_RESULT_REL]
    reviewed_at = result["reviewed_at"]
    assignment_provenance = {
        "path": R002_REVIEW_ASSIGNMENT_REL.as_posix(),
        "sha256": R002_REVIEW_HISTORY_SHA256_BY_PATH[
            R002_REVIEW_ASSIGNMENT_REL
        ][0],
    }
    result_provenance = {
        "path": R002_REVIEW_RESULT_REL.as_posix(),
        "sha256": R002_REVIEW_HISTORY_SHA256_BY_PATH[R002_REVIEW_RESULT_REL][0],
    }
    return {
        "round_id": R002_ROUND_ID,
        "decision": "APPROVED",
        "reviewed_at": reviewed_at,
        "assignment": {
            **assignment_provenance,
            "byte_length": R002_REVIEW_HISTORY_SHA256_BY_PATH[
                R002_REVIEW_ASSIGNMENT_REL
            ][1],
        },
        "result": {
            **result_provenance,
            "byte_length": R002_REVIEW_HISTORY_SHA256_BY_PATH[
                R002_REVIEW_RESULT_REL
            ][1],
        },
        "independent_review": {
            "path": R002_INDEPENDENT_REVIEW_REL.as_posix(),
            "sha256": R002_REVIEW_HISTORY_SHA256_BY_PATH[
                R002_INDEPENDENT_REVIEW_REL
            ][0],
            "byte_length": R002_REVIEW_HISTORY_SHA256_BY_PATH[
                R002_INDEPENDENT_REVIEW_REL
            ][1],
        },
        "completion_receipt": {
            "path": R002_COMPLETION_RECEIPT_REL.as_posix(),
            "sha256": R002_REVIEW_HISTORY_SHA256_BY_PATH[
                R002_COMPLETION_RECEIPT_REL
            ][0],
            "byte_length": R002_REVIEW_HISTORY_SHA256_BY_PATH[
                R002_COMPLETION_RECEIPT_REL
            ][1],
        },
    }


def _load_prior_approved_review(root: Path) -> dict[str, Any]:
    _raw_by_path, documents = _load_validated_r002_review_history(root)
    return _prior_approved_review_summary(documents)


def load_immutable_predecessor_history_sha256_by_path(
    root: Path = ROOT,
) -> dict[Path, str]:
    require(
        len(IMMUTABLE_PREDECESSOR_HISTORY_PATHS) == 20
        and len(set(IMMUTABLE_PREDECESSOR_HISTORY_PATHS)) == 20,
        "immutable predecessor history inventory differs",
    )
    expected = {
        **gap_builder.EXPECTED_R026_SHA256_BY_PATH,
        **r016_builder.EXPECTED_R015_SHA256_BY_PATH,
        **gap_builder.load_validated_r001_history_sha256_by_path(root),
    }
    require(
        tuple(expected) == IMMUTABLE_PREDECESSOR_HISTORY_PATHS,
        "immutable predecessor history order or inventory differs",
    )
    for path, digest in expected.items():
        require(
            bytes_sha256(_read_review_bytes(root, path)) == digest,
            f"immutable predecessor history differs: {path}",
        )
    return expected


def _review_lineage(context: ReviewContext) -> dict[str, Any]:
    return {
        "predecessor_round_id": R002_ROUND_ID,
        "predecessor_result": deepcopy(dict(context.prior_approved_review)),
        "origin_rejected_review": deepcopy(dict(context.predecessor_review)),
        "predecessor_remains_add_only": True,
        "supersedes_for_completion_only": True,
    }


def _r027_physical_evidence_rows(
    root: Path,
    rebuilt_outputs: Mapping[Path, str],
    consumer_raw_by_path: Mapping[Path, bytes],
) -> tuple[dict[str, str], ...]:
    expected_paths = (
        gap_builder.R027_GAP_JSON_REL,
        gap_builder.R027_GAP_MD_REL,
        gap_builder.R027_BACKLOG_JSON_REL,
        gap_builder.R027_BACKLOG_MD_REL,
    )
    output_paths = tuple(gap_builder.OUTPUT_PATHS)
    require(
        output_paths == expected_paths and len(output_paths) == len(set(output_paths)),
        "reviewed R027 output inventory differs or is duplicated",
    )
    require(
        set(rebuilt_outputs) == set(output_paths),
        "reviewed R027 rebuilt output inventory differs",
    )

    rows: list[dict[str, str]] = []
    for path in output_paths:
        rebuilt = rebuilt_outputs[path]
        raw = _read_review_bytes(root, path)
        require(
            path not in consumer_raw_by_path or consumer_raw_by_path[path] == raw,
            f"reviewed R027 consumer bytes changed during exact raw reread: {path}",
        )
        require(
            type(rebuilt) is str and rebuilt.encode("utf-8") == raw,
            f"reviewed R027 physical output is not reproducible: {path}",
        )
        rows.append({"path": path.as_posix(), "sha256": bytes_sha256(raw)})
    require(
        len(rows) == len(output_paths)
        and len({row["path"] for row in rows}) == len(output_paths),
        "reviewed R027 physical evidence inventory differs or is duplicated",
    )
    return tuple(rows)


def prepare_review_context(root: Path = ROOT) -> ReviewContext:
    root = root.resolve()
    trace.validate_start_authority(root)
    _r002_raw_by_path, r002_documents = _load_validated_r002_review_history(root)
    r002_assignment = r002_documents[R002_REVIEW_ASSIGNMENT_REL]
    r002_completion = r002_documents[R002_COMPLETION_RECEIPT_REL]
    frozen_scope = r002_assignment["review_scope"]

    evidence_by_path: dict[str, str] = {}

    def add_evidence(path: str, digest: str) -> None:
        require(
            type(path) is str
            and bool(path)
            and type(digest) is str
            and len(digest) == 64,
            "review evidence binding is malformed",
        )
        previous = evidence_by_path.setdefault(path, digest)
        require(previous == digest, f"review evidence binding conflicts: {path}")

    frozen_evidence = frozen_scope.get("reviewed_evidence_manifest")
    require(
        type(frozen_evidence) is list and len(frozen_evidence) == 114,
        "R002 reviewed evidence manifest missing or incomplete",
    )
    frozen_paths: list[str] = []
    for row in frozen_evidence:
        require(
            type(row) is dict
            and set(row) == {"path", "sha256"}
            and type(row.get("path")) is str
            and Path(row["path"]).as_posix() == row["path"]
            and not Path(row["path"]).is_absolute()
            and "." not in Path(row["path"]).parts
            and ".." not in Path(row["path"]).parts
            and type(row.get("sha256")) is str
            and trace.SHA256_RE.fullmatch(row["sha256"]) is not None,
            "R002 reviewed evidence binding malformed",
        )
        frozen_paths.append(row["path"])
        raw = _read_review_bytes(root, Path(row["path"]))
        require(
            bytes_sha256(raw) == row["sha256"],
            f"R002 reviewed product evidence differs: {row['path']}",
        )
        add_evidence(row["path"], row["sha256"])
    require(
        frozen_paths == sorted(set(frozen_paths))
        and len(evidence_by_path) == len(frozen_evidence),
        "R002 reviewed evidence inventory is unsorted or duplicated",
    )

    result_raw = {path: _read_review_bytes(root, path) for path in _result_paths()}
    result_documents = {
        path: trace.strict_json_bytes(raw, path.as_posix())
        for path, raw in result_raw.items()
    }
    result_paths = {
        "IMPLEMENTATION_RECORD": trace.V2_IMPLEMENTATION_REL,
        "VERIFICATION_RESULT": trace.V2_VERIFICATION_REL,
        "SUCCESSOR_TRACE": trace.V2_SUCCESSOR_REL,
    }
    expected_result_hashes = frozen_scope.get("reviewed_result_sha256_by_kind")
    require(
        type(expected_result_hashes) is dict
        and set(expected_result_hashes) == set(result_paths),
        "R002 reviewed result inventory differs",
    )
    require(
        all(
            bytes_sha256(result_raw[path]) == expected_result_hashes[kind]
            for kind, path in result_paths.items()
        ),
        "R002 reviewed result bytes differ",
    )
    subject_binding = frozen_scope.get("review_subject")
    require(
        subject_binding
        == {
            "path": trace.V2_REVIEW_SUBJECT_REL.as_posix(),
            "sha256": bytes_sha256(result_raw[trace.V2_REVIEW_SUBJECT_REL]),
        },
        "R002 reviewed subject binding differs",
    )
    for document, field, label in (
        (
            result_documents[trace.V2_IMPLEMENTATION_REL],
            "implementation_record_content_sha256",
            "implementation",
        ),
        (
            result_documents[trace.V2_VERIFICATION_REL],
            "verification_result_content_sha256",
            "verification",
        ),
        (
            result_documents[trace.V2_SUCCESSOR_REL],
            "successor_trace_content_sha256",
            "successor",
        ),
        (
            result_documents[trace.V2_REVIEW_SUBJECT_REL],
            "review_subject_content_sha256",
            "review subject",
        ),
    ):
        trace.verify_seal(document, field, label)
        require(
            document.get("completion_boundary") == trace.completion_boundary(),
            f"{label} completion boundary differs",
        )
    require(
        result_documents[trace.V2_REVIEW_SUBJECT_REL].get(
            "reviewed_result_sha256_by_kind"
        )
        == expected_result_hashes,
        "R002 review subject result hashes differ",
    )

    frozen_consumers = frozen_scope.get("reviewed_consumer_bindings")
    require(
        type(frozen_consumers) is list
        and len(frozen_consumers) == len(trace.CONSUMER_SPECS),
        "R002 reviewed consumer inventory differs",
    )
    consumer_bindings: list[dict[str, Any]] = []
    for (role, path), frozen_binding in zip(
        trace.CONSUMER_SPECS, frozen_consumers, strict=True
    ):
        raw = _read_review_bytes(root, path)
        document = trace.strict_json_bytes(raw, path.as_posix())
        live_binding = {
            "role": role,
            "path": path.as_posix(),
            "document_id": trace._document_id(document, path),
            "sha256": bytes_sha256(raw),
            "byte_length": len(raw),
        }
        _strict_equal(frozen_binding, live_binding, f"R002 reviewed consumer {role}")
        consumer_bindings.append(live_binding)
    require(
        result_documents[trace.V2_SUCCESSOR_REL].get(
            "downstream_consumer_bindings"
        )
        == consumer_bindings
        and result_documents[trace.V2_REVIEW_SUBJECT_REL].get(
            "reviewed_consumer_bindings"
        )
        == consumer_bindings,
        "R002 successor or review-subject consumer binding differs",
    )

    predecessor_review = _load_predecessor_review(root)
    add_evidence(predecessor_review["path"], predecessor_review["sha256"])
    immutable_history = load_immutable_predecessor_history_sha256_by_path(root)
    frozen_evidence_path_set = set(frozen_paths)
    immutable_history_path_set = {
        path.as_posix() for path in IMMUTABLE_PREDECESSOR_HISTORY_PATHS
    }
    r002_history_path_set = {path.as_posix() for path in R002_REVIEW_HISTORY_PATHS}
    require(
        frozen_evidence_path_set.isdisjoint(immutable_history_path_set)
        and frozen_evidence_path_set.isdisjoint(r002_history_path_set)
        and immutable_history_path_set.isdisjoint(r002_history_path_set),
        "R003 predecessor evidence cohorts overlap",
    )
    for path, digest in immutable_history.items():
        add_evidence(path.as_posix(), digest)
    prior_approved_review = _prior_approved_review_summary(r002_documents)
    for path, (digest, _byte_length) in R002_REVIEW_HISTORY_SHA256_BY_PATH.items():
        add_evidence(path.as_posix(), digest)

    evidence_rows = tuple(
        {"path": path, "sha256": evidence_by_path[path]}
        for path in sorted(evidence_by_path)
    )
    require(
        len(evidence_rows) == 138,
        "R003 reviewed evidence inventory is not exact 114+20+4",
    )
    frozen_chronology = frozen_scope.get("reviewed_chronology")
    require(type(frozen_chronology) is list, "R002 reviewed chronology missing")
    chronology = deepcopy(frozen_chronology)
    chronology.extend(
        [
            {
                "path": R002_REVIEW_ASSIGNMENT_REL.as_posix(),
                "json_pointer": "/assigned_at",
                "timestamp": r002_assignment["assigned_at"],
            },
            {
                "path": R002_REVIEW_RESULT_REL.as_posix(),
                "json_pointer": "/reviewed_at",
                "timestamp": r002_documents[R002_REVIEW_RESULT_REL]["reviewed_at"],
            },
            {
                "path": R002_INDEPENDENT_REVIEW_REL.as_posix(),
                "json_pointer": "/reviewed_at",
                "timestamp": r002_documents[R002_INDEPENDENT_REVIEW_REL][
                    "reviewed_at"
                ],
            },
            {
                "path": R002_COMPLETION_RECEIPT_REL.as_posix(),
                "json_pointer": "/completed_at",
                "timestamp": r002_completion["completed_at"],
            },
            {
                "path": R002_COMPLETION_RECEIPT_REL.as_posix(),
                "json_pointer": "/generated_at",
                "timestamp": r002_completion["generated_at"],
            },
        ]
    )
    require(
        all(
            type(row) is dict
            and set(row) == {"path", "json_pointer", "timestamp"}
            and all(type(value) is str for value in row.values())
            for row in chronology
        ),
        "R002 reviewed chronology row differs",
    )
    chronology.sort(key=lambda row: (row["path"], row["json_pointer"], row["timestamp"]))
    require(chronology, "review chronology is empty")
    latest = max(
        chronology,
        key=lambda row: trace._parse_time(row["timestamp"], "review chronology timestamp"),
    )["timestamp"]
    execution_window = r002_completion.get("execution_window")
    require(
        type(execution_window) is dict
        and set(execution_window) == {"started_at", "ended_at"}
        and trace._parse_time(execution_window.get("started_at"), "R002 started_at")
        <= trace._parse_time(execution_window.get("ended_at"), "R002 ended_at"),
        "R002 execution window differs",
    )
    control_cohort, control_hash = _control_code_cohort(root)
    prior_controls = frozen_scope.get("reviewed_control_code_cohort")
    require(
        type(prior_controls) is list
        and len(prior_controls) == len(CONTROL_CODE_PATHS)
        and [row.get("path") for row in prior_controls]
        == [path.as_posix() for path in CONTROL_CODE_PATHS]
        and all(
            type(row) is dict
            and set(row) == {"path", "sha256", "byte_length"}
            and type(row.get("sha256")) is str
            and trace.SHA256_RE.fullmatch(row["sha256"]) is not None
            and type(row.get("byte_length")) is int
            and row["byte_length"] >= 0
            for row in prior_controls
        ),
        "R002 reviewed control-code cohort differs",
    )
    prior_control_hash = frozen_scope.get("reviewed_control_code_cohort_sha256")
    require(
        type(prior_control_hash) is str
        and prior_control_hash == trace.object_sha256(prior_controls),
        "R002 reviewed control-code cohort seal differs",
    )
    control_changes = tuple(
        {
            "path": current["path"],
            "before_sha256": prior["sha256"],
            "before_byte_length": prior["byte_length"],
            "after_sha256": current["sha256"],
            "after_byte_length": current["byte_length"],
        }
        for prior, current in zip(prior_controls, control_cohort, strict=True)
        if prior["sha256"] != current["sha256"]
        or prior["byte_length"] != current["byte_length"]
    )
    for row in evidence_rows:
        require(
            bytes_sha256(_read_review_bytes(root, Path(row["path"]))) == row["sha256"],
            f"review evidence changed during R003 snapshot: {row['path']}",
        )
    for row in control_cohort:
        raw = _read_review_bytes(root, Path(row["path"]))
        require(
            bytes_sha256(raw) == row["sha256"] and len(raw) == row["byte_length"],
            f"control code changed during R003 snapshot: {row['path']}",
        )
    return ReviewContext(
        result_raw=result_raw,
        consumer_bindings=tuple(consumer_bindings),
        evidence_manifest=evidence_rows,
        review_subject_sha256=bytes_sha256(result_raw[trace.V2_REVIEW_SUBJECT_REL]),
        reviewed_result_sha256_by_kind=deepcopy(expected_result_hashes),
        implementation_started_at=execution_window["started_at"],
        implementation_ended_at=execution_window["ended_at"],
        chronology=tuple(chronology),
        latest_reviewable_at=latest,
        control_code_cohort=control_cohort,
        control_code_cohort_sha256=control_hash,
        prior_control_code_cohort_sha256=prior_control_hash,
        control_code_changes_from_r002=control_changes,
        predecessor_review=predecessor_review,
        prior_approved_review=prior_approved_review,
    )


def validate_assignment(assignment: Mapping[str, Any], context: ReviewContext) -> None:
    require(
        set(assignment)
        == {
            "schema_version",
            "evidence_type",
            "goal_id",
            "round_id",
            "assigned_at",
            "assigner",
            "executor",
            "reviewer",
            "review_source_kind",
            "review_lineage",
            "additional_v2_audit_findings",
            "review_scope",
            "review_boundary",
        },
        "review assignment fields differ",
    )
    require(assignment.get("schema_version") == "2.0", "review assignment schema differs")
    require(
        assignment.get("evidence_type") == "INTERNAL_REVIEW_ASSIGNMENT"
        and assignment.get("goal_id") == trace.GOAL_ID,
        "review assignment identity differs",
    )
    round_id = assignment.get("round_id")
    require(
        type(round_id) is str
        and ROUND_ID_RE.fullmatch(round_id) is not None
        and round_id == R003_ROUND_ID,
        "review round id differs",
    )
    assigned_at = assignment.get("assigned_at")
    require(
        trace._parse_time(assigned_at, "assigned_at")
        >= trace._parse_time(context.latest_reviewable_at, "latest_reviewable_at"),
        "review assignment predates reviewed cohort",
    )
    assigner = _identity(assignment.get("assigner"), "assigner", "INTERNAL_REVIEW_ASSIGNER")
    executor = _identity(assignment.get("executor"), "executor", "INTERNAL_IMPLEMENTATION_EXECUTOR")
    reviewer = _identity(assignment.get("reviewer"), "reviewer", "SEPARATE_INTERNAL_REVIEWER")
    _separate_reviewer(executor, reviewer)
    require(
        assigner["agent_instance_id"] != reviewer["agent_instance_id"]
        and assigner["canonical_task"] != reviewer["canonical_task"],
        "reviewer cannot assign own review",
    )
    require(assignment.get("review_source_kind") == REVIEW_SOURCE_KIND, "review source kind differs")
    _strict_equal(assignment.get("review_lineage"), _review_lineage(context), "review assignment lineage")
    _strict_equal(
        assignment.get("additional_v2_audit_findings"),
        list(ADDITIONAL_V2_AUDIT_FINDINGS),
        "additional v2 audit finding inventory",
    )
    _strict_equal(assignment.get("review_scope"), _review_scope(context), "review assignment scope")
    _strict_equal(assignment.get("review_boundary"), _review_boundary(), "review assignment boundary")


def _validate_findings(findings: Any, context: ReviewContext) -> None:
    require(
        type(findings) is dict
        and set(findings) == {"blocking", "major_open", "minor_open"},
        "review findings fields differ",
    )
    evidence_paths = {row["path"] for row in context.evidence_manifest} | {
        row["path"] for row in context.control_code_cohort
    }
    seen: set[str] = set()
    for severity in ("blocking", "major_open", "minor_open"):
        rows = findings[severity]
        require(type(rows) is list, f"review findings {severity} differs")
        for row in rows:
            require(
                type(row) is dict
                and set(row) == {"finding_id", "summary", "evidence_paths"},
                f"review finding shape differs: {severity}",
            )
            finding_id = row.get("finding_id")
            summary = row.get("summary")
            refs = row.get("evidence_paths")
            require(
                type(finding_id) is str
                and finding_id
                and finding_id not in seen
                and type(summary) is str
                and bool(summary.strip())
                and type(refs) is list
                and bool(refs)
                and all(type(path) is str and path in evidence_paths for path in refs),
                f"review finding content differs: {severity}",
            )
            seen.add(finding_id)


def validate_review_result(
    result: Mapping[str, Any],
    result_raw: bytes,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    context: ReviewContext,
) -> None:
    validate_assignment(assignment, context)
    require(assignment_raw == json_text(assignment).encode(), "review assignment JSON is noncanonical")
    require(
        set(result)
        == {
            "schema_version",
            "evidence_type",
            "goal_id",
            "round_id",
            "assignment_binding",
            "assignment_participants",
            "reviewer",
            "reviewed_at",
            "review_source_kind",
            "review_lineage",
            "additional_v2_audit_findings",
            "review_scope",
            "decision",
            "findings",
            "predecessor_finding_dispositions",
            "additional_v2_audit_finding_dispositions",
            "review_boundary",
        },
        "review result fields differ",
    )
    require(result.get("schema_version") == "2.0", "review result schema differs")
    require(
        result.get("evidence_type") == "REVIEWER_AUTHORED_INTERNAL_REVIEW_RESULT"
        and result.get("goal_id") == trace.GOAL_ID
        and result.get("round_id") == assignment.get("round_id"),
        "review result identity differs",
    )
    require(
        result.get("assignment_binding")
        == {
            "path": REVIEW_ASSIGNMENT_REL.as_posix(),
            "sha256": bytes_sha256(assignment_raw),
        },
        "review result assignment hash differs",
    )
    _strict_equal(
        result.get("assignment_participants"),
        {
            "assigner": assignment.get("assigner"),
            "executor": assignment.get("executor"),
        },
        "review result assignment participants",
    )
    reviewer = _identity(result.get("reviewer"), "review result reviewer", "SEPARATE_INTERNAL_REVIEWER")
    _strict_equal(reviewer, assignment.get("reviewer"), "assigned reviewer")
    executor = _identity(assignment.get("executor"), "executor", "INTERNAL_IMPLEMENTATION_EXECUTOR")
    _separate_reviewer(executor, reviewer)
    reviewed_at = result.get("reviewed_at")
    reviewed_time = trace._parse_time(reviewed_at, "reviewed_at")
    require(
        reviewed_time >= trace._parse_time(assignment.get("assigned_at"), "assigned_at")
        and reviewed_time >= trace._parse_time(context.latest_reviewable_at, "latest_reviewable_at"),
        "review result predates reviewed evidence or assignment",
    )
    require(result.get("review_source_kind") == REVIEW_SOURCE_KIND, "review result source kind differs")
    _strict_equal(result.get("review_lineage"), assignment.get("review_lineage"), "review result lineage")
    _strict_equal(
        result.get("additional_v2_audit_findings"),
        assignment.get("additional_v2_audit_findings"),
        "review result additional v2 audit findings",
    )
    _strict_equal(result.get("review_scope"), _review_scope(context), "review result scope")
    decision = result.get("decision")
    require(decision in ALLOWED_DECISIONS, "review decision differs")
    _validate_findings(result.get("findings"), context)
    findings = result["findings"]
    dispositions = result.get("predecessor_finding_dispositions")
    require(
        type(dispositions) is list and len(dispositions) == 9,
        "predecessor finding dispositions differ",
    )
    disposition_ids: set[str] = set()
    evidence_paths = {row["path"] for row in context.evidence_manifest} | {
        row["path"] for row in context.control_code_cohort
    }
    for row in dispositions:
        require(
            type(row) is dict
            and set(row)
            == {"finding_id", "disposition", "rationale", "remediation_evidence_paths"}
            and type(row.get("finding_id")) is str
            and row["finding_id"] not in disposition_ids
            and row.get("disposition") in {"CLOSED", "NOT_APPLICABLE"}
            and type(row.get("rationale")) is str
            and bool(row["rationale"].strip())
            and type(row.get("remediation_evidence_paths")) is list
            and bool(row["remediation_evidence_paths"])
            and all(path in evidence_paths for path in row["remediation_evidence_paths"]),
            "predecessor finding disposition content differs",
        )
        disposition_ids.add(row["finding_id"])
    require(
        disposition_ids == set(context.predecessor_review["finding_ids"]),
        "predecessor finding disposition identities differ",
    )
    audit_dispositions = result.get("additional_v2_audit_finding_dispositions")
    require(
        type(audit_dispositions) is list
        and len(audit_dispositions) == len(ADDITIONAL_V2_AUDIT_FINDINGS),
        "additional v2 audit finding dispositions differ",
    )
    expected_audit_ids = {row["finding_id"] for row in ADDITIONAL_V2_AUDIT_FINDINGS}
    audit_disposition_ids: set[str] = set()
    for row in audit_dispositions:
        retest_paths = row.get("adversarial_retest_evidence_paths")
        r003_history_paths = {
            path.as_posix()
            for path in (
                *IMMUTABLE_PREDECESSOR_HISTORY_PATHS,
                *R002_REVIEW_HISTORY_PATHS,
            )
        }
        control_paths = {item["path"] for item in context.control_code_cohort}
        require(
            type(row) is dict
            and set(row)
            == {"finding_id", "disposition", "rationale", "adversarial_retest_evidence_paths"}
            and row.get("finding_id") in expected_audit_ids
            and row["finding_id"] not in audit_disposition_ids
            and row.get("disposition") in {"CLOSED", "NOT_APPLICABLE"}
            and (
                row["finding_id"] != "NPC-R003-AUDIT-P1-001"
                or row.get("disposition") == "CLOSED"
            )
            and type(row.get("rationale")) is str
            and bool(row["rationale"].strip())
            and type(retest_paths) is list
            and bool(retest_paths)
            and all(path in evidence_paths for path in retest_paths)
            and (
                row["finding_id"] != "NPC-R003-AUDIT-P1-001"
                or (
                    bool(set(retest_paths).intersection(r003_history_paths))
                    and bool(set(retest_paths).intersection(control_paths))
                )
            ),
            "additional v2 audit finding disposition content differs",
        )
        audit_disposition_ids.add(row["finding_id"])
    require(
        audit_disposition_ids == expected_audit_ids,
        "additional v2 audit disposition identities differ",
    )
    if decision == "APPROVED":
        require(not findings["blocking"] and not findings["major_open"], "approved review has blocking or major findings")
    elif decision == "CHANGES_REQUESTED":
        require(any(findings.values()), "changes-requested review has no findings")
    else:
        require(bool(findings["blocking"]), "rejected review has no blocking finding")
    _strict_equal(result.get("review_boundary"), _review_boundary(), "review result boundary")
    require(result_raw == json_text(result).encode(), "review result JSON is noncanonical")


def build_post_review_outputs(
    context: ReviewContext,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    result: Mapping[str, Any],
    result_raw: bytes,
) -> dict[Path, str]:
    validate_review_result(result, result_raw, assignment, assignment_raw, context)
    findings = result["findings"]
    require(
        result.get("decision") == "APPROVED"
        and not findings["blocking"]
        and not findings["major_open"]
        and not findings["minor_open"],
        "review result does not authorize completion",
    )
    assignment_provenance = {
        "path": REVIEW_ASSIGNMENT_REL.as_posix(),
        "sha256": bytes_sha256(assignment_raw),
    }
    result_provenance = {
        "path": REVIEW_RESULT_REL.as_posix(),
        "sha256": bytes_sha256(result_raw),
    }
    review = {
        "schema_version": "2.0",
        "document_id": "WS-NPC-SINGLE-ADMIN-RECOVERY-INTERNAL-REVIEW-20260813-002",
        "evidence_type": "INDEPENDENT_INTERNAL_REVIEW",
        "goal_id": trace.GOAL_ID,
        "round_id": result["round_id"],
        "status": "PASS",
        "reviewer": deepcopy(result["reviewer"]),
        "assigner": deepcopy(assignment["assigner"]),
        "executor": deepcopy(assignment["executor"]),
        "reviewed_at": result["reviewed_at"],
        "review_source_kind": result["review_source_kind"],
        "review_lineage": deepcopy(result["review_lineage"]),
        "review_scope": deepcopy(result["review_scope"]),
        "additional_v2_audit_findings": deepcopy(
            result["additional_v2_audit_findings"]
        ),
        "assignment_provenance": assignment_provenance,
        "review_result_provenance": result_provenance,
        "decision": result["decision"],
        "findings": deepcopy(findings),
        "predecessor_finding_dispositions": deepcopy(
            result["predecessor_finding_dispositions"]
        ),
        "additional_v2_audit_finding_dispositions": deepcopy(
            result["additional_v2_audit_finding_dispositions"]
        ),
        "review_boundary": _review_boundary(),
    }
    review_text = json_text(review)
    result_paths = {
        "IMPLEMENTATION_RECORD": trace.V2_IMPLEMENTATION_REL,
        "VERIFICATION_RESULT": trace.V2_VERIFICATION_REL,
        "SUCCESSOR_TRACE": trace.V2_SUCCESSOR_REL,
    }
    manifest = [
        *deepcopy(list(context.evidence_manifest)),
        assignment_provenance,
        result_provenance,
        {"path": INDEPENDENT_REVIEW_REL.as_posix(), "sha256": bytes_sha256(review_text.encode())},
    ]
    completion = {
        "schema_version": "2.0",
        "document_id": "WS-NPC-SINGLE-ADMIN-RECOVERY-WORK-ITEM-COMPLETION-20260813-002",
        "evidence_type": "WORK_ITEM_EXECUTION_RECEIPT",
        "target_goal_id": trace.GOAL_ID,
        "target_goal_content_sha256": trace.EXPECTED_GOAL_SHA256,
        "work_item_id": trace.WORK_ITEM_ID,
        "source_policy_ids": [trace.POLICY_ID],
        "gap_ids": [trace.GAP_ID],
        "target_completion_level": "INTERNAL_POLICY_CONFORMANCE_REASSESSED",
        "status": "ACCEPTED",
        "result": "PASS",
        "execution_start_event_sha256": trace.EXPECTED_START_EVENT_SHA256,
        "execution_start_event": {
            "sequence": trace.EXPECTED_START_EVENT_SEQUENCE,
            "event_id": trace.EXPECTED_START_EVENT_ID,
            "event_sha256": trace.EXPECTED_START_EVENT_SHA256,
        },
        "implementation_start_gate_binding": {
            "path": trace.START_GATE_RECEIPT_REL.as_posix(),
            "file_sha256": trace.EXPECTED_START_GATE_RECEIPT_SHA256,
        },
        "execution_window": {
            "started_at": context.implementation_started_at,
            "ended_at": context.implementation_ended_at,
        },
        "completed_at": result["reviewed_at"],
        "executor": deepcopy(assignment["executor"]),
        "reviewer": {
            **deepcopy(result["reviewer"]),
            "round_id": result["round_id"],
            "review_source_kind": result["review_source_kind"],
            "separate_internal_review_pass": True,
            "external_independence_claimed": False,
            "decision": result["decision"],
            "decided_at": result["reviewed_at"],
        },
        "review_assignment_provenance": assignment_provenance,
        "review_result_provenance": result_provenance,
        "review_lineage": deepcopy(result["review_lineage"]),
        "review_scope": deepcopy(result["review_scope"]),
        "predecessor_finding_dispositions": deepcopy(
            result["predecessor_finding_dispositions"]
        ),
        "additional_v2_audit_findings": deepcopy(
            result["additional_v2_audit_findings"]
        ),
        "additional_v2_audit_finding_dispositions": deepcopy(
            result["additional_v2_audit_finding_dispositions"]
        ),
        "reviewer_provenance": {
            "path": INDEPENDENT_REVIEW_REL.as_posix(),
            "sha256": bytes_sha256(review_text.encode()),
        },
        "reviewed_control_code_cohort": deepcopy(list(context.control_code_cohort)),
        "reviewed_control_code_cohort_sha256": context.control_code_cohort_sha256,
        "result_evidence": [
            {"kind": kind, "path": result_paths[kind].as_posix(), "sha256": digest}
            for kind, digest in context.reviewed_result_sha256_by_kind.items()
        ],
        "downstream_consumer_bindings": deepcopy(list(context.consumer_bindings)),
        "output_evidence_manifest": manifest,
        "output_evidence_manifest_sha256": trace.object_sha256(manifest),
        "self_excluded_from_output_manifest": True,
        "completion_boundary": trace.completion_boundary(),
        "generated_at": result["reviewed_at"],
    }
    return {
        INDEPENDENT_REVIEW_REL: review_text,
        COMPLETION_RECEIPT_REL: json_text(completion),
    }


def load_review_inputs(
    root: Path, context: ReviewContext
) -> tuple[dict[str, Any], bytes, dict[str, Any], bytes]:
    assignment_raw = _read_review_bytes(root, REVIEW_ASSIGNMENT_REL)
    assignment = trace.strict_json_bytes(assignment_raw, REVIEW_ASSIGNMENT_REL.as_posix())
    result_raw = _read_review_bytes(root, REVIEW_RESULT_REL)
    result = trace.strict_json_bytes(result_raw, REVIEW_RESULT_REL.as_posix())
    validate_review_result(result, result_raw, assignment, assignment_raw, context)
    return assignment, assignment_raw, result, result_raw


def _guard_post_review_write_boundary(
    root: Path,
    expected_outputs: Mapping[Path, str],
) -> None:
    """Rebuild the complete R003 authority immediately before add-only write."""

    current_context = prepare_review_context(root)
    assignment, assignment_raw, result, result_raw = load_review_inputs(
        root,
        current_context,
    )
    current_outputs = build_post_review_outputs(
        current_context,
        assignment,
        assignment_raw,
        result,
        result_raw,
    )
    require(
        dict(current_outputs) == dict(expected_outputs),
        "R003 review authority changed before add-only publication",
    )


def _write_post_review_outputs(
    root: Path,
    expected_outputs: Mapping[Path, str],
) -> None:
    """Hold the checkpoint publication lock across final rebuild and write."""

    checkpoint = root / trace.CHECKPOINT_REL
    parent = checkpoint.parent
    parent_descriptor = os.open(
        parent,
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_DIRECTORY", 0)
        | getattr(os, "O_NOFOLLOW", 0),
    )
    descriptor: int | None = None
    try:
        parent_before = os.fstat(parent_descriptor)
        parent_entry = parent.lstat()
        require(
            stat.S_ISDIR(parent_before.st_mode)
            and (parent_before.st_dev, parent_before.st_ino)
            == (parent_entry.st_dev, parent_entry.st_ino),
            "R003 publication parent authority differs",
        )
        try:
            fcntl.flock(parent_descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise BuildError("R003 publication parent is already locked") from exc
        descriptor = os.open(
            checkpoint.name,
            os.O_RDONLY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0),
            dir_fd=parent_descriptor,
        )
        before = os.fstat(descriptor)
        require(
            stat.S_ISREG(before.st_mode)
            and before.st_uid == os.geteuid()
            and before.st_nlink == 1
            and stat.S_IMODE(before.st_mode) == 0o600,
            "R003 publication lock authority differs",
        )
        before_identity = (
            before.st_dev,
            before.st_ino,
            stat.S_IMODE(before.st_mode),
            before.st_uid,
            before.st_gid,
            before.st_nlink,
            before.st_size,
            before.st_mtime_ns,
            before.st_ctime_ns,
        )
        before_raw = os.pread(descriptor, before.st_size, 0)
        require(
            len(before_raw) == before.st_size,
            "R003 checkpoint could not be read before publication",
        )
        _guard_post_review_write_boundary(root, expected_outputs)
        guarded = os.fstat(descriptor)
        guarded_identity = (
            guarded.st_dev,
            guarded.st_ino,
            stat.S_IMODE(guarded.st_mode),
            guarded.st_uid,
            guarded.st_gid,
            guarded.st_nlink,
            guarded.st_size,
            guarded.st_mtime_ns,
            guarded.st_ctime_ns,
        )
        guarded_raw = os.pread(descriptor, guarded.st_size, 0)
        current = os.stat(
            checkpoint.name,
            dir_fd=parent_descriptor,
            follow_symlinks=False,
        )
        require(
            guarded_identity == before_identity
            and len(guarded_raw) == guarded.st_size
            and guarded_raw == before_raw
            and (current.st_dev, current.st_ino)
            == (before.st_dev, before.st_ino),
            "R003 checkpoint changed before add-only publication",
        )
        trace.write_or_check_outputs(root, expected_outputs, write=True)
        after = os.fstat(descriptor)
        after_identity = (
            after.st_dev,
            after.st_ino,
            stat.S_IMODE(after.st_mode),
            after.st_uid,
            after.st_gid,
            after.st_nlink,
            after.st_size,
            after.st_mtime_ns,
            after.st_ctime_ns,
        )
        after_raw = os.pread(descriptor, after.st_size, 0)
        current = os.stat(
            checkpoint.name,
            dir_fd=parent_descriptor,
            follow_symlinks=False,
        )
        require(
            before_identity == after_identity
            and len(after_raw) == after.st_size
            and after_raw == before_raw,
            "R003 checkpoint descriptor changed during add-only publication",
        )
        require(
            (current.st_dev, current.st_ino) == (after.st_dev, after.st_ino)
            and (parent.lstat().st_dev, parent.lstat().st_ino)
            == (parent_before.st_dev, parent_before.st_ino),
            "R003 checkpoint entry changed during add-only publication",
        )
    finally:
        if descriptor is not None:
            os.close(descriptor)
        fcntl.flock(parent_descriptor, fcntl.LOCK_UN)
        os.close(parent_descriptor)


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--root", type=Path, default=ROOT)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check-review-result", action="store_true")
    mode.add_argument("--write-post-review", action="store_true")
    mode.add_argument("--check-post-review", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        root = args.root.resolve()
        context = prepare_review_context(root)
        assignment, assignment_raw, result, result_raw = load_review_inputs(root, context)
        if not args.check_review_result:
            outputs = build_post_review_outputs(
                context, assignment, assignment_raw, result, result_raw
            )
            if args.write_post_review:
                _write_post_review_outputs(root, outputs)
            else:
                trace.write_or_check_outputs(root, outputs, write=False)
    except (BuildError, OSError, ValueError, TypeError) as exc:
        print(f"NPC single-admin recovery strict review gate: FAIL: {exc}")
        return 1
    print("NPC single-admin recovery strict review gate: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

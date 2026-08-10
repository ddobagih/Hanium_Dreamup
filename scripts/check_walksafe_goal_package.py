#!/usr/bin/env python3
"""Validate the WalkSafe autonomous Goal package against the live checkpoint."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import tomllib
from collections import Counter
from datetime import date, datetime
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import check_walksafe_project_continuation as continuation  # noqa: E402


CHECKPOINT_RELATIVE = Path("docs/control/walksafe-project-continuation-checkpoint.json")
PACKAGE_RELATIVE = Path("docs/control/goals/walksafe-completion-v1")
STATIC_PLAN_MANIFEST_RELATIVE = (
    PACKAGE_RELATIVE / "static-plan-manifest-v1.1.0.json"
)
EXPECTED_STATIC_PLAN_VERSION = "1.1.0"
# Updated only when a new, explicitly versioned static-plan manifest is created.
# The manifest itself then pins every Master/Phase/EPIC/support/initial-leaf byte.
EXPECTED_STATIC_PLAN_MANIFEST_SHA256 = (
    "e8a4d078c5be8429ec363b6135247504eb4411d356c08b645cd9feba534ff43e"
)
# The first append-only transition event anchors the manifest into execution
# history. It cannot live inside the manifest because that would create a hash
# cycle: the event hashes the manifest while the manifest hashes the event.
EXPECTED_INITIAL_TRANSITION_EVENT_SHA256 = (
    "04bd265dbffba57f6ec9103b4bceeee8cc890a341ba71ca56fd501cb93d34438"
)
# The reviewed handoff pins both ends of the append-only transition chain.
# Appending a legitimate event requires a deliberate, versioned update of this
# head anchor after the new event has been independently preserved/reviewed.
EXPECTED_TRANSITION_HISTORY_HEAD_SHA256 = (
    "04bd265dbffba57f6ec9103b4bceeee8cc890a341ba71ca56fd501cb93d34438"
)
# Every reviewed transition head also pins the latest admissible event time.
# Moving this cutoff requires the same deliberate checker/checkpoint review as
# moving the head anchor; otherwise pre-written future history could validate.
EXPECTED_VALIDATION_CUTOFF_AT = "2026-07-23T23:59:59+09:00"
# Phase B/C/D may not complete until an externally approved authority roster is
# pinned here by a versioned checker update. Tests inject a fixture anchor.
EXPECTED_AUTHORITY_ROSTER_SHA256 = "__AUTHORITY_ROSTER_SHA256_NOT_CONFIGURED__"
EXPECTED_AUTHORITY_ROSTER_SHA256_HISTORY: tuple[str, ...] = ()
# Each Phase B/C/D receipt must be independently signed/anchored first, then its
# exact immutable file hash is pinned by a versioned checker update. An approved
# roster alone cannot authorize a self-authored completion receipt.
EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE: dict[str, str] = {}
EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_HISTORY_BY_ROLE: dict[
    str,
    tuple[str, ...],
] = {}
INITIAL_WORK_ITEM_GOAL_ID = "WS-GOAL-A-EPIC-02-FP-018-R001"
SUPPORT_PATHS = {
    f"{PACKAGE_RELATIVE.as_posix()}/README.md",
    f"{PACKAGE_RELATIVE.as_posix()}/templates/work-item-template.md",
    STATIC_PLAN_MANIFEST_RELATIVE.as_posix(),
}
EXPECTED_PACKAGE_ID = "WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-V1"
EXPECTED_MASTER_ID = "WS-GOAL-WALKSAFE-COMPLETION-V1"
EXPECTED_PHASE_IDS = {
    "A": "WS-GOAL-PHASE-A",
    "B": "WS-GOAL-PHASE-B",
    "C": "WS-GOAL-PHASE-C",
    "D": "WS-GOAL-PHASE-D",
}
EXPECTED_PHASE_ORDER = ["A", "B", "C", "D"]
EXPECTED_EXECUTION_ORDER = [
    "EPIC-01",
    "EPIC-02",
    "EPIC-03",
    "EPIC-04",
    "EPIC-05",
    "EPIC-06",
    "EPIC-07",
    "EPIC-08",
    "EPIC-10",
    "EPIC-09",
    "EPIC-11",
    "EPIC-12",
]
EXPECTED_EPIC_GOAL_IDS = {
    "EPIC-01": "WS-GOAL-A-EPIC-01",
    "EPIC-02": "WS-GOAL-A-EPIC-02",
    "EPIC-03": "WS-GOAL-A-EPIC-03",
    "EPIC-04": "WS-GOAL-A-EPIC-04",
    "EPIC-05": "WS-GOAL-A-EPIC-05",
    "EPIC-06": "WS-GOAL-A-EPIC-06",
    "EPIC-07": "WS-GOAL-A-EPIC-07",
    "EPIC-08": "WS-GOAL-A-EPIC-08",
    "EPIC-10": "WS-GOAL-A-EPIC-10",
    "EPIC-09": "WS-GOAL-A-EPIC-09",
    "EPIC-11": "WS-GOAL-A-EPIC-11",
    "EPIC-12": "WS-GOAL-B-EPIC-12",
}
EXPECTED_INITIAL_GOAL_IDS = {
    EXPECTED_MASTER_ID,
    *EXPECTED_PHASE_IDS.values(),
    *EXPECTED_EPIC_GOAL_IDS.values(),
    INITIAL_WORK_ITEM_GOAL_ID,
}
EXPECTED_PACKAGE_STATUS_BY_ACTIVATION = {
    "READY_NOT_ACTIVATED": "PREPARED_NOT_ACTIVATED",
    "ACTIVE": "ACTIVE",
    "COMPLETE": "COMPLETE_AT_TARGET",
}
EXPECTED_STANDING_EXECUTION_AUTHORITY = [
    "REPOSITORY_SCOPED_IMPLEMENTATION",
    "INTERNAL_VERIFICATION",
    "DRAFT_AUTHORING",
    "ACTIVE_FACT_RECORDING",
    "SUCCESSOR_TRACE_GENERATION",
    "CHECKPOINT_DAYLOG_MEMORY_UPDATE",
    "DEPENDENCY_READY_NEXT_GOAL_START",
]
EXPECTED_EXTERNAL_ACTION_REQUIRED_FOR = [
    "NORMATIVE_POLICY_CHANGE",
    "APPROVED_OR_BASELINED_TRANSITION",
    "GATE_WAIVER",
    "FORMAL_TEST_PASS",
    "REAL_DEVICE_OR_PARTICIPANT_EXECUTION",
    "SECRET_PAID_OR_PRODUCTION_RESOURCE",
    "DESTRUCTIVE_EXTERNAL_ACTION",
    "INDEPENDENT_HUMAN_REVIEW",
    "RELEASE_ACCEPTANCE_HANDOVER_OR_CLOSURE_SIGNATURE",
]
EXPECTED_LEAF_SELECTION_POLICY = (
    "LATEST_BACKLOG_NEXT_SINGLE_ACTION_WITH_OPEN_INTERNAL_WORK"
)
EXPECTED_TRANSITION_COMMIT_POLICY = (
    "PREPARE_VALIDATE_THEN_ATOMIC_CHECKPOINT_SWITCH"
)
ALLOWED_CURRENT_LEAF_SOURCES = {"IMPLEMENTATION_BACKLOG", "PHASE_PLAN"}
EXPECTED_RELEASE_GATE_IDS = {
    "GATE-PHONE-QUEUE-BYTE-LIMIT",
    "GATE-SERVER-CAPACITY-STATE-CONTRACT",
    "GATE-RAW-COLLECTION-RELEASE-REVIEW",
    "GATE-CLOUD-COST-MEASUREMENT",
    "GATE-SINGLE-ADMIN-RECOVERY-DRILL",
}
REQUIRED_PHASE_B_EVIDENCE_STATUS = {
    "TEST_PLAN_APPROVAL_RECEIPT": "APPROVED",
    "FORMAL_TEST_REPORT": "PASS",
    "ACTUAL_DEVICE_TEST_REPORT": "PASS",
    "RELEASE_GATE_CLOSURE_RECEIPT": "CLOSED",
    "RELEASE_ELIGIBILITY_APPROVAL": "ELIGIBLE",
}
REQUIRED_PHASE_C_EVIDENCE_STATUS = {
    "PHASE_C_TECHNICAL_DELIVERY_RECEIPT": "DELIVERED",
}
REQUIRED_PHASE_D_EVIDENCE_STATUS = {
    "PHASE_D_OPERATION_HANDOVER_RECEIPT": "HANDED_OVER",
    "PHASE_D_PROJECT_CLOSURE_RECEIPT": "CLOSED",
}
PHASE_C_REQUIRED_CHECKS = {
    "candidate_identity",
    "artifact_signature",
    "sbom_provenance_license",
    "backup_migration_rollback",
    "canary",
    "smoke",
    "delivery_documents",
    "acceptance_record",
}
PHASE_D_HANDOVER_REQUIRED_CHECKS = {
    "service_owner",
    "access_transfer",
    "runbook",
    "monitoring",
    "backup_restore",
    "support_escalation",
}
PHASE_D_CLOSURE_REQUIRED_CHECKS = {
    "final_acceptance",
    "remaining_defects",
    "remaining_risks",
    "technical_debt",
    "data_disposition",
    "account_key_cleanup",
    "lessons_learned",
}
REOPEN_TRIGGER_EVIDENCE_TYPES = {
    "POLICY_CHANGE_APPROVAL",
    "REGRESSION_DEFECT_RECEIPT",
    "EVIDENCE_REJECTION_RECEIPT",
    "CANONICAL_INPUT_REVISION_RECEIPT",
    "GOAL_INSTRUCTION_CORRECTION_RECEIPT",
}
BLOCKER_RESOLUTION_EVIDENCE_TYPES = {
    "DECISION_RECEIPT",
    "EXTERNAL_EVIDENCE_RECEIPT",
    "BLOCKER_REMEDIATION_RECEIPT",
}
WORK_ITEM_EXECUTION_EVIDENCE_TYPE = "WORK_ITEM_EXECUTION_RECEIPT"
WORK_ITEM_RESULT_EVIDENCE_KINDS = {
    "IMPLEMENTATION_RECORD",
    "VERIFICATION_RESULT",
    "SUCCESSOR_TRACE",
}
ALLOWED_WORK_ITEM_TARGETS_BY_PHASE = {
    "A": {"INTERNAL_POLICY_CONFORMANCE_REASSESSED", "IMPLEMENTATION_READY"},
    "B": {"FORMAL_VERIFICATION_ACTION_COMPLETE"},
    "C": {"RELEASE_DELIVERY_ACTION_COMPLETE"},
    "D": {"HANDOVER_CLOSURE_ACTION_COMPLETE"},
}
USER_WAIT_CODES = {
    "POLICY_CHANGE_REQUIRED",
    "PRODUCT_DIRECTION_AMBIGUITY",
    "IRREVERSIBLE_EXTERNAL_ACTION",
    "SECRET_OR_PAID_RESOURCE_REQUIRED",
    "AUTHORITY_EXPANSION_REQUIRED",
}
EXTERNAL_WAIT_CODES = {
    "REAL_DEVICE_OR_PARTICIPANT_REQUIRED",
    "EXTERNAL_EVIDENCE_REQUIRED",
}
ALLOWED_TRANSITION_EVENT_TYPES = {
    "PACKAGE_PREPARED",
    "PACKAGE_ACTIVATED",
    "GOAL_COMPLETED",
    "GOAL_SUPERSEDED",
    "GOAL_TRANSITION",
    "GOAL_BYPASSED",
    "BLOCKER_RECORDED",
    "BLOCKER_RESOLVED",
    "GOAL_RESUMED",
    "PACKAGE_COMPLETED",
}
TRANSITION_POINTER_FIELDS = (
    "current_phase_goal_id",
    "current_epic_goal_id",
    "current_leaf_goal_id",
    "current_leaf_goal_path",
    "current_work_item_id",
    "current_leaf_source",
    "goal_status",
    "activation_status",
    "package_status",
)
EXPECTED_QUESTION_CODES = [
    "POLICY_CHANGE_REQUIRED",
    "PRODUCT_DIRECTION_AMBIGUITY",
    "IRREVERSIBLE_EXTERNAL_ACTION",
    "SECRET_OR_PAID_RESOURCE_REQUIRED",
    "REAL_DEVICE_OR_PARTICIPANT_REQUIRED",
    "AUTHORITY_EXPANSION_REQUIRED",
]
EXPECTED_STOP_CODES = [
    "CANONICAL_INPUT_VALIDATION_FAILED",
    "DEPENDENCY_NOT_COMPLETE",
    "POLICY_CONFLICT",
    "SAFETY_OR_SECURITY_CRITICAL_FAILURE",
    "USER_CHANGE_OVERLAP",
    "EXTERNAL_EVIDENCE_REQUIRED",
]
EXPECTED_HEADINGS = [
    "## 목표",
    "## 정본 입력",
    "## 범위와 제외",
    "## 단계별 실행",
    "## 검증",
    "## 완료 기준",
    "## 질문·중단 조건",
    "## 완료 후 인계",
]
ALLOWED_FIELDS = {
    "schema_version",
    "goal_id",
    "goal_kind",
    "document_version",
    "phase_id",
    "parent_goal_id",
    "sequence",
    "initial_status",
    "source_status_at_creation",
    "target_completion_level",
    "next_goal_id",
    "return_goal_id",
    "work_item_id",
    "dependencies",
    "child_goal_ids",
    "source_policy_ids",
    "gap_ids",
    "canonical_input_roles",
    "question_condition_codes",
    "stop_condition_codes",
    "question_policy",
    "stop_policy",
    "materialized_from_role",
    "materialized_from_path",
    "materialized_from_document_id",
    "materialized_from_sha256",
    "predecessor_goal_id",
    "predecessor_goal_content_sha256",
    "supersedes_goal_id",
    "supersedes_goal_content_sha256",
    "reopen_reason",
    "reopen_evidence_refs",
}
REQUIRED_FIELDS = {
    "schema_version",
    "goal_id",
    "goal_kind",
    "document_version",
    "phase_id",
    "parent_goal_id",
    "sequence",
    "initial_status",
    "target_completion_level",
    "next_goal_id",
    "return_goal_id",
    "work_item_id",
    "dependencies",
    "child_goal_ids",
    "source_policy_ids",
    "gap_ids",
    "canonical_input_roles",
}
ALLOWED_KINDS = {"MASTER", "PHASE", "EPIC", "WORK_ITEM"}
ALLOWED_INITIAL_STATUSES = {"PLANNED", "READY", "COMPLETE_AT_TARGET"}
ALLOWED_RUNTIME_STATUSES = {
    "PLANNED",
    "READY",
    "IN_PROGRESS",
    "AWAITING_USER",
    "AWAITING_EXTERNAL",
    "BLOCKED",
    "COMPLETE_AT_TARGET",
    "SUPERSEDED",
}
GOAL_ID_PATTERN = re.compile(r"^WS-GOAL-[A-Z0-9-]+$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        value = json.load(handle)
    if not isinstance(value, dict):
        raise ValueError(f"JSON root must be an object: {path}")
    return value


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def string_set_sha256(values: list[str]) -> str:
    encoded = json.dumps(
        sorted(values),
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def resolve_safe_repo_file(root: Path, relative: Any) -> Path | None:
    if not isinstance(relative, str) or not relative:
        return None
    relative_path = Path(relative)
    if relative_path.is_absolute() or ".." in relative_path.parts:
        return None
    try:
        resolved_root = root.resolve(strict=True)
        candidate = resolved_root
        for part in relative_path.parts:
            candidate /= part
            if candidate.is_symlink():
                return None
        resolved = candidate.resolve(strict=True)
    except (OSError, RuntimeError, ValueError):
        return None
    if not resolved.is_file() or not resolved.is_relative_to(resolved_root):
        return None
    return resolved


def path_and_content_hashes(root: Path, relative_paths: list[str]) -> tuple[str, str]:
    normalized = sorted(relative_paths)
    path_payload = "\n".join(normalized).encode("utf-8") + b"\n"
    path_digest = hashlib.sha256(path_payload).hexdigest()
    content_digest = hashlib.sha256()
    for relative in normalized:
        content_digest.update(relative.encode("utf-8"))
        content_digest.update(b"\0")
        safe_path = resolve_safe_repo_file(root, relative)
        if safe_path is None:
            raise ValueError(f"unsafe or missing managed path: {relative}")
        content_digest.update(sha256_file(safe_path).encode("ascii"))
        content_digest.update(b"\n")
    return path_digest, content_digest.hexdigest()


def discover_package_paths(root: Path) -> tuple[list[str], list[str]]:
    package_root = root / PACKAGE_RELATIVE
    all_paths = sorted(
        path.relative_to(root).as_posix()
        for path in package_root.rglob("*")
        if path.is_file()
        and (
            path.suffix == ".md"
            or path.relative_to(root) == STATIC_PLAN_MANIFEST_RELATIVE
        )
    )
    goal_paths = [
        path
        for path in all_paths
        if path.endswith(".md") and path not in SUPPORT_PATHS
    ]
    return all_paths, goal_paths


def validate_static_plan_manifest(
    root: Path,
    state: dict[str, Any],
    goal_path_by_id: dict[str, str],
) -> list[str]:
    errors: list[str] = []
    relative = STATIC_PLAN_MANIFEST_RELATIVE.as_posix()
    path = resolve_safe_repo_file(root, relative)
    if path is None:
        return ["static Goal plan manifest is missing or unsafe"]
    actual_manifest_hash = sha256_file(path)
    if EXPECTED_STATIC_PLAN_MANIFEST_SHA256 == "__STATIC_PLAN_MANIFEST_SHA256__":
        errors.append("static Goal plan manifest checker hash is not finalized")
    elif actual_manifest_hash != EXPECTED_STATIC_PLAN_MANIFEST_SHA256:
        errors.append("static Goal plan manifest differs from the checker trust anchor")
    if state.get("static_plan_manifest_path") != relative:
        errors.append("static Goal plan manifest path drifted")
    if state.get("static_plan_manifest_sha256") != actual_manifest_hash:
        errors.append("static Goal plan manifest SHA-256 differs from checkpoint")
    if state.get("static_plan_version") != EXPECTED_STATIC_PLAN_VERSION:
        errors.append("static Goal plan version drifted")
    if state.get("static_plan_locked") is not True:
        errors.append("static Goal plan must remain locked")
    try:
        manifest = load_json(path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [f"static Goal plan manifest cannot be loaded: {exc}"]
    if manifest.get("schema_version") != "1.0":
        errors.append("static Goal plan manifest schema version drifted")
    if manifest.get("manifest_id") != "WS-GOAL-STATIC-PLAN-MANIFEST-1.1.0":
        errors.append("static Goal plan manifest ID drifted")
    if manifest.get("package_id") != EXPECTED_PACKAGE_ID:
        errors.append("static Goal plan manifest package ID drifted")
    if manifest.get("plan_version") != EXPECTED_STATIC_PLAN_VERSION:
        errors.append("static Goal plan manifest version drifted")
    if manifest.get("supersedes_manifest_sha256") not in ("", None):
        errors.append("initial static Goal plan supersedes hash must be empty")

    protected = manifest.get("protected_files")
    if not isinstance(protected, list):
        return errors + ["static Goal plan protected_files must be a list"]
    protected_by_path: dict[str, str] = {}
    for index, item in enumerate(protected):
        if not isinstance(item, dict):
            errors.append(f"static Goal plan protected file {index} must be an object")
            continue
        protected_path = item.get("path")
        protected_hash = item.get("sha256")
        if not isinstance(protected_path, str) or not protected_path:
            errors.append(f"static Goal plan protected file {index} path is missing")
            continue
        if protected_path in protected_by_path:
            errors.append(f"static Goal plan duplicate protected path: {protected_path}")
            continue
        protected_by_path[protected_path] = protected_hash
        protected_file = resolve_safe_repo_file(root, protected_path)
        if protected_file is None:
            errors.append(f"static Goal plan protected path is unsafe or missing: {protected_path}")
            continue
        if not isinstance(protected_hash, str) or not SHA256_PATTERN.fullmatch(
            protected_hash
        ):
            errors.append(f"static Goal plan protected hash is invalid: {protected_path}")
        elif sha256_file(protected_file) != protected_hash:
            errors.append(f"static Goal plan protected content changed: {protected_path}")

    required_paths = {
        f"{PACKAGE_RELATIVE.as_posix()}/README.md",
        f"{PACKAGE_RELATIVE.as_posix()}/templates/work-item-template.md",
        goal_path_by_id.get(EXPECTED_MASTER_ID, ""),
        *(goal_path_by_id.get(goal_id, "") for goal_id in EXPECTED_PHASE_IDS.values()),
        *(
            goal_path_by_id.get(goal_id, "")
            for goal_id in EXPECTED_EPIC_GOAL_IDS.values()
        ),
        goal_path_by_id.get(INITIAL_WORK_ITEM_GOAL_ID, ""),
    }
    required_paths.discard("")
    if set(protected_by_path) != required_paths:
        errors.append("static Goal plan protected path set differs")
    return errors


def parse_goal(path: Path) -> tuple[dict[str, Any], str]:
    text = path.read_text(encoding="utf-8")
    if not text.startswith("+++\n"):
        raise ValueError("TOML front matter start delimiter is missing")
    closing = text.find("\n+++\n", 4)
    if closing < 0:
        raise ValueError("TOML front matter end delimiter is missing")
    if text.find("\n+++\n", closing + 5) >= 0:
        raise ValueError("multiple TOML front matter blocks are not allowed")
    front_matter = text[4:closing]
    metadata = tomllib.loads(front_matter)
    if not isinstance(metadata, dict):
        raise ValueError("TOML front matter must be a table")
    return metadata, text[closing + 5 :]


def canonical_binding_map(checkpoint: dict[str, Any]) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    bindings = checkpoint.get("canonical_bindings", [])
    if not isinstance(bindings, list):
        return result
    for binding in bindings:
        if isinstance(binding, dict) and isinstance(binding.get("role"), str):
            result[binding["role"]] = binding
    return result


def validate_canonical_bindings_shape(checkpoint: dict[str, Any]) -> list[str]:
    bindings = checkpoint.get("canonical_bindings")
    if not isinstance(bindings, list):
        return ["canonical_bindings must be a list"]
    errors: list[str] = []
    seen_roles: set[str] = set()
    seen_document_ids: set[str] = set()
    for index, binding in enumerate(bindings):
        label = f"canonical binding {index}"
        if not isinstance(binding, dict):
            errors.append(f"{label}: binding must be an object")
            continue
        role = binding.get("role")
        path = binding.get("path")
        document_id = binding.get("document_id")
        if not isinstance(role, str) or not role:
            errors.append(f"{label}: role must be a non-empty string")
        elif role in seen_roles:
            errors.append(f"duplicate canonical binding role: {role}")
        else:
            seen_roles.add(role)
        if not isinstance(path, str) or not path:
            errors.append(f"{label}: path must be a non-empty string")
        if not isinstance(document_id, str) or not document_id:
            errors.append(f"{label}: document_id must be a non-empty string")
        elif document_id in seen_document_ids:
            errors.append(f"duplicate canonical binding document_id: {document_id}")
        else:
            seen_document_ids.add(document_id)
        file_hash = binding.get("file_sha256")
        if file_hash is not None and (
            not isinstance(file_hash, str) or not SHA256_PATTERN.fullmatch(file_hash)
        ):
            errors.append(f"{label}: file_sha256 is invalid")
    return errors


def string_list(value: Any) -> list[str]:
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        return []
    return value


def goal_ancestor_chain(
    nodes: dict[str, dict[str, Any]],
    goal_id: str,
) -> list[str]:
    chain: list[str] = []
    seen: set[str] = set()
    cursor = goal_id
    while cursor and cursor in nodes and cursor not in seen:
        chain.append(cursor)
        seen.add(cursor)
        parent = nodes[cursor].get("parent_goal_id")
        cursor = parent if isinstance(parent, str) else ""
    return chain


def blocker_records_by_id(
    blockers_by_goal: dict[str, Any],
) -> tuple[dict[str, dict[str, Any]], set[str]]:
    records: dict[str, dict[str, Any]] = {}
    duplicate_ids: set[str] = set()
    for blocker_rows in blockers_by_goal.values():
        if not isinstance(blocker_rows, list):
            continue
        for blocker in blocker_rows:
            if not isinstance(blocker, dict):
                continue
            blocker_id = blocker.get("blocker_id")
            if not isinstance(blocker_id, str) or not blocker_id:
                continue
            if blocker_id in records:
                duplicate_ids.add(blocker_id)
            else:
                records[blocker_id] = blocker
    return records, duplicate_ids


def event_sha256(event: dict[str, Any]) -> str:
    payload = {key: value for key, value in event.items() if key != "event_sha256"}
    encoded = json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def blocker_snapshot_sha256(blocker: dict[str, Any]) -> str:
    payload = {
        key: value
        for key, value in blocker.items()
        if key != "blocker_snapshot_sha256"
    }
    encoded = json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def evidence_reference_targets(
    bindings: dict[str, dict[str, Any]],
) -> dict[str, dict[str, Any]]:
    targets: dict[str, dict[str, Any]] = {}
    for role, binding in bindings.items():
        path = binding.get("path")
        if not isinstance(path, str):
            continue
        targets[role] = binding
        document_id = binding.get("document_id")
        if isinstance(document_id, str) and document_id:
            targets[document_id] = binding
    return targets


def validate_evidence_references(
    label: str,
    references: Any,
    *,
    root: Path,
    bindings: dict[str, dict[str, Any]],
) -> list[str]:
    if not isinstance(references, list) or not references:
        return [f"{label}: completion evidence references are missing"]
    if any(not isinstance(item, str) or not item for item in references):
        return [f"{label}: completion evidence references must be non-empty strings"]
    targets = evidence_reference_targets(bindings)
    errors: list[str] = []
    for reference in references:
        binding = targets.get(reference)
        if binding is None:
            errors.append(f"{label}: unresolved evidence reference: {reference}")
            continue
        relative = binding.get("path")
        if not isinstance(relative, str):
            errors.append(f"{label}: evidence path is missing: {reference}")
            continue
        evidence_path = resolve_safe_repo_file(root, relative)
        if evidence_path is None:
            errors.append(f"{label}: unresolved evidence reference: {reference}")
            continue
        expected_hash = binding.get("file_sha256")
        if not isinstance(expected_hash, str) or not SHA256_PATTERN.fullmatch(
            expected_hash
        ):
            errors.append(f"{label}: evidence binding lacks file SHA-256: {reference}")
        elif sha256_file(evidence_path) != expected_hash:
            errors.append(f"{label}: evidence file SHA-256 differs: {reference}")
    return errors


def load_policy_gap_mapping(
    root: Path,
    bindings: dict[str, dict[str, Any]],
) -> tuple[list[str], dict[str, str]]:
    errors: list[str] = []
    binding = bindings.get("IMPLEMENTATION_GAP")
    if not isinstance(binding, dict):
        return ["IMPLEMENTATION_GAP canonical binding is missing"], {}
    gap_path = resolve_safe_repo_file(root, binding.get("path"))
    if gap_path is None:
        return ["IMPLEMENTATION_GAP path is unsafe or missing"], {}
    expected_hash = binding.get("file_sha256")
    if (
        not isinstance(expected_hash, str)
        or not SHA256_PATTERN.fullmatch(expected_hash)
        or sha256_file(gap_path) != expected_hash
    ):
        errors.append("IMPLEMENTATION_GAP binding SHA-256 differs")
    try:
        report = load_json(gap_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [f"IMPLEMENTATION_GAP cannot be loaded: {exc}"], {}
    assessments = report.get("assessments")
    if not isinstance(assessments, list):
        return errors + ["IMPLEMENTATION_GAP assessments must be a list"], {}
    mapping: dict[str, str] = {}
    used_gap_ids: set[str] = set()
    for index, assessment in enumerate(assessments):
        if not isinstance(assessment, dict):
            errors.append(
                f"IMPLEMENTATION_GAP assessment {index} must be an object"
            )
            continue
        policy_id = assessment.get("source_policy_id")
        gap_id = assessment.get("gap_id")
        if (
            not isinstance(policy_id, str)
            or not policy_id
            or not isinstance(gap_id, str)
            or not gap_id
        ):
            errors.append(
                f"IMPLEMENTATION_GAP assessment {index} policy/Gap pair is invalid"
            )
            continue
        if policy_id in mapping:
            errors.append(
                f"IMPLEMENTATION_GAP policy is duplicated: {policy_id}"
            )
            continue
        if gap_id in used_gap_ids:
            errors.append(f"IMPLEMENTATION_GAP Gap is duplicated: {gap_id}")
            continue
        mapping[policy_id] = gap_id
        used_gap_ids.add(gap_id)
    if len(mapping) != 68:
        errors.append(
            "IMPLEMENTATION_GAP must define exactly 68 unique policy/Gap pairs"
        )
    return errors, mapping


def validate_work_item_completion_receipt(
    *,
    label: str,
    goal_id: str,
    references: Any,
    root: Path,
    bindings: dict[str, dict[str, Any]],
    node: dict[str, Any],
    goal_path: str,
) -> list[str]:
    errors: list[str] = []
    expected_role = f"WORK_ITEM_COMPLETION::{goal_id}"
    if string_list(references).count(expected_role) != 1:
        return [
            f"{label}: exactly one typed Work Item execution receipt is required; "
            "the source plan alone is not completion evidence"
        ]
    binding = bindings.get(expected_role)
    if not isinstance(binding, dict):
        return [f"{label}: Work Item execution receipt binding is missing"]
    receipt_path = resolve_safe_repo_file(root, binding.get("path"))
    if receipt_path is None:
        return [f"{label}: Work Item execution receipt path is unsafe or missing"]
    try:
        receipt = load_json(receipt_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return [f"{label}: Work Item execution receipt cannot be loaded: {exc}"]
    if receipt.get("evidence_type") != WORK_ITEM_EXECUTION_EVIDENCE_TYPE:
        errors.append(f"{label}: execution receipt evidence_type differs")
    if receipt.get("document_id") != binding.get("document_id"):
        errors.append(f"{label}: execution receipt document ID differs")
    if receipt.get("status") != "ACCEPTED":
        errors.append(f"{label}: execution receipt status is not ACCEPTED")
    if receipt.get("result") != "PASS":
        errors.append(f"{label}: execution receipt result is not PASS")
    expected_goal_path = resolve_safe_repo_file(root, goal_path)
    expected_goal_hash = (
        sha256_file(expected_goal_path) if expected_goal_path is not None else None
    )
    for field, expected in (
        ("target_goal_id", goal_id),
        ("target_goal_content_sha256", expected_goal_hash),
        ("work_item_id", node.get("work_item_id")),
        ("source_policy_ids", string_list(node.get("source_policy_ids"))),
        ("gap_ids", string_list(node.get("gap_ids"))),
        ("target_completion_level", node.get("target_completion_level")),
    ):
        if receipt.get(field) != expected:
            errors.append(f"{label}: execution receipt {field} differs")
    completed_at = parse_iso_datetime(receipt.get("completed_at"))
    if completed_at is None:
        errors.append(f"{label}: execution receipt completed_at is invalid")
    generated_at = parse_iso_datetime(receipt.get("generated_at"))
    if generated_at is None:
        errors.append(f"{label}: execution receipt generated_at is invalid")
    if (
        not isinstance(receipt.get("execution_start_event_sha256"), str)
        or not SHA256_PATTERN.fullmatch(
            receipt.get("execution_start_event_sha256", "")
        )
    ):
        errors.append(
            f"{label}: execution receipt start event SHA-256 is invalid"
        )
    execution_window = receipt.get("execution_window")
    if (
        not isinstance(execution_window, dict)
        or not is_iso_datetime(execution_window.get("started_at"))
        or not is_iso_datetime(execution_window.get("ended_at"))
        or not is_within_execution_window(
            receipt.get("completed_at"),
            execution_window,
        )
    ):
        errors.append(f"{label}: execution receipt time window is invalid")
    errors.extend(
        f"{label}: {error}"
        for error in validate_receipt_actor(
            WORK_ITEM_EXECUTION_EVIDENCE_TYPE,
            "executor",
            receipt.get("executor"),
        )
    )
    errors.extend(
        f"{label}: {error}"
        for error in validate_receipt_actor(
            WORK_ITEM_EXECUTION_EVIDENCE_TYPE,
            "reviewer",
            receipt.get("reviewer"),
            require_decision=True,
        )
    )
    reviewer_decided_at = parse_iso_datetime(
        receipt.get("reviewer", {}).get("decided_at")
        if isinstance(receipt.get("reviewer"), dict)
        else None
    )
    execution_started_at = parse_iso_datetime(
        execution_window.get("started_at")
        if isinstance(execution_window, dict)
        else None
    )
    execution_ended_at = parse_iso_datetime(
        execution_window.get("ended_at")
        if isinstance(execution_window, dict)
        else None
    )
    if (
        execution_started_at is not None
        and completed_at is not None
        and execution_ended_at is not None
        and reviewer_decided_at is not None
        and generated_at is not None
        and not (
            execution_started_at
            <= completed_at
            <= execution_ended_at
            <= reviewer_decided_at
            <= generated_at
        )
    ):
        errors.append(
            f"{label}: execution receipt completion/review/generation "
            "chronology differs"
        )
    result_evidence = receipt.get("result_evidence")
    if not isinstance(result_evidence, list):
        return errors + [f"{label}: execution receipt result_evidence is missing"]
    kinds: list[str] = []
    paths: list[str] = []
    prohibited_paths = {
        goal_path,
        node.get("materialized_from_path"),
    }
    for index, item in enumerate(result_evidence):
        if not isinstance(item, dict):
            errors.append(f"{label}: result evidence {index} is malformed")
            continue
        kind = item.get("kind")
        relative = item.get("path")
        digest = item.get("sha256")
        if isinstance(kind, str):
            kinds.append(kind)
        if isinstance(relative, str):
            paths.append(relative)
        result_path = resolve_safe_repo_file(root, relative)
        if (
            kind not in WORK_ITEM_RESULT_EVIDENCE_KINDS
            or result_path is None
            or relative in prohibited_paths
            or not isinstance(digest, str)
            or not SHA256_PATTERN.fullmatch(digest)
            or sha256_file(result_path) != digest
        ):
            errors.append(f"{label}: result evidence {index} is invalid")
            continue
        try:
            result_payload = load_json(result_path)
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            errors.append(
                f"{label}: result evidence {index} cannot be loaded: {exc}"
            )
            continue
        observed_at = parse_iso_datetime(result_payload.get("observed_at"))
        if (
            result_payload.get("goal_id") != goal_id
            or result_payload.get("kind") != kind
            or result_payload.get("status") != "PASS"
            or observed_at is None
            or not is_within_execution_window(
                result_payload.get("observed_at"),
                execution_window,
            )
            or (
                completed_at is not None
                and observed_at > completed_at
            )
        ):
            errors.append(
                f"{label}: result evidence {index} typed payload differs"
            )
    if (
        set(kinds) != WORK_ITEM_RESULT_EVIDENCE_KINDS
        or len(kinds) != len(WORK_ITEM_RESULT_EVIDENCE_KINDS)
        or len(paths) != len(set(paths))
    ):
        errors.append(
            f"{label}: result evidence must contain exactly one implementation, "
            "verification, and successor record with distinct paths"
        )
    return errors


def validate_external_attestation_anchor(
    role: str,
    actual_sha256: str,
) -> list[str]:
    single_anchor = EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE.get(
        role
    )
    historical_anchors = (
        EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_HISTORY_BY_ROLE.get(
            role,
            (),
        )
    )
    anchors = {
        anchor
        for anchor in (single_anchor, *historical_anchors)
        if isinstance(anchor, str) and SHA256_PATTERN.fullmatch(anchor)
    }
    if not anchors:
        return [
            f"external authority attestation trust anchor is not finalized: "
            f"{role}"
        ]
    if actual_sha256 not in anchors:
        return [
            f"evidence differs from external authority attestation anchor: "
            f"{role}"
        ]
    return []


def validate_receipt_authority_roster_binding(
    root: Path,
    receipt: dict[str, Any],
    bindings: dict[str, dict[str, Any]],
) -> list[str]:
    roster_binding = bindings.get("AUTHORITY_ROSTER")
    if not isinstance(roster_binding, dict):
        return ["receipt authority roster binding is missing"]
    errors: list[str] = []
    for field, expected in (
        ("authority_roster_ref", "AUTHORITY_ROSTER"),
        (
            "authority_roster_document_id",
            roster_binding.get("document_id"),
        ),
        ("authority_roster_sha256", roster_binding.get("file_sha256")),
    ):
        if receipt.get(field) != expected:
            errors.append(f"receipt {field} differs")
    roster_path = resolve_safe_repo_file(root, roster_binding.get("path"))
    if roster_path is None:
        return errors + ["receipt authority roster path is unsafe or missing"]
    try:
        roster = load_json(roster_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [f"receipt authority roster cannot be loaded: {exc}"]
    roster_approved_at = parse_iso_datetime(roster.get("approved_at"))
    effective_times: list[datetime] = []
    execution_window = receipt.get("execution_window")
    if isinstance(execution_window, dict):
        started_at = parse_iso_datetime(execution_window.get("started_at"))
        if started_at is not None:
            effective_times.append(started_at)
    for field in ("decided_at", "generated_at"):
        timestamp = parse_iso_datetime(receipt.get(field))
        if timestamp is not None:
            effective_times.append(timestamp)
    for actor_name in (
        "executor",
        "reviewer",
        "approver",
        "resolver",
        "recipient",
        "transferor",
        "transferee",
        "project_owner",
        "accepting_owner",
    ):
        actor = receipt.get(actor_name)
        if isinstance(actor, dict):
            timestamp = parse_iso_datetime(actor.get("decided_at"))
            if timestamp is not None:
                effective_times.append(timestamp)
    if (
        roster_approved_at is not None
        and effective_times
        and roster_approved_at > min(effective_times)
    ):
        errors.append(
            "receipt authority roster approval postdates receipt authority use"
        )
    return errors


def validate_reopen_trigger_receipts(
    *,
    label: str,
    references: Any,
    root: Path,
    bindings: dict[str, dict[str, Any]],
    superseded_goal_id: str,
    work_item_id: str,
) -> list[str]:
    errors = validate_evidence_references(
        label,
        references,
        root=root,
        bindings=bindings,
    )
    roster_errors, authority_actors = load_authority_roster(root, bindings)
    errors.extend(f"{label}: {error}" for error in roster_errors)
    targets = evidence_reference_targets(bindings)
    for reference in string_list(references):
        binding = targets.get(reference, {})
        trigger_path = resolve_safe_repo_file(root, binding.get("path"))
        if trigger_path is None:
            continue
        errors.extend(
            f"{label}: {error}"
            for error in validate_external_attestation_anchor(
                reference,
                sha256_file(trigger_path),
            )
        )
        try:
            trigger = load_json(trigger_path)
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            errors.append(f"{label}: trigger receipt cannot be loaded: {exc}")
            continue
        if trigger.get("evidence_type") not in REOPEN_TRIGGER_EVIDENCE_TYPES:
            errors.append(f"{label}: unsupported reopen trigger evidence type")
        if trigger.get("document_id") != binding.get("document_id"):
            errors.append(f"{label}: trigger receipt document ID differs")
        errors.extend(
            f"{label}: {error}"
            for error in validate_receipt_authority_roster_binding(
                root,
                trigger,
                bindings,
            )
        )
        if trigger.get("status") not in {"APPROVED", "ACCEPTED", "CONFIRMED"}:
            errors.append(f"{label}: trigger receipt status is not effective")
        if trigger.get("target_goal_id") != superseded_goal_id:
            errors.append(f"{label}: trigger receipt target Goal differs")
        if trigger.get("work_item_id") != work_item_id:
            errors.append(f"{label}: trigger receipt work item differs")
        completion_event_hash = trigger.get(
            "target_completion_event_sha256"
        )
        if (
            not isinstance(completion_event_hash, str)
            or not SHA256_PATTERN.fullmatch(completion_event_hash)
        ):
            errors.append(
                f"{label}: target completion event SHA-256 is invalid"
            )
        if not is_iso_datetime(trigger.get("decided_at")):
            errors.append(f"{label}: trigger receipt decision time is invalid")
        approver = trigger.get("approver")
        if (
            isinstance(approver, dict)
            and approver.get("decided_at") != trigger.get("decided_at")
        ):
            errors.append(
                f"{label}: trigger approver decision time differs"
            )
        errors.extend(
            validate_receipt_actor(
                str(trigger.get("evidence_type", "REOPEN_TRIGGER")),
                "approver",
                approver,
                require_decision=True,
            )
        )
        errors.extend(
            validate_actor_against_roster(
                receipt_role=str(
                    trigger.get("evidence_type", "REOPEN_TRIGGER")
                ),
                actor_name="approver",
                actor=approver,
                actors_by_id=authority_actors,
            )
        )
    return errors


def validate_blocker_resolution_receipt(
    *,
    label: str,
    reference: Any,
    root: Path,
    bindings: dict[str, dict[str, Any]],
    blocker_id: Any,
    goal_id: Any,
    condition_code: Any,
    owner: Any,
    blocker_event_sha256: Any,
    blocker_snapshot_hash: Any,
) -> list[str]:
    references = [reference] if isinstance(reference, str) else []
    errors = validate_evidence_references(
        label,
        references,
        root=root,
        bindings=bindings,
    )
    target = evidence_reference_targets(bindings).get(reference, {})
    receipt_path = resolve_safe_repo_file(root, target.get("path"))
    if receipt_path is None:
        return errors
    errors.extend(
        f"{label}: {error}"
        for error in validate_external_attestation_anchor(
            str(reference),
            sha256_file(receipt_path),
        )
    )
    try:
        receipt = load_json(receipt_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [f"{label}: resolution receipt cannot be loaded: {exc}"]
    evidence_type = receipt.get("evidence_type")
    if evidence_type not in BLOCKER_RESOLUTION_EVIDENCE_TYPES:
        errors.append(f"{label}: unsupported blocker resolution evidence type")
    expected_type = {
        "USER": "DECISION_RECEIPT",
        "EXTERNAL": "EXTERNAL_EVIDENCE_RECEIPT",
        "BLOCKED": "BLOCKER_REMEDIATION_RECEIPT",
    }.get(owner)
    if evidence_type != expected_type:
        errors.append(f"{label}: resolution evidence type differs from blocker owner")
    if receipt.get("document_id") != target.get("document_id"):
        errors.append(f"{label}: resolution receipt document ID differs")
    errors.extend(
        f"{label}: {error}"
        for error in validate_receipt_authority_roster_binding(
            root,
            receipt,
            bindings,
        )
    )
    if receipt.get("status") != "RESOLVED":
        errors.append(f"{label}: resolution receipt status is not RESOLVED")
    for field, expected in (
        ("blocker_id", blocker_id),
        ("goal_id", goal_id),
        ("condition_code", condition_code),
        ("owner", owner),
        ("blocker_event_sha256", blocker_event_sha256),
        ("blocker_snapshot_sha256", blocker_snapshot_hash),
    ):
        if receipt.get(field) != expected:
            errors.append(f"{label}: resolution receipt {field} differs")
    if not is_iso_datetime(receipt.get("decided_at")):
        errors.append(f"{label}: resolution receipt decision time is invalid")
    resolver = receipt.get("resolver")
    if (
        isinstance(resolver, dict)
        and resolver.get("decided_at") != receipt.get("decided_at")
    ):
        errors.append(f"{label}: resolver decision time differs")
    errors.extend(
        validate_receipt_actor(
            str(evidence_type or "BLOCKER_RESOLUTION"),
            "resolver",
            resolver,
            require_decision=True,
        )
    )
    roster_errors, authority_actors = load_authority_roster(root, bindings)
    errors.extend(f"{label}: {error}" for error in roster_errors)
    errors.extend(
        validate_actor_against_roster(
            receipt_role=str(evidence_type or "BLOCKER_RESOLUTION"),
            actor_name="resolver",
            actor=resolver,
            actors_by_id=authority_actors,
        )
    )
    raw_evidence = receipt.get("raw_evidence")
    if not isinstance(raw_evidence, list) or not raw_evidence:
        errors.append(f"{label}: resolution receipt raw evidence is missing")
    else:
        for index, item in enumerate(raw_evidence):
            if not isinstance(item, dict):
                errors.append(f"{label}: raw evidence {index} is malformed")
                continue
            raw_path = resolve_safe_repo_file(root, item.get("path"))
            raw_hash = item.get("sha256")
            if (
                raw_path is None
                or not isinstance(raw_hash, str)
                or not SHA256_PATTERN.fullmatch(raw_hash)
                or sha256_file(raw_path) != raw_hash
            ):
                errors.append(f"{label}: raw evidence {index} is invalid")
    return errors


def parse_iso_datetime(value: Any) -> datetime | None:
    if not isinstance(value, str) or not re.fullmatch(
        r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:Z|[+-]\d{2}:\d{2})",
        value,
    ):
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


def parse_iso_date(value: Any) -> date | None:
    if not isinstance(value, str) or not re.fullmatch(
        r"\d{4}-\d{2}-\d{2}",
        value,
    ):
        return None
    try:
        return date.fromisoformat(value)
    except ValueError:
        return None


def is_iso_datetime(value: Any) -> bool:
    return parse_iso_datetime(value) is not None


def is_within_execution_window(value: Any, execution_window: Any) -> bool:
    if not isinstance(execution_window, dict):
        return False
    timestamp = parse_iso_datetime(value)
    started_at = parse_iso_datetime(execution_window.get("started_at"))
    ended_at = parse_iso_datetime(execution_window.get("ended_at"))
    return (
        timestamp is not None
        and started_at is not None
        and ended_at is not None
        and started_at <= timestamp <= ended_at
    )


def validate_receipt_actor(
    role: str,
    actor_name: str,
    actor: Any,
    *,
    require_decision: bool = False,
) -> list[str]:
    if not isinstance(actor, dict):
        return [f"evidence {actor_name} is missing: {role}"]
    errors: list[str] = []
    for field in ("id", "role", "authority"):
        if not isinstance(actor.get(field), str) or not actor.get(field):
            errors.append(f"evidence {actor_name} {field} is missing: {role}")
    if require_decision:
        if actor.get("decision") != "APPROVED":
            errors.append(f"evidence {actor_name} decision is not APPROVED: {role}")
        if not is_iso_datetime(actor.get("decided_at")):
            errors.append(f"evidence {actor_name} decided_at is invalid: {role}")
    return errors


def load_authority_roster(
    root: Path,
    bindings: dict[str, dict[str, Any]],
) -> tuple[list[str], dict[str, dict[str, Any]]]:
    role = "AUTHORITY_ROSTER"
    errors: list[str] = []
    binding = bindings.get(role)
    if not isinstance(binding, dict):
        return ["authority roster canonical binding is missing"], {}
    roster_path = resolve_safe_repo_file(root, binding.get("path"))
    if roster_path is None:
        return ["authority roster path is unsafe or missing"], {}
    actual_hash = sha256_file(roster_path)
    if binding.get("file_sha256") != actual_hash:
        errors.append("authority roster binding SHA-256 differs")
    authority_roster_anchors = {
        anchor
        for anchor in (
            EXPECTED_AUTHORITY_ROSTER_SHA256,
            *EXPECTED_AUTHORITY_ROSTER_SHA256_HISTORY,
        )
        if isinstance(anchor, str) and SHA256_PATTERN.fullmatch(anchor)
    }
    if not authority_roster_anchors:
        errors.append("authority roster checker trust anchor is not finalized")
    elif actual_hash not in authority_roster_anchors:
        errors.append("authority roster differs from checker trust anchor")
    try:
        roster = load_json(roster_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [f"authority roster cannot be loaded: {exc}"], {}
    if roster.get("schema_version") != "1.0":
        errors.append("authority roster schema version differs")
    if roster.get("document_id") != binding.get("document_id"):
        errors.append("authority roster document ID differs")
    if roster.get("status") != "APPROVED":
        errors.append("authority roster is not approved")
    if not is_iso_datetime(roster.get("approved_at")):
        errors.append("authority roster approval time is invalid")
    actors = roster.get("actors")
    actors_by_id: dict[str, dict[str, Any]] = {}
    if not isinstance(actors, list) or not actors:
        errors.append("authority roster actor list is missing")
    else:
        for index, actor in enumerate(actors):
            if not isinstance(actor, dict):
                errors.append(f"authority roster actor {index} is malformed")
                continue
            actor_id = actor.get("id")
            if not isinstance(actor_id, str) or not actor_id:
                errors.append(f"authority roster actor {index} ID is missing")
            elif actor_id in actors_by_id:
                errors.append(f"authority roster actor ID is duplicated: {actor_id}")
            else:
                actors_by_id[actor_id] = actor
            for field in (
                "allowed_roles",
                "allowed_authorities",
                "permitted_evidence_types",
            ):
                if not string_list(actor.get(field)):
                    errors.append(
                        f"authority roster actor {actor_id} {field} is missing"
                    )
            if not isinstance(actor.get("independence_group"), str) or not actor.get(
                "independence_group"
            ):
                errors.append(
                    f"authority roster actor {actor_id} independence_group is missing"
                )
    return errors, actors_by_id


def validate_actor_against_roster(
    *,
    receipt_role: str,
    actor_name: str,
    actor: Any,
    actors_by_id: dict[str, dict[str, Any]],
) -> list[str]:
    if not isinstance(actor, dict):
        return []
    roster_actor = actors_by_id.get(actor.get("id"))
    if not isinstance(roster_actor, dict):
        return [f"{receipt_role}: {actor_name} is not in the authority roster"]
    errors: list[str] = []
    if actor.get("role") not in string_list(roster_actor.get("allowed_roles")):
        errors.append(f"{receipt_role}: {actor_name} role is not authorized")
    if actor.get("authority") not in string_list(
        roster_actor.get("allowed_authorities")
    ):
        errors.append(f"{receipt_role}: {actor_name} authority is not authorized")
    if receipt_role not in string_list(
        roster_actor.get("permitted_evidence_types")
    ):
        errors.append(f"{receipt_role}: {actor_name} cannot attest this evidence type")
    if actor.get("authority_evidence_ref") != "AUTHORITY_ROSTER":
        errors.append(f"{receipt_role}: {actor_name} authority evidence ref differs")
    return errors


def validate_receipt_common(
    root: Path,
    bindings: dict[str, dict[str, Any]],
    *,
    role: str,
    expected_status: str,
    allowed_source_goal_ids: set[str],
    goal_path_by_id: dict[str, str],
    authority_actors: dict[str, dict[str, Any]],
) -> tuple[list[str], dict[str, Any]]:
    errors: list[str] = []
    binding = bindings.get(role)
    if not isinstance(binding, dict) or not isinstance(binding.get("path"), str):
        return [f"required canonical evidence binding is missing: {role}"], {}
    receipt_path = resolve_safe_repo_file(root, binding["path"])
    if receipt_path is None:
        return [f"evidence path is unsafe or missing: {role}"], {}
    actual_receipt_hash = sha256_file(receipt_path)
    if binding.get("file_sha256") != actual_receipt_hash:
        errors.append(f"evidence binding file SHA-256 differs: {role}")
    errors.extend(
        validate_external_attestation_anchor(
            role,
            actual_receipt_hash,
        )
    )
    try:
        receipt = load_json(receipt_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [f"evidence cannot be loaded ({role}): {exc}"], {}
    if receipt.get("schema_version") != "1.0":
        errors.append(f"evidence schema version differs: {role}")
    if receipt.get("evidence_type") != role:
        errors.append(f"evidence type differs: {role}")
    if receipt.get("document_id") != binding.get("document_id"):
        errors.append(f"evidence document ID differs from canonical binding: {role}")
    errors.extend(
        f"{error}: {role}"
        for error in validate_receipt_authority_roster_binding(
            root,
            receipt,
            bindings,
        )
    )
    if receipt.get("status") != expected_status:
        errors.append(f"evidence status differs: {role}")
    candidate_hash = receipt.get("candidate_sha256")
    if not isinstance(candidate_hash, str) or not SHA256_PATTERN.fullmatch(
        candidate_hash
    ):
        errors.append(f"evidence candidate SHA-256 is invalid: {role}")
    if not is_iso_datetime(receipt.get("generated_at")):
        errors.append(f"evidence generated_at is invalid: {role}")
    errors.extend(validate_receipt_actor(role, "executor", receipt.get("executor")))
    errors.extend(
        validate_receipt_actor(
            role,
            "reviewer",
            receipt.get("reviewer"),
            require_decision=True,
        )
    )
    errors.extend(
        validate_receipt_actor(
            role,
            "approver",
            receipt.get("approver"),
            require_decision=True,
        )
    )
    actors = [
        receipt.get(name, {}).get("id")
        for name in ("executor", "reviewer", "approver")
        if isinstance(receipt.get(name), dict)
    ]
    if len(actors) == 3 and len(set(actors)) != 3:
        errors.append(f"evidence executor, reviewer, and approver must differ: {role}")
    independence_groups: list[str] = []
    for actor_name in ("executor", "reviewer", "approver"):
        actor = receipt.get(actor_name)
        errors.extend(
            validate_actor_against_roster(
                receipt_role=role,
                actor_name=actor_name,
                actor=actor,
                actors_by_id=authority_actors,
            )
        )
        if isinstance(actor, dict):
            roster_actor = authority_actors.get(actor.get("id"), {})
            group = roster_actor.get("independence_group")
            if isinstance(group, str):
                independence_groups.append(group)
    if len(independence_groups) == 3 and len(set(independence_groups)) != 3:
        errors.append(
            f"evidence executor, reviewer, and approver independence groups must differ: {role}"
        )
    execution_window = receipt.get("execution_window")
    if (
        not isinstance(execution_window, dict)
        or not is_iso_datetime(execution_window.get("started_at"))
        or not is_iso_datetime(execution_window.get("ended_at"))
    ):
        errors.append(f"evidence execution window is invalid: {role}")
    else:
        timeline = [
            parse_iso_datetime(execution_window["started_at"]),
            parse_iso_datetime(execution_window["ended_at"]),
            parse_iso_datetime(receipt.get("reviewer", {}).get("decided_at"))
            if isinstance(receipt.get("reviewer"), dict)
            else None,
            parse_iso_datetime(receipt.get("approver", {}).get("decided_at"))
            if isinstance(receipt.get("approver"), dict)
            else None,
            parse_iso_datetime(receipt.get("generated_at")),
        ]
        if all(item is not None for item in timeline) and timeline != sorted(timeline):
            errors.append(f"evidence execution/review/approval chronology differs: {role}")
    environment_ids = receipt.get("environment_ids")
    if (
        not isinstance(environment_ids, list)
        or not environment_ids
        or any(not isinstance(item, str) or not item for item in environment_ids)
        or len(environment_ids) != len(set(environment_ids))
    ):
        errors.append(f"evidence environment_ids are invalid: {role}")
    tool_versions = receipt.get("tool_versions")
    if (
        not isinstance(tool_versions, dict)
        or not tool_versions
        or any(
            not isinstance(name, str)
            or not name
            or not isinstance(version, str)
            or not version
            for name, version in tool_versions.items()
        )
    ):
        errors.append(f"evidence tool_versions are invalid: {role}")
    for refs_field in ("defect_refs", "residual_risk_refs"):
        refs = receipt.get(refs_field)
        if not isinstance(refs, list) or any(
            not isinstance(item, str) or not item for item in refs
        ):
            errors.append(f"evidence {refs_field} must be a string list: {role}")

    candidate_manifest = receipt.get("candidate_manifest")
    if not isinstance(candidate_manifest, dict):
        errors.append(f"evidence candidate manifest is missing: {role}")
    else:
        manifest_path = candidate_manifest.get("path")
        manifest_hash = candidate_manifest.get("sha256")
        candidate_manifest_path = resolve_safe_repo_file(root, manifest_path)
        if candidate_manifest_path is None:
            errors.append(f"evidence candidate manifest path is unsafe or missing: {role}")
        elif (
            not isinstance(manifest_hash, str)
            or not SHA256_PATTERN.fullmatch(manifest_hash)
            or sha256_file(candidate_manifest_path) != manifest_hash
            or manifest_hash != candidate_hash
        ):
            errors.append(f"evidence candidate manifest SHA-256 differs: {role}")
        component_hashes = candidate_manifest.get("component_hashes")
        if (
            not isinstance(component_hashes, dict)
            or not component_hashes
            or any(
                not isinstance(name, str)
                or not name
                or not isinstance(component_hash, str)
                or not SHA256_PATTERN.fullmatch(component_hash)
                for name, component_hash in component_hashes.items()
            )
        ):
            errors.append(f"evidence candidate component hashes are invalid: {role}")
        if candidate_manifest_path is not None:
            try:
                candidate_manifest_payload = load_json(candidate_manifest_path)
            except (OSError, ValueError, json.JSONDecodeError) as exc:
                errors.append(f"evidence candidate manifest cannot be loaded ({role}): {exc}")
            else:
                components = candidate_manifest_payload.get("components")
                actual_components: dict[str, str] = {}
                seen_component_paths: set[str] = set()
                if not isinstance(components, list) or not components:
                    errors.append(f"evidence candidate manifest components are missing: {role}")
                else:
                    for index, component in enumerate(components):
                        if not isinstance(component, dict):
                            errors.append(
                                f"evidence candidate component {index} is malformed: {role}"
                            )
                            continue
                        component_name = component.get("name")
                        component_relative = component.get("path")
                        component_path = resolve_safe_repo_file(
                            root,
                            component_relative,
                        )
                        component_hash = component.get("sha256")
                        if (
                            not isinstance(component_name, str)
                            or not component_name
                            or component_name in actual_components
                            or not isinstance(component_relative, str)
                            or component_relative in seen_component_paths
                            or component_path is None
                            or not isinstance(component_hash, str)
                            or not SHA256_PATTERN.fullmatch(component_hash)
                            or sha256_file(component_path) != component_hash
                        ):
                            errors.append(
                                f"evidence candidate component {index} is invalid: {role}"
                            )
                            continue
                        actual_components[component_name] = component_hash
                        seen_component_paths.add(component_relative)
                    if isinstance(component_hashes, dict) and (
                        actual_components != component_hashes
                    ):
                        errors.append(
                            f"evidence candidate manifest component set differs: {role}"
                        )
    source_goal_id = receipt.get("source_goal_id")
    if source_goal_id not in allowed_source_goal_ids:
        errors.append(f"evidence source Goal is invalid: {role}")
    source_path = goal_path_by_id.get(source_goal_id)
    source_goal_path = resolve_safe_repo_file(root, source_path)
    if source_goal_path is None:
        errors.append(f"evidence source Goal file is missing: {role}")
    elif receipt.get("source_goal_sha256") != sha256_file(source_goal_path):
        errors.append(f"evidence source Goal SHA-256 differs: {role}")
    raw_evidence = receipt.get("raw_evidence")
    if not isinstance(raw_evidence, list) or not raw_evidence:
        errors.append(f"evidence raw_evidence is missing: {role}")
    else:
        seen_paths: set[str] = set()
        for index, item in enumerate(raw_evidence):
            label = f"{role} raw_evidence {index}"
            if not isinstance(item, dict):
                errors.append(f"{label}: entry must be an object")
                continue
            raw_path = item.get("path")
            raw_hash = item.get("sha256")
            if not isinstance(raw_path, str) or not raw_path:
                errors.append(f"{label}: path is missing")
                continue
            raw_file = resolve_safe_repo_file(root, raw_path)
            if raw_file is None:
                errors.append(f"{label}: path is unsafe or missing")
                continue
            if raw_path in seen_paths:
                errors.append(f"{label}: duplicate raw evidence path")
            seen_paths.add(raw_path)
            if not isinstance(raw_hash, str) or not SHA256_PATTERN.fullmatch(raw_hash):
                errors.append(f"{label}: SHA-256 is invalid")
            elif sha256_file(raw_file) != raw_hash:
                errors.append(f"{label}: SHA-256 differs")
            record_count = item.get("record_count")
            if not isinstance(record_count, int) or isinstance(record_count, bool) or record_count < 1:
                errors.append(f"{label}: record_count is invalid")
            if not isinstance(item.get("media_type"), str) or not item.get("media_type"):
                errors.append(f"{label}: media_type is missing")
            if not is_iso_datetime(item.get("collected_at")):
                errors.append(f"{label}: collected_at is invalid")
            errors.extend(
                validate_receipt_actor(
                    role,
                    f"raw_evidence[{index}] collector",
                    item.get("collector"),
                )
            )
            errors.extend(
                validate_actor_against_roster(
                    receipt_role=role,
                    actor_name=f"raw_evidence[{index}] collector",
                    actor=item.get("collector"),
                    actors_by_id=authority_actors,
                )
            )
            collected_at = parse_iso_datetime(item.get("collected_at"))
            started_at = (
                parse_iso_datetime(execution_window.get("started_at"))
                if isinstance(execution_window, dict)
                else None
            )
            ended_at = (
                parse_iso_datetime(execution_window.get("ended_at"))
                if isinstance(execution_window, dict)
                else None
            )
            if (
                collected_at is not None
                and started_at is not None
                and ended_at is not None
                and not started_at <= collected_at <= ended_at
            ):
                errors.append(f"{label}: collected_at is outside execution window")
    return errors, receipt


def validate_required_check_map(
    role: str,
    value: Any,
    required_keys: set[str],
    *,
    allowed_evidence_refs: set[str],
    summary: Any,
    expected_actor_ids: dict[str, Any],
    execution_window: Any,
) -> list[str]:
    if not isinstance(value, dict):
        return [f"{role}: required_checks must be an object"]
    errors: list[str] = []
    if set(value) != required_keys:
        errors.append(f"{role}: required_checks key set differs")
    for check_name, result in value.items():
        if not isinstance(result, dict):
            errors.append(f"{role}: required check {check_name} must be an object")
            continue
        if result.get("status") != "PASS":
            errors.append(f"{role}: required check {check_name} must be PASS")
        if not is_iso_datetime(result.get("executed_at")):
            errors.append(f"{role}: required check {check_name} time is invalid")
        elif not is_within_execution_window(
            result.get("executed_at"),
            execution_window,
        ):
            errors.append(
                f"{role}: required check {check_name} time is outside "
                "the execution window"
            )
        for actor_name, actor_id in expected_actor_ids.items():
            if result.get(f"{actor_name}_id") != actor_id:
                errors.append(
                    f"{role}: required check {check_name} {actor_name} differs"
                )
        evidence_refs = result.get("evidence_refs")
        if not isinstance(evidence_refs, list) or not evidence_refs or any(
            not isinstance(item, str) or not item for item in evidence_refs
        ):
            errors.append(
                f"{role}: required check {check_name} evidence_refs are invalid"
            )
        elif not set(evidence_refs).issubset(allowed_evidence_refs):
            errors.append(
                f"{role}: required check {check_name} evidence_refs are not raw evidence"
            )
    if (
        not isinstance(summary, dict)
        or summary.get("total") != len(required_keys)
        or summary.get("passed") != len(required_keys)
        or summary.get("failed") != 0
        or summary.get("not_run") != 0
    ):
        errors.append(f"{role}: required check summary is inconsistent")
    return errors


def raw_evidence_record_count(receipt: dict[str, Any]) -> int:
    return sum(
        item.get("record_count", 0)
        for item in receipt.get("raw_evidence", [])
        if isinstance(item, dict)
        and isinstance(item.get("record_count"), int)
        and not isinstance(item.get("record_count"), bool)
        and item.get("record_count", 0) > 0
    )


def validate_phase_b_evidence_receipts(
    root: Path,
    bindings: dict[str, dict[str, Any]],
    references: Any,
    goal_path_by_id: dict[str, str],
    allowed_source_goal_ids: set[str] | None = None,
) -> list[str]:
    errors: list[str] = []
    roster_errors, authority_actors = load_authority_roster(root, bindings)
    errors.extend(f"completed Phase B {error}" for error in roster_errors)
    planned_case_binding = bindings.get("PLANNED_TEST_CASES", {})
    planned_case_path = resolve_safe_repo_file(
        root,
        planned_case_binding.get("path"),
    )
    planned_cases_by_id: dict[str, dict[str, Any]] = {}
    planned_case_register: dict[str, Any] = {}
    if (
        planned_case_path is None
        or planned_case_binding.get("file_sha256") != sha256_file(planned_case_path)
    ):
        errors.append("completed Phase B planned test case binding is invalid")
    else:
        try:
            planned_case_register = load_json(planned_case_path)
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            errors.append(f"completed Phase B planned test cases cannot be loaded: {exc}")
        else:
            planned_case_rows = planned_case_register.get("test_cases")
            metadata = planned_case_register.get("metadata")
            if not isinstance(metadata, dict) or metadata.get(
                "approval_status"
            ) not in {"APPROVED", "BASELINED", "APPROVED_BASELINED"}:
                errors.append("completed Phase B planned test cases are not approved")
            if not isinstance(metadata, dict) or not is_iso_datetime(
                metadata.get("approved_at")
            ):
                errors.append(
                    "completed Phase B planned test case approval time is invalid"
                )
            if not isinstance(planned_case_rows, list) or len(planned_case_rows) != 279:
                errors.append("completed Phase B planned test case inventory differs from 279")
            else:
                for row in planned_case_rows:
                    if not isinstance(row, dict):
                        errors.append("completed Phase B planned test case row is malformed")
                        continue
                    test_id = row.get("test_case_id")
                    if not isinstance(test_id, str) or not test_id:
                        errors.append("completed Phase B planned test case ID is missing")
                    elif test_id in planned_cases_by_id:
                        errors.append(
                            f"completed Phase B planned test case ID is duplicated: {test_id}"
                        )
                    else:
                        planned_cases_by_id[test_id] = row
    reference_set = set(string_list(references))
    required_roles = set(REQUIRED_PHASE_B_EVIDENCE_STATUS)
    if not required_roles.issubset(reference_set):
        errors.append("completed Phase B lacks required evidence receipt roles")
    receipts: dict[str, dict[str, Any]] = {}
    candidate_hashes: set[str] = set()
    for role, expected_status in REQUIRED_PHASE_B_EVIDENCE_STATUS.items():
        effective_source_goal_ids = (
            allowed_source_goal_ids
            if allowed_source_goal_ids is not None
            else {
                EXPECTED_PHASE_IDS["B"],
                EXPECTED_EPIC_GOAL_IDS["EPIC-12"],
            }
        )
        receipt_errors, receipt = validate_receipt_common(
            root,
            bindings,
            role=role,
            expected_status=expected_status,
            allowed_source_goal_ids=effective_source_goal_ids,
            goal_path_by_id=goal_path_by_id,
            authority_actors=authority_actors,
        )
        errors.extend(f"completed Phase B {error}" for error in receipt_errors)
        receipts[role] = receipt
        candidate_hash = receipt.get("candidate_sha256")
        if isinstance(candidate_hash, str) and SHA256_PATTERN.fullmatch(candidate_hash):
            candidate_hashes.add(candidate_hash)

    formal = receipts.get("FORMAL_TEST_REPORT", {})
    planned_binding_snapshot = {
        "role": "PLANNED_TEST_CASES",
        "document_id": planned_case_binding.get("document_id"),
        "path": planned_case_binding.get("path"),
        "file_sha256": planned_case_binding.get("file_sha256"),
        "version": (
            planned_case_register.get("metadata", {}).get("version")
            if isinstance(planned_case_register, dict)
            else None
        ),
        "test_case_id_set_sha256": string_set_sha256(
            list(planned_cases_by_id)
        ),
    }
    if formal.get("planned_test_cases_binding") != planned_binding_snapshot:
        errors.append(
            "completed Phase B formal report planned test case binding differs"
        )
    plan_approval = receipts.get("TEST_PLAN_APPROVAL_RECEIPT", {})
    if (
        plan_approval.get("planned_test_cases_binding")
        != planned_binding_snapshot
    ):
        errors.append(
            "completed Phase B test plan approval receipt binding differs"
        )
    plan_approved_at = parse_iso_datetime(
        plan_approval.get("plan_approved_at")
    )
    planned_metadata_approved_at = parse_iso_datetime(
        planned_case_register.get("metadata", {}).get("approved_at")
        if isinstance(planned_case_register, dict)
        else None
    )
    plan_receipt_generated_at = parse_iso_datetime(
        plan_approval.get("generated_at")
    )
    plan_approver_decided_at = parse_iso_datetime(
        plan_approval.get("approver", {}).get("decided_at")
        if isinstance(plan_approval.get("approver"), dict)
        else None
    )
    if (
        plan_approved_at is None
        or planned_metadata_approved_at is None
        or plan_approver_decided_at is None
        or plan_approved_at != planned_metadata_approved_at
        or plan_approved_at != plan_approver_decided_at
    ):
        errors.append(
            "completed Phase B test plan approval time/binding chronology differs"
        )
    for execution_role in (
        "FORMAL_TEST_REPORT",
        "ACTUAL_DEVICE_TEST_REPORT",
        "RELEASE_GATE_CLOSURE_RECEIPT",
    ):
        execution_receipt = receipts.get(execution_role, {})
        execution_started_at = parse_iso_datetime(
            execution_receipt.get("execution_window", {}).get(
                "started_at"
            )
            if isinstance(
                execution_receipt.get("execution_window"),
                dict,
            )
            else None
        )
        if (
            execution_started_at is not None
            and plan_approved_at is not None
            and plan_receipt_generated_at is not None
            and (
                plan_approved_at > execution_started_at
                or plan_receipt_generated_at > execution_started_at
            )
        ):
            errors.append(
                "completed Phase B execution predates immutable test plan "
                f"approval: {execution_role}"
            )
    summary = formal.get("test_summary")
    if not isinstance(summary, dict):
        errors.append("completed Phase B formal test summary is missing")
    else:
        total = summary.get("total")
        passed = summary.get("passed")
        failed = summary.get("failed")
        not_run = summary.get("not_run")
        not_applicable = summary.get("not_applicable", 0)
        if (
            total != 279
            or not isinstance(passed, int)
            or isinstance(passed, bool)
            or passed < 1
            or not isinstance(not_applicable, int)
            or isinstance(not_applicable, bool)
            or passed + not_applicable != 279
            or not isinstance(failed, int)
            or isinstance(failed, bool)
            or failed != 0
            or not isinstance(not_run, int)
            or isinstance(not_run, bool)
            or not_run != 0
        ):
            errors.append("completed Phase B formal test summary is inconsistent")
        if summary.get("p0_open", 0) != 0 or summary.get("p1_open", 0) != 0:
            errors.append("completed Phase B formal report has open P0/P1 defects")
    test_cases = formal.get("test_cases")
    formal_raw_paths = {
        item.get("path")
        for item in formal.get("raw_evidence", [])
        if isinstance(item, dict) and isinstance(item.get("path"), str)
    }
    candidate_hash = formal.get("candidate_sha256")
    executor_id = (
        formal.get("executor", {}).get("id")
        if isinstance(formal.get("executor"), dict)
        else None
    )
    formal_approver_id = (
        formal.get("approver", {}).get("id")
        if isinstance(formal.get("approver"), dict)
        else None
    )
    if not isinstance(test_cases, list) or len(test_cases) != 279:
        errors.append("completed Phase B formal report must contain exactly 279 test cases")
    else:
        seen_test_ids: set[str] = set()
        case_counts = Counter()
        for index, case in enumerate(test_cases):
            label = f"completed Phase B test case {index}"
            if not isinstance(case, dict):
                errors.append(f"{label} is malformed")
                continue
            test_id = case.get("test_case_id")
            if not isinstance(test_id, str) or not test_id:
                errors.append(f"{label} lacks test_case_id")
            elif test_id in seen_test_ids:
                errors.append(f"{label} duplicates test_case_id: {test_id}")
            else:
                seen_test_ids.add(test_id)
            applicability = case.get("applicability")
            status = case.get("status")
            if applicability == "APPLICABLE":
                if status != "PASS":
                    errors.append(f"{label} applicable status is not PASS")
            elif applicability == "NOT_APPLICABLE":
                if status != "NOT_APPLICABLE":
                    errors.append(f"{label} non-applicable status differs")
                if not isinstance(case.get("applicability_reason"), str) or not case.get(
                    "applicability_reason"
                ):
                    errors.append(f"{label} lacks applicability reason")
            else:
                errors.append(f"{label} applicability is invalid")
            if isinstance(status, str):
                case_counts[status] += 1
            if case.get("candidate_sha256") != candidate_hash:
                errors.append(f"{label} candidate SHA-256 differs")
            environment_id = case.get("environment_id")
            if not isinstance(environment_id, str) or not environment_id:
                errors.append(f"{label} environment_id is missing")
            planned_case = planned_cases_by_id.get(test_id, {})
            if not planned_case:
                errors.append(f"{label} is not in the approved test case inventory")
            elif environment_id not in string_list(
                planned_case.get("eligible_environment_ids")
            ):
                errors.append(f"{label} environment is not eligible")
            required_evidence_types = set(
                string_list(planned_case.get("required_evidence_types"))
            )
            if set(string_list(case.get("evidence_types"))) != required_evidence_types:
                errors.append(f"{label} evidence type set differs from plan")
            if case.get("executor_id") != executor_id:
                errors.append(f"{label} executor differs from report executor")
            case_refs = string_list(case.get("raw_evidence_refs"))
            if not case_refs or not set(case_refs).issubset(formal_raw_paths):
                errors.append(f"{label} raw evidence refs are invalid")
            if applicability == "NOT_APPLICABLE":
                decision = case.get("applicability_decision")
                if (
                    not isinstance(decision, dict)
                    or decision.get("decision") != "APPROVED_NOT_APPLICABLE"
                    or decision.get("approver_id") != formal_approver_id
                    or not is_iso_datetime(decision.get("decided_at"))
                ):
                    errors.append(f"{label} lacks approved applicability decision")
                elif isinstance(formal.get("execution_window"), dict):
                    decision_at = parse_iso_datetime(
                        decision.get("decided_at")
                    )
                    report_started_at = parse_iso_datetime(
                        formal["execution_window"].get("started_at")
                    )
                    report_approved_at = parse_iso_datetime(
                        formal.get("approver", {}).get("decided_at")
                        if isinstance(formal.get("approver"), dict)
                        else None
                    )
                    report_generated_at = parse_iso_datetime(
                        formal.get("generated_at")
                    )
                    if (
                        decision_at is not None
                        and report_started_at is not None
                        and report_approved_at is not None
                        and report_generated_at is not None
                        and not (
                            report_started_at
                            <= decision_at
                            <= report_approved_at
                            <= report_generated_at
                        )
                    ):
                        errors.append(
                            f"{label} applicability decision chronology differs"
                        )
        if isinstance(summary, dict):
            if case_counts["PASS"] != summary.get("passed"):
                errors.append("completed Phase B PASS case count differs from summary")
            if case_counts["NOT_APPLICABLE"] != summary.get(
                "not_applicable", 0
            ):
                errors.append(
                    "completed Phase B NOT_APPLICABLE case count differs from summary"
                )
        if set(seen_test_ids) != set(planned_cases_by_id):
            errors.append("completed Phase B executed test case ID set differs from plan")
    if raw_evidence_record_count(formal) < 279:
        errors.append("completed Phase B formal raw evidence count is below 279")

    actual_device = receipts.get("ACTUAL_DEVICE_TEST_REPORT", {})
    if actual_device.get("tested_candidate_sha256") != actual_device.get(
        "candidate_sha256"
    ):
        errors.append("completed Phase B actual-device candidate differs")
    approved_profiles = actual_device.get("approved_production_profile_count")
    if not isinstance(approved_profiles, int) or approved_profiles < 1:
        errors.append("completed Phase B actual-device report lacks approved profiles")
    profiles = actual_device.get("approved_production_profiles")
    actual_raw_paths = {
        item.get("path")
        for item in actual_device.get("raw_evidence", [])
        if isinstance(item, dict) and isinstance(item.get("path"), str)
    }
    if not isinstance(profiles, list) or len(profiles) != approved_profiles:
        errors.append("completed Phase B approved device profile rows are inconsistent")
    else:
        profile_ids: set[str] = set()
        for index, profile in enumerate(profiles):
            label = f"completed Phase B device profile {index}"
            if not isinstance(profile, dict):
                errors.append(f"{label} is malformed")
                continue
            profile_id = profile.get("profile_id")
            if not isinstance(profile_id, str) or not profile_id:
                errors.append(f"{label} profile_id is missing")
            elif profile_id in profile_ids:
                errors.append(f"{label} profile_id is duplicated")
            else:
                profile_ids.add(profile_id)
            for field in ("device_model", "os_version"):
                if not isinstance(profile.get(field), str) or not profile.get(field):
                    errors.append(f"{label} {field} is missing")
            if profile.get("status") != "PASS":
                errors.append(f"{label} status is not PASS")
            profile_refs = string_list(profile.get("raw_evidence_refs"))
            if not profile_refs or not set(profile_refs).issubset(actual_raw_paths):
                errors.append(f"{label} raw evidence refs are invalid")
    if isinstance(approved_profiles, int) and raw_evidence_record_count(
        actual_device
    ) < approved_profiles:
        errors.append("completed Phase B actual-device raw evidence count is too small")

    gate_receipt = receipts.get("RELEASE_GATE_CLOSURE_RECEIPT", {})
    gates = gate_receipt.get("gates")
    gate_ids: set[str] = set()
    raw_paths = {
        item.get("path")
        for item in gate_receipt.get("raw_evidence", [])
        if isinstance(item, dict)
    }
    if not isinstance(gates, list) or len(gates) != len(EXPECTED_RELEASE_GATE_IDS):
        errors.append("completed Phase B gate receipt must contain exactly five gates")
    else:
        for gate in gates:
            if not isinstance(gate, dict):
                errors.append("completed Phase B gate receipt contains a malformed gate")
                continue
            gate_id = gate.get("gate_id")
            if isinstance(gate_id, str):
                gate_ids.add(gate_id)
            if gate.get("status") != "CLOSED" or gate.get("waived") is not False:
                errors.append(f"completed Phase B gate is not closed and unwaived: {gate_id}")
            if not is_iso_datetime(gate.get("completed_at")):
                errors.append(f"completed Phase B gate completion time is invalid: {gate_id}")
            elif not is_within_execution_window(
                gate.get("completed_at"),
                gate_receipt.get("execution_window"),
            ):
                errors.append(
                    f"completed Phase B gate completion time is outside "
                    f"the execution window: {gate_id}"
                )
            expected_actor_ids = {
                actor_name: gate_receipt.get(actor_name, {}).get("id")
                if isinstance(gate_receipt.get(actor_name), dict)
                else None
                for actor_name in ("executor", "reviewer", "approver")
            }
            for actor_name, actor_id in expected_actor_ids.items():
                if gate.get(f"{actor_name}_id") != actor_id:
                    errors.append(
                        f"completed Phase B gate {actor_name} differs: {gate_id}"
                    )
            gate_refs = string_list(gate.get("evidence_refs"))
            if not gate_refs or not set(gate_refs).issubset(raw_paths):
                errors.append(f"completed Phase B gate evidence refs are invalid: {gate_id}")
        if gate_ids != EXPECTED_RELEASE_GATE_IDS:
            errors.append("completed Phase B gate ID set differs")
        if raw_evidence_record_count(gate_receipt) < len(EXPECTED_RELEASE_GATE_IDS):
            errors.append("completed Phase B gate raw evidence count is below five")
        cost_gate = next(
            (
                gate
                for gate in gates
                if isinstance(gate, dict)
                and gate.get("gate_id") == "GATE-CLOUD-COST-MEASUREMENT"
            ),
            {},
        )
        cost = cost_gate.get("cost_measurement")
        if not isinstance(cost, dict):
            errors.append("completed Phase B cloud cost measurement is missing")
        else:
            component_fields = (
                "storage_krw",
                "request_krw",
                "restore_krw",
                "vat_krw",
            )
            component_values = [cost.get(field) for field in component_fields]
            if any(
                not isinstance(value, int) or isinstance(value, bool) or value < 0
                for value in component_values
            ):
                errors.append("completed Phase B cloud cost components are invalid")
            elif (
                cost.get("vat_included") is not True
                or cost.get("currency") != "KRW"
                or cost.get("monthly_total_krw") != sum(component_values)
                or cost.get("monthly_total_krw") > 30000
            ):
                errors.append("completed Phase B cloud cost threshold or VAT differs")

    approval = receipts.get("RELEASE_ELIGIBILITY_APPROVAL", {})
    if approval.get("release_candidate_sha256") != approval.get("candidate_sha256"):
        errors.append("completed Phase B release approval candidate differs")
    if not is_iso_datetime(approval.get("eligibility_approved_at")):
        errors.append("completed Phase B release approval time is invalid")
    else:
        eligibility_approved_at = parse_iso_datetime(
            approval.get("eligibility_approved_at")
        )
        approver_decided_at = parse_iso_datetime(
            approval.get("approver", {}).get("decided_at")
            if isinstance(approval.get("approver"), dict)
            else None
        )
        prerequisite_generated_at = [
            parse_iso_datetime(receipts.get(role, {}).get("generated_at"))
            for role in (
                "TEST_PLAN_APPROVAL_RECEIPT",
                "FORMAL_TEST_REPORT",
                "ACTUAL_DEVICE_TEST_REPORT",
                "RELEASE_GATE_CLOSURE_RECEIPT",
            )
        ]
        if (
            eligibility_approved_at is not None
            and approver_decided_at is not None
            and eligibility_approved_at != approver_decided_at
        ):
            errors.append(
                "completed Phase B eligibility time differs from approver decision"
            )
        if (
            eligibility_approved_at is not None
            and all(item is not None for item in prerequisite_generated_at)
            and eligibility_approved_at
            < max(
                item
                for item in prerequisite_generated_at
                if item is not None
            )
        ):
            errors.append(
                "completed Phase B eligibility predates prerequisite receipts"
            )
    prerequisite_hashes = approval.get("prerequisite_receipt_sha256")
    prerequisite_roles = {
        "TEST_PLAN_APPROVAL_RECEIPT",
        "FORMAL_TEST_REPORT",
        "ACTUAL_DEVICE_TEST_REPORT",
        "RELEASE_GATE_CLOSURE_RECEIPT",
    }
    if not isinstance(prerequisite_hashes, dict) or set(
        prerequisite_hashes
    ) != prerequisite_roles:
        errors.append("completed Phase B release approval prerequisite set differs")
    else:
        for role in prerequisite_roles:
            binding = bindings.get(role, {})
            if prerequisite_hashes.get(role) != binding.get("file_sha256"):
                errors.append(
                    f"completed Phase B release approval prerequisite SHA differs: {role}"
                )
    if len(candidate_hashes) != 1:
        errors.append("completed Phase B evidence does not share one release candidate")
    return errors


def validate_phase_c_evidence_receipts(
    root: Path,
    bindings: dict[str, dict[str, Any]],
    references: Any,
    goal_path_by_id: dict[str, str],
) -> list[str]:
    role = "PHASE_C_TECHNICAL_DELIVERY_RECEIPT"
    errors: list[str] = []
    roster_errors, authority_actors = load_authority_roster(root, bindings)
    errors.extend(f"completed Phase C {error}" for error in roster_errors)
    if role not in set(string_list(references)):
        errors.append("completed Phase C lacks technical delivery receipt role")
    receipt_errors, receipt = validate_receipt_common(
        root,
        bindings,
        role=role,
        expected_status=REQUIRED_PHASE_C_EVIDENCE_STATUS[role],
        allowed_source_goal_ids={EXPECTED_PHASE_IDS["C"]},
        goal_path_by_id=goal_path_by_id,
        authority_actors=authority_actors,
    )
    errors.extend(f"completed Phase C {error}" for error in receipt_errors)
    phase_b_approval_binding = bindings.get("RELEASE_ELIGIBILITY_APPROVAL", {})
    if receipt.get("release_eligibility_receipt_sha256") != phase_b_approval_binding.get(
        "file_sha256"
    ):
        errors.append("completed Phase C is not bound to release eligibility approval")
    phase_b_approval_path = resolve_safe_repo_file(
        root,
        phase_b_approval_binding.get("path"),
    )
    phase_b_approval: dict[str, Any] = {}
    if phase_b_approval_path is not None:
        try:
            phase_b_approval = load_json(phase_b_approval_path)
        except (OSError, ValueError, json.JSONDecodeError):
            phase_b_approval = {}
    if receipt.get("approved_candidate_sha256") != receipt.get("candidate_sha256"):
        errors.append("completed Phase C approved candidate SHA-256 differs")
    if phase_b_approval and phase_b_approval.get(
        "release_candidate_sha256"
    ) != receipt.get("candidate_sha256"):
        errors.append("completed Phase C candidate differs from Phase B approval")
    if receipt.get("deployed_candidate_sha256") != receipt.get("candidate_sha256"):
        errors.append("completed Phase C deployed candidate SHA-256 differs")
    deployment = receipt.get("deployment")
    if not isinstance(deployment, dict):
        errors.append("completed Phase C deployment record is missing")
    else:
        for field in ("environment_id", "channel", "scope"):
            if not isinstance(deployment.get(field), str) or not deployment.get(field):
                errors.append(f"completed Phase C deployment {field} is missing")
        if (
            not is_iso_datetime(deployment.get("started_at"))
            or not is_iso_datetime(deployment.get("ended_at"))
            or deployment.get("executor_id")
            != (
                receipt.get("executor", {}).get("id")
                if isinstance(receipt.get("executor"), dict)
                else None
            )
        ):
            errors.append("completed Phase C deployment execution record is invalid")
        phase_b_approved_at = parse_iso_datetime(
            phase_b_approval.get("eligibility_approved_at")
        )
        deployment_started_at = parse_iso_datetime(deployment.get("started_at"))
        deployment_ended_at = parse_iso_datetime(deployment.get("ended_at"))
        if (
            deployment_started_at is not None
            and deployment_ended_at is not None
            and deployment_ended_at < deployment_started_at
        ):
            errors.append("completed Phase C deployment ends before it starts")
        if (
            not is_within_execution_window(
                deployment.get("started_at"),
                receipt.get("execution_window"),
            )
            or not is_within_execution_window(
                deployment.get("ended_at"),
                receipt.get("execution_window"),
            )
        ):
            errors.append(
                "completed Phase C deployment is outside the receipt execution window"
            )
        if (
            phase_b_approved_at is not None
            and deployment_started_at is not None
            and deployment_started_at < phase_b_approved_at
        ):
            errors.append("completed Phase C deployment predates release eligibility")
    delivery = receipt.get("technical_delivery")
    if not isinstance(delivery, dict):
        errors.append("completed Phase C technical delivery record is missing")
    else:
        recipient = delivery.get("recipient")
        errors.extend(
            f"completed Phase C {error}"
            for error in validate_receipt_actor(role, "recipient", recipient)
        )
        errors.extend(
            f"completed Phase C {error}"
            for error in validate_actor_against_roster(
                receipt_role=role,
                actor_name="recipient",
                actor=recipient,
                actors_by_id=authority_actors,
            )
        )
        if not is_iso_datetime(delivery.get("accepted_at")):
            errors.append("completed Phase C technical delivery acceptance time is invalid")
        elif not is_within_execution_window(
            delivery.get("accepted_at"),
            receipt.get("execution_window"),
        ):
            errors.append(
                "completed Phase C delivery acceptance is outside "
                "the receipt execution window"
            )
        elif isinstance(deployment, dict):
            deployed_at = parse_iso_datetime(deployment.get("ended_at"))
            accepted_at = parse_iso_datetime(delivery.get("accepted_at"))
            if (
                deployed_at is not None
                and accepted_at is not None
                and accepted_at < deployed_at
            ):
                errors.append("completed Phase C delivery acceptance predates deployment")
        artifacts = delivery.get("artifacts")
        if not isinstance(artifacts, list) or not artifacts:
            errors.append("completed Phase C technical delivery artifact list is missing")
        else:
            for index, artifact in enumerate(artifacts):
                if not isinstance(artifact, dict):
                    errors.append(
                        f"completed Phase C technical delivery artifact {index} is malformed"
                    )
                    continue
                artifact_path = resolve_safe_repo_file(root, artifact.get("path"))
                artifact_hash = artifact.get("sha256")
                if (
                    artifact_path is None
                    or not isinstance(artifact_hash, str)
                    or not SHA256_PATTERN.fullmatch(artifact_hash)
                    or sha256_file(artifact_path) != artifact_hash
                ):
                    errors.append(
                        f"completed Phase C technical delivery artifact {index} is invalid"
                    )
    errors.extend(
        validate_required_check_map(
            role,
            receipt.get("required_checks"),
            PHASE_C_REQUIRED_CHECKS,
            allowed_evidence_refs={
                item.get("path")
                for item in receipt.get("raw_evidence", [])
                if isinstance(item, dict) and isinstance(item.get("path"), str)
            },
            summary=receipt.get("required_check_summary"),
            expected_actor_ids={
                actor_name: receipt.get(actor_name, {}).get("id")
                if isinstance(receipt.get(actor_name), dict)
                else None
                for actor_name in ("executor", "reviewer", "approver")
            },
            execution_window=receipt.get("execution_window"),
        )
    )
    phase_c_checks = receipt.get("required_checks")
    canary_at = parse_iso_datetime(
        phase_c_checks.get("canary", {}).get("executed_at")
        if isinstance(phase_c_checks, dict)
        and isinstance(phase_c_checks.get("canary"), dict)
        else None
    )
    smoke_at = parse_iso_datetime(
        phase_c_checks.get("smoke", {}).get("executed_at")
        if isinstance(phase_c_checks, dict)
        and isinstance(phase_c_checks.get("smoke"), dict)
        else None
    )
    deployment_started_at = parse_iso_datetime(
        deployment.get("started_at")
        if isinstance(deployment, dict)
        else None
    )
    deployment_ended_at = parse_iso_datetime(
        deployment.get("ended_at")
        if isinstance(deployment, dict)
        else None
    )
    delivery_accepted_at = parse_iso_datetime(
        delivery.get("accepted_at")
        if isinstance(delivery, dict)
        else None
    )
    phase_c_sequence = (
        deployment_started_at,
        canary_at,
        smoke_at,
        deployment_ended_at,
        delivery_accepted_at,
    )
    if (
        any(item is None for item in phase_c_sequence)
        or not (
            deployment_started_at
            <= canary_at
            < smoke_at
            <= deployment_ended_at
            <= delivery_accepted_at
        )
    ):
        errors.append(
            "completed Phase C deployment chronology must be start, canary, "
            "smoke, deployment end, then delivery acceptance"
        )
    return errors


def validate_phase_d_evidence_receipts(
    root: Path,
    bindings: dict[str, dict[str, Any]],
    references: Any,
    goal_path_by_id: dict[str, str],
) -> list[str]:
    errors: list[str] = []
    roster_errors, authority_actors = load_authority_roster(root, bindings)
    errors.extend(f"completed Phase D {error}" for error in roster_errors)
    reference_set = set(string_list(references))
    required_roles = set(REQUIRED_PHASE_D_EVIDENCE_STATUS)
    if not required_roles.issubset(reference_set):
        errors.append("completed Phase D lacks handover and closure receipt roles")
    receipts: dict[str, dict[str, Any]] = {}
    candidate_hashes: set[str] = set()
    for role, status in REQUIRED_PHASE_D_EVIDENCE_STATUS.items():
        receipt_errors, receipt = validate_receipt_common(
            root,
            bindings,
            role=role,
            expected_status=status,
            allowed_source_goal_ids={EXPECTED_PHASE_IDS["D"]},
            goal_path_by_id=goal_path_by_id,
            authority_actors=authority_actors,
        )
        errors.extend(f"completed Phase D {error}" for error in receipt_errors)
        receipts[role] = receipt
        candidate_hash = receipt.get("candidate_sha256")
        if isinstance(candidate_hash, str) and SHA256_PATTERN.fullmatch(candidate_hash):
            candidate_hashes.add(candidate_hash)
    handover = receipts.get("PHASE_D_OPERATION_HANDOVER_RECEIPT", {})
    phase_c_binding = bindings.get("PHASE_C_TECHNICAL_DELIVERY_RECEIPT", {})
    if handover.get("phase_c_technical_delivery_receipt_sha256") != phase_c_binding.get(
        "file_sha256"
    ):
        errors.append("completed Phase D handover is not bound to Phase C delivery")
    phase_c_path = resolve_safe_repo_file(root, phase_c_binding.get("path"))
    phase_c_receipt: dict[str, Any] = {}
    if phase_c_path is not None:
        try:
            phase_c_receipt = load_json(phase_c_path)
        except (OSError, ValueError, json.JSONDecodeError):
            phase_c_receipt = {}
    if handover.get("deployed_candidate_sha256") != handover.get("candidate_sha256"):
        errors.append("completed Phase D handover deployed candidate differs")
    if phase_c_receipt and phase_c_receipt.get(
        "deployed_candidate_sha256"
    ) != handover.get("candidate_sha256"):
        errors.append("completed Phase D candidate differs from Phase C deployment")
    for actor_name in ("transferor", "transferee"):
        errors.extend(
            f"completed Phase D {error}"
            for error in validate_receipt_actor(
                "PHASE_D_OPERATION_HANDOVER_RECEIPT",
                actor_name,
                handover.get(actor_name),
                require_decision=True,
            )
        )
        errors.extend(
            f"completed Phase D {error}"
            for error in validate_actor_against_roster(
                receipt_role="PHASE_D_OPERATION_HANDOVER_RECEIPT",
                actor_name=actor_name,
                actor=handover.get(actor_name),
                actors_by_id=authority_actors,
            )
        )
    if not is_iso_datetime(handover.get("accepted_at")):
        errors.append("completed Phase D handover acceptance time is invalid")
    elif not is_within_execution_window(
        handover.get("accepted_at"),
        handover.get("execution_window"),
    ):
        errors.append(
            "completed Phase D handover acceptance is outside "
            "the receipt execution window"
        )
    else:
        phase_c_accepted_at = parse_iso_datetime(
            phase_c_receipt.get("technical_delivery", {}).get("accepted_at")
            if isinstance(phase_c_receipt.get("technical_delivery"), dict)
            else None
        )
        handover_accepted_at = parse_iso_datetime(handover.get("accepted_at"))
        if (
            phase_c_accepted_at is not None
            and handover_accepted_at is not None
            and handover_accepted_at < phase_c_accepted_at
        ):
            errors.append("completed Phase D handover predates Phase C delivery")
    responsibility = handover.get("operational_responsibility")
    transferee_id = (
        handover.get("transferee", {}).get("id")
        if isinstance(handover.get("transferee"), dict)
        else None
    )
    if (
        not isinstance(responsibility, dict)
        or responsibility.get("service_owner_id") != transferee_id
        or not isinstance(responsibility.get("support_contact"), str)
        or not responsibility.get("support_contact")
        or not isinstance(responsibility.get("cost_owner_id"), str)
        or not responsibility.get("cost_owner_id")
        or not is_iso_datetime(responsibility.get("effective_at"))
    ):
        errors.append("completed Phase D operational responsibility record is invalid")
    elif not is_within_execution_window(
        responsibility.get("effective_at"),
        handover.get("execution_window"),
    ):
        errors.append(
            "completed Phase D operational responsibility time is outside "
            "the handover execution window"
        )
    else:
        responsibility_effective_at = parse_iso_datetime(
            responsibility.get("effective_at")
        )
        handover_accepted_at = parse_iso_datetime(handover.get("accepted_at"))
        if (
            responsibility_effective_at is not None
            and handover_accepted_at is not None
            and responsibility_effective_at < handover_accepted_at
        ):
            errors.append(
                "completed Phase D responsibility predates handover acceptance"
            )
    errors.extend(
        validate_required_check_map(
            "PHASE_D_OPERATION_HANDOVER_RECEIPT",
            receipts.get("PHASE_D_OPERATION_HANDOVER_RECEIPT", {}).get(
                "required_checks"
            ),
            PHASE_D_HANDOVER_REQUIRED_CHECKS,
            allowed_evidence_refs={
                item.get("path")
                for item in receipts.get(
                    "PHASE_D_OPERATION_HANDOVER_RECEIPT", {}
                ).get("raw_evidence", [])
                if isinstance(item, dict) and isinstance(item.get("path"), str)
            },
            summary=receipts.get(
                "PHASE_D_OPERATION_HANDOVER_RECEIPT", {}
            ).get("required_check_summary"),
            expected_actor_ids={
                actor_name: receipts.get(
                    "PHASE_D_OPERATION_HANDOVER_RECEIPT", {}
                ).get(actor_name, {}).get("id")
                if isinstance(
                    receipts.get(
                        "PHASE_D_OPERATION_HANDOVER_RECEIPT", {}
                    ).get(actor_name),
                    dict,
                )
                else None
                for actor_name in ("executor", "reviewer", "approver")
            },
            execution_window=receipts.get(
                "PHASE_D_OPERATION_HANDOVER_RECEIPT", {}
            ).get("execution_window"),
        )
    )
    handover_window = handover.get("execution_window")
    handover_started_at = parse_iso_datetime(
        handover_window.get("started_at")
        if isinstance(handover_window, dict)
        else None
    )
    phase_c_accepted_at = parse_iso_datetime(
        phase_c_receipt.get("technical_delivery", {}).get("accepted_at")
        if isinstance(phase_c_receipt.get("technical_delivery"), dict)
        else None
    )
    handover_accepted_at = parse_iso_datetime(handover.get("accepted_at"))
    handover_activity_times = [
        parse_iso_datetime(check.get("executed_at"))
        for check in (
            handover.get("required_checks", {}).values()
            if isinstance(handover.get("required_checks"), dict)
            else []
        )
        if isinstance(check, dict)
    ] + [
        parse_iso_datetime(handover.get(actor_name, {}).get("decided_at"))
        if isinstance(handover.get(actor_name), dict)
        else None
        for actor_name in ("transferor", "transferee")
    ]
    if (
        phase_c_accepted_at is None
        or handover_started_at is None
        or handover_accepted_at is None
        or any(item is None for item in handover_activity_times)
        or handover_started_at < phase_c_accepted_at
        or any(
            item < handover_started_at or item > handover_accepted_at
            for item in handover_activity_times
            if item is not None
        )
    ):
        errors.append(
            "completed Phase D handover chronology must follow Phase C "
            "acceptance and finish all checks/decisions before acceptance"
        )
    errors.extend(
        validate_required_check_map(
            "PHASE_D_PROJECT_CLOSURE_RECEIPT",
            receipts.get("PHASE_D_PROJECT_CLOSURE_RECEIPT", {}).get(
                "required_checks"
            ),
            PHASE_D_CLOSURE_REQUIRED_CHECKS,
            allowed_evidence_refs={
                item.get("path")
                for item in receipts.get(
                    "PHASE_D_PROJECT_CLOSURE_RECEIPT", {}
                ).get("raw_evidence", [])
                if isinstance(item, dict) and isinstance(item.get("path"), str)
            },
            summary=receipts.get(
                "PHASE_D_PROJECT_CLOSURE_RECEIPT", {}
            ).get("required_check_summary"),
            expected_actor_ids={
                actor_name: receipts.get(
                    "PHASE_D_PROJECT_CLOSURE_RECEIPT", {}
                ).get(actor_name, {}).get("id")
                if isinstance(
                    receipts.get(
                        "PHASE_D_PROJECT_CLOSURE_RECEIPT", {}
                    ).get(actor_name),
                    dict,
                )
                else None
                for actor_name in ("executor", "reviewer", "approver")
            },
            execution_window=receipts.get(
                "PHASE_D_PROJECT_CLOSURE_RECEIPT", {}
            ).get("execution_window"),
        )
    )
    handover_binding = bindings.get("PHASE_D_OPERATION_HANDOVER_RECEIPT", {})
    closure = receipts.get("PHASE_D_PROJECT_CLOSURE_RECEIPT", {})
    if closure.get(
        "operation_handover_receipt_sha256"
    ) != handover_binding.get("file_sha256"):
        errors.append("completed Phase D closure is not bound to handover receipt")
    handover_accepted_at = parse_iso_datetime(handover.get("accepted_at"))
    handover_generated_at = parse_iso_datetime(handover.get("generated_at"))
    closure_window = closure.get("execution_window")
    closure_started_at = parse_iso_datetime(
        closure_window.get("started_at")
        if isinstance(closure_window, dict)
        else None
    )
    closure_generated_at = parse_iso_datetime(closure.get("generated_at"))
    if (
        handover_accepted_at is not None
        and closure_started_at is not None
        and closure_started_at < handover_accepted_at
    ):
        errors.append(
            "completed Phase D closure execution starts before handover acceptance"
        )
    if (
        handover_generated_at is not None
        and closure_generated_at is not None
        and closure_generated_at < handover_generated_at
    ):
        errors.append(
            "completed Phase D closure receipt predates the handover receipt"
        )
    if closure.get("deployed_candidate_sha256") != closure.get("candidate_sha256"):
        errors.append("completed Phase D closure deployed candidate differs")
    if closure.get("closure_type") not in {"CONTINUED_OPERATION", "SERVICE_TERMINATION"}:
        errors.append("completed Phase D closure type is invalid")
    archive = closure.get("final_archive")
    archive_path = (
        resolve_safe_repo_file(root, archive.get("path"))
        if isinstance(archive, dict)
        else None
    )
    if (
        archive_path is None
        or not isinstance(archive.get("sha256"), str)
        or not SHA256_PATTERN.fullmatch(archive.get("sha256"))
        or sha256_file(archive_path) != archive.get("sha256")
    ):
        errors.append("completed Phase D final archive is invalid")
    kpi_result_refs = closure.get("kpi_result_refs")
    if not isinstance(kpi_result_refs, list) or not kpi_result_refs or any(
        not isinstance(item, str) or not item for item in kpi_result_refs
    ):
        errors.append("completed Phase D KPI result refs are missing")
    remaining_registers = closure.get("remaining_item_registers")
    if not isinstance(remaining_registers, dict) or set(remaining_registers) != {
        "defects",
        "risks",
        "technical_debt",
    } or any(
        not isinstance(value, list)
        or any(not isinstance(item, str) or not item for item in value)
        for value in remaining_registers.values()
    ):
        errors.append("completed Phase D remaining item registers are invalid")
    for field in ("data_disposition_status", "account_key_cleanup_status"):
        if closure.get(field) not in {
            "COMPLETED",
            "TRANSFERRED",
            "RETAINED_APPROVED",
        }:
            errors.append(f"completed Phase D {field} is invalid")
    for actor_name in ("project_owner", "accepting_owner"):
        errors.extend(
            f"completed Phase D {error}"
            for error in validate_receipt_actor(
                "PHASE_D_PROJECT_CLOSURE_RECEIPT",
                actor_name,
                closure.get(actor_name),
                require_decision=True,
            )
        )
        errors.extend(
            f"completed Phase D {error}"
            for error in validate_actor_against_roster(
                receipt_role="PHASE_D_PROJECT_CLOSURE_RECEIPT",
                actor_name=actor_name,
                actor=closure.get(actor_name),
                actors_by_id=authority_actors,
            )
        )
    if not is_iso_datetime(closure.get("closed_at")):
        errors.append("completed Phase D closure time is invalid")
    elif not is_within_execution_window(
        closure.get("closed_at"),
        closure.get("execution_window"),
    ):
        errors.append(
            "completed Phase D closure time is outside "
            "the receipt execution window"
        )
    else:
        handover_accepted_at = parse_iso_datetime(handover.get("accepted_at"))
        closed_at = parse_iso_datetime(closure.get("closed_at"))
        if (
            handover_accepted_at is not None
            and closed_at is not None
            and closed_at < handover_accepted_at
        ):
            errors.append("completed Phase D closure predates handover")
    closure_started_at = parse_iso_datetime(
        closure.get("execution_window", {}).get("started_at")
        if isinstance(closure.get("execution_window"), dict)
        else None
    )
    closed_at = parse_iso_datetime(closure.get("closed_at"))
    closure_activity_times = [
        parse_iso_datetime(check.get("executed_at"))
        for check in (
            closure.get("required_checks", {}).values()
            if isinstance(closure.get("required_checks"), dict)
            else []
        )
        if isinstance(check, dict)
    ] + [
        parse_iso_datetime(closure.get(actor_name, {}).get("decided_at"))
        if isinstance(closure.get(actor_name), dict)
        else None
        for actor_name in ("project_owner", "accepting_owner")
    ]
    if (
        handover_accepted_at is None
        or closure_started_at is None
        or closed_at is None
        or any(item is None for item in closure_activity_times)
        or closure_started_at < handover_accepted_at
        or any(
            item < closure_started_at or item > closed_at
            for item in closure_activity_times
            if item is not None
        )
    ):
        errors.append(
            "completed Phase D closure chronology must start after handover "
            "and finish all checks/decisions before closure"
        )
    if len(candidate_hashes) != 1:
        errors.append("completed Phase D evidence does not share one deployed release")
    return errors


def validate_goal_document(
    relative: str,
    metadata: dict[str, Any],
    body: str,
    binding_roles: set[str],
) -> list[str]:
    errors: list[str] = []
    unknown = sorted(set(metadata) - ALLOWED_FIELDS)
    missing = sorted(REQUIRED_FIELDS - set(metadata))
    if unknown:
        errors.append(f"{relative}: unknown front matter fields: {unknown}")
    if missing:
        errors.append(f"{relative}: missing front matter fields: {missing}")
    if metadata.get("schema_version") != "1.0":
        errors.append(f"{relative}: schema_version must be 1.0")
    goal_id = metadata.get("goal_id")
    if not isinstance(goal_id, str) or not GOAL_ID_PATTERN.fullmatch(goal_id):
        errors.append(f"{relative}: invalid goal_id")
    if metadata.get("goal_kind") not in ALLOWED_KINDS:
        errors.append(f"{relative}: invalid goal_kind")
    if metadata.get("initial_status") not in ALLOWED_INITIAL_STATUSES:
        errors.append(f"{relative}: invalid initial_status")
    if not isinstance(metadata.get("sequence"), int) or metadata.get("sequence", -1) < 0:
        errors.append(f"{relative}: sequence must be a non-negative integer")

    string_fields = [
        "schema_version",
        "goal_id",
        "goal_kind",
        "document_version",
        "phase_id",
        "parent_goal_id",
        "initial_status",
        "target_completion_level",
        "next_goal_id",
        "return_goal_id",
        "work_item_id",
    ]
    for field in string_fields:
        if not isinstance(metadata.get(field), str):
            errors.append(f"{relative}: {field} must be a string")

    list_fields = [
        "dependencies",
        "child_goal_ids",
        "source_policy_ids",
        "gap_ids",
        "canonical_input_roles",
    ]
    for field in list_fields:
        value = metadata.get(field)
        if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
            errors.append(f"{relative}: {field} must be a string list")
        elif value != list(dict.fromkeys(value)):
            errors.append(f"{relative}: {field} contains duplicates")

    roles = metadata.get("canonical_input_roles", [])
    if isinstance(roles, list):
        missing_roles = sorted(set(roles) - binding_roles)
        if missing_roles:
            errors.append(f"{relative}: unknown canonical input roles: {missing_roles}")

    kind = metadata.get("goal_kind")
    if kind == "MASTER":
        if metadata.get("question_condition_codes") != EXPECTED_QUESTION_CODES:
            errors.append(f"{relative}: master question condition codes drifted")
        if metadata.get("stop_condition_codes") != EXPECTED_STOP_CODES:
            errors.append(f"{relative}: master stop condition codes drifted")
        if metadata.get("parent_goal_id") or metadata.get("return_goal_id"):
            errors.append(f"{relative}: master must not have parent or return goal")
    else:
        if metadata.get("question_policy") != "INHERIT_MASTER":
            errors.append(f"{relative}: child goal must inherit master question policy")
        if metadata.get("stop_policy") != "INHERIT_MASTER":
            errors.append(f"{relative}: child goal must inherit master stop policy")

    if kind == "PHASE" and metadata.get("phase_id") not in EXPECTED_PHASE_ORDER:
        errors.append(f"{relative}: phase goal has invalid phase_id")
    if kind in {"EPIC", "WORK_ITEM"} and metadata.get("phase_id") not in EXPECTED_PHASE_ORDER:
        errors.append(f"{relative}: {kind} has invalid phase_id")
    if kind == "EPIC" and not isinstance(
        metadata.get("source_status_at_creation"), str
    ):
        errors.append(f"{relative}: epic source_status_at_creation is required")
    if kind == "WORK_ITEM":
        if not metadata.get("work_item_id"):
            errors.append(f"{relative}: work item ID is required")
        if metadata.get("child_goal_ids"):
            errors.append(f"{relative}: work item cannot have child goals")
        if metadata.get("next_goal_id"):
            errors.append(f"{relative}: work item returns to its parent instead of a stale next leaf")
        if metadata.get("return_goal_id") != metadata.get("parent_goal_id"):
            errors.append(f"{relative}: work item return goal must be its parent")
        required_work_item_strings = [
            "materialized_from_role",
            "materialized_from_path",
            "materialized_from_document_id",
            "materialized_from_sha256",
            "predecessor_goal_id",
            "predecessor_goal_content_sha256",
            "supersedes_goal_id",
            "supersedes_goal_content_sha256",
            "reopen_reason",
        ]
        for field in required_work_item_strings:
            if not isinstance(metadata.get(field), str):
                errors.append(f"{relative}: work item {field} must be a string")
        reopen_evidence_refs = metadata.get("reopen_evidence_refs")
        if not isinstance(reopen_evidence_refs, list) or any(
            not isinstance(item, str) for item in reopen_evidence_refs
        ):
            errors.append(
                f"{relative}: work item reopen_evidence_refs must be a string list"
            )
        if metadata.get("materialized_from_role") not in ALLOWED_CURRENT_LEAF_SOURCES:
            errors.append(f"{relative}: unsupported materialization source role")
        if not SHA256_PATTERN.fullmatch(metadata.get("materialized_from_sha256", "")):
            errors.append(f"{relative}: invalid materialization source SHA-256")
        for id_field, hash_field in (
            ("predecessor_goal_id", "predecessor_goal_content_sha256"),
            ("supersedes_goal_id", "supersedes_goal_content_sha256"),
        ):
            related_id = metadata.get(id_field, "")
            related_hash = metadata.get(hash_field, "")
            if bool(related_id) != bool(related_hash):
                errors.append(
                    f"{relative}: {id_field} and {hash_field} must be set together"
                )
            if related_hash and not SHA256_PATTERN.fullmatch(related_hash):
                errors.append(f"{relative}: invalid {hash_field}")
        if metadata.get("supersedes_goal_id"):
            if not metadata.get("reopen_reason"):
                errors.append(f"{relative}: superseding work item requires reopen_reason")
            if not string_list(metadata.get("reopen_evidence_refs")):
                errors.append(
                    f"{relative}: superseding work item requires reopen_evidence_refs"
                )
        elif metadata.get("reopen_reason") or string_list(
            metadata.get("reopen_evidence_refs")
        ):
            errors.append(
                f"{relative}: reopen fields require supersedes_goal_id"
            )

    for heading in EXPECTED_HEADINGS:
        if body.count(heading) != 1:
            errors.append(f"{relative}: required heading must appear once: {heading}")
    return errors


def has_cycle(nodes: dict[str, dict[str, Any]], field: str) -> bool:
    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(goal_id: str) -> bool:
        if goal_id in visited:
            return False
        if goal_id in visiting:
            return True
        visiting.add(goal_id)
        for dependency in string_list(nodes[goal_id].get(field)):
            if dependency in nodes and visit(dependency):
                return True
        visiting.remove(goal_id)
        visited.add(goal_id)
        return False

    return any(visit(goal_id) for goal_id in nodes)


def validate_graph(nodes: dict[str, dict[str, Any]]) -> list[str]:
    errors: list[str] = []
    masters = [node for node in nodes.values() if node.get("goal_kind") == "MASTER"]
    if len(masters) != 1 or masters[0].get("goal_id") != EXPECTED_MASTER_ID:
        errors.append("goal graph must contain exactly the expected master")

    phases = {
        node.get("phase_id"): node
        for node in nodes.values()
        if node.get("goal_kind") == "PHASE"
        and isinstance(node.get("phase_id"), str)
    }
    if set(phases) != set(EXPECTED_PHASE_ORDER):
        errors.append("goal graph must contain exactly phases A-D")
    else:
        for phase_id, expected_id in EXPECTED_PHASE_IDS.items():
            if phases[phase_id].get("goal_id") != expected_id:
                errors.append(f"phase {phase_id} goal ID drifted")

    for goal_id, node in nodes.items():
        parent = node.get("parent_goal_id")
        if not isinstance(parent, str):
            errors.append(f"{goal_id}: parent_goal_id must be a string")
            parent = ""
        if parent and parent not in nodes:
            errors.append(f"{goal_id}: parent goal is missing: {parent}")
        for field in ("dependencies", "child_goal_ids"):
            for related in string_list(node.get(field)):
                if related not in nodes:
                    errors.append(f"{goal_id}: {field} target is missing: {related}")
                if related == goal_id:
                    errors.append(f"{goal_id}: self reference in {field}")
        next_goal = node.get("next_goal_id")
        if not isinstance(next_goal, str):
            errors.append(f"{goal_id}: next_goal_id must be a string")
            next_goal = ""
        if next_goal and next_goal not in nodes:
            errors.append(f"{goal_id}: next goal is missing: {next_goal}")
        return_goal = node.get("return_goal_id")
        if not isinstance(return_goal, str):
            errors.append(f"{goal_id}: return_goal_id must be a string")
            return_goal = ""
        if return_goal and return_goal not in nodes:
            errors.append(f"{goal_id}: return goal is missing: {return_goal}")
        for child in string_list(node.get("child_goal_ids")):
            if child in nodes and nodes[child].get("parent_goal_id") != goal_id:
                errors.append(f"{goal_id}: child parent is not reciprocal: {child}")
        if (
            node.get("goal_kind") != "MASTER"
            and node.get("goal_kind") != "WORK_ITEM"
            and parent in nodes
            and goal_id not in string_list(nodes[parent].get("child_goal_ids"))
        ):
            errors.append(f"{goal_id}: parent does not list this static child")

    for goal_id, node in nodes.items():
        seen: set[str] = set()
        cursor = goal_id
        while cursor:
            if cursor in seen:
                errors.append(f"{goal_id}: parent cycle detected")
                break
            seen.add(cursor)
            parent = nodes.get(cursor, {}).get("parent_goal_id", "")
            if not isinstance(parent, str):
                errors.append(f"{cursor}: parent_goal_id must be a string")
                break
            if not parent:
                if cursor != EXPECTED_MASTER_ID:
                    errors.append(f"{goal_id}: goal is orphaned from master")
                break
            cursor = parent

    if has_cycle(nodes, "dependencies"):
        errors.append("goal dependency graph contains a cycle")

    expected_phase_next = {
        "A": EXPECTED_PHASE_IDS["B"],
        "B": EXPECTED_PHASE_IDS["C"],
        "C": EXPECTED_PHASE_IDS["D"],
        "D": "",
    }
    expected_phase_dependencies = {
        "A": [],
        "B": [EXPECTED_PHASE_IDS["A"]],
        "C": [EXPECTED_PHASE_IDS["B"]],
        "D": [EXPECTED_PHASE_IDS["C"]],
    }
    for phase_id in EXPECTED_PHASE_ORDER:
        node = phases.get(phase_id)
        if not node:
            continue
        if node.get("next_goal_id") != expected_phase_next[phase_id]:
            errors.append(f"phase {phase_id}: next goal drifted")
        if node.get("dependencies") != expected_phase_dependencies[phase_id]:
            errors.append(f"phase {phase_id}: dependencies drifted")

    master = nodes.get(EXPECTED_MASTER_ID, {})
    if master.get("child_goal_ids") != [EXPECTED_PHASE_IDS[item] for item in EXPECTED_PHASE_ORDER]:
        errors.append("master phase children drifted")
    if master.get("next_goal_id") != EXPECTED_PHASE_IDS["A"]:
        errors.append("master next goal must be phase A")
    return errors


def validate_epics(
    nodes: dict[str, dict[str, Any]],
    backlog: dict[str, Any],
    policy_gap_mapping: dict[str, str],
) -> list[str]:
    errors: list[str] = []
    epics = {
        epic.get("epic_id"): epic
        for epic in backlog.get("epics", [])
        if isinstance(epic, dict)
    }
    if backlog.get("execution_order") != EXPECTED_EXECUTION_ORDER:
        errors.append("backlog execution order drifted from the approved Goal chain")
    if set(epics) != set(EXPECTED_EXECUTION_ORDER):
        errors.append("backlog EPIC set is not the expected 12")
        return errors

    epic_nodes: dict[str, dict[str, Any]] = {}
    for epic_id, goal_id in EXPECTED_EPIC_GOAL_IDS.items():
        node = nodes.get(goal_id)
        if not node or node.get("goal_kind") != "EPIC":
            errors.append(f"{epic_id}: expected EPIC goal is missing")
            continue
        epic_nodes[epic_id] = node
        source = epics[epic_id]
        if node.get("source_policy_ids") != source.get("ordered_source_policy_ids"):
            errors.append(f"{epic_id}: ordered policies differ from latest backlog")
        if node.get("gap_ids") != source.get("gap_ids"):
            errors.append(f"{epic_id}: Gap list differs from latest backlog")
        expected_gap_inventory = [
            policy_gap_mapping.get(policy_id)
            for policy_id in string_list(node.get("source_policy_ids"))
        ]
        if (
            any(gap_id is None for gap_id in expected_gap_inventory)
            or Counter(string_list(node.get("gap_ids")))
            != Counter(expected_gap_inventory)
        ):
            errors.append(
                f"{epic_id}: policy/Gap inventory differs from the canonical "
                "IMPLEMENTATION_GAP mapping"
            )
        if not node.get("source_status_at_creation"):
            errors.append(f"{epic_id}: source status at creation is missing")
        if node.get("target_completion_level") != source.get("target_completion_level"):
            errors.append(f"{epic_id}: target completion level differs from latest backlog")
        expected_dependencies = [
            EXPECTED_EPIC_GOAL_IDS[item]
            for item in source.get("dependencies", [])
        ]
        if node.get("dependencies") != expected_dependencies:
            errors.append(f"{epic_id}: EPIC dependencies differ from latest backlog")

    all_policies = [
        policy
        for epic_id in EXPECTED_EXECUTION_ORDER
        for policy in string_list(
            epic_nodes.get(epic_id, {}).get("source_policy_ids")
        )
    ]
    all_gaps = [
        gap_id
        for epic_id in EXPECTED_EXECUTION_ORDER
        for gap_id in string_list(epic_nodes.get(epic_id, {}).get("gap_ids"))
    ]
    backlog_policies = [
        item.get("source_policy_id")
        for item in backlog.get("next_action_sequence", [])
        if isinstance(item, dict)
    ]
    if len(all_policies) != 68 or Counter(all_policies) != Counter(backlog_policies):
        errors.append("68 policies/gates are not covered exactly once by EPIC goals")
    if len(all_gaps) != 68 or len(set(all_gaps)) != 68:
        errors.append("68 Gaps are not covered exactly once by EPIC goals")

    phase_a = nodes.get(EXPECTED_PHASE_IDS["A"], {})
    phase_b = nodes.get(EXPECTED_PHASE_IDS["B"], {})
    expected_a_children = [
        EXPECTED_EPIC_GOAL_IDS[item]
        for item in EXPECTED_EXECUTION_ORDER[:-1]
    ]
    if phase_a.get("child_goal_ids") != expected_a_children:
        errors.append("phase A EPIC order drifted")
    if phase_b.get("child_goal_ids") != [EXPECTED_EPIC_GOAL_IDS["EPIC-12"]]:
        errors.append("phase B must own EPIC-12")

    for index, epic_id in enumerate(EXPECTED_EXECUTION_ORDER[:-1]):
        expected_next = (
            EXPECTED_EPIC_GOAL_IDS[EXPECTED_EXECUTION_ORDER[index + 1]]
            if index + 1 < len(EXPECTED_EXECUTION_ORDER) - 1
            else ""
        )
        if epic_nodes.get(epic_id, {}).get("next_goal_id") != expected_next:
            errors.append(f"{epic_id}: next EPIC pointer drifted")
    if epic_nodes.get("EPIC-12", {}).get("next_goal_id"):
        errors.append("EPIC-12 must return to phase B instead of skipping to release")
    return errors


def validate_work_item_lineage(
    root: Path,
    nodes: dict[str, dict[str, Any]],
    goal_path_by_id: dict[str, str],
    bindings: dict[str, dict[str, Any]],
    policy_gap_mapping: dict[str, str],
) -> list[str]:
    errors: list[str] = []
    relation_fields = [
        ("predecessor_goal_id", "predecessor_goal_content_sha256"),
        ("supersedes_goal_id", "supersedes_goal_content_sha256"),
    ]
    work_items = {
        goal_id: node
        for goal_id, node in nodes.items()
        if node.get("goal_kind") == "WORK_ITEM"
    }
    for goal_id, node in work_items.items():
        relative = goal_path_by_id[goal_id]
        if node.get("target_completion_level") not in (
            ALLOWED_WORK_ITEM_TARGETS_BY_PHASE.get(
                str(node.get("phase_id")),
                set(),
            )
        ):
            errors.append(
                f"{relative}: Work Item target completion level is not "
                "allowed for its phase"
            )
        parent_goal_id = node.get("parent_goal_id")
        parent = nodes.get(parent_goal_id, {}) if isinstance(parent_goal_id, str) else {}
        if parent.get("goal_kind") not in {"EPIC", "PHASE"}:
            errors.append(f"{relative}: work item parent must be an EPIC or phase")
        if parent.get("goal_kind") == "EPIC":
            policy = string_list(node.get("source_policy_ids"))
            gap = string_list(node.get("gap_ids"))
            if len(policy) != 1:
                errors.append(f"{relative}: EPIC work item must contain exactly one policy")
            if len(gap) != 1:
                errors.append(f"{relative}: EPIC work item must contain exactly one Gap")
            if policy and policy[0] not in string_list(parent.get("source_policy_ids")):
                errors.append(f"{relative}: policy is not owned by the parent EPIC")
            if gap and gap[0] not in string_list(parent.get("gap_ids")):
                errors.append(f"{relative}: Gap is not owned by the parent EPIC")
            if (
                len(policy) == 1
                and len(gap) == 1
                and policy_gap_mapping.get(policy[0]) != gap[0]
            ):
                errors.append(
                    f"{relative}: policy/Gap pair differs from the canonical "
                    "IMPLEMENTATION_GAP assessment"
                )

        source_relative = node.get("materialized_from_path")
        source_path = resolve_safe_repo_file(root, source_relative)
        if source_path is None:
            errors.append(f"{relative}: materialization source path is unsafe or missing")
        elif sha256_file(source_path) != node.get("materialized_from_sha256"):
            errors.append(f"{relative}: materialization source SHA-256 differs")
        elif node.get("materialized_from_role") == "IMPLEMENTATION_BACKLOG":
            try:
                source = load_json(source_path)
            except (OSError, ValueError, json.JSONDecodeError) as exc:
                errors.append(f"{relative}: materialization backlog cannot be loaded: {exc}")
            else:
                source_id = source.get("metadata", {}).get("backlog_id")
                if source_id != node.get("materialized_from_document_id"):
                    errors.append(f"{relative}: materialization backlog ID differs")
        elif node.get("materialized_from_role") == "PHASE_PLAN":
            expected_source_path = goal_path_by_id.get(parent_goal_id)
            if source_relative != expected_source_path:
                errors.append(
                    f"{relative}: PHASE_PLAN source must be the parent Goal document"
                )
            if node.get("materialized_from_document_id") != parent_goal_id:
                errors.append(
                    f"{relative}: PHASE_PLAN document ID must equal the parent Goal ID"
                )

        predecessor_id = node.get("predecessor_goal_id")
        if goal_id == INITIAL_WORK_ITEM_GOAL_ID:
            if predecessor_id or node.get("supersedes_goal_id"):
                errors.append(
                    f"{relative}: initial Work Item cannot have predecessor or supersedes"
                )
        elif not isinstance(predecessor_id, str) or not predecessor_id:
            errors.append(f"{relative}: non-initial Work Item requires predecessor_goal_id")

        for id_field, hash_field in relation_fields:
            related_id = node.get(id_field)
            if not related_id:
                continue
            related = work_items.get(related_id)
            if not related:
                errors.append(f"{relative}: {id_field} is not an existing Work Item")
                continue
            related_path = resolve_safe_repo_file(root, goal_path_by_id[related_id])
            if related_path is None or sha256_file(related_path) != node.get(hash_field):
                errors.append(f"{relative}: {hash_field} differs from immutable predecessor")
            if id_field == "supersedes_goal_id" and (
                related.get("work_item_id") != node.get("work_item_id")
            ):
                errors.append(f"{relative}: superseded Goal has a different work item ID")
        if node.get("supersedes_goal_id"):
            errors.extend(
                validate_reopen_trigger_receipts(
                    label=f"{relative}: reopen",
                    root=root,
                    bindings=bindings,
                    references=node.get("reopen_evidence_refs"),
                    superseded_goal_id=node["supersedes_goal_id"],
                    work_item_id=str(node.get("work_item_id", "")),
                )
            )

    for relation_field, _ in relation_fields:
        for goal_id in work_items:
            seen: set[str] = set()
            cursor = goal_id
            while cursor:
                if cursor in seen:
                    errors.append(
                        f"work item {relation_field} graph contains a cycle"
                    )
                    break
                seen.add(cursor)
                related = work_items.get(cursor, {}).get(relation_field)
                cursor = related if isinstance(related, str) else ""
            if errors and errors[-1].endswith("graph contains a cycle"):
                break

    by_work_item_id: dict[str, list[str]] = {}
    for goal_id, node in work_items.items():
        work_item_id = node.get("work_item_id")
        if isinstance(work_item_id, str) and work_item_id:
            by_work_item_id.setdefault(work_item_id, []).append(goal_id)
    for work_item_id, goal_ids in by_work_item_id.items():
        revisions: dict[int, str] = {}
        for goal_id in goal_ids:
            match = re.search(r"-R(\d{3})$", goal_id)
            if not match:
                errors.append(
                    f"work item {work_item_id}: revision Goal ID must end in -RNNN"
                )
                continue
            revision = int(match.group(1))
            if revision in revisions:
                errors.append(
                    f"work item {work_item_id}: duplicate revision number R{revision:03d}"
                )
            revisions[revision] = goal_id
        if sorted(revisions) != list(range(1, len(goal_ids) + 1)):
            errors.append(
                f"work item {work_item_id}: revision sequence must be contiguous"
            )
            continue
        for revision in range(1, len(goal_ids) + 1):
            goal_id = revisions[revision]
            expected_supersedes = revisions.get(revision - 1, "")
            if work_items[goal_id].get("supersedes_goal_id") != expected_supersedes:
                if revision == 1:
                    errors.append(
                        f"work item {work_item_id}: R001 cannot supersede another revision"
                    )
                else:
                    errors.append(
                        f"work item {work_item_id}: R{revision:03d} must supersede "
                        f"R{revision - 1:03d}"
                    )
    return errors


def validate_completed_epic_work_item_coverage(
    nodes: dict[str, dict[str, Any]],
    statuses: dict[str, Any],
    policy_gap_mapping: dict[str, str],
) -> list[str]:
    errors: list[str] = []
    for epic_goal_id in EXPECTED_EPIC_GOAL_IDS.values():
        if statuses.get(epic_goal_id) != "COMPLETE_AT_TARGET":
            continue
        epic = nodes.get(epic_goal_id, {})
        if epic.get("initial_status") == "COMPLETE_AT_TARGET":
            continue
        epic_policy_ids = string_list(epic.get("source_policy_ids"))
        expected_pairs = [
            (policy_id, policy_gap_mapping.get(policy_id))
            for policy_id in epic_policy_ids
        ]
        if (
            any(gap_id is None for _, gap_id in expected_pairs)
            or Counter(string_list(epic.get("gap_ids")))
            != Counter(gap_id for _, gap_id in expected_pairs)
        ):
            errors.append(
                f"{epic_goal_id}: policy/Gap inventory differs from the canonical "
                "IMPLEMENTATION_GAP mapping"
            )
            continue
        final_work_items = [
            node
            for goal_id, node in nodes.items()
            if node.get("goal_kind") == "WORK_ITEM"
            and node.get("parent_goal_id") == epic_goal_id
            and statuses.get(goal_id) != "SUPERSEDED"
        ]
        actual_pairs: list[tuple[str, str]] = []
        for node in final_work_items:
            policies = string_list(node.get("source_policy_ids"))
            gaps = string_list(node.get("gap_ids"))
            if len(policies) == 1 and len(gaps) == 1:
                actual_pairs.append((policies[0], gaps[0]))
        if Counter(actual_pairs) != Counter(expected_pairs):
            errors.append(
                f"{epic_goal_id}: completed EPIC Work Items do not cover every "
                "policy/Gap pair exactly once"
            )
        incomplete_final = [
            node.get("goal_id")
            for node in final_work_items
            if statuses.get(node.get("goal_id")) != "COMPLETE_AT_TARGET"
        ]
        if incomplete_final:
            errors.append(
                f"{epic_goal_id}: completed EPIC has incomplete final Work Items"
            )
    return errors


def validate_runtime_state(
    root: Path,
    checkpoint: dict[str, Any],
    nodes: dict[str, dict[str, Any]],
    goal_path_by_id: dict[str, str],
    backlog: dict[str, Any],
    bindings: dict[str, dict[str, Any]],
    policy_gap_mapping: dict[str, str],
) -> list[str]:
    errors: list[str] = []
    state = checkpoint.get("goal_execution")
    if not isinstance(state, dict):
        return ["checkpoint goal_execution is missing"]
    pointer_fields = (
        "package_id",
        "master_goal_id",
        "current_phase_goal_id",
        "current_epic_goal_id",
        "current_leaf_goal_id",
        "current_leaf_goal_path",
        "current_work_item_id",
        "current_leaf_source",
        "activation_status",
        "package_status",
        "goal_status",
        "static_plan_manifest_path",
        "static_plan_manifest_sha256",
        "static_plan_version",
        "transition_history_anchor_sha256",
        "validation_cutoff_at",
    )
    for field in pointer_fields:
        if not isinstance(state.get(field), str):
            errors.append(f"goal execution {field} must be a string")
    evidence_bindings = dict(bindings)
    for goal_id, node in nodes.items():
        if node.get("goal_kind") != "WORK_ITEM":
            continue
        evidence_bindings[f"WORK_ITEM_SOURCE::{goal_id}"] = {
            "document_id": node.get("materialized_from_document_id"),
            "path": node.get("materialized_from_path"),
            "file_sha256": node.get("materialized_from_sha256"),
        }
    if state.get("package_id") != EXPECTED_PACKAGE_ID:
        errors.append("goal package ID drifted")
    if state.get("schema_version") != "1.0":
        errors.append("goal execution schema version drifted")
    if state.get("master_goal_id") != EXPECTED_MASTER_ID:
        errors.append("goal execution master pointer drifted")
    if state.get("phase_order") != EXPECTED_PHASE_ORDER:
        errors.append("goal execution phase order drifted")
    if state.get("standing_execution_authority") != EXPECTED_STANDING_EXECUTION_AUTHORITY:
        errors.append("standing execution authority drifted")
    if state.get("external_action_required_for") != EXPECTED_EXTERNAL_ACTION_REQUIRED_FOR:
        errors.append("external action boundary drifted")
    if state.get("current_leaf_selection_policy") != EXPECTED_LEAF_SELECTION_POLICY:
        errors.append("current leaf selection policy drifted")
    if state.get("transition_commit_policy") != EXPECTED_TRANSITION_COMMIT_POLICY:
        errors.append("transition commit policy drifted")
    validation_cutoff_at = parse_iso_datetime(
        state.get("validation_cutoff_at")
    )
    if state.get("validation_cutoff_at") != EXPECTED_VALIDATION_CUTOFF_AT:
        errors.append(
            "goal execution validation cutoff differs from the reviewed "
            "checker trust anchor"
        )
    if validation_cutoff_at is None:
        errors.append("goal execution validation cutoff is invalid")
    checkpoint_as_of = parse_iso_date(
        checkpoint.get("metadata", {}).get("as_of")
        if isinstance(checkpoint.get("metadata"), dict)
        else None
    )
    if (
        validation_cutoff_at is not None
        and (
            checkpoint_as_of is None
            or validation_cutoff_at.date() != checkpoint_as_of
        )
    ):
        errors.append(
            "goal execution validation cutoff date differs from checkpoint as-of"
        )
    if state.get("current_leaf_source") not in ALLOWED_CURRENT_LEAF_SOURCES:
        errors.append("unsupported current leaf source")

    statuses = state.get("status_by_goal")
    if not isinstance(statuses, dict) or set(statuses) != set(nodes):
        errors.append("goal runtime status map must cover the exact goal document set")
        statuses = {}
    invalid_statuses = sorted(
        goal_id
        for goal_id, status in statuses.items()
        if status not in ALLOWED_RUNTIME_STATUSES
    )
    if invalid_statuses:
        errors.append(f"invalid runtime goal statuses: {invalid_statuses}")
    blockers_by_goal = state.get("blockers_by_goal")
    if not isinstance(blockers_by_goal, dict):
        errors.append("blockers_by_goal must be an object")
        blockers_by_goal = {}

    activation = state.get("activation_status")
    if activation not in EXPECTED_PACKAGE_STATUS_BY_ACTIVATION:
        errors.append("unsupported activation status")
    elif state.get("package_status") != EXPECTED_PACKAGE_STATUS_BY_ACTIVATION[activation]:
        errors.append("package status is inconsistent with activation status")

    materialized = state.get("materialized_child_goal_ids_by_parent")
    if not isinstance(materialized, dict):
        errors.append("materialized child Goal map is missing")
        materialized = {}
    actual_materialized: dict[str, list[str]] = {}
    for goal_id, node in nodes.items():
        if node.get("goal_kind") != "WORK_ITEM":
            continue
        parent = node.get("parent_goal_id")
        if isinstance(parent, str):
            actual_materialized.setdefault(parent, []).append(goal_id)
    for children in actual_materialized.values():
        children.sort(key=lambda goal_id: nodes[goal_id].get("sequence", 0))
    if materialized != actual_materialized:
        errors.append("materialized Work Item parent-child map differs from Goal files")

    completed = {goal_id for goal_id, status in statuses.items() if status == "COMPLETE_AT_TARGET"}
    evidence = state.get("completion_evidence_by_goal")
    if not isinstance(evidence, dict) or set(evidence) != completed:
        errors.append("completion evidence map must match COMPLETE_AT_TARGET goals")
        evidence = {}
    else:
        for goal_id, refs in evidence.items():
            errors.extend(
                validate_evidence_references(
                    goal_id,
                    refs,
                    root=root,
                    bindings=evidence_bindings,
                )
            )
            node = nodes.get(goal_id, {})
            if node.get("goal_kind") == "WORK_ITEM":
                errors.extend(
                    validate_work_item_completion_receipt(
                        label=goal_id,
                        goal_id=goal_id,
                        references=refs,
                        root=root,
                        bindings=evidence_bindings,
                        node=node,
                        goal_path=goal_path_by_id.get(goal_id, ""),
                    )
                )
    superseded = {goal_id for goal_id, status in statuses.items() if status == "SUPERSEDED"}
    archived_evidence = state.get("archived_completion_evidence_by_goal")
    if not isinstance(archived_evidence, dict) or set(archived_evidence) != superseded:
        errors.append(
            "archived completion evidence map must match SUPERSEDED Work Items"
        )
        archived_evidence = {}
    else:
        for goal_id, refs in archived_evidence.items():
            errors.extend(
                validate_evidence_references(
                    f"{goal_id} archived completion",
                    refs,
                    root=root,
                    bindings=evidence_bindings,
                )
            )
            node = nodes.get(goal_id, {})
            if node.get("goal_kind") == "WORK_ITEM":
                errors.extend(
                    validate_work_item_completion_receipt(
                        label=f"{goal_id} archived completion",
                        goal_id=goal_id,
                        references=refs,
                        root=root,
                        bindings=evidence_bindings,
                        node=node,
                        goal_path=goal_path_by_id.get(goal_id, ""),
                    )
                )

    closed = {
        goal_id
        for goal_id, status in statuses.items()
        if status in {"COMPLETE_AT_TARGET", "SUPERSEDED"}
    }
    for goal_id, status in statuses.items():
        node = nodes.get(goal_id, {})
        dependencies = string_list(node.get("dependencies"))
        if status not in {"PLANNED", "SUPERSEDED"}:
            incomplete = [item for item in dependencies if statuses.get(item) != "COMPLETE_AT_TARGET"]
            if incomplete:
                errors.append(f"{goal_id}: active or completed Goal has incomplete dependencies")
        if status == "COMPLETE_AT_TARGET":
            children = string_list(node.get("child_goal_ids")) + string_list(
                materialized.get(goal_id)
            )
            if any(child not in closed for child in children):
                errors.append(f"{goal_id}: completed parent has an incomplete child Goal")
    errors.extend(
        validate_completed_epic_work_item_coverage(
            nodes,
            statuses,
            policy_gap_mapping,
        )
    )

    backlog_epics = {
        item.get("epic_id"): item
        for item in backlog.get("epics", [])
        if isinstance(item, dict)
    }
    for epic_id, goal_id in EXPECTED_EPIC_GOAL_IDS.items():
        if statuses.get(goal_id) == "COMPLETE_AT_TARGET":
            source = backlog_epics.get(epic_id, {})
            if source.get("current_status") != nodes[goal_id].get("target_completion_level"):
                errors.append(f"{goal_id}: latest backlog does not prove target completion")

    phase_ids = [EXPECTED_PHASE_IDS[item] for item in EXPECTED_PHASE_ORDER]
    incomplete_phases = [
        goal_id for goal_id in phase_ids if statuses.get(goal_id) != "COMPLETE_AT_TARGET"
    ]
    current_phase_id = state.get("current_phase_goal_id")
    if not isinstance(current_phase_id, str):
        current_phase_id = ""
    terminal = not incomplete_phases
    if terminal:
        if activation != "COMPLETE":
            errors.append("all phases complete but package is not COMPLETE")
        if any(
            state.get(field)
            for field in (
                "current_phase_goal_id",
                "current_epic_goal_id",
                "current_leaf_goal_id",
                "current_leaf_goal_path",
                "current_work_item_id",
            )
        ):
            errors.append("complete package must clear all current Goal pointers")
        if statuses.get(EXPECTED_MASTER_ID) != "COMPLETE_AT_TARGET":
            errors.append("complete package master Goal is not complete")
        if state.get("goal_status") != "COMPLETE_AT_TARGET":
            errors.append("complete package goal_status must be COMPLETE_AT_TARGET")
        current_chain: list[str] = []
        current_leaf: dict[str, Any] = {}
    else:
        expected_phase = incomplete_phases[0]
        if current_phase_id != expected_phase:
            errors.append("current phase is not the first incomplete phase")
        current_phase = nodes.get(current_phase_id, {})
        phase_children = string_list(current_phase.get("child_goal_ids"))
        incomplete_children = [child for child in phase_children if child not in closed]
        current_epic_id = state.get("current_epic_goal_id")
        if not isinstance(current_epic_id, str):
            current_epic_id = ""
        if incomplete_children:
            if current_epic_id not in incomplete_children:
                errors.append("current EPIC is not an incomplete child of the current phase")
            elif current_epic_id != incomplete_children[0]:
                bypass = state.get("temporary_dependency_ready_bypass")
                skipped = incomplete_children[:incomplete_children.index(current_epic_id)]
                blocked_leaf_ids = (
                    string_list(bypass.get("blocked_leaf_goal_ids"))
                    if isinstance(bypass, dict)
                    else []
                )
                all_blocked_goal_ids = [*skipped, *blocked_leaf_ids]
                expected_blocker_ids = {
                    blocker.get("blocker_id")
                    for goal_id in all_blocked_goal_ids
                    for blocker in blockers_by_goal.get(goal_id, [])
                    if isinstance(blocker, dict)
                }
                if (
                    not isinstance(bypass, dict)
                    or bypass.get("current_goal_id") != current_epic_id
                    or bypass.get("return_goal_ids") != skipped
                    or len(blocked_leaf_ids) != 1
                    or bypass.get("return_leaf_goal_id") != blocked_leaf_ids[0]
                    or bypass.get("resolution_required") is not True
                    or any(
                        statuses.get(goal_id)
                        not in {"AWAITING_USER", "AWAITING_EXTERNAL", "BLOCKED"}
                        for goal_id in all_blocked_goal_ids
                    )
                    or any(
                        not blockers_by_goal.get(goal_id)
                        for goal_id in all_blocked_goal_ids
                    )
                    or set(string_list(bypass.get("blocker_ids")))
                    != expected_blocker_ids
                ):
                    errors.append("out-of-order EPIC lacks a valid dependency-ready bypass")
            elif state.get("temporary_dependency_ready_bypass") is not None:
                errors.append("in-order EPIC retains a stale dependency-ready bypass")
        elif current_epic_id:
            errors.append("current phase has no EPIC child but current EPIC is set")
        elif state.get("temporary_dependency_ready_bypass") is not None:
            errors.append("phase-direct work retains a stale dependency-ready bypass")

        current_leaf_id = state.get("current_leaf_goal_id")
        if not isinstance(current_leaf_id, str):
            current_leaf_id = ""
        current_leaf = nodes.get(current_leaf_id, {})
        if current_leaf.get("goal_kind") != "WORK_ITEM":
            errors.append("current leaf must be a materialized Work Item Goal")
        expected_parent = current_epic_id or current_phase_id
        if current_leaf.get("parent_goal_id") != expected_parent:
            errors.append("current leaf parent differs from the current EPIC or phase")
        bypass = state.get("temporary_dependency_ready_bypass")
        if isinstance(bypass, dict) and current_epic_id == bypass.get("current_goal_id"):
            if current_leaf.get("predecessor_goal_id") != bypass.get(
                "return_leaf_goal_id"
            ):
                errors.append("bypass Work Item predecessor differs from return leaf")
        current_leaf_path = state.get("current_leaf_goal_path")
        if goal_path_by_id.get(current_leaf_id) != current_leaf_path:
            errors.append("current leaf Goal ID/path mapping is inconsistent")
        if state.get("current_work_item_id") != current_leaf.get("work_item_id"):
            errors.append("goal current work item differs from current leaf")

        current_chain = [EXPECTED_MASTER_ID, current_phase_id]
        if current_epic_id:
            current_chain.append(current_epic_id)
        current_chain.append(current_leaf_id)

        if activation == "READY_NOT_ACTIVATED":
            if any(statuses.get(goal_id) != "READY" for goal_id in current_chain):
                errors.append("prepared package READY ancestor/leaf chain is inconsistent")
            ready_ids = {
                goal_id for goal_id, status in statuses.items() if status == "READY"
            }
            if ready_ids != set(current_chain):
                errors.append("prepared package has stale or missing READY Goal branches")
            if any(status == "IN_PROGRESS" for status in statuses.values()):
                errors.append("not-activated package cannot have an IN_PROGRESS goal")
            if state.get("goal_status") != "READY":
                errors.append("not-activated package goal status must be READY")
        elif activation == "ACTIVE":
            for goal_id in current_chain[:-1]:
                if statuses.get(goal_id) != "IN_PROGRESS":
                    errors.append("active ancestor Goal chain is inconsistent")
                    break
            leaf_status = statuses.get(current_leaf_id)
            if leaf_status not in {
                "IN_PROGRESS",
                "AWAITING_USER",
                "AWAITING_EXTERNAL",
                "BLOCKED",
            }:
                errors.append("active current leaf has an invalid runtime status")
            if state.get("goal_status") != leaf_status:
                errors.append("active package goal status differs from current leaf")
            active_ids = {
                goal_id for goal_id, status in statuses.items() if status == "IN_PROGRESS"
            }
            expected_active = set(current_chain[:-1])
            if leaf_status == "IN_PROGRESS":
                expected_active.add(current_leaf_id)
            if active_ids != expected_active:
                errors.append("multiple or missing active Goal branches")
            ready_ids = {
                goal_id for goal_id, status in statuses.items() if status == "READY"
            }
            if ready_ids:
                errors.append("active package cannot retain stale READY Goal branches")
        elif activation == "COMPLETE":
            errors.append("non-terminal package cannot be COMPLETE")

        source_role = state.get("current_leaf_source")
        if current_leaf.get("materialized_from_role") != source_role:
            errors.append("current leaf materialization role differs from runtime source")
        if source_role == "IMPLEMENTATION_BACKLOG":
            binding = bindings.get("IMPLEMENTATION_BACKLOG", {})
            source_path = binding.get("path")
            if current_leaf.get("materialized_from_path") != source_path:
                errors.append("current leaf was not materialized from latest backlog")
            safe_source_path = resolve_safe_repo_file(root, source_path)
            if safe_source_path is None:
                errors.append("current leaf latest backlog path is unsafe or missing")
            elif current_leaf.get("materialized_from_sha256") != sha256_file(
                safe_source_path
            ):
                errors.append("current leaf latest backlog SHA-256 differs")
            if current_leaf.get("materialized_from_document_id") != binding.get("document_id"):
                errors.append("current leaf latest backlog document ID differs")

            next_action = backlog.get("next_single_action")
            checkpoint_work = checkpoint.get("current_work")
            if not isinstance(next_action, dict):
                errors.append("latest backlog next_single_action is missing")
                next_action = {}
            if not isinstance(checkpoint_work, dict):
                errors.append("checkpoint current_work is missing")
                checkpoint_work = {}
            expected_values = {
                "work_item_id": current_leaf.get("work_item_id"),
                "source_policy_id": string_list(current_leaf.get("source_policy_ids"))[0]
                if string_list(current_leaf.get("source_policy_ids"))
                else None,
                "gap_id": string_list(current_leaf.get("gap_ids"))[0]
                if string_list(current_leaf.get("gap_ids"))
                else None,
            }
            for field, value in expected_values.items():
                if next_action.get(field) != value:
                    errors.append(f"latest backlog current {field} differs from current leaf")
            if checkpoint_work.get("work_item_id") != current_leaf.get("work_item_id"):
                errors.append("checkpoint current_work work item differs from current leaf")
            current_epic = next(
                (
                    epic_id
                    for epic_id, goal_id in EXPECTED_EPIC_GOAL_IDS.items()
                    if goal_id == current_epic_id
                ),
                None,
            )
            if next_action.get("epic_id") != current_epic:
                errors.append("latest backlog current EPIC differs from current Goal")

    approved = checkpoint.get("approved_state", {})
    boundary = checkpoint.get("verification_boundary", {})
    formal_total = approved.get("formal_test_count")
    formal_not_run = approved.get("formal_test_not_run_count")
    remaining_gates = approved.get("remaining_gate_count")
    remaining_gate_ids = string_list(boundary.get("remaining_gate_ids"))
    if formal_total != 279 or boundary.get("formal_test_total") != 279:
        errors.append("formal test inventory total differs from 279")
    if (
        not isinstance(formal_not_run, int)
        or not 0 <= formal_not_run <= 279
        or boundary.get("formal_test_not_run_count") != formal_not_run
    ):
        errors.append("formal test NOT_RUN count is invalid or inconsistent")
    if (
        not isinstance(remaining_gates, int)
        or not 0 <= remaining_gates <= 5
        or len(remaining_gate_ids) != remaining_gates
        or len(set(remaining_gate_ids)) != len(remaining_gate_ids)
        or not set(remaining_gate_ids).issubset(EXPECTED_RELEASE_GATE_IDS)
    ):
        errors.append("remaining gate count is invalid or inconsistent")
    if approved.get("remaining_gates_waived") is not False:
        errors.append("remaining gates must not be waived")

    phase_a_complete = statuses.get(EXPECTED_PHASE_IDS["A"]) == "COMPLETE_AT_TARGET"
    phase_b_complete = statuses.get(EXPECTED_PHASE_IDS["B"]) == "COMPLETE_AT_TARGET"
    phase_c_complete = statuses.get(EXPECTED_PHASE_IDS["C"]) == "COMPLETE_AT_TARGET"
    phase_d_complete = statuses.get(EXPECTED_PHASE_IDS["D"]) == "COMPLETE_AT_TARGET"
    if not phase_a_complete:
        if formal_not_run != 279:
            errors.append("Phase A cannot promote formal test NOT_RUN results")
        if remaining_gates != 5:
            errors.append("Phase A cannot close the five release gates")
        if set(remaining_gate_ids) != EXPECTED_RELEASE_GATE_IDS:
            errors.append("Phase A release gate ID set drifted")
        if boundary.get("all_remaining_gate_status") != "NOT_RUN":
            errors.append("Phase A release gate aggregate status drifted")
        if boundary.get("actual_device_test_status") != "NOT_RUN":
            errors.append("Phase A cannot promote actual-device test status")
        if boundary.get("formal_test_pass_claimed") is not False:
            errors.append("Phase A cannot claim formal test PASS")
        if approved.get("release_status") != "NOT_ELIGIBLE":
            errors.append("Phase A cannot promote release eligibility")
        if boundary.get("release_eligible") is not False:
            errors.append("Phase A cannot promote release eligibility")
    elif not phase_b_complete:
        if formal_not_run != 279:
            errors.append(
                "formal test boundary cannot advance before Phase B completes"
            )
        if remaining_gates != 5:
            errors.append(
                "release gate boundary cannot advance before Phase B completes"
            )
        if set(remaining_gate_ids) != EXPECTED_RELEASE_GATE_IDS:
            errors.append(
                "release gate ID set cannot change before Phase B completes"
            )
        if boundary.get("all_remaining_gate_status") != "NOT_RUN":
            errors.append(
                "release gate aggregate cannot advance before Phase B completes"
            )
        if boundary.get("actual_device_test_status") != "NOT_RUN":
            errors.append(
                "actual-device status cannot advance before Phase B completes"
            )
        if boundary.get("formal_test_pass_claimed") is not False:
            errors.append(
                "formal test PASS cannot be claimed before Phase B completes"
            )
        if boundary.get("approved_production_profile_count") != 0:
            errors.append(
                "production profile approval cannot advance before Phase B completes"
            )
        if approved.get("release_status") != "NOT_ELIGIBLE":
            errors.append("release cannot be eligible before Phase B completes")
        if boundary.get("release_eligible") is not False:
            errors.append("release eligibility cannot be true before Phase B completes")
    else:
        if formal_not_run != 0:
            errors.append("completed Phase B cannot leave formal tests NOT_RUN")
        if remaining_gates != 0:
            errors.append("completed Phase B cannot leave release gates open")
        if boundary.get("actual_device_test_status") != "PASS":
            errors.append("completed Phase B lacks actual-device PASS")
        approved_profiles = boundary.get("approved_production_profile_count")
        if not isinstance(approved_profiles, int) or approved_profiles < 1:
            errors.append("completed Phase B lacks an approved production device profile")
        if boundary.get("formal_test_pass_claimed") is not True:
            errors.append("completed Phase B lacks formal test PASS")
        if boundary.get("all_remaining_gate_status") != "CLOSED":
            errors.append("completed Phase B gate aggregate status is not CLOSED")
        if approved.get("release_status") != "ELIGIBLE":
            errors.append("completed Phase B lacks release eligibility approval")
        if boundary.get("release_eligible") is not True:
            errors.append("completed Phase B release eligibility is false")

    verification_refs = state.get("verification_evidence_refs")
    progressed_verification = (
        formal_not_run != 279
        or remaining_gates != 5
        or boundary.get("actual_device_test_status") != "NOT_RUN"
        or boundary.get("formal_test_pass_claimed") is not False
        or boundary.get("release_eligible") is not False
    )
    if progressed_verification:
        errors.extend(
            validate_evidence_references(
                "verification boundary",
                verification_refs,
                root=root,
                bindings=evidence_bindings,
            )
        )
        if phase_b_complete:
            required_phase_b_roles = set(
                REQUIRED_PHASE_B_EVIDENCE_STATUS
            )
            phase_b_refs = set(
                string_list(
                    evidence.get(EXPECTED_PHASE_IDS["B"], [])
                )
            )
            epic_12_refs = set(
                string_list(
                    evidence.get(
                        EXPECTED_EPIC_GOAL_IDS["EPIC-12"],
                        [],
                    )
                )
            )
            if phase_b_refs != required_phase_b_roles:
                errors.append(
                    "completed Phase B completion evidence differs from exact "
                    "formal receipt roles"
                )
            if epic_12_refs != required_phase_b_roles:
                errors.append(
                    "completed EPIC-12 completion evidence differs from exact "
                    "formal receipt roles"
                )
            if set(string_list(verification_refs)) != required_phase_b_roles:
                errors.append(
                    "completed Phase B runtime verification receipt set differs"
                )
            errors.extend(
                validate_phase_b_evidence_receipts(
                    root,
                    bindings,
                    evidence.get(EXPECTED_PHASE_IDS["B"], []),
                    goal_path_by_id,
                )
            )
    elif verification_refs != []:
        errors.append("initial verification boundary must have no promoted evidence refs")

    if phase_c_complete:
        errors.extend(
            validate_phase_c_evidence_receipts(
                root,
                bindings,
                evidence.get(EXPECTED_PHASE_IDS["C"], []),
                goal_path_by_id,
            )
        )
    if phase_d_complete:
        phase_d_refs = evidence.get(EXPECTED_PHASE_IDS["D"], [])
        errors.extend(
            validate_phase_d_evidence_receipts(
                root,
                bindings,
                phase_d_refs,
                goal_path_by_id,
            )
        )
        master_refs = set(string_list(evidence.get(EXPECTED_MASTER_ID, [])))
        if not set(REQUIRED_PHASE_D_EVIDENCE_STATUS).issubset(master_refs):
            errors.append("completed Master lacks Phase D handover/closure receipt roles")

    pending_questions = state.get("pending_questions")
    if not isinstance(pending_questions, list):
        errors.append("pending_questions must be a list")
        pending_questions = []
    current_leaf_pointer = state.get("current_leaf_goal_id")
    if not isinstance(current_leaf_pointer, str):
        current_leaf_pointer = ""
    current_leaf_status = statuses.get(current_leaf_pointer)
    waiting_statuses = {"AWAITING_USER", "AWAITING_EXTERNAL", "BLOCKED"}
    active_blocker_ids: set[str] = set()
    blocker_count = 0
    for blocked_goal_id, blocker_records in blockers_by_goal.items():
        if blocked_goal_id not in statuses:
            errors.append(f"blocker map references unknown Goal: {blocked_goal_id}")
            continue
        if statuses.get(blocked_goal_id) not in waiting_statuses:
            errors.append(f"Goal with blockers is not waiting or blocked: {blocked_goal_id}")
        if not isinstance(blocker_records, list) or not blocker_records:
            errors.append(f"blocker list is empty or malformed: {blocked_goal_id}")
            continue
        blocker_count += len(blocker_records)
        for index, blocker in enumerate(blocker_records):
            label = f"blocker {blocked_goal_id}[{index}]"
            if not isinstance(blocker, dict):
                errors.append(f"{label}: record must be an object")
                continue
            blocker_id = blocker.get("blocker_id")
            condition_code = blocker.get("condition_code")
            owner = blocker.get("owner")
            if not isinstance(blocker_id, str) or not blocker_id:
                errors.append(f"{label}: blocker_id is missing")
            elif blocker_id in active_blocker_ids:
                errors.append(f"{label}: blocker_id is duplicated")
            else:
                active_blocker_ids.add(blocker_id)
            if blocker.get("blocks_goal_id") != blocked_goal_id:
                errors.append(f"{label}: blocks_goal_id differs")
            if not is_iso_datetime(blocker.get("created_at")):
                errors.append(f"{label}: created_at is invalid")
            return_leaf_goal_id = blocker.get("return_leaf_goal_id")
            if (
                not isinstance(return_leaf_goal_id, str)
                or return_leaf_goal_id not in statuses
            ):
                errors.append(f"{label}: return_leaf_goal_id is invalid")
            snapshot_hash = blocker.get("blocker_snapshot_sha256")
            if (
                not isinstance(snapshot_hash, str)
                or not SHA256_PATTERN.fullmatch(snapshot_hash)
                or snapshot_hash != blocker_snapshot_sha256(blocker)
            ):
                errors.append(f"{label}: blocker snapshot SHA-256 differs")
            if condition_code not in EXPECTED_QUESTION_CODES + EXPECTED_STOP_CODES:
                errors.append(f"{label}: condition_code is invalid")
            expected_owner = (
                "USER"
                if condition_code in USER_WAIT_CODES
                else "EXTERNAL"
                if condition_code in EXTERNAL_WAIT_CODES
                else "BLOCKED"
            )
            if owner != expected_owner:
                errors.append(f"{label}: owner differs from condition code")
            expected_status = {
                "USER": "AWAITING_USER",
                "EXTERNAL": "AWAITING_EXTERNAL",
                "BLOCKED": "BLOCKED",
            }[expected_owner]
            if statuses.get(blocked_goal_id) != expected_status:
                errors.append(f"{label}: Goal waiting status differs from blocker owner")
            if not isinstance(blocker.get("prompt"), str) or not blocker.get("prompt"):
                errors.append(f"{label}: prompt is missing")
    waiting_goal_ids = {
        goal_id for goal_id, status in statuses.items() if status in waiting_statuses
    }
    if waiting_goal_ids != set(blockers_by_goal):
        errors.append("every waiting/blocked Goal must have exactly one blocker-map entry")
    if state.get("open_question_count") != blocker_count:
        errors.append("open question count is inconsistent")
    expected_pending = blockers_by_goal.get(current_leaf_pointer, [])
    if pending_questions != expected_pending:
        errors.append("pending_questions must equal blockers for the current leaf")
    if pending_questions and current_leaf_status not in waiting_statuses:
        errors.append("pending questions require a waiting or blocked current leaf")

    resolution_history = state.get("blocker_resolution_history")
    if not isinstance(resolution_history, list):
        errors.append("blocker_resolution_history must be a list")
        resolution_history = []
    resolved_ids: set[str] = set()
    runtime_resolution_ids: list[str] = []
    resolutions_by_id: dict[str, dict[str, Any]] = {}
    for index, resolution in enumerate(resolution_history):
        label = f"blocker resolution {index}"
        if not isinstance(resolution, dict):
            errors.append(f"{label}: record must be an object")
            continue
        blocker_id = resolution.get("blocker_id")
        goal_id = resolution.get("goal_id")
        receipt_ref = resolution.get("resolution_receipt_ref")
        condition_code = resolution.get("condition_code")
        owner = resolution.get("owner")
        blocker_event_sha256 = resolution.get("blocker_event_sha256")
        blocker_snapshot_hash = resolution.get("blocker_snapshot_sha256")
        if not isinstance(blocker_id, str) or not blocker_id:
            errors.append(f"{label}: blocker_id is missing")
        elif blocker_id in resolved_ids:
            errors.append(f"{label}: blocker_id is duplicated")
        else:
            resolved_ids.add(blocker_id)
            runtime_resolution_ids.append(blocker_id)
            resolutions_by_id[blocker_id] = resolution
        if not isinstance(goal_id, str) or goal_id not in statuses:
            errors.append(f"{label}: goal_id is invalid")
        if condition_code not in EXPECTED_QUESTION_CODES + EXPECTED_STOP_CODES:
            errors.append(f"{label}: condition_code is invalid")
        if owner not in {"USER", "EXTERNAL", "BLOCKED"}:
            errors.append(f"{label}: owner is invalid")
        if (
            not isinstance(blocker_event_sha256, str)
            or not SHA256_PATTERN.fullmatch(blocker_event_sha256)
        ):
            errors.append(f"{label}: blocker event SHA-256 is invalid")
        if (
            not isinstance(blocker_snapshot_hash, str)
            or not SHA256_PATTERN.fullmatch(blocker_snapshot_hash)
        ):
            errors.append(f"{label}: blocker snapshot SHA-256 is invalid")
        errors.extend(
            validate_blocker_resolution_receipt(
                label=label,
                reference=receipt_ref,
                root=root,
                bindings=evidence_bindings,
                blocker_id=blocker_id,
                goal_id=goal_id,
                condition_code=condition_code,
                owner=owner,
                blocker_event_sha256=blocker_event_sha256,
                blocker_snapshot_hash=blocker_snapshot_hash,
            )
        )
    if active_blocker_ids & resolved_ids:
        errors.append("resolved blocker cannot remain active")

    history = state.get("transition_history")
    if not isinstance(history, list) or not history:
        errors.append("goal transition history is missing")
    else:
        valid_history = [item for item in history if isinstance(item, dict)]
        if len(valid_history) != len(history):
            errors.append("goal transition history entries must be objects")
        else:
            initial_event_hash = valid_history[0].get("event_sha256")
            if (
                EXPECTED_INITIAL_TRANSITION_EVENT_SHA256
                == "__INITIAL_TRANSITION_EVENT_SHA256__"
            ):
                errors.append("initial transition event checker hash is not finalized")
            elif initial_event_hash != EXPECTED_INITIAL_TRANSITION_EVENT_SHA256:
                errors.append(
                    "initial transition event differs from the checker trust anchor"
                )
            sequences = [item.get("sequence") for item in valid_history]
            if sequences != list(range(1, len(valid_history) + 1)):
                errors.append("goal transition history sequence is not contiguous")
            previous_hash = ""
            event_ids: set[str] = set()
            replay_statuses: dict[str, str] = {}
            replay_pointers: dict[str, str] = {}
            replay_blockers: dict[str, Any] = {}
            replay_resolution_ids: list[str] = []
            replay_bypass: dict[str, Any] | None = None
            recorded_blocker_event_by_id: dict[str, str] = {}
            recorded_blocker_event_time_by_id: dict[str, datetime] = {}
            resolved_blocker_event_time_by_id: dict[str, datetime] = {}
            recorded_blocker_snapshot_by_id: dict[str, str] = {}
            recorded_blocker_by_id: dict[str, dict[str, Any]] = {}
            completion_event_by_goal_id: dict[str, dict[str, Any]] = {}
            execution_start_event_by_goal_id: dict[str, dict[str, Any]] = {}
            consumed_resume_resolution_ids: set[str] = set()
            activated = False
            completed = False
            previous_event_occurred_at: datetime | None = None
            for index, event in enumerate(valid_history):
                label = f"goal transition event {index + 1}"
                event_id = event.get("event_id")
                event_type = event.get("event_type")
                if not isinstance(event_id, str) or not event_id:
                    errors.append(f"{label}: event_id is missing")
                elif event_id in event_ids:
                    errors.append(f"{label}: event_id is duplicated")
                else:
                    event_ids.add(event_id)
                if event_type not in ALLOWED_TRANSITION_EVENT_TYPES:
                    errors.append(f"{label}: unsupported event_type")
                occurred_on = parse_iso_date(event.get("occurred_on"))
                occurred_at = parse_iso_datetime(event.get("occurred_at"))
                if occurred_on is None:
                    errors.append(f"{label}: occurred_on is invalid")
                if (
                    occurred_at is None
                    or occurred_on is None
                    or occurred_at.date() != occurred_on
                ):
                    errors.append(
                        f"{label}: occurred_at is invalid or differs from occurred_on"
                    )
                elif (
                    previous_event_occurred_at is not None
                    and occurred_at <= previous_event_occurred_at
                ):
                    errors.append(
                        f"{label}: occurred_at is not strictly increasing"
                    )
                elif (
                    validation_cutoff_at is not None
                    and occurred_at > validation_cutoff_at
                ):
                    errors.append(
                        f"{label}: occurred_at exceeds the reviewed validation cutoff"
                    )
                if occurred_at is not None:
                    previous_event_occurred_at = occurred_at
                if event.get("previous_event_sha256") != previous_hash:
                    errors.append(f"{label} previous hash differs")
                actual_hash = event_sha256(event)
                if event.get("event_sha256") != actual_hash:
                    errors.append(f"{label} hash is invalid")
                previous_hash = event.get("event_sha256", "")
                if event.get("static_plan_manifest_sha256") != state.get(
                    "static_plan_manifest_sha256"
                ):
                    errors.append(f"{label}: static-plan anchor differs")
                previous_leaf_id = event.get("previous_leaf_goal_id")
                current_event_leaf_id = event.get("current_leaf_goal_id")
                previous_leaf_hash = event.get("previous_leaf_content_sha256")
                current_leaf_hash = event.get("current_leaf_content_sha256")
                if not isinstance(previous_leaf_id, str):
                    errors.append(f"{label}: previous_leaf_goal_id must be a string")
                    previous_leaf_id = ""
                if not isinstance(current_event_leaf_id, str):
                    errors.append(f"{label}: current_leaf_goal_id must be a string")
                    current_event_leaf_id = ""
                expected_previous_leaf = replay_pointers.get(
                    "current_leaf_goal_id",
                    "",
                )
                if index > 0 and previous_leaf_id != expected_previous_leaf:
                    errors.append(f"{label}: previous leaf breaks event continuity")
                for leaf_id, leaf_hash, field_name in (
                    (
                        previous_leaf_id,
                        previous_leaf_hash,
                        "previous_leaf_content_sha256",
                    ),
                    (
                        current_event_leaf_id,
                        current_leaf_hash,
                        "current_leaf_content_sha256",
                    ),
                ):
                    if not leaf_id:
                        if leaf_hash not in ("", None):
                            errors.append(f"{label}: {field_name} must be empty")
                        continue
                    leaf_path = goal_path_by_id.get(leaf_id)
                    safe_leaf_path = resolve_safe_repo_file(root, leaf_path)
                    if safe_leaf_path is None:
                        errors.append(f"{label}: referenced leaf Goal is missing: {leaf_id}")
                    elif leaf_hash != sha256_file(safe_leaf_path):
                        errors.append(f"{label}: {field_name} differs")
                from_status = event.get("from_status")
                to_status = event.get("to_status")
                if not isinstance(from_status, str) or not isinstance(to_status, str):
                    errors.append(f"{label}: from_status/to_status must be strings")
                    from_status = ""
                    to_status = ""
                expected_from_status = (
                    replay_statuses.get(previous_leaf_id, "")
                    if previous_leaf_id
                    else ""
                )
                if from_status != expected_from_status:
                    errors.append(f"{label}: from_status breaks state replay")

                prior_statuses = dict(replay_statuses)
                status_changes = event.get("status_changes")
                if not isinstance(status_changes, dict):
                    errors.append(f"{label}: status_changes must be an object")
                    status_changes = {}
                for goal_id, changed_status in status_changes.items():
                    if not isinstance(goal_id, str) or goal_id not in nodes:
                        errors.append(f"{label}: status_changes references unknown Goal")
                        continue
                    if changed_status not in ALLOWED_RUNTIME_STATUSES:
                        errors.append(f"{label}: status_changes contains invalid status")
                        continue
                    previous_goal_status = replay_statuses.get(goal_id)
                    if (
                        index > 0
                        and previous_goal_status is not None
                        and changed_status == previous_goal_status
                    ):
                        errors.append(f"{label}: status_changes contains a no-op")
                    if (
                        previous_goal_status
                        in {"COMPLETE_AT_TARGET", "SUPERSEDED"}
                        and changed_status != previous_goal_status
                        and not (
                            event_type == "GOAL_SUPERSEDED"
                            and previous_goal_status == "COMPLETE_AT_TARGET"
                            and changed_status == "SUPERSEDED"
                        )
                    ):
                        errors.append(
                            f"{label}: closed Goal status cannot be rewritten"
                        )
                    if (
                        changed_status == "COMPLETE_AT_TARGET"
                        and event_type
                        not in {"PACKAGE_PREPARED", "GOAL_COMPLETED", "PACKAGE_COMPLETED"}
                    ):
                        errors.append(
                            f"{label}: completion status requires a completion event"
                        )
                    if (
                        changed_status == "SUPERSEDED"
                        and event_type != "GOAL_SUPERSEDED"
                    ):
                        errors.append(
                            f"{label}: SUPERSEDED status requires GOAL_SUPERSEDED"
                        )
                    replay_statuses[goal_id] = changed_status
                    if (
                        changed_status == "IN_PROGRESS"
                        and previous_goal_status != "IN_PROGRESS"
                    ):
                        execution_start_event_by_goal_id[goal_id] = event
                        predecessor_goal_id = (
                            nodes.get(goal_id, {}).get(
                                "predecessor_goal_id"
                            )
                            if nodes.get(goal_id, {}).get("goal_kind")
                            == "WORK_ITEM"
                            else None
                        )
                        if isinstance(predecessor_goal_id, str) and (
                            predecessor_goal_id
                        ):
                            predecessor_binding = evidence_bindings.get(
                                "WORK_ITEM_COMPLETION::"
                                f"{predecessor_goal_id}",
                                {},
                            )
                            predecessor_receipt_path = (
                                resolve_safe_repo_file(
                                    root,
                                    predecessor_binding.get("path"),
                                )
                            )
                            if predecessor_receipt_path is not None:
                                try:
                                    predecessor_receipt = load_json(
                                        predecessor_receipt_path
                                    )
                                except (
                                    OSError,
                                    ValueError,
                                    json.JSONDecodeError,
                                ):
                                    predecessor_receipt = {}
                                predecessor_generated_at = (
                                    parse_iso_datetime(
                                        predecessor_receipt.get(
                                            "generated_at"
                                        )
                                    )
                                )
                                start_occurred_at = parse_iso_datetime(
                                    event.get("occurred_at")
                                )
                                if (
                                    predecessor_generated_at is not None
                                    and start_occurred_at is not None
                                    and predecessor_generated_at
                                    > start_occurred_at
                                ):
                                    errors.append(
                                        f"{label}: successor execution starts "
                                        "before predecessor evidence is final"
                                    )
                introduced_goal_ids = {
                    goal_id
                    for goal_id in status_changes
                    if goal_id not in prior_statuses
                }
                if index > 0:
                    if len(introduced_goal_ids) > 1:
                        errors.append(
                            f"{label}: only one dynamic Work Item may be introduced"
                        )
                    if introduced_goal_ids and event_type not in {
                        "GOAL_COMPLETED",
                        "GOAL_SUPERSEDED",
                        "GOAL_TRANSITION",
                        "GOAL_BYPASSED",
                    }:
                        errors.append(
                            f"{label}: dynamic Work Item was introduced by "
                            "an unsupported event"
                        )
                    for introduced_goal_id in introduced_goal_ids:
                        introduced_node = nodes.get(introduced_goal_id, {})
                        introduced_status = status_changes.get(introduced_goal_id)
                        allowed_direct_status = (
                            introduced_goal_id == current_event_leaf_id
                            and event_type
                            in {"GOAL_TRANSITION", "GOAL_BYPASSED"}
                            and introduced_status in {"READY", "IN_PROGRESS"}
                        )
                        if (
                            introduced_node.get("goal_kind") != "WORK_ITEM"
                            or introduced_node.get("predecessor_goal_id")
                            != previous_leaf_id
                            or (
                                introduced_status != "PLANNED"
                                and not allowed_direct_status
                            )
                        ):
                            errors.append(
                                f"{label}: dynamic Goal introduction contract differs"
                            )
                        if (
                            introduced_node.get("supersedes_goal_id")
                            and event_type != "GOAL_SUPERSEDED"
                        ):
                            errors.append(
                                f"{label}: reopen successor must first be "
                                "introduced by GOAL_SUPERSEDED"
                            )
                introduced_planned_ids = {
                    goal_id
                    for goal_id in introduced_goal_ids
                    if status_changes.get(goal_id) == "PLANNED"
                }
                effective_status_changes = {
                    goal_id: changed_status
                    for goal_id, changed_status in status_changes.items()
                    if goal_id not in introduced_planned_ids
                }

                pointers_after = event.get("pointers_after")
                if not isinstance(pointers_after, dict) or set(
                    pointers_after
                ) != set(TRANSITION_POINTER_FIELDS):
                    errors.append(f"{label}: pointers_after field set differs")
                    pointers_after = {}
                elif any(
                    not isinstance(pointers_after.get(field), str)
                    for field in TRANSITION_POINTER_FIELDS
                ):
                    errors.append(f"{label}: pointers_after values must be strings")
                replay_pointers = {
                    field: pointers_after.get(field, "")
                    for field in TRANSITION_POINTER_FIELDS
                }
                if replay_pointers.get("current_leaf_goal_id") != current_event_leaf_id:
                    errors.append(f"{label}: event leaf differs from pointer snapshot")
                expected_to_status = (
                    replay_statuses.get(current_event_leaf_id, "")
                    if current_event_leaf_id
                    else replay_pointers.get("goal_status", "")
                )
                if to_status != expected_to_status:
                    errors.append(f"{label}: to_status breaks state replay")
                pointer_activation = replay_pointers.get("activation_status")
                if (
                    pointer_activation not in EXPECTED_PACKAGE_STATUS_BY_ACTIVATION
                    or replay_pointers.get("package_status")
                    != EXPECTED_PACKAGE_STATUS_BY_ACTIVATION.get(
                        pointer_activation
                    )
                ):
                    errors.append(f"{label}: pointer activation/package state differs")
                if current_event_leaf_id:
                    pointer_leaf = nodes.get(current_event_leaf_id, {})
                    pointer_parent_id = pointer_leaf.get("parent_goal_id")
                    pointer_parent = nodes.get(pointer_parent_id, {})
                    if pointer_parent.get("goal_kind") == "EPIC":
                        expected_epic_pointer = pointer_parent_id
                        expected_phase_pointer = pointer_parent.get(
                            "parent_goal_id"
                        )
                    else:
                        expected_epic_pointer = ""
                        expected_phase_pointer = pointer_parent_id
                    if (
                        replay_pointers.get("current_leaf_goal_path")
                        != goal_path_by_id.get(current_event_leaf_id)
                        or replay_pointers.get("current_work_item_id")
                        != pointer_leaf.get("work_item_id")
                        or replay_pointers.get("current_leaf_source")
                        != pointer_leaf.get("materialized_from_role")
                        or replay_pointers.get("current_epic_goal_id")
                        != expected_epic_pointer
                        or replay_pointers.get("current_phase_goal_id")
                        != expected_phase_pointer
                        or replay_pointers.get("goal_status")
                        != replay_statuses.get(current_event_leaf_id)
                    ):
                        errors.append(f"{label}: pointer snapshot does not match leaf")
                elif event_type == "PACKAGE_COMPLETED" and any(
                    replay_pointers.get(field)
                    for field in (
                        "current_phase_goal_id",
                        "current_epic_goal_id",
                        "current_leaf_goal_id",
                        "current_leaf_goal_path",
                        "current_work_item_id",
                    )
                ):
                    errors.append(f"{label}: completed pointer snapshot is not clear")

                blockers_after = event.get("blockers_after")
                if not isinstance(blockers_after, dict):
                    errors.append(f"{label}: blockers_after must be an object")
                    blockers_after = {}
                previous_blockers_snapshot = replay_blockers
                previous_blocker_records, previous_duplicate_blocker_ids = (
                    blocker_records_by_id(previous_blockers_snapshot)
                )
                current_blocker_records, current_duplicate_blocker_ids = (
                    blocker_records_by_id(blockers_after)
                )
                previous_blocker_ids = set(previous_blocker_records)
                current_blocker_ids = set(current_blocker_records)
                if previous_duplicate_blocker_ids or current_duplicate_blocker_ids:
                    errors.append(f"{label}: blocker snapshot contains duplicate IDs")
                for blocked_goal_id, blocker_rows in blockers_after.items():
                    if (
                        not isinstance(blocked_goal_id, str)
                        or blocked_goal_id not in nodes
                        or not isinstance(blocker_rows, list)
                        or not blocker_rows
                    ):
                        errors.append(f"{label}: blockers_after entry is malformed")
                        continue
                    for blocker in blocker_rows:
                        if not isinstance(blocker, dict):
                            errors.append(f"{label}: blocker snapshot is malformed")
                            continue
                        blocker_id = blocker.get("blocker_id")
                        condition_code = blocker.get("condition_code")
                        owner = blocker.get("owner")
                        expected_owner = (
                            "USER"
                            if condition_code in USER_WAIT_CODES
                            else "EXTERNAL"
                            if condition_code in EXTERNAL_WAIT_CODES
                            else "BLOCKED"
                        )
                        expected_waiting_status = {
                            "USER": "AWAITING_USER",
                            "EXTERNAL": "AWAITING_EXTERNAL",
                            "BLOCKED": "BLOCKED",
                        }[expected_owner]
                        if (
                            not isinstance(blocker_id, str)
                            or not blocker_id
                            or blocker.get("blocks_goal_id") != blocked_goal_id
                            or condition_code
                            not in EXPECTED_QUESTION_CODES + EXPECTED_STOP_CODES
                            or owner != expected_owner
                            or replay_statuses.get(blocked_goal_id)
                            != expected_waiting_status
                            or not is_iso_datetime(blocker.get("created_at"))
                            or not isinstance(blocker.get("return_leaf_goal_id"), str)
                            or blocker.get("return_leaf_goal_id") not in nodes
                            or not isinstance(blocker.get("prompt"), str)
                            or not blocker.get("prompt")
                            or blocker.get("blocker_snapshot_sha256")
                            != blocker_snapshot_sha256(blocker)
                        ):
                            errors.append(f"{label}: blocker snapshot contract differs")
                common_blocker_ids = previous_blocker_ids & current_blocker_ids
                if any(
                    previous_blocker_records[blocker_id]
                    != current_blocker_records[blocker_id]
                    for blocker_id in common_blocker_ids
                ):
                    errors.append(f"{label}: existing blocker snapshot was rewritten")
                replay_blockers = blockers_after

                resolution_ids_after = event.get("blocker_resolution_ids_after")
                if (
                    not isinstance(resolution_ids_after, list)
                    or any(
                        not isinstance(item, str) or not item
                        for item in resolution_ids_after
                    )
                    or len(resolution_ids_after) != len(set(resolution_ids_after))
                ):
                    errors.append(
                        f"{label}: blocker_resolution_ids_after is invalid"
                    )
                    resolution_ids_after = []
                if not set(replay_resolution_ids).issubset(resolution_ids_after):
                    errors.append(f"{label}: resolved blocker history was removed")
                previous_resolution_ids = set(replay_resolution_ids)
                replay_resolution_ids = resolution_ids_after
                added_resolution_ids = (
                    set(resolution_ids_after) - previous_resolution_ids
                )
                if (
                    event_type != "BLOCKER_RESOLVED"
                    and added_resolution_ids
                ):
                    errors.append(
                        f"{label}: blocker resolution IDs changed outside "
                        "BLOCKER_RESOLVED"
                    )

                bypass_after = event.get("bypass_after")
                if bypass_after is not None and not isinstance(bypass_after, dict):
                    errors.append(f"{label}: bypass_after must be an object or null")
                    bypass_after = None
                previous_bypass = replay_bypass
                replay_bypass = bypass_after

                if completed:
                    errors.append(f"{label}: event appears after package completion")
                previous_leaf_chain = set(
                    goal_ancestor_chain(nodes, previous_leaf_id)
                )
                current_leaf_chain = set(
                    goal_ancestor_chain(nodes, current_event_leaf_id)
                )
                if event_type == "PACKAGE_PREPARED":
                    if (
                        index != 0
                        or previous_leaf_id
                        or current_event_leaf_id != INITIAL_WORK_ITEM_GOAL_ID
                        or from_status != ""
                        or to_status != "READY"
                    ):
                        errors.append(f"{label}: invalid PACKAGE_PREPARED transition")
                    if set(status_changes) != EXPECTED_INITIAL_GOAL_IDS:
                        errors.append(
                            f"{label}: initial status snapshot must cover every Goal"
                        )
                    if blockers_after or resolution_ids_after or bypass_after is not None:
                        errors.append(f"{label}: initial blocker/bypass snapshot is not empty")
                elif event_type == "PACKAGE_ACTIVATED":
                    if (
                        previous_leaf_id != current_event_leaf_id
                        or from_status != "READY"
                        or to_status != "IN_PROGRESS"
                    ):
                        errors.append(f"{label}: invalid PACKAGE_ACTIVATED transition")
                    if (
                        set(effective_status_changes) != current_leaf_chain
                        or any(
                            prior_statuses.get(goal_id) != "READY"
                            or changed_status != "IN_PROGRESS"
                            for goal_id, changed_status in (
                                effective_status_changes.items()
                            )
                        )
                    ):
                        errors.append(
                            f"{label}: activation must start exactly the current "
                            "leaf and ancestor chain"
                        )
                    if activated:
                        errors.append(f"{label}: duplicate package activation")
                    activated = True
                elif event_type == "GOAL_COMPLETED":
                    if (
                        previous_leaf_id != current_event_leaf_id
                        or not previous_leaf_id
                        or from_status != "IN_PROGRESS"
                        or to_status != "COMPLETE_AT_TARGET"
                    ):
                        errors.append(f"{label}: invalid GOAL_COMPLETED transition")
                    if (
                        not effective_status_changes
                        or set(effective_status_changes)
                        - previous_leaf_chain
                        or effective_status_changes.get(previous_leaf_id)
                        != "COMPLETE_AT_TARGET"
                        or any(
                            prior_statuses.get(goal_id) != "IN_PROGRESS"
                            or changed_status != "COMPLETE_AT_TARGET"
                            for goal_id, changed_status in (
                                effective_status_changes.items()
                            )
                        )
                    ):
                        errors.append(
                            f"{label}: completion may close only the current leaf "
                            "and eligible ancestors"
                        )
                    closed_after_event = {
                        goal_id
                        for goal_id, status in replay_statuses.items()
                        if status in {"COMPLETE_AT_TARGET", "SUPERSEDED"}
                    }
                    for completed_goal_id in effective_status_changes:
                        completed_node = nodes.get(completed_goal_id, {})
                        completed_children = string_list(
                            completed_node.get("child_goal_ids")
                        ) + string_list(materialized.get(completed_goal_id))
                        if any(
                            child_id not in closed_after_event
                            for child_id in completed_children
                        ):
                            errors.append(
                                f"{label}: completed Goal still has an open child"
                            )
                    completion_refs: set[str] = set()
                    for completed_goal_id in effective_status_changes:
                        completion_refs.update(
                            string_list(
                                evidence.get(completed_goal_id)
                                or archived_evidence.get(completed_goal_id)
                            )
                        )
                    if completion_refs != set(
                        string_list(event.get("evidence_refs"))
                    ):
                        errors.append(
                            f"{label}: event completion evidence set differs"
                        )
                    completion_event_time = parse_iso_datetime(
                        event.get("occurred_at")
                    )
                    externally_attested_roles = (
                        set(REQUIRED_PHASE_B_EVIDENCE_STATUS)
                        | set(REQUIRED_PHASE_C_EVIDENCE_STATUS)
                        | set(REQUIRED_PHASE_D_EVIDENCE_STATUS)
                    )
                    for receipt_role in (
                        completion_refs & externally_attested_roles
                    ):
                        receipt_binding = evidence_bindings.get(
                            receipt_role,
                            {},
                        )
                        receipt_path = resolve_safe_repo_file(
                            root,
                            receipt_binding.get("path"),
                        )
                        if receipt_path is None:
                            continue
                        try:
                            phase_receipt = load_json(receipt_path)
                        except (
                            OSError,
                            ValueError,
                            json.JSONDecodeError,
                        ):
                            continue
                        finalization_values = [
                            (
                                "generated_at",
                                phase_receipt.get("generated_at"),
                            ),
                        ]
                        phase_goal_id = (
                            EXPECTED_PHASE_IDS["B"]
                            if receipt_role
                            in REQUIRED_PHASE_B_EVIDENCE_STATUS
                            else EXPECTED_PHASE_IDS["C"]
                            if receipt_role
                            in REQUIRED_PHASE_C_EVIDENCE_STATUS
                            else EXPECTED_PHASE_IDS["D"]
                        )
                        phase_start_event = (
                            execution_start_event_by_goal_id.get(
                                phase_goal_id,
                                {},
                            )
                        )
                        phase_started_at = parse_iso_datetime(
                            phase_start_event.get("occurred_at")
                        )
                        receipt_window = phase_receipt.get(
                            "execution_window"
                        )
                        receipt_started_at = parse_iso_datetime(
                            receipt_window.get("started_at")
                            if isinstance(receipt_window, dict)
                            else None
                        )
                        if (
                            phase_started_at is not None
                            and receipt_started_at is not None
                            and receipt_started_at < phase_started_at
                        ):
                            errors.append(
                                f"{label}: {receipt_role} execution "
                                "predates its Phase start event"
                            )
                        if receipt_role == (
                            "PHASE_C_TECHNICAL_DELIVERY_RECEIPT"
                        ):
                            technical_delivery = phase_receipt.get(
                                "technical_delivery",
                            )
                            finalization_values.append(
                                (
                                    "technical_delivery.accepted_at",
                                    technical_delivery.get("accepted_at")
                                    if isinstance(
                                        technical_delivery,
                                        dict,
                                    )
                                    else None,
                                )
                            )
                        elif receipt_role == (
                            "PHASE_D_OPERATION_HANDOVER_RECEIPT"
                        ):
                            handover = phase_receipt.get("handover")
                            finalization_values.append(
                                (
                                    "handover.accepted_at",
                                    handover.get("accepted_at")
                                    if isinstance(handover, dict)
                                    else None,
                                )
                            )
                        elif receipt_role == (
                            "PHASE_D_PROJECT_CLOSURE_RECEIPT"
                        ):
                            closure = phase_receipt.get(
                                "project_closure",
                            )
                            finalization_values.append(
                                (
                                    "project_closure.closed_at",
                                    closure.get("closed_at")
                                    if isinstance(closure, dict)
                                    else None,
                                )
                            )
                        for field_name, raw_timestamp in finalization_values:
                            finalization_time = parse_iso_datetime(
                                raw_timestamp
                            )
                            if (
                                finalization_time is not None
                                and completion_event_time is not None
                                and finalization_time
                                > completion_event_time
                            ):
                                errors.append(
                                    f"{label}: {receipt_role} "
                                    f"{field_name} postdates GOAL_COMPLETED"
                                )
                    for completed_goal_id in effective_status_changes:
                        completion_event_by_goal_id[
                            completed_goal_id
                        ] = event
                        completed_node = nodes.get(completed_goal_id, {})
                        if completed_node.get("goal_kind") != "WORK_ITEM":
                            continue
                        receipt_role = (
                            f"WORK_ITEM_COMPLETION::{completed_goal_id}"
                        )
                        receipt_binding = evidence_bindings.get(
                            receipt_role,
                            {},
                        )
                        expected_binding_snapshot = {
                            field: receipt_binding.get(field)
                            for field in (
                                "role",
                                "document_id",
                                "path",
                                "file_sha256",
                            )
                        }
                        if event.get(
                            "completion_receipt_binding"
                        ) != expected_binding_snapshot:
                            errors.append(
                                f"{label}: Work Item completion receipt binding "
                                "snapshot differs"
                            )
                        receipt_path = resolve_safe_repo_file(
                            root,
                            receipt_binding.get("path"),
                        )
                        if receipt_path is None:
                            continue
                        try:
                            completion_receipt = load_json(receipt_path)
                        except (
                            OSError,
                            ValueError,
                            json.JSONDecodeError,
                        ):
                            continue
                        start_event = execution_start_event_by_goal_id.get(
                            completed_goal_id,
                            {},
                        )
                        if completion_receipt.get(
                            "execution_start_event_sha256"
                        ) != start_event.get("event_sha256"):
                            errors.append(
                                f"{label}: Work Item receipt is not bound to "
                                "the latest execution start event"
                            )
                        start_occurred_at = parse_iso_datetime(
                            start_event.get("occurred_at")
                        )
                        window = completion_receipt.get(
                            "execution_window",
                        )
                        window_started_at = parse_iso_datetime(
                            window.get("started_at")
                            if isinstance(window, dict)
                            else None
                        )
                        completed_at = parse_iso_datetime(
                            completion_receipt.get("completed_at")
                        )
                        generated_at = parse_iso_datetime(
                            completion_receipt.get("generated_at")
                        )
                        completion_event_occurred_at = parse_iso_datetime(
                            event.get("occurred_at")
                        )
                        if (
                            start_occurred_at is not None
                            and window_started_at is not None
                            and window_started_at < start_occurred_at
                        ):
                            errors.append(
                                f"{label}: Work Item execution window "
                                "predates its start event"
                            )
                        if (
                            completion_event_occurred_at is not None
                            and (
                                completed_at is None
                                or generated_at is None
                                or completed_at
                                > completion_event_occurred_at
                                or generated_at
                                > completion_event_occurred_at
                            )
                        ):
                            errors.append(
                                f"{label}: Work Item completion receipt is "
                                "not finalized before the completion event"
                            )
                elif event_type == "GOAL_SUPERSEDED":
                    superseded_goal_id = event.get("superseded_goal_id")
                    successor_goal_id = event.get("successor_goal_id")
                    current_status_before = prior_statuses.get(previous_leaf_id)
                    expected_current_status_after = replay_statuses.get(
                        current_event_leaf_id
                    )
                    if (
                        previous_leaf_id != current_event_leaf_id
                        or not previous_leaf_id
                        or from_status != current_status_before
                        or to_status != expected_current_status_after
                        or not isinstance(superseded_goal_id, str)
                        or nodes.get(superseded_goal_id, {}).get("goal_kind")
                        != "WORK_ITEM"
                        or prior_statuses.get(superseded_goal_id)
                        != "COMPLETE_AT_TARGET"
                    ):
                        errors.append(f"{label}: invalid GOAL_SUPERSEDED transition")
                    if effective_status_changes != {
                        superseded_goal_id: "SUPERSEDED"
                    }:
                        errors.append(
                            f"{label}: supersede may change only its named completed "
                            "Work Item"
                        )
                    successor = (
                        nodes.get(successor_goal_id, {})
                        if isinstance(successor_goal_id, str)
                        else {}
                    )
                    successor_reopen_refs = set(
                        string_list(successor.get("reopen_evidence_refs"))
                    )
                    event_reopen_refs = set(
                        string_list(event.get("reopen_evidence_refs"))
                    )
                    if (
                        successor.get("goal_kind") != "WORK_ITEM"
                        or successor.get("supersedes_goal_id")
                        != superseded_goal_id
                        or successor.get("predecessor_goal_id")
                        != previous_leaf_id
                        or introduced_goal_ids != {successor_goal_id}
                        or status_changes.get(successor_goal_id) != "PLANNED"
                        or not successor_reopen_refs
                        or event_reopen_refs != successor_reopen_refs
                        or successor_reopen_refs
                        - set(string_list(event.get("evidence_refs")))
                    ):
                        errors.append(
                            f"{label}: supersede event successor/reopen contract differs"
                        )
                    target_completion_event = completion_event_by_goal_id.get(
                        str(superseded_goal_id)
                    )
                    if not isinstance(target_completion_event, dict):
                        errors.append(
                            f"{label}: superseded Work Item lacks a recorded "
                            "completion event"
                        )
                    else:
                        target_completion_hash = target_completion_event.get(
                            "event_sha256"
                        )
                        completion_occurred_at = parse_iso_datetime(
                            target_completion_event.get("occurred_at")
                        )
                        supersede_occurred_at = parse_iso_datetime(
                            event.get("occurred_at")
                        )
                        for reference in successor_reopen_refs:
                            binding = evidence_reference_targets(
                                evidence_bindings
                            ).get(reference, {})
                            trigger_path = resolve_safe_repo_file(
                                root,
                                binding.get("path"),
                            )
                            if trigger_path is None:
                                continue
                            try:
                                trigger = load_json(trigger_path)
                            except (
                                OSError,
                                ValueError,
                                json.JSONDecodeError,
                            ):
                                continue
                            if trigger.get(
                                "target_completion_event_sha256"
                            ) != target_completion_hash:
                                errors.append(
                                    f"{label}: reopen trigger is not bound to "
                                    "the target completion event"
                                )
                            trigger_time = parse_iso_datetime(
                                trigger.get("decided_at")
                            )
                            if (
                                completion_occurred_at is not None
                                and trigger_time is not None
                                and trigger_time < completion_occurred_at
                            ):
                                errors.append(
                                    f"{label}: reopen trigger predates target "
                                    "completion"
                                )
                            if (
                                supersede_occurred_at is not None
                                and trigger_time is not None
                                and trigger_time > supersede_occurred_at
                            ):
                                errors.append(
                                    f"{label}: reopen trigger postdates the "
                                    "supersede event"
                                )
                elif event_type in {"GOAL_TRANSITION", "GOAL_BYPASSED"}:
                    if (
                        not previous_leaf_id
                        or not current_event_leaf_id
                        or previous_leaf_id == current_event_leaf_id
                    ):
                        errors.append(f"{label}: transition must change leaf Goals")
                    if event_type == "GOAL_TRANSITION" and from_status not in {
                        "COMPLETE_AT_TARGET",
                        "SUPERSEDED",
                    }:
                        errors.append(f"{label}: previous leaf was not closed")
                    if event_type == "GOAL_BYPASSED" and from_status not in {
                        "AWAITING_USER",
                        "AWAITING_EXTERNAL",
                        "BLOCKED",
                    }:
                        errors.append(f"{label}: bypassed leaf was not waiting")
                    if to_status not in {"READY", "IN_PROGRESS"}:
                        errors.append(f"{label}: next leaf status is invalid")
                    if (
                        current_event_leaf_id not in effective_status_changes
                        or set(effective_status_changes) - current_leaf_chain
                        or any(
                            changed_status not in {"READY", "IN_PROGRESS"}
                            or prior_statuses.get(goal_id)
                            not in {None, "PLANNED", "READY"}
                            for goal_id, changed_status in (
                                effective_status_changes.items()
                            )
                        )
                    ):
                        errors.append(
                            f"{label}: transition status changes leave the new "
                            "leaf branch"
                        )
                    next_node = nodes.get(current_event_leaf_id, {})
                    if next_node.get("predecessor_goal_id") != previous_leaf_id:
                        errors.append(f"{label}: next leaf predecessor differs")
                    if (
                        event_type == "GOAL_TRANSITION"
                        and bypass_after != previous_bypass
                    ):
                        errors.append(
                            f"{label}: normal transition cannot create or alter "
                            "bypass state"
                        )
                    if event_type == "GOAL_BYPASSED":
                        introduced_ids = set(
                            string_list(event.get("blocker_ids"))
                        )
                        if not introduced_ids or introduced_ids != current_blocker_ids:
                            errors.append(
                                f"{label}: bypass blocker IDs differ from preserved blockers"
                            )
                        if not isinstance(bypass_after, dict):
                            errors.append(f"{label}: bypass state is missing")
                        else:
                            current_epic_goal_id = next_node.get(
                                "parent_goal_id"
                            )
                            current_phase_goal_id = nodes.get(
                                current_epic_goal_id, {}
                            ).get("parent_goal_id")
                            phase_children = string_list(
                                nodes.get(current_phase_goal_id, {}).get(
                                    "child_goal_ids"
                                )
                            )
                            incomplete_children = [
                                goal_id
                                for goal_id in phase_children
                                if replay_statuses.get(goal_id)
                                not in {"COMPLETE_AT_TARGET", "SUPERSEDED"}
                            ]
                            expected_return_goal_ids = (
                                incomplete_children[
                                    : incomplete_children.index(
                                        current_epic_goal_id
                                    )
                                ]
                                if current_epic_goal_id
                                in incomplete_children
                                else []
                            )
                            expected_blocked_leaf_ids = [previous_leaf_id]
                            expected_bypass_goal_ids = [
                                *expected_return_goal_ids,
                                previous_leaf_id,
                            ]
                            expected_bypass_blocker_ids = {
                                blocker_id
                                for blocker_id, blocker in (
                                    current_blocker_records.items()
                                )
                                if blocker.get("blocks_goal_id")
                                in expected_bypass_goal_ids
                            }
                            if (
                                previous_bypass is not None
                                or bypass_after.get("current_goal_id")
                                != current_epic_goal_id
                                or bypass_after.get("return_goal_ids")
                                != expected_return_goal_ids
                                or bypass_after.get(
                                    "blocked_leaf_goal_ids"
                                )
                                != expected_blocked_leaf_ids
                                or bypass_after.get("return_leaf_goal_id")
                                != previous_leaf_id
                                or bypass_after.get("resolution_required")
                                is not True
                                or set(
                                    string_list(
                                        bypass_after.get("blocker_ids")
                                    )
                                )
                                != expected_bypass_blocker_ids
                                or any(
                                    replay_statuses.get(goal_id)
                                    not in {
                                        "AWAITING_USER",
                                        "AWAITING_EXTERNAL",
                                        "BLOCKED",
                                    }
                                    or not any(
                                        blocker.get("blocks_goal_id")
                                        == goal_id
                                        for blocker in (
                                            current_blocker_records.values()
                                        )
                                    )
                                    for goal_id in (
                                        expected_bypass_goal_ids
                                    )
                                )
                                or event.get("return_goal_ids")
                                != expected_return_goal_ids
                                or event.get("blocked_leaf_goal_ids")
                                != expected_blocked_leaf_ids
                            ):
                                errors.append(
                                    f"{label}: historical bypass snapshot differs"
                                )
                elif event_type == "BLOCKER_RECORDED":
                    introduced_ids = current_blocker_ids - previous_blocker_ids
                    removed_ids = previous_blocker_ids - current_blocker_ids
                    introduced_goal_ids_for_blockers = {
                        current_blocker_records[blocker_id].get(
                            "blocks_goal_id"
                        )
                        for blocker_id in introduced_ids
                    }
                    if (
                        previous_leaf_id != current_event_leaf_id
                        or not previous_leaf_id
                        or from_status not in {"READY", "IN_PROGRESS"}
                        or to_status
                        not in {"AWAITING_USER", "AWAITING_EXTERNAL", "BLOCKED"}
                        or set(string_list(event.get("blocker_ids"))) != introduced_ids
                        or not introduced_ids
                        or removed_ids
                        or set(effective_status_changes)
                        != introduced_goal_ids_for_blockers
                        or introduced_goal_ids_for_blockers
                        - previous_leaf_chain
                        or any(
                            prior_statuses.get(goal_id)
                            not in {"READY", "IN_PROGRESS"}
                            or changed_status
                            not in {
                                "AWAITING_USER",
                                "AWAITING_EXTERNAL",
                                "BLOCKED",
                            }
                            for goal_id, changed_status in (
                                effective_status_changes.items()
                            )
                        )
                    ):
                        errors.append(f"{label}: invalid BLOCKER_RECORDED transition")
                    for blocker_id in introduced_ids:
                        if isinstance(blocker_id, str):
                            if blocker_id in recorded_blocker_event_by_id:
                                errors.append(
                                    f"{label}: blocker ID was reused: {blocker_id}"
                                )
                            recorded_blocker_event_by_id[blocker_id] = event.get(
                                "event_sha256",
                                "",
                            )
                            blocker_event_time = parse_iso_datetime(
                                event.get("occurred_at")
                            )
                            if blocker_event_time is not None:
                                recorded_blocker_event_time_by_id[
                                    blocker_id
                                ] = blocker_event_time
                            recorded_blocker = next(
                                (
                                    blocker
                                    for blocker_rows in blockers_after.values()
                                    if isinstance(blocker_rows, list)
                                    for blocker in blocker_rows
                                    if isinstance(blocker, dict)
                                    and blocker.get("blocker_id") == blocker_id
                                ),
                                {},
                            )
                            recorded_blocker_snapshot_by_id[blocker_id] = str(
                                recorded_blocker.get(
                                    "blocker_snapshot_sha256",
                                    "",
                                )
                            )
                            recorded_blocker_by_id[blocker_id] = recorded_blocker
                            blocker_created_at = parse_iso_datetime(
                                recorded_blocker.get("created_at")
                            )
                            if (
                                blocker_created_at is not None
                                and blocker_event_time is not None
                                and blocker_created_at > blocker_event_time
                            ):
                                errors.append(
                                    f"{label}: blocker creation postdates its "
                                    "BLOCKER_RECORDED event"
                                )
                elif event_type == "BLOCKER_RESOLVED":
                    resolved_now = added_resolution_ids
                    resolved_event_time = parse_iso_datetime(
                        event.get("occurred_at")
                    )
                    if resolved_event_time is not None:
                        for blocker_id in resolved_now:
                            resolved_blocker_event_time_by_id[
                                blocker_id
                            ] = resolved_event_time
                    expected_resolution_refs = {
                        resolutions_by_id.get(blocker_id, {}).get(
                            "resolution_receipt_ref"
                        )
                        for blocker_id in resolved_now
                    }
                    removed_ids = previous_blocker_ids - current_blocker_ids
                    introduced_ids = current_blocker_ids - previous_blocker_ids
                    resolved_goal_ids = {
                        previous_blocker_records[blocker_id].get(
                            "blocks_goal_id"
                        )
                        for blocker_id in resolved_now
                        if blocker_id in previous_blocker_records
                    }
                    ready_goal_ids = {
                        goal_id
                        for goal_id in resolved_goal_ids
                        if not any(
                            blocker.get("blocks_goal_id") == goal_id
                            for blocker in current_blocker_records.values()
                        )
                    }
                    if (
                        previous_leaf_id != current_event_leaf_id
                        or not previous_leaf_id
                        or set(string_list(event.get("resolved_blocker_ids")))
                        != resolved_now
                        or None in expected_resolution_refs
                        or set(string_list(event.get("evidence_refs")))
                        != expected_resolution_refs
                        or not resolved_now
                        or resolved_now & current_blocker_ids
                        or removed_ids != resolved_now
                        or introduced_ids
                        or set(effective_status_changes) != ready_goal_ids
                        or any(
                            prior_statuses.get(goal_id)
                            not in {
                                "AWAITING_USER",
                                "AWAITING_EXTERNAL",
                                "BLOCKED",
                            }
                            or changed_status != "READY"
                            for goal_id, changed_status in (
                                effective_status_changes.items()
                            )
                        )
                    ):
                        errors.append(f"{label}: invalid BLOCKER_RESOLVED transition")
                elif event_type == "GOAL_RESUMED":
                    resolved_for_resume = set(
                        string_list(event.get("resolved_blocker_ids"))
                    )
                    expected_resume_status_changes = {
                        goal_id: "IN_PROGRESS"
                        for goal_id in current_leaf_chain
                        if prior_statuses.get(goal_id) == "READY"
                    }
                    if (
                        isinstance(previous_bypass, dict)
                        and previous_leaf_id != current_event_leaf_id
                    ):
                        bypass_goal_id = previous_bypass.get(
                            "current_goal_id"
                        )
                        if (
                            isinstance(bypass_goal_id, str)
                            and bypass_goal_id
                            and bypass_goal_id not in current_leaf_chain
                            and prior_statuses.get(bypass_goal_id)
                            in {"READY", "IN_PROGRESS"}
                        ):
                            expected_resume_status_changes[
                                bypass_goal_id
                            ] = "PLANNED"
                    if not resolved_for_resume or not resolved_for_resume.issubset(
                        set(resolution_ids_after)
                    ):
                        errors.append(f"{label}: resume lacks resolved blocker IDs")
                    if previous_leaf_id == current_event_leaf_id:
                        expected_resume_resolution_ids = {
                            blocker_id
                            for blocker_id in set(resolution_ids_after)
                            - consumed_resume_resolution_ids
                            if recorded_blocker_by_id.get(
                                blocker_id, {}
                            ).get("blocks_goal_id")
                            in current_leaf_chain
                        }
                        if (
                            not previous_leaf_id
                            or from_status != "READY"
                            or to_status != "IN_PROGRESS"
                            or previous_bypass is not None
                            or bypass_after is not None
                            or effective_status_changes
                            != expected_resume_status_changes
                            or resolved_for_resume
                            != expected_resume_resolution_ids
                            or any(
                                blocker.get("blocks_goal_id")
                                in current_leaf_chain
                                for blocker in current_blocker_records.values()
                            )
                        ):
                            errors.append(f"{label}: invalid same-leaf GOAL_RESUMED")
                    else:
                        blocked_leaf_ids = set(
                            string_list(
                                previous_bypass.get("blocked_leaf_goal_ids")
                                if isinstance(previous_bypass, dict)
                                else []
                            )
                        )
                        expected_resume_resolution_ids = set(
                            string_list(
                                previous_bypass.get("blocker_ids")
                                if isinstance(previous_bypass, dict)
                                else []
                            )
                        )
                        if (
                            not previous_leaf_id
                            or not current_event_leaf_id
                            or current_event_leaf_id not in blocked_leaf_ids
                            or event.get("return_from_bypass") is not True
                            or from_status
                            not in {"COMPLETE_AT_TARGET", "SUPERSEDED"}
                            or to_status != "IN_PROGRESS"
                            or bypass_after is not None
                            or effective_status_changes
                            != expected_resume_status_changes
                            or resolved_for_resume
                            != expected_resume_resolution_ids
                            or any(
                                blocker_id not in set(resolution_ids_after)
                                for blocker_id in (
                                    expected_resume_resolution_ids
                                )
                            )
                            or any(
                                recorded_blocker_by_id.get(
                                    blocker_id, {}
                                ).get("return_leaf_goal_id")
                                != current_event_leaf_id
                                for blocker_id in (
                                    expected_resume_resolution_ids
                                )
                            )
                        ):
                            errors.append(f"{label}: invalid bypass-return GOAL_RESUMED")
                    consumed_resume_resolution_ids.update(resolved_for_resume)
                elif event_type == "PACKAGE_COMPLETED":
                    if (
                        not previous_leaf_id
                        or current_event_leaf_id
                        or from_status != "COMPLETE_AT_TARGET"
                        or to_status != "COMPLETE_AT_TARGET"
                    ):
                        errors.append(f"{label}: invalid PACKAGE_COMPLETED transition")
                    if index != len(valid_history) - 1:
                        errors.append(f"{label}: PACKAGE_COMPLETED must be final")
                    if effective_status_changes:
                        errors.append(
                            f"{label}: PACKAGE_COMPLETED cannot rewrite Goal statuses"
                        )
                    completed = True
                if (
                    event_type
                    not in {"BLOCKER_RECORDED", "BLOCKER_RESOLVED"}
                    and blockers_after != previous_blockers_snapshot
                ):
                    errors.append(
                        f"{label}: blocker snapshot changed outside a blocker event"
                    )
                bypass_return = (
                    event_type == "GOAL_RESUMED"
                    and previous_leaf_id != current_event_leaf_id
                )
                if (
                    event_type != "GOAL_BYPASSED"
                    and not bypass_return
                    and bypass_after != previous_bypass
                ):
                    errors.append(
                        f"{label}: bypass snapshot changed outside bypass/resume"
                    )
                if index > 0 and not activated and event_type != "PACKAGE_ACTIVATED":
                    errors.append(f"{label}: execution event occurred before activation")
                errors.extend(
                    validate_evidence_references(
                        label,
                        event.get("evidence_refs"),
                        root=root,
                        bindings=evidence_bindings,
                    )
                )
            event_types = [event.get("event_type") for event in valid_history]
            activation_count = event_types.count("PACKAGE_ACTIVATED")
            if activation == "READY_NOT_ACTIVATED" and len(valid_history) != 1:
                errors.append("not-activated package contains execution history")
            if activation == "READY_NOT_ACTIVATED" and activation_count:
                errors.append("not-activated package contains an activation event")
            if activation in {"ACTIVE", "COMPLETE"} and activation_count != 1:
                errors.append("active or complete package must have one activation event")
            if activation == "COMPLETE" and event_types[-1] != "PACKAGE_COMPLETED":
                errors.append("complete package lacks a final completion event")
            if replay_statuses != statuses:
                errors.append("goal transition replay status map differs from runtime")
            expected_pointer_snapshot = {
                field: state.get(field, "")
                for field in TRANSITION_POINTER_FIELDS
            }
            if replay_pointers != expected_pointer_snapshot:
                errors.append("goal transition replay pointers differ from runtime")
            if replay_blockers != blockers_by_goal:
                errors.append("goal transition replay blockers differ from runtime")
            if replay_resolution_ids != runtime_resolution_ids:
                errors.append(
                    "goal transition replay blocker resolutions differ from runtime"
                )
            expected_bypass = state.get("temporary_dependency_ready_bypass")
            if expected_bypass is not None and not isinstance(expected_bypass, dict):
                expected_bypass = None
            if replay_bypass != expected_bypass:
                errors.append("goal transition replay bypass differs from runtime")
            recorded_blocker_ids = set(recorded_blocker_by_id)
            if recorded_blocker_ids != active_blocker_ids | resolved_ids:
                errors.append(
                    "recorded blockers must remain active or have one resolution"
                )
            for blocker_id, resolution in resolutions_by_id.items():
                if resolution.get("blocker_event_sha256") != (
                    recorded_blocker_event_by_id.get(blocker_id)
                ):
                    errors.append(
                        f"blocker resolution {blocker_id} differs from recorded blocker event"
                    )
                if resolution.get("blocker_snapshot_sha256") != (
                    recorded_blocker_snapshot_by_id.get(blocker_id)
                ):
                    errors.append(
                        f"blocker resolution {blocker_id} differs from blocker snapshot"
                    )
                original_blocker = recorded_blocker_by_id.get(blocker_id, {})
                for resolution_field, blocker_field in (
                    ("goal_id", "blocks_goal_id"),
                    ("condition_code", "condition_code"),
                    ("owner", "owner"),
                ):
                    if resolution.get(resolution_field) != original_blocker.get(
                        blocker_field
                    ):
                        errors.append(
                            f"blocker resolution {blocker_id} {resolution_field} "
                            "differs from original blocker"
                        )
                resolution_binding = evidence_reference_targets(
                    evidence_bindings
                ).get(resolution.get("resolution_receipt_ref"), {})
                resolution_path = resolve_safe_repo_file(
                    root,
                    resolution_binding.get("path"),
                )
                if resolution_path is not None:
                    try:
                        resolution_receipt = load_json(resolution_path)
                    except (OSError, ValueError, json.JSONDecodeError):
                        resolution_receipt = {}
                    blocker_created_at = parse_iso_datetime(
                        original_blocker.get("created_at")
                    )
                    blocker_recorded_at = (
                        recorded_blocker_event_time_by_id.get(blocker_id)
                    )
                    resolution_decided_at = parse_iso_datetime(
                        resolution_receipt.get("decided_at")
                    )
                    resolution_recorded_at = (
                        resolved_blocker_event_time_by_id.get(blocker_id)
                    )
                    if (
                        blocker_created_at is not None
                        and blocker_recorded_at is not None
                        and blocker_created_at > blocker_recorded_at
                    ):
                        errors.append(
                            f"blocker {blocker_id} creation postdates its "
                            "recording event"
                        )
                    if (
                        blocker_created_at is not None
                        and resolution_decided_at is not None
                        and resolution_decided_at < blocker_created_at
                    ):
                        errors.append(
                            f"blocker resolution {blocker_id} predates blocker "
                            "creation"
                        )
                    if (
                        blocker_recorded_at is not None
                        and resolution_decided_at is not None
                        and resolution_decided_at < blocker_recorded_at
                    ):
                        errors.append(
                            f"blocker resolution {blocker_id} predates its "
                            "BLOCKER_RECORDED event"
                        )
                    if (
                        resolution_decided_at is not None
                        and resolution_recorded_at is not None
                        and resolution_decided_at > resolution_recorded_at
                    ):
                        errors.append(
                            f"blocker resolution {blocker_id} postdates its "
                            "resolution event"
                        )
            if state.get("transition_history_anchor_sha256") != valid_history[-1].get(
                "event_sha256"
            ):
                errors.append("goal transition history anchor differs from the head")
            head_hash = valid_history[-1].get("event_sha256")
            if (
                EXPECTED_TRANSITION_HISTORY_HEAD_SHA256
                == "__TRANSITION_HISTORY_HEAD_SHA256__"
            ):
                errors.append(
                    "transition history checker head anchor is not finalized"
                )
            elif head_hash != EXPECTED_TRANSITION_HISTORY_HEAD_SHA256:
                errors.append(
                    "goal transition history differs from the reviewed "
                    "checker head trust anchor"
                )

            work_items = {
                goal_id: node
                for goal_id, node in nodes.items()
                if node.get("goal_kind") == "WORK_ITEM"
            }
            superseded_targets = {
                node.get("supersedes_goal_id")
                for node in work_items.values()
                if node.get("supersedes_goal_id")
            }
            for goal_id, status in statuses.items():
                if status == "SUPERSEDED" and goal_id not in superseded_targets:
                    errors.append(f"{goal_id}: SUPERSEDED Work Item has no successor")
            for successor_id, successor in work_items.items():
                superseded_goal_id = successor.get("supersedes_goal_id")
                if (
                    superseded_goal_id
                    and statuses.get(superseded_goal_id) != "SUPERSEDED"
                ):
                    errors.append(
                        f"{successor_id}: active successor target is not SUPERSEDED"
                    )
            if current_leaf_pointer and current_leaf_pointer != INITIAL_WORK_ITEM_GOAL_ID:
                predecessor_id = work_items.get(current_leaf_pointer, {}).get(
                    "predecessor_goal_id"
                )
                if not isinstance(predecessor_id, str) or not predecessor_id:
                    errors.append("current non-initial leaf lacks predecessor")
                elif statuses.get(predecessor_id) not in {
                    "COMPLETE_AT_TARGET",
                    "SUPERSEDED",
                    "AWAITING_USER",
                    "AWAITING_EXTERNAL",
                    "BLOCKED",
                }:
                    errors.append("current leaf predecessor is neither closed nor bypassed")
                head_event = valid_history[-1]
                if head_event.get("event_type") in {
                    "GOAL_TRANSITION",
                    "GOAL_BYPASSED",
                } and head_event.get("previous_leaf_goal_id") != predecessor_id:
                    errors.append("current leaf predecessor differs from transition head")
    return errors


def validate(
    root: Path = ROOT,
    *,
    check_continuation: bool = True,
) -> list[str]:
    errors: list[str] = []
    checkpoint_path = resolve_safe_repo_file(
        root,
        CHECKPOINT_RELATIVE.as_posix(),
    )
    if checkpoint_path is None:
        return ["checkpoint path is unsafe or missing"]
    if check_continuation:
        errors.extend(
            f"continuation: {error}"
            for error in continuation.validate(checkpoint_path, root)
        )
    try:
        checkpoint = load_json(checkpoint_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [f"checkpoint cannot be loaded: {exc}"]

    try:
        all_paths, goal_paths = discover_package_paths(root)
    except OSError as exc:
        return errors + [f"Goal package cannot be discovered: {exc}"]
    if not all_paths:
        return errors + ["Goal package contains no Markdown files"]

    state = checkpoint.get("goal_execution", {})
    if not isinstance(state, dict):
        return errors + ["checkpoint goal_execution must be an object"]
    expected_all_paths = state.get("managed_goal_paths")
    expected_goal_paths = state.get("goal_document_paths")
    if all_paths != expected_all_paths:
        errors.append("Goal package exact managed path manifest differs from checkpoint")
    if goal_paths != expected_goal_paths:
        errors.append("Goal document exact path manifest differs from checkpoint")
    if not SUPPORT_PATHS.issubset(set(all_paths)):
        errors.append("Goal package required support document is missing")
    if sorted(SUPPORT_PATHS) != state.get("support_paths"):
        errors.append("Goal support path manifest differs from checkpoint")
    unsafe = [
        relative
        for relative in all_paths
        if resolve_safe_repo_file(root, relative) is None
    ]
    if unsafe:
        errors.append(f"Goal package contains unsafe paths: {unsafe}")
    elif all_paths:
        try:
            path_hash, content_hash = path_and_content_hashes(root, all_paths)
        except (OSError, ValueError) as exc:
            errors.append(f"Goal package hash calculation failed safely: {exc}")
            path_hash = ""
            content_hash = ""
        if state.get("managed_goal_path_count") != len(all_paths):
            errors.append("Goal package managed path count differs")
        if state.get("path_set_sha256") != path_hash:
            errors.append("Goal package path-set SHA-256 differs")
        if state.get("content_set_sha256") != content_hash:
            errors.append("Goal package content-set SHA-256 differs")

    errors.extend(validate_canonical_bindings_shape(checkpoint))
    bindings = canonical_binding_map(checkpoint)
    nodes: dict[str, dict[str, Any]] = {}
    goal_path_by_id: dict[str, str] = {}
    for relative in goal_paths:
        goal_file = resolve_safe_repo_file(root, relative)
        if goal_file is None:
            errors.append(f"{relative}: Goal path is unsafe or missing")
            continue
        try:
            metadata, body = parse_goal(goal_file)
        except (OSError, ValueError, tomllib.TOMLDecodeError) as exc:
            errors.append(f"{relative}: cannot parse Goal: {exc}")
            continue
        try:
            errors.extend(
                validate_goal_document(relative, metadata, body, set(bindings))
            )
        except (TypeError, AttributeError, KeyError, ValueError) as exc:
            errors.append(f"{relative}: malformed Goal metadata: {exc}")
        goal_id = metadata.get("goal_id")
        if isinstance(goal_id, str):
            if goal_id in nodes:
                errors.append(f"duplicate goal_id: {goal_id}")
            else:
                nodes[goal_id] = metadata
                goal_path_by_id[goal_id] = relative

    try:
        errors.extend(validate_graph(nodes))
    except (TypeError, AttributeError, KeyError, ValueError) as exc:
        errors.append(f"Goal graph contains malformed input: {exc}")
    mapping_errors, policy_gap_mapping = load_policy_gap_mapping(root, bindings)
    errors.extend(mapping_errors)
    try:
        errors.extend(
            validate_work_item_lineage(
                root,
                nodes,
                goal_path_by_id,
                bindings,
                policy_gap_mapping,
            )
        )
    except (TypeError, AttributeError, KeyError, ValueError) as exc:
        errors.append(f"Work Item lineage contains malformed input: {exc}")
    errors.extend(validate_static_plan_manifest(root, state, goal_path_by_id))
    backlog_binding = bindings.get("IMPLEMENTATION_BACKLOG")
    if not backlog_binding:
        errors.append("IMPLEMENTATION_BACKLOG canonical binding is missing")
        return errors
    try:
        backlog_path = backlog_binding.get("path")
        if not isinstance(backlog_path, str):
            raise ValueError("binding path must be a string")
        safe_backlog_path = resolve_safe_repo_file(root, backlog_path)
        if safe_backlog_path is None:
            raise ValueError("binding path is unsafe or missing")
        backlog = load_json(safe_backlog_path)
    except (OSError, KeyError, ValueError, json.JSONDecodeError) as exc:
        return errors + [f"implementation backlog cannot be loaded: {exc}"]
    try:
        errors.extend(validate_epics(nodes, backlog, policy_gap_mapping))
    except (TypeError, AttributeError, KeyError, ValueError) as exc:
        errors.append(f"EPIC plan contains malformed input: {exc}")
    try:
        errors.extend(
            validate_runtime_state(
                root,
                checkpoint,
                nodes,
                goal_path_by_id,
                backlog,
                bindings,
                policy_gap_mapping,
            )
        )
    except (TypeError, AttributeError, KeyError, ValueError) as exc:
        errors.append(f"Goal runtime state contains malformed input: {exc}")

    required_text = {
        EXPECTED_MASTER_ID: [
            "279",
            "5개 gate",
            "NOT_ELIGIBLE",
            "질문 없이",
            "WORK_ITEM_COMPLETION::<goal_id>",
            "WORK_ITEM_EXECUTION_RECEIPT",
            "target_completion_event_sha256",
            "IMPLEMENTATION_GAP.assessments",
            "blocker_event_sha256",
            "blocker_snapshot_sha256",
            "occurred_at",
            "validation_cutoff_at",
            "TEST_PLAN_APPROVAL_RECEIPT",
            "EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE",
        ],
        EXPECTED_PHASE_IDS["A"]: [
            "INTERNAL_POLICY_CONFORMANCE_REASSESSED",
            "IMPLEMENTATION_READY",
            "WORK_ITEM_COMPLETION::<goal_id>",
            "WORK_ITEM_EXECUTION_RECEIPT",
            "IMPLEMENTATION_GAP.assessments",
            "occurred_at",
        ],
        EXPECTED_PHASE_IDS["B"]: [
            "GATE-CLOUD-COST-MEASUREMENT",
            "GATE-PHONE-QUEUE-BYTE-LIMIT",
            "GATE-RAW-COLLECTION-RELEASE-REVIEW",
            "GATE-SERVER-CAPACITY-STATE-CONTRACT",
            "GATE-SINGLE-ADMIN-RECOVERY-DRILL",
            "실제 사용자시험 또는 배포 전",
            "변경요청·영향분석·제품책임자 재승인",
            "FORMAL_VERIFICATION_ACTION_COMPLETE",
            "WORK_ITEM_EXECUTION_RECEIPT",
            "TEST_PLAN_APPROVAL_RECEIPT",
            "test_case_id_set_sha256",
            "validation_cutoff_at",
            "EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE",
        ],
        EXPECTED_PHASE_IDS["C"]: [
            "RELEASE_DELIVERY_ACTION_COMPLETE",
            "WORK_ITEM_EXECUTION_RECEIPT",
            "required_check_summary",
            "deployment.started_at",
            "technical_delivery.accepted_at",
            "validation_cutoff_at",
            "Phase C 시작",
            "EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE",
        ],
        EXPECTED_PHASE_IDS["D"]: [
            "HANDOVER_CLOSURE_ACTION_COMPLETE",
            "WORK_ITEM_EXECUTION_RECEIPT",
            "required_check_summary",
            "handover.accepted_at",
            "project_closure.closed_at",
            "validation_cutoff_at",
            "Phase D 시작",
            "EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE",
        ],
        EXPECTED_EPIC_GOAL_IDS["EPIC-07"]: [
            "일반 활동원본과 자동신고 원본 최대 180일",
            "서버 원본·검역본·복사본 7일",
            "삭제 확인기록",
        ],
        EXPECTED_EPIC_GOAL_IDS["EPIC-08"]: [
            "미전송 자동신고 후보를 24시간 안에 삭제",
            "자동신고 원본은 삭제요청 상태로 바꿔 7일 안에 삭제",
            "일반 활동원본은 자동신고를 껐다는 이유만으로 삭제하지 않고",
        ],
        EXPECTED_EPIC_GOAL_IDS["EPIC-09"]: [
            "주 원본 저장소 300 GiB",
            "백업 저장소 300 GiB",
            "사용자의 보행 자체를 자동 재개하지 않는다",
        ],
    }
    for goal_id, fragments in required_text.items():
        relative = goal_path_by_id.get(goal_id)
        if not relative:
            continue
        safe_goal_path = resolve_safe_repo_file(root, relative)
        if safe_goal_path is None:
            errors.append(f"{relative}: required safety text path is unsafe")
            continue
        text = safe_goal_path.read_text(encoding="utf-8")
        for fragment in fragments:
            if fragment not in text:
                errors.append(f"{relative}: required safety text is missing: {fragment}")

    support_required_text = {
        f"{PACKAGE_RELATIVE.as_posix()}/README.md": [
            "READY_NOT_ACTIVATED",
            "한 번의 논리적 commit",
            "WalkSafe 활성 Goal을 이어서 진행해",
            "WORK_ITEM_COMPLETION::<goal_id>",
            "WORK_ITEM_EXECUTION_RECEIPT",
            "target_completion_event_sha256",
            "IMPLEMENTATION_GAP",
            "blocker_event_sha256",
            "blocker_snapshot_sha256",
            "occurred_at",
            "validation_cutoff_at",
            "TEST_PLAN_APPROVAL_RECEIPT",
            "test_case_id_set_sha256",
            "EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE",
        ],
        f"{PACKAGE_RELATIVE.as_posix()}/templates/work-item-template.md": [
            "materialized_from_sha256",
            "predecessor_goal_content_sha256",
            "supersedes_goal_content_sha256",
            "WORK_ITEM_COMPLETION::<goal_id>",
            "WORK_ITEM_EXECUTION_RECEIPT",
            "target_completion_event_sha256",
            "IMPLEMENTATION_GAP",
            "INTERNAL_POLICY_CONFORMANCE_REASSESSED",
            "FORMAL_VERIFICATION_ACTION_COMPLETE",
            "RELEASE_DELIVERY_ACTION_COMPLETE",
            "HANDOVER_CLOSURE_ACTION_COMPLETE",
            "blocker_event_sha256",
            "blocker_snapshot_sha256",
            "occurred_at",
            "validation_cutoff_at",
            "TEST_PLAN_APPROVAL_RECEIPT",
            "EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE",
        ],
    }
    for relative, fragments in support_required_text.items():
        path = resolve_safe_repo_file(root, relative)
        if path is None:
            continue
        text = path.read_text(encoding="utf-8")
        for fragment in fragments:
            if fragment not in text:
                errors.append(f"{relative}: required control text is missing: {fragment}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument(
        "--skip-continuation",
        action="store_true",
        help="Skip the outer continuation validator; intended only for isolated unit tests.",
    )
    parser.add_argument(
        "--print-hashes",
        action="store_true",
        help="Print the discovered package count and hashes without validating checkpoint values.",
    )
    args = parser.parse_args()
    root = args.root.resolve()
    if args.print_hashes:
        all_paths, goal_paths = discover_package_paths(root)
        path_hash, content_hash = path_and_content_hashes(root, all_paths)
        print(
            json.dumps(
                {
                    "managed_goal_path_count": len(all_paths),
                    "goal_document_count": len(goal_paths),
                    "managed_goal_paths": all_paths,
                    "goal_document_paths": goal_paths,
                    "support_paths": sorted(SUPPORT_PATHS),
                    "path_set_sha256": path_hash,
                    "content_set_sha256": content_hash,
                },
                ensure_ascii=False,
                indent=2,
            )
        )
        return 0
    errors = validate(root, check_continuation=not args.skip_continuation)
    if errors:
        print("WalkSafe Goal package validation: FAIL", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("WalkSafe Goal package validation: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Atomically append the exact FP-048 producer/completion seq43-44 pair."""

from __future__ import annotations

import argparse
import copy
import ctypes
from dataclasses import dataclass
from datetime import datetime, timedelta
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import stat
import sys
import tempfile
from typing import Any, Callable, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import check_walksafe_goal_graph_v2_4 as goal_graph
from scripts import check_walksafe_project_continuation_v2_4 as contract
from scripts import materialize_walksafe_fp048_goal_20260802 as materialize


CHECKPOINT_RELATIVE = Path(
    "docs/control/walksafe-project-continuation-checkpoint.json"
)
SOURCE_CHECKPOINT_RAW_SHA256 = (
    "17d95f92197797be88ea35c230c5449057f8594938d2d616599f2a01da987d32"
)
SOURCE_CHECKPOINT_BYTE_COUNT = 1_392_068
SOURCE_EVENT_SHA256 = (
    "e8b4f8c91082e856934b93ea633047ddc32f8b1c7549c590b455483145fef1c9"
)
SOURCE_EVENT_ID = "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-20260802-005"
CANONICAL_UPDATE_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-CANONICAL-BINDINGS-UPDATED-FP048-20260802-001"
)
COMPLETION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-COMPLETED-FP048-20260802-001"
)
MANIFEST_SHA256 = (
    "7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07"
)

GOAL_ID = materialize.GOAL_ID
GOAL_PATH = materialize.GOAL_PATH.as_posix()
GOAL_SHA256 = materialize.GOAL_SHA256
WORK_ITEM_ID = materialize.WORK_ITEM_ID
PARENT_GOAL_ID = materialize.PARENT_GOAL_ID
PARENT_GOAL_PATH = materialize.PARENT_GOAL_PATH
PARENT_GOAL_SHA256 = materialize.PARENT_GOAL_SHA256
EPIC12_GOAL_ID = "WS-GOAL-EPIC-12"

COMPLETION_ROLE = f"WORK_ITEM_COMPLETION::{GOAL_ID}"
COMPLETION_DOCUMENT_ID = (
    "WS-FP048-ENCRYPTION-CONNECTION-SECURITY-WORK-ITEM-"
    "COMPLETION-20260802-001"
)
COMPLETION_PATH = Path(
    "docs/control/execution/goal-results/"
    f"{GOAL_ID}/completion-receipt.json"
)
RESULT_ROOT = COMPLETION_PATH.parent
IMPLEMENTATION_RESULT_PATH = RESULT_ROOT / "implementation-record.json"
VERIFICATION_RESULT_PATH = RESULT_ROOT / "verification-result.json"
SUCCESSOR_TRACE_PATH = RESULT_ROOT / "successor-trace.json"
INDEPENDENT_REVIEW_PATH = RESULT_ROOT / "independent-review.json"
GAP_PATH = Path(
    "docs/control/audits/"
    "walksafe-implementation-gap-analysis-20260802-r023.json"
)
BACKLOG_PATH = Path(
    "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260802-r023.json"
)

NEXT_POLICY_ID = "FP-008"
NEXT_GAP_ID = "GAP-017"
NEXT_WORK_ITEM_ID = "EPIC-03-FP008-ADMIN-REVIEW-DELIVERY"
NEXT_ACTION = (
    "사용자 앱과 별개 앱 식별값·서명·세션을 쓰는 Android 관리자 앱을 "
    "만들고 로그인, 검수, 기관 전달, 감사까지 관리자 흐름을 분리한다."
)

FINAL_CURRENT_FOCUS = (
    "FP048/GAP-057 COMPLETE_AT_TARGET; FP008/GAP-017 PLANNED_NEXT"
)
FINAL_SCOPE = (
    "Graph v2.4 through the atomic FP048 canonical producer seq43 and "
    "completion seq44 transaction; FP008/GAP-017 is the next pointer and no "
    "formal, external, deployment, or release credit is granted."
)
FINAL_HANDOFF_EPIC = "EPIC-03 / FP008/GAP-017 PLANNED_NEXT"

SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


@dataclass(frozen=True)
class ArtifactSpec:
    role: str
    document_id: str
    path: Path
    identity_json_path: str
    mutable: bool
    consumer_role: str


ARTIFACT_SPECS = (
    ArtifactSpec(
        "ARTIFACT_CHANGE_LOG",
        "ART-DOC-05-001",
        Path("docs/deliverables/00-control/artifact-change-log.json"),
        "metadata.register_id",
        True,
        "ARTIFACT_CHANGE_LOG",
    ),
    ArtifactSpec(
        "ARTIFACT_REGISTER",
        "ART-DOC-01-001",
        Path("docs/deliverables/00-control/artifact-register.json"),
        "metadata.register_id",
        True,
        "ARTIFACT_REGISTER",
    ),
    ArtifactSpec(
        "DESIGN_TRACEABILITY",
        "WS-DESIGN-TRACEABILITY-20260721-001",
        Path("docs/deliverables/04-design/design-traceability-register.json"),
        "metadata.register_id",
        True,
        "DESIGN_TRACEABILITY",
    ),
    ArtifactSpec(
        "IMPLEMENTATION_BACKLOG",
        "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260802-023",
        BACKLOG_PATH,
        "metadata.backlog_id",
        False,
        "BACKLOG_R023",
    ),
    ArtifactSpec(
        "IMPLEMENTATION_GAP",
        "WS-IMPLEMENTATION-GAP-ANALYSIS-20260802-023",
        GAP_PATH,
        "metadata.report_id",
        False,
        "GAP_R023",
    ),
    ArtifactSpec(
        "MODULE_REGISTER",
        "DEV-18",
        Path("docs/deliverables/05-implementation/module-register.json"),
        "metadata.artifact_type_ids",
        True,
        "MODULE_REGISTER",
    ),
    ArtifactSpec(
        "REQUIREMENTS_TRACEABILITY",
        "WS-REQ-RTM-DRAFT-20260721-R001",
        Path("docs/deliverables/03-requirements/rtm.json"),
        "metadata.document_id",
        True,
        "REQUIREMENTS_TRACEABILITY",
    ),
    ArtifactSpec(
        COMPLETION_ROLE,
        COMPLETION_DOCUMENT_ID,
        COMPLETION_PATH,
        "document_id",
        False,
        COMPLETION_ROLE,
    ),
)
SPEC_BY_ROLE = {spec.role: spec for spec in ARTIFACT_SPECS}

PINNED_PRODUCTION_SHA256_BY_ROLE: dict[str, str] = {
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
    COMPLETION_ROLE: (
        "072dfee0086702250a15dbf320d58ba477561c5d1ec999349e59a55a4df56992"
    ),
}

CHANGED_ROLES = sorted(SPEC_BY_ROLE)
PRODUCED_ROLES = ["IMPLEMENTATION_BACKLOG", "IMPLEMENTATION_GAP"]
TRACE_ROLES = {
    "ARTIFACT_CHANGE_LOG",
    "ARTIFACT_REGISTER",
    "DESIGN_TRACEABILITY",
    "MODULE_REGISTER",
    "REQUIREMENTS_TRACEABILITY",
}
FORBIDDEN_CHANGED_ROLES = {
    "IMPLEMENTATION_MANIFEST",
    "PLANNED_TEST_CASES",
    "PHASE1_EXACT257_SUCCESSOR_R012_LEDGER",
    "PHASE1_EXACT257_SUCCESSOR_R012_EVIDENCE",
    "PHASE1_EXACT257_SUCCESSOR_R012_CHECK_RECEIPT",
}
ARTIFACT_SUBJECT_IDS = [
    "DLV-DES-06",
    "DLV-DEV-01",
    "DLV-DEV-18",
    "DLV-DOC-01",
    "DLV-DOC-05",
    "DLV-REQ-16",
]
CHANGED_SUBJECT_IDS_BY_ROLE = {
    "ARTIFACT_CHANGE_LOG": ARTIFACT_SUBJECT_IDS,
    "ARTIFACT_REGISTER": ARTIFACT_SUBJECT_IDS,
    "DESIGN_TRACEABILITY": ["FP-048"],
    "IMPLEMENTATION_BACKLOG": ["FP-048"],
    "IMPLEMENTATION_GAP": ["FP-048", "GAP-057"],
    "MODULE_REGISTER": ["FP-048"],
    "REQUIREMENTS_TRACEABILITY": ["FP-048"],
}
PRODUCER_OUTPUT_SUBJECT_IDS_BY_ROLE = {
    role: CHANGED_SUBJECT_IDS_BY_ROLE[role]
    for role in PRODUCED_ROLES
}

UPDATE_EVENT_FIELDS = {
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
COMPLETION_EVENT_FIELDS = {
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

EXPECTED_COMPLETION_BOUNDARY = {
    "formal_test_ids": [f"TC-FP-048-{number:02d}" for number in range(1, 8)],
    "formal_test_status": "NOT_RUN",
    "formal_279_status": "NOT_RUN",
    "actual_device_status": "NOT_RUN",
    "external_tls_status": "NOT_RUN",
    "external_kms_status": "NOT_RUN",
    "external_cloud_status": "NOT_RUN",
    "external_backup_restore_status": "NOT_RUN",
    "external_security_review_status": "NOT_RUN",
    "external_legal_review_status": "NOT_RUN",
    "external_privacy_review_status": "NOT_RUN",
    "production_deployment_status": "NOT_RUN",
    "release_gate_count": 2,
    "release_gate_status": "NOT_RUN",
    "release_gates_waived": False,
    "external_independence_claimed": False,
    "release_status": "NOT_ELIGIBLE",
}


class CompletionApplyError(RuntimeError):
    """Raised before the canonical checkpoint can be replaced."""


class CompletionPostCommitError(RuntimeError):
    """Raised after checkpoint replacement when durability is uncertain."""


@dataclass(frozen=True)
class CompletionEvidence:
    documents_by_role: dict[str, dict[str, Any]]
    bindings_by_role: dict[str, dict[str, str]]
    receipt: dict[str, Any]
    update_occurred_at: str
    completion_occurred_at: str


@dataclass(frozen=True)
class PreparedProjection:
    root: Path
    checkpoint_path: Path
    source_checkpoint_bytes: bytes
    projected_checkpoint: dict[str, Any]
    projected_checkpoint_bytes: bytes
    update_event: dict[str, Any]
    completion_event: dict[str, Any]
    evidence: CompletionEvidence
    artifact_sha256_by_role: dict[str, str]


@dataclass(frozen=True)
class RetainedArtifactPin:
    role: str
    relative: Path
    descriptor: int
    identity: tuple[int, ...]
    expected_size: int
    expected_sha256: str


class ArtifactPinCohort:
    def __init__(self, root: Path, pins: list[RetainedArtifactPin]) -> None:
        self.root = root
        self.pins = pins

    def verify(self) -> None:
        for pin in self.pins:
            _verify_retained_artifact_pin(self.root, pin)

    def close(self, primary_error: BaseException | None = None) -> None:
        first_error: OSError | None = None
        while self.pins:
            pin = self.pins.pop()
            try:
                os.close(pin.descriptor)
            except OSError as exc:
                if first_error is None:
                    first_error = exc
        if first_error is None:
            return
        if primary_error is not None:
            try:
                primary_error.add_note(
                    "artifact pin descriptor cleanup also failed: "
                    f"{type(first_error).__name__}: {first_error}"
                )
            except BaseException:
                pass
            return
        raise first_error


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise CompletionApplyError(message)


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def json_bytes(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode(
        "utf-8"
    )


def _reject_duplicate_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        _require(key not in value, f"duplicate JSON member: {key}")
        value[key] = item
    return value


def _parse_json_bytes(content: bytes, *, label: str) -> dict[str, Any]:
    _require(not content.startswith(b"\xef\xbb\xbf"), f"{label} has a BOM")
    try:
        value = json.loads(
            content.decode("utf-8"),
            object_pairs_hook=_reject_duplicate_pairs,
            parse_constant=lambda token: (_ for _ in ()).throw(
                CompletionApplyError(f"{label} has non-finite JSON: {token}")
            ),
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise CompletionApplyError(f"{label} is not valid UTF-8 JSON") from exc
    _require(isinstance(value, dict), f"{label} root is not an object")
    return value


def _safe_file(root: Path, relative: Path) -> Path:
    _require(
        not relative.is_absolute()
        and relative.parts
        and ".." not in relative.parts
        and "." not in relative.parts,
        f"unsafe repository path: {relative}",
    )
    resolved_root = root.resolve(strict=True)
    candidate = resolved_root
    for part in relative.parts:
        candidate /= part
        _require(not candidate.is_symlink(), f"symlink is forbidden: {relative}")
    try:
        resolved = candidate.resolve(strict=True)
        resolved.relative_to(resolved_root)
    except (OSError, RuntimeError, ValueError) as exc:
        raise CompletionApplyError(
            f"repository file is missing or unsafe: {relative}"
        ) from exc
    _require(resolved.is_file(), f"repository path is not a file: {relative}")
    return resolved


def _parse_time(
    value: Any,
    *,
    label: str,
    allow_fractional_seconds: bool = False,
) -> datetime:
    _require(isinstance(value, str), f"{label} is missing")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise CompletionApplyError(f"{label} is not ISO-8601") from exc
    _require(
        parsed.tzinfo is not None and parsed.utcoffset() is not None,
        f"{label} lacks a timezone",
    )
    _require(parsed.isoformat() == value, f"{label} is not canonical ISO-8601")
    if not allow_fractional_seconds:
        _require(parsed.microsecond == 0, f"{label} must use second precision")
    return parsed


def _json_path(value: dict[str, Any], dotted: str) -> Any:
    current: Any = value
    for component in dotted.split("."):
        if not isinstance(current, dict) or component not in current:
            return None
        current = current[component]
    return current


def _resolve_sha256_pins(
    override: Mapping[str, str] | None,
) -> dict[str, str]:
    pins = dict(PINNED_PRODUCTION_SHA256_BY_ROLE if override is None else override)
    _require(set(pins) == set(SPEC_BY_ROLE), "artifact SHA-256 role set differs")
    pending = sorted(
        role
        for role, digest in pins.items()
        if not isinstance(digest, str) or SHA256_RE.fullmatch(digest) is None
    )
    _require(
        not pending,
        "production artifact SHA-256 is PENDING or invalid: " + ", ".join(pending),
    )
    return pins


def require_exact_source(content: bytes, source: dict[str, Any]) -> None:
    _require(len(content) == SOURCE_CHECKPOINT_BYTE_COUNT, "source byte count differs")
    _require(
        sha256_bytes(content) == SOURCE_CHECKPOINT_RAW_SHA256,
        "source checkpoint raw SHA-256 differs",
    )
    state = source.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    _require(source.get("schema_version") == "1.25.0", "source schema differs")
    _require(isinstance(state, dict), "source goal execution is missing")
    _require(isinstance(history, list) and len(history) == 42, "source is not seq42")
    tail = history[-1]
    _require(
        isinstance(tail, dict)
        and tail.get("sequence") == 42
        and tail.get("event_id") == SOURCE_EVENT_ID
        and tail.get("event_type") == "GOAL_STARTED"
        and tail.get("subject_goal_id") == GOAL_ID
        and tail.get("from_status") == "READY"
        and tail.get("to_status") == "IN_PROGRESS"
        and tail.get("event_sha256") == SOURCE_EVENT_SHA256
        and contract.event_sha256(tail) == SOURCE_EVENT_SHA256,
        "source seq42 seal differs",
    )
    _require(
        state.get("transition_history_anchor_sha256") == SOURCE_EVENT_SHA256,
        "source history anchor differs",
    )
    _require(
        state.get("status_by_goal", {}).get(GOAL_ID) == "IN_PROGRESS"
        and state.get("focus_goal_id") == GOAL_ID
        and state.get("focus_goal_path") == GOAL_PATH
        and state.get("focus_work_item_id") == WORK_ITEM_ID
        and state.get("ready_frontier_goal_ids")
        == [GOAL_ID, PARENT_GOAL_ID, EPIC12_GOAL_ID],
        "source FP048 IN_PROGRESS projection differs",
    )
    _require(
        state.get("pending_producer_completion_goal_id") in {None, ""},
        "source already has a pending producer transaction",
    )
    bindings = source.get("canonical_bindings")
    _require(isinstance(bindings, list) and len(bindings) == 38, "source canonical count differs")
    _require(
        COMPLETION_ROLE
        not in {row.get("role") for row in bindings if isinstance(row, dict)},
        "source already contains the FP048 completion role",
    )
    history_errors = contract.validate_generic_event_order(history)
    _require(not history_errors, "source history differs: " + "; ".join(history_errors))


def _load_evidence_documents(
    root: Path,
    pins: Mapping[str, str],
) -> tuple[dict[str, dict[str, Any]], dict[str, dict[str, str]]]:
    documents: dict[str, dict[str, Any]] = {}
    bindings: dict[str, dict[str, str]] = {}
    for spec in ARTIFACT_SPECS:
        path = _safe_file(root, spec.path)
        content = path.read_bytes()
        _require(
            sha256_bytes(content) == pins[spec.role],
            f"sealed physical artifact SHA-256 differs: {spec.role}",
        )
        value = _parse_json_bytes(content, label=spec.role)
        identity = _json_path(value, spec.identity_json_path)
        identity_matches = (
            spec.document_id in identity
            if isinstance(identity, list)
            else identity == spec.document_id
        )
        _require(identity_matches, f"physical artifact identity differs: {spec.role}")
        documents[spec.role] = value
        bindings[spec.role] = {
            "role": spec.role,
            "document_id": spec.document_id,
            "path": spec.path.as_posix(),
            "file_sha256": pins[spec.role],
        }
    return documents, bindings


def _consumer_binding_map(receipt: dict[str, Any]) -> dict[str, dict[str, Any]]:
    raw = receipt.get("downstream_consumer_bindings")
    _require(isinstance(raw, list), "completion downstream consumer bindings are missing")
    result: dict[str, dict[str, Any]] = {}
    for row in raw:
        _require(isinstance(row, dict), "completion consumer binding is malformed")
        role = row.get("role")
        _require(isinstance(role, str) and role not in result, "completion consumer role differs")
        result[role] = row
    return result


def _require_physical_sha256_binding(
    root: Path,
    value: Any,
    *,
    label: str,
    expected_path: Path | None = None,
) -> None:
    _require(isinstance(value, dict), f"{label} binding is missing")
    relative_value = value.get("path")
    digest = value.get("sha256")
    _require(
        isinstance(relative_value, str)
        and isinstance(digest, str)
        and SHA256_RE.fullmatch(digest) is not None,
        f"{label} binding is malformed",
    )
    relative = Path(relative_value)
    _require(
        expected_path is None or relative == expected_path,
        f"{label} path differs",
    )
    path = _safe_file(root, relative)
    _require(
        sha256_bytes(path.read_bytes()) == digest,
        f"{label} physical SHA-256 differs",
    )


def _validate_receipt(
    root: Path,
    source: dict[str, Any],
    receipt: dict[str, Any],
    bindings: Mapping[str, dict[str, str]],
) -> tuple[str, str]:
    expected = {
        "schema_version": "1.0",
        "document_id": COMPLETION_DOCUMENT_ID,
        "evidence_type": "WORK_ITEM_EXECUTION_RECEIPT",
        "status": "ACCEPTED",
        "result": "PASS",
        "target_goal_id": GOAL_ID,
        "target_goal_content_sha256": GOAL_SHA256,
        "work_item_id": WORK_ITEM_ID,
        "source_policy_ids": ["FP-048"],
        "gap_ids": ["GAP-057"],
        "target_completion_level": "INTERNAL_POLICY_CONFORMANCE_REASSESSED",
        "execution_start_event_sha256": SOURCE_EVENT_SHA256,
    }
    for field, wanted in expected.items():
        _require(receipt.get(field) == wanted, f"completion receipt {field} differs")

    start_event = source["goal_execution"]["transition_history"][-1]
    _require(
        receipt.get("implementation_start_gate_binding")
        == start_event.get("implementation_start_gate_binding"),
        "completion receipt start-gate binding differs",
    )
    session_event = receipt.get("execution_session_event")
    if session_event is not None:
        _require(
            isinstance(session_event, dict)
            and session_event.get("sequence") == 42
            and session_event.get("event_id") == SOURCE_EVENT_ID
            and session_event.get("event_type") == "GOAL_STARTED"
            and session_event.get("event_sha256") == SOURCE_EVENT_SHA256,
            "completion execution-session event differs",
        )

    executor = receipt.get("executor")
    reviewer = receipt.get("reviewer")
    _require(
        isinstance(executor, dict) and isinstance(reviewer, dict),
        "completion actors are missing",
    )
    _require(
        isinstance(executor.get("id"), str)
        and executor.get("id")
        and isinstance(reviewer.get("id"), str)
        and reviewer.get("id")
        and executor.get("id") != reviewer.get("id")
        and executor.get("task") != reviewer.get("task"),
        "completion executor/reviewer separation differs",
    )
    _require(
        reviewer.get("separate_internal_review_pass") is True
        and reviewer.get("external_independence_claimed") is False
        and reviewer.get("decision") == "APPROVED"
        and reviewer.get("authority") == "INTERNAL_REPOSITORY_CONTROL",
        "completion internal review boundary differs",
    )
    _require_physical_sha256_binding(
        root,
        receipt.get("reviewer_provenance"),
        label="completion reviewer provenance",
        expected_path=INDEPENDENT_REVIEW_PATH,
    )
    result_evidence = receipt.get("result_evidence")
    _require(
        isinstance(result_evidence, list) and len(result_evidence) == 3,
        "completion result evidence set differs",
    )
    expected_result_paths = {
        "IMPLEMENTATION_RECORD": IMPLEMENTATION_RESULT_PATH,
        "VERIFICATION_RESULT": VERIFICATION_RESULT_PATH,
        "SUCCESSOR_TRACE": SUCCESSOR_TRACE_PATH,
    }
    result_by_kind = {
        row.get("kind"): row
        for row in result_evidence
        if isinstance(row, dict) and isinstance(row.get("kind"), str)
    }
    _require(
        set(result_by_kind) == set(expected_result_paths),
        "completion result evidence kinds differ",
    )
    for kind, expected_path in expected_result_paths.items():
        _require_physical_sha256_binding(
            root,
            result_by_kind[kind],
            label=f"completion {kind}",
            expected_path=expected_path,
        )
    _require(
        receipt.get("completion_boundary") == EXPECTED_COMPLETION_BOUNDARY,
        "completion formal/external/release boundary differs",
    )

    window = receipt.get("execution_window")
    _require(isinstance(window, dict), "completion execution window is missing")
    tail_at = _parse_time(start_event.get("occurred_at"), label="seq42 occurred_at")
    started_at = _parse_time(
        window.get("started_at"),
        label="execution started_at",
        allow_fractional_seconds=True,
    )
    ended_at = _parse_time(
        window.get("ended_at"),
        label="execution ended_at",
        allow_fractional_seconds=True,
    )
    completed_at = _parse_time(receipt.get("completed_at"), label="completion completed_at")
    reviewed_at = _parse_time(reviewer.get("decided_at"), label="reviewer decided_at")
    generated_at = _parse_time(receipt.get("generated_at"), label="completion generated_at")
    _require(
        tail_at <= started_at <= ended_at <= completed_at <= reviewed_at <= generated_at,
        "completion receipt chronology differs",
    )

    manifest = receipt.get("output_evidence_manifest")
    _require(isinstance(manifest, list) and manifest, "completion evidence manifest is missing")
    _require(
        contract.canonical_json_sha256(manifest)
        == receipt.get("output_evidence_manifest_sha256"),
        "completion evidence manifest seal differs",
    )
    for row in manifest:
        _require(
            isinstance(row, dict)
            and isinstance(row.get("path"), str)
            and isinstance(row.get("sha256"), str)
            and SHA256_RE.fullmatch(row["sha256"]) is not None,
            "completion evidence manifest row differs",
        )
        _require_physical_sha256_binding(
            root,
            row,
            label=f"completion evidence manifest {row['path']}",
        )

    consumers = _consumer_binding_map(receipt)
    required_consumer_roles = TRACE_ROLES | {
        "GAP_R023",
        "BACKLOG_R023",
    }
    _require(
        required_consumer_roles.issubset(consumers),
        "completion consumer evidence omits a canonical FP048 output",
    )
    for spec in ARTIFACT_SPECS:
        if spec.role == COMPLETION_ROLE:
            continue
        consumer = consumers[spec.consumer_role]
        _require(
            consumer.get("path") == spec.path.as_posix()
            and consumer.get("sha256") == bindings[spec.role]["file_sha256"],
            f"completion consumer binding differs: {spec.role}",
        )

    update_time = max(tail_at + timedelta(seconds=1), generated_at)
    _require(
        update_time - generated_at <= timedelta(hours=1),
        "completion evidence is stale for seq43",
    )
    completion_time = update_time + timedelta(seconds=1)
    return update_time.isoformat(), completion_time.isoformat()


def _validate_next_pointer(
    gap: dict[str, Any],
    backlog: dict[str, Any],
) -> None:
    action = backlog.get("next_single_action")
    _require(
        action
        == {
            "epic_id": "EPIC-03",
            "source_policy_id": NEXT_POLICY_ID,
            "gap_id": NEXT_GAP_ID,
            "status": "PLANNED_NEXT",
            "action": NEXT_ACTION,
        },
        "r023 FP008/GAP017 next_single_action differs",
    )
    adjacent = gap.get("reassessment_scope", {}).get("next_adjacent_gap")
    _require(
        isinstance(adjacent, dict)
        and adjacent.get("source_policy_id") == NEXT_POLICY_ID
        and adjacent.get("gap_id") == NEXT_GAP_ID,
        "r023 FP008/GAP017 next-adjacent pointer differs",
    )
    assessments = gap.get("assessments")
    gap057 = (
        [row for row in assessments if isinstance(row, dict) and row.get("gap_id") == "GAP-057"]
        if isinstance(assessments, list)
        else []
    )
    _require(
        len(gap057) == 1
        and gap057[0].get("source_policy_id") == "FP-048"
        and gap057[0].get("status") == "PARTIAL",
        "r023 GAP-057 reassessment differs",
    )


def load_completion_evidence(
    root: Path,
    source: dict[str, Any],
    pins: Mapping[str, str],
) -> CompletionEvidence:
    documents, bindings = _load_evidence_documents(root, pins)
    _validate_next_pointer(
        documents["IMPLEMENTATION_GAP"],
        documents["IMPLEMENTATION_BACKLOG"],
    )
    update_at, completion_at = _validate_receipt(
        root,
        source,
        documents[COMPLETION_ROLE],
        bindings,
    )
    return CompletionEvidence(
        documents_by_role=documents,
        bindings_by_role=bindings,
        receipt=documents[COMPLETION_ROLE],
        update_occurred_at=update_at,
        completion_occurred_at=completion_at,
    )


def _project_canonical_bindings(
    source: dict[str, Any],
    evidence: CompletionEvidence,
) -> list[dict[str, Any]]:
    raw = source.get("canonical_bindings")
    _require(isinstance(raw, list), "source canonical bindings are missing")
    projected: list[dict[str, Any]] = []
    seen: set[str] = set()
    for source_binding in raw:
        _require(isinstance(source_binding, dict), "source canonical binding is malformed")
        role = source_binding.get("role")
        _require(isinstance(role, str) and role not in seen, "source canonical role differs")
        seen.add(role)
        if role not in SPEC_BY_ROLE or role == COMPLETION_ROLE:
            projected.append(copy.deepcopy(source_binding))
            continue
        spec = SPEC_BY_ROLE[role]
        binding = copy.deepcopy(source_binding)
        binding.update(evidence.bindings_by_role[role])
        binding["identity_json_path"] = spec.identity_json_path
        binding["mutable"] = spec.mutable
        projected.append(binding)
    _require(COMPLETION_ROLE not in seen, "FP048 completion role already exists")
    completion_spec = SPEC_BY_ROLE[COMPLETION_ROLE]
    projected.append(
        {
            **evidence.bindings_by_role[COMPLETION_ROLE],
            "identity_json_path": completion_spec.identity_json_path,
            "mutable": completion_spec.mutable,
        }
    )
    _require(len(projected) == 39, "projected canonical binding count differs")
    return projected


def _project_gap_snapshot(
    gap: dict[str, Any],
    backlog: dict[str, Any],
) -> dict[str, Any]:
    gap_metadata = gap.get("metadata")
    backlog_metadata = backlog.get("metadata")
    assessments = gap.get("assessments")
    epics = backlog.get("epics")
    status_counts = gap.get("summary", {}).get("status_counts")
    implementation_snapshot = gap.get("implementation_snapshot")
    _require(
        isinstance(gap_metadata, dict)
        and isinstance(backlog_metadata, dict)
        and isinstance(assessments, list)
        and isinstance(epics, list)
        and isinstance(status_counts, dict)
        and isinstance(implementation_snapshot, dict),
        "r023 checkpoint Gap snapshot source differs",
    )
    epic_status_counts: dict[str, int] = {}
    for epic in epics:
        _require(isinstance(epic, dict), "r023 backlog epic is malformed")
        status = epic.get("current_status")
        _require(isinstance(status, str) and status, "r023 backlog epic status differs")
        epic_status_counts[status] = epic_status_counts.get(status, 0) + 1
    return {
        "report_id": gap_metadata.get("report_id"),
        "report_version": gap_metadata.get("version"),
        "assessment_count": len(assessments),
        "status_counts": copy.deepcopy(status_counts),
        "backlog_id": backlog_metadata.get("backlog_id"),
        "epic_count": len(epics),
        "epic_status_counts": epic_status_counts,
        "implementation_snapshot": copy.deepcopy(implementation_snapshot),
    }


def _runtime_projection(
    state: dict[str, Any],
    *,
    artifact_queue: dict[str, Any],
    completion_boundary: dict[str, Any],
) -> dict[str, Any]:
    return {
        "focus_goal_id": state["focus_goal_id"],
        "focus_goal_path": state["focus_goal_path"],
        "focus_work_item_id": state["focus_work_item_id"],
        "focus_source": state["focus_source"],
        "ready_frontier_goal_ids": copy.deepcopy(state["ready_frontier_goal_ids"]),
        "blocked_goal_ids": copy.deepcopy(state["blocked_goal_ids"]),
        "pending_questions": copy.deepcopy(state["pending_questions"]),
        "open_question_count": state["open_question_count"],
        "artifact_work_queue_sha256": contract.canonical_json_sha256(artifact_queue),
        "completion_boundary_sha256": contract.canonical_json_sha256(completion_boundary),
        "activation_status": state["activation_status"],
        "package_status": state["package_status"],
    }


def derive_runtime(
    root: Path,
    checkpoint: dict[str, Any],
    ready_frontier_goal_ids: list[str],
) -> tuple[dict[str, Any], dict[str, Any]]:
    return materialize.derive_queue_and_boundary(
        root,
        checkpoint,
        ready_frontier_goal_ids=ready_frontier_goal_ids,
    )


def _update_working_snapshot(
    root: Path,
    checkpoint: dict[str, Any],
    snapshot_hasher: Callable[[Path, list[str]], tuple[str, str]],
) -> None:
    snapshot = checkpoint.get("working_tree_snapshot")
    _require(isinstance(snapshot, dict), "working snapshot is missing")
    paths = snapshot.get("managed_changed_paths")
    _require(isinstance(paths, list), "working snapshot path list is missing")
    paths = sorted(
        set(paths)
        | {
            GAP_PATH.as_posix(),
            BACKLOG_PATH.as_posix(),
            COMPLETION_PATH.as_posix(),
        }
    )
    path_hash, content_hash = snapshot_hasher(root, paths)
    snapshot["managed_changed_paths"] = paths
    snapshot["managed_changed_path_count"] = len(paths)
    snapshot["path_set_sha256"] = path_hash
    snapshot["content_set_sha256"] = content_hash
    snapshot["scope"] = FINAL_SCOPE


def project_seq43_44(
    root: Path,
    source: dict[str, Any],
    evidence: CompletionEvidence,
    *,
    runtime_deriver: Callable[
        [Path, dict[str, Any], list[str]],
        tuple[dict[str, Any], dict[str, Any]],
    ] = derive_runtime,
    snapshot_hasher: Callable[[Path, list[str]], tuple[str, str]] = (
        contract.working_snapshot_hashes
    ),
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    checkpoint = copy.deepcopy(source)
    checkpoint["canonical_bindings"] = _project_canonical_bindings(source, evidence)
    checkpoint["implementation_gap_snapshot"] = _project_gap_snapshot(
        evidence.documents_by_role["IMPLEMENTATION_GAP"],
        evidence.documents_by_role["IMPLEMENTATION_BACKLOG"],
    )
    state = checkpoint["goal_execution"]
    snapshot = contract.canonical_binding_snapshot(checkpoint)
    _require(len(snapshot) == 39, "canonical snapshot role count differs")

    queue43, boundary43 = runtime_deriver(
        root,
        checkpoint,
        [GOAL_ID, PARENT_GOAL_ID, EPIC12_GOAL_ID],
    )
    state["artifact_work_queue"] = queue43
    state["completion_boundary"] = boundary43
    update_at = evidence.update_occurred_at
    completion_binding = evidence.bindings_by_role[COMPLETION_ROLE]
    update_event: dict[str, Any] = {
        "sequence": 43,
        "event_id": CANONICAL_UPDATE_EVENT_ID,
        "event_type": "CANONICAL_BINDINGS_UPDATED",
        "occurred_on": datetime.fromisoformat(update_at).date().isoformat(),
        "occurred_at": update_at,
        "previous_focus_goal_id": GOAL_ID,
        "previous_focus_content_sha256": GOAL_SHA256,
        "focus_goal_id": GOAL_ID,
        "focus_goal_content_sha256": GOAL_SHA256,
        "from_status": "IN_PROGRESS",
        "to_status": "IN_PROGRESS",
        "static_plan_manifest_sha256": MANIFEST_SHA256,
        "status_changes": {},
        "runtime_after": _runtime_projection(
            state,
            artifact_queue=queue43,
            completion_boundary=boundary43,
        ),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [
            row["resolution_id"] for row in state["blocker_resolution_history"]
        ],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": list(CHANGED_ROLES),
        "previous_event_sha256": SOURCE_EVENT_SHA256,
        "produced_by_goal_id": GOAL_ID,
        "produced_binding_roles": list(PRODUCED_ROLES),
        "producer_completion_receipt_binding": copy.deepcopy(completion_binding),
        "changed_binding_roles": list(CHANGED_ROLES),
        "changed_subject_ids_by_role": copy.deepcopy(
            CHANGED_SUBJECT_IDS_BY_ROLE
        ),
        "producer_output_subject_ids_by_role": copy.deepcopy(
            PRODUCER_OUTPUT_SUBJECT_IDS_BY_ROLE
        ),
        "impact_closure_goal_ids": [PARENT_GOAL_ID],
        "impact_disposition_by_goal": {
            PARENT_GOAL_ID: {
                "result": "REVALIDATION_REFRESH_REQUIRED",
                "target_status": "READY",
            }
        },
        "reopened_completion_event_sha256_by_goal": {},
        "canonical_binding_snapshot_after": copy.deepcopy(snapshot),
    }
    update_event["event_sha256"] = contract.event_sha256(update_event)
    _require(set(update_event) == UPDATE_EVENT_FIELDS, "seq43 event field set differs")
    state["transition_history"].append(update_event)
    state["pending_producer_completion_goal_id"] = GOAL_ID

    state["status_by_goal"][GOAL_ID] = "COMPLETE_AT_TARGET"
    state["focus_goal_id"] = PARENT_GOAL_ID
    state["focus_goal_path"] = PARENT_GOAL_PATH
    state["focus_work_item_id"] = ""
    state["focus_source"] = "WORKSTREAM_GRAPH"
    state["ready_frontier_goal_ids"] = [PARENT_GOAL_ID, EPIC12_GOAL_ID]
    queue44, boundary44 = runtime_deriver(
        root,
        checkpoint,
        [PARENT_GOAL_ID, EPIC12_GOAL_ID],
    )
    state["artifact_work_queue"] = queue44
    state["completion_boundary"] = boundary44
    completion_evidence = copy.deepcopy(state["completion_evidence_by_goal"])
    completion_evidence[GOAL_ID] = [COMPLETION_ROLE]
    completion_at = evidence.completion_occurred_at
    completion_event: dict[str, Any] = {
        "sequence": 44,
        "event_id": COMPLETION_EVENT_ID,
        "event_type": "GOAL_COMPLETED",
        "occurred_on": datetime.fromisoformat(completion_at).date().isoformat(),
        "occurred_at": completion_at,
        "previous_focus_goal_id": GOAL_ID,
        "previous_focus_content_sha256": GOAL_SHA256,
        "focus_goal_id": PARENT_GOAL_ID,
        "focus_goal_content_sha256": PARENT_GOAL_SHA256,
        "subject_goal_id": GOAL_ID,
        "from_status": "IN_PROGRESS",
        "to_status": "COMPLETE_AT_TARGET",
        "static_plan_manifest_sha256": MANIFEST_SHA256,
        "status_changes": {GOAL_ID: "COMPLETE_AT_TARGET"},
        "runtime_after": _runtime_projection(
            state,
            artifact_queue=queue44,
            completion_boundary=boundary44,
        ),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [
            row["resolution_id"] for row in state["blocker_resolution_history"]
        ],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": [COMPLETION_ROLE],
        "previous_event_sha256": update_event["event_sha256"],
        "canonical_update_event_sha256": update_event["event_sha256"],
        "completion_receipt_binding": copy.deepcopy(completion_binding),
        "completion_evidence_bindings": {
            COMPLETION_ROLE: copy.deepcopy(completion_binding)
        },
        "completion_evidence_by_goal_after": completion_evidence,
        "canonical_binding_snapshot_after": copy.deepcopy(snapshot),
    }
    completion_event["event_sha256"] = contract.event_sha256(completion_event)
    _require(
        set(completion_event) == COMPLETION_EVENT_FIELDS,
        "seq44 event field set differs",
    )
    state["transition_history"].append(completion_event)
    state["transition_history_anchor_sha256"] = completion_event["event_sha256"]
    state["validation_cutoff_at"] = completion_at
    state["completion_evidence_by_goal"] = completion_evidence
    state["pending_producer_completion_goal_id"] = ""

    current = checkpoint["current_work"]
    current["work_item_id"] = NEXT_WORK_ITEM_ID
    current["last_completed_work_summary"] = (
        "FP048/GAP-057 repository-internal encryption, key lifecycle, access "
        "audit, incident controls, trace successors, and internal review completed"
    )
    current["current_focus"] = FINAL_CURRENT_FOCUS
    current["next_action"] = NEXT_ACTION
    current["release_completion_claimed"] = False

    handoff = checkpoint["session_handoff"]
    handoff["current_epic"] = FINAL_HANDOFF_EPIC
    handoff["next_single_action"] = NEXT_ACTION
    handoff["last_updated_by_work_item"] = WORK_ITEM_ID
    handoff["last_verification_status"] = (
        "PASS_INTERNAL_ONLY_FORMAL_EXTERNAL_RELEASE_NOT_RUN"
    )
    _update_working_snapshot(root, checkpoint, snapshot_hasher)
    return checkpoint, update_event, completion_event


def _changed_canonical_roles(
    source: dict[str, Any],
    projected: dict[str, Any],
) -> list[str]:
    before = contract.canonical_binding_snapshot(source)
    after = contract.canonical_binding_snapshot(projected)
    return sorted(
        role
        for role in set(before) | set(after)
        if before.get(role) != after.get(role)
    )


def validate_exact_projection(
    source: dict[str, Any],
    projected: dict[str, Any],
    evidence: CompletionEvidence,
) -> None:
    source_state = source["goal_execution"]
    state = projected["goal_execution"]
    history = state.get("transition_history")
    _require(isinstance(history, list) and len(history) == 44, "projection is not seq44")
    _require(history[:-2] == source_state["transition_history"], "pre-seq43 history changed")
    update, completion = history[-2:]
    _require(
        update.get("sequence") == 43 and completion.get("sequence") == 44,
        "seq43/44 adjacency differs",
    )
    _require(update.get("event_type") == "CANONICAL_BINDINGS_UPDATED", "seq43 type differs")
    _require(completion.get("event_type") == "GOAL_COMPLETED", "seq44 type differs")
    _require(update.get("event_sha256") == contract.event_sha256(update), "seq43 seal differs")
    _require(
        completion.get("event_sha256") == contract.event_sha256(completion),
        "seq44 seal differs",
    )
    _require(
        completion.get("previous_event_sha256") == update.get("event_sha256"),
        "event inserted between seq43/44",
    )
    _require(set(update) == UPDATE_EVENT_FIELDS, "seq43 exact field set differs")
    _require(set(completion) == COMPLETION_EVENT_FIELDS, "seq44 exact field set differs")
    _require(update.get("changed_binding_roles") == CHANGED_ROLES, "seq43 changed roles differ")
    _require(update.get("evidence_refs") == CHANGED_ROLES, "seq43 evidence roles differ")
    _require(
        update.get("produced_binding_roles") == PRODUCED_ROLES,
        "seq43 producer authority differs",
    )
    _require(
        update.get("changed_subject_ids_by_role") == CHANGED_SUBJECT_IDS_BY_ROLE,
        "seq43 changed subjects differ",
    )
    _require(
        update.get("producer_output_subject_ids_by_role")
        == PRODUCER_OUTPUT_SUBJECT_IDS_BY_ROLE,
        "seq43 producer subjects differ",
    )
    _require(not (set(CHANGED_ROLES) & FORBIDDEN_CHANGED_ROLES), "forbidden role changed")
    _require(
        _changed_canonical_roles(source, projected) == CHANGED_ROLES,
        "physical canonical role delta differs",
    )
    _require(len(projected.get("canonical_bindings", [])) == 39, "final canonical count differs")
    snapshot = contract.canonical_binding_snapshot(projected)
    _require(len(snapshot) == 39, "final canonical snapshot count differs")
    _require(
        update.get("canonical_binding_snapshot_after") == snapshot,
        "seq43 canonical snapshot differs",
    )
    _require(
        completion.get("canonical_binding_snapshot_after") == snapshot,
        "seq44 canonical snapshot differs",
    )
    for role in CHANGED_ROLES:
        _require(
            snapshot.get(role) == evidence.bindings_by_role[role],
            f"canonical evidence binding differs: {role}",
        )
    _require(
        state.get("status_by_goal", {}).get(GOAL_ID) == "COMPLETE_AT_TARGET",
        "FP048 final status differs",
    )
    _require(state.get("focus_goal_id") == PARENT_GOAL_ID, "final focus differs")
    _require(
        state.get("ready_frontier_goal_ids")
        == [PARENT_GOAL_ID, EPIC12_GOAL_ID],
        "final ready frontier differs",
    )
    _require(
        state.get("pending_producer_completion_goal_id") in {None, ""},
        "producer transaction remains open",
    )
    _require(
        state.get("completion_evidence_by_goal", {}).get(GOAL_ID)
        == [COMPLETION_ROLE],
        "FP048 completion evidence differs",
    )
    _require(
        completion.get("completion_evidence_by_goal_after")
        == state.get("completion_evidence_by_goal"),
        "seq44 completion evidence projection differs",
    )
    _require(
        projected["current_work"].get("work_item_id") == NEXT_WORK_ITEM_ID,
        "FP008 work pointer differs",
    )
    _require(
        projected["current_work"].get("next_action") == NEXT_ACTION,
        "FP008 action pointer differs",
    )
    _require(
        projected["session_handoff"].get("next_single_action") == NEXT_ACTION,
        "handoff FP008 pointer differs",
    )
    _require(
        projected.get("implementation_gap_snapshot")
        == _project_gap_snapshot(
            evidence.documents_by_role["IMPLEMENTATION_GAP"],
            evidence.documents_by_role["IMPLEMENTATION_BACKLOG"],
        ),
        "r023 checkpoint Gap snapshot differs",
    )

    for field in ("authority_boundary", "verification_boundary", "approved_state"):
        _require(projected.get(field) == source.get(field), f"{field} credit changed")
    _require(
        state.get("standing_execution_authority")
        == source_state.get("standing_execution_authority"),
        "generic POLICY_GAP_WORK authority expanded",
    )
    _require(
        projected.get("approved_state", {}).get("release_status") == "NOT_ELIGIBLE"
        and projected.get("verification_boundary", {}).get("formal_test_pass_claimed") is False
        and projected.get("verification_boundary", {}).get("release_eligible") is False,
        "formal/external/release credit was promoted",
    )

    allowed_top = {
        "canonical_bindings",
        "current_work",
        "goal_execution",
        "implementation_gap_snapshot",
        "session_handoff",
        "working_tree_snapshot",
    }
    for key in set(source) | set(projected):
        if key not in allowed_top:
            _require(
                source.get(key) == projected.get(key),
                f"unauthorized top-level mutation: {key}",
            )
    allowed_state = {
        "artifact_work_queue",
        "completion_boundary",
        "completion_evidence_by_goal",
        "focus_goal_id",
        "focus_goal_path",
        "focus_source",
        "focus_work_item_id",
        "pending_producer_completion_goal_id",
        "ready_frontier_goal_ids",
        "status_by_goal",
        "transition_history",
        "transition_history_anchor_sha256",
        "validation_cutoff_at",
    }
    for key in set(source_state) | set(state):
        if key not in allowed_state:
            _require(
                source_state.get(key) == state.get(key),
                f"unauthorized runtime mutation: {key}",
            )
    history_errors = contract.validate_generic_event_order(history)
    _require(not history_errors, "projected generic history differs: " + "; ".join(history_errors))


def run_continuation_checker(root: Path, checkpoint_path: Path) -> list[str]:
    return contract.validate(
        root,
        checkpoint_path,
        contract.V23_ARCHIVE_RELATIVE,
        contract.V24_MANIFEST_RELATIVE,
    )


def run_goal_graph_checker(root: Path, checkpoint_path: Path) -> list[str]:
    checkpoint = _parse_json_bytes(
        _safe_file(root, checkpoint_path).read_bytes(),
        label="projected checkpoint",
    )
    return goal_graph.validate_v24_artifact_work_queue(root, checkpoint)


def validate_projected_with_both_checkers(
    root: Path,
    projected_bytes: bytes,
    *,
    continuation_checker: Callable[[Path, Path], list[str]] = run_continuation_checker,
    goal_graph_checker: Callable[[Path, Path], list[str]] = run_goal_graph_checker,
) -> None:
    checkpoint_dir = root / CHECKPOINT_RELATIVE.parent
    descriptor, temporary_name = tempfile.mkstemp(
        dir=checkpoint_dir,
        prefix=".walksafe-fp048-seq43-44-preflight.",
        suffix=".json",
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(projected_bytes)
            stream.flush()
            os.fsync(stream.fileno())
        relative = temporary.relative_to(root)
        continuation_errors = continuation_checker(root, relative)
        _require(
            not continuation_errors,
            "projected continuation v2.4 check failed: "
            + "; ".join(continuation_errors),
        )
        graph_errors = goal_graph_checker(root, relative)
        _require(
            not graph_errors,
            "projected goal-graph v2.4 check failed: " + "; ".join(graph_errors),
        )
    finally:
        temporary.unlink(missing_ok=True)


def prepare_projection(
    root: Path,
    *,
    artifact_sha256_by_role: Mapping[str, str] | None = None,
    source_validator: Callable[[bytes, dict[str, Any]], None] = require_exact_source,
    runtime_deriver: Callable[
        [Path, dict[str, Any], list[str]],
        tuple[dict[str, Any], dict[str, Any]],
    ] = derive_runtime,
    snapshot_hasher: Callable[[Path, list[str]], tuple[str, str]] = (
        contract.working_snapshot_hashes
    ),
    continuation_checker: Callable[[Path, Path], list[str]] = run_continuation_checker,
    goal_graph_checker: Callable[[Path, Path], list[str]] = run_goal_graph_checker,
) -> PreparedProjection:
    root = root.resolve(strict=True)
    pins = _resolve_sha256_pins(artifact_sha256_by_role)
    checkpoint_path = _safe_file(root, CHECKPOINT_RELATIVE)
    source_bytes = checkpoint_path.read_bytes()
    source = _parse_json_bytes(source_bytes, label="source checkpoint")
    source_validator(source_bytes, source)
    _require(checkpoint_path.read_bytes() == source_bytes, "source changed during validation")
    evidence = load_completion_evidence(root, source, pins)
    _require(
        checkpoint_path.read_bytes() == source_bytes,
        "source changed during evidence validation",
    )
    projected, update, completion = project_seq43_44(
        root,
        source,
        evidence,
        runtime_deriver=runtime_deriver,
        snapshot_hasher=snapshot_hasher,
    )
    validate_exact_projection(source, projected, evidence)
    projected_bytes = json_bytes(projected)
    validate_projected_with_both_checkers(
        root,
        projected_bytes,
        continuation_checker=continuation_checker,
        goal_graph_checker=goal_graph_checker,
    )
    _require(
        checkpoint_path.read_bytes() == source_bytes,
        "source changed during checker preflight",
    )
    return PreparedProjection(
        root=root,
        checkpoint_path=checkpoint_path,
        source_checkpoint_bytes=source_bytes,
        projected_checkpoint=projected,
        projected_checkpoint_bytes=projected_bytes,
        update_event=update,
        completion_event=completion,
        evidence=evidence,
        artifact_sha256_by_role=pins,
    )


def _write_and_sync(stream: Any, content: bytes) -> None:
    stream.write(content)
    stream.flush()
    os.fsync(stream.fileno())


def _fsync_directory(path: Path) -> None:
    descriptor = os.open(
        path,
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | os.O_DIRECTORY
        | getattr(os, "O_NOFOLLOW", 0),
    )
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _file_identity(info: os.stat_result) -> tuple[int, ...]:
    return (
        info.st_dev,
        info.st_ino,
        info.st_mode,
        info.st_uid,
        info.st_gid,
        info.st_nlink,
        info.st_size,
        info.st_mtime_ns,
        info.st_ctime_ns,
    )


def _directory_identity(info: os.stat_result) -> tuple[int, ...]:
    return (
        info.st_dev,
        info.st_ino,
        info.st_mode,
        info.st_uid,
        info.st_gid,
    )


def _canonical_parent_matches(
    parent: Path,
    expected_identity: tuple[int, ...],
) -> bool:
    try:
        observed = os.stat(parent, follow_symlinks=False)
    except OSError:
        return False
    return (
        stat.S_ISDIR(observed.st_mode)
        and _directory_identity(observed) == expected_identity
    )


def _stable_file_identity(info: os.stat_result) -> tuple[int, ...]:
    return (
        info.st_dev,
        info.st_ino,
        info.st_mode,
        info.st_uid,
        info.st_gid,
        info.st_nlink,
        info.st_size,
    )


def _read_descriptor_exact(descriptor: int, maximum_bytes: int) -> bytes:
    os.lseek(descriptor, 0, os.SEEK_SET)
    chunks: list[bytes] = []
    observed = 0
    while observed <= maximum_bytes:
        chunk = os.read(descriptor, min(1024 * 1024, maximum_bytes + 1 - observed))
        if not chunk:
            break
        chunks.append(chunk)
        observed += len(chunk)
    _require(observed <= maximum_bytes, "checkpoint exceeds retained source size")
    return b"".join(chunks)


def _verify_retained_checkpoint(
    parent_fd: int,
    name: str,
    source_fd: int,
    identity: tuple[int, ...],
    expected_source: bytes,
) -> None:
    path_before = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
    fd_before = os.fstat(source_fd)
    _require(
        _file_identity(path_before) == identity == _file_identity(fd_before),
        "source checkpoint identity changed under publication lock",
    )
    observed = _read_descriptor_exact(source_fd, len(expected_source))
    path_after = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
    fd_after = os.fstat(source_fd)
    _require(
        _file_identity(path_after) == identity == _file_identity(fd_after),
        "source checkpoint identity changed while retained",
    )
    _require(
        observed == expected_source,
        "source checkpoint bytes changed under publication lock",
    )


def _read_entry_exact(parent_fd: int, name: str, maximum_bytes: int) -> bytes:
    descriptor = os.open(
        name,
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0),
        dir_fd=parent_fd,
    )
    try:
        return _read_descriptor_exact(descriptor, maximum_bytes)
    finally:
        os.close(descriptor)


def _verify_retained_artifact_pin(root: Path, pin: RetainedArtifactPin) -> None:
    canonical = _safe_file(root, pin.relative)
    path_before = os.stat(canonical, follow_symlinks=False)
    descriptor_before = os.fstat(pin.descriptor)
    _require(
        _file_identity(path_before)
        == pin.identity
        == _file_identity(descriptor_before),
        f"retained physical artifact identity differs: {pin.role}",
    )
    observed = _read_descriptor_exact(pin.descriptor, pin.expected_size)
    path_after = os.stat(canonical, follow_symlinks=False)
    descriptor_after = os.fstat(pin.descriptor)
    _require(
        _file_identity(path_after)
        == pin.identity
        == _file_identity(descriptor_after),
        f"retained physical artifact changed while read: {pin.role}",
    )
    _require(
        len(observed) == pin.expected_size
        and sha256_bytes(observed) == pin.expected_sha256,
        f"retained physical artifact SHA-256 differs: {pin.role}",
    )


def retain_artifact_pin_cohort(
    root: Path,
    pins: Mapping[str, str],
) -> ArtifactPinCohort:
    retained: list[RetainedArtifactPin] = []
    try:
        for spec in ARTIFACT_SPECS:
            canonical = _safe_file(root, spec.path)
            descriptor = os.open(
                canonical,
                os.O_RDONLY
                | getattr(os, "O_CLOEXEC", 0)
                | getattr(os, "O_NOFOLLOW", 0),
            )
            try:
                info = os.fstat(descriptor)
                path_info = os.stat(canonical, follow_symlinks=False)
                _require(
                    _file_identity(info) == _file_identity(path_info)
                    and stat.S_ISREG(info.st_mode)
                    and info.st_uid == os.geteuid()
                    and info.st_nlink == 1
                    and stat.S_IMODE(info.st_mode) == 0o600,
                    f"physical artifact authority differs: {spec.role}",
                )
                observed = _read_descriptor_exact(descriptor, info.st_size)
                _require(
                    sha256_bytes(observed) == pins[spec.role],
                    f"sealed physical artifact SHA-256 differs: {spec.role}",
                )
                retained.append(
                    RetainedArtifactPin(
                        role=spec.role,
                        relative=spec.path,
                        descriptor=descriptor,
                        identity=_file_identity(info),
                        expected_size=info.st_size,
                        expected_sha256=pins[spec.role],
                    )
                )
            except BaseException:
                os.close(descriptor)
                raise
        cohort = ArtifactPinCohort(root, retained)
        cohort.verify()
        return cohort
    except BaseException as exc:
        ArtifactPinCohort(root, retained).close(exc)
        raise


def _rename_exchange_at(parent_fd: int, source: str, destination: str) -> None:
    renameat2 = getattr(ctypes.CDLL(None, use_errno=True), "renameat2", None)
    _require(renameat2 is not None, "renameat2(RENAME_EXCHANGE) is unavailable")
    renameat2.argtypes = [
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_uint,
    ]
    renameat2.restype = ctypes.c_int
    if renameat2(
        parent_fd,
        os.fsencode(source),
        parent_fd,
        os.fsencode(destination),
        2,
    ) == 0:
        return
    error_number = ctypes.get_errno()
    raise OSError(
        error_number,
        os.strerror(error_number),
        f"{source} <-> {destination}",
    )


def _create_temporary_at(parent_fd: int, checkpoint_name: str) -> tuple[int, str]:
    for _attempt in range(128):
        name = (
            f".{checkpoint_name}.fp048-seq43-44."
            f"{secrets.token_hex(12)}.tmp"
        )
        try:
            descriptor = os.open(
                name,
                os.O_RDWR
                | os.O_CREAT
                | os.O_EXCL
                | getattr(os, "O_CLOEXEC", 0)
                | getattr(os, "O_NOFOLLOW", 0),
                0o600,
                dir_fd=parent_fd,
            )
        except FileExistsError:
            continue
        return descriptor, name
    raise CompletionApplyError("could not allocate checkpoint staging file")


def _rollback_exchange(
    parent_fd: int,
    temporary_name: str,
    checkpoint_name: str,
    staged_identity: tuple[int, ...],
) -> None:
    staged_path = os.stat(
        checkpoint_name,
        dir_fd=parent_fd,
        follow_symlinks=False,
    )
    displaced_path = os.stat(
        temporary_name,
        dir_fd=parent_fd,
        follow_symlinks=False,
    )
    displaced_identity = _stable_file_identity(displaced_path)
    _require(
        _stable_file_identity(staged_path) == staged_identity,
        "published checkpoint changed before CAS rollback",
    )
    _rename_exchange_at(parent_fd, temporary_name, checkpoint_name)
    restored = os.stat(
        checkpoint_name,
        dir_fd=parent_fd,
        follow_symlinks=False,
    )
    returned_stage = os.stat(
        temporary_name,
        dir_fd=parent_fd,
        follow_symlinks=False,
    )
    _require(
        _stable_file_identity(restored) == displaced_identity
        and _stable_file_identity(returned_stage) == staged_identity,
        "checkpoint CAS rollback identity differs",
    )


def atomic_write(
    path: Path,
    content: bytes,
    *,
    expected_source: bytes,
    writer: Callable[[Any, bytes], None] = _write_and_sync,
    exchanger: Callable[[int, str, str], None] = _rename_exchange_at,
    directory_syncer: Callable[[int], None] = os.fsync,
    commit_guard: Callable[[], None] | None = None,
) -> None:
    parent_fd = os.open(
        path.parent,
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | os.O_DIRECTORY
        | getattr(os, "O_NOFOLLOW", 0),
    )
    parent_identity = _directory_identity(os.fstat(parent_fd))
    source_fd: int | None = None
    temporary_name: str | None = None
    locked = False
    exchanged = False
    namespace_dirty = False
    preserve_temporary = False
    directory_sync_attempted = False
    primary_error: BaseException | None = None
    staged_identity: tuple[int, ...] | None = None
    try:
        try:
            fcntl.flock(parent_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise CompletionApplyError(
                "checkpoint publication parent is already locked"
            ) from exc
        locked = True
        _require(
            _canonical_parent_matches(path.parent, parent_identity),
            "checkpoint publication parent identity differs",
        )
        source_fd = os.open(
            path.name,
            os.O_RDONLY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0),
            dir_fd=parent_fd,
        )
        source_info = os.fstat(source_fd)
        _require(
            stat.S_ISREG(source_info.st_mode)
            and source_info.st_uid == os.geteuid()
            and source_info.st_nlink == 1
            and stat.S_IMODE(source_info.st_mode) == 0o600,
            "source checkpoint authority differs",
        )
        source_identity = _file_identity(source_info)
        _verify_retained_checkpoint(
            parent_fd,
            path.name,
            source_fd,
            source_identity,
            expected_source,
        )
        descriptor, temporary_name = _create_temporary_at(parent_fd, path.name)
        namespace_dirty = True
        source_mode = stat.S_IMODE(source_info.st_mode)
        try:
            os.fchmod(descriptor, source_mode)
        except BaseException:
            os.close(descriptor)
            raise
        with os.fdopen(descriptor, "wb") as stream:
            writer(stream, content)
        _require(
            _canonical_parent_matches(path.parent, parent_identity),
            "checkpoint publication parent changed while staging",
        )
        _verify_retained_checkpoint(
            parent_fd,
            path.name,
            source_fd,
            source_identity,
            expected_source,
        )
        staged_info = os.stat(
            temporary_name,
            dir_fd=parent_fd,
            follow_symlinks=False,
        )
        _require(
            stat.S_ISREG(staged_info.st_mode)
            and staged_info.st_uid == os.geteuid()
            and staged_info.st_nlink == 1
            and stat.S_IMODE(staged_info.st_mode) == source_mode
            and _read_entry_exact(parent_fd, temporary_name, len(content)) == content,
            "staged checkpoint authority or bytes differ",
        )
        staged_identity = _stable_file_identity(staged_info)
        _require(
            _canonical_parent_matches(path.parent, parent_identity),
            "checkpoint publication parent changed before atomic exchange",
        )
        if commit_guard is not None:
            commit_guard()
        exchanger(parent_fd, temporary_name, path.name)
        exchanged = True
        try:
            if commit_guard is not None:
                commit_guard()
            published = os.stat(
                path.name,
                dir_fd=parent_fd,
                follow_symlinks=False,
            )
            displaced = os.stat(
                temporary_name,
                dir_fd=parent_fd,
                follow_symlinks=False,
            )
            source_stable_identity = _stable_file_identity(source_info)
            compare_exchange_succeeded = (
                _canonical_parent_matches(path.parent, parent_identity)
                and _stable_file_identity(published) == staged_identity
                and _stable_file_identity(displaced) == source_stable_identity
                and _read_entry_exact(parent_fd, path.name, len(content)) == content
                and _read_entry_exact(
                    parent_fd,
                    temporary_name,
                    len(expected_source),
                )
                == expected_source
            )
            if not compare_exchange_succeeded:
                raise CompletionApplyError(
                    "checkpoint or publication parent changed at atomic "
                    "compare-exchange boundary"
                )
            _require(
                _canonical_parent_matches(path.parent, parent_identity),
                "checkpoint publication parent changed after atomic exchange",
            )
            if commit_guard is not None:
                commit_guard()
            os.unlink(temporary_name, dir_fd=parent_fd)
            temporary_name = None
            exchanged = False
        except BaseException as primary:
            if exchanged and staged_identity is not None:
                try:
                    _rollback_exchange(
                        parent_fd,
                        temporary_name,
                        path.name,
                        staged_identity,
                    )
                    exchanged = False
                except BaseException as rollback_error:
                    preserve_temporary = True
                    try:
                        primary.add_note(
                            "checkpoint CAS rollback also failed: "
                            f"{type(rollback_error).__name__}: {rollback_error}; "
                            f"retained recovery entry: {temporary_name}"
                        )
                    except BaseException:
                        pass
            raise
        try:
            directory_sync_attempted = True
            directory_syncer(parent_fd)
        except OSError as exc:
            raise CompletionPostCommitError(
                "checkpoint replacement completed but parent directory fsync failed; "
                "durability is uncertain, so inspect the checkpoint before retrying"
            ) from exc
    except BaseException as exc:
        primary_error = exc
        raise
    finally:
        if temporary_name is not None and not preserve_temporary:
            try:
                os.unlink(temporary_name, dir_fd=parent_fd)
            except FileNotFoundError:
                pass
            except BaseException as cleanup_error:
                preserve_temporary = True
                if primary_error is not None:
                    try:
                        primary_error.add_note(
                            "checkpoint recovery-entry cleanup also failed: "
                            f"{type(cleanup_error).__name__}: {cleanup_error}; "
                            f"retained recovery entry: {temporary_name}"
                        )
                    except BaseException:
                        pass
                else:
                    raise
        if (
            primary_error is not None
            and namespace_dirty
            and not directory_sync_attempted
        ):
            directory_sync_attempted = True
            try:
                directory_syncer(parent_fd)
            except BaseException as durability_error:
                try:
                    primary_error.add_note(
                        "checkpoint failure-state parent fsync also failed: "
                        f"{type(durability_error).__name__}: {durability_error}; "
                        "recovery durability is uncertain"
                    )
                except BaseException:
                    pass
        if source_fd is not None:
            os.close(source_fd)
        if locked:
            fcntl.flock(parent_fd, fcntl.LOCK_UN)
        os.close(parent_fd)


def write_projection(
    prepared: PreparedProjection,
    *,
    source_validator: Callable[[bytes, dict[str, Any]], None] = require_exact_source,
    runtime_deriver: Callable[
        [Path, dict[str, Any], list[str]],
        tuple[dict[str, Any], dict[str, Any]],
    ] = derive_runtime,
    snapshot_hasher: Callable[[Path, list[str]], tuple[str, str]] = (
        contract.working_snapshot_hashes
    ),
    continuation_checker: Callable[[Path, Path], list[str]] = run_continuation_checker,
    goal_graph_checker: Callable[[Path, Path], list[str]] = run_goal_graph_checker,
    atomic_writer: Callable[..., None] = atomic_write,
) -> None:
    _require(
        prepared.checkpoint_path.read_bytes() == prepared.source_checkpoint_bytes,
        "source changed before write revalidation",
    )
    cohort = retain_artifact_pin_cohort(
        prepared.root,
        prepared.artifact_sha256_by_role,
    )
    primary_error: BaseException | None = None
    try:
        refreshed = prepare_projection(
            prepared.root,
            artifact_sha256_by_role=prepared.artifact_sha256_by_role,
            source_validator=source_validator,
            runtime_deriver=runtime_deriver,
            snapshot_hasher=snapshot_hasher,
            continuation_checker=continuation_checker,
            goal_graph_checker=goal_graph_checker,
        )
        cohort.verify()
        _require(
            refreshed.evidence == prepared.evidence,
            "completion evidence changed before write",
        )
        _require(
            refreshed.projected_checkpoint_bytes == prepared.projected_checkpoint_bytes,
            "projected checkpoint changed before write",
        )
        atomic_writer(
            prepared.checkpoint_path,
            prepared.projected_checkpoint_bytes,
            expected_source=prepared.source_checkpoint_bytes,
            commit_guard=cohort.verify,
        )
    except BaseException as exc:
        primary_error = exc
        raise
    finally:
        cohort.close(primary_error)


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--write", action="store_true")
    parser.add_argument("--root", type=Path, default=ROOT)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        prepared = prepare_projection(args.root)
        if args.write:
            write_projection(prepared)
            mode = "WRITE"
        else:
            mode = "PREFLIGHT"
    except CompletionPostCommitError as exc:
        print(f"FP-048 GOAL_COMPLETED seq43-44: POSTCOMMIT_UNCERTAIN: {exc}")
        return 1
    except (
        CompletionApplyError,
        KeyError,
        OSError,
        TypeError,
        ValueError,
    ) as exc:
        print(f"FP-048 GOAL_COMPLETED seq43-44: FAIL: {exc}")
        return 1
    print(
        "FP-048 GOAL_COMPLETED seq43-44: PASS "
        f"mode={mode} update_sha256={prepared.update_event['event_sha256']} "
        f"completion_sha256={prepared.completion_event['event_sha256']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

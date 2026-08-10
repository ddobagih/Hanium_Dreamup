#!/usr/bin/env python3
"""Build the immutable WalkSafe policy-baseline approval record and manifest."""

from __future__ import annotations

import argparse
from copy import deepcopy
from datetime import datetime
import hashlib
import json
from pathlib import Path
import sys
import unicodedata
from typing import Any

try:
    from scripts import build_walksafe_feature_policy_baseline_review_20260721 as review_builder
except ModuleNotFoundError:  # Direct execution places scripts/ at sys.path[0].
    import build_walksafe_feature_policy_baseline_review_20260721 as review_builder


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = Path(__file__).resolve()
INTERVIEW_DIR = REPO_ROOT / "docs" / "control" / "decision-interview"
SOURCE_DIR = INTERVIEW_DIR / "source-records"
BASELINE_DIR = REPO_ROOT / "docs" / "control" / "baselines"

APPROVAL_SOURCE_PATH = SOURCE_DIR / "walksafe-feature-policy-baseline-approval-20260721-r001.txt"
APPROVAL_INTAKE_PATH = SOURCE_DIR / "walksafe-feature-policy-baseline-approval-20260721-r001.intake.json"
REVIEW_RESOLUTION_PATH = INTERVIEW_DIR / "walksafe-feature-policy-baseline-review-resolution-20260721-r001.json"
POLICY_JSON_PATH = INTERVIEW_DIR / "walksafe-feature-policy-comprehensive-draft.json"
POLICY_HTML_PATH = INTERVIEW_DIR / "walksafe-feature-policy-comprehensive-draft-20260720.html"
ANSWERS_PATH = SOURCE_DIR / "walksafe-feature-policy-baseline-review-20260720-r001-answers.json"
ANSWERS_INTAKE_PATH = ANSWERS_PATH.with_suffix(".intake.json")

APPROVAL_RECORD_PATH = BASELINE_DIR / "walksafe-feature-policy-baseline-1.0.0-approval-20260721-r001.json"
BASELINE_MANIFEST_PATH = BASELINE_DIR / "walksafe-feature-policy-baseline-1.0.0-manifest-20260721-r001.json"

SOURCE_EVENT_METADATA_STATUS = "NOT_EXPOSED_BY_CONVERSATION_INTERFACE"
RECORDED_AT = "2026-07-21T00:54:30+09:00"
APPROVAL_EVENT_DATE = "2026-07-21"
BASELINE_ID = "PB-WALKSAFE-FEATURE-POLICY-1.0.0"
BASELINE_VERSION = "1.0.0"

EXPECTED_LAYOUT_REPLACEMENTS = [
    {
        "from": "WS-FEATURE-\n  POLICY",
        "to": "WS-FEATURE-POLICY",
        "required_count": 1,
    },
    {
        "from": "항목은\n  면제",
        "to": "항목은 면제",
        "required_count": 1,
    },
]
EXPECTED_NORMALIZATION_POLICY = {
    "unicode": "NFC",
    "terminal_newline": "IGNORED_AS_TEXT_FILE_SERIALIZATION",
    "allowed_layout_replacements": EXPECTED_LAYOUT_REPLACEMENTS,
    "meaning_changes_allowed": False,
}
EXPECTED_APPROVER = {
    "name": "김민호",
    "role": "PROJECT_MANAGER_AND_FINAL_POLICY_OWNER",
    "identity_basis": "CURRENT_CONVERSATION_ACTOR_AND_CONTROLLED_REVIEWER_NAME",
    "identity_verification_status": "CONVERSATION_CONTINUITY_NOT_CRYPTOGRAPHICALLY_VERIFIED",
    "cryptographic_signature_status": "NOT_SIGNED",
}
EXPECTED_RECORD_CONTROLS = {
    "source_class": "EXTERNAL",
    "artifact_form": "EXTERNAL_RECORD",
    "evidence_classification": "USER_APPROVED",
    "adoption_trust": "A",
    "confidentiality": "INTERNAL",
    "personal_data": ["approver_name"],
    "retention_class": "PROJECT_LIFECYCLE_PLUS_3_YEARS",
    "notes": (
        "사용자 대화의 승인문 원문을 별도 텍스트로 보존하고, "
        "화면 줄바꿈만 정규화해 사전 고정 승인문과 비교한다."
    ),
}


class BaselineApprovalError(ValueError):
    """Raised when approval evidence or a generated baseline is not trustworthy."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise BaselineApprovalError(message)


def _reject_constant(value: str) -> None:
    raise BaselineApprovalError(f"non-standard JSON number is not allowed: {value}")


def _object_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise BaselineApprovalError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load_strict_json(path: Path) -> dict[str, Any]:
    raw = path.read_bytes()
    _require(not raw.startswith(b"\xef\xbb\xbf"), f"UTF-8 BOM is not allowed: {path.name}")
    try:
        value = json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=_object_pairs,
            parse_constant=_reject_constant,
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise BaselineApprovalError(f"invalid JSON: {path.name}: {exc}") from exc
    _require(isinstance(value, dict), f"JSON root must be an object: {path.name}")
    return value


def _strict_object(value: Any, label: str, fields: set[str]) -> dict[str, Any]:
    _require(isinstance(value, dict), f"{label} must be an object")
    _require(set(value) == fields, f"{label} fields differ from the schema")
    return value


def _strict_string(value: Any, label: str) -> str:
    _require(isinstance(value, str) and bool(value.strip()), f"{label} must be a non-empty string")
    _require(value == value.strip(), f"{label} has surrounding whitespace")
    return value


def _strict_int(value: Any, label: str, minimum: int = 0) -> int:
    _require(isinstance(value, int) and not isinstance(value, bool), f"{label} must be an integer")
    _require(value >= minimum, f"{label} must be at least {minimum}")
    return value


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _bytes_sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _text_sha256(value: str) -> str:
    return _bytes_sha256(value.encode("utf-8"))


def _object_sha256(value: Any) -> str:
    return _bytes_sha256(
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    )


def _json_bytes(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _source_binding(path: Path) -> dict[str, str]:
    return {"path": str(path.relative_to(REPO_ROOT)), "sha256": _file_sha256(path)}


def _parse_recorded_at(value: Any) -> None:
    text = _strict_string(value, "approval intake recorded_at")
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError as exc:
        raise BaselineApprovalError("approval intake recorded_at is not ISO-8601") from exc
    _require(parsed.tzinfo is not None, "approval intake recorded_at has no timezone")


def _normalize_approval_statement(
    value: str,
    policy: dict[str, Any] | None = None,
) -> tuple[str, list[dict[str, Any]]]:
    """Apply only the two layout repairs explicitly authorized by the intake."""

    effective_policy = EXPECTED_NORMALIZATION_POLICY if policy is None else policy
    _require(effective_policy == EXPECTED_NORMALIZATION_POLICY, "approval normalization policy differs")
    normalized = unicodedata.normalize("NFC", value)
    actions: list[dict[str, Any]] = []
    for replacement in EXPECTED_LAYOUT_REPLACEMENTS:
        source = replacement["from"]
        actual_count = normalized.count(source)
        _require(
            actual_count == replacement["required_count"],
            f"approval layout replacement count differs: {source!r}",
        )
        normalized = normalized.replace(source, replacement["to"])
        actions.append({**replacement, "actual_count": actual_count})
    _require("\n" not in normalized and "\r" not in normalized, "approval has an unapproved line break")
    return normalized, actions


def _read_approval_source() -> tuple[bytes, str]:
    raw = APPROVAL_SOURCE_PATH.read_bytes()
    _require(not raw.startswith(b"\xef\xbb\xbf"), "approval source has a UTF-8 BOM")
    try:
        serialized = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise BaselineApprovalError(f"approval source is not UTF-8: {exc}") from exc
    _require(serialized.endswith("\n"), "approval source lacks its declared terminal newline")
    _require(not serialized.endswith("\n\n"), "approval source has more than one terminal newline")
    _require("\r" not in serialized, "approval source contains an undeclared carriage return")
    statement = serialized[:-1]
    _require(bool(statement), "approval source is empty")
    return raw, statement


def _validate_upstream_review() -> dict[str, Any]:
    resolution = review_builder.load_strict_json(REVIEW_RESOLUTION_PATH)
    review_builder.validate_resolution(resolution)
    expected_bytes = review_builder._json_bytes(review_builder.build_resolution())
    _require(REVIEW_RESOLUTION_PATH.read_bytes() == expected_bytes, "review resolution is stale")
    return resolution


def _expected_intake_target(resolution: dict[str, Any]) -> dict[str, Any]:
    target = resolution["handoff"]["approval_target"]
    return {
        "review_resolution_id": target["review_resolution_id"],
        "review_resolution_version": target["review_resolution_version"],
        "decision_binding_sha256": target["decision_binding_sha256"],
        "policy_document_id": target["document_id"],
        "policy_document_version": target["document_version"],
        "policy_document_content_sha256": target["document_content_sha256"],
        "policy_baseline_version": target["baseline_version"],
        "scope": target["scope"],
    }


def _validate_intake(intake: dict[str, Any], resolution: dict[str, Any], raw: bytes) -> None:
    _strict_object(
        intake,
        "approval intake",
        {
            "schema_version",
            "intake_id",
            "intake_version",
            "recorded_at",
            "recorded_at_basis",
            "approval_event_date",
            "source_event_id",
            "source_event_timestamp",
            "source_event_metadata_status",
            "source_kind",
            "capture_fidelity",
            "source_record_path",
            "source_record_sha256",
            "source_record_byte_length",
            "approver",
            "normalization_policy",
            "approval_target",
            "approval_boundary",
            "record_controls",
        },
    )
    _require(
        intake.get("schema_version") == "walksafe.feature-policy-baseline-approval-intake.v1",
        "approval intake schema is invalid",
    )
    _require(
        intake.get("intake_id") == "WS-FEATURE-POLICY-BASELINE-APPROVAL-INTAKE-20260721-001",
        "approval intake ID is invalid",
    )
    _require(intake.get("intake_version") == "1.0.0", "approval intake version is invalid")
    _parse_recorded_at(intake.get("recorded_at"))
    _require(intake.get("recorded_at") == RECORDED_AT, "approval recorded time differs")
    _require(
        intake.get("recorded_at_basis") == "PROCESSING_TIME_MESSAGE_HAS_NO_MACHINE_TIMESTAMP",
        "approval recorded time basis differs",
    )
    _require(intake.get("approval_event_date") == APPROVAL_EVENT_DATE, "approval event date differs")
    _require(intake.get("source_event_id") is None, "unexposed source event ID must be null")
    _require(intake.get("source_event_timestamp") is None, "unexposed source event timestamp must be null")
    _require(
        intake.get("source_event_metadata_status") == SOURCE_EVENT_METADATA_STATUS,
        "source event metadata status differs",
    )
    _require(intake.get("source_kind") == "USER_CHAT_MESSAGE", "approval source kind differs")
    _require(
        intake.get("capture_fidelity") == "DISPLAYED_MESSAGE_TRANSCRIPTION_WITH_TERMINAL_NEWLINE",
        "approval capture fidelity differs",
    )
    _require(
        intake.get("source_record_path") == str(APPROVAL_SOURCE_PATH.relative_to(REPO_ROOT)),
        "approval source path differs",
    )
    _require(intake.get("source_record_sha256") == _bytes_sha256(raw), "approval source hash differs")
    _strict_int(intake.get("source_record_byte_length"), "approval source byte length", minimum=1)
    _require(intake.get("source_record_byte_length") == len(raw), "approval source byte length differs")

    approver = _strict_object(
        intake.get("approver"),
        "approval intake approver",
        {
            "name",
            "role",
            "identity_basis",
            "identity_verification_status",
            "cryptographic_signature_status",
        },
    )
    _require(approver == EXPECTED_APPROVER, "approval actor differs")
    answers = load_strict_json(ANSWERS_PATH)
    _require(answers.get("reviewer") == approver["name"], "approver and controlled reviewer differ")

    normalization = _strict_object(
        intake.get("normalization_policy"),
        "approval normalization policy",
        {"unicode", "terminal_newline", "allowed_layout_replacements", "meaning_changes_allowed"},
    )
    _require(normalization == EXPECTED_NORMALIZATION_POLICY, "approval normalization policy differs")
    replacements = normalization["allowed_layout_replacements"]
    _require(isinstance(replacements, list) and len(replacements) == 2, "approval replacement set differs")
    for index, replacement in enumerate(replacements):
        _strict_object(replacement, f"approval replacement {index}", {"from", "to", "required_count"})
        _strict_int(replacement["required_count"], f"approval replacement count {index}", minimum=1)

    target = _strict_object(
        intake.get("approval_target"),
        "approval intake target",
        {
            "review_resolution_id",
            "review_resolution_version",
            "decision_binding_sha256",
            "policy_document_id",
            "policy_document_version",
            "policy_document_content_sha256",
            "policy_baseline_version",
            "scope",
        },
    )
    _require(target == _expected_intake_target(resolution), "approval intake target differs")

    boundary = _strict_object(
        intake.get("approval_boundary"),
        "approval intake boundary",
        {
            "remaining_gate_count",
            "remaining_gates_waived",
            "release_status",
            "formal_deliverables_0_to_6",
            "implementation_completion_claimed",
            "test_completion_claimed",
        },
    )
    _require(
        boundary
        == {
            "remaining_gate_count": 5,
            "remaining_gates_waived": False,
            "release_status": "NOT_ELIGIBLE",
            "formal_deliverables_0_to_6": "AUTHORIZED_TO_START",
            "implementation_completion_claimed": False,
            "test_completion_claimed": False,
        },
        "approval intake boundary differs",
    )
    controls = _strict_object(
        intake.get("record_controls"),
        "approval record controls",
        {
            "source_class",
            "artifact_form",
            "evidence_classification",
            "adoption_trust",
            "confidentiality",
            "personal_data",
            "retention_class",
            "notes",
        },
    )
    _require(controls == EXPECTED_RECORD_CONTROLS, "approval record controls differ")


def _approval_boundary(intake: dict[str, Any]) -> dict[str, Any]:
    return {
        "policy_review_status": "REVIEW_COMPLETE_ALL_CONFIRMED",
        "content_approval_status": "APPROVED",
        "baseline_status": "APPROVED",
        "baseline_approval_recorded": True,
        "approved_by": {
            "name": intake["approver"]["name"],
            "role": intake["approver"]["role"],
        },
        "approved_at": None,
        "approved_at_status": SOURCE_EVENT_METADATA_STATUS,
        "approval_event_date": intake["approval_event_date"],
        "approval_scope": "POLICY_BASELINE_ONLY",
        "remaining_gate_count": 5,
        "remaining_gates_are_waived": False,
        "release_status": "NOT_ELIGIBLE",
        "formal_deliverables_authorized": True,
        "formal_deliverable_generation_status": "NOT_RUN",
        "implementation_completion_claimed": False,
        "test_completion_claimed": False,
    }


def _approved_baseline_payload(
    resolution: dict[str, Any],
    intake: dict[str, Any],
    gates: list[dict[str, Any]],
    boundary: dict[str, Any],
) -> dict[str, Any]:
    return {
        "baseline_id": BASELINE_ID,
        "baseline_version": BASELINE_VERSION,
        "scope": "POLICY_BASELINE_ONLY",
        "approval_target": deepcopy(resolution["handoff"]["approval_target"]),
        "review_resolution": {
            "resolution_id": resolution["metadata"]["resolution_id"],
            "resolution_version": resolution["metadata"]["resolution_version"],
            "controlled_revision": resolution["metadata"]["controlled_revision"],
            "file_sha256": _file_sha256(REVIEW_RESOLUTION_PATH),
            "content_sha256": resolution["resolution_content_sha256"],
            "source_binding_sha256": resolution["source_binding_sha256"],
        },
        "review_summary": deepcopy(resolution["review_summary"]),
        "reviewed_policy": deepcopy(resolution["reviewed_policy"]),
        "remaining_gates": deepcopy(gates),
        "gate_binding_sha256": _object_sha256(gates),
        "approval_boundary": deepcopy(boundary),
    }


def _compose_approval_record(
    resolution: dict[str, Any],
    intake: dict[str, Any],
    raw: bytes,
    raw_statement: str,
) -> dict[str, Any]:
    normalized_statement, normalization_actions = _normalize_approval_statement(
        raw_statement,
        intake["normalization_policy"],
    )
    required_statement = resolution["handoff"]["required_approval_statement"]
    _require(normalized_statement == required_statement, "normalized approval statement differs")

    source_bindings = {
        "generator": _source_binding(GENERATOR_PATH),
        "approval_source": _source_binding(APPROVAL_SOURCE_PATH),
        "approval_intake": _source_binding(APPROVAL_INTAKE_PATH),
        "review_resolution": _source_binding(REVIEW_RESOLUTION_PATH),
        "policy_json": _source_binding(POLICY_JSON_PATH),
        "policy_html": _source_binding(POLICY_HTML_PATH),
        "review_answers": _source_binding(ANSWERS_PATH),
        "review_answers_intake": _source_binding(ANSWERS_INTAKE_PATH),
    }
    gates = deepcopy(resolution["remaining_gates"])
    boundary = _approval_boundary(intake)
    payload = _approved_baseline_payload(resolution, intake, gates, boundary)
    record: dict[str, Any] = {
        "schema_version": "walksafe.feature-policy-baseline-approval-record.v1",
        "metadata": {
            "approval_record_id": "WS-FEATURE-POLICY-BASELINE-APPROVAL-20260721-001",
            "approval_record_version": "1.0.0",
            "controlled_revision": 1,
            "supersedes_approval_record_id": None,
            "supersedes_approval_record_version": None,
            "supersedes_approval_record_controlled_revision": None,
            "supersedes_approval_record_file_sha256": None,
            "recorded_at": intake["recorded_at"],
            "approval_event_date": intake["approval_event_date"],
            "approved_at": None,
            "approved_at_status": intake["source_event_metadata_status"],
            "approval_status": "APPROVAL_RECORDED",
            "lifecycle_status": "APPROVED",
        },
        "source_bindings": source_bindings,
        "source_binding_sha256": _object_sha256(source_bindings),
        "approval_evidence": {
            "source_kind": intake["source_kind"],
            "capture_fidelity": intake["capture_fidelity"],
            "source_event_id": None,
            "source_event_timestamp": None,
            "source_event_metadata_status": intake["source_event_metadata_status"],
            "approval_event_date": intake["approval_event_date"],
            "raw_statement_path": intake["source_record_path"],
            "raw_statement_file_sha256": intake["source_record_sha256"],
            "raw_statement_byte_length": intake["source_record_byte_length"],
            "raw_statement": raw_statement,
            "raw_statement_text_sha256": _text_sha256(raw_statement),
            "normalized_statement": normalized_statement,
            "normalized_statement_text_sha256": _text_sha256(normalized_statement),
            "required_statement": required_statement,
            "required_statement_text_sha256": _text_sha256(required_statement),
            "normalization_policy": deepcopy(intake["normalization_policy"]),
            "normalization_actions": normalization_actions,
            "normalized_exact_match": True,
        },
        "approver": deepcopy(intake["approver"]),
        "approved_baseline_payload": payload,
        "approved_baseline_payload_sha256": _object_sha256(payload),
        "remaining_gates": gates,
        "gate_binding_sha256": _object_sha256(gates),
        "approval_boundary": boundary,
        "record_controls": deepcopy(intake["record_controls"]),
    }
    record["approval_record_content_sha256"] = _object_sha256(record)
    return record


def build_approval_record() -> dict[str, Any]:
    resolution = _validate_upstream_review()
    intake = load_strict_json(APPROVAL_INTAKE_PATH)
    raw, raw_statement = _read_approval_source()
    _validate_intake(intake, resolution, raw)
    record = _compose_approval_record(resolution, intake, raw, raw_statement)
    validate_approval_record(record, resolution)
    return record


def validate_approval_record(
    record: dict[str, Any],
    resolution: dict[str, Any] | None = None,
) -> None:
    if resolution is None:
        resolution = _validate_upstream_review()
    intake = load_strict_json(APPROVAL_INTAKE_PATH)
    raw, raw_statement = _read_approval_source()
    _validate_intake(intake, resolution, raw)
    _strict_object(
        record,
        "approval record",
        {
            "schema_version",
            "metadata",
            "source_bindings",
            "source_binding_sha256",
            "approval_evidence",
            "approver",
            "approved_baseline_payload",
            "approved_baseline_payload_sha256",
            "remaining_gates",
            "gate_binding_sha256",
            "approval_boundary",
            "record_controls",
            "approval_record_content_sha256",
        },
    )
    expected = _compose_approval_record(resolution, intake, raw, raw_statement)
    _require(record == expected, "approval record differs from its controlled evidence")
    _require(
        record["approved_baseline_payload_sha256"]
        == _object_sha256(record["approved_baseline_payload"]),
        "approved baseline payload hash differs",
    )
    _require(
        record["remaining_gates"] == resolution["remaining_gates"],
        "approval does not preserve all five gate details",
    )
    _require(
        record["gate_binding_sha256"] == _object_sha256(record["remaining_gates"]),
        "approval gate binding differs",
    )
    _require(
        record["approved_baseline_payload"]["remaining_gates"] == record["remaining_gates"],
        "approved payload and approval gates differ",
    )
    _require(
        record["approved_baseline_payload"]["approval_boundary"] == record["approval_boundary"],
        "approved payload and approval boundary differ",
    )
    expected_content = _object_sha256(
        {key: value for key, value in record.items() if key != "approval_record_content_sha256"}
    )
    _require(
        record["approval_record_content_sha256"] == expected_content,
        "approval record content hash differs",
    )


def _establishment_boundary(record: dict[str, Any]) -> dict[str, Any]:
    approval = record["approval_boundary"]
    return {
        "policy_review_status": approval["policy_review_status"],
        "content_approval_status": "APPROVED",
        "baseline_status": "BASELINED",
        "baseline_approval_recorded": True,
        "approved_by": deepcopy(approval["approved_by"]),
        "approved_at": None,
        "approved_at_status": SOURCE_EVENT_METADATA_STATUS,
        "approval_event_date": approval["approval_event_date"],
        "approval_scope": "POLICY_BASELINE_ONLY",
        "remaining_gate_count": 5,
        "remaining_gates_are_waived": False,
        "release_status": "NOT_ELIGIBLE",
        "formal_deliverables_authorized": True,
        "formal_deliverable_generation_status": "NOT_RUN",
        "implementation_completion_claimed": False,
        "test_completion_claimed": False,
        "verification_status": "NOT_RUN",
    }


def _compose_baseline_manifest(
    record: dict[str, Any],
    resolution: dict[str, Any],
) -> dict[str, Any]:
    record_bytes = _json_bytes(record)
    source_bindings = {
        "generator": _source_binding(GENERATOR_PATH),
        "policy_json": _source_binding(POLICY_JSON_PATH),
        "policy_html": _source_binding(POLICY_HTML_PATH),
        "review_resolution": _source_binding(REVIEW_RESOLUTION_PATH),
        "review_answers": _source_binding(ANSWERS_PATH),
        "review_answers_intake": _source_binding(ANSWERS_INTAKE_PATH),
        "approval_source": _source_binding(APPROVAL_SOURCE_PATH),
        "approval_intake": _source_binding(APPROVAL_INTAKE_PATH),
        "approval_record": {
            "path": str(APPROVAL_RECORD_PATH.relative_to(REPO_ROOT)),
            "sha256": _bytes_sha256(record_bytes),
        },
    }
    gates = deepcopy(record["remaining_gates"])
    payload = deepcopy(record["approved_baseline_payload"])
    manifest: dict[str, Any] = {
        "schema_version": "walksafe.feature-policy-baseline-manifest.v1",
        "metadata": {
            "manifest_id": "WS-FEATURE-POLICY-BASELINE-MANIFEST-20260721-001",
            "manifest_version": "1.0.0",
            "baseline_id": BASELINE_ID,
            "baseline_version": BASELINE_VERSION,
            "controlled_revision": 1,
            "supersedes_manifest_id": None,
            "supersedes_manifest_version": None,
            "supersedes_manifest_controlled_revision": None,
            "supersedes_manifest_file_sha256": None,
            "supersedes_baseline_id": None,
            "supersedes_baseline_version": None,
            "established_at": record["metadata"]["recorded_at"],
            "established_at_basis": "APPROVAL_RECORD_PROCESSING_TIME",
            "lifecycle_status": "BASELINED",
            "freshness_status_at_establishment": "CURRENT",
            "verification_status": "NOT_RUN",
        },
        "source_bindings": source_bindings,
        "source_binding_sha256": _object_sha256(source_bindings),
        "approval_binding": {
            "approval_record_id": record["metadata"]["approval_record_id"],
            "approval_record_version": record["metadata"]["approval_record_version"],
            "approval_record_controlled_revision": record["metadata"]["controlled_revision"],
            "approval_record_path": str(APPROVAL_RECORD_PATH.relative_to(REPO_ROOT)),
            "approval_record_file_sha256": _bytes_sha256(record_bytes),
            "approval_record_content_sha256": record["approval_record_content_sha256"],
            "approval_source_path": record["approval_evidence"]["raw_statement_path"],
            "approval_source_file_sha256": record["approval_evidence"]["raw_statement_file_sha256"],
            "approval_statement_text_sha256": record["approval_evidence"]["required_statement_text_sha256"],
            "approver_name": record["approver"]["name"],
            "approver_role": record["approver"]["role"],
            "approval_event_date": record["metadata"]["approval_event_date"],
            "source_event_id": None,
            "source_event_timestamp": None,
            "source_event_metadata_status": SOURCE_EVENT_METADATA_STATUS,
            "approved_at": None,
            "approved_at_status": SOURCE_EVENT_METADATA_STATUS,
        },
        "baseline_payload": payload,
        "baseline_payload_sha256": record["approved_baseline_payload_sha256"],
        "remaining_gates": gates,
        "gate_binding_sha256": record["gate_binding_sha256"],
        "establishment_boundary": _establishment_boundary(record),
        "authorized_next_steps": {
            "formal_deliverables_0_to_6": "AUTHORIZED_TO_START",
            "controlled_development_and_test_preparation": "AUTHORIZED",
            "gate_closure": "REQUIRED_AT_DECLARED_BOUNDARIES",
            "release": "NOT_AUTHORIZED",
        },
        "change_control": {
            "direct_edit_allowed": False,
            "change_request_required": True,
            "next_version_rule": (
                "정책·gate·승인 범위를 바꾸면 새 승인 입력, 새 생성기와 r002 이상 manifest를 "
                "만들고 이 r001을 Superseded로 연결한다."
            ),
            "stale_triggers": [
                "승인된 정책 문장 또는 공통정책 변경",
                "결정대장과 승인 정책의 불일치",
                "남은 gate 결과가 정책 변경을 요구함",
                "지원환경·외부 서비스·법률 의무 변경",
            ],
        },
    }
    manifest["manifest_content_sha256"] = _object_sha256(manifest)
    return manifest


def build_baseline_manifest(record: dict[str, Any]) -> dict[str, Any]:
    resolution = _validate_upstream_review()
    validate_approval_record(record, resolution)
    manifest = _compose_baseline_manifest(record, resolution)
    validate_baseline_manifest(manifest, record, resolution)
    return manifest


def validate_baseline_manifest(
    manifest: dict[str, Any],
    record: dict[str, Any],
    resolution: dict[str, Any] | None = None,
) -> None:
    if resolution is None:
        resolution = _validate_upstream_review()
    validate_approval_record(record, resolution)
    _strict_object(
        manifest,
        "baseline manifest",
        {
            "schema_version",
            "metadata",
            "source_bindings",
            "source_binding_sha256",
            "approval_binding",
            "baseline_payload",
            "baseline_payload_sha256",
            "remaining_gates",
            "gate_binding_sha256",
            "establishment_boundary",
            "authorized_next_steps",
            "change_control",
            "manifest_content_sha256",
        },
    )
    expected = _compose_baseline_manifest(record, resolution)
    _require(manifest == expected, "baseline manifest differs from the approved record")
    _require(
        manifest["baseline_payload"] == record["approved_baseline_payload"],
        "manifest payload differs from approved payload",
    )
    _require(
        manifest["baseline_payload_sha256"] == _object_sha256(manifest["baseline_payload"]),
        "manifest payload hash differs",
    )
    _require(
        manifest["remaining_gates"] == resolution["remaining_gates"],
        "manifest does not preserve all five gate details",
    )
    _require(
        manifest["gate_binding_sha256"] == _object_sha256(manifest["remaining_gates"]),
        "manifest gate binding differs",
    )
    _require(
        "manifest_file_sha256" not in manifest
        and "manifest_file_sha256" not in manifest["metadata"],
        "manifest contains a circular self file hash",
    )
    expected_content = _object_sha256(
        {key: value for key, value in manifest.items() if key != "manifest_content_sha256"}
    )
    _require(manifest["manifest_content_sha256"] == expected_content, "manifest content hash differs")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail if approval record or manifest is missing or stale")
    args = parser.parse_args(argv)
    try:
        record = build_approval_record()
        manifest = build_baseline_manifest(record)
        record_bytes = _json_bytes(record)
        manifest_bytes = _json_bytes(manifest)
        if args.check:
            _require(APPROVAL_RECORD_PATH.is_file(), "approval record is missing")
            _require(BASELINE_MANIFEST_PATH.is_file(), "baseline manifest is missing")
            _require(APPROVAL_RECORD_PATH.read_bytes() == record_bytes, "approval record is stale")
            _require(BASELINE_MANIFEST_PATH.read_bytes() == manifest_bytes, "baseline manifest is stale")
        else:
            BASELINE_DIR.mkdir(parents=True, exist_ok=True)
            APPROVAL_RECORD_PATH.write_bytes(record_bytes)
            BASELINE_MANIFEST_PATH.write_bytes(manifest_bytes)
    except (BaselineApprovalError, review_builder.BaselineReviewError, OSError, KeyError, TypeError) as exc:
        print(f"WalkSafe policy baseline approval failed: {exc}", file=sys.stderr)
        return 1
    if args.check:
        print("WalkSafe policy baseline approval check passed: APPROVED and BASELINED, release NOT_ELIGIBLE")
    else:
        print(APPROVAL_RECORD_PATH.relative_to(REPO_ROOT))
        print(BASELINE_MANIFEST_PATH.relative_to(REPO_ROOT))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

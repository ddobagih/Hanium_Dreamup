#!/usr/bin/env python3
"""Validate the 2026-07-21 final policy review and build its r001 approval receipt."""

from __future__ import annotations

import argparse
from datetime import datetime, timedelta
import hashlib
import json
from pathlib import Path
import sys
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = Path(__file__).resolve()
INTERVIEW_DIR = REPO_ROOT / "docs" / "control" / "decision-interview"
ANSWERS_PATH = (
    INTERVIEW_DIR
    / "source-records"
    / "walksafe-feature-policy-baseline-review-20260720-r001-answers.json"
)
INTAKE_PATH = ANSWERS_PATH.with_suffix(".intake.json")
DOCUMENT_JSON_PATH = INTERVIEW_DIR / "walksafe-feature-policy-comprehensive-draft.json"
DOCUMENT_HTML_PATH = INTERVIEW_DIR / "walksafe-feature-policy-comprehensive-draft-20260720.html"
OUTPUT_PATH = INTERVIEW_DIR / "walksafe-feature-policy-baseline-review-resolution-20260721-r001.json"

ANSWER_FIELDS = {
    "schema_version",
    "report_id",
    "document_content_sha256",
    "source_binding_sha256",
    "baseline_status",
    "reviewer",
    "exported_at",
    "items",
}
ITEM_FIELDS = {"id", "decision", "note"}
ALLOWED_DECISIONS = {"confirm", "revise", "hold"}
EXPECTED_COMMON_IDS = [
    "NPC-RAW-ORIGINAL-COLLECTION",
    "NPC-DATA-LIFECYCLE",
    "NPC-SERVER-STORAGE-CAPACITY",
    "NPC-PHONE-QUEUE-CAPACITY",
    "NPC-AUTO-REPORT",
    "NPC-PERMISSION-SESSION-LIFECYCLE",
    "NPC-NAVIGATION-ROUTE-DIRECTION",
    "NPC-SINGLE-ADMIN-RECOVERY",
    "NPC-SERVER-CAPACITY-STATE-SYNC",
]
EXPECTED_FEATURE_IDS = [f"FP-{index:03d}" for index in range(1, 55)]
EXPECTED_GATE_IDS = [
    "GATE-PHONE-QUEUE-BYTE-LIMIT",
    "GATE-SERVER-CAPACITY-STATE-CONTRACT",
    "GATE-RAW-COLLECTION-RELEASE-REVIEW",
    "GATE-CLOUD-COST-MEASUREMENT",
    "GATE-SINGLE-ADMIN-RECOVERY-DRILL",
]
GATE_APPROVAL_BOUNDARIES = {
    "GATE-PHONE-QUEUE-BYTE-LIMIT": {
        "must_close_before": "지원 기기별 실제 사용자시험 범위 확정과 TST-22 출시 준비도 판단 전",
        "blocks_until_complete": ["지원 기기별 대기열 용량 기준 확정", "TST-22 출시 준비도 승인"],
    },
    "GATE-SERVER-CAPACITY-STATE-CONTRACT": {
        "must_close_before": "관련 기능의 통합시험 완료와 TST-22 출시 준비도 판단 전",
        "blocks_until_complete": ["서버 용량상태 연동 통합시험 완료", "TST-22 출시 준비도 승인"],
    },
    "GATE-RAW-COLLECTION-RELEASE-REVIEW": {
        "must_close_before": "TST-22 출시 준비도 판단과 실제 서비스 배포 전",
        "blocks_until_complete": ["무가림 원본수집 출시 적합 판정", "REL-02 릴리스 승인"],
    },
    "GATE-CLOUD-COST-MEASUREMENT": {
        "must_close_before": "운영비 기준 확정과 TST-22 출시 준비도 판단 전",
        "blocks_until_complete": ["월 저장비 상한 충족 판정", "TST-22 출시 준비도 승인"],
    },
    "GATE-SINGLE-ADMIN-RECOVERY-DRILL": {
        "must_close_before": "실제 사용자시험 또는 배포 전",
        "blocks_until_complete": ["실제 사용자시험 시작", "REL-02 릴리스 승인"],
    },
}
EXPECTED_DOCUMENT_SOURCE_PATHS = {
    "generator": "scripts/build_walksafe_feature_policy_document.py",
    "document_rules": "docs/control/decision-interview/walksafe-feature-policy-document-rules.json",
    "resolution": "docs/control/decision-interview/walksafe-feature-policy-review-resolution.json",
    "proposals": "docs/control/decision-interview/walksafe-feature-policy-comprehensive-proposals.json",
    "base_policy": "docs/control/decision-interview/walksafe-feature-policy-effective-candidate.json",
    "decision_register": "docs/control/decision-interview/walksafe-effective-decision-register.json",
    "template": "docs/control/decision-interview/feature-policy-document-template.html",
}


class BaselineReviewError(ValueError):
    """Raised when a review input or generated receipt is not trustworthy."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise BaselineReviewError(message)


def _reject_constant(value: str) -> None:
    raise BaselineReviewError(f"non-standard JSON number is not allowed: {value}")


def _object_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise BaselineReviewError(f"duplicate JSON key: {key}")
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
        raise BaselineReviewError(f"invalid JSON: {path.name}: {exc}") from exc
    _require(isinstance(value, dict), f"JSON root must be an object: {path.name}")
    return value


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _object_sha256(value: Any) -> str:
    return hashlib.sha256(
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()


def _strict_object(value: Any, label: str, fields: set[str]) -> dict[str, Any]:
    _require(isinstance(value, dict), f"{label} must be an object")
    _require(set(value) == fields, f"{label} fields differ from the schema")
    return value


def _strict_string(value: Any, label: str) -> str:
    _require(isinstance(value, str) and bool(value.strip()), f"{label} must be a non-empty string")
    _require(value == value.strip(), f"{label} has surrounding whitespace")
    _require(not any(ord(char) < 32 for char in value), f"{label} contains a control character")
    return value


def _strict_int(value: Any, label: str, minimum: int = 0) -> int:
    _require(isinstance(value, int) and not isinstance(value, bool), f"{label} must be an integer")
    _require(value >= minimum, f"{label} must be at least {minimum}")
    return value


def _parse_timestamp(value: Any, label: str) -> datetime:
    text = _strict_string(value, label)
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError as exc:
        raise BaselineReviewError(f"{label} is not ISO-8601") from exc
    _require(parsed.tzinfo is not None, f"{label} must include a timezone")
    return parsed


def _validate_document(document: dict[str, Any]) -> tuple[list[str], list[str]]:
    _strict_object(
        document,
        "reviewed document",
        {
            "schema_version",
            "metadata",
            "source_binding_sha256",
            "source_bindings",
            "resolution_content_sha256",
            "policy_precedence",
            "summary",
            "reading_guide",
            "end_to_end_flows",
            "common_policies",
            "reviewed_cross_feature_policies",
            "remaining_gates",
            "areas",
            "features",
            "decision_catalog",
            "review_application_log",
            "approval_boundary",
            "handoff",
            "coverage",
            "document_content_sha256",
        },
    )
    _require(
        document.get("schema_version") == "walksafe.feature-policy-comprehensive-draft.v1",
        "reviewed document schema version is invalid",
    )
    metadata = _strict_object(
        document.get("metadata"),
        "reviewed document metadata",
        {
            "document_id",
            "document_version",
            "as_of",
            "title",
            "subtitle",
            "lifecycle_status",
            "policy_decision_status",
            "baseline_status",
            "release_status",
            "html_report_generation_status",
            "formal_deliverable_generation_status",
            "purpose",
        },
    )
    boundary = _strict_object(
        document.get("approval_boundary"),
        "reviewed document approval boundary",
        {
            "owner_policy_review_status",
            "baseline_status",
            "release_status",
            "approved_by",
            "approved_at",
            "baseline_approval_recorded",
            "document_status",
            "formal_deliverable_generation_status",
        },
    )
    _require(metadata.get("document_id") == "WS-FEATURE-POLICY-DRAFT-20260720", "wrong document ID")
    _require(metadata.get("document_version") == "1.0.0", "wrong document version")
    _require(metadata.get("lifecycle_status") == "IN_REVIEW", "reviewed document lifecycle is invalid")
    _require(metadata.get("baseline_status") == "NOT_APPROVED", "reviewed document is not unapproved")
    _require(metadata.get("release_status") == "NOT_ELIGIBLE", "reviewed document allows release")
    _require(
        metadata.get("formal_deliverable_generation_status") == "NOT_RUN",
        "reviewed document metadata claims formal deliverables",
    )
    _require(boundary.get("owner_policy_review_status") == "REVIEW_COMPLETE_WITH_REVISIONS", "document review state is invalid")
    _require(boundary.get("baseline_status") == "NOT_APPROVED", "document boundary claims approval")
    _require(boundary.get("release_status") == "NOT_ELIGIBLE", "document boundary allows release")
    _require(boundary.get("baseline_approval_recorded") is False, "document records baseline approval")
    _require(boundary.get("approved_by") is None and boundary.get("approved_at") is None, "document has approver data")
    _require(boundary.get("document_status") == "IN_REVIEW", "document boundary lifecycle is invalid")
    _require(
        boundary.get("formal_deliverable_generation_status") == "NOT_RUN",
        "formal deliverables were already generated",
    )
    bindings = _strict_object(
        document.get("source_bindings"),
        "reviewed document source bindings",
        set(EXPECTED_DOCUMENT_SOURCE_PATHS),
    )
    for source_id, binding in bindings.items():
        current = _strict_object(binding, f"reviewed document source {source_id}", {"path", "sha256"})
        relative_path = _strict_string(current.get("path"), f"reviewed document source path {source_id}")
        _require(
            relative_path == EXPECTED_DOCUMENT_SOURCE_PATHS[source_id],
            f"reviewed document source path differs: {source_id}",
        )
        _require(not Path(relative_path).is_absolute(), f"reviewed document source path is absolute: {source_id}")
        source_path = (REPO_ROOT / relative_path).resolve()
        _require(source_path.is_relative_to(REPO_ROOT.resolve()), f"reviewed document source escapes repository: {source_id}")
        _require(source_path.is_file(), f"reviewed document source is missing: {source_id}")
        _require(current.get("sha256") == _file_sha256(source_path), f"reviewed document source hash differs: {source_id}")
    _require(
        document.get("source_binding_sha256") == _object_sha256(bindings),
        "reviewed document source binding hash is stale",
    )
    content = {key: value for key, value in document.items() if key != "document_content_sha256"}
    _require(
        document.get("document_content_sha256") == _object_sha256(content),
        "reviewed document content hash is stale",
    )
    common_policies = document.get("common_policies")
    features = document.get("features")
    _require(isinstance(common_policies, list), "reviewed document common policies must be an array")
    _require(isinstance(features, list), "reviewed document features must be an array")
    _require(all(isinstance(item, dict) for item in common_policies), "reviewed document has an invalid common policy")
    _require(all(isinstance(item, dict) for item in features), "reviewed document has an invalid feature")
    common_ids = [item.get("id") for item in common_policies]
    feature_ids = [item.get("id") for item in features]
    _require(common_ids == EXPECTED_COMMON_IDS, "common policy ID order is invalid")
    _require(feature_ids == EXPECTED_FEATURE_IDS, "feature ID order is invalid")
    gates = document.get("remaining_gates")
    _require(isinstance(gates, list) and len(gates) == 5, "expected exactly 5 remaining gates")
    _require(
        all(
            isinstance(item, dict)
            and set(item) == {"id", "kind", "status", "affected_feature_ids", "closure", "title", "kind_label"}
            for item in gates
        ),
        "reviewed document remaining gate schema is invalid",
    )
    _require([item.get("id") for item in gates] == EXPECTED_GATE_IDS, "remaining gate ID order is invalid")
    _require(all(item.get("status") == "NOT_RUN" for item in gates), "a remaining gate is not NOT_RUN")
    return common_ids, feature_ids


def _validate_html_binding(document: dict[str, Any]) -> None:
    text = DOCUMENT_HTML_PATH.read_text(encoding="utf-8")
    start_marker = '<script id="report-data" type="application/json">'
    end_marker = "</script>"
    _require(text.count(start_marker) == 1, "reviewed HTML report-data block count differs")
    payload = text.split(start_marker, 1)[1].split(end_marker, 1)[0]
    try:
        embedded = json.loads(
            payload,
            object_pairs_hook=_object_pairs,
            parse_constant=_reject_constant,
        )
    except json.JSONDecodeError as exc:
        raise BaselineReviewError(f"reviewed HTML report-data is invalid JSON: {exc}") from exc
    _require(embedded == document, "reviewed HTML report-data differs from the reviewed JSON")


def _validate_answers(
    answers: dict[str, Any], document: dict[str, Any], common_ids: list[str], feature_ids: list[str]
) -> dict[str, Any]:
    _strict_object(answers, "answers", ANSWER_FIELDS)
    _require(
        answers.get("schema_version") == "walksafe.feature-policy-baseline-review-answers.v1",
        "answer schema version is invalid",
    )
    metadata = document["metadata"]
    _require(answers.get("report_id") == metadata.get("document_id"), "answer report ID differs from the document")
    _require(
        answers.get("document_content_sha256") == document.get("document_content_sha256"),
        "answer document content binding differs",
    )
    _require(
        answers.get("source_binding_sha256") == document.get("source_binding_sha256"),
        "answer source binding differs",
    )
    _require(answers.get("baseline_status") == "NOT_APPROVED", "answer file must remain NOT_APPROVED")
    reviewer = _strict_string(answers.get("reviewer"), "answers.reviewer")
    exported_at = _parse_timestamp(answers.get("exported_at"), "answers.exported_at")
    _require(
        exported_at <= datetime.now(exported_at.tzinfo) + timedelta(minutes=5),
        "answers.exported_at is in the future",
    )
    items = answers.get("items")
    _require(isinstance(items, list), "answers.items must be an array")
    expected_ids = [*common_ids, *feature_ids]
    _require(len(items) == len(expected_ids), "answer item count differs from the document")
    decisions: dict[str, int] = {value: 0 for value in sorted(ALLOWED_DECISIONS)}
    non_empty_notes = 0
    actual_ids: list[str] = []
    for index, item in enumerate(items):
        current = _strict_object(item, f"answers.items[{index}]", ITEM_FIELDS)
        item_id = _strict_string(current.get("id"), f"answers.items[{index}].id")
        decision = current.get("decision")
        note = current.get("note")
        _require(decision in ALLOWED_DECISIONS, f"invalid decision for {item_id}")
        _require(isinstance(note, str), f"note for {item_id} must be a string")
        if decision in {"revise", "hold"}:
            _require(bool(note.strip()), f"{decision} requires a note for {item_id}")
        actual_ids.append(item_id)
        decisions[decision] += 1
        non_empty_notes += int(bool(note.strip()))
    _require(actual_ids == expected_ids, "answer IDs are missing, duplicated, added, or out of order")
    return {
        "reviewer": reviewer,
        "exported_at": answers["exported_at"],
        "exported_at_parsed": exported_at,
        "item_count": len(items),
        "common_policy_count": len(common_ids),
        "feature_count": len(feature_ids),
        "confirmed": decisions["confirm"],
        "revision_requested": decisions["revise"],
        "held": decisions["hold"],
        "non_empty_note_count": non_empty_notes,
    }


def _validate_intake(intake: dict[str, Any], answers: dict[str, Any], summary: dict[str, Any]) -> None:
    _strict_object(
        intake,
        "intake",
        {
            "schema_version",
            "intake_id",
            "intake_version",
            "change_reason",
            "answer_intake",
            "answer_metadata",
            "review_source_bindings",
            "record_controls",
            "approval_boundary",
        },
    )
    _require(
        intake.get("schema_version") == "walksafe.feature-policy-baseline-review-answer-intake.v1",
        "intake schema version is invalid",
    )
    _require(
        intake.get("intake_id") == "WS-FEATURE-POLICY-BASELINE-REVIEW-ANSWER-INTAKE-20260721-001",
        "intake ID is invalid",
    )
    _require(intake.get("intake_version") == "1.0.0", "intake version is invalid")
    _strict_string(intake.get("change_reason"), "intake.change_reason")
    answer_intake = _strict_object(
        intake.get("answer_intake"),
        "intake.answer_intake",
        {
            "external_path",
            "external_path_usage",
            "controlled_path",
            "sha256",
            "byte_length",
            "captured_at",
            "controlled_revision",
            "prior_controlled_path",
            "prior_controlled_sha256",
            "source_class",
            "artifact_form",
            "evidence_classification",
            "adoption_trust",
        },
    )
    _require(answer_intake.get("external_path_usage") == "PROVENANCE_ONLY", "external path is not provenance-only")
    external_path = _strict_string(answer_intake.get("external_path"), "intake.answer_intake.external_path")
    _require(Path(external_path).is_absolute(), "external provenance path must be absolute")
    controlled_path = _strict_string(answer_intake.get("controlled_path"), "intake.answer_intake.controlled_path")
    _require(not Path(controlled_path).is_absolute(), "controlled answer path must be relative")
    _require((REPO_ROOT / controlled_path).resolve() == ANSWERS_PATH.resolve(), "controlled answer path differs")
    _require(answer_intake.get("sha256") == _file_sha256(ANSWERS_PATH), "controlled answer hash differs")
    _strict_int(answer_intake.get("byte_length"), "intake.answer_intake.byte_length", minimum=1)
    _require(answer_intake.get("byte_length") == ANSWERS_PATH.stat().st_size, "controlled answer byte length differs")
    captured_at = _parse_timestamp(answer_intake.get("captured_at"), "intake.answer_intake.captured_at")
    _require(captured_at >= summary["exported_at_parsed"], "capture time is before export time")
    _require(
        captured_at <= datetime.now(captured_at.tzinfo) + timedelta(minutes=5),
        "intake capture time is in the future",
    )
    _strict_int(answer_intake.get("controlled_revision"), "intake.answer_intake.controlled_revision", minimum=1)
    _require(answer_intake.get("controlled_revision") == 1, "first controlled revision must be 1")
    _require(answer_intake.get("prior_controlled_path") is None, "first revision must not supersede a path")
    _require(answer_intake.get("prior_controlled_sha256") is None, "first revision must not supersede a hash")
    _require(answer_intake.get("source_class") == "EXTERNAL", "answer source class is invalid")
    _require(answer_intake.get("artifact_form") == "EXTERNAL_RECORD", "answer artifact form is invalid")
    _require(answer_intake.get("evidence_classification") == "USER_CONFIRMED", "answer evidence class is invalid")
    _require(answer_intake.get("adoption_trust") == "D", "answer adoption trust is invalid")

    metadata = _strict_object(
        intake.get("answer_metadata"),
        "intake.answer_metadata",
        {
            "schema_version",
            "report_id",
            "document_content_sha256",
            "source_binding_sha256",
            "baseline_status",
            "reviewer",
            "exported_at",
            "item_count",
            "common_policy_count",
            "feature_count",
            "confirmed",
            "revision_requested",
            "held",
            "non_empty_note_count",
        },
    )
    for field in (
        "schema_version",
        "report_id",
        "document_content_sha256",
        "source_binding_sha256",
        "baseline_status",
        "reviewer",
        "exported_at",
    ):
        _require(metadata.get(field) == answers.get(field), f"intake answer metadata is stale: {field}")
    for field in (
        "item_count",
        "common_policy_count",
        "feature_count",
        "confirmed",
        "revision_requested",
        "held",
        "non_empty_note_count",
    ):
        _strict_int(metadata.get(field), f"intake.answer_metadata.{field}", minimum=0)
        _require(metadata.get(field) == summary.get(field), f"intake review summary is stale: {field}")

    expected_sources = {
        "SRC-REVIEWED-FEATURE-POLICY-DRAFT-JSON": DOCUMENT_JSON_PATH,
        "SRC-REVIEWED-FEATURE-POLICY-DRAFT-HTML": DOCUMENT_HTML_PATH,
    }
    sources = intake.get("review_source_bindings")
    _require(isinstance(sources, list) and len(sources) == len(expected_sources), "review source bindings are incomplete")
    seen: set[str] = set()
    for index, source in enumerate(sources):
        current = _strict_object(source, f"intake.review_source_bindings[{index}]", {"id", "path", "sha256"})
        source_id = _strict_string(current.get("id"), f"review source ID {index}")
        _require(source_id in expected_sources and source_id not in seen, f"unknown or duplicate review source: {source_id}")
        expected_path = expected_sources[source_id]
        relative_path = _strict_string(current.get("path"), f"review source path {source_id}")
        _require(relative_path == str(expected_path.relative_to(REPO_ROOT)), f"review source path differs: {source_id}")
        _require(current.get("sha256") == _file_sha256(expected_path), f"review source hash differs: {source_id}")
        seen.add(source_id)
    _require(seen == set(expected_sources), "review source binding set is incomplete")

    controls = _strict_object(
        intake.get("record_controls"),
        "intake.record_controls",
        {"external_signature_status", "confidentiality", "personal_data", "retention_class", "notes"},
    )
    _require(controls.get("external_signature_status") == "NOT_SIGNED", "signature status is invalid")
    _require(controls.get("confidentiality") == "INTERNAL", "confidentiality is invalid")
    _require(controls.get("personal_data") == ["reviewer_name"], "personal data classification is invalid")
    _require(controls.get("retention_class") == "PROJECT_LIFECYCLE_PLUS_3_YEARS", "retention class is invalid")
    _strict_string(controls.get("notes"), "intake.record_controls.notes")

    boundary = _strict_object(
        intake.get("approval_boundary"),
        "intake.approval_boundary",
        {
            "review_status",
            "baseline_status",
            "release_status",
            "approved_by",
            "approved_at",
            "baseline_approval_recorded",
            "formal_deliverable_generation_status",
            "note",
        },
    )
    _require(boundary.get("review_status") == "REVIEW_COMPLETE_ALL_CONFIRMED", "review status is invalid")
    _require(boundary.get("baseline_status") == "NOT_APPROVED", "intake must not approve the baseline")
    _require(boundary.get("release_status") == "NOT_ELIGIBLE", "intake must not allow release")
    _require(boundary.get("approved_by") is None and boundary.get("approved_at") is None, "intake has approver data")
    _require(boundary.get("baseline_approval_recorded") is False, "intake records baseline approval")
    _require(
        boundary.get("formal_deliverable_generation_status") == "NOT_RUN",
        "intake claims formal deliverables were generated",
    )
    _strict_string(boundary.get("note"), "intake.approval_boundary.note")


def _source_binding(path: Path) -> dict[str, str]:
    return {"path": str(path.relative_to(REPO_ROOT)), "sha256": _file_sha256(path)}


def build_resolution() -> dict[str, Any]:
    answers = load_strict_json(ANSWERS_PATH)
    intake = load_strict_json(INTAKE_PATH)
    document = load_strict_json(DOCUMENT_JSON_PATH)
    common_ids, feature_ids = _validate_document(document)
    _validate_html_binding(document)
    summary = _validate_answers(answers, document, common_ids, feature_ids)
    _validate_intake(intake, answers, summary)
    decisions = [
        {
            "id": item["id"],
            "decision": item["decision"],
            "note": item["note"],
            "note_sha256": hashlib.sha256(item["note"].encode("utf-8")).hexdigest(),
        }
        for item in answers["items"]
    ]
    decision_binding_sha256 = _object_sha256(decisions)
    all_confirmed = summary["confirmed"] == summary["item_count"]
    status = (
        "READY_FOR_SEPARATE_BASELINE_APPROVAL"
        if all_confirmed
        else "BLOCKED_BY_HOLD"
        if summary["held"]
        else "REVISION_REQUIRED"
    )
    source_bindings = {
        "generator": _source_binding(GENERATOR_PATH),
        "answers": _source_binding(ANSWERS_PATH),
        "intake": _source_binding(INTAKE_PATH),
        "reviewed_document_json": _source_binding(DOCUMENT_JSON_PATH),
        "reviewed_document_html": _source_binding(DOCUMENT_HTML_PATH),
    }
    resolution: dict[str, Any] = {
        "schema_version": "walksafe.feature-policy-baseline-review-resolution.v1",
        "metadata": {
            "resolution_id": "WS-FEATURE-POLICY-BASELINE-REVIEW-RESOLUTION-20260721-001",
            "resolution_version": "1.0.0",
            "controlled_revision": 1,
            "supersedes_resolution_id": None,
            "supersedes_resolution_sha256": None,
            "as_of": "2026-07-21",
            "status": status,
            "purpose": "63개 최종 정책 검토 결과를 변경 없이 고정하고 별도 정책 기준선 승인 입력으로 전달한다.",
        },
        "source_bindings": source_bindings,
        "source_binding_sha256": _object_sha256(source_bindings),
        "review_summary": {
            key: value for key, value in summary.items() if key != "exported_at_parsed"
        },
        "reviewed_policy": {
            "document_id": document["metadata"]["document_id"],
            "document_version": document["metadata"]["document_version"],
            "document_content_sha256": document["document_content_sha256"],
            "source_binding_sha256": document["source_binding_sha256"],
            "policy_content_changed_by_review": not all_confirmed,
            "regeneration_required": not all_confirmed,
            "regeneration_reason": (
                "수정·보류가 없어 검토된 정책 본문과 지문을 그대로 유지한다."
                if all_confirmed
                else "수정 또는 보류 내용을 반영한 뒤 새 지문으로 재검토해야 한다."
            ),
        },
        "decision_records": decisions,
        "decision_binding_sha256": decision_binding_sha256,
        "remaining_gates": [
            {
                "id": gate["id"],
                "status": gate["status"],
                "title": gate["title"],
                "kind_label": gate["kind_label"],
                "completion_condition": gate["closure"],
                "affected_feature_ids": gate["affected_feature_ids"],
                **GATE_APPROVAL_BOUNDARIES[gate["id"]],
                "does_not_block_after_policy_baseline_approval": [
                    "0~6 정식 산출물 작성",
                    "통제된 개발·시험 준비",
                ],
            }
            for gate in document["remaining_gates"]
        ],
        "approval_boundary": {
            "policy_review_status": "REVIEW_COMPLETE_ALL_CONFIRMED" if all_confirmed else status,
            "baseline_approval_status": "AWAITING_EXPLICIT_APPROVAL" if all_confirmed else "NOT_READY",
            "baseline_status": "NOT_APPROVED",
            "baseline_approval_recorded": False,
            "approved_by": None,
            "approved_at": None,
            "approval_scope": None,
            "remaining_gates_are_waived": False,
            "release_status": "NOT_ELIGIBLE",
            "formal_deliverable_generation_status": "NOT_RUN",
        },
        "handoff": {
            "current_step": "SEPARATE_POLICY_BASELINE_APPROVAL_REQUIRED" if all_confirmed else status,
            "explicit_approval_required": all_confirmed,
            "formal_deliverables_authorized": False,
            "required_approver_role": "PROJECT_MANAGER_AND_FINAL_POLICY_OWNER",
            "approval_record_requirements": [
                "승인자 역할과 이름",
                "승인 시각과 승인문 원문",
                "정책 문서 ID·버전·내용 지문",
                "검토 종결 ID·버전·파일 지문과 결정 결속 지문",
                "남은 5개 gate 미면제와 출시 NOT_ELIGIBLE",
            ],
            "approval_target": {
                "document_id": document["metadata"]["document_id"],
                "document_version": document["metadata"]["document_version"],
                "document_content_sha256": document["document_content_sha256"],
                "baseline_version": "1.0.0",
                "scope": "POLICY_BASELINE_ONLY",
                "review_resolution_id": "WS-FEATURE-POLICY-BASELINE-REVIEW-RESOLUTION-20260721-001",
                "review_resolution_version": "1.0.0",
                "decision_binding_sha256": decision_binding_sha256,
                "controlled_answer_sha256": _file_sha256(ANSWERS_PATH),
            },
            "required_approval_statement": (
                "검토 종결 WS-FEATURE-POLICY-BASELINE-REVIEW-RESOLUTION-20260721-001 버전 1.0.0, "
                f"결정 지문 {decision_binding_sha256}을 근거로 문서 "
                f"{document['metadata']['document_id']} 버전 {document['metadata']['document_version']}, "
                f"내용 지문 {document['document_content_sha256']}를 WalkSafe 기능 정책 기준선 1.0.0으로 승인하며, "
                "5개 미실행 검증 항목은 면제하지 않고 출시 상태는 NOT_ELIGIBLE로 유지한 채 "
                "0~6 정식 산출물 작성을 시작하도록 승인합니다."
            ),
            "after_approval": [
                "별도 불변 승인 기록을 생성하고 이 검토 종결 기록과 결속한다.",
                "승인 기록과 기준선 manifest를 현행 정본으로 지정하고 검토용 draft는 근거 원본으로 보존한다.",
                "135개 결정대장을 승인 정책과 맞추고 오래된 충돌·상태를 갱신한다.",
                "REQ-16 추적 원장 골격을 개설하고 0~6 정식 산출물을 작성하면서 연결을 채운 뒤 각 기준선에서 검증한다.",
            ],
        },
    }
    resolution["resolution_content_sha256"] = _object_sha256(resolution)
    validate_resolution(resolution)
    return resolution


def validate_resolution(resolution: dict[str, Any]) -> None:
    _require(
        set(resolution)
        == {
            "schema_version",
            "metadata",
            "source_bindings",
            "source_binding_sha256",
            "review_summary",
            "reviewed_policy",
            "decision_records",
            "decision_binding_sha256",
            "remaining_gates",
            "approval_boundary",
            "handoff",
            "resolution_content_sha256",
        },
        "resolution fields differ from the schema",
    )
    _require(
        resolution.get("schema_version") == "walksafe.feature-policy-baseline-review-resolution.v1",
        "resolution schema version is invalid",
    )
    metadata = _strict_object(
        resolution.get("metadata"),
        "resolution.metadata",
        {
            "resolution_id",
            "resolution_version",
            "controlled_revision",
            "supersedes_resolution_id",
            "supersedes_resolution_sha256",
            "as_of",
            "status",
            "purpose",
        },
    )
    _require(
        metadata.get("resolution_id") == "WS-FEATURE-POLICY-BASELINE-REVIEW-RESOLUTION-20260721-001",
        "resolution ID is invalid",
    )
    _require(metadata.get("resolution_version") == "1.0.0", "resolution version is invalid")
    _strict_int(metadata.get("controlled_revision"), "resolution.metadata.controlled_revision", minimum=1)
    _require(metadata.get("controlled_revision") == 1, "resolution controlled revision is invalid")
    _require(
        metadata.get("supersedes_resolution_id") is None
        and metadata.get("supersedes_resolution_sha256") is None,
        "first resolution must not supersede another resolution",
    )
    _require(metadata.get("as_of") == "2026-07-21", "resolution date is invalid")
    _require(metadata.get("status") == "READY_FOR_SEPARATE_BASELINE_APPROVAL", "resolution is not ready")
    _strict_string(metadata.get("purpose"), "resolution.metadata.purpose")

    answers = load_strict_json(ANSWERS_PATH)
    intake = load_strict_json(INTAKE_PATH)
    document = load_strict_json(DOCUMENT_JSON_PATH)
    common_ids, feature_ids = _validate_document(document)
    _validate_html_binding(document)
    expected_summary_with_datetime = _validate_answers(
        answers,
        document,
        common_ids,
        feature_ids,
    )
    _validate_intake(intake, answers, expected_summary_with_datetime)
    expected_summary = {
        key: value
        for key, value in expected_summary_with_datetime.items()
        if key != "exported_at_parsed"
    }
    summary = _strict_object(
        resolution.get("review_summary"),
        "resolution.review_summary",
        set(expected_summary),
    )
    _require(summary == expected_summary, "resolution review summary differs from the controlled answers")

    expected_decisions = [
        {
            "id": item["id"],
            "decision": item["decision"],
            "note": item["note"],
            "note_sha256": hashlib.sha256(item["note"].encode("utf-8")).hexdigest(),
        }
        for item in answers["items"]
    ]
    decisions = resolution["decision_records"]
    _require(isinstance(decisions, list), "resolution decision records must be an array")
    _require(decisions == expected_decisions, "resolution decisions differ from the controlled answers")
    _require(
        resolution.get("decision_binding_sha256") == _object_sha256(resolution["decision_records"]),
        "resolution decision binding is stale",
    )

    expected_gates = [
        {
            "id": gate["id"],
            "status": gate["status"],
            "title": gate["title"],
            "kind_label": gate["kind_label"],
            "completion_condition": gate["closure"],
            "affected_feature_ids": gate["affected_feature_ids"],
            **GATE_APPROVAL_BOUNDARIES[gate["id"]],
            "does_not_block_after_policy_baseline_approval": [
                "0~6 정식 산출물 작성",
                "통제된 개발·시험 준비",
            ],
        }
        for gate in document["remaining_gates"]
    ]
    gates = resolution["remaining_gates"]
    _require(gates == expected_gates, "resolution remaining gates differ from the reviewed document")

    expected_reviewed_policy = {
        "document_id": document["metadata"]["document_id"],
        "document_version": document["metadata"]["document_version"],
        "document_content_sha256": document["document_content_sha256"],
        "source_binding_sha256": document["source_binding_sha256"],
        "policy_content_changed_by_review": False,
        "regeneration_required": False,
        "regeneration_reason": "수정·보류가 없어 검토된 정책 본문과 지문을 그대로 유지한다.",
    }
    reviewed_policy = _strict_object(
        resolution.get("reviewed_policy"),
        "resolution.reviewed_policy",
        set(expected_reviewed_policy),
    )
    _require(reviewed_policy == expected_reviewed_policy, "reviewed policy differs from the actual document")

    expected_boundary = {
        "policy_review_status": "REVIEW_COMPLETE_ALL_CONFIRMED",
        "baseline_approval_status": "AWAITING_EXPLICIT_APPROVAL",
        "baseline_status": "NOT_APPROVED",
        "baseline_approval_recorded": False,
        "approved_by": None,
        "approved_at": None,
        "approval_scope": None,
        "remaining_gates_are_waived": False,
        "release_status": "NOT_ELIGIBLE",
        "formal_deliverable_generation_status": "NOT_RUN",
    }
    boundary = _strict_object(
        resolution.get("approval_boundary"),
        "resolution.approval_boundary",
        set(expected_boundary),
    )
    _require(boundary == expected_boundary, "resolution approval boundary differs")

    handoff = _strict_object(
        resolution.get("handoff"),
        "resolution.handoff",
        {
            "current_step",
            "explicit_approval_required",
            "formal_deliverables_authorized",
            "required_approver_role",
            "approval_record_requirements",
            "approval_target",
            "required_approval_statement",
            "after_approval",
        },
    )
    _require(handoff.get("current_step") == "SEPARATE_POLICY_BASELINE_APPROVAL_REQUIRED", "handoff step is invalid")
    _require(handoff.get("explicit_approval_required") is True, "explicit approval is not required")
    _require(handoff.get("formal_deliverables_authorized") is False, "formal outputs are authorized early")
    _require(handoff.get("required_approver_role") == "PROJECT_MANAGER_AND_FINAL_POLICY_OWNER", "required approver role differs")
    target = _strict_object(
        handoff.get("approval_target"),
        "resolution.handoff.approval_target",
        {
            "document_id",
            "document_version",
            "document_content_sha256",
            "baseline_version",
            "scope",
            "review_resolution_id",
            "review_resolution_version",
            "decision_binding_sha256",
            "controlled_answer_sha256",
        },
    )
    expected_target = {
        "document_id": document["metadata"]["document_id"],
        "document_version": document["metadata"]["document_version"],
        "document_content_sha256": document["document_content_sha256"],
        "baseline_version": "1.0.0",
        "scope": "POLICY_BASELINE_ONLY",
        "review_resolution_id": metadata["resolution_id"],
        "review_resolution_version": metadata["resolution_version"],
        "decision_binding_sha256": resolution["decision_binding_sha256"],
        "controlled_answer_sha256": _file_sha256(ANSWERS_PATH),
    }
    _require(target == expected_target, "approval target differs from the actual reviewed policy")
    expected_statement = (
        f"검토 종결 {target['review_resolution_id']} 버전 {target['review_resolution_version']}, "
        f"결정 지문 {target['decision_binding_sha256']}을 근거로 문서 "
        f"{target['document_id']} 버전 {target['document_version']}, "
        f"내용 지문 {target['document_content_sha256']}를 WalkSafe 기능 정책 기준선 1.0.0으로 승인하며, "
        "5개 미실행 검증 항목은 면제하지 않고 출시 상태는 NOT_ELIGIBLE로 유지한 채 "
        "0~6 정식 산출물 작성을 시작하도록 승인합니다."
    )
    _require(handoff.get("required_approval_statement") == expected_statement, "required approval statement differs")
    expected_after_approval = [
        "별도 불변 승인 기록을 생성하고 이 검토 종결 기록과 결속한다.",
        "승인 기록과 기준선 manifest를 현행 정본으로 지정하고 검토용 draft는 근거 원본으로 보존한다.",
        "135개 결정대장을 승인 정책과 맞추고 오래된 충돌·상태를 갱신한다.",
        "REQ-16 추적 원장 골격을 개설하고 0~6 정식 산출물을 작성하면서 연결을 채운 뒤 각 기준선에서 검증한다.",
    ]
    _require(handoff.get("after_approval") == expected_after_approval, "post-approval handoff differs")
    expected_approval_record_requirements = [
        "승인자 역할과 이름",
        "승인 시각과 승인문 원문",
        "정책 문서 ID·버전·내용 지문",
        "검토 종결 ID·버전·파일 지문과 결정 결속 지문",
        "남은 5개 gate 미면제와 출시 NOT_ELIGIBLE",
    ]
    _require(
        handoff.get("approval_record_requirements") == expected_approval_record_requirements,
        "approval record requirements differ",
    )
    expected_bindings = {
        "generator": _source_binding(GENERATOR_PATH),
        "answers": _source_binding(ANSWERS_PATH),
        "intake": _source_binding(INTAKE_PATH),
        "reviewed_document_json": _source_binding(DOCUMENT_JSON_PATH),
        "reviewed_document_html": _source_binding(DOCUMENT_HTML_PATH),
    }
    _require(resolution.get("source_bindings") == expected_bindings, "resolution source files differ")
    _require(
        resolution.get("source_binding_sha256") == _object_sha256(resolution["source_bindings"]),
        "resolution source binding is stale",
    )
    expected_content = _object_sha256(
        {key: value for key, value in resolution.items() if key != "resolution_content_sha256"}
    )
    _require(resolution.get("resolution_content_sha256") == expected_content, "resolution content hash is stale")


def _json_bytes(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail if the generated resolution is missing or stale")
    args = parser.parse_args(argv)
    try:
        resolution = build_resolution()
        content = _json_bytes(resolution)
        if args.check:
            _require(OUTPUT_PATH.is_file(), "generated review resolution is missing")
            _require(OUTPUT_PATH.read_bytes() == content, "generated review resolution is stale")
        else:
            OUTPUT_PATH.write_bytes(content)
    except (BaselineReviewError, OSError, KeyError, TypeError) as exc:
        print(f"WalkSafe feature policy baseline review failed: {exc}", file=sys.stderr)
        return 1
    if args.check:
        print("WalkSafe feature policy baseline review check passed: 63/63 confirmed, separate approval required")
    else:
        print(OUTPUT_PATH.relative_to(REPO_ROOT))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Validate and build the integrated WalkSafe Android baseline questionnaire."""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
import re
import sys
from typing import Any
from urllib.parse import urlparse

try:
    from scripts import build_walksafe_answer_review as answer_review
except ModuleNotFoundError:  # Direct execution from the scripts directory.
    import build_walksafe_answer_review as answer_review  # type: ignore[no-redef]


REPO_ROOT = Path(__file__).resolve().parents[1]
QUESTIONNAIRE_DIR = REPO_ROOT / "docs" / "control" / "questionnaire"
SOURCE_RECORDS_DIR = QUESTIONNAIRE_DIR / "source-records"
DEFAULT_QUESTIONS_PATH = answer_review.DEFAULT_QUESTIONS_PATH
DEFAULT_ANSWERS_PATH = answer_review.DEFAULT_ANSWERS_PATH
DEFAULT_ANALYSIS_PATH = answer_review.DEFAULT_ANALYSIS_PATH
DEFAULT_FOLLOWUPS_PATH = answer_review.DEFAULT_FOLLOWUPS_PATH
DEFAULT_DELTA_PATH = (
    SOURCE_RECORDS_DIR / "walksafe-android-baseline-delta-review.json"
)
DEFAULT_INTEGRATED_ANALYSIS_PATH = (
    QUESTIONNAIRE_DIR / "walksafe-integrated-baseline-analysis.json"
)
DEFAULT_INTEGRATED_QUESTIONS_PATH = (
    QUESTIONNAIRE_DIR / "walksafe-integrated-baseline-questions.json"
)
DEFAULT_TEMPLATE_PATH = QUESTIONNAIRE_DIR / "integrated-baseline-template.html"
ARTIFACT_TYPES_PATH = REPO_ROOT / "docs" / "control" / "artifact-types.json"
DEFAULT_OUTPUT_PATH = (
    QUESTIONNAIRE_DIR / "walksafe-integrated-baseline-questionnaire-20260718.html"
)
DATA_PLACEHOLDER = "__WALKSAFE_INTEGRATED_BASELINE_DATA__"

DELTA_FIELDS = {
    "review_schema_version",
    "delta_policy",
    "source_answer_sha256",
    "question_set_hash",
    "analysis_sha256",
    "followups_sha256",
    "storage_binding_hash",
    "exported_at",
    "followup_answers",
    "followup_notes",
    "unresolved_required",
    "note_errors",
    "consistency_errors",
    "source_note_clarifications",
    "remaining_source_note_errors",
}
DELTA_ANSWER_FIELDS = {"answer", "active"}

INTEGRATED_ANALYSIS_FIELDS = {
    "schema_version",
    "title",
    "as_of",
    "merge_policy",
    "source_materials",
    "directives",
    "domain_reviews",
    "findings",
    "runtime_evidence",
    "external_constraints",
}
MERGE_POLICY_FIELDS = {"rank", "source", "rule"}
SOURCE_MATERIAL_FIELDS = {"id", "type", "path", "sha256", "summary"}
DIRECTIVE_FIELDS = {
    "id",
    "area",
    "title",
    "user_statement",
    "normalized_definition",
    "status",
    "supersedes",
    "implementation_meaning",
    "open_question_topics",
}
DOMAIN_REVIEW_FIELDS = {
    "id",
    "order",
    "title",
    "one_line",
    "confirmed",
    "implementation",
    "conflicts",
    "open_topics",
    "original_category_ids",
    "followup_group_ids",
}
FINDING_FIELDS = {
    "id",
    "severity",
    "title",
    "plain_summary",
    "evidence_refs",
    "impact",
    "recommendation",
    "new_question_topics",
}
RUNTIME_EVIDENCE_FIELDS = {
    "id",
    "area",
    "title",
    "status",
    "summary",
    "facts",
    "evidence",
    "decision",
}
EVIDENCE_ITEM_FIELDS = {"path", "lines", "claim"}
EXTERNAL_CONSTRAINT_FIELDS = {
    "id",
    "area",
    "title",
    "summary",
    "source_url",
    "consequence",
    "status",
}

INTEGRATED_QUESTION_FIELDS = {
    "metadata",
    "groups",
    "questions",
    "consistency_rules",
    "coverage_checklist",
}
QUESTION_METADATA_FIELDS = {
    "schema_version",
    "question_set_id",
    "version",
    "title",
    "created_at",
    "purpose",
    "completion_rule",
}
GROUP_FIELDS = {"id", "order", "title", "description"}
QUESTION_FIELDS = {
    "id",
    "group_id",
    "order",
    "priority",
    "feature",
    "prompt",
    "explanation",
    "why_needed",
    "required",
    "answer_type",
    "options",
    "recommended_option_ids",
    "recommendation_reason",
    "detail_required_option_ids",
    "detail_prompt",
    "source_refs",
    "affects",
    "activation",
    "validation",
}
OPTION_FIELDS = {"id", "label", "meaning", "impact"}
ACTIVATION_FIELDS = {"question_id", "option_ids"}
CONSISTENCY_RULE_FIELDS = {
    "id",
    "severity",
    "when_all",
    "message",
    "resolution",
}
CONSISTENCY_CONDITION_FIELDS = {"question_id", "option_ids"}

DIRECTIVE_STATUSES = {"CONFIRMED", "CONDITIONAL", "CONFLICT", "COMPLIANCE_GATE"}
FINDING_SEVERITIES = {"CRITICAL", "HIGH", "MEDIUM", "LOW"}
RUNTIME_STATUSES = {"MEASURED", "IMPLEMENTED", "NOT_MEASURED", "INFERENCE"}
QUESTION_PRIORITIES = {"P0", "P1", "P2"}
ANSWER_TYPES = {"single_choice", "multi_choice", "text", "number"}
USER_DIRECTIVE_REFS = {
    "USER-20260718-ADMIN",
    "USER-20260718-CONSENT",
    "USER-20260718-DATA",
    "USER-20260718-DEVICE",
    "USER-20260718-FAILURE",
    "USER-20260718-NAV",
    "USER-20260718-RELEASE",
    "USER-20260718-SESSION",
    "USER-20260718-UI",
    "USER-20260718-VOICE",
}
REQUIRED_SOURCE_MATERIAL_IDS = {
    "SRC-ORIGINAL-ANSWERS",
    "SRC-DELTA-ANSWERS",
}
CONTROLLED_SOURCE_MATERIAL_PATHS = {
    "SRC-ORIGINAL-ANSWERS": DEFAULT_ANSWERS_PATH,
    "SRC-DELTA-ANSWERS": DEFAULT_DELTA_PATH,
    "SRC-ORIGINAL-QUESTIONS": DEFAULT_QUESTIONS_PATH,
    "SRC-DELTA-QUESTIONS": DEFAULT_FOLLOWUPS_PATH,
    "SRC-RUNTIME-FIELD": (
        REPO_ROOT
        / "artifacts"
        / "android-field-sessions"
        / "20260717-053234_R3CN50F4APH"
        / "field_session_summary.json"
    ),
}
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
ID_PATTERN = re.compile(r"^[A-Za-z][A-Za-z0-9._-]*$")
OPEN_TOPIC_PATTERN = re.compile(r"^(IBQ-\d{3}):\s+\S")
DELIVERABLE_ID_PATTERN = re.compile(
    r"^(?:DOC|MGT|DSC|REQ|DES|DEV|TST|SEC|AIML|REL|OPS|WS|CLS)-\d{2}$"
)
OFFICIAL_SOURCE_DOMAINS = {
    "ai.google.dev",
    "developer.android.com",
    "developers.google.com",
    "docs.github.com",
    "ftc.go.kr",
    "github.com",
    "kisa.or.kr",
    "korea.kr",
    "law.go.kr",
    "mois.go.kr",
    "moleg.go.kr",
    "msit.go.kr",
    "pipc.go.kr",
    "play.google.com",
    "policies.google.com",
    "privacy.go.kr",
    "source.android.com",
    "support.google.com",
    "tmapapi.sktelecom.com",
    "tmapmobility.com",
}


class IntegratedBaselineValidationError(answer_review.AnswerReviewValidationError):
    """Raised when an integrated-baseline input violates its contract."""


load_strict_json = answer_review.load_strict_json
file_sha256 = answer_review.file_sha256
object_sha256 = answer_review.object_sha256


def _resolved_source_material_path(item_id: str, recorded_path: str) -> Path:
    """Resolve known records to repository-controlled files, not ambient paths."""
    controlled_path = CONTROLLED_SOURCE_MATERIAL_PATHS.get(item_id)
    if controlled_path is not None:
        return controlled_path
    source_path = Path(recorded_path)
    return source_path if source_path.is_absolute() else REPO_ROOT / source_path


def _report_source_materials(source_materials: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Expose reproducible paths while preserving the canonical input object and hash."""
    resolved: list[dict[str, Any]] = []
    for item in source_materials:
        report_item = dict(item)
        controlled_path = CONTROLLED_SOURCE_MATERIAL_PATHS.get(item["id"])
        if controlled_path is not None:
            report_item["path"] = controlled_path.relative_to(REPO_ROOT).as_posix()
        resolved.append(report_item)
    return resolved


def _exact_fields(value: dict[str, Any], expected: set[str], label: str) -> None:
    missing = sorted(expected - value.keys())
    unexpected = sorted(value.keys() - expected)
    if missing or unexpected:
        raise IntegratedBaselineValidationError(
            f"{label} fields are invalid: missing={missing}; unexpected={unexpected}"
        )


def _object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise IntegratedBaselineValidationError(f"{label} must be an object")
    return value


def _string(value: Any, label: str, *, allow_empty: bool = False) -> str:
    if not isinstance(value, str) or (not allow_empty and not value.strip()):
        qualifier = "a string" if allow_empty else "a non-empty string"
        raise IntegratedBaselineValidationError(f"{label} must be {qualifier}")
    return value


def _identifier(value: Any, label: str) -> str:
    identifier = _string(value, label)
    if ID_PATTERN.fullmatch(identifier) is None:
        raise IntegratedBaselineValidationError(
            f"{label} is not a valid identifier: {identifier}"
        )
    return identifier


def _string_list(
    value: Any,
    label: str,
    *,
    allowed: set[str] | None = None,
    allow_empty: bool = True,
) -> list[str]:
    if not isinstance(value, list):
        raise IntegratedBaselineValidationError(f"{label} must be an array")
    result = [_string(item, f"{label}[{index}]") for index, item in enumerate(value)]
    if not allow_empty and not result:
        raise IntegratedBaselineValidationError(f"{label} must not be empty")
    if len(result) != len(set(result)):
        raise IntegratedBaselineValidationError(f"{label} must not contain duplicates")
    if allowed is not None:
        unknown = sorted(set(result) - allowed)
        if unknown:
            raise IntegratedBaselineValidationError(
                f"{label} contains unknown IDs: {unknown}"
            )
    return result


def _sha256(value: Any, label: str) -> str:
    digest = _string(value, label)
    if SHA256_PATTERN.fullmatch(digest) is None:
        raise IntegratedBaselineValidationError(f"{label} must be lowercase SHA-256")
    return digest


def _enum(value: Any, allowed: set[str], label: str) -> str:
    selected = _string(value, label)
    if selected not in allowed:
        raise IntegratedBaselineValidationError(
            f"{label} must be one of {sorted(allowed)}"
        )
    return selected


def _positive_int(value: Any, label: str) -> int:
    if type(value) is not int or value < 1:
        raise IntegratedBaselineValidationError(f"{label} must be a positive integer")
    return value


def _is_official_https_url(value: str) -> bool:
    parsed = urlparse(value)
    hostname = (parsed.hostname or "").lower()
    return parsed.scheme == "https" and any(
        hostname == domain or hostname.endswith(f".{domain}")
        for domain in OFFICIAL_SOURCE_DOMAINS
    )


def _open_topic_list(
    value: Any,
    label: str,
    *,
    integrated_question_ids: set[str],
) -> list[str]:
    topics = _string_list(value, label)
    for index, topic in enumerate(topics):
        match = OPEN_TOPIC_PATTERN.match(topic)
        if match is None:
            raise IntegratedBaselineValidationError(
                f"{label}[{index}] must use 'IBQ-###: description'"
            )
        if match.group(1) not in integrated_question_ids:
            raise IntegratedBaselineValidationError(
                f"{label}[{index}] references unknown integrated question: {match.group(1)}"
            )
    return topics


def _artifact_display_ids() -> set[str]:
    catalog = load_strict_json(ARTIFACT_TYPES_PATH)
    if not isinstance(catalog, dict):
        raise IntegratedBaselineValidationError("artifact type catalog must be an object")
    metadata = _object(catalog.get("metadata"), "artifact type catalog.metadata")
    expected_count = metadata.get("expected_count")
    if type(expected_count) is not int or expected_count < 1:
        raise IntegratedBaselineValidationError(
            "artifact type catalog expected_count must be a positive integer"
        )
    artifact_types = catalog.get("artifact_types")
    if not isinstance(artifact_types, list) or len(artifact_types) != expected_count:
        raise IntegratedBaselineValidationError(
            "artifact type catalog count does not match metadata.expected_count"
        )
    display_ids = {
        _string(item.get("display_code"), f"artifact_types[{index}].display_code")
        for index, raw in enumerate(artifact_types)
        for item in [_object(raw, f"artifact_types[{index}]")]
    }
    if len(display_ids) != expected_count:
        raise IntegratedBaselineValidationError(
            "artifact type catalog display_code values must be unique"
        )
    return display_ids


def _delta_activity(
    followup_questions: list[dict[str, Any]],
    activation_rules: list[dict[str, Any]],
    answers: dict[str, dict[str, Any]],
) -> dict[str, bool]:
    rules = {rule["question_id"]: rule for rule in activation_rules}
    result: dict[str, bool] = {}
    for question in followup_questions:
        rule = rules.get(question["id"])
        if rule is None:
            result[question["id"]] = True
            continue
        dependency = answers.get(rule["depends_on_question_id"], {})
        result[question["id"]] = dependency.get("answer") not in rule["inactive_option_ids"]
    return result


def validate_delta(
    payload: dict[str, Any], review_data: dict[str, Any]
) -> dict[str, Any]:
    """Validate the exported delta and independently recompute every completion gate."""

    _exact_fields(payload, DELTA_FIELDS, "delta")
    if payload["review_schema_version"] != "walksafe.answer-review-delta.v1":
        raise IntegratedBaselineValidationError("delta.review_schema_version is unsupported")
    if payload["delta_policy"] != review_data["delta_policy"]:
        raise IntegratedBaselineValidationError("delta.delta_policy mismatch")
    _string(payload["exported_at"], "delta.exported_at")
    expected_bindings = {
        "source_answer_sha256": review_data["binding"]["source_answer_sha256"],
        "question_set_hash": review_data["binding"]["question_set_hash"],
        "analysis_sha256": review_data["binding"]["analysis_sha256"],
        "followups_sha256": review_data["binding"]["followups_sha256"],
        "storage_binding_hash": review_data["binding"]["storage_binding_hash"],
    }
    for field, expected in expected_bindings.items():
        _sha256(payload[field], f"delta.{field}")
        if payload[field] != expected:
            raise IntegratedBaselineValidationError(
                f"delta {field} mismatch: expected {expected}"
            )

    questions = review_data["followups"]
    by_id = {question["id"]: question for question in questions}
    raw_answers = _object(payload["followup_answers"], "delta.followup_answers")
    answers: dict[str, dict[str, Any]] = {}
    for question_id, raw in raw_answers.items():
        if question_id not in by_id:
            raise IntegratedBaselineValidationError(
                f"delta answer has unknown followup ID: {question_id}"
            )
        answer = _object(raw, f"delta.followup_answers.{question_id}")
        _exact_fields(answer, DELTA_ANSWER_FIELDS, f"delta.followup_answers.{question_id}")
        option_ids = {option["id"] for option in by_id[question_id]["options"]}
        if not isinstance(answer["answer"], str) or answer["answer"] not in option_ids:
            raise IntegratedBaselineValidationError(
                f"delta.followup_answers.{question_id}.answer is invalid"
            )
        if not isinstance(answer["active"], bool):
            raise IntegratedBaselineValidationError(
                f"delta.followup_answers.{question_id}.active must be boolean"
            )
        answers[question_id] = answer

    active = _delta_activity(
        questions, review_data["followup_activation_rules"], answers
    )
    for question_id, answer in answers.items():
        if answer["active"] != active[question_id]:
            raise IntegratedBaselineValidationError(
                f"delta.followup_answers.{question_id}.active does not match recomputed activity"
            )

    notes = _object(payload["followup_notes"], "delta.followup_notes")
    for question_id, note in notes.items():
        if question_id not in by_id:
            raise IntegratedBaselineValidationError(
                f"delta note has unknown followup ID: {question_id}"
            )
        _string(note, f"delta.followup_notes.{question_id}")

    unresolved = [
        question["id"]
        for question in questions
        if question["required"]
        and active[question["id"]]
        and question["id"] not in answers
    ]
    if payload["unresolved_required"] != unresolved:
        raise IntegratedBaselineValidationError(
            "delta.unresolved_required does not match recomputed active questions"
        )

    detail_ids = {
        requirement["question_id"]
        for requirement in review_data["followup_detail_requirements"]
    }
    note_errors: list[str] = []
    for question in questions:
        question_id = question["id"]
        if not active[question_id] or question_id not in answers:
            continue
        answer = answers[question_id]["answer"]
        needs_note = (
            question_id in detail_ids
            or answer == "custom"
            or answer not in question["recommended_option_ids"]
        )
        if needs_note and not str(notes.get(question_id, "")).strip():
            note_errors.append(question_id)
    if payload["note_errors"] != note_errors:
        raise IntegratedBaselineValidationError(
            "delta.note_errors does not match recomputed note requirements"
        )

    consistency_errors = []
    for rule in review_data["followup_consistency_rules"]:
        if all(
            active[condition["question_id"]]
            and answers.get(condition["question_id"], {}).get("answer")
            in condition["option_ids"]
            for condition in rule["when_all"]
        ):
            consistency_errors.append(rule)
    if payload["consistency_errors"] != consistency_errors:
        raise IntegratedBaselineValidationError(
            "delta.consistency_errors does not match recomputed rules"
        )

    source_error_ids = review_data["source"]["source_note_error_ids"]
    source_error_set = set(source_error_ids)
    clarifications = _object(
        payload["source_note_clarifications"], "delta.source_note_clarifications"
    )
    for question_id, clarification in clarifications.items():
        if question_id not in source_error_set:
            raise IntegratedBaselineValidationError(
                f"delta clarification has unknown source error ID: {question_id}"
            )
        _string(
            clarification,
            f"delta.source_note_clarifications.{question_id}",
        )
    remaining_source_errors = [
        question_id
        for question_id in source_error_ids
        if question_id not in clarifications
    ]
    if payload["remaining_source_note_errors"] != remaining_source_errors:
        raise IntegratedBaselineValidationError(
            "delta.remaining_source_note_errors does not match clarifications"
        )

    return {
        "answers": answers,
        "active": active,
        "notes": notes,
        "unresolved_required": unresolved,
        "note_errors": note_errors,
        "consistency_errors": consistency_errors,
        "clarifications": clarifications,
        "remaining_source_note_errors": remaining_source_errors,
    }


def validate_integrated_analysis(
    payload: dict[str, Any],
    questionnaire: dict[str, Any],
    followups: dict[str, Any],
    integrated_questions: dict[str, Any],
) -> None:
    _exact_fields(payload, INTEGRATED_ANALYSIS_FIELDS, "integrated analysis")
    if payload["schema_version"] != "walksafe.integrated-baseline-analysis.v1":
        raise IntegratedBaselineValidationError(
            "integrated analysis.schema_version is unsupported"
        )
    for field in ("title", "as_of"):
        _string(payload[field], f"integrated analysis.{field}")

    merge_policy = _object(payload["merge_policy"], "integrated analysis.merge_policy")
    _exact_fields(merge_policy, MERGE_POLICY_FIELDS, "integrated analysis.merge_policy")
    for field in sorted(MERGE_POLICY_FIELDS):
        _string_list(
            merge_policy[field],
            f"integrated analysis.merge_policy.{field}",
            allow_empty=False,
        )
    if len({len(merge_policy[field]) for field in MERGE_POLICY_FIELDS}) != 1:
        raise IntegratedBaselineValidationError(
            "integrated analysis.merge_policy arrays must have equal length"
        )

    source_materials = payload["source_materials"]
    if not isinstance(source_materials, list) or not source_materials:
        raise IntegratedBaselineValidationError(
            "integrated analysis.source_materials must be a non-empty array"
        )
    integrated_question_ids = {
        question["id"] for question in integrated_questions["questions"]
    }
    source_material_ids: set[str] = set()
    for index, raw in enumerate(source_materials):
        item = _object(raw, f"integrated analysis.source_materials[{index}]")
        _exact_fields(
            item,
            SOURCE_MATERIAL_FIELDS,
            f"integrated analysis.source_materials[{index}]",
        )
        item_id = _identifier(item["id"], f"integrated analysis.source_materials[{index}].id")
        if item_id in source_material_ids:
            raise IntegratedBaselineValidationError(
                f"duplicate integrated source material ID: {item_id}"
            )
        source_material_ids.add(item_id)
        for field in ("type", "path", "summary"):
            _string(item[field], f"integrated analysis.source_materials[{index}].{field}")
        _sha256(item["sha256"], f"integrated analysis.source_materials[{index}].sha256")
        source_path = _resolved_source_material_path(item_id, item["path"])
        if source_path.exists():
            if not source_path.is_file():
                raise IntegratedBaselineValidationError(
                    f"integrated source material is not a file: {source_path}"
                )
            actual_sha256 = file_sha256(source_path)
            if item["sha256"] != actual_sha256:
                raise IntegratedBaselineValidationError(
                    f"integrated source material SHA-256 mismatch: {item_id}"
                )
        elif item_id in REQUIRED_SOURCE_MATERIAL_IDS:
            raise IntegratedBaselineValidationError(
                f"required integrated source material is missing: {source_path}"
            )
    missing_source_material_ids = sorted(
        REQUIRED_SOURCE_MATERIAL_IDS - source_material_ids
    )
    if missing_source_material_ids:
        raise IntegratedBaselineValidationError(
            "integrated analysis is missing required source material IDs: "
            f"{missing_source_material_ids}"
        )

    original_ids = {question["id"] for question in questionnaire["questions"]}
    followup_ids = {question["id"] for question in followups["questions"]}
    supersedable_ids = original_ids | followup_ids | {
        rule["id"] for rule in followups["consistency_rules"]
    }
    directives = payload["directives"]
    if not isinstance(directives, list) or not directives:
        raise IntegratedBaselineValidationError(
            "integrated analysis.directives must be a non-empty array"
        )
    directive_ids: set[str] = set()
    for index, raw in enumerate(directives):
        directive = _object(raw, f"integrated analysis.directives[{index}]")
        _exact_fields(directive, DIRECTIVE_FIELDS, f"integrated analysis.directives[{index}]")
        directive_id = _identifier(
            directive["id"], f"integrated analysis.directives[{index}].id"
        )
        if directive_id in directive_ids:
            raise IntegratedBaselineValidationError(
                f"duplicate integrated directive ID: {directive_id}"
            )
        directive_ids.add(directive_id)
        for field in ("area", "title", "user_statement", "normalized_definition"):
            _string(directive[field], f"integrated analysis.directives[{index}].{field}")
        _enum(
            directive["status"],
            DIRECTIVE_STATUSES,
            f"integrated analysis.directives[{index}].status",
        )
        _string_list(
            directive["supersedes"],
            f"integrated analysis.directives[{index}].supersedes",
            allowed=supersedable_ids,
        )
        _string_list(
            directive["implementation_meaning"],
            f"integrated analysis.directives[{index}].implementation_meaning",
        )
        _open_topic_list(
            directive["open_question_topics"],
            f"integrated analysis.directives[{index}].open_question_topics",
            integrated_question_ids=integrated_question_ids,
        )

    original_category_ids = {category["id"] for category in questionnaire["categories"]}
    followup_group_ids = {group["id"] for group in followups["groups"]}
    domain_reviews = payload["domain_reviews"]
    if not isinstance(domain_reviews, list) or len(domain_reviews) != len(original_category_ids):
        raise IntegratedBaselineValidationError(
            "integrated analysis must have one domain review per original category"
        )
    domain_ids: set[str] = set()
    covered_categories: set[str] = set()
    covered_followup_groups: set[str] = set()
    for index, raw in enumerate(domain_reviews, 1):
        review = _object(raw, f"integrated analysis.domain_reviews[{index - 1}]")
        _exact_fields(
            review,
            DOMAIN_REVIEW_FIELDS,
            f"integrated analysis.domain_reviews[{index - 1}]",
        )
        review_id = _identifier(
            review["id"], f"integrated analysis.domain_reviews[{index - 1}].id"
        )
        review_order = _positive_int(
            review["order"], f"integrated analysis.domain_reviews[{index - 1}].order"
        )
        if review_id in domain_ids or review_order != index:
            raise IntegratedBaselineValidationError(
                "integrated domain review IDs must be unique and order contiguous from 1"
            )
        domain_ids.add(review_id)
        for field in ("title", "one_line"):
            _string(review[field], f"integrated analysis.domain_reviews[{index - 1}].{field}")
        for field in ("confirmed", "implementation", "conflicts"):
            _string_list(
                review[field],
                f"integrated analysis.domain_reviews[{index - 1}].{field}",
            )
        _open_topic_list(
            review["open_topics"],
            f"integrated analysis.domain_reviews[{index - 1}].open_topics",
            integrated_question_ids=integrated_question_ids,
        )
        category_refs = _string_list(
            review["original_category_ids"],
            f"integrated analysis.domain_reviews[{index - 1}].original_category_ids",
            allowed=original_category_ids,
            allow_empty=False,
        )
        followup_refs = _string_list(
            review["followup_group_ids"],
            f"integrated analysis.domain_reviews[{index - 1}].followup_group_ids",
            allowed=followup_group_ids,
        )
        covered_categories.update(category_refs)
        covered_followup_groups.update(followup_refs)
    if covered_categories != original_category_ids:
        raise IntegratedBaselineValidationError(
            "integrated domain reviews do not cover every original category"
        )
    category_ref_count = sum(
        len(review["original_category_ids"]) for review in domain_reviews
    )
    if category_ref_count != len(original_category_ids):
        raise IntegratedBaselineValidationError(
            "each original category must belong to exactly one integrated domain review"
        )
    if covered_followup_groups != followup_group_ids:
        raise IntegratedBaselineValidationError(
            "integrated domain reviews do not cover every followup group"
        )

    runtime_evidence = payload["runtime_evidence"]
    if not isinstance(runtime_evidence, list) or not runtime_evidence:
        raise IntegratedBaselineValidationError(
            "integrated analysis.runtime_evidence must be a non-empty array"
        )
    runtime_ids: set[str] = set()
    for index, raw in enumerate(runtime_evidence):
        item = _object(raw, f"integrated analysis.runtime_evidence[{index}]")
        _exact_fields(
            item,
            RUNTIME_EVIDENCE_FIELDS,
            f"integrated analysis.runtime_evidence[{index}]",
        )
        item_id = _identifier(item["id"], f"integrated analysis.runtime_evidence[{index}].id")
        if item_id in runtime_ids:
            raise IntegratedBaselineValidationError(f"duplicate runtime evidence ID: {item_id}")
        runtime_ids.add(item_id)
        for field in ("area", "title", "summary", "decision"):
            _string(item[field], f"integrated analysis.runtime_evidence[{index}].{field}")
        _enum(
            item["status"],
            RUNTIME_STATUSES,
            f"integrated analysis.runtime_evidence[{index}].status",
        )
        _string_list(
            item["facts"],
            f"integrated analysis.runtime_evidence[{index}].facts",
            allow_empty=False,
        )
        evidence = item["evidence"]
        if not isinstance(evidence, list) or not evidence:
            raise IntegratedBaselineValidationError(
                f"integrated analysis.runtime_evidence[{index}].evidence must not be empty"
            )
        for evidence_index, raw_evidence in enumerate(evidence):
            reference = _object(
                raw_evidence,
                f"integrated analysis.runtime_evidence[{index}].evidence[{evidence_index}]",
            )
            _exact_fields(
                reference,
                EVIDENCE_ITEM_FIELDS,
                f"integrated analysis.runtime_evidence[{index}].evidence[{evidence_index}]",
            )
            for field in sorted(EVIDENCE_ITEM_FIELDS):
                _string(
                    reference[field],
                    f"integrated analysis.runtime_evidence[{index}].evidence[{evidence_index}].{field}",
                )

    constraints = payload["external_constraints"]
    if not isinstance(constraints, list) or not constraints:
        raise IntegratedBaselineValidationError(
            "integrated analysis.external_constraints must be a non-empty array"
        )
    constraint_ids: set[str] = set()
    for index, raw in enumerate(constraints):
        item = _object(raw, f"integrated analysis.external_constraints[{index}]")
        _exact_fields(
            item,
            EXTERNAL_CONSTRAINT_FIELDS,
            f"integrated analysis.external_constraints[{index}]",
        )
        item_id = _identifier(
            item["id"], f"integrated analysis.external_constraints[{index}].id"
        )
        if item_id in constraint_ids:
            raise IntegratedBaselineValidationError(
                f"duplicate external constraint ID: {item_id}"
            )
        constraint_ids.add(item_id)
        for field in ("area", "title", "summary", "consequence"):
            _string(item[field], f"integrated analysis.external_constraints[{index}].{field}")
        source_url = _string(
            item["source_url"],
            f"integrated analysis.external_constraints[{index}].source_url",
        )
        if not _is_official_https_url(source_url):
            raise IntegratedBaselineValidationError(
                f"external constraint source must be an official HTTPS URL: {source_url}"
            )
        status = _string(
            item["status"], f"integrated analysis.external_constraints[{index}].status"
        )
        if status != "RELEASE_REVIEW_GATE":
            raise IntegratedBaselineValidationError(
                "external constraint status must be RELEASE_REVIEW_GATE"
            )

    findings = payload["findings"]
    if not isinstance(findings, list) or not findings:
        raise IntegratedBaselineValidationError(
            "integrated analysis.findings must be a non-empty array"
        )
    finding_ids: set[str] = set()
    followup_consistency_ids = {
        rule["id"] for rule in followups["consistency_rules"]
    }
    evidence_reference_ids = (
        source_material_ids
        | directive_ids
        | runtime_ids
        | constraint_ids
        | original_ids
        | followup_ids
        | followup_consistency_ids
    )
    for index, raw in enumerate(findings):
        finding = _object(raw, f"integrated analysis.findings[{index}]")
        _exact_fields(finding, FINDING_FIELDS, f"integrated analysis.findings[{index}]")
        finding_id = _identifier(finding["id"], f"integrated analysis.findings[{index}].id")
        if finding_id in finding_ids:
            raise IntegratedBaselineValidationError(f"duplicate integrated finding ID: {finding_id}")
        finding_ids.add(finding_id)
        _enum(
            finding["severity"],
            FINDING_SEVERITIES,
            f"integrated analysis.findings[{index}].severity",
        )
        for field in ("title", "plain_summary", "impact", "recommendation"):
            _string(finding[field], f"integrated analysis.findings[{index}].{field}")
        _string_list(
            finding["evidence_refs"],
            f"integrated analysis.findings[{index}].evidence_refs",
            allowed=evidence_reference_ids,
            allow_empty=False,
        )
        _open_topic_list(
            finding["new_question_topics"],
            f"integrated analysis.findings[{index}].new_question_topics",
            integrated_question_ids=integrated_question_ids,
        )
    analysis_id_groups = (
        source_material_ids,
        directive_ids,
        domain_ids,
        runtime_ids,
        constraint_ids,
        finding_ids,
    )
    if sum(len(ids) for ids in analysis_id_groups) != len(set().union(*analysis_id_groups)):
        raise IntegratedBaselineValidationError(
            "integrated analysis IDs must be unique across all entity types"
        )
    source_evidence_ids = original_ids | followup_ids | followup_consistency_ids
    ambiguous_ids = sorted(set().union(*analysis_id_groups) & source_evidence_ids)
    if ambiguous_ids:
        raise IntegratedBaselineValidationError(
            f"integrated analysis IDs collide with source evidence IDs: {ambiguous_ids}"
        )


def _finite_number(value: Any, label: str) -> float | int:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise IntegratedBaselineValidationError(f"{label} must be a number")
    if isinstance(value, float) and not math.isfinite(value):
        raise IntegratedBaselineValidationError(f"{label} must be finite")
    return value


def validate_integrated_questions(
    payload: dict[str, Any],
    questionnaire: dict[str, Any],
    followups: dict[str, Any],
) -> None:
    _exact_fields(payload, INTEGRATED_QUESTION_FIELDS, "integrated questions")
    metadata = _object(payload["metadata"], "integrated questions.metadata")
    _exact_fields(metadata, QUESTION_METADATA_FIELDS, "integrated questions.metadata")
    expected_metadata = {
        "schema_version": "walksafe.integrated-baseline-questionnaire.v1",
        "question_set_id": "walksafe-integrated-baseline-20260718",
        "version": "1.0.0",
    }
    for field, expected in expected_metadata.items():
        if metadata[field] != expected:
            raise IntegratedBaselineValidationError(
                f"integrated questions.metadata.{field} must be {expected!r}"
            )
    for field in ("title", "created_at", "purpose", "completion_rule"):
        _string(metadata[field], f"integrated questions.metadata.{field}")

    groups = payload["groups"]
    if not isinstance(groups, list) or not groups:
        raise IntegratedBaselineValidationError(
            "integrated questions.groups must be a non-empty array"
        )
    group_ids: set[str] = set()
    for index, raw in enumerate(groups, 1):
        group = _object(raw, f"integrated questions.groups[{index - 1}]")
        _exact_fields(group, GROUP_FIELDS, f"integrated questions.groups[{index - 1}]")
        expected_id = f"IBG-{index:02d}"
        group_order = _positive_int(
            group["order"], f"integrated questions.groups[{index - 1}].order"
        )
        if group["id"] != expected_id or group_order != index:
            raise IntegratedBaselineValidationError(
                "integrated group IDs/order must be contiguous IBG-01..."
            )
        group_ids.add(expected_id)
        for field in ("title", "description"):
            _string(group[field], f"integrated questions.groups[{index - 1}].{field}")

    questions = payload["questions"]
    if not isinstance(questions, list) or not questions:
        raise IntegratedBaselineValidationError(
            "integrated questions.questions must be a non-empty array"
        )
    source_ids = {
        question["id"] for question in questionnaire["questions"]
    } | {question["id"] for question in followups["questions"]} | {
        rule["id"] for rule in followups["consistency_rules"]
    }
    artifact_display_ids = _artifact_display_ids()
    by_id: dict[str, dict[str, Any]] = {}
    option_ids_by_question: dict[str, set[str]] = {}
    seen_user_refs: set[str] = set()
    group_orders: dict[str, int] = {group_id: 0 for group_id in group_ids}
    for index, raw in enumerate(questions, 1):
        question = _object(raw, f"integrated questions.questions[{index - 1}]")
        _exact_fields(
            question,
            QUESTION_FIELDS,
            f"integrated questions.questions[{index - 1}]",
        )
        expected_id = f"IBQ-{index:03d}"
        if question["id"] != expected_id:
            raise IntegratedBaselineValidationError(
                "integrated question IDs must be contiguous IBQ-001..."
            )
        group_id = _identifier(question["group_id"], f"{expected_id}.group_id")
        if group_id not in group_ids:
            raise IntegratedBaselineValidationError(f"{expected_id} has unknown group_id")
        group_orders[group_id] += 1
        question_order = _positive_int(question["order"], f"{expected_id}.order")
        if question_order != group_orders[group_id]:
            raise IntegratedBaselineValidationError(
                f"{expected_id}.order must be contiguous within its group"
            )
        _enum(question["priority"], QUESTION_PRIORITIES, f"{expected_id}.priority")
        answer_type = _enum(
            question["answer_type"], ANSWER_TYPES, f"{expected_id}.answer_type"
        )
        if not isinstance(question["required"], bool):
            raise IntegratedBaselineValidationError(f"{expected_id}.required must be boolean")
        for field in (
            "feature",
            "prompt",
            "explanation",
            "why_needed",
            "recommendation_reason",
        ):
            _string(question[field], f"{expected_id}.{field}")
        _string(question["detail_prompt"], f"{expected_id}.detail_prompt", allow_empty=True)

        options = question["options"]
        if not isinstance(options, list):
            raise IntegratedBaselineValidationError(f"{expected_id}.options must be an array")
        option_ids: set[str] = set()
        for option_index, raw_option in enumerate(options):
            option = _object(raw_option, f"{expected_id}.options[{option_index}]")
            _exact_fields(option, OPTION_FIELDS, f"{expected_id}.options[{option_index}]")
            option_id = _identifier(option["id"], f"{expected_id}.options[{option_index}].id")
            if option_id in option_ids:
                raise IntegratedBaselineValidationError(f"{expected_id} has duplicate option IDs")
            option_ids.add(option_id)
            for field in ("label", "meaning", "impact"):
                _string(option[field], f"{expected_id}.options[{option_index}].{field}")
        if answer_type in {"single_choice", "multi_choice"}:
            if len(options) < 2:
                raise IntegratedBaselineValidationError(
                    f"{expected_id} choice question needs at least two options"
                )
        elif options:
            raise IntegratedBaselineValidationError(
                f"{expected_id} non-choice question must not define options"
            )

        recommendations = _string_list(
            question["recommended_option_ids"],
            f"{expected_id}.recommended_option_ids",
            allowed=option_ids,
            allow_empty=answer_type not in {"single_choice", "multi_choice"},
        )
        if answer_type == "single_choice" and len(recommendations) != 1:
            raise IntegratedBaselineValidationError(
                f"{expected_id} single_choice needs exactly one recommendation"
            )
        detail_options = _string_list(
            question["detail_required_option_ids"],
            f"{expected_id}.detail_required_option_ids",
            allowed=option_ids,
        )
        if detail_options and not question["detail_prompt"].strip():
            raise IntegratedBaselineValidationError(
                f"{expected_id} detail options require a detail_prompt"
            )
        refs = _string_list(
            question["source_refs"],
            f"{expected_id}.source_refs",
            allow_empty=False,
        )
        unknown_refs = sorted(
            ref
            for ref in refs
            if ref not in source_ids and ref not in USER_DIRECTIVE_REFS
        )
        if unknown_refs:
            raise IntegratedBaselineValidationError(
                f"{expected_id}.source_refs contains unknown IDs: {unknown_refs}"
            )
        seen_user_refs.update(set(refs) & USER_DIRECTIVE_REFS)
        affects = _string_list(
            question["affects"], f"{expected_id}.affects", allow_empty=False
        )
        invalid_affects = sorted(
            item
            for item in affects
            if DELIVERABLE_ID_PATTERN.fullmatch(item) is None
            or item not in artifact_display_ids
        )
        if invalid_affects:
            raise IntegratedBaselineValidationError(
                f"{expected_id}.affects contains IDs absent from the 257-item artifact catalog: "
                f"{invalid_affects}"
            )

        validation = question["validation"]
        if validation is not None:
            validation = _object(validation, f"{expected_id}.validation")
            if answer_type == "number":
                _exact_fields(validation, {"min", "max"}, f"{expected_id}.validation")
                minimum = _finite_number(validation["min"], f"{expected_id}.validation.min")
                maximum = _finite_number(validation["max"], f"{expected_id}.validation.max")
            elif answer_type == "text":
                _exact_fields(
                    validation,
                    {"min_length", "max_length"},
                    f"{expected_id}.validation",
                )
                minimum = _finite_number(
                    validation["min_length"], f"{expected_id}.validation.min_length"
                )
                maximum = _finite_number(
                    validation["max_length"], f"{expected_id}.validation.max_length"
                )
                if not isinstance(minimum, int) or not isinstance(maximum, int):
                    raise IntegratedBaselineValidationError(
                        f"{expected_id} text lengths must be integers"
                    )
            else:
                raise IntegratedBaselineValidationError(
                    f"{expected_id} choice question must use validation=null"
                )
            if minimum < 0 or minimum > maximum:
                raise IntegratedBaselineValidationError(
                    f"{expected_id}.validation range is invalid"
                )
        by_id[expected_id] = question
        option_ids_by_question[expected_id] = option_ids

    if any(order == 0 for order in group_orders.values()):
        raise IntegratedBaselineValidationError(
            "every integrated question group must contain at least one question"
        )
    if seen_user_refs != USER_DIRECTIVE_REFS:
        missing = sorted(USER_DIRECTIVE_REFS - seen_user_refs)
        raise IntegratedBaselineValidationError(
            f"integrated questions do not cover every user directive reference: {missing}"
        )
    positions = {question_id: index for index, question_id in enumerate(by_id)}
    for question_id, question in by_id.items():
        activation = question["activation"]
        if activation is None:
            continue
        activation = _object(activation, f"{question_id}.activation")
        _exact_fields(activation, ACTIVATION_FIELDS, f"{question_id}.activation")
        source_id = _identifier(
            activation["question_id"], f"{question_id}.activation.question_id"
        )
        if source_id not in by_id or positions[source_id] >= positions[question_id]:
            raise IntegratedBaselineValidationError(
                f"{question_id}.activation must reference an earlier question"
            )
        _string_list(
            activation["option_ids"],
            f"{question_id}.activation.option_ids",
            allowed=option_ids_by_question[source_id],
            allow_empty=False,
        )

    rules = payload["consistency_rules"]
    if not isinstance(rules, list):
        raise IntegratedBaselineValidationError(
            "integrated questions.consistency_rules must be an array"
        )
    for index, raw in enumerate(rules, 1):
        rule = _object(raw, f"integrated questions.consistency_rules[{index - 1}]")
        _exact_fields(
            rule,
            CONSISTENCY_RULE_FIELDS,
            f"integrated questions.consistency_rules[{index - 1}]",
        )
        if rule["id"] != f"IBCR-{index:03d}":
            raise IntegratedBaselineValidationError(
                "integrated consistency rule IDs must be contiguous IBCR-001..."
            )
        _enum(
            rule["severity"],
            {"error", "warning"},
            f"integrated consistency rule {rule['id']}.severity",
        )
        for field in ("message", "resolution"):
            _string(rule[field], f"integrated consistency rule {rule['id']}.{field}")
        conditions = rule["when_all"]
        if not isinstance(conditions, list) or not conditions:
            raise IntegratedBaselineValidationError(
                f"integrated consistency rule {rule['id']} needs at least one condition"
            )
        seen_questions: set[str] = set()
        for condition_index, raw_condition in enumerate(conditions):
            condition = _object(
                raw_condition,
                f"integrated consistency rule {rule['id']}.when_all[{condition_index}]",
            )
            _exact_fields(
                condition,
                CONSISTENCY_CONDITION_FIELDS,
                f"integrated consistency rule {rule['id']}.when_all[{condition_index}]",
            )
            question_id = _identifier(
                condition["question_id"],
                f"integrated consistency rule {rule['id']}.question_id",
            )
            if question_id not in by_id or question_id in seen_questions:
                raise IntegratedBaselineValidationError(
                    f"integrated consistency rule {rule['id']} has invalid question reference"
                )
            seen_questions.add(question_id)
            _string_list(
                condition["option_ids"],
                f"integrated consistency rule {rule['id']}.{question_id}.option_ids",
                allowed=option_ids_by_question[question_id],
                allow_empty=False,
            )

    _string_list(
        payload["coverage_checklist"],
        "integrated questions.coverage_checklist",
        allow_empty=False,
    )


def _build_delta_decisions(
    review_data: dict[str, Any],
    delta: dict[str, Any],
    delta_state: dict[str, Any],
) -> list[dict[str, Any]]:
    errors_by_question: dict[str, list[dict[str, Any]]] = {}
    for rule in delta_state["consistency_errors"]:
        for condition in rule["when_all"]:
            errors_by_question.setdefault(condition["question_id"], []).append(rule)
    note_error_ids = set(delta_state["note_errors"])
    decisions = []
    for question in review_data["followups"]:
        question_id = question["id"]
        answer_record = delta_state["answers"].get(question_id)
        answer = answer_record["answer"] if answer_record else None
        selected_options = [
            {
                **option,
                "selected": option["id"] == answer,
                "recommended": option["id"] in question["recommended_option_ids"],
            }
            for option in question["options"]
            if option["id"] == answer
        ]
        consistency_errors = errors_by_question.get(question_id, [])
        decisions.append(
            {
                **question,
                "source_question": question,
                "source_answer": delta["followup_answers"].get(question_id),
                "source_note_raw": delta["followup_notes"].get(question_id, ""),
                "answer": answer,
                "selected_options": selected_options,
                "note": delta_state["notes"].get(question_id, ""),
                "active": delta_state["active"][question_id],
                "flags": {
                    "answered": answer_record is not None,
                    "note_error": question_id in note_error_ids,
                    "consistency_error_ids": [
                        rule["id"] for rule in consistency_errors
                    ],
                },
                "consistency_errors": consistency_errors,
            }
        )
    return decisions


def _build_original_decisions(
    questionnaire: dict[str, Any],
    source_answers: dict[str, Any],
    review_data: dict[str, Any],
    delta_state: dict[str, Any],
) -> list[dict[str, Any]]:
    remaining_error_ids = set(delta_state["remaining_source_note_errors"])
    decisions_by_id = {decision["id"]: decision for decision in review_data["decisions"]}
    decisions = []
    for question in questionnaire["questions"]:
        question_id = question["id"]
        decision = decisions_by_id[question_id]
        clarification = delta_state["clarifications"].get(question_id, "")
        decisions.append(
            {
                **decision,
                "source_question": question,
                "source_answer": source_answers["answers"][question_id],
                "source_note_raw": source_answers["notes"].get(question_id, ""),
                "delta_clarification": clarification,
                "effective_note": clarification or decision["source_note"],
                "flags": {
                    **decision["flags"],
                    "clarified_by_delta": bool(clarification),
                    "remaining_source_note_error": question_id in remaining_error_ids,
                },
            }
        )
    return decisions


def _validate_legacy_traceability(
    integrated_analysis: dict[str, Any],
    integrated_questions: dict[str, Any],
    delta_state: dict[str, Any],
) -> dict[str, Any]:
    question_refs = {
        source_ref
        for question in integrated_questions["questions"]
        for source_ref in question["source_refs"]
    }
    confirmed_supersedes = {
        source_id
        for directive in integrated_analysis["directives"]
        if directive["status"] == "CONFIRMED"
        for source_id in directive["supersedes"]
    }
    traced_ids = question_refs | confirmed_supersedes
    target_groups = {
        "remaining_original_note_errors": delta_state["remaining_source_note_errors"],
        "delta_note_errors": delta_state["note_errors"],
        "active_delta_consistency_errors": [
            rule["id"] for rule in delta_state["consistency_errors"]
        ],
    }
    missing_by_group = {
        group: [source_id for source_id in source_ids if source_id not in traced_ids]
        for group, source_ids in target_groups.items()
    }
    missing_ids = [
        source_id
        for source_ids in missing_by_group.values()
        for source_id in source_ids
    ]
    if missing_ids:
        raise IntegratedBaselineValidationError(
            "legacy ambiguity traceability is incomplete; every remaining original "
            "note error, delta note error, and active delta consistency error must be "
            f"referenced by a new question or CONFIRMED directive: {missing_by_group}"
        )
    target_ids = {
        source_id for source_ids in target_groups.values() for source_id in source_ids
    }
    return {
        "target_total": len(target_ids),
        "traced_total": len(target_ids & traced_ids),
        "untraced_total": 0,
        "target_groups": {
            group: {"target_count": len(source_ids), "untraced_ids": []}
            for group, source_ids in target_groups.items()
        },
        "trace_sources": {
            "new_question_source_refs": sorted(target_ids & question_refs),
            "confirmed_directive_supersedes": sorted(
                target_ids & confirmed_supersedes
            ),
        },
    }


def build_integrated_report(
    questionnaire: dict[str, Any],
    source_answers: dict[str, Any],
    source_analysis: dict[str, Any],
    followups: dict[str, Any],
    delta: dict[str, Any],
    integrated_analysis: dict[str, Any],
    integrated_questions: dict[str, Any],
    *,
    source_answer_sha256: str,
    delta_sha256: str,
) -> dict[str, Any]:
    base_report = answer_review.build_review_data(
        questionnaire,
        source_answers,
        source_analysis,
        followups,
        source_answer_sha256=source_answer_sha256,
    )
    delta_state = validate_delta(delta, base_report)
    validate_integrated_questions(integrated_questions, questionnaire, followups)
    validate_integrated_analysis(
        integrated_analysis,
        questionnaire,
        followups,
        integrated_questions,
    )
    legacy_traceability = _validate_legacy_traceability(
        integrated_analysis,
        integrated_questions,
        delta_state,
    )
    _sha256(delta_sha256, "delta_sha256")

    binding = {
        "source_answer_sha256": source_answer_sha256,
        "source_question_set_hash": base_report["binding"]["question_set_hash"],
        "delta_sha256": delta_sha256,
        "analysis_sha256": object_sha256(integrated_analysis),
        "integrated_questions_sha256": object_sha256(integrated_questions),
    }
    active_ids = {
        question_id for question_id, is_active in delta_state["active"].items() if is_active
    }
    active_answered = active_ids & set(delta_state["answers"])
    merge_policy = [
        {"rank": rank, "source": source, "rule": rule}
        for rank, source, rule in zip(
            integrated_analysis["merge_policy"]["rank"],
            integrated_analysis["merge_policy"]["source"],
            integrated_analysis["merge_policy"]["rule"],
            strict=True,
        )
    ]
    return {
        "report_schema_version": "walksafe.integrated-baseline-report.v1",
        "title": integrated_analysis["title"],
        "as_of": integrated_analysis["as_of"],
        "merge_policy": merge_policy,
        "source_materials": _report_source_materials(
            integrated_analysis["source_materials"]
        ),
        "source_exports": {
            "original": source_answers,
            "delta": delta,
        },
        "binding": {**binding, "storage_binding_hash": object_sha256(binding)},
        "questionnaire_metadata": integrated_questions["metadata"],
        "source_summary": {
            "original_answered": len(base_report["decisions"]),
            "original_total": base_report["source"]["required_count"],
            "original_note_errors": len(base_report["source"]["source_note_error_ids"]),
            "delta_active_answered": len(active_answered),
            "delta_active_total": len(active_ids),
            "delta_note_errors": len(delta_state["note_errors"]),
            "delta_remaining_source_note_errors": len(
                delta_state["remaining_source_note_errors"]
            ),
            "delta_consistency_errors": len(delta_state["consistency_errors"]),
            "delta_inactive_count": len(delta_state["active"]) - len(active_ids),
            "legacy_ambiguity_target_count": legacy_traceability["target_total"],
            "legacy_ambiguity_untraced_count": legacy_traceability["untraced_total"],
        },
        "legacy_traceability": legacy_traceability,
        "directives": integrated_analysis["directives"],
        "domain_reviews": integrated_analysis["domain_reviews"],
        "findings": integrated_analysis["findings"],
        "runtime_evidence": integrated_analysis["runtime_evidence"],
        "external_constraints": integrated_analysis["external_constraints"],
        "original_categories": base_report["categories"],
        "original_decisions": _build_original_decisions(
            questionnaire,
            source_answers,
            base_report,
            delta_state,
        ),
        "delta_groups": base_report["followup_groups"],
        "delta_decisions": _build_delta_decisions(base_report, delta, delta_state),
        "new_groups": integrated_questions["groups"],
        "new_questions": integrated_questions["questions"],
        "new_consistency_rules": integrated_questions["consistency_rules"],
        "coverage_checklist": integrated_questions["coverage_checklist"],
    }


def _canonical_json(value: Any) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def _script_safe_json(value: Any) -> str:
    return (
        _canonical_json(value)
        .replace("&", "\\u0026")
        .replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")
    )


def render_integrated_baseline(report: dict[str, Any], template: str) -> str:
    placeholder_count = template.count(DATA_PLACEHOLDER)
    if placeholder_count != 1:
        raise IntegratedBaselineValidationError(
            f"template must contain {DATA_PLACEHOLDER!r} exactly once; found {placeholder_count}"
        )
    rendered = template.replace(DATA_PLACEHOLDER, _script_safe_json(report))
    if not rendered.endswith("\n"):
        rendered += "\n"
    return rendered


def build_from_paths(
    questions_path: Path,
    answers_path: Path,
    analysis_path: Path,
    followups_path: Path,
    delta_path: Path,
    integrated_analysis_path: Path,
    integrated_questions_path: Path,
    template_path: Path,
) -> tuple[str, dict[str, Any]]:
    questionnaire = load_strict_json(questions_path)
    source_answers = load_strict_json(answers_path)
    source_analysis = load_strict_json(analysis_path)
    followups = load_strict_json(followups_path)
    delta = load_strict_json(delta_path)
    integrated_analysis = load_strict_json(integrated_analysis_path)
    integrated_questions = load_strict_json(integrated_questions_path)
    try:
        template = template_path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        raise IntegratedBaselineValidationError(
            f"cannot read template {template_path}: {exc}"
        ) from exc
    report = build_integrated_report(
        questionnaire,
        source_answers,
        source_analysis,
        followups,
        delta,
        integrated_analysis,
        integrated_questions,
        source_answer_sha256=file_sha256(answers_path),
        delta_sha256=file_sha256(delta_path),
    )
    return render_integrated_baseline(report, template), report


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Build the standalone WalkSafe integrated baseline questionnaire"
    )
    parser.add_argument("--questions", type=Path, default=DEFAULT_QUESTIONS_PATH)
    parser.add_argument("--answers", type=Path, default=DEFAULT_ANSWERS_PATH)
    parser.add_argument("--analysis", type=Path, default=DEFAULT_ANALYSIS_PATH)
    parser.add_argument("--followups", type=Path, default=DEFAULT_FOLLOWUPS_PATH)
    parser.add_argument("--delta", type=Path, default=DEFAULT_DELTA_PATH)
    parser.add_argument(
        "--integrated-analysis", type=Path, default=DEFAULT_INTEGRATED_ANALYSIS_PATH
    )
    parser.add_argument(
        "--integrated-questions", type=Path, default=DEFAULT_INTEGRATED_QUESTIONS_PATH
    )
    parser.add_argument("--template", type=Path, default=DEFAULT_TEMPLATE_PATH)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT_PATH)
    parser.add_argument(
        "--check",
        action="store_true",
        help="validate all inputs and fail unless output matches the deterministic render",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        rendered, report = build_from_paths(
            args.questions,
            args.answers,
            args.analysis,
            args.followups,
            args.delta,
            args.integrated_analysis,
            args.integrated_questions,
            args.template,
        )
    except (
        OSError,
        UnicodeError,
        answer_review.AnswerReviewValidationError,
        answer_review.question_builder.QuestionnaireValidationError,
    ) as exc:
        print(f"integrated baseline build failed: {exc}", file=sys.stderr)
        return 2
    if args.check:
        try:
            current = args.output.read_bytes()
        except OSError:
            current = None
        if current != rendered.encode("utf-8"):
            print(
                f"integrated baseline output is stale: {args.output}; "
                "run python scripts/build_walksafe_integrated_baseline.py",
                file=sys.stderr,
            )
            return 1
        print(f"Integrated baseline is current: {args.output}")
        return 0
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(rendered, encoding="utf-8", newline="\n")
    summary = report["source_summary"]
    print(
        f"Wrote {args.output}: original {summary['original_answered']}/"
        f"{summary['original_total']}, delta {summary['delta_active_answered']}/"
        f"{summary['delta_active_total']}, new questions {len(report['new_questions'])}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

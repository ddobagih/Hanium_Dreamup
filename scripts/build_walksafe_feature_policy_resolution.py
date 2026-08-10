#!/usr/bin/env python3
"""Validate and resolve the comprehensive WalkSafe policy review into an authoring input."""

from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import sys
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
INTERVIEW_DIR = REPO_ROOT / "docs" / "control" / "decision-interview"
CONTROLLED_ANSWERS_PATH = (
    INTERVIEW_DIR
    / "source-records"
    / "walksafe-feature-policy-comprehensive-review-20260719-answers.json"
)
INTAKE_PATH = (
    INTERVIEW_DIR
    / "source-records"
    / "walksafe-feature-policy-comprehensive-review-20260719-answers.intake.json"
)
PROPOSALS_PATH = INTERVIEW_DIR / "walksafe-feature-policy-comprehensive-proposals.json"
BASE_FEATURE_POLICY_PATH = INTERVIEW_DIR / "walksafe-feature-policy-effective-candidate.json"
BASE_REGISTER_PATH = INTERVIEW_DIR / "walksafe-effective-decision-register.json"
RULES_PATH = INTERVIEW_DIR / "walksafe-feature-policy-review-resolution-rules.json"
OUTPUT_PATH = INTERVIEW_DIR / "walksafe-feature-policy-review-resolution.json"

EXPECTED_SOURCE_PATHS = {
    "SRC-CONTROLLED-COMPREHENSIVE-ANSWERS": CONTROLLED_ANSWERS_PATH,
    "SRC-COMPREHENSIVE-ANSWER-INTAKE": INTAKE_PATH,
    "SRC-COMPREHENSIVE-PROPOSALS": PROPOSALS_PATH,
    "SRC-BASE-EFFECTIVE-FEATURE-POLICY": BASE_FEATURE_POLICY_PATH,
    "SRC-BASE-EFFECTIVE-DECISION-REGISTER": BASE_REGISTER_PATH,
}
EXPECTED_INTAKE_REVIEW_SOURCE_PATHS = {
    "SRC-COMPREHENSIVE-PROPOSALS": PROPOSALS_PATH,
    "SRC-COMPREHENSIVE-REVIEW-HTML": (
        INTERVIEW_DIR / "walksafe-feature-policy-comprehensive-review-20260719.html"
    ),
}
ALLOWED_DECISIONS = {"accept", "revise", "hold"}
DATED_CORRECTION_PREFIX = "2026-07-20"
SEMVER_RE = re.compile(r"^[1-9]\d*\.\d+\.\d+$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
EXPECTED_RULES_SEMANTIC_SHA256 = (
    "0ce1688e593ca2acc622ae0278d5972874f28dd7b41dffda110dd34ae3a7870e"
)
EXPECTED_BASE_DECISION_IDS_SHA256 = (
    "21b16b61cec71f8ed2b8c498aa936344dea1c0bad9877ea4560018013959a9f1"
)
EXPECTED_BASE_CANONICAL_DECISION_IDS_SHA256 = (
    "860fb89669e8b0a5d510737f5640547c084580c9920242ade9ac44cbec3fb8ba"
)
RESOLUTION_PURPOSE = (
    "정식 산출물 작성 전에 종합 검토 답변과 기능 간 연쇄 정책을 "
    "검증 가능한 작성 입력으로 고정한다."
)


class PolicyResolutionValidationError(ValueError):
    """Raised when a policy-review resolution input violates its contract."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise PolicyResolutionValidationError(message)


def _strict_object(value: Any, label: str) -> dict[str, Any]:
    _require(isinstance(value, dict), f"{label} must be an object")
    return value


def _strict_list(value: Any, label: str) -> list[Any]:
    _require(isinstance(value, list), f"{label} must be a list")
    return value


def _strict_string(value: Any, label: str, *, allow_empty: bool = False) -> str:
    _require(isinstance(value, str), f"{label} must be a string")
    if not allow_empty:
        _require(bool(value.strip()), f"{label} must not be blank")
    return value


def _unique_strings(value: Any, label: str) -> list[str]:
    items = _strict_list(value, label)
    for index, item in enumerate(items):
        _strict_string(item, f"{label}[{index}]")
    _require(len(items) == len(set(items)), f"{label} contains duplicates")
    return items


def _reject_constant(value: str) -> None:
    raise PolicyResolutionValidationError(f"non-standard JSON number is not allowed: {value}")


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise PolicyResolutionValidationError(f"duplicate JSON key is not allowed: {key}")
        result[key] = value
    return result


def load_strict_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_constant,
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise PolicyResolutionValidationError(f"cannot read strict JSON {path}: {exc}") from exc
    return _strict_object(value, str(path))


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def text_sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def object_sha256(value: Any) -> str:
    canonical = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def json_text(value: dict[str, Any]) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n"


def _expected_ids(prefix: str, count: int, width: int) -> list[str]:
    return [f"{prefix}-{index:0{width}d}" for index in range(1, count + 1)]


def _review_collections(answers: dict[str, Any]) -> list[tuple[str, dict[str, Any]]]:
    return [
        ("GLOBAL", _strict_object(answers.get("global_policy_reviews"), "global_policy_reviews")),
        ("SHARED", _strict_object(answers.get("shared_policy_reviews"), "shared_policy_reviews")),
        ("FEATURE", _strict_object(answers.get("feature_reviews"), "feature_reviews")),
    ]


def _all_reviews(answers: dict[str, Any]) -> dict[str, dict[str, Any]]:
    merged: dict[str, dict[str, Any]] = {}
    for _, collection in _review_collections(answers):
        for review_id, review in collection.items():
            _require(review_id not in merged, f"duplicate review ID across collections: {review_id}")
            merged[review_id] = _strict_object(review, f"review {review_id}")
    return merged


def _calculated_review_summary(answers: dict[str, Any]) -> dict[str, Any]:
    collections = dict(_review_collections(answers))
    expected = {
        "GLOBAL": set(_expected_ids("GP", 7, 2)),
        "SHARED": set(_expected_ids("SP", 15, 2)),
        "FEATURE": set(_expected_ids("FP", 54, 3)),
    }
    decisions: list[str] = []
    missing_reason_reviews: list[str] = []
    missing_reason_features: list[str] = []
    for scope, collection in collections.items():
        for review_id, review_value in collection.items():
            review = _strict_object(review_value, f"review {review_id}")
            _require(set(review) == {"decision", "note"}, f"review {review_id} has unknown fields")
            decision = _strict_string(review.get("decision"), f"{review_id}.decision")
            note = _strict_string(review.get("note"), f"{review_id}.note", allow_empty=True)
            _require(decision in ALLOWED_DECISIONS, f"invalid decision for {review_id}: {decision}")
            if decision in {"revise", "hold"} and not note.strip():
                if scope == "FEATURE":
                    missing_reason_features.append(review_id)
                else:
                    missing_reason_reviews.append(review_id)
            decisions.append(decision)
        _require(
            set(collection) == expected[scope],
            f"{scope.lower()} review IDs do not match the expected set",
        )

    counts = Counter(decisions)
    reviewed = len(decisions)
    return {
        "reviewed": reviewed,
        "reviewed_feature_count": len(collections["FEATURE"]),
        "reviewed_global_policy_count": len(collections["GLOBAL"]),
        "reviewed_shared_policy_count": len(collections["SHARED"]),
        "accepted": counts["accept"],
        "revision_requested": counts["revise"],
        "held": counts["hold"],
        "unreviewed_feature_ids": sorted(expected["FEATURE"] - set(collections["FEATURE"])),
        "unreviewed_global_policy_ids": sorted(expected["GLOBAL"] - set(collections["GLOBAL"])),
        "unreviewed_shared_policy_ids": sorted(expected["SHARED"] - set(collections["SHARED"])),
        "missing_reason_review_ids": sorted(missing_reason_reviews),
        "missing_reason_feature_ids": sorted(missing_reason_features),
        "missing_reviewer": not bool(str(answers.get("reviewer", "")).strip()),
    }


def _validate_source_bindings(rules: dict[str, Any]) -> list[dict[str, str]]:
    raw_bindings = _strict_list(rules.get("source_bindings"), "source_bindings")
    bindings: list[dict[str, str]] = []
    seen: set[str] = set()
    for index, raw in enumerate(raw_bindings):
        binding = _strict_object(raw, f"source_bindings[{index}]")
        _require(set(binding) == {"id", "path", "sha256"}, f"source binding {index} has unknown fields")
        source_id = _strict_string(binding.get("id"), f"source_bindings[{index}].id")
        relative_path = _strict_string(binding.get("path"), f"source_bindings[{index}].path")
        expected_hash = _strict_string(binding.get("sha256"), f"source_bindings[{index}].sha256")
        _require(source_id not in seen, f"duplicate source binding ID: {source_id}")
        _require(source_id in EXPECTED_SOURCE_PATHS, f"unknown source binding ID: {source_id}")
        _require(not Path(relative_path).is_absolute(), f"source path must be relative: {relative_path}")
        actual_path = (REPO_ROOT / relative_path).resolve()
        _require(
            actual_path == EXPECTED_SOURCE_PATHS[source_id].resolve(),
            f"source path differs from the configured input: {source_id}",
        )
        _require(SHA256_RE.fullmatch(expected_hash) is not None, f"invalid source hash: {source_id}")
        _require(actual_path.is_file(), f"source file is missing: {relative_path}")
        _require(file_sha256(actual_path) == expected_hash, f"source hash mismatch: {source_id}")
        seen.add(source_id)
        bindings.append({"id": source_id, "path": relative_path, "sha256": expected_hash})
    _require(set(seen) == set(EXPECTED_SOURCE_PATHS), "source binding set is incomplete")
    return bindings


def _validate_intake(intake: dict[str, Any], answers: dict[str, Any]) -> None:
    _require(
        set(intake)
        == {
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
        "intake top-level fields differ from the schema",
    )
    _require(
        intake.get("schema_version") == "walksafe.comprehensive-policy-review-answer-intake.v1",
        "intake schema_version is invalid",
    )
    _require(
        intake.get("intake_id") == "WS-POLICY-COMPREHENSIVE-ANSWER-INTAKE-20260720-001",
        "intake ID is invalid",
    )
    _require(SEMVER_RE.fullmatch(str(intake.get("intake_version", ""))) is not None, "invalid intake version")
    _strict_string(intake.get("change_reason"), "intake.change_reason")
    answer_intake = _strict_object(intake.get("answer_intake"), "answer_intake")
    controlled_path = _strict_string(answer_intake.get("controlled_path"), "answer_intake.controlled_path")
    _require(
        (REPO_ROOT / controlled_path).resolve() == CONTROLLED_ANSWERS_PATH.resolve(),
        "intake controlled answer path differs from the resolver input",
    )
    expected_hash = _strict_string(answer_intake.get("sha256"), "answer_intake.sha256")
    _require(file_sha256(CONTROLLED_ANSWERS_PATH) == expected_hash, "controlled answer hash mismatch")
    _require(
        CONTROLLED_ANSWERS_PATH.stat().st_size == answer_intake.get("byte_length"),
        "controlled answer byte length mismatch",
    )
    _require(answer_intake.get("external_path_usage") == "PROVENANCE_ONLY", "external path must be provenance only")
    external_path = _strict_string(answer_intake.get("external_path"), "answer_intake.external_path")
    _require(Path(external_path).is_absolute(), "external provenance path must be absolute")
    captured_at = _strict_string(answer_intake.get("captured_at"), "answer_intake.captured_at")
    try:
        captured_time = datetime.fromisoformat(captured_at)
    except ValueError as exc:
        raise PolicyResolutionValidationError("answer_intake.captured_at is not ISO-8601") from exc
    _require(
        captured_time.tzinfo is not None and captured_time.utcoffset() is not None,
        "answer_intake.captured_at must include a timezone",
    )
    controlled_revision = answer_intake.get("controlled_revision")
    _require(
        isinstance(controlled_revision, int)
        and not isinstance(controlled_revision, bool)
        and controlled_revision >= 1,
        "answer_intake.controlled_revision must be a positive integer",
    )
    superseded_hash = _strict_string(
        answer_intake.get("supersedes_external_sha256"),
        "answer_intake.supersedes_external_sha256",
    )
    _require(SHA256_RE.fullmatch(superseded_hash) is not None, "superseded answer hash is invalid")
    _require(superseded_hash != expected_hash, "superseded answer hash equals the controlled answer")
    _require(answer_intake.get("source_class") == "EXTERNAL", "answer source class is invalid")
    _require(answer_intake.get("artifact_form") == "EXTERNAL_RECORD", "answer artifact form is invalid")
    _require(
        answer_intake.get("evidence_classification") == "USER_CONFIRMED",
        "answer evidence classification is invalid",
    )
    _require(answer_intake.get("adoption_trust") == "D", "answer adoption trust is invalid")

    metadata = _strict_object(intake.get("answer_metadata"), "answer_metadata")
    direct_fields = (
        "schema_version",
        "report_id",
        "report_binding_sha256",
        "exported_at",
        "status",
        "baseline_status",
        "reviewer",
    )
    for field in direct_fields:
        _require(metadata.get(field) == answers.get(field), f"intake answer metadata is stale: {field}")
    summary = _strict_object(answers.get("summary"), "answers.summary")
    for field in ("reviewed", "accepted", "revision_requested", "held"):
        _require(metadata.get(field) == summary.get(field), f"intake review count is stale: {field}")

    review_source_bindings = _strict_list(
        intake.get("review_source_bindings"), "review_source_bindings"
    )
    seen_source_ids: set[str] = set()
    for index, raw in enumerate(review_source_bindings):
        binding = _strict_object(raw, f"review_source_bindings[{index}]")
        _require(
            set(binding) == {"id", "path", "sha256"},
            f"review_source_bindings[{index}] has unknown fields",
        )
        source_id = _strict_string(binding.get("id"), f"review_source_bindings[{index}].id")
        _require(source_id not in seen_source_ids, f"duplicate intake review source: {source_id}")
        _require(
            source_id in EXPECTED_INTAKE_REVIEW_SOURCE_PATHS,
            f"unknown intake review source: {source_id}",
        )
        relative_path = _strict_string(
            binding.get("path"), f"review_source_bindings[{index}].path"
        )
        _require(not Path(relative_path).is_absolute(), "intake review source path must be relative")
        path = (REPO_ROOT / relative_path).resolve()
        _require(
            path == EXPECTED_INTAKE_REVIEW_SOURCE_PATHS[source_id].resolve(),
            f"intake review source path differs from the configured input: {source_id}",
        )
        expected = _strict_string(binding.get("sha256"), f"review_source_bindings[{index}].sha256")
        _require(SHA256_RE.fullmatch(expected) is not None, f"invalid intake review source hash: {source_id}")
        _require(path.is_file() and file_sha256(path) == expected, f"review source binding mismatch: {path}")
        seen_source_ids.add(source_id)
    _require(
        seen_source_ids == set(EXPECTED_INTAKE_REVIEW_SOURCE_PATHS),
        "intake review source binding set is incomplete",
    )

    record_controls = _strict_object(intake.get("record_controls"), "record_controls")
    _require(
        set(record_controls)
        == {
            "external_signature_status",
            "confidentiality",
            "personal_data",
            "retention_class",
            "notes",
        },
        "record controls have unknown or missing fields",
    )
    _require(
        record_controls.get("external_signature_status") == "NOT_SIGNED",
        "external signature status is invalid",
    )
    _require(record_controls.get("confidentiality") == "INTERNAL", "confidentiality is invalid")
    _require(record_controls.get("personal_data") == ["reviewer_name"], "personal data marker is invalid")
    _require(
        record_controls.get("retention_class") == "PROJECT_LIFECYCLE_PLUS_3_YEARS",
        "retention class is invalid",
    )
    _strict_string(record_controls.get("notes"), "record_controls.notes")

    boundary = _strict_object(intake.get("approval_boundary"), "intake.approval_boundary")
    _require(
        boundary.get("review_status") == answers.get("status") == "REVIEW_COMPLETE",
        "intake review status differs from the controlled answer",
    )
    _require(boundary.get("baseline_status") == "NOT_APPROVED", "intake must not claim baseline approval")
    _require(boundary.get("release_status") == "NOT_ELIGIBLE", "intake must not claim release eligibility")
    _require(boundary.get("approved_by") is None, "intake must not set approved_by")
    _require(boundary.get("approved_at") is None, "intake must not set approved_at")


def _validate_answers_and_proposals(
    answers: dict[str, Any],
    proposals: dict[str, Any],
    intake: dict[str, Any],
    rules: dict[str, Any],
) -> None:
    expected_answer_keys = {
        "schema_version",
        "report_id",
        "report_binding_sha256",
        "exported_at",
        "status",
        "baseline_status",
        "reviewer",
        "summary",
        "global_policy_reviews",
        "shared_policy_reviews",
        "feature_reviews",
    }
    _require(set(answers) == expected_answer_keys, "answer top-level fields differ from the schema")
    _require(
        answers.get("schema_version") == "walksafe.comprehensive-policy-review-answers.v2",
        "answer schema_version is invalid",
    )
    _require(answers.get("status") == "REVIEW_COMPLETE", "review answers are incomplete")
    _require(answers.get("baseline_status") == "NOT_APPROVED", "answers must not claim baseline approval")
    _strict_string(answers.get("reviewer"), "answers.reviewer")
    _require(
        proposals.get("schema_version") == "walksafe.comprehensive-policy-proposals.v1",
        "proposal schema_version is invalid",
    )
    _require(answers.get("report_id") == proposals.get("report_id"), "answer report_id is stale")
    _require(
        answers.get("report_binding_sha256") == proposals.get("binding_sha256"),
        "answer report binding differs from the proposals",
    )

    calculated = _calculated_review_summary(answers)
    _require(answers.get("summary") == calculated, "stored answer summary differs from recalculated values")
    expected = _strict_object(rules.get("expected_review"), "expected_review")
    for field in ("report_id", "report_binding_sha256"):
        _require(expected.get(field) == answers.get(field), f"rules expected review is stale: {field}")
    for field in (
        "reviewed",
        "accepted",
        "revision_requested",
        "held",
        "global_policy_count",
        "shared_policy_count",
        "feature_count",
    ):
        expected_value = {
            "global_policy_count": calculated["reviewed_global_policy_count"],
            "shared_policy_count": calculated["reviewed_shared_policy_count"],
            "feature_count": calculated["reviewed_feature_count"],
        }.get(field, calculated.get(field))
        _require(expected.get(field) == expected_value, f"rules expected count is stale: {field}")

    reviews = _all_reviews(answers)
    review_binding = {
        review_id: {
            "decision": review["decision"],
            "note_sha256": text_sha256(review["note"]),
        }
        for review_id, review in sorted(reviews.items())
    }
    _require(
        expected.get("review_decision_note_binding_sha256")
        == object_sha256(review_binding),
        "review decisions or notes changed without a reviewed normalization update",
    )
    non_empty = sorted(review_id for review_id, review in reviews.items() if review["note"].strip())
    dated = sorted(
        review_id
        for review_id, review in reviews.items()
        if review["note"].startswith(DATED_CORRECTION_PREFIX)
    )
    dated_features = sorted(review_id for review_id in dated if review_id.startswith("FP-"))
    _require(expected.get("non_empty_note_count") == len(non_empty), "non-empty note count is stale")
    _require(expected.get("dated_correction_count") == len(dated), "dated correction count is stale")
    _require(
        expected.get("direct_changed_feature_count") == len(dated_features),
        "direct changed feature count is stale",
    )
    _require(
        expected.get("dated_changed_feature_ids") == dated_features,
        "dated changed feature IDs are stale",
    )
    for scope, field in (
        ("GP-", "revised_global_policy_ids"),
        ("SP-", "revised_shared_policy_ids"),
        ("FP-", "revised_feature_ids"),
    ):
        actual = sorted(
            review_id
            for review_id, review in reviews.items()
            if review_id.startswith(scope) and review["decision"] == "revise"
        )
        _require(expected.get(field) == actual, f"{field} is stale")

    metadata = _strict_object(intake.get("answer_metadata"), "answer_metadata")
    _require(metadata.get("non_empty_note_count") == len(non_empty), "intake non-empty note count is stale")
    _require(metadata.get("dated_correction_count") == len(dated), "intake dated correction count is stale")
    _require(
        metadata.get("direct_changed_feature_count") == len(dated_features),
        "intake direct changed feature count is stale",
    )


def _validate_base_candidates(
    base_feature_policy: dict[str, Any],
    base_register: dict[str, Any],
    proposals: dict[str, Any],
) -> None:
    _require(
        base_feature_policy.get("schema_version") == "walksafe.effective-feature-policy.v1",
        "base feature policy schema_version is invalid",
    )
    _require(base_feature_policy.get("lifecycle_status") == "IN_REVIEW", "base feature policy is not in review")
    feature_boundary = _strict_object(base_feature_policy.get("approval_boundary"), "feature approval_boundary")
    _require(feature_boundary.get("baseline_status") == "NOT_APPROVED", "base feature policy claims approval")
    _require(
        base_register.get("schema_version") == "walksafe.effective-decision-register.v1",
        "base register schema_version is invalid",
    )
    _require(base_register.get("lifecycle_status") == "IN_REVIEW", "base register is not in review")
    register_boundary = _strict_object(base_register.get("approval_boundary"), "register approval_boundary")
    _require(register_boundary.get("baseline_status") == "NOT_APPROVED", "base register claims approval")

    proposal_features = _strict_list(proposals.get("features"), "proposal features")
    proposal_id_list = [item["feature_id"] for item in proposal_features]
    expected_feature_ids = _expected_ids("FP", 54, 3)
    _require(
        proposal_id_list == expected_feature_ids
        and len(proposal_id_list) == len(set(proposal_id_list)),
        "proposal feature IDs are incomplete, duplicated, or out of order",
    )
    base_features = _strict_list(base_feature_policy.get("features"), "base features")
    base_id_list = [item["id"] for item in base_features]
    _require(
        base_id_list == expected_feature_ids
        and len(base_id_list) == len(set(base_id_list)),
        "base feature IDs are incomplete, duplicated, or out of order",
    )
    decisions = _strict_list(base_register.get("decisions"), "base decisions")
    _require(len(decisions) == 135, "base decision register count is invalid")
    decision_ids = [item["decision_id"] for item in decisions]
    canonical_ids = [item["canonical_decision_id"] for item in decisions]
    register_orders = [item["register_order"] for item in decisions]
    _require(len(decision_ids) == len(set(decision_ids)), "base decision IDs contain duplicates")
    _require(len(canonical_ids) == len(set(canonical_ids)), "base canonical decision IDs contain duplicates")
    _require(
        object_sha256(sorted(decision_ids)) == EXPECTED_BASE_DECISION_IDS_SHA256,
        "base decision ID set differs from the reviewed register",
    )
    _require(
        object_sha256(sorted(canonical_ids)) == EXPECTED_BASE_CANONICAL_DECISION_IDS_SHA256,
        "base canonical decision ID set differs from the reviewed register",
    )
    _require(register_orders == list(range(1, 136)), "base register order is incomplete or duplicated")
    valid_features = set(expected_feature_ids)
    for decision in decisions:
        affected = _unique_strings(
            decision.get("affected_feature_ids"),
            f"{decision['decision_id']}.affected_feature_ids",
        )
        _require(set(affected) <= valid_features, f"base decision has an unknown feature: {decision['decision_id']}")


def _validate_rule_references(rules: dict[str, Any], answers: dict[str, Any]) -> None:
    semantic_rules = {
        key: value for key, value in rules.items() if key != "source_bindings"
    }
    _require(
        object_sha256(semantic_rules) == EXPECTED_RULES_SEMANTIC_SHA256,
        "resolution rule semantics changed without a reviewed validator update",
    )
    reviews = _all_reviews(answers)
    feature_ids = set(_expected_ids("FP", 54, 3))
    constants = _strict_list(rules.get("normalized_policy_constants"), "normalized_policy_constants")
    constant_ids = _unique_strings([item["id"] for item in constants], "normalized constant IDs")
    for raw in constants:
        item = _strict_object(raw, "normalized policy constant")
        for source_id in _unique_strings(item.get("source_review_ids"), f"{item['id']}.source_review_ids"):
            _require(source_id in reviews, f"unknown review source in {item['id']}: {source_id}")

    cascades = _strict_list(rules.get("cascade_groups"), "cascade_groups")
    _unique_strings([item["id"] for item in cascades], "cascade group IDs")
    for raw in cascades:
        item = _strict_object(raw, "cascade group")
        for constant_id in _unique_strings(item.get("constant_ids"), f"{item['id']}.constant_ids"):
            _require(constant_id in constant_ids, f"unknown constant in {item['id']}: {constant_id}")
        for review_id in _unique_strings(item.get("source_review_ids"), f"{item['id']}.source_review_ids"):
            _require(review_id in reviews, f"unknown review source in {item['id']}: {review_id}")
        affected = set(_unique_strings(item.get("affected_feature_ids"), f"{item['id']}.affected_feature_ids"))
        _require(bool(affected) and affected <= feature_ids, f"invalid affected feature in {item['id']}")

    replacements = _strict_list(rules.get("known_conflict_replacements"), "known_conflict_replacements")
    _unique_strings([item["id"] for item in replacements], "known conflict replacement IDs")
    for raw in replacements:
        item = _strict_object(raw, "known conflict replacement")
        for review_id in _unique_strings(
            item.get("superseding_review_ids"), f"{item['id']}.superseding_review_ids"
        ):
            _require(review_id in reviews, f"unknown superseding review in {item['id']}: {review_id}")
        affected = set(_unique_strings(item.get("affected_feature_ids"), f"{item['id']}.affected_feature_ids"))
        _require(bool(affected) and affected <= feature_ids, f"invalid conflict feature in {item['id']}")
        _unique_strings(item.get("stale_active_concepts"), f"{item['id']}.stale_active_concepts")
        _strict_string(item.get("replacement"), f"{item['id']}.replacement")

    gates = _strict_list(rules.get("remaining_gates"), "remaining_gates")
    _unique_strings([item["id"] for item in gates], "remaining gate IDs")
    for raw in gates:
        item = _strict_object(raw, "remaining gate")
        _require(item.get("status") == "NOT_RUN", f"remaining gate must be NOT_RUN: {item['id']}")
        affected = set(_unique_strings(item.get("affected_feature_ids"), f"{item['id']}.affected_feature_ids"))
        _require(bool(affected) and affected <= feature_ids, f"invalid gate feature in {item['id']}")


def _validate_normalized_constants(rules: dict[str, Any]) -> None:
    constants = {
        item["id"]: item
        for item in _strict_list(rules.get("normalized_policy_constants"), "normalized_policy_constants")
    }
    raw_original = constants["NPC-RAW-ORIGINAL-COLLECTION"]
    _require(
        raw_original["bystander_face_plate_voice_masking_at_collection"] == "NONE",
        "raw originals must not be masked at collection",
    )

    server = constants["NPC-SERVER-STORAGE-CAPACITY"]
    _require(
        server["primary_limit_gib"] + server["backup_limit_gib"] == server["physical_total_limit_gib"],
        "server storage total does not equal primary plus backup",
    )
    _require(
        server["thresholds"]
        == [
            {"percent": 70, "gib": 210, "action": "ADMIN_ONLY_WARNING"},
            {
                "percent": 85,
                "gib": 255,
                "action": "STOP_ADDING_NEW_FIELD_TEST_PARTICIPANTS",
            },
            {
                "percent": 95,
                "gib": 285,
                "action": "CLEAN_EXPIRED_DATA_THEN_HOLD_NEW_RAW_COLLECTION_SESSIONS",
            },
            {
                "percent": 100,
                "gib": 300,
                "action": "SILENTLY_HOLD_NEW_TRAINING_DATA_AND_AUTO_REPORT_CANDIDATES",
            },
        ],
        "server capacity threshold actions differ from the confirmed policy",
    )
    for threshold in server["thresholds"]:
        _require(
            threshold["gib"] == server["primary_limit_gib"] * threshold["percent"] // 100,
            f"server capacity threshold arithmetic is wrong: {threshold['percent']}%",
        )
    pricing = server["pricing_assumption"]
    calculated_cost = round(
        (
            server["primary_limit_gib"] * pricing["standard_usd_per_gib_month"]
            + server["backup_limit_gib"] * pricing["nearline_usd_per_gib_month"]
        )
        * pricing["policy_exchange_rate_krw_per_usd"]
    )
    _require(pricing["calculated_storage_only_krw"] == calculated_cost, "storage cost arithmetic is wrong")
    _require(calculated_cost <= server["monthly_storage_budget_krw"], "storage-only cost exceeds the policy budget")

    phone = constants["NPC-PHONE-QUEUE-CAPACITY"]
    _require(phone["server_gib_or_percentage_thresholds_apply"] is False, "server thresholds leak to phones")
    _require(phone["user_notification_for_data_pipeline_hold"] is False, "phone data hold must be silent")
    _require(
        phone["delete_or_hold_order"]
        == [
            "RECREATABLE_TEMP_CACHE",
            "SERVER_RECEIPT_CONFIRMED_LOCAL_COPY",
            "EXPIRED_OPTIONAL_TRAINING_DATA",
            "EXPIRED_LOW_CONFIDENCE_UNSENT_REPORT_CANDIDATE",
            "HOLD_NEW_TRAINING_DATA_AND_AUTO_REPORT_CANDIDATE_CREATION",
        ],
        "phone deletion/hold order differs from the confirmed five stages",
    )

    lifecycle = constants["NPC-DATA-LIFECYCLE"]
    retention = lifecycle["retention"]
    _require(retention["unsent_local_original_days"] == 30, "unsent local retention must be 30 days")
    _require(retention["server_general_and_auto_report_original_days"] == 180, "server raw retention must be 180 days")
    _require(
        retention["approved_training_original_label_and_fixed_validation_calendar_years"] == 3,
        "training retention must be three calendar years",
    )
    _require(retention["rotating_backup_days"] == 35, "backup rotation must be 35 days")
    _require(
        lifecycle["full_deletion_request_deadlines"]["hash_only_deletion_receipt_calendar_years"]
        == 3,
        "deletion receipt retention must be three calendar years",
    )
    _require(
        set(lifecycle["route_data_objects"])
        == {"local_operational_route_cache", "consented_uploaded_activity_original"},
        "route cache and uploaded route original must be separate data objects",
    )

    route = constants["NPC-NAVIGATION-ROUTE-DIRECTION"]
    _require(len(route["deviation_flow"]) == 10, "route-deviation flow must contain exactly ten steps")
    _require(
        route["direction_responsibility"]["stride_for_direction"] == "PROHIBITED",
        "stride must not determine direction",
    )
    _require(route["reroute_on_network_recovery"] is False, "network recovery must not trigger rerouting")

    auto_report = constants["NPC-AUTO-REPORT"]
    _require(auto_report["candidate_notification"] == "NONE", "automatic candidates must be silent")
    _require(auto_report["per_candidate_cancel"] is False, "per-candidate cancellation must be disabled")
    _require(auto_report["settings_master_off"] is True, "automatic reporting needs a master off switch")
    _require(
        auto_report["off_behavior"]
        == [
            "새 후보 생성 즉시 중단",
            "미전송 후보의 전송 중단 및 24시간 안 삭제",
            "전송 중인 자료의 서버 처리 여부 확인",
            "서버 원본을 삭제요청 상태로 바꾸고 7일 안 삭제",
            "처리 결과를 내부 감사기록에 남김",
        ],
        "automatic report off behavior differs from the confirmed policy",
    )
    _require(
        auto_report["mobile_network_transfer"] == "OPT_IN_ONLY",
        "mobile-network transfer must be opt-in only",
    )
    _require(
        auto_report["wifi_unavailable_behavior"]
        == "암호화 대기열에 보관하고 다음 Wi-Fi 연결 때 전송",
        "Wi-Fi-unavailable behavior differs from the confirmed waiting policy",
    )
    _require(auto_report["upload_after_walk_session_ends"] is True, "automatic report upload timing is invalid")
    _require(auto_report["stable_idempotency_key_required"] is True, "idempotency key is required")
    _require("상태를 먼저 조회" in auto_report["unknown_response_behavior"], "unknown response recovery is invalid")
    _require("commit" in auto_report["completion_condition"], "automatic report completion lacks commit")
    _require("hash" in auto_report["completion_condition"], "automatic report completion lacks hash receipt")

    permission = constants["NPC-PERMISSION-SESSION-LIFECYCLE"]
    _require(permission["logout_revokes_os_permissions"] is False, "logout must not revoke OS permissions")
    _require(
        permission["walk_or_route_auto_resume_after_reboot_crash_or_os_kill"] is False,
        "walk or route must not auto-resume after process or device restart",
    )

    admin = constants["NPC-SINGLE-ADMIN-RECOVERY"]
    _require(admin["administrator_count"] == 1 and admin["final_approver_count"] == 1, "admin count is invalid")
    _require(admin["two_independent_recovery_approvers_required"] is False, "dual recovery approvers remain enabled")
    _require(admin["authentication"] == "MFA_OR_PASSKEY", "single-admin authentication policy is invalid")
    _require(
        admin["recovery_material_location"] == "관리자 휴대전화 밖의 복구코드 또는 보안키",
        "single-admin recovery material must stay outside the administrator phone",
    )
    _require(
        admin["remote_device_session_revocation_required"] is True,
        "single-admin recovery must allow remote device-session revocation",
    )
    _require(
        admin["access_loss_behavior"] == "복구할 때까지 출시·권한 변경·데이터 삭제 등 고위험 작업 동결",
        "single-admin access-loss behavior differs from the confirmed high-risk freeze",
    )
    _require(
        admin["pre_field_test_or_deployment_recovery_drill_count"] == 1,
        "single-admin recovery requires exactly one pre-field-test or pre-deployment drill",
    )

    sync = constants["NPC-SERVER-CAPACITY-STATE-SYNC"]
    _require(sync["parameter_status"] == "PENDING_ENGINEERING_REVIEW", "capacity-state parameters claim approval")
    _require(sync["online_poll_interval_minutes"] is None, "capacity poll interval was guessed")
    _require(sync["state_ttl_minutes"] is None, "capacity-state TTL was guessed")
    _require("30일" in sync["server_rejection_behavior"], "server rejection ignores local expiry")


def _proposal_indexes(proposals: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    global_items = _strict_list(proposals.get("global_policy_proposals"), "global proposals")
    shared_items = _strict_list(proposals.get("shared_decision_proposals"), "shared proposals")
    feature_items = _strict_list(proposals.get("features"), "feature proposals")
    global_ids = [item["id"] for item in global_items]
    shared_ids = [item["proposal_id"] for item in shared_items]
    feature_ids = [item["feature_id"] for item in feature_items]
    _require(
        global_ids == _expected_ids("GP", 7, 2) and len(global_ids) == len(set(global_ids)),
        "global proposal IDs are incomplete, duplicated, or out of order",
    )
    _require(
        shared_ids == _expected_ids("SP", 15, 2) and len(shared_ids) == len(set(shared_ids)),
        "shared proposal IDs are incomplete, duplicated, or out of order",
    )
    _require(
        feature_ids == _expected_ids("FP", 54, 3) and len(feature_ids) == len(set(feature_ids)),
        "feature proposal IDs are incomplete, duplicated, or out of order",
    )
    globals_by_id = {item["id"]: item for item in global_items}
    shared_by_id = {item["proposal_id"]: item for item in shared_items}
    features_by_id = {item["feature_id"]: item for item in feature_items}
    return globals_by_id, shared_by_id, features_by_id


def _merge_action(decision: str, note: str) -> str:
    if decision == "hold":
        return "DO_NOT_AUTHOR_UNTIL_RESOLVED"
    if decision == "revise":
        return "SUPERSEDE_CONFLICTING_CLAUSES_AND_APPLY_NOTE"
    if note.strip():
        return "KEEP_PROPOSAL_AND_APPLY_NOTE"
    return "KEEP_PROPOSAL"


def _build_review_records(
    answers: dict[str, Any],
    proposals: dict[str, Any],
) -> list[dict[str, Any]]:
    globals_by_id, shared_by_id, features_by_id = _proposal_indexes(proposals)
    records: list[dict[str, Any]] = []
    for scope, collection in _review_collections(answers):
        for review_id in sorted(collection):
            review = collection[review_id]
            note = review["note"]
            if scope == "GLOBAL":
                proposal = globals_by_id[review_id]
                proposal_ref = f"global_policy_proposals#{review_id}"
                proposal_text = proposal["policy"]
                affected = proposal["feature_ids"]
            elif scope == "SHARED":
                proposal = shared_by_id[review_id]
                proposal_ref = f"shared_decision_proposals#{review_id}"
                proposal_text = proposal["recommended_answer"]
                affected = proposal["affected_feature_ids"]
            else:
                proposal = features_by_id[review_id]
                proposal_ref = f"features#{review_id}"
                proposal_text = proposal["policy_conclusion"]
                affected = [review_id]
            records.append(
                {
                    "review_id": review_id,
                    "scope": scope,
                    "decision": review["decision"],
                    "merge_action": _merge_action(review["decision"], note),
                    "proposal_ref": proposal_ref,
                    "proposal_text": proposal_text,
                    "proposal_text_sha256": text_sha256(proposal_text),
                    "proposal_text_role": (
                        "HISTORICAL_CONTEXT_WHERE_CONFLICTING"
                        if review["decision"] == "revise"
                        else "ACTIVE_BASE"
                    ),
                    "reviewer_note": note,
                    "reviewer_note_sha256": text_sha256(note) if note else None,
                    "is_dated_correction": note.startswith(DATED_CORRECTION_PREFIX),
                    "affected_feature_ids": affected,
                }
            )
    return records


def _build_feature_resolutions(
    answers: dict[str, Any],
    proposals: dict[str, Any],
    base_feature_policy: dict[str, Any],
    rules: dict[str, Any],
) -> list[dict[str, Any]]:
    globals_by_id, shared_by_id, features_by_id = _proposal_indexes(proposals)
    reviews = _all_reviews(answers)
    base_by_id = {
        item["id"]: item for item in _strict_list(base_feature_policy.get("features"), "base features")
    }
    globals_for_feature: dict[str, list[str]] = {feature_id: [] for feature_id in base_by_id}
    shared_for_feature: dict[str, list[str]] = {feature_id: [] for feature_id in base_by_id}
    for review_id, proposal in globals_by_id.items():
        for feature_id in proposal["feature_ids"]:
            globals_for_feature[feature_id].append(review_id)
    for review_id, proposal in shared_by_id.items():
        for feature_id in proposal["affected_feature_ids"]:
            shared_for_feature[feature_id].append(review_id)

    groups_for_feature: dict[str, list[dict[str, Any]]] = {feature_id: [] for feature_id in base_by_id}
    for group in rules["cascade_groups"]:
        for feature_id in group["affected_feature_ids"]:
            groups_for_feature[feature_id].append(group)

    conflicts_for_feature: dict[str, list[str]] = {feature_id: [] for feature_id in base_by_id}
    for replacement in rules["known_conflict_replacements"]:
        for feature_id in replacement["affected_feature_ids"]:
            conflicts_for_feature[feature_id].append(replacement["id"])

    gates_for_feature: dict[str, list[str]] = {feature_id: [] for feature_id in base_by_id}
    for gate in rules["remaining_gates"]:
        for feature_id in gate["affected_feature_ids"]:
            gates_for_feature[feature_id].append(gate["id"])

    result: list[dict[str, Any]] = []
    for feature_id in sorted(base_by_id):
        base = base_by_id[feature_id]
        groups = groups_for_feature[feature_id]
        direct_and_inherited_review_ids = [
            feature_id,
            *sorted(globals_for_feature[feature_id]),
            *sorted(shared_for_feature[feature_id]),
        ]
        cascade_evidence_review_ids = sorted(
            {
                review_id
                for group in groups
                for review_id in group["source_review_ids"]
            }
        )
        applicable_review_ids = sorted(set(direct_and_inherited_review_ids))
        dated_ids = [
            review_id
            for review_id in applicable_review_ids
            if reviews[review_id]["note"].startswith(DATED_CORRECTION_PREFIX)
        ]
        cascade_dated_ids = [
            review_id
            for review_id in cascade_evidence_review_ids
            if reviews[review_id]["note"].startswith(DATED_CORRECTION_PREFIX)
        ]
        note_ids = [
            review_id for review_id in applicable_review_ids if reviews[review_id]["note"].strip()
        ]
        revision_ids = [
            review_id for review_id in applicable_review_ids if reviews[review_id]["decision"] == "revise"
        ]
        constant_ids = sorted({constant_id for group in groups for constant_id in group["constant_ids"]})
        accepted_change_proposal_refs = [
            f"features#{feature_id}.open_item_proposals[{index}]"
            for index, proposal in enumerate(features_by_id[feature_id]["open_item_proposals"])
            if proposal["changes_current_policy"]
        ]
        accepted_change_proposal_refs.extend(
            f"global_policy_proposals#{review_id}"
            for review_id in globals_for_feature[feature_id]
            if globals_by_id[review_id]["changes_current_policy"]
            and reviews[review_id]["decision"] != "hold"
        )
        accepted_change_proposal_refs.extend(
            f"shared_decision_proposals#{review_id}"
            for review_id in shared_for_feature[feature_id]
            if shared_by_id[review_id]["changes_current_policy"]
            and reviews[review_id]["decision"] != "hold"
        )
        accepted_change_proposal_refs = sorted(accepted_change_proposal_refs)
        policy_change_reasons: list[str] = []
        if revision_ids or dated_ids:
            policy_change_reasons.append("DIRECT_OR_INHERITED_REVIEW_AMENDMENT")
        if groups:
            policy_change_reasons.append("CASCADE_CONSTANT")
        if accepted_change_proposal_refs:
            policy_change_reasons.append("ACCEPTED_PROPOSAL_CHANGES_CURRENT_POLICY")
        if revision_ids:
            integration_status = "REVISION_OVERLAY_REQUIRED"
        elif dated_ids:
            integration_status = "ACCEPTED_WITH_AMENDMENTS"
        elif groups:
            integration_status = "CASCADE_AMENDMENT_REQUIRED"
        elif accepted_change_proposal_refs:
            integration_status = "ACCEPTED_CHANGE_PROPOSAL"
        else:
            integration_status = "ACCEPTED_AS_PROPOSED"
        result.append(
            {
                "feature_id": feature_id,
                "feature_name": base["name"],
                "area_id": base["area_id"],
                "direct_review_id": feature_id,
                "inherited_global_review_ids": sorted(globals_for_feature[feature_id]),
                "inherited_shared_review_ids": sorted(shared_for_feature[feature_id]),
                "cascade_group_ids": sorted(group["id"] for group in groups),
                "normalized_constant_ids": constant_ids,
                "direct_and_inherited_review_ids": applicable_review_ids,
                "cascade_evidence_review_ids": cascade_evidence_review_ids,
                "applicable_review_ids": applicable_review_ids,
                "review_note_ids": note_ids,
                "dated_correction_review_ids": dated_ids,
                "cascade_dated_correction_review_ids": cascade_dated_ids,
                "revision_source_ids": revision_ids,
                "accepted_change_proposal_refs": accepted_change_proposal_refs,
                "policy_change_reasons": policy_change_reasons,
                "known_conflict_replacement_ids": sorted(conflicts_for_feature[feature_id]),
                "remaining_gate_ids": sorted(gates_for_feature[feature_id]),
                "policy_integration_status": integration_status,
                "implementation_status_before_review": base["implementation"]["status"],
                "implementation_alignment_status": (
                    "REVALIDATION_REQUIRED"
                    if policy_change_reasons
                    else "NOT_ASSESSED_IN_THIS_STAGE"
                ),
                "policy_status_before_review": base["policy"]["overall_status"],
                "policy_conflict_after_review": (
                    "POLICY_CONFLICT_RESOLVED_MEASUREMENT_PENDING"
                    if feature_id == "FP-053"
                    else "NONE_IDENTIFIED_BY_THIS_RESOLUTION"
                ),
            }
        )
    return result


def _resolution_summary(
    review_records: list[dict[str, Any]],
    feature_resolutions: list[dict[str, Any]],
    rules: dict[str, Any],
) -> dict[str, Any]:
    integration_counts = Counter(
        item["policy_integration_status"] for item in feature_resolutions
    )
    revalidation_ids = [
        item["feature_id"]
        for item in feature_resolutions
        if item["implementation_alignment_status"] == "REVALIDATION_REQUIRED"
    ]
    cascade_affected = {
        feature_id
        for group in rules["cascade_groups"]
        for feature_id in group["affected_feature_ids"]
    }
    dated_review_ids = [
        item["review_id"] for item in review_records if item["is_dated_correction"]
    ]
    non_empty_note_ids = [
        item["review_id"] for item in review_records if item["reviewer_note"]
    ]
    return {
        "reviewed": len(review_records),
        "accepted": sum(item["decision"] == "accept" for item in review_records),
        "revision_requested": sum(item["decision"] == "revise" for item in review_records),
        "held": sum(item["decision"] == "hold" for item in review_records),
        "non_empty_note_count": len(non_empty_note_ids),
        "dated_correction_count": len(dated_review_ids),
        "direct_changed_feature_count": rules["expected_review"]["direct_changed_feature_count"],
        "feature_count": len(feature_resolutions),
        "cascade_group_count": len(rules["cascade_groups"]),
        "cascade_affected_feature_count": len(cascade_affected),
        "normalized_policy_constant_count": len(rules["normalized_policy_constants"]),
        "known_conflict_replacement_count": len(rules["known_conflict_replacements"]),
        "remaining_gate_count": len(rules["remaining_gates"]),
        "implementation_revalidation_feature_count": len(revalidation_ids),
        "feature_integration_status_counts": dict(sorted(integration_counts.items())),
        "previous_remaining_policy_conflict_feature_ids": ["FP-053"],
        "remaining_policy_conflict_feature_ids": [],
        "unresolved_review_ids": [],
    }


def _resolution_coverage(
    review_records: list[dict[str, Any]],
    feature_resolutions: list[dict[str, Any]],
    rules: dict[str, Any],
) -> dict[str, Any]:
    cascade_affected = sorted(
        {
            feature_id
            for group in rules["cascade_groups"]
            for feature_id in group["affected_feature_ids"]
        }
    )
    return {
        "expected_review_ids": (
            _expected_ids("GP", 7, 2)
            + _expected_ids("SP", 15, 2)
            + _expected_ids("FP", 54, 3)
        ),
        "actual_review_ids": [item["review_id"] for item in review_records],
        "non_empty_note_review_ids": [
            item["review_id"] for item in review_records if item["reviewer_note"]
        ],
        "dated_correction_review_ids": [
            item["review_id"] for item in review_records if item["is_dated_correction"]
        ],
        "expected_feature_ids": _expected_ids("FP", 54, 3),
        "actual_feature_ids": [item["feature_id"] for item in feature_resolutions],
        "cascade_affected_feature_ids": cascade_affected,
        "implementation_revalidation_feature_ids": [
            item["feature_id"]
            for item in feature_resolutions
            if item["implementation_alignment_status"] == "REVALIDATION_REQUIRED"
        ],
        "missing_review_ids": [],
        "duplicate_review_ids": [],
        "missing_feature_ids": [],
        "duplicate_feature_ids": [],
        "orphan_cascade_source_ids": [],
        "orphan_cascade_feature_ids": [],
    }


def build_resolution() -> dict[str, Any]:
    rules = load_strict_json(RULES_PATH)
    answers = load_strict_json(CONTROLLED_ANSWERS_PATH)
    intake = load_strict_json(INTAKE_PATH)
    proposals = load_strict_json(PROPOSALS_PATH)
    base_feature_policy = load_strict_json(BASE_FEATURE_POLICY_PATH)
    base_register = load_strict_json(BASE_REGISTER_PATH)

    _require(
        rules.get("schema_version") == "walksafe.feature-policy-review-resolution-rules.v1",
        "resolution rules schema_version is invalid",
    )
    _require(SEMVER_RE.fullmatch(str(rules.get("rules_version", ""))) is not None, "invalid rules version")
    _require(rules.get("lifecycle_status") == "IN_REVIEW", "resolution rules must stay in review")
    _strict_string(rules.get("change_reason"), "rules.change_reason")
    source_bindings = _validate_source_bindings(rules)
    _validate_intake(intake, answers)
    _validate_answers_and_proposals(answers, proposals, intake, rules)
    _validate_base_candidates(base_feature_policy, base_register, proposals)
    _validate_rule_references(rules, answers)
    _validate_normalized_constants(rules)

    rules_binding = {
        "id": "SRC-POLICY-RESOLUTION-RULES",
        "path": str(RULES_PATH.relative_to(REPO_ROOT)),
        "sha256": file_sha256(RULES_PATH),
    }
    all_bindings = [*source_bindings, rules_binding]
    input_binding_sha256 = object_sha256(
        {item["id"]: item["sha256"] for item in sorted(all_bindings, key=lambda item: item["id"])}
    )
    review_records = _build_review_records(answers, proposals)
    feature_resolutions = _build_feature_resolutions(
        answers,
        proposals,
        base_feature_policy,
        rules,
    )
    result = {
        "schema_version": "walksafe.feature-policy-review-resolution.v1",
        "resolution_id": rules["resolution_id"],
        "resolution_version": rules["rules_version"],
        "as_of": rules["as_of"],
        "lifecycle_status": "IN_REVIEW",
        "purpose": RESOLUTION_PURPOSE,
        "change_reason": rules["change_reason"],
        "input_binding_sha256": input_binding_sha256,
        "source_bindings": all_bindings,
        "approval_boundary": rules["approval_boundary"],
        "generation_boundary": rules["generation_boundary"],
        "policy_precedence": rules["policy_precedence"],
        "application_rules": rules["application_rules"],
        "summary": _resolution_summary(review_records, feature_resolutions, rules),
        "review_records": review_records,
        "normalized_policy_constants": rules["normalized_policy_constants"],
        "cascade_groups": rules["cascade_groups"],
        "known_conflict_replacements": rules["known_conflict_replacements"],
        "remaining_gates": rules["remaining_gates"],
        "feature_resolutions": feature_resolutions,
        "coverage": _resolution_coverage(review_records, feature_resolutions, rules),
        "next_stage_handoff": {
            "status": "READY_FOR_POLICY_AUTHORING",
            "formal_deliverable_generation_status": "NOT_RUN",
            "html_report_generation_status": "NOT_RUN",
            "required_input_binding_sha256": input_binding_sha256,
            "required_resolution_path": str(OUTPUT_PATH.relative_to(REPO_ROOT)),
            "rule": "다음 작성기는 이 파일의 resolution_content_sha256과 source binding을 검증하고 현재 입력으로 재계산한 결과와 일치한 뒤에만 기능별 정책 초안을 만든다. 기존 proposals만 단독으로 사용하지 않는다.",
        },
    }
    result["resolution_content_sha256"] = object_sha256(result)
    validate_resolution(result)
    return result


def validate_resolution(result: dict[str, Any]) -> None:
    expected_keys = {
        "schema_version",
        "resolution_id",
        "resolution_version",
        "as_of",
        "lifecycle_status",
        "purpose",
        "change_reason",
        "input_binding_sha256",
        "source_bindings",
        "approval_boundary",
        "generation_boundary",
        "policy_precedence",
        "application_rules",
        "summary",
        "review_records",
        "normalized_policy_constants",
        "cascade_groups",
        "known_conflict_replacements",
        "remaining_gates",
        "feature_resolutions",
        "coverage",
        "next_stage_handoff",
        "resolution_content_sha256",
    }
    _require(set(result) == expected_keys, "resolution top-level fields differ from the schema")
    content_hash = _strict_string(
        result.get("resolution_content_sha256"),
        "resolution_content_sha256",
    )
    _require(SHA256_RE.fullmatch(content_hash) is not None, "resolution content hash is invalid")
    payload = dict(result)
    payload.pop("resolution_content_sha256")
    _require(
        object_sha256(payload) == content_hash,
        "resolution content hash differs from the payload",
    )

    rules = load_strict_json(RULES_PATH)
    answers = load_strict_json(CONTROLLED_ANSWERS_PATH)
    intake = load_strict_json(INTAKE_PATH)
    proposals = load_strict_json(PROPOSALS_PATH)
    base_feature_policy = load_strict_json(BASE_FEATURE_POLICY_PATH)
    base_register = load_strict_json(BASE_REGISTER_PATH)
    source_bindings = _validate_source_bindings(rules)
    _validate_intake(intake, answers)
    _validate_answers_and_proposals(answers, proposals, intake, rules)
    _validate_base_candidates(base_feature_policy, base_register, proposals)
    _validate_rule_references(rules, answers)
    _validate_normalized_constants(rules)

    rules_binding = {
        "id": "SRC-POLICY-RESOLUTION-RULES",
        "path": str(RULES_PATH.relative_to(REPO_ROOT)),
        "sha256": file_sha256(RULES_PATH),
    }
    expected_bindings = [*source_bindings, rules_binding]
    _require(result.get("source_bindings") == expected_bindings, "resolution source bindings are stale")
    expected_input_binding = object_sha256(
        {
            item["id"]: item["sha256"]
            for item in sorted(expected_bindings, key=lambda item: item["id"])
        }
    )
    _require(
        result.get("input_binding_sha256") == expected_input_binding,
        "resolution input binding is stale",
    )

    _require(
        result.get("schema_version") == "walksafe.feature-policy-review-resolution.v1",
        "resolution schema_version is invalid",
    )
    _require(result.get("resolution_id") == rules["resolution_id"], "resolution ID is stale")
    _require(result.get("resolution_version") == rules["rules_version"], "resolution version is stale")
    _require(result.get("as_of") == rules["as_of"], "resolution date is stale")
    _require(result.get("lifecycle_status") == "IN_REVIEW", "resolution must stay in review")
    _require(result.get("purpose") == RESOLUTION_PURPOSE, "resolution purpose is invalid")
    _require(result.get("change_reason") == rules["change_reason"], "resolution change reason is stale")
    for field in (
        "approval_boundary",
        "generation_boundary",
        "policy_precedence",
        "application_rules",
        "normalized_policy_constants",
        "cascade_groups",
        "known_conflict_replacements",
        "remaining_gates",
    ):
        _require(result.get(field) == rules[field], f"resolution rules are stale: {field}")

    expected_reviews = _build_review_records(answers, proposals)
    expected_features = _build_feature_resolutions(
        answers,
        proposals,
        base_feature_policy,
        rules,
    )
    _require(result.get("review_records") == expected_reviews, "resolved review records are stale")
    _require(result.get("feature_resolutions") == expected_features, "resolved feature records are stale")
    _require(
        result.get("summary") == _resolution_summary(expected_reviews, expected_features, rules),
        "resolution summary is stale",
    )
    _require(
        result.get("coverage") == _resolution_coverage(expected_reviews, expected_features, rules),
        "resolution coverage is stale",
    )

    boundary = _strict_object(result.get("approval_boundary"), "resolution approval_boundary")
    _require(
        boundary.get("owner_policy_review_status") == "REVIEW_COMPLETE_WITH_REVISIONS",
        "resolution owner review status is invalid",
    )
    _require(boundary.get("baseline_status") == "NOT_APPROVED", "resolution claims baseline approval")
    _require(boundary.get("release_status") == "NOT_ELIGIBLE", "resolution claims release eligibility")
    _require(boundary.get("approved_by") is None, "resolution must not set approved_by")
    _require(boundary.get("approved_at") is None, "resolution must not set approved_at")
    _require(
        boundary.get("baseline_approval_recorded") is False,
        "resolution must not record baseline approval",
    )
    generation = _strict_object(result.get("generation_boundary"), "generation_boundary")
    _require(generation.get("formal_deliverables_generated") is False, "formal deliverables were claimed")
    _require(generation.get("html_report_generated") is False, "HTML report was claimed")

    expected_handoff = {
        "status": "READY_FOR_POLICY_AUTHORING",
        "formal_deliverable_generation_status": "NOT_RUN",
        "html_report_generation_status": "NOT_RUN",
        "required_input_binding_sha256": expected_input_binding,
        "required_resolution_path": str(OUTPUT_PATH.relative_to(REPO_ROOT)),
        "rule": "다음 작성기는 이 파일의 resolution_content_sha256과 source binding을 검증하고 현재 입력으로 재계산한 결과와 일치한 뒤에만 기능별 정책 초안을 만든다. 기존 proposals만 단독으로 사용하지 않는다.",
    }
    _require(result.get("next_stage_handoff") == expected_handoff, "authoring handoff is stale")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="validate inputs and require the checked-in resolution to be byte-for-byte current",
    )
    args = parser.parse_args(argv)
    try:
        result = build_resolution()
        rendered = json_text(result)
        if args.check:
            _require(OUTPUT_PATH.is_file(), f"generated resolution is missing: {OUTPUT_PATH}")
            _require(
                OUTPUT_PATH.read_text(encoding="utf-8") == rendered,
                "generated resolution is stale; run the builder without --check",
            )
            print(
                "WalkSafe policy resolution check passed: "
                f"{result['summary']['reviewed']} reviews, "
                f"{result['summary']['feature_count']} features, "
                f"{result['summary']['dated_correction_count']} dated corrections"
            )
            return 0
        OUTPUT_PATH.write_text(rendered, encoding="utf-8")
        print(
            f"Wrote {OUTPUT_PATH.relative_to(REPO_ROOT)} "
            f"({result['summary']['reviewed']} reviews, "
            f"{result['summary']['feature_count']} features)"
        )
        return 0
    except (OSError, PolicyResolutionValidationError, KeyError, TypeError) as exc:
        print(f"WalkSafe policy resolution failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

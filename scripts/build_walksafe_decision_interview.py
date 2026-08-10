#!/usr/bin/env python3
"""Validate and build the WalkSafe feature-policy correction interview."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
CONTROL_DIR = REPO_ROOT / "docs" / "control"
QUESTIONNAIRE_DIR = CONTROL_DIR / "questionnaire"
INTERVIEW_DIR = CONTROL_DIR / "decision-interview"
SOURCE_RECORDS_DIR = QUESTIONNAIRE_DIR / "source-records"

ORIGINAL_QUESTIONS_PATH = QUESTIONNAIRE_DIR / "walksafe-project-decision-questions.json"
ORIGINAL_ANSWERS_PATH = (
    SOURCE_RECORDS_DIR / "walksafe-project-decisions-20260717-answers.json"
)
DELTA_QUESTIONS_PATH = QUESTIONNAIRE_DIR / "walksafe-answer-review-followups.json"
DELTA_ANSWERS_PATH = (
    SOURCE_RECORDS_DIR / "walksafe-android-baseline-delta-review.json"
)
INTEGRATED_ANALYSIS_PATH = QUESTIONNAIRE_DIR / "walksafe-integrated-baseline-analysis.json"
INTEGRATED_QUESTIONS_PATH = QUESTIONNAIRE_DIR / "walksafe-integrated-baseline-questions.json"
ARTIFACT_TYPES_PATH = CONTROL_DIR / "artifact-types.json"

FEATURE_POLICY_PATH = INTERVIEW_DIR / "walksafe-feature-policy-baseline.json"
RESPONSIBILITY_PATH = INTERVIEW_DIR / "walksafe-decision-responsibility.json"
OWNER_QUESTIONS_PATH = INTERVIEW_DIR / "walksafe-owner-decision-questions.json"
TEMPLATE_PATH = INTERVIEW_DIR / "feature-policy-review-template.html"
OUTPUT_PATH = INTERVIEW_DIR / "walksafe-feature-policy-and-decision-review-20260718.html"
DATA_PLACEHOLDER = "__WALKSAFE_DECISION_INTERVIEW_DATA__"

SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
ARTIFACT_ID_RE = re.compile(
    r"^(?:DOC|MGT|DSC|REQ|DES|DEV|TST|SEC|AIML|REL|OPS|WS|CLS)-\d{2}$"
)
RESPONSIBILITY_TYPES = {
    "owner_decision",
    "engineering_proposal",
    "measurement_gate",
    "expert_review",
    "generated_evidence",
    "already_confirmed",
}
DISPOSITIONS = {
    "ask_now",
    "show_proposal",
    "defer_measurement",
    "external_gate",
    "not_question",
}
FEATURE_STATUSES = {
    "CONFIRMED",
    "CONFIRMED_WITH_OPEN_DETAILS",
    "CONDITIONAL",
    "CONFLICTING",
    "UNRESOLVED",
}
IMPLEMENTATION_STATUSES = {
    "IMPLEMENTED",
    "PARTIALLY_IMPLEMENTED",
    "IMPLEMENTED_UNVERIFIED",
    "POLICY_ONLY",
    "NOT_IMPLEMENTED",
    "MEASURED_INSUFFICIENT",
    "UNKNOWN",
}
ANSWER_TYPES = {"single_choice", "multi_choice", "text", "number"}


class DecisionInterviewValidationError(ValueError):
    """Raised when an interview input violates its canonical contract."""


def _reject_constant(value: str) -> None:
    raise DecisionInterviewValidationError(f"non-finite JSON number is forbidden: {value}")


def _object_pairs_no_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DecisionInterviewValidationError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load_strict_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=_object_pairs_no_duplicates,
            parse_constant=_reject_constant,
        )
    except (OSError, json.JSONDecodeError) as error:
        raise DecisionInterviewValidationError(f"cannot read strict JSON {path}: {error}") from error
    if not isinstance(value, dict):
        raise DecisionInterviewValidationError(f"top level must be an object: {path}")
    return value


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def object_sha256(value: Any) -> str:
    payload = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _string(value: Any, label: str, *, allow_empty: bool = False) -> str:
    if not isinstance(value, str) or (not allow_empty and not value.strip()):
        raise DecisionInterviewValidationError(f"{label} must be a non-empty string")
    return value


def _string_list(value: Any, label: str, *, allow_empty: bool = True) -> list[str]:
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        raise DecisionInterviewValidationError(f"{label} must be a string list")
    if not allow_empty and not value:
        raise DecisionInterviewValidationError(f"{label} must not be empty")
    if len(value) != len(set(value)):
        raise DecisionInterviewValidationError(f"{label} contains duplicates")
    return value


def _object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise DecisionInterviewValidationError(f"{label} must be an object")
    return value


def _object_list(value: Any, label: str, *, allow_empty: bool = False) -> list[dict[str, Any]]:
    if not isinstance(value, list) or any(not isinstance(item, dict) for item in value):
        raise DecisionInterviewValidationError(f"{label} must be an object list")
    if not allow_empty and not value:
        raise DecisionInterviewValidationError(f"{label} must not be empty")
    return value


def _unique_ids(items: list[dict[str, Any]], label: str, field: str = "id") -> set[str]:
    ids = [_string(item.get(field), f"{label}.{field}") for item in items]
    if len(ids) != len(set(ids)):
        raise DecisionInterviewValidationError(f"{label} contains duplicate {field} values")
    return set(ids)


def _source_ids(
    original_questions: dict[str, Any],
    delta_questions: dict[str, Any],
    integrated_analysis: dict[str, Any],
    integrated_questions: dict[str, Any],
) -> set[str]:
    values = {
        item["id"] for item in original_questions.get("questions", [])
    } | {item["id"] for item in delta_questions.get("questions", [])}
    values |= {item["id"] for item in integrated_questions.get("questions", [])}
    for field in (
        "directives", "domain_reviews", "findings", "runtime_evidence",
        "external_constraints",
    ):
        values |= {item["id"] for item in integrated_analysis.get(field, [])}
    values |= {item["id"] for item in integrated_questions.get("consistency_rules", [])}
    values |= {
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
    return values


def validate_feature_policy(
    policy: dict[str, Any], *, artifact_ids: set[str], source_ids: set[str]
) -> None:
    required = {
        "schema_version", "baseline_id", "title", "as_of", "language", "purpose",
        "audience", "authority_order", "status_definitions", "reading_guide",
        "source_materials", "summary", "areas", "features",
    }
    if set(policy) != required:
        raise DecisionInterviewValidationError(
            f"feature policy fields invalid: missing={sorted(required-set(policy))}; "
            f"unexpected={sorted(set(policy)-required)}"
        )
    areas = _object_list(policy["areas"], "feature policy areas")
    features = _object_list(policy["features"], "feature policy features")
    area_ids = _unique_ids(areas, "feature policy areas")
    feature_ids = _unique_ids(features, "feature policy features")
    if len(areas) < 16:
        raise DecisionInterviewValidationError("feature policy must cover at least 16 areas")
    if len(features) < 40:
        raise DecisionInterviewValidationError("feature policy must contain at least 40 features")
    if sorted(item.get("order") for item in areas) != list(range(1, len(areas) + 1)):
        raise DecisionInterviewValidationError("feature area order must be contiguous from 1")
    listed_features: list[str] = []
    for area in areas:
        _string(area.get("title"), f"area {area.get('id')} title")
        _string(area.get("plain_scope"), f"area {area.get('id')} plain_scope")
        listed_features.extend(_string_list(area.get("feature_ids"), "area feature_ids", allow_empty=False))
    if set(listed_features) != feature_ids or len(listed_features) != len(feature_ids):
        raise DecisionInterviewValidationError("areas must list every feature exactly once")
    for area in areas:
        area_features = [item for item in features if item.get("area_id") == area["id"]]
        if sorted(item.get("order") for item in area_features) != list(
            range(1, len(area_features) + 1)
        ):
            raise DecisionInterviewValidationError(
                f"feature order in {area['id']} must be contiguous from 1"
            )

    seen_source_refs: set[str] = set()
    for feature in features:
        feature_id = _string(feature.get("id"), "feature id")
        if feature.get("area_id") not in area_ids:
            raise DecisionInterviewValidationError(f"{feature_id} has unknown area_id")
        for field in ("name", "plain_summary", "user_purpose"):
            _string(feature.get(field), f"{feature_id}.{field}")
        for field in ("start_conditions", "normal_flow", "failure_behavior"):
            _string_list(feature.get(field), f"{feature_id}.{field}", allow_empty=False)
        guidance = _object(feature.get("user_guidance"), f"{feature_id}.user_guidance")
        if set(guidance) != {"screen", "speech", "vibration"}:
            raise DecisionInterviewValidationError(f"{feature_id}.user_guidance fields invalid")
        for field in guidance:
            _string_list(guidance[field], f"{feature_id}.user_guidance.{field}")
        permissions = _object_list(
            feature.get("required_permissions"), f"{feature_id}.required_permissions", allow_empty=True
        )
        for permission in permissions:
            if set(permission) != {"name", "requirement", "plain_reason"}:
                raise DecisionInterviewValidationError(f"{feature_id} permission fields invalid")
            for field in permission:
                _string(permission[field], f"{feature_id}.permission.{field}")
        boundary = _object(feature.get("execution_boundary"), f"{feature_id}.execution_boundary")
        if set(boundary) != {"on_device", "server", "external"}:
            raise DecisionInterviewValidationError(f"{feature_id}.execution_boundary fields invalid")
        for field in boundary:
            _string_list(boundary[field], f"{feature_id}.execution_boundary.{field}")
        data_handling = _object(feature.get("data_handling"), f"{feature_id}.data_handling")
        expected_data_fields = {
            "stored_on_device", "sent_to_server", "delete_from_device", "server_retention"
        }
        if set(data_handling) != expected_data_fields:
            raise DecisionInterviewValidationError(f"{feature_id}.data_handling fields invalid")
        for field in data_handling:
            _string_list(data_handling[field], f"{feature_id}.data_handling.{field}")
        feature_policy = _object(feature.get("policy"), f"{feature_id}.policy")
        if set(feature_policy) != {
            "overall_status", "confirmed", "conditional", "conflicts", "unresolved"
        }:
            raise DecisionInterviewValidationError(f"{feature_id}.policy fields invalid")
        if feature_policy["overall_status"] not in FEATURE_STATUSES:
            raise DecisionInterviewValidationError(f"{feature_id} has invalid policy status")
        for field in ("confirmed", "conditional", "conflicts", "unresolved"):
            _string_list(feature_policy[field], f"{feature_id}.policy.{field}")
        implementation = _object(feature.get("implementation"), f"{feature_id}.implementation")
        if set(implementation) != {"status", "plain_status", "evidence_refs"}:
            raise DecisionInterviewValidationError(f"{feature_id}.implementation fields invalid")
        if implementation["status"] not in IMPLEMENTATION_STATUSES:
            raise DecisionInterviewValidationError(f"{feature_id} has invalid implementation status")
        _string(implementation["plain_status"], f"{feature_id}.implementation.plain_status")
        _string_list(implementation["evidence_refs"], f"{feature_id}.implementation.evidence_refs")
        refs = _string_list(feature.get("source_refs"), f"{feature_id}.source_refs", allow_empty=False)
        invalid_refs = sorted(set(refs) - source_ids)
        if invalid_refs:
            raise DecisionInterviewValidationError(f"{feature_id} has invalid source refs: {invalid_refs}")
        seen_source_refs.update(refs)
        affects = _string_list(
            feature.get("affected_deliverables"), f"{feature_id}.affected_deliverables", allow_empty=False
        )
        invalid_artifacts = sorted(set(affects) - artifact_ids)
        if invalid_artifacts:
            raise DecisionInterviewValidationError(
                f"{feature_id} has invalid affected deliverables: {invalid_artifacts}"
            )
    summary = _object(policy["summary"], "feature policy summary")
    if summary.get("area_count") != len(areas) or summary.get("feature_count") != len(features):
        raise DecisionInterviewValidationError("feature policy summary counts are stale")
    expected_policy_counts = {
        status: sum(item["policy"]["overall_status"] == status for item in features)
        for status in FEATURE_STATUSES
    }
    expected_implementation_counts = {
        status: sum(item["implementation"]["status"] == status for item in features)
        for status in IMPLEMENTATION_STATUSES
    }
    if summary.get("policy_status_counts") != expected_policy_counts:
        raise DecisionInterviewValidationError("feature policy status counts are stale")
    if summary.get("implementation_status_counts") != expected_implementation_counts:
        raise DecisionInterviewValidationError("feature implementation status counts are stale")
    missing_directives = sorted(
        {f"DIR-{number:03d}" for number in range(1, 17)} - seen_source_refs
    )
    if missing_directives:
        raise DecisionInterviewValidationError(
            f"feature policy does not trace every latest directive: {missing_directives}"
        )


def validate_responsibility(
    responsibility: dict[str, Any], *, ibq_ids: set[str]
) -> tuple[dict[str, dict[str, Any]], dict[str, dict[str, Any]]]:
    required = {
        "schema_version", "title", "as_of", "source", "responsibility_types",
        "dispositions", "canonical_decisions", "ibq_mappings", "coverage",
    }
    if set(responsibility) != required:
        raise DecisionInterviewValidationError("responsibility top-level fields invalid")
    declared_types = _object_list(
        responsibility["responsibility_types"], "responsibility type definitions"
    )
    declared_dispositions = _object_list(
        responsibility["dispositions"], "disposition definitions"
    )
    if _unique_ids(declared_types, "responsibility type definitions") != RESPONSIBILITY_TYPES:
        raise DecisionInterviewValidationError("responsibility type definitions are incomplete")
    if _unique_ids(declared_dispositions, "disposition definitions") != DISPOSITIONS:
        raise DecisionInterviewValidationError("disposition definitions are incomplete")
    canonical = _object_list(responsibility["canonical_decisions"], "canonical decisions")
    mappings = _object_list(responsibility["ibq_mappings"], "IBQ mappings")
    canonical_ids = _unique_ids(canonical, "canonical decisions")
    mapping_ids = _unique_ids(mappings, "IBQ mappings", "ibq_id")
    if mapping_ids != ibq_ids:
        raise DecisionInterviewValidationError(
            f"IBQ mapping coverage invalid: missing={sorted(ibq_ids-mapping_ids)}; "
            f"unexpected={sorted(mapping_ids-ibq_ids)}"
        )
    canonical_by_id = {item["id"]: item for item in canonical}
    mapping_by_id = {item["ibq_id"]: item for item in mappings}
    for item in canonical:
        decision_id = item["id"]
        if item.get("responsibility_type") not in RESPONSIBILITY_TYPES:
            raise DecisionInterviewValidationError(f"{decision_id} responsibility type invalid")
        if item.get("disposition") not in DISPOSITIONS:
            raise DecisionInterviewValidationError(f"{decision_id} disposition invalid")
        source_ibqs = _string_list(item.get("source_ibq_ids"), f"{decision_id}.source_ibq_ids", allow_empty=False)
        if not set(source_ibqs) <= ibq_ids:
            raise DecisionInterviewValidationError(f"{decision_id} references unknown IBQ")
        question_id = item.get("question_id")
        if question_id is not None:
            _string(question_id, f"{decision_id}.question_id")
        if item["disposition"] == "ask_now" and item["responsibility_type"] != "owner_decision":
            raise DecisionInterviewValidationError(f"{decision_id}: only owner decisions may be asked now")
        if item["responsibility_type"] == "already_confirmed" and item["disposition"] != "not_question":
            raise DecisionInterviewValidationError(f"{decision_id}: confirmed decisions must not be asked")
    for item in mappings:
        ibq_id = item["ibq_id"]
        decision_id = item.get("canonical_decision_id")
        if decision_id not in canonical_ids:
            raise DecisionInterviewValidationError(f"{ibq_id} references unknown canonical decision")
        decision = canonical_by_id[decision_id]
        if item.get("responsibility_type") != decision["responsibility_type"]:
            raise DecisionInterviewValidationError(f"{ibq_id} responsibility differs from canonical decision")
        if item.get("disposition") != decision["disposition"]:
            raise DecisionInterviewValidationError(f"{ibq_id} disposition differs from canonical decision")
        if ibq_id not in decision["source_ibq_ids"]:
            raise DecisionInterviewValidationError(f"{ibq_id} is absent from canonical source list")
    covered_by_canonical = [ibq for item in canonical for ibq in item["source_ibq_ids"]]
    if set(covered_by_canonical) != ibq_ids or len(covered_by_canonical) != len(ibq_ids):
        raise DecisionInterviewValidationError("canonical decisions must partition the 144 IBQs exactly once")
    coverage = _object(responsibility["coverage"], "responsibility coverage")
    if coverage.get("source_question_count") != len(ibq_ids):
        raise DecisionInterviewValidationError("responsibility source count is stale")
    if coverage.get("mapped_question_count") != len(ibq_ids):
        raise DecisionInterviewValidationError("responsibility mapped count is stale")
    if coverage.get("unmapped_ibq_ids") or coverage.get("duplicate_ibq_ids"):
        raise DecisionInterviewValidationError("responsibility coverage reports mapping errors")
    if coverage.get("canonical_decision_count") != len(canonical):
        raise DecisionInterviewValidationError("canonical decision count is stale")
    ask_count = sum(item["disposition"] == "ask_now" for item in canonical)
    if coverage.get("ask_now_count") != ask_count:
        raise DecisionInterviewValidationError("ask-now count is stale")
    return canonical_by_id, mapping_by_id


def _has_activation_cycle(questions: list[dict[str, Any]]) -> bool:
    graph = {
        item["id"]: ([item["activation"]["question_id"]] if item.get("activation") else [])
        for item in questions
    }
    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(node: str) -> bool:
        if node in visiting:
            return True
        if node in visited:
            return False
        visiting.add(node)
        if any(visit(parent) for parent in graph[node]):
            return True
        visiting.remove(node)
        visited.add(node)
        return False

    return any(visit(node) for node in graph)


def validate_owner_questions(
    questionnaire: dict[str, Any], *, canonical_by_id: dict[str, dict[str, Any]],
    artifact_ids: set[str], bundle_ids: set[str], artifact_bundle_by_id: dict[str, str],
    ibq_ids: set[str]
) -> None:
    required = {
        "schema_version", "title", "as_of", "purpose", "source", "groups",
        "questions", "coverage",
    }
    if set(questionnaire) != required:
        raise DecisionInterviewValidationError("owner questionnaire top-level fields invalid")
    groups = _object_list(questionnaire["groups"], "owner question groups")
    questions = _object_list(questionnaire["questions"], "owner questions", allow_empty=True)
    group_ids = _unique_ids(groups, "owner question groups")
    question_ids = _unique_ids(questions, "owner questions")
    if sorted(item.get("order") for item in groups) != list(range(1, len(groups) + 1)):
        raise DecisionInterviewValidationError("owner question group order is not contiguous")
    if sorted(item.get("order") for item in questions) != list(range(1, len(questions) + 1)):
        raise DecisionInterviewValidationError("owner question order is not contiguous")
    canonical_question_ids = [item.get("canonical_decision_id") for item in questions]
    if len(canonical_question_ids) != len(set(canonical_question_ids)):
        raise DecisionInterviewValidationError("owner questions repeat a canonical decision")
    expected = {
        key for key, value in canonical_by_id.items() if value["disposition"] == "ask_now"
    }
    if set(canonical_question_ids) != expected:
        raise DecisionInterviewValidationError(
            f"owner question coverage differs from ask-now decisions: "
            f"missing={sorted(expected-set(canonical_question_ids))}; "
            f"unexpected={sorted(set(canonical_question_ids)-expected)}"
        )
    for question in questions:
        question_id = question["id"]
        decision = canonical_by_id[question["canonical_decision_id"]]
        if question.get("group_id") not in group_ids:
            raise DecisionInterviewValidationError(f"{question_id} has unknown group")
        if question.get("answer_type") not in ANSWER_TYPES:
            raise DecisionInterviewValidationError(f"{question_id} answer type invalid")
        for field in ("title", "prompt", "explanation", "detail_prompt"):
            _string(question.get(field), f"{question_id}.{field}", allow_empty=field == "detail_prompt")
        if not isinstance(question.get("required"), bool):
            raise DecisionInterviewValidationError(f"{question_id}.required must be boolean")
        options = _object_list(
            question.get("options"), f"{question_id}.options",
            allow_empty=question["answer_type"] in {"text", "number"},
        )
        option_ids = _unique_ids(options, f"{question_id}.options") if options else set()
        for option in options:
            for field in ("label", "meaning", "impact"):
                _string(option.get(field), f"{question_id}.option.{field}")
        recommendation = _object(question.get("recommendation"), f"{question_id}.recommendation")
        if set(recommendation) != {"option_id", "label", "reason"}:
            raise DecisionInterviewValidationError(f"{question_id}.recommendation fields invalid")
        if recommendation["option_id"] not in option_ids:
            raise DecisionInterviewValidationError(f"{question_id} recommendation option invalid")
        source_ibqs = _string_list(question.get("source_ibq_ids"), f"{question_id}.source_ibq_ids", allow_empty=False)
        if not set(source_ibqs) <= ibq_ids or set(source_ibqs) != set(decision["source_ibq_ids"]):
            raise DecisionInterviewValidationError(f"{question_id} source IBQs differ from canonical decision")
        affects = _string_list(question.get("affects"), f"{question_id}.affects", allow_empty=False)
        if not set(affects) <= artifact_ids:
            raise DecisionInterviewValidationError(f"{question_id} has invalid affected artifact")
        bundles = _string_list(question.get("document_bundles"), f"{question_id}.document_bundles", allow_empty=False)
        if not set(bundles) <= bundle_ids:
            raise DecisionInterviewValidationError(f"{question_id} has invalid document bundle")
        expected_bundles = {artifact_bundle_by_id[item] for item in affects}
        if set(bundles) != expected_bundles:
            raise DecisionInterviewValidationError(
                f"{question_id} document bundles do not match affected artifacts"
            )
        activation = question.get("activation")
        if activation is not None:
            activation = _object(activation, f"{question_id}.activation")
            if set(activation) != {"question_id", "option_ids"}:
                raise DecisionInterviewValidationError(f"{question_id}.activation fields invalid")
            parent_id = activation["question_id"]
            if parent_id not in question_ids or parent_id == question_id:
                raise DecisionInterviewValidationError(f"{question_id} activation parent invalid")
            parent = next(item for item in questions if item["id"] == parent_id)
            parent_options = {item["id"] for item in parent["options"]}
            if not set(activation["option_ids"]) <= parent_options:
                raise DecisionInterviewValidationError(f"{question_id} activation option invalid")
        if decision.get("question_id") != question_id:
            raise DecisionInterviewValidationError(
                f"{question_id} differs from its canonical decision question_id"
            )
    if _has_activation_cycle(questions):
        raise DecisionInterviewValidationError("owner question activation graph has a cycle")
    coverage = _object(questionnaire["coverage"], "owner question coverage")
    if coverage.get("question_count") != len(questions) or len(questions) > coverage.get("target_max", -1):
        raise DecisionInterviewValidationError("owner question count/target is stale")
    if set(coverage.get("canonical_decision_ids", [])) != set(canonical_question_ids):
        raise DecisionInterviewValidationError("owner question canonical coverage is stale")
    if coverage.get("duplicate_canonical_decision_ids"):
        raise DecisionInterviewValidationError("owner question coverage reports duplicates")
    if coverage.get("unresolved_owner_decision_ids"):
        raise DecisionInterviewValidationError("owner question coverage reports missing decisions")
    if coverage.get("invalid_activation_refs"):
        raise DecisionInterviewValidationError("owner question coverage reports invalid activation")


def build_report(
    original_questions: dict[str, Any], original_answers: dict[str, Any],
    delta_questions: dict[str, Any], delta_answers: dict[str, Any],
    integrated_analysis: dict[str, Any], integrated_questions: dict[str, Any],
    artifact_catalog: dict[str, Any], feature_policy: dict[str, Any],
    responsibility: dict[str, Any], owner_questions: dict[str, Any],
) -> dict[str, Any]:
    artifacts = _object_list(artifact_catalog.get("artifact_types"), "artifact catalog")
    if len(artifacts) != 257:
        raise DecisionInterviewValidationError("artifact catalog must contain 257 types")
    artifact_ids = {item["display_code"] for item in artifacts}
    artifact_bundle_by_id = {
        item["display_code"]: item["recommended_bundle_id"] for item in artifacts
    }
    if any(not ARTIFACT_ID_RE.fullmatch(item) for item in artifact_ids):
        raise DecisionInterviewValidationError("artifact catalog contains invalid display IDs")
    bundle_ids = set(artifact_catalog.get("metadata", {}).get("bundle_ids", []))
    if len(bundle_ids) != 40:
        raise DecisionInterviewValidationError("artifact catalog must contain 40 bundle IDs")
    ibq_ids = {item["id"] for item in integrated_questions.get("questions", [])}
    if len(ibq_ids) != 144:
        raise DecisionInterviewValidationError("integrated questionnaire must contain 144 questions")
    integrated_sha = file_sha256(INTEGRATED_QUESTIONS_PATH)
    for label, value in (
        ("responsibility", responsibility.get("source")),
        ("owner questionnaire", owner_questions.get("source")),
    ):
        source = _object(value, f"{label} source")
        if source.get("sha256") != integrated_sha:
            raise DecisionInterviewValidationError(f"{label} is bound to a stale source hash")
    sources = _source_ids(original_questions, delta_questions, integrated_analysis, integrated_questions)
    validate_feature_policy(feature_policy, artifact_ids=artifact_ids, source_ids=sources)
    canonical_by_id, _ = validate_responsibility(responsibility, ibq_ids=ibq_ids)
    validate_owner_questions(
        owner_questions, canonical_by_id=canonical_by_id, artifact_ids=artifact_ids,
        bundle_ids=bundle_ids, artifact_bundle_by_id=artifact_bundle_by_id, ibq_ids=ibq_ids,
    )

    source_paths = {
        "original_questions": ORIGINAL_QUESTIONS_PATH,
        "original_answers": ORIGINAL_ANSWERS_PATH,
        "delta_questions": DELTA_QUESTIONS_PATH,
        "delta_answers": DELTA_ANSWERS_PATH,
        "integrated_analysis": INTEGRATED_ANALYSIS_PATH,
        "integrated_questions": INTEGRATED_QUESTIONS_PATH,
        "artifact_catalog": ARTIFACT_TYPES_PATH,
        "feature_policy": FEATURE_POLICY_PATH,
        "responsibility": RESPONSIBILITY_PATH,
        "owner_questions": OWNER_QUESTIONS_PATH,
    }
    binding_core = {name: file_sha256(path) for name, path in source_paths.items()}
    if not all(SHA256_RE.fullmatch(value) for value in binding_core.values()):
        raise DecisionInterviewValidationError("source binding contains an invalid SHA-256")
    binding = {**binding_core, "storage_binding_hash": object_sha256(binding_core)}

    queue_order = [
        "owner_decision", "engineering_proposal", "measurement_gate", "expert_review",
        "generated_evidence", "already_confirmed",
    ]
    queues = {
        queue: [item for item in responsibility["canonical_decisions"] if item["responsibility_type"] == queue]
        for queue in queue_order
    }
    artifact_trace: dict[str, dict[str, Any]] = {}
    for artifact in artifacts:
        artifact_trace[artifact["display_code"]] = {
            "id": artifact["display_code"],
            "title": artifact["title"],
            "bundle_id": artifact["recommended_bundle_id"],
            "feature_ids": [],
            "question_ids": [],
        }
    for feature in feature_policy["features"]:
        for artifact_id in feature["affected_deliverables"]:
            artifact_trace[artifact_id]["feature_ids"].append(feature["id"])
    for question in owner_questions["questions"]:
        for artifact_id in question["affects"]:
            artifact_trace[artifact_id]["question_ids"].append(question["id"])

    original_answer_count = len(original_answers.get("answers", {}))
    delta_answer_count = len(delta_answers.get("followup_answers", {}))
    return {
        "metadata": {
            "schema_version": "walksafe.feature-policy-decision-interview-report.v1",
            "title": "WalkSafe 기능 정책 정리와 남은 결정",
            "as_of": feature_policy["as_of"],
            "completion_states": [
                {
                    "id": "QUESTIONNAIRE_COMPLETE",
                    "label": "수정·질문 입력 완료",
                    "meaning": "활성 필수 사용자 결정과 수정 요청을 형식상 작성했습니다.",
                },
                {
                    "id": "BASELINE_APPROVED",
                    "label": "문서 기준선 승인",
                    "meaning": "기술 제안·실측·전문가 검토를 반영하고 프로젝트 관리자가 승인했습니다.",
                },
                {
                    "id": "RELEASE_ELIGIBLE",
                    "label": "출시 가능",
                    "meaning": "구현·시험·법률·안전·운영 출시 gate를 모두 통과했습니다.",
                },
            ],
            "audit_appendix": "walksafe-integrated-baseline-questionnaire-20260718.html",
        },
        "binding": binding,
        "source_summary": {
            "original_answered": original_answer_count,
            "original_total": len(original_questions.get("questions", [])),
            "delta_answered": delta_answer_count,
            "delta_total": len(delta_questions.get("questions", [])),
            "audit_question_total": len(ibq_ids),
            "artifact_type_total": len(artifact_ids),
            "document_bundle_total": len(bundle_ids),
        },
        "feature_policy": feature_policy,
        "responsibility": responsibility,
        "owner_questionnaire": owner_questions,
        "queues": queues,
        "artifact_trace": list(artifact_trace.values()),
    }


def render_report(report: dict[str, Any], template: str) -> str:
    if template.count(DATA_PLACEHOLDER) != 1:
        raise DecisionInterviewValidationError("template must contain the data placeholder exactly once")
    payload = json.dumps(
        report, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False
    ).replace("&", "\\u0026").replace("<", "\\u003c").replace(">", "\\u003e")
    return template.replace(DATA_PLACEHOLDER, payload)


def load_and_build() -> dict[str, Any]:
    inputs = [
        load_strict_json(path) for path in (
            ORIGINAL_QUESTIONS_PATH, ORIGINAL_ANSWERS_PATH, DELTA_QUESTIONS_PATH,
            DELTA_ANSWERS_PATH, INTEGRATED_ANALYSIS_PATH, INTEGRATED_QUESTIONS_PATH,
            ARTIFACT_TYPES_PATH, FEATURE_POLICY_PATH, RESPONSIBILITY_PATH, OWNER_QUESTIONS_PATH,
        )
    ]
    return build_report(*inputs)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify canonical output freshness")
    parser.add_argument("--output", type=Path, default=OUTPUT_PATH, help="HTML output path")
    args = parser.parse_args(argv)
    try:
        report = load_and_build()
        rendered = render_report(report, TEMPLATE_PATH.read_text(encoding="utf-8"))
        if args.check:
            if not args.output.exists() or args.output.read_text(encoding="utf-8") != rendered:
                raise DecisionInterviewValidationError(f"generated HTML is stale or missing: {args.output}")
            print(
                f"PASS: {len(report['feature_policy']['features'])} features, "
                f"{len(report['owner_questionnaire']['questions'])} owner questions, "
                "144/144 classified"
            )
            return 0
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
        print(f"WROTE: {args.output}")
        return 0
    except (DecisionInterviewValidationError, OSError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

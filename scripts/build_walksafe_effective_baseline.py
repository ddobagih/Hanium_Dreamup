#!/usr/bin/env python3
"""Build the WalkSafe effective decision register and approval-review candidate."""

from __future__ import annotations

import argparse
from collections import Counter
import copy
import json
from pathlib import Path
import re
import sys
from typing import Any

try:
    from scripts import build_walksafe_decision_interview as interview
except ModuleNotFoundError:  # Direct execution from the scripts directory.
    import build_walksafe_decision_interview as interview  # type: ignore[no-redef]


REPO_ROOT = Path(__file__).resolve().parents[1]
INTERVIEW_DIR = REPO_ROOT / "docs" / "control" / "decision-interview"
INTEGRATED_QUESTIONS_PATH = (
    REPO_ROOT / "docs" / "control" / "questionnaire" / "walksafe-integrated-baseline-questions.json"
)
CONTROLLED_ANSWERS_PATH = (
    INTERVIEW_DIR / "walksafe-feature-policy-decisions-20260718-answers.json"
)
RULES_PATH = INTERVIEW_DIR / "walksafe-effective-baseline-rules.json"
DECISION_TRACE_PATH = INTERVIEW_DIR / "walksafe-canonical-decision-trace.json"
REGISTER_OUTPUT_PATH = INTERVIEW_DIR / "walksafe-effective-decision-register.json"
POLICY_OUTPUT_PATH = INTERVIEW_DIR / "walksafe-feature-policy-effective-candidate.json"
REVIEW_OUTPUT_PATH = INTERVIEW_DIR / "walksafe-effective-policy-approval-review-20260719.md"

REPORT_SOURCE_PATHS = {
    "original_questions": interview.ORIGINAL_QUESTIONS_PATH,
    "original_answers": interview.ORIGINAL_ANSWERS_PATH,
    "delta_questions": interview.DELTA_QUESTIONS_PATH,
    "delta_answers": interview.DELTA_ANSWERS_PATH,
    "integrated_analysis": interview.INTEGRATED_ANALYSIS_PATH,
    "integrated_questions": interview.INTEGRATED_QUESTIONS_PATH,
    "artifact_catalog": interview.ARTIFACT_TYPES_PATH,
    "feature_policy": interview.FEATURE_POLICY_PATH,
    "responsibility": interview.RESPONSIBILITY_PATH,
    "owner_questions": interview.OWNER_QUESTIONS_PATH,
}

ALLOWED_FEATURE_STATUSES = {
    "CONFIRMED",
    "CONFIRMED_WITH_OPEN_DETAILS",
    "CONDITIONAL",
    "CONFLICTING",
    "UNRESOLVED",
}
SOURCE_CLASSES = {
    "CANONICAL",
    "GENERATED",
    "IMPLEMENTATION_EVIDENCE",
    "HISTORY",
    "EXTERNAL",
    "UNCLASSIFIED",
}
ARTIFACT_FORMS = {
    "CANONICAL_DOCUMENT",
    "CONTROLLED_ARTIFACT",
    "SECTION",
    "REGISTER",
    "GENERATED_EVIDENCE",
    "EXTERNAL_RECORD",
}
EVIDENCE_CLASSIFICATIONS = {
    "USER_CONFIRMED",
    "CONFIRMED_BY_CODE",
    "CURRENT_CANDIDATE",
    "CONFLICTING",
    "STALE",
    "UNKNOWN",
}
ADOPTION_TRUST_LEVELS = {"A", "B", "C", "D", "E", "U"}
SOURCE_PROFILES = {
    "original_questions": ("GENERATED", "CONTROLLED_ARTIFACT", "CURRENT_CANDIDATE", "D"),
    "original_answers": ("EXTERNAL", "EXTERNAL_RECORD", "USER_CONFIRMED", "D"),
    "delta_questions": ("GENERATED", "CONTROLLED_ARTIFACT", "CURRENT_CANDIDATE", "D"),
    "delta_answers": ("EXTERNAL", "EXTERNAL_RECORD", "USER_CONFIRMED", "D"),
    "integrated_analysis": ("GENERATED", "CONTROLLED_ARTIFACT", "CURRENT_CANDIDATE", "D"),
    "integrated_questions": ("GENERATED", "CONTROLLED_ARTIFACT", "CURRENT_CANDIDATE", "D"),
    "artifact_catalog": ("GENERATED", "CONTROLLED_ARTIFACT", "CURRENT_CANDIDATE", "D"),
    "feature_policy": ("GENERATED", "CONTROLLED_ARTIFACT", "CURRENT_CANDIDATE", "D"),
    "responsibility": ("GENERATED", "REGISTER", "CURRENT_CANDIDATE", "D"),
    "owner_questions": ("GENERATED", "CONTROLLED_ARTIFACT", "CURRENT_CANDIDATE", "D"),
}
EXPECTED_ROLE_GATE = {
    "owner_decision": ("RESOLVED", "OWNER_CONFIRMATION", "PASS"),
    "already_confirmed": ("RESOLVED", "SOURCE_REVALIDATION", "PASS"),
    "engineering_proposal": ("PENDING_PROPOSAL", "ENGINEERING_REVIEW", "NOT_RUN"),
    "measurement_gate": ("PENDING_MEASUREMENT", "MEASUREMENT", "NOT_RUN"),
    "expert_review": ("PENDING_EXPERT_REVIEW", "EXPERT_REVIEW", "NOT_RUN"),
    "generated_evidence": ("PENDING_EVIDENCE", "GENERATED_EVIDENCE", "NOT_RUN"),
}
LIST_PATCH_FIELDS = {
    "start_conditions",
    "normal_flow",
    "failure_behavior",
}
SET_PATCH_FIELDS = LIST_PATCH_FIELDS | {"required_permissions"}
POLICY_LIST_FIELDS = {
    "confirmed",
    "conditional",
    "conflicts",
    "unresolved",
}
ALLOWED_UPDATE_FIELDS = {
    "feature_id",
    "set_plain_summary",
    "set_overall_status",
    "set_user_guidance",
    "set_execution_boundary",
    "set_data_handling",
    *{f"set_{field}" for field in SET_PATCH_FIELDS},
    *{f"append_{field}" for field in LIST_PATCH_FIELDS | POLICY_LIST_FIELDS},
    *{f"remove_{field}" for field in LIST_PATCH_FIELDS | POLICY_LIST_FIELDS},
}
POLICY_STATUS_LABELS = {
    "CONFIRMED": "확정",
    "CONFIRMED_WITH_OPEN_DETAILS": "큰 방향 확정·세부 미정",
    "CONDITIONAL": "조건부",
    "CONFLICTING": "충돌 있음",
    "UNRESOLVED": "미정",
}


class EffectiveBaselineValidationError(ValueError):
    """Raised when an effective-baseline input or output violates its contract."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise EffectiveBaselineValidationError(message)


def _strict_object(value: Any, label: str) -> dict[str, Any]:
    _require(isinstance(value, dict), f"{label} must be an object")
    return value


def _strict_list(value: Any, label: str) -> list[Any]:
    _require(isinstance(value, list), f"{label} must be a list")
    return value


def _strict_string(value: Any, label: str) -> str:
    _require(isinstance(value, str) and bool(value.strip()), f"{label} must be a string")
    return value


def _unique(values: list[str], label: str) -> list[str]:
    _require(len(values) == len(set(values)), f"{label} contains duplicates")
    return values


def _json_text(value: dict[str, Any]) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n"


def _content_sha(value: dict[str, Any]) -> str:
    return interview.object_sha256(value)


def _source_ref_id(key: str) -> str:
    return "SRC-" + key.replace("_", "-").upper()


def _decision_id(canonical_decision_id: str) -> str:
    _require(canonical_decision_id.startswith("CD-"), f"invalid canonical decision ID: {canonical_decision_id}")
    return "DEC-" + canonical_decision_id.removeprefix("CD-")


def _normalize_trace_source_refs(raw_refs: list[str]) -> list[str]:
    normalized: list[str] = []
    for raw_ref in raw_refs:
        core = raw_ref.split(":", 1)[0]
        if core.startswith("USER-"):
            continue
        if core.startswith("SRC-"):
            source_id, separator, record_id = core.partition("#")
            _require(bool(separator and record_id), f"trace source ref has no record: {raw_ref}")
            if source_id == "SRC-ORIGINAL-ANSWERS" and not record_id.startswith(("answers.", "notes.")):
                record_id = f"answers.{record_id}"
            elif source_id == "SRC-DELTA-ANSWERS" and not record_id.startswith(("followup_answers.", "followup_notes.")):
                record_id = f"followup_answers.{record_id}"
            value = f"{source_id}#{record_id}"
        elif core.startswith("Q-"):
            value = f"SRC-ORIGINAL-ANSWERS#answers.{core}"
        elif core.startswith("FUP-"):
            value = f"SRC-DELTA-ANSWERS#followup_answers.{core}"
        elif core.startswith("DIR-"):
            value = f"SRC-INTEGRATED-ANALYSIS#directives.{core}"
        else:
            raise EffectiveBaselineValidationError(f"unknown trace source ref namespace: {raw_ref}")
        if value not in normalized:
            normalized.append(value)
    return normalized


def _validate_answer_intake(
    report: dict[str, Any],
    answers: dict[str, Any],
    rules: dict[str, Any],
    questions: dict[str, Any],
) -> None:
    intake = _strict_object(rules.get("answer_intake"), "answer_intake")
    expected_hash = _strict_string(intake.get("sha256"), "answer_intake.sha256")
    controlled_path = _strict_string(
        intake.get("controlled_path"), "answer_intake.controlled_path"
    )
    _require(
        (REPO_ROOT / controlled_path).resolve() == CONTROLLED_ANSWERS_PATH.resolve(),
        "registered controlled owner-answer path differs from the build input",
    )
    _require(
        interview.file_sha256(CONTROLLED_ANSWERS_PATH) == expected_hash,
        "controlled owner-answer hash differs from the registered hash",
    )
    _strict_string(intake.get("external_path"), "answer_intake.external_path")
    _require(
        answers.get("schema_version") == "walksafe.owner-decision-review-answers.v1",
        "owner-answer schema_version is invalid",
    )
    _require(answers.get("status") == "QUESTIONNAIRE_COMPLETE", "owner answers are incomplete")
    _require(answers.get("baseline_status") == "NOT_APPROVED", "answers must not claim approval")
    _require(answers.get("unresolved_required") == [], "owner answers contain unresolved required items")
    _require(answers.get("correction_errors") == [], "owner answers contain correction errors")
    _require(answers.get("feature_corrections") == {}, "feature corrections require a separate reviewed patch")
    enabled = _strict_object(answers.get("correction_enabled"), "correction_enabled")
    _require(not any(value is True for value in enabled.values()), "an enabled feature correction is missing")
    _require(
        answers.get("storage_binding_hash") == report["binding"]["storage_binding_hash"],
        "owner answers are bound to a different source set",
    )
    answer_bindings = _strict_object(answers.get("source_bindings"), "source_bindings")
    for key, expected in report["binding"].items():
        _require(answer_bindings.get(key) == expected, f"owner-answer source binding is stale: {key}")

    answer_values = _strict_object(answers.get("answers"), "answers")
    question_list = _strict_list(questions.get("questions"), "owner questions")
    question_ids = [item["id"] for item in question_list]
    _require(set(answer_values) == set(question_ids), "owner-answer question IDs do not match")
    for question in question_list:
        options = {item["id"] for item in question["options"]}
        _require(answer_values[question["id"]] in options, f"invalid owner answer: {question['id']}")


def _validate_feature_update_shape(update: Any, label: str) -> dict[str, Any]:
    update = _strict_object(update, label)
    unknown = set(update) - ALLOWED_UPDATE_FIELDS
    _require(not unknown, f"unknown feature update fields for {label}: {sorted(unknown)}")
    _strict_string(update.get("feature_id"), f"{label}.feature_id")
    if "set_overall_status" in update:
        _require(
            update["set_overall_status"] in ALLOWED_FEATURE_STATUSES,
            f"invalid feature status for {label}",
        )
    for key, value in update.items():
        if key.startswith(("append_", "remove_")):
            _require(
                isinstance(value, list)
                and all(isinstance(item, str) and item for item in value),
                f"{label}.{key} must be a string list",
            )
        if key.startswith("set_") and key.removeprefix("set_") in LIST_PATCH_FIELDS:
            _require(
                isinstance(value, list)
                and all(isinstance(item, str) and item for item in value),
                f"{label}.{key} must be a string list",
            )
        if key == "set_required_permissions":
            _require(isinstance(value, list), f"{label}.{key} must be a list")
            for permission in value:
                _require(
                    isinstance(permission, dict)
                    and set(permission) == {"name", "requirement", "plain_reason"}
                    and permission["requirement"] in {"REQUIRED", "CONDITIONAL"}
                    and all(
                        isinstance(permission[field], str) and permission[field]
                        for field in permission
                    ),
                    f"{label}.{key} contains an invalid permission",
                )
        if key == "set_user_guidance":
            guidance = _strict_object(value, f"{label}.{key}")
            _require(
                set(guidance) == {"screen", "speech", "vibration"},
                f"{label}.{key} fields are invalid",
            )
            for channel, messages in guidance.items():
                _require(
                    isinstance(messages, list)
                    and all(isinstance(item, str) and item for item in messages),
                    f"{label}.{key}.{channel} must be a string list",
                )
        if key in {"set_execution_boundary", "set_data_handling"}:
            expected_fields = (
                {"on_device", "server", "external"}
                if key == "set_execution_boundary"
                else {
                    "stored_on_device",
                    "sent_to_server",
                    "delete_from_device",
                    "server_retention",
                }
            )
            section = _strict_object(value, f"{label}.{key}")
            _require(set(section) == expected_fields, f"{label}.{key} fields are invalid")
            for field, items in section.items():
                _require(
                    isinstance(items, list)
                    and all(isinstance(item, str) and item for item in items),
                    f"{label}.{key}.{field} must be a string list",
                )
    return update


def _validate_rules(
    report: dict[str, Any],
    answers: dict[str, Any],
    rules: dict[str, Any],
) -> dict[str, dict[str, Any]]:
    _require(
        rules.get("schema_version") == "walksafe.effective-baseline-rules.v1",
        "effective-baseline rules schema is invalid",
    )
    candidate = _strict_object(rules.get("candidate"), "candidate")
    _require(
        bool(re.fullmatch(r"[a-z0-9][a-z0-9-]+", _strict_string(candidate.get("id"), "candidate.id"))),
        "candidate.id is invalid",
    )
    _require(
        bool(re.fullmatch(r"\d+\.\d+\.\d+", _strict_string(candidate.get("version"), "candidate.version"))),
        "candidate.version is invalid",
    )
    _require(
        bool(re.fullmatch(r"\d{4}-\d{2}-\d{2}", _strict_string(candidate.get("as_of"), "candidate.as_of"))),
        "candidate.as_of is invalid",
    )
    _require(candidate.get("lifecycle_status") == "IN_REVIEW", "candidate must be IN_REVIEW")
    boundary = _strict_object(rules.get("approval_boundary"), "approval_boundary")
    _require(boundary.get("questionnaire_status") == "QUESTIONNAIRE_COMPLETE", "questionnaire must be complete")
    _require(boundary.get("owner_policy_review_status") == "READY_FOR_REVIEW", "owner policy review must remain ready for review")
    _require(boundary.get("baseline_status") == "NOT_APPROVED", "rules must not claim approval")
    _require(boundary.get("release_status") == "NOT_ELIGIBLE", "rules must not claim release eligibility")
    _require(boundary.get("approved_by") is None, "approved_by must be null")
    _require(boundary.get("approved_at") is None, "approved_at must be null")
    _strict_string(boundary.get("baseline_approver_role"), "baseline_approver_role")
    _require(boundary.get("baseline_approval_recorded") is False, "baseline approval must not be recorded")
    _strict_string(boundary.get("note"), "approval_boundary.note")
    expected_outcomes = _strict_object(rules.get("expected_outcomes"), "expected_outcomes")
    for field in (
        "canonical_decision_count",
        "owner_decision_count",
        "feature_count",
        "source_normalization_pending_count",
    ):
        _require(
            isinstance(expected_outcomes.get(field), int)
            and expected_outcomes[field] >= 0,
            f"expected_outcomes.{field} must be a non-negative integer",
        )
    expected_conflicts = _strict_list(
        expected_outcomes.get("remaining_conflict_feature_ids"),
        "remaining_conflict_feature_ids",
    )
    _require(
        expected_conflicts
        and all(isinstance(item, str) and item for item in expected_conflicts),
        "remaining conflict IDs are invalid",
    )
    _unique(expected_conflicts, "remaining_conflict_feature_ids")

    features = {item["id"]: item for item in report["feature_policy"]["features"]}
    questions = {item["id"]: item for item in report["owner_questionnaire"]["questions"]}
    canonical = {item["id"]: item for item in report["responsibility"]["canonical_decisions"]}
    _require(expected_outcomes["feature_count"] == len(features), "expected feature count differs")
    _require(expected_outcomes["canonical_decision_count"] == len(canonical), "expected canonical decision count differs")
    owner_rules = _strict_list(rules.get("owner_decisions"), "owner_decisions")
    expected_count = expected_outcomes["owner_decision_count"]
    _require(len(owner_rules) == expected_count == len(questions), "owner decision rule count is invalid")
    by_canonical: dict[str, dict[str, Any]] = {}
    seen_questions: set[str] = set()
    for rule in owner_rules:
        question_id = _strict_string(rule.get("question_id"), "owner rule question_id")
        canonical_id = _strict_string(rule.get("canonical_decision_id"), "owner canonical_decision_id")
        _require(question_id in questions, f"unknown owner question: {question_id}")
        _require(canonical_id in canonical, f"unknown canonical decision: {canonical_id}")
        _require(questions[question_id]["canonical_decision_id"] == canonical_id, f"question/canonical mismatch: {question_id}")
        _require(canonical[canonical_id]["responsibility_type"] == "owner_decision", f"not an owner decision: {canonical_id}")
        _require(question_id not in seen_questions, f"duplicate owner question rule: {question_id}")
        _require(canonical_id not in by_canonical, f"duplicate owner canonical rule: {canonical_id}")
        seen_questions.add(question_id)
        expected_option = _strict_string(rule.get("expected_option_id"), "expected_option_id")
        _require(answers["answers"][question_id] == expected_option, f"answer/rule mismatch: {question_id}")
        option_ids = {item["id"] for item in questions[question_id]["options"]}
        _require(expected_option in option_ids, f"rule has unknown option: {question_id}")
        feature_ids = _unique(_strict_list(rule.get("affected_feature_ids"), "affected_feature_ids"), f"{canonical_id} affected_feature_ids")
        _require(feature_ids, f"owner decision has no feature links: {canonical_id}")
        _require(set(feature_ids) <= set(features), f"owner decision has unknown feature links: {canonical_id}")
        updates = _strict_list(rule.get("feature_updates"), "feature_updates")
        update_ids = [item.get("feature_id") for item in updates]
        _require(set(update_ids) == set(feature_ids), f"feature updates do not cover links: {canonical_id}")
        _unique(update_ids, f"{canonical_id} feature update IDs")
        for update_index, update in enumerate(updates):
            _validate_feature_update_shape(
                update, f"owner_decisions.{canonical_id}.feature_updates[{update_index}]"
            )
        _require(
            isinstance(rule.get("open_details"), list) and all(isinstance(item, str) and item for item in rule["open_details"]),
            f"{canonical_id} open_details must be a string list",
        )
        by_canonical[canonical_id] = rule
    _require(set(seen_questions) == set(questions), "owner decision rules do not cover every question")
    expected_non_recommended = {
        question_id
        for question_id, question in questions.items()
        if answers["answers"][question_id] != question["recommendation"]["option_id"]
    }
    registered_non_recommended = set(
        _unique(
            _strict_list(
                expected_outcomes.get("non_recommended_question_ids"),
                "non_recommended_question_ids",
            ),
            "non_recommended_question_ids",
        )
    )
    _require(
        registered_non_recommended == expected_non_recommended,
        "non-recommended answer summary differs from the actual selections",
    )

    role_policies = _strict_object(rules.get("role_policies"), "role_policies")
    responsibility_types = {item["id"] for item in report["responsibility"]["responsibility_types"]}
    _require(set(role_policies) == responsibility_types, "role policies do not cover every responsibility type")
    for kind, policy in role_policies.items():
        owner = _strict_string(policy.get("owner_role"), f"{kind}.owner_role")
        record_owner = _strict_string(policy.get("record_owner_role"), f"{kind}.record_owner_role")
        approver = _strict_string(policy.get("approver_role"), f"{kind}.approver_role")
        reviewers = _strict_list(policy.get("reviewer_roles"), f"{kind}.reviewer_roles")
        _require(reviewers and all(isinstance(item, str) and item for item in reviewers), f"{kind}.reviewer_roles must contain roles")
        _unique(reviewers, f"{kind}.reviewer_roles")
        _require(record_owner not in {owner, approver, *reviewers}, f"record owner must be separated for {kind}")
        _require(owner not in reviewers, f"decision owner cannot self-review for {kind}")
        _require(approver not in reviewers, f"approver cannot also review for {kind}")
        actual_gate = (
            policy.get("resolution_status"),
            policy.get("gate_kind"),
            policy.get("gate_status"),
        )
        _require(actual_gate == EXPECTED_ROLE_GATE[kind], f"invalid role/gate state combination for {kind}")
        _strict_string(policy.get("closure_criteria"), f"{kind}.closure_criteria")
    return by_canonical


def _validate_policy_review_corrections(
    rules: dict[str, Any],
    decision_trace: dict[str, dict[str, Any]],
    feature_ids: set[str],
) -> list[dict[str, Any]]:
    corrections = _strict_list(
        rules.get("policy_review_corrections"), "policy_review_corrections"
    )
    correction_ids: list[str] = []
    for index, raw in enumerate(corrections):
        correction = _strict_object(raw, f"policy_review_corrections[{index}]")
        _require(
            set(correction)
            == {"id", "canonical_decision_ids", "reason", "feature_updates"},
            f"policy review correction fields are invalid at index {index}",
        )
        correction_id = _strict_string(correction.get("id"), f"correction[{index}].id")
        _require(
            bool(re.fullmatch(r"PRC-\d{3}", correction_id)),
            f"invalid policy review correction ID: {correction_id}",
        )
        correction_ids.append(correction_id)
        _strict_string(correction.get("reason"), f"{correction_id}.reason")
        canonical_ids = _strict_list(
            correction.get("canonical_decision_ids"),
            f"{correction_id}.canonical_decision_ids",
        )
        _require(
            canonical_ids
            and all(isinstance(item, str) and item in decision_trace for item in canonical_ids),
            f"{correction_id} has an unknown canonical decision",
        )
        _unique(canonical_ids, f"{correction_id}.canonical_decision_ids")
        allowed_features = {
            feature_id
            for canonical_id in canonical_ids
            for feature_id in decision_trace[canonical_id]["affected_feature_ids"]
        }
        updates = _strict_list(
            correction.get("feature_updates"), f"{correction_id}.feature_updates"
        )
        _require(updates, f"{correction_id} has no feature updates")
        update_ids: list[str] = []
        for update_index, update in enumerate(updates):
            parsed = _validate_feature_update_shape(
                update, f"{correction_id}.feature_updates[{update_index}]"
            )
            update_ids.append(parsed["feature_id"])
        _unique(update_ids, f"{correction_id}.feature_update_ids")
        _require(set(update_ids) <= feature_ids, f"{correction_id} has unknown features")
        _require(
            set(update_ids) <= allowed_features,
            f"{correction_id} updates features outside its decision trace",
        )
    _unique(correction_ids, "policy review correction IDs")
    return corrections


def _validate_decision_trace(
    report: dict[str, Any],
    trace: dict[str, Any],
    owner_rules: dict[str, dict[str, Any]],
    rules: dict[str, Any],
) -> dict[str, dict[str, Any]]:
    _require(
        trace.get("schema_version") == "walksafe.canonical-decision-trace.v1",
        "canonical decision trace schema is invalid",
    )
    _require(trace.get("candidate_id") == rules["candidate"]["id"], "decision trace candidate differs")
    _require(trace.get("as_of") == rules["candidate"]["as_of"], "decision trace date differs")
    _require(trace.get("lifecycle_status") == "IN_REVIEW", "decision trace must remain IN_REVIEW")
    entries = _strict_list(trace.get("decisions"), "decision_trace.decisions")
    canonical_list = report["responsibility"]["canonical_decisions"]
    _require(
        len(entries) == rules["expected_outcomes"]["canonical_decision_count"],
        "decision trace count differs from the registered expectation",
    )
    canonical_ids = [item["id"] for item in canonical_list]
    trace_ids = [item.get("canonical_decision_id") for item in entries]
    _require(trace_ids == canonical_ids, "decision trace must follow and cover every canonical decision")
    fragments = _strict_list(
        trace.get("review_fragments"), "decision_trace.review_fragments"
    )
    expected_fragments = (("1-45", 0, 45), ("46-90", 45, 90), ("91-135", 90, 135))
    _require(len(fragments) == len(expected_fragments), "decision trace review fragments differ")
    for fragment, (expected_range, start, end) in zip(
        fragments, expected_fragments, strict=True
    ):
        fragment = _strict_object(fragment, f"review fragment {expected_range}")
        _require(
            set(fragment) == {"range", "path", "sha256"},
            f"review fragment fields are invalid: {expected_range}",
        )
        _require(fragment.get("range") == expected_range, f"review fragment range differs: {expected_range}")
        fragment_path_text = _strict_string(
            fragment.get("path"), f"review fragment {expected_range}.path"
        )
        fragment_path = Path(fragment_path_text)
        _require(not fragment_path.is_absolute(), f"review fragment path must be repository-relative: {expected_range}")
        fragment_path = (REPO_ROOT / fragment_path).resolve()
        _require(
            fragment_path.is_relative_to(REPO_ROOT.resolve()) and fragment_path.is_file(),
            f"review fragment is missing or outside the repository: {expected_range}",
        )
        expected_sha256 = _strict_string(
            fragment.get("sha256"), f"review fragment {expected_range}.sha256"
        )
        _require(
            bool(re.fullmatch(r"[0-9a-f]{64}", expected_sha256))
            and interview.file_sha256(fragment_path) == expected_sha256,
            f"review fragment hash differs: {expected_range}",
        )
        try:
            fragment_entries = json.loads(
                fragment_path.read_text(encoding="utf-8"),
                object_pairs_hook=interview._object_pairs_no_duplicates,
                parse_constant=interview._reject_constant,
            )
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            raise EffectiveBaselineValidationError(
                f"cannot read review fragment {expected_range}: {exc}"
            ) from exc
        _require(
            isinstance(fragment_entries, list)
            and [item.get("canonical_decision_id") for item in fragment_entries]
            == canonical_ids[start:end],
            f"review fragment decision coverage differs: {expected_range}",
        )
        _require(
            all(
                fragment_item.get("affected_feature_ids")
                == trace_item.get("affected_feature_ids")
                for fragment_item, trace_item in zip(
                    fragment_entries, entries[start:end], strict=True
                )
            ),
            f"review fragment feature mapping differs: {expected_range}",
        )
    feature_ids = {item["id"] for item in report["feature_policy"]["features"]}
    canonical_by_id = {item["id"]: item for item in canonical_list}
    by_id: dict[str, dict[str, Any]] = {}
    for expected_order, item in enumerate(entries, start=1):
        canonical_id = _strict_string(item.get("canonical_decision_id"), "trace canonical_decision_id")
        _require(item.get("register_order") == expected_order, f"invalid register order: {canonical_id}")
        affected = _strict_list(item.get("affected_feature_ids"), f"{canonical_id}.affected_feature_ids")
        _require(affected and all(isinstance(value, str) for value in affected), f"{canonical_id} has no feature impact")
        _unique(affected, f"{canonical_id}.affected_feature_ids")
        _require(set(affected) <= feature_ids, f"{canonical_id} has unknown feature links")
        _strict_string(item.get("rationale"), f"{canonical_id}.rationale")
        responsibility_type = canonical_by_id[canonical_id]["responsibility_type"]
        if responsibility_type == "already_confirmed":
            status = item.get("normalization_status")
            _require(status in {"NORMALIZED", "PENDING"}, f"{canonical_id} normalization status is invalid")
            refs = _strict_list(item.get("source_record_refs"), f"{canonical_id}.source_record_refs")
            _require(refs and all(isinstance(value, str) and value for value in refs), f"{canonical_id} has no normalization source")
            if status == "NORMALIZED":
                _strict_string(item.get("decision_statement"), f"{canonical_id}.decision_statement")
            else:
                _strict_string(item.get("pending_reason") or item.get("normalization_note"), f"{canonical_id}.pending_reason")
        else:
            _require("normalization_status" not in item, f"non-confirmed decision has normalization state: {canonical_id}")
        superseded = item.get("superseded_source_record_refs", [])
        _require(
            isinstance(superseded, list)
            and all(isinstance(value, str) and value for value in superseded),
            f"{canonical_id}.superseded_source_record_refs is invalid",
        )
        if canonical_id in owner_rules:
            _require(
                set(affected) == set(owner_rules[canonical_id]["affected_feature_ids"]),
                f"owner decision feature trace differs from its applied updates: {canonical_id}",
            )
        by_id[canonical_id] = item
    _require(
        sum(item.get("normalization_status") == "PENDING" for item in entries)
        == rules["expected_outcomes"]["source_normalization_pending_count"],
        "source normalization pending count differs from the registered expectation",
    )
    return by_id


def _apply_feature_update(feature: dict[str, Any], update: dict[str, Any]) -> None:
    if "set_plain_summary" in update:
        feature["plain_summary"] = _strict_string(update["set_plain_summary"], "set_plain_summary")
    if "set_overall_status" in update:
        feature["policy"]["overall_status"] = update["set_overall_status"]
    if "set_user_guidance" in update:
        feature["user_guidance"] = copy.deepcopy(update["set_user_guidance"])
    if "set_execution_boundary" in update:
        feature["execution_boundary"] = copy.deepcopy(update["set_execution_boundary"])
    if "set_data_handling" in update:
        feature["data_handling"] = copy.deepcopy(update["set_data_handling"])
    for field in SET_PATCH_FIELDS:
        key = f"set_{field}"
        if key not in update:
            continue
        target_container = feature["policy"] if field in POLICY_LIST_FIELDS else feature
        target_container[field] = copy.deepcopy(update[key])
    for prefix in ("remove_", "append_"):
        for key, values in update.items():
            if not key.startswith(prefix):
                continue
            field = key[len(prefix):]
            target = feature["policy"][field] if field in POLICY_LIST_FIELDS else feature[field]
            if prefix == "remove_":
                for value in values:
                    _require(value in target, f"cannot remove missing text from {feature['id']}.{field}: {value}")
                    target.remove(value)
            else:
                for value in values:
                    if value not in target:
                        target.append(value)


def _artifact_and_bundle_impact(
    canonical: dict[str, Any],
    integrated_by_id: dict[str, dict[str, Any]],
    artifact_by_id: dict[str, dict[str, Any]],
    owner_question: dict[str, Any] | None,
) -> tuple[list[str], list[str]]:
    if owner_question:
        artifact_ids = sorted(owner_question["affects"])
    else:
        artifact_ids = sorted({
            artifact_id
            for ibq_id in canonical["source_ibq_ids"]
            for artifact_id in integrated_by_id[ibq_id]["affects"]
        })
    _require(set(artifact_ids) <= set(artifact_by_id), f"unknown artifact impact: {canonical['id']}")
    bundle_ids = sorted({artifact_by_id[item]["recommended_bundle_id"] for item in artifact_ids})
    if owner_question:
        _require(
            set(bundle_ids) == set(owner_question["document_bundles"]),
            f"owner question bundle impact differs from its artifacts: {owner_question['id']}",
        )
    return artifact_ids, bundle_ids


def _build_sources(report: dict[str, Any], rules: dict[str, Any]) -> tuple[list[dict[str, Any]], str]:
    sources: list[dict[str, Any]] = []
    for key, path in REPORT_SOURCE_PATHS.items():
        source_class, artifact_form, evidence_classification, adoption_trust = SOURCE_PROFILES[key]
        sources.append({
            "id": _source_ref_id(key),
            "path": path.relative_to(REPO_ROOT).as_posix(),
            "sha256": report["binding"][key],
            "source_class": source_class,
            "artifact_form": artifact_form,
            "evidence_classification": evidence_classification,
            "adoption_trust": adoption_trust,
        })
    sources.extend([
        {
            "id": "SRC-OWNER-ANSWERS-CONTROLLED",
            "path": str(CONTROLLED_ANSWERS_PATH.relative_to(REPO_ROOT)),
            "sha256": interview.file_sha256(CONTROLLED_ANSWERS_PATH),
            "source_class": "EXTERNAL",
            "artifact_form": "EXTERNAL_RECORD",
            "evidence_classification": "USER_CONFIRMED",
            "adoption_trust": "D",
        },
        {
            "id": "SRC-EFFECTIVE-RULES",
            "path": str(RULES_PATH.relative_to(REPO_ROOT)),
            "sha256": interview.file_sha256(RULES_PATH),
            "source_class": "GENERATED",
            "artifact_form": "CONTROLLED_ARTIFACT",
            "evidence_classification": "CURRENT_CANDIDATE",
            "adoption_trust": "D",
        },
        {
            "id": "SRC-DECISION-TRACE",
            "path": str(DECISION_TRACE_PATH.relative_to(REPO_ROOT)),
            "sha256": interview.file_sha256(DECISION_TRACE_PATH),
            "source_class": "GENERATED",
            "artifact_form": "REGISTER",
            "evidence_classification": "CURRENT_CANDIDATE",
            "adoption_trust": "D",
        },
    ])
    _unique([item["id"] for item in sources], "source IDs")
    for source in sources:
        _require(source["source_class"] in SOURCE_CLASSES, f"invalid source_class: {source['id']}")
        _require(source["artifact_form"] in ARTIFACT_FORMS, f"invalid artifact_form: {source['id']}")
        _require(source["evidence_classification"] in EVIDENCE_CLASSIFICATIONS, f"invalid evidence classification: {source['id']}")
        _require(source["adoption_trust"] in ADOPTION_TRUST_LEVELS, f"invalid adoption trust: {source['id']}")
    binding = _content_sha({item["id"]: item["sha256"] for item in sources})
    return sources, binding


def build_effective_artifacts() -> tuple[dict[str, Any], dict[str, Any], str]:
    report = interview.load_and_build()
    answers = interview.load_strict_json(CONTROLLED_ANSWERS_PATH)
    rules = interview.load_strict_json(RULES_PATH)
    trace = interview.load_strict_json(DECISION_TRACE_PATH)
    integrated = interview.load_strict_json(INTEGRATED_QUESTIONS_PATH)
    _validate_answer_intake(report, answers, rules, report["owner_questionnaire"])
    owner_rules = _validate_rules(report, answers, rules)
    decision_trace = _validate_decision_trace(report, trace, owner_rules, rules)
    policy_review_corrections = _validate_policy_review_corrections(
        rules,
        decision_trace,
        {item["id"] for item in report["feature_policy"]["features"]},
    )

    sources, input_binding_sha256 = _build_sources(report, rules)
    questions = {item["id"]: item for item in report["owner_questionnaire"]["questions"]}
    integrated_by_id = {item["id"]: item for item in integrated["questions"]}
    artifacts = {item["display_code"]: item for item in interview.load_strict_json(interview.ARTIFACT_TYPES_PATH)["artifact_types"]}
    role_policies = rules["role_policies"]

    decisions: list[dict[str, Any]] = []
    for index, canonical in enumerate(report["responsibility"]["canonical_decisions"], start=1):
        canonical_id = canonical["id"]
        trace_item = decision_trace[canonical_id]
        responsibility_type = canonical["responsibility_type"]
        role = role_policies[responsibility_type]
        owner_rule = owner_rules.get(canonical_id)
        question = questions[owner_rule["question_id"]] if owner_rule else None
        artifact_ids, bundle_ids = _artifact_and_bundle_impact(
            canonical, integrated_by_id, artifacts, question
        )
        option = None
        if question:
            option = next(item for item in question["options"] if item["id"] == owner_rule["expected_option_id"])
        resolution_status = role["resolution_status"]
        gate_status = role["gate_status"]
        gate_criteria = role["closure_criteria"]
        normalization_status = None
        if option:
            decision_statement = f"{option['label']}: {option['meaning']}"
            value_type = "OPTION_ID"
        elif responsibility_type == "already_confirmed":
            normalization_status = trace_item["normalization_status"]
            decision_statement = trace_item.get("decision_statement")
            value_type = (
                "NORMALIZED_STATEMENT"
                if normalization_status == "NORMALIZED"
                else "PENDING_SOURCE_NORMALIZATION"
            )
            if normalization_status == "PENDING":
                resolution_status = "PENDING_SOURCE_NORMALIZATION"
                gate_status = "PARTIAL"
                gate_criteria = trace_item.get("pending_reason") or trace_item["normalization_note"]
        else:
            decision_statement = None
            value_type = "PENDING_GATE"
        source_evidence_refs = [
            f"SRC-INTEGRATED-QUESTIONS#{ibq_id}"
            for ibq_id in canonical["source_ibq_ids"]
        ]
        if question:
            source_evidence_refs = [
                f"SRC-OWNER-ANSWERS-CONTROLLED#answers.{question['id']}"
            ]
        elif responsibility_type == "already_confirmed":
            source_evidence_refs = _normalize_trace_source_refs(
                trace_item["source_record_refs"]
            )
            _require(source_evidence_refs, f"normalized decision has no resolvable source: {canonical_id}")
        decision: dict[str, Any] = {
            "decision_id": _decision_id(canonical_id),
            "register_order": index,
            "canonical_decision_id": canonical_id,
            "decision_version": rules["candidate"]["version"],
            "title": canonical["title"],
            "decision_statement": decision_statement,
            "value_type": value_type,
            "selected_value": option["id"] if option else None,
            "selected_label": option["label"] if option else None,
            "impact_summary": option["impact"] if option else trace_item["rationale"],
            "rationale": (
                f"제품책임자가 통제 답변 {question['id']}에서 {option['label']}을 선택했다."
                if question
                else trace_item["rationale"]
            ),
            "disposition_rationale": canonical["reason"],
            "responsibility_type": responsibility_type,
            "previous_disposition": canonical["disposition"],
            "lifecycle_status": "IN_REVIEW",
            "resolution_status": resolution_status,
            "normalization_status": normalization_status,
            "owner_role": role["owner_role"],
            "record_owner_role": role["record_owner_role"],
            "reviewer_roles": role["reviewer_roles"],
            "approver_role": role["approver_role"],
            "source_refs": [
                *[
                    f"SRC-INTEGRATED-QUESTIONS#{ibq_id}"
                    for ibq_id in canonical["source_ibq_ids"]
                ],
                *(
                    [
                        f"SRC-OWNER-QUESTIONS#{question['id']}",
                        f"SRC-OWNER-ANSWERS-CONTROLLED#answers.{question['id']}",
                    ]
                    if question
                    else []
                ),
            ],
            "supersedes_decision_ids": [],
            "supersedes_source_refs": _normalize_trace_source_refs(
                trace_item.get("superseded_source_record_refs", [])
            ),
            "affected_feature_ids": trace_item["affected_feature_ids"],
            "affected_artifact_type_ids": artifact_ids,
            "affected_bundle_ids": bundle_ids,
            "open_details": (
                owner_rule["open_details"]
                if owner_rule
                else (
                    [gate_criteria]
                    if resolution_status == "PENDING_SOURCE_NORMALIZATION"
                    else []
                )
            ),
            "normalization_source_refs": (
                source_evidence_refs
                if responsibility_type == "already_confirmed"
                else []
            ),
            "verification_gate": {
                "kind": role["gate_kind"],
                "status": gate_status,
                "owner_role": role["owner_role"],
                "criteria": gate_criteria,
                "evidence_ids": [],
                "source_evidence_refs": source_evidence_refs,
            },
            "approved_by": None,
            "approved_at": None,
        }
        hash_input = {key: value for key, value in decision.items() if key not in {"approved_by", "approved_at"}}
        decision["content_sha256"] = _content_sha(hash_input)
        decisions.append(decision)

    source_ids = {item["id"] for item in sources}
    for decision in decisions:
        source_ref_groups = [
            decision["source_refs"],
            decision["supersedes_source_refs"],
            decision["normalization_source_refs"],
            decision["verification_gate"]["source_evidence_refs"],
        ]
        for source_refs in source_ref_groups:
            for source_ref in source_refs:
                source_id, separator, record_id = source_ref.partition("#")
                _require(
                    bool(separator and record_id) and source_id in source_ids,
                    f"invalid source record reference in {decision['decision_id']}: {source_ref}",
                )

    feature_policy = copy.deepcopy(report["feature_policy"])
    feature_by_id = {item["id"]: item for item in feature_policy["features"]}
    for feature in feature_policy["features"]:
        feature["decision_refs"] = []
        feature["canonical_decision_refs"] = []
        feature["owner_decision_refs"] = []
        feature["decision_open_details"] = {}
        feature["change_notes"] = []
        feature["review_correction_notes"] = []
    for canonical_id, trace_item in decision_trace.items():
        for feature_id in trace_item["affected_feature_ids"]:
            feature = feature_by_id[feature_id]
            feature["decision_refs"].append(_decision_id(canonical_id))
            feature["canonical_decision_refs"].append(canonical_id)
    for canonical_id, rule in owner_rules.items():
        question = questions[rule["question_id"]]
        option = next(item for item in question["options"] if item["id"] == rule["expected_option_id"])
        for update in rule["feature_updates"]:
            feature = feature_by_id[update["feature_id"]]
            before_update = copy.deepcopy(feature)
            _apply_feature_update(feature, update)
            _require(feature != before_update, f"feature update has no effect: {canonical_id}/{feature['id']}")
            feature["owner_decision_refs"].append(_decision_id(canonical_id))
            feature["decision_open_details"][canonical_id] = rule["open_details"]
            feature["change_notes"].append(f"{question['id']} · {option['label']}: {option['meaning']}")
            for source_ref in (question["id"], canonical_id):
                if source_ref not in feature["source_refs"]:
                    feature["source_refs"].append(source_ref)

    for correction in policy_review_corrections:
        for update in correction["feature_updates"]:
            feature = feature_by_id[update["feature_id"]]
            before_update = copy.deepcopy(feature)
            _apply_feature_update(feature, update)
            _require(
                feature != before_update,
                f"policy review correction has no effect: {correction['id']}/{feature['id']}",
            )
            feature["review_correction_notes"].append(
                f"{correction['id']}: {correction['reason']}"
            )

    for feature in feature_policy["features"]:
        _unique(feature["decision_refs"], f"{feature['id']} decision_refs")
        _unique(feature["canonical_decision_refs"], f"{feature['id']} canonical_decision_refs")
        _unique(feature["owner_decision_refs"], f"{feature['id']} owner_decision_refs")
        _require(feature["decision_refs"], f"feature has no decision or gate trace: {feature['id']}")
        _require(
            (feature["policy"]["overall_status"] == "CONFLICTING") == bool(feature["policy"]["conflicts"]),
            f"feature conflict status/list mismatch: {feature['id']}",
        )

    expected_conflicts = rules["expected_outcomes"]["remaining_conflict_feature_ids"]
    actual_conflicts = [item["id"] for item in feature_policy["features"] if item["policy"]["conflicts"]]
    _require(actual_conflicts == expected_conflicts, f"remaining feature conflicts differ: {actual_conflicts}")
    _require(len(feature_policy["features"]) == rules["expected_outcomes"]["feature_count"], "feature count changed")

    policy_status_counts = Counter(item["policy"]["overall_status"] for item in feature_policy["features"])
    implementation_status_counts = Counter(item["implementation"]["status"] for item in feature_policy["features"])
    effective_summary = {
        "area_count": len(feature_policy["areas"]),
        "feature_count": len(feature_policy["features"]),
        "owner_decision_count": len(owner_rules),
        "decision_linked_feature_count": sum(bool(item["decision_refs"]) for item in feature_policy["features"]),
        "owner_decision_linked_feature_count": sum(bool(item["owner_decision_refs"]) for item in feature_policy["features"]),
        "policy_status_counts": dict(sorted(policy_status_counts.items())),
        "implementation_status_counts": dict(sorted(implementation_status_counts.items())),
        "unresolved_feature_count": sum(bool(item["policy"]["unresolved"]) for item in feature_policy["features"]),
        "unresolved_item_count": sum(len(item["policy"]["unresolved"]) for item in feature_policy["features"]),
        "conditional_feature_count": sum(bool(item["policy"]["conditional"]) for item in feature_policy["features"]),
        "remaining_conflict_feature_ids": actual_conflicts,
    }
    effective_policy = {
        "schema_version": "walksafe.effective-feature-policy.v1",
        "candidate_id": rules["candidate"]["id"],
        "candidate_version": rules["candidate"]["version"],
        "document_version": rules["candidate"]["version"],
        "as_of": rules["candidate"]["as_of"],
        "lifecycle_status": rules["candidate"]["lifecycle_status"],
        "approval_boundary": rules["approval_boundary"],
        "base_policy_id": feature_policy["baseline_id"],
        "title": "WalkSafe 유효 기능 정책 기준선 후보",
        "purpose": "기존 54개 기능 정책에 최신 10개 제품책임자 답변을 적용해 승인 전 검토할 단일 후보를 제공한다.",
        "audience": feature_policy["audience"],
        "authority_order": [
            {
                "rank": 1,
                "source": "통제 등록된 2026-07-18 제품책임자 최종 10개 답변",
                "rule": "현재 10개 open owner decision을 닫는다. 답변이 다루지 않은 기술·실측·전문가 세부사항은 자동 확정하지 않는다."
            },
            *[
                {**item, "rank": item["rank"] + 1}
                for item in feature_policy["authority_order"]
            ],
        ],
        "status_definitions": feature_policy["status_definitions"],
        "reading_guide": [
            "이번 후보에서 10개 제품책임자 질문은 모두 답변됐지만 기준선 승인은 아직 아니다.",
            "추천과 다른 선택도 사용자 답변이면 그대로 반영하되 그 영향과 남은 gate를 숨기지 않는다.",
            *feature_policy["reading_guide"],
        ],
        "source_bindings": sources,
        "input_binding_sha256": input_binding_sha256,
        "summary": effective_summary,
        "areas": feature_policy["areas"],
        "features": feature_policy["features"],
    }

    resolution_counts = Counter(item["resolution_status"] for item in decisions)
    canonical_type_counts = Counter(item["responsibility_type"] for item in decisions)
    source_type_counts = report["responsibility"]["coverage"]["by_responsibility_type"]
    resolution_by_type = {
        kind: Counter(
            item["resolution_status"]
            for item in decisions
            if item["responsibility_type"] == kind
        )
        for kind in role_policies
    }
    gate_by_type = {
        kind: Counter(
            item["verification_gate"]["status"]
            for item in decisions
            if item["responsibility_type"] == kind
        )
        for kind in role_policies
    }
    register = {
        "schema_version": "walksafe.effective-decision-register.v1",
        "artifact_type_code": "DLV-MGT-15",
        "artifact_instance_id": "ART-MGT-DECISIONS-001",
        "candidate_id": rules["candidate"]["id"],
        "document_version": rules["candidate"]["version"],
        "as_of": rules["candidate"]["as_of"],
        "lifecycle_status": rules["candidate"]["lifecycle_status"],
        "approval_boundary": rules["approval_boundary"],
        "source_bindings": sources,
        "input_binding_sha256": input_binding_sha256,
        "decisions": decisions,
        "queue_summary": [
            {
                "responsibility_type": kind,
                "canonical_decision_count": canonical_type_counts.get(kind, 0),
                "source_question_count": source_type_counts.get(kind, 0),
                "resolution_status": (
                    next(iter(resolution_by_type[kind]))
                    if len(resolution_by_type[kind]) == 1
                    else "MIXED"
                ),
                "resolution_status_counts": dict(sorted(resolution_by_type[kind].items())),
                "gate_status": (
                    next(iter(gate_by_type[kind]))
                    if len(gate_by_type[kind]) == 1
                    else "MIXED"
                ),
                "gate_status_counts": dict(sorted(gate_by_type[kind].items())),
                "owner_role": role_policies[kind]["owner_role"],
                "approver_role": role_policies[kind]["approver_role"],
                "closure_criteria": role_policies[kind]["closure_criteria"],
            }
            for kind in role_policies
        ],
        "approval_blockers": [
            {
                "id": "BLK-OWNER-POLICY-REVIEW",
                "status": "OPEN",
                "reason": "프로젝트관리자인 사용자 본인이 10개 결정과 54개 기능 설명을 아직 최종 승인하지 않았다."
            },
            {
                "id": "BLK-SOURCE-NORMALIZATION",
                "status": "OPEN",
                "count": resolution_counts.get("PENDING_SOURCE_NORMALIZATION", 0),
                "decision_ids": [
                    item["decision_id"]
                    for item in decisions
                    if item["resolution_status"] == "PENDING_SOURCE_NORMALIZATION"
                ],
                "reason": "과거에 이미 확정으로 분류됐지만 실제 단일 정책값이 남지 않은 항목을 다시 확인해야 한다."
            },
            {
                "id": "BLK-ENGINEERING-PROPOSALS",
                "status": "OPEN",
                "count": source_type_counts["engineering_proposal"],
                "reason": "기술안이 아직 채택되지 않았다."
            },
            {
                "id": "BLK-MEASUREMENTS",
                "status": "OPEN",
                "count": source_type_counts["measurement_gate"],
                "reason": "실기기·현장 측정이 아직 실행되지 않았다."
            },
            {
                "id": "BLK-EXPERT-REVIEWS",
                "status": "OPEN",
                "canonical_gate_count": canonical_type_counts["expert_review"],
                "source_question_count": source_type_counts["expert_review"],
                "reason": "법률·개인정보·보안·접근성 등 독립 전문가 검토가 남아 있다."
            },
            {
                "id": "BLK-GENERATED-EVIDENCE",
                "status": "OPEN",
                "count": source_type_counts["generated_evidence"],
                "reason": "대상 형상에 결속된 생성 증거가 아직 없다."
            },
            {
                "id": "BLK-POLICY-CONFLICT",
                "status": "OPEN",
                "feature_ids": actual_conflicts,
                "reason": "월 운영비 0원과 대용량 원본 장기 cloud 저장 정책이 충돌한다."
            },
            {
                "id": "BLK-OPEN-DETAILS",
                "status": "OPEN",
                "count": effective_summary["unresolved_item_count"],
                "feature_count": effective_summary["unresolved_feature_count"],
                "feature_ids": [
                    item["id"]
                    for item in feature_policy["features"]
                    if item["policy"]["unresolved"]
                ],
                "reason": "기능별 미정 세부사항을 담당 gate와 결정에 연결해 닫아야 한다."
            },
        ],
        "coverage": {
            "expected_canonical_decisions": report["responsibility"]["coverage"]["canonical_decision_count"],
            "actual_decisions": len(decisions),
            "feature_linked_decision_count": sum(bool(item["affected_feature_ids"]) for item in decisions),
            "source_normalization_pending_count": resolution_counts.get("PENDING_SOURCE_NORMALIZATION", 0),
            "source_question_count": report["responsibility"]["coverage"]["source_question_count"],
            "mapped_source_question_count": report["responsibility"]["coverage"]["mapped_question_count"],
            "owner_answer_count": len(owner_rules),
            "feature_count": effective_summary["feature_count"],
            "artifact_type_count": report["source_summary"]["artifact_type_total"],
            "document_bundle_count": report["source_summary"]["document_bundle_total"],
            "by_resolution_status": dict(sorted(resolution_counts.items())),
            "by_responsibility_type": dict(sorted(canonical_type_counts.items())),
            "missing_decision_ids": [],
            "duplicate_decision_ids": [],
        },
    }
    _require(register["coverage"]["actual_decisions"] == register["coverage"]["expected_canonical_decisions"], "decision coverage mismatch")
    _unique([item["decision_id"] for item in decisions], "decision IDs")
    _unique([item["canonical_decision_id"] for item in decisions], "canonical decision IDs")

    review = render_approval_review(register, effective_policy, report, questions, owner_rules, rules)
    return register, effective_policy, review


def _md(value: Any) -> str:
    return str(value).replace("|", "\\|").replace("\n", " ")


def _append_detail_list(
    lines: list[str],
    title: str,
    values: list[str],
    *,
    empty_text: str | None = None,
) -> None:
    if not values:
        if empty_text:
            lines.append(f"- **{title}:** {empty_text}")
        return
    lines.append(f"- **{title}**")
    lines.extend(f"  - {item}" for item in values)


def _append_feature_detail(lines: list[str], feature: dict[str, Any]) -> None:
    lines.extend([
        f"#### {feature['id']} {feature['name']}",
        "",
        feature["plain_summary"],
        "",
        f"- **사용자에게 필요한 이유:** {feature['user_purpose']}",
        f"- **정책 상태:** {POLICY_STATUS_LABELS[feature['policy']['overall_status']]} (`{feature['policy']['overall_status']}`)",
        f"- **현재 구현 판단:** {feature['implementation']['plain_status']}",
    ])
    _append_detail_list(lines, "시작 전 조건", feature["start_conditions"])
    _append_detail_list(lines, "정상 작동 순서", feature["normal_flow"])

    guidance = feature["user_guidance"]
    _append_detail_list(lines, "화면 안내", guidance["screen"])
    _append_detail_list(lines, "음성 안내", guidance["speech"])
    _append_detail_list(lines, "진동 안내", guidance["vibration"])

    permissions = [
        f"{item['name']} — {item['plain_reason']} (`{item['requirement']}`)"
        for item in feature["required_permissions"]
    ]
    _append_detail_list(
        lines,
        "필요 권한",
        permissions,
        empty_text="이 기능에 직접 연결된 별도 권한 항목은 없다.",
    )

    boundary = feature["execution_boundary"]
    _append_detail_list(lines, "휴대폰에서 처리", boundary["on_device"])
    _append_detail_list(lines, "WalkSafe 서버에서 처리", boundary["server"])
    _append_detail_list(lines, "외부 서비스·기관", boundary["external"])

    data = feature["data_handling"]
    _append_detail_list(
        lines,
        "휴대폰에 저장",
        data["stored_on_device"],
        empty_text="현재 정책에서 확인된 별도 저장 항목이 없다.",
    )
    _append_detail_list(
        lines,
        "서버로 전송",
        data["sent_to_server"],
        empty_text="현재 정책에서 확인된 별도 전송 항목이 없다.",
    )
    _append_detail_list(
        lines,
        "휴대폰에서 삭제",
        data["delete_from_device"],
        empty_text="현재 정책에서 확인된 별도 삭제 규칙이 없다.",
    )
    _append_detail_list(
        lines,
        "서버 보존",
        data["server_retention"],
        empty_text="현재 정책에서 확인된 별도 서버 보존 항목이 없다.",
    )
    _append_detail_list(lines, "문제가 생기면", feature["failure_behavior"])

    policy = feature["policy"]
    _append_detail_list(lines, "확정된 정책", policy["confirmed"])
    _append_detail_list(lines, "조건을 충족해야 확정되는 정책", policy["conditional"])
    _append_detail_list(
        lines,
        "서로 충돌하는 정책",
        policy["conflicts"],
        empty_text="현재 확인된 직접 충돌은 없다.",
    )
    _append_detail_list(lines, "아직 정하지 않은 세부사항", policy["unresolved"])
    _append_detail_list(lines, "이번 10개 답변으로 바뀐 점", feature["change_notes"])
    _append_detail_list(
        lines,
        "독립 검토에서 모순 없이 정리한 점",
        feature["review_correction_notes"],
    )
    _append_detail_list(lines, "이번 10개 답변의 결정 ID", feature["owner_decision_refs"])
    _append_detail_list(lines, "연결된 전체 결정·후속 gate ID", feature["decision_refs"])
    _append_detail_list(lines, "정책 근거 ID", feature["source_refs"])
    _append_detail_list(
        lines,
        "현재 구현 근거 ID",
        feature["implementation"]["evidence_refs"],
        empty_text="현재 저장소에서 이 기능에 직접 연결해 확인한 코드·실행 증거가 없다.",
    )
    _append_detail_list(lines, "연결 산출물", feature["affected_deliverables"])
    lines.append("")


def render_approval_review(
    register: dict[str, Any],
    policy: dict[str, Any],
    report: dict[str, Any],
    questions: dict[str, dict[str, Any]],
    owner_rules: dict[str, dict[str, Any]],
    rules: dict[str, Any],
) -> str:
    decisions = {item["canonical_decision_id"]: item for item in register["decisions"]}
    source_by_id = {item["id"]: item for item in register["source_bindings"]}
    lines = [
        "# WalkSafe 유효 정책 기준선 후보 검토",
        "",
        "> 상태: **IN_REVIEW / NOT_APPROVED**",
        ">",
        "> 목적: 제품책임자 답변 10개가 54개 기능 정책에 정확히 반영됐는지 비전공자 관점에서 최종 확인한다.",
        "> 이 문서 확인은 구현·시험·법률 검토 또는 출시 승인을 대신하지 않는다.",
        "",
        "## 권장 읽는 순서",
        "",
        "1. 바로 아래의 검토 결과와 고위험 한계를 먼저 확인한다.",
        "2. 2절에서 이번에 고른 10개 답변이 맞는지 확인한다.",
        "3. 3절에서 답변 후에도 남긴 세부사항을 확인한다.",
        "4. 4절의 18개 분야 요약표를 읽고, 수정할 기능만 FP ID의 상세 설명을 확인한다.",
        "5. 5~7절에서 별도 담당 gate와 승인 전 차단 항목을 확인한다.",
        "",
        "## 1. 이번 검토 결과",
        "",
        f"- 통제 답변: `{source_by_id['SRC-OWNER-ANSWERS-CONTROLLED']['sha256']}`",
        f"- 제품책임자 질문: **{register['coverage']['owner_answer_count']}/10 응답**",
        f"- 기능 정책: **{policy['summary']['feature_count']}/54 검토본에 수록** (54개가 모두 완전히 확정됐다는 뜻은 아님)",
        f"- 이번 10개 답변이 직접 바꾼 기능: **{policy['summary']['owner_decision_linked_feature_count']}/54**",
        f"- 정책 상태: **큰 방향 확정·세부 미정 {policy['summary']['policy_status_counts'].get('CONFIRMED_WITH_OPEN_DETAILS', 0)}개 / 조건부 {policy['summary']['policy_status_counts'].get('CONDITIONAL', 0)}개 / 충돌 {policy['summary']['policy_status_counts'].get('CONFLICTING', 0)}개**",
        f"- 아직 세부사항이 남은 기능: **{policy['summary']['unresolved_feature_count']}/54, 총 {policy['summary']['unresolved_item_count']}개 항목**",
        f"- 감사 질문 책임 분류: **{register['coverage']['mapped_source_question_count']}/144**",
        f"- canonical 결정 원장: **{register['coverage']['actual_decisions']}/135**",
        f"- 기능 영향이 연결된 결정·gate: **{register['coverage']['feature_linked_decision_count']}/135**",
        f"- 과거 확정 분류 중 단일 정책값 재확인 필요: **{register['coverage']['source_normalization_pending_count']}개**",
        f"- 남은 정책 충돌: **{len(policy['summary']['remaining_conflict_feature_ids'])}개** ({', '.join(policy['summary']['remaining_conflict_feature_ids'])})",
        "- 현재 판정: 이번 10개 제품책임자 답변 통합 완료, 별도 담당 gate와 사용자 최종 승인은 미완료",
        "",
        "### 반드시 확인할 안전·개인정보 한계",
        "",
        "- **단독 보행은 아직 입증되지 않았다.** 일반 도심에서 WalkSafe만으로 보행한다는 목표와 실제 안전성 증거는 다르다. (FP-006, FP-050)",
        "- **지원 기능 하나의 지속 장애도 전체 기능 정지로 이어지는 현재 정책이다.** 어떤 실패까지 전면정지할지는 세부 기준이 남아 있다. (FP-043)",
        "- **영상·음성·정확 위치 등 광범위한 원본 수집 의도가 있다.** 보존·삭제·철회·주변인·청소년 처리는 법률·개인정보 검토 전이다. (FP-034, FP-046)",
        "- **보안사고를 사용자에게 알리지 않으려는 기존 의도는 확정 출시정책이 아니다.** 법적 의무와 피해 대응 검토가 필요하다. (FP-048)",
        "- **독립 모델평가와 대상 사용자 UAT를 생략한 기존 답변만으로 안전성을 입증할 수 없다.** 별도 시험 gate가 남는다. (FP-038, FP-050)",
        "- **문제 버전으로 되돌리는 rollback 없이 수정 버전만 내는 기존 방향은 운영 위험이 크다.** 출시 전 재검토해야 한다. (FP-039, FP-051)",
        "- **월 운영비 0원과 대용량 원본 장기 저장은 현재 양립하지 않는다.** 보존량·기간 또는 예산을 바꾸거나 실현 가능 증거가 필요하다. (FP-053)",
        "",
        "## 2. 이번에 선택한 10개 제품 결정",
        "",
        "| 결정 | 선택 | 쉬운 의미 | 주요 영향 | 기능 | 추천과 다름 |",
        "|---|---|---|---|---|---|",
    ]
    non_recommended_ids = set(rules["expected_outcomes"]["non_recommended_question_ids"])
    for canonical_id, rule in owner_rules.items():
        decision = decisions[canonical_id]
        question = questions[rule["question_id"]]
        lines.append(
            "| " + " | ".join([
                _md(f"{question['id']} {question['title']}"),
                _md(decision["selected_label"]),
                _md(decision["decision_statement"].split(": ", 1)[-1]),
                _md(decision["impact_summary"]),
                _md(", ".join(decision["affected_feature_ids"])),
                "예" if question["id"] in non_recommended_ids else "아니요",
            ]) + " |"
        )
    lines.extend([
        "",
        "### 추천과 다르게 선택한 항목",
        "",
    ])
    for canonical_id, rule in owner_rules.items():
        if rule["question_id"] not in non_recommended_ids:
            continue
        decision = decisions[canonical_id]
        lines.append(f"- **{decision['title']} — {decision['selected_label']}**: {decision['impact_summary']}")

    lines.extend([
        "",
        "## 3. 답변 뒤에도 자동으로 확정하지 않은 내용",
        "",
        "아래 내용은 이번 선택에서 직접 답하지 않았으므로 열린 항목으로 남긴다.",
        "",
    ])
    for canonical_id, rule in owner_rules.items():
        lines.append(f"### {decisions[canonical_id]['title']} ({canonical_id})")
        lines.extend(f"- {item}" for item in rule["open_details"])
        lines.append("")

    pending_normalizations = [
        item
        for item in register["decisions"]
        if item["resolution_status"] == "PENDING_SOURCE_NORMALIZATION"
    ]
    lines.extend([
        "### 과거 확정 분류에서 다시 확인할 항목",
        "",
        "이 항목들은 과거 자료에서 ‘이미 확정’으로 분류됐지만, 실제로는 하나의 정책값으로 닫히지 않았다. 이번 10개 답변과 별개이며 기준선 승인 전에 다시 확인한다.",
        "",
        "| 결정 | 현재 확인된 내용 | 아직 확인할 점 | 영향 기능 |",
        "|---|---|---|---|",
    ])
    for decision in pending_normalizations:
        lines.append(
            "| " + " | ".join([
                _md(f"{decision['decision_id']} {decision['title']}"),
                _md(decision["decision_statement"] or "단일 정책문이 남아 있지 않음"),
                _md("; ".join(decision["open_details"])),
                _md(", ".join(decision["affected_feature_ids"])),
            ]) + " |"
        )
    lines.append("")

    lines.extend([
        "## 4. 비전공자용 54개 기능 정책 요약",
        "",
        "정책 상태와 현재 구현 상태는 서로 다르다. 정책이 정해져도 코드·시험 증거가 없으면 구현 완료가 아니다.",
        "",
        "### 자주 쓰는 말",
        "",
        "| 용어 | 쉬운 뜻 |",
        "|---|---|",
        "| Android 네이티브 앱 | 웹페이지가 아니라 Android 휴대폰에 직접 설치하는 앱 |",
        "| TMAP | 목적지 검색과 큰 이동 경로를 받는 외부 지도 서비스 |",
        "| on-device | 영상·음성 등을 서버로 보내기 전에 휴대폰 안에서 처리하는 방식 |",
        "| session | 로그인 상태 또는 한 번의 보행 시작부터 종료까지 이어지는 작업 단위 |",
        "| queue(대기열) | 지금 보내지 못한 데이터를 순서대로 안전하게 보관하는 곳 |",
        "| receipt·hash | 서버가 파일 전체를 정확히 받았는지 확인하는 수신 증거와 지문값 |",
        "| gate | 다음 단계로 넘어가기 전에 반드시 통과해야 하는 검토나 시험 |",
        "| E2E 시험 | 사용 시작부터 결과 확인까지 전체 흐름을 실제처럼 잇는 시험 |",
        "| Depth·TTC | 물체까지의 거리와 충돌까지 남은 시간을 계산하는 값 |",
        "| rollback | 새 버전에 문제가 생겼을 때 이전 정상 버전으로 되돌리는 것 |",
        "| MFA | 비밀번호 외에 인증수단을 하나 더 확인하는 관리자 보안 방식 |",
        "| OTP | 한 번만 쓸 수 있는 문자 인증번호 |",
        "| rate limit | 짧은 시간에 인증·요청을 너무 많이 하지 못하게 막는 제한 |",
        "| session revocation | 특정 기기의 로그인 권한을 서버에서 무효로 만드는 것 |",
        "| OS kill·crash | Android가 앱을 종료하거나 앱 오류로 갑자기 멈추는 상황 |",
        "| capability | 그 기기에서 실제 사용할 수 있는 카메라·거리·음성 같은 기능 |",
        "| escalation | 담당자가 해결하지 못한 장애를 더 높은 책임자에게 올리는 절차 |",
        "| P0·SEV | 장애의 심각도와 대응 우선순위를 표시하는 등급 |",
        "| FPS·p50·p95 | 초당 처리 화면 수와 측정값의 절반·95%가 그 안에 들어오는 지연 기준 |",
        "| telemetry | 앱·서버가 정상 동작하는지 확인하기 위해 보내는 운영 상태 기록 |",
        "| manifest | 함께 배포할 앱·모델·설정의 정확한 버전 목록 |",
        "| chunk | 큰 파일을 전송하기 좋게 나눈 작은 조각 |",
        "| scheduler | 정해진 조건·시각에 작업을 실행하는 예약 장치 |",
        "| TLS·token·secret | 통신 암호화 방식, 로그인 증표, 외부에 노출하면 안 되는 인증정보 |",
        "| SAST·SCA·DAST | 코드·외부 라이브러리·실행 중 서비스를 각각 점검하는 보안검사 |",
        "| UAT | 실제 사용자 관점에서 전체 기능을 써 보고 받아들일 수 있는지 확인하는 시험 |",
        "| STT·TTS | 사용자의 말을 글자·명령으로 바꾸는 기능과 글자를 음성으로 읽는 기능 |",
        "| APK·AAB | Android 앱 설치 파일과 Google Play 배포용 앱 묶음 |",
        "| SBOM | 앱에 포함된 외부 라이브러리와 버전을 적은 구성품 목록 |",
        "| SemVer | 큰 변경·기능 추가·오류 수정 순서로 버전을 표시하는 규칙 |",
        "",
        "근거 ID에서 `Q-`는 최초 질문 답변, `FUP-`는 Android 전환 후속 답변, `DIR-`는 최신 직접 설명, `ODQ-`는 이번 10개 질문, `CD-`는 중복을 합친 결정, `RTE-`는 코드·시험 등 현재 구현 근거를 뜻한다.",
        "",
        "### 18개 분야 바로가기",
        "",
        *[
            f"- [{area['id']} {area['title']}](#{area['id'].lower()})"
            for area in policy["areas"]
        ],
        "",
    ])
    feature_by_id = {item["id"]: item for item in policy["features"]}
    for area in policy["areas"]:
        lines.extend([
            f"<a id=\"{area['id'].lower()}\"></a>",
            "",
            f"### {area['id']} {area['title']}",
            "",
            area["plain_scope"],
            "",
            "| 기능 | 현재 정책을 쉬운 말로 | 정책 상태 | 구현 판단 | 이번 10개 답변 | 남은 세부사항 |",
            "|---|---|---|---|---|---:|",
        ])
        for feature_id in area["feature_ids"]:
            feature = feature_by_id[feature_id]
            lines.append(
                "| " + " | ".join([
                    _md(f"{feature['id']} {feature['name']}"),
                    _md(feature["plain_summary"]),
                    _md(
                        f"{POLICY_STATUS_LABELS[feature['policy']['overall_status']]} "
                        f"({feature['policy']['overall_status']})"
                    ),
                    _md(feature["implementation"]["plain_status"]),
                    _md(", ".join(feature["owner_decision_refs"]) or "이번 답변 변경 없음"),
                    str(len(feature["policy"]["unresolved"])),
                ]) + " |"
            )
        lines.append("")
        for feature_id in area["feature_ids"]:
            _append_feature_detail(lines, feature_by_id[feature_id])

    lines.extend([
        "## 5. 별도 담당·증거가 필요한 후속 gate",
        "",
        "| 책임 유형 | canonical 결정 | 원 감사 항목 | 현재 상태 | 담당 역할 | 완료 조건 |",
        "|---|---:|---:|---|---|---|",
    ])
    for queue in register["queue_summary"]:
        resolution_display = ", ".join(
            f"{status} {count}"
            for status, count in queue["resolution_status_counts"].items()
        )
        lines.append(
            "| " + " | ".join([
                _md(queue["responsibility_type"]),
                str(queue["canonical_decision_count"]),
                str(queue["source_question_count"]),
                _md(resolution_display),
                _md(queue["owner_role"]),
                _md(queue["closure_criteria"]),
            ]) + " |"
        )

    lines.extend([
        "",
        "## 6. 승인 체크리스트",
        "",
        "### 자동 검증 완료",
        "",
        "- [x] 수령 시 답변 원본과 통제 사본의 SHA-256 일치 기록 확인",
        "- [x] 10개 제품책임자 질문 모두 유효한 선택값",
        "- [x] 기능 설명 수정 요청과 입력 오류 0건",
        "- [x] 10개 결정 모두 기능과 산출물에 직접 연결",
        "- [x] 54개 기능·18개 분야 누락·중복 0건",
        "- [x] 144개 감사 질문을 135개 canonical 결정에 추적",
        "- [x] 135개 결정·후속 gate를 영향받는 기능에 직접 연결",
        "- [x] 독립 범위 검토 원기록 3개의 상대경로·SHA-256·각 45개 범위 확인",
        "- [x] Depth 관련 두 충돌을 제한 경고 정책으로 해소",
        "- [x] 출시 단계·사고 통지·접근성·모델 평가·장애 복구의 단계 혼동과 모순 재검토",
        "",
        "### 지금 프로젝트관리자인 사용자가 확인할 항목",
        "",
        "- [ ] 위 10개 선택·쉬운 의미·영향이 실제 답변과 정확히 일치하는지 확인",
        "- [ ] 54개 기능의 요약과 기능별 상세가 의도한 정책·작동 방식과 맞는지 확인",
        "- [ ] 추천과 다르게 선택한 5개 항목의 영향 확인",
        "- [ ] 상단의 안전·개인정보·시험·rollback 한계를 인지하고 잘못된 정책은 FP ID로 수정",
        "- [ ] 이번 답변에서 자동 확정하지 않은 28개 세부사항이 열린 상태임을 확인",
        f"- [ ] 과거 확정 분류 중 단일 정책값 재확인이 필요한 {register['coverage']['source_normalization_pending_count']}개 항목 확인",
        "",
        "### 기준선 승인 전에 닫아야 할 항목",
        "",
        "- [ ] FP-053의 보존량·기간 또는 예산 변경, 혹은 실현 가능 증거 확보",
        "- [ ] 기술 제안 54개 검토·채택",
        "- [ ] 실측 gate 21개 실행·판정",
        "- [ ] 전문가 검토 17개 원 감사 항목 완료",
        "- [ ] 생성 증거 2개 대상 형상에 결속",
        f"- [ ] 기능별 미정 세부사항 {policy['summary']['unresolved_item_count']}개를 담당 결정·gate에 연결하고 처리",
        "- [ ] 프로젝트관리자인 사용자 본인의 최종 승인 여부 확인",
        "- [ ] 승인자·승인시각·승인 버전 기록",
        "",
        "## 7. 현재 승인 경계",
        "",
        "- `QUESTIONNAIRE_COMPLETE`: 완료",
        "- `OWNER_POLICY_REVIEW`: 검토 가능 상태",
        "- `BASELINE_APPROVED`: **아직 아님**",
        "- `RELEASE_ELIGIBLE`: **아직 아님**",
        "",
        "이 검토본에서 잘못된 정책이 있으면 승인 전에 결정 ID 또는 기능 ID를 지정해 수정한다. 독립 전문검토는 법률·보안·접근성 등 해당 분야의 검토이며, 최종 기준선 승인권은 프로젝트관리자인 사용자 본인에게 있다. 위 미완료 gate와 사용자 최종 승인 기록이 없으면 기준선 승인으로 표시하지 않는다.",
        "",
        "## 8. 생성 근거",
        "",
        f"- 후보 ID: `{register['candidate_id']}`",
        f"- 입력 결속 SHA-256: `{register['input_binding_sha256']}`",
        "- 기계 판독 결정 원장: `walksafe-effective-decision-register.json`",
        "- 기계 판독 기능 정책: `walksafe-feature-policy-effective-candidate.json`",
        "- 생성기: `scripts/build_walksafe_effective_baseline.py`",
    ])
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify generated outputs are current")
    args = parser.parse_args(argv)
    try:
        register, policy, review = build_effective_artifacts()
        outputs = {
            REGISTER_OUTPUT_PATH: _json_text(register),
            POLICY_OUTPUT_PATH: _json_text(policy),
            REVIEW_OUTPUT_PATH: review,
        }
        if args.check:
            for path, expected in outputs.items():
                if not path.is_file() or path.read_text(encoding="utf-8") != expected:
                    raise EffectiveBaselineValidationError(f"generated output is stale or missing: {path}")
            print("PASS: 135 decisions, 10 owner answers, 54 features, approval review current")
            return 0
        for path, content in outputs.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            print(f"WROTE: {path}")
        return 0
    except (EffectiveBaselineValidationError, interview.DecisionInterviewValidationError, OSError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

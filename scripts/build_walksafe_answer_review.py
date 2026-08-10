#!/usr/bin/env python3
"""Build the standalone WalkSafe answer review and delta questionnaire."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import sys
from typing import Any

try:
    from scripts import build_walksafe_project_questionnaire as question_builder
except ModuleNotFoundError:  # Direct execution from the scripts directory.
    import build_walksafe_project_questionnaire as question_builder  # type: ignore[no-redef]


REPO_ROOT = Path(__file__).resolve().parents[1]
QUESTIONNAIRE_DIR = REPO_ROOT / "docs" / "control" / "questionnaire"
SOURCE_RECORDS_DIR = QUESTIONNAIRE_DIR / "source-records"
DEFAULT_QUESTIONS_PATH = QUESTIONNAIRE_DIR / "walksafe-project-decision-questions.json"
DEFAULT_ANSWERS_PATH = (
    SOURCE_RECORDS_DIR / "walksafe-project-decisions-20260717-answers.json"
)
DEFAULT_ANALYSIS_PATH = QUESTIONNAIRE_DIR / "walksafe-answer-review-analysis.json"
DEFAULT_FOLLOWUPS_PATH = QUESTIONNAIRE_DIR / "walksafe-answer-review-followups.json"
DEFAULT_TEMPLATE_PATH = QUESTIONNAIRE_DIR / "answer-review-template.html"
DEFAULT_OUTPUT_PATH = (
    QUESTIONNAIRE_DIR / "walksafe-project-decision-answer-review-20260718.html"
)
DATA_PLACEHOLDER = "__WALKSAFE_ANSWER_REVIEW_DATA__"

ANSWER_FIELDS = {
    "questionnaire_schema_version",
    "answers_schema_version",
    "question_set_id",
    "question_set_hash",
    "exported_at",
    "answers",
    "notes",
    "review_required",
    "unresolved_required",
    "conflicts",
}
ANALYSIS_FIELDS = {"schema_version", "category_reviews", "findings", "manual_flags"}
CATEGORY_REVIEW_FIELDS = {
    "category_id",
    "headline",
    "confirmed_meanings",
    "implications",
    "open_points",
    "key_question_ids",
}
FINDING_FIELDS = {
    "id",
    "severity",
    "area",
    "title",
    "summary",
    "question_ids",
    "impact",
    "recommended_action",
}
MANUAL_FLAG_FIELDS = {"scope_replaced", "ambiguous_note", "critical"}
FOLLOWUP_FIELDS = {
    "schema_version",
    "groups",
    "questions",
    "activation_rules",
    "consistency_rules",
    "detail_requirements",
}
GROUP_FIELDS = {"id", "title", "description", "order"}
FOLLOWUP_QUESTION_FIELDS = {
    "id",
    "group_id",
    "priority",
    "title",
    "prompt",
    "why_it_matters",
    "required",
    "answer_type",
    "options",
    "recommended_option_ids",
    "recommendation_reason",
    "source_question_ids",
    "conditional_note",
}
OPTION_FIELDS = {"id", "label", "description", "tradeoffs"}
CONFLICT_REQUIRED_FIELDS = {
    "rule_id",
    "question_id",
    "trigger_question_id",
    "severity",
    "message",
}
FOLLOWUP_PRIORITIES = {"P0", "P1", "P2"}
FINDING_SEVERITIES = {"BLOCKER", "HIGH", "MEDIUM", "INFO"}
FINDING_COUNT = 13
FOLLOWUP_COUNT = 75
SOURCE_QUESTION_COUNT = 286
SOURCE_NOTE_ERROR_COUNT = 102
ACTIVATION_RULE_FIELDS = {
    "question_id",
    "depends_on_question_id",
    "inactive_option_ids",
    "reason",
}
DETAIL_REQUIREMENT_FIELDS = {"question_id", "prompt"}
CONSISTENCY_RULE_FIELDS = {"id", "severity", "when_all", "message"}
CONSISTENCY_CONDITION_FIELDS = {"question_id", "option_ids"}
ID_PATTERN = re.compile(r"^[A-Za-z][A-Za-z0-9._-]*$")


class AnswerReviewValidationError(ValueError):
    """Raised when an answer-review input violates its contract."""


def _strict_object_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise AnswerReviewValidationError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load_strict_json(path: Path) -> dict[str, Any]:
    """Read a UTF-8 JSON object while rejecting duplicate keys and NaN/Infinity."""

    def reject_constant(value: str) -> Any:
        raise AnswerReviewValidationError(f"non-finite JSON number is forbidden: {value}")

    try:
        payload = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=_strict_object_pairs,
            parse_constant=reject_constant,
        )
    except AnswerReviewValidationError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise AnswerReviewValidationError(f"cannot read strict JSON from {path}: {exc}") from exc
    if not isinstance(payload, dict):
        raise AnswerReviewValidationError(f"JSON root must be an object: {path}")
    return payload


def _exact_fields(value: dict[str, Any], expected: set[str], label: str) -> None:
    missing = sorted(expected - value.keys())
    unexpected = sorted(value.keys() - expected)
    if missing or unexpected:
        raise AnswerReviewValidationError(
            f"{label} fields are invalid: missing={missing}; unexpected={unexpected}"
        )


def _object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise AnswerReviewValidationError(f"{label} must be an object")
    return value


def _string(value: Any, label: str, *, allow_empty: bool = False) -> str:
    if not isinstance(value, str) or (not allow_empty and not value.strip()):
        qualifier = "a string" if allow_empty else "a non-empty string"
        raise AnswerReviewValidationError(f"{label} must be {qualifier}")
    return value


def _identifier(value: Any, label: str) -> str:
    identifier = _string(value, label)
    if ID_PATTERN.fullmatch(identifier) is None:
        raise AnswerReviewValidationError(f"{label} is not a valid identifier: {identifier}")
    return identifier


def _string_list(
    value: Any,
    label: str,
    *,
    allowed: set[str] | None = None,
    allow_empty: bool = True,
) -> list[str]:
    if not isinstance(value, list):
        raise AnswerReviewValidationError(f"{label} must be an array")
    result = [_string(item, f"{label}[{index}]") for index, item in enumerate(value)]
    if not allow_empty and not result:
        raise AnswerReviewValidationError(f"{label} must not be empty")
    if len(result) != len(set(result)):
        raise AnswerReviewValidationError(f"{label} must not contain duplicates")
    if allowed is not None:
        unknown = sorted(set(result) - allowed)
        if unknown:
            raise AnswerReviewValidationError(f"{label} contains unknown IDs: {unknown}")
    return result


def _canonical_json(value: Any) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def object_sha256(value: Any) -> str:
    return hashlib.sha256(_canonical_json(value).encode("utf-8")).hexdigest()


def file_sha256(path: Path) -> str:
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError as exc:
        raise AnswerReviewValidationError(f"cannot hash {path}: {exc}") from exc


def _answer_is_valid(question: dict[str, Any], value: Any) -> bool:
    option_ids = {option["id"] for option in question["options"]}
    answer_type = question["answer_type"]
    if answer_type == "single_choice":
        return isinstance(value, str) and value in option_ids
    if answer_type == "multi_choice":
        return (
            isinstance(value, list)
            and bool(value)
            and len(value) == len(set(value))
            and all(isinstance(item, str) and item in option_ids for item in value)
        )
    if answer_type == "text":
        return isinstance(value, str) and bool(value.strip())
    if answer_type == "number":
        return (
            not isinstance(value, bool)
            and isinstance(value, (int, float))
            and (not isinstance(value, float) or math.isfinite(value))
            and question["validation"]["min"] <= value <= question["validation"]["max"]
        )
    return False


def _condition_matches(
    question: dict[str, Any], condition: dict[str, Any], answers: dict[str, Any]
) -> bool:
    answered = question["id"] in answers and _answer_is_valid(question, answers[question["id"]])
    value = answers.get(question["id"])
    operator = condition["operator"]
    if operator == "answered":
        return answered
    if operator == "unanswered":
        return not answered
    if not answered:
        return False
    if operator == "equals":
        return value == condition["value"]
    if operator == "not_equals":
        return value != condition["value"]
    includes = (
        condition["value"] in value
        if isinstance(value, list)
        else isinstance(value, str) and str(condition["value"]) in value
    )
    if operator == "includes":
        return includes
    if operator == "excludes":
        return not includes
    return False


def evaluate_source_conflicts(
    questionnaire: dict[str, Any], answers: dict[str, Any], notes: dict[str, str]
) -> list[dict[str, Any]]:
    """Mirror questionnaire-template.html's evaluateConflicts in the same order."""

    questions = questionnaire["questions"]
    by_id = {question["id"]: question for question in questions}
    conflicts: list[dict[str, Any]] = []
    for owner in questions:
        owner_answered = owner["id"] in answers and _answer_is_valid(
            owner, answers[owner["id"]]
        )
        for dependency_id in owner["dependencies"]:
            dependency = by_id.get(dependency_id)
            dependency_answered = bool(
                dependency
                and dependency_id in answers
                and _answer_is_valid(dependency, answers[dependency_id])
            )
            if dependency and owner_answered and not dependency_answered:
                conflicts.append(
                    {
                        "rule_id": f"DEPENDENCY-{owner['id']}-{dependency_id}",
                        "question_id": owner["id"],
                        "trigger_question_id": dependency_id,
                        "severity": "error",
                        "kind": "unresolved_dependency",
                        "message": f"선행 질문 {dependency_id}의 답변이 필요합니다.",
                    }
                )
        for rule in owner["conflict_rules"]:
            if rule.get("if") and not _condition_matches(owner, rule["if"], answers):
                continue
            trigger = by_id.get(rule["when"]["question_id"])
            if not trigger or not _condition_matches(trigger, rule["when"], answers):
                continue
            conflicts.append(
                {
                    "rule_id": rule["id"],
                    "question_id": owner["id"],
                    "trigger_question_id": trigger["id"],
                    "severity": rule["severity"],
                    "message": rule["message"],
                }
            )

    for question in questions:
        if question["id"] not in answers or not _answer_is_valid(
            question, answers[question["id"]]
        ):
            continue
        if question["answer_type"] == "text":
            continue
        answer = answers[question["id"]]
        reasons: list[str] = []
        if (
            question["answer_type"] == "single_choice"
            and answer not in question["recommended_option_ids"]
        ):
            reasons.append("추천안과 다른 선택")
        if question["answer_type"] == "multi_choice" and sorted(answer) != sorted(
            question["recommended_option_ids"]
        ):
            reasons.append("추천 조합과 다른 선택")
        if answer == "custom" or (isinstance(answer, list) and "custom" in answer):
            reasons.append("사용자 정의 선택")
        if question["answer_type"] == "number":
            reasons.append("수치 결정")
        if any(
            item["question_id"] == question["id"] and item["severity"] == "warning"
            for item in conflicts
        ):
            reasons.append("활성 경고에 대한 수용 근거")
        note = notes.get(question["id"])
        if reasons and (not isinstance(note, str) or not note.strip()):
            unique_reasons = list(dict.fromkeys(reasons))
            conflicts.append(
                {
                    "rule_id": f"NOTE-{question['id']}",
                    "question_id": question["id"],
                    "trigger_question_id": question["id"],
                    "severity": "error",
                    "kind": "required_decision_note",
                    "message": "결정 메모가 필요합니다: "
                    + ", ".join(unique_reasons)
                    + ". 구체 값·예외·근거·책임자를 적어 주세요.",
                }
            )
    return conflicts


def validate_source_answers(
    payload: dict[str, Any], questionnaire: dict[str, Any], question_set_hash: str
) -> list[dict[str, Any]]:
    _exact_fields(payload, ANSWER_FIELDS, "source answers")
    metadata = questionnaire["metadata"]
    expected_scalars = {
        "questionnaire_schema_version": metadata["schema_version"],
        "answers_schema_version": "walksafe.questionnaire-answers.v2",
        "question_set_id": metadata["question_set_id"],
        "question_set_hash": question_set_hash,
    }
    for field, expected in expected_scalars.items():
        if payload[field] != expected:
            raise AnswerReviewValidationError(
                f"source answers {field} mismatch: expected {expected!r}"
            )
    _string(payload["exported_at"], "source answers.exported_at")
    answers = _object(payload["answers"], "source answers.answers")
    notes = _object(payload["notes"], "source answers.notes")
    questions = questionnaire["questions"]
    by_id = {question["id"]: question for question in questions}
    if len(questions) != SOURCE_QUESTION_COUNT:
        raise AnswerReviewValidationError(
            f"canonical questionnaire must contain {SOURCE_QUESTION_COUNT} questions"
        )
    if set(answers) != set(by_id):
        missing = sorted(set(by_id) - set(answers))
        unknown = sorted(set(answers) - set(by_id))
        raise AnswerReviewValidationError(
            f"source answers must contain exactly all question IDs: missing={missing}; unknown={unknown}"
        )
    for question_id, value in answers.items():
        if not _answer_is_valid(by_id[question_id], value):
            raise AnswerReviewValidationError(
                f"source answers.answers.{question_id} violates its answer contract"
            )
    for question_id, note in notes.items():
        if question_id not in by_id:
            raise AnswerReviewValidationError(f"source note has unknown question ID: {question_id}")
        _string(note, f"source answers.notes.{question_id}")
    review_required = _string_list(
        payload["review_required"], "source answers.review_required", allowed=set(by_id)
    )
    unresolved = _string_list(
        payload["unresolved_required"],
        "source answers.unresolved_required",
        allowed=set(by_id),
    )
    expected_unresolved = [
        question["id"]
        for question in questions
        if question["required"] and question["id"] not in answers
    ]
    if unresolved != expected_unresolved:
        raise AnswerReviewValidationError("source unresolved_required does not match answers")
    if review_required != sorted(review_required):
        raise AnswerReviewValidationError("source review_required must be sorted")
    exported_conflicts = payload["conflicts"]
    if not isinstance(exported_conflicts, list):
        raise AnswerReviewValidationError("source answers.conflicts must be an array")
    for index, raw in enumerate(exported_conflicts):
        conflict = _object(raw, f"source answers.conflicts[{index}]")
        expected_fields = set(CONFLICT_REQUIRED_FIELDS)
        if conflict.get("kind") is not None:
            expected_fields.add("kind")
        _exact_fields(conflict, expected_fields, f"source answers.conflicts[{index}]")
    recomputed = evaluate_source_conflicts(questionnaire, answers, notes)
    if exported_conflicts != recomputed:
        raise AnswerReviewValidationError(
            "source exported conflicts do not exactly match recomputed questionnaire conflicts"
        )
    if len(recomputed) != SOURCE_NOTE_ERROR_COUNT or any(
        item.get("kind") != "required_decision_note" or item["severity"] != "error"
        for item in recomputed
    ):
        raise AnswerReviewValidationError(
            f"expected exactly {SOURCE_NOTE_ERROR_COUNT} required_decision_note errors"
        )
    return recomputed


def validate_analysis(
    payload: dict[str, Any], questionnaire: dict[str, Any]
) -> None:
    _exact_fields(payload, ANALYSIS_FIELDS, "analysis")
    if payload["schema_version"] != "walksafe.answer-review-analysis.v1":
        raise AnswerReviewValidationError("analysis.schema_version is unsupported")
    categories = questionnaire["categories"]
    category_ids = [category["id"] for category in categories]
    question_ids = {question["id"] for question in questionnaire["questions"]}
    reviews = payload["category_reviews"]
    if not isinstance(reviews, list) or len(reviews) != len(categories):
        raise AnswerReviewValidationError("analysis must have one category review per category")
    if [item.get("category_id") for item in reviews if isinstance(item, dict)] != category_ids:
        raise AnswerReviewValidationError("analysis category reviews must follow canonical category order")
    for index, raw in enumerate(reviews):
        review = _object(raw, f"analysis.category_reviews[{index}]")
        _exact_fields(review, CATEGORY_REVIEW_FIELDS, f"analysis.category_reviews[{index}]")
        _string(review["headline"], f"analysis.category_reviews[{index}].headline")
        for field in ("confirmed_meanings", "implications", "open_points"):
            _string_list(review[field], f"analysis.category_reviews[{index}].{field}")
        _string_list(
            review["key_question_ids"],
            f"analysis.category_reviews[{index}].key_question_ids",
            allowed=question_ids,
            allow_empty=False,
        )
    findings = payload["findings"]
    if not isinstance(findings, list) or len(findings) != FINDING_COUNT:
        raise AnswerReviewValidationError(f"analysis must contain exactly {FINDING_COUNT} findings")
    for index, raw in enumerate(findings, 1):
        finding = _object(raw, f"analysis.findings[{index - 1}]")
        _exact_fields(finding, FINDING_FIELDS, f"analysis.findings[{index - 1}]")
        if finding["id"] != f"FND-{index:03d}":
            raise AnswerReviewValidationError("analysis finding IDs must be contiguous FND-001...")
        if finding["severity"] not in FINDING_SEVERITIES:
            raise AnswerReviewValidationError(f"invalid finding severity: {finding['severity']}")
        for field in ("area", "title", "summary", "impact", "recommended_action"):
            _string(finding[field], f"analysis.findings[{index - 1}].{field}")
        _string_list(
            finding["question_ids"],
            f"analysis.findings[{index - 1}].question_ids",
            allowed=question_ids,
            allow_empty=False,
        )
    manual_flags = _object(payload["manual_flags"], "analysis.manual_flags")
    _exact_fields(manual_flags, MANUAL_FLAG_FIELDS, "analysis.manual_flags")
    for field in sorted(MANUAL_FLAG_FIELDS):
        _string_list(
            manual_flags[field], f"analysis.manual_flags.{field}", allowed=question_ids
        )


def validate_followups(
    payload: dict[str, Any], questionnaire: dict[str, Any]
) -> None:
    _exact_fields(payload, FOLLOWUP_FIELDS, "followups")
    if payload["schema_version"] != "walksafe.answer-review-followups.v1":
        raise AnswerReviewValidationError("followups.schema_version is unsupported")
    source_question_ids = {question["id"] for question in questionnaire["questions"]}
    groups = payload["groups"]
    if not isinstance(groups, list) or not groups:
        raise AnswerReviewValidationError("followups.groups must be a non-empty array")
    group_ids: set[str] = set()
    for index, raw in enumerate(groups, 1):
        group = _object(raw, f"followups.groups[{index - 1}]")
        _exact_fields(group, GROUP_FIELDS, f"followups.groups[{index - 1}]")
        group_id = _identifier(group["id"], f"followups.groups[{index - 1}].id")
        if not group_id.startswith("GRP-") or group_id in group_ids:
            raise AnswerReviewValidationError(f"invalid or duplicate followup group ID: {group_id}")
        group_ids.add(group_id)
        if group["order"] != index:
            raise AnswerReviewValidationError("followup group order must be contiguous from 1")
        _string(group["title"], f"followups.groups[{index - 1}].title")
        _string(group["description"], f"followups.groups[{index - 1}].description")
    questions = payload["questions"]
    if not isinstance(questions, list) or len(questions) != FOLLOWUP_COUNT:
        raise AnswerReviewValidationError(
            f"followups.questions must contain exactly {FOLLOWUP_COUNT} questions"
        )
    by_id: dict[str, dict[str, Any]] = {}
    for index, raw in enumerate(questions, 1):
        question = _object(raw, f"followups.questions[{index - 1}]")
        _exact_fields(question, FOLLOWUP_QUESTION_FIELDS, f"followups.questions[{index - 1}]")
        expected_id = f"FUP-{index:03d}"
        if question["id"] != expected_id:
            raise AnswerReviewValidationError("followup question IDs must be contiguous FUP-001...")
        if question["group_id"] not in group_ids:
            raise AnswerReviewValidationError(f"{expected_id} has unknown group_id")
        if question["priority"] not in FOLLOWUP_PRIORITIES:
            raise AnswerReviewValidationError(f"{expected_id} has invalid priority")
        if not isinstance(question["required"], bool):
            raise AnswerReviewValidationError(f"{expected_id}.required must be boolean")
        if question["required"] != (question["priority"] in {"P0", "P1"}):
            raise AnswerReviewValidationError(f"{expected_id} required flag must match priority")
        if question["answer_type"] != "single_choice":
            raise AnswerReviewValidationError(f"{expected_id} must be single_choice")
        for field in ("title", "prompt", "why_it_matters", "recommendation_reason"):
            _string(question[field], f"{expected_id}.{field}")
        _string(question["conditional_note"], f"{expected_id}.conditional_note", allow_empty=True)
        options = question["options"]
        if not isinstance(options, list) or len(options) < 2:
            raise AnswerReviewValidationError(f"{expected_id}.options must have at least two items")
        option_ids: set[str] = set()
        for option_index, raw_option in enumerate(options):
            option = _object(raw_option, f"{expected_id}.options[{option_index}]")
            _exact_fields(option, OPTION_FIELDS, f"{expected_id}.options[{option_index}]")
            option_id = _identifier(option["id"], f"{expected_id}.options[{option_index}].id")
            if option_id in option_ids:
                raise AnswerReviewValidationError(f"{expected_id} has duplicate option ID")
            option_ids.add(option_id)
            for field in ("label", "description", "tradeoffs"):
                _string(option[field], f"{expected_id}.options[{option_index}].{field}")
        if "custom" not in option_ids:
            raise AnswerReviewValidationError(f"{expected_id} must include custom option")
        recommendations = _string_list(
            question["recommended_option_ids"],
            f"{expected_id}.recommended_option_ids",
            allowed=option_ids,
            allow_empty=False,
        )
        if len(recommendations) != 1 or recommendations[0] == "custom":
            raise AnswerReviewValidationError(
                f"{expected_id} must have one non-custom recommendation"
            )
        _string_list(
            question["source_question_ids"],
            f"{expected_id}.source_question_ids",
            allowed=source_question_ids,
            allow_empty=False,
        )
        by_id[expected_id] = question
    activation_rules = payload["activation_rules"]
    if not isinstance(activation_rules, list):
        raise AnswerReviewValidationError("followups.activation_rules must be an array")
    activation_targets: set[str] = set()
    for index, raw in enumerate(activation_rules):
        rule = _object(raw, f"followups.activation_rules[{index}]")
        _exact_fields(rule, ACTIVATION_RULE_FIELDS, f"followups.activation_rules[{index}]")
        target_id = _identifier(
            rule["question_id"], f"followups.activation_rules[{index}].question_id"
        )
        source_id = _identifier(
            rule["depends_on_question_id"],
            f"followups.activation_rules[{index}].depends_on_question_id",
        )
        if target_id not in by_id or source_id not in by_id or target_id == source_id:
            raise AnswerReviewValidationError(
                f"followups.activation_rules[{index}] references an invalid question"
            )
        if target_id in activation_targets:
            raise AnswerReviewValidationError(f"duplicate activation target: {target_id}")
        activation_targets.add(target_id)
        source_options = {option["id"] for option in by_id[source_id]["options"]}
        _string_list(
            rule["inactive_option_ids"],
            f"followups.activation_rules[{index}].inactive_option_ids",
            allowed=source_options,
            allow_empty=False,
        )
        _string(rule["reason"], f"followups.activation_rules[{index}].reason")
    detail_requirements = payload["detail_requirements"]
    if not isinstance(detail_requirements, list) or not detail_requirements:
        raise AnswerReviewValidationError(
            "followups.detail_requirements must be a non-empty array"
        )
    detail_question_ids: set[str] = set()
    for index, raw in enumerate(detail_requirements):
        requirement = _object(raw, f"followups.detail_requirements[{index}]")
        _exact_fields(
            requirement,
            DETAIL_REQUIREMENT_FIELDS,
            f"followups.detail_requirements[{index}]",
        )
        question_id = _identifier(
            requirement["question_id"],
            f"followups.detail_requirements[{index}].question_id",
        )
        if question_id not in by_id or question_id in detail_question_ids:
            raise AnswerReviewValidationError(
                f"invalid or duplicate detail requirement question: {question_id}"
            )
        detail_question_ids.add(question_id)
        _string(
            requirement["prompt"],
            f"followups.detail_requirements[{index}].prompt",
        )
    consistency_rules = payload["consistency_rules"]
    if not isinstance(consistency_rules, list):
        raise AnswerReviewValidationError("followups.consistency_rules must be an array")
    for index, raw in enumerate(consistency_rules, 1):
        rule = _object(raw, f"followups.consistency_rules[{index - 1}]")
        _exact_fields(
            rule, CONSISTENCY_RULE_FIELDS, f"followups.consistency_rules[{index - 1}]"
        )
        if rule["id"] != f"FCR-{index:03d}":
            raise AnswerReviewValidationError(
                "followup consistency rule IDs must be contiguous FCR-001..."
            )
        if rule["severity"] not in {"error", "warning"}:
            raise AnswerReviewValidationError(
                f"followups.consistency_rules[{index - 1}].severity is invalid"
            )
        _string(rule["message"], f"followups.consistency_rules[{index - 1}].message")
        conditions = rule["when_all"]
        if not isinstance(conditions, list) or len(conditions) < 2:
            raise AnswerReviewValidationError(
                f"followups.consistency_rules[{index - 1}].when_all needs two conditions"
            )
        condition_question_ids: set[str] = set()
        for condition_index, raw_condition in enumerate(conditions):
            condition = _object(
                raw_condition,
                f"followups.consistency_rules[{index - 1}].when_all[{condition_index}]",
            )
            _exact_fields(
                condition,
                CONSISTENCY_CONDITION_FIELDS,
                f"followups.consistency_rules[{index - 1}].when_all[{condition_index}]",
            )
            question_id = _identifier(
                condition["question_id"],
                f"followups.consistency_rules[{index - 1}].when_all[{condition_index}].question_id",
            )
            if question_id not in by_id or question_id in condition_question_ids:
                raise AnswerReviewValidationError(
                    f"followup consistency rule {rule['id']} has invalid question"
                )
            condition_question_ids.add(question_id)
            option_ids = {option["id"] for option in by_id[question_id]["options"]}
            _string_list(
                condition["option_ids"],
                f"followups.consistency_rules[{index - 1}].when_all[{condition_index}].option_ids",
                allowed=option_ids,
                allow_empty=False,
            )


def _choice_status(question: dict[str, Any], answer: Any) -> tuple[bool | None, bool]:
    if question["answer_type"] not in {"single_choice", "multi_choice"}:
        return None, False
    recommended = (
        answer in question["recommended_option_ids"]
        if question["answer_type"] == "single_choice"
        else sorted(answer) == sorted(question["recommended_option_ids"])
    )
    custom = answer == "custom" or (isinstance(answer, list) and "custom" in answer)
    return recommended, custom


def _selected_options(question: dict[str, Any], answer: Any) -> list[dict[str, Any]]:
    selected_ids = answer if isinstance(answer, list) else [answer]
    options = {option["id"]: option for option in question["options"]}
    return [
        {
            **options[option_id],
            "recommended": option_id in question["recommended_option_ids"],
        }
        for option_id in selected_ids
        if option_id in options
    ]


def build_review_data(
    questionnaire: dict[str, Any],
    source_answers: dict[str, Any],
    analysis: dict[str, Any],
    followups: dict[str, Any],
    *,
    source_answer_sha256: str,
) -> dict[str, Any]:
    question_builder.validate_questionnaire(questionnaire)
    question_set_hash = question_builder.questionnaire_sha256(questionnaire)
    conflicts = validate_source_answers(source_answers, questionnaire, question_set_hash)
    validate_analysis(analysis, questionnaire)
    validate_followups(followups, questionnaire)
    if not re.fullmatch(r"[0-9a-f]{64}", source_answer_sha256):
        raise AnswerReviewValidationError("source_answer_sha256 must be lowercase SHA-256")

    categories = questionnaire["categories"]
    category_by_id = {category["id"]: category for category in categories}
    reviews_by_category = {
        review["category_id"]: review for review in analysis["category_reviews"]
    }
    manual_sets = {
        key: set(values) for key, values in analysis["manual_flags"].items()
    }
    conflicts_by_question: dict[str, list[dict[str, Any]]] = {}
    for conflict in conflicts:
        conflicts_by_question.setdefault(conflict["question_id"], []).append(conflict)
    findings_by_question: dict[str, list[str]] = {}
    for finding in analysis["findings"]:
        for question_id in finding["question_ids"]:
            findings_by_question.setdefault(question_id, []).append(finding["id"])
    followups_by_source: dict[str, list[str]] = {}
    for question in followups["questions"]:
        for source_id in question["source_question_ids"]:
            followups_by_source.setdefault(source_id, []).append(question["id"])

    decisions: list[dict[str, Any]] = []
    for question in questionnaire["questions"]:
        question_id = question["id"]
        answer = source_answers["answers"][question_id]
        recommended, custom = _choice_status(question, answer)
        question_conflicts = conflicts_by_question.get(question_id, [])
        flags = {
            "answered": True,
            "recommended": recommended,
            "custom": custom,
            "has_source_note": question_id in source_answers["notes"],
            "error": any(item["severity"] == "error" for item in question_conflicts),
            "warning": any(item["severity"] == "warning" for item in question_conflicts),
            "source_note_error": any(
                item.get("kind") == "required_decision_note" for item in question_conflicts
            ),
            "scope_replaced": question_id in manual_sets["scope_replaced"],
            "ambiguous_note": question_id in manual_sets["ambiguous_note"],
            "critical": question_id in manual_sets["critical"],
        }
        decisions.append(
            {
                "id": question_id,
                "category_id": question["category_id"],
                "category_title": category_by_id[question["category_id"]]["title"],
                "decision_level": question["decision_level"],
                "title": question["title"],
                "prompt": question["prompt"],
                "answer_type": question["answer_type"],
                "answer": answer,
                "selected_options": _selected_options(question, answer),
                "source_note": source_answers["notes"].get(question_id, ""),
                "recommendation_reason": question["recommendation_reason"],
                "impacts": question["impacts"],
                "affected_deliverable_types": question["affected_deliverable_types"],
                "conflicts": question_conflicts,
                "finding_ids": findings_by_question.get(question_id, []),
                "followup_ids": followups_by_source.get(question_id, []),
                "flags": flags,
            }
        )

    category_summaries = []
    for category in categories:
        category_decisions = [
            decision for decision in decisions if decision["category_id"] == category["id"]
        ]
        category_summaries.append(
            {
                **category,
                "review": reviews_by_category[category["id"]],
                "decision_count": len(category_decisions),
                "error_count": sum(item["flags"]["error"] for item in category_decisions),
                "scope_replaced_count": sum(
                    item["flags"]["scope_replaced"] for item in category_decisions
                ),
                "critical_count": sum(item["flags"]["critical"] for item in category_decisions),
            }
        )

    analysis_hash = object_sha256(analysis)
    followups_hash = object_sha256(followups)
    binding = {
        "source_answer_sha256": source_answer_sha256,
        "question_set_hash": question_set_hash,
        "analysis_sha256": analysis_hash,
        "followups_sha256": followups_hash,
    }
    return {
        "report_schema_version": "walksafe.answer-review-report.v1",
        "title": "WalkSafe 프로젝트 답변 검토와 Android 기준선 추가 질문",
        "delta_policy": (
            "이 화면의 추가 답변과 근거 보완은 원본 286개 답변을 자동으로 덮어쓰지 않는 delta review입니다. "
            "이후 사람이 유지·대체·폐기 매핑을 승인해야 새 문서 기준선이 됩니다."
        ),
        "binding": {**binding, "storage_binding_hash": object_sha256(binding)},
        "source": {
            "question_set_id": questionnaire["metadata"]["question_set_id"],
            "questionnaire_schema_version": questionnaire["metadata"]["schema_version"],
            "answers_schema_version": source_answers["answers_schema_version"],
            "exported_at": source_answers["exported_at"],
            "answer_count": len(source_answers["answers"]),
            "required_count": sum(
                question["required"] for question in questionnaire["questions"]
            ),
            "unresolved_required_count": len(source_answers["unresolved_required"]),
            "error_count": sum(item["severity"] == "error" for item in conflicts),
            "warning_count": sum(item["severity"] == "warning" for item in conflicts),
            "source_note_error_ids": [
                item["question_id"]
                for item in conflicts
                if item.get("kind") == "required_decision_note"
            ],
        },
        "android_transition": {
            "headline": (
                "Android 네이티브 전환은 확정됐고, ‘온디바이스 학습 모델’이 단말 추론·"
                "개인화·단말 학습 중 무엇인지는 추가 질문에서 확정해야 합니다."
            ),
            "explanation": (
                "기존 답변에는 Web/PWA·브라우저·서버 추론 전제가 함께 남아 있습니다. "
                "범위 대체 표시와 추가 질문을 승인하기 전에는 Android 기준선에 반영된 것으로 볼 수 없습니다."
            ),
            "scope_replaced_count": len(manual_sets["scope_replaced"]),
            "scope_replaced_question_ids": analysis["manual_flags"]["scope_replaced"],
        },
        "categories": category_summaries,
        "findings": analysis["findings"],
        "manual_flags": analysis["manual_flags"],
        "decisions": decisions,
        "followup_groups": followups["groups"],
        "followups": followups["questions"],
        "followup_activation_rules": followups["activation_rules"],
        "followup_consistency_rules": followups["consistency_rules"],
        "followup_detail_requirements": followups["detail_requirements"],
    }


def _script_safe_json(value: Any) -> str:
    rendered = _canonical_json(value)
    return (
        rendered.replace("&", "\\u0026")
        .replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")
    )


def render_answer_review(review_data: dict[str, Any], template: str) -> str:
    placeholder_count = template.count(DATA_PLACEHOLDER)
    if placeholder_count != 1:
        raise AnswerReviewValidationError(
            f"template must contain {DATA_PLACEHOLDER!r} exactly once; found {placeholder_count}"
        )
    rendered = template.replace(DATA_PLACEHOLDER, _script_safe_json(review_data))
    if not rendered.endswith("\n"):
        rendered += "\n"
    return rendered


def build_from_paths(
    questions_path: Path,
    answers_path: Path,
    analysis_path: Path,
    followups_path: Path,
    template_path: Path,
) -> tuple[str, dict[str, Any]]:
    questionnaire = load_strict_json(questions_path)
    source_answers = load_strict_json(answers_path)
    analysis = load_strict_json(analysis_path)
    followups = load_strict_json(followups_path)
    try:
        template = template_path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        raise AnswerReviewValidationError(f"cannot read template {template_path}: {exc}") from exc
    review_data = build_review_data(
        questionnaire,
        source_answers,
        analysis,
        followups,
        source_answer_sha256=file_sha256(answers_path),
    )
    return render_answer_review(review_data, template), review_data


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Build the standalone WalkSafe answer review and delta questionnaire"
    )
    parser.add_argument("--questions", type=Path, default=DEFAULT_QUESTIONS_PATH)
    parser.add_argument("--answers", type=Path, default=DEFAULT_ANSWERS_PATH)
    parser.add_argument("--analysis", type=Path, default=DEFAULT_ANALYSIS_PATH)
    parser.add_argument("--followups", type=Path, default=DEFAULT_FOLLOWUPS_PATH)
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
        rendered, data = build_from_paths(
            args.questions,
            args.answers,
            args.analysis,
            args.followups,
            args.template,
        )
    except (OSError, UnicodeError, AnswerReviewValidationError, question_builder.QuestionnaireValidationError) as exc:
        print(f"answer review build failed: {exc}", file=sys.stderr)
        return 2
    if args.check:
        try:
            current = args.output.read_text(encoding="utf-8")
        except (OSError, UnicodeError):
            current = None
        if current != rendered:
            print(
                f"answer review output is stale: {args.output}; "
                "run python scripts/build_walksafe_answer_review.py",
                file=sys.stderr,
            )
            return 1
        print(f"Answer review is current: {args.output}")
        return 0
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(rendered, encoding="utf-8", newline="\n")
    print(
        f"Wrote {args.output}: source {data['source']['answer_count']}/"
        f"{data['source']['required_count']}, errors {data['source']['error_count']}, "
        f"followups {len(data['followups'])}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

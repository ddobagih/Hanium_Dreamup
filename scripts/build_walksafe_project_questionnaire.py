#!/usr/bin/env python3
"""Validate and build the standalone WalkSafe project-decision questionnaire."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import sys
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_QUESTIONS_PATH = (
    REPO_ROOT
    / "docs"
    / "control"
    / "questionnaire"
    / "walksafe-project-decision-questions.json"
)
DEFAULT_TEMPLATE_PATH = (
    REPO_ROOT / "docs" / "control" / "questionnaire" / "questionnaire-template.html"
)
DEFAULT_OUTPUT_PATH = (
    REPO_ROOT
    / "docs"
    / "control"
    / "questionnaire"
    / "walksafe-project-decision-questionnaire.html"
)
DATA_PLACEHOLDER = "__WALKSAFE_QUESTIONNAIRE_DATA__"

TOP_LEVEL_FIELDS = {"metadata", "categories", "questions"}
METADATA_FIELDS = {
    "schema_version",
    "question_set_id",
    "title",
    "purpose",
    "language",
    "source_policy",
    "completion_rule",
}
METADATA_OPTIONAL_FIELDS = {"expected_question_count", "gap_trace_count"}
CATEGORY_FIELDS = {"id", "title", "description", "order"}
QUESTION_FIELDS = {
    "id",
    "category_id",
    "title",
    "prompt",
    "why_it_matters",
    "current_context",
    "required",
    "decision_level",
    "answer_type",
    "options",
    "recommended_option_ids",
    "recommendation_reason",
    "impacts",
    "affected_deliverable_types",
    "evidence",
    "dependencies",
    "conflict_rules",
    "followup_guidance",
}
OPTION_FIELDS = {"id", "label", "description", "tradeoffs"}
EVIDENCE_FIELDS = {"path", "classification", "note"}
CONFLICT_FIELDS = {"id", "when", "message", "severity"}
WHEN_REQUIRED_FIELDS = {"question_id", "operator"}
VALIDATION_FIELDS = {"min", "max", "unit"}

ANSWER_TYPES = {"single_choice", "multi_choice", "text", "number"}
DECISION_LEVELS = {
    "PRODUCT",
    "POLICY",
    "SPECIFICATION",
    "IMPLEMENTATION",
    "OPERATIONS",
    "ASSURANCE",
}
EVIDENCE_CLASSIFICATIONS = {
    "USER_CONFIRMED",
    "CONFIRMED_BY_CODE",
    "CURRENT_CANDIDATE",
    "CONFLICTING",
    "STALE",
    "UNKNOWN",
}
CONFLICT_OPERATORS = {
    "equals",
    "not_equals",
    "includes",
    "excludes",
    "answered",
    "unanswered",
}
CONFLICT_SEVERITIES = {"error", "warning"}
ID_PATTERN = re.compile(r"^[A-Za-z][A-Za-z0-9._-]*$")
OPTION_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")


class QuestionnaireValidationError(ValueError):
    """Raised when the canonical question set does not match its contract."""


def _strict_object_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise QuestionnaireValidationError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load_questionnaire(path: Path) -> dict[str, Any]:
    """Load a UTF-8 strict-JSON questionnaire object from *path*."""

    def reject_constant(value: str) -> Any:
        raise QuestionnaireValidationError(f"non-finite JSON number is forbidden: {value}")

    try:
        payload = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=_strict_object_pairs,
            parse_constant=reject_constant,
        )
    except QuestionnaireValidationError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise QuestionnaireValidationError(f"cannot read strict JSON from {path}: {exc}") from exc
    if not isinstance(payload, dict):
        raise QuestionnaireValidationError("questionnaire root must be an object")
    return payload


def _expect_object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise QuestionnaireValidationError(f"{label} must be an object")
    return value


def _expect_exact_fields(
    value: dict[str, Any],
    required: set[str],
    label: str,
    *,
    optional: set[str] | None = None,
) -> None:
    optional = optional or set()
    missing = sorted(required - value.keys())
    unexpected = sorted(value.keys() - required - optional)
    if missing or unexpected:
        details: list[str] = []
        if missing:
            details.append(f"missing={missing}")
        if unexpected:
            details.append(f"unexpected={unexpected}")
        raise QuestionnaireValidationError(f"{label} fields are invalid: {'; '.join(details)}")


def _expect_string(value: Any, label: str, *, allow_empty: bool = False) -> str:
    if not isinstance(value, str) or (not allow_empty and not value.strip()):
        qualifier = "a string" if allow_empty else "a non-empty string"
        raise QuestionnaireValidationError(f"{label} must be {qualifier}")
    return value


def _expect_id(value: Any, label: str) -> str:
    identifier = _expect_string(value, label)
    if ID_PATTERN.fullmatch(identifier) is None:
        raise QuestionnaireValidationError(
            f"{label} must start with a letter and contain only letters, digits, '.', '_' or '-'"
        )
    return identifier


def _expect_option_id(value: Any, label: str) -> str:
    identifier = _expect_string(value, label)
    if OPTION_ID_PATTERN.fullmatch(identifier) is None:
        raise QuestionnaireValidationError(
            f"{label} must contain only letters, digits, '.', '_' or '-'"
        )
    return identifier


def _expect_string_list(value: Any, label: str, *, unique: bool = True) -> list[str]:
    if not isinstance(value, list):
        raise QuestionnaireValidationError(f"{label} must be an array")
    result = [_expect_string(item, f"{label}[{index}]") for index, item in enumerate(value)]
    if unique and len(result) != len(set(result)):
        raise QuestionnaireValidationError(f"{label} must not contain duplicate values")
    return result


def _expect_finite_number(value: Any, label: str) -> int | float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise QuestionnaireValidationError(f"{label} must be a number")
    if isinstance(value, float) and not math.isfinite(value):
        raise QuestionnaireValidationError(f"{label} must be finite")
    return value


def _validate_metadata(metadata: Any) -> dict[str, Any]:
    result = _expect_object(metadata, "metadata")
    _expect_exact_fields(
        result,
        METADATA_FIELDS,
        "metadata",
        optional=METADATA_OPTIONAL_FIELDS,
    )
    for field in METADATA_FIELDS:
        _expect_string(result[field], f"metadata.{field}")
    _expect_id(result["question_set_id"], "metadata.question_set_id")
    if result["language"] != "ko":
        raise QuestionnaireValidationError("metadata.language must be 'ko'")
    for field in METADATA_OPTIONAL_FIELDS:
        if field in result and (
            isinstance(result[field], bool)
            or not isinstance(result[field], int)
            or result[field] < 0
        ):
            raise QuestionnaireValidationError(
                f"metadata.{field} must be a non-negative integer"
            )
    return result


def _validate_categories(categories: Any) -> tuple[list[dict[str, Any]], set[str]]:
    if not isinstance(categories, list) or not categories:
        raise QuestionnaireValidationError("categories must be a non-empty array")
    ids: set[str] = set()
    orders: set[int] = set()
    result: list[dict[str, Any]] = []
    for index, raw in enumerate(categories):
        label = f"categories[{index}]"
        category = _expect_object(raw, label)
        _expect_exact_fields(category, CATEGORY_FIELDS, label)
        identifier = _expect_id(category["id"], f"{label}.id")
        if identifier in ids:
            raise QuestionnaireValidationError(f"duplicate category id: {identifier}")
        ids.add(identifier)
        for field in ("title", "description"):
            _expect_string(category[field], f"{label}.{field}")
        order = category["order"]
        if isinstance(order, bool) or not isinstance(order, int) or order < 0:
            raise QuestionnaireValidationError(f"{label}.order must be a non-negative integer")
        if order in orders:
            raise QuestionnaireValidationError(f"duplicate category order: {order}")
        orders.add(order)
        result.append(category)
    return result, ids


def _validate_options(options: Any, label: str) -> tuple[list[dict[str, Any]], set[str]]:
    if not isinstance(options, list):
        raise QuestionnaireValidationError(f"{label} must be an array")
    ids: set[str] = set()
    result: list[dict[str, Any]] = []
    for index, raw in enumerate(options):
        option_label = f"{label}[{index}]"
        option = _expect_object(raw, option_label)
        _expect_exact_fields(option, OPTION_FIELDS, option_label)
        identifier = _expect_option_id(option["id"], f"{option_label}.id")
        if identifier in ids:
            raise QuestionnaireValidationError(f"{label} has duplicate option id: {identifier}")
        ids.add(identifier)
        for field in ("label", "description", "tradeoffs"):
            _expect_string(option[field], f"{option_label}.{field}")
        result.append(option)
    return result, ids


def _validate_evidence(evidence: Any, label: str) -> None:
    if not isinstance(evidence, list):
        raise QuestionnaireValidationError(f"{label} must be an array")
    for index, raw in enumerate(evidence):
        item_label = f"{label}[{index}]"
        item = _expect_object(raw, item_label)
        _expect_exact_fields(item, EVIDENCE_FIELDS, item_label)
        _expect_string(item["path"], f"{item_label}.path")
        _expect_string(item["note"], f"{item_label}.note")
        if (
            not isinstance(item["classification"], str)
            or item["classification"] not in EVIDENCE_CLASSIFICATIONS
        ):
            raise QuestionnaireValidationError(
                f"{item_label}.classification must be one of {sorted(EVIDENCE_CLASSIFICATIONS)}"
            )


def _validate_condition_value(value: Any, label: str) -> None:
    if value is None or isinstance(value, (list, dict)):
        raise QuestionnaireValidationError(f"{label} must be a non-null scalar JSON value")
    if isinstance(value, float) and not math.isfinite(value):
        raise QuestionnaireValidationError(f"{label} must be finite")


def _validate_local_condition(condition: Any, label: str) -> None:
    item = _expect_object(condition, label)
    operator = item.get("operator")
    if not isinstance(operator, str) or operator not in CONFLICT_OPERATORS:
        raise QuestionnaireValidationError(
            f"{label}.operator must be one of {sorted(CONFLICT_OPERATORS)}"
        )
    needs_value = operator not in {"answered", "unanswered"}
    _expect_exact_fields(
        item,
        {"operator"} | ({"value"} if needs_value else set()),
        label,
    )
    if needs_value:
        _validate_condition_value(item["value"], f"{label}.value")


def _validate_conflict_rules(rules: Any, label: str) -> tuple[list[dict[str, Any]], set[str]]:
    if not isinstance(rules, list):
        raise QuestionnaireValidationError(f"{label} must be an array")
    result: list[dict[str, Any]] = []
    rule_ids: set[str] = set()
    for index, raw in enumerate(rules):
        rule_label = f"{label}[{index}]"
        rule = _expect_object(raw, rule_label)
        _expect_exact_fields(rule, CONFLICT_FIELDS, rule_label, optional={"if"})
        rule_id = _expect_id(rule["id"], f"{rule_label}.id")
        if rule_id in rule_ids:
            raise QuestionnaireValidationError(f"{label} has duplicate rule id: {rule_id}")
        rule_ids.add(rule_id)
        when = _expect_object(rule["when"], f"{rule_label}.when")
        operator = when.get("operator")
        if not isinstance(operator, str) or operator not in CONFLICT_OPERATORS:
            raise QuestionnaireValidationError(
                f"{rule_label}.when.operator must be one of {sorted(CONFLICT_OPERATORS)}"
            )
        needs_value = operator not in {"answered", "unanswered"}
        _expect_exact_fields(
            when,
            WHEN_REQUIRED_FIELDS | ({"value"} if needs_value else set()),
            f"{rule_label}.when",
        )
        _expect_id(when["question_id"], f"{rule_label}.when.question_id")
        if needs_value:
            _validate_condition_value(when["value"], f"{rule_label}.when.value")
        if "if" in rule:
            _validate_local_condition(rule["if"], f"{rule_label}.if")
        _expect_string(rule["message"], f"{rule_label}.message")
        if not isinstance(rule["severity"], str) or rule["severity"] not in CONFLICT_SEVERITIES:
            raise QuestionnaireValidationError(
                f"{rule_label}.severity must be one of {sorted(CONFLICT_SEVERITIES)}"
            )
        result.append(rule)
    return result, rule_ids


def _validate_dependency_cycles(graph: dict[str, list[str]]) -> None:
    visited: set[str] = set()
    active: list[str] = []
    active_set: set[str] = set()

    def visit(question_id: str) -> None:
        visited.add(question_id)
        active.append(question_id)
        active_set.add(question_id)
        for dependency in graph.get(question_id, []):
            if dependency not in graph:
                continue
            if dependency not in visited:
                visit(dependency)
            elif dependency in active_set:
                start = active.index(dependency)
                cycle = active[start:] + [dependency]
                raise QuestionnaireValidationError(
                    f"question dependency cycle: {' -> '.join(cycle)}"
                )
        active.pop()
        active_set.remove(question_id)

    for question_id in graph:
        if question_id not in visited:
            visit(question_id)


def validate_questionnaire(payload: dict[str, Any]) -> None:
    """Validate the complete canonical question-set schema and cross-references."""

    root = _expect_object(payload, "questionnaire root")
    _expect_exact_fields(root, TOP_LEVEL_FIELDS, "questionnaire root")
    metadata = _validate_metadata(root["metadata"])
    _, category_ids = _validate_categories(root["categories"])
    questions = root["questions"]
    if not isinstance(questions, list) or not questions:
        raise QuestionnaireValidationError("questions must be a non-empty array")
    if (
        "expected_question_count" in metadata
        and metadata["expected_question_count"] != len(questions)
    ):
        raise QuestionnaireValidationError(
            "metadata.expected_question_count does not match questions length"
        )

    question_ids: set[str] = set()
    question_contracts: dict[str, tuple[str, set[str]]] = {}
    rule_ids: set[str] = set()
    dependency_references: list[tuple[str, str]] = []
    conflict_references: list[tuple[str, str, str, dict[str, Any], dict[str, Any] | None]] = []
    for index, raw in enumerate(questions):
        label = f"questions[{index}]"
        question = _expect_object(raw, label)
        answer_type = question.get("answer_type")
        optional_fields = {"validation"} if answer_type == "number" else set()
        _expect_exact_fields(question, QUESTION_FIELDS, label, optional=optional_fields)
        identifier = _expect_id(question["id"], f"{label}.id")
        if identifier in question_ids:
            raise QuestionnaireValidationError(f"duplicate question id: {identifier}")
        question_ids.add(identifier)
        category_id = _expect_id(question["category_id"], f"{label}.category_id")
        if category_id not in category_ids:
            raise QuestionnaireValidationError(
                f"{label}.category_id references unknown category: {category_id}"
            )
        for field in (
            "title",
            "prompt",
            "why_it_matters",
            "current_context",
            "recommendation_reason",
            "followup_guidance",
        ):
            _expect_string(question[field], f"{label}.{field}")
        if not isinstance(question["required"], bool):
            raise QuestionnaireValidationError(f"{label}.required must be a boolean")
        if (
            not isinstance(question["decision_level"], str)
            or question["decision_level"] not in DECISION_LEVELS
        ):
            raise QuestionnaireValidationError(
                f"{label}.decision_level must be one of {sorted(DECISION_LEVELS)}"
            )
        if not isinstance(answer_type, str) or answer_type not in ANSWER_TYPES:
            raise QuestionnaireValidationError(
                f"{label}.answer_type must be one of {sorted(ANSWER_TYPES)}"
            )

        options, option_ids = _validate_options(question["options"], f"{label}.options")
        recommendations = _expect_string_list(
            question["recommended_option_ids"], f"{label}.recommended_option_ids"
        )
        unknown_recommendations = sorted(set(recommendations) - option_ids)
        if unknown_recommendations:
            raise QuestionnaireValidationError(
                f"{label}.recommended_option_ids references unknown options: {unknown_recommendations}"
            )
        if answer_type in {"single_choice", "multi_choice"}:
            if len(options) < 2:
                raise QuestionnaireValidationError(f"{label}.options must contain at least two choices")
            if "custom" not in option_ids:
                raise QuestionnaireValidationError(
                    f"{label}.options must include the 'custom' direct-definition choice"
                )
            if not recommendations:
                raise QuestionnaireValidationError(
                    f"{label}.recommended_option_ids must contain at least one recommendation"
                )
            if answer_type == "single_choice" and len(recommendations) != 1:
                raise QuestionnaireValidationError(
                    f"{label}.recommended_option_ids must contain exactly one choice for single_choice"
                )
        elif options or recommendations:
            raise QuestionnaireValidationError(
                f"{label}.options and recommended_option_ids must be empty for {answer_type}"
            )
        question_contracts[identifier] = (answer_type, option_ids)

        if answer_type == "number":
            validation = _expect_object(question["validation"], f"{label}.validation")
            _expect_exact_fields(validation, VALIDATION_FIELDS, f"{label}.validation")
            minimum = _expect_finite_number(validation["min"], f"{label}.validation.min")
            maximum = _expect_finite_number(validation["max"], f"{label}.validation.max")
            if minimum > maximum:
                raise QuestionnaireValidationError(
                    f"{label}.validation.min must be less than or equal to max"
                )
            _expect_string(validation["unit"], f"{label}.validation.unit")

        _expect_string_list(question["impacts"], f"{label}.impacts")
        _expect_string_list(
            question["affected_deliverable_types"], f"{label}.affected_deliverable_types"
        )
        _validate_evidence(question["evidence"], f"{label}.evidence")
        dependencies = _expect_string_list(question["dependencies"], f"{label}.dependencies")
        for dependency in dependencies:
            _expect_id(dependency, f"{label}.dependencies")
            if dependency == identifier:
                raise QuestionnaireValidationError(f"{label} must not depend on itself")
            dependency_references.append((identifier, dependency))
        rules, local_rule_ids = _validate_conflict_rules(
            question["conflict_rules"], f"{label}.conflict_rules"
        )
        duplicates = sorted(rule_ids & local_rule_ids)
        if duplicates:
            raise QuestionnaireValidationError(f"duplicate conflict rule ids: {duplicates}")
        rule_ids.update(local_rule_ids)
        for rule in rules:
            target_question_id = rule["when"]["question_id"]
            if target_question_id == identifier:
                raise QuestionnaireValidationError(
                    f"conflict rule {rule['id']} must not reference its owner question"
                )
            conflict_references.append(
                (identifier, rule["id"], target_question_id, rule["when"], rule.get("if"))
            )

    for question_id, dependency in dependency_references:
        if dependency not in question_ids:
            raise QuestionnaireValidationError(
                f"question {question_id} depends on unknown question: {dependency}"
            )
    dependency_graph = {question_id: [] for question_id in question_ids}
    for question_id, dependency in dependency_references:
        dependency_graph[question_id].append(dependency)
    _validate_dependency_cycles(dependency_graph)
    for owner_id, rule_id, question_id, when, local_if in conflict_references:
        if question_id not in question_ids:
            raise QuestionnaireValidationError(
                f"conflict rule {rule_id} references unknown question: {question_id}"
            )
        if local_if is not None and local_if["operator"] not in {"answered", "unanswered"}:
            owner_type, owner_options = question_contracts[owner_id]
            if owner_type not in {"single_choice", "multi_choice"}:
                raise QuestionnaireValidationError(
                    f"conflict rule {rule_id} if.value requires a choice owner question"
                )
            if not isinstance(local_if["value"], str) or local_if["value"] not in owner_options:
                raise QuestionnaireValidationError(
                    f"conflict rule {rule_id} if.value references an unknown owner option"
                )
        if when["operator"] not in {"answered", "unanswered"}:
            target_type, target_options = question_contracts[question_id]
            if target_type not in {"single_choice", "multi_choice"}:
                raise QuestionnaireValidationError(
                    f"conflict rule {rule_id} when.value requires a choice target question"
                )
            if not isinstance(when["value"], str) or when["value"] not in target_options:
                raise QuestionnaireValidationError(
                    f"conflict rule {rule_id} when.value references an unknown target option"
                )


def canonical_questionnaire_json(payload: dict[str, Any]) -> str:
    """Return the normalized JSON used for stable hashing and HTML embedding."""

    validate_questionnaire(payload)
    return json.dumps(
        payload,
        ensure_ascii=False,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def questionnaire_sha256(payload: dict[str, Any]) -> str:
    return hashlib.sha256(canonical_questionnaire_json(payload).encode("utf-8")).hexdigest()


def _script_safe_json(value: Any) -> str:
    rendered = json.dumps(
        value,
        ensure_ascii=False,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return (
        rendered.replace("&", "\\u0026")
        .replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")
    )


def render_questionnaire(payload: dict[str, Any], template: str) -> str:
    """Render a deterministic, standalone HTML document from template plus JSON."""

    validate_questionnaire(payload)
    placeholder_count = template.count(DATA_PLACEHOLDER)
    if placeholder_count != 1:
        raise QuestionnaireValidationError(
            f"template must contain {DATA_PLACEHOLDER!r} exactly once; found {placeholder_count}"
        )
    envelope = {
        "question_set_hash": questionnaire_sha256(payload),
        "questionnaire": payload,
    }
    rendered = template.replace(DATA_PLACEHOLDER, _script_safe_json(envelope))
    if not rendered.endswith("\n"):
        rendered += "\n"
    return rendered


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Build the standalone WalkSafe project-decision questionnaire"
    )
    parser.add_argument("--questions", type=Path, default=DEFAULT_QUESTIONS_PATH)
    parser.add_argument("--template", type=Path, default=DEFAULT_TEMPLATE_PATH)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT_PATH)
    parser.add_argument(
        "--check",
        action="store_true",
        help="validate inputs and fail unless output already matches the deterministic render",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        payload = load_questionnaire(args.questions)
        template = args.template.read_text(encoding="utf-8")
        rendered = render_questionnaire(payload, template)
    except (OSError, UnicodeError, QuestionnaireValidationError) as exc:
        print(f"questionnaire build failed: {exc}", file=sys.stderr)
        return 2

    if args.check:
        try:
            current = args.output.read_text(encoding="utf-8")
        except (OSError, UnicodeError):
            current = None
        if current != rendered:
            print(
                f"questionnaire output is stale: {args.output}; "
                "run python scripts/build_walksafe_project_questionnaire.py",
                file=sys.stderr,
            )
            return 1
        print(
            f"Questionnaire is current: {args.output} "
            f"(sha256={questionnaire_sha256(payload)})"
        )
        return 0

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(rendered, encoding="utf-8", newline="\n")
    print(
        f"Wrote {args.output} with {len(payload['questions'])} questions "
        f"(question_set_sha256={questionnaire_sha256(payload)})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

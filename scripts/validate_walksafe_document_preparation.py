#!/usr/bin/env python3
"""Validate the WalkSafe document plan and decision-question sources."""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Any


EXPECTED_CATEGORY_COUNTS = {
    "DOC": 5,
    "MGT": 18,
    "DSC": 15,
    "REQ": 19,
    "DES": 27,
    "DEV": 21,
    "TST": 23,
    "SEC": 19,
    "AIML": 26,
    "REL": 22,
    "OPS": 24,
    "WS": 22,
    "CLS": 16,
}

EXPECTED_FORM_COUNTS = {
    "CANONICAL_DOCUMENT": 120,
    "CONTROLLED_ARTIFACT": 14,
    "SECTION": 30,
    "REGISTER": 42,
    "GENERATED_EVIDENCE": 42,
    "EXTERNAL_RECORD": 9,
}

EXPECTED_QUESTION_COUNT = 286
EXPECTED_GAP_TRACE_COUNT = 60

EXPECTED_QUESTION_CATEGORY_IDS = {
    "CAT-GOV",
    "CAT-USR",
    "CAT-PLT",
    "CAT-SES",
    "CAT-DET",
    "CAT-NAV",
    "CAT-VOI",
    "CAT-RPT",
    "CAT-AIML",
    "CAT-ARC",
    "CAT-PRV",
    "CAT-RES",
    "CAT-ACC",
    "CAT-TST",
    "CAT-REL",
    "CAT-OPS",
}

ARTIFACT_FORMS = {
    "SECTION",
    "REGISTER",
    "CANONICAL_DOCUMENT",
    "CONTROLLED_ARTIFACT",
    "GENERATED_EVIDENCE",
    "EXTERNAL_RECORD",
}

EXPECTED_PRIMARY_FORMS = {
    "MGT-17": "GENERATED_EVIDENCE",
    "DSC-04": "CANONICAL_DOCUMENT",
    "DSC-07": "CANONICAL_DOCUMENT",
    "DSC-15": "REGISTER",
    "DES-10": "CONTROLLED_ARTIFACT",
    "DEV-15": "REGISTER",
    "SEC-18": "CANONICAL_DOCUMENT",
    "REL-05": "CONTROLLED_ARTIFACT",
    "REL-15": "CANONICAL_DOCUMENT",
    "REL-22": "CANONICAL_DOCUMENT",
    "OPS-06": "CONTROLLED_ARTIFACT",
    "OPS-07": "CONTROLLED_ARTIFACT",
    "OPS-09": "REGISTER",
    "OPS-11": "CANONICAL_DOCUMENT",
    "OPS-13": "CANONICAL_DOCUMENT",
    "OPS-14": "REGISTER",
}

EXPECTED_SUPPORTING_FORMS = {
    "DSC-04": {"EXTERNAL_RECORD"},
    "DEV-15": {"EXTERNAL_RECORD"},
    "REL-02": {"EXTERNAL_RECORD"},
    "REL-15": {"GENERATED_EVIDENCE"},
    "REL-20": {"CANONICAL_DOCUMENT"},
    "OPS-06": {"GENERATED_EVIDENCE"},
    "OPS-11": {"GENERATED_EVIDENCE"},
    "OPS-13": {"GENERATED_EVIDENCE", "EXTERNAL_RECORD"},
    "WS-21": {"CANONICAL_DOCUMENT"},
}

CRITICAL_APPROVERS = {
    "TST-10": "제품책임자·사용자대표",
    "TST-20": "제품책임자",
    "TST-22": "제품책임자",
    "TST-23": "지정 인수자",
    "REL-21": "지정 검수자",
    "CLS-02": "지정 인수자",
}

CRITICAL_OWNERS = {
    "DSC-03": "사용자연구책임자",
    "DSC-04": "사용자연구책임자",
    "DSC-05": "사용자연구책임자",
}

SPECIALIST_REVIEWERS = {
    "MGT-08": {"재무·자원승인권자"},
    "MGT-12": {"QA책임자"},
    "REQ-09": {"보안·개인정보책임자"},
    "REQ-10": {"보안·개인정보책임자"},
    "REQ-13": {"운영책임자"},
    "DES-23": {"운영책임자"},
    "DES-25": {"운영책임자"},
    "DES-27": {"운영책임자"},
    "AIML-21": {"기술책임자"},
    "AIML-22": {"기술책임자"},
    "AIML-24": {"릴리스책임자", "운영책임자"},
    "AIML-25": {"운영책임자"},
    "CLS-14": {"보안·개인정보책임자"},
    "CLS-15": {"보안·개인정보책임자"},
    "CLS-16": {"보안·개인정보책임자"},
}

REQUIRED_ARTIFACT_UPSTREAM = {
    "DEV-01": {"SEC-01", "SEC-04"},
    "TST-05": {"WS-21"},
    "TST-17": {"REL-15"},
    "TST-18": {"TST-02"},
    "TST-20": {"TST-21", "WS-20"},
    "TST-22": {"REL-01", "REL-02", "SEC-14", "TST-20", "TST-21"},
    "SEC-14": {"REL-04", "REL-06"},
    "SEC-15": {"SEC-03"},
    "AIML-15": {"AIML-18", "AIML-20", "AIML-23"},
    "AIML-17": {"AIML-09", "AIML-16"},
    "REL-04": {"REL-05", "REL-06", "REL-07", "REL-08"},
    "REL-06": {"REL-05"},
    "REL-07": {"REL-06"},
    "REL-08": {"REL-06"},
    "REL-13": {"TST-22"},
    "REL-14": {"REL-15", "TST-22"},
    "REL-17": {"WS-22"},
    "REL-20": {"OPS-01", "OPS-02"},
    "WS-06": {"WS-21"},
    "WS-07": {"WS-21"},
    "CLS-01": {"CLS-04", "CLS-05", "CLS-07", "CLS-08", "CLS-09", "CLS-10"},
    "CLS-14": {"CLS-16"},
    "CLS-15": {"CLS-16"},
}

LEGAL_REVIEW_TYPES = {
    "REQ-15",
    "DEV-17",
    "DEV-19",
    "SEC-05",
    "SEC-06",
    "AIML-03",
    "REL-22",
    "WS-21",
    "WS-22",
    "CLS-13",
    "CLS-16",
}

ACCESSIBILITY_REVIEW_TYPES = {"REQ-11", "DES-18", "DES-27"}

ARTIFACT_REQUIRED_FIELDS = {
    "type_code",
    "display_code",
    "title",
    "category",
    "default_applicability",
    "activation_condition",
    "purpose",
    "recommended_form",
    "required_contents",
    "required_inputs",
    "owner_role",
    "reviewer_roles",
    "approver_role",
    "upstream_types",
    "downstream_types",
    "update_triggers",
    "completion_criteria",
    "current_priority",
    "existing_candidate_paths",
    "sequence_hint",
    "recommended_bundle_id",
    "supporting_forms",
}

QUESTION_REQUIRED_FIELDS = {
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


def load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        raise ValueError(f"missing file: {path}") from None
    except json.JSONDecodeError as exc:
        raise ValueError(f"invalid JSON {path}: {exc}") from exc


def nonempty_string(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def validate_string_list(value: Any, label: str, errors: list[str]) -> list[str]:
    if not isinstance(value, list):
        errors.append(f"{label} must be an array")
        return []
    valid: list[str] = []
    for index, item in enumerate(value):
        if not nonempty_string(item):
            errors.append(f"{label}[{index}] must be a non-empty string")
        else:
            valid.append(item)
    if len(valid) != len(set(valid)):
        errors.append(f"{label} must not contain duplicates")
    return valid


def path_without_location(value: str) -> str:
    value = value.split("#", 1)[0]
    return re.sub(r":\d+(?::\d+)?$", "", value)


def resolve_internal_path(repo: Path, value: str) -> Path | None:
    relative = Path(path_without_location(value))
    if relative.is_absolute():
        return None
    candidate = (repo / relative).resolve()
    try:
        candidate.relative_to(repo)
    except ValueError:
        return None
    return candidate


def validate_artifacts(repo: Path, data: Any) -> tuple[list[str], set[str]]:
    errors: list[str] = []
    if not isinstance(data, dict):
        return ["artifact catalog must be an object"], set()
    metadata = data.get("metadata")
    categories = data.get("categories")
    artifacts = data.get("artifact_types")
    if not isinstance(metadata, dict):
        errors.append("artifact metadata must be an object")
    elif metadata.get("expected_count") != 257:
        errors.append("artifact metadata.expected_count must be 257")
    bundle_ids = (
        validate_string_list(
            metadata.get("bundle_ids"), "artifact metadata.bundle_ids", errors
        )
        if isinstance(metadata, dict)
        else []
    )
    if len(bundle_ids) != 40:
        errors.append(f"artifact metadata.bundle_ids must contain 40 IDs, got {len(bundle_ids)}")
    if bundle_ids != sorted(bundle_ids):
        errors.append("artifact metadata.bundle_ids must be stored in sorted order")
    for bundle_id in bundle_ids:
        if re.fullmatch(r"BND-[A-Z0-9-]+", bundle_id) is None:
            errors.append(f"artifact metadata has invalid bundle ID: {bundle_id}")
    if not isinstance(categories, list):
        errors.append("artifact categories must be an array")
        categories = []
    if not isinstance(artifacts, list):
        errors.append("artifact_types must be an array")
        return errors, set()
    if len(artifacts) != 257:
        errors.append(f"artifact_types count must be 257, got {len(artifacts)}")

    expected_codes = {
        f"{category}-{index:02d}"
        for category, count in EXPECTED_CATEGORY_COUNTS.items()
        for index in range(1, count + 1)
    }
    display_codes = [item.get("display_code") for item in artifacts if isinstance(item, dict)]
    type_codes = [item.get("type_code") for item in artifacts if isinstance(item, dict)]
    duplicate_display = [code for code, count in Counter(display_codes).items() if count > 1]
    duplicate_types = [code for code, count in Counter(type_codes).items() if count > 1]
    if duplicate_display:
        errors.append(f"duplicate display codes: {sorted(duplicate_display)}")
    if duplicate_types:
        errors.append(f"duplicate type codes: {sorted(duplicate_types)}")
    actual_display = {code for code in display_codes if isinstance(code, str)}
    actual_type_codes = {code for code in type_codes if isinstance(code, str)}
    missing = sorted(expected_codes - actual_display)
    extra = sorted(actual_display - expected_codes)
    if missing:
        errors.append(f"missing display codes: {missing}")
    if extra:
        errors.append(f"unexpected display codes: {extra}")

    counts: Counter[str] = Counter()
    form_counts: Counter[str] = Counter()
    for index, item in enumerate(artifacts):
        label = f"artifact_types[{index}]"
        if not isinstance(item, dict):
            errors.append(f"{label} must be an object")
            continue
        missing_fields = sorted(ARTIFACT_REQUIRED_FIELDS - item.keys())
        if missing_fields:
            errors.append(f"{label} missing fields: {missing_fields}")
            continue
        display_code = item["display_code"]
        if item["type_code"] != f"DLV-{display_code}":
            errors.append(f"{label}.type_code must be DLV-{display_code}")
        category = item["category"]
        counts[category] += 1
        if display_code.split("-", 1)[0] != category:
            errors.append(f"{label} category/display_code mismatch")
        for field in (
            "title",
            "activation_condition",
            "purpose",
            "owner_role",
            "approver_role",
        ):
            if not nonempty_string(item[field]):
                errors.append(f"{label}.{field} must be a non-empty string")
        if item["default_applicability"] not in {"REQUIRED", "CONDITIONAL"}:
            errors.append(f"{label}.default_applicability is invalid")
        if item["recommended_form"] not in ARTIFACT_FORMS:
            errors.append(f"{label}.recommended_form is invalid")
        else:
            form_counts[item["recommended_form"]] += 1
        expected_primary_form = EXPECTED_PRIMARY_FORMS.get(display_code)
        if expected_primary_form and item["recommended_form"] != expected_primary_form:
            errors.append(
                f"{label}.recommended_form must be {expected_primary_form} for {display_code}"
            )
        sequence_hint = item["sequence_hint"]
        if (
            isinstance(sequence_hint, bool)
            or not isinstance(sequence_hint, int)
            or sequence_hint < 1
            or sequence_hint > 257
        ):
            errors.append(f"{label}.sequence_hint must be an integer from 1 to 257")
        if not nonempty_string(item["recommended_bundle_id"]) or re.fullmatch(
            r"BND-[A-Z0-9-]+", str(item["recommended_bundle_id"])
        ) is None:
            errors.append(f"{label}.recommended_bundle_id has an invalid format")
        supporting_forms = validate_string_list(
            item["supporting_forms"], f"{label}.supporting_forms", errors
        )
        for supporting_form in supporting_forms:
            if supporting_form not in ARTIFACT_FORMS:
                errors.append(
                    f"{label}.supporting_forms has an invalid form: {supporting_form}"
                )
            if supporting_form == item["recommended_form"]:
                errors.append(
                    f"{label}.supporting_forms must not repeat recommended_form"
                )
        expected_supporting = EXPECTED_SUPPORTING_FORMS.get(display_code, set())
        if set(supporting_forms) != expected_supporting:
            errors.append(
                f"{label}.supporting_forms mismatch: expected {sorted(expected_supporting)}, "
                f"got {sorted(supporting_forms)}"
            )
        if item["current_priority"] not in {"P0", "P1", "P2", "CONDITIONAL"}:
            errors.append(f"{label}.current_priority is invalid")
        expected_approver = CRITICAL_APPROVERS.get(display_code)
        if expected_approver and item["approver_role"] != expected_approver:
            errors.append(
                f"{label}.approver_role must be {expected_approver} for independent acceptance"
            )
        if display_code in CRITICAL_APPROVERS and item["owner_role"] == item["approver_role"]:
            errors.append(f"{label} must separate owner and approver roles")
        expected_owner = CRITICAL_OWNERS.get(display_code)
        if expected_owner and item["owner_role"] != expected_owner:
            errors.append(
                f"{label}.owner_role must be {expected_owner} for {display_code}"
            )
        reviewers = set(item.get("reviewer_roles", []))
        if item["owner_role"] == item["approver_role"]:
            errors.append(f"{label} must separate owner and approver roles")
        if item["owner_role"] in reviewers:
            errors.append(f"{label} reviewer_roles must not contain the owner role")
        if display_code in LEGAL_REVIEW_TYPES and "법무·라이선스검토자" not in reviewers:
            errors.append(f"{label} requires a legal/license reviewer")
        if (
            display_code in ACCESSIBILITY_REVIEW_TYPES or item["category"] == "WS"
        ) and "접근성·안전책임자" not in reviewers:
            errors.append(f"{label} requires an accessibility/safety reviewer")
        missing_specialists = SPECIALIST_REVIEWERS.get(display_code, set()) - reviewers
        if missing_specialists:
            errors.append(
                f"{label} missing specialist reviewers: {sorted(missing_specialists)}"
            )
        for field in (
            "required_contents",
            "required_inputs",
            "reviewer_roles",
            "upstream_types",
            "downstream_types",
            "update_triggers",
            "completion_criteria",
            "existing_candidate_paths",
        ):
            if not isinstance(item[field], list):
                errors.append(f"{label}.{field} must be an array")
        for field in (
            "required_contents",
            "required_inputs",
            "reviewer_roles",
            "update_triggers",
            "completion_criteria",
        ):
            values = validate_string_list(item.get(field), f"{label}.{field}", errors)
            if not values:
                errors.append(f"{label}.{field} must not be empty")
        for field in ("upstream_types", "downstream_types"):
            references = validate_string_list(item.get(field), f"{label}.{field}", errors)
            for reference in references:
                if reference not in actual_type_codes:
                    errors.append(f"{label}.{field} has unknown artifact type: {reference}")
                if reference == item["type_code"]:
                    errors.append(f"{label}.{field} must not reference itself")
        for candidate in item.get("existing_candidate_paths", []):
            if not isinstance(candidate, str) or not candidate.strip():
                errors.append(f"{label} has an invalid candidate path")
                continue
            candidate_path = resolve_internal_path(repo, candidate)
            if candidate_path is None:
                errors.append(f"{label} candidate path escapes the repository: {candidate}")
            elif not candidate_path.exists():
                errors.append(f"{label} candidate path does not exist: {candidate}")

    if dict(counts) != EXPECTED_CATEGORY_COUNTS:
        errors.append(
            f"artifact category counts mismatch: expected {EXPECTED_CATEGORY_COUNTS}, got {dict(counts)}"
        )
    if dict(form_counts) != EXPECTED_FORM_COUNTS:
        errors.append(
            f"artifact form counts mismatch: expected {EXPECTED_FORM_COUNTS}, "
            f"got {dict(form_counts)}"
        )
    category_rows = {row.get("code"): row for row in categories if isinstance(row, dict)}
    if set(category_rows) != set(EXPECTED_CATEGORY_COUNTS):
        errors.append("artifact category metadata does not cover the exact category set")
    for code, expected_count in EXPECTED_CATEGORY_COUNTS.items():
        row = category_rows.get(code, {})
        if row.get("expected_count") != expected_count:
            errors.append(f"category {code} expected_count must be {expected_count}")
    category_orders = [
        row.get("order") for row in categories if isinstance(row, dict)
    ]
    if category_orders != list(range(len(EXPECTED_CATEGORY_COUNTS))):
        errors.append("artifact categories must be stored in contiguous order 0..12")
    sequence_hints = [
        item.get("sequence_hint") for item in artifacts if isinstance(item, dict)
    ]
    if sequence_hints != list(range(1, 258)):
        errors.append(
            "artifact sequence_hint values must be unique and match catalog order 1..257"
        )
    used_bundle_ids = {
        item.get("recommended_bundle_id")
        for item in artifacts
        if isinstance(item, dict)
        and isinstance(item.get("recommended_bundle_id"), str)
    }
    if used_bundle_ids != set(bundle_ids):
        errors.append(
            "artifact bundle coverage mismatch: "
            f"unused={sorted(set(bundle_ids) - used_bundle_ids)}, "
            f"unregistered={sorted(used_bundle_ids - set(bundle_ids))}"
        )
    upstream_graph = {
        item["type_code"]: list(item.get("upstream_types", []))
        for item in artifacts
        if isinstance(item, dict) and isinstance(item.get("type_code"), str)
    }
    downstream_graph = {
        item["type_code"]: list(item.get("downstream_types", []))
        for item in artifacts
        if isinstance(item, dict) and isinstance(item.get("type_code"), str)
    }
    validate_graph_cycles(upstream_graph, "artifact upstream dependency", errors)
    validate_graph_cycles(downstream_graph, "artifact downstream impact", errors)
    for type_code, upstream_types in upstream_graph.items():
        for upstream_type in upstream_types:
            if type_code not in downstream_graph.get(upstream_type, []):
                errors.append(
                    f"artifact dependency is not inverse-symmetric: "
                    f"{type_code} upstream {upstream_type}"
                )
    for type_code, downstream_types in downstream_graph.items():
        for downstream_type in downstream_types:
            if type_code not in upstream_graph.get(downstream_type, []):
                errors.append(
                    f"artifact dependency is not inverse-symmetric: "
                    f"{type_code} downstream {downstream_type}"
                )
    independent_pairs = {
        ("DLV-SEC-10", "DLV-SEC-11"),
        ("DLV-WS-10", "DLV-WS-11"),
        ("DLV-MGT-08", "DLV-MGT-09"),
    }
    for left, right in independent_pairs:
        if right in upstream_graph.get(left, []) or left in upstream_graph.get(right, []):
            errors.append(f"independent artifact pair must not be linked: {left}, {right}")
    for dependent, required_upstream in REQUIRED_ARTIFACT_UPSTREAM.items():
        actual_upstream = {
            value.removeprefix("DLV-")
            for value in upstream_graph.get(f"DLV-{dependent}", [])
        }
        missing_upstream = required_upstream - actual_upstream
        if missing_upstream:
            errors.append(
                f"artifact semantic gate missing for {dependent}: "
                f"required upstream {sorted(missing_upstream)}"
            )
    # 질문 데이터는 사람이 문서에서 사용하는 표시 ID(DOC-01, MGT-01, ... )로
    # 추적한다. 내부 유형 ID(DLV-DOC-01, ... )는 산출물 instance ID와 구분하기
    # 위한 catalog 전용 값이다.
    return errors, actual_display


def validate_condition_option(
    question: dict[str, Any],
    condition: dict[str, Any],
    label: str,
    errors: list[str],
) -> None:
    """Validate a conflict value against the question whose answer it examines."""

    operator = condition.get("operator")
    if operator in {"answered", "unanswered"}:
        return
    value = condition.get("value")
    answer_type = question.get("answer_type")
    if answer_type in {"single_choice", "multi_choice"}:
        option_ids = {
            option.get("id")
            for option in question.get("options", [])
            if isinstance(option, dict)
        }
        if value not in option_ids:
            errors.append(f"{label}.value references an unknown option: {value}")
    elif answer_type == "number" and (
        isinstance(value, bool) or not isinstance(value, (int, float))
    ):
        errors.append(f"{label}.value must be a number for a number question")
    elif answer_type == "text" and not isinstance(value, str):
        errors.append(f"{label}.value must be a string for a text question")


def validate_graph_cycles(
    graph: dict[str, list[str]], label: str, errors: list[str]
) -> None:
    visited: set[str] = set()
    active: list[str] = []
    active_set: set[str] = set()

    def visit(node: str) -> None:
        visited.add(node)
        active.append(node)
        active_set.add(node)
        for dependency in graph.get(node, []):
            if dependency not in graph:
                continue
            if dependency not in visited:
                visit(dependency)
            elif dependency in active_set:
                start = active.index(dependency)
                cycle = active[start:] + [dependency]
                errors.append(f"{label} cycle: {' -> '.join(cycle)}")
        active.pop()
        active_set.remove(node)

    for node in graph:
        if node not in visited:
            visit(node)


def validate_source_audit(path: Path, question_ids: set[str]) -> list[str]:
    errors: list[str] = []
    try:
        content = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        return [f"missing source conflict audit: {path}"]
    marker = "## 8. 감사 항목→canonical 질문 추적 매트릭스"
    if marker not in content:
        return ["source conflict audit is missing section 8 trace matrix"]
    trace_section = content.split(marker, 1)[1]
    rows: dict[str, set[str]] = {}
    for line in trace_section.splitlines():
        match = re.match(r"^\| (A-\d{3}) \| (.+?) \|", line)
        if not match:
            continue
        audit_id, mapping_cell = match.groups()
        if audit_id in rows:
            errors.append(f"duplicate source audit trace row: {audit_id}")
            continue
        rows[audit_id] = set(re.findall(r"`(Q-[A-Z]+-\d{3})`", mapping_cell))
    expected = {f"A-{index:03d}" for index in range(1, 51)}
    if set(rows) != expected:
        errors.append(
            "source audit trace IDs mismatch: "
            f"missing={sorted(expected - set(rows))}, extra={sorted(set(rows) - expected)}"
        )
    for audit_id, references in rows.items():
        if not references:
            errors.append(f"source audit trace {audit_id} has no question")
        unknown = sorted(references - question_ids)
        if unknown:
            errors.append(f"source audit trace {audit_id} has unknown questions: {unknown}")

    gap_marker = "## 9. 추가 공백→canonical 질문 추적 매트릭스"
    if gap_marker not in content:
        errors.append("source conflict audit is missing section 9 gap trace matrix")
        return errors
    gap_section = content.split(gap_marker, 1)[1]
    gap_rows: dict[str, set[str]] = {}
    for line in gap_section.splitlines():
        match = re.match(r"^\| (G-\d{3}) \| (.+?) \|", line)
        if not match:
            continue
        gap_id, mapping_cell = match.groups()
        if gap_id in gap_rows:
            errors.append(f"duplicate gap trace row: {gap_id}")
            continue
        gap_rows[gap_id] = set(re.findall(r"`(Q-[A-Z]+-\d{3})`", mapping_cell))
    expected_gap_ids = {
        f"G-{index:03d}" for index in range(1, EXPECTED_GAP_TRACE_COUNT + 1)
    }
    if set(gap_rows) != expected_gap_ids:
        errors.append(
            "gap trace IDs mismatch: "
            f"missing={sorted(expected_gap_ids - set(gap_rows))}, "
            f"extra={sorted(set(gap_rows) - expected_gap_ids)}"
        )
    for gap_id, references in gap_rows.items():
        if not references:
            errors.append(f"gap trace {gap_id} has no question")
        elif len(references) != 1:
            errors.append(f"gap trace {gap_id} must map to exactly one canonical question")
        unknown = sorted(references - question_ids)
        if unknown:
            errors.append(f"gap trace {gap_id} has unknown questions: {unknown}")
    traced_gap_questions = [
        question_id for references in gap_rows.values() for question_id in references
    ]
    duplicate_gap_questions = sorted(
        question_id
        for question_id, count in Counter(traced_gap_questions).items()
        if count > 1
    )
    if duplicate_gap_questions:
        errors.append(
            f"gap traces must be 1:1; duplicate questions: {duplicate_gap_questions}"
        )
    return errors


def expand_artifact_coverage(specification: str) -> list[str]:
    codes: list[str] = []
    for raw_part in re.split(r"\s*[,·]\s*", specification.strip()):
        part = raw_part.strip().strip("`")
        if not part:
            continue
        match = re.fullmatch(
            r"([A-Z]+)-(\d{2})(?:~(?:([A-Z]+)-)?(\d{2}))?",
            part,
        )
        if match is None:
            raise ValueError(f"invalid artifact coverage expression: {part}")
        start_category, start_number_text, end_category, end_number_text = match.groups()
        start_number = int(start_number_text)
        if end_number_text is None:
            codes.append(f"{start_category}-{start_number:02d}")
            continue
        if end_category is not None and end_category != start_category:
            raise ValueError(f"artifact coverage range changes category: {part}")
        end_number = int(end_number_text)
        if end_number < start_number:
            raise ValueError(f"artifact coverage range is reversed: {part}")
        codes.extend(
            f"{start_category}-{number:02d}"
            for number in range(start_number, end_number + 1)
        )
    return codes


def validate_authoring_plan(path: Path, artifact_data: Any) -> list[str]:
    errors: list[str] = []
    try:
        content = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        return [f"missing document authoring plan: {path}"]
    start_marker = "## 5. 권장 물리 문서 묶음"
    end_marker = "## 6. 범주별 작성 순서와 gate"
    if start_marker not in content or end_marker not in content:
        return ["document authoring plan is missing the bundle table section"]
    section = content.split(start_marker, 1)[1].split(end_marker, 1)[0]
    plan_bundle_ids: list[str] = []
    coverage_by_code: dict[str, list[str]] = {}
    for line in section.splitlines():
        if not line.startswith("|"):
            continue
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if not cells or not cells[0].startswith("BND-"):
            continue
        if len(cells) != 5:
            errors.append(f"bundle table row must have five columns: {line}")
            continue
        bundle_id, category, bundle_name, coverage_spec, authoring_method = cells
        if bundle_id in plan_bundle_ids:
            errors.append(f"duplicate bundle table ID: {bundle_id}")
        plan_bundle_ids.append(bundle_id)
        if not nonempty_string(bundle_name) or not nonempty_string(authoring_method):
            errors.append(f"bundle table row has empty content: {bundle_id}")
        try:
            coverage_codes = expand_artifact_coverage(coverage_spec)
        except ValueError as exc:
            errors.append(f"{bundle_id}: {exc}")
            continue
        if not coverage_codes:
            errors.append(f"bundle table row has no coverage: {bundle_id}")
        for display_code in coverage_codes:
            if display_code.split("-", 1)[0] != category:
                errors.append(
                    f"bundle {bundle_id} category does not match {display_code}"
                )
            coverage_by_code.setdefault(display_code, []).append(bundle_id)

    metadata = artifact_data.get("metadata", {}) if isinstance(artifact_data, dict) else {}
    artifact_types = (
        artifact_data.get("artifact_types", []) if isinstance(artifact_data, dict) else []
    )
    catalog_bundle_ids = metadata.get("bundle_ids", [])
    if len(plan_bundle_ids) != 40:
        errors.append(f"bundle table must contain 40 rows, got {len(plan_bundle_ids)}")
    if set(plan_bundle_ids) != set(catalog_bundle_ids):
        errors.append(
            "plan/catalog bundle ID mismatch: "
            f"plan_only={sorted(set(plan_bundle_ids) - set(catalog_bundle_ids))}, "
            f"catalog_only={sorted(set(catalog_bundle_ids) - set(plan_bundle_ids))}"
        )
    catalog_codes = {
        item.get("display_code")
        for item in artifact_types
        if isinstance(item, dict) and isinstance(item.get("display_code"), str)
    }
    plan_codes = set(coverage_by_code)
    if plan_codes != catalog_codes:
        errors.append(
            "bundle table artifact coverage mismatch: "
            f"missing={sorted(catalog_codes - plan_codes)}, "
            f"extra={sorted(plan_codes - catalog_codes)}"
        )
    duplicated_codes = sorted(
        display_code
        for display_code, bundle_ids in coverage_by_code.items()
        if len(bundle_ids) != 1
    )
    if duplicated_codes:
        errors.append(f"bundle table has duplicate artifact coverage: {duplicated_codes}")
    for item in artifact_types:
        if not isinstance(item, dict) or not isinstance(item.get("display_code"), str):
            continue
        display_code = item["display_code"]
        plan_mapping = coverage_by_code.get(display_code, [])
        if plan_mapping and item.get("recommended_bundle_id") != plan_mapping[0]:
            errors.append(
                f"bundle mapping mismatch for {display_code}: "
                f"catalog={item.get('recommended_bundle_id')}, plan={plan_mapping[0]}"
            )

    stage_numbers = [
        int(match.group(1))
        for match in re.finditer(r"^### 단계 (\d+) —", content, flags=re.MULTILINE)
    ]
    if stage_numbers != list(range(9)):
        errors.append(
            f"authoring plan stages must be stored exactly once as 0..8, got {stage_numbers}"
        )
    for form, expected_count in EXPECTED_FORM_COUNTS.items():
        match = re.search(
            rf"^\| {re.escape(form)} \| (\d+) \|",
            content,
            flags=re.MULTILINE,
        )
        if match is None or int(match.group(1)) != expected_count:
            errors.append(
                f"authoring plan form count for {form} must be {expected_count}"
            )
    return errors


def validate_questions(repo: Path, data: Any, artifact_type_codes: set[str]) -> list[str]:
    errors: list[str] = []
    if not isinstance(data, dict):
        return ["question data must be an object"]
    metadata = data.get("metadata")
    categories = data.get("categories")
    questions = data.get("questions")
    if not isinstance(metadata, dict):
        errors.append("question metadata must be an object")
    else:
        for field in (
            "schema_version",
            "question_set_id",
            "title",
            "purpose",
            "language",
            "source_policy",
            "completion_rule",
        ):
            if not nonempty_string(metadata.get(field)):
                errors.append(f"question metadata.{field} must be a non-empty string")
        if metadata.get("language") != "ko":
            errors.append("question metadata.language must be ko")
        if metadata.get("expected_question_count") != EXPECTED_QUESTION_COUNT:
            errors.append(
                f"question metadata.expected_question_count must be {EXPECTED_QUESTION_COUNT}"
            )
        if metadata.get("gap_trace_count") != EXPECTED_GAP_TRACE_COUNT:
            errors.append(
                f"question metadata.gap_trace_count must be {EXPECTED_GAP_TRACE_COUNT}"
            )
    if not isinstance(categories, list):
        errors.append("question categories must be an array")
        categories = []
    if not isinstance(questions, list):
        errors.append("questions must be an array")
        return errors
    if len(questions) != EXPECTED_QUESTION_COUNT:
        errors.append(
            f"questions must contain exactly {EXPECTED_QUESTION_COUNT} items, "
            f"got {len(questions)}"
        )

    category_ids = [row.get("id") for row in categories if isinstance(row, dict)]
    if len(category_ids) != len(set(category_ids)):
        errors.append("question category IDs must be unique")
    if set(category_ids) != EXPECTED_QUESTION_CATEGORY_IDS:
        errors.append(
            "question category IDs mismatch: "
            f"missing={sorted(EXPECTED_QUESTION_CATEGORY_IDS - set(category_ids))}, "
            f"extra={sorted(set(category_ids) - EXPECTED_QUESTION_CATEGORY_IDS)}"
        )
    for index, row in enumerate(categories):
        if not isinstance(row, dict):
            errors.append(f"categories[{index}] must be an object")
            continue
        for field in ("id", "title", "description"):
            if not nonempty_string(row.get(field)):
                errors.append(f"categories[{index}].{field} must be a non-empty string")
        if not isinstance(row.get("order"), int):
            errors.append(f"categories[{index}].order must be an integer")
    category_orders = [row.get("order") for row in categories if isinstance(row, dict)]
    if (
        not all(isinstance(order, int) for order in category_orders)
        or category_orders != list(range(1, len(categories) + 1))
    ):
        errors.append(
            "question categories must be stored in the exact contiguous order 1..N"
        )

    question_ids = [item.get("id") for item in questions if isinstance(item, dict)]
    if len(question_ids) != len(set(question_ids)):
        errors.append("question IDs must be unique")
    question_titles = [item.get("title") for item in questions if isinstance(item, dict)]
    if len(question_titles) != len(set(question_titles)):
        errors.append("question titles must be unique")
    question_id_set = {value for value in question_ids if isinstance(value, str)}
    question_by_id = {
        item["id"]: item
        for item in questions
        if isinstance(item, dict) and isinstance(item.get("id"), str)
    }
    category_usage: Counter[str] = Counter()
    decision_level_usage: Counter[str] = Counter()
    all_conflict_ids: set[str] = set()
    dependency_graph: dict[str, list[str]] = {}
    for index, item in enumerate(questions):
        label = f"questions[{index}]"
        if not isinstance(item, dict):
            errors.append(f"{label} must be an object")
            continue
        missing_fields = sorted(QUESTION_REQUIRED_FIELDS - item.keys())
        if missing_fields:
            errors.append(f"{label} missing fields: {missing_fields}")
            continue
        for field in (
            "id",
            "category_id",
            "title",
            "prompt",
            "why_it_matters",
            "current_context",
            "recommendation_reason",
            "followup_guidance",
        ):
            if not nonempty_string(item[field]):
                errors.append(f"{label}.{field} must be a non-empty string")
        category_usage[item["category_id"]] += 1
        if item["category_id"] not in category_ids:
            errors.append(f"{label} references an unknown category")
        question_id_match = re.fullmatch(r"Q-([A-Z]+)-\d{3}", item["id"])
        expected_category_id = (
            f"CAT-{question_id_match.group(1)}" if question_id_match else None
        )
        if question_id_match is None:
            errors.append(f"{label}.id has an invalid format")
        elif item["category_id"] != expected_category_id:
            errors.append(f"{label}.id/category_id prefix mismatch")
        if not isinstance(item["required"], bool):
            errors.append(f"{label}.required must be boolean")
        if item["decision_level"] not in DECISION_LEVELS:
            errors.append(f"{label}.decision_level is invalid")
        else:
            decision_level_usage[item["decision_level"]] += 1
        answer_type = item["answer_type"]
        if answer_type not in ANSWER_TYPES:
            errors.append(f"{label}.answer_type is invalid")
        for field in (
            "options",
            "recommended_option_ids",
            "impacts",
            "affected_deliverable_types",
            "evidence",
            "dependencies",
            "conflict_rules",
        ):
            if not isinstance(item[field], list):
                errors.append(f"{label}.{field} must be an array")
        for field in (
            "recommended_option_ids",
            "impacts",
            "affected_deliverable_types",
            "dependencies",
        ):
            validate_string_list(item.get(field), f"{label}.{field}", errors)
        if not item.get("impacts"):
            errors.append(f"{label}.impacts must not be empty")
        if not item.get("affected_deliverable_types"):
            errors.append(f"{label}.affected_deliverable_types must not be empty")
        elif len(item["affected_deliverable_types"]) > 12:
            errors.append(
                f"{label}.affected_deliverable_types must list direct impacts only "
                f"(maximum 12, got {len(item['affected_deliverable_types'])})"
            )
        options = item.get("options", [])
        option_ids = [option.get("id") for option in options if isinstance(option, dict)]
        if len(option_ids) != len(set(option_ids)):
            errors.append(f"{label} option IDs must be unique")
        for option_index, option in enumerate(options):
            if not isinstance(option, dict):
                errors.append(f"{label}.options[{option_index}] must be an object")
                continue
            for field in ("id", "label", "description", "tradeoffs"):
                if not nonempty_string(option.get(field)):
                    errors.append(f"{label}.options[{option_index}].{field} is required")
        if answer_type in {"single_choice", "multi_choice"}:
            if len(options) < 2:
                errors.append(f"{label} choice question must have at least two options")
            if "custom" not in option_ids:
                errors.append(f"{label} choice question must include option id 'custom'")
            if not item.get("recommended_option_ids"):
                errors.append(f"{label} choice question must have a recommendation")
        elif options or item.get("recommended_option_ids"):
            errors.append(f"{label} text/number question cannot define choice options")
        for option_id in item.get("recommended_option_ids", []):
            if option_id not in option_ids:
                errors.append(f"{label} recommendation references unknown option {option_id}")
        if answer_type == "number":
            validation = item.get("validation")
            if not isinstance(validation, dict):
                errors.append(f"{label} number question requires validation")
            elif not all(field in validation for field in ("min", "max", "unit")):
                errors.append(f"{label} number validation requires min/max/unit")
            elif (
                isinstance(validation.get("min"), bool)
                or isinstance(validation.get("max"), bool)
                or not isinstance(validation.get("min"), (int, float))
                or not isinstance(validation.get("max"), (int, float))
                or validation["min"] > validation["max"]
                or not nonempty_string(validation.get("unit"))
            ):
                errors.append(f"{label} number validation has invalid min/max/unit")
        elif "validation" in item:
            errors.append(f"{label} validation is only allowed for number questions")
        dependency_graph[item["id"]] = list(item.get("dependencies", []))
        for dependency in item.get("dependencies", []):
            if dependency not in question_id_set:
                errors.append(f"{label} dependency is unknown: {dependency}")
            if dependency == item["id"]:
                errors.append(f"{label} cannot depend on itself")
        for affected in item.get("affected_deliverable_types", []):
            if affected not in artifact_type_codes:
                errors.append(f"{label} affected deliverable type is unknown: {affected}")
        guidance_type_codes = set(
            re.findall(
                r"(?<![A-Z0-9-])(?:DOC|MGT|DSC|REQ|DES|DEV|TST|SEC|AIML|REL|OPS|WS|CLS)-\d{2}(?![A-Z0-9-])",
                item.get("followup_guidance", ""),
            )
        )
        affected_type_codes = set(item.get("affected_deliverable_types", []))
        if guidance_type_codes != affected_type_codes:
            errors.append(
                f"{label}.followup_guidance must reference every direct affected type "
                f"and no others: missing={sorted(affected_type_codes - guidance_type_codes)}, "
                f"extra={sorted(guidance_type_codes - affected_type_codes)}"
            )
        for evidence_index, evidence in enumerate(item.get("evidence", [])):
            if not isinstance(evidence, dict):
                errors.append(f"{label}.evidence[{evidence_index}] must be an object")
                continue
            if evidence.get("classification") not in EVIDENCE_CLASSIFICATIONS:
                errors.append(f"{label}.evidence[{evidence_index}] has invalid classification")
            if not nonempty_string(evidence.get("note")):
                errors.append(f"{label}.evidence[{evidence_index}].note is required")
            path = evidence.get("path")
            if path:
                evidence_path = resolve_internal_path(repo, path) if isinstance(path, str) else None
                if evidence_path is None:
                    errors.append(f"{label} evidence path escapes the repository: {path}")
                elif not evidence_path.exists():
                    errors.append(f"{label} evidence path does not exist: {path}")
            else:
                errors.append(f"{label} evidence path is required; use an empty evidence array when no source exists")
        conflict_ids: set[str] = set()
        for rule_index, rule in enumerate(item.get("conflict_rules", [])):
            rule_label = f"{label}.conflict_rules[{rule_index}]"
            if not isinstance(rule, dict):
                errors.append(f"{rule_label} must be an object")
                continue
            if not nonempty_string(rule.get("id")) or rule["id"] in conflict_ids:
                errors.append(f"{rule_label}.id must be non-empty and unique within the question")
            else:
                conflict_ids.add(rule["id"])
                if rule["id"] in all_conflict_ids:
                    errors.append(f"{rule_label}.id must be globally unique")
                all_conflict_ids.add(rule["id"])
            unexpected_rule_fields = set(rule) - {"id", "if", "when", "message", "severity"}
            if unexpected_rule_fields:
                errors.append(f"{rule_label} has unexpected fields: {sorted(unexpected_rule_fields)}")
            if rule.get("severity") not in {"error", "warning"}:
                errors.append(f"{rule_label}.severity is invalid")
            if not nonempty_string(rule.get("message")):
                errors.append(f"{rule_label}.message is required")
            current_condition = rule.get("if")
            if current_condition is not None:
                if not isinstance(current_condition, dict):
                    errors.append(f"{rule_label}.if must be an object")
                else:
                    expected_fields = {"operator"}
                    if current_condition.get("operator") not in {"answered", "unanswered"}:
                        expected_fields.add("value")
                    if set(current_condition) != expected_fields:
                        errors.append(f"{rule_label}.if fields are invalid")
                    if current_condition.get("operator") not in CONFLICT_OPERATORS:
                        errors.append(f"{rule_label}.if.operator is invalid")
                    if (
                        current_condition.get("operator") not in {"answered", "unanswered"}
                        and "value" not in current_condition
                    ):
                        errors.append(f"{rule_label}.if.value is required")
                    elif current_condition.get("operator") not in {"answered", "unanswered"}:
                        validate_condition_option(
                            item,
                            current_condition,
                            f"{rule_label}.if",
                            errors,
                        )
            when = rule.get("when")
            if not isinstance(when, dict):
                errors.append(f"{rule_label}.when must be an object")
                continue
            if when.get("question_id") not in question_id_set:
                errors.append(f"{rule_label} references an unknown question")
            elif when.get("question_id") == item["id"]:
                errors.append(f"{rule_label} must use if for the owning question, not self-reference")
            if when.get("operator") not in CONFLICT_OPERATORS:
                errors.append(f"{rule_label}.when.operator is invalid")
            expected_when_fields = {"question_id", "operator"}
            if when.get("operator") not in {"answered", "unanswered"}:
                expected_when_fields.add("value")
            if set(when) != expected_when_fields:
                errors.append(f"{rule_label}.when fields are invalid")
            if when.get("operator") not in {"answered", "unanswered"} and "value" not in when:
                errors.append(f"{rule_label}.when.value is required")
            elif when.get("question_id") in question_by_id and when.get("operator") in CONFLICT_OPERATORS:
                validate_condition_option(
                    question_by_id[when["question_id"]],
                    when,
                    f"{rule_label}.when",
                    errors,
                )

    unused_categories = sorted(set(category_ids) - set(category_usage))
    if unused_categories:
        errors.append(f"question categories without questions: {unused_categories}")
    category_order_by_id = {
        row["id"]: row["order"]
        for row in categories
        if isinstance(row, dict)
        and isinstance(row.get("id"), str)
        and isinstance(row.get("order"), int)
    }
    question_order_keys: list[tuple[int, int]] = []
    question_numbers_by_category: dict[str, list[int]] = {
        category_id: [] for category_id in category_ids if isinstance(category_id, str)
    }
    for item in questions:
        if not isinstance(item, dict):
            continue
        match = re.fullmatch(r"Q-[A-Z]+-(\d{3})", str(item.get("id", "")))
        category_id = item.get("category_id")
        if match and category_id in category_order_by_id:
            number = int(match.group(1))
            question_order_keys.append((category_order_by_id[category_id], number))
            question_numbers_by_category[category_id].append(number)
    if question_order_keys != sorted(question_order_keys):
        errors.append(
            "questions must be stored contiguously by category order and then numeric ID"
        )
    for category_id, numbers in question_numbers_by_category.items():
        if numbers and numbers != list(range(1, len(numbers) + 1)):
            errors.append(
                f"{category_id} question numbers must be contiguous from 001"
            )
    validate_graph_cycles(dependency_graph, "question dependency", errors)
    missing_decision_levels = sorted(DECISION_LEVELS - set(decision_level_usage))
    if missing_decision_levels:
        errors.append(
            f"question decision levels are not fully covered: {missing_decision_levels}"
        )
    if len(all_conflict_ids) < 20:
        errors.append(
            "question set must contain at least 20 cross-question conflict rules, "
            f"got {len(all_conflict_ids)}"
        )
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument(
        "--artifact-catalog",
        type=Path,
        default=Path("docs/control/artifact-types.json"),
    )
    parser.add_argument(
        "--questions",
        type=Path,
        default=Path("docs/control/questionnaire/walksafe-project-decision-questions.json"),
    )
    parser.add_argument(
        "--source-audit",
        type=Path,
        default=Path("docs/control/questionnaire/source-conflict-audit.md"),
    )
    parser.add_argument(
        "--authoring-plan",
        type=Path,
        default=Path("docs/control/documentation-authoring-preparation-plan.md"),
    )
    args = parser.parse_args()
    repo = args.repo.resolve()
    artifact_path = args.artifact_catalog if args.artifact_catalog.is_absolute() else repo / args.artifact_catalog
    question_path = args.questions if args.questions.is_absolute() else repo / args.questions
    audit_path = args.source_audit if args.source_audit.is_absolute() else repo / args.source_audit
    plan_path = (
        args.authoring_plan
        if args.authoring_plan.is_absolute()
        else repo / args.authoring_plan
    )
    try:
        artifact_data = load_json(artifact_path)
        artifact_errors, type_codes = validate_artifacts(repo, artifact_data)
        plan_errors = validate_authoring_plan(plan_path, artifact_data)
        question_data = load_json(question_path)
        question_errors = validate_questions(repo, question_data, type_codes)
        question_ids = {
            item.get("id")
            for item in question_data.get("questions", [])
            if isinstance(item, dict) and isinstance(item.get("id"), str)
        }
        audit_errors = validate_source_audit(audit_path, question_ids)
    except ValueError as exc:
        print(f"FAIL: {exc}")
        return 1
    errors = artifact_errors + plan_errors + question_errors + audit_errors
    if errors:
        print(f"FAIL: {len(errors)} validation error(s)")
        for error in errors:
            print(f"- {error}")
        return 1
    print(
        "PASS: artifact types=257, "
        f"source audit traces={50 + EXPECTED_GAP_TRACE_COUNT}, "
        f"question categories={len(question_data['categories'])}, "
        f"questions={len(question_data['questions'])}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())

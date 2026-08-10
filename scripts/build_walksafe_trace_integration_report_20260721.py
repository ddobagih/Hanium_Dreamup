#!/usr/bin/env python3
"""Build the leaf REQ-DES-TST structural trace integration report.

This report is intentionally generated after the REQ, DES, and DEV/TST
bundles.  It binds their final files by SHA-256 without being added back to
an upstream manifest, so the trace evidence does not create a hash cycle.

Passing this builder means only that IDs, relative paths, anchors, hashes,
and reverse references are structurally consistent.  It is not a test run,
formal approval, policy-conformance decision, or release authorization.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys
from typing import Any, Iterable


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = Path(__file__).resolve()
CONTROL_DIR = REPO_ROOT / "docs" / "control"
DELIVERABLES_DIR = REPO_ROOT / "docs" / "deliverables"

POLICY_PATH = CONTROL_DIR / "decision-interview" / "walksafe-feature-policy-comprehensive-draft.json"
APPROVAL_PATH = CONTROL_DIR / "baselines" / "walksafe-feature-policy-baseline-1.0.0-approval-20260721-r001.json"
POLICY_MANIFEST_PATH = CONTROL_DIR / "baselines" / "walksafe-feature-policy-baseline-1.0.0-manifest-20260721-r001.json"
FP035_CORRECTION_CANDIDATE_PATH = (
    CONTROL_DIR
    / "decision-interview"
    / "walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json"
)
REQUIREMENTS_RTM_PATH = DELIVERABLES_DIR / "03-requirements" / "rtm.json"
REQUIREMENTS_MANIFEST_PATH = DELIVERABLES_DIR / "manifests" / "requirements-draft-20260721-r001.json"
REQUIREMENTS_DOCUMENT_PATH = DELIVERABLES_DIR / "03-requirements" / "system-requirements.md"
DESIGN_TRACE_PATH = DELIVERABLES_DIR / "04-design" / "design-traceability-register.json"
DESIGN_MANIFEST_PATH = DELIVERABLES_DIR / "manifests" / "design-draft-20260721-r001.json"
DESIGN_DOCUMENT_PATHS = (
    DELIVERABLES_DIR / "04-design" / "software-architecture.md",
    DELIVERABLES_DIR / "04-design" / "interface-and-data-design.md",
    DELIVERABLES_DIR / "04-design" / "user-experience-and-accessibility-design.md",
    DELIVERABLES_DIR / "04-design" / "security-and-operations-design.md",
)
TEST_CASES_PATH = DELIVERABLES_DIR / "06-testing" / "registers" / "test-cases.json"
MODULE_REGISTER_PATH = DELIVERABLES_DIR / "05-implementation" / "module-register.json"
DEV_TEST_MANIFEST_PATH = DELIVERABLES_DIR / "manifests" / "dev-test-draft-20260721-r001.json"

TRACEABILITY_DIR = DELIVERABLES_DIR / "traceability"
REPORT_PATH = TRACEABILITY_DIR / "req-des-tst-integration-report-20260721-r001.json"
README_PATH = TRACEABILITY_DIR / "README.md"

REPORT_ID = "WS-REQ-DES-TST-TRACE-INTEGRATION-20260721-001"
SCHEMA_VERSION = "walksafe.req-des-tst-trace-integration-report.v1"
VERSION = "0.2.0"
AS_OF = "2026-07-22"
STRUCTURAL_STATUS = "STRUCTURAL_TRACE_VALIDATION_PASS"
VERIFICATION_SCOPE = "ID_PATH_HASH_STRUCTURE_ONLY"
FP035_NETWORK_ISSUE_ID = "ISS-POLICY-FP035-NETWORK-001"
FP035_CORRECTION_CANDIDATE_ID = "WS-FEATURE-POLICY-FP035-CORRECTION-CANDIDATE-20260722-001"
FP035_REQUIRED_ACTIVATION_EVENT = "EXACT_NEW_BUNDLED_OWNER_APPROVAL_STATEMENT"
FP035_APPROVAL_BLOCKERS = [FP035_CORRECTION_CANDIDATE_ID, FP035_REQUIRED_ACTIVATION_EVENT]
FP035_DIRECT_DESIGN_IDS = ["DES-04", "DES-13", "DES-20"]
FP035_RELATED_DOWNSTREAM_DESIGN_IDS = ["DES-09"]
FP035_DIRECT_MODULE_IDS = ["MOD-ANDROID-USER"]
FP035_RELATED_DOWNSTREAM_MODULE_IDS = ["MOD-BACKEND"]
FP035_BRANCH_READINESS = "BLOCKED_PENDING_BUNDLED_APPROVAL"
FP035_BRANCH_IMPLEMENTATION_STATUS = "FROZEN_PENDING_EXACT_BUNDLED_APPROVAL"
FP035_FORMAL_TEST_STATUS = "NOT_RUN_BLOCKED_PENDING_EXACT_BUNDLED_APPROVAL"
FP035_NETWORK_BRANCH_REDESIGN = [
    "Wi-Fi 연결",
    "Wi-Fi 없음 + 이동통신망 전송 명시적 선택",
    "Wi-Fi 없음 + 이동통신망 전송 미선택",
]


class TraceIntegrationError(ValueError):
    """Raised when a trace source or generated report is inconsistent."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise TraceIntegrationError(message)


def _object_sha256(value: Any) -> str:
    payload = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _bytes_sha256(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def _json_bytes(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _repo_path(path: Path) -> str:
    try:
        return path.relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return path.as_posix()


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise TraceIntegrationError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load_strict_json(path: Path) -> dict[str, Any]:
    _require(path.is_file(), f"required source is missing: {_repo_path(path)}")
    payload = path.read_bytes()
    _require(not payload.startswith(b"\xef\xbb\xbf"), f"UTF-8 BOM is not allowed: {_repo_path(path)}")
    try:
        text = payload.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise TraceIntegrationError(f"source is not valid UTF-8: {_repo_path(path)}") from exc

    def reject_non_finite(value: str) -> None:
        raise TraceIntegrationError(f"non-finite JSON number is not allowed: {value}")

    try:
        value = json.loads(
            text,
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=reject_non_finite,
        )
    except json.JSONDecodeError as exc:
        raise TraceIntegrationError(f"invalid JSON source: {_repo_path(path)}") from exc
    _require(isinstance(value, dict), f"JSON root must be an object: {_repo_path(path)}")
    return value


def _source_binding(path: Path) -> dict[str, Any]:
    _require(path.is_file(), f"source binding target is missing: {_repo_path(path)}")
    payload = path.read_bytes()
    return {
        "path": _repo_path(path),
        "sha256": _bytes_sha256(payload),
        "byte_length": len(payload),
    }


def _fp035_candidate_binding() -> dict[str, str]:
    return {
        "path": _repo_path(FP035_CORRECTION_CANDIDATE_PATH),
        "sha256": _bytes_sha256(FP035_CORRECTION_CANDIDATE_PATH.read_bytes()),
    }


def _source_bindings() -> dict[str, dict[str, Any]]:
    return {
        "generator": _source_binding(GENERATOR_PATH),
        "policy_payload": _source_binding(POLICY_PATH),
        "policy_approval_record": _source_binding(APPROVAL_PATH),
        "policy_baseline_manifest": _source_binding(POLICY_MANIFEST_PATH),
        "fp035_correction_candidate": _source_binding(FP035_CORRECTION_CANDIDATE_PATH),
        "requirements_rtm": _source_binding(REQUIREMENTS_RTM_PATH),
        "requirements_manifest": _source_binding(REQUIREMENTS_MANIFEST_PATH),
        "requirements_document": _source_binding(REQUIREMENTS_DOCUMENT_PATH),
        "design_traceability_register": _source_binding(DESIGN_TRACE_PATH),
        "design_manifest": _source_binding(DESIGN_MANIFEST_PATH),
        "design_architecture_document": _source_binding(DESIGN_DOCUMENT_PATHS[0]),
        "design_interface_data_document": _source_binding(DESIGN_DOCUMENT_PATHS[1]),
        "design_user_experience_document": _source_binding(DESIGN_DOCUMENT_PATHS[2]),
        "design_security_operations_document": _source_binding(DESIGN_DOCUMENT_PATHS[3]),
        "test_cases": _source_binding(TEST_CASES_PATH),
        "module_register": _source_binding(MODULE_REGISTER_PATH),
        "dev_test_manifest": _source_binding(DEV_TEST_MANIFEST_PATH),
    }


def load_sources() -> dict[str, dict[str, Any]]:
    return {
        "policy": load_strict_json(POLICY_PATH),
        "approval": load_strict_json(APPROVAL_PATH),
        "policy_manifest": load_strict_json(POLICY_MANIFEST_PATH),
        "fp035_correction_candidate": load_strict_json(FP035_CORRECTION_CANDIDATE_PATH),
        "rtm": load_strict_json(REQUIREMENTS_RTM_PATH),
        "requirements_manifest": load_strict_json(REQUIREMENTS_MANIFEST_PATH),
        "design_trace": load_strict_json(DESIGN_TRACE_PATH),
        "design_manifest": load_strict_json(DESIGN_MANIFEST_PATH),
        "test_cases": load_strict_json(TEST_CASES_PATH),
        "module_register": load_strict_json(MODULE_REGISTER_PATH),
        "dev_test_manifest": load_strict_json(DEV_TEST_MANIFEST_PATH),
    }


def _validate_embedded_digest(value: dict[str, Any], key: str, label: str) -> None:
    expected = value.get(key)
    _require(isinstance(expected, str) and len(expected) == 64, f"{label} digest is missing")
    actual = _object_sha256({name: item for name, item in value.items() if name != key})
    _require(expected == actual, f"{label} embedded digest differs")


def _validate_manifest_file(manifest: dict[str, Any], path: Path, label: str) -> None:
    relative = _repo_path(path)
    matches = [item for item in manifest.get("generated_files", []) if item.get("path") == relative]
    _require(len(matches) == 1, f"{label} manifest binding must occur exactly once")
    binding = matches[0]
    payload = path.read_bytes()
    _require(binding.get("sha256") == _bytes_sha256(payload), f"{label} manifest SHA differs")
    _require(binding.get("byte_length") == len(payload), f"{label} manifest byte length differs")


def _normalize_gates(items: Iterable[dict[str, Any]], label: str) -> list[dict[str, Any]]:
    normalized: list[dict[str, Any]] = []
    for item in items:
        gate_id = item.get("id")
        _require(isinstance(gate_id, str) and gate_id.startswith("GATE-"), f"{label} gate ID differs")
        _require(item.get("status") == "NOT_RUN", f"{label} closes gate {gate_id}")
        _require(item.get("waived", False) is False, f"{label} waives gate {gate_id}")
        if "completion_claimed" in item:
            _require(item["completion_claimed"] is False, f"{label} claims gate completion: {gate_id}")
        normalized.append({"id": gate_id, "status": "NOT_RUN", "waived": False})
    _require(len(normalized) == 5, f"{label} gate count must be 5")
    _require(len({item["id"] for item in normalized}) == 5, f"{label} gate IDs must be unique")
    return normalized


def _metadata_status(value: dict[str, Any], label: str) -> None:
    metadata = value.get("metadata", {})
    _require(metadata.get("lifecycle_status") == "DRAFT", f"{label} is not Draft")
    _require(metadata.get("approval_status") == "NOT_APPROVED", f"{label} claims approval")
    _require(metadata.get("release_status") == "NOT_ELIGIBLE", f"{label} authorizes release")


def _validate_source_controls(sources: dict[str, dict[str, Any]]) -> list[dict[str, Any]]:
    policy = sources["policy"]
    approval = sources["approval"]
    policy_manifest = sources["policy_manifest"]
    fp035_candidate = sources["fp035_correction_candidate"]
    rtm = sources["rtm"]
    requirements_manifest = sources["requirements_manifest"]
    design_trace = sources["design_trace"]
    design_manifest = sources["design_manifest"]
    tests = sources["test_cases"]
    modules = sources["module_register"]
    dev_test_manifest = sources["dev_test_manifest"]

    for value, key, label in (
        (policy, "document_content_sha256", "policy payload"),
        (approval, "approval_record_content_sha256", "policy approval record"),
        (policy_manifest, "manifest_content_sha256", "policy baseline manifest"),
        (fp035_candidate, "candidate_content_sha256", "FP-035 correction candidate"),
        (rtm, "document_content_sha256", "requirements RTM"),
        (requirements_manifest, "manifest_content_sha256", "requirements manifest"),
        (design_trace, "register_content_sha256", "design trace register"),
        (design_manifest, "manifest_content_sha256", "design manifest"),
        (dev_test_manifest, "manifest_content_sha256", "DEV/TST manifest"),
    ):
        _validate_embedded_digest(value, key, label)

    approved_payload = approval.get("approved_baseline_payload", {})
    _require(
        approval.get("approved_baseline_payload_sha256") == _object_sha256(approved_payload),
        "approved policy payload digest differs",
    )
    target = approved_payload.get("approval_target", {})
    _require(target.get("document_id") == "WS-FEATURE-POLICY-DRAFT-20260720", "approved document ID differs")
    _require(target.get("document_version") == "1.0.0", "approved document version differs")
    _require(target.get("document_content_sha256") == policy.get("document_content_sha256"), "approved policy digest differs")
    _require(approved_payload.get("baseline_id") == "PB-WALKSAFE-FEATURE-POLICY-1.0.0", "policy baseline ID differs")
    boundary = policy_manifest.get("establishment_boundary", {})
    _require(boundary.get("baseline_status") == "BASELINED", "policy baseline is not established")
    _require(boundary.get("approval_scope") == "POLICY_BASELINE_ONLY", "policy approval scope expanded")
    _require(boundary.get("remaining_gates_are_waived") is False, "policy baseline waives gates")
    _require(boundary.get("release_status") == "NOT_ELIGIBLE", "policy baseline authorizes release")

    for value, label in (
        (rtm, "requirements RTM"),
        (requirements_manifest, "requirements manifest"),
        (design_trace, "design trace register"),
        (design_manifest, "design manifest"),
        (tests, "test case register"),
        (modules, "module register"),
        (dev_test_manifest, "DEV/TST manifest"),
    ):
        _metadata_status(value, label)

    _validate_manifest_file(requirements_manifest, REQUIREMENTS_RTM_PATH, "requirements RTM")
    _validate_manifest_file(design_manifest, DESIGN_TRACE_PATH, "design trace register")
    _validate_manifest_file(dev_test_manifest, TEST_CASES_PATH, "test cases")
    _validate_manifest_file(dev_test_manifest, MODULE_REGISTER_PATH, "module register")
    for document in DESIGN_DOCUMENT_PATHS:
        _validate_manifest_file(design_manifest, document, f"design document {_repo_path(document)}")
    _validate_manifest_file(requirements_manifest, REQUIREMENTS_DOCUMENT_PATH, "requirements document")

    policy_gates = _normalize_gates(policy.get("remaining_gates", []), "policy")
    gate_sources = (
        (approval.get("remaining_gates", []), "approval record"),
        (policy_manifest.get("remaining_gates", []), "policy baseline manifest"),
        (rtm.get("remaining_gates", []), "requirements RTM"),
        (requirements_manifest.get("remaining_gates", []), "requirements manifest"),
        (design_trace.get("remaining_gates", []), "design trace register"),
        (design_manifest.get("remaining_gates", []), "design manifest"),
        (dev_test_manifest.get("remaining_gates", []), "DEV/TST manifest"),
    )
    for gates, label in gate_sources:
        _require(_normalize_gates(gates, label) == policy_gates, f"{label} gate set differs")

    formal_manifests = (requirements_manifest, design_manifest, dev_test_manifest)
    _require(
        all(item["metadata"].get("approval_status") == "NOT_APPROVED" for item in formal_manifests),
        "a formal manifest claims approval",
    )
    _require(
        all(item.get("authorization_boundary", {}).get("formal_deliverables_approved") is False for item in formal_manifests),
        "a formal bundle claims approval",
    )
    _require(
        all(item.get("authorization_boundary", {}).get("release_status") == "NOT_ELIGIBLE" for item in formal_manifests),
        "a formal bundle authorizes release",
    )
    _require(dev_test_manifest.get("formal_test_execution_count") == 0, "formal test execution count is not zero")
    _require(
        all(
            item.get("lifecycle_status") == "DRAFT"
            and item.get("approval_status") == "NOT_APPROVED"
            and item.get("baseline_status") == "NOT_BASELINED"
            for item in rtm.get("requirements", [])
        ),
        "a requirement row claims approval or baseline",
    )
    _require(
        all(
            item.get("lifecycle_status") == "DRAFT"
            and item.get("approval_status") == "NOT_APPROVED"
            for item in design_trace.get("records", [])
        ),
        "a design record claims approval",
    )
    return policy_gates


def _design_target(record: dict[str, Any]) -> dict[str, str]:
    path = record["document_path"]
    anchor = record["anchor"]
    return {
        "design_id": record["design_id"],
        "path": path,
        "anchor": anchor,
        "target": f"{path}#{anchor}",
    }


def _validate_link_anchor(link: dict[str, Any], expected: dict[str, str], label: str) -> None:
    for key, value in expected.items():
        _require(link.get(key) == value, f"{label} {key} differs")
    path = REPO_ROOT / expected["path"]
    _require(path.is_file(), f"{label} target file is missing")
    content = path.read_text(encoding="utf-8")
    _require(f'id="{expected["anchor"]}"' in content, f"{label} anchor is missing")


def _requirement_target(requirement_id: str) -> dict[str, str]:
    path = _repo_path(REQUIREMENTS_DOCUMENT_PATH)
    return {
        "requirement_id": requirement_id,
        "path": path,
        "anchor": requirement_id,
        "target": f"{path}#{requirement_id}",
    }


def _build_trace_records(
    sources: dict[str, dict[str, Any]],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    rtm_rows = sources["rtm"].get("requirements", [])
    design_records = sources["design_trace"].get("records", [])
    test_cases = sources["test_cases"].get("test_cases", [])
    modules = sources["module_register"].get("modules", [])

    _require(len(rtm_rows) == 68, "requirements count must be 68")
    _require(len(design_records) == 27, "design artifact count must be 27")
    _require(len(test_cases) == 279, "test case count must be 279")
    _require(len(modules) > 0, "module register is empty")

    requirements_by_id = {item.get("requirement_id"): item for item in rtm_rows}
    designs_by_id = {item.get("design_id"): item for item in design_records}
    tests_by_id = {item.get("test_case_id"): item for item in test_cases}
    _require(len(requirements_by_id) == 68 and None not in requirements_by_id, "requirement IDs are not unique")
    _require(len(designs_by_id) == 27 and None not in designs_by_id, "design IDs are not unique")
    _require(len(tests_by_id) == 279 and None not in tests_by_id, "test case IDs are not unique")
    _require(set(designs_by_id) == {f"DES-{number:02d}" for number in range(1, 28)}, "DES-01..DES-27 coverage differs")

    requirement_design_trace: list[dict[str, Any]] = []
    acceptance_test_trace: list[dict[str, Any]] = []
    test_design_trace: list[dict[str, Any]] = []
    module_trace: list[dict[str, Any]] = []

    for row in rtm_rows:
        requirement_id = row["requirement_id"]
        links = row.get("design_trace", {}).get("links", [])
        declared_ids = [item.get("design_id") for item in links]
        _require(bool(declared_ids), f"requirement has no design link: {requirement_id}")
        _require(len(declared_ids) == len(set(declared_ids)), f"duplicate requirement-design link: {requirement_id}")
        for link in links:
            design_id = link.get("design_id")
            _require(design_id in designs_by_id, f"unknown design link for {requirement_id}: {design_id}")
            _validate_link_anchor(link, _design_target(designs_by_id[design_id]), f"{requirement_id}->{design_id}")
            _require(link.get("trace_status") == "DRAFT_NOT_APPROVED", f"design link claims approval: {requirement_id}->{design_id}")
        reverse_ids = sorted(
            record["design_id"]
            for record in design_records
            if requirement_id in record.get("planned_specific_requirement_refs", [])
        )
        _require(set(declared_ids) == set(reverse_ids), f"REQ/DES reverse references differ: {requirement_id}")
        requirement_design_trace.append(
            {
                "requirement_id": requirement_id,
                "source_policy_id": row["source_policy_id"],
                "declared_design_ids": declared_ids,
                "reverse_design_ids": reverse_ids,
                "edge_count": len(declared_ids),
                "status": "STRUCTURAL_LINK_VALIDATED",
            }
        )

        for acceptance in row.get("acceptance_conditions", []):
            acceptance_id = acceptance["acceptance_condition_id"]
            test_id = acceptance["planned_test_id"]
            _require(test_id in tests_by_id, f"planned test is missing: {test_id}")
            case = tests_by_id[test_id]
            _require(case.get("requirement_id") == requirement_id, f"test requirement differs: {test_id}")
            _require(case.get("source_policy_id") == row["source_policy_id"], f"test policy source differs: {test_id}")
            _require(case.get("acceptance_condition_id") == acceptance_id, f"test acceptance ID differs: {test_id}")
            _require(case.get("execution_status") == "NOT_RUN", f"test execution was claimed: {test_id}")
            acceptance_test_trace.append(
                {
                    "acceptance_condition_id": acceptance_id,
                    "test_case_id": test_id,
                    "requirement_id": requirement_id,
                    "source_policy_id": row["source_policy_id"],
                    "status": "STRUCTURAL_LINK_VALIDATED",
                }
            )

    _require(len(acceptance_test_trace) == 279, "acceptance/test trace count must be 279")
    _require(
        {item["test_case_id"] for item in acceptance_test_trace} == set(tests_by_id),
        "acceptance/test coverage is not exact",
    )

    for case in test_cases:
        test_id = case["test_case_id"]
        requirement_id = case["requirement_id"]
        _require(requirement_id in requirements_by_id, f"test points to unknown requirement: {test_id}")
        _validate_link_anchor(case.get("requirement_reference", {}), _requirement_target(requirement_id), f"{test_id} requirement")
        _require(case["requirement_reference"].get("trace_status") == "DRAFT_NOT_APPROVED", f"test requirement link claims approval: {test_id}")

        direct_ids = case.get("design_reference_ids", [])
        direct_links = case.get("design_references", [])
        candidate_ids = case.get("design_candidate_reference_ids", [])
        candidate_links = case.get("design_candidate_references", [])
        candidate_role = case.get("design_candidate_role")
        candidate_links_are_trace_edges = case.get("design_candidate_links_are_trace_edges")
        candidate_empty_reason = case.get("design_candidate_empty_reason")
        _require(bool(direct_ids), f"test has no direct design link: {test_id}")
        _require(len(direct_ids) == len(set(direct_ids)) == len(direct_links), f"test direct design links differ: {test_id}")
        _require(len(candidate_ids) == len(set(candidate_ids)) == len(candidate_links), f"test candidate design links differ: {test_id}")
        _require(set(candidate_ids) <= set(direct_ids), f"test candidate designs exceed direct trace scope: {test_id}")
        _require(
            candidate_role == "VERIFICATION_FOCUS_ONLY_NOT_TRACE_EDGE",
            f"test candidate role differs: {test_id}",
        )
        _require(candidate_links_are_trace_edges is False, f"test candidates claim trace edges: {test_id}")
        if candidate_ids:
            _require(candidate_empty_reason is None, f"non-empty candidate list has an empty reason: {test_id}")
        else:
            _require(
                isinstance(candidate_empty_reason, str) and bool(candidate_empty_reason.strip()),
                f"empty candidate list has no reason: {test_id}",
            )

        for design_id, link in zip(direct_ids, direct_links, strict=True):
            _require(design_id in designs_by_id, f"test points to unknown design: {test_id}->{design_id}")
            _validate_link_anchor(link, _design_target(designs_by_id[design_id]), f"{test_id}->{design_id}")
            _require(link.get("trace_status") == "DRAFT_NOT_APPROVED", f"test design link claims approval: {test_id}->{design_id}")
            _require(
                requirement_id in designs_by_id[design_id].get("planned_specific_requirement_refs", []),
                f"test design has no requirement reverse reference: {test_id}->{design_id}",
            )
        expected_direct_ids = [
            item["design_id"]
            for item in requirements_by_id[requirement_id].get("design_trace", {}).get("links", [])
        ]
        _require(direct_ids == expected_direct_ids, f"test direct design list differs from RTM: {test_id}")
        for design_id, link in zip(candidate_ids, candidate_links, strict=True):
            _require(design_id in designs_by_id, f"test points to unknown candidate design: {test_id}->{design_id}")
            _validate_link_anchor(link, _design_target(designs_by_id[design_id]), f"{test_id} candidate->{design_id}")
            _require(
                link.get("trace_status") == "VERIFICATION_FOCUS_ONLY_NOT_TRACE_EDGE",
                f"test verification-focus link role differs: {test_id}->{design_id}",
            )
        test_design_trace.append(
            {
                "test_case_id": test_id,
                "requirement_id": requirement_id,
                "direct_design_ids": direct_ids,
                "candidate_design_ids": candidate_ids,
                "candidate_role": candidate_role,
                "candidate_links_are_trace_edges": candidate_links_are_trace_edges,
                "candidate_empty_reason": candidate_empty_reason,
                "direct_reverse_validated": True,
                "status": "DIRECT_REVERSE_AND_VERIFICATION_FOCUS_VALIDATED",
            }
        )

    _require(
        sum(not item["candidate_design_ids"] for item in test_design_trace) == 48,
        "verification-focus empty test count must be 48",
    )

    for module in modules:
        module_id = module.get("module_id")
        requirement_ids = module.get("requirement_refs", [])
        requirement_links = module.get("requirement_links", [])
        design_ids = module.get("design_refs", [])
        design_links = module.get("design_links", [])
        _require(isinstance(module_id, str), "module ID is missing")
        _require(len(requirement_ids) == len(set(requirement_ids)) == len(requirement_links), f"module requirement links differ: {module_id}")
        _require(len(design_ids) == len(set(design_ids)) == len(design_links), f"module design links differ: {module_id}")
        for requirement_id, link in zip(requirement_ids, requirement_links, strict=True):
            _require(requirement_id in requirements_by_id, f"module points to unknown requirement: {module_id}->{requirement_id}")
            _validate_link_anchor(link, _requirement_target(requirement_id), f"{module_id}->{requirement_id}")
            _require(link.get("trace_status") == "DRAFT_NOT_APPROVED", f"module requirement link claims approval: {module_id}")
        for design_id, link in zip(design_ids, design_links, strict=True):
            _require(design_id in designs_by_id, f"module points to unknown design: {module_id}->{design_id}")
            _validate_link_anchor(link, _design_target(designs_by_id[design_id]), f"{module_id}->{design_id}")
            _require(link.get("trace_status") == "DRAFT_NOT_APPROVED", f"module design link claims approval: {module_id}")
        module_trace.append(
            {
                "module_id": module_id,
                "requirement_ids": requirement_ids,
                "design_ids": design_ids,
                "status": "DECLARED_TARGETS_VALIDATED",
            }
        )

    return requirement_design_trace, acceptance_test_trace, test_design_trace, module_trace


def _validate_fp035(sources: dict[str, dict[str, Any]]) -> dict[str, Any]:
    candidates = sources["rtm"].get("bound_policy_correction_candidates", [])
    _require(
        len(candidates) == 1 and candidates[0].get("issue_id") == FP035_NETWORK_ISSUE_ID,
        "FP-035 correction candidate is missing from RTM",
    )
    candidate_binding = candidates[0]
    _require(
        candidate_binding.get("status") == "CORRECTION_CANDIDATE_BOUND_NOT_APPROVED_NOT_EFFECTIVE",
        "FP-035 correction candidate status differs",
    )
    _require(
        candidate_binding.get("correction_candidate_id") == FP035_CORRECTION_CANDIDATE_ID,
        "FP-035 correction candidate ID differs",
    )
    _require(candidate_binding.get("correction_candidate_approval_status") == "NOT_APPROVED", "FP-035 candidate claims approval")
    _require(candidate_binding.get("correction_candidate_effective_status") == "NOT_EFFECTIVE", "FP-035 candidate claims effect")

    candidate = sources["fp035_correction_candidate"]
    metadata = candidate.get("metadata", {})
    boundary = candidate.get("authorization_boundary", {})
    correction = candidate.get("correction", {})
    _require(metadata.get("candidate_id") == FP035_CORRECTION_CANDIDATE_ID, "FP-035 candidate source ID differs")
    _require(metadata.get("approval_status") == "NOT_APPROVED", "FP-035 candidate source claims approval")
    _require(metadata.get("effective_status") == "NOT_EFFECTIVE", "FP-035 candidate source claims effect")
    _require(boundary.get("required_activation_event") == FP035_REQUIRED_ACTIVATION_EVENT, "FP-035 activation event differs")
    _require(boundary.get("authoring_and_planning_allowed_before_activation") is True, "FP-035 authoring is unexpectedly blocked")
    _require(
        boundary.get("mobile_network_branch_implementation_status") == "FROZEN_PENDING_EXACT_BUNDLED_APPROVAL",
        "FP-035 implementation freeze differs",
    )
    _require(
        boundary.get("mobile_network_branch_formal_test_status") == "NOT_RUN_BLOCKED_PENDING_EXACT_BUNDLED_APPROVAL",
        "FP-035 formal-test freeze differs",
    )
    _require(
        correction.get("affected_artifact_codes") == ["REQ-03", "REQ-06", *FP035_DIRECT_DESIGN_IDS],
        "FP-035 direct artifact scope differs",
    )
    fp035_row = next(
        (item for item in sources["rtm"]["requirements"] if item.get("source_policy_id") == "FP-035"),
        None,
    )
    _require(fp035_row is not None, "FP-035 requirement is missing")
    _require(fp035_row.get("requirement_id") == "RQ-FP-035-001", "FP-035 requirement ID differs")
    _require(fp035_row.get("source_issue_ids") == [], "FP-035 requirement keeps a resolved owner-question blocker")
    _require(fp035_row.get("approval_blockers") == FP035_APPROVAL_BLOCKERS, "FP-035 approval blocker differs")
    _require(fp035_row.get("execution_blockers") == FP035_APPROVAL_BLOCKERS, "FP-035 execution blocker differs")
    _require(fp035_row.get("required_activation_event") == FP035_REQUIRED_ACTIVATION_EVENT, "FP-035 requirement activation event differs")
    _require(
        fp035_row.get("formal_branch_implementation_and_test_readiness") == "BLOCKED_PENDING_BUNDLED_APPROVAL",
        "FP-035 requirement execution readiness differs",
    )

    cases = [item for item in sources["test_cases"]["test_cases"] if item.get("source_policy_id") == "FP-035"]
    expected_case_ids = [f"TC-FP-035-{number:02d}" for number in range(1, 5)]
    _require([item["test_case_id"] for item in cases] == expected_case_ids, "FP-035 blocked test set differs")
    for case in cases:
        test_id = case["test_case_id"]
        _require(case.get("source_issue_ids") == [FP035_NETWORK_ISSUE_ID], f"{test_id} issue ref differs")
        _require(case.get("policy_correction_candidate_id") == FP035_CORRECTION_CANDIDATE_ID, f"{test_id} candidate ID differs")
        _require(case.get("policy_correction_candidate_refs") == [FP035_CORRECTION_CANDIDATE_ID], f"{test_id} candidate refs differ")
        _require(case.get("correction_candidate_binding") == _fp035_candidate_binding(), f"{test_id} candidate binding differs")
        _require(case.get("required_activation_event") == FP035_REQUIRED_ACTIVATION_EVENT, f"{test_id} activation event differs")
        _require(case.get("bundled_approval_dependency_refs") == FP035_APPROVAL_BLOCKERS, f"{test_id} bundled approval refs differ")
        _require(case.get("approval_blockers") == FP035_APPROVAL_BLOCKERS, f"{test_id} approval blockers differ")
        _require(case.get("execution_blockers") == FP035_APPROVAL_BLOCKERS, f"{test_id} execution blockers differ")
        _require(case.get("approval_readiness") == FP035_BRANCH_READINESS, f"{test_id} approval readiness differs")
        _require(case.get("execution_readiness") == FP035_BRANCH_READINESS, f"{test_id} execution readiness differs")
        _require(case.get("mobile_network_branch_formal_test_status") == FP035_FORMAL_TEST_STATUS, f"{test_id} formal-test status differs")
        _require(case.get("formal_branch_implementation_and_test_frozen") is True, f"{test_id} branch freeze differs")
        _require(case.get("execution_status") == "NOT_RUN", f"{test_id} execution was claimed")
        _require(case.get("network_branch_redesign_after_issue_resolution") == FP035_NETWORK_BRANCH_REDESIGN, f"{test_id} redesign branches differ")
        _require(case.get("network_branch_redesign_status") == "NORMALIZED_NOT_YET_BASELINED", f"{test_id} redesign status differs")

    module_issue_records = sources["module_register"].get("known_source_policy_issues", [])
    _require(
        len(module_issue_records) == 1
        and module_issue_records[0].get("issue_id") == FP035_NETWORK_ISSUE_ID
        and module_issue_records[0].get("owner_clarification_required") is False,
        "FP-035 module-register candidate boundary differs",
    )
    module_issue = module_issue_records[0]
    _require(module_issue.get("status") == "OPEN_OWNER_DIRECTIVE_CAPTURED_PENDING_BUNDLED_APPROVAL", "FP-035 module issue status differs")
    _require(module_issue.get("correction_candidate_id") == FP035_CORRECTION_CANDIDATE_ID, "FP-035 module candidate ID differs")
    _require(module_issue.get("correction_candidate_binding") == _fp035_candidate_binding(), "FP-035 module candidate binding differs")
    _require(module_issue.get("required_activation_event") == FP035_REQUIRED_ACTIVATION_EVENT, "FP-035 module activation event differs")
    _require(module_issue.get("approval_blockers") == FP035_APPROVAL_BLOCKERS, "FP-035 module approval blockers differ")
    _require(module_issue.get("mobile_network_branch_implementation_and_test_readiness") == FP035_BRANCH_READINESS, "FP-035 module branch readiness differs")
    _require(module_issue.get("mobile_network_branch_implementation_status") == FP035_BRANCH_IMPLEMENTATION_STATUS, "FP-035 module implementation status differs")
    _require(module_issue.get("mobile_network_branch_formal_test_status") == FP035_FORMAL_TEST_STATUS, "FP-035 module formal-test status differs")
    _require(module_issue.get("affected_module_ids") == [*FP035_DIRECT_MODULE_IDS, *FP035_RELATED_DOWNSTREAM_MODULE_IDS], "FP-035 affected module IDs differ")
    modules = sources["module_register"].get("modules", [])
    for module in modules:
        module_id = module["module_id"]
        direct = module_id in FP035_DIRECT_MODULE_IDS
        related = module_id in FP035_RELATED_DOWNSTREAM_MODULE_IDS
        affected = direct or related
        _require(module.get("source_issue_ids") == ([FP035_NETWORK_ISSUE_ID] if affected else []), f"module issue refs differ: {module_id}")
        _require(module.get("policy_correction_candidate_refs") == ([FP035_CORRECTION_CANDIDATE_ID] if affected else []), f"module candidate refs differ: {module_id}")
        _require(module.get("direct_policy_correction_candidate_refs") == ([FP035_CORRECTION_CANDIDATE_ID] if direct else []), f"module direct candidate refs differ: {module_id}")
        _require(module.get("related_policy_correction_candidate_refs") == ([FP035_CORRECTION_CANDIDATE_ID] if related else []), f"module related candidate refs differ: {module_id}")
        expected_relation = (
            "DIRECT_MOBILE_NETWORK_BRANCH_IMPLEMENTATION_DEPENDENCY"
            if direct
            else "RELATED_DOWNSTREAM_UPLOAD_CONTRACT_DEPENDENCY"
            if related
            else None
        )
        _require(module.get("fp035_dependency_relation") == expected_relation, f"module dependency relation differs: {module_id}")
        _require(module.get("correction_candidate_binding") == (_fp035_candidate_binding() if affected else None), f"module candidate binding differs: {module_id}")
        _require(module.get("bundled_approval_dependency_refs") == (FP035_APPROVAL_BLOCKERS if affected else []), f"module bundled approval refs differ: {module_id}")
        _require(module.get("required_activation_event") == (FP035_REQUIRED_ACTIVATION_EVENT if affected else None), f"module activation event differs: {module_id}")
        _require(module.get("implementation_blockers") == (FP035_APPROVAL_BLOCKERS if affected else []), f"module implementation blockers differ: {module_id}")
        _require(module.get("mobile_data_branch_status") == (FP035_BRANCH_READINESS if affected else "NOT_APPLICABLE"), f"module branch readiness differs: {module_id}")
        _require(module.get("mobile_network_branch_implementation_status") == (FP035_BRANCH_IMPLEMENTATION_STATUS if affected else None), f"module implementation status differs: {module_id}")
        _require(module.get("mobile_network_branch_formal_test_status") == (FP035_FORMAL_TEST_STATUS if affected else None), f"module formal-test status differs: {module_id}")
        _require(module.get("formal_branch_implementation_and_test_frozen") is affected, f"module branch freeze differs: {module_id}")

    general_trace_designs = [
        item for item in sources["design_trace"]["records"] if "FP-035" in item.get("policy_feature_refs", [])
    ]
    _require(len(general_trace_designs) == 16, "FP-035 general policy trace count must be 16")
    for record in sources["design_trace"]["records"]:
        design_id = record["design_id"]
        direct = design_id in FP035_DIRECT_DESIGN_IDS
        related = design_id in FP035_RELATED_DOWNSTREAM_DESIGN_IDS
        _require(record.get("source_issue_refs") == [], f"design keeps a resolved owner-question issue: {design_id}")
        _require(record.get("approval_blockers") == (FP035_APPROVAL_BLOCKERS if direct else []), f"design approval blockers differ: {design_id}")
        _require(record.get("execution_blockers") == (FP035_APPROVAL_BLOCKERS if direct else []), f"design execution blockers differ: {design_id}")
        _require(record.get("normalization_refs") == (["DEC-FP035-NETWORK-NORMALIZATION-20260722"] if direct else []), f"design normalization refs differ: {design_id}")
        _require(record.get("policy_correction_candidate_refs") == ([FP035_CORRECTION_CANDIDATE_ID] if direct else []), f"design correction candidate refs differ: {design_id}")
        _require(record.get("required_activation_event") == (FP035_REQUIRED_ACTIVATION_EVENT if direct else None), f"design activation event differs: {design_id}")
        _require(record.get("formal_branch_implementation_and_test_frozen") is direct, f"design formal branch freeze differs: {design_id}")
        _require(record.get("approval_readiness") == ("BLOCKED_PENDING_BUNDLED_APPROVAL" if direct else "DRAFT_REVIEW_REQUIRED"), f"design approval readiness differs: {design_id}")
        _require(record.get("related_dependency_status") == ("RELATED_DOWNSTREAM_DEPENDENCY" if related else None), f"design related dependency status differs: {design_id}")
        _require(record.get("related_policy_correction_candidate_refs") == ([FP035_CORRECTION_CANDIDATE_ID] if related else []), f"design related candidate refs differ: {design_id}")
        _require(record.get("related_bundled_approval_dependency_refs") == (FP035_APPROVAL_BLOCKERS if related else []), f"design related approval refs differ: {design_id}")
        _require(record.get("related_upstream_design_refs") == (FP035_DIRECT_DESIGN_IDS if related else []), f"design related upstream refs differ: {design_id}")

    result = dict(candidate_binding)
    result["correction_candidate_path"] = _repo_path(FP035_CORRECTION_CANDIDATE_PATH)
    result["correction_candidate_file_sha256"] = _bytes_sha256(FP035_CORRECTION_CANDIDATE_PATH.read_bytes())
    result["required_activation_event"] = FP035_REQUIRED_ACTIVATION_EVENT
    result["approval_blockers"] = FP035_APPROVAL_BLOCKERS
    result["authoring_and_planning_readiness"] = "ALLOWED"
    result["mobile_network_branch_implementation_and_test_readiness"] = "BLOCKED_PENDING_BUNDLED_APPROVAL"
    result["blocked_test_case_ids"] = expected_case_ids
    result["blocked_test_case_count"] = 4
    result["general_policy_trace_design_ids"] = [item["design_id"] for item in general_trace_designs]
    result["general_policy_trace_design_count"] = 16
    result["direct_affected_design_ids"] = FP035_DIRECT_DESIGN_IDS
    result["direct_affected_design_count"] = 3
    result["related_downstream_design_ids"] = FP035_RELATED_DOWNSTREAM_DESIGN_IDS
    result["related_downstream_design_count"] = 1
    result["direct_affected_module_ids"] = FP035_DIRECT_MODULE_IDS
    result["direct_affected_module_count"] = 1
    result["related_downstream_module_ids"] = FP035_RELATED_DOWNSTREAM_MODULE_IDS
    result["related_downstream_module_count"] = 1
    return result


def build_report(sources: dict[str, dict[str, Any]] | None = None) -> dict[str, Any]:
    if sources is None:
        sources = load_sources()
    remaining_gates = _validate_source_controls(sources)
    requirement_trace, acceptance_trace, test_trace, module_trace = _build_trace_records(sources)
    fp035_issue = _validate_fp035(sources)
    bindings = _source_bindings()
    rtm_rows = sources["rtm"]["requirements"]
    report: dict[str, Any] = {
        "schema_version": SCHEMA_VERSION,
        "metadata": {
            "report_id": REPORT_ID,
            "version": VERSION,
            "as_of": AS_OF,
            "title": "WalkSafe REQ·DES·TST 교차추적 구조 검증 보고서",
            "lifecycle_status": "DRAFT",
            "approval_status": "NOT_APPROVED",
            "structural_validation_status": STRUCTURAL_STATUS,
            "verification_scope": VERIFICATION_SCOPE,
            "formal_test_execution_status": "NOT_RUN",
            "release_status": "NOT_ELIGIBLE",
        },
        "source_bindings": bindings,
        "source_binding_sha256": _object_sha256(bindings),
        "authorization_boundary": {
            "policy_baseline_status": "BASELINED",
            "fp035_correction_candidate_id": FP035_CORRECTION_CANDIDATE_ID,
            "fp035_correction_candidate_file_sha256": fp035_issue["correction_candidate_file_sha256"],
            "fp035_correction_candidate_approval_status": "NOT_APPROVED",
            "fp035_correction_candidate_effective_status": "NOT_EFFECTIVE",
            "fp035_required_activation_event": FP035_REQUIRED_ACTIVATION_EVENT,
            "fp035_authoring_and_planning_readiness": "ALLOWED",
            "fp035_mobile_network_branch_implementation_and_test_readiness": "BLOCKED_PENDING_BUNDLED_APPROVAL",
            "formal_approval_count": 0,
            "formal_deliverables_approved": False,
            "requirements_baselined": False,
            "formal_test_execution_count": 0,
            "test_completion_claimed": False,
            "remaining_gates_waived": False,
            "release_authorized": False,
            "release_status": "NOT_ELIGIBLE",
            "structural_validation_is_not_test_execution_or_approval": True,
            "plain_explanation": "파일의 ID·경로·연결·지문이 서로 맞는지만 확인했다. 기능 시험, 정책 적합 판정, 문서 승인, 출시 승인은 하지 않았다.",
        },
        "coverage": {
            "source_binding_count": len(bindings),
            "requirement_count": len(rtm_rows),
            "feature_requirement_count": sum(item.get("source_kind") == "FEATURE_POLICY" for item in rtm_rows),
            "common_policy_requirement_count": sum(item.get("source_kind") == "COMMON_POLICY" for item in rtm_rows),
            "remaining_gate_requirement_count": sum(item.get("source_kind") == "REMAINING_GATE" for item in rtm_rows),
            "requirement_design_edge_count": sum(item["edge_count"] for item in requirement_trace),
            "acceptance_condition_count": len(acceptance_trace),
            "test_case_count": len(test_trace),
            "direct_test_design_edge_count": sum(len(item["direct_design_ids"]) for item in test_trace),
            "verification_focus_candidate_reference_count": sum(len(item["candidate_design_ids"]) for item in test_trace),
            "verification_focus_empty_test_case_count": sum(not item["candidate_design_ids"] for item in test_trace),
            "design_artifact_count": len(sources["design_trace"]["records"]),
            "module_count": len(module_trace),
            "module_requirement_edge_count": sum(len(item["requirement_ids"]) for item in module_trace),
            "module_design_edge_count": sum(len(item["design_ids"]) for item in module_trace),
            "blocked_fp035_test_case_count": 4,
            "fp035_general_policy_trace_design_count": 16,
            "fp035_direct_affected_design_count": 3,
            "fp035_related_downstream_design_count": 1,
            "fp035_direct_affected_module_count": 1,
            "fp035_related_downstream_module_count": 1,
            "pending_fp035_correction_candidate_count": 1,
            "remaining_gate_count": len(remaining_gates),
            "formal_approval_count": 0,
            "formal_test_execution_count": 0,
            "open_policy_issue_count": 1,
        },
        "requirement_design_trace": requirement_trace,
        "acceptance_test_trace": acceptance_trace,
        "test_design_trace": test_trace,
        "module_trace": module_trace,
        "open_policy_issues": [fp035_issue],
        "remaining_gates": remaining_gates,
    }
    report["report_content_sha256"] = _object_sha256(report)
    validate_report(report, sources)
    return report


def _expected_coverage(report: dict[str, Any]) -> dict[str, int]:
    requirement_trace = report["requirement_design_trace"]
    acceptance_trace = report["acceptance_test_trace"]
    test_trace = report["test_design_trace"]
    module_trace = report["module_trace"]
    source_kinds = {
        item["source_policy_id"]: (
            "FEATURE_POLICY" if item["source_policy_id"].startswith("FP-")
            else "REMAINING_GATE" if item["source_policy_id"].startswith("GATE-")
            else "COMMON_POLICY"
        )
        for item in requirement_trace
    }
    return {
        "source_binding_count": len(report["source_bindings"]),
        "requirement_count": len(requirement_trace),
        "feature_requirement_count": sum(value == "FEATURE_POLICY" for value in source_kinds.values()),
        "common_policy_requirement_count": sum(value == "COMMON_POLICY" for value in source_kinds.values()),
        "remaining_gate_requirement_count": sum(value == "REMAINING_GATE" for value in source_kinds.values()),
        "requirement_design_edge_count": sum(item["edge_count"] for item in requirement_trace),
        "acceptance_condition_count": len(acceptance_trace),
        "test_case_count": len(test_trace),
        "direct_test_design_edge_count": sum(len(item["direct_design_ids"]) for item in test_trace),
        "verification_focus_candidate_reference_count": sum(len(item["candidate_design_ids"]) for item in test_trace),
        "verification_focus_empty_test_case_count": sum(not item["candidate_design_ids"] for item in test_trace),
        "design_artifact_count": 27,
        "module_count": len(module_trace),
        "module_requirement_edge_count": sum(len(item["requirement_ids"]) for item in module_trace),
        "module_design_edge_count": sum(len(item["design_ids"]) for item in module_trace),
        "blocked_fp035_test_case_count": 4,
        "fp035_general_policy_trace_design_count": 16,
        "fp035_direct_affected_design_count": 3,
        "fp035_related_downstream_design_count": 1,
        "fp035_direct_affected_module_count": 1,
        "fp035_related_downstream_module_count": 1,
        "pending_fp035_correction_candidate_count": 1,
        "remaining_gate_count": len(report["remaining_gates"]),
        "formal_approval_count": 0,
        "formal_test_execution_count": 0,
        "open_policy_issue_count": len(report["open_policy_issues"]),
    }


def validate_report(report: dict[str, Any], sources: dict[str, dict[str, Any]] | None = None) -> None:
    if sources is None:
        sources = load_sources()
    _require(report.get("schema_version") == SCHEMA_VERSION, "report schema differs")
    metadata = report.get("metadata", {})
    _require(metadata.get("report_id") == REPORT_ID, "report ID differs")
    _require(metadata.get("lifecycle_status") == "DRAFT", "report must be Draft")
    _require(metadata.get("approval_status") == "NOT_APPROVED", "report must not claim approval")
    _require(metadata.get("structural_validation_status") == STRUCTURAL_STATUS, "structural status differs")
    _require(metadata.get("verification_scope") == VERIFICATION_SCOPE, "verification scope differs")
    _require(metadata.get("formal_test_execution_status") == "NOT_RUN", "report claims test execution")
    _require(metadata.get("release_status") == "NOT_ELIGIBLE", "report authorizes release")

    expected_bindings = _source_bindings()
    _require(report.get("source_bindings") == expected_bindings, "report source bindings are stale")
    _require(report.get("source_binding_sha256") == _object_sha256(expected_bindings), "source binding digest differs")
    _require(_repo_path(REPORT_PATH) not in {item["path"] for item in expected_bindings.values()}, "report binds itself")
    _require(_repo_path(README_PATH) not in {item["path"] for item in expected_bindings.values()}, "report binds its summary")

    boundary = report.get("authorization_boundary", {})
    expected_false = (
        "formal_deliverables_approved",
        "requirements_baselined",
        "test_completion_claimed",
        "remaining_gates_waived",
        "release_authorized",
    )
    _require(all(boundary.get(key) is False for key in expected_false), "authorization boundary overclaims completion")
    _require(boundary.get("formal_approval_count") == 0, "formal approval count is not zero")
    _require(boundary.get("formal_test_execution_count") == 0, "formal test execution count is not zero")
    _require(boundary.get("release_status") == "NOT_ELIGIBLE", "boundary authorizes release")
    _require(boundary.get("structural_validation_is_not_test_execution_or_approval") is True, "scope warning is missing")
    _require(boundary.get("fp035_correction_candidate_id") == FP035_CORRECTION_CANDIDATE_ID, "boundary FP-035 candidate ID differs")
    _require(
        boundary.get("fp035_correction_candidate_file_sha256")
        == _bytes_sha256(FP035_CORRECTION_CANDIDATE_PATH.read_bytes()),
        "boundary FP-035 candidate file SHA differs",
    )
    _require(boundary.get("fp035_correction_candidate_approval_status") == "NOT_APPROVED", "boundary FP-035 candidate claims approval")
    _require(boundary.get("fp035_correction_candidate_effective_status") == "NOT_EFFECTIVE", "boundary FP-035 candidate claims effect")
    _require(boundary.get("fp035_required_activation_event") == FP035_REQUIRED_ACTIVATION_EVENT, "boundary FP-035 activation event differs")
    _require(boundary.get("fp035_authoring_and_planning_readiness") == "ALLOWED", "boundary FP-035 authoring readiness differs")
    _require(
        boundary.get("fp035_mobile_network_branch_implementation_and_test_readiness") == "BLOCKED_PENDING_BUNDLED_APPROVAL",
        "boundary FP-035 branch readiness differs",
    )

    _require(len(report.get("requirement_design_trace", [])) == 68, "report requirement trace count differs")
    _require(len(report.get("acceptance_test_trace", [])) == 279, "report acceptance trace count differs")
    _require(len(report.get("test_design_trace", [])) == 279, "report test trace count differs")
    _require(len(report.get("module_trace", [])) > 0, "report module trace is empty")
    _require(
        all(item.get("status") == "STRUCTURAL_LINK_VALIDATED" for item in report["requirement_design_trace"]),
        "a requirement-design trace is not structurally validated",
    )
    _require(
        all(item.get("status") == "STRUCTURAL_LINK_VALIDATED" for item in report["acceptance_test_trace"]),
        "an acceptance-test trace is not structurally validated",
    )
    _require(
        all(
            item.get("status") == "DIRECT_REVERSE_AND_VERIFICATION_FOCUS_VALIDATED"
            and item.get("direct_reverse_validated") is True
            and item.get("candidate_role") == "VERIFICATION_FOCUS_ONLY_NOT_TRACE_EDGE"
            and item.get("candidate_links_are_trace_edges") is False
            for item in report["test_design_trace"]
        ),
        "a test-design trace is not structurally validated",
    )
    _require(
        all(item.get("status") == "DECLARED_TARGETS_VALIDATED" for item in report["module_trace"]),
        "a module trace is not structurally validated",
    )
    _require(report.get("coverage") == _expected_coverage(report), "report coverage differs")
    _require(report["coverage"]["feature_requirement_count"] == 54, "feature requirement count differs")
    _require(report["coverage"]["common_policy_requirement_count"] == 9, "common-policy requirement count differs")
    _require(report["coverage"]["remaining_gate_requirement_count"] == 5, "gate requirement count differs")
    _require(report["coverage"]["fp035_general_policy_trace_design_count"] == 16, "FP-035 general trace design count differs")
    _require(report["coverage"]["fp035_direct_affected_design_count"] == 3, "FP-035 direct design count differs")
    _require(report["coverage"]["fp035_related_downstream_design_count"] == 1, "FP-035 related design count differs")
    _require(report["coverage"]["fp035_direct_affected_module_count"] == 1, "FP-035 direct module count differs")
    _require(report["coverage"]["fp035_related_downstream_module_count"] == 1, "FP-035 related module count differs")
    _require(report["coverage"]["pending_fp035_correction_candidate_count"] == 1, "FP-035 pending candidate count differs")

    issues = report.get("open_policy_issues", [])
    _require(len(issues) == 1 and issues[0].get("issue_id") == FP035_NETWORK_ISSUE_ID, "FP-035 open issue differs")
    _require(issues[0].get("blocked_test_case_count") == 4, "FP-035 blocked case count differs")
    _require(
        issues[0].get("status") == "CORRECTION_CANDIDATE_BOUND_NOT_APPROVED_NOT_EFFECTIVE",
        "FP-035 correction candidate status differs",
    )
    _require(issues[0].get("correction_candidate_id") == FP035_CORRECTION_CANDIDATE_ID, "FP-035 candidate ID is missing")
    _require(issues[0].get("required_activation_event") == FP035_REQUIRED_ACTIVATION_EVENT, "FP-035 activation event is missing")
    _require(issues[0].get("direct_affected_design_ids") == FP035_DIRECT_DESIGN_IDS, "FP-035 direct design IDs differ")
    _require(issues[0].get("related_downstream_design_ids") == FP035_RELATED_DOWNSTREAM_DESIGN_IDS, "FP-035 related design IDs differ")
    _require(issues[0].get("direct_affected_module_ids") == FP035_DIRECT_MODULE_IDS, "FP-035 direct module IDs differ")
    _require(issues[0].get("related_downstream_module_ids") == FP035_RELATED_DOWNSTREAM_MODULE_IDS, "FP-035 related module IDs differ")
    _require(_normalize_gates(report.get("remaining_gates", []), "report") == report["remaining_gates"], "report gates differ")

    expected_records = _build_trace_records(sources)
    for key, expected in zip(
        ("requirement_design_trace", "acceptance_test_trace", "test_design_trace", "module_trace"),
        expected_records,
        strict=True,
    ):
        _require(report[key] == expected, f"report {key} differs from source")
    _require(report["open_policy_issues"] == [_validate_fp035(sources)], "report FP-035 issue differs from source")
    _require(report["remaining_gates"] == _validate_source_controls(sources), "report gates differ from source")
    _require(
        report.get("report_content_sha256")
        == _object_sha256({key: value for key, value in report.items() if key != "report_content_sha256"}),
        "report content digest differs",
    )


def build_readme(report: dict[str, Any]) -> str:
    coverage = report["coverage"]
    fp035 = report["open_policy_issues"][0]
    gates = "\n".join(f"- `{item['id']}` — 아직 실행하지 않았고 면제하지 않음" for item in report["remaining_gates"])
    return f"""# WalkSafe 요구·설계·시험 연결 확인 결과

결론은 **문서 연결 구조 확인 완료 — 앱 시험 아님**입니다. 요구사항, 설계, 시험 케이스, 구현 모듈의 번호와 연결 경로가 서로 맞는지 자동으로 확인했으며 기계 상태값은 `{STRUCTURAL_STATUS}`입니다.

쉽게 말하면 **문서 사이의 목차와 연결표가 끊기지 않았는지 확인한 것**입니다. 실제 앱 기능을 실행해 합격시킨 것이 아니고, 문서를 승인하거나 출시를 허가한 것도 아닙니다.

## 확인한 범위

| 확인 대상 | 결과 |
|---|---:|
| 요구사항 | {coverage['requirement_count']}개 |
| 요구사항→설계 연결 | {coverage['requirement_design_edge_count']}개 |
| 인수조건→시험 케이스 연결 | {coverage['acceptance_condition_count']}개 |
| 시험 케이스 | {coverage['test_case_count']}개 |
| 시험할 설계 초점 표시 | {coverage['verification_focus_candidate_reference_count']}개 |
| 설계 초점을 억지로 고르지 않고 비워 둔 시험 | {coverage['verification_focus_empty_test_case_count']}개 |
| 설계 산출물 | {coverage['design_artifact_count']}개 |
| FP-035 일반 정책 추적 설계 | {coverage['fp035_general_policy_trace_design_count']}개 |
| FP-035 직접 영향 설계 | {coverage['fp035_direct_affected_design_count']}개 |
| FP-035 하류 의존 설계 | {coverage['fp035_related_downstream_design_count']}개 |
| 구현 모듈 | {coverage['module_count']}개 |
| FP-035 직접 영향 모듈 | {coverage['fp035_direct_affected_module_count']}개 |
| FP-035 하류 의존 모듈 | {coverage['fp035_related_downstream_module_count']}개 |
| 실제 정식 시험 실행 | {coverage['formal_test_execution_count']}개 |
| 정식 산출물 승인 | {coverage['formal_approval_count']}개 |

확인한 것은 다음과 같습니다.

- 연결된 ID가 실제 원장에 존재하는지
- 상대경로와 문서 안의 이동 위치(anchor)가 실제로 존재하는지
- 요구사항에서 설계로 연결한 내용이 설계에서도 해당 요구사항을 다시 가리키는지
- 인수조건 279개와 시험 케이스 279개가 일대일로 맞는지
- 각 입력 파일의 SHA-256 지문이 현재 파일과 같은지

시험할 설계 초점 표시는 이미 확인된 요구사항→설계 연결 안에서 무엇을 중점적으로 시험할지 좁혀 보여주는 참고 목록입니다. 새로운 추적 연결로 세지 않습니다. 맞는 초점을 근거 있게 고를 수 없는 48개 시험은 목록을 비워 두고 그 이유를 기록했습니다.

확인하지 않은 것은 다음과 같습니다.

- 앱 기능의 실제 동작과 합격 여부
- 설계와 구현이 실제로 같은지
- 성능·보안·접근성·현장시험 결과
- 정책이나 정식 산출물의 승인
- 출시 가능 여부

## FP-035 승인 대기 정정 후보

기존 답변으로 방향은 정해졌으므로 제품 방향을 다시 질문할 상태가 아닙니다. 다만 아래 정정 후보와 직접 영향 산출물을 정확한 한 묶음 승인문으로 승인하기 전까지 정책 효력은 발생하지 않습니다.

| 항목 | 값 |
|---|---|
| 이슈 | `{FP035_NETWORK_ISSUE_ID}` |
| 정정 후보 | `{FP035_CORRECTION_CANDIDATE_ID}` |
| 후보 상태 | `NOT_APPROVED / NOT_EFFECTIVE` |
| 후보 파일 SHA-256 | `{fp035['correction_candidate_file_sha256']}` |
| 효력 발생 조건 | `{FP035_REQUIRED_ACTIVATION_EVENT}` |
| 직접 영향 설계 | `DES-04`, `DES-13`, `DES-20` |
| 하류 의존 설계 | `DES-09` (`RELATED_DOWNSTREAM_DEPENDENCY`, 직접 승인 차단 아님) |
| 직접 영향 모듈 | `MOD-ANDROID-USER` |
| 하류 의존 모듈 | `MOD-BACKEND` |
| 승인 전 허용 | 문서 작성·계획 |
| 승인 전 동결 | 이동통신망 분기 구현·정식시험 `BLOCKED_PENDING_BUNDLED_APPROVAL` |

`policy_feature_refs`에서 FP-035를 가리키는 설계 16개는 일반 추적 범위입니다. 이 16개 전부를 승인 차단 대상으로 해석하지 않으며, 직접 영향은 위 3개뿐입니다. 관련 시험 4개는 실제 실행 상태 `NOT_RUN`을 유지합니다.

## 아직 실행하지 않은 5개 검증

{gates}

따라서 현재 상태는 `DRAFT`, `NOT_APPROVED`, `NOT_ELIGIBLE`입니다.

- 상세 JSON: [req-des-tst-integration-report-20260721-r001.json](req-des-tst-integration-report-20260721-r001.json)
- 보고서 내용 지문: `{report['report_content_sha256']}`
- 검증 범위 코드: `{VERIFICATION_SCOPE}`
"""


def build_outputs() -> dict[Path, bytes]:
    report = build_report()
    outputs = {
        REPORT_PATH: _json_bytes(report),
        README_PATH: build_readme(report).encode("utf-8"),
    }
    validate_outputs(outputs)
    return outputs


def validate_outputs(outputs: dict[Path, bytes]) -> None:
    _require(set(outputs) == {REPORT_PATH, README_PATH}, "trace output set differs")
    report = json.loads(outputs[REPORT_PATH])
    validate_report(report)
    readme = outputs[README_PATH].decode("utf-8")
    for phrase in (
        STRUCTURAL_STATUS,
        "실제 앱 기능을 실행해 합격시킨 것이 아니고",
        FP035_NETWORK_ISSUE_ID,
        FP035_CORRECTION_CANDIDATE_ID,
        FP035_REQUIRED_ACTIVATION_EVENT,
        "직접 영향 설계 | 3개",
        "하류 의존 설계 | 1개",
        "RELATED_DOWNSTREAM_DEPENDENCY",
        "정식 산출물 승인 | 0개",
        "`DRAFT`, `NOT_APPROVED`, `NOT_ELIGIBLE`",
        report["report_content_sha256"],
    ):
        _require(phrase in readme, f"trace README is missing: {phrase}")


def generate() -> dict[Path, bytes]:
    outputs = build_outputs()
    for path, payload in outputs.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(payload)
    return outputs


def check() -> dict[Path, bytes]:
    outputs = build_outputs()
    for path, expected in outputs.items():
        _require(path.is_file(), f"generated output is missing: {_repo_path(path)}")
        _require(path.read_bytes() == expected, f"generated output is stale: {_repo_path(path)}")
    return outputs


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify committed outputs without rewriting")
    args = parser.parse_args(argv)
    try:
        outputs = check() if args.check else generate()
        report = json.loads(outputs[REPORT_PATH])
    except (TraceIntegrationError, OSError, KeyError, TypeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    coverage = report["coverage"]
    action = "verified" if args.check else "generated"
    print(
        f"{action} leaf trace report: {coverage['requirement_count']} requirements, "
        f"{coverage['acceptance_condition_count']} acceptance/test links, "
        f"{coverage['design_artifact_count']} designs, {coverage['module_count']} modules; "
        "structural validation only"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
